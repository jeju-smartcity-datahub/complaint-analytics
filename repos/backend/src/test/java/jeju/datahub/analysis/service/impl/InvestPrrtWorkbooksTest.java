package jeju.datahub.analysis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.AnlsWorkbookVrblVO;
import jeju.datahub.analysis.service.InvestPrrtLotBaseVO;
import jeju.datahub.analysis.service.InvestPrrtLotVO;
import jeju.datahub.analysis.service.InvestPrrtWorkbookRowVO;
import jeju.datahub.analysis.service.InvestPrrtWorkbookVO;
import jeju.datahub.analysis.service.impl.InvestPrrtCalculator.LessCnt;
import jeju.datahub.analysis.service.impl.InvestPrrtStngResolver.InvestPrrtStng;

/**
 * {@link InvestPrrtWorkbooks} 단위테스트 — DB 없이 계수 대응·입력여부 규칙·행 순서를 고정한다.
 */
class InvestPrrtWorkbooksTest {

    private static final List<String> WEIGHTS = List.of("SAFETY", "COMPLAINT", "USAGE", "ACCIDENT");

    /** 8칸 전부 서로 다른 값 — 대응을 잘못 짝지으면 값 집합 크기가 줄거나 기대값이 어긋난다. */
    private static InvestPrrtStng distinctStng() {
        Map<String, BigDecimal> w = new LinkedHashMap<>();
        w.put("SAFETY", new BigDecimal("21"));
        w.put("COMPLAINT", new BigDecimal("22"));
        w.put("USAGE", new BigDecimal("23"));
        w.put("ACCIDENT", new BigDecimal("34"));
        return new InvestPrrtStng(w, List.of(), new BigDecimal("301"),
                new BigDecimal("81"), new BigDecimal("61"), 51);
    }

    private static InvestPrrtStng stngWith(Map<String, BigDecimal> weights) {
        return new InvestPrrtStng(weights, List.of(), new BigDecimal("300"),
                new BigDecimal("80"), new BigDecimal("60"), 50);
    }

    private static AnlsVrblOvrd ovrdWithout(String... offCds) {
        Set<String> off = Set.of(offCds);
        List<AnalysisWeightVO> rows = new ArrayList<>();
        for (String cd : AnlsVrblScope.INVEST.getVrblCds()) {
            if (off.contains(cd)) {
                continue;
            }
            AnalysisWeightVO row = new AnalysisWeightVO();
            row.setAnlsTypeCd("INVEST");
            row.setVrblCd(cd);
            row.setVrblNm(cd + "_NM");
            row.setMinVl(new BigDecimal("0.0000"));
            row.setMaxVl(new BigDecimal("1000.0000"));
            row.setUseYn("Y");
            rows.add(row);
        }
        return AnlsVrblOvrd.of(AnlsVrblScope.INVEST, rows, Map.of());
    }

    private static Map<String, String> inptYns(List<AnlsWorkbookVrblVO> vrbls) {
        Map<String, String> m = new LinkedHashMap<>();
        vrbls.forEach(v -> m.put(v.getVrblCd(), v.getInptYn()));
        return m;
    }

    private static InvestPrrtLotVO lot(long pkltId) {
        InvestPrrtLotVO l = new InvestPrrtLotVO();
        l.setPkltId(pkltId);
        return l;
    }

