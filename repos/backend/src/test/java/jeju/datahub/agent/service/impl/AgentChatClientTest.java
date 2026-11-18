package jeju.datahub.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.agent.service.AgentApiException;
import jeju.datahub.agent.service.AgentApiException.Code;
import jeju.datahub.agent.service.AgentProperties;
import jeju.datahub.agent.service.AgentResponseFilter;
import jeju.datahub.agent.service.AgentTokenStore;
import jeju.datahub.agent.service.AiChatRequest;
import jeju.datahub.agent.service.AiChatResponse;
import jeju.datahub.agent.service.AiMessageVO;
import jeju.datahub.agent.service.AiReportBlockVO;
import jeju.datahub.agent.service.impl.filter.MinimaxResponseFilter;
import jeju.datahub.agent.web.AiSessionController;

/**
 * {@link AgentApiClient#sendMessage} 전송 경로 단위 테스트.
 *
 * <p>업스트림 없이 {@link MockRestServiceServer}(spring-test 내장 — <b>새 의존성 없음</b>)로
 * 정상·실패 경로를 검증한다. 조회 경로({@code AgentApiClientTest})와 <b>같은 방식·같은 픽스처</b>다.
 *
 * <h2>🔴 왜 패키지가 {@code ...agent.service.impl} 인가</h2>
 * 목 주입 지점인 <b>3인자 시임</b> {@code AgentApiClient(RestClient, AgentProperties, Map)} 이
 * <b>package-private</b> 이기 때문이다. 종전 파일 경로는
 * {@code src/test/java/jeju/datahub/agent/AgentChatClientTest.java} 였지만 그 패키지에서는 시임에
 * 닿지 못한다 — 선례({@code AgentApiClientTest})와 같은 자리에 둔다.
 * <p>🔴 <b>4인자 생성자를 쓰지 않는다</b>: {@link MockRestServiceServer} 에는 타임아웃이 무의미하고,
 * 3인자 시임이 조회용·전송용을 같은 클라이언트로 보므로 전송 테스트가 그대로 성립한다.
 *
 * <h2>🔴 응답 {@code Content-Type} 은 {@code text/event-stream} 이다</h2>
 * 정상 응답이라도 벤더는 항상 이 타입을 준다(§1 실측) — {@code stream} 을 보내지 않아도 그렇다.
 * 그래서 목 응답도 같은 타입으로 준다. 클라이언트는 {@code body(String.class)} 로 받아
 * 직접 파싱하므로(자동 역직렬화 아님) 그대로 동작해야 한다.
 *
 * <h2>덮는 케이스 (번호)</h2>
 * ① 정상 전송·매핑 · ③ 응답 수신 중 실패는 재시도 안 함 · ⑥ 타임아웃 → 502 ·
 * ⑦ {@code <think>} 제거 · ⑧ 새 대화의 {@code X-Session-Id} 유일성 ·
 * ⑨ⓐⓑⓒ {@code AGENT_NO_ANSWER} 3갈래 · ⑩ {@code AGENT_NO_ANSWER} ↔ {@code AGENT_UPSTREAM_ERROR} 구별 ·
 * 🔴 SSE 에러 프레임 → {@code AGENT_BAD_REQUEST}(§1 ①).
 * <p>⛔ <b>④ 429 는 만들지 않는다</b> — ⑤-d 에서 재현 불가로 확정돼 {@code AGENT_RATE_LIMITED}
 * 자체가 없다. ⛔ <b>⑤ 길이 초과 400 도 여기서 테스트하지 않는다</b> — {@code @Size} 는 컨트롤러 빈
 * 검증이라 MVC 계층이 없는 이 테스트에서는 발동하지 않는다. ⛔ <b>⑪ 인증만료 승격도 없다</b> —
 * ⑤-f 가 H2 를 기각해 승격 분기가 폐기됐다.
 * <p>②(401 → 갱신 → 재시도 1회)는 {@code withToken} 의 동작이라
 * {@code jeju.datahub.agent.service.AgentTokenRefreshTest} 에 있다.
 */
