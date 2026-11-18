package jeju.datahub.batch.cvlcpt.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * DHubManager 데이터 조회 — {@code POST /api/v1/analytics/query} (2026-08-21 이관).
 *
 * <h2>엔드포인트 이관 (구 {@code /api/v1/datasets/{id}/table/query})</h2>
 * 감성·발생일시를 담은 원천({@code minwon_sentiment})으로 갈아타면서 조회 경로도 함께 옮겼다.
 * 요청·응답 형태가 둘 다 달라 <b>같은 클라이언트로 두 경로를 겸용하지 않는다.</b>
 *
 * <pre>{@code
 * POST /api/v1/analytics/query
 * {"kind":"dataset", "source_id":"dataset.…", "query":"SELECT …", "max_rows":1000}
 * }</pre>
 *
 * <h2>응답 형태 — 봉투가 있다</h2>
 * 구 엔드포인트는 <b>최상위 배열</b>이었지만 이쪽은 {@code rows} 봉투다(2026-08-21 실호출 확정).
 *
 * <pre>{@code
 * {"rows":[{"cvlcpt_id":"SPP-2501-1000255", …}], "row_count":2, "truncated":false,
 *  "source":{"kind":"dataset","id":"dataset.…"}, "database":"…", "table":"…"}
 * }</pre>
 *
 * <h2>🔴 {@code max_rows} 는 필수다 — 생략하면 100행만 온다</h2>
 * OpenAPI 스펙({@code AnalyticsQueryRequest}) 원문: "Rows returned, default 100, capped at 1000".
 * 실측(2026-08-21)으로도 {@code max_rows} 없이 {@code LIMIT 500} 을 보내면 <b>100행 + truncated=true</b>
 * 가 온다. 그래서 이 클라이언트는 <b>언제나 {@code max_rows} 를 실어 보내고</b>, 상한 1000 은
 * {@link DhubSql#MAX_PAGE_SIZE} 로 못박아 페이지 크기 검증에서 미리 걸린다.
 *
 * <p>{@code truncated=true} 는 <b>예외로 끊는다.</b> 이 클라이언트가 보내는 SQL 은 항상 자기
 * {@code LIMIT} 이 {@code max_rows} 이하라 정상 경로에서는 false 다 — true 라면 설정이 어긋나
 * <b>조용히 행을 잃고 있는</b> 상황이고, 그대로 두면 keyset 커서가 그 지점부터 건너뛴다.
 *
 * <h2>질의 엔진 · 게이트웨이</h2>
 * 질의는 <b>ClickHouse SQL</b> 로 실행되므로 SQL 안의 {@code ORDER BY}/{@code LIMIT} 이 통한다.
 * 다만 앞단의 <b>istio-envoy 가 응답을 전부 버퍼링</b>해 넘기므로 한 번에 받는 구간을 작게 쪼개는
 * 것 말고는 대용량 대책이 없다 — 그래서 {@code dhub.page-size} 로 나눠 받는다.
 *
 * <h2>인증</h2>
 * 토큰 확보·갱신·401 재시도는 전부 {@link DhubAuthClient#withToken} 에 위임한다. 이 클래스는
 * <b>401 을 {@link DhubUnauthorizedException} 으로 바꿔 던지는 역할만</b> 한다 — 그래야 auth 쪽이
 * 재로그인 후 1회 재시도를 걸 수 있다.
 *
 * <h2>🔒 로깅</h2>
 * {@code Authorization} 헤더 값은 어디에도 남기지 않는다. HTTP 예외도 응답 본문이 섞이지 않게
 * 상태 코드와 예외 타입만 옮긴다.
 */
@Component
public class DhubQueryClient {

    private static final Logger log = LoggerFactory.getLogger(DhubQueryClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String QUERY_PATH = "/api/v1/analytics/query";

    /** {@code AnalyticsQueryRequest.kind} — 우리 원천은 전부 데이터셋이다. */
    static final String SOURCE_KIND = "dataset";

    private final RestClient restClient;
    private final DhubAuthClient authClient;
    private final DhubProperties properties;

    /** 실제로 나간 조회 요청 수(401 재시도 포함) — 페이징이 예상대로 도는지 확인하는 용도. */
    private final AtomicLong requestCount = new AtomicLong();

    public DhubQueryClient(DhubAuthClient authClient, DhubProperties properties) {
        this.authClient = authClient;
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * {@code max_rows} 를 {@code dhub.page-size} 로 두고 실행한다 — 페이지 단위 조회의 기본값.
     *
     * @see #query(String, int)
     */
    public ArrayNode query(String sql) {
        return query(sql, properties.getPageSize());
    }

    /**
     * ClickHouse SQL 을 실행하고 응답 봉투의 <b>{@code rows} 배열</b>을 돌려준다.
     *
     * <p>{@code sql} 은 반드시 {@link DhubSql} 을 거쳐 조립한 문자열이어야 한다 — 이 API 에는
     * 바인딩 파라미터가 없어 값이 문자열로 이어 붙는다(10-4).
     *
     * @param maxRows 서버가 돌려줄 최대 행 수. SQL 자체의 {@code LIMIT} 과 <b>같거나 크게</b> 준다.
     * @throws DhubApiException {@code rows} 가 배열이 아니거나, 응답이 잘렸거나({@code truncated}),
     *         HTTP 호출이 실패했을 때
     */
    public ArrayNode query(String sql, int maxRows) {
        return query(properties.getTableId(), sql, maxRows);
    }

    /**
     * {@link #query(String, int)} 와 같되 <b>데이터셋({@code source_id})을 호출부가 지정</b>한다 —
     * 민원({@code dhub.table-id}) 외의 원천(단속 {@code dhub.crackdown-table-id} 등)이 같은 전송 경로를
     * 타게 하기 위한 오버로드다(🔁 2026-09-17). 🔴 원천마다 클라이언트를
     * 새로 만들지 않는다 — {@code max_rows}·{@code truncated}·401 처리가 갈라진다.
     *
     * <p>🔴 {@code sourceId} 와 SQL 의 {@code FROM} 테이블은 <b>같은 데이터셋의 짝</b>이어야 한다
     * (어긋나면 서버가 422 로 거절한다).
     *
     * @throws IllegalStateException {@code sourceId} 가 비어 있을 때(호출 전)
     */
    public ArrayNode query(String sourceId, String sql, int maxRows) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalStateException(
                    "DHub 조회 대상 데이터셋(source_id)이 비어 있습니다 — dhub.*table-id 설정을 확인하세요.");
        }
        int cappedRows = DhubSql.limit(maxRows);
        JsonNode body = authClient.withToken(token -> execute(sourceId, sql, cappedRows, token));

        JsonNode rows = body.path("rows");
        if (!rows.isArray()) {
            // rows 가 없으면 스펙이 바뀐 것이다. 본문을 그대로 싣지 않고 노드 타입만 남긴다.
            throw new DhubApiException(
                    "DHub 조회 응답에 rows 배열이 없습니다(nodeType=" + rows.getNodeType() + ").");
        }
        if (body.path("truncated").asBoolean(false)) {
            // 조용히 넘기면 그 페이지의 뒷부분이 사라지고 keyset 커서가 그만큼 건너뛴다.
            throw new DhubApiException(
                    "DHub 조회 응답이 잘렸습니다(truncated=true, max_rows=" + cappedRows
                            + ", 수신=" + rows.size() + "). SQL 의 LIMIT 이 max_rows 보다 크지 않은지 "
                            + "확인하세요(프로퍼티: dhub.page-size · dhub.crackdown-page-size · dhub.pblpklt-page-size).");
        }
        return (ArrayNode) rows;
    }

    /**
     * {@code SELECT count(*) AS cnt ...} 처럼 <b>한 행·한 숫자</b>를 돌려주는 질의의 결과를 읽는다.
     * 응답에 총건수 메타가 없어 페이징 전에 규모를 알려면 이 방법뿐이다.
     */
    public long count(String sql) {
        ArrayNode rows = query(sql, 1);
        if (rows.isEmpty()) {
            throw new DhubApiException("DHub 건수 조회 응답이 빈 배열입니다.");
        }
        JsonNode first = rows.get(0);
        JsonNode cnt = first.path("cnt");
        if (cnt.isMissingNode() || !cnt.isNumber()) {
            // 별칭이 cnt 가 아니면 첫 번째 숫자 필드를 쓴다 — 호출부의 별칭 오타로 죽지 않게.
            for (JsonNode value : first) {
                if (value.isNumber()) {
                    return value.asLong();
                }
            }
            throw new DhubApiException("DHub 건수 조회 응답에 숫자 컬럼이 없습니다. 필드=" + first.fieldNames());
        }
        return cnt.asLong();
    }

    /** 누적 조회 요청 수. */
    public long requestCount() {
        return requestCount.get();
    }

    private JsonNode execute(String sourceId, String sql, int maxRows, String token) {
        requestCount.incrementAndGet();
        log.debug("DHub 조회 요청: sourceId={} maxRows={} sql={}", sourceId, maxRows, sql);

        String body;
        try {
            body = restClient.post()
                    .uri(QUERY_PATH)
                    // ⚠️ 헤더 값(토큰)은 로그·예외 어디에도 남기지 않는다.
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    // Map 으로 넘겨 Jackson 이 이스케이프하게 한다 — SQL 에 따옴표/개행이 있어도 안전하다.
                    .body(Map.of(
                            "kind", SOURCE_KIND,
                            "source_id", sourceId,
                            "query", sql,
                            "max_rows", maxRows))
                    .retrieve()
                    // 401 만 인증 예외로 바꾼다. 나머지 4xx/5xx 는 기본 핸들러가 던지고 아래에서 감싼다.
                    .onStatus(status -> status.value() == 401, (request, response) -> {
                        throw new DhubUnauthorizedException("DHub 조회가 401 로 거절됐습니다(토큰 만료/무효).");
                    })
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // getMessage() 에 응답 본문이 실린다 — 상태 코드만 옮기고 원인은 cause 로 전달한다.
            throw new DhubApiException(
                    "DHub 조회 실패(HTTP " + e.getStatusCode().value() + ").", e);
        } catch (RestClientException e) {
            throw new DhubApiException(
                    "DHub 조회 실패: " + e.getClass().getSimpleName(), e);
        }

        try {
            return MAPPER.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            // 응답이 잘렸거나 JSON 이 아님. 본문을 로그에 싣지 않는다(1MB 이고 개인정보가 섞일 수 있다).
            throw new DhubApiException("DHub 조회 응답 파싱 실패(길이=" + (body == null ? 0 : body.length()) + ").", e);
        }
    }
}
