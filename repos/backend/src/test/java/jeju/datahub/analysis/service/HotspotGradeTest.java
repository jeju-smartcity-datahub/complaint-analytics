package jeju.datahub.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 순위 비율 등급 경계 (TSK-4321) — 기본 상위 5% 심각 · 5~10% 경고 · 10~15% 주의 · 그 밖 양호. */
class HotspotGradeTest {

    @ParameterizedTest(name = "{1}개 중 {0}위 → {2}")
    @CsvSource({
            "11, 208, SEVERE",
            "12, 208, WARN",
            "21, 208, WARN",
            "22, 208, CAUTION",
            "32, 208, CAUTION",
            "33, 208, NORMAL",
            "5,  100, SEVERE",
            "6,  100, WARN",
            "10, 100, WARN",
            "11, 100, CAUTION",
            "15, 100, CAUTION",
            "16, 100, NORMAL",
            "1,    1, SEVERE",
            "1,   10, SEVERE",
            "2,   10, CAUTION",
            "3,   10, NORMAL",
            "1,    0, NORMAL",
    })
    void gradeOfDefault(long rank, long total, HotspotGrade expected) {
        assertThat(HotspotGradeBndry.DEFAULT.gradeOf(rank, total)).isEqualTo(expected);
    }

    @Test
    @DisplayName("설정값 20/40/60 — 비율이 바뀌면 경계도 따라간다")
    void gradeOfConfigured() {
        HotspotGradeBndry bndry = new HotspotGradeBndry(20, 40, 60);
        assertThat(bndry.gradeOf(20, 100)).isEqualTo(HotspotGrade.SEVERE);
        assertThat(bndry.gradeOf(21, 100)).isEqualTo(HotspotGrade.WARN);
        assertThat(bndry.gradeOf(60, 100)).isEqualTo(HotspotGrade.CAUTION);
        assertThat(bndry.gradeOf(61, 100)).isEqualTo(HotspotGrade.NORMAL);
    }

    @Test
    @DisplayName("구 보고서 재판정 — 저장된 점수와 당시 임계값 80/60/40 으로 판정한다")
    void ofScoreKeepsLegacyThresholds() {
        assertThat(HotspotGrade.of(new BigDecimal("80"))).isEqualTo(HotspotGrade.SEVERE);
        assertThat(HotspotGrade.of(new BigDecimal("79.99"))).isEqualTo(HotspotGrade.WARN);
        assertThat(HotspotGrade.of(new BigDecimal("40"))).isEqualTo(HotspotGrade.CAUTION);
        assertThat(HotspotGrade.of(new BigDecimal("39.99"))).isEqualTo(HotspotGrade.NORMAL);
    }
}
