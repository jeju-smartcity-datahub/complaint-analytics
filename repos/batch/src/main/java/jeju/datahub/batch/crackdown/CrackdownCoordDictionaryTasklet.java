package jeju.datahub.batch.crackdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 단속 좌표 사전 전파 — 같은 {@code crdn_addr} 로 이미 좌표가 있는 행(시드·이전 실행)의 값을
 * <b>좌표 없는 행에 set-based 로 복사</b>한다.
 *
 * <h2>왜 지오코딩보다 앞인가</h2>
 * <pre>
 *   fetchStep  →  [coordDictStep — 이 Tasklet]  →  geocodeStep(주소 단위 VWorld)
 *                  └─ API 0회로 약 55% 가 채워진다 (2026-09-15 로컬 실측 100,696 / 181,866)
 * </pre>
 * 단속 주소는 쏠림이 커서(상위 1,000개 주소 = 83%) 시드 CSV 의 좌표 있는 고유 주소 1,496개만으로
 * 절반 이상이 채워진다. 남은 주소만 {@code geocodeStep} 이 지오코딩하므로 쿼터·시간을 가장 크게 줄인다.
 * 다음 수집으로 들어온 신규 행도 매 실행 이 Tasklet 이 먼저 채운다.
 *
 * <h2>🔴 {@code emd_cd IS NOT NULL} 인 좌표만 사전으로 쓴다 (D6)</h2>
 * 시드의 {@code 협재해수욕장} 1행은 좌표가 행정동 경계 밖(최근접 경계 64.1m — 50m 버퍼로도 못 잡음)이라
 * {@code emd_cd} 가 NULL 이다. 이 조건이 없으면 같은 주소 151행에 <b>"좌표는 있고 행정동은 없는"</b> 값이
 * 퍼진다. 제주 판정은 행정동 매칭이 지므로(D6) 행정동 없는 좌표는 사전 자격이 없다 — 그 행들은
 * {@code lat IS NULL} 로 남아 {@code geocodeStep} 이 다시 지오코딩한다.
 *
 * <h2>{@code geom} 은 쓰지 않는다</h2>
 * {@code geom} 은 {@code lat/lot} 에서 만드는 생성 컬럼({@code GENERATED ALWAYS … STORED})이라 SET 에
 * 넣으면 오류다. {@code lat/lot} 을 쓰면 DB 가 함께 갱신한다.
 *
 * <h2>한 트랜잭션 UPDATE</h2>
 * 대상이 약 9.4만 행 규모라 청크로 나누지 않는다 — 한 문장이 전부 반영되거나 전부 롤백된다.
 * 중간에 죽어도 "일부 주소만 전파된" 상태가 남지 않고, 재실행은 {@code t.lat IS NULL} 로 이어간다(멱등).
 * 같은 주소에 좌표가 둘 이상이면 {@code DISTINCT ON … ORDER BY crdn_addr, crdn_id} 가
 * <b>{@code crdn_id} 가 가장 작은 행</b>을 결정적으로 고르고, 그런 주소 수를 WARN 로 남긴다
 * (§1-2 실측 0건).
 *
 * <h2>🔴 영향 행수를 {@code write_cnt} 에 합산하지 않는다 (D10)</h2>
 * {@code BatchRunHistoryRecorder.record} 가 Job 의 <b>모든 Step 의 read/write 를 합산</b>해 이력 한 행에
 * 남긴다. 여기서 {@code contribution.incrementWriteCount} 를 부르면 수집 행수에 전파 행수(9만 단위)가
 * 섞여 {@code write_cnt} 가 의미를 잃는다. 그래서 INFO 로그로만 남긴다.
 *
 * <h2>🔴 클래스·{@link #propagate()} 를 {@code final}/{@code private} 로 만들지 않는다</h2>
 * Job 통합 테스트가 {@code @Primary} 익명 하위클래스로 {@link #propagate()} 를 재정의해
 * UPDATE 범위를 픽스처로 좁힌다({@link #UPDATE_SQL} 뒤에 조건을 덧붙인다). 실 DB 의 9만 행에 커밋하지
 * 않기 위한 장치다.
 */
@Component
public class CrackdownCoordDictionaryTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(CrackdownCoordDictionaryTasklet.class);

    /**
     * 사전 전파 UPDATE.
     *
     * <p>🔴 <b>마지막 조건이 {@code AND t.lat IS NULL} 로 끝나야 한다</b> — Job 통합 테스트가
     * {@code UPDATE_SQL + " AND t.crdn_addr LIKE '테스트단속주소-%'"} 로 뒤에 조건을 덧붙인다.
     * 뒤에 세미콜론·ORDER BY·주석을 붙이지 않는다.
     */
    static final String UPDATE_SQL = """
            UPDATE parking.tb_crdn_dsctn t
               SET lat = d.lat, lot = d.lot, emd_cd = d.emd_cd
              FROM (SELECT DISTINCT ON (crdn_addr) crdn_addr, lat, lot, emd_cd
                      FROM parking.tb_crdn_dsctn
                     WHERE lat IS NOT NULL
                       AND emd_cd IS NOT NULL
                     ORDER BY crdn_addr, crdn_id) d
             WHERE t.crdn_addr = d.crdn_addr
               AND t.lat IS NULL
            """;

    /** 사전 자격이 있는 좌표 중 같은 주소에 서로 다른 (lat, lot) 이 2개 이상인 주소 수. */
    static final String CONFLICT_COUNT_SQL = """
            SELECT count(*)
              FROM (SELECT crdn_addr
                      FROM parking.tb_crdn_dsctn
                     WHERE lat IS NOT NULL
                       AND emd_cd IS NOT NULL
                     GROUP BY crdn_addr
                    HAVING count(DISTINCT (lat, lot)) > 1) x
            """;

    private final JdbcTemplate jdbc;

    public CrackdownCoordDictionaryTasklet(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // 전파 전에 본다 — 전파 뒤에는 복사된 행이 섞여 "원래 사전이 1:1 이었는지" 가 흐려진다.
        int conflicts = countConflictingAddresses();
        if (conflicts > 0) {
            // 값·주소 원문은 남기지 않는다(건수만). 어떤 주소인지는 CONFLICT_COUNT_SQL 로 직접 조회.
            log.warn("단속 좌표 사전 — 같은 주소에 서로 다른 좌표가 있는 주소 {}개. "
                    + "crdn_id 가 가장 작은 행의 좌표로 전파한다", conflicts);
        }

        int updated = propagate();

        // 🔴 contribution.incrementWriteCount 를 부르지 않는다 — 이력 write_cnt 는 Step 합산이다(D10).
        log.info("단속 좌표 사전 전파 완료 — 좌표 없는 행 {}건에 같은 주소의 좌표·행정동을 채움", updated);
        return RepeatStatus.FINISHED;
    }

    /** 사전 전파 UPDATE 를 실행하고 영향 행수를 돌려준다. 🔴 {@code final}/{@code private} 금지(클래스 주석). */
    int propagate() {
        return jdbc.update(UPDATE_SQL);
    }

    int countConflictingAddresses() {
        Integer n = jdbc.queryForObject(CONFLICT_COUNT_SQL, Integer.class);
        return n == null ? 0 : n;
    }
}
