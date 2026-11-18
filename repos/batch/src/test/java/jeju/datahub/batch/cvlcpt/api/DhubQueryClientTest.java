package jeju.datahub.batch.cvlcpt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link DhubQueryClient} 의 요청 조립·응답 해석과 인증 연동 테스트
 * ({@code POST /api/v1/analytics/query}).
 *
 * <p>확인하는 것은 넷이다 — <b>{@code rows} 봉투</b>를 벗겨 배열을 돌려주는지,
 * <b>{@code kind}/{@code source_id}/{@code max_rows} 를 실어 보내는지</b>(생략하면 서버가 100행으로
 * 자른다), <b>401 을 {@link DhubUnauthorizedException} 으로 바꿔</b> {@link DhubAuthClient} 의
 * 재시도를 발동시키는지, 그리고 <b>예외·로그에 토큰이 새지 않는지</b>.
 */
class DhubQueryClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 실제 응답 1건(2026-08-21 실호출)에서 개인정보 컬럼을 뺀 형태 — rows 봉투가 있다. */
    private static final String TWO_ROWS = """
            {"rows":[
              {"dclr_no":"SPP-2501-1000255","dclr_ymd":"2025-01-10","src_path":"모바일앱",
               "dclr_ocrn_rgn":"제주특별자치도 제주시 연동 272","dclr_type":"횡단보도",
               "dclr_cn_sntmnt":"보통","dclr_grp_nm":null},
              {"dclr_no":"SPP-2501-1000256","dclr_ymd":"2025-01-10","src_path":"포털",
               "dclr_ocrn_rgn":"제주특별자치도 제주시 연동 312-1","dclr_type":"장애인전용구역",
               "dclr_cn_sntmnt":"부정","dclr_grp_nm":null}
             ],"row_count":2,"truncated":false,
             "source":{"kind":"dataset","id":"dataset.25c829ec-66bd-4dc8-84d8-ba33442962a9"},
             "database":"jeju_smart_city_test","table":"minwon_sentiment"}""";

    private HttpServer server;
    private DhubProperties properties;
    private DhubAuthClient authClient;

    /** 조회 요청이 실려 온 Authorization 헤더 — 재시도가 새 토큰으로 나갔는지 본다. */
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    /** 조회 요청 본문 전체 — query 이스케이프와 kind/source_id/max_rows 를 함께 본다. */
    private final List<JsonNode> receivedBodies = new CopyOnWriteArrayList<>();

    private final AtomicInteger queryHits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(DhubAuthClient.LOGIN_PATH, exchange -> DhubAuthClientTest.respond(exchange, 200,
                """
                {"access_token":"%s","token_type":"bearer"}"""
                        .formatted(DhubAuthClientTest.jwtExpiringAt(Instant.now().plusSeconds(900)))));
        server.start();

        properties = new DhubProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setEmail("tester@example.com");
        properties.setPassword("TEST-ONLY-NOT-A-REAL-SECRET");
        properties.setTableId("dataset.25c829ec-66bd-4dc8-84d8-ba33442962a9");
        properties.setSourceTable("`db`.`tbl`");
        properties.setTimeoutMs(3_000);
        authClient = new DhubAuthClient(properties);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("rows 봉투를 벗겨 행 배열을 돌려준다")
    void unwrapsRowsEnvelope() {
        serveQuery(exchange -> reply(200, TWO_ROWS));

        ArrayNode rows = newClient().query("SELECT dclr_no FROM `db`.`tbl`");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("dclr_no").asText()).isEqualTo("SPP-2501-1000255");
        // NULL 은 JSON null 로 온다 — 문자열 "null" 이 아니다.
        assertThat(rows.get(0).path("dclr_grp_nm").isNull()).isTrue();
        assertThat(receivedBodies).hasSize(1);
    }

    @Test
    @DisplayName("🔴 kind · source_id · max_rows 를 실어 보낸다 — max_rows 를 빼면 서버가 100행으로 자른다")
    void sendsSourceAndMaxRows() {
        serveQuery(exchange -> reply(200, "{\"rows\":[],\"truncated\":false}"));

        // 따옴표가 섞인 SQL 이 JSON 본문에서 깨지면 서버가 받는 문자열이 달라진다.
        String sql = "SELECT count(*) AS cnt FROM `db`.`tbl` WHERE dclr_ymd >= '2025-01-01'";
        newClient().query(sql, 500);

        JsonNode body = receivedBodies.get(0);
        assertThat(body.path("kind").asText()).isEqualTo("dataset");
        assertThat(body.path("source_id").asText())
                .isEqualTo("dataset.25c829ec-66bd-4dc8-84d8-ba33442962a9");
        assertThat(body.path("query").asText()).isEqualTo(sql);
        assertThat(body.path("max_rows").asInt()).isEqualTo(500);
        // 데이터셋은 본문으로 지정한다 — 경로에는 더 이상 table-id 가 들어가지 않는다.
        assertThat(requestPaths).containsExactly("/api/v1/analytics/query");
    }

    @Test
    @DisplayName("max_rows 기본값은 dhub.page-size 이고, 상한(1000)을 넘기면 호출 전에 막힌다")
    void defaultsMaxRowsToPageSizeAndRejectsOverCap() {
        serveQuery(exchange -> reply(200, "{\"rows\":[],\"truncated\":false}"));
        properties.setPageSize(250);

        newClient().query("SELECT 1");

        assertThat(receivedBodies.get(0).path("max_rows").asInt()).isEqualTo(250);
        // 서버가 422 로 거절하기 전에 우리가 먼저 막는다(AnalyticsQueryRequest.max_rows ≤ 1000).
        assertThatThrownBy(() -> newClient().query("SELECT 1", DhubSql.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("🔴 truncated=true 는 조용히 넘기지 않고 실패한다 — 잘린 페이지는 keyset 을 건너뛰게 한다")
    void failsOnTruncatedResponse() {
        serveQuery(exchange -> reply(200,
                "{\"rows\":[{\"dclr_no\":\"SPP-2501-1000255\"}],\"row_count\":1,\"truncated\":true}"));

        DhubQueryClient client = newClient();
        assertThatThrownBy(() -> client.query("SELECT dclr_no FROM `db`.`tbl` LIMIT 1000", 1000))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    @DisplayName("count(*) 응답 {rows:[{\"cnt\":N}]} 에서 건수를 읽는다")
    void readsCount() {
        serveQuery(exchange -> reply(200, "{\"rows\":[{\"cnt\":106579}],\"truncated\":false}"));

        assertThat(newClient().count("SELECT count(*) AS cnt FROM `db`.`tbl`")).isEqualTo(106_579L);
        // 한 행짜리 질의라 max_rows 는 1 이면 충분하다.
        assertThat(receivedBodies.get(0).path("max_rows").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("401 이면 재로그인 후 1회 재시도하고, 재시도는 새 토큰으로 나간다")
    void retriesOnceWithFreshTokenOn401() {
        AtomicInteger hits = new AtomicInteger();
        serveQuery(exchange -> hits.incrementAndGet() == 1
                ? reply(401, "{\"detail\":\"Not authenticated\"}")
                : reply(200, TWO_ROWS));

        ArrayNode rows = newClient().query("SELECT dclr_no FROM `db`.`tbl`");

        assertThat(rows).hasSize(2);
        assertThat(hits).hasValue(2);
        assertThat(authClient.loginCount()).isEqualTo(2); // 최초 1 + 401 후 1
        assertThat(authHeaders).hasSize(2);
        assertThat(authHeaders.get(1)).isNotEqualTo(authHeaders.get(0));
        assertThat(authHeaders).allSatisfy(header -> assertThat(header).startsWith("Bearer "));
    }

    @Test
    @DisplayName("계속 401 이면 2회에서 멈추고 예외를 던진다 — 무한 재로그인 금지")
    void stopsAfterSingleRetry() {
        serveQuery(exchange -> reply(401, "{\"detail\":\"Not authenticated\"}"));

        DhubQueryClient client = newClient();
        assertThatThrownBy(() -> client.query("SELECT 1"))
                .isInstanceOf(DhubUnauthorizedException.class);

        assertThat(queryHits).hasValue(2);
        assertThat(client.requestCount()).isEqualTo(2);
        assertThat(authClient.loginCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("500 은 재시도하지 않고, 예외 메시지에 응답 본문이 실리지 않는다")
    void wrapsServerErrorsWithoutLeakingBody() {
        String bodyText = "clickhouse-internal-detail-should-not-leak";
        serveQuery(exchange -> reply(500, "{\"detail\":\"" + bodyText + "\"}"));

        DhubQueryClient client = newClient();
        assertThatThrownBy(() -> client.query("SELECT 1"))
                .isInstanceOf(DhubApiException.class)
                .isNotInstanceOf(DhubUnauthorizedException.class)
                .hasMessageContaining("500")
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(bodyText));

        // 401 이 아니므로 재시도하지 않는다.
        assertThat(queryHits).hasValue(1);
        assertThat(authClient.loginCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답에 rows 가 없으면(스펙 변경) 조용히 넘어가지 않고 실패한다")
    void rejectsResponseWithoutRows() {
        serveQuery(exchange -> reply(200, "[{\"dclr_no\":\"SPP-2501-1000255\"}]"));

        DhubQueryClient client = newClient();
        assertThatThrownBy(() -> client.query("SELECT 1"))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("rows");
    }

    @Test
    @DisplayName("어떤 실패에서도 예외 메시지에 Bearer 토큰이 새지 않는다")
    void neverLeaksBearerTokenInExceptions() {
        serveQuery(exchange -> reply(500, "boom"));

        DhubQueryClient client = newClient();
        String token = authClient.currentToken();

        assertThatThrownBy(() -> client.query("SELECT 1"))
                .satisfies(thrown -> {
                    for (Throwable t = thrown; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(token);
                    }
                });
    }

    private DhubQueryClient newClient() {
        return new DhubQueryClient(authClient, properties);
    }

    // --- 테스트용 HTTP 서버 -------------------------------------------------------------

    private final List<String> requestPaths = new CopyOnWriteArrayList<>();

    private record Reply(int status, String body) {
    }

    private static Reply reply(int status, String body) {
        return new Reply(status, body);
    }

    /** 조회 엔드포인트를 등록한다. 요청마다 Authorization 헤더·경로·본문을 기록한다. */
    private void serveQuery(java.util.function.Function<HttpExchange, Reply> responder) {
        server.createContext(DhubQueryClient.QUERY_PATH, exchange -> {
            queryHits.incrementAndGet();
            requestPaths.add(exchange.getRequestURI().getPath());
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(MAPPER.readTree(requestBody));
            Reply reply = responder.apply(exchange);
            DhubAuthClientTest.respond(exchange, reply.status(), reply.body());
        });
    }
}
