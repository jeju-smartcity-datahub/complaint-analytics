package jeju.datahub.aisurvey.service;

/** AI 어시스턴트 만족도 설문(TSK-4369). */
public interface AiSurveyService {

    /** 지금 설문을 띄워도 되는지 + 미룸 일수. */
    AiSurveyStatusVO status(String userId);

    /**
     * 설문 1건 저장 후 {@code srvy_sn} 을 돌려준다.
     *
     * @throws AiSurveyAlreadySubmittedException 제출 기간 안에 이미 제출했다(409)
     */
    long submit(String userId, AiSurveySubmitRequest request);
}
