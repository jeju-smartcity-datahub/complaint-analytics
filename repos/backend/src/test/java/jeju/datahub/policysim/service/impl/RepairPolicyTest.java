package jeju.datahub.policysim.service.impl;

import static jeju.datahub.policysim.service.impl.ExpandPolicyTest.emd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jeju.datahub.policysim.service.impl.PolicySimOutputCalculator.Indicators;

/**
 * 이면도로 정비 변화식 + 풍선효과 단위테스트.
 *
 * <p>🔴 <b>실데이터 값을 단언하지 않는다</b> — 기준선·인접을 직접 주입한다(연동 C0 818.6 · E0 174.75 는 예시값을
 * 주입 데이터로만 쓴다). 실값·응답 키 확인은 curl 이다. DB 없이 돈다.
 */
class RepairPolicyTest {

    private static final double EPS = 1e-9;

    /** INSERT 기본값 — n_100 주거 14.4 · 상업 12 · α_B 0.8 · s 0.2 */
    private static final RepairCoef COEF = new RepairCoef(14.4, 12.0, 0.8, 0.2);

    private static RepairCoef withS(double s) {
        return new RepairCoef(14.4, 12.0, 0.8, s);
    }

    /** 연동 예시 — C0 818.6 · E0 174.75 · S 1000 (S 는 임의) */
    private static final PolicySimEmdBaseline YEON = emd("50110137", "제주시", 818.6, 174.75, 1000);

    /** k 가 서로 다른 인접 2곳 — k = 6.0 · 2.0 (대상 k ≈ 4.68 과 다르다) */
    private static final PolicySimEmdBaseline ADJ_A = emd("50110101", "제주시", 600.0, 100.0, 500);
    private static final PolicySimEmdBaseline ADJ_B = emd("50110102", "제주시", 100.0, 50.0, 300);
    /** 단속 없음(NO_ENF) 인접 — 배분에서 빠져야 한다 */
    private static final PolicySimEmdBaseline ADJ_NOENF = emd("50110103", "제주시", 30.0, 0.0, 200);

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    @Nested
    @DisplayName("ΔS = L ÷ 100 × n_100")
    class Slot {

        @Test
        @DisplayName("600m 주거지역형 → 6 × 14.4 = 86.4면 · 상업지역형 → 6 × 12 = 72면")
        void dltSlotByType() {
            assertThat(RepairPolicyCalculator.dltSlot(RoadMtncType.RSDNC, 600, COEF)).isCloseTo(86.4, within(EPS));
            assertThat(RepairPolicyCalculator.dltSlot(RoadMtncType.CMRC, 600, COEF)).isCloseTo(72.0, within(EPS));

            RepairResult r = RepairPolicyCalculator.compute(List.of(YEON), List.of(), COEF, RoadMtncType.RSDNC, 600);
            assertThat(r.dltSlot()).isCloseTo(86.4, within(EPS));
            assertThat(r.n100()).isEqualTo(14.4);
            // 응답 vldAddPkltSlot = NUMERIC(8,2) 반올림 → 86.40
            assertThat(PolicySimOutputCalculator.scale(r.dltSlot(), 2)).isEqualByComparingTo("86.40");
        }

        @Test
        @DisplayName("대상 여러 곳 — ΔS 를 E0 비율로 배분하고 S1 = S + ΔS_e (Σ ΔS_e = ΔS · NO_ENF 는 0)")
        void allocateByE0() {
            PolicySimEmdBaseline a = emd("A", "제주시", 30.0, 30.0, 100);
            PolicySimEmdBaseline b = emd("B", "제주시", 10.0, 10.0, 100);
            PolicySimEmdBaseline z = emd("Z", "제주시", 5.0, 0.0, 100);
            RepairResult r = RepairPolicyCalculator.compute(List.of(a, b, z), List.of(), COEF, RoadMtncType.CMRC, 100);

            assertThat(r.emds().get(0).dltSlot()).isCloseTo(9.0, within(EPS));
            assertThat(r.emds().get(1).dltSlot()).isCloseTo(3.0, within(EPS));
            assertThat(r.emds().get(2).dltSlot()).isZero();
            assertThat(r.emds().get(0).s1()).isCloseTo(109.0, within(EPS));
            assertThat(r.emds().stream().mapToDouble(ExpandEmdResult::dltSlot).sum()).isCloseTo(12.0, within(EPS));
        }
    }

