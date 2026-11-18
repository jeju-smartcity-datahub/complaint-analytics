package jeju.datahub.agent.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 승인이 필요한 도구가 거절된 <b>바로 뒤</b> 답변의 "승인" 문단을 우리 고정 문구로 바꾼다.
 *
 * <p>거절({@code {"approved":false}})을 받은 모델은 "사용자의 승인이 확인되지 않아 …" 처럼 답한다. 우리 화면에는
 * 승인 UI 가 없어 사용자에게는 뜻이 통하지 않는다. 그래서 답변을 빈 줄 기준 문단으로 나눠
 * <b>승인 거절을 말하는 문단</b>({@link #DENIAL_PHRASES})이 있으면 그 문단을 빼고 맨 앞에 {@link #NOTICE} 를 붙인다.
 * 그런 문단이 <b>없으면 원문 그대로</b>다(예: Dtonic 화면에서 직접 승인해 보고서가 만들어진 답변을 덮지 않는다).
 *
 * <ul>
 *   <li>🔴 <b>전송·조회가 이 한 함수를 쓴다</b> — 한쪽만 고치면 새로고침할 때 원문이 다시 보인다.
 *       전송은 {@code AgentApiClient#rejectToolCalls} 가 받은 답에, 조회는 {@code toSessionDetail} 이
 *       {@link #APPROVAL_TOOL_NAMES} 를 부른 assistant <b>바로 다음</b> 답변에 건다.</li>
 *   <li>🔴 <b>Dtonic 이력에는 {@code role:"tool"} 메시지가 남지 않는다</b>(2026-11-18 실측 — 40건 중 0건). 그래서 조회는
 *       "거절 메시지 다음" 이 아니라 <b>"승인 필요 도구를 부른 다음"</b> 으로 찾는다. 데이터 조회 같은 일반 도구도 같은
 *       모양({@code tool_calls} → 답변)으로 남으므로 도구 이름으로 거른다.</li>
 *   <li>🔴 단어 "승인" 하나로 거르지 않는다 — 승인 <b>거절을 말하는 표현</b>만 본다(공백 무시).</li>
 * </ul>
 */
final class ToolRejectReplyRewriter {

    /** 사용자에게 보일 고정 문구 (사용자 확정 2026-11-18). */
    static final String NOTICE = "요청하신 보고서 생성이 정상적으로 완료되지 못하고 중단되었습니다. 다시 한 번 요청해 주시기 바랍니다.";

    /** 실행 전에 사용자 승인을 기다리는 도구 — 조회 경로가 이 이름으로 "거절 뒤 답변" 을 찾는다. 늘면 여기 더한다. */
    static final Set<String> APPROVAL_TOOL_NAMES = Set.of("reports_generate_from_conversation");

    /** 승인 거절을 말하는 표현 — 공백을 뺀 형태로 비교한다. 늘릴 때는 실제 답변 문구를 근거로 한다. */
    static final List<String> DENIAL_PHRASES = List.of(
            "사용자의승인", "승인이확인", "승인되지", "승인을받지", "승인을얻지", "승인이필요",
            "승인하지", "승인이거부", "승인이거절", "승인이없", "승인없이", "승인을거부", "승인을거절");

    private ToolRejectReplyRewriter() {}

    /** 승인 거절 문단이 있으면 고정 문구 + 나머지 문단, 없으면 원문 그대로. */
    static String rewrite(String content) {
        if (content == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        boolean dropped = false;
        for (String p : content.strip().split("\\n\\s*\\n")) {
            if (mentionsDenial(p)) {
                dropped = true;
            } else if (!p.isBlank()) {
                kept.add(p.strip());
            }
        }
        if (!dropped) {
            return content;
        }
        return kept.isEmpty() ? NOTICE : NOTICE + "\n\n" + String.join("\n\n", kept);
    }

    static boolean mentionsDenial(String paragraph) {
        String compact = paragraph.replaceAll("\\s+", "");
        for (String phrase : DENIAL_PHRASES) {
            if (compact.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /** assistant 메시지의 {@code tool_calls[].function.name} 중 승인 필요 도구가 있는가. */
    static boolean callsApprovalTool(JsonNode message) {
        for (JsonNode c : message.path("tool_calls")) {
            JsonNode name = c.path("function").path("name");
            if (name.isTextual() && APPROVAL_TOOL_NAMES.contains(name.asText())) {
                return true;
            }
        }
        return false;
    }
}
