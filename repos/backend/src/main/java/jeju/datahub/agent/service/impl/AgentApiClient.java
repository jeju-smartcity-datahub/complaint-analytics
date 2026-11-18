package jeju.datahub.agent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.agent.service.AgentApiException;
import jeju.datahub.agent.service.AgentApiException.Code;
import jeju.datahub.agent.service.AgentProperties;
import jeju.datahub.agent.service.AgentResponseFilter;
import jeju.datahub.agent.service.AgentToolCallPendingException;
import jeju.datahub.agent.service.AiMessageVO;
import jeju.datahub.agent.service.AiReportBlockVO;
import jeju.datahub.agent.service.AiSessionDetailVO;
import jeju.datahub.agent.service.AiSessionListVO;
import jeju.datahub.agent.service.AiSessionVO;

/**
 * Agent Service({@code /v1/sessions})를 호출해 우리 VO 로 옮기는 유일한 지점.
 *
 * <h2>이 클래스가 지는 세 가지 (매핑에서 함께 한다)</h2>
 * <ol>
 *   <li><b>snake → camel 변환</b>. 대응표는 각 VO 의 javadoc 이 정본이다.</li>
 *   <li><b>{@code role} 이 {@code system}·{@code tool} 인 메시지 제거</b> — 프론트에서 거르면
 *       내부 프롬프트가 응답 바디에 실려 브라우저까지 간다.</li>
 *   <li><b>모델별 응답 필터를 태운 뒤 빈 {@code content} 떨구기</b> —
 *       제거 규칙 자체는 {@link AgentResponseFilter} 구현이 지고, 여기서는 <b>비면 떨군다</b> 는
 *       우리 표시 규칙만 남긴다.</li>
 * </ol>
 *
 * <h2>🔴 생성자가 둘인 이유</h2>
 * 운영 생성자가 {@code requestFactory(...)} 를 부르는데, {@code DefaultRestClientBuilder#build()} 는
 * 그 시점의 팩토리를 <b>스냅샷</b>한다. {@code MockRestServiceServer.bindTo(builder)} 도 같은 자리에
 * 목 팩토리를 심으므로, 운영 생성자만 있으면 목이 타임아웃 팩토리에 덮여 테스트가 성립하지 않는다.
 * 그래서 <b>조립된 {@link RestClient} 를 그대로 받는 package-private 생성자</b>를 따로 둔다.
 * <p>🔴 운영 생성자의 주입 애노테이션은 필수다 — 생성자가 2개라 없으면 컨테이너가 no-arg 를 찾다
 * 기동에 실패한다.
 * <p>🔴 이 클래스 안에서 {@link RestClient} 를 스스로 만들지 않는다(정적 팩토리·빌더 모두).
 * 만드는 순간 주입된(=목일 수 있는) 클라이언트가 무시된다.
 *
 * <h2>🔴 응답 후처리는 모델별 필터가 진다</h2>
 * {@code <think>} 제거 정규식을 이 클래스에 박아 두면 <b>모델을 바꾸는 사람이 이 파일을 열어
 * 정규식을 고치게</b> 된다 — {@code <think>} 는 모델({@code minimax}) 고유 습성이지 Agent API 의
 * 계약이 아니다. 그래서 {@code app.agent.model} 이름으로 {@link AgentResponseFilter} 를 고른다.
 * <p>🔴 <b>미등록 모델명이면 통과(no-op) + 기동 시 WARN 1회</b>다. 조용히 원문이 나가면 다음 사람이
 * 원인을 못 찾고, 그렇다고 기동을 죽이면 모델명 오타로 화면 전체가 죽는다.
 * <p>🔴 <b>주입받은 Map 의 키는 빈 이름</b>이므로 {@link AgentResponseFilter#modelName()} 으로
 * 다시 인덱싱한다 — 설정에 적는 이름은 빈 이름이 아니라 모델 이름이다.
 *
 * <h2>🔴 재시도하지 않는다</h2>
 * upstream 401 은 {@code AGENT_UNAUTHORIZED} 로 그대로 던진다 — 이 클래스는 토큰을 문자열로만 받아
 * 갱신할 수단이 없다. 재시도 책임은 토큰 보유자({@code AgentTokenStore#withToken})에게 있다.
 *
 * <h2>🔴 마스킹</h2>
 * <b>upstream 응답 본문을 로그·예외에 싣지 않는다.</b> 남기는 것은 status 와 요청 경로뿐이다 —
 * 본문에는 토큰·내부 프롬프트가 섞일 수 있고, 예외 메시지는 그대로 응답으로 흘러 나간다.
 */
@Component
public class AgentApiClient {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(AgentApiClient.class);

    /** 외부 JSON 파싱 전용 — 앱 직렬화 설정과 무관하게 동작하도록 자체 인스턴스를 쓴다(스레드 안전). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PATH_SESSIONS = "/v1/sessions";

    /** 전송 경로 — OpenAI 호환 표면. {@code X-Agent-Id} 가 비면 벤더가 거절한다. */
    private static final String PATH_CHAT_COMPLETIONS = "/v1/chat/completions";

    /** 🔴 {@code X-Agent-Id} 는 상세 스펙에 선언돼 있지 않지만 보내도 무해하다 — §2 함정 2·4. */
    private static final String HEADER_AGENT_ID = "X-Agent-Id";

    /**
     * 🔴 새 대화·이어쓰기를 가르는 헤더. 스펙에 선언돼 있지 않지만 실측으로
     * <b>세션이 실제로 생기는 것</b>을 확인했다(§1 ②).
     */
    private static final String HEADER_SESSION_ID = "X-Session-Id";

    /**
     * 🔴 SSE 에러 프레임 선판정 접두 (①).
     *
     * <p>요청 검증 실패는 <b>HTTP 200 + {@code data: {"error":{…}}}</b> 로 온다. 그대로
     * {@code readTree} 하면 파싱이 깨지거나 엉뚱한 노드가 나오므로 <b>먼저</b> 본다.
     * 정상 응답은 SSE 가 아니라 단일 JSON 객체다.
     */
    private static final String SSE_DATA_PREFIX = "data:";

    /** 정상 종료를 뜻하는 {@code finish_reason}. 그 밖(특히 {@code error})은 답변이 없는 것이다. */
    private static final String FINISH_REASON_ERROR = "error";

    /** 길이 상한으로 잘린 {@code finish_reason} — 미완성 보고서 영역을 {@code TRUNCATED} 로 본다. */
    private static final String FINISH_REASON_LENGTH = "length";

