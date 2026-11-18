package jeju.datahub.aisurvey.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jeju.datahub.aisurvey.service.AiSurveyAlreadySubmittedException;

/** {@link AiSurveyAlreadySubmittedException} → <b>409 {@code {"code":"SURVEY_ALREADY_SUBMITTED"}}</b>. 이 컨트롤러 한정. */
@RestControllerAdvice(assignableTypes = AiSurveyController.class)
public class AiSurveyExceptionHandler {

    @ExceptionHandler(AiSurveyAlreadySubmittedException.class)
    public ResponseEntity<Map<String, String>> handleAlreadySubmitted(AiSurveyAlreadySubmittedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", AiSurveyAlreadySubmittedException.CODE));
    }
}
