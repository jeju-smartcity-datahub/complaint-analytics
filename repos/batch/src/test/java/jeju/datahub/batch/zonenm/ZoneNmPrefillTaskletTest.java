package jeju.datahub.batch.zonenm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.web.client.ResourceAccessException;

import jeju.datahub.batch.zonenm.BackendZoneNmClient.PrefillResponse;
import jeju.datahub.batch.zonenm.BackendZoneNmClient.Result;

/**
 * {@link ZoneNmPrefillTasklet} — 반복·중단 조건과 실패 갈래.
 */
class ZoneNmPrefillTaskletTest {

    private BackendZoneNmClient client;
    private StepExecution stepExecution;
    private StepContribution contribution;
    private ChunkContext chunkContext;
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = mock(BackendZoneNmClient.class);
        stepExecution = MetaDataInstanceFactory.createStepExecution();
        contribution = new StepContribution(stepExecution);
        chunkContext = new ChunkContext(new StepContext(stepExecution));
        sleeps.clear();
    }

    private ZoneNmPrefillTasklet tasklet(Long gridSz) {
        return new ZoneNmPrefillTasklet(client, gridSz, 100, 200, sleeps::add);
    }

    private static Result ok(int size, int resolved, int trans, int remaining, boolean tripped) {
        return new Result(200, new PrefillResponse(size, 999, resolved, trans, remaining, tripped));
    }

    /** FINISHED 가 나올 때까지 돌린다. 무한 반복이면 테스트가 실패하게 상한을 둔다. */
    private int runToEnd(ZoneNmPrefillTasklet t) throws Exception {
        for (int i = 1; i <= 100; i++) {
            if (t.execute(contribution, chunkContext) == RepeatStatus.FINISHED) {
                return i;
            }
        }
        throw new AssertionError("FINISHED 가 나오지 않았다");
    }

    private ExecutionContext ctx() {
        return stepExecution.getExecutionContext();
    }

    @Test
    @DisplayName("gridSz 없으면 1000→500→250→100 을 remainingCnt==0 까지 차례로 소진한다")
    void drainsAllSizesInOrder() throws Exception {
        when(client.prefill(eq(1000), eq(100))).thenReturn(ok(1000, 100, 0, 23, false), ok(1000, 23, 0, 0, false));
        when(client.prefill(eq(500), eq(100))).thenReturn(ok(500, 5, 0, 0, false));
        when(client.prefill(eq(250), eq(100))).thenReturn(ok(250, 0, 0, 0, false));
        when(client.prefill(eq(100), eq(100))).thenReturn(ok(100, 7, 2, 0, true));

        int executions = runToEnd(tasklet(null));

        assertThat(executions).isEqualTo(5);
        assertThat(ctx().getString("zoneNm.1000.outcome")).isEqualTo("DONE");
        assertThat(ctx().getLong("zoneNm.1000.calls")).isEqualTo(2);
        assertThat(ctx().getLong("zoneNm.1000.resolved")).isEqualTo(123);
        assertThat(ctx().getLong("zoneNm.1000.remaining")).isZero();
        assertThat(ctx().getLong("zoneNm.100.transient")).isEqualTo(2);
        assertThat(ctx().getLong("zoneNm.100.tripped")).isEqualTo(1);
        // read = 시도(저장+일시실패) · write = 저장 · filter = 일시실패
        assertThat(contribution.getReadCount()).isEqualTo(137);
        assertThat(contribution.getWriteCount()).isEqualTo(135);
        assertThat(contribution.getFilterCount()).isEqualTo(2);
        // 호출 사이에만 대기 — 5회 호출이면 4번
        assertThat(sleeps).hasSize(4).allMatch(ms -> ms == 200L);
    }

    @Test
    @DisplayName("🔴 remainingCnt 가 직전보다 줄지 않으면 그 크기만 중단하고 다음 크기로 — Job 은 계속")
    void stopsSizeWhenNoProgress() throws Exception {
        when(client.prefill(eq(1000), anyInt()))
                .thenReturn(ok(1000, 0, 3, 600, true), ok(1000, 0, 3, 600, true));
        when(client.prefill(eq(500), anyInt())).thenReturn(ok(500, 10, 0, 0, false));
        when(client.prefill(eq(250), anyInt())).thenReturn(ok(250, 0, 0, 0, false));
        when(client.prefill(eq(100), anyInt())).thenReturn(ok(100, 0, 0, 0, false));

        runToEnd(tasklet(null));

        verify(client, times(2)).prefill(eq(1000), anyInt());
        assertThat(ctx().getString("zoneNm.1000.outcome")).isEqualTo("NO_PROGRESS");
        assertThat(ctx().getLong("zoneNm.1000.remaining")).isEqualTo(600);
        assertThat(ctx().getLong("zoneNm.1000.tripped")).isEqualTo(2);
        assertThat(ctx().getString("zoneNm.500.outcome")).isEqualTo("DONE");
    }

    @Test
    @DisplayName("🔴 resolvedCnt==0 이어도 remainingCnt 가 줄었으면(경합 캐시 히트) 계속한다")
    void continuesWhenRemainingDropsWithoutStores() throws Exception {
        when(client.prefill(eq(250), anyInt()))
                .thenReturn(ok(250, 50, 0, 200, false), ok(250, 0, 0, 150, false), ok(250, 0, 0, 0, false));

        int executions = runToEnd(tasklet(250L));

        assertThat(executions).isEqualTo(3);
        assertThat(ctx().getString("zoneNm.250.outcome")).isEqualTo("DONE");
    }

    @Test
    @DisplayName("gridSz 지정이면 그 크기 하나만 부른다")
    void singleSizeOnly() throws Exception {
        when(client.prefill(eq(1000), anyInt())).thenReturn(ok(1000, 5, 0, 0, false));

        int executions = runToEnd(tasklet(1000L));

        assertThat(executions).isEqualTo(1);
        verify(client, never()).prefill(eq(500), anyInt());
        verify(client, never()).prefill(eq(250), anyInt());
        verify(client, never()).prefill(eq(100), anyInt());
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("5xx·타임아웃은 그 크기만 중단(TRANSIENT_ERROR) — 예외를 올리지 않는다")
    void transientFailuresStopSizeOnly() throws Exception {
        when(client.prefill(eq(1000), anyInt())).thenReturn(new Result(500, null));
        when(client.prefill(eq(500), anyInt())).thenThrow(new ResourceAccessException("timeout"));
        when(client.prefill(eq(250), anyInt())).thenReturn(new Result(502, null));
        when(client.prefill(eq(100), anyInt())).thenReturn(ok(100, 1, 0, 0, false));

        runToEnd(tasklet(null));

        assertThat(ctx().getString("zoneNm.1000.outcome")).isEqualTo("TRANSIENT_ERROR");
        assertThat(ctx().getString("zoneNm.500.outcome")).isEqualTo("TRANSIENT_ERROR");
        assertThat(ctx().getLong("zoneNm.500.remaining")).isEqualTo(-1);
        assertThat(ctx().getString("zoneNm.250.outcome")).isEqualTo("TRANSIENT_ERROR");
        assertThat(ctx().getString("zoneNm.100.outcome")).isEqualTo("DONE");
    }

    @Test
    @DisplayName("🔴 400·401·403·503·예상 밖 상태·200 본문 불일치는 IllegalStateException(Job FAILED)")
    void fatalStatusesThrow() {
        for (Result r : List.of(new Result(400, null), new Result(401, null), new Result(403, null),
                new Result(503, null), new Result(404, null), new Result(200, null))) {
            BackendZoneNmClient c = mock(BackendZoneNmClient.class);
            when(c.prefill(anyInt(), anyInt())).thenReturn(r);
            ZoneNmPrefillTasklet t = new ZoneNmPrefillTasklet(c, 500L, 100, 0, ms -> { });

            assertThatThrownBy(() -> t.execute(contribution, chunkContext))
                    .as("status %s", r.status())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("지원하지 않는 gridSz·범위 밖 limit 은 Job 시작 시점에 끊는다")
    void rejectsBadParameters() {
        assertThatThrownBy(() -> new ZoneNmPrefillTasklet(client, 300L, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneNmPrefillTasklet(client, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneNmPrefillTasklet(client, null, 201, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ZoneNmPrefillTasklet.isSupported(100)).isTrue();
        assertThat(ZoneNmPrefillTasklet.isSupported(4_294_968_296L)).isFalse();
    }
}
