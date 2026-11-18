package jeju.datahub.batch.geocode;

import java.util.Optional;

/**
 * 지오코딩 1건의 결과. <b>"못 찾음"과 "지금은 못 물어봄"을 구분하기 위해</b> 존재한다
 * (D8 2026-08-04 개정).
 *
 * <p>이전에는 {@code Optional<Coordinate>} 하나로 반환했는데, {@link Optional#empty()} 가 되는 경로가
 * 성질이 정반대인 둘이었다.
 *
 * <table>
 *   <tr><th>{@link Status}</th><th>실제 상황</th><th>성질</th><th>캐시</th></tr>
 *   <tr><td>{@link Status#FOUND}</td><td>좌표를 얻음</td><td>-</td><td>좌표 캐싱</td></tr>
 *   <tr><td>{@link Status#NOT_FOUND}</td><td>제공자가 그 주소가 없다고 답함</td><td><b>영구</b> — 다시 물어도 없다</td>
 *       <td>실패 확정으로 캐싱(재조회 안 함)</td></tr>
 *   <tr><td>{@link Status#TRANSIENT}</td><td>네트워크·타임아웃·일시 {@code ERROR} 가 재시도를 모두 소진</td>
 *       <td><b>일시</b> — 나중엔 찾을 수 있다</td>
 *       <td>DB 에 관측용으로만 남기고 <b>히트로 치지 않는다</b></td></tr>
 * </table>
 *
 * <p>{@code TRANSIENT} 를 실패로 캐싱하면 다음 실행에서 캐시 히트로 API 호출 자체를 건너뛰고,
 * 스킵 테이블의 {@code NOT EXISTS} 영구 제외까지 겹쳐 <b>장애 구간에 걸린 주소가 재실행해도 영영
 * 되살아나지 않는다.</b> 그래서 상태를 타입으로 들고 다닌다.
 *
 * <p><b>호출자(Processor, Step 6) 계약</b> — 이 세 상태는 처리가 각각 다르다.
 * <ul>
 *   <li>{@code FOUND} → 좌표를 실어 적재한다.</li>
 *   <li>{@code NOT_FOUND} → {@code GEOCODE_FAILED} 로 스킵 테이블에 기록한다(영구 제외).</li>
 *   <li>{@code TRANSIENT} → <b>스킵 기록 대상이 아니다.</b> 그 행만 이번 실행에서 빼고(Processor
 *       {@code null} 반환 = filter) 다음 실행에 Reader 가 다시 집어 오게 둔다.</li>
 * </ul>
 *
 * <h2>{@link #detail()} — 실패 원인을 사람이 읽을 수 있게 (Step 7-4)</h2>
 * 상태만으로는 "왜 못 찾았는지"를 알 수 없다. 스킵 테이블({@code tb_cvlcpt_geocode_skip.note})의
 * 원문이 되므로 <b>어느 단계까지 갔고 무엇을 받았는지</b>를 담는다 —
 * {@code parcel=NOT_FOUND, road=NOT_FOUND, stripped(제주...상도리 3204)=NOT_FOUND} 형태다.
 * 1·2단은 {@link VworldGeocodingClient}, 3단(부번 제거)은 {@link CompositeGeocoder} 가 덧붙인다.
 *
 * @param status 결과 구분
 * @param point  좌표. {@code FOUND} 일 때만 존재한다
 * @param detail 실패 단계 내역(스킵 {@code note} 원문). 성공이거나 내역이 없으면 {@code null}
 */
public record GeocodeOutcome(Status status, Coordinate point, String detail) {

    /** 지오코딩 결과 구분. 이름이 그대로 {@code batch.tb_geocode_cache.fail_rsn} 값이 된다. */
    public enum Status {
        /** 좌표를 얻었다. */
        FOUND,
        /** 제공자가 "그 주소는 없다"고 답했다 — 다시 물어도 결과가 같다. */
        NOT_FOUND,
        /** 일시 장애로 재시도를 모두 소진했다 — 나중에 다시 물어봐야 한다. */
        TRANSIENT
    }

    public GeocodeOutcome {
        if ((status == Status.FOUND) != (point != null)) {
            // FOUND 인데 좌표가 없거나, 실패인데 좌표가 있는 조합은 호출자가 오독한다.
            throw new IllegalArgumentException("status 와 point 가 맞지 않습니다: " + status);
        }
    }

    public static GeocodeOutcome found(Coordinate point) {
        return new GeocodeOutcome(Status.FOUND, point, null);
    }

    public static GeocodeOutcome notFound() {
        return notFound(null);
    }

    /** @param detail 어느 단계에서 무엇을 받았는지 — 스킵 {@code note} 원문이 된다(Step 7-4) */
    public static GeocodeOutcome notFound(String detail) {
        return new GeocodeOutcome(Status.NOT_FOUND, null, detail);
    }

    /** {@code transient} 는 예약어라 메서드명으로 못 쓴다. */
    public static GeocodeOutcome transientFailure() {
        return transientFailure(null);
    }

    /** @param detail 어느 단계에서 무엇을 받았는지. {@code TRANSIENT} 는 스킵 기록 대상이 아니라 로그용이다 */
    public static GeocodeOutcome transientFailure(String detail) {
        return new GeocodeOutcome(Status.TRANSIENT, null, detail);
    }

    /**
     * 상태·좌표는 그대로 두고 단계 내역만 바꾼 사본.
     *
     * <p>폴백 체인이 진행되며 앞 단계 결과에 뒤 단계를 덧붙이는 용도다(Step 7-4). 캐시 히트처럼
     * 내역이 유실된 경로에서 "어디서 온 실패인지"를 채워 넣는 데도 쓴다.
     */
    public GeocodeOutcome withDetail(String detail) {
        return new GeocodeOutcome(status, point, detail);
    }

    public boolean isFound() {
        return status == Status.FOUND;
    }

    /** 이번 실행에서 처리 불가 — 실패로 굳히면 안 되는 상태다. */
    public boolean isTransient() {
        return status == Status.TRANSIENT;
    }

    /** 좌표(없으면 {@link Optional#empty()}). {@code NOT_FOUND} 와 {@code TRANSIENT} 를 구분하지 않으므로 사유가 필요하면 {@link #status()} 를 본다. */
    public Optional<Coordinate> coordinate() {
        return Optional.ofNullable(point);
    }

    /**
     * 캐시에 남길 실패 사유. 성공이면 {@code null}({@code fail_rsn} 컬럼이 NULL 이 된다).
     *
     * @see GeocodeCacheRepository
     */
    public String failReason() {
        return isFound() ? null : status.name();
    }
}
