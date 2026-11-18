package jeju.datahub.batch.cvlcpt.api;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import jeju.datahub.batch.collect.SourceLoadQuery;
import jeju.datahub.batch.collect.SourceLoadRange;

/** 민원 원천 load_dt + 행 해시 페이징. 원천 cvlcpt_id는 NULL/중복이어도 수집한다. */
@Component
@ConditionalOnProperty(name = "complaint.api.mode", havingValue = "real", matchIfMissing = true)
public class DhubComplaintApiClient implements ComplaintApiClient {
    static final String DATE_COLUMN = "load_dt";
    static final KeysetOrder KEYSET_ORDER = KeysetOrder.LEXICOGRAPHIC;
    private final DhubQueryClient queryClient;
    private final DhubProperties properties;
    public DhubComplaintApiClient(DhubQueryClient queryClient, DhubProperties properties) {
        this.queryClient = queryClient;
        this.properties = properties;
    }
    @Override public ComplaintPage fetch(SourceLoadRange range, String cursor, int size) {
        DhubKeysetPage page = new SourceLoadQuery(properties.requireSourceTable(), ComplaintOriginRow.API_COLUMNS, range)
                .fetch(queryClient, properties.getTableId(), cursor, size);
        List<ComplaintOriginRow> rows = new ArrayList<>();
        page.rows().forEach(node -> rows.add(ComplaintOriginRow.fromApi(node)));
        return new ComplaintPage(rows, page.hasMore(), page.nextCursor());
    }
    /** 명시 일자 구간도 원천 load_dt로 조회한다. */
    @Override public ComplaintPage fetch(DateRange range, String cursor, int size) {
        return fetch(new SourceLoadRange(range.fromInclusive().atStartOfDay(), range.toExclusive().atStartOfDay()), cursor, size);
    }
    static String buildSql(String table, DateRange range, String cursor, int size) {
        return new SourceLoadQuery(table, ComplaintOriginRow.API_COLUMNS,
                new SourceLoadRange(range.fromInclusive().atStartOfDay(), range.toExclusive().atStartOfDay())).pageSql(cursor, size);
    }
    public long count(DateRange range) {
        return queryClient.count("SELECT count(*) AS cnt FROM " + DhubSql.table(properties.requireSourceTable())
                + " WHERE " + new SourceLoadRange(range.fromInclusive().atStartOfDay(), range.toExclusive().atStartOfDay()).filter());
    }
}
