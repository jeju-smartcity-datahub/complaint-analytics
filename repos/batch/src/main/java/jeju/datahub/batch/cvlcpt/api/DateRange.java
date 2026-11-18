package jeju.datahub.batch.cvlcpt.api;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 수집 대상 일자 구간 — <b>항상 반개구간</b>({@code from <= d < toExclusive}) 이다 (계획서 11-1).
 *
 * <h2>왜 반개구간인가</h2>
 * 원천 {@code dclr_ymd} 가 DATE 든 DATETIME 이든 <b>같은 술어가 맞아떨어진다</b>(사전조사 제약 H).
 * {@code = '{일자}'} 로 쓰면 DATETIME 인 순간 시각부 때문에 통째로 빗나가는데, 그 사실을 아무도
 * 모른 채 "0건 수집"으로 조용히 끝난다.
 *
 * <h2>daily 와 backfill 이 같은 타입을 쓴다</h2>
 * <ul>
 *   <li>daily = {@link #ofDay(LocalDate)} — 하루짜리 구간. 일자 루프의 1회전과 정확히 같다.</li>
 *   <li>backfill = {@link #of(LocalDate, LocalDate)} — <b>임의</b> 구간. 연 단위로 고정하지 않는다:
 *       원천이 2025.1~2026.5 에 걸쳐 있어 연도를 고정하면 2026년분이 통째로 누락된다.</li>
 * </ul>
 * 두 모드가 하나의 값 객체로 수렴하므로 {@link ComplaintItemReader} 의 코드 경로도 하나뿐이다.
 */
public record DateRange(LocalDate fromInclusive, LocalDate toExclusive) implements Iterable<LocalDate> {

    /**
     * @throws IllegalArgumentException 구간이 비었거나 뒤집혔을 때. 빈 구간을 허용하면 "0건 수집"이
     *         정상 완료로 보고돼 설정 실수를 놓친다.
     */
    public DateRange {
        if (fromInclusive == null || toExclusive == null) {
            throw new IllegalArgumentException(
                    "수집 구간이 비어 있습니다. from=" + fromInclusive + " to=" + toExclusive);
        }
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "수집 구간이 비었거나 뒤집혔습니다(반개구간은 from < to 여야 함): ["
                            + fromInclusive + ", " + toExclusive + ")");
        }
    }

    /** 하루짜리 구간 — daily 모드. */
    public static DateRange ofDay(LocalDate day) {
        if (day == null) {
            throw new IllegalArgumentException("일자가 null 입니다.");
        }
        return new DateRange(day, day.plusDays(1));
    }

    /** 임의 구간 — backfill 모드. <b>연 단위로 고정하지 않는다</b>(위 클래스 주석 참조). */
    public static DateRange of(LocalDate from, LocalDate toExclusive) {
        return new DateRange(from, toExclusive);
    }

    /** 구간에 든 일수. 로그에 "며칠치를 도는지" 를 남기는 용도. */
    public long days() {
        return toExclusive.toEpochDay() - fromInclusive.toEpochDay();
    }

    /** 일자 루프용 — {@code from} 부터 {@code toExclusive} 직전까지 하루씩. */
    @Override
    public Iterator<LocalDate> iterator() {
        return new Iterator<>() {
            private LocalDate next = fromInclusive;

            @Override
            public boolean hasNext() {
                return next.isBefore(toExclusive);
            }

            @Override
            public LocalDate next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                LocalDate current = next;
                next = next.plusDays(1);
                return current;
            }
        };
    }

    @Override
    public String toString() {
        return "[" + fromInclusive + ", " + toExclusive + ")";
    }
}
