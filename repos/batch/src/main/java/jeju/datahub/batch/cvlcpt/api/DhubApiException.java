package jeju.datahub.batch.cvlcpt.api;

/**
 * DHubManager 호출 실패.
 *
 * <p>⚠️ <b>메시지에 {@code access_token}·{@code password} 를 절대 싣지 않는다.</b> 이 예외는
 * Job 실패 로그에 스택트레이스째로 남는다. HTTP 라이브러리 예외를 감쌀 때도 원문 메시지 대신
 * <b>예외 타입 이름과 상태 코드만</b> 남기고 원인은 {@code cause} 로 전달한다
 * ({@code VworldGeocodingClient} 와 같은 관례).
 */
public class DhubApiException extends RuntimeException {

    public DhubApiException(String message) {
        super(message);
    }

    public DhubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
