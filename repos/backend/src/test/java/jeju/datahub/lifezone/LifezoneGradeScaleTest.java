package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneGradeScale;

/**
 * 구간 경계 산출({@link LifezoneGradeScale}) 단위테스트 — TSK-4088 D17.
 *
 * <p>🔴 <b>{@code @SpringBootTest} 를 쓰지 않는다</b> — 경계 산출은 DB·컨텍스트에 의존하지 않는
 * 순수 계산이다.
 *
 * <p>분류방법은 <b>두 가지</b>이며 기본은 자연분류({@link LifezoneClsfMthd#JENKS})다.
 *
 * <p>🔴 <b>{@link #eqOf} 로 부르는 케이스는 전부 등간격 규약</b>이다 — 기본값이 자연분류로 바뀌었으므로
 * 등간격을 보려면 방법을 <b>명시</b>해야 한다. 검증 대상:
 * <ol>
 *   <li>상한은 <b>최대값</b>이다 — 백분위(P95)를 쓰지 않는다. 꼬리를 잘라내는 개념 자체가 없다.
 *       <b>두 방법 공통</b>이다</li>
 *   <li>등간격의 눈금이 <b>더하기</b>다 — 경계 사이의 <b>차</b>가 폭({@code w = ceil(mx/5)})으로 일정하다.
 *       첫 칸만 하한이 {@code 0} 이 아니라 {@code 1} 이라 하나 짧다(0건 격자를 그리지 않으므로)</li>
 *   <li>단조 증가 보정 — 연속 경계가 같거나 역전되면 뒤엣것을 앞+1 로 올린다(두 방법 공통)</li>
 *   <li>빈 목록 — 예외 없이 {@code cap=0}, 경계 {@code [1,2,3,4,5]}
 *       ({@code width} 는 등간격 1 · 자연분류 0)</li>
 *   <li>🔴 자연분류는 <b>경계를 손계산과 대조</b>하고, 같은 데이터에서 <b>GVF 가 등간격보다 높은지</b>까지 본다</li>
 * </ol>
 *
 * <p>격자 4종 기대값은 <b>로컬 적재분 실측</b>(2026-08-31 · 전 기간 · 전 지역)이다.
 * {@link LifezoneAnalysisIntegrationTest} 케이스 21 이 같은 값을 DB 재계산으로 대조한다.
 */
class LifezoneGradeScaleTest {

    /** 등급 수. 폭 규약({@code w = ceil(cap / GRADE_CNT)})이 이 수에 걸려 있다. */
    private static final int GRADE_CNT = LifezoneGrade.values().length;

    /** 실측 격자 4종의 최대값 — 폭·경계 규약을 한꺼번에 훑는 케이스들이 공유한다. */
    private static final int[] REAL_MAXES = { 719, 386, 314, 247 };

    /**
     * 🔴 <b>등간격</b>으로 경계를 낸다. 기본값이 자연분류로 바뀌었으므로 등간격 규약을 보는 케이스는
     * 전부 이 헬퍼를 거친다 — {@code LifezoneGradeScale.of(values)} 를 그대로 쓰면 Jenks 가 돌아
     * "무엇을 검증하는 케이스인지" 가 소리 없이 바뀐다.
     */
    private static LifezoneGradeScale eqOf(List<Integer> values) {
        return LifezoneGradeScale.of(values, LifezoneClsfMthd.EQUAL_INTERVAL);
    }

    /** <b>자연분류(Jenks)</b>로 경계를 낸다 — 기본 방법이지만 대비를 위해 명시해 부른다. */
    private static LifezoneGradeScale jenksOf(List<Integer> values) {
        return LifezoneGradeScale.of(values, LifezoneClsfMthd.JENKS);
    }

    /** {@link LifezoneGrade} 선언 순서(심각→낮음)의 경계를 낮음→심각 오름차순으로 뽑는다 */
    private static List<Integer> ascMinVls(LifezoneGradeScale scale) {
        List<Integer> asc = new ArrayList<>();
        for (LifezoneGrade grade : LifezoneGrade.values()) {
            asc.add(scale.minVlOf(grade));
        }
        Collections.reverse(asc);
        return asc;
    }

    /** 격자 실측 한 줄을 통째로 단언한다 — 최대값 · 폭 · 경계 5개. <b>등간격</b> 기준이다. */
    private static void assertGrid(int mx, int width, Integer... asc) {
        LifezoneGradeScale scale = eqOf(List.of(mx));

        assertThat(scale.cap()).as("cap 은 최대값이다").isEqualTo(mx);
        assertThat(scale.width()).as("폭").isEqualTo(width);
        assertThat(ascMinVls(scale)).as("경계(낮음→심각)").containsExactly(asc);
    }

    @Nested
    @DisplayName("격자 4종 실측 재현 (전 기간 · 전 지역)")
    class RealGrids {

        @Test
        @DisplayName("1km — 최대 719 · 폭 144 · 경계 [1,144,288,432,576]")
        void grid1km() {
            assertGrid(719, 144, 1, 144, 288, 432, 576);
        }

        @Test
        @DisplayName("500m — 최대 386 · 폭 78 · 경계 [1,78,156,234,312]")
        void grid500m() {
            assertGrid(386, 78, 1, 78, 156, 234, 312);
        }

        @Test
        @DisplayName("250m — 최대 314 · 폭 63 · 경계 [1,63,126,189,252]")
        void grid250m() {
            assertGrid(314, 63, 1, 63, 126, 189, 252);
        }

        @Test
        @DisplayName("100m — 최대 247 · 폭 50 · 경계 [1,50,100,150,200]")
        void grid100m() {
            assertGrid(247, 50, 1, 50, 100, 150, 200);
        }

        /** 문서·계획서에 적힌 검산 예 — 나누어떨어지는 값이라 폭이 정확히 {@code mx/5} 다 */
        @Test
        @DisplayName("검산 예 — 최대 160 · 폭 32 · 경계 [1,32,64,96,128]")
        void workedExample() {
            assertGrid(160, 32, 1, 32, 64, 96, 128);
        }
    }

    @Nested
    @DisplayName("상한은 최대값이다 — 백분위를 쓰지 않는다")
    class CapIsMax {

        /**
         * 🔴 종전 P95 식과 <b>결과가 갈리는</b> 입력이다 — 596칸이 1건이고 한 칸만 386건이면
         * P95 는 {@code 1}, 최대값은 {@code 386} 이다. 상위 5% 를 이상치로 잘라내던 개념이
         * 사라졌음을 값으로 못박는다.
         */
        @Test
        @DisplayName("꼬리가 한 칸뿐이어도 그 값이 cap (P95 라면 1 이 잡힌다)")
        void tailIsNotTrimmed() {
            List<Integer> values = new ArrayList<>(Collections.nCopies(596, 1));
            values.add(386);

            LifezoneGradeScale scale = eqOf(values);

            assertThat(scale.cap()).isEqualTo(386);
            assertThat(scale.width()).isEqualTo(78);
            assertThat(ascMinVls(scale)).containsExactly(1, 78, 156, 234, 312);
        }

        /** 입력 순서와 무관하다 — 최대값만 보므로 정렬조차 필요 없다 */
        @Test
        @DisplayName("입력 순서가 달라도 같은 cap")
        void orderIndependent() {
            List<Integer> shuffled = new ArrayList<>(List.of(9, 386, 3, 1, 77, 12));
            Collections.shuffle(shuffled, new java.security.SecureRandom());   // 2.8 — 순서 무관 검사라 어떤 순서든 된다

            assertThat(eqOf(shuffled).cap()).isEqualTo(386);
        }

        /** 목록 안의 {@code null} 은 건너뛴다 — 매퍼가 빈 집계를 흘려도 터지지 않는다 */
        @Test
        @DisplayName("null 원소는 무시한다")
        void nullElementsIgnored() {
            List<Integer> values = new ArrayList<>();
            values.add(null);
            values.add(247);
            values.add(null);

            assertThat(eqOf(values).cap()).isEqualTo(247);
        }
    }

    @Nested
    @DisplayName("눈금이 더하기다 — 폭이 일정하다")
    class ConstantWidth {

        /**
         * 🔴 <b>폭의 정의</b> — {@code width = ceil(cap / 5)}. 정수식 {@code (mx + 4) / 5} 와 같다.
         */
        @Test
        @DisplayName("width 가 ceil(cap/5) 다 (격자 4종)")
        void widthIsCeilOfFifth() {
            for (int mx : REAL_MAXES) {
                LifezoneGradeScale scale = eqOf(List.of(mx));

                assertThat(scale.width())
                        .as("mx=%d", mx)
                        .isEqualTo((int) Math.ceil(mx / (double) GRADE_CNT));
            }
        }

