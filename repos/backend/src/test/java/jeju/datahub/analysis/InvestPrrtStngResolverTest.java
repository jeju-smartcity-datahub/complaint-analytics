package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.AnalysisWeightService;
import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.impl.AnalysisWeightMapper;
import jeju.datahub.analysis.service.impl.InvestPrrtStngResolver;
import jeju.datahub.analysis.service.impl.InvestPrrtStngResolver.InvestPrrtStng;

/**
 * {@link InvestPrrtStngResolver} 단위 테스트.
 *
 * <p>DB·스프링 컨텍스트를 띄우지 않는다 — {@link AnalysisWeightService}(가중치)와
 * {@link AnalysisWeightMapper}(계수 원본 행)를 Mockito 로 갈아끼운다({@code HotspotWeightResolverTest}
 * 형식).
 *
 * <p>⚠️ 가중치 mock 이 돌려주는 목록은 <b>이미 재정규화된</b> 값이다
 * ({@link AnalysisWeightService#selectEffectiveWeights(String)} 계약 — {@code use_yn='N'} 제거 +
 * 합계 100 복원). 그래서 "단속을 껐다" 는 그 행을 <b>빼고</b> 재현한다.
 */
@ExtendWith(MockitoExtension.class)
class InvestPrrtStngResolverTest {

    private static final String INVEST = "INVEST";

    @Mock
    private AnalysisWeightService analysisWeightService;

    @Mock
    private AnalysisWeightMapper analysisWeightMapper;

    @InjectMocks
    private InvestPrrtStngResolver resolver;

    @Test
    @DisplayName("균등 25 4개 + 계수 4개(300 · 80 · 60 · 50)를 읽는다")
    void resolvesDefaults() {
        givenWeights(weight("SAFETY", "25", 1), weight("COMPLAINT", "25", 2),
                weight("USAGE", "25", 3), weight("ENFORCE", "25", 4));
        givenCoefficients(cfc("ALTMNT_RNG_VL", "300.0000"), cfc("UP_GRD_CRTR_SCR", "80.0000"),
                cfc("MD_GRD_CRTR_SCR", "60.0000"), cfc("INDCT_PKLT_CNT", "50.0000"));

        InvestPrrtStng stng = resolver.resolve();

        assertThat(stng.weights().keySet()).containsExactly("SAFETY", "COMPLAINT", "USAGE", "ENFORCE");
        assertThat(stng.weights().get("ENFORCE")).isEqualByComparingTo("25");
        assertThat(stng.appliedWeights()).extracting(AnalysisWeightVO::getVrblCd)
                .containsExactly("SAFETY", "COMPLAINT", "USAGE", "ENFORCE");
        assertThat(stng.altmntRngVl()).isEqualByComparingTo("300");
        assertThat(stng.upGrdCrtrScr()).isEqualByComparingTo("80");
        assertThat(stng.mdGrdCrtrScr()).isEqualByComparingTo("60");
        assertThat(stng.indctPkltCnt()).isEqualTo(50);
    }

    @Test
    @DisplayName("가중치 0 · 산식이 모르는 변수는 계산용 Map 에서도 되비출 목록에서도 빠진다")
    void dropsZeroAndUnknownWeights() {
        givenWeights(weight("SAFETY", "34", 1), weight("COMPLAINT", "33", 2),
                weight("USAGE", "13", 3), weight("ENFORCE", "0", 4), weight("UNKNOWN_X", "20", 5));
        givenDefaultCoefficients();

        InvestPrrtStng stng = resolver.resolve();

        assertThat(stng.weights().keySet()).containsExactly("SAFETY", "COMPLAINT", "USAGE");
        assertThat(stng.appliedWeights()).extracting(AnalysisWeightVO::getVrblCd)
                .containsExactly("SAFETY", "COMPLAINT", "USAGE");
    }

    @Test
    @DisplayName("ACCIDENT 는 무시되지 않고 반영되며, ENFORCE 행이 없으면 weights 에서 빠진다")
    void accidentReflectedAndEnforceAbsent() {
        // 마이그레이션 뒤의 INVEST 구성 — 단속 행이 지워지고 사고가 켜졌다.
        givenWeights(weight("SAFETY", "25", 1), weight("COMPLAINT", "25", 2),
                weight("USAGE", "25", 3), weight("ACCIDENT", "25", 5));
        givenDefaultCoefficients();

        InvestPrrtStng stng = resolver.resolve();

        assertThat(stng.weights().keySet()).containsExactly("SAFETY", "COMPLAINT", "USAGE", "ACCIDENT");
        assertThat(stng.weights().get("ACCIDENT")).isEqualByComparingTo("25");
        assertThat(stng.weights()).doesNotContainKey("ENFORCE");
        assertThat(stng.appliedWeights()).extracting(AnalysisWeightVO::getVrblCd)
                .containsExactly("SAFETY", "COMPLAINT", "USAGE", "ACCIDENT");
    }

