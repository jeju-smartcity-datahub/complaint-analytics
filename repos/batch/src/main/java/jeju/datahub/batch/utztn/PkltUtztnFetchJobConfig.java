package jeju.datahub.batch.utztn;

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

import jeju.datahub.batch.utztn.api.DhubPkltUtztnApiClient;
import jeju.datahub.batch.utztn.api.DhubPkltUtztnItemReader;
import jeju.datahub.batch.utztn.api.PkltUtztnOriginRow;

/** 측정월이 없는 자료도 수집해 실행별 관측으로 추가한다. 같은 실행의 재시도는 Writer 가 중복 방지한다. */
@Configuration
public class PkltUtztnFetchJobConfig {

    public static final String JOB_NAME = "pkltUtztnFetchJob";
    static final String STEP_NAME = "pkltUtztnFetchStep";

    @Bean
    public Job pkltUtztnFetchJob(
            JobRepository jobRepository,
            Step pkltUtztnFetchStep,
            PkltUtztnFetchHistoryListener historyListener,
            RunningJobRegistryListener runningJobRegistry) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // TSK-4337 Step 5 — 실행별 로그 파일 MDC 는 체인 맨 앞(beforeJob 가장 먼저 — 다른 리스너 로그까지 파일에 담는다)
                .listener(JobLogMdcListener.INSTANCE)
                .listener(historyListener)
                // TSK-4337 — 실행 레지스트리는 리스너 체인 맨 뒤 — afterJob 이 역순이라 가장 먼저 불려 앞 리스너 예외에도 해제된다.
                .listener(runningJobRegistry)
                .start(pkltUtztnFetchStep)
                .build();
    }

    @Bean
    public DhubPkltUtztnItemReader pkltUtztnItemReader(DhubPkltUtztnApiClient apiClient) {
        return new DhubPkltUtztnItemReader(apiClient);
    }

    @Bean
    public Step pkltUtztnFetchStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DhubPkltUtztnItemReader pkltUtztnItemReader,
            PkltUtztnProcessor processor,
            PkltUtztnItemWriter writer,
            PkltUtztnSkipListener skipListener,
            PkltUtztnPkltIdResolver pkltIdResolver,
            @Value("${batch.utztn.chunk-size:500}") int chunkSize) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<PkltUtztnOriginRow, PkltUtztnRow>chunk(chunkSize, transactionManager)
                .reader(pkltUtztnItemReader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skip(PkltUtztnSkippableException.class)
                .skipLimit(Integer.MAX_VALUE)
                .listener(skipListener)
                .listener(writer)
                // 🔴 맨 마지막 — 이 뒤로는 fault-tolerant 전용 메서드를 이을 수 없다.
                .listener(pkltIdResolver)
                .build();
    }
}
