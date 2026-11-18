package jeju.datahub.auth.service;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * OIDC 로그인 토큰(access · 만료시각 · refresh) 세션 보관소 (TSK-4271 Step 2 · D5).
 *
 * <p>🔴 <b>Agent 저장소({@code AgentTokenStore})와 별개다.</b> 교환({@code dhub2-agent}·{@code dhub2-manager})
 * 에 필요한 것은 refresh 가 아니라 <b>로그인 access_token</b> 이고, 그것이 살아 있으면 refresh 없이
 * 재교환만 하면 된다(D5·D7 ①). Agent 쪽 {@code ATTR_REFRESH} 를 빌려 쓰지 않는다.
 *
 * <p>🔴 <b>토큰은 HttpSession 안에서만 산다.</b> {@code /api/auth/me}({@code LoginUserVO})·응답 바디·
 * 로그 어디에도 싣지 않는다. 로그아웃은 {@code session.invalidate()} 가 함께 지우므로 별도 호출이 필요 없다.
 * <p>세션 값은 {@code String}·{@link Instant} 만 둔다(R5 — 다중 인스턴스·세션 직렬화).
 *
 * <h2>🔒 로깅</h2>
 * <b>토큰 값은 어떤 레벨로도 남기지 않는다.</b> {@link #save} 의 INFO 1줄은 refresh <b>유무</b>와
 * 수명(초)만 남긴다 — R6(코드 교환 응답에 refresh 가 없는 세션) 판정용이다.
 */
@Component
public class OidcLoginTokenStore {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(OidcLoginTokenStore.class);

    /**
     * 🔴 세션 키 3종은 <b>이 클래스에만</b> 둔다(선례 {@code AgentTokenStore}). 다른 곳에서 문자열을
     * 다시 적으면 이름이 갈려도 컴파일이 통과해 조용히 토큰을 못 찾는다.
     */
    static final String ATTR_ACCESS = "OIDC_LOGIN_ACCESS_TOKEN";
    static final String ATTR_EXPIRES = "OIDC_LOGIN_ACCESS_TOKEN_EXPIRES_AT";
    static final String ATTR_REFRESH = "OIDC_LOGIN_REFRESH_TOKEN";

    /**
     * 코드 교환·refresh 갱신 응답을 세션에 담는다 — access_token · 만료시각 · (있으면) refresh_token.
     *
     * <p>🔴 응답에 refresh 가 없으면(회전 없음) <b>기존 refresh 값을 유지</b>한다(선례 {@code AgentTokenStore.save}).
     */
    public void save(HttpSession session, OAuth2AccessTokenResponse res) {
        if (session == null || res == null) {
            return;
        }
        OAuth2AccessToken accessToken = res.getAccessToken();
        if (accessToken == null || !StringUtils.hasText(accessToken.getTokenValue())) {
            log.warn("[oidc-login-token] 토큰 응답에 access_token 이 없어 보관하지 않는다.");
            return;
        }
        session.setAttribute(ATTR_ACCESS, accessToken.getTokenValue());
        Instant expiresAt = accessToken.getExpiresAt();
        session.setAttribute(ATTR_EXPIRES, expiresAt);

        OAuth2RefreshToken refreshToken = res.getRefreshToken();
        if (refreshToken != null && StringUtils.hasText(refreshToken.getTokenValue())) {
            session.setAttribute(ATTR_REFRESH, refreshToken.getTokenValue());
        }
        // 🔴 값이 아니라 유무와 수명만 남긴다(R6 판정용).
        // 🔴 유무는 이번 응답이 아니라 **보관 후 세션 상태** 기준이다 — 회전 없는 갱신 응답에서
        //    "absent" 로 찍히면 refresh 를 잃은 것처럼 오독된다(AgentTokenStore.save 의 같은 판단).
        log.info("[oidc-login-token] saved refresh={} expiresIn={}s",
                refreshToken(session).isPresent() ? "present" : "absent",
                expiresInSeconds(accessToken));
    }

    /**
     * 지금 쓸 수 있는 로그인 access_token. 남은 수명이 {@code skew} 이하이면 empty(D7 ① 판정).
     *
     * <p>만료시각을 모르면(=null) 유효한 것으로 본다 — 계획서에 명시가 없어
     * {@code AgentTokenStore.currentToken} 과 같은 판단을 따랐다(판정 근거가 없을 때 미리 막는 것보다
     * upstream 의 거절이 원인을 정확히 말해 준다).
     */
    public Optional<String> validAccessToken(HttpSession session, Duration skew) {
        if (session == null) {
            return Optional.empty();
        }
        if (!(session.getAttribute(ATTR_ACCESS) instanceof String value) || !StringUtils.hasText(value)) {
            return Optional.empty();
        }
        Instant expiresAt = (session.getAttribute(ATTR_EXPIRES) instanceof Instant at) ? at : null;
        if (expiresAt == null) {
            return Optional.of(value);
        }
        Duration margin = (skew == null) ? Duration.ZERO : skew;
        if (!Instant.now().isBefore(expiresAt.minus(margin))) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /** 세션의 로그인 refresh_token. 없으면 empty(R6). 🔴 로깅 금지. */
    public Optional<String> refreshToken(HttpSession session) {
        if (session == null) {
            return Optional.empty();
        }
        return (session.getAttribute(ATTR_REFRESH) instanceof String value && StringUtils.hasText(value))
                ? Optional.of(value)
                : Optional.empty();
    }

    /** 세션에서 로그인 토큰 3종을 지운다. 로그아웃은 {@code session.invalidate()} 가 대신하므로 부르지 않는다. */
    public void clear(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(ATTR_ACCESS);
        session.removeAttribute(ATTR_EXPIRES);
        session.removeAttribute(ATTR_REFRESH);
    }

    /** 로그용 수명(초). 만료시각을 모르면 {@code "?"}. */
    private static String expiresInSeconds(OAuth2AccessToken accessToken) {
        Instant expiresAt = accessToken.getExpiresAt();
        if (expiresAt == null) {
            return "?";
        }
        Instant base = (accessToken.getIssuedAt() != null) ? accessToken.getIssuedAt() : Instant.now();
        return String.valueOf(Duration.between(base, expiresAt).getSeconds());
    }
}