        /**
         * 🔴 <b>경계 사이의 차가 전부 {@code width} 다</b> — 이게 등간격의 정의다.
         *
         * <p>단, <b>첫 칸만 하나 짧다</b>: 하한이 {@code 0} 이 아니라 {@code 1} 이라
         * ({@code 0건 격자를 그리지 않으므로}) {@code asc[1] - asc[0] = width - 1} 이다.
         * 두 번째 경계부터는 {@code width * i} 라 차가 정확히 {@code width} 로 일정하다.
         */
        @Test
        @DisplayName("경계 사이의 차가 전부 width 로 일정하다 (첫 칸만 하나 짧다)")
        void gapsAreConstant() {
            for (int mx : REAL_MAXES) {
                LifezoneGradeScale scale = eqOf(List.of(mx));
                List<Integer> asc = ascMinVls(scale);
                int width = scale.width();

                assertThat(asc.get(0)).as("mx=%d 최하한은 1 (0건 격자는 그리지 않는다)", mx).isEqualTo(1);
                assertThat(asc.get(1)).as("mx=%d 두 번째 경계는 width 자신", mx).isEqualTo(width);

                for (int i = 2; i < GRADE_CNT; i++) {
                    assertThat(asc.get(i) - asc.get(i - 1))
                            .as("mx=%d · %d번째 간격", mx, i)
                            .isEqualTo(width);
                }
            }
        }

        /**
         * 폭이 <b>올림</b>이라 {@code 5w >= cap} 이 보장된다 — 최대값이 최상위 칸에 들어간다.
         * 동시에 남는 여유는 5 미만이라 폭이 필요 이상 커지지 않는다.
         */
        @Test
        @DisplayName("5 * width 가 cap 이상이고 그 초과분은 5 미만")
        void widthCoversCapTightly() {
            for (int mx : REAL_MAXES) {
                LifezoneGradeScale scale = eqOf(List.of(mx));
                int span = scale.width() * GRADE_CNT;

                assertThat(span).as("mx=%d · 5w >= cap", mx).isGreaterThanOrEqualTo(mx);
                assertThat(span - mx).as("mx=%d · 여유는 5 미만", mx).isLessThan(GRADE_CNT);
            }
        }

        /**
         * 🔴 <b>올림이 최상위 칸을 비울 수 있다</b> — 최상위 하한은 {@code 4w} 인데 폭이 올림이라
         * {@code 4w > cap} 이 되는 구간이 있다. 전수 확인 결과 {@code mx ∈ {6, 7, 11}} 셋뿐이며
         * (예: {@code mx=6 → w=2 → [1,2,4,6,8]}, 최대값 6 이 SEVERE(8)에 못 닿아 WARN 이다),
         * 그 밖에서는 최대값이 언제나 SEVERE 다. 실측 격자 4종은 모두 후자다.
         *
         * <p>이 사실을 테스트로 남기는 이유 — "가장 붐비는 칸이 심각으로 안 나온다" 는 문의가
         * 버그가 아니라 <b>등간격 + 올림의 산술적 귀결</b>임을 값으로 못박는다.
         */
        @Test
        @DisplayName("최대값은 mx∈{6,7,11} 을 뺀 전 구간에서 SEVERE 다")
        void capLandsInTopBandExceptForCeilingOvershoot() {
            List<Integer> overshoot = List.of(6, 7, 11);

            for (int mx = GRADE_CNT + 1; mx <= 1000; mx++) {
                LifezoneGradeScale scale = eqOf(List.of(mx));
                LifezoneGrade grade = scale.gradeOf(mx);

                if (overshoot.contains(mx)) {
                    assertThat(grade).as("mx=%d 는 4w(%d) 에 못 닿는다", mx, scale.width() * 4)
                            .isEqualTo(LifezoneGrade.WARN);
                } else {
                    assertThat(grade).as("mx=%d 최대값의 등급", mx).isEqualTo(LifezoneGrade.SEVERE);
                }
            }
        }
    }

    @Nested
    @DisplayName("단조 증가 보정 — 좁은 조회")
    class MonotonicFix {

        /** 🔴 읍면동 1곳 실측(100m · 최대 3) — 폭 1 로 붙어버린 경계를 보정해 [1,2,3,4,5] 로 편다 */
        @Test
        @DisplayName("최대 3 → 폭 1 · [1,2,3,4,5]")
        void tinyMax() {
            LifezoneGradeScale scale = eqOf(List.of(1, 1, 2, 3));

            assertThat(scale.cap()).isEqualTo(3);
            assertThat(scale.width()).isEqualTo(1);
            assertThat(ascMinVls(scale)).containsExactly(1, 2, 3, 4, 5);
        }

        /** {@code mx=1} 이어도 폭의 하한은 1 이다 — 경계를 [1,2,3,4,5] 로 편다 */
        @Test
        @DisplayName("최대 1 → 폭 1 · [1,2,3,4,5]")
        void maxOne() {
            LifezoneGradeScale scale = eqOf(List.of(1, 1, 1));

            assertThat(scale.cap()).isEqualTo(1);
            assertThat(scale.width()).isEqualTo(1);
            assertThat(ascMinVls(scale)).containsExactly(1, 2, 3, 4, 5);
        }

        /**
         * 🔴 보정이 발동하는 <b>마지막</b> 최대값 — {@code mx=5} 까지는 폭이 1 이라 경계가 겹친다.
         * {@code mx=6} 이면 폭이 2 로 올라 보정 없이 5칸이 선다.
         */
        @Test
        @DisplayName("최대 5 → 보정 발동 [1,2,3,4,5] · 최대 6 → 보정 없이 [1,2,4,6,8]")
        void fixBoundary() {
            assertGrid(5, 1, 1, 2, 3, 4, 5);
            assertGrid(6, 2, 1, 2, 4, 6, 8);
        }

        /** 어떤 입력에서도 경계 5개는 서로 다르고 오름차순이다 (범례 1~0 이 나오지 않는다) */
        @Test
        @DisplayName("최대 0~1000 전 구간에서 경계가 항상 순증가")
        void alwaysStrictlyIncreasing() {
            for (int mx = 0; mx <= 1000; mx++) {
                List<Integer> asc = ascMinVls(eqOf(List.of(mx)));
                assertThat(asc.get(0)).as("mx=%d 최하한", mx).isEqualTo(1);
                for (int i = 1; i < asc.size(); i++) {
                    assertThat(asc.get(i)).as("mx=%d 경계 %d", mx, i).isGreaterThan(asc.get(i - 1));
                }
            }
        }

        /** 보정은 폭 1(=최대값이 등급 수 이하)에서만 발동한다 — 그 밖에서는 {@code w*i} 그대로다 */
        @Test
        @DisplayName("폭이 2 이상이면 보정이 발동하지 않는다 (경계 = w*i)")
        void noFixWhenWidthIsAtLeastTwo() {
            for (int mx = GRADE_CNT + 1; mx <= 1000; mx++) {
                LifezoneGradeScale scale = eqOf(List.of(mx));
                List<Integer> asc = ascMinVls(scale);

                assertThat(scale.width()).as("mx=%d 폭", mx).isGreaterThanOrEqualTo(2);
                for (int i = 1; i < GRADE_CNT; i++) {
                    assertThat(asc.get(i))
                            .as("mx=%d 경계 %d 는 w*%d", mx, i, i)
                            .isEqualTo(scale.width() * i);
                }
            }
        }
    }

    @Nested
    @DisplayName("빈 목록 — 결과 0건 (D11)")
    class EmptyValues {

