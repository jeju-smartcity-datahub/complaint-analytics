package jeju.datahub.batch.zonenm;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import jeju.datahub.batch.rptp.BackendReportProperties;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * 백엔드 내부 API {@code POST /api/internal/zone-names/prefill?gridSz=&limit=} 호출 클라이언트.
 *
 * <p>배치는 구역명을 스스로 짓지 않는다 — VWorld 폴백 체인·캐시 저장 규칙은 전부 백엔드
 * {@code ZoneNameResolver} 가 한다(배치가 VWorld 를 직접 부르면 규칙이 두 벌이 된다).
 * 이 클래스의 책임은 <b>한 번 부르고, 상태코드를 그대로 살려 돌려주는 것</b>뿐이다.
 *
 * <h2>구성은 {@code rptp.BackendReportClient} 와 같다</h2>
 * <ul>
 *   <li>🔴 {@code onStatus(status -> true, …)} — 기본 4xx/5xx 예외 변환을 끈다. 끄면
 *       400·401·503·5xx 가 한 덩어리가 되어 {@link ZoneNmPrefillTasklet} 의 실패 갈래가 성립하지 않는다.</li>
 *   <li>🔴 {@code BufferingClientHttpRequestFactory(JdkClientHttpRequestFactory)} — chunked 함정 방지
 *       (바디가 비어도 POST 다. {@code Content-Length: 0} 으로 나간다).</li>
 *   <li>설정은 <b>{@code batch.rptp.backend} 재사용</b>({@link BackendReportProperties}) — 같은 백엔드라
 *       base-url·api-key·timeout 이 같다. 새 env 키 없음.</li>
 * </ul>
 *
 * <p>🔒 응답 본문을 로그·예외에 싣지 않는다 — 상태코드와 {@code gridSz} 만 남긴다.
 */
@Component
@EnableConfigurationProperties(BackendReportProperties.class)
public class BackendZoneNmClient {

