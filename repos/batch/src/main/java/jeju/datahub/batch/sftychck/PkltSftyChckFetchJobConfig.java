package jeju.datahub.batch.sftychck;

import jeju.datahub.batch.ops.JobLogMdcListener;
import jeju.datahub.batch.ops.RunningJobRegistryListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jeju.datahub.batch.cvlcpt.api.DhubProperties;
import jeju.datahub.batch.sftychck.api.DhubPkltSftyChckApiClient;
import jeju.datahub.batch.sftychck.api.DhubPkltSftyChckItemReader;
import jeju.datahub.batch.sftychck.api.PkltSftyChckOriginRow;

/** 안전점검 수집과 관측 추가를 한 Step 으로 수행한다. 기존 실자료와 시드를 갱신하거나 삭제하지 않는다. */
@Configuration
public class PkltSftyChckFetchJobConfig {

    public static final String JOB_NAME = "pkltSftyChckFetchJob";
    static final String STEP_NAME = "pkltSftyChckFetchStep";
    static final String SEED_CLEANUP_STEP_NAME = "pkltSftyChckSeedCleanupStep";

    @Bean
    public Job pkltSftyChckFetchJob(
            JobRepository jobRepository,
            Step pkltSftyChckFetchStep,
            PkltSftyChckFetchHistoryListener historyListener,
            RunningJobRegistryListener runningJobRegistry) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // 실행별 로그 파일 MDC 는 체인 맨 앞(beforeJob 가장 먼저 — 다른 리스너 로그까지 파일에 담는다)
                .listener(JobLogMdcListener.INSTANCE)
                .listener(historyListener)
                // 실행 레지스트리는 리스너 체인 맨 뒤 — afterJob 이 역순이라 가장 먼저 불려 앞 리스너 예외에도 해제된다.
                .listener(runningJobRegistry)
                .start(pkltSftyChckFetchStep)
                .build();
    }

    @Bean
    public DhubPkltSftyChckItemReader pkltSftyChckItemReader(
            DhubPkltSftyChckApiClient apiClient, DhubProperties properties) {
        return new DhubPkltSftyChckItemReader(apiClient, properties);
    }

    @Bean
    public Step pkltSftyChckFetchStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DhubPkltSftyChckItemReader pkltSftyChckItemReader,
            PkltSftyChckProcessor processor,
            PkltSftyChckItemWriter writer,
            PkltSftyChckSkipListener skipListener,
            PkltSftyChckPkltIdResolver pkltIdResolver,
            @Value("${batch.sftychck.chunk-size:500}") int chunkSize) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<PkltSftyChckOriginRow, PkltSftyChckRow>chunk(chunkSize, transactionManager)
                .reader(pkltSftyChckItemReader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skip(PkltSftyChckSkippableException.class)
                .skipLimit(Integer.MAX_VALUE)
                // 스킵 스캔 때 앞선 항목의 Processor 결과를 재사용한다 — 재처리 때 DUP_KEY 오판정을 막는다.
                .processorNonTransactional()
                .listener(skipListener)
                .listener(writer)
                // 🔴 맨 마지막 — 이 뒤로는 fault-tolerant 전용 메서드를 이을 수 없다.
                .listener(pkltIdResolver)
                .build();
    }

}
