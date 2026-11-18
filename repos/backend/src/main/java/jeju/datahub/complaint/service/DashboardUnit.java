package jeju.datahub.complaint.service;

/**
 * 대시보드 조회 단위. 조회 기간(현재 구간)의 시작·종료를 "오늘"로부터의 일수로 정한다.
 *
 * <p>SQL 인젝션 방어: {@code unit} 은 문자열이 아니라 이 enum 으로만 받는다. 컨트롤러가
 * 바인딩에 실패하면(화이트리스트 밖 값) Spring 이 400 을 반환하며, SQL 에는 이 enum 이
 * 직접 닿지 않는다(기간은 자바에서 {@link java.time.LocalDate} 로 계산해 {@code #{}} 로만 전달).
 *
 * <p><b>왜 종료 offset 이 따로 있나</b> — 화면 조회단위 3종은 전부 <b>어제까지</b>다.
 * 🔁 2026-11-04 TSK-4311 — 주간·월간의 종료일을 오늘에서 어제로 옮겼다(민원현황 {@code ComplaintUnit} 과 같은
 * 구간). 수집 배치가 전날분을 가져와 오늘 칸이 거의 비므로, 오늘을 넣으면 6일을 직전 7일과 비교하게 된다.
 */
public enum DashboardUnit {

    /**
     * 오늘 하루: [오늘, 오늘].
     *
     * <p>화면 세그먼트에서는 빠졌지만(TSK-4059 D4) API 호환을 위해 남긴다 — 수집 배치가 매일
     * 02:00 에 <b>전날분</b>을 가져오므로 이 구간은 구조적으로 거의 항상 0건이다.
     */
    TODAY(0, 0),

    /** 어제 하루: [오늘-1, 오늘-1]. 화면 조회단위 "어제" (TSK-4059 D4) */
    YESTERDAY(1, 1),

    /** 어제까지 7일: [오늘-7, 오늘-1] */
    WEEK(7, 1),

    /** 어제까지 30일: [오늘-30, 오늘-1] */
    MONTH(30, 1);

    /** 현재 구간 시작일이 오늘로부터 며칠 전인지 */
    private final int startDaysBack;

    /** 현재 구간 종료일이 오늘로부터 며칠 전인지(0 = 오늘) */
    private final int endDaysBack;

    DashboardUnit(int startDaysBack, int endDaysBack) {
        this.startDaysBack = startDaysBack;
        this.endDaysBack = endDaysBack;
    }

    public int getStartDaysBack() {
        return startDaysBack;
    }

    public int getEndDaysBack() {
        return endDaysBack;
    }
}
