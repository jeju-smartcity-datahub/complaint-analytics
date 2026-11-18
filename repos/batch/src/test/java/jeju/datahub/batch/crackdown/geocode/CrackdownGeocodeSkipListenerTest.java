package jeju.datahub.batch.crackdown.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeSkippableException.Reason;
import jeju.datahub.batch.geocode.GeocodeSkipRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link CrackdownGeocodeSkipListener} 단위 테스트 — TSK-4054 Step 6.
 *
 * <h2>🔴 SHA-256 일치 (TSK-4336 — md5 대체)</h2>
 * {@link #SHA256_AIRPORT_JUNCTION} 은 로컬 DB {@code SELECT encode(sha256(convert_to('공항입구교차로','UTF8')),'hex')} 결과다(2026-11-12).
 * Java 값이 이것과 다르면 Reader 의 {@code NOT EXISTS (… trgt_id = encode(sha256(…)))} 가 스킵 주소를
 * 매 실행 다시 읽는다. DB 없이 돌도록 상수로 고정했다.
 */
class CrackdownGeocodeSkipListenerTest {

    private static final String PLACE = "공항입구교차로";

    /** {@code SELECT encode(sha256(convert_to('공항입구교차로','UTF8')),'hex')} — PostgreSQL 15 로컬 실측. */
    private static final String SHA256_AIRPORT_JUNCTION =
            "1477f31cee5dc22e15d18f122bb4c4c6e8e351a400a6fd6ae815c924a81b92d7";

    private final GeocodeSkipRecorder recorder = mock(GeocodeSkipRecorder.class);
    private final CrackdownGeocodeSkipListener listener = new CrackdownGeocodeSkipListener(recorder);

    @Test
    @DisplayName("🔴 trgt_id = sha256Hex(crdn_addr) 로 Recorder 를 부르고, SQL sha256 과 같은 값이다 · note 에 주소 없음")
    void recordsWithSha256TargetId() {
        listener.onSkipInProcess(PLACE, new CrackdownGeocodeSkippableException(
                Reason.GEOCODE_FAILED, PLACE, CrackdownGeocodeProcessor.PLACE_NOT_ATTEMPTED_NOTE));

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(recorder).record(eq("CRDN"), eq(SHA256_AIRPORT_JUNCTION), eq("GEOCODE_FAILED"), note.capture(),
                eq(PLACE));
        assertThat(note.getValue()).isEqualTo("kind=PLACE, place=NOT_ATTEMPTED(step4-skipped)")
                .doesNotContain(PLACE);
    }

    @Test
    @DisplayName("사유별 카운터 — GEOCODE_FAILED 1건 → 1, resetStats 후 0")
    void countsAndResets() {
        listener.onSkipInProcess(PLACE, new CrackdownGeocodeSkippableException(Reason.GEOCODE_FAILED, PLACE, "n"));
        listener.onSkipInProcess("노형동 1", new CrackdownGeocodeSkippableException(Reason.EMD_UNRESOLVED, "노형동 1", "n"));
        listener.onSkipInProcess("노형동 2", new CrackdownGeocodeSkippableException(Reason.EMD_UNRESOLVED, "노형동 2", "n"));

        assertThat(listener.skipCounts().get(Reason.GEOCODE_FAILED)).isEqualTo(1);
        assertThat(listener.skipCounts().get(Reason.EMD_UNRESOLVED)).isEqualTo(2);
        assertThat(listener.totalSkipped()).isEqualTo(3);

        listener.resetStats();
        assertThat(listener.skipCounts().get(Reason.GEOCODE_FAILED)).isZero();
        assertThat(listener.skipCounts().get(Reason.EMD_UNRESOLVED)).isZero();
        assertThat(listener.totalSkipped()).isZero();
    }

    @Test
    @DisplayName("스킵 예외가 아닌 Throwable 은 기록·카운트하지 않는다")
    void ignoresNonSkipThrowable() {
        listener.onSkipInProcess(PLACE, new IllegalStateException("boom"));
        listener.onSkipInProcess(PLACE, null);

        verifyNoInteractions(recorder);
        assertThat(listener.totalSkipped()).isZero();
    }

    @Test
    @DisplayName("기록 실패는 삼키지 않고, 카운터도 올리지 않는다")
    void recorderFailurePropagates() {
        doThrow(new IllegalStateException("db down"))
                .when(recorder).record(anyString(), anyString(), anyString(), any(), any());

        assertThatThrownBy(() -> listener.onSkipInProcess(PLACE,
                new CrackdownGeocodeSkippableException(Reason.GEOCODE_FAILED, PLACE, "n")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(listener.totalSkipped()).isZero();
    }
}