        @Test
        @DisplayName("빈 목록이면 예외 없이 cap=0 · width=1 · [1,2,3,4,5]")
        void emptyList() {
            LifezoneGradeScale scale = eqOf(List.of());

            assertThat(scale.cap()).isZero();
            assertThat(scale.width()).isEqualTo(1);
            assertThat(ascMinVls(scale)).containsExactly(1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("null 목록도 빈 목록과 같다")
        void nullList() {
            LifezoneGradeScale scale = eqOf(null);

            assertThat(scale.cap()).isZero();
            assertThat(scale.width()).isEqualTo(1);
            assertThat(ascMinVls(scale)).containsExactly(1, 2, 3, 4, 5);
        }
    }

    @Nested
    @DisplayName("gradeOf — 값 → 등급")
    class GradeOf {

        /** 500m 실측 경계 [1,78,156,234,312] — 각 경계값과 그 -1 이 올바른 등급이어야 한다 */
        @Test
        @DisplayName("각 경계값과 경계-1 이 올바른 등급")
        void boundaries() {
            LifezoneGradeScale scale = eqOf(List.of(386));

            assertThat(scale.gradeOf(312)).isEqualTo(LifezoneGrade.SEVERE);
            assertThat(scale.gradeOf(311)).isEqualTo(LifezoneGrade.WARN);
            assertThat(scale.gradeOf(234)).isEqualTo(LifezoneGrade.WARN);
            assertThat(scale.gradeOf(233)).isEqualTo(LifezoneGrade.CAUTION);
            assertThat(scale.gradeOf(156)).isEqualTo(LifezoneGrade.CAUTION);
            assertThat(scale.gradeOf(155)).isEqualTo(LifezoneGrade.NORMAL);
            assertThat(scale.gradeOf(78)).isEqualTo(LifezoneGrade.NORMAL);
            assertThat(scale.gradeOf(77)).isEqualTo(LifezoneGrade.LOW);
            assertThat(scale.gradeOf(1)).isEqualTo(LifezoneGrade.LOW);
        }

        /**
         * 🔴 <b>최대값이 곧 최상위 등급의 시작점은 아니다</b> — cap(386)은 최상위 하한(312)보다
         * 크다. 상한이 없으므로 cap 자신도, cap 을 넘는 값도 SEVERE 다.
         */
        @Test
        @DisplayName("cap 자신과 cap 을 넘는 값 모두 SEVERE")
        void capAndAbove() {
            LifezoneGradeScale scale = eqOf(List.of(386));

            assertThat(scale.gradeOf(386)).isEqualTo(LifezoneGrade.SEVERE);
            assertThat(scale.gradeOf(719)).isEqualTo(LifezoneGrade.SEVERE);
        }

        /** 🔴 값이 0 이하면 null — 호출부가 그 셀을 버린다 (D5) */
        @Test
        @DisplayName("0 이하는 null")
        void nonPositiveIsNull() {
            LifezoneGradeScale scale = eqOf(List.of(386));

            assertThat(scale.gradeOf(0)).isNull();
            assertThat(scale.gradeOf(-1)).isNull();
        }
    }

    @Nested
    @DisplayName("응답 투영")
    class Projection {

        /** 응답 gradeScale 은 {method, cap, width} 3키다 — minVls 는 직렬화되지 않는다 */
        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("직렬화 키가 method·cap·width 3개")
        void serializedKeys() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(eqOf(List.of(386)));
            Map<String, Object> map = mapper.readValue(json, Map.class);

            assertThat(map).containsOnlyKeys("method", "cap", "width");
            assertThat(map.get("method")).isEqualTo("EQUAL_INTERVAL");
            assertThat(map.get("cap")).isEqualTo(386);
            assertThat(map.get("width")).isEqualTo(78);
        }

        /** 범례 문구까지 서버가 만든다 — 500m 실측 5행 (D5) */
        @Test
        @DisplayName("gradeCriteria 5종 · lgndTxt 문구 (500m)")
        void gradeCriteria() {
            List<LifezoneGradeCriterionVO> criteria =
                    LifezoneGradeCriterionVO.allOf(eqOf(List.of(386)), "건");

            assertThat(criteria).hasSize(5);
            assertThat(criteria).extracting(LifezoneGradeCriterionVO::getGrade)
                    .containsExactly("SEVERE", "WARN", "CAUTION", "NORMAL", "LOW");
            assertThat(criteria).extracting(LifezoneGradeCriterionVO::getMinVl)
                    .containsExactly(312, 234, 156, 78, 1);
            assertThat(criteria).extracting(LifezoneGradeCriterionVO::getLgndTxt)
                    .containsExactly("심각 (312건 이상)", "경고 (234~311건)", "주의 (156~233건)",
                            "보통 (78~155건)", "낮음 (1~77건)");
        }

        /** 격자 4종 범례 문구 — 계획서·문서의 실측표와 같은 값이다 */
        @Test
        @DisplayName("격자 4종 범례 문구")
        void gradeCriteriaPerGrid() {
            assertThat(lgndTxts(719)).containsExactly(
                    "심각 (576건 이상)", "경고 (432~575건)", "주의 (288~431건)",
                    "보통 (144~287건)", "낮음 (1~143건)");
            assertThat(lgndTxts(314)).containsExactly(
                    "심각 (252건 이상)", "경고 (189~251건)", "주의 (126~188건)",
                    "보통 (63~125건)", "낮음 (1~62건)");
            assertThat(lgndTxts(247)).containsExactly(
                    "심각 (200건 이상)", "경고 (150~199건)", "주의 (100~149건)",
                    "보통 (50~99건)", "낮음 (1~49건)");
        }

        /** 결과 0건이어도 범례 5행이 나온다 — 프론트 범례가 응답으로만 조립되기 때문 (D11) */
        @Test
        @DisplayName("0건이어도 범례 5행")
        void gradeCriteriaWhenEmpty() {
            List<LifezoneGradeCriterionVO> criteria =
                    LifezoneGradeCriterionVO.allOf(eqOf(List.of()), "건");

            assertThat(criteria).hasSize(5);
            assertThat(criteria).extracting(LifezoneGradeCriterionVO::getMinVl)
                    .containsExactly(5, 4, 3, 2, 1);
            assertThat(criteria.get(0).getLgndTxt()).isEqualTo("심각 (5건 이상)");
            assertThat(criteria.get(4).getLgndTxt()).isEqualTo("낮음 (1~1건)");
        }

        private static List<String> lgndTxts(int mx) {
            List<String> txts = new ArrayList<>();
            for (LifezoneGradeCriterionVO vo
                    : LifezoneGradeCriterionVO.allOf(eqOf(List.of(mx)), "건")) {
                txts.add(vo.getLgndTxt());
            }
            return txts;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 자연분류 (Jenks) — 기본 방법
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 격자 4종의 <b>실측 도수분포</b>(2026-08-31 · 전 기간 {@code 20260804~20260930} · 전 지역).
     *
     * <p>{@code 값x빈도} 를 공백으로 이은 문자열이다 — 셀 값을 전부 적으면 2,015줄이지만
     * <b>고유값이 56~79개</b>뿐이라 도수로 적으면 한 화면에 들어간다. Jenks 도 같은 압축으로 돈다.
     *
     * <p>🔴 <b>왜 값을 박아 두나</b> — 경계 손계산과의 대조가 이 테스트의 핵심이라 입력이 고정돼야 한다.
     * 시드가 재적재되면 이 값이 아니라 {@code LifezoneAnalysisIntegrationTest} 쪽이 DB 재계산으로
     * 현재 적재분을 본다. 두 테스트의 역할이 다르다.
     */
    private static final String FREQ_1KM =
            "1x95 2x49 3x30 4x17 5x10 6x13 7x6 8x9 9x4 10x7 11x5 12x3 13x5 14x4 15x4 16x3 17x6 20x4 21x1 "
            + "22x1 23x2 24x1 25x2 26x2 27x3 28x2 29x2 30x1 31x2 32x1 33x1 36x2 37x1 40x1 41x1 42x1 43x1 "
            + "44x2 46x1 47x1 49x1 50x1 51x1 53x1 54x1 57x1 58x1 62x1 64x1 77x1 81x1 83x1 87x1 102x1 107x1 "
            + "110x1 115x2 123x1 171x1 176x1 200x1 224x1 226x1 239x1 247x1 259x1 263x1 266x2 277x1 285x1 "
            + "551x1 647x1 719x1";

    private static final String FREQ_500M =
            "1x181 2x89 3x53 4x32 5x19 6x13 7x15 8x21 9x12 10x8 11x11 12x7 13x6 14x5 15x9 16x6 17x6 18x5 "
            + "19x3 20x1 21x3 22x1 23x1 24x5 25x3 26x4 27x1 28x4 30x2 31x1 32x3 33x2 34x3 35x1 36x1 37x2 "
            + "39x2 41x2 42x3 43x4 44x3 46x2 48x2 49x1 50x1 51x2 52x1 56x1 57x1 60x1 63x1 67x1 70x1 72x2 "
            + "78x2 79x1 80x1 81x1 85x2 89x1 94x1 102x1 104x1 112x1 118x1 120x1 121x1 142x1 155x1 159x1 "
            + "160x1 165x1 166x1 194x1 215x1 225x1 247x1 371x1 386x1";

    private static final String FREQ_250M =
            "1x385 2x156 3x96 4x64 5x46 6x42 7x31 8x30 9x18 10x23 11x16 12x10 13x8 14x9 15x7 16x7 17x11 "
            + "18x13 19x5 20x7 21x8 22x3 23x6 24x5 25x2 26x4 27x2 28x3 29x2 30x5 31x2 32x3 33x4 34x1 35x4 "
            + "36x3 37x1 38x1 39x2 40x1 41x2 44x2 47x3 49x1 50x1 52x2 55x2 56x1 58x1 60x1 69x2 78x1 80x1 "
            + "86x1 88x1 95x1 96x1 102x1 109x1 123x1 132x1 146x1 150x1 228x1 247x1 314x1";

    private static final String FREQ_100M =
            "1x975 2x351 3x185 4x100 5x65 6x54 7x39 8x32 9x30 10x27 11x16 12x16 13x11 14x16 15x7 16x6 "
            + "17x3 18x10 19x4 20x7 21x3 23x5 24x5 25x4 26x1 27x1 28x2 29x2 30x2 31x2 32x1 33x1 34x1 36x3 "
            + "38x3 40x1 43x2 44x2 45x1 48x1 50x2 52x2 53x1 54x1 60x1 66x1 70x1 79x1 82x1 85x1 91x1 98x1 "
            + "112x1 183x1 206x1 247x1";

    /** {@code 값x빈도} 문자열을 값 목록으로 편다. */
    private static List<Integer> expand(String freq) {
        List<Integer> values = new ArrayList<>();
        for (String token : freq.split(" ")) {
            int at = token.indexOf('x');
            int value = Integer.parseInt(token.substring(0, at));
            int cnt = Integer.parseInt(token.substring(at + 1));
            for (int i = 0; i < cnt; i++) {
                values.add(value);
            }
        }
        return values;
    }

    /** 경계로 나눈 등급별 셀 수(낮음→심각). 손계산 분포와 대조한다. */
    private static List<Integer> distribution(List<Integer> values, LifezoneGradeScale scale) {
        int[] counts = new int[GRADE_CNT];
        for (int value : values) {
            LifezoneGrade grade = scale.gradeOf(value);
            counts[GRADE_CNT - 1 - grade.ordinal()]++;   // 낮음이 0번
        }
        List<Integer> out = new ArrayList<>();
        for (int c : counts) {
            out.add(c);
        }
        return out;
    }

    /**
     * GVF(Goodness of Variance Fit) = {@code (SDAM - SDCM) / SDAM}. 1 에 가까울수록 구간이
     * 분포를 잘 설명한다 — 자연분류가 최소화하는 목적함수 그 자체다.
     */
    private static double gvf(List<Integer> values, LifezoneGradeScale scale) {
        double mean = 0;
        for (int v : values) {
            mean += v;
        }
        mean /= values.size();

        double sdam = 0;
        for (int v : values) {
            sdam += (v - mean) * (v - mean);
        }

        Map<LifezoneGrade, List<Integer>> groups = new java.util.EnumMap<>(LifezoneGrade.class);
        for (int v : values) {
            groups.computeIfAbsent(scale.gradeOf(v), g -> new ArrayList<>()).add(v);
        }
        double sdcm = 0;
        for (List<Integer> group : groups.values()) {
            double m = 0;
            for (int v : group) {
                m += v;
            }
            m /= group.size();
            for (int v : group) {
                sdcm += (v - m) * (v - m);
            }
        }
        return (sdam - sdcm) / sdam;
    }

    @Nested
    @DisplayName("자연분류(Jenks) — 격자 4종 실측 재현")
    class JenksRealGrids {

        /**
         * 격자 한 줄을 통째로 단언한다 — 경계 5개 · 등급별 셀 분포 · {@code cap} · {@code width=0}.
         *
         * <p>🔴 <b>기대값은 손계산</b>(같은 도수분포에 Fisher-Jenks DP 를 독립 구현해 얻은 값)이다.
         * 구현이 아니라 <b>알고리즘</b>이 옳은지를 본다.
         */
        private void assertJenks(String freq, int cap, List<Integer> bounds, List<Integer> dist) {
            List<Integer> values = expand(freq);
            LifezoneGradeScale scale = jenksOf(values);

            assertThat(scale.cap()).as("cap 은 두 방법 모두 최대값이다").isEqualTo(cap);
            assertThat(scale.width()).as("자연분류는 폭이 일정하지 않아 width 가 0 이다").isZero();
            assertThat(ascMinVls(scale)).as("경계(낮음→심각)").isEqualTo(bounds);
            assertThat(distribution(values, scale)).as("등급별 셀 수(낮음→심각)").isEqualTo(dist);
        }

        @Test
        @DisplayName("1km — 경계 [1,21,77,200,551] · 셀 274/41/12/11/3")
        void grid1km() {
            assertJenks(FREQ_1KM, 719, List.of(1, 21, 77, 200, 551), List.of(274, 41, 12, 11, 3));
        }

        @Test
        @DisplayName("500m — 경계 [1,20,63,142,371] · 셀 501/64/20/10/2")
        void grid500m() {
            assertJenks(FREQ_500M, 386, List.of(1, 20, 63, 142, 371), List.of(501, 64, 20, 10, 2));
        }

        @Test
        @DisplayName("250m — 경계 [1,10,29,78,228] · 셀 868/149/47/12/3")
        void grid250m() {
            assertJenks(FREQ_250M, 314, List.of(1, 10, 29, 78, 228), List.of(868, 149, 47, 12, 3));
        }

        @Test
        @DisplayName("100m — 경계 [1,8,27,66,183] · 셀 1769/203/32/8/3")
        void grid100m() {
            assertJenks(FREQ_100M, 247, List.of(1, 8, 27, 66, 183), List.of(1769, 203, 32, 8, 3));
        }
    }

    @Nested
    @DisplayName("자연분류가 등간격보다 분포를 잘 설명한다 (GVF)")
    class JenksBeatsEqualInterval {

        /**
         * 🔴 <b>이 프로젝트가 기본을 바꾼 근거</b>다. 같은 데이터에서 자연분류의 GVF 가 등간격보다
         * 항상 높아야 한다 — 낮아지면 구현이 Jenks 가 아니다.
         *
         * <p>실측(2026-08-31): 자연분류 {@code 0.977/0.956/0.942/0.943} ↔
         * 등간격 {@code 0.922/0.865/0.787/0.764}.
         */
        @Test
        @DisplayName("격자 4종 모두 Jenks GVF > 등간격 GVF · Jenks 는 0.94 이상")
        void jenksHasHigherGvf() {
            for (String freq : List.of(FREQ_1KM, FREQ_500M, FREQ_250M, FREQ_100M)) {
                List<Integer> values = expand(freq);
                double jenksGvf = gvf(values, jenksOf(values));
                double equalGvf = gvf(values, eqOf(values));

                assertThat(jenksGvf)
                        .as("Jenks(%.4f) 가 등간격(%.4f) 보다 높아야 한다", jenksGvf, equalGvf)
                        .isGreaterThan(equalGvf);
                assertThat(jenksGvf).as("Jenks GVF").isGreaterThan(0.94);
            }
        }

        /**
         * 🔴 <b>등간격이 100m 에서 단색이 되는 것</b>을 값으로 남긴다 — 2,015칸 중 1,997칸(99%)이
         * 최하위다. 자연분석은 같은 데이터에서 최하위가 1,769칸(88%)이고 나머지 네 칸에 246칸이 든다.
         */
        @Test
        @DisplayName("100m — 등간격은 99% 가 최하위, 자연분류는 88%")
        void equalIntervalCollapsesAt100m() {
            List<Integer> values = expand(FREQ_100M);

            assertThat(distribution(values, eqOf(values))).containsExactly(1997, 14, 1, 1, 2);
            assertThat(distribution(values, jenksOf(values))).containsExactly(1769, 203, 32, 8, 3);
        }
    }

    @Nested
    @DisplayName("자연분류 — 경계 조건")
    class JenksEdgeCases {

        /** 🔴 기본 방법이 자연분류다 — {@code of(values)} 한 인자 호출이 {@code JENKS} 와 같아야 한다 */
        @Test
        @DisplayName("of(values) 는 JENKS 로 위임한다")
        void defaultMethodIsJenks() {
            List<Integer> values = expand(FREQ_500M);
            LifezoneGradeScale byDefault = LifezoneGradeScale.of(values);

            assertThat(byDefault.method()).isEqualTo("JENKS");
            assertThat(ascMinVls(byDefault)).isEqualTo(ascMinVls(jenksOf(values)));
            assertThat(LifezoneGradeScale.of(values, null).method())
                    .as("null 도 기본값으로 취급한다")
                    .isEqualTo("JENKS");
        }

        /** 빈 목록·null — 예외 없이 {@code cap=0 · width=0}, 경계 {@code [1,2,3,4,5]} (D11) */
        @Test
        @DisplayName("빈 목록·null 이면 cap=0 · width=0 · [1,2,3,4,5]")
        void emptyValues() {
            for (LifezoneGradeScale scale : List.of(jenksOf(List.of()), jenksOf(null))) {
                assertThat(scale.cap()).isZero();
                assertThat(scale.width()).isZero();
                assertThat(ascMinVls(scale)).containsExactly(1, 2, 3, 4, 5);
            }
        }

        /**
         * 🔴 <b>고유값이 등급 수보다 적으면 구간을 다 채울 수 없다</b> — 남는 칸은 단조 보정이
         * {@code 앞 + 1} 로 민다. 등간격의 좁은 조회와 같은 처리다(범례가 {@code 1~0} 으로 깨지지 않게).
         */
        @Test
        @DisplayName("고유값이 3개뿐이어도 경계 5개가 서로 다르고 오름차순")
        void fewerUniqueValuesThanGrades() {
            LifezoneGradeScale scale = jenksOf(List.of(1, 1, 1, 2, 2, 3));

            assertThat(scale.cap()).isEqualTo(3);
            assertThat(ascMinVls(scale)).containsExactly(1, 2, 3, 4, 5);
        }

        /** 고유값 1개 — 모든 셀이 같은 값이어도 5칸이 선다 */
        @Test
        @DisplayName("고유값 1개 → [1,2,3,4,5]")
        void singleUniqueValue() {
            LifezoneGradeScale scale = jenksOf(List.of(7, 7, 7, 7));

            assertThat(scale.cap()).isEqualTo(7);
            assertThat(ascMinVls(scale)).containsExactly(1, 2, 3, 4, 5);
        }

        /** {@code null} 원소·0 이하 값은 무시한다 — 매퍼가 빈 집계를 흘려도 터지지 않는다 */
        @Test
        @DisplayName("null 원소와 0 이하 값을 무시한다")
        void ignoresNullAndNonPositive() {
            List<Integer> values = new ArrayList<>(expand(FREQ_100M));
            values.add(null);
            values.add(0);
            values.add(-3);

            LifezoneGradeScale scale = jenksOf(values);

            assertThat(scale.cap()).isEqualTo(247);
            assertThat(ascMinVls(scale)).containsExactly(1, 8, 27, 66, 183);
        }

        /** 입력 순서와 무관하다 — 도수분포만 보므로 정렬해 들어오지 않아도 같은 경계다 */
        @Test
        @DisplayName("입력 순서가 달라도 같은 경계")
        void orderIndependent() {
            List<Integer> shuffled = new ArrayList<>(expand(FREQ_250M));
            Collections.shuffle(shuffled, new java.security.SecureRandom());   // 2.8 — 순서 무관 검사라 어떤 순서든 된다

            assertThat(ascMinVls(jenksOf(shuffled))).containsExactly(1, 10, 29, 78, 228);
        }

        /** 어떤 입력에서도 경계 5개는 서로 다르고 오름차순이다 (범례 {@code 1~0} 이 나오지 않는다) */
        @Test
        @DisplayName("최대 0~300 전 구간에서 경계가 항상 순증가")
        void alwaysStrictlyIncreasing() {
            for (int mx = 0; mx <= 300; mx++) {
                List<Integer> values = new ArrayList<>();
                for (int v = 1; v <= mx; v++) {
                    values.add(v);
                }
                List<Integer> asc = ascMinVls(jenksOf(values));

                assertThat(asc.get(0)).as("mx=%d 최하한", mx).isEqualTo(1);
                for (int i = 1; i < asc.size(); i++) {
                    assertThat(asc.get(i)).as("mx=%d 경계 %d", mx, i).isGreaterThan(asc.get(i - 1));
                }
            }
        }

        /**
         * 응답 계약은 <b>{@code {method, cap, width}} 3키 그대로</b>다 — 방법이 늘어도 키는 늘지 않는다.
         * {@code width} 는 자연분류에서 0 이다(간격이 일정하지 않다).
         */
        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("직렬화 키가 method·cap·width 3개이고 method=JENKS · width=0")
        void serializedKeys() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(jenksOf(expand(FREQ_500M)));
            Map<String, Object> map = mapper.readValue(json, Map.class);

            assertThat(map).containsOnlyKeys("method", "cap", "width");
            assertThat(map.get("method")).isEqualTo("JENKS");
            assertThat(map.get("cap")).isEqualTo(386);
            assertThat(map.get("width")).isEqualTo(0);
        }

        /** 범례 문구도 같은 경로로 만들어진다 — 500m 자연분류 5행 */
        @Test
        @DisplayName("gradeCriteria 범례 문구 (500m 자연분류)")
        void gradeCriteria() {
            List<LifezoneGradeCriterionVO> criteria =
                    LifezoneGradeCriterionVO.allOf(jenksOf(expand(FREQ_500M)), "건");

            assertThat(criteria).extracting(LifezoneGradeCriterionVO::getMinVl)
                    .containsExactly(371, 142, 63, 20, 1);
            assertThat(criteria).extracting(LifezoneGradeCriterionVO::getLgndTxt)
                    .containsExactly("심각 (371건 이상)", "경고 (142~370건)", "주의 (63~141건)",
                            "보통 (20~62건)", "낮음 (1~19건)");
        }

        /**
         * 🔴 <b>성능</b> — 고유값 압축이 빠졌는지 본다. 100m 격자(2,015셀 · 고유값 56)를 20회 돌린다.
         * 압축하면 {@code O(56² × 5)} ≈ 1.6만 연산이라 눈 깜짝할 새지만, 셀 수로 돌면
         * {@code O(2015² × 5)} ≈ 2천만 × 20회가 되어 확연히 느려진다.
         */
        @Test
        @DisplayName("100m 격자 20회 산출이 1초 안에 끝난다 (고유값 압축)")
        void compressedByUniqueValues() {
            List<Integer> values = expand(FREQ_100M);
            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                assertThat(jenksOf(values).cap()).isEqualTo(247);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs)
                    .as("20회에 %dms — 고유값(56)이 아니라 셀 수(2,015)로 DP 를 돌고 있지 않은지 보라",
                            elapsedMs)
                    .isLessThan(1_000);
        }
    }

