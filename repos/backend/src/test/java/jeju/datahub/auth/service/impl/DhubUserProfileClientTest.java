package jeju.datahub.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * {@link DhubUserProfileClient} 단위 테스트.
 *
 * <p>업스트림 없이 {@link MockRestServiceServer}(spring-test 내장 — <b>새 의존성 없음</b>)로
 * 조달 경로를 검증한다. 패키지가 {@code ...auth.service.impl} 인 이유는 목 주입 생성자
 * {@code DhubUserProfileClient(RestClient, ClientRegistrationRepository, AuthProperties)} 가
 * <b>package-private</b> 이기 때문이다(선례: {@link DhubPasswordAuthClientTest}).
 *
 * <h2>🔴 이 테스트가 지키는 계약</h2>
 * <ul>
 *   <li>200 + {@code type} → 그 값</li>
 *   <li><b>401 → empty (예외 없음)</b> — OIDC 브로커 토큰의 <b>정상 경로</b>다.
 *       여기서 던지면 OIDC 로그인이 통째로 막힌다.</li>
 *   <li>200 이지만 {@code type} 부재 → empty (→ 기존 롤 유지)</li>
 *   <li>{@code profile-endpoint} 가 빈 값 → <b>호출 자체가 없다</b>(끄는 스위치)</li>
 *   <li>issuer 끝의 {@code /} 와 경로 앞의 {@code /} 가 겹쳐도 <b>슬래시는 하나</b>다</li>
 * </ul>
 */
class DhubUserProfileClientTest {

    private static final String ISSUER = "https://manager.example.test:10443";
    private static final String ME_URL = ISSUER + "/api/v1/auth/me";
    private static final String TOKEN = "dummy-access-token";

    /** 🔴 응답 전문에는 개인정보가 섞인다 — 실측 모양 그대로 두되 단언은 {@code type} 하나다. */
    private static final String ME_BODY = """
            {"id":"user.6f8f1f4c","name":"홍길동","email":"tester@example.test",
             "category":"user","type":"manager","groups":[],"provider":"local"}
            """;

    private record Fixture(DhubUserProfileClient client, MockRestServiceServer server) {}

    private static Fixture fixture(AuthProperties props) {
        return fixture(props, ISSUER);
    }

    private static Fixture fixture(AuthProperties props, String issuer) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new DhubUserProfileClient(builder.build(), repo(registration(issuer)), props), server);
    }

    private static AuthProperties props() {
        return new AuthProperties();   // profileEndpoint=/api/v1/auth/me, roleClaim=type
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
                .authorizationUri(ISSUER + "/authorize")
                .tokenUri(ISSUER + "/token")
                .userInfoUri(ISSUER + "/userinfo")
                .issuerUri(issuer)
                .scope("openid")
                .userNameAttributeName("preferred_username")
                .build();
    }

    @Test
    @DisplayName("200 + type 이면 그 값을 돌려준다 — URL 은 issuer 에서 조립하고 Bearer 로 보낸다")
    void fetchType_success() {
        Fixture f = fixture(props());
        f.server().expect(requestTo(ME_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(ME_BODY, MediaType.APPLICATION_JSON));

        assertThat(f.client().fetchType(TOKEN)).isEqualTo(Optional.of("manager"));
        f.server().verify();
    }

    @Test
    @DisplayName("🔴 issuer 끝의 '/' 와 경로 앞의 '/' 가 겹쳐도 슬래시는 하나다 — //api/... 가 되지 않는다")
    void fetchType_normalizesSlashesBetweenIssuerAndEndpoint() {
        AuthProperties props = props();
        props.setProfileEndpoint("api/v1/auth/me");          // 앞의 '/' 를 빠뜨린 설정
        Fixture f = fixture(props, ISSUER + "/");            // 끝에 '/' 가 붙은 issuer
        f.server().expect(requestTo(ME_URL))                 // 🔴 정확히 ME_URL 이라야 한다
                .andRespond(withSuccess(ME_BODY, MediaType.APPLICATION_JSON));

        assertThat(f.client().fetchType(TOKEN)).isEqualTo(Optional.of("manager"));
        f.server().verify();
    }

    @Test
    @DisplayName("🔴 401 이면 예외 없이 empty 다 — OIDC 브로커 토큰의 정상 경로")
    void fetchType_unauthorized_isSwallowed() {
        Fixture f = fixture(props());
        f.server().expect(requestTo(ME_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Invalid token\"}"));

        // 🔴 던지지 않는다 — 던지면 OIDC 로그인이 통째로 막힌다.
        assertThat(f.client().fetchType(TOKEN)).isEmpty();
        f.server().verify();
    }

    @Test
    @DisplayName("200 이지만 type 필드가 없으면 empty 다 — 기존 롤 유지")
    void fetchType_missingField() {
        Fixture f = fixture(props());
        f.server().expect(requestTo(ME_URL))
                .andRespond(withSuccess("{\"id\":\"user.6f8f1f4c\",\"category\":\"user\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(f.client().fetchType(TOKEN)).isEmpty();
        f.server().verify();
    }

    @Test
    @DisplayName("🔴 5xx·본문 오류도 삼킨다 — 로그인은 계속된다")
    void fetchType_serverError_isSwallowed() {
        Fixture f = fixture(props());
        f.server().expect(requestTo(ME_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(f.client().fetchType(TOKEN)).isEmpty();
        f.server().verify();
    }

    @Test
    @DisplayName("profile-endpoint 가 빈 값이면 호출 자체를 건너뛴다 (끄는 스위치)")
    void fetchType_disabled_skipsCall() {
        AuthProperties props = props();
        props.setProfileEndpoint("");
        Fixture f = fixture(props);
        // 🔴 기대 요청을 하나도 등록하지 않았다 — 호출이 나가면 목 서버가 실패시킨다.

        assertThat(f.client().fetchType(TOKEN)).isEmpty();
        f.server().verify();
    }

    @Test
    @DisplayName("🔴 빈 문자열 type 도 값이다 — 부재(유지)와 강등을 가른다")
    void fetchType_emptyStringIsAValue() {
        Fixture f = fixture(props());
        f.server().expect(requestTo(ME_URL))
                .andRespond(withSuccess("{\"type\":\"\"}", MediaType.APPLICATION_JSON));

        assertThat(f.client().fetchType(TOKEN)).isEqualTo(Optional.of(""));
        f.server().verify();
    }

    @Test
    @DisplayName("injectType — 맵에 이미 type 이 있으면 호출조차 하지 않는다(claim 이 정본)")
    void injectType_doesNotOverwriteExistingClaim() {
        Fixture f = fixture(props());
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "admin");
        // 🔴 기대 요청 없음 — 조달 호출이 나가면 실패한다.

        f.client().injectType(claims, TOKEN);

        assertThat(claims).containsEntry("type", "admin");
        f.server().verify();
    }

    @Test
    @DisplayName("injectType — 맵에 없으면 조달값을 넣는다(sync 는 claim 맵만 본다)")
    void injectType_putsFetchedValue() {
        Fixture f = fixture(props());
        f.server().expect(requestTo(ME_URL))
                .andRespond(withSuccess(ME_BODY, MediaType.APPLICATION_JSON));
        Map<String, Object> claims = new HashMap<>();

        f.client().injectType(claims, TOKEN);

        assertThat(claims).containsEntry("type", "manager");
        f.server().verify();
    }
}
