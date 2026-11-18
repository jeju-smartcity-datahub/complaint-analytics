package jeju.datahub.batch.collect;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jeju.datahub.batch.cvlcpt.api.*;

class SourceLoadQueryTest {
    static final LocalDateTime DT = LocalDateTime.parse("2026-11-01T12:00:00.123456");
    final SourceLoadRange range = new SourceLoadRange(DT, LocalDateTime.parse("2026-11-15T00:00:00"));
    final SourceLoadQuery query = new SourceLoadQuery("`source`.`records`", List.of("cvlcpt_id", "load_dt", "ttl"), range);
    final DhubQueryClient client = mock(DhubQueryClient.class);
    final ObjectMapper mapper = new ObjectMapper();
    ArrayNode rows(String json) throws Exception { return (ArrayNode)mapper.readTree(json); }
    @Test void incrementalUsesExactMaximumAndIncludesTodayAndNulls() {
        var resolved = SourceLoadRange.resolve(null, null, () -> DT, LocalDate.of(2026,11,14));
        assertThat(resolved.fromInclusive()).isEqualTo(DT);
        assertThat(resolved.toExclusive()).isEqualTo(LocalDate.of(2026,11,15).atStartOfDay());
        assertThat(resolved.filter()).contains("load_dt >= '2026-11-01 12:00:00.123456'", "OR load_dt IS NULL");
    }
    @Test void emptyTableHasNoLowerBoundAndManualRangeBypassesWatermark() {
        assertThat(SourceLoadRange.resolve(null,null,()->null,LocalDate.of(2026,11,14)).fromInclusive()).isNull();
        var explicit = SourceLoadRange.resolve("2026-10-01","2026-11-01",()->{throw new AssertionError();},LocalDate.of(2026,11,14));
        assertThat(explicit.fromInclusive()).isEqualTo(LocalDate.of(2026,10,1).atStartOfDay());
        assertThatThrownBy(()->SourceLoadRange.resolve(null,"2026-11-01",()->null,LocalDate.of(2026,11,14))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SourceLoadRange.resolve("2026-11-02","2026-11-01",()->null,LocalDate.of(2026,11,14))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pageSqlDoesNotDependOnSourcePkUniqueness() {
        var cursor = new SourceLoadCursor(DT,"a".repeat(64));
        String sql = query.pageSql(cursor.encode(),2);
        assertThat(sql).contains("SELECT DISTINCT", "SHA256(toJSONString(tuple(", "ORDER BY load_dt ASC NULLS FIRST, batch_row_key ASC",
                "load_dt = '2026-11-01 12:00:00.123456' AND batch_row_key > '"+"a".repeat(64)+"'");
        assertThat(sql).doesNotContain("cvlcpt_id >", "OFFSET").endsWith("LIMIT 2");
        assertThat(SourceLoadCursor.decode(cursor.encode())).isEqualTo(cursor);
    }
    @Test void nullTimestampCursorContinuesThroughNullRowsBeforeDatedRows() {
        String sql = query.pageSql(new SourceLoadCursor(null,"a".repeat(64)).encode(),1);
        assertThat(sql).contains("load_dt IS NOT NULL OR (load_dt IS NULL AND batch_row_key >");
    }
    @Test void duplicateAndNullIdsAtSameTimestampAreAllRead() throws Exception {
        var source = rows("[{\"cvlcpt_id\":null,\"load_dt\":\"2026-11-01 12:00:00.123456\",\"batch_row_key\":\""+"1".repeat(64)+"\"},"
                +"{\"cvlcpt_id\":null,\"load_dt\":\"2026-11-01 12:00:00.123456\",\"batch_row_key\":\""+"2".repeat(64)+"\"}]");
        when(client.query(eq("dataset.test"),anyString(),eq(2))).thenReturn(source);
        var page=query.fetch(client,"dataset.test",null,2);
        assertThat(page.rows()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(SourceLoadCursor.decode(page.nextCursor()).rowKey()).isEqualTo("2".repeat(64));
    }
    @Test void repeatedOrUnorderedPagesFailInsteadOfAdvancingWatermark() throws Exception {
        var source=rows("[{\"load_dt\":\"2026-11-01 12:00:00.123456\",\"batch_row_key\":\""+"1".repeat(64)+"\"}]");
        when(client.query(anyString(),anyString(),anyInt())).thenReturn(source);
        assertThatThrownBy(()->query.fetch(client,"test",new SourceLoadCursor(DT,"1".repeat(64)).encode(),1))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void missingSourceTimestampAndMalformedCursorAreRejected() throws Exception {
        when(client.query(anyString(),anyString(),anyInt())).thenReturn(rows("[{\"batch_row_key\":\""+"1".repeat(64)+"\"}]"));
        assertThatThrownBy(()->query.fetch(client,"test",null,1)).isInstanceOf(DhubApiException.class);
        assertThatThrownBy(()->query.pageSql("x' OR 1=1",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SourceTimestamp.parse("bad-date")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SourceTimestamp.parse("2026-11-01 00:00:00.1234567")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fingerprintPreservesNullEmptyBoundariesAndUnicode() {
        assertThat(SourceFingerprint.hash((String)null)).isNotEqualTo(SourceFingerprint.hash(""));
        assertThat(SourceFingerprint.hash("ab","c")).isNotEqualTo(SourceFingerprint.hash("a","bc"));
        assertThat(SourceFingerprint.hash("제주🙂",null)).hasSize(64);
    }
}
