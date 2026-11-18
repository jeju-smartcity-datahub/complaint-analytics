package jeju.datahub.batch.rptp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스킵 확정 건을 <b>범용 잡 스킵 목록</b> {@code batch.tb_job_skip} 에 기록한다
 * (TSK-4027 Step 8-3). 선례는 {@code RiskAlertSkipListener} 이며 <b>같은 표</b>를 쓴다
 * (그 표가 {@code job_se} 로 잡을 가르는 범용 표로 만들어진 이유가 이것이다).
 *
 * <h2>🔴 PK 매핑을 못박는다 — {@code (job_se, trgt_id, trgt_de)}</h2>
 * <table>
 *   <tr><th>컬럼</th><th>타입</th><th>이 잡에서의 값</th></tr>
 *   <tr><td>{@code job_se}</td><td>VARCHAR(20)</td><td>{@code 'RPTP'}(공통코드 {@code JOB_SE} — 마이그레이션 72)</td></tr>
 *   <tr><td>{@code trgt_id}</td><td>VARCHAR(40)</td><td>{@code stng_id} 를 <b>문자열로</b> (설정 1건 = 스킵 1행)</td></tr>
 *   <tr><td>{@code trgt_de}</td><td>DATE</td><td>Job 파라미터 {@code baseYmd}(실행 기준일)</td></tr>
 *   <tr><td>{@code fail_rsn}</td><td>VARCHAR(30)</td><td>화이트리스트 4종({@link ReportAutoCrtSkippableException})</td></tr>
 * </table>
 * 어긋나면 같은 설정의 재스킵이 PK 충돌로 죽거나, 다른 날 스킵을 덮어쓴다.
 *
 * <h2>🔴 일시 실패는 기록하지 않는다</h2>
 * 5xx·타임아웃은 {@link ReportAutoCrtSkippableException#recordable()} 이 {@code false} 다 —
 * 행을 만들지 않고 카운터와 WARN 로그로만 드러낸다. 굳히면 그 주기가 <b>영구 유실된 것처럼</b>
 * 남아 원인 추적을 방해한다(계획서 D8-②).
 *
 * <h2>🔴 {@code REQUIRES_NEW} — 청크 트랜잭션에 얹으면 기록이 사라진다</h2>
 * Spring Batch 는 스킵 예외가 나면 <b>청크 트랜잭션을 롤백하고</b> 한 건씩 다시 처리한다(scan).
 * 이 upsert 가 청크 트랜잭션에 얹혀 있으면 롤백에 휩쓸려 기록이 남지 않는다.
 * <p>⚠️ 그래서 {@code @Transactional} 은 <b>이 인터페이스 메서드에 직접</b> 붙인다. 내부 헬퍼로
 * 옮기면 자기호출이라 프록시를 타지 않아 애노테이션이 조용히 무시된다
 * (선례 {@code geocode/GeocodeSkipRecorder}).
 *
 * <h2>🔒 응답 본문을 남기지 않는다</h2>
 * {@code fail_rsn} 은 화이트리스트 상수, {@code note} 는 <b>우리가 보낸 값과 상태코드</b>뿐이다.
 * 본문 원문을 넣으면 이력 표가 유출 경로가 된다.
 *
 * <p>재실행하면 마지막 스킵이 남는다 — {@code ON CONFLICT (job_se, trgt_id, trgt_de) DO UPDATE}.
 * ⚠️ Reader 는 이 표를 {@code NOT EXISTS} 로 제외하지 <b>않는다</b>(재시도가 정상 경로다).
 * 즉 여기 남는 기록은 "영구 제외 목록" 이 아니라 <b>그날 그 설정이 왜 실패했는지</b>의 진단 기록이다.
 */
@Component
public class ReportAutoCrtSkipListener
        implements SkipListener<RptpStngRow, ReportAutoCrtCommand> {

    private static final Logger log = LoggerFactory.getLogger(ReportAutoCrtSkipListener.class);

    /** 범용 스킵 표에서 이 잡을 가리키는 값 — {@code JOB_SE} 공통코드(마이그레이션 72). */
    static final String JOB_SE = "RPTP";

    /** {@code fail_rsn} 컬럼 상한. 🔴 넘기면 INSERT 자체가 죽으므로 기록 직전에 자른다. */
    static final int FAIL_RSN_MAX = 30;

    /** 일시 실패(기록하지 않음)를 세는 통계 키 — <b>테이블에는 쓰이지 않는다</b>. */
    static final String TRANSIENT_STAT_KEY = "TRANSIENT";

    private static final String UPSERT_SQL = """
            INSERT INTO batch.tb_job_skip
              (job_se, trgt_id, trgt_de, fail_rsn, note, checked_dt)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (job_se, trgt_id, trgt_de) DO UPDATE SET
              fail_rsn = EXCLUDED.fail_rsn, note = EXCLUDED.note, checked_dt = now()
            """;

    private final JdbcTemplate jdbc;

    /** 사유별 스킵 건수 — Job 종료 요약 로그(Step 9)의 원천. */
    private final Map<String, AtomicLong> skipCounts = new ConcurrentHashMap<>();

    public ReportAutoCrtSkipListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writer 가 던진 스킵 예외 1건을 처리한다.
     *
     * <p>🔴 이 잡의 스킵은 <b>쓰기(백엔드 호출) 단계</b>에서만 난다 — Processor 는 도래하지 않은
     * 설정을 {@code null} 로 <b>걸러 낼 뿐</b>(filter) 예외를 던지지 않는다. 그래서
     * {@code onSkipInProcess} 가 아니라 이 메서드가 유일한 기록 경로다.
     *
     * @param item 스킵된 요청. 예외가 이미 식별자를 들고 있어 기록에는 쓰지 않고 로그 대조용으로만 둔다
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSkipInWrite(ReportAutoCrtCommand item, Throwable t) {
        if (!(t instanceof ReportAutoCrtSkippableException skip)) {
            // skip() 목록에 ReportAutoCrtSkippableException 만 등록돼 있어 평시엔 오지 않는다.
            // 왔다면 Step 조립이 바뀐 것이므로 조용히 넘기지 않고 드러낸다.
            log.error("스킵 사유를 해석할 수 없어 기록하지 않습니다. stngId={} type={}",
                    item == null ? null : item.stngId(),
                    t == null ? null : t.getClass().getName(), t);
            return;
        }

        if (!skip.recordable()) {
            // 🔴 일시 실패 — 표에 굳히지 않는다. 다음 실행이 다시 집어 온다(D8-②).
            count(TRANSIENT_STAT_KEY);
            log.warn("일시 실패로 건너뜁니다(사유를 기록하지 않습니다). stngId={} trgt_de={} note={}",
                    skip.stngId(), skip.baseYmd(), skip.note());
            return;
        }

        String reason = normalizeReason(skip.reason());
        jdbc.update(UPSERT_SQL,
                JOB_SE,
                String.valueOf(skip.stngId()),          // 🔴 trgt_id 는 VARCHAR — 문자열로 넣는다
                java.sql.Date.valueOf(skip.baseYmd()),  // 🔴 trgt_de 는 DATE
                reason,
                skip.note());
        count(reason);
        log.warn("스킵 기록 [{}] stngId={} trgt_de={} note={}",
                reason, skip.stngId(), skip.baseYmd(), skip.note());
    }

    /**
     * 🔒 {@code fail_rsn} 안전망 — 비었으면 {@link ReportAutoCrtSkippableException#UNKNOWN},
     * 30자를 넘으면 자른다. {@code NOT NULL VARCHAR(30)} 이라 둘 다 INSERT 를 죽인다.
     */
    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return ReportAutoCrtSkippableException.UNKNOWN;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= FAIL_RSN_MAX ? trimmed : trimmed.substring(0, FAIL_RSN_MAX);
    }

    private void count(String key) {
        skipCounts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    /** 사유별 누계(이번 실행). 🔴 {@link #TRANSIENT_STAT_KEY} 는 <b>표에 없는</b> 통계 전용 키다. */
    public Map<String, Long> skipCounts() {
        return skipCounts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    /** 이번 실행의 총 스킵 건수(일시 실패 포함). */
    public long totalSkipped() {
        return skipCounts.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /**
     * 카운터를 0 으로 되돌린다 — <b>Job 시작 시점에 호출한다</b>(Step 9).
     *
     * <p>싱글턴 빈이라 리셋하지 않으면 두 번째 실행의 요약에 1회차 스킵 건수가 그대로 더해진다.
     */
    public void resetStats() {
        skipCounts.clear();
    }
}
