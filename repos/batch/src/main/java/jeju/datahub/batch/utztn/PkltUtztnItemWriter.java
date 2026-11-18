package jeju.datahub.batch.utztn;

import javax.sql.DataSource;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import jeju.datahub.batch.collect.ParkingObservationHash;

/** 매 수집 실행을 별도 관측으로 추가한다. 동일 실행의 청크 재시도만 중복 방지한다. */
@Component
public class PkltUtztnItemWriter implements ItemWriter<PkltUtztnRow>, StepExecutionListener {
    private static final String INSERT = """
            INSERT INTO parking.tb_pklt_utztn_pttn
                (pklt_id, tot_ym, prk_cntom, day_avg_trnvr_rt, load_dt, btch_excn_id, src_hash, src_json)
            VALUES (?, ?, ?, ?, now(), ?, ?, ?::jsonb)
            ON CONFLICT (btch_excn_id, src_hash) DO NOTHING
            """;
    private final JdbcTemplate jdbc;
    private Long executionId;
    public PkltUtztnItemWriter(DataSource dataSource) { jdbc = new JdbcTemplate(dataSource); }
    @Override public void beforeStep(StepExecution step) { executionId = step.getJobExecutionId(); }
    @Override public void write(Chunk<? extends PkltUtztnRow> chunk) {
        jdbc.batchUpdate(INSERT, chunk.getItems().stream().map(r -> {
            String payload = r.srcJson();
            // 수동/테스트 호출에도 결정적인 해시를 사용한다. 실제 원천은 JSON 전체를 보존한다.
            String hashInput = payload == null ? r.toString() : payload;
            return new Object[] {r.pkltId(), r.totYm(), r.prkCntom(), r.dayAvgTrnvrRt(),
                    executionId, ParkingObservationHash.of(r.pkltId(), hashInput), payload};
        }).toList());
    }
}
