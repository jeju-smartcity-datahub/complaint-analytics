package jeju.datahub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CSRF 토큰을 강제로 실체화해 {@code XSRF-TOKEN} 쿠키가 내려가게 하는 필터 (TSK-4076 D6).
 *
 * <p>🔴 <b>프레임워크가 주는 클래스가 아니다.</b> Spring Security 공식 SPA 가이드에 나오는 필터를
 * 우리가 직접 만든다.
 *
 * <p>Spring Security 6 은 CSRF 토큰을 <b>지연 생성</b>한다 — 아무도 토큰을 읽지 않는 GET 응답에는
 * {@code XSRF-TOKEN} 쿠키가 <b>아예 내려가지 않아</b> SPA 의 첫 POST 가 403 이 된다.
 * {@link CsrfToken#getToken()} 을 한 번 호출하는 것만으로 토큰이 실체화되고
 * {@code CookieCsrfTokenRepository} 가 {@code Set-Cookie} 를 붙인다.
 *
 * <p>Step 3 검증의 <i>"{@code /actuator/health} 응답에 {@code Set-Cookie: XSRF-TOKEN}"</i> 이
 * 이 필터에 걸려 있다.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // 토큰을 실제로 렌더링해야 지연 생성이 트리거된다. 반환값은 쓰지 않는다.
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
