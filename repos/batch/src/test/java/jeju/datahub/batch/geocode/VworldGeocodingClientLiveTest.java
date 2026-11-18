package jeju.datahub.batch.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * <b>VWorld 실호출</b> 확인. {@code VWORLD_API_KEY} 환경변수가 있을 때만 돈다
 * (없으면 조용히 건너뛴다 — 키 없는 환경에서 빌드가 깨지지 않게).
 *
 * <pre>{@code
 * set -a; . ./.env; set +a          # 루트 .env
 * ./gradlew test --tests '*Geocod*'
 * }</pre>
 *
 * <p>⚠️ <b>예시 주소 {@code 제주특별자치도 제주시 삼도이동 574-1} 은 쓰지 않는다.</b>
 * 2026-08-04 실호출 결과 지번·도로명·부번제거 전부 {@code NOT_FOUND} 이고, 원천
 * {@code parking.tb_cvlcpt} 에도 그 주소는 없다(삼도이동 최다 신고 주소는 {@code 1264}).
 * 그래서 원천에 실재하는 {@code 삼도이동 1264}(218건) 로 "제주 범위 좌표가 나온다"를 확인한다.
 */
@EnabledIfEnvironmentVariable(named = "VWORLD_API_KEY", matches = ".+")
class VworldGeocodingClientLiveTest {

    /** 제주도 대략 범위 — 좌표가 엉뚱한 곳(경도/위도 뒤바뀜 등)에 찍히면 여기서 걸린다. */
    private static final BigDecimal LAT_MIN = new BigDecimal("33.1");
    private static final BigDecimal LAT_MAX = new BigDecimal("34.0");
    private static final BigDecimal LOT_MIN = new BigDecimal("126.1");
    private static final BigDecimal LOT_MAX = new BigDecimal("127.0");

    private final VworldGeocodingClient client = new VworldGeocodingClient(
            System.getenv("VWORLD_API_KEY"), "https://api.vworld.kr/req/address", 10_000, 4);

    @Test
    @DisplayName("원천에 실재하는 주소가 제주 범위 좌표로 변환된다")
    void geocodesRealJejuAddress() {
        GeocodeOutcome outcome = client.geocode("제주특별자치도 제주시 삼도이동 1264");

        assertThat(outcome.status()).isEqualTo(GeocodeOutcome.Status.FOUND);
        assertThat(outcome.point().lat()).isBetween(LAT_MIN, LAT_MAX);
        assertThat(outcome.point().lot()).isBetween(LOT_MIN, LOT_MAX);
    }

    /**
     * 이 주소가 <b>주소DB 로는 절대 안 나온다</b>는 사실이 D7 3단(장소 POI 검색) 신설의 근거다.
     * 여기서 {@code NOT_FOUND} 가 유지되는 한, 이 주소를 살리는 것은
     * {@link VworldPlaceSearchClientLiveTest} 가 확인하는 3단뿐이다.
     */
    @Test
    @DisplayName("VWorld 주소DB 미수록(상도리 3204-1) — 지번·도로명·부번제거 전부 NOT_FOUND (3단 신설 근거)")
    void vworldMissesHaenyeoMuseumAddress() {
        String address = "제주특별자치도 제주시 구좌읍 상도리 3204-1"; // 해녀박물관. 원천 24건
        String mainLotOnly = CompositeGeocoder.stripSubLot(address);

        // 1·2단(지번 → 도로명). TRANSIENT 가 아니라 NOT_FOUND 여야 스킵 확정 대상이 된다.
        assertThat(client.geocode(address).status()).isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
        // 4단(부번 제거) — 여기서도 안 나온다. 3단이 없으면 GEOCODE_FAILED 로 스킵된다.
        assertThat(mainLotOnly).isEqualTo("제주특별자치도 제주시 구좌읍 상도리 3204");
        assertThat(client.geocode(mainLotOnly).status()).isEqualTo(GeocodeOutcome.Status.NOT_FOUND);
    }
}
