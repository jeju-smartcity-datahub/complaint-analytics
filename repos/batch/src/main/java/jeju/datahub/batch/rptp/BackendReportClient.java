package jeju.datahub.batch.rptp;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * 백엔드 내부 API {@code POST /api/internal/reports/auto-crt} 호출 클라이언트
 * (TSK-4027 Step 8-1).
 *
 * <p>배치는 보고서를 스스로 만들지 않는다 — 집계·이름짓기·멱등은 전부 백엔드가 한다.
 * 이 클래스의 책임은 <b>한 건을 보내고, 돌아온 상태코드를 그대로 살려 돌려주는 것</b>뿐이다.
 *
 * <h2>🔴 상태코드를 삼키지 않는다 — Writer 가 직접 가른다</h2>
 * {@code RestClient} 의 기본 {@code onStatus} 는 4xx/5xx 를 예외로 바꿔 버린다. 그러면
 * <b>400(그 설정만의 확정 실패) · 401/403/503(전 설정 공통 실패) · 5xx(일시 실패)</b> 가
 * 한 덩어리가 되어 {@link ReportAutoCrtWriter} 의 5종 분기가 성립하지 않는다(계획서 D8).
 * 그래서 {@code onStatus(status -> true, (req, res) -> {})} 로 <b>기본 예외 변환을 통째로 끈다.</b>
 * 🔴 이 no-op 핸들러를 지우면 컴파일은 통과하고 <b>런타임에 정책만 조용히 무너진다</b>
 * (모든 실패가 {@code RestClientResponseException} 한 종류가 되어 Job 이 통째로 FAILED 된다).
 *
 * <h2>🔴 {@code BufferingClientHttpRequestFactory} — chunked 함정</h2>
 * 이 경로는 <b>POST + JSON 바디</b>다. CLAUDE.md 가 AI 어시스턴트 전송 경로에서 실증한 함정이
 * 그대로 적용된다.
 * <ul>
 *   <li>🔴 Spring 기본 팩토리({@code Simple…ClientHttpRequestFactory} — ⚠️ 이름을 줄여 적는다.
 *       "{@code Simple} 로 시작하는 그 팩토리가 이 파일에 0건" 인지를 grep 으로 지키기
 *       때문이다)는 바디를 <b>스트리밍</b>해
 *       {@code Transfer-Encoding: chunked} 로 보낸다 — 게이트웨이(istio-envoy)가
 *       <b>400 {@code Invalid HTTP request received.}</b> 로 거절한다.</li>
 *   <li>🔴 {@code JdkClientHttpRequestFactory} <b>단독도 안 된다</b> — 길이를 모른 채 publisher 를
 *       넘겨 결국 다시 chunked 다.</li>
 *   <li>✅ 고침은 <b>{@code BufferingClientHttpRequestFactory} 로 감싸는 것</b>이다. 바디를 먼저
 *       메모리에 담아 {@code Content-Length} 를 붙인다.</li>
 * </ul>
 * ⚠️ 이 부류는 <b>{@code MockRestServiceServer} 로 잡히지 않는다</b>(전송 계층을 타지 않는다).
 * 회귀는 실소켓 테스트나 E2E 가 진다.
 *
 * <p>응답은 {@code application/json} 이라 CLAUDE.md 의 <b>charset 함정은 없다</b>
 * (그건 {@code text/event-stream} 전용 문제였다 — {@code StringHttpMessageConverter} 의
 * UTF-8 특례가 {@code application/json} 에는 적용된다).
 *
 * <h2>🔒 응답 본문을 로그·예외에 싣지 않는다</h2>
 * 실패 로그에는 <b>상태코드와 {@code stngId} 만</b> 남긴다. 본문 원문이 이력 테이블이나 로그로
 * 흘러가면 그대로 유출 경로가 된다(Step 8-3 의 화이트리스트 사유와 같은 이유).
 *
 * @see BackendReportProperties
 * @see ReportAutoCrtWriter
 */
