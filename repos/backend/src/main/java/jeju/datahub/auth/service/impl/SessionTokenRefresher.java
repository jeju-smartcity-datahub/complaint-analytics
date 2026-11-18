package jeju.datahub.auth.service.impl;

import static jeju.datahub.auth.service.AuthMethod.AUTH_METHOD;
import static jeju.datahub.auth.service.AuthMethod.AUTH_METHOD_OIDC;
import static jeju.datahub.auth.service.AuthMethod.AUTH_METHOD_PASSWORD;

import jakarta.servlet.http.HttpSession;
import jeju.datahub.agent.service.TokenRefresher;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Component;

/**
 * {@link TokenRefresher} 의 <b>유일한 구현</b> — 세션의 로그인 방식으로 갱신을 위임한다 (TSK-4271 Step 5 · D6).
 *
 * <ul>
 *   <li>{@code password} → {@link DhubPasswordAuthClient#refresh(String)} (Manager 쿠키 갱신 — 종전 그대로)</li>
 *   <li>{@code oidc} → {@link OidcAgentTokenRenewer#renew} (로그인 토큰 재교환 / refresh_token grant — D7)</li>
 *   <li>그 밖({@code mock}·값 없음·세션 null) → {@link IllegalStateException} → {@code AgentTokenStore} 가 503</li>
 * </ul>
 *
 * <h2>🔴 로그인 방식 분기는 {@code auth} 패키지 여기 한 곳이다</h2>
 * CLAUDE.md "{@code agent} 패키지는 로그인 방식을 몰라야 한다" — {@code AgentTokenStore} 는 세션과 refresh
 * 값을 넘기기만 한다. 구현을 갈래마다 빈으로 두면 {@code AgentTokenStore} 의
 * {@code @Autowired(required = false)} 단일 주입이 모호해진다 — 그래서 위임 구현 1개다.
 *
 * <h2>🔴 {@code @Component} 를 빼지 말 것</h2>
 * 빠지면 {@code AgentTokenStore} 의 {@code @Autowired(required = false)} 가 null 로 조용히 넘어가
 * <b>갱신 전체가 503</b> 이 된다 — 단위 테스트는 필드를 직접 꽂아 이를 못 잡는다.
 *
 * <h2>🔒 로깅</h2>
 * refresh 값을 남기지 않는다. 예외 메시지에는 방식 값만 싣는다(토큰이 섞일 자리가 없다).
 */
@Component
public class SessionTokenRefresher implements TokenRefresher {

    private final DhubPasswordAuthClient passwordClient;
    private final OidcAgentTokenRenewer oidcRenewer;

    public SessionTokenRefresher(DhubPasswordAuthClient passwordClient, OidcAgentTokenRenewer oidcRenewer) {
        this.passwordClient = passwordClient;
        this.oidcRenewer = oidcRenewer;
    }

    @Override
    public OAuth2AccessTokenResponse refresh(HttpSession session, String refreshToken) {
        Object method = (session == null) ? null : session.getAttribute(AUTH_METHOD);
        if (AUTH_METHOD_PASSWORD.equals(method)) {
            return passwordClient.refresh(refreshToken);
        }
        if (AUTH_METHOD_OIDC.equals(method)) {
            return oidcRenewer.renew(session, refreshToken);
        }
        // mock 등 — 갱신 수단이 없다. AgentTokenStore 가 잡아 503(AGENT_TOKEN_UNAVAILABLE)으로 바꾼다.
        throw new IllegalStateException("갱신 수단 없음 — method=" + method);
    }
}
