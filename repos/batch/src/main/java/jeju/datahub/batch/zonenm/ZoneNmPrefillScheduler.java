package jeju.datahub.batch.zonenm;

import jeju.datahub.batch.history.ExcnSe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * 매일 03:10(Asia/Seoul) 구역명 미리 채우기 (TSK-4339 Step 8).
 *
 * <pre>
 * 02:00  complaintFetchJob → riskAlertJudgeJob
 * 02:30  reportAutoCrtJob
 * 03:10  zoneNmPrefillJob(전 크기) — 새로 민원·주차장이 생긴 격자만 남아 있다
 * </pre>
 *
 * <p>🔁 언제 도나는 {@code batch.tb_btch_schdl} 이 정한다(TSK-4337 Step 3) — {@code @Scheduled}·{@code @EnableScheduling} 은
 * 걷혔고 {@code BatchScheduleRegistry} 가 {@link #prefillDaily()} 를 건다(기동 로그 {@code [schedule] registered job=zoneNmPrefillJob}).
 * {@code zonenm.sync.scheduled=false} 면 이 설정 클래스 자체가 빠진다(= env 상위 차단). 수동 트리거는 무관하다.
 * 종전 {@code zonenm.sync.cron} 은 폐기됐다 — cron 은 표가 정본이다.
 */
@Configuration
@ConditionalOnProperty(name = "zonenm.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class ZoneNmPrefillScheduler {

    private static final Logger log = LoggerFactory.getLogger(ZoneNmPrefillScheduler.class);

    /** 초기값 참고용(정본은 {@code batch.tb_btch_schdl}) — 매일 03:10 — 민원 수집(02:00)·보고서(02:30) 뒤. */
    static final String CRON = "0 10 3 * * *";

    static final String ZONE = ZoneNmPrefillJobConfig.ZONE;

    private final JobLauncher jobLauncher;
    private final Job zoneNmPrefillJob;

    public ZoneNmPrefillScheduler(JobLauncher jobLauncher, Job zoneNmPrefillJob) {
        this.jobLauncher = jobLauncher;
        this.zoneNmPrefillJob = zoneNmPrefillJob;
    }

    /** 예외를 밖으로 던지지 않는다 — 실패는 ERROR 로 남기고 다음 날 실행이 남은 셀을 이어서 한다. */
    public void prefillDaily() {
        try {
            JobExecution execution = jobLauncher.run(zoneNmPrefillJob, ExcnSe.tag(new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis()), ExcnSe.SCHDL)
                    .toJobParameters());
            log.info("zoneNmPrefillJob 종료 — status={}", execution.getStatus());
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — JobLauncher.run 의 선언 예외 4종 + JobRepository(JDBC·트랜잭션) + 인자·상태 예외.
            // (동기 런처는 Job 내부 실패를 예외가 아니라 JobExecution 상태로 돌려준다 — 여기 오는 것은 실행 전·저장소 실패다.)
            log.error("zoneNmPrefillJob 실행 실패 — 수동 재실행: POST /api/batch/zone-nm-prefill", e);
        }
    }
}
