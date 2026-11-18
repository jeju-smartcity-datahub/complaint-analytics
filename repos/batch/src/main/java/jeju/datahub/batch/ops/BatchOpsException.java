package jeju.datahub.batch.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * 운영 API 오류 — 응답 본문 {@code {code, message, ...extra}} (TSK-4337 Step 4).
 *
 * <p>배치 앱에는 정해진 오류 응답 관례가 없어(키 필터의 {@code {"error":…}}·{@code ResponseStatusException} 뿐)
 * 운영 API 만 {@code code} 를 싣는다 — 백엔드 프록시(Step 7)가 409·400·404 를 그대로 전달하고 화면이 코드로 문구를 고른다.
 */
public class BatchOpsException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extra;
    /**
     * 응답에 싣는 문구 — 호출부가 직접 쓴 고정 문구다. 🔴 하위 예외의 {@code getMessage()} 를 이어 붙이지 않는다
     * (시큐어코딩 4.1 오류 메시지 정보노출 — TSK-4336). 상세 원인은 호출부가 서버 로그에 남긴다.
     */
    private final String clientMessage;

    public BatchOpsException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public BatchOpsException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.clientMessage = message;
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 응답 본문 — {@code code}·{@code message} 뒤에 부가 필드. */
    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", clientMessage);
        body.putAll(extra);
        return body;
    }

    static BatchOpsException badRequest(String code, String message) {
        return new BatchOpsException(HttpStatus.BAD_REQUEST, code, message);
    }

    static BatchOpsException notFound(String code, String message) {
        return new BatchOpsException(HttpStatus.NOT_FOUND, code, message);
    }
}
