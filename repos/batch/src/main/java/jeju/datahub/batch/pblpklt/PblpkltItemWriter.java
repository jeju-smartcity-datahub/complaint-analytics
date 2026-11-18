package jeju.datahub.batch.pblpklt;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 주소가 같은 기존 주차장 전부를 갱신하고, 같은 주소가 없으면 새 주차장을 만든다.
 * 주소는 원문 정확 일치이며 UNIQUE 제약을 두지 않는다. 내부 pklt_id 와 FK 는 유지한다.
 * origin_pk 는 마지막으로 관측한 원천 식별자이며 주차장 식별에 사용하지 않는다.
 *
 * <p>Spring JDBC 의 데이터 변경 CTE 로 UPDATE 후 INSERT 를 같은 문장에서 처리한다.
 * 같은 청크에 같은 주소가 반복되면 입력 순서대로 갱신한다. 청크 트랜잭션과 잡의
 * 단일 인스턴스 실행 정책을 따른다. 주소 특이 패턴도 같은 트랜잭션에 기록한다.
 */
@Component
public class PblpkltItemWriter implements ItemWriter<PblpkltRecord> {

    private static final List<String> COLUMNS = List.of(
            "pklt_nm", "emd_cd", "lat", "lot", "pklt_cpcty", "pklt_type_cd",
            "origin_pk", "pklt_addr", "orgnl_id", "orgnl_sn", "emd_nm", "pklt_type_nm",
            "pkltar", "prk_crg_yn", "prk_crg_cn", "prk_crg_swtc_ym", "instl_ym", "instl_ymd",
            "rttrt", "day_avg_vhcl_cnt", "incm_amt", "expnd_amt", "mng_nope", "mng_dept_nm",
            "pklt_mthd_nm", "ev_chrgr_cnt", "ownshp_type_nm", "prk_slot_grd", "obstcl_cn", "obstcl_slot_cnt",
            "trfc_sfty_sign_instl_yn", "stpr_instl_yn", "rmrk_cn");
    private static final Set<String> INTEGER_COLUMNS = Set.of(
            "pklt_cpcty", "orgnl_sn", "day_avg_vhcl_cnt", "mng_nope", "ev_chrgr_cnt", "obstcl_slot_cnt");
    private static final Set<String> NUMERIC_COLUMNS = Set.of(
            "lat", "lot", "pkltar", "rttrt", "incm_amt", "expnd_amt");

    // VALUES 의 NULL 도 올바른 타입을 갖게 한다. NULL 정수/금액을 text 로 추론하면 UPDATE 가 실패한다.
    private static final String UPSERT_PBLPKLT_SQL = """
            WITH incoming (%s) AS (VALUES (%s)),
            updated AS (
                UPDATE parking.tb_pblpklt p
                   SET %s, extsh_dt = NULL
                  FROM incoming i
                 WHERE p.pklt_addr = i.pklt_addr
                RETURNING p.pklt_id
            )
            INSERT INTO parking.tb_pblpklt (%s)
            SELECT %s FROM incoming i
             WHERE NOT EXISTS (SELECT 1 FROM updated)
            """.formatted(
                    String.join(", ", COLUMNS),
                    COLUMNS.stream().map(c -> "?::" + (INTEGER_COLUMNS.contains(c) ? "integer"
                            : NUMERIC_COLUMNS.contains(c) ? "numeric" : "text")).collect(Collectors.joining(", ")),
                    COLUMNS.stream().map(c -> c + " = i." + c).collect(Collectors.joining(", ")),
                    String.join(", ", COLUMNS),
                    COLUMNS.stream().map(c -> "i." + c).collect(Collectors.joining(", ")));

