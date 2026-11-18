package jeju.datahub.batch.ops;

import org.slf4j.MDC;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * 실행별 로그 파일 라우팅 키를 MDC 에 싣는다 (TSK-4337 Step 5 · D7 ②).
 *
 * <p>{@code log4j2-spring.xml} 의 Routing appender 가 {@code ctx:jobExecutionId} 가 있으면
 * {@code ${LOG_PATH}/batch-jobs/{jobNm}/{jobExecutionId}.log} 로 보낸다. 없으면 콘솔만.
 *
 * <h2>🔴 체인 맨 앞에 붙인다 — 그리고 해제를 이 리스너에만 맡기지 않는다</h2>
 * {@code CompositeJobExecutionListener} 는 {@code beforeJob} 을 등록 순, {@code afterJob} 을 역순으로 부르고 리스너별
 * 예외를 막지 않는다({@link RunningJobRegistryListener} 주석). 맨 앞이면 {@code beforeJob} 이 가장 먼저라 다른 리스너의
 * 시작·종료 로그까지 그 실행 파일에 담기지만, {@code afterJob} 이 가장 나중이라 <b>앞 리스너 예외 시 해제가 빠진다</b>.
 * 레지스트리 리스너(맨 뒤)에 합치면 반대로 다른 리스너 로그가 파일에서 빠진다. 그래서:
 * <ol>
 *   <li>{@code beforeJob} 은 항상 <b>덮어쓴다</b> — 같은 스레드의 다음 실행(연쇄 위험판정 등)은 제 키로 시작한다.</li>
 *   <li>실행 진입점 3곳이 {@code try/finally} 로 {@link #clear()} 한다 — 비동기 런처의 실행 Runnable
 *       ({@link AsyncJobLauncher}) · 스케줄 fired 래퍼({@code BatchScheduleRegistry.run}) · HTTP 트리거 요청
 *       ({@link JobLogMdcClearFilter}, 톰캣 스레드). 이게 없으면 스레드풀(스케줄러 1스레드·톰캣)에 이전
 *       {@code jobExecutionId} 가 남아 <b>무관한 다음 로그가 이전 실행 파일로 라우팅</b>된다.</li>
 * </ol>
 *
 * <p>상태가 없어 빈이 아니라 {@link #INSTANCE} 로 붙인다 — JobConfig 의 {@code @Bean} 시그니처를 늘리지 않는다
 * (그 메서드를 직접 부르는 테스트가 있다).
 *
 * <p>⚠️ MDC 는 스레드 로컬이다. 지금 잡은 전부 단일 스레드 Step(Step 에 {@code taskExecutor}·partition·split 없음 —
 * 2026-11-11 grep 확인)이라 Job 리스너 스레드 = Step 스레드다. 멀티스레드 Step 을 도입하면 그 스레드의 로그는 파일로 가지 않는다.
 */
public final class JobLogMdcListener implements JobExecutionListener {

    public static final String KEY_EXECUTION_ID = "jobExecutionId";
    public static final String KEY_JOB_NM = "jobNm";

    public static final JobLogMdcListener INSTANCE = new JobLogMdcListener();

    private JobLogMdcListener() {
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        if (jobExecution.getId() == null) {
            clear();
            return;
        }
        MDC.put(KEY_JOB_NM, jobExecution.getJobInstance().getJobName());
        MDC.put(KEY_EXECUTION_ID, String.valueOf(jobExecution.getId()));
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        clear();
    }

    /** 라우팅 키 2개만 지운다(다른 MDC 키는 건드리지 않는다). 진입점 finally 가 부른다. */
    public static void clear() {
        MDC.remove(KEY_EXECUTION_ID);
        MDC.remove(KEY_JOB_NM);
    }
}
