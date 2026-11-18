package jeju.datahub.batch.cvlcpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import jeju.datahub.batch.history.ExcnSe;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.CronExpression;

/**
 * {@link ComplaintSyncScheduler} — 크론 시각과 실행 파라미터, ①→③ 체인 분기 검증.
 *
 * <p>🔁 2026-08-24 — 원본(staging) 폐지로 ②{@code cvlcptGeocodeJob} 이 사라졌다(수집·가공이 한 Job).
 * 🔁 2026-08-26 — 그 자리에 ③{@code riskAlertJudgeJob} 이 붙어 <b>"①이 COMPLETED 일 때만
 * ③"</b> 이 다시 이 클래스의 계약이 됐다. 생성자가 <b>2인자 → 4인자</b>로 늘어난 것도 그래서다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다 — 확인 대상은 분기 로직과 애노테이션이지 빈 배선이 아니다.
 * 🔴 그래서 {@code risknoti.sync.scheduled} 플래그도 <b>생성자 인자로 직접</b> 넣는다(설정 파일로
 * 우회할 수 없다).
 */
class ComplaintSyncSchedulerTest {

    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final Job fetchJob = mock(Job.class);
    private final Job judgeJob = mock(Job.class);
    private final ComplaintSyncScheduler scheduler =
            new ComplaintSyncScheduler(jobLauncher, fetchJob, judgeJob, true);

