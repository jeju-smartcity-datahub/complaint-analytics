package jeju.datahub.batch.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JejuBounds} — 제주 범위 정본 (TSK-4192 Step 1).
 *
 * <p>이 클래스의 실패는 전부 <b>조용하다</b> — 축 순서가 뒤집히면 장소검색이 전건
 * {@code NOT_FOUND} 가 되고 가드가 전건을 거부한다. 그래서 경계값과 형식 검증을 테스트로 못박는다.
 */
class JejuBoundsTest {

    /** 운영 기본값. */
    private static final String PROD = "126.10,33.10,127.00,34.05";

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("bbox 문자열을 그대로 파라미터로 내보낸다 — 경도,위도 순")
    void exposesBboxParamVerbatim() {
        assertThat(new JejuBounds(PROD).bboxParam()).isEqualTo(PROD);
        assertThat(new JejuBounds("  " + PROD + "  ").bboxParam()).isEqualTo(PROD);
    }

    @Test
    @DisplayName("🔴 추자면 최북단(34.0153 N)이 범위 안이다 — 상한을 33.6 으로 줄이면 여기서 깨진다")
    void includesChujaIsland() {
        JejuBounds bounds = new JejuBounds(PROD);

        // parking.tb_admdst 실측 최북단(추자면). 본섬만 보고 상한을 잡으면 추자도가 통째로 빠진다.
        assertThat(bounds.contains(bd("34.015303259602035"), bd("126.2471"))).isTrue();
        // 실제 추자면 POI(대서리 211-3) 좌표도 안에 든다.
        assertThat(bounds.contains(bd("33.96171431203832"), bd("126.29240280028456"))).isTrue();
    }

    @Test
    @DisplayName("제주 본섬·부속섬 좌표는 범위 안, 육지 좌표는 범위 밖")
    void separatesJejuFromMainland() {
        JejuBounds bounds = new JejuBounds(PROD);

        // 제주시청 일대 · 마라도 남단 · 우도
        assertThat(bounds.contains(bd("33.4996"), bd("126.5312"))).isTrue();
        assertThat(bounds.contains(bd("33.11371210348836"), bd("126.2678"))).isTrue();
        assertThat(bounds.contains(bd("33.4972"), bd("126.9684"))).isTrue();

        // 실제로 tb_pblpklt 에 적재돼 있던 육지 좌표들(315행 중 표본) — 전부 거부돼야 한다.
        assertThat(bounds.contains(bd("37.3390"), bd("126.9318"))).isFalse();  // 안산
        assertThat(bounds.contains(bd("35.6279"), bd("129.3516"))).isFalse();  // 울산
        assertThat(bounds.contains(bd("37.7445"), bd("128.8963"))).isFalse();  // 강릉
        assertThat(bounds.contains(bd("34.8954"), bd("127.8735"))).isFalse();  // 순천
    }

    @Test
    @DisplayName("경계값은 포함이다 — 배타로 두면 경계 근처가 탈락한다")
    void boundaryIsInclusive() {
        JejuBounds bounds = new JejuBounds(PROD);

        assertThat(bounds.contains(bd("33.10"), bd("126.10"))).isTrue();
        assertThat(bounds.contains(bd("34.05"), bd("127.00"))).isTrue();
        // 바로 밖은 거부
        assertThat(bounds.contains(bd("33.0999"), bd("126.10"))).isFalse();
        assertThat(bounds.contains(bd("34.0501"), bd("127.00"))).isFalse();
    }

    @Test
    @DisplayName("🔴 좌표가 null 이면 통과 — 일시 장애를 범위 위반으로 오분류하지 않는다")
    void nullCoordinatePasses() {
        JejuBounds bounds = new JejuBounds(PROD);

        // 좌표가 없는 것은 가드가 판정할 대상이 아니다(호출자가 TRANSIENT/NOT_FOUND 로 이미 분기한다).
        assertThat(bounds.contains(null, bd("126.5"))).isTrue();
        assertThat(bounds.contains(bd("33.5"), null)).isTrue();
        assertThat(bounds.contains(null, null)).isTrue();
    }

    @Test
    @DisplayName("설정이 비면 bbox 와 가드가 모두 꺼진다 — 되돌림 스위치")
    void emptyConfigDisablesEverything() {
        for (String off : new String[] {"", "   ", null}) {
            JejuBounds bounds = new JejuBounds(off);

            assertThat(bounds.enabled()).isFalse();
            assertThat(bounds.bboxParam()).isEmpty();
            // 육지 좌표까지 통과한다 — 종전 동작으로 정확히 되돌아간다.
            assertThat(bounds.contains(bd("37.5665"), bd("126.9780"))).isTrue();
        }
    }

    @Test
    @DisplayName("🔴 형식이 틀리면 기동을 죽인다 — 조용히 전건 실패하는 것보다 낫다")
    void rejectsMalformedConfig() {
        // 개수 부족/초과
        assertThatThrownBy(() -> new JejuBounds("126.10,33.10,127.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4개");
        // 숫자가 아님
        assertThatThrownBy(() -> new JejuBounds("126.10,33.10,127.00,삼십사"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("숫자");
        // 🔴 축 순서 뒤집힘(위도,경도) — 이게 이 검증의 핵심 대상이다.
        //    ⚠️ min<max 검사로는 못 잡는다: 33.10→34.05 도 126.10→127.00 도 증가라 통과한다.
        //       값이 있어야 할 대역(경도 124~132 / 위도 33~39)을 봐야 걸린다.
        //    걸러내지 않으면 장소검색이 전건 NOT_FOUND 가 되고 가드가 전건을 거부한다.
        assertThatThrownBy(() -> new JejuBounds("33.10,126.10,34.05,127.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("순서")
                .hasMessageContaining("경도(minx)");

        // min >= max (순서는 맞지만 값이 뒤바뀐 경우)
        assertThatThrownBy(() -> new JejuBounds("127.00,33.10,126.10,34.05"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min 이 max");
    }

    @Test
    @DisplayName("note 용 표기에 좌표와 적용 범위가 함께 남는다")
    void describeCarriesCoordinateAndRange() {
        String note = new JejuBounds(PROD).describe(bd("37.3390"), bd("126.9318"));

        assertThat(note).contains("37.3390").contains("126.9318").contains(PROD);
    }
}
