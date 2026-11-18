package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.analysis.service.InvestPrrtLotBaseVO;
import jeju.datahub.analysis.service.InvestPrrtLotVO;
import jeju.datahub.analysis.service.impl.InvestPrrtCalculator;
import jeju.datahub.analysis.service.impl.InvestPrrtCalculator.Result;

/**
 * {@link InvestPrrtCalculator} 단위 테스트 (TSK-4036 Step 5).
 *
 * <p>DB·스프링 컨텍스트를 띄우지 않는다 — 계산기가 순수 계산이라 {@code new} 로 만들어 부른다.
 * 입력은 전부 테스트가 직접 만든 합성 값이거나 분석 결과표 픽스처라, 기대값 리터럴이 정당하다
 * ("날짜·건수를 박지 않는다" 규칙은 DB 를 읽는 테스트의 것이다).
 *
 * <p>🔴 {@link BigDecimal} 단언은 {@code isEqualByComparingTo} 로 한다 — 종합점수는 스케일 9,
 * 지표 점수는 스케일 6 이라 {@code isEqualTo} 는 스케일 차이로 실패한다.
 */
class InvestPrrtCalculatorTest {

    private static final BigDecimal UP = new BigDecimal("80");
    private static final BigDecimal MD = new BigDecimal("60");

    private final InvestPrrtCalculator calculator = new InvestPrrtCalculator();

    // ------------------------------------------------------------------ ① ② 백분위

