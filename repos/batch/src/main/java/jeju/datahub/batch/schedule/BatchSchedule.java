package jeju.datahub.batch.schedule;

/**
 * {@code batch.tb_btch_schdl} 한 행 — 잡별 cron · 사용여부 (TSK-4337 Step 3 · D5).
 *
 * @param jobNm    Spring Batch Job 이름(PK)
 * @param cronExpr Spring 6필드 cron(초 분 시 일 월 요일, Asia/Seoul)
 * @param useYn    {@code Y} = 스케줄 사용 · {@code N} = 중지(수동 실행은 가능)
 */
public record BatchSchedule(String jobNm, String cronExpr, String useYn) {

    /** {@code use_yn='Y'} 인가. */
    public boolean inUse() {
        return "Y".equals(useYn);
    }
}
