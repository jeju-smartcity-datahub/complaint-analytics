package jeju.datahub.aisurvey.service.impl;

import java.time.LocalDateTime;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import jeju.datahub.aisurvey.service.AiSurveyAlreadySubmittedException;
import jeju.datahub.aisurvey.service.AiSurveyService;
import jeju.datahub.aisurvey.service.AiSurveyStatusVO;
import jeju.datahub.aisurvey.service.AiSurveySubmitRequest;
import lombok.RequiredArgsConstructor;

/**
 * AI 어시스턴트 만족도 설문.
 *
 * <ul>
 *   <li>재질문 판정은 <b>서버가 정본</b>이다 — 내 마지막 제출이 {@code 제출 기간} 안이면 대상이 아니고,
 *       그 안의 재제출은 409 다. 기간은 {@link AiSurveyPeriodReader} 가 요청마다 공통코드에서 읽는다.</li>
 *   <li>⚠️ 동시 이중 제출은 UNIQUE 로 막지 않는다 — "N일 창" 은 UNIQUE 로 표현할 수 없다. 프론트
 *       {@code submitting} 가드가 1차 방어선이고, 극단적 동시 2행은 허용 오차로 본다.</li>
 *   <li>Agent 를 부르지 않는다 — 세션 ID 는 저장만 하고 존재 확인을 하지 않는다(503 경로 없음).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiSurveyServiceImpl extends EgovAbstractServiceImpl implements AiSurveyService {

    private final AiDgstfnSrvyRepository repository;
    private final AiSurveyPeriodReader periodReader;

    @Override
    public AiSurveyStatusVO status(String userId) {
        LocalDateTime last = lastSubmitted(userId);
        boolean eligible = !withinSubmitPeriod(last, periodReader.submitDays());
        return new AiSurveyStatusVO(eligible, last, periodReader.postponeDays());
    }

    @Override
    @Transactional
    public long submit(String userId, AiSurveySubmitRequest request) {
        if (withinSubmitPeriod(lastSubmitted(userId), periodReader.submitDays())) {
            throw new AiSurveyAlreadySubmittedException(userId);
        }
        AiDgstfnSrvy row = new AiDgstfnSrvy();
        row.setUserId(userId);
        row.setSesnId(StringUtils.hasText(request.getSesnId()) ? request.getSesnId().trim() : null);
        row.setDgstfnScr(request.getDgstfnScr());
        row.setOpnnCn(StringUtils.hasText(request.getOpnnCn()) ? request.getOpnnCn().trim() : null);
        row.setRegDt(LocalDateTime.now());
        return repository.save(row).getSrvySn();
    }

    private LocalDateTime lastSubmitted(String userId) {
        return repository.findFirstByUserIdOrderByRegDtDesc(userId).map(AiDgstfnSrvy::getRegDt).orElse(null);
    }

    private static boolean withinSubmitPeriod(LocalDateTime last, int days) {
        return last != null && last.isAfter(LocalDateTime.now().minusDays(days));
    }
}
