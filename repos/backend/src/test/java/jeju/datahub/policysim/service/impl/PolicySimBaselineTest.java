package jeju.datahub.policysim.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.impl.AnalysisWeightMapper;
import jeju.datahub.policysim.service.PolicySimParamMissingException;

/**
 * 정책 시뮬레이션 기준선 산정 단위테스트 (TSK-4037 Step 2).
 *
 * <p>🔴 <b>실데이터 값을 단언하지 않는다</b> — 시드를 재적재하면 민원 날짜가 이동해 C0 가 바뀐다(DEC-009).
 * 주입한 고정 건수로 산식·구간 규칙·NO_ENF·NO_SPLY 분기·계수 누락 503 만 검증한다.
 * {@code @SpringBootTest} 를 쓰지 않는다 — DB 없이 돈다(매퍼는 Mockito).
 */
class PolicySimBaselineTest {

    private static final double EPS = 1e-9;

    /** Step 1-1 표의 기본값 13종 */
    static Map<String, BigDecimal> defaults() {
        Map<String, BigDecimal> m = new HashMap<>();
        m.put("PCSHR_CRTR_VL", new BigDecimal("0.85"));
        m.put("PCSHR_THRSH", new BigDecimal("0.85"));
        m.put("ALPHA_VL", new BigDecimal("0.50"));
        m.put("DSCL_MLTP", new BigDecimal("3.0"));
        m.put("FIX_WGVL", new BigDecimal("0.50"));
        m.put("GEO_CVRG_CRCT_JEJU", new BigDecimal("2.890"));
        m.put("GEO_CVRG_CRCT_SEOGWIPO", new BigDecimal("1.385"));
        m.put("VACLOT_GVBK_RT", new BigDecimal("0.032"));
        m.put("CST_VACLOT", new BigDecimal("1870000"));
        m.put("CST_PBLPKLT", new BigDecimal("38220000"));
        m.put("CST_MULTFLR", new BigDecimal("18150000"));
        m.put("CST_LOW_BNDRY", new BigDecimal("5000000"));
        m.put("CST_HIGH_BNDRY", new BigDecimal("30000000"));
        return m;
    }

    static PolicySimParams params(Map<String, BigDecimal> overrides) {
        Map<String, BigDecimal> m = defaults();
        m.putAll(overrides);
        return PolicySimParams.of(m);
    }

    static PolicySimEmdCountRow row(String sggNm, long cvlcpt, long cctv, long pda, long fld, long s) {
        PolicySimEmdCountRow r = new PolicySimEmdCountRow();
        r.setEmdCd("50110000");
        r.setSggNm(sggNm);
        r.setEmdNm("테스트동");
        r.setCvlcptCnt(cvlcpt);
        r.setCctvCrdnCnt(cctv);
        r.setPdaCrdnCnt(pda);
        r.setFldCrdnCnt(fld);
        r.setPkltCpctySum(s);
        return r;
    }

    static List<AnalysisWeightVO> weightRows(Map<String, BigDecimal> values) {
        List<AnalysisWeightVO> rows = new ArrayList<>();
        values.forEach((cd, v) -> {
            AnalysisWeightVO w = new AnalysisWeightVO();
            w.setAnlsTypeCd("POLICY_SIM");
            w.setVrblCd(cd);
            w.setStngVl(v);
            w.setBscStngVl(v);
            w.setUseYn("Y");
            rows.add(w);
        });
        return rows;
    }

    @Nested
    @DisplayName("산식")
    class Formula {

        @Test
        @DisplayName("제주시 — C0·Enf·E0(g_sgg 2.890)·U0·k·H0 가 산식대로 나온다")
        void jeju() {
            // 민원 800 · CCTV 100(가중 0.5) · PDA 200 · 현장 50 · 공급 1,000면
            PolicySimEmdBaseline b = PolicySimBaselineCalculator.computeEmd(
                    row("제주시", 800, 100, 200, 50, 1000), params(Map.of()), 8, 8);

            double c0 = 800 / 8.0;                          // 100
            double enf = (200 + 50 + 0.5 * 100) / 8.0;      // 37.5
            double e0 = 3.0 * enf / 30.0 * 2.890;           // 10.8375
            assertThat(b.c0()).isCloseTo(c0, within(EPS));
            assertThat(b.enf()).isCloseTo(enf, within(EPS));
            assertThat(b.e0()).isCloseTo(e0, within(EPS));
            assertThat(b.gSgg()).isEqualTo(2.890);
            assertThat(b.u0()).isCloseTo(850.0, within(EPS));
            assertThat(b.s()).isEqualTo(1000);
            assertThat(b.k()).isCloseTo(c0 / e0, within(EPS));
            assertThat(b.h0()).isCloseTo((850.0 + e0) / 1000.0, within(EPS));
            assertThat(b.calcStts()).isNull();
        }

