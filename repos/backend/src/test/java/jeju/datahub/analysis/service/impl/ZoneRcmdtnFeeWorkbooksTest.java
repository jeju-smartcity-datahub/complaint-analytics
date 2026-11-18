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
import jeju.datahub.analysis.service.ZoneRcmdtnCrtrVls;

/**
 * {@link ZoneRcmdtnFeeWorkbooks} 단위테스트 (TSK-4340 Step 1) — DB 없이 계수 대응·입력여부 규칙을 고정한다.
 */
class ZoneRcmdtnFeeWorkbooksTest {

    /** 10칸 전부 서로 다른 값 — getter 를 잘못 짝지으면 값 집합 크기가 줄거나 기대값이 어긋난다. */
    private static ZoneRcmdtnCrtrVls distinctCrtrVls() {
        return new ZoneRcmdtnCrtrVls("NARROW",
                new BigDecimal("301"), new BigDecimal("21"),
                10, 30,
                new BigDecimal("71"), new BigDecimal("29"),
                new BigDecimal("22"), new BigDecimal("11"),
                new BigDecimal("72"), new BigDecimal("28"),
                new BigDecimal("0.5"), new BigDecimal("101"));
    }

    private static AnlsVrblOvrd ovrdWithout(String... offCds) {
        Set<String> off = Set.of(offCds);
        List<AnalysisWeightVO> rows = new ArrayList<>();
        for (String cd : AnlsVrblScope.RECOMMEND_FEE.getVrblCds()) {
            if (off.contains(cd)) {
                continue;
            }
            AnalysisWeightVO row = new AnalysisWeightVO();
            row.setAnlsTypeCd("RECOMMEND");
            row.setVrblCd(cd);
            row.setVrblNm(cd + "_NM");
            row.setMinVl(new BigDecimal("0.0000"));
            row.setMaxVl(new BigDecimal("1000.0000"));
            row.setUseYn("Y");
            rows.add(row);
        }
        return AnlsVrblOvrd.of(AnlsVrblScope.RECOMMEND_FEE, rows, Map.of());
    }

    private static Map<String, String> inptYns(List<AnlsWorkbookVrblVO> vrbls) {
        Map<String, String> m = new LinkedHashMap<>();
        vrbls.forEach(v -> m.put(v.getVrblCd(), v.getInptYn()));
        return m;
    }

    @Test
    @DisplayName("vrblCd → crtrVls 대응 Map 은 RECOMMEND_FEE 10개를 빠짐없이 덮고 전부 서로 다른 칸을 읽는다")
    void aplcnVlMapCoversAllTen() {
        List<String> cds = AnlsVrblScope.RECOMMEND_FEE.getVrblCds();
        assertThat(cds).hasSize(10);
        assertThat(ZoneRcmdtnFeeWorkbooks.APLCN_VL.keySet()).containsExactlyInAnyOrderElementsOf(cds);

        ZoneRcmdtnCrtrVls crtrVls = distinctCrtrVls();
        Set<BigDecimal> seen = new HashSet<>();
        for (String cd : cds) {
            BigDecimal v = ZoneRcmdtnFeeWorkbooks.APLCN_VL.get(cd).apply(crtrVls);
            assertThat(v).as(cd).isNotNull();
            seen.add(v);
        }
        assertThat(seen).hasSize(10);

        List<AnlsWorkbookVrblVO> vrbls = ZoneRcmdtnFeeWorkbooks.vrbls(ovrdWithout(), crtrVls);
        assertThat(vrbls).extracting(AnlsWorkbookVrblVO::getVrblCd).containsExactlyElementsOf(cds);
        assertThat(vrbls).allSatisfy(v -> {
            assertThat(v.getAplcnVl()).isNotNull();
            assertThat(v.getIntOnlyYn()).isEqualTo("N");
            assertThat(v.getMaxVl()).isEqualByComparingTo("1000");
        });
        assertThat(vrbls.get(0).getAplcnVl()).isEqualByComparingTo("301");
        assertThat(vrbls.get(9).getAplcnVl()).isEqualByComparingTo("101");
    }

    @Test
    @DisplayName("입력여부 — 공간 2개 · 면당민원 가중치는 N (입력 셀은 FEE 7개) — 🔁 2026-11-11 판정 계수 3개는 스코프에서 빠졌다")
    void inptYnJudgeLocked() {
        Map<String, String> yn = inptYns(ZoneRcmdtnFeeWorkbooks.vrbls(ovrdWithout(), distinctCrtrVls()));
        assertThat(yn.entrySet()).filteredOn(e -> "Y".equals(e.getValue())).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("FEE_CVLCPT_CNT_WGVL",
                        "FEE_CVLCPT_MM_CRTR_JEJU", "FEE_CVLCPT_MM_CRTR_SEOGWIPO",
                        "FEE_CVLCPT_RT_CRTR_JEJU", "FEE_CVLCPT_RT_CRTR_SEOGWIPO",
                        "FEE_CVLCPT_INCRS_WGVL", "FEE_CVLCPT_INCRS_RT_UPLMT_VL");
        assertThat(yn.entrySet()).filteredOn(e -> "N".equals(e.getValue())).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("ALTMNT_RNG_VL", "SWTC_TRGT_MIN_CPCTY", "FEE_CVLCPT_RT_WGVL");
        assertThat(yn).doesNotContainKeys("STSF_RT_THRSH", "CVLCPT_MM_CRTR_VL", "ILGL_CRTR_VL");
    }

    @Test
    @DisplayName("입력여부 — 가중치 한쪽이 꺼지면(row null) 두 가중치 모두 N, 꺼진 행은 이름 vrblCd · 범위 null")
    void inptYnWeightPairOff() {
        List<AnlsWorkbookVrblVO> vrbls =
                ZoneRcmdtnFeeWorkbooks.vrbls(ovrdWithout("FEE_CVLCPT_RT_WGVL"), distinctCrtrVls());
        Map<String, String> yn = inptYns(vrbls);
        assertThat(yn.get("FEE_CVLCPT_CNT_WGVL")).isEqualTo("N");
        assertThat(yn.get("FEE_CVLCPT_RT_WGVL")).isEqualTo("N");
        AnlsWorkbookVrblVO off = vrbls.stream().filter(v -> "FEE_CVLCPT_RT_WGVL".equals(v.getVrblCd()))
                .findFirst().orElseThrow();
        assertThat(off.getVrblNm()).isEqualTo("FEE_CVLCPT_RT_WGVL");
        assertThat(off.getMinVl()).isNull();
        assertThat(off.getMaxVl()).isNull();
        assertThat(off.getAplcnVl()).isEqualByComparingTo("29");
    }
}
