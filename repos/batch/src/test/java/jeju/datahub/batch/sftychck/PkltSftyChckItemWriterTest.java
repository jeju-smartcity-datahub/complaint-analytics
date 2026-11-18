package jeju.datahub.batch.sftychck;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link PkltSftyChckItemWriter} 통합 검증 — 자연키 upsert 와 시드 인수(D3·D6).
 *
 * <h2>🔴 왜 실 DB 인가 (목이 아니다)</h2>
 * 검증 대상(충돌 키 · {@code CASE WHEN t.rgtr_id = 'admin'} · "SET 안의 {@code t.rgtr_id} 는 갱신 전 값")이
 * 전부 SQL 안에 있어 목으로는 아무것도 확인되지 않는다. 하네스는 선례
 * {@code RiskAlertReceiverMappingTaskletTest}({@code @SpringBootTest} + {@code local} 프로파일 + {@link JdbcTemplate})를 따른다.
 *
 * <h2>🔴 픽스처와 정리</h2>
 * <ul>
 *   <li>주차장은 테스트 전용 {@code origin_pk} 접두({@value #ORIGIN_PK_PREFIX})로 만든다 — {@code pklt_id} 는 IDENTITY.</li>
 *   <li>정리는 <b>그 접두의 주차장 {@code pklt_id} 범위로만</b> 자식(안전점검) → 부모(주차장) 순이다
 *       ({@code fk_tb_pklt_sfty_chck_pklt_id} 에 CASCADE 가 없다). 🔴 표 전체 DELETE·{@code rgtr_id} 단독
 *       조건 DELETE 를 쓰지 않는다 — 시드 4,514행({@code admin})이 함께 날아간다.</li>
 *   <li>{@code @Transactional} 을 쓰지 않는다 — Writer 가 자기 커넥션으로 커밋한다.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class PkltSftyChckItemWriterTest {

    /** 🔴 테스트 전용 주차장 식별 접두. 정리의 스코프가 이것 하나다. */
    private static final String ORIGIN_PK_PREFIX = "TEST-SFTY-";

    private static final String FIXTURE_SCOPE =
            "SELECT pklt_id FROM parking.tb_pblpklt WHERE origin_pk LIKE '" + ORIGIN_PK_PREFIX + "%'";

    private static final String YMD = "20260301";

    @Autowired
    private PkltSftyChckItemWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    private long pkltId;

    @BeforeEach
    void setUp() {
        cleanup(); // 앞선 실행이 비정상 종료해 남긴 픽스처를 먼저 걷는다.
        // emd_cd 는 FK(tb_admdst) — 실재 코드를 DB 에서 고른다(리터럴을 박지 않는다).
        String emdCd = jdbc.queryForObject("SELECT min(emd_cd) FROM parking.tb_admdst", String.class);
        pkltId = jdbc.queryForObject("""
                INSERT INTO parking.tb_pblpklt (pklt_nm, emd_cd, lat, lot, origin_pk)
                VALUES ('테스트 안전점검 주차장', ?, 33.5, 126.5, ?)
                RETURNING pklt_id
                """, Long.class, emdCd, ORIGIN_PK_PREFIX + "1");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    /** 🔴 자식 → 부모. 둘 다 픽스처 주차장 범위로만 좁힌다. */
    private void cleanup() {
        jdbc.update("DELETE FROM parking.tb_pklt_sfty_chck WHERE pklt_id IN (" + FIXTURE_SCOPE + ")");
        jdbc.update("DELETE FROM parking.tb_pblpklt WHERE origin_pk LIKE ?", ORIGIN_PK_PREFIX + "%");
    }

    private PkltSftyChckRow row(String rslt, String rmrk) {
        return new PkltSftyChckRow(pkltId, YMD, "H1", "REGULAR", rslt, rmrk);
    }

    private void write(PkltSftyChckRow... rows) {
        writer.write(new Chunk<>(List.of(rows)));
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("""
                SELECT chck_ymd, chck_prd_cd, sfty_chck_type_cd, sfty_chck_rslt_cd, rmrk_cn, rgtr_id, reg_dt
                  FROM parking.tb_pklt_sfty_chck WHERE pklt_id = ? ORDER BY sfty_chck_id
                """, pkltId);
    }

    @Test
    @DisplayName("① 신규 행은 rgtr_id=BATCH · reg_dt=now() 로 들어가고 chck_ymd 는 CHAR(8) 그대로다")
    void insertsNewRowAsBatch() {
        LocalDateTime before = jdbc.queryForObject("SELECT now()::timestamp", LocalDateTime.class);

        write(row("A", "비고"));

        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(1);
        Map<String, Object> r = rows.get(0);
        assertThat(r.get("chck_ymd")).isEqualTo(YMD);
        assertThat(r.get("chck_prd_cd")).isEqualTo("H1");
        assertThat(r.get("sfty_chck_type_cd")).isEqualTo("REGULAR");
        assertThat(r.get("sfty_chck_rslt_cd")).isEqualTo("A");
        assertThat(r.get("rmrk_cn")).isEqualTo("비고");
        assertThat(r.get("rgtr_id")).isEqualTo(PkltSftyChckItemWriter.BATCH_RGTR_ID);
        assertThat(((Timestamp) r.get("reg_dt")).toLocalDateTime()).isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    @DisplayName("동일 점검일도 새 행으로 추가하고 이전 관측을 유지한다")
    void rerunAppendsAndKeepsPreviousObservation() {
        write(row("A", "첫 적재"));
        write(new PkltSftyChckRow(pkltId, YMD, "H2", "REGULAR", "C", null));
        assertThat(rows()).hasSize(2);
        assertThat(rows().get(0)).containsEntry("sfty_chck_rslt_cd", "A").containsEntry("rmrk_cn", "첫 적재");
        assertThat(rows().get(1)).containsEntry("sfty_chck_rslt_cd", "C").containsEntry("chck_prd_cd", "H2");
    }

    @Test
    @DisplayName("기존 시드도 삭제하거나 인수하지 않고 새 관측을 추가한다")
    void keepsSeedAndAppendsRealObservation() {
        jdbc.update("""
            INSERT INTO parking.tb_pklt_sfty_chck
                (pklt_id,chck_ymd,chck_prd_cd,sfty_chck_type_cd,sfty_chck_rslt_cd,rmrk_cn,rgtr_id,reg_dt)
            VALUES (?,?,'H1','REGULAR','B','시드','admin',TIMESTAMP '2020-01-01 00:00:00')
            """,pkltId,YMD);
        write(row("A","실자료"));
        assertThat(rows()).hasSize(2);
        assertThat(rows().get(0)).containsEntry("rgtr_id","admin").containsEntry("rmrk_cn","시드");
        assertThat(rows().get(1)).containsEntry("rgtr_id","BATCH").containsEntry("rmrk_cn","실자료");
    }

}