    @Test
    @DisplayName("vrblCd → 적용값 대응 Map 은 INVEST 8개를 빠짐없이 덮고 전부 서로 다른 값을 읽는다")
    void aplcnVlMapCoversAllEight() {
        List<String> cds = AnlsVrblScope.INVEST.getVrblCds();
        assertThat(cds).hasSize(8);
        assertThat(InvestPrrtWorkbooks.APLCN_VL.keySet()).containsExactlyInAnyOrderElementsOf(cds);

        InvestPrrtStng stng = distinctStng();
        Set<BigDecimal> seen = new HashSet<>();
        for (String cd : cds) {
            BigDecimal v = InvestPrrtWorkbooks.APLCN_VL.get(cd).apply(stng);
            assertThat(v).as(cd).isNotNull();
            seen.add(v);
        }
        assertThat(seen).hasSize(8);

        List<AnlsWorkbookVrblVO> vrbls = InvestPrrtWorkbooks.vrbls(ovrdWithout(), stng);
        assertThat(vrbls).extracting(AnlsWorkbookVrblVO::getVrblCd).containsExactlyElementsOf(cds);
        assertThat(vrbls).allSatisfy(v -> {
            assertThat(v.getAplcnVl()).isNotNull();
            assertThat(v.getIntOnlyYn()).isEqualTo("N");
            assertThat(v.getVrblNm()).isEqualTo(v.getVrblCd() + "_NM");
            assertThat(v.getMaxVl()).isEqualByComparingTo("1000");
        });
        Map<String, BigDecimal> byCd = new LinkedHashMap<>();
        vrbls.forEach(v -> byCd.put(v.getVrblCd(), v.getAplcnVl()));
        assertThat(byCd.get("SAFETY")).isEqualByComparingTo("21");
        assertThat(byCd.get("ACCIDENT")).isEqualByComparingTo("34");
        assertThat(byCd.get("ALTMNT_RNG_VL")).isEqualByComparingTo("301");
        assertThat(byCd.get("UP_GRD_CRTR_SCR")).isEqualByComparingTo("81");
        assertThat(byCd.get("MD_GRD_CRTR_SCR")).isEqualByComparingTo("61");
        assertThat(byCd.get("INDCT_PKLT_CNT")).isEqualByComparingTo("51");
    }

