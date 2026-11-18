package jeju.datahub.auth;

import jeju.datahub.auth.service.impl.DhubPasswordAuthClient;
import jeju.datahub.auth.service.impl.DhubUserProfileClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 이메일/비밀번호 로그인 통합 회귀.
 *
 * <p>하네스({@code SPRING_PROFILES_ACTIVE=test}) 위에서 돈다.
 *
 * <p>🔴 <b>실 IdP 를 부르지 않는다.</b> {@link DhubPasswordAuthClient} 를 테스트 더블로 갈아끼워
 * claim 맵을 주입한다 — 이 클래스가 고정하는 것은 Manager 왕복이 아니라 <b>그 뒤의 계약</b>
 * (세션 확립 · 실패 응답 · 시도 제한 · 이력 · 로그아웃 분기)이다. Manager 왕복 자체는
 * {@code DhubPasswordAuthClientTest} 가 {@code MockRestServiceServer} 로 이미 고정했고,
 * 실호출 검증은 그쪽 {@code @EnabledIfEnvironmentVariable} 케이스가 맡는다(CI 는 외부망이 없다).
 *
 * <p>🔴 <b>운영 필터체인을 켠 채 돈다</b> — {@code app.auth.security-enabled=true} 가
 * {@code TestSecurityConfig}({@code havingValue="false"})를 끄고 {@code SecurityConfig} 를 켠다.
 * 켜지 않으면 permitAll 체인이 떠서 <b>CSRF 403 도 미인증 401 도 나오지 않고 테스트가 거짓 통과</b>한다.
 * 선례: {@code AuthSecurityIntegrationTest}.
 *
 * <p>🔴 {@code app.auth.idp-logout-enabled=true} 를 <b>일부러 켠다</b> — 기본값(false)으로 두면
 * "비밀번호 세션은 IdP 로그아웃 URL 을 만들지 않는다"가 플래그 때문인지 {@code AUTH_METHOD}
 * 판정 때문인지 구분되지 않아 회귀 가치가 없다. 켠 상태에서도 null 이라야 설계 결정이 증명된다.
 *
 * <p>🔴 {@code max-failures} 를 3 으로 낮춘다 — 운영 기본값(5)에 맞춰 반복하면 실패 이력만 늘어난다.
 *
 * <p>⚠️ 이 레포는 httpclient5 가 없어 {@code TestRestTemplate} 에 쿠키 저장소가 없다 —
 * {@code Set-Cookie} → {@code Cookie} 를 테스트가 직접 이어 붙인다.
 *
 * <p>⚠️ 비밀번호는 <b>더미</b>다. 실계정 비밀번호를 테스트 소스에 두지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.auth.security-enabled=true",
                "app.auth.idp-logout-enabled=true",
                "app.auth.login-attempt.max-failures=3"
        })
class PasswordLoginIntegrationTest {

    /** 🔴 더미다 — 실계정 비밀번호가 아니다. */
    private static final String DUMMY_PASSWORD = "dummy-not-a-real-password";

    private static final String EMAIL = "tsk4134-pwd@example.test";
    private static final String EMAIL_WRONG = "tsk4134-wrong@example.test";
    private static final String EMAIL_BLOCK = "tsk4134-block@example.test";

    /** 주입할 claim 맵의 {@code sub} — {@code TB_USER.cert_id} 가 된다(정리 키). */
    private static final String CERT_ID = "tsk4134-pwd-sub";
    /** {@code preferred_username} = {@code TB_USER.user_id} (20자 이하라 절단·충돌 분기를 타지 않는다). */
    private static final String USER_ID = "tsk4134pwd";

    /** 시도 제한 임계치 — 위 {@code properties} 와 같은 값이어야 한다. */
    private static final int MAX_FAILURES = 3;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 🔴 실 IdP 를 부르지 않기 위한 테스트 더블. {@code @MockBean} 은 Boot 3.4 에서 폐기 예정이라 후속 표준을 쓴다. */
    @MockitoBean private DhubPasswordAuthClient passwordAuthClient;

    /**
     * 🔴 <b>mock 이 아니라 spy 다</b>. 끊는 것은 {@code GET /api/v1/auth/me} 왕복
     * ({@code fetchType}) 하나뿐이고, {@code injectType} 의 "이미 있으면 덮어쓰지 않는다" 가드와
     * claim 맵에 넣는 동작은 <b>실제 코드가 돌아야</b> 배선이 검증된다. 통째로 mock 하면 이 테스트가
     * 자기 스텁을 확인할 뿐이다. HTTP 왕복·URL 조립은 {@code DhubUserProfileClientTest} 가 맡는다.
     */
    @MockitoSpyBean private DhubUserProfileClient profileClient;