    /** 내려도 되는 role 두 가지. 그 밖(system·tool …)은 아예 내리지 않는다. */
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    /** 도구 호출 결과를 돌려보내는 role. 우리는 <b>거절</b>에만 쓴다. */
    private static final String ROLE_TOOL = "tool";

    /** 세션 상세가 이보다 오래 걸리면 WARN(한도는 detail-read-timeout-ms 30s). */
    private static final long DETAIL_SLOW_WARN_MS = 10_000;

    /**
     * 거절 본문. Dtonic 화면이 `거절` 버튼에 보내는 값 그대로다(2026-11-17 실측).
     * 🔴 객체가 아니라 <b>JSON 문자열</b>을 {@code content} 에 담는다 — 벤더 화면이 그렇게 보낸다.
     */
    static final String TOOL_REJECT_CONTENT = "{\"approved\":false}";

    /** 로그에 남길 도구 이름 정제 — 업스트림 값이 로그 줄을 깨지 않게 한다. */
    private static final java.util.regex.Pattern TOOL_NAME_UNSAFE = java.util.regex.Pattern.compile("[^A-Za-z0-9_.\\-]");
    private static final int TOOL_NAME_MAX_LEN = 64;

    /** 벤더 스펙 {@code string(1..512)}. 예시만 보고 좁히면 긴 ID 가 막힌다. */
    private static final int SESSION_ID_MAX_LEN = 512;

    /** 미등록 모델명일 때 쓰는 통과 필터 — 원문 그대로 돌려준다. */
    private static final AgentResponseFilter NO_OP = new AgentResponseFilter() {
        @Override
        public String apply(String content) {
            return content;
        }

        @Override
        public String modelName() {
            return "(none)";
        }
    };

    /** 🔴 조회 전용 — {@code connect 2s / read 5s}. */
    private final RestClient rest;

    /**
     * 🔴 전송 전용 — {@code connect 2s / read 120s}.
     *
     * <p>인스턴스를 나눈 이유: {@link RestClient}(Boot 3.4.1)는 <b>요청별 타임아웃 오버라이드를
     * 제공하지 않는다.</b> {@link #rest} 를 그대로 쓰면 LLM 응답이 120s 가 아니라 5초에 끊긴다.
     * 반대로 {@link AgentProperties#getReadTimeoutMs()} 를 올려 맞추면 세션 목록 조회까지 느리게
     * 실패한다. 🔴 늘리는 것은 <b>read 하나뿐</b>이고 connect 는 두 팩토리가 공유한다.
     */
    private final RestClient chatRest;

    /** 세션 상세 전용({@code connect 2s / read detail-read-timeout-ms}). 목록·삭제는 {@link #rest}. */
    private final RestClient detailRest;

    private final AgentProperties props;

    /** {@code app.agent.model} 로 고른 응답 필터. 미등록이면 {@link #NO_OP} (기동 시 WARN 1회). */
    private final AgentResponseFilter responseFilter;

    /**
     * 보고서 영역 추출기 — 상태 없는 일반 클래스. 🔴 생성자 주입이 아니라 필드 초기화다 —
     * 생성자 3종 시그니처(테스트 시임)를 바꾸지 않기 위해서다.
     */
    private final AgentReportExtractor extractor = new AgentReportExtractor();

    /**
     * 🔴 아래 주입 애노테이션 필수 — 생성자가 2개라 없으면 no-arg 를 찾다가 기동에 실패한다.
     *
     * @param filters 스프링이 넣어 주는 {@link AgentResponseFilter} 빈들. 🔴 <b>키가 빈 이름</b>이라
     *                {@code modelName()} 으로 다시 인덱싱한다.
     */
    @Autowired
    public AgentApiClient(RestClient.Builder builder, AgentProperties props,
                          Map<String, AgentResponseFilter> filters) {
        this(builder.requestFactory(timeoutFactory(props)).build(),        // 조회용 (2s/5s)
             builder.requestFactory(chatTimeoutFactory(props)).build(),    // 🔴 전송용 (2s/120s)
             builder.requestFactory(detailTimeoutFactory(props)).build(),  // 🆕 상세용 (2s/detail-read)
             props, filters);
    }

    /**
     * 🔴 <b>기존 3인자 시임 유지</b>(목 주입 지점, package-private) —
     * 두 클라이언트를 같은 것으로 본다. {@code MockRestServiceServer} 테스트에서는 타임아웃이
     * 무의미하므로 이래도 되고, 덕분에 {@code AgentApiClientTest} 가 <b>무수정으로</b> 통과한다.
     */
    AgentApiClient(RestClient rest, AgentProperties props, Map<String, AgentResponseFilter> filters) {
        this(rest, rest, props, filters);
    }

    /** 🔴 조립이 끝난 두 클라이언트를 그대로 받는다(package-private) — 조회용·전송용. */
    AgentApiClient(RestClient rest, RestClient chatRest, AgentProperties props,
                   Map<String, AgentResponseFilter> filters) {
        this(rest, chatRest, rest, props, filters);
    }

    /** 조회용·전송용·상세용 세 클라이언트를 받는다(package-private). */
    AgentApiClient(RestClient rest, RestClient chatRest, RestClient detailRest, AgentProperties props,
                   Map<String, AgentResponseFilter> filters) {
        // 🔴 baseUrl 을 여기서 박지 않는다 — 빈 값 판정을 호출 시점에 하려고(2차 방어선).
        this.rest = rest;
        this.chatRest = chatRest;
        this.detailRest = detailRest;
        this.props = props;
        this.responseFilter = resolveFilter(props, filters);
    }

    /**
     * 모델 이름 → 필터. 🔴 <b>미등록이면 통과(no-op) + WARN 1회</b>이고 <b>기동을 죽이지 않는다</b> —
     * 모델명 오타로 화면 전체가 죽는 것이 더 나쁘다. 생성자에서 한 번만 부르므로 WARN 도 기동 시 1회다.
     */
    private static AgentResponseFilter resolveFilter(AgentProperties props,
                                                     Map<String, AgentResponseFilter> filters) {
        Map<String, AgentResponseFilter> byModel = new LinkedHashMap<>();
        if (filters != null) {
            // 🔴 주입된 Map 의 키는 빈 이름이다 — 설정과 대조되는 modelName() 이 키가 되게 다시 담는다.
            filters.values().forEach(f -> byModel.put(f.modelName(), f));
        }
        String model = props.getModel();
        AgentResponseFilter found = StringUtils.hasText(model) ? byModel.get(model.trim()) : null;
        if (found == null) {
            log.warn("[agent-api] app.agent.model='{}' 에 맞는 AgentResponseFilter 가 없다 — 후처리 없이 통과한다. 등록된 모델: {}",
                    model, byModel.keySet());
            return NO_OP;
        }
        return found;
    }

