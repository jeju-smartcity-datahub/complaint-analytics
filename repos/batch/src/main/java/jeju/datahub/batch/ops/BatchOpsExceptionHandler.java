package jeju.datahub.batch.ops;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 운영 API 오류 변환 — {@link BatchOpsController} 한정 (TSK-4337 Step 4).
 *
 * <p>🔴 {@code assignableTypes} 로 범위를 묶는다 — 전역이면 기존 트리거({@code POST /api/batch/*})의 오류 응답
 * 형식까지 바뀐다(Step 4 는 기존 트리거 요청·응답 불변이 조건이다).
 */
@RestControllerAdvice(assignableTypes = BatchOpsController.class)
public class BatchOpsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BatchOpsExceptionHandler.class);

    @ExceptionHandler(BatchOpsException.class)
    public ResponseEntity<Map<String, Object>> handle(BatchOpsException e) {
        if (e.status().is5xxServerError()) {
            // 4.1(TSK-4336) — getMessage() 를 메시지 인자로 싣지 않는다. 5xx 는 내부 결함이라 스택은 ERROR 에 남긴다.
            log.error("[ops] {} 내부 오류", e.code(), e);
        }
        return ResponseEntity.status(e.status()).body(e.body());
    }

    /**
     * stop·abandon 이 읽은 뒤 다른 쪽(실행 중인 Job·다른 요청)이 같은 실행 행을 먼저 갱신했다 — version 충돌.
     * 상태를 다시 읽고 재시도하면 되는 경합이라 409 다.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> concurrentUpdate(OptimisticLockingFailureException e) {
        log.warn("[ops] CONCURRENT_UPDATE — {}", e.getClass().getSimpleName());
        log.debug("[ops] CONCURRENT_UPDATE 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만 (TSK-4336)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "CONCURRENT_UPDATE",
                "message", "같은 실행이 동시에 갱신됐습니다 — 상태를 다시 조회한 뒤 시도하세요"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_BODY", "message", "요청 본문을 읽을 수 없습니다(JSON 형식 확인)"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        // TSK-4337 Step 5 — 쿼리 파라미터(로그 tailKb·fromByte)는 경로가 아니다 — 코드를 갈라 화면이 문구를 고르게 한다.
        if (e.getParameter().hasParameterAnnotation(RequestParam.class)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", "INVALID_PARAM", "message", "파라미터 형식이 잘못됐습니다: " + e.getName()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_PATH", "message", "경로 값 형식이 잘못됐습니다: " + e.getName()));
    }
}
