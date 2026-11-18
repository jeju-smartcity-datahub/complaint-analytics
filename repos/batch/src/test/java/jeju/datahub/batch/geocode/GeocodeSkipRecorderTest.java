package jeju.datahub.batch.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link GeocodeSkipRecorder} — 실 DB, TSK-4054 Step 6 (D13).
 *
 * <h2>🔴 바깥 트랜잭션이 롤백돼도 스킵 행이 남는가({@code REQUIRES_NEW})</h2>
 * {@link TransactionTemplate} 안에서 {@code record(...)} 를 부르고 {@code setRollbackOnly()} 한다. 같은
 * 트랜잭션에서 {@code JdbcTemplate} 으로 직접 넣은 대조 행은 사라지고(롤백이 실제로 일어났다는 증거),
 * Recorder 가 쓴 행만 남아야 한다.
 *
 * <p>테스트 키는 {@code src_se='CRDN'} · {@code trgt_id LIKE 'T4054REC-%'} 이며 정리도 이 두 조건으로만
 * 한다 — {@code src_se} 만으로 지우면 실 스킵 이력이 사라진다.
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다</b> — 트랜잭션 경계와 "끝난 뒤" 단언을 테스트 코드가 쥔다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 테스트 컨텍스트에 02:00 크론이 등록될 필요는 없다.
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class GeocodeSkipRecorderTest {

    private static final String SRC_SE = "CRDN";
    private static final String RECORDED_ID = "T4054REC-recorded";
    private static final String CONTROL_ID = "T4054REC-control";
    private static final String UPSERT_ID = "T4054REC-upsert";

    @Autowired
    private GeocodeSkipRecorder recorder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void deleteFixtures() {
        jdbc.update("DELETE FROM batch.tb_geocode_skip WHERE src_se = ? AND trgt_id LIKE 'T4054REC-%'", SRC_SE);
    }

    @Test
    @DisplayName("🔴 바깥 트랜잭션이 롤백돼도 Recorder 가 쓴 스킵 행은 남는다")
    void survivesOuterRollback() {
        // 프록시가 아니면 @Transactional 자체가 무시된다 — 전제부터 확인한다.
        assertThat(AopUtils.isAopProxy(recorder)).isTrue();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO batch.tb_geocode_skip (src_se, trgt_id, fail_rsn) VALUES (?, ?, 'GEOCODE_FAILED')",
                    SRC_SE, CONTROL_ID);
            recorder.record(SRC_SE, RECORDED_ID, "GEOCODE_FAILED", "kind=PLACE", "테스트단속주소-REC");
            status.setRollbackOnly();
        });

        assertThat(count(CONTROL_ID)).as("바깥 트랜잭션 대조 행 — 롤백됐어야 한다").isZero();
        assertThat(count(RECORDED_ID)).as("REQUIRES_NEW 로 커밋된 행").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT fail_rsn, note, trgt_addr, checked_dt FROM batch.tb_geocode_skip WHERE src_se = ? AND trgt_id = ?",
                SRC_SE, RECORDED_ID);
        assertThat(row.get("fail_rsn")).isEqualTo("GEOCODE_FAILED");
        assertThat(row.get("note")).isEqualTo("kind=PLACE");
        assertThat(row.get("trgt_addr")).isEqualTo("테스트단속주소-REC");
        assertThat(row.get("checked_dt")).isNotNull();
    }

    @Test
    @DisplayName("같은 키 두 번 → 1행, 마지막 판정으로 갱신")
    void upsertsSameKey() {
        recorder.record(SRC_SE, UPSERT_ID, "GEOCODE_FAILED", "first", "테스트단속주소-A");
        recorder.record(SRC_SE, UPSERT_ID, "EMD_UNRESOLVED", "second", "테스트단속주소-B");

        assertThat(count(UPSERT_ID)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT fail_rsn, note, trgt_addr FROM batch.tb_geocode_skip WHERE src_se = ? AND trgt_id = ?",
                SRC_SE, UPSERT_ID);
        assertThat(row.get("fail_rsn")).isEqualTo("EMD_UNRESOLVED");
        assertThat(row.get("note")).isEqualTo("second");
        assertThat(row.get("trgt_addr")).isEqualTo("테스트단속주소-B");
    }

    private int count(String trgtId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM batch.tb_geocode_skip WHERE src_se = ? AND trgt_id = ?",
                Integer.class, SRC_SE, trgtId);
        return n == null ? 0 : n;
    }
}
