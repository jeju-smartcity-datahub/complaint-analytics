package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.InvestPrrtTestData.Win;
import jeju.datahub.analysis.service.InvestPrrtService;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 투자우선순위 보고서 <b>저장 → 스냅샷 조회 왕복</b> 통합 테스트.
 * ({@code POST /api/analysis/investment-priority/reports} · {@code GET /api/reports/{rptpId}/investment-priority})
 *
 * <p>test 프로파일은 운영 필터체인을 끄고 permitAll 로 교대하므로 인증·CSRF 없이 호출한다
 * (CSRF 403 은 운영 체인을 켠 런타임 검증이 본다).
 *
 * <p>🔴 <b>날짜·건수·주차장 이름·점수를 박지 않는다.</b> 기간은 DB 의 주차 민원 최근 발생일에서 3개월을
 * 거슬러 잡고({@link InvestPrrtTestData#recentWin()}), 기대값은 같은 조건의 조회 응답·DB 실측·응답 안의
 * 불변식으로 만든다.
 *
 * <h2>🔴 {@code RANDOM_PORT} 라 롤백이 안 먹는다 — 이 테스트는 실제로 커밋하고 손으로 되돌린다</h2>
 * 서버가 요청을 별도 스레드·별도 커넥션으로 처리해 테스트 트랜잭션이 서버 쪽 쓰기를 감싸지 못한다.
 * <ul>
 *   <li><b>보고서 3행</b> — {@link #post} 가 201 을 받을 때마다 {@code rptpId}·{@code excnId} 를 모아 두고
 *       {@link #cleanUp()} 이 <b>그 id 만</b> 결과 → 보고서 → 실행 순(FK 방향)으로 지운다.
 *       🔴 범위 DELETE({@code anls_type_cd='INVEST'} 전부 등)를 쓰지 않는다 — 화면에서 만든 보고서까지 날아간다.
 *       "400 이어야 하는 요청이 201 을 받은" 실패에서도 흔적이 남지 않게 수집은 단언보다 앞이다.</li>
 *   <li><b>가중치</b> — {@link #snapshotSurvivesWeightChange()} 만 {@code tb_anls_vrbl_stng} 을 고친다.
 *       고치기 <b>전에</b> INVEST 행의 {@code stng_vl}·{@code mdfr_id}·{@code mdfcn_dt} 를 읽어 두고
 *       {@link #cleanUp()} 이 <b>그 값으로</b> 되돌린다(단언이 실패해도 돈다).
 *       {@code AnalysisWeightIntegrationTest} 처럼 {@code bsc_stng_vl} 로 되돌리지 않는다 — 로컬에서 조정해 둔
 *       값을 기본값으로 덮게 된다.</li>
 * </ul>
 *
 * <h2>가중치를 설정 저장 API 가 아니라 {@code JdbcTemplate} 으로 직접 고치는 이유</h2>
 * 저장 API({@code PUT /api/analysis-weights/...})는 변경마다 {@code tb_anls_vrbl_hstry} 에 이력 행을 쌓고
 * 수정자·수정일시를 바꾼다 — 그 이력까지 "내가 만든 것만" 골라 지워야 하고, 메뉴 인가(M0603) 준비도 필요해진다.
 * 이 케이스가 보려는 것은 저장 API 가 아니라 <b>"설정 표가 바뀐 뒤에도 스냅샷이 그대로인가"</b> 하나라서,
 * 설정 표의 {@code stng_vl} 만 직접 고쳐 이력을 아예 만들지 않는다(분석은 매 요청 이 표를 읽는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("투자우선순위 보고서 저장 · 스냅샷 조회")
class InvestPrrtReportIntegrationTest {

    private static final String QUERY_URL = "/api/analysis/investment-priority";
    private static final String CREATE_URL = "/api/analysis/investment-priority/reports";
    private static final String SNAPSHOT_URL = "/api/reports/{rptpId}/investment-priority";

    private static final String INVEST = "INVEST";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 소수를 double 로 뭉개지 않는다 — 점수를 자릿수까지 비교한다 */
    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** 이 테스트가 만든 보고서·실행 id — 정리는 이 목록만 본다 */
    private final List<Long> createdRptpIds = new ArrayList<>();
    private final List<Long> createdExcnIds = new ArrayList<>();

    /** 가중치를 고치기 직전에 읽어 둔 INVEST 설정 원본. null 이면 고치지 않았다(되돌릴 것이 없다) */
    private List<Map<String, Object>> stngBackup;

    private long investExcnCntBefore;
    private String bgngYmd;
    private String endYmd;

    /**
     * 🔴 저장은 {@code creatr_id} 에 실사용자를 적고 스냅샷은 소유자 가드를
     * 거친다. {@code CurrentUserResolver} 가 principal 이 null 이면 401 이라 익명으로는 닿지
     * 않으므로 {@code mock-login} 세션을 쓴다. 익명이 401 인 것은 전용 케이스(#10)가 지킨다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
        InvestPrrtTestData data = new InvestPrrtTestData(jdbcTemplate);
        Win win = data.recentWin();
        assumeTrue(!data.targetIds().isEmpty(), "노상·공한지가 아닌 미소멸 주차장이 없다");
        assumeTrue(win != null, "좌표 있는 주차 민원(parking.tb_cvlcpt · 유형 01~05)이 없어 창을 잡을 수 없다");
        bgngYmd = win.bgng().format(InvestPrrtTestData.YMD);
        endYmd = win.end().format(InvestPrrtTestData.YMD);
        investExcnCntBefore = investExcnCnt();
    }

    /**
     * 이 테스트가 남긴 것만 되돌린다. 한 문장이 터져도 나머지를 마저 실행하고, 실패는 끝에서 한 번에 드러낸다 —
     * 정리 실패를 삼키면 다음 실행이 알 수 없는 이유로 깨진다.
     */
    @AfterEach
    void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();

        // ① 가중치 — 고쳤을 때만, 읽어 둔 원래 값으로
        if (stngBackup != null) {
            for (Map<String, Object> row : stngBackup) {
                runQuietly(failures, "UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?, mdfr_id = ?, mdfcn_dt = ?"
                                + " WHERE anls_type_cd = ? AND vrbl_cd = ?",
                        row.get("stng_vl"), row.get("mdfr_id"), row.get("mdfcn_dt"), INVEST, row.get("vrbl_cd"));
            }
            stngBackup = null;
        }

        // ② 보고서 3행 — 모아 둔 id 만, FK 방향(결과 → 보고서 → 실행)으로
        for (Long excnId : createdExcnIds) {
            runQuietly(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long rptpId : createdRptpIds) {
            runQuietly(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        }
        for (Long excnId : createdExcnIds) {
            runQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ? AND anls_type_cd = ?",
                    excnId, INVEST);
        }
        createdRptpIds.clear();
        createdExcnIds.clear();

        auth.cleanUp();

        assertThat(failures).as("테스트가 만든 행·설정 복원에 실패했다 — 손으로 되돌려야 한다").isEmpty();
        assertThat(investExcnCnt()).as("테스트가 끝나면 INVEST 실행 이력 수가 시작 때로 돌아온다")
                .isEqualTo(investExcnCntBefore);
    }

    private void runQuietly(List<RuntimeException> failures, String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    // ───────────────────────── 1. 저장 ─────────────────────────

    @Test
    @DisplayName("1. 201 — 실행·결과·보고서가 한 번에 한 행씩 늘고, 실행은 INVEST·DONE·실제 창, 보고서는 MANUAL·DONE 이다")
    void createInsertsExactlyOneRowInEachTable() {
        long[] before = tableCounts();

        JsonNode body = createOk(request(bgngYmd, endYmd, null, null, "PDF"));

        long[] after = tableCounts();
        assertThat(after[0] - before[0]).as("tb_anls_excn +1").isEqualTo(1);
        assertThat(after[1] - before[1]).as("tb_anls_rslt +1").isEqualTo(1);
        assertThat(after[2] - before[2]).as("tb_rptp +1").isEqualTo(1);

        long rptpId = body.path("rptpId").asLong();
        long excnId = body.path("excnId").asLong();
        assertThat(body.path("fileFmt").asText()).isEqualTo("PDF");
        assertThat(body.path("rptpNm").asText()).isNotBlank().endsWith(body.path("regionNm").asText());
        assertThat(body.path("bgngYmd").asText()).isEqualTo(bgngYmd);
        assertThat(body.path("endYmd").asText()).isEqualTo(endYmd);
        assertThat(body.path("anlsPrdExpln").asText()).isNotBlank();
        assertThat(body.path("crtYmd").asText()).isNotBlank();
        assertThat(body.path("lots").size()).as("필터가 없으면 lots = 전 지역 대상")
                .isEqualTo(body.path("smry").path("trgtCnt").asInt()).isPositive();

        Map<String, Object> excn = jdbcTemplate.queryForMap(
                "SELECT anls_type_cd, excn_stts_cd, anls_bgng_ymd, anls_end_ymd, sgg_nm, emd_cd, excutr_id,"
                        + " anls_param_json::text AS param FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        assertThat(excn.get("anls_type_cd")).isEqualTo(INVEST);
        assertThat(excn.get("excn_stts_cd")).isEqualTo("DONE");
        assertThat(excn.get("anls_bgng_ymd")).isEqualTo(bgngYmd);
        assertThat(excn.get("anls_end_ymd")).isEqualTo(endYmd);
        assertThat(excn.get("sgg_nm")).as("지역 조건이 없으면 null").isNull();
        assertThat(excn.get("emd_cd")).isNull();
        assertThat(excn.get("excutr_id")).isNotNull();

        // 재현 메타 — 가중치·임계·반경·표시 수·필터·기간이 키로 들어 있다
        JsonNode param = parse((String) excn.get("param"));
        assertThat(param.fieldNames()).toIterable().contains("bgngYmd", "endYmd", "sggNm", "emdCd", "weights",
                "upGrdCrtrScr", "mdGrdCrtrScr", "altmntRngVl", "indctPkltCnt", "scoreFormula");
        assertThat(param.path("bgngYmd").asText()).isEqualTo(bgngYmd);
        assertThat(param.path("weights").size()).isEqualTo(body.path("weights").size()).isPositive();
        assertThat(param.path("weights").get(0).path("vrblCd").asText())
                .isEqualTo(body.path("weights").get(0).path("vrblCd").asText());
        assertThat(param.path("weights").get(0).path("stngVl").decimalValue())
                .isEqualByComparingTo(body.path("weights").get(0).path("stngVl").decimalValue());
        assertThat(param.path("upGrdCrtrScr").decimalValue())
                .isEqualByComparingTo(body.path("gradeCriteria").get(0).path("minScr").decimalValue());
        assertThat(param.path("altmntRngVl").decimalValue())
                .isEqualByComparingTo(body.path("crtrVls").path("altmntRngVl").decimalValue());

        // 결과 JSON — 스냅샷 본문 8키(wbk — PDF 보고서에도 싣는다)
        JsonNode rslt = parse(jdbcTemplate.queryForObject(
                "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class, excnId));
        assertThat(rslt.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "smry", "weights", "gradeCriteria", "crtrVls", "warnings", "scoreFormula", "lots", "wbk");
        assertThat(rslt.path("lots").size()).isEqualTo(body.path("lots").size());

        Map<String, Object> rptp = jdbcTemplate.queryForMap(
                "SELECT crt_type, excn_id, stts_cd, file_fmt, file_path, rptp_nm, anls_prd_expln, creatr_id,"
                        + " creatr_nm, crt_ymd, stng_id FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        assertThat(rptp.get("crt_type")).isEqualTo("MANUAL");
        assertThat(((Number) rptp.get("excn_id")).longValue()).isEqualTo(excnId);
        assertThat(rptp.get("stts_cd")).isEqualTo("DONE");
        assertThat(rptp.get("file_fmt")).isEqualTo("PDF");
        assertThat(rptp.get("file_path")).as("파일을 서버에 굽지 않는다").isNull();
        assertThat(rptp.get("stng_id")).as("자동 생성 전용 칸").isNull();
        assertThat(rptp.get("rptp_nm")).isEqualTo(body.path("rptpNm").asText());
        assertThat(rptp.get("anls_prd_expln")).isEqualTo(body.path("anlsPrdExpln").asText());
        // 🔴 'system'/'관리자' 하드코딩이 아니라 세션 사용자다 (요청 ③④)
        assertThat(rptp.get("creatr_id")).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(rptp.get("creatr_nm")).isNotNull();
        assertThat(rptp.get("crt_ymd")).isNotNull();

        // 보고서 목록의 출처 칸 — 공통코드 ANLS_TYPE_CD 의 INVEST 이름이 그대로 나온다(매퍼 수정 없음)
        String srcNm = jdbcTemplate.queryForObject("SELECT cd_nm FROM parking.tb_com_cd"
                + " WHERE group_cd = 'ANLS_TYPE_CD' AND cd_vl = ?", String.class, INVEST);
        JsonNode header = getOk("/api/reports/{id}", rptpId);
        assertThat(header.path("anlsTypeCd").asText()).isEqualTo(INVEST);
        assertThat(header.path("srcNm").asText()).isEqualTo(srcNm).isNotBlank();
        assertThat(header.path("crtType").asText()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("2. 기간을 생략하면 '오늘 기준 최근 1년(12개월)' 이 실제 날짜로 저장된다 · DOCX 도 받는다")
    void defaultPeriodIsStoredAsActualDates() {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        LocalDate before = LocalDate.now(zone);
        JsonNode body = createOk(request(null, null, null, null, "DOCX"));
        LocalDate after = LocalDate.now(zone);

        Map<String, Object> excn = jdbcTemplate.queryForMap("SELECT anls_bgng_ymd, anls_end_ymd"
                + " FROM parking.tb_anls_excn WHERE excn_id = ?", body.path("excnId").asLong());
        LocalDate bgng = LocalDate.parse((String) excn.get("anls_bgng_ymd"), InvestPrrtTestData.YMD);
        LocalDate end = LocalDate.parse((String) excn.get("anls_end_ymd"), InvestPrrtTestData.YMD);
        assertThat(end).as("종료일 = 오늘").isBetween(before, after);
        assertThat(bgng).isEqualTo(end.minusMonths(InvestPrrtService.DEFAULT_PRD_MONTHS));
        assertThat(body.path("bgngYmd").asText()).isEqualTo(excn.get("anls_bgng_ymd"));
        assertThat(body.path("endYmd").asText()).isEqualTo(excn.get("anls_end_ymd"));
        assertThat(body.path("fileFmt").asText()).isEqualTo("DOCX");

        JsonNode snapshot = getOk(SNAPSHOT_URL, body.path("rptpId").asLong());
        assertThat(snapshot.path("bgngYmd").asText()).isEqualTo(excn.get("anls_bgng_ymd"));
        assertThat(snapshot.path("fileFmt").asText()).isEqualTo("DOCX");
    }

    // ───────────────────────── 3·4. 스냅샷 ─────────────────────────

    @Test
    @DisplayName("3. 스냅샷 GET 은 저장 직전 같은 조건의 조회 응답과 같다 — lots 전 행의 pkltId·rnk·snthsScr, 그리고 POST 응답과도 같다")
    void snapshotEqualsQueryAtSaveTime() {
        JsonNode query = getOk(QUERY_URL + "?bgngYmd={b}&endYmd={e}", bgngYmd, endYmd);
        JsonNode created = createOk(request(bgngYmd, endYmd, null, null, "PDF"));
        JsonNode snapshot = getOk(SNAPSHOT_URL, created.path("rptpId").asLong());

        JsonNode expected = query.path("lots");
        JsonNode actual = snapshot.path("lots");
        assertThat(actual.size()).as("lots 길이").isEqualTo(expected.size()).isPositive();
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = expected.get(i);
            JsonNode a = actual.get(i);
            assertThat(a.path("pkltId").asLong()).as("pkltId (index %d)", i).isEqualTo(e.path("pkltId").asLong());
            assertThat(a.path("rnk").asInt()).as("rnk (index %d)", i).isEqualTo(e.path("rnk").asInt());
            assertThat(a.path("snthsScr").decimalValue()).as("snthsScr (index %d)", i)
                    .isEqualByComparingTo(e.path("snthsScr").decimalValue());
            // 자릿수까지 그대로다(JSONB 가 숫자 표기를 보존하고, 복원이 double 을 거치지 않는다)
            assertThat(a.path("snthsScr").asText()).as("snthsScr 표기 (index %d)", i)
                    .isEqualTo(e.path("snthsScr").asText());
        }
        // 행 전체도 같다 — 저장하다 빠진 칸·바뀐 값이 없다
        assertThat(actual).as("lots 전체").isEqualTo(expected);
        for (String key : List.of("smry", "weights", "gradeCriteria", "crtrVls", "vrblAjmt", "warnings", "scoreFormula",
                "bgngYmd", "endYmd")) {
            assertThat(snapshot.path(key)).as("스냅샷 %s = 조회 응답", key).isEqualTo(query.path(key));
        }

        // POST 응답과 GET 응답이 같은 모양·같은 값이다(crtYmd 는 DB 가 마이크로초로 자르므로 뺀다)
        assertThat(fieldNames(snapshot)).as("응답 키 집합").isEqualTo(fieldNames(created));
        for (String key : fieldNames(created)) {
            if (!"crtYmd".equals(key)) {
                assertThat(snapshot.path(key)).as("GET %s = POST %s", key, key).isEqualTo(created.path(key));
            }
        }
        assertThat(snapshot.path("crtYmd").asText()).isNotBlank();
        assertThat(snapshot.path("regionNm").asText()).isNotBlank();
    }

    @Test
    @DisplayName("4. 가중치를 바꾼 뒤에도 스냅샷은 저장 시점 값 그대로다 (조회 API 는 새 가중치를 쓴다)")
    void snapshotSurvivesWeightChange() {
        JsonNode created = createOk(request(bgngYmd, endYmd, null, null, "PDF"));
        long rptpId = created.path("rptpId").asLong();
        JsonNode before = getOk(SNAPSHOT_URL, rptpId);

        // 반영 중인 가중치 두 개를 +10 / −10 — 합계는 그대로 두고 비율만 바꾼다
        List<Map<String, Object>> applied = jdbcTemplate.queryForList(
                "SELECT vrbl_cd, stng_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ?"
                        + " AND vrbl_se_cd = 'WGVL' AND use_yn = 'Y' AND stng_vl > 0 ORDER BY sort_seq", INVEST);
        Map<String, Object> up = null;
        Map<String, Object> down = null;
        BigDecimal delta = BigDecimal.TEN;
        for (Map<String, Object> row : applied) {
            BigDecimal vl = (BigDecimal) row.get("stng_vl");
            if (up == null && vl.add(delta).compareTo(BigDecimal.valueOf(100)) <= 0) {
                up = row;
            } else if (down == null && vl.compareTo(delta) > 0) {
                down = row;
            }
        }
        assumeTrue(up != null && down != null, "±10 을 줄 수 있는 INVEST 가중치 두 개가 없다(설정이 한 변수에 몰려 있다)");

        // 🔴 고치기 전에 원본을 읽어 둔다 — 이 줄부터는 cleanUp() 이 반드시 되돌린다(단언이 실패해도)
        stngBackup = jdbcTemplate.queryForList("SELECT vrbl_cd, stng_vl, mdfr_id, mdfcn_dt"
                + " FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ?", INVEST);
        assertThat(jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = stng_vl + ?"
                + " WHERE anls_type_cd = ? AND vrbl_cd = ?", delta, INVEST, up.get("vrbl_cd"))).isEqualTo(1);
        assertThat(jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = stng_vl - ?"
                + " WHERE anls_type_cd = ? AND vrbl_cd = ?", delta, INVEST, down.get("vrbl_cd"))).isEqualTo(1);

        // 변경이 실제로 분석에 먹었다 — 같은 조건의 조회는 이제 다른 가중치를 돌려준다
        JsonNode queryNow = getOk(QUERY_URL + "?bgngYmd={b}&endYmd={e}", bgngYmd, endYmd);
        assertThat(queryNow.path("weights")).as("조회 API 는 바뀐 가중치를 쓴다").isNotEqualTo(before.path("weights"));
        assertThat(queryNow.path("scoreFormula")).isNotEqualTo(before.path("scoreFormula"));

        // 그래도 스냅샷은 저장 시점 그대로다 — 가중치도, 산식 표기도, 주차장마다의 점수·순위도
        JsonNode after = getOk(SNAPSHOT_URL, rptpId);
        assertThat(after).as("스냅샷 응답 전체").isEqualTo(before);
        assertThat(after.path("weights")).isEqualTo(created.path("weights"));
        assertThat(after.path("lots")).isEqualTo(created.path("lots"));

        // 되돌린 뒤 원래 값인지 여기서도 본다(cleanUp 과 같은 문장 — 실패하면 이 케이스에서 바로 드러난다)
        restoreStng();
        for (Map<String, Object> row : applied) {
            BigDecimal now = jdbcTemplate.queryForObject("SELECT stng_vl FROM parking.tb_anls_vrbl_stng"
                    + " WHERE anls_type_cd = ? AND vrbl_cd = ?", BigDecimal.class, INVEST, row.get("vrbl_cd"));
            assertThat(now).as("복원된 %s", row.get("vrbl_cd")).isEqualByComparingTo((BigDecimal) row.get("stng_vl"));
        }
    }

    /** 읽어 둔 원본으로 되돌린다. 성공해야 백업을 비운다 — 실패하면 {@link #cleanUp()} 이 한 번 더 시도한다 */
    private void restoreStng() {
        for (Map<String, Object> row : stngBackup) {
            jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?, mdfr_id = ?, mdfcn_dt = ?"
                            + " WHERE anls_type_cd = ? AND vrbl_cd = ?",
                    row.get("stng_vl"), (String) row.get("mdfr_id"), (Timestamp) row.get("mdfcn_dt"),
                    INVEST, row.get("vrbl_cd"));
        }
        stngBackup = null;
    }

    // ───────────────────────── 5·6. 400 — 아무것도 남기지 않는다 ─────────────────────────

    @Test
    @DisplayName("5. 분석이 400 이면(날짜 역전 · 한쪽 날짜만 · 36개월 초과 · 없는 읍면동 · 결과 0곳) 세 표 모두 불변")
    void analysisFailureLeavesNoRows() {
        long[] before = tableCounts();

        assertThat(post(request(endYmd, bgngYmd, null, null, "PDF")).getStatusCode())
                .as("날짜 역전").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(request(bgngYmd, null, null, null, "PDF")).getStatusCode())
                .as("한쪽 날짜만").isEqualTo(HttpStatus.BAD_REQUEST);
        String tooEarly = LocalDate.parse(endYmd, InvestPrrtTestData.YMD).withDayOfMonth(1)
                .minusMonths(InvestPrrtService.MAX_PRD_MONTHS).format(InvestPrrtTestData.YMD);
        assertThat(post(request(tooEarly, endYmd, null, null, "PDF")).getStatusCode())
                .as("36개월 초과").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(request("2026-01-01", endYmd, null, null, "PDF")).getStatusCode())
                .as("날짜 형식").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(request(bgngYmd, endYmd, null, "00000000", "PDF")).getStatusCode())
                .as("없는 읍면동코드").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(request(bgngYmd, endYmd, "없는시", null, "PDF")).getStatusCode())
                .as("필터 통과 0곳").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(tableCounts()).as("실행·결과·보고서 행 수 불변").containsExactly(before);
    }

    @Test
    @DisplayName("6. fileFmt 가 없거나 PDF·DOCX·XLSX 가 아니면 400 — 세 표 모두 불변")
    void invalidFileFmtIs400() {
        long[] before = tableCounts();

        assertThat(post(request(bgngYmd, endYmd, null, null, null)).getStatusCode())
                .as("누락").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(request(bgngYmd, endYmd, null, null, "")).getStatusCode())
                .as("빈 값").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(request(bgngYmd, endYmd, null, null, "HWP")).getStatusCode())
                .as("그 밖의 값").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(request(bgngYmd, endYmd, null, null, "pdf")).getStatusCode())
                .as("소문자").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(tableCounts()).containsExactly(before);
    }

    @Test
    @DisplayName("6-1. fileFmt=XLSX 는 201 — 생성 응답·스냅샷·rslt_json 모두 wbk 를 담고, 조회 GET 에는 wbk 키가 없다")
    void xlsxIs201WithWbk() {
        // 정리는 post() 의 id 자동 수집 → cleanUp() 에 맡긴다
        JsonNode created = createOk(request(bgngYmd, endYmd, null, null, "XLSX"));
        assertThat(created.path("fileFmt").asText()).isEqualTo("XLSX");

        long rptpId = created.path("rptpId").asLong();
        long excnId = created.path("excnId").asLong();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT file_fmt FROM parking.tb_rptp WHERE rptp_id = ?", String.class, rptpId)).isEqualTo("XLSX");

        JsonNode wbk = created.path("wbk");
        assertThat(wbk.isObject()).as("생성 응답 wbk").isTrue();
        assertThat(wbk.path("trgtCnt").asInt()).as("trgtCnt = 필터 전 대상 수 ≥ lots").isGreaterThanOrEqualTo(created.path("lots").size());
        assertThat(wbk.path("rows").size()).as("rows = lots").isEqualTo(created.path("lots").size()).isPositive();
        for (int i = 0; i < wbk.path("rows").size(); i++) {
            assertThat(wbk.path("rows").get(i).path("pkltId").asLong()).as("rows[%d] 순서 = lots", i)
                    .isEqualTo(created.path("lots").get(i).path("pkltId").asLong());
        }
        assertThat(wbk.path("vrbls").size()).as("계수 8행").isEqualTo(8);

        // rslt_json 과 스냅샷 GET 에도 같은 wbk — 수동 조립 2곳(vo.setWbk · rslt.put)이 짝으로 들어갔다
        JsonNode rslt = parse(jdbcTemplate.queryForObject(
                "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class, excnId));
        assertThat(rslt.path("wbk")).as("rslt_json wbk = POST wbk").isEqualTo(wbk);
        JsonNode snapshot = getOk(SNAPSHOT_URL, rptpId);
        assertThat(snapshot.path("wbk")).as("스냅샷 wbk = POST wbk").isEqualTo(wbk);

        // 조회 GET 응답은 불변 — wbk 키 자체가 없다
        JsonNode query = getOk(QUERY_URL + "?bgngYmd={b}&endYmd={e}", bgngYmd, endYmd);
        assertThat(query.has("wbk")).as("조회 GET 에 wbk 키 없음").isFalse();
    }

    // ───────────────────────── 7. 404 ─────────────────────────

    @Test
    @DisplayName("7. 없는 rptpId 는 404 · 다른 유형 보고서 id 로 부르면 404 · 이 보고서를 다른 유형 경로로 불러도 404")
    void snapshotNotFound() {
        Long maxId = jdbcTemplate.queryForObject("SELECT coalesce(max(rptp_id), 0) FROM parking.tb_rptp", Long.class);
        assertThat(status(SNAPSHOT_URL, maxId + 1_000_000L)).as("없는 보고서").isEqualTo(HttpStatus.NOT_FOUND);

        // 이미 있는 다른 유형 보고서(있을 때만) — 유형이 다르면 이 경로에 그 자원이 없다
        List<Long> others = jdbcTemplate.queryForList("SELECT r.rptp_id FROM parking.tb_rptp r"
                + " LEFT JOIN parking.tb_anls_excn e ON e.excn_id = r.excn_id"
                + " WHERE e.anls_type_cd IS DISTINCT FROM ? ORDER BY r.rptp_id LIMIT 3", Long.class, INVEST);
        for (Long otherId : others) {
            assertThat(status(SNAPSHOT_URL, otherId)).as("다른 유형(또는 실행 미연결) 보고서 %d", otherId)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        // 반대 방향은 데이터와 무관하게 항상 확인된다 — 방금 만든 INVEST 보고서를 다른 유형 경로로
        long rptpId = createOk(request(bgngYmd, endYmd, null, null, "PDF")).path("rptpId").asLong();
        assertThat(status(SNAPSHOT_URL, rptpId)).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/reports/{id}/hotspot", rptpId)).as("집중구역 경로").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("/api/reports/{id}/complaint", rptpId)).as("민원현황 경로").isEqualTo(HttpStatus.NOT_FOUND);

        // 유형은 맞는데 결과 행이 없으면 404 — 빈 문서를 200 으로 내지 않는다
        long excnId = jdbcTemplate.queryForObject(
                "SELECT excn_id FROM parking.tb_rptp WHERE rptp_id = ?", Long.class, rptpId);
        jdbcTemplate.update("DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        assertThat(status(SNAPSHOT_URL, rptpId)).as("결과 행 없음").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ───────────────────────── 10. 익명은 401 ─────────────────────────

    @Test
    @DisplayName("10. 🔴 익명 HTTP 는 저장·스냅샷 모두 401 이고 세 표가 한 행도 늘지 않는다")
    void anonymousIs401AndInsertsNothing() {
        // 바디는 유효하다 — 같은 바디를 세션으로 보내면 201 이다(= 401 의 원인이 바디가 아니다)
        long rptpId = createOk(request(bgngYmd, endYmd, null, null, "PDF")).path("rptpId").asLong();
        long[] before = tableCounts();

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        assertThat(restTemplate.postForEntity(CREATE_URL,
                new HttpEntity<>(request(bgngYmd, endYmd, null, null, "PDF"), json), String.class)
                .getStatusCode()).as("익명 저장").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(SNAPSHOT_URL, String.class, rptpId).getStatusCode())
                .as("익명 스냅샷 — 내 보고서라도 세션이 없으면 401 이다")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(tableCounts()).as("401 이면 아무것도 저장하지 않는다").containsExactly(before);
    }

    // ───────────────────────── 11~13. 일시 입력 ─────────────────────────

    @Test
    @DisplayName("11. vrblVls — 보고서 저장값 = 같은 입력의 조회값이고 vrblAjmt 가 anls_param_json 과 POST·GET 에 같다 · 설정 표 불변")
    void reportStoresSameResultAsAdjustedQuery() throws JsonProcessingException {
        InvestPrrtTestData data = new InvestPrrtTestData(jdbcTemplate);
        Map<String, BigDecimal> input = data.adjustedVrblVls(BigDecimal.TEN);
        assumeTrue(input != null, "±10 을 줄 수 있는 INVEST 가중치 두 개가 없다");
        List<Map<String, Object>> stngBefore = data.investStngPrint();

        JsonNode query = getOk(QUERY_URL + "?bgngYmd={b}&endYmd={e}&vrblVls={v}", bgngYmd, endYmd,
                objectMapper.writeValueAsString(input));
        Map<String, Object> req = request(bgngYmd, endYmd, null, null, "PDF");
        req.put("vrblVls", input);
        JsonNode created = createOk(req);
        JsonNode snapshot = getOk(SNAPSHOT_URL, created.path("rptpId").asLong());

        assertThat(created.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("Y");
        for (String key : List.of("smry", "weights", "gradeCriteria", "crtrVls", "vrblAjmt", "warnings", "scoreFormula",
                "lots")) {
            assertThat(created.path(key)).as("POST %s = 같은 입력의 조회", key).isEqualTo(query.path(key));
            assertThat(snapshot.path(key)).as("GET %s = POST", key).isEqualTo(created.path(key));
        }

        JsonNode param = parse(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?", String.class,
                created.path("excnId").asLong()));
        assertThat(param.path("vrblAjmt")).as("anls_param_json.vrblAjmt").isEqualTo(created.path("vrblAjmt"));
        assertThat(param.path("altmntRngVl").decimalValue()).isEqualByComparingTo(input.get("ALTMNT_RNG_VL"));
        assertThat(param.path("mdGrdCrtrScr").decimalValue()).isEqualByComparingTo(input.get("MD_GRD_CRTR_SCR"));
        assertThat(data.investStngPrint()).as("설정 표 불변").isEqualTo(stngBefore);
    }

    @Test
    @DisplayName("12. vrblVls 가 규칙 위반이면(중간 ≥ 상위 · 목록 밖 코드) 400 이고 세 표 모두 불변")
    void invalidVrblVlsLeavesNoRows() {
        long[] before = tableCounts();
        BigDecimal up = jdbcTemplate.queryForObject("SELECT stng_vl FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = ? AND vrbl_cd = 'UP_GRD_CRTR_SCR'", BigDecimal.class, INVEST);

        Map<String, Object> inverted = request(bgngYmd, endYmd, null, null, "PDF");
        inverted.put("vrblVls", Map.of("MD_GRD_CRTR_SCR", up));
        assertThat(post(inverted).getStatusCode()).as("중간 = 상위").isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> unknown = request(bgngYmd, endYmd, null, null, "PDF");
        unknown.put("vrblVls", Map.of("GRD_BNDRY1", 3));
        assertThat(post(unknown).getStatusCode()).as("목록 밖 코드").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(tableCounts()).containsExactly(before);
    }

    @Test
    @DisplayName("13. 구 보고서 — anls_param_json 에 vrblAjmt 가 없으면 스냅샷의 vrblAjmt 는 null 이고 다른 칸은 그대로다")
    void oldReportHasNullVrblAjmt() {
        JsonNode created = createOk(request(bgngYmd, endYmd, null, null, "PDF"));
        long rptpId = created.path("rptpId").asLong();
        assertThat(created.path("vrblAjmt").path("ajmtYn").asText()).as("입력 없음도 내역을 남긴다").isEqualTo("N");
        JsonNode before = getOk(SNAPSHOT_URL, rptpId);

        // 이 테스트가 방금 만든 행만 고친다
        assertThat(jdbcTemplate.update("UPDATE parking.tb_anls_excn SET anls_param_json = anls_param_json - 'vrblAjmt'"
                + " WHERE excn_id = ?", created.path("excnId").asLong())).isEqualTo(1);

        JsonNode after = getOk(SNAPSHOT_URL, rptpId);
        assertThat(after.has("vrblAjmt")).isTrue();
        assertThat(after.path("vrblAjmt").isNull()).as("빈 내역으로 채우지 않는다").isTrue();
        assertThat(after.path("lots")).isEqualTo(before.path("lots"));
    }

    // ───────────────────────── 8·9. 지역 필터 ─────────────────────────

    @Test
    @DisplayName("8. sggNm 으로 저장하면 lots 가 그 시만 담기고 rnk 는 전 지역 순위 그대로다 — smry 는 전체 기준")
    void sggFilterKeepsGlobalRank() {
        JsonNode full = getOk(QUERY_URL + "?bgngYmd={b}&endYmd={e}", bgngYmd, endYmd);
        // 대상이 가장 적은 시군구를 고른다 — "1부터 다시 매기지 않았다" 가 가장 잘 드러난다
        Map<String, Integer> perSgg = new LinkedHashMap<>();
        Map<Long, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode l : full.path("lots")) {
            byId.put(l.path("pkltId").asLong(), l);
            if (l.hasNonNull("sggNm")) {
                perSgg.merge(l.path("sggNm").asText(), 1, Integer::sum);
            }
        }
        assumeTrue(!perSgg.isEmpty(), "시군구가 채워진 대상 주차장이 없다");
        String sggNm = perSgg.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();

        JsonNode created = createOk(request(bgngYmd, endYmd, sggNm, null, "PDF"));
        JsonNode snapshot = getOk(SNAPSHOT_URL, created.path("rptpId").asLong());

        JsonNode lots = snapshot.path("lots");
        assertThat(lots.size()).as("%s 대상 수", sggNm).isEqualTo(perSgg.get(sggNm)).isPositive();
        int prev = 0;
        for (JsonNode l : lots) {
            assertThat(l.path("sggNm").asText()).isEqualTo(sggNm);
            JsonNode inFull = byId.get(l.path("pkltId").asLong());
            assertThat(l.path("rnk").asInt()).as("pkltId %s 의 rnk = 전 지역 순위", l.path("pkltId").asLong())
                    .isEqualTo(inFull.path("rnk").asInt());
            assertThat(l).as("행 전체가 필터 없는 조회의 같은 주차장과 같다").isEqualTo(inFull);
            assertThat(l.path("rnk").asInt()).as("전체 순위 오름차순").isGreaterThan(prev);
            prev = l.path("rnk").asInt();
        }
        if (perSgg.size() > 1) {
            assertThat(lots.get(lots.size() - 1).path("rnk").asInt())
                    .as("다른 시가 섞여 있으므로 마지막 rnk 는 행 수보다 크다(1..n 으로 다시 매기지 않았다)")
                    .isGreaterThan(lots.size());
        }
        assertThat(snapshot.path("smry")).as("smry 는 필터와 무관하게 전체 기준").isEqualTo(full.path("smry"));
        assertThat(snapshot.path("smry").path("trgtCnt").asInt()).isEqualTo(full.path("lots").size());

        assertThat(snapshot.path("regionNm").asText()).isEqualTo(sggNm + " 전체");
        assertThat(created.path("regionNm").asText()).isEqualTo(sggNm + " 전체");
        assertThat(snapshot.path("rptpNm").asText()).endsWith(sggNm + " 전체");
        Map<String, Object> excn = jdbcTemplate.queryForMap("SELECT sgg_nm, emd_cd, anls_param_json->>'sggNm' AS p"
                + " FROM parking.tb_anls_excn WHERE excn_id = ?", created.path("excnId").asLong());
        assertThat(excn.get("sgg_nm")).isEqualTo(sggNm);
        assertThat(excn.get("emd_cd")).isNull();
        assertThat(excn.get("p")).isEqualTo(sggNm);
    }

    @Test
    @DisplayName("9. emdCd 만 보내도 시군구명을 채워 저장한다 — 지역명이 POST 와 GET 에서 같고, 시군구가 어긋나면 400")
    void emdOnlyFilterFillsSggNm() {
        JsonNode full = getOk(QUERY_URL + "?bgngYmd={b}&endYmd={e}", bgngYmd, endYmd);
        JsonNode sample = null;
        String otherSgg = null;
        for (JsonNode l : full.path("lots")) {
            if (sample == null && l.hasNonNull("sggNm") && l.hasNonNull("emdCd") && l.hasNonNull("emdNm")) {
                sample = l;
            } else if (sample != null && l.hasNonNull("sggNm")
                    && !l.path("sggNm").asText().equals(sample.path("sggNm").asText())) {
                otherSgg = l.path("sggNm").asText();
                break;
            }
        }
        assumeTrue(sample != null, "시군구·읍면동이 채워진 대상 주차장이 없다");
        String emdCd = sample.path("emdCd").asText();
        String expectedRegion = sample.path("sggNm").asText() + " " + sample.path("emdNm").asText();

        JsonNode created = createOk(request(bgngYmd, endYmd, null, emdCd, "PDF"));
        JsonNode snapshot = getOk(SNAPSHOT_URL, created.path("rptpId").asLong());

        assertThat(created.path("regionNm").asText()).isEqualTo(expectedRegion);
        assertThat(snapshot.path("regionNm").asText()).as("다시 열어도 같은 지역명(NULL 연결로 사라지지 않는다)")
                .isEqualTo(expectedRegion);
        assertThat(snapshot.path("lots").size()).isPositive();
        for (JsonNode l : snapshot.path("lots")) {
            assertThat(l.path("emdCd").asText()).isEqualTo(emdCd);
        }
        Map<String, Object> excn = jdbcTemplate.queryForMap("SELECT sgg_nm, emd_cd FROM parking.tb_anls_excn"
                + " WHERE excn_id = ?", created.path("excnId").asLong());
        assertThat(excn.get("sgg_nm")).isEqualTo(sample.path("sggNm").asText());
        assertThat(excn.get("emd_cd")).isEqualTo(emdCd);

        if (otherSgg != null) {
            long[] before = tableCounts();
            assertThat(post(request(bgngYmd, endYmd, otherSgg, emdCd, "PDF")).getStatusCode())
                    .as("읍면동이 그 시군구에 속하지 않는다").isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(tableCounts()).containsExactly(before);
        }
    }

    // ───────────────────────── 헬퍼 ─────────────────────────

    /** null 인 키는 싣지 않는다 — "생략" 을 그대로 흉내 낸다 */
    private static Map<String, Object> request(String bgngYmd, String endYmd, String sggNm, String emdCd,
                                               String fileFmt) {
        Map<String, Object> req = new LinkedHashMap<>();
        if (bgngYmd != null) {
            req.put("bgngYmd", bgngYmd);
        }
        if (endYmd != null) {
            req.put("endYmd", endYmd);
        }
        if (sggNm != null) {
            req.put("sggNm", sggNm);
        }
        if (emdCd != null) {
            req.put("emdCd", emdCd);
        }
        if (fileFmt != null) {
            req.put("fileFmt", fileFmt);
        }
        return req;
    }

    /**
     * 저장 호출. 🔴 <b>201 이면 단언보다 먼저 id 를 수집한다</b> — 400 을 기대한 요청이 201 을 받아 단언이
     * 실패하더라도 그 행은 {@link #cleanUp()} 이 지운다.
     */
    private ResponseEntity<String> post(Map<String, Object> request) {
        ResponseEntity<String> res = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, request), String.class);
        if (res.getStatusCode() == HttpStatus.CREATED && res.getBody() != null) {
            JsonNode body = parse(res.getBody());
            if (body.hasNonNull("rptpId")) {
                createdRptpIds.add(body.path("rptpId").asLong());
            }
            if (body.hasNonNull("excnId")) {
                createdExcnIds.add(body.path("excnId").asLong());
            }
        }
        return res;
    }

    private JsonNode createOk(Map<String, Object> request) {
        ResponseEntity<String> res = post(request);
        assertThat(res.getStatusCode()).as("POST %s → %s", request, res.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = parse(res.getBody());
        assertThat(body.path("rptpId").asLong()).isPositive();
        assertThat(body.path("excnId").asLong()).isPositive();
        return body;
    }

    private JsonNode getOk(String url, Object... vars) {
        ResponseEntity<String> res = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(session), String.class, vars);
        assertThat(res.getStatusCode()).as("GET %s → %s", url, res.getBody()).isEqualTo(HttpStatus.OK);
        return parse(res.getBody());
    }

    private HttpStatus status(String url, Object... vars) {
        return HttpStatus.valueOf(restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(session),
                String.class, vars).getStatusCode().value());
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 파싱 실패: " + json, e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(null);
        return names;
    }

    /** {@code [tb_anls_excn, tb_anls_rslt, tb_rptp]} 전체 행 수 */
    private long[] tableCounts() {
        return new long[] {count("parking.tb_anls_excn"), count("parking.tb_anls_rslt"), count("parking.tb_rptp")};
    }

    private long count(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }

    private long investExcnCnt() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_excn WHERE anls_type_cd = ?", Long.class, INVEST);
        return n == null ? 0L : n;
    }
}
