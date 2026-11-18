package jeju.datahub.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 테스트 전용 필터체인.
 *
 * <p>{@code @SpringBootApplication} 의 컴포넌트 스캔이 {@code jeju.datahub} 를 훑고 테스트 소스도
 * 클래스패스에 있어, 이 클래스는 별도 {@code @Import} 없이 test 프로파일에서만 자동으로 잡힌다.
 *
 * <p>🔴 {@code @Profile("test")} 만으로는 부족하다 — {@code AuthSecurityIntegrationTest} 가
 * {@code properties = "app.auth.security-enabled=true"} 로 운영 체인을 켤 때도 test 프로파일은 그대로라
 * 두 체인이 함께 뜬다. 둘 다 {@code securityMatcher} 없는 {@code anyRequest} 체인이라 SS6 의
 * "any request 체인은 마지막" 검증에 걸려 컨텍스트가 아예 기동하지 못한다.
 *
 * <p>그래서 {@code security-enabled} 를 두 체인의 <b>배타 스위치</b>로 쓴다 — 운영 체인은
 * {@code havingValue="true"}(matchIfMissing=true), 테스트 체인은 {@code havingValue="false"}
 * (matchIfMissing 없음 = 기본 false).
 *
 * <p>🔴 <b>운영 체인을 끄면 반드시 이 체인이 있어야 한다.</b> {@code SecurityFilterChain} 빈이 하나도
 * 없으면 Boot 의 {@code SpringBootWebSecurityConfiguration} 이 기본 체인
 * ({@code anyRequest().authenticated()} + formLogin)을 만들어 상황이 더 나빠진다.
 */
@Configuration
@Profile("test")
@ConditionalOnProperty(name = "app.auth.security-enabled", havingValue = "false")
public class TestSecurityConfig {

    @Bean
    SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                   .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                   .build();
    }
}
