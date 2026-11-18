package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 집중구역 등급 — 🔁 TSK-4321 로 <b>종합점수 순위 비율</b>로 판정한다. 이 enum 이 단일 출처다 (TSK-4032 D5).
 *
 * <p>조회 결과의 전체 군집(0점 제외)을 점수 내림차순으로 세워 <b>기본 상위 5% 심각 · 5~10% 경고 · 10~15% 주의</b>,
 * 나머지는 양호(응답 제외)다. 비율은 설정값이고({@link HotspotGradeBndry}) 여기 값은 폴백이다. 같은 점수는 같은 등급이다. 그래서 점수가 낮아도 순위가 높으면 심각일 수 있다 —
 * 등급은 절대 수준이 아니라 <b>이번 조회 조건 안의 상대 위치</b>다.
 *
 * <p>🔴 {@link #minScore} 는 <b>구 보고서 재판정 전용</b>이다 — {@code grade} 가 저장되지 않은 스냅샷만
 * {@link #of(BigDecimal)} 로 판정한다. 신규 판정에 쓰지 않는다.
 */
public enum HotspotGrade {

    /** 심각 — 기본 순위 상위 5% */
    SEVERE(80, "심각", 5),

    /** 경고 — 기본 순위 상위 5~10% */
    WARN(60, "경고", 10),

    /** 주의 — 기본 순위 상위 10~15% */
    CAUTION(40, "주의", 15),

    /** 양호 — 그 밖 (응답에 싣지 않는다) */
    NORMAL(0, "양호", 100);

    /** 구 보고서 재판정용 최소 종합점수(이상). 구 스냅샷은 등급을 저장하지 않았다 */
    private final int minScore;

    /** 화면 표시용 등급명 */
    private final String gradeNm;

    /** 설정 행을 쓸 수 없을 때의 순위 비율 상한(%). 🔴 마이그레이션 98 의 {@code bsc_stng_vl} 과 같은 값이다 */
    private final int maxRankPct;

    HotspotGrade(int minScore, String gradeNm, int maxRankPct) {
        this.minScore = minScore;
        this.gradeNm = gradeNm;
        this.maxRankPct = maxRankPct;
    }

    public int getMinScore() {
        return minScore;
    }

    public String getGradeNm() {
        return gradeNm;
    }

    public int getMaxRankPct() {
        return maxRankPct;
    }

    /**
     * 화면·보고서에 <b>표시하는 최하위 등급</b> (TSK-4172). 이 등급의 비율이 곧 조회 범위이며,
     * 그 밖의 구역({@link #NORMAL})은 응답에 싣지 않는다.
     *
     * <p>🔴 {@code NORMAL} 을 enum 에서 지우지 않는다 — 구 보고서 스냅샷을 재판정할 때 필요하다.
     */
    public static final HotspotGrade MIN_DISPLAYED = CAUTION;

    /** 표시 대상 등급만 (선언 순서 = 임계값 내림차순). {@code gradeCriteria} 의 출처다 */
    public static List<HotspotGrade> displayedValues() {
        return Arrays.stream(values())
                .filter(g -> g.maxRankPct <= MIN_DISPLAYED.maxRankPct)
                .toList();
    }

    /**
     * 구 보고서 재판정 — 저장된 점수와 당시 임계값(80/60/40)으로 판정한다.
     *
     * @param score 종합점수. {@code null} 이거나 음수면 {@link #NORMAL}
     */
    public static HotspotGrade of(BigDecimal score) {
        if (score == null) {
            return NORMAL;
        }
        for (HotspotGrade grade : values()) {
            if (score.compareTo(BigDecimal.valueOf(grade.minScore)) >= 0) {
                return grade;
            }
        }
        return NORMAL;
    }
}
