package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 <b>요금제 적용</b> 보고서 생성 요청. (POST /api/analysis/zone-recommendation/reports)
 *
 * <p>{@link InvestPrrtReportCreateRequest} 와 같은 형식이다 — <b>조회 조건과 파일형식만</b> 받고 순위·판정을
 * 받지 않는다. 서버가 같은 조건으로 다시 계산해 그 결과를 저장한다(화면이 보낸 순위를 굳히지 않는다).
 *
 * <p>조회 조건은 조회 API({@code GET /api/analysis/zone-recommendation})의 쿼리 파라미터와 <b>같은 이름 · 같은
 * 규칙</b>이다 — 민원 창은 필수, 단속 창과 유형 범위는 생략할 수 있다.
 * 지역은 {@code emdCd}(읍면동 모드) 또는 {@code sggNm}(시군구 모드) <b>중 정확히 하나</b>다.
 * 그 배타 규칙은 여기서 보지 않는다 — 조회와 같은 서비스의 {@code resolveScope} 가 같은 문구로 400 을 낸다
 * (보고서 서비스가 직접 부르지 않는다 · 두 벌 금지).
 *
 * <p>⚠️ 날짜의 실재 여부(예: {@code 20251232}) · 순서(종료 ≥ 시작) · {@code trgtTypeSe} 화이트리스트 ·
 * 읍면동 실재 여부는 여기서 보지 않는다 — 조회와 같은 서비스({@link ZoneRcmdtnService#selectZoneRcmdtn})가
 * 같은 문구로 400/404 를 낸다(같은 오류를 두 곳에서 다른 문구로 내지 않는다).
 * 기간 상한(36개월)은 컨트롤러가 본다.
 *
 * <p>공유주차제 적용 보고서는 요청 조건이 달라 별도 타입을 쓴다.
 */
@Schema(description = "구역추천 요금제 적용 보고서 생성 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnReportCreateRequest {

    /**
     * 읍면동코드 (8자리). 실재하지 않으면 404.
     * 필수가 아니다. {@code sggNm} 과 <b>배타</b>다(둘 다 없음·둘 다 있음은 400).
     */
    @Schema(description = "읍면동코드 8자리 (읍면동 모드). sggNm 과 배타 — 둘 중 정확히 하나. 실재하지 않으면 404",
            example = "50110104")
    private String emdCd;

    /**
     * 시군구명 (시군구 모드). {@code emdCd} 와 <b>배타</b>다. 40자 초과·없는 시군구는 400.
     */
    @Size(max = 40, message = "sggNm 은 40자를 넘을 수 없습니다")
    @Schema(description = "시군구명 (시군구 모드). emdCd 와 배타 — 둘 중 정확히 하나. 없는 시군구는 400",
            example = "제주시")
    private String sggNm;

    /** 민원 창 시작일 (필수) */
    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "민원 조회 시작일 (YYYYMMDD)", example = "20250101",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    /** 민원 창 종료일 (필수 · 당일 포함) */
    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "민원 조회 종료일 (YYYYMMDD, 포함)", example = "20250831",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    /** 단속 창 시작일. 생략하면 조회 API 와 같은 기본값({@link ZoneRcmdtnService#DEFAULT_CRDN_BGNG_YMD}) */
    @Pattern(regexp = "\\d{8}", message = "crdnBgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "단속 창 시작일 (YYYYMMDD). 생략하면 20250101", example = "20250101")
    private String crdnBgngYmd;

    /** 단속 창 종료일(포함). 생략하면 조회 API 와 같은 기본값({@link ZoneRcmdtnService#DEFAULT_CRDN_END_YMD}) */
    @Pattern(regexp = "\\d{8}", message = "crdnEndYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "단속 창 종료일 (YYYYMMDD, 포함). 생략하면 20260831", example = "20260831")
    private String crdnEndYmd;

    /** 후보 주차장 유형 범위 — NARROW(기본) | WIDE. 그 밖의 값은 서비스가 400 으로 낸다 */
    @Schema(description = "후보 주차장 유형 범위 (NARROW | WIDE). 생략하면 NARROW", example = "NARROW",
            allowableValues = {"NARROW", "WIDE"})
    private String trgtTypeSe;

    /**
     * 분석 계수 일시 입력(선택). 조회의 JSON 문자열과 달리 바디 객체다.
     *
     * <p>화면은 마지막으로 성공한 조회의 값을 보낸다. 생략하면 설정값이다. 규칙은 조회와 같다.
     */
    @Schema(description = "분석 계수 일시 입력 (선택 — 10개 — 공통 2(ALTMNT_RNG_VL·SWTC_TRGT_MIN_CPCTY) + FEE_* 8. 생략 = 설정값)",
            example = "{\"ALTMNT_RNG_VL\":500}")
    private Map<String, BigDecimal> vrblVls;

    /** 파일형식 — PDF | DOCX | XLSX. 그 밖의 값·누락은 400 */
    @NotBlank(message = "fileFmt 는 필수입니다")
    @Pattern(regexp = "^(PDF|DOCX|XLSX)$", message = "fileFmt 는 PDF, DOCX, XLSX 중 하나여야 합니다")
    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF",
            allowableValues = {"PDF", "DOCX", "XLSX"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileFmt;
}
