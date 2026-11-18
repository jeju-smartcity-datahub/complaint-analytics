package jeju.datahub.batch.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link CompositeGeocoder} 의 폴백 체인·캐시 동작 테스트.
 *
 * <p>외부 API 는 {@link RecordingClient} 로 대체한다 — 검증 대상이 "언제 API 를 부르고 언제 안
 * 부르는가"라서 호출 횟수를 셀 수 있어야 하고, 실호출로는 재현할 수 없는 분기(쿼터 초과, 일시 장애
 * 소진 등)가 있다. 실호출 확인은 {@link VworldGeocodingClientLiveTest} ·
 * {@link VworldPlaceSearchClientLiveTest} 가 담당한다.
 *
 * <p><b>제공자 스텁이 둘</b>인 이유는 체인이 4단이기 때문이다(D7 2026-08-05 재개정) —
 * 1·2·4단은 주소 API({@code vworld}), 3단은 장소 검색({@code place}). 어느 단계에서 좌표가 나왔는지
 * 구분하려면 호출 기록도 따로 있어야 한다.
 *
 * <p>캐시는 <b>실제 {@code batch.tb_geocode_cache}</b> 를 쓴다(로컬 DB 필요).
 *
 * <h2>🔴 2026-09-18 — {@code @Transactional} 을 쓰지 않는다</h2>
 * {@link GeocodeCacheRepository#save(String, GeocodeOutcome)} 이 {@code REQUIRES_NEW} 가 되면서,
 * 클래스 {@code @Transactional} 로는 이 테스트가 <b>멈춘다</b> — 바깥 트랜잭션이 잡은 같은 캐시 키의
 * 행 잠금을 {@code save()} 의 새 커넥션이 기다려 <b>교착이 감지되지 않는 무한 대기</b>가 된다.
 * 또 롤백에 기대던 정리가 더 이상 듣지 않아 테스트 판정({@code NOT_FOUND} 등)이 <b>공유 캐시에
 * 커밋돼 남는다</b>.
 *
 * <p>그래서 ① 픽스처 주소를 <b>테스트 전용 접두 {@code T4162 }</b> 로 바꾸고(실제 제주 주소를 쓰면
 * 운영 캐시와 같은 키를 다툰다) ② {@code @BeforeEach}/{@code @AfterEach} 가
 * {@code DELETE … WHERE addr LIKE 'T4162 %'} 로 <b>커밋해서</b> 지운다.
 * 🔴 <b>그 접두 밖의 행은 절대 지우지 않는다</b> — 세 잡이 공유하는 운영 캐시가 통째로 날아간다
 * ({@code GeocodeSkipRecorderTest} 의 {@code T4054REC-%} 방식과 같다).
 *
 * <p>접두를 붙여도 {@code stripSubLot} 은 <b>주소 끝</b>의 {@code 숫자-숫자} 만 보므로 4단 폴백 검증은
 * 그대로 성립한다. 순수함수 단언({@link #stripsSubLotOnly()})만 DB 를 타지 않아 실주소를 유지한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class CompositeGeocoderTest {

    private static final String KNOWN_ADDRESS = "T4162 삼도이동 1264";
    private static final String UNKNOWN_ADDRESS = "T4162 상도리 3204-1";
    private static final Coordinate JEJU_POINT =
            new Coordinate(new BigDecimal("33.5181430745"), new BigDecimal("126.5171603281"));

    /** 임계값에 걸리지 않을 만큼 큰 값 — 연속 장애 중단은 전용 테스트에서만 검증한다. */
    private static final int NO_OUTAGE_CUTOFF = 1_000;

    @Autowired
    private GeocodeCacheRepository cacheRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 픽스처 행을 캐시에서 비운다 — <b>테스트가 실행 순서·이전 실행에 휘둘리지 않게</b>.
     * 앞뒤로 모두 돈다: 앞은 "캐시 미스에서 시작한다"는 전제를 세우고, 뒤는 {@code REQUIRES_NEW} 로
     * <b>커밋돼 버린</b> 판정을 치운다(더는 롤백이 치워 주지 않는다).
     *
     * <p>🔴 조건은 <b>테스트 전용 접두 하나뿐</b>이다 — {@code src_se} 만으로 지우면 실 이력이 사라지던
     * {@code GeocodeSkipRecorderTest} 와 같은 이유로, 여기서도 접두 밖 행(운영 캐시 1.3만여 행)은
     * 손대지 않는다.
     */
    @BeforeEach
    @AfterEach
    void clearFixtureCacheRows() {
        jdbc.update("DELETE FROM batch.tb_geocode_cache WHERE addr LIKE 'T4162 %'");
    }

    @Test
    @DisplayName("stripSubLot — 부번만 뗀다. 뗄 게 없으면 null")
    void stripsSubLotOnly() {
        assertThat(CompositeGeocoder.stripSubLot("제주특별자치도 제주시 애월읍 하귀1리 258-1"))
                .isEqualTo("제주특별자치도 제주시 애월읍 하귀1리 258");
        assertThat(CompositeGeocoder.stripSubLot("제주특별자치도 서귀포시 남원읍 위미리 산 14-27"))
                .isEqualTo("제주특별자치도 서귀포시 남원읍 위미리 산 14");
        // 부번이 없으면 같은 주소를 한 번 더 묻게 되므로 null 로 4단을 건너뛴다.
        assertThat(CompositeGeocoder.stripSubLot("제주특별자치도 제주시 애월읍 하귀1리 258")).isNull();
    }

    @Test
    @DisplayName("같은 주소를 두 번 조회해도 외부 호출은 1회 — 캐시 행도 1건만 는다")
    void reusesCachedResult() {
        long before = cacheRowCount();
        RecordingClient client = new RecordingClient(Map.of(KNOWN_ADDRESS, JEJU_POINT));
        CompositeGeocoder geocoder = newGeocoder(client);

        GeocodeOutcome first = geocoder.geocode(KNOWN_ADDRESS);
        GeocodeOutcome second = geocoder.geocode(KNOWN_ADDRESS);

        assertThat(first.coordinate()).contains(JEJU_POINT);
        assertThat(second.coordinate()).contains(JEJU_POINT);
        assertThat(client.queried).containsExactly(KNOWN_ADDRESS); // 2회차는 호출 없음
        assertThat(geocoder.apiLookups()).isEqualTo(1);
        assertThat(geocoder.cacheHits()).isEqualTo(1);
        assertThat(cacheRowCount()).isEqualTo(before + 1);
        assertThat(failReasonOf(KNOWN_ADDRESS)).isNull(); // 성공은 fail_rsn 이 비어 있다
    }

    @Test
    @DisplayName("DB 캐시만 있어도(다음 실행) 외부 호출 없이 좌표가 나온다")
    void readsCacheWrittenByPreviousRun() {
        cacheRepository.save(KNOWN_ADDRESS, GeocodeOutcome.found(JEJU_POINT));
        RecordingClient client = new RecordingClient(Map.of());

        GeocodeOutcome outcome = newGeocoder(client).geocode(KNOWN_ADDRESS);

        assertThat(outcome.isFound()).isTrue();
        assertThat(outcome.point().lat()).isEqualByComparingTo(JEJU_POINT.lat());
        assertThat(outcome.point().lot()).isEqualByComparingTo(JEJU_POINT.lot());
        assertThat(client.queried).isEmpty();
    }

    @Test
    @DisplayName("NOT_FOUND 는 실패 확정으로 캐싱한다 — 두 번째 조회는 외부 호출 0회")
    void cachesNotFoundAsPermanentFailure() {
        long before = cacheRowCount();
        RecordingClient client = new RecordingClient(Map.of()); // 무엇을 물어도 못 찾음
        CompositeGeocoder geocoder = newGeocoder(client);

        assertThat(geocoder.geocode(UNKNOWN_ADDRESS).status())
                .isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
        int callsAfterFirst = client.queried.size();
        assertThat(geocoder.geocode(UNKNOWN_ADDRESS).status())
                .isEqualTo(GeocodeOutcome.Status.NOT_FOUND);

        // 1회차: 원주소 + 부번제거 주소 2번. 2회차: 0번.
        assertThat(callsAfterFirst).isEqualTo(2);
        assertThat(client.queried).hasSize(2);
        assertThat(cacheRowCount()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject(
                "SELECT found FROM batch.tb_geocode_cache WHERE addr = ?", Boolean.class,
                UNKNOWN_ADDRESS)).isFalse();
        assertThat(failReasonOf(UNKNOWN_ADDRESS)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("TRANSIENT 는 DB 에 fail_rsn='TRANSIENT' 로만 남고 인메모리 캐시에는 안 들어간다")
    void recordsTransientInDbButNotInMemory() {
        long before = cacheRowCount();
        RecordingClient client = new RecordingClient(Map.of());
        client.transientAddresses = Set.of(KNOWN_ADDRESS);
        CompositeGeocoder geocoder = newGeocoder(client);

        assertThat(geocoder.geocode(KNOWN_ADDRESS).status())
                .isEqualTo(GeocodeOutcome.Status.TRANSIENT);

        // 관측용 행은 남는다 — "언제 어느 주소가 장애에 걸렸는지"를 사후 조회할 수 있어야 한다.
        assertThat(cacheRowCount()).isEqualTo(before + 1);
        assertThat(failReasonOf(KNOWN_ADDRESS)).isEqualTo("TRANSIENT");
        assertThat(jdbc.queryForObject(
                "SELECT found FROM batch.tb_geocode_cache WHERE addr = ?", Boolean.class,
                KNOWN_ADDRESS)).isFalse();

        // 인메모리에 들어갔다면 장애가 회복돼도 같은 실행 안에서 계속 실패로 읽힌다.
        client.transientAddresses = Set.of();
        client.answers = Map.of(KNOWN_ADDRESS, JEJU_POINT);
        assertThat(geocoder.geocode(KNOWN_ADDRESS).coordinate()).contains(JEJU_POINT);
        assertThat(geocoder.cacheHits()).isZero();
    }

    @Test
    @DisplayName("TRANSIENT 행은 캐시 히트가 아니다 — 다음 실행이 다시 조회한다")
    void doesNotTreatTransientRowAsCacheHit() {
        cacheRepository.save(KNOWN_ADDRESS, GeocodeOutcome.transientFailure());
        RecordingClient client = new RecordingClient(Map.of(KNOWN_ADDRESS, JEJU_POINT));
        CompositeGeocoder geocoder = newGeocoder(client);

        GeocodeOutcome outcome = geocoder.geocode(KNOWN_ADDRESS);

        // 캐시에 행이 있어도 재조회해서 좌표를 얻어야 한다(D8 개정의 핵심).
        assertThat(outcome.coordinate()).contains(JEJU_POINT);
        assertThat(client.queried).containsExactly(KNOWN_ADDRESS);
        assertThat(geocoder.cacheHits()).isZero();
        assertThat(geocoder.apiLookups()).isEqualTo(1);
        // 회복된 결과가 그 행을 덮어쓴다.
        assertThat(failReasonOf(KNOWN_ADDRESS)).isNull();
    }

    @Test
    @DisplayName("NOT_FOUND 행은 종전대로 실패 확정 히트 — 재조회하지 않는다")
    void treatsNotFoundRowAsPermanentHit() {
        cacheRepository.save(UNKNOWN_ADDRESS, GeocodeOutcome.notFound());
        RecordingClient client = new RecordingClient(Map.of(UNKNOWN_ADDRESS, JEJU_POINT));
        CompositeGeocoder geocoder = newGeocoder(client);

        GeocodeOutcome outcome = geocoder.geocode(UNKNOWN_ADDRESS);

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
        assertThat(client.queried).isEmpty();
        assertThat(geocoder.cacheHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("fail_rsn 이 NULL 인 옛 실패 행은 NOT_FOUND 로 읽는다(하위호환)")
    void readsLegacyFailureRowAsNotFound() {
        jdbc.update("INSERT INTO batch.tb_geocode_cache (addr, found) VALUES (?, false)",
                UNKNOWN_ADDRESS);
        RecordingClient client = new RecordingClient(Map.of());

        CompositeGeocoder geocoder = newGeocoder(client);
        assertThat(geocoder.geocode(UNKNOWN_ADDRESS).status())
                .isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
        assertThat(client.queried).isEmpty();
    }

    @Test
    @DisplayName("어느 단계든 TRANSIENT 면 전체 TRANSIENT — 없는 주소로 굳히지 않는다")
    void keepsTransientAcrossFallbackChain() {
        RecordingClient client = new RecordingClient(Map.of());
        client.transientAddresses = Set.of("T4162 하귀1리 258"); // 4단만 장애
        CompositeGeocoder geocoder = newGeocoder(client);

        GeocodeOutcome outcome = geocoder.geocode("T4162 하귀1리 258-1");

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.TRANSIENT);
        assertThat(geocoder.transientFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 3단(장소 검색)이 TRANSIENT 면 나머지가 NOT_FOUND 여도 전체 TRANSIENT")
    void keepsTransientFromPlaceSearchStage() {
        RecordingClient vworld = new RecordingClient(Map.of());
        RecordingClient place = new RecordingClient(Map.of());
        place.transientAll = true;
        CompositeGeocoder geocoder = newGeocoder(vworld, place);

        GeocodeOutcome outcome = geocoder.geocode(UNKNOWN_ADDRESS);

        // 장소 검색을 못 물어본 이상 "그 주소는 없다"고 단정할 수 없다 — 굳히면 복구가 불가능해진다.
        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.TRANSIENT);
        assertThat(geocoder.transientFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 3단 — 주소DB 가 못 찾으면 장소(POI) 검색으로 넘어가고, 성공하면 4단은 부르지 않는다")
    void recoversViaPlaceSearchBeforeStrippingSubLot() {
        RecordingClient vworld = new RecordingClient(Map.of()); // 지번·도로명·부번제거 전부 실패
        RecordingClient place = new RecordingClient(Map.of(UNKNOWN_ADDRESS, JEJU_POINT));
        CompositeGeocoder geocoder = newGeocoder(vworld, place);

        GeocodeOutcome outcome = geocoder.geocode(UNKNOWN_ADDRESS);

        assertThat(outcome.coordinate()).contains(JEJU_POINT);
        assertThat(place.queried).containsExactly(UNKNOWN_ADDRESS);
        // 🔴 순서가 핵심이다 — 장소 검색이 성공했으면 부번제거(정밀도 낮음)까지 가면 안 된다.
        assertThat(vworld.queried).containsExactly(UNKNOWN_ADDRESS);
        assertThat(geocoder.placeRecoveries()).isEqualTo(1);
        assertThat(geocoder.strippedRecoveries()).isZero();
    }

    @Test
    @DisplayName("4단 폴백 — 지번·도로명·장소검색이 없으면 부번을 떼고 다시 묻는다")
    void fallsBackToMainLot() {
        RecordingClient client = new RecordingClient(
                Map.of("T4162 하귀1리 258", JEJU_POINT));
        CompositeGeocoder geocoder = newGeocoder(client);

        GeocodeOutcome outcome = geocoder.geocode("T4162 하귀1리 258-1");

        assertThat(outcome.coordinate()).contains(JEJU_POINT);
        assertThat(client.queried).containsExactly(
                "T4162 하귀1리 258-1",
                "T4162 하귀1리 258");
        assertThat(geocoder.strippedRecoveries()).isEqualTo(1);
        assertThat(geocoder.placeRecoveries()).isZero();
    }

    @Test
    @DisplayName("부번이 없는 주소는 4단을 건너뛴다 — 같은 질의를 두 번 하지 않는다")
    void skipsFourthStageWhenNothingToStrip() {
        RecordingClient vworld = new RecordingClient(Map.of());
        RecordingClient place = new RecordingClient(Map.of());

        assertThat(newGeocoder(vworld, place).geocode(KNOWN_ADDRESS).coordinate()).isEmpty();

        // 주소 API 는 1회(1·2단)만, 장소 검색도 1회만 — 부번이 없으니 재조회는 없다.
        assertThat(vworld.queried).containsExactly(KNOWN_ADDRESS);
        assertThat(place.queried).containsExactly(KNOWN_ADDRESS);
    }

    @Test
    @DisplayName("🔵 실패 note — 4단 판정을 이어 붙인다(parcel/road/place/stripped)")
    void composesStageDetailForSkipNote() {
        // 실제 클라이언트가 채우는 판정을 흉내 낸다(각 클라이언트 테스트가 그쪽을 본다).
        RecordingClient vworld = new RecordingClient(Map.of());
        vworld.failureDetail = "parcel=NOT_FOUND, road=NOT_FOUND";
        RecordingClient place = new RecordingClient(Map.of());
        place.failureDetail = "place=NOT_FOUND";

        GeocodeOutcome outcome = newGeocoder(vworld, place).geocode(UNKNOWN_ADDRESS);

        // GEOCODE_FAILED note 형식 그대로다.
        assertThat(outcome.detail()).isEqualTo(
                "parcel=NOT_FOUND, road=NOT_FOUND, place=NOT_FOUND, "
                        + "stripped(T4162 상도리 3204)=NOT_FOUND");
    }

    @Test
    @DisplayName("🔵 장소 검색이 주소 불일치로 버렸으면 그 사유가 note 에 남는다")
    void keepsPlaceMismatchReasonInDetail() {
        RecordingClient vworld = new RecordingClient(Map.of());
        vworld.failureDetail = "parcel=NOT_FOUND, road=NOT_FOUND";
        RecordingClient place = new RecordingClient(Map.of());
        place.failureDetail = "place=ADDR_MISMATCH(제주특별자치도 제주시 연동 312-1)";

        GeocodeOutcome outcome = newGeocoder(vworld, place).geocode(KNOWN_ADDRESS);

        // "POI 가 아예 없었다"와 "엉뚱한 POI 라 버렸다"는 사후 진단이 완전히 다르다.
        assertThat(outcome.detail()).isEqualTo(
                "parcel=NOT_FOUND, road=NOT_FOUND, "
                        + "place=ADDR_MISMATCH(제주특별자치도 제주시 연동 312-1), stripped=(해당없음)");
    }

    @Test
    @DisplayName("🔵 부번이 없어 4단을 건너뛰면 note 에 stripped=(해당없음) 으로 남는다")
    void marksSkippedFourthStageInDetail() {
        RecordingClient vworld = new RecordingClient(Map.of());
        vworld.failureDetail = "parcel=NOT_FOUND, road=NOT_FOUND";
        RecordingClient place = new RecordingClient(Map.of());
        place.failureDetail = "place=NOT_FOUND";

        GeocodeOutcome outcome = newGeocoder(vworld, place).geocode(KNOWN_ADDRESS); // 부번 없는 주소

        // "부번제거를 시도조차 안 한 건"과 "시도했는데도 실패한 건"을 사후에 구분할 수 있어야 한다.
        assertThat(outcome.detail()).isEqualTo(
                "parcel=NOT_FOUND, road=NOT_FOUND, place=NOT_FOUND, stripped=(해당없음)");
    }

    @Test
    @DisplayName("🔵 DB 캐시 히트로 되살린 실패도 note 가 비지 않는다")
    void keepsDetailForCachedFailure() {
        cacheRepository.save(UNKNOWN_ADDRESS, GeocodeOutcome.notFound()); // 캐시엔 단계 내역이 없다
        RecordingClient client = new RecordingClient(Map.of());

        GeocodeOutcome outcome = newGeocoder(client).geocode(UNKNOWN_ADDRESS);

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
        assertThat(outcome.detail()).isEqualTo(CompositeGeocoder.CACHED_FAILURE_DETAIL);
        assertThat(client.queried).isEmpty();
    }

    @Test
    @DisplayName("쿼터 초과는 전파되고 아무것도 캐싱하지 않는다 — 실패로 굳히면 안 된다(D9)")
    void doesNotCacheQuotaExceeded() {
        long before = cacheRowCount();
        RecordingClient client = new RecordingClient(Map.of());
        client.quotaExceeded = true;
        CompositeGeocoder geocoder = newGeocoder(client);

        assertThatThrownBy(() -> geocoder.geocode(KNOWN_ADDRESS))
                .isInstanceOf(GeocodeQuotaExceededException.class);

        assertThat(cacheRowCount()).isEqualTo(before);
        // 인메모리 캐시도 오염되지 않아야 한다 — 쿼터 회복 후 재실행 시 다시 물어봐야 한다.
        client.quotaExceeded = false;
        client.answers = Map.of(KNOWN_ADDRESS, JEJU_POINT);
        assertThat(geocoder.geocode(KNOWN_ADDRESS).coordinate()).contains(JEJU_POINT);
    }

    @Test
    @DisplayName("연속 TRANSIENT 가 임계값에 도달해도 Job 을 끊지 않는다 — 경보만 남기고 계속 간다(D9 2026-08-05 재확인)")
    void doesNotStopJobOnConsecutiveTransientOutage() {
        RecordingClient client = new RecordingClient(Map.of());
        client.transientAll = true;
        CompositeGeocoder geocoder =
                new CompositeGeocoder(client, new RecordingClient(Map.of()), cacheRepository, 0L, 3);

        // 임계값(3)을 넘겨도 예외가 나오지 않아야 한다. 산발적 장애로 배치가 죽으면 운영이 번거롭다는
        // 판단이며, 그 행들은 스킵 기록이 없어 재실행하면 그대로 복구된다.
        for (int i = 1; i <= 5; i++) {
            assertThat(geocoder.geocode("T4162 주소" + i).isTransient()).isTrue();
        }
        assertThat(geocoder.consecutiveTransients()).isEqualTo(5);
        assertThat(geocoder.transientFailures()).isEqualTo(5);
    }

    @Test
    @DisplayName("성공이 한 번 나오면 연속 카운터가 0 으로 리셋된다 — 산발적 장애로는 안 끊는다")
    void resetsStreakOnSuccessfulLookup() {
        RecordingClient client = new RecordingClient(Map.of(KNOWN_ADDRESS, JEJU_POINT));
        client.transientAddresses = Set.of("T4162 주소1", "T4162 주소2", "T4162 주소3", "T4162 주소4");
        CompositeGeocoder geocoder =
                new CompositeGeocoder(client, new RecordingClient(Map.of()), cacheRepository, 0L, 3);

        assertThat(geocoder.geocode("T4162 주소1").isTransient()).isTrue();
        assertThat(geocoder.geocode("T4162 주소2").isTransient()).isTrue();
        assertThat(geocoder.consecutiveTransients()).isEqualTo(2);

        // 성공 1회 → 제공자가 살아 있다는 증거이므로 카운터를 되돌린다.
        assertThat(geocoder.geocode(KNOWN_ADDRESS).isFound()).isTrue();
        assertThat(geocoder.consecutiveTransients()).isZero();

        // 임계값(3)만큼 다시 쌓기 전까지는 끊기지 않는다.
        assertThat(geocoder.geocode("T4162 주소3").isTransient()).isTrue();
        assertThat(geocoder.geocode("T4162 주소4").isTransient()).isTrue();
        assertThat(geocoder.consecutiveTransients()).isEqualTo(2);
    }

    @Test
    @DisplayName("NOT_FOUND 응답도 카운터를 리셋한다 — 제공자가 살아 있다는 증거다")
    void resetsStreakOnNotFoundResponse() {
        RecordingClient client = new RecordingClient(Map.of());
        client.transientAddresses = Set.of("T4162 주소1", "T4162 주소2");
        CompositeGeocoder geocoder =
                new CompositeGeocoder(client, new RecordingClient(Map.of()), cacheRepository, 0L, 3);

        assertThat(geocoder.geocode("T4162 주소1").isTransient()).isTrue();
        assertThat(geocoder.geocode("T4162 주소2").isTransient()).isTrue();
        assertThat(geocoder.geocode(UNKNOWN_ADDRESS).status())
                .isEqualTo(GeocodeOutcome.Status.NOT_FOUND);

        assertThat(geocoder.consecutiveTransients()).isZero();
    }

    @Test
    @DisplayName("rate-limit 은 실제로 API 를 부른 뒤에만 — 캐시 히트는 기다리지 않는다")
    void waitsOnlyAfterRealApiCall() {
        RecordingClient client = new RecordingClient(Map.of(KNOWN_ADDRESS, JEJU_POINT));
        CompositeGeocoder geocoder = new CompositeGeocoder(
                client, new RecordingClient(Map.of()), cacheRepository, 300L, NO_OUTAGE_CUTOFF);

        long apiCallMs = elapsedMs(() -> geocoder.geocode(KNOWN_ADDRESS));
        long cacheHitMs = elapsedMs(() -> geocoder.geocode(KNOWN_ADDRESS));

        assertThat(apiCallMs).isGreaterThanOrEqualTo(300L);
        assertThat(cacheHitMs).isLessThan(300L);
    }

    @Test
    @DisplayName("빈 주소는 외부 호출도 캐싱도 하지 않는다")
    void ignoresBlankAddress() {
        long before = cacheRowCount();
        RecordingClient client = new RecordingClient(Map.of());

        assertThat(newGeocoder(client).geocode("   ").coordinate()).isEmpty();

        assertThat(client.queried).isEmpty();
        assertThat(cacheRowCount()).isEqualTo(before);
    }

    /**
     * rate-limit 0 — 대기 규칙 자체는 프로덕션 설정(100ms)이고 테스트에서 기다릴 이유가 없다.
     * 3단(장소 검색)은 <b>아무것도 못 찾는 스텁</b>이라 기존 단계 검증에 끼어들지 않는다.
     */
    private CompositeGeocoder newGeocoder(GeocodingClient client) {
        return newGeocoder(client, new RecordingClient(Map.of()));
    }

    private CompositeGeocoder newGeocoder(GeocodingClient vworld, GeocodingClient place) {
        return new CompositeGeocoder(vworld, place, cacheRepository, 0L, NO_OUTAGE_CUTOFF);
    }

    /** 시간을 잴 동작. 시큐어코딩 7.2 — Runnable.run() 직접 호출(스레드 오용 패턴) 대신 전용 인터페이스를 쓴다. */
    @FunctionalInterface
    private interface TimedAction {
        void execute();
    }

    private static long elapsedMs(TimedAction action) {
        long startedAt = System.nanoTime();
        action.execute();
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private long cacheRowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM batch.tb_geocode_cache", Long.class);
    }

    private String failReasonOf(String address) {
        return jdbc.queryForObject(
                "SELECT fail_rsn FROM batch.tb_geocode_cache WHERE addr = ?", String.class,
                address);
    }

    /** 질의된 주소를 순서대로 기록하는 가짜 제공자. */
    private static final class RecordingClient implements GeocodingClient {

        private final List<String> queried = new CopyOnWriteArrayList<>();
        private Map<String, Coordinate> answers;
        private boolean quotaExceeded;
        /** 이 주소들은 일시 장애로 답한다(재시도를 다 소진한 상태를 흉내). */
        private Set<String> transientAddresses = Set.of();
        /** 제공자가 통째로 죽은 상태. */
        private boolean transientAll;
        /** 실패 응답에 실을 타입별 판정 — 실제 클라이언트({@code parcel=..., road=...})를 흉내 낸다. */
        private String failureDetail;

        private RecordingClient(Map<String, Coordinate> answers) {
            this.answers = answers;
        }

        @Override
        public GeocodeOutcome geocode(String address) {
            if (quotaExceeded) {
                throw new GeocodeQuotaExceededException("OVER_REQUEST_LIMIT (stub)");
            }
            queried.add(address);
            if (transientAll || transientAddresses.contains(address)) {
                return GeocodeOutcome.transientFailure(failureDetail);
            }
            Coordinate point = answers.get(address);
            return point == null
                    ? GeocodeOutcome.notFound(failureDetail)
                    : GeocodeOutcome.found(point);
        }
    }
}
