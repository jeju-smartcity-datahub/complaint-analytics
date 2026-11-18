package jeju.datahub.aisurvey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.aisurvey.service.impl.AiSurveyPeriodReader;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * AI 만족도 설문 — <b>운영 필터 체인을 켠 채</b> HTTP 로 확인한다.
 *
 * <ul>
 *   <li>🔴 RANDOM_PORT 는 롤백이 안 먹는다 — 시작 시 {@code max(srvy_sn)} 을 기록해 그보다 큰 행만 지운다.</li>
 *   <li>🔴 공통코드 기간 2행은 테스트가 바꿀 수 있으므로 시작 값을 읽어 두었다가 그대로 되돌린다
 *       (14·7 을 박아 넣지 않는다 — 운영자가 바꾼 로컬 값을 덮지 않게).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth.security-enabled=true"})
class AiSurveyIntegrationTest {

    private static final String STATUS_URL = "/api/ai/surveys/status";
    private static final String SUBMIT_URL = "/api/ai/surveys";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    private IntegrationLoginSupport auth;
    private long startSn;
    private final Map<String, List<String>> periodBackup = new HashMap<>();

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forProductionChain(rest, jdbcTemplate);
        auth.markStart();
        startSn = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(srvy_sn), 0) FROM parking.tb_ai_dgstfn_srvy", Long.class);
        for (String g : List.of(AiSurveyPeriodReader.GROUP_SBMSN, AiSurveyPeriodReader.GROUP_PSTP)) {
            periodBackup.put(g, jdbcTemplate.queryForList(
                    "SELECT cd_vl FROM parking.tb_com_cd WHERE group_cd = ?", String.class, g));
        }
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM parking.tb_ai_dgstfn_srvy WHERE srvy_sn > ?", startSn);
        periodBackup.forEach((g, vals) -> {
            jdbcTemplate.update("DELETE FROM parking.tb_com_cd WHERE group_cd = ?", g);
            for (String v : vals) {
                jdbcTemplate.update("INSERT INTO parking.tb_com_cd (group_cd, cd_vl, cd_nm, sort_seq, use_yn) "
                        + "VALUES (?, ?, ?, 1, 'Y')", g, v, nameOf(g));
            }
        });
        auth.cleanUp();
    }

    private static String nameOf(String group) {
        return AiSurveyPeriodReader.GROUP_SBMSN.equals(group) ? "제출 후 재질문 일수" : "나중에 유예 일수";
    }

    @Test
    @DisplayName("미인증 GET status → 401")
    void unauthenticatedStatusIs401() {
        ResponseEntity<String> res = rest.getForEntity(STATUS_URL, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("로그인 세션에서 X-XSRF-TOKEN 만 빼고 POST → 403")
    void postWithoutCsrfIs403() {
        HttpHeaders h = new HttpHeaders();
        h.addAll(auth.mockSession());
        h.remove("X-XSRF-TOKEN");
        h.add(HttpHeaders.CONTENT_TYPE, "application/json");
        ResponseEntity<String> res = rest.exchange(SUBMIT_URL, HttpMethod.POST,
                new HttpEntity<>("{\"dgstfnScr\":6}", h), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("대상 → 제출 201 → 대상 아님 → 재제출 409 SURVEY_ALREADY_SUBMITTED")
    @SuppressWarnings("rawtypes")
    void submitFlow() {
        HttpHeaders session = auth.mockSession();

        Map before = getStatus(session);
        assertThat(before.get("eligible")).isEqualTo(true);
        assertThat(before.get("lastSbmsnDt")).isNull();

        ResponseEntity<Map> created = post(session, Map.of("dgstfnScr", 6, "opnnCn", "  좋아요  ", "sesnId", "portal-x"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long sn = ((Number) created.getBody().get("srvySn")).longValue();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT user_id, sesn_id, dgstfn_scr, opnn_cn FROM parking.tb_ai_dgstfn_srvy WHERE srvy_sn = ?", sn);
        assertThat(row.get("user_id")).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(row.get("sesn_id")).isEqualTo("portal-x");
        assertThat(((Number) row.get("dgstfn_scr")).intValue()).isEqualTo(6);
        assertThat(row.get("opnn_cn")).isEqualTo("좋아요");   // trim

        Map after = getStatus(session);
        assertThat(after.get("eligible")).isEqualTo(false);
        assertThat(after.get("lastSbmsnDt")).isNotNull();

        ResponseEntity<Map> again = post(session, Map.of("dgstfnScr", 9));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "SURVEY_ALREADY_SUBMITTED");
    }

    @Test
    @DisplayName("점수 누락·0·11, 의견 1001자 → 400 · 공백 의견은 NULL 저장")
    @SuppressWarnings("rawtypes")
    void validation() {
        HttpHeaders session = auth.mockSession();

        assertThat(post(session, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(session, Map.of("dgstfnScr", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(session, Map.of("dgstfnScr", 11)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(session, Map.of("dgstfnScr", 5, "opnnCn", "가".repeat(1001))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> ok = post(session, Map.of("dgstfnScr", 10, "opnnCn", "   "));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long sn = ((Number) ok.getBody().get("srvySn")).longValue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT opnn_cn FROM parking.tb_ai_dgstfn_srvy WHERE srvy_sn = ?", String.class, sn)).isNull();
    }

    @Test
    @DisplayName("공통코드 UPDATE 가 재기동 없이 반영된다 — 유예 일수 · 제출 기간 경계")
    @SuppressWarnings("rawtypes")
    void periodFromCommonCode() {
        HttpHeaders session = auth.mockSession();
        jdbcTemplate.update("UPDATE parking.tb_com_cd SET cd_vl = '3' WHERE group_cd = ?", AiSurveyPeriodReader.GROUP_PSTP);
        jdbcTemplate.update("UPDATE parking.tb_com_cd SET cd_vl = '1' WHERE group_cd = ?", AiSurveyPeriodReader.GROUP_SBMSN);

        assertThat(((Number) getStatus(session).get("postponeDays")).intValue()).isEqualTo(3);

        ResponseEntity<Map> created = post(session, Map.of("dgstfnScr", 7));
        long sn = ((Number) created.getBody().get("srvySn")).longValue();
        assertThat(getStatus(session).get("eligible")).isEqualTo(false);

        // 제출을 2일 전으로 옮기면 1일 기간이 지나 다시 대상
        jdbcTemplate.update("UPDATE parking.tb_ai_dgstfn_srvy SET reg_dt = now() - interval '2 days' WHERE srvy_sn = ?", sn);
        assertThat(getStatus(session).get("eligible")).isEqualTo(true);
        assertThat(post(session, Map.of("dgstfnScr", 8)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @SuppressWarnings("rawtypes")
    private Map getStatus(HttpHeaders session) {
        ResponseEntity<Map> res = rest.exchange(STATUS_URL, HttpMethod.GET, new HttpEntity<>(session), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(HttpHeaders session, Map<String, ?> body) {
        return rest.exchange(SUBMIT_URL, HttpMethod.POST, auth.jsonEntity(session, body), Map.class);
    }
}
