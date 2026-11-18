package jeju.datahub.batch.ops;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * 운영 API 전용 비동기 런처 (TSK-4337 Step 4 · D4).
 *
 * <h2>🔴 {@code JobLauncher} 빈으로 등록하지 않는다</h2>
 * 기존 트리거 컨트롤러·스케줄러는 {@code JobLauncher} 를 <b>타입으로</b> 주입받고, 그 기본 런처의 <b>동기 결과</b>
 * ({@code COMPLETED} 여부)로 연쇄를 판단한다. 같은 타입 빈이 하나 더 생기면 주입이 모호해져 기동이 죽거나,
 * {@code @Primary} 로 풀면 기본 런처가 바뀔 위험이 있다. 그래서 {@link TaskExecutorJobLauncher} 를 이 클래스의
 * <b>필드</b>로만 만들고({@code afterPropertiesSet} 직접 호출), 이 클래스는 {@code JobLauncher} 를 구현하지 않는다.
 *
 * <h2>스레드</h2>
 * 실행마다 새 데몬 스레드({@code batch-ops-N})다. 같은 잡은 운영 API 가 409 로 막으므로 동시에 많아야 잡 수(9)만큼이다.
 * 스케줄 직렬화(풀 1)와는 무관하다 — 수동 실행이 02:00 스케줄을 기다리게 하지 않는다.
 */
@Component
public class AsyncJobLauncher {

    private final TaskExecutorJobLauncher delegate;

    public AsyncJobLauncher(JobRepository jobRepository) throws Exception {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("batch-ops-");
        executor.setDaemon(true);
        // TSK-4337 Step 5 — 실행 진입점 MDC 정리(JobLogMdcListener 주석의 이중 장치). 스레드는 실행마다 새로 만들지만
        // 실행기를 풀로 바꿔도 이전 jobExecutionId 가 남지 않게 진입점에서 지운다.
        executor.setTaskDecorator(task -> () -> {
            try {
                new SyncTaskExecutor().execute(task);   // 시큐어코딩 7.2(TSK-4336) — run() 직접 호출 대신 같은 스레드 실행기(동작 같음)
            } finally {
                JobLogMdcListener.clear();
            }
        });
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
        this.delegate = launcher;
    }

    /** 실행 행을 만든 뒤 즉시 반환한다(Job 본문은 별도 스레드). */
    public JobExecution run(Job job, JobParameters parameters) throws JobExecutionAlreadyRunningException,
            JobRestartException, JobInstanceAlreadyCompleteException, JobParametersInvalidException {
        return delegate.run(job, parameters);
    }
}
