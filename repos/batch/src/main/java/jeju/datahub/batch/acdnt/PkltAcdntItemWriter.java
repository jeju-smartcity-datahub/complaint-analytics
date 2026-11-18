package jeju.datahub.batch.acdnt;

import javax.sql.DataSource;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.stereotype.Component;

/** 배치 대량 적재는 백엔드 JPA 관례의 적용 밖이다. JDBC upsert로 재수집 중복을 막는다. */
@Component
public class PkltAcdntItemWriter implements ItemWriter<PkltAcdntRow>, StepExecutionListener {
    static final String UPSERT_SQL = """
            INSERT INTO parking.tb_pklt_acdnt_hstry
              (pklt_id, acdnt_dt, svrt_cd, acdnt_type, origin_pk, pklt_nm, pklt_addr,
               prcs_cn, prcs_stts_nm, src_json, src_hash, reg_dt, btch_excn_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul', ?)
            ON CONFLICT (src_hash) DO UPDATE SET
              svrt_cd=EXCLUDED.svrt_cd, origin_pk=EXCLUDED.origin_pk,
              pklt_nm=EXCLUDED.pklt_nm, pklt_addr=EXCLUDED.pklt_addr,
              prcs_cn=EXCLUDED.prcs_cn, prcs_stts_nm=EXCLUDED.prcs_stts_nm,
              src_json=EXCLUDED.src_json, btch_excn_id=EXCLUDED.btch_excn_id
            """;
    private final JdbcBatchItemWriter<PkltAcdntRow> delegate;
    private Long executionId;
    public PkltAcdntItemWriter(DataSource dataSource) {
        delegate = new JdbcBatchItemWriter<>();
        delegate.setDataSource(dataSource);
        delegate.setSql(UPSERT_SQL);
        delegate.setItemPreparedStatementSetter((item, ps) -> {
            var row = item.source();
            ps.setLong(1, item.pkltId());
            ps.setObject(2, row.acdntDt());
            ps.setString(3, row.svrtCd());
            ps.setString(4, row.acdntType());
            ps.setString(5, row.originPk());
            ps.setString(6, row.pkltNm());
            ps.setString(7, row.pkltAddr());
            ps.setString(8, row.prcsCn());
            ps.setString(9, row.prcsSttsNm());
            ps.setString(10, row.srcJson());
            ps.setString(11, item.srcHash());
            ps.setObject(12, executionId);
        });
    }
    @Override public void beforeStep(StepExecution step) { executionId = step.getJobExecutionId(); }
    @Override public void write(Chunk<? extends PkltAcdntRow> chunk) throws Exception { delegate.write(chunk); }
}