    private static final Logger log = LoggerFactory.getLogger(BackendZoneNmClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 내부 API 경로. */
    static final String PREFILL_PATH = "/api/internal/zone-names/prefill";

    /** 내부 API 인증 헤더({@code InternalApiKeyFilter}). */
    static final String API_KEY_HEADER = "X-API-Key";

    private final RestClient restClient;
    private final BackendReportProperties properties;

    /**
     * 백엔드 응답 본문 — 백엔드 {@code ZoneNmPrefillResultVO} 와 같은 6칸.
     *
     * @param remainingCnt 호출 <b>뒤</b> 그 격자 크기에 남은 캐시 없는 대상 수. 진척 판정의 근거다
     * @param resolvedCnt  이번 호출에서 캐시에 <b>저장된</b> 수(경합 캐시 히트는 세지 않는다 — 그래서
     *                     진척 판정에 쓰지 않는다)
     * @param transientCnt 일시 실패·차단으로 저장되지 않은 수(다음 호출·실행이 다시 집어 온다)
     * @param tripped      연속 실패 차단(3건)이 걸렸는지
     */
    public record PrefillResponse(
            int gridSz,
            int targetCnt,
            int resolvedCnt,
            int transientCnt,
            int remainingCnt,
            boolean tripped) {
    }

    /**
     * 호출 1건의 결과 — 상태코드를 그대로 들고 온다. 판정은 {@link ZoneNmPrefillTasklet} 한 곳이다.
     *
     * @param status HTTP 상태코드 원본
     * @param body   2xx 이고 6칸이 모두 해석됐을 때만 채워진다. 그 밖에는 {@code null}
     */
    public record Result(int status, PrefillResponse body) {
    }

    @Autowired
    public BackendZoneNmClient(BackendReportProperties properties) {
        this(properties, RestClient.builder());
    }

    /** 테스트용 — {@code MockRestServiceServer} 가 바인딩할 빌더를 주입받는다. */
    BackendZoneNmClient(BackendReportProperties properties, RestClient.Builder builder) {
        this.properties = properties;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory jdkFactory = new JdkClientHttpRequestFactory(httpClient);
        // 읽기 타임아웃 120초 — 호출 1건 = VWorld 최대 limit 건(백엔드 동기 처리).
        jdkFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                // 🔴 반드시 Buffering 으로 감싼다 — 단독이면 다시 chunked 다.
                .requestFactory(new BufferingClientHttpRequestFactory(jdkFactory))
                .build();
    }

    /**
     * 미리 채우기 1회를 요청한다.
     *
     * <p>🔴 어떤 상태코드도 예외로 바꾸지 않는다.
     *
     * @throws ResourceAccessException 연결 실패·읽기 타임아웃 — <b>일시 실패</b>(Tasklet 이 그 크기를 중단한다)
     * @throws IllegalStateException   {@code base-url} 이 비었을 때 — Job FAILED
     */
    public Result prefill(int gridSz, int limit) {
        requireBaseUrl();

        RestClient.RequestBodySpec spec = restClient.post()
                .uri(uri -> uri.path(PREFILL_PATH)
                        .queryParam("gridSz", gridSz)
                        .queryParam("limit", limit)
                        .build())
                .accept(MediaType.APPLICATION_JSON);

        // 🔴 키가 없으면 헤더 자체를 넣지 않는다(BackendReportClient 와 같다).
        if (properties.hasApiKey()) {
            spec = spec.header(API_KEY_HEADER, properties.apiKey());
        }

        ResponseEntity<String> response = spec
                .retrieve()
                // 🔴 기본 4xx/5xx 예외 변환을 끈다.
                .onStatus(status -> true, (request, res) -> { })
                .toEntity(String.class);

        int status = response.getStatusCode().value();
        if (!response.getStatusCode().is2xxSuccessful()) {
            // 🔒 본문을 찍지 않는다.
            log.warn("구역명 미리 채우기 요청 실패: status={} gridSz={} limit={}", status, gridSz, limit);
            return new Result(status, null);
        }
        return new Result(status, parseBody(status, response.getBody(), gridSz));
    }

    /** 🔴 호스트가 비었는지 Job 실행 시점에 확인한다(기동을 죽이지 않는다). */
    private void requireBaseUrl() {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "백엔드 내부 API 호스트(batch.rptp.backend.base-url)가 비어 있습니다 —"
                            + " BACKEND_BASE_URL 환경변수를 확인하세요.");
        }
    }

    /**
     * 6칸 중 하나라도 없거나 타입이 어긋나면 {@code null} — 계약 불일치라 Tasklet 이 Job 을 끊는다
     * (반쯤 읽은 값으로 진척을 판정하면 무한 반복·조기 중단이 된다).
     */
    private static PrefillResponse parseBody(int status, String body, int gridSz) {
        if (body == null || body.isBlank()) {
            log.warn("구역명 미리 채우기 응답 본문이 비어 있습니다. status={} gridSz={}", status, gridSz);
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (!node.path("gridSz").isInt() || !node.path("targetCnt").isInt()
                    || !node.path("resolvedCnt").isInt() || !node.path("transientCnt").isInt()
                    || !node.path("remainingCnt").isInt() || !node.path("tripped").isBoolean()) {
                log.warn("구역명 미리 채우기 응답 필드가 계약과 다릅니다. status={} gridSz={}", status, gridSz);
                return null;
            }
            return new PrefillResponse(
                    node.path("gridSz").asInt(),
                    node.path("targetCnt").asInt(),
                    node.path("resolvedCnt").asInt(),
                    node.path("transientCnt").asInt(),
                    node.path("remainingCnt").asInt(),
                    node.path("tripped").asBoolean());
        } catch (JsonProcessingException e) {
            // 🔒 예외 메시지에 본문 일부가 섞이므로 타입만 남긴다.
            log.warn("구역명 미리 채우기 응답 본문을 해석하지 못했습니다. status={} gridSz={} error={}",
                    status, gridSz, e.getClass().getSimpleName());
            return null;
        }
    }
}
