package jeju.datahub.batch.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VworldPlaceSearchClient} — <b>주소 검증</b>과 상태별 처리 테스트 (D7 3단, 2026-08-05 재개정).
 *
 * <p>실제 VWorld 를 부르지 않는다. 이 단계의 핵심 위험은 "질의와 무관한 POI 를 채택하는 것"인데,
 * 그런 응답은 실서비스에서 의도적으로 만들 수 없으므로 JDK 내장 {@link HttpServer} 로 응답을
 * 만들어 준다. 실호출 확인은 {@link VworldPlaceSearchClientLiveTest} 가 담당한다.
 */
class VworldPlaceSearchClientTest {

    private static final String API_KEY = "TEST-KEY-DO-NOT-USE";

    /** 해녀박물관 일대 — 주소DB 에는 없고 POI 로만 나오는 실제 주소(원천 24행). */
    private static final String QUERY = "제주특별자치도 제주시 구좌읍 상도리 3204-1";

    private HttpServer server;
    private String baseUrl;

    /** 실제로 나간 HTTP 요청 수 — 재시도/무재시도 규율 확인용. */
    private final AtomicInteger requestCount = new AtomicInteger();

    /** 마지막 요청의 쿼리스트링(디코딩) — {@code bbox} 가 실제로 실렸는지 확인용. */
    private final AtomicReference<String> lastQueryString = new AtomicReference<>("");

    /** 운영 기본값과 같은 제주 영역(경도,위도 순). */
    private static final String JEJU_BBOX = "126.10,33.10,127.00,34.05";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/req/search";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("반환 주소가 질의와 같으면 채택 — items[0].point 의 x=경도 / y=위도")
    void adoptsExactAddressMatch() {
        respondWith(() -> ok(QUERY, "126.86342559014764", "33.52360987936556"));

        GeocodeOutcome outcome = newClient(4).geocode(QUERY);

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.FOUND);
        // NUMERIC(12,10)/(13,10) 에 맞춰 소수 10자리로 반올림된다(주소 API 와 동일).
        assertThat(outcome.point().lat()).isEqualByComparingTo("33.5236098794");
        assertThat(outcome.point().lot()).isEqualByComparingTo("126.8634255901");
        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("🔴 끝에 지목 1자가 붙은 반환 주소도 채택한다 — 안 그러면 대다수가 버려진다")
    void adoptsAddressWithTrailingLandCategory() {
        // 실측: 질의 '…삼도이동 1264' → 반환 '…1264도'(도로), 질의 '…560-2' → 반환 '…560-2대'(대지).
        String query = "제주특별자치도 제주시 삼도이동 1264";
        respondWith(() -> ok(query + "도", "126.51868392296353", "33.51836238811842"));

        assertThat(newClient(4).geocode(query).status()).isEqualTo(GeocodeOutcome.Status.FOUND);

        String parcelQuery = "제주특별자치도 제주시 외도일동 560-2";
        assertThat(VworldPlaceSearchClient.matchesQuery(parcelQuery, parcelQuery + "대")).isTrue();
    }

