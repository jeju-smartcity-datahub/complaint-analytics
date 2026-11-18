package jeju.datahub.batch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import jeju.datahub.batch.cvlcpt.ComplaintSyncScheduler;
import jeju.datahub.batch.pblpklt.PblpkltSyncScheduler;
import jeju.datahub.batch.schedule.BatchScheduleRegistry;
import jeju.datahub.batch.schedule.BatchScheduleRepository;
import jeju.datahub.batch.schedule.BatchSchedulerConfig;

/**
 * 카탈로그가 두 벌로 가진 것 — 연쇄({@code chainNext})·스케줄 가능 여부 — 가 실제 코드와 같은지 (TSK-4337 Step 4).
 * Step 이름·순서 대조는 Job 빈이 필요해 {@code BatchOpsContextTest} 에 있다.
 */
class BatchJobCatalogTest {

    private final BatchJobCatalog catalog = new BatchJobCatalog();

    @Test
    @DisplayName("잡 10개 · 이름 중복 없음 · 파라미터 명세가 기존 트리거 파라미터와 같다")
    void tenJobs() {
        assertThat(catalog.all()).extracting(BatchJobCatalog.JobSpec::jobNm).containsExactly(
                "complaintFetchJob", "riskAlertJudgeJob", "riskAlertReceiverBackfillJob", "pblpkltFetchJob",
                "pkltUtztnFetchJob", "crackdownFetchJob", "reportAutoCrtJob", "pkltSftyChckFetchJob",
                "zoneNmPrefillJob", "pkltAcdntFetchJob");
        Map<String, List<String>> params = catalog.all().stream().collect(Collectors.toMap(
                BatchJobCatalog.JobSpec::jobNm, j -> j.params().stream().map(BatchJobCatalog.ParamSpec::name).toList()));
        assertThat(params.get("complaintFetchJob")).containsExactly("mode", "targetDate", "from", "to");
        assertThat(params.get("riskAlertJudgeJob")).containsExactly("targetDate");
        assertThat(params.get("riskAlertReceiverBackfillJob")).containsExactly("fromDt", "toDt");
        assertThat(params.get("reportAutoCrtJob")).containsExactly("baseYmd");
        assertThat(params.get("zoneNmPrefillJob")).containsExactly("gridSz");
        assertThat(params.get("crackdownFetchJob")).containsExactly("from", "to");   // TSK-4316
    }

    @Test
    @DisplayName("🔴 chainNext = 스케줄러가 COMPLETED 뒤 실제로 부르는 잡 (민원 → 위험판정 · 주차장 → 이용패턴)")
    void chainNextMatchesSchedulers() throws Exception {
        assertThat(chainedBy("complaintFetchJob", (launcher, head, next) ->
                new ComplaintSyncScheduler(launcher, head, next, true).syncMonthly(), "riskAlertJudgeJob"))
                .isEqualTo(catalog.find("complaintFetchJob").orElseThrow().chainNext());
        assertThat(chainedBy("pblpkltFetchJob", (launcher, head, next) ->
                new PblpkltSyncScheduler(launcher, head, next, true).syncQuarterly(), "pkltUtztnFetchJob"))
                .isEqualTo(catalog.find("pblpkltFetchJob").orElseThrow().chainNext());
        // 🔴 Job 은 빈 이름(= 생성자 파라미터 이름)으로 주입된다 — 머리 잡이 아닌 Job 파라미터가 연쇄 대상이다.
        assertThat(otherJobParam(ComplaintSyncScheduler.class, "complaintFetchJob"))
                .isEqualTo(catalog.find("complaintFetchJob").orElseThrow().chainNext());
        assertThat(otherJobParam(PblpkltSyncScheduler.class, "pblpkltFetchJob"))
                .isEqualTo(catalog.find("pblpkltFetchJob").orElseThrow().chainNext());
        // 나머지는 연쇄가 없다.
        assertThat(catalog.all().stream().filter(j -> j.chainNext() != null).map(BatchJobCatalog.JobSpec::jobNm))
                .containsExactlyInAnyOrder("complaintFetchJob", "pblpkltFetchJob");
    }

