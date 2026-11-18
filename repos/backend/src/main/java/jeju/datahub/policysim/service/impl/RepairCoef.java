package jeju.datahub.policysim.service.impl;

/**
 * 이면도로 정비 계수 4종의 적용값 (INSERT).
 *
 * <p>값의 조달(행 존재 확인 · {@code use_yn}/NULL 폴백 · 누락 시 503)은 구현이 {@link PolicySimParams} 해석 규칙으로 한다 —
 * 이 record 는 계산기 입력만 정한다. {@code vrbl_cd} 는 아래 상수다.
 *
 * @param n100Rsdnc 주거지역형 100m당 확보 구획 ({@code N100_RSDNC}, 기본 14.4)
 * @param n100Cmrc  상업지역형 100m당 확보 구획 ({@code N100_CMRC}, 기본 12)
 * @param alphaB    구획 흡수율 α_B ({@code ALPHA_B_VL}, 기본 0.8)
 * @param balloonRt 풍선효과율 s ({@code BALLOON_RT}, 기본 0.2)
 */
public record RepairCoef(double n100Rsdnc, double n100Cmrc, double alphaB, double balloonRt) {

    public static final String N100_RSDNC = "N100_RSDNC";
    public static final String N100_CMRC = "N100_CMRC";
    public static final String ALPHA_B_VL = "ALPHA_B_VL";
    public static final String BALLOON_RT = "BALLOON_RT";

    /** 음수·NaN 은 산식이 뒤집히므로 받지 않는다. 설정 화면 범위(min_vl·max_vl) 검사는 저장 API 가 한다 */
    public RepairCoef {
        if (!(n100Rsdnc >= 0) || !(n100Cmrc >= 0) || !(alphaB >= 0) || !(balloonRt >= 0 && balloonRt <= 1)) {
            throw new IllegalArgumentException("이면도로 계수가 유효하지 않습니다: n100Rsdnc=" + n100Rsdnc
                    + ", n100Cmrc=" + n100Cmrc + ", alphaB=" + alphaB + ", balloonRt=" + balloonRt);
        }
    }
}
