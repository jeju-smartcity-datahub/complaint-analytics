package jeju.datahub.batch.acdnt;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AccidentRangeTest {
    private final LocalDate today = LocalDate.of(2026, 11, 16);
    @Test void firstRunAndTodayBoundary() {
        var range = AccidentRange.resolve(null, null, () -> null, today);
        assertThat(range.fromInclusive()).isNull();
        assertThat(range.contains(today.atTime(23, 59, 59, 999999000))).isTrue();
        assertThat(range.contains(today.plusDays(1).atStartOfDay())).isFalse();
        assertThat(range.filter()).doesNotContain("load_dt").contains("acdnt_dt < '2026-11-17");
    }
    @Test void watermarkIsInclusiveAndPreservesTime() {
        LocalDateTime last = today.atTime(14, 55, 12, 123456000);
        var range = AccidentRange.resolve(null, null, () -> last, today);
        assertThat(range.fromInclusive()).isEqualTo(last);
        assertThat(range.contains(last)).isTrue();
        assertThat(range.contains(last.minusNanos(1000))).isFalse();
        assertThat(range.filter()).contains("acdnt_dt >= '2026-11-16 14:55:12.123456'");
    }
    @Test void explicitBackfillOverridesWatermark() {
        var range = AccidentRange.resolve("2025-01-01", "2026-01-01", () -> today.atStartOfDay(), today);
        assertThat(range.fromInclusive()).isEqualTo(LocalDate.of(2025, 1, 1).atStartOfDay());
        assertThat(range.toExclusive()).isEqualTo(LocalDate.of(2026, 1, 1).atStartOfDay());
    }
    @Test void invalidRangesCannotReachSql() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccidentRange.resolve(null, "2026-01-01", () -> null, today));
        assertThatIllegalArgumentException().isThrownBy(() -> AccidentRange.resolve("2026-01-01", "2025-01-01", () -> null, today));
        assertThatIllegalArgumentException().isThrownBy(() -> AccidentRange.resolve("2026-01-01", "2026-11-18", () -> null, today));
        assertThatIllegalArgumentException().isThrownBy(() -> AccidentRange.resolve("2026-01-01' OR 1=1", null, () -> null, today));
    }
}
