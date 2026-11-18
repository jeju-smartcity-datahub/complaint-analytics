package jeju.datahub.report.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * AI 보고서 스냅샷 — {@code GET /api/reports/{rptpId}/ai} 응답.
 *
 * <p>{@code html} 은 저장 시 새니타이즈된 값을 읽을 때 한 번 더 새니타이즈한 것이다.
 */
@Schema(description = "AI 보고서 스냅샷")
@Getter
@ToString(exclude = "html")
@NoArgsConstructor
@AllArgsConstructor
public class AiReportSnapshotVO {

    @Schema(description = "보고서 ID", example = "123")
    private Long rptpId;

    @Schema(description = "보고서명", example = "제주특별자치도 주차 민원 분석 보고서")
    private String rptpNm;

    @Schema(description = "보고서 HTML 본문 (새니타이즈됨)")
    private String html;
}
