package jeju.datahub.batch.rptp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
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
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.CronExpression;

/**
 * {@link ReportAutoCrtScheduler} — 크론 시각·실행 파라미터·예외 격리, 그리고
 * <b>스케줄 플래그 off 시 빈 자체가 빠지는지</b> (케이스 11).
 *
 * <p>스프링 컨텍스트를 통째로 띄우지 않는다 — 확인 대상은 분기 로직과 애노테이션이지 빈 배선이 아니다.
 * 다만 <b>케이스 11 만은 조건부 등록이 검증 대상</b>이라 {@link ApplicationContextRunner} 로
 * 가벼운 컨텍스트를 세 번 띄운다(플래그 true / false / 미지정).
 */
class ReportAutoCrtSchedulerTest {

    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final Job reportAutoCrtJob = mock(Job.class);
    private final ReportAutoCrtScheduler scheduler =
            new ReportAutoCrtScheduler(jobLauncher, reportAutoCrtJob);

    // ------------------------------------------------------------------ 케이스 11

    /**
     * 🔴 케이스 11. {@code @ConditionalOnProperty} 는 <b>빈 자체를 빼는</b> 장치다 —
     * "스케줄러는 등록되는데 메서드 안에서 플래그를 보고 return" 하는 구조가 아니다.
     * 🔁 그래야 BatchScheduleRegistry 가 빈 부재를 env 상위 차단으로 보고 등록하지 않는다.
     */
    @Test
    @DisplayName("11. 🔴 rptp.sync.scheduled=false 면 스케줄러 빈 자체가 컨텍스트에 없다")
    void schedulerBeanAbsentWhenFlagOff() {
        contextRunner().withPropertyValues("rptp.sync.scheduled=false",
"utztn.sync.scheduled=false")
                .run(context -> assertThat(context)
                        .as("플래그를 끄면 빈이 아예 만들어지지 않아야 한다 "
                                + "(등록해 두고 메서드에서 return 하면 크론 스레드가 계속 뜬다)")
                        .doesNotHaveBean(ReportAutoCrtScheduler.class));
    }

    @Test
    @DisplayName("11-1. rptp.sync.scheduled=true 면 스케줄러 빈이 하나 등록된다")
    void schedulerBeanPresentWhenFlagOn() {
        contextRunner().withPropertyValues("rptp.sync.scheduled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(ReportAutoCrtScheduler.class));
    }

    @Test
    @DisplayName("11-2. 🔴 플래그를 아예 안 적으면 켜진 것으로 본다 — matchIfMissing=true")
    void schedulerBeanPresentWhenFlagMissing() {
        contextRunner().run(context -> assertThat(context)
                .as("운영 기본값이 '켜짐' 이다 — 설정을 빠뜨려 보고서가 조용히 안 만들어지면 안 된다")
                .hasSingleBean(ReportAutoCrtScheduler.class));
    }

    // ------------------------------------------------------------------ 곁가지 계약

    @Test
    @DisplayName("스케줄이 돌면 reportAutoCrtJob 을 한 번 실행한다")
    void runsJob() throws Exception {
        when(jobLauncher.run(eq(reportAutoCrtJob), any())).thenReturn(execution(BatchStatus.COMPLETED));

        scheduler.syncDaily();

        verify(jobLauncher).run(eq(reportAutoCrtJob), any());
    }

    @Test
    @DisplayName("🔴 runAt(유니크) 과 baseYmd(기준일)가 항상 실린다 — baseYmd 가 없으면 Job 이 시작조차 못 한다")
    void passesRunAtAndBaseYmd() throws Exception {
        when(jobLauncher.run(eq(reportAutoCrtJob), any())).thenReturn(execution(BatchStatus.COMPLETED));
        String today = LocalDate.now(ReportAutoCrtJobConfig.SEOUL).toString();

        scheduler.syncDaily();

        verify(jobLauncher).run(eq(reportAutoCrtJob), org.mockito.ArgumentMatchers.argThat(
                (JobParameters p) -> p.getLong("runAt") != null
                        && today.equals(p.getString("baseYmd"))));
    }

    @Test
    @DisplayName("🔴 Job 이 FAILED 여도 스케줄 스레드는 살아남는다")
    void survivesFailedJob() throws Exception {
        when(jobLauncher.run(eq(reportAutoCrtJob), any())).thenReturn(execution(BatchStatus.FAILED));

        scheduler.syncDaily();   // 예외가 밖으로 나가면 안 된다

        verify(jobLauncher).run(eq(reportAutoCrtJob), any());
    }

    @Test
    @DisplayName("🔴 Job 이 예외로 죽어도 스케줄 스레드는 살아남는다")
    void swallowsException() throws Exception {
        when(jobLauncher.run(eq(reportAutoCrtJob), any()))
                .thenThrow(new IllegalStateException("백엔드 내부 API 키 없음"));

        scheduler.syncDaily();   // 예외가 밖으로 나가면 안 된다
    }

    @Test
    @DisplayName("🔁 @EnableScheduling·@Scheduled 가 없다 — 스케줄은 BatchScheduleRegistry 가 batch.tb_btch_schdl 로 건다")
    void hasNoAnnotationScheduling() {
        assertThat(ReportAutoCrtScheduler.class.getAnnotation(EnableScheduling.class))
                .as("남아 있으면 Boot 자동 스케줄러가 다시 생기고, @Scheduled 가 있으면 레지스트리와 이중으로 돈다")
                .isNull();
        assertThat(java.util.Arrays.stream(ReportAutoCrtScheduler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)))
                .isEmpty();
    }

    @Test
    @DisplayName("크론의 다음 실행 시각은 02:30(Asia/Seoul)이다 — ①·③(02:00)과 30분 띄웠다")
    void cronFiresAtTwoThirtyAmSeoul() {
        ZoneId seoul = ZoneId.of(ReportAutoCrtScheduler.ZONE);
        ZonedDateTime next = CronExpression.parse(ReportAutoCrtScheduler.CRON)
                .next(ZonedDateTime.of(LocalDate.parse("2026-05-15").atTime(3, 0), seoul));

        assertThat(next).isNotNull();
        assertThat(next.getHour()).isEqualTo(2);
        assertThat(next.getMinute()).isEqualTo(30);
        // 03:00 기준이면 "당일 02:30" 은 이미 지났으므로 다음 날이다.
        assertThat(next.toLocalDate()).isEqualTo(LocalDate.parse("2026-05-16"));
    }

    // ------------------------------------------------------------------ 헬퍼

    /**
     * 스케줄러 하나만 올리는 가벼운 컨텍스트. 실제 {@link JobLauncher}·{@link Job} 은 목으로 채운다 —
     * DB 도 배치 메타도 필요 없다({@link BatchAutoConfiguration} 을 끌어오지 않는다).
     */
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(JobLauncher.class, () -> mock(JobLauncher.class))
                .withBean("reportAutoCrtJob", Job.class, () -> mock(Job.class))
                .withUserConfiguration(ReportAutoCrtScheduler.class);
    }

    private static JobExecution execution(BatchStatus status) {
        JobExecution execution = new JobExecution(1L);
        execution.setStatus(status);
        execution.setExitStatus(status == BatchStatus.COMPLETED
                ? ExitStatus.COMPLETED : ExitStatus.FAILED);
        return execution;
    }
}
