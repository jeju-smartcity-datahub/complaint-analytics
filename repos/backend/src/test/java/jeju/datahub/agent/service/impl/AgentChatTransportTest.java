package jeju.datahub.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jeju.datahub.agent.service.AgentProperties;
import jeju.datahub.agent.service.AgentResponseFilter;
import jeju.datahub.agent.service.AiMessageVO;
import jeju.datahub.agent.service.AiSessionListVO;
import jeju.datahub.agent.service.impl.filter.MinimaxResponseFilter;

/**
 * 🔴 <b>전송 계층 회귀 테스트</b> — 요청이 실제 소켓에 어떤 모양으로 나가는지 본다 (TSK-4122).
 *
 * <h2>왜 이 테스트가 따로 필요한가</h2>
 * {@code AgentChatClientTest}·{@code AgentApiClientTest} 가 쓰는 {@link
 * org.springframework.test.web.client.MockRestServiceServer} 는 <b>전송 계층을 타지 않는다</b> —
 * 실제 소켓도, 실제 HTTP 헤더도 없다. 그래서 아래 운영 장애를 <b>한 건도 잡지 못했다</b>.
 *
 * <h2>잡으려는 장애 (2026-09-08 E2E 실측)</h2>
 * {@code POST /v1/chat/completions} 가 항상 <b>400 {@code Invalid HTTP request received.}</b> 로
 * 거절됐다. 원인은 바디 내용이 아니라 <b>전송 인코딩</b>이었다.
 * <ul>
 *   <li>{@code SimpleClientHttpRequestFactory}(JDK {@code HttpURLConnection})는 요청 바디를
 *       버퍼링하지 않고 <b>스트리밍</b>해 {@code Transfer-Encoding: chunked} 로 보낸다.</li>
 *   <li>업스트림 게이트웨이(istio-envoy)가 <b>chunked 요청 바디를 거절</b>한다 — curl 실측:
 *       {@code Content-Length} 면 HTTP/1.1·HTTP/2 모두 200, {@code chunked} 면 400.</li>
 *   <li>조회({@code GET})가 멀쩡했던 것은 바디가 없어 chunked 가 될 일이 없었기 때문이다.</li>
 * </ul>
 * 그래서 {@link AgentApiClient} 는 {@code JdkClientHttpRequestFactory} 를 쓴다 — Spring 이 바디를
 * 버퍼링해 {@code Content-Length} 를 붙인다.
 *
 * <h2>🔴 반드시 운영 생성자 경로로 만든다</h2>
 * 검증 대상이 <b>{@link AgentApiClient} 가 스스로 조립하는 요청 팩토리</b>이므로, 목을 심는
 * package-private 시임이 아니라 {@code new AgentApiClient(RestClient.builder(), props, filters)} 를
 * 쓴다. 서버는 JDK 내장 {@link HttpServer} 라 <b>새 의존성이 없다</b>.
 */
class AgentChatTransportTest {

    private static final String AGENT_ID = "agent.0edd4421";
    private static final String TOKEN = "test-access-token";
    private static final String SESSION_ID = "portal-transport-test";
    private static final String QUESTION = "연동 지역 주차 민원이 가장 많은 시간대는?";

    /** 🔴 컨테이너와 같은 모양(빈 이름 키) — 클라이언트가 {@code modelName()} 으로 다시 인덱싱한다. */
    private static final Map<String, AgentResponseFilter> FILTERS =
            Map.of("minimaxResponseFilter", new MinimaxResponseFilter());

    private static final String CHAT_BODY = """
            {
              "id": "chatcmpl-transport",
              "object": "chat.completion",
              "model": "innopam_test",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "연동입니다." },
                  "finish_reason": "stop"
                }
              ]
            }""";

    private static final String SESSIONS_BODY = """
            {
              "sessions": [
                { "session_id": "portal-1", "agent_id": "agent.0edd4421",
                  "created_at": "2026-09-01T10:00:00Z", "updated_at": "2026-09-01T10:05:00Z",
                  "message_count": 2, "preview": "안녕" }
              ],
              "next_cursor": null,
              "has_more": false
            }""";

    private HttpServer server;
    private String baseUrl;

