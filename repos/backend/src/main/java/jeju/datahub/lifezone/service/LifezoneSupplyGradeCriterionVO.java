package jeju.datahub.lifezone.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 수급 등급 범례 1행 — 부족률 등간격 5단계 (TSK-4258 D13 · 🔁 D19).
 *
 * <p><b>왜 {@link LifezoneGradeCriterionVO} 를 재사용하지 않나</b> — 저쪽은 경계가 <b>값</b>(민원 N건 이상)이고
 * 이쪽은 경계가 <b>부족률</b>(80% 이상)이다. 필드 의미({@code minVl})가 맞지 않고, 수급은 등급별 집계
 * (격자·민원·공급 0 격자 수)까지 싣는다. 사분면 범례({@link LifezoneQuadrantCriterionVO})는 구 스냅샷
 * 전용이라 손대지 않는다(D10).
 *
 * <p>🔴 <b>범례 문구 {@link #lgndTxt} 는 서버가 완성해 내린다</b> — 프론트 {@code lifezoneGrade.ts} 의
 * "범례 문구는 응답 {@code lgndTxt} 를 그대로 출력" 원칙. 서식은 {@link LifezoneGradeCriterionVO#getLgndTxt()}
 * 와 같은 "등급명 (구간)" 이다:
 * <ul>
 *   <li>최상위 — {@code 심각 (80% 이상)}</li>
 *   <li>최하위 — {@code 낮음 (20% 미만)}</li>
 *   <li>그 밖 — {@code 경고 (60~80%)}</li>
 * </ul>
 *
 * <p>결과가 <b>0건이어도 5행을 그대로 싣는다</b> — 범례에서 항목이 사라지면 색과 의미의 대응이 조회마다 달라진다.
 */
@Schema(description = "수급 등급 범례 1행 (부족률 등간격 5단계)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneSupplyGradeCriterionVO {

    /** 등급코드. {@code cells[].grade} 와 같은 값이다 */
    @Schema(description = "등급코드 (SEVERE | WARN | CAUTION | NORMAL | LOW)", example = "SEVERE")
    private String grade;

    /** 화면 표시용 등급명 */
    @Schema(description = "등급명", example = "심각")
    private String gradeNm;

    /**
     * 부족률 하한(%, 포함) — 80 · 60 · 40 · 20 · 0.
     *
     * <p>🔁 D20 — 값은 설정 표 {@code SPLY_GRD_BNDRY1~4} 에서 온다(위 숫자는 기본값).
     * {@code 부족률 × 100 >= 이 값} 이면서 위 등급의 하한에 못 미치면 이 등급이다
     * ({@link LifezoneGradeCriterionVO#getMinVl()} 과 같은 "하한만 싣는다" 방식).
     * 상한을 필드로 두지 않는 이유: 위 등급의 하한이 곧 상한이라 두 번 적으면 어긋난다.
     * 이름은 표준단어 최소 {@code MIN} + 부족률 {@code shrtRt}(셀 행 {@code LifezoneSupplyRow.shrtRt} 와 같은 이름)다 —
     * 사전에 {@code 하한}·{@code 부족} 단어가 없다(🔁 D19 — 종전 {@code rnkRtUplmt}, 순위 백분위 상한).
     */
    @Schema(description = "부족률 하한 (%, 포함)", example = "80")
    private int minShrtRt;

    /** 해당 등급 격자 수 */
    @Schema(description = "해당 등급 격자 수", example = "17")
    private int gridCnt;

    /** 해당 등급 민원 합계 */
    @Schema(description = "해당 등급 민원 합계", example = "1520")
    private int cvlcptCnt;

    /** 해당 등급 중 공영주차면 0 인 격자 수 */
    @Schema(description = "공영주차면 0 인 격자 수", example = "17")
    private int zeroSplyGridCnt;

    /** 범례 문구. 서버가 완성해 내린다 */
    @Schema(description = "범례 문구", example = "심각 (80% 이상)")
    private String lgndTxt;

    /**
     * 등급 5종 범례를 만든다. 집계가 0건인 등급도 <b>행을 남긴다</b>.
     *
     * <p>🔁 D20 — 하한·문구 숫자는 {@code bndry}(설정 표 {@code SPLY_GRD_BNDRY1~4})에서 온다. 서식은 그대로다.
     *
     * @param cntByGrade 등급별 {@code [격자 수, 민원 합, 공급 0 격자 수]} (없는 등급은 0 으로 채운다)
     * @param bndry      이번 분석에 적용한 등급 경계 (낮음 하한은 0)
     */
    public static List<LifezoneSupplyGradeCriterionVO> allOf(Map<LifezoneGrade, int[]> cntByGrade,
                                                             LifezoneSupply.GrdBndry bndry) {
        LifezoneGrade[] grades = LifezoneGrade.values();
        int last = grades.length - 1;
        List<LifezoneSupplyGradeCriterionVO> rows = new ArrayList<>(grades.length);
        for (int i = 0; i < grades.length; i++) {
            LifezoneGrade g = grades[i];
            int[] agg = cntByGrade.getOrDefault(g, new int[] {0, 0, 0});
            LifezoneSupplyGradeCriterionVO vo = new LifezoneSupplyGradeCriterionVO();
            vo.grade = g.name();
            vo.gradeNm = g.getGradeNm();
            vo.minShrtRt = bndry.minOf(g);
            vo.gridCnt = agg[0];
            vo.cvlcptCnt = agg[1];
            vo.zeroSplyGridCnt = agg[2];
            // i == 0 이 최상위 등급이다 — 선언 순서가 경계 내림차순이라는 LifezoneGrade 계약에 기댄다
            if (i == 0) {
                vo.lgndTxt = "%s (%d%% 이상)".formatted(vo.gradeNm, vo.minShrtRt);
            } else if (i == last) {
                vo.lgndTxt = "%s (%d%% 미만)".formatted(vo.gradeNm, bndry.minOf(grades[i - 1]));
            } else {
                vo.lgndTxt = "%s (%d~%d%%)".formatted(vo.gradeNm, vo.minShrtRt, bndry.minOf(grades[i - 1]));
            }
            rows.add(vo);
        }
        return rows;
    }
}
