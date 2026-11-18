package jeju.datahub.policysim.service.impl;

import static jeju.datahub.policysim.service.impl.ExpandPolicyTest.emd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jeju.datahub.policysim.service.impl.PolicySimOutputCalculator.Indicators;

/**
 * 요금제 적용 변화식 단위테스트 (민감도·경고).
 *
 * <p>🔴 <b>실데이터 값을 단언하지 않는다</b> — 기준선·적용 대상 면수·인접을 직접 주입한다. 실값(제주 전체 V·R 등)은 curl 이다.
 * DB 없이 돈다. 기준선 행은 {@link ExpandPolicyTest#emd} (U0 = S × 0.85 · k = C0 ÷ E0) 를 쓴다.
 */
class FeePolicyTest {

    private static final double EPS = 1e-9;

    /** INSERT 기본값(ε −0.3 · λ 0.3 · τ 0.2 · τ_local 0.5) + 공유 occ 0.85 · occ_th 0.85 · α 0.5(임의) */
    private static final FeeCoef COEF = new FeeCoef(0.85, 0.85, 0.5, -0.3, 0.3, 0.2, 0.5);

    private static final BigDecimal P10 = BigDecimal.TEN;

    /** 대상 2곳 — k = 3.0 · 2.0 */
    private static final PolicySimEmdBaseline T_A = emd("A", "제주시", 60.0, 20.0, 100);
    private static final PolicySimEmdBaseline T_B = emd("B", "제주시", 20.0, 10.0, 50);
    /** 단속 없음(NO_ENF) 대상 — 무료 면이 있어도 V·흡수·전이에서 빠진다 */
    private static final PolicySimEmdBaseline T_NOENF = emd("Z", "제주시", 5.0, 0.0, 60);

    /** 인접 — k = 6.0 · 2.0 · NO_ENF */
    private static final PolicySimEmdBaseline ADJ_A = emd("N1", "제주시", 600.0, 100.0, 500);
    private static final PolicySimEmdBaseline ADJ_B = emd("N2", "제주시", 100.0, 50.0, 300);
    private static final PolicySimEmdBaseline ADJ_NOENF = emd("N3", "제주시", 30.0, 0.0, 200);

    static FeeTargetRow slot(String emdCd, long n) {
        FeeTargetRow r = new FeeTargetRow();
        r.setEmdCd(emdCd);
        r.setEmdNm("동" + emdCd);
        r.setAplcnTrgtSlot(n);
        return r;
    }

    private static final List<FeeTargetRow> SLOTS = List.of(slot("A", 80), slot("B", 40));

    private static FeeResult convert(List<PolicySimEmdBaseline> t, List<FeeTargetRow> s, List<PolicySimEmdBaseline> adj, FeeCoef c) {
        return FeePolicyCalculator.compute(t, s, adj, c, FeeAplcnMthd.CONVERT, null);
    }

    private static FeeResult raise(List<PolicySimEmdBaseline> t, List<FeeTargetRow> s, List<PolicySimEmdBaseline> adj, FeeCoef c, BigDecimal p) {
        return FeePolicyCalculator.compute(t, s, adj, c, FeeAplcnMthd.RAISE, p);
    }

    @Nested
    @DisplayName("① 수요 감소 · ② 흡수 · ③ 전이 — 식 일치")
    class Formula {

