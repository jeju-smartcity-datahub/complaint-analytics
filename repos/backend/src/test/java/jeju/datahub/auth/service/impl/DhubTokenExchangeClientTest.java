package jeju.datahub.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import jeju.datahub.auth.service.AuthProperties;

/**
 * {@link DhubTokenExchangeClient} 단위 테스트.
 *
 * <p>{@link MockRestServiceServer} 로 교환·로그인 refresh 의 <b>요청 모양</b>을 고정한다.
 *
 * <h2>🔴 목 조립 순서</h2>
 * <pre>
 * RestClient.Builder b = RestClient.builder();
 * MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();   // ① 목 팩토리를 심는다
 * new DhubTokenExchangeClient(b, repo, props);                              // ② builder 째 넘긴다
 * </pre>
 * 목 생성자가 운영과 같은 {@code oauth2(builder)}(컨버터·에러 핸들러)를 거치므로 2번(응답 파싱)·
 * 3번(4xx → 예외)이 운영 구성을 그대로 검증한다.
 */
class DhubTokenExchangeClientTest {

    private static final String ISSUER = "https://manager.example.test:10443";
    private static final String TOKEN_URL = ISSUER + "/token";

    private static final String CLIENT_ID = "oidc-test-innopam";
    /** 🔴 특수문자를 넣는다 — Basic 헤더가 RFC 6749 2.3.1 대로 urlencode 후 base64 되는지 본다. */
    private static final String CLIENT_SECRET = "s3cr:et/+=&";

    private static final String LOGIN_ACCESS = "login-access-token-value";
    private static final String AGENT_ACCESS = "agent-access-token-value";
    private static final String LOGIN_REFRESH = "login-refresh-token-value";

