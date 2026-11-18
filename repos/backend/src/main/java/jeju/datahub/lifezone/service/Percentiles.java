package jeju.datahub.lifezone.service;

/**
 * 백분위 — <b>선형 보간</b> 방식. (TSK-4089)
 *
 * <p>🔴 {@code numpy.percentile}/{@code pandas.quantile} 의 <b>기본</b> 정의({@code linear})와
 * 같아야 한다. 산출 로직 원본이 그 정의로 임계값을 냈고(인계서 §2-3 "백분위는 선형 보간"),
 * 다른 정의(가장 가까운 순위·낮은 값 택일 등)를 쓰면 임계값이 달라져 사분면이 통째로 바뀐다.
 * 300m 의 P80 = 23.4 처럼 <b>정수가 아닌 값</b>이 나오는 것이 보간이 걸렸다는 표시다.
 *
 * <p>정의: 오름차순 정렬한 {@code n} 개 값에서 {@code pos = p * (n - 1)} 위치를 읽는다.
 * 정수면 그 값, 아니면 앞뒤 두 값을 소수부 비율로 섞는다.
 *
 * <p>🔴 <b>결과를 반올림하지 않는다.</b> 임계값은 비교에만 쓰고 표시할 때만 올림한다 —
 * 반올림한 값으로 판정하면 경계 격자의 사분면이 달라진다(인계서 §5-3).
 *
 * <p>{@link jeju.datahub.lifezone.service.LifezoneGradeScale} 안에도 {@code percentile} 이
 * 있지만 <b>그쪽은 다른 정의</b>다(정수 반환 · 올림 인덱스). 등급 구간용이라 목적이 다르며,
 * 합치면 한쪽이 조용히 틀어진다.
 */
public final class Percentiles {

    private Percentiles() {
    }

    /**
     * 선형 보간 백분위.
     *
     * @param sortedAsc <b>오름차순으로 정렬된</b> 값 배열. 정렬은 호출부 책임이다
     *                  (같은 배열로 여러 백분위를 뽑으므로 매번 정렬하지 않는다)
     * @param p         0.0 ~ 1.0
     * @return 보간된 백분위 값. 빈 배열이면 {@code 0.0}
     */
    public static double linear(double[] sortedAsc, double p) {
        if (sortedAsc == null || sortedAsc.length == 0) {
            return 0.0;
        }
        if (sortedAsc.length == 1) {
            return sortedAsc[0];
        }
        double pos = p * (sortedAsc.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sortedAsc[lo];
        }
        double frac = pos - lo;
        return sortedAsc[lo] + (sortedAsc[hi] - sortedAsc[lo]) * frac;
    }
}
