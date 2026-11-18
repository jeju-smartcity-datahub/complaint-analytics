package jeju.datahub.lifezone.service;

import java.math.BigDecimal;
import java.util.Map;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 혼잡도 조회 요청.
 *
 * <p>필드는 {@link LifezoneSupplyRequest}({@code gridSz}·{@code bgngYmd}·{@code endYmd}·{@code sggNm}·
 * {@code emdCd})와 같고 {@code clsfMthd}·{@code limit} 을 더한다.
 *
 * <p>🔴 컨트롤러에서 {@code @Valid} 를 빠뜨리면 아래 제약이 통째로 발동하지 않는다. 서비스가
 * 격자크기·날짜·분류방법·지역을 다시 검증하고 {@code limit} 을 하한 1 로 묶으므로 서비스 직접 호출도 안전하다.
 */
@Schema(description = "생활권 혼잡도 조회 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneCngstRequest {

    @Schema(description = "격자크기(m) — 1000 | 500 | 250 | 100", example = "500", defaultValue = "500")
    private int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;

    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20251101",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 종료일 (YYYYMMDD, 당일 포함)", example = "20260630",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    @Schema(description = "시군구명 (생략 = 전 지역)", example = "제주시")
    private String sggNm;

    @Schema(description = "읍면동코드 (생략 = 시군구 전체)", example = "50110137")
    private String emdCd;

    /**
     * 등급 분류방법. 🔴 초기값 {@code null} = 유형 기본값({@code CNGST_ABSOLUTE}).
     * 허용 목록은 {@code CNGST_ABSOLUTE} 1종이고 밖이면 400 이다.
     */
    @Schema(description = "등급 분류방법 — 생략 시 유형 기본값(혼잡도 CNGST_ABSOLUTE). 허용 목록은 /targets 의 clsfMthds",
            example = "CNGST_ABSOLUTE", nullable = true)
    private String clsfMthd;

    /**
     * 순위 목록 개수(선택). <b>생략하면 상한 없음</b>(심각·경고·주의 셀 전부),
     * 지정하면 그 값까지(1 이상 · 종전 상한 30 은 없앴다). 화면은 보내지 않는다.
     */
    @Min(value = 1, message = "limit 은 1 이상이어야 합니다")
    @Schema(description = "순위 개수 (생략 = 심각·경고·주의 전부, 지정 시 1 이상)", example = "15", nullable = true)
    private Integer limit;

    /**
     * 분석 계수 일시 입력 — JSON 문자열(선택). 예: {@code {"PCSHR_CRTR_VL":0.8,"MIN_CPCTY_VL":10}}.
     *
     * <p>생략하면 설정값이다. 🔴 PCSHR_CRTR_VL·CNGST_GRD_BNDRY1~4·DSCL_MLTP·FIX_WGVL·GEO_CVRG_CRCT_VL·CVLCPT_WGVL·MIN_CPCTY_VL 만 받는다 — 목록 밖 코드·범위 밖·소수(정수 전용)·경계 역전은 400 이다.
     * 판정은 {@code AnlsVrblOvrdResolver} 한 곳이다. 설정 표에는 쓰지 않는다.
     */
    @Schema(description = "분석 계수 일시 입력 (JSON 문자열, 선택 — PCSHR_CRTR_VL·CNGST_GRD_BNDRY1~4·DSCL_MLTP·FIX_WGVL·GEO_CVRG_CRCT_VL·CVLCPT_WGVL·MIN_CPCTY_VL 만. 생략 = 설정값)",
            example = "{\"PCSHR_CRTR_VL\":0.8,\"MIN_CPCTY_VL\":10}")
    private String vrblVls;

    /**
     * 보고서 경로 전용 입력값 — 보고서 요청의 {@code toAnalysisRequest()} 만 채운다.
     * 서비스는 이 값을 {@link #vrblVls} 보다 먼저 본다.
     *
     * <p>🔴 세터를 공개하지 않는다 — 공개하면 쿼리 {@code vrblVlsMap[KEY]=값} 이 바인딩되고 함께 온
     * {@link #vrblVls} 가 무시된다.
     */
    @Parameter(hidden = true)
    @Setter(AccessLevel.PACKAGE)
    private Map<String, BigDecimal> vrblVlsMap;
}
