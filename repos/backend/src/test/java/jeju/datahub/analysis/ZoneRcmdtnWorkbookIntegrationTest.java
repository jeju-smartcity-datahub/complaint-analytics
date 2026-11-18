package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.ZoneRcmdtnTestData.Win;
import jeju.datahub.analysis.service.ZoneRcmdtnReportCreateRequest;
import jeju.datahub.analysis.service.ZoneRcmdtnReportService;
import jeju.datahub.analysis.service.ZoneRcmdtnReportVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareReportCreateRequest;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 구역추천 엑셀 재계산 정답지 (TSK-4340 Step 4) — <b>보고서 {@code wbk} 를 엑셀 수식 규칙으로 다시 계산한 결과 =
 * 같은 조건 + 그 계수로 화면이 다시 조회한 결과</b>.
 *
 * <h2>🔴 재계산기는 서버 코드를 복사하지 않는다</h2>
 * 서버는 {@code BigDecimal}, 엑셀은 double 이다. 이 테스트의 재계산기({@link #recomputeFee}·{@link #recomputeShare})는
 * 계획서 3-1·3-2 표의 <b>엑셀 수식 그대로</b>(double · 나눗셈마다 {@code ROUND 6} · 검토 지수
 * {@code ROUNDDOWN(ROUND(x,10),6)} · 종합지수 {@code ROUND(rvw×cfc,10)} · 비교식 안의 곱({@code fill*100}·
 * {@code MM*1.05}·{@code RT*1.05})도 {@code ROUND(…,10)} 후 비교 · {@code ×2} 는 그대로 — D9)를 적는다.
 * 그래야 "엑셀 수식이 서버와 같다" 를 대조하는 의미가 있다. 엑셀 {@code ROUND}/{@code ROUNDDOWN} 은 유효숫자 15자리로
 * 먼저 맞춘다({@link #xlRound}·{@link #xlRoundDown} — 생활권 {@code excelRound0} 과 같은 규칙).
 *
 * <h2>🔴 비교 상대는 "같은 조건 + 다른 계수" GET 이다</h2>
 * 보고서는 기본 계수로 1건만 만들고, 조합마다 그 {@code wbk}(+ 요금제는 {@code lots})를 계수만 바꿔 재계산한다.
 * 공간 계수(D1 — {@code ALTMNT_RNG_VL}·{@code SWTC_TRGT_MIN_CPCTY})는 조합에 넣지 않는다(엑셀이 못 바꾼다).
 *
 * <h2>🔴 설정 표를 바꾸지 않는다 · 날짜를 박지 않는다 · 내가 만든 행만 지운다</h2>
 * 계수는 전부 {@code vrblVls} 로만 넘기고 테스트 전후 {@code tb_anls_vrbl_stng} 을 대조한다. 기간은 공유주차제 응답의
 * {@code dataBgngYmd}·{@code dataEndYmd} 로 끝 3개월을 잡는다. HTTP 조회가 남긴 실행 행은 응답 {@code excnId} 로,
 * 보고서 서비스 호출이 남긴 행은 기준선 초과 + 생성자 {@value #TESTER} 로 지운다({@code ZoneRcmdtnVrblAjmtIntegrationTest} 선례).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TSK-4340 Step 4 — 구역추천 엑셀 재계산(wbk) = 같은 계수의 화면 조회")
class ZoneRcmdtnWorkbookIntegrationTest {

    private static final String DETAIL_URL = "/api/analysis/zone-recommendation";
    private static final String SHARE_URL = "/api/analysis/zone-recommendation/share";
    private static final String FEE_REPORT_URL = "/api/analysis/zone-recommendation/reports";
    private static final String SHARE_REPORT_URL = "/api/analysis/zone-recommendation/share/reports";

    private static final String RECOMMEND = "RECOMMEND";
    private static final String TESTER = "tester4340w";
    private static final String TESTER_NM = "엑셀테스터";
    private static final String SGP = "서귀포시";

    /** 엑셀 ROUND 의 유효숫자 15자리 규칙 */
    private static final MathContext MC15 = new MathContext(15, RoundingMode.HALF_EVEN);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ZoneRcmdtnReportService reportService;

    /** 서비스가 저장·복원에 쓰는 매퍼 — 스냅샷을 프론트가 받는 JSON 그대로 만든다 */
    @Autowired
    private ObjectMapper springMapper;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private ZoneRcmdtnTestData data;
    private Win cv;
    private Win crdn;
    private BigDecimal minCpcty;

    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    private final Set<Long> createdExcnIds = new LinkedHashSet<>();
    private long baseRptpId;
    private long baseExcnId;
    private List<Map<String, Object>> stngBefore;

    @BeforeEach
    void setUp() {
        stngBefore = readSettings();
        baseRptpId = maxId("parking.tb_rptp", "rptp_id");
        baseExcnId = maxId("parking.tb_anls_excn", "excn_id");

        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();

        data = new ZoneRcmdtnTestData(jdbcTemplate);
        Win cvRange = data.cvRange();
        Win crdnRange = data.crdnRange();
        assertThat(cvRange).as("주차 민원이 비어 있다").isNotNull();
        assertThat(crdnRange).as("단속이 비어 있다").isNotNull();

        // 자료 구간은 공유주차제 응답의 dataBgngYmd·dataEndYmd 로 얻는다(이 호출도 실행 행을 남긴다 — 정리 대상)
        Win head = ZoneRcmdtnTestData.head(cvRange);
        JsonNode probe = getOk(SHARE_URL + "?bgngYmd=" + head.bgngYmd() + "&endYmd=" + head.endYmd(), null, null);
        LocalDate dataBgng = LocalDate.parse(probe.path("dataBgngYmd").asText(), ZoneRcmdtnTestData.YMD);
        LocalDate dataEnd = LocalDate.parse(probe.path("dataEndYmd").asText(), ZoneRcmdtnTestData.YMD);
        cv = last3Months(new Win(dataBgng, dataEnd));
        crdn = last3Months(crdnRange);
        minCpcty = data.appliedVl(RECOMMEND, "SWTC_TRGT_MIN_CPCTY");
        assertThat(minCpcty).isNotNull();
    }

    @AfterEach
    void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long excnId : createdExcnIds) {
            run(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long excnId : createdExcnIds) {
            run(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdExcnIds.clear();
        run(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id > ? AND creatr_id = ?", baseRptpId, TESTER);
        run(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id IN"
                + " (SELECT excn_id FROM parking.tb_anls_excn WHERE excn_id > ? AND excutr_id = ?)", baseExcnId, TESTER);
        run(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id > ? AND excutr_id = ?", baseExcnId, TESTER);
        auth.cleanUp();
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다").isEmpty();
        assertThat(readSettings()).as("🔴 설정 표(stng_vl)를 바꾸지 않는다").isEqualTo(stngBefore);
    }

    // ═════════════════════════════════════════════ 1. 요금제 — 읍면동 모드

    @Test
    @DisplayName("요금제 EMD — 계수 4조합: wbk+lots 재계산 = 같은 계수의 GET (순서 · 단계 · 점수 · 누적)")
    void feeEmdRecomputeEqualsQuery() throws Exception {
        String emd = data.busyEmd(cv, minCpcty, false, 2);
        assumeTrue(emd != null, "NARROW 후보 좌표가 2곳 이상이면서 민원이 있는 읍면동이 없다");

        ZoneRcmdtnReportVO created = reportService.createFeeReport(feeRequest(emd, null, "XLSX"), TESTER, TESTER_NM);
        assertThat(jdbcTemplate.queryForObject("SELECT file_fmt FROM parking.tb_rptp WHERE rptp_id = ?",
                String.class, created.getRptpId())).as("XLSX 저장").isEqualTo("XLSX");
        JsonNode fee = snapshotTree(created.getRptpId()).path("fee");
        JsonNode wbk = fee.path("wbk");
        assertThat(wbk.isObject()).as("새 보고서는 wbk 를 싣는다").isTrue();
        // 🔁 2026-11-11 — 읍면동별 요금제 대상 판정을 엑셀에 두지 않아 판정 원값·상수를 싣지 않는다
        assertThat(wbk.path("zoneBase").isMissingNode()).as("wbk.zoneBase 없음").isTrue();
        assertThat(wbk.path("fixed").path("dsclMltp").isMissingNode()).as("wbk.fixed 판정 상수 없음").isTrue();
        assertThat(inptYn(wbk, "STSF_RT_THRSH") + inptYn(wbk, "CVLCPT_MM_CRTR_VL") + inptYn(wbk, "ILGL_CRTR_VL"))
                .as("🔁 TSK-4340 — 판정 계수 3개는 wbk.vrbls 에 없다(RECOMMEND_FEE 스코프 밖)").isEqualTo("???");
        assertThat(wbk.path("vrbls").size()).as("계수 10행").isEqualTo(10);
        assumeTrue(fee.path("lots").size() >= 2, "후보 주차장이 2개소 미만이다");

        Map<String, Double> base = aplcnVls(wbk);
        List<Map<String, Double>> combos = new ArrayList<>();
        combos.add(Map.of());
        combos.add(Map.of("FEE_CVLCPT_CNT_WGVL", 100.0, "FEE_CVLCPT_RT_WGVL", 0.0));
        combos.add(Map.of("FEE_CVLCPT_INCRS_WGVL", 0.0));
        Map<String, Double> doubled = new LinkedHashMap<>();
        for (String cd : List.of("FEE_CVLCPT_MM_CRTR_JEJU", "FEE_CVLCPT_MM_CRTR_SEOGWIPO",
                "FEE_CVLCPT_RT_CRTR_JEJU", "FEE_CVLCPT_RT_CRTR_SEOGWIPO")) {
            doubled.put(cd, base.get(cd) * 2);
        }
        combos.add(doubled);
        // 🔁 TSK-4340 — 판정 계수 조합(STSF_RT_THRSH·CVLCPT_MM_CRTR_VL·ILGL_CRTR_VL)은 이제 400 이라 뺐다

        List<String> mismatches = new ArrayList<>();
        Set<String> outcomes = new LinkedHashSet<>();
        for (Map<String, Double> combo : combos) {
            assertWithinRange(wbk, combo);
            FeeResult mine = recomputeFee(wbk, fee.path("lots"), merge(base, combo), true);
            JsonNode query = getOk(DETAIL_URL + "?emdCd=" + emd + "&" + prd(), null, vrblJson(combo));
            compareFee("EMD " + combo, mine, query, true, mismatches);
            outcomes.add(feeSignature(mine));
        }
        assertThat(mismatches).as("요금제 EMD 재계산 ≠ 화면 조회 (불일치 %d건)", mismatches.size()).isEmpty();
        // 공허한 통과 방지 — 조합이 실제로 결과(순서·단계·점수)를 바꿨어야 대조가 의미 있다
        assertThat(outcomes.size()).as("조합 중 결과가 서로 다른 조합 수").isGreaterThanOrEqualTo(3);
    }

    // ═════════════════════════════════════════════ 2. 요금제 — 시군구 모드

    @Test
    @DisplayName("요금제 SGG — 계수 3조합(기본 · 민원 월기준 2배 · 면당 기준 변경): rnk 순서 · 단계 · 점수 = GET")
    void feeSggRecomputeEqualsQuery() throws Exception {
        String emd = data.busyEmd(cv, minCpcty, false, 1);
        assumeTrue(emd != null, "NARROW 후보가 있으면서 민원이 있는 읍면동이 없다");
        String sggNm = (String) data.admdst(emd).get("sgg_nm");

        ZoneRcmdtnReportVO created = reportService.createFeeReport(feeRequest(null, sggNm, "PDF"), TESTER, TESTER_NM);
        JsonNode fee = snapshotTree(created.getRptpId()).path("fee");
        JsonNode wbk = fee.path("wbk");
        assertThat(wbk.isObject()).as("PDF 로 저장해도 wbk 를 싣는다(D11)").isTrue();
        assertThat(wbk.path("zoneBase").isMissingNode()).as("wbk.zoneBase 없음").isTrue();

        Map<String, Double> base = aplcnVls(wbk);
        Map<String, Double> mm2 = Map.of(
                "FEE_CVLCPT_MM_CRTR_JEJU", base.get("FEE_CVLCPT_MM_CRTR_JEJU") * 2,
                "FEE_CVLCPT_MM_CRTR_SEOGWIPO", base.get("FEE_CVLCPT_MM_CRTR_SEOGWIPO") * 2);
        Map<String, Double> rtChg = Map.of(
                "FEE_CVLCPT_RT_CRTR_JEJU", Math.max(1.0, Math.floor(base.get("FEE_CVLCPT_RT_CRTR_JEJU") / 2)),
                "FEE_CVLCPT_RT_CRTR_SEOGWIPO", base.get("FEE_CVLCPT_RT_CRTR_SEOGWIPO") * 3);

        List<String> mismatches = new ArrayList<>();
        Set<String> outcomes = new LinkedHashSet<>();
        for (Map<String, Double> combo : List.of(Map.<String, Double>of(), mm2, rtChg)) {
            assertWithinRange(wbk, combo);
            FeeResult mine = recomputeFee(wbk, fee.path("lots"), merge(base, combo), false);
            JsonNode query = getOk(DETAIL_URL + "?sggNm={sggNm}&" + prd(), sggNm, vrblJson(combo));
            compareFee("SGG " + sggNm + " " + combo, mine, query, false, mismatches);
            outcomes.add(feeSignature(mine));
        }
        assertThat(mismatches).as("요금제 SGG 재계산 ≠ 화면 조회 (불일치 %d건)", mismatches.size()).isEmpty();
        assertThat(outcomes).as("3조합의 결과가 모두 달라야 대조가 의미 있다").hasSize(3);
    }

    // ═════════════════════════════════════════════ 3. 공유주차제

    @Test
    @DisplayName("공유주차제 서귀포시 — 계수 5조합: wbk.rows 재계산 = 같은 계수의 GET (후보·판정 불가 수 · 표시 순서 · 등급 · 주의 · 점수)")
    void shareRecomputeEqualsQuery() throws Exception {
        ZoneRcmdtnShareReportCreateRequest req = new ZoneRcmdtnShareReportCreateRequest();
        req.setBgngYmd(cv.bgngYmd());
        req.setEndYmd(cv.endYmd());
        req.setSggNm(SGP);
        req.setFileFmt("XLSX");
        JsonNode baseQuery = getOk(shareUrl(), SGP, null);
        boolean hasCand = false;
        for (JsonNode z : baseQuery.path("zones")) {
            hasCand |= "Y".equals(z.path("rvwTrgtYn").asText());
        }
        assumeTrue(hasCand, "서귀포시 공유주차 검토 후보가 0곳이라 보고서를 만들 수 없다");

        ZoneRcmdtnReportVO created = reportService.createShareReport(req, TESTER, TESTER_NM);
        assertThat(jdbcTemplate.queryForObject("SELECT file_fmt FROM parking.tb_rptp WHERE rptp_id = ?",
                String.class, created.getRptpId())).as("XLSX 저장").isEqualTo("XLSX");
        JsonNode share = snapshotTree(created.getRptpId()).path("share");
        JsonNode wbk = share.path("wbk");
        assertThat(wbk.path("rows").size()).as("wbk.rows = 표시 읍면동").isEqualTo(share.path("zones").size());

        Map<String, Double> base = aplcnVls(wbk);
        List<Map<String, Double>> combos = List.of(
                Map.of(),
                Map.of("CVLCPT_MM_CRTR_VL", 0.0),
                Map.of("CVLCPT_MM_CRTR_VL", 100.0, "CVLCPT_RT_CRTR_VL", 3.0),
                Map.of("CVLCPT_INCRS_WGVL", 1.0, "CVLCPT_INCRS_RT_UPLMT_VL", 10.0),
                Map.of("CVLCPT_RT_CRTR_VL", 20.0));

        List<String> mismatches = new ArrayList<>();
        Set<String> outcomes = new LinkedHashSet<>();
        for (Map<String, Double> combo : combos) {
            assertWithinRange(wbk, combo);
            List<ShareRow> mine = recomputeShare(wbk, merge(base, combo));
            JsonNode query = getOk(shareUrl(), SGP, vrblJson(combo));
            compareShare("SHARE " + combo, mine, query, mismatches);
            outcomes.add(mine.toString());
        }
        assertThat(mismatches).as("공유주차제 재계산 ≠ 화면 조회 (불일치 %d건)", mismatches.size()).isEmpty();
        assertThat(outcomes).as("5조합의 결과가 모두 달라야 대조가 의미 있다").hasSize(5);
    }

    // ═════════════════════════════════════════════ 4. 저장 형식 · 구 스냅샷

    @Test
    @DisplayName("fileFmt=XLS 는 두 POST 모두 400(@Valid) · 세 표 0행 증가")
    void xlsIsRejected() {
        String emd = data.busyEmd(cv, minCpcty, false, 1);
        assumeTrue(emd != null, "NARROW 후보가 있으면서 민원이 있는 읍면동이 없다");
        long rptpBefore = maxId("parking.tb_rptp", "rptp_id");

        Map<String, Object> feeBody = new LinkedHashMap<>();
        feeBody.put("emdCd", emd);
        feeBody.put("bgngYmd", cv.bgngYmd());
        feeBody.put("endYmd", cv.endYmd());
        feeBody.put("fileFmt", "XLS");
        Map<String, Object> shareBody = new LinkedHashMap<>();
        shareBody.put("bgngYmd", cv.bgngYmd());
        shareBody.put("endYmd", cv.endYmd());
        shareBody.put("fileFmt", "XLS");

        ResponseEntity<String> fee = restTemplate.exchange(FEE_REPORT_URL, HttpMethod.POST,
                new HttpEntity<>(feeBody, session), String.class);
        ResponseEntity<String> share = restTemplate.exchange(SHARE_REPORT_URL, HttpMethod.POST,
                new HttpEntity<>(shareBody, session), String.class);
        assertThat(fee.getStatusCode()).as("요금제 XLS → %s", fee.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(share.getStatusCode()).as("공유주차제 XLS → %s", share.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(maxId("parking.tb_rptp", "rptp_id")).as("보고서 0행 증가").isEqualTo(rptpBefore);
    }

    @Test
    @DisplayName("구 스냅샷(rslt_json 에 wbk 키 없음) — 요금제·공유주차제 스냅샷의 wbk 는 null")
    void legacySnapshotHasNoWbk() throws Exception {
        String emd = data.busyEmd(cv, minCpcty, false, 1);
        assumeTrue(emd != null, "NARROW 후보가 있으면서 민원이 있는 읍면동이 없다");
        ZoneRcmdtnReportVO fee = reportService.createFeeReport(feeRequest(emd, null, "PDF"), TESTER, TESTER_NM);
        assertThat(fee.getFee().getWbk()).as("전제 — 새 보고서는 wbk 가 있다").isNotNull();
        long legacyFee = insertLegacy("FEE", fee.getExcnId());
        assertThat(reportService.selectReportSnapshot(legacyFee).getFee().getWbk()).as("요금제 구 스냅샷").isNull();
        assertThat(snapshotTree(legacyFee).path("fee").has("wbk")).as("요금제 구 스냅샷 JSON 에 wbk 키 없음(NON_NULL)")
                .isFalse();

        ZoneRcmdtnShareReportCreateRequest req = new ZoneRcmdtnShareReportCreateRequest();
        req.setBgngYmd(cv.bgngYmd());
        req.setEndYmd(cv.endYmd());
        req.setFileFmt("PDF");
        ZoneRcmdtnReportVO share;
        try {
            share = reportService.createShareReport(req, TESTER, TESTER_NM);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            assumeTrue(false, "공유주차 검토 후보가 0곳이라 보고서를 만들 수 없다: " + e.getReason());
            return;
        }
        assertThat(share.getShare().getWbk()).isNotNull();
        long legacyShare = insertLegacy("SHARE", share.getExcnId());
        assertThat(reportService.selectReportSnapshot(legacyShare).getShare().getWbk()).as("공유주차제 구 스냅샷")
                .isNull();
    }

    // ═════════════════════════════════════════════ 5. 재계산기 — 요금제 (계획서 3-1 · 엑셀 규칙 · double)

    /** 주차장 1행의 재계산 결과(표시 순서대로) */
    private record FeeLot(long pkltId, String grd, double rvw, double cfc, double prrt, Long cum) {
    }

    private record FeeResult(List<FeeLot> lots) {
    }

    /** 시트 2 한 행 — D8 재정렬용 원자료 */
    private record FeeIn(long pkltId, long asgn, long cpcty, String sggNm, Double incrsRt) {
    }

    /**
     * 요금제 재계산. {@code lots} 는 응답 {@code fee.lots} 그대로(순서 무관) — D8 2차 키(배정↓ · 면수↓ · pkltId↑)로
     * 미리 정렬한 뒤 표시순번 = {@code SUMPRODUCT(--(P>Pᵢ))} + 앞 행 중 동점 수 + 1.
     */
    private static FeeResult recomputeFee(JsonNode wbk, JsonNode lots, Map<String, Double> v, boolean emdMode) {
        double months = wbk.path("months").asDouble();
        JsonNode fixed = wbk.path("fixed");
        double prrtTop = fixed.path("prrtTop").asDouble();
        double prrtHigh = fixed.path("prrtHigh").asDouble();

        // ── 시트 2 행 — D8 재정렬
        List<FeeIn> rows = new ArrayList<>();
        for (JsonNode l : lots) {
            JsonNode g = l.path("cvlcptIncrsRt");
            rows.add(new FeeIn(l.path("pkltId").asLong(), l.path("asgnCvlcptCnt").asLong(), l.path("pkltCpcty").asLong(),
                    l.path("sggNm").isTextual() ? l.path("sggNm").asText() : null,
                    g.isNumber() ? g.asDouble() : null));
        }
        rows.sort(Comparator.comparingLong(FeeIn::asgn).reversed()
                .thenComparing(Comparator.comparingLong(FeeIn::cpcty).reversed())
                .thenComparingLong(FeeIn::pkltId));

        double w1 = v.get("FEE_CVLCPT_CNT_WGVL");
        double w2 = v.get("FEE_CVLCPT_RT_WGVL");
        double wi = v.get("FEE_CVLCPT_INCRS_WGVL");
        double uplmt = v.get("FEE_CVLCPT_INCRS_RT_UPLMT_VL");
        int n = rows.size();
        double[] rvw = new double[n];
        double[] cfc = new double[n];
        double[] p = new double[n];
        for (int i = 0; i < n; i++) {
            FeeIn r = rows.get(i);
            boolean sgp = SGP.equals(r.sggNm());
            double mm = xlRound(r.asgn() / months, 6);
            double s1 = xlRound(mm * 100 / (sgp ? v.get("FEE_CVLCPT_MM_CRTR_SEOGWIPO") : v.get("FEE_CVLCPT_MM_CRTR_JEJU")), 6);
            double s2 = r.cpcty() <= 0 ? 0 : xlRound(xlRound(mm * 100 / r.cpcty(), 6) * 100
                    / (sgp ? v.get("FEE_CVLCPT_RT_CRTR_SEOGWIPO") : v.get("FEE_CVLCPT_RT_CRTR_JEJU")), 6);
            rvw[i] = xlRound((w1 * s1 + w2 * s2) / (w1 + w2), 6);
            cfc[i] = r.incrsRt() == null ? 1
                    : 1 + wi * Math.max(Math.min(r.incrsRt() / 100, uplmt / 100), -uplmt / 100);
            p[i] = xlRound(rvw[i] * cfc[i], 10);
        }
        int[] seq = displaySeq(p, null);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.comparingInt(i -> seq[i]));

        List<FeeLot> out = new ArrayList<>(n);
        long cum = 0;
        for (Integer i : order) {
            cum += rows.get(i).cpcty();
            String grd = p[i] >= prrtTop ? "TOP" : p[i] >= prrtHigh ? "HIGH" : "LOW";
            out.add(new FeeLot(rows.get(i).pkltId(), grd, rvw[i], cfc[i], p[i],
                    emdMode ? cum : null));
        }
        return new FeeResult(out);
    }

    private static String inptYn(JsonNode wbk, String vrblCd) {
        for (JsonNode x : wbk.path("vrbls")) {
            if (vrblCd.equals(x.path("vrblCd").asText())) {
                return x.path("inptYn").asText();
            }
        }
        return "?";
    }

    private static String feeSignature(FeeResult r) {
        return r.lots().toString();
    }

    private static void compareFee(String label, FeeResult mine, JsonNode query, boolean emdMode, List<String> mm) {
        // 🔁 TSK-4340 — 서버 판정은 제거됐다(zone 은 위치 3필드뿐)
        if (!emdMode && !query.path("zone").isNull() && !query.path("zone").isMissingNode()) {
            mm.add(label + " zone: SGG 모드인데 zone 이 있다 " + query.path("zone"));
        }

        JsonNode lots = query.path("lots");
        if (lots.size() != mine.lots().size()) {
            mm.add(label + " lots 수: 재계산 " + mine.lots().size() + " ≠ 조회 " + lots.size());
            return;
        }
        List<Long> mineIds = new ArrayList<>();
        List<Long> queryIds = new ArrayList<>();
        for (int k = 0; k < lots.size(); k++) {
            FeeLot a = mine.lots().get(k);
            JsonNode b = lots.get(k);
            String at = label + " rnk " + (k + 1) + " (재계산 pkltId=" + a.pkltId() + " · 조회 pkltId="
                    + b.path("pkltId").asLong() + ")";
            mineIds.add(a.pkltId());
            queryIds.add(b.path("pkltId").asLong());
            if (b.path("rnk").asInt() != k + 1) {
                mm.add(at + " 조회 rnk=" + b.path("rnk").asInt());
            }
            if (a.pkltId() != b.path("pkltId").asLong()) {
                mm.add(at + " 순서 불일치 · 재계산 P=" + a.prrt() + " · 조회 incrsRfltRvwScr=" + b.path("incrsRfltRvwScr"));
                continue;
            }
            eqText(mm, at + " rvwGrdCd", a.grd(), b.path("rvwGrdCd"));
            eqNum(mm, at + " incrsRfltRvwScr(1)", xlRound(a.prrt(), 1), b.path("incrsRfltRvwScr"));
            eqNum(mm, at + " rvwScr(1)", xlRound(a.rvw(), 1), b.path("rvwScr"));
            eqNum(mm, at + " incrsCfc(3)", xlRound(a.cfc(), 3), b.path("incrsCfc"));
            if (emdMode) {
                eqNum(mm, at + " cumCpcty", a.cum() == null ? null : a.cum().doubleValue(), b.path("cumCpcty"));
            }
        }
        if (!mineIds.equals(queryIds)) {
            mm.add(label + " rnk 순 pkltId: 재계산 " + mineIds + " ≠ 조회 " + queryIds);
        }
    }

    // ═════════════════════════════════════════════ 6. 재계산기 — 공유주차제 (계획서 3-2 · 엑셀 규칙 · double)

    /** 읍면동 1행의 재계산 결과 — 표시 순서대로 돌려준다 */
    private record ShareRow(String emdCd, Integer rnk, String grd, boolean cand, Double s, Double g, double cfc,
                            Double p, List<String> cutn, boolean vaclotCand) {
    }

    private static List<ShareRow> recomputeShare(JsonNode wbk, Map<String, Double> v) {
        double months = wbk.path("months").asDouble();
        String trendMode = wbk.path("trendMode").asText();
        JsonNode fixed = wbk.path("fixed");
        double core = fixed.path("coreMltp").asDouble();
        double bndryRt = fixed.path("bndryRt").asDouble();
        double minBase = fixed.path("minTrendBase").asDouble();
        double mmC = v.get("CVLCPT_MM_CRTR_VL");
        double rtC = v.get("CVLCPT_RT_CRTR_VL");
        double wi = v.get("CVLCPT_INCRS_WGVL");
        double uplmt = v.get("CVLCPT_INCRS_RT_UPLMT_VL");

        // 🔴 행 순서 = wbk.rows 그대로(cvlcptCnt↓ · emdCd↑ — D8). 다시 정렬하지 않는다
        JsonNode rows = wbk.path("rows");
        int n = rows.size();
        String[] emd = new String[n];
        String[] grd = new String[n];
        boolean[] cand = new boolean[n];
        Double[] s = new Double[n];
        Double[] g = new Double[n];
        double[] cfc = new double[n];
        Double[] p = new Double[n];
        List<List<String>> cutn = new ArrayList<>();
        boolean[] vaclot = new boolean[n];
        for (int i = 0; i < n; i++) {
            JsonNode r = rows.get(i);
            emd[i] = r.path("emdCd").asText();
            double cnt = r.path("cvlcptCnt").asDouble();
            double cpcty = r.path("pkltCpcty").asDouble();
            double mm = xlRound(cnt / months, 6);
            Double rt = cpcty <= 0 ? null : xlRound(mm * 100 / cpcty, 6);
            grd[i] = shareGrade(mm, rt, mmC, rtC, core);
            cand[i] = isCand(grd[i]);
            s[i] = rt == null ? null
                    : mmC <= 0 ? xlRoundDown(xlRound(rt / rtC, 10), 6)
                    : Math.min(xlRoundDown(xlRound(mm / mmC, 10), 6), xlRoundDown(xlRound(rt / rtC, 10), 6));
            JsonNode rc = r.path("rcntCnt");
            JsonNode bf = r.path("bfrCnt");
            g[i] = "NONE".equals(trendMode) || !bf.isNumber() || bf.asDouble() < minBase ? null
                    : xlRound((rc.asDouble() - bf.asDouble()) / bf.asDouble(), 6);
            cfc[i] = g[i] == null ? 1 : 1 + wi * Math.max(Math.min(g[i], uplmt / 100), -uplmt / 100);
            p[i] = s[i] == null ? null : xlRound(s[i] * cfc[i], 10);

            List<String> c = new ArrayList<>();
            if (cand[i]) {
                double top = r.path("topPntCvlcptCnt").asDouble();
                double mmEx = xlRound((cnt - top) / months, 6);
                Double rtEx = cpcty <= 0 ? null : xlRound(mmEx * 100 / cpcty, 6);
                if (!isCand(shareGrade(mmEx, rtEx, mmC, rtC, core))) {
                    c.add("TOP_PNT");
                }
                boolean bndry = mm < xlRound(mmC * bndryRt, 10) || rt < xlRound(rtC * bndryRt, 10);
                if (!bndry && "CORE".equals(grd[i])) {
                    bndry = mm < xlRound(mmC * core * bndryRt, 10) || rt < xlRound(rtC * core * bndryRt, 10);
                }
                if (bndry) {
                    c.add("CRTR_BNDRY");
                }
            }
            cutn.add(c);
            double vac = r.path("vaclotExclPkltCpcty").asDouble();
            vaclot[i] = isCand(shareGrade(mm, vac <= 0 ? null : xlRound(mm * 100 / vac, 6), mmC, rtC, core));
        }

        double[] pp = new double[n];
        boolean[] scored = new boolean[n];
        for (int i = 0; i < n; i++) {
            scored[i] = p[i] != null;
            pp[i] = scored[i] ? p[i] : 0;
        }
        int[] seq = displaySeq(pp, scored);

        // 표시 순서 — 순번 있는 행(순번 순) → 순번 없는 그 밖 → 판정 불가(UNABLE), 같은 무리 안은 행 순서
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.<Integer>comparingInt(i -> scored[i] ? 0 : 1)
                .thenComparingInt(i -> scored[i] ? seq[i] : 0)
                .thenComparingInt(i -> "UNABLE".equals(grd[i]) ? 1 : 0)
                .thenComparingInt(i -> i));
        List<ShareRow> out = new ArrayList<>(n);
        for (Integer i : order) {
            out.add(new ShareRow(emd[i], scored[i] ? seq[i] : null, grd[i], cand[i], s[i], g[i], cfc[i], p[i],
                    cutn.get(i), vaclot[i]));
        }
        return out;
    }

    private static String shareGrade(double mm, Double rt, double mmC, double rtC, double core) {
        if (rt == null) {
            return "UNABLE";
        }
        if (mm >= mmC && rt >= rtC) {
            return mm >= mmC * core && rt >= rtC * core ? "CORE" : "CAND";
        }
        return mm >= mmC ? "CVLCPT_ONLY" : "NONE";
    }

    private static boolean isCand(String grd) {
        return "CORE".equals(grd) || "CAND".equals(grd);
    }

    private static void compareShare(String label, List<ShareRow> mine, JsonNode query, List<String> mm) {
        JsonNode zones = query.path("zones");
        int qCand = 0;
        int qUnable = 0;
        for (JsonNode z : zones) {
            qCand += "Y".equals(z.path("rvwTrgtYn").asText()) ? 1 : 0;
            qUnable += "UNABLE".equals(z.path("rvwGrdCd").asText()) ? 1 : 0;
        }
        long myCand = mine.stream().filter(ShareRow::cand).count();
        long myUnable = mine.stream().filter(r -> "UNABLE".equals(r.grd())).count();
        if (myCand != qCand) {
            mm.add(label + " 후보 수: 재계산 " + myCand + " ≠ 조회 " + qCand);
        }
        if (myUnable != qUnable) {
            mm.add(label + " 판정 불가 수: 재계산 " + myUnable + " ≠ 조회 " + qUnable);
        }
        if (zones.size() != mine.size()) {
            mm.add(label + " 행 수: 재계산 " + mine.size() + " ≠ 조회 " + zones.size());
            return;
        }
        List<String> mineOrder = new ArrayList<>();
        List<String> queryOrder = new ArrayList<>();
        for (int k = 0; k < zones.size(); k++) {
            ShareRow a = mine.get(k);
            JsonNode b = zones.get(k);
            mineOrder.add(a.emdCd());
            queryOrder.add(b.path("emdCd").asText());
            String at = label + " 표시 " + (k + 1) + "행 (재계산 " + a.emdCd() + " · 조회 " + b.path("emdCd").asText()
                    + " " + b.path("emdNm").asText() + ")";
            if (!a.emdCd().equals(b.path("emdCd").asText())) {
                mm.add(at + " 순서 불일치 · 재계산 P=" + a.p() + " · 조회 incrsRfltRvwScr=" + b.path("incrsRfltRvwScr"));
                continue;
            }
            eqNum(mm, at + " rnk", a.rnk() == null ? null : a.rnk().doubleValue(), b.path("rnk"));
            eqText(mm, at + " rvwGrdCd", a.grd(), b.path("rvwGrdCd"));
            eqText(mm, at + " rvwTrgtYn", a.cand() ? "Y" : "N", b.path("rvwTrgtYn"));
            eqText(mm, at + " vaclotExclRvwTrgtYn", a.vaclotCand() ? "Y" : "N", b.path("vaclotExclRvwTrgtYn"));
            List<String> qCut = new ArrayList<>();
            b.path("cutnCds").forEach(c -> qCut.add(c.asText()));
            if (!a.cutn().equals(qCut)) {
                mm.add(at + " cutnCds: 재계산 " + a.cutn() + " ≠ 조회 " + qCut);
            }
            eqNum(mm, at + " rvwScr(2 버림)", a.s() == null ? null : xlRoundDown(xlRound(a.s(), 10), 2), b.path("rvwScr"));
            eqNum(mm, at + " incrsRfltRvwScr(2 버림)", a.p() == null ? null : xlRoundDown(xlRound(a.p(), 10), 2),
                    b.path("incrsRfltRvwScr"));
            eqNum(mm, at + " cvlcptIncrsRt(1)", a.g() == null ? null : xlRound(a.g() * 100, 1), b.path("cvlcptIncrsRt"));
            eqNum(mm, at + " incrsCfc(3)", xlRound(a.cfc(), 3), b.path("incrsCfc"));
        }
        if (!mineOrder.equals(queryOrder)) {
            mm.add(label + " 표시 순서(emdCd): 재계산 " + mineOrder + " ≠ 조회 " + queryOrder);
        }
    }

    // ═════════════════════════════════════════════ 7. 엑셀 함수 · 순번 (D8 · D9)

    /** 엑셀 {@code ROUND(x,n)} — 유효숫자 15자리로 맞춘 뒤 0 에서 먼 쪽(양수 HALF_UP) */
    private static double xlRound(double x, int n) {
        if (x == 0 || !Double.isFinite(x)) {
            return x;
        }
        return new BigDecimal(x).round(MC15).setScale(n, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 엑셀 {@code ROUNDDOWN(x,n)} — 유효숫자 15자리로 맞춘 뒤 0 쪽으로. 🔴 호출부는 D9 대로 항상
     * {@code ROUNDDOWN(ROUND(x,10),n)} 모양으로 부른다.
     */
    private static double xlRoundDown(double x, int n) {
        if (x == 0 || !Double.isFinite(x)) {
            return x;
        }
        return new BigDecimal(x).round(MC15).setScale(n, RoundingMode.DOWN).doubleValue();
    }

    /**
     * D8 표시순번 — {@code SUMPRODUCT(--(P>Pᵢ))} + 앞 행 중 동점 수 + 1. {@code scored} 가 주어지면 그 행끼리만 센다
     * (공유주차제 — {@code S<>""} 행만).
     */
    private static int[] displaySeq(double[] p, boolean[] scored) {
        int n = p.length;
        int[] seq = new int[n];
        for (int i = 0; i < n; i++) {
            if (scored != null && !scored[i]) {
                continue;
            }
            int gt = 0;
            int tieBefore = 0;
            for (int j = 0; j < n; j++) {
                if (scored != null && !scored[j]) {
                    continue;
                }
                if (p[j] > p[i]) {
                    gt++;
                } else if (j < i && p[j] == p[i]) {
                    tieBefore++;
                }
            }
            seq[i] = gt + tieBefore + 1;
        }
        return seq;
    }

    // ═════════════════════════════════════════════ 8. 비교 · 계수 도우미

    private static void eqNum(List<String> mm, String what, Double mine, JsonNode resp) {
        boolean respNull = resp == null || resp.isNull() || resp.isMissingNode();
        if (mine == null || respNull) {
            if (mine != null || !respNull) {
                mm.add(what + ": 재계산 " + mine + " ≠ 조회 " + resp);
            }
            return;
        }
        if (BigDecimal.valueOf(mine).compareTo(resp.decimalValue()) != 0) {
            mm.add(what + ": 재계산 " + BigDecimal.valueOf(mine).toPlainString() + " ≠ 조회 " + resp.asText());
        }
    }

    private static void eqText(List<String> mm, String what, String mine, JsonNode resp) {
        String r = resp == null || resp.isNull() || resp.isMissingNode() ? null : resp.asText();
        if (!java.util.Objects.equals(mine, r)) {
            mm.add(what + ": 재계산 " + mine + " ≠ 조회 " + r);
        }
    }

    /** wbk.vrbls → {@code vrblCd → aplcnVl} */
    private static Map<String, Double> aplcnVls(JsonNode wbk) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (JsonNode r : wbk.path("vrbls")) {
            out.put(r.path("vrblCd").asText(), r.path("aplcnVl").asDouble());
        }
        return out;
    }

    private static Map<String, Double> merge(Map<String, Double> base, Map<String, Double> combo) {
        Map<String, Double> out = new LinkedHashMap<>(base);
        out.putAll(combo);
        return out;
    }

    /** 조합 값이 설정 행 범위(wbk.vrbls 의 minVl·maxVl) 밖이면 조합 자체가 400 이라 건너뛴다 */
    private static void assertWithinRange(JsonNode wbk, Map<String, Double> combo) {
        for (Map.Entry<String, Double> e : combo.entrySet()) {
            JsonNode row = null;
            for (JsonNode r : wbk.path("vrbls")) {
                if (e.getKey().equals(r.path("vrblCd").asText())) {
                    row = r;
                }
            }
            assertThat(row).as("wbk.vrbls 에 %s 가 없다", e.getKey()).isNotNull();
            assumeTrue(!row.path("minVl").isNumber() || e.getValue() >= row.path("minVl").asDouble(),
                    e.getKey() + "=" + e.getValue() + " 이 최솟값 미만이다");
            assumeTrue(!row.path("maxVl").isNumber() || e.getValue() <= row.path("maxVl").asDouble(),
                    e.getKey() + "=" + e.getValue() + " 이 최댓값 초과다");
        }
    }

    /** 조합 → {@code vrblVls} JSON. 빈 조합이면 null(입력 없음 = 기본) */
    private static String vrblJson(Map<String, Double> combo) {
        if (combo.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Double> e : combo.entrySet()) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(e.getKey()).append("\":")
                    .append(BigDecimal.valueOf(e.getValue()).stripTrailingZeros().toPlainString());
        }
        return sb.append('}').toString();
    }

    // ═════════════════════════════════════════════ 9. HTTP · DB 도우미

    /** 자료 끝 3개월 — 시작이 자료 시작일 앞이면 자료 시작일로 */
    private static Win last3Months(Win range) {
        LocalDate bgng = range.end().minusMonths(3).plusDays(1);
        return new Win(bgng.isBefore(range.bgng()) ? range.bgng() : bgng, range.end());
    }

    private String prd() {
        return "bgngYmd=" + cv.bgngYmd() + "&endYmd=" + cv.endYmd()
                + "&crdnBgngYmd=" + crdn.bgngYmd() + "&crdnEndYmd=" + crdn.endYmd();
    }

    private String shareUrl() {
        return SHARE_URL + "?bgngYmd=" + cv.bgngYmd() + "&endYmd=" + cv.endYmd() + "&sggNm={sggNm}";
    }

    private ZoneRcmdtnReportCreateRequest feeRequest(String emdCd, String sggNm, String fileFmt) {
        ZoneRcmdtnReportCreateRequest req = new ZoneRcmdtnReportCreateRequest();
        req.setEmdCd(emdCd);
        req.setSggNm(sggNm);
        req.setBgngYmd(cv.bgngYmd());
        req.setEndYmd(cv.endYmd());
        req.setCrdnBgngYmd(crdn.bgngYmd());
        req.setCrdnEndYmd(crdn.endYmd());
        req.setFileFmt(fileFmt);
        return req;
    }

    /** 스냅샷 조회 응답을 프론트가 받는 JSON 그대로(서비스 매퍼로 직렬화 → BigDecimal 로 되읽기) */
    private JsonNode snapshotTree(long rptpId) throws Exception {
        return objectMapper.readTree(springMapper.writeValueAsString(reportService.selectReportSnapshot(rptpId)));
    }

    /** 🔴 한글 sggNm·JSON 은 URI 변수로 넘긴다 — 템플릿 확장이 퍼센트 인코딩한다 */
    private JsonNode getOk(String url, String sggNm, String vrblJson) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (sggNm != null) {
            vars.put("sggNm", sggNm);
        }
        String template = url;
        if (vrblJson != null) {
            template += "&vrblVls={vrblVls}";
            vars.put("vrblVls", vrblJson);
        }
        ResponseEntity<String> res = restTemplate.exchange(
                template, HttpMethod.GET, new HttpEntity<>(session), String.class, vars);
        JsonNode body = null;
        try {
            body = res.getBody() == null ? null : objectMapper.readTree(res.getBody());
        } catch (JsonProcessingException e) {   // 4.3(TSK-4336) — readTree 가 던지는 파싱 예외만
            // 아래 상태코드 단언이 원문을 보여 준다
            org.slf4j.LoggerFactory.getLogger(ZoneRcmdtnWorkbookIntegrationTest.class).debug("응답 JSON 파싱 생략 — {}", e.getClass().getSimpleName());   // 4.2
        }
        if (body != null && body.path("excnId").isIntegralNumber()) {
            createdExcnIds.add(body.path("excnId").asLong());
        }
        assertThat(res.getStatusCode()).as("GET %s vrblVls=%s → %s", url, vrblJson, res.getBody())
                .isEqualTo(HttpStatus.OK);
        return body;
    }

    /** 실제 보고서의 결과 JSON 에서 wbk 키만 걷어 '이 작업 전 스냅샷' 을 만든다 — 생성자 TESTER 라 정리된다 */
    private long insertLegacy(String rcmdtnTypeCd, long srcExcnId) {
        String legacyRslt = jdbcTemplate.queryForObject(
                "SELECT (rslt_json - 'wbk')::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class, srcExcnId);
        Long excnId = jdbcTemplate.queryForObject("INSERT INTO parking.tb_anls_excn"
                + " (anls_type_cd, excutr_id, anls_bgng_ymd, anls_end_ymd, anls_param_json, excn_stts_cd, excn_dt)"
                + " VALUES (?, ?, ?, ?, ?::jsonb, 'DONE', now()) RETURNING excn_id",
                Long.class, RECOMMEND, TESTER, cv.bgngYmd(), cv.endYmd(),
                "{\"rcmdtnTypeCd\":\"" + rcmdtnTypeCd + "\"}");
        jdbcTemplate.update("INSERT INTO parking.tb_anls_rslt (excn_id, rslt_json) VALUES (?, ?::jsonb)",
                excnId, legacyRslt);
        return jdbcTemplate.queryForObject("INSERT INTO parking.tb_rptp"
                + " (crt_type, excn_id, rptp_nm, stts_cd, file_fmt, creatr_id, creatr_nm, crt_ymd)"
                + " VALUES ('MANUAL', ?, ?, 'DONE', 'PDF', ?, ?, now()) RETURNING rptp_id",
                Long.class, excnId, "TSK-4340 구 스냅샷(" + rcmdtnTypeCd + ")", TESTER, TESTER_NM);
    }

    private long maxId(String table, String idCol) {
        return jdbcTemplate.queryForObject("SELECT coalesce(max(" + idCol + "), 0) FROM " + table, Long.class);
    }

    private List<Map<String, Object>> readSettings() {
        return jdbcTemplate.queryForList("SELECT anls_type_cd, vrbl_cd, stng_vl, use_yn, mdfcn_dt"
                + " FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd");
    }

    private void run(List<RuntimeException> failures, String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