@Component
@EnableConfigurationProperties(BackendReportProperties.class)
public class BackendReportClient {

    private static final Logger log = LoggerFactory.getLogger(BackendReportClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 내부 API 경로. 🔴 {@code base-url} 끝에 {@code '/'} 가 있으면 {@code //api/...} 가 된다(프로퍼티가 정규화한다). */
    static final String AUTO_CRT_PATH = "/api/internal/reports/auto-crt";

    /** 내부 API 인증 헤더({@code InternalApiKeyFilter} — Step 2). 세션이 아니라 이 헤더로 인증한다. */
    static final String API_KEY_HEADER = "X-API-Key";

    private final RestClient restClient;
    private final BackendReportProperties properties;

    /**
     * 백엔드 호출 1건의 결과 — <b>상태코드를 그대로 들고 온다</b>.
     *
     * <p>🔴 성공/실패 판정을 여기서 내리지 않는다. 정책 분기는 {@link ReportAutoCrtWriter} 한 곳이다.
     *
     * @param status    HTTP 상태코드 원본(201·200·400·401·403·503·5xx …)
     * @param rptpId    생성(또는 기존) 보고서 ID. 성공 응답에서만 채워진다
     * @param excnId    분석실행 ID. 성공 응답에서만 채워진다
     * @param rptpNm    백엔드가 지은 보고서명. 성공 응답에서만 채워진다
     * @param trgtPrdCd 대상기간 코드(요청값을 되비춘 값)
     * @param created   백엔드가 보고서를 <b>새로 만들었는지</b>. 🔴 상태코드(201/200)와 같은 사실을
     *                  본문으로도 전달하는 값이라 <b>교차검증용</b>으로만 쓴다(판정은 상태코드가 진다)
     */
    public record Result(
            int status,
            Long rptpId,
            Long excnId,
            String rptpNm,
            String trgtPrdCd,
            Boolean created) {
    }

    @Autowired
    public BackendReportClient(BackendReportProperties properties) {
        this(properties, RestClient.builder());
    }

    /**
     * 테스트용 — {@code MockRestServiceServer} 가 바인딩할 빌더를 주입받는다.
     *
     * <p>⚠️ 생성자가 둘이라 {@link Autowired} 로 주입 대상을 못 박았다({@code DhubAuthClient} 와 같은 이유).
     */
    BackendReportClient(BackendReportProperties properties, RestClient.Builder builder) {
        this.properties = properties;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory jdkFactory = new JdkClientHttpRequestFactory(httpClient);
        // ⚠️ 읽기 타임아웃은 HttpClient 가 아니라 팩토리가 건다(요청 단위 timeout 으로 내려간다).
        //    120초인 이유는 백엔드가 집계를 동기로 돌리기 때문이다(BackendReportProperties 참조).
        jdkFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                // 🔴 반드시 Buffering 으로 감싼다 — 단독이면 다시 chunked 다(클래스 javadoc).
                .requestFactory(new BufferingClientHttpRequestFactory(jdkFactory))
                .build();
    }

    /**
     * 보고서 자동 생성 1건을 요청한다.
     *
     * <p>🔴 <b>어떤 상태코드도 예외로 바꾸지 않는다</b> — 201·200·400·401·403·503·5xx 가 모두
     * {@link Result} 로 돌아온다. 정책 분기는 {@link ReportAutoCrtWriter} 가 한다.
     *
     * @param command Processor 가 만든 요청 1건. 그대로 JSON 바디가 된다(필드명 = 백엔드
     *                {@code ReportAutoCrtRequest} 의 필드명)
     * @return 상태코드와(성공이면) 본문을 담은 결과
     * @throws ResourceAccessException 연결 실패·읽기 타임아웃 등 <b>응답을 받지 못한</b> 경우.
     *         🔴 <b>일시 실패</b>라 Writer 가 사유를 굳히지 않고 스킵한다(계획서 D8-②)
     */
    public Result autoCreate(ReportAutoCrtCommand command) {
        requireBaseUrl();

        RestClient.RequestBodySpec spec = restClient.post()
                .uri(AUTO_CRT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        // 🔴 키가 없으면 헤더 자체를 넣지 않는다 — 빈 값을 보내면 로컬(app.internal-api.enabled=false)
        //    에서도 "틀린 키" 로 보일 수 있다. 키 유무 판정은 프로퍼티 한 곳이다.
        if (properties.hasApiKey()) {
            spec = spec.header(API_KEY_HEADER, properties.apiKey());
        }

        ResponseEntity<String> response = spec
                .body(command)
                .retrieve()
                // 🔴 기본 4xx/5xx 예외 변환을 끈다 — 상태코드를 살려 Writer 에게 넘기기 위함이다.
                .onStatus(status -> true, (request, res) -> { })
                .toEntity(String.class);

        int status = response.getStatusCode().value();
        if (!response.getStatusCode().is2xxSuccessful()) {
            // 🔒 본문을 찍지 않는다 — 상태코드와 식별자만 남긴다.
            log.warn("보고서 자동 생성 요청 실패: status={} stngId={} trgtPrdCd={}",
                    status, command.stngId(), command.trgtPrdCd());
            return new Result(status, null, null, null, null, null);
        }
        return parseBody(status, response.getBody(), command);
    }

    /**
     * 🔴 호스트가 비었는지 <b>Job 실행 시점</b>에 확인한다 — 기동 시점이 아니다.
     *
     * <p>{@code prod} 는 {@code base-url} 기본값이 빈 문자열이다(환경변수 주입). 기동을 죽이면
     * 헬스체크·수동 트리거 엔드포인트까지 함께 죽어 원인 파악이 어려워지므로, 검증은 실제로
     * 부르는 이 지점에서 <b>명확한 예외</b>로 한다({@code rule_batch-project.md} '환경변수' 절 —
     * {@code DhubProperties#requireCredentials()} 와 같은 관례).
     *
     * <p>⚠️ 이 예외는 {@link ReportAutoCrtSkippableException} 이 <b>아니다</b> — 설정이 비면
     * 전 건이 같은 이유로 실패하므로 Job 을 끊는다.
     */
    private void requireBaseUrl() {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "백엔드 내부 API 호스트(batch.rptp.backend.base-url)가 비어 있습니다 —"
                            + " BACKEND_BASE_URL 환경변수를 확인하세요.");
        }
    }

    /**
     * 성공 응답 본문을 읽는다. 본문이 비었거나 형식이 어긋나도 <b>상태코드는 지킨다</b> —
     * 본문 파싱 실패로 201 을 실패로 뒤집으면 이미 만들어진 보고서가 "안 만들어진 것" 이 된다.
     */
    private Result parseBody(int status, String body, ReportAutoCrtCommand command) {
        if (body == null || body.isBlank()) {
            log.warn("보고서 자동 생성 응답 본문이 비어 있습니다. status={} stngId={}",
                    status, command.stngId());
            return new Result(status, null, null, null, null, null);
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            return new Result(
                    status,
                    node.path("rptpId").isNumber() ? node.path("rptpId").asLong() : null,
                    node.path("excnId").isNumber() ? node.path("excnId").asLong() : null,
                    node.path("rptpNm").isTextual() ? node.path("rptpNm").asText() : null,
                    node.path("trgtPrdCd").isTextual() ? node.path("trgtPrdCd").asText() : null,
                    node.path("created").isBoolean() ? node.path("created").asBoolean() : null);
        } catch (JsonProcessingException e) {
            // 🔒 파싱 실패 메시지에 본문을 싣지 않는다(예외 메시지에 본문 일부가 섞이므로 타입만 남긴다).
            log.warn("보고서 자동 생성 응답 본문을 해석하지 못했습니다. status={} stngId={} error={}",
                    status, command.stngId(), e.getClass().getSimpleName());
            return new Result(status, null, null, null, null, null);
        }
    }
}
