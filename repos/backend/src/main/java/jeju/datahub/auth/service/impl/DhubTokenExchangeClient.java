package jeju.datahub.auth.service.impl;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientTokenExchangeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.TokenExchangeGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import jeju.datahub.auth.service.AuthProperties;

/**
 * DHub2 Manager 토큰 교환(RFC 8693) · 로그인 토큰 갱신 클라이언트.
 *
 * <h2>왜 필요한가</h2>
 * OIDC 로그인 access_token 은 SSO 전용이라 Agent API·D-Hub API 에 그대로 보내면 401 이다
 * (2026-10-21 실측 — Agent {@code audience mismatch} · {@code /api/v1/auth/me} {@code Invalid token}).
 * 그래서 백엔드가 client secret 으로 {@code grant_type=…:token-exchange} 를 호출해 대상별 토큰
 * ({@link #AUDIENCE_AGENT} · {@link #AUDIENCE_MANAGER})을 받아 쓴다.
 *
 * <h2>🔴 교환은 이 클래스 한 곳에서만 한다 · HTTP 요청을 직접 조립하지 않는다</h2>
 * 엔드포인트는 {@code ClientRegistration} 의 {@code tokenUri}, 자격증명은 {@code clientId}/{@code clientSecret}
 * 이다 — 🔴 호스트 하드코딩 금지. 요청은 SS 6.5 표준 클라이언트에 위임한다:
 * 교환은 {@link RestClientTokenExchangeTokenResponseClient}, 로그인 갱신은
 * {@link RestClientRefreshTokenTokenResponseClient}. 🔴 {@code Authorization: Basic} 을
 * {@code HttpHeaders#setBasicAuth} 로 손수 만들지 않는다 — 표준 헤더 변환기는 RFC 6749 2.3.1 대로
 * id/secret 을 form-urlencode 한 뒤 base64 하고, 콜백의 코드 교환도 같은 변환기를 탄다.
 * 손수 만들면 특수문자가 든 secret 에서 두 경로의 인코딩이 갈린다.
 *
 * <h2>🔴 교환 파라미터 보정</h2>
 * 표준 기본값({@code TokenExchangeGrantRequest.defaultParameters})은 {@code scope}(registration scopes)·
 * {@code requested_token_type} 을 싣고 {@code audience} 는 싣지 않는다. 명세(Dtonic 안내 §2)는 정확히
 * {@code grant_type}·{@code subject_token}·{@code subject_token_type}·{@code audience} 4개다.
 * 파이프라인은 {@code parametersConverter.convert(req)} → {@code parametersCustomizer.accept(params)} 순서이고
 * 🔴 converter 는 <b>추가만</b> 하고 제거하지 못하며, customizer 는 요청 객체를 받지 못한다. 그래서:
 * <ul>
 *   <li><b>audience(호출마다 다름)</b> — {@link AudienceTokenExchangeGrantRequest} 서브클래스로 값을 요청 객체에
 *       싣고 {@code addParametersConverter} 가 꺼낸다. 🔴 {@code ThreadLocal}·가변 필드 금지(호출 간에 섞인다).</li>
 *   <li><b>제거(고정)</b> — {@code setParametersCustomizer} 가 {@code scope}·{@code requested_token_type} 을 뺀다.
 *       상태 없는 고정 동작이라 동시 호출에 공유해도 안전하다.</li>
 * </ul>
 *
 * <h2>🔒 로깅</h2>
 * <b>토큰·secret 은 어떤 레벨로도 남기지 않는다.</b> 남기는 것은 audience·수명(초)·회전 여부·{@code error} 코드뿐이다.
 * 로그 태그는 {@code [token-exchange]} — E2E 판정이 이 INFO 줄을 센다
 * ({@code AgentTokenStore} 의 갱신 성공 로그는 DEBUG 라 판정에 못 쓴다).
 *
 * <h2>🔴 생성자가 둘인 이유</h2>
 * {@code requestFactory(타임아웃)} 는 <b>운영 생성자에서만</b> 건다 — 목 생성자에서 걸면
 * {@code MockRestServiceServer.bindTo(builder)} 가 심은 목 팩토리를 덮는다(선례:
 * {@code DhubPasswordAuthClient}). 대신 두 생성자 모두 {@link #oauth2(RestClient.Builder)} 를 거쳐
 * 테스트가 <b>운영과 같은 컨버터·에러 핸들러</b>를 검증한다. 🔴 두 생성자는 인자 개수가 달라야 한다
 * (5개 ↔ 3개) — 타임아웃을 {@code AuthProperties} 중첩 바인딩으로 받으면 시그니처가 같아져 컴파일 에러다.
 */
