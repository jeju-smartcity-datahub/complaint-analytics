package jeju.datahub.batch.admdst;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 좌표 → 읍면동 코드({@code emd_cd}) <b>2단 공간매칭</b> (계획서 D6 · Step 6-1).
 *
 * <p>Node 프로토타입은 주소 문자열(시도·시군구·읍면동)을 {@code tb_admdst} 이름과 맞췄지만, 표기
 * 흔들림에 약해 좌표 기반 PostGIS 매칭으로 바꿨다.
 *
 * <h2>왜 2단인가 — 비용이 3자리수 차이난다</h2>
 * <table>
 *   <tr><th>단계</th><th>판정</th><th>비용</th><th>실측(80,138건)</th></tr>
 *   <tr><td>1차 {@code ST_Contains}</td><td>폴리곤 내부</td><td>GiST 인덱스로 수 ms</td><td><b>99.97%</b> 가 여기서 끝</td></tr>
 *   <tr><td>2차 최근접 5개 + geography 거리 50m</td><td>경계선 위~바로 밖</td><td>geography 거리라 행당 ~700ms</td><td>27건</td></tr>
 * </table>
 *
 * <p>2차를 전건에 돌리면 80,138 × 700ms ≈ 15시간이다. 1차로 걸러 낸 뒤 실패한 소수에만 태우는 것이
 * 이 클래스의 존재 이유다. 2차 안에서도 {@code <->} 연산자로 GiST 를 태워 후보 5개만 싸게 추린 뒤
 * <b>그 5개에만</b> 정확한 geography 거리를 계산한다 — 74개 폴리곤 전체에 geography 거리를 재지 않는다.
 *
 * <p>2차가 필요한 이유는 항구·방파제·해안 매립지처럼 <b>행정동 경계 폴리곤이 해안선까지 그려져 있지
 * 않은</b> 곳이다. 실측상 좌표가 경계 바깥 최대 148m 까지 찍힌다.
 *
 * <h2>🔴 좌표 인자 순서</h2>
 * {@code ST_MakePoint(경도, 위도)} 다 — {@code lot}, {@code lat} 순. 뒤집으면 제주 좌표가
 * (126.5, 33.5) 가 아니라 (33.5, 126.5) 로 해석돼 <b>전건 매칭 실패</b>한다. 조용히 0건이 되는 종류의
 * 버그라 {@code AdmDistResolverTest} 에 회귀 테스트를 둔다.
 */
@Component
public class AdmDistResolver {

    private static final Logger log = LoggerFactory.getLogger(AdmDistResolver.class);

    /**
     * 2차 매칭 버퍼(미터).
     *
     * <p><b>근거:</b> 경계 폴리곤이 해안선을 정밀하게 따라가지 않아 항구·방파제 좌표가 경계 밖에
     * 찍힌다(실측 최대 148m). 그렇다고 148m 를 그대로 쓰면 좁은 하천·도로를 사이에 둔 <b>이웃 읍면동에
     * 잘못 붙는다</b> — 제주 시내 동 간 거리가 그보다 가까운 곳이 있다. 50m 는 "경계선에 걸친 좌표"만
     * 구제하고 그 이상은 {@code EMD_UNRESOLVED} 로 남기는 절충값이다(Node 프로토타입 승계).
     * 여기를 넓히면 오매칭이 조용히 늘어난다 — 스킵 건수를 줄이려고 손대지 말 것.
     */
    static final int BUFFER_METERS = 50;

    /** 1차: 폴리곤 포함 판정. {@code bndry_geom} 의 GiST 인덱스를 그대로 탄다. */
    private static final String CONTAINS_SQL = """
            SELECT emd_cd FROM parking.tb_admdst
             WHERE ST_Contains(bndry_geom, ST_SetSRID(ST_MakePoint(?, ?), 4326)) LIMIT 1
            """;

