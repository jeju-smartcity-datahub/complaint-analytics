package jeju.datahub.batch.cvlcpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jeju.datahub.batch.cvlcpt.GeocodeSkippableException.Reason;
import jeju.datahub.batch.geocode.GeocodeSkipRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GeocodeSkipListener} 단위 테스트 — TSK-4054 3차 Step 1(작성) · Step 2(Recorder 이관).
 *
 * <p>🔴 <b>이관 전후로 같은 값을 고정한다.</b> 표 쓰기가 {@link GeocodeSkipRecorder} 로 옮겨가
 * 검증 대상이 {@code verify(recorder).record(...)} 로 바뀌었지만 <b>ID·사유·note·주소 값은
 * Step 1 과 그대로</b>다.
 *
 * <p>Spring 컨텍스트 없이 {@code new GeocodeSkipListener(mock)} 으로 만든다 — 테스트 인스턴스가
 * 테스트마다 새로 생성되므로 싱글턴 카운터 오염이 없다.
 */
class GeocodeSkipListenerTest {

    private static final String CVLCPT_ID = "CVL-20260915-001";
    private static final String ADDRESS = "제주시 노형동 1234-5";
    private static final String NOTE = "vworld=NOT_FOUND, juso=NOT_FOUND";

    private final GeocodeSkipRecorder recorder = mock(GeocodeSkipRecorder.class);
    private final GeocodeSkipListener listener = new GeocodeSkipListener(recorder);

    @Test
    @DisplayName("스킵 예외 1건을 CVLCPT·ID·사유·note·주소 5개 인자로 Recorder 에 넘긴다")
    void recordsSkipExceptionOnce() {
        listener.onSkipInProcess(null,
                new GeocodeSkippableException(Reason.GEOCODE_FAILED, CVLCPT_ID, ADDRESS, NOTE));

        // 🔴 민원은 주소를 기록해 왔다 — SQL 이 리스너를 떠나 captor 단언은 불가하므로, 주소가
        // record 의 5번째 인자(trgt_addr)로 전달되는지를 이 인자 단언이 대신 지킨다.
        verify(recorder).record("CVLCPT", CVLCPT_ID, "GEOCODE_FAILED", NOTE, ADDRESS);
    }

    @Test
    @DisplayName("사유별 카운터 — GEOCODE_FAILED 1건 기록 후 1, 합계 1")
    void countsByReason() {
        listener.onSkipInProcess(null,
                new GeocodeSkippableException(Reason.GEOCODE_FAILED, CVLCPT_ID, ADDRESS, NOTE));

        assertThat(listener.skipCounts().get(Reason.GEOCODE_FAILED)).isEqualTo(1);
        assertThat(listener.skipCounts().get(Reason.EMD_UNRESOLVED)).isZero();
        assertThat(listener.totalSkipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("EMD_UNRESOLVED 는 사유 문자열 그대로 기록된다")
    void recordsEmdUnresolvedReason() {
        listener.onSkipInProcess(null,
                new GeocodeSkippableException(Reason.EMD_UNRESOLVED, CVLCPT_ID, ADDRESS, "lat=33.4, lot=126.5"));

        verify(recorder).record("CVLCPT", CVLCPT_ID, "EMD_UNRESOLVED", "lat=33.4, lot=126.5", ADDRESS);
        assertThat(listener.skipCounts().get(Reason.EMD_UNRESOLVED)).isEqualTo(1);
    }

    @Test
    @DisplayName("스킵 예외가 아닌 Throwable 은 기록·카운트하지 않고 예외도 전파하지 않는다 — ERROR 로그만")
    void ignoresNonSkipThrowable() {
        listener.onSkipInProcess(null, new IllegalStateException("boom"));
        listener.onSkipInProcess(null, null);

        verifyNoInteractions(recorder);
        assertThat(listener.totalSkipped()).isZero();
    }

    @Test
    @DisplayName("resetStats() 후 카운터가 0 이 된다")
    void resetStatsZeroesCounters() {
        listener.onSkipInProcess(null,
                new GeocodeSkippableException(Reason.GEOCODE_FAILED, CVLCPT_ID, ADDRESS, NOTE));
        listener.onSkipInProcess(null,
                new GeocodeSkippableException(Reason.EMD_UNRESOLVED, "CVL-20260915-002", ADDRESS, NOTE));
        assertThat(listener.totalSkipped()).isEqualTo(2);

        listener.resetStats();

        assertThat(listener.skipCounts().get(Reason.GEOCODE_FAILED)).isZero();
        assertThat(listener.skipCounts().get(Reason.EMD_UNRESOLVED)).isZero();
        assertThat(listener.totalSkipped()).isZero();
    }
}
