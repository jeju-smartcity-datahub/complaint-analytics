package jeju.datahub.agent;

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
 * AI 대화 API 의 인증·CSRF 회귀 테스트.
 *
 * <p>🔴 <b>운영 필터체인을 켠 채 돈다</b> — {@code properties = "app.auth.security-enabled=true"} 가
 * {@code TestSecurityConfig}({@code havingValue="false"})를 끄고 {@code SecurityConfig} 체인을 켠다.
 * 기본값으로 두면 {@code application-test.yml:40} 의 {@code security-enabled: false} 때문에
 * <b>permitAll 체인</b>이 떠서 미인증 요청이 컨트롤러까지 들어가고, test 프로파일은
 * {@code app.agent.base-url} 이 비어 있어 <b>401 이 아니라 503</b>({@code AGENT_NOT_CONFIGURED})이
 * 나온다 — 거짓 실패다. 선례: {@code jeju.datahub.auth.AuthSecurityIntegrationTest}.
 *
 * <p>🔴 <b>{@code app.agent.base-url=} 을 빈 값으로 고정한다</b>. base-url 은
 * {@code application.yml} 의 {@code ${AGENT_API_BASE_URL:}} 라, 루트 {@code .env} 를 export 한 셸에서
 * 돌리면 설정 검사를 통과해 503 케이스가 {@code AGENT_TOKEN_UNAVAILABLE} 로 실패한다.
 *
 * <p>⚠️ 이 레포에는 MockMvc 가 0건이라 {@code TestRestTemplate} 을 쓴다(spring-security-test 의
 * 모의 사용자 도구는 서버 필터체인에 닿지 않는다).
 *
 * <p>🔴 <b>삭제 케이스 일부가 인증 경로(mock-login)를 탄다</b> — {@code TB_USER}(mock-user)와
 * {@code TB_LGN_HSTRY} 행이 실제로 커밋되므로({@code RANDOM_PORT} 는 롤백이 안 먹는다)
 * {@link IntegrationLoginSupport#markStart()}·{@link IntegrationLoginSupport#cleanUp()} 으로
 * {@code lgn_sn > startSn} 기준으로 되돌린다({@code OR user_id IS NULL} 로 넓히지 않는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth.security-enabled=true", "app.agent.base-url="})
class AiSessionSecurityIntegrationTest {

    private static final String DELETE_URL = "/api/ai/sessions/x";

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
    @DisplayName("쿠키 없이 GET /api/ai/sessions → 401 (permitAll 목록에 없다 — 302 가 아니라 401 JSON)")
    void unauthenticatedSessionListIs401() {
        ResponseEntity<String> res = rest.getForEntity("/api/ai/sessions", String.class);

        // 🔴 503 이 나오면 운영 체인이 아니라 permitAll 체인이 뜬 것이다(위 properties 확인).
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("쿠키·헤더 없이 DELETE → 403 (CsrfFilter 가 인가 판정보다 먼저 돈다)")
    void unauthenticatedSessionDeleteWithoutCsrfIs403() {
        ResponseEntity<String> res = rest.exchange(DELETE_URL, HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()), String.class);

        // 🔴 401 이 나오면 CSRF 와 인가의 순서가 바뀐 것이다.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("세션 없이 XSRF 쿠키 + X-XSRF-TOKEN 만 실어 DELETE → 401")
    void unauthenticatedSessionDeleteIs401() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        String cookies = IntegrationLoginSupport.cookieHeader(health);
        String xsrf = IntegrationLoginSupport.xsrfOf(cookies);
        assertThat(xsrf).as("CsrfCookieFilter 가 XSRF-TOKEN 을 내려야 한다").isNotBlank();

        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookies);
        h.add("X-XSRF-TOKEN", xsrf);
        ResponseEntity<String> res = rest.exchange(DELETE_URL, HttpMethod.DELETE,
                new HttpEntity<>(h), String.class);

        // HttpStatusEntryPoint 라 본문이 없다 — status 만 단언한다.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("로그인 세션에서 X-XSRF-TOKEN 헤더만 빼고 DELETE → 403")
    void sessionDeleteWithoutCsrfIs403() {
        HttpHeaders session = auth.mockSession();
        HttpHeaders h = new HttpHeaders();
        h.addAll(session);
        h.remove("X-XSRF-TOKEN");

        ResponseEntity<String> res = rest.exchange(DELETE_URL, HttpMethod.DELETE,
                new HttpEntity<>(h), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("로그인 세션 + CSRF 로 DELETE → 503 AGENT_NOT_CONFIGURED (컨트롤러 도달 · 설정 → 토큰 순서)")
    @SuppressWarnings("rawtypes")
    void sessionDeleteWithCsrfIs503NotConfigured() {
        HttpHeaders session = auth.mockSession();

        ResponseEntity<Map> res = rest.exchange(DELETE_URL, HttpMethod.DELETE,
                new HttpEntity<>(session), Map.class);

        // 🔴 AGENT_TOKEN_UNAVAILABLE 이면 설정 검사가 withToken 람다 안으로 들어간 것이다.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).containsEntry("code", "AGENT_NOT_CONFIGURED");
    }
}
