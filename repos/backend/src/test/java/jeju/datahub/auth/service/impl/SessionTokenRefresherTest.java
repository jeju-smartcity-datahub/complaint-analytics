package jeju.datahub.auth.service.impl;

import static jeju.datahub.auth.service.AuthMethod.AUTH_METHOD;
import static jeju.datahub.auth.service.AuthMethod.AUTH_METHOD_MOCK;
import static jeju.datahub.auth.service.AuthMethod.AUTH_METHOD_OIDC;
import static jeju.datahub.auth.service.AuthMethod.AUTH_METHOD_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

/**
 * {@link SessionTokenRefresher} 단위 테스트 — 로그인 방식 4갈래.
 */
class SessionTokenRefresherTest {

    private final DhubPasswordAuthClient passwordClient = mock(DhubPasswordAuthClient.class);
    private final OidcAgentTokenRenewer oidcRenewer = mock(OidcAgentTokenRenewer.class);
    private final SessionTokenRefresher refresher = new SessionTokenRefresher(passwordClient, oidcRenewer);

    private static final OAuth2AccessTokenResponse RENEWED = OAuth2AccessTokenResponse.withToken("renewed")
            .tokenType(OAuth2AccessToken.TokenType.BEARER)
            .expiresIn(600)
            .build();

    private static MockHttpSession sessionWith(String method) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(AUTH_METHOD, method);
        return s;
    }

    @Test
    @DisplayName("password 세션 → DhubPasswordAuthClient.refresh 로 위임")
    void passwordDelegatesToPasswordClient() {
        MockHttpSession s = sessionWith(AUTH_METHOD_PASSWORD);
        when(passwordClient.refresh("rt")).thenReturn(RENEWED);

        assertThat(refresher.refresh(s, "rt")).isSameAs(RENEWED);
        verify(oidcRenewer, never()).renew(any(), anyString());
    }

    @Test
    @DisplayName("oidc 세션 → OidcAgentTokenRenewer.renew 로 위임")
    void oidcDelegatesToRenewer() {
        MockHttpSession s = sessionWith(AUTH_METHOD_OIDC);
        when(oidcRenewer.renew(s, "rt")).thenReturn(RENEWED);

        assertThat(refresher.refresh(s, "rt")).isSameAs(RENEWED);
        verify(passwordClient, never()).refresh(anyString());
    }

    @Test
    @DisplayName("mock 세션 → IllegalStateException (AgentTokenStore 가 503 으로 바꾼다)")
    void mockThrows() {
        MockHttpSession s = sessionWith(AUTH_METHOD_MOCK);

        assertThatThrownBy(() -> refresher.refresh(s, "rt")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(passwordClient, oidcRenewer);
    }

    @Test
    @DisplayName("세션 null → IllegalStateException")
    void nullSessionThrows() {
        assertThatThrownBy(() -> refresher.refresh(null, "rt")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(passwordClient, oidcRenewer);
    }
}
