package jeju.datahub.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import jeju.datahub.auth.service.AuthProperties;
import jeju.datahub.auth.service.impl.DhubPasswordAuthClient.PasswordLoginException;
import jeju.datahub.auth.service.impl.DhubPasswordAuthClient.PasswordLoginException.Reason;
import jeju.datahub.auth.service.impl.DhubPasswordAuthClient.PasswordLoginResult;

/**
 * {@link DhubPasswordAuthClient} 단위 테스트 (TSK-4134 Step 1).
 *
 * <p>업스트림 없이 {@link MockRestServiceServer}(spring-test 내장 — <b>새 의존성 없음</b>)로
 * 정상·실패 경로를 검증한다. 패키지가 {@code ...auth.service.impl} 인 이유는 목 주입 생성자
 * {@code DhubPasswordAuthClient(RestClient, ClientRegistrationRepository, AuthProperties,
 * DhubUserProfileClient)} 가
 * <b>package-private</b> 이기 때문이다(선례: {@code AgentApiClientTest}).
 *
 * <h2>🔴 목 조립 순서</h2>
 * <pre>
 * RestClient.Builder b = RestClient.builder();
 * MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();   // ① 목 팩토리를 심는다
 * new DhubPasswordAuthClient(b.build(), repo, props, profileClient);        // ② 심은 뒤 build()
 * </pre>
 * 순서를 뒤집거나 운영 생성자({@code requestFactory(...)} 를 부른다)를 쓰면 목이 덮여 걸리지 않는다.
 *
 * <h2>🔴 access token 을 서명 없이 만든다</h2>
 * 이 경로는 <b>서명을 검증하지 않는다</b>(D2 — 검증은 {@code /userinfo} 가 진다). 그래서 테스트도
 * 키를 만들지 않고 {@link PlainJWT} 로 payload 만 있는 토큰을 만든다 — 클라이언트가 읽는 것은
 * {@code exp}·{@code iat}·{@code sub} 뿐이다.
 *
 * <h2>🔴 실호출 테스트는 자격증명이 있을 때만 돈다</h2>
 * {@code @EnabledIfEnvironmentVariable(named = "DHUB_USER_EMAIL")} — 키 없는 CI 에서 빌드를 깨지
 * 않으면서 로컬에서는 실호출 검증이 자동으로 켜진다({@code rule_batch-project.md} 관례).
 */
class DhubPasswordAuthClientTest {

    private static final String ISSUER = "https://manager.example.test:10443";
    private static final String LOGIN_URL = ISSUER + DhubPasswordAuthClient.LOGIN_PATH;
    private static final String USERINFO_URL = ISSUER + "/userinfo";
    /** 🔴 TSK-4163 Step 5 ⑦ 의 조달 대상 — {@code /userinfo} 가 아니라 Manager 자체 API 다(D8). */
    private static final String ME_URL = ISSUER + "/api/v1/auth/me";

    private static final String SUB = "user.6f8f1f4c-1a2b-4c3d-9e0f-112233445566";
    private static final String EMAIL = "tester@example.test";
    private static final String USERNAME = "tester";

    /** 🔴 실측 수명(§2.2)과 같은 값을 테스트에서 만든다 — 운영 코드에는 이 상수가 없다(exp-iat 유래). */
    private static final long LIFETIME_SEC = 900L;

    /** Manager 가 실패 시 주는 본문 — 🔴 이 문자열이 우리 예외 메시지에 새지 않는지 단언한다(D6). */
    private static final String UPSTREAM_401_BODY = "{\"detail\":\"Invalid email or password\"}";

