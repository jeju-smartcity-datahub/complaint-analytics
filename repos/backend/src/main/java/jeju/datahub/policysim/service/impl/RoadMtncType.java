package jeju.datahub.policysim.service.impl;

/**
 * 이면도로 정비 방식 (TSK-4038 Step 3 · Step 4 요청 {@code roadMtncType}). 저장 칸 {@code road_mtnc_type} 에 코드값 그대로 담긴다.
 *
 * <p>🔴 <b>방식이 결과에 영향을 준다</b>(계획서 §0-2 수정 2) — 100m당 확보 구획 {@code n_100} 이 갈린다.
 * 명세는 두 방식의 계산식이 같다고 했으나, 주차장법 시행규칙 제3조의 주거지역 도로 2.0×5.0m 를 반영했다.
 */
public enum RoadMtncType {

    /** 주거지역형 — n_100 = 100 ÷ 5.0 × 0.72 = 14.4 ({@code N100_RSDNC}) */
    RSDNC,

    /** 상업지역형 — n_100 = 100 ÷ 6.0 × 0.72 = 12 ({@code N100_CMRC}) */
    CMRC;

    /** 이 방식의 100m당 확보 구획 n_100 */
    public double n100(RepairCoef coef) {
        return this == RSDNC ? coef.n100Rsdnc() : coef.n100Cmrc();
    }
}
