package jeju.datahub.batch.acdnt;

import java.time.Clock;
import jeju.datahub.batch.acdnt.api.DhubPkltAcdntApiClient;
import jeju.datahub.batch.acdnt.api.DhubPkltAcdntItemReader;
import jeju.datahub.batch.acdnt.api.PkltAcdntOriginRow;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.ops.JobLogMdcListener;
import jeju.datahub.batch.ops.RunningJobRegistryListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PkltAcdntFetchJobConfig {
    public static final String JOB_NAME = "pkltAcdntFetchJob";
    @Bean public Job pkltAcdntFetchJob(JobRepository repository, Step pkltAcdntFetchStep,
            Step pkltAcdntSeedCleanupStep, PkltAcdntHistoryListener history,
            RunningJobRegistryListener runningJobRegistry) {
        return new JobBuilder(JOB_NAME, repository)
                .listener(JobLogMdcListener.INSTANCE).listener(history).listener(runningJobRegistry)
                .start(pkltAcdntFetchStep).next(pkltAcdntSeedCleanupStep).build();
    }
    @Bean @StepScope
    public DhubPkltAcdntItemReader pkltAcdntItemReader(DhubPkltAcdntApiClient client,
            DhubProperties common, AccidentProperties properties, JdbcTemplate jdbc,
            @Value("#{jobParameters['from']}") String from, @Value("#{jobParameters['to']}") String to) {
        return new DhubPkltAcdntItemReader(client, common, properties,
                () -> AccidentRange.resolve(from, to,
                        () -> jdbc.queryForObject("SELECT max(acdnt_dt) FROM parking.tb_pklt_acdnt_hstry "
                                + "WHERE acdnt_id NOT LIKE 'SEED-%'", java.time.LocalDateTime.class),
                        common.resolveToday(Clock.systemUTC())));
    }
    @Bean public Step pkltAcdntFetchStep(JobRepository repository, PlatformTransactionManager tx,
            DhubPkltAcdntItemReader pkltAcdntItemReader, PkltAcdntProcessor processor,
            PkltAcdntItemWriter writer, PkltAcdntPkltIdResolver resolver,
            @Value("${batch.acdnt.chunk-size:100}") int chunkSize) {
        // 오류 행을 스킵하면 이후 사고일시가 커밋돼 해당 행이 증분 하한 밖으로 빠질 수 있다.
        return new StepBuilder("pkltAcdntFetchStep", repository)
                .<PkltAcdntOriginRow, PkltAcdntRow>chunk(chunkSize, tx)
                .reader(pkltAcdntItemReader).processor(processor).writer(writer)
                .listener(writer).listener(resolver).build();
    }
    @Bean public Step pkltAcdntSeedCleanupStep(JobRepository repository, PlatformTransactionManager tx,
            PkltAcdntSeedCleanupTasklet cleanup) {
        return new StepBuilder("pkltAcdntSeedCleanupStep", repository).tasklet(cleanup, tx).build();
    }
}
