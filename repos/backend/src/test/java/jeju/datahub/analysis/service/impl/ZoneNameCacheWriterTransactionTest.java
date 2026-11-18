package jeju.datahub.analysis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jeju.datahub.analysis.service.impl.VworldCallSupport.LookupStatus;
import jeju.datahub.analysis.service.impl.VworldPlaceClient.NearestPlace;
import jeju.datahub.analysis.service.impl.ZoneNameResolver.ZoneName;

/**
 * 🔴 <b>회귀 방지선</b> — 구역명 캐시 쓰기가 읽기전용 트랜잭션을 오염시키지 않는지 확인한다
 * (후속 수정).
 *
 * <h2>막으려는 사고</h2>
 * 집중구역 분석 서비스는 조회 전용이라 {@code @Transactional(readOnly = true)} 로 표명한다.
 * 그 경계 안에서 캐시 upsert 를 그대로 실행하면 이렇게 번졌다(실측).
 * <ol>
 *   <li>{@code PSQLException: cannot execute INSERT in a read-only transaction} — SQLState {@code 25006}</li>
 *   <li>그 실패가 PostgreSQL 트랜잭션을 <b>abort</b> 시켜 이후 모든 문장이 {@code 25P02:
 *       current transaction is aborted} 로 죽는다</li>
 *   <li>{@link ZoneNameResolver} 가 쓰기 예외를 삼켜도 소용없다 — <b>같은 트랜잭션의 다음 조회</b>가
 *       터져 API 가 500 을 낸다</li>
 * </ol>
 * {@link ZoneNameCacheWriter} 를 별도 빈 + {@code REQUIRES_NEW} 로 분리해 막았고, 이 테스트는
 * "읽기전용 경계 안에서 구역명을 채운 뒤 <b>후속 쿼리가 정상 동작</b>한다"를 단언한다.
 *
 * <p>외부 호출은 {@link MockitoBean} 으로 끊는다 — VWorld 응답이 아니라 <b>트랜잭션 전파</b>가
 * 검증 대상이고, 키 없는 CI 에서도 같은 경로가 돌아야 한다.
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다. 마이그레이션 54
 * ({@code database/migrations/sql/54-TB_ZONE_NM_CACHE.sql}) 적용을 전제로 한다.
 */
@SpringBootTest
class ZoneNameCacheWriterTransactionTest {

    /** 실데이터와 겹치지 않는 시험용 좌표(제주 경계 밖). 매 테스트 후 지운다. */
    private static final BigDecimal LAT = new BigDecimal("33.99999");
    private static final BigDecimal LOT = new BigDecimal("126.99999");
    private static final String EMD = "테스트동";

    @Autowired
    private ZoneNameResolver resolver;

    @Autowired
    private ZoneNameCacheWriter cacheWriter;

    @Autowired
    private ZoneNameCacheMapper cacheMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private VworldPlaceClient placeClient;

    @MockitoBean
    private VworldReverseGeocodeClient reverseGeocodeClient;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM parking.tb_zone_nm_cache WHERE lat_key = ? AND lot_key = ?", LAT, LOT);
    }

    private TransactionTemplate readOnlyTx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    @Test
    @DisplayName("읽기전용 트랜잭션 안에서 구역명을 채워도 후속 쿼리가 살아 있다 (25P02 회귀 방지)")
    void resolvingInsideReadOnlyTransactionKeepsParentUsable() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.FOUND, "테스트공원", 42));

        readOnlyTx().executeWithoutResult(status -> {
            ZoneName name = resolver.newSession().resolve(LAT, LOT, EMD);
            assertThat(name.zoneNm()).isEqualTo("테스트동 테스트공원");

            // 🔴 여기가 핵심이다 — 캐시 쓰기가 부모를 abort 시켰다면 이 조회가 25P02 로 죽는다.
            assertThatCode(() -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM parking.tb_cvlcpt", Integer.class)).doesNotThrowAnyException();
            assertThatCode(() -> cacheMapper.selectZoneName(LAT, LOT)).doesNotThrowAnyException();
        });

        // REQUIRES_NEW 라 부모가 읽기전용이어도 저장은 실제로 커밋된다.
        ZoneNameCacheVO saved = cacheMapper.selectZoneName(LAT, LOT);
        assertThat(saved).isNotNull();
        assertThat(saved.getZoneNm()).isEqualTo("테스트동 테스트공원");
        assertThat(saved.getZoneNmSrc()).isEqualTo(ZoneNameResolver.SRC_POI);
        assertThat(saved.getPoiDistM()).isEqualTo(42);
    }

    @Test
    @DisplayName("확정 실패(NOT_FOUND) 기록도 읽기전용 경계를 오염시키지 않는다")
    void cachingNotFoundInsideReadOnlyTransactionIsSafe() {
        given(placeClient.findNearestLandmark(any(), any(), anyString()))
                .willReturn(new NearestPlace(LookupStatus.NOT_FOUND, null, 0));
        given(reverseGeocodeClient.findAddress(any(), any()))
                .willReturn(new VworldReverseGeocodeClient.ReverseAddress(LookupStatus.NOT_FOUND, null));

        readOnlyTx().executeWithoutResult(status -> {
            ZoneName name = resolver.newSession().resolve(LAT, LOT, EMD);
            assertThat(name.zoneNm()).isEqualTo("테스트동 일대");

            assertThatCode(() -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM parking.tb_crdn_dsctn", Integer.class)).doesNotThrowAnyException();
        });

        assertThat(cacheMapper.selectZoneName(LAT, LOT).getZoneNmSrc())
                .isEqualTo(ZoneNameResolver.SRC_NOT_FOUND);
    }

    @Test
    @DisplayName("쓰기 전용 빈을 직접 불러도 마찬가지다 — REQUIRES_NEW 가 실제로 걸려 있는지 확인")
    void writerCommitsInItsOwnTransaction() {
        readOnlyTx().executeWithoutResult(status -> {
            cacheWriter.save(LAT, LOT, "테스트동 직접저장", ZoneNameResolver.SRC_REVERSE_GEOCODE, null);

            assertThatCode(() -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM parking.tb_zone_nm_cache", Integer.class)).doesNotThrowAnyException();
        });

        assertThat(cacheMapper.selectZoneName(LAT, LOT).getZoneNm()).isEqualTo("테스트동 직접저장");
    }
}
