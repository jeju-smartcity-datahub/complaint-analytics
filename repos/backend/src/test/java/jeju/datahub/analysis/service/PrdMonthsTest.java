package jeju.datahub.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PrdMonths} 단위테스트 — 표 8행과 1:1 이다.
 *
 * <p>🔴 스프링 컨텍스트를 띄우지 않는다 — 날짜만 넣는 순수 함수다.
 */
class PrdMonthsTest {

    private static BigDecimal of(String bgng, String end) {
        return PrdMonths.of(LocalDate.parse(bgng), LocalDate.parse(end));
    }

    @Test
    @DisplayName("1. 온전한 8개월(01-01 ~ 08-31) = 8 — 종전 정수와 같다")
    void fullEightMonths() {
        BigDecimal m = of("2026-01-01", "2026-08-31");

        assertThat(m).isEqualByComparingTo("8");
        assertThat(m.toString()).isEqualTo("8");
    }

    @Test
    @DisplayName("2. 09-26 ~ 10-26 = 5/30 + 26/31 = 1.005376")
    void partialTwoMonths() {
        assertThat(of("2026-09-26", "2026-10-26")).isEqualByComparingTo("1.005376");
    }

    @Test
    @DisplayName("3. 온전한 한 달(10-01 ~ 10-31) = 1")
    void fullOneMonth() {
        assertThat(of("2026-10-01", "2026-10-31")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("4. 1일 조회(10-26 ~ 10-26) = 1/31 = 0.032258 — 하한 없음")
    void singleDay() {
        assertThat(of("2026-10-26", "2026-10-26")).isEqualByComparingTo("0.032258");
    }

    @Test
    @DisplayName("5. 윤년 2월(2028-02-01 ~ 02-29) = 1")
    void leapFebruary() {
        assertThat(of("2028-02-01", "2028-02-29")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("6. 연도 경계(2025-12-15 ~ 2026-01-14) = 17/31 + 14/31 = 1")
    void yearBoundary() {
        assertThat(of("2025-12-15", "2026-01-14")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("7. 단속 창 20개월(2025-01-01 ~ 2026-08-31) = 20 — 2E+1 로 나오지 않는다")
    void twentyMonthsIsPlain() {
        BigDecimal m = of("2025-01-01", "2026-08-31");

        assertThat(m).isEqualByComparingTo("20");
        assertThat(m.scale()).isGreaterThanOrEqualTo(0);
        assertThat(m.toString()).isEqualTo("20");
    }

    @Test
    @DisplayName("8. end < bgng 이면 IllegalArgumentException")
    void reversedRangeThrows() {
        assertThatThrownBy(() -> of("2026-10-26", "2026-10-25"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
