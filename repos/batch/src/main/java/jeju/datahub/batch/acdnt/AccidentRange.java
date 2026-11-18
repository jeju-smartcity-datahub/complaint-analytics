package jeju.datahub.batch.acdnt;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import jeju.datahub.batch.collect.SourceTimestamp;
import jeju.datahub.batch.cvlcpt.api.DhubSql;

/** 사고일시 [마지막 적재 시각, 오늘 다음날 00:00). 수동 from/to는 과거 보강용이다. */
public record AccidentRange(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
    public AccidentRange {
        if (toExclusive == null || (fromInclusive != null && !fromInclusive.isBefore(toExclusive)))
            throw new IllegalArgumentException("사고일시 수집 구간은 시작 < 끝이어야 합니다.");
    }

    public static AccidentRange resolve(String from, String to, Supplier<LocalDateTime> watermark, LocalDate today) {
        boolean hasFrom = from != null && !from.isBlank();
        boolean hasTo = to != null && !to.isBlank();
        if (hasTo && !hasFrom) throw new IllegalArgumentException("to는 from과 함께 지정하세요.");
        LocalDateTime lower = hasFrom ? DhubSql.date(from).atStartOfDay() : watermark.get();
        LocalDateTime upper = hasTo ? DhubSql.date(to).atStartOfDay() : today.plusDays(1).atStartOfDay();
        if (upper.isAfter(today.plusDays(1).atStartOfDay()))
            throw new IllegalArgumentException("사고일시 수집 상한은 오늘 다음날 00:00까지입니다.");
        return new AccidentRange(lower, upper);
    }

    public String filter() {
        return "acdnt_dt < " + SourceTimestamp.literal(toExclusive)
                + (fromInclusive == null ? "" : " AND acdnt_dt >= " + SourceTimestamp.literal(fromInclusive));
    }

    public boolean contains(LocalDateTime value) {
        return value != null && value.isBefore(toExclusive)
                && (fromInclusive == null || !value.isBefore(fromInclusive));
    }
}
