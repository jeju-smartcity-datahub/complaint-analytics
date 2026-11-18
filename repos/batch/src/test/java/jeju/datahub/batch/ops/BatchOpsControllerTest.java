package jeju.datahub.batch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jeju.datahub.batch.history.ExcnSe;
import jeju.datahub.batch.schedule.BatchScheduleRegistry;
import jeju.datahub.batch.schedule.BatchScheduleRepository;
import jeju.datahub.batch.schedule.ScheduleState;

/**
 * 운영 API — HTTP 상태·본문 계약. 컨트롤러 + 실 서비스 + 실 카탈로그 + 실 실행 레지스트리,
 * 배치 인프라(런처·탐색기·저장소·오퍼레이터·스케줄)만 대역이다. DB·외부 API 없음.
 */
class BatchOpsControllerTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private final AsyncJobLauncher launcher = mock(AsyncJobLauncher.class);
    private final JobExplorer explorer = mock(JobExplorer.class);
    private final JobRepository repository = mock(JobRepository.class);
    private final JobOperator operator = mock(JobOperator.class);
    private final BatchScheduleRepository scheduleRepository = mock(BatchScheduleRepository.class);
    private final BatchScheduleRegistry scheduleRegistry = mock(BatchScheduleRegistry.class);
    private final RunningJobRegistryListener running = new RunningJobRegistryListener();
    private final BatchJobCatalog catalog = new BatchJobCatalog();
    private final JobLiveMetrics liveMetrics = new JobLiveMetrics();
    private BatchOpsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        List<Job> jobs = new ArrayList<>();
        for (BatchJobCatalog.JobSpec spec : catalog.all()) {
            Job job = mock(Job.class);
            when(job.getName()).thenReturn(spec.jobNm());
            jobs.add(job);
        }
        service = new BatchOpsService(catalog, jobs, launcher, explorer, repository, operator, running,
                scheduleRepository, scheduleRegistry);
        mvc = MockMvcBuilders.standaloneSetup(new BatchOpsController(service,
                        new JobLogService(explorer, running, "build/tmp/batch-ops-controller-test-logs"), liveMetrics))
                .setControllerAdvice(new BatchOpsExceptionHandler()).build();
        when(scheduleRepository.findAllDetails()).thenReturn(List.of());
        when(explorer.findRunningJobExecutions(anyString())).thenReturn(Set.of());
        for (String t : List.of("complaintFetchJob", "pblpkltFetchJob", "pkltUtztnFetchJob", "reportAutoCrtJob",
                "pkltSftyChckFetchJob", "zoneNmPrefillJob", "crackdownFetchJob", "pkltAcdntFetchJob")) {
            when(scheduleRegistry.isTarget(t)).thenReturn(true);
        }
    }

    private static JobExecution execution(long id, String jobNm, BatchStatus status) {
        JobExecution e = new JobExecution(new JobInstance(id, jobNm), id, new JobParameters());
        e.setStatus(status);
        return e;
    }

    @Test
    @DisplayName("GET jobs — 카탈로그 10개 배열 · 파라미터 명세 · 스케줄 불가 잡은 schedule=null")
    void jobs() throws Exception {
        mvc.perform(get("/api/batch/ops/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].jobNm").value("complaintFetchJob"))
                .andExpect(jsonPath("$[0].chainNext").value("riskAlertJudgeJob"))
                .andExpect(jsonPath("$[0].params[0].name").value("mode"))
                .andExpect(jsonPath("$[1].jobNm").value("riskAlertJudgeJob"))
                .andExpect(jsonPath("$[1].schedule").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].schedule.registered").value(false));
    }

    @Test
    @DisplayName("POST executions — 202 + jobExecutionId · MANUAL + 실행자 · 파라미터 전달")
    void execute202() throws Exception {
        when(launcher.run(any(), any())).thenReturn(execution(77, "reportAutoCrtJob", BatchStatus.STARTING));

        mvc.perform(post("/api/batch/ops/jobs/reportAutoCrtJob/executions").contentType(JSON)
                        .content("{\"params\":{\"baseYmd\":\"2026-11-01\"},\"excutrId\":\"user01\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").value(77))
                .andExpect(jsonPath("$.jobNm").value("reportAutoCrtJob"));

        ArgumentCaptor<JobParameters> params = ArgumentCaptor.forClass(JobParameters.class);
        verify(launcher).run(any(), params.capture());
        assertThat(params.getValue().getString("baseYmd")).isEqualTo("2026-11-01");
        assertThat(params.getValue().getString(ExcnSe.PARAM_EXCN_SE)).isEqualTo(ExcnSe.MANUAL);
        assertThat(params.getValue().getString(ExcnSe.PARAM_EXCUTR_ID)).isEqualTo("user01");
        assertThat(params.getValue().getLong("runAt")).isNotNull();
    }

    @Test
    @DisplayName("POST executions — 본문 없어도 202(기본값) · zoneNm gridSz 숫자도 받는다")
    void executeNoBodyAndGridSz() throws Exception {
        when(launcher.run(any(), any())).thenReturn(execution(5, "x", BatchStatus.STARTING));
        mvc.perform(post("/api/batch/ops/jobs/crackdownFetchJob/executions")).andExpect(status().isAccepted());
        mvc.perform(post("/api/batch/ops/jobs/zoneNmPrefillJob/executions").contentType(JSON)
                .content("{\"params\":{\"gridSz\":250}}")).andExpect(status().isAccepted());
        ArgumentCaptor<JobParameters> params = ArgumentCaptor.forClass(JobParameters.class);
        verify(launcher, org.mockito.Mockito.times(2)).run(any(), params.capture());
        assertThat(params.getAllValues().get(1).getLong("gridSz")).isEqualTo(250L);
    }

    @Test
    @DisplayName("POST executions — 실행 중이면 409 JOB_ALREADY_RUNNING + 그 ID + zombie(레지스트리 기준)")
    void execute409() throws Exception {
        JobExecution live = execution(10, "crackdownFetchJob", BatchStatus.STARTED);
        when(explorer.findRunningJobExecutions("crackdownFetchJob")).thenReturn(Set.of(live));
        running.beforeJob(live);

        mvc.perform(post("/api/batch/ops/jobs/crackdownFetchJob/executions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.jobExecutionId").value(10))
                .andExpect(jsonPath("$.zombie").value(false));

        running.afterJob(live);   // 프로세스가 죽은 것과 같다 — DB 만 STARTED
        mvc.perform(post("/api/batch/ops/jobs/crackdownFetchJob/executions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.zombie").value(true));
        verify(launcher, never()).run(any(), any());
    }

    @Test
    @DisplayName("POST executions — 400 화이트리스트 밖 키 · 날짜 형식 · enum 값 / 404 없는 잡")
    void execute400and404() throws Exception {
        mvc.perform(post("/api/batch/ops/jobs/crackdownFetchJob/executions").contentType(JSON)
                        .content("{\"params\":{\"runAt\":\"1\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(post("/api/batch/ops/jobs/reportAutoCrtJob/executions").contentType(JSON)
                        .content("{\"params\":{\"baseYmd\":\"2026/11/01\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(post("/api/batch/ops/jobs/reportAutoCrtJob/executions").contentType(JSON)
                        .content("{\"params\":{\"baseYmd\":\"2026-02-30\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/batch/ops/jobs/complaintFetchJob/executions").contentType(JSON)
                        .content("{\"params\":{\"mode\":\"weekly\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/batch/ops/jobs/zoneNmPrefillJob/executions").contentType(JSON)
                        .content("{\"params\":{\"gridSz\":\"300\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/batch/ops/jobs/cvlcptGeocodeJob/executions"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        mvc.perform(post("/api/batch/ops/jobs/crackdownFetchJob/executions").contentType(JSON)
                        .content("{\"excutrId\":\"abcdefghijklmnopqrstuvwxyz\"}"))
                .andExpect(status().isBadRequest());
        verify(launcher, never()).run(any(), any());
    }

    @Test
    @DisplayName("PUT schedules — 200 저장·재등록·다음 3회")
    void schedule200() throws Exception {
        when(scheduleRegistry.reschedule("reportAutoCrtJob")).thenReturn(ScheduleState.REGISTERED);

        mvc.perform(put("/api/batch/ops/schedules/reportAutoCrtJob").contentType(JSON)
                        .content("{\"cronExpr\":\"0 0 5 * * *\",\"useYn\":\"Y\",\"mdfrId\":\"tester\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.blockedByEnv").value(false))
                .andExpect(jsonPath("$.nextRuns.length()").value(3));
        verify(scheduleRepository).save("reportAutoCrtJob", "0 0 5 * * *", "Y", "tester");
    }

    @Test
    @DisplayName("PUT schedules — env 차단 잡도 표에는 저장 · {registered:false, blockedByEnv:true, nextRuns:[]}")
    void scheduleBlockedByEnv() throws Exception {
        when(scheduleRegistry.reschedule("reportAutoCrtJob")).thenReturn(ScheduleState.DISABLED);
        when(scheduleRegistry.isBlockedByEnv("reportAutoCrtJob")).thenReturn(true);

        mvc.perform(put("/api/batch/ops/schedules/reportAutoCrtJob").contentType(JSON)
                        .content("{\"cronExpr\":\"0 0 5 * * *\",\"useYn\":\"N\",\"mdfrId\":\"tester\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(false))
                .andExpect(jsonPath("$.blockedByEnv").value(true))
                .andExpect(jsonPath("$.nextRuns.length()").value(0));
        verify(scheduleRepository).save("reportAutoCrtJob", "0 0 5 * * *", "N", "tester");
    }

    @Test
    @DisplayName("PUT schedules — 400 잘못된 cron(env 차단 잡이어도) · useYn · 스케줄 불가 잡 / 저장 안 함")
    void schedule400() throws Exception {
        when(scheduleRegistry.isBlockedByEnv("reportAutoCrtJob")).thenReturn(true);
        mvc.perform(put("/api/batch/ops/schedules/reportAutoCrtJob").contentType(JSON)
                        .content("{\"cronExpr\":\"0 0 5 * *\",\"useYn\":\"Y\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CRON"));
        mvc.perform(put("/api/batch/ops/schedules/reportAutoCrtJob").contentType(JSON)
                        .content("{\"cronExpr\":\"0 0 5 * * *\",\"useYn\":\"X\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_USE_YN"));
        mvc.perform(put("/api/batch/ops/schedules/riskAlertJudgeJob").contentType(JSON)
                        .content("{\"cronExpr\":\"0 0 5 * * *\",\"useYn\":\"Y\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SCHEDULE_NOT_SUPPORTED"));
        mvc.perform(put("/api/batch/ops/schedules/riskAlertReceiverBackfillJob").contentType(JSON)
                        .content("{\"cronExpr\":\"0 0 5 * * *\",\"useYn\":\"Y\"}"))
                .andExpect(status().isBadRequest());
        verify(scheduleRepository, never()).save(anyString(), anyString(), anyString(), any());
        verify(scheduleRegistry, never()).reschedule(anyString());
    }

    @Test
    @DisplayName("stop — 202 STOPPING(JobOperator.stop) · 끝난 실행 409 NOT_RUNNING · 없는 실행 404")
    void stop() throws Exception {
        JobExecution live = execution(20, "crackdownFetchJob", BatchStatus.STARTED);
        running.beforeJob(live);
        when(explorer.getJobExecution(20L)).thenReturn(live);
        mvc.perform(post("/api/batch/ops/executions/20/stop"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STOPPING"))
                .andExpect(jsonPath("$.zombie").value(false));
        verify(operator).stop(20L);

        when(explorer.getJobExecution(21L)).thenReturn(execution(21, "crackdownFetchJob", BatchStatus.COMPLETED));
        mvc.perform(post("/api/batch/ops/executions/21/stop"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_RUNNING"));

        mvc.perform(post("/api/batch/ops/executions/999/stop")).andExpect(status().isNotFound());
        mvc.perform(post("/api/batch/ops/executions/abc/stop")).andExpect(status().isBadRequest());
        verify(operator, never()).stop(21L);
    }

    @Test
    @DisplayName("abandon — 좀비면 200 FAILED(Job·실행 중 Step + endTime + 메시지) · 실제 실행 중이면 409 STILL_RUNNING")
    void abandon() throws Exception {
        JobExecution zombie = execution(30, "crackdownFetchJob", BatchStatus.STARTED);
        StepExecution done = zombie.createStepExecution("crackdownFetchStep");
        done.setStatus(BatchStatus.COMPLETED);
        StepExecution stuck = zombie.createStepExecution("crackdownCoordDictStep");
        stuck.setStatus(BatchStatus.STARTED);
        when(explorer.getJobExecution(30L)).thenReturn(zombie);

        mvc.perform(post("/api/batch/ops/executions/30/abandon").contentType(JSON).content("{\"excutrId\":\"admin1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.exitMessage").value("운영 화면에서 실패 처리 — admin1"));
        assertThat(zombie.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(zombie.getEndTime()).isNotNull();
        assertThat(zombie.getExitStatus().getExitDescription()).contains("admin1");
        assertThat(stuck.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stuck.getEndTime()).isNotNull();
        assertThat(done.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(repository).update(zombie);
        verify(repository).update(stuck);
        verify(repository, never()).update(done);

        JobExecution live = execution(31, "crackdownFetchJob", BatchStatus.STARTED);
        running.beforeJob(live);
        when(explorer.getJobExecution(31L)).thenReturn(live);
        mvc.perform(post("/api/batch/ops/executions/31/abandon"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STILL_RUNNING"));
        assertThat(live.getStatus()).isEqualTo(BatchStatus.STARTED);

        when(explorer.getJobExecution(32L)).thenReturn(execution(32, "crackdownFetchJob", BatchStatus.COMPLETED));
        mvc.perform(post("/api/batch/ops/executions/32/abandon"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_RUNNING"));
    }

    @Test
    @DisplayName("🔴 동기 런처(스케줄러·기존 트리거 경로)로 도는 실행도 레지스트리가 잡는다 — 실행 중 abandon 은 409")
    void abandonRejectedDuringSyncRun() throws Exception {
        JobRepository repo = new ResourcelessJobRepository();
        AtomicReference<Integer> statusSeen = new AtomicReference<>();
        Job job = new JobBuilder("crackdownFetchJob", repo)
                .listener(running)
                .start(new StepBuilder("crackdownFetchStep", repo).tasklet((contribution, ctx) -> {
                    JobExecution self = ctx.getStepContext().getStepExecution().getJobExecution();
                    when(explorer.getJobExecution(self.getId())).thenReturn(self);
                    int code = mvc.perform(post("/api/batch/ops/executions/" + self.getId() + "/abandon"))
                            .andReturn().getResponse().getStatus();
                    statusSeen.set(code);
                    return RepeatStatus.FINISHED;
                }, new ResourcelessTransactionManager()).build())
                .build();
        TaskExecutorJobLauncher sync = new TaskExecutorJobLauncher();
        sync.setJobRepository(repo);
        sync.afterPropertiesSet();

        JobExecution result = sync.run(job, JobParameterFactory.base().toJobParameters());

        assertThat(statusSeen.get()).isEqualTo(409);
        assertThat(result.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(running.isRunning(result.getId())).isFalse();
        verify(repository, never()).update(any(JobExecution.class));
        verify(operator, never()).stop(anyLong());
    }

    @Test
    @DisplayName("후속 — stop·abandon 의 version 충돌은 409 CONCURRENT_UPDATE · excutrId 20자 초과는 400")
    void concurrentUpdateAndRequesterLength() throws Exception {
        JobExecution zombie = execution(40, "crackdownFetchJob", BatchStatus.STARTED);
        when(explorer.getJobExecution(40L)).thenReturn(zombie);
        org.mockito.Mockito.doThrow(new org.springframework.dao.OptimisticLockingFailureException("version"))
                .when(repository).update(any(JobExecution.class));
        mvc.perform(post("/api/batch/ops/executions/40/abandon"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));

        JobExecution live = execution(41, "crackdownFetchJob", BatchStatus.STARTED);
        when(explorer.getJobExecution(41L)).thenReturn(live);
        when(operator.stop(41L)).thenThrow(new org.springframework.dao.OptimisticLockingFailureException("version"));
        mvc.perform(post("/api/batch/ops/executions/41/stop"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));

        String tooLong = "{\"excutrId\":\"abcdefghijklmnopqrstuvwxyz\"}";
        mvc.perform(post("/api/batch/ops/executions/41/stop").contentType(JSON).content(tooLong))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EXCUTRID"));
        mvc.perform(post("/api/batch/ops/executions/40/abandon").contentType(JSON).content(tooLong))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EXCUTRID"));
    }

    @Test
    @DisplayName("추가 — GET live: 공급자 등록 중 200 {jobExecutionId, metrics} · 해제·미등록·공급자 예외는 404 LIVE_NOT_AVAILABLE")
    void liveMetrics() throws Exception {
        liveMetrics.register(50, () -> java.util.Map.of("fetchCnt", 1200L, "dayIdx", 3));
        mvc.perform(get("/api/batch/ops/executions/50/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobExecutionId").value(50))
                .andExpect(jsonPath("$.metrics.fetchCnt").value(1200))
                .andExpect(jsonPath("$.metrics.dayIdx").value(3));

        liveMetrics.unregister(50);
        mvc.perform(get("/api/batch/ops/executions/50/live"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LIVE_NOT_AVAILABLE"));

        liveMetrics.register(51, () -> { throw new IllegalStateException("boom"); });
        mvc.perform(get("/api/batch/ops/executions/51/live"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LIVE_NOT_AVAILABLE"));
    }
}
