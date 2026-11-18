package jeju.datahub.batch.utztn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.history.BatchRunHistoryRecorder;
import jeju.datahub.batch.history.BatchRunMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * {@code pkltUtztnFetchJob} 실행 1회를 {@code batch.tb_btch_excn_hstry} 에 남긴다.
 *
 * <p>🔴 <b>지표는 빈 값이다</b>({@link BatchRunMetrics#none()}) — 이 잡은 지오코딩·행정동 매칭·유형 분류를
 * 하지 않는다. 이력 테이블에 컬럼을 새로 추가하지 않고, 해당 지표를 NULL 로 둔다.
 * 스킵·매칭 내역은 감사 표 {@code batch.tb_pklt_utztn_mtch} 가 훨씬 자세히 진다.
 *
 * <p>🔴 <b>try/catch 는 트랜잭션 경계 바깥인 여기다</b> — 이력 기록 실패가 배치를 죽이면 안 된다.
 * 기록 자체는 {@code REQUIRES_NEW} 라 FAILED 로 끝난 실행도 남는다(그래야 감사 기록이 된다).
 */
@Component
public class PkltUtztnFetchHistoryListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(PkltUtztnFetchHistoryListener.class);

    private final BatchRunHistoryRecorder historyRecorder;

    public PkltUtztnFetchHistoryListener(BatchRunHistoryRecorder historyRecorder) {
        this.historyRecorder = historyRecorder;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        try {
            historyRecorder.record(jobExecution, BatchRunMetrics.none());
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3 — 기록(JDBC upsert · REQUIRES_NEW 트랜잭션 · 값 가공)에서 날 수 있는 예외만 잡는다.
            // 그 밖의 예외는 Spring Batch AbstractJob 이 afterJob 예외를 잡아 ERROR 로 남기므로 Job 결과는 바뀌지 않는다.
            log.warn("pkltUtztnFetchJob 실행 이력 기록 실패 — 배치는 계속한다: {}", e.getClass().getSimpleName());
            log.debug("pkltUtztnFetchJob 실행 이력 기록 실패 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만
        }
    }
}
