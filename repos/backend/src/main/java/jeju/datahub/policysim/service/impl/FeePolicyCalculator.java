package jeju.datahub.policysim.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 정책 시뮬레이션 — 요금제 적용 변화식: 수요 감소 → 흡수 → 전이 (명세 6.3).
 *
 * <pre>
 * d     = λ (CONVERT)  |  −ε × p ÷ 100 (RAISE, ε ≤ 0)       수요 감소 비율
 * V_e   = 면수_e × occ × d                                    (NO_ENF 대상은 0 — 사용자 결정 2026-10-11)
 * ΔE_e  = min(E0_e, α × V_e)  if occ ≥ occ_th, else 0         흡수 — 포화 지역만
 * 전이   = τ × ΣV  =  대상 내 τ × τ_local × V_e  +  인접분 τ × (1 − τ_local) × ΣV
 * E1_e  = E0_e − ΔE_e + τ × τ_local × V_e
 * E1_n  = E0_n + τ × (1 − τ_local) × ΣV × E0_n ÷ Σ_N E0_j    (인접 N 중 E0 &gt; 0 만 — 4038 배분 재사용)
 *   └ 남는 인접이 없으면 → E1_e += 인접분 × E0_e ÷ Σ_T E0_j  (대상에 가산 — 명세 6.3)
 * U1_e  = U0_e − V_e + ΔE_e ;  S1_e = S_e (공급 불변) ;  C1 = k × E1
 * </pre>
 *
 * <p>🔴 <b>τ_local 은 전이를 대상/인접으로 나누는 비율이지 총량을 줄이는 계수가 아니다</b> — 전이 총량은 τ × ΣV.
 * <p>🔴 <b>대상 = 조회 범위 ∩ 급지</b>(emdCd 없을 때 — = B, 급지를 V 출처로만 보던 규칙 폐기) — 거르는 일은
 * 호출부({@code PolicySimServiceImpl.computeFee})가 하고 여기는 받은 대상을 그대로 쓴다. 행이 없는 대상은 면수 0(V = 0).
 * <p>🔴 <b>U1 ≥ 0</b> — d ≤ 1 이고 면수_e ≤ S_e(구현과 기준선 S 가 같은 주차장 조건)라 V_e ≤ U0_e 다. 어긋나면 계산하지 않는다.
 * <p>🔴 <b>포화(SATURATED)</b> — 흡수가 켜졌고 α × V_e ≥ E0_e(E0_e &gt; 0)인 대상. 4037·4038 과 같은 규칙.
 * <p>🔴 <b>R·I 는 여기서 내지 않는다</b> — {@link PolicySimOutputCalculator#indicators(FeeResult)} (R 분자에 인접 증가분, I 는 대상만).
 *
 * <p>값은 {@code double} 원값이다 — 반올림·퍼센트 변환은 응답 조립 한 곳에서만 한다(4037 규칙).
 * 산식은 DB 없이 검증하고 민감도가 계수만 바꿔 다시 부를 수 있도록 정적 순수 함수로 둔다.
 */
@Component
public class FeePolicyCalculator {

    private static final double HUNDRED = 100.0;
    /** V ≤ U0 검사의 부동소수 허용 오차 */
    private static final double TOLERANCE = 1e-9;

    /** 흡수 0 경고 — occ &lt; occ_th (응답 전용 {@code wrnngCn}) */
    public static final String WRNNG_NO_ABSORB = "점유율이 포화 기준 미만이라 흡수 효과가 없습니다";

    /** 서비스 진입점 — {@link #compute} 와 같다 */
    public FeeResult calculate(List<PolicySimEmdBaseline> target, List<FeeTargetRow> feeTargets,
                               List<PolicySimEmdBaseline> adjacent, FeeCoef coef,
                               FeeAplcnMthd mthd, BigDecimal feeIncrsRt) {
        return compute(target, feeTargets, adjacent, coef, mthd, feeIncrsRt);
    }

    /**
     * 수요 감소 비율 d — CONVERT λ · RAISE −ε × p ÷ 100.
     *
     * @throws IllegalArgumentException RAISE 인데 인상률이 없거나 ≤ 0 · d &gt; 1(빠져나가는 차가 이용 차보다 많다)
     */
    public static double dmndRdctRt(FeeAplcnMthd mthd, FeeCoef coef, BigDecimal feeIncrsRt) {
        double d;
        if (mthd == FeeAplcnMthd.RAISE) {
            if (feeIncrsRt == null || feeIncrsRt.signum() <= 0) {
                throw new IllegalArgumentException("요금 인상률은 양수여야 합니다: p=" + feeIncrsRt);
            }
            // ε ≤ 0 (FeeCoef 가 보장) — 부호를 뒤집어 V 를 양수로 만든다
            d = -coef.eps() * feeIncrsRt.doubleValue() / HUNDRED;
        } else {
            d = coef.lambda();
        }
        if (!(d >= 0 && d <= 1)) {
            throw new IllegalArgumentException("수요 감소 비율이 0~1 밖입니다(U1 음수 방지): d=" + d);
        }
        return d;
    }

    /**
     * 범위 하나의 요금제 변화식 (순수 계산).
     *
     * @param target     대상 읍면동 기준선 — 조회 범위({@link ExpandPolicyCalculator#scope}) ∩ 급지(emdCd 없을 때). 비어 있으면 안 된다
     * @param feeTargets {@code selectFeeTargetSlot} 결과 — 대상에 없는 읍면동이 있으면 안 된다. 행이 없는 대상은 면수 0
     * @param adjacent   인접 읍면동 기준선 — 대상 바깥 이웃(4038 {@code selectAdjacentEmd} · 기준선 {@code List<String>} 오버로드). null·빈 목록이면 인접 없음
     * @param coef       계수 7종 — 민감도는 이 인자만 바꿔 다시 부른다
     * @param mthd       적용 방식
     * @param feeIncrsRt 인상률 p (%) — RAISE 필수, CONVERT 는 무시(결과에 null)
     * @throws IllegalArgumentException 방식·계수 없음 · 대상 없음 · 대상/인접 겹침 · 대상 밖 면수 행 · 면수 &gt; S · d 범위 밖
     */
    public static FeeResult compute(List<PolicySimEmdBaseline> target, List<FeeTargetRow> feeTargets,
                                    List<PolicySimEmdBaseline> adjacent, FeeCoef coef,
                                    FeeAplcnMthd mthd, BigDecimal feeIncrsRt) {
        if (mthd == null) {
            throw new IllegalArgumentException("요금 적용 방식이 없습니다");
        }
        if (coef == null) {
            throw new IllegalArgumentException("요금제 계수가 없습니다");
        }
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("조회 범위에 읍면동이 없습니다");
        }
        // 시큐어코딩 5.1 — 원소를 읽는 checkDisjoint·slotsByEmd 앞에서 null 원소를 400 경로(IAE)로 끊는다.
        if (target.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("조회 범위에 비어 있는 읍면동 행이 있습니다");
        }
        List<PolicySimEmdBaseline> adj = RepairPolicyCalculator.checkDisjoint(target, adjacent);
        Map<String, Long> slots = slotsByEmd(target, feeTargets);
        BigDecimal p = mthd == FeeAplcnMthd.RAISE ? feeIncrsRt : null;
        double d = dmndRdctRt(mthd, coef, p);
        boolean absorbs = coef.absorbs();

        // NO_ENF 는 E0 = 0 이라 합에 기여하지 않는다 — w_e = 0 (인접분 대상 가산의 분모)
        double sumE0 = target.stream().mapToDouble(e -> e.e0() > 0 ? e.e0() : 0.0).sum();

        // 1) 수요 감소 · 흡수 · 대상 내 전이
        int size = target.size();
        long[] slot = new long[size];
        double[] v = new double[size];
        double[] abs = new double[size];
        double[] trLocal = new double[size];
        boolean[] sat = new boolean[size];
        long sumSlot = 0;
        double sumV = 0;
        double sumAbs = 0;
        boolean excs = false;
        for (int idx = 0; idx < size; idx++) {
            PolicySimEmdBaseline e = target.get(idx);
            slot[idx] = slots.getOrDefault(e.emdCd(), 0L);
            sumSlot += slot[idx];
            if (slot[idx] > e.s()) {
                throw new IllegalArgumentException("적용 대상 면수가 공영주차면수보다 큽니다: emdCd=" + e.emdCd()
                        + ", slot=" + slot[idx] + ", S=" + e.s());
            }
            // 🔴 NO_ENF 대상은 V·흡수·전이에서 뺀다 — k 가 없어 전이분을 C1 로 바꿀 수 없다
            if (!(e.e0() > 0)) {
                continue;
            }
            // 시큐어코딩 5.1 — 계산은 지역 변수(vi·absI·trLocalI)로 하고 배열에는 결과만 담는다(동작 같음).
            double vi = slot[idx] * coef.occ() * d;
            v[idx] = vi;
            if (vi > e.u0() + TOLERANCE) {
                throw new IllegalArgumentException("빠져나가는 차량이 정상 이용보다 많습니다(U1 음수): emdCd=" + e.emdCd()
                        + ", V=" + vi + ", U0=" + e.u0());
            }
            double absorbable = coef.alpha() * vi;
            double absI = absorbs ? Math.min(e.e0(), absorbable) : 0.0;
            abs[idx] = absI;
            sat[idx] = absorbs && absorbable >= e.e0();
            excs |= sat[idx];
            double trLocalI = coef.tau() * coef.tauLocal() * vi;
            trLocal[idx] = trLocalI;
            sumV += vi;
            sumAbs += absI;
        }

        // 2) 인접분 — 4038 배분 재사용(E0_n = 0 인접 제외). 남는 인접이 없으면 대상에 가산
        double trAdj = coef.tau() * (1 - coef.tauLocal()) * sumV;
        RepairPolicyCalculator.AdjcAllocation alloc = RepairPolicyCalculator.allocateAdjacent(adj, trAdj);
        boolean toTarget = alloc.toTarget();

        // 3) 대상 E1·U1·S1·C1·H1
        List<ExpandEmdResult> out = new ArrayList<>(size);
        List<FeeEmdResult> feeOut = new ArrayList<>(size);
        double sumTrLocal = 0;
        for (int idx = 0; idx < size; idx++) {
            PolicySimEmdBaseline e = target.get(idx);
            double w = e.e0() > 0 && sumE0 > 0 ? e.e0() / sumE0 : 0.0;
            double back = toTarget ? trAdj * w : 0.0;
            double vi = v[idx];             // 5.1 — 배열 값을 한 번씩 꺼내 지역 변수로 쓴다
            double absI = abs[idx];
            double trLocalI = trLocal[idx];
            double e1 = e.e0() - absI + trLocalI + back;
            double u1 = e.u0() - vi + absI;
            double s1 = e.s();
            Double c1 = e.k() != null ? e.k() * e1 : null;
            Double h1 = e.s() > 0 ? (u1 + e1) / s1 : null;
            out.add(new ExpandEmdResult(e, w, 0.0, absI, e1, u1, s1, c1, h1, sat[idx]));
            feeOut.add(new FeeEmdResult(e.emdCd(), slot[idx], vi, trLocalI, back));
            sumTrLocal += trLocalI;
        }

        return new FeeResult(mthd, p, coef, d, sumSlot, sumE0, sumV, sumAbs, coef.tau() * sumV, sumTrLocal, trAdj,
                toTarget, absorbs, List.copyOf(out), List.copyOf(feeOut), alloc.adjcEmds(), alloc.adjcEmdCnt(),
                alloc.adjcDltCvlcpt(), excs);
    }

    /**
     * 응답 경고 문구 (응답 전용 — 저장하지 않는다). 흡수가 꺼졌으면(occ &lt; occ_th) {@link #WRNNG_NO_ABSORB}, 아니면 null.
     * {@code occ} 는 흡수 on/off 계단이라 민감도 범위 대신 이 문구로 드러낸다.
     */
    public static String wrnngCn(FeeResult result) {
        return result.absorbs() ? null : WRNNG_NO_ABSORB;
    }

    /** 행 → 읍면동별 면수. 대상 밖 코드·중복 코드는 범위 불일치라 끊는다(조용히 버리면 V 가 줄어든다) */
    private static Map<String, Long> slotsByEmd(List<PolicySimEmdBaseline> target, List<FeeTargetRow> feeTargets) {
        Set<String> targetCds = new HashSet<>();
        target.forEach(e -> targetCds.add(e.emdCd()));
        Map<String, Long> slots = new HashMap<>();
        if (feeTargets == null) {
            return slots;
        }
        for (FeeTargetRow r : feeTargets) {
            if (!targetCds.contains(r.getEmdCd())) {
                throw new IllegalArgumentException("적용 대상 면수 행이 조회 범위 밖입니다: " + r.getEmdCd());
            }
            if (r.getAplcnTrgtSlot() < 0) {
                throw new IllegalArgumentException("적용 대상 면수가 음수입니다: " + r.getEmdCd());
            }
            if (slots.put(r.getEmdCd(), r.getAplcnTrgtSlot()) != null) {
                throw new IllegalArgumentException("적용 대상 면수 행이 중복입니다: " + r.getEmdCd());
            }
        }
        return slots;
    }
}
