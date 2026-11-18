package jeju.datahub.agent.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

/**
 * Agent API access_token 을 갱신할 수 있는 주체 (개정 · 세션 인자화).
 *
 * <h2>🔴 이 인터페이스가 {@code agent} 패키지에 사는 이유</h2>
 * 구현은 {@code SessionTokenRefresher}({@code auth} 패키지, <b>로그인 방식별 위임</b>)
 * 하나다 — password 세션은 {@code DhubPasswordAuthClient}, OIDC 세션은 {@code OidcAgentTokenRenewer}
 * 로 넘긴다. {@link AgentTokenStore} 가 그 클래스들을 직접 참조하면 <b>{@code agent} → {@code auth} 역참조</b>가
 * 생긴다. 🔴 로그인 방식 분기도 {@code auth} 안에만 둔다 — {@code agent} 는 로그인 방식을 모른다.
 * 지금 의존 방향은 {@code auth} → {@code agent} 한 방향이다({@code AuthController} 가
 * {@code AgentTokenStore.save} 를 부른다). 그래서 <b>계약은 {@code agent} 가 소유하고 구현만
 * {@code auth} 가 진다</b> — 나중에 갱신 주체가 바뀌어도 이 파일은 그대로다.
 *
 * <h2>🔴 구현이 없어도 된다</h2>
 * {@link AgentTokenStore} 는 이 빈을 <b>필드 주입({@code @Autowired(required = false)})</b> 으로 받고,
 * 없으면 <b>종전 동작을 그대로</b> 한다 — 만료는 곧 "토큰 없음"(503)이고 재시도는
 * 없다(단위 테스트 등). 🔁 종전 "OIDC 경로에는 {@code refresh_token} 이 오지 않는다" 는
 * 2026-10-21 실측으로 뒤집혔고, <b>OIDC 세션도 갱신된다</b>(로그인 refresh + 재교환).
 *
 * <h2>🔴 반환 타입이 {@link OAuth2AccessTokenResponse} 인 이유</h2>
 * {@link AgentTokenStore#save} 가 요구하는 타입 그대로다 — 갱신 결과를 저장하는 데 <b>변환 계층이
 * 하나도 끼지 않는다</b>. 스프링 시큐리티 공용 타입이라 {@code auth}/{@code agent} 어느 쪽에도
 * 속하지 않는다는 점도 이 선택의 근거다.
 *
 * <h2>🔒 로깅</h2>
 * 구현은 <b>refresh 값·새 access 값을 어떤 레벨로도 남기지 않는다.</b> 남기는 것은 만료 시각·
 * 성공 여부뿐이다.
 */
public interface TokenRefresher {

    /**
     * 세션에 보관해 둔 {@code refresh_token} 값으로 새 access_token 을 받아 온다.
     *
     * <p>🔴 {@code session} 은 구현이 <b>로그인 방식을 판정</b>하고(OIDC 갈래는) 로그인 토큰 보관소를
     * 읽고 쓰는 데 쓴다. {@code agent} 는 그 안을 들여다보지 않고 넘기기만 한다.
     *
     * <p>🔴 <b>password 갈래에서는 리프레시가 body 가 아니라 쿠키로 오간다</b> — 구현은 이 값을 자기 요청의
     * {@code Cookie: refresh_token=<값>} 헤더로 실어 보낸다. 브라우저 쿠키가 아니다(브라우저는
     * Manager 를 직접 부르지 않는다).
     *
     * <p>🔴 응답에 <b>새 {@code Set-Cookie} 가 오면</b>(회전) 반환값의
     * {@code refreshToken} 에 그 값을 실어 준다 — 그래야 {@link AgentTokenStore#save} 가 세션의
     * 보관값을 교체한다. 회전이 없으면 {@code refreshToken} 을 비워 반환하고, 그러면 종전 값이
     * 그대로 남는다({@code save} 는 값이 없을 때 덮어쓰지 않는다).
     *
     * @param session      현재 HTTP 세션(로그인 방식 판정용). null 일 수 있다 — 구현이 예외로 처리한다
     * @param refreshToken 세션({@code AGENT_REFRESH_TOKEN})에 보관해 둔 값. 🔴 로깅 금지
     * @return 새 access_token + 수명(+ 회전됐다면 새 refresh 값)
     * @throws RuntimeException 갱신 실패(만료·회수·업스트림 오류). 🔴 호출부
     *         ({@link AgentTokenStore})는 이것을 잡아 세션 토큰을 비우고
     *         {@code AGENT_TOKEN_UNAVAILABLE}(503)로 바꾼다 — 401 로 내면 {@code /login} 무한
     *         왕복이 된다. 그래서 <b>예외 타입을 좁히지 않는다</b> —
     *         구현이 어떤 예외를 쓰든 {@code agent} 는 알 필요가 없다.
     */
    OAuth2AccessTokenResponse refresh(HttpSession session, String refreshToken);
}
