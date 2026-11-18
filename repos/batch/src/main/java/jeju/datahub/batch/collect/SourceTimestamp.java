package jeju.datahub.batch.collect;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 원천 load_dt. 결측만 허용하며 잘못된 시각을 현재 시각으로 대체하지 않는다. */
public final class SourceTimestamp {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private SourceTimestamp() { }
    public static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDateTime parsed = LocalDateTime.parse(value.trim().replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (parsed.getNano() % 1000 != 0) throw new IllegalArgumentException("원천 load_dt 정밀도가 PostgreSQL 마이크로초 범위를 초과합니다.");
            return parsed;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("원천 load_dt 형식이 올바르지 않습니다(ISO 날짜·시각 필요).", e);
        }
    }
    public static String format(LocalDateTime value) {
        return value == null ? null : value.format(FORMAT);
    }
    public static String literal(LocalDateTime value) {
        if (value == null) throw new IllegalArgumentException("시각 리터럴이 비어 있습니다.");
        return "'" + format(value) + "'";
    }
}
