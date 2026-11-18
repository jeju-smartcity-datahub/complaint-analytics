package jeju.datahub.auth.service.impl;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import jeju.datahub.auth.service.AuthProperties;

/**
 * 롤 판정 값({@code type}) 조달 — DHub2 Manager {@code GET /api/v1/auth/me}.
 *
 * <h2>🔴 왜 {@code /userinfo} 가 아니라 여기인가</h2>
 * 2026-09-23 실측 결과 {@code type} 은 <b>OIDC claim 이 아니다</b> — {@code id_token} 에도
 * {@code /userinfo} 에도 없고 <b>Manager 자체 API 의 응답 필드</b>다. 🔴 그렇다고
 * {@code /userinfo} 를 이것으로 <b>교체하지 않는다</b> — 그 호출은 {@code id_token} 의 {@code sub}
 * 와 대조하는 <b>토큰 치환 방어 지점</b>(OIDC Core 5.3.2)이라 끊으면 검증이 사라진다.
 * 두 호출은 역할이 다르다(신원 검증 ↔ 롤 조달).
 *
 * <h2>🔴 실패를 전부 삼킨다</h2>
 * 401·타임아웃·필드 부재 — 무엇이든 {@link Optional#empty()} + <b>로그 1줄</b>이다. 예외를 던지면
 * OIDC 로그인이 통째로 막힌다. <b>Manager 자체 API 는 자기가 발급한 토큰만 받으므로 OIDC 브로커
 * 토큰은 401 이 정상 경로</b>다 — 그래서 401 만 <b>INFO</b> 로 가른다(WARN 으로 찍으면 매 OIDC
 * 로그인마다 경보가 울린다). 값이 없으면 {@code sync()} 가 claim 부재로 보고 <b>기존 롤을 유지</b>한다.
 * OIDC 경로는 교환 토큰({@code dhub2-manager})을 넘긴다 — 401 이면 이상 신호다.
 * 로그 레벨은 INFO 그대로 둔다(비밀번호 경로가 같은 메서드를 쓰므로 레벨을 바꾸면 그쪽 의미가 바뀐다).
 *
 * <h2>🔴 응답 전문을 로그에 남기지 않는다</h2>
 * 응답에는 이름·이메일·아바타까지 들어 있다. 남기는 것은 <b>{@code type} 값과 상태코드</b>뿐이다.
 *
 * <h2>🔴 base URL 은 issuer 에서 조립한다</h2>
 * 호스트를 하드코딩하지 않는다 — 환경마다 다르다. 경로는 설정 키
 * ({@code app.auth.profile-endpoint})로 노출하고, <b>비어 있으면 호출 자체를 건너뛴다</b>(끄는 스위치).
 *
 * <h2>🔴 생성자가 둘인 이유</h2>
 * 운영 생성자가 {@code requestFactory(...)} 를 부르는데 {@code DefaultRestClientBuilder#build()} 는
 * 그 시점의 팩토리를 <b>스냅샷</b>한다. {@code MockRestServiceServer.bindTo(builder)} 도 같은 자리에
 * 목 팩토리를 심으므로, 운영 생성자만 있으면 목이 타임아웃 팩토리에 덮인다(선례:
 * {@link DhubPasswordAuthClient}). 🔴 운영 생성자의 {@code @Autowired} 는 필수다.
 */
@Component
public class DhubUserProfileClient {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(DhubUserProfileClient.class);

    private static final String LOG_TAG = "[profile] ";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient rest;
    private final ClientRegistrationRepository repo;
    private final AuthProperties props;

    /** 🔴 {@code @Autowired} 필수 — 생성자가 2개다. 🔴 타임아웃 무한 대기는 로그인 스레드를 묶는다. */
    @Autowired
    public DhubUserProfileClient(RestClient.Builder builder,
                                 ClientRegistrationRepository repo,
                                 AuthProperties props) {
        this(builder.requestFactory(timeoutFactory(props.getProfileTimeoutMs())).build(), repo, props);
    }

    /** 🔴 목 주입 지점(package-private) — 조립이 끝난 클라이언트를 그대로 받는다. */
    DhubUserProfileClient(RestClient rest, ClientRegistrationRepository repo, AuthProperties props) {
        this.rest = rest;
        this.repo = repo;
        this.props = props;
    }

