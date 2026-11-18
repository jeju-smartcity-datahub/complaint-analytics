package jeju.datahub.batch.acdnt;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 실자료를 정상 수집한 뒤 임의 SEED-* 사고만 정리한다. 시더와 같은 퇴장 정책이다. */
@Component
public class PkltAcdntSeedCleanupTasklet implements Tasklet {
    private final JdbcTemplate jdbc;
    public PkltAcdntSeedCleanupTasklet(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public RepeatStatus execute(StepContribution contribution, ChunkContext context) {
        var job = context.getStepContext().getStepExecution().getJobExecution();
        boolean collected = job.getStepExecutions().stream()
                .anyMatch(s -> "pkltAcdntFetchStep".equals(s.getStepName()) && s.getWriteCount() > 0);
        if (collected) jdbc.update("""
                DELETE FROM parking.tb_pklt_acdnt_hstry
                 WHERE acdnt_id LIKE 'SEED-%'
                   AND EXISTS (SELECT 1 FROM parking.tb_pklt_acdnt_hstry
                                WHERE acdnt_id NOT LIKE 'SEED-%' AND src_hash IS NOT NULL)
                """);
        return RepeatStatus.FINISHED;
    }
}