@Component
public class DhubTokenExchangeClient {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(DhubTokenExchangeClient.class);

    /** 🔴 E2E 판정이 세는 접두다 — 바꾸면 판정 grep 도 함께 바꾼다. */
    private static final String LOG_TAG = "[token-exchange] ";

    /** 에이전트 채팅용 교환 대상 (안내 §1). */
    public static final String AUDIENCE_AGENT = "dhub2-agent";

    /** D-Hub API({@code /api/v1/auth/me} 등)용 교환 대상 (안내 §1). */
    public static final String AUDIENCE_MANAGER = "dhub2-manager";

    /** 교환 요청 파라미터 이름 — SS 의 {@code OAuth2ParameterNames} 에 상수가 없다(RFC 8693 §2.1). */
    static final String PARAM_AUDIENCE = "audience";

    /** 입력이 비었을 때의 코드 — 업스트림을 부르지 않고 끊는다. */
    static final String ERROR_INVALID_REQUEST = "invalid_request";

    /**
     * 🔴 {@code OAuth2RefreshTokenGrantRequest} 가 notNull 로 요구하는 access token 자리채움.
     * 요청 파라미터에 실리지 않는다({@code defaultParameters} = {@code scope}(비면 생략) + {@code refresh_token}).
     */
    private static final OAuth2AccessToken UNUSED_ACCESS_TOKEN =
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "unused", null, null);

    private final ClientRegistrationRepository repo;
    private final AuthProperties props;
    private final RestClientTokenExchangeTokenResponseClient exchangeClient;
    private final RestClientRefreshTokenTokenResponseClient refreshClient;

    /**
     * 🔴 {@code @Autowired} 필수 — 생성자가 2개다.
     *
     * <p>🔴 타임아웃을 반드시 준다. 기본값이 없는 {@code RestClient} 는 무한 대기라 Manager 장애가
     * 로그인·AI 요청 스레드 고갈로 번진다. connect·read 를 하나로 합치지 않는다(선례: {@code DhubPasswordAuthClient}).
     * TLS 우회가 기본 {@code SSLContext} 에 설치되므로 같은 {@link SimpleClientHttpRequestFactory} 계열을 쓴다.
     */
    @Autowired
    public DhubTokenExchangeClient(
            RestClient.Builder builder,
            ClientRegistrationRepository repo,
            AuthProperties props,
            @Value("${app.auth.token-exchange.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.auth.token-exchange.read-timeout-ms:5000}") int readTimeoutMs) {
        this(builder.requestFactory(timeoutFactory(connectTimeoutMs, readTimeoutMs)),
                repo,
                props);
    }

    /** 🔴 목 주입 지점(package-private) — 조립 전 builder 를 받아 운영과 같은 {@link #oauth2} 를 거친다. */
    DhubTokenExchangeClient(
            RestClient.Builder builder,
            ClientRegistrationRepository repo,
            AuthProperties props) {
        this.repo = repo;
        this.props = props;
        RestClient rest = oauth2(builder).build();

        this.exchangeClient = new RestClientTokenExchangeTokenResponseClient();
        this.exchangeClient.setRestClient(rest);
        this.exchangeClient.addParametersConverter(DhubTokenExchangeClient::audienceParameter);
        this.exchangeClient.setParametersCustomizer(params -> {
            params.remove(OAuth2ParameterNames.SCOPE);
            params.remove(OAuth2ParameterNames.REQUESTED_TOKEN_TYPE);
        });

        this.refreshClient = new RestClientRefreshTokenTokenResponseClient();
        this.refreshClient.setRestClient(rest);
    }

    /**
     * 표준 클라이언트 기본 {@code RestClient} 와 같은 구성 — 🔴 빠뜨리면 응답 파싱
     * ({@code expires_in} → {@code expiresAt})·4xx 의 OAuth2 {@code error} 변환이 깨진다.
     */
    static RestClient.Builder oauth2(RestClient.Builder builder) {
        return builder
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler());
    }

    private static ClientHttpRequestFactory timeoutFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        rf.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return rf;
    }

    // ------------------------------------------------------------------ 공개 API

    /**
     * 로그인 access_token 을 대상({@code audience})용 토큰으로 교환한다.
     *
     * <p>🔴 응답에 {@code refresh_token} 은 없다(안내 §3) — 만료되면 같은 요청으로 다시 교환한다.
     *
     * @param subjectToken 로그인 access_token (🔴 refresh_token 불가 — 안내 §2)
     * @param audience     {@link #AUDIENCE_AGENT} 또는 {@link #AUDIENCE_MANAGER}
     * @throws DhubTokenExchangeException 실패 전부 — 삼킬지는 호출부가 정한다(vs)
     */
    public OAuth2AccessTokenResponse exchange(String subjectToken, String audience) {
        if (!StringUtils.hasText(subjectToken) || !StringUtils.hasText(audience)) {
            throw new DhubTokenExchangeException(ERROR_INVALID_REQUEST);
        }
        ClientRegistration reg = exchangeRegistration(registration());
        // 🔴 Jwt 로 감싸면 subject_token_type 이 …:jwt 가 되어 명세(…:access_token)와 갈린다.
        OAuth2AccessToken subject =
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, subjectToken, null, null);

        OAuth2AccessTokenResponse res;
        try {
            res = exchangeClient.getTokenResponse(new AudienceTokenExchangeGrantRequest(reg, subject, audience));
        } catch (OAuth2AuthorizationException e) {
            String code = errorCode(e);
            log.warn(LOG_TAG + "exchange failed audience={} error={}", audience, code);
            throw new DhubTokenExchangeException(code, e);
        }
        long expiresIn = expiresInSec(res);
        log.info(LOG_TAG + "exchange ok audience={} expiresIn={}s", audience, expiresIn);
        // 🔴 한시적 진단 로그(스위치 OFF 면 찍지 않는다). 교환으로 바뀐 토큰을 대조하는 용도다.
        AuthDiagLog.exchanged(props.isDiagLogEnabled(), audience, subjectToken,
                res.getAccessToken() == null ? null : res.getAccessToken().getTokenValue(), expiresIn);
        return res;
    }

    /**
     * 로그인 토큰 갱신. {@code grant_type=refresh_token}, 같은 {@code tokenUri}·Basic 인증.
     *
     * <p>🔴 Manager 자체 {@code /api/v1/auth/refresh}(쿠키) 로 보내지 않는다 — OIDC refresh 는 거기서 403 이다(10-21 실측).
     * 🔴 표준 클라이언트는 응답에 {@code refresh_token} 이 없으면 요청 값을 채워 넣으므로
     * {@code getRefreshToken()} 은 항상 non-null 이다 — 회전 여부는 요청 값과의 비교로만 판정한다.
     *
     * @throws DhubTokenExchangeException 실패 전부
     */
    public OAuth2AccessTokenResponse refreshLogin(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new DhubTokenExchangeException(ERROR_INVALID_REQUEST);
        }
        ClientRegistration reg = registration();
        OAuth2RefreshToken presented = new OAuth2RefreshToken(refreshToken, null);

        OAuth2AccessTokenResponse res;
        try {
            res = refreshClient.getTokenResponse(
                    new OAuth2RefreshTokenGrantRequest(reg, UNUSED_ACCESS_TOKEN, presented));
        } catch (OAuth2AuthorizationException e) {
            String code = errorCode(e);
            log.warn(LOG_TAG + "login refresh failed audience=- error={}", code);
            throw new DhubTokenExchangeException(code, e);
        }
        long expiresIn = expiresInSec(res);
        String rotation = isRotated(res, refreshToken) ? "rotated" : "kept";
        log.info(LOG_TAG + "login refresh ok expiresIn={} refresh={}", expiresIn, rotation);
        return res;
    }

    // ------------------------------------------------------------------ private

    /**
     * 교환 요청에 audience 를 싣는 서브클래스 — {@code TokenExchangeGrantRequest} 에는 audience 필드가 없다.
     * 🔴 불변 필드라 호출 간에 섞이지 않는다.
     */
    private static final class AudienceTokenExchangeGrantRequest extends TokenExchangeGrantRequest {

        private final String audience;

        AudienceTokenExchangeGrantRequest(
                ClientRegistration clientRegistration,
                OAuth2AccessToken subjectToken,
                String audience) {
            super(clientRegistration, subjectToken, null);
            this.audience = audience;
        }

        String audience() {
            return audience;
        }
    }

    /** {@code addParametersConverter} 용 — 기본 파라미터에 {@code audience} 하나를 더한다. */
    private static MultiValueMap<String, String> audienceParameter(TokenExchangeGrantRequest req) {
        if (req instanceof AudienceTokenExchangeGrantRequest a) {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add(PARAM_AUDIENCE, a.audience());
            return params;
        }
        return null;
    }

    private ClientRegistration registration() {
        ClientRegistration reg = repo.findByRegistrationId(props.getRegistrationId());
        if (reg == null) {
            // 설정 오류다 — 교환 실패 코드로 접지 않는다.
            throw new IllegalStateException(
                    "ClientRegistration 을 찾을 수 없습니다: " + props.getRegistrationId());
        }
        return reg;
    }

    /**
     * 🔴 교환용 registration 파생 — {@code TokenExchangeGrantRequest} 는 grant type 이
     * {@code TOKEN_EXCHANGE} 인지 assert 한다(기존 {@code dhub2} 는 {@code authorization_code}).
     */
    private static ClientRegistration exchangeRegistration(ClientRegistration reg) {
        return ClientRegistration.withClientRegistration(reg)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .build();
    }

    /** {@code error} 코드만 꺼낸다 — 🔴 description 에는 응답 본문이 섞이므로 쓰지 않는다. */
    private static String errorCode(OAuth2AuthorizationException e) {
        return (e.getError() == null || !StringUtils.hasText(e.getError().getErrorCode()))
                ? "unknown"
                : e.getError().getErrorCode();
    }

    /** 수명(초) — 표준 컨버터가 {@code expires_in} 으로 계산한 {@code expiresAt} 에서 되돌린다. */
    private static long expiresInSec(OAuth2AccessTokenResponse res) {
        OAuth2AccessToken token = res.getAccessToken();
        Instant expiresAt = token.getExpiresAt();
        Instant issuedAt = token.getIssuedAt();
        if (expiresAt == null || issuedAt == null) {
            return 0L;
        }
        return Duration.between(issuedAt, expiresAt).getSeconds();
    }

    /** 요청 refresh 값과 응답 값이 다를 때만 회전이다(응답에 없으면 표준 클라이언트가 요청 값을 채운다). */
    private static boolean isRotated(OAuth2AccessTokenResponse res, String presented) {
        OAuth2RefreshToken returned = res.getRefreshToken();
        return returned != null && !presented.equals(returned.getTokenValue());
    }
}
