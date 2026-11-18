package jeju.datahub.batch.cvlcpt.api;

/**
 * DHubManager 가 <b>HTTP 401</b> 로 답했다 — 토큰이 만료됐거나 유효하지 않다.
 *
 * <p>이 예외만 {@link DhubAuthClient#withToken} 의 <b>재로그인 후 1회 재시도</b> 대상이다.
 * 다른 실패({@link DhubApiException})는 재시도하지 않는다 — 재로그인해도 결과가 같기 때문이다.
 *
 * <p>재시도 후 또 401 이면 이 예외가 그대로 위로 전파된다(무한 재로그인 루프 방지).
 */
public class DhubUnauthorizedException extends DhubApiException {

    public DhubUnauthorizedException(String message) {
        super(message);
    }
}
