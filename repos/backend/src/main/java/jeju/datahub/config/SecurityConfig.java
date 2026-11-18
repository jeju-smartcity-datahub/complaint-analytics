package jeju.datahub.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.Arrays;
import java.util.List;

/**
 * 인증·인가 설정 (TSK-4076).
 *
 * <p>옵션 B(D5)라 {@code oauth2Login()} 을 <b>쓰지 않는다.</b> 인가 URL 생성·코드 교환은
 * {@code AuthController}(Step 5)가 맡고, 여기서는 <b>인가 규칙 · CSRF · 401 · 로그아웃</b>만 잡는다.
 *
 * <p>🔴 <b>PKCE 는 여기가 아니라 Step 5 의 인가 URL 생성부</b>에서 직접 만든다 —
 * {@code code_challenge} 가 빠지면 IdP 가 422 를 낸다(계획서 1.2절).
 */
@Configuration
@EnableWebSecurity
// 🔴 TSK-4115 Step 7 — @PreAuthorize 를 켠다. 이 애노테이션은 **클래스**에 붙는데 이 클래스 자체는
//    조건부가 아니다(@ConditionalOnProperty 는 아래 필터체인 @Bean 에만 붙어 있다). 따라서
//    test 프로파일(security-enabled=false → TestSecurityConfig 의 permitAll)에서도 메서드 보안이
//    그대로 켜진다 — 익명 호출은 필터를 통과해도 @PreAuthorize 에서 403 이다(의도된 동작).
@EnableMethodSecurity
public class SecurityConfig {

    private final Environment env;

    public SecurityConfig(Environment env) {
        this.env = env;
    }

    /**
     * 🔴 잘못된 프로파일 × 스위치 조합을 기동 시점에 끊는다 (D11).
     *
     * <p>이 가드를 {@code MockAuthController} 에 두면 안 된다 — 그쪽은
     * {@code havingValue="mock"} 이라 {@code mode=oidc} 일 때 빈 자체가 없어 가드가 돌지 않는다.
     * 항상 등록되는 이 {@code @Configuration} 에 둔다.
     *
     * <p>{@code AuthProperties}(Step 4)는 아직 없으므로 {@link Environment} 로 직접 읽는다.
     */
    @PostConstruct
    void validateAuthMode() {
        List<String> profiles = Arrays.asList(env.getActiveProfiles());
        String mode = env.getProperty("app.auth.mode", "oidc");
        boolean securityEnabled = Boolean.parseBoolean(
                env.getProperty("app.auth.security-enabled", "true"));

        if (profiles.contains("prod") && "mock".equals(mode)) {
            throw new IllegalStateException(
                    "prod 프로파일에서 app.auth.mode=mock 은 허용하지 않는다 — 무인증 세션 발급 경로가 열린다.");
        }
        // 🔴 prod 만이 아니다. test 프로파일이 아닌데 security-enabled=false 면 TestSecurityConfig
        //    (@Profile("test"))가 없어 SecurityFilterChain 빈이 0개가 되고, Boot 의
        //    SpringBootWebSecurityConfiguration 기본 체인(anyRequest().authenticated() + formLogin)이
        //    대신 뜬다 — 전 API 가 로그인 폼으로 튕기는 더 나쁜 상태다.
        if (!securityEnabled && !profiles.contains("test")) {
            throw new IllegalStateException(
                    "app.auth.security-enabled=false 는 test 프로파일에서만 허용한다 (활성 프로파일: "
                            + profiles + "). 운영 체인을 끄면 Boot 기본 formLogin 체인이 대신 뜬다.");
        }
        if ("mock".equals(mode)) {
            // D11 — 개발·CI 전용. 켜진 채로 배포되면 무인증 세션 발급 경로가 남는다(R12).
            org.slf4j.LoggerFactory.getLogger(SecurityConfig.class).warn(
                    "[auth] app.auth.mode=mock — POST /api/auth/mock-login 이 열려 있습니다. 운영 반영 금지.");
        }
    }

    /**
     * Step 3 의 운영 필터체인과 Step 3.5 의 테스트 전용 체인({@code TestSecurityConfig})은
     * {@code app.auth.security-enabled} 를 <b>배타 스위치</b>로 교대한다.
     *
     * <p>SS6 은 "any request 를 매칭하는 체인은 마지막이어야 한다"고 검증하므로 두 체인을 동시에 두고
     * 순서로 이기는 방식은 쓸 수 없다 — 반드시 교대다.
     */
    @Bean
    @ConditionalOnProperty(name = "app.auth.security-enabled", havingValue = "true", matchIfMissing = true)
    SecurityFilterChain filterChain(HttpSecurity http,
                                    SecurityContextRepository securityContextRepository,
                                    AuthorizationManager<RequestAuthorizationContext> swaggerAllowed)
            throws Exception {

        // D6 — Spring Security 6 의 기본 XorCsrfTokenRequestAttributeHandler 는 BREACH 대응으로
        //      토큰을 매 요청 인코딩해, 쿠키 값을 그대로 헤더에 싣는 SPA 는 403 이 난다.
        //      증상이 "GET 은 되는데 POST/PATCH 만 403" 이라 원인 찾기가 어렵다.
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        // 🔴 HttpOnly=false 는 의도다 — axios 가 쿠키를 읽어 X-XSRF-TOKEN 에 싣는다. Secure·SameSite 만 더한다.
        //    값은 세션 쿠키와 같은 키에서 읽는다. false 면 저장소 기본값(요청이 https 면 Secure)을 그대로 둔다.
        boolean cookieSecure = env.getProperty("server.servlet.session.cookie.secure", Boolean.class, true);
        String cookieSameSite = StringUtils.capitalize(env.getProperty("server.servlet.session.cookie.same-site", "lax"));
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> {
            cookie.sameSite(cookieSameSite);
            if (cookieSecure) {
                cookie.secure(true);
            }
        });

