package jeju.datahub.policysim.service.impl;

import static jeju.datahub.policysim.service.impl.ExpandPolicyTest.emd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import jeju.datahub.policysim.service.PolicySimResultVO;
import jeju.datahub.policysim.service.impl.PolicySimOutputCalculator.Indicators;

/**
 * 이면도로 정비 ↔ 주차장 확충 교차 검증.
 *
 * <p>흡수율을 맞춘 조건({@code α_B = α_e})과 같은 순증 면수({@code ΔS} = 150면 = 상업지역형 1,250m = 12.5 × 12)에서
 * 두 정책의 차이가 <b>풍선효과분뿐</b>임을 확인한다.
 * <pre>
 * 대상 ΔE        : 확충 = 이면도로            (변화식이 같다)
 * R_REPAIR − R_EXPAND = Σ_n k_n × 배분_n ÷ ΣC0   (인접이 있을 때)
 *                     = s × ΔE × k_대상 ÷ ΣC0     (인접 공집합 → 대상에 가산)
 * </pre>
 *
 * <p>🔴 <b>α 는 방식과 무관하다</b> — 확충의 {@code α_e = α × min(1, occ ÷ occ_th)} 는 방식(VACLOT·MULTFLR·NEW)을 보지 않는다.
 * 방식이 바꾸는 것은 {@code ΔS_eff} 뿐이다(공한지만 반환분 차감 → {@code T_m = 0} 이면 N 과 같다).
 *
 * <p>🔴 <b>실데이터 값을 단언하지 않는다</b> — 기준선을 직접 주입한다(연동 C0 818.6 · E0 174.75 는 예시값을 주입 데이터로만 쓴다).
 * DB 없이 돈다.
 */
class RepairIntegrationTest {

    private static final double EPS = 1e-9;

    private static final PolicySimParams PARAMS = PolicySimBaselineTest.params(Map.of());

    /** 확충 α_e (기본 0.5 × min(1, 0.85 ÷ 0.85) = 0.5) — 이면도로 α_B 를 이 값에 맞춘다 */
    private static final double ALPHA = ExpandPolicyCalculator.alphaE(PARAMS);

    private static final double S_RT = 0.2;

    private static final RepairCoef COEF = new RepairCoef(14.4, 12.0, ALPHA, S_RT);

    /** 150면 = 확충 허용값과 이면도로 상업지역형 환산값이 정확히 맞는 면수 */
    private static final int SLOT = 150;
    private static final int LEN_CMRC = 1250;

    /** 대상 — 연동 예시. k = 818.6 ÷ 174.75 ≈ 4.684 · ΔE = 0.5 × 150 = 75 < 174.75 (포화 없음) */
    private static final PolicySimEmdBaseline TARGET = emd("50110137", "제주시", 818.6, 174.75, 1000);

    /** 인접 — k 가 서로 다르다(6.0 · 2.0), 하나는 단속 없음(E0 = 0) */
    private static final PolicySimEmdBaseline ADJ_A = emd("50110101", "제주시", 600.0, 100.0, 500);
    private static final PolicySimEmdBaseline ADJ_B = emd("50110102", "제주시", 100.0, 50.0, 300);
    private static final PolicySimEmdBaseline ADJ_Z = emd("50110103", "제주시", 30.0, 0.0, 200);
    private static final List<PolicySimEmdBaseline> ADJ = List.of(ADJ_A, ADJ_B, ADJ_Z);

    private static ExpandResult expand(List<PolicySimEmdBaseline> target, ExpandMethod m) {
        return ExpandPolicyCalculator.compute(target, PARAMS, m, SLOT, 0);
    }

    private static RepairResult repair(List<PolicySimEmdBaseline> target, List<PolicySimEmdBaseline> adj) {
        return RepairPolicyCalculator.compute(target, adj, COEF, RoadMtncType.CMRC, LEN_CMRC);
    }

    @Test
    @DisplayName("전제 — α_B = α_e = 0.5 · ΔS = ΔS_eff = 150 · 포화 없음")
    void preconditions() {
        assertThat(ALPHA).isCloseTo(0.5, within(EPS));
        assertThat(RepairPolicyCalculator.dltSlot(RoadMtncType.CMRC, LEN_CMRC, COEF)).isCloseTo(SLOT, within(EPS));
        ExpandResult ex = expand(List.of(TARGET), ExpandMethod.NEW);
        RepairResult rp = repair(List.of(TARGET), ADJ);
        assertThat(ex.vldAddPkltSlot()).isCloseTo(SLOT, within(EPS));
        assertThat(ex.excs()).isFalse();
        assertThat(rp.excs()).isFalse();
    }

