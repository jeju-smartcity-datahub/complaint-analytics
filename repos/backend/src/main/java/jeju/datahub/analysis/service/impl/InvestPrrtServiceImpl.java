package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.InvestPrrtDataLastVO;
import jeju.datahub.analysis.service.InvestPrrtLotBaseVO;
import jeju.datahub.analysis.service.InvestPrrtLotVO;
import jeju.datahub.analysis.service.InvestPrrtResultVO;
import jeju.datahub.analysis.service.InvestPrrtService;
import jeju.datahub.analysis.service.impl.InvestPrrtStngResolver.InvestPrrtStng;
import jeju.datahub.commoncode.service.CommonCodeService;
import jeju.datahub.commoncode.service.CommonCodeVO;
import lombok.RequiredArgsConstructor;

/**
 * 투자우선순위 분석 서비스 구현.
 *
 * <p>흐름: 기간 확정·검증 → 설정({@link InvestPrrtStngResolver}) → 지표 원값({@link InvestPrrtMapper},
 * 전 지역) → 점수·순위·등급({@link InvestPrrtCalculator}, 전 지역) → 알림·요약(전 지역) →
 * <b>행정구역 필터(표시만)</b>.
 *
 * <h2>🔴 행정구역 필터는 계산이 끝난 뒤에 건다</h2>
 * 백분위·순위는 "전 지역 대상 전체" 가 모집단이다. 필터를 매퍼나 계산기 앞에 걸면 같은 주차장의
 * 점수가 조회 조건마다 달라진다. 그래서 필터는 {@code lots} 를 <b>거르기만</b> 하고, 걸러진 뒤에도
 * {@code rnk}·{@code sggRnk}·{@code crdnExclRnk} 를 다시 매기지 않는다(1부터 연속이 아닐 수 있다).
 * {@code smry}·{@code warnings} 는 필터 <b>전</b> 전체로 만든다.
 *
 * <h2>🔴 적재하지 않는다</h2>
 * 조회는 {@code TB_ANLS_EXCN}·{@code TB_ANLS_RSLT} 에 쓰지 않는다(읽기 전용 트랜잭션). 실행 이력은
 * 보고서로 저장할 때만 남는다.
 *
 * <h2>검증은 전부 400 {@link ResponseStatusException}</h2>
 * 이 경로를 덮는 전역 예외 핸들러가 없다(구역추천 선례). 설정 오류(반영할 가중치 없음·임계 역전)는
 * 리졸버가 409 로 낸다.
 */
@Service
@RequiredArgsConstructor
public class InvestPrrtServiceImpl extends EgovAbstractServiceImpl implements InvestPrrtService {

    private static final Logger log = LoggerFactory.getLogger(InvestPrrtServiceImpl.class);

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    /** "오늘" 의 기준 시간대 — 서버 기본 시간대에 기대지 않는다 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 등급 이름의 출처 공통코드 그룹 */
    private static final String LVL_GROUP_CD = "IVST_PRRT_LVL_CD";

