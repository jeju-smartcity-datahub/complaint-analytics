package jeju.datahub.batchops.service;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonRawValue;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 실행 상세 — 요약 칸 + 이력 지표 전체 + 파라미터 + Step 별 카운트 + 진행 단계 (TSK-4337 Step 6).
 *
 * <p>요약 칸({@link BatchOpsExcnVO})을 그대로 물려받는다 — 메시지는 상세에서는 <b>자르지 않는다</b>(마스킹만).
 * 이력 지표는 잡마다 쓰는 칸이 달라 대부분 {@code null} 이다(지표가 없는 잡은 빈 지표로 남는다 — rule_batch-project).
 */
@Schema(description = "배치 실행 상세")
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
public class BatchOpsExcnDetailVO extends BatchOpsExcnVO {

    // ---------------------------------------------------------------- 이력 지표 (batch.tb_btch_excn_hstry)

    @Schema(description = "지오코딩 실패 스킵 수")
    private Long skipGeocodeFailed;

    @Schema(description = "읍면동 미해석 스킵 수")
    private Long skipEmdUnresolved;

    @Schema(description = "일시 실패로 이번 실행에서 제외한 수")
    private Long filteredTransient;

    @Schema(description = "지오코딩 API 호출 수")
    private Long geocodeApiLookups;

    @Schema(description = "지오코딩 캐시 히트 수")
    private Long geocodeCacheHits;

    @Schema(description = "장소검색 폴백 복구 수")
    private Long geocodePlaceRecoveries;

    @Schema(description = "주소 정제 후 재시도 수")
    private Long geocodeStripped;

    @Schema(description = "공간매칭 포함(ST_Contains) 수")
    private Long emdContains;

    @Schema(description = "공간매칭 버퍼(ST_DWithin) 수")
    private Long emdBuffer;

    @Schema(description = "읍면동명 폴백 수")
    private Long emdNameFallback;

    @Schema(description = "미분류 수")
    private Long unclsfCnt;

    /** 미분류 유형별 건수 — jsonb 원문을 JSON 그대로 싣는다 */
    @Schema(description = "미분류 유형별 건수 (JSON 객체 · 없으면 null)")
    @JsonRawValue
    private String unknownTypes;

    // ---------------------------------------------------------------- 메타

    /** 실행 파라미터 (batch_job_execution_params) */
    @Schema(description = "실행 파라미터")
    private List<BatchOpsParamVO> params = List.of();

    /** Step 별 카운트 (batch_step_execution — 실행 순서) */
    @Schema(description = "Step 별 카운트 (실행 순서)")
    private List<BatchOpsStepVO> steps = List.of();

    /** 진행 단계 — 실행 중(STARTING·STARTED·STOPPING)일 때만, 아니면 null */
    @Schema(description = "진행 단계 (실행 중일 때만 · 아니면 null)")
    private BatchOpsProgressVO progress;
}
