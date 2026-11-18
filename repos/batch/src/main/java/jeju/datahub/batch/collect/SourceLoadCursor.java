package jeju.datahub.batch.collect;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

/** load_dt + 행 내용 해시. 원천 PK의 NULL/중복 여부와 독립적이다. */
public record SourceLoadCursor(LocalDateTime loadDt, String rowKey) implements Comparable<SourceLoadCursor> {
    public SourceLoadCursor {
        if (rowKey == null || !rowKey.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("수집 커서의 행 해시가 올바르지 않습니다.");
    }
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ((loadDt == null ? "" : SourceTimestamp.format(loadDt)) + "|" + rowKey).getBytes(StandardCharsets.UTF_8));
    }
    public static SourceLoadCursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException("수집 커서 형식이 올바르지 않습니다.");
        return new SourceLoadCursor(SourceTimestamp.parse(parts[0]), parts[1]);
    }
    public String filter() {
        if (loadDt == null) return "(load_dt IS NOT NULL OR (load_dt IS NULL AND batch_row_key > '" + rowKey + "'))";
        String dt = SourceTimestamp.literal(loadDt);
        return "(load_dt > " + dt + " OR (load_dt = " + dt + " AND batch_row_key > '" + rowKey + "'))";
    }
    @Override public int compareTo(SourceLoadCursor other) {
        int dates = loadDt == null ? (other.loadDt == null ? 0 : -1)
                : (other.loadDt == null ? 1 : loadDt.compareTo(other.loadDt));
        return dates == 0 ? rowKey.compareTo(other.rowKey) : dates;
    }
}
