package jeju.datahub.batch.acdnt.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import jeju.datahub.batch.acdnt.AccidentProperties;
import jeju.datahub.batch.acdnt.AccidentRange;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Step마다 사고일시 구간을 한 번 고정한다. 실패 후에는 DB의 커밋된 마지막 사고일시부터 재수집한다. */
public class DhubPkltAcdntItemReader implements ItemStreamReader<PkltAcdntOriginRow> {
    private static final Logger log = LoggerFactory.getLogger(DhubPkltAcdntItemReader.class);
    private final DhubPkltAcdntApiClient client;
    private final DhubProperties common;
    private final AccidentProperties properties;
    private final Supplier<AccidentRange> rangeSupplier;
    private final Deque<PkltAcdntOriginRow> buffer = new ArrayDeque<>();
    private AccidentRange range;
    private DhubPkltAcdntApiClient.Cursor cursor;
    private boolean opened;
    private boolean hasMore;
    private int pages;
    /** 페이지 루프 상한(시큐어코딩 3.2). 반복 조건에 직접 넣어 무한 루프를 막는다(페이지 1,000행 기준 1,000만 행). */
    static final int MAX_PAGES = 10_000;
    private int pageSize;
    public DhubPkltAcdntItemReader(DhubPkltAcdntApiClient client, DhubProperties common,
            AccidentProperties properties, Supplier<AccidentRange> rangeSupplier) {
        this.client = client;
        this.common = common;
        this.properties = properties;
        this.rangeSupplier = rangeSupplier;
    }
    @Override public void open(ExecutionContext context) {
        if (opened) return;
        common.requireCredentials();
        properties.validate();
        range = rangeSupplier.get();
        pageSize = properties.getPageSize();
        buffer.clear();
        cursor = null;
        hasMore = true;
        pages = 0;
        opened = true;
        log.info("사고이력 수집 시작 — acdnt_dt [{} 이상, {} 미만), 페이지 {}행",
                range.fromInclusive() == null ? "하한 없음" : range.fromInclusive(), range.toExclusive(), pageSize);
    }
    @Override public PkltAcdntOriginRow read() {
        if (!opened) open(new ExecutionContext());
        while (buffer.isEmpty() && hasMore && pages < MAX_PAGES) {
            var page = client.fetch(range, cursor, pageSize);
            pages++;
            if (page.hasMore() && (page.rows().isEmpty() || page.next() == null))
                throw new IllegalStateException("사고이력 다음 페이지 커서가 없습니다.");
            if (cursor != null && page.next() != null && page.next().compareTo(cursor) <= 0)
                throw new IllegalStateException("사고이력 커서가 전진하지 않습니다.");
            buffer.addAll(page.rows());
            cursor = page.next();
            hasMore = page.hasMore();
        }
        if (buffer.isEmpty() && hasMore) {
            throw new IllegalStateException("사고이력 페이지 상한(" + MAX_PAGES + ")에 닿았습니다 — 커서가 전진하는지 원천을 확인하세요.");
        }
        return buffer.poll();
    }
    @Override public void update(ExecutionContext context) { }
    @Override public void close() { buffer.clear(); cursor = null; opened = false; }
}
