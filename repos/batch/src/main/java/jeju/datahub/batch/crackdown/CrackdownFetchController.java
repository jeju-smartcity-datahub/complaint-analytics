package jeju.datahub.batch.crackdown;


import jeju.datahub.batch.history.ExcnSe;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import jeju.datahub.batch.ops.JobParameterFactory;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jeju.datahub.batch.web.JobExecutionSummary;
import org.springframework.web.bind.annotation.RestController;

/**
 * 단속 수동 트리거. 기본은 원천 MAX(load_dt)부터 오늘까지 증분 수집이다.
 * from/to는 load_dt 기준(끝일 미포함)이며 to만 지정할 수 없다.
 * 원천 ID는 NULL/중복을 허용하고 내부 PK는 DB 시퀀스로 발급한다.
 */
@RestController
@RequestMapping("/api/batch")
public class CrackdownFetchController {

    private final JobLauncher jobLauncher;
    private final Job crackdownFetchJob;

    public CrackdownFetchController(JobLauncher jobLauncher, Job crackdownFetchJob) {
        this.jobLauncher = jobLauncher;
        this.crackdownFetchJob = crackdownFetchJob;
    }

    /**
     * @param from 시작일 {@code yyyy-MM-dd}(포함, 선택). 없으면 원천 load_dt 증분
     * @param to   끝일 {@code yyyy-MM-dd}(미포함, 선택 — {@code from} 이 있을 때만)
     */
    @PostMapping("/crackdown-fetch")
    public JobExecutionSummary runCrackdownFetch(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws Exception {
        // runAt 은 팩토리가 넣는다 — 매 실행 새 JobInstance (cvlcpt 컨트롤러와 같은 함정 회피).
        JobParameters params = ExcnSe.tag(JobParameterFactory.crackdownFetch(from, to), ExcnSe.API)   // TSK-4337 — 비식별
                .toJobParameters();
        JobExecution execution = jobLauncher.run(crackdownFetchJob, params);
        return JobExecutionSummary.of(execution);
    }
}
