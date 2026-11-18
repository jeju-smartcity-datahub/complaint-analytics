package jeju.datahub.analysis.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석유형 1건의 설정값 <b>전체 교체</b> 저장 요청 바디.
 * (PUT /api/analysis-weights/{anlsTypeCd})
 *
 * <p>메뉴 권한 저장(TSK-4019)과 달리 <b>빈 배열을 허용하지 않는다</b> — 변수가 하나도 없는
 * 분석유형은 종합점수를 낼 수 없으므로 {@code @Size(min = 1)} 로 막는다.
 * 컨트롤러에서 {@code @Valid} 를 빠뜨리면 이 검증이 발동하지 않는다.
 *
 * <p>저장 경로는 재정규화를 거치지 않고 <b>원값 그대로</b> 저장한다 (TSK-4020 D10).
 */
@Schema(description = "분석유형 1건의 설정값 전체 교체 저장 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AnalysisWeightSaveRequest {

    /** 이 분석유형에 남길 변수·설정값 전체. 서버가 기존 값을 이 목록으로 전체 교체한다. */
    @NotNull(message = "weights 는 필수입니다")
    @Size(min = 1, message = "weights 는 최소 1건이어야 합니다")
    @Valid
    @Schema(description = "이 분석유형에 남길 변수·설정값 전체 (최소 1건, 합계 100)")
    private List<AnalysisWeightItemRequest> weights;

    /** 변경 사유(선택, TSK-4020 D16). 이력({@code chg_rsn})에 그대로 기록된다. */
    @Size(max = 300, message = "chgRsn 은 300자를 넘을 수 없습니다")
    @Schema(description = "변경 사유 (선택) — 이력에 그대로 기록", example = "단속 실적 반영 비중 상향")
    private String chgRsn;
}
