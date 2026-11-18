package jeju.datahub.agent.web;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jeju.datahub.agent.service.AgentApiException;
import jeju.datahub.agent.service.AgentApiException.Code;
import jeju.datahub.agent.service.AgentProperties;
import jeju.datahub.agent.service.AgentTokenStore;
import jeju.datahub.agent.service.AgentToolCallPendingException;
import jeju.datahub.agent.service.AiChatRequest;
import jeju.datahub.agent.service.AiChatResponse;
import jeju.datahub.agent.service.AiMessageVO;
import jeju.datahub.agent.service.AiSessionDetailVO;
import jeju.datahub.agent.service.AiSessionListVO;
import jeju.datahub.agent.service.impl.AgentApiClient;
import jeju.datahub.agent.service.impl.ChatDiagLog;
import lombok.RequiredArgsConstructor;

/**
 * AI 어시스턴트 대화 REST 컨트롤러 — 조회(TSK-4121 Step 3) + 전송(TSK-4122 Step 2)
 * + 삭제(TSK-4218 Step 3).
 *
 * <p>백엔드가 Agent Service 를 <b>프록시</b>한다(D1) — 프론트는 Agent API 를 직접 부르지 않고,
 * 토큰은 이 프로세스 밖으로 나가지 않는다. 🔴 전송이 생겨도 <b>우리 DB 에는 아무것도 쓰지 않는다</b>
 * (D3) — 대화의 단일 정본은 Agent API 다.
 *
 * <h2>🔴 판정 순서를 컨트롤러가 강제한다</h2>
 * {@link AgentTokenStore#withToken} 은 토큰이 없으면 <b>람다를 실행하지 않고</b> 곧장
 * {@code AGENT_TOKEN_UNAVAILABLE} 을 던진다. 그래서 설정 검사를 람다 <b>안</b>에 두면 설정도 토큰도
 * 없는 상태에서 항상 토큰 쪽이 먼저 걸려 {@code AGENT_NOT_CONFIGURED} 가 영원히 나오지 않는다.
 * 두 503 은 동시에 성립할 수 있으므로 <b>설정 검사를 람다 밖(호출 전)</b>에 둔다.
 * {@code AgentApiClient#requireConfigured} 는 같은 검사의 2차 방어선일 뿐 순서를 지지 못한다.
 *
 * <h2>🔴 여기서 하지 않는 것</h2>
 * <ul>
 *   <li><b>Agent 식별자를 쿼리로 받지 않는다</b>(D4) — 목록·상세 어느 쪽에도 쿼리로 두지 않는다.
 *       클라이언트가 조회 범위를 정할 수 있으면 여러 Agent 가 공유하는 기본 세션 ID
 *       {@code current} 때문에 남의 Agent 대화가 재생된다. 값은 {@code app.agent.agent-id} 하나뿐이고
 *       {@link AgentApiClient} 가 스스로 붙인다.</li>
 *   <li><b>{@code role} 필터·{@code <think>} 제거를 다시 하지 않는다</b>(D6·D13) —
 *       {@link AgentApiClient} 의 VO 매핑이 정본이다. 두 곳에서 거르면 규칙이 갈린다.</li>
 *   <li><b>{@code sessionId} 오염 검사를 다시 하지 않는다</b> — {@link AgentApiClient} 가
 *       호출 직전에 {@code AGENT_BAD_REQUEST} 로 끊는다.</li>
 * </ul>
 *
 * <h2>🔴 예외 → HTTP</h2>
 * {@link AgentApiException} 은 저절로 상태코드가 되지 않는다. 이 레포에는 advice 가 없어 그대로 두면
 * Boot 기본 {@code /error} 가 <b>500 + code 없는 본문</b>을 낸다 —
 * {@link AgentApiExceptionHandler} 가 이 컨트롤러 한정으로 옮긴다.
 *
 * <p>인가는 {@code SecurityConfig} 가 이미 지고 있다 — {@code /api/**} 는 {@code authenticated()} 라
 * 미인증은 401 JSON 이다. 🔴 <b>{@code SecurityConfig} 를 건드리지 않는다</b>(permitAll 에 추가하지
 * 않는다).
 * <p>🔴 <b>전송은 POST 라 CSRF 가 실제로 걸린다</b>(4122 D4) — 조회(GET)만 있던 때와 달라지는
 * 지점이다. 프론트가 공용 {@code api}(axios) 인스턴스를 쓰면 {@code X-XSRF-TOKEN} 이 자동으로
 * 실려 통과하고, 새 인스턴스를 만들면 그 헤더가 빠져 <b>403</b> 이다. 삭제({@code DELETE})도
 * 같은 CSRF 대상이다(4218 D9).
 */
