package jeju.datahub.batch.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;

/**
 * {@link BatchScheduleRegistry} — 등록·취소(reschedule)·잘못된 cron·env 차단·단속.
 *
 * <p>스프링 컨텍스트·DB 없이 돈다 — {@link TaskScheduler}·{@link BatchScheduleRepository} 는 mock 이다.
 */
class BatchScheduleRegistryTest {

    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final BatchScheduleRepository repository = mock(BatchScheduleRepository.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);

    private BatchScheduleRegistry registry(ScheduleTarget... targets) {
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        return new BatchScheduleRegistry(taskScheduler, repository, environment, List.of(targets));
    }

    @Test
    @DisplayName("1. use_yn=Y 만 등록하고 cron·시간대(Asia/Seoul)는 표 값이다 — N 은 등록하지 않는다")
    void registersOnlyInUseRows() {
        AtomicInteger calls = new AtomicInteger();
        BatchScheduleRegistry registry = registry(
                new ScheduleTarget("reportAutoCrtJob", "rptp.sync.scheduled", calls::incrementAndGet),
                new ScheduleTarget("pblpkltFetchJob", "pblpklt.sync.scheduled", () -> { }));
        when(repository.findAll()).thenReturn(List.of(
                new BatchSchedule("pblpkltFetchJob", "0 0 2 1 1,4,7,10 *", "N"),
                new BatchSchedule("reportAutoCrtJob", "0 30 2 * * *", "Y")));

        registry.registerAll();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler, times(1)).schedule(task.capture(), trigger.capture());
        assertThat(trigger.getValue()).isEqualTo(new CronTrigger("0 30 2 * * *", BatchScheduleRegistry.ZONE));
        assertThat(registry.isRegistered("reportAutoCrtJob")).isTrue();
        assertThat(registry.isRegistered("pblpkltFetchJob")).isFalse();

        // 등록된 작업은 매핑된 실행 메서드를 부른다.
        new SyncTaskExecutor().execute(task.getValue());   // 7.2 — run() 직접 호출 대신 같은 스레드 실행기
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("2. reschedule = 기존 future 취소(인터럽트 없음) → 표 다시 읽기 → 새 cron 으로 등록 · N 이면 취소만")
    void rescheduleCancelsAndReregisters() {
        BatchScheduleRegistry registry = registry(
                new ScheduleTarget("reportAutoCrtJob", "rptp.sync.scheduled", () -> { }));
        when(repository.findAll()).thenReturn(List.of(new BatchSchedule("reportAutoCrtJob", "0 30 2 * * *", "Y")));
        registry.registerAll();

        when(repository.find("reportAutoCrtJob"))
                .thenReturn(Optional.of(new BatchSchedule("reportAutoCrtJob", "0 */1 * * * *", "Y")));
        assertThat(registry.reschedule("reportAutoCrtJob")).isEqualTo(ScheduleState.REGISTERED);
        verify(future, times(1)).cancel(false);
        verify(taskScheduler).schedule(any(Runnable.class),
                eq(new CronTrigger("0 */1 * * * *", BatchScheduleRegistry.ZONE)));

        when(repository.find("reportAutoCrtJob"))
                .thenReturn(Optional.of(new BatchSchedule("reportAutoCrtJob", "0 30 2 * * *", "N")));
        assertThat(registry.reschedule("reportAutoCrtJob")).isEqualTo(ScheduleState.DISABLED);
        verify(future, times(2)).cancel(false);
        assertThat(registry.isRegistered("reportAutoCrtJob")).isFalse();
    }

    @Test
    @DisplayName("3. 🔴 잘못된 cron 은 그 잡만 등록하지 않는다 — 다른 잡·기동은 산다")
    void invalidCronSkipsOnlyThatJob() {
        BatchScheduleRegistry registry = registry(
                new ScheduleTarget("complaintFetchJob", "complaint.sync.scheduled", () -> { }),
                new ScheduleTarget("reportAutoCrtJob", "rptp.sync.scheduled", () -> { }));
        when(repository.findAll()).thenReturn(List.of(
                new BatchSchedule("complaintFetchJob", "매일 두 시", "Y"),
                new BatchSchedule("reportAutoCrtJob", "0 30 2 * * *", "Y")));

        assertThatCode(registry::registerAll).doesNotThrowAnyException();

        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
        assertThat(registry.isRegistered("complaintFetchJob")).isFalse();
        assertThat(registry.isRegistered("reportAutoCrtJob")).isTrue();

        when(repository.find("complaintFetchJob"))
                .thenReturn(Optional.of(new BatchSchedule("complaintFetchJob", "0 0 25 * * *", "Y")));
        assertThat(registry.reschedule("complaintFetchJob")).isEqualTo(ScheduleState.INVALID_CRON);
    }

