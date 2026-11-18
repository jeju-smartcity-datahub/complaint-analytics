package jeju.datahub.agent.service;

/**
 * 모델별 응답 후처리 필터.
 *
 * <p>🔴 <b>{@code <think>} 같은 잡음은 모델 고유 습성이지 Agent API 의 계약이 아니다.</b>
 * 지금 모델 {@code minimax} 는 붙이지만 {@code exaone}·{@code claude}·{@code gpt} 로 갈면
 * 대신 다른 것이 붙거나 아무것도 안 붙는다. 그래서 제거 규칙을
 * {@link jeju.datahub.agent.service.impl.AgentApiClient} 에 박지 않고
 * <b>{@code app.agent.model} 이름 → 구현</b>으로 고른다 — 모델을 바꾸는 사람이 클라이언트를 열어
 * 정규식을 고치지 않게 하기 위해서다.
 *
 * <p>🔴 <b>(전송)가 같은 필터를 통과시킨다.</b> 한쪽만 걸면 "이력에는 없던 {@code <think>} 가
 * 방금 받은 답변에만 보인다" 가 된다 — 그래서 공용 컴포넌트다. 4122 는 <b>재사용만</b> 한다.
 *
 * <p>🔴 <b>필터는 문자열만 다룬다.</b> 제거 후 {@code content} 가 비면 그 메시지를 떨구는 판정은
 * 필터가 아니라 호출부({@code AgentApiClient})에 남긴다 — 그것은 모델 습성이 아니라 우리 표시 규칙이다.
 *
 * <p>구현체는 {@code @Component} 로 등록하고 <b>모델 이름을 스스로 밝힌다</b>({@link #modelName()}).
 * 선택은 {@code AgentApiClient} 가 {@code props.getModel()} 로 한다.
 */
public interface AgentResponseFilter {

    /**
     * 응답 본문 후처리.
     *
     * @param content 업스트림이 준 본문. 🔴 {@code null} 은 넘어오지 않는다(호출부가 걸러 준다)
     * @return 화면에 내려도 되는 본문. 비어도 된다 — 떨굴지는 호출부가 정한다
     */
    String apply(String content);

    /** 이 필터가 담당하는 모델 이름 — {@code app.agent.model} 값과 대조되는 키다. */
    String modelName();
}
