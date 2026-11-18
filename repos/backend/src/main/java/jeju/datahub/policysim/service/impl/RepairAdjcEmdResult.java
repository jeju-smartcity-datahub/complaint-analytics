package jeju.datahub.policysim.service.impl;

/**
 * 인접 읍면동 1곳의 풍선효과 후 값. 값은 반올림 전 원값이다.
 *
 * <p>🔴 <b>{@code E0_n = 0}(NO_ENF) 인 인접은 배분에서 빠진다</b> — weight·dltIlgl = 0, e1 = E0, c1·dltCvlcpt 는 null/0.
 *
 * @param base       인접 읍면동 자신의 기준선 (g_sgg·k_n 은 그 읍면동 자기 시 기준)
 * @param weight     배분 가중치 E0_n ÷ Σ E0_j (E0 &gt; 0 인 인접만의 합) — 대상에 가산된 경우 0
 * @param dltIlgl    유입 불법주차 = s × Σ ΔE × weight (대, 양수)
 * @param e1         E1_n = E0_n + dltIlgl
 * @param c1         C1_n = k_n × E1_n — NO_ENF 면 null
 * @param dltCvlcpt  민원 증가분 C1_n − C0_n = k_n × dltIlgl — NO_ENF 면 0 (뺄셈 대신 곱으로 내 부동소수 잔차를 남기지 않는다)
 */
public record RepairAdjcEmdResult(
        PolicySimEmdBaseline base,
        double weight,
        double dltIlgl,
        double e1,
        Double c1,
        double dltCvlcpt) {
}
