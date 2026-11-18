package jeju.datahub.batch.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ApiKeyAuthFilter} 를 {@code /api/batch/*} 에만 등록하는 구성.
 *
 * <p>이름에 유의 — Spring Security 를 쓰지 않는다(D2, 이 프로젝트엔 spring-security 의존성이
 * 없다). {@code FilterRegistrationBean} 으로 일반 서블릿 필터 하나를 특정 URL 패턴에 거는
 * 것뿐이라, {@code WebSecurityConfig} 같은 이름을 쓰면 시큐리티 설정이 있는 것으로 오해할 수
 * 있어 {@code ApiKeyAuthFilterConfig} 로 둔다.
 *
 * @see ApiKeyAuthFilter
 */
@Configuration
public class ApiKeyAuthFilterConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
            @Value("${batch.api.auth.enabled:false}") boolean enabled,
            @Value("${batch.api.auth.key:}") String apiKey) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(enabled, apiKey));
        registration.addUrlPatterns("/api/batch/*");
        registration.setName("apiKeyAuthFilter");
        registration.setOrder(0);
        return registration;
    }
}
