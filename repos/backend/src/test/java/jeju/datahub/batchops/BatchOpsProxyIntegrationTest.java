package jeju.datahub.batchops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 배치 운영 명령 프록시 통합테스트 (TSK-4337 Step 7) — 배치 앱을 <b>실제 소켓 스텁</b>(JDK {@link HttpServer})으로 흉내 낸다.
 *
 * <p>🔴 {@code MockRestServiceServer} 를 쓰지 않는다 — 전송 계층을 타지 않아 chunked 전송·charset 디코딩 함정을
 * 원리적으로 못 잡는다({@code AgentChatTransportTest} 선례). 스텁은 요청 헤더·본문을 그대로 기록해
 * {@code Content-Length}·{@code X-API-Key}·{@code Accept}·실행자 치환을 단언한다.
 *
 * <p>🔴 운영 필터체인을 켠다 — 미인증 401·CSRF 를 실제 체인으로 본다. 카탈로그 캐시는 끈다({@code ttl=0} —
 * 테스트마다 카탈로그 응답을 바꾼다 · 실패 캐시도 0). 캐시·타임아웃 동작은 {@code BatchOpsClientTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.auth.security-enabled=true",
                "app.batch-ops.api-key=" + BatchOpsProxyIntegrationTest.API_KEY,
                "app.batch-ops.read-timeout-ms=1000",
                "app.batch-ops.catalog-cache-ttl-ms=0",
                "app.batch-ops.catalog-failure-cache-ttl-ms=0" })
class BatchOpsProxyIntegrationTest {

    static final String API_KEY = "test-ops-key";
    private static final String BASE = "/api/ops/batch";
    private static final String OPS = "/api/batch/ops";

    private static final String RAN_JOB = "tsk4337ProxyRanJob";
    private static final String NEVER_JOB = "tsk4337ProxyNeverJob";
    private static final String RETIRED_JOB = "tsk4337ProxyRetiredJob";
    private static final long RAN_INST = -4337201L;
    private static final long RETIRED_INST = -4337202L;
    private static final long E_RUNNING = -4337210L;
    private static final long E_RETIRED = -4337211L;

    private static final String KOREAN_LOG = "2026-11-11 02:00:01 INFO [complaintFetchJob] 민원 수집 시작 — 1,200건 처리\n";

    private static final String CATALOG = """
            [
              {"jobNm":"%1$s","jobKorNm":"테스트 수집","description":"설명","countLabel":"적재 행",
               "params":[{"name":"baseYmd","type":"DATE","required":false,"defaultDesc":"오늘","options":[],"description":"기준일"}],
               "schedulable":true,
               "schedule":{"cronExpr":"0 0 9 * * *","useYn":"Y","mdfrId":null,"mdfcnDt":null,"registered":false,
                           "blockedByEnv":true,"envKey":"TEST_SYNC_SCHEDULED","nextRuns":[]},
               "chainNext":null,"chainCondition":null,
               "steps":[{"stepNm":"fetchStep","stepKorNm":"수집"},{"stepNm":"geocodeStep","stepKorNm":"지오코딩"},
                        {"stepNm":"writeStep","stepKorNm":"적재"}],
               "runningJobExecutionIds":[%3$d]},
              {"jobNm":"%2$s","jobKorNm":"한 번도 안 돈 잡","description":null,"countLabel":"생성 보고서",
               "params":[],"schedulable":true,
               "schedule":{"cronExpr":"0 30 2 * * *","useYn":"Y","mdfrId":"admin1","mdfcnDt":"2026-11-10T09:00:00","registered":true,
                           "blockedByEnv":false,"envKey":"TEST2_SYNC_SCHEDULED","nextRuns":["2026-11-12T02:30:00+09:00"]},
               "chainNext":null,"chainCondition":null,"steps":[{"stepNm":"oneStep","stepKorNm":"하나"}],
               "runningJobExecutionIds":[]}
            ]""".formatted(RAN_JOB, NEVER_JOB, E_RUNNING);

    // ---------------------------------------------------------------- 스텁 배치

    /** 스텁이 받은 요청 한 건 — 소켓에 실제로 실린 헤더·본문 */
    record Received(String method, String path, String query, Headers headers, String body) {
    }

