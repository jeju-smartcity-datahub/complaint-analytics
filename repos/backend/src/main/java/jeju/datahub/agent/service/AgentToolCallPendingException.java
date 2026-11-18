package jeju.datahub.agent.service;

import java.util.List;

/**
 * 전송 응답이 <b>답변 없이 도구 호출({@code tool_calls})만</b> 담고 왔다는 신호 (TSK-4345).
 *
 * <p>업스트림 Agent 는 일부 도구(예: {@code reports_generate_from_conversation} — 슬라이드 보고서 생성)를
 * 실행하기 전에 <b>사용자 승인</b>을 기다린다. 이때 {@code content} 는 비고 {@code tool_calls} 만 온다.
 * 우리 화면은 승인 UI 를 두지 않기로 했으므로(TSK-4345 결정) 컨트롤러가 이 신호를 받아
 * <b>자동 거절 1회</b>({@code AgentApiClient#rejectToolCalls})를 보내고 그 답변을 보여 준다.
 *
 * <ul>
 *   <li>🔴 <b>{@link AgentApiException} 이 아니다</b> — {@code withToken} 의 401 재시도 판정과
 *       예외 핸들러의 코드 매핑에 걸리지 않게 일부러 갈랐다. 이 예외를 잡는 곳은
 *       {@code AiSessionController#dispatch} 한 곳뿐이다(놓치면 500 이다).</li>
 *   <li>🔴 <b>{@code arguments} 원문을 싣지 않는다</b> — 대화 내용이 그대로 들어 있어 로그·응답으로
 *       새면 안 된다. 거절에 필요한 것은 호출 ID 뿐이다.</li>
 * </ul>
 */
public class AgentToolCallPendingException extends RuntimeException {

    private final List<String> toolCallIds;
    private final List<String> toolNames;

    public AgentToolCallPendingException(List<String> toolCallIds, List<String> toolNames) {
        super("AGENT_TOOL_CALL_PENDING");   // 🔴 고정 문구 — 업스트림 값을 메시지에 넣지 않는다
        this.toolCallIds = List.copyOf(toolCallIds);
        this.toolNames = List.copyOf(toolNames);
    }

    /** 거절 메시지의 {@code tool_call_id} 로 그대로 되돌려 보낼 값. 비어 있지 않다. */
    public List<String> getToolCallIds() { return toolCallIds; }

    /** 로그용 도구 이름(정제됨). */
    public List<String> getToolNames() { return toolNames; }
}