    /**
     * 🔁 TSK-4090 Step 5 — {@code CNGST_ABSOLUTE} 는 설정값 경계라 {@code of()} 가 계속 IAE 로 막는다.
     *
     * <p>if 체인이라 막지 않으면 조용히 자연분류로 빠진다. 🔴 값이 없을 때도 던져야 한다 —
     * 축퇴 분기({@code mx <= 0}) 뒤에서 막으면 빈 목록에 기본 경계가 나간다.
     */
    @Nested
    @DisplayName("TSK-4090 — CNGST_ABSOLUTE 는 of() 가 IAE 로 막는다 (ofFixed 전용)")
    class CngstAbsoluteRejectedByOf {

        @Test
        @DisplayName("값이 있어도 · 빈 목록이어도 · null 이어도 IllegalArgumentException")
        void rejectsBeforeDegeneratePath() {
            LifezoneClsfMthd mthd = LifezoneClsfMthd.CNGST_ABSOLUTE;
            assertThatThrownBy(() -> LifezoneGradeScale.of(List.of(1, 5, 9, 20, 40), mthd))
                    .as("자연분류로 조용히 빠지면 안 된다")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LifezoneGradeScale.of(Collections.emptyList(), mthd))
                    .as("축퇴 분기보다 먼저 막아야 한다")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LifezoneGradeScale.of(null, mthd))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * TSK-4090 Step 5 — <b>분위</b>. P25·P50·P75·P90 선형 보간 → {@link Math#round}.
     * 경계 {@code [1, b1..b4]} · {@code cap} = 최대 · {@code width} = 0.
     */
    @Nested
    @DisplayName("TSK-4090 — QUANTILE (분위)")
    class Quantile {

        private LifezoneGradeScale qOf(List<Integer> values) {
            return LifezoneGradeScale.of(values, LifezoneClsfMthd.QUANTILE);
        }

        /**
         * 손계산 — n=10, pos = p × 9.
         * P25: 2.25 → 8520+0.25×80 = 8540 · P50: 4.5 → 8750 · P75: 6.75 → 8975 · P90: 8.1 → 9500+0.1×2500 = 9750.
         */
        @Test
        @DisplayName("경계를 손계산과 대조 — [1, 8540, 8750, 8975, 9750] · cap=최대 · width=0")
        void handComputed() {
            LifezoneGradeScale scale = qOf(List.of(9000, 8500, 8510, 8520, 8600, 8700, 8800, 8900, 9500, 12000));
            assertThat(ascMinVls(scale)).containsExactly(1, 8540, 8750, 8975, 9750);
            assertThat(scale.cap()).isEqualTo(12000);
            assertThat(scale.width()).isZero();
            assertThat(scale.method()).isEqualTo("QUANTILE");
            assertThat(scale.gradeOf(8500)).isEqualTo(LifezoneGrade.LOW);
            assertThat(scale.gradeOf(8539)).isEqualTo(LifezoneGrade.LOW);
            assertThat(scale.gradeOf(8540)).isEqualTo(LifezoneGrade.NORMAL);
            assertThat(scale.gradeOf(8975)).isEqualTo(LifezoneGrade.WARN);
            assertThat(scale.gradeOf(12000)).isEqualTo(LifezoneGrade.SEVERE);
        }

        /** 반올림은 Math.round(half-up) — P50 = 1.5 → 2 */
        @Test
        @DisplayName("반올림 후 겹친 경계는 단조 보정 — [1,2] → [1,2,3,4,5]")
        void monotonicAfterRounding() {
            assertThat(ascMinVls(qOf(List.of(1, 2)))).containsExactly(1, 2, 3, 4, 5);
            // 같은 값만 있으면 분위가 전부 그 값 — 뒤 경계를 +1 로 민다
            assertThat(ascMinVls(qOf(List.of(8500, 8500, 8500)))).containsExactly(1, 8500, 8501, 8502, 8503);
        }

        @Test
        @DisplayName("균등 분포 1..1000 이면 약 25/25/25/15/10% (낮음→심각)")
        void roughlyQuartileShares() {
            List<Integer> values = new ArrayList<>();
            for (int v = 1; v <= 1000; v++) {
                values.add(v);
            }
            LifezoneGradeScale scale = qOf(values);
            Map<LifezoneGrade, Integer> cnt = new java.util.EnumMap<>(LifezoneGrade.class);
            for (int v : values) {
                cnt.merge(scale.gradeOf(v), 1, Integer::sum);
            }
            assertThat(cnt.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(1000);
            assertThat(cnt.get(LifezoneGrade.LOW)).isBetween(248, 252);
            assertThat(cnt.get(LifezoneGrade.NORMAL)).isBetween(248, 252);
            assertThat(cnt.get(LifezoneGrade.CAUTION)).isBetween(248, 252);
            assertThat(cnt.get(LifezoneGrade.WARN)).isBetween(148, 152);
            assertThat(cnt.get(LifezoneGrade.SEVERE)).isBetween(99, 103);
        }

        @Test
        @DisplayName("빈 목록·null — 예외 없이 cap=0 · width=0 · [1,2,3,4,5] (등간격 계열만 width=1)")
        void emptyValues() {
            for (List<Integer> v : java.util.Arrays.asList(null, Collections.<Integer>emptyList())) {
                LifezoneGradeScale scale = qOf(v);
                assertThat(scale.cap()).isZero();
                assertThat(scale.width()).isZero();
                assertThat(ascMinVls(scale)).containsExactly(1, 2, 3, 4, 5);
            }
            assertThat(LifezoneGradeScale.of(List.of(), LifezoneClsfMthd.EQUAL_INTERVAL_P95).width()).isEqualTo(1);
            assertThat(LifezoneGradeScale.of(List.of(), LifezoneClsfMthd.EQUAL_INTERVAL).width()).isEqualTo(1);
            assertThat(LifezoneGradeScale.of(List.of(), LifezoneClsfMthd.JENKS).width()).isZero();
        }

        @Test
        @DisplayName("입력 순서와 무관 · null·0 이하 원소는 무시")
        void orderIndependentAndIgnoresInvalid() {
            List<Integer> base = List.of(8500, 8510, 8520, 8600, 8700, 8800, 8900, 9000, 9500, 12000);
            List<Integer> shuffled = new ArrayList<>(base);
            Collections.reverse(shuffled);
            assertThat(ascMinVls(qOf(shuffled))).isEqualTo(ascMinVls(qOf(base)));

            List<Integer> noisy = new ArrayList<>(base);
            noisy.add(null);
            noisy.add(0);
            noisy.add(-3);
            assertThat(ascMinVls(qOf(noisy))).isEqualTo(ascMinVls(qOf(base)));
        }

        @Test
        @DisplayName("직렬화 키 {method, cap, width}")
        void serializedKeys() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new ObjectMapper().readValue(
                    new ObjectMapper().writeValueAsString(qOf(List.of(8500, 9000, 12000))), Map.class);
            assertThat(map.keySet()).containsExactlyInAnyOrder("method", "cap", "width");
            assertThat(map.get("method")).isEqualTo("QUANTILE");
        }

        /**
         * 🔁 TSK-4227 — 통합테스트({@code LifezoneCngstIntegrationTest.occShiftMovesAllCellsMonotonically})에서
         * 이관했다(D12 ②). 혼잡도 허용 목록이 {@code CNGST_ABSOLUTE} 1종이 되어 서비스 경로로는 분위를 부를 수 없다.
         * 분위는 선형 보간이라 전 값을 같은 양만큼 옮기면 경계도 같은 양만큼 옮겨진다 —
         * 통합 쪽의 셀 반올림 ±1 이 보간·반올림을 거쳐 번지는 폭을 그대로 ±2 로 둔다.
         */
        @Test
        @DisplayName("TSK-4227 이관 — 전 값 +500 평행이동 → 상위 경계 4개 +500(±2) · 최하위 하한 1 고정")
        void shiftMovesUpperBoundsByTheSameAmount() {
            // 시큐어코딩 2.8(TSK-4336) — 예측 가능한 java.util.Random 대신 SecureRandom. 성질(평행이동) 검사라 입력이 매번 달라도 된다.
            java.security.SecureRandom rnd = new java.security.SecureRandom();
            List<List<Integer>> inputs = new ArrayList<>();
            inputs.add(List.of(9000, 8500, 8510, 8520, 8600, 8700, 8800, 8900, 9500, 12000));
            List<Integer> random = new ArrayList<>();
            for (int i = 0; i < 597; i++) {
                random.add(8000 + rnd.nextInt(6000));
            }
            inputs.add(random);

            for (List<Integer> base : inputs) {
                List<Integer> shifted = base.stream().map(v -> v + 500).toList();
                List<Integer> before = ascMinVls(qOf(base));
                List<Integer> after = ascMinVls(qOf(shifted));
                assertThat(after.get(0)).as("최하위 하한은 1 고정").isEqualTo(1).isEqualTo(before.get(0));
                for (int i = 1; i < GRADE_CNT; i++) {
                    assertThat(after.get(i) - before.get(i)).as("QUANTILE 경계 %d 이동", i).isBetween(498, 502);
                }
                assertThat(qOf(shifted).cap()).isEqualTo(qOf(base).cap() + 500);
            }
        }
    }

    /**
     * 🔁 TSK-4227 — <b>등간격 P95 상한</b>({@link LifezoneClsfMthd#EQUAL_INTERVAL_P95}).
     * 민원건수 허용 목록에서 빠져 서비스 경로로는 부를 수 없게 됐지만(저장 요청은 400 — {@code LifezoneReportIntegrationTest}
     * 케이스 3), 과거 보고서 스냅샷 해석용으로 산출 코드는 남았다(D1). 종전 통합테스트가 지키던 경계 산출을 단위로 이관한다(D12 ②).
     *
     * <p>자의 길이 = P95 ({@code percentile_disc} — 오름차순 {@code ceil(0.95·n)-1} 번째, 보간 없음) ·
     * {@code width = ceil(P95/5)} · 경계 {@code [1, w, 2w, 3w, 4w]} · {@code cap} = P95 · P95 를 넘는 값은 버리지 않고 SEVERE.
     */
    @Nested
    @DisplayName("TSK-4227 이관 — EQUAL_INTERVAL_P95 (등간격 · P95 상한)")
    class EqualIntervalP95 {

        private LifezoneGradeScale p95Of(List<Integer> values) {
            return LifezoneGradeScale.of(values, LifezoneClsfMthd.EQUAL_INTERVAL_P95);
        }

        /**
         * 손계산 — 1..30 (n=30): idx0 = ceil(28.5)-1 = 28 → P95 = 29 (선형 보간이면 28.55 라 disc 와 갈린다) ·
         * width = ceil(29/5) = 6 · 경계 [1,6,12,18,24].
         */
        @Test
        @DisplayName("자 길이 = P95(percentile_disc) · width = ceil(P95/5) — 1..30 → P95 29 · 폭 6 · [1,6,12,18,24]")
        void handComputed() {
            List<Integer> values = new ArrayList<>();
            for (int v = 30; v >= 1; v--) {   // 역순 — 정렬은 산출이 한다
                values.add(v);
            }
            LifezoneGradeScale scale = p95Of(values);
            assertThat(scale.cap()).as("cap 은 최대값(30)이 아니라 P95").isEqualTo(29);
            assertThat(scale.width()).isEqualTo(6);
            assertThat(ascMinVls(scale)).containsExactly(1, 6, 12, 18, 24);
            assertThat(scale.method()).isEqualTo("EQUAL_INTERVAL_P95");
        }

        /**
         * 꼬리가 긴 분포 — 1..19 + 247 (n=20): idx0 = ceil(19)-1 = 18 → P95 = 19 · width = ceil(19/5) = 4 ·
         * 경계 [1,4,8,12,16]. 최대값 247 은 자에 들어가지 않지만 <b>버려지지 않고 SEVERE</b> 다.
         */
        @Test
        @DisplayName("P95 초과 값은 버리지 않고 SEVERE — 1..19 + 247 → P95 19 · 폭 4 · [1,4,8,12,16]")
        void valuesAboveP95AreSevere() {
            List<Integer> values = new ArrayList<>();
            for (int v = 1; v <= 19; v++) {
                values.add(v);
            }
            values.add(247);
            LifezoneGradeScale scale = p95Of(values);

            assertThat(scale.cap()).isEqualTo(19);
            assertThat(scale.width()).isEqualTo(Math.max(1, (19 + GRADE_CNT - 1) / GRADE_CNT));
            assertThat(ascMinVls(scale)).containsExactly(1, 4, 8, 12, 16);
            assertThat(scale.gradeOf(19)).as("P95 자신").isEqualTo(LifezoneGrade.SEVERE);
            assertThat(scale.gradeOf(247)).as("P95 초과 — 최대값").isEqualTo(LifezoneGrade.SEVERE);
            assertThat(scale.gradeOf(15)).isEqualTo(LifezoneGrade.WARN);
            assertThat(scale.gradeOf(3)).isEqualTo(LifezoneGrade.LOW);

            // 같은 값의 등간격(최대값 상한)과 자 길이가 다르다 — 247 → 폭 50
            assertThat(eqOf(values).cap()).isEqualTo(247);
            assertThat(ascMinVls(eqOf(values))).isNotEqualTo(ascMinVls(scale));
        }
    }

    /**
     * TSK-4090 (2026-10-08 · ⑪ 개정) — <b>등간격 최솟값 기준 · 혼잡도 전용</b>.
     * 심각 = 100점 초과({@code > 10000}) 고정 · 나머지는 {@code mn}(≤ 10000 값의 최소) ~ 10000 4등분 ·
     * {@code w = (10000 − mn)/4} · 경계 {@code [1, round(mn+w), round(mn+2w), round(mn+3w), 10001]} ·
     * 축퇴({@code ≤ 10000} 값 없음 · {@code w < 1}) → {@code mn = 9996}.
     */
    @Nested
    @DisplayName("TSK-4090 — EQUAL_INTERVAL_MIN (100점 초과 = 심각 · 최솟값~100점 4등분)")
    class EqualIntervalMin {

        private static final int FULL = LifezoneGradeScale.CNGST_FULL_VL;

        private LifezoneGradeScale mOf(List<Integer> values) {
            return LifezoneGradeScale.of(values, LifezoneClsfMthd.EQUAL_INTERVAL_MIN);
        }

        @Test
        @DisplayName("100점 = 10000 상수")
        void fullConstant() {
            assertThat(FULL).isEqualTo(10_000);
        }

        /** 손계산 — mn=8000 (13000 은 자에 안 들어간다) → w=(10000-8000)/4=500 → [1, 8500, 9000, 9500, 10001] */
        @Test
        @DisplayName("경계를 손계산과 대조 — 최솟값~100점 4등분 · 심각 10001 · cap=최대 · width=폭")
        void handComputed() {
            LifezoneGradeScale scale = mOf(List.of(13000, 8000, 9000, 9600));
            assertThat(ascMinVls(scale)).containsExactly(1, 8500, 9000, 9500, 10001);
            assertThat(scale.cap()).isEqualTo(13000);
            assertThat(scale.width()).isEqualTo(500);
            assertThat(scale.method()).isEqualTo("EQUAL_INTERVAL_MIN");
            assertThat(scale.gradeOf(8000)).isEqualTo(LifezoneGrade.LOW);
            assertThat(scale.gradeOf(8499)).isEqualTo(LifezoneGrade.LOW);
            assertThat(scale.gradeOf(8500)).isEqualTo(LifezoneGrade.NORMAL);
            assertThat(scale.gradeOf(9499)).isEqualTo(LifezoneGrade.CAUTION);
            assertThat(scale.gradeOf(9600)).isEqualTo(LifezoneGrade.WARN);
            assertThat(scale.gradeOf(10000)).as("경고 상한 = 100점 포함").isEqualTo(LifezoneGrade.WARN);
            assertThat(scale.gradeOf(10001)).as("100점 초과 = 심각").isEqualTo(LifezoneGrade.SEVERE);
            assertThat(scale.gradeOf(13000)).isEqualTo(LifezoneGrade.SEVERE);
        }

        /** 0 기준 등간격과 다르다 — 같은 값에서 EQUAL_INTERVAL 은 ceil(13000/5)=2600 폭이다 */
        @Test
        @DisplayName("0 기준 등간격(EQUAL_INTERVAL)과 경계가 다르다")
        void differsFromZeroBased() {
            List<Integer> v = List.of(13000, 8000, 9000, 9500);
            assertThat(ascMinVls(mOf(v)))
                    .isNotEqualTo(ascMinVls(LifezoneGradeScale.of(v, LifezoneClsfMthd.EQUAL_INTERVAL)));
        }

        /** 실수 폭 2.5 — round(9992.5)=9993 · round(9995)=9995 · round(9997.5)=9998 · width=round(2.5)=3 */
        @Test
        @DisplayName("폭이 정수가 아니면 경계마다 반올림 — [1, 9993, 9995, 9998, 10001] · width=3")
        void roundsEachBoundary() {
            LifezoneGradeScale scale = mOf(List.of(9990, 10000));
            assertThat(ascMinVls(scale)).containsExactly(1, 9993, 9995, 9998, 10001);
            assertThat(scale.width()).isEqualTo(3);
            assertThat(scale.gradeOf(9990)).isEqualTo(LifezoneGrade.LOW);
            assertThat(scale.gradeOf(10000)).isEqualTo(LifezoneGrade.WARN);
        }

        @Test
        @DisplayName("100점 초과 값은 하위 4등급의 자에 영향을 주지 않는다 (값이 얼마든 심각)")
        void over100DoesNotMoveLowerBounds() {
            List<Integer> lower = ascMinVls(mOf(List.of(7000)));
            assertThat(lower).containsExactly(1, 7750, 8500, 9250, 10001);
            assertThat(ascMinVls(mOf(List.of(7000, 15000, 20000)))).isEqualTo(lower);
            assertThat(ascMinVls(mOf(List.of(7000, 10001)))).isEqualTo(lower);
        }

        @Test
        @DisplayName("값을 평행이동해도 심각 하한 10001 은 고정 — 하위 경계만 최솟값을 따라 움직인다")
        void severeBoundFixedUnderShift() {
            List<Integer> base = List.of(6000, 8300, 9100, 9900, 13000);
            List<Integer> shifted = base.stream().map(x -> x + 1000).toList();
            List<Integer> a = ascMinVls(mOf(base));
            List<Integer> b = ascMinVls(mOf(shifted));
            assertThat(a.get(4)).isEqualTo(FULL + 1);
            assertThat(b.get(4)).isEqualTo(FULL + 1);
            assertThat(a).containsExactly(1, 7000, 8000, 9000, 10001);
            assertThat(b).containsExactly(1, 7750, 8500, 9250, 10001);
        }

        @Test
        @DisplayName("축퇴 — 100점 이하 값 없음(전부 초과) · w<1 · 빈 목록/null → mn=9996 · [1, 9997, 9998, 9999, 10001] · width=1")
        void degenerate() {
            List<Integer> deg = List.of(1, 9997, 9998, 9999, 10001);

            LifezoneGradeScale allOver = mOf(List.of(10001, 15000));
            assertThat(ascMinVls(allOver)).containsExactlyElementsOf(deg);
            assertThat(allOver.cap()).isEqualTo(15000);
            assertThat(allOver.width()).isEqualTo(1);
            assertThat(allOver.gradeOf(10001)).as("경고 이하가 비어도 심각 판정 유지").isEqualTo(LifezoneGrade.SEVERE);

            LifezoneGradeScale narrow = mOf(List.of(9998, 10000, 12000));   // w = 0.5 < 1
            assertThat(ascMinVls(narrow)).containsExactlyElementsOf(deg);
            assertThat(narrow.gradeOf(10000)).isEqualTo(LifezoneGrade.WARN);
            assertThat(narrow.gradeOf(12000)).isEqualTo(LifezoneGrade.SEVERE);

            // mn = 9996 은 정상 경로(w = 1) — 축퇴 결과와 같은 경계라 연속이다
            LifezoneGradeScale edge = mOf(List.of(9996));
            assertThat(ascMinVls(edge)).containsExactlyElementsOf(deg);
            assertThat(edge.gradeOf(9996)).isEqualTo(LifezoneGrade.LOW);

            for (List<Integer> v : java.util.Arrays.asList(null, Collections.<Integer>emptyList())) {
                LifezoneGradeScale scale = mOf(v);
                assertThat(scale.cap()).isZero();
                assertThat(scale.width()).isEqualTo(1);
                assertThat(ascMinVls(scale)).as("빈 값도 심각 하한 10001 유지").containsExactlyElementsOf(deg);
            }
        }

        @Test
        @DisplayName("null·0 이하 원소는 최솟값에서 무시 · 입력 순서와 무관")
        void ignoresInvalidAndOrder() {
            List<Integer> base = List.of(8000, 8300, 9100, 9900, 13000);
            List<Integer> noisy = new ArrayList<>(base);
            Collections.reverse(noisy);
            noisy.add(null);
            noisy.add(0);
            noisy.add(-5);
            assertThat(ascMinVls(mOf(noisy))).isEqualTo(ascMinVls(mOf(base)));
        }

        @Test
        @DisplayName("임의 입력 — 경계 엄격 단조 · 심각 ⟺ 100점 초과 · 100점 이하 최솟값은 낮음(축퇴 밖)")
        void invariantsOnRandomInputs() {
            // 시큐어코딩 2.8(TSK-4336) — SecureRandom. 불변식 검사라 입력이 매번 달라도 되고, 실패 메시지에 입력(v)이 찍혀 재현할 수 있다.
            java.security.SecureRandom rnd = new java.security.SecureRandom();
            for (int trial = 0; trial < 500; trial++) {
                int n = 1 + rnd.nextInt(30);
                List<Integer> v = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    v.add(1 + rnd.nextInt(1 + rnd.nextInt(30000)));
                }
                LifezoneGradeScale scale = mOf(v);
                List<Integer> asc = ascMinVls(scale);
                for (int i = 1; i < asc.size(); i++) {
                    assertThat(asc.get(i)).as("trial %d %s", trial, v).isGreaterThan(asc.get(i - 1));
                }
                for (int x : v) {
                    assertThat(scale.gradeOf(x) == LifezoneGrade.SEVERE).as("trial %d x=%d", trial, x)
                            .isEqualTo(x > FULL);
                }
                int t = trial;
                v.stream().filter(x -> x <= FULL - 4).min(Integer::compare).ifPresent(mn ->
                        assertThat(scale.gradeOf(mn)).as("trial %d %s", t, v).isEqualTo(LifezoneGrade.LOW));
            }
        }
    }

