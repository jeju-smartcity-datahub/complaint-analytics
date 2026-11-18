package jeju.datahub.batch.sftychck;

import jeju.datahub.batch.history.ExcnSe;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import jeju.datahub.batch.ops.JobParameterFactory;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jeju.datahub.batch.web.JobExecutionSummary;

/**
 * {@code pkltSftyChckFetchJob} 수동 트리거 (TSK-4254 Step 6 · D5).
 *
 * <p>⚠️ <b>동기 실행</b>이다 — 전량을 받는 동안 응답이 블로킹된다.
 * ⚠️ <b>무인증</b>이다(로컬 기본값). dev/prod 는 {@code batch.api.auth.enabled=true} 로 API Key 를 요구한다
 * ({@code ApiKeyAuthFilterConfig} 의 {@code /api/batch/*} 패턴에 이 경로가 포함된다).
 *
 * <p>🔴 {@code runAt} 으로 매 실행 새 {@code JobInstance} 를 보장한다 — 없으면 두 번째 실행부터
 * {@code JobInstanceAlreadyCompleteException} 이다(형제 컨트롤러와 같은 함정 회피).
 *
 * <p>🔴 이 경로는 {@code sftychck.sync.scheduled} 와 <b>무관하게</b> 동작한다. ⚠️ 주차장 수집과 연쇄가 없으므로
 * 주차장을 재적재했으면 이 트리거를 <b>직접</b> 눌러야 한다(D5).
 */
@RestController
@RequestMapping("/api/batch")
public class PkltSftyChckFetchController {

    private final JobLauncher jobLauncher;
    private final Job pkltSftyChckFetchJob;

    public PkltSftyChckFetchController(JobLauncher jobLauncher, Job pkltSftyChckFetchJob) {
        this.jobLauncher = jobLauncher;
        this.pkltSftyChckFetchJob = pkltSftyChckFetchJob;
    }

    @PostMapping("/pklt-sfty-chck-fetch")
    public JobExecutionSummary runPkltSftyChckFetch() throws Exception {
        JobParameters params = ExcnSe.tag(JobParameterFactory.base(), ExcnSe.API)   // TSK-4337 — 비식별
                .toJobParameters();
        JobExecution execution = jobLauncher.run(pkltSftyChckFetchJob, params);
        return JobExecutionSummary.of(execution);
    }
}
