package jeju.datahub.batch.cvlcpt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DhubKeysetQuery} · {@link KeysetOrder} — keyset 페이징 공용 부품 검증
 * (검증 체크리스트 1:1).
 *
 * <p>네트워크 없이 돈다(truncated 1건만 로컬 {@link HttpServer} 로 실제 {@link DhubQueryClient} 를 탄다).
 */
class DhubKeysetQueryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PBLPKLT_TABLE = "`jeju_mini`.`tb_pblpklt`";
    private static final String CRDN_TABLE = "`jeju_smart_city_test`.`tb_crdn_dsctn`";

    private static DhubKeysetQuery pblpklt() {
        return new DhubKeysetQuery(PBLPKLT_TABLE, List.of(), null, "pklt_id", KeysetOrder.LEXICOGRAPHIC);
    }

    private static DhubKeysetQuery crdn() {
        return new DhubKeysetQuery(CRDN_TABLE, List.of(), null, "crdn_id", KeysetOrder.NUMERIC);
    }

    // --- 커서 미전진 -------------------------------------------------------------------

    @Test
    @DisplayName("🔴 커서가 전진하지 않으면(같은 키) 예외다 — 같은 페이지 무한 반복 방지")
    void nonAdvancingCursorFails() {
        assertThatThrownBy(() -> pblpklt().toPage(stringKeys("pklt_id", "1", "10", "100"), "100", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전진");
        assertThatThrownBy(() -> crdn().toPage(numberKeys(5, 6, 7), "7", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전진");
    }

    @Test
    @DisplayName("LEXICOGRAPHIC — 사전순 경계 \"100\" 다음 페이지가 \"1000\" 으로 이어진다")
    void lexicographicBoundaryContinues() {
        DhubKeysetQuery query = pblpklt();

        DhubKeysetPage first = query.toPage(stringKeys("pklt_id", "1", "10", "100"), null, 3);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isEqualTo("100");
        assertThat(query.pageSql(first.nextCursor(), 3))
                .isEqualTo("SELECT * FROM " + PBLPKLT_TABLE
                        + " WHERE pklt_id > '100' ORDER BY pklt_id LIMIT 3");

        DhubKeysetPage second = query.toPage(stringKeys("pklt_id", "1000", "1001", "1002"), "100", 3);
        assertThat(second.nextCursor()).isEqualTo("1002");
        // 🔴 숫자로 파싱하지 않는다 — "999" 는 사전순으로 "1002" 뒤다.
        assertThat(KeysetOrder.LEXICOGRAPHIC.isAdvanced("1002", "999")).isTrue();
    }

    @Test
    @DisplayName("🔴 NUMERIC — \"999\"→\"1000\" 은 전진(오탐 없음), \"1000\"→\"999\" 는 미전진 예외")
    void numericComparesAsNumbers() {
        DhubKeysetQuery query = crdn();

        DhubKeysetPage page = query.toPage(numberKeys(998, 999, 1000), "997", 3);
        assertThat(page.nextCursor()).isEqualTo("1000");
        assertThat(query.toPage(numberKeys(1000), "999", 3).nextCursor()).isEqualTo("1000");
        assertThat(KeysetOrder.NUMERIC.isAdvanced("999", "1000")).isTrue();
        // 문자열 비교였다면 여기서 오탐이 난다 — 대조용.
        assertThat(KeysetOrder.LEXICOGRAPHIC.isAdvanced("999", "1000")).isFalse();

        assertThatThrownBy(() -> query.toPage(numberKeys(999), "1000", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전진");
        assertThat(query.pageSql("999", 1000)).contains("WHERE crdn_id > 999 ORDER BY crdn_id LIMIT 1000");
    }

    @Test
    @DisplayName("🔴 NUMERIC 커서에 숫자가 아닌 값이 오면 SQL 조립 전에 예외다")
    void numericCursorRejectsInjection() {
        assertThatThrownBy(() -> crdn().pageSql("1 OR 1=1", 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crdn().pageSql("-1", 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeysetOrder.NUMERIC.literal("99999999999999999999"))
                .isInstanceOf(IllegalArgumentException.class);
        // LEXICOGRAPHIC 은 민원이 쓰던 형식 검증 그대로다.
        assertThatThrownBy(() -> pblpklt().pageSql("1' OR '1'='1", 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 필터링 계약 -------------------------------------------------------------------

    @Test
    @DisplayName("🔴 필터 문자열이 수집 SQL 과 총건수 SQL 양쪽에 동일하게 들어간다")
    void filterSharedByPageAndCountSql() {
        String filter = DhubSql.halfOpenRange("crdn_dt", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"));
        DhubKeysetQuery query = new DhubKeysetQuery(
                CRDN_TABLE, List.of("crdn_id", "crdn_dt"), filter, "crdn_id", KeysetOrder.NUMERIC);

        String where = " WHERE (" + filter + ")";
        assertThat(query.countSql()).isEqualTo("SELECT count(*) AS cnt FROM " + CRDN_TABLE + where);
        assertThat(query.pageSql(null, 1000)).isEqualTo(
                "SELECT crdn_id, crdn_dt FROM " + CRDN_TABLE + where + " ORDER BY crdn_id LIMIT 1000");
        assertThat(query.pageSql("42", 1000)).isEqualTo(
                "SELECT crdn_id, crdn_dt FROM " + CRDN_TABLE + where
                        + " AND crdn_id > 42 ORDER BY crdn_id LIMIT 1000");
    }

    @Test
    @DisplayName("필터가 없으면 WHERE 가 없고, 두 SQL 모두 전량을 본다")
    void noFilterMeansNoWhere() {
        assertThat(pblpklt().countSql()).isEqualTo("SELECT count(*) AS cnt FROM " + PBLPKLT_TABLE);
        assertThat(pblpklt().pageSql(null, 1000))
                .isEqualTo("SELECT * FROM " + PBLPKLT_TABLE + " ORDER BY pklt_id LIMIT 1000");
        assertThat(new DhubKeysetQuery(PBLPKLT_TABLE, null, "  ", "pklt_id", KeysetOrder.LEXICOGRAPHIC).countSql())
                .doesNotContain("WHERE");
    }

    @Test
    @DisplayName("식별자는 DhubSql 화이트리스트를 통과해야 한다")
    void identifiersAreWhitelisted() {
        assertThatThrownBy(() -> new DhubKeysetQuery(
                "`db`.`t`; DROP TABLE x", List.of(), null, "id", KeysetOrder.NUMERIC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DhubKeysetQuery(
                CRDN_TABLE, List.of("crdn_id, 1"), null, "crdn_id", KeysetOrder.NUMERIC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DhubKeysetQuery(CRDN_TABLE, List.of(), null, "crdn_id", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 페이지 판정 -------------------------------------------------------------------

    @Test
    @DisplayName("다음 페이지 판정 — size==pageSize 면 hasMore, 미만이면 끝, 빈 페이지면 끝(커서 null)")
    void hasMoreIsRowCountEqualsPageSize() {
        assertThat(crdn().toPage(numberKeys(1, 2, 3), null, 3).hasMore()).isTrue();

        DhubKeysetPage partial = crdn().toPage(numberKeys(4, 5), "3", 3);
        assertThat(partial.hasMore()).isFalse();
        assertThat(partial.nextCursor()).isEqualTo("5");

        DhubKeysetPage empty = crdn().toPage(MAPPER.createArrayNode(), "5", 3);
        assertThat(empty.hasMore()).isFalse();
        assertThat(empty.nextCursor()).isNull();
    }

    @Test
    @DisplayName("LIMIT 보다 많은 행이 오면 판정 전제가 깨졌으므로 예외다")
    void moreRowsThanLimitFails() {
        assertThatThrownBy(() -> crdn().toPage(numberKeys(1, 2, 3, 4), null, 3))
                .isInstanceOf(DhubApiException.class);
    }

    @Test
    @DisplayName("🔴 키가 NULL·누락인 행은 커서를 망가뜨리지 않고 예외로 끊는다")
    void nullKeyFails() throws Exception {
        ArrayNode nullKey = (ArrayNode) MAPPER.readTree("[{\"crdn_id\":1},{\"crdn_id\":null}]");
        assertThatThrownBy(() -> crdn().toPage(nullKey, null, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NULL");

        ArrayNode missing = (ArrayNode) MAPPER.readTree("[{\"other\":\"x\"}]");
        assertThatThrownBy(() -> pblpklt().toPage(missing, null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("누락");
    }

    @Test
    @DisplayName("페이지 크기는 1..1000 — 0·1001 은 예외(max_rows 서버 검증 ge:1·le:1000)")
    void pageSizeBounds() {
        assertThatThrownBy(() -> crdn().pageSql(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crdn().pageSql(null, 1001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crdn().toPage(MAPPER.createArrayNode(), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(crdn().pageSql(null, 1)).endsWith("LIMIT 1");
        assertThat(crdn().pageSql(null, 1000)).endsWith("LIMIT 1000");
    }

    @Test
    @DisplayName("🔴 truncated=true 응답은 fetchPage 에서 예외다 — 잘린 페이지로 커서를 옮기지 않는다")
    void truncatedResponseFailsFetchPage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(DhubAuthClient.LOGIN_PATH, exchange -> DhubAuthClientTest.respond(exchange, 200,
                "{\"access_token\":\"%s\",\"token_type\":\"bearer\"}"
                        .formatted(DhubAuthClientTest.jwtExpiringAt(Instant.now().plusSeconds(900)))));
        server.createContext(DhubQueryClient.QUERY_PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            DhubAuthClientTest.respond(exchange, 200,
                    "{\"rows\":[{\"crdn_id\":1},{\"crdn_id\":2}],\"row_count\":2,\"truncated\":true}");
        });
        server.start();
        try {
            DhubProperties properties = new DhubProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setEmail("tester@example.com");
            properties.setPassword("TEST-ONLY-NOT-A-REAL-SECRET");
            properties.setTableId("dataset.test");
            properties.setTimeoutMs(3_000);
            DhubQueryClient client = new DhubQueryClient(new DhubAuthClient(properties), properties);

            assertThatThrownBy(() -> crdn().fetchPage(client, null, 2))
                    .isInstanceOf(DhubApiException.class)
                    .hasMessageContaining("truncated");
        } finally {
            server.stop(0);
        }
    }

    // --- 도우미 ---------------------------------------------------------------------

    private static ArrayNode stringKeys(String column, String... keys) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (String key : keys) {
            rows.addObject().put(column, key);
        }
        return rows;
    }

    /** 단속 {@code crdn_id} 는 JSON number 로 온다(스파이크 실측). */
    private static ArrayNode numberKeys(long... keys) {
        ArrayNode rows = MAPPER.createArrayNode();
        IntStream.range(0, keys.length).forEach(i -> rows.addObject().put("crdn_id", keys[i]));
        return rows;
    }
}