    @Test
    @DisplayName("4. 🔴 env 차단 — 스케줄러 빈이 없으면(action=null) 표가 Y 여도 등록하지 않는다")
    void envBlockedWhenBeanAbsent() {
        environment.setProperty("zonenm.sync.scheduled", "false");
        BatchScheduleRegistry registry = registry(
                new ScheduleTarget("zoneNmPrefillJob", "zonenm.sync.scheduled", null));
        when(repository.findAll()).thenReturn(List.of(new BatchSchedule("zoneNmPrefillJob", "0 10 3 * * *", "Y")));

        assertThatCode(registry::registerAll).doesNotThrowAnyException();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
        when(repository.find("zoneNmPrefillJob"))
                .thenReturn(Optional.of(new BatchSchedule("zoneNmPrefillJob", "0 10 3 * * *", "Y")));
        assertThat(registry.reschedule("zoneNmPrefillJob")).isEqualTo(ScheduleState.BLOCKED);
    }

    @Test
    @DisplayName("5. 🔴 단속은 차단 스위치가 없다(envKey=null) — 표가 Y 면 등록, N 이면 미등록")
    void crackdownFollowsTableOnly() {
        BatchScheduleRegistry registry = registry(new ScheduleTarget("crackdownFetchJob", null, () -> { }));
        when(repository.findAll()).thenReturn(List.of(new BatchSchedule("crackdownFetchJob", "0 0 3 * * *", "N")));
        registry.registerAll();
        assertThat(registry.isRegistered("crackdownFetchJob")).isFalse();

        when(repository.find("crackdownFetchJob"))
                .thenReturn(Optional.of(new BatchSchedule("crackdownFetchJob", "0 0 3 * * *", "Y")));
        assertThat(registry.reschedule("crackdownFetchJob")).isEqualTo(ScheduleState.REGISTERED);
        assertThat(registry.isRegistered("crackdownFetchJob")).isTrue();
    }

    @Test
    @DisplayName("6. 표에만 있는 잡 · 실행 메서드만 있는 잡은 등록하지 않는다(WARN) · 표 조회 실패도 기동을 죽이지 않는다")
    void unknownAndMissingRowsAndLoadFailure() {
        BatchScheduleRegistry registry = registry(
                new ScheduleTarget("pkltUtztnFetchJob", "utztn.sync.scheduled", () -> { }));
        when(repository.findAll()).thenReturn(List.of(new BatchSchedule("ghostJob", "0 0 1 * * *", "Y")));

        registry.registerAll();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
        assertThat(registry.reschedule("ghostJob")).isEqualTo(ScheduleState.NO_TARGET);
        when(repository.find("pkltUtztnFetchJob")).thenReturn(Optional.empty());
        assertThat(registry.reschedule("pkltUtztnFetchJob")).isEqualTo(ScheduleState.NO_ROW);

        when(repository.findAll()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB 없음"));
        assertThatCode(registry::registerAll).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("7. 실행 메서드의 런타임 예외가 스케줄 스레드 밖으로 나가지 않는다")
    void runtimeExceptionIsSwallowed() {
        BatchScheduleRegistry registry = registry(new ScheduleTarget("pkltSftyChckFetchJob", "sftychck.sync.scheduled",
                () -> { throw new IllegalStateException("boom"); }));
        when(repository.findAll()).thenReturn(List.of(new BatchSchedule("pkltSftyChckFetchJob", "0 0 4 1 1,7 *", "Y")));
        registry.registerAll();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Trigger.class));
        assertThatCode(task.getValue()::run).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("8. 같은 잡이 이 프로세스에서 이미 돌고 있으면 그 주기는 건너뛴다 — 끝나면 다시 돈다 · isTarget·isBlockedByEnv")
    void skipsWhenSameJobRunning() {
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(true);
        BatchScheduleRegistry registry = new BatchScheduleRegistry(taskScheduler, repository, environment, List.of(
                new ScheduleTarget("crackdownFetchJob", null, calls::incrementAndGet),
                new ScheduleTarget("reportAutoCrtJob", "rptp.sync.scheduled", null)),
                jobNm -> "crackdownFetchJob".equals(jobNm) && busy.get());
        when(repository.findAll()).thenReturn(List.of(new BatchSchedule("crackdownFetchJob", "0 0 3 * * *", "Y")));
        registry.registerAll();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Trigger.class));
        new SyncTaskExecutor().execute(task.getValue());   // 7.2 — run() 직접 호출 대신 같은 스레드 실행기
        assertThat(calls).as("운영 API 실행과 겹친 주기").hasValue(0);
        busy.set(false);
        new SyncTaskExecutor().execute(task.getValue());   // 7.2 — run() 직접 호출 대신 같은 스레드 실행기
        assertThat(calls).hasValue(1);

        assertThat(registry.isTarget("crackdownFetchJob")).isTrue();
        assertThat(registry.isTarget("riskAlertJudgeJob")).isFalse();
        assertThat(registry.isBlockedByEnv("reportAutoCrtJob")).isTrue();
        assertThat(registry.isBlockedByEnv("crackdownFetchJob")).isFalse();
        assertThat(registry.envKey("reportAutoCrtJob")).isEqualTo("rptp.sync.scheduled");
    }
}
