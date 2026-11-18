package jeju.datahub.batch.acdnt;

import jeju.datahub.batch.history.ExcnSe;
import jeju.datahub.batch.ops.JobParameterFactory;
import jeju.datahub.batch.web.JobExecutionSummary;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/batch")
public class PkltAcdntFetchController {
    private final JobLauncher launcher;
    private final Job pkltAcdntFetchJob;
    public PkltAcdntFetchController(JobLauncher launcher, Job pkltAcdntFetchJob) {
        this.launcher = launcher;
        this.pkltAcdntFetchJob = pkltAcdntFetchJob;
    }
    @PostMapping("/pklt-acdnt-fetch")
    public JobExecutionSummary run(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws Exception {
        return JobExecutionSummary.of(launcher.run(pkltAcdntFetchJob,
                ExcnSe.tag(JobParameterFactory.pkltAcdntFetch(from, to), ExcnSe.API).toJobParameters()));
    }
}