    /**
     * TSK-4090 Step 5 — <b>설정값 경계</b> {@code ofFixed}. 인자는 b1~b4 <b>4개</b>이고
     * {@code MIN_FLOOR} 를 앞에 붙이는 것은 팩토리 책임이다.
     */
    @Nested
    @DisplayName("TSK-4090 — CNGST_ABSOLUTE (ofFixed)")
    class Fixed {

        private static final int[] BND = { 9000, 10000, 11000, 13000 };

        @Test
        @DisplayName("[1, 9000, 10000, 11000, 13000] · cap 그대로 · width=0 · 경계 포함 판정")
        void bounds() {
            LifezoneGradeScale scale = LifezoneGradeScale.ofFixed(BND, 20130, LifezoneClsfMthd.CNGST_ABSOLUTE);
            assertThat(ascMinVls(scale)).containsExactly(1, 9000, 10000, 11000, 13000);
            assertThat(scale.cap()).isEqualTo(20130);
            assertThat(scale.width()).isZero();
            assertThat(scale.method()).isEqualTo("CNGST_ABSOLUTE");
            assertThat(scale.gradeOf(8500)).isEqualTo(LifezoneGrade.LOW);
            assertThat(scale.gradeOf(9000)).isEqualTo(LifezoneGrade.NORMAL);
            assertThat(scale.gradeOf(9999)).isEqualTo(LifezoneGrade.NORMAL);
            assertThat(scale.gradeOf(10000)).isEqualTo(LifezoneGrade.CAUTION);
            assertThat(scale.gradeOf(11000)).isEqualTo(LifezoneGrade.WARN);
            assertThat(scale.gradeOf(13000)).isEqualTo(LifezoneGrade.SEVERE);
        }

