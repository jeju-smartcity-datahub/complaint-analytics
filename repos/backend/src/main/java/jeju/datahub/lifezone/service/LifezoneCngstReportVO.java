package jeju.datahub.lifezone.service;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 <b>혼잡도</b> 보고서 = 분석 응답 + 보고서 헤더 7칸. (TSK-4317)
 *
 * <p>🔴 {@code ranking[].anlsVl}·{@code cells[].anlsVl} 은 <b>점수 × 100 정수</b>다(10000 = 100점).
 */
@Schema(description = "생활권 혼잡도 보고서 (분석 응답 + 보고서 헤더)")
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
public class LifezoneCngstReportVO extends LifezoneCngstResultVO {

    @Schema(description = "보고서아이디", example = "101")
    private Long rptpId;

    @Schema(description = "분석실행아이디", example = "101")
    private Long excnId;

    @Schema(description = "보고서명", example = "생활권 혼잡도 보고서 전체 지역 500m")
    private String rptpNm;

    @Schema(description = "지역명 (전체 지역 | {시군구} 전체 | {시군구} {읍면동})", example = "전체 지역")
    private String regionNm;

    @Schema(description = "분석기간설명", example = "2025.11.01 ~ 2026.06.30")
    private String anlsPrdExpln;

    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF")
    private String fileFmt;

    @Schema(description = "생성일시", example = "2026-11-04T01:03:11")
    private LocalDateTime crtYmd;

    /**
     * 엑셀 재계산 데이터 (TSK-4340 D7) — 원자료 + 개월수 + 계수. 형식과 무관하게 새 보고서마다 저장된다.
     * 🔴 <b>필드 초기값을 두지 않는다</b> — 이 값을 저장하기 전 보고서는 {@code null} 이다(D7-1 — 프론트가 안내 문구를 띄운다).
     */
    @Schema(description = "엑셀 재계산 데이터 (원자료 + 개월수 + 계수). 이 값을 저장하기 전 보고서는 null", nullable = true)
    private LifezoneCngstWorkbookVO wbk;
}
