package jeju.datahub.policysim.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jeju.datahub.policysim.service.PolicySimParamMissingException;

/**
 * {@link PolicySimParamMissingException} → <b>503 {@code {"code":"POLICY_SIM_PARAM_MISSING"}}</b> (TSK-4037 Step 5).
 *
 * <p>🔴 범위를 {@link PolicySimController} 하나로 좁힌다({@code AgentApiExceptionHandler} 와 같은 이유) —
 * 다른 컨트롤러는 {@code ResponseStatusException} 을 Boot 기본 처리에 얹고 있다.
 * <p>🔴 최상위 {@code Exception} 을 잡지 않고 ResponseEntity 계열 기본 예외 처리 기반 클래스를 상속하지 않는다 —
 * 그러면 이 컨트롤러의 400(바인딩·{@code ResponseStatusException}) 응답 형태까지 바뀐다.
 * <p>🔴 본문은 {@code code} 한 키뿐이다 — 빠진 {@code vrbl_cd} 는 싣지 않는다. ERROR 로그는 던지는 쪽
 * ({@code PolicySimParams.resolve})이 이미 1줄 남기므로 여기서 또 남기지 않는다.
 */
@RestControllerAdvice(assignableTypes = PolicySimController.class)
public class PolicySimExceptionHandler {

    @ExceptionHandler(PolicySimParamMissingException.class)
    public ResponseEntity<Map<String, String>> handleParamMissing(PolicySimParamMissingException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", PolicySimParamMissingException.CODE));
    }
}
