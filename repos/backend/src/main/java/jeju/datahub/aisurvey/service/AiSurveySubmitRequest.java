package jeju.datahub.aisurvey.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 만족도 설문 제출 요청(TSK-4369). 🔴 사용자 ID 는 받지 않는다 — 세션 사용자만 쓴다.
 * 형식 위반은 400 이다(컨트롤러 {@code @Valid}).
 */
@Getter
@Setter
@ToString(exclude = "opnnCn")
@NoArgsConstructor
public class AiSurveySubmitRequest {

    public static final int OPNN_MAX_LEN = 1000;

    @Schema(description = "만족도 점수 1(매우 불만족)~10(매우 만족)", example = "6", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(1)
    @Max(10)
    private Integer dgstfnScr;

    @Schema(description = "자유 의견(선택). 앞뒤 공백 제거 후 빈 값이면 저장하지 않는다", maxLength = OPNN_MAX_LEN)
    @Size(max = OPNN_MAX_LEN)
    private String opnnCn;

    @Schema(description = "응답 당시 대화 세션 ID(선택 · 참고용). 새 대화 화면이면 비운다", maxLength = 512)
    @Size(max = 512)
    private String sesnId;
}
