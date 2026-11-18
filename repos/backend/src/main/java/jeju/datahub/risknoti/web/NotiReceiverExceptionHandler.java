package jeju.datahub.risknoti.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jeju.datahub.risknoti.service.NotiReceiverDuplicatedException;

/**
 * {@link NotiReceiverDuplicatedException} → <b>409 {@code {"code":"RCVR_DUPLICATED"}}</b> (TSK-4250 R9).
 *
 * <p>🔴 범위를 {@link NotiReceiverController} 하나로 좁힌다({@code PolicySimExceptionHandler}·
 * {@code AgentApiExceptionHandler} 와 같은 이유) — 다른 컨트롤러는 {@code ResponseStatusException} 을
 * Boot 기본 처리에 얹고 있다. 이 컨트롤러의 400·404 도 그대로 {@code ResponseStatusException} 이다.
 * <p>🔴 최상위 {@code Exception}·{@code DataIntegrityViolationException} 을 잡지 않는다 — 제약명 판별은
 * 서비스가 하고, 그 밖의 무결성 위반은 정규화 버그라 500 으로 드러나야 한다.
 * <p>본문은 {@code code} 한 키뿐이다 — {@code userId} 를 싣지 않는다.
 */
@RestControllerAdvice(assignableTypes = NotiReceiverController.class)
public class NotiReceiverExceptionHandler {

    @ExceptionHandler(NotiReceiverDuplicatedException.class)
    public ResponseEntity<Map<String, String>> handleDuplicated(NotiReceiverDuplicatedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", NotiReceiverDuplicatedException.CODE));
    }
}
