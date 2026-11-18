package jeju.datahub.analysis.service.impl;

import org.springframework.transaction.TransactionException;
import org.springframework.dao.DataAccessException;
import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jeju.datahub.analysis.service.impl.VworldCallSupport.LookupStatus;
import jeju.datahub.analysis.service.impl.VworldPlaceClient.NearestPlace;
import jeju.datahub.analysis.service.impl.VworldReverseGeocodeClient.ReverseAddress;

/**
 * 구역명 폴백 체인(TSK-4032 D6)의 <b>②~④단계</b>를 조립한다.
 *
 * <pre>
 * ① 군집 반경 내 단속주소 중 장소명   → HotspotAnalysisMapper.xml 이 SQL 로 채운다 (여기 없음)
 * ② 캐시 → VWorld POI 최근접(150m)   → zoneNmSrc = POI
 * ③ 캐시 → VWorld 역지오코딩 지번     → zoneNmSrc = REVERSE_GEOCODE
 * ④ 폴백 실패 · 외부 API 장애         → "{읍면동명} 일대", zoneNmSrc = EMD
 * </pre>
 *
 * <h2>🔴 예외를 밖으로 던지지 않는다</h2>
 * 키가 없든 VWorld 가 죽었든 <b>④로 내려가고 WARN 만 남긴다.</b> 집중구역 순위 자체는 DB 만으로
 * 계산되므로 이름 때문에 화면이 죽어서는 안 된다. 캐시 저장 실패도 마찬가지로 삼킨다 — 캐시는
 * 성능 장치이지 정확성의 전제가 아니다.
 *
 * <h2>캐시 정책</h2>
 * <ul>
 *   <li>키는 <b>중심좌표를 소수 5자리(약 1m)로 반올림</b>한 값이다. 같은 기간·지역 조합을 다시
 *       조회하면 군집 중심점이 동일해 그대로 맞는다.</li>
 *   <li>저장하는 값은 <b>최종 구역명</b>(읍면동명 접두까지 붙인 형태)이라 히트 시 그대로 쓴다.</li>
 *   <li>{@code NOT_FOUND}(POI 도 역지오코딩도 결과 없음)는 <b>저장한다</b> — 확정 실패를 캐시
 *       히트로 만들어 재호출을 막는다.</li>
 *   <li>🔴 <b>일시 실패(타임아웃·5xx)는 저장하지 않는다</b> — 저장하면 외부 장애 한 번이 영구
 *       {@code 일대} 로 굳는다. 다음 조회가 다시 시도해야 한다(rule_batch-project.md "캐시" 와 동일).</li>
 *   <li>🔴 저장은 {@link ZoneNameCacheWriter}(별도 빈 · {@code REQUIRES_NEW})가 <b>독립 트랜잭션</b>
 *       에서 한다. 호출부가 {@code @Transactional(readOnly = true)} 여도 부모 트랜잭션을 오염시키지
 *       않기 위해서다 — 자세한 경위는 그 클래스 주석 참고.</li>
 * </ul>
 *
 * <h2>호출 상한</h2>
 * 한 요청이 외부를 부르는 횟수는 {@code analysis.zone-name.max-lookups-per-run}(기본 50)
 * 으로 묶는다. 그래서 상태가 필요하고, 그 상태를 빈에 두면 동시 요청이 섞이므로
 * <b>요청마다 {@link #newSession()} 으로 세션을 하나 만들어</b> 쓴다. 캐시 히트는 외부를 부르지
 * 않으므로 상한을 소모하지 않는다.
 *
 * <p>🔁 TSK-4272 — 상한이 15(집중구역 상위 15곳 = 목록 길이)에서 <b>50</b>으로 올랐다(사용자 결정). 생활권 순위(D21)·수급 목록이
 * 상한 없는 목록이 되어 15 로는 첫 조회의 대부분이 폴백 이름이었다(실측 수급 목록 대상 최대 193칸).
 * 정상 응답은 호출당 약 0.05초라 50건이면 첫 조회가 수 초 늘어나는 정도다. 목록이 50칸보다 길면 뒤쪽은
 * 폴백이고, 캐시 히트는 상한을 소모하지 않으므로 같은 조회를 반복하면 50칸씩 이름이 채워진다.
 *
 * <h2>🔴 연속 일시 실패 차단</h2>
 * 상한을 올린 대가로 <b>VWorld 장애 시 최악 시간</b>이 커진다 — 좌표 1건이 POI·역지오코딩 각각
 * 타임아웃 2초 × 2회 시도라 약 8초이고, 50건이면 요청 하나가 약 7분을 점유한다. 그래서 좌표가
 * {@code analysis.zone-name.max-consecutive-transient}(기본 3)건 <b>연속으로</b> 일시 실패하면 그 세션은
 * 외부 조회를 멈추고 남은 캐시 미스를 바로 ④로 내린다(WARN 1줄). 최악이 약 24초로 묶인다.
 * 세션 단위라 <b>다음 요청은 다시 시도한다</b> — 전역 차단기를 두면 복구 시점을 따로 판정해야 한다.
 */