        @Test
        @DisplayName("서귀포시는 g_sgg 1.385 를 쓴다")
        void seogwipo() {
            PolicySimEmdBaseline b = PolicySimBaselineCalculator.computeEmd(
                    row("서귀포시", 0, 0, 80, 0, 100), params(Map.of()), 8, 8);
            assertThat(b.gSgg()).isEqualTo(1.385);
            assertThat(b.e0()).isCloseTo(3.0 * (80 / 8.0) / 30.0 * 1.385, within(EPS));
        }

        @Test
        @DisplayName("g_sgg 를 1.0 으로 바꾸면 E0 가 1/g 배, k 가 g 배가 된다(보정 작동)")
        void geoCorrection() {
            PolicySimEmdCountRow r = row("제주시", 800, 100, 200, 50, 1000);
            PolicySimEmdBaseline on = PolicySimBaselineCalculator.computeEmd(r, params(Map.of()), 8, 8);
            PolicySimEmdBaseline off = PolicySimBaselineCalculator.computeEmd(r,
                    params(Map.of("GEO_CVRG_CRCT_JEJU", BigDecimal.ONE)), 8, 8);
            assertThat(off.e0()).isCloseTo(on.e0() / 2.890, within(EPS));
            assertThat(off.k()).isCloseTo(on.k() * 2.890, within(EPS));
        }

        @Test
        @DisplayName("occ 를 0.9 로 바꾸면 U0·H0 는 변하고 k 는 불변")
        void occ() {
            PolicySimEmdCountRow r = row("제주시", 800, 100, 200, 50, 1000);
            PolicySimEmdBaseline a = PolicySimBaselineCalculator.computeEmd(r, params(Map.of()), 8, 8);
            PolicySimEmdBaseline b = PolicySimBaselineCalculator.computeEmd(r,
                    params(Map.of("PCSHR_CRTR_VL", new BigDecimal("0.9"))), 8, 8);
            assertThat(b.u0()).isCloseTo(900.0, within(EPS));
            assertThat(b.h0()).isNotEqualTo(a.h0());
            assertThat(b.k()).isCloseTo(a.k(), within(EPS));
        }

        @Test
        @DisplayName("crdn_mthd NULL·미등재 수단은 N_현장(가중 1)으로 — 매퍼가 넘긴 그대로 1배")
        void fieldWeight() {
            PolicySimEmdBaseline cctvOnly = PolicySimBaselineCalculator.computeEmd(
                    row("제주시", 0, 16, 0, 0, 10), params(Map.of()), 8, 8);
            PolicySimEmdBaseline fldOnly = PolicySimBaselineCalculator.computeEmd(
                    row("제주시", 0, 0, 0, 16, 10), params(Map.of()), 8, 8);
            assertThat(cctvOnly.enf()).isCloseTo(1.0, within(EPS));   // 16 × 0.5 ÷ 8
            assertThat(fldOnly.enf()).isCloseTo(2.0, within(EPS));    // 16 ÷ 8
        }