    @Nested
    @DisplayName("ΔE · 포화")
    class Absorb {

        @Test
        @DisplayName("연동 600m 주거 → ΔE = min(174.75, 0.8 × 86.4) = 69.12 · 풍선 = 0.2 × 69.12 = 13.824")
        void yeonExample() {
            RepairResult r = RepairPolicyCalculator.compute(List.of(YEON), List.of(ADJ_A, ADJ_B), COEF, RoadMtncType.RSDNC, 600);
            ExpandEmdResult e = r.emds().get(0);
            assertThat(e.dltIlgl()).isCloseTo(69.12, within(1e-9));
            assertThat(r.balloon()).isCloseTo(13.824, within(1e-9));
            assertThat(e.e1()).isCloseTo(174.75 - 69.12, within(1e-9));
            assertThat(e.u1()).isCloseTo(1000 * 0.85 + 69.12, within(1e-9));
            assertThat(e.saturated()).isFalse();
            assertThat(r.excs()).isFalse();
        }

        @Test
        @DisplayName("ΔE_e 는 E0_e 를 넘지 않는다 — 긴 연장(10km)에서도")
        void dltNeverExceedsE0() {
            PolicySimEmdBaseline a = emd("A", "제주시", 30.0, 30.0, 100);
            PolicySimEmdBaseline b = emd("B", "제주시", 10.0, 10.0, 100);
            for (int len : new int[] {1, 100, 600, 10_000}) {
                RepairResult r = RepairPolicyCalculator.compute(List.of(a, b), List.of(ADJ_A), COEF, RoadMtncType.RSDNC, len);
                for (ExpandEmdResult e : r.emds()) {
                    assertThat(e.dltIlgl()).isLessThanOrEqualTo(e.base().e0());
                    assertThat(e.dltIlgl()).isGreaterThanOrEqualTo(0.0);
                }
            }
        }

        @Test
        @DisplayName("ΔE = E0 로 잘리면 포화 true — E0 > 0 인 대상만 판정(NO_ENF 는 false)")
        void saturation() {
            PolicySimEmdBaseline small = emd("A", "제주시", 10.0, 5.0, 100);
            PolicySimEmdBaseline noEnf = emd("Z", "제주시", 3.0, 0.0, 100);
            // ΔS = 6 × 14.4 = 86.4 → α_B × ΔS = 69.12 ≥ 5
            RepairResult r = RepairPolicyCalculator.compute(List.of(small, noEnf), List.of(ADJ_A), COEF, RoadMtncType.RSDNC, 600);
            assertThat(r.emds().get(0).dltIlgl()).isCloseTo(5.0, within(EPS));
            assertThat(r.emds().get(0).saturated()).isTrue();
            assertThat(r.emds().get(1).saturated()).isFalse();
            assertThat(r.excs()).isTrue();

            // NO_ENF 만 있는 범위 — ΔS_e = 0 이라 0 ≥ 0 이지만 포화가 아니다 · 배분·풍선 없음
            RepairResult onlyNoEnf = RepairPolicyCalculator.compute(List.of(noEnf), List.of(ADJ_A), COEF, RoadMtncType.RSDNC, 600);
            assertThat(onlyNoEnf.excs()).isFalse();
            assertThat(onlyNoEnf.balloon()).isZero();
            assertThat(onlyNoEnf.adjcDltCvlcpt()).isZero();
        }
    }

    @Nested
    @DisplayName("풍선효과")
    class Balloon {

