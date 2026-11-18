package jeju.datahub.report.web;

import java.util.List;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jeju.datahub.agent.service.AgentApiException;
import jeju.datahub.agent.service.AgentApiException.Code;
import jeju.datahub.agent.service.AgentProperties;
import jeju.datahub.agent.service.AgentTokenStore;
import jeju.datahub.agent.service.AiMessageVO;
import jeju.datahub.agent.service.AiReportBlockVO;
import jeju.datahub.agent.service.AiSessionDetailVO;
import jeju.datahub.agent.service.impl.AgentApiClient;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.common.web.CurrentUserResolver;
import jeju.datahub.report.service.AiReportCrtRequest;
import jeju.datahub.report.service.AiReportCrtVO;
import jeju.datahub.report.service.AiReportService;
import lombok.RequiredArgsConstructor;

/**
 * AI 보고서 등록 REST 컨트롤러.
 *
 * <p>URL 은 AI 대화 아래({@code /api/ai/sessions/{sessionId}/reports})지만 컨트롤러는 {@code report} 에 둔다.
 * 🔴 {@code agent} 패키지에 두지 않는다 — 생성자 기록에 {@code auth.service.LoginUserVO} 가 필요한데
 * {@code agent} 는 {@code auth} 를 import 하지 않고, {@code AiSessionController} 의 계약은
 * "우리 DB 에 아무것도 쓰지 않는다"다. 의존은 {@code report} → {@code agent} 한 방향뿐이다.
 *
 * <h2>🔴 판정 순서</h2>
 * ① 로그인 사용자(401) → ② Agent 설정({@code AGENT_NOT_CONFIGURED} — {@code withToken} <b>람다 밖</b>.
 * 안에 두면 토큰 없음이 항상 먼저 걸린다) → ③ 요청 검증(400 — 재조회 전에) → ④ 세션 상세 재조회(
 * 프론트가 보낸 HTML 을 믿지 않는다) → ⑤ 대상 판정(404) → ⑥ 저장(201 · 멱등 200).
 *
 * <p>🔴 401 재시도를 여기서 만들지 않는다 — {@link AgentTokenStore#withToken} 이 1회 재시도를 한다.
 * <p>🔴 POST 라 CSRF 헤더가 필요하다 — {@code SecurityConfig} 를 건드리지 않는다(permitAll 추가 없음).
 * <p>{@link AgentApiException} 은 {@link AiReportExceptionHandler} 가 이 컨트롤러 한정으로 상태코드로 옮긴다.
 */
@Tag(name = "보고서", description = "보고서 목록 · 자동 생성 설정(TB_RPTP / TB_RPTP_AUTO_CRT_STNG) API")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiReportController {

    /** {@code msg_id VARCHAR(64)} — 넘기면 INSERT 500 이라 재조회 전에 400 으로 끊는다 */
    private static final int MSG_ID_MAX_LEN = 64;

    private static final String ROLE_ASSISTANT = "assistant";

    private final CurrentUserResolver currentUser;
    private final AgentProperties agentProps;
    private final AgentTokenStore agentTokenStore;
    private final AgentApiClient agentApiClient;
    private final AiReportService aiReportService;

    @Operation(summary = "AI 보고서 등록",
            description = "AI 답변 속 HTML 보고서 영역 1건을 AI 유형 보고서로 등록한다. 서버가 세션 상세를 다시 받아 "
                    + "msgId 가 같은 assistant 메시지의 reports[rptIdx] 를 꺼내 저장한다(본문을 요청으로 받지 않는다). "
                    + "새로 만들면 201, 같은 (사용자·세션·msgId·rptIdx) 로 이미 등록돼 있으면 200 + 기존 rptpId. "
                    + "msgId 누락·64자 초과·rptIdx 누락·음수는 400, 그런 메시지·보고서가 없거나 미완성(DONE 아님)이면 404, "
                    + "미인증 401, base-url·agent-id 미설정 503 AGENT_NOT_CONFIGURED, "
                    + "세션에 Agent 토큰이 없으면 503 AGENT_TOKEN_UNAVAILABLE.")
    @PostMapping("/sessions/{sessionId}/reports")
    public ResponseEntity<AiReportCrtVO> register(
            @AuthenticationPrincipal OidcUser user,
            @Parameter(description = "목록·전송 응답이 준 sessionId", required = true,
                    schema = @Schema(type = "string", maxLength = 512))
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) AiReportCrtRequest request,
            HttpSession session) {

        LoginUserVO me = currentUser.resolve(user);                    // ① 401
        requireConfigured();                                           // ② 설정 (람다 밖)
        String msgId = request == null ? null : request.getMsgId();    // ③ 400 — 재조회 전에
        Integer rptIdx = request == null ? null : request.getRptIdx();
        if (!StringUtils.hasText(msgId) || msgId.length() > MSG_ID_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "msgId 는 필수이며 64자 이하다");
        }
        if (rptIdx == null || rptIdx < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rptIdx 는 0 이상이어야 한다");
        }

        // ④ 다시 받는다. 🔴 401 재시도는 withToken 이 한다
        AiSessionDetailVO detail = agentTokenStore.withToken(session, t -> agentApiClient.getSession(t, sessionId));
        AiReportBlockVO block = findDoneBlock(detail, msgId, rptIdx);  // ⑤ 404

        // ⑥ 멱등 — 선조회 → INSERT → UNIQUE 위반이면 새 트랜잭션에서 재조회.
        //    🔴 catch 는 @Transactional 저장 메서드 바깥(여기)에 둔다
        String userId = me.getUserId();
        AiReportCrtVO found = aiReportService.findRegistered(userId, sessionId, msgId, rptIdx);
        if (found != null) {
            return ResponseEntity.ok(found);
        }
        try {
            AiReportCrtVO created = aiReportService.register(userId, me.getUserNm(), sessionId, msgId, rptIdx,
                    block.getTitle(), block.getHtml());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (DataIntegrityViolationException e) {
            AiReportCrtVO raced = aiReportService.findRegistered(userId, sessionId, msgId, rptIdx);
            if (raced == null) {
                throw e;   // 멱등 키 위반이 아니다 — 숨기지 않는다
            }
            return ResponseEntity.ok(raced);
        }
    }

    /** {@code msgId} 가 같은 assistant 메시지의 {@code reports[rptIdx]} 가 {@code DONE} 이어야 한다. 아니면 404 */
    private static AiReportBlockVO findDoneBlock(AiSessionDetailVO detail, String msgId, int rptIdx) {
        List<AiMessageVO> messages = detail == null || detail.getMessages() == null ? List.of() : detail.getMessages();
        AiMessageVO msg = messages.stream()
                .filter(Objects::nonNull)
                .filter(m -> ROLE_ASSISTANT.equals(m.getRole()) && msgId.equals(m.getMsgId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<AiReportBlockVO> reports = msg.getReports() == null ? List.of() : msg.getReports();
        if (rptIdx >= reports.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        AiReportBlockVO block = reports.get(rptIdx);
        if (block == null || !AiReportBlockVO.STTS_DONE.equals(block.getStts()) || block.getHtml() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return block;
    }

    /** 🔴 {@code withToken} 호출 <b>전</b>에만 부른다 ({@code AiSessionController#requireConfigured} 와 같은 검사) */
    private void requireConfigured() {
        if (!StringUtils.hasText(agentProps.getBaseUrl()) || !StringUtils.hasText(agentProps.getAgentId())) {
            throw new AgentApiException(Code.AGENT_NOT_CONFIGURED);
        }
    }
}
