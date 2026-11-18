package jeju.datahub.batch.cvlcpt;

import jeju.datahub.batch.history.ExcnSe;
import jeju.datahub.batch.cvlcpt.api.DhubSql;
import jeju.datahub.batch.ops.JobParameterFactory;
import jeju.datahub.batch.web.JobExecutionSummary;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 민원 수동 트리거. 기본은 원천 MAX(load_dt)부터 오늘까지 증분 수집이다.
 * from/to는 load_dt 기준(끝일 미포함). daily/backfill은 명시 보정용으로 유지한다.
 * 응답은 동기 JobExecutionSummary이며, 운영 화면은 같은 파라미터 팩토리를 사용한다.
 */
@RestController
@RequestMapping("/api/batch")
public class ComplaintBatchTriggerController {

    private final JobLauncher jobLauncher;
    private final Job complaintFetchJob;

    public ComplaintBatchTriggerController(JobLauncher jobLauncher, Job complaintFetchJob) {
        this.jobLauncher = jobLauncher;
        this.complaintFetchJob = complaintFetchJob;
    }

    /**
     * @param mode       {@code daily} | {@code backfill}(선택). 없으면 incremental
     * @param targetDate daily 기준일 {@code yyyy-MM-dd}(선택). 없으면 전일
     * @param from       backfill 시작일(포함, 선택). 없으면 {@code dhub.backfill-from}
     * @param to         backfill 끝일(<b>미포함</b>, 선택). 없으면 {@code dhub.backfill-to}(비면 실행일)
     * @throws Exception {@code JobExecutionException} 계열. 로컬 트리거라 그대로 500 으로 드러내는
     *                   편이 원인 파악에 낫다
     */
    @PostMapping("/complaint-fetch")
    public JobExecutionSummary runComplaintFetch(
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String targetDate,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws Exception {

        JobExecution execution = jobLauncher.run(
                complaintFetchJob, buildParameters(mode, targetDate, from, to));
        return JobExecutionSummary.of(execution);
    }

    /**
     * 스케줄러와 공유하는 파라미터 조립 — "{@code runAt} 을 빼먹는" 실수를 한 곳에서만 막는다.
     *
     * <p>일자는 {@link DhubSql#date(String)} 로 <b>파싱해 정규화한 뒤</b> 넣는다. HTTP 파라미터가
     * SQL 문자열로 흘러가는 경로이므로(이 API 에는 바인딩 파라미터가 없다) 관문을 앞당겨 둔다 —
     * 형식이 틀리면 Job 이 시작되기도 전에 400/500 으로 끊긴다.
     */
    static JobParameters buildParameters(String mode, String targetDate, String from, String to) {
        // 🔁 조립 본문은 JobParameterFactory 로 옮겼다(운영 API 와 같은 메서드). runAt·null 가드·
        //    DhubSql.date 정규화는 거기서 한다. 실행 구분(API, 비식별)만 이 진입점이 붙인다.
        return ExcnSe.tag(JobParameterFactory.complaintFetch(mode, targetDate, from, to), ExcnSe.API)
                .toJobParameters();
    }
}
