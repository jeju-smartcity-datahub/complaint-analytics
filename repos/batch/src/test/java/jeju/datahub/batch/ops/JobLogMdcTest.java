package jeju.datahub.batch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import jeju.datahub.batch.schedule.BatchSchedule;
import jeju.datahub.batch.schedule.BatchScheduleRegistry;
import jeju.datahub.batch.schedule.BatchScheduleRepository;
import jeju.datahub.batch.schedule.ScheduleTarget;

/**
 * 🔴 실행별 로그 MDC 누수 방지 — 리스너 체인 맨 앞에서 put, 진입점 finally 에서 한 번 더 지운다.
 *
 * <p>앞 리스너 {@code afterJob} 예외로 MDC 리스너의 해제가 빠지는 경우를 일부러 만들고, 같은 스레드의 다음 로그 시점에
 * 라우팅 키가 남지 않는지(스케줄 fired 래퍼·HTTP 필터) 본다. 다른 리스너의 시작·종료 시점에 키가 있는지(파일에 담기는지)도 본다.
 */
class JobLogMdcTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /** 운영 JobConfig 와 같은 순서: MDC → (이력 리스너 = 시점 기록 + afterJob 폭발) → 레지스트리. */
    private static final class Probe {
        final JobRepository repo = new ResourcelessJobRepository();
        final RunningJobRegistryListener running = new RunningJobRegistryListener();
        final Map<String, String> seen = new ConcurrentHashMap<>();
        final Job job;

        Probe(String jobNm) {
            JobExecutionListener history = new JobExecutionListener() {
                @Override
                public void beforeJob(JobExecution e) {
                    seen.put("historyBefore", String.valueOf(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)));
                }

                @Override
                public void afterJob(JobExecution e) {
                    seen.put("historyAfter", String.valueOf(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)));
                    throw new IllegalStateException("이력 리스너 폭발");
                }
            };
            job = new JobBuilder(jobNm, repo)
                    .listener(JobLogMdcListener.INSTANCE)
                    .listener(history)
                    .listener(running)
                    .start(new StepBuilder("probeStep", repo).tasklet((c, ctx) -> {
                        seen.put("step", MDC.get(JobLogMdcListener.KEY_EXECUTION_ID) + "/" + MDC.get(JobLogMdcListener.KEY_JOB_NM));
                        seen.put("stepThread", Thread.currentThread().getName());
                        return RepeatStatus.FINISHED;
                    }, new ResourcelessTransactionManager()).build())
                    .build();
        }

        // 4.3 — afterPropertiesSet()(throws Exception) 대신 그 기본값인 동기 실행기를 직접 지정해
        //   던지는 예외를 JobLauncher.run 의 JobExecutionException 계열로 좁힌다(동작 같음).
        JobExecution runSync() throws JobExecutionException {
            TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
            launcher.setJobRepository(repo);
            launcher.setTaskExecutor(new SyncTaskExecutor());
            return launcher.run(job, JobParameterFactory.base().toJobParameters());
        }
    }

    @Test
    @DisplayName("MDC 는 다른 리스너 시작·종료와 Step 에 실려 있다(같은 스레드) · 앞 리스너 예외면 리스너만으로는 남는다(진입점 정리가 필요한 이유)")
    void listenerAloneLeaksOnException() throws Exception {
        Probe p = new Probe("probeJob");
        JobExecution e = p.runSync();
        String id = String.valueOf(e.getId());

        assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(p.seen.get("historyBefore")).isEqualTo(id);
        assertThat(p.seen.get("historyAfter")).isEqualTo(id);
        assertThat(p.seen.get("step")).isEqualTo(id + "/probeJob");
        assertThat(p.seen.get("stepThread")).isEqualTo(Thread.currentThread().getName());   // Step = 런처 호출 스레드
        // afterJob 역순: 레지스트리 → 이력(폭발) → MDC 리스너는 불리지 못한다
        assertThat(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)).isEqualTo(id);
        assertThat(p.running.isRunning(e.getId())).isFalse();
    }

    @Test
    @DisplayName("예외 없으면 afterJob 에서 지운다 · 다음 실행 beforeJob 은 덮어쓴다")
    void normalPathClearsAndOverwrites() throws Exception {
        MDC.put(JobLogMdcListener.KEY_EXECUTION_ID, "999");
        MDC.put(JobLogMdcListener.KEY_JOB_NM, "staleJob");
        MDC.put("other", "keep");
        JobRepository repo = new ResourcelessJobRepository();
        Map<String, String> seen = new ConcurrentHashMap<>();
        Job job = new JobBuilder("cleanJob", repo).listener(JobLogMdcListener.INSTANCE)
                .start(new StepBuilder("s", repo).tasklet((c, ctx) -> {
                    seen.put("step", MDC.get(JobLogMdcListener.KEY_EXECUTION_ID) + "/" + MDC.get(JobLogMdcListener.KEY_JOB_NM));
                    return RepeatStatus.FINISHED;
                }, new ResourcelessTransactionManager()).build()).build();
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repo);
        launcher.afterPropertiesSet();
        JobExecution e = launcher.run(job, JobParameterFactory.base().toJobParameters());

        assertThat(seen.get("step")).isEqualTo(e.getId() + "/cleanJob");
        assertThat(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)).isNull();
        assertThat(MDC.get(JobLogMdcListener.KEY_JOB_NM)).isNull();
        assertThat(MDC.get("other")).isEqualTo("keep");                // 남의 키는 건드리지 않는다
    }

    @Test
    @DisplayName("🔴 스케줄 fired 래퍼 — 리스너 예외로 남은 키를 같은 스레드에서 지운다(다음 무관한 로그가 이전 실행 파일로 가지 않는다)")
    void scheduleEntryClears() throws Exception {
        Probe p = new Probe("complaintFetchJob");
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doReturn(mock(ScheduledFuture.class)).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));
        BatchScheduleRepository repo = mock(BatchScheduleRepository.class);
        when(repo.findAll()).thenReturn(List.of(new BatchSchedule("complaintFetchJob", "0 0 2 * * *", "Y")));
        Runnable action = () -> {
            try {
                p.runSync();
                // 진입점 정리 전에는 남아 있다 — 이 상태로 스레드가 반환되면 누수
                assertThat(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)).isNotNull();
            } catch (JobExecutionException ex) {   // 4.3
                throw new IllegalStateException(ex);
            }
        };
        BatchScheduleRegistry registry = new BatchScheduleRegistry(scheduler, repo, mock(Environment.class),
                List.of(new ScheduleTarget("complaintFetchJob", "complaint.sync.scheduled", action)));
        registry.registerAll();
        ArgumentCaptor<Runnable> fired = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(fired.capture(), any(Trigger.class));

        new SyncTaskExecutor().execute(fired.getValue());   // 스케줄 스레드 대신 이 스레드에서 · 7.2 run() 직접 호출 대신

        assertThat(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)).isNull();
        assertThat(MDC.get(JobLogMdcListener.KEY_JOB_NM)).isNull();
        assertThat(p.seen.get("step")).isNotNull();
    }

    @Test
    @DisplayName("🔴 스케줄 래퍼 — 실행 메서드가 던져도 지운다")
    void scheduleEntryClearsOnThrow() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doReturn(mock(ScheduledFuture.class)).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));
        BatchScheduleRepository repo = mock(BatchScheduleRepository.class);
        when(repo.findAll()).thenReturn(List.of(new BatchSchedule("crackdownFetchJob", "0 0 3 * * *", "Y")));
        Runnable action = () -> {
            MDC.put(JobLogMdcListener.KEY_EXECUTION_ID, "77");
            throw new IllegalStateException("boom");
        };
        BatchScheduleRegistry registry = new BatchScheduleRegistry(scheduler, repo, mock(Environment.class),
                List.of(new ScheduleTarget("crackdownFetchJob", null, action)));
        registry.registerAll();
        ArgumentCaptor<Runnable> fired = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(fired.capture(), any(Trigger.class));
        new SyncTaskExecutor().execute(fired.getValue());   // 7.2
        assertThat(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)).isNull();
    }

    @Test
    @DisplayName("🔴 HTTP 진입점 필터 — 동기 트리거가 톰캣 스레드에 남긴 키를 요청 끝에서 지운다 · 요청 시작 전 잔여 키도 지운다")
    void httpEntryClears() throws Exception {
        Probe p = new Probe("crackdownFetchJob");
        MDC.put(JobLogMdcListener.KEY_EXECUTION_ID, "stale");
        Map<String, String> atStart = new ConcurrentHashMap<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                    jakarta.servlet.http.HttpServletResponse res) {
                atStart.put("v", String.valueOf(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)));
                try {
                    p.runSync();
                } catch (JobExecutionException ex) {   // 4.3
                    throw new IllegalStateException(ex);
                }
            }
        });
        new JobLogMdcClearFilter().doFilter(new MockHttpServletRequest("POST", "/api/batch/crackdown-fetch"),
                new MockHttpServletResponse(), chain);
        assertThat(atStart.get("v")).isEqualTo("null");
        assertThat(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)).isNull();
        assertThat(MDC.get(JobLogMdcListener.KEY_JOB_NM)).isNull();
    }

    @Test
    @DisplayName("운영 JobConfig(보고서 잡 대표) — MDC 리스너가 체인 맨 앞이다(Step 에서 키 존재 · 앞 리스너 afterJob 예외 후에도 레지스트리 해제)")
    void productionJobsHaveMdcFirst() throws Exception {
        RunningJobRegistryOrderTest.Fixture<jeju.datahub.batch.rptp.ReportAutoCrtHistoryListener> f =
                new RunningJobRegistryOrderTest.Fixture<>(jeju.datahub.batch.rptp.ReportAutoCrtHistoryListener.class);
        Map<String, String> seen = new ConcurrentHashMap<>();
        org.springframework.batch.core.Step step = new StepBuilder("probeStep", f.repo).tasklet((c, ctx) -> {
            seen.put("step", String.valueOf(MDC.get(JobLogMdcListener.KEY_EXECUTION_ID)));
            return RepeatStatus.FINISHED;
        }, new ResourcelessTransactionManager()).build();
        Job job = new jeju.datahub.batch.rptp.ReportAutoCrtJobConfig().reportAutoCrtJob(f.repo, step, f.listener(), f.running);
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(f.repo);
        launcher.afterPropertiesSet();
        JobExecution e = launcher.run(job, JobParameterFactory.base().toJobParameters());
        assertThat(seen.get("step")).isEqualTo(String.valueOf(e.getId()));
        assertThat(f.running.isRunning(e.getId())).isFalse();
    }
}
