package jeju.datahub.report;

import static org.assertj.core.api.Assertions.assertThat;

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

import jeju.datahub.support.IntegrationLoginSupport;

/**
 * AI 보고서 등록·스냅샷의 인증·CSRF 회귀 테스트 (TSK-4293 Step 5).
 *
 * <p>🔴 운영 필터체인을 켠 채 돈다({@code app.auth.security-enabled=true}) — 기본 test 체인은 permitAll 이라
 * 401·403 을 단언할 수 없다. 🔴 {@code app.agent.base-url=} 을 빈 값으로 고정한다 — 루트 {@code .env} 를 export 한
 * 셸에서도 설정 검사가 503 {@code AGENT_NOT_CONFIGURED} 로 끊기게(선례 {@code AiSessionSecurityIntegrationTest}).
 *
 * <p>🔴 POST 미인증 401 은 {@code /actuator/health} 로 받은 XSRF 쿠키와 {@code X-XSRF-TOKEN} 헤더를 <b>실어야</b>
 * 한다 — 없으면 CsrfFilter 가 인가보다 먼저 돌아 403 이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth.security-enabled=true", "app.agent.base-url="})
class AiReportSecurityIntegrationTest {

    private static final String POST_URL = "/api/ai/sessions/x/reports";
    private static final String BODY = "{\"msgId\":\"chatcmpl-000000000001\",\"rptIdx\":0}";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    private IntegrationLoginSupport auth;

    @BeforeEach
    void markStart() {
        auth = IntegrationLoginSupport.forProductionChain(rest, jdbcTemplate);
        auth.markStart();
    }

    @AfterEach
    void cleanUp() {
        auth.cleanUp();
    }

    @Test
    @DisplayName("세션 없이 XSRF 쿠키 + X-XSRF-TOKEN 만 실어 POST → 401")
    void unauthenticatedPostIs401() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        String cookies = IntegrationLoginSupport.cookieHeader(health);
        String xsrf = IntegrationLoginSupport.xsrfOf(cookies);
        assertThat(xsrf).as("CsrfCookieFilter 가 XSRF-TOKEN 을 내려야 한다").isNotBlank();

        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookies);
        h.add("X-XSRF-TOKEN", xsrf);
        h.add(HttpHeaders.CONTENT_TYPE, "application/json");
        ResponseEntity<String> res = rest.exchange(POST_URL, HttpMethod.POST, new HttpEntity<>(BODY, h), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("쿠키·헤더 없이 POST → 403 (CsrfFilter 가 인가보다 먼저 돈다)")
    void postWithoutCsrfIs403() {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.CONTENT_TYPE, "application/json");
        ResponseEntity<String> res = rest.exchange(POST_URL, HttpMethod.POST, new HttpEntity<>(BODY, h), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("로그인 세션에서 X-XSRF-TOKEN 헤더만 빼고 POST → 403")
    void loggedInPostWithoutCsrfIs403() {
        HttpHeaders h = new HttpHeaders();
        h.addAll(auth.mockSession());
        h.remove("X-XSRF-TOKEN");
        h.add(HttpHeaders.CONTENT_TYPE, "application/json");

        ResponseEntity<String> res = rest.exchange(POST_URL, HttpMethod.POST, new HttpEntity<>(BODY, h), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("로그인 세션 + CSRF 로 POST → 503 AGENT_NOT_CONFIGURED (컨트롤러 도달 · 예외 핸들러 · 설정 → 토큰 순서)")
    @SuppressWarnings("rawtypes")
    void loggedInPostIs503NotConfigured() {
        HttpHeaders session = auth.mockSession();

        ResponseEntity<Map> res = rest.exchange(POST_URL, HttpMethod.POST,
                auth.jsonEntity(session, Map.of("msgId", "chatcmpl-000000000001", "rptIdx", 0)), Map.class);

        // 🔴 500 이면 AiReportExceptionHandler 가 붙지 않은 것 · AGENT_TOKEN_UNAVAILABLE 이면 설정 검사가 람다 안이다
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).containsEntry("code", "AGENT_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("쿠키 없이 GET /api/reports/{id}/ai → 401")
    void unauthenticatedSnapshotIs401() {
        ResponseEntity<String> res = rest.getForEntity("/api/reports/1/ai", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