    // 알림 코드 — 4종
    static final String WARN_CRDN_SGG_SKEW = "CRDN_SGG_SKEW";
    static final String WARN_SFTY_CHCK_MISSING = "SFTY_CHCK_MISSING";
    static final String WARN_ACDNT_NO_DATA = "ACDNT_NO_DATA";
    static final String WARN_UTZTN_PARTIAL = "UTZTN_PARTIAL";
    // 알림 코드 — 계산기·리졸버가 드러낸 것을 옮기는 3종(산출물)
    static final String WARN_SFTY_CD_UNKNOWN = "SFTY_CD_UNKNOWN";
    static final String WARN_UTZTN_MISSING = "UTZTN_MISSING";
    static final String WARN_WGVL_NOT_APPLIED = "WGVL_NOT_APPLIED";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 산식 표기에 쓰는 짧은 지표 이름. 🔴 키는 {@link InvestPrrtCalculator#KNOWN_VRBL_CDS} 와 같아야 한다 —
     * 산식이 아는 항에만 이름이 있다(설정의 {@code vrbl_nm} 은 "안전위험도" 처럼 길어 산식 표기에 맞지 않는다).
     */
    private static final Map<String, String> FORMULA_LABELS = Map.of(
            InvestPrrtCalculator.VRBL_SAFETY, "안전",
            InvestPrrtCalculator.VRBL_COMPLAINT, "민원",
            InvestPrrtCalculator.VRBL_USAGE, "이용",
            InvestPrrtCalculator.VRBL_ENFORCE, "단속",
            InvestPrrtCalculator.VRBL_ACCIDENT, "사고");

    private final InvestPrrtMapper investPrrtMapper;
    private final InvestPrrtStngResolver investPrrtStngResolver;
    private final AnlsVrblOvrdResolver anlsVrblOvrdResolver;
    private final InvestPrrtCalculator investPrrtCalculator;
    private final CommonCodeService commonCodeService;

    @Override
    @Transactional(readOnly = true)
    public InvestPrrtResultVO analyze(String bgngYmd, String endYmd, String sggNm, String emdCd) {
        return analyze(bgngYmd, endYmd, sggNm, emdCd, null);
    }

    @Override
    @Transactional(readOnly = true)
    public InvestPrrtResultVO analyze(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                      Map<String, BigDecimal> vrblVls) {
        return compute(bgngYmd, endYmd, sggNm, emdCd, vrblVls).result();
    }

    /**
     * {@inheritDoc}
     *
     * <p>🔴 {@link #compute} 가 이미 계산한 백분위 개수·계수 설정 행·적용값으로 {@code wbk} 를 만든다 — 새 조회가 없다.
     */
    @Override
    @Transactional(readOnly = true)
    public InvestPrrtResultVO analyzeWithWorkbook(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                                  Map<String, BigDecimal> vrblVls) {
        Computed c = compute(bgngYmd, endYmd, sggNm, emdCd, vrblVls);
        c.result().setWbk(InvestPrrtWorkbooks.of(c.ovrd(), c.stng(), c.trgtCnt(),
                c.result().getLots(), c.calc().lessCnts()));
        return c.result();
    }

    /**
     * 조회 1회분 — 결과와, 엑셀 재계산 데이터({@code wbk})를 만들 때 필요한 같은 실행의 값.
     *
     * @param trgtCnt 계산기에 넣은 대상 수({@code bases.size()} — 🔴 행정구역 필터 <b>전</b>)
     */
    private record Computed(InvestPrrtResultVO result, InvestPrrtCalculator.Result calc,
                            AnlsVrblOvrd ovrd, InvestPrrtStng stng, int trgtCnt) {
    }

    /**
     * {@link #analyze(String, String, String, String, Map)}·{@link #analyzeWithWorkbook} 공용 본문(구역추천 {@code compute}/{@code Computed} 선례).
     * 종전 5인자 {@code analyze} 본문을 그대로 옮겼다.
     */
    private Computed compute(String bgngYmd, String endYmd, String sggNm, String emdCd,
                             Map<String, BigDecimal> vrblVls) {
        // 1) 기간 — 형식·순서·상한을 SQL 로 흘려보내기 전에 끊는다
        LocalDate[] prd = resolvePrd(bgngYmd, endYmd);
        LocalDate bgng = prd[0];
        LocalDate end = prd[1];
        String sggFilter = blankToNull(sggNm);
        String emdFilter = blankToNull(emdCd);

        // 2) 설정 (가중치 + 계수) + 일시 입력. 입력 오류는 400, 설정 오류는 409
        AnlsVrblOvrd ovrd = anlsVrblOvrdResolver.resolve(AnlsVrblScope.INVEST, vrblVls);
        InvestPrrtStng stng = investPrrtStngResolver.resolve(ovrd);

        // 3) 지표 원값 — 🔴 전 지역(행정구역 조건 없음)
        InvestPrrtQueryParam param = new InvestPrrtQueryParam();
        param.setBgngDt(bgng.atStartOfDay());
        param.setEndDtExcl(end.plusDays(1).atStartOfDay());   // 반열림 상한 = 종료일 다음 날 00:00
        YearMonth utztnBgng = utztnBgngYm(bgng, end);   // 이용률 집계 달 — 시작일이 1일이 아니면 시작 달 제외
        param.setBgngYm(utztnBgng.format(YM));
        param.setEndYm(end.format(YM));
        param.setRdsM(stng.altmntRngVl());
        List<InvestPrrtLotBaseVO> bases = investPrrtMapper.selectLotBase(param);

        // 4) 점수·순위·등급 — 🔴 전 지역. 종합점수는 계산기 값을 그대로 쓴다(지표 점수로 다시 합산하지 않는다)
        InvestPrrtCalculator.Result calc = investPrrtCalculator.calculate(
                bases, stng.weights(), stng.upGrdCrtrScr(), stng.mdGrdCrtrScr());
        List<InvestPrrtLotVO> all = calc.lots();

        Long acdntCnt = investPrrtMapper.selectAcdntCnt(param);
        // 지표별 최근 자료일(종료일 이하 전체의 최댓값 · 화면 헤더 전용)
        InvestPrrtDataLastVO dataLast = investPrrtMapper.selectDataLastDt(param);
        // UTZTN_PARTIAL 분모 — 이용률 집계 달 수(시작 달 규칙이 bgngYm 과 같아야 한다)
        int prdMmCnt = (int) ChronoUnit.MONTHS.between(utztnBgng, YearMonth.from(end)) + 1;

        // 5) 조립 — smry·warnings 는 필터 전 전체 기준
        InvestPrrtResultVO result = new InvestPrrtResultVO();
        result.setBgngYmd(bgng.format(YMD));
        result.setEndYmd(end.format(YMD));
        // 화면 전용(InfoTip·헤더). 🔴 보고서 스냅샷(buildRsltJson)에는 싣지 않는다
        result.setUtztnBgngYm(utztnBgng.format(YM));
        result.setUtztnEndYm(end.format(YM));
        result.setSftyCrtrYmd(end.format(YMD));
        if (dataLast != null) {
            result.setCvlcptLastYmd(dataLast.getCvlcptLastYmd());
            result.setUtztnLastYm(dataLast.getUtztnLastYm());
            result.setSftyChckLastYmd(dataLast.getSftyChckLastYmd());
            result.setAcdntLastYmd(dataLast.getAcdntLastYmd());
        }
        result.setScoreFormula(scoreFormula(stng.appliedWeights()));
        result.setWeights(new ArrayList<>(stng.appliedWeights()));
        result.setGradeCriteria(gradeCriteria(stng));
        result.setCrtrVls(new InvestPrrtResultVO.CrtrVls(plain(stng.altmntRngVl()), stng.indctPkltCnt()));
        result.setVrblAjmt(ovrd.toAjmtJson());
        result.setWarnings(warnings(bases, all, calc, stng, acdntCnt, prdMmCnt));
        result.setSmry(new InvestPrrtResultVO.Smry(
                all.size(),
                (int) all.stream().filter(l -> InvestPrrtCalculator.LVL_HIGH.equals(l.getPrrtLvlCd())).count(),
                acdntCnt));

        // 6) 🔴 행정구역 필터는 여기서 — 표시만 거른다(순위를 다시 매기지 않는다)
        List<InvestPrrtLotVO> shown = new ArrayList<>(all.size());
        for (InvestPrrtLotVO lot : all) {
            if ((sggFilter == null || sggFilter.equals(lot.getSggNm()))
                    && (emdFilter == null || emdFilter.equals(lot.getEmdCd()))) {
                shown.add(lot);
            }
        }
        result.setLots(shown);
        return new Computed(result, calc, ovrd, stng, bases == null ? 0 : bases.size());
    }

    // ------------------------------------------------------------------ 기간

    /**
     * 이용률 집계 시작 달. 시작 달은 <b>시작일이 1일일 때만</b> 포함한다 — "최근 N개월" 이 N 개 달이 되게
     * (= A · 오늘이 1일이면 기본 창이 N+1 개 달이 되는 예외는 허용).
     * 🔴 창이 한 달 안(예: 11-05 ~ 11-20)이면 시작 달이 끝 달을 넘어 이용률이 통째로 빈다
     * → 끝 달로 clamp 한다(그 달 하나만 본다 — 종전 동작과 같다).
     * 🔴 private 으로 두지 않는다 — 같은 패키지 단위 테스트가 직접 부른다.
     */
    static YearMonth utztnBgngYm(LocalDate bgng, LocalDate end) {
        YearMonth endYm = YearMonth.from(end);
        YearMonth ym = YearMonth.from(bgng);
        YearMonth start = bgng.getDayOfMonth() == 1 ? ym : ym.plusMonths(1);
        return start.isAfter(endYm) ? endYm : start;
    }

    /** 🔴 "오늘" 은 여기 한 곳에서만 읽는다 — 여러 곳에서 읽으면 자정 근처에서 시작일·종료일이 갈린다. */
    LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /**
     * 기간을 확정한다. 둘 다 없으면 <b>오늘에서 12개월(1년) 전 ~ 오늘</b>(오늘 포함), 한쪽만 오면 400.
     *
     * @return {@code [시작일, 종료일]} (양끝 포함)
     */
    private LocalDate[] resolvePrd(String bgngYmd, String endYmd) {
        boolean noBgng = blankToNull(bgngYmd) == null;
        boolean noEnd = blankToNull(endYmd) == null;
        if (noBgng && noEnd) {
            LocalDate today = today();
            return new LocalDate[] {today.minusMonths(DEFAULT_PRD_MONTHS), today};
        }
        if (noBgng || noEnd) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "bgngYmd 와 endYmd 는 함께 주거나 함께 생략해야 합니다 (YYYYMMDD)");
        }
        LocalDate bgng = parseYmd(bgngYmd, "bgngYmd");
        LocalDate end = parseYmd(endYmd, "endYmd");
        if (end.isBefore(bgng)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endYmd 가 bgngYmd 보다 빠릅니다: " + bgng + " ~ " + end);
        }
        // 상한은 창이 닿는 달 수로 센다(구역추천과 같은 세는 법 · 입력 검증) — 이용률 집계 달(utztnBgngYm)과 별개다
        long months = ChronoUnit.MONTHS.between(bgng.withDayOfMonth(1), end.withDayOfMonth(1)) + 1;
        if (months > MAX_PRD_MONTHS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "조회 기간은 " + MAX_PRD_MONTHS + "개월을 넘을 수 없습니다: "
                            + bgng + " ~ " + end + " (" + months + "개월)");
        }
        return new LocalDate[] {bgng, end};
    }

    private static LocalDate parseYmd(String value, String field) {
        String v = value.trim();
        // BASIC_ISO_DATE 는 뒤에 오프셋(20260101+0900)이 붙어도 통과시킨다 — 길이로 먼저 끊는다
        if (v.length() == 8) {
            try {
                return LocalDate.parse(v, YMD);
            } catch (DateTimeParseException e) {
                // 아래에서 400 — 같은 형식 오류 경로로 합친다 (4.2)
                log.debug("날짜 파싱 실패 — 400 으로 처리: field={}", field);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                field + " 는 YYYYMMDD 형식의 실재하는 날짜여야 합니다: " + value);
    }

    // ------------------------------------------------------------------ 조립

    /** 산식 표기 — 실제 반영한 변수만, 설정의 정렬 순서대로. */
    private static String scoreFormula(List<AnalysisWeightVO> applied) {
        List<String> terms = new ArrayList<>(applied.size());
        boolean hasSafety = false;
        boolean hasPercentile = false;
        for (AnalysisWeightVO w : applied) {
            terms.add(FORMULA_LABELS.getOrDefault(w.getVrblCd(), w.getVrblCd())
                    + " " + plain(w.getStngVl()).toPlainString() + "%");
            if (InvestPrrtCalculator.VRBL_SAFETY.equals(w.getVrblCd())) {
                hasSafety = true;
            } else {
                hasPercentile = true;
            }
        }
        String note = hasSafety && hasPercentile ? "안전은 점검 결과 점수 · 그 밖은 백분위 점수"
                : hasSafety ? "점검 결과 점수" : "백분위 점수";
        return String.join(" + ", terms) + " (" + note + ")";
    }

    /**
     * 등급 기준 — 임계는 리졸버(분석변수), 이름은 공통코드 {@code IVST_PRRT_LVL_CD}.
     * 공통코드에 이름이 없으면(행 없음·사용안함) 코드값을 이름 자리에 둔다 — 이름을 소스에 박지 않는다.
     */
    private List<InvestPrrtResultVO.GradeCriterion> gradeCriteria(InvestPrrtStng stng) {
        Map<String, String> names = new HashMap<>();
        List<CommonCodeVO> codes = commonCodeService.selectCommonCodesByGroup(LVL_GROUP_CD);
        if (codes != null) {
            for (CommonCodeVO c : codes) {
                names.put(c.getCdVl(), c.getCdNm());
            }
        }
        List<InvestPrrtResultVO.GradeCriterion> out = new ArrayList<>(3);
        out.add(criterion(InvestPrrtCalculator.LVL_HIGH, names, stng.upGrdCrtrScr()));
        out.add(criterion(InvestPrrtCalculator.LVL_MID, names, stng.mdGrdCrtrScr()));
        out.add(criterion(InvestPrrtCalculator.LVL_LOW, names, BigDecimal.ZERO));
        return out;
    }

    private static InvestPrrtResultVO.GradeCriterion criterion(String cd, Map<String, String> names,
                                                               BigDecimal minScr) {
        return new InvestPrrtResultVO.GradeCriterion(cd, names.getOrDefault(cd, cd), plain(minScr));
    }

    /**
     * 자료의 한계 알림. 🔴 <b>필터 전 전체 대상</b>으로 판정한다 — 한계는 조회 조건과 무관한 사실이다.
     *
     * @param bases    매퍼 원값(전 지역) — 이용패턴 달 수는 여기에만 있다
     * @param all      계산 결과(전 지역)
     * @param prdMmCnt 이용률 집계 달 수(시작일이 1일이 아니면 시작 달 제외)
     */
    private static List<InvestPrrtResultVO.Warning> warnings(List<InvestPrrtLotBaseVO> bases,
                                                            List<InvestPrrtLotVO> all,
                                                            InvestPrrtCalculator.Result calc,
                                                            InvestPrrtStng stng,
                                                            Long acdntCnt, int prdMmCnt) {
        List<InvestPrrtResultVO.Warning> out = new ArrayList<>();

        // 단속이 1건 이상인 대상 주차장이 한 시에만 있다 → 두 시의 순위를 서로 비교할 수 없다.
        // 대상이 애초에 한 시뿐이면 치우침이 아니므로 알리지 않는다.
        // 🔴 단속(ENFORCE)이 실제 반영됐을 때만 낸다(INVEST 단속 설정 행 삭제). 단속이 빠진
        //    상태의 단속 편중 경고는 순위가 단속 때문에 갈린 것처럼 오해를 부른다.
        Set<String> sggAll = new TreeSet<>();
        Set<String> sggWithCrdn = new TreeSet<>();
        for (InvestPrrtLotVO l : all) {
            if (l.getSggNm() == null) {
                continue;
            }
            sggAll.add(l.getSggNm());
            if (l.getCrdnCnt() != null && l.getCrdnCnt() > 0) {
                sggWithCrdn.add(l.getSggNm());
            }
        }
        if (stng.weights().containsKey(InvestPrrtCalculator.VRBL_ENFORCE)
                && sggAll.size() > 1 && sggWithCrdn.size() == 1) {
            out.add(new InvestPrrtResultVO.Warning(WARN_CRDN_SGG_SKEW,
                    "주변 단속이 1건 이상인 주차장이 " + sggWithCrdn.iterator().next() + "에만 있습니다. "
                            + "시군구 사이의 순위는 서로 비교할 수 없습니다(같은 시 안 등수 sggRnk · 단속을 뺀 순위 "
                            + "crdnExclRnk 를 함께 보세요).", null));
        }

        if (calc.sftyChckMissingCnt() > 0) {
            out.add(new InvestPrrtResultVO.Warning(WARN_SFTY_CHCK_MISSING,
                    "안전점검 기록이 없는 주차장 " + calc.sftyChckMissingCnt() + "곳을 안전 0점으로 계산했습니다.",
                    calc.sftyChckMissingCnt()));
        }
        if (!calc.unknownSftyCds().isEmpty()) {
            int cnt = calc.unknownSftyCds().values().stream().mapToInt(Integer::intValue).sum();
            out.add(new InvestPrrtResultVO.Warning(WARN_SFTY_CD_UNKNOWN,
                    "점수 대응표에 없는 안전점검 결과 코드 " + calc.unknownSftyCds().keySet() + " 를 가진 주차장 "
                            + cnt + "곳을 안전 0점으로 계산했습니다.", cnt));
        }

        // D1=A — 사고 자료가 없어도 사고 항을 빼지 않고 전부 0 으로 반영한다
        if (acdntCnt == null) {
            out.add(new InvestPrrtResultVO.Warning(WARN_ACDNT_NO_DATA,
                    "사고이력 자료가 없어 모든 주차장의 사고 점수를 0으로 계산했습니다.", null));
        }

        // 집계 달의 일부만 이용패턴이 있다(0 < 달 수 < 이용률 집계 달 수) → 있는 달만 평균했다
        int partial = 0;
        for (InvestPrrtLotBaseVO b : bases) {
            int mm = b.getUtztnMmCnt() == null ? 0 : b.getUtztnMmCnt();
            if (mm > 0 && mm < prdMmCnt) {
                partial++;
            }
        }
        if (partial > 0) {
            out.add(new InvestPrrtResultVO.Warning(WARN_UTZTN_PARTIAL,
                    "이용률 집계 " + prdMmCnt + "개월 중 일부 달에만 이용패턴이 있는 주차장이 " + partial
                            + "곳입니다. 회전율은 자료가 있는 달만 평균했습니다.", partial));
        }
        if (calc.utztnMissingCnt() > 0) {
            out.add(new InvestPrrtResultVO.Warning(WARN_UTZTN_MISSING,
                    "기간에 이용패턴이 한 달도 없는 주차장 " + calc.utztnMissingCnt()
                            + "곳을 이용 0점으로 계산했습니다(dayAvgTrnvrRt 는 null).", calc.utztnMissingCnt()));
        }

        // 켜진 가중치 변수 중 산식이 모르는 것이 있으면, 반영한 가중치 합이 100 에 못 미친다
        // (종합점수는 반영한 합으로 나눠 0~100 을 유지한다)
        BigDecimal sum = BigDecimal.ZERO;
        for (AnalysisWeightVO w : stng.appliedWeights()) {
            sum = sum.add(w.getStngVl());
        }
        if (sum.compareTo(HUNDRED) < 0) {
            out.add(new InvestPrrtResultVO.Warning(WARN_WGVL_NOT_APPLIED,
                    "사용 중인 가중치 변수 가운데 점수 산식에 없는 것이 있어, 반영한 가중치 합이 "
                            + plain(sum).toPlainString() + "% 입니다. 종합점수는 반영한 변수의 가중치 합으로 나눠 계산했습니다.",
                    null));
        }
        return out;
    }

    /** 표기 정리 — 꼬리 0 을 걷는다({@code 80.0000 → 80}). 지수 표기({@code 8E+1})가 되지 않게 스케일을 0 이상으로 둔다. */
    private static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
