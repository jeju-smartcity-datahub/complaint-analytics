package jeju.datahub.policysim.service.impl;

/**
 * 대상 읍면동 1곳의 요금제 고유 값 (TSK-4039 Step 3). 값은 반올림 전 원값이다.
 * E1·U1·S1·C1·H1·흡수(ΔE_abs = {@code dltIlgl})·포화는 확충과 같은 행 형식 {@link ExpandEmdResult} 에 있다 — 같은 순서의 짝이다.
 *
 * <p>🔴 <b>{@code NO_ENF}(E0 = 0) 대상은 V·흡수·전이가 0</b> 이다(사용자 결정 2026-10-11) — k 가 없어 전이분을 C1 로 바꿀 수 없다.
 * {@code aplcnTrgtSlot} 은 면수 집계 그대로 둔다.
 *
 * @param emdCd         읍면동 코드
 * @param aplcnTrgtSlot 적용 대상 면수 (Step 2 · 급지 밖이면 0)
 * @param lvngVhcl      V_e = 면수 × occ × (λ | −ε × p) — NO_ENF 면 0
 * @param trnsfLocal    대상 내 전이 τ × τ_local × V_e
 * @param trnsfBack     인접이 없어 되돌려 받은 인접분 τ × (1 − τ_local) × ΣV × w_e — 인접이 있으면 0
 */
public record FeeEmdResult(
        String emdCd,
        long aplcnTrgtSlot,
        double lvngVhcl,
        double trnsfLocal,
        double trnsfBack) {
}