    /**
     * 🔴 <b>왜 {@link JdkClientHttpRequestFactory} 인가 — {@code SimpleClientHttpRequestFactory} 를
     * 쓰면 전송이 항상 400 으로 거절된다</b> (2026-09-08 E2E 실측).
     *
     * <ol>
     *   <li>{@code SimpleClientHttpRequestFactory}(JDK {@code HttpURLConnection})는 요청 바디를
     *       <b>버퍼링하지 않고 스트리밍</b>해 {@code Transfer-Encoding: chunked} 로 보낸다
     *       (실제 요청 캡처로 확인).</li>
     *   <li>업스트림 게이트웨이(istio-envoy)가 <b>chunked 요청 바디를 거절</b>한다 — curl 실측:
     *       {@code HTTP/2 + Content-Length} ✅ 200 · {@code HTTP/1.1 + Content-Length} ✅ 200 ·
     *       <b>{@code HTTP/1.1 + Transfer-Encoding: chunked} ❌ 400
     *       {@code Invalid HTTP request received.}</b></li>
     *   <li>바디 내용은 <b>무관</b>했다. 조회({@code GET})가 멀쩡했던 것은 바디가 없어 chunked 가 될
     *       일이 없었기 때문이다 — 즉 이 문제는 <b>전송(POST) 경로만</b>의 것이었다.</li>
     * </ol>
     * {@link JdkClientHttpRequestFactory}({@link HttpClient} 기반)는 Spring 이 바디를 버퍼링해
     * <b>{@code Content-Length} 를 붙인다.</b> 회귀는 {@code AgentChatTransportTest} 가 <b>실제
     * 소켓</b>으로 못박는다 — {@code MockRestServiceServer} 는 전송 계층을 타지 않아 이 결함을
     * 한 건도 잡지 못했다.
     *
     * <p>🔴 <b>타임아웃 설정 위치가 둘로 갈린다</b>: connect 는 {@link HttpClient.Builder}, read 는
     * {@link JdkClientHttpRequestFactory#setReadTimeout(Duration)} 이다.
     * <p>🔴 <b>{@code HTTP_1_1} 로 고정한다</b> — 평문 {@code http} 에서 JDK 기본값
     * ({@code HTTP_2})은 h2c 업그레이드를 시도해 게이트웨이가 다시 요청 모양을 트집 잡을 여지를
     * 남긴다. 위 실측표에서 <b>{@code HTTP/1.1 + Content-Length} 가 이미 200</b> 이므로 굳이 위험을
     * 지지 않는다.
     */
    private static HttpClient httpClient(AgentProperties props) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))  // 🔴 read 는 여기가 아니다
                .build();
    }

    /**
     * 🔴 <b>{@link BufferingClientHttpRequestFactory} 로 감싸는 것이 핵심이다</b> — 팩토리 교체만으로는
     * 부족하다. {@link JdkClientHttpRequestFactory} 도 바디를 <b>스트리밍</b>하고
     * ({@code BodyPublishers.fromPublisher} 를 길이 없이 호출), Jackson 컨버터는
     * {@code Content-Length} 를 계산해 주지 않는다 — 그러면 다시 chunked 다(실측으로 확인했다).
     * {@code AbstractBufferingClientHttpRequest} 가 바디를 바이트로 모은 뒤
     * <b>{@code Content-Length} 가 없으면 직접 채워 준다.</b>
     */
    private static ClientHttpRequestFactory buffered(JdkClientHttpRequestFactory rf) {
        return new BufferingClientHttpRequestFactory(rf);
    }

    /** 조회 전용 팩토리 — {@code connect 2s / read 5s}. */
    private static ClientHttpRequestFactory timeoutFactory(AgentProperties props) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient(props));
        rf.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return buffered(rf);
    }

    /**
     * 전송 전용 팩토리 — 🔴 <b>connect 는 조회와 같은 값</b>이고 read 만 늘린다.
     * 연결 수립이 길어질 이유는 없다 — 긴 것은 LLM 이 답을 만드는 시간뿐이다.
     */
    private static ClientHttpRequestFactory chatTimeoutFactory(AgentProperties props) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient(props));
        rf.setReadTimeout(Duration.ofMillis(props.getChatTimeoutMs()));
        return buffered(rf);
    }

    /** 세션 상세 전용 팩토리. connect 는 공유하고 read 만 {@code detail-read-timeout-ms}. */
    private static ClientHttpRequestFactory detailTimeoutFactory(AgentProperties props) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient(props));
        rf.setReadTimeout(Duration.ofMillis(props.getDetailReadTimeoutMs()));
        return buffered(rf);
    }

    // ------------------------------------------------------------------ 공개 API

    /**
     * 세션 목록 — {@code GET /v1/sessions?agent_id=&limit=&cursor=}.
     *
     * <p>🔴 <b>{@code agentId} 파라미터가 없는 것은 의도다</b>. 주입받은
     * {@code props.getAgentId()} 를 스스로 쿼리에 붙인다 — 호출부가 지정할 수 있으면 빠뜨릴 수 있고,
     * 그 순간 여러 Agent 가 공유하는 기본 세션 ID {@code current} 때문에 남의 대화가 재생된다.
     *
     * @param token  로그인 사용자 본인의 access_token
     * @param limit  조회 건수. {@code null} 이면 {@code app.agent.default-limit}
     * @param cursor 이어 받기 커서. 비면 첫 페이지
     */
    public AiSessionListVO listSessions(String token, Integer limit, String cursor) {
        String baseUrl = requireConfigured();
        int effectiveLimit = (limit != null) ? limit : props.getDefaultLimit();

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path(PATH_SESSIONS)
                .queryParam("agent_id", props.getAgentId())   // 🔴 백엔드가 붙인다
                .queryParam("limit", effectiveLimit);
        if (StringUtils.hasText(cursor)) {
            uri.queryParam("cursor", cursor);
        }
        URI target = uri.build().encode().toUri();

        JsonNode root = call(target, token, null, PATH_SESSIONS);
        return toSessionList(root);
    }

    /**
     * 세션 상세 — {@code GET /v1/sessions/{sessionId}} + 헤더 {@code X-Agent-Id}.
     *
     * <p>🔴 여기에도 {@code agentId} 파라미터를 두지 않는다 — 위와 같은 이유다.
     *
     * @param token     로그인 사용자 본인의 access_token
     * @param sessionId 목록이 준 {@code sessionId}
     */
    public AiSessionDetailVO getSession(String token, String sessionId) {
        String baseUrl = requireConfigured();
        validateSessionId(sessionId);

        URI target = UriComponentsBuilder.fromUriString(baseUrl)
                .path(PATH_SESSIONS)
                .pathSegment(sessionId)
                .build()
                .encode()
                .toUri();

        // 상세 전용 클라이언트(read 30s). 대화가 바뀐 뒤 첫 조회는 5초를 넘긴다(실측 최대 14.5s).
        //    10초를 넘기면 WARN 1줄 — 30초 한도에 다가가는지 운영에서 보려는 것이다. 본문은 남기지 않는다.
        long started = System.nanoTime();
        JsonNode root = call(detailRest, target, token, props.getAgentId(), PATH_SESSIONS + "/{sessionId}");
        long elapsed = elapsedMs(started);
        if (elapsed > DETAIL_SLOW_WARN_MS) {
            log.warn("[agent-api] 세션 상세 조회가 느리다 — sessionId={} elapsedMs={} messages={}",
                    sessionId, elapsed, root.path("messages").size());
        }
        return toSessionDetail(root);
    }

    /**
     * 세션 삭제 — {@code DELETE /v1/sessions/{sessionId}} + 헤더 {@code X-Agent-Id}.
     *
     * <p>🔴 여기에도 {@code agentId} 파라미터를 두지 않는다 — 조회와 같은
     * 이유다. 호출부가 지정할 수 있으면 <b>남의 Agent 세션을 지우는 경로</b>가 된다.
     * <p>성공은 Dtonic 명세상 {@code 204}(본문 없음)이지만 공개 스펙은 {@code 200} + JSON 이다 —
     * {@code toBodilessEntity()} 라 <b>2xx 전부 성공</b>으로 본다(본문은 읽지 않는다).
     * <p>🔴 <b>조회용 {@link #rest}(read 5s)</b>를 쓴다 — {@link #chatRest} 아님. 바디가 없어
     * chunked 함정과 무관하고 120s 를 쓸 이유가 없다. 4xx 분류는 {@link #fromClientError} 그대로다.
     *
     * @param token     로그인 사용자 본인의 access_token
     * @param sessionId 목록이 준 {@code sessionId}
     */
    public void deleteSession(String token, String sessionId) {
        String baseUrl = requireConfigured();
        validateSessionId(sessionId);   // 🔴 공용 검증 재사용 — 업스트림 상한 200자에 맞춰 좁히지 않는다

        URI target = UriComponentsBuilder.fromUriString(baseUrl)
                .path(PATH_SESSIONS)
                .pathSegment(sessionId)
                .build()
                .encode()
                .toUri();
        String logPath = PATH_SESSIONS + "/{sessionId}";

        try {
            rest.delete()   // 🔴 조회용 rest 다 — 전송용(read 120s)이 아니다
                    .uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HEADER_AGENT_ID, props.getAgentId())     // 🔴 백엔드가 붙인다
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // 🔴 401 은 그대로 AGENT_UNAUTHORIZED — withToken 이 갱신 후 1회 재시도한다.
            throw fromClientError(e.getStatusCode().value(), logPath);
        } catch (HttpServerErrorException e) {
            // 🔴 status 만 남긴다 — 본문에는 내부 정보가 섞인다.
            log.warn("[agent-api] upstream 5xx — path={} status={}", logPath, e.getStatusCode().value());
            throw new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
        } catch (RestClientException e) {
            // 타임아웃·네트워크. 🔴 예외 타입만 남긴다(메시지에 URL 이 섞인다).
            log.warn("[agent-api] upstream 호출 실패 — path={} cause={}", logPath, e.getClass().getSimpleName());
            throw new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
        }
    }

    /**
     * 전송 — {@code POST /v1/chat/completions} + 헤더 {@code X-Agent-Id}·{@code X-Session-Id}.
     *
     * <h2>🔴 업스트림에 보내는 것은 이게 전부다</h2>
     * <pre>{"messages":[{"role":"user","content":"…"}]}</pre>
     * <ul>
     *   <li>🔴 <b>{@code model} 을 넣지 않는다</b>(§1 ⑥ 실측) — 라우팅에는 <b>무시</b>되고 응답에
     *       <b>그대로 에코</b>될 뿐이라, 넣으면 응답의 {@code model} 이 진단 가치를 잃는다.
     *       모델은 {@code agent-id} 가 정한다.</li>
     *   <li>🔴 <b>{@code stream} 도 넣지 않는다</b> — 이번 범위는 동기 응답이다(이관).</li>
     *   <li>🔴 <b>{@code sessionId} 는 호출부가 만들어 넘긴다</b> — 새 대화의
     *       {@code portal-{UUID}} 생성은 컨트롤러의 몫이다. 여기서 만들면 {@code withToken} 의 401
     *       재시도가 다른 세션 ID 를 만들어 응답의 {@code sessionId} 와 실제로 보낸 값이 갈린다.</li>
     * </ul>
     *
     * <h2>🔴 "HTTP 200 = 성공" 이 아니다</h2>
     * 응답 {@code Content-Type} 은 {@code stream} 을 안 보내도 <b>항상 {@code text/event-stream}</b>
     * 이고, 요청 검증 실패도 <b>200 + SSE 에러 프레임</b>으로 온다(§1 ①). 그래서 실패 판정은
     * status 가 아니라 {@link #toChatMessage} 가 본문으로 한다.
     *
     * @param token     로그인 사용자 본인의 access_token
     * @param sessionId 업스트림 {@code X-Session-Id} 에 실을 값 — 새 대화면 {@code portal-{UUID}}
     * @param message   사용자 질문 원문 (길이 상한은 {@code AiChatRequest} 가 진다)
     * @return assistant 답변 1건. 🔴 {@code AiChatResponse} 가 아니다 — 세션 ID 를 실어 주는 것은
     *         그것을 만든 컨트롤러의 몫이다
     */
    public AiMessageVO sendMessage(String token, String sessionId, String message) {
        String baseUrl = requireConfigured();
        validateSessionId(sessionId);
        if (!StringUtils.hasText(message)) {
            // 🔴 2차 방어선 — 형식 검증은 AiChatRequest(@NotBlank·@Size)가 이미 했다.
            throw new AgentApiException(Code.AGENT_BAD_REQUEST);
        }

        // 🔴 보내는 것은 messages 하나뿐이다 (§1 ⑥ 결론) — model·stream 을 넣지 않는다.
        //    전송 400 의 원인은 바디가 아니라 Transfer-Encoding: chunked 였다(요청 팩토리 주석 참조).
        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of("role", ROLE_USER, "content", message)));
        // 답변 없이 도구 호출만 오면 AgentToolCallPendingException 이 그대로 올라간다
        //    (자동 거절은 컨트롤러가 별도 withToken 으로 보낸다 — 여기서 보내면 401 재시도가 질문을 다시 보낸다).
        return toChatMessage(postChat(baseUrl, token, sessionId, body));
    }

    /**
     * 답 없이 남은 도구 호출을 <b>거절</b>하고 그 뒤의 답변을 받는다.
     *
     * <pre>{"messages":[{"role":"tool","tool_call_id":"call_…","content":"{\"approved\":false}"}, …]}</pre>
     * Dtonic 화면의 `거절` 버튼과 같은 요청이다(2026-11-17 실측 — {@code X-Agent-Id}·{@code X-Session-Id} 도 같다).
     * 거절 응답은 일반 답변처럼 온다(예: "요청하신 보고서 생성 기능을 현재 바로 실행할 수 없습니다 …").
     *
     * <ul>
     *   <li>🔴 <b>자동 거절은 1회뿐이다</b> — 이 응답에 또 도구 호출만 오면 더 보내지 않고
     *       {@code AGENT_TOOL_CALL_UNRESOLVED}(409)로 끝낸다(무한 왕복 방지). 그 대화에는 답 없는 호출이 남는다.</li>
     *   <li>🔴 {@code stream} 은 넣지 않는다 — 벤더 화면은 {@code stream:true} 지만 우리 전송과 같은 동기 응답을 받는다.</li>
     * </ul>
     *
     * @param toolCallIds 직전 응답의 {@code tool_calls[].id} — 비어 있으면 {@code AGENT_BAD_REQUEST}
     */
    public AiMessageVO rejectToolCalls(String token, String sessionId, List<String> toolCallIds) {
        String baseUrl = requireConfigured();
        validateSessionId(sessionId);
        if (toolCallIds == null || toolCallIds.isEmpty()) {
            throw new AgentApiException(Code.AGENT_BAD_REQUEST);
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (String id : toolCallIds) {
            messages.add(Map.of("role", ROLE_TOOL, "tool_call_id", id, "content", TOOL_REJECT_CONTENT));
        }
        try {
            AiMessageVO vo = toChatMessage(postChat(baseUrl, token, sessionId, Map.of("messages", messages)));
            // 🔴 모델의 "승인이 확인되지 않아 …" 문단을 고정 문구로 바꾼다 — 조회(toSessionDetail)도 같은 함수다.
            vo.setContent(ToolRejectReplyRewriter.rewrite(vo.getContent()));
            return vo;
        } catch (AgentToolCallPendingException again) {
            // 🔴 ERROR — 관리자가 "관리자에게 문의" 를 받고 찾을 줄이다. arguments 원문은 남기지 않는다.
            log.error("[agent-api] tool_call unresolved after auto-reject — sessionId={} rejected={} name={} count={}",
                    sessionId, toolCallIds.size(), again.getToolNames(), again.getToolCallIds().size());
            throw new AgentApiException(Code.AGENT_TOOL_CALL_UNRESOLVED);
        }
    }

    /**
     * 전송 공통 — {@code POST /v1/chat/completions} 를 부르고 <b>원문 본문</b>을 돌려준다(판정은 호출부의
     * {@link #toChatMessage}). 질문 전송과 도구 거절이 같은 요청 팩토리·헤더·디코딩을 쓰게 한 곳에 둔다.
     */
    private String postChat(String baseUrl, String token, String sessionId, Map<String, Object> body) {
        URI target = UriComponentsBuilder.fromUriString(baseUrl)
                .path(PATH_CHAT_COMPLETIONS)
                .build()
                .encode()
                .toUri();

        // 🔴 한시적 진단 로그(스위치 OFF 면 찍지 않는다). 보낸 그대로 · 받은 그대로.
        boolean diag = props.isDiagLogEnabled();
        if (diag) {
            String bodyJson;
            try {
                bodyJson = MAPPER.writeValueAsString(body);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                bodyJson = "(직렬화 실패)";
            }
            ChatDiagLog.upstreamRequest(true, target.toString(), props.getAgentId(), sessionId, token, bodyJson);
        }
        long started = System.nanoTime();

        String raw;
        try {
            // 🔴 String 이 아니라 byte[] 로 받아 UTF-8 로 직접 디코딩한다 (charset 방어).
            //    ① 응답 Content-Type 은 stream 을 안 보내도 항상 text/event-stream 이다(§1 실측).
            //    ② StringHttpMessageConverter 는 text/* 에 charset 표기가 없으면 ISO-8859-1 로 읽는다
            //       — UTF-8 특례는 application/json 뿐이다. 벤더가 charset=utf-8 표기를 빼는 순간
            //       한글 답변이 예외 없이 조용히 전부 깨진다.
            //    ③ SSE 도 JSON 도 표준상 항상 UTF-8 이므로, 헤더 표기에 기대지 않고 우리가 고정한다.
            org.springframework.http.ResponseEntity<byte[]> entity = chatRest.post()  // 🔴 조회용 rest 가 아니다 — read-timeout 이 다르다
                    .uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HEADER_AGENT_ID, props.getAgentId())     // 🔴 비면 벤더가 400 으로 거절한다
                    .header(HEADER_SESSION_ID, sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] bytes = entity.getBody();
            // 🔴 비었을 때의 동작을 바꾸지 않는다 — 빈 문자열이 toChatMessage 로 가 AGENT_NO_ANSWER 가 된다.
            raw = (bytes == null) ? "" : new String(bytes, StandardCharsets.UTF_8);
            ChatDiagLog.upstreamResponse(diag, sessionId, entity.getStatusCode().value(),
                    String.valueOf(entity.getHeaders().getContentType()), elapsedMs(started), raw, null);
        } catch (HttpClientErrorException e) {
            upstreamFailureDiag(diag, sessionId, started, e);
            // 🔴 401 은 그대로 AGENT_UNAUTHORIZED 다 — withToken 이 갱신 후 1회 재시도한다.
            //    만료·변조·누락 4종 전부 upstream 401 이라는 것이 실측됐다(§2-3 ⑤-f).
            throw fromClientError(e.getStatusCode().value(), PATH_CHAT_COMPLETIONS);
        } catch (HttpServerErrorException e) {
            upstreamFailureDiag(diag, sessionId, started, e);
            log.warn("[agent-api] upstream 5xx — path={} status={}", PATH_CHAT_COMPLETIONS, e.getStatusCode().value());
            throw new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
        } catch (RestClientException e) {
            ChatDiagLog.upstreamResponse(diag, sessionId, null, null, elapsedMs(started), null,
                    e.getClass().getSimpleName());
            log.warn("[agent-api] upstream 호출 실패 — path={} cause={}", PATH_CHAT_COMPLETIONS, e.getClass().getSimpleName());
            throw new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
        }

        return raw;
    }

    /** 진단 — 업스트림 4xx·5xx 응답(상태·헤더·본문). 스위치 OFF 면 아무것도 하지 않는다. */
    private static void upstreamFailureDiag(boolean diag, String sessionId, long started,
                                            org.springframework.web.client.HttpStatusCodeException e) {
        if (!diag) {
            return;
        }
        HttpHeaders h = e.getResponseHeaders();
        ChatDiagLog.upstreamResponse(true, sessionId, e.getStatusCode().value(),
                h == null ? null : String.valueOf(h.getContentType()), elapsedMs(started),
                e.getResponseBodyAsString(StandardCharsets.UTF_8), e.getClass().getSimpleName());
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    // ------------------------------------------------------------------ 호출 전 검증

    /**
     * 🔴 <b>2차 방어선</b>이다 — 판정 순서(설정 → 토큰)는 컨트롤러가 진다. 이 메서드는
     * {@code withToken} 람다 안에서 실행되므로 토큰이 없으면 애초에 닿지 않는다.
     * 그럼에도 두는 이유는 이 클래스를 직접 부르는 경로가 생겨도 빈 URL 로 호출하지 않게 하기 위해서다.
     */
    private String requireConfigured() {
        String baseUrl = props.getBaseUrl();
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(props.getAgentId())) {
            throw new AgentApiException(Code.AGENT_NOT_CONFIGURED);
        }
        return baseUrl.trim();
    }

    /**
     * {@code sessionId} 오염 검사 — {@code /}·{@code %} 포함 또는 512자 초과면 호출 전에 거절한다.
     *
     * <p>🔴 {@code ResponseStatusException}·{@code IllegalArgumentException} 을 쓰지 않는다 —
     * advice 가 {@link AgentApiException} 만 잡으므로 다른 타입이면 code 없는 500 이 나간다.
     */
    private void validateSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)
                || sessionId.length() > SESSION_ID_MAX_LEN
                || sessionId.indexOf('/') >= 0
                || sessionId.indexOf('%') >= 0) {
            throw new AgentApiException(Code.AGENT_BAD_REQUEST);
        }
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * GET 1회 + JSON 파싱. 실패는 4종 code 로만 나간다.
     *
     * @param logPath 로그·진단에 남길 <b>요청 경로</b>. 🔴 URL 전체(쿼리에 커서·agent_id 가 섞인다)나
     *                응답 본문을 남기지 않는다.
     */
    private JsonNode call(URI target, String token, String agentIdHeader, String logPath) {
        return call(rest, target, token, agentIdHeader, logPath);
    }

    private JsonNode call(RestClient client, URI target, String token, String agentIdHeader, String logPath) {
        String body;
        try {
            body = client.get()
                    .uri(target)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .headers(h -> {
                        if (StringUtils.hasText(agentIdHeader)) {
                            h.set(HEADER_AGENT_ID, agentIdHeader);
                        }
                    })
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            throw fromClientError(e.getStatusCode().value(), logPath);
        } catch (HttpServerErrorException e) {
            // 🔴 status 만 남긴다 — 본문에는 내부 정보가 섞인다.
            log.warn("[agent-api] upstream 5xx — path={} status={}", logPath, e.getStatusCode().value());
            throw new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
        } catch (RestClientException e) {
            // 타임아웃·네트워크·본문 변환 실패. 🔴 예외 타입만 남긴다(메시지에 URL 이 섞인다).
            log.warn("[agent-api] upstream 호출 실패 — path={} cause={}", logPath, e.getClass().getSimpleName());
            throw new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
        }

        try {
            return MAPPER.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            // 🔴 파싱 실패도 본문을 남기지 않는다.
            log.warn("[agent-api] upstream 응답 파싱 실패 — path={}", logPath);
            throw new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
        }
    }

    /**
     * 4xx 분류.
     *
     * <p>🔴 403·404 를 502 로 뭉개지 않는다 — 권한/대상 없음은 우리 장애가 아니고 화면 문구가 다르다.
     * 그 밖의 4xx(예: 429)는 표에 없으므로 남은 갈래인 {@code AGENT_UPSTREAM_ERROR} 로 수렴시킨다 —
     * 던지는 것은 4종을 벗어나지 않는다.
     */
    private AgentApiException fromClientError(int status, String logPath) {
        log.warn("[agent-api] upstream 4xx — path={} status={}", logPath, status);
        if (status == 401) {
            // 🔴 재시도하지 않는다 — 갱신 수단이 없다. 그대로 전파한다.
            return new AgentApiException(Code.AGENT_UNAUTHORIZED);
        }
        if (status == 403 || status == 404) {
            return new AgentApiException(Code.AGENT_UPSTREAM_REJECTED, status);
        }
        return new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
    }

    // ------------------------------------------------------------------ 매핑

    /**
     * 전송 응답 본문 → {@link AiMessageVO}.
     *
     * <h2>🔴 실패 판정은 셋 다 본다 — 어느 경우도 HTTP status 는 200 이다</h2>
     * <ol>
     *   <li><b>ⓐ 본문이 {@code data:} 로 시작한다</b> = SSE 에러 프레임
     *       ({@code data: {"error":{…,"code":422|400}}}). → {@code AGENT_BAD_REQUEST}(400).
     *       🔴 <b>{@code AGENT_NO_ANSWER} 로 뭉뚱그리지 않는다</b> — 이건 "AI 가 답을 못 만든 것" 이
     *       아니라 <b>우리가 요청을 잘못 보낸 것</b>이라 고쳐야 할 주체가 다르다.
     *       🔴 {@code error.message} 원문은 응답·로그 어디에도 싣지 않는다(마스킹 원칙).</li>
     *   <li><b>ⓑ {@code choices[0].finish_reason == "error"}</b> → {@code AGENT_NO_ANSWER}(502).
     *       🔴 <b>{@code finish_reason} 을 먼저 본다</b> — "빈 문자열이면" 만으로 잡으면 벤더가 빈
     *       문자열 대신 공백 한 칸을 넣는 순간 새어 나간다.</li>
     *   <li><b>ⓒ {@code content} 가 null 이거나 필터 통과 후 빈 문자열</b> → {@code AGENT_NO_ANSWER}(502).</li>
     * </ol>
     * ⚠️ 4121 의 <b>조회</b>는 빈 {@code content} 메시지를 조용히 <b>떨궜다</b>. 그건 "이미 지난
     * 대화의 잡음" 이라 맞지만, <b>전송은 방금 요청한 것이라 조용히 사라지면 안 된다</b> — 빈
     * {@code message} 를 200 으로 내리면 프론트가 빈 말풍선을 그리고 사용자는 원인을 모른다.
     * 그래서 같은 규칙을 쓰지 않는다.
     *
     * <p>🔴 {@code content} 는 조회와 <b>같은</b> {@link AgentResponseFilter} 를 통과한다 —
     * 한쪽만 걸면 "이력에는 없던 {@code <think>} 가 방금 받은 답변에만 보인다" 가 된다.
     */
    private AiMessageVO toChatMessage(String body) {
        String raw = (body == null) ? "" : body.strip();

        // ⓐ 🔴 SSE 선판정 — readTree 보다 먼저다. 정상 응답은 단일 JSON 객체라 여기 걸리지 않는다.
        if (raw.startsWith(SSE_DATA_PREFIX)) {
            // 🔴 error.message 는 벤더가 고정 문구로 주는 진단값이다("No messages provided." ·
            //    "Field required" …) — 사용자 입력도 비밀도 아니므로 남긴다. 그것이 없으면
            //    "우리가 무엇을 잘못 보냈는지" 를 알 방법이 없다(본문 전체는 여전히 남기지 않는다).
            log.warn("[agent-api] 전송 요청이 거절됐다 — path={} upstreamErrorCode={} upstreamErrorMessage={}",
                    PATH_CHAT_COMPLETIONS, sseErrorCode(raw), sseErrorMessage(raw));
            throw new AgentApiException(Code.AGENT_BAD_REQUEST);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            // 🔴 파싱 실패도 본문을 남기지 않는다.
            log.warn("[agent-api] upstream 응답 파싱 실패 — path={}", PATH_CHAT_COMPLETIONS);
            throw new AgentApiException(Code.AGENT_UPSTREAM_ERROR);
        }

        // 진단용 — 응답의 model 은 Agent 배포명이다. 🔴 app.agent.model(LLM 이름)과 비교해 WARN 하지
        //          않는다(§2-3 #6 — 정상인데도 달라 오탐이다). 필터 선택은 설정값이 정본이다.
        log.debug("[agent-api] 전송 응답 — path={} model={}", PATH_CHAT_COMPLETIONS, text(root, "model"));

        JsonNode choice = root.path("choices").path(0);
        // ⓑ 🔴 finish_reason 을 먼저 본다.
        String finishReason = text(choice, "finish_reason");
        if (FINISH_REASON_ERROR.equals(finishReason)) {
            log.warn("[agent-api] 업스트림이 답변을 만들지 못했다 — path={} finishReason={}",
                    PATH_CHAT_COMPLETIONS, finishReason);
            throw new AgentApiException(Code.AGENT_NO_ANSWER);
        }

        // ⓒ content 는 조회와 같은 필터를 통과한다.
        //    필터 <b>다음</b> 보고서 영역 추출. 상세 경로와 같은 순서다(한쪽만 걸면
        //    "방금 받은 답변엔 카드가 있는데 새로고침하면 원문 HTML" 이 된다).
        String rawContent = text(choice.path("message"), "content");
        String filtered = (rawContent == null) ? "" : responseFilter.apply(rawContent);
        AgentReportExtractor.Extracted extracted = extractor.extract(filtered);
        // 🔴 빈 판정은 추출 뒤 — 자리표시만 남아도 답변이다(떨구지 않는다).
        String content = extracted.content();
        if (!StringUtils.hasText(content)) {
            // 비었는데 도구 호출이 있으면 "답이 없다" 가 아니라 "승인을 기다린다" 다.
            //    본문이 있으면서 도구 호출도 있는 경우는 건드리지 않는다(본문을 그대로 보여 준다).
            AgentToolCallPendingException pending = pendingToolCalls(choice.path("message"));
            if (pending != null) {
                throw pending;
            }
            log.warn("[agent-api] 답변 본문이 비었다 — path={} finishReason={}", PATH_CHAT_COMPLETIONS, finishReason);
            throw new AgentApiException(Code.AGENT_NO_ANSWER);
        }

        AiMessageVO vo = new AiMessageVO();
        vo.setRole(ROLE_ASSISTANT);   // 🔴 원천의 role 을 그대로 믿지 않는다 — 화면이 그리는 것은 답변이다
        vo.setContent(content);
        // 전송 응답 최상위 id == 세션 상세 assistant 메시지 completion_id(실측).
        vo.setMsgId(text(root, "id"));
        vo.setReports(markTruncated(extracted.reports(), finishReason));
        // 🔴 createdAt 은 채우지 않는다 — 전송 응답 최상위에 오지 않는다(§2-3 #3 실측).
        return vo;
    }

    /**
     * 전송 경로에서 {@code finish_reason=length}(길이 상한 잘림)가 확인되면 미완성({@code PENDING})
     * 영역을 {@code TRUNCATED} 로 바꾼다. 재조회해도 같은 결과라 프론트가 기다리지 않게 하려는 것이다.
     * 상세 경로에는 {@code finish_reason} 이 없어 {@code PENDING} 그대로 둔다.
     */
    private static List<AiReportBlockVO> markTruncated(List<AiReportBlockVO> reports, String finishReason) {
        List<AiReportBlockVO> out = new ArrayList<>(reports);
        if (FINISH_REASON_LENGTH.equals(finishReason)) {
            for (AiReportBlockVO r : out) {
                if (AiReportBlockVO.STTS_PENDING.equals(r.getStts())) {
                    r.setStts(AiReportBlockVO.STTS_TRUNCATED);
                }
            }
        }
        return out;
    }

    /**
     * SSE 에러 프레임에서 {@code error.code} 만 꺼낸다 — 🔴 <b>로그에 남기는 것은 이 코드뿐</b>이고
     * {@code error.message} 원문은 남기지 않는다(마스킹 원칙). 읽어 내지 못하면 {@code null}.
     */
    /**
     * SSE 에러 프레임의 {@code error.message}. 🔴 <b>벤더 고정 문구</b>라 진단에 쓰고 남긴다 —
     * 사용자 입력이 아니다. 길이 상한을 두어 이상 응답이 로그를 채우지 않게 한다.
     */
    private static String sseErrorMessage(String raw) {
        for (String line : raw.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith(SSE_DATA_PREFIX)) {
                continue;
            }
            String payload = trimmed.substring(SSE_DATA_PREFIX.length()).strip();
            if (payload.isEmpty() || !payload.startsWith("{")) {
                continue;
            }
            try {
                JsonNode msg = MAPPER.readTree(payload).path("error").path("message");
                if (msg.isTextual()) {
                    String v = msg.asText();
                    return v.length() > 200 ? v.substring(0, 200) + "…" : v;
                }
            } catch (JsonProcessingException ignored) {
                // 진단 부가정보라 실패해도 흐름을 바꾸지 않는다.
                log.debug("[agent-api] 오류 본문 message 해석 실패 — 생략");   // 4.2
            }
        }
        return null;
    }

    private static Integer sseErrorCode(String raw) {
        for (String line : raw.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith(SSE_DATA_PREFIX)) {
                continue;
            }
            String payload = trimmed.substring(SSE_DATA_PREFIX.length()).strip();
            if (payload.isEmpty() || !payload.startsWith("{")) {
                continue;   // "[DONE]" 등
            }
            try {
                JsonNode code = MAPPER.readTree(payload).path("error").path("code");
                if (code.isNumber()) {
                    return code.asInt();
                }
            } catch (JsonProcessingException ignored) {
                // 🔴 진단 부가정보라 실패해도 흐름을 바꾸지 않는다.
                log.debug("[agent-api] 오류 본문 code 해석 실패 — 생략");   // 4.2
            }
        }
        return null;
    }

    private AiSessionListVO toSessionList(JsonNode root) {
        AiSessionListVO vo = new AiSessionListVO();
        for (JsonNode node : root.path("sessions")) {
            AiSessionVO session = new AiSessionVO();
            // 🔴 원천의 sessions[].id 는 내리지 않는다 — 키는 session_id 하나다.
            session.setSessionId(text(node, "session_id"));
            session.setAgentId(text(node, "agent_id"));
            session.setAgentName(text(node, "agent_name"));
            session.setTitle(text(node, "title"));
            session.setPreview(text(node, "preview"));
            session.setCreatedAt(text(node, "created_at"));
            session.setUpdatedAt(text(node, "updated_at"));
            vo.getSessions().add(session);
        }
        vo.setNextCursor(text(root, "next_cursor"));
        return vo;
    }

    private AiSessionDetailVO toSessionDetail(JsonNode root) {
        AiSessionDetailVO vo = new AiSessionDetailVO();
        vo.setSessionId(text(root, "session_id"));

        // 승인 필요 도구를 부른 assistant 바로 뒤 답변은 전송 때와 같이 고쳐 쓴다.
        //    🔴 이력에는 role:tool(거절) 메시지가 남지 않는다(2026-11-18 실측) — 도구 이름으로 찾는다.
        boolean afterApprovalTool = false;
        for (JsonNode node : root.path("messages")) {
            String role = text(node, "role");
            if (ROLE_USER.equals(role)) {
                afterApprovalTool = false;
            }
            boolean rewriteThis = afterApprovalTool && ROLE_ASSISTANT.equals(role);
            if (ROLE_ASSISTANT.equals(role) && ToolRejectReplyRewriter.callsApprovalTool(node)) {
                afterApprovalTool = true;   // 🔴 이 메시지 자신이 아니라 다음 답변부터
            }
            // ② system·tool 은 아예 내리지 않는다(화이트리스트로 판정한다).
            if (!ROLE_USER.equals(role) && !ROLE_ASSISTANT.equals(role)) {
                continue;
            }
            // ③ 모델별 필터를 태운다. 남는 것이 없으면 그 메시지도 떨군다(우리 표시 규칙).
            String raw = text(node, "content");
            String content = (raw == null) ? "" : responseFilter.apply(raw);
            // 필터 다음 보고서 영역 추출. 🔴 assistant 만(사용자가 마커를 쳐도 보고서가 아니다).
            //    전송 경로와 같은 순서다. 빈 판정은 추출 뒤 — 자리표시만 남아도 떨구지 않는다.
            boolean assistant = ROLE_ASSISTANT.equals(role);
            AgentReportExtractor.Extracted extracted = assistant ? extractor.extract(content) : null;
            if (extracted != null) {
                content = extracted.content();
            }
            if (!StringUtils.hasText(content)) {
                continue;
            }
            if (rewriteThis) {
                content = ToolRejectReplyRewriter.rewrite(content);
                afterApprovalTool = false;
            }
            AiMessageVO message = new AiMessageVO();
            message.setRole(role);
            message.setContent(content);
            // completion_id 를 msgId 로 내린다(assistant 만 · user 는 null).
            if (extracted != null) {
                message.setMsgId(text(node, "completion_id"));
                message.setReports(new ArrayList<>(extracted.reports()));
            }
            message.setCreatedAt(text(node, "created_at"));
            vo.getMessages().add(message);
        }

        // 🔴 최상위 pending_approval · state 는 내리지 않는다.
        JsonNode context = root.path("context");
        if (context.isObject()) {
            AiSessionDetailVO.ContextVO ctx = new AiSessionDetailVO.ContextVO();
            ctx.setUsedTokens(number(context, "used_tokens"));
            ctx.setSystemTokens(number(context, "system_tokens"));
            ctx.setHistoryTokens(number(context, "history_tokens"));
            ctx.setBudgetTokens(number(context, "budget_tokens"));
            ctx.setLimitTokens(number(context, "limit_tokens"));
            vo.setContext(ctx);
        }
        return vo;
    }

    /** 없거나 null 이면 {@code null} — 빈 문자열로 채우지 않는다(화면이 "없음"을 구분한다). */
    /**
     * {@code message.tool_calls[]} 에서 거절에 쓸 ID 와 로그용 이름만 뽑는다.
     * ID 가 하나도 없으면(배열이 없거나 형식이 깨졌다) {@code null} — 호출부가 종전대로 {@code AGENT_NO_ANSWER} 로 간다.
     * 🔴 {@code function.arguments} 는 읽지 않는다(대화 원문이 들어 있다).
     */
    private static AgentToolCallPendingException pendingToolCalls(JsonNode message) {
        JsonNode calls = message.path("tool_calls");
        if (!calls.isArray()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (JsonNode c : calls) {
            String id = text(c, "id");
            if (!StringUtils.hasText(id)) {
                continue;
            }
            ids.add(id);
            String name = text(c.path("function"), "name");
            String safe = name == null ? "?" : TOOL_NAME_UNSAFE.matcher(name).replaceAll("");
            names.add(safe.length() > TOOL_NAME_MAX_LEN ? safe.substring(0, TOOL_NAME_MAX_LEN) : safe);
        }
        return ids.isEmpty() ? null : new AgentToolCallPendingException(ids, names);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /** 없거나 숫자가 아니면 {@code null} — 0 과 "모름" 을 구분한다. */
    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }
}
