package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.batch.admdst.AdmDistResolver;
import jeju.datahub.batch.geocode.CompositeGeocoder;
import jeju.datahub.batch.geocode.Coordinate;
import jeju.datahub.batch.geocode.GeocodeOutcome;
import jeju.datahub.batch.geocode.JejuBounds;
import jeju.datahub.batch.geocode.VworldGeocodingClient;
import jeju.datahub.batch.geocode.VworldPlaceSearchClient;

/**
 * <b>접두 후보 체인 실호출 검증</b>.
 *
 * <p>{@code tb_pblpklt} 에 실제로 <b>육지 좌표로 적재돼 있던 주소 5건</b>을 골라, 접두를 붙인
 * 후보 체인이 <b>제주 좌표를 준다</b>는 것을 실호출로 확인한다. {@code VWORLD_API_KEY} 가 있을 때만 돈다.
 *
 * <pre>{@code
 * set -a; . ./.env; set +a
 * ./gradlew test --tests '*PblpkltGeocodePrefixLive*'
 * }</pre>
 *
 * <h2>🔴 이 테스트가 없으면 무엇을 놓치나</h2>
 * 단위 테스트는 <b>후보 문자열을 어떤 순서로 만드는지</b>만 확인한다(지오코딩은 mock). "그 문자열을
 * 실제로 VWorld 에 물으면 제주가 나오는가" 는 실호출만 답할 수 있다 — 그리고 이 작업의 근거가
 * 바로 그 실측이다. 접두를 잘못 조립하면(예: 공백 누락, 읍면동 위치 오류) 단위 테스트는 전부
 * 초록불인데 전량 재실행에서 수백 건이 조용히 미발견이 된다.
 *
 * <h2>왜 {@link CompositeGeocoder} 를 쓰지 않나</h2>
 * 그건 DB 캐시({@code batch.tb_geocode_cache})를 요구해 단위 테스트 범위를 넘는다. 대신 폴백
 * <b>순서를 여기서 그대로 재현</b>한다 — 주소 API(지번 → 도로명) → 장소검색. 실측상 이 5건은
 * 전부 주소 API 단계에서 끝난다({@code CompositeGeocoder} 의 4단(부번 제거)까지 가지 않는다).
 */
@EnabledIfEnvironmentVariable(named = "VWORLD_API_KEY", matches = ".+")
class PblpkltGeocodePrefixLiveTest {

    /** 운영 기본값과 같은 제주 범위. */
    private static final String JEJU_BBOX = "126.10,33.10,127.00,34.05";

    /** 운영 기본값과 같은 접두. */
    private static final String PREFIX = "제주특별자치도 ";

    private final JejuBounds bounds = new JejuBounds(JEJU_BBOX);

    private final VworldGeocodingClient addressClient = new VworldGeocodingClient(
            System.getenv("VWORLD_API_KEY"), "https://api.vworld.kr/req/address", 10_000, 4);

    private final VworldPlaceSearchClient placeClient = new VworldPlaceSearchClient(
            System.getenv("VWORLD_API_KEY"), "https://api.vworld.kr/req/search", 10_000, 4,
            new JejuBounds(JEJU_BBOX));

    /** 후보 조립만 쓰므로 협력자는 mock 이면 충분하다. */
    private final PblpkltGeocodeProcessor processor = new PblpkltGeocodeProcessor(
            mock(CompositeGeocoder.class), mock(AdmDistResolver.class), mock(JdbcTemplate.class),
            new JejuBounds(JEJU_BBOX), PREFIX, true);

    /**
     * 실제로 육지 좌표가 적재돼 있던 5건 (2026-09-29 로컬 DB 실측).
     * {@code {주소, emd_nm, 종전 좌표}} — 종전 좌표는 "무엇이 틀렸는지" 를 읽히게 남긴다.
     */
    private static List<String[]> mainlandCases() {
        return List.of(
                new String[] {"삼성로 41, 45", "일도이동", "제주시", "37.3390,126.9318 (안산)"},
                new String[] {"동대1길 19", "건입동", "제주시", "35.6279,129.3516 (울산)"},
                new String[] {"661-9", "건입동", "제주시", "37.7445,128.8963 (강릉)"},
                new String[] {"월정리 481, 480", "구좌읍", "제주시", "37.0881,127.4596 (평택)"},
                new String[] {"탑동로 47", "삼도이동", "제주시", "34.8954,127.8735 (순천)"});
    }

