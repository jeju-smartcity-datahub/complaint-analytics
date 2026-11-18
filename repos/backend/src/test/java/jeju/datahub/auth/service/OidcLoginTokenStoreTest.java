package jeju.datahub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

/**
 * {@link OidcLoginTokenStore} 단위 테스트.
 *
 * <p>{@code HttpSession} 은 spring-test 의 {@link MockHttpSession} 을 쓴다(선례 {@code AgentTokenStoreTest}).
 */
class OidcLoginTokenStoreTest {

    private static final Duration SKEW = Duration.ofSeconds(120);

    private final OidcLoginTokenStore store = new OidcLoginTokenStore();

    private static OAuth2AccessTokenResponse response(String access, long expiresInSec, String refresh) {
        OAuth2AccessTokenResponse.Builder b = OAuth2AccessTokenResponse.withToken(access)
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(expiresInSec);
        if (refresh != null) {
            b.refreshToken(refresh);
        }
        return b.build();
    }

    @Test
    @DisplayName("저장한 access·refresh 를 그대로 꺼낸다")
    void saveAndRead() {
        MockHttpSession session = new MockHttpSession();

        store.save(session, response("login-access", 900, "login-refresh"));

        assertThat(store.validAccessToken(session, SKEW)).contains("login-access");
        assertThat(store.refreshToken(session)).contains("login-refresh");
    }

    @Test
    @DisplayName("남은 수명이 skew 이하이면 access 는 empty 다")
    void nearExpiryIsEmpty() {
        MockHttpSession session = new MockHttpSession();

        store.save(session, response("login-access", 60, "login-refresh"));   // 60s < skew 120s

        assertThat(store.validAccessToken(session, SKEW)).isEmpty();
        assertThat(store.refreshToken(session)).contains("login-refresh");    // refresh 는 남는다
    }

    @Test
    @DisplayName("회전 없는 갱신 응답이면 기존 refresh 를 유지하고 access 만 바뀐다")
    void keepsRefreshWhenNotRotated() {
        MockHttpSession session = new MockHttpSession();
        store.save(session, response("old-access", 900, "login-refresh"));

        store.save(session, response("new-access", 900, null));

        assertThat(store.validAccessToken(session, SKEW)).contains("new-access");
        assertThat(store.refreshToken(session)).contains("login-refresh");
    }

    @Test
    @DisplayName("clear 는 세션의 로그인 토큰 3종을 모두 지운다")
    void clearRemovesAll() {
        MockHttpSession session = new MockHttpSession();
        store.save(session, response("login-access", 900, "login-refresh"));

        store.clear(session);

        assertThat(store.validAccessToken(session, SKEW)).isEmpty();
        assertThat(store.refreshToken(session)).isEmpty();
        assertThat(session.getAttributeNames().hasMoreElements()).isFalse();
    }
}
