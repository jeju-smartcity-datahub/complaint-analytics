package jeju.datahub.batch.sftychck;

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
 * {@code pkltSftyChckFetchJob} 연 2회 스케줄 — 기본 <b>1·7월 1일 04:00({@code Asia/Seoul})</b>.
 *
 * <p>1·7월 1일은 분기 주차장 수집(02:00)과 이용패턴 연쇄가 먼저 도는 날이라 FK 대상 주차장이 최신이다.
 * 🔴 다만 <b>연쇄가 아니다</b> — {@code PblpkltSyncScheduler} 는 건드리지 않았다. 앞 잡이 04:00 을 넘겨도 겹치지 않는 것은
 * 스케줄러가 1스레드라서다({@code BatchSchedulerConfig.POOL_SIZE} — 1 유지).
 *
 * <h2>설정</h2>
 * <ul>
 *   <li>{@code sftychck.sync.scheduled=false} 면 이 설정 클래스 자체가 빠진다. 수동 트리거
 *       ({@link PkltSftyChckFetchController})는 이 값과 무관하다.</li>
 *   <li>🔁 종전 {@code sftychck.sync.cron}({@code SFTYCHCK_SYNC_CRON})은 폐기됐다 — 주기는
 *       {@code batch.tb_btch_schdl}(운영 화면)에서 바꾼다.</li>
 * </ul>
 *
 * <p>🔁 언제 도나는 {@code batch.tb_btch_schdl} 이 정한다 — {@code @Scheduled}·{@code @EnableScheduling} 은
 * 걷혔고 {@code BatchScheduleRegistry} 가 {@link #syncSemiannual()} 를 건다(기동 로그 {@code [schedule] registered job=pkltSftyChckFetchJob}).
 */
@Configuration
@ConditionalOnProperty(name = "sftychck.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class PkltSftyChckSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PkltSftyChckSyncScheduler.class);

    /** 초기값 참고용(정본은 {@code batch.tb_btch_schdl}) — 1·7월 1일 04:00. cron 식은 {@code 초 분 시 일 월 요일}. */
    static final String CRON = "0 0 4 1 1,7 *";

    static final String ZONE = "Asia/Seoul";

    private final JobLauncher jobLauncher;
    private final Job pkltSftyChckFetchJob;

    public PkltSftyChckSyncScheduler(JobLauncher jobLauncher, Job pkltSftyChckFetchJob) {
        this.jobLauncher = jobLauncher;
        this.pkltSftyChckFetchJob = pkltSftyChckFetchJob;
    }

    /**
     * 🔴 launch 를 try/catch 로 감싼다 — 예외를 스케줄 스레드 밖으로 던지면 원인이 로그에 분명히 남지 않는다.
     * 🔴 {@code runAt} 이 없으면 두 번째 실행부터 {@code JobInstanceAlreadyCompleteException} 이라 조용히 안 돈다.
     */
    public void syncSemiannual() {
        try {
            jobLauncher.run(pkltSftyChckFetchJob, ExcnSe.tag(new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis()), ExcnSe.SCHDL)
                    .toJobParameters());
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            // 시큐어코딩 4.3 — JobLauncher.run 의 선언 예외 4종 + JobRepository(JDBC·트랜잭션) + 인자·상태 예외.
            // (동기 런처는 Job 내부 실패를 예외가 아니라 JobExecution 상태로 돌려준다 — 여기 오는 것은 실행 전·저장소 실패다.)
            log.error("pkltSftyChckFetchJob 실행 실패 — 수동 트리거(POST /api/batch/pklt-sfty-chck-fetch)로 다시 돌리세요(upsert 멱등).", e);
        }
    }
}
