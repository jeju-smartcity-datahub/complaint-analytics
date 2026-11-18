package jeju.datahub.batchops.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 배치 운영 API 접속 설정 (TSK-4337 Step 7 — {@code app.batch-ops.*}).
 *
 * <p>🔴 <b>{@code @Component} + {@code @ConfigurationProperties} 세트다</b> — {@code AgentProperties} 와 같은 이유
 * (이 레포에는 {@code @ConfigurationPropertiesScan} 이 없다).
 *
 * <p>🔴 <b>{@code baseUrl} 기본값은 빈 문자열이다</b> — 비어도 기동은 되고, 명령 API 만 호출 시점에
 * 503 {@code BATCH_OPS_NOT_CONFIGURED} 다. 조회 API(DB 직접 읽기)는 영향이 없다(카탈로그만 빠진다).
 * 🔴 끝에 {@code /} 를 붙이지 않는다 — 붙어 있으면 {@code BatchOpsClient} 가 기동 시 떼고 WARN 1줄을 남긴다.
 */
@Component
@ConfigurationProperties(prefix = "app.batch-ops")
public class BatchOpsProperties {

    /** 배치 앱 base URL (env {@code BATCH_OPS_BASE_URL}). 비면 명령 API 503 */
    private String baseUrl = "";

    /** 배치 운영 API 키 — 배치의 {@code BATCH_API_KEY} 와 같은 값 (env {@code BATCH_OPS_API_KEY}). 비면 헤더를 싣지 않는다 */
    private String apiKey = "";

    /** 연결 타임아웃(ms) */
    private int connectTimeoutMs = 3000;

    /** 읽기 타임아웃(ms) — 실행은 비동기(202)라 짧아도 된다 */
    private int readTimeoutMs = 10000;

    /**
     * 카탈로그 캐시 수명(ms). 목록 화면이 2초 폴링하므로 요청마다 배치를 두드리지 않게 한다.
     * 실패는 {@link #catalogFailureCacheTtlMs} 로 따로 캐시하고, 이 백엔드가 보낸 명령(실행·스케줄·중지·실패 처리)이 성공하면 둘 다 즉시 비운다.
     * {@code 0} 이면 캐시하지 않는다.
     */
    private long catalogCacheTtlMs = 5000;

    /**
     * 카탈로그 조회 전용 읽기 타임아웃(ms). 🔴 명령용 {@link #readTimeoutMs}(10s)와 따로 둔다 — 배치가 멈춰(hang) 있을 때
     * 목록·상세 GET 이 10s 를 기다리면 프론트 axios(10s)가 먼저 실패한다("배치가 꺼져도 목록이 보인다" 위반).
     */
    private int catalogReadTimeoutMs = 2000;

    /**
     * 카탈로그 <b>실패</b> 캐시 수명(ms) — 이 동안은 배치를 부르지 않고 곧바로 DB 만으로 그린다(2초 폴링이 요청 스레드를
     * 쌓지 않게). 명령이 2xx 로 끝나면 함께 비운다. {@code 0} 이면 실패를 캐시하지 않는다.
     */
    private long catalogFailureCacheTtlMs = 5000;

    public int getCatalogReadTimeoutMs() {
        return catalogReadTimeoutMs;
    }

    public void setCatalogReadTimeoutMs(int catalogReadTimeoutMs) {
        this.catalogReadTimeoutMs = catalogReadTimeoutMs;
    }

    public long getCatalogFailureCacheTtlMs() {
        return catalogFailureCacheTtlMs;
    }

    public void setCatalogFailureCacheTtlMs(long catalogFailureCacheTtlMs) {
        this.catalogFailureCacheTtlMs = catalogFailureCacheTtlMs;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
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

    public long getCatalogCacheTtlMs() {
        return catalogCacheTtlMs;
    }

    public void setCatalogCacheTtlMs(long catalogCacheTtlMs) {
        this.catalogCacheTtlMs = catalogCacheTtlMs;
    }
}
