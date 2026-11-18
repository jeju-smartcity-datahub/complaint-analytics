package jeju.datahub.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import jeju.datahub.agent.service.AgentApiException.Code;

/**
 * {@link AgentTokenStore} 단위 테스트 (TSK-4121 Step 4).
 *
 * <p>🔴 이 클래스가 검증하는 것은 <b>Step 1-A 산출물</b>이다 — 세션에 Agent 토큰이 없을 때
 * {@code withToken} 이 {@code AGENT_TOKEN_UNAVAILABLE}(503)을 던진다는 계약(D8).
 * Step 1-B(토큰 갱신)는 <b>닫혔다</b>(코드 교환 응답에 {@code refresh_token} 이 오지 않는 것을
 * 2026-09-04 실측) — 그래서 갱신 테스트는 두지 않는다
 * <b>(password 경로는 TSK-4134 가 연다 — 갱신 테스트는 {@code AgentTokenRefreshTest} 에 있다)</b>.
 *
 * <p>{@code HttpSession} 은 spring-test 의 {@link MockHttpSession} 을 쓴다(새 의존성 없음).
 * 패키지가 {@code ...agent.service} 인 것은 대상 클래스와 같은 패키지를 유지하기 위해서다.
 */
class AgentTokenStoreTest {

    private final AgentTokenStore store = new AgentTokenStore();

    @Test
    @DisplayName("세션에 Agent 토큰이 없으면 AGENT_TOKEN_UNAVAILABLE(503) 를 던지고 call 을 실행하지 않는다 (D8)")
    void missingTokenThrowsTokenUnavailable() {
        MockHttpSession session = new MockHttpSession();    // 토큰을 한 번도 저장하지 않은 세션

        // 🔴 401 이 아니라 503 이다 — 401 이면 인터셉터가 /login 으로 보내는데,
        //    다시 로그인해도 토큰이 안 생기는 상태면 무한 왕복이 된다(D8).
        Throwable thrown = catchThrowable(() -> store.withToken(session, token -> {
            throw new AssertionError("토큰이 없으면 call 이 실행되면 안 된다");
        }));

        assertThat(thrown).isInstanceOf(AgentApiException.class);
        AgentApiException ex = (AgentApiException) thrown;
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_TOKEN_UNAVAILABLE);
        assertThat(ex.httpStatus()).isEqualTo(503);
    }
}
