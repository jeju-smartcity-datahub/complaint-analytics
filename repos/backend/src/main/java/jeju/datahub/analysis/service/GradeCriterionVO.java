package jeju.datahub.analysis.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 등급 판정 기준 한 줄. {@link HotspotGrade} 상수 하나를 그대로 옮긴 <b>읽기 전용 투영</b>이다.
 *
 * <p><b>왜 응답에 싣나</b> — 지도 범례가 {@code 심각 (상위 20%)} 처럼 기준을 문구로 보여 준다.
 * 비율은 설정값이라 프론트가 직접 적으면 <b>범례만 조용히 틀려진다</b>.
 *
 * <p><b>값을 두 번 적지 않는다</b> — {@link #displayedOf(HotspotGradeBndry)} 가 실제 적용된 비율로 만든다.
 */
@Schema(description = "등급 판정 기준 (HotspotGrade 파생)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GradeCriterionVO {

    /** 등급코드. {@code zones[].grade} 와 같은 값이다 — 프론트가 색·순서를 이 코드로 맞춘다 */
    @Schema(description = "등급코드", example = "SEVERE")
    private String grade;

    /** 화면 표시용 등급명 */
    @Schema(description = "등급명", example = "심각")
    private String gradeNm;

    /**
     * 이 등급의 순위 비율 상한(%). 하한은 싣지 않는다 — 바로 위 등급의 값이 곧 하한이라
     * 두 번 적으면 어긋날 수 있다.
     */
    @Schema(description = "이 등급의 순위 비율 상한(%) — 상위 몇 %까지인가", example = "20")
    private int maxRankPct;

    private GradeCriterionVO(HotspotGrade grade, int maxRankPct) {
        this.grade = grade.name();
        this.gradeNm = grade.getGradeNm();
        this.maxRankPct = maxRankPct;
    }

    /**
     * 화면·보고서에 <b>표시하는</b> 등급의 판정 기준. {@code maxRankPct} 오름차순이다.
     *
     * <p>🔴 {@code NORMAL}(양호)은 <b>싣지 않는다</b> — 같은 작업에서 {@code zones[]} 에서도
     * 빠졌기 때문이다(그 밖 구역은 조회 단계에서 걸러진다). 어느 행에도 해당하지 않는 기준을
     * 응답에 남기면 범례·내보내기가 "존재하지 않는 등급" 을 설명하게 된다.
     * ⚠️ 이 목록을 다시 4행으로 늘리려면 조회 하한({@link HotspotGrade#MIN_DISPLAYED})을 함께 되돌린다.
     */
    public static List<GradeCriterionVO> displayedOf(HotspotGradeBndry bndry) {
        return HotspotGrade.displayedValues().stream()
                .map(grade -> new GradeCriterionVO(grade, bndry.pctOf(grade)))
                .toList();
    }
}
