package jeju.datahub.analysis.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 투자우선순위 보고서의 엑셀 재계산 데이터 {@code wbk} (TSK-4343 D5·D6).
 *
 * <p>🔴 <b>주차장 목록을 복제하지 않는다</b> — 이름·주소·면수·안전 점수({@code sftyRiskScr})는 응답 {@code lots[]} 에 이미 있다(D6).
 * 여기에는 백분위 모집단 수 {@link #trgtCnt} · 주차장별 백분위 개수 {@link #rows} · 계수 8행 {@link #vrbls} 만 싣는다.
 *
 * <p>🔴 백분위 모집단은 <b>필터 전 전 지역</b>이다 — 보고서 {@code lots} 는 필터 통과분뿐이라 엑셀이 {@code k} 를
 * 다시 셀 수 없다. 그래서 서버가 계산한 {@code k}(정수)와 {@code n} 을 내린다(계획서 1-2).
 */
@Schema(description = "투자우선순위 엑셀 재계산 데이터 (모집단 수 + 주차장별 백분위 개수 + 계수)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class InvestPrrtWorkbookVO {

    /** 백분위 모집단 {@code n} — 계산기에 넣은 대상 주차장 수(🔴 행정구역 필터 <b>전</b> 전 지역). 응답 {@code smry.trgtCnt} 와 같은 이름·같은 값 */
    @Schema(description = "백분위 모집단 n (필터 전 전 지역 대상 수)", example = "509")
    private Integer trgtCnt;

    /** 주차장별 백분위 개수 — 🔴 응답 {@code lots} 와 <b>같은 주차장·같은 순서</b>(필터 통과분 · 순위 오름차순) */
    @Schema(description = "주차장별 백분위 개수 k (lots 와 같은 주차장·같은 순서)")
    private List<InvestPrrtWorkbookRowVO> rows;

    /** 분석 계수 — {@code AnlsVrblScope.INVEST} 8행 순서 */
    @Schema(description = "분석 계수 (적용값·범위·입력여부)")
    private List<AnlsWorkbookVrblVO> vrbls;
}
