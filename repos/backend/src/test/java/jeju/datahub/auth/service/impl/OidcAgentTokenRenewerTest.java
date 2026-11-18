package jeju.datahub.auth.service.impl;

import static jeju.datahub.auth.service.impl.DhubTokenExchangeClient.AUDIENCE_AGENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jeju.datahub.auth.service.OidcLoginTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

/**
 * {@link OidcAgentTokenRenewer} 단위 테스트 (TSK-4271 Step 5 · D7·D8).
 *
 * <p>로그인 토큰 보관소는 실물({@link OidcLoginTokenStore} + {@link MockHttpSession}), 교환 클라이언트만
 * Mockito 목이다 — HTTP 계약은 {@code DhubTokenExchangeClientTest} 가 진다.
 */
class OidcAgentTokenRenewerTest {

    private final DhubTokenExchangeClient exchangeClient = mock(DhubTokenExchangeClient.class);
    private final OidcLoginTokenStore loginStore = new OidcLoginTokenStore();
    private final OidcAgentTokenRenewer renewer = new OidcAgentTokenRenewer(exchangeClient, loginStore);

    private static OAuth2AccessTokenResponse response(String access, long expiresInSec, String refresh) {
        OAuth2AccessTokenResponse.Builder b = OAuth2AccessTokenResponse.withToken(access)
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(expiresInSec);
        if (refresh != null) {
            b.refreshToken(refresh);
        }
        return b.build();
    }

    private MockHttpSession sessionWithLogin(long loginExpiresInSec) {
        MockHttpSession session = new MockHttpSession();
        loginStore.save(session, response("login-access", loginExpiresInSec, "login-refresh"));
        return session;
    }

    @Test
    @DisplayName("① 로그인 토큰이 유효(잔여 > SKEW 120s)하면 refreshLogin 없이 재교환만 1회")
    void validLoginTokenExchangesOnly() {
        MockHttpSession session = sessionWithLogin(600);
        when(exchangeClient.exchange("login-access", AUDIENCE_AGENT)).thenReturn(response("agent-access", 600, null));

        OAuth2AccessTokenResponse res = renewer.renew(session, "login-refresh");

        assertThat(res.getAccessToken().getTokenValue()).isEqualTo("agent-access");
        verify(exchangeClient, never()).refreshLogin(anyString());
        verify(exchangeClient, times(1)).exchange("login-access", AUDIENCE_AGENT);
    }

    @Test
    @DisplayName("② 로그인 토큰 만료(임박)면 refreshLogin 1회 → 로그인 저장소 갱신 → 새 로그인 토큰으로 교환 1회")
    void expiredLoginTokenRefreshesThenExchanges() {
        MockHttpSession session = sessionWithLogin(60);   // 60s < SKEW 120s
        when(exchangeClient.refreshLogin("login-refresh")).thenReturn(response("login-access-2", 900, "login-refresh"));
        when(exchangeClient.exchange("login-access-2", AUDIENCE_AGENT)).thenReturn(response("agent-access", 600, null));

        OAuth2AccessTokenResponse res = renewer.renew(session, "login-refresh");

        assertThat(res.getAccessToken().getTokenValue()).isEqualTo("agent-access");
        verify(exchangeClient, times(1)).refreshLogin("login-refresh");
        verify(exchangeClient, times(1)).exchange("login-access-2", AUDIENCE_AGENT);
        assertThat(loginStore.validAccessToken(session, OidcAgentTokenRenewer.SKEW)).contains("login-access-2");
    }

    @Test
    @DisplayName("③ refreshLogin 이 실패하면 예외를 그대로 전파하고 교환은 부르지 않는다")
    void refreshFailurePropagates() {
        MockHttpSession session = sessionWithLogin(60);
        when(exchangeClient.refreshLogin("login-refresh")).thenThrow(new DhubTokenExchangeException("invalid_grant"));

        assertThatThrownBy(() -> renewer.renew(session, "login-refresh"))
                .isInstanceOf(DhubTokenExchangeException.class);
        verify(exchangeClient, never()).exchange(anyString(), anyString());
    }

    @Test
    @DisplayName("④ 회전된 로그인 refresh 가 반환값의 refreshToken 에 실린다 (D8)")
    void rotatedRefreshIsReturned() {
        MockHttpSession session = sessionWithLogin(60);
        when(exchangeClient.refreshLogin("login-refresh")).thenReturn(response("login-access-2", 900, "login-refresh-rotated"));
        when(exchangeClient.exchange("login-access-2", AUDIENCE_AGENT)).thenReturn(response("agent-access", 600, null));

        OAuth2AccessTokenResponse res = renewer.renew(session, "login-refresh");

        assertThat(res.getRefreshToken()).isNotNull();
        assertThat(res.getRefreshToken().getTokenValue()).isEqualTo("login-refresh-rotated");
        assertThat(loginStore.refreshToken(session)).contains("login-refresh-rotated");
    }
}
