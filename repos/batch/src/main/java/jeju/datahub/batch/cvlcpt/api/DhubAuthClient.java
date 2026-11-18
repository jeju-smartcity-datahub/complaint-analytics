package jeju.datahub.batch.cvlcpt.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * DHubManager 인증 — {@code POST /api/v1/auth/login} 으로 JWT 를 받아 보관하고,
 * <b>만료 전에 미리 갱신</b>하며, 그래도 401 이 나면 <b>1회만</b> 재로그인 후 재시도한다(제약 F).
 *
 * <h2>왜 선제갱신인가</h2>
 * 실측 토큰의 수명은 <b>900초(15분)</b> 다(2026-08-04, {@code exp - iat}). backfill 은 159,530행을
 * 1,000행씩 나눠 받아 160회 이상 호출이 이어지고 행마다 후속 처리가 붙으므로 <b>한 번의 실행이 15분을
 * 넘기는 것이 정상</b>이다. 반응형(401 을 맞고 나서 갱신)만으로도 동작은 하지만, 페이지마다 실패 왕복이
 * 한 번씩 끼어들고 재시도 예산을 인증에 써 버린다. 그래서 요청 전에 {@code exp} 를 보고 앞질러 갱신한다.
 *
 * <pre>{@code
 * if (token == null || now >= exp - 60s) login();     // 선제갱신
 * res = call(token);
 * if (res.status() == 401) { login(); res = call(token); }   // 안전망: 재시도 1회
 * }</pre>
 *
 * <h2>exp 를 못 읽으면</h2>
 * {@code access_token} 은 JWT 지만 <b>서명을 검증하지 않는다</b> — 이 클라이언트는 토큰의 소비자가
 * 아니라 전달자이고, 검증에 필요한 공개키도 없다. payload 만 base64url 디코드해 {@code exp} 를 읽는다.
 * 클레임이 없거나 형식이 바뀌어 파싱에 실패하면 <b>선제갱신을 끄고 401 반응형으로 폴백</b>한다 —
 * 토큰 형식이 바뀌었다고 배치가 죽으면 안 된다.
 *
 * <h2>refresh 를 쓰지 않는 이유</h2>
 * {@code POST /api/v1/auth/refresh} 는 {@code refresh_token} 을 <b>쿠키로</b> 받는다. 쓰려면 HTTP
 * 클라이언트에 쿠키 저장소를 붙여야 하는데, 자격증명이 환경변수에 있어 재로그인이 언제든 가능하고
 * backfill 전체에서 갱신은 몇 번뿐이라 실익이 없다(사전조사 F).
 *
 * <h2>🔒 로깅</h2>
 * <b>토큰과 비밀번호는 어떤 레벨로도 남기지 않는다.</b> 로그에 남기는 것은 만료 시각·수명(초)·
 * 로그인 횟수뿐이다. HTTP 예외도 본문이 섞이지 않게 타입 이름만 옮긴다.
 */
@Component
public class DhubAuthClient {

