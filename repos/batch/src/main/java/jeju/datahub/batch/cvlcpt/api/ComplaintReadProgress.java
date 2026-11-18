package jeju.datahub.batch.cvlcpt.api;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * 민원 수집 Reader 의 진행 위치 — 일자 루프 몇 번째 날인가 · 지금까지 원천에서 받은 행 수 (추가).
 *
 * <p>Reader 는 {@code @StepScope} 라 Job 리스너가 직접 잡을 수 없어서, 다른 통계 빈(Processor·Geocoder)과 같은
 * <b>싱글턴 + {@code beforeJob} 리셋</b> 방식으로 둔다({@code CvlcptJobSummaryListener}). 같은 잡이 동시에 둘 돌지 않는다는
 * 전제(운영 API 409 · 스케줄 skipped-running)도 그 빈들과 같다.
 *
 * <p>⚠️ 원천 총건수는 모른다 — Reader 가 일자마다 keyset 으로 끝까지 읽는 구조라, 진행률은 건수가 아니라 <b>일자</b>로 보인다.
 */
@Component
public class ComplaintReadProgress {

    private final AtomicInteger dayCnt = new AtomicInteger();
    private final AtomicInteger dayIdx = new AtomicInteger();
    private final AtomicReference<LocalDate> currentDay = new AtomicReference<>();
    private final AtomicLong fetched = new AtomicLong();

    public void reset() {
        dayCnt.set(0);
        dayIdx.set(0);
        currentDay.set(null);
        fetched.set(0);
    }

    public void start(int days) {
        dayCnt.set(days);
    }

    public void day(int idx, LocalDate day) {
        dayIdx.set(idx);
        currentDay.set(day);
    }

    public void fetched(int rows) {
        fetched.addAndGet(rows);
    }

    /** 구간 전체 일수(daily 는 1). Reader 생성 전이면 0. */
    public int dayCnt() {
        return dayCnt.get();
    }

    /** 지금 읽는 일자의 순번(1부터). 아직 시작 전이면 0. */
    public int dayIdx() {
        return dayIdx.get();
    }

    public LocalDate currentDay() {
        return currentDay.get();
    }

    /** 원천 API 로 받은 행 수(페이지 단위로 늘어난다 — 커밋 전 행 포함). */
    public long fetched() {
        return fetched.get();
    }
}