@Component
public class ZoneNameResolver {

    private static final Logger log = LoggerFactory.getLogger(ZoneNameResolver.class);

    /** 좌표키 소수 자릿수. 5자리 = 약 1m. */
    static final int KEY_SCALE = 5;

    /** 구역명 최대 길이 — {@code TB_ZONE_NM_CACHE.zone_nm} · 응답 필드와 같은 100자. */
    static final int MAX_NAME_LENGTH = 100;

    /** VWorld POI 최근접 (②). */
    public static final String SRC_POI = "POI";

    /** VWorld 역지오코딩 지번 (③). */
    public static final String SRC_REVERSE_GEOCODE = "REVERSE_GEOCODE";

    /** {@code {읍면동명} 일대} (④). */
    public static final String SRC_EMD = "EMD";

    /** 캐시 전용 출처값 — ②·③ 둘 다 확정 실패. 응답으로 나가지 않는다. */
    static final String SRC_NOT_FOUND = "NOT_FOUND";

    /** 읍면동명조차 없을 때의 최후 이름. 중심점이 행정경계 밖일 때만 나온다. */
    static final String UNKNOWN_ZONE_NM = "위치 미상";

    private final ZoneNameCacheMapper cacheMapper;
    private final ZoneNameCacheWriter cacheWriter;
    private final VworldPlaceClient placeClient;
    private final VworldReverseGeocodeClient reverseGeocodeClient;
    private final int maxLookupsPerRun;
    private final int maxConsecutiveTransient;

    public ZoneNameResolver(ZoneNameCacheMapper cacheMapper,
                            ZoneNameCacheWriter cacheWriter,
                            VworldPlaceClient placeClient,
                            VworldReverseGeocodeClient reverseGeocodeClient,
                            @Value("${analysis.zone-name.max-lookups-per-run:50}") int maxLookupsPerRun,
                            @Value("${analysis.zone-name.max-consecutive-transient:3}") int maxConsecutiveTransient) {
        this.cacheMapper = cacheMapper;
        this.cacheWriter = cacheWriter;
        this.placeClient = placeClient;
        this.reverseGeocodeClient = reverseGeocodeClient;
        this.maxLookupsPerRun = maxLookupsPerRun;
        this.maxConsecutiveTransient = maxConsecutiveTransient;
    }

    /**
     * 확정된 구역명 1건.
     *
     * @param zoneNm    화면에 그대로 쓰는 이름. 비지 않는다
     * @param zoneNmSrc {@link #SRC_POI} / {@link #SRC_REVERSE_GEOCODE} / {@link #SRC_EMD}
     * @param poiDistM  POI 채택 시 중심점까지 거리(m). 그 외에는 {@code null}
     */
    public record ZoneName(String zoneNm, String zoneNmSrc, Integer poiDistM) {
    }

    /** 요청 1건이 쓰는 조회 세션. 외부 호출 상한을 여기서 센다. */
    public Session newSession() {
        return newSession(maxLookupsPerRun);
    }

    /**
     * 외부 조회 상한을 직접 정한 세션 — 구역명 캐시 미리 채우기(TSK-4339 Step 5)가 호출 1회의
     * {@code limit} 으로 쓴다. 연속 일시 실패 차단은 설정값 그대로다.
     *
     * @param maxLookups 이 세션의 외부 조회 상한. 1 이상
     * @throws IllegalArgumentException {@code maxLookups <= 0}
     */
    public Session newSession(int maxLookups) {
        if (maxLookups <= 0) {
            throw new IllegalArgumentException("maxLookups 는 1 이상이어야 합니다: " + maxLookups);
        }
        return new Session(maxLookups);
    }

    /**
     * 한 번의 분석 요청 동안 유지되는 조회 세션. <b>스레드 안전하지 않다</b> — 요청 하나가 순차로
     * 쓰는 것을 전제로 한다(빈이 아니라 세션에 상태를 둔 이유가 그것이다).
     */
    public final class Session {

        private int remainingLookups;
        /** 연속 일시 실패 좌표 수 — 일시 실패가 없던 조회 1건이 0 으로 되돌린다 */
        private int consecutiveTransient;
        /** 연속 일시 실패 차단이 걸렸는가 — 걸리면 이 세션은 외부를 더 부르지 않는다 */
        private boolean tripped;
        /** 이 세션의 외부 조회로 캐시 저장에 성공한 좌표 수 (미리 채우기 집계용) */
        private int storedCnt;

