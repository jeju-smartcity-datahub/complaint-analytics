package jeju.datahub.batch.cvlcpt.api;
import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import jeju.datahub.batch.collect.SourceLoadRange;
class MockComplaintApiClientTest {
    @Test void mockUsesSourceLoadDatesAndSameCompositeCursor() {
        var client=new MockComplaintApiClient("mock/complaint-sample.json");
        var range=new SourceLoadRange(LocalDateTime.parse("2026-07-01T00:00:00"),LocalDateTime.parse("2026-07-02T00:00:00"));
        String cursor=null;var ids=new HashSet<String>();int count=0;
        for(int i=0;i<20;i++) {
            var page=client.fetch(range,cursor,2);
            page.rows().forEach(row->ids.add(row.cvlcptId()));count+=page.rows().size();
            if(!page.hasMore()) break;
            cursor=page.nextCursor();
        }
        assertThat(count).isEqualTo(7);
        assertThat(ids).hasSize(7);
    }
}
