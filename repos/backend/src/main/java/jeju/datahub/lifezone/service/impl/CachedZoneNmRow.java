package jeju.datahub.lifezone.service.impl;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역명 캐시 일괄 조회 1행 — {@link LifezoneAnalysisMapper#selectCachedZoneNames} 결과 (TSK-4339).
 * 보고서 셀 복원이 저장된 격자 중심 좌표로 캐시 이름을 되찾을 때 쓴다.
 */
@Getter
@Setter
@ToString
public class CachedZoneNmRow {

    /** 위도 키 (소수 5자리 — {@code numeric(9,5)}) */
    private BigDecimal latKey;

    /** 경도 키 (소수 5자리 — {@code numeric(10,5)}) */
    private BigDecimal lotKey;

    /** 캐시 이름 (출처 POI·REVERSE_GEOCODE 만) */
    private String zoneNm;
}
