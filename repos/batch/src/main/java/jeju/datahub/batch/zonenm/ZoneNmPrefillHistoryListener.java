package jeju.datahub.batch.zonenm;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.history.BatchRunHistoryRecorder;
import jeju.datahub.batch.history.BatchRunMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * {@code zoneNmPrefillJob} 실행 1회를 {@code batch.tb_btch_excn_hstry} 에 남기고 크기별 요약을 로그로 뱉는다.
 *
 * <pre>
 * [zoneNmPrefillJob] status=COMPLETED 1000m{호출=7 저장=610 일시실패=13 남음=0 차단=0 DONE} 500m{…}
 * </pre>
 *
 * <h2>지표 기록 방식 — 공용 {@code history/} 를 고치지 않는다</h2>
 * {@code BatchRunMetrics} 에 크기별 칸이 없고, 이 작업은 공용 이력 스키마를 늘리지 않는다. 그래서
 * <ul>
 *   <li>{@code read_cnt} = 시도한 셀(저장+일시실패) · {@code write_cnt} = 저장 — Tasklet 이
 *       {@code StepContribution} 에 올린 값을 Recorder 가 그대로 읽는다</li>
 *   <li>{@code filtered_transient} = 일시실패 합 — 기존 칸의 뜻("이번 실행에서만 제외, 다음 실행이 다시
 *       집어 온다")과 정확히 같다. 나머지 지오코딩 칸은 {@code null}(이 잡은 VWorld 를 직접 부르지 않는다 —
 *       0 을 채우면 "했는데 0건" 이라는 가짜 관측치가 된다)</li>
 *   <li>크기별 내역(호출·저장·일시실패·남음·차단·결과)은 위 요약 로그 + Step {@code ExecutionContext}
 *       ({@code batch.BATCH_STEP_EXECUTION_CONTEXT} 의 {@code zoneNm.{크기}.*})에 남는다</li>
 * </ul>
 */
@Component
public class ZoneNmPrefillHistoryListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ZoneNmPrefillHistoryListener.class);

    private final BatchRunHistoryRecorder historyRecorder;

    public ZoneNmPrefillHistoryListener(BatchRunHistoryRecorder historyRecorder) {
        this.historyRecorder = historyRecorder;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("[{}] 시작 — jobExecutionId={} params={}",
                jobExecution.getJobInstance().getJobName(), jobExecution.getId(),
                jobExecution.getJobParameters());
    }

    /** 실패한 Job 에서도 호출된다. */
    @Override
    public void afterJob(JobExecution jobExecution) {
        long transientSum = 0;
        List<String> parts = new ArrayList<>();
        for (StepExecution step : jobExecution.getStepExecutions()) {
            ExecutionContext ctx = step.getExecutionContext();
            for (Integer size : ZoneNmPrefillTasklet.ALL_GRID_SIZES) {
                String p = ZoneNmPrefillTasklet.CTX_PREFIX + size + ".";
                if (!ctx.containsKey(p + "calls")) {
                    continue;
                }
                long trans = ctx.getLong(p + "transient", 0L);
                transientSum += trans;
                parts.add(size + "m{호출=" + ctx.getLong(p + "calls", 0L)
                        + " 저장=" + ctx.getLong(p + "resolved", 0L)
                        + " 일시실패=" + trans
                        + " 남음=" + ctx.getLong(p + "remaining", -1L)
                        + " 차단=" + ctx.getLong(p + "tripped", 0L)
                        + " " + ctx.getString(p + "outcome", "INCOMPLETE") + "}");
            }
        }

        log.info("[{}] status={} {}", jobExecution.getJobInstance().getJobName(),
                jobExecution.getStatus(), parts.isEmpty() ? "(호출 없음)" : String.join(" ", parts));

        BatchRunMetrics metrics = new BatchRunMetrics(
                null, null, transientSum, null, null, null, null, null, null, null, null, null);
        // ⚠️ try/catch 는 트랜잭션 경계 바깥인 여기 — 이력 기록 실패가 Job 결과를 바꾸면 안 된다.
        try {
            historyRecorder.record(jobExecution, metrics);
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3 — 기록(JDBC upsert · REQUIRES_NEW 트랜잭션 · 값 가공)에서 날 수 있는 예외만 잡는다.
            // 그 밖의 예외는 Spring Batch AbstractJob 이 afterJob 예외를 잡아 ERROR 로 남기므로 Job 결과는 바뀌지 않는다.
            log.warn("배치 실행 이력 기록 실패 — jobExecutionId={} (Job 결과에는 영향 없음). "
                    + "종료 요약은 위 로그에 남아 있습니다.", jobExecution.getId(), e);
        }
    }
}