    /**
     * 2차: 1차 실패 시에만. {@code <->}(GiST 근접 정렬)로 후보 5개를 추린 뒤 그 5개에만
     * geography 거리를 계산한다.
     *
     * <p><b>버퍼 판정을 SQL 이 아니라 Java 에서 한다</b>({@code ST_DWithin} 제거). 거리를 그대로
     * 받아 와야 실패한 건의 {@code note} 에 "최근접 60.25m" 를 남길 수 있기 때문이다(Step 7-4).
     * 종전에도 {@code ORDER BY ...::geography <-> ...} 로 같은 거리를 계산하고 있었으므로
     * 비용은 늘지 않는다 — 오히려 {@code ST_DWithin} 평가 한 번이 줄었다.
     */
    private static final String NEAREST_SQL = """
            WITH nearest AS (
              SELECT emd_cd, emd_nm, bndry_geom FROM parking.tb_admdst
               ORDER BY bndry_geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4326) LIMIT 5
            )
            SELECT emd_cd, emd_nm,
                   ST_Distance(bndry_geom::geography,
                               ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_m
              FROM nearest
             ORDER BY distance_m LIMIT 1
            """;

    /** 원천 {@code emd_cd} 검증용 — {@code tb_admdst} 전체(74행)를 한 번만 읽는다. */
    private static final String KNOWN_EMD_SQL = "SELECT emd_cd FROM parking.tb_admdst";

    private final JdbcTemplate jdbc;

    /** {@link #knownEmdCds()} 의 지연 캐시. {@code volatile} — 이중 검사 잠금의 가시성 확보용. */
    private volatile Set<String> knownEmdCds;

    private final AtomicLong containsMatches = new AtomicLong();
    private final AtomicLong bufferLookups = new AtomicLong();
    private final AtomicLong bufferMatches = new AtomicLong();
    private final AtomicLong unresolved = new AtomicLong();