    @ParameterizedTest(name = "확충 {0}")
    @EnumSource(ExpandMethod.class)
    @DisplayName("(a) 대상 ΔE·ΔS_e·U1·S1 이 확충과 같다 — 방식 무관(공한지는 T = 0)")
    void sameTargetDelta(ExpandMethod method) {
        ExpandEmdResult ex = expand(List.of(TARGET), method).emds().get(0);
        ExpandEmdResult rp = repair(List.of(TARGET), ADJ).emds().get(0);
        assertThat(rp.dltIlgl()).isCloseTo(ex.dltIlgl(), within(EPS));
        assertThat(rp.dltIlgl()).isCloseTo(75.0, within(EPS));
        assertThat(rp.dltSlot()).isCloseTo(ex.dltSlot(), within(EPS));
        assertThat(rp.u1()).isCloseTo(ex.u1(), within(EPS));
        assertThat(rp.s1()).isCloseTo(ex.s1(), within(EPS));
        // 인접이 있으면 대상 E1·C1 도 같다(풍선분은 인접으로만 간다)
        assertThat(rp.e1()).isCloseTo(ex.e1(), within(EPS));
        assertThat(rp.c1()).isCloseTo(ex.c1(), within(EPS));
    }

    @Test
    @DisplayName("(b) R_REPAIR − R_EXPAND = 인접 증가분 ÷ ΣC0 — 인접 k 각자로 가중, E0 = 0 인접은 제외")
    void rDiffEqualsAdjacentIncrease() {
        RepairResult rp = repair(List.of(TARGET), ADJ);
        double rRepair = PolicySimOutputCalculator.indicators(rp).r();
        double rExpand = PolicySimOutputCalculator.indicators(expand(List.of(TARGET), ExpandMethod.NEW)).r();

        // 풍선분 = 0.2 × 75 = 15대 → A 에 2/3 (k 6) · B 에 1/3 (k 2) · Z 0
        double balloon = S_RT * 75.0;
        double adjInc = 6.0 * balloon * 100.0 / 150.0 + 2.0 * balloon * 50.0 / 150.0;   // = 70
        assertThat(rp.balloon()).isCloseTo(balloon, within(EPS));
        assertThat(rp.adjcDltCvlcpt()).isCloseTo(adjInc, within(1e-9));
        assertThat(rp.adjcEmdCnt()).isEqualTo(2);
        assertThat(rp.adjcEmds().get(2).dltIlgl()).isZero();

        assertThat(rRepair - rExpand).isCloseTo(rp.adjcDltCvlcpt() / 818.6, within(1e-12));
        assertThat((rRepair - rExpand) * 100).isCloseTo(adjInc / 818.6 * 100, within(1e-9));
        // 기대값 — EXPAND −75 ÷ 174.75 = −42.918% (단일 읍면동이라 k 가 약분) · REPAIR −42.918 + 8.551 = −34.367%
        assertThat(rExpand).isCloseTo(-75.0 / 174.75, within(1e-12));
    }

    @Test
    @DisplayName("(b') 응답 VO 기준 — cvlcptRdctRt 차이 = adjcCvlcptCnt ÷ crtrCvlcptCnt × 100 (±0.02, 6-1 curl 판정식)")
    void voDiffMatchesCurlCheck() {
        List<PolicySimEmdBaseline> target = List.of(TARGET);
        PolicySimResultVO voEx = PolicySimOutputCalculator.toVO(PolicySimOutputTest.baseline(PARAMS, target),
                expand(target, ExpandMethod.NEW), null, TARGET.emdCd());
        PolicySimResultVO voRp = PolicySimOutputCalculator.toVO(PolicySimOutputTest.baseline(PARAMS, target),
                repair(target, ADJ), null, TARGET.emdCd());

        assertThat(voRp.getCrtrCvlcptCnt()).isEqualByComparingTo(voEx.getCrtrCvlcptCnt());
        assertThat(voRp.getVldAddPkltSlot()).isEqualByComparingTo(voEx.getVldAddPkltSlot());
        double diff = voRp.getCvlcptRdctRt().subtract(voEx.getCvlcptRdctRt()).doubleValue();
        double expected = voRp.getAdjcCvlcptCnt().doubleValue() / voRp.getCrtrCvlcptCnt().doubleValue() * 100;
        assertThat(diff).isCloseTo(expected, within(0.02));
        assertThat(voEx.getCvlcptRdctRt()).isEqualByComparingTo(new BigDecimal("-42.92"));
    }

