package jeju.datahub.lifezone.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 구역명 캐시 미리 채우기 1회 결과. ({@code POST /api/internal/zone-names/prefill})
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ZoneNmPrefillResultVO {

    /** 격자크기(m) */
    private int gridSz;
    /** 대상 격자 수 (캐시 유무 무관) */
    private int targetCnt;
    /** 이번 호출에서 캐시에 저장된 격자 수 (POI · 역지오코딩 · 확정 실패 NOT_FOUND) */
    private int resolvedCnt;
    /** 저장되지 않은 격자 수 (일시 실패 · 연속 실패 차단 · 상한) — 다음 호출이 다시 시도한다 */
    private int transientCnt;
    /** 호출 뒤 캐시가 아직 없는 대상 격자 수 */
    private int remainingCnt;
    /** 연속 일시 실패 차단이 걸렸는가 */
    private boolean tripped;
}
