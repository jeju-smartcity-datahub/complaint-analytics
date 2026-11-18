package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.analysis.service.PrdMonths;
import jeju.datahub.analysis.service.ZoneRcmdtnShareBaseVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareService;
import lombok.RequiredArgsConstructor;

/**
 * 구역추천(공유주차제 적용) 분석 서비스 구현체. eGovFrame {@link EgovAbstractServiceImpl} 상속(기존 패턴).
 *
 * <p>하는 일은 조립뿐이다 — ① 기간 파싱·검증, ② 설정 읽기, ③ 추세 창(D · D/2 · 블록 경계) 계산,
 * ④ 매퍼 호출, ⑤ 판정·주의 표시·요약·지역 필터·순번({@link ZoneRcmdtnShareCalculator}).
 * 추세 창은 요금제 {@code ZoneRcmdtnServiceImpl.applyTrendWindow} 와 같은 경계다(TSK-4283). <b>판정식은 계산기 한 곳에만 둔다</b> — 여기에
 * 다시 쓰면 두 곳이 갈린다.
 *
 * <p>🔴 <b>매퍼에서는 거르지 않는다 — 계산기에 읍면동 전량을 넘긴다.</b> 판정(등급·검토 지수·주의
 * 표시)과 {@code smry} 는 필터와 무관하게 도 전체 값이고, <b>순번({@code rnk})만 조회 결과(필터 적용
 * 후) 안에서 1 부터</b> 매긴다. 그 순서(요약 → 필터 → 순번)는 계산기가 진다.
 *
 * <p>🔴 <b>기간 처리는 요금제 트랙({@code ZoneRcmdtnServiceImpl})과 같은 식이다</b> — 반열림
 * ({@code atStartOfDay()} / {@code plusDays(1).atStartOfDay()})이고 개월 수는 {@code PrdMonths} — 달별 조회
 * 비율 합(TSK-4284)이다. 같은 화면의 두 트랙이 "월평균 민원" 을 다르게 세면 안 된다 — 그래서 식을
 * 공용 유틸 한 곳에 두고 두 트랙이 같이 부른다.
 *
 * <p>창구가 둘이다 — {@link #selectShare}(조회만)와 {@link #analyzeAndRecord}(조회 + 실행이력·결과
 * 적재, TSK-4208 Step 4). 둘 다 {@link #compute} 한 곳을 지나므로 판정 결과가 갈리지 않는다.
 * 🔴 없는 {@code emdCd} 의 400 은 {@link #compute} 안에 있어 <b>적재보다 앞</b>이다 — 뒤면
 * {@code tb_anls_excn.emd_cd} FK 위반으로 400 이 아니라 500 이 난다.
 */
