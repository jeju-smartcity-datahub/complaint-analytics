package jeju.datahub.batch.risknoti;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 위험단계 판정 기준({@code parking.tb_risk_stp_noti_crtr} 3행)을 <b>실행마다 1회</b> 읽어 두는 캐시.
 *
 * <h2>🔴 {@code @StepScope} 가 이 클래스의 핵심이다 — 빼면 옛 기준으로 판정한다</h2>
 * 기본 싱글턴이면 로드가 <b>앱 기동 시 1회</b>로 굳는다. 기준은 화면에서
 * {@code PUT /api/risk-criteria} 로 언제든 바뀌고 스케줄 경로는 앱이 상주하므로, 싱글턴이면
 * <b>재기동 전까지 옛 임계로 판정</b>하게 된다. 이 Job 은 Step 이 하나라 "Step 시작 시 1회" =
 * "Job 시작 시 1회" 다(Step 이 둘 이상으로 늘면 그때 {@code @JobScope} 로 올린다).
 *
 * <p>⚠️ 그래서 <b>클래스·메서드를 {@code final} 로 만들지 않는다</b> — {@code @StepScope} 는
 * CGLIB 스코프 프록시(TARGET_CLASS)를 만들고, {@code @StepScope} 인
 * {@link RiskAlertJudgeProcessor} 가 그 프록시를 생성자로 주입받는다. 실제 대상은 Step 실행 중
 * 첫 호출에 풀린다.
 *
 * <h2>🔴 SELECT 는 세 컬럼뿐이다 — {@code sort_seq} 를 넣으면 첫 실행에 죽는다</h2>
 * {@code parking.tb_risk_stp_noti_crtr} 의 컬럼은 {@code thrsh_id}·{@code risk_lvl_cd}·
 * {@code thrsh_cnt}·{@code mdfr_id}·{@code mdfcn_dt}·{@code incrs_rt}·{@code min_cvlcpt_cnt} 뿐이고
 * <b>{@code sort_seq} 가 없다</b>(2026-08-22 실측). 그 컬럼은 {@code parking.tb_com_cd}
 * ({@code group_cd='RISK_LVL_CD'}) 소유라 백엔드는 조인해서 정렬하지만, 판정에 필요한 값은
 * {@code incrs_rt} 하나라 여기서는 조인하지 않는다. {@code ORDER BY sort_seq} 로 짜면 Job 첫
 * 실행이 SQL 오류로 통째로 FAILED 다(D9 스킵 대상이 아니다).
 *
 * <h2>왜 "가장 높은 임계" 인가</h2>
 * 단조성(CRIT&gt;WARN&gt;CAUT)은 <b>DB 제약도 API 검증도 강제하지 않는다</b> —
 * {@code tb_risk_stp_noti_crtr} 의 제약은 PK 와 {@code UNIQUE(risk_lvl_cd)} 둘뿐이고
 * {@code PUT /api/risk-criteria} 는 항목별 {@code 0 < x <= 100} 만 본다. 그래서 판정을
 * "넘긴 기준 중 <b>임계가 가장 큰</b> 하나"로 정의해야 비단조 설정에서도 의미가 유지된다(D5).
 */
@Component
@StepScope
public class RiskCriteriaCache {

    private static final Logger log = LoggerFactory.getLogger(RiskCriteriaCache.class);

    /**
     * 🔴 <b>세 컬럼 · 조인 없음.</b> 위 주석대로 {@code sort_seq}/{@code tb_com_cd} 를 넣지 않는다.
     * {@code ORDER BY incrs_rt DESC} 라 <b>첫 매치가 곧 답</b>이다.
     */
    static final String LOAD_SQL =
            "SELECT risk_lvl_cd, incrs_rt, min_cvlcpt_cnt "
          + "  FROM parking.tb_risk_stp_noti_crtr ORDER BY incrs_rt DESC";

    /** 임계 내림차순. {@link #LOAD_SQL} 이 이미 그 순서를 내지만 방어적으로 한 번 더 고정한다. */
    private final List<RiskCriteria> criteria;

    /**
     * 🔴 <b>{@code @Autowired} 를 빼면 Job 첫 행 처리에서 즉사한다</b> — 아래 테스트용 생성자와 함께
     * <b>생성자가 둘</b>이라, 표시가 없으면 Spring 이 어느 쪽도 고르지 않고 기본 생성자를 찾다가
     * {@code "No default constructor found"} 로 죽는다(2026-08-26 통합 테스트에서 실측 —
     * {@code @StepScope} 라 <b>기동이 아니라 Processor 의 첫 호출 시점</b>에 터져 기동만으로는 드러나지 않는다).
     */
    @Autowired
    public RiskCriteriaCache(JdbcTemplate jdbc) {
        this(load(jdbc));
    }

