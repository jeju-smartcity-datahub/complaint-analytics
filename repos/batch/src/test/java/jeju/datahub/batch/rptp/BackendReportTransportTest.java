package jeju.datahub.batch.rptp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 🔴 <b>전송 계층 회귀 테스트</b> (케이스 10) — 요청이 <b>실제 소켓</b>에 어떤 모양으로
 * 나가는지 본다.
 *
 * <h2>왜 따로 필요한가</h2>
 * {@link BackendReportClientTest} 가 쓰는 {@code MockRestServiceServer} 는 <b>전송 계층을 타지 않는다</b> —
 * 실제 소켓도, 실제 HTTP 헤더도 없다. 그래서 아래 부류의 장애를 <b>원리적으로 한 건도 잡지 못한다</b>.
 *
 * <h2>잡으려는 장애 (선례: AI 어시스턴트 전송 경로, 2026-09-08 E2E 실측)</h2>
 * {@code POST} 가 바디를 <b>스트리밍</b>하면 {@code Transfer-Encoding: chunked} 로 나가고,
 * 업스트림 게이트웨이(istio-envoy)가 이를 <b>400 {@code Invalid HTTP request received.}</b> 로 거절한다.
 * <ul>
 *   <li>🔴 {@code Simple…ClientHttpRequestFactory}(JDK {@code HttpURLConnection})가 그렇게 보낸다.</li>
 *   <li>🔴 {@code JdkClientHttpRequestFactory} <b>하나만으로도 안 고쳐진다</b> — 길이 없이 publisher 를
 *       넘겨 역시 chunked 다. 그래서 {@link BackendReportClient} 는
 *       {@code BufferingClientHttpRequestFactory} 로 <b>한 겹 더</b> 감싼다.</li>
 *   <li>GET 은 바디가 없어 안 걸리므로 증상이 <b>"조회는 되는데 전송만 400"</b> 으로 나타난다 —
 *       바디 내용을 의심하면 못 찾는다.</li>
 * </ul>
 *
 * <h2>🔴 반드시 운영 생성자 경로로 만든다</h2>
 * 검증 대상이 <b>{@link BackendReportClient} 가 스스로 조립하는 요청 팩토리</b>이므로, 목을 심는
 * package-private 시임이 아니라 {@code new BackendReportClient(properties)} 를 쓴다.
 * 서버는 JDK 내장 {@link HttpServer} 라 <b>새 의존성이 없다</b>.
 */
class BackendReportTransportTest {

    private static final String API_KEY = "TEST-ONLY-NOT-A-REAL-KEY";

    private static final ReportAutoCrtCommand COMMAND = new ReportAutoCrtCommand(
            7002L, "CVLCPT", "2026-Q1", "20260101", "20260331",
            "서귀포시", "50130250", "ALL", "QUARTER");

    private static final String CREATED_BODY = """
            {"rptpId":902,"excnId":402,"rptpNm":"2026년 1분기 민원 분석 보고서",
             "trgtPrdCd":"2026-Q1","created":true}""";

    private HttpServer server;
    private String baseUrl;

    /** 마지막으로 수신한 요청의 헤더·바디·메서드·경로 — 소켓에 실제로 실린 것이다. */
    private final AtomicReference<Headers> lastHeaders = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    /** 스텁이 돌려줄 상태코드. 케이스별로 바꾼다. */
    private volatile int responseStatus = 201;
    private volatile String responseBody = CREATED_BODY;

