package jeju.datahub.agent.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 🔴 한시적 진단 로그 — 운영서버에서 AI 채팅 전송이 실패하는 원인을 보려고
 * <b>우리 {@code POST /api/ai/messages}</b> 와 <b>업스트림 {@code POST /v1/chat/completions}</b> 의
 * 요청·응답을 통째로 남긴다. 확인이 끝나면 env 를 끄고 이 클래스와 호출부를 지운다.
 *
 * <p>스위치 {@code app.agent.diag-log-enabled} — env {@code AGENT_DIAG_LOG}, 없으면 {@code AUTH_DIAG_LOG}
 * 를 따른다(기본 false). 🔴 {@code agent} → {@code auth} import 금지 규칙 때문에 {@code AuthDiagLog} 를 쓰지
 * 않고 여기 따로 둔다.
 *
 * <p>🔴 <b>질문·답변 원문(개인정보·내부 내용)이 INFO 로 남는다</b> — "대화를 저장하지 않는다" 원칙의
 * 의도적 예외다. 토큰은 원문이 아니라 지문만 남긴다 — {@code AuthDiagLog} 의 {@code exchange 결과} 줄과
 * 같은 알고리즘(SHA-256 앞 12 hex)이라 {@code fp} 로 대조된다. ⚠️ 세션 해시({@code SesnIdHasher})가 아니다.
 *
 * <p>한 줄 = 한 사건. 본문의 개행은 {@code \n} 으로 바꿔 grep 한 줄에 담고, {@link #BODY_MAX} 자를 넘으면 자른다.
 * 같은 전송의 줄들은 {@code sid}(= {@code X-Session-Id})로 묶인다.
 */
public final class ChatDiagLog {

    private static final Logger log = LoggerFactory.getLogger(ChatDiagLog.class);

    static final String TAG = "[chat-diag] ";

    /** 본문 절단 길이 — "전체" 를 보되 HTML 보고서가 수십 KB 라 상한을 둔다. */
    static final int BODY_MAX = 20_000;

    private ChatDiagLog() {
    }

    // ------------------------------------------------------------------ 우리 API (/api/ai/messages)

    /** 우리 전송 API 수신. */
    public static void apiRequest(boolean on, String path, String sessionId, boolean newSession, String message) {
        if (!on) {
            return;
        }
        log.info(TAG + "api 요청 path={} sid={} newSession={} messageLen={} message={}",
                path, sessionId, newSession, message == null ? 0 : message.length(), flat(message));
    }

    /** withToken 이 람다를 부른 시점 — 401 재시도면 attempt=2 다. */
    public static void apiAttempt(boolean on, String sessionId, int attempt, String token) {
        if (!on) {
            return;
        }
        log.info(TAG + "api 시도 sid={} attempt={} tokenFp={}", sessionId, attempt, fingerprint(token));
    }

    /** 우리 전송 API 정상 응답(프론트로 나가는 값). */
    public static void apiResponse(boolean on, String sessionId, long elapsedMs, String msgId,
                                   String content, int reportCnt) {
        if (!on) {
            return;
        }
        log.info(TAG + "api 응답 sid={} status=200 elapsedMs={} msgId={} reports={} contentLen={} content={}",
                sessionId, elapsedMs, msgId, reportCnt, content == null ? 0 : content.length(), flat(content));
    }

    /** 우리 전송 API 실패 — 프론트가 받는 status·code. */
    public static void apiFailure(boolean on, String sessionId, long elapsedMs, int status, String code) {
        if (!on) {
            return;
        }
        log.info(TAG + "api 실패 sid={} status={} code={} elapsedMs={}", sessionId, status, code, elapsedMs);
    }

    // ------------------------------------------------------------------ 업스트림 (/v1/chat/completions)

    /** 업스트림 호출 직전 — 보낸 그대로. */
    public static void upstreamRequest(boolean on, String uri, String agentId, String sessionId,
                                       String token, String body) {
        if (!on) {
            return;
        }
        log.info(TAG + "upstream 요청 POST {} X-Agent-Id={} X-Session-Id={} tokenFp={} body={}",
                uri, agentId, sessionId, fingerprint(token), flat(body));
    }

    /** 업스트림 응답 — 2xx·4xx·5xx 공통. status 를 모르면(전송 오류) null. */
    public static void upstreamResponse(boolean on, String sessionId, Integer status, String contentType,
                                        long elapsedMs, String body, String cause) {
        if (!on) {
            return;
        }
        log.info(TAG + "upstream 응답 sid={} status={} contentType={} elapsedMs={} bodyLen={} body={}{}",
                sessionId, status == null ? "-" : status, contentType, elapsedMs,
                body == null ? 0 : body.length(), flat(body), cause == null ? "" : " cause=" + cause);
    }

    // ------------------------------------------------------------------ 부품

    /** SHA-256(UTF-8) 앞 12 hex — {@code AuthDiagLog.fingerprint} 와 같은 값. null·빈 값은 {@code "-"}. */
    static String fingerprint(String token) {
        if (token == null || token.isEmpty()) {
            return "-";
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    /** 개행을 {@code \n} 글자로 바꾸고 {@link #BODY_MAX} 자에서 자른다. null·빈 값은 {@code (empty)}. */
    static String flat(String s) {
        if (s == null || s.isEmpty()) {
            return "(empty)";
        }
        String f = s.replace("\r", "").replace("\n", "\\n");
        return f.length() <= BODY_MAX ? f : f.substring(0, BODY_MAX) + "…(" + f.length() + "자 중 " + BODY_MAX + "자)";
    }
}
