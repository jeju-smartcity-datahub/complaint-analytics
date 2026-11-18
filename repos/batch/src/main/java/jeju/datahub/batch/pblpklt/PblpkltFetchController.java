package jeju.datahub.batch.pblpklt;

import jeju.datahub.batch.history.ExcnSe;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import jeju.datahub.batch.ops.JobParameterFactory;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import jeju.datahub.batch.web.JobExecutionSummary;
import org.springframework.web.bind.annotation.RestController;

/**
 * ①{@code pblpkltFetchJob} 수동 트리거.
 *
 * <pre>
 * # 기본 — pklt_id keyset 페이징으로 전량 수집 (수동 트리거)
 * curl -X POST 'http://localhost:8280/api/batch/pblpklt-fetch'
 * </pre>
 *
 * <p>cvlcpt 와 달리 <b>파라미터가 없다</b>. pblpklt 는 일자 컬럼이 없어 daily/backfill 분기가 무의미하고
 * (2026-08-11 E 답변). 🔁 2026-09-17 — 페이징은 파라미터가 아니라 설정 {@code dhub.pblpklt-page-size} 로 한다.
 *
 * <h2>⚠️ 로컬 개발용·무인증 엔드포인트 (cvlcpt 와 동일)</h2>
 * 인증 없음. 운영 노출 시 인증/네트워크 차단 필수.
 *
 * <h2>⚠️ 동기 실행</h2>
 * 전량 수집·가공이 끝날 때까지 응답이 블로킹된다(2026-09-17 실측 원천 2,272행 약 5분). 더 길어지면
 * 비동기(별도 executor)로 전환을 검토한다.
 */
@RestController
@RequestMapping("/api/batch")
public class PblpkltFetchController {

    private final JobLauncher jobLauncher;
    private final Job pblpkltFetchJob;

    public PblpkltFetchController(JobLauncher jobLauncher, Job pblpkltFetchJob) {
        this.jobLauncher = jobLauncher;
        this.pblpkltFetchJob = pblpkltFetchJob;
    }

    /**
     * 🔴 2026-08-24 — 반환형을 {@link JobExecution} 에서 {@link JobExecutionSummary} 로 바꿨다.
     *
     * <p>종전에는 {@code JobExecution} 을 그대로 돌려줬는데, 그 객체 그래프를 Jackson 이
     * 직렬화하면서 <b>{@code Document nesting depth (1001) exceeds the maximum allowed (1000)}</b>
     * 로 응답이 깨졌다. Job 자체는 정상 종료하는데 호출자는 결과를 받지 못했다 —
     * 다른 두 트리거는 처음부터 요약 record 를 썼고 이 하나만 빠져 있었다.
     */
    @PostMapping("/pblpklt-fetch")
    public JobExecutionSummary runPblpkltFetch() throws Exception {
        // runAt 으로 매 실행 새 JobInstance 를 보장 (cvlcpt 컨트롤러와 같은 두 번째 함정 회피).
        JobParameters params = ExcnSe.tag(JobParameterFactory.base(), ExcnSe.API)   // 비식별
                .toJobParameters();
        return JobExecutionSummary.of(jobLauncher.run(pblpkltFetchJob, params));
    }
}