        @Test
        @DisplayName("입력 배열을 바꿔도 값 객체가 흔들리지 않는다")
        void defensiveCopy() {
            int[] bnd = BND.clone();
            LifezoneGradeScale scale = LifezoneGradeScale.ofFixed(bnd, 0, LifezoneClsfMthd.CNGST_ABSOLUTE);
            bnd[0] = 1;
            assertThat(ascMinVls(scale)).containsExactly(1, 9000, 10000, 11000, 13000);
        }

        @Test
        @DisplayName("단조가 아니면 앞+1 로 민다 · 1 이하 경계도 [1,2,3,4,5]")
        void monotonic() {
            assertThat(ascMinVls(LifezoneGradeScale.ofFixed(new int[] { 9000, 9000, 8000, 13000 }, 0,
                    LifezoneClsfMthd.CNGST_ABSOLUTE))).containsExactly(1, 9000, 9001, 9002, 13000);
            assertThat(ascMinVls(LifezoneGradeScale.ofFixed(new int[] { 0, 0, 0, 0 }, 0,
                    LifezoneClsfMthd.CNGST_ABSOLUTE))).containsExactly(1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("경계가 4개가 아니거나 null · mthd null → IllegalArgumentException")
        void rejectsBadInput() {
            LifezoneClsfMthd m = LifezoneClsfMthd.CNGST_ABSOLUTE;
            assertThatThrownBy(() -> LifezoneGradeScale.ofFixed(new int[] { 1, 9000, 10000, 11000, 13000 }, 0, m))
                    .as("MIN_FLOOR 를 붙인 5개를 넘기면 안 된다").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LifezoneGradeScale.ofFixed(new int[] { 9000, 10000, 11000 }, 0, m))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LifezoneGradeScale.ofFixed(null, 0, m))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LifezoneGradeScale.ofFixed(BND, 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * TSK-4090 Step 5 — {@code allOf(scale, unit, fmt)} 오버로드. 혼잡도는 점수×100 정수라 ÷100 포매터를 넘긴다
     * (2026-10-08 지수 → 점수 환산 · {@code .0} 생략 — 서비스 {@code formatScore} 와 같은 규칙).
     * 🔴 낮음 행만 {@code {위 등급 하한} 미만} 이고, 2인자(민원건수)는 종전 {@code 1~N} 그대로다.
     */
    @Nested
    @DisplayName("TSK-4090 — LifezoneGradeCriterionVO.allOf 포매터 오버로드")
    class CriterionFormatter {

        private final java.util.function.IntFunction<String> score = v -> {
            String s = String.format(java.util.Locale.ROOT, "%.1f", v / 100.0);
            return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
        };

        @Test
        @DisplayName("÷100 점수 포매터 — 심각 이상 · 중간 a~b · 낮음은 '미만' · minVl 은 정수 그대로")
        void formatted() {
            LifezoneGradeScale scale = LifezoneGradeScale.ofFixed(
                    new int[] { 8800, 9200, 9600, 10000 }, 0, LifezoneClsfMthd.CNGST_ABSOLUTE);
            List<LifezoneGradeCriterionVO> criteria = LifezoneGradeCriterionVO.allOf(scale, "점", score);
            assertThat(criteria).extracting(LifezoneGradeCriterionVO::getMinVl)
                    .containsExactly(10000, 9600, 9200, 8800, 1);
            // 상한(위 하한 − 1)은 첫째 자리 반올림이라 위 하한과 같아 보인다(허용 — 서식 주석)
            assertThat(criteria).extracting(LifezoneGradeCriterionVO::getLgndTxt).containsExactly(
                    "심각 (100점 이상)", "경고 (96~100점)", "주의 (92~96점)",
                    "보통 (88~92점)", "낮음 (88점 미만)");
        }

        @Test
        @DisplayName("2인자는 불변 — 낮음 행도 1~N 형식 (String::valueOf 위임)")
        void twoArgUnchanged() {
            LifezoneGradeScale scale = eqOf(List.of(386));
            List<String> two = LifezoneGradeCriterionVO.allOf(scale, "건").stream()
                    .map(LifezoneGradeCriterionVO::getLgndTxt).toList();
            assertThat(two).containsExactly("심각 (312건 이상)", "경고 (234~311건)", "주의 (156~233건)",
                    "보통 (78~155건)", "낮음 (1~77건)");
            // 3인자 + String::valueOf 는 낮음 행만 다르다
            List<String> three = LifezoneGradeCriterionVO.allOf(scale, "건", String::valueOf).stream()
                    .map(LifezoneGradeCriterionVO::getLgndTxt).toList();
            assertThat(three.subList(0, 4)).isEqualTo(two.subList(0, 4));
            assertThat(three.get(4)).isEqualTo("낮음 (78건 미만)");
        }
    }
}
