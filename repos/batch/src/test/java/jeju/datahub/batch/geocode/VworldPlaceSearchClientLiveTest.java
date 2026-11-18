package jeju.datahub.batch.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * <b>VWorld 장소(POI) 검색 실호출</b> 확인 — D7 3단이 존재하는 이유를 그대로 검증한다.
 * {@code VWORLD_API_KEY} 환경변수가 있을 때만 돈다(없으면 조용히 건너뛴다).
 *
 * <pre>{@code
 * set -a; . ./.env; set +a
 * ./gradlew test --tests '*PlaceSearch*'
 * }</pre>
 *
 * <p>핵심은 {@link #recoversAddressMissingFromAddressDatabase()} 다 —
 * {@code 제주특별자치도 제주시 구좌읍 상도리 3204-1}(해녀박물관 일대, 원천 24행)은 주소DB
 * ({@code /req/address})에서 지번·도로명·부번제거가 <b>전부 {@code NOT_FOUND}</b> 인데
 * ({@link VworldGeocodingClientLiveTest} 가 그걸 확인한다) 장소 검색에서는 좌표가 나온다.
 * 이 단계가 없으면 이 주소는 {@code GEOCODE_FAILED} 로 영구 스킵된다.
 */
@EnabledIfEnvironmentVariable(named = "VWORLD_API_KEY", matches = ".+")
class VworldPlaceSearchClientLiveTest {

    /** 제주도 대략 범위 — 좌표가 엉뚱한 곳(경도/위도 뒤바뀜 등)에 찍히면 여기서 걸린다. */
    private static final BigDecimal LAT_MIN = new BigDecimal("33.1");
    private static final BigDecimal LAT_MAX = new BigDecimal("34.0");
    private static final BigDecimal LOT_MIN = new BigDecimal("126.1");
    private static final BigDecimal LOT_MAX = new BigDecimal("127.0");

    /** 운영 기본값과 같은 제주 영역(경도,위도 = minx,miny,maxx,maxy). */
    private static final String JEJU_BBOX = "126.10,33.10,127.00,34.05";

    private final VworldPlaceSearchClient client = new VworldPlaceSearchClient(
            System.getenv("VWORLD_API_KEY"), "https://api.vworld.kr/req/search", 10_000, 4,
            new JejuBounds(JEJU_BBOX));

    /** bbox 를 끈 클라이언트 — "bbox 가 실제로 걸러낸 것"임을 대조로 증명하는 데만 쓴다. */
    private final VworldPlaceSearchClient noBboxClient = new VworldPlaceSearchClient(
            System.getenv("VWORLD_API_KEY"), "https://api.vworld.kr/req/search", 10_000, 4,
            new JejuBounds(""));

    @Test
    @DisplayName("🔴 주소DB 미수록 주소(상도리 3204-1)가 장소 검색으로 복구된다 — D7 3단의 존재 이유")
    void recoversAddressMissingFromAddressDatabase() {
        GeocodeOutcome outcome = client.geocode("제주특별자치도 제주시 구좌읍 상도리 3204-1");

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.FOUND);
        assertThat(outcome.point().lat()).isBetween(LAT_MIN, LAT_MAX);
        assertThat(outcome.point().lot()).isBetween(LOT_MIN, LOT_MAX);
    }

    @Test
    @DisplayName("없는 지번은 NOT_FOUND — fuzzy 매칭으로 아무거나 돌려주지 않는다")
    void returnsNotFoundForNonexistentLot() {
        assertThat(client.geocode("제주특별자치도 제주시 구좌읍 상도리 999999-999").status())
                .isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("지목이 붙어 오는 실응답도 채택된다(삼도이동 1264 → '…1264도')")
    void adoptsRealResponseWithTrailingLandCategory() {
        GeocodeOutcome outcome = client.geocode("제주특별자치도 제주시 삼도이동 1264");

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.FOUND);
        assertThat(outcome.point().lat()).isBetween(LAT_MIN, LAT_MAX);
        assertThat(outcome.point().lot()).isBetween(LOT_MIN, LOT_MAX);
    }

    @Test
    @DisplayName("🔴 본번(상도리 3204)으로 검색하면 부번 필지 POI 가 나오지만 검증에서 거부된다")
    void rejectsDifferentLotEvenWhenSearchReturnsIt() {
        // 실측: '상도리 3204' 검색 결과의 첫 건은 '상도리 3204-1' 의 POI 다. 접두 일치였다면
        // 채택돼 다른 필지 좌표가 찍힌다 — 정확 일치라 여기서 걸러진다.
        assertThat(client.geocode("제주특별자치도 제주시 구좌읍 상도리 3204").status())
                .isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("🔴 bbox 가 육지 POI 를 걸러낸다 — 같은 질의가 bbox 없이는 서울 좌표로 나온다")
    void bboxExcludesMainlandPoi() {
        String seoul = "서울특별시 중구 태평로1가 31"; // 서울시청 — 지번이 정확히 일치해 돌아온다

        // 대조: bbox 가 없으면 채택된다(지번이 맞으므로 matchesQuery 로는 못 막는다).
        GeocodeOutcome without = noBboxClient.geocode(seoul);
        assertThat(without.status()).isEqualTo(GeocodeOutcome.Status.FOUND);
        assertThat(without.point().lat()).isGreaterThan(new BigDecimal("37"));

        // bbox 를 걸면 검색 자체가 제주 밖을 보지 않는다.
        assertThat(client.geocode(seoul).status()).isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("🔴 추자면(최북단 33.96 N)이 bbox 안에 들어온다 — 위도 상한을 33.6 으로 줄이면 깨진다")
    void bboxIncludesChujaIsland() {
        // parking.tb_admdst 실측 최북단은 추자면 34.0153 N 이다. 본섬(≤ 33.57)만 보고 상한을
        // 잡으면 추자도 전역이 조용히 NOT_FOUND 가 된다 — 그래서 상한 34.05 를 이 테스트가 지킨다.
        GeocodeOutcome outcome = client.geocode("제주특별자치도 제주시 추자면 대서리 211-3");

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.FOUND);
        assertThat(outcome.point().lat()).isGreaterThan(new BigDecimal("33.9"));
        assertThat(outcome.point().lat()).isBetween(LAT_MIN, LAT_MAX);
        assertThat(outcome.point().lot()).isBetween(LOT_MIN, LOT_MAX);
    }
}
