package jeju.datahub.batch.sftychck;

import javax.sql.DataSource;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import jeju.datahub.batch.collect.ParkingObservationHash;

/** 점검일이 같아도 실행이 다르면 새 관측을 추가한다. 기존 점검/시드 이력은 갱신·삭제하지 않는다. */
@Component
public class PkltSftyChckItemWriter implements ItemWriter<PkltSftyChckRow>, StepExecutionListener {
    static final String SEED_RGTR_ID = "admin";
    static final String BATCH_RGTR_ID = "BATCH";
    static final String UPSERT = """
            INSERT INTO parking.tb_pklt_sfty_chck
                (pklt_id, chck_ymd, chck_prd_cd, sfty_chck_type_cd, sfty_chck_rslt_cd, rmrk_cn,
                 rgtr_id, reg_dt, load_dt, btch_excn_id, src_hash, src_json)
            VALUES (?, ?, ?, ?, ?, ?, 'BATCH', now(), now(), ?, ?, ?::jsonb)
            ON CONFLICT (btch_excn_id, src_hash) DO NOTHING
            """;
    private final JdbcTemplate jdbc;
    private Long executionId;
    public PkltSftyChckItemWriter(DataSource dataSource) { jdbc = new JdbcTemplate(dataSource); }
    @Override public void beforeStep(StepExecution step) { executionId = step.getJobExecutionId(); }
    @Override public void write(Chunk<? extends PkltSftyChckRow> chunk) {
        jdbc.batchUpdate(UPSERT, chunk.getItems().stream().map(r -> {
            String payload = r.srcJson();
            String hashInput = payload == null ? r.toString() : payload;
            return new Object[] {r.pkltId(), r.chckYmd(), r.chckPrdCd(), r.sftyChckTypeCd(),
                    r.sftyChckRsltCd(), r.rmrkCn(), executionId,
                    ParkingObservationHash.of(r.pkltId(), hashInput), payload};
        }).toList());
    }
}
