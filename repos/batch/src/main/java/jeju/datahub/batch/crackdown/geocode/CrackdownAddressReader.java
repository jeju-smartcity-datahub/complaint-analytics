package jeju.datahub.batch.crackdown.geocode;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.jdbc.core.SingleColumnRowMapper;

/**
 * {@code crackdownGeocodeStep} 의 Reader 를 만드는 유틸 — <b>좌표가 없는 고유 단속 주소</b>를 한 건씩 낸다.
 *
 * <pre>{@code
 * SELECT DISTINCT crdn_addr
 *   FROM parking.tb_crdn_dsctn
 *  WHERE lat IS NULL AND crdn_addr IS NOT NULL
 *    AND NOT EXISTS (SELECT 1 FROM batch.tb_geocode_skip s
 *                     WHERE s.src_se='CRDN' AND s.trgt_id = encode(sha256(convert_to(crdn_addr,'UTF8')),'hex'))
 *  ORDER BY crdn_addr
 * }</pre>
 *
 * <h2>이 클래스 자신은 빈이 아니다</h2>
 * 운영 빈은 {@code CrackdownFetchJobConfig} 의 {@code @Bean @StepScope} 메서드가
 * {@link #create} 결과를 <b>반환</b>해서 만든다. Job 통합 테스트는 같은 {@link #create} 에
 * {@link #WHERE_CLAUSE} + 픽스처 조건을 넘겨 픽스처 한정 Reader 로 교체한다 — 그래서 SQL 조각이
 * {@code public} 이다({@code crackdown} 패키지에서 읽는다).
 *
 * <h2>🔴 {@code afterPropertiesSet()} 을 여기서 부르지 않는다</h2>
 * {@link JdbcPagingItemReader#afterPropertiesSet()} 는 내부 {@code JdbcTemplate} 과 페이지 SQL 을 만든다.
 * 이 메서드가 반환한 객체를 <b>{@code @Bean} 으로 반환해야</b> 컨테이너가 그것을 불러 준다
 * ({@code InitializingBean}). {@code @Bean} 을 거치지 않고 {@code new} 로만 쓰면 첫 read 에서 죽는다.
 *
 * <h2>keyset 페이징 · {@code setSaveState(false)}</h2>
 * {@link PostgresPagingQueryProvider} + 단일 sortKey 라 2페이지부터 {@code AND ((crdn_addr > ?))} 로
 * 이어 읽는다({@code RiskAlertTargetItemReader} 선례). FROM 이 단일 테이블이라 한정자 없는
 * {@code crdn_addr} 가 모호하지 않고, {@code NOT EXISTS} 하위 쿼리의 {@code crdn_addr} 는 스킵 테이블에
 * 없는 컬럼이라 바깥 행을 가리킨다. 재개는 저장된 페이지 위치가 아니라 {@code lat IS NULL}(Writer 가 채운
 * 주소는 다시 안 읽힌다) + {@code NOT EXISTS}(스킵 확정 주소 제외)가 담당하므로 상태를 저장하지 않는다.
 *
 * <h2>⚠️ {@code maxAddresses == 0} 이면 {@code setMaxItemCount} 를 부르지 않는다</h2>
 * {@code AbstractItemCountingItemStreamItemReader} 는 {@code maxItemCount=0} 이면 첫 read 에서
 * {@code null} 을 돌려 <b>한 건도 읽지 않는다</b>. 0 은 "무제한" 이라는 설정 의미라 걸지 않는다(D9).
 */
public final class CrackdownAddressReader {

    /** 주소 단위 — 18만 행이 약 1만 주소로 줄고, Writer 가 같은 주소의 모든 행을 한 번에 채운다(D2). */
    public static final String SELECT_CLAUSE = "DISTINCT crdn_addr";

    public static final String FROM_CLAUSE = "parking.tb_crdn_dsctn";

    /**
     * 좌표가 없고, 스킵 확정되지 않은 주소.
     *
     * <p>🔴 {@code encode(sha256(convert_to(crdn_addr,'UTF8')),'hex')} 는 스킵 리스너의 {@code trgt_id}
     * (UTF-8 소문자 16진 SHA-256 — md5 대체)와 <b>같은 값</b>
     * 이어야 한다 — 다르면 스킵된 주소를 매 실행 다시 읽는다({@code CrackdownGeocodeSkipListenerTest}).
     */
    public static final String WHERE_CLAUSE = "lat IS NULL AND crdn_addr IS NOT NULL AND NOT EXISTS "
            + "(SELECT 1 FROM batch.tb_geocode_skip s WHERE s.src_se='CRDN' AND s.trgt_id = encode(sha256(convert_to(crdn_addr, 'UTF8')), 'hex'))";

    /**
     * keyset 페이징 기준 키. ⚠️ 한정자를 붙이지 않는다 — 내부 {@code PagingRowMapper} 가 이 문자열로
     * {@code ResultSet#getObject(String)} 를 불러 다음 페이지 시작값을 뽑는다.
     */
    public static final String SORT_KEY = "crdn_addr";

    private static final String READER_NAME = "crackdownAddressReader";

    private CrackdownAddressReader() {
    }

    /**
     * Reader 를 조립한다. 🔴 반환 객체의 {@code afterPropertiesSet()} 은 부르지 않았다 — 호출자가
     * {@code @Bean} 으로 반환해 컨테이너가 부르게 한다(클래스 주석).
     *
     * @param dataSource   DataSource
     * @param whereClause  WHERE 절. 운영은 {@link #WHERE_CLAUSE} 그대로, 테스트는 조건을 덧붙인다
     * @param pageSize     페이지 크기 — 청크 크기와 <b>같은 설정 키</b>({@code batch.crackdown.geocode.chunk-size})
     * @param maxAddresses 1회 실행 상한. {@code 0} 이하 = 무제한({@code setMaxItemCount} 미설정)
     */
    public static JdbcPagingItemReader<String> create(
            DataSource dataSource, String whereClause, int pageSize, int maxAddresses) {
        PostgresPagingQueryProvider provider = new PostgresPagingQueryProvider();
        provider.setSelectClause(SELECT_CLAUSE);
        provider.setFromClause(FROM_CLAUSE);
        provider.setWhereClause(whereClause);
        provider.setSortKeys(Map.of(SORT_KEY, Order.ASCENDING));

        JdbcPagingItemReader<String> reader = new JdbcPagingItemReader<>();
        reader.setName(READER_NAME);
        reader.setDataSource(dataSource);
        reader.setQueryProvider(provider);
        reader.setRowMapper(new SingleColumnRowMapper<>(String.class));
        reader.setPageSize(pageSize);
        reader.setSaveState(false);
        if (maxAddresses > 0) {
            reader.setMaxItemCount(maxAddresses);
        }
        return reader;
    }
}
