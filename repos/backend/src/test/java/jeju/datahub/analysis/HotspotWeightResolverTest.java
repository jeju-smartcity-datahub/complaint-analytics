package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.List;

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
import jeju.datahub.analysis.service.impl.HotspotWeightResolver;
import jeju.datahub.analysis.service.impl.HotspotWeightResolver.HotspotWeights;

/**
 * {@link HotspotWeightResolver} 단위 테스트.
 *
 * <p>DB·스프링 컨텍스트를 띄우지 않는다 — 검증 대상이 "퍼센트 → 계수 환산" 뿐이라
 * {@link AnalysisWeightService} 를 Mockito 로 갈아끼우는 것으로 충분하다. 같은 패키지의
 * {@code AnalysisWeightIntegrationTest} 는 DB 를 쓰는 통합 테스트라 성격이 다르다.
 *
 * <p>⚠️ mock 이 돌려주는 목록은 <b>이미 재정규화된</b> 값이다 —
 * {@link AnalysisWeightService#selectEffectiveWeights(String)} 가 {@code use_yn='N'} 제거 + 합계
 * 100 복원까지 끝낸 계약이기 때문이다. 그래서 "ENFORCE 를 껐다" 는 상황은
 * <b>COMPLAINT 100 한 행</b>으로 재현한다 — {@code useYn='N'} 행을 섞어 넣는 게 아니다.
 *
 * <p>🔴 {@link BigDecimal} 단언은 전부 {@code isEqualByComparingTo} 로 한다 — 계수는 스케일 6
 * ({@code 0.600000})이라 {@code isEqualTo(new BigDecimal("0.6"))} 는 스케일 차이로 실패한다.
 */
@ExtendWith(MockitoExtension.class)
class HotspotWeightResolverTest {

    private static final String HOTSPOT = "HOTSPOT";

    @Mock
    private AnalysisWeightService analysisWeightService;

    @InjectMocks
    private HotspotWeightResolver resolver;

