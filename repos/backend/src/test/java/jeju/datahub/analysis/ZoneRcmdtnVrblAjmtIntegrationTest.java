package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.ZoneRcmdtnTestData.Src;
import jeju.datahub.analysis.ZoneRcmdtnTestData.Win;
import jeju.datahub.analysis.service.ZoneRcmdtnReportCreateRequest;
import jeju.datahub.analysis.service.ZoneRcmdtnReportService;
import jeju.datahub.analysis.service.ZoneRcmdtnReportVO;
import jeju.datahub.analysis.service.ZoneRcmdtnResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnService;
import jeju.datahub.analysis.service.ZoneRcmdtnShareReportCreateRequest;
import jeju.datahub.analysis.service.ZoneRcmdtnShareResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareService;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 구역추천 분석 계수 일시 조정 통합 테스트 (TSK-4325 Step 4).
 *
 * <p>요금제({@code RECOMMEND_FEE} — {@code /zones}·상세·요금제 보고서)와 공유주차제({@code RECOMMEND_SHARE} — {@code /share}·
 * 공유주차제 보고서)가 {@code vrblVls} 로 계산되고, 적용 내역({@code vrblAjmt})이 실행 행·응답에 남는지 본다.
 *
 * <h2>🔴 설정 표를 바꾸지 않는다</h2>
 * 이 기능의 요점이 "설정은 그대로" 라 이 테스트도 설정 행에 쓰지 않는다. {@link #assertSettingsUntouched()} 가
 * 테스트 전후의 {@code stng_vl}·{@code mdfcn_dt} 를 대조한다. 기대값은 상수가 아니라 <b>DB 현재 설정값</b>에서 계산한다.
 *
 * <h2>🔴 정리 — 내가 만든 행만 지운다</h2>
 * HTTP 조회가 남긴 실행 행은 응답 {@code excnId} 로, 보고서 서비스 직접 호출이 남긴 행은 기준선 초과 + 생성자
 * {@value #TESTER} 로 지운다({@code ZoneRcmdtnReportIntegrationTest} 선례). 조회 날짜는 DB 보유 구간에서 유도한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TSK-4325 Step 4 — 구역추천 분석 계수 일시 조정 (요금제 · 공유주차제)")
class ZoneRcmdtnVrblAjmtIntegrationTest {

    private static final String ZONES_URL = "/api/analysis/zone-recommendation/zones";
    private static final String DETAIL_URL = "/api/analysis/zone-recommendation";
    private static final String SHARE_URL = "/api/analysis/zone-recommendation/share";

    private static final String RECOMMEND = "RECOMMEND";
    private static final String TESTER = "tester4325";
    private static final String TESTER_NM = "계수테스터";

    private static final int FEE_VRBL_CNT = 10;
    private static final int SHARE_VRBL_CNT = 4;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ZoneRcmdtnService zoneRcmdtnService;

    @Autowired
    private ZoneRcmdtnShareService zoneRcmdtnShareService;

    @Autowired
    private ZoneRcmdtnReportService reportService;

    /** 🔴 응답 비교용 — 서비스가 저장·복원에 쓰는 매퍼와 같은 빈이다 */
    @Autowired
    private ObjectMapper springMapper;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private ZoneRcmdtnTestData data;
    private Win cv;
    private Win crdn;
    private BigDecimal minCpcty;
    private BigDecimal rng;

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
        cv = ZoneRcmdtnTestData.head(cvRange);
        crdn = ZoneRcmdtnTestData.head(crdnRange);
        minCpcty = data.appliedVl(RECOMMEND, "SWTC_TRGT_MIN_CPCTY");
        rng = data.appliedVl(RECOMMEND, "ALTMNT_RNG_VL");
        assertThat(minCpcty).isNotNull();
        assertThat(rng).isNotNull();
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
        assertSettingsUntouched();
    }

    // ───────────────────────── 요금제 ─────────────────────────

    @Test
    @DisplayName("요금제 — ALTMNT_RNG_VL 입력이 crtrVls·배정에 반영되고(독립 재계산) 적용 내역·실행 행에 남는다")
    void feeAltmntRngChangesAssignment() throws Exception {
        String emd = feeEmd();
        BigDecimal max = bound("ALTMNT_RNG_VL", "max_vl");
        BigDecimal min = bound("ALTMNT_RNG_VL", "min_vl");
        BigDecimal changed = rng.compareTo(max) < 0 ? max : min;

        JsonNode base = getOk(detailUrl(emd), null, null);
        JsonNode body = getOk(detailUrl(emd), null, "{\"ALTMNT_RNG_VL\":" + changed.toPlainString() + "}");

        assertThat(body.path("crtrVls").path("altmntRngVl").decimalValue()).isEqualByComparingTo(changed);
        long expected = data.asgnTotal(Src.CV, emd, cv, minCpcty, false, changed);
        assertThat(body.path("asgn").path("cvlcptAsgn").asLong()).as("바뀐 반경의 배정 합 = 독립 재계산").isEqualTo(expected);
        assertThat(base.path("asgn").path("cvlcptAsgn").asLong()).as("입력 없는 조회는 설정 반경 그대로")
                .isEqualTo(data.asgnTotal(Src.CV, emd, cv, minCpcty, false, rng));
        if (expected != base.path("asgn").path("cvlcptAsgn").asLong()) {
            assertThat(lotScores(body)).as("배정이 바뀌면 순위 점수도 바뀐다").isNotEqualTo(lotScores(base));
        }

        JsonNode ajmt = body.path("vrblAjmt");
        assertThat(ajmt.path("ajmtYn").asText()).isEqualTo("Y");
        assertThat(ajmt.path("vrbls").size()).as("요금제 허용 목록 전체").isEqualTo(FEE_VRBL_CNT);
        JsonNode row = vrbl(ajmt, "ALTMNT_RNG_VL");
        assertThat(row.path("stngVl").decimalValue()).isEqualByComparingTo(rng);
        assertThat(row.path("aplcnVl").decimalValue()).isEqualByComparingTo(changed);
        assertThat(vrbl(ajmt, "SWTC_TRGT_MIN_CPCTY").path("aplcnVl").decimalValue()).as("조정 안 한 행은 설정값")
                .isEqualByComparingTo(data.appliedVl(RECOMMEND, "SWTC_TRGT_MIN_CPCTY"));

        JsonNode param = paramJson(body.path("excnId").asLong());
        assertThat(param.path("vrblAjmt")).as("anls_param_json.vrblAjmt = 응답").isEqualTo(ajmt);
        assertThat(param.path("crtrVls").path("altmntRngVl").decimalValue()).isEqualByComparingTo(changed);
        // 🔁 TSK-4340 — 읍면동 판정 제거로 판정 계수·POLICY_SIM 2종은 더 굳히지 않는다
        for (String gone : List.of("pcshrCrtrVl", "paidDmndRt", "stsfRtThrsh", "cvlcptMmCrtrVl", "ilglCrtrVl")) {
            assertThat(param.path("crtrVls").has(gone)).as("anls_param_json.crtrVls.%s 없음", gone).isFalse();
        }
    }

    @Test
    @DisplayName("요금제 — FEE 가중치·기준값 입력이 검토지수 산식(가중합)과 순위에 반영된다")
    void feeWeightsAndCriteriaChangeScoreAndRank() throws Exception {
        String emd = feeEmd();
        String sggNm = (String) data.admdst(emd).get("sgg_nm");
        String sggUrl = DETAIL_URL + "?sggNm={sggNm}&" + prd();

        JsonNode cntOnly = getOk(sggUrl, sggNm, "{\"FEE_CVLCPT_CNT_WGVL\":100,\"FEE_CVLCPT_RT_WGVL\":0}");
        JsonNode rtOnly = getOk(sggUrl, sggNm, "{\"FEE_CVLCPT_CNT_WGVL\":0,\"FEE_CVLCPT_RT_WGVL\":100}");
        JsonNode lots = cntOnly.path("lots");
        assumeTrue(lots.size() >= 3, "시군구 후보 주차장이 3개소 미만이다");

        BigDecimal tol = new BigDecimal("0.02");
        for (JsonNode l : lots) {
            assertThat(l.path("rvwScr").decimalValue().subtract(l.path("cvlcptCntScr").decimalValue()).abs())
                    .as("민원강도 100 → 검토지수 = 민원강도 점수: %s", l).isLessThanOrEqualTo(tol);
        }
        for (JsonNode l : rtOnly.path("lots")) {
            assertThat(l.path("rvwScr").decimalValue().subtract(l.path("cvlcptRtScr").decimalValue()).abs())
                    .as("면당민원 100 → 검토지수 = 면당민원 점수: %s", l).isLessThanOrEqualTo(tol);
        }
        assertRankOrder(lots);
        assertRankOrder(rtOnly.path("lots"));
        assertThat(cntOnly.path("crtrVls").path("feeCvlcptCntWgvl").decimalValue()).isEqualByComparingTo("100");
        assertThat(rtOnly.path("crtrVls").path("feeCvlcptRtWgvl").decimalValue()).isEqualByComparingTo("100");
        assertThat(lotIds(cntOnly)).as("가중치가 반대면 순위가 달라진다").isNotEqualTo(lotIds(rtOnly));

        // 기준값 2배 → 민원강도 점수는 반 (DB 현재 설정값 기준)
        String crtrCd = "서귀포시".equals(sggNm) ? "FEE_CVLCPT_MM_CRTR_SEOGWIPO" : "FEE_CVLCPT_MM_CRTR_JEJU";
        BigDecimal crtr = data.appliedVl(RECOMMEND, crtrCd);
        BigDecimal doubled = crtr.multiply(BigDecimal.valueOf(2));
        assumeTrue(doubled.compareTo(bound(crtrCd, "max_vl")) <= 0, crtrCd + " 을 2배로 올릴 여유가 없다");
        JsonNode base = getOk(sggUrl, sggNm, null);
        JsonNode half = getOk(sggUrl, sggNm, "{\"" + crtrCd + "\":" + doubled.toPlainString() + "}");
        Map<Long, BigDecimal> baseScr = byLot(base, "cvlcptCntScr");
        Map<Long, BigDecimal> halfScr = byLot(half, "cvlcptCntScr");
        assertThat(halfScr.keySet()).isEqualTo(baseScr.keySet());
        for (Map.Entry<Long, BigDecimal> e : halfScr.entrySet()) {
            assertThat(e.getValue().multiply(BigDecimal.valueOf(2)).subtract(baseScr.get(e.getKey())).abs())
                    .as("민원강도 점수 반감: pkltId=%s", e.getKey())
                    // 응답이 소수 1자리 반올림이라 2배 값은 ±0.1, 기준 쪽은 ±0.05 흔들린다
                    .isLessThanOrEqualTo(new BigDecimal("0.15"));
        }
        assertThat(vrbl(half.path("vrblAjmt"), crtrCd).path("aplcnVl").decimalValue()).isEqualByComparingTo(doubled);
    }

    @Test
    @DisplayName("요금제 — 🔁 TSK-4340 판정 계수 3개(STSF_RT_THRSH·CVLCPT_MM_CRTR_VL·ILGL_CRTR_VL)는 목록 밖 → 400 · /zones 원소는 위치 3필드 · 상세 zone = 목록 원소")
    void judgeCoefficientsAreOutOfScope() {
        String emd = feeEmd();
        for (String cd : List.of("STSF_RT_THRSH", "CVLCPT_MM_CRTR_VL", "ILGL_CRTR_VL")) {
            String bad = "{\"" + cd + "\":" + bound(cd, "min_vl").toPlainString() + "}";
            assertThat(call(detailUrl(emd), null, bad).getStatusCode()).as("상세 %s", bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(call(ZONES_URL + "?" + prd(), null, bad).getStatusCode()).as("/zones %s", bad)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        JsonNode zones = getOk(ZONES_URL + "?" + prd(), null, null);
        JsonNode detail = getOk(detailUrl(emd), null, null);
        JsonNode fromList = null;
        for (JsonNode z : zones) {
            List<String> keys = new ArrayList<>();
            z.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).as("/zones 원소 키").containsExactlyInAnyOrder("emdCd", "emdNm", "sggNm");
            if (emd.equals(z.path("emdCd").asText())) {
                fromList = z;
            }
        }
        assertThat(fromList).as("목록에 고른 동이 있다").isNotNull();
        assertThat(detail.path("zone")).as("상세 zone = 목록 원소").isEqualTo(fromList);
        assertThat(detail.path("lots")).allSatisfy(l -> assertThat(l.has("reachNeed")).isFalse());
    }

    @Test
    @DisplayName("요금제 — FEE 가중치 합계 99 · 목록 밖 코드 · 형식 오류는 400 이고 실행 행·보고서를 남기지 않는다")
    void feeRejectsBadInput() {
        BigDecimal cnt = data.appliedVl(RECOMMEND, "FEE_CVLCPT_CNT_WGVL");
        BigDecimal rt = data.appliedVl(RECOMMEND, "FEE_CVLCPT_RT_WGVL");
        BigDecimal cnt99 = BigDecimal.valueOf(99).subtract(rt);
        assumeTrue(cnt99.signum() >= 0, "FEE 가중치 합계 99 를 만들 수 없다");
        String emd = feeEmd();
        long before = countExcn();

        for (String bad : List.of(
                "{\"FEE_CVLCPT_CNT_WGVL\":" + cnt99.toPlainString() + "}",                       // 합계 99
                "{\"FEE_CVLCPT_CNT_WGVL\":" + cnt.toPlainString() + ",\"FEE_CVLCPT_RT_WGVL\":"
                        + BigDecimal.valueOf(99).subtract(cnt).toPlainString() + "}",            // 묶음 합계 99
                "{\"CVLCPT_RT_CRTR_VL\":7}",                                                     // 공유주차제 변수
                "{\"PCSHR_CRTR_VL\":0.85}",                                                      // POLICY_SIM
                "{\"STSF_RT_THRSH\":50}",                                                       // 🔁 TSK-4340 판정 계수(목록 밖)
                "{\"ALTMNT_RNG_VL\":",                                                            // 형식 오류
                "{\"ALTMNT_RNG_VL\":" + bound("ALTMNT_RNG_VL", "max_vl").add(BigDecimal.ONE).toPlainString() + "}")) {
            assertThat(call(detailUrl(emd), null, bad).getStatusCode()).as("상세 %s", bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(call(ZONES_URL + "?" + prd(), null, bad).getStatusCode()).as("/zones %s", bad)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(countExcn()).as("400 은 실행 행을 남기지 않는다").isEqualTo(before);

        ZoneRcmdtnReportCreateRequest req = feeRequest(emd, Map.of("FEE_CVLCPT_CNT_WGVL", cnt99));
        assertThatThrownBy(() -> reportService.createFeeReport(req, TESTER, TESTER_NM))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThat(countReportsByTester()).isZero();
    }

    @Test
    @DisplayName("요금제 — 입력이 없거나 설정값과 값이 같으면(70 vs 70.00) ajmtYn='N' 이고 허용 목록 전체를 담는다")
    void feeNoAdjustmentIsN() {
        String emd = feeEmd();
        JsonNode none = getOk(detailUrl(emd), null, null).path("vrblAjmt");
        assertThat(none.path("ajmtYn").asText()).isEqualTo("N");
        assertThat(none.path("vrbls").size()).isEqualTo(FEE_VRBL_CNT);

        BigDecimal cnt = data.appliedVl(RECOMMEND, "FEE_CVLCPT_CNT_WGVL").setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal rt = data.appliedVl(RECOMMEND, "FEE_CVLCPT_RT_WGVL").setScale(2, RoundingMode.UNNECESSARY);
        JsonNode same = getOk(detailUrl(emd), null, "{\"FEE_CVLCPT_CNT_WGVL\":" + cnt.toPlainString()
                + ",\"FEE_CVLCPT_RT_WGVL\":" + rt.toPlainString() + "}").path("vrblAjmt");
        assertThat(same.path("ajmtYn").asText()).as("값 비교(D11)").isEqualTo("N");
    }

    @Test
    @DisplayName("요금제 보고서 — 저장값 = 같은 입력의 조회값 · vrblAjmt 는 머리에만(본문 null) · POST = 스냅샷 = anls_param_json")
    void feeReportKeepsVrblAjmt() throws Exception {
        String emd = feeEmd();
        BigDecimal changed = rng.compareTo(bound("ALTMNT_RNG_VL", "max_vl")) < 0
                ? bound("ALTMNT_RNG_VL", "max_vl") : bound("ALTMNT_RNG_VL", "min_vl");
        Map<String, BigDecimal> vrblVls = Map.of("ALTMNT_RNG_VL", changed);

        ZoneRcmdtnReportVO created = reportService.createFeeReport(feeRequest(emd, vrblVls), TESTER, TESTER_NM);
        ZoneRcmdtnResultVO query = zoneRcmdtnService.selectZoneRcmdtn(emd, null, cv.bgngYmd(), cv.endYmd(),
                crdn.bgngYmd(), crdn.endYmd(), null, vrblVls);

        assertThat(created.getFee().getCrtrVls().getAltmntRngVl()).isEqualByComparingTo(changed);
        assertThat(ids(created.getFee())).as("보고서 순위 = 같은 입력의 조회 순위").isEqualTo(ids(query));
        assertThat(created.getFee().getVrblAjmt()).as("본문은 비운다(스냅샷과 같게)").isNull();
        assertThat(created.getVrblAjmt()).isNotNull();
        assertThat(created.getVrblAjmt().get("ajmtYn")).isEqualTo("Y");

        ZoneRcmdtnReportVO snapshot = reportService.selectReportSnapshot(created.getRptpId());
        assertThat(snapshot.getFee().getVrblAjmt()).isNull();
        assertThat(tree(snapshot.getVrblAjmt())).as("스냅샷 = POST").isEqualTo(tree(created.getVrblAjmt()));
        assertThat(paramJson(created.getExcnId()).path("vrblAjmt")).as("anls_param_json = POST")
                .isEqualTo(tree(created.getVrblAjmt()));
    }

    // ───────────────────────── 공유주차제 ─────────────────────────

    @Test
    @DisplayName("공유주차제 — CVLCPT_RT_CRTR_VL 입력이 rvwScr(독립 재계산)·후보에 반영되고 적용 내역·실행 행에 남는다")
    void shareRtCrtrChangesScoreAndCandidates() throws Exception {
        BigDecimal crtr = data.appliedVl(RECOMMEND, "CVLCPT_RT_CRTR_VL");
        BigDecimal max = bound("CVLCPT_RT_CRTR_VL", "max_vl");
        BigDecimal changed = crtr.compareTo(max) < 0 ? max : bound("CVLCPT_RT_CRTR_VL", "min_vl");

        JsonNode base = getOk(shareUrl(), null, null);
        JsonNode body = getOk(shareUrl(), null, "{\"CVLCPT_RT_CRTR_VL\":" + changed.toPlainString() + "}");

        BigDecimal mmCrtr = body.path("crtrVls").path("cvlcptMmCrtrVl").decimalValue();
        assertThat(mmCrtr).isEqualByComparingTo(data.appliedVl(RECOMMEND, "CVLCPT_MM_CRTR_VL"));
        assertThat(body.path("crtrVls").path("cvlcptRtCrtrVl").decimalValue()).isEqualByComparingTo(changed);

        BigDecimal tol = new BigDecimal("0.01")
                .add(mmCrtr.signum() > 0 ? new BigDecimal("0.05").divide(mmCrtr, 6, RoundingMode.UP) : BigDecimal.ZERO)
                .max(new BigDecimal("0.01").add(new BigDecimal("0.005").divide(changed, 6, RoundingMode.UP)));
        int scored = 0;
        for (JsonNode z : body.path("zones")) {
            if (z.path("rvwScr").isNull()) {
                continue;
            }
            scored++;
            BigDecimal byRt = z.path("cvlcptRt").decimalValue().divide(changed, 6, RoundingMode.HALF_UP);
            BigDecimal expected = mmCrtr.signum() > 0
                    ? z.path("cvlcptMmAvg").decimalValue().divide(mmCrtr, 6, RoundingMode.HALF_UP).min(byRt) : byRt;
            assertThat(z.path("rvwScr").decimalValue().subtract(expected).abs())
                    .as("rvwScr ≈ min(월평균 ÷ %s, 비율 ÷ %s): %s", mmCrtr, changed, z).isLessThan(tol);
            assertThat(z.path("rvwScr").decimalValue().compareTo(BigDecimal.ONE) >= 0)
                    .as("rvwScr ≥ 1 ⇔ 후보: %s", z).isEqualTo("Y".equals(z.path("rvwTrgtYn").asText()));
        }
        assertThat(scored).isPositive();
        assertThat(byEmd(body, "rvwScr")).as("기준값이 바뀌면 rvwScr 가 바뀐다").isNotEqualTo(byEmd(base, "rvwScr"));
        assertThat(byEmd(body, "rvwTrgtYn")).as("후보가 바뀐다").isNotEqualTo(byEmd(base, "rvwTrgtYn"));

        JsonNode ajmt = body.path("vrblAjmt");
        assertThat(ajmt.path("ajmtYn").asText()).isEqualTo("Y");
        assertThat(ajmt.path("vrbls").size()).isEqualTo(SHARE_VRBL_CNT);
        assertThat(vrbl(ajmt, "CVLCPT_RT_CRTR_VL").path("stngVl").decimalValue()).isEqualByComparingTo(crtr);
        assertThat(base.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("N");
        JsonNode param = paramJson(body.path("excnId").asLong());
        assertThat(param.path("vrblAjmt")).isEqualTo(ajmt);
        assertThat(param.path("cvlcptRtCrtrVl").decimalValue()).isEqualByComparingTo(changed);
    }

    @Test
    @DisplayName("공유주차제 — 요금제 변수·범위 밖은 400 이고 실행 행을 남기지 않는다")
    void shareRejectsBadInput() {
        long before = countExcn();
        for (String bad : List.of("{\"ALTMNT_RNG_VL\":500}", "{\"FEE_CVLCPT_CNT_WGVL\":70}",
                "{\"CVLCPT_RT_CRTR_VL\":" + bound("CVLCPT_RT_CRTR_VL", "max_vl").add(BigDecimal.ONE).toPlainString() + "}")) {
            assertThat(call(shareUrl(), null, bad).getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(countExcn()).isEqualTo(before);
    }

    @Test
    @DisplayName("공유주차제 보고서 — 저장값 = 같은 입력의 조회값 · vrblAjmt 는 머리에만 · POST = 스냅샷 = anls_param_json")
    void shareReportKeepsVrblAjmt() throws Exception {
        BigDecimal crtr = data.appliedVl(RECOMMEND, "CVLCPT_RT_CRTR_VL");
        BigDecimal min = bound("CVLCPT_RT_CRTR_VL", "min_vl");
        BigDecimal changed = crtr.compareTo(min) > 0 ? min : min.add(BigDecimal.ONE);
        Map<String, BigDecimal> vrblVls = Map.of("CVLCPT_RT_CRTR_VL", changed);
        ZoneRcmdtnShareResultVO query = zoneRcmdtnShareService.selectShare(cv.bgngYmd(), cv.endYmd(), null, null, vrblVls);
        assumeTrue(query.getZones().stream().anyMatch(z -> "Y".equals(z.getRvwTrgtYn())), "검토 후보가 0곳이다");

        ZoneRcmdtnShareReportCreateRequest req = new ZoneRcmdtnShareReportCreateRequest();
        req.setBgngYmd(cv.bgngYmd());
        req.setEndYmd(cv.endYmd());
        req.setFileFmt("PDF");
        req.setVrblVls(vrblVls);
        ZoneRcmdtnReportVO created = reportService.createShareReport(req, TESTER, TESTER_NM);

        assertThat(created.getShare().getCrtrVls().getCvlcptRtCrtrVl()).isEqualByComparingTo(changed);
        assertThat(created.getShare().getSmry().getRvwTrgtCnt()).isEqualTo(query.getSmry().getRvwTrgtCnt());
        assertThat(tree(created.getShare().getZones())).as("보고서 판정 = 같은 입력의 조회").isEqualTo(tree(query.getZones()));
        assertThat(created.getShare().getVrblAjmt()).isNull();
        assertThat(created.getVrblAjmt().get("ajmtYn")).isEqualTo("Y");

        ZoneRcmdtnReportVO snapshot = reportService.selectReportSnapshot(created.getRptpId());
        assertThat(snapshot.getShare().getVrblAjmt()).isNull();
        assertThat(tree(snapshot.getVrblAjmt())).isEqualTo(tree(created.getVrblAjmt()));
        assertThat(paramJson(created.getExcnId()).path("vrblAjmt")).isEqualTo(tree(created.getVrblAjmt()));
    }

    // ───────────────────────── 헬퍼 ─────────────────────────

    private String feeEmd() {
        String emd = data.busyEmd(cv, minCpcty, false, 2);
        assumeTrue(emd != null, "NARROW 후보 좌표가 2곳 이상이면서 민원이 있는 읍면동이 없다");
        return emd;
    }

    private String prd() {
        return "bgngYmd=" + cv.bgngYmd() + "&endYmd=" + cv.endYmd()
                + "&crdnBgngYmd=" + crdn.bgngYmd() + "&crdnEndYmd=" + crdn.endYmd();
    }

    private String detailUrl(String emdCd) {
        return DETAIL_URL + "?emdCd=" + emdCd + "&" + prd();
    }

    private String shareUrl() {
        return SHARE_URL + "?bgngYmd=" + cv.bgngYmd() + "&endYmd=" + cv.endYmd();
    }

    private ZoneRcmdtnReportCreateRequest feeRequest(String emdCd, Map<String, BigDecimal> vrblVls) {
        ZoneRcmdtnReportCreateRequest req = new ZoneRcmdtnReportCreateRequest();
        req.setEmdCd(emdCd);
        req.setBgngYmd(cv.bgngYmd());
        req.setEndYmd(cv.endYmd());
        req.setCrdnBgngYmd(crdn.bgngYmd());
        req.setCrdnEndYmd(crdn.endYmd());
        req.setFileFmt("PDF");
        req.setVrblVls(vrblVls);
        return req;
    }

    /** 🔴 한글 sggNm·JSON 은 URI 변수로 넘긴다 — 템플릿 확장이 퍼센트 인코딩한다 */
    private ResponseEntity<String> call(String url, String sggNm, String vrblJson) {
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
        if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
            try {
                JsonNode excnId = objectMapper.readTree(res.getBody()).path("excnId");
                if (excnId.isIntegralNumber()) {
                    createdExcnIds.add(excnId.asLong());
                }
            } catch (JsonProcessingException e) {   // 4.3(TSK-4336) — readTree 가 던지는 파싱 예외만
                // 목록 응답(/zones)은 배열이라 excnId 가 없다
                org.slf4j.LoggerFactory.getLogger(ZoneRcmdtnVrblAjmtIntegrationTest.class).debug("응답 JSON 파싱 생략 — {}", e.getClass().getSimpleName());   // 4.2
            }
        }
        return res;
    }

    private JsonNode getOk(String url, String sggNm, String vrblJson) {
        ResponseEntity<String> res = call(url, sggNm, vrblJson);
        assertThat(res.getStatusCode()).as("GET %s vrblVls=%s → %s", url, vrblJson, res.getBody()).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readTree(res.getBody());
        } catch (JsonProcessingException e) {   // 4.3(TSK-4336) — readTree 가 던지는 파싱 예외만
            throw new IllegalStateException("응답 JSON 파싱 실패: " + url, e);
        }
    }

    private static JsonNode vrbl(JsonNode ajmt, String vrblCd) {
        for (JsonNode v : ajmt.path("vrbls")) {
            if (vrblCd.equals(v.path("vrblCd").asText())) {
                return v;
            }
        }
        throw new AssertionError("vrblAjmt 에 " + vrblCd + " 가 없다: " + ajmt);
    }

    private static void assertRankOrder(JsonNode lots) {
        BigDecimal prev = null;
        for (int i = 0; i < lots.size(); i++) {
            JsonNode l = lots.get(i);
            assertThat(l.path("rnk").asInt()).isEqualTo(i + 1);
            BigDecimal cur = l.path("incrsRfltRvwScr").decimalValue();
            if (prev != null) {
                assertThat(cur).as("종합지수 비증가: %s", l).isLessThanOrEqualTo(prev);
            }
            prev = cur;
        }
    }

    private static List<Long> lotIds(JsonNode body) {
        List<Long> out = new ArrayList<>();
        for (JsonNode l : body.path("lots")) {
            out.add(l.path("pkltId").asLong());
        }
        return out;
    }

    private static Map<Long, BigDecimal> lotScores(JsonNode body) {
        return byLot(body, "rvwScr");
    }

    private static Map<Long, BigDecimal> byLot(JsonNode body, String field) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (JsonNode l : body.path("lots")) {
            out.put(l.path("pkltId").asLong(), l.path(field).decimalValue());
        }
        return out;
    }

    private static Map<String, String> byEmd(JsonNode body, String field) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode z : body.path("zones")) {
            out.put(z.path("emdCd").asText(), z.path(field).asText());
        }
        return out;
    }

    private static List<Long> ids(ZoneRcmdtnResultVO result) {
        return result.getLots().stream().map(l -> l.getPkltId()).toList();
    }

    /** 같은 매퍼로 직렬화 → 다시 읽어 숫자 노드 종류를 맞춘다(키 순서는 트리 비교라 상관없다) */
    private JsonNode tree(Object value) throws Exception {
        return objectMapper.readTree(springMapper.writeValueAsString(value));
    }

    private JsonNode paramJson(long excnId) throws Exception {
        return objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?", String.class, excnId));
    }

    private BigDecimal bound(String vrblCd, String column) {
        // column 은 이 클래스가 넘기는 상수(min_vl | max_vl)뿐이다
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = ? AND vrbl_cd = ?", BigDecimal.class, RECOMMEND, vrblCd).stripTrailingZeros();
    }

    private long countExcn() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_anls_excn WHERE excutr_id = ?",
                Long.class, IntegrationLoginSupport.MOCK_ID);
    }

    private long countReportsByTester() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_rptp WHERE rptp_id > ? AND creatr_id = ?",
                Long.class, baseRptpId, TESTER);
    }

    private long maxId(String table, String idCol) {
        return jdbcTemplate.queryForObject("SELECT coalesce(max(" + idCol + "), 0) FROM " + table, Long.class);
    }

    private List<Map<String, Object>> readSettings() {
        return jdbcTemplate.queryForList("SELECT anls_type_cd, vrbl_cd, stng_vl, use_yn, mdfcn_dt"
                + " FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd");
    }

    private void assertSettingsUntouched() {
        assertThat(readSettings()).as("🔴 일시 조정은 설정 표를 바꾸지 않는다").isEqualTo(stngBefore);
    }

    private void run(List<RuntimeException> failures, String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