    private record Fixture(DhubPasswordAuthClient client, MockRestServiceServer server) {}

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthProperties props = new AuthProperties();     // registrationId=dhub2, usernameClaim=preferred_username
        // 🔴 TSK-4163 Step 5 — 이 테스트의 관심은 로그인 계약이다. type 조달은 끄는 스위치
        //    (profile-endpoint 빈 값)로 꺼 둔다 — 켜면 목 서버에 없는 요청이 하나 더 나간다.
        props.setProfileEndpoint("");
        ClientRegistrationRepository repo = repo(registration(ISSUER));
        DhubUserProfileClient profileClient =
                new DhubUserProfileClient(builder.build(), repo, props);
        return new Fixture(
                new DhubPasswordAuthClient(builder.build(), repo, props, profileClient), server);
    }

    private static ClientRegistrationRepository repo(ClientRegistration reg) {
        return registrationId -> reg.getRegistrationId().equals(registrationId) ? reg : null;
    }

    private static ClientRegistration registration(String issuer) {
        return ClientRegistration.withRegistrationId("dhub2")
                .clientId("oidc-test-innopam")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:5173")
                .authorizationUri(issuer + "/authorize")
                .tokenUri(issuer + "/token")
                .userInfoUri(issuer + "/userinfo")
                .issuerUri(issuer)
                .scope("openid")
                .userNameAttributeName("preferred_username")
                .build();
    }

    /** 서명 없는 JWT — 읽히는 것은 {@code sub}·{@code iat}·{@code exp} 뿐이다. */
    private static String jwt(String sub, long lifetimeSec) {
        Instant iat = Instant.now();
        return new PlainJWT(new JWTClaimsSet.Builder()
                .subject(sub)
                .issueTime(Date.from(iat))
                .expirationTime(Date.from(iat.plusSeconds(lifetimeSec)))
                .build()).serialize();
    }

    private static String loginBody(String accessToken) {
        return "{\"access_token\":\"" + accessToken + "\",\"token_type\":\"Bearer\"}";
    }

    private static String userinfoBody(String sub) {
        return "{\"sub\":\"" + sub + "\",\"name\":\"테스터\",\"email\":\"" + EMAIL
                + "\",\"preferred_username\":\"" + USERNAME + "\",\"email_verified\":true,\"type\":\"\"}";
    }

    /**
     * 🔴 {@code type} 이 <b>없는</b> {@code /userinfo} 본문 — 배선 회귀 케이스 전용이다.
     *
     * <p>{@link #userinfoBody(String)} 는 {@code "type":""} 를 담고 있어 그대로 쓰면
     * {@code injectType} 의 {@code containsKey} 가드(=claim 이 정본)에 먼저 걸려
     * <b>조달이 일어나지 않는다</b>. 기존 케이스들의 기대값을 건드리지 않으려고 본문을 따로 둔다.
     */
    private static String userinfoBodyWithoutType(String sub) {
        return "{\"sub\":\"" + sub + "\",\"name\":\"테스터\",\"email\":\"" + EMAIL
                + "\",\"preferred_username\":\"" + USERNAME + "\",\"email_verified\":true}";
    }

    /** §2.4 실측 형태 그대로 — 값 뒤에 속성이 붙는다. */
    private static String setCookie(String value) {
        return "refresh_token=" + value + "; HttpOnly; Max-Age=604800; Path=/; SameSite=none; Secure";
    }

    private static void expectLogin(MockRestServiceServer server, String accessToken, String refreshCookie) {
        server.expect(requestTo(LOGIN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    var response = withSuccess(loginBody(accessToken), MediaType.APPLICATION_JSON)
                            .createResponse(request);
                    if (refreshCookie != null) {
                        response.getHeaders().add(HttpHeaders.SET_COOKIE, refreshCookie);
                    }
                    return response;
                });
    }

    private static void expectUserinfo(MockRestServiceServer server, String sub) {
        server.expect(requestTo(USERINFO_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, org.hamcrest.Matchers.startsWith("Bearer ")))
                .andRespond(withSuccess(userinfoBody(sub), MediaType.APPLICATION_JSON));
    }

    // ------------------------------------------------------------------ ① 정상

    @Test
    @DisplayName("정상 로그인 — claim 맵에 sub·email·preferred_username 이 담기고 토큰 2종이 채워진다")
    void authenticate_success() {
        Fixture f = fixture();
        String accessToken = jwt(SUB, LIFETIME_SEC);
        expectLogin(f.server(), accessToken, setCookie("refresh-value-1"));
        expectUserinfo(f.server(), SUB);

        PasswordLoginResult result = f.client().authenticate(EMAIL, "pw");

        assertThat(result.claims()).containsKeys("sub", "email", "preferred_username");
        assertThat(result.claims().get("sub")).isEqualTo(SUB);
        assertThat(result.claims().get("email")).isEqualTo(EMAIL);
        assertThat(result.claims().get("preferred_username")).isEqualTo(USERNAME);
        assertThat(result.accessToken()).isEqualTo(accessToken);
        // 🔴 Set-Cookie 에서 뽑았다 — 본문에는 없다(§2.4)
        assertThat(result.refreshToken()).isEqualTo("refresh-value-1");
        // 🔴 하드코딩이 아니라 exp - iat 유래
        assertThat(result.expiresInSec()).isEqualTo(LIFETIME_SEC);
        f.server().verify();
    }

    @Test
    @DisplayName("🔴 toString() 이 토큰을 마스킹한다 (D6)")
    void result_toString_masksTokens() {
        Fixture f = fixture();
        expectLogin(f.server(), jwt(SUB, LIFETIME_SEC), setCookie("refresh-value-2"));
        expectUserinfo(f.server(), SUB);

        String text = f.client().authenticate(EMAIL, "pw").toString();

        assertThat(text).doesNotContain("refresh-value-2").contains("****");
    }

    /**
     * 🔴 <b>배선 회귀</b> — {@code DhubPasswordAuthClient} 의 ⑦
     * ({@code profileClient.injectType(claims, accessToken)}) 이 <b>실제로 도는지</b>를 지킨다.
     *
     * <p>{@code PasswordLoginIntegrationTest} 는 {@code passwordAuthClient} 를 통째로 목으로
     * 바꾸므로 그 한 줄이 지워져도 초록불이다. 그래서 이 케이스는 🔴 {@link DhubUserProfileClient}
     * 를 <b>목/스파이로 대체하지 않는다</b> — 실제 인스턴스를 쓰되 그 안의 {@link RestClient} 만
     * {@link MockRestServiceServer} 로 덮어, {@code /api/v1/auth/me} 가 <b>정말 나갔는지</b>를
     * {@code verify()} 로 단언한다.
     *
     * <p>🔴 목 서버가 <b>둘</b>인 이유: 두 클라이언트는 서로 다른 {@link RestClient} 를 받으므로
     * 빌더도 따로 둔다. 한 빌더를 공유하면 기대 순서가 두 클라이언트에 걸쳐 한 줄로 엮여,
     * "누가 무엇을 불렀는지" 가 흐려진다.
     */
    @Test
    @DisplayName("🔴 authenticate() 가 실제로 /api/v1/auth/me 를 호출해 type 을 claims 에 넣는다 (Step 5 ⑦ 배선)")
    void authenticate_injectsTypeFromProfileEndpoint() {
        RestClient.Builder loginBuilder = RestClient.builder();
        MockRestServiceServer loginServer = MockRestServiceServer.bindTo(loginBuilder).build();
        RestClient.Builder profileBuilder = RestClient.builder();
        MockRestServiceServer profileServer = MockRestServiceServer.bindTo(profileBuilder).build();

        AuthProperties props = new AuthProperties();
        // 🔴 이 케이스에서만 조달을 켠다 — 공용 fixture() 의 빈 값("")은 그대로 둔다.
        props.setProfileEndpoint("/api/v1/auth/me");
        ClientRegistrationRepository repo = repo(registration(ISSUER));
        DhubUserProfileClient profileClient = new DhubUserProfileClient(profileBuilder.build(), repo, props);
        DhubPasswordAuthClient client =
                new DhubPasswordAuthClient(loginBuilder.build(), repo, props, profileClient);

        String accessToken = jwt(SUB, LIFETIME_SEC);
        expectLogin(loginServer, accessToken, setCookie("refresh-value-7"));
        // 🔴 type 이 없는 userinfo — 있으면 containsKey 가드에 걸려 주입이 검증되지 않는다.
        loginServer.expect(requestTo(USERINFO_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(userinfoBodyWithoutType(SUB), MediaType.APPLICATION_JSON));
        // 🔴 ⑦ 이 사라지면 이 기대가 미충족으로 남아 profileServer.verify() 가 실패한다.
        profileServer.expect(requestTo(ME_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andRespond(withSuccess("{\"id\":\"user.6f8f1f4c\",\"category\":\"user\","
                        + "\"type\":\"manager\"}", MediaType.APPLICATION_JSON));

        PasswordLoginResult result = client.authenticate(EMAIL, "pw");

        // ② 조달값이 claim 맵에 담겼다 — sync(claims) 는 이 맵만 본다.
        assertThat(result.claims()).containsEntry("type", "manager");
        // ① 두 호출이 모두 실제로 나갔다
        loginServer.verify();
        profileServer.verify();
    }

    // ------------------------------------------------------------------ ② 실패

    @Test
    @DisplayName("Manager 401 → PASSWORD_MISMATCH 로 변환되고 응답 본문이 예외 메시지에 없다 (D6)")
    void login401_isMappedWithoutBody() {
        Fixture f = fixture();
        f.server().expect(requestTo(LOGIN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON).body(UPSTREAM_401_BODY));

        // 🔴 인자 순서는 (타입, 호출) 이다 — 반대 순서 오버로드는 AssertJ 3.24 에서 deprecated 다.
        PasswordLoginException e = catchThrowableOfType(
                PasswordLoginException.class, () -> f.client().authenticate(EMAIL, "wrong-pw"));

        assertThat(e).isNotNull();
        assertThat(e.getReason()).isEqualTo(Reason.PASSWORD_MISMATCH);
        assertThat(e.getMessage()).isEqualTo("PASSWORD_MISMATCH");
        assertThat(e.getMessage()).doesNotContain("Invalid email or password").doesNotContain("wrong-pw");
        // 🔴 401 경로는 cause 도 달지 않는다 — cause 메시지에 본문이 실린다.
        assertThat(e.getCause()).isNull();
    }

    @Test
    @DisplayName("🔴 Set-Cookie 가 없으면 레코드 조립에서 실패한다 (Step 2.5 갱신이 죽는 것을 막는다)")
    void missingRefreshCookie_fails() {
        Fixture f = fixture();
        expectLogin(f.server(), jwt(SUB, LIFETIME_SEC), null);
        expectUserinfo(f.server(), SUB);

        assertThatThrownBy(() -> f.client().authenticate(EMAIL, "pw"))
                .isInstanceOf(PasswordLoginException.class)
                .hasMessage(Reason.TOKEN_EXCHANGE_FAILED.name());
    }

    @Test
    @DisplayName("🔴 exp/iat 가 없어 수명을 못 구하면 실패한다 (900 하드코딩이 아님을 보장)")
    void missingExpiry_fails() {
        Fixture f = fixture();
        String noExpiry = new PlainJWT(new JWTClaimsSet.Builder().subject(SUB).build()).serialize();
        expectLogin(f.server(), noExpiry, setCookie("refresh-value-3"));
        expectUserinfo(f.server(), SUB);

        assertThatThrownBy(() -> f.client().authenticate(EMAIL, "pw"))
                .isInstanceOf(PasswordLoginException.class)
                .hasMessage(Reason.TOKEN_EXCHANGE_FAILED.name());
    }

    @Test
    @DisplayName("🔴 /userinfo 의 sub 가 토큰 payload 의 sub 와 다르면 SUB_MISMATCH (토큰 치환 방어)")
    void subMismatch_fails() {
        Fixture f = fixture();
        expectLogin(f.server(), jwt(SUB, LIFETIME_SEC), setCookie("refresh-value-4"));
        expectUserinfo(f.server(), "user.99999999-0000-0000-0000-000000000000");

        assertThatThrownBy(() -> f.client().authenticate(EMAIL, "pw"))
                .isInstanceOf(PasswordLoginException.class)
                .hasMessage(Reason.SUB_MISMATCH.name());
    }

    @Test
    @DisplayName("🔴 preferred_username 이 없으면 USERINFO_EMPTY — 조용한 sha256 폴백을 두지 않는다")
    void missingPreferredUsername_fails() {
        Fixture f = fixture();
        expectLogin(f.server(), jwt(SUB, LIFETIME_SEC), setCookie("refresh-value-5"));
        f.server().expect(requestTo(USERINFO_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"sub\":\"" + SUB + "\",\"email\":\"" + EMAIL + "\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> f.client().authenticate(EMAIL, "pw"))
                .isInstanceOf(PasswordLoginException.class)
                .hasMessage(Reason.USERINFO_EMPTY.name());
    }

    @Test
    @DisplayName("🔴 /userinfo 가 401 이면 USERINFO_EMPTY — 검증 지점을 건너뛰지 않는다 (D2)")
    void userinfoRejected_fails() {
        Fixture f = fixture();
        expectLogin(f.server(), jwt(SUB, LIFETIME_SEC), setCookie("refresh-value-6"));
        f.server().expect(requestTo(USERINFO_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> f.client().authenticate(EMAIL, "pw"))
                .isInstanceOf(PasswordLoginException.class)
                .hasMessage(Reason.USERINFO_EMPTY.name());
    }

    // ------------------------------------------------------------------ ③ 실호출 (자격증명이 있을 때만)

    /**
     * 실 Manager 실호출 — 🔴 {@code DHUB_USER_EMAIL} 이 있을 때만 돈다(키 없는 CI 를 깨지 않는다).
     *
     * <p>단언은 계약(§2.1~2.4)이 그대로인지다: claim 3종 · {@code Set-Cookie} 의 갱신값 ·
     * {@code exp - iat} 수명. 🔴 값은 어떤 형태로도 출력하지 않는다(D6).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "DHUB_USER_EMAIL", matches = ".+")
    @DisplayName("[실호출] 실계정으로 로그인하면 claim·갱신값·수명이 모두 채워진다")
    void authenticate_realCall() {
        String email = System.getenv("DHUB_USER_EMAIL");
        String password = System.getenv("DHUB_USER_PW");
        Assumptions.assumeTrue(password != null && !password.isBlank(), "DHUB_USER_PW 가 없어 건너뛴다");

        String issuer = System.getenv().getOrDefault("OIDC_ISSUER_URI", "https://manager.project.dtonic.io:10443");
        ClientRegistration reg = registration(issuer);
        AuthProperties props = new AuthProperties();
        ClientRegistrationRepository repoReal = repo(reg);
        DhubPasswordAuthClient client = new DhubPasswordAuthClient(
                RestClient.builder().build(), repoReal, props,
                new DhubUserProfileClient(RestClient.builder().build(), repoReal, props));

        PasswordLoginResult result = client.authenticate(email, password);

        assertThat(result.claims()).containsKeys("sub", "email", "preferred_username");
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();       // 🔴 Set-Cookie 에서 뽑힌다
        assertThat(result.expiresInSec()).isPositive();       // 🔴 exp - iat
    }
}
