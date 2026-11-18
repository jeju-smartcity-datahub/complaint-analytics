package jeju.datahub.agent.service.impl.filter;

import org.springframework.stereotype.Component;

import jeju.datahub.agent.service.AgentResponseFilter;

/**
 * {@code deepseek} 응답 필터 — <b>빈 구현</b>(입력을 그대로 돌려준다).
 *
 * <p>🔴 <b>가정이 아니라 다른 Agent 가 실제로 쓰는 모델이다</b>(TSK-4122 D10 · §2-3 #6).
 * Agent 마다 LLM 이 달라 {@code app.agent.agent-id} 를 바꾸면 {@code app.agent.model} 도 함께
 * 바뀌는데, 그때 고를 자리가 없으면 미등록으로 접혀 no-op + WARN 이 된다. 자리만 먼저 만들어 둔다.
 *
 * <p>🔴 <b>규칙을 지어내 넣지 않는다</b> — 이 모델이 어떤 잡음을 붙이는지 아직 확인하지 않았다.
 * 잡음이 발견되면 {@link #apply(String)} 만 채운다. {@code exaone}·{@code claude}·{@code gpt} 의
 * 빈 구현과 같은 형태다.
 */
@Component
public class DeepseekResponseFilter implements AgentResponseFilter {

    /** 🔴 후처리 없음 — 원문 그대로. */
    @Override
    public String apply(String content) {
        return content;
    }

    @Override
    public String modelName() {
        return "deepseek";
    }
}
