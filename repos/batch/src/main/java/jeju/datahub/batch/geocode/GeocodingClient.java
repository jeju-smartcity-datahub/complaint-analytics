package jeju.datahub.batch.geocode;

/**
 * 주소 → 좌표 변환 포트.
 *
 * <p>지금 구현체는 {@link VworldGeocodingClient} 하나뿐이다(D7 개정 2026-08-03 — Kakao 폴백 제거).
 * 그럼에도 인터페이스를 남겨 둔 이유는 <b>제공자 교체 여지</b> 때문이다 — Kakao 를 되살리거나 다른
 * 제공자로 바꿀 때 이 포트의 구현을 하나 더 만들고 {@link CompositeGeocoder} 의 체인만 바꾸면 된다.
 *
 * <p>이 패키지는 Reader/Job 에 의존하지 않는다(D5). 원천이 외부 수집 API 로 바뀌어도
 * 그대로 재사용된다.
 *
 * <p><b>🔄 2026-08-04 시그니처 변경</b> — 반환 타입이 {@code Optional<Coordinate>} 에서
 * {@link GeocodeOutcome} 으로 바뀌었다. {@code Optional.empty()} 하나로는 "그 주소가 없다"와
 * "지금 제공자가 죽어 못 물어봤다"를 구분할 수 없어, 후자를 실패로 캐싱해 영구히 굳혀 버렸다
 * (D8 개정). 새 구현체는 이 둘을 반드시 구분해 돌려줘야 한다.
 */
public interface GeocodingClient {

    /**
     * 주소를 좌표로 변환한다.
     *
     * @param address 지번 또는 도로명 주소
     * @return {@link GeocodeOutcome} — {@code FOUND} / {@code NOT_FOUND}(그 주소가 없음, 영구) /
     *         {@code TRANSIENT}(일시 장애로 재시도 소진, 나중에 재조회해야 함).
     *         <b>못 찾은 것은 예외가 아니다</b> — 다음 폴백 단계로 넘어가야 하는 정상 흐름이다
     * @throws GeocodeQuotaExceededException 호출 한도 초과 — 재시도해도 회복되지 않으므로 즉시 전파된다
     */
    GeocodeOutcome geocode(String address);
}
