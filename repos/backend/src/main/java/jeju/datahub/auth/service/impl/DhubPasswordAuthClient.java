package jeju.datahub.auth.service.impl;

import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;

import jeju.datahub.auth.service.AuthProperties;

/**
 * 이메일/비밀번호 직접 로그인 — DHub2 Manager {@code POST /api/v1/auth/login} + {@code GET /userinfo}
 * (TSK-4134 Step 1).
 *
 * <h2>계약</h2>
 * 입력은 이메일/비밀번호, 출력은 {@link PasswordLoginResult} — <b>claim 맵 + 토큰 3종</b>이다.
 * {@code TB_USER} upsert 는 이 클래스가 하지 않는다 — 기존
 * {@link jeju.datahub.auth.service.AuthUserSyncService} 가 claim 맵을 받아 그대로 처리한다(§2.3).
 * 세션 확립·이력 기록·응답 조립은 컨트롤러(Step 2)의 몫이다.
 *
 * <h2>🔴 D2 — {@code /userinfo} 호출을 생략하지 않는다</h2>
 * Manager 가 주는 것은 {@code id_token} 이 아니라 <b>access token</b> 이고({@code typ=at+jwt}),
 * 그 {@code aud} 에는 우리 {@code client_id} 가 없어 <b>표준 id_token 검증이 성립하지 않는다</b>(§2.2).
 * 서명을 우리가 검증하는 대신, <b>Manager 의 {@code /userinfo} 가 200 을 주는 것 자체를 검증으로
 * 삼는다</b>. 그래서 {@code /userinfo} 호출이 <b>이 경로의 유일한 검증 지점</b>이며 생략하면
 * 위조 토큰을 그대로 믿게 된다. 같은 이유로 <b>서명을 검증하는 JWT 디코더</b>(스프링 시큐리티의
 * Nimbus 기반 디코더)를 쓰지 않는다 —
 * 아래 {@code exp}/{@code sub} 읽기는 {@link JWTParser} 의 <b>무검증 파싱</b>이다.
 *
 * <h2>🔴 {@code sub}·{@code preferred_username} 을 명시 검증한다</h2>
 * {@code AuthUserSyncService} 의 javadoc 은 <i>"{@code /userinfo} 단독을 넘기지 않는다 —
 * {@code preferred_username} 이 비면 {@code TB_USER.user_id} NOT NULL 위반"</i> 이라고 못박았다.
 * 이 경로에는 {@code id_token} 이 없어 <b>{@code /userinfo} 단독으로 넘길 수밖에 없으므로</b>,
 * {@code sync()} 를 부르기 전에 두 claim 의 존재를 여기서 검증하고 없으면
 * {@link PasswordLoginException.Reason#USERINFO_EMPTY} 로 실패시킨다.
 * 🔴 조용한 {@code sha256} 폴백을 두지 않는다 — 같은 {@code sub} 가 실행마다 다른 {@code user_id} 로
 * 갈려 사용자 행이 쪼개진다.
 *
 * <h2>🔴 토큰 2종은 응답 본문에 없다</h2>
 * <ul>
 *   <li>{@code refresh_token} 은 <b>{@code Set-Cookie} 로만</b> 온다(§2.4) — 본문에 없다.
 *       그래서 응답을 {@link ResponseEntity} 로 받아 헤더에서 직접 뽑는다.</li>
 *   <li>수명(초)도 본문에 없다 — access token payload 의 {@code exp - iat} 로 구한다.
 *       🔴 상수를 하드코딩하지 않는다(수명이 바뀌면 조용히 어긋난다).</li>
 * </ul>
 * 둘 중 하나라도 비면 Step 2.5 의 갱신이 통째로 죽으므로 <b>레코드 조립 시점에 검증해 실패</b>시킨다.
 *
 * <h2>🔒 D6 — 로깅</h2>
 * <b>비밀번호와 토큰은 어떤 레벨로도 남기지 않는다.</b> 남기는 것은 수명(초)·쿠키 유무·HTTP status 뿐이다.
 * {@link RestClientException} 의 메시지에는 <b>응답 본문이 실리므로</b> 예외로 옮길 때
 * <b>타입 이름만</b> 옮기고 원인은 cause 로 전달한다(선례: 배치 {@code DhubAuthClient}).
 * {@link PasswordLoginException} 의 메시지는 사유 코드 이름 고정이라 본문이 섞일 자리가 없다.
 *
 * <h2>🔴 생성자가 둘인 이유</h2>
 * 운영 생성자가 {@code requestFactory(...)} 를 부르는데 {@code DefaultRestClientBuilder#build()} 는
 * 그 시점의 팩토리를 <b>스냅샷</b>한다. {@code MockRestServiceServer.bindTo(builder)} 도 같은 자리에
 * 목 팩토리를 심으므로, 운영 생성자만 있으면 목이 타임아웃 팩토리에 덮여 테스트가 성립하지 않는다
 * (선례: {@code AgentApiClient}). 그래서 조립된 {@link RestClient} 를 그대로 받는 package-private
 * 생성자를 따로 둔다. 🔴 운영 생성자의 {@code @Autowired} 는 필수다 — 생성자가 2개라 없으면
 * 컨테이너가 no-arg 를 찾다 기동에 실패한다.
 */
