package jeju.datahub.batch.pblpklt;

/**
 * 가공 단계 실패 확정 신호.
 *
 * <p>Step 조립에서 {@code .faultTolerant().skip(PblpkltGeocodeSkippableException.class)} 대상이며,
 * {@link PblpkltSkipListener} 가 받아 {@code batch.tb_pblpklt_skip} 에 기록한다. Reader 의
 * {@code NOT EXISTS} 가 영구 제외하므로 "다시 시도해도 결과가 같은 건"만 여기로 보낸다 —
 * 지오코딩 4단 실패 / 공간매칭 실패 2종. 일시 장애({@code TRANSIENT})는 Processor 가 {@code null}
 * 반환(filter) — 스킵 테이블에 남기지 않는다.
 */
public class PblpkltGeocodeSkippableException extends RuntimeException {

    /**
     * 스킵 사유. 이름이 그대로 {@code batch.tb_pblpklt_skip.fail_rsn} 값이 된다
     * ({@code VARCHAR(30)}).
     */
    public enum Reason {
        /** 지오코딩 4단 폴백이 모두 {@code NOT_FOUND}. */
        GEOCODE_FAILED,
        /** 좌표는 얻었으나 공간매칭(1차 → 50m 2차) 모두 실패. */
        EMD_UNRESOLVED
    }

    private final Reason reason;
    private final String originPk;
    private final String address;
    private final String note;

    public PblpkltGeocodeSkippableException(Reason reason, String originPk, String address, String note) {
        super("[" + reason + "] origin_pk=" + originPk + " addr=" + address + " note=" + note);
        this.reason = reason;
        this.originPk = originPk;
        this.address = address;
        this.note = note;
    }

    public Reason reason() {
        return reason;
    }

    public String originPk() {
        return originPk;
    }

    public String address() {
        return address;
    }

    public String note() {
        return note;
    }
}