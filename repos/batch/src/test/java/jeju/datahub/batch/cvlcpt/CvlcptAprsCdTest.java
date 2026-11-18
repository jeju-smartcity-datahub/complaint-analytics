package jeju.datahub.batch.cvlcpt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link CvlcptCodeMapping#aprsCd} — 원천 감정 원문 → {@code APRS_CD} (TSK-4316). */
class CvlcptAprsCdTest {

    @Test
    @DisplayName("🆕 현행 원천 표기 좋음/보통/나쁨 → POSITIVE/NEUTRAL/NEGATIVE")
    void currentSourceLabels() {
        assertThat(CvlcptCodeMapping.aprsCd("좋음")).isEqualTo("POSITIVE");
        assertThat(CvlcptCodeMapping.aprsCd("보통")).isEqualTo("NEUTRAL");
        assertThat(CvlcptCodeMapping.aprsCd(" 나쁨 ")).isEqualTo("NEGATIVE");
    }

    @Test
    @DisplayName("종전 표기 긍정/부정 · 코드 그대로도 받는다")
    void legacyLabelsAndCodes() {
        assertThat(CvlcptCodeMapping.aprsCd("긍정")).isEqualTo("POSITIVE");
        assertThat(CvlcptCodeMapping.aprsCd("부정")).isEqualTo("NEGATIVE");
        assertThat(CvlcptCodeMapping.aprsCd("NEUTRAL")).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("값 없음 · 모르는 값은 NULL — ETC 로 뭉개지 않는다")
    void unknownIsNull() {
        assertThat(CvlcptCodeMapping.aprsCd(null)).isNull();
        assertThat(CvlcptCodeMapping.aprsCd(" ")).isNull();
        assertThat(CvlcptCodeMapping.aprsCd("매우좋음")).isNull();
    }
}
