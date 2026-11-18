package jeju.datahub.analysis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import jeju.datahub.analysis.service.impl.VworldCallSupport.LookupStatus;
import jeju.datahub.analysis.service.impl.VworldPlaceClient.NearestPlace;

/**
 * {@link VworldPlaceClient} 단위 테스트 (TSK-4032 Step 4).
 *
 * <p>HTTP 를 타지 않고 {@code parse()} 만 검증한다 — 이 클래스에서 판단이 일어나는 곳은
 * <b>카테고리 필터 · 거리 상한 · 재정렬</b> 셋이고, 전부 응답 문자열만 있으면 재현된다.
 * 실호출 검증은 별도(런타임)로 한다.
 *
 * <p>픽스처는 실측 응답에서 따왔다 — 아라일동 군집 중심(33.47749, 126.54188) 주변 POI 다.
 * 패키지가 {@code ...service.impl} 인 이유는 {@code parse()} 가 package-private 이기 때문이다.
 */
class VworldPlaceClientTest {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(VworldPlaceClientTest.class);

    private static final double CTR_LAT = 33.47749d;
    private static final double CTR_LOT = 126.54188d;

    /** 좌표는 자유롭게 주되 키는 있는 것으로 둔다 — parse() 는 키를 쓰지 않는다. */
    private final VworldPlaceClient client =
            new VworldPlaceClient("https://api.vworld.kr", "dummy-key", 2000, 2, 100, 150);

    /** 중심에서 대략 {@code meters} 만큼 북쪽에 있는 위도. */
    private static double latOffset(double meters) {
        return CTR_LAT + meters / 111_320.0d;
    }

    private static String item(String title, String category, double lat, double lot) {
        return """
                {"title":"%s","category":"%s","point":{"x":"%s","y":"%s"}}"""
                .formatted(title, category, lot, lat);
    }

    private static String okBody(String... items) {
        return """
                {"response":{"status":"OK","result":{"items":[%s]}}}"""
                .formatted(String.join(",", items));
    }

