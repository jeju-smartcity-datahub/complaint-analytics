package jeju.datahub.complaint.service;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 위험단계 알림 KPI 카드용 집계. (parking.TB_RISK_STP_NOTI_HSTRY)
 *
 * <p>⚠️ 같은 응답의 {@link DashboardKpiVO#getTotal()} 과 <b>모수가 다르다</b> —
 * 저쪽은 민원(TB_CVLCPT), 이쪽은 알림 이력이다. 필터도 갈린다:
 * <ul>
 *   <li>{@code typeCd}(민원유형) — 이 집계에 <b>적용되지 않는다</b>(알림 이력에 유형 컬럼이 없다.)</li>
 *   <li>{@code riskLvlCd}(위험단계) — <b>이 집계에만</b> 적용된다(민원 쪽에서는 제거했다.)</li>
 *   <li>기간 · {@code sggCd} — 양쪽 모두에 적용된다</li>
 * </ul>
 *
 * <p>직전 구간 대비 증감률은 제공하지 않는다 — 카드가 단계별 분해를 대신 보여준다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskAlertKpiVO {

    /** 현재 기간 알림 총 건수 (= breakdown 의 count 합) */
    private long total;

    /** 단계별 분해. 건수 0 인 단계는 원소로 나오지 않는다(GROUP BY 결과 그대로). */
    private List<RiskLevelCountVO> breakdown;
}
