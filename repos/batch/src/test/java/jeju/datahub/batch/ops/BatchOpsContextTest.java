package jeju.datahub.batch.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 운영 API 의 배선 — 실 컨텍스트 (TSK-4337 Step 4).
 *
 * <ul>
 *   <li>카탈로그 Step 이름·순서 = 실제 Job 의 Step (진행 단계 표시가 이 순서를 쓴다)</li>
 *   <li>🔴 {@code JobLauncher} 빈은 여전히 하나(기본 동기) — 비동기 런처가 기존 주입을 흔들지 않는다</li>
 *   <li>{@code JobOperator} 빈 존재 · {@code JobRegistry} 에 10개 등록(중지 시 WARN 방지)</li>
 *   <li>🔴 키 필터({@code /api/batch/*})가 {@code /api/batch/ops/**} 하위 경로까지 덮는다 — 키 없으면 401</li>
 * </ul>
 * 잡은 실행하지 않는다(GET 만).
 *
 * <p>⚠️ 속성이 달라 캐시된 컨텍스트를 하나 더 만든다 — 로컬 PostgreSQL 연결 수(max_connections)를 넘기지 않게 풀을 2로
 * 줄이고 {@link DirtiesContext} 로 클래스가 끝나면 닫는다(없으면 뒤 통합 테스트가 {@code too many clients} 로 기동 실패).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@DirtiesContext
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "batch.api.auth.enabled=true",
        "batch.api.auth.key=ops-test-key",
        // 테스트 컨텍스트에 실 스케줄이 등록될 필요는 없다(env 차단 → 스케줄러 빈 없음).
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
        "acdnt.sync.scheduled=false",
        "rptp.sync.scheduled=false",
        "zonenm.sync.scheduled=false",
        "risknoti.sync.scheduled=false",
})
class BatchOpsContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private BatchJobCatalog catalog;

    @Autowired
    private TestRestTemplate rest;

    /** 중지 실측용 임시 잡 이름 — 끝나면 메타 행을 지운다. */
    private static final String PROBE_JOB = "opsStopProbeJob";
    private static JdbcTemplate cleanupJdbc;

    @Test
    @DisplayName("카탈로그 Step 이름·순서 = 실제 Job 의 Step")
    void stepsMatchJobs() {
        Map<String, Job> jobs = context.getBeansOfType(Job.class);
        assertThat(jobs.values()).extracting(Job::getName)
                .containsExactlyInAnyOrderElementsOf(catalog.all().stream().map(BatchJobCatalog.JobSpec::jobNm).toList());
        for (Job job : jobs.values()) {
            BatchJobCatalog.JobSpec spec = catalog.find(job.getName()).orElseThrow();
            assertThat(((StepLocator) job).getStepNames()).as(job.getName())
                    .containsExactlyElementsOf(spec.steps().stream().map(BatchJobCatalog.StepSpec::stepNm).toList());
        }
    }

    @Test
    @DisplayName("🔴 JobLauncher 빈은 하나(기본 동기) · JobOperator 존재 · JobRegistry 10개")
    void launcherOperatorRegistry() throws Exception {
        assertThat(context.getBeansOfType(JobLauncher.class)).hasSize(1);
        assertThat(JobLauncher.class.isAssignableFrom(AsyncJobLauncher.class)).isFalse();
        assertThat(context.getBean(JobOperator.class)).isNotNull();
        assertThat(context.getBean(JobRegistry.class).getJobNames())
                .containsAll(catalog.all().stream().map(BatchJobCatalog.JobSpec::jobNm).toList());
    }

    @Test
    @DisplayName("🔴 키 필터가 /api/batch/ops 하위 경로까지 덮는다 — 키 없음·틀림 401 · 맞으면 200 + 10개")
    void apiKeyCoversOpsPaths() {
        assertThat(rest.getForEntity("/api/batch/ops/jobs", String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(rest.exchange("/api/batch/ops/jobs/crackdownFetchJob/executions", HttpMethod.POST,
                HttpEntity.EMPTY, String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(rest.exchange("/api/batch/ops/executions/1/abandon", HttpMethod.POST,
                HttpEntity.EMPTY, String.class).getStatusCode().value()).isEqualTo(401);

        HttpHeaders wrong = new HttpHeaders();
        wrong.set("X-API-Key", "nope");
        assertThat(rest.exchange("/api/batch/ops/jobs", HttpMethod.GET, new HttpEntity<>(wrong), String.class)
                .getStatusCode().value()).isEqualTo(401);

        HttpHeaders ok = new HttpHeaders();
        ok.set("X-API-Key", "ops-test-key");
        ResponseEntity<Object[]> res = rest.exchange("/api/batch/ops/jobs", HttpMethod.GET, new HttpEntity<>(ok),
                Object[].class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).hasSize(10);
    }

    @Test
    @DisplayName("🔴 실 JobRepository·JobOperator — 비동기 실행 중 stop → 다음 반복 경계에서 STOPPED · 레지스트리 해제")
    void stopRealExecution(@Autowired JobRepository repository, @Autowired PlatformTransactionManager tx,
            @Autowired AsyncJobLauncher launcher, @Autowired BatchOpsService service,
            @Autowired JobExplorer explorer, @Autowired RunningJobRegistryListener running,
            @Autowired JdbcTemplate jdbc) throws Exception {
        cleanupJdbc = jdbc;
        AtomicInteger loops = new AtomicInteger();
        Job probe = new JobBuilder(PROBE_JOB, repository)
                .listener(running)
                .start(new StepBuilder("opsStopProbeStep", repository).tasklet((contribution, ctx) -> {
                    loops.incrementAndGet();
                    Thread.sleep(200);
                    return loops.get() < 300 ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;   // 최대 60초
                }, tx).build())
                .build();

        JobExecution started = launcher.run(probe, JobParameterFactory.base().toJobParameters());
        long id = started.getId();
        waitUntil(() -> running.isRunning(id) && loops.get() >= 2);

        assertThat(service.stop(id, "tester")).containsEntry("status", "STOPPING").containsEntry("zombie", false);
        waitUntil(() -> explorer.getJobExecution(id).getStatus() == BatchStatus.STOPPED);

        assertThat(loops.get()).isLessThan(300);
        assertThat(running.isRunning(id)).isFalse();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("30초 안에 조건 미충족").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    /** 임시 잡의 Spring Batch 메타 행 정리(참조 역순). */
    @AfterAll
    static void cleanupProbe() {
        if (cleanupJdbc == null) {
            return;
        }
        String ids = "SELECT e.job_execution_id FROM batch.batch_job_execution e JOIN batch.batch_job_instance i "
                + "ON i.job_instance_id = e.job_instance_id WHERE i.job_name = '" + PROBE_JOB + "'";
        cleanupJdbc.update("DELETE FROM batch.batch_step_execution_context WHERE step_execution_id IN "
                + "(SELECT step_execution_id FROM batch.batch_step_execution WHERE job_execution_id IN (" + ids + "))");
        cleanupJdbc.update("DELETE FROM batch.batch_step_execution WHERE job_execution_id IN (" + ids + ")");
        cleanupJdbc.update("DELETE FROM batch.batch_job_execution_context WHERE job_execution_id IN (" + ids + ")");
        cleanupJdbc.update("DELETE FROM batch.batch_job_execution_params WHERE job_execution_id IN (" + ids + ")");
        cleanupJdbc.update("DELETE FROM batch.batch_job_execution WHERE job_execution_id IN (" + ids + ")");
        cleanupJdbc.update("DELETE FROM batch.batch_job_instance WHERE job_name = '" + PROBE_JOB + "'");
    }
}
