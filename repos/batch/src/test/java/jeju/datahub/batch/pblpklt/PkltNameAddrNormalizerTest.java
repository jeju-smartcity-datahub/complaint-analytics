package jeju.datahub.batch.pblpklt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공용 정규화 회귀 (TSK-4254 Step 4) — 이용패턴 {@code PkltUtztnPkltIdResolverTest} 가 리졸버를 거쳐 확인하던
 * 정규화 케이스를 그대로 옮겨 <b>함수 단위</b>로 고정한다. 값은 그 테스트의 가짜 픽스처다.
 */
class PkltNameAddrNormalizerTest {

    @Test
    @DisplayName("주소: 읍면 접두를 제거한다 — '남원읍 남원리 180' → '남원리 180'")
    void addrStripsEupMyeonPrefix() {
        assertThat(PkltNameAddrNormalizer.normAddr("남원읍 남원리 180")).isEqualTo("남원리 180");
        assertThat(PkltNameAddrNormalizer.normAddr("남원리 180")).isEqualTo("남원리 180");
    }

    @Test
    @DisplayName("주소: 면 접두도 제거한다 · 동 주소는 그대로")
    void addrStripsMyeonOnly() {
        assertThat(PkltNameAddrNormalizer.normAddr("표선면 표선리 1")).isEqualTo("표선리 1");
        assertThat(PkltNameAddrNormalizer.normAddr("동홍동 395-2")).isEqualTo("동홍동 395-2");
    }

    @Test
    @DisplayName("🔴 주소: 다필지 주소를 첫 지번으로 자르지 않는다 — 원천·우리 표기가 다르면 다르게 남는다")
    void addrDoesNotTruncateMultiParcel() {
        assertThat(PkltNameAddrNormalizer.normAddr("서귀동 305-1, 300-19")).isEqualTo("서귀동 305-1, 300-19");
        assertThat(PkltNameAddrNormalizer.normAddr("서귀동 305-1, 300-19"))
                .isNotEqualTo(PkltNameAddrNormalizer.normAddr("서귀동 305-1, 서귀동 300-19"));
    }

    @Test
    @DisplayName("이름: 괄호 내용을 제거한다 — '천지공영주차빌딩(공작물)' → '천지공영주차빌딩'")
    void nameStripsParentheses() {
        assertThat(PkltNameAddrNormalizer.normName("천지공영주차빌딩(공작물)")).isEqualTo("천지공영주차빌딩");
        assertThat(PkltNameAddrNormalizer.normName("천지공영 주차빌딩")).isEqualTo("천지공영주차빌딩");
    }

    @Test
    @DisplayName("이름: 공백을 전부 제거한다 — '공영노외3 주차장' == '공영노외3주차장'")
    void nameStripsSpaces() {
        assertThat(PkltNameAddrNormalizer.normName("대정하모 공영노외3 주차장"))
                .isEqualTo(PkltNameAddrNormalizer.normName("대정하모 공영노외3주차장"))
                .isEqualTo("대정하모공영노외3주차장");
    }

    @Test
    @DisplayName("🔴 이름: greedy 괄호 함정 — 'A(가)B(나)' 는 'A' 가 아니라 'AB'")
    void nameParenthesesAreNotGreedy() {
        assertThat(PkltNameAddrNormalizer.normName("A(가)B(나)")).isEqualTo("AB");
    }

    @Test
    @DisplayName("null 은 null 이다")
    void nullStaysNull() {
        assertThat(PkltNameAddrNormalizer.normAddr(null)).isNull();
        assertThat(PkltNameAddrNormalizer.normName(null)).isNull();
    }
}
