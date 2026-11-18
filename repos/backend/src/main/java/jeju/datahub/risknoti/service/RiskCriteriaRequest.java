package jeju.datahub.risknoti.service;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 위험단계 알림 기준 일괄 저장 요청 바디. (PUT /api/risk-criteria)
 *
 * <p><b>3건 일괄 UPSERT</b> 다 — 단계당 정확히 1행이라 전체 교체(DELETE+INSERT)보다
 * UPSERT 가 안전하다. {@code thrsh_id} 는 {@code 'THRSH_' + riskLvlCd} 로 서버가 만든다.
 *
 * <p>🔴 <b>부분 저장을 허용하지 않는다</b> — 단계 하나라도 빠지면 400 이다.
 * 기준 3행이 전 지역·전 수신자에 공통 적용되는 유일한 설정이라, 화면이 세 값을 함께 보여주고
 * 함께 저장한다. 어떤 단계가 필수인지는 {@code RISK_LVL_CD} 공통코드가 정한다(서비스가 대조).
 *
 * <p>⚠️ 기준을 바꿔도 과거 알림 이력의 {@code incrs_rt_thrsh} 는 건드리지 않는다
 * (판정 당시 스냅샷이라 소급 수정하면 의미가 사라진다).
 *
 * <p>응답은 요청과 달리 <b>래핑 없는 최상위 배열</b>({@code RiskCriteriaVO[]})이다.
 */
@Schema(description = "위험단계 알림 기준 일괄 저장 요청 (3단계 전부)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskCriteriaRequest {

    /**
     * 단계별 기준 목록 — <b>3단계 전부</b>를 보내야 한다.
     *
     * <p>{@code @Valid} 를 붙여야 원소({@link Item})의 제약이 함께 검증된다.
     * 개수·누락·중복 판정은 공통코드 대조가 필요해 서비스가 한다.
     */
    @NotEmpty(message = "criteria 는 필수입니다 (3단계 전부)")
    @Valid
    @Schema(description = "단계별 기준 목록 (3단계 전부)")
    private List<Item> criteria;

    /**
     * 기준 1건. ({@code riskLvlCd} + {@code incrsRt} + {@code minCvlcptCnt} — 항목 3개)
     *
     * <p>{@code thrshId} 는 받지 않는다 — {@code 'THRSH_' + riskLvlCd} 로 서버가 만든다.
     * {@code thrshCnt}(구 건수 기준)도 받지 않는다(보류 — 범위 밖 #7).
     *
     * <p>🔴 {@code bscMinCvlcptCnt}(기본 최소 민원수)<b>도 받지 않는다</b> (③) —
     * 응답({@link RiskCriteriaVO})에만 있는 불변 기본값이라 반영할 자리가 없다
     * (엔티티가 {@code updatable=false}). 프론트는 GET 응답을 그대로 PUT 바디로 재사용하지 않는다.
     */
    @Schema(description = "단계별 기준 1건")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    public static class Item {

        /** 위험단계 코드 (RISK_LVL_CD). 실재 여부는 서비스가 TB_COM_CD 로 대조한다 */
        @NotBlank(message = "riskLvlCd 는 필수입니다")
        @Schema(description = "위험단계 코드 (CRIT | WARN | CAUT)", example = "CRIT")
        private String riskLvlCd;

        /**
         * 증가율 기준 (%) — {@code 0 < x <= 100}, 소수 2자리까지.
         *
         * <p>0 이하는 "언제나 알림"이라 기준이 되지 않고, 100 초과는 화면 입력 범위 밖이다.
         * 컬럼이 {@code NUMERIC(7,2)} 라 소수 3자리 이상은 반올림돼 저장 값이 요청과 갈린다.
         */
        @NotNull(message = "incrsRt 는 필수입니다")
        @DecimalMin(value = "0", inclusive = false, message = "incrsRt 는 0 보다 커야 합니다")
        @DecimalMax(value = "100", message = "incrsRt 는 100 이하여야 합니다")
        @Digits(integer = 3, fraction = 2, message = "incrsRt 는 소수 2자리까지만 허용합니다")
        @Schema(description = "증가율 기준 (%) — 0 초과 100 이하", example = "30.00")
        private BigDecimal incrsRt;

        /**
         * 최소 민원수 하한 — {@code 0 <= x <= 1000}, 정수.
         *
         * <p>0 은 "하한 없음" 이라 유효한 값이다({@code incrsRt} 와 달리 0 을 막지 않는다).
         * 상한 1000 은 오타 하나로 알림이 영구 침묵하는 것을 막는 안전장치다
         * (실측 읍면동 일일 최대 민원은 41건 — 2026-08-27).
         */
        @NotNull(message = "minCvlcptCnt 는 필수입니다")
        @Min(value = 0, message = "minCvlcptCnt 는 0 이상이어야 합니다")
        @Max(value = 1000, message = "minCvlcptCnt 는 1000 이하여야 합니다")
        @Schema(description = "최소 민원수 하한 (0 = 하한 없음)", example = "10")
        private Integer minCvlcptCnt;
    }
}
