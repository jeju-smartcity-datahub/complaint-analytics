package jeju.datahub.batch.rptp;

import java.time.LocalDate;

/**
 * 이 설정 <b>한 건만</b> 건너뛰라는 신호 (TSK-4027 Step 8-3). 선례는
 * {@code RiskAlertSkippableException} 이다.
 *
 * <p>Step 조립(Step 9)에서 {@code .faultTolerant().skip(ReportAutoCrtSkippableException.class)}
 * 대상이며, {@link ReportAutoCrtSkipListener} 가 받아 {@code batch.tb_job_skip}
 * ({@code job_se='RPTP'})에 기록한다.
 *
 * <h2>🔴 스킵 대상을 좁게 못박는다 — 401·403·503 은 이 예외가 아니다</h2>
 * <table>
 *   <tr><th>응답</th><th>처리</th></tr>
 *   <tr><td>400</td><td><b>이 예외</b> + 사유 기록({@link #recordable()}={@code true})</td></tr>
 *   <tr><td>5xx · 타임아웃 · 연결 실패</td><td><b>이 예외</b> + <b>사유 기록 안 함</b>({@link #recordable()}={@code false})</td></tr>
 *   <tr><td>401 · 403 · 503</td><td>🔴 <b>이 예외가 아니다.</b> 키 오류·백엔드 다운이라 전 설정이 같은 이유로 실패한다 → Job FAILED</td></tr>
 *   <tr><td>200 {@code created=false}</td><td>정상 경로(이미 있음). 예외 아님</td></tr>
 * </table>
 * ⚠️ {@code skip(Exception.class)} 같은 광범위 스킵을 쓰지 않는다 — 삼키면 <b>보고서가 조용히 비고
 * Job 은 COMPLETED</b> 로 끝난다({@code rule_batch-project.md} 명시 금지).
 *
 * <h2>🔴 일시 실패는 사유를 굳히지 않는다 ({@link #recordable()}={@code false})</h2>
 * 5xx·타임아웃은 <b>다음 실행이 다시 집어 와야</b> 한다(계획서 D8-②). 스킵 표에 굳히면 그 주기가
 * 영구 유실된 것처럼 남아 원인 추적을 오히려 방해한다.
 *
 * <h2>🔴 {@code stngId}·{@code baseYmd} 를 실어 나른다</h2>
 * 스킵 표 PK 가 {@code (job_se, trgt_id, trgt_de)} 3칸인데 <b>리스너는 Job 파라미터를 모른다.</b>
 * 그래서 예외가 직접 들고 간다(선례 {@code RiskAlertSkippableException} 이 판정일을 들고 있는 것과 같다).
 *
 * <h2>🔒 사유는 화이트리스트다 — 응답 본문을 넣지 않는다</h2>
 * 본문 원문을 {@code fail_rsn}·{@code note} 에 넣으면 이력 표가 유출 경로가 된다. 아래 4종 밖의
 * 값을 쓰지 않으며, 늘리려면 <b>여기 상수로 먼저 등재</b>한다({@code VARCHAR(30)} 상한도 함께 본다).
 */
public class ReportAutoCrtSkippableException extends RuntimeException {

    /**
     * 백엔드가 <b>400</b> 으로 확정 거절했다 — 그 설정만의 문제다.
     *
     * <p>⚠️ 400 의 세부 사유를 배치가 더 좁힐 수 없다. 백엔드는 {@code ResponseStatusException} 의
     * {@code reason} 을 본문에 싣지 않고({@code server.error.include-message} 기본값이
     * {@code never} 다), 기계가 읽을 코드 필드도 없다. 🔴 <b>한글 메시지 문자열을 매칭해 가르지
     * 않는다</b> — 레포를 가로지르는 문구 의존이라 백엔드가 문장 하나만 고쳐도 조용히 깨진다.
     * 원인은 백엔드 로그가 답한다(같은 시각·같은 {@code stngId}).
     */
    public static final String BAD_REQUEST = "BAD_REQUEST";

    /**
     * 분석 결과 0건이라 보고서를 만들 수 없었다({@code HOTSPOT} 전용 — {@code CVLCPT} 는 0건도 정상이다).
     *
     * <p>⚠️ <b>지금은 도달하지 않는다</b> — 위 {@link #BAD_REQUEST} 의 이유로 400 을 세분할 수 없어서다.
     * 백엔드가 기계가 읽을 오류 코드를 주기 시작하면 그때 이 값으로 가른다(화이트리스트를 미리 열어 둔다).
     */
    public static final String NO_RESULT = "NO_RESULT";

