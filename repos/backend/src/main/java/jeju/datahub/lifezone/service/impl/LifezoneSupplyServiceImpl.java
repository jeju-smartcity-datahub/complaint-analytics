package jeju.datahub.lifezone.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnalysisWeightService;
import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.analysis.service.impl.AnlsVrblOvrdResolver;
import jeju.datahub.analysis.service.impl.ZoneNameResolver;
import jeju.datahub.analysis.service.impl.ZoneNameResolver.ZoneName;
import jeju.datahub.common.DateRanges;
import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneSupply;
import jeju.datahub.lifezone.service.LifezoneSupplyCellVO;
import jeju.datahub.lifezone.service.LifezoneSupplyGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneSupplyRequest;
import jeju.datahub.lifezone.service.LifezoneSupplyResultVO;
import jeju.datahub.lifezone.service.LifezoneSupplyService;
import jeju.datahub.lifezone.service.LifezoneSupplyWorkbookCellVO;
import jeju.datahub.lifezone.service.LifezoneSupplyWorkbookVO;
import jeju.datahub.lifezone.service.LifezoneTarget;

/**
 * 생활권 수급 분석 구현.
 *
 * <p><b>이 화면이 내는 것이 무엇인지</b> — 🔴 "주차 부족 면수" 가 <b>아니다.</b> 주차 수요 자료
 * (등록차량·법정주차대수·실태조사)가 없어 확보율을 계산할 수 없다. 낼 수 있는 최대 표현은
 * <b>"공영주차 공급 대비 민원 발생량"</b> 이다.
 *
 * <p>🔁 <b>사분면(P80·P70) → 부족률 등간격 5단계(순위 백분위 개정).</b> 공간 집중 분석의
 * "수요 부족 지역"(B)과 같은 산식(부족률 = 민원 ÷ (면수 + 민원))을 <b>단위만 격자로</b> 옮겼다.
 *
 * <p><b>왜 {@link LifezoneAnalysisServiceImpl} 을 확장하지 않았나</b> — 세 군데가 다르다.
 * <ul>
 *   <li>격자: {@code TB_GRID} ↔ <b>계산 격자</b>. 200·300m 가 마스터에 없어 계산으로 냈다.
 *       목록은 민원건수와 같아졌다(1000/500/250/100) — 계산 방식은 그대로다.</li>
 *   <li>결과 형태: 값 경계 5등급 ↔ <b>부족률 고정 경계 5등급</b>(종전 범주형 4사분면).
 *       등급 코드는 같은 {@link LifezoneGrade} 이지만 경계·요약·셀 지표가 다르다.</li>
 *   <li>민원 범위: 미분류({@code '99'}) 제외 ↔ 🔁 <b>주차 민원 01~05 + 월평균 최소 기준</b>.</li>
 * </ul>
 * 🔁 <b>기간 컬럼은 2026-09-13 부터 같다</b>({@code ocrn_dt}). 처음에는 설계 결정을 따라
 * {@code dclr_ymd} 를 썼으나 그 컬럼이 {@code NULL} 인 행이 8,738건 들어와 통째로 빠지는 문제가
 * 생겼다 — 사정은 {@link LifezoneSupplyQueryParam} 에 있다.
 *
 * <p><b>계산 순서</b>
 * <pre>
 *   ① 매퍼: 정제 → 격자 배정 → 격자별 민원·공급 집계 → 월평균 최소 기준   (SQL)
 *   ② 매퍼: 부족률·수요지수 · 부족 순위 row_number() · 모집단 수 N        (SQL)
 *   ⓪ 설정: 최소 월평균 민원·경계 4개를 설정 표에서 읽는다               (여기 — 요청마다 1회)
 *   ③ 등급: 부족률 경계(기본 80/60/40/20%) 포함(≥), 반올림 전 정수 비교 (여기)
 *   ④ 요약: 등급별 격자·민원·공급 0 격자 수 → 범례 5행(lgndTxt 서버 조립) (여기)
 * </pre>
 *
 * <p>🔴 <b>등급은 절대 경계다</b> — 같은 부족률은 격자크기·기간·지역과 무관하게 같은 등급이다.
 * 그 대가로 심각 수가 상위 10% 로 고정되지 않는다(공급 0 격자는 전부 심각). 순위({@code rnk})는
 * 리스트 순서에만 쓴다 — 별도 공식으로 다시 세지 않고 판정 결과만 센다.
 *
 * <p>🔁 <b>경계·최소 기준은 설정 표({@code tb_anls_vrbl_stng} LIFEZONE/SPLY_DMND 5행)가 정본이다.</b>
 * 혼잡도({@code LifezoneCngstServiceImpl#readCoefficients})와 같이 <b>요청마다</b> {@link AnalysisWeightService#coefficient}
 * 로 읽는다 — 🔴 캐시가 없어 관리 화면에서 저장하면 다음 조회부터 바로 반영된다. 한 요청 안에서는 한 번만 읽어
 * 매퍼 파라미터·판정·범례·응답 {@code minMnthAvgCvlcpt} 가 같은 값을 쓴다(중간에 저장돼도 갈리지 않는다).
 */