    @Test
    @DisplayName("🔴 접두 후보 체인이 육지로 찍혔던 5건을 전부 제주 좌표로 복구한다")
    void prefixChainRecoversMainlandCases() {
        for (String[] testCase : mainlandCases()) {
            String source = testCase[0];
            String emdNm = testCase[1];
            String sggNm = testCase[2];
            String before = testCase[3];

            Resolved resolved = resolveThroughChain(source, emdNm, sggNm);

            assertThat(resolved.point())
                    .as("접두 후보 체인이 좌표를 찾지 못했다. 주소=%s emd_nm=%s (종전 %s)",
                            source, emdNm, before)
                    .isNotNull();
            assertThat(bounds.contains(resolved.point().lat(), resolved.point().lot()))
                    .as("복구 좌표가 제주 범위 밖이다. 주소=%s → %s,%s (종전 %s)",
                            source, resolved.point().lat(), resolved.point().lot(), before)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("🔴 접두 없이 같은 주소를 물으면 육지가 나온다 — 접두가 원인이었음을 대조로 증명한다")
    void withoutPrefixTheSameAddressesLandOnMainland() {
        // 이 단정이 깨지면 원천 주소나 VWorld 주소DB 가 바뀐 것이다 — 그때는 접두의 근거를
        // 다시 실측해야 한다(테스트가 그 사실을 알려주는 역할도 한다).
        int mainlandHits = 0;
        for (String[] testCase : mainlandCases()) {
            Coordinate point = throughAddressApi(testCase[0]);
            if (point != null && !bounds.contains(point.lat(), point.lot())) {
                mainlandHits++;
            }
        }
        assertThat(mainlandHits)
                .as("접두 없는 원문 조회가 더 이상 육지를 주지 않는다 — 접두 근거를 재실측할 것")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("후보 1(시군구+읍면동)이 우선 채택된다 — 정밀도 높은 수단이 먼저다")
    void firstCandidateIsPreferredWhenItResolves() {
        // '661-9' + '건입동' → 후보 1 이 성공해야 한다(후보 2 는 읍면동이 없어 덜 정확하다).
        List<String> candidates = processor.geocodeCandidates("661-9", "건입동", "제주시");
        assertThat(candidates).first().isEqualTo("제주특별자치도 제주시 건입동 661-9");

        Coordinate first = throughAddressApi(candidates.get(0));
        assertThat(first).as("후보 1이 실패하면 이 케이스의 전제가 깨진다").isNotNull();
        assertThat(bounds.contains(first.lat(), first.lot())).isTrue();
    }

    // ── 폴백 순서 재현 (CompositeGeocoder 1~3단) ──────────────────────────────

    private record Resolved(String input, Coordinate point) {}

    /** 후보를 순서대로 시도하고 첫 '제주 범위 안' 좌표를 반환한다 — 프로세서 로직과 같은 규율. */
    private Resolved resolveThroughChain(String source, String emdNm, String sggNm) {
        for (String candidate : processor.geocodeCandidates(source, emdNm, sggNm)) {
            Coordinate point = throughAddressApi(candidate);
            if (point == null) {
                point = throughPlaceSearch(candidate);
            }
            if (point != null && bounds.contains(point.lat(), point.lot())) {
                return new Resolved(candidate, point);
            }
        }
        return new Resolved(null, null);
    }

    /** 주소 API 1·2단(지번 → 도로명). {@code null} 이면 미발견이거나 일시 장애다. */
    private Coordinate throughAddressApi(String address) {
        GeocodeOutcome outcome = addressClient.geocode(address);
        return outcome.isFound() ? outcome.point() : null;
    }

    /** 장소검색 3단. bbox 가 적용된 클라이언트다. */
    private Coordinate throughPlaceSearch(String address) {
        GeocodeOutcome outcome = placeClient.geocode(address);
        return outcome.isFound() ? outcome.point() : null;
    }

    /** 좌표 비교용(assertJ 메시지에 쓰려고 BigDecimal 을 그대로 노출한다). */
    @SuppressWarnings("unused")
    private static BigDecimal lat(Coordinate c) {
        return c.lat();
    }
}
