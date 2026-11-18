package jeju.datahub.agent.service.impl.filter;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import jeju.datahub.agent.service.AgentResponseFilter;

/**
 * {@code minimax} 응답 필터 — {@code <think>…</think>} 내부 추론 제거.
 *
 * <p>실측(2026-09-03): assistant 메시지 <b>19/19</b> 가 {@code <think>} 로 시작하고 그 안은 모델의
 * 내부 추론(영어)이다. 🔴 {@code rehype-sanitize} 로는 못 막는다 — 태그만 지우고 <b>안의 텍스트는
 * 남긴다</b>. 그래서 백엔드에서 뗀다(프론트에서 빼면 추론 문자열이 응답 바디로 브라우저까지 간다).
 *
 * <p>🔴 <b>규칙을 그대로 옮긴 것이다</b>. 정규식을 "개선" 하지 않는다 —
 * 이 이동은 <b>동작을 바꾸지 않는다</b>.
 */
@Component
public class MinimaxResponseFilter implements AgentResponseFilter {

    /**
     * 여는 태그부터 <b>첫</b> {@code </think>} 까지 (비탐욕 {@code .*?} + {@code DOTALL}).
     * 여러 블록이 있어도 {@code replaceAll} 이 전부 지운다.
     */
    private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>");

    /**
     * 닫는 태그가 없는 경우 — 여는 태그 <b>이후 전체를 버린다</b>.
     * 추론이 새는 것보다 답변이 잘리는 편이 낫다. ①을 먼저 돌린 뒤 남은 여는 태그에만 걸린다.
     */
    private static final Pattern THINK_UNCLOSED = Pattern.compile("(?s)<think>.*$");

    /**
     * <ol>
     *   <li>여는 태그부터 <b>첫</b> {@code </think>} 까지 지운다(비탐욕 매칭). 블록이 여럿이면 전부.</li>
     *   <li>남은 여는 태그가 있으면(=닫는 태그가 없다) <b>그 이후 전체를 버린다</b>.</li>
     * </ol>
     * 결과가 공백뿐이면 호출부가 그 메시지를 떨군다 — 그 판정은 여기 두지 않는다.
     */
    @Override
    public String apply(String content) {
        if (content == null) {
            return "";
        }
        String stripped = THINK_BLOCK.matcher(content).replaceAll("");
        stripped = THINK_UNCLOSED.matcher(stripped).replaceAll("");
        return stripped.strip();
    }

    @Override
    public String modelName() {
        return "minimax";
    }
}
