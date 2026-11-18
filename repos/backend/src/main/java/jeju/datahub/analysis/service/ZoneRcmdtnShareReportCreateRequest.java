package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 <b>공유주차제 적용</b> 보고서 생성 요청.
 * (POST /api/analysis/zone-recommendation/share/reports)
 *
 * <p>{@link ZoneRcmdtnReportCreateRequest}(요금제 적용)와 같은 형식이다 — <b>조회 조건과 파일형식만</b> 받고
 * 판정·순번을 받지 않는다. 서버가 같은 조건으로 다시 계산해 그 결과를 저장한다. 요청 조건이 달라
 * (읍면동 필수 + 단속 창 + 유형 범위 ↔ 시군구 필터) 타입을 가른다.
 *
 * <p>🔴 <b>읍면동 필터({@code emdCd})를 받지 않는다</b> — 화면에서 그 칸을 걷었다(2026-10-13). 조회 API
 * ({@code GET /api/analysis/zone-recommendation/share})에는 그 파라미터가 남아 있지만 보고서는 시군구 필터까지다.
 *
 * <p>⚠️ 날짜의 실재 여부(예: {@code 20251232}) · 순서(종료 ≥ 시작) · 시군구명의 길이(40자)와 실재 여부는
 * 여기서 보지 않는다 — 조회와 같은 서비스({@link ZoneRcmdtnShareService#selectShare})가 같은 문구로 400 을 낸다
 * (같은 오류를 두 곳에서 다른 문구로 내지 않는다). 기간 상한(36개월)은 컨트롤러가 본다.
 */
@Schema(description = "구역추천 공유주차제 적용 보고서 생성 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnShareReportCreateRequest {

    /** 민원 창 시작일 (필수). 🔴 8자리 숫자만 통과한다 — 이 값이 실행 행의 {@code anls_bgng_ymd} 로 그대로 간다 */
    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "민원 조회 시작일 (YYYYMMDD)", example = "20260101",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    /** 민원 창 종료일 (필수 · 당일 포함). 🔴 8자리 숫자만 통과한다 — 실행 행의 {@code anls_end_ymd} 로 그대로 간다 */
    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "민원 조회 종료일 (YYYYMMDD, 포함)", example = "20260831",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    /** 시군구명 필터. 생략하거나 비우면(공백뿐 포함) 걸지 않는다 — 전체 지역. 없는 이름이면 서비스가 400 */
    @Schema(description = "시군구명 필터(40자 이내). 생략하거나 비우면 전체 지역. 없는 이름이면 400", example = "제주시")
    private String sggNm;

    /**
     * 분석 계수 일시 입력(선택). 조회의 JSON 문자열과 달리 바디 객체다.
     *
     * <p>화면은 마지막으로 성공한 조회의 값을 보낸다. 생략하면 설정값이다. 규칙은 조회와 같다.
     */
    @Schema(description = "분석 계수 일시 입력 (선택 — 4개 — CVLCPT_MM_CRTR_VL·CVLCPT_RT_CRTR_VL·CVLCPT_INCRS_WGVL·CVLCPT_INCRS_RT_UPLMT_VL. 생략 = 설정값)",
            example = "{\"CVLCPT_RT_CRTR_VL\":10}")
    private Map<String, BigDecimal> vrblVls;

    /** 파일형식 — PDF | DOCX | XLSX. 그 밖의 값·누락은 400 */
    @NotBlank(message = "fileFmt 는 필수입니다")
    @Pattern(regexp = "^(PDF|DOCX|XLSX)$", message = "fileFmt 는 PDF, DOCX, XLSX 중 하나여야 합니다")
    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF",
            allowableValues = {"PDF", "DOCX", "XLSX"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileFmt;
}
