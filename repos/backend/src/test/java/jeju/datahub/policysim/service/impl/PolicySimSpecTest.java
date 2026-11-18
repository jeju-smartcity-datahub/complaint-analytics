package jeju.datahub.policysim.service.impl;

import static jeju.datahub.policysim.service.impl.ExpandPolicyTest.emd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.policysim.service.PolicySimResultVO;

/**
 * 🔴 <b>명세 13장 기준값의 정본 테스트</b> (TSK-4037 Step 8-1).
 *
 * <p>{@link ExpandPolicyTest.SpecCases}·{@link PolicySimOutputTest.SpecCases} 에도 같은 케이스가 있다 — 그쪽은
 * 각 Step(3·4) 단위 회귀용으로 그대로 두고, <b>명세 기준값과의 대조는 이 클래스가 정본</b>이다.
 * 여기서는 계산 체인 전체({@link ExpandPolicyCalculator#calculate} → {@link PolicySimOutputCalculator#calculate}
 * 및 반올림 전 {@link PolicySimOutputCalculator#indicators})를 한 번에 태운다.
 *
 * <p>기준선(명세 13장 서귀포시 동홍동 예시): S 1,819 · occ 0.95 · E0 527 · C0 44 · N 100 ·
 * occ_th 0.85 · α 0.50 · 공한지 반환율 0.032. 🔴 실데이터를 쓰지 않는다 — DB 없이 돈다.
 * ⚠️ 케이스 B(복층화) 비용 등급은 명세의 "고"가 아니라 "중"이다(§0-2 수정 2).
 */
class PolicySimSpecTest {

    private static final String EMD = "50130105";
    private static final double TOL4 = 5e-5;   // 넷째 자리 반올림 일치

    private final PolicySimParams p = PolicySimBaselineTest.params(Map.of(
            "PCSHR_CRTR_VL", new BigDecimal("0.95"),
            "PCSHR_THRSH", new BigDecimal("0.85"),
            "ALPHA_VL", new BigDecimal("0.50"),
            "VACLOT_GVBK_RT", new BigDecimal("0.032")));
    private final List<PolicySimEmdBaseline> dh = List.of(emd(EMD, "서귀포시", 44.0, 527.0, 1819, 0.95));
    private final PolicySimBaseline baseline = PolicySimOutputTest.baseline(p, dh);

    private final ExpandPolicyCalculator expand = new ExpandPolicyCalculator();
    private final PolicySimOutputCalculator output = new PolicySimOutputCalculator();

    @Test
    @DisplayName("주입 기준선 — U0 1,728.05 · k 0.08349 · H0 1.240 · α_e 0.50")
    void baseline() {
        PolicySimEmdBaseline b = dh.get(0);
        assertThat(b.u0()).isCloseTo(1728.05, within(1e-9));
        assertThat(b.k()).isCloseTo(0.08349, within(5e-6));
        assertThat(b.h0()).isCloseTo(1.240, within(5e-4));
        assertThat(ExpandPolicyCalculator.alphaE(p)).isCloseTo(0.50, within(1e-12));
    }

    private void check(ExpandMethod m, int t, double dsEff, double expR, double expI,
                       String rPct, String iPct, String lvl) {
        ExpandResult res = expand.calculate(baseline, m, 100, t, null, EMD);
        assertThat(res.vldAddPkltSlot()).isCloseTo(dsEff, within(1e-9));
        assertThat(res.excs()).isFalse();

        PolicySimOutputCalculator.Indicators ind = PolicySimOutputCalculator.indicators(res);
        assertThat(ind.r()).isCloseTo(expR, within(TOL4));   // 넷째 자리 (셋째 자리보다 엄격)
        assertThat(ind.i()).isCloseTo(expI, within(TOL4));

        PolicySimResultVO vo = output.calculate(baseline, res, null, EMD);
        assertThat(vo.getCvlcptRdctRt()).isEqualByComparingTo(rPct);
        assertThat(vo.getCngstImpvRt()).isEqualByComparingTo(iPct);
        assertThat(vo.getCrtrCngstIdx()).isEqualByComparingTo("1.240");
        assertThat(vo.getCrtrCvlcptCnt()).isEqualByComparingTo("44.00");
        assertThat(vo.getCrtrIlglCnt()).isEqualByComparingTo("527.00");
        assertThat(vo.getExsPkltCpcty()).isEqualTo(1819L);
        assertThat(vo.getCstLvlCd()).isEqualTo(lvl);
        assertThat(vo.getCalcSttsCd()).isNull();
    }

