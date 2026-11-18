package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneSupply;
import jeju.datahub.lifezone.service.LifezoneSupply.GrdBndry;

/**
 * 수급 부족률 등간격 등급·조회 일수·최소 월평균 기준 단위 테스트. (케이스 2-1·6)
 *
 * <p>DB·컨텍스트가 필요 없는 정적 계산만 본다 — {@code @SpringBootTest} 를 쓰지 않는다.
 *
 * <p>🔴 경계는 DB 재현(픽스처 INSERT)으로 만들지 않는다 — 여기서 식 자체를 표로 못박는다.
 *
 * <p>경계가 인자형({@link GrdBndry})이 됐다. 기본 경계 표({@link GrdBndry#DEFAULT} = 20/40/60/80 —
 * 설정 표를 못 읽을 때의 폴백값)와 임의 경계 1세트(10/30/50/90)를 함께 본다.
 */
@DisplayName("수급 부족률 등간격 등급 (LifezoneGrade.ofShrtRt · ofCode · LifezoneSupply.prdDaysOf)")
class LifezoneGradeShrtRtTest {

    /**
     * 경계 포함 — 부족률이 정확히 80·60·40·20% 이면 위 등급, 20% 미만은 낮음.
     * 🔴 {@code 19999/25000 = 0.79996} 은 응답 {@code shortageRatio}(소수 4자리)로는 {@code 0.8000} 이지만 경고다 —
     * 반올림 전 정수 비교({@code 민원×100 >= 경계×수요지수})라 한 칸 올라가지 않는다.
     */
    @ParameterizedTest(name = "민원 {0} / 수요지수 {1} → {2}")
    @CsvSource({
            "100,   100,   SEVERE",
            "80,    100,   SEVERE",
            "79,    100,   WARN",
            "19999, 25000, WARN",
            "7999,  10000, WARN",
            "60,    100,   WARN",
            "5999,  10000, CAUTION",
            "40,    100,   CAUTION",
            "3999,  10000, NORMAL",
            "20,    100,   NORMAL",
            "1999,  10000, LOW",
            "0,     100,   LOW",
            "5,     5,     SEVERE",
            "4,     5,     SEVERE",
            "3,     5,     WARN",
            "2,     5,     CAUTION",
            "1,     5,     NORMAL",
            "1,     6,     LOW",
    })
    void ofShrtRtBoundaries(int cvlcptCnt, int dmndIdx, LifezoneGrade expected) {
        assertThat(LifezoneGrade.ofShrtRt(cvlcptCnt, dmndIdx, GrdBndry.DEFAULT)).isEqualTo(expected);
    }

    @Test
    @DisplayName("ofShrtRt — 공급 0 격자(수요지수 = 민원)는 민원 수와 무관하게 항상 심각")
    void zeroSupplyIsAlwaysSevere() {
        for (int c = 1; c <= 1000; c++) {
            assertThat(LifezoneGrade.ofShrtRt(c, c, GrdBndry.DEFAULT)).as("민원 %d · 공급 0", c).isEqualTo(LifezoneGrade.SEVERE);
        }
    }

    @Test
    @DisplayName("ofShrtRt — 같은 부족률은 규모와 무관하게 같은 등급 (순위 백분위와의 차이)")
    void sameRatioSameGrade() {
        for (int k = 1; k <= 200; k++) {
            assertThat(LifezoneGrade.ofShrtRt(4 * k, 5 * k, GrdBndry.DEFAULT)).isEqualTo(LifezoneGrade.SEVERE);
            assertThat(LifezoneGrade.ofShrtRt(3 * k, 5 * k, GrdBndry.DEFAULT)).isEqualTo(LifezoneGrade.WARN);
            assertThat(LifezoneGrade.ofShrtRt(2 * k, 5 * k, GrdBndry.DEFAULT)).isEqualTo(LifezoneGrade.CAUTION);
            assertThat(LifezoneGrade.ofShrtRt(k, 5 * k, GrdBndry.DEFAULT)).isEqualTo(LifezoneGrade.NORMAL);
        }
    }

