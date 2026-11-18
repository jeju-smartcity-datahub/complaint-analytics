package jeju.datahub.batch.utztn;

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
 * {@code pkltUtztnFetchJob} 수동 트리거.
 *
 * <p>⚠️ <b>동기 실행</b>이다 — 전량(2026-09-29 실측 4,308행 · 원천 왕복 17회)이면 응답이 그동안 블로킹된다.
 * ⚠️ <b>무인증</b>이다(로컬 기본값). dev/prod 는 {@code batch.api.auth.enabled=true} 로 API Key 를 요구한다.
 *
 * <p>🔴 {@code runAt} 으로 매 실행 새 {@code JobInstance} 를 보장한다 — 없으면 두 번째 실행부터
 * {@code JobInstanceAlreadyCompleteException} 이다(형제 컨트롤러와 같은 함정 회피).
 *
 * <p>🔴 이 경로는 {@code utztn.sync.scheduled} 와 <b>무관하게</b> 동작한다 — 그 플래그는 자동 실행
 * (월별 스케줄 + 주차장 연쇄)만 끈다. ⚠️ 뒤집어 말하면 <b>수동 트리거로 주차장만 돌린 뒤에는 연쇄가 없으므로
 * 이 트리거를 직접 눌러야 한다</b>(부모 표 재적재 복구 순서 ④).
 */
@RestController
@RequestMapping("/api/batch")
public class PkltUtztnFetchController {

    private final JobLauncher jobLauncher;
    private final Job pkltUtztnFetchJob;

    public PkltUtztnFetchController(JobLauncher jobLauncher, Job pkltUtztnFetchJob) {
        this.jobLauncher = jobLauncher;
        this.pkltUtztnFetchJob = pkltUtztnFetchJob;
    }

    @PostMapping("/pklt-utztn-fetch")
    public JobExecutionSummary runPkltUtztnFetch() throws Exception {
        JobParameters params = ExcnSe.tag(JobParameterFactory.base(), ExcnSe.API)   // 비식별
                .toJobParameters();
        JobExecution execution = jobLauncher.run(pkltUtztnFetchJob, params);
        return JobExecutionSummary.of(execution);
    }
}
