package jeju.datahub.batch.collect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import jeju.datahub.batch.cvlcpt.api.DhubSql;

/** 원천 load_dt 기준 [저장된 최대 시각, 실행일 다음날 00:00). NULL load_dt는 매 실행 재조회한다. */
public record SourceLoadRange(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
    public SourceLoadRange {
        if (toExclusive == null || (fromInclusive != null && !fromInclusive.isBefore(toExclusive)))
            throw new IllegalArgumentException("load_dt 수집 구간은 시작 < 끝이어야 합니다.");
    }
    public static SourceLoadRange resolve(String from, String to, Supplier<LocalDateTime> watermark, LocalDate today) {
        boolean hasFrom = from != null && !from.isBlank();
        boolean hasTo = to != null && !to.isBlank();
        if (hasTo && !hasFrom) throw new IllegalArgumentException("to는 from과 함께 지정하세요.");
        LocalDateTime lower = hasFrom ? DhubSql.date(from).atStartOfDay() : watermark.get();
        LocalDateTime upper = hasTo ? DhubSql.date(to).atStartOfDay() : today.plusDays(1).atStartOfDay();
        return new SourceLoadRange(lower, upper);
    }
    public String filter() {
        String dated = "load_dt < " + SourceTimestamp.literal(toExclusive);
        if (fromInclusive != null) dated += " AND load_dt >= " + SourceTimestamp.literal(fromInclusive);
        return "((" + dated + ") OR load_dt IS NULL)";
    }
}