    @Test
    @DisplayName("폴백 기본 경계 — 80/60/40/20 (마이그레이션 91 bsc_stng_vl 과 짝)")
    void boundaryConstants() {
        assertThat(LifezoneSupply.GRADE_SHRT_RT_SEVERE).isEqualTo(80);
        assertThat(LifezoneSupply.GRADE_SHRT_RT_WARN).isEqualTo(60);
        assertThat(LifezoneSupply.GRADE_SHRT_RT_CAUTION).isEqualTo(40);
        assertThat(LifezoneSupply.GRADE_SHRT_RT_NORMAL).isEqualTo(20);
        assertThat(GrdBndry.DEFAULT.toList()).as("설정 표 BNDRY1~4 순서(오름차순)").containsExactly(20, 40, 60, 80);
        assertThat(GrdBndry.DEFAULT.minOf(LifezoneGrade.SEVERE)).isEqualTo(80);
        assertThat(GrdBndry.DEFAULT.minOf(LifezoneGrade.LOW)).as("낮음 하한은 0").isZero();
    }

    /**
     * 임의 경계(보통 10 · 주의 30 · 경고 50 · 심각 90)에서도 같은 규칙(아래 경계 포함 · 정수 비교)이다.
     * 기본 경계로는 심각인 {@code 80/100} 이 여기서는 경고다 — 경계가 정말 인자에서 온다는 증거.
     */
    @ParameterizedTest(name = "경계 10/30/50/90 · 민원 {0} / 수요지수 {1} → {2}")
    @CsvSource({
            "100,   100,   SEVERE",
            "90,    100,   SEVERE",
            "89,    100,   WARN",
            "80,    100,   WARN",
            "50,    100,   WARN",
            "4999,  10000, CAUTION",
            "30,    100,   CAUTION",
            "2999,  10000, NORMAL",
            "10,    100,   NORMAL",
            "999,   10000, LOW",
            "0,     100,   LOW",
            "7,     7,     SEVERE",
    })
    void ofShrtRtCustomBoundaries(int cvlcptCnt, int dmndIdx, LifezoneGrade expected) {
        GrdBndry custom = new GrdBndry(10, 30, 50, 90);
        assertThat(LifezoneGrade.ofShrtRt(cvlcptCnt, dmndIdx, custom)).isEqualTo(expected);
    }

