package jeju.datahub.batch.crackdown.api;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import jeju.datahub.batch.collect.SourceLoadRange;
class CrackdownFetchRangeTest {
    @Test void usesSourceLoadTimestampInsteadOfEventDate() {
        var last=LocalDateTime.parse("2026-10-30T10:30:15");
        var range=SourceLoadRange.resolve(null,null,()->last,LocalDate.of(2026,11,14));
        assertThat(range.filter()).contains("load_dt >= '2026-10-30 10:30:15.000000'").doesNotContain("crdn_dt");
    }
}
