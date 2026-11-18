package jeju.datahub.batch.schedule;

/**
 * 스케줄 대상 잡 하나 — 잡 이름 · env 차단 스위치 키 · 실행 메서드.
 *
 * @param jobNm  {@code batch.tb_btch_schdl.job_nm} 과 같은 Job 이름
 * @param envKey 상위 차단 스위치 키({@code *.sync.scheduled}). 🔴 단속처럼 스위치가 없는 잡은 {@code null}
 * @param action 스케줄러 빈의 실행 메서드. 🔴 {@code null} = 그 빈이 없다(= {@code @ConditionalOnProperty}
 *               가 env {@code false} 로 뺐다) — 레지스트리가 "env 차단" 으로 다룬다
 */
public record ScheduleTarget(String jobNm, String envKey, Runnable action) {
}
