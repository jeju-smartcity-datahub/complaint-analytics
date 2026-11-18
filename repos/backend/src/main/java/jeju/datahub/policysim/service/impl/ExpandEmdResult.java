package jeju.datahub.policysim.service.impl;

/**
 * 읍면동 1곳의 확충 후 값. 값은 반올림 전 원값이다 — 반올림·퍼센트 변환은 응답 조립 한 곳에서만 한다.
 *
 * <p>구현이 범위 Σ 를 낼 때 쓰는 재료다 — {@code R} 의 Σ 는 {@code NO_ENF} 제외 전 읍면동,
 * {@code H0_T}·{@code H1_T} 의 Σ 는 {@code base.s() > 0} 인 읍면동만이다(명세 10장).
 *
 * @param base       기준선 (C0·E0·U0·S·k·H0·calcStts)
 * @param weight     배분 가중치 w_e = E0_e ÷ ΣE0 — NO_ENF 이거나 범위 ΣE0 = 0 이면 0
 * @param dltSlot    ΔS_e = ΔS_eff × w_e
 * @param dltIlgl    ΔE_e = min(E0_e, α_e × ΔS_e)
 * @param e1         E1 = E0 − ΔE
 * @param u1         U1 = U0 + ΔE
 * @param s1         S1 = S + ΔS_e
 * @param c1         C1 = k × E1 — {@code NO_ENF}(k 없음)면 null
 * @param h1         H1 = (U1 + E1) ÷ S1 — 기준 공급 S = 0 이면 null (공급 0 은 혼잡 계산 제외. S1 &gt; 0 이어도 내지 않는다)
 * @param saturated  흡수 포화(SATURATED) — α_e × ΔS_e ≥ E0_e. 🔴 E0_e &gt; 0 인 읍면동만 판정(NO_ENF 는 항상 false)
 */
public record ExpandEmdResult(
        PolicySimEmdBaseline base,
        double weight,
        double dltSlot,
        double dltIlgl,
        double e1,
        double u1,
        double s1,
        Double c1,
        Double h1,
        boolean saturated) {
}
