package jeju.datahub.policysim.service.impl;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/** 민원 보유 구간 (매퍼 결과 전용). 민원이 0건이면 둘 다 null. (TSK-4037 Step 2) */
@Getter
@Setter
@ToString
public class PolicySimCvlcptPeriodRow {

    /** 최초 발생일시 {@code min(ocrn_dt)} */
    private LocalDateTime minOcrnDt;

    /** 최종 발생일시 {@code max(ocrn_dt)} */
    private LocalDateTime maxOcrnDt;
}
