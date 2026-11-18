package jeju.datahub.policysim.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 정책 시뮬레이션 — 이면도로 정비 변화식 + 풍선효과 (TSK-4038 Step 3 · 명세 6.2).
 *
 * <pre>
 * ΔS    = (L ÷ 100) × n_100                 n_100 = 주거 14.4 / 상업 12 (§0-2 수정 2)
 * w_e   = E0_e ÷ Σ_T E0_j                   (대상 T. NO_ENF 는 w_e = 0)
 * ΔS_e  = ΔS × w_e
 * ΔE_e  = min(E0_e, α_B × ΔS_e)
 * E1_e  = E0_e − ΔE_e ;  U1_e = U0_e + ΔE_e ;  S1_e = S_e + ΔS_e
 * B     = s × Σ ΔE_e                        (풍선효과분)
 * E1_n  = E0_n + B × E0_n ÷ Σ_N E0_j         (인접 N 중 E0 &gt; 0 만)
 *   └ 인접이 없거나 전부 E0 = 0 → E1_e += B × w_e  (대상에 가산 — 명세 6.2)
 * C1    = k × E1  (대상·인접 각자의 k)
 * </pre>
 *
 * <p>🔴 <b>α_B 는 점유율 보정이 없다</b> — 확충의 α_e = α × min(1, occ ÷ occ_th) 와 다르다(명세 6.2).
 * <p>🔴 <b>포화(SATURATED, DEC-025)</b> 는 확충과 같은 규칙 — α_B × ΔS_e ≥ E0_e, <b>대상 중 E0_e &gt; 0</b> 인 곳만 판정한다.
 * 대상에 풍선분을 가산해 E1 &gt; 0 이 돼도 포화 플래그는 그대로다(흡수 한계에 닿았다는 사실은 같다).
 * <p>🔴 <b>R·I 는 여기서 내지 않는다</b> — {@link PolicySimOutputCalculator#indicators(RepairResult)} 한 곳이다
 * (R 의 분자에 인접 증가분, I 는 대상만).
 *
 * <p>산식은 DB 없이 검증할 수 있도록 정적 메서드로 둔다({@link ExpandPolicyCalculator} 와 같은 구조).
 */
@Component
public class RepairPolicyCalculator {

    private static final double HUNDRED_M = 100.0;

    /**
     * 대상·인접 기준선으로 계산한다. 대상은 {@link ExpandPolicyCalculator#scope}, 인접은 Step 2 인접 코드로
     * {@link PolicySimBaselineCalculator#calculate(List)} 에서 받은 것(같은 구간 · 자기 시 g_sgg)을 넘긴다.
     */
    public RepairResult calculate(List<PolicySimEmdBaseline> target, List<PolicySimEmdBaseline> adjacent,
                                  RepairCoef coef, RoadMtncType type, int roadMtncLen) {
        return compute(target, adjacent, coef, type, roadMtncLen);
    }

    /** ΔS = L ÷ 100 × n_100 */
    public static double dltSlot(RoadMtncType type, int roadMtncLen, RepairCoef coef) {
        return roadMtncLen / HUNDRED_M * type.n100(coef);
    }

    /**
     * 범위 하나의 이면도로 정비 변화식 (순수 계산).
     *
     * @param target   대상 읍면동 기준선 — 비어 있으면 안 된다({@link ExpandPolicyCalculator#scope} 결과)
     * @param adjacent 인접 읍면동 기준선 — 대상 바깥 이웃(Step 2 {@code selectAdjacentEmd}). null·빈 목록이면 인접 없음
     * @throws IllegalArgumentException 방식 없음 · 연장 ≤ 0 · 대상 없음 · 대상과 인접이 겹칠 때(이중 계상 방지)
     */
    public static RepairResult compute(List<PolicySimEmdBaseline> target, List<PolicySimEmdBaseline> adjacent,
                                       RepairCoef coef, RoadMtncType type, int roadMtncLen) {
        if (type == null) {
            throw new IllegalArgumentException("정비 방식이 없습니다");
        }
        if (coef == null) {
            throw new IllegalArgumentException("이면도로 계수가 없습니다");
        }
        if (roadMtncLen <= 0) {
            throw new IllegalArgumentException("정비 구간 연장은 양수여야 합니다: L=" + roadMtncLen);
        }
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("조회 범위에 읍면동이 없습니다");
        }
        List<PolicySimEmdBaseline> adj = checkDisjoint(target, adjacent);

        double n100 = type.n100(coef);
        double dS = dltSlot(type, roadMtncLen, coef);
        double alphaB = coef.alphaB();
        // NO_ENF 는 E0 = 0 이라 합에 기여하지 않는다 — w_e = 0
        double sumE0 = target.stream().mapToDouble(e -> e.e0() > 0 ? e.e0() : 0.0).sum();

        // 1) 대상 배분 — ΔS_e · ΔE_e · 포화
        int size = target.size();
        double[] w = new double[size];
        double[] ds = new double[size];
        double[] de = new double[size];
        boolean[] sat = new boolean[size];
        double sumDe = 0;
        boolean excs = false;
        for (int idx = 0; idx < size; idx++) {
            PolicySimEmdBaseline e = target.get(idx);
            boolean hasEnf = e.e0() > 0;
            w[idx] = hasEnf && sumE0 > 0 ? e.e0() / sumE0 : 0.0;
            ds[idx] = dS * w[idx];
            double absorbable = alphaB * ds[idx];
            de[idx] = hasEnf ? Math.min(e.e0(), absorbable) : 0.0;
            // 🔴 NO_ENF 제외 — 0 ≥ 0 오판정 방지
            sat[idx] = hasEnf && absorbable >= e.e0();
            excs |= sat[idx];
            sumDe += de[idx];
        }

        // 2) 풍선효과 — E0_n = 0 인 인접은 배분에서 뺀다(0 나눗셈 방지)
        double balloon = coef.balloonRt() * sumDe;
        AdjcAllocation alloc = allocateAdjacent(adj, balloon);
        boolean toTarget = alloc.toTarget();

        // 3) 대상 E1·U1·S1·C1·H1 — 인접이 없으면 풍선효과분을 대상 E0 비율로 되돌려 더한다
        List<ExpandEmdResult> out = new ArrayList<>(size);
        for (int idx = 0; idx < size; idx++) {
            PolicySimEmdBaseline e = target.get(idx);
            double back = toTarget ? balloon * w[idx] : 0.0;
            double e1 = e.e0() - de[idx] + back;
            double u1 = e.u0() + de[idx];
            double s1 = e.s() + ds[idx];
            Double c1 = e.k() != null ? e.k() * e1 : null;
            Double h1 = e.s() > 0 ? (u1 + e1) / s1 : null;
            out.add(new ExpandEmdResult(e, w[idx], ds[idx], de[idx], e1, u1, s1, c1, h1, sat[idx]));
        }

        return new RepairResult(type, roadMtncLen, n100, dS, alphaB, coef.balloonRt(), sumE0, sumDe, balloon,
                toTarget, List.copyOf(out), alloc.adjcEmds(), alloc.adjcEmdCnt(), alloc.adjcDltCvlcpt(), excs);
    }

    /**
     * 인접 배분 결과 — {@link #allocateAdjacent} (이면도로 풍선효과 · 요금제 인접 전이 TSK-4039 Step 3 공용).
     *
     * @param adjcEmds      인접 읍면동별 값 (입력 순서 유지 · E0 = 0 포함)
     * @param adjcEmdCnt    배분에 쓴 인접 수(E0 &gt; 0) — 대상 가산이면 0
     * @param adjcDltCvlcpt 인접 민원 증가분 Σ_n k_n × 배분_n — 대상 가산이면 0
     * @param toTarget      남는 인접이 없어(없음 · 전부 E0 = 0) 배분하지 않았다 — 호출부가 대상에 E0 비율로 더한다
     */
    record AdjcAllocation(List<RepairAdjcEmdResult> adjcEmds, int adjcEmdCnt, double adjcDltCvlcpt, boolean toTarget) {
    }

    /**
     * {@code amount} 대를 인접 읍면동에 E0 비율로 배분한다 — {@code E1_n = E0_n + amount × E0_n ÷ Σ_N E0_j}.
     * 🔴 <b>{@code E0_n = 0} 인 인접은 배분에서 뺀다</b>(k 가 없어 민원으로 바꿀 수 없고 0 나눗셈 방지).
     */
    static AdjcAllocation allocateAdjacent(List<PolicySimEmdBaseline> adj, double amount) {
        double sumE0Adj = adj.stream().mapToDouble(n -> n.e0() > 0 ? n.e0() : 0.0).sum();
        boolean toTarget = sumE0Adj <= 0;

        List<RepairAdjcEmdResult> adjOut = new ArrayList<>(adj.size());
        int adjcEmdCnt = 0;
        double adjcDltCvlcpt = 0;
        for (PolicySimEmdBaseline n : adj) {
            boolean hasEnf = n.e0() > 0;
            double wn = !toTarget && hasEnf ? n.e0() / sumE0Adj : 0.0;
            double inflow = amount * wn;
            double e1 = n.e0() + inflow;
            Double c1 = n.k() != null ? n.k() * e1 : null;
            double dc = n.k() != null ? n.k() * inflow : 0.0;
            if (!toTarget && hasEnf) {
                adjcEmdCnt++;
            }
            adjcDltCvlcpt += dc;
            adjOut.add(new RepairAdjcEmdResult(n, wn, inflow, e1, c1, dc));
        }
        return new AdjcAllocation(List.copyOf(adjOut), adjcEmdCnt, adjcDltCvlcpt, toTarget);
    }

    /** 대상과 인접이 겹치면 이중 계상이다 — 요금제(TSK-4039)와 같은 검사 */
    static List<PolicySimEmdBaseline> checkDisjoint(List<PolicySimEmdBaseline> target, List<PolicySimEmdBaseline> adjacent) {
        List<PolicySimEmdBaseline> adj = adjacent != null ? adjacent : List.of();
        Set<String> targetCds = new HashSet<>();
        target.forEach(e -> targetCds.add(e.emdCd()));
        for (PolicySimEmdBaseline n : adj) {
            if (targetCds.contains(n.emdCd())) {
                throw new IllegalArgumentException("인접 읍면동이 대상과 겹칩니다: " + n.emdCd());
            }
        }
        return adj;
    }
}