@Component
public class DhubPasswordAuthClient {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(DhubPasswordAuthClient.class);

    /**
     * 🔴 로그 접두는 {@code [pw-login]} 이다 — {@code password} 라는 낱말을 로그 문자열에 두지 않는다.
     * D6 회귀를 grep 한 줄로 확인할 수 있게 하려는 것이다.
     */
    private static final String LOG_TAG = "[pw-login] ";

    /** Manager 의 이메일/비밀번호 로그인 경로 (§2.1 실측). base 는 issuer 에서 얻는다. */
    static final String LOGIN_PATH = "/api/v1/auth/login";

    /**
     * Manager 의 토큰 갱신 경로 (TSK-4121 §3-1 실측 2026-09-03 · TSK-4134 Step 2.5).
     *
     * <p>🔴 {@code refresh_token} 을 <b>본문이 아니라 쿠키로</b> 받는다 —
     * 쿠키 없이 부르면 {@code 401 {"detail":"Missing refresh token"}} 이다.
     */
    static final String REFRESH_PATH = "/api/v1/auth/refresh";

    /** 🔴 응답 본문이 아니라 {@code Set-Cookie} 로 오는 값의 이름 (§2.4). */
    static final String REFRESH_COOKIE_NAME = "refresh_token";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient rest;
    private final ClientRegistrationRepository repo;
    private final AuthProperties props;
    /**
     * 🔴 TSK-4163 Step 5 — 롤 판정 값({@code type}) 조달. {@code /userinfo} 에는 없고
     * Manager 자체 API {@code GET /api/v1/auth/me} 의 응답 필드다(D8). 실패는 전부 삼켜지므로
     * 이 의존성이 로그인을 깨뜨리지 않는다(D9).
     */
    private final DhubUserProfileClient profileClient;

    /**
     * 🔴 {@code @Autowired} 필수 — 생성자가 2개다.
     *
     * <p>🔴 타임아웃을 반드시 준다(계획서 Step 1). 기본값이 없는 {@code RestClient} 는 무한 대기라
     * Manager 장애가 요청 스레드 고갈로 번진다. connect·read 를 하나로 합치지 않는다 —
     * 최악 경로는 둘의 합이다(선례: {@code AgentProperties}).
     */
    @Autowired
    public DhubPasswordAuthClient(
            RestClient.Builder builder,
            ClientRegistrationRepository repo,
            AuthProperties props,
            DhubUserProfileClient profileClient,
            @Value("${app.auth.password-login.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.auth.password-login.read-timeout-ms:5000}") int readTimeoutMs) {
        this(builder.requestFactory(timeoutFactory(connectTimeoutMs, readTimeoutMs)).build(),
                repo, props, profileClient);
    }