    @Test
    @DisplayName("🔴 앞 잡이 STOPPED(중지)면 연쇄 잡을 부르지 않는다 — 민원 · 주차장")
    void stoppedHeadDoesNotChain() throws Exception {
        for (String head : List.of("complaintFetchJob", "pblpkltFetchJob")) {
            JobLauncher launcher = mock(JobLauncher.class);
            Job headJob = job(head);
            Job nextJob = job("next");
            JobExecution stopped = new JobExecution(1L);
            stopped.setStatus(BatchStatus.STOPPED);
            stopped.setExitStatus(ExitStatus.STOPPED);
            when(launcher.run(org.mockito.ArgumentMatchers.eq(headJob), any())).thenReturn(stopped);
            if (head.equals("complaintFetchJob")) {
                new ComplaintSyncScheduler(launcher, headJob, nextJob, true).syncMonthly();
            } else {
                new PblpkltSyncScheduler(launcher, headJob, nextJob, true).syncQuarterly();
            }
            org.mockito.Mockito.verify(launcher, org.mockito.Mockito.never())
                    .run(org.mockito.ArgumentMatchers.eq(nextJob), any());
        }
    }

    @Test
    @DisplayName("🔴 schedulable = 스케줄 레지스트리 대상(batch.tb_btch_schdl 8행)")
    @SuppressWarnings("unchecked")
    void schedulableMatchesRegistryTargets() {
        ObjectProvider<Object> absent = mock(ObjectProvider.class);
        BatchScheduleRegistry registry = new BatchSchedulerConfig().batchScheduleRegistry(
                new ThreadPoolTaskScheduler(), mock(BatchScheduleRepository.class), mock(Environment.class),
                (ObjectProvider) absent, (ObjectProvider) absent, (ObjectProvider) absent, (ObjectProvider) absent,
                (ObjectProvider) absent, (ObjectProvider) absent, (ObjectProvider) absent,
                (ObjectProvider) absent,
                new RunningJobRegistryListener());
        for (BatchJobCatalog.JobSpec spec : catalog.all()) {
            assertThat(registry.isTarget(spec.jobNm())).as(spec.jobNm()).isEqualTo(spec.schedulable());
        }
        assertThat(catalog.all().stream().filter(BatchJobCatalog.JobSpec::schedulable)).hasSize(8);
    }

    private static String otherJobParam(Class<?> scheduler, String head) {
        java.lang.reflect.Parameter[] params = scheduler.getConstructors()[0].getParameters();
        List<String> jobParams = java.util.Arrays.stream(params).filter(p -> p.getType() == Job.class)
                .map(java.lang.reflect.Parameter::getName).toList();
        assertThat(jobParams).as("-parameters 로 컴파일돼야 빈 이름 주입이 성립한다").contains(head).hasSize(2);
        return jobParams.stream().filter(n -> !n.equals(head)).findFirst().orElseThrow();
    }

    private interface SchedulerRun {
        void run(JobLauncher launcher, Job head, Job next) throws Exception;
    }

    /** 앞 잡을 COMPLETED 로 끝내고 스케줄러가 두 번째로 띄운 잡의 이름. */
    private static String chainedBy(String headNm, SchedulerRun run, String nextNm) throws Exception {
        JobLauncher launcher = mock(JobLauncher.class);
        Job head = job(headNm);
        Job next = job(nextNm);
        JobExecution done = new JobExecution(1L);
        done.setStatus(BatchStatus.COMPLETED);
        done.setExitStatus(ExitStatus.COMPLETED);
        when(launcher.run(any(), any())).thenReturn(done);

        run.run(launcher, head, next);

        ArgumentCaptor<Job> jobs = ArgumentCaptor.forClass(Job.class);
        org.mockito.Mockito.verify(launcher, atLeastOnce()).run(jobs.capture(), any(JobParameters.class));
        assertThat(jobs.getAllValues()).hasSize(2);
        assertThat(jobs.getAllValues().get(0).getName()).isEqualTo(headNm);
        return jobs.getAllValues().get(1).getName();
    }

    private static Job job(String name) {
        Job job = mock(Job.class);
        when(job.getName()).thenReturn(name);
        return job;
    }
}
