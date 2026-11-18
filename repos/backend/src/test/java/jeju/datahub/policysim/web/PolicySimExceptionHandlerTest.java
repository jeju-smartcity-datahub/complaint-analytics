package jeju.datahub.policysim.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jeju.datahub.policysim.service.PolicySimParamMissingException;

/**
 * 503 {@code POLICY_SIM_PARAM_MISSING} 핸들러.
 *
 * <p>계수 행을 실제로 지워 503 을 재현하면 공유 DB 의 설정 표를 건드리게 되므로 핸들러를 직접 부른다.
 */
class PolicySimExceptionHandlerTest {

    @Test
    void paramMissingIs503WithCodeOnly() {
        ResponseEntity<Map<String, String>> res = new PolicySimExceptionHandler()
                .handleParamMissing(new PolicySimParamMissingException(List.of("CST_VACLOT")));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        // 본문은 code 한 키뿐 — 빠진 vrbl_cd 를 싣지 않는다
        assertThat(res.getBody()).containsExactly(Map.entry("code", "POLICY_SIM_PARAM_MISSING"));
    }

    @Test
    void adviceIsScopedToPolicySimControllerOnly() {
        RestControllerAdvice advice = PolicySimExceptionHandler.class.getAnnotation(RestControllerAdvice.class);
        assertThat(advice.assignableTypes()).containsExactly(PolicySimController.class);
        assertThat(advice.basePackages()).isEmpty();
        assertThat(advice.value()).isEmpty();
    }
}
