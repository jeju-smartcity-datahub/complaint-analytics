package jeju.datahub.batch.schedule;

/** {@link BatchScheduleRegistry#reschedule(String)} 결과 (TSK-4337 Step 3 — Step 4 운영 API 가 응답에 쓴다). */
public enum ScheduleState {
    /** cron 으로 등록됐다. */
    REGISTERED,
    /** 표가 {@code use_yn='N'} 이라 등록하지 않았다. */
    DISABLED,
    /** env {@code *.sync.scheduled=false} 라 스케줄러 빈이 없다(D5 상위 차단). */
    BLOCKED,
    /** 표의 cron 이 {@code CronExpression.parse} 를 통과하지 못했다. */
    INVALID_CRON,
    /** 표에는 있는데 실행 메서드가 매핑돼 있지 않다. */
    NO_TARGET,
    /** 실행 메서드는 있는데 표에 행이 없다. */
    NO_ROW
}
