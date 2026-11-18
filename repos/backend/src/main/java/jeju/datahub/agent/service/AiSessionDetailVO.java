package jeju.datahub.agent.service;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 대화 세션 상세 — {@code GET /api/ai/sessions/{sessionId}} 응답 (TSK-4121 Step 2).
 *
 * <pre>
 * 필드 대응표 (D5) — 원천 snake_case → 우리 camelCase.
 *   session_id → sessionId | messages[] → messages[] ({@link AiMessageVO})
 *   context.used_tokens    → context.usedTokens
 *   context.system_tokens  → context.systemTokens
 *   context.history_tokens → context.historyTokens
 *   context.budget_tokens  → context.budgetTokens
 *   context.limit_tokens   → context.limitTokens
 * </pre>
 *
 * <p>🔴 최상위 {@code pending_approval} · {@code state} 는 <b>내리지 않는다</b>(화면이 쓰지 않는다).
 * <p>🔴 원천 스펙이 바뀌면 이 클래스와 {@code AgentApiClient} 의 매핑 한 곳만 고친다.
 */
@Schema(description = "AI 대화 세션 상세")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AiSessionDetailVO {

    @Schema(description = "세션 ID", example = "current")
    private String sessionId;

    @Schema(description = "메시지 목록 (user · assistant 만)")
    private List<AiMessageVO> messages = new ArrayList<>();

    @Schema(description = "컨텍스트 토큰 사용량 (없을 수 있다)")
    private ContextVO context;

    /**
     * 컨텍스트 토큰 사용량 <b>5필드</b> (2026-09-03 실측 — 문서에 없던 것이다).
     *
     * <p>별도 파일로 두지 않는 이유 — 이 값은 세션 상세 응답 밖에서 쓰이지 않는다.
     * 값이 없을 수 있으므로 원시타입이 아니라 {@link Long} 이다({@code 0} 과 "모름" 을 구분한다).
     */
    @Schema(description = "컨텍스트 토큰 사용량")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    public static class ContextVO {

        @Schema(description = "사용 토큰 수 (used_tokens)", example = "1820")
        private Long usedTokens;

        @Schema(description = "시스템 프롬프트 토큰 수 (system_tokens)", example = "640")
        private Long systemTokens;

        @Schema(description = "대화 이력 토큰 수 (history_tokens)", example = "1180")
        private Long historyTokens;

        @Schema(description = "예산 토큰 수 (budget_tokens)", example = "8000")
        private Long budgetTokens;

        @Schema(description = "상한 토큰 수 (limit_tokens)", example = "16000")
        private Long limitTokens;
    }
}
