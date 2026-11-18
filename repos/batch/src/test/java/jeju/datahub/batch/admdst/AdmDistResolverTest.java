package jeju.datahub.batch.admdst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link AdmDistResolver} 2단 공간매칭 테스트.
 *
 * <p><b>로컬 DB(deploy/local, {@code jeju-datahub-db})가 떠 있어야 한다.</b> PostGIS 함수·GiST
 * 인덱스·실제 경계 폴리곤 74개에 붙어야 의미가 있어 인메모리 DB 로 대체하지 않는다. 조회 전용이라
 * {@code @Transactional} 없이도 데이터를 건드리지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AdmDistResolverTest {

    /** 제주시청. 폴리곤 내부라 1차({@code ST_Contains})에서 끝나야 한다. */
    private static final BigDecimal CITY_HALL_LAT = new BigDecimal("33.4996");
    private static final BigDecimal CITY_HALL_LOT = new BigDecimal("126.5312");
    private static final String CITY_HALL_EMD_CD = "50110104"; // 이도이동

    /** 건입동 — 2차 매칭 케이스의 기준 행정동. */
    private static final String GEONIPDONG_EMD_CD = "50110107";

    /**
     * 스킵 CSV({@code batch/output/cvlcpt-geocode-skipped.csv})의 {@code 건입동 1451-5} 를
     * VWorld 로 실제 지오코딩한 좌표(2026-08-04 실호출). 제주항 매립지라 행정동 경계 밖에 찍힌다.
     */
    private static final BigDecimal PORT_LAT = new BigDecimal("33.5275699752");
    private static final BigDecimal PORT_LOT = new BigDecimal("126.5446207166");

    /** 바다 한가운데(제주 남서쪽 먼바다). 어느 경계에도 안 걸린다. */
    private static final BigDecimal SEA_LAT = new BigDecimal("33.0");
    private static final BigDecimal SEA_LOT = new BigDecimal("126.0");

    @Autowired
    private JdbcTemplate jdbc;

    private AdmDistResolver resolver;

    @BeforeEach
    void setUp() {
        // 카운터를 테스트마다 새로 시작하려고 빈을 주입받지 않고 직접 만든다.
        resolver = new AdmDistResolver(jdbc);
    }

    @Test
    @DisplayName("1차 매칭 — 폴리곤 내부 좌표(제주시청)는 ST_Contains 에서 끝난다")
    void resolvesByContains() {
        AdmDistMatch match = resolver.resolve(CITY_HALL_LAT, CITY_HALL_LOT);

        assertThat(match.matched()).isTrue();
        assertThat(match.emdCd()).isEqualTo(CITY_HALL_EMD_CD);
        assertThat(resolver.containsMatches()).isEqualTo(1);
        // 비싼 2차(geography 거리, 행당 ~700ms)를 태우지 않았다는 것이 이 테스트의 핵심이다.
        assertThat(resolver.bufferLookups()).isZero();
        assertThat(resolver.unresolved()).isZero();
    }

    @Test
    @DisplayName("🔴 좌표 인자 순서 회귀 — 위/경도를 뒤집으면 매칭되지 않는다")
    void doesNotMatchWhenLatLotSwapped() {
        // ST_MakePoint(경도, 위도) 순서가 뒤집히면 제주 좌표가 (33.5, 126.5) 로 해석돼
        // 전건 매칭 실패한다. 조용히 0건이 되는 종류의 버그라 회귀 테스트로 못박는다.
        AdmDistMatch match = resolver.resolve(CITY_HALL_LOT, CITY_HALL_LAT);

        assertThat(match.matched()).isFalse();
        assertThat(resolver.containsMatches()).isZero();
    }

    @Test
    @DisplayName("2차 매칭 — 경계 바로 밖(약 20m) 좌표는 50m 버퍼로 구제된다")
    void resolvesByBufferWhenJustOutsideBoundary() {
        // 건입동 경계 최북단 정점에서 북(바다)으로 약 20m 밀어낸 점.
        // 좌표를 상수로 박으면 경계 데이터가 갱신될 때 조용히 무의미해지므로 DB 에서 유도한다.
        BigDecimal[] point = pointJustOutside(GEONIPDONG_EMD_CD, 0.00018);

        AdmDistMatch match = resolver.resolve(point[0], point[1]);

        assertThat(match.emdCd()).isEqualTo(GEONIPDONG_EMD_CD);
        assertThat(resolver.containsMatches()).isZero();   // 1차는 실패해야 한다(폴리곤 밖)
        assertThat(resolver.bufferLookups()).isEqualTo(1); // 2차를 실제로 탔다
        assertThat(resolver.bufferMatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("2차 매칭 — 건입동 1451-5(제주항)는 경계에서 50m 를 넘어 EMD_UNRESOLVED 로 남는다")
    void leavesPortAddressUnresolvedBeyondBuffer() {
        double distance = nearestBoundaryDistanceMeters(PORT_LAT, PORT_LOT);

        // 실측 60.25m — 버퍼(50m)를 넘는다. 이 값이 50 아래로 내려가면 아래 기대도 바뀌어야 한다.
        assertThat(distance).isGreaterThan(AdmDistResolver.BUFFER_METERS);
        assertThat(resolver.resolve(PORT_LAT, PORT_LOT).matched()).isFalse();
        // Node 프로토타입이 이 주소를 EMD_UNRESOLVED 로 스킵했던 것과 같은 결론이다
        assertThat(resolver.bufferLookups()).isEqualTo(1);
        assertThat(resolver.unresolved()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔵 실패 건은 최근접 행정동·거리를 함께 돌려준다 — 스킵 note 의 원문")
    void reportsNearestBoundaryWhenUnresolved() {
        AdmDistMatch match = resolver.resolve(PORT_LAT, PORT_LOT);

        // 추가 조회 없이(2차 쿼리가 이미 뽑은 후보) 거리를 알 수 있어야 한다.
        assertThat(match.nearestEmdCd()).isEqualTo(GEONIPDONG_EMD_CD);
        assertThat(match.nearestEmdNm()).isEqualTo("건입동");
        assertThat(match.nearestDistanceMeters())
                .isCloseTo(nearestBoundaryDistanceMeters(PORT_LAT, PORT_LOT), within(0.01));
        // "버퍼만 넓히면 살아나는 건인지"를 이 한 줄로 판단한다.
        assertThat(match.describeNearest())
                .matches("최근접 50110107\\(건입동\\) \\d+\\.\\d{2}m > 버퍼 50m");
    }

    @Test
    @DisplayName("바다 한가운데 좌표는 1·2차 모두 실패한다 → 호출자가 EMD_UNRESOLVED 로 스킵")
    void leavesOpenSeaUnresolved() {
        AdmDistMatch match = resolver.resolve(SEA_LAT, SEA_LOT);

        assertThat(match.matched()).isFalse();
        assertThat(resolver.containsMatches()).isZero();
        assertThat(resolver.bufferMatches()).isZero();
        assertThat(resolver.unresolved()).isEqualTo(1);
        // 좌표가 통째로 잘못된 건은 거리가 km 단위로 나와 경계 미세조정 대상이 아님이 드러난다.
        assertThat(match.nearestDistanceMeters()).isGreaterThan(1_000);
    }

    @Test
    @DisplayName("좌표가 null 이면 DB 를 부르지 않고 빈 값 — 미분류(99) 행 방어")
    void returnsEmptyForNullCoordinate() {
        assertThat(resolver.resolve(null, CITY_HALL_LOT).matched()).isFalse();
        assertThat(resolver.resolve(CITY_HALL_LAT, null).matched()).isFalse();

        assertThat(resolver.containsMatches() + resolver.bufferLookups() + resolver.unresolved())
                .isZero();
    }

    @Test
    @DisplayName("1차/2차 실행 횟수 카운터가 누적되고 요약 로그가 남는다. resetStats() 로 0 이 된다")
    void tracksStageCounters() {
        resolver.resolve(CITY_HALL_LAT, CITY_HALL_LOT);
        resolver.resolve(CITY_HALL_LAT, CITY_HALL_LOT);
        resolver.resolve(SEA_LAT, SEA_LOT);

        assertThat(resolver.containsMatches()).isEqualTo(2);
        assertThat(resolver.bufferLookups()).isEqualTo(1);
        assertThat(resolver.unresolved()).isEqualTo(1);
        resolver.logStats(); // 예외 없이 요약이 찍힌다(리스너가 Job 종료 시 호출)

        // 싱글턴 빈이라 리셋이 없으면 2회차 Job 요약에 1회차 수치가 섞인다.
        resolver.resetStats();
        assertThat(resolver.containsMatches() + resolver.bufferLookups()
                + resolver.bufferMatches() + resolver.unresolved()).isZero();
    }

    /**
     * 지정 행정동 경계의 <b>최북단 정점</b>을 북쪽으로 {@code degrees} 만큼 밀어낸 점.
     * 제주 북쪽 해안 동(건입동)이라 바깥은 바다이므로 어느 폴리곤에도 포함되지 않는다.
     *
     * @return {@code [lat, lot]}
     */
    private BigDecimal[] pointJustOutside(String emdCd, double degrees) {
        var row = jdbc.queryForMap("""
                SELECT ST_Y(p) AS lat, ST_X(p) AS lot,
                       (SELECT count(*) FROM parking.tb_admdst a WHERE ST_Contains(a.bndry_geom, p)) AS inside_cnt
                  FROM (
                    SELECT ST_Translate(ST_PointN(r.ring, t.idx), 0, ?) AS p
                      FROM (SELECT ST_ExteriorRing(ST_GeometryN(ST_Multi(bndry_geom), 1)) AS ring
                              FROM parking.tb_admdst WHERE emd_cd = ?) r,
                           LATERAL (SELECT n FROM generate_series(1, ST_NPoints(r.ring)) n
                                     ORDER BY ST_Y(ST_PointN(r.ring, n)) DESC LIMIT 1) t(idx)
                  ) q
                """, degrees, emdCd);

        // 전제가 깨지면(이웃 폴리곤 안으로 들어감) 아래 기대가 무의미해지므로 여기서 드러낸다.
        assertThat(((Number) row.get("inside_cnt")).longValue())
                .as("유도한 점이 어느 폴리곤에도 포함되지 않아야 2차 매칭 테스트가 성립한다")
                .isZero();
        return new BigDecimal[] {
                BigDecimal.valueOf((Double) row.get("lat")),
                BigDecimal.valueOf((Double) row.get("lot"))};
    }

    /** 좌표에서 가장 가까운 행정동 경계까지의 실제 거리(m). 버퍼 기대값의 근거를 테스트가 직접 확인한다. */
    private double nearestBoundaryDistanceMeters(BigDecimal lat, BigDecimal lot) {
        return jdbc.queryForObject("""
                SELECT min(ST_Distance(bndry_geom::geography,
                                       ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography))
                  FROM parking.tb_admdst
                """, Double.class, lot.doubleValue(), lat.doubleValue());
    }
}
