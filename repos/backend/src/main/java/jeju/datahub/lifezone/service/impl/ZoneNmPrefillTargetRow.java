package jeju.datahub.lifezone.service.impl;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 미리 채우기 대상 격자 1행 — {@link ZoneNmPrefillMapper#selectTargets} 결과. */
@Getter
@Setter
@NoArgsConstructor
public class ZoneNmPrefillTargetRow {

    private long gx;
    private long gy;
    /** 격자 중심 위도(EPSG:4326, 소수 7자리) — 수급 매퍼 ctr_lat 과 같은 식 */
    private BigDecimal ctrLat;
    /** 격자 중심 경도(EPSG:4326, 소수 7자리) */
    private BigDecimal ctrLot;
    /** 셀 안 점들의 최빈 읍면동명. 행정동 미매칭이면 null */
    private String emdNm;
}
