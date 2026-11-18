package jeju.datahub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import jeju.datahub.common.SesnIdHasher;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 애플리케이션 로그 각 줄에 {@code userId}·{@code sesnId} 를 붙인다 — 액세스 로그(Step 2)와 대조하기 위한 MDC 필터.
 *
 * <p>🔴 {@code MDC} 는 <b>{@code org.slf4j.MDC}</b> 다. 이 레포는 {@code spring-boot-starter-log4j2} 라
 * {@code log4j-slf4j2-impl} 이 slf4j MDC 를 Log4j2 {@code ThreadContext} 로 위임한다 —
 * {@code ThreadContext} 를 직접 쓰지 않는다.
 *
 * <p>🔴 <b>이중 등록 금지.</b> 순서를 주지 않은 {@code Filter} 빈은 {@code LOWEST_PRECEDENCE} 로 등록돼
 * {@code springSecurityFilterChain}({@code SecurityProperties.DEFAULT_FILTER_ORDER} = -100) <b>뒤</b>에
 * 놓이므로 {@code SecurityContext} 가 이미 채워져 있다. 즉 {@code @Component} 만으로 충분하며,
 * {@code SecurityConfig} 에 {@code addFilterAfter(...)} 를 함께 두면 서블릿 컨테이너와 시큐리티 체인
 * 양쪽에 중복 등록된다.
 */
@Component
public class MdcLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            // 🔴 익명 요청이면 principal 이 "anonymousUser" 문자열이다 — null 이 아니다
            boolean authed = auth != null && auth.isAuthenticated()
                    && !(auth instanceof AnonymousAuthenticationToken);
            // 🔴 항상 put 한다 — Log4j2 %X 에는 기본값 문법이 없어, 안 넣으면 빈칸으로 찍힌다
            MDC.put("userId", authed ? auth.getName() : "anon");
            HttpSession s = req.getSession(false);          // 🔴 없을 때 만들지 않는다
            // D5 — 원문 저장 금지. 길이는 DB(SHA-256 앞 16자)와 **같게** 맞춘다
            String sesnHash = (s != null) ? SesnIdHasher.shortHash(s.getId()) : null;
            MDC.put("sesnId", sesnHash != null ? sesnHash : "-");   // 🔴 "-" 는 MDC 표시 전용 — DB 로는 안 간다
            chain.doFilter(req, res);
        } finally {
            MDC.clear();     // 🔴 반드시 지운다 — 톰캣 스레드풀이 재사용돼 다음 요청에 샌다
        }
    }
}