        @Test
        @DisplayName("인접 2곳 → 각 E1 > E0 · 증가 합 = s × Σ ΔE · E0 비율 배분")
        void adjacentIncrease() {
            RepairResult r = RepairPolicyCalculator.compute(List.of(YEON), List.of(ADJ_A, ADJ_B), COEF, RoadMtncType.RSDNC, 600);
            double inc = 0;
            for (RepairAdjcEmdResult n : r.adjcEmds()) {
                assertThat(n.e1()).isGreaterThan(n.base().e0());
                inc += n.e1() - n.base().e0();
            }
            assertThat(inc).isCloseTo(0.2 * r.sumDltIlgl(), within(1e-9));
            assertThat(r.adjcEmds().get(0).weight()).isCloseTo(100.0 / 150.0, within(EPS));
            assertThat(r.adjcEmds().get(0).dltIlgl()).isCloseTo(13.824 * 100.0 / 150.0, within(1e-9));
            assertThat(r.adjcEmdCnt()).isEqualTo(2);
            assertThat(r.balloonToTarget()).isFalse();
            // 대상 E1 에는 풍선분이 더해지지 않는다
            assertThat(r.emds().get(0).e1()).isCloseTo(174.75 - 69.12, within(1e-9));
        }

        @Test
        @DisplayName("인접 민원 증가분 = Σ k_n × 배분_n — 대상 k 하나를 곱한 값과 다르다")
        void adjacentCvlcptUsesOwnK() {
            RepairResult r = RepairPolicyCalculator.compute(List.of(YEON), List.of(ADJ_A, ADJ_B), COEF, RoadMtncType.RSDNC, 600);
            double balloon = 0.2 * r.sumDltIlgl();
            double expected = 6.0 * balloon * 100.0 / 150.0 + 2.0 * balloon * 50.0 / 150.0;
            assertThat(round2(r.adjcDltCvlcpt())).isEqualTo(round2(expected));
            // C1_n − C0_n 과도 같다
            double viaC1 = r.adjcEmds().stream().mapToDouble(n -> n.c1() - n.base().c0()).sum();
            assertThat(round2(viaC1)).isEqualTo(round2(expected));

            double targetK = YEON.k();
            assertThat(round2(r.adjcDltCvlcpt())).isNotEqualTo(round2(targetK * balloon));
        }

        @Test
        @DisplayName("E0_n = 0 인 인접은 배분에서 뺀다 — weight 0 · E1 = E0 · 남은 인접이 풍선분 전부를 받는다")
        void excludeNoEnfAdjacent() {
            RepairResult r = RepairPolicyCalculator.compute(List.of(YEON), List.of(ADJ_A, ADJ_NOENF, ADJ_B),
                    COEF, RoadMtncType.RSDNC, 600);
            RepairAdjcEmdResult z = r.adjcEmds().get(1);
            assertThat(z.weight()).isZero();
            assertThat(z.e1()).isEqualTo(0.0);
            assertThat(z.c1()).isNull();
            assertThat(z.dltCvlcpt()).isZero();
            assertThat(r.adjcEmdCnt()).isEqualTo(2);
            double inc = r.adjcEmds().stream().mapToDouble(RepairAdjcEmdResult::dltIlgl).sum();
            assertThat(inc).isCloseTo(r.balloon(), within(1e-9));
            assertThat(r.adjcEmds()).hasSize(3);   // 입력 순서 유지
        }

        @Test
        @DisplayName("인접 공집합 → 풍선분을 대상 E1 에 가산 · 인접 증가분 0 · |R| 이 s=0 보다 작다(덜 감소)")
        void noAdjacentAddsToTarget() {
            RepairResult r = RepairPolicyCalculator.compute(List.of(YEON), List.of(), COEF, RoadMtncType.RSDNC, 600);
            assertThat(r.balloonToTarget()).isTrue();
            assertThat(r.adjcEmdCnt()).isZero();
            assertThat(r.adjcDltCvlcpt()).isZero();
            assertThat(r.emds().get(0).e1()).isCloseTo(174.75 - 69.12 + 13.824, within(1e-9));

            RepairResult noBalloon = RepairPolicyCalculator.compute(List.of(YEON), List.of(), withS(0.0), RoadMtncType.RSDNC, 600);
            double rWith = PolicySimOutputCalculator.indicators(r).r();
            double rWithout = PolicySimOutputCalculator.indicators(noBalloon).r();
            assertThat(rWith).isNegative();
            assertThat(Math.abs(rWith)).isLessThan(Math.abs(rWithout));

            // null 도 공집합으로 본다
            RepairResult nullAdj = RepairPolicyCalculator.compute(List.of(YEON), null, COEF, RoadMtncType.RSDNC, 600);
            assertThat(nullAdj.emds().get(0).e1()).isCloseTo(r.emds().get(0).e1(), within(EPS));
        }

