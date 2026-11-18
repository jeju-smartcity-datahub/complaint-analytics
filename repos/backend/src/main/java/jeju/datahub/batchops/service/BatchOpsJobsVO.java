package jeju.datahub.batchops.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * {@code GET /api/ops/batch/jobs} 응답 — 🔴 배열이 아니라 객체 {@code {catalogAvailable, jobs}} 다(TSK-4337 Step 6).
 *
 * <p>{@code catalogAvailable=false} 면 {@code jobs} 는 DB({@code batch.batch_job_instance})에 실행 기록이 있는 잡
 * 이름만으로 그린 것이다 — 한글명·파라미터·Step 명세가 비고, 한 번도 안 돈 잡은 행이 없다.
 * {@code true} 면 카탈로그 9개 기준이다(Step 7 — 한 번도 안 돈 잡 포함 · 카탈로그 밖 DB 잡 제외).
 */
@Schema(description = "배치 잡 목록 (카탈로그 병합 여부 + 잡별 요약)")
@Getter
@ToString
@RequiredArgsConstructor
public class BatchOpsJobsVO {

    /** 배치 카탈로그를 받아 합쳤는가. false 면 DB 잡 이름만으로 그린 목록이다 */
    @Schema(description = "배치 카탈로그 병합 여부 — false 면 DB 잡 이름만으로 그린 목록")
    private final boolean catalogAvailable;

    /** 잡별 요약 */
    @Schema(description = "잡별 요약")
    private final List<BatchOpsJobVO> jobs;
}
