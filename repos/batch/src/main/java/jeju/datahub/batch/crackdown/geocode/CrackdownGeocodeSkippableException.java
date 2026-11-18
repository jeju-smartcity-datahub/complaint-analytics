package jeju.datahub.batch.crackdown.geocode;

/**
 * 이 주소는 <b>실패 확정</b>이니 건너뛰라는 신호.
 *
 * <p>{@code crackdownGeocodeStep} 의 {@code .faultTolerant().skip(...)} 대상이며,
 * {@link CrackdownGeocodeSkipListener} 가 받아 {@code batch.tb_geocode_skip(src_se='CRDN')} 에 기록한다.
 * Reader 가 그 테이블을 {@code NOT EXISTS} 로 영구 제외하므로 다시 시도할 가치가 있는 실패를 담지 않는다.
 *
 * <table>
 *   <caption>실패 갈래</caption>
 *   <tr><th>상황</th><th>처리</th></tr>
 *   <tr><td>{@code NOT_FOUND} · 장소명</td><td><b>이 예외</b>({@link Reason#GEOCODE_FAILED})</td></tr>
 *   <tr><td>좌표가 어느 읍면동에도 안 들어감</td><td><b>이 예외</b>({@link Reason#EMD_UNRESOLVED})</td></tr>
 *   <tr><td>{@code TRANSIENT}</td><td>Processor 가 {@code null}(filter). <b>이 예외 아님</b></td></tr>
 *   <tr><td>쿼터 초과</td><td>{@code GeocodeQuotaExceededException} 전파 → Job FAILED. <b>이 예외 아님</b></td></tr>
 * </table>
 *
 * <p>민원 {@code GeocodeSkippableException} 과 같은 형태지만 <b>행 ID 필드가 없다</b> — 주소 단위라
 * {@code trgt_id} 는 리스너가 주소의 SHA-256 으로 만든다(D7).
 *
 * <p>🔒 예외 메시지에 주소 원문을 싣지 않는다 — 단속 장소 값은 로그에 남기지 않는 것이 이 도메인의 관례다
 * ({@code CrackdownOriginRow} "로깅"). 주소는 {@link #address()} 로만 들고 다니며 스킵 테이블
 * {@code trgt_addr} 에만 들어간다.
 */
public class CrackdownGeocodeSkippableException extends RuntimeException {

    /** 스킵 사유. 이름이 그대로 {@code batch.tb_geocode_skip.fail_rsn} 값이 된다 — 민원과 같은 문자열. */
    public enum Reason {
        /** 지오코딩 폴백이 모두 {@code NOT_FOUND}, 또는 장소명이라 지오코딩하지 않음. */
        GEOCODE_FAILED,
        /** 좌표는 얻었으나 행정동 2단 매칭(포함 → 50m 버퍼)이 모두 실패 — 제주 행정동 밖이다(D6). */
        EMD_UNRESOLVED
    }

    private final Reason reason;
    private final String address;
    private final String note;

    /**
     * @param reason  사유
     * @param address {@code crdn_addr} 원문 — {@code trgt_addr} 와 {@code trgt_id}(SHA-256) 의 원천
     * @param note    단계 판정만(값 없이). 좌표·거리는 허용
     */
    public CrackdownGeocodeSkippableException(Reason reason, String address, String note) {
        super("[" + reason + "] note=" + note);
        this.reason = reason;
        this.address = address;
        this.note = note;
    }

    public Reason reason() {
        return reason;
    }

    /** {@code tb_geocode_skip.trgt_addr} — 실패한 주소 원문. */
    public String address() {
        return address;
    }

    /** {@code tb_geocode_skip.note} — 단계 판정(값 없이). */
    public String note() {
        return note;
    }
}
