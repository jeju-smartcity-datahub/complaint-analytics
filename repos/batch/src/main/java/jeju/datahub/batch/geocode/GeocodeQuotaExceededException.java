package jeju.datahub.batch.geocode;

/**
 * 외부 지오코딩 API 가 <b>재시도해도 절대 해결되지 않는</b> 호출 한도 초과를 응답할 때 던진다
 * (VWorld {@code error.code = OVER_REQUEST_LIMIT}).
 *
 * <p>"그 지번이 없음"({@code NOT_FOUND})과는 완전히 다르다 — 이건 "이 API 키로 더 이상 아무것도
 * 조회할 수 없다"는 뜻이다. 따라서 <b>재시도 루프를 태우지 않고</b> 즉시 상위로 전파해 Job 을
 * FAILED 로 끝낸다(D9).
 *
 * <p>⚠️ 이 예외로 중단된 행은 {@code batch.tb_cvlcpt_geocode_skip} 에 <b>기록하지 않는다.</b>
 * 실패 확정이 아니라 "오늘은 못 부름"일 뿐이라, 기록하면 Reader 의 {@code NOT EXISTS} 에 걸려
 * 영구 제외되고 데이터가 유실된다. 쿼터가 회복된 뒤 재실행하면 이어서 처리된다.
 */
public class GeocodeQuotaExceededException extends RuntimeException {

    public GeocodeQuotaExceededException(String message) {
        super(message);
    }
}
