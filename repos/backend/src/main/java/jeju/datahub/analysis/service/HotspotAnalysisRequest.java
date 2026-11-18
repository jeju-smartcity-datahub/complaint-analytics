package jeju.datahub.analysis.service;

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
 * 불법주차 집중구역 분석 조회 요청. (GET /api/analysis/hotspot)
 *
 * <p><b>GET 인 이유</b> — 분석 결과를 저장하지 않는 순수 조회다(TSK-4032 D7).
 * 파라미터가 URL 에 남아 링크 공유·재현이 쉽다. 쿼리스트링을 이 클래스로 바인딩하므로
 * 컨트롤러에서 {@code @Valid} 를 빠뜨리면 아래 제약이 전혀 발동하지 않는다.
 *
 * <p>지역 조건({@link #sggNm}·{@link #emdCd})은 <b>키를 아예 넣지 않는 것</b>이 "전체"다 —
 * 빈 문자열은 400 이다. 값이 실제로 존재하는 지역인지(미등록 시군구·미존재 읍면동)는
 * 여기서 판정하지 않고 서비스 계층이 검증한다.
 */
@Schema(description = "불법주차 집중구역 분석 조회 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotAnalysisRequest {

    /** 시군구명(선택). 생략하면 전 지역. 예: {@code 제주시} */
    @Schema(description = "시군구명 (생략 = 전 지역)", example = "제주시")
    private String sggNm;

    /**
     * 읍면동코드(선택). 생략하면 선택 시군구 전체.
     *
     * <p>지역 필터는 원천 테이블의 {@code emd_cd} 로 <b>군집화 전에</b> 거른다(D19).
     */
    @Schema(description = "읍면동코드 (생략 = 시군구 전체)", example = "50110560")
    private String emdCd;

    /** 분석 시작일 (YYYYMMDD, 필수) */
    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20251201", requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    /** 분석 종료일 (YYYYMMDD, 필수 · 당일 포함) */
    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 종료일 (YYYYMMDD, 당일 포함)", example = "20251231", requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    /**
     * 결과 구역 개수. <b>미지정이면 전량</b> — 상한이 없다 (TSK-4172 D8).
     *
     * <p>순위 목록과 지도 마커는 <b>같은 집합</b>이라 이 값 하나가 둘 다를 정한다.
     * 🔁 종전의 상한 30 을 걷었다 — "주의 이상 전부" 를 보여줘야 하므로 개수를 묶지 않고,
     * 대신 표시 하한({@link HotspotGrade#MIN_DISPLAYED})이 결과 수를 줄인다.
     *
     * <p>🔴 기본값을 두지 않는다({@code null} = 전량). {@code @Min(1)} 은 남긴다 —
     * {@code null} 은 통과하고 0·음수만 400 이다.
     */
    @Min(value = 1, message = "limit 은 1 이상이어야 합니다")
    @Schema(description = "결과 구역 개수 (미지정이면 전량 — 표시 하한 이상 모두)", example = "15")
    private Integer limit;

    /**
     * 민원 가중치(%, 선택) — 분석 화면에서 일시 조정한 값이다 (TSK-4306).
     *
     * <p>{@link #crdnWgvl} 과 <b>함께</b> 보내거나 함께 생략한다. 둘 다 생략하면 설정값으로 분석한다(D1).
     * 한쪽만 · 합계가 100 이 아님 · 설정 범위 밖 · 소수 셋째 자리 이상 · 꺼진 지표가 있음은 400 이다(D2·D3) —
     * 판정은 {@code HotspotWeightResolver} 한 곳이다. 설정 표에는 쓰지 않는다.
     */
    @Schema(description = "민원 가중치 (%, 선택 — crdnWgvl 과 함께 보내며 합계 100. 둘 다 생략 = 설정값)", example = "80")
    private BigDecimal cvlcptWgvl;

    /** 단속 가중치(%, 선택). 규칙은 {@link #cvlcptWgvl} 과 같다 (TSK-4306). */
    @Schema(description = "단속 가중치 (%, 선택 — cvlcptWgvl 과 함께 보내며 합계 100. 둘 다 생략 = 설정값)", example = "20")
    private BigDecimal crdnWgvl;

    /**
     * 분석 계수 일시 입력 — JSON 문자열(선택, TSK-4325 D1). 예: {@code {"GRD_BNDRY1":3,"GRD_BNDRY2":7,"GRD_BNDRY3":12}}.
     *
     * <p>생략하면 설정값이다. 🔴 집중구역은 등급 비율 {@code GRD_BNDRY1~3} 만 받는다 — 가중치는
     * {@link #cvlcptWgvl}·{@link #crdnWgvl} 이 맡고, 여기에 {@code COMPLAINT} 등을 보내면 400 이다(D2).
     * 판정은 {@code AnlsVrblOvrdResolver} 한 곳이다. 설정 표에는 쓰지 않는다.
     */
    @Schema(description = "분석 계수 일시 입력 (JSON 문자열, 선택 — GRD_BNDRY1~3 만. 생략 = 설정값)",
            example = "{\"GRD_BNDRY1\":3,\"GRD_BNDRY2\":7,\"GRD_BNDRY3\":12}")
    private String vrblVls;

    /**
     * 보고서 경로 전용 입력값 — {@code HotspotReportCreateRequest#toAnalysisRequest()} 만 채운다(TSK-4325 Step 1).
     * 서비스는 이 값을 {@link #vrblVls} 보다 먼저 본다.
     *
     * <p>🔴 세터를 공개하지 않는다 — 공개하면 쿼리 {@code vrblVlsMap[KEY]=값} 이 바인딩되고 함께 온
     * {@link #vrblVls} 가 무시된다.
     */
    @Parameter(hidden = true)
    @Setter(AccessLevel.PACKAGE)
    private Map<String, BigDecimal> vrblVlsMap;
}
