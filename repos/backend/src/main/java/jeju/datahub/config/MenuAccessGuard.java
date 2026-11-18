package jeju.datahub.config;

import jeju.datahub.auth.service.AuthLookupService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * 표 파생 인가 판정 한 곳 (폐기 후 유일한 인가 근거).
 *
 * <p>🔁 <b>롤 리터럴을 코드에 두지 않는다.</b> SpEL 롤 판정식에 {@code ROLE_SYSTEM_ADMIN} 을 박아 두면
 * 관리자가 {@code ROLE_USER} 에게 {@code M0602} 를 준 순간 <b>화면은 열리는데 저장은 403</b> 이 된다 —
 * "부여한 대로" 가 아니다. 화면과 API 가 같은 표({@code TB_AUTHRT_MENU})를 본다.
 *
 * <p>✅ 세션이 아니라 DB 를 본다. {@code @PreAuthorize} 가 읽는 authority 는 로그인 시점에 세션에
 * 굳지만({@code AuthController}/{@code MockAuthController} 가 {@code SimpleGrantedAuthority} 저장)
 * 이 판정은 매 요청 조회라 권한을 저장한 즉시 반영된다 — {@code /me} 와 같은 성질이다.
 *
 * <p>사용법: {@code @PreAuthorize("@menuAccessGuard.can(authentication, 'M0602')")}
 */
@Component("menuAccessGuard")
public class MenuAccessGuard {

    private final AuthLookupService authLookupService;

    public MenuAccessGuard(AuthLookupService authLookupService) {
        this.authLookupService = authLookupService;
    }

    /**
     * 🔴 <b>익명은 반드시 false 다.</b> test 프로파일은 필터체인이 permitAll 이라 익명 요청이 여기까지
     * 온다({@code AnonymousAuthenticationToken} 은 {@code isAuthenticated()} 가 true 이고 principal 이
     * {@code "anonymousUser"} 문자열이다). principal 이 {@code OidcUser} 가 아니면 즉시 거절한다.
     */
    public boolean can(Authentication authentication, String menuId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (!(authentication.getPrincipal() instanceof OidcUser user)) {
            return false;
        }
        return authLookupService.hasMenu(user.getSubject(), menuId);
    }
}
