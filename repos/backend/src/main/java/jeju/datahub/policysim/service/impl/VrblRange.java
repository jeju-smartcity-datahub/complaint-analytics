package jeju.datahub.policysim.service.impl;

/**
 * 계수 하나의 허용 범위 — {@code TB_ANLS_VRBL_STNG.min_vl}·{@code max_vl}.
 *
 * <p>요금제 민감도({@link FeeSensitivity})가 계수를 이 두 끝값으로 바꿔 다시 계산한다.
 * 🔴 <b>범위를 코드에 하드코딩하지 않는다</b> — 정본은 설정 행이고, 조달은 {@link PolicySimParams#range(String)} 다.
 *
 * @param minVl 하한 ({@code min_vl})
 * @param maxVl 상한 ({@code max_vl})
 */
public record VrblRange(double minVl, double maxVl) {

    public VrblRange {
        if (!(minVl <= maxVl)) {
            throw new IllegalArgumentException("계수 범위가 뒤집혔습니다: min=" + minVl + ", max=" + maxVl);
        }
    }
}
