package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.impl.AnalysisWeightMapper;
import jeju.datahub.analysis.service.impl.AnlsVrblOvrdResolver;

/** {@link AnlsVrblOvrdResolver}·{@link AnlsVrblOvrd} 단위 테스트 (TSK-4325 Step 1). DB·스프링 없이 매퍼만 모킹한다. */
@ExtendWith(MockitoExtension.class)
class AnlsVrblOvrdResolverTest {

    @Mock
    private AnalysisWeightMapper mapper;

    @InjectMocks
    private AnlsVrblOvrdResolver resolver;

    // ------------------------------------------------------------------ 생략 · 정상

    @Test
    @DisplayName("null·빈 입력 → 빈 덮어쓰기, 적용 내역은 허용 목록 전체다 (HOTSPOT 은 등급 비율 3행뿐 — D2)")
    void omittedInputIsEmptyOverride() {
        stubHotspot();

        for (Map<String, BigDecimal> input : Arrays.asList(null, Map.<String, BigDecimal>of())) {
            AnlsVrblOvrd ovrd = resolver.resolve(AnlsVrblScope.HOTSPOT, input);

            assertThat(ovrd.isEmpty()).isTrue();
            assertThat(ovrd.ajmtYn()).isEqualTo("N");
            assertThat(ovrd.value("GRD_BNDRY1", () -> new BigDecimal("77"))).isEqualByComparingTo("77");
            assertThat(vrbls(ovrd)).extracting(v -> v.get("vrblCd"))
                    .containsExactly("GRD_BNDRY1", "GRD_BNDRY2", "GRD_BNDRY3");
        }
    }

