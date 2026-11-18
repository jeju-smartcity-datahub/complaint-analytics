package jeju.datahub.analysis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link InvestPrrtServiceImpl#utztnBgngYm} 단위테스트 (= A) — 이용률 집계 시작 달과 달 수를 고정한다.
 * 시작 달은 시작일이 1일일 때만 포함하고, 한 달 안 창이면 끝 달로 clamp 한다. 오늘이 1일이면 기본 창이
 * 13개 달이 되는 예외는 허용한다.
 */
class InvestPrrtUtztnYmTest {

    @ParameterizedTest(name = "{0} ~ {1} → {2} · {3}개월 ({4})")
    @CsvSource({
            "2025-11-12, 2026-11-12, 202512, 12, 기본 1년 창 — 시작 달 제외",
            "2026-08-12, 2026-11-12, 202609, 3, 3개월 창",
            "2026-11-01, 2026-11-30, 202611, 1, 1일 시작 — 그 달 포함",
            "2026-11-05, 2026-11-20, 202611, 1, 한 달 안 — 끝 달로 clamp",
            "2023-02-28, 2024-02-29, 202303, 12, 윤년 말일 — 2024-02-29 의 minusMonths(12)",
            "2025-11-01, 2026-11-01, 202511, 13, 오늘이 1일 — D1 A 의 허용 예외"
    })
    void utztnBgngYmAndMonthCount(LocalDate bgng, LocalDate end, String expYm, int expMm, String why) {
        YearMonth start = InvestPrrtServiceImpl.utztnBgngYm(bgng, end);

        assertThat(start).as(why).isEqualTo(YearMonth.parse(expYm.substring(0, 4) + "-" + expYm.substring(4)));
        assertThat(ChronoUnit.MONTHS.between(start, YearMonth.from(end)) + 1).as(why + " — 달 수").isEqualTo(expMm);
    }
}