    private static final Logger log = LoggerFactory.getLogger(DhubAuthClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String LOGIN_PATH = "/api/v1/auth/login";

    /** 만료 이 초 전이면 요청 전에 미리 재로그인한다. 수명 900초 대비 충분히 짧다. */
    static final long REFRESH_MARGIN_SECONDS = 60L;

    /**
     * 연속 로그인 실패 상한(시큐어코딩 2.16). 이만큼 연달아 실패하면 {@link #LOGIN_COOLDOWN} 동안
     * HTTP 요청 없이 즉시 실패시킨다 — 페이지마다 재로그인해 원천 계정이 잠기는 것을 막는다.
     */
    static final int MAX_CONSECUTIVE_LOGIN_FAILURES = 3;

    /** 연속 실패 상한 도달 뒤 로그인을 막는 시간. */
    static final Duration LOGIN_COOLDOWN = Duration.ofMinutes(5);

    private final RestClient restClient;
    private final DhubProperties properties;
    private final Clock clock;

    /** 아래 네 필드는 {@code this} 로 보호한다 — 배치는 단일 스레드지만 상태가 공유되므로 잠근다. */
    private String token;
    private Instant expiresAt;
    private boolean proactiveRefresh = true;

    /**
     * 지금 들고 있는 토큰에 한해 선제갱신을 건너뛴다 — <b>갓 받은 토큰이 이미 갱신 경계 안</b>일 때
     * 켜진다. 이 상황에서 계속 선제갱신을 하면 호출마다 로그인이 붙어 <b>로그인 폭주</b>가 된다
     * (다시 로그인해도 같은 상황이므로 해결되지 않는다). 원인은 대개 클라이언트 시계가 서버보다
     * 많이 앞서 있거나 서버가 토큰 수명을 갱신 여유(60초)보다 짧게 준 경우다.
     */
    private boolean proactiveRefreshSuppressed;

    /** 실제로 나간 로그인 요청 수 — "401 재시도가 1회로 끝났는지"를 테스트/운영에서 확인하는 용도. */
    private final AtomicLong loginCount = new AtomicLong();

    /**
     * 연속 로그인 실패 수 · 차단 만료 시각. {@code login()} 을 부르는 synchronized 메서드 안에서만 읽고 쓴다.
     * 🔴 싱글턴이라 차단 상태를 이 프로세스의 모든 DHub 수집 잡이 함께 본다.
     * 쿨다운이 지나도 카운터는 유지한다 — 쿨다운 뒤 1회가 또 실패하면 곧바로 다시 막힌다(성공해야 초기화).
     */
    private int consecutiveLoginFailures;
    private Instant loginBlockedUntil;

    // ⚠️ 생성자가 둘이므로 @Autowired 로 주입 대상을 못 박는다. 없으면 스프링이 기본 생성자를 찾다가
    //    "No default constructor found" 로 컨텍스트 기동이 통째로 깨진다.
    @Autowired
    public DhubAuthClient(DhubProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 테스트용 — {@code exp} 선제갱신을 검증하려면 시계를 앞으로 돌릴 수 있어야 한다. */
    DhubAuthClient(DhubProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 토큰을 확보해 {@code call} 에 넘긴다. 401 이면 <b>재로그인 후 정확히 1회</b> 다시 부른다.
     *
     * <p>두 번째 호출이 또 401 이면 그 예외를 그대로 위로 던진다 — 자격증명 자체가 틀렸거나 권한이
     * 없는 상황이라 재로그인을 반복해도 회복되지 않는다. <b>무한 재로그인 루프를 만들지 않는 것이
     * 이 메서드의 계약</b>이다.
     *
     * @param call 토큰을 받아 실제 HTTP 호출을 수행한다. 401 을 만나면
     *             {@link DhubUnauthorizedException} 을 던져야 이 재시도가 발동한다.
     */
    public <T> T withToken(Function<String, T> call) {
        String current = currentToken();
        try {
            return call.apply(current);
        } catch (DhubUnauthorizedException first) {
            // 선제갱신이 꺼져 있거나(exp 미상), 서버가 만료를 앞당겼거나, 토큰이 무효화된 경우.
            // 시큐어코딩 4.1 — 예외 원문 대신 타입만 남기고 상세는 DEBUG 로 분리한다.
            log.warn("DHub 401 수신 — 재로그인 후 1회만 재시도합니다. (사유: {})", first.getClass().getSimpleName());
            log.debug("DHub 401 상세", first);
            String renewed = forceLogin();
            return call.apply(renewed); // 여기서 또 401 이면 전파된다 — 재시도는 여기까지다.
        }
    }

    /**
     * 유효한 토큰을 돌려준다 — 없거나 곧 만료면 로그인한다.
     * {@link #withToken(Function)} 을 쓸 수 없는 곳(진단·수동 확인)을 위해 공개한다.
     */
    public synchronized String currentToken() {
        if (token == null) {
            ensureLoginAllowed();   // 2.16 — 연속 실패 차단 중이면 요청 없이 실패
            return login();
        }
        if (proactiveRefresh && !proactiveRefreshSuppressed && isNearExpiry()) {
            log.info("DHub 토큰 만료가 임박해 선제 재로그인합니다. exp={} (만료 {}초 전 기준)",
                    expiresAt, REFRESH_MARGIN_SECONDS);
            ensureLoginAllowed();   // 2.16 — 연속 실패 차단 중이면 요청 없이 실패
            return login();
        }
        return token;
    }

    /** 지금 토큰을 버리고 반드시 새로 로그인한다(401 안전망 경로). */
    public synchronized String forceLogin() {
        token = null;
        expiresAt = null;
        ensureLoginAllowed();   // 2.16 — 연속 실패 차단 중이면 요청 없이 실패
        return login();
    }

    /** 누적 로그인 요청 수. */
    public long loginCount() {
        return loginCount.get();
    }

    /** {@code exp} 를 읽지 못해 선제갱신이 꺼졌으면 {@code false}(= 401 반응형 폴백 상태). */
    public synchronized boolean isProactiveRefreshEnabled() {
        return proactiveRefresh;
    }

    /**
     * 지금 토큰에 한해 선제갱신을 건너뛰는 중인가 — 발급 직후부터 만료 임박이라 재로그인해도
     * 개선되지 않는 상태다(로그인 폭주 방지). 다음 로그인에서 정상 수명을 받으면 자동으로 풀린다.
     */
    public synchronized boolean isProactiveRefreshSuppressed() {
        return proactiveRefreshSuppressed;
    }

    /** 현재 토큰의 만료 시각. {@code exp} 를 못 읽었으면 {@code null}. */
    public synchronized Instant expiresAt() {
        return expiresAt;
    }

    /** 만료 {@value #REFRESH_MARGIN_SECONDS}초 전 경계를 넘었는가. {@code exp} 를 모르면 {@code false}. */
    private boolean isNearExpiry() {
        return expiresAt != null
                && !clock.instant().isBefore(expiresAt.minusSeconds(REFRESH_MARGIN_SECONDS));
    }

    private String login() {
        properties.requireCredentials();
        ensureLoginAllowed();   // 호출부도 같은 검사를 먼저 한다 — 여기는 다른 경로로 들어와도 막는 방어선
        loginCount.incrementAndGet();

        String accessToken;
        String tokenType;
        try {
            JsonNode body = restClient.post()
                    .uri(LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "email", properties.getEmail(),
                            "password", properties.getPassword()))
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                recordLoginFailure();
                throw new DhubApiException("DHub 로그인 응답이 비었습니다. " + LOGIN_PATH);
            }
            accessToken = body.path("access_token").asText("");
            tokenType = body.path("token_type").asText("");
        } catch (RestClientException e) {
            // 원문 메시지에는 응답 본문이 실린다 — 자격증명 오류 응답이 그대로 로그에 남지 않도록
            // 타입 이름만 옮기고 원인은 cause 로 전달한다(VworldGeocodingClient 와 같은 관례).
            recordLoginFailure();
            throw new DhubApiException(
                    "DHub 로그인 실패(" + LOGIN_PATH + "): " + e.getClass().getSimpleName(), e);
        }

        if (accessToken.isBlank()) {
            recordLoginFailure();
            throw new DhubApiException("DHub 로그인 응답에 access_token 이 없습니다. " + LOGIN_PATH);
        }

        this.token = accessToken;
        this.expiresAt = parseExpiry(accessToken);
        consecutiveLoginFailures = 0;
        loginBlockedUntil = null;

        // 갓 받은 토큰이 이미 갱신 경계 안이면 선제갱신은 아무것도 해결하지 못한다 — 그대로 두면
        // 호출마다 로그인이 붙는다(backfill 160페이지 = 로그인 160회). 이 토큰에 한해 끄고
        // 401 반응형에 맡긴다. 다음 로그인 때 정상 수명이 돌아오면 자동으로 풀린다.
        this.proactiveRefreshSuppressed = proactiveRefresh && isNearExpiry();
        if (proactiveRefreshSuppressed) {
            log.warn("DHub 가 발급 직후 이미 만료 임박({}초 이내)인 토큰을 줬습니다 — 선제갱신을 이번 토큰에 한해 "
                            + "끄고 401 반응형으로 진행합니다. exp={} now={} (서버와 시계가 어긋났거나 토큰 수명이 짧습니다)",
                    REFRESH_MARGIN_SECONDS, expiresAt, clock.instant());
        }

        // ⚠️ 토큰 값은 어디에도 남기지 않는다. 남기는 건 만료 시각과 수명뿐이다.
        log.info("DHub 로그인 성공 (token_type={}, exp={}, 남은수명={}초, 누적 로그인={}회)",
                tokenType.isBlank() ? "(없음)" : tokenType,
                expiresAt == null ? "(미상)" : expiresAt,
                expiresAt == null ? "-" : Duration.between(clock.instant(), expiresAt).toSeconds(),
                loginCount.get());
        return accessToken;
    }

    /**
     * JWT payload 의 {@code exp} 클레임을 읽는다. <b>서명은 검증하지 않는다</b>(공개키가 없고,
     * 이 값은 갱신 시점 판단에만 쓴다 — 틀려도 401 안전망이 받아낸다).
     *
     * @return 만료 시각. 형식이 JWT 가 아니거나 {@code exp} 가 없으면 {@code null} 을 돌려주고
     *         <b>선제갱신을 끈다</b>(401 반응형 폴백).
     */
    private Instant parseExpiry(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return disableProactiveRefresh("JWT 형식이 아닙니다(마침표로 구분된 3분절이 아님)");
        }
        try {
            // base64url 이며 padding 이 없을 수 있다 — java.util.Base64 의 URL 디코더는 둘 다 받는다.
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = MAPPER.readTree(payload);
            if (!claims.hasNonNull("exp") || !claims.get("exp").isNumber()) {
                return disableProactiveRefresh("payload 에 숫자 exp 클레임이 없습니다");
            }
            Instant exp = Instant.ofEpochSecond(claims.get("exp").asLong());
            if (!proactiveRefresh) {
                // 앞선 토큰에서 꺼졌더라도 다시 읽히면 되살린다.
                proactiveRefresh = true;
                log.info("DHub 토큰에서 exp 를 다시 읽었습니다 — 선제갱신을 재개합니다.");
            }
            return exp;
        } catch (IllegalArgumentException | java.io.IOException e) {
            return disableProactiveRefresh("payload 디코드/파싱 실패: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 연속 로그인 실패로 차단 중이면 HTTP 요청 없이 {@link DhubApiException} 을 던진다(시큐어코딩 2.16).
     * 차단된 호출은 {@code loginCount} 를 올리지 않는다("실제로 나간 로그인 요청 수").
     */
    private void ensureLoginAllowed() {
        if (loginBlockedUntil != null && clock.instant().isBefore(loginBlockedUntil)) {
            throw new DhubApiException("DHub 로그인 연속 실패 " + consecutiveLoginFailures
                    + "회 — " + loginBlockedUntil + " 까지 로그인을 시도하지 않습니다. 자격증명·원천 상태를 확인하세요.");
        }
    }

    /** 로그인 실패 1회를 센다. 상한에 닿으면 {@link #LOGIN_COOLDOWN} 동안 로그인을 막는다. */
    private void recordLoginFailure() {
        consecutiveLoginFailures++;
        if (consecutiveLoginFailures >= MAX_CONSECUTIVE_LOGIN_FAILURES) {
            loginBlockedUntil = clock.instant().plus(LOGIN_COOLDOWN);
            log.warn("DHub 로그인 연속 {}회 실패 — {} 까지 로그인을 막습니다(그동안 시작한 DHub 수집 잡은 실패합니다).",
                    consecutiveLoginFailures, loginBlockedUntil);
        }
    }

    /** 선제갱신을 끄고 401 반응형으로 폴백한다. <b>토큰 값은 로그에 싣지 않는다.</b> */
    private Instant disableProactiveRefresh(String reason) {
        if (proactiveRefresh) {
            log.warn("DHub 토큰의 만료시각을 알 수 없어 선제갱신을 끄고 401 반응형으로 전환합니다. 사유: {}", reason);
        }
        proactiveRefresh = false;
        return null;
    }
}
