package jeju.datahub.policysim.service;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 정책 시뮬레이션 요청 (TSK-4037 Step 5) — {@code GET /api/analysis/policy-sim} 쿼리스트링 ·
 * {@code POST /api/analysis/policy-sim/results} 바디 공용.
 *
 * <p>형식(필수·패턴)만 여기서 걸고({@code @Valid} → 400), <b>허용 값 목록</b>(면수 5종·기간 4종)과
 * 미구현 정책 유형은 서비스가 {@code ResponseStatusException} 400 으로 낸다 — 생활권 요청과 같은 두 겹이다.
 *
 * <p>⚠️ 클래스에 {@code @Validated} 를 달거나 컨트롤러 파라미터에 제약을 직접 붙이지 않는다 —
 * {@code ConstraintViolationException} 이 500 으로 나간다({@code LifezoneAnalysisRequest} 주석 참고).
 *
 * <p>지역: 둘 다 생략 = 제주 전체 · {@code sggNm} 만 = 시 전체 · {@code emdCd} = 그 읍면동 하나.
 * {@code emdCd} 가 오면 {@code sggNm} 은 무시하고 서버가 {@code TB_ADMDST} 값으로 채운다.
 * 키를 <b>아예 넣지 않는 것</b>이 "전체"다 — 빈 문자열은 패턴 위반으로 400 이다.
 *
 * <p>🔴 <b>정책 유형별 필수 필드는 클래스 단위 검증({@link #isValidForType()})이다</b>(TSK-4038 Step 4-1) —
 * {@code EXPAND} 는 {@code expandMethod}·{@code addPkltSlot}, {@code REPAIR} 는 {@code roadMtncType}·{@code roadMtncLen},
 * {@code FEE} 는 {@code feeAplcnMthd}(+ {@code RAISE} 면 {@code feeIncrsRt}) (TSK-4039 Step 5-1).
 * 필드 단위 {@code @NotNull} 로 두면 다른 유형 요청이 전부 400 이다. {@code predcMm} 은 세 유형 공통 필수다
 * ({@code predc_mm NOT NULL} · 차트 가로축 — REPAIR·FEE 계산에는 쓰지 않는다). 다른 유형의 필드가 와도 무시한다.
 */
@Schema(description = "정책 시뮬레이션 요청 (주차장 확충 EXPAND · 이면도로 정비 REPAIR · 요금제 적용 FEE)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PolicySimRequest {

    /** 정책유형코드 (공통코드 PLCY_TYPE_CD) — EXPAND(TSK-4037) · REPAIR(TSK-4038) · FEE(TSK-4039) */
    @NotNull(message = "plcyType 은 필수입니다")
    @Pattern(regexp = "EXPAND|REPAIR|FEE", message = "plcyType 은 EXPAND · REPAIR · FEE 중 하나여야 합니다")
    @Schema(description = "정책유형코드 — EXPAND(주차장 확충) · REPAIR(이면도로 정비) · FEE(요금제 적용)", example = "EXPAND",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String plcyType;

    /** 확충 방식 — {@code ExpandMethod} 상수명. EXPAND 일 때 필수 */
    @Pattern(regexp = "VACLOT|MULTFLR|NEW", message = "expandMethod 는 VACLOT · MULTFLR · NEW 중 하나여야 합니다")
    @Schema(description = "확충 방식 — VACLOT(공한지) · MULTFLR(복층화) · NEW(신규). plcyType=EXPAND 일 때 필수",
            example = "VACLOT", nullable = true)
    private String expandMethod;

    /** 추가 면수 N — 50·80·100·150·200 (목록 밖은 서비스 400). EXPAND 일 때 필수 */
    @Schema(description = "추가 면수 — 50 · 80 · 100 · 150 · 200. plcyType=EXPAND 일 때 필수", example = "100",
            nullable = true)
    private Integer addPkltSlot;

    /** 이면도로 정비 방식 — {@code RoadMtncType} 상수명. REPAIR 일 때 필수 (저장 칸 road_mtnc_type 에 그대로) */
    @Pattern(regexp = "RSDNC|CMRC", message = "roadMtncType 은 RSDNC · CMRC 중 하나여야 합니다")
    @Schema(description = "이면도로 정비 방식 — RSDNC(주거지역형, n_100 14.4) · CMRC(상업지역형, n_100 12). "
            + "plcyType=REPAIR 일 때 필수", example = "RSDNC", nullable = true)
    private String roadMtncType;

    /** 정비 구간 연장(m) — 1 ~ 10,000. REPAIR 일 때 필수 (상한은 오입력 방지용 10km) */
    @Min(value = 1, message = "roadMtncLen 은 1 ~ 10000 (m) 이어야 합니다")
    @Max(value = 10000, message = "roadMtncLen 은 1 ~ 10000 (m) 이어야 합니다")
    @Schema(description = "정비 구간 연장(m, 정수 1 ~ 10000). plcyType=REPAIR 일 때 필수", example = "600",
            nullable = true)
    private Integer roadMtncLen;

    /** 요금 적용 방식 — {@code FeeAplcnMthd} 상수명. FEE 일 때 필수 (저장 칸 fee_aplcn_mthd 에 그대로) */
    @Pattern(regexp = "RAISE|CONVERT", message = "feeAplcnMthd 는 RAISE · CONVERT 중 하나여야 합니다")
    @Schema(description = "요금 적용 방식 — RAISE(유료 주차장 요금 인상) · CONVERT(무료 → 유료 전환). plcyType=FEE 일 때 필수",
            example = "CONVERT", nullable = true)
    private String feeAplcnMthd;

    /**
     * 적용 급지 — ALL(전체) · Z1(1급지 동) · Z2(2급지 읍·면). FEE 에서 생략하면 ALL.
     * 🔴 {@code emdCd} 가 있으면 무시하고 서버가 그 읍면동의 급지(읍·면 → Z2, 동 → Z1)로 채운다 — 저장·응답에는 채운 값.
     */
    @Pattern(regexp = "ALL|Z1|Z2", message = "feeAplcnZone 은 ALL · Z1 · Z2 중 하나여야 합니다")
    @Schema(description = "적용 급지 — ALL(전체) · Z1(1급지 동) · Z2(2급지 읍·면). plcyType=FEE 에서 생략하면 ALL. "
            + "emdCd 가 있으면 무시하고 그 읍면동의 급지로 채운다", example = "ALL", nullable = true)
    private String feeAplcnZone;

    /** 요금 인상률(%) — 10·20·30 (목록 밖은 서비스 400). FEE·RAISE 일 때 필수, CONVERT 면 무시(저장 NULL) */
    @Schema(description = "요금 인상률(%) — 10 · 20 · 30. plcyType=FEE·feeAplcnMthd=RAISE 일 때 필수, CONVERT 면 무시",
            example = "10", nullable = true)
    private Integer feeIncrsRt;

    /** 예측기간(개월) — 3·6·12·24 (목록 밖은 서비스 400) */
    @NotNull(message = "predcMm 은 필수입니다")
    @Schema(description = "예측기간(개월) — 3 · 6 · 12 · 24", example = "12",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer predcMm;

    /** 시군구명 — null = 제주 전체 */
    @Pattern(regexp = "제주시|서귀포시", message = "sggNm 은 제주시 또는 서귀포시여야 합니다(전체면 생략)")
    @Schema(description = "시군구명 (생략 = 제주 전체). emdCd 가 있으면 서버가 TB_ADMDST 값으로 채운다",
            example = "제주시", nullable = true)
    private String sggNm;

    /** 읍면동코드 8자리 — null = 시 전체 */
    @Pattern(regexp = "\\d{8}", message = "emdCd 는 8자리 숫자여야 합니다(시 전체면 생략)")
    @Schema(description = "읍면동코드 8자리 (생략 = 시 전체)", example = "50110137", nullable = true)
    private String emdCd;

    /**
     * 분석 계수 일시 조정 (TSK-4332) — POST 바디·보고서 {@code cond} 공용. 설정 표에는 쓰지 않는다.
     * 🔴 GET 쿼리 바인딩은 컨트롤러 {@code @InitBinder} 가 막는다(D9 — {@code vrblVls[KEY]=} 500 방지).
     */
    @Schema(description = "분석 계수 일시 조정 — {변수코드: 값}. 생략 = 설정값. 정책 유형별 허용 목록 밖이면 400 (TSK-4332)",
            nullable = true)
    private Map<String, BigDecimal> vrblVls;

    /**
     * 정책 유형별 필수 필드 (TSK-4038 Step 4-1) — 위반은 바인딩 검증 400.
     * {@code plcyType} 이 없거나 목록 밖이면 true 로 넘긴다 — 그 오류는 필드 제약이 따로 낸다(메시지 중복 방지).
     *
     * <p>🔴 {@code @Schema(hidden = true)}·{@code @JsonIgnore} 필수 — 없으면 boolean getter 가 {@code validForType}
     * 속성으로 Swagger 파라미터(GET {@code @ParameterObject})·스키마(POST 바디)와 응답 JSON 에 드러난다.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "plcyType=EXPAND 는 expandMethod·addPkltSlot, plcyType=REPAIR 는 roadMtncType·roadMtncLen, "
            + "plcyType=FEE 는 feeAplcnMthd(RAISE 면 feeIncrsRt 도)가 필수입니다")
    public boolean isValidForType() {
        if ("EXPAND".equals(plcyType)) {
            return expandMethod != null && addPkltSlot != null;
        }
        if ("REPAIR".equals(plcyType)) {
            return roadMtncType != null && roadMtncLen != null;
        }
        if ("FEE".equals(plcyType)) {
            // CONVERT 에 온 feeIncrsRt 는 무시한다(400 아님 — 다른 정책 전용 필드와 같은 취급)
            return feeAplcnMthd != null && (!"RAISE".equals(feeAplcnMthd) || feeIncrsRt != null);
        }
        return true;
    }
}
