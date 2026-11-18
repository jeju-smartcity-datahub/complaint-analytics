package jeju.datahub.batch.cvlcpt.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;

/**
 * {@code POST /api/v1/analytics/query} 용 <b>keyset 페이징 부품</b> — 수집 SQL·총건수 SQL 조립과
 * 페이지 판정을 한 곳에 둔다 (🔁 2026-09-17 TSK-4161 Phase 2 Step 10).
 *
 * <pre>{@code
 * 수집:   SELECT <cols|*> FROM t [WHERE (<필터>) [AND 키 > 커서]] ORDER BY 키 LIMIT n
 * 총건수: SELECT count(*) AS cnt FROM t [WHERE (<필터>)]
 * }</pre>
 *
 * <h2>🔴 필터링 계약 — 두 SQL 이 같은 필터 문자열을 쓴다</h2>
 * 총건수에 필터가 빠지면 {@code 총건수(전체) > 수신(필터분)} 으로 매 실행 FAILED 이고, 수집에만 빠지면
 * 소멸 마킹이 필터 밖 행을 지운다. 그래서 필터는 생성자에서 <b>한 번만</b> 받고 두 SQL 이 모두
 * {@link #whereClause(String)} 를 거친다. 필터는 괄호로 감싼다 — {@code OR} 가 섞여도 커서 술어가
 * 필터 한쪽에만 붙는 우선순위 사고를 막는다.
 *
 * <p>🔴 <b>키 NULL 행을 수집 SQL 에서만 거르지 않는다</b>({@code 키 IS NOT NULL}). 거르려면 필터에 넣어
 * 총건수에도 함께 걸어야 한다 — 한쪽만 걸면 교차검증이 그 누락을 못 본다. NULL 키 행은
 * {@code 키 > 커서} 에서 원래 빠지므로 지금은 {@code count(*)} 교차검증이 잡는다.
 *
 * <h2>🔴 다음 페이지 판정은 {@code rows.size() == pageSize} 다 — {@code truncated} 가 아니다</h2>
 * {@code truncated} 는 SQL 결과가 {@code max_rows} 를 넘을 때만 true 라(스파이크 실측) {@code LIMIT n} ·
 * {@code max_rows n} 이 같으면 뒤에 행이 남아도 항상 false 다. true 는 설정 결함 신호로만 쓰며
 * {@link DhubQueryClient#query(String, int)} 가 이미 예외로 끊는다(여기서 다시 보지 않는다).
 *
 * <h2>식별자·값</h2>
 * 테이블·컬럼은 {@link DhubSql} 화이트리스트를, 커서는 {@link KeysetOrder#literal} 형식 검증을 거친다.
 * 필터 문자열은 호출부가 {@link DhubSql} 도우미({@code halfOpenRange} 등)로 조립한 것이어야 한다 —
 * 이 클래스는 필터 내용을 해석하지 않는다.
 */
public final class DhubKeysetQuery {

    private final String table;
    private final String selectList;
    private final String filter;
    private final String keyColumn;
    private final KeysetOrder order;

    /**
     * @param table     {@code `db`.`table`} — {@link DhubSql#table} 검증
     * @param columns   SELECT 목록. 비어 있으면 {@code *}
     * @param filter    {@link DhubSql} 로 조립한 술어. 없으면 {@code null}·빈 문자열
     * @param keyColumn keyset 키(유일해야 한다)
     * @param order     키의 정렬 방식 — 원천 컬럼 타입과 맞춘다
     */
    public DhubKeysetQuery(String table, List<String> columns, String filter,
                           String keyColumn, KeysetOrder order) {
        if (order == null) {
            throw new IllegalArgumentException("keyset 정렬 방식(KeysetOrder)이 비어 있습니다.");
        }
        this.table = DhubSql.table(table);
        this.selectList = buildSelectList(columns);
        this.filter = filter == null ? "" : filter.trim();
        this.keyColumn = DhubSql.column(keyColumn);
        this.order = order;
    }

    /** 수집 SQL — 커서가 {@code null}·공백이면 첫 페이지. */
    public String pageSql(String cursor, int pageSize) {
        return "SELECT " + selectList + " FROM " + table + whereClause(cursor)
                + " ORDER BY " + keyColumn + " LIMIT " + DhubSql.limit(pageSize);
    }

    /** 총건수 SQL — 수집 SQL 과 <b>같은 필터</b>, 커서 없음. {@link DhubDatasetCount#parse} 로 읽는다. */
    public String countSql() {
        return "SELECT count(*) AS cnt FROM " + table + whereClause(null);
    }

    /**
     * 🔴 두 SQL 이 공유하는 {@code WHERE} 절 — 이 메서드 말고는 필터를 이어 붙이는 곳이 없다.
     *
     * @return 앞에 공백이 붙은 {@code " WHERE ..."}, 조건이 하나도 없으면 빈 문자열
     */
    String whereClause(String cursor) {
        boolean hasFilter = !filter.isEmpty();
        boolean hasCursor = cursor != null && !cursor.isBlank();
        if (!hasFilter && !hasCursor) {
            return "";
        }
        StringBuilder where = new StringBuilder(" WHERE ");
        if (hasFilter) {
            where.append('(').append(filter).append(')');
        }
        if (hasCursor) {
            if (hasFilter) {
                where.append(" AND ");
            }
            where.append(keyColumn).append(" > ").append(order.literal(cursor));
        }
        return where.toString();
    }