    @Test
    @DisplayName("GrdBndry — 1 ≤ BNDRY1 < BNDRY2 < BNDRY3 < BNDRY4 ≤ 99 가 아니면 IllegalArgumentException (서비스가 잡아 기본값 폴백)")
    void grdBndryRejectsInvalid() {
        assertThatThrownBy(() -> new GrdBndry(40, 20, 60, 80)).as("비단조").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrdBndry(20, 40, 40, 80)).as("같은 값(엄격 증가)").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrdBndry(0, 40, 60, 80)).as("하한 0").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrdBndry(20, 40, 60, 100)).as("상한 100 — 공급 0 이 심각이 아니게 된다")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrdBndry(1, 1, 1, 1)).as("coefficient 의 1.0 폴백 4개")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new GrdBndry(1, 2, 3, 99).toList()).containsExactly(1, 2, 3, 99);
    }

    @Test
    @DisplayName("ofShrtRt — 수요지수 0 이하면 IllegalArgumentException")
    void ofShrtRtRejectsNonPositiveDemand() {
        assertThatThrownBy(() -> LifezoneGrade.ofShrtRt(0, 0, GrdBndry.DEFAULT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LifezoneGrade.ofShrtRt(1, -1, GrdBndry.DEFAULT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ofCode — 대소문자·공백 관대 · 미지원·빈 값·null 은 null(호출부가 400)")
    void ofCode() {
        assertThat(LifezoneGrade.ofCode("SEVERE")).isEqualTo(LifezoneGrade.SEVERE);
        assertThat(LifezoneGrade.ofCode(" warn ")).isEqualTo(LifezoneGrade.WARN);
        assertThat(LifezoneGrade.ofCode("Caution")).isEqualTo(LifezoneGrade.CAUTION);
        assertThat(LifezoneGrade.ofCode("normal")).as("ofCode 는 5종 모두 받는다 — 리스트 대상 판정은 isListed()")
                .isEqualTo(LifezoneGrade.NORMAL);
        assertThat(LifezoneGrade.ofCode("LOW")).isEqualTo(LifezoneGrade.LOW);
        assertThat(LifezoneGrade.ofCode("Q1")).as("valueOf 처럼 예외를 던지면 500 이 된다").isNull();
        assertThat(LifezoneGrade.ofCode("")).isNull();
        assertThat(LifezoneGrade.ofCode("   ")).isNull();
        assertThat(LifezoneGrade.ofCode(null)).isNull();
    }

    @Test
    @DisplayName("isListed — 리스트·보고서 표 대상은 SEVERE·WARN·CAUTION 3종")
    void listedGrades() {
        assertThat(LifezoneGrade.SEVERE.isListed()).isTrue();
        assertThat(LifezoneGrade.WARN.isListed()).isTrue();
        assertThat(LifezoneGrade.CAUTION.isListed()).isTrue();
        assertThat(LifezoneGrade.NORMAL.isListed()).isFalse();
        assertThat(LifezoneGrade.LOW.isListed()).isFalse();
    }

    @ParameterizedTest(name = "{0} ~ {1} → {2}일")
    @CsvSource({
            "2026-09-01, 2026-09-30, 30",
            "2026-01-01, 2026-01-31, 31",
            "2028-02-01, 2028-02-29, 29",
            "2026-02-01, 2026-02-28, 28",
            "2026-09-01, 2026-09-01, 1",
            "2025-12-31, 2026-12-31, 366",
    })
    @DisplayName("prdDaysOf — 조회 일수 = 종료일 − 시작일 + 1 (양끝 포함 · 윤년 2월 29)")
    void prdDaysOf(LocalDate bgng, LocalDate end, int expected) {
        assertThat(LifezoneSupply.prdDaysOf(bgng, end)).isEqualTo(expected);
    }

    /**
     * 최소 월평균 기준 판정식 — {@code 민원 × 30 ≥ 5 × 일수}.
     *
     * <p>🔴 매퍼 {@code selectSupplyCells} 의 {@code HAVING count(*) * 30 >= #{minMnthAvgCvlcpt} * #{prdDays}}
     * 와 <b>같은 식</b>이다 — 한쪽만 바꾸지 않는다. 상수는 {@link LifezoneSupply#MNTH_DAYS}·
     * {@link LifezoneSupply#MIN_MNTH_AVG_CVLCPT} 와 짝이고 아래에서 값을 못박는다.
     */
    @ParameterizedTest(name = "{0}일 · 민원 {1}건 → {2}")
    @CsvSource({
            "30, 4, false",
            "30, 5, true",
            "31, 5, false",
            "31, 6, true",
            "29, 4, false",
            "29, 5, true",
            "366, 60, false",
            "366, 61, true",
    })
    @DisplayName("판정식 — 30일 4건 F·5건 T / 31일 5건 F·6건 T")
    void minimumMonthlyAverageRule(int prdDays, int cvlcptCnt, boolean expected) {
        assertThat(LifezoneSupply.MNTH_DAYS).isEqualTo(30);
        assertThat(LifezoneSupply.MIN_MNTH_AVG_CVLCPT).isEqualTo(5);
        boolean passes = (long) cvlcptCnt * 30 >= 5L * prdDays;
        assertThat(passes).isEqualTo(expected);
        assertThat((long) cvlcptCnt * LifezoneSupply.MNTH_DAYS
                >= (long) LifezoneSupply.MIN_MNTH_AVG_CVLCPT * prdDays)
                .as("서비스 상수로 계산해도 같다").isEqualTo(expected);
    }
}
