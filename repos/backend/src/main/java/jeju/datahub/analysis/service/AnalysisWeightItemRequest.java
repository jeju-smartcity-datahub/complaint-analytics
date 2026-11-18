package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 설정값 저장 요청의 변수 1건. ({@link AnalysisWeightSaveRequest#getWeights()} 의 원소)
 *
 * <p>바깥 요청의 {@code weights} 에 {@code @Valid} 가 붙어 있어야 이 클래스의 제약이 발동한다 —
 * 컨트롤러의 {@code @Valid} 와도 세트다 (주석 참고: {@code @Valid} 가 빠지면
 * {@code @NotNull} 이 발동하지 않아 바디 누락이 400 이 아니라 NPE 로 흐른다).
 */
@Schema(description = "설정값 저장 요청의 변수 1건")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AnalysisWeightItemRequest {

    /** 변수코드 (TB_ANLS_VRBL_STNG.vrbl_cd — 분석유형 안에서 변수를 식별) */
    @NotBlank(message = "vrblCd 는 필수입니다")
    @Size(max = 30, message = "vrblCd 는 30자를 넘을 수 없습니다")
    @Schema(description = "변수코드", example = "COMPLAINT")
    private String vrblCd;

    /**
     * 설정값. 컬럼은 {@code NUMERIC(12,4)} 이며 가중치(%)와 계수가 같은 칸을 쓴다.
     *
     * <p>🔴 <b>여기에 범위 상수를 두지 않는다</b>. 허용 범위는 행별
     * {@code min_vl}/{@code max_vl} 이 지며, 판정은 앱 검증
     * ({@code AnalysisWeightServiceImpl} 의 범위 검증)과 DB CHECK 두 곳이 한다.
     * DTO 에 {@code @DecimalMin("0")}/{@code @DecimalMax("100")} 이 남아 있으면
     * 계수(예: {@code MIN_CPCTY_VL}: 범위 0~50 · {@code PCSHR_CRTR_VL}: 0.3~1.0)가 DTO 의 0~100 과 어긋나
     * <b>엉뚱한 사유의 400</b>(또는 DTO 를 통과한 뒤 DB CHECK 500)으로 갈리고,
     * 범위 근거가 DTO 와 DB 두 곳에 갈려 있게 된다 — 그래서 제거했다.
     *
     * <p>{@code @Digits(integer = 8, fraction = 4)} 는 컬럼 정밀도({@code NUMERIC(12,4)})를
     * 그대로 옮긴 것이다. 종전의 {@code fraction = 0}(정수만)은 가중치 슬라이더 전제였는데,
     * 계수는 {@code 0.5} 같은 소수를 받아야 해서 완화했다.
     *
     * <p>{@link BigDecimal} 인 이유: 합계 100 판정을 부동소수로 하면 오차 때문에 간헐 실패한다.
     */
    @NotNull(message = "stngVl 는 필수입니다")
    @Digits(integer = 8, fraction = 4,
            message = "stngVl 는 정수부 8자리·소수부 4자리를 넘을 수 없습니다")
    @Schema(description = "설정값 (가중치는 %, 계수는 자체 단위) — 허용 범위는 행별 minVl~maxVl", example = "55")
    private BigDecimal stngVl;
}
