package jeju.datahub.batch.geocode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 폴백 체인 + 주소 캐시 (D7 · D8 · D9).
 *
 * <pre>
 * 캐시 조회(인메모리 → DB)
 *   └ 히트: 즉시 반환 (NOT_FOUND 면 실패 확정으로 즉시 반환, API 호출 없음)
 *   └ 미스: VWorld 주소(parcel → road) → VWorld 장소검색 → stripSubLot 후 VWorld 주소
 *           → 모두 NOT_FOUND 면 GEOCODE_FAILED (호출자가 스킵 기록)
 *           → 하나라도 TRANSIENT 면 TRANSIENT (스킵 기록 금지 — 다음 실행에 재시도)
 *           → 결과를 캐시에 기록 (TRANSIENT 는 DB 에만, 관측용)
 *           → API 를 실제로 호출한 경우에만 rate-limit-ms 대기
 * </pre>
 *
 * <p>1·2단(지번 → 도로명)은 {@link VworldGeocodingClient#geocode(String)} 안에, 3단(장소 POI 검색)은
 * {@link VworldPlaceSearchClient} 에 있고, 이 클래스는 <b>체인 조립</b>과 4단(부번 제거 후 재조회) ·
 * 캐시 · 호출간격 · 연속 장애 감시를 담당한다.
 *
 * <p><b>🔄 2026-08-05 — 3단(장소 검색) 신설로 체인이 4단이 됐다(D7 재개정).</b> 순서가 중요하다:
 * 장소 검색은 <b>부번 제거보다 앞</b>이다. 부번 제거는 본번 대표좌표라 정밀도가 떨어지는 최후 수단이고,
 * 장소 검색은 질의한 지번 그대로의 좌표를 준다 — 뒤에 두면 더 정확한 결과를 스스로 버리게 된다.
 *
 * <p><b>{@link GeocodingClient} 를 구현하지 않는다.</b> 시그니처는 같지만 구현해 버리면 이 빈과
 * {@link VworldGeocodingClient} 가 같은 타입의 빈 2개가 되어, 정작 이 클래스가 주입받아야 할
 * {@code GeocodingClient} 가 모호해진다. Processor 는 이 타입을 그대로 주입받는다.
 *
 * <p><b>캐시는 write-through 다.</b> D8 은 "Job 종료 시 flush" 로 적었지만, Job 조립(Step 7) 전에도
 * 캐시가 검증 가능해야 하고 중간에 죽어도 이미 산 좌표를 잃지 않아야 해서 조회 즉시 upsert 한다.
 * 인메모리 1차 캐시({@link ConcurrentHashMap})가 앞에 있어 같은 실행 안에서 반복 신고 주소는
 * DB 조회조차 하지 않는다.
 *
 * <p>🔁 <b>2026-09-18 TSK-4162 Step 3 — 이 upsert 는 이제 별도 트랜잭션이다</b>(갈래 A).
 * 종전에는 {@code cacheRepository.save()} 가 <b>청크 트랜잭션에 참여</b>해서, 스킵으로 청크가 롤백되면
 * <b>그 청크에서 처음 조회된 주소 전부</b>의 DB 캐시 행이 사라졌다 — 실패한 그 한 건만이 아니다.
 * 더구나 인메모리 캐시가 남아 있어 같은 실행 안에서는 재조회되지 않으므로 <b>다시 기록될 기회도 없었다</b>
 * (메모리 히트가 {@code save()} 앞에서 반환한다). 청크 100 · 캐시 미스율 약 15% 기준 <b>스킵이 난 청크당
 * 약 15행</b>, 전량 실행 기준 수백 행 규모였다.
 *
 * <p>"손실 범위가 유한하니 그냥 둔다"는 종전 판단은 <b>주차장에서 깨졌다</b> — 주차장 Reader 는
 * {@code NOT EXISTS} 를 쓰지 않아 매 실행 전량을 다시 지오코딩하므로, 같은 유실이 실행마다 되풀이되며
 * VWorld 호출이 그만큼 낭비된다. 그래서
 * {@link GeocodeCacheRepository#save(String, GeocodeOutcome)} 에
 * {@code @Transactional(propagation = REQUIRES_NEW)} 를 걸었다. 🔴 대가는 <b>실패 판정도 커밋돼
 * 영구히 남는다</b>는 것이다 — 무효화 절차는 그 메서드 주석과 배치 런북에 있다.
 *
 * <h2>Job 을 중단시키는 예외 — 쿼터 초과 하나뿐이다</h2>
 * <ul>
 *   <li>{@link GeocodeQuotaExceededException} — <b>아무것도 캐싱하지 않고</b> 그대로 전파해 Job 을
 *       즉시 FAILED 로 끊는다. "못 찾은 주소"가 아니라 "오늘은 못 부르는 상태"이므로 실패로 굳히면
 *       안 된다(D9). 스킵 테이블 기록 대상이 아니다.</li>
 * </ul>
 *
 * <p><b>🔄 2026-08-05 (D9 재확인) — 연속 일시장애로는 Job 을 끊지 않는다.</b>
 * {@code geocode.transient-outage-threshold} 는 이제 <b>중단 조건이 아니라 경보 기준</b>이다
 * ({@link #trackOutage} 참조). ⚠️ 끊지 않으므로 제공자가 통째로 죽으면 전량을 헛돌고도 Job 은
 * {@code COMPLETED} 로 끝난다 — 그래서 임계값 배수마다 ERROR 를 남긴다. 그 행들은 스킵 기록이
 * 없어 재실행하면 그대로 복구된다.
 *
 * <h2>호출자(Processor, Step 6) 계약</h2>
 * 반환 {@link GeocodeOutcome} 의 상태로 분기한다 — {@code TRANSIENT} 를 {@code NOT_FOUND} 와 같이
 * 다뤄 {@code GEOCODE_FAILED} 로 스킵 기록하면 <b>일시 장애가 영구 제외로 굳는다.</b>
 * {@code GEOCODE_FAILED} 스킵은 {@code NOT_FOUND} 로 4단이 모두 실패한 경우만이다.
 */
@Component
public class CompositeGeocoder {

    private static final Logger log = LoggerFactory.getLogger(CompositeGeocoder.class);

    /**
     * DB 캐시 히트로 되살린 실패에 붙이는 단계 내역.
     *
     * <p>{@code tb_geocode_cache} 는 최종 판정만 저장하고 단계 내역은 갖고 있지 않다. 그 행이
     * {@code NOT_FOUND} 라는 것은 <b>이전 실행에서 폴백 체인이 모두 실패했다</b>는 뜻이므로, 내역 대신
     * 그 사실을 적는다 — 스킵 {@code note} 가 비어 원인을 못 읽는 상황을 만들지 않기 위함이다.
     */
    static final String CACHED_FAILURE_DETAIL = "주소 캐시 히트(이전 실행에서 폴백 체인 모두 NOT_FOUND)";

    /** 부번이 없어 4단을 건너뛴 경우의 표기(Step 7-4 note 형식). */
    static final String NO_SUB_LOT_DETAIL = "stripped=(해당없음)";

    private final GeocodingClient vworld;
    private final GeocodingClient placeSearch;
    private final GeocodeCacheRepository cacheRepository;
    private final long rateLimitMs;
    private final int transientOutageThreshold;

    /**
     * 인메모리 1차 캐시. <b>{@code TRANSIENT} 는 절대 넣지 않는다</b> — 넣으면 같은 실행 안에서도
     * 장애가 회복돼도 그 주소만 계속 실패로 읽힌다(D8 개정). 값이 있으면 곧 확정된 결과라는 뜻이다.
     */
    private final Map<String, GeocodeOutcome> memoryCache = new ConcurrentHashMap<>();

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong apiLookups = new AtomicLong();
    private final AtomicLong placeRecoveries = new AtomicLong();
    private final AtomicLong strippedRecoveries = new AtomicLong();
    private final AtomicLong transientFailures = new AtomicLong();

    /** 연속 {@code TRANSIENT} 수. 응답을 제대로 받은 조회(FOUND·NOT_FOUND)가 나오면 0 으로 리셋된다. */
    private final AtomicInteger consecutiveTransients = new AtomicInteger();

    /**
     * @param vworld 주소 지오코딩 제공자(1·2·4단). <b>{@link Qualifier} 로 빈을 못박는다</b> —
     *               {@link GeocodingClient} 구현이 둘({@link VworldGeocodingClient},
     *               {@link VworldPlaceSearchClient})이라 타입으로만 주입하면
     *               {@code NoUniqueBeanDefinitionException} 으로 <b>기동이 죽는다.</b>
     *               <p>제공자를 추가할 때는 여기 파라미터를 늘리고 {@link #resolve} 의 체인에 끼워
     *               넣는다 — 계획서 D7 이 정한 확장 방식이다.
     * @param placeSearch 장소(POI) 검색 제공자(3단, D7 2026-08-05 재개정). 주소DB 에 없는 지번을
     *               POI 로 살린다. 반환 주소 검증은 클라이언트 안에서 끝나므로 여기서는
     *               {@code FOUND} 를 그대로 신뢰한다
     * @param transientOutageThreshold 연속 일시장애 <b>몇 건마다 ERROR 경보를 낼지</b>({@code 0}
     *               이하면 경보 끄기). <b>Job 을 끊지는 않는다</b>(D9 2026-08-05 재확인) — 산발적
     *               실패로 배치가 죽으면 운영이 번거롭다는 판단이다. 기본 10
     */
    public CompositeGeocoder(
            @Qualifier("vworldGeocodingClient") GeocodingClient vworld,
            @Qualifier("vworldPlaceSearchClient") GeocodingClient placeSearch,
            GeocodeCacheRepository cacheRepository,
            @Value("${geocode.rate-limit-ms}") long rateLimitMs,
            @Value("${geocode.transient-outage-threshold}") int transientOutageThreshold) {
        this.vworld = vworld;
        this.placeSearch = placeSearch;
        this.cacheRepository = cacheRepository;
        this.rateLimitMs = rateLimitMs;
        this.transientOutageThreshold = transientOutageThreshold;
    }

    /**
     * @return 4단 폴백까지 태운 결과. {@code NOT_FOUND} 면 호출자가 {@code GEOCODE_FAILED} 로 스킵
     *         기록하고, <b>{@code TRANSIENT} 면 스킵 기록 없이 이번 실행에서만 건너뛴다</b>
     * @throws GeocodeQuotaExceededException 호출 한도 초과 — 캐싱하지 않고 그대로 전파(D9)
     * <p>연속 일시장애로는 <b>예외를 던지지 않는다</b> — 임계값 배수마다 ERROR 로그만 남긴다(D9 재확인).
     */
    public GeocodeOutcome geocode(String address) {
        if (address == null || address.isBlank()) {
            return GeocodeOutcome.notFound();
        }

        GeocodeOutcome memoryHit = memoryCache.get(address);
        if (memoryHit != null) {
            cacheHits.incrementAndGet();
            return memoryHit;
        }

        // TRANSIENT 행은 여기서 히트로 잡히지 않는다 — 아래로 내려가 다시 조회된다.
        GeocodeOutcome stored = cacheRepository.find(address).orElse(null); // 5.1 — Optional.get() 대신 null 검사 뒤 사용(TSK-4336)
        if (stored != null) {
            // 캐시에는 단계 내역이 없다 — 실패 히트면 "이전 실행에서 실패한 주소"라는 사실을 채운다.
            GeocodeOutcome hit = stored;
            if (!hit.isFound() && hit.detail() == null) {
                hit = hit.withDetail(CACHED_FAILURE_DETAIL);
            }
            memoryCache.put(address, hit);
            cacheHits.incrementAndGet();
            return hit;
        }

        apiLookups.incrementAndGet();
        GeocodeOutcome resolved = resolve(address);
        if (!resolved.isTransient()) {
            memoryCache.put(address, resolved);
        }
        // TRANSIENT 도 DB 에는 남긴다(관측용). find() 가 히트로 치지 않으므로 재조회를 막지 않는다.
        cacheRepository.save(address, resolved);
        trackOutage(address, resolved);
        // 캐시 히트에는 대기가 없다 — 실제로 외부 API 를 부른 이 경로에서만 쉰다.
        awaitRateLimit();
        return resolved;
    }

    /**
     * D7 폴백 체인 4단 — <b>VWorld 주소(지번→도로명) → VWorld 장소검색 → 부번 제거 후 VWorld 주소.</b>
     *
     * <p><b>순서를 바꾸지 말 것.</b> 장소 검색(3단)은 부번 제거(4단)보다 <b>앞</b>이다 — 부번 제거는
     * 본번 대표좌표라 번지 단위 정밀도를 잃는 최후 수단이고, 장소 검색은 질의한 지번 그대로의 좌표를
     * 준다. 뒤에 두면 더 정확한 좌표를 두고 덜 정확한 좌표를 채택하게 된다.
     *
     * <p>실패하면 단계별 판정을 이어 붙여 {@link GeocodeOutcome#detail()} 에 싣는다 —
     * {@code parcel=NOT_FOUND, road=NOT_FOUND, place=NOT_FOUND, stripped(...258)=NOT_FOUND}.
     * 이 문자열이 그대로 스킵 테이블의 {@code note} 가 되어 "어느 단계까지 갔는지"를 사후에
     * 판단하게 해 준다(Step 7-4).
     *
     * <p><b>한 단계라도 {@code TRANSIENT} 면 전체가 {@code TRANSIENT}</b> 다 — 물어보지 못한 단계가
     * 있는 이상 "그 주소는 없다"고 단정할 수 없고, 단정하면 실패로 캐싱돼 복구가 불가능해진다(D8).
     */
    private GeocodeOutcome resolve(String address) {
        // 1·2단 — VWorld 주소 API(지번 → 도로명).
        GeocodeOutcome direct = vworld.geocode(address);
        if (direct.isFound()) {
            return direct;
        }

        // 3단 — 장소(POI) 검색. 주소DB 에 없는 지번이 여기서 살아난다(D7 2026-08-05 재개정).
        GeocodeOutcome place = placeSearch.geocode(address);
        if (place.isFound()) {
            placeRecoveries.incrementAndGet();
            // 이 단계가 실제로 얼마나 기여하는지 봐야 하므로 건별로 남긴다. 부번제거(4단)와 달리
            // 질의한 지번 그대로의 좌표라 정밀도 경고는 아니다.
            log.info("장소(POI) 검색으로 복구(주소DB 미수록) addr={}", address);
            return place;
        }
        String detail = appendStage(direct.detail(), placeStage(place));

        // 4단 — 부번을 떼고 VWorld 주소 API 재조회.
        String mainLotOnly = stripSubLot(address);
        if (mainLotOnly == null) {
            // 뗄 부번이 없으면 같은 주소를 또 묻는 셈이라 호출하지 않는다 — 건너뛴 사실은 남긴다.
            return failure(appendStage(detail, NO_SUB_LOT_DETAIL), direct, place);
        }

        GeocodeOutcome byMainLot = vworld.geocode(mainLotOnly);
        if (byMainLot.isFound()) {
            strippedRecoveries.incrementAndGet();
            // 본번 대표좌표라 번지 단위 정밀도가 아니다. 읍면동 중심좌표보다는 훨씬 낫지만
            // 얼마나 쓰였는지 남겨 둔다(Node 프로토타입 동작 승계).
            log.warn("본번만으로 재조회 성공(정밀도 낮음) addr={} -> {}", address, mainLotOnly);
            return byMainLot;
        }
        return failure(
                appendStage(detail, "stripped(" + mainLotOnly + ")=" + byMainLot.status()),
                direct, place, byMainLot);
    }

    /** 실패 확정({@code NOT_FOUND}) vs 이번 실행만 실패({@code TRANSIENT}) 판정 — 하나라도 일시 장애면 후자. */
    private static GeocodeOutcome failure(String detail, GeocodeOutcome... stages) {
        for (GeocodeOutcome stage : stages) {
            if (stage.isTransient()) {
                return GeocodeOutcome.transientFailure(detail);
            }
        }
        return GeocodeOutcome.notFound(detail);
    }

    /**
     * 장소 검색 단계의 판정 문자열. 클라이언트가 이미 사유를 담아 주면(주소 불일치 등) 그대로 쓰고,
     * 없으면 상태만 적는다 — 스텁/다른 구현체가 들어와도 {@code note} 가 비지 않게 한다.
     */
    private static String placeStage(GeocodeOutcome place) {
        String detail = place.detail();
        return detail == null || detail.isBlank() ? "place=" + place.status() : detail;
    }

    /** {@code "parcel=NOT_FOUND, road=NOT_FOUND"} 뒤에 다음 단계 판정을 잇는다. */
    private static String appendStage(String detail, String stage) {
        return detail == null || detail.isBlank() ? stage : detail + ", " + stage;
    }

    /**
     * 연속 일시장애 감시(D9). 제공자가 응답을 제대로 준 조회({@code FOUND}·{@code NOT_FOUND})는
     * "살아 있다"는 증거이므로 카운터를 0 으로 되돌린다.
     *
     * <p>캐시 히트는 카운터를 건드리지 않는다 — 외부 API 를 부르지 않았으니 제공자 상태에 대한
     * 증거가 아니다. 즉 이 카운터는 <b>실제로 API 를 태운 행들의 연속 실패</b>를 센다.
     */
    private void trackOutage(String address, GeocodeOutcome outcome) {
        if (!outcome.isTransient()) {
            consecutiveTransients.set(0);
            return;
        }
        transientFailures.incrementAndGet();
        int streak = consecutiveTransients.incrementAndGet();
        // 🔄 2026-08-05 (D9 재확인) — 연속 장애로 Job 을 끊지 않는다. 임계값은 "중단 조건"이 아니라
        //    "경보 기준"이다. 대신 조용히 흘려보내지 않는다 — 임계값 도달 시점과 그 뒤 임계값 배수마다
        //    ERROR 를 남겨, 종료 후 filtered(TRANSIENT) 수치만 보고 원인을 되짚지 않아도 되게 한다.
        //    ⚠️ 끊지 않으므로 제공자가 통째로 죽으면 전량(고유 주소 12,419건)을 헛돈다. 주소당 최대
        //    16회 HTTP + 백오프(~7초)라 수 시간~하루가 걸리고, Job 은 COMPLETED 로 끝난다.
        //    그 행들은 스킵 테이블에 남지 않아 재실행하면 그대로 복구된다(D8).
        if (transientOutageThreshold > 0 && streak % transientOutageThreshold == 0) {
            log.error("지오코딩 일시 장애가 연속 {}건입니다(경보 기준 {}). 제공자 상태를 확인하세요 — "
                            + "Job 은 중단하지 않으며, 이 행들은 스킵 기록 없이 다음 실행에 재시도됩니다. 마지막 주소={}",
                    streak, transientOutageThreshold, address);
        }
    }

    /**
     * "본번-부번" 에서 부번 제거 ({@code "...258-1"} → {@code "...258"}).
     * 뗄 게 없으면 {@code null} 로 무의미한 재조회를 막는다.
     */
    static String stripSubLot(String address) {
        String stripped = address.replaceAll("(\\d+)-\\d+\\s*$", "$1");
        return stripped.equals(address) ? null : stripped;
    }

    private void awaitRateLimit() {
        if (rateLimitMs <= 0) {
            return;
        }
        try {
            Thread.sleep(rateLimitMs);
        } catch (InterruptedException e) {
            // 중단 요청은 삼키지 않고 플래그만 복원한다 — 이미 구한 좌표는 그대로 반환한다.
            Thread.currentThread().interrupt();
        }
    }

    /** 캐시(인메모리+DB)로 외부 호출 없이 해결된 횟수. */
    public long cacheHits() {
        return cacheHits.get();
    }

    /** 캐시 미스로 외부 API 폴백 체인을 태운 주소 수. */
    public long apiLookups() {
        return apiLookups.get();
    }

    /**
     * 3단(장소 POI 검색)에서 살아난 주소 수 — <b>이 단계가 실제로 얼마나 기여하는지</b>를 본다.
     *
     * <p>부번제거(4단)와 따로 센다. 둘은 성격이 다르다: 장소 검색은 질의한 지번 그대로의 좌표라
     * 정밀도 손실이 없고, 부번제거는 본번 대표좌표라 정밀도를 잃는다. 한 수치로 합치면 "정밀도가
     * 낮은 좌표가 몇 건인가"를 알 수 없게 된다.
     */
    public long placeRecoveries() {
        return placeRecoveries.get();
    }

    /** 4단(부번 제거)에서 살아난 주소 수 — 정밀도가 낮은 좌표의 규모. */
    public long strippedRecoveries() {
        return strippedRecoveries.get();
    }

    /** 일시 장애로 이번 실행에서 처리하지 못한 주소 수 — 다음 실행에 다시 들어온다. */
    public long transientFailures() {
        return transientFailures.get();
    }

    /** 현재 연속 일시장애 수. 임계값 배수마다 ERROR 경보가 난다. */
    public int consecutiveTransients() {
        return consecutiveTransients.get();
    }

    /**
     * 카운터를 0 으로 되돌린다 — <b>Job 시작 시점에 호출한다</b>(Step 7-4 요약 로그).
     *
     * <p>이 빈은 싱글턴이라 같은 JVM 에서 Job 을 두 번 돌리면 카운터가 <b>누적</b>돼 종료 요약이
     * 이번 실행의 수치가 아니게 된다. 수동 트리거로 여러 번 돌리는 것이 기본 사용법이라 반드시 필요하다.
     *
     * <p><b>인메모리 캐시는 비우지 않는다.</b> 캐시는 실행 간에도 유효한 조회 결과이고, 비우면
     * 두 번째 실행이 같은 주소를 API 로 다시 물어 쿼터를 낭비한다. 리셋 대상은 관측 수치뿐이다.
     */
    public void resetStats() {
        cacheHits.set(0);
        apiLookups.set(0);
        placeRecoveries.set(0);
        strippedRecoveries.set(0);
        transientFailures.set(0);
        consecutiveTransients.set(0);
    }
}
