package jeju.datahub.lifezone.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 등급 판정 기준 한 줄 — {@link LifezoneGrade} × {@link LifezoneGradeScale} 의 투영.
 *
 * <p><b>왜 응답에 싣나</b> — 생활권은 경계가 <b>조회마다 달라진다</b>. 프론트가 임계값을 박으면
 * 범례가 언제나 틀린다. 그래서 등급별 하한과 <b>완성된 범례 문구</b>까지 서버가 만들어 내린다 —
 * 프론트는 {@code lgndTxt} 를 그대로 찍고 {@code grade} 코드로 색만 맞춘다.
 *
 * <p>{@link #lgndTxt} 서식:
 * <ul>
 *   <li>최상위 등급 — {@code 심각 (56건 이상)} (상한이 없다)</li>
 *   <li>그 밖 — {@code 경고 (42~55건)} (상한은 바로 위 등급의 하한 - 1)</li>
 * </ul>
 * 상한을 필드로 두지 않는 이유: 위 등급의 {@code minVl} 이 곧 상한이라 두 번 적으면 어긋난다.
 *
 * <p>결과가 <b>0건이어도 5종을 그대로 싣는다</b> — 프론트 범례가 응답으로만 조립되므로 키가 비면
 * 범례 자체가 사라진다({@link LifezoneGradeScale} 의 빈 목록 규약).
 */
@Schema(description = "등급 판정 기준 (LifezoneGrade × LifezoneGradeScale 파생)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneGradeCriterionVO {

    /** 등급코드. {@code cells[].grade}·{@code ranking[].grade} 와 같은 값이다 */
    @Schema(description = "등급코드 (SEVERE | WARN | CAUTION | NORMAL | LOW)", example = "SEVERE")
    private String grade;

    /** 화면 표시용 등급명 */
    @Schema(description = "등급명", example = "심각")
    private String gradeNm;

    /** 이 등급으로 판정되는 <b>최소값(이상)</b>. 조회 결과에서 산출된 값이다 */
    @Schema(description = "이 등급의 최소값(이상)", example = "56")
    private int minVl;

    /** 범례 문구. 서버가 완성해 내린다 — 프론트가 임계값을 조립하지 않게 하려는 것이다 */
    @Schema(description = "범례 문구", example = "심각 (56건 이상)")
    private String lgndTxt;

    /**
     * 산출된 경계를 등급 기준 5종으로 투영한다. {@link LifezoneGrade} 선언 순서(심각 → 낮음)다.
     *
     * <p>값을 정수 그대로 찍는다 — 민원건수의 서식이다(낮음 행도 {@code 낮음 (1~77건)}).
     * 내부적으로는 {@code String::valueOf} 포매터로 위임한다({@code %d} 와 같은 문자열).
     *
     * @param scale 조회 결과에서 산출된 경계
     * @param unit  값 단위. {@link LifezoneTarget#getUnit()} — {@code null} 이면 단위 없이 적는다
     */
    public static List<LifezoneGradeCriterionVO> allOf(LifezoneGradeScale scale, String unit) {
        return build(scale, unit, String::valueOf, false);
    }

    /**
     * 값 포매터를 받아 투영한다 — 정수화된 값을 화면 단위로 되돌릴 때 쓴다.
     *
     * <p>혼잡도는 경계가 <b>점수 × 100 정수</b>라 점수 소수 첫째 자리 포매터({@code .0} 생략)를 넘긴다 —
     * 그대로 찍으면 {@code 심각 (8891점 이상)} 이 나간다. 상한(위 등급 하한 − 1)도 같은 포매터로 찍어
     * 첫째 자리에서 위 등급 하한과 같아 보일 수 있다(허용).
     *
     * <p>🔴 <b>최하위(낮음) 행은 {@code {위 등급 하한} 미만}</b> 이다 — 하한 {@code MIN_FLOOR}(1)를
     * ÷100 하면 {@code 낮음 (0~85.1점)} 이 되기 때문이다. 이 서식은 <b>이 오버로드에만</b>
     * 적용되고 2인자({@link #allOf(LifezoneGradeScale, String)} — 민원건수)는 종전 {@code 1~N} 그대로다.
     * ⚠️ 낮음 행 서식은 가정이다.
     *
     * @param scale 산출된 경계
     * @param unit  값 단위 ({@code null} 이면 단위 없이)
     * @param fmt   경계값(정수) → 표시 문자열
     */
    public static List<LifezoneGradeCriterionVO> allOf(LifezoneGradeScale scale, String unit,
                                                       IntFunction<String> fmt) {
        return build(scale, unit, fmt, true);
    }

    private static List<LifezoneGradeCriterionVO> build(LifezoneGradeScale scale, String unit,
                                                        IntFunction<String> fmt, boolean lowAsBelow) {
        String sfx = unit == null ? "" : unit;
        LifezoneGrade[] grades = LifezoneGrade.values();
        int last = grades.length - 1;
        List<LifezoneGradeCriterionVO> list = new ArrayList<>(grades.length);
        for (int i = 0; i < grades.length; i++) {
            LifezoneGrade grade = grades[i];
            LifezoneGradeCriterionVO vo = new LifezoneGradeCriterionVO();
            vo.grade = grade.name();
            vo.gradeNm = grade.getGradeNm();
            vo.minVl = scale.minVlOf(grade);
            // i == 0 이 최상위 등급이다 — 선언 순서가 경계 내림차순이라는 계약에 기댄다
            if (i == 0) {
                vo.lgndTxt = "%s (%s%s 이상)".formatted(vo.gradeNm, fmt.apply(vo.minVl), sfx);
            } else if (i == last && lowAsBelow) {
                vo.lgndTxt = "%s (%s%s 미만)".formatted(vo.gradeNm,
                        fmt.apply(scale.minVlOf(grades[i - 1])), sfx);
            } else {
                vo.lgndTxt = "%s (%s~%s%s)".formatted(vo.gradeNm, fmt.apply(vo.minVl),
                        fmt.apply(scale.minVlOf(grades[i - 1]) - 1), sfx);
            }
            list.add(vo);
        }
        return list;
    }
}