        private Session(int remainingLookups) {
            this.remainingLookups = remainingLookups;
        }

        /** 연속 일시 실패 차단이 걸렸는가 */
        public boolean isTripped() {
            return tripped;
        }

        /**
         * 이 세션의 외부 조회 중 캐시에 저장된 좌표 수(POI · 역지오코딩 · 확정 실패 NOT_FOUND).
         * 캐시 히트·일시 실패·저장 실패는 세지 않는다 — 반환 출처만으로는 갈 수 없다
         * (NOT_FOUND 도 {@link #SRC_EMD} 로 돌아오고, ②가 일시 실패한 ③ 결과는 저장되지 않는다).
         */
        public int storedCount() {
            return storedCnt;
        }

        /**
         * 중심좌표의 구역명. 캐시 → POI → 역지오코딩 → {@code {읍면동명} 일대} 순으로 내려간다.
         *
         * @param lat   군집 중심 위도(EPSG:4326)
         * @param lot   군집 중심 경도(EPSG:4326)
         * @param emdNm 중심점이 속한 읍면동명. ②의 검색어이자 ④의 이름이 된다
         * @return 항상 이름이 채워진 결과. 예외를 던지지 않는다
         */
        public ZoneName resolve(BigDecimal lat, BigDecimal lot, String emdNm) {
            if (lat == null || lot == null) {
                return emdFallback(emdNm);
            }
            BigDecimal latKey = key(lat);
            BigDecimal lotKey = key(lot);

            ZoneNameCacheVO cached = findCached(latKey, lotKey);
            if (cached != null) {
                if (SRC_NOT_FOUND.equals(cached.getZoneNmSrc()) || cached.getZoneNm() == null) {
                    return emdFallback(emdNm);            // 확정 실패의 캐시 히트 — 외부를 부르지 않는다
                }
                return new ZoneName(cached.getZoneNm(), cached.getZoneNmSrc(), cached.getPoiDistM());
            }

            if (tripped) {
                return emdFallback(emdNm);                // 차단 WARN 은 걸릴 때 1줄만 남겼다
            }
            if (remainingLookups <= 0) {
                log.warn("구역명 외부 조회 상한({}회)에 걸려 '{} 일대' 로 내려갑니다."
                        + " analysis.zone-name.max-lookups-per-run 을 확인하세요.", maxLookupsPerRun, emdNm);
                return emdFallback(emdNm);
            }
            // 한 좌표 = 조회 1건으로 센다. ②·③ 은 같은 좌표를 채우는 한 묶음이라 따로 세지 않는다.
            remainingLookups--;
            Lookup result = lookup(latKey, lotKey, lat, lot, emdNm);
            if (result.stored()) {
                storedCnt++;
            }
            consecutiveTransient = result.transientSeen() ? consecutiveTransient + 1 : 0;
            if (maxConsecutiveTransient > 0 && consecutiveTransient >= maxConsecutiveTransient) {
                tripped = true;
                log.warn("VWorld 구역명 조회가 {}건 연속 일시 실패해 이 요청의 외부 조회를 멈춥니다 — 남은 행은 폴백 이름입니다."
                        + " analysis.zone-name.max-consecutive-transient 를 확인하세요.", consecutiveTransient);
            }
            return result.name();
        }
    }

    /** 외부 조회 1건의 결과 — 이름 + 일시 실패가 섞였는지(연속 일시 실패 차단 재료) + 캐시 저장 성공 여부 */
    private record Lookup(ZoneName name, boolean transientSeen, boolean stored) {
    }

