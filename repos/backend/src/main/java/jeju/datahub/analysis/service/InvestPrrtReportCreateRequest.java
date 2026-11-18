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
 * 투자우선순위 보고서 생성 요청. (POST /api/analysis/investment-priority/reports — TSK-4036 Step 7)
 *
 * <p>{@link HotspotReportCreateRequest} 와 같은 형식이다 — <b>조회 조건과 파일형식만</b> 받고 점수·순위를
 * 받지 않는다. 서버가 같은 조건으로 다시 분석해 그 결과를 저장한다(화면이 보낸 순위를 굳히지 않는다).
 *
 * <p>집중구역과 다른 점은 <b>기간이 선택</b>이라는 것이다 — 조회 API 와 같은 규칙으로, 둘 다 생략하면
 * "오늘 기준 최근 1년" 이고 한쪽만 오면 400 이다(그 판정은 {@link InvestPrrtService#analyze} 가 한다).
 * 화면은 기간 컨트롤이 없어 날짜를 보내지 않는다.
 */
@Schema(description = "투자우선순위 보고서 생성 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class InvestPrrtReportCreateRequest {

    /** 시군구명 필터. 생략(빈 값 포함) = 전 지역 */
    @Schema(description = "시군구명 (생략 = 전 지역). 보고서에 담을 주차장을 거른다 — 점수·순위는 바뀌지 않는다",
            example = "제주시")
    private String sggNm;

    /** 읍면동코드 필터. 생략(빈 값 포함) = 시군구 전체 */
    @Schema(description = "읍면동코드 8자리 (생략 = 시군구 전체). 없는 코드이거나 sggNm 과 어긋나면 400",
            example = "50110250")
    private String emdCd;

    /** 분석 시작일. {@code endYmd} 와 함께 주거나 함께 생략한다 */
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 시작일 (YYYYMMDD). endYmd 와 함께 생략하면 오늘에서 12개월 전", example = "20260713")
    private String bgngYmd;

    /** 분석 종료일(당일 포함). {@code bgngYmd} 와 함께 주거나 함께 생략한다 */
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 종료일 (YYYYMMDD, 당일 포함). bgngYmd 와 함께 생략하면 오늘", example = "20261013")
    private String endYmd;

    /**
     * 분석 계수 일시 입력(선택, TSK-4325 D1). 조회의 JSON 문자열과 달리 바디 객체다.
     *
     * <p>화면은 마지막으로 성공한 조회의 값을 보낸다(D7). 생략하면 설정값이다. 규칙은 조회와 같다.
     */
    @Schema(description = "분석 계수 일시 입력 (선택 — 가중치 SAFETY·COMPLAINT·USAGE·ACCIDENT, 계수 ALTMNT_RNG_VL·"
            + "UP_GRD_CRTR_SCR·MD_GRD_CRTR_SCR·INDCT_PKLT_CNT. 생략 = 설정값)",
            example = "{\"ALTMNT_RNG_VL\":500}")
    private Map<String, BigDecimal> vrblVls;

    /** 파일형식 — PDF | DOCX | XLSX(🆕 TSK-4343). 그 밖의 값·누락은 400 */
    @NotBlank(message = "fileFmt 는 필수입니다")
    @Pattern(regexp = "^(PDF|DOCX|XLSX)$", message = "fileFmt 는 PDF, DOCX 또는 XLSX 여야 합니다")
    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF",
            allowableValues = {"PDF", "DOCX", "XLSX"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileFmt;
}
