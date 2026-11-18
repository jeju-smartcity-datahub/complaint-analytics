package jeju.datahub.batchops.service;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 실행 아래에 붙는 <b>하위 결과 1행</b> (화면의 실행 행 서랍).
 *
 * <p>🔴 <b>연쇄 잡 전용 모양이 아니다</b> — 민원 유입경로별 3회 수집의 경로별 결과도
 * 같은 행으로 붙인다. 그래서 잡 이름 대신 일반화한 {@code subSeCd}(하위 구분)·{@code subNm}(하위 이름)을 두고,
 * 실행 ID 는 연쇄일 때만 있는 선택 칸이다.
 * <ul>
 *   <li>{@code CHAIN} — 이력 {@code up_excn_id} 로 이어진 연쇄 실행. {@code subNm} = 그 잡 이름.
 *       🔴 {@code up_excn_id} 가 NULL 인 과거 연쇄 실행은 묶지 않는다(시각 근접으로 추정하지 않는다)</li>
 * </ul>
 */
@Schema(description = "실행 하위 결과 (연쇄 실행 · 후속 경로별 결과 공용 모양)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BatchOpsSubRsltVO {

    /** 하위 구분 — 지금은 CHAIN(연쇄 실행)뿐 */
    @Schema(description = "하위 구분 (CHAIN = 연쇄 실행)", example = "CHAIN")
    private String subSeCd;

    /** 하위 이름 — CHAIN 이면 잡 이름 */
    @Schema(description = "하위 이름 (CHAIN 이면 Job 이름)", example = "riskAlertJudgeJob")
    private String subNm;

    /** 하위 실행 ID (CHAIN 이면 있다) */
    @Schema(description = "하위 실행 ID (없을 수 있다)")
    private Long jobExecutionId;

    /** 상태 */
    @Schema(description = "상태", example = "COMPLETED")
    private String status;

    /** 시작 시각 */
    @Schema(description = "시작 시각")
    private LocalDateTime startTime;

    /** 종료 시각 */
    @Schema(description = "종료 시각")
    private LocalDateTime endTime;

    /** 소요 ms */
    @Schema(description = "소요 ms")
    private Long durationMs;

    /** 읽기 건수 */
    @Schema(description = "읽기 건수")
    private Long readCnt;

    /** 처리 건수 */
    @Schema(description = "처리 건수")
    private Long writeCnt;

    /** 메시지 — 실패 메시지, 없으면 종료 메시지 (마스킹 · 500자 절단) */
    @Schema(description = "메시지 (실패 메시지 → 없으면 종료 메시지 · 마스킹 · 500자 절단)")
    private String message;

    /** 묶음 키 — 어느 상위 실행에 붙는가 (응답에는 싣지 않는다 — 상위 행 안에 들어 있다) */
    @JsonIgnore
    private Long upExcnId;
}