        @Test
        @DisplayName("인접이 전부 E0 = 0 → 인접 없음과 같다(대상에 가산)")
        void allNoEnfAdjacentIsEmpty() {
            RepairResult r = RepairPolicyCalculator.compute(List.of(YEON), List.of(ADJ_NOENF), COEF, RoadMtncType.RSDNC, 600);
            RepairResult empty = RepairPolicyCalculator.compute(List.of(YEON), List.of(), COEF, RoadMtncType.RSDNC, 600);
            assertThat(r.balloonToTarget()).isTrue();
            assertThat(r.adjcEmdCnt()).isZero();
            assertThat(r.adjcDltCvlcpt()).isZero();
            assertThat(r.emds().get(0).e1()).isCloseTo(empty.emds().get(0).e1(), within(EPS));
            assertThat(r.adjcEmds().get(0).e1()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("대상 여러 곳에 가산할 때는 대상 E0 비율로 나눈다(합 = 풍선분)")
        void addBackByTargetWeight() {
            PolicySimEmdBaseline a = emd("A", "제주시", 300.0, 300.0, 1000);
            PolicySimEmdBaseline b = emd("B", "서귀포시", 100.0, 100.0, 1000);
            RepairResult r = RepairPolicyCalculator.compute(List.of(a, b), List.of(), COEF, RoadMtncType.CMRC, 100);
            double back = 0;
            for (ExpandEmdResult e : r.emds()) {
                back += e.e1() - (e.base().e0() - e.dltIlgl());
            }
            assertThat(back).isCloseTo(r.balloon(), within(1e-9));
            double backA = r.emds().get(0).e1() - (300.0 - r.emds().get(0).dltIlgl());
            assertThat(backA).isCloseTo(r.balloon() * 0.75, within(1e-9));
        }
    }

    @Nested
    @DisplayName("출력 지표 — R(순효과) · I(대상만)")
    class Output {

        @Test
        @DisplayName("R = (Σ_대상 (C1 − C0) + Σ_인접 k_n × 배분_n) ÷ Σ_대상 C0 · C1 합 = ΣC0 × (1 + R)")
        void netEffect() {
            RepairResult r = RepairPolicyCalculator.compute(List.of(YEON), List.of(ADJ_A, ADJ_B), COEF, RoadMtncType.RSDNC, 600);
            Indicators ind = PolicySimOutputCalculator.indicators(r);

            double targetDelta = r.emds().get(0).c1() - 818.6;
            double expectedR = (targetDelta + r.adjcDltCvlcpt()) / 818.6;
            assertThat(ind.r()).isCloseTo(expectedR, within(EPS));
            assertThat(ind.c0()).isCloseTo(818.6, within(EPS));      // 분모는 대상만
            assertThat(ind.c1()).isCloseTo(818.6 * (1 + ind.r()), within(1e-9));
            // 인접 증가분이 있으니 대상만 본 R 보다 덜 감소
            assertThat(ind.r()).isGreaterThan(targetDelta / 818.6);
        }

        @Test
        @DisplayName("s 를 0.2 → 0.3 으로 올리면 R 이 덜 감소한다")
        void higherBalloonLessReduction() {
            double r02 = PolicySimOutputCalculator.indicators(RepairPolicyCalculator.compute(
                    List.of(YEON), List.of(ADJ_A, ADJ_B), withS(0.2), RoadMtncType.RSDNC, 600)).r();
            double r03 = PolicySimOutputCalculator.indicators(RepairPolicyCalculator.compute(
                    List.of(YEON), List.of(ADJ_A, ADJ_B), withS(0.3), RoadMtncType.RSDNC, 600)).r();
            assertThat(r03).isGreaterThan(r02);

            // 인접 없음(대상 가산)에서도 같은 방향
            double e02 = PolicySimOutputCalculator.indicators(RepairPolicyCalculator.compute(
                    List.of(YEON), List.of(), withS(0.2), RoadMtncType.RSDNC, 600)).r();
            double e03 = PolicySimOutputCalculator.indicators(RepairPolicyCalculator.compute(
                    List.of(YEON), List.of(), withS(0.3), RoadMtncType.RSDNC, 600)).r();
            assertThat(e03).isGreaterThan(e02);
        }

        @Test
        @DisplayName("풍선효과가 크면 R 이 양수가 될 수 있다 — 부호를 그대로 낸다")
        void positiveRAllowed() {
            // 인접 k 가 매우 크면 순효과가 증가
            PolicySimEmdBaseline hotAdj = emd("H", "제주시", 10_000.0, 10.0, 100);
            Indicators ind = PolicySimOutputCalculator.indicators(RepairPolicyCalculator.compute(
                    List.of(YEON), List.of(hotAdj), withS(0.3), RoadMtncType.RSDNC, 600));
            assertThat(ind.r()).isPositive();
        }

        @Test
        @DisplayName("혼잡 지수 H0_T·H1_T·I 는 대상만 — 인접 기준선을 바꿔도 같다")
        void congestionTargetOnly() {
            Indicators withAdj = PolicySimOutputCalculator.indicators(RepairPolicyCalculator.compute(
                    List.of(YEON), List.of(ADJ_A, ADJ_B), COEF, RoadMtncType.RSDNC, 600));
            Indicators otherAdj = PolicySimOutputCalculator.indicators(RepairPolicyCalculator.compute(
                    List.of(YEON), List.of(emd("X", "서귀포시", 1.0, 999.0, 99_999)), COEF, RoadMtncType.RSDNC, 600));
            assertThat(withAdj.h0()).isCloseTo(otherAdj.h0(), within(EPS));
            assertThat(withAdj.h1()).isCloseTo(otherAdj.h1(), within(EPS));
            assertThat(withAdj.i()).isCloseTo(otherAdj.i(), within(EPS));
            assertThat(withAdj.e0()).isCloseTo(174.75, within(EPS));
            assertThat(withAdj.s()).isEqualTo(1000L);

            double h0 = (850 + 174.75) / 1000.0;
            double h1 = (850 + 69.12 + 174.75 - 69.12) / (1000 + 86.4);
            assertThat(withAdj.i()).isCloseTo((h0 - h1) / h0, within(1e-9));
        }

        @Test
        @DisplayName("확충 경로의 indicators 는 인접 증가분 0 — 종전 산식과 같다")
        void expandUnchanged() {
            ExpandResult ex = ExpandPolicyCalculator.compute(List.of(YEON), PolicySimBaselineTest.params(java.util.Map.of()),
                    ExpandMethod.NEW, 100, 12);
            Indicators ind = PolicySimOutputCalculator.indicators(ex);
            assertThat(ind.r()).isCloseTo(ExpandPolicyTest.r(ex), within(EPS));
            assertThat(ind.c1()).isCloseTo(ex.emds().get(0).c1(), within(EPS));
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {

        @Test
        @DisplayName("연장 ≤ 0 · 방식 없음 · 대상 없음 · 대상과 인접 중복 → IllegalArgumentException")
        void invalidInputs() {
            assertThatThrownBy(() -> RepairPolicyCalculator.compute(List.of(YEON), List.of(), COEF, RoadMtncType.RSDNC, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RepairPolicyCalculator.compute(List.of(YEON), List.of(), COEF, null, 600))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RepairPolicyCalculator.compute(List.of(), List.of(), COEF, RoadMtncType.RSDNC, 600))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RepairPolicyCalculator.compute(List.of(YEON), List.of(YEON), COEF, RoadMtncType.RSDNC, 600))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("50110137");
        }

        @Test
        @DisplayName("계수 — 음수·s > 1 은 거부")
        void invalidCoef() {
            assertThatThrownBy(() -> new RepairCoef(-1, 12, 0.8, 0.2)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RepairCoef(14.4, 12, 0.8, 1.5)).isInstanceOf(IllegalArgumentException.class);
            assertThat(RoadMtncType.RSDNC.n100(COEF)).isEqualTo(14.4);
            assertThat(RoadMtncType.CMRC.n100(COEF)).isEqualTo(12.0);
        }
    }
}
