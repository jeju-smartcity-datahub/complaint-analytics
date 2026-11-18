package jeju.datahub.lifezone.service;

/**
 * 구역명 캐시 미리 채우기. (TSK-4339 Step 5)
 *
 * <p>배치({@code zoneNmPrefillJob})가 내부 API 로 반복 호출한다. 이름 짓기·캐시 저장 규칙은
 * {@code ZoneNameResolver} 그대로다 — 이 서비스는 캐시에 직접 쓰지 않는다.
 */
public interface ZoneNmPrefillService {

    /**
     * 캐시가 없는 대상 격자 중 최대 {@code limit} 개의 구역명을 조회해 캐시에 채운다.
     *
     * @param gridSz 격자크기(m) — 1000/500/250/100
     * @param limit  이번 호출의 외부 조회 상한(1~200)
     */
    ZoneNmPrefillResultVO prefill(int gridSz, int limit);
}
