package jeju.datahub.complaint.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 위험단계별 알림 건수. (parking.TB_RISK_STP_NOTI_HSTRY 집계 — MyBatis 조회 전용 VO)
 *
 * <p>단계 이름(심각/경고/주의)은 내리지 않는다 — 프론트가 이미 같은 화면의 위험단계
 * 필터를 {@code RISK_LEVELS} 상수로 그리고 있어, 여기서 또 내리면 라벨 출처가 두 벌이 된다(TSK-4058 D6).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskLevelCountVO {

    /** 위험단계코드 (RISK_LVL_CD — CRIT/WARN/CAUT) */
    private String riskLvlCd;

    /** 해당 단계 알림 건수(현재 기간) */
    private long count;
}
