package jeju.datahub.batch.crackdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.history.ExcnSe;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * {@code crackdownFetchJob} 스케줄 실행 메서드 (TSK-4337 Step 3).
 *
 * <p>언제 부를지는 {@code BatchScheduleRegistry} 가 {@code batch.tb_btch_schdl} 로 정한다 — 초기값은
 * {@code use_yn='N'}(종전처럼 수동 전용). 🔴 다른 스케줄러와 달리 <b>env 차단 스위치
 * ({@code *.sync.scheduled})가 없다</b> — {@code @ConditionalOnProperty} 를 붙이지 않아 빈이 항상 있고,
 * 표 값만 따른다.
 *
 * <p>🆕 TSK-4316 — 파라미터를 넣지 않는다 = <b>증분</b>(적재된 최대 원천 {@code load_dt}부터 오늘까지). 매월 1일 03:00에 돌며
 * 그날 새로 생긴 구간만 받는다. 스케줄 정본은 batch.tb_btch_schdl이다.
 *
 * <p>본문은 다른 스케줄러와 같은 모양이다 — 기본 {@link JobLauncher}(동기) + {@code runAt} + 실행 구분
 * {@link ExcnSe#SCHDL}. 연쇄는 없다.
 */
@Component
public class CrackdownSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrackdownSyncScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job crackdownFetchJob;

    public CrackdownSyncScheduler(JobLauncher jobLauncher, Job crackdownFetchJob) {
        this.jobLauncher = jobLauncher;
        this.crackdownFetchJob = crackdownFetchJob;
    }

    /**
     * 🔴 launch 를 try/catch 로 감싼다 — 예외를 스케줄 스레드 밖으로 던지지 않는다.
     * 🔴 {@code runAt} 이 없으면 두 번째 실행부터 {@code JobInstanceAlreadyCompleteException} 이다.
     */
    public void fetch() {
        try {
            JobExecution execution = jobLauncher.run(crackdownFetchJob, ExcnSe.tag(new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis()), ExcnSe.SCHDL)
                    .toJobParameters());
            log.info("crackdownFetchJob 종료 — status={}", execution.getStatus());
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — JobLauncher.run 의 선언 예외 4종 + JobRepository(JDBC·트랜잭션) + 인자·상태 예외.
            // (동기 런처는 Job 내부 실패를 예외가 아니라 JobExecution 상태로 돌려준다 — 여기 오는 것은 실행 전·저장소 실패다.)
            log.error("crackdownFetchJob 실행 실패 — 수동 재실행: POST /api/batch/crackdown-fetch (upsert 멱등)", e);
        }
    }
}
