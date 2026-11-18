package jeju.datahub.auth.service.impl;

/**
 * {@link DhubTokenExchangeClient} 의 단일 실패 타입.
 *
 * <p>4xx(OAuth2 {@code error} 응답)·5xx·타임아웃·응답 파싱 실패가 <b>전부 이 타입 하나</b>로 온다 —
 * 호출부(콜백의 {@code dhub2-manager} 교환은 삼키고, 갱신 경로는 503 으로 올린다)가
 * 실패 원인마다 catch 를 따로 두지 않게 하기 위해서다.
 *
 * <p>🔴 메시지는 OAuth2 {@code error} 코드(예: {@code invalid_grant}, I/O 실패는
 * {@code invalid_token_response}) <b>고정</b>이다 — 응답 본문·{@code error_description}·토큰을 싣지 않는다.
 * 예외 메시지는 로그·에러 응답으로 흘러 나가는 경로라 그대로 유출 경로가 된다(선례:
 * {@code PasswordLoginException}). 원인은 cause 로만 전달한다.
 */
public class DhubTokenExchangeException extends RuntimeException {

    private final String errorCode;

    public DhubTokenExchangeException(String errorCode) {
        this(errorCode, null);
    }

    public DhubTokenExchangeException(String errorCode, Throwable cause) {
        super(errorCode, cause);   // 🔴 메시지에 응답 본문·토큰을 넣지 않는다
        this.errorCode = errorCode;
    }

    /** OAuth2 {@code error} 코드 — 메시지와 같은 값이다. */
    public String getErrorCode() {
        return errorCode;
    }
}
