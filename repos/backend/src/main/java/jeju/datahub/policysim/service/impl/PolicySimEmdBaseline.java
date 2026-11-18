package jeju.datahub.policysim.service.impl;

/**
 * 읍면동 1곳의 기준선 (TSK-4037 Step 2). 값은 반올림 전 원값이다 — 반올림은 응답·저장 조립 한 곳에서만 한다(Step 4).
 *
 * @param c0       C0 — 월평균 주차민원 (건/월)
 * @param enf      Enf — 월평균 가중 단속 (건/월)
 * @param e0       E0 — 불법주차 추정 (대) = m × Enf ÷ 30 × g_sgg
 * @param u0       U0 — 정상 이용 (대) = S × occ
 * @param s        S — 공영주차면수
 * @param gSgg     이 읍면동에 적용한 좌표 결측 보정계수
 * @param k        k = C0 ÷ E0 — {@code NO_ENF} 면 null
 * @param h0       H0 = (U0 + E0) ÷ S — {@code S = 0} 이면 null ({@code NO_ENF} 라도 S &gt; 0 이면 값)
 * @param calcStts null = 정상 · NO_ENF · NO_SPLY
 */
public record PolicySimEmdBaseline(
        String emdCd,
        String sggNm,
        String emdNm,
        double c0,
        double enf,
        double e0,
        double u0,
        long s,
        double gSgg,
        Double k,
        Double h0,
        PolicySimCalcStts calcStts) {
}
