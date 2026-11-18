package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 생활권 <b>혼잡도 보고서</b> 통합 테스트.
 *
 * <p>🔴 응답을 VO 가 아니라 {@link JsonNode} 로 읽는다 — {@code gradeScale} record 는 {@code @JsonIgnore minVls}
 * 때문에 역직렬화할 수 없다. 🔴 만든 보고서 ID 만 참조 역순으로 지우고, 바꾼 경계는 떠 둔 원래 값으로 되돌린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("생활권 혼잡도 보고서 (POST /api/analysis/lifezone/congestion/reports)")
class LifezoneCngstReportIntegrationTest {

    private static final String CREATE_URL = "/api/analysis/lifezone/congestion/reports";
    private static final String CNGST_SNAPSHOT_URL = "/api/reports/{rptpId}/lifezone/congestion";
    private static final String SUPPLY_SNAPSHOT_URL = "/api/reports/{rptpId}/lifezone/supply";
    private static final String CVLCPT_SNAPSHOT_URL = "/api/reports/{rptpId}/lifezone";
    private static final String ANALYZE_URL = "/api/analysis/lifezone/congestion";
    private static final String SUPPLY_CREATE_URL = "/api/analysis/lifezone/supply/reports";

    /** 🔴 서비스 상수와 같은 값 — 테스트가 독립적으로 다시 적는다. */
    private static final String ANLS_TYPE_LIFEZONE_CNGST = "LIFEZONE_CNGST";
    private static final int GRID_SZ = 500;
    private static final int MAX_RANGE_DAYS = 366;
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String BNDRY_WHERE =
            " WHERE anls_type_cd = 'LIFEZONE' AND vrbl_cd LIKE 'CNGST_GRD_BNDRY%' ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private IntegrationLoginSupport auth;
    private HttpHeaders session;
    private String bgngYmd;
    private String endYmd;

    private final Map<String, BigDecimal> originalBndry = new LinkedHashMap<>();
    private final List<Long> createdRptpIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();

        JsonNode range = get(ANALYZE_URL + "?bgngYmd=20000101&endYmd=20000102");
        assertThat(range.path("dataEndYmd").asText()).as("단속 원천 보유 구간").matches("\\d{8}");
        LocalDate end = LocalDate.parse(range.path("dataEndYmd").asText(), YMD);
        LocalDate dataBgng = LocalDate.parse(range.path("dataBgngYmd").asText(), YMD);
        LocalDate floor = end.minusDays(MAX_RANGE_DAYS - 1L);
        endYmd = end.format(YMD);
        bgngYmd = (dataBgng.isBefore(floor) ? floor : dataBgng).format(YMD);