    /** 🔴 목 주입 지점(package-private) — 조립이 끝난 클라이언트를 그대로 받는다. */
    DhubPasswordAuthClient(RestClient rest, ClientRegistrationRepository repo, AuthProperties props,
                           DhubUserProfileClient profileClient) {
        this.rest = rest;
        this.repo = repo;
        this.props = props;
        this.profileClient = profileClient;
    }

    private static ClientHttpRequestFactory timeoutFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        rf.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return rf;
    }

    // ------------------------------------------------------------------ 계약 타입

    /**
     * 로그인 결과 — <b>claim 맵 + 토큰</b> (🔴 D7 개정 2026-09-07).
     *
     * <p>초안의 "토큰을 버린다" 는 폐기됐다 — Agent API 는 <b>이 경로의 토큰으로만</b> 통과한다
     * ({@code aud} 에 {@code dhub2-agent} 가 있다, §2.4). {@code accessToken}/{@code refreshToken}/
     * {@code expiresInSec} 는 Step 2.5 가 {@code AgentTokenStore} 로 넘긴다.
     *
     * <p>🔴 <b>조립 시점 검증</b> — {@code refreshToken} 이 비거나 {@code expiresInSec} 가 0 이하면
     * Step 2.5 의 갱신이 통째로 죽는다. 조용히 통과시키면 15분 뒤 어시스턴트가 이유 없이 멈추므로
     * 여기서 {@link PasswordLoginException.Reason#TOKEN_EXCHANGE_FAILED} 로 끊는다.
     * 🔴 {@code toString()} 을 재정의해 토큰을 마스킹한다 — 레코드 기본 {@code toString()} 은
     * 모든 필드를 그대로 찍어 로그·예외 경로가 곧 유출 경로가 된다(D6).
     */
    public record PasswordLoginResult(Map<String, Object> claims, String accessToken,
                                      String refreshToken, long expiresInSec) {

        public PasswordLoginResult {
            if (claims == null || claims.isEmpty()) {
                throw new PasswordLoginException(PasswordLoginException.Reason.USERINFO_EMPTY);
            }
            if (!StringUtils.hasText(accessToken)
                    || !StringUtils.hasText(refreshToken)
                    || expiresInSec <= 0) {
                // 🔴 어떤 값이 비었는지는 예외에 싣지 않는다 — 값 자체가 비밀이다.
                throw new PasswordLoginException(PasswordLoginException.Reason.TOKEN_EXCHANGE_FAILED);
            }
        }

        /** 🔴 토큰을 찍지 않는다 — claim 키 목록과 수명만 남긴다. */
        @Override
        public String toString() {
            return "PasswordLoginResult[claims=" + claims.keySet()
                    + ", accessToken=****, refreshToken=****, expiresInSec=" + expiresInSec + "]";
        }
    }

    /**
     * 이 경로의 단일 실패 타입.
     *
     * <p>🔴 메시지는 {@code reason.name()} <b>고정</b>이다(선례: {@code AgentApiException}) —
     * 예외 메시지는 로그·에러 응답으로 흘러 나가는 경로라 upstream 응답 본문을 실으면 그대로 유출된다.
     * 사유 코드 이름은 {@code LgnHstryServiceImpl} 의 화이트리스트와 <b>같은 철자</b>로 맞춰 두었다 —
     * Step 2 가 {@code recordFailure(..., reason.name())} 로 그대로 넘길 수 있게 하기 위해서다
     * (🔴 {@code PASSWORD_MISMATCH} 는 Step 2 에서 그 목록에 먼저 등재해야 한다 — 안 하면 {@code UNKNOWN}
     * 으로 접힌다).
     */
    public static class PasswordLoginException extends RuntimeException {

        public enum Reason {
            /** Manager 가 401 — 비밀번호 오류·없는 계정이 <b>같은 응답</b>이다(§2.1 · D5). */
            PASSWORD_MISMATCH,
            /** {@code /userinfo} 가 비었거나 {@code sub}/{@code preferred_username} 이 없다. */
            USERINFO_EMPTY,
            /** {@code /userinfo} 의 {@code sub} 가 access token 의 {@code sub} 와 다르다(토큰 치환 방어). */
            SUB_MISMATCH,
            /** 로그인 호출 자체가 실패했거나 응답이 계약과 다르다(쿠키·수명 부재 포함). */
            TOKEN_EXCHANGE_FAILED,
            /** 그 밖. */
            UNKNOWN
        }

        private final Reason reason;

        public PasswordLoginException(Reason reason) {
            this(reason, null);
        }

        public PasswordLoginException(Reason reason, Throwable cause) {
            super(reason.name(), cause);   // 🔴 메시지에 응답 본문·비밀번호·토큰을 넣지 않는다
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }

    // ------------------------------------------------------------------ 공개 API

    /**
     * 이메일/비밀번호로 Manager 에 로그인하고 {@code /userinfo} 로 신원을 확정한다.
     *
     * @param email       사용자 이메일 (🔴 POST 본문으로만 전달한다 — 쿼리스트링 금지, D6)
     * @param rawPassword 원문 비밀번호. <b>어디에도 저장·로깅하지 않는다</b>(D1 · D6)
     * @return claim 맵 + 토큰 3종
     * @throws PasswordLoginException 인증 실패 — 사유는 {@link PasswordLoginException#getReason()}
     */
    public PasswordLoginResult authenticate(String email, String rawPassword) {
        ClientRegistration reg = registration();

        // ① 로그인 — 🔴 응답을 ResponseEntity 로 받는다. refresh_token 이 Set-Cookie 에만 있어서다(§2.4).
        ResponseEntity<Map<String, Object>> res = login(reg, email, rawPassword);

        Map<String, Object> body = res.getBody();
        String accessToken = (body == null) ? null : asText(body.get("access_token"));
        if (!StringUtils.hasText(accessToken)) {
            log.warn(LOG_TAG + "Manager 로그인 응답에 자격 값이 없다 — 계약이 바뀌었는지 확인이 필요하다.");
            throw new PasswordLoginException(PasswordLoginException.Reason.TOKEN_EXCHANGE_FAILED);
        }

        // ② Set-Cookie 에서 갱신용 값을 뽑는다 — 본문에 없다.
        String refreshToken = extractRefreshCookie(res.getHeaders());

        // ③ 수명은 payload 의 exp - iat 다 (🔴 상수 하드코딩 금지 · 🔴 서명 검증이 아니다 — D2)
        JWTClaimsSet payload = parseUnverified(accessToken);
        long expiresInSec = lifetimeSeconds(payload);

        // ④ 🔴 D2 — 유일한 검증 지점. 생략하면 위조 토큰을 그대로 믿게 된다.
        Map<String, Object> fetched = fetchUserInfo(reg, accessToken);
        if (fetched == null || fetched.isEmpty()) {
            throw new PasswordLoginException(PasswordLoginException.Reason.USERINFO_EMPTY);
        }
        // 🔴 가변 사본으로 바꾼다 — ⑦ 의 type 주입이 이 맵에 쓴다(변환기가 준 맵은 불변일 수 있다).
        Map<String, Object> claims = new LinkedHashMap<>(fetched);

        // ⑤ 🔴 AuthUserSyncService 가 요구하는 두 claim 을 명시 검증한다(조용한 폴백 금지).
        String sub = asText(claims.get("sub"));
        String username = asText(claims.get(props.getUsernameClaim()));
        if (!StringUtils.hasText(sub) || !StringUtils.hasText(username)) {
            log.warn(LOG_TAG + "userinfo 에 필수 claim 이 없다 — sub={}, {}={}",
                    StringUtils.hasText(sub) ? "present" : "absent",
                    props.getUsernameClaim(), StringUtils.hasText(username) ? "present" : "absent");
            throw new PasswordLoginException(PasswordLoginException.Reason.USERINFO_EMPTY);
        }

        // ⑥ 🔴 OIDC 콜백과 같은 규약 — userinfo 의 sub 와 payload 의 sub 를 대조한다(토큰 치환 방어).
        String payloadSub = (payload == null) ? null : payload.getSubject();
        if (!sub.equals(payloadSub)) {
            log.warn(LOG_TAG + "userinfo 의 sub 가 자격 payload 의 sub 와 다르다.");
            throw new PasswordLoginException(PasswordLoginException.Reason.SUB_MISMATCH);
        }

        // ⑦ 🔴 TSK-4163 Step 5 — 롤 판정 값(type)을 claim 맵에 주입한다(D8).
        //    {@code /userinfo} 에는 없고 Manager 자체 API 의 응답 필드라 여기서 한 번 더 왕복한다.
        //    🔴 신원 검증(⑥)이 끝난 뒤에 부른다 — 검증 전 토큰으로 외부를 찌르지 않는다.
        //    🔴 sync(claims) 는 claim 맵만 보는 계약 그대로다 — audience 가 풀려 claim 으로 오면
        //       이 한 줄만 걷어내면 된다. 실패는 전부 삼켜져 기존 롤이 유지된다(D3 · D9).
        profileClient.injectType(claims, accessToken);

        // 🔴 남기는 것은 수명과 쿠키 유무뿐이다(D6).
        log.info(LOG_TAG + "인증 성공 — 수명={}초, 갱신쿠키={}",
                expiresInSec, StringUtils.hasText(refreshToken) ? "present" : "absent");

        // 🔴 조립 시점 검증 — refresh 값·수명이 비면 여기서 끊긴다.
        return new PasswordLoginResult(claims, accessToken, refreshToken, expiresInSec);
    }

    /**
     * password 세션의 Agent 토큰 갱신 — {@code SessionTokenRefresher} 가 세션 {@code AUTH_METHOD=password}
     * 일 때 위임한다(TSK-4271 D6). 세션에 보관해 둔 {@code refresh_token} 으로 새 access_token 을 받아 오고,
     * 응답에 새 {@code Set-Cookie} 가 오면(회전) 반환값의 {@code refreshToken} 에 싣는다.
     * 실패는 예외로 던진다 — 호출부({@code AgentTokenStore})가 503 으로 바꾼다. 🔴 refresh 값 로깅 금지.
     *
     * <h2>🔴 세션에 담아 둔 값을 <b>쿠키 헤더로 되돌려준다</b> (TSK-4134 Step 2.5 · §2.4)</h2>
     * 브라우저 쿠키가 아니다 — 브라우저는 Manager 를 직접 부르지 않는다(D1). 백엔드가
     * {@link jeju.datahub.agent.service.AgentTokenStore} 에 보관해 둔 값을 <b>자기 요청의
     * {@code Cookie} 헤더</b>로 실어 보낸다. 그래서 이 클라이언트에는 쿠키 저장소가 필요 없다
     * (배치 {@code DhubAuthClient} 가 갱신을 포기한 이유였던 그 제약을 세션이 대신 진다).
     *
     * <h2>🔴 Step 1 의 부품을 그대로 재사용한다</h2>
     * {@link #extractRefreshCookie}(회전 대비) · {@link #parseUnverified} · {@link #lifetimeSeconds}
     * (🔴 수명은 {@code exp - iat} — 상수 하드코딩 금지) · {@link #registration}. 같은 로직을 두 번
     * 적지 않는다 — 갈리면 로그인과 갱신이 다른 규칙으로 동작한다.
     *
     * <h2>🔴 왜 갱신 로직이 이 클래스에 있나 — 🔁 TSK-4271 이후 {@code TokenRefresher} 구현은 아니다</h2>
     * 갱신에 필요한 것(issuer · {@link RestClient} · 쿠키 파싱 · 수명 계산)이 전부 여기 있다.
     * 새 클라이언트를 만들면 타임아웃·로깅 규약이 두 벌이 된다. TSK-4271 이후 {@code TokenRefresher}
     * 구현은 {@code SessionTokenRefresher}(로그인 방식별 위임)가 지고, 이 클래스는 <b>password 갈래의
     * 위임 대상</b>이다 — 그래서 {@code implements} 없이 public 메서드로 남는다.
     */
    public OAuth2AccessTokenResponse refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new PasswordLoginException(PasswordLoginException.Reason.TOKEN_EXCHANGE_FAILED);
        }
        ClientRegistration reg = registration();
        String issuer = reg.getProviderDetails().getIssuerUri();
        if (!StringUtils.hasText(issuer)) {
            throw new IllegalStateException("issuer-uri 가 비어 있어 갱신 URL 을 만들 수 없습니다.");
        }
        URI target = UriComponentsBuilder.fromUriString(issuer).path(REFRESH_PATH).build().encode().toUri();

        ResponseEntity<Map<String, Object>> res;
        try {
            res = rest.post()
                    .uri(target)
                    // 🔴 값을 본문이 아니라 Cookie 헤더로 보낸다 — 없으면 401 "Missing refresh token".
                    .header(HttpHeaders.COOKIE, REFRESH_COOKIE_NAME + "=" + refreshToken)
                    .retrieve()
                    .toEntity(JSON_MAP);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // 🔴 status 만 남긴다 — 본문·토큰을 옮기지 않는다(D6).
            log.warn(LOG_TAG + "갱신 실패 — status={}", e.getStatusCode().value());
            throw new PasswordLoginException(PasswordLoginException.Reason.TOKEN_EXCHANGE_FAILED, e);
        } catch (RestClientException e) {
            log.warn(LOG_TAG + "갱신 호출 실패 — cause={}", e.getClass().getSimpleName());
            throw new PasswordLoginException(PasswordLoginException.Reason.TOKEN_EXCHANGE_FAILED, e);
        }

        Map<String, Object> body = res.getBody();
        String accessToken = (body == null) ? null : asText(body.get("access_token"));
        // 🔴 파싱 전에 존재부터 본다 — JWTParser 는 null 을 ParseException 으로 접어 주지 않는다.
        long expiresInSec = StringUtils.hasText(accessToken)
                ? lifetimeSeconds(parseUnverified(accessToken))
                : 0L;
        if (!StringUtils.hasText(accessToken) || expiresInSec <= 0) {
            // 🔴 어떤 값이 비었는지는 예외에 싣지 않는다 — 값 자체가 비밀이다.
            log.warn(LOG_TAG + "갱신 응답이 계약과 다르다 — 재로그인이 필요하다.");
            throw new PasswordLoginException(PasswordLoginException.Reason.TOKEN_EXCHANGE_FAILED);
        }

        // 🔴 회전 대비 — 새 Set-Cookie 가 오면 그 값을 실어 보관값을 교체하게 한다.
        //    없으면(대개 이쪽) refreshToken 을 비워 둔다 → AgentTokenStore.save 가 종전 값을 남긴다.
        String rotated = extractRefreshCookie(res.getHeaders());

        // 🔴 남기는 것은 수명과 회전 여부뿐이다(D6).
        log.info(LOG_TAG + "갱신 성공 — 수명={}초, 갱신쿠키회전={}",
                expiresInSec, StringUtils.hasText(rotated) ? "yes" : "no");

        OAuth2AccessTokenResponse.Builder builder = OAuth2AccessTokenResponse.withToken(accessToken)
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(expiresInSec);
        if (StringUtils.hasText(rotated)) {
            builder.refreshToken(rotated);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ private

    /**
     * base URL 은 {@code ClientRegistration} 의 issuer 에서 얻는다 — 🔴 하드코딩 금지(환경마다 다르다).
     */
    private ClientRegistration registration() {
        ClientRegistration reg = repo.findByRegistrationId(props.getRegistrationId());
        if (reg == null) {
            // 설정 오류다 — 인증 실패가 아니므로 401 사유 코드로 접지 않는다.
            throw new IllegalStateException(
                    "ClientRegistration 을 찾을 수 없습니다: " + props.getRegistrationId());
        }
        return reg;
    }

    private ResponseEntity<Map<String, Object>> login(ClientRegistration reg, String email, String rawPassword) {
        String issuer = reg.getProviderDetails().getIssuerUri();
        if (!StringUtils.hasText(issuer)) {
            throw new IllegalStateException("issuer-uri 가 비어 있어 로그인 URL 을 만들 수 없습니다.");
        }
        URI target = UriComponentsBuilder.fromUriString(issuer).path(LOGIN_PATH).build().encode().toUri();
        try {
            return rest.post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 🔴 자격증명은 본문으로만 보낸다 — 쿼리스트링이면 액세스 로그에 URL 이 통째로 남는다(D6).
                    .body(Map.of("email", email, "password", rawPassword))
                    .retrieve()
                    .toEntity(JSON_MAP);
        } catch (HttpClientErrorException.Unauthorized e) {
            // 🔴 본문({"detail":"Invalid email or password"})을 옮기지 않는다. 비밀번호 오류와
            //    없는 계정이 같은 응답이라 우리도 그 이상을 구분하지 않는다(§2.1 · D5).
            log.warn(LOG_TAG + "Manager 가 자격증명을 거절했다 — status=401");
            throw new PasswordLoginException(PasswordLoginException.Reason.PASSWORD_MISMATCH);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // 🔴 status 만 남긴다 — 본문에는 내부 정보가 섞인다.
            log.warn(LOG_TAG + "Manager 로그인 실패 — status={}", e.getStatusCode().value());
            throw new PasswordLoginException(PasswordLoginException.Reason.TOKEN_EXCHANGE_FAILED, e);
        } catch (RestClientException e) {
            // 타임아웃·네트워크·본문 변환 실패. 🔴 메시지에 URL·본문이 섞이므로 타입 이름만 남긴다.
            log.warn(LOG_TAG + "Manager 로그인 호출 실패 — cause={}", e.getClass().getSimpleName());
            throw new PasswordLoginException(PasswordLoginException.Reason.TOKEN_EXCHANGE_FAILED, e);
        }
    }

    /**
     * {@code Set-Cookie: refresh_token=…; HttpOnly; Max-Age=604800; …} 에서 값만 뽑는다(§2.4).
     *
     * <p>🔴 {@code java.net.HttpCookie} 에 맡기지 않는다 — Manager 가 붙이는 {@code SameSite} 는
     * 그 파서가 모르는 속성이라 구현·JDK 판에 따라 동작이 갈린다. 첫 조각({@code name=value})만
     * 보는 방식이 계약(이름 하나)에 정확히 맞고 결과가 결정적이다.
     *
     * @return 값. 헤더가 없거나 이름이 다르면 {@code null}(→ 레코드 조립에서 실패한다)
     */
    private static String extractRefreshCookie(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        for (String setCookie : setCookies) {
            if (setCookie == null) {
                continue;
            }
            String nameValue = setCookie.split(";", 2)[0].trim();   // 속성 앞의 첫 조각만이 name=value
            int eq = nameValue.indexOf('=');
            if (eq > 0 && REFRESH_COOKIE_NAME.equals(nameValue.substring(0, eq).trim())) {
                String value = nameValue.substring(eq + 1).trim();
                return StringUtils.hasText(value) ? value : null;
            }
        }
        return null;
    }

    /**
     * payload 를 <b>무검증</b>으로 읽는다.
     *
     * <p>🔴 <b>서명을 검증하는 디코더</b>(스프링 시큐리티의 Nimbus 기반 JWT 디코더)를 쓰지 않는다 —
     * 이 토큰의 {@code aud} 에 우리 {@code client_id}
     * 가 없어 규격 검증 자체가 성립하지 않고(§2.2), 검증은 {@code /userinfo} 가 진다(D2).
     * 여기서 읽는 것은 수명 계산과 {@code sub} 대조에 쓸 값뿐이다.
     */
    private JWTClaimsSet parseUnverified(String jwt) {
        try {
            JWT parsed = JWTParser.parse(jwt);
            return parsed.getJWTClaimsSet();
        } catch (ParseException e) {
            // 🔴 토큰 값·예외 메시지를 남기지 않는다 — 메시지에 토큰 조각이 섞인다.
            log.warn(LOG_TAG + "자격 payload 를 읽지 못했다 — 형식이 JWT 가 아니다.");
            return null;
        }
    }

    /**
     * 수명(초) = {@code exp - iat}. 🔴 상수를 하드코딩하지 않는다 — 수명이 바뀌면 조용히 어긋난다.
     *
     * @return 초. 두 claim 중 하나라도 없으면 {@code 0} → 레코드 조립에서 실패한다
     */
    private static long lifetimeSeconds(JWTClaimsSet payload) {
        if (payload == null) {
            return 0L;
        }
        Date exp = payload.getExpirationTime();
        Date iat = payload.getIssueTime();
        if (exp == null || iat == null) {
            return 0L;
        }
        return (exp.getTime() - iat.getTime()) / 1000L;
    }

    /**
     * {@code /userinfo} 를 Bearer GET 한다 — 🔴 D2 의 검증 지점 (선례: {@code AuthController#fetchUserInfo}).
     */
    private Map<String, Object> fetchUserInfo(ClientRegistration reg, String accessToken) {
        String uri = reg.getProviderDetails().getUserInfoEndpoint().getUri();
        if (!StringUtils.hasText(uri)) {
            throw new IllegalStateException("user-info-uri 가 비어 있어 신원을 확인할 수 없습니다.");
        }
        // 🔴 TSK-4349 — 한시적 진단 로그(스위치 OFF 면 찍지 않는다). 예외 변환은 그대로다.
        boolean diag = props.isDiagLogEnabled();
        AuthDiagLog.userinfoRequest(diag, "password", uri, accessToken);
        long started = System.nanoTime();
        try {
            ResponseEntity<Map<String, Object>> res = rest.get()
                    .uri(URI.create(uri))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(JSON_MAP);
            AuthDiagLog.userinfoResponse(diag, "password", res.getStatusCode().value(),
                    (System.nanoTime() - started) / 1_000_000L, res.getBody());
            return res.getBody();
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            AuthDiagLog.userinfoFailure(diag, "password", e.getStatusCode().value(),
                    (System.nanoTime() - started) / 1_000_000L, e.getResponseBodyAsString(), e.getClass().getSimpleName());
            log.warn(LOG_TAG + "userinfo 조회 실패 — status={}", e.getStatusCode().value());
            throw new PasswordLoginException(PasswordLoginException.Reason.USERINFO_EMPTY, e);
        } catch (RestClientException e) {
            AuthDiagLog.userinfoFailure(diag, "password", null,
                    (System.nanoTime() - started) / 1_000_000L, null, e.getClass().getSimpleName());
            log.warn(LOG_TAG + "userinfo 호출 실패 — cause={}", e.getClass().getSimpleName());
            throw new PasswordLoginException(PasswordLoginException.Reason.USERINFO_EMPTY, e);
        }
    }

    private static String asText(Object value) {
        return (value == null) ? null : value.toString();
    }
}
