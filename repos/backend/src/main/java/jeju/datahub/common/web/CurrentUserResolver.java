package jeju.datahub.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.auth.service.AuthLookupService;
import jeju.datahub.auth.service.LoginUserVO;

/**
 * 로그인 사용자 해석기 (TSK-4240 Step 1) — 컨트롤러가 {@code @AuthenticationPrincipal OidcUser} 로 받은
 * principal 을 {@code TB_USER} 의 사용자로 옮긴다. 컨트롤러는 {@code currentUser} 로 주입받는다.
 *
 * <p>🔴 <b>401 판정은 여기 한 곳이다</b>(D7). 미인증 · 사용자 없음 · 정지는 폴백이 아니라 401 이다 —
 * {@code "system"} 같은 폴백을 두면 아무에게도 보이지 않는 소유 행이 쌓인다. 서비스는 인증 기술을 모르고
 * {@code userId}·{@code userNm} 문자열만 받는다(D6).
 *
 * <p>🔴 조회 키는 {@code cert_id}(= OIDC {@code sub}, {@code user.getSubject()})다 —
 * {@code authentication.getName()} 은 {@code user_id} 와 다를 수 있다(R7, {@code AuthController#me} 와 같은 규칙).
 *
 * <p>⚠️ 비밀번호 로그인 · mock 로그인도 principal 이 {@code OidcUser} 다(합성 {@code OidcIdToken}).
 * {@code AUTH_METHOD} 로 가르지 않는다.
 *
 * <p>⚠️ {@code common} → {@code auth.service} 의존은 이 클래스가 처음이다. 인터페이스
 * ({@link AuthLookupService})와 VO 만 참조한다 — {@code auth.service.impl} 은 참조하지 않는다.
 */
@Component
public class CurrentUserResolver {

    private final AuthLookupService authLookupService;

    public CurrentUserResolver(AuthLookupService authLookupService) {
        this.authLookupService = authLookupService;
    }

    /**
     * 로그인 사용자({@code user_id} · {@code user_nm}).
     *
     * <p>익명(테스트 permitAll 체인에서 principal 이 null)·사용자 없음·정지는 401 — 운영 체인에서 익명은
     * 여기 닿기 전에 401 이다.
     *
     * <p>⚠️ 반환 객체는 {@code /me} 응답 VO 라 {@code menuPathNms} 가 <b>null</b> 이다. 호출부는
     * {@code getUserId()}·{@code getUserNm()} 만 읽고, 이 객체를 응답으로 그대로 내보내지 않는다.
     */
    public LoginUserVO resolve(OidcUser user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        LoginUserVO me = authLookupService.findUserSummary(user.getSubject());
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return me;
    }

    /** 로그인 사용자의 {@code TB_USER.user_id}. 401 판정을 한 곳에 두려고 {@link #resolve} 를 감싼다 */
    public String resolveUserId(OidcUser user) {
        return resolve(user).getUserId();
    }
}
