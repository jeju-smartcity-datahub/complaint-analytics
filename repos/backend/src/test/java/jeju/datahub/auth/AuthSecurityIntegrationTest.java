package jeju.datahub.auth;

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

import jeju.datahub.support.IntegrationLoginSupport;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인증·CSRF 회귀 전용 테스트 (TSK-4076 Step 6).
 *
 * <p>🔴 <b>운영 필터체인을 켠 채 도는 유일한 테스트다</b> —
 * {@code properties = "app.auth.security-enabled=true"} 가 {@code TestSecurityConfig}
 * ({@code havingValue="false"})를 끄고 {@code SecurityConfig} 체인을 켠다. 컨텍스트가 분리돼
 * 다른 테스트에 영향이 없다. ({@code app.auth.mode=mock} 은 {@code application-test.yml} 에서
 * 상속하므로 여기서 다시 주지 않는다.)
 *
 * <p>🔴 이 레포는 httpclient5 가 없어 {@code TestRestTemplate} 이
 * {@code SimpleClientHttpRequestFactory} 로 돌고 <b>쿠키 저장소가 없다</b> —
 * {@code Set-Cookie} → {@code Cookie} 를 테스트가 직접 이어 붙인다.
 *
 * <p>🔴 spring-security-test 의 세 도구(모의 사용자 애노테이션 · MockMvc post-processor · csrf()
 * request post-processor)는 <b>하나도 쓸 수 없다</b> — 이 레포에 MockMvc 가 0건이고, 테스트 스레드의
 * {@code SecurityContextHolder} 는 별 스레드로 도는 서버 필터체인에 닿지 않는다. 그래서 그 의존성을
 * 아예 넣지 않았다(Step 2).
 * ⚠️ 이 주석에 그 애노테이션 이름을 <b>리터럴로 적지 않는다</b> — Step 6 검증이 리터럴 grep 이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.security-enabled=true")
class AuthSecurityIntegrationTest {

    /** Step 5 가 리터럴 상수로 못박은 mock 식별자 */
    private static final String MOCK_ID = "mock-user";

    /** TSK-4115 Step 7 — 메뉴 권한 관리 화면. {@code PUT /api/menu-authorities/**} 가 요구하는 메뉴다. */
    private static final String MENU_AUTHRT_MENU = "M0602";
    /** 그 화면에서 편집 대상으로 쓰는 다른 메뉴(보고서 관리) — 요구 메뉴가 아니다 */
    private static final String OTHER_MENU = "M0402";
    private static final String AUTHORITIES_URL = "/api/menu-authorities/authorities";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 이 테스트가 만든 로그인 이력 행의 경계 (TSK-4104 Step 8).
     *
     * <p>Step 6 이 mock-login/logout 에 이력 기록을 끼우면서 이 테스트도 부수적으로
     * {@code TB_LGN_HSTRY} 행을 남기게 됐다. {@code RANDOM_PORT} 라 실제로 커밋되므로
     * 지우지 않으면 실행할 때마다 쌓인다.
     */
    private IntegrationLoginSupport auth;

    @BeforeEach
    void markStart() {
        // 운영 체인을 켠 클래스라 forProductionChain — XSRF 토큰 발급 단언을 그대로 유지한다
        auth = IntegrationLoginSupport.forProductionChain(rest, jdbcTemplate);
        auth.markStart();
    }