@Service
@RequiredArgsConstructor
public class ZoneRcmdtnShareServiceImpl extends EgovAbstractServiceImpl implements ZoneRcmdtnShareService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 분석유형코드 ({@code ANLS_TYPE_CD} 공통코드). 기준값 2종이 이 유형의 분석변수다 */
    private static final String ANLS_TYPE_RECOMMEND = "RECOMMEND";

    /** 분석실행상태코드 ({@code EXCN_STTS_CD}) — 동기 실행이라 저장이 곧 완료다 */
    private static final String EXCN_STTS_DONE = "DONE";

    /**
     * 구역추천 하위 유형({@code ZONE_RCMDTN_TYPE_CD}). 🔴 {@code TB_ANLS_EXCN} 에 하위 유형 칸이 없어
     * {@code anls_param_json.rcmdtnTypeCd} 가 요금제 실행(TSK-4207)과 이 실행을 가르는 유일한 표지다.
     */
    private static final String RCMDTN_TYPE_SHARE = "SHARE";

    /**
     * {@code sggNm} 길이 상한 — {@code tb_anls_excn.sgg_nm} 이 {@code VARCHAR(40)} 이다. 넘는 값이 적재까지
     * 가면 400 이 아니라 500 이 난다. 🔴 컬럼 폭이 바뀌면 함께 고친다.
     */
    private static final int SGG_NM_MAX_LEN = 40;

    private final ZoneRcmdtnShareMapper zoneRcmdtnShareMapper;

    /**
     * 설정값 읽기 창구. 🔴 {@code AnalysisWeightService} 가 아니라 <b>매퍼</b>를 직접 받는다 — 그 서비스의
     * 계수 단건 조회는 행 부재 시 {@code 1.0} 을, 실효 가중치 조회는 재정규화 결과를 돌려준다.
     */
    private final AnalysisWeightMapper analysisWeightMapper;

    private final AnlsVrblOvrdResolver anlsVrblOvrdResolver;

    private final AnlsExcnRepository anlsExcnRepository;
    private final AnlsRsltRepository anlsRsltRepository;

    /** JSONB 직렬화용 — 스프링이 구성한 인스턴스를 그대로 쓴다(응답과 같은 표기로 적재된다) */
    private final ObjectMapper objectMapper;

    @Override
    public ZoneRcmdtnShareResultVO selectShare(String bgngYmd, String endYmd, String sggNm, String emdCd) {
        return selectShare(bgngYmd, endYmd, sggNm, emdCd, null);
    }

    @Override
    public ZoneRcmdtnShareResultVO selectShare(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                               Map<String, BigDecimal> vrblVls) {
        return compute(bgngYmd, endYmd, sggNm, emdCd, vrblVls, false).result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>🔴 {@link #compute} 한 번으로 판정·{@code wbk} 를 함께 만든다 — 재조회가 없다. 행·블록 건수는 계산기가,
     * 계수 4행은 같은 실행의 {@code ovrd}·응답 {@code crtrVls} 로 여기서 붙인다(TSK-4340 Step 2).
     */
    @Override
    public ZoneRcmdtnShareResultVO selectShareWithWorkbook(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                                           Map<String, BigDecimal> vrblVls) {
        Computed c = compute(bgngYmd, endYmd, sggNm, emdCd, vrblVls, true);
        c.result.getWbk().setVrbls(ZoneRcmdtnShareWorkbooks.vrbls(c.ovrd, c.result.getCrtrVls()));
        return c.result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>순서가 중요하다 — <b>조회 → 실행이력 → 결과</b>. {@code TB_ANLS_RSLT.excn_id} 가 NOT NULL FK 라
     * 실행이력을 먼저 {@code flush()} 해 identity 채번을 확정한다({@code ZoneRcmdtnServiceImpl}·
     * {@code HotspotReportServiceImpl} 선례).
     *
     * <p>🔴 <b>{@code readOnly = true} 를 붙이지 않는다</b> — {@code GET} 이지만 실행 이력을 남긴다.
     * 붙이면 적재가 조용히 빠진다.
     * <p>⚠️ 조회가 400 으로 끊기면 아직 아무것도 저장하지 않았으므로 <b>두 표 모두 늘지 않는다</b>.
     */
    @Override
    @Transactional
    public ZoneRcmdtnShareResultVO analyzeAndRecord(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                                    String excutrId) {
        return analyzeAndRecord(bgngYmd, endYmd, sggNm, emdCd, excutrId, null);
    }

    @Override
    @Transactional
    public ZoneRcmdtnShareResultVO analyzeAndRecord(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                                    String excutrId, Map<String, BigDecimal> vrblVls) {
        Computed c = compute(bgngYmd, endYmd, sggNm, emdCd, vrblVls, false);
        ZoneRcmdtnShareResultVO result = c.result;

        // ── ① 실행이력 1행 (anls_type_cd='RECOMMEND') ───────────────────────────
        AnlsExcn excn = new AnlsExcn();
        excn.setAnlsTypeCd(ANLS_TYPE_RECOMMEND);
        excn.setExcutrId(excutrId);   // 해석·401 은 컨트롤러 책임(PolicySimServiceImpl 과 같은 규칙) — TSK-4249
        // 🔴 emdCd 가 있으면 그 읍면동이 실제로 속한 시군구가 우선한다(HotspotReportServiceImpl 선례) —
        //    emdCd 만 온 요청에서 sgg_nm 을 비우면 지역명을 엮는 조회의 `sgg_nm || ' ' || emd_nm` 이
        //    NULL 연결이 된다. 지역 조건이 없으면 두 칸 모두 NULL 이다(전체 지역).
        excn.setSggNm(c.emdCdFilter != null ? c.emdSggNm : c.sggNmFilter);
        excn.setEmdCd(c.emdCdFilter);
        // 요청 문자열이 아니라 파싱을 통과한 값이다(앞뒤 공백이 CHAR(8) 로 흘러가지 않는다)
        excn.setAnlsBgngYmd(c.bgng.format(YMD));
        excn.setAnlsEndYmd(c.end.format(YMD));
        excn.setAnlsParamJson(buildParamJson(c));
        excn.setExcnSttsCd(EXCN_STTS_DONE);
        excn.setExcnDt(LocalDateTime.now());
        anlsExcnRepository.save(excn);
        // 결과 행이 excn_id 를 참조하므로 여기서 채번을 확정한다.
        anlsExcnRepository.flush();

        // ── ② 결과 1행 (실행 1건 = 결과 1행 — excn_id UNIQUE) ────────────────────
        AnlsRslt rslt = new AnlsRslt();
        rslt.setExcnId(excn.getExcnId());
        rslt.setRsltJson(buildRsltJson(result));
        anlsRsltRepository.save(rslt);

        result.setExcnId(excn.getExcnId());
        return result;
    }

    /**
     * 검증·조회·판정·필터·순번 — 두 창구가 함께 지나는 한 곳이다. <b>DB 에 쓰지 않는다.</b>
     *
     * <p>🔴 서비스의 400(형식·역전·{@code sggNm} 길이·없는 {@code sggNm}·없는 {@code emdCd})은 전부 여기서
     * 난다 — 적재는 이 메서드가 돌아온 뒤다. (기간 상한 36개월은 컨트롤러가 이보다 앞에서 본다.)
     */
    private Computed compute(String bgngYmd, String endYmd, String sggNm, String emdCd,
                             Map<String, BigDecimal> vrblVls, boolean withWbk) {
        // ① 기간 — 형식 오류·역전은 SQL 로 흘려보내지 않고 400 으로 끊는다
        LocalDate bgng = parseYmd(bgngYmd, "bgngYmd");
        LocalDate end = parseYmd(endYmd, "endYmd");
        if (end.isBefore(bgng)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endYmd 가 bgngYmd 보다 빠릅니다: " + bgng + " ~ " + end);
        }
        // 빈 문자열·공백만 온 필터는 "필터 없음" 이다
        String sggNmFilter = trimToNull(sggNm);
        String emdCdFilter = trimToNull(emdCd);
        // 🔴 DB 를 읽기 전에 끊는다 — 넘는 값은 어차피 실재하는 시군구명일 수 없다
        if (sggNmFilter != null && sggNmFilter.length() > SGG_NM_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sggNm 은 " + SGG_NM_MAX_LEN + "자를 넘을 수 없습니다: " + sggNmFilter.length() + "자");
        }

        // 🔴 반열림 — ocrn_dt 는 timestamp 라 종료일 00:00 으로 두면 그 날이 빠진다
        LocalDateTime cvFrom = bgng.atStartOfDay();
        LocalDateTime cvToExcl = end.plusDays(1).atStartOfDay();
        // 월평균 분모 — 달별 조회 비율 합(TSK-4284). 1일 조회도 0 보다 크다(1 로 올리지 않는다)
        BigDecimal months = PrdMonths.of(bgng, end);

        // ② 설정 — 폴백(설정 → 최초 등재값 → 상수)은 계산기의 순수 함수가 한다
        //    🔴 일시 입력은 행에 덮어 넘긴다 — 입력 오류는 400 이고 적재보다 앞이다. 설정 표에 쓰지 않는다 (TSK-4325)
        AnlsVrblOvrd ovrd = anlsVrblOvrdResolver.resolve(AnlsVrblScope.RECOMMEND_SHARE, vrblVls);
        List<String> warnings = new ArrayList<>();
        ZoneRcmdtnShareCalculator.Crtr crtr = ZoneRcmdtnShareCalculator.resolveCrtr(
                ovrd.apply(analysisWeightMapper.selectWeightsByType(ANLS_TYPE_RECOMMEND)), warnings);

        // ③ 추세 창(TSK-4283) — D · D/2 · 블록 경계를 여기서 한 번만 계산해 매퍼(경계)와 계산기(D · D/2)에
        //    같은 값을 넘긴다. 🔴 경계는 요금제 ZoneRcmdtnServiceImpl.applyTrendWindow 와 같다(공용 유틸로 뽑지
        //    않았다 — 요금제 코드를 건드리지 않기 위해서다. 한쪽을 고치면 다른 쪽도 함께 고친다).
        //    1차 이전 = [bgng − D, bgng) · 최근 = 민원 창 그대로 [bgng, end+1)
        //    폴백 앞 = [bgng, bgng + half) · 뒤 = [end − (half − 1), end+1) — D 가 홀수면 가운데 1일을 버린다
        long days = ChronoUnit.DAYS.between(bgng, end) + 1;   // 양끝 포함. end >= bgng 는 ① 이 보장한다
        long half = days / 2;                                  // 🔴 정수 나눗셈 — 계산기는 다시 나누지 않는다
        Integer trendDayCnt = null;
        Integer splitDayCnt = null;
        LocalDateTime trendFrom = null;
        LocalDateTime splitMid = null;
        LocalDateTime splitMidExcl = null;
        if (half < ZoneRcmdtnCalculator.MIN_TREND_DAYS) {
            // 🔴 경계 3칸을 null 로 둔다 — XML 이 jdbcType=TIMESTAMP 를 달아 두었고 ocrn_dt >= NULL 은 거짓이라
            //    세 칸이 0 이다. 일수도 null 이다(요금제의 일수 0 관례를 옮기지 않는다 — 계산기가 폴백 판정을
            //    생략하는 신호다). 경고는 여기서 담아 계산기로 넘긴다.
            warnings.add(ZoneRcmdtnCalculator.WARN_TREND_WINDOW_SHORT);
        } else {
            trendDayCnt = (int) days;
            splitDayCnt = (int) half;
            trendFrom = bgng.minusDays(days).atStartOfDay();       // 요금제 bfrFrom
            splitMid = bgng.plusDays(half).atStartOfDay();         // 요금제 splitBfrToExcl
            splitMidExcl = cvToExcl.minusDays(half);               // 요금제 splitTrendFrom = end − (half − 1)
        }

        // ④ 읍면동 전량(행정동 마스터 기준 — 원천이 0건인 동도 빠지지 않는다)
        List<ZoneRcmdtnShareBaseVO> rows = zoneRcmdtnShareMapper.selectShareBase(cvFrom, cvToExcl,
                trendFrom, splitMid, splitMidExcl);

        // 🔴 없는 sggNm(오타 등)도 400 이다 — 없는 emdCd 와 같은 취급이다. 통과시키면 빈 zones 로 200 이
        //    나가면서 실행 행이 적재돼, 오타 한 번이 "결과 없는 실행" 으로 이력에 남는다.
        if (sggNmFilter != null && rows.stream().noneMatch(r -> sggNmFilter.equals(r.getSggNm()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "시군구를 찾을 수 없습니다: sggNm=" + sggNmFilter);
        }

        // 🔴 없는 emdCd 는 400 이다 — 존재 확인은 이 목록에서 한다(새 쿼리가 필요 없다).
        //    그 읍면동의 시군구도 같은 목록에서 읽어 둔다(실행이력 sgg_nm 의 출처다).
        String emdSggNm = null;
        if (emdCdFilter != null) {
            ZoneRcmdtnShareBaseVO emdRow = rows.stream()
                    .filter(r -> emdCdFilter.equals(r.getEmdCd()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "읍면동을 찾을 수 없습니다: emdCd=" + emdCdFilter));
            emdSggNm = emdRow.getSggNm();
        }

        // ⑤ 판정·주의 표시·요약(도 전체) → 지역 필터 → 순번(🔴 필터에 걸린 읍면동 안에서 1 부터)
        //    rows 는 전량을 넘긴다 — smry·판정은 필터와 무관하다. D · D/2 는 ③ 에서 경계를 만든 그 값이다
        //    🆕 TSK-4282 D13 — 자료 구간을 판정 앞에서 읽는다. 1차 이전 블록(trendFrom)이 자료 시작일 앞이면
        //    반 분할로 내려앉는다(요금제와 같은 규칙). 같은 값을 아래 자료 구간 안내에도 쓴다(1회 조회)
        DataRangeVO range = zoneRcmdtnShareMapper.selectDataRange();
        //    🆕 TSK-4340 — 보고서 경로(withWbk)는 같은 판정에 엑셀 원자료를 붙인다(판정 결과는 같다)
        ZoneRcmdtnShareResultVO result = withWbk
                ? ZoneRcmdtnShareCalculator.calculateWithWorkbook(
                        rows, months, crtr, warnings, sggNmFilter, emdCdFilter, trendDayCnt, splitDayCnt,
                        trendFrom, ZoneRcmdtnCalculator.dataBgngDt(range))
                : ZoneRcmdtnShareCalculator.calculate(
                        rows, months, crtr, warnings, sggNmFilter, emdCdFilter, trendDayCnt, splitDayCnt,
                        trendFrom, ZoneRcmdtnCalculator.dataBgngDt(range));

        // 자료 구간 — 조회 기간·필터와 무관한 안내값이다. 자료가 없으면 두 값이 null
        if (range != null) {
            result.setDataBgngYmd(range.getDataBgngYmd());
            result.setDataEndYmd(range.getDataEndYmd());
        }
        result.setVrblAjmt(ovrd.toAjmtJson());
        return new Computed(result, bgng, end, sggNmFilter, emdCdFilter, emdSggNm, ovrd);
    }

    /**
     * 재현 메타 JSON ({@code anls_param_json}).
     *
     * <p>🔴 <b>{@code rcmdtnTypeCd} 를 반드시 넣는다</b> — 없으면 요금제 실행과 공유주차제 실행을 사후에
     * 가를 수 없다. 🔴 <b>적용 기준값도 함께 굳힌다</b> — 설정이 바뀌면 같은 조건의 결과가 달라진다.
     * 기준값은 설정을 다시 읽지 않고 <b>응답의 {@code crtrVls}</b>(꼬리 0 을 걷은 값)를 쓴다 — 다시 읽으면
     * 그 사이 바뀐 값이 섞여 "응답에 적힌 기준" 과 갈린다.
     *
     * <p>{@code sggNm}·{@code emdCd} 는 <b>요청이 건 필터 그대로</b>다(없으면 null) — 같은 조회를 다시
     * 부르기 위한 값이라, 읍면동에서 유도한 시군구({@code sgg_nm} 컬럼)로 바꿔 적지 않는다.
     */
    private String buildParamJson(Computed c) {
        return writeJson(buildParamMap(c.result.getCrtrVls(), c.sggNmFilter, c.emdCdFilter,
                c.result.getVrblAjmt()), "분석 파라미터");
    }

    /**
     * 재현 메타의 Map 조립부 — 12키({@code rcmdtnTypeCd · cvlcptMmCrtrVl · cvlcptRtCrtrVl · coreMltp · months ·
     * sggNm · emdCd} + TSK-4283 추세 4키 {@code incrsWgvl · incrsRtUplmtVl · minTrendBase · trendDayCnt} +
     * TSK-4325 {@code vrblAjmt}).
     * 인스턴스 상태를 쓰지 않는 순수 함수다.
     *
     * <p>추세 4키도 응답 {@code crtrVls} 에서 읽는다 — 가중치·상한이 바뀌면 같은 기간이라도 순번이 달라지고,
     * {@code trendDayCnt}(1차 D · 폴백 D/2 · 기간 하한 미달 {@code null})는 어느 블록으로 증가율을 냈는지를
     * 굳힌다. 기존 7키의 이름·순서는 그대로 두고 뒤에 덧붙인다.
     *
     * <p>package-private static 인 이유 — 같은 패키지의 보고서 서비스({@code ZoneRcmdtnReportServiceImpl},
     * TSK-4240 Step 3)가 <b>조회 실행과 같은 키</b>를 적어야 해서다. 사본을 두면 두 실행의 키가 갈린다.
     *
     * @param v           응답의 {@code crtrVls}(설정을 다시 읽지 않는다)
     * @param sggNmFilter 정규화한(trim · 빈 값은 null) 시군구명 필터
     * @param emdCdFilter 정규화한 읍면동코드 필터
     * @param vrblAjmt    분석 계수 적용 내역(D10) — {@code v} 에 담기지 않아 따로 받는다
     */
    static Map<String, Object> buildParamMap(ZoneRcmdtnShareResultVO.CrtrVls v,
                                             String sggNmFilter, String emdCdFilter,
                                             Map<String, Object> vrblAjmt) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("rcmdtnTypeCd", RCMDTN_TYPE_SHARE);
        param.put("cvlcptMmCrtrVl", v.getCvlcptMmCrtrVl());
        param.put("cvlcptRtCrtrVl", v.getCvlcptRtCrtrVl());
        param.put("coreMltp", v.getCoreMltp());
        param.put("months", v.getMonths());
        param.put("sggNm", sggNmFilter);
        param.put("emdCd", emdCdFilter);
        param.put("incrsWgvl", v.getIncrsWgvl());
        param.put("incrsRtUplmtVl", v.getIncrsRtUplmtVl());
        param.put("minTrendBase", v.getMinTrendBase());
        param.put("trendDayCnt", v.getTrendDayCnt());
        param.put("vrblAjmt", vrblAjmt);
        return param;
    }

    /**
     * 결과 JSON ({@code rslt_json}) — 응답의 {@code smry} + {@code zones}.
     *
     * <p>🔴 <b>객체로 감싼다</b>({@code AnlsRslt} D15). 키는 응답 VO 필드명과 같게 둔다(되읽을 때 변환이
     * 필요 없다). {@code zones} 는 응답과 같이 <b>필터에 걸린 읍면동</b>(순번도 응답과 같이 그 안에서
     * 매긴 값)이고 {@code smry} 는 도 전체 값이다.
     */
    private String buildRsltJson(ZoneRcmdtnShareResultVO result) {
        Map<String, Object> rslt = new LinkedHashMap<>();
        rslt.put("smry", result.getSmry());
        rslt.put("zones", result.getZones());
        return writeJson(rslt, "분석 결과");
    }

    private String writeJson(Object value, String what) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 직렬화 대상이 Map·VO 뿐이라 정상 흐름에서는 오지 않는다. 런타임 예외로 올려 트랜잭션을 롤백한다.
            throw new IllegalStateException(what + " JSON 직렬화에 실패했습니다.", e);
        }
    }

    /**
     * {@link #compute} 의 반환 묶음 — 응답과, 적재에만 쓰는 정규화된 요청값이다.
     *
     * @param emdSggNm {@code emdCd} 필터가 가리키는 읍면동의 시군구({@code TB_ADMDST}). 필터가 없으면 null
     * @param ovrd     이번 실행의 계수 입력 — 엑셀 계수 행의 이름·범위 출처(TSK-4340 D5)
     */
    private record Computed(ZoneRcmdtnShareResultVO result, LocalDate bgng, LocalDate end,
                            String sggNmFilter, String emdCdFilter, String emdSggNm, AnlsVrblOvrd ovrd) {
    }

    /** 🔴 형식 오류는 SQL 로 흘려보내지 않고 400 으로 끊는다({@code ZoneRcmdtnServiceImpl} 선례). */
    private static LocalDate parseYmd(String value, String field) {
        if (trimToNull(value) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 는 필수입니다 (YYYYMMDD)");
        }
        try {
            return LocalDate.parse(value.trim(), YMD);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 는 YYYYMMDD 형식의 실재하는 날짜여야 합니다: " + value);
        }
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
