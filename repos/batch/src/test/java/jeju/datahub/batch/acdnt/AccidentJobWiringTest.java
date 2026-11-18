package jeju.datahub.batch.acdnt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import jeju.datahub.batch.acdnt.api.DhubPkltAcdntApiClient;
import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.ops.BatchJobCatalog;
import jeju.datahub.batch.ops.RunningJobRegistryListener;

class AccidentJobWiringTest {
    @Test void jobStepsMatchOpsCatalogWithoutRequiringAnExternalDataset() {
        new ApplicationContextRunner().withUserConfiguration(PkltAcdntFetchJobConfig.class)
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(DhubPkltAcdntApiClient.class, () -> mock(DhubPkltAcdntApiClient.class))
                .withBean(DhubProperties.class, DhubProperties::new)
                .withBean(AccidentProperties.class, AccidentProperties::new)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PkltAcdntProcessor.class, () -> mock(PkltAcdntProcessor.class))
                .withBean(PkltAcdntItemWriter.class, () -> mock(PkltAcdntItemWriter.class))
                .withBean(PkltAcdntPkltIdResolver.class, () -> mock(PkltAcdntPkltIdResolver.class))
                .withBean(PkltAcdntSeedCleanupTasklet.class, () -> mock(PkltAcdntSeedCleanupTasklet.class))
                .withBean(PkltAcdntHistoryListener.class, () -> mock(PkltAcdntHistoryListener.class))
                .withBean(RunningJobRegistryListener.class, RunningJobRegistryListener::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Job job = context.getBean("pkltAcdntFetchJob", Job.class);
                    assertThat(((StepLocator) job).getStepNames()).containsExactlyElementsOf(
                            new BatchJobCatalog().find(job.getName()).orElseThrow().steps().stream()
                                    .map(BatchJobCatalog.StepSpec::stepNm).toList());
                });
    }
}
