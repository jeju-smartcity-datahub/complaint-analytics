package jeju.datahub.batch.acdnt.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jeju.datahub.batch.acdnt.AccidentProperties;
import jeju.datahub.batch.acdnt.AccidentRange;
import jeju.datahub.batch.cvlcpt.api.DhubApiException;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.cvlcpt.api.DhubQueryClient;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;

class DhubPkltAcdntApiClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DhubQueryClient query = mock(DhubQueryClient.class);
    private final AccidentProperties properties = properties();
    private final DhubPkltAcdntApiClient client = new DhubPkltAcdntApiClient(query, properties);
    private final AccidentRange range = new AccidentRange(LocalDateTime.of(2026, 5, 6, 0, 0),
            LocalDateTime.of(2026, 11, 17, 0, 0));

    static AccidentProperties properties() {
        AccidentProperties p = new AccidentProperties();
        p.setTableId("dataset.accident-test");
        p.setSourceTable("`test`.`tb_pklt_acdnt_hstry`");
        p.setPageSize(1);
        return p;
    }
    static ObjectNode row(String date, String hash) {
        ObjectNode row = JSON.createObjectNode();
        PkltAcdntOriginRow.COLUMNS.forEach(c -> row.putNull(c));
        row.put("origin_pk", "1").put("pklt_nm", "노형 제5").put("pklt_addr", "노형동 3793")
                .put("acdnt_dt", date).put("acdnt_type", "입구 볼라드 파손")
                .put("prcs_cn", "보수 완료").put("prcs_stts_nm", "완료").put("src_json", "{}")
                .put("batch_row_key", hash);
        return row;
    }
    @Test void sameTimeRowsHaveIndependentCursorsAcrossPages() {
        when(query.query(eq(properties.getTableId()), anyString(), eq(1)))
                .thenReturn(JSON.createArrayNode().add(row("2026-05-06 00:00:00.000000", "a".repeat(64))))
                .thenReturn(JSON.createArrayNode().add(row("2026-05-06 00:00:00.000000", "b".repeat(64))))
                .thenReturn(JSON.createArrayNode());
        var first = client.fetch(range, null, 1);
        var second = client.fetch(range, first.next(), 1);
        var last = client.fetch(range, second.next(), 1);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.next().compareTo(first.next())).isPositive();
        assertThat(last.hasMore()).isFalse();
        assertThat(last.rows()).isEmpty();
        assertThat(client.pageSql(range, first.next(), 1))
                .contains("acdnt_dt >= '2026-05-06 00:00:00.000000'")
                .contains("ORDER BY acdnt_dt ASC, batch_row_key ASC LIMIT 1")
                .contains("batch_row_key > '" + "a".repeat(64) + "'")
                .doesNotContain("load_dt");
    }
    @Test void rejectsNonAdvancingAndOutOfRangeResponses() {
        var a = new DhubPkltAcdntApiClient.Cursor(range.fromInclusive(), "a".repeat(64));
        when(query.query(anyString(), anyString(), eq(1)))
                .thenReturn(JSON.createArrayNode().add(row("2026-05-06 00:00:00", "a".repeat(64))))
                .thenReturn(JSON.createArrayNode().add(row("2026-11-17 00:00:00", "b".repeat(64))));
        assertThatExceptionOfType(DhubApiException.class).isThrownBy(() -> client.fetch(range, a, 1));
        assertThatExceptionOfType(DhubApiException.class).isThrownBy(() -> client.fetch(range, null, 1));
    }
    @Test void rejectsBadSourceSchemaAndNullDates() {
        ObjectNode bad = row("2026-05-06 00:00:00", "a".repeat(64));
        bad.remove("prcs_cn");
        assertThatIllegalArgumentException().isThrownBy(() -> PkltAcdntOriginRow.from(bad));
        var noDate = row("2026-05-06 00:00:00", "a".repeat(64));
        noDate.putNull("acdnt_dt");
        assertThatIllegalArgumentException().isThrownBy(() -> PkltAcdntOriginRow.from(noDate));
    }
    @Test void readerReopensWithFreshWatermarkAndDetectsMissingCursor() {
        DhubProperties common = new DhubProperties();
        common.setEmail("test@example.invalid");
        common.setPassword("test-only");
        var api = mock(DhubPkltAcdntApiClient.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(api.fetch(any(), isNull(), eq(1)))
                .thenReturn(new DhubPkltAcdntApiClient.Page(java.util.List.of(), null, false));
        var reader = new DhubPkltAcdntItemReader(api, common, properties, () -> { calls.incrementAndGet(); return range; });
        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNull();
        reader.close();
        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNull();
        assertThat(calls.get()).isEqualTo(2);
        reader.close();
        when(api.fetch(any(), isNull(), eq(1)))
                .thenReturn(new DhubPkltAcdntApiClient.Page(java.util.List.of(), null, true));
        assertThatIllegalStateException().isThrownBy(reader::read);
        reader.close();
    }
}
