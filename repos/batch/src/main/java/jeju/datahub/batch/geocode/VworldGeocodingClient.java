package jeju.datahub.batch.geocode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;
import jeju.datahub.batch.geocode.VworldCallSupport.TransientGeocodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * VWorld 주소 → 좌표 변환 (<a href="https://api.vworld.kr/req/address">api.vworld.kr/req/address</a>).
 *
 * <p><b>한 번의 {@link #geocode(String)} 호출이 지번({@code parcel}) → 도로명({@code road}) 2단을
 * 순서대로 시도한다</b>(D7 의 1·2단). 4단(부번 제거 후 재조회)은 주소 문자열을 바꿔 이 클라이언트를
 * 다시 부르는 것이므로 {@link CompositeGeocoder} 가 담당한다. 그 사이 3단(장소 POI 검색)은
 * 엔드포인트가 달라 {@link VworldPlaceSearchClient} 가 맡는다.
 *
 * <h2>상태별 처리</h2>
 * <table>
 *   <tr><th>응답</th><th>처리</th></tr>
 *   <tr><td>{@code status=OK}</td><td>{@code result.point} 의 x=경도, y=위도 → {@code FOUND}</td></tr>
 *   <tr><td>{@code status=NOT_FOUND}</td><td>{@code NOT_FOUND} — <b>재시도하지 않고</b> 다음 단계로</td></tr>
 *   <tr><td>{@code error.code=OVER_REQUEST_LIMIT}</td><td>{@link GeocodeQuotaExceededException} 즉시 전파(재시도 금지, D9)</td></tr>
 *   <tr><td>그 밖의 {@code status=ERROR} · 네트워크 · 타임아웃</td><td>일시 장애로 보고 재시도, 소진하면 {@code TRANSIENT}</td></tr>
 * </table>
 *
 * <p>재시도는 {@link RetryTemplate} 으로 <b>{@code max-attempts}(기본 4)회, 300ms × 시도횟수</b>
 * 만큼 쉬며 반복한다(Node 프로토타입 {@code batch/src/geocode/vworld.ts} 와 동일한 상수).
 * 다 소진해도 예외를 던지지 않고 {@link GeocodeOutcome.Status#TRANSIENT} 로 내려보내 다음 폴백
 * 단계로 넘긴다 — 일시 장애 한 건이 배치 전체를 멈추면 안 되기 때문이다(쿼터 초과는 반대로 즉시
 * 중단시킨다).
 *
 * <p><b>🔄 2026-08-04</b> — 예전에는 재시도 소진도 "못 찾음"({@code Optional.empty()})으로 뭉갰다.
 * 그러면 호출자가 일시 장애를 영구 실패로 캐싱해 그 주소가 영영 복구되지 않는다(D8 개정).
 * 지금은 {@code NOT_FOUND} 와 {@code TRANSIENT} 를 구분해 돌려준다.
 *
 * <p><b>호출 간격(rate limit)은 여기서 두지 않는다.</b> "API 를 실제로 호출한 경우에만 대기"라는
 * 규칙은 캐시를 아는 쪽만 판단할 수 있어 {@link CompositeGeocoder} 에 있다.
 *
 * <p><b>ℹ️ {@code domain} 파라미터는 쓰지 않는다.</b> 루트 {@code .env} 에 {@code VWORLD_API_DOMAIN}
 * 이 있지만, 지오코딩 API({@code /req/address})는 2026-08-04 실호출 확인 결과 {@code domain} 없이도
 * {@code status=OK} 를 반환한다(있으나 없으나 응답 동일). {@code domain} 이 필요한 것은
 * {@code /req/data}(공간정보 조회, TSK-3927) 쪽이다.
 */
@Component
public class VworldGeocodingClient implements GeocodingClient {

    private static final Logger log = LoggerFactory.getLogger(VworldGeocodingClient.class);

    /** 조회 순서 — 지번 먼저, 실패하면 도로명(D7 1·2단). */
    static final List<String> ADDRESS_TYPES = List.of("parcel", "road");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String apiKey;
    private final int maxAttempts;
    private final RetryTemplate retryTemplate;

    /** 실제로 나간 HTTP 요청 수 — 캐시가 호출을 줄이고 있는지 테스트/운영에서 확인하는 용도. */
    private final AtomicLong apiCallCount = new AtomicLong();

    public VworldGeocodingClient(
            @Value("${geocode.vworld.api-key}") String apiKey,
            @Value("${geocode.vworld.base-url}") String baseUrl,
            @Value("${geocode.vworld.timeout-ms}") int timeoutMs,
            @Value("${geocode.vworld.max-attempts}") int maxAttempts) {

        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.maxAttempts = maxAttempts;
        this.restClient = VworldCallSupport.restClient(baseUrl, timeoutMs);
        this.retryTemplate = VworldCallSupport.retryTemplate(maxAttempts);
    }

    /**
     * {@inheritDoc}
     *
     * <p>지번·도로명 중 <b>하나라도 일시 장애로 끝났으면 전체를 {@code TRANSIENT}</b> 로 본다 —
     * 못 물어본 타입이 있는 이상 "그 주소는 없다"고 단정할 수 없기 때문이다.
     *
     * <p>실패한 결과에는 타입별 판정을 {@link GeocodeOutcome#detail()} 에 담는다
     * ({@code parcel=NOT_FOUND, road=TRANSIENT}). 스킵 테이블 {@code note} 의 앞부분이 되어
     * "어느 타입에서 걸렸는지"를 사후에 볼 수 있게 한다(Step 7-4).
     */
    @Override
    public GeocodeOutcome geocode(String address) {
        if (address == null || address.isBlank()) {
            return GeocodeOutcome.notFound("address=(빈 값)");
        }
        requireApiKey();
        boolean anyTransient = false;
        StringJoiner detail = new StringJoiner(", ");
        for (String type : ADDRESS_TYPES) {
            GeocodeOutcome outcome = geocodeOnce(address, type);
            if (outcome.isFound()) {
                return outcome;
            }
            detail.add(type + "=" + outcome.status());
            anyTransient |= outcome.isTransient();
        }
        return anyTransient
                ? GeocodeOutcome.transientFailure(detail.toString())
                : GeocodeOutcome.notFound(detail.toString());
    }

    /** 실제로 나간 HTTP 요청 수(누적). */
    public long apiCallCount() {
        return apiCallCount.get();
    }

    /**
     * 키가 없어도 <b>기동은 되게</b> 두고(헬스체크·수동 트리거 엔드포인트를 함께 죽이지 않으려고),
     * 실제 조회 시점에 원인이 분명한 예외로 실패시킨다. 세 프로파일 모두 {@code api-key} 기본값이
     * 빈 문자열이라 환경변수 주입을 잊으면 여기서 잡힌다.
     */
    private void requireApiKey() {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "VWorld API 키가 없습니다. 환경변수 VWORLD_API_KEY 를 주입하세요 "
                            + "(루트 .env → local.sh 가 export). 프로퍼티: geocode.vworld.api-key");
        }
    }

    private GeocodeOutcome geocodeOnce(String address, String type) {
        return retryTemplate.execute(
                context -> request(address, type)
                        .map(GeocodeOutcome::found)
                        .orElseGet(GeocodeOutcome::notFound),
                context -> {
                    Throwable last = context.getLastThrowable();
                    // ⚠️ RetryTemplate 은 "재시도 대상이 아닌 예외"도 이 recovery 로 흘려보낸다
                    //    (stateless 재시도에선 rethrow 하지 않고 handleRetryExhausted 로 빠진다).
                    //    그대로 두면 쿼터 초과가 조용히 "못 찾음"이 되어 D9(즉시 중단)가 깨지므로
                    //    재시도 대상이었던 일시 장애가 아니면 되던진다.
                    if (last != null && !(last instanceof TransientGeocodeException)) {
                        throw last instanceof RuntimeException e ? e : new IllegalStateException(last);
                    }
                    // 재시도를 다 써도 안 되면 TRANSIENT 로 내려보낸다 — 다음 폴백 단계(도로명/부번
                    // 제거)는 살려 두되, "그 주소가 없다"(NOT_FOUND)와 섞이면 안 된다.
                    // 섞으면 호출자가 실패로 캐싱해 장애 구간의 주소를 영구히 굳힌다(D8 개정).
                    log.warn("VWorld 조회가 {}회 시도 후에도 실패했습니다(일시 장애로 처리). type={} addr={}",
                            maxAttempts, type, address, last);
                    return GeocodeOutcome.transientFailure();
                });
    }

    /**
     * 재시도 1회분(HTTP 1회 + 응답 해석).
     *
     * <p>package-private 인 이유는 테스트가 <b>감싸진 예외에 인증키가 섞이지 않는지</b>를 직접
     * 확인하기 위해서다 — {@link #geocode(String)} 는 재시도를 소진하면 예외를 삼켜 버려
     * 메시지를 볼 수 없다.
     */
    Optional<Coordinate> request(String address, String type) {
        apiCallCount.incrementAndGet();
        String body;
        try {
            body = restClient.get()
                    .uri(uri -> uri
                            .queryParam("service", "address")
                            .queryParam("request", "getcoord")
                            .queryParam("version", "2.0")
                            .queryParam("crs", "epsg:4326")
                            .queryParam("address", address)
                            .queryParam("refine", "true")
                            .queryParam("simple", "false")
                            .queryParam("format", "json")
                            .queryParam("type", type)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // 연결 실패·타임아웃·4xx/5xx. 원문 메시지는 인증키가 섞일 수 있는 URL 을 포함할 수 있어
            // 그대로 붙이지 않고 예외 타입만 남긴다(원인은 cause 로 전달).
            throw new TransientGeocodeException(
                    "VWorld 호출 실패(type=" + type + "): " + e.getClass().getSimpleName(), e);
        }
        return parse(body, type);
    }

    private Optional<Coordinate> parse(String body, String type) {
        JsonNode response;
        try {
            response = MAPPER.readTree(body == null ? "" : body).path("response");
        } catch (JsonProcessingException e) {
            // 응답이 잘렸거나 JSON 이 아님 — 일시 장애로 보고 재시도한다.
            throw new TransientGeocodeException("VWorld 응답 파싱 실패(type=" + type + ")", e);
        }

        String status = response.path("status").asText("");
        if ("OK".equals(status)) {
            JsonNode point = response.path("result").path("point");
            if (point.hasNonNull("x") && point.hasNonNull("y")) {
                return Optional.of(Coordinate.of(point.get("x").asText(), point.get("y").asText()));
            }
            log.warn("VWorld status=OK 인데 result.point 가 없습니다 — 미조회로 처리합니다. type={}", type);
            return Optional.empty();
        }
        if ("NOT_FOUND".equals(status)) {
            return Optional.empty(); // 그 주소가 없는 것 — 재시도해도 결과가 같다
        }

        JsonNode error = response.path("error");
        String errorCode = error.path("code").asText("");
        if (VworldCallSupport.QUOTA_ERROR_CODE.equals(errorCode)) {
            // 재시도 대상이 아니다 — RetryTemplate 을 뚫고 Job 까지 그대로 올라간다.
            throw new GeocodeQuotaExceededException(
                    "VWorld 호출 한도 초과(" + errorCode + "): " + error.path("text").asText(""));
        }
        throw new TransientGeocodeException(
                "VWorld status=" + status + " code=" + errorCode + " type=" + type);
    }
}
