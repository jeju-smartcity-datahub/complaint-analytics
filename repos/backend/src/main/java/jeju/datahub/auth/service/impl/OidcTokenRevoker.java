package jeju.datahub.auth.service.impl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import jeju.datahub.auth.service.AuthProperties;

/**
 * 로그아웃 시 IdP 토큰 폐기 — RFC 7009 {@code POST {revocation_endpoint}}.
 *
 * <h2>🔴 예외를 밖으로 던지지 않는다</h2>
 * IdP 장애·거절이 로그아웃을 막으면 안 된다. 4xx·5xx 는 상태·본문을 로그로 남기고, 전송 오류는 예외 <b>타입만</b>
 * 남긴 뒤 {@link Result} 로 돌려준다. 호출부({@code AuthController#logout})는 결과를 보지 않고 계속한다.
 *
 * <h2>엔드포인트</h2>
 * {@code app.auth.revocation-endpoint} → 비면 discovery {@code revocation_endpoint} → 둘 다 없으면 skip.
 *
 * <h2>클라이언트 인증</h2>
 * 등록값과 같은 {@code client_secret_basic}. SS 6.5 에 revoke 클라이언트가 없어 직접 싣는다 —
 * RFC 6749 2.3.1 대로 id·secret 을 form-urlencode 한 뒤 Basic 으로 보낸다.
 *
 * <h2>🔒 로깅</h2>
 * {@code 결과/skip/실패} 1줄은 항상 남긴다(토큰 없이). {@code 호출} 줄은 토큰 지문만 남기고, claim 은
 * {@code app.auth.diag-log-enabled} 가 켜졌을 때만 더한다(한시적, 제거). 토큰 원문은 남기지 않는다.
 * 🔴 {@code client_secret}·{@code Authorization} 헤더는 어떤 레벨로도 남기지 않는다.
 */
@Component
public class OidcTokenRevoker {

    private static final Logger log = LoggerFactory.getLogger(OidcTokenRevoker.class);

    static final String TAG = "[oidc-revoke] ";

    public static final String HINT_REFRESH = "refresh_token";
    public static final String HINT_ACCESS = "access_token";

    /** 결과 요약 — 테스트·호출부 판단용. status 는 응답이 없으면 null. */
    public record Result(String outcome, Integer status) {
        static Result skip(String reason) {
            return new Result("skip:" + reason, null);
        }
    }

    private final RestClient rest;
    private final ClientRegistrationRepository repo;
    private final AuthProperties props;

    /**
     * 🔴 전용 RestClient — connect 3s · read 5s. 공용 인스턴스의 타임아웃을 건드리지 않는다.
     * {@code Buffering} 으로 감싸 {@code Content-Length} 를 보낸다(istio-envoy 의 chunked 400 선례 — AI 전송 경로).
     */
    @Autowired
    public OidcTokenRevoker(RestClient.Builder builder, ClientRegistrationRepository repo, AuthProperties props) {
        this(builder.requestFactory(timeoutFactory()).build(), repo, props);
    }

    /** 목 주입 지점(package-private). */
    OidcTokenRevoker(RestClient rest, ClientRegistrationRepository repo, AuthProperties props) {
        this.rest = rest;
        this.repo = repo;
        this.props = props;
    }

    private static BufferingClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(3));
        rf.setReadTimeout(Duration.ofSeconds(5));
        return new BufferingClientHttpRequestFactory(rf);
    }

    /** 생략 사유를 1줄 남긴다(호출부가 revoke 자체를 하지 않을 때 — 예: 비밀번호 세션). */
    public void logSkip(String reason) {
        log.info(TAG + "skip — reason={}", reason);
    }

    /**
     * 토큰 1개를 폐기 요청한다. 🔴 예외를 던지지 않는다.
     *
     * @param token 폐기할 토큰 원문. 비면 skip
     * @param hint  {@link #HINT_REFRESH} 또는 {@link #HINT_ACCESS}
     */
    public Result revoke(String token, String hint) {
        if (!StringUtils.hasText(token)) {
            log.info(TAG + "skip — hint={} reason=NO_TOKEN", hint);
            return Result.skip("NO_TOKEN");
        }
        ClientRegistration reg;
        String uri;
        try {
            reg = repo.findByRegistrationId(props.getRegistrationId());
            uri = endpoint(reg);
        } catch (RuntimeException e) {
            log.warn(TAG + "실패 hint={} cause={} (registration 조회)", hint, e.getClass().getSimpleName());
            return new Result("error", null);
        }
        if (reg == null || !StringUtils.hasText(uri)) {
            log.info(TAG + "skip — hint={} reason=NO_ENDPOINT", hint);
            return Result.skip("NO_ENDPOINT");
        }

        if (props.isDiagLogEnabled()) {
            // 🔴 한시적 진단 로그. 스위치 ON 일 때만 claim 을 더한다(토큰 원문은 남기지 않는다).
            log.info(TAG + "호출 hint={} uri={} fp={} claims={}",
                    hint, uri, AuthDiagLog.fingerprint(token), AuthDiagLog.tokenClaims(token));
        } else {
            log.info(TAG + "호출 hint={} uri={} fp={}", hint, uri, AuthDiagLog.fingerprint(token));
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        form.add("token_type_hint", hint);

        long started = System.nanoTime();
        try {
            ResponseEntity<String> res = rest.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(h -> h.setBasicAuth(formEncode(reg.getClientId()), formEncode(reg.getClientSecret())))
                    .body(form)
                    .retrieve()
                    // 4xx·5xx 도 예외 없이 받아 상태·본문을 남긴다
                    .onStatus(HttpStatusCode::isError, (req, resp) -> { })
                    .toEntity(String.class);
            int status = res.getStatusCode().value();
            log.info(TAG + "결과 hint={} status={} elapsedMs={} body={}",
                    hint, status, elapsedMs(started), AuthDiagLog.truncate(res.getBody()));
            return new Result(res.getStatusCode().is2xxSuccessful() ? "ok" : "rejected", status);
        } catch (RuntimeException e) {
            // 🔴 메시지를 남기지 않는다 — 요청 본문(토큰)이 섞일 수 있다
            log.warn(TAG + "실패 hint={} cause={} elapsedMs={} — 로그아웃은 계속한다",
                    hint, e.getClass().getSimpleName(), elapsedMs(started));
            return new Result("error", null);
        }
    }

    /** 설정값 → discovery 폴백. */
    private String endpoint(ClientRegistration reg) {
        if (StringUtils.hasText(props.getRevocationEndpoint())) {
            return props.getRevocationEndpoint();
        }
        if (reg == null) {
            return null;
        }
        Object v = reg.getProviderDetails().getConfigurationMetadata().get("revocation_endpoint");
        return v == null ? null : v.toString();
    }

    /** RFC 6749 2.3.1 — Basic 에 싣기 전 form-urlencode. null 은 빈 문자열. */
    private static String formEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
