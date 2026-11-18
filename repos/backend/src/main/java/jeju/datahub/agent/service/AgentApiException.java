package jeju.datahub.agent.service;

/**
 * Agent API 프록시 계층의 단일 예외 타입.
 *
 * <p>🔴 <b>이 예외가 구현에 사는 이유</b> — {@link AgentTokenStore#withToken} 이 이것을 던진다.
 * ({@code AgentApiClient})에 두면 의존 순서(1-A → 2)가 역행해 1-A 의 {@code compileJava} 가
 * 통과할 수 없다. 백엔드 전 단계(1-A·2·3)가 공유하는 한 벌의 계약이므로 가장 앞 단계가 소유한다.
 *
 * <p>🔴 <b>메시지에 upstream 응답 본문·토큰을 넣지 않는다.</b> 메시지는 {@code code.name()} 고정이다 —
 * 예외 메시지는 로그·에러 응답으로 흘러 나가는 경로라 그대로 유출 통로가 된다.
 *
 * <p>🔴 <b>{@code AGENT_NO_ANSWER} 가 늘어 6종 → 7종이 됐다</b>.
 * {@code AGENT_UPSTREAM_ERROR}(연결 실패·5xx·타임아웃)와 <b>분리</b>한다 — 같은 502 라도
 * <i>"서버에 못 닿았다"</i> 와 <i>"닿았는데 답을 못 만들었다"</i> 는 조치가 다르고, 로그에서 즉시
 * 갈려야 원인 추적이 된다. 🔴 {@code Code} 를 늘리면 <b>프론트 4지점</b>({@code AgentErrorCode} 유니온 ·
 * 런타임 배열 {@code AGENT_ERROR_CODES} · {@code AGENT_ERROR_TEXT} ·
 * {@code NO_RETRY_CODES}/{@code RELOGIN_CODES})도 함께 늘려야 한다 — 백엔드만 늘리면 프론트가
 * {@code null} 로 접어 기본 문구가 나간다(4122).
 *
 * <p>🆕 <b>{@code AGENT_TOOL_CALL_UNRESOLVED}(409)가 늘어 8종이 됐다</b> — 도구 호출 자동 거절
 * 뒤에도 또 도구 호출만 온 경우다. {@code AGENT_NO_ANSWER}(502)와 갈라 둔다 — 그쪽은 "잠시 후 다시" 가 의미가
 * 있지만 이쪽은 같은 결과가 반복되므로 관리자 확인이 필요하다.
 */
public class AgentApiException extends RuntimeException {

    public enum Code {
        AGENT_BAD_REQUEST(400),         // sessionId 오염 등 우리 쪽 입력 거절 — AgentApiClient(2)
        AGENT_NOT_CONFIGURED(503),      // base-url·agent-id 빈 값        — 컨트롤러(3)·클라이언트(2)
        AGENT_TOKEN_UNAVAILABLE(503),   // 세션에 토큰 없음           — AgentTokenStore(1-A)
        AGENT_UNAUTHORIZED(401),        // upstream 401 (갱신 후에도)      — AgentApiClient(2)
        AGENT_UPSTREAM_REJECTED(502),   // upstream 403/404 그대로         — AgentApiClient(2)
        AGENT_UPSTREAM_ERROR(502),      // upstream 5xx·타임아웃           — AgentApiClient(2)
        AGENT_NO_ANSWER(502),           // 200 인데 답변이 없다             — AgentApiClient(4122)
        // 자동 거절 뒤에도 또 도구 호출만 왔다. 다시 눌러도 같으므로 502(재시도 유도)가 아니다.
        //    그 대화에는 답 없는 도구 호출이 남아 있다(상태 충돌) → 409 · 화면은 "관리자에게 문의".
        AGENT_TOOL_CALL_UNRESOLVED(409);

        final int status;

        Code(int status) { this.status = status; }   // 🔴 없으면 컴파일되지 않는다
    }

    private final Code code;

    /** 🔴 AGENT_UPSTREAM_REJECTED 일 때만 채운다(403/404). 그 밖에는 null */
    private final Integer upstreamStatus;

    public AgentApiException(Code code) { this(code, null); }

    public AgentApiException(Code code, Integer upstreamStatus) {
        super(code.name());       // 🔴 메시지에 upstream 본문·토큰을 넣지 않는다
        this.code = code;
        this.upstreamStatus = upstreamStatus;
    }

    public Code getCode() { return code; }

    /**
     * 응답으로 내보낼 HTTP 상태.
     *
     * <p>🔴 {@code upstreamStatus} 가 있으면 그것이 이긴다.
     * 🔴 {@code AGENT_UPSTREAM_REJECTED} 의 기본값을 {@code 0} sentinel 로 두지 않는 이유 —
     * 1인자 생성자로 만들면 {@code ResponseEntity.status(0)} 이 {@code IllegalArgumentException} 을
     * 던져 <b>500 + code 없는 본문</b>이 나간다. {@code 502} 폴백이면 최악이라도 "upstream 실패" 로
     * 읽히고, 정상 경로에서는 403/404 가 항상 이긴다.
     */
    public int httpStatus() { return upstreamStatus != null ? upstreamStatus : code.status; }
}
