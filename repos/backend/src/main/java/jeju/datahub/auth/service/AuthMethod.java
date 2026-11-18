package jeju.datahub.auth.service;

/**
 * 🔴 로그인 방식 세션 키·값 (TSK-4134 D4 → 🔁 TSK-4271 Step 5 에서 {@code AuthController} 로부터 이동).
 *
 * <p>세션 attribute {@link #AUTH_METHOD} 에 {@link #AUTH_METHOD_OIDC} / {@link #AUTH_METHOD_PASSWORD} /
 * {@link #AUTH_METHOD_MOCK} 중 하나를 둔다. 기록은 {@code AuthController}·{@code MockAuthController},
 * 판정은 {@code AuthController#logout}(IdP 로그아웃 URL 조립)과 {@code SessionTokenRefresher}(Agent 토큰
 * 갱신 위임)가 한다.
 *
 * <h2>🔴 왜 {@code auth.service} 로 옮겼나</h2>
 * 종전에는 {@code AuthController}({@code auth.web})의 package-private 상수라 {@code auth.service.impl} 의
 * {@code SessionTokenRefresher} 가 읽지 못했다. 컨트롤러 쪽을 {@code public} 으로 여는 것은 기각했다 —
 * {@code service → web} 역의존이 생긴다. 🔴 값은 <b>이동만</b> 했다(문자열을 새로 적지 않는다).
 *
 * <h2>🔴 {@code agent} 패키지는 이 클래스를 import 하지 않는다</h2>
 * {@code agent} 는 로그인 방식을 몰라야 한다(CLAUDE.md) — 방식 분기는 {@code auth} 안에만 둔다.
 *
 * <p>D3 대로 비밀번호 경로도 <b>합성 {@code OidcIdToken}</b> 을 들고 있어
 * {@code principal instanceof OidcUser && getIdToken() != null} 이 <b>참</b>이 된다.
 * 그 조건만으로 분기하면 {@code /logout} 이 {@code id_token_hint=<access_token>} 을 조립해
 * IdP 에 엉뚱한 토큰을 보낸다 — 그래서 방식은 이 세션 키로 가른다.
 */
public final class AuthMethod {

    /** 🔴 로그인 방식 세션 키 (TSK-4134 D4). {@code oidc} / {@code password} / {@code mock} 중 하나. */
    public static final String AUTH_METHOD = "AUTH_METHOD";

    /** {@link #AUTH_METHOD} 값 — 이메일/비밀번호 로그인. */
    public static final String AUTH_METHOD_PASSWORD = "password";

    /** {@link #AUTH_METHOD} 값 — 표준 OIDC 인가코드 로그인. 🔴 {@code /logout} 이 IdP 로그아웃 URL 을 만드는 <b>유일한</b> 조건이다. */
    public static final String AUTH_METHOD_OIDC = "oidc";

    /** {@link #AUTH_METHOD} 값 — mock 로그인({@code MockAuthController}). 실 IdP 세션이 없다. */
    public static final String AUTH_METHOD_MOCK = "mock";

    private AuthMethod() {
    }
}
