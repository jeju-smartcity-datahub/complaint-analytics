package jeju.datahub.policysim.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 주차장 확충 변화식 단위테스트 (TSK-4037 Step 3 + Step 8-1 명세 13장 케이스).
 *
 * <p>🔴 <b>실데이터 값을 단언하지 않는다</b> — 기준선을 직접 주입한다. 동홍동·연동 등의 E0 는 계획서 예시값을
 * 주입 데이터로만 쓴다. DB 없이 돈다.
 */
class ExpandPolicyTest {

    private static final double EPS = 1e-9;

    private static final PolicySimParams DEFAULT = PolicySimBaselineTest.params(Map.of());

    /** 기준선 주입 — U0 = S × occ, k = C0 ÷ E0, H0 = (U0+E0) ÷ S 를 Step 2 와 같은 규칙으로 채운다 */
    static PolicySimEmdBaseline emd(String emdCd, String sggNm, double c0, double e0, long s, double occ) {
        double u0 = s * occ;
        Double k = e0 > 0 ? c0 / e0 : null;
        Double h0 = s > 0 ? (u0 + e0) / s : null;
        PolicySimCalcStts stts = e0 <= 0 ? PolicySimCalcStts.NO_ENF : s <= 0 ? PolicySimCalcStts.NO_SPLY : null;
        return new PolicySimEmdBaseline(emdCd, sggNm, "동" + emdCd, c0, 0.0, e0, u0, s, 1.0, k, h0, stts);
    }

    static PolicySimEmdBaseline emd(String emdCd, String sggNm, double c0, double e0, long s) {
        return emd(emdCd, sggNm, c0, e0, s, 0.85);
    }

    // ── Step 4 산식을 테스트 안에서만 재현한다(출력 지표 구현은 Step 4) ──

    /** R = Σ(C1 − C0) ÷ ΣC0 — NO_ENF 제외 */
    static double r(ExpandResult res) {
        double c0 = 0, c1 = 0;
        for (ExpandEmdResult e : res.emds()) {
            if (e.c1() != null) {
                c0 += e.base().c0();
                c1 += e.c1();
            }
        }
        return (c1 - c0) / c0;
    }

    /** I = (H0_T − H1_T) ÷ H0_T — S > 0 인 읍면동만 */
    static double i(ExpandResult res) {
        double num0 = 0, num1 = 0, s0 = 0, s1 = 0;
        for (ExpandEmdResult e : res.emds()) {
            if (e.base().s() > 0) {
                num0 += e.base().u0() + e.base().e0();
                num1 += e.u1() + e.e1();
                s0 += e.base().s();
                s1 += e.s1();
            }
        }
        double h0 = num0 / s0;
        double h1 = num1 / s1;
        return (h0 - h1) / h0;
    }

    static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    @Nested
    @DisplayName("ΔS_eff · α_e")
    class Effective {

        @Test
        @DisplayName("공한지 100면 12개월 → 96.8 · 3개월 → 99.2 · 복층화·신규 → 100 (예측기간 무관)")
        void vldAddPkltSlot() {
            assertThat(ExpandPolicyCalculator.vldAddPkltSlot(ExpandMethod.VACLOT, 100, 12, DEFAULT)).isCloseTo(96.8, within(EPS));
            assertThat(ExpandPolicyCalculator.vldAddPkltSlot(ExpandMethod.VACLOT, 100, 3, DEFAULT)).isCloseTo(99.2, within(EPS));
            assertThat(ExpandPolicyCalculator.vldAddPkltSlot(ExpandMethod.VACLOT, 80, 12, DEFAULT)).isCloseTo(77.44, within(EPS));
            for (int t : new int[] {3, 6, 12, 24}) {
                assertThat(ExpandPolicyCalculator.vldAddPkltSlot(ExpandMethod.MULTFLR, 100, t, DEFAULT)).isEqualTo(100.0);
                assertThat(ExpandPolicyCalculator.vldAddPkltSlot(ExpandMethod.NEW, 100, t, DEFAULT)).isEqualTo(100.0);
            }
        }

        @Test
        @DisplayName("occ=0.85·occ_th=0.85·α=0.5 → α_e 0.50 · occ 가 임계 위면 1 로 잘리고 아래면 비례 감소")
        void alphaE() {
            assertThat(ExpandPolicyCalculator.alphaE(DEFAULT)).isCloseTo(0.50, within(EPS));
            assertThat(ExpandPolicyCalculator.alphaE(PolicySimBaselineTest.params(
                    Map.of("PCSHR_CRTR_VL", new BigDecimal("0.95"))))).isCloseTo(0.50, within(EPS));
            assertThat(ExpandPolicyCalculator.alphaE(PolicySimBaselineTest.params(
                    Map.of("PCSHR_CRTR_VL", new BigDecimal("0.70"))))).isCloseTo(0.5 * 0.70 / 0.85, within(EPS));
        }

