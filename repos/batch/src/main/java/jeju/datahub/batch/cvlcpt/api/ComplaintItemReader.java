package jeju.datahub.batch.cvlcpt.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jeju.datahub.batch.collect.SourceLoadCursor;
import jeju.datahub.batch.collect.SourceLoadRange;
import jeju.datahub.batch.collect.SourceLoadWatermark;

/** 원천 load_dt 증분 Reader. 최대 시각을 포함해 재조회하고, NULL 시각은 매 실행 먼저 읽는다. */
@Component
@StepScope
public class ComplaintItemReader implements ItemReader<ComplaintOriginRow> {
    private final ComplaintApiClient client;
    private final int pageSize;
    private final SourceLoadRange range;
    private final ComplaintReadProgress progress;
    private String cursor;
    private boolean hasMore = true;
    /** 받은 페이지 수 — @StepScope 라 실행마다 새 인스턴스에서 0 으로 시작한다. */
    private int pages;
    /** 페이지 루프 상한(시큐어코딩 3.2 — TSK-4336). 반복 조건에 직접 넣어 무한 루프를 막는다(페이지 1,000행 기준 1,000만 행). */
    static final int MAX_PAGES = 10_000;
    private Iterator<ComplaintOriginRow> buffer = List.<ComplaintOriginRow>of().iterator();

    @Autowired
    public ComplaintItemReader(ComplaintApiClient client, DhubProperties properties,
            @Value("${dhub.page-size:1000}") int pageSize,
            @Value("#{jobParameters['mode']}") String mode,
            @Value("#{jobParameters['targetDate']}") String targetDate,
            @Value("#{jobParameters['from']}") String from,
            @Value("#{jobParameters['to']}") String to,
            ComplaintReadProgress progress, SourceLoadWatermark watermark) {
        this(client, properties, pageSize, mode, targetDate, from, to, Clock.systemUTC(), progress, watermark::complaint);
    }
    ComplaintItemReader(ComplaintApiClient client, DhubProperties properties, int pageSize,
            String mode, String targetDate, String from, String to, Clock clock) {
        this(client, properties, pageSize, mode, targetDate, from, to, clock, new ComplaintReadProgress(), () -> null);
    }
    ComplaintItemReader(ComplaintApiClient client, DhubProperties properties, int pageSize,
            String mode, String targetDate, String from, String to, Clock clock, ComplaintReadProgress progress) {
        this(client, properties, pageSize, mode, targetDate, from, to, clock, progress, () -> null);
    }
    ComplaintItemReader(ComplaintApiClient client, DhubProperties properties, int pageSize,
            String mode, String targetDate, String from, String to, Clock clock, ComplaintReadProgress progress,
            Supplier<LocalDateTime> watermark) {
        this.client = client;
        this.pageSize = DhubSql.limit(pageSize);
        this.progress = progress;
        LocalDate today = properties.resolveToday(clock);
        mode = mode == null || mode.isBlank() ? properties.getQueryMode().name() : mode.trim();
        if ("daily".equalsIgnoreCase(mode)) {
            LocalDate day = targetDate == null || targetDate.isBlank() ? today.minusDays(1) : DhubSql.date(targetDate);
            this.range = new SourceLoadRange(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        } else if ("backfill".equalsIgnoreCase(mode)) {
            LocalDate lower = from == null || from.isBlank() ? properties.getBackfillFrom() : DhubSql.date(from);
            LocalDate upper = to == null || to.isBlank() ? properties.resolveBackfillTo(clock) : DhubSql.date(to);
            this.range = new SourceLoadRange(lower == null ? null : lower.atStartOfDay(), upper.atStartOfDay());
        } else {
            if (mode != null && !mode.isBlank() && !"incremental".equalsIgnoreCase(mode))
                throw new IllegalArgumentException("mode는 incremental, daily, backfill 중 하나여야 합니다.");
            if (targetDate != null && !targetDate.isBlank())
                throw new IllegalArgumentException("targetDate는 mode=daily와 함께 지정하세요.");
            this.range = SourceLoadRange.resolve(from, to, watermark, today);
        }
        progress.start(1);
        progress.day(1, range.fromInclusive() == null ? today : range.fromInclusive().toLocalDate());
    }
    @Override public ComplaintOriginRow read() {
        while (!buffer.hasNext() && hasMore && pages < MAX_PAGES) {
            ComplaintPage page = client.fetch(range, cursor, pageSize);
            pages++;
            String next = page.nextCursor();
            if (next != null && cursor != null && SourceLoadCursor.decode(next).compareTo(SourceLoadCursor.decode(cursor)) <= 0)
                throw new IllegalStateException("민원 load_dt 수집 커서가 전진하지 않습니다.");
            if (page.hasMore() && (page.rows().isEmpty() || next == null))
                throw new IllegalStateException("다음 페이지 커서가 없는 민원 응답입니다.");
            buffer = page.rows().iterator();
            progress.fetched(page.rows().size());
            cursor = next;
            hasMore = page.hasMore();
        }
        if (!buffer.hasNext() && hasMore) {
            throw new IllegalStateException("민원 페이지 상한(" + MAX_PAGES + ")에 닿았습니다 — 커서가 전진하는지 원천을 확인하세요.");
        }
        return buffer.hasNext() ? buffer.next() : null;
    }
    public SourceLoadRange range() { return range; }
}
