package jeju.datahub.batch.cvlcpt;

/**
 * 이 행은 <b>실패 확정</b>이니 건너뛰라는 신호 (계획서 Step 6-2 · 7-2).
 *
 * <p>Step 조립에서 {@code .faultTolerant().skip(GeocodeSkippableException.class)} 대상이며,
 * {@code GeocodeSkipListener} 가 이 예외를 받아 {@code batch.tb_geocode_skip} 에 기록한다.
 * 그 테이블은 Reader 가 {@code NOT EXISTS} 로 <b>영구 제외</b>하는 용도라(D8), 다시 시도해 볼 가치가
 * 있는 실패를 여기 담으면 안 된다.
 *
 * <table>
 *   <tr><th>상황</th><th>처리</th></tr>
 *   <tr><td>{@code NOT_FOUND} — 제공자가 "그 주소는 없다"</td><td><b>이 예외</b>({@link Reason#GEOCODE_FAILED})</td></tr>
 *   <tr><td>공간매칭 실패 — 좌표가 어느 읍면동에도 안 들어감</td><td><b>이 예외</b>({@link Reason#EMD_UNRESOLVED})</td></tr>
 *   <tr><td>{@code TRANSIENT} — 일시 장애</td><td>Processor 가 {@code null} 반환(filter). <b>이 예외 아님</b></td></tr>
 *   <tr><td>미분류(99)</td><td>{@code 99} 로 정상 적재. <b>이 예외 아님</b></td></tr>
 *   <tr><td>쿼터 초과·연속 장애</td><td>Job 중단 예외로 전파. <b>이 예외 아님</b></td></tr>
 * </table>
 *
 * <p>스킵 테이블의 컬럼({@code trgt_id}, {@code fail_rsn}, {@code note}, {@code trgt_addr})을
 * 그대로 필드로 들고 다닌다 — 리스너가 원본 행에 다시 접근하지 않아도 기록할 수 있게 하기 위함이다.
 * 대상 식별자는 민원의 경우 {@code cvlcpt_id} 다(2026-09-01 원천 이관으로 {@code dclr_no} 에서 개명).
 */
public class GeocodeSkippableException extends RuntimeException {

    /**
     * 스킵 사유. 이름이 그대로 {@code batch.tb_geocode_skip.fail_rsn} 값이 된다
     * ({@code VARCHAR(30)}).
     */
    public enum Reason {
        /** 지오코딩 3단 폴백이 모두 {@code NOT_FOUND} — 제공자 주소DB 에 없는 주소다. */
        GEOCODE_FAILED,
        /** 좌표는 얻었으나 2단 공간매칭(포함 → 50m 버퍼)이 모두 실패 — 행정동 경계 밖이다. */
        EMD_UNRESOLVED
    }

    private final Reason reason;
    private final String cvlcptId;
    private final String address;
    private final String note;

    public GeocodeSkippableException(Reason reason, String cvlcptId, String address, String note) {
        super("[" + reason + "] cvlcpt_id=" + cvlcptId + " addr=" + address + " note=" + note);
        this.reason = reason;
        this.cvlcptId = cvlcptId;
        this.address = address;
        this.note = note;
    }

    public Reason reason() {
        return reason;
    }

    /** {@code tb_geocode_skip.trgt_id} (PK 의 일부). 민원에서는 {@code cvlcpt_id} 다. */
    public String cvlcptId() {
        return cvlcptId;
    }

    /** {@code tb_geocode_skip.trgt_addr} — 실패한 주소. 사후 원인 분석의 핵심 단서다. */
    public String address() {
        return address;
    }

    /** {@code tb_geocode_skip.note} — 사람이 읽을 부연(좌표 등). */
    public String note() {
        return note;
    }
}
