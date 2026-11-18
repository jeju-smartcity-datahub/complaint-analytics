package jeju.datahub.aisurvey.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jeju.datahub.aisurvey.service.AiSurveyService;
import jeju.datahub.aisurvey.service.AiSurveyStatusVO;
import jeju.datahub.aisurvey.service.AiSurveySubmitRequest;
import jeju.datahub.common.web.CurrentUserResolver;
import lombok.RequiredArgsConstructor;

/**
 * AI 어시스턴트 만족도 설문 REST 컨트롤러.
 *
 * <ul>
 *   <li>인가는 로그인 사용자 전원이다 — {@code SecurityConfig} 의 {@code /api/**} authenticated 그대로,
 *       메뉴 인가를 걸지 않는다. 미인증은 {@link CurrentUserResolver} 가 401 로 끊는다.</li>
 *   <li>🔴 POST 는 CSRF 대상이다 — 프론트는 공용 {@code api}(axios) 인스턴스로 부른다(헤더 없으면 403).</li>
 *   <li>🔴 {@code agent} 패키지와 분리한 도메인이다 — Agent 를 부르지 않고, {@code auth} 를 import 하지 않는다.</li>
 * </ul>
 */
@Tag(name = "AI 어시스턴트 만족도 설문", description = "AI 어시스턴트 만족도(1~10점 + 선택 의견) 수집 API")
@RestController
@RequestMapping("/api/ai/surveys")
@RequiredArgsConstructor
public class AiSurveyController {

    private final CurrentUserResolver currentUser;
    private final AiSurveyService aiSurveyService;

    @Operation(summary = "설문 대상 여부",
            description = "지금 설문을 띄워도 되는지(eligible)와 '나중에' 유예 일수(postponeDays)를 돌려준다. "
                    + "최근 제출이 공통코드 AI_SRVY_SBMSN_PRD_CD 일 안이면 eligible=false. 미인증 401.")
    @GetMapping("/status")
    public AiSurveyStatusVO status(@AuthenticationPrincipal OidcUser user) {
        return aiSurveyService.status(currentUser.resolveUserId(user));
    }

    @Operation(summary = "설문 제출",
            description = "점수(1~10)와 선택 의견(1000자 이하)을 저장한다. 201 {srvySn}. 형식 위반 400, "
                    + "제출 기간 안 재제출 409 {\"code\":\"SURVEY_ALREADY_SUBMITTED\"}, 미인증 401, CSRF 헤더 없으면 403.")
    @PostMapping
    public ResponseEntity<Map<String, Long>> submit(@AuthenticationPrincipal OidcUser user,
            @Valid @RequestBody AiSurveySubmitRequest request) {
        String userId = currentUser.resolveUserId(user);
        long srvySn = aiSurveyService.submit(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("srvySn", srvySn));
    }
}
