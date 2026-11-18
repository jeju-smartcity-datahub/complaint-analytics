package jeju.datahub.report.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jeju.datahub.agent.service.AgentApiException;

/**
 * {@link AiReportController} 한정 {@link AgentApiException} → HTTP 변환 (TSK-4293 Step 5).
 *
 * <p>🔴 {@code agent.web.AgentApiExceptionHandler} 는 {@code assignableTypes = AiSessionController.class}
 * 한정이라 여기서 던진 {@code AgentApiException} 은 그대로 두면 <b>500</b> 이다. 그쪽 {@code assignableTypes}
 * 에 이 컨트롤러를 더하면 {@code agent} → {@code report} 역참조가 생기므로 같은 방식을 여기에 따로 둔다.
 *
 * <p>응답 바디는 {@code code} 하나뿐이다 — 예외 메시지·upstream 본문을 싣지 않는다. 최상위
 * {@code Exception} 은 잡지 않는다(400·404 는 {@code ResponseStatusException} 그대로 Boot 기본 처리).
 */
@RestControllerAdvice(assignableTypes = AiReportController.class)
public class AiReportExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiReportExceptionHandler.class);

    @ExceptionHandler(AgentApiException.class)
    public ResponseEntity<Map<String, String>> handle(AgentApiException e) {
        log.warn("[ai-report] 요청 거절 — code={}, status={}", e.getCode().name(), e.httpStatus());
        return ResponseEntity.status(e.httpStatus()).body(Map.of("code", e.getCode().name()));
    }
}