        @Test
        @DisplayName("CONVERT — V_e = 면수 × occ × λ · 흡수 min(E0, αV) · 대상 전이 ττ_local V · 인접분 τ(1−τ_local)ΣV")
        void convertFormula() {
            FeeResult r = convert(List.of(T_A, T_B), SLOTS, List.of(ADJ_A, ADJ_B), COEF);

            double vA = 80 * 0.85 * 0.3;   // 20.4
            double vB = 40 * 0.85 * 0.3;   // 10.2
            assertThat(r.dmndRdctRt()).isCloseTo(0.3, within(EPS));
            assertThat(r.feeEmds().get(0).lvngVhcl()).isCloseTo(vA, within(EPS));
            assertThat(r.feeEmds().get(1).lvngVhcl()).isCloseTo(vB, within(EPS));
            assertThat(r.lvngVhclCnt()).isCloseTo(vA + vB, within(EPS));
            assertThat(r.aplcnTrgtSlot()).isEqualTo(120);

            // 흡수 — α × V 가 E0 보다 작아 잘리지 않는다
            assertThat(r.emds().get(0).dltIlgl()).isCloseTo(0.5 * vA, within(EPS));
            assertThat(r.emds().get(1).dltIlgl()).isCloseTo(0.5 * vB, within(EPS));
            assertThat(r.absrpCnt()).isCloseTo(0.5 * (vA + vB), within(EPS));
            assertThat(r.excs()).isFalse();

            // 전이 — 총량 τ × ΣV (τ_local 이 총량을 줄이지 않는다)
            assertThat(r.trnsfCnt()).isCloseTo(0.2 * (vA + vB), within(EPS));
            assertThat(r.trnsfLocalCnt()).isCloseTo(0.2 * 0.5 * (vA + vB), within(EPS));
            assertThat(r.trnsfAdjcCnt()).isCloseTo(0.2 * 0.5 * (vA + vB), within(EPS));
            assertThat(r.trnsfLocalCnt() + r.trnsfAdjcCnt()).isCloseTo(r.trnsfCnt(), within(EPS));

            // E1_e = E0 − ΔE + ττ_local V_e (인접이 있으므로 가산 없음) · U1 = U0 − V + ΔE · S1 = S
            ExpandEmdResult a = r.emds().get(0);
            assertThat(a.e1()).isCloseTo(20.0 - 0.5 * vA + 0.1 * vA, within(EPS));
            assertThat(a.u1()).isCloseTo(85.0 - vA + 0.5 * vA, within(EPS));
            assertThat(a.s1()).isEqualTo(100.0);
            assertThat(a.dltSlot()).isZero();
            assertThat(a.c1()).isCloseTo(3.0 * a.e1(), within(EPS));
            assertThat(a.h1()).isCloseTo((a.u1() + a.e1()) / 100.0, within(EPS));

            // 인접 E0 비율 배분 — 100 : 50
            double trAdj = 0.1 * (vA + vB);
            assertThat(r.trnsfToTarget()).isFalse();
            assertThat(r.adjcEmdCnt()).isEqualTo(2);
            assertThat(r.adjcEmds().get(0).dltIlgl()).isCloseTo(trAdj * 100.0 / 150.0, within(EPS));
            assertThat(r.adjcEmds().get(1).dltIlgl()).isCloseTo(trAdj * 50.0 / 150.0, within(EPS));
            assertThat(r.adjcDltCvlcpt()).isCloseTo(6.0 * trAdj * 100.0 / 150.0 + 2.0 * trAdj * 50.0 / 150.0, within(EPS));
            assertThat(r.feeEmds().get(0).trnsfBack()).isZero();
        }

        @Test
        @DisplayName("RAISE — d = −ε × p ÷ 100 · V = 면수 × occ × d · λ 를 바꿔도 결과 불변")
        void raiseFormula() {
            FeeResult r = raise(List.of(T_A, T_B), SLOTS, List.of(ADJ_A), COEF, P10);
            double d = 0.3 * 10 / 100.0;    // 0.03
            assertThat(r.dmndRdctRt()).isCloseTo(d, within(EPS));
            assertThat(r.feeIncrsRt()).isEqualByComparingTo("10");
            assertThat(r.lvngVhclCnt()).isCloseTo(120 * 0.85 * d, within(EPS));
            assertThat(r.absrpCnt()).isCloseTo(0.5 * 120 * 0.85 * d, within(EPS));
            assertThat(r.trnsfCnt()).isCloseTo(0.2 * 120 * 0.85 * d, within(EPS));

            FeeResult otherLambda = raise(List.of(T_A, T_B), SLOTS, List.of(ADJ_A), COEF.withLambda(0.5), P10);
            assertThat(otherLambda.lvngVhclCnt()).isEqualTo(r.lvngVhclCnt());
            assertThat(PolicySimOutputCalculator.indicators(otherLambda).r())
                    .isEqualTo(PolicySimOutputCalculator.indicators(r).r());
        }

        @Test
        @DisplayName("CONVERT 는 인상률을 무시한다 — 결과 feeIncrsRt null · ε 를 바꿔도 불변")
        void convertIgnoresRaiseInputs() {
            FeeResult r = FeePolicyCalculator.compute(List.of(T_A), List.of(slot("A", 80)), List.of(), COEF,
                    FeeAplcnMthd.CONVERT, BigDecimal.valueOf(30));
            FeeResult otherEps = convert(List.of(T_A), List.of(slot("A", 80)), List.of(), COEF.withEps(-0.6));
            assertThat(r.feeIncrsRt()).isNull();
            assertThat(otherEps.lvngVhclCnt()).isEqualTo(r.lvngVhclCnt());
        }

        @Test
        @DisplayName("대상 읍면동에 null 원소가 섞이면 IllegalArgumentException(400 경로) — NPE 로 새지 않는다 (시큐어코딩 5.1)")
        void rejectsNullTargetElement() {
            List<PolicySimEmdBaseline> withNull = Arrays.asList(T_A, null);
            assertThatThrownBy(() -> convert(withNull, List.of(slot("A", 80)), List.of(), COEF))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("비어 있는 읍면동");
        }

