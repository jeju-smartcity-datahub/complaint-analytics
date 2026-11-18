package jeju.datahub.batch.geocode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스킵 확정 건 1행을 {@code batch.tb_geocode_skip} 에 upsert 하는 <b>공용</b> 기록기
 * (TSK-4054 계획서 D13 · Step 6).
 *
 * <p>민원·공영주차장 스킵 리스너가 각자 SQL 을 들고 있어 단속이 세 번째 복사본이 되는 것을 막으려고 뺐다.
 * <b>이번에는 단속 리스너만 쓴다</b> — 기존 두 리스너의 이관은 별도 계획서다. 그래서 파이프라인 고유 로직
 * (식별자 해시·사유 문자열·값 절단)을 두지 않는다. 받은 값을 <b>그대로</b> 쓴다.
 *
 * <h2>🔴 {@code REQUIRES_NEW}</h2>
 * 리스너 호출 뒤~청크 커밋 사이의 실패(같은 청크의 다음 스킵 기록 실패 →
 * {@code SkipListenerFailedException}, {@code JobRepository} 갱신·커밋 실패)와 {@code noRollback} 이
 * 추가될 때 {@code write()} 전에 리스너가 불리는 경로에서도 확정 판정이 남게 한다. 남지 않으면 다음 실행에서
 * Reader 가 그 대상을 다시 읽어 같은 외부 호출을 되풀이한다.
 *
 * <p>⚠️ 별도 빈이라 호출이 항상 프록시를 거친다 — 리스너 내부 헬퍼로 옮기면 자기호출이라 애노테이션이
 * 조용히 무시된다. 이 메서드는 예외를 삼키지 않는다(커밋 실패는 프록시가 반환 뒤에 던진다).
 *
 * <h2>재기록하면 마지막 판정이 남는다</h2>
 * {@code ON CONFLICT (src_se, trgt_id) DO UPDATE} 로 {@code fail_rsn}·{@code note}·{@code trgt_addr}·
 * {@code checked_dt} 를 갱신한다.
 */
@Component
public class GeocodeSkipRecorder {

    /** 민원 {@code GeocodeSkipListener.UPSERT_SQL} 과 문자 그대로 같다(D13). */
    private static final String UPSERT_SQL = """
            INSERT INTO batch.tb_geocode_skip
              (src_se, trgt_id, fail_rsn, note, trgt_addr, checked_dt)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (src_se, trgt_id) DO UPDATE SET
              fail_rsn = EXCLUDED.fail_rsn, note = EXCLUDED.note,
              trgt_addr = EXCLUDED.trgt_addr, checked_dt = now()
            """;

    private final JdbcTemplate jdbc;

    public GeocodeSkipRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 스킵 1건을 별도 트랜잭션에서 기록·커밋한다.
     *
     * @param srcSe     파이프라인 구분({@code GEOCODE_SRC_SE} 공통코드 — {@code CVLCPT}·{@code PBLPKLT}·{@code CRDN})
     * @param trgtId    대상 식별자({@code VARCHAR(40)})
     * @param failRsn   사유({@code VARCHAR(30)})
     * @param note      단계 판정
     * @param trgtAddr  실패한 주소({@code VARCHAR(200)}). 없으면 {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String srcSe, String trgtId, String failRsn, String note, String trgtAddr) {
        jdbc.update(UPSERT_SQL, srcSe, trgtId, failRsn, note, trgtAddr);
    }
}
