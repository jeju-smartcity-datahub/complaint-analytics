package jeju.datahub.batch.risknoti;

import java.time.LocalDate;
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
 * ③{@code riskAlertJudgeJob} 의 Reader — <b>판정일 하루치를 읍면동으로 묶어 (당일, 전일) 건수를
 * 한 행으로</b> 낸다.
 *
 * <pre>{@code
 * SELECT t.emd_cd, t.emd_nm, t.target_cnt, t.prev_cnt
 *   FROM ( ...당일 집계 d JOIN tb_admdst a LEFT JOIN 전일 집계 p... ) t
 *  ORDER BY emd_cd
 * }</pre>
 *
 * <h2>🔴 FROM 은 파생 테이블 <b>하나</b>다 — 조인을 그대로 두면 2페이지에서 죽는다</h2>
 * {@link JdbcPagingItemReader} 는 2페이지부터 WHERE 에 {@code AND ((emd_cd > ?))} 를
 * <b>한정자 없이</b> 덧붙인다. 조인을 FROM 절에 그대로 펴 두면 {@code d}·{@code a}·{@code p} 가
 * 모두 {@code emd_cd} 를 가져 {@code column reference "emd_cd" is ambiguous} 로 죽고
 * (2026-08-21 psql 실증), 그렇다고 sortKey 를 {@code d.emd_cd} 로 바꾸면 이번엔 내부
 * {@code PagingRowMapper} 가 {@code rs.getObject("d.emd_cd")} 를 호출해
 * {@code column name d.emd_cd was not found} 로 죽는다.
 * → <b>조인 전체를 파생 테이블로 감싸 단일 관계로 만든다.</b> 그러면 {@code t} 하나뿐이라
 * 한정자 없는 {@code emd_cd} 가 모호하지 않고, 결과셋 컬럼 라벨도 {@code emd_cd} 라 둘 다 산다.
 *
 * <h2>🔴 {@code NOT EXISTS} 를 두지 않는다 — 재판정이 정상 경로다(D6)</h2>
 * 지오코딩 Reader 가 이미 처리한 행을 {@code NOT EXISTS} 로 영구 제외한 이유는 <b>외부 API
 * 재호출이 비싸서</b>였다. 여기는 순수 SQL 이라 다시 계산하는 비용이 없고, 오히려
 * <b>늦게 들어온 민원·지오코딩 보정분을 반영해야</b> 한다({@code targetDate} 를 파라미터로 받는
 * 이유가 그것이다). 멱등성은 {@code UNIQUE (emd_cd, ocrn_dt)} + Writer 의
 * {@code ON CONFLICT DO UPDATE} 가 낸다. ⚠️ 여기에 {@code NOT EXISTS} 를 넣으면 Writer 의
 * upsert 경로에 영원히 도달하지 못해 열람 상태 보존(D7)도 검증할 수 없게 된다.
 *
 * <h2>⚠️ OFFSET 페이징 금지 · {@code setSaveState(false)}</h2>
 * {@link PostgresPagingQueryProvider} + <b>단일 sortKey</b> 조합이라야 Spring Batch 가
 * keyset 페이징({@code WHERE emd_cd > ?})으로 다음 페이지를 만든다. 재개는 저장된 페이지
 * 위치가 아니라 Writer 의 upsert 가 담당하므로 상태를 저장하지 않는다.
 *
 * <h2>⚠️ 반개구간으로 쓴다 — {@code ocrn_dt::date = :targetDate} 금지</h2>
 * 좌변에 함수를 씌우면 인덱스 {@code ix_tb_cvlcpt_emd_ocrn (emd_cd, ocrn_dt)} 를 못 탄다.
 * 당일은 {@code [D, D+1)}, 전일은 {@code [D-1, D)} 다(EXPLAIN 상 Index Only Scan 확인).
 */
@Component
@StepScope
public class RiskAlertTargetItemReader extends JdbcPagingItemReader<RiskAlertTargetRow> {

    /** SQL 안의 네 자리({@code >= D} · {@code < D+1} · {@code >= D-1} · {@code < D})가 모두 이 키 하나를 참조한다. */
    static final String TARGET_DATE_PARAM = "targetDate";

    static final String SELECT_CLAUSE = "t.emd_cd, t.emd_nm, t.target_cnt, t.prev_cnt";

