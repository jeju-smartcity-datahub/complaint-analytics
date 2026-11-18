package jeju.datahub.batch.ops;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

/**
 * 실행 레지스트리 — "이 프로세스에서 지금 돌고 있는 실행" 의 정본.
 *
 * <p>9개 잡 전부의 JobConfig 가 {@code .listener(...)} 로 <b>리스너 체인의 맨 뒤</b>에 붙인다.
 *
 * <h2>🔴 왜 맨 뒤인가 — 해제 보장</h2>
 * {@code CompositeJobExecutionListener}(Spring Batch 5.2.4 소스 확인)는 {@code beforeJob} 을 등록 순서, {@code afterJob} 을
 * <b>역순</b>으로 부르고 <b>리스너별 예외를 막지 않는다</b> — 앞 리스너의 {@code afterJob} 이 던지면 뒤 순번은 불리지 않는다.
 * 맨 앞에 두면 {@code afterJob} 이 맨 마지막이라, 이력·요약 리스너(try/catch 밖 코드)가 던지는 순간 해제가 빠져
 * 끝난 실행이 영원히 "실행 중" 으로 남는다(그 잡의 abandon 이 계속 409). 맨 뒤면 {@code afterJob} 이 <b>가장 먼저</b>
 * 불린다. 대가로 {@code beforeJob} 이 맨 마지막이 되는데, 앞 리스너 {@code beforeJob} 예외로 등록이 안 되는 것은
 * 안전한 쪽이다(실행은 곧 FAILED 로 끝나고 좀비로 오판될 창도 없다). 순서는 {@code RunningJobRegistryOrderTest} 가 단언한다.
 *
 * <h2>🔴 왜 비동기 런처가 아니라 Job 리스너인가</h2>
 * 런처 기준이면 스케줄러·기존 HTTP 트리거가 <b>동기 {@code JobLauncher} 로 띄운 실행</b>(02:00 민원·연쇄 위험판정 등)이
 * 빠진다. 그러면 abandon 이 진짜 돌고 있는 실행을 좀비로 보고 {@code FAILED} 로 덮는다. 리스너는 어느 런처로
 * 띄웠든 Job 본문이 도는 동안만 등록돼 있다.
 *
 * <h2>"좀비" 판정</h2>
 * DB({@code batch_job_execution})는 {@code STARTED} 인데 여기 없으면 좀비다 — 프로세스가 죽어 {@code afterJob} 이
 * 불리지 못한 실행이다. ⚠️ 배치 인스턴스 1개 전제다(인스턴스가 여럿이면 남의 프로세스 실행이 좀비로 보인다).
 * ⚠️ 실행 생성({@code STARTING}) ~ {@code beforeJob} 사이 수 ms 는 여기 없다 — 그 찰나의 abandon 은 막지 못한다.
 */
@Component
public class RunningJobRegistryListener implements JobExecutionListener {

    /** jobExecutionId → jobNm. */
    private final Map<Long, String> running = new ConcurrentHashMap<>();

    @Override
    public void beforeJob(JobExecution jobExecution) {
        if (jobExecution.getId() != null) {
            running.put(jobExecution.getId(), jobExecution.getJobInstance().getJobName());
        }
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getId() != null) {
            running.remove(jobExecution.getId());
        }
    }

    /** 이 실행이 이 프로세스에서 돌고 있나. */
    public boolean isRunning(long jobExecutionId) {
        return running.containsKey(jobExecutionId);
    }

    /** 이 잡의 실행이 하나라도 이 프로세스에서 돌고 있나(스케줄 실행 직전 겹침 확인용). */
    public boolean isJobRunning(String jobNm) {
        return running.containsValue(jobNm);
    }

    /** 이 잡의 실행 중 ID 들. */
    public Set<Long> runningIds(String jobNm) {
        return running.entrySet().stream().filter(e -> e.getValue().equals(jobNm))
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }
}
