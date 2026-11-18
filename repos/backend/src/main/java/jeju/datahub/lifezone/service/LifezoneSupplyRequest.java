package jeju.datahub.lifezone.service;

import java.math.BigDecimal;
import java.util.Map;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 수급 분석 조회 요청. (GET /api/analysis/lifezone/supply)
 *
 * <p>민원건수 분석({@link LifezoneAnalysisRequest})과 <b>격자 체계·기간 컬럼이 다르다</b>.
 * 같은 요청 클래스를 쓰지 않는 이유는 {@link jeju.datahub.lifezone.service.impl.LifezoneSupplyServiceImpl}
 * 의 클래스 주석에 정리돼 있다.
 *
 * <p>⚠️ {@link LifezoneAnalysisRequest} 와 같은 이유로 <b>클래스에 {@code @Validated} 를 달거나
 * 파라미터에 제약을 직접 붙이지 않는다</b> — 전역 예외 핸들러가 없어
 * {@code ConstraintViolationException} 이 <b>500</b> 으로 나간다. 형식 제약만 여기서 걸고
 * 값 검증(격자크기·기간 역전·미등록 지역)은 서비스가 400 으로 던진다.
 */
@Schema(description = "생활권 수급 분석 조회 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneSupplyRequest {

    /**
     * 격자크기(m). 기본 {@code 500}.
     *
     * <p>🔁 민원건수 분석과 <b>같은 목록</b>({@code 1000/500/250/100})이다(종전
     * {@code 100/200/300/500}). 수급은 {@code TB_GRID} 를 쓰지 않고 EPSG:5179 좌표를 격자크기로 나눠
     * 격자를 <b>계산</b>하므로 어느 해상도든 낼 수 있다 — 화면에서 유형을 바꿔도 같은 격자로 비교되게 맞췄다.
     *
     * <p>기본값이 {@code 500} 인 것은 화면의 기존 기본 격자와 맞추기 위해서다. ⚠️ 100m 는 공급 임계값이
     * 0면이 되어 Q1 이 곧 "공영주차장 없는 격자"가 된다.
     */
    @Schema(description = "격자크기(m) — 1000 | 500 | 250 | 100", example = "500", defaultValue = "500")
    private int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;

    /** 분석 시작일 (YYYYMMDD, 필수) */
    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20250101",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    /** 분석 종료일 (YYYYMMDD, 필수 · 당일 포함) */
    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 종료일 (YYYYMMDD, 당일 포함)", example = "20250831",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    /**
     * 시군구명(선택). 생략하면 전 지역.
     *
     * <p>키를 <b>아예 넣지 않는 것</b>이 "전체"다 — 빈 문자열은 400 이다(민원건수 분석과 같다).
     */
    @Schema(description = "시군구명 (생략 = 전 지역)", example = "제주시")
    private String sggNm;

    /** 읍면동코드(선택). 생략하면 선택 시군구 전체 */
    @Schema(description = "읍면동코드 (생략 = 시군구 전체)", example = "50110137")
    private String emdCd;

    /**
     * 분석 계수 일시 입력 — JSON 문자열(선택). 예: {@code {"SPLY_MIN_MNTH_AVG_CVLCPT":3,"SPLY_GRD_BNDRY4":85}}.
     *
     * <p>생략하면 설정값이다. 🔴 SPLY_MIN_MNTH_AVG_CVLCPT·SPLY_GRD_BNDRY1~4 만 받는다 — 목록 밖 코드·범위 밖·소수(정수 전용)·경계 역전은 400 이다.
     * 판정은 {@code AnlsVrblOvrdResolver} 한 곳이다. 설정 표에는 쓰지 않는다.
     */
    @Schema(description = "분석 계수 일시 입력 (JSON 문자열, 선택 — SPLY_MIN_MNTH_AVG_CVLCPT·SPLY_GRD_BNDRY1~4 만. 생략 = 설정값)",
            example = "{\"SPLY_MIN_MNTH_AVG_CVLCPT\":3,\"SPLY_GRD_BNDRY4\":85}")
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
