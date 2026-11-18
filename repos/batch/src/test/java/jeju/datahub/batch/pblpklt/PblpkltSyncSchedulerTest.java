package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jeju.datahub.batch.history.ExcnSe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주차장 → 이용패턴 <b>연쇄</b> 회귀.
 *
 * <p>선례는 {@code ComplaintSyncSchedulerTest} 다 — 스프링 컨텍스트를 띄우지 않고
 * {@code new PblpkltSyncScheduler(...)} 로 분기만 단언한다(그래서 플래그가 생성자 파라미터여야 한다).
 *
 * <p>🔴 이 연쇄가 막으려는 것은 부모 표 전량 재적재 뒤 <b>이용패턴 재실행을 깜빡하는 것</b> 하나다.
 * 그 사고만 구조적으로 없애고, 나머지(수동 트리거 경로)는 런북의 건수 확인이 진다.
 */
class PblpkltSyncSchedulerTest {

    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final Job fetchJob = mock(Job.class);
    private final Job utztnJob = mock(Job.class);

    private final PblpkltSyncScheduler scheduler =
            new PblpkltSyncScheduler(jobLauncher, fetchJob, utztnJob, true);

    @Test
    @DisplayName("주차장 수집이 COMPLETED 면 이용패턴을 잇는다")
    void chainsUtztnWhenFetchCompleted() throws Exception {
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.COMPLETED));
        when(jobLauncher.run(eq(utztnJob), any())).thenReturn(execution(BatchStatus.COMPLETED));

        scheduler.syncQuarterly();

        verify(jobLauncher).run(eq(utztnJob), any());
    }

    @Test
    @DisplayName("🔴 주차장 수집이 FAILED 면 잇지 않는다 — 부분 수집분으로 매칭하면 대량 미해석이다")
    void skipsUtztnWhenFetchFailed() throws Exception {
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.FAILED));

        scheduler.syncQuarterly();

        verify(jobLauncher, never()).run(eq(utztnJob), any());
    }

    @Test
    @DisplayName("🔴 utztn.sync.scheduled=false 면 주차장은 그대로 돌고 연쇄만 건너뛴다")
    void skipsUtztnWhenFlagOff() throws Exception {
        PblpkltSyncScheduler flagOff =
                new PblpkltSyncScheduler(jobLauncher, fetchJob, utztnJob, false);
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.COMPLETED));

        flagOff.syncQuarterly();

        verify(jobLauncher).run(eq(fetchJob), any());
        verify(jobLauncher, never()).run(eq(utztnJob), any());
    }

    @Test
    @DisplayName("🔴 두 Job 모두 runAt 유니크 파라미터를 받는다 — 없으면 2회차부터 조용히 안 돈다")
    void passesUniqueRunAtParameter() throws Exception {
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.COMPLETED));
        when(jobLauncher.run(eq(utztnJob), any())).thenReturn(execution(BatchStatus.COMPLETED));

        scheduler.syncQuarterly();

        verify(jobLauncher).run(eq(fetchJob), org.mockito.ArgumentMatchers.argThat(
                (JobParameters p) -> p.getLong("runAt") != null));
        verify(jobLauncher).run(eq(utztnJob), org.mockito.ArgumentMatchers.argThat(
                (JobParameters p) -> p.getLong("runAt") != null));
    }

    @Test
    @DisplayName("🔴 연쇄가 예외를 던져도 스케줄 스레드로 새지 않는다 — 삼키고 ERROR 만 남긴다")
    void swallowsChainException() throws Exception {
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.COMPLETED));
        when(jobLauncher.run(eq(utztnJob), any())).thenThrow(new IllegalStateException("boom"));

        scheduler.syncQuarterly(); // 예외가 나가면 이 줄에서 테스트가 깨진다
    }

    @Test
    @DisplayName("주차장은 SCHDL, 이용패턴 연쇄는 CHAIN + prntExcnId=주차장 실행 ID (모두 비식별)")
    void tagsExcnSeAndParent() throws Exception {
        JobExecution fetch = new JobExecution(55L);
        fetch.setStatus(BatchStatus.COMPLETED);
        fetch.setExitStatus(ExitStatus.COMPLETED);
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(fetch);
        when(jobLauncher.run(eq(utztnJob), any())).thenReturn(execution(BatchStatus.COMPLETED));

        scheduler.syncQuarterly();

        verify(jobLauncher).run(eq(fetchJob), org.mockito.ArgumentMatchers.argThat(
                (JobParameters p) -> ExcnSe.SCHDL.equals(p.getString(ExcnSe.PARAM_EXCN_SE))
                        && !p.getParameter(ExcnSe.PARAM_EXCN_SE).isIdentifying()));
        verify(jobLauncher).run(eq(utztnJob), org.mockito.ArgumentMatchers.argThat(
                (JobParameters p) -> ExcnSe.CHAIN.equals(p.getString(ExcnSe.PARAM_EXCN_SE))
                        && Long.valueOf(55L).equals(p.getLong(ExcnSe.PARAM_PRNT_EXCN_ID))
                        && !p.getParameter(ExcnSe.PARAM_PRNT_EXCN_ID).isIdentifying()));
    }

    @Test
    @DisplayName("🔁 @EnableScheduling·@Scheduled 가 없다 — 스케줄은 BatchScheduleRegistry 가 batch.tb_btch_schdl 로 건다")
    void hasNoAnnotationScheduling() {
        assertThat(PblpkltSyncScheduler.class.getAnnotation(EnableScheduling.class))
                .as("남아 있으면 Boot 자동 스케줄러가 다시 생기고, @Scheduled 가 있으면 레지스트리와 이중으로 돈다")
                .isNull();
        assertThat(java.util.Arrays.stream(PblpkltSyncScheduler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)))
                .isEmpty();
    }

    private static JobExecution execution(BatchStatus status) {
        JobExecution execution = new JobExecution(1L);
        execution.setStatus(status);
        execution.setExitStatus(status == BatchStatus.COMPLETED
                ? ExitStatus.COMPLETED : ExitStatus.FAILED);
        return execution;
    }
}
