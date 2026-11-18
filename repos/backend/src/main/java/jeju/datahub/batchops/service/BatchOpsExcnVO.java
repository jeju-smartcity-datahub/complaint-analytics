package jeju.datahub.batchops.service;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 실행 1건의 요약 — 목록 행 · 잡별 마지막 실행.
 *
 * <p>기준은 {@code batch.batch_job_execution} 이고 실행 이력 {@code batch.tb_btch_excn_hstry} 는 LEFT JOIN 이다 —
 * 이력 기록이 실패한 실행도 보이게 한다({@code hstryYn='N'} 이면 이력 칸이 비어 있다).
 *
 * <p>🔒 {@code exitMessage}·{@code failureMessage} 는 마스킹 후 <b>목록에서는 500자로 자른다</b>(상세는 전체).
 */
@Schema(description = "배치 실행 요약")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BatchOpsExcnVO {

    /** 실행 ID (batch_job_execution.job_execution_id) */
    @Schema(description = "실행 ID", example = "4521")
    private Long jobExecutionId;

    /** Job 이름 */
    @Schema(description = "Job 이름", example = "complaintFetchJob")
    private String jobNm;

    /** Spring Batch 실행 상태 (STARTING·STARTED·STOPPING·STOPPED·COMPLETED·FAILED·ABANDONED·UNKNOWN) */
    @Schema(description = "실행 상태 (Spring Batch BatchStatus)", example = "COMPLETED")
    private String status;

    /** 종료 코드 */
    @Schema(description = "종료 코드 (ExitStatus)", example = "COMPLETED")
    private String exitCode;

    /** 종료 메시지 — 마스킹, 목록은 500자 절단 */
    @Schema(description = "종료 메시지 (마스킹 · 목록은 500자 절단)")
    private String exitMessage;

    /** 생성 시각 */
    @Schema(description = "실행 생성 시각")
    private LocalDateTime createTime;

    /** 시작 시각 */
    @Schema(description = "시작 시각")
    private LocalDateTime startTime;

    /** 종료 시각 (실행 중이면 null) */
    @Schema(description = "종료 시각 (실행 중이면 null)")
    private LocalDateTime endTime;

    /** 마지막 갱신 시각 */
    @Schema(description = "메타 마지막 갱신 시각")
    private LocalDateTime lastUpdated;

    /** 소요(ms) — 이력값, 없으면 메타의 종료-시작 */
    @Schema(description = "소요 ms (이력값 · 없으면 종료-시작)")
    private Long durationMs;

    /** 이력 행이 있는가 (Y/N) */
    @Schema(description = "실행 이력 행 존재 여부 (N 이면 이력 기록 실패 — 이력 칸이 비어 있다)", example = "Y")
    private String hstryYn;

    /** 읽기 건수 (이력) */
    @Schema(description = "읽기 건수 (이력)")
    private Long readCnt;

    /** 처리 건수 (이력) — 잡마다 의미가 다르다(카탈로그 countLabel) */
    @Schema(description = "처리 건수 (이력 write_cnt — 의미는 카탈로그 countLabel)")
    private Long writeCnt;

    /** 실행구분 (SCHDL·MANUAL·CHAIN·API, 도입 전 실행은 null) */
    @Schema(description = "실행구분 (SCHDL/MANUAL/CHAIN/API · 도입 전 실행은 null)", example = "SCHDL")
    private String excnSeCd;

    /** 실행자 (수동 실행만) */
    @Schema(description = "실행자 (수동 실행만)")
    private String excutrId;

    /** 상위(앞 잡) 실행 ID (연쇄 실행만) */
    @Schema(description = "상위(앞 잡) 실행 ID (연쇄 실행만)")
    private Long upExcnId;

    /** 실행 방식 (이력 run_mode — 민원 daily/backfill 등) */
    @Schema(description = "실행 방식 (이력 run_mode)")
    private String runMode;

    /** 대상 구간 (이력 target_range) */
    @Schema(description = "대상 구간 (이력 target_range)")
    private String targetRange;

    /** 실패 메시지 (이력) — 마스킹, 목록은 500자 절단 */
    @Schema(description = "실패 메시지 (이력 · 마스킹 · 목록은 500자 절단)")
    private String failureMessage;

    /**
     * 하위 결과 — 이 실행 아래에 붙는 행. 지금은 {@code up_excn_id} 로 이어진 연쇄 실행({@code subSeCd='CHAIN'})뿐이다.
     * 🔴 연쇄 잡만 전제로 하지 않는다 — 후속(민원 유입경로별 결과)도 같은 모양으로 붙인다.
     */
    @Schema(description = "하위 결과 (지금은 연쇄 실행 CHAIN 뿐 · 없으면 빈 배열)")
    private List<BatchOpsSubRsltVO> subRslts = List.of();
}
