package jeju.datahub.analysis.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 투자우선순위 엑셀 재계산 데이터의 주차장 1행 — {@code wbk.rows[]}.
 *
 * <p>백분위 개수 {@code k} = 자기보다 값이 <b>엄격히</b> 작은 대상 수(모집단은 필터 전 전 지역 {@code wbk.trgtCnt}).
 * 엑셀은 {@code 백분위 = k ÷ (n − 1) × 100} 을 다시 만들지 않고 분자 {@code 100·w·k} 에 그대로 쓴다(나눗셈 1번).
 * 🔴 응답의 지표 점수({@code cvlcptFrqScr} 등)는 6자리 반올림값이라 9자리 종합점수·동점 판정에 쓸 수 없다.
 *
 * <p>⚠️ {@code *LtCnt} 는 <b>임시 이름</b>이다 — 데이터표준사전 v1.0 에 단어 {@code 미만}·{@code 하위} 가 없다
 * 의미어(민원 {@code CVLCPT} · 이용 {@code UTZTN} ·
 * 사고 {@code ACDNT}) + 임시 {@code LT}(미만) + 형식어 수 {@code CNT}. JSON 키이며 DB 컬럼이 아니다.
 * 단속 {@code k} 는 싣지 않는다.
 */
@Schema(description = "엑셀 주차장 1행 (백분위 개수 k)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class InvestPrrtWorkbookRowVO {

    /** 주차장 ID — 응답 {@code lots[].pkltId} 와 같다 */
    @Schema(description = "주차장 ID", example = "101")
    private Long pkltId;

    /** 민원 건수의 백분위 개수 {@code k}. ⚠️ 임시 이름 */
    @Schema(description = "민원 백분위 개수 k (자기보다 민원이 엄격히 적은 대상 수)", example = "120")
    private Integer cvlcptLtCnt;

    /** 일평균 회전율의 백분위 개수 {@code k}(회전율 없음 = 0 으로 본 값 기준). ⚠️ 임시 이름 */
    @Schema(description = "이용 백분위 개수 k (자기보다 회전율이 엄격히 낮은 대상 수)", example = "200")
    private Integer utztnLtCnt;

    /** 사고 건수의 백분위 개수 {@code k}. ⚠️ 임시 이름 */
    @Schema(description = "사고 백분위 개수 k (자기보다 사고가 엄격히 적은 대상 수)", example = "0")
    private Integer acdntLtCnt;
}
