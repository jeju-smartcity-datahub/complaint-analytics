package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 요금제 보고서의 엑셀 재계산 데이터 {@code wbk}.
 *
 * <p>🔴 <b>주차장 목록을 복제하지 않는다</b> — 재계산 입력(배정 민원·면수·시군구·증가율)은 응답 {@code lots[]} 에 이미 있다.
 * 여기에는 개월수 · 고정 상수 · 계수 10행만 싣는다.
 * 🔁 2026-11-11 — 기준선 원값({@code zoneBase})을 뺐다(읍면동별 요금제 대상 판정을 엑셀에 두지 않는다).
 */
@Schema(description = "요금제 엑셀 재계산 데이터 (개월수 + 고정 상수 + 계수)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnFeeWorkbookVO {

    /** 민원 창 개월수 — {@code PrdMonths} 값 그대로(소수). 월배정 민원 {@code mm = 배정 ÷ months} 의 분모 */
    @Schema(description = "민원 창 개월수 (소수)", example = "1.005376")
    private BigDecimal months;

    /** 엑셀에서 바꿀 수 없는 상수 */
    @Schema(description = "고정 상수")
    private ZoneRcmdtnFeeWorkbookFixedVO fixed;

    /** 분석 계수 — {@code AnlsVrblScope.RECOMMEND_FEE} 10행 순서 */
    @Schema(description = "분석 계수 (적용값·범위·입력여부)")
    private List<AnlsWorkbookVrblVO> vrbls;
}
