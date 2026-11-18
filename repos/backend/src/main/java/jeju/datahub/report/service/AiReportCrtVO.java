package jeju.datahub.report.service;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * AI 보고서 등록 결과 (TSK-4293 Step 5). 201(새로 만듦) · 200(이미 있음 — D12 멱등) 공용.
 *
 * <p>{@code created} 는 상태코드를 고르는 데만 쓰고 응답 JSON 에 싣지 않는다.
 */
@Schema(description = "AI 보고서 등록 결과")
@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class AiReportCrtVO {

    @Schema(description = "보고서 ID", example = "123")
    private Long rptpId;

    @Schema(description = "보고서명 — 보고서 영역의 title", example = "제주특별자치도 주차 민원 분석 보고서")
    private String rptpNm;

    /** 이번 요청이 새로 만들었는가 — 201/200 판정용(응답에 싣지 않는다) */
    @JsonIgnore
    private boolean created;
}
