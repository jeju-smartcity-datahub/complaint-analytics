package jeju.datahub.agent.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent API 접속 설정 (TSK-4121 Step 2 — {@code app.agent.*}).
 *
 * <p>🔴 <b>{@code @Component} + {@code @ConfigurationProperties} 세트다.</b> 이 레포에는
 * {@code @ConfigurationPropertiesScan}·{@code @EnableConfigurationProperties} 가 한 곳도 없다 —
 * {@code AuthProperties} 도 같은 방식이다. {@code @Component} 를 빼면 기동 시
 * {@code NoSuchBeanDefinitionException} 이다.
 *
 * <p>🔴 <b>{@code baseUrl}·{@code agentId} 의 기본값은 빈 문자열이다</b> — 키가 없어도 기동은 되고,
 * 검증은 호출 시점에 한다({@code AGENT_NOT_CONFIGURED} → 503). 기동을 죽이면 헬스체크까지 함께
 * 죽어 원인 파악이 어려워진다.
 *
 * <p>🔴 <b>{@code agentId} 는 백엔드만 안다(D4).</b> 목록은 쿼리 {@code agent_id=}, 상세는 헤더
 * {@code X-Agent-Id} 로 {@link jeju.datahub.agent.service.impl.AgentApiClient} 가 스스로 붙인다.
 * 프론트·컨트롤러가 이 값을 지정할 수 있으면 클라이언트가 조회 범위를 정하게 되고, 여러 Agent 가
 * 기본 세션 ID {@code current} 를 공유하므로 그 순간 남의 Agent 대화가 재생된다(§2 함정 4).
 *
 * <p>🔴 <b>connect·read 타임아웃을 하나로 합치지 않는다</b> — 최악 경로는 둘의 합이다.
 * 5000 하나로 두면 10s 가 되어 프론트 axios 타임아웃(10_000)과 동률이 된다. 2+5=7s 로 나눠 둔다.
 */
@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /** Agent Service base URL. 🔴 비면 호출 시점에 {@code AGENT_NOT_CONFIGURED}(503) */
    private String baseUrl = "";

    /** 조회 대상 Agent ID (D4). 🔴 비면 호출 시점에 {@code AGENT_NOT_CONFIGURED}(503) */
    private String agentId = "";

    /** 연결 타임아웃(ms) */
    private int connectTimeoutMs = 2000;

    /** 읽기 타임아웃(ms) — 🔴 <b>조회 전용</b>이다. 전송은 {@link #chatTimeoutMs} 를 쓴다. */
    private int readTimeoutMs = 5000;

    /**
     * 전송({@code POST /v1/chat/completions}) 전용 <b>읽기</b> 타임아웃(ms) — TSK-4122 Step 2.
     *
     * <p>🔴 <b>조회 타임아웃을 늘려 맞추지 않는다.</b> LLM 응답은 조회보다 훨씬 길다(실측 0.3~19.7s,
     * 최장은 업스트림이 히스토리를 자동 압축한 턴). {@link #readTimeoutMs} 를 늘리면 세션 목록
     * 조회까지 느리게 실패한다.
     *
     * <p>🔴 <b>{@link #connectTimeoutMs} 는 두 경로가 공유한다</b> — 연결 수립이 길어질 이유는 없다.
     * 늘리는 것은 read 하나뿐이다.
     *
     * <p>⚠️ 120s 는 톰캣 기본 {@code connectionTimeout}(20s) 밖이라 그동안 요청 스레드를 점유한다 —
     * 동시 대화가 늘면 다른 화면까지 느려진다. 지금은 감수하고 <b>TSK-4135</b>(스트리밍)에서 푼다.
     */
    private int chatTimeoutMs = 120000;

    /**
     * 🆕 TSK-4345 — 세션 <b>상세</b>({@code GET /v1/sessions/{id}}) 전용 읽기 타임아웃(ms).
     *
     * <p>상세는 목록보다 느리고 긴 대화를 처음 열 때 5초를 넘긴다(로컬 25일 실측 124건 — 성공 최대 4.97s ·
     * 9건(7%)이 정확히 5.0s 에서 502). 목록(최대 1.1s)까지 늘리지 않게 인스턴스를 나눈다 —
     * {@link #chatTimeoutMs} 를 나눈 것과 같은 이유다. 2026-11-18 실측 — 대화가 바뀐 뒤 첫 조회 최대 14.5s → 30s.
     */
    private int detailReadTimeoutMs = 30000;

    /** 세션 목록 기본 조회 건수 (D11) */
    private int defaultLimit = 20;

    /**
     * 응답 후처리 필터를 고르는 모델 이름 (Step 2-B · D13).
     *
     * <p>🔴 <b>요청 바디의 {@code model} 필드가 아니다</b> — 응답 후처리 필터가 스스로 밝히는
     * {@code modelName()} 과 대조되는 키다. 기본값은 yml 이 준다(AGENT_MODEL, 기본 minimax).
     * 🔴 <b>미등록 이름이면 통과(no-op) + 기동 시 WARN 1회</b> — 모델명 오타로 화면 전체가 죽는 것이
     * 더 나쁘므로 기동을 죽이지는 않는다.
     */
    private String model = "";

    /**
     * 🔴 TSK-4349 한시적 진단 로그({@code ChatDiagLog}) 스위치 — 켜면 채팅 질문·답변 원문이 INFO 로 남는다.
     * env {@code AGENT_DIAG_LOG}, 없으면 {@code AUTH_DIAG_LOG} 를 따른다(yml). 확인 후 끄고 코드째 지운다.
     */
    private boolean diagLogEnabled = false;

    public boolean isDiagLogEnabled() {
        return diagLogEnabled;
    }

    public void setDiagLogEnabled(boolean diagLogEnabled) {
        this.diagLogEnabled = diagLogEnabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getChatTimeoutMs() {
        return chatTimeoutMs;
    }

    public void setChatTimeoutMs(int chatTimeoutMs) {
        this.chatTimeoutMs = chatTimeoutMs;
    }

    public int getDetailReadTimeoutMs() {
        return detailReadTimeoutMs;
    }

    public void setDetailReadTimeoutMs(int detailReadTimeoutMs) {
        this.detailReadTimeoutMs = detailReadTimeoutMs;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
