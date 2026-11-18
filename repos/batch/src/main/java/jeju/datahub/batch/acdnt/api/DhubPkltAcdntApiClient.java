package jeju.datahub.batch.acdnt.api;

import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jeju.datahub.batch.acdnt.AccidentProperties;
import jeju.datahub.batch.acdnt.AccidentRange;
import jeju.datahub.batch.collect.SourceTimestamp;
import jeju.datahub.batch.cvlcpt.api.DhubApiException;
import jeju.datahub.batch.cvlcpt.api.DhubQueryClient;
import jeju.datahub.batch.cvlcpt.api.DhubSql;
import org.springframework.stereotype.Component;

/** 사고일시 + 원천 행 해시 복합 커서. seq_no가 NULL/중복이어도 누락 없이 조회한다. */
@Component
public class DhubPkltAcdntApiClient {
    public record Cursor(LocalDateTime date, String hash) implements Comparable<Cursor> {
        public Cursor {
            if (date == null || hash == null || !hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("사고이력 수집 커서가 올바르지 않습니다.");
        }
        @Override public int compareTo(Cursor other) {
            int dates = date.compareTo(other.date);
            return dates == 0 ? hash.compareTo(other.hash) : dates;
        }
    }
    public record Page(List<PkltAcdntOriginRow> rows, Cursor next, boolean hasMore) { }
    private final DhubQueryClient client;
    private final AccidentProperties properties;
    public DhubPkltAcdntApiClient(DhubQueryClient client, AccidentProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public String pageSql(AccidentRange range, Cursor after, int size) {
        String columns = String.join(", ", PkltAcdntOriginRow.COLUMNS);
        String cursor = after == null ? "" : " AND (acdnt_dt > " + SourceTimestamp.literal(after.date())
                + " OR (acdnt_dt = " + SourceTimestamp.literal(after.date())
                + " AND batch_row_key > '" + after.hash() + "'))";
        return "SELECT DISTINCT " + columns + ", lower(hex(SHA256(toJSONString(tuple(" + columns
                + "))))) AS batch_row_key FROM " + DhubSql.table(properties.getSourceTable())
                + " WHERE " + range.filter() + cursor
                + " ORDER BY acdnt_dt ASC, batch_row_key ASC LIMIT " + DhubSql.limit(size);
    }

    public Page fetch(AccidentRange range, Cursor after, int size) {
        ArrayNode nodes = client.query(properties.getTableId(), pageSql(range, after, size), size);
        if (nodes.size() > size) throw new DhubApiException("사고이력 응답이 요청 페이지 크기를 초과했습니다.");
        var rows = new java.util.ArrayList<PkltAcdntOriginRow>();
        Cursor previous = after;
        for (JsonNode node : nodes) {
            var row = PkltAcdntOriginRow.from(node);
            if (!range.contains(row.acdntDt())) throw new DhubApiException("사고일시 수집 구간 밖의 응답입니다.");
            Cursor next = new Cursor(row.acdntDt(), node.path("batch_row_key").asText(null));
            if (previous != null && next.compareTo(previous) <= 0)
                throw new DhubApiException("사고일시 수집 커서가 전진하지 않습니다.");
            rows.add(row);
            previous = next;
        }
        return new Page(List.copyOf(rows), nodes.isEmpty() ? null : previous, nodes.size() == size);
    }
}
