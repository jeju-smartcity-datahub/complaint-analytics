package jeju.datahub.batch.cvlcpt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DhubAuthClient} 의 토큰 수명 관리 테스트 — <b>선제갱신 · 401 재시도 1회 · exp 폴백</b>.
 *
 * <p>실제 DHubManager 를 부르지 않는다. 토큰 만료를 실시간으로 기다릴 수 없으므로
 * JDK 내장 {@link HttpServer} 로 로그인 응답을 만들고 {@link MutableClock} 으로 시간을 앞당긴다
 * ({@code VworldGeocodingClientTest} 와 같은 방식). 실호출 확인은
 * {@link DhubClientLiveTest} 가 담당한다.
 *
 * <p>실측 기준: 발급 토큰의 수명은 <b>900초</b>다({@code exp - iat}, 2026-08-04).
 */
class DhubAuthClientTest {

    /** 실측 토큰 수명. 이 값을 기준으로 선제갱신 경계를 검증한다. */
    private static final Duration TOKEN_TTL = Duration.ofSeconds(900);

    private static final Instant T0 = Instant.parse("2026-08-04T02:00:00Z");

    private HttpServer server;
    private DhubProperties properties;
    private MutableClock clock;

    /** 로그인 요청 때마다 응답으로 쓸 access_token 을 만든다 — 테스트가 갈아 끼운다. */
    private final AtomicReference<Supplier<String>> tokenFactory = new AtomicReference<>();

    /** 서버가 받은 로그인 요청 수(클라이언트 카운터와 교차 확인). */
    private final AtomicInteger serverLoginHits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        clock = new MutableClock(T0);
        tokenFactory.set(() -> jwtExpiringAt(clock.instant().plus(TOKEN_TTL)));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(DhubAuthClient.LOGIN_PATH, exchange -> {
            serverLoginHits.incrementAndGet();
            respond(exchange, 200, """
                    {"access_token":"%s","token_type":"bearer"}""".formatted(tokenFactory.get().get()));
        });
        server.start();

        properties = new DhubProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setEmail("tester@example.com");
        properties.setPassword("TEST-ONLY-NOT-A-REAL-SECRET");
        properties.setTableId("dataset.test");
        properties.setSourceTable("`db`.`tbl`");
        properties.setTimeoutMs(3_000);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("로그인하면 access_token 을 받고 JWT payload 의 exp 로 만료시각을 읽는다")
    void loginReadsExpiryFromJwt() {
        DhubAuthClient client = newClient();

        String token = client.currentToken();

        assertThat(token).isNotBlank();
        assertThat(client.loginCount()).isEqualTo(1);
        assertThat(serverLoginHits).hasValue(1);
        // exp 를 읽었으면 선제갱신이 살아 있어야 한다.
        assertThat(client.isProactiveRefreshEnabled()).isTrue();
        assertThat(client.expiresAt()).isEqualTo(T0.plus(TOKEN_TTL));
    }

