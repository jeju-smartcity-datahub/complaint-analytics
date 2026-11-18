package jeju.datahub.batch.crackdown.api;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import jeju.datahub.batch.collect.SourceLoadQuery;
import jeju.datahub.batch.collect.SourceLoadRange;
import jeju.datahub.batch.cvlcpt.api.*;

/** 단속 원천 PK는 메타데이터로만 받는다. 페이징은 load_dt + 행 해시다. */
@Component
public class DhubCrackdownApiClient {
    static final List<String> SELECT_COLUMNS;
    static {
        List<String> columns = new ArrayList<>(CrackdownOriginRow.SOURCE_COLUMNS);
        columns.add("crdn_id");
        columns.add("load_dt");
        SELECT_COLUMNS = List.copyOf(columns);
    }
    private final DhubQueryClient queryClient;
    private final DhubProperties properties;
    public DhubCrackdownApiClient(DhubQueryClient queryClient, DhubProperties properties) {
        this.queryClient = queryClient;
        this.properties = properties;
    }
    public DhubKeysetPage fetchPage(SourceLoadRange range, String cursor, int size) {
        return new SourceLoadQuery(properties.requireCrackdownSourceTable(), SELECT_COLUMNS, range)
                .fetch(queryClient, properties.getCrackdownTableId(), cursor, size);
    }
}
