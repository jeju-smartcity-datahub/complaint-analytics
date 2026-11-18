package jeju.datahub.batch.cvlcpt.api;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * {@link DhubKeysetQuery#fetchPage} 한 번의 결과 — <b>한 페이지</b>다
 * (🔁 2026-09-17).
 *
 * <p>민원 {@link ComplaintPage} 와 같은 모양({@code hasMore()}·{@code nextCursor()})이라 Reader 루프가
 * 그대로 옮겨진다. 다른 점은 행이 도메인 record 가 아니라 원문 {@link ArrayNode} 라는 것뿐 —
 * 매핑은 잡마다 다르므로 호출부가 한다.
 *
 * @param rows       이 페이지의 행({@code ORDER BY 키} 오름차순)
 * @param hasMore    {@code rows.size() == pageSize}. 마지막 페이지가 정확히 가득 차면 빈 페이지 1회를
 *                   더 받는다 — 누락보다 안전하고, 스파이크가 정상으로 확인한 동작이다
 * @param nextCursor 마지막 행의 키({@code asText()}). 빈 페이지면 {@code null}
 */
public record DhubKeysetPage(ArrayNode rows, boolean hasMore, String nextCursor) {
}
