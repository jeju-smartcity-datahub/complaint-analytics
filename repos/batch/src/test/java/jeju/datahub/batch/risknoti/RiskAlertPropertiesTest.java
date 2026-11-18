package jeju.datahub.batch.risknoti;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 판정일 해석 규칙을 고정한다.
 *
 * <p>🔴 <b>설정 바인딩 테스트가 아니다</b> — {@link RiskAlertProperties} 는
 * {@code @ConfigurationProperties} 를 쓰지 않고 받을 키도 없다. 여기서 지키는 건
 * "판정일을 누가 어떻게 정하는가" 하나다.
 */
class RiskAlertPropertiesTest {

    /** UTC 로는 2026-01-01 15:30, KST 로는 2026-01-02 00:30 — 두 존의 날짜가 갈리는 순간. */
    private static final Clock CROSS_MIDNIGHT_KST =
            Clock.fixed(Instant.parse("2026-01-01T15:30:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("targetDate 를 주면 그 값을 그대로 쓴다 — 시계를 보지 않는다")
    void usesGivenTargetDate() {
        LocalDate given = LocalDate.of(2025, 12, 31);

        assertThat(RiskAlertProperties.resolveTargetDate(given, CROSS_MIDNIGHT_KST))
                .isEqualTo(given);
    }

    @Test
    @DisplayName("🔴 targetDate 가 없으면 Asia/Seoul 기준 '어제' 다 — UTC 로 계산하면 하루가 밀린다")
    void fallsBackToSeoulYesterday() {
        // KST 로 2026-01-02 00:30 이므로 어제 = 2026-01-01.
        // UTC 로 구현했다면 2026-01-01 15:30 이라 어제 = 2025-12-31 이 나와 FAIL 한다.
        // ⚠️ 기대값을 LocalDate.now(SEOUL) 로 만들면 KST 개발 머신에서 UTC 구현도 통과해
        //    단언이 동어반복이 된다 — 그래서 고정 Clock 을 쓴다.
        assertThat(RiskAlertProperties.resolveTargetDate(null, CROSS_MIDNIGHT_KST))
                .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("공개 오버로드는 실제 시계를 쓴다 — 어제여야 한다")
    void publicOverloadUsesSystemClock() {
        LocalDate yesterdayKst = LocalDate.now(RiskAlertProperties.SEOUL).minusDays(1);

        assertThat(RiskAlertProperties.resolveTargetDate(null)).isEqualTo(yesterdayKst);
    }
}