        @Test
        @DisplayName("제주시·서귀포시 밖의 시군구는 1.0 으로 메우지 않고 예외")
        void unknownSgg() {
            assertThatThrownBy(() -> PolicySimBaselineCalculator.computeEmd(
                    row("모름시", 1, 1, 1, 1, 1), params(Map.of()), 8, 8))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("NO_ENF · NO_SPLY")
    class Status {

        @Test
        @DisplayName("단속 0 → NO_ENF · k null · S>0 이면 H0 = occ")
        void noEnf() {
            PolicySimEmdBaseline b = PolicySimBaselineCalculator.computeEmd(
                    row("제주시", 25, 0, 0, 0, 311), params(Map.of()), 8, 8);
            assertThat(b.calcStts()).isEqualTo(PolicySimCalcStts.NO_ENF);
            assertThat(b.e0()).isZero();
            assertThat(b.k()).isNull();
            assertThat(b.c0()).isCloseTo(25 / 8.0, within(EPS));   // C0 는 실제 값을 낸다
            assertThat(b.h0()).isCloseTo(0.85, within(EPS));
        }

        @Test
        @DisplayName("단속 0 · 공급 0 → NO_ENF 우선 · H0 null")
        void noEnfWins() {
            PolicySimEmdBaseline b = PolicySimBaselineCalculator.computeEmd(
                    row("서귀포시", 0, 0, 0, 0, 0), params(Map.of()), 8, 8);
            assertThat(b.calcStts()).isEqualTo(PolicySimCalcStts.NO_ENF);
            assertThat(b.k()).isNull();
            assertThat(b.h0()).isNull();
        }

        @Test
        @DisplayName("S=0 & E0>0 → NO_SPLY · 예외 없이 k 는 나오고 H0 는 null")
        void noSply() {
            PolicySimEmdBaseline b = PolicySimBaselineCalculator.computeEmd(
                    row("제주시", 40, 0, 10, 0, 0), params(Map.of()), 8, 8);
            assertThat(b.calcStts()).isEqualTo(PolicySimCalcStts.NO_SPLY);
            assertThat(b.e0()).isPositive();
            assertThat(b.k()).isNotNull().isCloseTo(b.c0() / b.e0(), within(EPS));
            assertThat(b.u0()).isZero();
            assertThat(b.h0()).isNull();
        }
    }

    @Nested
    @DisplayName("기준 구간 (TSK-4309 — 민원·단속 공통 최근 완결 3개월)")
    class Window {

        private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

        @Test
        @DisplayName("끝은 전월 말 — 진행 중인 달과 미래 날짜는 쓰지 않는다")
        void endsAtLastCompleteMonth() {
            PolicySimBaselineCalculator.BaselineWindow w = PolicySimBaselineCalculator.baselineWindow(
                    TODAY, LocalDateTime.of(2026, 11, 15, 22, 0), LocalDateTime.of(2026, 12, 1, 0, 0));
            assertThat(w.bgngYmd()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(w.endYmdExcl()).isEqualTo(LocalDate.of(2026, 11, 1));
        }

        @Test
        @DisplayName("연도를 넘겨도 M개월이다")
        void acrossYear() {
            PolicySimBaselineCalculator.BaselineWindow w = PolicySimBaselineCalculator.baselineWindow(
                    LocalDate.of(2027, 1, 31), null, null);
            assertThat(w.bgngYmd()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(w.endYmdExcl()).isEqualTo(LocalDate.of(2027, 1, 1));
        }

        @Test
        @DisplayName("원천 최신 달이 전월보다 앞서면 더 이른 쪽까지로 당긴다")
        void pulledByLaggingSource() {
            PolicySimBaselineCalculator.BaselineWindow byCrdn = PolicySimBaselineCalculator.baselineWindow(
                    TODAY, LocalDateTime.of(2026, 10, 31, 0, 0), LocalDateTime.of(2026, 6, 30, 0, 0));
            assertThat(byCrdn.bgngYmd()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(byCrdn.endYmdExcl()).isEqualTo(LocalDate.of(2026, 7, 1));

            PolicySimBaselineCalculator.BaselineWindow byCvlcpt = PolicySimBaselineCalculator.baselineWindow(
                    TODAY, LocalDateTime.of(2026, 8, 20, 0, 0), LocalDateTime.of(2026, 10, 31, 0, 0));
            assertThat(byCvlcpt.endYmdExcl()).isEqualTo(LocalDate.of(2026, 9, 1));
        }

        @Test
        @DisplayName("민원 나눗수 — 구간 전체 보유면 M, 구간 중간부터면 보유 개월수, 없으면 0")
        void cvlcptMonths() {
            PolicySimBaselineCalculator.BaselineWindow w = PolicySimBaselineCalculator.baselineWindow(TODAY, null, null);
            assertThat(PolicySimBaselineCalculator.cvlcptMonths(LocalDateTime.of(2026, 1, 16, 0, 0), w))
                    .isEqualTo(PolicySimBaselineCalculator.MONTHS);
            assertThat(PolicySimBaselineCalculator.cvlcptMonths(LocalDateTime.of(2026, 9, 15, 0, 0), w)).isEqualTo(2);
            assertThat(PolicySimBaselineCalculator.cvlcptMonths(LocalDateTime.of(2026, 11, 2, 0, 0), w)).isZero();
            assertThat(PolicySimBaselineCalculator.cvlcptMonths(null, w)).isZero();

            PolicySimEmdBaseline b = PolicySimBaselineCalculator.computeEmd(
                    row("제주시", 100, 0, 8, 0, 10), params(Map.of()), 2, 3);
            assertThat(b.c0()).isCloseTo(50.0, within(EPS));
        }

        @Test
        @DisplayName("민원·단속에 같은 구간을 매퍼에 넘긴다 · 민원 0건이면 민원 구간만 비운다")
        void sameWindowToMapper() {
            AnalysisWeightMapper wm = mock(AnalysisWeightMapper.class);
            PolicySimMapper pm = mock(PolicySimMapper.class);
            when(wm.selectWeightsByType("POLICY_SIM")).thenReturn(weightRows(defaults()));
            PolicySimCvlcptPeriodRow period = new PolicySimCvlcptPeriodRow();
            period.setMinOcrnDt(LocalDateTime.of(2026, 1, 2, 0, 0));
            period.setMaxOcrnDt(LocalDateTime.of(2026, 10, 30, 0, 0));
            when(pm.selectCvlcptPeriod()).thenReturn(period);
            when(pm.selectMaxCrdnDt()).thenReturn(LocalDateTime.of(2026, 10, 31, 0, 0));
            when(pm.selectEmdCounts(any())).thenReturn(List.of(
                    row("제주시", 30, 0, 8, 0, 10), row("서귀포시", 0, 0, 0, 0, 0)));

            // 끝은 10월로 고정된다 — 원천 최신 달이 10월이고 오늘은 그 뒤다(실행일이 지나도 결과가 같다)
            PolicySimBaseline res = new PolicySimBaselineCalculator(wm, pm).calculate();

            ArgumentCaptor<PolicySimBaselineQueryParam> cap = ArgumentCaptor.forClass(PolicySimBaselineQueryParam.class);
            verify(pm).selectEmdCounts(cap.capture());
            PolicySimBaselineQueryParam q = cap.getValue();
            assertThat(q.getCrdnBgngDt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0));
            assertThat(q.getCrdnEndDtExcl()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
            assertThat(q.getCvlcptBgngDt()).isEqualTo(q.getCrdnBgngDt());
            assertThat(q.getCvlcptEndDtExcl()).isEqualTo(q.getCrdnEndDtExcl());

            assertThat(res.crdnEndYmd()).isEqualTo(LocalDate.of(2026, 10, 31));
            assertThat(res.cvlcptEndYmd()).isEqualTo(LocalDate.of(2026, 10, 31));
            assertThat(res.crdnMonths()).isEqualTo(3);
            assertThat(res.cvlcptMonths()).isEqualTo(3);
            assertThat(res.emds()).hasSize(2);
            assertThat(res.emds().get(0).c0()).isCloseTo(10.0, within(EPS));
            assertThat(res.emds().get(1).calcStts()).isEqualTo(PolicySimCalcStts.NO_ENF);
            assertThat(res.params().toMap()).hasSize(13);

            when(pm.selectCvlcptPeriod()).thenReturn(null);
            PolicySimBaseline empty = new PolicySimBaselineCalculator(wm, pm).calculate();
            assertThat(empty.cvlcptBgngYmd()).isNull();
            assertThat(empty.cvlcptEndYmd()).isNull();
            assertThat(empty.cvlcptMonths()).isZero();
            assertThat(empty.crdnBgngYmd()).isEqualTo(LocalDate.of(2026, 8, 1));
        }
    }

    @Nested
    @DisplayName("계수 해석 · 503 POLICY_SIM_PARAM_MISSING")
    class Params {

        @Test
        @DisplayName("13종 중 하나라도 없으면 예외 — 집계 쿼리를 돌리지 않는다(CST_*=1원 같은 결과가 없다)")
        void missing() {
            Map<String, BigDecimal> m = defaults();
            m.remove("CST_PBLPKLT");
            AnalysisWeightMapper wm = mock(AnalysisWeightMapper.class);
            PolicySimMapper pm = mock(PolicySimMapper.class);
            when(wm.selectWeightsByType("POLICY_SIM")).thenReturn(weightRows(m));

            assertThatThrownBy(() -> new PolicySimBaselineCalculator(wm, pm).calculate())
                    .isInstanceOf(PolicySimParamMissingException.class)
                    .satisfies(e -> assertThat(((PolicySimParamMissingException) e).getMissingVrblCds())
                            .containsExactly("CST_PBLPKLT"));
            verify(pm, never()).selectEmdCounts(any());
            verify(pm, never()).selectCvlcptPeriod();
        }

        @Test
        @DisplayName("행 개수가 아니라 코드로 판정 — 다른 작업의 행이 더해져 13행이어도 누락은 누락")
        void countIsNotEnough() {
            Map<String, BigDecimal> m = defaults();
            m.remove("GEO_CVRG_CRCT_JEJU");
            m.put("ALPHA_B_VL", new BigDecimal("0.3"));       // TSK-4038 행 흉내
            assertThat(m).hasSize(13);
            assertThatThrownBy(() -> PolicySimParams.resolve(weightRows(m)))
                    .isInstanceOf(PolicySimParamMissingException.class)
                    .hasMessageContaining("GEO_CVRG_CRCT_JEJU");
        }

        @Test
        @DisplayName("행이 하나도 없으면 13종 전부 누락")
        void empty() {
            assertThatThrownBy(() -> PolicySimParams.resolve(List.of()))
                    .isInstanceOf(PolicySimParamMissingException.class)
                    .satisfies(e -> assertThat(((PolicySimParamMissingException) e).getMissingVrblCds())
                            .hasSize(13));
        }

        @Test
        @DisplayName("use_yn='N'·값 NULL 이면 bsc_stng_vl 폴백 — 적용값(toMap)에도 폴백값")
        void fallback() {
            List<AnalysisWeightVO> rows = weightRows(defaults());
            rows.stream().filter(r -> "PCSHR_CRTR_VL".equals(r.getVrblCd())).forEach(r -> {
                r.setUseYn("N");
                r.setStngVl(new BigDecimal("0.5"));
                r.setBscStngVl(new BigDecimal("0.85"));
            });
            rows.stream().filter(r -> "ALPHA_VL".equals(r.getVrblCd())).forEach(r -> {
                r.setStngVl(null);
                r.setBscStngVl(new BigDecimal("0.40"));
            });
            PolicySimParams p = PolicySimParams.resolve(rows);
            assertThat(p.occ()).isEqualTo(0.85);
            assertThat(p.toMap().get("ALPHA_VL")).isEqualByComparingTo("0.40");
        }

        @Test
        @DisplayName("stng_vl·bsc_stng_vl 이 모두 NULL 이면 1.0 으로 메우지 않고 누락")
        void bothNull() {
            List<AnalysisWeightVO> rows = weightRows(defaults());
            rows.stream().filter(r -> "DSCL_MLTP".equals(r.getVrblCd())).forEach(r -> {
                r.setStngVl(null);
                r.setBscStngVl(null);
            });
            assertThatThrownBy(() -> PolicySimParams.resolve(rows))
                    .isInstanceOf(PolicySimParamMissingException.class)
                    .hasMessageContaining("DSCL_MLTP");
        }
    }

    @Nested
    @DisplayName("읍면동 코드 목록 경로 — 인접 기준선 (TSK-4038 Step 3)")
    class ByEmdCds {

        private PolicySimMapper mapperWithPeriod(AnalysisWeightMapper wm) {
            PolicySimMapper pm = mock(PolicySimMapper.class);
            when(wm.selectWeightsByType("POLICY_SIM")).thenReturn(weightRows(defaults()));
            PolicySimCvlcptPeriodRow period = new PolicySimCvlcptPeriodRow();
            period.setMinOcrnDt(LocalDateTime.of(2026, 1, 2, 0, 0));
            period.setMaxOcrnDt(LocalDateTime.of(2026, 10, 30, 0, 0));
            when(pm.selectCvlcptPeriod()).thenReturn(period);
            return pm;
        }

        @Test
        @DisplayName("코드 목록을 매퍼에 넘기고 구간은 전체 경로와 같다 · g_sgg 는 각 읍면동 자기 시 값")
        void sameWindowsOwnGsgg() {
            AnalysisWeightMapper wm = mock(AnalysisWeightMapper.class);
            PolicySimMapper pm = mapperWithPeriod(wm);
            when(pm.selectEmdCounts(any())).thenReturn(List.of(
                    row("제주시", 30, 0, 30, 0, 10), row("서귀포시", 30, 0, 30, 0, 10)));

            PolicySimBaselineCalculator calc = new PolicySimBaselineCalculator(wm, pm);
            PolicySimBaseline all = calc.calculate();
            PolicySimBaseline sub = calc.calculate(List.of("50130250", "50110137"));

            ArgumentCaptor<PolicySimBaselineQueryParam> cap = ArgumentCaptor.forClass(PolicySimBaselineQueryParam.class);
            verify(pm, org.mockito.Mockito.times(2)).selectEmdCounts(cap.capture());
            PolicySimBaselineQueryParam qAll = cap.getAllValues().get(0);
            PolicySimBaselineQueryParam qSub = cap.getAllValues().get(1);
            assertThat(qAll.getEmdCds()).isNull();                    // 4037 경로 — 필터 없음
            assertThat(qSub.getEmdCds()).containsExactly("50130250", "50110137");
            assertThat(qSub.getCrdnBgngDt()).isEqualTo(qAll.getCrdnBgngDt());
            assertThat(qSub.getCrdnEndDtExcl()).isEqualTo(qAll.getCrdnEndDtExcl());
            assertThat(qSub.getCvlcptBgngDt()).isEqualTo(qAll.getCvlcptBgngDt());
            assertThat(qSub.getCvlcptEndDtExcl()).isEqualTo(qAll.getCvlcptEndDtExcl());
            assertThat(sub.cvlcptBgngYmd()).isEqualTo(all.cvlcptBgngYmd());
            assertThat(sub.cvlcptMonths()).isEqualTo(all.cvlcptMonths());

            // Enf = 30 ÷ 3 = 10 → E0 = 3 × 10 ÷ 30 × g = g
            assertThat(sub.emds().get(0).gSgg()).isCloseTo(2.890, within(EPS));
            assertThat(sub.emds().get(0).e0()).isCloseTo(2.890, within(EPS));
            assertThat(sub.emds().get(1).gSgg()).isCloseTo(1.385, within(EPS));
            assertThat(sub.emds().get(1).e0()).isCloseTo(1.385, within(EPS));
            assertThat(sub.emds().get(1).k()).isCloseTo(10.0 / 1.385, within(EPS));
        }

        @Test
        @DisplayName("빈 목록 → 집계 쿼리 없이 빈 emds (IN () 문법 오류 방지) · null → 전 읍면동과 같다")
        void emptyAndNull() {
            AnalysisWeightMapper wm = mock(AnalysisWeightMapper.class);
            PolicySimMapper pm = mapperWithPeriod(wm);

            PolicySimBaseline empty = new PolicySimBaselineCalculator(wm, pm).calculate(List.of());
            assertThat(empty.emds()).isEmpty();
            assertThat(empty.crdnMonths()).isEqualTo(PolicySimBaselineCalculator.MONTHS);
            verify(pm, never()).selectEmdCounts(any());

            when(pm.selectEmdCounts(any())).thenReturn(List.of(row("제주시", 8, 0, 8, 0, 10)));
            PolicySimBaseline nul = new PolicySimBaselineCalculator(wm, pm).calculate(null);
            assertThat(nul.emds()).hasSize(1);
            ArgumentCaptor<PolicySimBaselineQueryParam> cap = ArgumentCaptor.forClass(PolicySimBaselineQueryParam.class);
            verify(pm).selectEmdCounts(cap.capture());
            assertThat(cap.getValue().getEmdCds()).isNull();
        }
    }
}
