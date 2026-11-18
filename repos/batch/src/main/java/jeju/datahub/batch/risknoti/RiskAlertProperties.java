package jeju.datahub.batch.risknoti;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 판정일 해석 규칙 한 곳. <b>설정 바인딩 빈이 아니다</b> — 상태가 없다.
 *
 * <p>🔴 이름이 {@code ...Properties} 라 {@code @ConfigurationProperties} 처럼 보이지만
 * <b>바인딩할 키가 하나도 없다.</b>
 * <ul>
 *   <li>{@code batch.risknoti.chunk-size} 는 Reader 와 Step 이 각자 {@code @Value} 로 읽는다 —
 *       그래야 "Reader 페이지 크기 = 청크 크기" 가 <b>같은 키 하나</b>로 묶인다
 *       ({@code application.yml} 의 {@code batch.cvlcpt.chunk-size} 주석과 같은 규약).</li>
 *   <li>{@code today-override} 는 D11 로 폐기됐다 — 날짜는 Job 파라미터 {@code targetDate} 하나뿐이다.</li>
 * </ul>
 * 그래서 {@code final} 클래스 + {@code private} 생성자로 <b>인스턴스화·빈 등록을 막는다.</b>
 *
 * <p>⚠️ 이 메서드를 부르는 곳이 <b>네 군데</b>다 — Reader · Processor · 트리거 컨트롤러 · 스케줄러.
 * "어제" 계산이 흩어지지 않게 <b>여기 말고 다른 곳에서 {@code LocalDate.now()} 를 부르지 않는다.</b>
 */
public final class RiskAlertProperties {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private RiskAlertProperties() { }        // 🔴 인스턴스화 금지 — 빈으로 등록하지 않는다

    /** 판정 대상일 = jobParameter targetDate 가 있으면 그것, 없으면 실제 어제 (D1·D11). */
    public static LocalDate resolveTargetDate(LocalDate fromJobParameter) {
        return resolveTargetDate(fromJobParameter, Clock.systemUTC());
    }

    /**
     * 🔴 테스트가 파고들 수 있는 오버로드 — {@code Clock} 이 없으면 "Asia/Seoul 고정" 을 <b>단언할 수 없다.</b>
     * 넘긴 Clock 의 존은 {@code withZone(SEOUL)} 이 덮어쓰므로 프로덕션 경로는 항상 KST 다.
     *
     * <p>⚠️ 서버 타임존이 UTC 면 한국 시각 새벽 실행이 <b>하루 전을 가리킨다</b> —
     * 스케줄이 02:00 대라 정확히 그 구간이다.
     */
    static LocalDate resolveTargetDate(LocalDate fromJobParameter, Clock clock) {
        return fromJobParameter != null
                ? fromJobParameter
                : LocalDate.now(clock.withZone(SEOUL)).minusDays(1);
    }
}
