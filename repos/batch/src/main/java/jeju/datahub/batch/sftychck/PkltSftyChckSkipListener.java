package jeju.datahub.batch.sftychck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jeju.datahub.batch.sftychck.api.PkltSftyChckOriginRow;

/**
 * 스킵 확정 건을 <b>범용 잡 스킵 목록</b> {@code batch.tb_job_skip} 에 기록한다.
 * 선례는 {@code RiskAlertSkipListener} 이며 <b>같은 표·같은 SQL</b>을 쓴다.
 *
 * <h2>🔴 PK 매핑 — {@code (job_se, trgt_id, trgt_de)}</h2>
 * <table>
 *   <tr><th>컬럼</th><th>타입</th><th>이 잡에서의 값</th></tr>
 *   <tr><td>{@code job_se}</td><td>VARCHAR(20)</td><td>{@code 'SFTYCHCK'}(공통코드 {@code JOB_SE})</td></tr>
 *   <tr><td>{@code trgt_id}</td><td>VARCHAR(40)</td><td>원천 {@code sfty_chck_id} 를 <b>문자열로</b>(최대 5자리 실측)</td></tr>
 *   <tr><td>{@code trgt_de}</td><td>DATE</td><td>점검일(해석되면) · 실행일(해석되지 않으면)</td></tr>
 *   <tr><td>{@code fail_rsn}</td><td>VARCHAR(30)</td><td>화이트리스트 7종({@link PkltSftyChckSkippableException})</td></tr>
 * </table>
 *
 * <h2>🔴 제네릭 실인자는 <b>Step 청크 타입</b>과 같아야 한다</h2>
 * {@code SkipListener<PkltSftyChckOriginRow, PkltSftyChckRow>} — {@code pkltSftyChckFetchStep} 의
 * {@code .<PkltSftyChckOriginRow, PkltSftyChckRow>chunk(...)} 와 같다. 어긋나면
 * {@code FaultTolerantStepBuilder.listener(Object)} 오버로드가 잡혀 콜백이 <b>0회</b> 등록되고 스킵 기록이 통째로
 * 사라진다 — 컴파일 에러도 경고도 없다({@code PblpkltSkipListener} 클래스 주석 · 102건 유실 전례).
 * 회귀는 {@code PkltSftyChckSkipListenerTypeTest} 가 진다.
 *
 * <h2>🔴 {@code REQUIRES_NEW} — 청크 트랜잭션에 얹으면 기록이 사라진다</h2>
 * Spring Batch 는 스킵 예외가 나면 청크 트랜잭션을 롤백하고 한 건씩 다시 처리한다(scan). 이 upsert 가 청크
 * 트랜잭션에 얹혀 있으면 롤백에 휩쓸려 기록이 남지 않는다.
 * <p>⚠️ 그래서 {@code @Transactional} 은 <b>이 인터페이스 메서드에 직접</b> 붙인다. 내부 헬퍼로 옮기면 자기호출이라
 * 프록시를 타지 않아 애노테이션이 조용히 무시된다(선례 {@code geocode/GeocodeSkipRecorder}).
 *
 * <h2>재실행하면 마지막 판정이 남는다</h2>
 * {@code ON CONFLICT (job_se, trgt_id, trgt_de) DO UPDATE} 로 {@code fail_rsn}·{@code note}·{@code checked_dt} 를
 * 갱신한다. 🔴 {@code checked_dt} 갱신이 있어야 "이번 실행의 스킵" 을 {@code checked_dt >= 실행 시작} 으로 볼 수 있다.
 * Reader 는 이 표를 {@code NOT EXISTS} 로 제외하지 <b>않는다</b> — 진단 기록이지 영구 제외 목록이 아니다.
 *
 * <p>읽기·쓰기 단계 스킵({@code onSkipInRead}·{@code onSkipInWrite})은 구현하지 않는다(기본 no-op) —
 * {@code skip()} 목록에 이 잡의 스킵 예외만 있고 그것은 Processor 만 던진다. 읽기·쓰기 실패는 Step FAILED 가 맞다.
 */
@Component
public class PkltSftyChckSkipListener implements SkipListener<PkltSftyChckOriginRow, PkltSftyChckRow> {

    private static final Logger log = LoggerFactory.getLogger(PkltSftyChckSkipListener.class);

    /** 범용 스킵 표에서 이 잡을 가리키는 값 — {@code JOB_SE} 공통코드. */
    static final String JOB_SE = "SFTYCHCK";

    static final String UPSERT_SQL = """
            INSERT INTO batch.tb_job_skip
              (job_se, trgt_id, trgt_de, fail_rsn, note, checked_dt)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (job_se, trgt_id, trgt_de) DO UPDATE SET
              fail_rsn = EXCLUDED.fail_rsn, note = EXCLUDED.note, checked_dt = now()
            """;

    private final JdbcTemplate jdbc;

    public PkltSftyChckSkipListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Processor 가 던진 스킵 예외 1건을 기록한다. 사유·대상·날짜·진단 문구는 예외가 그대로 들고 온다.
     *
     * @param item 스킵된 원천 행. 예외에 이미 식별자가 있어 기록에는 쓰지 않고 로그 대조용으로만 둔다
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSkipInProcess(PkltSftyChckOriginRow item, Throwable t) {
        if (!(t instanceof PkltSftyChckSkippableException skip)) {
            // skip() 목록에 PkltSftyChckSkippableException 만 등록돼 있어 평시엔 오지 않는다.
            // 왔다면 Step 조립이 바뀐 것이므로 조용히 넘기지 않고 드러낸다.
            log.error("스킵 사유를 해석할 수 없어 기록하지 않습니다. sfty_chck_id={} type={}",
                    item == null ? null : item.sftyChckId(), t == null ? null : t.getClass().getName(), t);
            return;
        }
        jdbc.update(UPSERT_SQL, JOB_SE, skip.trgtId(), java.sql.Date.valueOf(skip.trgtDe()),
                skip.reason(), skip.note());
        log.warn("스킵 기록 [{}] sfty_chck_id={} trgt_de={} note={}",
                skip.reason(), skip.trgtId(), skip.trgtDe(), skip.note());
    }
}