    /**
     * 한 페이지를 받아 판정까지 끝낸다. {@code max_rows} 는 {@code LIMIT} 과 같은 값으로 보낸다
     * (생략하면 서버 기본 100 으로 잘린다).
     */
    public DhubKeysetPage fetchPage(DhubQueryClient client, String cursor, int pageSize) {
        int size = DhubSql.limit(pageSize);
        ArrayNode rows = client.query(pageSql(cursor, size), size);
        return toPage(rows, cursor, size);
    }

    /**
     * {@link #fetchPage(DhubQueryClient, String, int)} 와 같되 데이터셋({@code source_id})을 지정한다 —
     * 민원 외 원천(단속 등)용(🔁 2026-09-17 TSK-4161 Step 11). 🔴 {@code sourceId} 는 이 질의의
     * {@code FROM} 테이블과 같은 데이터셋이어야 한다.
     */
    public DhubKeysetPage fetchPage(DhubQueryClient client, String sourceId, String cursor, int pageSize) {
        int size = DhubSql.limit(pageSize);
        ArrayNode rows = client.query(sourceId, pageSql(cursor, size), size);
        return toPage(rows, cursor, size);
    }

    /**
     * 받은 행으로 페이지를 판정한다 — 네트워크 없이 검증할 수 있게 {@link #fetchPage} 에서 떼어 둔다.
     *
     * @throws DhubApiException      {@code LIMIT} 보다 많은 행이 왔을 때
     * @throws IllegalStateException 마지막 행의 키가 NULL·누락·공백이거나, 커서가 전진하지 않았을 때
     */
    DhubKeysetPage toPage(ArrayNode rows, String previousCursor, int pageSize) {
        int size = DhubSql.limit(pageSize);
        if (rows.size() > size) {
            // LIMIT 을 어겼다 — hasMore 를 size 비교로 판정할 전제가 깨졌으므로 조용히 넘기지 않는다.
            throw new DhubApiException(
                    "DHub 조회가 LIMIT 보다 많은 행을 돌려줬습니다(요청=" + size + ", 수신=" + rows.size() + ").");
        }
        String nextCursor = rows.isEmpty() ? null : keyOf(rows.get(rows.size() - 1));

        boolean hasPrevious = previousCursor != null && !previousCursor.isBlank();
        if (nextCursor != null && hasPrevious && !order.isAdvanced(previousCursor, nextCursor)) {
            throw new IllegalStateException(
                    "keyset 커서가 전진하지 않습니다(무한 루프 방지). 키=" + keyColumn + "(" + order + ")"
                            + " 직전커서=" + previousCursor + " 새커서=" + nextCursor
                            + " — 원천의 ORDER BY " + keyColumn + " 또는 키 유일성·정렬 방식을 확인하세요.");
        }
        return new DhubKeysetPage(rows, rows.size() == size, nextCursor);
    }

    /**
     * 커서로 쓸 마지막 행의 키를 읽는다. 숫자 키(JSON number)·문자열 키(JSON string) 모두
     * {@code asText()} 로 통일한다.
     *
     * <p>🔴 <b>NULL·누락·공백 키는 예외로 끊는다.</b> {@code NullNode.asText()} 는 문자열 {@code "null"} 이라
     * 그대로 두면 LEXICOGRAPHIC 커서가 {@code 'null'} 로 <b>조용히 엉뚱한 지점까지 건너뛰고</b>, 커서를
     * {@code null} 로 두면 호출부가 "끝" 으로 오인해 역시 조용히 멈춘다. ClickHouse 는 {@code ORDER BY}
     * 에서 NULL 을 뒤로 보내므로 페이지에 NULL 키가 있으면 마지막 행에 드러난다 — 마지막 행만 봐도 된다.
     * 현재 원천의 NULL 키는 0건(스파이크)이라 이 예외는 원천 변화의 경보다.
     */
    private String keyOf(JsonNode row) {
        JsonNode key = row.path(keyColumn);
        if (key.isMissingNode() || key.isNull() || key.asText().isBlank()) {
            throw new IllegalStateException(
                    "keyset 키가 비어 있는 행이 있습니다(키=" + keyColumn + ", 값="
                            + (key.isMissingNode() ? "누락" : key.isNull() ? "NULL" : "공백")
                            + "). 키 NULL 행은 커서 조건에서 조용히 빠지므로 수집을 중단합니다.");
        }
        return key.asText();
    }

    private static String buildSelectList(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return "*";
        }
        StringBuilder list = new StringBuilder();
        for (String column : columns) {
            if (list.length() > 0) {
                list.append(", ");
            }
            list.append(DhubSql.column(column));
        }
        return list.toString();
    }
}
