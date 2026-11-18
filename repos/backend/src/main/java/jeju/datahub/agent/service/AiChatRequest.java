package jeju.datahub.agent.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 어시스턴트 전송 요청 바디.
 *
 * <p>필드는 {@code message} <b>하나뿐</b>이다.
 * <ul>
 *   <li>🔴 <b>{@code agentId} 를 두지 않는다</b>(4121) — {@code X-Agent-Id} 는 설정
 *       {@code app.agent.agent-id} 에서 {@code AgentApiClient} 가 붙인다. 프론트는 그 값을 모른다.</li>
 *   <li>🔴 <b>{@code sessionId} 를 두지 않는다</b> — 새 대화는 <b>URL 에 세션 ID 가 없는 것</b>으로
 *       표현하고, 백엔드가 {@code portal-{UUID}} 를 만든다. optional 바디로 받으면 프론트가 남의
 *       세션 ID 를 적어 넣어 그 대화에 이어 쓸 수 있다(세션 ID 는 Agent 간 공유된다 — 4121 §2-1).</li>
 *   <li>🔴 <b>{@code model}·{@code stream} 을 두지 않는다</b>(§1 ⑥) — 업스트림 바디에 실을
 *       것이 {@code messages} 하나뿐이라 받을 이유가 없다.</li>
 * </ul>
 *
 * <p>🔴 컨트롤러에서 {@code @Valid} 를 빠뜨리면 아래 검증이 통째로 발동하지 않는다.
 */
@Schema(description = "AI 어시스턴트 전송 요청 (message 하나뿐이다)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AiChatRequest {

    /**
     * 사용자 질문 원문.
     *
     * <p>🔴 <b>상한 4000자</b>(2026-09-04 결정). 없으면 임의 길이 본문이 그대로 업스트림으로 나간다 —
     * 대본 붙여넣기 한 번이 쿼터를 태운다.
     */
    @NotBlank(message = "message 는 필수입니다")
    @Size(max = 4000, message = "message 는 4000자 이내여야 합니다")
    @Schema(description = "사용자 질문 (1~4000자)", example = "연동 지역 주차 민원이 가장 많은 시간대는?",
            maxLength = 4000, requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;
}
