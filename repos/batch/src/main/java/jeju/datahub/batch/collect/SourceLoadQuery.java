package jeju.datahub.batch.collect;

import java.util.List;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jeju.datahub.batch.cvlcpt.api.DhubApiException;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.cvlcpt.api.DhubQueryClient;
import jeju.datahub.batch.cvlcpt.api.DhubSql;

/** NULL/중복 원천 PK를 허용하는 ClickHouse 질의. load_dt 오름차순 커밋으로 실패 후 증분 누락을 막는다. */
public final class SourceLoadQuery {
    private final String table;
    private final String columns;
    private final SourceLoadRange range;
    public SourceLoadQuery(String table, List<String> columns, SourceLoadRange range) {
        this.table = DhubSql.table(table);
        this.columns = columns.stream().map(DhubSql::column).collect(Collectors.joining(", "));
        if (!columns.contains("load_dt")) throw new IllegalArgumentException("load_dt 조회 컬럼이 필요합니다.");
        this.range = range;
    }
    public String pageSql(String cursor, int size) {
        SourceLoadCursor after = SourceLoadCursor.decode(cursor);
        return "SELECT DISTINCT " + columns
                + ", lower(hex(SHA256(toJSONString(tuple(" + columns + "))))) AS batch_row_key FROM " + table
                + " WHERE " + range.filter() + (after == null ? "" : " AND " + after.filter())
                + " ORDER BY load_dt ASC NULLS FIRST, batch_row_key ASC LIMIT " + DhubSql.limit(size);
    }
    public DhubKeysetPage fetch(DhubQueryClient client, String sourceId, String cursor, int size) {
        ArrayNode rows = client.query(sourceId, pageSql(cursor, size), size);
        if (rows.size() > size) throw new DhubApiException("원천 응답이 요청한 페이지 크기를 초과했습니다.");
        SourceLoadCursor previous = SourceLoadCursor.decode(cursor);
        for (JsonNode row : rows) {
            if (!row.has("load_dt")) throw new DhubApiException("원천 응답에 load_dt 컬럼이 없습니다.");
            SourceLoadCursor next = new SourceLoadCursor(SourceTimestamp.parse(row.path("load_dt").asText(null)),
                    row.path("batch_row_key").asText(null));
            if (previous != null && next.compareTo(previous) <= 0)
                throw new IllegalStateException("load_dt 수집 커서가 전진하지 않습니다. 원천 정렬/중복을 확인하세요.");
            if (next.loadDt() != null && (next.loadDt().compareTo(range.toExclusive()) >= 0
                    || (range.fromInclusive() != null && next.loadDt().isBefore(range.fromInclusive()))))
                throw new DhubApiException("원천이 요청한 load_dt 구간 밖의 행을 반환했습니다.");
            previous = next;
        }
        return new DhubKeysetPage(rows, rows.size() == size, rows.isEmpty() ? null : previous.encode());
    }
}
