package jeju.datahub.complaint.service;

import java.time.LocalDate;

/**
 * 민원현황(GIS) 화면의 조회 단위 enum.
 *
 * * 결정 (2026-08-21): 화면 mock 의의 "어제/주간/월간"을 그대로 API enum 에 매핑.
 * * default {@link #YESTERDAY} (Dashboard 의 {@code TODAY} 와 다름).
 * * 의미/기간:
 * * * {@link #YESTERDAY} — 어제 (직전 1일).  [오늘-1, 오늘-1]
 * * * {@link #WEEK}      — 어제까지 7일.      [오늘-7, 오늘-1]
 * * * {@link #MONTH}     — 어제까지 30일.     [오늘-30, 오늘-1]
 *
 * * 🔁 2026-11-04 주간·월간의 종료일을 오늘에서 어제로 옮겼다. 민원은 매일 02:00 에 수집되어
 *   오늘 칸이 거의 비므로, 오늘을 넣으면 6일을 직전 7일과 비교하게 되어 감소로 치우친다.
 *   🔴 {@code DashboardUnit} 은 별도 enum 이다 — 구간을 바꾸면 두 곳을 함께 고친다(숫자가 화면마다 갈린다).
 *
 * * 화이트리스트 밖 값이면 Spring 이 400 을 낸다. {@code null} 이면 default {@code YESTERDAY}.
 *
 * * SQL 인젝션 방어: enum 만 받고 구간은 자바에서 {@link LocalDate} 로 계산해 {@code #{}} 로
 *   매퍼에 전달한다 — SQL 에는 enum 문자열이 닿지 않는다.
 */
public enum ComplaintUnit {

    /** 어제 하루: [오늘-1, 오늘-1] */
    YESTERDAY(1, 1),

    /** 어제까지 7일: [오늘-7, 오늘-1] */
    WEEK(7, 1),

    /** 어제까지 30일: [오늘-30, 오늘-1] */
    MONTH(30, 1);

    /** 구간 시작 offset (오늘로부터 N 일 전). */
    private final int startDaysBack;

    /** 구간 종료 offset (오늘로부터 N 일 전). */
    private final int endDaysBack;

    ComplaintUnit(int startDaysBack, int endDaysBack) {
        this.startDaysBack = startDaysBack;
        this.endDaysBack = endDaysBack;
    }

    /**
     * 오늘 기준 구간을 {@link LocalDate} 쌍으로 반환.
     * @param today 기준일 (테스트 가능하도록 주입 — 운영에선 {@link LocalDate#now()})
     */
    public LocalDate[] resolveRange(LocalDate today) {
        return new LocalDate[] {
                today.minusDays(startDaysBack),
                today.minusDays(endDaysBack)
        };
    }

    /**
     * 직전 동일 길이 구간의 시작일. 예: 오늘~어제(2일) → prevStart = 그저께.
     * 직전 구간 종료일은 항상 {@code curStart.minusDays(1)} 이므로 호출부가 직접 구한다.
     *
     * <p>종전 {@code ComplaintServiceImpl.resolveRange} 안에 있던 식을 그대로 옮긴
     * 공용 헬퍼다. 민원 KPI와 분석레이어 순위(enforcement·shortage)가 같은 "직전 구간"
     * 정의를 써야 증감률이 서로 설명되므로, 식을 두 벌로 두지 않는다.
     *
     * @param curStart 현재 구간 시작일
     * @param curEnd   현재 구간 종료일
     */
    public static LocalDate prevStart(LocalDate curStart, LocalDate curEnd) {
        long curLenDays = curEnd.toEpochDay() - curStart.toEpochDay() + 1;
        return curStart.minusDays(curLenDays);
    }
}