    @Test
    @DisplayName("계수(CFC) 행이 가중치 목록에 섞여 와도 점수 가중치로 새지 않는다")
    void ignoresCoefficientRowsAmongWeights() {
        AnalysisWeightVO leaked = cfc("UP_GRD_CRTR_SCR", "80");
        givenWeights(weight("SAFETY", "100", 1), leaked);
        givenDefaultCoefficients();

        assertThat(resolver.resolve().weights().keySet()).containsExactly("SAFETY");
    }

    @Test
    @DisplayName("유효 가중치가 하나도 없으면 409 — 조용히 0점을 만들지 않는다")
    void conflictWhenNoEffectiveWeights() {
        given(analysisWeightService.selectEffectiveWeights(INVEST)).willReturn(List.of());

        assertConflict("INVEST");
    }

    @Test
    @DisplayName("남은 가중치가 전부 0 이거나 모르는 변수뿐이어도 409")
    void conflictWhenOnlyZeroOrUnknownWeights() {
        // ACCIDENT 는 이제 아는 변수라 실존하지 않는 코드로 예시를 바꿨다
        givenWeights(weight("SAFETY", "0", 1), weight("UNKNOWN_X", "100", 5));

        assertConflict("INVEST");
    }

    @Test
    @DisplayName("임계 역전(중간 > 높음)은 409")
    void conflictWhenThresholdsInverted() {
        givenWeights(weight("SAFETY", "100", 1));
        givenCoefficients(cfc("ALTMNT_RNG_VL", "300"), cfc("UP_GRD_CRTR_SCR", "60"),
                cfc("MD_GRD_CRTR_SCR", "70"), cfc("INDCT_PKLT_CNT", "50"));

        assertConflict("MD_GRD_CRTR_SCR");
    }

    @Test
    @DisplayName("임계가 같아도(중간 = 높음) 409 — '중간' 등급이 나올 구간이 없다")
    void conflictWhenThresholdsEqual() {
        givenWeights(weight("SAFETY", "100", 1));
        givenCoefficients(cfc("ALTMNT_RNG_VL", "300"), cfc("UP_GRD_CRTR_SCR", "70.0000"),
                cfc("MD_GRD_CRTR_SCR", "70"), cfc("INDCT_PKLT_CNT", "50"));

        assertConflict("UP_GRD_CRTR_SCR");
    }

    @Test
    @DisplayName("계수 행이 없으면 1.0 이 아니라 시드 기본값(300 · 80 · 60 · 50)으로 진행한다")
    void fallsBackToSeedDefaultsWhenRowsMissing() {
        givenWeights(weight("SAFETY", "100", 1));
        given(analysisWeightMapper.selectWeightsByType(INVEST)).willReturn(List.of());

        InvestPrrtStng stng = resolver.resolve();

        assertThat(stng.altmntRngVl()).isEqualByComparingTo("300");
        assertThat(stng.upGrdCrtrScr()).isEqualByComparingTo("80");
        assertThat(stng.mdGrdCrtrScr()).isEqualByComparingTo("60");
        assertThat(stng.indctPkltCnt()).isEqualTo(50);
    }

    @Test
    @DisplayName("꺼진 계수는 기본설정값(bsc_stng_vl)으로, 범위 밖 값은 시드 기본값으로 대체한다")
    void fallsBackForDisabledOrOutOfRangeCoefficients() {
        givenWeights(weight("SAFETY", "100", 1));
        AnalysisWeightVO offUp = cfc("UP_GRD_CRTR_SCR", "95");
        offUp.setUseYn("N");
        offUp.setBscStngVl(new BigDecimal("80"));
        givenCoefficients(cfc("ALTMNT_RNG_VL", "0"), offUp,
                cfc("MD_GRD_CRTR_SCR", "140"), cfc("INDCT_PKLT_CNT", "0"));

        InvestPrrtStng stng = resolver.resolve();

        assertThat(stng.altmntRngVl()).isEqualByComparingTo("300"); // 반경 0 은 허용하지 않는다
        assertThat(stng.upGrdCrtrScr()).isEqualByComparingTo("80"); // 꺼짐 → bsc_stng_vl
        assertThat(stng.mdGrdCrtrScr()).isEqualByComparingTo("60"); // 100 초과 → 기본값
        assertThat(stng.indctPkltCnt()).isEqualTo(50);              // 1 미만 → 기본값
    }