    @Test
    @DisplayName("🔴 질의와 다른 지번의 POI 는 거부하고 NOT_FOUND — 좌표가 엉뚱한 곳에 찍히면 안 된다")
    void rejectsUnrelatedPoi() {
        // 검색은 지오코딩이 아니라 질의와 무관한 POI 를 돌려줄 수 있다.
        respondWith(() -> ok("제주특별자치도 제주시 연동 312-1", "126.4900000", "33.4800000"));

        GeocodeOutcome outcome = newClient(4).geocode(QUERY);

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
        assertThat(outcome.coordinate()).isEmpty();
        // 왜 버렸는지가 스킵 note 에 남아야 사후에 판단할 수 있다.
        assertThat(outcome.detail()).startsWith(VworldPlaceSearchClient.MISMATCH_DETAIL_PREFIX)
                .contains("제주특별자치도 제주시 연동 312-1");
        // 없는 것으로 확정하는 것이지 장애가 아니다 — 재시도하지 않는다.
        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("🔴 접두 일치를 쓰지 않는다 — '상도리 3204' 가 '상도리 32041'/'3204-1' 에 걸리면 안 된다")
    void doesNotMatchByPrefix() {
        String mainLot = "제주특별자치도 제주시 구좌읍 상도리 3204";

        // 부번이 다른 별개 필지. 실측으로 '상도리 3204' 검색 시 실제로 이 POI 가 첫 건으로 나온다.
        assertThat(VworldPlaceSearchClient.matchesQuery(mainLot, mainLot + "-1")).isFalse();
        // 본번 자체가 다른 필지.
        assertThat(VworldPlaceSearchClient.matchesQuery(mainLot, mainLot + "1")).isFalse();
        // 지목 1자를 뗀 뒤 정확히 일치하는 경우만 통과한다.
        assertThat(VworldPlaceSearchClient.matchesQuery(mainLot, mainLot + "대")).isTrue();
        assertThat(VworldPlaceSearchClient.matchesQuery(mainLot, mainLot)).isTrue();
        // 반환 주소가 아예 없으면 검증할 근거가 없다 — 채택하지 않는다.
        assertThat(VworldPlaceSearchClient.matchesQuery(mainLot, null)).isFalse();
    }

    @Test
    @DisplayName("status=NOT_FOUND 는 재시도 없이 즉시 NOT_FOUND — 다음 단계로 넘긴다")
    void returnsNotFoundWithoutRetry() {
        respondWith(VworldPlaceSearchClientTest::notFound);

        VworldPlaceSearchClient client = newClient(4);
        GeocodeOutcome outcome = client.geocode("제주특별자치도 제주시 구좌읍 상도리 999999-999");

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
        assertThat(outcome.detail()).isEqualTo("place=NOT_FOUND");
        // "그 주소가 없다"는 재시도해도 결과가 같다 — max-attempts(4)를 태우면 안 된다.
        assertThat(client.apiCallCount()).isEqualTo(1);
        assertThat(requestCount).hasValue(1);
    }

    @Test
    @DisplayName("OVER_REQUEST_LIMIT 은 재시도 0회로 GeocodeQuotaExceededException 을 던진다(D9)")
    void propagatesQuotaExceededWithoutRetry() {
        respondWith(() -> error("OVER_REQUEST_LIMIT", "하루 요청 제한을 초과하였습니다."));

        VworldPlaceSearchClient client = newClient(4);

        assertThatThrownBy(() -> client.geocode(QUERY))
                .isInstanceOf(GeocodeQuotaExceededException.class)
                .hasMessageContaining("OVER_REQUEST_LIMIT");
        assertThat(client.apiCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("일시 ERROR 는 max-attempts 만큼 재시도한 뒤 TRANSIENT — NOT_FOUND 가 아니다")
    void retriesTransientErrors() {
        respondWith(() -> error("SERVER_ERROR", "일시적인 오류입니다."));

        // 백오프(300ms × 시도횟수)가 실제로 도므로 시도 횟수를 2로 줄인다(운영 기본값은 4).
        VworldPlaceSearchClient client = newClient(2);
        GeocodeOutcome outcome = client.geocode(QUERY);

        // TRANSIENT 여야 호출자가 이걸 영구 실패로 캐싱하지 않는다(D8 개정).
        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.TRANSIENT);
        assertThat(outcome.detail()).isEqualTo("place=TRANSIENT");
        assertThat(client.apiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("HTTP 5xx 도 재시도 대상이며, 예외 메시지에 인증키가 새지 않는다")
    void wrapsHttpErrorsWithoutLeakingApiKey() {
        server.createContext("/req/search", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        VworldPlaceSearchClient client = newClient(1);

        // request() 를 직접 부른다 — geocode() 는 재시도 소진 후 예외를 삼켜 메시지를 볼 수 없다.
        assertThatThrownBy(() -> client.request(QUERY))
                .isInstanceOf(VworldCallSupport.TransientGeocodeException.class)
                .satisfies(thrown -> {
                    for (Throwable t = thrown; t != null; t = t.getCause()) {
                        assertThat(t.getMessage()).doesNotContain(API_KEY);
                    }
                });
    }

    @Test
    @DisplayName("status=OK 인데 items 가 비면 미조회로 처리한다")
    void treatsEmptyItemsAsNotFound() {
        respondWith(() -> """
                {"response":{"status":"OK","result":{"crs":"EPSG:4326","type":"place","items":[]}}}
                """);

        assertThat(newClient(4).geocode(QUERY).status())
                .isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("🔴 bbox 가 설정되면 쿼리에 그대로 실린다 — 순서는 경도,위도(minx,miny,maxx,maxy)")
    void sendsConfiguredBbox() {
        respondWith(() -> ok(QUERY, "126.86342559014764", "33.52360987936556"));

        assertThat(newClient(4).geocode(QUERY).status()).isEqualTo(GeocodeOutcome.Status.FOUND);

        // 🔴 위도를 앞에 쓰면 영역이 제주를 벗어나 실호출에서 전건 NOT_FOUND 가 된다(2026-09-29 실측).
        //    그래서 값 자체를 문자열로 못박는다 — 순서가 뒤집히면 여기서 깨진다.
        assertThat(lastQueryString.get()).contains("bbox=126.10,33.10,127.00,34.05");
        // crs 기준으로 해석되는 값이라 EPSG:4326 과 짝으로 나가야 한다.
        assertThat(lastQueryString.get()).contains("crs=EPSG:4326");
    }

    @Test
    @DisplayName("bbox 가 비어 있으면 파라미터를 아예 싣지 않는다 — 되돌림 스위치")
    void omitsBboxWhenNotConfigured() {
        respondWith(() -> ok(QUERY, "126.86342559014764", "33.52360987936556"));

        assertThat(newClient(4, "  ").geocode(QUERY).status())
                .isEqualTo(GeocodeOutcome.Status.FOUND);

        // 빈 값을 그대로 실어 'bbox=' 로 보내면 VWorld 가 거절할 수 있다 — 키 자체가 없어야 한다.
        assertThat(lastQueryString.get()).doesNotContain("bbox");
    }

    @Test
    @DisplayName("빈 주소는 호출조차 하지 않는다")
    void skipsBlankAddress() {
        respondWith(() -> ok(QUERY, "126.8", "33.5"));

        assertThat(newClient(4).geocode("   ").status()).isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
        assertThat(requestCount).hasValue(0);
    }

    private VworldPlaceSearchClient newClient(int maxAttempts) {
        return newClient(maxAttempts, JEJU_BBOX);
    }

    private VworldPlaceSearchClient newClient(int maxAttempts, String bbox) {
        return new VworldPlaceSearchClient(API_KEY, baseUrl, 3_000, maxAttempts, new JejuBounds(bbox));
    }

    private void respondWith(Supplier<String> body) {
        server.createContext("/req/search", exchange -> {
            requestCount.incrementAndGet();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            lastQueryString.set(
                    rawQuery == null ? "" : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8));
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
    }

    /** 실응답 구조 그대로 — {@code result.items[].address.parcel} 과 {@code items[].point}. */
    private static String ok(String parcel, String x, String y) {
        return """
                {"response":{"status":"OK","result":{"crs":"EPSG:4326","type":"place","items":[
                  {"id":"POI01000000NDELA5","title":"전기차충전소",
                   "address":{"road":"제주특별자치도 제주시 구좌읍 해녀박물관길 26","parcel":"%s"},
                   "point":{"x":"%s","y":"%s"}}]}}}
                """.formatted(parcel, x, y);
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
