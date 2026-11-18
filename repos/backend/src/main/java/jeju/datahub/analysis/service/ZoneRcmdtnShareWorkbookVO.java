package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 공유주차제 보고서의 엑셀 재계산 데이터 {@code wbk} (TSK-4340 D5·D7).
 *
 * <p>{@code rows} = <b>응답 {@code zones} 와 같은 읍면동</b>(지역 필터 뒤)의 원자료를 {@code cvlcptCnt↓, emdCd↑} 순으로(D8).
 * 🔴 도 전체 {@code smry} 는 재현하지 않는다(D7). {@code rows}·{@code months}·{@code trendMode}·{@code fixed} 는
 * 계산기({@code ZoneRcmdtnShareCalculator.calculateWithWorkbook}), {@code vrbls} 는 서비스가 채운다.
 *
 * <p>⚠️ {@code trendMode} 는 JSON 묶음 키다 — 데이터표준사전 v1.0 에 단어 {@code 추세}·{@code 방식} 이 없어 임시 이름이다.
 */
@Schema(description = "공유주차제 엑셀 재계산 데이터 (표시 읍면동 원자료 + 개월수 + 블록 방식 + 고정 상수 + 계수)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnShareWorkbookVO {

    /** 표시 읍면동 원자료 — 응답 {@code zones} 와 같은 집합 · {@code cvlcptCnt↓, emdCd↑} 순 */
    @Schema(description = "표시 읍면동 원자료 (민원 건수 내림차순 → emdCd 오름차순)")
    private List<ZoneRcmdtnShareWorkbookRowVO> rows;

    /** 월평균 분모 — {@code PrdMonths} 값 그대로(소수). 응답 {@code crtrVls.months} 와 같다 */
    @Schema(description = "개월수 (소수)", example = "1.005376")
    private BigDecimal months;

    /**
     * 증가율 블록 방식 (도 전체 1회 판정 · 계수와 무관) — {@code PRIMARY}(조회 기간·직전 기간) |
     * {@code FALLBACK}(반 분할 뒤·앞) | {@code NONE}(기간 하한 미달 — 증가율 없음 · 계수 1)
     */
    @Schema(description = "증가율 블록 방식 (PRIMARY | FALLBACK | NONE)", example = "PRIMARY")
    private String trendMode;

    /** 엑셀에서 바꿀 수 없는 코드 상수 */
    @Schema(description = "고정 상수")
    private ZoneRcmdtnShareWorkbookFixedVO fixed;

    /** 분석 계수 — {@code AnlsVrblScope.RECOMMEND_SHARE} 4행 순서 */
    @Schema(description = "분석 계수 (적용값·범위·입력여부)")
    private List<AnlsWorkbookVrblVO> vrbls;
}
