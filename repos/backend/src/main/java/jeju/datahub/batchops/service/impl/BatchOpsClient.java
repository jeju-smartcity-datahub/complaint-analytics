package jeju.datahub.batchops.service.impl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.batchops.service.BatchOpsLiveVO;
import jeju.datahub.batchops.service.BatchOpsProperties;
import jeju.datahub.batchops.service.BatchOpsProxyException;

/**
 * 배치 앱 운영 API({@code /api/batch/ops/**}) 클라이언트.
 *
 * <p>🔴 <b>요청 팩토리는 {@code AgentApiClient} 와 같은 조립이다</b> —
 * {@link BufferingClientHttpRequestFactory}({@link JdkClientHttpRequestFactory}(HTTP/1.1)). 요청 바디를
 * 버퍼링해 {@code Content-Length} 로 보낸다(chunked 금지). 회귀는 실소켓
 * {@code BatchOpsProxyIntegrationTest} 가 진다({@code MockRestServiceServer} 는 전송 계층을 타지 않는다).
 *
 * <p>🔴 <b>응답은 {@code byte[]} 로 받는다</b> — 로그는 {@code text/plain} 이라 charset 표기가 빠지면
 * {@code StringHttpMessageConverter} 가 ISO-8859-1 로 읽어 한글이 조용히 깨진다. 디코딩은 호출부가 UTF-8 로 한다.
 *
 * <p>상태 판정(호출부가 받는 것):
 * <ul>
 *   <li>2xx · 400 · 404 · 409 → {@link Upstream} 그대로(배치 본문 {@code {code, message, ...}} 를 전달)</li>
 *   <li>그 밖(401·403·5xx·예상 밖) · 연결 실패 · 타임아웃 → {@link BatchOpsProxyException#unavailable()} 503.
 *       🔴 401 을 넘기면 프론트가 로그인으로 튕긴다</li>
 *   <li>{@code base-url} 이 비었다 → {@link BatchOpsProxyException#notConfigured()} 503</li>
 * </ul>
 *
 * <p>카탈로그는 짧게 캐시한다({@code catalog-cache-ttl-ms}, 기본 5s) — 목록 화면이 2초 폴링한다.
 * 🔴 카탈로그는 <b>전용 RestClient(읽기 2s, {@code catalog-read-timeout-ms})</b>로 부르고, 실패도 짧게 캐시한다
 * ({@code catalog-failure-cache-ttl-ms}, 기본 5s — 그동안은 배치를 부르지 않는다). 배치가 멈춰 있어도 목록·상세 GET 이
 * 프론트 axios 10s 안에 DB 만으로 끝나게 하려는 것이다. 명령이 2xx 로 끝나면 성공·실패 캐시를 함께 비운다.
 */
@Component
public class BatchOpsClient {

    private static final Logger log = LoggerFactory.getLogger(BatchOpsClient.class);

    static final String HEADER_API_KEY = "X-API-Key";
    public static final String HEADER_NEXT_BYTE = "X-Next-Byte";
    public static final String HEADER_JOB_RUNNING = "X-Job-Running";

    private static final String PATH_OPS = "/api/batch/ops";
    private static final TypeReference<List<BatchOpsCatalogJob>> CATALOG_TYPE = new TypeReference<>() {
    };

    /** 응답 원문 — 상태·헤더·본문 바이트. */
    public record Upstream(int status, HttpHeaders headers, byte[] body) {
        public boolean is2xx() {
            return status >= 200 && status < 300;
        }
    }

    /** 카탈로그 캐시 한 칸 — {@code jobs == null} 이면 실패 캐시(negative) */
    private record CachedCatalog(List<BatchOpsCatalogJob> jobs, long expiresAtNanos) {
    }

    /** 명령·로그용 — 읽기 {@code read-timeout-ms}(10s) */
    private final RestClient rest;
    /** 카탈로그 전용 — 읽기 {@code catalog-read-timeout-ms}(2s) */
    private final RestClient catalogRest;
    private final BatchOpsProperties props;
    private final ObjectMapper objectMapper;
    /** 끝 {@code /} 를 뗀 base URL (비면 미설정) */
    private final String baseUrl;

    private final AtomicReference<CachedCatalog> catalogCache = new AtomicReference<>();
    /** 카탈로그 실패 WARN 을 상태가 바뀔 때만 남긴다 — 2초 폴링이 로그를 덮지 않게 */
    private final AtomicBoolean catalogOk = new AtomicBoolean(true);

    @Autowired
    public BatchOpsClient(RestClient.Builder builder, BatchOpsProperties props, ObjectMapper objectMapper) {
        this.rest = builder.requestFactory(requestFactory(props, props.getReadTimeoutMs())).build();
        this.catalogRest = builder.requestFactory(requestFactory(props, props.getCatalogReadTimeoutMs())).build();
        this.props = props;
        this.objectMapper = objectMapper;
        this.baseUrl = normalize(props.getBaseUrl());
        if (baseUrl.isEmpty()) {
            log.info("[batch-ops] app.batch-ops.base-url 미설정 — 명령 API 는 503 BATCH_OPS_NOT_CONFIGURED, 조회는 카탈로그 없이 동작");
        }
    }

