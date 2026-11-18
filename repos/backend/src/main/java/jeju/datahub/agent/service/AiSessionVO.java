package jeju.datahub.agent.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 대화 세션 1건 — {@code GET /v1/sessions} 응답 원소.
 *
 * <pre>
 * 필드 대응표 — 원천 snake_case → 우리 camelCase.
 *   session_id → sessionId | agent_id → agentId | agent_name → agentName
 *   title → title | preview → preview | created_at → createdAt | updated_at → updatedAt
 * </pre>
 *
 * <p>🔴 원천의 {@code sessions[].id} 는 <b>내리지 않는다</b> — 키는 {@code session_id} 하나다.
 * <p>🔴 원천 스펙이 바뀌면 이 클래스와 {@code AgentApiClient} 의 매핑 한 곳만 고친다.
 */
@Schema(description = "AI 대화 세션 1건")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AiSessionVO {

    @Schema(description = "세션 ID (상세 조회 키)", example = "current")
    private String sessionId;

    @Schema(description = "Agent ID (표시용)", example = "agent.0edd4421-52b8-4a36-9609-930411b91324")
    private String agentId;

    @Schema(description = "Agent 이름", example = "제주 주차 어시스턴트")
    private String agentName;

    @Schema(description = "세션 제목 (없을 수 있다)", example = "연동 주차 민원 현황")
    private String title;

    @Schema(description = "미리보기 문구 (없을 수 있다)", example = "연동 지역 민원이 가장 많은 시간대는...")
    private String preview;

    @Schema(description = "생성 일시 (원천 문자열 그대로)", example = "2026-09-03T10:12:33.120Z")
    private String createdAt;

    @Schema(description = "수정 일시 (원천 문자열 그대로)", example = "2026-09-03T10:31:02.004Z")
    private String updatedAt;
}