    /**
     * 요청이 설정과 어긋났다(설정이 그 사이 수정·삭제됨).
     *
     * <p>⚠️ {@link #NO_RESULT} 와 같은 이유로 지금은 도달하지 않는다.
     */
    public static final String STNG_MISMATCH = "STNG_MISMATCH";

    /** 위 어디에도 넣을 수 없는 사유. {@link ReportAutoCrtSkipListener} 가 마지막 안전망으로 쓴다. */
    public static final String UNKNOWN = "UNKNOWN";

    private final String reason;
    private final Long stngId;
    private final LocalDate baseYmd;
    private final String note;
    private final boolean recordable;

    private ReportAutoCrtSkippableException(String reason, Long stngId, LocalDate baseYmd,
                                            String note, boolean recordable, Throwable cause) {
        super("[" + (recordable ? reason : "TRANSIENT") + "] stngId=" + stngId
                + " trgt_de=" + baseYmd + " " + note, cause);
        this.reason = reason;
        this.stngId = stngId;
        this.baseYmd = baseYmd;
        this.note = note;
        this.recordable = recordable;
    }

    /**
     * <b>확정 실패</b> — 사유와 함께 {@code batch.tb_job_skip} 에 남긴다(현재 유일한 경로는 400).
     *
     * @param reason  화이트리스트 사유 상수. {@code fail_rsn}({@code VARCHAR(30)})에 그대로 들어간다
     * @param command 스킵된 요청. {@code trgt_id}({@code stngId})와 진단 문구의 출처다
     * @param baseYmd Job 파라미터 {@code baseYmd} — 스킵 표의 {@code trgt_de}
     * @param status  받은 HTTP 상태코드. 🔒 <b>본문은 싣지 않는다</b>
     */
    public static ReportAutoCrtSkippableException confirmed(
            String reason, ReportAutoCrtCommand command, LocalDate baseYmd, int status) {
        return new ReportAutoCrtSkippableException(
                reason, command.stngId(), baseYmd, note(command, status), true, null);
    }

    /**
     * <b>일시 실패</b>(5xx·타임아웃·연결 실패) — 스킵하되 🔴 <b>사유를 굳히지 않는다</b>.
     * 다음 실행이 다시 집어 온다(D8-②).
     *
     * @param status 받은 HTTP 상태코드. 응답 자체를 받지 못했으면 {@code 0}
     * @param cause  타임아웃·연결 실패의 원인 예외(있으면)
     */
    public static ReportAutoCrtSkippableException transientFailure(
            ReportAutoCrtCommand command, LocalDate baseYmd, int status, Throwable cause) {
        return new ReportAutoCrtSkippableException(
                null, command.stngId(), baseYmd, note(command, status), false, cause);
    }

    /** 🔒 진단 문구 — 우리가 보낸 값과 상태코드만 담는다(응답 본문 금지). */
    private static String note(ReportAutoCrtCommand command, int status) {
        return "type=" + command.rptpTypeCd()
                + " trgtPrdCd=" + command.trgtPrdCd()
                + " 구간=" + command.bgngYmd() + "~" + command.endYmd()
                + " httpStatus=" + status;
    }

    /**
     * {@code batch.tb_job_skip.fail_rsn}. 🔴 일시 실패면 {@code null} 이다 —
     * {@link #recordable()} 이 {@code false} 라 애초에 기록하지 않는다.
     */
    public String reason() {
        return reason;
    }

    /** {@code batch.tb_job_skip.trgt_id} — 이 잡에서는 설정 PK({@code stng_id})를 문자열로 쓴다. */
    public Long stngId() {
        return stngId;
    }

    /** {@code batch.tb_job_skip.trgt_de} — 실행 기준일. PK 의 일부라 <b>없으면 기록 자체가 불가능</b>하다. */
    public LocalDate baseYmd() {
        return baseYmd;
    }

    /** {@code batch.tb_job_skip.note} — 우리가 보낸 값과 상태코드(사람이 읽을 진단 정보). */
    public String note() {
        return note;
    }

    /**
     * 스킵 표에 <b>남길</b> 실패인가. 🔴 {@code false}(일시 실패)면 리스너가 행을 만들지 않는다 —
     * 굳히면 그 주기가 영구 유실된 것처럼 남는다.
     */
    public boolean recordable() {
        return recordable;
    }
}
