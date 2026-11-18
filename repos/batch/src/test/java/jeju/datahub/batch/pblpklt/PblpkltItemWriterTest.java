package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link PblpkltItemWriter} 단위 테스트 — 33컬럼 upsert 의 <b>SQL 모양과 파라미터 순서</b>.
 *
 * <p>🔁 2026-09-18(TSK-4162 · C′) — {@code pklt_id} 가 DB IDENTITY 로 바뀌며 INSERT 목록에서
 * 빠졌다(34 → 33). 현재는 주소 일치 행을 갱신하며 origin_pk 도 마지막 관측값으로 갱신한다.
 *
 * <h2>🔴 실 DB 를 쓰지 않는다 — 그래도 이 테스트가 잡는 사고가 가장 조용하다</h2>
 * 컬럼 목록과 {@code setValues} 의 setter 순서가 한 칸이라도 어긋나면 <b>타입이 같은 구간에서는
 * 예외 없이 엉뚱한 컬럼에 값이 들어간다</b>(String 이 연달아 오는 구간이 특히 위험하다).
 * 그래서 {@link JdbcTemplate} 을 mock 으로 갈아 끼워 실행된 SQL 문자열을 잡고,
 * {@link BatchPreparedStatementSetter} 를 mock {@link PreparedStatement} 에 직접 태워
 * <b>33번의 setter 호출 순서</b>를 전수로 확인한다. 실 DB 연결·행 적재는 하지 않는다.
 */
class PblpkltItemWriterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private PblpkltItemWriter writer;

    @BeforeEach
    void setUp() {
        writer = new PblpkltItemWriter(mock(DataSource.class));
        ReflectionTestUtils.setField(writer, "jdbc", jdbc);
    }

    // ── 1. SQL 모양 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 INSERT 컬럼 33개 · 물음표 33개 — 둘이 어긋나면 바인딩이 통째로 밀린다")
    void insertsThirtyThreeColumnsWithMatchingPlaceholders() {
        String sql = captureUpsertSql();

        List<String> columns = insertColumns(sql);
        assertThat(columns).hasSize(33);
        assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(33);

        // 컬럼 목록 자체도 못박는다 — 마이그레이션 63 의 25종이 빠짐없이 실렸는지가 요점이다.
        // 🔴 pklt_id 는 없다 — DB 가 IDENTITY 로 매긴다(마이그레이션 70).
        assertThat(columns).containsExactly(
                "pklt_nm", "emd_cd", "lat", "lot", "pklt_cpcty", "pklt_type_cd",
                "origin_pk", "pklt_addr",
                "orgnl_id", "orgnl_sn", "emd_nm", "pklt_type_nm", "pkltar", "prk_crg_yn",
                "prk_crg_cn", "prk_crg_swtc_ym", "instl_ym", "instl_ymd", "rttrt",
                "day_avg_vhcl_cnt", "incm_amt", "expnd_amt", "mng_nope", "mng_dept_nm",
                "pklt_mthd_nm", "ev_chrgr_cnt", "ownshp_type_nm", "prk_slot_grd",
                "obstcl_cn", "obstcl_slot_cnt", "trfc_sfty_sign_instl_yn", "stpr_instl_yn",
                "rmrk_cn");
        assertThat(sql).contains("INSERT INTO parking.tb_pblpklt");
        assertThat(sql).contains("WHERE p.pklt_addr = i.pklt_addr", "WHERE NOT EXISTS (SELECT 1 FROM updated)");
        assertThat(sql).doesNotContain("ON CONFLICT");
    }

    @Test
    @DisplayName("🔴 주소 기준 갱신에서 내부 PK 는 유지하고 관측 원천 ID 는 갱신한다")
    void keepsInternalPkAndUpdatesObservedOriginPk() {
        Map<String, String> updates = doUpdateAssignments(captureUpsertSql());

        assertThat(updates).containsEntry("origin_pk", "i.origin_pk");
        // pklt_id 는 아예 INSERT 목록에 없다(DB IDENTITY) — 갱신 대상일 수도 없다.
        assertThat(updates).doesNotContainKey("pklt_id");
    }

    @Test
    @DisplayName("🔴 UPDATE 에 extsh_dt = NULL 이 있다 — 다시 나타난 주차장의 소멸 표시를 거둔다")
    void restoresExtinctionTimestampToNull() {
        Map<String, String> updates = doUpdateAssignments(captureUpsertSql());

        assertThat(updates).containsEntry("extsh_dt", "NULL");
        // 원천 33컬럼과 extsh_dt 를 함께 갱신한다.
        assertThat(updates).hasSize(34);
        // 나머지는 수신 행의 같은 컬럼을 참조한다.
        updates.forEach((column, value) -> {
            if (!column.equals("extsh_dt")) {
                assertThat(value).as(column).isEqualTo("i." + column);
            }
        });
    }

    // ── 2. 파라미터 순서 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 setter 33개가 컬럼 목록과 같은 순서로 값을 넣는다 — 같은 타입 연속 구간이 위험하다")
    void bindsEveryValueInColumnOrder() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        captureSetter().setValues(ps, 0);

        InOrder order = inOrder(ps);
        order.verify(ps).setString(1, "대정상모 공영노외1 주차장"); // pklt_nm
        order.verify(ps).setString(2, "50130250");               // emd_cd
        order.verify(ps).setBigDecimal(3, new BigDecimal("33.2185123456"));   // lat
        order.verify(ps).setBigDecimal(4, new BigDecimal("126.2521123456"));  // lot
        order.verify(ps).setObject(5, 37);                       // pklt_cpcty
        order.verify(ps).setString(6, "OFSTR");                  // pklt_type_cd
        order.verify(ps).setString(7, "1916");                   // origin_pk (마지막 관측 원천 키)
        order.verify(ps).setString(8, "상모리 3964-1");           // pklt_addr
        // ↓ String 이 연달아 오는 구간 — 한 칸 밀려도 예외가 나지 않는 곳이라 전수로 본다.
        order.verify(ps).setString(9, "ORG-1916");               // orgnl_id
        order.verify(ps).setObject(10, 131);                     // orgnl_sn
        order.verify(ps).setString(11, "대정읍");                 // emd_nm
        order.verify(ps).setString(12, "노외공영");               // pklt_type_nm
        order.verify(ps).setBigDecimal(13, new BigDecimal("1073.000000000")); // pkltar
        order.verify(ps).setString(14, "Y");                     // prk_crg_yn
        order.verify(ps).setString(15, "30분 500원");             // prk_crg_cn
        order.verify(ps).setString(16, "202602");                // prk_crg_swtc_ym
        order.verify(ps).setString(17, "202510");                // instl_ym
        order.verify(ps).setString(18, "20251015");              // instl_ymd
        order.verify(ps).setBigDecimal(19, new BigDecimal("1.46"));           // rttrt
        order.verify(ps).setObject(20, 54);                      // day_avg_vhcl_cnt
        order.verify(ps).setBigDecimal(21, new BigDecimal("2118400"));        // incm_amt
        order.verify(ps).setBigDecimal(22, new BigDecimal("31000"));          // expnd_amt
        order.verify(ps).setObject(23, 2);                       // mng_nope
        order.verify(ps).setString(24, "교통행정과");              // mng_dept_nm
        order.verify(ps).setString(25, "직각주차");                // pklt_mthd_nm
        order.verify(ps).setObject(26, 4);                       // ev_chrgr_cnt
        order.verify(ps).setString(27, "공유");                   // ownshp_type_nm
        order.verify(ps).setString(28, "A");                     // prk_slot_grd
        order.verify(ps).setString(29, "경계석");                 // obstcl_cn
        order.verify(ps).setObject(30, 1);                       // obstcl_slot_cnt
        order.verify(ps).setString(31, "Y");                     // trfc_sfty_sign_instl_yn
        order.verify(ps).setString(32, "N");                     // stpr_instl_yn
        order.verify(ps).setString(33, "재조사 대상");             // rmrk_cn
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("NULL 가능한 정수는 setInt 가 아니라 setObject 다 — setInt 는 null 을 못 넣는다")
    void bindsNullableIntegersWithSetObject() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        PblpkltRecord sparse = new PblpkltRecord(
                "12", "(이름 없음)", "50110250",
                new BigDecimal("33.4"), new BigDecimal("126.2"),
                null, "ETC", "테스트 주소", List.of(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);

        captureSetter(sparse).setValues(ps, 0);

        verify(ps).setObject(5, null);     // pklt_cpcty
        verify(ps).setObject(10, null);    // orgnl_sn
        verify(ps).setObject(20, null);    // day_avg_vhcl_cnt
        verify(ps).setObject(23, null);    // mng_nope
        verify(ps).setObject(26, null);    // ev_chrgr_cnt
        verify(ps).setObject(30, null);    // obstcl_slot_cnt
        verify(ps).setString(33, null);    // rmrk_cn
    }

    @Test
    @DisplayName("배치 크기는 청크 크기와 같다")
    void batchSizeFollowsChunkSize() {
        writer.write(new Chunk<>(List.of(record(), record(), record())));

        assertThat(captureSetterFrom(1).getBatchSize()).isEqualTo(3);
    }

    // ── 3. 곁가지 — 주소 특이 패턴 ───────────────────────────────────────────

    @Test
    @DisplayName("anomaly 가 없으면 곁가지 INSERT 를 아예 하지 않는다")
    void skipsAnomalyInsertWhenNothingObserved() {
        writer.write(new Chunk<>(List.of(record())));

        verify(jdbc, never()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("🔴 anomaly 의 키는 origin_pk 다 — Writer 는 DB 가 매긴 pklt_id 를 알 수 없다")
    void writesOneAnomalyRowPerObservedPattern() {
        writer.write(new Chunk<>(List.of(recordWithAnomalies("SAN_PREFIX", "NO_LOT_NO"))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sqlCaptor.capture(), captor.capture());

        assertThat(sqlCaptor.getValue())
                .contains("batch.tb_pblpklt_addr_anomaly")
                .contains("ON CONFLICT (pklt_id, orgnl_addr, anomaly_type) DO NOTHING");
        assertThat(captor.getValue()).hasSize(2);
        // 컬럼 이름은 pklt_id 지만 값은 원천 키다(TSK-4162 C′ · 마이그레이션 70 이 기존 342행도 이관).
        assertThat(captor.getValue().get(0))
                .containsExactly("1916", "상모리 3964-1", "SAN_PREFIX");
        assertThat(captor.getValue().get(1))
                .containsExactly("1916", "상모리 3964-1", "NO_LOT_NO");
    }

    @Test
    @DisplayName("주소가 없으면 이름/원천 ID 로 임의 매칭하지 않고 저장을 실패시킨다")
    void rejectsMissingAddressBeforeWritingAnything() {
        for (String address : new String[] { null, "", "   " }) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.write(new Chunk<>(List.of(
                    withAddressAndName(record(), address, "이름")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pklt_addr");
        }
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("빈 청크는 아무 쿼리도 실행하지 않는다")
    void doesNothingForEmptyChunk() {
        writer.write(new Chunk<PblpkltRecord>(List.of()));

        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private String captureUpsertSql() {
        writer.write(new Chunk<>(List.of(record())));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sqlCaptor.capture(), org.mockito.ArgumentMatchers
                .any(BatchPreparedStatementSetter.class));
        return sqlCaptor.getValue();
    }

    private BatchPreparedStatementSetter captureSetter() {
        return captureSetter(record());
    }

    private BatchPreparedStatementSetter captureSetter(PblpkltRecord record) {
        writer.write(new Chunk<>(List.of(record)));
        return captureSetterFrom(1);
    }

    /** {@code write()} 를 {@code times}번 호출한 뒤 마지막 setter 를 꺼낸다. */
    private BatchPreparedStatementSetter captureSetterFrom(int times) {
        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc, org.mockito.Mockito.times(times))
                .batchUpdate(anyString(), captor.capture());
        return captor.getValue();
    }

    /** INSERT 컬럼 목록 — 첫 괄호쌍 안을 쉼표로 자른다. */
    private static List<String> insertColumns(String sql) {
        int open = sql.indexOf('(');
        int close = sql.indexOf(')', open);
        return Arrays.stream(sql.substring(open + 1, close).split(","))
                .map(String::strip)
                .toList();
    }

    /** {@code DO UPDATE SET} 절의 "컬럼 → 대입값". 주석 줄은 건너뛴다. */
    private static Map<String, String> doUpdateAssignments(String sql) {
        Map<String, String> assignments = new LinkedHashMap<>();
        String section = sql.substring(sql.indexOf("SET ") + 4, sql.indexOf("FROM incoming"));
        for (String assignment : section.split(",")) {
            String[] parts = assignment.strip().split("=", 2);
            assignments.put(parts[0].strip(), parts[1].strip());
        }
        return assignments;
    }

    private static PblpkltRecord record() {
        return record(List.of());
    }

    private static PblpkltRecord recordWithAnomalies(String... types) {
        return record(Arrays.asList(types));
    }

    /** 옛 7필드 데이터셋 모양 — {@code pklt_addr} 이 없다. 판정·저장 모두 {@code pklt_nm} 으로 폴백해야 한다. */
    private static PblpkltRecord recordWithoutAddress(String... types) {
        return withAddressAndName(record(Arrays.asList(types)), null, "대정상모 공영노외1 주차장");
    }

    /** 주소도 이름도 없는 극단 케이스 — {@code orgnl_addr} 이 NOT NULL 이라 곁가지를 남기면 안 된다. */
    private static PblpkltRecord recordWithoutAddressOrName(String... types) {
        return withAddressAndName(record(Arrays.asList(types)), null, null);
    }

    /**
     * 34컴포넌트를 다시 나열하지 않고 두 칸만 바꾼다 — 필드가 늘어도 이 헬퍼는 따라온다
     * (record 는 불변이라 새 인스턴스를 만든다).
     */
    private static PblpkltRecord withAddressAndName(PblpkltRecord r, String pkltAddr, String pkltNm) {
        return new PblpkltRecord(
                r.originPk(), pkltNm, r.emdCd(), r.lat(), r.lot(),
                r.pkltCpcty(), r.pkltTypeCd(), pkltAddr, r.anomalyTypes(),
                r.orgnlId(), r.orgnlSn(), r.emdNm(), r.pkltTypeNm(), r.pkltar(),
                r.prkCrgYn(), r.prkCrgCn(), r.prkCrgSwtcYm(), r.instlYm(), r.instlYmd(),
                r.rttrt(), r.dayAvgVhclCnt(), r.incmAmt(), r.expndAmt(),
                r.mngNope(), r.mngDeptNm(), r.pkltMthdNm(), r.evChrgrCnt(),
                r.ownshpTypeNm(), r.prkSlotGrd(), r.obstclCn(), r.obstclSlotCnt(),
                r.trfcSftySignInstlYn(), r.stprInstlYn(), r.rmrkCn());
    }

    /** 33컬럼이 서로 구분되도록 값을 전부 다르게 채운 레코드 — 한 칸 밀리면 즉시 드러난다. */
    private static PblpkltRecord record(List<String> anomalyTypes) {
        return new PblpkltRecord(
                "1916", "대정상모 공영노외1 주차장", "50130250",
                new BigDecimal("33.2185123456"), new BigDecimal("126.2521123456"),
                37, "OFSTR", "상모리 3964-1", anomalyTypes,
                "ORG-1916", 131, "대정읍", "노외공영", new BigDecimal("1073.000000000"),
                "Y", "30분 500원", "202602", "202510", "20251015",
                new BigDecimal("1.46"), 54, new BigDecimal("2118400"), new BigDecimal("31000"),
                2, "교통행정과", "직각주차", 4, "공유", "A",
                "경계석", 1, "Y", "N", "재조사 대상");
    }
}