    /**
     * 주소 특이 패턴 곁가지 기록 (2026-08-24 합류).
     *
     * <p>수집 Writer 가 하던 일이다. 1단화로 이 Writer 가 이어받아 서비스 테이블 upsert 와
     * <b>같은 청크 트랜잭션</b>에서 남긴다 — 값 보존과 곁가지 기록의 정합성이 트랜잭션으로
     * 보장된다.
     *
     * <p>🔴 <b>{@code pklt_id} 컬럼에 넣는 값은 {@code origin_pk}(원천 키)다</b>(TSK-4162 C′).
     * {@code pklt_id} 를 DB 가 매기면서 Writer 가 INSERT 시점에 그 값을 알 수 없게 됐다.
     * 컬럼 이름만 옛것이 남았고, 마이그레이션 70 이 그 사실을 컬럼 주석에 박으며 기존 342행도
     * {@code origin_pk} 로 이관했다.
     *
     * <p>{@code ON CONFLICT DO NOTHING} 은 chunk 경계를 넘는 재실행까지 막는 최종 방어선이다.
     * 한 주소가 두 패턴에 동시에 해당하면 {@code anomaly_type} 이 달라 두 행으로 남는 게 정상이고,
     * 같은 타입의 반복 INSERT 만 차단된다.
     */
    private static final String INSERT_ANOMALY_SQL = """
            INSERT INTO batch.tb_pblpklt_addr_anomaly (pklt_id, orgnl_addr, anomaly_type)
            VALUES (?, ?, ?)
            ON CONFLICT (pklt_id, orgnl_addr, anomaly_type) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public PblpkltItemWriter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void write(Chunk<? extends PblpkltRecord> chunk) {
        List<? extends PblpkltRecord> items = chunk.getItems();
        if (items.isEmpty()) {
            return;
        }

        // 주소 없는 행을 한 그룹으로 합치거나 재실행마다 새로 만들지 않는다.
        // 실패하면 청크가 롤백되고 후행 소멸 마킹도 실행되지 않는다.
        if (items.stream().anyMatch(r -> r.pkltAddr() == null || r.pkltAddr().isBlank())) {
            throw new IllegalArgumentException("주차장 주소(pklt_addr)가 없어 주소 기준으로 저장할 수 없습니다.");
        }

        // 각 입력을 순서대로 처리해 같은 청크 안의 중복 주소도 새 행 없이 갱신한다.
        jdbc.batchUpdate(UPSERT_PBLPKLT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int row) throws java.sql.SQLException {
                PblpkltRecord r = items.get(row);
                // 🔴 인덱스를 손으로 세지 않는다 — 아래 순서는 UPSERT_PBLPKLT_SQL 의 컬럼 목록과
                //    한 줄씩 대응한다. 컬럼을 넣고 빼도 뒤 번호를 다시 셀 일이 없다.
                //    NULL 가능한 INTEGER 는 setInt 가 아니라 setObject 다(setInt 는 null 을 못 넣는다).
                int i = 0;
                ps.setString(++i, r.pkltNm());                  // 1  pklt_nm
                ps.setString(++i, r.emdCd());                   // 2  emd_cd
                ps.setBigDecimal(++i, r.lat());                 // 3  lat
                ps.setBigDecimal(++i, r.lot());                 // 4  lot
                ps.setObject(++i, r.pkltCpcty());               // 5  pklt_cpcty
                ps.setString(++i, r.pkltTypeCd());              // 6  pklt_type_cd
                ps.setString(++i, r.originPk());                // 7  origin_pk (마지막 관측 원천 키)
                ps.setString(++i, r.pkltAddr());                // 8  pklt_addr
                ps.setString(++i, r.orgnlId());                 // 9  orgnl_id
                ps.setObject(++i, r.orgnlSn());                 // 10 orgnl_sn
                ps.setString(++i, r.emdNm());                   // 11 emd_nm
                ps.setString(++i, r.pkltTypeNm());              // 12 pklt_type_nm
                ps.setBigDecimal(++i, r.pkltar());              // 13 pkltar
                ps.setString(++i, r.prkCrgYn());                // 14 prk_crg_yn
                ps.setString(++i, r.prkCrgCn());                // 15 prk_crg_cn
                ps.setString(++i, r.prkCrgSwtcYm());            // 16 prk_crg_swtc_ym
                ps.setString(++i, r.instlYm());                 // 17 instl_ym
                ps.setString(++i, r.instlYmd());                // 18 instl_ymd
                ps.setBigDecimal(++i, r.rttrt());               // 19 rttrt
                ps.setObject(++i, r.dayAvgVhclCnt());           // 20 day_avg_vhcl_cnt
                ps.setBigDecimal(++i, r.incmAmt());             // 21 incm_amt
                ps.setBigDecimal(++i, r.expndAmt());            // 22 expnd_amt
                ps.setObject(++i, r.mngNope());                 // 23 mng_nope
                ps.setString(++i, r.mngDeptNm());               // 24 mng_dept_nm
                ps.setString(++i, r.pkltMthdNm());              // 25 pklt_mthd_nm
                ps.setObject(++i, r.evChrgrCnt());              // 26 ev_chrgr_cnt
                ps.setString(++i, r.ownshpTypeNm());            // 27 ownshp_type_nm
                ps.setString(++i, r.prkSlotGrd());              // 28 prk_slot_grd
                ps.setString(++i, r.obstclCn());                // 29 obstcl_cn
                ps.setObject(++i, r.obstclSlotCnt());           // 30 obstcl_slot_cnt
                ps.setString(++i, r.trfcSftySignInstlYn());     // 31 trfc_sfty_sign_instl_yn
                ps.setString(++i, r.stprInstlYn());             // 32 stpr_instl_yn
                ps.setString(++i, r.rmrkCn());                  // 33 rmrk_cn
            }

            @Override
            public int getBatchSize() {
                return items.size();
            }
        });

        // 곁가지 — 주소 특이 패턴 관측. 정상 행은 빈 리스트라 아무것도 남지 않는다.
        //
        // 🔴 2026-09-16 — 저장값은 반드시 **판정에 쓴 값**이어야 한다.
        //   종전엔 Classifier 가 pklt_nm 으로 판정하고 여기서 pklt_addr 을 적재해 둘이 어긋나 있었고,
        //   옛 7필드 데이터셋엔 pklt_addr 컬럼이 없어 전건 null → NOT NULL 위반으로 Job 이 죽었다.
        //   이제 양쪽이 PblpkltAddressAnomalyClassifier.anomalyInput() 한 식을 공유한다.
        //   (입력이 null 인 행은 Classifier 가 애초에 anomaly 를 만들지 않지만, NOT NULL 컬럼이라
        //    Writer 에서도 한 번 더 걸러 둔다 — 여기서 새면 청크 전체가 롤백된다.)
        List<Object[]> anomalies = items.stream()
                .flatMap(r -> {
                    String addr = PblpkltAddressAnomalyClassifier.anomalyInput(r.pkltAddr(), r.pkltNm());
                    return addr == null
                            ? java.util.stream.Stream.<Object[]>empty()
                            : r.anomalyTypes().stream()
                                    .map(type -> new Object[] { r.originPk(), addr, type });
                })
                .toList();
        if (!anomalies.isEmpty()) {
            jdbc.batchUpdate(INSERT_ANOMALY_SQL, anomalies);
        }
    }
}