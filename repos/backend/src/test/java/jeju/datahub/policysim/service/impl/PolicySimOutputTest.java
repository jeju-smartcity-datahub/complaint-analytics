package jeju.datahub.policysim.service.impl;

import static jeju.datahub.policysim.service.impl.ExpandPolicyTest.emd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jeju.datahub.policysim.service.PolicySimAplcnParam;
import jeju.datahub.policysim.service.PolicySimResultVO;

/**
 * 출력 지표 단위테스트 (+ 명세 13장 케이스).
 *
 * <p>🔴 <b>실데이터 값을 단언하지 않는다</b> — 기준선을 직접 주입한다. DB 없이 돈다.
 */
class PolicySimOutputTest {

    private static final double EPS = 1e-9;
    private static final PolicySimParams DEFAULT = PolicySimBaselineTest.params(Map.of());

    static PolicySimBaseline baseline(PolicySimParams p, List<PolicySimEmdBaseline> emds) {
        return new PolicySimBaseline(p,
                LocalDate.of(2025, 11, 1), LocalDate.of(2026, 6, 30), 8,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31), 8,
                emds);
    }

    static PolicySimResultVO run(PolicySimParams p, List<PolicySimEmdBaseline> emds, ExpandMethod m, int n, int t,
                                 String sggNm, String emdCd) {
        ExpandResult res = ExpandPolicyCalculator.compute(emds, p, m, n, t);
        return PolicySimOutputCalculator.toVO(baseline(p, emds), res, sggNm, emdCd);
    }

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("단위 — 내부 비율 · 응답 퍼센트")
    class Units {

        @Test
        @DisplayName("비율 → 퍼센트 ×100 HALF_UP 둘째 자리 (−0.27697 → −27.70 · 0.040144 → 4.01 · 반올림 0 은 0.00)")
        void pct() {
            assertThat(PolicySimOutputCalculator.pct(-0.27697)).isEqualByComparingTo("-27.70");
            assertThat(PolicySimOutputCalculator.pct(0.040144)).isEqualByComparingTo("4.01");
            assertThat(PolicySimOutputCalculator.pct(-0.0918406)).isEqualByComparingTo("-9.18");
            assertThat(PolicySimOutputCalculator.pct(0.000049)).isEqualByComparingTo("0.00");
            assertThat(PolicySimOutputCalculator.pct(-0.000049).signum()).isZero();
            assertThat(PolicySimOutputCalculator.pct(-0.00005)).isEqualByComparingTo("-0.01");  // HALF_UP 은 절댓값 기준
            assertThat(PolicySimOutputCalculator.pct(null)).isNull();
        }

        @Test
        @DisplayName("🔴 I 는 반올림 전 H 로 계산 — 주입 S 2315 · E0 174.75 · ΔS 96.8 → H0 0.92549 · H1 0.88834 → 4.01 (반올림된 0.925·0.888 로 나누면 4.00)")
        void iUsesUnroundedH() {
            // 주입: S 2315 · occ 0.85 · E0 174.75 · 공한지 100면 12개월(ΔS 96.8 · ΔE 48.4)
            List<PolicySimEmdBaseline> yd = List.of(emd("50110137", "제주시", 818.63, 174.75, 2315));
            PolicySimResultVO vo = run(DEFAULT, yd, ExpandMethod.VACLOT, 100, 12, null, "50110137");
            double h0 = (2315 * 0.85 + 174.75) / 2315;
            double h1 = (2315 * 0.85 + 48.4 + 174.75 - 48.4) / (2315 + 96.8);
            double exact = (h0 - h1) / h0;
            assertThat(vo.getCrtrCngstIdx()).isEqualByComparingTo("0.925");
            assertThat(vo.getAftrCngstIdx()).isEqualByComparingTo("0.888");
            assertThat(vo.getCngstImpvRt()).isEqualByComparingTo(PolicySimOutputCalculator.pct(exact)).isEqualByComparingTo("4.01");
            // 반올림된 H 로 다시 나누면 다른 값이 된다 — 그 값을 쓰지 않았다는 회귀
            double fromRounded = (0.925 - 0.888) / 0.925;
            assertThat(PolicySimOutputCalculator.pct(fromRounded)).isEqualByComparingTo("4.00");
        }

        @Test
        @DisplayName("R 은 반올림 전 C0·C1 로 계산 · 응답 C0·C1 은 소수 둘째 · ΔS_eff 둘째(77.44)")
        void rUnrounded() {
            List<PolicySimEmdBaseline> one = List.of(emd("X", "제주시", 818.631, 174.754, 2315));
            ExpandResult res = ExpandPolicyCalculator.compute(one, DEFAULT, ExpandMethod.VACLOT, 80, 12);
            PolicySimOutputCalculator.Indicators ind = PolicySimOutputCalculator.indicators(res);
            double k = 818.631 / 174.754;
            double c1 = k * (174.754 - 0.5 * 77.44);
            assertThat(ind.r()).isCloseTo((c1 - 818.631) / 818.631, within(EPS));
            PolicySimResultVO vo = PolicySimOutputCalculator.toVO(baseline(DEFAULT, one), res, null, "X");
            assertThat(vo.getCrtrCvlcptCnt()).isEqualByComparingTo("818.63");
            assertThat(vo.getCrtrIlglCnt()).isEqualByComparingTo("174.75");
            assertThat(vo.getVldAddPkltSlot()).isEqualByComparingTo("77.44");
            assertThat(vo.getAftrCvlcptCnt()).isEqualByComparingTo(PolicySimOutputCalculator.scale(c1, 2));
            assertThat(vo.getCvlcptRdctRt()).isEqualByComparingTo(PolicySimOutputCalculator.pct((c1 - 818.631) / 818.631));
        }

        @Test
        @DisplayName("R 이 양수(민원 증가)여도 부호 그대로 퍼센트로 낸다")
        void positiveR() {
            // 확충 결과를 직접 만든다 — E1 > E0 인 경우는 확충식에서 나오지 않으므로 지표 계산만 검증
            PolicySimEmdBaseline b = emd("X", "제주시", 10, 5, 100);
            ExpandEmdResult e = new ExpandEmdResult(b, 1.0, 0, 0, 5.1, b.u0(), 100, b.k() * 5.1, (b.u0() + 5.1) / 100, false);
            ExpandResult res = new ExpandResult(ExpandMethod.NEW, 50, 12, 50, 0.5, 5, List.of(e), false);
            PolicySimResultVO vo = PolicySimOutputCalculator.toVO(baseline(DEFAULT, List.of(b)), res, null, "X");
            assertThat(vo.getCvlcptRdctRt()).isEqualByComparingTo("2.00");
            assertThat(vo.getCngstImpvRt().signum()).isNegative();
        }
    }

    @Nested
    @DisplayName("범위 calcSttsCd · null 패턴 · Σ 규칙")
    class RangeStatus {

        @Test
        @DisplayName("단일 NO_ENF(주입 S 311 · C0 3.125 · E0 0) → NO_ENF · R·I·C1·H1 null · C0 실제 합 3.13(HALF_UP)·H0 0.850 · excs=false")
        void singleNoEnf() {
            List<PolicySimEmdBaseline> ud = List.of(emd("50110330", "제주시", 3.125, 0.0, 311));
            PolicySimResultVO vo = run(DEFAULT, ud, ExpandMethod.VACLOT, 100, 12, null, "50110330");
            assertThat(vo.getCalcSttsCd()).isEqualTo("NO_ENF");
            assertThat(vo.getCrtrCngstIdx()).isEqualByComparingTo("0.850");
            assertThat(vo.getCrtrCvlcptCnt()).isEqualByComparingTo("3.13");
            assertThat(vo.getCvlcptRdctRt()).isNull();
            assertThat(vo.getCngstImpvRt()).isNull();
            assertThat(vo.getAftrCvlcptCnt()).isNull();
            assertThat(vo.getAftrCngstIdx()).isNull();
            assertThat(vo.isExcsYn()).isFalse();
            assertThat(vo.getCstLvlCd()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("단일 NO_SPLY(S=0 · E0>0) → NO_SPLY · R·C1 값 · I·H0·H1 null · 포화와 함께 올 수 있다")
        void singleNoSply() {
            List<PolicySimEmdBaseline> one = List.of(emd("X", "제주시", 5, 1.93, 0));
            PolicySimResultVO vo = run(DEFAULT, one, ExpandMethod.VACLOT, 50, 12, null, "X");
            assertThat(vo.getCalcSttsCd()).isEqualTo("NO_SPLY");
            assertThat(vo.getCvlcptRdctRt()).isEqualByComparingTo("-100.00");
            assertThat(vo.getAftrCvlcptCnt()).isEqualByComparingTo("0.00");
            assertThat(vo.getCngstImpvRt()).isNull();
            assertThat(vo.getCrtrCngstIdx()).isNull();
            assertThat(vo.getAftrCngstIdx()).isNull();
            assertThat(vo.isExcsYn()).isTrue();
        }

        @Test
        @DisplayName("E0=0 · S=0 이면 NO_ENF 우선")
        void noEnfWins() {
            List<PolicySimEmdBaseline> one = List.of(emd("X", "서귀포시", 0, 0.0, 0));
            PolicySimResultVO vo = run(DEFAULT, one, ExpandMethod.NEW, 50, 12, null, "X");
            assertThat(vo.getCalcSttsCd()).isEqualTo("NO_ENF");
            assertThat(vo.getCrtrCngstIdx()).isNull();
            assertThat(vo.getCvlcptRdctRt()).isNull();
        }

        @Test
        @DisplayName("🔴 NO_ENF·NO_SPLY 가 섞인 시군구 → calcSttsCd null · R Σ 는 NO_ENF 제외(NO_SPLY 포함) · H Σ 는 S>0 만")
        void mixedRange() {
            PolicySimEmdBaseline a = emd("A", "제주시", 800, 174.75, 2315);
            PolicySimEmdBaseline b = emd("B", "제주시", 50, 40.58, 600);
            PolicySimEmdBaseline c = emd("C", "제주시", 3, 0.0, 311);    // NO_ENF
            PolicySimEmdBaseline d = emd("D", "제주시", 2, 1.0, 0);      // NO_SPLY
            List<PolicySimEmdBaseline> range = List.of(a, b, c, d);
            ExpandResult res = ExpandPolicyCalculator.compute(range, DEFAULT, ExpandMethod.VACLOT, 100, 12);
            PolicySimOutputCalculator.Indicators ind = PolicySimOutputCalculator.indicators(res);

            assertThat(ind.stts()).isNull();
            // R 의 Σ — A·B·D (C 제외)
            double c0 = 800 + 50 + 2;
            double c1 = res.emds().stream().filter(e -> e.c1() != null && e.base().e0() > 0).mapToDouble(ExpandEmdResult::c1).sum();
            assertThat(res.emds().get(3).c1()).isNotNull();   // NO_SPLY 는 R Σ 에 들어간다
            assertThat(ind.c0()).isCloseTo(c0, within(EPS));
            assertThat(ind.c1()).isCloseTo(c1, within(EPS));
            assertThat(ind.r()).isCloseTo((c1 - c0) / c0, within(EPS));
            // H 의 Σ — A·B·C (D 는 S=0 이라 제외. NO_ENF C 는 S>0 이라 포함)
            double h0 = (a.u0() + a.e0() + b.u0() + b.e0() + c.u0() + c.e0()) / (2315 + 600 + 311);
            double s1 = res.emds().get(0).s1() + res.emds().get(1).s1() + res.emds().get(2).s1();
            double h1Num = 0;
            for (int idx = 0; idx < 3; idx++) {
                h1Num += res.emds().get(idx).u1() + res.emds().get(idx).e1();
            }
            double h1 = h1Num / s1;
            assertThat(ind.h0()).isCloseTo(h0, within(EPS));
            assertThat(ind.h1()).isCloseTo(h1, within(EPS));
            assertThat(ind.i()).isCloseTo((h0 - h1) / h0, within(EPS));
            assertThat(ind.e0()).isCloseTo(174.75 + 40.58 + 1.0, within(EPS));
            assertThat(ind.s()).isEqualTo(2315 + 600 + 311);

            PolicySimResultVO vo = PolicySimOutputCalculator.toVO(baseline(DEFAULT, range), res, "제주시", null);
            assertThat(vo.getCalcSttsCd()).isNull();
            assertThat(vo.getCvlcptRdctRt()).isNotNull();
            assertThat(vo.getCngstImpvRt()).isNotNull();
            assertThat(vo.getCrtrCvlcptCnt()).isEqualByComparingTo("852.00");   // NO_ENF 의 C0 3 은 분모에서 빠진다
            assertThat(vo.getExsPkltCpcty()).isEqualTo(3226L);
            assertThat(vo.getAnlsRngCn()).isEqualTo("제주시 전체");
        }

        @Test
        @DisplayName("범위 전체가 NO_ENF(여러 곳) → C0 는 범위 실제 합")
        void rangeAllNoEnf() {
            List<PolicySimEmdBaseline> range = List.of(emd("A", "서귀포시", 1.5, 0.0, 100), emd("B", "서귀포시", 2.0, 0.0, 0));
            PolicySimResultVO vo = run(DEFAULT, range, ExpandMethod.NEW, 100, 12, "서귀포시", null);
            assertThat(vo.getCalcSttsCd()).isEqualTo("NO_ENF");
            assertThat(vo.getCrtrCvlcptCnt()).isEqualByComparingTo("3.50");
            assertThat(vo.getCrtrCngstIdx()).isEqualByComparingTo("0.850");   // ΣS>0 → S>0 인 A 만으로 H0
            assertThat(vo.getAftrCngstIdx()).isNull();
        }

        @Test
        @DisplayName("단속은 있으나 민원 0(R 분모 0) → R null (0/0 을 0 으로 메우지 않는다) · calcSttsCd null")
        void zeroDenominator() {
            List<PolicySimEmdBaseline> one = List.of(emd("X", "제주시", 0, 2.0, 100));
            PolicySimResultVO vo = run(DEFAULT, one, ExpandMethod.NEW, 50, 12, null, "X");
            assertThat(vo.getCalcSttsCd()).isNull();
            assertThat(vo.getCvlcptRdctRt()).isNull();
            assertThat(vo.getAftrCvlcptCnt()).isEqualByComparingTo("0.00");
            assertThat(vo.getCngstImpvRt()).isNotNull();
        }

        @Test
        @DisplayName("포화 단일(주입 E0 40.58 · 공한지 100면 12개월 → α×ΔS 48.4) → R −100.00 과 excs=true 함께 · 값을 비우지 않는다")
        void saturated() {
            List<PolicySimEmdBaseline> dh = List.of(emd("50130105", "서귀포시", 50, 40.58, 600));
            PolicySimResultVO vo = run(DEFAULT, dh, ExpandMethod.VACLOT, 100, 12, null, "50130105");
            assertThat(vo.isExcsYn()).isTrue();
            assertThat(vo.getCvlcptRdctRt()).isEqualByComparingTo("-100.00");
            assertThat(vo.getCngstImpvRt()).isNotNull();
            assertThat(vo.getAftrCngstIdx()).isNotNull();
            assertThat(vo.getCalcSttsCd()).isNull();
            assertThat(vo.getAnlsRngCn()).isEqualTo("서귀포시 동50130105");
        }
    }

    @Nested
    @DisplayName("비용 · 등급 (명세 7.4)")
    class Cost {

        @Test
        @DisplayName("방식별 등급 — 공한지 187만 LOW · 복층화 1,815만 MID · 신규 3,822만 HIGH · 비용 = N × 단가")
        void byMethod() {
            List<PolicySimEmdBaseline> one = List.of(emd("X", "제주시", 100, 50, 1000));
            PolicySimResultVO v = run(DEFAULT, one, ExpandMethod.VACLOT, 100, 12, null, "X");
            PolicySimResultVO m = run(DEFAULT, one, ExpandMethod.MULTFLR, 100, 12, null, "X");
            PolicySimResultVO n = run(DEFAULT, one, ExpandMethod.NEW, 100, 12, null, "X");
            assertThat(v.getCstAmt()).isEqualByComparingTo("187000000");
            assertThat(v.getCstAmt().toPlainString()).isEqualTo("187000000");
            assertThat(v.getCstLvlCd()).isEqualTo("LOW");
            assertThat(m.getCstAmt()).isEqualByComparingTo("1815000000");
            assertThat(m.getCstLvlCd()).isEqualTo("MID");
            assertThat(n.getCstAmt()).isEqualByComparingTo("3822000000");
            assertThat(n.getCstLvlCd()).isEqualTo("HIGH");
            // 공한지 50면 24개월(ΔS_eff 46.8)도 1면당 199.8만 → LOW
            assertThat(run(DEFAULT, one, ExpandMethod.VACLOT, 50, 24, null, "X").getCstLvlCd()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("경계 — 1면당 비용이 저중경계와 같으면 MID · 중고경계와 같으면 HIGH (< 저 · < 중 · ≥ 고)")
        void boundaries() {
            PolicySimParams p = DEFAULT;
            assertThat(PolicySimOutputCalculator.cstLvlCd(bd("499999900"), 100, p)).isEqualTo("LOW");
            assertThat(PolicySimOutputCalculator.cstLvlCd(bd("500000000"), 100, p)).isEqualTo("MID");
            assertThat(PolicySimOutputCalculator.cstLvlCd(bd("2999999900"), 100, p)).isEqualTo("MID");
            assertThat(PolicySimOutputCalculator.cstLvlCd(bd("3000000000"), 100, p)).isEqualTo("HIGH");
            // 1면당 = 비용 ÷ 영향 면수(공한지 ΔS_eff) — 명세 13장 A: 1.87억 ÷ 96.8 = 1,931,818원 → 저
            assertThat(PolicySimOutputCalculator.cstLvlCd(bd("187000000"), 96.8, p)).isEqualTo("LOW");
            // 경계 계수를 바꾸면 따라간다
            PolicySimParams lowered = PolicySimBaselineTest.params(Map.of("CST_LOW_BNDRY", bd("1000000")));
            assertThat(PolicySimOutputCalculator.cstLvlCd(bd("187000000"), 96.8, lowered)).isEqualTo("MID");
        }
    }

    @Nested
    @DisplayName("aplcnParam · 범위 라벨")
    class Aplcn {

        @Test
        @DisplayName("13계수 실제 적용값(꼬리 0 제거) + g_sgg 두 시 + 민원·단속 구간")
        void params() {
            PolicySimAplcnParam ap = PolicySimOutputCalculator.aplcnParam(baseline(DEFAULT, List.of()));
            assertThat(ap.occ()).isEqualByComparingTo("0.85");
            assertThat(ap.occTh()).isEqualByComparingTo("0.85");
            assertThat(ap.alpha()).isEqualByComparingTo("0.5");
            assertThat(ap.m().toPlainString()).isEqualTo("3");
            assertThat(ap.wFixed()).isEqualByComparingTo("0.5");
            assertThat(ap.rRet()).isEqualByComparingTo("0.032");
            assertThat(ap.gSgg()).containsOnlyKeys("제주시", "서귀포시");
            assertThat(ap.gSgg().get("제주시")).isEqualByComparingTo("2.890");
            assertThat(ap.gSgg().get("서귀포시")).isEqualByComparingTo("1.385");
            assertThat(ap.cVacant().toPlainString()).isEqualTo("1870000");
            assertThat(ap.cPublic().toPlainString()).isEqualTo("38220000");
            assertThat(ap.cMultflr().toPlainString()).isEqualTo("18150000");
            assertThat(ap.cstLowBndry().toPlainString()).isEqualTo("5000000");
            assertThat(ap.cstHighBndry().toPlainString()).isEqualTo("30000000");
            assertThat(ap.cvlcptPrd()).isEqualTo("20260101~20260831");
            assertThat(ap.crdnPrd()).isEqualTo("20251101~20260630");
        }

        @Test
        @DisplayName("폴백 적용값이 그대로 담긴다 · 민원 0건이면 cvlcptPrd null")
        void fallbackAndEmpty() {
            PolicySimParams p = PolicySimBaselineTest.params(Map.of("PCSHR_CRTR_VL", bd("0.9000")));
            PolicySimBaseline b = new PolicySimBaseline(p, LocalDate.of(2025, 11, 1), LocalDate.of(2026, 6, 30), 8,
                    null, null, 0, List.of());
            PolicySimAplcnParam ap = PolicySimOutputCalculator.aplcnParam(b);
            assertThat(ap.occ().toPlainString()).isEqualTo("0.9");
            assertThat(ap.cvlcptPrd()).isNull();
        }

        @Test
        @DisplayName("범위 라벨 — 읍면동 '{시군구} {읍면동}' · 시군구 '{시군구} 전체' · 전체 '전체 지역'")
        void label() {
            List<PolicySimEmdBaseline> two = List.of(emd("A", "제주시", 10, 5, 100), emd("B", "서귀포시", 10, 5, 100));
            assertThat(run(DEFAULT, two.subList(0, 1), ExpandMethod.NEW, 50, 12, null, "A").getAnlsRngCn()).isEqualTo("제주시 동A");
            assertThat(run(DEFAULT, two.subList(1, 2), ExpandMethod.NEW, 50, 12, "서귀포시", null).getAnlsRngCn()).isEqualTo("서귀포시 전체");
            assertThat(run(DEFAULT, two, ExpandMethod.NEW, 50, 12, null, null).getAnlsRngCn()).isEqualTo("전체 지역");
        }
    }

    @Nested
    @DisplayName("명세 13장 — 동홍동 예시 기준선 (S 1,819 · occ 0.95 · E0 527 · C0 44 · N 100)")
    class SpecCases {

        private final PolicySimParams p = PolicySimBaselineTest.params(Map.of("PCSHR_CRTR_VL", bd("0.95")));
        private final List<PolicySimEmdBaseline> dh = List.of(emd("50130105", "서귀포시", 44.0, 527.0, 1819, 0.95));

        void check(ExpandMethod m, int t, double expR, double expI, String rPct, String iPct, String lvl) {
            ExpandResult res = ExpandPolicyCalculator.compute(dh, p, m, 100, t);
            PolicySimOutputCalculator.Indicators ind = PolicySimOutputCalculator.indicators(res);
            assertThat(ExpandPolicyTest.round4(ind.r())).isEqualTo(expR);
            assertThat(ExpandPolicyTest.round4(ind.i())).isEqualTo(expI);
            PolicySimResultVO vo = PolicySimOutputCalculator.toVO(baseline(p, dh), res, null, "50130105");
            assertThat(vo.getCvlcptRdctRt()).isEqualByComparingTo(rPct);
            assertThat(vo.getCngstImpvRt()).isEqualByComparingTo(iPct);
            assertThat(vo.getCrtrCngstIdx()).isEqualByComparingTo("1.240");
            assertThat(vo.getCrtrCvlcptCnt()).isEqualByComparingTo("44.00");
            assertThat(vo.getCstLvlCd()).isEqualTo(lvl);
            assertThat(vo.getCalcSttsCd()).isNull();
            assertThat(vo.isExcsYn()).isFalse();
        }

        @Test
        @DisplayName("A 공한지 100면 12개월 → R −0.0918(−9.18%) · I 0.0505(5.05%) · ΔC_net −4.041 · H1 1.177 · 저")
        void caseA() {
            check(ExpandMethod.VACLOT, 12, -0.0918, 0.0505, "-9.18", "5.05", "LOW");
            ExpandResult res = ExpandPolicyCalculator.compute(dh, p, ExpandMethod.VACLOT, 100, 12);
            PolicySimOutputCalculator.Indicators ind = PolicySimOutputCalculator.indicators(res);
            assertThat(ind.c1() - ind.c0()).isCloseTo(-4.041, within(5e-4));
            assertThat(PolicySimOutputCalculator.toVO(baseline(p, dh), res, null, "50130105").getAftrCngstIdx())
                    .isEqualByComparingTo("1.177");
        }

        @Test
        @DisplayName("A3 공한지 100면 3개월 → R −0.0941(−9.41%) · I 0.0517(5.17%) · 저")
        void caseA3() { check(ExpandMethod.VACLOT, 3, -0.0941, 0.0517, "-9.41", "5.17", "LOW"); }

        @Test
        @DisplayName("B 복층화 100면 12개월 → R −0.0949(−9.49%) · I 0.0521(5.21%) · 🔴 중(명세 '고' 아님 — §0-2 수정 2)")
        void caseB() { check(ExpandMethod.MULTFLR, 12, -0.0949, 0.0521, "-9.49", "5.21", "MID"); }

        @Test
        @DisplayName("C 신규 100면 12개월 → R −0.0949(−9.49%) · I 0.0521(5.21%) · 고")
        void caseC() { check(ExpandMethod.NEW, 12, -0.0949, 0.0521, "-9.49", "5.21", "HIGH"); }
    }
}
