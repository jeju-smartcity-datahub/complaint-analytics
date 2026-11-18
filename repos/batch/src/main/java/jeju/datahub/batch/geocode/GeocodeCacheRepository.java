package jeju.datahub.batch.geocode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code batch.tb_geocode_cache} — 주소 → 좌표 캐시(D8).
 *
 * <p>Node 프로토타입은 파일({@code output/geocode-cache.json})이었으나 실행 간 재사용·조회 편의를
 * 위해 DB 테이블로 승격했다. <b>실패도 캐싱한다</b>({@code found=false}) — 실측상 고유 주소는
 * 15.5% 뿐이라(80,138행 ÷ 12,419주소) 실패 주소를 기억하지 않으면 같은 미수록 주소를 평균 6.45번
 * 다시 물어보게 된다.
 *
 * <pre>{@code
 * CREATE TABLE batch.tb_geocode_cache (
 *     addr VARCHAR(200) PRIMARY KEY, lat NUMERIC(12,10), lot NUMERIC(13,10),
 *     found BOOLEAN NOT NULL, fail_rsn VARCHAR(20),
 *     updated_dt TIMESTAMP NOT NULL DEFAULT now());
 * }</pre>
 *
 * <h2>🔄 2026-08-04 — {@code fail_rsn} 으로 실패를 두 종류로 나눈다 (D8 개정)</h2>
 *
 * <table>
 *   <tr><th>행</th><th>{@link #find(String)}</th><th>이유</th></tr>
 *   <tr><td>{@code found=true}</td><td>좌표 히트</td><td>-</td></tr>
 *   <tr><td>{@code found=false, fail_rsn='NOT_FOUND'}</td><td>실패 확정 히트</td>
 *       <td>다시 물어도 없다 — API 호출을 아낀다</td></tr>
 *   <tr><td>{@code found=false, fail_rsn='TRANSIENT'}</td><td><b>히트 아님</b>(빈 값)</td>
 *       <td>일시 장애였을 뿐이라 재조회해야 한다. 행은 <b>관측용으로 남긴다</b></td></tr>
 * </table>
 *
 * <p>{@code TRANSIENT} 를 히트로 치면 장애 구간에 걸린 주소가 다음 실행에서 API 호출조차 건너뛰어
 * 영영 복구되지 않는다. 운영 복구 수단이 필요하면
 * {@code DELETE FROM batch.tb_geocode_cache WHERE NOT found AND fail_rsn = 'TRANSIENT';}
 * 로 관측 행까지 지울 수 있다(지우지 않아도 재조회는 된다).
 */
@Repository
public class GeocodeCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(GeocodeCacheRepository.class);

    /** {@code addr} 컬럼 길이. 원천 {@code dclr_ocrn_rgn} 은 VARCHAR(100) 이라 평시엔 걸리지 않는다. */
    static final int ADDR_MAX_LENGTH = 200;

    private final JdbcTemplate jdbc;

    public GeocodeCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 캐시 히트를 조회한다.
     *
     * @return <b>히트일 때만</b> 값이 있다 — {@code FOUND}(좌표) 또는 {@code NOT_FOUND}(실패 확정).
     *         캐시에 행이 없거나 {@code TRANSIENT} 행이면 {@link Optional#empty()} 이며,
     *         호출자는 <b>둘을 똑같이 "재조회 대상"으로 다루면 된다</b>
     */
    public Optional<GeocodeOutcome> find(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        List<GeocodeOutcome> rows = jdbc.query(
                "SELECT lat, lot, found, fail_rsn FROM batch.tb_geocode_cache WHERE addr = ?",
                (rs, rowNum) -> {
                    BigDecimal lat = rs.getBigDecimal("lat");
                    BigDecimal lot = rs.getBigDecimal("lot");
                    if (rs.getBoolean("found") && lat != null && lot != null) {
                        return GeocodeOutcome.found(new Coordinate(lat, lot));
                    }
                    return failureOf(rs.getString("fail_rsn"), address);
                },
                address);
        // TRANSIENT 는 행이 있어도 히트가 아니다 — 여기서 걸러야 다음 실행이 다시 물어본다.
        return rows.stream().findFirst().filter(outcome -> !outcome.isTransient());
    }

    /**
     * 조회 결과를 성공·실패 무관하게 기록한다(upsert).
     *
     * <p>{@code TRANSIENT} 도 기록한다 — 히트로는 쓰이지 않지만(위 {@link #find}) "언제 어느 주소가
     * 장애에 걸렸는지"를 남겨 두면 장애 규모를 사후에 조회할 수 있다.
     *
     * <p>재실행/동시 실행에서 같은 주소가 겹칠 수 있어 {@code ON CONFLICT DO UPDATE} 로 둔다 —
     * 중복 키로 배치가 죽지 않는다. 뒤에 온 결과가 이긴다(장애가 회복되면 {@code TRANSIENT} 행이
     * 좌표로 덮어써진다).
     *
     * <h2>🔴 2026-09-18 — {@code REQUIRES_NEW}(갈래 A)</h2>
     * 종전에는 이 upsert 가 <b>청크 트랜잭션에 참여</b>해서, 스킵으로 청크가 롤백되면 그 청크에서 처음
     * 조회된 주소 <b>전부</b>의 캐시 행이 사라졌다. 인메모리 1차 캐시가 {@link CompositeGeocoder} 안에
     * 남아 같은 실행에서는 재조회되지 않으므로 <b>다시 기록될 기회도 없었다</b>. 별도 트랜잭션으로 떼어
     * 그 유실을 막는다.
     *
     * <p>🔴 <b>공용 컴포넌트다</b> — 민원·단속의 캐시 저장에도 즉시 적용된다(세 잡이 같은
     * {@link CompositeGeocoder} 를 쓴다). 주차장만의 변경이 아니라는 뜻이며, 나머지 두 잡도 지금까지
     * 잃어 온 롤백 청크의 캐시 쓰기를 함께 되찾는다.
     *
     * <p>⚠️ <b>프록시를 거쳐야 먹는다.</b> 이 클래스 안에서 {@code save(...)} 를 부르면 자기호출이라
     * 애노테이션이 조용히 무시된다({@link GeocodeSkipRecorder} 를 별도 빈으로 둔 것과 같은 이유).
     * 현재 유일한 호출부는 {@link CompositeGeocoder} 이며, 이 클래스 내부 자기호출은 없다.
     *
     * <p>⚠️ <b>커넥션을 동시에 2개 점유한다</b> — 청크 트랜잭션 1 + 이 저장 1. HikariCP 기본 풀 10 ·
     * 배치 <b>단일 스레드</b> 전제에서만 안전하다. 파티셔닝이나 멀티스레드 Step 을 도입하면 풀 고갈
     * 위험이 생기므로 그때는 풀 크기를 함께 올린다.
     *
     * <p>🔴 <b>실패 판정이 영구 캐시된다</b> — {@code found=false, fail_rsn='NOT_FOUND'} 가 커밋되면
     * 그 주소는 다시 조회되지 않는다(원천이 주소를 정정해도 마찬가지다). 해소는 캐시 행 삭제뿐이며,
     * <b>무효화 절차(누가·언제·어떤 행을)는 운영 절차로 정한다.</b>
     * 🔴 전체 삭제 금지 — 세 잡이 공유한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(String address, GeocodeOutcome outcome) {
        if (address == null || address.isBlank()) {
            return;
        }
        if (address.length() > ADDR_MAX_LENGTH) {
            // PK 컬럼 길이를 넘는 주소는 캐싱만 포기한다 — 캐시는 최적화용 보조 데이터라
            // 여기서 예외를 던져 배치를 멈출 이유가 없다(인메모리 캐시는 여전히 동작한다).
            log.warn("주소가 캐시 컬럼 길이({})를 넘어 DB 캐싱을 건너뜁니다. length={}",
                    ADDR_MAX_LENGTH, address.length());
            return;
        }
        Coordinate point = outcome.point();
        jdbc.update("""
                INSERT INTO batch.tb_geocode_cache (addr, lat, lot, found, fail_rsn, updated_dt)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (addr) DO UPDATE
                   SET lat = EXCLUDED.lat, lot = EXCLUDED.lot,
                       found = EXCLUDED.found, fail_rsn = EXCLUDED.fail_rsn,
                       updated_dt = now()
                """,
                address,
                point == null ? null : point.lat(),
                point == null ? null : point.lot(),
                outcome.isFound(),
                outcome.failReason());
    }

    /**
     * {@code fail_rsn} 문자열을 상태로 되돌린다.
     *
     * <p>{@code NULL} 은 {@code fail_rsn} 도입(2026-08-04) 이전에 쓰인 행이다 — 그때는
     * {@code found=false} 가 곧 "실패 확정"이었으므로 {@code NOT_FOUND} 로 읽는다. 알 수 없는 값도
     * 같은 이유로 보수적으로 {@code NOT_FOUND} 로 둔다(재조회를 무한히 유발하지 않는 쪽).
     */
    private static GeocodeOutcome failureOf(String failReason, String address) {
        if (failReason == null || failReason.isBlank()) {
            return GeocodeOutcome.notFound();
        }
        try {
            GeocodeOutcome.Status status = GeocodeOutcome.Status.valueOf(failReason);
            return status == GeocodeOutcome.Status.TRANSIENT
                    ? GeocodeOutcome.transientFailure()
                    : GeocodeOutcome.notFound();
        } catch (IllegalArgumentException e) {
            log.warn("캐시의 fail_rsn 값을 해석할 수 없어 실패 확정으로 읽습니다. value={} addr={}",
                    failReason, address);
            return GeocodeOutcome.notFound();
        }
    }
}
