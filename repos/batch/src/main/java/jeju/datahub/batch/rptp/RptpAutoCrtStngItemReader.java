package jeju.datahub.batch.rptp;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * ④{@code reportAutoCrtJob} 의 Reader — <b>살아 있는 자동생성 설정 전량</b>을 읽는다
 * (TSK-4027 Step 7-1).
 *
 * <pre>{@code
 * SELECT stng_id, rptp_type_cd, crltn_cycl_cd, crltn_mm, crltn_dd,
 *        sgg_nm, emd_cd, crltn_dept_cd
 *   FROM parking.tb_rptp_auto_crt_stng
 *  WHERE actvtn_yn = TRUE
 *    AND del_dt IS NULL
 *  ORDER BY stng_id
 * }</pre>
 *
 * <h2>🔴 {@code del_dt IS NULL} 을 빠뜨리지 않는다</h2>
 * 설정 삭제는 <b>소프트 삭제</b>(TSK-4026 D11)라 행이 그대로 남는다. 이 조건이 없으면
 * <b>사용자가 지운 설정이 계속 보고서를 찍어낸다</b> — 화면에서는 사라졌는데 매달 새 보고서가
 * 쌓이는, 원인을 찾기 어려운 사고다.
 *
 * <h2>🔴 날짜 조건을 SQL 에 두지 않는다 — 도래 판정은 Processor 가 한다</h2>
 * "오늘이 배포일인가" 는 {@link ReportDueDateResolver} 한 곳에서만 판정한다(Step 7-2).
 * SQL 에도 같은 규칙을 심으면 두 벌이 되어 한쪽만 고쳐지고, 단위 테스트로 덮을 수도 없다.
 * 설정 수가 수십 건 규모라 전량을 읽고 걸러도 비용이 없다.
 *
 * <h2>🔴 {@code NOT EXISTS} 로 "이미 만든 것" 을 빼지 않는다</h2>
 * 멱등성은 백엔드의 유니크 키({@code uq_rptp_stng_prd})와 "이미 있으면 그 행의 ID 를 돌려준다"
 * 가 낸다(계획서 D5). 여기서 미리 빼면 같은 날 재실행이 <b>조용히 아무것도 하지 않는</b> 것과
 * <b>정상 멱등</b>을 구분할 수 없어진다.
 *
 * <h2>⚠️ 페이지 크기 = 청크 크기 (같은 키 하나)</h2>
 * {@code batch.rptp.chunk-size} 를 Reader 와 Step({@code ReportAutoCrtJobConfig}, Step 9)이
 * <b>함께</b> 읽어 항상 일치시킨다 — 한쪽만 바꾸지 말 것.
 *
 * <p>⚠️ {@code setSaveState(false)} — 재개는 저장된 페이지 위치가 아니라 백엔드의 멱등
 * 처리가 담당한다({@code RiskAlertTargetItemReader} 와 같은 이유).
 */
@Component
@StepScope
public class RptpAutoCrtStngItemReader extends JdbcPagingItemReader<RptpStngRow> {

    static final String SELECT_CLAUSE =
            "stng_id, rptp_type_cd, crltn_cycl_cd, crltn_mm, crltn_dd,"
                    + " sgg_nm, emd_cd, crltn_dept_cd";

    static final String FROM_CLAUSE = "parking.tb_rptp_auto_crt_stng";

    /** 🔴 소프트 삭제된 설정을 제외한다 — 없으면 지운 설정이 계속 보고서를 찍어낸다. */
    static final String WHERE_CLAUSE = "actvtn_yn = TRUE AND del_dt IS NULL";

    /**
     * keyset 페이징 기준 키.
     *
     * <p>⚠️ 별칭을 붙이지 않는다 — {@code JdbcPagingItemReader} 의 내부 {@code PagingRowMapper}
     * 가 이 문자열을 그대로 {@code ResultSet#getObject(String)} 에 넘긴다(결과셋 컬럼 라벨과
     * 달라지면 2페이지째에 죽는다). FROM 이 테이블 하나뿐이라 모호하지도 않다.
     */
    static final String SORT_KEY = "stng_id";

    /**
     * {@code crltn_mm}·{@code crltn_dd} 는 {@code SMALLINT} <b>nullable</b> 이다 —
     * {@code rs.getInt()} 는 NULL 을 0 으로 읽으므로 {@code getObject(..., Integer.class)} 로
     * 받는다. 0 으로 읽히면 {@code MONTH} 설정이 "1일" 대신 "0일" 이 되어 영원히 도래하지 않는다.
     */
    private static final RowMapper<RptpStngRow> ROW_MAPPER = (rs, rowNum) -> new RptpStngRow(
            rs.getLong("stng_id"),
            rs.getString("rptp_type_cd"),
            rs.getString("crltn_cycl_cd"),
            rs.getObject("crltn_mm", Integer.class),
            rs.getObject("crltn_dd", Integer.class),
            rs.getString("sgg_nm"),
            rs.getString("emd_cd"),
            rs.getString("crltn_dept_cd"));

    /**
     * @param dataSource 로컬/dev/prod 공용 DataSource
     * @param pageSize   페이지 크기. <b>청크 크기와 같은 프로퍼티</b>({@code batch.rptp.chunk-size})
     */
    public RptpAutoCrtStngItemReader(
            DataSource dataSource,
            @Value("${batch.rptp.chunk-size:50}") int pageSize) {

        setName("rptpAutoCrtStngItemReader");
        setDataSource(dataSource);
        setQueryProvider(queryProvider());
        setRowMapper(ROW_MAPPER);
        setPageSize(pageSize);
        setSaveState(false);
    }

    /** 테스트에서 생성 SQL(WHERE 절·keyset 여부)을 직접 확인할 수 있도록 package-private 로 둔다. */
    static PostgresPagingQueryProvider queryProvider() {
        PostgresPagingQueryProvider provider = new PostgresPagingQueryProvider();
        provider.setSelectClause(SELECT_CLAUSE);
        provider.setFromClause(FROM_CLAUSE);
        provider.setWhereClause(WHERE_CLAUSE);
        Map<String, Order> sortKeys = new LinkedHashMap<>();
        sortKeys.put(SORT_KEY, Order.ASCENDING);
        provider.setSortKeys(sortKeys);
        return provider;
    }
}
