package jeju.datahub.batch.crackdown;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link CrackdownCoordDictionaryTasklet} 검증 — 실 DB, TSK-4054 Step 5.
 *
 * <h2>🔴 전부 {@link TransactionTemplate} 안에서 돌리고 롤백한다</h2>
 * {@link CrackdownCoordDictionaryTasklet#propagate()} 는 <b>테이블 전체</b>가 대상이다. 커밋되면 픽스처뿐
 * 아니라 실 행 약 9.4만 건이 함께 채워지고, 롤백 미리보기({@code UPDATE 94069})와 Step 7 "전후 동일" 이
 * 깨진다. 그래서 픽스처 적재 → 전파 → 단언을 <b>한 트랜잭션</b>에서 하고 끝에 {@code setRollbackOnly()} 한다.
 * {@code JdbcTemplate} 과 {@code TransactionTemplate} 은 같은 DataSource(Boot 자동 구성 하나)를 쓰므로
 * 바깥 트랜잭션에 합류한다 — 트랜잭션이 끝난 뒤 <b>픽스처가 0행이고 실 행 좌표 수가 전과 같음</b>을
 * 단언해 그 합류를 실제로 증명한다.
 *
 * <p>실 행 9.4만 UPDATE 가 수 초 걸리므로 케이스마다 반복하지 않는다 — 픽스처를 한 번에 심고
 * 전파를 1회(+멱등 확인 1회) 돌린 뒤, 케이스별 단언은 {@link SoftAssertions} 로 나눠 본다.
 *
 * <h2>픽스처</h2>
 * <ul>
 *   <li>{@code src_hash} = {@code AAA-CRDNTEST-NNNN}({@code char(64)} 이하, UNIQUE)</li>
 *   <li>{@code crdn_addr} = {@code 테스트단속주소-X} — 단언과 정리가 이 접두로만 범위를 좁힌다</li>
 *   <li>{@code emd_cd} 는 FK 라 실재 코드 {@code 50110107}(건입동)</li>
 * </ul>
 * {@code @AfterEach} 의 DELETE 는 롤백이 실패했을 때의 안전망이다.
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다</b> — 트랜잭션 경계와 "끝난 뒤 불변" 단언을 테스트 코드가
 * 직접 쥐어야 해서다.
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
class CrackdownCoordDictionaryTaskletTest {

    private static final String ADDR_PREFIX = "테스트단속주소-";
    private static final String HASH_PREFIX = "AAA-CRDNTEST-";

    /** 건입동 — {@code tb_admdst} 실재 코드(FK). */
    private static final String EMD = "50110107";

    private static final BigDecimal LAT_A = new BigDecimal("33.4996000000");
    private static final BigDecimal LOT_A = new BigDecimal("126.5312000000");
    private static final BigDecimal LAT_B = new BigDecimal("33.5100000000");
    private static final BigDecimal LOT_B = new BigDecimal("126.5200000000");

    private static final String INSERT_SQL = """
            INSERT INTO parking.tb_crdn_dsctn (src_hash, crdn_addr, lat, lot, emd_cd)
            VALUES (?, ?, ?, ?, ?)""";

    private static final String REAL_COUNTS_SQL = """
            SELECT count(lat) AS lat_cnt, count(emd_cd) AS emd_cnt
              FROM parking.tb_crdn_dsctn
             WHERE crdn_addr IS NULL OR crdn_addr NOT LIKE '테스트단속주소-%'""";

    private final AtomicInteger seq = new AtomicInteger();

    @Autowired
    private CrackdownCoordDictionaryTasklet tasklet;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void deleteFixtures() {
        jdbc.update("DELETE FROM parking.tb_crdn_dsctn WHERE crdn_addr LIKE '테스트단속주소-%'");
    }

    @Test
    @DisplayName("같은 주소의 (좌표+행정동) 만 좌표 없는 행에 전파하고, 롤백 뒤 실 DB 는 불변")
    void propagatesInsideRolledBackTransaction() {
        Map<String, Object> realBefore = jdbc.queryForMap(REAL_COUNTS_SQL);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            int conflictsBefore = tasklet.countConflictingAddresses();

            // 케이스 1 — 주소 X: 좌표·emd 1행 + 좌표 없는 3행
            insert("X", LAT_A, LOT_A, EMD);
            insert("X", null, null, null);
            insert("X", null, null, null);
            insert("X", null, null, null);
            // 케이스 2 — 주소 Y: 좌표 있는 행 없음
            insert("Y", null, null, null);
            insert("Y", null, null, null);
            // 케이스 3 — 주소 Z: 좌표는 있고 emd NULL 1행 + 좌표 없는 2행 (D6)
            insert("Z", LAT_A, LOT_A, null);
            insert("Z", null, null, null);
            insert("Z", null, null, null);
            // 케이스 4 — 주소 W: 좌표 A·emd 행(사전) + 좌표 B 가 이미 있는 행(emd NULL) + 좌표 없는 1행
            insert("W", LAT_A, LOT_A, EMD);
            insert("W", LAT_B, LOT_B, null);
            insert("W", null, null, null);
            // 보조 — 주소 V: 사전 자격 좌표가 둘(A 먼저, B 나중) → crdn_id 가 작은 A 로 결정적 전파 + WARN 건수
            insert("V", LAT_A, LOT_A, EMD);
            insert("V", LAT_B, LOT_B, EMD);
            insert("V", null, null, null);

            int first = tasklet.propagate();
            // 케이스 5 — 같은 트랜잭션에서 두 번째 호출. 첫 호출이 실 행까지 채웠으므로 전체 반환이 0 이어야 한다.
            int second = tasklet.propagate();

            SoftAssertions softly = new SoftAssertions();

            // 케이스 1
            List<Map<String, Object>> x = rows("X");
            softly.assertThat(x).as("X 행 수").hasSize(4);
            x.forEach(r -> assertCoord(softly, "X", r, LAT_A, LOT_A, EMD));

            // 케이스 2
            rows("Y").forEach(r -> assertCoord(softly, "Y", r, null, null, null));

            // 케이스 3
            List<Map<String, Object>> z = rows("Z");
            assertCoord(softly, "Z 원본", z.get(0), LAT_A, LOT_A, null);
            z.subList(1, z.size()).forEach(r -> assertCoord(softly, "Z 좌표없음", r, null, null, null));

            // 케이스 4
            List<Map<String, Object>> w = rows("W");
            assertCoord(softly, "W 사전", w.get(0), LAT_A, LOT_A, EMD);
            assertCoord(softly, "W 기존 좌표 B(불변)", w.get(1), LAT_B, LOT_B, null);
            assertCoord(softly, "W 좌표없음", w.get(2), LAT_A, LOT_A, EMD);

            // 보조 V
            List<Map<String, Object>> v = rows("V");
            assertCoord(softly, "V 좌표없음 → crdn_id 최소 좌표", v.get(2), LAT_A, LOT_A, EMD);
            softly.assertThat(tasklet.countConflictingAddresses())
                    .as("충돌 주소 수 = 기준 + V 1개").isEqualTo(conflictsBefore + 1);

            // 영향 행수 — 픽스처 몫(X 3 + W 1 + V 1 = 5) 이상이고, 두 번째는 0
            softly.assertThat(first).as("첫 전파 영향 행수(픽스처 5행 포함)").isGreaterThanOrEqualTo(5);
            softly.assertThat(second).as("두 번째 전파 영향 행수").isZero();

            // execute — FINISHED 이고 write 카운트에 합산하지 않는다(D10)
            StepContribution contribution = new StepContribution(
                    new StepExecution("coordDictStep", new JobExecution(1L)));
            softly.assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.FINISHED);
            softly.assertThat(contribution.getWriteCount()).as("incrementWriteCount 미호출").isZero();

            softly.assertAll();
            status.setRollbackOnly();
        });

        // 🔴 롤백이 실제로 먹었는가 — JdbcTemplate 이 바깥 트랜잭션에 합류하지 않았다면 여기서 드러난다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking.tb_crdn_dsctn WHERE crdn_addr LIKE '테스트단속주소-%'",
                Integer.class)).as("롤백 뒤 픽스처 행").isZero();
        assertThat(jdbc.queryForMap(REAL_COUNTS_SQL)).as("롤백 뒤 실 행 좌표·행정동 수 불변")
                .isEqualTo(realBefore);
    }

    private void insert(String addrSuffix, BigDecimal lat, BigDecimal lot, String emdCd) {
        String hash = HASH_PREFIX + String.format("%04d", seq.incrementAndGet());
        jdbc.update(INSERT_SQL, hash, ADDR_PREFIX + addrSuffix, lat, lot, emdCd);
    }

    /** 삽입 순서(= crdn_id 오름차순)로 돌려준다. */
    private List<Map<String, Object>> rows(String addrSuffix) {
        return jdbc.queryForList(
                "SELECT lat, lot, emd_cd FROM parking.tb_crdn_dsctn WHERE crdn_addr = ? ORDER BY crdn_id",
                ADDR_PREFIX + addrSuffix);
    }

    private static void assertCoord(SoftAssertions softly, String label, Map<String, Object> row,
                                    BigDecimal lat, BigDecimal lot, String emdCd) {
        assertDecimal(softly, label + " lat", (BigDecimal) row.get("lat"), lat);
        assertDecimal(softly, label + " lot", (BigDecimal) row.get("lot"), lot);
        softly.assertThat((String) row.get("emd_cd")).as(label + " emd_cd").isEqualTo(emdCd);
    }

    private static void assertDecimal(SoftAssertions softly, String label, BigDecimal actual, BigDecimal expected) {
        if (expected == null) {
            softly.assertThat(actual).as(label).isNull();
        } else {
            softly.assertThat(actual).as(label).isEqualByComparingTo(expected);
        }
    }
}
