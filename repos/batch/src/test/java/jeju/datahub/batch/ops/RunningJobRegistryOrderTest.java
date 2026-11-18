package jeju.datahub.batch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

import jeju.datahub.batch.cvlcpt.ComplaintFetchJobConfig;
import jeju.datahub.batch.cvlcpt.CvlcptJobSummaryListener;
import jeju.datahub.batch.rptp.ReportAutoCrtHistoryListener;
import jeju.datahub.batch.rptp.ReportAutoCrtJobConfig;
import jeju.datahub.batch.zonenm.ZoneNmPrefillHistoryListener;
import jeju.datahub.batch.zonenm.ZoneNmPrefillJobConfig;

/**
 * 🔴 실행 레지스트리 리스너는 체인 맨 뒤라 {@code afterJob} 이 <b>가장 먼저</b> 불린다 (후속).
 *
 * <p>{@code CompositeJobExecutionListener.afterJob} 은 역순 호출 + 리스너별 예외 차단이 없다. 운영 Job 조립 메서드에
 * {@code afterJob} 에서 던지는 앞 리스너를 넣어도 레지스트리가 해제되는지(= 좀비 오판 없음), 그리고 실행 중에는
 * 등록돼 있었는지를 DB 없이({@link ResourcelessJobRepository}) 단언한다.
 */
class RunningJobRegistryOrderTest {

    @Test
    @DisplayName("보고서 · 민원 · 구역명 — 앞 리스너 afterJob 예외에도 레지스트리 해제 · 실행 중엔 등록")
    void releasedEvenWhenOtherListenerThrows() throws Exception {
        assertReleased("reportAutoCrtJob", ReportAutoCrtHistoryListener.class,
                (f) -> new ReportAutoCrtJobConfig().reportAutoCrtJob(f.repo, f.step, f.listener(), f.running));
        assertReleased("complaintFetchJob", CvlcptJobSummaryListener.class,
                (f) -> new ComplaintFetchJobConfig().complaintFetchJob(f.repo, f.step, f.listener(), f.running));
        assertReleased("zoneNmPrefillJob", ZoneNmPrefillHistoryListener.class,
                (f) -> new ZoneNmPrefillJobConfig().zoneNmPrefillJob(f.repo, f.step, f.listener(), f.running));
    }

    /** 대역 Step·리스너 묶음. */
    static final class Fixture<L extends JobExecutionListener> {
        final JobRepository repo = new ResourcelessJobRepository();
        final RunningJobRegistryListener running = new RunningJobRegistryListener();
        final AtomicBoolean registeredDuringStep = new AtomicBoolean();
        final AtomicBoolean otherAfterJobCalled = new AtomicBoolean();
        final Step step;
        private final L listener;

        Fixture(Class<L> type) {
            step = new StepBuilder("probeStep", repo).tasklet((contribution, ctx) -> {
                registeredDuringStep.set(running.isRunning(
                        ctx.getStepContext().getStepExecution().getJobExecution().getId()));
                return RepeatStatus.FINISHED;
            }, new ResourcelessTransactionManager()).build();
            listener = mock(type);
            // afterJob 이 불린 시점에 레지스트리는 이미 해제돼 있어야 한다(맨 뒤 = 가장 먼저) — 그리고 던진다.
            doAnswer(inv -> {
                otherAfterJobCalled.set(true);
                JobExecution e = inv.getArgument(0);
                assertThat(running.isRunning(e.getId())).as("레지스트리 afterJob 이 먼저 불렸어야 한다").isFalse();
                throw new IllegalStateException("이력 기록 리스너 폭발");
            }).when(listener).afterJob(any());
        }

        L listener() {
            return listener;
        }
    }

    private static <L extends JobExecutionListener> void assertReleased(String jobNm, Class<L> type,
            Function<Fixture<L>, Job> assemble) throws Exception {
        Fixture<L> f = new Fixture<>(type);
        Job job = assemble.apply(f);
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(f.repo);
        launcher.afterPropertiesSet();

        JobExecution execution = launcher.run(job, JobParameterFactory.base().toJobParameters());

        assertThat(execution.getJobInstance().getJobName()).isEqualTo(jobNm);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);   // AbstractJob 이 afterJob 예외를 로그로만 남긴다
        assertThat(f.registeredDuringStep).as(jobNm + " 실행 중 등록").isTrue();
        assertThat(f.otherAfterJobCalled).as(jobNm + " 앞 리스너 afterJob 호출").isTrue();
        assertThat(f.running.isRunning(execution.getId())).as(jobNm + " 해제").isFalse();
        assertThat(f.running.isJobRunning(jobNm)).isFalse();
    }

    @Test
    @DisplayName("대조 — 레지스트리를 맨 앞에 두면 앞 리스너 예외로 해제가 빠진다(이 순서를 되돌리면 안 되는 이유)")
    void frontPlacementLeaks() throws Exception {
        JobRepository repo = new ResourcelessJobRepository();
        RunningJobRegistryListener running = new RunningJobRegistryListener();
        JobExecutionListener thrower = mock(JobExecutionListener.class);
        doThrow(new IllegalStateException("boom")).when(thrower).afterJob(any());
        Job job = new org.springframework.batch.core.job.builder.JobBuilder("frontJob", repo)
                .listener(running).listener(thrower)
                .start(new StepBuilder("s", repo).tasklet((c, x) -> RepeatStatus.FINISHED,
                        new ResourcelessTransactionManager()).build())
                .build();
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repo);
        launcher.afterPropertiesSet();

        JobExecution execution = launcher.run(job, JobParameterFactory.base().toJobParameters());

        assertThat(running.isRunning(execution.getId())).isTrue();
    }
}
