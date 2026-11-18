package jeju.datahub.batch.sftychck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.CronExpression;

/**
 * {@link PkltSftyChckSyncScheduler} — 크론 시각(연 2회)·실행 파라미터·예외 격리·조건부 등록 (TSK-4254 Step 8 · D5).
 *
 * <p>스프링 컨텍스트를 통째로 띄우지 않는다(선례 {@code ReportAutoCrtSchedulerTest} — 이용패턴 스케줄러 테스트는 없다).
 * 조건부 등록만 {@link ApplicationContextRunner} 로 가볍게 띄운다(🔁 cron 은 TSK-4337 부터 batch.tb_btch_schdl 이 정본 —
 * 종전 {@code sftychck.sync.cron} 덮어쓰기 단언은 키 폐기와 함께 걷었다).
 */
class PkltSftyChckSyncSchedulerTest {

    private static final ZoneId SEOUL = ZoneId.of(PkltSftyChckSyncScheduler.ZONE);

    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final Job job = mock(Job.class);
    private final PkltSftyChckSyncScheduler scheduler = new PkltSftyChckSyncScheduler(jobLauncher, job);

    @Test
    @DisplayName("CRON(표 초기값 참고용)은 유효하고 1·7월 1일 04:00 이다")
    void cronIsValid() {
        assertThat(PkltSftyChckSyncScheduler.CRON).isEqualTo("0 0 4 1 1,7 *");
        assertThat(CronExpression.isValidExpression(PkltSftyChckSyncScheduler.CRON)).isTrue();
        assertThat(PkltSftyChckSyncScheduler.ZONE).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("다음 실행은 1월·7월 1일 04:00(Asia/Seoul)만 — 연 2회")
    void nextExecutionsAreJanuaryAndJulyFirstAtFour() {
        CronExpression cron = CronExpression.parse(PkltSftyChckSyncScheduler.CRON);

        ZonedDateTime fromFeb = ZonedDateTime.of(LocalDateTime.of(2026, 2, 15, 0, 0), SEOUL);
        assertThat(cron.next(fromFeb)).isEqualTo(ZonedDateTime.of(LocalDateTime.of(2026, 7, 1, 4, 0), SEOUL));

        ZonedDateTime fromAug = ZonedDateTime.of(LocalDateTime.of(2026, 8, 1, 0, 0), SEOUL);
        assertThat(cron.next(fromAug)).isEqualTo(ZonedDateTime.of(LocalDateTime.of(2027, 1, 1, 4, 0), SEOUL));

        // 당일 04:00 직후면 다음 반기다.
        ZonedDateTime justAfter = ZonedDateTime.of(LocalDateTime.of(2027, 1, 1, 4, 0, 1), SEOUL);
        assertThat(cron.next(justAfter)).isEqualTo(ZonedDateTime.of(LocalDateTime.of(2027, 7, 1, 4, 0), SEOUL));
    }

    @Test
    @DisplayName("🔴 runAt(유니크) 이 실린다 — 없으면 두 번째 실행부터 JobInstanceAlreadyCompleteException")
    void passesRunAt() throws Exception {
        scheduler.syncSemiannual();

        verify(jobLauncher).run(eq(job), argThat((JobParameters p) -> p.getLong("runAt") != null));
    }

    @Test
    @DisplayName("🔴 launch 예외를 삼킨다 — 스케줄 스레드 밖으로 던지지 않는다")
    void swallowsLaunchException() throws Exception {
        when(jobLauncher.run(eq(job), any())).thenThrow(new IllegalStateException("launch 실패"));

        assertThatCode(scheduler::syncSemiannual).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("🔁 @EnableScheduling·@Scheduled 가 없다 — 스케줄은 BatchScheduleRegistry 가 batch.tb_btch_schdl 로 건다(TSK-4337)")
    void hasNoAnnotationScheduling() {
        assertThat(PkltSftyChckSyncScheduler.class.getAnnotation(EnableScheduling.class))
                .as("남아 있으면 Boot 자동 스케줄러가 다시 생기고, @Scheduled 가 있으면 레지스트리와 이중으로 돈다")
                .isNull();
        assertThat(java.util.Arrays.stream(PkltSftyChckSyncScheduler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)))
                .isEmpty();
    }

    @Test
    @DisplayName("sftychck.sync.scheduled=false 면 빈 자체가 없고, 미지정이면 등록된다(matchIfMissing)")
    void conditionalRegistration() {
        contextRunner().withPropertyValues("sftychck.sync.scheduled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PkltSftyChckSyncScheduler.class));
        contextRunner()
                .run(ctx -> assertThat(ctx).hasSingleBean(PkltSftyChckSyncScheduler.class));
    }


    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(JobLauncher.class, () -> mock(JobLauncher.class))
                .withBean("pkltSftyChckFetchJob", Job.class, () -> mock(Job.class))
                .withUserConfiguration(PkltSftyChckSyncScheduler.class);
    }
}
