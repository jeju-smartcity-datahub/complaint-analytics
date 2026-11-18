package jeju.datahub.policysim.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;

import lombok.extern.slf4j.Slf4j;

/**
 * 요금제 민감도 — 가정 계수 하나를 {@code [min_vl, max_vl]} 안에서 흔들었을 때의 R 범위 (TSK-4039 Step 4 · §0-4).
 *
 * <p>흔드는 계수는 <b>방식별 수요 계수 1개 + 전이율 τ</b> 다.
 * <ul>
 *   <li>{@code CONVERT} → {@code lambda}(λ {@code PAID_DMND_RT}) · {@code tau}(τ {@code TRNSF_RT})</li>
 *   <li>{@code RAISE} → {@code eps}(ε {@code PRC_ELST_VL}) · {@code tau} — RAISE 는 λ 를 쓰지 않는다</li>
 * </ul>
 * 한 번에 하나만 바꾸고 나머지는 현재 적용값({@link FeeResult#coef()})으로 고정한다.
 *
 * <p>🔴 <b>끝점 두 개만 보면 하한이 틀린다</b>(2026-10-11 Step 5 검증에서 발견 — 연동 CONVERT base R −51.72 인데 λ 범위
 * [−30.02, −25.86]). V_e 는 λ·|ε| 에 선형이고 흡수 {@code −min(E0_e, αV_e)} 가 볼록이라 R 은 λ·|ε| 에 대해
 * <b>볼록 조각선형</b>이다 — 최댓값은 끝점이지만 최솟값은 구간 안의 꺾임점(αV_e = E0_e)에 있을 수 있다.
 * 그래서 후보를 {끝점 두 개 · 현재값 · 구간 안 꺾임점} 으로 잡아 각각 재계산한 R 의 min·max 를 낸다(조각선형이라 정확하다).
 * 현재값을 넣으므로 범위는 항상 base R 을 포함한다. τ 는 R 에 선형이라 꺾임점이 없다.
 *
 * <p>값은 R 과 같은 <b>비율</b>(반올림 전)이다 — 퍼센트 변환(× 100 · 소수 둘째 자리 HALF_UP)은 응답 조립(Step 5 toVO) 한 곳에서 한다.
 * 키는 응답 {@code aplcnParam} 이름과 같다(Step 5-2).
 *
 * <p>🔴 <b>R 이 null 이면(범위 NO_ENF · ΣC0 = 0) 범위도 null</b> — 그 판정은 E0·C0 만 보므로 계수를 바꿔도 같다.
 * <p>🔴 <b>재계산의 {@link IllegalArgumentException}(d &gt; 1 등)은 삼키지 않는다</b> — 현재 인상률 p 는 10·20·30 만 허용(Step 5)이라
 * ε 하한 −0.6 에서도 d ≤ 0.18 이다. 이 예외가 난다면 입력 검증이 뚫린 것이라 조용히 범위를 비우지 않는다.
 */
@Slf4j
public final class FeeSensitivity {

    /** 응답 키 — {@code aplcnParam} 이름과 같다 */
    public static final String KEY_LAMBDA = "lambda";
    public static final String KEY_EPS = "eps";
    public static final String KEY_TAU = "tau";

    private static final double HUNDRED = 100.0;

    private FeeSensitivity() {
    }

    /**
     * R 범위 하나 (비율 · low ≤ high).
     *
     * @param low  후보(끝점·현재값·꺾임점) 재계산 R 중 가장 작은 값
     * @param high 후보 재계산 R 중 가장 큰 값
     */
    public record Sensitivity(double low, double high) {
    }

