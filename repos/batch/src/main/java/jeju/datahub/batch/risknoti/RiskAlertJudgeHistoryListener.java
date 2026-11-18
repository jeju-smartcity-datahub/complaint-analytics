package jeju.datahub.batch.risknoti;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.history.BatchRunHistoryRecorder;
import jeju.datahub.batch.history.BatchRunMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * ③{@code riskAlertJudgeJob} 실행 1회를 {@code batch.tb_btch_excn_hstry} 에 남기고
 * 종료 요약 한 줄을 로그로 뱉는다.
 *
 * <pre>
 * [riskAlertJudgeJob] status=COMPLETED read=74 written=11
 *   skipped: 총 1건 {RATE_OVERFLOW=1}
 * </pre>
 *
 * <h2>🔴 지표는 {@link BatchRunMetrics#none()} — 전용 컬럼을 만들지 않는다 (D10)</h2>
 * 이 잡은 지오코딩·분류를 하지 않아 {@code BatchRunMetrics} 의 11필드에 채울 값이 하나도 없다.
 * 0 으로 채우면 <b>"했는데 0건"</b> 이라는 가짜 관측치가 추이 집계에 섞인다
 * ({@code CrackdownFetchHistoryListener}·{@code PblpkltFetchHistoryListener} 와 동일 근거).
 *
 * <p>✅ 그래서 {@code history/} 패키지({@code BatchRunMetrics}·{@code BatchRunHistoryRecorder})와
 * 뷰 {@code batch.v_btch_excn_hstry} 를 <b>한 줄도 고치지 않는다</b> — 이 잡을 붙이면서 기존 3개 잡의
 * 이력 기록에 회귀 위험을 만들지 않는 것이 D10 을 이렇게 정한 실익이다. 남는 값은
 * {@code job_nm}·{@code status}·{@code read_cnt}·{@code write_cnt}·{@code started_dt}·{@code ended_dt} 다.
 *
 * <p>⚠️ 그 대신 <b>단계별 분포와 스킵 건수는 이력 테이블에 남지 않는다.</b> 각각
 * {@code parking.tb_risk_stp_noti_hstry}(단계별 {@code GROUP BY risk_lvl_cd})와
 * {@code batch.tb_job_skip}({@code WHERE job_se='RISKNOTI'})을 직접 집계해서 본다.
 *
 * <h2>🔴 {@code beforeJob} 의 리셋이 없으면 요약이 거짓말을 한다</h2>
 * {@link RiskAlertSkipListener} 는 {@code @Component} <b>싱글턴</b>이라 카운터가 실행 간에 살아남는다.
 * 수동 트리거로 같은 JVM 에서 두 번 돌리면(재실행 안전성 검증이 정확히 그 경우다) 2회차 요약에
 * 1회차 스킵이 그대로 더해진다. 그래서 Job 시작 시점에 0 으로 되돌린다 — 누적 건수는
 * {@code batch.tb_job_skip} 이 정본이므로 이 카운터는 <b>"이번 실행에서 새로 스킵된 수"</b> 를 뜻한다.
 * (Step 의 read/write 는 {@link StepExecution} 이 실행마다 새로 만들어 주므로 리셋 대상이 아니다.
 * 선례 {@code CvlcptJobSummaryListener}.)
 */
@Component
public class RiskAlertJudgeHistoryListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(RiskAlertJudgeHistoryListener.class);

    private final RiskAlertSkipListener skipListener;
    private final BatchRunHistoryRecorder historyRecorder;

    public RiskAlertJudgeHistoryListener(RiskAlertSkipListener skipListener,
                                         BatchRunHistoryRecorder historyRecorder) {
        this.skipListener = skipListener;
        this.historyRecorder = historyRecorder;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        skipListener.resetStats();
        log.info("[{}] 시작 — jobExecutionId={} params={}",
                jobExecution.getJobInstance().getJobName(), jobExecution.getId(),
                jobExecution.getJobParameters());
    }

    /** 실패한 Job 에서도 호출된다 — FAILED 야말로 "어디까지 하고 죽었는지"가 남아야 한다. */
    @Override
    public void afterJob(JobExecution jobExecution) {
        long read = 0;
        long written = 0;
        for (StepExecution step : jobExecution.getStepExecutions()) {
            read += step.getReadCount();
            written += step.getWriteCount();
        }

        long skipped = skipListener.totalSkipped();
        Map<String, Long> skipCounts = skipListener.skipCounts();

        // ⚠️ read > written 은 정상이다 — 어떤 기준도 못 넘은 읍면동은 Processor 가 null 을 반환해
        //    걸러지고(알림 없음), 대부분의 읍면동이 매일 그쪽이다. 스킵과 혼동하지 말 것.
        log.info("[{}] status={} read={} written={} skipped={} {}",
                jobExecution.getJobInstance().getJobName(), jobExecution.getStatus(),
                read, written, skipped, skipCounts);

        // ⚠️ try/catch 는 트랜잭션 경계 바깥인 여기 — 이력 기록 실패가 판정 배치를 죽이면 안 된다.
        //    record() 안에서 잡으면 커밋 시점의 실패를 못 잡는다.
        try {
            historyRecorder.record(jobExecution, BatchRunMetrics.none());   // 🔴 D10 — 전용 지표 없음
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3 — 기록(JDBC upsert · REQUIRES_NEW 트랜잭션 · 값 가공)에서 날 수 있는 예외만 잡는다.
            // 그 밖의 예외는 Spring Batch AbstractJob 이 afterJob 예외를 잡아 ERROR 로 남기므로 Job 결과는 바뀌지 않는다.
            log.warn("배치 실행 이력 기록 실패 — jobExecutionId={} (Job 결과에는 영향 없음). "
                    + "종료 요약은 위 로그에 남아 있습니다.", jobExecution.getId(), e);
        }

        if (skipped > 0) {
            log.info("스킵 내역은 batch.tb_job_skip 으로 조회한다 — 예: "
                    + "SELECT fail_rsn, count(*) FROM batch.tb_job_skip "
                    + "WHERE job_se = 'RISKNOTI' GROUP BY 1;");
        }
    }
}
