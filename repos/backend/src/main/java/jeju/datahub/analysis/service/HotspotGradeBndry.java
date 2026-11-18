package jeju.datahub.analysis.service;

/**
 * 집중구역 등급 순위 비율 한 벌(%) — 설정값 {@code GRD_BNDRY1~3} 을 읽어 만든다.
 *
 * <p>기준 순위는 {@code ceil(전체 × 비율)} 이다 — 올림이라 구역이 적어도 1위는 항상 심각이다.
 * 🔴 매퍼의 조회 범위({@code CEIL(scr_cnt * maxRankPct / 100.0)})와 같은 식이어야 한다.
 *
 * @param severePct  심각 상위 비율
 * @param warnPct    경고 상위 비율
 * @param cautionPct 주의 상위 비율. 그 밖은 표시하지 않는다
 */
public record HotspotGradeBndry(int severePct, int warnPct, int cautionPct) {

    /** 설정 행을 쓸 수 없을 때의 폴백. {@link HotspotGrade} 의 비율이자 마이그레이션 {@code bsc_stng_vl} 과 같다. */
    public static final HotspotGradeBndry DEFAULT = new HotspotGradeBndry(
            HotspotGrade.SEVERE.getMaxRankPct(),
            HotspotGrade.WARN.getMaxRankPct(),
            HotspotGrade.CAUTION.getMaxRankPct());

    /**
     * 순위 비율로 등급을 판정한다.
     *
     * @param rank  점수 내림차순 순위 (동점은 같은 순위)
     * @param total 0점을 뺀 전체 구역 수
     */
    public HotspotGrade gradeOf(long rank, long total) {
        if (rank <= cutoff(total, severePct)) {
            return HotspotGrade.SEVERE;
        }
        if (rank <= cutoff(total, warnPct)) {
            return HotspotGrade.WARN;
        }
        if (rank <= cutoff(total, cautionPct)) {
            return HotspotGrade.CAUTION;
        }
        return HotspotGrade.NORMAL;
    }

    /** 표시 등급의 비율. {@code NORMAL} 은 표시하지 않으므로 100 이다. */
    public int pctOf(HotspotGrade grade) {
        return switch (grade) {
            case SEVERE -> severePct;
            case WARN -> warnPct;
            case CAUTION -> cautionPct;
            case NORMAL -> 100;
        };
    }

    private static long cutoff(long total, int pct) {
        return (total * pct + 99) / 100;
    }
}
