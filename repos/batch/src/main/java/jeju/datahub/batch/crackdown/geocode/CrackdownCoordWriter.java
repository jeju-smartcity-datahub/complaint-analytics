package jeju.datahub.batch.crackdown.geocode;

import javax.sql.DataSource;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.ItemPreparedStatementSetter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.stereotype.Component;

/**
 * {@code crackdownGeocodeStep} 의 Writer — 주소 1건의 좌표·행정동을 <b>같은 주소의 좌표 없는 모든 행</b>에
 * 쓴다 (TSK-4054 계획서 Step 6 · D2).
 *
 * <h2>JPA 를 쓰지 않는 이유</h2>
 * {@code rule_persistence-jpa-mybatis.md} 의 "쓰기=JPA" 는 백엔드 규칙이며, 배치 대량 적재는
 * 그 적용 대상 밖이다 — {@code JdbcBatchItemWriter} + 조건부 {@code UPDATE … AND lat IS NULL} 로
 * 멱등성을 SQL 로 보장한다({@code CrackdownItemWriter} 와 동일 근거).
 *
 * <h2>{@code AND lat IS NULL} + {@code assertUpdates=false}</h2>
 * 이미 좌표가 있는 행(시드·사전 전파·이전 실행)은 덮어쓰지 않는다. 그 사이 다른 경로가 먼저 채웠으면
 * 0행 갱신이 정상이라 {@code assertUpdates} 를 끈다(켜면 {@code EmptyResultDataAccessException} 으로 죽는다).
 *
 * <h2>🔴 {@code geom} 은 쓰지 않는다</h2>
 * {@code lat/lot} 에서 계산되는 생성 컬럼({@code GENERATED ALWAYS … STORED})이라 쓰면 오류다.
 */
@Component
public class CrackdownCoordWriter implements ItemWriter<CrackdownCoord> {

    static final String UPDATE_SQL = """
            UPDATE parking.tb_crdn_dsctn
               SET lat = ?, lot = ?, emd_cd = ?
             WHERE crdn_addr = ? AND lat IS NULL
            """;

    /** 파라미터 순서 {@code lat, lot, emd_cd, crdn_addr} — 테스트가 직접 호출해 확인한다. */
    static final ItemPreparedStatementSetter<CrackdownCoord> SETTER = (item, ps) -> {
        ps.setBigDecimal(1, item.lat());
        ps.setBigDecimal(2, item.lot());
        ps.setString(3, item.emdCd());
        ps.setString(4, item.crdnAddr());
    };

    private final JdbcBatchItemWriter<CrackdownCoord> delegate;

    public CrackdownCoordWriter(DataSource dataSource) {
        this.delegate = new JdbcBatchItemWriter<CrackdownCoord>() {};
        this.delegate.setDataSource(dataSource);
        this.delegate.setSql(UPDATE_SQL);
        this.delegate.setItemPreparedStatementSetter(SETTER);
        // 다른 경로가 먼저 채웠으면 0행 갱신이 정상 — assert 를 켜면 예외로 죽는다.
        this.delegate.setAssertUpdates(false);
    }

    @Override
    public void write(Chunk<? extends CrackdownCoord> chunk) throws Exception {
        delegate.write(chunk);
    }
}