    @Test
    @DisplayName("가중치 0 이 섞인 stng(SAFETY·ACCIDENT 0 — Map 에서 빠짐)에서도 8행 모두 aplcnVl 이 null 아님(0)")
    void zeroWeightsBecomeZeroNotNull() {
        Map<String, BigDecimal> w = new LinkedHashMap<>();
        w.put("COMPLAINT", new BigDecimal("50"));
        w.put("USAGE", new BigDecimal("50"));
        List<AnlsWorkbookVrblVO> vrbls = InvestPrrtWorkbooks.vrbls(ovrdWithout(), stngWith(w));
        assertThat(vrbls).hasSize(8).allSatisfy(v -> assertThat(v.getAplcnVl()).as(v.getVrblCd()).isNotNull());
        Map<String, BigDecimal> byCd = new LinkedHashMap<>();
        vrbls.forEach(v -> byCd.put(v.getVrblCd(), v.getAplcnVl()));
        assertThat(byCd.get("SAFETY")).isEqualByComparingTo("0");
        assertThat(byCd.get("ACCIDENT")).isEqualByComparingTo("0");
        assertThat(byCd.get("COMPLAINT")).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("입력여부 — 입력 셀은 SAFETY·COMPLAINT·USAGE·등급 기준 2개, ACCIDENT·ALTMNT_RNG_VL·INDCT_PKLT_CNT 는 N")
    void inptYnRules() {
        Map<String, String> yn = inptYns(InvestPrrtWorkbooks.vrbls(ovrdWithout(), distinctStng()));
        assertThat(yn.entrySet()).filteredOn(e -> "Y".equals(e.getValue())).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("SAFETY", "COMPLAINT", "USAGE", "UP_GRD_CRTR_SCR", "MD_GRD_CRTR_SCR");
        assertThat(yn.entrySet()).filteredOn(e -> "N".equals(e.getValue())).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("ACCIDENT", "ALTMNT_RNG_VL", "INDCT_PKLT_CNT");
    }

    @Test
    @DisplayName("입력여부 가중치 하나라도 row null 이면 가중치 4개 모두 N, 등급 기준은 그대로 Y · 꺼진 행은 이름 vrblCd · 범위 null")
    void inptYnWeightOff() {
        List<AnlsWorkbookVrblVO> vrbls = InvestPrrtWorkbooks.vrbls(ovrdWithout("USAGE"), distinctStng());
        Map<String, String> yn = inptYns(vrbls);
        WEIGHTS.forEach(cd -> assertThat(yn.get(cd)).as(cd).isEqualTo("N"));
        assertThat(yn.get("UP_GRD_CRTR_SCR")).isEqualTo("Y");
        assertThat(yn.get("MD_GRD_CRTR_SCR")).isEqualTo("Y");
        AnlsWorkbookVrblVO off = vrbls.stream().filter(v -> "USAGE".equals(v.getVrblCd())).findFirst().orElseThrow();
        assertThat(off.getVrblNm()).isEqualTo("USAGE");
        assertThat(off.getMinVl()).isNull();
        assertThat(off.getMaxVl()).isNull();
        assertThat(off.getAplcnVl()).isEqualByComparingTo("23");
    }

    @Test
    @DisplayName("rows 는 shown 과 같은 주차장·같은 순서이고 k 3개를 옮긴다 · trgtCnt 는 넘긴 값(필터 전)")
    void rowsFollowShownOrder() {
        Map<Long, LessCnt> lessCnts = new LinkedHashMap<>();
        lessCnts.put(1L, new LessCnt(1, 2, 3));
        lessCnts.put(2L, new LessCnt(4, 5, 6));
        lessCnts.put(3L, new LessCnt(7, 8, 9));
        lessCnts.put(4L, new LessCnt(10, 11, 12));
        List<InvestPrrtLotVO> shown = List.of(lot(3L), lot(1L));   // 필터 통과분 — 순위순

        InvestPrrtWorkbookVO wbk = InvestPrrtWorkbooks.of(ovrdWithout(), distinctStng(), 4, shown, lessCnts);

        assertThat(wbk).isNotNull();
        assertThat(wbk.getTrgtCnt()).isEqualTo(4);
        assertThat(wbk.getRows()).extracting(InvestPrrtWorkbookRowVO::getPkltId).containsExactly(3L, 1L);
        assertThat(wbk.getRows().get(0).getCvlcptLtCnt()).isEqualTo(7);
        assertThat(wbk.getRows().get(0).getUtztnLtCnt()).isEqualTo(8);
        assertThat(wbk.getRows().get(0).getAcdntLtCnt()).isEqualTo(9);
        assertThat(wbk.getRows().get(1).getCvlcptLtCnt()).isEqualTo(1);
        assertThat(wbk.getVrbls()).hasSize(8);
    }

    @Test
    @DisplayName("stng.weights() 에 ENFORCE 가 있으면 wbk 는 null")
    void enforceWeightYieldsNull() {
        Map<String, BigDecimal> w = new LinkedHashMap<>();
        w.put("SAFETY", new BigDecimal("50"));
        w.put("ENFORCE", new BigDecimal("50"));
        InvestPrrtWorkbookVO wbk = InvestPrrtWorkbooks.of(ovrdWithout(), stngWith(w), 1,
                List.of(lot(1L)), Map.of(1L, new LessCnt(0, 0, 0)));
        assertThat(wbk).isNull();
    }

    @Test
    @DisplayName("계산기 Result.lessCnts — 전 대상의 k 를 pklt_id 로 담는다(엄격히 작은 수 · 동점 같은 값)")
    void calculatorExposesLessCnts() {
        List<InvestPrrtLotBaseVO> bases = new ArrayList<>();
        long[][] data = {{1, 5, 0}, {2, 5, 2}, {3, 9, 1}};   // pkltId, 민원, 사고
        for (long[] d : data) {
            InvestPrrtLotBaseVO b = new InvestPrrtLotBaseVO();
            b.setPkltId(d[0]);
            b.setCvlcptCnt(d[1]);
            b.setAcdntCnt(d[2]);
            b.setCrdnCnt(0L);
            b.setDayAvgTrnvrRt(new BigDecimal(d[0]));
            bases.add(b);
        }
        InvestPrrtCalculator.Result r = new InvestPrrtCalculator().calculate(bases,
                Map.of("COMPLAINT", new BigDecimal("50"), "USAGE", new BigDecimal("50")),
                new BigDecimal("80"), new BigDecimal("60"));
        assertThat(r.lessCnts()).hasSize(3);
        assertThat(r.lessCnts().get(1L)).isEqualTo(new LessCnt(0, 0, 0));
        assertThat(r.lessCnts().get(2L)).isEqualTo(new LessCnt(0, 1, 2));
        assertThat(r.lessCnts().get(3L)).isEqualTo(new LessCnt(2, 2, 1));
    }
}
