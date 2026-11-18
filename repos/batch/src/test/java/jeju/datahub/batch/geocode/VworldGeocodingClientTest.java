package jeju.datahub.batch.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VworldGeocodingClient} 의 상태별 처리·재시도 정책 테스트.
 *
 * <p>실제 VWorld 를 부르지 않는다 — 쿼터 초과나 일시 장애 응답은 실서비스에서 재현할 수 없으므로
 * JDK 내장 {@link HttpServer} 로 응답을 만들어 준다. 실호출 확인은
 * {@link VworldGeocodingClientLiveTest} 가 담당한다.
 */
class VworldGeocodingClientTest {

    private static final String API_KEY = "TEST-KEY-DO-NOT-USE";

    private HttpServer server;
    private String baseUrl;

    /** 요청 순서대로 쌓이는 {@code type} 파라미터 — 몇 번, 어떤 순서로 불렸는지 확인용. */
    private final List<String> requestedTypes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/req/address";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("status=OK 면 point.x=경도 / point.y=위도 로 좌표를 만든다 — 지번 1회로 끝")
    void parsesOkResponse() {
        respondWith(() -> ok("126.51716032806468", "33.518143074484456"));

        GeocodeOutcome outcome = newClient(4).geocode("제주특별자치도 제주시 삼도이동 1264");

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.FOUND);
        // NUMERIC(12,10)/(13,10) 에 맞춰 소수 10자리로 반올림된다.
        assertThat(outcome.point().lat()).isEqualByComparingTo("33.5181430745");
        assertThat(outcome.point().lot()).isEqualByComparingTo("126.5171603281");
        // 지번에서 성공했으면 도로명은 부르지 않는다.
        assertThat(requestedTypes).containsExactly("parcel");
    }

    @Test
    @DisplayName("지번 NOT_FOUND 면 도로명으로 한 번 더 — 재시도는 하지 않는다(총 2회)")
    void fallsBackToRoadWithoutRetry() {
        respondWith(VworldGeocodingClientTest::notFound);

        VworldGeocodingClient client = newClient(4);
        GeocodeOutcome outcome = client.geocode("제주특별자치도 제주시 구좌읍 상도리 3204-1");

        // 제공자가 "없다"고 답한 것이므로 영구 실패로 확정해도 된다.
        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
        // NOT_FOUND 는 "그 주소가 없다"라 재시도해도 결과가 같다 — max-attempts(4)를 태우면 안 된다.
        assertThat(requestedTypes).containsExactly("parcel", "road");
        assertThat(client.apiCallCount()).isEqualTo(2);
        // 타입별 판정이 스킵 note 의 앞부분이 된다(Step 7-4).
        assertThat(outcome.detail()).isEqualTo("parcel=NOT_FOUND, road=NOT_FOUND");
    }

    @Test
    @DisplayName("OVER_REQUEST_LIMIT 은 재시도 0회로 GeocodeQuotaExceededException 을 던진다")
    void propagatesQuotaExceededWithoutRetry() {
        respondWith(() -> error("OVER_REQUEST_LIMIT", "하루 요청 제한을 초과하였습니다."));

        VworldGeocodingClient client = newClient(4);

        assertThatThrownBy(() -> client.geocode("제주특별자치도 제주시 연동 312-1"))
                .isInstanceOf(GeocodeQuotaExceededException.class)
                .hasMessageContaining("OVER_REQUEST_LIMIT");
        // 재시도도, 도로명 폴백도 없이 첫 호출에서 즉시 중단돼야 한다(D9).
        assertThat(client.apiCallCount()).isEqualTo(1);
        assertThat(requestedTypes).containsExactly("parcel");
    }

    @Test
    @DisplayName("일시 ERROR 는 max-attempts 만큼 재시도한 뒤 TRANSIENT 로 넘어간다 — NOT_FOUND 가 아니다")
    void retriesTransientErrors() {
        respondWith(() -> error("SERVER_ERROR", "일시적인 오류입니다."));

        // 백오프(300ms × 시도횟수)가 실제로 도므로 시도 횟수를 2로 줄여 테스트 시간을 아낀다.
        // 운영 기본값 4 는 application-*.yml(geocode.vworld.max-attempts)에서 온다.
        VworldGeocodingClient client = newClient(2);
        GeocodeOutcome outcome = client.geocode("제주특별자치도 제주시 연동 312-1");

        // 재시도를 다 써도 예외가 아니라 TRANSIENT 로 내려가야 다음 폴백이 살고,
        // NOT_FOUND 와 구분돼야 호출자가 이걸 영구 실패로 캐싱하지 않는다(D8 개정).
        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.TRANSIENT);
        assertThat(outcome.coordinate()).isEmpty();
        // 타입 2종 × 시도 2회.
        assertThat(client.apiCallCount()).isEqualTo(4);
        assertThat(requestedTypes).containsExactly("parcel", "parcel", "road", "road");
    }

    @Test
    @DisplayName("지번이 일시 장애면 도로명이 NOT_FOUND 여도 전체는 TRANSIENT — 없다고 단정할 수 없다")
    void transientOnAnyTypeWinsOverNotFound() {
        // 지번은 계속 ERROR, 도로명은 NOT_FOUND.
        respondPerType(type -> "parcel".equals(type)
                ? error("SERVER_ERROR", "일시적인 오류입니다.")
                : notFound());

        GeocodeOutcome outcome = newClient(2).geocode("제주특별자치도 제주시 연동 312-1");

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.TRANSIENT);
        assertThat(requestedTypes).containsExactly("parcel", "parcel", "road");
        // 어느 타입이 장애였고 어느 타입이 없었는지가 로그·note 로 남는다.
        assertThat(outcome.detail()).isEqualTo("parcel=TRANSIENT, road=NOT_FOUND");
    }

    @Test
    @DisplayName("HTTP 5xx 도 재시도 대상이며, 예외 메시지에 인증키가 새지 않는다")
    void wrapsHttpErrorsWithoutLeakingApiKey() {
        server.createContext("/req/address", exchange -> {
            requestedTypes.add(typeOf(exchange));
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        VworldGeocodingClient client = newClient(1);

        // request() 를 직접 불러 감싸진 예외를 본다 — geocode() 는 재시도 소진 후 삼키기 때문에
        // 메시지를 확인할 수 없다. 이 클라이언트는 예외를 WARN 로그에 그대로 싣는다.
        assertThatThrownBy(() -> client.request("제주특별자치도 제주시 연동 312-1", "parcel"))
                .isInstanceOf(VworldCallSupport.TransientGeocodeException.class)
                .satisfies(thrown -> {
                    for (Throwable t = thrown; t != null; t = t.getCause()) {
                        assertThat(t.getMessage()).doesNotContain(API_KEY);
                    }
                });
    }

    private VworldGeocodingClient newClient(int maxAttempts) {
        return new VworldGeocodingClient(API_KEY, baseUrl, 3_000, maxAttempts);
    }

    /** 모든 요청에 같은 본문을 돌려주며 {@code type} 파라미터를 기록한다. */
    private void respondWith(Supplier<String> body) {
        respondPerType(type -> body.get());
    }

    /** {@code type}(parcel/road)별로 다른 본문을 돌려준다 — 한쪽만 장애인 상황을 만들 때 쓴다. */
    private void respondPerType(Function<String, String> bodyByType) {
        server.createContext("/req/address", exchange -> {
            String type = typeOf(exchange);
            requestedTypes.add(type);
            byte[] payload = bodyByType.apply(type).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
    }

    private static String typeOf(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        for (String param : query.split("&")) {
            if (param.startsWith("type=")) {
                return param.substring("type=".length());
            }
        }
        return "";
    }

    private static String ok(String x, String y) {
        return """
                {"response":{"status":"OK","result":{"crs":"EPSG:4326","point":{"x":"%s","y":"%s"}}}}
                """.formatted(x, y);
    }

    private static String notFound() {
        return """
                {"response":{"status":"NOT_FOUND","record":{"total":"0","current":"0"}}}
                """;
    }

    private static String error(String code, String text) {
        return """
                {"response":{"status":"ERROR","error":{"level":"2","code":"%s","text":"%s"}}}
                """.formatted(code, text);
    }
}
