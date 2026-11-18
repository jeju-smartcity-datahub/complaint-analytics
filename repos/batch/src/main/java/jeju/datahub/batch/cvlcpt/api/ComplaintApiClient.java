package jeju.datahub.batch.cvlcpt.api;

import jeju.datahub.batch.collect.SourceLoadRange;

/** 민원 수집 포트. real/mock 모두 원천 load_dt와 복합 커서 규약을 지킨다. */
public interface ComplaintApiClient {
    ComplaintPage fetch(SourceLoadRange range, String cursor, int size);

    /** 명시 일자 구간도 원천 load_dt 기준이다. */
    default ComplaintPage fetch(DateRange range, String cursor, int size) {
        return fetch(new SourceLoadRange(range.fromInclusive().atStartOfDay(),
                range.toExclusive().atStartOfDay()), cursor, size);
    }
}