    @Test
    @DisplayName("거리순으로 오지 않는 응답을 중심좌표 기준으로 재정렬해 최근접을 고른다")
    void reordersByDistance() {
        String body = okBody(
                item("먼공원", "공원 > 근린공원", latOffset(120), CTR_LOT),
                item("가까운공원", "공원 > 어린이공원", latOffset(30), CTR_LOT),
                item("중간공원", "공원 > 소공원", latOffset(80), CTR_LOT));

        NearestPlace result = client.parse(body, CTR_LAT, CTR_LOT);

        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.title()).isEqualTo("가까운공원");
        assertThat(result.distM()).isBetween(28, 32);
    }

    @Test
    @DisplayName("150m 를 넘는 후보는 더 가까운 대안이 없어도 채택하지 않는다")
    void rejectsBeyondRadius() {
        NearestPlace result = client.parse(
                okBody(item("먼공원", "공원 > 근린공원", latOffset(400), CTR_LOT)), CTR_LAT, CTR_LOT);

        assertThat(result.status()).isEqualTo(LookupStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("건물·진출입시설은 더 가까워도 버리고 랜드마크를 고른다")
    void skipsNonLandmarkCategories() {
        String body = okBody(
                item("휴빌", "건물 > 주거용공동주택", latOffset(20), CTR_LOT),
                item("아라효가입구", "진출입시설", latOffset(50), CTR_LOT),
                item("1-110호공원", "공원 > 어린이공원", latOffset(130), CTR_LOT));

        NearestPlace result = client.parse(body, CTR_LAT, CTR_LOT);

        assertThat(result.title()).isEqualTo("1-110호공원");
    }

    @Test
    @DisplayName("랜드마크가 하나도 없으면 NOT_FOUND — 확정 실패라 재시도하지 않는다")
    void notFoundWhenNoLandmark() {
        String body = okBody(
                item("휴빌", "건물 > 주거용공동주택", latOffset(20), CTR_LOT),
                item("(주)그리메", "그외기타교육기관", latOffset(72), CTR_LOT));

        assertThat(client.parse(body, CTR_LAT, CTR_LOT).status()).isEqualTo(LookupStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("VWorld 가 NOT_FOUND 를 주면 그대로 확정 실패다")
    void propagatesNotFoundStatus() {
        NearestPlace result = client.parse("""
                {"response":{"status":"NOT_FOUND"}}""", CTR_LAT, CTR_LOT);

        assertThat(result.status()).isEqualTo(LookupStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("제목 키워드 보정: 거친 대분류에 들어간 랜드마크도 건진다 (신광초등학교 → 중앙행정기관)")
    void titleKeywordRescuesMisCategorized() {
        assertThat(VworldPlaceClient.isLandmark("신광초등학교", "중앙행정기관 > 교육부")).isTrue();
        assertThat(VworldPlaceClient.isLandmark("제주한라병원", "병원 > 종합병원")).isTrue();
        // 화이트리스트에 없는 대분류라도 제목이 걸리면 후보다.
        assertThat(VworldPlaceClient.isLandmark("제주버스터미널", "도로시설 > 버스터미널/정류장")).isTrue();
        assertThat(VworldPlaceClient.isLandmark("황제PC", "건물 > 제1종근린생활시설")).isFalse();
    }

    @Test
    @DisplayName("🔴 블랙리스트가 제목 키워드보다 우선한다 — 부속·경계 시설은 지명이 아니다")
    void blacklistBeatsTitleKeyword() {
        // 키워드('사거리')가 걸려도 진출입시설이면 탈락한다. 우회를 허용하면 '○○사거리입구' 가 구역명이 된다.
        assertThat(VworldPlaceClient.isLandmark("제원사거리입구", "진출입시설")).isFalse();
        assertThat(VworldPlaceClient.isLandmark("한라병원입구", "진출입시설")).isFalse();
        assertThat(VworldPlaceClient.isLandmark("연동이화아파트입구", "진출입시설")).isFalse();
        assertThat(VworldPlaceClient.isLandmark("제주대학교정문", "시설구역경계")).isFalse();
        // 같은 이름이라도 대분류가 블랙리스트가 아니면 그대로 후보다.
        assertThat(VworldPlaceClient.isLandmark("제원사거리", "교통시설")).isTrue();
    }

    @Test
    @DisplayName("대분류는 '>' 앞부분이며, 구분자가 없으면 문자열 전체다")
    void majorCategorySplit() {
        assertThat(VworldPlaceClient.majorCategory("공원 > 어린이공원")).isEqualTo("공원");
        assertThat(VworldPlaceClient.majorCategory("진출입시설")).isEqualTo("진출입시설");
        assertThat(VworldPlaceClient.majorCategory(null)).isEmpty();
    }

    /**
     * 실 API 호출 검증. 키가 있을 때만 돈다 — 키 없는 CI 에서 빌드를 깨지 않으면서
     * 로컬에서는 실호출 검증이 자동으로 켜진다(rule_batch-project.md 의 관례와 동일).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "VWORLD_API_KEY", matches = ".+")
    @DisplayName("[실호출] 아라일동 군집 중심에서 150m 이내 랜드마크를 찾는다")
    void liveCallFindsLandmark() {
        VworldPlaceClient live = new VworldPlaceClient(
                "https://api.vworld.kr", System.getenv("VWORLD_API_KEY"), 5000, 2, 100, 150);

        NearestPlace result = live.findNearestLandmark(
                new BigDecimal("33.47749"), new BigDecimal("126.54188"), "아라일동");

        assertThat(result.status()).isNotEqualTo(LookupStatus.TRANSIENT_FAILURE);
        if (result.status() == LookupStatus.FOUND) {
            assertThat(result.distM()).isLessThanOrEqualTo(150);
            LOG.info("[실호출] POI = {} ({}m)", result.title(), result.distM());   // 6.2(TSK-4336) — System.out 대신 로거
        }
    }
}
