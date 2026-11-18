package jeju.datahub.batch.crackdown.api;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import jeju.datahub.batch.collect.SourceLoadCursor;
import jeju.datahub.batch.collect.SourceLoadRange;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;

/** 단속 load_dt 증분 Reader. 매 실행 구간과 상한을 한 번만 고정한다. */
public class DhubCrackdownItemReader implements ItemStreamReader<CrackdownOriginRow> {
    private final DhubCrackdownApiClient client;
    private final DhubProperties properties;
    private final Supplier<SourceLoadRange> rangeSupplier;
    private final Deque<JsonNode> buffer = new ArrayDeque<>();
    private SourceLoadRange range;
    private String cursor;
    private boolean opened;
    private boolean hasMore;
    private int pages;
    /** 페이지 루프 상한(시큐어코딩 3.2). 반복 조건에 직접 넣어 무한 루프를 막는다(페이지 1,000행 기준 1,000만 행). */
    static final int MAX_PAGES = 10_000;
    private DateTimeFormatter dateFormat;
    private int pageSize;
    public DhubCrackdownItemReader(DhubCrackdownApiClient client, DhubProperties properties) {
        this(client, properties, () -> new SourceLoadRange(null,
                properties.resolveToday(java.time.Clock.systemUTC()).plusDays(1).atStartOfDay()));
    }
    public DhubCrackdownItemReader(DhubCrackdownApiClient client, DhubProperties properties,
            Supplier<SourceLoadRange> rangeSupplier) {
        this.client = client;
        this.properties = properties;
        this.rangeSupplier = rangeSupplier;
    }
    @Override public void open(ExecutionContext context) {
        if (opened) return;
        properties.requireCredentials();
        pageSize = properties.requireCrackdownPageSize();
        dateFormat = DateTimeFormatter.ofPattern(properties.getCrackdownDatePattern());
        range = rangeSupplier.get();
        buffer.clear();
        cursor = null;
        hasMore = true;
        pages = 0;
        opened = true;
    }
    @Override public CrackdownOriginRow read() {
        if (!opened) open(new ExecutionContext());
        while (buffer.isEmpty() && hasMore && pages < MAX_PAGES) {
            var page = client.fetchPage(range, cursor, pageSize);
            pages++;
            String next = page.nextCursor();
            if (cursor != null && next != null && SourceLoadCursor.decode(next).compareTo(SourceLoadCursor.decode(cursor)) <= 0)
                throw new IllegalStateException("단속 load_dt 커서가 전진하지 않습니다.");
            if (page.hasMore() && (page.rows().isEmpty() || next == null))
                throw new IllegalStateException("다음 페이지 커서가 없는 단속 응답입니다.");
            page.rows().forEach(buffer::add);
            cursor = next;
            hasMore = page.hasMore();
        }
        if (buffer.isEmpty() && hasMore) {
            throw new IllegalStateException("단속 페이지 상한(" + MAX_PAGES + ")에 닿았습니다 — 커서가 전진하는지 원천을 확인하세요.");
        }
        JsonNode row = buffer.poll();
        return row == null ? null : CrackdownOriginRow.fromApi(row, dateFormat, properties.getCrackdownDatePattern());
    }
    @Override public void update(ExecutionContext context) { }
    @Override public void close() { buffer.clear(); opened = false; }
    public SourceLoadRange range() { return range; }
}
