package jeju.datahub.agent.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jeju.datahub.agent.service.AgentApiException;

/**
 * {@link AgentApiException} 을 HTTP 응답으로 옮기는 advice.
 *
 * <p>🔴 <b>이 레포의 첫 advice 다</b> — 범위를 {@link AiSessionController} 하나로 좁힌다.
 * 다른 컨트롤러는 {@code ResponseStatusException} 을 던져 Boot 기본 처리에 얹혀 있다(실측 134건).
 * <p>🔴 스프링 MVC 의 <b>ResponseEntity 계열 기본 예외 처리 기반 클래스를 상속하지 않고</b>,
 * 최상위 {@code Exception} 도 <b>잡지 않는다</b> — 둘 중 하나라도 하면 그 134건의 응답 형태가
 * 통째로 바뀐다.
 * <p>🔴 응답 바디는 {@code code} <b>하나뿐</b>이다. 예외 메시지·upstream 본문을 싣지 않는다 —
 * 본문에는 토큰·내부 프롬프트가 섞일 수 있고 그대로 브라우저까지 흘러간다.
 *
 * <table>
 *   <caption>코드 → 상태</caption>
 *   <tr><td>AGENT_BAD_REQUEST</td><td>400</td></tr>
 *   <tr><td>AGENT_NOT_CONFIGURED</td><td>503</td></tr>
 *   <tr><td>AGENT_TOKEN_UNAVAILABLE</td><td>503</td></tr>
 *   <tr><td>AGENT_UNAUTHORIZED</td><td>401</td></tr>
 *   <tr><td>AGENT_UPSTREAM_REJECTED</td><td>upstream 그대로(403·404)</td></tr>
 *   <tr><td>AGENT_UPSTREAM_ERROR</td><td>502</td></tr>
 *   <tr><td>AGENT_NO_ANSWER</td><td>502 — 🔴 upstream 은 200 인데 답변이 없다 (4122)</td></tr>
 *   <tr><td>AGENT_TOOL_CALL_UNRESOLVED</td><td>409 — 🆕 도구 호출 자동 거절 뒤에도 또 도구 호출</td></tr>
 * </table>
 * 상태 판정은 {@link AgentApiException#httpStatus()} 가 지고, 여기서는 그것을 그대로 쓴다.
 *
 * <p>🔴 <b>{@code AGENT_NO_ANSWER} 를 {@code AGENT_UPSTREAM_ERROR} 로 뭉개지 않는다</b>(4122) —
 * 같은 502 라도 {@code code} 가 달라야 로그·프론트 문구가 갈린다. 여기 코드는 분기 없이
 * {@code httpStatus()} 를 그대로 쓰므로 <b>enum 을 늘리는 것만으로 매핑이 완성</b>된다.
 */
@RestControllerAdvice(assignableTypes = AiSessionController.class)
public class AgentApiExceptionHandler {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(AgentApiExceptionHandler.class);

    @ExceptionHandler(AgentApiException.class)
    public ResponseEntity<Map<String, String>> handle(AgentApiException e) {
        // 🔴 code 만 남긴다 — 스택트레이스·upstream 본문은 남기지 않는다.
        log.warn("[agent-api] 요청 거절 — code={}, status={}", e.getCode().name(), e.httpStatus());
        return ResponseEntity.status(e.httpStatus()).body(Map.of("code", e.getCode().name()));
    }
}
