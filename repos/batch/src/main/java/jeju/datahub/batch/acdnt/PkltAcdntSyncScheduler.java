package jeju.datahub.batch.acdnt;

import jeju.datahub.batch.history.ExcnSe;
import jeju.datahub.batch.ops.JobParameterFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@ConditionalOnProperty(name = "acdnt.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class PkltAcdntSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(PkltAcdntSyncScheduler.class);
    private final JobLauncher launcher;
    private final Job pkltAcdntFetchJob;
    public PkltAcdntSyncScheduler(JobLauncher launcher, Job pkltAcdntFetchJob) {
        this.launcher = launcher;
        this.pkltAcdntFetchJob = pkltAcdntFetchJob;
    }
    public void syncMonthly() {
        try {
            launcher.run(pkltAcdntFetchJob, ExcnSe.tag(JobParameterFactory.pkltAcdntFetch(null, null),
                    ExcnSe.SCHDL).toJobParameters());
        } catch (JobExecutionAlreadyRunningException | JobRestartException | JobInstanceAlreadyCompleteException
                | JobParametersInvalidException | DataAccessException | TransactionException
                | IllegalArgumentException | IllegalStateException e) {
            log.error("사고이력 배치 실행 실패 — /api/batch/pklt-acdnt-fetch로 재실행하세요.", e);
        }
    }
}