        http
                // 🔴 기존 WebMvcConfig 의 CORS 설정(/api/**, 5173·3000, allowCredentials)은
                //    HandlerMapping 단계라 필터체인보다 뒤다 — Security 를 켜면 preflight OPTIONS 가
                //    DispatcherServlet 에 닿기 전에 401 로 끊겨 그 설정이 조용히 죽는다.
                //    withDefaults() 는 CorsConfigurationSource 빈이 없으면 HandlerMappingIntrospector 를
                //    타 WebMvcConfig 의 설정을 그대로 재사용한다.
                .cors(Customizer.withDefaults())
                // Step 5 의 콜백이 saveContext() 로 쓰는 것과 **같은 인스턴스**를 주입받는다
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .csrf(c -> c.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                        // 🔴 TSK-4027 D12 — 내부 API 는 세션·쿠키를 쓰지 않는다(배치가 부른다).
                        //    CSRF 토큰을 받을 창구가 없으므로 면제한다. 인증은 X-API-Key 가 진다.
                        //    🔴 permitAll 은 CSRF 면제가 아니다 — 이 줄이 없으면 POST 가 403 이다.
                        .ignoringRequestMatchers("/api/internal/**"))
                // 🔴 D6 — SS6 은 CSRF 토큰을 지연 생성한다. 아무도 토큰을 읽지 않는 GET 은
                //    XSRF-TOKEN 쿠키가 아예 안 내려가 SPA 의 첫 POST 가 403 이 된다.
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                // 🔴 TSK-4027 D12 — /api/internal/** 전용 API Key 검사. @Component 로 두면
                //    Boot 가 모든 경로에 자동 등록해 actuator 헬스체크까지 막으므로 여기서만 건다
                //    (필터 자신도 shouldNotFilter() 로 그 경로 밖 요청을 통과시킨다).
                .addFilterBefore(new InternalApiKeyFilter(
                                env.getProperty("app.internal-api.enabled", Boolean.class, Boolean.TRUE),
                                env.getProperty("app.internal-api.key", "")),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a                                   // D10
                        .requestMatchers("/api/auth/authorize-url", "/api/auth/callback").permitAll()
                        // TSK-4134 — 이메일/비밀번호 직접 로그인. 인증 전에 부르는 경로라 열어 둔다.
                        // 🔴 permitAll 은 **CSRF 면제가 아니다** — 이 POST 도 X-XSRF-TOKEN 헤더가
                        //    없으면 403 이다(프론트는 XSRF-TOKEN 쿠키 값을 그대로 실어 보낸다).
                        //    🔴 이 목록이 permitAll 의 정본이다 — 다른 곳에 공개 경로를 적지 않는다.
                        .requestMatchers("/api/auth/login").permitAll()
                        // D11 — mock 모드에서만 컨트롤러가 존재한다(아니면 404). matcher 는 항상 열어 둔다.
                        // 🔴 permitAll 은 CSRF 면제가 아니다.
                        .requestMatchers("/api/auth/mock-login").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // 🔴 /swagger-ui/** 만으로는 /swagger-ui.html 이 안 잡힌다 —
                        //    application.yml 의 springdoc.swagger-ui.path 가 /swagger-ui.html 이다.
                        //    안 잡히면 anyRequest().permitAll() 로 새어 prod 에서도 열린다.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .access(swaggerAllowed)                                 // local·dev 만 (prod 차단)
                        .requestMatchers("/actuator/**").authenticated()        // health 외 (D10)
                        // 🔴 TSK-4027 D12 — 배치가 부르는 내부 API. 세션 인증이 아니라
                        //    InternalApiKeyFilter 의 X-API-Key 가 인증을 진다(fail-closed).
                        //    🔴 이 줄은 아래 `/api/**` authenticated **앞**에 있어야 한다 —
                        //    매처는 선언 순서로 평가돼, 뒤에 두면 /api/** 가 먼저 잡아 401 이다.
                        .requestMatchers("/api/internal/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(   // D7
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/api/**")))
                // 🔴 D9 — 로그아웃은 LogoutFilter 가 아니라 Step 5 의 AuthController 가 처리한다.
                //    logoutUrl("/api/auth/logout") 을 걸면 필터가 먼저 가로채 같은 URL 의 컨트롤러
                //    핸들러가 죽은 코드가 되고, OidcClientInitiatedLogoutSuccessHandler 가 내는 302 를
                //    axios XHR 이 따라가다 IdP 오리진에서 CORS 로 깨진다.
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * Swagger 는 local·dev 에서만 연다 (D10 — prod 는 스펙 노출 차단).
     */
    @Bean
    AuthorizationManager<RequestAuthorizationContext> swaggerAllowed(Environment environment) {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean allowed = profiles.contains("local") || profiles.contains("dev");
        return (authentication, context) -> new AuthorizationDecision(allowed);
    }

    /**
     * 🔴 이 체인과 Step 5 의 콜백이 <b>같은 인스턴스</b>를 주입받아야 한다 —
     * Spring Security 6 은 {@code SecurityContext} 를 세션에 자동 저장하지 않아,
     * 콜백이 직접 {@code saveContext()} 를 부른다. 인스턴스가 갈리면 콜백 응답은 200 인데
     * 다음 요청이 401 이 된다.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
