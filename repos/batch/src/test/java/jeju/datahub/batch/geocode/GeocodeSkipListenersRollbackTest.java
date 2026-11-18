package jeju.datahub.batch.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import jeju.datahub.batch.cvlcpt.GeocodeSkipListener;
import jeju.datahub.batch.cvlcpt.GeocodeSkippableException;
import jeju.datahub.batch.pblpklt.PblpkltGeocodeSkippableException;
import jeju.datahub.batch.pblpklt.PblpkltSkipListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 두 스킵 리스너({@link GeocodeSkipListener} · {@link PblpkltSkipListener}) — 실 DB, TSK-4054 Step 5.
 *
 * <h2>무엇을 확인하나</h2>
 * 확인 대상은 "<b>리스너 → 프록시된 {@link GeocodeSkipRecorder}</b> 호출이 바깥 트랜잭션 롤백에서
 * 살아남는가" 다. Recorder 단독 생존은 {@link GeocodeSkipRecorderTest} 가 따로 진다.
 *
 * <h2>⚠️ Job 통합 테스트는 {@code REQUIRES_NEW} 를 증명하지 못한다</h2>
 * spring-batch-core 5.2.1 에서 Processor 스킵은 ① 청크 롤백 → ② 새 트랜잭션에서 재처리·스킵 판정 →
 * ③ {@code write()} 성공 뒤 {@code FaultTolerantChunkProcessor#callSkipListeners} 가
 * {@code onSkipInProcess} 호출 → ④ 같은 트랜잭션에서 {@code stream.update}·
 * {@code JobRepository.update(stepExecution)}({@code TaskletStep}) → 커밋 순서다. 리스너는
 * <b>롤백이 끝난 뒤, 커밋될 트랜잭션 안에서</b> 불리므로 {@code REQUIRES_NEW} 가 없어도 기본 경로의
 * 기록은 남는다 — 그래서 {@code ComplaintFetchJobTest} 로는 이 성질을 가릴 수 없다.
 *
 * <p><b>그래도 {@code REQUIRES_NEW} 를 유지한다</b> — ③~④ 사이 실패(같은 청크의 다음 스킵 기록 실패 →
 * {@code SkipListenerFailedException}, {@code JobRepository} 갱신·커밋 실패)로 청크가 롤백돼도 이미
 * 확정된 판정은 남아야 재실행이 같은 건을 다시 부르지 않는다. {@code noRollback} 이 추가되면 리스너가
 * {@code write()} <b>전에</b> 불리는 경로({@code transform} 의 {@code callProcessSkipListener})도 열린다.
 *
 * <p>테스트 키는 {@code trgt_id LIKE 'T4054RB-%'} 이며 정리도 이 조건으로만 한다 — 🔴 {@code src_se}
 * 만으로 지우면 실 스킵 이력(CVLCPT 3 · PBLPKLT 102)이 함께 사라진다. 실 스킵 행이 그대로인지는
 * {@link #baselineUnchanged()} 가 클래스 시작 시점 스냅샷과 대조해 확인한다.
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeocodeSkipListenersRollbackTest {

    private static final String CVLCPT = "CVLCPT";
    private static final String PBLPKLT = "PBLPKLT";
    private static final String CVLCPT_ID = "T4054RB-CVLCPT";
    private static final String PBLPKLT_ID = "T4054RB-PBLPKLT";
    private static final String CVLCPT_CONTROL_ID = "T4054RB-CONTROL-CVLCPT";
    private static final String PBLPKLT_CONTROL_ID = "T4054RB-CONTROL-PBLPKLT";

    /** 실 스킵 행 스냅샷 — 하드코딩하지 않고 클래스 시작 시점 값을 잡아 마지막에 대조한다. */
    private static final String SNAPSHOT_SQL = """
            SELECT src_se, count(*) AS row_cnt, count(trgt_addr) AS addr_cnt
              FROM batch.tb_geocode_skip
             WHERE trgt_id NOT LIKE 'T4054RB-%'
             GROUP BY 1 ORDER BY 1
            """;

    @Autowired
    private GeocodeSkipListener geocodeSkipListener;

    @Autowired
    private PblpkltSkipListener pblpkltSkipListener;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private List<Map<String, Object>> baseline;

    @BeforeAll
    void captureBaseline() {
        deleteFixtures();
        baseline = jdbc.queryForList(SNAPSHOT_SQL);
    }

    @AfterEach
    void cleanUp() {
        deleteFixtures();
        geocodeSkipListener.resetStats();
        pblpkltSkipListener.resetStats();
    }

    @AfterAll
    void baselineUnchanged() {
        assertThat(jdbc.queryForList(SNAPSHOT_SQL))
                .as("실 스킵 행(T4054RB- 가 아닌 것)은 이 테스트가 건드리지 않는다")
                .isEqualTo(baseline);
    }

    @Test
    @DisplayName("🔴 민원 — 바깥 트랜잭션이 롤백돼도 리스너가 남긴 스킵 행은 살아남는다")
    void cvlcptSkipSurvivesOuterRollback() {
        String addr = "제주특별자치도 제주시 테스트민원로 4054";
        String note = "geocode=NOT_FOUND";

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 롤백이 실제로 일어났다는 증거가 될 대조 행 — 같은 트랜잭션에서 직접 넣는다.
            jdbc.update("INSERT INTO batch.tb_geocode_skip (src_se, trgt_id, fail_rsn) VALUES (?, ?, 'GEOCODE_FAILED')",
                    CVLCPT, CVLCPT_CONTROL_ID);
            geocodeSkipListener.onSkipInProcess(null, new GeocodeSkippableException(
                    GeocodeSkippableException.Reason.GEOCODE_FAILED, CVLCPT_ID, addr, note));
            status.setRollbackOnly();
        });

        assertThat(count(CVLCPT, CVLCPT_CONTROL_ID)).as("바깥 트랜잭션 대조 행 — 롤백됐어야 한다").isZero();
        assertThat(count(CVLCPT, CVLCPT_ID)).as("REQUIRES_NEW 로 커밋된 행").isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT fail_rsn, note, trgt_addr, checked_dt FROM batch.tb_geocode_skip WHERE src_se = ? AND trgt_id = ?",
                CVLCPT, CVLCPT_ID);
        assertThat(row.get("fail_rsn")).isEqualTo("GEOCODE_FAILED");
        assertThat(row.get("note")).isEqualTo(note);
        assertThat(row.get("trgt_addr")).as("민원은 실패 주소를 기록한다").isEqualTo(addr);
        assertThat(row.get("checked_dt")).isNotNull();
    }

    @Test
    @DisplayName("🔴 공영주차장 — 롤백 뒤에도 행이 남고 trgt_addr 는 NULL 이다(M2)")
    void pblpkltSkipSurvivesOuterRollbackWithoutAddress() {
        String addr = "제주특별자치도 서귀포시 테스트주차장로 4054";
        String note = "emd=NO_MATCH";

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO batch.tb_geocode_skip (src_se, trgt_id, fail_rsn) VALUES (?, ?, 'EMD_UNRESOLVED')",
                    PBLPKLT, PBLPKLT_CONTROL_ID);
            pblpkltSkipListener.onSkipInProcess(null, new PblpkltGeocodeSkippableException(
                    PblpkltGeocodeSkippableException.Reason.EMD_UNRESOLVED, PBLPKLT_ID, addr, note));
            status.setRollbackOnly();
        });

        assertThat(count(PBLPKLT, PBLPKLT_CONTROL_ID)).as("바깥 트랜잭션 대조 행 — 롤백됐어야 한다").isZero();
        assertThat(count(PBLPKLT, PBLPKLT_ID)).as("REQUIRES_NEW 로 커밋된 행").isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT fail_rsn, note, trgt_addr, checked_dt FROM batch.tb_geocode_skip WHERE src_se = ? AND trgt_id = ?",
                PBLPKLT, PBLPKLT_ID);
        assertThat(row.get("fail_rsn")).isEqualTo("EMD_UNRESOLVED");
        assertThat(row.get("note")).isEqualTo(note);
        assertThat(row.get("trgt_addr"))
                .as("M2 — 주소를 알고 있어도 공영주차장은 기록하지 않는다(현재 동작 유지)")
                .isNull();
        assertThat(row.get("checked_dt")).isNotNull();
    }

    private void deleteFixtures() {
        jdbc.update("DELETE FROM batch.tb_geocode_skip WHERE trgt_id LIKE 'T4054RB-%'");
    }

    private int count(String srcSe, String trgtId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM batch.tb_geocode_skip WHERE src_se = ? AND trgt_id = ?",
                Integer.class, srcSe, trgtId);
        return n == null ? 0 : n;
    }
}