    @Test
    @DisplayName("민원 60 · 단속 40 → 계수 0.6 / 0.4, 원본 가중치를 그대로 되비춘다")
    void resolvesSixtyFortyIntoCoefficients() {
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT))
                .willReturn(List.of(weight("COMPLAINT", "60", 1), weight("ENFORCE", "40", 2)));

        HotspotWeights weights = resolver.resolve();

        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("0.6");
        assertThat(weights.crdnWgt()).isEqualByComparingTo("0.4");
        // 계수 합은 정확히 1 이어야 한다 — 점수가 설정 변경만으로 통째로 줄지 않게.
        assertThat(weights.cvlcptWgt().add(weights.crdnWgt())).isEqualByComparingTo("1");
        // 응답 echo 는 계수가 아니라 퍼센트 원본이다
        assertThat(weights.weights()).extracting(AnalysisWeightVO::getVrblCd)
                .containsExactly("COMPLAINT", "ENFORCE");
        assertThat(weights.weights().get(0).getStngVl()).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("단속을 끄면(재정규화 결과 민원 100) → 계수 1.0 / 0")
    void resolvesSingleRemainingIndicatorIntoOne() {
        // selectEffectiveWeights 가 use_yn='N' 인 ENFORCE 를 빼고 COMPLAINT 를 100 으로 돌려준 상태
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT))
                .willReturn(List.of(weight("COMPLAINT", "100.00", 1)));

        HotspotWeights weights = resolver.resolve();

        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("1");
        assertThat(weights.crdnWgt()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("민원을 끄면(재정규화 결과 단속 100) → 계수 0 / 1.0")
    void resolvesRemainingEnforceIntoOne() {
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT))
                .willReturn(List.of(weight("ENFORCE", "100.00", 2)));

        HotspotWeights weights = resolver.resolve();

        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("0");
        assertThat(weights.crdnWgt()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("지표가 하나도 없으면 조용히 0점을 만들지 않고 실패한다 (메시지에 anlsTypeCd 포함)")
    void failsWhenNoEffectiveWeights() {
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT)).willReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(HOTSPOT)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("모르는 지표코드만 오면 실패한다 — 점수 산식에 반영할 항이 없다")
    void failsWhenOnlyUnknownIndicators() {
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT))
                .willReturn(List.of(weight("SOMETHING_NEW", "100", 1)));

        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(HOTSPOT)
                .hasMessageContaining("COMPLAINT");
    }

    @Test
    @DisplayName("모르는 지표가 섞여 있으면 무시하고, 아는 지표끼리 계수 합 1 을 유지한다")
    void ignoresUnknownIndicators() {
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT))
                .willReturn(List.of(
                        weight("COMPLAINT", "50", 1),
                        weight("ENFORCE", "30", 2),
                        weight("SOMETHING_NEW", "20", 3)));

        HotspotWeights weights = resolver.resolve();

        // 50/(50+30) = 0.625 — 모르는 지표(20)는 분모에도 넣지 않는다
        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("0.625");
        assertThat(weights.crdnWgt()).isEqualByComparingTo("0.375");
        // echo 목록은 무시한 지표까지 원본 그대로 (응답 재현용)
        assertThat(weights.weights()).hasSize(3);
    }

    @Test
    @DisplayName("가중치가 전부 0 이면 실패한다 — 0 으로 나누지 않는다")
    void failsWhenTotalIsZero() {
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT))
                .willReturn(List.of(weight("COMPLAINT", "0", 1), weight("ENFORCE", "0.00", 2)));

        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(HOTSPOT);
    }

    // ---------------------------------------------------- 화면 입력 가중치
    // 🔴 strict stubs — ①~③ 에서 끝나는 케이스(누락·자릿수·합계)는 selectEffectiveWeights 를 스텁하지 않는다.

    @Test
    @DisplayName("입력 70/30 → 계수 0.7 / 0.3, 응답 stngVl 은 입력값이고 다른 필드는 유지한다")
    void resolvesInputIntoCoefficients() {
        givenSixtyForty();

        HotspotWeights weights = resolver.resolve(new BigDecimal("70"), new BigDecimal("30"));

        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("0.7");
        assertThat(weights.crdnWgt()).isEqualByComparingTo("0.3");
        assertThat(weights.cvlcptWgt().add(weights.crdnWgt())).isEqualByComparingTo("1");
        assertThat(weights.weights()).extracting(AnalysisWeightVO::getVrblCd)
                .containsExactly("COMPLAINT", "ENFORCE");
        assertThat(weights.weights().get(0).getStngVl()).isEqualByComparingTo("70");
        assertThat(weights.weights().get(1).getStngVl()).isEqualByComparingTo("30");
        assertThat(weights.weights().get(0).getVrblNm()).isEqualTo("COMPLAINT");
        assertThat(weights.weights().get(0).getBscStngVl()).isEqualByComparingTo("60");
        assertThat(weights.weights().get(0).getMaxVl()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("입력 0/100 → 계수 0 / 1, 0% 행도 응답에 남는다")
    void acceptsZeroComplaint() {
        givenSixtyForty();

        HotspotWeights weights = resolver.resolve(new BigDecimal("0"), new BigDecimal("100"));

        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("0");
        assertThat(weights.crdnWgt()).isEqualByComparingTo("1");
        assertThat(weights.weights()).hasSize(2);
        assertThat(weights.weights().get(0).getStngVl()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("입력 100/0 → 계수 1 / 0, 0% 행도 응답에 남는다")
    void acceptsZeroEnforce() {
        givenSixtyForty();

        HotspotWeights weights = resolver.resolve(new BigDecimal("100"), new BigDecimal("0"));

        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("1");
        assertThat(weights.crdnWgt()).isEqualByComparingTo("0");
        assertThat(weights.weights()).hasSize(2);
        assertThat(weights.weights().get(1).getStngVl()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("입력 33.33/66.67(소수 둘째 자리)은 받는다 — 계수 합은 정확히 1")
    void acceptsTwoDecimalPlaces() {
        givenSixtyForty();

        HotspotWeights weights = resolver.resolve(new BigDecimal("33.33"), new BigDecimal("66.67"));

        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("0.3333");
        assertThat(weights.crdnWgt()).isEqualByComparingTo("0.6667");
    }

    @Test
    @DisplayName("끝자리 0 은 자릿수로 세지 않고, 합계는 스케일이 달라도 값으로 비교한다 (70.000 + 30)")
    void ignoresTrailingZerosAndScale() {
        givenSixtyForty();

        HotspotWeights weights = resolver.resolve(new BigDecimal("70.000"), new BigDecimal("30"));

        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("0.7");
    }

    @Test
    @DisplayName("입력이 둘 다 null 이면 설정값을 쓴다")
    void fallsBackToSettingsWhenInputIsAbsent() {
        givenSixtyForty();

        HotspotWeights weights = resolver.resolve(null, null);

        assertThat(weights.cvlcptWgt()).isEqualByComparingTo("0.6");
        assertThat(weights.crdnWgt()).isEqualByComparingTo("0.4");
        assertThat(weights.weights().get(0).getStngVl()).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("한쪽만 보내면 400")
    void rejectsOneSidedInput() {
        assertBadRequest(new BigDecimal("70"), null, "함께");
        assertBadRequest(null, new BigDecimal("30"), "함께");
    }

    @Test
    @DisplayName("합계가 100 이 아니면 재정규화하지 않고 400")
    void rejectsSumOtherThanHundred() {
        assertBadRequest(new BigDecimal("70"), new BigDecimal("29"), "합계");
    }

    @Test
    @DisplayName("소수 셋째 자리 이상이면 400")
    void rejectsThreeDecimalPlaces() {
        assertBadRequest(new BigDecimal("33.333"), new BigDecimal("66.667"), "소수");
    }

    @Test
    @DisplayName("큰 지수 표기는 합계를 계산하기 전에 400 — add 가 자릿수를 맞추다 자원을 소진하지 않게 한다")
    void rejectsHugeExponentBeforeAdding() {
        assertBadRequest(new BigDecimal("1E+999999999"), new BigDecimal("1"), "범위");
        assertBadRequest(new BigDecimal("100"), new BigDecimal("0E+999999999"), "범위");
    }

    @Test
    @DisplayName("끝자리 0 을 걷어내면 scale 이 넘치는 입력도 예외가 아니라 400 이다 (100E+2147483647)")
    void rejectsExponentThatOverflowsWhenStripped() {
        assertBadRequest(new BigDecimal("100E+2147483647"), new BigDecimal("0"), "범위");
        assertBadRequest(new BigDecimal("0"), new BigDecimal("1000E+2147483647"), "범위");
    }

    @Test
    @DisplayName("값이 0 이고 소수 자리만 큰 입력은 0 으로 정규화한다 — 합계 계산에서 자원을 소진하거나 예외가 나지 않게 한다")
    void normalizesZeroWithHugeScale() {
        givenSixtyForty();

        HotspotWeights weights = resolver.resolve(new BigDecimal("100"), new BigDecimal("0E-999999999"));

        assertThat(weights.crdnWgt()).isEqualByComparingTo("0");
        assertThat(weights.weights().get(1).getStngVl().scale()).isZero();
    }

    @Test
    @DisplayName("지수 표기 입력은 응답 stngVl 에서 일반 숫자로 정규화한다 (1E+2 → 100)")
    void normalizesExponentNotation() {
        givenSixtyForty();

        HotspotWeights weights = resolver.resolve(new BigDecimal("1E+2"), new BigDecimal("0"));

        assertThat(weights.weights().get(0).getStngVl()).hasToString("100");
    }

    @Test
    @DisplayName("음수는 합계가 100 이어도 범위 검사에서 400 (-10/110)")
    void rejectsNegative() {
        givenSixtyForty();

        assertBadRequest(new BigDecimal("-10"), new BigDecimal("110"), "범위");
    }

    @Test
    @DisplayName("행의 minVl~maxVl 밖이면 400 — 범위는 상수가 아니라 설정 행에서 읽는다")
    void rejectsOutsideRowRange() {
        AnalysisWeightVO complaint = weight("COMPLAINT", "60", 1);
        complaint.setMinVl(new BigDecimal("10"));
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT))
                .willReturn(List.of(complaint, weight("ENFORCE", "40", 2)));

        assertBadRequest(new BigDecimal("5"), new BigDecimal("95"), "10~100");
    }

    @Test
    @DisplayName("ENFORCE 가 꺼져 있으면 입력을 받지 않고 400")
    void rejectsWhenEnforceIsOff() {
        // selectEffectiveWeights 가 use_yn='N' 인 ENFORCE 를 빼고 COMPLAINT 를 100 으로 돌려준 상태
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT))
                .willReturn(List.of(weight("COMPLAINT", "100.00", 1)));

        assertBadRequest(new BigDecimal("70"), new BigDecimal("30"), "모두 쓸 수 있을 때만");
    }

    @Test
    @DisplayName("쓸 수 있는 지표가 하나도 없으면 입력 경로는 400 — 409 는 설정값 경로의 계약이다")
    void rejectsWhenNoEffectiveWeights() {
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT)).willReturn(List.of());

        assertBadRequest(new BigDecimal("70"), new BigDecimal("30"), "모두 쓸 수 있을 때만");
    }

    @Test
    @DisplayName("조정 여부 — 입력 없음 N (resolve() · resolve(null, null))")
    void adjustmentIsNWithoutInput() {
        givenSixtyForty();
        assertThat(resolver.resolve().wgvlAjmtYn()).isEqualTo("N");

        givenSixtyForty();
        assertThat(resolver.resolve(null, null).wgvlAjmtYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("조정 여부 — 설정과 값이 같으면 스케일이 달라도 N (60.00 / 40 vs 60 / 40)")
    void adjustmentIsNWhenInputEqualsSettingsByValue() {
        givenSixtyForty();

        assertThat(resolver.resolve(new BigDecimal("60.00"), new BigDecimal("40")).wgvlAjmtYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("조정 여부 — 설정과 값이 다르면 Y (setStngVl 이 덮어쓰기 전에 비교한다)")
    void adjustmentIsYWhenInputDiffersFromSettings() {
        givenSixtyForty();

        assertThat(resolver.resolve(new BigDecimal("80"), new BigDecimal("20")).wgvlAjmtYn()).isEqualTo("Y");
    }

    private void givenSixtyForty() {
        given(analysisWeightService.selectEffectiveWeights(HOTSPOT))
                .willReturn(List.of(weight("COMPLAINT", "60", 1), weight("ENFORCE", "40", 2)));
    }

    private void assertBadRequest(BigDecimal cvlcptWgvl, BigDecimal crdnWgvl, String messagePart) {
        assertThatThrownBy(() -> resolver.resolve(cvlcptWgvl, crdnWgvl))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(messagePart)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * {@code use_yn} 은 CHAR(1) 의 'Y'/'N' 문자열이다 — boolean 이 아니다.
     *
     * <p>🔴 <b>{@code vrblSeCd} 를 반드시 채운다</b>. {@link HotspotWeightResolver}
     * 가 {@code 'WGVL'} 이 아닌 행을 점수 산식에서 건너뛰므로, 비워 두면 <b>전 케이스가 409
     * CONFLICT</b>("산식이 아는 변수가 없다")로 죽는다 — 구현 버그가 아니라 픽스처 누락이다.
     *
     * <p>🔴 <b>{@code minVl}·{@code maxVl} 도 채운다</b>. 입력 경로의 범위 검사가 읽으므로
     * 비워 두면 NPE 로 죽는다. 값은 현재 설정과 같은 0·100 이다.
     */
    private static AnalysisWeightVO weight(String vrblCd, String stngVl, int sortSeq) {
        AnalysisWeightVO vo = new AnalysisWeightVO();
        vo.setAnlsTypeCd(HOTSPOT);
        vo.setVrblCd(vrblCd);
        vo.setVrblNm(vrblCd);
        vo.setVrblSeCd("WGVL");
        vo.setStngVl(new BigDecimal(stngVl));
        vo.setBscStngVl(new BigDecimal(stngVl));
        vo.setMinVl(BigDecimal.ZERO);
        vo.setMaxVl(new BigDecimal("100"));
        vo.setUseYn("Y");
        vo.setSortSeq(sortSeq);
        return vo;
    }
}
