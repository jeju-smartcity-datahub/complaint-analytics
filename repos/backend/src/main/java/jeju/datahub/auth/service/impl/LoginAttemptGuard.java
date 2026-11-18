package jeju.datahub.auth.service.impl;

import jeju.datahub.common.SesnIdHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이메일/비밀번호 로그인의 시도 횟수 제한 (TSK-4134 Step 4 · R5).
 *
 * <p>{@code POST /api/auth/login} 은 <b>IdP 로 향하는 새 브루트포스 창구</b>다(§1 "브루트포스 표면 신설").
 * IdP(Manager/Keycloak) 쪽에 잠금 정책이 있는지 확인되지 않았으므로 우리 쪽에 최소한을 둔다 —
 * 임계치를 넘으면 <b>Manager 를 부르지 않고</b> 그 자리에서 {@code 429} 다.
 *
 * <h2>🔴 인메모리 한계 — 이것을 완전한 방어로 오해하지 말 것</h2>
 * 카운터는 <b>이 JVM 의 힙에만</b> 있다. 그래서 아래 상황에서 무력하거나 부정확하다.
 * <ul>
 *   <li><b>다중 인스턴스 배포</b> — 인스턴스가 N 개면 공격자가 사실상 {@code N × 임계치} 만큼 시도할 수 있다.
 *       계획서가 이 한계를 <b>범위 밖 #2 로 명시</b>했다(분산 저장 = Redis 등은 다중 인스턴스 배포가
 *       확정되면 도입한다). 그때 이 클래스를 인터페이스로 바꾸고 저장소만 갈아끼우면 된다.</li>
 *   <li><b>재기동</b> — 프로세스가 죽으면 카운터도 함께 사라진다(차단이 풀린다).</li>
 *   <li><b>분산 출발지</b> — 키에 IP 가 들어가므로 IP 를 바꿔 가며 두드리면 키가 갈린다.
 *       그래도 <b>IP 를 빼면 안 된다</b> — 이메일만으로 잠그면 남의 계정을 골라 잠글 수 있다(정상 사용자 차단).</li>
 * </ul>
 * 즉 이것은 <b>완화(mitigation)</b> 이지 방어가 아니다. 실제 잠금 정책의 정본은 IdP 여야 한다.
 *
 * <h2>키 설계 — 이메일 + 클라이언트 IP</h2>
 * 계획서 Step 4 가 정한 그대로다. 둘 중 하나만 쓰면 각각 이렇게 깨진다.
 * <ul>
 *   <li><b>IP 만</b> — NAT/사무실 공유 IP 뒤의 정상 사용자가 남의 실패로 함께 막힌다.</li>
 *   <li><b>이메일만</b> — 임의의 계정을 골라 고의로 잠글 수 있다(계정 잠금 DoS).</li>
 * </ul>
 * 🔴 IP 는 {@code request.getRemoteAddr()} 하나만 쓴다 — {@code server.forward-headers-strategy: NATIVE}
 * (application.yml:39)가 켠 {@code RemoteIpValve} 가 XFF 를 이미 반영해 덮어쓴 값이다.
 * <b>XFF 헤더를 직접 파싱하지 않는다</b>(TSK-4104 규약 · 직접 파싱하면 헤더 위조로 시도 제한이 우회된다).
 *
 * <h2>🔴 D6 — 자격증명은 로그에 남기지 않는다</h2>
 * 키에는 이메일이 들어가므로 <b>키 원문을 로그에 찍지 않는다</b>. 표시가 필요하면
 * {@link SesnIdHasher#shortHash(String)} 로 마스킹한 레이블만 남긴다.
 * ⚠️ 이 값은 <b>세션 해시가 아니다</b> — 로그 상관용 라벨일 뿐이며
 * {@code TB_LGN_HSTRY.sesn_id} 계약과 무관하다. 새 {@code MessageDigest} 를 만들지 않으려고
 * 같은 공용 유틸을 빌려 쓴 것뿐이다.
 *
 * <h2>메모리 누수 방지</h2>
 * 키가 (이메일 × IP) 조합이라 방치하면 무한히 자란다. 두 겹으로 막는다.
 * <ul>
 *   <li><b>만료 정리(sweep)</b> — 쓰기 경로에서 {@value #SWEEP_INTERVAL_SEC} 초에 한 번 만료 항목을 제거한다.</li>
 *   <li><b>상한(cap)</b> — 항목 수가 {@code max-entries} 를 넘으면 <b>만료가 임박한 순</b>으로 잘라낸다.
 *       상한을 넘겨 새 키를 아예 무시하면(fail-open) 무작위 이메일 폭주로 제한 자체가 꺼지므로 그렇게 하지 않는다.</li>
 * </ul>
 *
 * <h2>설정 ({@code app.auth.login-attempt.*})</h2>
 * 기본값은 Java 상수에 있으므로 yml 이 키를 빠뜨려도 동작한다.
 */
@Component
public class LoginAttemptGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);

    /** 만료 항목 정리 주기(초). 쓰기 경로에서만 확인한다 — 별도 스케줄러 스레드를 두지 않는다. */
    static final int SWEEP_INTERVAL_SEC = 60;

    /** 상한을 넘겼을 때 남길 비율 — 90% 까지 줄인다(매 요청마다 잘라내지 않게 여유를 둔다). */
    private static final double EVICT_KEEP_RATIO = 0.9d;

    private final int maxFailures;
    private final Duration window;
    private final Duration blockDuration;
    private final int maxEntries;
    private final Clock clock;

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    /** 다음 정리 시각. 쓰기 경로에서만 갱신한다. */
    private volatile Instant nextSweepAt = Instant.EPOCH;

    /**
     * 🔴 생성자가 둘이라 {@code @Autowired} 로 <b>어느 쪽을 쓸지 못박는다</b> —
     * 없으면 컨테이너가 기본 생성자를 찾다가 {@code NoSuchMethodException} 으로 기동이 죽는다.
     */
    @Autowired
    public LoginAttemptGuard(
            @Value("${app.auth.login-attempt.max-failures:5}") int maxFailures,
            @Value("${app.auth.login-attempt.window-minutes:10}") long windowMinutes,
            @Value("${app.auth.login-attempt.block-minutes:10}") long blockMinutes,
            @Value("${app.auth.login-attempt.max-entries:10000}") int maxEntries) {
        this(maxFailures, Duration.ofMinutes(windowMinutes), Duration.ofMinutes(blockMinutes),
                maxEntries, Clock.systemUTC());
    }

    /** 테스트용 — 시간을 직접 밀어 만료·차단 해제를 검증한다(Thread.sleep 금지). */
    LoginAttemptGuard(int maxFailures, Duration window, Duration blockDuration, int maxEntries, Clock clock) {
        this.maxFailures = Math.max(1, maxFailures);
        this.window = window;
        this.blockDuration = blockDuration;
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
    }

    /**
     * 차단 중인가. {@code true} 면 호출부는 <b>Manager 를 부르지 않고</b> 429 로 끝낸다.
     *
     * <p>조회는 항목을 새로 만들지 않는다({@code computeIfPresent}) — 미인증 조회로 맵을 키울 수 없게.
     */
    public boolean isBlocked(String email, String ip) {
        Instant now = clock.instant();
        Counter c = counters.computeIfPresent(key(email, ip), (k, v) -> v.isExpired(now) ? null : v);
        return c != null && c.isBlocked(now);
    }

    /**
     * 자격증명 실패 1회 기록. 임계치에 닿으면 그 시점부터 {@code block-minutes} 동안 차단한다.
     *
     * @return 이 호출로 <b>차단이 시작됐으면</b> {@code true}(전이 시점 1회만). 이미 차단 중이거나
     *         임계치 미달이면 {@code false} — 호출부가 이력을 <b>전이 때만</b> 남기게 하는 신호다.
     *
     * <p>🔴 <b>자격증명 검증 실패에만</b> 부른다. 정지 계정({@code USER_DISABLED})처럼 비밀번호가
     * 맞은 뒤의 실패는 브루트포스가 아니므로 세지 않는다 — 세면 정상 자격증명 보유자가 잠긴다.
     */
    public boolean recordFailure(String email, String ip) {
        Instant now = clock.instant();
        maintain(now);
        String key = key(email, ip);
        Counter updated = counters.compute(key, (k, v) -> {
            Counter c = (v == null || v.isExpired(now)) ? new Counter(now.plus(window)) : v;
            if (c.isBlocked(now)) {
                return c;   // 이미 차단 — 연장하지 않는다(호출부가 이미 429 로 끊으므로 여기 오지 않는 것이 정상)
            }
            c.failures++;
            if (c.failures >= maxFailures) {
                c.blockedUntil = now.plus(blockDuration);
            }
            return c;
        });
        boolean justBlocked = updated.blockedUntil != null && updated.failures == maxFailures;
        if (justBlocked) {
            // 🔴 D6 — 키 원문(이메일 포함)을 찍지 않는다. 마스킹 레이블 + IP 만 남긴다.
            log.warn("[login-attempt] 임계치({}) 도달 — {}분간 차단 (key={}, ip={})",
                    maxFailures, blockDuration.toMinutes(), mask(key), ip);
        }
        return justBlocked;
    }

    /** 로그인 성공 — 카운터를 초기화한다(정상 사용자가 다음 오타로 바로 잠기지 않게). */
    public void recordSuccess(String email, String ip) {
        counters.remove(key(email, ip));
    }

    /** 테스트·진단용 — 현재 추적 중인 키 수. */
    int trackedKeys() {
        return counters.size();
    }

    // ---------------------------------------------------------------- 내부

    /**
     * 키 = 정규화한 이메일 + IP. 대소문자·앞뒤 공백만 다른 입력으로 카운터가 갈리지 않게 정규화한다.
     * (Manager 가 대소문자를 어떻게 다루든 <b>우리 카운터는</b> 같은 키로 모은다.)
     */
    private static String key(String email, String ip) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return e + "|" + (ip == null ? "" : ip);
    }

    /** 로그 표시용 마스킹 — 세션 해시가 아니다(클래스 주석 D6 절 참고). */
    private static String mask(String key) {
        String h = SesnIdHasher.shortHash(key);
        return h == null ? "-" : h;
    }

    /** 만료 정리 + 상한 유지. 쓰기 경로에서만 부른다. */
    private void maintain(Instant now) {
        boolean overCapacity = counters.size() >= maxEntries;
        if (!overCapacity && now.isBefore(nextSweepAt)) {
            return;
        }
        nextSweepAt = now.plusSeconds(SWEEP_INTERVAL_SEC);
        counters.values().removeIf(c -> c.isExpired(now));
        if (counters.size() >= maxEntries) {
            evictOldest();
        }
    }

    /**
     * 상한 초과분을 <b>만료가 임박한 순</b>으로 잘라낸다.
     *
     * <p>새 키를 무시(fail-open)하지 않는 이유 — 무작위 이메일로 맵을 채우면 그 뒤의 실제 공격이
     * 아예 집계되지 않아 제한이 꺼진다. 오래된 것을 버리는 쪽이 낫다(버려지는 것은 이미 만료가 가까운 항목이다).
     */
    private synchronized void evictOldest() {
        int target = (int) (maxEntries * EVICT_KEEP_RATIO);
        int excess = counters.size() - target;
        if (excess <= 0) {
            return;
        }
        List<Map.Entry<String, Counter>> entries = new ArrayList<>(counters.entrySet());
        entries.sort(Comparator.comparing(e -> e.getValue().expiresAt()));
        for (int i = 0; i < excess && i < entries.size(); i++) {
            counters.remove(entries.get(i).getKey(), entries.get(i).getValue());
        }
        log.warn("[login-attempt] 추적 항목이 상한({})에 닿아 {}건을 정리했다 — 분산 공격이거나 상한이 낮다",
                maxEntries, excess);
    }

    /**
     * 키 하나의 상태. {@code ConcurrentHashMap.compute(...)} 안에서만 변경하므로
     * (해당 bin 이 잠긴다) 별도 동기화를 두지 않는다.
     */
    private static final class Counter {
        /** 실패 누적 창의 끝. 넘기면 카운터를 새로 시작한다. */
        private final Instant windowEndsAt;
        private int failures;
        /** null 이면 차단 아님. */
        private Instant blockedUntil;

        private Counter(Instant windowEndsAt) {
            this.windowEndsAt = windowEndsAt;
        }

        private boolean isBlocked(Instant now) {
            return blockedUntil != null && blockedUntil.isAfter(now);
        }

        /** 차단 중이면 차단 해제 시각, 아니면 실패 창의 끝. 정리 순서를 정하는 기준이다. */
        private Instant expiresAt() {
            return blockedUntil != null && blockedUntil.isAfter(windowEndsAt) ? blockedUntil : windowEndsAt;
        }

        private boolean isExpired(Instant now) {
            return !expiresAt().isAfter(now);
        }
    }
}
