package jeju.datahub.policysim.service.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 요금제 적용 대상 — 읍면동 1곳의 적용 대상 주차면수 (매퍼 결과 전용).
 *
 * <p>{@code 대상 지역 ∩ 급지 ∩ 유무료}(RAISE 유료 / CONVERT 무료) 공영주차장(민영 제외·미소멸)의 면수 합이다.
 * 🔴 이 행 목록을 대상 지역(R 분모·인접 산출 기준)으로 쓰지 않는다 — 대상은 조회 범위 ∩ 급지를 서비스가
 * 기준선에서 거른 것이고, 면수 행이 없는 대상은 면수 0 이다.
 */
@Getter
@Setter
@ToString
public class FeeTargetRow {

    private String emdCd;

    private String emdNm;

    /** 적용 대상 주차면수 — 해당 주차장이 없으면 0 (LEFT JOIN) */
    private long aplcnTrgtSlot;
}
