package jeju.datahub.batch.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
 * {@link GeocodeCacheRepository} — 실 DB (갈래 A).
 *
 * <h2>🔴 바깥 트랜잭션이 롤백돼도 캐시 행이 남는가({@code REQUIRES_NEW})</h2>
 * {@link TransactionTemplate} 안에서 {@code save(...)} 를 부르고 {@code setRollbackOnly()} 한다. 같은
 * 트랜잭션에서 {@link JdbcTemplate} 으로 직접 넣은 대조 행은 사라지고(롤백이 실제로 일어났다는 증거),
 * Repository 가 쓴 행만 남아야 한다.
 *
 * <p>막으려는 사고는 이것이다 — 스킵으로 청크가 롤백되면 <b>그 청크에서 처음 조회된 주소 전부</b>의
 * 캐시 행이 함께 사라졌고, {@link CompositeGeocoder} 의 인메모리 히트가 재기록 기회까지 막아
 * 같은 실행에서는 복구되지 않았다. 주차장은 매 실행 전량을 다시 지오코딩하므로 그 낭비가 반복된다.
 *
 * <p>테스트 키는 <b>{@code addr LIKE 'T4162 %'}</b> 이며 정리도 이 조건으로만 한다 — 🔴 캐시는 민원·
 * 주차장·단속이 공유하므로 조건을 넓히면 실 캐시(만여 행)가 함께 사라지고 세 잡이 VWorld 를 수천 번
 * 다시 부른다. 실 캐시가 그대로인지는 {@link #baselineUnchanged()} 가 대조한다.
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
class GeocodeCacheRepositoryTest {

    private static final String SAVED_ADDR = "T4162 CACHE 저장주소";
    private static final String CONTROL_ADDR = "T4162 CACHE 대조주소";
    private static final Coordinate JEJU_POINT =
            new Coordinate(new BigDecimal("33.5181430745"), new BigDecimal("126.5171603281"));

    @Autowired
    private GeocodeCacheRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void deleteFixtures() {
        jdbc.update("DELETE FROM batch.tb_geocode_cache WHERE addr LIKE 'T4162 %'");
    }

    @Test
    @DisplayName("🔴 바깥 트랜잭션이 롤백돼도 Repository 가 쓴 캐시 행은 남는다")
    void survivesOuterRollback() {
        // 프록시가 아니면 @Transactional 자체가 무시된다 — 전제부터 확인한다.
        assertThat(AopUtils.isAopProxy(repository)).isTrue();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO batch.tb_geocode_cache (addr, found) VALUES (?, false)",
                    CONTROL_ADDR);
            repository.save(SAVED_ADDR, GeocodeOutcome.found(JEJU_POINT));
            status.setRollbackOnly();
        });

        assertThat(count(CONTROL_ADDR)).as("바깥 트랜잭션 대조 행 — 롤백됐어야 한다").isZero();
        assertThat(count(SAVED_ADDR)).as("REQUIRES_NEW 로 커밋된 행").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT lat, lot, found, fail_rsn FROM batch.tb_geocode_cache WHERE addr = ?",
                SAVED_ADDR);
        assertThat((BigDecimal) row.get("lat")).isEqualByComparingTo(JEJU_POINT.lat());
        assertThat((BigDecimal) row.get("lot")).isEqualByComparingTo(JEJU_POINT.lot());
        assertThat(row.get("found")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("fail_rsn")).isNull();
    }

    @Test
    @DisplayName("🔴 실패 판정도 롤백을 넘어 남는다 — 그래서 무효화 절차(D3)가 필요하다")
    void survivesOuterRollbackForFailure() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.save(SAVED_ADDR, GeocodeOutcome.notFound());
            status.setRollbackOnly();
        });

        // 커밋돼 남으므로 find() 가 "실패 확정 히트" 로 읽는다 — 다시 조회되지 않는다.
        assertThat(repository.find(SAVED_ADDR))
                .get()
                .extracting(GeocodeOutcome::status)
                .isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("실 캐시는 건드리지 않는다 — 정리 조건이 T4162 접두 밖으로 넘지 않는지")
    void baselineUnchanged() {
        long before = jdbc.queryForObject(
                "SELECT count(*) FROM batch.tb_geocode_cache WHERE addr NOT LIKE 'T4162 %'",
                Long.class);

        repository.save(SAVED_ADDR, GeocodeOutcome.transientFailure());
        deleteFixtures();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM batch.tb_geocode_cache WHERE addr NOT LIKE 'T4162 %'",
                Long.class)).isEqualTo(before);
    }

    private int count(String addr) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM batch.tb_geocode_cache WHERE addr = ?", Integer.class, addr);
        return n == null ? 0 : n;
    }
}
