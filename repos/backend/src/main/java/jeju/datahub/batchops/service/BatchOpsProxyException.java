package jeju.datahub.batchops.service;

import org.springframework.http.HttpStatus;

/**
 * 배치 운영 명령 프록시가 <b>스스로 만드는</b> 오류 (TSK-4337 Step 7) — 본문 {@code {code, message}}.
 *
 * <table>
 *   <caption>코드 → 상태</caption>
 *   <tr><td>BATCH_OPS_NOT_CONFIGURED</td><td>503 — {@code app.batch-ops.base-url} 이 비었다</td></tr>
 *   <tr><td>BATCH_UNAVAILABLE</td><td>503 — 연결 실패·타임아웃·배치 401/5xx·예상 밖 응답</td></tr>
 *   <tr><td>INVALID_PATH</td><td>400 — 잡 이름 형식</td></tr>
 * </table>
 *
 * <p>🔴 배치가 낸 400·404·409 는 이 예외가 아니다 — 상태·본문 그대로 전달한다({@code BatchOpsController}).
 * 🔴 배치 401 을 그대로 넘기지 않는다 — 프론트가 로그인으로 튕긴다(AI 어시스턴트 503 관례와 같은 이유).
 */
public class BatchOpsProxyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String NOT_CONFIGURED = "BATCH_OPS_NOT_CONFIGURED";
    public static final String UNAVAILABLE = "BATCH_UNAVAILABLE";
    public static final String INVALID_PATH = "INVALID_PATH";

    private final HttpStatus status;
    private final String code;

    public BatchOpsProxyException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static BatchOpsProxyException notConfigured() {
        return new BatchOpsProxyException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED,
                "배치 운영 연결이 설정되지 않았습니다");
    }

    public static BatchOpsProxyException unavailable() {
        // 🔴 원인을 단정하지 않는다 — 꺼짐·네트워크·키 불일치가 같은 503 이다
        return new BatchOpsProxyException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE,
                "배치 서버에 연결할 수 없습니다");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
