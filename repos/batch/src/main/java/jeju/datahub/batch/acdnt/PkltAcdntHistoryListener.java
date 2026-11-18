package jeju.datahub.batch.acdnt;

import jeju.datahub.batch.history.BatchRunHistoryRecorder;
import jeju.datahub.batch.history.BatchRunMetrics;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class PkltAcdntHistoryListener implements JobExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(PkltAcdntHistoryListener.class);
    private final BatchRunHistoryRecorder recorder;
    public PkltAcdntHistoryListener(BatchRunHistoryRecorder recorder) { this.recorder = recorder; }
    @Override public void afterJob(JobExecution execution) {
        try {
            recorder.record(execution, BatchRunMetrics.none());
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) {
            log.warn("사고이력 실행 이력 기록 실패 — 배치 결과는 유지합니다.", e);
        }
    }
}
