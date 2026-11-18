package jeju.datahub.batch.risknoti;

import jeju.datahub.batch.ops.JobLogMdcListener;
import jeju.datahub.batch.ops.RunningJobRegistryListener;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jeju.datahub.batch.history.BatchRunHistoryRecorder;
import jeju.datahub.batch.history.BatchRunMetrics;
import jeju.datahub.batch.risknoti.RiskAlertReceiverMappingTasklet.PeriodMode;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * ③{@code riskAlertJudgeJob} 조립 — <b>{@code parking.tb_cvlcpt} 집계 → 위험단계 판정 →
 * {@code parking.tb_risk_stp_noti_hstry}</b>.
 *
 * <pre>
 * Reader  RiskAlertTargetItemReader   판정일 하루치를 읍면동으로 묶어 (당일, 전일) 건수 1행
 * Processor RiskAlertJudgeProcessor   증가율 → 단계 판정 → 알림 스냅샷 (못 넘으면 null = 알림 없음)
 * Writer  RiskAlertItemWriter         ON CONFLICT (emd_cd, ocrn_dt) DO UPDATE upsert
 * </pre>
 *
 * <h2>🔴 중단 정책 — {@code skip()} 목록에 무엇을 넣느냐가 곧 정책이다 (D9)</h2>
 * <table>
 *   <tr><th>예외</th><th>처리</th><th>이유</th></tr>
 *   <tr><td>{@link RiskAlertSkippableException}</td><td><b>스킵</b>(기록 후 계속)</td>
 *       <td>그 읍면동만 저장 불가 확정(증가율이 {@code NUMERIC(7,2)} 상한 초과).
 *           한 곳의 병적인 값이 그날 전체 판정을 죽이지 않게 한다</td></tr>
 *   <tr><td>그 외(SQL 오류·커넥션 장애 등)</td><td><b>Job FAILED</b></td>
 *       <td>삼키면 <b>데이터가 조용히 비면서 Job 은 COMPLETED</b> 로 끝나
 *           불완전한 판정분을 완전한 것으로 보게 된다. upsert 라 다시 돌리면 그만이다</td></tr>
 * </table>
 * 🔴 <b>{@code skip()} 목록에 다른 예외를 추가하지 않는다</b> — {@code skip(Exception.class)} 같은
 * 광범위 스킵은 금지한다. 목록에 하나 더 넣는 순간
 * 그 실패가 행 단위로 삼켜져 위 정책이 통째로 무력화된다(선례 {@code ComplaintFetchJobConfig} 와
 * 같은 근거 — 거기서는 {@code GeocodeQuotaExceededException} 을 <b>넣지 않는 것</b>이 곧 즉시 중단 정책이다).
 *
 * <p>{@code skipLimit} 은 {@link Integer#MAX_VALUE} 다. 스킵은 예외 상황이 아니라 정상 경로라,
 * 건수가 늘었다고 Job 을 죽이면 나머지 정상 읍면동까지 적재되지 않는다. 급증 감지는 종료 요약
 * 로그와 {@code batch.tb_job_skip}({@code job_se='RISKNOTI'}) 조회로 한다.
 *
 * <h2>알림 없음(단계 미해당)은 스킵이 아니다</h2>
 * 어떤 기준도 못 넘은 읍면동은 Processor 가 {@code null} 을 반환해 Spring Batch 가 조용히 걸러 낸다
 * — <b>대부분의 읍면동이 매일 여기다.</b> 스킵 사유는 {@code RATE_OVERFLOW} 하나뿐이다.
 */
@Configuration
public class RiskAlertJudgeJobConfig {

    /**
     * 배치 잡 이름. 수동 트리거({@code RiskAlertJudgeController})·스케줄러
     * ({@code ComplaintSyncScheduler} 체인)·{@code batch_job_instance} 조회가 쓰는 값이라 상수로 못박는다.
     */
    public static final String JOB_NAME = "riskAlertJudgeJob";

    static final String STEP_NAME = "riskAlertJudgeStep";

    /**
     * 🔴 백필 전용 Job 이름. 수동 트리거
     * {@code POST /api/batch/risk-alert-receiver-backfill} 가 쓴다.
     */
    public static final String BACKFILL_JOB_NAME = "riskAlertReceiverBackfillJob";

    /** 판정 Job 의 후행 Step — 그날 판정이 끝난 뒤 알림×수신자 매핑을 적재한다(D7·D8). */
    static final String MAPPING_STEP_NAME = "riskAlertReceiverMappingStep";

    /** 백필 Job 의 유일한 Step — 같은 Tasklet 을 기간 파라미터만 바꿔 재사용한다(D7-1 ②). */
    static final String BACKFILL_STEP_NAME = "riskAlertReceiverBackfillStep";

    /**
     * @param historyListener 시작 시 스킵 카운터 리셋 + 종료 시 요약 로그 + 실행 이력 1행
     *                        ({@code batch.tb_btch_excn_hstry}, {@code BatchRunMetrics.none()} — D10)
     */
    @Bean
    public Job riskAlertJudgeJob(
            JobRepository jobRepository,
            Step riskAlertJudgeStep,
            Step riskAlertReceiverMappingStep,
            RiskAlertJudgeHistoryListener historyListener,
            RunningJobRegistryListener runningJobRegistry) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // 실행별 로그 파일 MDC 는 체인 맨 앞(beforeJob 가장 먼저 — 다른 리스너 로그까지 파일에 담는다)
                .listener(JobLogMdcListener.INSTANCE)
                .listener(historyListener)
                // 실행 레지스트리는 리스너 체인 맨 뒤 — afterJob 이 역순이라 가장 먼저 불려 앞 리스너 예외에도 해제된다.
                .listener(runningJobRegistry)
                .start(riskAlertJudgeStep)
                // 🔴 .next() 는 앞 Step 이 COMPLETED 일 때만 돈다 — 전량 판정이 끝나야 그날 noti_id 가
                //    모두 선다(D8). 부분 적재분으로 수신 대상을 굳히면 그날 알림이 일부에게만 간다.
                .next(riskAlertReceiverMappingStep)
                .build();
    }

    /**
     * @param reader    {@code @StepScope} 프록시. Job 파라미터({@code targetDate})를 이 시점에 읽는다
     * @param processor {@code @StepScope} 프록시. 판정 기준({@link RiskCriteriaCache})도 Step 시작에
     *                  새로 읽히므로 화면에서 바뀐 임계가 다음 실행에 바로 반영된다
     * @param chunkSize 한 트랜잭션에 커밋하는 행 수 = 중간 실패 시 되돌아가는 범위.
     *                  🔴 <b>Reader 페이지 크기와 같은 키</b>({@code batch.risknoti.chunk-size})를 읽어
     *                  한쪽만 바뀌는 일을 막는다({@link RiskAlertTargetItemReader} 생성자와 동일)
     */
    @Bean
    public Step riskAlertJudgeStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RiskAlertTargetItemReader reader,
            RiskAlertJudgeProcessor processor,
            RiskAlertItemWriter writer,
            RiskAlertSkipListener riskAlertSkipListener,
            @Value("${batch.risknoti.chunk-size:200}") int chunkSize) {

        return new StepBuilder(STEP_NAME, jobRepository)
                .<RiskAlertTargetRow, RiskAlertRecord>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                // 🔴 D9 — 이 예외 하나만 스킵한다. 다른 예외를 넣지 않는다(위 표).
                .skip(RiskAlertSkippableException.class)
                // 스킵은 정상 경로 — 건수로 Job 을 죽이지 않는다.
                .skipLimit(Integer.MAX_VALUE)
                .listener(riskAlertSkipListener)
                .build();
    }

    /**
     * 판정 Job 의 <b>후행</b> Step — 알림 × 수신자 매핑 적재 (D7·D8).
     *
     * <p>기간은 Job 파라미터 {@code targetDate} 하나가 정본이다({@link PeriodMode#DAILY}) —
     * Tasklet 이 "오늘" 을 다시 계산하면 자정 근처에서 판정과 적재가 하루 어긋난다.
     *
     * <p>🔴 chunk Step 이 아니라 Tasklet 이다 — {@code JdbcBatchItemWriter} 는 upsert 후
     * {@code noti_id} 를 돌려받지 않고, 전량을 봐야 판정되는 것은
     * 별도 Tasklet 으로 뒤에 둔다(선례 {@code PblpkltSoftDeleteMarkingTasklet}).
     */
    @Bean
    public Step riskAlertReceiverMappingStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {

        return new StepBuilder(MAPPING_STEP_NAME, jobRepository)
                .tasklet(new RiskAlertReceiverMappingTasklet(dataSource, PeriodMode.DAILY),
                        transactionManager)
                .build();
    }

    /**
     * 🔴 백필 전용 Job (D7-1 ②) — {@code POST /api/batch/risk-alert-receiver-backfill?fromDt=&toDt=}.
     *
     * <h2>왜 컨트롤러에서 JDBC 를 직접 치지 않는가</h2>
     * 배치 레포의 HTTP 트리거는 <b>전부</b> {@code JobLauncher.run} 이다. 직접 JDBC 면
     * {@code batch.tb_btch_excn_hstry} 에 실행 이력이 남지 않아
     * <i>"Job 실행 1회 = 한 행"</i> 이 깨진다.
     *
     * <h2>🔴 이력 리스너를 직접 붙인다</h2>
     * 이력을 남기는 것은 {@code JobLauncher.run} 이 아니라 <b>잡별 {@link JobExecutionListener}</b> 다.
     * 안 붙이면 위 근거가 그대로 무너진다. {@link RiskAlertJudgeHistoryListener} 를 그대로 쓰면
     * 스킵 카운터 리셋이 함께 묻어오므로(백필 Job 은 스킵이 없다) 전용 리스너를 둔다 —
     * {@link BatchRunHistoryRecorder} + {@link BatchRunMetrics#none()} 은 그대로 재사용한다.
     *
     * <p>⚠️ 기간을 생략하면 <b>전 기간</b>이다. "수단을 남기는 것" 이 핵심이라 1회성이 아니다 —
     * 백필 이후 새로 등록된 수신자도 이 트리거로 과거 알림을 받는다(D7-1).
     */
    @Bean
    public Job riskAlertReceiverBackfillJob(
            JobRepository jobRepository,
            Step riskAlertReceiverBackfillStep,
            BatchRunHistoryRecorder historyRecorder,
            RunningJobRegistryListener runningJobRegistry) {

        return new JobBuilder(BACKFILL_JOB_NAME, jobRepository)
                // 실행별 로그 파일 MDC 는 체인 맨 앞(beforeJob 가장 먼저 — 다른 리스너 로그까지 파일에 담는다)
                .listener(JobLogMdcListener.INSTANCE)
                .listener(new BackfillHistoryListener(historyRecorder))
                // 실행 레지스트리는 리스너 체인 맨 뒤 — afterJob 이 역순이라 가장 먼저 불려 앞 리스너 예외에도 해제된다.
                .listener(runningJobRegistry)
                .start(riskAlertReceiverBackfillStep)
                .build();
    }

    /**
     * 백필 Job 의 유일한 Step — 판정 Step 과 <b>같은 Tasklet 클래스</b>를 기간 파라미터만 바꿔 쓴다
     * ({@link PeriodMode#RANGE}). 지역 3단 규칙 SQL 이 두 벌로 갈리지 않게 하는 장치다(D7-1).
     */
    @Bean
    public Step riskAlertReceiverBackfillStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {

        return new StepBuilder(BACKFILL_STEP_NAME, jobRepository)
                .tasklet(new RiskAlertReceiverMappingTasklet(dataSource, PeriodMode.RANGE),
                        transactionManager)
                .build();
    }

    /**
     * 백필 Job 전용 이력 리스너 — 실행 1회를 {@code batch.tb_btch_excn_hstry} 한 행으로 남긴다.
     *
     * <p>🔴 지표는 {@link BatchRunMetrics#none()} 이다 — 이 잡은 지오코딩·분류를 하지 않아 채울 값이
     * 없고, 0 으로 채우면 <b>"했는데 0건"</b> 이라는 가짜 관측치가 추이 집계에 섞인다
     * ({@link RiskAlertJudgeHistoryListener} 와 같은 근거).
     *
     * <p>⚠️ try/catch 는 트랜잭션 경계 바깥인 {@code afterJob} 에 둔다 — 이력 기록 실패가 백필을
     * 죽이면 안 된다.
     */
    static final class BackfillHistoryListener implements JobExecutionListener {

        private static final Logger log = LoggerFactory.getLogger(BackfillHistoryListener.class);

        private final BatchRunHistoryRecorder historyRecorder;

        BackfillHistoryListener(BatchRunHistoryRecorder historyRecorder) {
            this.historyRecorder = historyRecorder;
        }

        @Override
        public void beforeJob(JobExecution jobExecution) {
            log.info("[{}] 시작 — jobExecutionId={} params={}",
                    BACKFILL_JOB_NAME, jobExecution.getId(), jobExecution.getJobParameters());
        }

        /** 실패한 Job 에서도 호출된다 — FAILED 야말로 "어디까지 하고 죽었는지" 가 남아야 한다. */
        @Override
        public void afterJob(JobExecution jobExecution) {
            // ⚠️ read/write 는 0 이 정상이다 — Tasklet 이 카운트를 올리지 않는다(적재 건수는 Tasklet 로그).
            log.info("[{}] status={} — 적재 건수는 Tasklet 로그('알림×수신자 매핑 적재 N건')를 본다.",
                    BACKFILL_JOB_NAME, jobExecution.getStatus());
            try {
                historyRecorder.record(jobExecution, BatchRunMetrics.none());
            } catch (DataAccessException | TransactionException | IllegalArgumentException
                    | IllegalStateException e) {
                // 시큐어코딩 4.3 — 기록(JDBC upsert · REQUIRES_NEW 트랜잭션 · 값 가공)에서 날 수 있는 예외만 잡는다.
                // 그 밖의 예외는 Spring Batch AbstractJob 이 afterJob 예외를 잡아 ERROR 로 남기므로 Job 결과는 바뀌지 않는다.
                log.warn("배치 실행 이력 기록 실패 — jobExecutionId={} (Job 결과에는 영향 없음).",
                        jobExecution.getId(), e);
            }
        }
    }
}
