package jeju.datahub.complaint.service;

/**
 * 추이 차트 x축 한 칸이 묶는 시간 단위.
 *
 * <p>{@code unit}(enum)과 마찬가지로 문자열이 아니라 enum 으로만 받는다 — 화이트리스트 밖 값이면
 * Spring 이 400 을 내고, SQL 에는 이 값이 직접 닿지 않는다(서비스가 매퍼 메서드를 고를 뿐이다).
 */
public enum TrendBucket {

    /**
     * 시간대: x축 24칸(00~23). 구간 내 <b>같은 시각끼리 합산</b>한다 —
     * 07시 칸 = (1일 07시 + 2일 07시 + …).
     */
    HOUR,

    /** 일자: x축 = 구간의 날짜 수. 진짜 시계열. (기간 직접 지정 시 기본) */
    DAY
}
