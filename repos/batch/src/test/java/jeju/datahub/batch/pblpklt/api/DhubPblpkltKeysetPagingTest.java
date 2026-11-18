package jeju.datahub.batch.pblpklt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;
import jeju.datahub.batch.cvlcpt.api.DhubAuthClient;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.cvlcpt.api.DhubQueryClient;

/**
 * 주차장 keyset 페이징 <b>실소켓 검증</b> — Reader → {@link DhubPblpkltApiClient} → 공용 {@link DhubQueryClient} 를
 * 실제로 태워, 로컬 {@link HttpServer} 가 받은 SQL 로 커서가 이어지는지 본다.
 *
 * <p>가짜 서버는 받은 SQL 의 {@code pklt_id > '커서'} · {@code LIMIT n} 을 해석해 메모리 원천에서
 * <b>문자열 사전순</b>(ClickHouse {@code String} 과 같은 바이트 비교)으로 잘라 돌려준다. 그래서 커서 리터럴이
 * 숫자로 가거나 정렬 기준이 어긋나면 행이 겹치거나 빠져 단언이 깨진다.
 */
class DhubPblpkltKeysetPagingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE_ID = "dataset.pblpklt-test";
    private static final String SOURCE_TABLE = "`jeju_mini`.`tb_pblpklt`";

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String QUERY_PATH = "/api/v1/analytics/query";

    /** 문자열 리터럴 커서만 인식한다 — 숫자 리터럴이면 매치되지 않아 첫 페이지를 다시 주고, 미전진 가드가 터진다. */
    private static final Pattern CURSOR = Pattern.compile("pklt_id > '([^']*)'");
    private static final Pattern LIMIT = Pattern.compile("LIMIT (\\d+)");
    /** 🔴 사전순 키에 섞이면 안 되는 숫자 캐스팅 흔적. */
    private static final Pattern NUMERIC_CAST =
            Pattern.compile("(?i)toU?Int\\d*|CAST\\s*\\(|toFloat|toDecimal|reinterpret|pklt_id\\s*[<>=]+\\s*\\d");

    private HttpServer server;
    private DhubProperties properties;

    /** 원천 키 목록. 서버가 사전순으로 정렬해 쓴다. */
    private List<String> sourceKeys = List.of();

    private final AtomicLong countOverride = new AtomicLong(-1);
    /** N번째(1부터) 페이지 요청에 500 을 준다. 0 이면 끔. */
    private final AtomicInteger failOnPage = new AtomicInteger(0);
    /** 🔴 가드 B' — true 면 커서를 무시하고 첫 페이지를 다시 준다(원천 정렬 이상 흉내). */
    private final AtomicBoolean ignoreCursor = new AtomicBoolean(false);
    /** 🔴 가드 A' — true 면 페이지 응답에 truncated=true 를 싣는다. */
    private final AtomicBoolean truncated = new AtomicBoolean(false);

    private final List<JsonNode> pageBodies = new CopyOnWriteArrayList<>();
    private final List<String> countQueries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(LOGIN_PATH, exchange -> respond(exchange, 200,
                "{\"access_token\":\"%s\",\"token_type\":\"bearer\"}"
                        .formatted(jwtExpiringAt(Instant.now().plusSeconds(900)))));
        server.createContext(QUERY_PATH, exchange -> {
            JsonNode body = MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sql = body.path("query").asText();
            if (sql.startsWith("SELECT count(*)")) {
                countQueries.add(sql);
                long cnt = countOverride.get() >= 0 ? countOverride.get() : sourceKeys.size();
                respond(exchange, 200, "{\"rows\":[{\"cnt\":" + cnt + "}],\"row_count\":1,\"truncated\":false}");
                return;
            }
            pageBodies.add(body);
            if (failOnPage.get() == pageBodies.size()) {
                respond(exchange, 500, "{\"detail\":\"boom\"}");
                return;
            }
            respond(exchange, 200, page(sql).toString());
        });
        server.start();

        properties = new DhubProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setEmail("tester@example.com");
        properties.setPassword("TEST-ONLY-NOT-A-REAL-SECRET");
        properties.setTableId("dataset.complaint-should-not-be-used");
        properties.setPblpkltTableId(TABLE_ID);
        properties.setPblpkltSourceTable(SOURCE_TABLE);
        properties.setTimeoutMs(3_000);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("🔴 사전순 경계 \"100\"→\"1000\"→\"1001\" 이 이어지고 누락·중복이 없다 — 커서는 문자열 리터럴, 숫자 캐스팅 없음")
    void pagesLexicographicallyAcrossDigitBoundary() {
        // 사전순: 1, 10, 100 | 1000, 1001, 1002 | 101, 2, 999 | (빈 페이지 — 9행이 3으로 나눠떨어진다)
        sourceKeys = List.of("999", "1", "1001", "100", "2", "1000", "10", "101", "1002");
        properties.setPblpkltPageSize(3);
        DhubPblpkltItemReader reader = newReader();

        List<String> read = readAll(reader);

        assertThat(read).containsExactly("1", "10", "100", "1000", "1001", "1002", "101", "2", "999");
        assertThat(reader.getSeenPkltIds()).containsExactlyInAnyOrderElementsOf(sourceKeys);
        assertThat(pageBodies).hasSize(4);

        List<String> sqls = pageBodies.stream().map(b -> b.path("query").asText()).toList();
        assertThat(sqls.get(0)).doesNotContain("WHERE").endsWith("ORDER BY pklt_id LIMIT 3");
        assertThat(sqls.get(1)).contains("WHERE pklt_id > '100' ORDER BY pklt_id LIMIT 3");
        assertThat(sqls.get(2)).contains("WHERE pklt_id > '1002' ORDER BY pklt_id LIMIT 3");
        // 숫자 순서였다면 '2' 가 '101' 앞, 마지막이 '1002' 였을 것이다 — 커서가 사전순 최대값 '999' 로 끝난다
        assertThat(sqls.get(3)).contains("WHERE pklt_id > '999' ORDER BY pklt_id LIMIT 3");
        // 🔴 사전순 키에 숫자 캐스팅·숫자 리터럴 비교가 한 곳도 없다 — 수집·총건수 SQL 모두
        assertThat(sqls).noneMatch(sql -> NUMERIC_CAST.matcher(sql).find());
        assertThat(countQueries).noneMatch(sql -> NUMERIC_CAST.matcher(sql).find());

        // SELECT 목록 = 매핑이 읽는 33필드 전부(키 포함, 중복 없음), FROM = 설정 테이블
        assertThat(sqls.get(0)).startsWith("SELECT " + String.join(", ", PblpkltOriginRow.SOURCE_COLUMNS)
                + " FROM " + SOURCE_TABLE);
        assertThat(DhubPblpkltApiClient.SELECT_COLUMNS)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(PblpkltOriginRow.SOURCE_COLUMNS)
                .doesNotContain("*");
        // 전송 규약 — 주차장 데이터셋으로, max_rows == LIMIT
        assertThat(pageBodies).allSatisfy(b -> {
            assertThat(b.path("source_id").asText()).isEqualTo(TABLE_ID);
            assertThat(b.path("max_rows").asInt()).isEqualTo(3);
        });
        // 총건수는 같은 테이블·같은 (빈) WHERE
        assertThat(countQueries).containsExactly("SELECT count(*) AS cnt FROM " + SOURCE_TABLE);
    }

    @Test
    @DisplayName("원천이 페이지 크기로 나눠떨어지면 빈 마지막 페이지를 1회 더 받고 정상 종료한다")
    void exactMultipleFetchesEmptyLastPage() {
        sourceKeys = List.of("1", "10", "100", "1000");
        properties.setPblpkltPageSize(2);

        assertThat(readAll(newReader())).hasSize(4);
        assertThat(pageBodies).hasSize(3);
    }

    @Test
    @DisplayName("🔴 총건수 > 누적 수신이면 open() 에서 DhubApiException — 한 행도 emit 되지 않는다(소멸 마킹 근거 없음)")
    void failsBeforeEmittingWhenCountExceedsReceived() {
        sourceKeys = List.of("1", "10", "100", "1000", "1001");
        countOverride.set(6);
        properties.setPblpkltPageSize(2);
        DhubPblpkltItemReader reader = newReader();

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("원천 6행 중 5행");
        assertThat(pageBodies).hasSize(3);
        assertThat(reader.getSeenPkltIds()).isEmpty();
    }

    @Test
    @DisplayName("🔴 페이지 중간(2번째) HTTP 500 이면 예외가 전파되고 seen set 은 비어 있다 — 부분 수집으로 소멸 마킹 불가")
    void midPagingFailurePropagates() {
        sourceKeys = List.of("1", "10", "100", "1000", "1001");
        failOnPage.set(2);
        properties.setPblpkltPageSize(2);
        DhubPblpkltItemReader reader = newReader();

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("HTTP 500");
        assertThat(pageBodies).hasSize(2);
        assertThat(reader.getSeenPkltIds()).isEmpty();
    }

    @Test
    @DisplayName("🔴 가드 A' — 페이지 응답이 truncated=true 면 open() 에서 DhubApiException, seen set 은 비어 있다")
    void truncatedPageFails() {
        sourceKeys = List.of("1", "10", "100", "1000", "1001");
        truncated.set(true);
        properties.setPblpkltPageSize(2);
        DhubPblpkltItemReader reader = newReader();

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(DhubApiException.class)
                .hasMessageContaining("truncated");
        assertThat(pageBodies).hasSize(1);
        assertThat(reader.getSeenPkltIds()).isEmpty();
    }

    @Test
    @DisplayName("🔴 가드 B' — 원천이 커서를 무시해 같은 페이지를 주면 open() 에서 미전진 예외, seen set 은 비어 있다")
    void nonAdvancingCursorFails() {
        sourceKeys = List.of("1", "10", "100", "1000", "1001");
        ignoreCursor.set(true);
        properties.setPblpkltPageSize(2);
        DhubPblpkltItemReader reader = newReader();

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전진");
        assertThat(pageBodies).hasSize(2);
        assertThat(reader.getSeenPkltIds()).isEmpty();
    }

    @Test
    @DisplayName("🔴 page-size 0 이면 어떤 HTTP 요청도 나가기 전에 IllegalStateException — LIMIT 0 · 0행 성공 금지")
    void zeroPageSizeFailsBeforeHttp() {
        sourceKeys = List.of("1", "10");
        properties.setPblpkltPageSize(0);

        assertThatThrownBy(() -> newReader().read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dhub.pblpklt-page-size");
        assertThat(pageBodies).isEmpty();
        assertThat(countQueries).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private DhubPblpkltItemReader newReader() {
        DhubQueryClient queryClient = new DhubQueryClient(new DhubAuthClient(properties), properties);
        return new DhubPblpkltItemReader(new DhubPblpkltApiClient(queryClient, properties), properties);
    }

    /** {@link #readAll} 의 반복 상한 — 무한 루프 방지용이며 픽스처 행 수보다 충분히 크다. */
    private static final int MAX_TEST_ROWS = 100_000;

    private static List<String> readAll(DhubPblpkltItemReader reader) {
        List<String> ids = new ArrayList<>();
        // 시큐어코딩 4.3 — read() 가 선언한 checked 예외(java.text.ParseException)만 잡는다. 나머지는 그대로 테스트 실패.
        try {
            reader.open(new ExecutionContext());
            // 시큐어코딩 5.1·3.2 — null 은 if 로 먼저 거르고, 반복은 상한을 둔다(픽스처는 수십 행).
            for (int i = 0; i < MAX_TEST_ROWS; i++) {
                PblpkltOriginRow row = reader.read();
                if (row == null) {
                    break;
                }
                ids.add(row.pkltId());
            }
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    /** 받은 SQL 을 ClickHouse 처럼 해석한 응답 — 문자열 커서 초과 · 사전순 오름차순 · LIMIT. */
    private ObjectNode page(String sql) {
        Matcher cursor = CURSOR.matcher(sql);
        String after = cursor.find() && !ignoreCursor.get() ? cursor.group(1) : null;
        Matcher limit = LIMIT.matcher(sql);
        int n = limit.find() ? Integer.parseInt(limit.group(1)) : Integer.MAX_VALUE;

        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode rows = response.putArray("rows");
        sourceKeys.stream().sorted()
                .filter(k -> after == null || k.compareTo(after) > 0)
                .limit(n)
                .forEach(k -> rows.addObject()
                        .put("pklt_id", k)
                        .put("pklt_nm", "테스트 주차장 " + k)
                        .put("pklt_cpcty", 37)
                        // analytics/query 실응답처럼 JSON 숫자(덤프는 문자열 "1073.000000000")
                        .put("pkltar", 1073)
                        .put("pklt_addr", "상모리 " + k));
        response.put("row_count", rows.size());
        response.put("truncated", truncated.get());
        return response;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** 서명 검증 없이 payload 의 exp 만 읽히는 JWT 모양 토큰. */
    private static String jwtExpiringAt(Instant exp) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String payload = "{\"sub\":\"tester\",\"iat\":%d,\"exp\":%d}"
                .formatted(exp.minusSeconds(900).getEpochSecond(), exp.getEpochSecond());
        return encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString("sig".getBytes(StandardCharsets.UTF_8));
    }
}
