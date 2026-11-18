package jeju.datahub.batch.web;

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
 * {@code /api/batch/**} 수동 트리거 엔드포인트 보호용 API Key 필터.
 *
 * <h2>왜 Spring Security 가 아니라 Filter 인가</h2>
 * 이 프로젝트는 {@code spring-security} 의존성이 없다(D2 — RTE/시큐리티 모두 배치엔 불필요).
 * 트리거 엔드포인트 4개만 잠그면 되는 요구라 전체 시큐리티 체인을 새로 얹기보다,
 * 이 경로에만 적용되는 얇은 필터 하나가 변경 범위와 리스크가 작다.
 *
 * <h2>동작</h2>
 * <ul>
 *   <li>{@code batch.api.auth.enabled=false}(기본, local) — 검사하지 않는다. 기존 로컬 개발
 *       curl 예시(컨트롤러 Javadoc)가 그대로 동작한다.</li>
 *   <li>{@code enabled=true}(dev/prod 기본) — {@code X-API-Key} 헤더가
 *       {@code batch.api.auth.key} 와 일치해야 통과한다. 키가 설정되지 않았으면
 *       <b>fail-closed</b> — 전부 503 으로 막는다(무인증으로 열리는 것보다 안전하다).</li>
 * </ul>
 *
 * <p><b>{@code @Component} 로 등록하지 않는다.</b> Spring Boot 는 Filter 빈을 발견하면
 * 기본적으로 모든 경로({@code /*})에 자동 등록하므로, 이 필터를 {@code @Component} 로
 * 두면 actuator 헬스체크까지 막혀버린다. 대신 {@link ApiKeyAuthFilterConfig} 에서
 * {@code FilterRegistrationBean} 으로 {@code /api/batch/*} 에만 명시적으로 건다.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String HEADER = "X-API-Key";

    private final boolean enabled;
    private final String apiKey;

    public ApiKeyAuthFilter(boolean enabled, String apiKey) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            log.error("batch.api.auth.enabled=true 인데 batch.api.auth.key(BATCH_API_KEY) 가 "
                    + "비어 있다 — /api/batch/** 전체가 503 으로 막힌다. 환경변수 BATCH_API_KEY 를 설정할 것.");
        }
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
                    "batch API key not configured (BATCH_API_KEY missing)");
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !constantTimeEquals(provided, apiKey)) {
            log.warn("API 트리거 인증 실패: {} {} (from {})",
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