        @Test
        @DisplayName("방식별 단가 계수 매핑 — 공한지·복층화·신규")
        void cstVrblCd() {
            assertThat(ExpandMethod.VACLOT.cstVrblCd()).isEqualTo("CST_VACLOT");
            assertThat(ExpandMethod.MULTFLR.cstVrblCd()).isEqualTo("CST_MULTFLR");
            assertThat(ExpandMethod.NEW.cstVrblCd()).isEqualTo("CST_PBLPKLT");
        }
    }

    @Nested
    @DisplayName("배분 · 흡수")
    class Allocation {

        private final List<PolicySimEmdBaseline> range = List.of(
                emd("A", "제주시", 800, 174.75, 2315),
                emd("B", "제주시", 50, 40.58, 600),
                emd("C", "제주시", 3, 0.0, 311),     // NO_ENF
                emd("D", "제주시", 2, 1.0, 0));      // NO_SPLY

        @Test
        @DisplayName("w_e = E0 비율 · NO_ENF w=0 · ΣΔS_e = ΔS_eff · S1 = S + ΔS_e · U1/E1 보존")
        void weights() {
            ExpandResult res = ExpandPolicyCalculator.compute(range, DEFAULT, ExpandMethod.NEW, 200, 12);
            double sum = 174.75 + 40.58 + 1.0;
            assertThat(res.sumE0()).isCloseTo(sum, within(EPS));
            assertThat(res.emds().get(0).weight()).isCloseTo(174.75 / sum, within(EPS));
            assertThat(res.emds().get(2).weight()).isZero();
            assertThat(res.emds().get(2).dltSlot()).isZero();
            assertThat(res.emds().stream().mapToDouble(ExpandEmdResult::dltSlot).sum()).isCloseTo(200.0, within(1e-6));
            for (ExpandEmdResult e : res.emds()) {
                assertThat(e.s1()).isCloseTo(e.base().s() + e.dltSlot(), within(EPS));
                assertThat(e.e1() + e.dltIlgl()).isCloseTo(e.base().e0(), within(EPS));
                assertThat(e.u1() - e.dltIlgl()).isCloseTo(e.base().u0(), within(EPS));
            }
        }

        @Test
        @DisplayName("ΔE_e 는 E0_e 를 넘지 않는다 — 방식·면수 전 조합")
        void absorptionCapped() {
            for (ExpandMethod m : ExpandMethod.values()) {
                for (int n : new int[] {50, 80, 100, 150, 200, 5000}) {
                    for (PolicySimEmdBaseline one : range) {
                        ExpandResult single = ExpandPolicyCalculator.compute(List.of(one), DEFAULT, m, n, 24);
                        assertThat(single.emds().get(0).dltIlgl()).isLessThanOrEqualTo(one.e0());
                        assertThat(single.emds().get(0).e1()).isGreaterThanOrEqualTo(0.0);
                    }
                    ExpandPolicyCalculator.compute(range, DEFAULT, m, n, 24).emds()
                            .forEach(e -> assertThat(e.dltIlgl()).isLessThanOrEqualTo(e.base().e0()));
                }
            }
        }

        @Test
        @DisplayName("C1 = k × E1 · NO_ENF 는 C1 null · 기준 S=0(NO_SPLY) 은 H1 null 이고 C1 은 낸다")
        void c1h1() {
            ExpandResult res = ExpandPolicyCalculator.compute(range, DEFAULT, ExpandMethod.VACLOT, 100, 12);
            ExpandEmdResult a = res.emds().get(0);
            assertThat(a.c1()).isCloseTo(a.base().k() * a.e1(), within(EPS));
            assertThat(a.h1()).isCloseTo((a.u1() + a.e1()) / a.s1(), within(EPS));
            assertThat(res.emds().get(2).c1()).isNull();
            assertThat(res.emds().get(2).h1()).isNotNull();          // NO_ENF 라도 S>0 이면 읍면동 H1 은 있다(범위 판정은 Step 4)
            assertThat(res.emds().get(3).h1()).isNull();
            assertThat(res.emds().get(3).c1()).isNotNull();
        }
    }

    @Nested
    @DisplayName("흡수 포화 SATURATED")
    class Saturation {