class AgentChatClientTest {

    private static final String BASE_URL = "https://agent.example.test";
    private static final String CHAT_URL = BASE_URL + "/v1/chat/completions";
    private static final String AGENT_ID = "agent.0edd4421";
    private static final String TOKEN = "test-access-token";
    private static final String SESSION_ID = "portal-fixed-for-test";
    private static final String QUESTION = "연동 지역 주차 민원이 가장 많은 시간대는?";

    /**
     * 🔴 실측된 정상 응답의 {@code Content-Type} — 정상이어도 SSE 타입이다(§1 — {@code stream} 을
     * 보내지 않아도 그렇다).
     *
     * <p>🔴 <b>{@code charset=utf-8} 을 빼지 마라</b> — §1 실측값이 그렇고, 이 상수가 <b>업스트림이
     * 실제로 보내는 헤더</b>를 문서화한다.
     * <p>표기가 <b>빠졌을 때</b>도 한글이 살아남는지는 별도 회귀 테스트
     * ({@link #koreanSurvivesWhenUpstreamOmitsCharset()})가 본다 — 클라이언트가 {@code byte[]} 로
     * 받아 UTF-8 로 고정 디코딩하므로 양쪽 모두 통과해야 한다.
     */
    private static final MediaType EVENT_STREAM =
            MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private record Fixture(AgentApiClient client, MockRestServiceServer server) {}

    /** 🔴 컨테이너와 같은 모양(빈 이름 키) — 클라이언트가 {@code modelName()} 으로 다시 인덱싱한다. */
    private static final Map<String, AgentResponseFilter> FILTERS =
            Map.of("minimaxResponseFilter", new MinimaxResponseFilter());

