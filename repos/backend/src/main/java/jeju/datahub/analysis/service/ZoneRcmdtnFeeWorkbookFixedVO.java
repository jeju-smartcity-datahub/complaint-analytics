package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 요금제 엑셀의 고정 상수 (TSK-4340 D1·D6) — 이 화면의 계수가 아니라 엑셀에서 바꿀 수 없는 값.
 *
 * <p>🔁 2026-11-11 — 읍면동 판정 상수(적발배수·기준점유율·유료수요율)를 뺐다(엑셀에 판정을 두지 않는다).
 */
@Schema(description = "요금제 엑셀 고정 상수")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnFeeWorkbookFixedVO {

    /** 종합지수 단계 '높음' 하한 — {@code ZoneRcmdtnCalculator.PRRT_TOP} */
    @Schema(description = "종합지수 높음 하한", example = "100")
    private BigDecimal prrtTop;

    /** 종합지수 단계 '중간' 하한 — {@code ZoneRcmdtnCalculator.PRRT_HIGH} */
    @Schema(description = "종합지수 중간 하한", example = "60")
    private BigDecimal prrtHigh;
}
