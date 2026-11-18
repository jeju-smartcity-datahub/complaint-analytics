package jeju.datahub.lifezone.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 혼잡도 산식의 <b>개월수</b> — 달력 월 수, 부분월은 일수 비례 (라운드2 가정).
 * 순수 계산이라 컨텍스트를 띄우지 않는다.
 */
@DisplayName("LifezoneCngstServiceImpl.monthsOf — 달력 월 수 (부분월 일수 비례)")
class LifezoneCngstMonthsTest {

    private static double months(String bgng, String end) {
        return LifezoneCngstServiceImpl.monthsOf(LocalDate.parse(bgng), LocalDate.parse(end));
    }

    @Test
    @DisplayName("2025-11-01~2026-06-30 = 8.0")
    void fullMonths() {
        assertThat(months("2025-11-01", "2026-06-30")).isCloseTo(8.0, within(1e-12));
    }

    @Test
    @DisplayName("01-15~01-31 = 17/31")
    void partialMonth() {
        assertThat(months("2026-01-15", "2026-01-31")).isCloseTo(17.0 / 31, within(1e-12));
    }

    @Test
    @DisplayName("월을 걸친 부분 구간 — 01-15~02-14 = 17/31 + 14/28 · 하루 = 1/그 달 일수")
    void spanningMonths() {
        assertThat(months("2026-01-15", "2026-02-14")).isCloseTo(17.0 / 31 + 14.0 / 28, within(1e-12));
        assertThat(months("2024-02-29", "2024-02-29")).isCloseTo(1.0 / 29, within(1e-12));
    }

    @Test
    @DisplayName("formatScore — anlsVl(점수×100) → 점수 소수 첫째 자리 · '.0' 은 지운다 · 소수점은 로캘과 무관하게 '.' · 100점 초과도 그대로 (2026-10-08 ⑪)")
    void formatScore() {
        assertThat(LifezoneCngstServiceImpl.formatScore(10000)).isEqualTo("100");
        assertThat(LifezoneCngstServiceImpl.formatScore(9200)).isEqualTo("92");
        assertThat(LifezoneCngstServiceImpl.formatScore(8891)).isEqualTo("88.9");
        assertThat(LifezoneCngstServiceImpl.formatScore(8536)).isEqualTo("85.4");
        assertThat(LifezoneCngstServiceImpl.formatScore(9999)).as("반올림으로 100 이 된다").isEqualTo("100");
        assertThat(LifezoneCngstServiceImpl.formatScore(1)).isEqualTo("0");
        // 🔁 ⑪ 상한 없음 — 100점을 넘는 값도 그대로 찍는다
        assertThat(LifezoneCngstServiceImpl.formatScore(15970)).isEqualTo("159.7");
        assertThat(LifezoneCngstServiceImpl.formatScore(10001)).as("100.01 → 첫째 자리 100").isEqualTo("100");
        assertThat(LifezoneCngstServiceImpl.formatScore(25000)).isEqualTo("250");
    }
}
