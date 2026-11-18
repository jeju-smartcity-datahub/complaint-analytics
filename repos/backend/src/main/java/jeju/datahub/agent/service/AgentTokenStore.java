package jeju.datahub.agent.service;

import jakarta.servlet.http.HttpSession;
import jeju.datahub.agent.service.AgentApiException.Code;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * 로그인 사용자 본인의 Agent API access_token 보관소.
 *
 * <p>🔴 <b>토큰은 HttpSession 안에서만 산다.</b> 응답 바디·로그 어디에도 넣지 않는다 —
 * 특히 {@code /api/auth/me}({@code LoginUserVO})에 절대 추가하지 말 것. 브라우저에 토큰을 두지 않는
 * 것이 BFF 설계의 전제다.
 * <p>🔴 세션 attribute 는 {@code changeSessionId()} 를 건너 살아남으므로 저장 순서는 자유다.
 *
 * <h2>🔒 로깅</h2>
 * <b>토큰 값은 어떤 레벨로도 남기지 않는다.</b> 남기는 것은 만료 시각·성공 여부뿐이다
 * (선례: 배치의 {@code DhubAuthClient}).
 *
 * <h2>갱신(refresh) — (2026-09-04 실측 → 🔴 2026-09-07 개정)</h2>
 * <b>email/pw 경로</b> — Manager {@code /api/v1/auth/login} 이
 * {@code Set-Cookie: refresh_token}(7일)을 주고 그 값이 {@link #ATTR_REFRESH} 에 담긴다.
 * <p>🔁 <b>OIDC 경로</b> — 종전에는 코드 교환 응답에 {@code refresh_token} 이 오지 않아 갱신이 닫혀
 * 있었지만, 2026-10-21 실측으로 이제 온다. 콜백이 <b>로그인 refresh 값</b>을
 * {@link #ATTR_REFRESH} 에 함께 넣어 <b>OIDC 세션도 갱신된다</b>(로그인 refresh/재교환).
 * <p>🔴 어느 경로든 이 클래스는 <b>로그인 방식을 모른다</b> — 세션과 refresh 값을 {@link TokenRefresher}
 * 에 넘길 뿐이고 분기는 구현({@code auth} 패키지)이 한다. 갱신 주체는 {@link TokenRefresher} 이며 이 클래스는 그것을
 * <b>필드 주입으로 선택적으로</b> 받는다 — <b>없으면 종전(1-A) 동작 그대로</b>다.
 *
 * <h2>🔴 {@link TokenRefresher} 를 생성자 주입하지 않는 이유</h2>
 * {@code AgentTokenStoreTest} 가 {@code new AgentTokenStore()}(무인자)로 이 클래스를 직접 만든다.
 * 생성자를 만들면 그 테스트가 <b>컴파일되지 않아</b> 회귀 방어가 통째로 사라진다.
 * 필드 주입은 그 계약을 지키면서 스프링에서만 갱신 수단이 붙게 한다.
 */
@Component
public class AgentTokenStore {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(AgentTokenStore.class);

    /**
     * 🔴 <b>선제 갱신 여유</b> — 남은 수명이 이보다 짧으면 "곧 만료" 로 보고 미리 갱신한다.
     * 호출 왕복 중에 만료되어 upstream 401 을 맞는 것을 줄이려는 것이다(선례: 배치 {@code DhubAuthClient}).
     *
     * <p>🔴 이 여유는 {@link #tokenRefresher} 가 <b>있을 때만</b> 적용된다 — 갱신 수단이 없는데
     * 여유를 적용하면 아직 쓸 수 있는 토큰을 "만료" 로 접어 종전(1-A) 동작이 바뀐다.
     */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);

    /**
     * 🔴 <b>필드 주입</b>({@code required = false}) — 위 "생성자 주입하지 않는 이유" 참조.
     * 구현이 없는 환경(스프링 없이 만든 단위 테스트 등)에서는 {@code null} 이고, 그때 이 클래스는
     * 계약을 한 글자도 바꾸지 않는다.
     *
     * <p>패키지 접근인 것은 같은 패키지의 {@code AgentTokenRefreshTest} 가 스프링 없이 목을
     * 꽂기 위해서다(스프링은 패키지 접근 필드도 주입한다).
     */
    @Autowired(required = false)
    TokenRefresher tokenRefresher;

    /**
     * 🔴 세션 키 3종은 <b>이 클래스에만</b> 둔다. 다른 곳에서 문자열을 다시 적으면
     * 이름이 갈려도 컴파일이 통과해 조용히 토큰을 못 찾는다.
     */
    static final String ATTR_ACCESS = "AGENT_ACCESS_TOKEN";
    static final String ATTR_EXPIRES = "AGENT_ACCESS_TOKEN_EXPIRES_AT";
    static final String ATTR_REFRESH = "AGENT_REFRESH_TOKEN";

    /**
     * OIDC 콜백의 토큰 교환 결과를 세션에 담는다 — access_token · 만료시각 ·
     * (있으면) refresh_token. 🔴 호출 지점은 {@code AuthController#callback} 의
     * {@code authUserSyncService.sync(...)} <b>성공 뒤</b> 하나뿐이다.
     */
    public void save(HttpSession session, OAuth2AccessTokenResponse res) {
        if (session == null || res == null) {
            return;
        }
        OAuth2AccessToken accessToken = res.getAccessToken();
        if (accessToken == null || !StringUtils.hasText(accessToken.getTokenValue())) {
            log.warn("[agent-token] 토큰 교환 응답에 access_token 이 없어 보관하지 않는다.");
            return;
        }
        session.setAttribute(ATTR_ACCESS, accessToken.getTokenValue());
        Instant expiresAt = accessToken.getExpiresAt();
        session.setAttribute(ATTR_EXPIRES, expiresAt);

        OAuth2RefreshToken refreshToken = res.getRefreshToken();
        if (refreshToken != null && StringUtils.hasText(refreshToken.getTokenValue())) {
            session.setAttribute(ATTR_REFRESH, refreshToken.getTokenValue());
        }
        // 🔴 값이 아니라 만료 시각과 유무만 남긴다.
        // 🔴 유무는 **이번 응답에 실려 온 것**이 아니라 **보관 후 세션의 상태**를 찍는다 —
        //    갱신 응답에 새 쿠키가 없으면(회전 없음) 위에서 종전 값을 그대로 두는데, 들어온 것을
        //    찍으면 "보관 완료 — refreshToken=absent" 가 되어 **리프레시를 잃은 것처럼 읽힌다.**
        //    그 오독은 갱신 장애 조사를 정반대 방향으로 보낸다(2026-09-07 실로그에서 실제로 겪었다).
        log.debug("[agent-token] 세션에 보관 완료 — expiresAt={}, refreshToken={}(회전={})",
                expiresAt,
                StringUtils.hasText((String) session.getAttribute(ATTR_REFRESH)) ? "present" : "absent",
                refreshToken != null ? "yes" : "no");
    }

    /**
     * 세션 토큰의 상태 — 🔴 <b>"없음" 과 "만료" 를 나눈다</b>.
     *
     * <p>종전에는 둘 다 {@code Optional.empty()} 였다. 그러면 <b>갱신 진입 자체가 불가능</b>하다 —
     * "없음" 은 갱신해도 소용없고(refresh 값도 없다) "만료" 는 갱신하면 살아나는데, 호출부가 그
     * 둘을 구분할 방법이 없기 때문이다.
     */
    enum TokenState {
        /** 세션에 access_token 이 아예 없다 — 갱신 대상이 아니다. 곧장 503. */
        ABSENT,
        /** access_token 은 있는데 만료됐다 — 🔴 갱신 대상. */
        EXPIRED,
        /** 지금 쓸 수 있다. */
        VALID
    }

    /**
     * {@link #currentToken} 의 결과.
     *
     * @param state     상태
     * @param token     {@link TokenState#VALID} 일 때만 의미가 있다. 🔴 로깅 금지
     * @param expiresAt 만료 시각. 모르면 {@code null}(→ 선제 갱신을 걸지 않는다)
     */
    record TokenLookup(TokenState state, String token, Instant expiresAt) {}

    /**
     * withToken 의 내부 부품 — 🔴 컨트롤러가 직접 부르지 않는다. <b>여기서는 갱신하지 않는다</b>
     * (판정만 한다 — 갱신은 {@link #withToken} 이 상태를 보고 결정한다).
     *
     * <p>만료 시각을 모르면(=null) 유효한 것으로 보고 그대로 넘긴다 — 판정할 근거가 없을 때
     * 미리 503 을 내는 것보다 upstream 401 이 원인을 정확히 말해 준다.
     */
    TokenLookup currentToken(HttpSession session) {
        if (session == null) {
            return new TokenLookup(TokenState.ABSENT, null, null);
        }
        Object token = session.getAttribute(ATTR_ACCESS);
        if (!(token instanceof String value) || !StringUtils.hasText(value)) {
            return new TokenLookup(TokenState.ABSENT, null, null);
        }
        Instant expiresAt = (session.getAttribute(ATTR_EXPIRES) instanceof Instant at) ? at : null;
        if (expiresAt != null && !Instant.now().isBefore(expiresAt)) {
            // 🔴 "없음" 이 아니라 "만료" 다 — refresh 가 있으면 여기서 되살아난다.
            return new TokenLookup(TokenState.EXPIRED, value, expiresAt);
        }
        return new TokenLookup(TokenState.VALID, value, expiresAt);
    }

    /**
     * 🔴 Agent API 호출의 <b>유일한 진입점</b>. 구현이 부르는 것은 이 메서드다
     * (1-B 를 닫아도 반드시 존재해야 한다 — 없으면 컨트롤러가 컴파일되지 않는다).
     *
     * <p>1-A 의 계약(갱신 없음):
     * <ol>
     *   <li>토큰 없음·만료 → {@code AgentApiException(AGENT_TOKEN_UNAVAILABLE)} 를 <b>던진다</b>.
     *       🔴 {@code Function<String,T>} 는 "토큰 없음" 을 반환값에 실을 자리가 없다.
     *       401 이 아니라 503 인 이유는 401 이면 인터셉터가 {@code /login} 으로 보내는데,
     *       다시 로그인해도 토큰이 안 생기는 상태면 무한 왕복이 된다.</li>
     *   <li>있으면 {@code call} 을 <b>정확히 한 번</b> 실행하고 결과를 그대로 돌려준다.</li>
     *   <li>upstream 401 은 그대로 전파한다 — 갱신 수단이 없으므로 재시도하지 않는다.</li>
     * </ol>
     * 1-B 를 연다면 ①과 ③ 사이에 선제 갱신과 401→갱신→1회 재시도만 끼워 넣는다 —
     * 컨트롤러의 호출 코드는 한 글자도 바뀌지 않는다.
     *
     * <h2>🔴 1-B 가 열렸다 — 예고한 그 자리에만 끼워 넣었다</h2>
     * <b>시그니처는 그대로다.</b> {@link TokenRefresher} 가 주입돼 있을 때만 아래 두 갈래가 동작한다.
     * <ol>
     *   <li><b>선제</b> — 만료됐거나 {@link #REFRESH_SKEW} 안으로 임박했으면 <b>부르기 전에</b> 갱신한다.</li>
     *   <li><b>사후</b> — upstream 401({@code AGENT_UNAUTHORIZED})이면 갱신 후 <b>정확히 1회</b>만
     *       재시도한다. 🔴 재시도의 결과는 성공이든 실패든 그대로 나간다 — 루프를 만들지 않는다.</li>
     * </ol>
     * 🔴 갱신 실패는 {@link #clear} 후 {@code AGENT_TOKEN_UNAVAILABLE}(503)이다.
     * <b>401 로 내지 않는다</b> — 401 이면 인터셉터가 {@code /login} 으로 보내는데 재로그인해도
     * 토큰이 안 생기는 상태라 무한 왕복이 된다.
     * <p>🔴 {@code tokenRefresher} 가 {@code null} 이면 위 두 갈래가 통째로 빠져 <b>1-A 의 계약이
     * 글자 그대로 유지</b>된다 — 만료는 곧 503, 401 은 그대로 전파.
     */
    public <T> T withToken(HttpSession session, Function<String, T> call) {
        TokenLookup lookup = currentToken(session);

        // ① 토큰 자체가 없으면 갱신할 것도 없다 — 종전과 동일하게 곧장 503.
        if (lookup.state() == TokenState.ABSENT) {
            throw new AgentApiException(Code.AGENT_TOKEN_UNAVAILABLE);
        }

        // ② 선제 갱신 — 만료됐거나 임박했을 때. 🔴 임박 판정은 갱신 수단이 있을 때만 건다.
        String token = lookup.token();
        if (lookup.state() == TokenState.EXPIRED) {
            // 🔴 갱신 수단이 없으면 refreshOrFail 이 종전 그대로 503 을 던진다.
            token = refreshOrFail(session, token);
        } else if (tokenRefresher != null && dueSoon(lookup.expiresAt())) {
            token = refreshOrFail(session, token);
        }

        try {
            return call.apply(token);   // ③ 정상 경로에서는 정확히 한 번 실행된다.
        } catch (AgentApiException e) {
            // ④ 사후 갱신 — upstream 401 만 대상이다. 갱신 수단이 없으면 종전대로 그대로 전파한다.
            if (e.getCode() != Code.AGENT_UNAUTHORIZED || tokenRefresher == null) {
                throw e;
            }
            log.debug("[agent-token] upstream 401 — 갱신 후 1회만 재시도한다.");
            String renewed = refreshOrFail(session, token);
            // 🔴 재시도는 여기 한 번뿐이다. 이 호출이 다시 401 이어도 잡지 않는다(무한 루프 방지).
            return call.apply(renewed);
        }
    }

    /** 남은 수명이 {@link #REFRESH_SKEW} 안이면 참. 만료 시각을 모르면(=null) 거짓(=선제 갱신 안 함). */
    private static boolean dueSoon(Instant expiresAt) {
        return expiresAt != null && Instant.now().isAfter(expiresAt.minus(REFRESH_SKEW));
    }

    /**
     * 세션의 {@link #ATTR_REFRESH} 로 갱신하고 <b>새 access_token 을 돌려준다</b>.
     * 실패하면 {@link #clear} 후 {@code AGENT_TOKEN_UNAVAILABLE}(503) — 401 이 아니다.
     *
     * <p>🔴 갱신 성공 시 {@link #save} 를 그대로 재사용한다 — 세션 키를 여기서 다시 적지 않는다.
     * 응답에 새 refresh 값이 실려 오면(회전) {@code save} 가 교체하고, 없으면 종전 값이 남는다.
     *
     * <h2>🔴 같은 세션의 갱신은 한 번에 하나다</h2>
     * refresh_token 은 쓸 때마다 회전하고, 이미 쓴 값을 다시 쓰면 IdP 가 거절한다
     * (D.Hub2 연동 가이드 4절 — 400 {@code Refresh token revoked}). 잠금이 없으면 같은 세션의 두 요청이
     * 같은 refresh 로 동시에 갱신해 늦은 쪽이 실패하고, 그 실패의 {@link #clear} 가 앞 요청이 방금 받은
     * 토큰까지 지워 재로그인 전까지 503 이 된다(2026-11-10 로그에서 같은 세션 동시 갱신 실측).
     * <ul>
     *   <li>잠금은 세션 뮤텍스({@link WebUtils#getSessionMutex}) — {@code HttpSessionMutexListener} 가
     *       등록한 직렬화 가능 객체이고, 없으면 세션 자체다.</li>
     *   <li>잠금 안에서 토큰을 <b>다시 읽어</b>, 다른 요청이 이미 갱신했으면(내가 본 토큰과 다르고 유효)
     *       refresh 를 또 쓰지 않고 그 토큰을 돌려준다.</li>
     *   <li>⚠️ 잠금은 갱신 왕복 동안만 잡는다 — Agent 호출({@code call})은 잠금 밖이다.</li>
     * </ul>
     *
     * @param staleToken 호출부가 갱신이 필요하다고 판단했을 때 본 토큰(만료·임박·upstream 401)
     */
    private String refreshOrFail(HttpSession session, String staleToken) {
        if (session == null || tokenRefresher == null) {
            return doRefresh(session);
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            TokenLookup now = currentToken(session);
            if (now.state() == TokenState.VALID
                    && !now.token().equals(staleToken)
                    && !dueSoon(now.expiresAt())) {
                log.debug("[agent-token] 다른 요청이 이미 갱신했다 — 갱신 없이 그 토큰을 쓴다.");
                return now.token();
            }
            return doRefresh(session);
        }
    }

    /** 실제 갱신 — {@link #refreshOrFail} 의 잠금 안에서만 부른다. */
    private String doRefresh(HttpSession session) {
        TokenRefresher refresher = tokenRefresher;
        if (refresher == null) {
            // 🔴 종전 동작 — 갱신 수단이 없으면 "만료" 는 곧 "토큰 없음" 이다.
            throw new AgentApiException(Code.AGENT_TOKEN_UNAVAILABLE);
        }
        Object refresh = (session == null) ? null : session.getAttribute(ATTR_REFRESH);
        if (!(refresh instanceof String refreshValue) || !StringUtils.hasText(refreshValue)) {
            log.warn("[agent-token] 갱신할 refresh 값이 세션에 없다 — 재로그인이 필요하다.");
            clear(session);
            throw new AgentApiException(Code.AGENT_TOKEN_UNAVAILABLE);
        }

        OAuth2AccessTokenResponse res;
        try {
            res = refresher.refresh(session, refreshValue);
        } catch (RuntimeException e) {
            // 🔴 값도, 예외 메시지도 남기지 않는다 — 타입 이름만 옮긴다.
            log.warn("[agent-token] 갱신 실패 — 세션 토큰을 비운다. cause={}", e.getClass().getSimpleName());
            clear(session);
            throw new AgentApiException(Code.AGENT_TOKEN_UNAVAILABLE);
        }

        OAuth2AccessToken renewed = (res == null) ? null : res.getAccessToken();
        if (renewed == null || !StringUtils.hasText(renewed.getTokenValue())) {
            log.warn("[agent-token] 갱신 응답에 access_token 이 없다 — 세션 토큰을 비운다.");
            clear(session);
            throw new AgentApiException(Code.AGENT_TOKEN_UNAVAILABLE);
        }

        save(session, res);
        // 🔴 값이 아니라 만료 시각과 성공 여부만 남긴다.
        log.debug("[agent-token] 갱신 성공 — expiresAt={}", renewed.getExpiresAt());
        return renewed.getTokenValue();
    }

    /**
     * 세션에서 Agent 토큰 3종을 지운다.
     *
     * <p>🔴 <b>호출부가 생겼다</b> — {@link #refreshOrFail} 의 갱신 실패 경로다.
     * 예고한 대로 "세션은 살아 있는데 토큰만 못 쓰는" 상태가 실재하게 됐고, 그때 죽은 토큰을 남겨
     * 두면 다음 요청이 같은 값으로 다시 401 을 맞는다. {@code AuthController#logout} 은 여전히
     * {@code session.invalidate()} 로 세션 전체를 버리므로 이 메서드를 부르지 않는다.
     */
    public void clear(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(ATTR_ACCESS);
        session.removeAttribute(ATTR_EXPIRES);
        session.removeAttribute(ATTR_REFRESH);
    }
}
