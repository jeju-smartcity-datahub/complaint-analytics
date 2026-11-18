package jeju.datahub.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import jeju.datahub.agent.service.AgentApiException;
import jeju.datahub.agent.service.AgentApiException.Code;
import jeju.datahub.agent.service.AgentProperties;
import jeju.datahub.agent.service.AgentResponseFilter;
import jeju.datahub.agent.service.AiMessageVO;
import jeju.datahub.agent.service.AiReportBlockVO;
import jeju.datahub.agent.service.AiSessionDetailVO;
import jeju.datahub.agent.service.AiSessionListVO;
import jeju.datahub.agent.service.AiSessionVO;
import jeju.datahub.agent.service.impl.filter.MinimaxResponseFilter;

/**
 * {@link AgentApiClient} 단위 테스트.
 *
 * <p>업스트림 없이 {@link MockRestServiceServer}(spring-test 내장 — <b>새 의존성 없음</b>)로
 * 정상·실패 경로를 모두 검증한다. 🔴 <b>실 Agent API 를 부르는 테스트는 두지 않는다</b> —
 * 키·네트워크 없는 CI 에서 빌드가 깨진다.
 *
 * <p>패키지가 {@code ...agent.service.impl} 인 이유는 목 주입 생성자
 * {@code AgentApiClient(RestClient, AgentProperties, Map)} 가 <b>package-private</b> 이기 때문이다
 * (선례: {@code jeju.datahub.analysis.service.impl.VworldPlaceClientTest}).
 *
 * <p>🔴 세 번째 인자는 <b>모델별 응답 필터 레지스트리</b>다. 여기서는 컨테이너 없이
 * {@link MinimaxResponseFilter} 하나를 직접 넣고 {@code props.model = "minimax"} 로 고른다 —
 * {@code <think>} 제거 <b>동작은 구현과 같아야 하므로 기대값은 그대로다</b>.
 *
 * <h2>🔴 목 조립 순서</h2>
 * <pre>
 * RestClient.Builder b = RestClient.builder();
 * MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();  // ① b 에 목 팩토리를 심는다
 * AgentApiClient client = new AgentApiClient(b.build(), props, FILTERS);   // ② 심은 뒤 build()
 * </pre>
 * 순서를 뒤집거나 운영 생성자({@code requestFactory(...)} 를 부른다)를 쓰면 목이 덮여 걸리지 않는다.
 *
 * <h2>🔴 {@code httpStatus()} 로 단언하는 이유</h2>
 * 이 테스트에는 HTTP 계층이 없어 "400 이 나갔다" 를 직접 볼 수 없다. ④·⑤ 는 예외의
 * {@code httpStatus()} 로 단언한다 — <b>여기가 {@code upstreamStatus} 우선 규칙을 검증하는
 * 유일한 자리</b>다.
 */
class AgentApiClientTest {

    private static final String BASE_URL = "https://agent.example.test";
    private static final String AGENT_ID = "agent.0edd4421";
    private static final String TOKEN = "test-access-token";

    /** 목을 먼저 심고 build() 한 클라이언트와 그 목 서버를 한 벌로 들고 다닌다. */
    private record Fixture(AgentApiClient client, MockRestServiceServer server) {}

    /**
     * 🔴 주입되는 Map 의 키는 <b>빈 이름</b>이라 클라이언트가 {@code modelName()} 으로 다시 인덱싱한다 —
     * 여기서도 컨테이너와 같은 모양(빈 이름 키)으로 넣는다.
     */
    private static final Map<String, AgentResponseFilter> FILTERS =
            Map.of("minimaxResponseFilter", new MinimaxResponseFilter());

    private static AgentProperties props(String baseUrl, String agentId) {
        AgentProperties p = new AgentProperties();
        p.setBaseUrl(baseUrl);
        p.setAgentId(agentId);
        p.setDefaultLimit(20);
        p.setModel("minimax");      // 🔴 기본값은 yml 이 주므로 테스트가 직접 고른다
        return p;
    }

