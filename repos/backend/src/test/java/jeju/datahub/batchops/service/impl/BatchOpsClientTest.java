package jeju.datahub.batchops.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jeju.datahub.batchops.service.BatchOpsProperties;
import jeju.datahub.batchops.service.BatchOpsProxyException;

/**
 * {@link BatchOpsClient} 단위 테스트 — 연결 실패·미설정·카탈로그 캐시(성공·실패)·카탈로그 전용 타임아웃.
 *
 * <p>🔴 운영 생성자로 만든다 — 요청 팩토리 조립(타임아웃·버퍼링)이 검증 대상에 들어간다. 서버는 JDK {@link HttpServer}.
 */
class BatchOpsClientTest {

    private static final ObjectMapper OM = Jackson2ObjectMapperBuilder.json().build();
    private static final String CATALOG = "[{\"jobNm\":\"aJob\",\"steps\":[{\"stepNm\":\"s1\",\"stepKorNm\":\"하나\"}]}]";

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger catalogHits = new AtomicInteger();
    private final AtomicBoolean catalogOk = new AtomicBoolean(true);
    /** 응답 지연(ms) — 배치 멈춤(hang) 흉내. 카탈로그·명령 각각 */
    private final AtomicLong catalogDelayMs = new AtomicLong();
    private final AtomicLong commandDelayMs = new AtomicLong();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/batch/ops/jobs", ex -> {
            if (ex.getRequestURI().getPath().endsWith("/executions")) {
                sleep(commandDelayMs.get());
                send(ex, 202, "{\"jobExecutionId\":1}");
                return;
            }
            catalogHits.incrementAndGet();
            sleep(catalogDelayMs.get());
            boolean ok = catalogOk.get();
            send(ex, ok ? 200 : 500, ok ? CATALOG : "{\"code\":\"BOOM\"}");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊었다
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 운영 기본값(연결 3s · 명령 읽기 10s · 카탈로그 읽기 2s · 캐시 5s/실패 5s) 그대로 */
    private static BatchOpsProperties props(String baseUrl) {
        BatchOpsProperties p = new BatchOpsProperties();
        p.setBaseUrl(baseUrl);
        return p;
    }

    private static BatchOpsClient client(BatchOpsProperties p) {
        return new BatchOpsClient(RestClient.builder(), p, OM);
    }

    @Test
    @DisplayName("1. 연결 거부(닫힌 포트) — 명령은 503 BATCH_UNAVAILABLE · 카탈로그는 empty (예외 없음)")
    void connectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        BatchOpsClient c = client(props("http://127.0.0.1:" + closedPort));
        assertThatThrownBy(() -> c.execute("aJob", Map.of(), "u1"))
                .isInstanceOfSatisfying(BatchOpsProxyException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BatchOpsProxyException.UNAVAILABLE));
        assertThatThrownBy(() -> c.log(1L, 200, null))
                .isInstanceOfSatisfying(BatchOpsProxyException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(503));
        assertThat(c.catalog()).isEmpty();
    }

    @Test
    @DisplayName("2. 미설정 — 명령 4종·로그 503 BATCH_OPS_NOT_CONFIGURED · 카탈로그 empty")
    void notConfigured() {
        BatchOpsClient c = client(props(""));
        for (Runnable r : new Runnable[] {
                () -> c.execute("aJob", null, "u1"),
                () -> c.updateSchedule("aJob", "0 0 1 * * *", "Y", "u1"),
                () -> c.stop(1L, "u1"),
                () -> c.abandon(1L, "u1"),
                () -> c.log(1L, null, 0L) }) {
            assertThatThrownBy(r::run).isInstanceOfSatisfying(BatchOpsProxyException.class,
                    e -> assertThat(e.getCode()).isEqualTo(BatchOpsProxyException.NOT_CONFIGURED));
        }
        assertThat(c.catalog()).isEmpty();
    }

    @Test
    @DisplayName("3. 성공 캐시 — TTL 안에서는 1회만 부른다 · 명령 성공이 캐시를 비운다")
    void catalogCache() {
        BatchOpsClient c = client(props(baseUrl + "/"));   // 끝 '/' 는 떼어진다
        assertThat(c.catalog()).get().satisfies(l -> assertThat(l.get(0).getSteps().get(0).getStepKorNm()).isEqualTo("하나"));
        assertThat(c.catalog()).isPresent();
        assertThat(catalogHits.get()).isEqualTo(1);

        assertThat(c.execute("x", Map.of(), "u1").status()).isEqualTo(202);
        assertThat(c.catalog()).isPresent();
        assertThat(catalogHits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("4. 실패 캐시 — 실패 직후 재호출은 배치를 부르지 않는다 · 명령 성공이 실패 캐시도 비운다")
    void failureCache() {
        catalogOk.set(false);
        BatchOpsClient c = client(props(baseUrl));
        assertThat(c.catalog()).isEmpty();
        assertThat(c.catalog()).isEmpty();
        assertThat(catalogHits.get()).as("실패 캐시 동안은 배치를 부르지 않는다").isEqualTo(1);

        catalogOk.set(true);
        assertThat(c.catalog()).as("실패 캐시 TTL(5s) 안이라 아직 empty").isEmpty();
        assertThat(c.execute("x", Map.of(), "u1").status()).isEqualTo(202);
        assertThat(c.catalog()).as("명령 성공이 실패 캐시를 비웠다").isPresent();
        assertThat(catalogHits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("5. 배치 멈춤(3s 지연) — 카탈로그는 2s 남짓에 empty, 직후 재호출은 즉시 empty · 명령은 10s 기준이라 3s 응답을 받는다")
    void hangUsesCatalogTimeout() {
        catalogDelayMs.set(3000);
        BatchOpsClient c = client(props(baseUrl));

        long t0 = System.nanoTime();
        assertThat(c.catalog()).isEmpty();
        long firstMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(firstMs).as("카탈로그 읽기 타임아웃 2s").isBetween(1800L, 2900L);

        long t1 = System.nanoTime();
        assertThat(c.catalog()).isEmpty();
        assertThat((System.nanoTime() - t1) / 1_000_000).as("실패 캐시 — 배치를 부르지 않는다").isLessThan(200L);
        assertThat(catalogHits.get()).isEqualTo(1);

        commandDelayMs.set(3000);
        long t2 = System.nanoTime();
        assertThat(c.execute("x", Map.of(), "u1").status()).as("명령은 10s 기준 — 3s 지연도 받는다").isEqualTo(202);
        assertThat((System.nanoTime() - t2) / 1_000_000).isGreaterThanOrEqualTo(2900L);
    }
}
