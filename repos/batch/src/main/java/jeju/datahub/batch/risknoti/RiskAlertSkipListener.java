package jeju.datahub.batch.risknoti;

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
 * (TSK-4069 Step 5 · D9).
 *
 * <p>{@code job_se='RISKNOTI'} · {@code trgt_id}=읍면동코드 · {@code trgt_de}=판정일 로 남긴다.
 * 표가 범용인 이유는 향후 보고서 자동생성({@code 'RPTP'}) 등이 같은 표를 쓰게 하기 위함이다 —
 * 잡마다 스킵 표를 새로 파면 46-UNIFY-GEOCODE-SKIP 이 겪은 통합 작업을 반복하게 된다.
 *
 * <p>🔴 지오코딩 전용 {@code batch.tb_geocode_skip} 에 합류하지 않는다 — 그 표는 PK 가
 * {@code (src_se, trgt_id)} 라 <b>날짜 차원이 없다.</b> 판정 스킵은 같은 읍면동이 <b>다른 날 다시</b>
 * 스킵될 수 있어 그대로 얹으면 이전 판정일 기록을 덮어쓴다.
 *
 * <h2>🔴 {@code REQUIRES_NEW} — 청크 트랜잭션에 얹으면 기록이 사라진다</h2>
 * Spring Batch 는 Processor 가 스킵 예외를 던지면 <b>청크 트랜잭션을 롤백하고</b> 그 청크를 한 건씩
 * 다시 처리한다(scan). 이 upsert 가 청크 트랜잭션에 얹혀 있으면 롤백에 같이 휩쓸려 스킵 기록이
 * 남지 않는다. 별도 트랜잭션에서 즉시 커밋해야 한다.
 *
 * <p>⚠️ 그래서 {@code @Transactional} 은 <b>이 인터페이스 메서드에 직접</b> 붙인다. 내부 헬퍼로
 * 옮기면 자기호출(self-invocation)이라 프록시를 타지 않아 애노테이션이 조용히 무시된다 —
 * 겉보기엔 동작하지만 롤백 한 번에 기록이 통째로 사라지는, 발견하기 어려운 형태로 깨진다
 * (선례 {@code geocode/GeocodeSkipRecorder}).
 *
 * <h2>재실행하면 마지막 판정이 남는다</h2>
 * {@code ON CONFLICT (job_se, trgt_id, trgt_de) DO UPDATE} 로 {@code fail_rsn}·{@code note}·
 * {@code checked_dt} 를 갱신한다. ⚠️ 판정 잡의 Reader 는 이 표를 {@code NOT EXISTS} 로 제외하지
 * <b>않는다</b>(D6 — 재판정이 정상 경로다). 즉 여기 남는 기록은 "영구 제외 목록"이 아니라
 * <b>그날 그 읍면동을 왜 못 적재했는지</b>의 진단 기록이다.
 */
@Component
public class RiskAlertSkipListener implements SkipListener<RiskAlertTargetRow, RiskAlertRecord> {

    private static final Logger log = LoggerFactory.getLogger(RiskAlertSkipListener.class);

    /** 범용 스킵 표에서 이 잡을 가리키는 값 — {@code JOB_SE} 공통코드(52-risk-alert-judge). */
    static final String JOB_SE = "RISKNOTI";

    private static final String UPSERT_SQL = """
            INSERT INTO batch.tb_job_skip
              (job_se, trgt_id, trgt_de, fail_rsn, note, checked_dt)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (job_se, trgt_id, trgt_de) DO UPDATE SET
              fail_rsn = EXCLUDED.fail_rsn, note = EXCLUDED.note, checked_dt = now()
            """;

    private final JdbcTemplate jdbc;

    /** 사유별 스킵 건수 — Job 종료 요약 로그의 원천. 사유가 문자열이라 {@code EnumMap} 대신 맵을 쓴다. */
    private final Map<String, AtomicLong> skipCounts = new ConcurrentHashMap<>();

    public RiskAlertSkipListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Processor 가 던진 스킵 예외 1건을 기록한다.
     *
     * <p>사유·판정일·진단 문구는 예외가 그대로 들고 온다 — 원본 행이나 Job 파라미터를 다시 뒤지지
     * 않는다({@link RiskAlertSkippableException} 설계).
     *
     * @param item 스킵된 집계 행. 예외에 이미 식별자가 있어 기록에는 쓰지 않고 로그 대조용으로만 둔다
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSkipInProcess(RiskAlertTargetRow item, Throwable t) {
        if (!(t instanceof RiskAlertSkippableException skip)) {
            // skip() 목록에 RiskAlertSkippableException 만 등록돼 있어 평시엔 오지 않는다.
            // 왔다면 Step 조립이 바뀐 것이므로 조용히 넘기지 않고 드러낸다.
            log.error("스킵 사유를 해석할 수 없어 기록하지 않습니다. emd_cd={} type={}",
                    item == null ? null : item.emdCd(), t == null ? null : t.getClass().getName(), t);
            return;
        }
        jdbc.update(UPSERT_SQL, JOB_SE, skip.emdCd(), java.sql.Date.valueOf(skip.judgeDate()),
                skip.reason(), skip.note());
        skipCounts.computeIfAbsent(skip.reason(), key -> new AtomicLong()).incrementAndGet();
        log.warn("스킵 기록 [{}] emd_cd={} trgt_de={} note={}",
                skip.reason(), skip.emdCd(), skip.judgeDate(), skip.note());
    }

    /** 사유별 누계(이번 실행). Job 종료 요약 로그가 읽는다. */
    public Map<String, Long> skipCounts() {
        return skipCounts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    /** 이번 실행의 총 스킵 건수. */
    public long totalSkipped() {
        return skipCounts.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /**
     * 카운터를 0 으로 되돌린다 — <b>Job 시작 시점에 호출한다</b>.
     *
     * <p>싱글턴 빈이라 리셋하지 않으면 두 번째 실행의 요약에 1회차 스킵 건수가 그대로 더해진다.
     * 테이블에 쌓인 누적 건수는 {@code batch.tb_job_skip} 을 직접 조회해 볼 수 있으므로, 이
     * 카운터는 "이번 실행에서 새로 스킵된 수"를 뜻하게 둔다.
     */
    public void resetStats() {
        skipCounts.clear();
    }
}