    private static Fixture fixture(AgentProperties props) {
        RestClient.Builder b = RestClient.builder();                            // ①
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build(); // ① 목 팩토리를 심는다
        return new Fixture(new AgentApiClient(b.build(), props, FILTERS), server); // ② 심은 뒤 build()
    }

    private static Fixture fixture() {
        return fixture(props(BASE_URL, AGENT_ID));
    }

    // ------------------------------------------------------------------ ① 정상 매핑

    @Test
    @DisplayName("① 목록 — snake_case 를 camelCase 로 옮기고 agent_id·limit 를 백엔드가 붙인다")
    void listSessionsMapsSnakeToCamel() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(),
                        requestTo(BASE_URL + "/v1/sessions?agent_id=" + AGENT_ID + "&limit=20"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess("""
                        {
                          "sessions": [
                            {
                              "id": "내부-식별자-내리지-않는다",
                              "session_id": "s-1",
                              "agent_id": "agent.0edd4421",
                              "agent_name": "제주 데이터 도우미",
                              "title": "연동 민원 현황",
                              "preview": "연동 지역 민원이 가장 많습니다.",
                              "created_at": "2026-09-03T10:12:00.000Z",
                              "updated_at": "2026-09-03T10:20:11.000Z"
                            }
                          ],
                          "next_cursor": "eyJvZmZzZXQiOjIwfQ=="
                        }""", MediaType.APPLICATION_JSON));

        AiSessionListVO vo = f.client().listSessions(TOKEN, null, null);