@Service
public class LifezoneSupplyServiceImpl extends EgovAbstractServiceImpl implements LifezoneSupplyService {

    private static final Logger log = LoggerFactory.getLogger(LifezoneSupplyServiceImpl.class);

    private static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private final LifezoneSupplyMapper lifezoneSupplyMapper;
    private final LifezoneRegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final AnalysisWeightService analysisWeightService;
    private final ZoneNameResolver zoneNameResolver;
    private final AnlsVrblOvrdResolver anlsVrblOvrdResolver;

    public LifezoneSupplyServiceImpl(LifezoneSupplyMapper lifezoneSupplyMapper,
                                     LifezoneRegionResolver regionResolver,
                                     ObjectMapper objectMapper,
                                     AnalysisWeightService analysisWeightService,
                                     ZoneNameResolver zoneNameResolver,
                                     AnlsVrblOvrdResolver anlsVrblOvrdResolver) {
        this.lifezoneSupplyMapper = lifezoneSupplyMapper;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.analysisWeightService = analysisWeightService;
        this.zoneNameResolver = zoneNameResolver;
        this.anlsVrblOvrdResolver = anlsVrblOvrdResolver;
    }

    /** 엑셀 후보 기준 N 폴백 — 설정 행 {@code min_vl} 을 읽지 못했을 때 */
    private static final int CAND_MIN_MNTH_FALLBACK = 1;

    /** 이번 요청에 적용한 기준 — 최소 월평균 민원 + 등급 경계 4개. 요청 1회 읽기의 결과다 */
    private record Crtr(int minMnthAvgCvlcpt, LifezoneSupply.GrdBndry bndry) {
    }

    /**
     * 검증·정규화를 마친 조회 조건 한 벌 — {@link #analyze} 와 {@link #analyzeWithWorkbook} 공용.
     *
     * @param param 매퍼 파라미터(적용 최소 월평균 민원까지 채운 상태)
     */
    private record PreparedQuery(int gridSz, LocalDate bgngYmd, LocalDate endYmd,
                                 LifezoneRegionResolver.Region region, String emdCd,
                                 AnlsVrblOvrd ovrd, Crtr crtr, LifezoneSupplyQueryParam param) {
    }

    @Override
    @Transactional(readOnly = true)
    public LifezoneSupplyResultVO analyze(LifezoneSupplyRequest req) {
        return run(prepare(req));
    }

    /**
     * {@inheritDoc}
     *
     * <p>🔴 후보는 <b>같은 SQL</b>({@code selectSupplyCells})을 최소 월평균 민원 = {@code min_vl} 로 한 번 더 부른 결과다 —
     * SQL 을 복사하지 않는다. 순위 정렬 키(면수÷민원 ASC, 민원 DESC, gx, gy)가 계수와 무관해서 엑셀이 "포함된 것만 누적 번호" 로
     * {@code rnk} 를 재현한다.
     */
    @Override
    @Transactional(readOnly = true)
    public LifezoneSupplyWithWorkbook analyzeWithWorkbook(LifezoneSupplyRequest req) {
        PreparedQuery q = prepare(req);
        LifezoneSupplyResultVO result = run(q);
        return new LifezoneSupplyWithWorkbook(result, buildWorkbook(q, result.getCells()));
    }

    /** ① 검증·정규화 + 일시 입력 검증 + ⓪ 설정 1회 읽기 → 매퍼 파라미터. */
    private PreparedQuery prepare(LifezoneSupplyRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 조건이 없습니다");
        }
        int gridSz = validateGridSz(req.getGridSz());
        LocalDate bgngYmd = parseYmd(req.getBgngYmd(), "bgngYmd");
        LocalDate endYmd = parseYmd(req.getEndYmd(), "endYmd");
        DateRanges.validate(bgngYmd, endYmd);