    private static final Map<String, Received> RECEIVED = new ConcurrentHashMap<>();
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicBoolean CATALOG_OK = new AtomicBoolean(true);
    private static final AtomicBoolean CATALOG_HANG = new AtomicBoolean(false);
    private static final HttpServer SERVER = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.createContext(OPS, BatchOpsProxyIntegrationTest::handle);
            s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void batchOpsUrl(DynamicPropertyRegistry r) {
        // 🔴 끝 '/' 를 일부러 붙인다 — 클라이언트가 떼야 한다(//api/... 가 되면 스텁이 404 를 낸다)
        r.add("app.batch-ops.base-url", () -> "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + SERVER.getAddress().getPort() + "/");
    }

    @AfterAll
    static void stopStub() {
        SERVER.stop(0);
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String method = ex.getRequestMethod();
        if (!path.equals(OPS + "/jobs")) {
            HITS.incrementAndGet();
            RECEIVED.put(method + " " + path,
                    new Received(method, path, ex.getRequestURI().getRawQuery(), ex.getRequestHeaders(), body));
        }

        if (!API_KEY.equals(ex.getRequestHeaders().getFirst("X-API-Key"))) {
            send(ex, 401, "application/json", "{\"code\":\"UNAUTHORIZED\"}");
            return;
        }
        if (path.equals(OPS + "/jobs")) {
            if (CATALOG_HANG.get()) {
                sleep(3000);   // 배치 멈춤 — 카탈로그 읽기 타임아웃(2s)이 먼저 끊는다
            }
            if (CATALOG_OK.get()) {
                send(ex, 200, "application/json", CATALOG);
            } else {
                send(ex, 500, "application/json", "{\"code\":\"BOOM\"}");
            }
            return;
        }
        String job = path.startsWith(OPS + "/jobs/") ? path.substring((OPS + "/jobs/").length()).replace("/executions", "") : "";
        switch (job) {
            case "okJob" -> send(ex, 202, "application/json",
                    "{\"jobExecutionId\":123,\"jobNm\":\"okJob\",\"status\":\"STARTING\"}");
            case "busyJob" -> send(ex, 409, "application/json",
                    "{\"code\":\"JOB_ALREADY_RUNNING\",\"message\":\"이미 실행 중입니다\",\"jobExecutionId\":77,\"zombie\":false}");
            case "badJob" -> send(ex, 400, "application/json", "{\"code\":\"INVALID_PARAM\",\"message\":\"baseYmd 형식\"}");
            case "missingJob" -> send(ex, 404, "application/json", "{\"code\":\"JOB_NOT_FOUND\",\"message\":\"없는 잡\"}");
            case "unauthJob" -> send(ex, 401, "application/json", "{\"code\":\"UNAUTHORIZED\"}");
            case "errorJob" -> send(ex, 500, "application/json", "{\"code\":\"JOB_BEAN_MISSING\"}");
            case "slowJob" -> {
                sleep(2500);
                send(ex, 202, "application/json", "{\"jobExecutionId\":1}");
            }
            default -> route(ex, path);
        }
    }

    private static void route(HttpExchange ex, String path) throws IOException {
        if (path.equals(OPS + "/schedules/okJob")) {
            send(ex, 200, "application/json",
                    "{\"state\":\"REGISTERED\",\"registered\":true,\"blockedByEnv\":false,\"nextRuns\":[]}");
        } else if (path.equals(OPS + "/executions/5/stop")) {
            send(ex, 202, "application/json", "{\"jobExecutionId\":5,\"status\":\"STOPPING\",\"zombie\":false}");
        } else if (path.equals(OPS + "/executions/5/abandon")) {
            send(ex, 409, "application/json", "{\"code\":\"STILL_RUNNING\",\"message\":\"아직 돌고 있습니다\"}");
        } else if (path.equals(OPS + "/executions/6/abandon")) {
            send(ex, 200, "application/json", "{\"jobExecutionId\":6,\"status\":\"ABANDONED\"}");
        } else if (path.equals(OPS + "/executions/5/log")) {
            // 🔴 charset 표기 없는 text/plain — 백엔드가 ISO-8859-1 로 읽으면 한글이 깨진다
            ex.getResponseHeaders().set("X-Next-Byte", "2048");
            ex.getResponseHeaders().set("X-Job-Running", "true");
            send(ex, 200, "text/plain", KOREAN_LOG);
        } else if (path.equals(OPS + "/executions/" + E_RUNNING + "/live")) {
            // TSK-4337 추가 — 실행 중 단계별 수치. 모르는 키(futureKey)는 백엔드가 버린다
            send(ex, 200, "application/json", "{\"jobExecutionId\":" + E_RUNNING + ",\"metrics\":{\"dayIdx\":3,\"dayCnt\":30,"
                    + "\"crntYmd\":\"2026-11-03\",\"fetchCnt\":6800,\"prcsCnt\":6750,\"geocodedCnt\":6500,"
                    + "\"geocodeCacheHits\":5900,\"geocodeApiLookups\":850,\"skipGeocodeFailed\":12,\"futureKey\":1}}");
        } else if (path.equals(OPS + "/executions/7/log")) {
            send(ex, 404, "application/json", "{\"code\":\"LOG_NOT_FOUND\",\"message\":\"로그 파일이 없습니다\"}");
        } else {
            send(ex, 404, "application/json", "{\"code\":\"STUB_NO_ROUTE\",\"message\":\"" + path + "\"}");
        }
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 생명주기

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();
    private IntegrationLoginSupport auth;

    @BeforeEach
    void setUp() {
        RECEIVED.clear();
        CATALOG_OK.set(true);
        CATALOG_HANG.set(false);
        auth = IntegrationLoginSupport.forProductionChain(rest, jdbcTemplate);
        auth.markStart();
        deleteSynthetic();
        insertSynthetic();
    }

    @AfterEach
    void tearDown() {
        deleteSynthetic();
        auth.cleanUp();
    }

    // ---------------------------------------------------------------- 인가

    @Test
    @DisplayName("1. 미인증 — 조회 GET·로그 401 · CSRF 실은 POST/PUT 도 401 · 배치는 호출되지 않는다")
    void unauthenticatedIs401() {
        HttpHeaders csrfOnly = csrfOnly();
        int before = HITS.get();
        assertThat(rest.getForEntity(BASE + "/executions/5/log", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        for (Object[] c : commands()) {
            ResponseEntity<String> res = rest.exchange((String) c[1], (HttpMethod) c[0],
                    new HttpEntity<>(c[2], json(csrfOnly)), String.class);
            assertThat(res.getStatusCode()).as(c[0] + " " + c[1]).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(HITS.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("2. 일반 사용자(M0604 없음) — 명령·로그 403 · 배치는 호출되지 않는다")
    void plainUserIs403() {
        HttpHeaders session = auth.mockSession();
        int before = HITS.get();
        assertThat(get(session, BASE + "/executions/5/log").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for (Object[] c : commands()) {
            ResponseEntity<String> res = rest.exchange((String) c[1], (HttpMethod) c[0],
                    new HttpEntity<>(c[2], json(session)), String.class);
            assertThat(res.getStatusCode()).as(c[0] + " " + c[1]).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(HITS.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("3. POST·PUT 은 CSRF 헤더가 없으면 403 (면제하지 않는다)")
    void csrfRequired() {
        HttpHeaders session = adminSession();
        HttpHeaders noCsrf = new HttpHeaders();
        noCsrf.addAll(session);
        noCsrf.remove("X-XSRF-TOKEN");
        ResponseEntity<String> res = rest.exchange(BASE + "/jobs/okJob/executions", HttpMethod.POST,
                new HttpEntity<>(Map.of(), json(noCsrf)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(RECEIVED).doesNotContainKey("POST " + OPS + "/jobs/okJob/executions");
    }

    // ---------------------------------------------------------------- 실행

    @Test
    @DisplayName("4. 실행 202 전달 — excutrId 는 로그인 user_id 로 바뀐다 · Content-Length(chunked 아님) · X-API-Key")
    void executeRelays202WithLoginUser() throws Exception {
        HttpHeaders session = adminSession();
        ResponseEntity<String> res = rest.exchange(BASE + "/jobs/okJob/executions", HttpMethod.POST,
                new HttpEntity<>(Map.of("params", Map.of("baseYmd", "2026-11-01"), "excutrId", "hacker"), json(session)),
                String.class);
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = om.readTree(res.getBody());
        assertThat(body.get("jobExecutionId").asLong()).isEqualTo(123);
        assertThat(body.get("status").asText()).isEqualTo("STARTING");

        Received r = RECEIVED.get("POST " + OPS + "/jobs/okJob/executions");
        assertThat(r).as("끝 '/' 를 뗀 base-url 로 정확한 경로에 닿아야 한다").isNotNull();
        JsonNode sent = om.readTree(r.body());
        assertThat(sent.get("excutrId").asText()).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(sent.get("params").get("baseYmd").asText()).isEqualTo("2026-11-01");
        assertContentLength(r);
        assertThat(r.headers().getFirst("X-API-Key")).isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("5. 배치 409·400·404 — 상태·본문 그대로 전달")
    void relaysBatchErrors() throws Exception {
        HttpHeaders session = adminSession();
        ResponseEntity<String> busy = post(session, BASE + "/jobs/busyJob/executions", Map.of());
        assertThat(busy.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode b = om.readTree(busy.getBody());
        assertThat(b.get("code").asText()).isEqualTo("JOB_ALREADY_RUNNING");
        assertThat(b.get("message").asText()).isEqualTo("이미 실행 중입니다");
        assertThat(b.get("jobExecutionId").asLong()).isEqualTo(77);

        ResponseEntity<String> bad = post(session, BASE + "/jobs/badJob/executions", Map.of());
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(om.readTree(bad.getBody()).get("code").asText()).isEqualTo("INVALID_PARAM");

        ResponseEntity<String> missing = post(session, BASE + "/jobs/missingJob/executions", Map.of());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(om.readTree(missing.getBody()).get("code").asText()).isEqualTo("JOB_NOT_FOUND");

        ResponseEntity<String> stillRunning = post(session, BASE + "/executions/5/abandon", null);
        assertThat(stillRunning.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(om.readTree(stillRunning.getBody()).get("code").asText()).isEqualTo("STILL_RUNNING");
    }

    @Test
    @DisplayName("6. 배치 401·5xx·읽기 타임아웃 → 503 BATCH_UNAVAILABLE (401 을 넘기지 않는다)")
    void unavailableIs503() throws Exception {
        HttpHeaders session = adminSession();
        for (String job : List.of("unauthJob", "errorJob", "slowJob")) {
            ResponseEntity<String> res = post(session, BASE + "/jobs/" + job + "/executions", Map.of());
            assertThat(res.getStatusCode()).as(job).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(om.readTree(res.getBody()).get("code").asText()).as(job).isEqualTo("BATCH_UNAVAILABLE");
        }
    }

    @Test
    @DisplayName("7. 잡 이름 형식 위반 — 배치를 부르지 않고 400 INVALID_PATH")
    void invalidJobNm() throws Exception {
        HttpHeaders session = adminSession();
        int before = HITS.get();
        ResponseEntity<String> res = post(session, BASE + "/jobs/bad-name/executions", Map.of());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(om.readTree(res.getBody()).get("code").asText()).isEqualTo("INVALID_PATH");
        assertThat(HITS.get()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- 스케줄·중지·실패 처리

    @Test
    @DisplayName("8. 스케줄 PUT 200 — mdfrId 는 로그인 user_id · 중지 202 · 실패 처리 200 — 요청자 = 로그인 user_id")
    void scheduleStopAbandon() throws Exception {
        HttpHeaders session = adminSession();
        ResponseEntity<String> put = rest.exchange(BASE + "/schedules/okJob", HttpMethod.PUT,
                new HttpEntity<>(Map.of("cronExpr", "0 30 2 * * *", "useYn", "Y", "mdfrId", "hacker"), json(session)),
                String.class);
        assertThat(put.getStatusCode()).as(put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(om.readTree(put.getBody()).get("state").asText()).isEqualTo("REGISTERED");
        Received rp = RECEIVED.get("PUT " + OPS + "/schedules/okJob");
        JsonNode sentPut = om.readTree(rp.body());
        assertThat(sentPut.get("mdfrId").asText()).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(sentPut.get("cronExpr").asText()).isEqualTo("0 30 2 * * *");
        assertThat(sentPut.get("useYn").asText()).isEqualTo("Y");
        assertContentLength(rp);

        ResponseEntity<String> stop = post(session, BASE + "/executions/5/stop", Map.of("excutrId", "hacker"));
        assertThat(stop.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(om.readTree(stop.getBody()).get("status").asText()).isEqualTo("STOPPING");
        Received rs = RECEIVED.get("POST " + OPS + "/executions/5/stop");
        assertThat(om.readTree(rs.body()).get("excutrId").asText()).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertContentLength(rs);

        ResponseEntity<String> abandon = post(session, BASE + "/executions/6/abandon", null);
        assertThat(abandon.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(om.readTree(RECEIVED.get("POST " + OPS + "/executions/6/abandon").body()).get("excutrId").asText())
                .isEqualTo(IntegrationLoginSupport.MOCK_ID);
    }

    // ---------------------------------------------------------------- 로그

    @Test
    @DisplayName("9. 로그 — 헤더를 JSON {content, nextByte, jobRunning} 으로 감싼다 · 한글 UTF-8 · Accept 2종 · 404 전달")
    void logWrapped() throws Exception {
        HttpHeaders session = adminSession();
        ResponseEntity<String> res = get(session, BASE + "/executions/5/log?fromByte=1024");
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = om.readTree(res.getBody());
        assertThat(body.get("content").asText()).isEqualTo(KOREAN_LOG);
        assertThat(body.get("nextByte").asLong()).isEqualTo(2048);
        assertThat(body.get("jobRunning").asBoolean()).isTrue();

        Received r = RECEIVED.get("GET " + OPS + "/executions/5/log");
        assertThat(r.query()).isEqualTo("fromByte=1024");
        assertThat(r.headers().getFirst("Accept")).contains("text/plain").contains("application/json");

        ResponseEntity<String> missing = get(session, BASE + "/executions/7/log?tailKb=200");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(om.readTree(missing.getBody()).get("code").asText()).isEqualTo("LOG_NOT_FOUND");
        assertThat(RECEIVED.get("GET " + OPS + "/executions/7/log").query()).isEqualTo("tailKb=200");
    }

    // ---------------------------------------------------------------- 카탈로그 병합

    @Test
    @DisplayName("10. /jobs — 카탈로그 기준(안 돈 잡 포함 · 카탈로그 밖 DB 잡 제외) · catalogAvailable=true · 스케줄은 DB 정본 + 프로세스 칸")
    void catalogMerge() throws Exception {
        JsonNode body = json(get(adminSession(), BASE + "/jobs"));
        assertThat(body.get("catalogAvailable").asBoolean()).isTrue();
        List<String> names = new ArrayList<>();
        body.get("jobs").forEach(j -> names.add(j.get("jobNm").asText()));
        assertThat(names).as("카탈로그 순서 그대로 — DB 에만 있는 잡(폐지 잡·실 잡)은 빠진다").containsExactly(RAN_JOB, NEVER_JOB);

        JsonNode ran = body.get("jobs").get(0);
        assertThat(ran.get("jobKorNm").asText()).isEqualTo("테스트 수집");
        assertThat(ran.get("countLabel").asText()).isEqualTo("적재 행");
        assertThat(ran.get("lastExcn").get("jobExecutionId").asLong()).isEqualTo(E_RUNNING);
        assertThat(ran.get("params").get(0).get("name").asText()).isEqualTo("baseYmd");
        assertThat(ran.get("params").get(0).get("type").asText()).isEqualTo("DATE");
        assertThat(ran.get("steps")).hasSize(3);
        assertThat(ran.get("steps").get(1).get("stepKorNm").asText()).isEqualTo("지오코딩");
        assertThat(ran.get("runningJobExecutionIds").get(0).asLong()).isEqualTo(E_RUNNING);
        JsonNode sch = ran.get("schedule");
        assertThat(sch.get("cronExpr").asText()).as("cron 은 DB 행이 정본").isEqualTo("0 0 1 * * *");
        assertThat(sch.get("useYn").asText()).isEqualTo("N");
        assertThat(sch.get("registered").asBoolean()).isFalse();
        assertThat(sch.get("blockedByEnv").asBoolean()).isTrue();
        assertThat(sch.get("envKey").asText()).isEqualTo("TEST_SYNC_SCHEDULED");

        JsonNode never = body.get("jobs").get(1);
        assertThat(never.get("lastExcn").isNull()).isTrue();
        assertThat(never.get("schedule").get("cronExpr").asText()).isEqualTo("0 30 2 * * *");
        assertThat(never.get("schedule").get("registered").asBoolean()).isTrue();
        assertThat(never.get("schedule").get("nextRuns")).hasSize(1);
    }

    @Test
    @DisplayName("11. 카탈로그 실패 — /jobs 200 · catalogAvailable=false · DB 잡 이름(카탈로그 밖 포함) · 상세 totalStepCnt null")
    void catalogUnavailable() throws Exception {
        CATALOG_OK.set(false);
        HttpHeaders session = adminSession();
        JsonNode body = json(get(session, BASE + "/jobs"));
        assertThat(body.get("catalogAvailable").asBoolean()).isFalse();
        List<String> names = new ArrayList<>();
        body.get("jobs").forEach(j -> names.add(j.get("jobNm").asText()));
        assertThat(names).contains(RAN_JOB, RETIRED_JOB).doesNotContain(NEVER_JOB);

        JsonNode detail = json(get(session, BASE + "/executions/" + E_RUNNING));
        assertThat(detail.get("progress").get("totalStepCnt").isNull()).isTrue();
        assertThat(detail.get("progress").get("currentStepKorNm").isNull()).isTrue();
        assertThat(detail.get("steps").get(0).get("stepKorNm").isNull()).isTrue();
    }

    @Test
    @DisplayName("13. 배치 멈춤(카탈로그 3s 지연) — /jobs 는 2s 남짓에 200 + catalogAvailable=false (axios 10s 안)")
    void catalogHangFallsBackFast() throws Exception {
        CATALOG_HANG.set(true);
        HttpHeaders session = adminSession();
        long t0 = System.nanoTime();
        JsonNode body = json(get(session, BASE + "/jobs"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(body.get("catalogAvailable").asBoolean()).isFalse();
        assertThat(ms).as("카탈로그 읽기 타임아웃 2s").isBetween(1800L, 2900L);
    }

    @Test
    @DisplayName("12. 상세 — 카탈로그 steps 로 전체 Step 수·현재 Step 한글명·Step 한글명을 채운다")
    void detailUsesCatalogSteps() throws Exception {
        JsonNode detail = json(get(adminSession(), BASE + "/executions/" + E_RUNNING));
        JsonNode p = detail.get("progress");
        assertThat(p.get("totalStepCnt").asInt()).isEqualTo(3);
        assertThat(p.get("cmptnStepCnt").asInt()).isEqualTo(1);
        assertThat(p.get("currentStep").asText()).isEqualTo("geocodeStep");
        assertThat(p.get("currentStepKorNm").asText()).isEqualTo("지오코딩");
        assertThat(detail.get("steps").get(0).get("stepKorNm").asText()).isEqualTo("수집");
        assertThat(detail.get("steps").get(1).get("stepKorNm").asText()).isEqualTo("지오코딩");

        // TSK-4337 추가 — 단계별 수치를 배치 /live 에서 받아 progress.live 로 싣는다
        JsonNode live = p.get("live");
        assertThat(live.get("dayIdx").asInt()).isEqualTo(3);
        assertThat(live.get("dayCnt").asInt()).isEqualTo(30);
        assertThat(live.get("crntYmd").asText()).isEqualTo("2026-11-03");
        assertThat(live.get("fetchCnt").asLong()).isEqualTo(6800);
        assertThat(live.get("geocodeApiLookups").asLong()).isEqualTo(850);
        assertThat(live.get("skipEmdUnresolved").isNull()).isTrue();
        assertThat(live.has("futureKey")).isFalse();
        assertThat(RECEIVED.get("GET " + OPS + "/executions/" + E_RUNNING + "/live").headers().getFirst("X-API-Key"))
                .isEqualTo(API_KEY);
    }

    // ---------------------------------------------------------------- 헬퍼

    /** 명령 엔드포인트 4종(실행·스케줄·중지·실패 처리) — {method, url, body} */
    private static List<Object[]> commands() {
        return List.of(
                new Object[] { HttpMethod.POST, BASE + "/jobs/okJob/executions", Map.of() },
                new Object[] { HttpMethod.PUT, BASE + "/schedules/okJob", Map.of("cronExpr", "0 0 1 * * *", "useYn", "Y") },
                new Object[] { HttpMethod.POST, BASE + "/executions/5/stop", Map.of() },
                new Object[] { HttpMethod.POST, BASE + "/executions/6/abandon", Map.of() });
    }

    /** 세션 없이 CSRF 쿠키·헤더만 — 미인증 POST 가 CSRF(403)가 아니라 인증(401)에서 막히는지 보려고 */
    private HttpHeaders csrfOnly() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        String cookies = IntegrationLoginSupport.cookieHeader(health);
        String xsrf = IntegrationLoginSupport.xsrfOf(cookies);
        assertThat(xsrf).isNotBlank();
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookies);
        h.add("X-XSRF-TOKEN", xsrf);
        return h;
    }

    private static void assertContentLength(Received r) {
        assertThat(r.headers().getFirst("Content-Length")).as("🔴 요청 바디는 Content-Length 로 보낸다").isNotBlank();
        assertThat(Integer.parseInt(r.headers().getFirst("Content-Length")))
                .isEqualTo(r.body().getBytes(StandardCharsets.UTF_8).length);
        assertThat(r.headers().getFirst("Transfer-Encoding")).as("🔴 chunked 금지").isNull();
    }

    private HttpHeaders adminSession() {
        HttpHeaders session = auth.mockSession();
        auth.promoteTo("ROLE_SERVICE_ADMIN");
        return session;
    }

    private HttpHeaders json(HttpHeaders session) {
        return auth.json(session);
    }

    private ResponseEntity<String> get(HttpHeaders session, String url) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(session), String.class);
    }

    private ResponseEntity<String> post(HttpHeaders session, String url, Object body) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, json(session)), String.class);
    }

    private JsonNode json(ResponseEntity<String> res) throws Exception {
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
        return om.readTree(res.getBody());
    }

    private void insertSynthetic() {
        jdbcTemplate.update("INSERT INTO batch.batch_job_instance (job_instance_id, version, job_name, job_key) VALUES (?, 0, ?, 'p1'), (?, 0, ?, 'p2')",
                RAN_INST, RAN_JOB, RETIRED_INST, RETIRED_JOB);
        String exec = """
                INSERT INTO batch.batch_job_execution
                  (job_execution_id, version, job_instance_id, create_time, start_time, end_time, status, exit_code, exit_message, last_updated)
                VALUES (?, 1, ?, now(), now(), ?, ?, ?, '', now())""";
        jdbcTemplate.update(exec, E_RUNNING, RAN_INST, null, "STARTED", "UNKNOWN");
        jdbcTemplate.update(exec, E_RETIRED, RETIRED_INST, java.time.LocalDateTime.now(), "COMPLETED", "COMPLETED");

        String step = """
                INSERT INTO batch.batch_step_execution
                  (step_execution_id, version, step_name, job_execution_id, create_time, start_time, end_time, status,
                   commit_count, read_count, filter_count, write_count, read_skip_count, write_skip_count, process_skip_count,
                   rollback_count, exit_code, exit_message, last_updated)
                VALUES (?, 1, ?, ?, now(), now(), ?, ?, 1, ?, 0, ?, 0, 0, 0, 0, ?, '', now())""";
        jdbcTemplate.update(step, -4337302L, "fetchStep", E_RUNNING, java.time.LocalDateTime.now(), "COMPLETED", 50, 50, "COMPLETED");
        jdbcTemplate.update(step, -4337301L, "geocodeStep", E_RUNNING, null, "STARTED", 10, 9, "EXECUTING");

        jdbcTemplate.update("INSERT INTO batch.tb_btch_schdl (job_nm, cron_expr, use_yn) VALUES (?, '0 0 1 * * *', 'N')", RAN_JOB);
    }

    private void deleteSynthetic() {
        String ids = "(SELECT e.job_execution_id FROM batch.batch_job_execution e JOIN batch.batch_job_instance i"
                + " ON i.job_instance_id = e.job_instance_id WHERE i.job_name LIKE 'tsk4337Proxy%')";
        jdbcTemplate.update("DELETE FROM batch.tb_btch_excn_hstry WHERE job_nm LIKE 'tsk4337Proxy%' OR job_execution_id IN " + ids);
        jdbcTemplate.update("DELETE FROM batch.batch_step_execution WHERE job_execution_id IN " + ids);
        jdbcTemplate.update("DELETE FROM batch.batch_job_execution_params WHERE job_execution_id IN " + ids);
        jdbcTemplate.update("DELETE FROM batch.batch_job_execution WHERE job_instance_id IN"
                + " (SELECT job_instance_id FROM batch.batch_job_instance WHERE job_name LIKE 'tsk4337Proxy%')");
        jdbcTemplate.update("DELETE FROM batch.batch_job_instance WHERE job_name LIKE 'tsk4337Proxy%'");
        jdbcTemplate.update("DELETE FROM batch.tb_btch_schdl WHERE job_nm LIKE 'tsk4337Proxy%'");
    }
}