        f.server().verify();
        assertThat(vo.getNextCursor()).isEqualTo("eyJvZmZzZXQiOjIwfQ==");
        assertThat(vo.getSessions()).hasSize(1);
        AiSessionVO s = vo.getSessions().get(0);
        assertThat(s.getSessionId()).isEqualTo("s-1");
        assertThat(s.getAgentId()).isEqualTo("agent.0edd4421");
        assertThat(s.getAgentName()).isEqualTo("제주 데이터 도우미");
        assertThat(s.getTitle()).isEqualTo("연동 민원 현황");
        assertThat(s.getPreview()).isEqualTo("연동 지역 민원이 가장 많습니다.");
        assertThat(s.getCreatedAt()).isEqualTo("2026-09-03T10:12:00.000Z");
        assertThat(s.getUpdatedAt()).isEqualTo("2026-09-03T10:20:11.000Z");
    }

    @Test
    @DisplayName("① 상세 — system·tool 제거 + <think> 제거·빈 content 떨구기(닫는 태그 없는 경우 포함)")
    void getSessionFiltersRolesAndStripsThink() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/sessions/s-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header("X-Agent-Id", AGENT_ID))
                .andRespond(withSuccess("""
                        {
                          "session_id": "s-1",
                          "pending_approval": null,
                          "state": "idle",
                          "messages": [
                            {"role": "system",    "content": "내부 시스템 프롬프트 — 내려가면 안 된다"},
                            {"role": "user",      "content": "연동 민원 알려줘",
                             "created_at": "2026-09-03T10:12:00.000Z"},
                            {"role": "assistant", "content": "<think>The user asks about Yeon-dong.</think>\\n**연동** 민원이 가장 많습니다.",
                             "completion_id": "c-1",
                             "created_at": "2026-09-03T10:12:33.120Z"},
                            {"role": "tool",      "content": "{\\"tool\\":\\"sql\\",\\"rows\\":3}"},
                            {"role": "assistant", "content": "앞부분 답변은 남는다.<think>closing tag missing internal reasoning"},
                            {"role": "assistant", "content": "<think>only reasoning here</think>   "},
                            {"role": "assistant", "content": "<think>unclosed reasoning only"}
                          ],
                          "context": {
                            "used_tokens": 1820, "system_tokens": 640, "history_tokens": 1180,
                            "budget_tokens": 8000, "limit_tokens": 16000
                          }
                        }""", MediaType.APPLICATION_JSON));

        AiSessionDetailVO vo = f.client().getSession(TOKEN, "s-1");

        f.server().verify();
        assertThat(vo.getSessionId()).isEqualTo("s-1");

        // system·tool 은 아예 없다. 빈 content 두 건도 떨어져 3건만 남는다.
        assertThat(vo.getMessages()).hasSize(3);
        assertThat(vo.getMessages()).extracting(AiMessageVO::getRole)
                .containsExactly("user", "assistant", "assistant");

        AiMessageVO user = vo.getMessages().get(0);
        assertThat(user.getContent()).isEqualTo("연동 민원 알려줘");
        assertThat(user.getCreatedAt()).isEqualTo("2026-09-03T10:12:00.000Z");   // snake → camel

        // 여는 태그부터 첫 </think> 까지 제거. 뒤 본문은 그대로 남는다.
        assertThat(vo.getMessages().get(1).getContent()).isEqualTo("**연동** 민원이 가장 많습니다.");
        assertThat(vo.getMessages().get(1).getCreatedAt()).isEqualTo("2026-09-03T10:12:33.120Z");

        // 닫는 태그가 없으면 여는 태그 이후 전체를 버린다(앞에 있던 본문만 남는다).
        assertThat(vo.getMessages().get(2).getContent()).isEqualTo("앞부분 답변은 남는다.");

        // 🔴 어떤 메시지에도 태그·추론 문자열이 남지 않는다.
        assertThat(vo.getMessages()).extracting(AiMessageVO::getContent)
                .noneMatch(c -> c.contains("<think>") || c.contains("</think>")
                        || c.contains("reasoning") || c.contains("The user asks"));

        assertThat(vo.getContext()).isNotNull();
        assertThat(vo.getContext().getUsedTokens()).isEqualTo(1820L);
        assertThat(vo.getContext().getSystemTokens()).isEqualTo(640L);
        assertThat(vo.getContext().getHistoryTokens()).isEqualTo(1180L);
        assertThat(vo.getContext().getBudgetTokens()).isEqualTo(8000L);
        assertThat(vo.getContext().getLimitTokens()).isEqualTo(16000L);
    }

    // ------------------------------------------------------------------ ② upstream 401

    @Test
    @DisplayName("② upstream 401 → AGENT_UNAUTHORIZED 를 그대로 전파한다 — 재시도하지 않는다")
    void upstream401PropagatesWithoutRetry() {
        Fixture f = fixture();
        // 🔴 once() + verify() 로 '한 번만 나갔다'(재시도 없음)를 함께 단언한다.
        f.server().expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/sessions/s-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> f.client().getSession(TOKEN, "s-1"))
                .isInstanceOf(AgentApiException.class)
                .extracting(e -> ((AgentApiException) e).getCode())
                .isEqualTo(Code.AGENT_UNAUTHORIZED);

        f.server().verify();
    }

    // ------------------------------------------------------------------ ③ 설정 없음

    @Test
    @DisplayName("③ base-url 이 비면 호출 전에 AGENT_NOT_CONFIGURED (503) — upstream 으로 나가지 않는다")
    void emptyBaseUrlIsNotConfigured() {
        Fixture f = fixture(props("", AGENT_ID));

        assertThatThrownBy(() -> f.client().listSessions(TOKEN, null, null))
                .isInstanceOf(AgentApiException.class)
                .extracting(e -> ((AgentApiException) e).getCode())
                .isEqualTo(Code.AGENT_NOT_CONFIGURED);

        f.server().verify();    // 기대를 하나도 걸지 않았다 = 요청이 나가면 안 된다
    }

    // ------------------------------------------------------------------ ④ sessionId 오염

    @Test
    @DisplayName("④ sessionId 에 '/' 가 있으면 AGENT_BAD_REQUEST 이고 httpStatus() == 400")
    void slashInSessionIdIsBadRequest() {
        Fixture f = fixture();

        AgentApiException ex = (AgentApiException) org.assertj.core.api.Assertions
                .catchThrowable(() -> f.client().getSession(TOKEN, "s-1/../admin"));

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_BAD_REQUEST);
        // 🔴 HTTP 계층이 없어 '400 이 나갔다' 를 직접 못 본다 — httpStatus() 로 단언한다.
        assertThat(ex.httpStatus()).isEqualTo(400);

        f.server().verify();    // 호출 전에 거절된다
    }

    // ------------------------------------------------------------------ ⑤ upstream 403

    @Test
    @DisplayName("⑤ upstream 403 → AGENT_UPSTREAM_REJECTED 이고 httpStatus() == 403 (upstreamStatus 우선)")
    void upstream403KeepsStatus() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(),
                        requestTo(BASE_URL + "/v1/sessions?agent_id=" + AGENT_ID + "&limit=20"))
                .andExpect(headerDoesNotExist("X-Agent-Id"))    // 목록은 쿼리로만 붙인다
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        AgentApiException ex = (AgentApiException) org.assertj.core.api.Assertions
                .catchThrowable(() -> f.client().listSessions(TOKEN, null, null));

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_UPSTREAM_REJECTED);
        // 🔴 code 기본값 502 가 아니라 upstreamStatus(403) 가 이겨야 한다.
        assertThat(ex.httpStatus()).isEqualTo(403);

        f.server().verify();
    }

    // ------------------------------------------------------------------ ⑥ 세션 삭제

    @Test
    @DisplayName("⑥ 삭제 — DELETE /v1/sessions/{id} 에 X-Agent-Id(설정값)·Bearer 를 싣고 204 면 예외 없음")
    void deleteSessionSendsAgentIdHeader() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/sessions/s-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-Agent-Id", AGENT_ID))      // 🔴 백엔드가 붙인다
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withNoContent());

        f.client().deleteSession(TOKEN, "s-1");

        f.server().verify();
    }

    @Test
    @DisplayName("⑥ 삭제 — 업스트림이 200 + JSON 본문을 줘도 성공이다 (공개 스펙은 200)")
    void deleteSessionAccepts200WithBody() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/sessions/s-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        f.client().deleteSession(TOKEN, "s-1");

        f.server().verify();
    }

    @Test
    @DisplayName("⑥ 삭제 — upstream 404 → AGENT_UPSTREAM_REJECTED 이고 httpStatus() == 404")
    void deleteSessionUpstream404KeepsStatus() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/sessions/s-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        AgentApiException ex = (AgentApiException) org.assertj.core.api.Assertions
                .catchThrowable(() -> f.client().deleteSession(TOKEN, "s-1"));

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_UPSTREAM_REJECTED);
        // 🔴 프론트가 404 를 "이미 지워짐" 으로 판정한다 — 502 로 뭉개지면 안 된다.
        assertThat(ex.httpStatus()).isEqualTo(404);

        f.server().verify();
    }

    @Test
    @DisplayName("⑥ 삭제 — upstream 401 → AGENT_UNAUTHORIZED 를 그대로 전파한다 — 재시도하지 않는다")
    void deleteSessionUpstream401IsUnauthorized() {
        Fixture f = fixture();
        // 🔴 once() + verify() 로 '한 번만 나갔다'(재시도 없음)를 함께 단언한다.
        f.server().expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/sessions/s-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> f.client().deleteSession(TOKEN, "s-1"))
                .isInstanceOf(AgentApiException.class)
                .extracting(e -> ((AgentApiException) e).getCode())
                .isEqualTo(Code.AGENT_UNAUTHORIZED);

        f.server().verify();
    }

    @Test
    @DisplayName("⑥ 삭제 — upstream 5xx → AGENT_UPSTREAM_ERROR")
    void deleteSessionUpstream5xxIsUpstreamError() {
        Fixture f = fixture();
        f.server().expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/sessions/s-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> f.client().deleteSession(TOKEN, "s-1"))
                .isInstanceOf(AgentApiException.class)
                .extracting(e -> ((AgentApiException) e).getCode())
                .isEqualTo(Code.AGENT_UPSTREAM_ERROR);

        f.server().verify();
    }

    @Test
    @DisplayName("⑥ 삭제 — sessionId 에 '/' 가 있으면 AGENT_BAD_REQUEST 이고 업스트림 호출 0회")
    void deleteSessionSlashIsBadRequest() {
        Fixture f = fixture();

        AgentApiException ex = (AgentApiException) org.assertj.core.api.Assertions
                .catchThrowable(() -> f.client().deleteSession(TOKEN, "s-1/../admin"));

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(Code.AGENT_BAD_REQUEST);
        assertThat(ex.httpStatus()).isEqualTo(400);

        f.server().verify();    // 기대를 하나도 걸지 않았다 = 요청이 나가면 안 된다
    }

    // ------------------------------------------------------------------ 보고서 영역 (상세 경로)

    /**
     * 🔴 상세·전송 두 경로가 <b>같은 답변</b>으로 같은 결과를 내는지 보는 공용 픽스처.
     * {@code AgentChatClientTest} 가 그대로 참조한다 — 값을 바꾸면 두 테스트가 함께 바뀐다.
     * 구조는 실측(마커 단독 줄 · 접두·펜스 없음 · 완전한 HTML 문서)을 축약한 것이다.
     */
    static final String REPORT_ANSWER = """
            <think>internal reasoning</think>
            분석 결과를 보고서로 정리했습니다.
            html_report_start_7b1d8f43-6a92-4c3f-a5e7-9d20e4b8610c
            <!DOCTYPE html><html lang="ko"><head><meta charset="utf-8"><title>제주특별자치도 주차 민원 분석 보고서</title></head><body><main class="report"><h1>주차 민원</h1><div class="bar" style="width: 75%;"></div><script>alert(1)</script></main></body></html>
            html_report_end_7b1d8f43-6a92-4c3f-a5e7-9d20e4b8610c
            추가 질문이 있으면 말씀해 주세요.""";

    /** 픽스처 답변의 메시지 식별자 — 상세 {@code completion_id} == 전송 최상위 {@code id}. */
    static final String REPORT_MSG_ID = "chatcmpl-6b098ee996e7";

    /** 픽스처 답변에 대한 기대 본문 — 영역이 자리표시로 바뀌고 앞뒤 텍스트는 남는다. */
    static final String REPORT_CONTENT =
            "분석 결과를 보고서로 정리했습니다.\n[[AI_REPORT:0]]\n추가 질문이 있으면 말씀해 주세요.";

    private static String json(String s) {
        try {
            return new ObjectMapper().writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("상세 — assistant 만 보고서 영역 추출 · msgId=completion_id · user 는 msgId null·reports 빈 배열")
    void getSessionExtractsReportForAssistantOnly() {
        Fixture f = fixture();
        String marker = "html_report_start_7b1d8f43-6a92-4c3f-a5e7-9d20e4b8610c 사용자가 친 마커";
        f.server().expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/sessions/s-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "session_id": "s-1",
                          "messages": [
                            {"role": "user", "content": %s},
                            {"role": "assistant", "content": %s, "completion_id": %s},
                            {"role": "assistant", "content": %s, "completion_id": "chatcmpl-only-report"}
                          ]
                        }""".formatted(json(marker), json(REPORT_ANSWER), json(REPORT_MSG_ID),
                        json("html_report_start_7b1d8f43-6a92-4c3f-a5e7-9d20e4b8610c\n<h1>미완성")),
                        MediaType.APPLICATION_JSON));

        AiSessionDetailVO vo = f.client().getSession(TOKEN, "s-1");
        f.server().verify();

        assertThat(vo.getMessages()).hasSize(3);

        // 🔴 user 메시지는 추출하지 않는다 — 마커를 쳐도 원문 그대로 · msgId null · reports 빈 배열
        AiMessageVO user = vo.getMessages().get(0);
        assertThat(user.getContent()).isEqualTo(marker);
        assertThat(user.getMsgId()).isNull();
        assertThat(user.getReports()).isEmpty();

        AiMessageVO a = vo.getMessages().get(1);
        assertThat(a.getContent()).isEqualTo(REPORT_CONTENT);
        assertThat(a.getMsgId()).isEqualTo(REPORT_MSG_ID);
        assertThat(a.getReports()).hasSize(1);
        AiReportBlockVO r = a.getReports().get(0);
        assertThat(r.getIdx()).isZero();
        assertThat(r.getStts()).isEqualTo(AiReportBlockVO.STTS_DONE);
        assertThat(r.getTitle()).isEqualTo("제주특별자치도 주차 민원 분석 보고서");
        assertThat(r.getHtml()).contains("주차 민원").contains("width: 75%").doesNotContain("<script");

        // 🔴 자리표시만 남아도 떨구지 않는다 · 상세 경로에는 finish_reason 이 없어 PENDING 그대로
        AiMessageVO pending = vo.getMessages().get(2);
        assertThat(pending.getContent()).isEqualTo("[[AI_REPORT:0]]");
        assertThat(pending.getReports()).singleElement()
                .extracting(AiReportBlockVO::getStts).isEqualTo(AiReportBlockVO.STTS_PENDING);
    }

    @Test
    @DisplayName("상세 — 승인 필요 도구를 부른 바로 다음 답변만 고쳐 쓴다 · 일반 도구 뒤·일반 답변의 '승인' 은 그대로")
    void getSessionRewritesOnlyReplyAfterApprovalTool() {
        Fixture f = fixture();
        String denial = "죄송합니다. 요청하신 보고서 생성 기능에 대해 사용자의 승인이 확인되지 않아 보고서를 생성하지 못했습니다.";
        String follow = "혹시 특정 데이터의 분석이나 요약이 필요하신가요?";
        String afterQuery = "승인되지 않은 노상주차장은 3곳입니다.";   // 일반 도구 뒤 — 거절 표현이 있어도 그대로
        // 🔴 실측 모양(2026-11-18) — 이력에 role:tool 은 남지 않는다. tool_calls → 바로 다음 assistant.
        f.server().expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/sessions/s-1"))
                .andRespond(withSuccess("""
                        {
                          "session_id": "s-1",
                          "messages": [
                            {"role": "user", "content": "보고서 만들어줘"},
                            {"role": "assistant", "content": "", "completion_id": "c-tool",
                             "tool_calls": [{"id": "call_1", "function": {"name": "reports_generate_from_conversation"}}]},
                            {"role": "assistant", "content": %s, "completion_id": "c-after"},
                            {"role": "user", "content": "노상주차장 알려줘"},
                            {"role": "assistant", "content": "", "completion_id": "c-q",
                             "tool_calls": [{"id": "call_2", "function": {"name": "query_dataset"}}]},
                            {"role": "assistant", "content": %s, "completion_id": "c-normal"}
                          ]
                        }""".formatted(json(denial + "\n\n" + follow), json(afterQuery)),
                        MediaType.APPLICATION_JSON));

        AiSessionDetailVO vo = f.client().getSession(TOKEN, "s-1");
        f.server().verify();

        assertThat(vo.getMessages()).extracting(AiMessageVO::getRole)
                .containsExactly("user", "assistant", "user", "assistant");   // 빈 tool_calls 답변은 내리지 않는다
        assertThat(vo.getMessages().get(1).getContent())
                .isEqualTo(ToolRejectReplyRewriter.NOTICE + "\n\n" + follow);
        assertThat(vo.getMessages().get(1).getMsgId()).isEqualTo("c-after");
        assertThat(vo.getMessages().get(3).getContent()).isEqualTo(afterQuery);
    }
}