        originalBndry.clear();
        jdbcTemplate.query("SELECT vrbl_cd, stng_vl FROM parking.tb_anls_vrbl_stng" + BNDRY_WHERE,
                rs -> {
                    originalBndry.put(rs.getString(1), rs.getBigDecimal(2));
                });
        assertThat(originalBndry).as("혼잡도 등급 경계 4행").hasSize(4);
    }

    @AfterEach
    void tearDown() {
        originalBndry.forEach((cd, vl) -> jdbcTemplate.update(
                "UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?" + BNDRY_WHERE + " AND vrbl_cd = ?", vl, cd));
        for (Long rptpId : createdRptpIds) {
            Long excnId = jdbcTemplate.queryForObject(
                    "SELECT excn_id FROM parking.tb_rptp WHERE rptp_id = ?", Long.class, rptpId);
            jdbcTemplate.update("DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
            jdbcTemplate.update("DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
            jdbcTemplate.update("DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdRptpIds.clear();
        auth.cleanUp();
    }

    @Test
    @DisplayName("1. 저장 — 201 · 3표 각 1행 · anls_type_cd 는 LIFEZONE_CNGST")
    void createsThreeRowsWithCngstType() {
        long[] before = tableCounts();
        JsonNode report = createOk(defaultBody("PDF"));

        long[] after = tableCounts();
        assertThat(after[0] - before[0]).as("실행").isEqualTo(1);
        assertThat(after[1] - before[1]).as("결과").isEqualTo(1);
        assertThat(after[2] - before[2]).as("보고서").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT anls_type_cd FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, report.path("excnId").asLong()))
                .as("🔴 LIFEZONE 이면 민원건수 파서가 읽는다").isEqualTo(ANLS_TYPE_LIFEZONE_CNGST);
        assertThat(report.path("fileFmt").asText()).isEqualTo("PDF");
        assertThat(report.path("cells")).isNotEmpty();
    }

    @Test
    @DisplayName("2. 🔴 저장은 다시 분석한다 — 요약·경계·순위·셀이 조회 결과와 같다")
    void savedReportMatchesFreshAnalysis() {
        JsonNode report = createOk(defaultBody("DOCX"));
        JsonNode fresh = get(ANALYZE_URL + "?bgngYmd=" + bgngYmd + "&endYmd=" + endYmd + "&gridSz=" + GRID_SZ);
        for (String key : List.of("totGridCnt", "grdTrgtGridCnt", "noEnfGridCnt", "refGridCnt", "over1GridCnt",
                "occUsedVl", "severeCnt", "warnCnt", "cautionCnt", "normalCnt", "lowCnt",
                "unit", "sectionTitle", "sectionSub", "clsfMthd", "clsfMthdNm",
                "gradeScale", "gradeCriteria", "ranking", "cells")) {
            assertThat(report.path(key)).as(key).isEqualTo(fresh.path(key));
        }
    }

    @Test
    @DisplayName("3. 스냅샷 왕복 — 저장 응답과 본문이 같고 보유 구간은 비어 있다")
    void snapshotRoundTrip() {
        JsonNode saved = createOk(defaultBody("PDF"));
        JsonNode snap = snapshot(saved.path("rptpId").asLong());
        saved.fieldNames().forEachRemaining(key -> {
            if (!key.equals("crtYmd")) {   // DB timestamp 정밀도 차이만 허용한다
                assertThat(snap.path(key)).as(key).isEqualTo(saved.path(key));
            }
        });
        assertThat(snap.path("crtYmd").asText()).startsWith(saved.path("crtYmd").asText().substring(0, 19));
        assertThat(snap.path("dataBgngYmd").isNull()).isTrue();
        assertThat(snap.path("dataEndYmd").isNull()).isTrue();
        assertThat(snap.path("cells").get(0).path("ring")).as("격자 폴리곤도 굳힌다").hasSize(4);
    }

    @Test
    @DisplayName("4. 🔴 저장 뒤 경계를 바꿔도 옛 스냅샷의 등급·범례·기준값은 그대로다")
    void boundaryChangeDoesNotMoveOldSnapshot() throws IOException {
        JsonNode saved = createOk(defaultBody("PDF"));
        JsonNode before = snapshot(saved.path("rptpId").asLong());

        setBoundaries("50", "60", "70", "80");
        JsonNode fresh = get(ANALYZE_URL + "?bgngYmd=" + bgngYmd + "&endYmd=" + endYmd + "&gridSz=" + GRID_SZ);
        assertThat(fresh.path("gradeCriteria")).as("새 분석에는 바뀐 경계가 반영된다")
                .isNotEqualTo(saved.path("gradeCriteria"));

        JsonNode after = snapshot(saved.path("rptpId").asLong());
        for (String key : List.of("gradeCriteria", "ranking", "cells", "severeCnt", "warnCnt", "cautionCnt",
                "normalCnt", "lowCnt")) {
            assertThat(after.path(key)).as(key).isEqualTo(before.path(key));
        }

        JsonNode param = MAPPER.readTree(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, saved.path("excnId").asLong()));
        assertThat(param.path("crtrVls").path("grdBndrys")).as("경계 4개").hasSize(4);
        assertThat(param.path("crtrVls").path("occUsedVl").decimalValue())
                .isEqualByComparingTo(saved.path("occUsedVl").decimalValue());
    }

    @Test
    @DisplayName("5. 🔴 유형 교차 — 다른 유형의 스냅샷 경로는 서로를 거부한다(404)")
    void snapshotPathsRejectEachOther() {
        long cngstId = createOk(defaultBody("PDF")).path("rptpId").asLong();
        assertThat(status(CVLCPT_SNAPSHOT_URL, cngstId)).as("혼잡도 → 민원건수 경로").isEqualTo(404);
        assertThat(status(SUPPLY_SNAPSHOT_URL, cngstId)).as("혼잡도 → 수급 경로").isEqualTo(404);

        long supplyId = createOkAt(SUPPLY_CREATE_URL, defaultBody("PDF")).path("rptpId").asLong();
        assertThat(status(CNGST_SNAPSHOT_URL, supplyId)).as("수급 → 혼잡도 경로").isEqualTo(404);
    }

    @Test
    @DisplayName("6. 🔴 남의 보고서는 404 다")
    void otherUsersReportIs404() {
        long rptpId = createOk(defaultBody("PDF")).path("rptpId").asLong();
        jdbcTemplate.update("UPDATE parking.tb_rptp SET creatr_id = 'tsk4317-other' WHERE rptp_id = ?", rptpId);
        assertThat(status(CNGST_SNAPSHOT_URL, rptpId)).isEqualTo(404);
    }

    @Test
    @DisplayName("7. 🔴 익명은 저장·스냅샷 모두 401 이고 아무것도 저장하지 않는다")
    void anonymousIs401AndInsertsNothing() {
        long rptpId = createOk(defaultBody("PDF")).path("rptpId").asLong();
        long[] before = tableCounts();

        assertThat(restTemplate.postForEntity(CREATE_URL, defaultBody("PDF"), String.class)
                .getStatusCode().value()).as("익명 저장").isEqualTo(401);
        assertThat(restTemplate.getForEntity(CNGST_SNAPSHOT_URL, String.class, rptpId)
                .getStatusCode().value()).as("익명 스냅샷").isEqualTo(401);
        assertThat(tableCounts()).isEqualTo(before);
    }

    @Test
    @DisplayName("8. 격자 0건 기간은 400 이고 아무것도 저장하지 않는다")
    void emptyResultIsRejected() {
        long[] before = tableCounts();
        LocalDate after = LocalDate.parse(endYmd, YMD).plusYears(5);
        Map<String, Object> body = defaultBody("PDF");
        body.put("bgngYmd", after.format(YMD));
        body.put("endYmd", after.plusDays(10).format(YMD));

        assertThat(post(CREATE_URL, body).getStatusCode().value()).isEqualTo(400);
        assertThat(tableCounts()).isEqualTo(before);
    }

    @Test
    @DisplayName("9. 잘못된 fileFmt·격자크기·366일 초과는 400 이다")
    void invalidRequestsAreRejected() {
        Map<String, Object> badFmt = defaultBody("HWP");
        assertThat(post(CREATE_URL, badFmt).getStatusCode().value()).as("fileFmt").isEqualTo(400);

        Map<String, Object> badGrid = defaultBody("PDF");
        badGrid.put("gridSz", 300);
        assertThat(post(CREATE_URL, badGrid).getStatusCode().value()).as("gridSz").isEqualTo(400);

        LocalDate end = LocalDate.parse(endYmd, YMD);
        Map<String, Object> tooLong = defaultBody("PDF");
        tooLong.put("bgngYmd", end.minusDays(MAX_RANGE_DAYS).format(YMD));
        assertThat(post(CREATE_URL, tooLong).getStatusCode().value()).as("기간").isEqualTo(400);
    }

    @Test
    @DisplayName("10. 🔴 셀이 있고 순위만 0행이면 201 이다 — 빈 표 안내는 문서가 맡는다")
    void emptyRankingWithCellsIsSaved() {
        // 경계를 허용 상한 근처로 올려 심각·경고·주의를 줄이고, 그런 지역을 찾는다(실데이터 특정 값을 박지 않는다).
        setBoundaries("97", "98", "99", "100");
        List<String> emdCds = jdbcTemplate.queryForList(
                "SELECT DISTINCT emd_cd FROM parking.tb_pblpklt WHERE emd_cd IS NOT NULL ORDER BY emd_cd", String.class);
        String found = null;
        for (String emdCd : emdCds) {
            JsonNode r = get(ANALYZE_URL + "?bgngYmd=" + bgngYmd + "&endYmd=" + endYmd
                    + "&gridSz=" + GRID_SZ + "&emdCd=" + emdCd);
            if (!r.path("cells").isEmpty() && r.path("ranking").isEmpty()) {
                found = emdCd;
                break;
            }
        }
        Assumptions.assumeTrue(found != null, "순위 0행 + 셀 존재 지역이 현재 데이터에 없다");

        Map<String, Object> body = defaultBody("PDF");
        body.put("emdCd", found);
        JsonNode report = createOk(body);
        assertThat(report.path("ranking")).isEmpty();
        assertThat(report.path("cells")).isNotEmpty();
        assertThat(snapshot(report.path("rptpId").asLong()).path("ranking")).isEmpty();
    }

    @Test
    @DisplayName("11. 보고서명·지역명 규칙을 따르고 생성자는 로그인 사용자다")
    void namesAndCreator() {
        JsonNode report = createOk(defaultBody("PDF"));
        assertThat(report.path("rptpNm").asText()).isEqualTo("생활권 혼잡도 보고서 전체 지역 500m");
        assertThat(report.path("regionNm").asText()).isEqualTo("전체 지역");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT creatr_id FROM parking.tb_rptp WHERE rptp_id = ?",
                String.class, report.path("rptpId").asLong())).isNotEqualTo("system");
    }

    // ─────────────────────────────────────────────────────── 헬퍼

    private Map<String, Object> defaultBody(String fileFmt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", bgngYmd);
        body.put("endYmd", endYmd);
        body.put("gridSz", GRID_SZ);
        body.put("fileFmt", fileFmt);
        return body;
    }

    private JsonNode createOk(Map<String, Object> body) {
        return createOkAt(CREATE_URL, body);
    }

    private JsonNode createOkAt(String url, Map<String, Object> body) {
        ResponseEntity<String> response = post(url, body);
        assertThat(response.getStatusCode().value()).as("저장은 201 이다: %s", response.getBody()).isEqualTo(201);
        JsonNode report = parse(response.getBody());
        createdRptpIds.add(report.path("rptpId").asLong());   // 🔴 tearDown 이 이것만 지운다
        return report;
    }

    private ResponseEntity<String> post(String url, Map<String, Object> body) {
        return restTemplate.exchange(url, HttpMethod.POST, auth.jsonEntity(session, body), String.class);
    }

    private JsonNode snapshot(long rptpId) {
        ResponseEntity<String> response = restTemplate.exchange(CNGST_SNAPSHOT_URL, HttpMethod.GET,
                new HttpEntity<>(session), String.class, rptpId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return parse(response.getBody());
    }

    private int status(String url, long rptpId) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(session), String.class, rptpId)
                .getStatusCode().value();
    }

    private JsonNode get(String url) {
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        assertThat(response.getStatusCode().value()).as(url).isEqualTo(200);
        return parse(response.getBody());
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setBoundaries(String b1, String b2, String b3, String b4) {
        String[] values = { b1, b2, b3, b4 };
        for (int i = 0; i < values.length; i++) {
            int n = jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?::numeric"
                    + BNDRY_WHERE + " AND vrbl_cd = ?", values[i], "CNGST_GRD_BNDRY" + (i + 1));
            assertThat(n).isEqualTo(1);
        }
    }

    /** 적용 내역에서 한 변수의 적용값 — 행이 없으면 실패한다. */
    private static BigDecimal ajmtVl(JsonNode vrblAjmt, String vrblCd) {
        for (JsonNode v : vrblAjmt.path("vrbls")) {
            if (vrblCd.equals(v.path("vrblCd").asText())) {
                return v.path("aplcnVl").decimalValue();
            }
        }
        throw new AssertionError("vrblAjmt 에 " + vrblCd + " 행이 없다");
    }

    private long[] tableCounts() {
        return new long[] { countOf("parking.tb_anls_excn"), countOf("parking.tb_anls_rslt"), countOf("parking.tb_rptp") };
    }

    private long countOf(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    // ─────────────────────────────────────────────────────── 분석 계수 일시 입력

    @Test
    @DisplayName("V1. vrblVls — vrblAjmt 가 적용값으로 POST·스냅샷에 남고 같은 입력의 조회와 같으며 설정 표는 그대로다 (노출 계수로 조정)")
    void vrblVlsInputReachesReport() throws IOException {
        String settingsSql = "SELECT * FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd";
        List<Map<String, Object>> settingsBefore = jdbcTemplate.queryForList(settingsSql);
        // 종전 PCSHR_CRTR_VL·MIN_CPCTY_VL 은 숨김이라 400 → 노출 GEO_CVRG_CRCT_VL·CNGST_GRD_BNDRY1 로 바꿨다
        BigDecimal occ = stngVl("PCSHR_CRTR_VL");
        BigDecimal geo = stngVl("GEO_CVRG_CRCT_VL");
        BigDecimal newGeo = geo.compareTo(new BigDecimal("3.5")) <= 0
                ? geo.add(new BigDecimal("0.5")) : geo.subtract(new BigDecimal("0.5"));
        BigDecimal newB1 = stngVl("CNGST_GRD_BNDRY1").add(stngVl("CNGST_GRD_BNDRY2")).divide(BigDecimal.valueOf(2));
        Map<String, Object> vrblVls = new LinkedHashMap<>();
        vrblVls.put("GEO_CVRG_CRCT_VL", newGeo);
        vrblVls.put("CNGST_GRD_BNDRY1", newB1);

        Map<String, Object> body = defaultBody("PDF");
        body.put("vrblVls", vrblVls);
        JsonNode created = createOk(body);
        assertThat(created.path("occUsedVl").decimalValue()).as("occ 는 조정하지 않았다 — 설정값").isEqualByComparingTo(occ);
        assertThat(created.path("vrblAjmt").path("ajmtYn").asText()).as("POST 응답도 채운다").isEqualTo("Y");
        assertThat(ajmtVl(created.path("vrblAjmt"), "GEO_CVRG_CRCT_VL")).isEqualByComparingTo(newGeo);
        assertThat(ajmtVl(created.path("vrblAjmt"), "CNGST_GRD_BNDRY1")).isEqualByComparingTo(newB1);

        JsonNode param = MAPPER.readTree(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, created.path("excnId").asLong()));
        assertThat(param.path("occUsedVl").decimalValue()).isEqualByComparingTo(occ);
        assertThat(param.path("crtrVls").path("occUsedVl").decimalValue()).isEqualByComparingTo(occ);
        assertThat(param.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("Y");
        assertThat(param.path("vrblAjmt").path("vrbls").size()).as("허용 목록 10행 전부").isEqualTo(10);
        assertThat(ajmtVl(param.path("vrblAjmt"), "GEO_CVRG_CRCT_VL")).isEqualByComparingTo(newGeo);

        JsonNode snap = snapshot(created.path("rptpId").asLong());
        assertThat(snap.path("vrblAjmt")).as("POST·GET 이 같은 값이다").isEqualTo(created.path("vrblAjmt"));
        assertThat(snap.path("occUsedVl")).isEqualTo(created.path("occUsedVl"));

        ResponseEntity<String> queried = restTemplate.getForEntity(
                ANALYZE_URL + "?bgngYmd={b}&endYmd={e}&gridSz={g}&vrblVls={v}", String.class,
                bgngYmd, endYmd, GRID_SZ, MAPPER.writeValueAsString(vrblVls));
        assertThat(queried.getStatusCode().value()).isEqualTo(200);
        JsonNode fresh = parse(queried.getBody());
        for (String key : List.of("totGridCnt", "noEnfGridCnt", "refGridCnt", "over1GridCnt", "occUsedVl",
                "severeCnt", "warnCnt", "cautionCnt", "normalCnt", "lowCnt", "gradeCriteria", "ranking", "cells",
                "vrblAjmt")) {
            assertThat(snap.path(key)).as(key).isEqualTo(fresh.path(key));
        }

        assertThat(jdbcTemplate.queryForList(settingsSql)).as("설정 표는 그대로다").isEqualTo(settingsBefore);
    }

    @Test
    @DisplayName("V2. vrblVls 생략은 ajmtYn N · 위반(역전·수급 코드·숨김 코드)은 400 이고 3표가 늘지 않는다 · 키 없는 구 스냅샷은 null")
    void omittedInvalidAndLegacyVrblVls() throws IOException {
        JsonNode plain = createOk(defaultBody("PDF"));
        assertThat(plain.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("N");

        long[] before = tableCounts();
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("CNGST_GRD_BNDRY1", stngVl("CNGST_GRD_BNDRY2")),
                Map.of("SPLY_MIN_MNTH_AVG_CVLCPT", 3),
                Map.of("MIN_CPCTY_VL", 30))) {   // 숨김 코드
            Map<String, Object> body = defaultBody("PDF");
            body.put("vrblVls", bad);
            assertThat(post(CREATE_URL, body).getStatusCode().value()).as("%s", bad).isEqualTo(400);
        }
        assertThat(tableCounts()).as("400 은 아무것도 남기지 않는다").containsExactly(before);

        // 🔴 구 보고서 — 자기 생성 행에서 키만 걷어 재현한다(로컬 기존 보고서에 기대지 않는다)
        assertThat(jdbcTemplate.update(
                "UPDATE parking.tb_anls_excn SET anls_param_json = anls_param_json - 'vrblAjmt' WHERE excn_id = ?",
                plain.path("excnId").asLong())).isEqualTo(1);
        JsonNode legacy = snapshot(plain.path("rptpId").asLong());
        assertThat(legacy.path("vrblAjmt").isNull() || legacy.path("vrblAjmt").isMissingNode())
                .as("🔴 VO 초기값이 생기면 구 보고서가 '조정 안 함' 으로 읽힌다").isTrue();
        assertThat(legacy.path("cells")).as("본문은 정상 복원").isNotEmpty();
    }

    private BigDecimal stngVl(String vrblCd) {
        return jdbcTemplate.queryForObject("SELECT stng_vl FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = 'LIFEZONE' AND vrbl_cd = ?", BigDecimal.class, vrblCd);
    }
}
