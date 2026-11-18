package jeju.datahub.auth.service.impl;

import static jeju.datahub.auth.service.impl.DhubTokenExchangeClient.AUDIENCE_AGENT;

import jakarta.servlet.http.HttpSession;
import jeju.datahub.auth.service.OidcLoginTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OIDC 세션의 Agent 토큰 갱신. {@link SessionTokenRefresher} 가
 * 세션 {@code AUTH_METHOD=oidc} 일 때 위임한다.
 *
 * <h2>순서</h2>
 * <ol>
 *   <li>로그인 access_token 이 유효하면(잔여 &gt; {@link #SKEW}) <b>재교환만</b> 한다 — Manager 왕복 1회.</li>
 *   <li>만료(임박)면 {@code grant_type=refresh_token} 으로 로그인 토큰을 갱신 → {@link OidcLoginTokenStore}
 *       에 보관 → 재교환.</li>
 *   <li>refresh 도 실패하면 예외를 그대로 던진다 → {@code AgentTokenStore} 가 세션 토큰을 비우고 503.</li>
 * </ol>
 * 🔴 Manager 자체 {@code /api/v1/auth/refresh}(쿠키)로 보내지 않는다 — OIDC refresh 는 거기서 403 이다(10-21 실측).
 *
 * <h2>🔴 재시도를 새로 만들지 않는다</h2>
 * {@code AgentTokenStore.withToken} 의 "선제 + upstream 401 후 1회" 가 이미 있다.
 * 교환이 실패했다고 refresh 로 한 번 더 가는 식의 <b>내부 재시도도 두지 않는다</b> — 두 겹이 되면
 * 같은 요청이 Manager 를 여러 번 친다. ⚠️ 교환만 일시 실패해도 {@code AgentTokenStore.clear} 가
 * refresh 까지 지운다(재로그인으로 복구).
 *
 * <h2>🔴 {@link #SKEW} 는 {@code AgentTokenStore.REFRESH_SKEW}(60s)보다 커야 한다</h2>
 * 교환 토큰 수명은 {@code min(로그인 만료, 10분)} 이다. 같은 60s 면 로그인 잔여 61s 일 때 재교환된
 * 토큰(61s)이 다음 요청에서 곧바로 "임박" 으로 다시 갱신된다. 120s 면 교환 토큰이 최소 120s 로 나와
 * 60s 이상 쓸 수 있다.
 *
 * <h2>🔒 로깅</h2>
 * 토큰 값은 어떤 레벨로도 남기지 않는다. 교환·갱신 성공/실패 로그는 {@link DhubTokenExchangeClient}
 * 가 {@code [token-exchange]} 태그로 이미 남긴다 — 여기서는 갈래만 DEBUG 로 남긴다.
 */
@Component
public class OidcAgentTokenRenewer {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(OidcAgentTokenRenewer.class);

    /** 로그인 토큰 유효 판정 여유 — 🔴 {@code AgentTokenStore.REFRESH_SKEW}(60s)보다 커야 한다. */
    static final Duration SKEW = Duration.ofSeconds(120);

    private final DhubTokenExchangeClient exchangeClient;
    private final OidcLoginTokenStore loginStore;

    public OidcAgentTokenRenewer(DhubTokenExchangeClient exchangeClient, OidcLoginTokenStore loginStore) {
        this.exchangeClient = exchangeClient;
        this.loginStore = loginStore;
    }

    /**
     * 새 {@code dhub2-agent} 교환 토큰을 받아 온다.
     *
     * @param session      현재 세션(로그인 토큰 보관소)
     * @param loginRefresh {@code AgentTokenStore.ATTR_REFRESH} 의 값 = 콜백이 실어 둔 로그인 refresh. 🔴 로깅 금지
     * @return 교환 토큰 + {@code refreshToken} 칸에 <b>현재(회전됐다면 새) 로그인 refresh</b>
     * @throws RuntimeException 로그인 refresh·교환 실패 — 그대로 전파한다(호출부가 503)
     */
    OAuth2AccessTokenResponse renew(HttpSession session, String loginRefresh) {
        String subject = loginStore.validAccessToken(session, SKEW).orElseGet(() -> {
            log.debug("[oidc-agent-renew] 로그인 토큰 만료(임박) — refresh_token 으로 갱신 후 재교환");
            OAuth2AccessTokenResponse r = exchangeClient.refreshLogin(loginRefresh);   // 실패 → 예외 전파
            loginStore.save(session, r);
            return r.getAccessToken().getTokenValue();
        });
        OAuth2AccessTokenResponse agent = exchangeClient.exchange(subject, AUDIENCE_AGENT);
        // 회전된 로그인 refresh 를 실어 AgentTokenStore.ATTR_REFRESH 와 맞춘다.
        //    withResponse 는 tokenType·expiresAt·scopes 를 그대로 복사한다 — refresh 칸만 채운다.
        return OAuth2AccessTokenResponse.withResponse(agent)
                .refreshToken(loginStore.refreshToken(session).orElse(loginRefresh))
                .build();
    }
}