    /**
     * 🔴 이 테스트는 <b>실제로 커밋한다</b>({@code RANDOM_PORT} 라 롤백되지 않는다) —
     * {@code mock-login} 이 {@code TB_USER} 행을, CSRF 검증의 {@code PUT} 이 {@code TB_AUTHRT_MENU}
     * 를 바꾼다. 둘 다 되돌리지 않으면 다음 실행의 {@code MenuAuthrtIntegrationTest} 와
     * Step 5 {@code /me} 의 {@code menuPathNms} 개수 검증이 드리프트로 깨진다.
     *
     * <p>매핑 복원은 {@code MenuAuthrtIntegrationTest.restoreSeed()} 와 <b>동일한 로직</b>이다
     * (전체 DELETE 후 시드와 같은 WHERE 로 3회 INSERT → 40행).
     */
    @AfterEach
    void restoreSeed() {
        // mock 계정·로그인 이력·헬퍼가 넣은 매핑 정리는 공용 헬퍼가 진다 (TSK-4115 Step 7-1).
        // 🔴 `OR user_id IS NULL` 로 넓히지 않는다 — 실패 행은 user_id 가 null 이라(D2)
        //    테스트가 만들지 않은 실 실패 이력까지 지운다 (TSK-4104 Step 8).
        auth.cleanUp();

        jdbcTemplate.update("DELETE FROM parking.tb_authrt_menu");
        jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                SELECT 'ROLE_SYSTEM_ADMIN', menu_id, 'system', now()
                  FROM parking.tb_menu WHERE menu_path_nm IS NOT NULL""");
        jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                SELECT 'ROLE_SERVICE_ADMIN', menu_id, 'system', now()
                  FROM parking.tb_menu
                 WHERE menu_path_nm IS NOT NULL""");
        jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                SELECT 'ROLE_USER', menu_id, 'system', now()
                  FROM parking.tb_menu
                 WHERE menu_path_nm IS NOT NULL
                   AND (up_menu_id IS DISTINCT FROM 'M06')
                   AND menu_id NOT IN ('M0402', 'M0404')""");
    }

    // ---------------------------------------------------------------- helper

    /**
     * 세션 확립 — 절차는 공용 헬퍼({@code IntegrationLoginSupport})가 진다 (TSK-4115 Step 7-1).
     * 운영 체인이므로 {@code XSRF-TOKEN} 발급 단언이 그대로 살아 있다.
     */
    private HttpHeaders mockSession() {
        return auth.mockSession();
    }

    private String anyMenuId() {
        return jdbcTemplate.queryForObject(
                "SELECT menu_id FROM parking.tb_menu WHERE menu_path_nm IS NOT NULL ORDER BY 1 LIMIT 1",
                String.class);
    }

    // ---------------------------------------------------------------- 인증

    @Test
    @DisplayName("세션·XSRF 쿠키에 Secure·SameSite 가 붙고 XSRF 만 HttpOnly 가 아니다 (OWASP ZAP)")
    void cookiesCarrySecurityFlags() {
        ResponseEntity<String> anonymous = rest.getForEntity("/actuator/health", String.class);
        // 🔴 HttpOnly 를 켜면 axios 가 토큰을 못 읽어 모든 POST 가 403 이다
        assertThat(anonymous.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anySatisfy(c -> assertThat(c).startsWith("XSRF-TOKEN=")
                        .contains("Secure").contains("SameSite=Lax").doesNotContain("HttpOnly"));

        String cookies = IntegrationLoginSupport.cookieHeader(anonymous);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookies);
        headers.add("X-XSRF-TOKEN", IntegrationLoginSupport.xsrfOf(cookies));
        ResponseEntity<Map> login = rest.exchange("/api/auth/mock-login", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);

        assertThat(login.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anySatisfy(c -> assertThat(c).startsWith("JSESSIONID=")
                        .contains("Secure").contains("HttpOnly").contains("SameSite=Lax"));
    }

    @Test
    @DisplayName("1. 쿠키 없이 GET /api/menu-authorities/authorities → 401 (Step 0 기준선 200 의 반전)")
    void unauthenticatedApiIs401() {
        ResponseEntity<String> res = rest.getForEntity("/api/menu-authorities/authorities", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("2. health 의 XSRF-TOKEN 을 실어 mock-login → 200 + JSESSIONID (permitAll 은 CSRF 면제가 아니다)")
    void mockLoginNeedsCsrfAndIssuesSession() {
        mockSession();   // 내부 단언이 200 + JSESSIONID 를 고정한다

        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_user WHERE user_id = ?", Integer.class, MOCK_ID);
        assertThat(cnt).isEqualTo(1);
    }

    @Test
    @DisplayName("3. 🔴 CSRF 헤더 없이 mock-login → 403 (matcher 누락이면 401 이 나온다)")
    void mockLoginWithoutCsrfIs403() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, IntegrationLoginSupport.cookieHeader(health));   // 쿠키만, X-XSRF-TOKEN 없음

        ResponseEntity<String> res = rest.exchange("/api/auth/mock-login", HttpMethod.POST,
                new HttpEntity<>(h), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------- CSRF (D6 회귀)

    @Test
    @DisplayName("4. 🔴 세션은 있는데 X-XSRF-TOKEN 이 없으면 PUT → 403, 붙이면 → 200 (D6 회귀 고정)")
    void putRequiresCsrfHeader() {
        HttpHeaders session = mockSession();
        // 🔴 TSK-4115 Step 7 — 이 PUT 은 이제 M0602 매핑을 요구한다(@PreAuthorize). 부여하지 않으면
        //    CSRF 헤더를 제대로 실어도 403 이 되어 D6 CSRF 회귀 앵커가 무의미해진다.
        auth.grantMenu(MENU_AUTHRT_MENU);
        String menuId = anyMenuId();
        String url = "/api/menu-authorities/menus/" + menuId + "/authorities";
        String body = "{\"authrtCds\":[\"ROLE_SYSTEM_ADMIN\"]}";

        // CSRF 헤더 제거 — "GET 은 되는데 POST/PUT 만 403" 이 이 함정의 증상이다
        HttpHeaders noCsrf = new HttpHeaders();
        noCsrf.addAll(HttpHeaders.COOKIE, session.get(HttpHeaders.COOKIE));
        noCsrf.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> denied = rest.exchange(url, HttpMethod.PUT,
                new HttpEntity<>(body, noCsrf), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders withCsrf = new HttpHeaders();
        withCsrf.addAll(session);
        withCsrf.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> allowed = rest.exchange(url, HttpMethod.PUT,
                new HttpEntity<>(body, withCsrf), String.class);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- 로그아웃 · 정지

    @Test
    @DisplayName("5. 🔴 logout → 200 + idpLogoutUrl null, 이어서 같은 세션의 /me → 401")
    void logoutInvalidatesSession() {
        HttpHeaders session = mockSession();

        ResponseEntity<Map> out = rest.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(session), Map.class);

        // 500 이면 Step 5 의 (OidcUser) 캐스팅 회귀이거나 Map.of(null) NPE 회귀다
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(out.getBody()).containsKey("idpLogoutUrl");
        assertThat(out.getBody().get("idpLogoutUrl")).isNull();   // mock 세션엔 id_token 이 없다

        ResponseEntity<String> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(session), String.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("6. 🔴 use_yn='N' 으로 정지된 뒤 같은 세션의 /me → 401 (500 이면 null 분기 누락 회귀)")
    void disabledUserSessionIs401() {
        HttpHeaders session = mockSession();
        jdbcTemplate.update("UPDATE parking.tb_user SET use_yn = 'N' WHERE user_id = ?", MOCK_ID);

        ResponseEntity<String> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(session), String.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("7. D10 경로 정책 — 로그인 세션이면 /me·/actuator/info 둘 다 200, 쿠키 없으면 info 는 401")
    void authenticatedMeOkAndActuatorInfoStill401() {
        HttpHeaders session = mockSession();

        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(session), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // health 만 열려 있다 — info 는 로그인해도 authenticated 라 200 이어야 한다
        assertThat(rest.exchange("/actuator/info", HttpMethod.GET,
                new HttpEntity<>(session), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 쿠키 없이는 401
        assertThat(rest.getForEntity("/actuator/info", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    // ---------------------------------------------------------------- 인가 (TSK-4115 Step 7)

    @Test
    @DisplayName("8. 🔴 권한 없는 세션 — 쓰기 PUT 은 403, 같은 세션의 읽기 GET 은 200, 미인증은 401 (401/403 구분)")
    void writeIsForbiddenWithoutMenuMappingButReadIsOpen() {
        HttpHeaders session = mockSession();   // 기본 롤 ROLE_USER — 시드에 M0602 매핑이 없다

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(session);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> denied = rest.exchange(
                "/api/menu-authorities/menus/" + OTHER_MENU + "/authorities", HttpMethod.PUT,
                new HttpEntity<>("{\"authrtCds\":[\"ROLE_SYSTEM_ADMIN\"]}", headers), String.class);

        // 🔴 인증은 됐고 권한만 없다 → 403 (401 이면 세션이 안 붙은 것이다)
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 읽기는 막지 않는다 (범위 밖 #2 — 쓰기 4종만 건다)
        assertThat(rest.exchange(AUTHORITIES_URL, HttpMethod.GET,
                new HttpEntity<>(session), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 미인증은 403 이 아니라 401 이다 (D7 — HttpStatusEntryPoint)
        assertThat(rest.getForEntity(AUTHORITIES_URL, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("9. 🔴 매핑을 INSERT 하면 재로그인 없이 같은 PUT 이 200 — 표 파생 판정이 매 요청 조회임을 고정")
    void grantingMenuTakesEffectWithoutRelogin() {
        HttpHeaders session = mockSession();

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(session);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request =
                new HttpEntity<>("{\"authrtCds\":[\"ROLE_SYSTEM_ADMIN\"]}", headers);
        String url = "/api/menu-authorities/menus/" + OTHER_MENU + "/authorities";

        assertThat(rest.exchange(url, HttpMethod.PUT, request, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 🔴 요구 메뉴는 편집 대상(M0402)이 아니라 **그 화면**(M0602)이다 — 7-2 의 표.
        //    (검증 항목 원문은 'M0402 매핑 INSERT' 라고 적었지만, 표 파생 판정의 요구 메뉴는 M0602 다.
        //     고정하려는 성질 — "INSERT 즉시, 재로그인 없이 반영" — 은 그대로다.)
        auth.grantMenu(MENU_AUTHRT_MENU);

        // ✅ 재로그인 없이 같은 세션·같은 요청이 통과해야 한다.
        //    403 이면 어딘가가 세션에 굳은 authority 를 보고 있다는 뜻이다.
        assertThat(rest.exchange(url, HttpMethod.PUT, request, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("10. 쿠키 없이 GET /api/analysis/lifezone/congestion → 401 (TSK-4090 — 혼잡도 조회도 인증 필요)")
    void unauthenticatedLifezoneCongestionIs401() {
        ResponseEntity<String> res = rest.getForEntity(
                "/api/analysis/lifezone/congestion?bgngYmd=20260101&endYmd=20260131", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
