package jeju.datahub.batch.web;

import java.time.LocalDateTime;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;

/**
 * 수동 트리거 응답 — Job 실행 1회의 요약.
 *
 * <h2>🔴 {@link JobExecution} 을 그대로 반환하면 안 된다</h2>
 * {@code JobExecution} 은 {@code JobInstance}·{@code ExecutionContext}·{@code StepExecution} 을
 * 서로 참조하는 객체 그래프다. Jackson 이 이걸 직렬화하면 깊이가 끝없이 늘어
 * <b>{@code Document nesting depth (1001) exceeds the maximum allowed (1000)}</b> 로 응답이
 * 깨진다(2026-08-24 {@code /api/batch/pblpklt-fetch} 실측 — Job 은 정상 종료했는데 응답만 깨졌다).
 *
 * <p>그래서 트리거는 <b>전부 이 record 로만</b> 응답한다. 종전에는 컨트롤러마다 같은 record 를
 * 중첩 클래스로 복사해 뒀고 그 사이에 하나가 빠져 위 사고가 났다 — 한곳에 모아 그 틈을 없앤다.
 *
 * <p>수치는 종료 요약 로그와 같은 값이다.
 *
 * @param read     Reader 가 흘려보낸 행 수
 * @param written  Writer 가 적재한 행 수
 * @param skipped  실패 확정으로 건너뛴 행 수. <b>정상 경로다</b> — 사유별 내역은
 *                 {@code batch.v_geocode_skip_diagnosis} 로 본다
 * @param filtered 일시 장애로 <b>이번 실행에서만</b> 제외된 행 수 — 다음 실행에 다시 들어온다
 */
public record JobExecutionSummary(
        Long jobExecutionId,
        String status,
        String exitCode,
        long read,
        long written,
        long skipped,
        long filtered,
        LocalDateTime startTime,
        LocalDateTime endTime) {

    public static JobExecutionSummary of(JobExecution execution) {
        long read = 0;
        long written = 0;
        long skipped = 0;
        long filtered = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            read += step.getReadCount();
            written += step.getWriteCount();
            skipped += step.getSkipCount();
            filtered += step.getFilterCount();
        }
        return new JobExecutionSummary(
                execution.getId(),
                execution.getStatus().name(),
                execution.getExitStatus().getExitCode(),
                read, written, skipped, filtered,
                execution.getStartTime(),
                execution.getEndTime());
    }
}
