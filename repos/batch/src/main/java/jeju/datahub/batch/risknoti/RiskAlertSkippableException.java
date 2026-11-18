package jeju.datahub.batch.risknoti;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 이 읍면동은 <b>저장 불가로 확정</b>이니 건너뛰라는 신호.
 * 선례는 {@code GeocodeSkippableException} 이다.
 *
 * <p>Step 조립에서 {@code .faultTolerant().skip(RiskAlertSkippableException.class)} 대상이며,
 * {@link RiskAlertSkipListener} 가 이 예외를 받아 {@code batch.tb_job_skip} 에 기록한다.
 *
 * <h2>🔴 스킵 대상을 좁게 못박는다</h2>
 * Processor 가 <b>저장 불가로 판정한 행만</b> 이 예외를 던진다 — 현재 유일한 사례는 증가율이
 * {@code NUMERIC(7,2)} 상한(99999.99)을 넘는 {@link #RATE_OVERFLOW} 다.
 * <table>
 *   <tr><th>상황</th><th>처리</th></tr>
 *   <tr><td>증가율이 저장 범위를 넘음</td><td><b>이 예외</b>({@link #RATE_OVERFLOW})</td></tr>
 *   <tr><td>어떤 기준도 못 넘음(알림 없음)</td><td>Processor 가 {@code null} 반환. <b>이 예외 아님</b> — 정상 경로다</td></tr>
 *   <tr><td>SQL 오류·커넥션 장애</td><td>그대로 전파해 Job 을 FAILED 로 드러낸다. <b>이 예외 아님</b></td></tr>
 * </table>
 * ⚠️ {@code skip(Exception.class)} 같은 광범위 스킵을 쓰지 않는다 — 삼키면 <b>데이터가 조용히 비고
 * Job 은 COMPLETED</b> 로 끝난다.
 *
 * <h2>🔴 {@code judgeDate} 를 같이 실어 나른다</h2>
 * 스킵 테이블 PK 가 {@code (job_se, trgt_id, trgt_de)} 라 리스너가 <b>판정일을 모르면 기록 자체를
 * 못 한다.</b> Reader 가 내는 {@link RiskAlertTargetRow} 에는 날짜가 없으므로(집계 SQL 이 이미
 * 날짜로 걸러 낸 뒤다) Processor 가 들고 있는 판정일을 예외에 실어 보낸다 — 리스너가 원본 행이나
 * Job 파라미터를 다시 뒤지지 않게 하기 위함이다(선례 {@code GeocodeSkippableException} 과 같은 설계).
 */
public class RiskAlertSkippableException extends RuntimeException {

    /**
     * 증가율이 {@code NUMERIC(7,2)} 상한(99999.99)을 넘어 그대로는 INSERT 가
     * {@code numeric field overflow} 로 죽는 경우. 그대로 {@code batch.tb_job_skip.fail_rsn}
     * 값이 된다({@code VARCHAR(30)}).
     */
    public static final String RATE_OVERFLOW = "RATE_OVERFLOW";

    private final String reason;
    private final String emdCd;
    private final BigDecimal incrsRt;
    private final LocalDate judgeDate;
    private final String note;

    /**
     * @param reason    스킵 사유 — {@link #RATE_OVERFLOW} 등. {@code fail_rsn} 에 그대로 들어간다
     * @param row       스킵된 집계 행. 진단 문구({@code note})와 {@code trgt_id}(읍면동코드)의 출처다
     * @param incrsRt   계산된 증가율. 저장은 못 하지만 <b>얼마나 튀었는지</b>가 사후 분석의 핵심 단서다
     * @param judgeDate 판정일 — 스킵 테이블의 {@code trgt_de}
     */
    public RiskAlertSkippableException(String reason, RiskAlertTargetRow row,
                                       BigDecimal incrsRt, LocalDate judgeDate) {
        super("[" + reason + "] emd_cd=" + row.emdCd() + "(" + row.emdNm() + ")"
                + " trgt_de=" + judgeDate
                + " target_cnt=" + row.targetCnt() + " prev_cnt=" + row.prevCnt()
                + " incrs_rt=" + incrsRt);
        this.reason = reason;
        this.emdCd = row.emdCd();
        this.incrsRt = incrsRt;
        this.judgeDate = judgeDate;
        this.note = "emd_nm=" + row.emdNm()
                + " target_cnt=" + row.targetCnt()
                + " prev_cnt=" + row.prevCnt()
                + " incrs_rt=" + incrsRt;
    }

    /** {@code batch.tb_job_skip.fail_rsn}. */
    public String reason() {
        return reason;
    }

    /** {@code batch.tb_job_skip.trgt_id} — 판정 잡에서는 읍면동코드다. */
    public String emdCd() {
        return emdCd;
    }

    /** 저장할 수 없었던 증가율. 로그 대조용. */
    public BigDecimal incrsRt() {
        return incrsRt;
    }

    /** {@code batch.tb_job_skip.trgt_de} — 판정일. PK 의 일부라 <b>없으면 기록 자체가 불가능</b>하다. */
    public LocalDate judgeDate() {
        return judgeDate;
    }

    /** {@code batch.tb_job_skip.note} — 당일/전일 건수와 계산된 증가율(사람이 읽을 진단 정보). */
    public String note() {
        return note;
    }
}
