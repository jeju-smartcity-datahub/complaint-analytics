package jeju.datahub.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LoginAttemptGuard} 단위 테스트.
 *
 * <p>시간을 {@link MutableClock} 으로 직접 민다 — {@code Thread.sleep} 을 쓰면 테스트가 느려지고
 * CI 부하에 따라 흔들린다.
 */
class LoginAttemptGuardTest {

    private static final String EMAIL = "someone@example.com";
    private static final String IP = "10.0.0.7";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-07T00:00:00Z"));

    /** 실패 3회 / 창 10분 / 차단 10분 / 상한 100 — 검증용으로 임계치를 낮춰 잡는다. */
    private LoginAttemptGuard guard() {
        return new LoginAttemptGuard(3, Duration.ofMinutes(10), Duration.ofMinutes(10), 100, clock);
    }

    @Test
    @DisplayName("임계치 미만이면 차단하지 않는다")
    void underThreshold() {
        LoginAttemptGuard guard = guard();
        guard.recordFailure(EMAIL, IP);
        guard.recordFailure(EMAIL, IP);
        assertThat(guard.isBlocked(EMAIL, IP)).isFalse();
    }

    @Test
    @DisplayName("임계치에 닿으면 차단한다 — 이 시점부터 호출부가 Manager 를 부르지 않는다")
    void blocksAtThreshold() {
        LoginAttemptGuard guard = guard();
        for (int i = 0; i < 3; i++) {
            guard.recordFailure(EMAIL, IP);
        }
        assertThat(guard.isBlocked(EMAIL, IP)).isTrue();
    }

    @Test
    @DisplayName("성공하면 카운터가 초기화된다 — 오타 뒤 성공한 사용자가 다음 오타로 잠기지 않는다")
    void successResetsCounter() {
        LoginAttemptGuard guard = guard();
        guard.recordFailure(EMAIL, IP);
        guard.recordFailure(EMAIL, IP);

        guard.recordSuccess(EMAIL, IP);

        guard.recordFailure(EMAIL, IP);
        guard.recordFailure(EMAIL, IP);
        assertThat(guard.isBlocked(EMAIL, IP)).isFalse();
        assertThat(guard.trackedKeys()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 다른 이메일은 영향받지 않는다 — 키가 이메일+IP 라서")
    void otherEmailUnaffected() {
        LoginAttemptGuard guard = guard();
        for (int i = 0; i < 3; i++) {
            guard.recordFailure(EMAIL, IP);
        }
        assertThat(guard.isBlocked(EMAIL, IP)).isTrue();
        assertThat(guard.isBlocked("other@example.com", IP)).isFalse();
    }

    @Test
    @DisplayName("🔴 같은 이메일이라도 다른 IP 는 영향받지 않는다 — 계정 잠금 DoS 방지")
    void otherIpUnaffected() {
        LoginAttemptGuard guard = guard();
        for (int i = 0; i < 3; i++) {
            guard.recordFailure(EMAIL, IP);
        }
        assertThat(guard.isBlocked(EMAIL, "10.0.0.8")).isFalse();
    }

    @Test
    @DisplayName("이메일 대소문자·공백은 같은 키로 모인다")
    void keyIsNormalized() {
        LoginAttemptGuard guard = guard();
        guard.recordFailure("  SomeOne@Example.com ", IP);
        guard.recordFailure(EMAIL, IP);
        guard.recordFailure("SOMEONE@EXAMPLE.COM", IP);
        assertThat(guard.isBlocked(EMAIL, IP)).isTrue();
        assertThat(guard.trackedKeys()).isEqualTo(1);
    }

    @Test
    @DisplayName("차단은 block-minutes 가 지나면 풀린다")
    void blockExpires() {
        LoginAttemptGuard guard = guard();
        for (int i = 0; i < 3; i++) {
            guard.recordFailure(EMAIL, IP);
        }
        assertThat(guard.isBlocked(EMAIL, IP)).isTrue();

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(guard.isBlocked(EMAIL, IP)).isFalse();
        // 만료 항목은 조회 경로에서 그대로 제거된다(무한히 남지 않는다)
        assertThat(guard.trackedKeys()).isZero();
    }

    @Test
    @DisplayName("창(window)이 지나면 실패 누적이 새로 시작한다")
    void windowResetsFailures() {
        LoginAttemptGuard guard = guard();
        guard.recordFailure(EMAIL, IP);
        guard.recordFailure(EMAIL, IP);

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        guard.recordFailure(EMAIL, IP);
        guard.recordFailure(EMAIL, IP);
        assertThat(guard.isBlocked(EMAIL, IP)).isFalse();
    }

    @Test
    @DisplayName("만료된 항목은 정리 주기에 걷힌다 — Map 이 무한히 자라지 않는다")
    void expiredEntriesAreSwept() {
        LoginAttemptGuard guard = guard();
        for (int i = 0; i < 30; i++) {
            guard.recordFailure("user" + i + "@example.com", IP);
        }
        assertThat(guard.trackedKeys()).isEqualTo(30);

        // 창이 지난 뒤 새 실패 1건 → 쓰기 경로의 sweep 이 만료분을 걷어낸다
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        guard.recordFailure("fresh@example.com", IP);
        assertThat(guard.trackedKeys()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 상한을 넘으면 만료 임박 순으로 잘라낸다 — 무작위 이메일 폭주로 제한이 꺼지지 않는다")
    void capIsEnforced() {
        LoginAttemptGuard guard =
                new LoginAttemptGuard(3, Duration.ofMinutes(10), Duration.ofMinutes(10), 20, clock);
        for (int i = 0; i < 60; i++) {
            guard.recordFailure("flood" + i + "@example.com", "10.0.0." + (i % 200));
            clock.advance(Duration.ofSeconds(1));   // 만료 시각을 달리해 정리 순서를 만든다
        }
        assertThat(guard.trackedKeys()).isLessThanOrEqualTo(20);

        // 상한에 걸린 뒤에도 새 키의 시도 제한은 그대로 동작한다
        for (int i = 0; i < 3; i++) {
            guard.recordFailure(EMAIL, IP);
        }
        assertThat(guard.isBlocked(EMAIL, IP)).isTrue();
    }

    @Test
    @DisplayName("차단 상태에서의 조회는 항목을 새로 만들지 않는다")
    void lookupDoesNotCreateEntries() {
        LoginAttemptGuard guard = guard();
        assertThat(guard.isBlocked("never-tried@example.com", IP)).isFalse();
        assertThat(guard.trackedKeys()).isZero();
    }

    /** 테스트에서 시간을 직접 미는 Clock. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
