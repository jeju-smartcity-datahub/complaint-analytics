package jeju.datahub.batch.crackdown;

import javax.sql.DataSource;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.ItemPreparedStatementSetter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.crackdown.api.CrackdownOriginRow;

/**
 * {@code crackdownFetchJob} 의 Writer — {@code parking.tb_crdn_dsctn} 단일 테이블 적재.
 * 컬럼명은 원천 데이터셋과 동일하다(2026-09-14 스키마 확정 — 이름 변환 없음).
 *
 * <h2>JPA 를 쓰지 않는 이유</h2>
 * "쓰기=JPA" 는 백엔드 규칙이며, 배치 대량 적재는
 * 그 적용 대상 밖이다 — {@code JdbcBatchItemWriter} + {@code INSERT … ON CONFLICT} 로
 * 멱등성을 SQL 로 보장한다(cvlcpt·pblpklt Writer 와 동일 근거).
 *
 * <p>원천 7필드의 정규화 내용 해시(src_hash)로 재수집을 병합한다.
 * 내부 crdn_id와 좌표는 유지하고 source load_dt만 더 큰 값으로 갱신한다.
 */
@Component
public class CrackdownItemWriter implements ItemWriter<CrackdownOriginRow> {

    static final String UPSERT_SQL = """
            INSERT INTO parking.tb_crdn_dsctn
              (crdn_dt, ffnlg, crdn_mthd, carmdl,
               orgnl_cn, crdn_plc, crdn_addr, src_hash, origin_pk, load_dt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (src_hash) DO UPDATE SET
              origin_pk = COALESCE(EXCLUDED.origin_pk, parking.tb_crdn_dsctn.origin_pk),
              load_dt = GREATEST(parking.tb_crdn_dsctn.load_dt, EXCLUDED.load_dt)
            """;

    static final ItemPreparedStatementSetter<CrackdownOriginRow> SETTER = (item, ps) -> {
        ps.setObject(1, item.crdnDt());
        ps.setString(2, item.ffnlg());
        ps.setString(3, item.crdnMthd());
        ps.setString(4, item.carmdl());
        ps.setString(5, item.orgnlCn());
        ps.setString(6, item.crdnPlc());
        ps.setString(7, item.crdnAddr());
        ps.setString(8, item.srcHash());
        ps.setString(9, item.originPk());
        ps.setObject(10, item.loadDt());
    };

    private final JdbcBatchItemWriter<CrackdownOriginRow> delegate;

    public CrackdownItemWriter(DataSource dataSource) {
        this.delegate = new JdbcBatchItemWriter<CrackdownOriginRow>() {};
        this.delegate.setDataSource(dataSource);
        this.delegate.setSql(UPSERT_SQL);
        this.delegate.setItemPreparedStatementSetter(SETTER);
        // 재수집은 기존 행의 원천 load_dt만 갱신한다.
        this.delegate.setAssertUpdates(false);
    }

    @Override
    public void write(Chunk<? extends CrackdownOriginRow> chunk) throws Exception {
        delegate.write(chunk);
    }
}