    /** 🔴 끝 {@code /} 금지 — 붙어 있으면 떼고 WARN 1줄(기동 시 1회). {@code //api/...} 가 되는 것을 막는다. */
    private static String normalize(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.endsWith("/")) {
            String stripped = v.replaceAll("/+$", "");
            log.warn("[batch-ops] app.batch-ops.base-url 끝의 '/' 를 뗐다 — 설정에서 지울 것: '{}' → '{}'", v, stripped);
            v = stripped;
        }
        return v;
    }

    /** {@code AgentApiClient#timeoutFactory} 와 같은 조립 — HTTP/1.1 고정 · connect 는 HttpClient, read 는 팩토리. */
    private static ClientHttpRequestFactory requestFactory(BatchOpsProperties props, int readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return new BufferingClientHttpRequestFactory(rf);
    }

    // ---------------------------------------------------------------- 카탈로그

    /**
     * 카탈로그 9개. 미설정·연결 실패·비정상 응답·실패 캐시 중이면 {@link Optional#empty()} — 조회 API 는 DB 만으로 그린다(예외 없음).
     */
    public Optional<List<BatchOpsCatalogJob>> catalog() {
        if (baseUrl.isEmpty()) {
            return Optional.empty();
        }
        long ttl = props.getCatalogCacheTtlMs();
        CachedCatalog cached = catalogCache.get();
        if (cached != null && System.nanoTime() - cached.expiresAtNanos() < 0) {
            // 성공 캐시면 그 목록, 실패 캐시면 배치를 부르지 않고 곧바로 empty
            return Optional.ofNullable(cached.jobs());
        }
        try {
            Upstream up = exchange(catalogRest, HttpMethod.GET, uri(PATH_OPS + "/jobs"), null, MediaType.APPLICATION_JSON);
            if (!up.is2xx()) {
                catalogFailed("status=" + up.status());
                return Optional.empty();
            }
            List<BatchOpsCatalogJob> jobs = List.copyOf(objectMapper.readValue(up.body(), CATALOG_TYPE));
            catalogCache.set(ttl > 0 ? new CachedCatalog(jobs, deadline(ttl)) : null);
            if (catalogOk.compareAndSet(false, true)) {
                log.info("[batch-ops] 카탈로그 수신 복구 — jobs={}", jobs.size());
            }
            return Optional.of(jobs);
        } catch (RestClientException | IOException e) {
            catalogFailed("cause=" + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void catalogFailed(String why) {
        long failTtl = props.getCatalogFailureCacheTtlMs();
        catalogCache.set(failTtl > 0 ? new CachedCatalog(null, deadline(failTtl)) : null);
        if (catalogOk.compareAndSet(true, false)) {
            log.warn("[batch-ops] 카탈로그 수신 실패 — DB 만으로 목록을 그린다(catalogAvailable=false) {}", why);
        } else {
            log.debug("[batch-ops] 카탈로그 수신 실패(지속) {}", why);
        }
    }

    private static long deadline(long ttlMs) {
        return System.nanoTime() + Duration.ofMillis(ttlMs).toNanos();
    }

    /** 명령이 성공하면 부른다 — 성공·실패 캐시를 함께 비운다(실행 중 ID·스케줄 등록 상태가 다음 목록 조회에 바로 보이게). */
    void invalidateCatalog() {
        catalogCache.set(null);
    }

    /** 배치 {@code /live} 응답 본문. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record LiveBody(BatchOpsLiveVO metrics) {
    }

    /**
     * 실행 중 단계별 수치 {@code GET /executions/{id}/live}. 카탈로그와 같은 <b>읽기 2s 전용 클라이언트</b>를 쓰고
     * 🔴 예외를 던지지 않는다 — 404(지원하지 않는 잡·이미 끝남)·미설정·연결 실패·카탈로그 실패 캐시 중이면 전부 empty
     * (상세 조회가 배치 상태에 끌려 실패하지 않게. 실패 캐시 중엔 배치를 부르지 않는다).
     */
    public Optional<BatchOpsLiveVO> live(long jobExecutionId) {
        if (baseUrl.isEmpty()) {
            return Optional.empty();
        }
        CachedCatalog cached = catalogCache.get();
        if (cached != null && cached.jobs() == null && System.nanoTime() - cached.expiresAtNanos() < 0) {
            return Optional.empty();
        }
        try {
            Upstream up = exchange(catalogRest, HttpMethod.GET,
                    uri(PATH_OPS + "/executions", String.valueOf(jobExecutionId), "live"), null, MediaType.APPLICATION_JSON);
            if (!up.is2xx()) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(up.body(), LiveBody.class).metrics());
        } catch (RestClientException | IOException e) {
            log.debug("[batch-ops] live 수신 실패 jobExecutionId={} cause={}", jobExecutionId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- 명령

    /** {@code POST /jobs/{jobNm}/executions} {@code {params, excutrId}} → 202. */
    public Upstream execute(String jobNm, Map<String, String> params, String excutrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params == null ? Map.of() : params);
        body.put("excutrId", excutrId);
        return command(HttpMethod.POST, uri(PATH_OPS + "/jobs", jobNm, "executions"), body);
    }

    /** {@code PUT /schedules/{jobNm}} {@code {cronExpr, useYn, mdfrId}} → 200. */
    public Upstream updateSchedule(String jobNm, String cronExpr, String useYn, String mdfrId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cronExpr", cronExpr);
        body.put("useYn", useYn);
        body.put("mdfrId", mdfrId);
        return command(HttpMethod.PUT, uri(PATH_OPS + "/schedules", jobNm), body);
    }

    /** {@code POST /executions/{id}/stop} {@code {excutrId}} → 202. */
    public Upstream stop(long jobExecutionId, String excutrId) {
        return command(HttpMethod.POST,
                uri(PATH_OPS + "/executions", String.valueOf(jobExecutionId), "stop"), Map.of("excutrId", excutrId));
    }

    /** {@code POST /executions/{id}/abandon} {@code {excutrId}} → 200. */
    public Upstream abandon(long jobExecutionId, String excutrId) {
        return command(HttpMethod.POST,
                uri(PATH_OPS + "/executions", String.valueOf(jobExecutionId), "abandon"), Map.of("excutrId", excutrId));
    }

    /**
     * {@code GET /executions/{id}/log?tailKb=|fromByte=} — 성공은 text/plain + {@code X-Next-Byte}·{@code X-Job-Running}.
     * 🔴 Accept 에 JSON 을 함께 싣는다 — 오류는 JSON 이라 {@code text/plain} 만 보내면 배치가 406 을 낸다.
     */
    public Upstream log(long jobExecutionId, Integer tailKb, Long fromByte) {
        String base = requireConfigured();
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(base)
                .path(PATH_OPS + "/executions").pathSegment(String.valueOf(jobExecutionId), "log");
        if (tailKb != null) {
            b.queryParam("tailKb", tailKb);
        }
        if (fromByte != null) {
            b.queryParam("fromByte", fromByte);
        }
        return passOrUnavailable(HttpMethod.GET, b.build().encode().toUri(), null,
                MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON);
    }

    private Upstream command(HttpMethod method, URI uri, Object body) {
        Upstream up = passOrUnavailable(method, uri, body, MediaType.APPLICATION_JSON);
        if (up.is2xx()) {
            invalidateCatalog();
        }
        return up;
    }

    // ---------------------------------------------------------------- 전송

    private Upstream passOrUnavailable(HttpMethod method, URI uri, Object body, MediaType... accept) {
        Upstream up;
        try {
            up = exchange(rest, method, uri, body, accept);
        } catch (RestClientException e) {
            log.warn("[batch-ops] 배치 호출 실패 — {} {} cause={}", method, uri.getPath(), e.getClass().getSimpleName());
            throw BatchOpsProxyException.unavailable();
        }
        int s = up.status();
        if (up.is2xx() || s == 400 || s == 404 || s == 409) {
            return up;
        }
        // 🔴 401(키 불일치)·5xx·예상 밖 상태는 전부 503 — 원인은 로그로만 남긴다
        log.warn("[batch-ops] 배치 응답 거절 — {} {} status={}", method, uri.getPath(), s);
        throw BatchOpsProxyException.unavailable();
    }

    private Upstream exchange(RestClient client, HttpMethod method, URI uri, Object body, MediaType... accept) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri).accept(accept);
        if (StringUtils.hasText(props.getApiKey())) {
            spec.header(HEADER_API_KEY, props.getApiKey());
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        // exchange 는 상태코드로 예외를 던지지 않는다 — 판정은 위에서 한다. IOException 은 ResourceAccessException 이 된다.
        return spec.exchange((req, res) -> {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(res.getHeaders());
            return new Upstream(res.getStatusCode().value(), headers, StreamUtils.copyToByteArray(res.getBody()));
        });
    }

    private String requireConfigured() {
        if (baseUrl.isEmpty()) {
            throw BatchOpsProxyException.notConfigured();
        }
        return baseUrl;
    }

    /** 경로 조각은 {@code pathSegment} 로 붙인다 — 값 속 {@code /} 가 경로를 바꾸지 못하게 인코딩된다. */
    private URI uri(String path, String... segments) {
        return UriComponentsBuilder.fromUriString(requireConfigured())
                .path(path).pathSegment(segments).build().encode().toUri();
    }
}
