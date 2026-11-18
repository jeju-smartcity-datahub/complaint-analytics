package jeju.datahub.policysim.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 정책 시뮬레이션 — 주차장 확충 변화식 (TSK-4037 Step 3 · 명세 6장).
 *
 * <pre>
 * ΔS_eff = 공한지: N × (1 − r_ret × T_m ÷ 12)  /  복층화·신규: N
 * w_e    = E0_e ÷ Σ E0_j      (범위 = 단일 읍면동 · 시군구 · 제주 전체. NO_ENF 는 w_e = 0)
 * ΔS_e   = ΔS_eff × w_e
 * α_e    = α × min(1, occ ÷ occ_th)
 * ΔE_e   = min(E0_e, α_e × ΔS_e)
 * E1_e   = E0_e − ΔE_e ;  U1_e = U0_e + ΔE_e ;  S1_e = S_e + ΔS_e
 * C1_e   = k_e × E1_e            (NO_ENF 면 null)
 * H1_e   = (U1_e + E1_e) ÷ S1_e  (기준 S_e = 0 이면 null)
 * </pre>
 *
 * <p>🔴 <b>인접 지역 변화는 없다</b>(명세 6.1 — 풍선효과는 이면도로·요금제에만 있다).
 * <p>🔴 <b>{@code occ} 가 설정 상수라 α_e 가 모든 읍면동에서 같다</b> — 명세는 격자별 occ_g 를 전제했으나 데이터가 없다(알려진 한계).
 * <p>🔴 <b>흡수 포화(SATURATED, DEC-025)</b> — α_e × ΔS_e ≥ E0_e 면 ΔE_e = E0_e 로 잘려 E1_e = 0 이다. 값은 그대로 두고
 * 플래그만 붙인다. 판정은 <b>E0_e &gt; 0 인 읍면동만</b> 한다 — NO_ENF 는 ΔS_e = 0 이라 {@code 0 ≥ 0} 이 참이 되어
 * 빼지 않으면 시군구·제주 전체 조회가 항상 포화로 잡힌다. NO_SPLY(S = 0, E0 &gt; 0)는 배분·판정 대상이다(명세 10장).
 *
 * <p>산식은 DB 없이 검증할 수 있도록 정적 메서드로 둔다(Step 2 {@link PolicySimBaselineCalculator} 와 같은 구조).
 * 출력 지표(R·I·비용 등급·범위 calc_stts_cd)는 Step 4 다.
 */
@Component
public class ExpandPolicyCalculator {

    private static final double MONTHS_PER_YEAR = 12.0;

    /** 기준선 전체에서 범위를 골라 계산한다 — 범위 규칙은 {@link #scope} */
    public ExpandResult calculate(PolicySimBaseline baseline, ExpandMethod method, int addPkltSlot, int predcMm,
                                  String sggNm, String emdCd) {
        return compute(scope(baseline.emds(), sggNm, emdCd), baseline.params(), method, addPkltSlot, predcMm);
    }

    /**
     * 조회 범위 — {@code emdCd} 가 있으면 그 읍면동 하나, 없고 {@code sggNm} 이 있으면 그 시군구 전체, 둘 다 없으면 제주 전체(74곳).
     *
     * @throws IllegalArgumentException 범위에 읍면동이 하나도 없을 때(없는 코드·시군구명) — 빈 범위를 0 으로 계산하지 않는다
     */
    public static List<PolicySimEmdBaseline> scope(List<PolicySimEmdBaseline> all, String sggNm, String emdCd) {
        List<PolicySimEmdBaseline> picked;
        if (emdCd != null) {
            picked = all.stream().filter(e -> emdCd.equals(e.emdCd())).toList();
        } else if (sggNm != null) {
            picked = all.stream().filter(e -> sggNm.equals(e.sggNm())).toList();
        } else {
            picked = all;
        }
        if (picked.isEmpty()) {
            throw new IllegalArgumentException("조회 범위에 읍면동이 없습니다: sggNm=" + sggNm + ", emdCd=" + emdCd);
        }
        return picked;
    }

    /**
     * ΔS_eff — 반환분 차감 후 순증 면수. 공한지만 {@code N × (1 − r_ret × T_m ÷ 12)}, 복층화·신규는 N.
     */
    public static double vldAddPkltSlot(ExpandMethod method, int addPkltSlot, int predcMm, PolicySimParams p) {
        if (method == ExpandMethod.VACLOT) {
            return addPkltSlot * (1 - p.vaclotGvbkRt() * predcMm / MONTHS_PER_YEAR);
        }
        return addPkltSlot;
    }

    /** α_e = α × min(1, occ ÷ occ_th) */
    public static double alphaE(PolicySimParams p) {
        return p.alpha() * Math.min(1.0, p.occ() / p.occTh());
    }

    /**
     * 범위 하나의 확충 변화식 (순수 계산).
     *
     * @param emds 조회 범위 읍면동 기준선 (비어 있으면 안 된다)
     */
    public static ExpandResult compute(List<PolicySimEmdBaseline> emds, PolicySimParams p,
                                       ExpandMethod method, int addPkltSlot, int predcMm) {
        if (method == null) {
            throw new IllegalArgumentException("확충 방식이 없습니다");
        }
        if (addPkltSlot <= 0 || predcMm < 0) {
            throw new IllegalArgumentException("면수는 양수, 예측기간은 0 이상이어야 합니다: N=" + addPkltSlot + ", T=" + predcMm);
        }
        if (emds == null || emds.isEmpty()) {
            throw new IllegalArgumentException("조회 범위에 읍면동이 없습니다");
        }

        double dsEff = vldAddPkltSlot(method, addPkltSlot, predcMm, p);
        double alphaE = alphaE(p);
        // NO_ENF 는 E0 = 0 이라 합에 기여하지 않는다 — w_e = 0
        double sumE0 = emds.stream().mapToDouble(e -> e.e0() > 0 ? e.e0() : 0.0).sum();

        boolean excs = false;
        List<ExpandEmdResult> out = new ArrayList<>(emds.size());
        for (PolicySimEmdBaseline e : emds) {
            boolean hasEnf = e.e0() > 0;
            double w = hasEnf && sumE0 > 0 ? e.e0() / sumE0 : 0.0;
            double ds = dsEff * w;
            double absorbable = alphaE * ds;
            double de = hasEnf ? Math.min(e.e0(), absorbable) : 0.0;
            double e1 = e.e0() - de;
            double u1 = e.u0() + de;
            double s1 = e.s() + ds;
            Double c1 = e.k() != null ? e.k() * e1 : null;
            Double h1 = e.s() > 0 ? (u1 + e1) / s1 : null;
            // 🔴 NO_ENF 제외 — 0 ≥ 0 오판정 방지
            boolean saturated = hasEnf && absorbable >= e.e0();
            excs |= saturated;
            out.add(new ExpandEmdResult(e, w, ds, de, e1, u1, s1, c1, h1, saturated));
        }
        return new ExpandResult(method, addPkltSlot, predcMm, dsEff, alphaE, sumE0, List.copyOf(out), excs);
    }
}