    @Test
    @DisplayName("(c) 이면도로 감소폭이 더 작다 — 둘 다 감소지만 |R_REPAIR| < |R_EXPAND|")
    void repairReducesLess() {
        double rRepair = PolicySimOutputCalculator.indicators(repair(List.of(TARGET), ADJ)).r();
        double rExpand = PolicySimOutputCalculator.indicators(expand(List.of(TARGET), ExpandMethod.NEW)).r();
        assertThat(rExpand).isNegative();
        assertThat(rRepair).isNegative();
        assertThat(Math.abs(rRepair)).isLessThan(Math.abs(rExpand));
        // 혼잡 개선율 I 는 대상만이라 같다(인접 기준선은 I 에 들어가지 않는다)
        Indicators iRp = PolicySimOutputCalculator.indicators(repair(List.of(TARGET), ADJ));
        Indicators iEx = PolicySimOutputCalculator.indicators(expand(List.of(TARGET), ExpandMethod.NEW));
        assertThat(iRp.i()).isCloseTo(iEx.i(), within(EPS));
    }

    @Test
    @DisplayName("(d) 인접 공집합 → 풍선분을 대상에 가산 · R 차이 = s × ΔE × k_대상 ÷ ΣC0")
    void noAdjacentAddsBalloonToTarget() {
        RepairResult rp = repair(List.of(TARGET), List.of());
        double rRepair = PolicySimOutputCalculator.indicators(rp).r();
        double rExpand = PolicySimOutputCalculator.indicators(expand(List.of(TARGET), ExpandMethod.NEW)).r();

        double kTarget = TARGET.k();
        assertThat(rp.balloonToTarget()).isTrue();
        assertThat(rp.adjcDltCvlcpt()).isZero();
        assertThat(rp.emds().get(0).e1()).isCloseTo(174.75 - 75.0 + S_RT * 75.0, within(1e-9));
        assertThat(rRepair - rExpand).isCloseTo(S_RT * 75.0 * kTarget / 818.6, within(1e-12));
        assertThat(Math.abs(rRepair)).isLessThan(Math.abs(rExpand));

        // 인접이 전부 E0 = 0 이어도 같다
        double rAllNoEnf = PolicySimOutputCalculator.indicators(repair(List.of(TARGET), List.of(ADJ_Z))).r();
        assertThat(rAllNoEnf).isCloseTo(rRepair, within(EPS));
    }

    @Test
    @DisplayName("대상 여러 곳(시군구 범위) — 읍면동별 ΔE 가 확충과 같고 R 차이 = 인접 증가분 ÷ ΣC0")
    void multiTargetRange() {
        PolicySimEmdBaseline t2 = emd("50110138", "제주시", 300.0, 60.0, 400);   // k 5.0
        PolicySimEmdBaseline t3 = emd("50110139", "제주시", 20.0, 0.0, 100);     // NO_ENF — R 분모에서 빠진다
        List<PolicySimEmdBaseline> target = List.of(TARGET, t2, t3);

        ExpandResult ex = expand(target, ExpandMethod.MULTFLR);
        RepairResult rp = repair(target, ADJ);
        for (int idx = 0; idx < target.size(); idx++) {
            assertThat(rp.emds().get(idx).dltIlgl()).isCloseTo(ex.emds().get(idx).dltIlgl(), within(EPS));
            assertThat(rp.emds().get(idx).saturated()).isFalse();
        }
        assertThat(rp.sumDltIlgl()).isCloseTo(75.0, within(1e-9));

        double sumC0 = 818.6 + 300.0;
        double rRepair = PolicySimOutputCalculator.indicators(rp).r();
        double rExpand = PolicySimOutputCalculator.indicators(ex).r();
        assertThat(rRepair - rExpand).isCloseTo(rp.adjcDltCvlcpt() / sumC0, within(1e-12));
        assertThat(rp.adjcDltCvlcpt()).isCloseTo(70.0, within(1e-9));
    }
}