        String emdCd = LifezoneRegionResolver.normalizeKey(req.getEmdCd(), "emdCd");
        String sggNm = LifezoneRegionResolver.normalizeKey(req.getSggNm(), "sggNm");
        LifezoneRegionResolver.Region region = regionResolver.resolve(sggNm, emdCd);
        // 일시 입력 — 보고서 경로의 Map 이 먼저이고, 없으면 쿼리 JSON 이다. 설정 표에는 쓰지 않는다
        Map<String, BigDecimal> vrblVls = req.getVrblVlsMap() != null
                ? req.getVrblVlsMap()
                : AnlsVrblOvrd.parse(req.getVrblVls());
        AnlsVrblOvrd ovrd = anlsVrblOvrdResolver.resolve(AnlsVrblScope.LIFEZONE_SPLY, vrblVls);

        LifezoneSupplyQueryParam param = new LifezoneSupplyQueryParam();
        param.setGridSz(gridSz);
        param.setBgngDt(bgngYmd.atStartOfDay());
        param.setEndDtExcl(endYmd.plusDays(1).atStartOfDay());   // 당일 포함 → 반열림 상한
        param.setEmdCd(emdCd);
        param.setSggCd(region.sggCd());
        // 설정 표를 이 요청에서 한 번만 읽는다(매퍼·판정·범례·응답이 같은 값을 쓴다)
        // 🔴 입력값이 있으면 그 값이 매퍼·판정·범례·응답에 함께 닿는다(보고서 crtrVls 도 이 값 — 적용값)
        Crtr crtr = readCrtr(ovrd);
        // 월평균 최소 기준. 매퍼가 count(*) × 30 >= 최소 × 조회 일수 로 정수 비교한다
        param.setPrdDays(LifezoneSupply.prdDaysOf(bgngYmd, endYmd));
        param.setMinMnthAvgCvlcpt(crtr.minMnthAvgCvlcpt());
        return new PreparedQuery(gridSz, bgngYmd, endYmd, region, emdCd, ovrd, crtr, param);
    }

    /** ①~④ 집계 → 등급 → 응답. {@link #analyze} 의 본체다. */
    private LifezoneSupplyResultVO run(PreparedQuery q) {
        List<LifezoneSupplyRow> rows = selectRows(q.param());
        List<LifezoneSupplyCellVO> cells = classify(rows, q.gridSz(), q.crtr().bndry());
        LifezoneSupplyResultVO result = buildResult(q.gridSz(), q.bgngYmd(), q.endYmd(), q.region(), q.emdCd(),
                cells, q.crtr());
        result.setVrblAjmt(q.ovrd().toAjmtJson());
        return result;
    }

    /** 🔴 {@code selectSupplyCells} 호출은 여기 한 곳이다 — 분석·엑셀 후보가 파라미터만 바꿔 같은 SQL 을 쓴다. */
    private List<LifezoneSupplyRow> selectRows(LifezoneSupplyQueryParam param) {
        List<LifezoneSupplyRow> rows = lifezoneSupplyMapper.selectSupplyCells(param);
        return rows == null ? List.of() : rows;
    }

    /* ── 엑셀 재계산 데이터 ──────────────── */

    /**
     * 후보 격자 + 적용 계수 → {@code wbk}.
     *
     * <p>구역명: ① 응답 셀에 같은 {@code gridId} 가 있으면 그 이름(목록 대상은 resolver 결과 · 그 밖은 캐시)
     * ② 없으면(현재 N 에서 빠진 격자) 행의 캐시 이름 ③ 둘 다 없으면 {@code null}(화면·엑셀이 읍면동명 → 격자ID 로 폴백).
     * 🔴 resolver 를 다시 태우지 않는다 — 외부 호출은 분석 세션(상한 50)에서 이미 끝났고, 후보 전량을 태우면 상한이 소진된다.
     */
    private LifezoneSupplyWorkbookVO buildWorkbook(PreparedQuery q, List<LifezoneSupplyCellVO> shownCells) {
        LifezoneSupplyQueryParam candParam = copyOf(q.param());
        candParam.setMinMnthAvgCvlcpt(candidateMinMnth(q.ovrd()));
        List<LifezoneSupplyRow> rows = selectRows(candParam);

        Map<String, String> shownNames = new HashMap<>();
        for (LifezoneSupplyCellVO cell : shownCells) {
            if (cell.getCntrPoiNm() != null) {
                shownNames.put(cell.getGridId(), cell.getCntrPoiNm());
            }
        }
        List<LifezoneSupplyWorkbookCellVO> cands = new ArrayList<>(rows.size());
        for (LifezoneSupplyRow row : rows) {
            LifezoneSupplyWorkbookCellVO cand = new LifezoneSupplyWorkbookCellVO();
            cand.setGridId(gridIdOf(q.gridSz(), row.getGx(), row.getGy()));
            String shown = shownNames.get(cand.getGridId());
            cand.setCntrPoiNm(shown != null ? shown : row.getCacheZoneNm());
            cand.setEmdNm(row.getEmdNm());
            cand.setCvlcptCnt(row.getCvlcptCnt());
            cand.setPkltCpctySum(row.getPkltCpctySum());
            cand.setPkltCnt(row.getPkltCnt());
            cands.add(cand);
        }

        Map<String, BigDecimal> aplcn = new HashMap<>();
        aplcn.put(LifezoneSupply.VRBL_MIN_MNTH_AVG_CVLCPT, BigDecimal.valueOf(q.crtr().minMnthAvgCvlcpt()));
        List<Integer> bndrys = q.crtr().bndry().toList();
        for (int i = 0; i < bndrys.size(); i++) {
            aplcn.put(LifezoneSupply.VRBL_GRD_BNDRY_PREFIX + (i + 1), BigDecimal.valueOf(bndrys.get(i)));
        }

        LifezoneSupplyWorkbookVO wbk = new LifezoneSupplyWorkbookVO();
        wbk.setPrdDays(q.param().getPrdDays());
        wbk.setVrbls(LifezoneWorkbookVrbls.of(AnlsVrblScope.LIFEZONE_SPLY, q.ovrd(), aplcn, true));
        wbk.setCells(cands);
        log.debug("수급 엑셀 후보: N={} 후보={}건 (분석 N={} 셀={}건)",
                candParam.getMinMnthAvgCvlcpt(), cands.size(), q.crtr().minMnthAvgCvlcpt(), shownCells.size());
        return wbk;
    }

    /**
     * 후보 기준 N = 설정 행 {@code SPLY_MIN_MNTH_AVG_CVLCPT.min_vl}(상수가 아니다).
     * 행·값이 없거나 1 미만·정수 아님이면 WARN 1줄 + {@link #CAND_MIN_MNTH_FALLBACK}.
     */
    private static int candidateMinMnth(AnlsVrblOvrd ovrd) {
        AnalysisWeightVO row = ovrd.row(LifezoneSupply.VRBL_MIN_MNTH_AVG_CVLCPT);
        BigDecimal minVl = row == null ? null : row.getMinVl();
        if (minVl != null && minVl.signum() > 0 && minVl.stripTrailingZeros().scale() <= 0) {
            try {
                return minVl.intValueExact();
            } catch (ArithmeticException e) {
                // 아래 폴백 — int 범위를 넘는 하한은 쓰지 않는다 (4.2)
                log.debug("최소 월평균 민원 하한이 int 범위 밖 — 폴백: minVl={}", minVl);
            }
        }
        log.warn("수급 최소 월평균 민원 범위 하한(min_vl)을 쓸 수 없어 엑셀 후보를 {} 기준으로 냅니다: {}",
                CAND_MIN_MNTH_FALLBACK, minVl);
        return CAND_MIN_MNTH_FALLBACK;
    }

    private static LifezoneSupplyQueryParam copyOf(LifezoneSupplyQueryParam src) {
        LifezoneSupplyQueryParam copy = new LifezoneSupplyQueryParam();
        BeanUtils.copyProperties(src, copy);
        return copy;
    }

    /* ── 0. 설정 ─────────────────────────────────── */

    /**
     * 최소 월평균 민원·등급 경계 4개를 설정 표에서 읽는다.
     *
     * <p>{@link AnalysisWeightService#coefficient} 를 5번 부른다 — 혼잡도 {@code readCoefficients} 선례이고,
     * 행 누락·{@code use_yn='N'} 의 폴백({@code bsc_stng_vl} → WARN)을 그 계약 그대로 쓰기 위해서다
     * (단건 조회 창구를 새로 내면 모듈 간 계약 인터페이스가 바뀐다).
     *
     * <p>🔴 <b>읽은 값이 쓸 수 없으면 분석을 죽이지 않고 상수 기본값으로 진행하며 WARN 1줄</b>을 남긴다
     * ({@code coefficient} 의 "설정 한 행 때문에 화면이 500 이 되면 안 된다" 와 같은 규약):
     * <ul>
     *   <li>소수 — 저장 검증이 400 으로 막으므로 DB 를 직접 고친 경우뿐이다. {@code HALF_UP} 으로 반올림해 쓴다</li>
     *   <li>경계 4개가 엄격 오름차순·1~99 가 아니다 — 등급이 뒤집히므로 경계 4개 전체를 기본값({@link LifezoneSupply.GrdBndry#DEFAULT})으로</li>
     *   <li>최소 월평균 민원 1 미만 — 기본값 {@link LifezoneSupply#MIN_MNTH_AVG_CVLCPT}</li>
     * </ul>
     * ⚠️ {@code coefficient} 는 행이 없고 기본설정값도 없으면 {@code 1.0} 을 준다 — 최소 기준은 1건으로 받아들여지고,
     * 경계는 {@code 1,1,1,1} 이 되어 단조 검사에 걸려 기본값으로 떨어진다.
     */
    private Crtr readCrtr(AnlsVrblOvrd ovrd) {
        int minMnth = intOf(ovrd, LifezoneSupply.VRBL_MIN_MNTH_AVG_CVLCPT);
        if (minMnth < 1) {
            log.warn("수급 최소 월평균 민원 설정값이 1 미만이라 기본값 {} 로 진행합니다: {}",
                    LifezoneSupply.MIN_MNTH_AVG_CVLCPT, minMnth);
            minMnth = LifezoneSupply.MIN_MNTH_AVG_CVLCPT;
        }
        int[] b = new int[4];
        for (int i = 0; i < b.length; i++) {
            b[i] = intOf(ovrd, LifezoneSupply.VRBL_GRD_BNDRY_PREFIX + (i + 1));
        }
        LifezoneSupply.GrdBndry bndry;
        try {
            bndry = new LifezoneSupply.GrdBndry(b[0], b[1], b[2], b[3]);
        } catch (IllegalArgumentException e) {
            log.warn("수급 등급 경계 설정값을 쓸 수 없어 기본값 {} 로 진행합니다: {}",
                    LifezoneSupply.GrdBndry.DEFAULT.toList(), e.getClass().getSimpleName());
            log.debug("수급 등급 경계 설정값을 쓸 수 없어 기본값 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만
            bndry = LifezoneSupply.GrdBndry.DEFAULT;
        }
        return new Crtr(minMnth, bndry);
    }

    /**
     * 설정값 1건 → 정수. 소수면 {@code HALF_UP} 반올림 + WARN 1줄(저장 검증 밖 — DB 직접 수정)
     *
     * <p>일시 입력이 있으면 그 값을 쓴다. 입력은 {@code AnlsVrblOvrdResolver} 가 정수·범위·경계 순서를
     * 이미 검증했다. 🔴 {@code coefficient()} 의 폴백 계약은 그대로다 — 입력이 없는 변수만 그 경로를 탄다.
     */
    private int intOf(AnlsVrblOvrd ovrd, String vrblCd) {
        BigDecimal value = ovrd.value(vrblCd,
                () -> analysisWeightService.coefficient(LifezoneSupply.STNG_ANLS_TYPE_CD, vrblCd));
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(value) != 0) {
            log.warn("수급 설정값이 정수가 아니라 반올림해 씁니다: vrblCd={} 값={} → {}",
                    vrblCd, value.toPlainString(), rounded.toPlainString());
        }
        try {
            return rounded.intValueExact();
        } catch (ArithmeticException e) {
            // int 범위 밖 — 범위 CHECK(1~99·1~30)가 있어 도달하지 않는다(방어). 0 은 위 검사가 기본값으로 돌린다
            log.warn("수급 설정값이 정수 범위를 벗어났습니다: vrblCd={} 값={}", vrblCd, value.toPlainString());
            return 0;
        }
    }

    /* ── 1. 검증 ──────────────────────────────────────── */

    private static int validateGridSz(int gridSz) {
        if (!LifezoneSupply.GRID_SZS.contains(gridSz)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "격자크기는 " + LifezoneSupply.GRID_SZS.stream().map(String::valueOf)
                            .reduce((a, b) -> a + " | " + b).orElse("") + " (m) 중 하나여야 합니다: " + gridSz);
        }
        return gridSz;
    }

    private static LocalDate parseYmd(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 는 필수입니다 (YYYYMMDD)");
        }
        try {
            return LocalDate.parse(value.trim(), YMD);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 는 YYYYMMDD 형식의 실재하는 날짜여야 합니다: " + value);
        }
    }

    /* ── 2. 등급 판정 ──────────────────────────────── */

    /**
     * 부족률 등간격으로 등급을 매긴다.
     *
     * <p>입력 {@code rows} 는 매퍼가 이미 <b>부족 순위({@code rnk}) 오름차순</b>으로 정렬해 두고, 순위·모집단 수
     * ({@code totGridCnt})도 SQL 이 낸다 — 여기서는 다시 정렬하거나 순위를 매기지 않는다.
     * 🔴 등급은 5단계 중 하나가 <b>항상</b> 붙는다(등급 없는 격자가 없다). 판정은 {@link LifezoneGrade#ofShrtRt}
     * 한 곳이고, 반올림된 {@code shrtRt} 가 아니라 민원·수요지수 원값으로 판정한다. {@code rnk} 는 셀에 그대로 싣는다.
     *
     * <p>목록 대상 등급 셀에는 POI 구역명({@code cntrPoiNm})을 붙인다({@link #resolvePoiNm}).
     * 조회 좌표는 격자 중심({@code ctrLat/Lot})이다.
     */
    private List<LifezoneSupplyCellVO> classify(List<LifezoneSupplyRow> rows, int gridSz,
                                                LifezoneSupply.GrdBndry bndry) {
        List<LifezoneSupplyCellVO> cells = new ArrayList<>(rows.size());
        // 요청 1건 = 세션 1개(외부 호출 상한을 세션이 센다 — 빈에 두면 동시 요청이 상한을 나눠 쓴다)
        ZoneNameResolver.Session session = zoneNameResolver.newSession();
        for (LifezoneSupplyRow row : rows) {
            LifezoneGrade grade = LifezoneGrade.ofShrtRt(row.getCvlcptCnt(), row.getDmndIdx(), bndry);

            LifezoneSupplyCellVO cell = new LifezoneSupplyCellVO();
            cell.setGridId(gridIdOf(gridSz, row.getGx(), row.getGy()));
            cell.setGrade(grade.name());
            cell.setGradeNm(grade.getGradeNm());
            cell.setRnk(row.getRnk());
            cell.setShortageRatio(row.getShrtRt());
            cell.setDemandIndex(row.getDmndIdx());
            cell.setSggNm(row.getSggNm());
            cell.setEmdNm(row.getEmdNm());
            cell.setEmdCd(row.getEmdCd());
            if (grade.isListed()) {
                cell.setCntrPoiNm(resolvePoiNm(session, row));
            } else {
                // 보통·낮음은 resolver 를 태우지 않고 캐시 이름만 싣는다(없으면 null → 키 생략)
                cell.setCntrPoiNm(row.getCacheZoneNm());
            }
            cell.setCvlcptCnt(row.getCvlcptCnt());
            cell.setPkltCpctySum(row.getPkltCpctySum());
            cell.setPkltCnt(row.getPkltCnt());
            cell.setDstncPtCnt(row.getDstncPtCnt());
            cell.setCncntWarnYn(cncntWarnYnOf(row));
            cell.setCtrLat(row.getCtrLat());
            cell.setCtrLot(row.getCtrLot());
            cell.setRing(parseRing(row.getRingJson(), cell.getGridId()));
            cells.add(cell);
        }
        return cells;
    }

    /**
     * 목록 대상 셀의 POI 구역명 (민원건수 분석 {@code LifezoneAnalysisServiceImpl#buildRanking} 과 같은 방식).
     *
     * <p>🔴 <b>목록 대상 등급(심각·경고·주의)에만 부른다</b> — 보통·낮음까지 태우면 외부 호출 상한
     * ({@code analysis.zone-name.max-lookups-per-run}, 기본 50)이 지도에만 보이는 셀에 소진된다.
     * 행은 부족 순위 순으로 오므로 상한은 위쪽 순위부터 쓰인다. 캐시 히트는 상한을 소모하지 않아
     * 같은 조회를 반복하면 POI 이름이 늘어난다.
     *
     * <p>🔴 조회 좌표는 <b>격자 중심 고정</b>({@code ctrLat/Lot})이다 (개정, 종전: 민원 무게중심) —
     * 조건이 바뀌어도 캐시 키가 같아 외부 조회 상한에 걸리지 않는다.
     *
     * <p>resolver 가 자기 폴백({@code SRC_EMD} — {@code … 일대}·{@code 위치 미상})으로 내려가면 {@code null} 을 돌려준다.
     * 🔴 그 문구를 싣지 않는다 — 화면이 이미 읍면동명으로 폴백하므로 {@code 일대} 가 붙은 이름이 섞이면
     * 같은 목록에 두 가지 폴백 문구가 보인다. resolver 는 예외를 던지지 않는다.
     */
    private static String resolvePoiNm(ZoneNameResolver.Session session, LifezoneSupplyRow row) {
        ZoneName resolved = session.resolve(row.getCtrLat(), row.getCtrLot(), row.getEmdNm());
        if (resolved == null || resolved.zoneNm() == null || resolved.zoneNm().isBlank()
                || ZoneNameResolver.SRC_EMD.equals(resolved.zoneNmSrc())) {
            return null;
        }
        return resolved.zoneNm();
    }

    /**
     * 격자ID {@code {격자크기}m_{gx}_{gy}} — 원본 산출물의 표기와 문자 단위로 같다.
     *
     * <p>🔴 <b>이 형식의 유일한 출처</b>다. 같은 패키지의 혼잡도 서비스({@code LifezoneCngstServiceImpl})가
     * 셀 {@code gridCd} 를 같은 형식으로 만들 때 이 메서드를 부른다 — 그래서 {@code private} 이
     * 아니라 <b>패키지 전용</b>이고, 행 타입이 달라 인자를 {@code gx}·{@code gy} 로 풀었다
     * ({@link LifezoneAnalysisServiceImpl#fallbackZoneNm} 과 같은 방식).
     */
    static String gridIdOf(int gridSz, long gx, long gy) {
        return gridSz + "m_" + gx + "_" + gy;
    }

    /**
     * 소수 지점 집중 주의.
     *
     * <p>민원 좌표가 지번 중심점 기준이라, 고유 좌표가 두세 개뿐인데 민원이 많은 격자는 실제
     * 발생 위치가 더 넓게 퍼져 있을 수 있다. 🔴 <b>판정을 서버가 한다</b> — 프론트에 같은 조건을
     * 또 적으면 기준이 갈린다.
     */
    private static String cncntWarnYnOf(LifezoneSupplyRow row) {
        boolean warn = row.getDstncPtCnt() <= LifezoneSupply.CNCNT_WARN_MAX_DSTNC_PT
                && row.getCvlcptCnt() >= LifezoneSupply.CNCNT_WARN_MIN_CVLCPT;
        return warn ? "Y" : "N";
    }

    /**
     * 격자 경계 GeoJSON → {@code [[위도, 경도], …]} 4점.
     *
     * <p>{@link LifezoneAnalysisServiceImpl#parseRing} 과 같은 모양이지만 <b>입력이 다르다</b> —
     * 저쪽은 {@code TB_GRID} 의 MultiPolygon, 이쪽은 계산한 Polygon 이다. 링 파싱은 실패해도
     * 셀 자체는 유효하므로 <b>빈 링으로 두고 WARN</b> 만 남긴다(지도에 안 그려질 뿐이다).
     */
    private List<List<BigDecimal>> parseRing(String ringJson, String gridId) {
        List<List<BigDecimal>> ring = new ArrayList<>(4);
        if (ringJson == null || ringJson.isBlank()) {
            return ring;
        }
        try {
            JsonNode coords = objectMapper.readTree(ringJson).path("coordinates");
            while (coords.isArray() && !coords.isEmpty()
                    && coords.get(0).isArray() && coords.get(0).path(0).isArray()) {
                coords = coords.get(0);
            }
            if (!coords.isArray() || coords.isEmpty()) {
                return ring;
            }
            int end = coords.size();
            if (end > 1 && coords.get(0).equals(coords.get(end - 1))) {
                end--;   // GeoJSON 링은 첫 점을 끝에 한 번 더 적는다 — 화면에는 4점이면 된다
            }
            for (int i = 0; i < end; i++) {
                JsonNode pt = coords.get(i);
                if (!pt.isArray() || pt.size() < 2) {
                    continue;
                }
                ring.add(List.of(pt.get(1).decimalValue(), pt.get(0).decimalValue()));
            }
        } catch (JsonProcessingException e) {
            log.warn("격자 꼭짓점 링 파싱 실패 — 빈 링으로 둡니다. gridId={} 사유={}", gridId, e.getClass().getSimpleName());
            log.debug("격자 꼭짓점 링 파싱 실패 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만
            return new ArrayList<>();
        }
        return ring;
    }

    /* ── 3. 응답 조립 ─────────────────────────────────── */

    private LifezoneSupplyResultVO buildResult(int gridSz, LocalDate bgngYmd, LocalDate endYmd,
                                               LifezoneRegionResolver.Region region, String emdCd,
                                               List<LifezoneSupplyCellVO> cells, Crtr crtr) {
        LifezoneSupplyResultVO result = new LifezoneSupplyResultVO();
        result.setBgngYmd(bgngYmd.format(YMD));
        result.setEndYmd(endYmd.format(YMD));
        result.setSggNm(region.sggNm());
        result.setEmdCd(emdCd);
        result.setAnlsTrgtCd(LifezoneTarget.SPLY_DMND.name());
        result.setAnlsTrgtNm(LifezoneTarget.SPLY_DMND.getAnlsTrgtNm());
        result.setGridSz(gridSz);
        result.setGridSzNm(LifezoneSupply.gridSzNm(gridSz));
        result.setCrs(LifezoneSupply.CRS);
        result.setSectionTitle(LifezoneTarget.SPLY_DMND.getSectionTitle());
        result.setSectionSub(LifezoneTarget.SPLY_DMND.getSectionSub());
        result.setDisclaimer(LifezoneSupply.DISCLAIMER);
        // 🔴 조회 응답에서도 채운다 — 프론트 unsaved 경로가 조회 결과로 보고서를 그린다
        result.setAnlsMthd(LifezoneSupply.ANLS_MTHD_SHRT_RT);
        // 🔴 매퍼에 넘긴 값과 같은 값(설정 표)이다. 상수를 다시 쓰면 범례 문구가 실제 기준과 갈린다
        result.setMinMnthAvgCvlcpt(crtr.minMnthAvgCvlcpt());
        // 100m 에서만 해석 주의 안내. 그 밖은 null → 키 생략
        result.setGridNotice(gridSz == 100 ? LifezoneSupply.SMALL_GRID_NOTICE : null);

        // 등급별 [격자수, 민원합, 공급0 격자수]
        Map<LifezoneGrade, int[]> agg = new EnumMap<>(LifezoneGrade.class);
        int totCvlcpt = 0;
        int totSply = 0;
        int zeroSply = 0;
        for (LifezoneSupplyCellVO cell : cells) {
            int[] a = agg.computeIfAbsent(LifezoneGrade.valueOf(cell.getGrade()), k -> new int[3]);
            a[0]++;
            a[1] += cell.getCvlcptCnt();
            if (cell.getPkltCpctySum() == 0) {
                a[2]++;
                zeroSply++;
            }
            totCvlcpt += cell.getCvlcptCnt();
            totSply += cell.getPkltCpctySum();
        }
        result.setGradeCriteria(LifezoneSupplyGradeCriterionVO.allOf(agg, crtr.bndry()));
        result.setTotGridCnt(cells.size());
        result.setTotCvlcptCnt(totCvlcpt);
        result.setTotPkltCpctySum(totSply);
        result.setZeroSplyGridCnt(zeroSply);
        // 🔴 0건이어도 0 으로 채운다 — null 은 구 스냅샷에서만 남아 키가 생략된다
        result.setSevereCnt(gridCntOf(agg, LifezoneGrade.SEVERE));
        result.setWarnCnt(gridCntOf(agg, LifezoneGrade.WARN));
        result.setCautionCnt(gridCntOf(agg, LifezoneGrade.CAUTION));
        result.setNormalCnt(gridCntOf(agg, LifezoneGrade.NORMAL));
        result.setLowCnt(gridCntOf(agg, LifezoneGrade.LOW));

        DataRangeVO range = lifezoneSupplyMapper.selectDataRange();
        if (range != null) {
            result.setDataBgngYmd(range.getDataBgngYmd());
            result.setDataEndYmd(range.getDataEndYmd());
        }
        result.setCells(cells);

        if (cells.isEmpty()) {
            log.info("생활권 수급 격자 0건 — 조건에 데이터가 없습니다(월평균 민원 {}건 이상 격자만). 기간={}~{} "
                            + "지역(sggCd={}, emdCd={}) 격자={}m 보유구간={}~{}",
                    crtr.minMnthAvgCvlcpt(), result.getBgngYmd(), result.getEndYmd(),
                    region.sggCd(), emdCd, gridSz, result.getDataBgngYmd(), result.getDataEndYmd());
        } else {
            log.debug("생활권 수급 분석 완료: 격자={}m 셀={}건 민원={}건 공급0={}건 "
                            + "심각={} 경고={} 주의={} 보통={} 낮음={}",
                    gridSz, cells.size(), totCvlcpt, zeroSply,
                    result.getSevereCnt(), result.getWarnCnt(), result.getCautionCnt(),
                    result.getNormalCnt(), result.getLowCnt());
        }
        return result;
    }

    private static int gridCntOf(Map<LifezoneGrade, int[]> agg, LifezoneGrade grade) {
        int[] a = agg.get(grade);
        return a == null ? 0 : a[0];
    }
}