    public AdmDistResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 좌표가 속한 읍면동 코드를 찾는다.
     *
     * @param lat 위도 (WGS84). 제주는 33.x
     * @param lot 경도 (WGS84). 제주는 126.x
     * @return 매칭 결과. 1·2차 모두 실패하면 {@link AdmDistMatch#matched()} 가 {@code false} 이고
     *         <b>최근접 후보와 거리가 함께 실려 온다</b> — 호출자가 그대로 {@code EMD_UNRESOLVED}
     *         스킵의 {@code note} 로 쓴다(Step 7-4)
     */
    public AdmDistMatch resolve(BigDecimal lat, BigDecimal lot) {
        if (lat == null || lot == null) {
            return AdmDistMatch.notAttempted();
        }
        // PostGIS 지오메트리는 내부적으로 double 이라 여기서 변환해도 정밀도 손실이 없다.
        // (BigDecimal 을 그대로 넘기면 numeric → float8 암시 캐스팅에 의존하게 된다.)
        double x = lot.doubleValue();
        double y = lat.doubleValue();

        String contained = jdbc.queryForList(CONTAINS_SQL, String.class, x, y)
                .stream().findFirst().orElse(null); // 5.1 — Optional.get() 대신 null 검사 뒤 사용(TSK-4336)
        if (contained != null) {
            containsMatches.incrementAndGet();
            return AdmDistMatch.matched(contained);
        }

        bufferLookups.incrementAndGet();
        NearestBoundary nearest = jdbc.query(NEAREST_SQL, NEAREST_MAPPER, x, y, x, y)
                .stream().findFirst().orElse(null);
        if (nearest != null && nearest.distanceMeters() <= BUFFER_METERS) {
            bufferMatches.incrementAndGet();
            // 경계 밖 좌표를 구제한 건이라 규모를 남긴다 — 갑자기 늘면 경계 데이터를 의심해야 한다.
            log.debug("경계 밖 좌표를 {}m 버퍼로 매칭 lat={} lot={} emd_cd={} 거리={}m",
                    BUFFER_METERS, lat, lot, nearest.emdCd(), nearest.distanceMeters());
            return AdmDistMatch.matched(nearest.emdCd());
        }

        unresolved.incrementAndGet();
        return nearest == null
                ? AdmDistMatch.unresolved(null, null, null)
                : AdmDistMatch.unresolved(
                        nearest.emdCd(), nearest.emdNm(), nearest.distanceMeters());
    }

    /**
     * 원천이 준 {@code emd_cd} 를 <b>그대로 쓸 수 있는지</b> 판정한다 (2026-09-01, TSK-4109).
     *
     * <h2>🔴 이 검증이 없으면 청크가 통째로 롤백된다</h2>
     * {@code parking.tb_cvlcpt.emd_cd} 에는 {@code tb_admdst} 를 향한 FK
     * ({@code fk_tb_cvlcpt_emd_cd})가 걸려 있다. 원천이 우리 코드 체계에 없는 값을 하나라도 보내면
     * 그 행이 아니라 <b>같은 청크의 정상 행까지</b> FK 위반으로 함께 되돌아간다. 형식 검사(8자리
     * 숫자)로는 막을 수 없어 <b>실재하는 코드인지</b>를 본다.
     *
     * <p>{@code tb_admdst} 는 74행뿐이라 <b>한 번 읽어 캐시</b>한다 — 행마다 조회하면 원천이 값을
     * 채워 보내기 시작한 순간 왕복이 건당 하나씩 늘어난다. 행정동 경계는 배치가 도는 중에 바뀌지
     * 않으므로 실행 중 갱신을 고려하지 않는다(바뀌면 앱을 다시 띄운다).
     *
     * @return 실재하는 읍면동 코드면 {@code true}. {@code null}·공백·미지 코드는 {@code false} —
     *         호출자는 좌표 공간매칭으로 되돌아간다
     */
    public boolean isKnownEmdCd(String emdCd) {
        if (emdCd == null || emdCd.isBlank()) {
            return false;
        }
        return knownEmdCds().contains(emdCd.strip());
    }

    /** 지연 초기화 — 배치가 {@code emd_cd} 를 한 번도 안 보면 이 쿼리도 돌지 않는다. */
    private Set<String> knownEmdCds() {
        Set<String> cached = knownEmdCds;
        if (cached == null) {
            synchronized (this) {
                cached = knownEmdCds;
                if (cached == null) {
                    cached = Set.copyOf(jdbc.queryForList(KNOWN_EMD_SQL, String.class));
                    knownEmdCds = cached;
                    log.info("행정동 코드 {}종을 캐시했습니다(원천 emd_cd 검증용).", cached.size());
                }
            }
        }
        return cached;
    }

    /** 2차 쿼리 1행 — 최근접 경계와 그 거리. 매칭에도 실패 진단에도 같은 값을 쓴다. */
    private record NearestBoundary(String emdCd, String emdNm, double distanceMeters) {
    }

    private static final RowMapper<NearestBoundary> NEAREST_MAPPER = (rs, rowNum) -> new NearestBoundary(
            rs.getString("emd_cd"), rs.getString("emd_nm"), rs.getDouble("distance_m"));

    /** 1차({@code ST_Contains})로 끝난 건수. */
    public long containsMatches() {
        return containsMatches.get();
    }

    /** 2차(최근접 후보 + geography 거리)를 실제로 실행한 건수 — 1차 실패 건수와 같다. 비싼 경로의 규모다. */
    public long bufferLookups() {
        return bufferLookups.get();
    }

    /** 2차에서 구제된 건수. */
    public long bufferMatches() {
        return bufferMatches.get();
    }

    /** 1·2차 모두 실패한 건수 — 호출자가 {@code EMD_UNRESOLVED} 로 스킵한 수와 같다. */
    public long unresolved() {
        return unresolved.get();
    }

    /**
     * 카운터를 0 으로 되돌린다 — <b>Job 시작 시점에 호출한다</b>(Step 7-4 요약 로그).
     *
     * <p>싱글턴 빈이라 리셋하지 않으면 같은 JVM 의 두 번째 실행에서 1·2차 건수가 누적돼 종료 요약이
     * 이번 실행의 수치가 아니게 된다(수동 트리거로 반복 실행하는 것이 기본 사용법이다).
     */
    public void resetStats() {
        containsMatches.set(0);
        bufferLookups.set(0);
        bufferMatches.set(0);
        unresolved.set(0);
    }

    /**
     * 1차/2차 실행 횟수 요약 로그(계획서 Step 6 검증: "2차 비율이 1% 미만").
     * Job 종료 시점(Step 7 리스너)에 한 번 호출한다.
     */
    public void logStats() {
        long total = containsMatches.get() + bufferLookups.get();
        double bufferRatio = total == 0 ? 0 : (double) bufferLookups.get() * 100 / total;
        log.info("행정동 공간매칭 요약 — 시도 {}건 / 1차(ST_Contains) {}건 / 2차(최근접 {}m 버퍼) 실행 {}건"
                        + "({}%) 중 매칭 {}건 / 미매칭 {}건",
                total, containsMatches.get(), BUFFER_METERS, bufferLookups.get(),
                String.format("%.2f", bufferRatio), bufferMatches.get(), unresolved.get());
    }
}
