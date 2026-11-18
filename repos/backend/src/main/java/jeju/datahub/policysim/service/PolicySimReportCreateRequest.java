package jeju.datahub.policysim.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 정책 시뮬레이션 시나리오 비교 보고서 생성 요청 (POST /api/analysis/policy-sim/reports).
 *
 * <p>시나리오마다 <b>저장분은 {@code rsltId} 만</b>, <b>미저장분은 조건({@code cond})만</b> 받는다 —
 * 어느 쪽도 화면이 보낸 숫자를 받지 않는다. 저장분은 저장된 값 그대로, 미저장분은 서버가 재계산한다.
 *
 * <p>{@code scenarios} 는 1~15건이며 화면에 보이던 순서 그대로다. 추천 판정의 마지막 동률 기준이
 * 이 순서다.
 *
 * <p>⚠️ {@code headIdx} 의 상한({@code headIdx < scenarios.size()})과 같은 {@code rsltId} 중복은 여기서 보지 않는다 —
 * 서비스가 400 으로 낸다. 클래스에 {@code @Validated} 를 달지 않는다({@link PolicySimRequest} 주석 참고).
 */
@Schema(description = "정책 시뮬레이션 시나리오 비교 보고서 생성 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PolicySimReportCreateRequest {

    /** 파일형식 — PDF | DOCX. 그 밖의 값·누락은 400 */
    @NotBlank(message = "fileFmt 는 필수입니다")
    @Pattern(regexp = "^(PDF|DOCX)$", message = "fileFmt 는 PDF 또는 DOCX 여야 합니다")
    @Schema(description = "파일형식 (PDF | DOCX)", example = "PDF",
            allowableValues = {"PDF", "DOCX"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileFmt;

    /**
     * 대표 시나리오 — {@code scenarios} 의 0-base 인덱스. 머리말(정책 유형 · 예측 기간)과 실행 이력의
     * 지역·기간 칸이 이 시나리오 기준이다. 범위 밖이면 서비스가 400. 응답의 {@code headSeq} 는 1-base 다.
     */
    @NotNull(message = "headIdx 는 필수입니다")
    @Min(value = 0, message = "headIdx 는 0 이상이어야 합니다")
    @Schema(description = "대표 시나리오 — scenarios 의 0-base 인덱스(범위 밖 400). 프론트는 현재 항상 0",
            example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer headIdx;

    /** 시나리오 1~15건 — 화면에 보이던 순서 그대로 */
    @NotEmpty(message = "scenarios 는 1건 이상이어야 합니다")
    @Size(max = 15, message = "scenarios 는 15건 이하여야 합니다")
    @Valid
    @Schema(description = "시나리오 1~15건 (화면에 보이던 순서 그대로)",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<PolicySimReportScenarioRequest> scenarios;
}
