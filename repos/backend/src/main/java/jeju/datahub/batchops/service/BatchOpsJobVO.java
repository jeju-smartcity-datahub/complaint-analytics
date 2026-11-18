package jeju.datahub.batchops.service;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 잡 1개의 요약 — 마지막 실행 · 마지막 성공 시각 · 스케줄.
 *
 * <p>DB 에서 채우는 칸: {@code jobNm}·{@code lastExcn}·{@code lastSuccessDt}·{@code lastSuccessExcnId}·{@code schedule}.
 * <br>🔴 <b>카탈로그 칸</b>({@code jobKorNm}·{@code description}·{@code countLabel}·{@code schedulable}·{@code chainNext}·
 * {@code chainCondition}·{@code runningJobExecutionIds}·{@code params}·{@code steps})은 배치 {@code GET /api/batch/ops/jobs} 의 같은 이름 필드를
 * 병합이 옮겨 담는 자리다 — 잡 명세를 백엔드에 두 벌로 두지 않는다. 카탈로그를 못 받으면 {@code null} 이다.
 */
@Schema(description = "배치 잡 요약")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BatchOpsJobVO {

    /** Spring Batch Job 이름 (batch_job_instance.job_name) */
    @Schema(description = "Job 이름", example = "complaintFetchJob")
    private String jobNm;

    // ---------------------------------------------------------------- 카탈로그 칸 (병합 — 못 받으면 null)

    /** 한글명 (카탈로그) */
    @Schema(description = "한글명 (카탈로그 — 못 받으면 null)")
    private String jobKorNm;

    /** 설명 (카탈로그) */
    @Schema(description = "설명 (카탈로그 — 못 받으면 null)")
    private String description;

    /** 처리 건수 문구 — {@code writeCnt} 의 의미 (카탈로그). 잡마다 다르다(적재 행·생성 보고서·알림) */
    @Schema(description = "처리 건수(writeCnt) 문구 (카탈로그 — 못 받으면 null)")
    private String countLabel;

    /** 스케줄 대상 잡인가 (카탈로그) */
    @Schema(description = "스케줄 대상 여부 (카탈로그 — 못 받으면 null)")
    private Boolean schedulable;

    /** 이어서 도는 잡 이름 (카탈로그) */
    @Schema(description = "연쇄로 이어서 도는 잡 (카탈로그 — 못 받으면 null)")
    private String chainNext;

    /** 연쇄 조건 문구 (카탈로그) */
    @Schema(description = "연쇄 조건 문구 (카탈로그 — 못 받으면 null)")
    private String chainCondition;

    /** 배치 프로세스가 지금 돌리고 있는 실행 ID (카탈로그 — DB 의 STARTED 는 좀비일 수 있어 근거로 쓰지 않는다) */
    @Schema(description = "배치 프로세스에서 실행 중인 실행 ID (카탈로그 — 못 받으면 null)")
    private List<Long> runningJobExecutionIds;

    /** 파라미터 명세 (카탈로그) — 실행 모달 폼의 근거 */
    @Schema(description = "파라미터 명세 (카탈로그 — 못 받으면 null)")
    private List<BatchOpsParamSpecVO> params;

    /** Step 명세 (카탈로그 — 순서 = 실행 순서) — 진행 단계 n / 전체 의 근거 */
    @Schema(description = "Step 명세 {stepNm, stepKorNm} (카탈로그 — 못 받으면 null)")
    private List<BatchOpsStepSpecVO> steps;

    // ---------------------------------------------------------------- DB 칸

    /** 마지막 실행 (job_execution_id 최대) */
    @Schema(description = "마지막 실행 (실행 기록이 없으면 null)")
    private BatchOpsExcnVO lastExcn;

    /** 마지막 성공(COMPLETED) 실행의 종료 시각 */
    @Schema(description = "마지막 성공(COMPLETED) 종료 시각")
    private LocalDateTime lastSuccessDt;

    /** 마지막 성공(COMPLETED) 실행 ID */
    @Schema(description = "마지막 성공(COMPLETED) 실행 ID")
    private Long lastSuccessExcnId;

    /** 스케줄 (batch.tb_btch_schdl — 행이 없으면 null) */
    @Schema(description = "스케줄 (스케줄 표에 행이 없으면 null)")
    private BatchOpsSchdlVO schedule;
}
