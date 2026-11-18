package jeju.datahub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.util.HttpSessionMutexListener;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 전역 Web MVC 설정.
 *
 * 프론트엔드(React, 개발 시 Vite dev server 5173 포트)에서의 CORS 를 허용한다.
 * 운영 환경에서는 허용 Origin 을 실제 도메인으로 좁혀야 한다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * 세션마다 직렬화 가능한 뮤텍스를 심는다(TSK-4349) — {@code AgentTokenStore} 가 같은 세션의 토큰 갱신을
     * 한 번에 하나로 잠그는 데 쓴다({@code WebUtils.getSessionMutex}). 빈으로 두면 Boot 가 서블릿 컨테이너
     * 리스너로 등록한다. 없어도 세션 객체로 잠가 동작은 하지만, 컨테이너가 요청마다 다른 세션 파사드를
     * 줄 수 있어 고정 객체를 둔다.
     */
    @Bean
    public HttpSessionMutexListener httpSessionMutexListener() {
        return new HttpSessionMutexListener();
    }
}
