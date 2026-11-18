package jeju.datahub.batch.cvlcpt.api;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jeju.datahub.batch.collect.*;
class DhubComplaintApiClientTest {
    @Test void mapsNullableSourceIdAndPreservesSourceTimestamp() throws Exception {
        var properties=new DhubProperties();properties.setSourceTable("`test`.`complaints`");properties.setTableId("dataset.complaints");
        var query=mock(DhubQueryClient.class);
        var rows=(ArrayNode)new ObjectMapper().readTree("[{\"cvlcpt_id\":null,\"ocrn_dt\":\"2026-01-01 00:00:00\",\"load_dt\":\"2026-11-01 12:00:00.123456\",\"batch_row_key\":\""+"a".repeat(64)+"\"}]");
        when(query.query(eq("dataset.complaints"),anyString(),eq(1))).thenReturn(rows);
        var page=new DhubComplaintApiClient(query,properties).fetch(new SourceLoadRange(null,LocalDateTime.parse("2026-11-15T00:00:00")),null,1);
        assertThat(page.rows().get(0).cvlcptId()).isNull();
        assertThat(page.rows().get(0).loadDt()).isEqualTo(LocalDateTime.parse("2026-11-01T12:00:00.123456"));
        assertThat(SourceLoadCursor.decode(page.nextCursor()).rowKey()).isEqualTo("a".repeat(64));
        verify(query).query(eq("dataset.complaints"),contains("ORDER BY load_dt ASC NULLS FIRST"),eq(1));
    }
}
