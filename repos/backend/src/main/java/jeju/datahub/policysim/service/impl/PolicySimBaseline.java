package jeju.datahub.policysim.service.impl;

import java.time.LocalDate;
import java.util.List;

/**
 * 기준선 산정 결과 전체 (TSK-4037 Step 2) — 읍면동별 값 + 산출에 쓴 계수·구간.
 *
 * <p>계수({@code params.toMap()})와 구간은 {@code aplcn_param_json} 에 담길 재료다(Step 1-2) —
 * 구간은 실행일에 따라 움직이므로 함께 남기지 않으면 과거 결과를 재현할 수 없다.
 *
 * @param params           실제 적용 계수 13종
 * @param crdnBgngYmd      단속 구간 시작 (포함)
 * @param crdnEndYmd       단속 구간 끝 (포함)
 * @param crdnMonths       단속 나눗수 M
 * @param cvlcptBgngYmd    민원 구간 시작 (포함) — 민원 0건이면 null
 * @param cvlcptEndYmd     민원 구간 끝 (포함) — 민원 0건이면 null
 * @param cvlcptMonths     민원 나눗수 — 구간 중 보유 개월수. 민원 0건이면 0
 * @param emds             읍면동별 기준선 (tb_admdst 전 행)
 */
public record PolicySimBaseline(
        PolicySimParams params,
        LocalDate crdnBgngYmd,
        LocalDate crdnEndYmd,
        int crdnMonths,
        LocalDate cvlcptBgngYmd,
        LocalDate cvlcptEndYmd,
        int cvlcptMonths,
        List<PolicySimEmdBaseline> emds) {
}
