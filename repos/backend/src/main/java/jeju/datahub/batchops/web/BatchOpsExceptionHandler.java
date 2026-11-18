package jeju.datahub.batchops.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jeju.datahub.batchops.service.BatchOpsProxyException;

/**
 * {@link BatchOpsProxyException} → {@code {code, message}} (TSK-4337 Step 7).
 *
 * <p>🔴 범위를 {@link BatchOpsController} 하나로 좁힌다 — {@code AgentApiExceptionHandler} 와 같은 이유(다른 컨트롤러의
 * {@code ResponseStatusException} 기본 응답 모양을 바꾸지 않는다). 최상위 {@code Exception} 은 잡지 않는다.
 * 메시지는 고정 문구다 — 배치 응답 원문·예외 메시지를 싣지 않는다.
 */
@RestControllerAdvice(assignableTypes = BatchOpsController.class)
public class BatchOpsExceptionHandler {

    @ExceptionHandler(BatchOpsProxyException.class)
    public ResponseEntity<Map<String, String>> handle(BatchOpsProxyException e) {
        // 🔴 message 는 코드별 고정 문구 — 예외 메시지를 그대로 싣지 않는다(시큐어코딩 4.1 — TSK-4336).
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getCode(), "message", messageOf(e.getCode())));
    }

    /** 문구는 {@link BatchOpsProxyException} 의 생성 문구와 같게 둔다(API 계약 — docs/API-guide/batch-ops.md). */
    static String messageOf(String code) {
        return switch (code) {
            case BatchOpsProxyException.NOT_CONFIGURED -> "배치 운영 연결이 설정되지 않았습니다";
            case BatchOpsProxyException.UNAVAILABLE -> "배치 서버에 연결할 수 없습니다";
            case BatchOpsProxyException.INVALID_PATH -> "잡 이름 형식이 잘못됐습니다";
            default -> "배치 운영 요청을 처리하지 못했습니다";
        };
    }
}
