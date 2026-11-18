package jeju.datahub.analysis.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 공유주차제 엑셀 재계산 원자료 1행 = 읍면동 1곳 (TSK-4340 D7).
 *
 * <p>🔴 {@code rcntCnt}·{@code bfrCnt} 는 <b>서버가 판정에 쓴 블록</b>({@code ZoneRcmdtnShareCalculator.blockOf})의 건수다 —
 * PRIMARY 면 조회 기간·직전 기간, FALLBACK 이면 반 분할 뒤·앞, NONE 이면 둘 다 {@code null}.
 * 원천 VO 의 {@code bfrCvlcptCnt}(항상 직전 기간)와 뜻이 달라 이름을 따로 둔다(최근 {@code RCNT} · 이전 {@code BFR} · 수 {@code CNT}).
 */
@Schema(description = "공유주차제 엑셀 원자료 1행 (읍면동)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnShareWorkbookRowVO {

    @Schema(description = "읍면동코드", example = "5011025000")
    private String emdCd;

    @Schema(description = "시군구명", example = "제주시")
    private String sggNm;

    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;

    /** 조회 기간 주차 민원 건수 */
    @Schema(description = "민원 건수", example = "320")
    private Long cvlcptCnt;

    @Schema(description = "공영주차장 개소 수", example = "12")
    private Long pkltCnt;

    @Schema(description = "공영 주차면수", example = "850")
    private Long pkltCpcty;

    /** 민원 최다 좌표 1곳의 건수 — 주의 표시 {@code TOP_PNT} 의 입력 */
    @Schema(description = "최다 지점 민원 건수", example = "40")
    private Long topPntCvlcptCnt;

    /** 공한지 제외 면수 — 공한지 제외 후보 판정의 입력 */
    @Schema(description = "공한지 제외 주차면수", example = "700")
    private Long vaclotExclPkltCpcty;

    /** 증가율 최근 블록 건수. {@code trendMode=NONE} 이면 {@code null} */
    @Schema(description = "최근 블록 민원 건수 (NONE 이면 null)", nullable = true, example = "320")
    private Long rcntCnt;

    /** 증가율 이전 블록 건수. {@code trendMode=NONE} 이면 {@code null} */
    @Schema(description = "이전 블록 민원 건수 (NONE 이면 null)", nullable = true, example = "280")
    private Long bfrCnt;
}