    private record Fixture(DhubTokenExchangeClient client, MockRestServiceServer server) {}

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthProperties props = new AuthProperties();     // registrationId=dhub2
        ClientRegistration reg = registration();
        ClientRegistrationRepository repo = id -> reg.getRegistrationId().equals(id) ? reg : null;
        return new Fixture(new DhubTokenExchangeClient(builder, repo, props), server);
    }

    /** 운영과 같은 모양 — grant type 은 authorization_code, scope 가 있다(교환에서 빠져야 한다). */
    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("dhub2")
                .clientId(CLIENT_ID)
                .clientSecret(CLIENT_SECRET)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:5173")
                .authorizationUri(ISSUER + "/authorize")
                .tokenUri(TOKEN_URL)
                .userInfoUri(ISSUER + "/userinfo")
                .issuerUri(ISSUER)
                .scope("openid", "profile", "email")
                .userNameAttributeName("preferred_username")
                .build();
    }

    private static String expectedBasic() {
        String raw = URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String exchangeBody() {
        return "{\"access_token\":\"" + AGENT_ACCESS + "\",\"token_type\":\"Bearer\",\"expires_in\":600,"
                + "\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}";
    }

    @Test
    @DisplayName("1. 교환 요청 — POST tokenUri · form · Basic(urlencode) · 파라미터 정확히 4개(scope·requested_token_type 없음)")
    void exchangeRequestShape() {
        Fixture f = fixture();
        MultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        expected.add("subject_token", LOGIN_ACCESS);
        expected.add("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        expected.add("audience", DhubTokenExchangeClient.AUDIENCE_AGENT);

        f.server().expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expectedBasic()))
                // 🔴 formData 는 전체 일치다 — scope·requested_token_type 이 섞이면 실패한다(보정 회귀 고정)
                .andExpect(content().formData(expected))
                .andRespond(withSuccess(exchangeBody(), MediaType.APPLICATION_JSON));

        f.client().exchange(LOGIN_ACCESS, DhubTokenExchangeClient.AUDIENCE_AGENT);
        f.server().verify();
    }

    @Test
    @DisplayName("2. 교환 응답 — access 값 · expiresAt ≈ now+600s · refresh 없음")
    void exchangeResponseParsed() {
        Fixture f = fixture();
        f.server().expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess(exchangeBody(), MediaType.APPLICATION_JSON));

        Instant before = Instant.now();
        OAuth2AccessTokenResponse res =
                f.client().exchange(LOGIN_ACCESS, DhubTokenExchangeClient.AUDIENCE_MANAGER);

        assertThat(res.getAccessToken().getTokenValue()).isEqualTo(AGENT_ACCESS);
        assertThat(res.getAccessToken().getExpiresAt())
                .isBetween(before.plusSeconds(595), Instant.now().plusSeconds(605));
        assertThat(res.getRefreshToken()).isNull();
        f.server().verify();
    }

    @Test
    @DisplayName("3. 교환 400 → DhubTokenExchangeException(error 코드만 — 토큰·본문 없음)")
    void exchange400ThrowsWithoutLeaking() {
        Fixture f = fixture();
        String body = "{\"error\":\"invalid_grant\",\"error_description\":\"subject " + LOGIN_ACCESS + " rejected\"}";
        f.server().expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(body));

        DhubTokenExchangeException ex = catchThrowableOfType(DhubTokenExchangeException.class,
                () -> f.client().exchange(LOGIN_ACCESS, DhubTokenExchangeClient.AUDIENCE_AGENT));

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo("invalid_grant");
        assertThat(ex.getMessage())
                .isEqualTo("invalid_grant")
                .doesNotContain(LOGIN_ACCESS)
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain("rejected");
        f.server().verify();
    }

    @Test
    @DisplayName("4. refreshLogin 요청 — grant_type=refresh_token&refresh_token=… + Basic (scope·자리채움 access 없음)")
    void refreshLoginRequestShape() {
        Fixture f = fixture();
        MultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("grant_type", "refresh_token");
        expected.add("refresh_token", LOGIN_REFRESH);

        f.server().expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expectedBasic()))
                .andExpect(content().formData(expected))       // 전체 일치 — scope·"unused" 가 실리면 실패
                .andExpect(content().string(not(containsString("unused"))))
                .andRespond(withSuccess("{\"access_token\":\"new-login\",\"token_type\":\"Bearer\",\"expires_in\":900}",
                        MediaType.APPLICATION_JSON));

        f.client().refreshLogin(LOGIN_REFRESH);
        f.server().verify();
    }

    @Test
    @DisplayName("5. refreshLogin 응답 — 회전된 refresh 값이 getRefreshToken() 에 실린다")
    void refreshLoginRotatedRefresh() {
        Fixture f = fixture();
        f.server().expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"new-login\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":900,\"refresh_token\":\"rotated-refresh\"}", MediaType.APPLICATION_JSON));

        OAuth2AccessTokenResponse res = f.client().refreshLogin(LOGIN_REFRESH);

        assertThat(res.getAccessToken().getTokenValue()).isEqualTo("new-login");
        assertThat(res.getRefreshToken()).isNotNull();
        assertThat(res.getRefreshToken().getTokenValue()).isEqualTo("rotated-refresh");
        f.server().verify();
    }

    @Test
    @DisplayName("6. 타임아웃·5xx → 같은 예외 타입(호출부가 하나로 잡는다)")
    void timeoutAnd5xxSameExceptionType() {
        Fixture f = fixture();
        f.server().expect(requestTo(TOKEN_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        f.server().expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        DhubTokenExchangeException timeout = catchThrowableOfType(DhubTokenExchangeException.class,
                () -> f.client().exchange(LOGIN_ACCESS, DhubTokenExchangeClient.AUDIENCE_AGENT));
        DhubTokenExchangeException serverError = catchThrowableOfType(DhubTokenExchangeException.class,
                () -> f.client().refreshLogin(LOGIN_REFRESH));

        assertThat(timeout).isNotNull();
        assertThat(serverError).isNotNull();
        assertThat(timeout.getMessage()).doesNotContain(LOGIN_ACCESS);
        assertThat(serverError.getMessage()).doesNotContain(LOGIN_REFRESH);
        f.server().verify();
    }
}
