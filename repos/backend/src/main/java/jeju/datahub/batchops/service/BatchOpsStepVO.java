package jeju.datahub.batchops.service;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Step 실행 1건 — {@code batch.batch_step_execution} (TSK-4337 Step 6).
 *
 * <p>카운트는 Spring Batch 가 <b>청크 커밋마다</b> 갱신한다 — 실행 중 진행 수의 근거다.
 * {@code stepKorNm} 은 배치 카탈로그 {@code steps[].stepKorNm} 을 Step 7 병합이 채운다(못 받으면 null).
 */
@Schema(description = "Step 실행 카운트")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BatchOpsStepVO {

    @Schema(description = "Step 실행 ID")
    private Long stepExecutionId;

    @Schema(description = "Step 이름", example = "complaintFetchStep")
    private String stepName;

    @Schema(description = "Step 한글명 (카탈로그 — 못 받으면 null)")
    private String stepKorNm;

    @Schema(description = "상태", example = "COMPLETED")
    private String status;

    @Schema(description = "시작 시각")
    private LocalDateTime startTime;

    @Schema(description = "종료 시각")
    private LocalDateTime endTime;

    @Schema(description = "읽기 수")
    private Long readCount;

    @Schema(description = "쓰기 수")
    private Long writeCount;

    @Schema(description = "필터 수 (Processor 가 null 반환)")
    private Long filterCount;

    @Schema(description = "읽기 스킵 수")
    private Long readSkipCount;

    @Schema(description = "쓰기 스킵 수")
    private Long writeSkipCount;

    @Schema(description = "처리 스킵 수")
    private Long processSkipCount;

    @Schema(description = "커밋 수")
    private Long commitCount;

    @Schema(description = "롤백 수")
    private Long rollbackCount;

    @Schema(description = "종료 코드")
    private String exitCode;

    @Schema(description = "종료 메시지 (마스킹 · 전체)")
    private String exitMessage;
}