    @Test
    @DisplayName("만료까지 60초를 넘게 남기면 재로그인하지 않는다 — 캐시된 토큰을 그대로 쓴다")
    void reusesTokenWhileFarFromExpiry() {
        DhubAuthClient client = newClient();
        String first = client.currentToken();

        // 만료 61초 전 — 아직 경계 밖이다.
        clock.advance(TOKEN_TTL.minusSeconds(DhubAuthClient.REFRESH_MARGIN_SECONDS + 1));

        assertThat(client.currentToken()).isEqualTo(first);
        assertThat(client.loginCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 60초 전에 들어서면 요청을 보내기 전에 선제 재로그인한다")
    void refreshesProactivelyBeforeExpiry() {
        DhubAuthClient client = newClient();
        String first = client.currentToken();

        // 만료 59초 전 — 경계를 넘었다. 401 을 맞기 전에 미리 갱신돼야 한다.
        clock.advance(TOKEN_TTL.minusSeconds(DhubAuthClient.REFRESH_MARGIN_SECONDS - 1));
        String second = client.currentToken();

        assertThat(client.loginCount()).isEqualTo(2);
        assertThat(second).isNotEqualTo(first);
        // 새 토큰의 만료도 새로 읽혀야 한다(갱신된 시계 기준).
        assertThat(client.expiresAt()).isEqualTo(clock.instant().plus(TOKEN_TTL));
    }

    @Test
    @DisplayName("발급 직후부터 만료 임박인 토큰이면 선제갱신을 멈춘다 — 호출마다 로그인하는 폭주 방지")
    void suppressesProactiveRefreshWhenFreshTokenIsAlreadyNearExpiry() {
        // 서버가 갱신 여유(60초)보다 짧은 30초짜리 토큰을 준다(시계 어긋남·수명 단축 상황).
        tokenFactory.set(() -> jwtExpiringAt(clock.instant().plusSeconds(30)));
        DhubAuthClient client = newClient();

        client.currentToken();
        assertThat(client.loginCount()).isEqualTo(1);
        assertThat(client.isProactiveRefreshSuppressed()).isTrue();
        // exp 자체는 읽혔다 — 못 읽은 것(폴백)과는 다른 상태다.
        assertThat(client.isProactiveRefreshEnabled()).isTrue();

        // 다시 불러도 로그인하지 않는다. 재로그인해도 같은 상황이라 반복해 봐야 소용이 없다.
        client.currentToken();
        client.currentToken();
        assertThat(client.loginCount()).isEqualTo(1);

        // 대신 401 안전망은 그대로 살아 있다.
        AtomicInteger calls = new AtomicInteger();
        String result = client.withToken(token -> {
            if (calls.incrementAndGet() == 1) {
                throw new DhubUnauthorizedException("401");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(client.loginCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("정상 수명 토큰을 다시 받으면 선제갱신이 자동으로 풀린다")
    void unsuppressesWhenNormalLifetimeReturns() {
        tokenFactory.set(() -> jwtExpiringAt(clock.instant().plusSeconds(30)));
        DhubAuthClient client = newClient();
        client.currentToken();
        assertThat(client.isProactiveRefreshSuppressed()).isTrue();

        tokenFactory.set(() -> jwtExpiringAt(clock.instant().plus(TOKEN_TTL)));
        client.forceLogin();

        assertThat(client.isProactiveRefreshSuppressed()).isFalse();
        assertThat(client.expiresAt()).isEqualTo(clock.instant().plus(TOKEN_TTL));
    }

    @Test
    @DisplayName("exp 가 없는 토큰이면 선제갱신을 끄고 401 반응형으로 폴백한다")
    void fallsBackToReactiveWhenExpMissing() {
        tokenFactory.set(() -> jwtWithPayload("{\"sub\":\"tester\"}")); // exp 없음
        DhubAuthClient client = newClient();

        client.currentToken();
        assertThat(client.isProactiveRefreshEnabled()).isFalse();
        assertThat(client.expiresAt()).isNull();

        // 아무리 시간이 흘러도 스스로 갱신하지 않는다(만료 시점을 모르므로).
        clock.advance(Duration.ofDays(1));
        client.currentToken();
        assertThat(client.loginCount()).isEqualTo(1);

        // 대신 401 을 맞으면 그때 갱신한다 — 폴백 경로가 실제로 동작해야 한다.
        AtomicInteger calls = new AtomicInteger();
        String result = client.withToken(token -> {
            if (calls.incrementAndGet() == 1) {
                throw new DhubUnauthorizedException("401");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(client.loginCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("JWT 형식이 아닌 토큰(마침표 없음)도 선제갱신만 끄고 배치를 죽이지 않는다")
    void toleratesNonJwtToken() {
        tokenFactory.set(() -> "not-a-jwt-at-all");
        DhubAuthClient client = newClient();

        assertThat(client.currentToken()).isEqualTo("not-a-jwt-at-all");
        assertThat(client.isProactiveRefreshEnabled()).isFalse();
    }

    @Test
    @DisplayName("payload 가 base64 로 깨져 있어도 예외 없이 선제갱신만 끈다")
    void toleratesUndecodablePayload() {
        tokenFactory.set(() -> "header.@@not-base64@@.signature");
        DhubAuthClient client = newClient();

        assertThat(client.currentToken()).isNotBlank();
        assertThat(client.isProactiveRefreshEnabled()).isFalse();
    }

    @Test
    @DisplayName("401 을 받으면 재로그인 후 정확히 1회만 재시도한다")
    void retriesOnceAfterUnauthorized() {
        DhubAuthClient client = newClient();
        List<String> tokensSeen = new CopyOnWriteArrayList<>();

        String result = client.withToken(token -> {
            tokensSeen.add(token);
            if (tokensSeen.size() == 1) {
                throw new DhubUnauthorizedException("401 — 토큰 만료");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(tokensSeen).hasSize(2);
        // 재시도는 반드시 "새로 로그인한" 토큰으로 나가야 한다 — 같은 토큰으로 다시 부르면 무의미하다.
        assertThat(tokensSeen.get(1)).isNotEqualTo(tokensSeen.get(0));
        assertThat(client.loginCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시도 후에도 401 이면 그대로 전파한다 — 재로그인 루프를 돌지 않는다")
    void doesNotLoopWhenUnauthorizedPersists() {
        DhubAuthClient client = newClient();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> client.withToken(token -> {
            calls.incrementAndGet();
            throw new DhubUnauthorizedException("401 — 권한 없음");
        })).isInstanceOf(DhubUnauthorizedException.class);

        // 최초 1회 + 재시도 1회 = 2회에서 멈춘다. 로그인도 2회를 넘지 않는다.
        assertThat(calls).hasValue(2);
        assertThat(client.loginCount()).isEqualTo(2);
        assertThat(serverLoginHits).hasValue(2);
    }

    @Test
    @DisplayName("401 이 아닌 실패는 재시도하지 않는다 — 재로그인해도 결과가 같다")
    void doesNotRetryNonAuthFailures() {
        DhubAuthClient client = newClient();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> client.withToken(token -> {
            calls.incrementAndGet();
            throw new DhubApiException("DHub 조회 실패(HTTP 500).");
        })).isInstanceOf(DhubApiException.class);

        assertThat(calls).hasValue(1);
        assertThat(client.loginCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("자격증명이 없으면 HTTP 를 부르기도 전에 환경변수 이름을 알려주며 실패한다")
    void failsFastWithoutCredentials() {
        properties.setPassword("");
        DhubAuthClient client = newClient();

        assertThatThrownBy(client::currentToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DHUB_USER_EMAIL")
                .hasMessageContaining("DHUB_USER_PW");

        // 값이 없다는 사실만 알리고, 조용히 진행하지도 서버를 부르지도 않는다.
        assertThat(serverLoginHits).hasValue(0);
        assertThat(client.loginCount()).isZero();
    }

    @Test
    @DisplayName("로그인 실패 응답의 본문이 예외 메시지에 실리지 않는다")
    void doesNotLeakResponseBodyOnLoginFailure() {
        server.removeContext(DhubAuthClient.LOGIN_PATH);
        String secretish = "leaked-token-value-should-not-appear";
        server.createContext(DhubAuthClient.LOGIN_PATH,
                exchange -> respond(exchange, 401, "{\"detail\":\"" + secretish + "\"}"));

        DhubAuthClient client = newClient();

        assertThatThrownBy(client::currentToken)
                .isInstanceOf(DhubApiException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(secretish));
    }

    @Test
    @DisplayName("응답에 access_token 이 없으면 빈 토큰으로 진행하지 않고 실패한다")
    void failsWhenAccessTokenMissing() {
        server.removeContext(DhubAuthClient.LOGIN_PATH);
        server.createContext(DhubAuthClient.LOGIN_PATH,
                exchange -> respond(exchange, 200, "{\"token_type\":\"bearer\"}"));

        assertThatThrownBy(() -> newClient().currentToken())
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("access_token");
    }

    @Test
    @DisplayName("DhubProperties.toString() 에 이메일·비밀번호가 들어가지 않는다")
    void propertiesToStringHidesCredentials() {
        assertThat(properties.toString())
                .doesNotContain("tester@example.com")
                .doesNotContain("TEST-ONLY-NOT-A-REAL-SECRET");
    }

    // --- 연속 로그인 실패 상한 (시큐어코딩 2.16 — TSK-4336) -------------------------------

    /** 로그인 응답을 5xx 로 바꾼다. */
    private void failLoginWith500() {
        server.removeContext(DhubAuthClient.LOGIN_PATH);
        server.createContext(DhubAuthClient.LOGIN_PATH, exchange -> {
            serverLoginHits.incrementAndGet();
            respond(exchange, 500, "{\"detail\":\"boom\"}");
        });
    }

    /** 로그인 응답을 정상(200 + 토큰)으로 되돌린다. */
    private void restoreLogin() {
        server.removeContext(DhubAuthClient.LOGIN_PATH);
        server.createContext(DhubAuthClient.LOGIN_PATH, exchange -> {
            serverLoginHits.incrementAndGet();
            respond(exchange, 200, """
                    {"access_token":"%s","token_type":"bearer"}""".formatted(tokenFactory.get().get()));
        });
    }

    @Test
    @DisplayName("로그인이 연속 3회 실패하면 쿨다운 동안 HTTP 요청 없이 즉시 실패한다")
    void blocksLoginAfterConsecutiveFailures() {
        failLoginWith500();
        DhubAuthClient client = newClient();

        for (int i = 0; i < DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES; i++) {
            assertThatThrownBy(client::currentToken).isInstanceOf(DhubApiException.class);
        }
        assertThat(serverLoginHits).hasValue(DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES);

        assertThatThrownBy(client::currentToken)
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("연속 실패");
        // 차단된 호출은 요청을 보내지 않고 loginCount 도 올리지 않는다.
        assertThat(serverLoginHits).hasValue(DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES);
        assertThat(client.loginCount()).isEqualTo(DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES);
    }

    @Test
    @DisplayName("쿨다운이 지나면 다시 시도하고, 그 1회가 또 실패하면 곧바로 다시 막힌다")
    void retriesAfterCooldownAndReblocksOnSingleFailure() {
        failLoginWith500();
        DhubAuthClient client = newClient();
        for (int i = 0; i < DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES; i++) {
            assertThatThrownBy(client::currentToken).isInstanceOf(DhubApiException.class);
        }

        clock.advance(DhubAuthClient.LOGIN_COOLDOWN.plusSeconds(1));
        assertThatThrownBy(client::currentToken).isInstanceOf(DhubApiException.class);
        assertThat(serverLoginHits).hasValue(DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES + 1);

        // 쿨다운 직후 1회 실패로 다시 차단됐다 — 요청이 더 나가지 않는다.
        assertThatThrownBy(client::currentToken).hasMessageContaining("연속 실패");
        assertThat(serverLoginHits).hasValue(DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES + 1);
    }

    @Test
    @DisplayName("로그인이 성공하면 연속 실패 수가 초기화된다")
    void successResetsFailureCount() {
        failLoginWith500();
        DhubAuthClient client = newClient();
        for (int i = 0; i < DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES - 1; i++) {
            assertThatThrownBy(client::currentToken).isInstanceOf(DhubApiException.class);
        }

        restoreLogin();
        assertThat(client.forceLogin()).isNotBlank();

        // 초기화됐으므로 다시 (상한 - 1)회 실패해도 막히지 않는다.
        failLoginWith500();
        for (int i = 0; i < DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES - 1; i++) {
            assertThatThrownBy(client::forceLogin).isInstanceOf(DhubApiException.class)
                    .hasMessageNotContaining("연속 실패");
        }
    }

    @Test
    @DisplayName("빈 응답 본문(body == null)도 로그인 실패로 센다")
    void emptyBodyCountsAsFailure() {
        server.removeContext(DhubAuthClient.LOGIN_PATH);
        server.createContext(DhubAuthClient.LOGIN_PATH, exchange -> {
            serverLoginHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        DhubAuthClient client = newClient();

        for (int i = 0; i < DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES; i++) {
            assertThatThrownBy(client::currentToken).isInstanceOf(DhubApiException.class);
        }
        assertThatThrownBy(client::currentToken).hasMessageContaining("연속 실패");
        assertThat(serverLoginHits).hasValue(DhubAuthClient.MAX_CONSECUTIVE_LOGIN_FAILURES);
    }

    private DhubAuthClient newClient() {
        return new DhubAuthClient(properties, clock);
    }

    // --- JWT 만들기 (서명은 검증되지 않으므로 아무 값이나 둔다) ---------------------------

    static String jwtExpiringAt(Instant exp) {
        return jwtWithPayload("{\"sub\":\"tester\",\"iat\":%d,\"exp\":%d}"
                .formatted(exp.minus(TOKEN_TTL).getEpochSecond(), exp.getEpochSecond()));
    }

    static String jwtWithPayload(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(("sig" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
    }

    static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** 시간을 앞당길 수 있는 시계 — 토큰 만료(900초)를 실시간으로 기다릴 수 없다. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(Instant start) {
            this(new AtomicReference<>(start), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration amount) {
            now.updateAndGet(instant -> instant.plus(amount));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now, zone); // 상태(now)를 공유해야 앞당긴 시간이 그대로 보인다
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