    /**
     * 🔴 <b>파생 테이블 하나</b>. 위 클래스 주석의 ambiguous 문제를 막는 핵심이다.
     *
     * <p>{@code prev_cnt} 는 <b>{@code LEFT JOIN} + {@code COALESCE(...,0)}</b> 다 —
     * 전일 민원이 0건인 읍면동을 {@code INNER JOIN} 으로 떨어뜨리면 D2 가 잡으려던
     * <b>신규 급증(0 → N)</b> 이 통째로 사라진다.
     *
     * <p>🔴 <b>{@code CAST(:targetDate AS date)} 의 {@code CAST} 를 벗기지 않는다.</b>
     * pgjdbc 는 {@code setDate} 값을 {@code Oid.UNSPECIFIED} 로 보내(암시적 캐스팅 허용)
     * 서버가 파라미터 타입을 <b>주변 연산자로 추론</b>한다. 맨 {@code :targetDate} 로 두면
     * {@code ? + INTERVAL '1 day'} 가 {@code interval + interval} 로 풀려
     * {@code operator does not exist: timestamp without time zone < interval} 로 죽는다
     * (2026-08-26 실측 — 맨 파라미터로는 1페이지에서 실패했다).
     * {@code ::date} 대신 {@code CAST} 를 쓰는 이유는 {@code AbstractSqlPagingQueryProvider.init}
     * 이 파라미터를 셀 때 {@code ::} 를 특수 처리하기 때문이다 — 굳이 그 경로를 건드리지 않는다.
     */
    static final String FROM_CLAUSE = """
            (SELECT d.emd_cd                AS emd_cd,
                    a.emd_nm                AS emd_nm,
                    d.target_cnt            AS target_cnt,
                    COALESCE(p.prev_cnt, 0) AS prev_cnt
               FROM (SELECT emd_cd, count(*) AS target_cnt
                       FROM parking.tb_cvlcpt
                      WHERE emd_cd IS NOT NULL
                        AND ocrn_dt >= CAST(:targetDate AS date)
                        AND ocrn_dt <  CAST(:targetDate AS date) + INTERVAL '1 day'
                      GROUP BY emd_cd) d
               JOIN parking.tb_admdst a ON a.emd_cd = d.emd_cd
               LEFT JOIN (SELECT emd_cd, count(*) AS prev_cnt
                            FROM parking.tb_cvlcpt
                           WHERE emd_cd IS NOT NULL
                             AND ocrn_dt >= CAST(:targetDate AS date) - INTERVAL '1 day'
                             AND ocrn_dt <  CAST(:targetDate AS date)
                           GROUP BY emd_cd) p ON p.emd_cd = d.emd_cd
            ) t""";

    /**
     * keyset 페이징 기준 키.
     *
     * <p>⚠️ <b>별칭({@code t.emd_cd})을 붙이지 않는다.</b> {@code JdbcPagingItemReader} 의 내부
     * {@code PagingRowMapper} 가 이 문자열을 그대로 {@code ResultSet#getObject(String)} 에 넘겨
     * 다음 페이지 시작값을 뽑기 때문에, 한정자를 붙이면 결과셋 컬럼 라벨({@code emd_cd})과 달라
     * 2페이지째에 "column name t.emd_cd was not found" 로 죽는다. FROM 이 파생 테이블 하나뿐이라
     * 별칭 없이도 {@code ORDER BY}/{@code WHERE emd_cd > ?} 는 모호하지 않다.
     */
    static final String SORT_KEY = "emd_cd";

    private static final RowMapper<RiskAlertTargetRow> ROW_MAPPER = (rs, rowNum) -> new RiskAlertTargetRow(
            rs.getString("emd_cd"),
            rs.getString("emd_nm"),
            rs.getLong("target_cnt"),
            rs.getLong("prev_cnt"));

    /**
     * @param dataSource     로컬/dev/prod 공용 DataSource
     * @param pageSize       페이지 크기. <b>청크 크기와 같은 프로퍼티</b>를 읽어 항상 맞춘다
     *                       ({@code batch.risknoti.chunk-size})
     * @param targetDateParam 선택 Job 파라미터 {@code targetDate}({@code yyyy-MM-dd}).
     *                       없으면 {@link RiskAlertProperties} 가 "Asia/Seoul 기준 어제"를 고른다(D1·D11).
     *                       ⚠️ <b>{@code String} 으로 받는다</b> — 트리거·스케줄러가
     *                       {@code addString("targetDate", ...)} 로 넣기 때문이다
     */
    public RiskAlertTargetItemReader(
            DataSource dataSource,
            @Value("${batch.risknoti.chunk-size:200}") int pageSize,
            @Value("#{jobParameters['targetDate']}") String targetDateParam) {

        LocalDate targetDate = RiskAlertProperties.resolveTargetDate(parseTargetDate(targetDateParam));

        setName("riskAlertTargetItemReader");
        setDataSource(dataSource);
        setQueryProvider(queryProvider());
        setRowMapper(ROW_MAPPER);
        setPageSize(pageSize);
        // 🔴 없으면 1페이지에서 "No value supplied for the SQL parameter 'targetDate'" 로 즉사한다.
        // ⚠️ java.sql.Date 로 바인딩해야 ":targetDate + INTERVAL '1 day'" 가 날짜 연산으로 계산된다
        //    (문자열로 넣으면 Postgres 가 타입을 정하지 못한다).
        setParameterValues(Map.of(TARGET_DATE_PARAM, java.sql.Date.valueOf(targetDate)));
        // 재개는 저장된 페이지 위치가 아니라 Writer 의 upsert(D6)가 담당한다.
        setSaveState(false);
    }

    /** 빈 문자열도 "파라미터 없음"으로 본다 — 트리거가 빈 값을 넘겨도 "어제" 로 떨어지게 한다. */
    static LocalDate parseTargetDate(String targetDateParam) {
        return targetDateParam == null || targetDateParam.isBlank()
                ? null
                : LocalDate.parse(targetDateParam.trim());
    }

    /** 테스트에서 생성 SQL(keyset 여부·파생 테이블)을 직접 확인할 수 있도록 package-private 로 둔다. */
    static PostgresPagingQueryProvider queryProvider() {
        PostgresPagingQueryProvider provider = new PostgresPagingQueryProvider();
        provider.setSelectClause(SELECT_CLAUSE);
        provider.setFromClause(FROM_CLAUSE);
        // WHERE 절은 없다 — 🔴 NOT EXISTS 를 두지 않는다(위 주석 · D6).
        Map<String, Order> sortKeys = new LinkedHashMap<>();
        sortKeys.put(SORT_KEY, Order.ASCENDING);
        provider.setSortKeys(sortKeys);
        return provider;
    }
}