    @Test
    @DisplayName("스케줄이 돌면 수집 Job 을 한 번 실행한다")
    void runsFetchJob() throws Exception {
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.COMPLETED));
        when(jobLauncher.run(eq(judgeJob), any())).thenReturn(execution(BatchStatus.COMPLETED));

        scheduler.syncMonthly();

        verify(jobLauncher).run(eq(fetchJob), any());
    }

    @Test
    @DisplayName("🔴 Job 이 FAILED 여도 스케줄 스레드는 살아남는다 — 다음 주기가 다시 시도한다")
    void survivesFailedJob() throws Exception {
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.FAILED));

        scheduler.syncMonthly();   // 예외가 밖으로 나가면 안 된다

        verify(jobLauncher).run(eq(fetchJob), any());
    }

    @Test
    @DisplayName("🔴 ①이 FAILED 면 ③ 판정 Job 을 돌리지 않는다 — 덜 찬 건수로 증가율이 계산된다")
    void skipsJudgeJobWhenFetchFailed() throws Exception {
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.FAILED));

        scheduler.syncMonthly();

        verify(jobLauncher, never()).run(eq(judgeJob), any());
    }

    @Test
    @DisplayName("🔴 risknoti.sync.scheduled=false 면 ①은 그대로 돌고 ③만 건너뛴다")
    void skipsJudgeJobWhenFlagOff() throws Exception {
        ComplaintSyncScheduler flagOff =
                new ComplaintSyncScheduler(jobLauncher, fetchJob, judgeJob, false);
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.COMPLETED));

        flagOff.syncMonthly();

        verify(jobLauncher).run(eq(fetchJob), any());        // ①은 플래그와 무관하게 돈다
        verify(jobLauncher, never()).run(eq(judgeJob), any());
    }

    @Test
    @DisplayName("🔴 Job 이 예외로 죽어도 스케줄 스레드는 살아남는다")
    void swallowsFetchException() throws Exception {
        when(jobLauncher.run(eq(fetchJob), any())).thenThrow(new IllegalStateException("자격증명 없음"));

        scheduler.syncMonthly();   // 예외가 밖으로 나가면 안 된다
    }

    @Test
    @DisplayName("실행 시 runAt 유니크 파라미터가 들어간다 — 없으면 2회차가 시작조차 못 한다")
    void passesUniqueRunAtParameter() throws Exception {
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(execution(BatchStatus.COMPLETED));
        when(jobLauncher.run(eq(judgeJob), any())).thenReturn(execution(BatchStatus.COMPLETED));

        scheduler.syncMonthly();

        verify(jobLauncher).run(eq(fetchJob), org.mockito.ArgumentMatchers.argThat(
                (JobParameters p) -> p.getLong("runAt") != null));
        // ③도 같은 함정을 밟는다 — 게다가 판정일까지 항상 실려야 한다(D1·D11).
        verify(jobLauncher).run(eq(judgeJob), org.mockito.ArgumentMatchers.argThat(
                (JobParameters p) -> p.getLong("runAt") != null
                        && p.getString("targetDate") != null));
    }

    @Test
    @DisplayName("①은 SCHDL, ③은 CHAIN + prntExcnId=①의 실행 ID (모두 비식별)")
    void tagsExcnSeAndParent() throws Exception {
        JobExecution fetch = execution(BatchStatus.COMPLETED);   // id = 1
        when(jobLauncher.run(eq(fetchJob), any())).thenReturn(fetch);
        when(jobLauncher.run(eq(judgeJob), any())).thenReturn(execution(BatchStatus.COMPLETED));

        scheduler.syncMonthly();

        verify(jobLauncher).run(eq(fetchJob), org.mockito.ArgumentMatchers.argThat(
                (JobParameters p) -> ExcnSe.SCHDL.equals(p.getString(ExcnSe.PARAM_EXCN_SE))
                        && !p.getParameter(ExcnSe.PARAM_EXCN_SE).isIdentifying()
                        && p.getParameter(ExcnSe.PARAM_PRNT_EXCN_ID) == null));
        verify(jobLauncher).run(eq(judgeJob), org.mockito.ArgumentMatchers.argThat(
                (JobParameters p) -> ExcnSe.CHAIN.equals(p.getString(ExcnSe.PARAM_EXCN_SE))
                        && fetch.getId().equals(p.getLong(ExcnSe.PARAM_PRNT_EXCN_ID))
                        && !p.getParameter(ExcnSe.PARAM_EXCN_SE).isIdentifying()
                        && !p.getParameter(ExcnSe.PARAM_PRNT_EXCN_ID).isIdentifying()));
    }

    @Test
    @DisplayName("🔁 @EnableScheduling·@Scheduled 가 없다 — 스케줄은 BatchScheduleRegistry 가 batch.tb_btch_schdl 로 건다")
    void hasNoAnnotationScheduling() {
        assertThat(ComplaintSyncScheduler.class.getAnnotation(EnableScheduling.class))
                .as("남아 있으면 Boot 자동 스케줄러가 다시 생기고, @Scheduled 가 있으면 레지스트리와 이중으로 돈다")
                .isNull();
        assertThat(java.util.Arrays.stream(ComplaintSyncScheduler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)))
                .isEmpty();
    }

    @Test
    @DisplayName("매월 1일 02:00(Asia/Seoul)에 수집한다")
    void cronFiresAtTwoAmSeoul() {
        ZoneId seoul = ZoneId.of(ComplaintSyncScheduler.ZONE);
        ZonedDateTime next = CronExpression.parse(ComplaintSyncScheduler.CRON)
                .next(ZonedDateTime.of(LocalDate.parse("2026-08-05").atTime(3, 0), seoul));

        assertThat(next).isNotNull();
        assertThat(next.getHour()).isEqualTo(2);
        assertThat(next.getMinute()).isZero();
        // 03:00 기준이면 "당일 02:00"은 이미 지났으므로 다음 날이다.
        assertThat(next.toLocalDate()).isEqualTo(LocalDate.parse("2026-09-01"));
    }

    private static JobExecution execution(BatchStatus status) {
        JobExecution execution = new JobExecution(1L);
        execution.setStatus(status);
        execution.setExitStatus(status == BatchStatus.COMPLETED
                ? ExitStatus.COMPLETED : ExitStatus.FAILED);
        return execution;
    }
}