        @Test
        @DisplayName("동홍동형(E0 40.58) 공한지 100면 12개월 → α×ΔS 48.4 ≥ E0 → E1=0 · R=−1 · 포화")
        void singleSaturated() {
            ExpandResult res = ExpandPolicyCalculator.compute(
                    List.of(emd("50130105", "서귀포시", 30, 40.58, 900)), DEFAULT, ExpandMethod.VACLOT, 100, 12);
            ExpandEmdResult e = res.emds().get(0);
            assertThat(res.alphaE() * e.dltSlot()).isCloseTo(48.4, within(1e-9));
            assertThat(e.dltIlgl()).isCloseTo(40.58, within(EPS));
            assertThat(e.e1()).isZero();
            assertThat(e.saturated()).isTrue();
            assertThat(res.excs()).isTrue();
            assertThat(r(res)).isCloseTo(-1.0, within(EPS));
        }

        @Test
        @DisplayName("연동형(E0 174.75) 같은 조건 → 포화 아님")
        void singleNotSaturated() {
            ExpandResult res = ExpandPolicyCalculator.compute(
                    List.of(emd("50110137", "제주시", 818.6, 174.75, 2315)), DEFAULT, ExpandMethod.VACLOT, 100, 12);
            assertThat(res.excs()).isFalse();
            assertThat(res.emds().get(0).dltIlgl()).isCloseTo(48.4, within(1e-9));
        }

        @Test
        @DisplayName("🔴 NO_ENF(E0=0) 가 섞인 범위 — 0 ≥ 0 오판정 없이 excs=false")
        void noEnfExcludedFromRange() {
            List<PolicySimEmdBaseline> range = List.of(
                    emd("S1", "서귀포시", 100, 200.0, 1000),
                    emd("S2", "서귀포시", 50, 84.12, 800),
                    emd("S3", "서귀포시", 0, 0.0, 0),      // 영남동형 — 전부 0
                    emd("S4", "서귀포시", 2, 0.0, 311));   // NO_ENF · S>0
            ExpandResult res = ExpandPolicyCalculator.compute(range, DEFAULT, ExpandMethod.VACLOT, 200, 12);
            assertThat(res.excs()).isFalse();
            res.emds().forEach(e -> assertThat(e.saturated()).isFalse());
        }

        @Test
        @DisplayName("단일 NO_ENF 읍면동 → excs=false · 배분 0 · C1 null")
        void singleNoEnf() {
            ExpandResult res = ExpandPolicyCalculator.compute(
                    List.of(emd("50110330", "제주시", 3.13, 0.0, 311)), DEFAULT, ExpandMethod.VACLOT, 200, 12);
            assertThat(res.excs()).isFalse();
            assertThat(res.sumE0()).isZero();
            ExpandEmdResult e = res.emds().get(0);
            assertThat(e.weight()).isZero();
            assertThat(e.dltSlot()).isZero();
            assertThat(e.dltIlgl()).isZero();
            assertThat(e.c1()).isNull();
        }

        @Test
        @DisplayName("범위 배분은 α×ΔS_eff ≥ ΣE0 일 때만 포화 — 그때는 E0>0 인 전 읍면동이 함께 포화, NO_ENF 는 제외")
        void rangeSaturatesTogether() {
            List<PolicySimEmdBaseline> range = List.of(
                    emd("X1", "서귀포시", 10, 25.0, 500),
                    emd("X2", "서귀포시", 5, 15.0, 0),     // NO_SPLY — 포화와 함께 올 수 있다
                    emd("X3", "서귀포시", 1, 0.0, 100));   // NO_ENF
            ExpandResult sat = ExpandPolicyCalculator.compute(range, DEFAULT, ExpandMethod.VACLOT, 100, 12); // 48.4 ≥ 40
            assertThat(sat.excs()).isTrue();
            assertThat(sat.emds().get(0).saturated()).isTrue();
            assertThat(sat.emds().get(1).saturated()).isTrue();
            assertThat(sat.emds().get(1).base().calcStts()).isEqualTo(PolicySimCalcStts.NO_SPLY);
            assertThat(sat.emds().get(2).saturated()).isFalse();

            ExpandResult notSat = ExpandPolicyCalculator.compute(range, DEFAULT, ExpandMethod.VACLOT, 50, 12); // 24.2 < 40
            assertThat(notSat.excs()).isFalse();
        }
    }

    @Nested
    @DisplayName("조회 범위")
    class Scope {

        private final List<PolicySimEmdBaseline> all = List.of(
                emd("50110137", "제주시", 1, 1, 1),
                emd("50110330", "제주시", 1, 0, 1),
                emd("50130105", "서귀포시", 1, 1, 1));

