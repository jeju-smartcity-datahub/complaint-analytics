package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

import jeju.datahub.batch.ops.JobParameterFactory;
import jeju.datahub.batch.ops.RunningJobRegistryListener;

/**
 * 🔴 공영주차장 수집 Step 이 중지({@code STOPPED})되면 소멸 마킹 Step 은 돌지 않는다.
 *
 * <p>부분 수집분으로 "이번 수집에 없었다" 를 판정하면 멀쩡한 주차장이 소멸 처리된다. 실 데이터셋 확정 전에는 실 실행이
 * 금지라 <b>운영 Job 조립 메서드({@link PblpkltFetchJobConfig#pblpkltFetchJob})</b>에 대역 Step 두 개를
 * 넣어 DB·외부 API 없이({@link ResourcelessJobRepository}) 단언한다.
 *
 * <p>중지는 {@code JobOperator.stop} → 실행 상태 {@code STOPPING} → 다음 청크 경계에서
 * {@code stepExecution.setTerminateOnly()} 로 나타난다 — 대역 수집 Step 이 그 마지막 단계를 그대로 흉내 낸다.
 */
class PblpkltStopSkipsSoftDeleteTest {

    @Test
    @DisplayName("수집 Step STOPPED → 소멸 마킹 미실행 · Job STOPPED · 실행 레지스트리 해제")
    void stoppedFetchSkipsSoftDelete() throws Exception {
        JobRepository repository = new ResourcelessJobRepository();
        ResourcelessTransactionManager tx = new ResourcelessTransactionManager();
        AtomicInteger fetchCalls = new AtomicInteger();
        AtomicInteger softDeleteCalls = new AtomicInteger();

        Step fetch = new StepBuilder(PblpkltFetchJobConfig.STEP_NAME, repository)
                .tasklet((contribution, chunkContext) -> {
                    // 첫 묶음 처리 뒤 중지 요청이 들어온 상황 — 다음 반복 전에 인터럽트된다.
                    fetchCalls.incrementAndGet();
                    chunkContext.getStepContext().getStepExecution().setTerminateOnly();
                    return RepeatStatus.CONTINUABLE;
                }, tx).build();
        Step softDelete = new StepBuilder(PblpkltFetchJobConfig.SOFT_DELETE_STEP_NAME, repository)
                .tasklet((contribution, chunkContext) -> {
                    softDeleteCalls.incrementAndGet();
                    return RepeatStatus.FINISHED;
                }, tx).build();
        RunningJobRegistryListener running = new RunningJobRegistryListener();

        Job job = new PblpkltFetchJobConfig().pblpkltFetchJob(repository, fetch, softDelete,
                mock(PblpkltFetchHistoryListener.class), running);

        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.afterPropertiesSet();   // 기본 = 동기(SyncTaskExecutor)
        JobExecution execution = launcher.run(job, JobParameterFactory.base().toJobParameters());

        assertThat(fetchCalls).hasValue(1);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(softDeleteCalls).as("부분 수집으로 소멸 마킹이 돌면 멀쩡한 주차장이 지워진다").hasValue(0);
        assertThat(execution.getStepExecutions()).extracting("stepName")
                .containsExactly(PblpkltFetchJobConfig.STEP_NAME);
        assertThat(running.isJobRunning(PblpkltFetchJobConfig.JOB_NAME)).isFalse();
    }
}
