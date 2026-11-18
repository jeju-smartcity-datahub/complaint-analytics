package jeju.datahub.batch.sftychck;

import java.time.LocalDate;

import jeju.datahub.batch.sftychck.api.PkltSftyChckOriginRow;

/**
 * 이 원천 행은 <b>적재 불가로 확정</b>이니 건너뛰라는 신호 (TSK-4254 Step 4 · D7).
 * 선례는 {@code RiskAlertSkippableException} · {@code PkltUtztnSkippableException} 이다.
 *
 * <p>Step 조립에서 {@code .faultTolerant().skip(PkltSftyChckSkippableException.class)} 대상이며,
 * {@link PkltSftyChckSkipListener} 가 이 예외를 받아 {@code batch.tb_job_skip}({@code job_se='SFTYCHCK'})에 기록한다.
 *
 * <h2>사유는 화이트리스트 7종</h2>
 * <table>
 *   <tr><th>사유</th><th>조건</th></tr>
 *   <tr><td>{@link #PKLT_NOT_FOUND}</td><td>① 키 경로 실패 + ② 주소·이름 후보 모두 0</td></tr>
 *   <tr><td>{@link #PKLT_AMBIGUOUS}</td><td>② 후보가 2개 이상 — 추측하지 않는다(D2). {@code note} 에 후보 수</td></tr>
 *   <tr><td>{@link #YMD_INVALID}</td><td>점검일자가 없거나 8자리 실존 날짜가 아니다</td></tr>
 *   <tr><td>{@link #TYPE_UNMAPPED} · {@link #RSLT_UNMAPPED}</td><td>유형·결과 원천값이 §4 허용 목록 밖(D4 — fallback 금지)</td></tr>
 *   <tr><td>{@link #PRD_UNMAPPED}</td><td>시기 원천값이 <b>있는데</b> 허용 목록 밖(없을 때만 점검월로 유도한다)</td></tr>
 *   <tr><td>{@link #DUP_KEY}</td><td>같은 실행에서 자연키 {@code (pklt_id, chck_ymd, sfty_chck_type_cd)} 가 이미 나왔다</td></tr>
 * </table>
 * 🔴 사유 코드는 그대로 {@code fail_rsn}({@code VARCHAR(30)})이 된다 — 예외 메시지 원문을 넣지 않는다.
 *
 * <h2>🔴 스킵 대상을 좁게 못박는다</h2>
 * 🔴 이 예외만 {@code skip()} 목록에 넣는다. API 오류·파싱 실패(원천 스키마 변경)는 넣지 않아 Job 이 FAILED 로
 * 끊기게 둔다 — 삼키면 데이터가 조용히 비고 Job 은 COMPLETED 로 끝난다.
 *
 * <h2>🔴 {@code trgtDe} 를 같이 실어 나른다</h2>
 * 스킵 표 PK 가 {@code (job_se, trgt_id, trgt_de)} 라 리스너가 날짜를 모르면 기록 자체를 못 한다.
 * {@code trgt_de} = 점검일(해석되면) 또는 실행일(해석되지 않으면) — Processor 가 정해 넣는다.
 * ⚠️ 점검일이 없는 행은 실행일마다 새 스킵 행이 쌓인다(고장이 아니다 — "이번 실행" 은 {@code checked_dt} 로 본다).
 */
public class PkltSftyChckSkippableException extends RuntimeException {

    public static final String PKLT_NOT_FOUND = "PKLT_NOT_FOUND";
    public static final String PKLT_AMBIGUOUS = "PKLT_AMBIGUOUS";
    public static final String YMD_INVALID = "YMD_INVALID";
    public static final String TYPE_UNMAPPED = "TYPE_UNMAPPED";
    public static final String RSLT_UNMAPPED = "RSLT_UNMAPPED";
    public static final String PRD_UNMAPPED = "PRD_UNMAPPED";
    public static final String DUP_KEY = "DUP_KEY";

    private final String reason;
    private final String trgtId;
    private final LocalDate trgtDe;
    private final String note;

    /**
     * @param reason 스킵 사유 — 위 7종 상수. {@code fail_rsn} 에 그대로 들어간다
     * @param row    스킵된 원천 행. {@code trgt_id}(= 원천 {@code sfty_chck_id})의 출처다
     * @param trgtDe 점검일 또는 실행일 — 스킵 표의 {@code trgt_de}
     * @param note   사람이 읽을 진단 문구({@code note})
     */
    public PkltSftyChckSkippableException(String reason, PkltSftyChckOriginRow row, LocalDate trgtDe, String note) {
        super("[" + reason + "] sfty_chck_id=" + row.sftyChckId() + " trgt_de=" + trgtDe + " " + note);
        this.reason = reason;
        // 🔴 trgt_id 는 VARCHAR(40) — 원천 키는 최대 5자리(Step 1 실측)라 long 문자열(최대 20자)이면 넘치지 않는다.
        this.trgtId = String.valueOf(row.sftyChckId());
        this.trgtDe = trgtDe;
        this.note = note;
    }

    /** {@code batch.tb_job_skip.fail_rsn}. */
    public String reason() {
        return reason;
    }

    /** {@code batch.tb_job_skip.trgt_id} — 원천 {@code sfty_chck_id} 문자열. */
    public String trgtId() {
        return trgtId;
    }

    /** {@code batch.tb_job_skip.trgt_de} — PK 의 일부라 <b>없으면 기록 자체가 불가능</b>하다. */
    public LocalDate trgtDe() {
        return trgtDe;
    }

    /** {@code batch.tb_job_skip.note}. */
    public String note() {
        return note;
    }
}
