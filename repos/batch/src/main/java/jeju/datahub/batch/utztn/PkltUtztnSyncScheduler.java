package jeju.datahub.batch.utztn;

import jeju.datahub.batch.history.ExcnSe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
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
 * {@code pkltUtztnFetchJob} 월별 스케줄 — <b>매월 1일 03:00({@code Asia/Seoul})</b> (TSK-4189 Step 9).
 *
 * <p>⚠️ <b>03:00 이다(02:00 아님)</b> — 분기 첫날에 {@code PblpkltSyncScheduler} 의 연쇄(02:00 시작)와
 * 시각을 갈라 둔다. 🔴 다만 시각 분리가 동시 실행을 막아 주는 것은 아니다 — 연쇄는 02:00 이 아니라
 * {@code pblpkltFetchJob} 이 <b>끝나는 시각</b>에 시작하는 동기 호출이다. 겹치지 않는 것은 스케줄러가
 * 1스레드라서다({@code BatchSchedulerConfig.POOL_SIZE} — 종전 Boot 기본과 같고 TSK-4337 에서도 1 유지).
 * 주차장 수집이 03:00 을 넘기면 이 스케줄은 그 연쇄가 끝날 때까지 기다렸다가 돈다(같은 달을 다시 upsert — 결과는 같다).
 *
 * <h2>on/off</h2>
 * {@code utztn.sync.scheduled=false} 면 이 설정 클래스 자체가 빠진다.
 * 🔴 <b>같은 키가 {@code PblpkltSyncScheduler} 의 연쇄 분기도 끈다</b> — "연쇄만 끈다" 가 아니라
 * "이용패턴 자동 실행 전부" 다. 수동 트리거({@link PkltUtztnFetchController})만 이 값과 무관하다.
 *
 * <p>🔁 언제 도나는 {@code batch.tb_btch_schdl} 이 정한다(TSK-4337 Step 3) — {@code @Scheduled}·{@code @EnableScheduling} 은
 * 걷혔고 {@code BatchScheduleRegistry} 가 {@link #syncMonthly()} 를 건다(기동 로그 {@code [schedule] registered job=pkltUtztnFetchJob}).
 */
@Configuration
@ConditionalOnProperty(name = "utztn.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class PkltUtztnSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PkltUtztnSyncScheduler.class);

    /** 초기값 참고용(정본은 {@code batch.tb_btch_schdl}) — 매월 1일 03:00. cron 식은 {@code 초 분 시 일 월 요일}. */
    static final String CRON = "0 0 3 1 * *";

    static final String ZONE = "Asia/Seoul";

    private final JobLauncher jobLauncher;
    private final Job pkltUtztnFetchJob;

    public PkltUtztnSyncScheduler(JobLauncher jobLauncher, Job pkltUtztnFetchJob) {
        this.jobLauncher = jobLauncher;
        this.pkltUtztnFetchJob = pkltUtztnFetchJob;
    }

    /**
     * 🔴 launch 를 try/catch 로 감싼다 — 예외를 스케줄 스레드 밖으로 던지면 그 주기가 통째로 사라지고
     * 원인이 로그에 남지 않는다. 🔴 {@code runAt} 이 없으면 두 번째 실행부터
     * {@code JobInstanceAlreadyCompleteException} 이라 조용히 안 돈다.
     */
    public void syncMonthly() {
        try {
            jobLauncher.run(pkltUtztnFetchJob, ExcnSe.tag(new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis()), ExcnSe.SCHDL)
                    .toJobParameters());
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3(TSK-4336) — JobLauncher.run 의 선언 예외 4종 + JobRepository(JDBC·트랜잭션) + 인자·상태 예외.
            // (동기 런처는 Job 내부 실패를 예외가 아니라 JobExecution 상태로 돌려준다 — 여기 오는 것은 실행 전·저장소 실패다.)
            log.error("pkltUtztnFetchJob 실행 실패 — 다음 실행이 전량을 다시 받습니다(upsert).", e);
        }
    }
}