    private static AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.setBaseUrl(BASE_URL);
        p.setAgentId(AGENT_ID);
        p.setDefaultLimit(20);
        p.setModel("minimax");      // 🔴 기본값은 yml 이 주므로 테스트가 직접 고른다
        return p;
    }

    private static Fixture fixture() {
        RestClient.Builder b = RestClient.builder();                            // ①
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build(); // ① 목 팩토리를 심는다
        return new Fixture(new AgentApiClient(b.build(), props(), FILTERS), server); // ② 심은 뒤 build()
    }

    /** {@code choices[0]} 하나짜리 정상 형태 응답 본문. */
    private static String chatBody(String finishReason, String contentJson) {
        return """
                {
                  "id": "chatcmpl-6eefe06bf5fb",
                  "object": "chat.completion",
                  "model": "innopam_test",
                  "choices": [
                    {
                      "index": 0,
                      "message": { "role": "assistant", "content": %s },
                      "finish_reason": %s
                    }
                  ],
                  "usage": { "prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0 }
                }""".formatted(contentJson, finishReason == null ? "null" : "\"" + finishReason + "\"");
    }

    // ------------------------------------------------------------------ ① 정상 전송·매핑

    @Test
    @DisplayName("① 정상 전송 — POST /v1/chat/completions + 헤더 3종, 바디는 messages 하나뿐(model·stream 없음)")
    void sendMessagePostsMinimalBodyAndMapsAssistant() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header("X-Agent-Id", AGENT_ID))          // 🔴 비면 벤더가 400 으로 거절한다
                .andExpect(header("X-Session-Id", SESSION_ID))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value(QUESTION))
                // 🔴 §2-3 #6 — 이 두 키를 보내지 않는 것이 계약이다.
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.stream").doesNotExist())
                .andRespond(withSuccess(
                        chatBody("stop", "\"**연동** 지역이 가장 많습니다.\""), EVENT_STREAM));

        AiMessageVO vo = f.client().sendMessage(TOKEN, SESSION_ID, QUESTION);

        f.server().verify();
        // 🔴 원천의 role 을 그대로 믿지 않는다 — 화면이 그리는 것은 답변이다.
        assertThat(vo.getRole()).isEqualTo("assistant");
        assertThat(vo.getContent()).isEqualTo("**연동** 지역이 가장 많습니다.");
        // 🔴 전송 응답 최상위에 created_at 이 없다(§2-3 #3 실측) — 지어내지 않는다.
        assertThat(vo.getCreatedAt()).isNull();
    }

    // ------------------------------------------------------------------ ③ 응답 수신 중 실패 (중복 전송 방지)

    @Test
    @DisplayName("③ 🔴 응답 수신 중 끊기면 재시도하지 않는다 — POST 는 정확히 1회, AGENT_UPSTREAM_ERROR")
    void brokenResponseIsNotRetried() {
        Fixture f = fixture();
        // once() + verify() 로 '한 번만 나갔다'(= 질문이 두 번 적재되지 않는다)를 함께 단언한다.
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withException(new IOException("connection reset while reading response")));

        AgentApiException ex = (AgentApiException) catchThrowable(
                () -> f.client().sendMessage(TOKEN, SESSION_ID, QUESTION));

        assertThat(ex).isNotNull();
        // 🔴 401 이 아니므로 withToken 도 재시도하지 않는다(AgentTokenRefreshTest 의 nonAuthFailureIsNotRefreshed).
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_UPSTREAM_ERROR);
        assertThat(ex.getCode()).isNotEqualTo(Code.AGENT_UNAUTHORIZED);
        f.server().verify();
    }

    // ------------------------------------------------------------------ ⑥ 타임아웃

    @Test
    @DisplayName("⑥ read 타임아웃 → AGENT_UPSTREAM_ERROR 이고 httpStatus() == 502, 재전송 없음")
    void readTimeoutBecomes502() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        AgentApiException ex = (AgentApiException) catchThrowable(
                () -> f.client().sendMessage(TOKEN, SESSION_ID, QUESTION));

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_UPSTREAM_ERROR);
        // 🔴 HTTP 계층이 없어 '502 가 나갔다' 를 직접 못 본다 — httpStatus() 로 단언한다.
        assertThat(ex.httpStatus()).isEqualTo(502);
        f.server().verify();
    }

    // ------------------------------------------------------------------ ⑦ <think> 제거

    @Test
    @DisplayName("⑦ 🔴 전송 응답도 조회와 같은 필터를 탄다 — <think> 블록이 걷혀서 나온다")
    void thinkBlockIsStrippedOnSendPathToo() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess(chatBody("stop",
                        "\"<think>사용자가 시간대를 묻는다. 집계를 보자.</think>\\n오후 6시대입니다.\""),
                        EVENT_STREAM));

        AiMessageVO vo = f.client().sendMessage(TOKEN, SESSION_ID, QUESTION);

        f.server().verify();
        // 🔴 이게 없으면 "이력에는 없던 <think> 가 방금 받은 답변에만 보인다" 가 런타임에서야 드러난다.
        assertThat(vo.getContent()).isEqualTo("오후 6시대입니다.");
        assertThat(vo.getContent()).doesNotContain("<think>", "</think>");
    }

    // ------------------------------------------------------------------ ⑧ 새 대화의 X-Session-Id 유일성

    @Test
    @DisplayName("⑧ 🔴 새 대화는 매 호출 다른 X-Session-Id 를 만든다 — 고정값이면 모두가 한 대화를 공유한다")
    void newConversationUsesFreshSessionIdEachCall() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        AgentProperties props = props();
        AgentApiClient client = new AgentApiClient(b.build(), props, FILTERS);

        // 🔴 세션 ID 를 만드는 주체는 컨트롤러다 — 클라이언트 단독으로는 볼 수 없다.
        AgentTokenStore store = new AgentTokenStore();       // refresher 없음 — 갱신 경로는 ②가 본다
        AiSessionController controller = new AiSessionController(props, store, client);

        List<String> sent = new ArrayList<>();
        server.expect(ExpectedCount.twice(), requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> sent.add(request.getHeaders().getFirst("X-Session-Id")))
                .andRespond(withSuccess(chatBody("stop", "\"네.\""), EVENT_STREAM));

        AiChatResponse first = controller.send(request(QUESTION), sessionWithToken(store));
        AiChatResponse second = controller.send(request(QUESTION), sessionWithToken(store));

        server.verify();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).startsWith("portal-");
        assertThat(sent.get(1)).startsWith("portal-");
        // 🔴 핵심 — 매 호출 다른 값이어야 한다.
        assertThat(sent.get(0)).isNotEqualTo(sent.get(1));
        // 🔴 응답의 sessionId 는 '실제로 보낸 값' 그대로여야 한다(람다 바깥에서 만든 결과다).
        assertThat(first.getSessionId()).isEqualTo(sent.get(0));
        assertThat(second.getSessionId()).isEqualTo(sent.get(1));
        assertThat(first.getMessage().getContent()).isEqualTo("네.");
    }

    // ------------------------------------------------------------------ ⑨ AGENT_NO_ANSWER 3갈래

    @Test
    @DisplayName("⑨ⓐ finish_reason:\"error\" + content:null → 502 AGENT_NO_ANSWER (빈 말풍선 방지)")
    void finishReasonErrorWithNullContentIsNoAnswer() {
        assertNoAnswer(chatBody("error", "null"));
    }

    @Test
    @DisplayName("⑨ⓑ 🔴 finish_reason:\"error\" 인데 content 가 있어도 AGENT_NO_ANSWER — finish_reason 을 먼저 본다")
    void finishReasonErrorWithContentIsStillNoAnswer() {
        // content 가 있다고 성공으로 내리면, 벤더가 부분 문장을 실어 보내는 순간 오답이 그대로 나간다.
        assertNoAnswer(chatBody("error", "\"죄송합니다. 답변을 생성하지\""));
    }

    @Test
    @DisplayName("⑨ⓒ finish_reason 은 정상인데 필터 통과 후 빈 문자열이면 AGENT_NO_ANSWER")
    void emptyContentAfterFilterIsNoAnswer() {
        // <think> 만 있는 본문 — 필터를 통과하면 남는 것이 없다. 200 으로 내리면 빈 말풍선이다.
        assertNoAnswer(chatBody("stop", "\"<think>내부 추론만 남았다</think>\""));
    }

    private void assertNoAnswer(String body) {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess(body, EVENT_STREAM));   // 🔴 어느 경우도 HTTP 는 200 이다

        AgentApiException ex = (AgentApiException) catchThrowable(
                () -> f.client().sendMessage(TOKEN, SESSION_ID, QUESTION));

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_NO_ANSWER);
        assertThat(ex.httpStatus()).isEqualTo(502);
        f.server().verify();
    }

    // ------------------------------------------------------------------ ⑩ NO_ANSWER ↔ UPSTREAM_ERROR 구별

    @Test
    @DisplayName("⑩ 🔴 같은 502 라도 code 가 갈린다 — 5xx 는 AGENT_UPSTREAM_ERROR, 무응답은 AGENT_NO_ANSWER")
    void noAnswerIsDistinctFromUpstreamError() {
        // (1) 업스트림 5xx — '서버에 못 닿았다'
        Fixture err = fixture();
        err.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withServerError());
        AgentApiException upstream = (AgentApiException) catchThrowable(
                () -> err.client().sendMessage(TOKEN, SESSION_ID, QUESTION));
        err.server().verify();

        // (2) 200 인데 답변이 없다 — '닿았는데 답을 못 만들었다'
        Fixture none = fixture();
        none.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess(chatBody("error", "null"), EVENT_STREAM));
        AgentApiException noAnswer = (AgentApiException) catchThrowable(
                () -> none.client().sendMessage(TOKEN, SESSION_ID, QUESTION));
        none.server().verify();

        assertThat(upstream.getCode()).isEqualTo(Code.AGENT_UPSTREAM_ERROR);
        assertThat(noAnswer.getCode()).isEqualTo(Code.AGENT_NO_ANSWER);
        // 🔴 status 는 같다 — 그래서 code 로만 갈린다. 뭉개면 원인 추적이 끊긴다.
        assertThat(upstream.httpStatus()).isEqualTo(502);
        assertThat(noAnswer.httpStatus()).isEqualTo(502);
        assertThat(upstream.getCode()).isNotEqualTo(noAnswer.getCode());
    }

    // ------------------------------------------------------------------ 🔴 SSE 에러 프레임 (§1 ① — 신규 발견)

    @Test
    @DisplayName("🔴 SSE 에러 프레임(HTTP 200 + data: {\"error\":{…}}) → 400 AGENT_BAD_REQUEST — NO_ANSWER 로 뭉개지 않는다")
    void sseErrorFrameIsBadRequestNotNoAnswer() {
        Fixture f = fixture();
        // 🔴 요청 검증 실패의 실제 모습 — HTTP 는 200 이고 본문이 SSE 프레임이다(§1 실측).
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess("""
                        data: {"error":{"message":"messages: field required","type":"invalid_request_error","code":422}}

                        data: [DONE]
                        """, EVENT_STREAM));

        AgentApiException ex = (AgentApiException) catchThrowable(
                () -> f.client().sendMessage(TOKEN, SESSION_ID, QUESTION));

        assertThat(ex).isNotNull();
        // 🔴 이건 "AI 가 답을 못 만든 것" 이 아니라 "우리가 요청을 잘못 보낸 것" — 고칠 주체가 다르다.
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_BAD_REQUEST);
        assertThat(ex.getCode()).isNotEqualTo(Code.AGENT_NO_ANSWER);
        assertThat(ex.httpStatus()).isEqualTo(400);
        f.server().verify();
    }

    @Test
    @DisplayName("🔴 code:400 짜리 SSE 에러 프레임(messages 빈 배열)도 같은 갈래다")
    void sseErrorFrameWithCode400IsAlsoBadRequest() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess("""
                        data: {"error":{"message":"messages must not be empty","code":400}}

                        data: [DONE]
                        """, EVENT_STREAM));

        AgentApiException ex = (AgentApiException) catchThrowable(
                () -> f.client().sendMessage(TOKEN, SESSION_ID, QUESTION));

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_BAD_REQUEST);
        f.server().verify();
    }

    // ------------------------------------------------------------------ 🔴 charset 방어 (범위 밖 #9 → 범위 안, 2026-09-08)

    /**
     * 🔴 <b>업스트림이 {@code charset} 표기를 빼도 한글이 깨지지 않아야 한다.</b>
     *
     * <p>{@link #EVENT_STREAM} 이 문서화한 전제("벤더가 {@code charset=utf-8} 을 계속 붙여 준다")가
     * 무너지는 날을 잡는 회귀 테스트다. {@code StringHttpMessageConverter} 는 {@code text/*} 에
     * charset 이 없으면 <b>ISO-8859-1</b> 로 읽으므로({@code application/json} 만 UTF-8 특례),
     * {@code body(String.class)} 로 받으면 한글 답변이 <b>예외 없이 조용히 전부</b> 깨진다.
     * 그래서 클라이언트는 {@code byte[]} 로 받아 <b>UTF-8 로 고정 디코딩</b>한다.
     *
     * <p>🔴 {@code withSuccess(String, MediaType)} 는 본문을 <b>항상 UTF-8 바이트</b>로 싣는다
     * ({@code DefaultResponseCreator#body(String)}) — 헤더의 charset 표기와 무관하다.
     * 즉 이 목은 "UTF-8 바이트인데 헤더에 charset 이 없는" 실제 위험 상황을 그대로 재현한다.
     */
    @Test
    @DisplayName("🔴 charset 없는 text/event-stream 이어도 한글이 깨지지 않는다 — UTF-8 고정 디코딩")
    void koreanSurvivesWhenUpstreamOmitsCharset() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                // 🔴 charset 파라미터가 없는 text/event-stream — 벤더가 표기를 뺀 상황이다.
                .andRespond(withSuccess(chatBody("stop", "\"연동 지역 주차 민원은 오후 6시대에 가장 많습니다.\""),
                        MediaType.parseMediaType("text/event-stream")));

        AiMessageVO vo = f.client().sendMessage(TOKEN, SESSION_ID, QUESTION);

        f.server().verify();
        assertThat(vo.getContent()).isEqualTo("연동 지역 주차 민원은 오후 6시대에 가장 많습니다.");
        // 🔴 ISO-8859-1 로 읽히면 대체문자(U+FFFD)나 깨진 라틴 문자만 남는다 — 그것을 명시로 막는다.
        assertThat(vo.getContent()).doesNotContain("�");
    }

    // ------------------------------------------------------------------ 픽스처 (⑧ 전용)

    private static AiChatRequest request(String message) {
        AiChatRequest r = new AiChatRequest();
        r.setMessage(message);
        return r;
    }

    /** 🔴 세션 속성 이름은 다른 패키지라 직접 못 쓴다 — 공개 API {@code save} 로 심는다. */
    private static MockHttpSession sessionWithToken(AgentTokenStore store) {
        MockHttpSession session = new MockHttpSession();
        store.save(session, OAuth2AccessTokenResponse.withToken(TOKEN)
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(900L)
                .build());
        return session;
    }

    // ------------------------------------------------------------------ 보고서 영역 (전송 경로)

    /** 최상위 {@code id} 를 고를 수 있는 {@link #chatBody} 변형 — 비교용. */
    private static String chatBodyWithId(String id, String finishReason, String content) throws Exception {
        ObjectMapper m = new ObjectMapper();
        return chatBody(finishReason, m.writeValueAsString(content))
                .replace("\"chatcmpl-6eefe06bf5fb\"", m.writeValueAsString(id));
    }

    @Test
    @DisplayName("전송 — 상세와 같은 픽스처로 같은 content·reports·msgId(최상위 id)")
    void sendMessageExtractsReportSameAsDetail() throws Exception {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(chatBodyWithId(AgentApiClientTest.REPORT_MSG_ID, "stop",
                        AgentApiClientTest.REPORT_ANSWER), MediaType.APPLICATION_JSON));   // 새 Agent 실측 — application/json

        AiMessageVO vo = f.client().sendMessage(TOKEN, SESSION_ID, QUESTION);
        f.server().verify();

        assertThat(vo.getContent()).isEqualTo(AgentApiClientTest.REPORT_CONTENT);
        // 🔴 상세 경로의 completion_id 와 같은 값이어야 전송 직후 카드의 저장이 404 가 아니다
        assertThat(vo.getMsgId()).isEqualTo(AgentApiClientTest.REPORT_MSG_ID);
        assertThat(vo.getReports()).hasSize(1);
        AiReportBlockVO r = vo.getReports().get(0);
        assertThat(r.getIdx()).isZero();
        assertThat(r.getStts()).isEqualTo(AiReportBlockVO.STTS_DONE);
        assertThat(r.getTitle()).isEqualTo("제주특별자치도 주차 민원 분석 보고서");
        assertThat(r.getHtml()).doesNotContain("<script");
    }

    @Test
    @DisplayName("전송 — finish_reason=length 이고 미완성 영역이면 TRUNCATED · 자리표시만 남아도 200")
    void lengthFinishMarksPendingReportTruncated() throws Exception {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(chatBodyWithId("chatcmpl-cut", "length",
                        "html_report_start_7b1d8f43-6a92-4c3f-a5e7-9d20e4b8610c\n<html><body><h1>잘린"),
                        MediaType.APPLICATION_JSON));

        AiMessageVO vo = f.client().sendMessage(TOKEN, SESSION_ID, QUESTION);

        assertThat(vo.getContent()).isEqualTo("[[AI_REPORT:0]]");
        assertThat(vo.getMsgId()).isEqualTo("chatcmpl-cut");
        assertThat(vo.getReports()).singleElement().satisfies(r -> {
            assertThat(r.getStts()).isEqualTo(AiReportBlockVO.STTS_TRUNCATED);
            assertThat(r.getHtml()).isNull();
            assertThat(r.getTitle()).isNull();
        });
    }

    @Test
    @DisplayName("전송 — 미완성 영역이라도 finish_reason=stop 이면 PENDING 그대로")
    void stopFinishKeepsPendingReport() throws Exception {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(chatBodyWithId("chatcmpl-p", "stop",
                        "앞 텍스트\nhtml_report_start_7b1d8f43-6a92-4c3f-a5e7-9d20e4b8610c\n<h1>생성 중"),
                        EVENT_STREAM));

        AiMessageVO vo = f.client().sendMessage(TOKEN, SESSION_ID, QUESTION);

        assertThat(vo.getContent()).isEqualTo("앞 텍스트\n[[AI_REPORT:0]]");
        assertThat(vo.getReports()).singleElement()
                .extracting(AiReportBlockVO::getStts).isEqualTo(AiReportBlockVO.STTS_PENDING);
    }

    // ------------------------------------------------------------------ 도구 호출 자동 거절

    /** 답변 없이 도구 호출만 온 응답(2026-11-10 실측 모양) — {@code content:""} + {@code tool_calls[]}. */
    private static String toolCallBody(String... callIds) {
        StringBuilder calls = new StringBuilder();
        for (int i = 0; i < callIds.length; i++) {
            if (i > 0) {
                calls.append(',');
            }
            calls.append("""
                    {"id":"%s","type":"function","function":{"name":"reports_generate_from_conversation",
                     "arguments":"{\\"title\\":\\"2026년 7월 민원\\",\\"slide_count\\":5}"}}""".formatted(callIds[i]));
        }
        return """
                {
                  "id": "chatcmpl-tool",
                  "object": "chat.completion",
                  "choices": [
                    {
                      "index": 0,
                      "message": { "role": "assistant", "content": "", "tool_calls": [%s] },
                      "finish_reason": "tool_calls"
                    }
                  ]
                }""".formatted(calls);
    }

    private static AiSessionController controller(AgentApiClient client, AgentTokenStore store) {
        return new AiSessionController(props(), store, client);
    }

    @Test
    @DisplayName("빈 답변 + tool_calls → 같은 세션으로 거절 1회({\"approved\":false}) → 거절 뒤 답변을 내린다")
    void toolCallOnlyAnswerIsAutoRejected() throws Exception {
        Fixture f = fixture();
        AgentTokenStore store = new AgentTokenStore();
        List<String> sessions = new ArrayList<>();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(request -> sessions.add(request.getHeaders().getFirst("X-Session-Id")))
                .andRespond(withSuccess(toolCallBody("call_1"), EVENT_STREAM));
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("X-Agent-Id", AGENT_ID))
                .andExpect(request -> sessions.add(request.getHeaders().getFirst("X-Session-Id")))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("tool"))
                .andExpect(jsonPath("$.messages[0].tool_call_id").value("call_1"))
                .andExpect(jsonPath("$.messages[0].content").value("{\"approved\":false}"))
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.stream").doesNotExist())
                .andRespond(withSuccess(chatBodyWithId("chatcmpl-after-reject", "stop",
                        "요청하신 보고서 생성 기능을 현재 바로 실행할 수 없습니다."), EVENT_STREAM));

        AiChatResponse res = controller(f.client(), store).send(request(QUESTION), sessionWithToken(store));

        f.server().verify();
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(1)).isEqualTo(sessions.get(0));        // 🔴 같은 대화에 거절한다
        assertThat(res.getSessionId()).isEqualTo(sessions.get(0));
        assertThat(res.getMessage().getContent())   // 승인 거절 문단이 없으면 원문 그대로다
                .isEqualTo("요청하신 보고서 생성 기능을 현재 바로 실행할 수 없습니다.");
        assertThat(res.getMessage().getMsgId()).isEqualTo("chatcmpl-after-reject");   // 🔴 최종 답변의 id
    }

    @Test
    @DisplayName("tool_calls 가 여럿이면 한 요청에 각 id 의 거절을 함께 담는다")
    void multipleToolCallsAreRejectedInOneRequest() throws Exception {
        Fixture f = fixture();
        AgentTokenStore store = new AgentTokenStore();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess(toolCallBody("call_a", "call_b"), EVENT_STREAM));
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].tool_call_id").value("call_a"))
                .andExpect(jsonPath("$.messages[1].tool_call_id").value("call_b"))
                .andRespond(withSuccess(chatBody("stop", "\"거절됐습니다.\""), EVENT_STREAM));

        AiChatResponse res = controller(f.client(), store).send(request(QUESTION), sessionWithToken(store));

        f.server().verify();
        assertThat(res.getMessage().getContent()).isEqualTo("거절됐습니다.");
    }

    @Test
    @DisplayName("🔴 거절 뒤에도 또 tool_calls → 더 보내지 않고 409 AGENT_TOOL_CALL_UNRESOLVED (자동 거절 1회뿐)")
    void secondToolCallIsUnresolvedNot502() {
        Fixture f = fixture();
        AgentTokenStore store = new AgentTokenStore();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess(toolCallBody("call_1"), EVENT_STREAM));
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andExpect(jsonPath("$.messages[0].role").value("tool"))
                .andRespond(withSuccess(toolCallBody("call_2"), EVENT_STREAM));
        // 🔴 세 번째 요청은 기대하지 않는다 — 나가면 verify 전에 MockRestServiceServer 가 AssertionError 를 던진다.

        AgentApiException ex = (AgentApiException) catchThrowable(
                () -> controller(f.client(), store).send(request(QUESTION), sessionWithToken(store)));

        f.server().verify();
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_TOOL_CALL_UNRESOLVED);
        assertThat(ex.httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("본문이 있으면 tool_calls 가 함께 와도 거절하지 않고 본문을 그대로 내린다")
    void contentWithToolCallsIsShownAsIs() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess(toolCallBody("call_1").replace("\"content\": \"\"", "\"content\": \"보고서를 만들까요?\""),
                        EVENT_STREAM));

        AiMessageVO vo = f.client().sendMessage(TOKEN, SESSION_ID, QUESTION);

        f.server().verify();
        assertThat(vo.getContent()).isEqualTo("보고서를 만들까요?");
    }

    @Test
    @DisplayName("빈 답변인데 tool_calls 에 id 가 없으면 종전대로 AGENT_NO_ANSWER")
    void toolCallsWithoutIdStayNoAnswer() {
        assertNoAnswer(toolCallBody("").replace("\"id\":\"\",", ""));
    }

    @Test
    @DisplayName("거절 뒤 답변에 '승인이 확인되지 않아' 문단이 있으면 고정 문구로 바꾸고 나머지 문단은 남긴다")
    void rejectReplyDenialParagraphIsReplaced() throws Exception {
        Fixture f = fixture();
        AgentTokenStore store = new AgentTokenStore();
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess(toolCallBody("call_1"), EVENT_STREAM));
        f.server().expect(ExpectedCount.once(), requestTo(CHAT_URL))
                .andRespond(withSuccess(chatBodyWithId("chatcmpl-r", "stop",
                        "죄송합니다. 사용자의 승인이 확인되지 않아 보고서를 생성하지 못했습니다.\n\n다른 분석이 필요하신가요?"),
                        EVENT_STREAM));

        AiChatResponse res = controller(f.client(), store).send(request(QUESTION), sessionWithToken(store));

        f.server().verify();
        assertThat(res.getMessage().getContent())
                .isEqualTo(ToolRejectReplyRewriter.NOTICE + "\n\n다른 분석이 필요하신가요?");
    }
}
