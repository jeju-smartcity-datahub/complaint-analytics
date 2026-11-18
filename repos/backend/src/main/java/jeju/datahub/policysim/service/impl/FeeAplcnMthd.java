package jeju.datahub.policysim.service.impl;

/**
 * 요금제 적용 방식 (요청 {@code feeAplcnMthd}). 저장 칸 {@code fee_aplcn_mthd} 에 코드값 그대로 담긴다.
 *
 * <p>방식이 적용 대상 주차장({@code selectFeeTargetSlot} 의 {@code crgYn})과 수요 감소식을 함께 고른다.
 */
public enum FeeAplcnMthd {

    /** 요금 인상 — 유료 주차장 대상 · ΔU = U0 × ε × p (ε &lt; 0) */
    RAISE("Y"),

    /** 무료 → 유료 전환 — 무료 주차장 대상 · ΔU = −U0 × λ */
    CONVERT("N");

    private final String crgYn;

    FeeAplcnMthd(String crgYn) {
        this.crgYn = crgYn;
    }

    /** 매퍼 {@code selectFeeTargetSlot} 의 {@code crgYn} — RAISE → 'Y'(유료) / CONVERT → 'N'(무료) */
    public String crgYn() {
        return crgYn;
    }
}