    /** 마지막으로 수신한 요청의 헤더·바디 — 소켓에 실제로 실린 것이다. */
    private final AtomicReference<Headers> lastHeaders = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        // 포트 0 = 임의의 빈 포트. loopback 에만 바인딩한다.
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", ex -> handle(ex, CHAT_BODY, "text/event-stream;charset=utf-8"));
        server.createContext("/v1/sessions", ex -> handle(ex, SESSIONS_BODY, "application/json;charset=utf-8"));
        server.setExecutor(null);
        server.start();
        baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex, String responseBody, String contentType) throws IOException {
        // 🔴 헤더는 바디를 읽기 전에 붙잡는다 — 여기가 이 테스트의 관측 지점이다.
        lastHeaders.set(ex.getRequestHeaders());
        try (InputStream in = ex.getRequestBody()) {
            lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 🔴 목 시임이 아니라 <b>운영 생성자</b>다 — 요청 팩토리 조립 자체가 검증 대상이다. */
    private AgentApiClient productionClient() {
        AgentProperties p = new AgentProperties();
        p.setBaseUrl(baseUrl);
        p.setAgentId(AGENT_ID);
        p.setDefaultLimit(20);
        p.setModel("minimax");
        return new AgentApiClient(RestClient.builder(), p, FILTERS);
    }

    // ------------------------------------------------------------------ 전송(POST)

    @Test
    @DisplayName("🔴 전송 요청은 Content-Length 로 나간다 — Transfer-Encoding: chunked 가 아니다(게이트웨이 400 회귀)")
    void chatRequestUsesContentLengthNotChunked() {
        AiMessageVO vo = productionClient().sendMessage(TOKEN, SESSION_ID, QUESTION);

        Headers h = lastHeaders.get();
        assertThat(h).as("전송 요청이 서버에 닿아야 한다").isNotNull();

        // 🔴 핵심 단언 — 게이트웨이(istio-envoy)가 거절하는 것은 chunked 요청 바디다.
        assertThat(h.getFirst("Transfer-Encoding"))
                .as("Transfer-Encoding 헤더가 없어야 한다 (chunked 면 업스트림이 400 Invalid HTTP request received.)")
                .isNull();
        assertThat(h.getFirst(HttpHeaders.CONTENT_LENGTH))
                .as("Content-Length 헤더가 붙어야 한다")
                .isNotNull();

        // 길이가 실제 바디 바이트 수와 일치해야 진짜 버퍼링된 것이다.
        String body = lastBody.get();
        assertThat(Integer.parseInt(h.getFirst(HttpHeaders.CONTENT_LENGTH)))
                .isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);

        // 응답 매핑까지 정상이어야 전송 경로 전체가 산 것이다.
        assertThat(vo.getRole()).isEqualTo("assistant");
        assertThat(vo.getContent()).isEqualTo("연동입니다.");
    }

    @Test
    @DisplayName("🔴 전송 바디는 messages 하나뿐 — model·stream 키가 소켓에 실리지 않는다(§1 ⑥)")
    void chatBodyCarriesOnlyMessages() throws IOException {
        productionClient().sendMessage(TOKEN, SESSION_ID, QUESTION);

        JsonNode root = new ObjectMapper().readTree(lastBody.get());
        assertThat(root.fieldNames()).toIterable().containsExactly("messages");
        assertThat(root.has("model")).as("model 을 보내지 않는다").isFalse();
        assertThat(root.has("stream")).as("stream 을 보내지 않는다").isFalse();

        JsonNode msg = root.path("messages").path(0);
        assertThat(msg.path("role").asText()).isEqualTo("user");
        assertThat(msg.path("content").asText()).isEqualTo(QUESTION);

        // 헤더 3종도 소켓 수준에서 확인한다.
        Headers h = lastHeaders.get();
        assertThat(h.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + TOKEN);
        assertThat(h.getFirst("X-Agent-Id")).isEqualTo(AGENT_ID);
        assertThat(h.getFirst("X-Session-Id")).isEqualTo(SESSION_ID);
        assertThat(h.getFirst(HttpHeaders.CONTENT_TYPE)).startsWith("application/json");
    }

    // ------------------------------------------------------------------ 조회(GET) 회귀

    @Test
    @DisplayName("조회 경로 회귀 — 팩토리를 바꿔도 GET /v1/sessions 는 그대로 동작한다")
    void listSessionsStillWorksOverRealSocket() {
        AiSessionListVO vo = productionClient().listSessions(TOKEN, 20, null);

        assertThat(vo.getSessions()).hasSize(1);
        assertThat(vo.getSessions().get(0).getSessionId()).isEqualTo("portal-1");
        assertThat(vo.getNextCursor()).isNull();

        Headers h = lastHeaders.get();
        assertThat(h.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + TOKEN);
        // GET 은 바디가 없으므로 애초에 chunked 가 될 일이 없다 — 그래도 회귀로 못박는다.
        assertThat(h.getFirst("Transfer-Encoding")).isNull();
        assertThat(lastBody.get()).isEmpty();
    }
}