        @Test
        @DisplayName("emdCd 우선 → 단일 · sggNm → 시군구 · 둘 다 null → 제주 전체")
        void pick() {
            assertThat(ExpandPolicyCalculator.scope(all, "서귀포시", "50110137")).extracting(PolicySimEmdBaseline::emdCd)
                    .containsExactly("50110137");
            assertThat(ExpandPolicyCalculator.scope(all, "제주시", null)).hasSize(2);
            assertThat(ExpandPolicyCalculator.scope(all, null, null)).hasSize(3);
        }

        @Test
        @DisplayName("빈 범위·잘못된 입력은 예외 — 0 으로 계산하지 않는다")
        void invalid() {
            assertThatThrownBy(() -> ExpandPolicyCalculator.scope(all, null, "99999999"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ExpandPolicyCalculator.scope(all, "부산시", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ExpandPolicyCalculator.compute(all, DEFAULT, ExpandMethod.NEW, 0, 12))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ExpandPolicyCalculator.compute(all, DEFAULT, null, 100, 12))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("명세 13장 — 동홍동 예시 기준선 (S 1,819 · occ 0.95 · E0 527 · C0 44 · N 100)")
    class SpecCases {

        private final PolicySimParams p = PolicySimBaselineTest.params(Map.of("PCSHR_CRTR_VL", new BigDecimal("0.95")));
        private final List<PolicySimEmdBaseline> dh = List.of(emd("50130105", "서귀포시", 44.0, 527.0, 1819, 0.95));

        @Test
        @DisplayName("주입 기준선 — U0 1,728.05 · k 0.08349 · H0 1.240")
        void baseline() {
            PolicySimEmdBaseline b = dh.get(0);
            assertThat(b.u0()).isCloseTo(1728.05, within(1e-9));
            assertThat(b.k()).isCloseTo(0.08349, within(5e-6));
            assertThat(b.h0()).isCloseTo(1.240, within(5e-4));
        }

        void check(ExpandMethod m, int t, double dsEff, double expR, double expI) {
            ExpandResult res = ExpandPolicyCalculator.compute(dh, p, m, 100, t);
            assertThat(res.vldAddPkltSlot()).isCloseTo(dsEff, within(1e-9));
            assertThat(res.alphaE()).isCloseTo(0.50, within(EPS));
            assertThat(res.excs()).isFalse();
            assertThat(round4(r(res))).isEqualTo(expR);
            assertThat(round4(i(res))).isEqualTo(expI);
        }

        @Test
        @DisplayName("A 공한지 100면 12개월 → ΔS_eff 96.8 · R −0.0918 · I 0.0505")
        void caseA() { check(ExpandMethod.VACLOT, 12, 96.8, -0.0918, 0.0505); }

        @Test
        @DisplayName("A3 공한지 100면 3개월 → ΔS_eff 99.2 · R −0.0941 · I 0.0517")
        void caseA3() { check(ExpandMethod.VACLOT, 3, 99.2, -0.0941, 0.0517); }

        @Test
        @DisplayName("B 복층화 100면 12개월 → ΔS_eff 100 · R −0.0949 · I 0.0521")
        void caseB() { check(ExpandMethod.MULTFLR, 12, 100, -0.0949, 0.0521); }

        @Test
        @DisplayName("C 신규 100면 12개월 → ΔS_eff 100 · R −0.0949 · I 0.0521")
        void caseC() { check(ExpandMethod.NEW, 12, 100, -0.0949, 0.0521); }

        @Test
        @DisplayName("ΔE — A 48.4 · A3 49.6 · B·C 50.0")
        void dltIlgl() {
            assertThat(ExpandPolicyCalculator.compute(dh, p, ExpandMethod.VACLOT, 100, 12).emds().get(0).dltIlgl()).isCloseTo(48.4, within(1e-9));
            assertThat(ExpandPolicyCalculator.compute(dh, p, ExpandMethod.VACLOT, 100, 3).emds().get(0).dltIlgl()).isCloseTo(49.6, within(1e-9));
            assertThat(ExpandPolicyCalculator.compute(dh, p, ExpandMethod.MULTFLR, 100, 12).emds().get(0).dltIlgl()).isCloseTo(50.0, within(1e-9));
            assertThat(ExpandPolicyCalculator.compute(dh, p, ExpandMethod.NEW, 100, 12).emds().get(0).dltIlgl()).isCloseTo(50.0, within(1e-9));
        }
    }
}