    /**
     * 기준을 직접 심는 경로 — <b>단위 테스트가 실 DB 값에 의존하지 않게</b> 하기 위해 둔다.
     * 기준은 화면에서 바뀌는 값이라 "현재 30/20/10" 을 테스트 기대값의 근거로 삼을 수 없다.
     */
    RiskCriteriaCache(List<RiskCriteria> criteria) {
        this.criteria = criteria.stream()
                // ⚠️ incrs_rt 는 NULL 을 허용하는 컬럼이다(41-alert-columns). DESC 정렬에서 NULL 은
                //    맨 앞으로 오므로(Postgres 기본 NULLS FIRST) 걸러 내지 않으면 첫 매치 비교에서
                //    NPE 로 죽는다. 기준값이 비어 있는 단계는 "설정되지 않은 단계" 로 보고 제외한다.
                .filter(row -> row.incrsRt() != null)
                .sorted(Comparator.comparing(RiskCriteria::incrsRt).reversed())
                .toList();
    }

    private static List<RiskCriteria> load(JdbcTemplate jdbc) {
        List<RiskCriteria> rows = jdbc.query(LOAD_SQL, (rs, rowNum) -> new RiskCriteria(
                rs.getString("risk_lvl_cd"),
                rs.getBigDecimal("incrs_rt"),
                rs.getInt("min_cvlcpt_cnt")));   // NOT NULL DEFAULT 0 이라 getInt 가 안전하다
        log.info("위험단계 판정 기준 {}건 로드: {}", rows.size(), rows);
        return rows;
    }

    /**
     * 넘긴 기준 중 <b>임계가 가장 큰 하나</b>를 고른다(D5). 통과 조건이 <b>둘</b>이다 —
     * 증가율이 임계 이상이고, <b>당일·전일 건수가 모두 하한 이상</b>이어야 한다
     * (🔁 전일 추가).
     *
     * <p>⚠️ 하나도 못 넘으면 {@code null} 이다 — <b>정상 경로</b>이지 예외가 아니다.
     *
     * <p>🔴 하한 검사를 여기 둔 이유: 단계마다 하한이 다를 수 있어(D3) 순회 안에서 함께 봐야 한다.
     * Processor 앞단에서 한 번에 거르면 3행이 같은 값일 때만 맞는다.
     *
     * @param incrsRt   계산된 증가율(%). 경계는 <b>"기준값 이상"</b> 이면 통과 (기준 30 · 증가율 30.00 → CRIT)
     * @param targetCnt 판정일 <b>당일</b> 민원 건수
     * @param prevCnt   판정일 <b>전일</b> 민원 건수 — 분모 보정(max(전일, 1)) 전의 <b>실제 값</b>이다.
     *                  보정값을 넘기면 하한 1 에서 전일 0건이 통과한다
     */
    public RiskCriteria highestMatched(BigDecimal incrsRt, long targetCnt, long prevCnt) {
        for (RiskCriteria row : criteria) {
            if (incrsRt.compareTo(row.incrsRt()) >= 0
                    && targetCnt >= row.minCvlcptCnt()
                    && prevCnt >= row.minCvlcptCnt()) {
                return row;                       // 정렬이 DESC 라 첫 매치 = 최고 임계
            }
        }
        return null;
    }

    /** 이번 실행이 들고 있는 기준(임계 내림차순). 진단·테스트용. */
    public List<RiskCriteria> criteria() {
        return criteria;
    }

    /**
     * 판정 기준 한 행.
     *
     * @param riskLvlCd     위험단계 코드 — 그대로 {@code tb_risk_stp_noti_hstry.risk_lvl_cd} 가 된다
     * @param incrsRt       증가율 임계(%) — 그대로 {@code incrs_rt_thrsh} 스냅샷이 된다(D8)
     * @param minCvlcptCnt  최소 민원수 하한. 당일 또는 전일 건수가 이 값 미만이면
     *                      증가율을 넘겨도 이 단계 알림은 나지 않는다. 0 이면 하한 없음
     */
    public record RiskCriteria(String riskLvlCd, BigDecimal incrsRt, int minCvlcptCnt) {
    }
}
