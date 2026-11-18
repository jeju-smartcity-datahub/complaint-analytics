package jeju.datahub.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /api/internal/**} 전용 API Key 필터.
 *
 * <p>🔴 배치(jeju-datahub-batch)의 {@code ApiKeyAuthFilter} 와 같은 규약의 역방향이다 —
 * 그쪽은 우리가 배치를 부를 때, 이쪽은 배치가 우리를 부를 때 쓴다. 헤더 이름·fail-closed
 * 정책·로그 문구를 일부러 같게 두어 관례가 하나로 유지된다.
 *
 * <p>🔴 <b>fail-closed</b> — {@code enabled=true} 인데 키가 비어 있으면 전부 503 이다.
 * 무인증으로 열리는 것보다 안전하다. 이 API 는 보고서를 무제한 생성할 수 있는 쓰기 경로다.
 *
 * <p>🔴 비교는 {@link MessageDigest#isEqual} 로 한다 — {@code String.equals} 는 타이밍 차가 난다.
 *
 * <p>🔴 {@code @Component} 로 등록하지 않는다 — Spring Boot 가 모든 경로에 자동 등록해
 * actuator 헬스체크까지 막는다. {@code SecurityConfig} 에서 {@code addFilterBefore} 로
 * 이 경로에만 건다.
 *
 * <h2>동작</h2>
 * <ul>
 *   <li>{@code app.internal-api.enabled=false}(기본, local) — 검사하지 않는다. 로컬 개발
 *       curl 예시가 그대로 동작한다.</li>
 *   <li>{@code enabled=true}(dev/prod 기본) — {@code X-API-Key} 헤더가
 *       {@code app.internal-api.key} 와 일치해야 통과한다. 키가 설정되지 않았으면
 *       <b>fail-closed</b> — 전부 503 으로 막는다(무인증으로 열리는 것보다 안전하다).</li>
 * </ul>
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalApiKeyFilter.class);
    private static final String HEADER = "X-API-Key";
    /** 🔴 {@code SecurityConfig} 의 permitAll·CSRF 면제 매처와 같은 경로여야 한다. */
    private static final String PATH_PREFIX = "/api/internal/";

    private final boolean enabled;
    private final String apiKey;

    public InternalApiKeyFilter(boolean enabled, String apiKey) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            log.error("app.internal-api.enabled=true 인데 app.internal-api.key(INTERNAL_API_KEY) 가 "
                    + "비어 있다 — /api/internal/** 전체가 503 으로 막힌다. 환경변수 INTERNAL_API_KEY 를 설정할 것.");
        }
    }

    /** 🔴 {@code /api/internal/} 이 아닌 요청은 이 필터를 타지 않는다(다른 경로에 영향 0). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            respond(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "internal API key not configured (INTERNAL_API_KEY missing)");
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !constantTimeEquals(provided, apiKey)) {
            log.warn("내부 API 인증 실패: {} {} (from {})",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            respond(response, HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid " + HEADER);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void respond(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /** 타이밍 공격 방지 — 문자열 길이·내용 비교를 헤더 값 길이와 무관한 시간에 끝낸다. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