@Tag(name = "AI 어시스턴트", description = "AI 대화 조회·전송·삭제 API (Agent Service 프록시 · 우리 DB 에 저장하지 않는다)")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiSessionController {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(AiSessionController.class);

    /** 목록 조회 건수 하한 (D11) */
    private static final int MIN_LIMIT = 1;

    /** 목록 조회 건수 상한 (D11). 초과하면 400 {@code AGENT_BAD_REQUEST} */
    private static final int MAX_LIMIT = 100;

    /**
     * 새 대화 세션 ID 접두 (TSK-4122 D7). 형식은 벤더 관례를 따르되 <b>생성 주체만 우리 쪽</b>이다 —
     * {@code java.util.UUID.randomUUID()} 로 만든다.
     */
    private static final String SESSION_ID_PREFIX = "portal-";

    private final AgentProperties agentProps;
    private final AgentTokenStore agentTokenStore;
    private final AgentApiClient agentApiClient;

    /**
     * 세션 목록 — 최신순 {@code limit} 건 + {@code nextCursor}.
     *
     * <p>{@code limit} 을 생략하면 {@code app.agent.default-limit}(기본 <b>20</b>)이 적용된다.
     * 🔴 컨트롤러에 기본값을 또 박지 않는다 — 두 곳에 두면 설정을 바꿔도 한쪽만 바뀐다.
     */
    @Operation(summary = "AI 대화 세션 목록",
            description = "로그인 사용자 본인의 AI 대화 세션을 최신순으로 조회한다. "
                    + "limit 은 1~100(생략 시 20), cursor 는 이전 응답의 nextCursor 를 그대로 넘긴다. "
                    + "limit 이 범위를 벗어나면 400 AGENT_BAD_REQUEST, "
                    + "base-url·agent-id 미설정은 503 AGENT_NOT_CONFIGURED, "
                    + "세션에 Agent 토큰이 없으면 503 AGENT_TOKEN_UNAVAILABLE 이다.")
    @GetMapping("/sessions")
    public AiSessionListVO sessions(
            @Parameter(description = "조회 건수 (1~100, 생략 시 20)",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", example = "20"))
            @RequestParam(name = "limit", required = false) Integer limit,
            @Parameter(description = "이어 받기 커서 — 이전 응답의 nextCursor",
                    schema = @Schema(type = "string"))
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpSession session) {

        if (limit != null && (limit < MIN_LIMIT || limit > MAX_LIMIT)) {
            throw new AgentApiException(Code.AGENT_BAD_REQUEST);
        }
        // ① 설정 (람다 밖 — 순서가 여기서 결정된다). agent-id 도 함께 본다(D4)
        requireConfigured();
        // ② 토큰 (없으면 여기서 503 AGENT_TOKEN_UNAVAILABLE)
        return agentTokenStore.withToken(session, t -> agentApiClient.listSessions(t, limit, cursor));
    }

    /**
     * 세션 상세 — {@code user}·{@code assistant} 메시지와 컨텍스트 사용량.
     *
     * <p>🔴 <b>Agent 식별자 쿼리를 두지 않는다</b>(D4). 프론트는 목록이 준 {@code sessionId} 만 보낸다.
     */
    @Operation(summary = "AI 대화 세션 상세",
            description = "세션 1건의 메시지를 조회한다. role 이 system·tool 인 메시지와 "
                    + "assistant 의 <think> 블록은 백엔드가 제거해 내려보내지 않는다(D6·D13). "
                    + "sessionId 에 '/'·'%' 가 있거나 512자를 넘으면 400 AGENT_BAD_REQUEST, "
                    + "upstream 403·404 는 그대로 전달된다(AGENT_UPSTREAM_REJECTED).")
    @GetMapping("/sessions/{sessionId}")
    public AiSessionDetailVO session(
            @Parameter(description = "목록이 반환한 sessionId", required = true,
                    schema = @Schema(type = "string", maxLength = 512))
            @PathVariable("sessionId") String sessionId,
            HttpSession session) {

        // ① 설정 → ② 토큰. 목록과 같은 순서다.
        requireConfigured();
        return agentTokenStore.withToken(session, t -> agentApiClient.getSession(t, sessionId));
    }

    /**
     * 세션 삭제 — 성공은 <b>204</b> (TSK-4218 D1). 되돌릴 수 없다.
     *
     * <p>🔴 <b>판정 순서는 조회와 같다 — 설정 → 토큰</b>(D3). {@code requireConfigured()} 를 람다
     * 안으로 옮기면 {@code AGENT_NOT_CONFIGURED} 가 영원히 나오지 않는다.
     *
     * <p>🔴 <b>401 재시도를 여기서 만들지 않는다</b>(D5) — {@link AgentTokenStore#withToken} 의 1회
     * 재시도에 맡긴다. DELETE 는 멱등이고 401 은 "실행 안 됨" 이라 재시도해도 이중 삭제가 없다.
     * {@code withToken} 이 {@code Function} 이라 {@code return null} 로 넘긴다
     * ({@code Consumer} 오버로드를 새로 만들지 않는다).
     *
     * <p>🔴 <b>Agent 식별자를 받지 않는다</b>(D2) — {@code X-Agent-Id} 는 {@link AgentApiClient} 가
     * {@code app.agent.agent-id} 로 붙인다. 받을 수 있으면 남의 Agent 세션을 지우는 경로가 된다.
     */
    @Operation(summary = "AI 대화 세션 삭제",
            description = "세션 1건을 Agent Service 에서 삭제한다(되돌릴 수 없다). 성공은 204. "
                    + "sessionId 에 '/'·'%' 가 있거나 512자를 넘으면 400 AGENT_BAD_REQUEST, "
                    + "upstream 403·404 는 그대로 전달된다(AGENT_UPSTREAM_REJECTED).")
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @Parameter(description = "목록이 반환한 sessionId", required = true,
                    schema = @Schema(type = "string", maxLength = 512))
            @PathVariable("sessionId") String sessionId,
            HttpSession session) {

        requireConfigured();                                   // ① 설정 (람다 밖 — D3)
        agentTokenStore.withToken(session, t -> {              // ② 토큰
            agentApiClient.deleteSession(t, sessionId);
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    /**
     * 전송 — <b>새 대화</b> (TSK-4122 Step 2).
     *
     * <p>🔴 <b>URL 에 세션 ID 가 없는 것</b>이 "새 대화" 의 표현이다(D6). 백엔드가
     * {@code portal-{UUID}} 를 만들어 업스트림 {@code X-Session-Id} 에 싣고, 응답에 그 값을 그대로
     * 실어 준다 — 프론트는 다음 턴부터 {@link #sendToSession} 을 쓴다.
     *
     * <p>하나의 엔드포인트에서 {@code sessionId} 를 optional 바디로 받지 않는 이유가 이것이다 —
     * 세션 ID 는 Agent 간 공유되는 구조(4121 §2-1)라, 프론트가 <b>남의 세션 ID 를 적어 넣어</b>
     * 그 대화에 이어 쓸 수 있게 된다.
     */
    @Operation(summary = "AI 어시스턴트 전송 (새 대화)",
            description = "새 대화를 시작하며 질문을 보낸다. 세션 ID 는 백엔드가 만들어(portal-{UUID}) "
                    + "응답 sessionId 로 돌려준다 — 프론트가 정하지 않는다. "
                    + "message 는 1~4000자이며 위반 시 400(code 없는 표준 검증 응답)이다. "
                    + "업스트림이 200 을 주고도 답변을 만들지 못하면 502 AGENT_NO_ANSWER, "
                    + "우리 요청이 거절되면 400 AGENT_BAD_REQUEST 다.")
    @PostMapping("/messages")
    public AiChatResponse send(@Valid @RequestBody AiChatRequest request, HttpSession session) {
        // ① 설정 (람다 밖 — 조회와 같은 순서다)
        requireConfigured();
        // ② 🔴 세션 ID 는 withToken 람다 **바깥**에서 한 번만 만든다 (D6·D7).
        //    람다 안에서 만들면 withToken 의 401 재시도가 **다른 세션 ID** 를 만들어,
        //    응답의 sessionId 와 실제로 전송된 X-Session-Id 가 갈린다.
        String sessionId = SESSION_ID_PREFIX + UUID.randomUUID();
        ChatDiagLog.apiRequest(agentProps.isDiagLogEnabled(), "/api/ai/messages", sessionId, true,
                request.getMessage());
        return dispatch(sessionId, request, session);
    }

    /**
     * 전송 — <b>기존 대화 이어쓰기</b> (TSK-4122 Step 2).
     *
     * <p>🔴 <b>{@code sessionId} 가 "우리가 아는 세션" 인지는 검사하지 않는다</b> — 그럴 방법이 없다
     * (대화를 우리 DB 에 저장하지 않는다, D3). 방어는 업스트림이 한다: 소유자(Bearer) +
     * {@code X-Agent-Id} 로 스코프가 걸리므로 남의 세션 ID 를 적어도 그 사용자 소유가 아니면
     * 403/404 다. 우리 몫은 <b>{@code X-Agent-Id} 를 절대 빠뜨리지 않는 것</b>과 형식 검증
     * ({@code /}·{@code %} 금지, 512자 — {@link AgentApiClient} 가 진다)뿐이다.
     */
    @Operation(summary = "AI 어시스턴트 전송 (이어쓰기)",
            description = "기존 세션에 이어 질문을 보낸다. sessionId 는 목록·이전 전송 응답이 준 값이다. "
                    + "sessionId 에 '/'·'%' 가 있거나 512자를 넘으면 400 AGENT_BAD_REQUEST, "
                    + "남의 세션이면 업스트림 403·404 가 그대로 전달된다(AGENT_UPSTREAM_REJECTED).")
    @PostMapping("/sessions/{sessionId}/messages")
    public AiChatResponse sendToSession(
            @Parameter(description = "이어 쓸 세션 ID", required = true,
                    schema = @Schema(type = "string", maxLength = 512))
            @PathVariable("sessionId") String sessionId,
            @Valid @RequestBody AiChatRequest request,
            HttpSession session) {

        requireConfigured();
        ChatDiagLog.apiRequest(agentProps.isDiagLogEnabled(), "/api/ai/sessions/{sessionId}/messages", sessionId,
                false, request.getMessage());
        return dispatch(sessionId, request, session);
    }

    /**
     * 두 전송 매핑의 공통 몸통.
     *
     * <p>🔴 <b>401 재시도를 여기서 만들지 않는다</b>(D2) — {@link AgentTokenStore#withToken} 이 이미
     * 선제 갱신 + upstream 401 후 <b>정확히 1회</b> 재시도를 한다(TSK-4134 Step 2.5). 여기서 또
     * 만들면 두 겹이 되어 최대 4번 나간다.
     *
     * <p>🔴 <b>{@code AiChatResponse} 조립은 컨트롤러가 한다.</b> {@code AgentApiClient} 는
     * {@link jeju.datahub.agent.service.AiMessageVO} 만 돌려준다 — 세션 ID 를 만든 주체가
     * 그것을 실어야 한다(D6·D7). 🔴 {@code sessionId} 는 <b>실제로 보낸 값 그대로</b>다.
     *
     * <p>🔴 <b>로그인 방식을 나타내는 세션 속성을 읽지 않는다</b>(D16 — 경로 중립). 전송 경로는
     * 토큰이 OIDC 에서 왔는지 email/pw 에서 왔는지 <b>몰라야 한다</b> — {@code withToken} 이 그
     * 차이를 이미 흡수한다({@code TokenRefresher} 빈 유무). 방식으로 분기하면 audience 가 부여돼도
     * OIDC 가 다른 경로를 타 검증되지 않은 채 남는다.
     */
    private AiChatResponse dispatch(String sessionId, AiChatRequest request, HttpSession session) {
        // 🔴 TSK-4349 — 한시적 진단 로그(스위치 OFF 면 찍지 않는다). 재시도 횟수·결과 코드를 남긴다.
        //    🔴 401 재시도를 여기서 만들지 않는다 — 람다는 withToken 이 부르는 그대로이고 횟수만 센다.
        boolean diag = agentProps.isDiagLogEnabled();
        AtomicInteger attempt = new AtomicInteger();
        long started = System.nanoTime();
        AiMessageVO message;
        try {
            try {
                message = agentTokenStore.withToken(session, t -> {
                    ChatDiagLog.apiAttempt(diag, sessionId, attempt.incrementAndGet(), t);
                    return agentApiClient.sendMessage(t, sessionId, request.getMessage());
                });
            } catch (AgentToolCallPendingException pending) {
                // 🆕 TSK-4345 — 답 없이 도구 호출(승인 대기)만 왔다 → 자동 거절 1회 후 그 답변을 보여 준다.
                //    🔴 거절은 <b>별도 withToken</b> 이다 — 위 람다 안에서 보내면 거절 단계의 401 재시도가
                //       사용자 질문까지 다시 보내 대화에 중복으로 쌓인다.
                //    🔴 거절 뒤에도 또 도구 호출이면 rejectToolCalls 가 409 AGENT_TOOL_CALL_UNRESOLVED 로 끝낸다(1회뿐).
                log.warn("[agent-api] tool_call auto-reject — sessionId={} name={} count={}",
                        sessionId, pending.getToolNames(), pending.getToolCallIds().size());
                message = agentTokenStore.withToken(session,
                        t -> agentApiClient.rejectToolCalls(t, sessionId, pending.getToolCallIds()));
            }
        } catch (AgentApiException e) {
            ChatDiagLog.apiFailure(diag, sessionId, elapsedMs(started), e.httpStatus(), e.getCode().name());
            throw e;
        } catch (RuntimeException e) {
            ChatDiagLog.apiFailure(diag, sessionId, elapsedMs(started), 500, e.getClass().getSimpleName());
            throw e;
        }
        if (diag) {
            ChatDiagLog.apiResponse(true, sessionId, elapsedMs(started), message.getMsgId(), message.getContent(),
                    message.getReports() == null ? 0 : message.getReports().size());
        }
        return new AiChatResponse(sessionId, message);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * 🔴 {@code withToken} 호출 <b>전</b>에만 부른다 — 람다 안으로 옮기는 순간 순서가 뒤집힌다.
     */
    private void requireConfigured() {
        if (!StringUtils.hasText(agentProps.getBaseUrl()) || !StringUtils.hasText(agentProps.getAgentId())) {
            throw new AgentApiException(Code.AGENT_NOT_CONFIGURED);
        }
    }
}