        @Test
        @DisplayName("방식이 매퍼 crgYn 을 고른다 — RAISE 'Y' · CONVERT 'N'")
        void crgYn() {
            assertThat(FeeAplcnMthd.RAISE.crgYn()).isEqualTo("Y");
            assertThat(FeeAplcnMthd.CONVERT.crgYn()).isEqualTo("N");
        }
    }

    @Nested
    @DisplayName("ε 음수 · V 범위")
    class Sign {

        @Test
        @DisplayName("ε 은 음수인데 V 는 양수 — ε 이 클수록(절대값) V 가 크다")
        void negativeEpsGivesPositiveV() {
            FeeResult lo = raise(List.of(T_A), List.of(slot("A", 80)), List.of(), COEF.withEps(-0.1), P10);
            FeeResult hi = raise(List.of(T_A), List.of(slot("A", 80)), List.of(), COEF.withEps(-0.6), P10);
            assertThat(lo.lvngVhclCnt()).isPositive();
            assertThat(hi.lvngVhclCnt()).isGreaterThan(lo.lvngVhclCnt());
            assertThat(hi.lvngVhclCnt()).isCloseTo(80 * 0.85 * 0.06, within(EPS));
        }

        @Test
        @DisplayName("양수 ε · λ > 1 · τ_local > 1 은 계수로 받지 않는다")
        void rejectBadCoef() {
            assertThatThrownBy(() -> COEF.withEps(0.3)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> COEF.withLambda(1.2)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new FeeCoef(0.85, 0.85, 0.5, -0.3, 0.3, 0.2, 1.5))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("RAISE 인데 인상률이 없거나 0 → 거부 · −ε × p > 1 (V > U0_aply) → 거부")
        void rejectBadRaise() {
            assertThatThrownBy(() -> raise(List.of(T_A), SLOTS.subList(0, 1), List.of(), COEF, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> raise(List.of(T_A), SLOTS.subList(0, 1), List.of(), COEF, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> raise(List.of(T_A), SLOTS.subList(0, 1), List.of(), COEF.withEps(-0.6), BigDecimal.valueOf(200)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("U1 은 음수가 되지 않는다 — 면수 = S · λ 최대 1 · occ<occ_th 조합까지")
        void u1NonNegative() {
            PolicySimEmdBaseline full = emd("F", "제주시", 9.0, 3.0, 40);
            for (double lambda : new double[] {0.0, 0.15, 0.3, 0.5, 1.0}) {
                for (FeeCoef c : new FeeCoef[] {COEF.withLambda(lambda),
                        new FeeCoef(0.85, 0.9, 0.5, -0.3, lambda, 0.3, 0.3)}) {
                    FeeResult r = convert(List.of(full), List.of(slot("F", 40)), List.of(ADJ_A), c);
                    for (ExpandEmdResult e : r.emds()) {
                        assertThat(e.u1()).isGreaterThanOrEqualTo(-EPS);
                    }
                }
            }
            for (double eps : new double[] {-0.1, -0.3, -0.6}) {
                for (int p : new int[] {10, 20, 30}) {
                    FeeResult r = raise(List.of(full), List.of(slot("F", 40)), List.of(), COEF.withEps(eps), BigDecimal.valueOf(p));
                    assertThat(r.emds().get(0).u1()).isGreaterThanOrEqualTo(0.0);
                }
            }
        }

        @Test
        @DisplayName("적용 대상 면수 > S (기준선 정의 불일치) → 거부")
        void rejectSlotOverSupply() {
            assertThatThrownBy(() -> convert(List.of(T_B), List.of(slot("B", 51)), List.of(), COEF))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("흡수 on/off · 포화")
    class Absorb {

        @Test
        @DisplayName("occ < occ_th → 흡수 0 · 전이 > 0 · E1 > E0 · absorbs=false")
        void noAbsorbBelowThreshold() {
            FeeCoef low = new FeeCoef(0.70, 0.85, 0.5, -0.3, 0.3, 0.2, 0.5);
            FeeResult r = convert(List.of(T_A, T_B), SLOTS, List.of(ADJ_A), low);
            assertThat(r.absorbs()).isFalse();
            assertThat(r.absrpCnt()).isZero();
            assertThat(r.trnsfCnt()).isPositive();
            assertThat(r.excs()).isFalse();
            // V 는 occ 0.70 으로 계산된다
            assertThat(r.lvngVhclCnt()).isCloseTo(120 * 0.70 * 0.3, within(EPS));
            for (ExpandEmdResult e : r.emds()) {
                assertThat(e.dltIlgl()).isZero();
                assertThat(e.e1()).isGreaterThan(e.base().e0());
            }
        }

        @Test
        @DisplayName("α × V_e ≥ E0_e 면 흡수 = E0_e 로 잘리고 포화(excs) — NO_ENF 대상은 판정하지 않는다")
        void saturation() {
            PolicySimEmdBaseline small = emd("S", "제주시", 4.0, 2.0, 100);
            FeeResult r = convert(List.of(small, T_NOENF), List.of(slot("S", 100), slot("Z", 60)), List.of(ADJ_A), COEF);
            assertThat(r.emds().get(0).dltIlgl()).isCloseTo(2.0, within(EPS));   // α×V = 12.75 ≥ 2
            assertThat(r.emds().get(0).saturated()).isTrue();
            assertThat(r.emds().get(1).saturated()).isFalse();
            assertThat(r.excs()).isTrue();
        }
    }

    @Nested
    @DisplayName("인접 배분")
    class Adjacent {

        @Test
        @DisplayName("인접 없음 → 인접분 τ(1−τ_local)ΣV 를 대상에 E0 비율로 가산 · 인접 증가분 0")
        void noAdjacentAddsToTarget() {
            FeeResult r = convert(List.of(T_A, T_B), SLOTS, List.of(), COEF);
            double sumV = r.lvngVhclCnt();
            double trAdj = 0.2 * 0.5 * sumV;
            assertThat(r.trnsfToTarget()).isTrue();
            assertThat(r.adjcEmdCnt()).isZero();
            assertThat(r.adjcDltCvlcpt()).isZero();
            assertThat(r.feeEmds().get(0).trnsfBack()).isCloseTo(trAdj * 20.0 / 30.0, within(EPS));
            assertThat(r.feeEmds().get(1).trnsfBack()).isCloseTo(trAdj * 10.0 / 30.0, within(EPS));
            // 대상 Σ(E1 − E0) = −흡수 + 전이 총량 (전량이 대상에 남는다)
            double dE = r.emds().stream().mapToDouble(e -> e.e1() - e.base().e0()).sum();
            assertThat(dE).isCloseTo(-r.absrpCnt() + r.trnsfCnt(), within(EPS));

            // null 도 인접 없음
            FeeResult nullAdj = convert(List.of(T_A, T_B), SLOTS, null, COEF);
            assertThat(nullAdj.trnsfToTarget()).isTrue();
            assertThat(nullAdj.emds().get(0).e1()).isEqualTo(r.emds().get(0).e1());
        }

        @Test
        @DisplayName("E0_n = 0 인접은 배분에서 뺀다 · 전부 E0 = 0 이면 대상 가산")
        void excludeNoEnfAdjacent() {
            FeeResult r = convert(List.of(T_A, T_B), SLOTS, List.of(ADJ_A, ADJ_NOENF), COEF);
            assertThat(r.adjcEmds()).hasSize(2);
            assertThat(r.adjcEmds().get(1).weight()).isZero();
            assertThat(r.adjcEmds().get(1).dltIlgl()).isZero();
            assertThat(r.adjcEmdCnt()).isEqualTo(1);
            assertThat(r.adjcEmds().get(0).dltIlgl()).isCloseTo(r.trnsfAdjcCnt(), within(EPS));

            FeeResult onlyNoEnf = convert(List.of(T_A, T_B), SLOTS, List.of(ADJ_NOENF), COEF);
            assertThat(onlyNoEnf.trnsfToTarget()).isTrue();
            assertThat(onlyNoEnf.adjcDltCvlcpt()).isZero();
        }

        @Test
        @DisplayName("대상과 인접이 겹치거나 면수 행이 범위 밖이면 거부")
        void rejectMismatch() {
            assertThatThrownBy(() -> convert(List.of(T_A), List.of(slot("A", 10)), List.of(T_A), COEF))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> convert(List.of(T_A), List.of(slot("X", 10)), List.of(), COEF))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("NO_ENF · 급지 밖 대상")
    class NoEnf {

        @Test
        @DisplayName("NO_ENF 대상 — 면수는 집계하되 V·흡수·전이 0 · E1 = 0 · U1 = U0")
        void noEnfTargetExcluded() {
            FeeResult r = convert(List.of(T_A, T_NOENF), List.of(slot("A", 80), slot("Z", 60)), List.of(), COEF);
            assertThat(r.aplcnTrgtSlot()).isEqualTo(140);
            assertThat(r.lvngVhclCnt()).isCloseTo(80 * 0.85 * 0.3, within(EPS));   // Z 의 60면은 빠진다
            FeeEmdResult z = r.feeEmds().get(1);
            assertThat(z.aplcnTrgtSlot()).isEqualTo(60);
            assertThat(z.lvngVhcl()).isZero();
            assertThat(z.trnsfLocal()).isZero();
            assertThat(z.trnsfBack()).isZero();   // 인접분 가산도 E0 비율이라 0
            ExpandEmdResult ze = r.emds().get(1);
            assertThat(ze.e1()).isZero();
            assertThat(ze.u1()).isEqualTo(T_NOENF.u0());
            assertThat(ze.c1()).isNull();
        }

        @Test
        @DisplayName("행이 없는 대상(급지 밖) — 면수 0 · V 0 이지만 대상(R 분모)에는 남는다")
        void outsideZone() {
            FeeResult r = convert(List.of(T_A, T_B), List.of(slot("A", 80)), List.of(), COEF);
            assertThat(r.feeEmds().get(1).aplcnTrgtSlot()).isZero();
            assertThat(r.feeEmds().get(1).lvngVhcl()).isZero();
            assertThat(PolicySimOutputCalculator.indicators(r).c0()).isCloseTo(80.0, within(EPS));
        }

        @Test
        @DisplayName("범위 전체 NO_ENF — 계산은 끝나고 R 은 null(calcStts NO_ENF)")
        void allNoEnf() {
            FeeResult r = convert(List.of(T_NOENF), List.of(slot("Z", 60)), List.of(ADJ_A), COEF);
            assertThat(r.lvngVhclCnt()).isZero();
            assertThat(r.adjcDltCvlcpt()).isZero();
            Indicators ind = PolicySimOutputCalculator.indicators(r);
            assertThat(ind.stts()).isEqualTo(PolicySimCalcStts.NO_ENF);
            assertThat(ind.r()).isNull();
        }
    }

    @Nested
    @DisplayName("R — 흡수와 전이의 크기가 부호를 정한다 · 인접 포함 순효과")
    class Rate {

        @Test
        @DisplayName("흡수 > 전이 → R < 0 · 흡수 < 전이 → R > 0")
        void signByAbsorbVsTransfer() {
            // 흡수 αV = 0.5V > 전이 τV = 0.2V (포화 없음)
            PolicySimEmdBaseline big = emd("G", "제주시", 300.0, 100.0, 100);
            FeeResult down = raise(List.of(big), List.of(slot("G", 100)), List.of(), COEF, P10);
            assertThat(down.absrpCnt()).isGreaterThan(down.trnsfCnt());
            assertThat(PolicySimOutputCalculator.indicators(down).r()).isNegative();

            // 흡수가 E0 = 1 로 잘리고 전이 τV = 5.1 이 크다
            PolicySimEmdBaseline tiny = emd("T", "제주시", 3.0, 1.0, 100);
            FeeResult up = convert(List.of(tiny), List.of(slot("T", 100)), List.of(), COEF);
            assertThat(up.absrpCnt()).isLessThan(up.trnsfCnt());
            assertThat(PolicySimOutputCalculator.indicators(up).r()).isPositive();
            // 대상만이면 R = (−흡수 + 전이) × k ÷ C0
            assertThat(PolicySimOutputCalculator.indicators(up).r())
                    .isCloseTo((-up.absrpCnt() + up.trnsfCnt()) * 3.0 / 3.0, within(EPS));
        }

        @Test
        @DisplayName("R = Σ_{대상∪인접}(C1 − C0) ÷ Σ_대상 C0 · aftr ÷ crtr − 1 = R · τ=0 이면 인접 증가 0")
        void netEffectIncludesAdjacent() {
            FeeResult r = convert(List.of(T_A, T_B), SLOTS, List.of(ADJ_A, ADJ_B), COEF);
            Indicators ind = PolicySimOutputCalculator.indicators(r);

            double target = r.emds().stream().mapToDouble(e -> e.c1() - e.base().c0()).sum();
            double adj = r.adjcEmds().stream().filter(n -> n.c1() != null).mapToDouble(n -> n.c1() - n.base().c0()).sum();
            assertThat(r.adjcDltCvlcpt()).isCloseTo(adj, within(EPS));
            assertThat(ind.r()).isCloseTo((target + adj) / 80.0, within(EPS));
            assertThat(ind.c1() / ind.c0() - 1).isCloseTo(ind.r(), within(EPS));

            // 혼잡 지수 Σ 는 대상만 — 인접 유무로 H1 이 바뀌지 않으나 대상 E1 은 인접 가산 여부로 달라진다
            FeeResult noTau = convert(List.of(T_A, T_B), SLOTS, List.of(ADJ_A, ADJ_B), COEF.withTau(0.0));
            assertThat(noTau.trnsfCnt()).isZero();
            assertThat(noTau.adjcDltCvlcpt()).isZero();
            assertThat(PolicySimOutputCalculator.indicators(noTau).r()).isLessThan(ind.r());
        }

        @Test
        @DisplayName("τ 에 선형 — 전이 총량 = τ × ΣV (민감도가 계수 하나만 바꿔 재계산)")
        void linearInTau() {
            FeeResult t1 = convert(List.of(T_A, T_B), SLOTS, List.of(ADJ_A), COEF.withTau(0.1));
            FeeResult t3 = convert(List.of(T_A, T_B), SLOTS, List.of(ADJ_A), COEF.withTau(0.3));
            assertThat(t3.trnsfCnt()).isCloseTo(3 * t1.trnsfCnt(), within(EPS));
            assertThat(t1.lvngVhclCnt()).isEqualTo(t3.lvngVhclCnt());
            assertThat(t1.absrpCnt()).isEqualTo(t3.absrpCnt());
        }
    }

    @Nested
    @DisplayName("민감도 · 흡수 0 경고")
    class Sensitivity {

        /** INSERT 의 min_vl·max_vl 을 테스트가 주입한다(코드에 범위를 두지 않는다) */
        private final Map<String, VrblRange> ranges = Map.of(
                FeeCoef.PAID_DMND_RT, new VrblRange(0.15, 0.5),
                FeeCoef.PRC_ELST_VL, new VrblRange(-0.6, -0.1),
                FeeCoef.TRNSF_RT, new VrblRange(0.1, 0.3));

        private final List<PolicySimEmdBaseline> t = List.of(T_A, T_B);
        private final List<PolicySimEmdBaseline> adj = List.of(ADJ_A, ADJ_B);

        private double rOf(FeeResult r) {
            return PolicySimOutputCalculator.indicators(r).r();
        }

        @Test
        @DisplayName("CONVERT → 꺾임점(λ* ≈ 0.588)이 구간 밖이면 lambda 범위 = λ 0.15·0.5 끝점 R 의 [min, max] · 키 lambda·tau (eps 없음)")
        void convertLambda() {
            FeeResult base = convert(t, SLOTS, adj, COEF);
            Map<String, FeeSensitivity.Sensitivity> rng = FeeSensitivity.compute(t, SLOTS, adj, base, ranges);
            assertThat(rng).containsOnlyKeys(FeeSensitivity.KEY_LAMBDA, FeeSensitivity.KEY_TAU);
            assertThat(rng.keySet()).containsExactly(FeeSensitivity.KEY_LAMBDA, FeeSensitivity.KEY_TAU);

            double r1 = rOf(convert(t, SLOTS, adj, COEF.withLambda(0.15)));
            double r2 = rOf(convert(t, SLOTS, adj, COEF.withLambda(0.5)));
            assertThat(r1).isNotEqualTo(r2);
            assertThat(rng.get("lambda").low()).isCloseTo(Math.min(r1, r2), within(EPS));
            assertThat(rng.get("lambda").high()).isCloseTo(Math.max(r1, r2), within(EPS));
        }

        @Test
        @DisplayName("RAISE → 꺾임점(ε* ≈ −1.96)이 구간 밖이면 eps 범위 = ε −0.6·−0.1 끝점 R 의 [min, max] · λ 를 바꿔도 RAISE 결과 불변")
        void raiseEps() {
            BigDecimal p30 = BigDecimal.valueOf(30);
            FeeResult base = raise(t, SLOTS, adj, COEF, p30);
            Map<String, FeeSensitivity.Sensitivity> rng = FeeSensitivity.compute(t, SLOTS, adj, base, ranges);
            assertThat(rng.keySet()).containsExactly(FeeSensitivity.KEY_EPS, FeeSensitivity.KEY_TAU);

            double r1 = rOf(raise(t, SLOTS, adj, COEF.withEps(-0.6), p30));
            double r2 = rOf(raise(t, SLOTS, adj, COEF.withEps(-0.1), p30));
            assertThat(rng.get("eps").low()).isCloseTo(Math.min(r1, r2), within(EPS));
            assertThat(rng.get("eps").high()).isCloseTo(Math.max(r1, r2), within(EPS));

            // λ 는 RAISE 에 들어가지 않는다 — 결과·민감도 모두 같다
            FeeResult other = raise(t, SLOTS, adj, COEF.withLambda(0.5), p30);
            assertThat(rOf(other)).isEqualTo(rOf(base));
            assertThat(other.lvngVhclCnt()).isEqualTo(base.lvngVhclCnt());
            assertThat(FeeSensitivity.compute(t, SLOTS, adj, other, ranges).get("eps"))
                    .isEqualTo(rng.get("eps"));
        }

        @Test
        @DisplayName("lambda·eps 범위가 base R 을 항상 포함한다 — 꺾임점 구간 밖·안 모두")
        void coefRangeContainsBase() {
            BigDecimal p30 = BigDecimal.valueOf(30);
            List<PolicySimEmdBaseline> kinked = List.of(emd("K", "제주시", 60.0, 10.0, 100), T_B);
            for (List<PolicySimEmdBaseline> tt : List.of(t, kinked)) {
                List<FeeTargetRow> s = List.of(slot(tt.get(0).emdCd(), 80), slot("B", 40));
                FeeResult cv = convert(tt, s, adj, COEF);
                FeeSensitivity.Sensitivity lam = FeeSensitivity.compute(tt, s, adj, cv, ranges).get("lambda");
                assertThat(rOf(cv)).isBetween(lam.low() - EPS, lam.high() + EPS);
                FeeResult rs = raise(tt, s, adj, COEF, p30);
                FeeSensitivity.Sensitivity eps = FeeSensitivity.compute(tt, s, adj, rs, ranges).get("eps");
                assertThat(rOf(rs)).isBetween(eps.low() - EPS, eps.high() + EPS);
            }
        }

        @Test
        @DisplayName("CONVERT 꺾임점 λ* = E0/(α·면수·occ) 가 구간 안이면 하한 = 꺾임점 R (끝점 R 보다 낮다 — 볼록)")
        void convertKinkInsideRange() {
            // K: E0 10 · 면수 80 → λ* = 10 / (0.5 × 80 × 0.85) ≈ 0.294 ∈ (0.15, 0.5). B 의 λ* ≈ 0.588 은 구간 밖
            List<PolicySimEmdBaseline> tt = List.of(emd("K", "제주시", 60.0, 10.0, 100), T_B);
            List<FeeTargetRow> s = List.of(slot("K", 80), slot("B", 40));
            double kink = 10.0 / (0.5 * 80 * 0.85);
            FeeResult base = convert(tt, s, adj, COEF);
            FeeSensitivity.Sensitivity lam = FeeSensitivity.compute(tt, s, adj, base, ranges).get("lambda");

            double rLo = rOf(convert(tt, s, adj, COEF.withLambda(0.15)));
            double rHi = rOf(convert(tt, s, adj, COEF.withLambda(0.5)));
            double rKink = rOf(convert(tt, s, adj, COEF.withLambda(kink)));
            assertThat(rKink).isLessThan(Math.min(rLo, rHi));
            assertThat(lam.low()).isCloseTo(rKink, within(EPS));
            assertThat(lam.high()).isCloseTo(Math.max(rLo, rHi), within(EPS));
            // 종전(끝점 두 개)이면 하한이 base R 보다 높게 나왔다 — 그 결함의 재현
            assertThat(Math.min(rLo, rHi)).isGreaterThan(rOf(base));
        }

        @Test
        @DisplayName("RAISE 꺾임점 ε* = −100·E0/(α·면수·occ·p) 가 구간 안이면 하한 = 꺾임점 R")
        void raiseKinkInsideRange() {
            // K: E0 4 · 면수 80 · p 30 → d* = 4 / 34 ≈ 0.1176 → ε* ≈ −0.392 ∈ (−0.6, −0.1)
            BigDecimal p30 = BigDecimal.valueOf(30);
            List<PolicySimEmdBaseline> tt = List.of(emd("K", "제주시", 24.0, 4.0, 100), T_B);
            List<FeeTargetRow> s = List.of(slot("K", 80), slot("B", 40));
            double kink = -100.0 * (4.0 / (0.5 * 80 * 0.85)) / 30.0;
            FeeResult base = raise(tt, s, adj, COEF, p30);
            FeeSensitivity.Sensitivity eps = FeeSensitivity.compute(tt, s, adj, base, ranges).get("eps");

            double rA = rOf(raise(tt, s, adj, COEF.withEps(-0.6), p30));
            double rB = rOf(raise(tt, s, adj, COEF.withEps(-0.1), p30));
            double rKink = rOf(raise(tt, s, adj, COEF.withEps(kink), p30));
            assertThat(rKink).isLessThan(Math.min(rA, rB));
            assertThat(eps.low()).isCloseTo(rKink, within(EPS));
            assertThat(eps.high()).isCloseTo(Math.max(rA, rB), within(EPS));
        }

        @Test
        @DisplayName("흡수가 꺼지면(occ < occ_th) 꺾임점이 없다 — 끝점 R 의 [min, max]")
        void noKinkWithoutAbsorb() {
            FeeCoef low = new FeeCoef(0.70, 0.85, 0.5, -0.3, 0.3, 0.2, 0.5);
            List<PolicySimEmdBaseline> tt = List.of(emd("K", "제주시", 60.0, 10.0, 100), T_B);
            List<FeeTargetRow> s = List.of(slot("K", 80), slot("B", 40));
            FeeSensitivity.Sensitivity lam = FeeSensitivity.compute(tt, s, adj, convert(tt, s, adj, low), ranges).get("lambda");
            double r1 = rOf(convert(tt, s, adj, low.withLambda(0.15)));
            double r2 = rOf(convert(tt, s, adj, low.withLambda(0.5)));
            assertThat(lam.low()).isCloseTo(Math.min(r1, r2), within(EPS));
            assertThat(lam.high()).isCloseTo(Math.max(r1, r2), within(EPS));
        }

        @Test
        @DisplayName("tau 범위가 base R 을 포함한다 (E1 이 τ 에 선형) — CONVERT·RAISE 모두")
        void tauContainsBase() {
            for (FeeResult base : List.of(convert(t, SLOTS, adj, COEF), raise(t, SLOTS, adj, COEF, P10))) {
                FeeSensitivity.Sensitivity tau = FeeSensitivity.compute(t, SLOTS, adj, base, ranges).get("tau");
                double r = rOf(base);
                assertThat(tau.low()).isLessThan(r);
                assertThat(tau.high()).isGreaterThan(r);
                // 선형 — τ 0.1·0.2·0.3 이 등간격이라 base 가 중점
                assertThat((tau.low() + tau.high()) / 2).isCloseTo(r, within(EPS));
            }
        }

        @Test
        @DisplayName("occ < occ_th → wrnngCn 채워짐 · 흡수 0 · 전이 > 0. occ ≥ occ_th 면 wrnngCn null")
        void wrnngCnBelowThreshold() {
            FeeResult low = convert(t, SLOTS, adj, new FeeCoef(0.70, 0.85, 0.5, -0.3, 0.3, 0.2, 0.5));
            assertThat(FeePolicyCalculator.wrnngCn(low)).isEqualTo("점유율이 포화 기준 미만이라 흡수 효과가 없습니다");
            assertThat(low.absrpCnt()).isZero();
            assertThat(low.trnsfCnt()).isPositive();

            assertThat(FeePolicyCalculator.wrnngCn(convert(t, SLOTS, adj, COEF))).isNull();
        }

        @Test
        @DisplayName("PolicySimParams.range — 행의 min_vl·max_vl 을 그대로 싣는다 · NULL 이면 null · toMap 에는 안 들어간다")
        void rangeFromRows() {
            java.util.function.BiFunction<String, double[], jeju.datahub.analysis.service.AnalysisWeightVO> row = (cd, mm) -> {
                jeju.datahub.analysis.service.AnalysisWeightVO r = new jeju.datahub.analysis.service.AnalysisWeightVO();
                r.setVrblCd(cd);
                r.setUseYn("Y");
                r.setStngVl(BigDecimal.ONE);
                r.setBscStngVl(BigDecimal.ONE);
                if (mm != null) {
                    r.setMinVl(BigDecimal.valueOf(mm[0]));
                    r.setMaxVl(BigDecimal.valueOf(mm[1]));
                }
                return r;
            };
            PolicySimParams p = PolicySimParams.resolve(List.of(
                    row.apply(FeeCoef.TRNSF_RT, new double[] {0.1, 0.3}),
                    row.apply(FeeCoef.PAID_DMND_RT, null)), List.of(FeeCoef.TRNSF_RT, FeeCoef.PAID_DMND_RT));
            assertThat(p.range(FeeCoef.TRNSF_RT)).isEqualTo(new VrblRange(0.1, 0.3));
            assertThat(p.range(FeeCoef.PAID_DMND_RT)).isNull();
            assertThat(p.toMap()).containsOnlyKeys(FeeCoef.TRNSF_RT, FeeCoef.PAID_DMND_RT);
        }

        @Test
        @DisplayName("범위 NO_ENF(R null) → 민감도도 null · 범위 없는 계수는 그 키만 null")
        void nullRules() {
            List<PolicySimEmdBaseline> noEnf = List.of(T_NOENF);
            FeeResult base = convert(noEnf, List.of(slot("Z", 10)), List.of(), COEF);
            assertThat(PolicySimOutputCalculator.indicators(base).r()).isNull();
            assertThat(FeeSensitivity.compute(noEnf, List.of(slot("Z", 10)), List.of(), base, ranges)).isNull();

            Map<String, FeeSensitivity.Sensitivity> partial = FeeSensitivity.compute(t, SLOTS, adj,
                    convert(t, SLOTS, adj, COEF), Map.of(FeeCoef.TRNSF_RT, new VrblRange(0.1, 0.3)));
            assertThat(partial).containsEntry("lambda", null);
            assertThat(partial.get("tau")).isNotNull();
        }
    }
}