    // ------------------------------------------------------------------ 일시 입력

    @Test
    @DisplayName("입력 없음 — resolve() 와 같은 창구를 타고 같은 값을 낸다")
    void emptyOverrideBehavesLikeResolve() {
        givenWeights(weight("SAFETY", "50", 1), weight("USAGE", "50", 3));
        givenDefaultCoefficients();

        InvestPrrtStng stng = resolver.resolve(ovrd(Map.of()));

        assertThat(stng.weights().keySet()).containsExactly("SAFETY", "USAGE");
        assertThat(stng.altmntRngVl()).isEqualByComparingTo("300");
        verify(analysisWeightService).selectEffectiveWeights(INVEST);
    }

    @Test
    @DisplayName("계수만 입력 — 가중치는 재정규화 창구 그대로, 계수 4개는 입력값이고 원본 행은 바뀌지 않는다")
    void coefficientInputOverlaysRows() {
        AnalysisWeightVO radius = cfc("ALTMNT_RNG_VL", "300");
        givenWeights(weight("SAFETY", "100", 1));
        givenCoefficients(radius, cfc("UP_GRD_CRTR_SCR", "80"), cfc("MD_GRD_CRTR_SCR", "60"), cfc("INDCT_PKLT_CNT", "50"));

        InvestPrrtStng stng = resolver.resolve(ovrd(Map.of(
                "ALTMNT_RNG_VL", new BigDecimal("500"), "UP_GRD_CRTR_SCR", new BigDecimal("90"),
                "MD_GRD_CRTR_SCR", new BigDecimal("70"), "INDCT_PKLT_CNT", new BigDecimal("30"))));

        assertThat(stng.altmntRngVl()).isEqualByComparingTo("500");
        assertThat(stng.upGrdCrtrScr()).isEqualByComparingTo("90");
        assertThat(stng.mdGrdCrtrScr()).isEqualByComparingTo("70");
        assertThat(stng.indctPkltCnt()).isEqualTo(30);
        assertThat(stng.weights()).containsOnlyKeys("SAFETY");
        assertThat(radius.getStngVl()).as("원본 행 불변").isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("가중치 입력 — 재정규화 없이 입력값 그대로 쓰고, 0 인 지표는 weights 에서 빠진다")
    void weightInputIsUsedAsIs() {
        givenCoefficients(weight("SAFETY", "25", 1), weight("COMPLAINT", "25", 2), weight("USAGE", "25", 3),
                weight("ACCIDENT", "25", 5), cfc("ALTMNT_RNG_VL", "300"), cfc("UP_GRD_CRTR_SCR", "80"),
                cfc("MD_GRD_CRTR_SCR", "60"), cfc("INDCT_PKLT_CNT", "50"));

        InvestPrrtStng stng = resolver.resolve(ovrd(Map.of(
                "SAFETY", new BigDecimal("70"), "COMPLAINT", new BigDecimal("30"),
                "USAGE", BigDecimal.ZERO, "ACCIDENT", BigDecimal.ZERO)));

        assertThat(stng.weights().keySet()).containsExactly("SAFETY", "COMPLAINT");
        assertThat(stng.weights().get("SAFETY")).isEqualByComparingTo("70");
        assertThat(stng.appliedWeights()).extracting(AnalysisWeightVO::getVrblCd).containsExactly("SAFETY", "COMPLAINT");
        verify(analysisWeightService, never()).selectEffectiveWeights(INVEST);
    }

    @Test
    @DisplayName("꺼진 지표가 있을 때 가중치 입력 — 재정규화된 값이 아니라 원본 행에 얹는다(합계가 100 을 벗어나지 않는다)")
    void weightInputDoesNotMixWithRenormalizedValues() {
        AnalysisWeightVO off = weight("ACCIDENT", "25", 5);
        off.setUseYn("N");
        givenCoefficients(weight("SAFETY", "25", 1), weight("COMPLAINT", "25", 2), weight("USAGE", "25", 3), off,
                cfc("ALTMNT_RNG_VL", "300"), cfc("UP_GRD_CRTR_SCR", "80"),
                cfc("MD_GRD_CRTR_SCR", "60"), cfc("INDCT_PKLT_CNT", "50"));

        InvestPrrtStng stng = resolver.resolve(ovrd(Map.of("SAFETY", new BigDecimal("50"))));

        assertThat(stng.weights().keySet()).containsExactly("SAFETY", "COMPLAINT", "USAGE");
        assertThat(stng.weights().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("역전 — 등급 기준을 입력했으면 400, 입력이 등급 기준에 닿지 않았으면 설정 오류 409 그대로다")
    void inversionIs400OnlyWhenInputTouchesGradeCriteria() {
        // 꺼진 상위 기준은 bsc_stng_vl 로 떨어져 입력 검증(stng_vl 기준)을 통과하고도 역전될 수 있다
        AnalysisWeightVO offUp = cfc("UP_GRD_CRTR_SCR", "95");
        offUp.setUseYn("N");
        offUp.setBscStngVl(new BigDecimal("80"));
        givenWeights(weight("SAFETY", "100", 1));
        givenCoefficients(cfc("ALTMNT_RNG_VL", "300"), offUp, cfc("MD_GRD_CRTR_SCR", "60"), cfc("INDCT_PKLT_CNT", "50"));

        assertThatThrownBy(() -> resolver.resolve(ovrd(Map.of("MD_GRD_CRTR_SCR", new BigDecimal("90")))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // 설정이 이미 역전됐고 입력은 반경뿐이다
        givenCoefficients(cfc("ALTMNT_RNG_VL", "300"), cfc("UP_GRD_CRTR_SCR", "50"),
                cfc("MD_GRD_CRTR_SCR", "60"), cfc("INDCT_PKLT_CNT", "50"));
        assertThatThrownBy(() -> resolver.resolve(ovrd(Map.of("ALTMNT_RNG_VL", new BigDecimal("500")))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private static AnlsVrblOvrd ovrd(Map<String, BigDecimal> values) {
        return AnlsVrblOvrd.of(AnlsVrblScope.INVEST, List.of(), values);
    }

    // ------------------------------------------------------------------ 픽스처

    private void assertConflict(String messagePart) {
        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(messagePart)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private void givenWeights(AnalysisWeightVO... weights) {
        given(analysisWeightService.selectEffectiveWeights(INVEST)).willReturn(new ArrayList<>(List.of(weights)));
    }

    private void givenCoefficients(AnalysisWeightVO... rows) {
        given(analysisWeightMapper.selectWeightsByType(INVEST)).willReturn(new ArrayList<>(List.of(rows)));
    }

    private void givenDefaultCoefficients() {
        lenient().when(analysisWeightMapper.selectWeightsByType(INVEST)).thenReturn(List.of(
                cfc("ALTMNT_RNG_VL", "300"), cfc("UP_GRD_CRTR_SCR", "80"),
                cfc("MD_GRD_CRTR_SCR", "60"), cfc("INDCT_PKLT_CNT", "50")));
    }

    /** 가중치 행. {@code use_yn} 은 CHAR(1) 의 'Y'/'N' 문자열이고, {@code vrblSeCd} 는 반드시 채운다. */
    private static AnalysisWeightVO weight(String vrblCd, String stngVl, int sortSeq) {
        AnalysisWeightVO vo = row(vrblCd, stngVl);
        vo.setVrblSeCd("WGVL");
        vo.setSortSeq(sortSeq);
        return vo;
    }

    /** 계수 행(저장된 원본 — 재정규화를 타지 않는다). */
    private static AnalysisWeightVO cfc(String vrblCd, String stngVl) {
        AnalysisWeightVO vo = row(vrblCd, stngVl);
        vo.setVrblSeCd("CFC");
        return vo;
    }

    private static AnalysisWeightVO row(String vrblCd, String stngVl) {
        AnalysisWeightVO vo = new AnalysisWeightVO();
        vo.setAnlsTypeCd(INVEST);
        vo.setVrblCd(vrblCd);
        vo.setVrblNm(vrblCd);
        vo.setStngVl(new BigDecimal(stngVl));
        vo.setBscStngVl(new BigDecimal(stngVl));
        vo.setUseYn("Y");
        return vo;
    }
}