    /**
     * 이 테스트가 만든 로그인 이력 행의 경계.
     *
     * <p>{@code RANDOM_PORT} 는 별 스레드·별 커넥션이라 {@code @Transactional} 롤백이 먹지 않고
     * 실제로 커밋된다 — 지우지 않으면 실행할 때마다 쌓인다.
     */
    private long startSn;

    @BeforeEach
    void markStart() {
        startSn = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(lgn_sn), 0) FROM parking.tb_lgn_hstry", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM parking.tb_user WHERE cert_id = ?", CERT_ID);
        // 🔴 이 테스트가 만든 행만 지운다. `OR user_id IS NULL` 로 넓히지 않는다 —
        //    실패 행은 user_id 가 null 이라 테스트가 만들지 않은 실 실패 이력까지 지운다.
        jdbcTemplate.update("DELETE FROM parking.tb_lgn_hstry WHERE lgn_sn > ?", startSn);
    }

    // ---------------------------------------------------------------- helper

    private static String cookieHeader(ResponseEntity<?> res) {
        List<String> setCookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return "";
        }
        return String.join("; ", setCookies.stream().map(c -> c.split(";", 2)[0]).toList());
    }

    private static String xsrfOf(String cookieHeader) {
        for (String c : cookieHeader.split("; ")) {
            if (c.startsWith("XSRF-TOKEN=")) {
                return c.substring("XSRF-TOKEN=".length());
            }
        }
        return null;
    }

    /** GET 한 번으로 XSRF-TOKEN 쿠키를 받아(CsrfCookieFilter) 헤더로 되돌린다. */
    private HttpHeaders csrfHeaders() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        String cookies = cookieHeader(health);
        String xsrf = xsrfOf(cookies);
        assertThat(xsrf).as("CsrfCookieFilter 가 XSRF-TOKEN 을 내려야 한다").isNotBlank();

        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookies);
        h.add("X-XSRF-TOKEN", xsrf);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private ResponseEntity<String> postLogin(String email, String password) {
        return rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(body(email, password), csrfHeaders()), String.class);
    }

    /** 로그인 성공 응답의 쿠키를 다음 요청용 세션 헤더로 옮긴다(세션 ID 가 바뀌었다). */
    private static HttpHeaders sessionOf(ResponseEntity<?> loginRes, HttpHeaders used) {
        String xsrf = used.getFirst("X-XSRF-TOKEN");
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookieHeader(loginRes) + "; XSRF-TOKEN=" + xsrf);
        h.add("X-XSRF-TOKEN", xsrf);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** 주입할 정상 결과. claim 계약은 콜백 경로와 같다(sub · preferred_username · email · name). */
    private static DhubPasswordAuthClient.PasswordLoginResult loginResult() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", CERT_ID);
        claims.put("preferred_username", USER_ID);
        claims.put("email", EMAIL);
        claims.put("name", "비밀번호 로그인 테스트");
        return new DhubPasswordAuthClient.PasswordLoginResult(
                claims, "test-access-token", "test-refresh-token", 900L);
    }

    /** 성공 로그인 1회 — 응답과 그때 쓴 CSRF 헤더를 함께 돌려준다. */
    private ResponseEntity<String> loginOk() {
        given(passwordAuthClient.authenticate(eq(EMAIL), anyString())).willReturn(loginResult());
        ResponseEntity<String> res = postLogin(EMAIL, DUMMY_PASSWORD);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res;
    }

    private List<Map<String, Object>> newHistoryRows() {
        return jdbcTemplate.queryForList(
                "SELECT user_id, lgn_yn, lgn_fail_rsn, sesn_id, cntn_end_dt"
                        + "  FROM parking.tb_lgn_hstry WHERE lgn_sn > ? ORDER BY lgn_sn", startSn);
    }

    // ---------------------------------------------------------------- 인증·CSRF

    @Test
    @DisplayName("1. 🔴 쿠키 없이 GET /api/auth/me → 401 (login 만 열었지 me 를 연 것이 아니다)")
    void unauthenticatedMeIs401() {
        ResponseEntity<String> res = rest.getForEntity("/api/auth/me", String.class);

        // 🔴 200/503 이 나오면 운영 체인이 아니라 permitAll 체인이 뜬 것이다(클래스 properties 확인)
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("2. 🔴 CSRF 헤더 없이 POST /api/auth/login → 403 (permitAll 은 CSRF 면제가 아니다)")
    void loginWithoutCsrfIs403() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookieHeader(health));   // 쿠키만, X-XSRF-TOKEN 없음
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> res = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(body(EMAIL, DUMMY_PASSWORD), h), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // 🔴 컨트롤러에 닿기 전에 끊겨야 한다 — 닿았다면 CSRF 가 꺼진 것이다
        verifyNoInteractions(passwordAuthClient);
    }

    // ---------------------------------------------------------------- 성공 경로

    @Test
    @DisplayName("3. 정상 로그인 → 200 + JSESSIONID + TB_USER 행 (응답 본문에 토큰이 없다 — BFF)")
    void loginSucceedsAndUpsertsUser() {
        ResponseEntity<String> res = loginOk();

        assertThat(cookieHeader(res)).contains("JSESSIONID");
        assertThat(res.getBody()).contains("\"userId\":\"" + USER_ID + "\"")
                .contains("\"userAuthrtCd\":\"ROLE_USER\"")
                .contains("\"userNm\":");
        // 🔴 토큰은 세션에만 남는다 — 본문에 실리면 브라우저로 새어 BFF 전제가 깨진다
        assertThat(res.getBody()).doesNotContain("test-access-token", "test-refresh-token",
                "accessToken", "refreshToken")
                // 🔴 요청 본문을 에코하지 않는다
                .doesNotContain(DUMMY_PASSWORD);

        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_user WHERE cert_id = ?", Integer.class, CERT_ID);
        assertThat(cnt).isEqualTo(1);
    }

    @Test
    @DisplayName("4. 🔴 그 세션으로 /api/auth/me 가 200 이다 (changeSessionId + saveContext 회귀)")
    void sessionIsUsableAfterLogin() {
        HttpHeaders used = csrfHeaders();
        given(passwordAuthClient.authenticate(eq(EMAIL), anyString())).willReturn(loginResult());
        ResponseEntity<String> login = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(body(EMAIL, DUMMY_PASSWORD), used), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(sessionOf(login, used)), Map.class);

        // 🔴 saveContext 를 빠뜨리면 로그인은 200 인데 여기서 401 이 난다
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("userId", USER_ID);
    }

    @Test
    @DisplayName("5. 성공 이력이 남는다 — lgn_yn='Y' · user_id · sesn_id(해시) 채움")
    void successIsRecordedWithSessionHash() {
        loginOk();

        List<Map<String, Object>> rows = newHistoryRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("lgn_yn", "Y").containsEntry("user_id", USER_ID);
        // 🔴 sesn_id 는 changeSessionId() **이후** 세션의 해시다 — 비면 로그아웃이 영원히 매칭되지 않는다
        assertThat((String) rows.get(0).get("sesn_id")).hasSize(16);
    }

    // ---------------------------------------------------------------- 실패 경로

    @Test
    @DisplayName("6. 🔴 자격증명 실패 → 401 한 문장 (사유는 응답이 아니라 이력에만)")
    void wrongCredentialsIs401WithoutReason() {
        given(passwordAuthClient.authenticate(eq(EMAIL_WRONG), anyString())).willThrow(
                new DhubPasswordAuthClient.PasswordLoginException(
                        DhubPasswordAuthClient.PasswordLoginException.Reason.PASSWORD_MISMATCH));

        ResponseEntity<String> res = postLogin(EMAIL_WRONG, DUMMY_PASSWORD);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("\"message\":")
                // 🔴 계정 열거 창구를 만들지 않는다 — 사유·요청 본문을 응답에 싣지 않는다
                .doesNotContain("PASSWORD_MISMATCH", DUMMY_PASSWORD, EMAIL_WRONG);

        List<Map<String, Object>> rows = newHistoryRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("lgn_yn", "N")
                .containsEntry("lgn_fail_rsn", "PASSWORD_MISMATCH")
                // 신원 확인 전 실패라 user_id 는 NULL 이다(정리 범위를 넓히면 안 되는 이유)
                .containsEntry("user_id", null);
    }

    @Test
    @DisplayName("7. 🔴 정지 계정(use_yn='N')도 같은 401 이다 — 이력에만 USER_DISABLED 가 남는다")
    void disabledUserIs401AndRecordedOnlyInHistory() {
        loginOk();   // 행을 만든다
        jdbcTemplate.update("UPDATE parking.tb_user SET use_yn = 'N' WHERE cert_id = ?", CERT_ID);

        ResponseEntity<String> res = postLogin(EMAIL, DUMMY_PASSWORD);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).doesNotContain("USER_DISABLED", "정지");

        assertThat(newHistoryRows()).anySatisfy(row ->
                assertThat(row).containsEntry("lgn_yn", "N").containsEntry("lgn_fail_rsn", "USER_DISABLED"));
    }

    @Test
    @DisplayName("8. 🔴 email/password 가 비면 400 이다 — Manager 를 부르지 않는다 (@Valid)")
    void blankCredentialsIs400() {
        ResponseEntity<String> res = postLogin("", "");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(passwordAuthClient);
        assertThat(newHistoryRows()).isEmpty();
    }

    // ---------------------------------------------------------------- 시도 제한

    @Test
    @DisplayName("9. 🔴 임계치를 넘으면 429 이고, 그 뒤로는 Manager 를 아예 부르지 않는다")
    void tooManyFailuresIsBlocked() {
        given(passwordAuthClient.authenticate(eq(EMAIL_BLOCK), anyString())).willThrow(
                new DhubPasswordAuthClient.PasswordLoginException(
                        DhubPasswordAuthClient.PasswordLoginException.Reason.PASSWORD_MISMATCH));

        for (int i = 0; i < MAX_FAILURES; i++) {
            assertThat(postLogin(EMAIL_BLOCK, DUMMY_PASSWORD).getStatusCode())
                    .as("임계치 전 시도 %d", i + 1).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> blocked = postLogin(EMAIL_BLOCK, DUMMY_PASSWORD);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // 🔴 본문은 401 과 같은 한 문장이다 — 남은 횟수·해제 시각을 알려주지 않는다
        assertThat(blocked.getBody()).contains("\"message\":").doesNotContain("ATTEMPT_BLOCKED");
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();

        // 🔴 차단 뒤에는 upstream 호출이 없다 — 임계치 검사가 authenticate 앞이라는 증거
        verify(passwordAuthClient, times(MAX_FAILURES)).authenticate(eq(EMAIL_BLOCK), anyString());

        List<Map<String, Object>> rows = newHistoryRows();
        // 🔴 차단 전이 시점에만 1회 남는다 — 차단 중 매 요청마다 쓰면 감사 테이블이 공격면이 된다
        assertThat(rows.stream().filter(r -> "ATTEMPT_BLOCKED".equals(r.get("lgn_fail_rsn"))).toList())
                .hasSize(1);
    }

    // ---------------------------------------------------------------- 로그아웃

    @Test
    @DisplayName("10. 🔴 비밀번호 세션 로그아웃 → idpLogoutUrl 이 null 이다 (idp-logout-enabled=true 인데도)")
    void passwordSessionLogoutDoesNotBuildIdpUrl() {
        HttpHeaders used = csrfHeaders();
        given(passwordAuthClient.authenticate(eq(EMAIL), anyString())).willReturn(loginResult());
        ResponseEntity<String> login = rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(body(EMAIL, DUMMY_PASSWORD), used), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders session = sessionOf(login, used);

        ResponseEntity<Map> out = rest.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(session), Map.class);

        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 🔴 AUTH_METHOD 판정이 빠지면 합성 OidcIdToken(=access token)이 id_token_hint 로 실린다
        assertThat(out.getBody()).containsKey("idpLogoutUrl");
        assertThat(out.getBody().get("idpLogoutUrl")).isNull();

        // 세션이 실제로 끊겼다
        ResponseEntity<String> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(session), String.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 접속 종료 보정 — 로그인/로그아웃이 같은 세션 해시를 써야만 채워진다
        assertThat(newHistoryRows()).anySatisfy(row ->
                assertThat(row.get("cntn_end_dt")).as("cntn_end_dt").isNotNull());
    }

    // ---------------------------------------------------------------- 롤 주입 배선

    @Test
    @DisplayName("11. 🔴 /api/v1/auth/me 가 type='manager' 면 TB_USER 가 ROLE_SERVICE_ADMIN 이 된다 (주입 → 매핑)")
    void injectedManagerTypeIsPersistedAsServiceAdmin() {
        // 🔴 실 API 를 부르지 않는다 — 조달 왕복 한 지점만 스텁한다.
        //    🔴 spy 라 given(spy.x()) 형태를 쓰지 않는다 — 스텁을 거는 동안 실제 메서드가 한 번 돈다.
        willReturn(Optional.of("manager")).given(profileClient).fetchType(anyString());
        // 🔴 주입 지점은 운영 경로와 같다 — DhubPasswordAuthClient 가 sub 대조 직후 claim 맵에 넣는다(⑦).
        //    claims 에는 type 이 없어야 injectType 의 containsKey 가드를 지나 조달값이 들어간다.
        given(passwordAuthClient.authenticate(eq(EMAIL), anyString())).willAnswer(invocation -> {
            Map<String, Object> claims = new LinkedHashMap<>(loginResult().claims());
            assertThat(claims).doesNotContainKey("type");
            profileClient.injectType(claims, "test-access-token");
            assertThat(claims).containsEntry("type", "manager");
            return new DhubPasswordAuthClient.PasswordLoginResult(
                    claims, "test-access-token", "test-refresh-token", 900L);
        });

        ResponseEntity<String> res = postLogin(EMAIL, DUMMY_PASSWORD);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"userAuthrtCd\":\"ROLE_SERVICE_ADMIN\"");
        // 🔴 응답만 보면 세션 VO 일 수 있다 — sync() 가 실제로 적재했는지까지 본다(덮어쓰기)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_authrt_cd FROM parking.tb_user WHERE cert_id = ?", String.class, CERT_ID))
                .isEqualTo("ROLE_SERVICE_ADMIN");
    }
}