    @Test
    @DisplayName("A 공한지 100면 12개월 → ΔS_eff 96.8 · R −0.0918 · I 0.0505 · LOW")
    void caseA() { check(ExpandMethod.VACLOT, 12, 96.8, -0.0918, 0.0505, "-9.18", "5.05", "LOW"); }

    @Test
    @DisplayName("A3 공한지 100면 3개월 → ΔS_eff 99.2 · R −0.0941 · I 0.0517 · LOW")
    void caseA3() { check(ExpandMethod.VACLOT, 3, 99.2, -0.0941, 0.0517, "-9.41", "5.17", "LOW"); }

    @Test
    @DisplayName("B 복층화 100면 12개월 → ΔS_eff 100 · R −0.0949 · I 0.0521 · 🔴 MID(명세 '고' 아님 — §0-2 수정 2)")
    void caseB() { check(ExpandMethod.MULTFLR, 12, 100, -0.0949, 0.0521, "-9.49", "5.21", "MID"); }

    @Test
    @DisplayName("C 신규 100면 12개월 → ΔS_eff 100 · R −0.0949 · I 0.0521 · HIGH")
    void caseC() { check(ExpandMethod.NEW, 12, 100, -0.0949, 0.0521, "-9.49", "5.21", "HIGH"); }

    @Test
    @DisplayName("TSK-4332 D1 — AnlsVrblScope.POLICY_* 3스코프가 정책 코드 상수와 같고, 고정값 코드는 없다")
    void policyScopesMatchCoefficientConstants() {
        List<String> common = List.of(PolicySimParams.PCSHR_CRTR_VL, PolicySimParams.ALPHA_VL,
                PolicySimParams.GEO_CVRG_CRCT_JEJU, PolicySimParams.GEO_CVRG_CRCT_SEOGWIPO);
        List<String> repair = List.of(RepairCoef.N100_RSDNC, RepairCoef.N100_CMRC,
                RepairCoef.ALPHA_B_VL, RepairCoef.BALLOON_RT);
        List<String> fee = List.of(FeeCoef.PRC_ELST_VL, FeeCoef.PAID_DMND_RT,
                FeeCoef.TRNSF_RT, FeeCoef.TRNSF_LOCAL_RT);
        List<String> fixed = List.of(PolicySimParams.PCSHR_THRSH, PolicySimParams.DSCL_MLTP,
                PolicySimParams.FIX_WGVL, PolicySimParams.VACLOT_GVBK_RT,
                PolicySimParams.CST_VACLOT, PolicySimParams.CST_PBLPKLT, PolicySimParams.CST_MULTFLR,
                PolicySimParams.CST_LOW_BNDRY, PolicySimParams.CST_HIGH_BNDRY);

        assertThat(AnlsVrblScope.POLICY_EXPAND.getVrblCds()).containsExactlyInAnyOrderElementsOf(common);
        assertThat(AnlsVrblScope.POLICY_REPAIR.getVrblCds()).containsExactlyInAnyOrderElementsOf(concat(common, repair));
        assertThat(AnlsVrblScope.POLICY_FEE.getVrblCds()).containsExactlyInAnyOrderElementsOf(concat(common, fee));
        for (AnlsVrblScope scope : List.of(
                AnlsVrblScope.POLICY_EXPAND, AnlsVrblScope.POLICY_REPAIR, AnlsVrblScope.POLICY_FEE)) {
            assertThat(scope.getAnlsTypeCd()).isEqualTo(PolicySimParams.ANLS_TYPE_CD);
            assertThat(scope.getVrblCds()).doesNotContainAnyElementsOf(fixed);
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
