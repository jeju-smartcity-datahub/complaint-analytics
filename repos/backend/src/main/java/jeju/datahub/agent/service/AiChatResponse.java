package jeju.datahub.agent.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 어시스턴트 전송 응답 (TSK-4122 Step 2).
 *
 * <p>🔴 <b>조립 주체는 컨트롤러다.</b> {@code AgentApiClient#sendMessage} 는 {@link AiMessageVO} 만
 * 돌려준다({@code listSessions}·{@code getSession} 과 같은 결). 클라이언트가 이 타입을 돌려주게 하면
 * 세션 ID 생성이 클라이언트로 내려가 D6·D7 과 어긋난다 — 만든 주체가 실어야 한다.
 *
 * <p>🔴 {@link #sessionId} 는 <b>실제로 업스트림에 보낸 {@code X-Session-Id} 값 그대로</b>다.
 * 새 대화면 컨트롤러가 만든 {@code portal-{UUID}}, 이어쓰기면 URL 의 값이다. 프론트는 이 값으로
 * 다음 턴을 이어 보내고 목록·상세를 조회한다.
 */
@Schema(description = "AI 어시스턴트 전송 응답 — 사용된 세션 ID 와 assistant 답변 1건")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {

    @Schema(description = "이 전송에 실제로 사용된 세션 ID (새 대화면 백엔드가 만든 portal-{UUID})",
            example = "portal-6f1c2f0e-9f2b-4a2e-9a0f-2a7d5b3c8e11")
    private String sessionId;

    @Schema(description = "assistant 답변 1건 (<think> 블록 제거 후)")
    private AiMessageVO message;
}
