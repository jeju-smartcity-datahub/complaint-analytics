package jeju.datahub.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import jeju.datahub.auth.service.AuthProperties;

/**
 * {@link OidcTokenRevoker} 단위 테스트 (TSK-4349 Step 2).
 *
 * <p>🔴 핵심 계약은 "예외를 밖으로 던지지 않는다"(D5) — 로그아웃이 IdP 상태와 무관하게 끝나야 한다.
 */
class OidcTokenRevokerTest {

    private static final String ISSUER = "https://manager.example.test:10443";
    private static final String DISCOVERED = ISSUER + "/revoke";

    private record Fixture(OidcTokenRevoker revoker, MockRestServiceServer server, AuthProperties props) {}

    private static Fixture fixture(Map<String, Object> metadata) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthProperties props = new AuthProperties();
        ClientRegistration reg = ClientRegistration.withRegistrationId("dhub2")
                .clientId("oidc test+id")              // form-encode 확인용 공백·+
                .clientSecret("s3cret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:5173")
                .authorizationUri(ISSUER + "/authorize")
                .tokenUri(ISSUER + "/token")
                .providerConfigurationMetadata(metadata)
                .build();
        ClientRegistrationRepository repo = id -> reg.getRegistrationId().equals(id) ? reg : null;
        return new Fixture(new OidcTokenRevoker(builder.build(), repo, props), server, props);
    }

    private static Fixture fixture() {
        return fixture(Map.of("revocation_endpoint", DISCOVERED));
    }

    @Test
    @DisplayName("discovery 엔드포인트로 form POST + Basic(form-encode) 를 보낸다 → 200 이면 ok")
    void postsFormWithBasicAuth() {
        Fixture f = fixture();
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString("oidc+test%2Bid:s3cret".getBytes(StandardCharsets.UTF_8));
        f.server().expect(requestTo(DISCOVERED))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.startsWith(
                        MediaType.APPLICATION_FORM_URLENCODED_VALUE)))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(content().formDataContains(Map.of("token", "tok-1", "token_type_hint", "refresh_token")))
                .andRespond(withSuccess());

        OidcTokenRevoker.Result r = f.revoker().revoke("tok-1", OidcTokenRevoker.HINT_REFRESH);

        f.server().verify();
        assertThat(r.outcome()).isEqualTo("ok");
        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("설정값이 있으면 discovery 보다 우선한다")
    void configuredEndpointWins() {
        Fixture f = fixture();
        f.props().setRevocationEndpoint("https://idp.example.test/custom-revoke");
        f.server().expect(requestTo("https://idp.example.test/custom-revoke")).andRespond(withSuccess());

        assertThat(f.revoker().revoke("tok", OidcTokenRevoker.HINT_ACCESS).outcome()).isEqualTo("ok");
        f.server().verify();
    }

    @Test
    @DisplayName("4xx·5xx 는 예외 없이 rejected + status")
    void errorStatusesDoNotThrow() {
        Fixture f = fixture();
        f.server().expect(requestTo(DISCOVERED)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"unsupported_token_type\"}"));
        f.server().expect(requestTo(DISCOVERED)).andRespond(withServerError());

        OidcTokenRevoker.Result r400 = f.revoker().revoke("a", OidcTokenRevoker.HINT_ACCESS);
        OidcTokenRevoker.Result r500 = f.revoker().revoke("b", OidcTokenRevoker.HINT_REFRESH);

        assertThat(r400.outcome()).isEqualTo("rejected");
        assertThat(r400.status()).isEqualTo(400);
        assertThat(r500.status()).isEqualTo(500);
    }

    @Test
    @DisplayName("전송 오류(타임아웃 등)도 예외 없이 error")
    void transportErrorDoesNotThrow() {
        Fixture f = fixture();
        f.server().expect(requestTo(DISCOVERED)).andRespond(req -> { throw new IOException("timeout"); });

        assertThat(f.revoker().revoke("tok", OidcTokenRevoker.HINT_REFRESH).outcome()).isEqualTo("error");
    }

    @Test
    @DisplayName("엔드포인트가 없거나 토큰이 비면 HTTP 호출 0회로 skip")
    void skipsWithoutCalling() {
        Fixture noEndpoint = fixture(Map.of());
        assertThat(noEndpoint.revoker().revoke("tok", OidcTokenRevoker.HINT_REFRESH).outcome())
                .isEqualTo("skip:NO_ENDPOINT");
        noEndpoint.server().verify();      // 기대 0건 — 요청이 나갔으면 실패

        Fixture f = fixture();
        assertThat(f.revoker().revoke(null, OidcTokenRevoker.HINT_ACCESS).outcome()).isEqualTo("skip:NO_TOKEN");
        assertThat(f.revoker().revoke("", OidcTokenRevoker.HINT_ACCESS).outcome()).isEqualTo("skip:NO_TOKEN");
        f.server().verify();
    }

    @Test
    @DisplayName("진단 스위치 ON 이어도 동작은 같다(로그만 늘어난다)")
    void diagOnSameBehavior() {
        Fixture f = fixture();
        f.props().setDiagLogEnabled(true);
        f.server().expect(requestTo(DISCOVERED)).andRespond(withSuccess());

        assertThat(f.revoker().revoke("tok", OidcTokenRevoker.HINT_REFRESH).outcome()).isEqualTo("ok");
    }
}
