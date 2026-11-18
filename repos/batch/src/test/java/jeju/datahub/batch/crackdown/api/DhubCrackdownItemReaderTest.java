package jeju.datahub.batch.crackdown.api;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jeju.datahub.batch.collect.*;
import jeju.datahub.batch.cvlcpt.api.*;
class DhubCrackdownItemReaderTest {
    final DhubCrackdownApiClient client=mock(DhubCrackdownApiClient.class);
    final DhubProperties properties=new DhubProperties();
    DhubCrackdownItemReader reader() {
        properties.setEmail("test@example.com");properties.setPassword("fixture");properties.setCrackdownPageSize(1);
        properties.setCrackdownDatePattern("yyyy-MM-dd HH:mm:ss[.SSS]");
        return new DhubCrackdownItemReader(client,properties,()->new SourceLoadRange(null,LocalDateTime.parse("2026-11-15T00:00:00")));
    }
    @Test void readsAllPagesWithNullSourceIdsAndCanBeReused() throws Exception {
        var rows=(ArrayNode)new ObjectMapper().readTree("[{\"crdn_id\":null,\"crdn_dt\":\"2026-01-01 00:00:00\",\"load_dt\":null}]");
        String cursor=new SourceLoadCursor(null,"1".repeat(64)).encode();
        when(client.fetchPage(any(),isNull(),eq(1))).thenReturn(new DhubKeysetPage(rows,true,cursor));
        when(client.fetchPage(any(),eq(cursor),eq(1))).thenReturn(new DhubKeysetPage(new ObjectMapper().createArrayNode(),false,null));
        var reader=reader();
        assertThat(reader.read().originPk()).isNull();
        assertThat(reader.read()).isNull();reader.close();
        assertThat(reader.read()).isNotNull();
    }
    @Test void repeatedCursorFailsInsteadOfLooping() throws Exception {
        var rows=(ArrayNode)new ObjectMapper().readTree("[{\"crdn_dt\":\"2026-01-01 00:00:00\"}]");
        String cursor=new SourceLoadCursor(null,"1".repeat(64)).encode();
        when(client.fetchPage(any(),any(),eq(1))).thenReturn(new DhubKeysetPage(rows,true,cursor));
        var reader=reader();reader.read();
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
    }
}
