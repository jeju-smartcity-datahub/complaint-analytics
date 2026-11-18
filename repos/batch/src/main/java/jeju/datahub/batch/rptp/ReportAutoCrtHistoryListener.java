package jeju.datahub.batch.rptp;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.history.BatchRunHistoryRecorder;
import jeju.datahub.batch.history.BatchRunMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * ④{@code reportAutoCrtJob} 실행 1회를 {@code batch.tb_btch_excn_hstry} 에 남기고
 * 종료 요약 한 줄을 로그로 뱉는다.
 *
 * <pre>
 * [reportAutoCrtJob] status=COMPLETED 읽은설정=12 도래=3 생성=2 이미있음=1 스킵=0 {}
 * </pre>
 *
 * <h2>🔴 Writer 카운터는 {@code afterStep} 에서 건져 둔다 — {@code afterJob} 은 늦다</h2>
 * {@link ReportAutoCrtWriter} 는 {@code @StepScope} 다(Job 파라미터 {@code baseYmd} 를 받아야 해서).
 * 그래서 주입된 것은 <b>스코프 프록시</b>이고, Step 이 끝나 스코프가 닫힌 뒤
 * ({@code afterJob} 시점) 메서드를 부르면 <b>"No context holder available for step scope"</b> 로
 * 터진다 — 그 예외가 이력 기록까지 함께 날린다. 그래서 Step 이 살아 있는 동안
 * ({@link #afterStep(StepExecution)})에 생성/이미있음 건수를 읽어 {@link JobExecution} 의
 * {@code ExecutionContext} 로 옮기고, 요약은 거기서 읽는다.
 *
 * <p>같은 이유로 🔴 <b>{@code beforeJob} 에서 {@code writer.resetStats()} 를 부르지 않는다</b>
 * (아직 Step 스코프가 열리지 않았다). 부를 필요도 없다 — {@code @StepScope} 라 Step 마다 새
 * 인스턴스이고 카운터가 항상 0 에서 시작한다. 반대로 {@link ReportAutoCrtSkipListener} 는
 * {@code @Component} <b>싱글턴</b>이라 카운터가 실행 간에 살아남으므로 {@code beforeJob} 에서
 * 반드시 0 으로 되돌린다 — 같은 JVM 에서 수동 트리거를 두 번 돌리면(재실행 안전성 검증이 정확히
 * 그 경우다) 2회차 요약에 1회차 스킵이 그대로 더해진다(선례 {@code RiskAlertJudgeHistoryListener}).
 *
 * <h2>지표는 {@link BatchRunMetrics#none()} — 전용 컬럼을 만들지 않는다</h2>
 * 이 잡은 지오코딩·분류를 하지 않아 {@code BatchRunMetrics} 의 필드에 채울 값이 하나도 없다.
 * 0 으로 채우면 <b>"했는데 0건"</b> 이라는 가짜 관측치가 추이 집계에 섞인다
 * ({@code RiskAlertJudgeHistoryListener} 와 동일 근거). 남는 값은 {@code job_nm}·{@code status}·
 * {@code read_cnt}·{@code write_cnt}·{@code started_dt}·{@code ended_dt} 이고, 생성/이미있음/스킵
 * 내역은 아래 요약 로그와 {@code batch.tb_job_skip}({@code job_se='RPTP'}) 으로 본다.
 */
@Component
public class ReportAutoCrtHistoryListener implements JobExecutionListener, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ReportAutoCrtHistoryListener.class);

    /** {@code afterStep} → {@code afterJob} 으로 건네는 키(생성 건수). */
    static final String CTX_CREATED = "rptp.createdCount";

    /** {@code afterStep} → {@code afterJob} 으로 건네는 키(이미있음 건수). */
    static final String CTX_ALREADY_EXISTS = "rptp.alreadyExistsCount";

    private final ReportAutoCrtWriter writer;
    private final ReportAutoCrtSkipListener skipListener;
    private final BatchRunHistoryRecorder historyRecorder;

    public ReportAutoCrtHistoryListener(ReportAutoCrtWriter writer,
                                        ReportAutoCrtSkipListener skipListener,
                                        BatchRunHistoryRecorder historyRecorder) {
        this.writer = writer;
        this.skipListener = skipListener;
        this.historyRecorder = historyRecorder;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        // 🔴 싱글턴인 스킵 리스너만 리셋한다 — Writer 는 @StepScope 라 여기서 부르면 터진다(위 Javadoc).
        skipListener.resetStats();
        log.info("[{}] 시작 — jobExecutionId={} params={}",
                jobExecution.getJobInstance().getJobName(), jobExecution.getId(),
                jobExecution.getJobParameters());
    }

    /**
     * Step 스코프가 닫히기 전에 Writer 카운터를 {@link JobExecution} 으로 옮긴다.
     *
     * <p>실패로 끝난 Step 에서도 호출된다 — 거기까지 몇 건을 만들었는지가 요약에 남아야 한다.
     * 반환값 {@code null} 은 "종료 상태를 바꾸지 않는다" 는 뜻이다.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        stepExecution.getJobExecution().getExecutionContext()
                .putLong(CTX_CREATED, writer.createdCount());
        stepExecution.getJobExecution().getExecutionContext()
                .putLong(CTX_ALREADY_EXISTS, writer.alreadyExistsCount());
        return null;
    }

    /** 실패한 Job 에서도 호출된다 — FAILED 야말로 "어디까지 하고 죽었는지"가 남아야 한다. */
    @Override
    public void afterJob(JobExecution jobExecution) {
        long read = 0;
        long written = 0;
        long filtered = 0;
        for (StepExecution step : jobExecution.getStepExecutions()) {
            read += step.getReadCount();
            written += step.getWriteCount();
            filtered += step.getFilterCount();
        }

        // 도래 건수 = 읽은 설정 - 걸러진 설정(배포일이 아니라 Processor 가 null 을 반환한 것).
        long due = read - filtered;
        long created = jobExecution.getExecutionContext().getLong(CTX_CREATED, 0L);
        long alreadyExists = jobExecution.getExecutionContext().getLong(CTX_ALREADY_EXISTS, 0L);
        long skipped = skipListener.totalSkipped();
        Map<String, Long> skipCounts = skipListener.skipCounts();

        // ⚠️ read > written 은 정상이다 — 배포일이 도래하지 않은 설정은 Processor 가 null 을 반환해
        //    걸러지고(filtered), 대부분의 날은 전부 그쪽이다. 스킵과 혼동하지 말 것.
        log.info("[{}] status={} 읽은설정={} 도래={} 생성={} 이미있음={} 스킵={} {} (written={})",
                jobExecution.getJobInstance().getJobName(), jobExecution.getStatus(),
                read, due, created, alreadyExists, skipped, skipCounts, written);

        // ⚠️ try/catch 는 트랜잭션 경계 바깥인 여기 — 이력 기록 실패가 보고서 배치를 죽이면 안 된다.
        //    record() 안에서 잡으면 커밋 시점의 실패를 못 잡는다(선례 RiskAlertJudgeHistoryListener).
        try {
            historyRecorder.record(jobExecution, BatchRunMetrics.none());
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3 — 기록(JDBC upsert · REQUIRES_NEW 트랜잭션 · 값 가공)에서 날 수 있는 예외만 잡는다.
            // 그 밖의 예외는 Spring Batch AbstractJob 이 afterJob 예외를 잡아 ERROR 로 남기므로 Job 결과는 바뀌지 않는다.
            log.warn("배치 실행 이력 기록 실패 — jobExecutionId={} (Job 결과에는 영향 없음). "
                    + "종료 요약은 위 로그에 남아 있습니다.", jobExecution.getId(), e);
        }

        if (skipped > 0) {
            log.info("스킵 내역은 batch.tb_job_skip 으로 조회한다 — 예: "
                    + "SELECT fail_rsn, count(*) FROM batch.tb_job_skip "
                    + "WHERE job_se = 'RPTP' GROUP BY 1;");
        }
    }
}
