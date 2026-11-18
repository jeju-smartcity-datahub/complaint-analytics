package jeju.datahub.batch.rptp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ④{@code reportAutoCrtJob} 이 부르는 <b>백엔드 내부 API</b> 접속 설정
 * ({@code batch.rptp.backend.*}).
 *
 * <p>배치는 보고서를 스스로 만들지 않는다 — 집계·이름짓기·멱등은 전부 백엔드
 * ({@code POST /api/internal/reports/auto-crt})가 한다. 이 설정은 "어디로 보낼지" 하나다.
 *
 * <table>
 *   <tr><th>키</th><th>local</th><th>dev</th></tr>
 *   <tr><td>{@code base-url}</td><td>{@code http://localhost:8180}</td>
 *       <td>{@code http://backend:8070}(compose 서비스명)</td></tr>
 *   <tr><td>{@code api-key}</td><td colspan="2">{@code ${INTERNAL_API_KEY:}} — 환경변수 주입</td></tr>
 *   <tr><td>{@code connect-timeout-ms}</td><td colspan="2">3000</td></tr>
 *   <tr><td>{@code read-timeout-ms}</td><td colspan="2">120000</td></tr>
 * </table>
 *
 * <h2>🔴 {@code base-url} 끝에 {@code '/'} 를 두지 않는다</h2>
 * {@code RestClient} 의 {@code baseUrl} 에 그대로 붙으므로 {@code http://host/} + {@code /api/...}
 * 는 <b>{@code //api/...}</b> 가 된다.
 * 설정에서 실수로 붙여도 동작하도록 <b>compact 생성자가 꼬리 슬래시를 떼어</b> 정규화한다 —
 * 정규화는 방어이지 면허가 아니다. yml 에는 슬래시 없이 적는다.
 *
 * <h2>🔴 {@code read-timeout-ms} 가 120초인 이유</h2>
 * 백엔드가 <b>동기로</b> 집계를 돌리고 집중구역(HOTSPOT)은 DBSCAN 까지 탄다. 그 시간 동안
 * 요청 스레드를 점유하므로 총 소요는 <b>설정 수 × 집계 시간</b>이다(설정 1건씩 순차 호출).
 *
 * <h2>🔒 {@code api-key} 는 로그에 남기지 않는다</h2>
 * {@code toString()} 을 재정의해 값을 가린다 — record 기본 {@code toString()} 은 전 필드를
 * 그대로 찍어 기동 로그·예외 메시지에 키가 샌다.
 *
 * <p>⚠️ 비어 있으면 {@link BackendReportClient} 가 {@code X-API-Key} 헤더 자체를 넣지 않는다.
 * 로컬 백엔드는 {@code app.internal-api.enabled=false} 라 키 없이 통과한다.
 *
 * @param baseUrl          백엔드 호스트(경로 없음, 꼬리 슬래시 없음)
 * @param apiKey           {@code X-API-Key} 헤더 값. 비면 헤더를 보내지 않는다
 * @param connectTimeoutMs 연결 타임아웃(ms)
 * @param readTimeoutMs    응답 대기 타임아웃(ms) — 🔴 집계가 동기로 도는 만큼 넉넉해야 한다
 */
@ConfigurationProperties(prefix = "batch.rptp.backend")
public record BackendReportProperties(
        String baseUrl,
        String apiKey,
        int connectTimeoutMs,
        int readTimeoutMs) {

    public BackendReportProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        apiKey = apiKey == null ? "" : apiKey.trim();
        connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 3_000;
        readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : 120_000;
    }

    /** 🔴 꼬리 슬래시 제거 — {@code //api/internal/...} 로 나가는 것을 막는다. */
    private static String normalizeBaseUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** {@code X-API-Key} 를 실어야 하는지 — 비어 있으면 헤더 자체를 넣지 않는다. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 🔒 키를 가린다 — record 기본 {@code toString()} 은 값을 그대로 찍는다. */
    @Override
    public String toString() {
        return "BackendReportProperties[baseUrl=" + baseUrl
                + ", apiKey=" + (hasApiKey() ? "****" : "(none)")
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs + "]";
    }
}