    /** connect·read 를 같은 값으로 건다 — 두 구간 모두 무한 대기를 막는 것이 목적이다. */
    private static ClientHttpRequestFactory timeoutFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofMillis(timeoutMs));
        rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        return rf;
    }

    // ------------------------------------------------------------------ 공개 API

    /**
     * claim 맵에 {@code type} 을 <b>주입</b>한다 — {@code sync(claims)} 호출 직전에 부른다.
     *
     * <p>🔴 {@link jeju.datahub.auth.service.AuthUserSyncService} 는 <b>claim 맵만 보는 계약</b>을
     * 유지한다("어디서 왔는지" 를 모르게 둔다) — Dtonic 이 audience 를 풀거나 {@code type} 을 claim 에
     * 실어 주면 <b>이 주입만 걷어내면</b> 되기 때문이다.
     *
     * <p>🔴 <b>이미 있으면 덮어쓰지 않는다</b> — claim 으로 오게 되면 그쪽이 정본이고, 그 순간
     * 이 단계가 자연스럽게 무력해진다.
     *
     * @param claims      가변 claim 맵 (불변 맵을 넘기면 안 된다)
     * @param accessToken Manager 가 발급한 access token
     */
    public void injectType(Map<String, Object> claims, String accessToken) {
        if (claims == null) {
            return;
        }
        String key = props.getRoleClaim();
        if (claims.containsKey(key)) {
            return;     // 🔴 claim 이 정본 — 덮어쓰지 않는다
        }
        fetchType(accessToken).ifPresent(value -> claims.put(key, value));
    }

    /**
     * {@code GET {issuer}{profile-endpoint}} 를 Bearer 로 호출해 {@code type} 하나만 꺼낸다.
     *
     * <p>🔴 <b>빈 문자열도 값이다</b>(명시적 일반 사용자 → {@code ROLE_USER} 강등). 필드가
     * 아예 없을 때(=부재, 유지)와 구분해야 하므로 {@code ""} 를 {@code empty} 로 접지 않는다.
     *
     * @return {@code type} 값. 끄는 스위치·401·타임아웃·필드 부재는 모두 {@link Optional#empty()}
     */
    public Optional<String> fetchType(String accessToken) {
        String endpoint = props.getProfileEndpoint();
        if (!StringUtils.hasText(endpoint)) {
            return Optional.empty();    // 끄는 스위치 — 호출 자체를 건너뛴다
        }
        if (!StringUtils.hasText(accessToken)) {
            return Optional.empty();
        }

        URI target = target(endpoint);
        if (target == null) {
            return Optional.empty();
        }

        String key = props.getRoleClaim();
        // 🔴 한시적 진단 로그(스위치 OFF 면 찍지 않는다). 아래 "응답 전문 금지" 의 의도적 예외다.
        boolean diag = props.isDiagLogEnabled();
        AuthDiagLog.meRequest(diag, target.toString(), accessToken);
        long started = System.nanoTime();
        try {
            Map<String, Object> body = rest.get()
                    .uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(JSON_MAP);
            AuthDiagLog.meResponse(diag, elapsedMs(started), body);
            // 🔴 응답 전문을 찍지 않는다 — 이름·이메일·아바타가 들어 있다.
            Object value = (body == null) ? null : body.get(key);
            if (value == null) {
                log.info(LOG_TAG + "응답에 '{}' 이(가) 없다 — 기존 롤을 유지한다.", key);
                return Optional.empty();
            }
            log.info(LOG_TAG + "'{}' = \"{}\"", key, value);
            return Optional.of(value.toString());
        } catch (HttpClientErrorException.Unauthorized e) {
            AuthDiagLog.meFailure(diag, 401, elapsedMs(started), e.getResponseBodyAsString(), e.getClass().getSimpleName());
            // 🔴 INFO 다 — OIDC 브로커 토큰은 이 API 에서 401 이 정상 경로였다.
            //    OIDC 경로는 교환 토큰을 넘기므로 401 은 이상 신호다 — 레벨은 그대로(문구만 보정).
            log.info(LOG_TAG + "조회 불가 — status=401 (기존 롤을 유지한다).");
            return Optional.empty();
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            AuthDiagLog.meFailure(diag, e.getStatusCode().value(), elapsedMs(started),
                    e.getResponseBodyAsString(), e.getClass().getSimpleName());
            // 🔴 status 만 남긴다 — 본문에는 개인정보가 섞인다.
            log.warn(LOG_TAG + "조회 실패 — status={}", e.getStatusCode().value());
            return Optional.empty();
        } catch (RestClientException e) {
            AuthDiagLog.meFailure(diag, null, elapsedMs(started), null, e.getClass().getSimpleName());
            // 타임아웃·네트워크·본문 변환 실패. 🔴 메시지에 URL·본문이 섞이므로 타입 이름만 남긴다.
            log.warn(LOG_TAG + "조회 호출 실패 — cause={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ private

    /** 진단 로그용 경과 시간(ms). */
    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * base URL 은 {@code ClientRegistration} 의 issuer 에서 조립한다 — 🔴 호스트 하드코딩 금지.
     *
     * @return 대상 URI. 설정 오류면 {@code null}(🔴 여기서도 던지지 않는다)
     */
    private URI target(String endpoint) {
        ClientRegistration reg = repo.findByRegistrationId(props.getRegistrationId());
        if (reg == null) {
            log.warn(LOG_TAG + "ClientRegistration 이 없어 건너뛴다 — id={}", props.getRegistrationId());
            return null;
        }
        String issuer = reg.getProviderDetails().getIssuerUri();
        if (!StringUtils.hasText(issuer)) {
            log.warn(LOG_TAG + "issuer-uri 가 비어 있어 건너뛴다.");
            return null;
        }
        try {
            // 🔴 issuer 끝의 '/' 와 endpoint 앞의 '/' 가 겹치면 "//api/v1/auth/me" 가 된다 —
            //    호스트가 바뀌면 그대로 다른 경로라 401/404 로 조용히 어긋난다. 설정에 무엇이 적히든
            //    같은 URL 이 되게 양쪽을 정규화한다(선례: AGENT_API_BASE_URL "끝에 / 금지").
            return UriComponentsBuilder.fromUriString(stripTrailingSlash(issuer))
                    .path(withLeadingSlash(endpoint)).build().encode().toUri();
        } catch (IllegalArgumentException | IllegalStateException e) { // 4.3: 잘못된 issuer URI(IAE)·인코딩 실패(ISE)
            log.warn(LOG_TAG + "조회 URL 을 만들지 못했다 — cause={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** issuer 는 끝의 '/' 유무가 환경마다 갈린다 — 경로 조립 전에 한 모양으로 맞춘다. */
    private static String stripTrailingSlash(String issuer) {
        String s = issuer.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 설정에 경로만 적히는 키라 '/' 를 빠뜨리기 쉽다 — 없으면 붙여 호스트에 딱 붙는 것을 막는다. */
    private static String withLeadingSlash(String endpoint) {
        String s = endpoint.strip();
        return s.startsWith("/") ? s : "/" + s;
    }
}
