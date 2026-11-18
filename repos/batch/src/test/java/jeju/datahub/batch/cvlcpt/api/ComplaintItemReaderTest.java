package jeju.datahub.batch.cvlcpt.api;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import jeju.datahub.batch.collect.*;

class ComplaintItemReaderTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-11-14T06:00:00Z"),ZoneOffset.UTC);
    final DhubProperties properties=new DhubProperties();
    final ComplaintApiClient client=mock(ComplaintApiClient.class);
    @Test void defaultUsesStoredSourceMaximumAndFrozenSeoulDay() {
        var last=LocalDateTime.parse("2026-10-01T03:00:00.123456");
        var reader=new ComplaintItemReader(client,properties,2,null,null,null,null,clock,new ComplaintReadProgress(),()->last);
        assertThat(reader.range().fromInclusive()).isEqualTo(last);
        assertThat(reader.range().toExclusive()).isEqualTo(LocalDateTime.parse("2026-11-15T00:00:00"));
    }
    @Test void firstRunHasNoLowerBound() {
        var reader=new ComplaintItemReader(client,properties,2,null,null,null,null,clock);
        assertThat(reader.range().fromInclusive()).isNull();
    }
    @Test void manualDatesFilterSourceLoadDate() {
        var reader=new ComplaintItemReader(client,properties,2,null,null,"2026-09-01","2026-10-01",clock);
        assertThat(reader.range().filter()).contains("load_dt >= '2026-09-01 00:00:00.000000'", "load_dt < '2026-10-01 00:00:00.000000'");
    }
    @Test void pagesWithDuplicateAndNullSourceIdsAreNotLost() throws Exception {
        var row=ComplaintOriginRow.fromApi(new ObjectMapper().readTree("{\"cvlcpt_id\":null,\"ocrn_dt\":\"2026-01-01 00:00:00\"}"));
        String first=new SourceLoadCursor(null,"1".repeat(64)).encode();
        String second=new SourceLoadCursor(null,"2".repeat(64)).encode();
        when(client.fetch(any(SourceLoadRange.class),isNull(),eq(1))).thenReturn(new ComplaintPage(List.of(row),true,first));
        when(client.fetch(any(SourceLoadRange.class),eq(first),eq(1))).thenReturn(new ComplaintPage(List.of(row),false,second));
        var reader=new ComplaintItemReader(client,properties,1,null,null,null,null,clock);
        assertThat(reader.read()).isEqualTo(row);
        assertThat(reader.read()).isEqualTo(row);
        assertThat(reader.read()).isNull();
    }
    @Test void nonAdvancingCursorFails() throws Exception {
        var row=ComplaintOriginRow.fromApi(new ObjectMapper().readTree("{}"));
        String cursor=new SourceLoadCursor(null,"1".repeat(64)).encode();
        when(client.fetch(any(SourceLoadRange.class),any(),eq(1))).thenReturn(new ComplaintPage(List.of(row),true,cursor));
        var reader=new ComplaintItemReader(client,properties,1,null,null,null,null,clock);
        reader.read();
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
    }
}
