package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.ZoneRcmdtnTestData.Win;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 분석 계수 일시 조정 — 보고서 재다운로드 재현 인수 테스트 (TSK-4325 Step 6 · 요구 5).
 *
 * <p>6개 보고서(집중구역 · 투자우선순위 · 구역추천 요금제 · 공유주차제 · 생활권 수급 · 혼잡도)에 같은 시나리오를 돌린다.
 * <ol>
 *   <li>조정값({@code vrblVls})으로 보고서 생성 → 스냅샷 A. {@code vrblAjmt} 가 {@code ajmtYn='Y'} · 허용 목록 전체 ·
 *       조정 안 한 행은 {@code stngVl = aplcnVl} · POST 응답 = 스냅샷 A</li>
 *   <li>같은 입력으로 조회 → 저장 결과와 순위·점수 일치 (🔴 설정을 바꾸기 <b>전에</b> 본다 — 알려진 한계)</li>
 *   <li>{@code PUT /api/analysis-weights/{type}} 로 설정값 변경 — 바꾸는 변수는 보고서 입력에 든 변수다</li>
 *   <li>스냅샷 B → A 와 전체 동일</li>
 *   <li>{@code vrblVls} 없는 조회 → 변경된 설정값으로 계산(요구 2)</li>
 * </ol>
 *
 * <h2>🔴 정리 — 테스트 전 값으로 되돌린다</h2>
 * {@code RANDOM_PORT} 라 롤백이 안 먹는다. {@link #cleanUp()} 이 ① 응답에서 모은 보고서·실행·결과 행만 지우고
 * ② 설정 표 {@code stng_vl}·{@code mdfr_id}·{@code mdfcn_dt} 를 {@link #setUp()} 에서 읽은 값으로 되돌리며
 * ③ 변경 이력은 {@code chg_hstry_sn} 시작값 초과분만 지우고 ④ 로그인 흔적을 지운다. 한 문장이 터져도 끝까지 돈다.
 * 🔴 {@code AnalysisWeightIntegrationTest.resetVariableSeed} 처럼 기본값으로 덮거나 이력을 전량 지우지 않는다.
 *
 * <p>기대값은 상수가 아니라 DB 현재 설정값에서 유도하고, 조회 기간은 DB 보유 구간에서 유도한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 집중구역 구역명 — VWorld HTTP 호출 경로를 차단한다(HotspotReportIntegrationTest 와 같은 설정 → 컨텍스트 공유)
        properties = "analysis.zone-name.vworld.api-key=")
@DisplayName("TSK-4325 Step 6 — 분석 계수 조정 보고서는 설정을 바꿔도 저장 당시 값으로 재다운로드된다")
class AnalysisCoefficientReportIntegrationTest {

    private static final String WEIGHTS_URL = "/api/analysis-weights";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_RANGE_DAYS = 366;
    private static final int GRID_SZ = 500;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 소수를 double 로 뭉개지 않는다 */
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    /** 테스트 전 설정 표(전 행) — 복원 원천 */
    private List<Map<String, Object>> stngBefore;
    private long hstrySnStart;
    private long baseExcnId;
    private long baseRptpId;

    private final Set<Long> createdRptpIds = new LinkedHashSet<>();
    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    @BeforeEach
    void setUp() {
        stngBefore = readSettings();
        hstrySnStart = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(chg_hstry_sn), 0) FROM parking.tb_anls_vrbl_hstry", Long.class);
        baseExcnId = maxId("parking.tb_anls_excn", "excn_id");
        baseRptpId = maxId("parking.tb_rptp", "rptp_id");

        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSessionWithMenus("M0603");   // PUT /api/analysis-weights 는 M0603 이 필요하다
    }

    @AfterEach
    void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();
        // ① 보고서 3행 — FK 방향(결과 → 보고서 → 실행)으로, 모아 둔 id 만
        for (Long rptpId : createdRptpIds) {
            Long excnId = quietly(failures, () -> jdbcTemplate.queryForList(
                    "SELECT excn_id FROM parking.tb_rptp WHERE rptp_id = ?", Long.class, rptpId)
                    .stream().findFirst().orElse(null));
            if (excnId != null) {
                createdExcnIds.add(excnId);
            }
        }
        for (Long excnId : createdExcnIds) {
            run(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long rptpId : createdRptpIds) {
            run(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        }
        for (Long excnId : createdExcnIds) {
            run(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdRptpIds.clear();
        createdExcnIds.clear();

        // ② 설정 — 테스트 전 값으로(바뀐 행만)
        List<Map<String, Object>> now = quietly(failures, this::readSettings);
        for (Map<String, Object> row : stngBefore) {
            if (now != null && now.contains(row)) {
                continue;
            }
            run(failures, "UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?, mdfr_id = ?, mdfcn_dt = ?"
                            + " WHERE anls_type_cd = ? AND vrbl_cd = ?",
                    row.get("stng_vl"), row.get("mdfr_id"), row.get("mdfcn_dt"),
                    row.get("anls_type_cd"), row.get("vrbl_cd"));
        }

        // ③ 이력 — 이 테스트가 만든 것(시작값 초과)만. 🔴 기존 행은 지우지 않는다
        run(failures, "DELETE FROM parking.tb_anls_vrbl_hstry WHERE chg_hstry_sn > ?", hstrySnStart);

        // ④ 로그인 흔적
        try {
            auth.cleanUp();
        } catch (RuntimeException e) {
            failures.add(e);
        }

        assertThat(failures).as("정리·복원에 실패했다 — 손으로 되돌려야 한다").isEmpty();
        assertThat(readSettings()).as("설정 표가 테스트 전 값으로 돌아왔다").isEqualTo(stngBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_vrbl_hstry WHERE chg_hstry_sn > ?", Long.class, hstrySnStart))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_anls_excn"
                        + " WHERE excn_id > ? AND excutr_id = ?", Long.class, baseExcnId, IntegrationLoginSupport.MOCK_ID))
                .as("이 테스트가 남긴 실행 행이 없다").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_rptp"
                        + " WHERE rptp_id > ? AND creatr_id = ?", Long.class, baseRptpId, IntegrationLoginSupport.MOCK_ID))
                .as("이 테스트가 남긴 보고서가 없다").isZero();
    }

    // ───────────────────────── 1. 집중구역 ─────────────────────────

    @Test
    @DisplayName("집중구역 — GRD_BNDRY 조정 보고서는 설정 변경 뒤에도 그대로이고, 입력 없는 조회는 새 설정값을 쓴다")
    void hotspotReport() {
        String[] range = jdbcTemplate.queryForObject(
                "SELECT to_char(LEAST((SELECT min(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                        + "              (SELECT min(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD'),"
                        + "       to_char(GREATEST((SELECT max(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                        + "              (SELECT max(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD')",
                (rs, n) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range[0]).as("민원·단속 원천이 비어 있다").matches("\\d{8}");
        String bgng = clamp(range[0], range[1]);
        String end = range[1];

        BigDecimal b1 = stngVl("HOTSPOT", "GRD_BNDRY1");
        BigDecimal b2 = stngVl("HOTSPOT", "GRD_BNDRY2");
        BigDecimal b3 = stngVl("HOTSPOT", "GRD_BNDRY3");
        BigDecimal lo = b2.add(BigDecimal.ONE);
        BigDecimal hi = maxVl("HOTSPOT", "GRD_BNDRY3");
        BigDecimal adj = pick(b3, lo, hi, BigDecimal.valueOf(5));
        BigDecimal changed = pick(b3, lo, hi, BigDecimal.ONE, adj);
        // D21 — 경계 묶음은 한 행이라도 다르면 묶음 전체를 보낸다
        Map<String, BigDecimal> vrblVls = vrbls("GRD_BNDRY1", b1, "GRD_BNDRY2", b2, "GRD_BNDRY3", adj);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", bgng);
        body.put("endYmd", end);
        body.put("fileFmt", "PDF");

        String queryUrl = "/api/analysis/hotspot?bgngYmd=" + bgng + "&endYmd=" + end;
        runScenario(new Scenario(
                "HOTSPOT", AnlsVrblScope.HOTSPOT, "/api/analysis/hotspot/reports", "/api/reports/{rptpId}/hotspot",
                body, vrblVls, queryUrl, "GRD_BNDRY3", changed,
                (created, query) -> {
                    assertThat(zoneRanking(created.path("zones"))).as("보고서 순위·점수·등급 = 같은 입력의 조회")
                            .isNotEmpty().isEqualTo(zoneRanking(query.path("zones")));
                    assertThat(created.path("gradeCriteria")).isEqualTo(query.path("gradeCriteria"));
                    assertThat(maxRankPcts(created)).containsExactly(b1.intValueExact(), b2.intValueExact(),
                            adj.intValueExact());
                },
                plain -> assertThat(maxRankPcts(plain)).as("입력 없는 조회는 새 설정값")
                        .containsExactly(b1.intValueExact(), b2.intValueExact(), changed.intValueExact())));
    }

    // ───────────────────────── 2. 투자우선순위 ─────────────────────────

    @Test
    @DisplayName("투자우선순위 — 가중치·계수 조정 보고서는 설정 변경 뒤에도 그대로이고, 입력 없는 조회는 새 설정값을 쓴다")
    void investReport() {
        InvestPrrtTestData data = new InvestPrrtTestData(jdbcTemplate);
        InvestPrrtTestData.Win win = data.recentWin();
        assumeTrue(!data.targetIds().isEmpty(), "노상·공한지가 아닌 미소멸 주차장이 없다");
        assumeTrue(win != null, "좌표 있는 주차 민원이 없다");
        String bgng = win.bgng().format(YMD);
        String end = win.end().format(YMD);

        Map<String, BigDecimal> vrblVls = data.adjustedVrblVls(BigDecimal.TEN);
        assumeTrue(vrblVls != null, "±10 을 줄 수 있는 INVEST 가중치 두 개가 없다");
        vrblVls.replaceAll((k, v) -> norm(v));
        BigDecimal cur = stngVl("INVEST", "ALTMNT_RNG_VL");
        BigDecimal changed = pick(cur, minVl("INVEST", "ALTMNT_RNG_VL"), maxVl("INVEST", "ALTMNT_RNG_VL"),
                BigDecimal.valueOf(100), vrblVls.get("ALTMNT_RNG_VL"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", bgng);
        body.put("endYmd", end);
        body.put("fileFmt", "PDF");

        String queryUrl = "/api/analysis/investment-priority?bgngYmd=" + bgng + "&endYmd=" + end;
        runScenario(new Scenario(
                "INVEST", AnlsVrblScope.INVEST, "/api/analysis/investment-priority/reports",
                "/api/reports/{rptpId}/investment-priority",
                body, vrblVls, queryUrl, "ALTMNT_RNG_VL", changed,
                (created, query) -> {
                    assertThat(created.path("lots")).as("lots").isNotEmpty();
                    for (String key : List.of("smry", "weights", "gradeCriteria", "crtrVls", "warnings",
                            "scoreFormula", "lots")) {
                        assertThat(created.path(key)).as("POST %s = 같은 입력의 조회", key).isEqualTo(query.path(key));
                    }
                    assertThat(created.path("crtrVls").path("altmntRngVl").decimalValue())
                            .isEqualByComparingTo(vrblVls.get("ALTMNT_RNG_VL"));
                },
                plain -> assertThat(plain.path("crtrVls").path("altmntRngVl").decimalValue())
                        .as("입력 없는 조회는 새 설정값").isEqualByComparingTo(changed)));
    }

    // ───────────────────────── 3·4. 구역추천 ─────────────────────────

    @Test
    @DisplayName("구역추천 요금제 — ALTMNT_RNG_VL 조정 보고서는 설정 변경 뒤에도 그대로이고, 입력 없는 조회는 새 설정값을 쓴다")
    void zoneFeeReport() {
        ZoneRcmdtnTestData data = new ZoneRcmdtnTestData(jdbcTemplate);
        Win cvRange = data.cvRange();
        Win crdnRange = data.crdnRange();
        assertThat(cvRange).as("주차 민원이 비어 있다").isNotNull();
        assertThat(crdnRange).as("단속이 비어 있다").isNotNull();
        Win cv = ZoneRcmdtnTestData.head(cvRange);
        Win crdn = ZoneRcmdtnTestData.head(crdnRange);
        BigDecimal minCpcty = data.appliedVl("RECOMMEND", "SWTC_TRGT_MIN_CPCTY");
        String emd = data.busyEmd(cv, minCpcty, false, 2);
        assumeTrue(emd != null, "NARROW 후보 좌표가 2곳 이상이면서 민원이 있는 읍면동이 없다");

        BigDecimal cur = stngVl("RECOMMEND", "ALTMNT_RNG_VL");
        BigDecimal lo = minVl("RECOMMEND", "ALTMNT_RNG_VL");
        BigDecimal hi = maxVl("RECOMMEND", "ALTMNT_RNG_VL");
        BigDecimal adj = pick(cur, lo, hi, BigDecimal.valueOf(200));
        BigDecimal changed = pick(cur, lo, hi, BigDecimal.valueOf(100), adj);
        Map<String, BigDecimal> vrblVls = vrbls("ALTMNT_RNG_VL", adj);

        String prd = "bgngYmd=" + cv.bgngYmd() + "&endYmd=" + cv.endYmd()
                + "&crdnBgngYmd=" + crdn.bgngYmd() + "&crdnEndYmd=" + crdn.endYmd();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("emdCd", emd);
        body.put("bgngYmd", cv.bgngYmd());
        body.put("endYmd", cv.endYmd());
        body.put("crdnBgngYmd", crdn.bgngYmd());
        body.put("crdnEndYmd", crdn.endYmd());
        body.put("fileFmt", "PDF");

        runScenario(new Scenario(
                "RECOMMEND", AnlsVrblScope.RECOMMEND_FEE, "/api/analysis/zone-recommendation/reports",
                "/api/reports/{rptpId}/zone-recommendation",
                body, vrblVls, "/api/analysis/zone-recommendation?emdCd=" + emd + "&" + prd, "ALTMNT_RNG_VL", changed,
                (created, query) -> {
                    JsonNode fee = created.path("fee");
                    assertThat(fee.path("lots")).as("lots").isNotEmpty();
                    for (String key : List.of("lots", "crtrVls", "zone", "asgn")) {
                        assertThat(fee.path(key)).as("보고서 fee.%s = 같은 입력의 조회", key).isEqualTo(query.path(key));
                    }
                    assertThat(fee.path("crtrVls").path("altmntRngVl").decimalValue()).isEqualByComparingTo(adj);
                },
                plain -> assertThat(plain.path("crtrVls").path("altmntRngVl").decimalValue())
                        .as("입력 없는 조회는 새 설정값").isEqualByComparingTo(changed)));
    }

    @Test
    @DisplayName("구역추천 공유주차제 — CVLCPT_RT_CRTR_VL 조정 보고서는 설정 변경 뒤에도 그대로이고, 입력 없는 조회는 새 설정값을 쓴다")
    void zoneShareReport() {
        ZoneRcmdtnTestData data = new ZoneRcmdtnTestData(jdbcTemplate);
        Win cvRange = data.cvRange();
        assertThat(cvRange).as("주차 민원이 비어 있다").isNotNull();
        Win cv = ZoneRcmdtnTestData.head(cvRange);

        BigDecimal cur = stngVl("RECOMMEND", "CVLCPT_RT_CRTR_VL");
        BigDecimal lo = minVl("RECOMMEND", "CVLCPT_RT_CRTR_VL");
        BigDecimal hi = maxVl("RECOMMEND", "CVLCPT_RT_CRTR_VL");
        BigDecimal adj = pick(cur, lo, hi, BigDecimal.valueOf(-2));   // 내려서 후보가 생기기 쉽게
        BigDecimal changed = pick(cur, lo, hi, BigDecimal.ONE, adj);
        Map<String, BigDecimal> vrblVls = vrbls("CVLCPT_RT_CRTR_VL", adj);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", cv.bgngYmd());
        body.put("endYmd", cv.endYmd());
        body.put("fileFmt", "PDF");

        runScenario(new Scenario(
                "RECOMMEND", AnlsVrblScope.RECOMMEND_SHARE, "/api/analysis/zone-recommendation/share/reports",
                "/api/reports/{rptpId}/zone-recommendation",
                body, vrblVls,
                "/api/analysis/zone-recommendation/share?bgngYmd=" + cv.bgngYmd() + "&endYmd=" + cv.endYmd(),
                "CVLCPT_RT_CRTR_VL", changed,
                (created, query) -> {
                    JsonNode share = created.path("share");
                    assertThat(share.path("zones")).as("zones").isNotEmpty();
                    for (String key : List.of("zones", "smry", "crtrVls")) {
                        assertThat(share.path(key)).as("보고서 share.%s = 같은 입력의 조회", key)
                                .isEqualTo(query.path(key));
                    }
                    assertThat(share.path("crtrVls").path("cvlcptRtCrtrVl").decimalValue()).isEqualByComparingTo(adj);
                },
                plain -> assertThat(plain.path("crtrVls").path("cvlcptRtCrtrVl").decimalValue())
                        .as("입력 없는 조회는 새 설정값").isEqualByComparingTo(changed)));
    }

    // ───────────────────────── 5·6. 생활권 ─────────────────────────

    @Test
    @DisplayName("생활권 수급 — 최소 월평균 민원 조정 보고서는 설정 변경 뒤에도 그대로이고, 입력 없는 조회는 새 설정값을 쓴다")
    void lifezoneSupplyReport() {
        String[] range = jdbcTemplate.queryForObject(
                "SELECT to_char(min(ocrn_dt), 'YYYYMMDD'), to_char(max(ocrn_dt), 'YYYYMMDD') FROM parking.tb_cvlcpt"
                        + " WHERE lat BETWEEN 33.10 AND 33.65 AND lot BETWEEN 126.10 AND 127.00"
                        + " AND cvlcpt_type_cd IN ('01','02','03','04','05')",
                (rs, n) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range[0]).as("좌표가 유효한 민원이 없다").matches("\\d{8}");
        String bgng = clamp(range[0], range[1]);
        String end = range[1];

        BigDecimal cur = stngVl("LIFEZONE", "SPLY_MIN_MNTH_AVG_CVLCPT");
        BigDecimal lo = minVl("LIFEZONE", "SPLY_MIN_MNTH_AVG_CVLCPT");
        BigDecimal hi = maxVl("LIFEZONE", "SPLY_MIN_MNTH_AVG_CVLCPT");
        BigDecimal adj = pick(cur, lo, hi, BigDecimal.ONE);
        BigDecimal changed = pick(cur, lo, hi, BigDecimal.ONE, adj);
        Map<String, BigDecimal> vrblVls = vrbls("SPLY_MIN_MNTH_AVG_CVLCPT", adj);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", bgng);
        body.put("endYmd", end);
        body.put("gridSz", GRID_SZ);
        body.put("fileFmt", "PDF");

        runScenario(new Scenario(
                "LIFEZONE", AnlsVrblScope.LIFEZONE_SPLY, "/api/analysis/lifezone/supply/reports",
                "/api/reports/{rptpId}/lifezone/supply",
                body, vrblVls,
                "/api/analysis/lifezone/supply?bgngYmd=" + bgng + "&endYmd=" + end + "&gridSz=" + GRID_SZ,
                "SPLY_MIN_MNTH_AVG_CVLCPT", changed,
                (created, query) -> {
                    assertThat(created.path("cells")).as("cells").isNotEmpty();
                    for (String key : List.of("totGridCnt", "severeCnt", "warnCnt", "cautionCnt", "normalCnt",
                            "lowCnt", "zeroSplyGridCnt", "minMnthAvgCvlcpt", "gradeCriteria", "cells")) {
                        assertThat(created.path(key)).as("POST %s = 같은 입력의 조회", key).isEqualTo(query.path(key));
                    }
                    assertThat(created.path("minMnthAvgCvlcpt").asInt()).isEqualTo(adj.intValueExact());
                },
                plain -> assertThat(plain.path("minMnthAvgCvlcpt").asInt()).as("입력 없는 조회는 새 설정값")
                        .isEqualTo(changed.intValueExact())));
    }

    @Test
    @DisplayName("생활권 혼잡도 — 좌표 결측 보정 조정 보고서는 설정 변경 뒤에도 그대로이고, 입력 없는 조회는 새 설정값을 쓴다")
    void lifezoneCngstReport() {
        JsonNode probe = getOk("/api/analysis/lifezone/congestion?bgngYmd=20000101&endYmd=20000102", null);
        assertThat(probe.path("dataEndYmd").asText()).as("단속 원천 보유 구간").matches("\\d{8}");
        String bgng = clamp(probe.path("dataBgngYmd").asText(), probe.path("dataEndYmd").asText());
        String end = probe.path("dataEndYmd").asText();

        // 🔁 TSK-4346 D10 — 종전 PCSHR_CRTR_VL 은 숨김이라 일시 조정 400 · 설정 PUT 무시(4-0) → 노출 계수 GEO_CVRG_CRCT_VL 로 바꿨다
        BigDecimal cur = stngVl("LIFEZONE", "GEO_CVRG_CRCT_VL");
        BigDecimal lo = minVl("LIFEZONE", "GEO_CVRG_CRCT_VL");
        BigDecimal hi = maxVl("LIFEZONE", "GEO_CVRG_CRCT_VL");
        BigDecimal adj = pick(cur, lo, hi, new BigDecimal("0.5"));
        BigDecimal changed = pick(cur, lo, hi, new BigDecimal("0.25"), adj);
        Map<String, BigDecimal> vrblVls = vrbls("GEO_CVRG_CRCT_VL", adj);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", bgng);
        body.put("endYmd", end);
        body.put("gridSz", GRID_SZ);
        body.put("fileFmt", "PDF");

        runScenario(new Scenario(
                "LIFEZONE", AnlsVrblScope.LIFEZONE_CNGST, "/api/analysis/lifezone/congestion/reports",
                "/api/reports/{rptpId}/lifezone/congestion",
                body, vrblVls,
                "/api/analysis/lifezone/congestion?bgngYmd=" + bgng + "&endYmd=" + end + "&gridSz=" + GRID_SZ,
                "GEO_CVRG_CRCT_VL", changed,
                (created, query) -> {
                    assertThat(created.path("cells")).as("cells").isNotEmpty();
                    for (String key : List.of("totGridCnt", "noEnfGridCnt", "refGridCnt", "over1GridCnt",
                            "occUsedVl", "severeCnt", "warnCnt", "cautionCnt", "normalCnt", "lowCnt",
                            "gradeCriteria", "ranking", "cells")) {
                        assertThat(created.path(key)).as("POST %s = 같은 입력의 조회", key).isEqualTo(query.path(key));
                    }
                    assertThat(vrbl(created.path("vrblAjmt"), "GEO_CVRG_CRCT_VL").path("aplcnVl").decimalValue())
                            .isEqualByComparingTo(adj);
                    assertThat(created.path("occUsedVl").decimalValue()).as("occ 는 조정하지 않았다 — 설정값")
                            .isEqualByComparingTo(stngVl("LIFEZONE", "PCSHR_CRTR_VL"));
                },
                plain -> assertThat(vrbl(plain.path("vrblAjmt"), "GEO_CVRG_CRCT_VL").path("aplcnVl").decimalValue())
                        .as("입력 없는 조회는 새 설정값").isEqualByComparingTo(changed)));
    }

    // ───────────────────────── 공통 시나리오 ─────────────────────────

    private record Scenario(
            String anlsTypeCd, AnlsVrblScope scope, String createUrl, String snapshotUrl,
            Map<String, Object> body, Map<String, BigDecimal> vrblVls, String queryUrl,
            String settingCd, BigDecimal settingNewVl,
            Check2 sameInputCheck, Check1 plainCheck) {
    }

    @FunctionalInterface
    private interface Check2 {
        void check(JsonNode created, JsonNode query);
    }

    @FunctionalInterface
    private interface Check1 {
        void check(JsonNode plain);
    }

    private void runScenario(Scenario s) {
        assertThat(s.vrblVls()).as("전제 — 설정을 바꿀 변수가 보고서 입력에 들어 있다").containsKey(s.settingCd());
        assertThat(s.settingNewVl()).as("전제 — 새 설정값은 현재값·입력값과 다르다")
                .isNotEqualByComparingTo(stngVl(s.anlsTypeCd(), s.settingCd()))
                .isNotEqualByComparingTo(s.vrblVls().get(s.settingCd()));
        String vrblJson = json(s.vrblVls());

        // ── 1. 조정값으로 보고서 생성 → 스냅샷 A
        Map<String, Object> body = new LinkedHashMap<>(s.body());
        body.put("vrblVls", s.vrblVls());
        JsonNode created = createOk(s.createUrl(), body);
        long rptpId = created.path("rptpId").asLong();
        JsonNode snapA = getSnapshot(s.snapshotUrl(), rptpId);

        JsonNode ajmt = created.path("vrblAjmt");
        assertVrblAjmt(ajmt, s.scope(), s.vrblVls(), "Y");
        assertThat(snapA.path("vrblAjmt")).as("POST 응답의 vrblAjmt = 스냅샷 A").isEqualTo(ajmt);
        assertThat(paramJson(created.path("excnId").asLong()).path("vrblAjmt"))
                .as("anls_param_json.vrblAjmt = POST").isEqualTo(ajmt);

        // ── 2. 같은 입력으로 조회 → 저장 결과와 일치 (🔴 설정을 바꾸기 전에)
        JsonNode query = getOk(s.queryUrl(), vrblJson);
        assertThat(query.path("vrblAjmt")).as("같은 입력의 조회 vrblAjmt = 보고서").isEqualTo(ajmt);
        s.sameInputCheck().check(created, query);

        // ── 3. 설정값 변경 (PUT 전체 교체)
        putSetting(s.anlsTypeCd(), s.settingCd(), s.settingNewVl());

        // ── 4. 스냅샷 B = A
        JsonNode snapB = getSnapshot(s.snapshotUrl(), rptpId);
        assertThat(snapB).as("🔴 설정을 바꿔도 재다운로드 스냅샷은 저장 당시 그대로다").isEqualTo(snapA);
        assertThat(vrbl(snapB.path("vrblAjmt"), s.settingCd()).path("stngVl").decimalValue())
                .as("스냅샷의 stngVl 은 저장 당시 설정값").isNotEqualByComparingTo(s.settingNewVl());

        // ── 5. vrblVls 없는 조회 → 변경된 설정값으로 계산
        JsonNode plain = getOk(s.queryUrl(), null);
        JsonNode plainAjmt = plain.path("vrblAjmt");
        assertThat(plainAjmt.path("ajmtYn").asText()).isEqualTo("N");
        JsonNode row = vrbl(plainAjmt, s.settingCd());
        assertThat(row.path("stngVl").decimalValue()).as("새 설정값이 기본값이 된다").isEqualByComparingTo(s.settingNewVl());
        assertThat(row.path("aplcnVl").decimalValue()).isEqualByComparingTo(s.settingNewVl());
        s.plainCheck().check(plain);
    }

    /** D10 — ajmtYn · 허용 목록 전체(설정 행이 있고 use_yn='Y') · 조정 행은 입력값, 나머지는 stngVl = aplcnVl */
    private void assertVrblAjmt(JsonNode ajmt, AnlsVrblScope scope, Map<String, BigDecimal> input, String ajmtYn) {
        assertThat(ajmt.path("ajmtYn").asText()).as("ajmtYn").isEqualTo(ajmtYn);
        List<String> expected = new ArrayList<>();
        for (String cd : scope.getVrblCds()) {
            List<String> use = jdbcTemplate.queryForList("SELECT use_yn FROM parking.tb_anls_vrbl_stng"
                    + " WHERE anls_type_cd = ? AND vrbl_cd = ?", String.class, scope.getAnlsTypeCd(), cd);
            if (!use.isEmpty() && "Y".equals(use.get(0))) {
                expected.add(cd);
            }
        }
        List<String> actual = new ArrayList<>();
        ajmt.path("vrbls").forEach(v -> actual.add(v.path("vrblCd").asText()));
        assertThat(actual).as("vrbls = 허용 목록 전체").containsExactlyInAnyOrderElementsOf(expected);

        for (JsonNode v : ajmt.path("vrbls")) {
            String cd = v.path("vrblCd").asText();
            BigDecimal stng = stngVl(scope.getAnlsTypeCd(), cd);
            assertThat(v.path("stngVl").decimalValue()).as("%s stngVl = DB 설정값", cd).isEqualByComparingTo(stng);
            BigDecimal aplcn = input.getOrDefault(cd, stng);
            assertThat(v.path("aplcnVl").decimalValue()).as("%s aplcnVl", cd).isEqualByComparingTo(aplcn);
        }
    }

    // ───────────────────────── 설정 변경 ─────────────────────────

    /** GET 으로 그 유형 행 전부를 받아 한 값만 바꿔 PUT (전체 교체 계약) */
    private void putSetting(String anlsTypeCd, String vrblCd, BigDecimal newVl) {
        JsonNode types = getOk(WEIGHTS_URL, null);
        JsonNode type = null;
        for (JsonNode t : types) {
            if (anlsTypeCd.equals(t.path("anlsTypeCd").asText())) {
                type = t;
            }
        }
        assertThat(type).as("분석유형 %s", anlsTypeCd).isNotNull();
        List<Map<String, Object>> items = new ArrayList<>();
        boolean replaced = false;
        for (JsonNode w : type.path("weights")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("vrblCd", w.path("vrblCd").asText());
            if (vrblCd.equals(w.path("vrblCd").asText())) {
                item.put("stngVl", newVl);
                replaced = true;
            } else {
                item.put("stngVl", w.path("stngVl").decimalValue());
            }
            items.add(item);
        }
        assertThat(replaced).as("%s 에 %s 행이 있다", anlsTypeCd, vrblCd).isTrue();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("weights", items);
        req.put("chgRsn", "TSK-4325 Step 6 통합 테스트");
        ResponseEntity<String> res = restTemplate.exchange(WEIGHTS_URL + "/" + anlsTypeCd, HttpMethod.PUT,
                auth.jsonEntity(session, req), String.class);
        assertThat(res.getStatusCode()).as("PUT %s → %s", anlsTypeCd, res.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stngVl(anlsTypeCd, vrblCd)).as("설정이 실제로 바뀌었다").isEqualByComparingTo(newVl);
    }

    // ───────────────────────── 호출 ─────────────────────────

    private JsonNode createOk(String url, Map<String, Object> body) {
        ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.POST, auth.jsonEntity(session, body),
                String.class);
        JsonNode node = res.getStatusCode().is2xxSuccessful() ? parse(res.getBody()) : null;
        if (node != null) {
            track(node);   // 🔴 단언보다 먼저 — 실패해도 cleanUp 이 지운다
        }
        assertThat(res.getStatusCode()).as("POST %s → %s", url, res.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(node.path("rptpId").asLong()).isPositive();
        assertThat(node.path("excnId").asLong()).isPositive();
        return node;
    }

    private JsonNode getSnapshot(String url, long rptpId) {
        ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(session),
                String.class, rptpId);
        assertThat(res.getStatusCode()).as("GET %s(%s) → %s", url, rptpId, res.getBody()).isEqualTo(HttpStatus.OK);
        return parse(res.getBody());
    }

    /** 🔴 JSON 은 URI 변수로 넘긴다 — 템플릿 확장이 퍼센트 인코딩한다 */
    private JsonNode getOk(String url, String vrblJson) {
        Map<String, Object> vars = new LinkedHashMap<>();
        String template = url;
        if (vrblJson != null) {
            template += (url.contains("?") ? "&" : "?") + "vrblVls={vrblVls}";
            vars.put("vrblVls", vrblJson);
        }
        ResponseEntity<String> res = restTemplate.exchange(template, HttpMethod.GET, new HttpEntity<>(session),
                String.class, vars);
        JsonNode node = res.getStatusCode().is2xxSuccessful() ? parse(res.getBody()) : null;
        if (node != null && node.isObject()) {
            track(node);   // 구역추천 조회는 호출마다 실행 행을 남긴다
        }
        assertThat(res.getStatusCode()).as("GET %s vrblVls=%s → %s", url, vrblJson, res.getBody())
                .isEqualTo(HttpStatus.OK);
        return node;
    }

    private void track(JsonNode node) {
        if (node.path("rptpId").isIntegralNumber()) {
            createdRptpIds.add(node.path("rptpId").asLong());
        }
        if (node.path("excnId").isIntegralNumber()) {
            createdExcnIds.add(node.path("excnId").asLong());
        }
    }

    // ───────────────────────── 값 ─────────────────────────

    /**
     * {@code cur + step, cur − step, cur + 2·step, …} 중 범위 안이고 {@code cur}·{@code exclude} 와 다른 첫 값.
     * 기대값을 상수가 아니라 현재 설정값에서 유도한다.
     */
    private static BigDecimal pick(BigDecimal cur, BigDecimal lo, BigDecimal hi, BigDecimal step,
                                   BigDecimal... exclude) {
        for (int k = 1; k <= 20; k++) {
            for (int sign : new int[] { 1, -1 }) {
                BigDecimal v = cur.add(step.multiply(BigDecimal.valueOf((long) k * sign)));
                if (v.compareTo(lo) < 0 || v.compareTo(hi) > 0 || v.compareTo(cur) == 0) {
                    continue;
                }
                boolean excluded = false;
                for (BigDecimal e : exclude) {
                    excluded |= e != null && v.compareTo(e) == 0;
                }
                if (!excluded) {
                    return norm(v);
                }
            }
        }
        throw new IllegalStateException("범위 [" + lo + ", " + hi + "] 에서 " + cur + " 와 다른 값을 고르지 못했다");
    }

    /** 정수 전용 변수가 {@code 15.0000} 처럼 가지 않게 표기를 정리한다 */
    private static BigDecimal norm(BigDecimal v) {
        return new BigDecimal(v.stripTrailingZeros().toPlainString());
    }

    private static Map<String, BigDecimal> vrbls(Object... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], norm((BigDecimal) kv[i + 1]));
        }
        return m;
    }

    private static String clamp(String bgngYmd, String endYmd) {
        LocalDate bgng = LocalDate.parse(bgngYmd, YMD);
        LocalDate floor = LocalDate.parse(endYmd, YMD).minusDays(MAX_RANGE_DAYS - 1L);
        return bgng.isBefore(floor) ? floor.format(YMD) : bgngYmd;
    }

    private BigDecimal stngVl(String anlsTypeCd, String vrblCd) {
        return col("stng_vl", anlsTypeCd, vrblCd);
    }

    private BigDecimal minVl(String anlsTypeCd, String vrblCd) {
        return col("min_vl", anlsTypeCd, vrblCd);
    }

    private BigDecimal maxVl(String anlsTypeCd, String vrblCd) {
        return col("max_vl", anlsTypeCd, vrblCd);
    }

    private BigDecimal col(String column, String anlsTypeCd, String vrblCd) {
        // column 은 이 클래스가 넘기는 상수(stng_vl | min_vl | max_vl)뿐이다
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = ? AND vrbl_cd = ?", BigDecimal.class, anlsTypeCd, vrblCd);
    }

    private static JsonNode vrbl(JsonNode ajmt, String vrblCd) {
        for (JsonNode v : ajmt.path("vrbls")) {
            if (vrblCd.equals(v.path("vrblCd").asText())) {
                return v;
            }
        }
        throw new AssertionError("vrblAjmt 에 " + vrblCd + " 가 없다: " + ajmt);
    }

    private static List<Integer> maxRankPcts(JsonNode body) {
        List<Integer> out = new ArrayList<>();
        body.path("gradeCriteria").forEach(c -> out.add(c.path("maxRankPct").asInt()));
        return out;
    }

    /** 집중구역 순위 비교 — 저장·복원되는 칸만, 점수는 표기를 맞춘다(HotspotReportIntegrationTest 선례) */
    private static List<String> zoneRanking(JsonNode zones) {
        List<String> out = new ArrayList<>();
        for (JsonNode z : zones) {
            out.add(z.path("rnk").asText() + "|" + z.path("zoneNm").asText() + "|" + z.path("cvlcptCnt").asText()
                    + "|" + z.path("crdnCnt").asText() + "|"
                    + z.path("snthsScr").decimalValue().stripTrailingZeros().toPlainString()
                    + "|" + z.path("grade").asText());
        }
        return out;
    }

    private JsonNode paramJson(long excnId) {
        return parse(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?", String.class, excnId));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 파싱 실패: " + body, e);
        }
    }

    private List<Map<String, Object>> readSettings() {
        return jdbcTemplate.queryForList("SELECT anls_type_cd, vrbl_cd, stng_vl, mdfr_id, mdfcn_dt"
                + " FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd");
    }

    private long maxId(String table, String idCol) {
        return jdbcTemplate.queryForObject("SELECT coalesce(max(" + idCol + "), 0) FROM " + table, Long.class);
    }

    private void run(List<RuntimeException> failures, String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    private static <T> T quietly(List<RuntimeException> failures, java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            failures.add(e);
            return null;
        }
    }
}
