package jeju.datahub.batch.crackdown.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeAddress.AddressKind;
import jeju.datahub.batch.crackdown.geocode.CrackdownGeocodeAddress.GeocodeInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link CrackdownGeocodeAddress} 단위 테스트 — 유형 분류 · 접두 규칙(J-B·R-B) · 동명 행정동 제외.
 *
 * <p><b>실 DB 를 쓰지 않는다.</b> 행정동 이름 표는 테스트용 생성자로 심거나 mock {@link JdbcTemplate} 으로
 * 돌려준다. 주소 표본은 모두 <b>형태만 같은 합성 주소</b>다(실 단속 주소 원문을 넣지 않는다).
 */
class CrackdownGeocodeAddressTest {

    /** 테스트용 표 — {@code 월평동} 은 시가 둘이라 운영 로드에서도 빠지므로 여기에도 넣지 않는다. */
    private final CrackdownGeocodeAddress builder = new CrackdownGeocodeAddress(Map.of(
            "노형동", "제주시",
            "강정동", "서귀포시"));

    // ── 1. 계획서 Step 3 표 ────────────────────────────────────────────────────

    @Test
    @DisplayName("지번 — 표에 있는 동이면 '제주특별자치도 {시}' 접두를 붙인다(J-B)")
    void prefixesJibunWithSgg() {
        assertThat(builder.build("노형동 1234-5"))
                .isEqualTo(new GeocodeInput(AddressKind.JIBUN, "제주특별자치도 제주시 노형동 1234-5"));
    }

    @Test
    @DisplayName("지번 — 산 지번도 지번이며 서귀포시 접두가 붙는다")
    void prefixesMountainJibun() {
        assertThat(builder.build("강정동 산 12"))
                .isEqualTo(new GeocodeInput(AddressKind.JIBUN, "제주특별자치도 서귀포시 강정동 산 12"));
    }

    @Test
    @DisplayName("지번 — 표에 없는 동명은 원문 그대로(접두 없음)")
    void keepsUnknownEmdAsIs() {
        assertThat(builder.build("가상동 1"))
                .isEqualTo(new GeocodeInput(AddressKind.JIBUN, "가상동 1"));
    }

    @Test
    @DisplayName("🔴 지번 — 시가 둘인 동명(월평동)은 원문 그대로 — 틀린 시 접두는 D6 이 못 거른다")
    void keepsDuplicatedEmdAsIs() {
        assertThat(builder.build("월평동 123"))
                .isEqualTo(new GeocodeInput(AddressKind.JIBUN, "월평동 123"));
    }

    @Test
    @DisplayName("도로명 — '제주특별자치도' 접두를 붙인다(R-B)")
    void prefixesRoad() {
        assertThat(builder.build("연북로 12"))
                .isEqualTo(new GeocodeInput(AddressKind.ROAD, "제주특별자치도 연북로 12"));
    }

    @Test
    @DisplayName("장소명 — '제주 ' 접두(지오코딩은 하지 않는다 — Step 4 생략)")
    void prefixesPlace() {
        assertThat(builder.build("공항입구교차로"))
                .isEqualTo(new GeocodeInput(AddressKind.PLACE, "제주 공항입구교차로"));
    }

    @Test
    @DisplayName("앞뒤 공백은 걷어낸 뒤 같은 결과를 낸다 — 캐시 키가 공백으로 갈리지 않는다")
    void trimsBeforeBuilding() {
        assertThat(builder.build("  노형동 1234-5\t")).isEqualTo(builder.build("노형동 1234-5"));
        assertThat(builder.build(" 연북로 12 ")).isEqualTo(builder.build("연북로 12"));
        assertThat(builder.build(" 공항입구교차로 ")).isEqualTo(builder.build("공항입구교차로"));
    }

    @Test
    @DisplayName("null 은 NPE — Reader 가 crdn_addr IS NOT NULL 을 보장한다")
    void rejectsNull() {
        assertThatThrownBy(() -> builder.build(null)).isInstanceOf(NullPointerException.class);
    }

    // ── 2. 운영 생성자 — 지연 로드 · 동명 제외 ─────────────────────────────────

    @Test
    @DisplayName("🔴 운영 생성자 — 생성 시 DB 를 읽지 않고, 첫 지번에서 1회만 읽으며 동명(월평동)을 뺀다")
    void loadsLazilyOnceAndExcludesDuplicatedEmdNm() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(CrackdownGeocodeAddress.LOAD_SQL)).thenReturn(List.of(
                Map.of("emd_nm", "월평동", "sgg_nm", "제주시"),
                Map.of("emd_nm", "노형동", "sgg_nm", "제주시"),
                Map.of("emd_nm", "월평동", "sgg_nm", "서귀포시")));

        CrackdownGeocodeAddress prod = new CrackdownGeocodeAddress(jdbc);
        verifyNoInteractions(jdbc);                 // 기동(빈 생성)에서 DB 를 읽지 않는다

        // 도로명·장소명은 표가 필요 없으므로 로드를 일으키지 않는다.
        prod.build("연북로 12");
        prod.build("공항입구교차로");
        verifyNoInteractions(jdbc);

        assertThat(prod.build("월평동 123"))
                .isEqualTo(new GeocodeInput(AddressKind.JIBUN, "월평동 123"));
        assertThat(prod.build("노형동 1234-5"))
                .isEqualTo(new GeocodeInput(AddressKind.JIBUN, "제주특별자치도 제주시 노형동 1234-5"));
        verify(jdbc, times(1)).queryForList(CrackdownGeocodeAddress.LOAD_SQL);
    }

    // ── 3. 분류 교차검증 — §1-1 SQL 과 같은 규칙 ───────────────────────────────

    @Test
    @DisplayName("분류 교차검증 — 지번형 합성 주소는 JIBUN")
    void classifiesJibunSamples() {
        assertKind(AddressKind.JIBUN,
                "연동 123",
                "애월읍 곽지리 1-2",
                "한림읍 45",
                "조천면 7-10",
                "삼도2동 88",
                "노형동 산12-3",
                "가상리  9");
    }

    @Test
    @DisplayName("분류 교차검증 — 도로명형 합성 주소는 ROAD")
    void classifiesRoadSamples() {
        assertKind(AddressKind.ROAD,
                "중앙로 5",
                "연북로 12",
                "가상대로 300",
                "서광로32길 7",
                "일주동로 100",
                "가상길 3");
    }

    @Test
    @DisplayName("분류 교차검증 — 장소명형 합성 주소는 PLACE (숫자 없는 '로'·'숫자+동' 포함)")
    void classifiesPlaceSamples() {
        assertKind(AddressKind.PLACE,
                "○○아파트",
                "△△교차로",
                "□□초등학교 앞",
                "◇◇해수욕장 3층",
                "××2동",
                "☆☆로",
                "");
    }

    @Test
    @DisplayName("⚠️ 알려진 한계 — 시설명 끝의 '동산+숫자' 는 지번으로 분류된다(NOT_FOUND 스킵으로 끝나 안전)")
    void classifiesDongsanFacilityAsJibun() {
        assertKind(AddressKind.JIBUN, "△△공원동산 2");
    }

    private void assertKind(AddressKind expected, String... samples) {
        for (String sample : samples) {
            assertThat(builder.build(sample).kind()).as("주소 '%s'", sample).isEqualTo(expected);
        }
    }
}