    @Test
    @DisplayName("① 0건이 많은 지표에서 0건은 0점이다 — 자기보다 작은 값이 없다")
    void zeroCountsScoreZero() {
        // 민원 0건이 4곳, 1건·7건이 각 1곳. 0건의 백분위는 '중간쯤' 이 아니라 0 이다.
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "A", 0, "1.0", 0), lot(2, "제주시", "A", 0, "1.0", 0),
                lot(3, "제주시", "A", 0, "1.0", 0), lot(4, "제주시", "A", 0, "1.0", 0),
                lot(5, "제주시", "A", 1, "1.0", 0), lot(6, "제주시", "A", 7, "1.0", 0));

        Map<Long, InvestPrrtLotVO> byId = byId(calculator.calculate(bases, weights("COMPLAINT", "100"), UP, MD));

        for (long id = 1; id <= 4; id++) {
            assertThat(byId.get(id).getCvlcptFrqScr()).isEqualByComparingTo("0");
            assertThat(byId.get(id).getSnthsScr()).isEqualByComparingTo("0");
        }
        // 1건: 자기보다 작은 곳 4 ÷ (6−1) × 100 = 80 / 7건: 5 ÷ 5 × 100 = 100
        assertThat(byId.get(5L).getCvlcptFrqScr()).isEqualByComparingTo("80");
        assertThat(byId.get(6L).getCvlcptFrqScr()).isEqualByComparingTo("100");
        // 전부 0건인 단속도 전부 0점이다(분자가 0)
        assertThat(byId.values()).allSatisfy(v -> assertThat(v.getCrdnScr()).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("② 동점은 같은 백분위를 받고, 순위는 pkltId 오름차순으로 갈린다")
    void tiesShareSamePercentileAndBreakByPkltId() {
        // 입력 순서를 일부러 섞는다 — 순위가 입력 순서에 기대면 안 된다.
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(30, "제주시", "A", 5, "1.0", 0), lot(10, "제주시", "A", 5, "1.0", 0),
                lot(20, "제주시", "A", 5, "1.0", 0), lot(40, "제주시", "A", 9, "1.0", 0),
                lot(50, "제주시", "A", 2, "1.0", 0));

        Result result = calculator.calculate(bases, weights("COMPLAINT", "100"), UP, MD);
        Map<Long, InvestPrrtLotVO> byId = byId(result);

        // 5건 셋: 자기보다 작은 곳 1(2건) ÷ 4 × 100 = 25 — 셋 다 같다
        assertThat(byId.get(10L).getCvlcptFrqScr()).isEqualByComparingTo("25");
        assertThat(byId.get(20L).getCvlcptFrqScr()).isEqualByComparingTo("25");
        assertThat(byId.get(30L).getCvlcptFrqScr()).isEqualByComparingTo("25");
        assertThat(byId.get(10L).getSnthsScr()).isEqualByComparingTo(byId.get(30L).getSnthsScr());

        assertThat(result.lots()).extracting(InvestPrrtLotVO::getPkltId)
                .containsExactly(40L, 10L, 20L, 30L, 50L);
        assertThat(result.lots()).extracting(InvestPrrtLotVO::getRnk).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("② 3분할 가중치에서도 수학적 동점은 9자리까지 같은 값이다 (float 덧셈 순서 전례 — DEC-009)")
    void mathematicalTiesStayEqual() {
        // 1번은 (민원 높음 · 이용 낮음), 2번은 (민원 낮음 · 이용 높음) — 가중치가 같으면 정확히 동점이다.
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "A", 9, "1.0", 3), lot(2, "제주시", "A", 1, "9.0", 3),
                lot(3, "제주시", "A", 5, "5.0", 3), lot(4, "제주시", "A", 0, "0.5", 3));
        Map<String, BigDecimal> w = weights("COMPLAINT", "33.33", "USAGE", "33.33", "ENFORCE", "33.34");

        Result result = calculator.calculate(bases, w, UP, MD);
        Map<Long, InvestPrrtLotVO> byId = byId(result);

        assertThat(byId.get(1L).getSnthsScr()).isEqualByComparingTo(byId.get(2L).getSnthsScr());
        assertThat(byId.get(1L).getSnthsScr().scale()).isEqualTo(9);
        assertThat(byId.get(1L).getRnk()).isLessThan(byId.get(2L).getRnk());
    }

    // ------------------------------------------------------------------ ③ ④ 가중치

    @Test
    @DisplayName("③ 가중치를 바꾸면 순위가 바뀐다")
    void weightChangeReordersRanking() {
        // 1번은 안전 불량(C) · 민원 최저, 2번은 안전 양호(A) · 민원 최고.
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "C", 0, "1.0", 0), lot(2, "제주시", "A", 9, "1.0", 0),
                lot(3, "제주시", "A", 4, "1.0", 0));

        Result safetyHeavy = calculator.calculate(bases, weights("SAFETY", "90", "COMPLAINT", "10"), UP, MD);
        Result complaintHeavy = calculator.calculate(bases, weights("SAFETY", "10", "COMPLAINT", "90"), UP, MD);

        assertThat(safetyHeavy.lots().get(0).getPkltId()).isEqualTo(1L);
        // (100×90 + 0×10) ÷ 100 = 90
        assertThat(safetyHeavy.lots().get(0).getSnthsScr()).isEqualByComparingTo("90");
        assertThat(complaintHeavy.lots().get(0).getPkltId()).isEqualTo(2L);
        // (0×10 + 100×90) ÷ 100 = 90
        assertThat(complaintHeavy.lots().get(0).getSnthsScr()).isEqualByComparingTo("90");
        assertThat(byId(complaintHeavy).get(1L).getRnk()).isEqualTo(3);
    }

    @Test
    @DisplayName("④ 가중치 0 · 목록에 없는 지표는 분자에도 분모에도 들어가지 않는다")
    void zeroOrAbsentWeightIsExcluded() {
        // 단속만 보면 3번이 1위다. 단속 가중치가 0 이면 그 사실이 순위에 영향을 주면 안 된다.
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "C", 9, "1.0", 0), lot(2, "제주시", "B", 4, "1.0", 0),
                lot(3, "제주시", "A", 0, "1.0", 99));

        Result zeroed = calculator.calculate(bases,
                weights("SAFETY", "50", "COMPLAINT", "50", "ENFORCE", "0"), UP, MD);
        Result absent = calculator.calculate(bases, weights("SAFETY", "50", "COMPLAINT", "50"), UP, MD);

        // 1번: (100×50 + 100×50) ÷ 100 = 100 — 분모가 150(단속 0 포함)이 아니라 100 이다
        assertThat(byId(zeroed).get(1L).getSnthsScr()).isEqualByComparingTo("100");
        assertThat(byId(zeroed).get(3L).getSnthsScr()).isEqualByComparingTo("0");
        assertThat(zeroed.lots()).extracting(InvestPrrtLotVO::getPkltId).containsExactly(1L, 2L, 3L);
        assertThat(zeroed.lots()).extracting(v -> v.getSnthsScr().toPlainString())
                .isEqualTo(absent.lots().stream().map(v -> v.getSnthsScr().toPlainString()).toList());
        // 산식이 모르는 변수코드는 값이 있어도 무시한다
        // (TSK-4296 — ACCIDENT 는 이제 아는 변수라 실존하지 않는 코드로 예시를 바꿨다)
        Result withUnknown = calculator.calculate(bases,
                weights("SAFETY", "50", "COMPLAINT", "50", "UNKNOWN_X", "40"), UP, MD);
        assertThat(byId(withUnknown).get(1L).getSnthsScr()).isEqualByComparingTo("100");
        // 반영하지 않은 지표의 점수 칸은 그래도 채운다(종합점수에만 안 들어간다)
        assertThat(byId(zeroed).get(3L).getCrdnScr()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("④ crdnExclRnk — 단속을 뺀 순위. 단속이 반영되지 않았으면 본 순위와 같고, 단속뿐이면 null")
    void crdnExclRnk() {
        // 3번은 단속 때문에 1위다. 단속을 빼면 꼴찌가 된다.
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "A", 9, "1.0", 0), lot(2, "제주시", "A", 4, "1.0", 0),
                lot(3, "제주시", "A", 0, "1.0", 99));

        Result withCrdn = calculator.calculate(bases, weights("COMPLAINT", "20", "ENFORCE", "80"), UP, MD);
        assertThat(withCrdn.lots()).extracting(InvestPrrtLotVO::getPkltId).containsExactly(3L, 1L, 2L);
        assertThat(byId(withCrdn).get(3L).getCrdnExclRnk()).isEqualTo(3);
        assertThat(byId(withCrdn).get(1L).getCrdnExclRnk()).isEqualTo(1);
        assertThat(byId(withCrdn).get(2L).getCrdnExclRnk()).isEqualTo(2);

        Result noCrdn = calculator.calculate(bases, weights("COMPLAINT", "100", "ENFORCE", "0"), UP, MD);
        assertThat(noCrdn.lots()).allSatisfy(v -> assertThat(v.getCrdnExclRnk()).isEqualTo(v.getRnk()));

        Result onlyCrdn = calculator.calculate(bases, weights("ENFORCE", "100"), UP, MD);
        assertThat(onlyCrdn.lots()).allSatisfy(v -> assertThat(v.getCrdnExclRnk()).isNull());
    }

    @Test
    @DisplayName("반영할 가중치가 하나도 없으면 조용히 0점을 만들지 않고 실패한다")
    void failsWithoutUsableWeights() {
        List<InvestPrrtLotBaseVO> bases = List.of(lot(1, "제주시", "A", 1, "1.0", 0));

        assertThatThrownBy(() -> calculator.calculate(bases, weights("SAFETY", "0", "UNKNOWN_X", "100"), UP, MD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(bases, Map.of(), UP, MD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 사고 지표 (TSK-4296)

    @Test
    @DisplayName("사고 ① ACCIDENT 가중치만 있으면 사고 건수의 백분위가 곧 종합점수다")
    void accidentOnlyWeight() {
        // 사고 0건 둘, 1건·3건 각 1곳. 다른 지표는 일부러 반대 순서로 둔다 — 반영되면 순위가 흔들린다.
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "C", 9, "9.0", 9, 0), lot(2, "제주시", "C", 9, "9.0", 9, 0),
                lot(3, "제주시", "A", 1, "1.0", 0, 1), lot(4, "제주시", "A", 0, "0.5", 0, 3));

        Result result = calculator.calculate(bases, weights("ACCIDENT", "100"), UP, MD);
        Map<Long, InvestPrrtLotVO> byId = byId(result);

        assertThat(byId.get(1L).getAcdntScr()).isEqualByComparingTo("0");
        assertThat(byId.get(2L).getAcdntScr()).isEqualByComparingTo("0");
        // 1건: 자기보다 작은 곳 2 ÷ (4−1) × 100 = 66.666667(6자리) / 3건: 3 ÷ 3 × 100 = 100
        assertThat(byId.get(3L).getAcdntScr()).isEqualByComparingTo("66.666667");
        assertThat(byId.get(4L).getAcdntScr()).isEqualByComparingTo("100");
        // 종합점수는 9자리 — 끝에서 한 번만 나눈다
        assertThat(byId.get(3L).getSnthsScr()).isEqualByComparingTo("66.666666667");
        assertThat(byId.get(4L).getSnthsScr()).isEqualByComparingTo("100");
        assertThat(byId.get(4L).getAcdntCnt()).isEqualTo(3L);
        assertThat(result.lots()).extracting(InvestPrrtLotVO::getPkltId).containsExactly(4L, 3L, 1L, 2L);
    }

    @Test
    @DisplayName("사고 ② 사고 건수가 없으면(null) 0 으로 계산하고 출력 건수도 0 이다")
    void missingAccidentCountIsZero() {
        // 사고 인자 없는 lot() 은 acdntCnt 를 null 로 둔다 — 매퍼 계약상 나오지 않지만 방어를 본다.
        InvestPrrtLotBaseVO noAcdnt = lot(1, "제주시", "A", 0, "1.0", 0);
        assertThat(noAcdnt.getAcdntCnt()).isNull();
        List<InvestPrrtLotBaseVO> bases = List.of(
                noAcdnt, lot(2, "제주시", "A", 0, "1.0", 0, 0), lot(3, "제주시", "A", 0, "1.0", 0, 2));

        Map<Long, InvestPrrtLotVO> byId = byId(calculator.calculate(bases, weights("ACCIDENT", "100"), UP, MD));

        assertThat(byId.get(1L).getAcdntCnt()).isEqualTo(0L);
        // null 은 0 과 같은 값이다 — 0건 주차장과 같은 백분위(0)
        assertThat(byId.get(1L).getAcdntScr()).isEqualByComparingTo("0");
        assertThat(byId.get(1L).getAcdntScr()).isEqualByComparingTo(byId.get(2L).getAcdntScr());
        // 2건: null·0 둘보다 크다 → 2 ÷ 2 × 100 = 100
        assertThat(byId.get(3L).getAcdntScr()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("사고 ③ 전 주차장 사고 0 이면 사고 점수가 전부 0 이고 종합점수 상한이 100 − w_A 다 (D1=A)")
    void allZeroAccidentsCapScore() {
        // 사고이력 표가 통째로 빈 상태. 1번은 나머지 세 지표가 모두 최고다 — 그래도 75 를 넘지 못한다.
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "C", 9, "9.0", 0, 0), lot(2, "제주시", "B", 5, "5.0", 0, 0),
                lot(3, "제주시", "A", 0, "0.5", 0, 0));
        Map<String, BigDecimal> w = weights("SAFETY", "25", "COMPLAINT", "25", "USAGE", "25", "ACCIDENT", "25");

        Result result = calculator.calculate(bases, w, UP, MD);

        assertThat(result.lots()).allSatisfy(v -> assertThat(v.getAcdntScr()).isEqualByComparingTo("0"));
        // (100×25 + 100×25 + 100×25 + 0×25) ÷ 100 = 75 — 사고 항을 빼고 재정규화하지 않는다
        assertThat(result.lots().get(0).getPkltId()).isEqualTo(1L);
        assertThat(result.lots().get(0).getSnthsScr()).isEqualByComparingTo("75");
        assertThat(result.lots()).allSatisfy(v -> assertThat(v.getSnthsScr()).isLessThanOrEqualTo(new BigDecimal("75")));
        // 상한 75 < 높음 임계 80 → 높음 0곳
        assertThat(result.lots()).noneMatch(v -> "H".equals(v.getPrrtLvlCd()));
        assertThat(result.lots().get(0).getPrrtLvlCd()).isEqualTo("M");
    }

    @Test
    @DisplayName("사고 ④ 5지표 분할 가중치에서도 수학적 동점은 9자리까지 같은 값이다")
    void fiveIndicatorTiesStayEqual() {
        // 1번은 (민원 높음 · 사고 낮음), 2번은 (민원 낮음 · 사고 높음) — 민원·사고 가중치가 같으면 정확히 동점이다.
        // 나머지 세 지표(안전·이용·단속)는 1·2번이 같은 값이다.
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "B", 9, "2.0", 3, 1), lot(2, "제주시", "B", 1, "2.0", 3, 9),
                lot(3, "제주시", "A", 5, "5.0", 1, 5), lot(4, "제주시", "C", 0, "0.5", 7, 0));
        Map<String, BigDecimal> w = weights(
                "SAFETY", "16.67", "COMPLAINT", "16.67", "USAGE", "33.33", "ENFORCE", "16.66", "ACCIDENT", "16.67");

        Result result = calculator.calculate(bases, w, UP, MD);
        Map<Long, InvestPrrtLotVO> byId = byId(result);

        assertThat(byId.get(1L).getSnthsScr()).isEqualByComparingTo(byId.get(2L).getSnthsScr());
        assertThat(byId.get(1L).getSnthsScr().scale()).isEqualTo(9);
        // 동점은 pkltId 오름차순
        assertThat(byId.get(1L).getRnk()).isLessThan(byId.get(2L).getRnk());
        // 같은 입력을 다시 계산해도 같은 값·같은 순위다(결정성)
        Result again = calculator.calculate(bases, w, UP, MD);
        assertThat(again.lots()).extracting(v -> v.getSnthsScr().toPlainString())
                .isEqualTo(result.lots().stream().map(v -> v.getSnthsScr().toPlainString()).toList());
        assertThat(again.lots()).extracting(InvestPrrtLotVO::getPkltId)
                .isEqualTo(result.lots().stream().map(InvestPrrtLotVO::getPkltId).toList());
    }

    // ------------------------------------------------------------------ ⑤ n = 1 · 빈 입력

    @Test
    @DisplayName("⑤ 대상이 1곳이면 백분위는 0 이다 (분모 n−1 = 0 으로 나누지 않는다)")
    void singleLot() {
        List<InvestPrrtLotBaseVO> bases = List.of(lot(7, "서귀포시", "C", 50, "3.0", 20));

        Result result = calculator.calculate(bases, equalWeights(), UP, MD);

        InvestPrrtLotVO only = result.lots().get(0);
        assertThat(only.getCvlcptFrqScr()).isEqualByComparingTo("0");
        assertThat(only.getUtztnPttnScr()).isEqualByComparingTo("0");
        assertThat(only.getCrdnScr()).isEqualByComparingTo("0");
        assertThat(only.getSftyRiskScr()).isEqualByComparingTo("100");
        // (100×25 + 0 + 0 + 0) ÷ 100 = 25
        assertThat(only.getSnthsScr()).isEqualByComparingTo("25");
        assertThat(only.getRnk()).isEqualTo(1);
        assertThat(only.getSggRnk()).isEqualTo(1);
        assertThat(only.getCrdnExclRnk()).isEqualTo(1);
        assertThat(only.getPrrtLvlCd()).isEqualTo("L");
    }

    @Test
    @DisplayName("대상이 0곳이면 빈 결과다")
    void emptyInput() {
        Result result = calculator.calculate(List.of(), equalWeights(), UP, MD);

        assertThat(result.lots()).isEmpty();
        assertThat(result.sftyChckMissingCnt()).isZero();
        assertThat(result.unknownSftyCds()).isEmpty();
        assertThat(result.utztnMissingCnt()).isZero();
        assertThat(calculator.calculate(null, equalWeights(), UP, MD).lots()).isEmpty();
    }

    // ------------------------------------------------------------------ ⑥ 등급 경계

    @Test
    @DisplayName("⑥ 등급 경계 — 정확히 80.0 은 H, 79.999999999 는 M, 정확히 60.0 은 M, 59.999999999 는 L")
    void gradeBoundaries() {
        assertThat(InvestPrrtCalculator.gradeOf(new BigDecimal("80.000000000"), UP, MD)).isEqualTo("H");
        assertThat(InvestPrrtCalculator.gradeOf(new BigDecimal("80.0"), UP, MD)).isEqualTo("H");
        assertThat(InvestPrrtCalculator.gradeOf(new BigDecimal("79.999999999"), UP, MD)).isEqualTo("M");
        assertThat(InvestPrrtCalculator.gradeOf(new BigDecimal("60.000000000"), UP, MD)).isEqualTo("M");
        assertThat(InvestPrrtCalculator.gradeOf(new BigDecimal("59.999999999"), UP, MD)).isEqualTo("L");
        assertThat(InvestPrrtCalculator.gradeOf(new BigDecimal("100"), UP, MD)).isEqualTo("H");
        assertThat(InvestPrrtCalculator.gradeOf(new BigDecimal("0.000000000"), UP, MD)).isEqualTo("L");
        // 임계의 스케일(NUMERIC(12,4) → 80.0000)이 달라도 같은 판정이다
        assertThat(InvestPrrtCalculator.gradeOf(new BigDecimal("80.000000000"),
                new BigDecimal("80.0000"), new BigDecimal("60.0000"))).isEqualTo("H");
    }

    @Test
    @DisplayName("⑥ 등급 경계를 계산 경로로도 본다 — 점수 80 · 60 · 50 이 H · M · L")
    void gradeBoundariesThroughCalculation() {
        // 안전 80 + 민원 20. 1번: C(100)·민원 최저 → 80.0 / 2번: B(50)·민원 중간(50) → 50.0
        // 3번: A(0)·민원 최고(100) → 20.0
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "C", 0, "1.0", 0), lot(2, "제주시", "B", 5, "1.0", 0),
                lot(3, "제주시", "A", 9, "1.0", 0));
        Map<String, BigDecimal> w = weights("SAFETY", "80", "COMPLAINT", "20");

        Map<Long, InvestPrrtLotVO> byId = byId(calculator.calculate(bases, w, UP, MD));
        assertThat(byId.get(1L).getSnthsScr()).isEqualByComparingTo("80");
        assertThat(byId.get(1L).getPrrtLvlCd()).isEqualTo("H");
        assertThat(byId.get(2L).getSnthsScr()).isEqualByComparingTo("50");
        assertThat(byId.get(2L).getPrrtLvlCd()).isEqualTo("L");

        // 임계를 점수 바로 위(0.000000001)로 올리면 한 등급 내려간다 — 비교가 9자리 값 그대로다.
        Map<Long, InvestPrrtLotVO> raised = byId(
                calculator.calculate(bases, w, new BigDecimal("80.000000001"), MD));
        assertThat(raised.get(1L).getPrrtLvlCd()).isEqualTo("M");

        // 중간 임계 정확히 50 → 2번이 M, 50.000000001 → L
        assertThat(byId(calculator.calculate(bases, w, UP, new BigDecimal("50"))).get(2L).getPrrtLvlCd())
                .isEqualTo("M");
        assertThat(byId(calculator.calculate(bases, w, UP, new BigDecimal("50.000000001"))).get(2L)
                .getPrrtLvlCd()).isEqualTo("L");
    }

    // ------------------------------------------------------------------ 빈 값 처리

    @Test
    @DisplayName("안전점검이 없으면(null) 0점으로 계산하고 그 수를 결과에 싣는다")
    void missingSafetyInspection() {
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", null, 0, "1.0", 0), lot(2, "제주시", "B", 0, "1.0", 0),
                lot(3, "제주시", null, 0, "1.0", 0));

        Result result = calculator.calculate(bases, weights("SAFETY", "100"), UP, MD);
        Map<Long, InvestPrrtLotVO> byId = byId(result);

        assertThat(result.sftyChckMissingCnt()).isEqualTo(2);
        assertThat(result.unknownSftyCds()).isEmpty();
        assertThat(byId.get(1L).getSftyRiskScr()).isEqualByComparingTo("0");
        assertThat(byId.get(1L).getSftyChckRsltCd()).isNull(); // 코드는 양호(A)로 채우지 않는다
        assertThat(byId.get(1L).getSnthsScr()).isEqualByComparingTo("0");
        assertThat(byId.get(1L).getRsnCd()).isIn("DEMAND", "NONE"); // 점검 없음은 '보강' 사유가 아니다
        assertThat(byId.get(2L).getSnthsScr()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("대응표에 없는 안전 코드는 0점으로 계산하되 코드별 건수를 결과에 싣는다")
    void unknownSafetyCode() {
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "D", 0, "1.0", 0), lot(2, "제주시", "D", 0, "1.0", 0),
                lot(3, "제주시", "Z", 0, "1.0", 0), lot(4, "제주시", "C", 0, "1.0", 0));

        Result result = calculator.calculate(bases, weights("SAFETY", "100"), UP, MD);

        assertThat(result.unknownSftyCds()).containsExactly(Map.entry("D", 2), Map.entry("Z", 1));
        assertThat(result.sftyChckMissingCnt()).isZero(); // '점검 없음' 과 섞어 세지 않는다
        assertThat(byId(result).get(1L).getSftyRiskScr()).isEqualByComparingTo("0");
        assertThat(byId(result).get(1L).getSftyChckRsltCd()).isEqualTo("D"); // 원문은 그대로 내린다
        assertThat(result.lots().get(0).getPkltId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("회전율이 없으면(null) 0 으로 보고 백분위를 매기고, 출력 원값은 null 로 둔다")
    void missingTurnover() {
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "A", 0, null, 0), lot(2, "제주시", "A", 0, "0.5", 0),
                lot(3, "제주시", "A", 0, "2.0", 0), lot(4, "제주시", "A", 0, null, 0));

        Result result = calculator.calculate(bases, weights("USAGE", "100"), UP, MD);
        Map<Long, InvestPrrtLotVO> byId = byId(result);

        assertThat(result.utztnMissingCnt()).isEqualTo(2);
        assertThat(byId.get(1L).getUtztnPttnScr()).isEqualByComparingTo("0");
        assertThat(byId.get(4L).getUtztnPttnScr()).isEqualByComparingTo("0");
        assertThat(byId.get(1L).getDayAvgTrnvrRt()).isNull(); // 0 으로 바꿔 내리지 않는다
        // 0.5 는 null 둘(=0)보다 크다: 2 ÷ 3 × 100 = 66.666667
        assertThat(byId.get(2L).getUtztnPttnScr()).isEqualByComparingTo("66.666667");
        assertThat(byId.get(3L).getUtztnPttnScr()).isEqualByComparingTo("100");
        assertThat(result.lots()).extracting(InvestPrrtLotVO::getPkltId).containsExactly(3L, 2L, 1L, 4L);
    }

    // ------------------------------------------------------------------ 보조 값

    @Test
    @DisplayName("sggRnk 는 같은 시군구 안에서 전체 순위 순으로 다시 센 등수다 (시군구가 없으면 null)")
    void sggRnk() {
        List<InvestPrrtLotBaseVO> bases = List.of(
                lot(1, "제주시", "A", 9, "1.0", 0), lot(2, "서귀포시", "A", 8, "1.0", 0),
                lot(3, "제주시", "A", 7, "1.0", 0), lot(4, null, "A", 6, "1.0", 0),
                lot(5, "서귀포시", "A", 5, "1.0", 0));

        Map<Long, InvestPrrtLotVO> byId = byId(calculator.calculate(bases, weights("COMPLAINT", "100"), UP, MD));

        assertThat(byId.get(1L).getSggRnk()).isEqualTo(1);
        assertThat(byId.get(3L).getSggRnk()).isEqualTo(2);
        assertThat(byId.get(2L).getSggRnk()).isEqualTo(1);
        assertThat(byId.get(5L).getSggRnk()).isEqualTo(2);
        assertThat(byId.get(4L).getSggRnk()).isNull();
        assertThat(byId.get(4L).getRnk()).isEqualTo(4);
    }

    @Test
    @DisplayName("rsnCd — 안전 B·C 는 SAFETY, 민원·이용 백분위 평균 상위 25%(ceil) 는 DEMAND, 둘 다면 BOTH")
    void rsnCd() {
        // 8곳 → 수요 상위 ceil(8 × 0.25) = 2곳. 민원·이용을 같은 순서로 줘 수요 순위가 id 역순이 되게 한다.
        List<InvestPrrtLotBaseVO> bases = new ArrayList<>();
        String[] sfty = {"A", "B", "A", "C", "A", "A", "B", "C"};
        for (int i = 0; i < 8; i++) {
            bases.add(lot(i + 1, "제주시", sfty[i], i, (i + 1) + ".0", 0));
        }

        // 사유는 가중치와 무관하다 — 단속만 반영해도 같은 사유가 나온다
        Map<Long, InvestPrrtLotVO> byId = byId(calculator.calculate(bases, weights("ENFORCE", "100"), UP, MD));

        assertThat(byId.get(8L).getRsnCd()).isEqualTo("BOTH");   // C + 수요 1위
        assertThat(byId.get(7L).getRsnCd()).isEqualTo("BOTH");   // B + 수요 2위
        assertThat(byId.get(6L).getRsnCd()).isEqualTo("NONE");   // A + 수요 3위(컷 밖)
        assertThat(byId.get(4L).getRsnCd()).isEqualTo("SAFETY"); // C + 수요 하위
        assertThat(byId.get(2L).getRsnCd()).isEqualTo("SAFETY");
        assertThat(byId.get(1L).getRsnCd()).isEqualTo("NONE");

        // 9곳이면 ceil(9 × 0.25) = 3곳. 수요 동점은 pkltId 오름차순으로 끊는다.
        List<InvestPrrtLotBaseVO> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(lot(i + 1, "제주시", "A", i < 5 ? 0 : 7, "1.0", 0)); // 6~9번이 수요 동점 1위
        }
        Map<Long, InvestPrrtLotVO> nineById = byId(calculator.calculate(nine, weights("ENFORCE", "100"), UP, MD));
        assertThat(nineById.get(6L).getRsnCd()).isEqualTo("DEMAND");
        assertThat(nineById.get(7L).getRsnCd()).isEqualTo("DEMAND");
        assertThat(nineById.get(8L).getRsnCd()).isEqualTo("DEMAND");
        assertThat(nineById.get(9L).getRsnCd()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("기본 정보는 입력에서 그대로 옮긴다")
    void copiesBaseFields() {
        InvestPrrtLotBaseVO base = lot(12869, "서귀포시", "C", 98, "4.7776", 173);
        base.setPkltNm("매일올레시장 공영주차빌딩");
        base.setPkltAddr("서귀동 277-1, 277-12");
        base.setEmdCd("50130520");
        base.setEmdNm("중앙동");
        base.setPkltCpcty(232);
        base.setInstlYm("200901");
        base.setLat(new BigDecimal("33.2501694366"));
        base.setLot(new BigDecimal("126.5634109154"));
        base.setSftyChckYmd("20260901");

        InvestPrrtLotVO vo = calculator.calculate(List.of(base), equalWeights(), UP, MD).lots().get(0);

        assertThat(vo.getPkltId()).isEqualTo(12869L);
        assertThat(vo.getPkltNm()).isEqualTo("매일올레시장 공영주차빌딩");
        assertThat(vo.getPkltAddr()).isEqualTo("서귀동 277-1, 277-12");
        assertThat(vo.getSggNm()).isEqualTo("서귀포시");
        assertThat(vo.getEmdCd()).isEqualTo("50130520");
        assertThat(vo.getEmdNm()).isEqualTo("중앙동");
        assertThat(vo.getPkltCpcty()).isEqualTo(232);
        assertThat(vo.getInstlYm()).isEqualTo("200901");
        assertThat(vo.getLat()).isEqualByComparingTo("33.2501694366");
        assertThat(vo.getLot()).isEqualByComparingTo("126.5634109154");
        assertThat(vo.getSftyChckRsltCd()).isEqualTo("C");
        assertThat(vo.getSftyChckYmd()).isEqualTo("20260901");
        assertThat(vo.getCvlcptCnt()).isEqualTo(98L);
        assertThat(vo.getDayAvgTrnvrRt()).isEqualByComparingTo("4.7776");
        assertThat(vo.getCrdnCnt()).isEqualTo(173L);
    }

    // ------------------------------------------------------------------ ⑦ 분석 결과표 재현

    private static final String FIXTURE = "/analysis/invest-prrt-result-369.csv";

    private static final Map<String, String> REASON_TO_RSN_CD = Map.of(
            "보강", "SAFETY", "수요", "DEMAND", "보강+수요", "BOTH", "해당없음", "NONE");

    @Test
    @DisplayName("⑦ 분석 결과표 재현 — 369곳의 점수(9자리)·순위·단속 제외 순위·시 안 등수·사유가 전부 일치")
    void reproducesAnalysisResult() throws IOException {
        List<Map<String, String>> rows = readCsv(FIXTURE);
        assertThat(rows).hasSize(369);

        // 입력으로 쓰는 것은 pklt_id · sgg_nm · sfty_h2_worst · raw_C · raw_U · raw_E 여섯 칸뿐이다.
        // ⚠️ raw_U 는 CSV 에서 소수 6자리로 반올림돼 있다 — 백분위는 순서만 쓰고, 6자리에서 같아지는
        //    쌍이 없어(아래 단언) 원값으로 계산한 순서와 같다.
        List<InvestPrrtLotBaseVO> bases = new ArrayList<>();
        for (Map<String, String> r : rows) {
            bases.add(lot(Long.parseLong(r.get("pklt_id")), r.get("sgg_nm"), r.get("sfty_h2_worst"),
                    Long.parseLong(r.get("raw_C")), r.get("raw_U"), Long.parseLong(r.get("raw_E"))));
        }
        assertThat(rows.stream().map(r -> new BigDecimal(r.get("raw_U")).stripTrailingZeros()).distinct().count())
                .as("raw_U 가 6자리 반올림으로 같아진 쌍이 없다").isEqualTo(369);

        Result result = calculator.calculate(bases, equalWeights(), UP, MD);
        Map<Long, InvestPrrtLotVO> byId = byId(result);

        List<String> scoreDiff = new ArrayList<>();
        List<String> rankDiff = new ArrayList<>();
        List<String> noCrdnDiff = new ArrayList<>();
        List<String> sggDiff = new ArrayList<>();
        List<String> reasonDiff = new ArrayList<>();
        List<String> idxDiff = new ArrayList<>();
        for (Map<String, String> r : rows) {
            String id = r.get("pklt_id");
            InvestPrrtLotVO vo = byId.get(Long.parseLong(id));
            if (vo.getSnthsScr().compareTo(new BigDecimal(r.get("score"))) != 0) {
                scoreDiff.add(id + ": " + vo.getSnthsScr() + " ≠ " + r.get("score"));
            }
            if (vo.getRnk() != Integer.parseInt(r.get("rank"))) {
                rankDiff.add(id + ": " + vo.getRnk() + " ≠ " + r.get("rank"));
            }
            if (vo.getCrdnExclRnk() != Integer.parseInt(r.get("rank_no_crdn"))) {
                noCrdnDiff.add(id + ": " + vo.getCrdnExclRnk() + " ≠ " + r.get("rank_no_crdn"));
            }
            if (vo.getSggRnk() != Integer.parseInt(r.get("rank_in_sgg"))) {
                sggDiff.add(id + ": " + vo.getSggRnk() + " ≠ " + r.get("rank_in_sgg"));
            }
            if (!REASON_TO_RSN_CD.get(r.get("reason")).equals(vo.getRsnCd())) {
                reasonDiff.add(id + ": " + vo.getRsnCd() + " ≠ " + r.get("reason"));
            }
            // 지표별 점수(CSV 의 n_S · n_C · n_U · n_E — 6자리)
            if (vo.getSftyRiskScr().compareTo(new BigDecimal(r.get("n_S"))) != 0
                    || vo.getCvlcptFrqScr().compareTo(new BigDecimal(r.get("n_C"))) != 0
                    || vo.getUtztnPttnScr().compareTo(new BigDecimal(r.get("n_U"))) != 0
                    || vo.getCrdnScr().compareTo(new BigDecimal(r.get("n_E"))) != 0) {
                idxDiff.add(id);
            }
        }
        assertThat(scoreDiff).as("score 불일치").isEmpty();
        assertThat(rankDiff).as("rank 불일치").isEmpty();
        assertThat(noCrdnDiff).as("rank_no_crdn 불일치").isEmpty();
        assertThat(sggDiff).as("rank_in_sgg 불일치").isEmpty();
        assertThat(reasonDiff).as("reason 불일치").isEmpty();
        assertThat(idxDiff).as("지표별 점수 불일치").isEmpty();

        // 계획서 0-4 의 스냅샷 표
        InvestPrrtLotVO first = result.lots().get(0);
        assertThat(first.getPkltId()).isEqualTo(12869L);
        assertThat(first.getSnthsScr()).isEqualByComparingTo("97.214673913");
        assertThat(first.getRsnCd()).isEqualTo("BOTH");
        assertThat(result.lots().get(19).getSnthsScr()).isEqualByComparingTo("58.967391304");
        assertThat(result.lots().get(20).getSnthsScr()).isEqualByComparingTo("58.831521739");
        Map<String, Long> byLvl = result.lots().stream()
                .collect(Collectors.groupingBy(InvestPrrtLotVO::getPrrtLvlCd, Collectors.counting()));
        assertThat(byLvl).containsEntry("H", 1L).containsEntry("M", 17L).containsEntry("L", 351L);
        assertThat(result.lots().subList(0, 20).stream().filter(v -> "서귀포시".equals(v.getSggNm())).count())
                .isEqualTo(12);
        assertThat(result.sftyChckMissingCnt()).isZero();
        assertThat(result.unknownSftyCds()).isEmpty();
        assertThat(result.utztnMissingCnt()).isZero();
        // 결과는 순위순이고 순위는 1..n 연속이다
        for (int i = 0; i < result.lots().size(); i++) {
            assertThat(result.lots().get(i).getRnk()).isEqualTo(i + 1);
        }
    }

    // ------------------------------------------------------------------ 픽스처

    private static Map<String, BigDecimal> equalWeights() {
        return weights("SAFETY", "25", "COMPLAINT", "25", "USAGE", "25", "ENFORCE", "25");
    }

    /** {@code weights("SAFETY", "25", "COMPLAINT", "25")} — 코드·값 쌍. */
    private static Map<String, BigDecimal> weights(String... pairs) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], new BigDecimal(pairs[i + 1]));
        }
        return map;
    }

    /** 지표 원값만 채운 대상 주차장 1곳. {@code trnvr} 가 {@code null} 이면 회전율 없음. */
    private static InvestPrrtLotBaseVO lot(long pkltId, String sggNm, String sftyCd,
                                           long cvlcptCnt, String trnvr, long crdnCnt) {
        InvestPrrtLotBaseVO vo = new InvestPrrtLotBaseVO();
        vo.setPkltId(pkltId);
        vo.setPkltNm("주차장" + pkltId);
        vo.setSggNm(sggNm);
        vo.setSftyChckRsltCd(sftyCd);
        vo.setCvlcptCnt(cvlcptCnt);
        vo.setDayAvgTrnvrRt(trnvr == null ? null : new BigDecimal(trnvr));
        vo.setUtztnMmCnt(trnvr == null ? 0 : 3);
        vo.setCrdnCnt(crdnCnt);
        return vo;
    }

    /** 사고 건수까지 채운 대상 주차장 1곳(TSK-4296). 사고 인자 없는 {@link #lot} 은 {@code acdntCnt} 를 {@code null} 로 둔다. */
    private static InvestPrrtLotBaseVO lot(long pkltId, String sggNm, String sftyCd,
                                           long cvlcptCnt, String trnvr, long crdnCnt, long acdntCnt) {
        InvestPrrtLotBaseVO vo = lot(pkltId, sggNm, sftyCd, cvlcptCnt, trnvr, crdnCnt);
        vo.setAcdntCnt(acdntCnt);
        return vo;
    }

    private static Map<Long, InvestPrrtLotVO> byId(Result result) {
        return result.lots().stream().collect(Collectors.toMap(InvestPrrtLotVO::getPkltId, Function.identity()));
    }

    /** 머리글 있는 CSV → 행마다 {@code 열 이름 → 값}. 큰따옴표로 감싼 칸(쉼표 포함 주소)을 처리한다. */
    private static List<Map<String, String>> readCsv(String resource) throws IOException {
        try (InputStream in = InvestPrrtCalculatorTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("픽스처 %s", resource).isNotNull();
            // 시큐어코딩 5.2(TSK-4336) — reader 도 try-with-resources 로 닫는다. in 의 null 단언을 먼저 하려고 안쪽 try 로 둔다.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                List<String> header = splitCsvLine(reader.readLine());
                List<Map<String, String>> rows = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    List<String> cells = splitCsvLine(line);
                    assertThat(cells).as("열 수: %s", line).hasSameSizeAs(header);
                    Map<String, String> row = new HashMap<>();
                    for (int i = 0; i < header.size(); i++) {
                        row.put(header.get(i), cells.get(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells;
    }
}
