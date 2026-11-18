package jeju.datahub.agent.service;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 대화 메시지 1건 — {@code GET /v1/sessions/{id}} 의 {@code messages[]} 원소.
 *
 * <pre>
 * 필드 대응표 — 원천 snake_case → 우리 camelCase.
 *   role → role | content → content | created_at → createdAt  (실측 확인 — 실제로 온다)
 * </pre>
 *
 * <p>🔁 {@code messages[].completion_id} 는 <b>{@code msgId} 로 내린다</b>(종전
 * "내리지 않는다" 폐기). 보고서 저장 요청 {@code {msgId, rptIdx}} 가 이 값으로 메시지를 찾는다.
 * 전송 경로는 응답 최상위 {@code id} 를 쓰며 두 값은 같다(실측). user 메시지는 {@code null}.
 *
 * <p>🆕 {@code reports} — assistant 답변 속 HTML 보고서 영역. {@code content} 의
 * {@code [[AI_REPORT:{idx}]]} 자리에 들어간다. user 메시지는 항상 빈 리스트다.
 *
 * <p>🔴 <b>이 VO 로 들어오지 않는 것 둘</b> — 둘 다 {@code AgentApiClient} 의 매핑에서 떨어진다.
 * <ul>
 *   <li><b>{@code role} 이 {@code system}·{@code tool} 인 메시지</b>. 프론트에서 거르면 내부
 *       프롬프트가 응답 바디에 실려 브라우저까지 간다 — 화면에 안 그려도 DevTools 에 남는다.</li>
 *   <li><b>{@code <think>…</think>} 를 뗀 뒤 {@code content} 가 빈 메시지</b>. 실측상 assistant
 *       메시지 19/19 가 {@code <think>} 로 시작하고 그 안은 모델의 내부 추론(영어)이다.
 *       {@code rehype-sanitize} 로는 못 막는다 — 태그만 지우고 안의 텍스트는 남기기 때문이다.</li>
 * </ul>
 */
@Schema(description = "AI 대화 메시지 1건 (role 은 user · assistant 뿐)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AiMessageVO {

    @Schema(description = "발화 주체 — user | assistant (system · tool 은 내려오지 않는다)",
            example = "assistant")
    private String role;

    @Schema(description = "본문 (마크다운). <think> 추론 블록은 제거된 상태다",
            example = "**연동** 지역 민원이 가장 많습니다.")
    private String content;

    @Schema(description = "작성 일시 (원천 문자열 그대로, 없을 수 있다)",
            example = "2026-09-03T10:12:33.120Z")
    private String createdAt;

    @Schema(description = "메시지 식별자 — assistant 는 업스트림 completion_id(전송 응답은 최상위 id, 같은 값). user 는 null",
            example = "chatcmpl-6b098ee996e7")
    private String msgId;

    @Schema(description = "답변 속 HTML 보고서 영역 — content 의 [[AI_REPORT:{idx}]] 와 짝. assistant 만 채우고 user 는 빈 배열")
    private List<AiReportBlockVO> reports = new ArrayList<>();
}
