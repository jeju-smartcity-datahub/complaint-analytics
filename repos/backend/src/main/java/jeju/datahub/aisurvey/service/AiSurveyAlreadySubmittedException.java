package jeju.datahub.aisurvey.service;

/** 제출 기간 안 재제출 — <b>409 {@code {"code":"SURVEY_ALREADY_SUBMITTED"}}</b>(TSK-4369 D5). */
public class AiSurveyAlreadySubmittedException extends RuntimeException {

    /** 응답 본문 {@code code} 값 */
    public static final String CODE = "SURVEY_ALREADY_SUBMITTED";

    public AiSurveyAlreadySubmittedException(String userId) {
        super(CODE + " userId=" + userId);
    }
}
