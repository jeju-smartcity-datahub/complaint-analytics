package jeju.datahub.agent.service.impl.filter;

import org.springframework.stereotype.Component;

import jeju.datahub.agent.service.AgentResponseFilter;

/**
 * {@code gpt} 응답 필터 — <b>빈 구현</b>(입력을 그대로 돌려준다).
 *
 * <p>🔴 <b>지금 지우지 않는다</b>(TSK-4121 Step 2-B). {@code <think>} 는 {@code minimax} 의 습성이라
 * 이 모델에는 해당이 없지만, 그 모델 특유의 잡음이 발견되면 <b>어디에 넣을지가 이미 정해져 있는 것</b>이
 * 이 단계의 산출물이다. 규칙이 생기면 {@link #apply(String)} 만 채운다.
 */
@Component
public class GptResponseFilter implements AgentResponseFilter {

    /** 🔴 후처리 없음 — 원문 그대로. */
    @Override
    public String apply(String content) {
        return content;
    }

    @Override
    public String modelName() {
        return "gpt";
    }
}
