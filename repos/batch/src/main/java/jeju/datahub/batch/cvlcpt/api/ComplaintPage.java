package jeju.datahub.batch.cvlcpt.api;

import java.util.List;

/** 원천 load_dt + 조회 행 해시로 이동하는 민원 한 페이지. 커서에 원천 PK를 사용하지 않는다. */
public record ComplaintPage(List<ComplaintOriginRow> rows, boolean hasMore, String nextCursor) {
    public ComplaintPage {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
    public ComplaintPage(List<ComplaintOriginRow> rows, boolean hasMore) {
        this(rows, hasMore, null);
    }
    public static ComplaintPage empty() { return new ComplaintPage(List.of(), false, null); }
}