    @Test
    @DisplayName("정상 값 → apply 는 복사본만 덮고, value 는 폴백을 부르지 않으며, 적용 내역이 D10 형식이다")
    void validValueOverridesCopies() {
        List<AnalysisWeightVO> rows = stubInvest();
        AnalysisWeightVO original = find(rows, "ALTMNT_RNG_VL");

        AnlsVrblOvrd ovrd = resolver.resolve(AnlsVrblScope.INVEST, Map.of("ALTMNT_RNG_VL", new BigDecimal("500")));

        List<AnalysisWeightVO> applied = ovrd.apply(rows);
        AnalysisWeightVO copy = find(applied, "ALTMNT_RNG_VL");
        assertThat(copy).isNotSameAs(original);
        assertThat(copy.getStngVl()).isEqualByComparingTo("500");
        assertThat(copy.getVrblNm()).isEqualTo(original.getVrblNm());
        assertThat(copy.getMinVl()).isEqualByComparingTo(original.getMinVl());
        assertThat(original.getStngVl()).isEqualByComparingTo("300");
        assertThat(find(applied, "SAFETY")).isNotSameAs(find(rows, "SAFETY"));

        assertThat(ovrd.value("ALTMNT_RNG_VL", () -> {
            throw new AssertionError("입력이 있으면 폴백을 부르지 않는다");
        })).isEqualByComparingTo("500");
        assertThat(ovrd.value("SAFETY", () -> new BigDecimal("25"))).isEqualByComparingTo("25");

        Map<String, Object> json = ovrd.toAjmtJson();
        assertThat(json).containsOnlyKeys("ajmtYn", "vrbls").containsEntry("ajmtYn", "Y");
        assertThat(vrbls(ovrd)).hasSize(8);
        Map<String, Object> changed = vrbl(ovrd, "ALTMNT_RNG_VL");
        assertThat(changed).containsOnlyKeys("vrblCd", "vrblNm", "unitNm", "bscStngVl", "stngVl", "aplcnVl", "expsrYn")
                .containsEntry("vrblNm", "ALTMNT_RNG_VL 이름").containsEntry("unitNm", "m")
                .containsEntry("expsrYn", "Y");   // TSK-4346 — 픽스처 null = 노출
        assertThat((BigDecimal) changed.get("bscStngVl")).isEqualByComparingTo("300");
        assertThat((BigDecimal) changed.get("stngVl")).isEqualByComparingTo("300");
        assertThat((BigDecimal) changed.get("aplcnVl")).isEqualByComparingTo("500");
        Map<String, Object> untouched = vrbl(ovrd, "SAFETY");
        assertThat((BigDecimal) untouched.get("stngVl")).isEqualByComparingTo("25");
        assertThat((BigDecimal) untouched.get("aplcnVl")).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("apply 는 같은 코드라도 다른 분석유형 행(POLICY_SIM)은 덮지 않는다")
    void applyLeavesOtherAnlsTypeRows() {
        given(mapper.selectWeightsByType("LIFEZONE")).willReturn(cngstRows());
        AnlsVrblOvrd ovrd = resolver.resolve(AnlsVrblScope.LIFEZONE_CNGST, Map.of("PCSHR_CRTR_VL", new BigDecimal("0.9")));

        AnalysisWeightVO policy = row("POLICY_SIM", null, "PCSHR_CRTR_VL", "CFC", "0.85", "0.3", "1");

        assertThat(ovrd.apply(List.of(policy)).get(0).getStngVl()).isEqualByComparingTo("0.85");
    }

    @Test
    @DisplayName("D11 — 60.00 과 60 은 같은 값이라 ajmtYn='N', 적용 내역 숫자 표기도 하나다")
    void sameValueWithDifferentScaleIsNotAdjusted() {
        given(mapper.selectWeightsByType("INVEST")).willReturn(List.of(
                row("INVEST", null, "MD_GRD_CRTR_SCR", "CFC", "60.0000", "0", "100"),
                row("INVEST", null, "UP_GRD_CRTR_SCR", "CFC", "80.0000", "0", "100")));

        AnlsVrblOvrd ovrd = resolver.resolve(AnlsVrblScope.INVEST, Map.of("MD_GRD_CRTR_SCR", new BigDecimal("60.00")));

        assertThat(ovrd.isEmpty()).isFalse();
        assertThat(ovrd.ajmtYn()).isEqualTo("N");
        assertThat(vrbl(ovrd, "MD_GRD_CRTR_SCR").get("stngVl").toString()).isEqualTo("60");
        assertThat(vrbl(ovrd, "MD_GRD_CRTR_SCR").get("aplcnVl").toString()).isEqualTo("60");
    }

    // ------------------------------------------------------------------ 허용 목록 · 행

    @Test
    @DisplayName("스코프 허용 목록 개수 — §3 표와 일치하고 분석유형 코드가 맞다")
    void scopesMatchPlanTable() {
        assertScope(AnlsVrblScope.HOTSPOT, "HOTSPOT", "GRD_BNDRY1", "GRD_BNDRY2", "GRD_BNDRY3");
        assertScope(AnlsVrblScope.INVEST, "INVEST",
                "SAFETY", "COMPLAINT", "USAGE", "ACCIDENT",
                "ALTMNT_RNG_VL", "UP_GRD_CRTR_SCR", "MD_GRD_CRTR_SCR", "INDCT_PKLT_CNT");
        assertScope(AnlsVrblScope.LIFEZONE_SPLY, "LIFEZONE",
                "SPLY_MIN_MNTH_AVG_CVLCPT",
                "SPLY_GRD_BNDRY1", "SPLY_GRD_BNDRY2", "SPLY_GRD_BNDRY3", "SPLY_GRD_BNDRY4");
        assertScope(AnlsVrblScope.LIFEZONE_CNGST, "LIFEZONE",
                "PCSHR_CRTR_VL",
                "CNGST_GRD_BNDRY1", "CNGST_GRD_BNDRY2", "CNGST_GRD_BNDRY3", "CNGST_GRD_BNDRY4",
                "DSCL_MLTP", "FIX_WGVL", "GEO_CVRG_CRCT_VL", "CVLCPT_WGVL", "MIN_CPCTY_VL");
        assertScope(AnlsVrblScope.RECOMMEND_FEE, "RECOMMEND",
                "ALTMNT_RNG_VL", "SWTC_TRGT_MIN_CPCTY",
                "FEE_CVLCPT_CNT_WGVL", "FEE_CVLCPT_RT_WGVL",
                "FEE_CVLCPT_MM_CRTR_JEJU", "FEE_CVLCPT_MM_CRTR_SEOGWIPO",
                "FEE_CVLCPT_RT_CRTR_JEJU", "FEE_CVLCPT_RT_CRTR_SEOGWIPO",
                "FEE_CVLCPT_INCRS_WGVL", "FEE_CVLCPT_INCRS_RT_UPLMT_VL");
        assertScope(AnlsVrblScope.RECOMMEND_SHARE, "RECOMMEND",
                "CVLCPT_MM_CRTR_VL", "CVLCPT_RT_CRTR_VL", "CVLCPT_INCRS_WGVL", "CVLCPT_INCRS_RT_UPLMT_VL");
    }

    @Test
    @DisplayName("목록 밖 코드 → 400: 집중구역 가중치(D2) · 다른 탭 코드 · 모르는 코드")
    void codeOutsideScopeIs400() {
        stubHotspot();
        assertBadRequest(AnlsVrblScope.HOTSPOT, Map.of("COMPLAINT", new BigDecimal("50")));

        given(mapper.selectWeightsByType("RECOMMEND")).willReturn(feeRows());
        assertBadRequest(AnlsVrblScope.RECOMMEND_SHARE, Map.of("ALTMNT_RNG_VL", new BigDecimal("500")));
        assertBadRequest(AnlsVrblScope.RECOMMEND_FEE, Map.of("PAID_DMND_RT", new BigDecimal("0.3")));
        assertBadRequest(AnlsVrblScope.RECOMMEND_FEE, Map.of("NOPE", BigDecimal.ONE));
        // 🔁 TSK-4340 — 읍면동 판정 계수 3개는 RECOMMEND_FEE 목록에서 빠졌다
        for (String cd : List.of("STSF_RT_THRSH", "CVLCPT_MM_CRTR_VL", "ILGL_CRTR_VL")) {
            assertBadRequest(AnlsVrblScope.RECOMMEND_FEE, Map.of(cd, new BigDecimal("50")));
        }
    }

    @Test
    @DisplayName("허용 목록에 있어도 설정 행이 없으면 400 (FEE 행 미반영 DB)")
    void allowedCodeWithoutRowIs400() {
        given(mapper.selectWeightsByType("RECOMMEND")).willReturn(List.of(
                row("RECOMMEND", null, "ALTMNT_RNG_VL", "CFC", "300", "100", "1000")));

        assertBadRequest(AnlsVrblScope.RECOMMEND_FEE, Map.of("FEE_CVLCPT_INCRS_WGVL", new BigDecimal("0.5")));
    }

    @Test
    @DisplayName("useYn='N' 행 입력은 400이고, 적용 내역에서도 빠진다")
    void disabledRowIs400() {
        AnalysisWeightVO off = row("INVEST", null, "INDCT_PKLT_CNT", "CFC", "50", "1", "500");
        off.setUseYn("N");
        List<AnalysisWeightVO> rows = new ArrayList<>(investRows());
        rows.removeIf(r -> "INDCT_PKLT_CNT".equals(r.getVrblCd()));
        rows.add(off);
        given(mapper.selectWeightsByType("INVEST")).willReturn(rows);

        assertBadRequest(AnlsVrblScope.INVEST, Map.of("INDCT_PKLT_CNT", new BigDecimal("60")));

        AnlsVrblOvrd ovrd = resolver.resolve(AnlsVrblScope.INVEST, Map.of());
        assertThat(vrbls(ovrd)).extracting(v -> v.get("vrblCd")).doesNotContain("INDCT_PKLT_CNT");
    }

    @Test
    @DisplayName("🆕 TSK-4346 — 숨김(expsrYn='N') 행 입력은 목록 밖 코드와 같은 문구의 400 이고, 적용 내역에는 남는다 · null 은 노출")
    void hiddenRowIs400ButStaysInAjmt() {
        List<AnalysisWeightVO> rows = new ArrayList<>(cngstRows());   // PCSHR_CRTR_VL 등 expsrYn=null(= 노출)
        AnalysisWeightVO hidden = row("LIFEZONE", "CNGST", "MIN_CPCTY_VL", "CFC", "20", "0", "50");
        hidden.setExpsrYn("N");
        AnalysisWeightVO exposed = row("LIFEZONE", "CNGST", "GEO_CVRG_CRCT_VL", "CFC", "1.815", "1", "4");
        exposed.setExpsrYn("Y");
        rows.add(hidden);
        rows.add(exposed);
        given(mapper.selectWeightsByType("LIFEZONE")).willReturn(rows);

        assertThatThrownBy(() -> resolver.resolve(AnlsVrblScope.LIFEZONE_CNGST,
                Map.of("MIN_CPCTY_VL", new BigDecimal("30"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("이 화면에서 조정할 수 없는 변수입니다: MIN_CPCTY_VL");
                });
        // 현재값과 같은 값이어도 숨김 코드는 입력 자체가 400 이다
        assertBadRequest(AnlsVrblScope.LIFEZONE_CNGST, Map.of("MIN_CPCTY_VL", new BigDecimal("20")));

        // 노출('Y')·null(픽스처 기본) 행은 그대로 조정된다
        assertThat(resolver.resolve(AnlsVrblScope.LIFEZONE_CNGST, Map.of("GEO_CVRG_CRCT_VL", new BigDecimal("1.5")))
                .ajmtYn()).isEqualTo("Y");
        assertThat(resolver.resolve(AnlsVrblScope.LIFEZONE_CNGST, Map.of("PCSHR_CRTR_VL", new BigDecimal("0.9")))
                .ajmtYn()).isEqualTo("Y");

        // 🔴 rows·usedRows 에서는 빼지 않는다 — 적용 내역·ovrd.row(cd) 가 숨김 행을 전제한다
        AnlsVrblOvrd ovrd = resolver.resolve(AnlsVrblScope.LIFEZONE_CNGST, Map.of());
        assertThat(vrbls(ovrd)).extracting(v -> v.get("vrblCd")).contains("MIN_CPCTY_VL");
        assertThat(ovrd.row("MIN_CPCTY_VL")).isNotNull();
        // 적용 내역에 노출여부·기본값이 실린다 (TSK-4346 Step 6)
        Map<String, Object> hiddenAjmt = vrbl(ovrd, "MIN_CPCTY_VL");
        assertThat(hiddenAjmt).containsEntry("expsrYn", "N");
        assertThat((BigDecimal) hiddenAjmt.get("bscStngVl")).isEqualByComparingTo("20");
        assertThat(vrbl(ovrd, "GEO_CVRG_CRCT_VL")).containsEntry("expsrYn", "Y");
        assertThat(vrbl(ovrd, "PCSHR_CRTR_VL")).containsEntry("expsrYn", "Y");
    }

    @Test
    @DisplayName("null 값 → 400")
    void nullValueIs400() {
        stubInvest();
        Map<String, BigDecimal> input = new HashMap<>();
        input.put("ALTMNT_RNG_VL", null);

        assertBadRequest(AnlsVrblScope.INVEST, input);
    }

    // ------------------------------------------------------------------ 값

    @ParameterizedTest
    @ValueSource(strings = {"49.99", "1000.01", "1E+999999999", "100E+2147483647", "-1E+999999999"})
    @DisplayName("범위 밖 → 400 (지수 표기로 자원을 소진시키지 않는다)")
    void outOfRangeIs400(String value) {
        stubInvest();

        assertBadRequest(AnlsVrblScope.INVEST, Map.of("ALTMNT_RNG_VL", new BigDecimal(value)));
    }

    @Test
    @DisplayName("설정 칸(NUMERIC(12,4))에 담을 수 없는 소수 자릿수 → 400")
    void tooManyDecimalsIs400() {
        given(mapper.selectWeightsByType("LIFEZONE")).willReturn(cngstRows());

        assertBadRequest(AnlsVrblScope.LIFEZONE_CNGST, Map.of("PCSHR_CRTR_VL", new BigDecimal("0.85001")));
        assertBadRequest(AnlsVrblScope.LIFEZONE_CNGST, Map.of("PCSHR_CRTR_VL", new BigDecimal("1E-999999999")));
        assertThat(resolver.resolve(AnlsVrblScope.LIFEZONE_CNGST, Map.of("PCSHR_CRTR_VL", new BigDecimal("0.8500")))
                .ajmtYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("정수 전용(SPLY_*·GRD_BNDRY*)의 소수 → 400, 20.0 은 정수, 혼잡도 경계는 소수 허용")
    void integerOnlyVariables() {
        given(mapper.selectWeightsByType("LIFEZONE")).willReturn(splyAndCngstRows());
        stubHotspot();

        assertBadRequest(AnlsVrblScope.LIFEZONE_SPLY, Map.of("SPLY_GRD_BNDRY1", new BigDecimal("20.5")));
        assertBadRequest(AnlsVrblScope.LIFEZONE_SPLY, Map.of("SPLY_MIN_MNTH_AVG_CVLCPT", new BigDecimal("5.5")));
        assertBadRequest(AnlsVrblScope.HOTSPOT, Map.of("GRD_BNDRY1", new BigDecimal("5.5")));

        assertThat(resolver.resolve(AnlsVrblScope.LIFEZONE_SPLY, Map.of("SPLY_GRD_BNDRY1", new BigDecimal("20.0")))
                .ajmtYn()).isEqualTo("N");
        assertThat(resolver.resolve(AnlsVrblScope.LIFEZONE_CNGST, Map.of("CNGST_GRD_BNDRY1", new BigDecimal("88.5")))
                .ajmtYn()).isEqualTo("Y");
    }

    // ------------------------------------------------------------------ 묶음 규칙

    @Test
    @DisplayName("경계 역전 → 400: 입력을 설정값 위에 얹어 판정한다 (같은 값도 역전)")
    void boundaryInversionIs400() {
        stubHotspot(); // 2 / 8 / 10
        given(mapper.selectWeightsByType("LIFEZONE")).willReturn(splyAndCngstRows());

        assertBadRequest(AnlsVrblScope.HOTSPOT, Map.of("GRD_BNDRY1", new BigDecimal("8")));
        assertBadRequest(AnlsVrblScope.HOTSPOT, Map.of("GRD_BNDRY3", new BigDecimal("7")));
        assertBadRequest(AnlsVrblScope.LIFEZONE_SPLY, Map.of("SPLY_GRD_BNDRY2", new BigDecimal("10")));
        assertBadRequest(AnlsVrblScope.LIFEZONE_CNGST, Map.of("CNGST_GRD_BNDRY4", new BigDecimal("90")));

        // 한 행만 보내도 설정값과 합쳐 순서가 맞으면 통과, 묶음 전체(D21)도 통과
        assertThat(resolver.resolve(AnlsVrblScope.HOTSPOT, Map.of("GRD_BNDRY1", new BigDecimal("7"))).ajmtYn())
                .isEqualTo("Y");
        assertThat(resolver.resolve(AnlsVrblScope.HOTSPOT, Map.of(
                "GRD_BNDRY1", new BigDecimal("20"),
                "GRD_BNDRY2", new BigDecimal("30"),
                "GRD_BNDRY3", new BigDecimal("40"))).ajmtYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("투자 중간 ≥ 상위 → 400 (설정 경로 409 와 달리 입력 오류다)")
    void investMiddleMustBeBelowUpper() {
        stubInvest(); // 중간 60 · 상위 80

        assertBadRequest(AnlsVrblScope.INVEST, Map.of("MD_GRD_CRTR_SCR", new BigDecimal("80")));
        assertBadRequest(AnlsVrblScope.INVEST, Map.of("UP_GRD_CRTR_SCR", new BigDecimal("50")));
    }

    @Test
    @DisplayName("입력이 닿지 않은 묶음은 보지 않는다 — 설정값만의 문제로 400 을 내지 않는다")
    void untouchedGroupIsNotChecked() {
        given(mapper.selectWeightsByType("INVEST")).willReturn(List.of(
                row("INVEST", null, "SAFETY", "WGVL", "10", "0", "100"),
                row("INVEST", null, "COMPLAINT", "WGVL", "10", "0", "100"),
                row("INVEST", null, "MD_GRD_CRTR_SCR", "CFC", "90", "0", "100"),
                row("INVEST", null, "UP_GRD_CRTR_SCR", "CFC", "80", "0", "100"),
                row("INVEST", null, "ALTMNT_RNG_VL", "CFC", "300", "50", "1000")));

        assertThat(resolver.resolve(AnlsVrblScope.INVEST, Map.of("ALTMNT_RNG_VL", new BigDecimal("500"))).ajmtYn())
                .isEqualTo("Y");
    }

    @Test
    @DisplayName("가중치 합계 99 → 400: 묶음 전체도, 일부만 보내 설정값과 합친 결과도 (재정규화 없음)")
    void weightSumMustBeHundred() {
        stubInvest(); // 25 × 4

        assertBadRequest(AnlsVrblScope.INVEST, Map.of(
                "SAFETY", new BigDecimal("24"), "COMPLAINT", new BigDecimal("25"),
                "USAGE", new BigDecimal("25"), "ACCIDENT", new BigDecimal("25")));
        assertBadRequest(AnlsVrblScope.INVEST, Map.of("SAFETY", new BigDecimal("24")));

        AnlsVrblOvrd ovrd = resolver.resolve(AnlsVrblScope.INVEST, Map.of(
                "SAFETY", new BigDecimal("40"), "COMPLAINT", new BigDecimal("10")));
        assertThat(ovrd.value("SAFETY", () -> BigDecimal.ZERO)).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("요금제 가중치 2행 합계 99 → 400, 합계 100 은 통과 — FEE 계수(CFC)는 합계에 섞이지 않는다")
    void feeWeightSum() {
        given(mapper.selectWeightsByType("RECOMMEND")).willReturn(feeRows());

        assertBadRequest(AnlsVrblScope.RECOMMEND_FEE, Map.of(
                "FEE_CVLCPT_CNT_WGVL", new BigDecimal("70"), "FEE_CVLCPT_RT_WGVL", new BigDecimal("29")));

        assertThat(resolver.resolve(AnlsVrblScope.RECOMMEND_FEE, Map.of(
                "FEE_CVLCPT_CNT_WGVL", new BigDecimal("80"), "FEE_CVLCPT_RT_WGVL", new BigDecimal("20"))).ajmtYn())
                .isEqualTo("Y");
        assertThat(resolver.resolve(AnlsVrblScope.RECOMMEND_FEE, Map.of(
                "FEE_CVLCPT_INCRS_WGVL", new BigDecimal("0.7"))).ajmtYn()).isEqualTo("Y");
    }

    // ------------------------------------------------------------------ 정책 시뮬레이션 (TSK-4332 Step 1)

    @Test
    @DisplayName("정책 스코프 허용 목록 — 공통 4 + 유형별, 분석유형은 POLICY_SIM (TSK-4332 D1)")
    void policyScopesMatchPlan() {
        assertScope(AnlsVrblScope.POLICY_EXPAND, "POLICY_SIM",
                "PCSHR_CRTR_VL", "ALPHA_VL", "GEO_CVRG_CRCT_JEJU", "GEO_CVRG_CRCT_SEOGWIPO");
        assertScope(AnlsVrblScope.POLICY_REPAIR, "POLICY_SIM",
                "PCSHR_CRTR_VL", "ALPHA_VL", "GEO_CVRG_CRCT_JEJU", "GEO_CVRG_CRCT_SEOGWIPO",
                "N100_RSDNC", "N100_CMRC", "ALPHA_B_VL", "BALLOON_RT");
        assertScope(AnlsVrblScope.POLICY_FEE, "POLICY_SIM",
                "PCSHR_CRTR_VL", "ALPHA_VL", "GEO_CVRG_CRCT_JEJU", "GEO_CVRG_CRCT_SEOGWIPO",
                "PRC_ELST_VL", "PAID_DMND_RT", "TRNSF_RT", "TRNSF_LOCAL_RT");
        assertThat(AnlsVrblScope.POLICY_EXPAND.getBndryChains()).isEmpty();
        assertThat(AnlsVrblScope.POLICY_REPAIR.getBndryChains()).isEmpty();
        assertThat(AnlsVrblScope.POLICY_FEE.getBndryChains()).isEmpty();
    }

    @Test
    @DisplayName("POLICY_FEE 에 TRNSF_RT 0.25 → 통과(ajmtYn='Y'), 적용 내역은 요금제 스코프 8행")
    void policyFeeTransferRateAllowed() {
        stubPolicySim();

        AnlsVrblOvrd ovrd = resolver.resolve(AnlsVrblScope.POLICY_FEE, Map.of("TRNSF_RT", new BigDecimal("0.25")));

        assertThat(ovrd.ajmtYn()).isEqualTo("Y");
        assertThat(ovrd.value("TRNSF_RT", () -> BigDecimal.ZERO)).isEqualByComparingTo("0.25");
        assertThat(vrbls(ovrd)).hasSize(8);
    }

    @Test
    @DisplayName("POLICY_EXPAND 에 요금제 계수 TRNSF_RT → 400 (다른 유형 계수 — D1)")
    void policyExpandRejectsFeeCoefficient() {
        stubPolicySim();

        assertBadRequest(AnlsVrblScope.POLICY_EXPAND, Map.of("TRNSF_RT", new BigDecimal("0.25")));
    }

    @Test
    @DisplayName("고정값(min=max) PCSHR_THRSH → 400, 3스코프 모두 (D2)")
    void policyFixedValueIs400() {
        stubPolicySim();

        for (AnlsVrblScope scope : List.of(
                AnlsVrblScope.POLICY_EXPAND, AnlsVrblScope.POLICY_REPAIR, AnlsVrblScope.POLICY_FEE)) {
            assertBadRequest(scope, Map.of("PCSHR_THRSH", new BigDecimal("0.85")));
        }
    }

    @Test
    @DisplayName("PRC_ELST_VL(-0.6~-0.1) — -0.7 은 400, -0.1 은 경계 포함 통과")
    void policyNegativeRange() {
        stubPolicySim();

        assertBadRequest(AnlsVrblScope.POLICY_FEE, Map.of("PRC_ELST_VL", new BigDecimal("-0.7")));
        assertThat(resolver.resolve(AnlsVrblScope.POLICY_FEE, Map.of("PRC_ELST_VL", new BigDecimal("-0.1")))
                .ajmtYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("GEO_CVRG_CRCT_JEJU(1~4) 4.5 → 400")
    void policyGeoCorrectionOutOfRange() {
        stubPolicySim();

        assertBadRequest(AnlsVrblScope.POLICY_EXPAND, Map.of("GEO_CVRG_CRCT_JEJU", new BigDecimal("4.5")));
    }

    // ------------------------------------------------------------------ parse

    @Test
    @DisplayName("parse — JSON 객체는 Map 으로, 비어 있으면 빈 Map 으로")
    void parseValidJson() {
        assertThat(AnlsVrblOvrd.parse(null)).isEmpty();
        assertThat(AnlsVrblOvrd.parse("  ")).isEmpty();
        assertThat(AnlsVrblOvrd.parse("{}")).isEmpty();
        assertThat(AnlsVrblOvrd.parse("{\"ALTMNT_RNG_VL\":500,\"PCSHR_CRTR_VL\":0.85}"))
                .containsEntry("ALTMNT_RNG_VL", new BigDecimal("500"))
                .containsEntry("PCSHR_CRTR_VL", new BigDecimal("0.85"));
    }

    @Test
    @DisplayName("parse — JSON null 값은 Map 에 남아 resolve 가 400 으로 끊는다")
    void parseKeepsNullValueForResolver() {
        stubInvest();
        Map<String, BigDecimal> parsed = AnlsVrblOvrd.parse("{\"ALTMNT_RNG_VL\":null}");

        assertThat(parsed).containsKey("ALTMNT_RNG_VL");
        assertBadRequest(AnlsVrblScope.INVEST, parsed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "abc", "[1,2]", "500", "null", "{\"A\":\"abc\"}", "{\"A\":true}",
            "{\"A\":{\"B\":1}}", "{\"A\":1,\"A\":2}", "{\"A\":1} {\"B\":2}", "{\"A\":1E+99999999999}"})
    @DisplayName("parse — 형식 오류는 400 이고 입력 원문을 메시지에 싣지 않는다")
    void parseMalformedJsonIs400(String json) {
        assertThatThrownBy(() -> AnlsVrblOvrd.parse(json))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).doesNotContain(json);
                });
    }

    // ------------------------------------------------------------------ 헬퍼

    private void assertBadRequest(AnlsVrblScope scope, Map<String, BigDecimal> input) {
        assertThatThrownBy(() -> resolver.resolve(scope, input))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** 🔴 개수가 아니라 코드 집합을 본다(계획서 §3 표) — 개수만 보면 코드가 뒤바뀌어도 통과한다. */
    private static void assertScope(AnlsVrblScope scope, String anlsTypeCd, String... vrblCds) {
        assertThat(scope.getAnlsTypeCd()).isEqualTo(anlsTypeCd);
        assertThat(scope.getVrblCds()).containsExactlyInAnyOrder(vrblCds);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vrbls(AnlsVrblOvrd ovrd) {
        return (List<Map<String, Object>>) ovrd.toAjmtJson().get("vrbls");
    }

    private static Map<String, Object> vrbl(AnlsVrblOvrd ovrd, String vrblCd) {
        return vrbls(ovrd).stream().filter(v -> vrblCd.equals(v.get("vrblCd"))).findFirst().orElseThrow();
    }

    private static AnalysisWeightVO find(List<AnalysisWeightVO> rows, String vrblCd) {
        return rows.stream().filter(r -> vrblCd.equals(r.getVrblCd())).findFirst().orElseThrow();
    }

    private void stubHotspot() {
        given(mapper.selectWeightsByType("HOTSPOT")).willReturn(List.of(
                row("HOTSPOT", null, "COMPLAINT", "WGVL", "60", "0", "100"),
                row("HOTSPOT", null, "ENFORCE", "WGVL", "40", "0", "100"),
                row("HOTSPOT", null, "GRD_BNDRY1", "CFC", "2", "1", "100"),
                row("HOTSPOT", null, "GRD_BNDRY2", "CFC", "8", "1", "100"),
                row("HOTSPOT", null, "GRD_BNDRY3", "CFC", "10", "1", "100")));
    }

    private List<AnalysisWeightVO> stubInvest() {
        List<AnalysisWeightVO> rows = investRows();
        given(mapper.selectWeightsByType("INVEST")).willReturn(rows);
        return rows;
    }

    private static List<AnalysisWeightVO> investRows() {
        return List.of(
                row("INVEST", null, "SAFETY", "WGVL", "25.0000", "0", "100"),
                row("INVEST", null, "COMPLAINT", "WGVL", "25.0000", "0", "100"),
                row("INVEST", null, "USAGE", "WGVL", "25.0000", "0", "100"),
                row("INVEST", null, "ACCIDENT", "WGVL", "25.0000", "0", "100"),
                row("INVEST", null, "ALTMNT_RNG_VL", "CFC", "300.0000", "50", "1000"),
                row("INVEST", null, "UP_GRD_CRTR_SCR", "CFC", "80.0000", "0", "100"),
                row("INVEST", null, "MD_GRD_CRTR_SCR", "CFC", "60.0000", "0", "100"),
                row("INVEST", null, "INDCT_PKLT_CNT", "CFC", "50.0000", "1", "500"));
    }

    private static List<AnalysisWeightVO> cngstRows() {
        return List.of(
                row("LIFEZONE", "CNGST", "PCSHR_CRTR_VL", "CFC", "0.85", "0.3", "1"),
                row("LIFEZONE", "CNGST", "CNGST_GRD_BNDRY1", "CFC", "88", "50", "100"),
                row("LIFEZONE", "CNGST", "CNGST_GRD_BNDRY2", "CFC", "92", "50", "100"),
                row("LIFEZONE", "CNGST", "CNGST_GRD_BNDRY3", "CFC", "96", "50", "100"),
                row("LIFEZONE", "CNGST", "CNGST_GRD_BNDRY4", "CFC", "100", "50", "100"));
    }

    private static List<AnalysisWeightVO> splyAndCngstRows() {
        List<AnalysisWeightVO> rows = new ArrayList<>(cngstRows());
        rows.add(row("LIFEZONE", "SPLY_DMND", "SPLY_MIN_MNTH_AVG_CVLCPT", "CFC", "5", "1", "30"));
        rows.add(row("LIFEZONE", "SPLY_DMND", "SPLY_GRD_BNDRY1", "CFC", "20", "1", "99"));
        rows.add(row("LIFEZONE", "SPLY_DMND", "SPLY_GRD_BNDRY2", "CFC", "40", "1", "99"));
        rows.add(row("LIFEZONE", "SPLY_DMND", "SPLY_GRD_BNDRY3", "CFC", "60", "1", "99"));
        rows.add(row("LIFEZONE", "SPLY_DMND", "SPLY_GRD_BNDRY4", "CFC", "80", "1", "99"));
        return rows;
    }

    private static List<AnalysisWeightVO> feeRows() {
        return List.of(
                row("RECOMMEND", null, "ALTMNT_RNG_VL", "CFC", "300", "100", "1000"),
                row("RECOMMEND", "FEE", "FEE_CVLCPT_CNT_WGVL", "WGVL", "70", "0", "100"),
                row("RECOMMEND", "FEE", "FEE_CVLCPT_RT_WGVL", "WGVL", "30", "0", "100"),
                row("RECOMMEND", "FEE", "FEE_CVLCPT_INCRS_WGVL", "CFC", "0.5", "0", "1"));
    }

    /** 로컬 DB {@code POLICY_SIM} 21행과 같은 값·범위 (고정값 min=max 9행 포함). */
    private void stubPolicySim() {
        given(mapper.selectWeightsByType("POLICY_SIM")).willReturn(List.of(
                row("POLICY_SIM", null, "ALPHA_VL", "CFC", "0.5", "0.3", "1"),
                row("POLICY_SIM", null, "DSCL_MLTP", "CFC", "3", "3", "3"),
                row("POLICY_SIM", null, "FIX_WGVL", "CFC", "0.5", "0.5", "0.5"),
                row("POLICY_SIM", null, "GEO_CVRG_CRCT_JEJU", "CFC", "2.89", "1", "4"),
                row("POLICY_SIM", null, "GEO_CVRG_CRCT_SEOGWIPO", "CFC", "1.385", "1", "2"),
                row("POLICY_SIM", null, "PCSHR_CRTR_VL", "CFC", "0.85", "0.3", "1"),
                row("POLICY_SIM", null, "PCSHR_THRSH", "CFC", "0.85", "0.85", "0.85"),
                row("POLICY_SIM", "EXPAND", "CST_HIGH_BNDRY", "CFC", "30000000", "30000000", "30000000"),
                row("POLICY_SIM", "EXPAND", "CST_LOW_BNDRY", "CFC", "5000000", "5000000", "5000000"),
                row("POLICY_SIM", "EXPAND", "CST_MULTFLR", "CFC", "18150000", "18150000", "18150000"),
                row("POLICY_SIM", "EXPAND", "CST_PBLPKLT", "CFC", "38220000", "38220000", "38220000"),
                row("POLICY_SIM", "EXPAND", "CST_VACLOT", "CFC", "1870000", "1870000", "1870000"),
                row("POLICY_SIM", "EXPAND", "VACLOT_GVBK_RT", "CFC", "0.032", "0.032", "0.032"),
                row("POLICY_SIM", "FEE", "PAID_DMND_RT", "CFC", "0.3", "0.15", "0.5"),
                row("POLICY_SIM", "FEE", "PRC_ELST_VL", "CFC", "-0.3", "-0.6", "-0.1"),
                row("POLICY_SIM", "FEE", "TRNSF_LOCAL_RT", "CFC", "0.5", "0.3", "0.7"),
                row("POLICY_SIM", "FEE", "TRNSF_RT", "CFC", "0.2", "0.1", "0.3"),
                row("POLICY_SIM", "REPAIR", "ALPHA_B_VL", "CFC", "0.8", "0.5", "1"),
                row("POLICY_SIM", "REPAIR", "BALLOON_RT", "CFC", "0.2", "0.1", "0.3"),
                row("POLICY_SIM", "REPAIR", "N100_CMRC", "CFC", "12", "8", "16"),
                row("POLICY_SIM", "REPAIR", "N100_RSDNC", "CFC", "14.4", "8", "20")));
    }

    private static AnalysisWeightVO row(String anlsTypeCd, String anlsTrgtCd, String vrblCd, String vrblSeCd,
                                        String stngVl, String minVl, String maxVl) {
        AnalysisWeightVO row = new AnalysisWeightVO();
        row.setAnlsTypeCd(anlsTypeCd);
        row.setAnlsTrgtCd(anlsTrgtCd);
        row.setVrblCd(vrblCd);
        row.setVrblNm(vrblCd + " 이름");
        row.setVrblSeCd(vrblSeCd);
        row.setStngVl(new BigDecimal(stngVl));
        row.setBscStngVl(new BigDecimal(stngVl));
        row.setMinVl(new BigDecimal(minVl));
        row.setMaxVl(new BigDecimal(maxVl));
        row.setUnitNm("ALTMNT_RNG_VL".equals(vrblCd) ? "m" : null);
        row.setUseYn("Y");
        return row;
    }
}
