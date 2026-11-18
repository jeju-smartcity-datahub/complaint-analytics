package jeju.datahub.batch.zonenm;

import jeju.datahub.batch.history.ExcnSe;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.batch.ops.JobParameterFactory;
import jeju.datahub.batch.web.JobExecutionSummary;

/**
 * {@code zoneNmPrefillJob} 수동 트리거.
 *
 * <pre>
 * # 전 크기(1000→500→250→100)
 * curl -X POST 'http://localhost:8280/api/batch/zone-nm-prefill'
 *
 * # 한 크기만 — VWorld 일일 한도 때문에 첫 채우기를 며칠에 나눌 때
 * curl -X POST 'http://localhost:8280/api/batch/zone-nm-prefill?gridSz=1000'
 * </pre>
 *
 * <p>⚠️ <b>동기 실행</b> — {@code JobLauncher} 기본이 {@code SyncTaskExecutor} 라 Job 이 끝날 때까지 응답이
 * 블로킹된다(첫 채우기는 크기당 수 분~수십 분). 응답은 {@link JobExecutionSummary}
 * (read = 시도한 셀 · written = 저장 · filtered = 일시실패).
 *
 * <p>⚠️ HTTP 파라미터는 Job 시작 전에 검증한다 — {@code gridSz} 가 1000·500·250·100 이 아니면 400.
 * {@code batch.api.auth.enabled=false}(local 기본)면 무인증이다(다른 트리거와 같다).
 */
@RestController
@RequestMapping("/api/batch")
public class ZoneNmPrefillController {

    // 🔴 Job 은 빈 이름으로 주입된다 — 파라미터명이 @Bean 메서드명(zoneNmPrefillJob)과 같아야 한다.
    private final JobLauncher jobLauncher;
    private final Job zoneNmPrefillJob;

    public ZoneNmPrefillController(JobLauncher jobLauncher, Job zoneNmPrefillJob) {
        this.jobLauncher = jobLauncher;
        this.zoneNmPrefillJob = zoneNmPrefillJob;
    }

    /**
     * {@code POST /api/batch/zone-nm-prefill[?gridSz=1000]}
     *
     * @param gridSz 격자 크기(m, 선택). 없으면 전 크기
     */
    @PostMapping("/zone-nm-prefill")
    public JobExecutionSummary run(@RequestParam(required = false) Long gridSz) throws Exception {
        if (gridSz != null && !ZoneNmPrefillTasklet.isSupported(gridSz)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "gridSz 는 1000·500·250·100 중 하나여야 합니다: " + gridSz);
        }
        // 🔁 runAt·gridSz 조립은 JobParameterFactory 한 곳(운영 API 와 공유).
        JobParametersBuilder params = ExcnSe.tag(JobParameterFactory.zoneNmPrefill(gridSz), ExcnSe.API);
        JobExecution execution = jobLauncher.run(zoneNmPrefillJob, params.toJobParameters());
        return JobExecutionSummary.of(execution);
    }
}
