package jeju.datahub.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import jakarta.servlet.http.HttpSession;
import jeju.datahub.agent.service.AgentTokenStore;
import jeju.datahub.agent.service.AiMessageVO;
import jeju.datahub.agent.service.AiReportBlockVO;
import jeju.datahub.agent.service.AiSessionDetailVO;
import jeju.datahub.agent.service.impl.AgentApiClient;
import jeju.datahub.report.service.AiReportService;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * AI 보고서 등록·스냅샷 통합 테스트 — 기본 test 프로파일(permitAll 체인 · CSRF off).
 *
 * <p>🔴 업스트림을 실호출하지 않는다. 막히는 곳 두 군데를 각각 연다 —
 * ① {@code app.agent.base-url}·{@code agent-id} 를 채워 컨트롤러 설정 검사(503 {@code AGENT_NOT_CONFIGURED})를 통과하고
 * ② mock-login 세션에는 Agent 토큰이 없어 {@code withToken} 이 람다를 실행하지 않으므로
 * {@link AgentTokenStore} 를 목으로 바꿔 더미 토큰 {@code "t"} 로 람다를 실행한다.
 *
 * <p>🔴 {@link AgentApiClient} 를 목으로 바꾸면 그 안의 추출기·새니타이저가 돌지 않는다 — 픽스처
 * {@code messages[].msgId}·{@code reports} 를 직접 채운다. 저장 시점 새니타이즈는 GET 응답이 아니라
 * {@code tb_ai_rptp_cn.rptp_cn} <b>원문</b>으로 확인한다(GET 은 읽을 때 한 번 더 새니타이즈해 저장 누락을 가린다).
 *
 * <p>🔴 정리 — {@code RANDOM_PORT} 는 롤백이 안 먹는다. 이 테스트가 만든 {@code TB_RPTP} 행만 ID 로 지우고
 * (본문은 CASCADE), 로그인 이력은 {@link IntegrationLoginSupport#cleanUp()} 이 {@code lgn_sn > startSn} 으로 지운다.
 * 세션 ID 는 실행마다 UUID 라 이전 실행의 잔여 행과 멱등 키가 겹치지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.agent.base-url=http://agent.invalid", "app.agent.agent-id=test-agent"})
class AiReportIntegrationTest {

    private static final String MSG_ID = "chatcmpl-000000000001";
    private static final String TITLE = "TESTDATA 테스트 보고서";
    private static final String DIRTY_HTML =
            "<h1>제목</h1><script>alert(1)</script><img src=\"x\" onerror=\"alert(2)\"><p>본문</p>";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private AgentTokenStore agentTokenStore;
    @MockitoBean private AgentApiClient agentApiClient;

    /** 동시 클릭(선조회 빗나감 → UNIQUE 위반) 경로를 재현하려고 선조회 1회만 비운다 — 나머지는 실제 코드 */
    @MockitoSpyBean private AiReportService aiReportService;

    private IntegrationLoginSupport auth;
    private HttpHeaders session;
    private String sessionId;
    private final List<Long> createdRptpIds = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        auth = IntegrationLoginSupport.forTestChain(rest, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
        sessionId = "test-tsk4293-" + UUID.randomUUID();
        createdRptpIds.clear();

        willAnswer(inv -> ((Function<String, Object>) inv.getArgument(1)).apply("t"))
                .given(agentTokenStore).withToken(any(HttpSession.class), any());
        given(agentApiClient.getSession(eq("t"), anyString())).willReturn(fixture());
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdRptpIds) {
            jdbcTemplate.update("DELETE FROM parking.tb_rptp WHERE rptp_id = ?", id);   // 본문은 CASCADE
        }
        auth.cleanUp();
    }

    /** user · assistant(보고서 2건: DONE · PENDING) */
    private static AiSessionDetailVO fixture() {
        AiMessageVO user = new AiMessageVO();
        user.setRole("user");
        user.setContent("보고서 만들어 줘");

        AiMessageVO assistant = new AiMessageVO();
        assistant.setRole("assistant");
        assistant.setMsgId(MSG_ID);
        assistant.setContent("여기 있습니다\n[[AI_REPORT:0]]\n[[AI_REPORT:1]]");
        assistant.setReports(new ArrayList<>(List.of(
                new AiReportBlockVO(0, TITLE, DIRTY_HTML, AiReportBlockVO.STTS_DONE),
                new AiReportBlockVO(1, null, null, AiReportBlockVO.STTS_PENDING))));

        AiSessionDetailVO detail = new AiSessionDetailVO();
        detail.setSessionId("fixture");
        detail.setMessages(new ArrayList<>(List.of(user, assistant)));
        return detail;
    }

    private String url() {
        return "/api/ai/sessions/" + sessionId + "/reports";
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(Object body) {
        return rest.exchange(url(), HttpMethod.POST, auth.jsonEntity(session, body), Map.class);
    }

    private static Map<String, Object> body(String msgId, Integer rptIdx) {
        Map<String, Object> b = new HashMap<>();
        b.put("msgId", msgId);
        b.put("rptIdx", rptIdx);
        return b;
    }

    @Test
    @DisplayName("등록 201 → 같은 요청 200 + 같은 rptpId · TB_RPTP(AI) 1행 · 저장 원문·스냅샷에 <script>·onerror 없음")
    @SuppressWarnings("rawtypes")
    void registerThenIdempotentThenSnapshot() {
        ResponseEntity<Map> first = post(body(MSG_ID, 0));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long rptpId = ((Number) first.getBody().get("rptpId")).longValue();
        createdRptpIds.add(rptpId);
        assertThat(first.getBody()).containsEntry("rptpNm", TITLE).doesNotContainKey("created");

        ResponseEntity<Map> again = post(body(MSG_ID, 0));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) again.getBody().get("rptpId")).longValue()).isEqualTo(rptpId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT crt_type, stts_cd, file_fmt, creatr_id, creatr_nm, crt_ymd, rptp_nm "
                        + "FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        assertThat(row.get("crt_type")).isEqualTo("AI");
        assertThat(row.get("stts_cd")).isEqualTo("DONE");
        assertThat(row.get("file_fmt")).isNull();
        assertThat(row.get("creatr_id")).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(row.get("crt_ymd")).isNotNull();
        assertThat(row.get("rptp_nm")).isEqualTo(TITLE);

        Integer cnCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_ai_rptp_cn WHERE sesn_id = ?", Integer.class, sessionId);
        assertThat(cnCount).as("멱등 — 두 번 눌러도 본문 1행").isEqualTo(1);

        // 🔴 저장 원문 — GET 은 다시 새니타이즈하므로 저장 시점 누락은 여기서만 잡힌다
        String stored = jdbcTemplate.queryForObject(
                "SELECT rptp_cn FROM parking.tb_ai_rptp_cn WHERE rptp_id = ?", String.class, rptpId);
        assertThat(stored).doesNotContain("<script").doesNotContain("onerror").contains("본문");

        ResponseEntity<Map> snap = rest.exchange("/api/reports/" + rptpId + "/ai", HttpMethod.GET,
                new HttpEntity<>(session), Map.class);
        assertThat(snap.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) snap.getBody().get("rptpId")).longValue()).isEqualTo(rptpId);
        assertThat(snap.getBody()).containsEntry("rptpNm", TITLE);
        assertThat((String) snap.getBody().get("html"))
                .doesNotContain("<script").doesNotContain("onerror").contains("본문");
    }

    @Test
    @DisplayName("동시 등록 — 선조회가 빗나가 UNIQUE 위반이 나도 200 + 기존 rptpId (실패한 쪽 TB_RPTP 는 롤백)")
    @SuppressWarnings("rawtypes")
    void uniqueViolationFallsBackTo200() {
        ResponseEntity<Map> first = post(body(MSG_ID, 0));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long rptpId = ((Number) first.getBody().get("rptpId")).longValue();
        createdRptpIds.add(rptpId);

        // 선조회만 null(= 다른 요청이 아직 커밋 전이던 상황) → register 가 UNIQUE 에 걸린다 → 재조회는 실제 코드
        willReturn(null).willCallRealMethod()
                .given(aiReportService).findRegistered(anyString(), eq(sessionId), eq(MSG_ID), anyInt());

        ResponseEntity<Map> raced = post(body(MSG_ID, 0));
        assertThat(raced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) raced.getBody().get("rptpId")).longValue()).isEqualTo(rptpId);

        Integer rptpCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp WHERE crt_type = 'AI' AND creatr_id = ? AND rptp_nm = ?",
                Integer.class, IntegrationLoginSupport.MOCK_ID, TITLE);
        assertThat(rptpCount).as("위반난 트랜잭션의 TB_RPTP 행은 함께 롤백돼야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("msgId 가 세션에 없으면 404")
    @SuppressWarnings("rawtypes")
    void unknownMsgIdIs404() {
        ResponseEntity<Map> res = post(body("chatcmpl-none", 0));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("reports[rptIdx] 가 없으면 404 · 미완성(PENDING)이면 404")
    @SuppressWarnings("rawtypes")
    void missingOrPendingReportIs404() {
        ResponseEntity<Map> outOfRange = post(body(MSG_ID, 5));
        assertThat(outOfRange.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> pending = post(body(MSG_ID, 1));
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Integer cnCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_ai_rptp_cn WHERE sesn_id = ?", Integer.class, sessionId);
        assertThat(cnCount).isZero();
    }

    @Test
    @DisplayName("msgId 누락·64자 초과·rptIdx 누락·음수 → 400 (세션 재조회 전에 끊는다)")
    @SuppressWarnings("rawtypes")
    void invalidRequestIs400BeforeUpstream() {
        assertThat(post(body(null, 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(body("  ", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(body("x".repeat(65), 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(body(MSG_ID, null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(body(MSG_ID, -1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        verify(agentApiClient, never()).getSession(anyString(), anyString());
    }

    @Test
    @DisplayName("남의 AI 보고서 스냅샷 → 404 · 없는 보고서 → 404")
    @SuppressWarnings("rawtypes")
    void othersReportSnapshotIs404() {
        Long otherId = jdbcTemplate.queryForObject("""
                INSERT INTO parking.tb_rptp (crt_type, rptp_nm, stts_cd, creatr_id, creatr_nm, crt_ymd)
                VALUES ('AI', 'TESTDATA 남의 보고서', 'DONE', 'tsk4293-other', '남', now())
                RETURNING rptp_id""", Long.class);
        createdRptpIds.add(otherId);
        jdbcTemplate.update("""
                INSERT INTO parking.tb_ai_rptp_cn (rptp_id, user_id, sesn_id, msg_id, rptp_seq, rptp_cn)
                VALUES (?, 'tsk4293-other', ?, 'chatcmpl-other', 0, '<p>남의 것</p>')""", otherId, sessionId);

        ResponseEntity<Map> others = rest.exchange("/api/reports/" + otherId + "/ai", HttpMethod.GET,
                new HttpEntity<>(session), Map.class);
        assertThat(others.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> none = rest.exchange("/api/reports/" + Long.MAX_VALUE + "/ai", HttpMethod.GET,
                new HttpEntity<>(session), Map.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("본문 없는 내 보고서(AI 아님)의 /ai 스냅샷 → 404")
    @SuppressWarnings("rawtypes")
    void ownReportWithoutContentIs404() {
        Long mineId = jdbcTemplate.queryForObject("""
                INSERT INTO parking.tb_rptp (crt_type, rptp_nm, stts_cd, creatr_id, creatr_nm, crt_ymd)
                VALUES ('MANUAL', 'TESTDATA 본문 없음', 'DONE', ?, 'mock', now())
                RETURNING rptp_id""", Long.class, IntegrationLoginSupport.MOCK_ID);
        createdRptpIds.add(mineId);

        ResponseEntity<Map> res = rest.exchange("/api/reports/" + mineId + "/ai", HttpMethod.GET,
                new HttpEntity<>(session), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
