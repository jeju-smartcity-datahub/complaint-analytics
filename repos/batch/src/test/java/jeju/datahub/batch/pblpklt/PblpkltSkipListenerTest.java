package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jeju.datahub.batch.geocode.GeocodeSkipRecorder;
import jeju.datahub.batch.pblpklt.PblpkltGeocodeSkippableException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PblpkltSkipListener} 단위 테스트.
 *
 * <p>🔴 <b>이관 전후로 같은 결과를 고정한다.</b> 공영주차장은 예외가 주소를 들고 와도
 * <b>{@code trgt_addr} 를 기록하지 않는다</b>(로컬 102행 전부 NULL). 이관 전에는 upsert 인자가
 * 4개인 것과 SQL 에 {@code trgt_addr} 가 없음을 captor 로 단언했지만, SQL 이 리스너를 떠나
 * 그 단언이 불가능해졌다 — 이제 {@code record} 의 5번째 인자 {@code isNull()} 이 그 역할을
 * 대신 지킨다.
 *
 * <p>Spring 컨텍스트 없이 {@code new PblpkltSkipListener(mock)} 으로 만든다 — 테스트 인스턴스가
 * 테스트마다 새로 생성되므로 싱글턴 카운터 오염이 없다.
 */
class PblpkltSkipListenerTest {

    private static final String ORIGIN_PK = "1234";
    private static final String ADDRESS = "제주시 연동 300-1";
    private static final String NOTE = "vworld=NOT_FOUND";

    private final GeocodeSkipRecorder recorder = mock(GeocodeSkipRecorder.class);
    private final PblpkltSkipListener listener = new PblpkltSkipListener(recorder);

    @Test
    @DisplayName("🔴 스킵 예외 1건을 PBLPKLT·ID·사유·note 로 기록한다 — 주소는 넘기지 않는다(trgt_addr null)")
    void recordsSkipExceptionWithoutAddress() {
        listener.onSkipInProcess(null,
                new PblpkltGeocodeSkippableException(Reason.GEOCODE_FAILED, ORIGIN_PK, ADDRESS, NOTE));

        // 🔴 M2 — 주소를 알고 있어도 5번째 인자(trgt_addr)로 넘기지 않으므로 표의 trgt_addr 는 NULL 로 남는다.
        verify(recorder).record(eq("PBLPKLT"), eq(ORIGIN_PK), eq("GEOCODE_FAILED"), eq(NOTE), isNull());
    }

    @Test
    @DisplayName("사유별 카운터 — GEOCODE_FAILED 1건 기록 후 1, 합계 1")
    void countsByReason() {
        listener.onSkipInProcess(null,
                new PblpkltGeocodeSkippableException(Reason.GEOCODE_FAILED, ORIGIN_PK, ADDRESS, NOTE));

        assertThat(listener.skipCounts().get(Reason.GEOCODE_FAILED)).isEqualTo(1);
        assertThat(listener.skipCounts().get(Reason.EMD_UNRESOLVED)).isZero();
        assertThat(listener.totalSkipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("EMD_UNRESOLVED 는 사유 문자열 그대로 기록된다")
    void recordsEmdUnresolvedReason() {
        listener.onSkipInProcess(null,
                new PblpkltGeocodeSkippableException(Reason.EMD_UNRESOLVED, ORIGIN_PK, ADDRESS, "lat=33.4, lot=126.5"));

        verify(recorder).record(
                eq("PBLPKLT"), eq(ORIGIN_PK), eq("EMD_UNRESOLVED"), eq("lat=33.4, lot=126.5"), isNull());
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
                new PblpkltGeocodeSkippableException(Reason.GEOCODE_FAILED, ORIGIN_PK, ADDRESS, NOTE));
        listener.onSkipInProcess(null,
                new PblpkltGeocodeSkippableException(Reason.EMD_UNRESOLVED, "5678", ADDRESS, NOTE));
        assertThat(listener.totalSkipped()).isEqualTo(2);

        listener.resetStats();

        assertThat(listener.skipCounts().get(Reason.GEOCODE_FAILED)).isZero();
        assertThat(listener.skipCounts().get(Reason.EMD_UNRESOLVED)).isZero();
        assertThat(listener.totalSkipped()).isZero();
    }
}