    /** ② → ③ → ④ 순서로 실제 조회. 일시 실패가 한 번이라도 있었으면 캐시에 굳히지 않는다. */
    private Lookup lookup(BigDecimal latKey, BigDecimal lotKey,
                            BigDecimal lat, BigDecimal lot, String emdNm) {
        boolean transientSeen = false;

        // ② POI 최근접 (랜드마크 · 150m 이내)
        NearestPlace place = placeClient.findNearestLandmark(lat, lot, emdNm);
        if (place.status() == LookupStatus.FOUND) {
            String zoneNm = compose(emdNm, place.title());
            boolean stored = store(latKey, lotKey, zoneNm, SRC_POI, place.distM());
            return new Lookup(new ZoneName(zoneNm, SRC_POI, place.distM()), false, stored);
        }
        transientSeen = place.status() == LookupStatus.TRANSIENT_FAILURE;

        // ③ 역지오코딩 지번
        ReverseAddress address = reverseGeocodeClient.findAddress(lat, lot);
        if (address.status() == LookupStatus.FOUND) {
            String zoneNm = compose(emdNm, address.address());
            boolean stored = false;
            if (transientSeen) {
                // ②가 일시 실패였다 — POI 가 있었을지 모르므로 더 낮은 단계의 결과를 굳히지 않는다.
                log.warn("POI 조회가 일시 실패해 역지오코딩 결과를 캐시하지 않습니다. lat={} lot={}", latKey, lotKey);
            } else {
                stored = store(latKey, lotKey, zoneNm, SRC_REVERSE_GEOCODE, null);
            }
            return new Lookup(new ZoneName(zoneNm, SRC_REVERSE_GEOCODE, null), transientSeen, stored);
        }
        transientSeen = transientSeen || address.status() == LookupStatus.TRANSIENT_FAILURE;

        // ④ 폴백
        boolean stored = false;
        if (transientSeen) {
            log.warn("VWorld 구역명 조회가 일시 실패했습니다 — '{} 일대' 로 내려갑니다(캐시하지 않음).", emdNm);
        } else {
            // 확정 실패만 굳힌다. 다음 조회는 외부를 부르지 않고 바로 ④로 간다.
            stored = store(latKey, lotKey, null, SRC_NOT_FOUND, null);
        }
        return new Lookup(emdFallback(emdNm), transientSeen, stored);
    }

    /** ④ {@code {읍면동명} 일대}. 읍면동조차 없으면(중심점이 행정경계 밖) {@link #UNKNOWN_ZONE_NM}. */
    static ZoneName emdFallback(String emdNm) {
        String zoneNm = (emdNm == null || emdNm.isBlank()) ? UNKNOWN_ZONE_NM : emdNm.trim() + " 일대";
        return new ZoneName(truncate(zoneNm), SRC_EMD, null);
    }

    /**
     * 읍면동명 접두를 붙인 최종 이름. ①단계 SQL 과 같은 규칙이다 —
     * 이름이 이미 읍면동으로 시작하면 그대로 두고(역지오코딩 지번이 그렇다), 아니면 앞에 붙인다.
     */
    static String compose(String emdNm, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String name = rawName.trim();
        if (emdNm == null || emdNm.isBlank()) {
            return truncate(name);
        }
        String emd = emdNm.trim();
        return truncate(name.startsWith(emd) ? name : emd + " " + name);
    }

    /** 좌표 → 캐시 키(소수 5자리 반올림). */
    static BigDecimal key(BigDecimal coord) {
        return coord.setScale(KEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String truncate(String name) {
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }

    /** 캐시 조회. 실패해도 조회를 계속한다(캐시는 성능 장치이지 정확성의 전제가 아니다). */
    private ZoneNameCacheVO findCached(BigDecimal latKey, BigDecimal lotKey) {
        try {
            return cacheMapper.selectZoneName(latKey, lotKey);
        } catch (DataAccessException | IllegalStateException e) { // 4.3: DB 쿼리 실패 · 상태 오류(ISE)
            log.warn("구역명 캐시 조회 실패 — 외부 조회로 진행합니다. lat={} lot={}", latKey, lotKey, e);
            return null;
        }
    }

    /**
     * 캐시 저장. 실패해도 이름은 이미 확정돼 있으므로 WARN 만 남긴다.
     *
     * <p>🔴 저장은 반드시 {@link ZoneNameCacheWriter}(별도 빈, {@code REQUIRES_NEW})를 거친다 —
     * 호출부가 {@code @Transactional(readOnly = true)} 안이면 INSERT 가 25006 으로 실패하면서
     * <b>부모 트랜잭션까지 abort</b> 시키기 때문이다(이후 조회가 25P02 로 전부 죽는다).
     * 예외를 여기서 잡는 것도 의도적이다 — 트랜잭션 경계 <b>바깥</b>이라 삼켜도 부모가 오염되지 않는다.
     */
    private boolean store(BigDecimal latKey, BigDecimal lotKey, String zoneNm, String src, Integer distM) {
        try {
            cacheWriter.save(latKey, lotKey, zoneNm, src, distM);
            return true;
        } catch (DataAccessException | TransactionException | IllegalStateException e) { // 4.3: DB 쿼리·트랜잭션(REQUIRES_NEW 커밋 포함) 실패 · 상태 오류(ISE)
            log.warn("구역명 캐시 저장 실패 — 이름은 그대로 사용합니다. lat={} lot={} src={}",
                    latKey, lotKey, src, e);
            return false;
        }
    }
}