    /**
     * 민감도를 계산한다 — 기준 결과와 같은 입력으로 계수만 바꿔 {@link FeePolicyCalculator#compute} 를 다시 부른다.
     *
     * @param target     기준 결과를 낸 것과 같은 대상 기준선
     * @param feeTargets 같은 Step 2 면수 행
     * @param adjacent   같은 인접 기준선
     * @param base       기준 결과 — 방식·인상률·현재 계수를 여기서 읽는다
     * @param ranges     {@code vrbl_cd} → 범위({@code min_vl}·{@code max_vl}). 방식별 수요 계수 + {@code TRNSF_RT} 가 필요하다
     * @return 키 순서 {@code lambda|eps}, {@code tau}. 기준 R 이 null 이면 null. 범위가 없는 계수는 값이 null(WARN 1줄)
     */
    public static Map<String, Sensitivity> compute(List<PolicySimEmdBaseline> target, List<FeeTargetRow> feeTargets,
                                                   List<PolicySimEmdBaseline> adjacent, FeeResult base,
                                                   Map<String, VrblRange> ranges) {
        if (PolicySimOutputCalculator.indicators(base).r() == null) {
            return null;
        }
        FeeAplcnMthd mthd = base.mthd();
        BigDecimal p = base.feeIncrsRt();
        FeeCoef coef = base.coef();
        Map<String, Sensitivity> out = new LinkedHashMap<>();
        if (mthd == FeeAplcnMthd.RAISE) {
            // αV_e = E0_e ⇔ |ε| = 100·E0_e ÷ (α·면수_e·occ·p) → ε* = −|ε|*
            double pv = p.doubleValue();
            out.put(KEY_EPS, swing(ranges, FeeCoef.PRC_ELST_VL, coef.eps(),
                    kinks(base, unit -> -HUNDRED * unit / pv),
                    v -> r(target, feeTargets, adjacent, coef.withEps(v), mthd, p)));
        } else {
            // αV_e = E0_e ⇔ λ* = E0_e ÷ (α·면수_e·occ)
            out.put(KEY_LAMBDA, swing(ranges, FeeCoef.PAID_DMND_RT, coef.lambda(),
                    kinks(base, unit -> unit),
                    v -> r(target, feeTargets, adjacent, coef.withLambda(v), mthd, p)));
        }
        out.put(KEY_TAU, swing(ranges, FeeCoef.TRNSF_RT, coef.tau(), List.of(),
                v -> r(target, feeTargets, adjacent, coef.withTau(v), mthd, p)));
        return out;
    }

    /**
     * 흡수가 꺾이는 계수값 — 대상 읍면동마다 αV_e = E0_e 가 되는 d 값 {@code E0_e ÷ (α·면수_e·occ)} 를 계수로 바꾼다.
     * 흡수가 꺼졌거나(occ &lt; occ_th) α·occ 가 0 이면 꺾임이 없다. E0_e = 0(NO_ENF)·면수 0 대상은 V_e = 0 이라 뺀다.
     *
     * @param toCoef d 꺾임값 → 계수값 (CONVERT 항등 · RAISE −100·d ÷ p)
     */
    private static List<Double> kinks(FeeResult base, DoubleUnaryOperator toCoef) {
        List<Double> out = new ArrayList<>();
        FeeCoef c = base.coef();
        if (!base.absorbs() || !(c.alpha() > 0) || !(c.occ() > 0)) {
            return out;
        }
        for (int idx = 0; idx < base.emds().size(); idx++) {
            double e0 = base.emds().get(idx).base().e0();
            long slot = base.feeEmds().get(idx).aplcnTrgtSlot();
            if (e0 > 0 && slot > 0) {
                out.add(toCoef.applyAsDouble(e0 / (c.alpha() * slot * c.occ())));
            }
        }
        return out;
    }

    /**
     * 후보 {min_vl, max_vl, 현재값} ∪ {(min_vl, max_vl) 안 꺾임점} 으로 R 을 계산해 [min, max].
     * 범위가 없거나 재계산 R 이 하나라도 null 이면 null. 현재값이 범위 밖이어도 후보에 넣고 그쪽 꺾임점까지 본다(base R 을 항상 포함).
     */
    private static Sensitivity swing(Map<String, VrblRange> ranges, String vrblCd, double current,
                                     List<Double> kinks, DoubleFunction<Double> rAt) {
        VrblRange range = ranges == null ? null : ranges.get(vrblCd);
        if (range == null) {
            log.warn("요금제 민감도 범위(min_vl·max_vl)가 없어 이 계수의 범위를 비웁니다: vrblCd={}", vrblCd);
            return null;
        }
        // 현재값이 범위 밖이면(설정 화면 밖 값) 구간을 현재값까지 넓혀 그 사이 꺾임점도 본다
        double lo = Math.min(range.minVl(), current);
        double hi = Math.max(range.maxVl(), current);
        TreeSet<Double> candidates = new TreeSet<>(List.of(range.minVl(), range.maxVl(), current));
        for (double k : kinks) {
            if (k > lo && k < hi) {
                candidates.add(k);
            }
        }
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (double v : candidates) {
            Double r = rAt.apply(v);
            if (r == null) {
                return null;
            }
            low = Math.min(low, r);
            high = Math.max(high, r);
        }
        return new Sensitivity(low, high);
    }

    private static Double r(List<PolicySimEmdBaseline> target, List<FeeTargetRow> feeTargets,
                            List<PolicySimEmdBaseline> adjacent, FeeCoef coef, FeeAplcnMthd mthd, BigDecimal p) {
        return PolicySimOutputCalculator.indicators(
                FeePolicyCalculator.compute(target, feeTargets, adjacent, coef, mthd, p)).r();
    }
}