    @BeforeEach
    void startServer() throws IOException {
        // 포트 0 = 임의의 빈 포트. loopback 에만 바인딩한다(8180 사용자 백엔드와 무관).
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(BackendReportClient.AUTO_CRT_PATH, this::handle);
        server.setExecutor(null);
        server.start();
        baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                + ":" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        // 🔴 헤더는 바디를 읽기 전에 붙잡는다 — 여기가 이 테스트의 관측 지점이다.
        lastHeaders.set(ex.getRequestHeaders());
        lastMethod.set(ex.getRequestMethod());
        lastPath.set(ex.getRequestURI().getPath());
        try (InputStream in = ex.getRequestBody()) {
            lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
        ex.sendResponseHeaders(responseStatus, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 🔴 목 시임이 아니라 <b>운영 생성자</b>다 — 요청 팩토리 조립 자체가 검증 대상이다. */
    private BackendReportClient productionClient() {
        return new BackendReportClient(
                new BackendReportProperties(baseUrl, API_KEY, 2_000, 5_000));
    }

    // ------------------------------------------------------------------ 케이스 10

    @Test
    @DisplayName("10. 🔴 전송 요청은 Content-Length 로 나간다 — Transfer-Encoding: chunked 가 아니다(게이트웨이 400 회귀)")
    void requestUsesContentLengthNotChunked() {
        BackendReportClient.Result result = productionClient().autoCreate(COMMAND);

        Headers h = lastHeaders.get();
        assertThat(h).as("전송 요청이 스텁 서버에 닿아야 한다").isNotNull();

        // 🔴 핵심 단언 ① — 게이트웨이(istio-envoy)가 거절하는 것은 chunked 요청 바디다.
        assertThat(h.getFirst("Transfer-Encoding"))
                .as("Transfer-Encoding 헤더가 없어야 한다 "
                        + "(chunked 면 업스트림이 400 Invalid HTTP request received. 를 낸다)")
                .isNull();

        // 🔴 핵심 단언 ② — 길이가 붙어 있어야 버퍼링된 것이다.
        assertThat(h.getFirst("Content-Length"))
                .as("Content-Length 헤더가 붙어야 한다")
                .isNotNull();

        // 🔴 핵심 단언 ③ — 길이가 실제 바디 바이트 수와 같아야 진짜 버퍼링이다(0 이나 추정치가 아니다).
        String body = lastBody.get();
        assertThat(Integer.parseInt(h.getFirst("Content-Length")))
                .isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(body).isNotEmpty();

        // 전송 경로 전체가 산 것인지 — 응답 매핑까지 확인한다.
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.rptpId()).isEqualTo(902L);
        assertThat(result.created()).isTrue();
    }

    @Test
    @DisplayName("10-1. 실소켓에 실린 메서드·경로·헤더·바디가 계약대로다 — X-API-Key 포함")
    void requestCarriesContractOverRealSocket() throws IOException {
        productionClient().autoCreate(COMMAND);

        assertThat(lastMethod.get()).isEqualTo("POST");
        // 🔴 base-url 끝의 '/' 를 정규화하지 않으면 여기가 //api/... 가 된다.
        assertThat(lastPath.get()).isEqualTo(BackendReportClient.AUTO_CRT_PATH);

        Headers h = lastHeaders.get();
        assertThat(h.getFirst(BackendReportClient.API_KEY_HEADER)).isEqualTo(API_KEY);
        assertThat(h.getFirst("Content-Type")).startsWith("application/json");
        assertThat(h.getFirst("Accept")).contains("application/json");

        JsonNode root = new ObjectMapper().readTree(lastBody.get());
        assertThat(root.path("stngId").asLong()).isEqualTo(7002L);
        assertThat(root.path("rptpTypeCd").asText()).isEqualTo("CVLCPT");
        assertThat(root.path("trgtPrdCd").asText()).isEqualTo("2026-Q1");
        assertThat(root.path("bgngYmd").asText()).isEqualTo("20260101");
        assertThat(root.path("endYmd").asText()).isEqualTo("20260331");
        assertThat(root.path("crltnCyclCd").asText()).isEqualTo("QUARTER");
        assertThat(root.path("sggNm").asText()).isEqualTo("서귀포시");
    }

    @Test
    @DisplayName("10-2. 🔴 실소켓에서도 4xx/5xx 가 예외가 되지 않는다 — onStatus no-op 이 전송 계층까지 산다")
    void keepsStatusCodeOverRealSocket() {
        responseStatus = 503;
        responseBody = "{\"code\":\"SERVICE_UNAVAILABLE\"}";

        BackendReportClient.Result result = productionClient().autoCreate(COMMAND);

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.rptpId()).isNull();
        // 실패 응답이어도 요청 자체는 Content-Length 로 나갔다.
        assertThat(lastHeaders.get().getFirst("Transfer-Encoding")).isNull();
        assertThat(lastHeaders.get().getFirst("Content-Length")).isNotNull();
    }

    @Test
    @DisplayName("10-3. base-url 끝에 '/' 가 붙어도 경로가 //api/... 로 겹치지 않는다")
    void normalizesTrailingSlashInBaseUrl() {
        BackendReportClient c = new BackendReportClient(
                new BackendReportProperties(baseUrl + "/", API_KEY, 2_000, 5_000));

        assertThat(c.autoCreate(COMMAND).status()).isEqualTo(201);
        assertThat(lastPath.get()).isEqualTo(BackendReportClient.AUTO_CRT_PATH);
    }
}
