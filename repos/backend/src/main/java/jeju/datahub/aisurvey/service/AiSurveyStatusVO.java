package jeju.datahub.aisurvey.service;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** 설문을 지금 띄워도 되는지. */
@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class AiSurveyStatusVO {

    @Schema(description = "true 면 설문 대상 — 최근 제출이 없거나 제출 기간(공통코드 AI_SRVY_SBMSN_PRD_CD 일)이 지났다")
    private boolean eligible;

    @Schema(description = "내 마지막 제출 일시(없으면 null)", nullable = true)
    private LocalDateTime lastSbmsnDt;

    @Schema(description = "'나중에' 선택 시 다시 묻지 않는 일수(공통코드 AI_SRVY_PSTP_PRD_CD). 프론트가 미룸 만료 시각 계산에 쓴다")
    private int postponeDays;
}
