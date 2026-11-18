package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** 공유주차제 엑셀의 고정 상수 (TSK-4340 D7) — 설정 변수가 아니라 코드 상수다. */
@Schema(description = "공유주차제 엑셀 고정 상수")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnShareWorkbookFixedVO {

    /** 핵심 후보 배수 — {@code ZoneRcmdtnShareCalculator.CORE_MLTP}(응답 {@code crtrVls.coreMltp} 와 같다) */
    @Schema(description = "핵심 후보 배수", example = "2")
    private BigDecimal coreMltp;

    /** 기준 경계 폭 — {@code ZoneRcmdtnShareCalculator.BNDRY_RT}. 주의 표시 {@code CRTR_BNDRY} 의 기준 배율 */
    @Schema(description = "기준 경계 폭 (CRTR_BNDRY 배율)", example = "1.05")
    private BigDecimal bndryRt;

    /** 증가율 분모 하한 — {@code ZoneRcmdtnCalculator.MIN_TREND_BASE}. 이전 블록이 이보다 작으면 증가율 없음 */
    @Schema(description = "증가율 분모 하한 (건)", example = "10")
    private Integer minTrendBase;
}
