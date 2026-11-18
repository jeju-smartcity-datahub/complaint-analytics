package jeju.datahub.batch.rptp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link BackendReportClient} 의 <b>응답 5종 분기</b> 검증 (케이스 1~6).
 *
 * <h2>이 클래스가 지키는 계약 — "상태코드를 삼키지 않는다"</h2>
 * {@code RestClient} 의 기본 {@code onStatus} 는 4xx/5xx 를 예외로 바꾼다. 그러면
 * <b>400(그 설정만의 확정 실패) · 401/503(전 설정 공통 실패) · 500(일시 실패)</b> 가 한 덩어리가 되어
 * {@link ReportAutoCrtWriter} 의 분기가 성립하지 않는다. 그래서 이 클래스는 <b>다섯 상태코드 모두
 * 예외 없이 {@link BackendReportClient.Result#status()} 로 돌아오는지</b>만 본다 —
 * "그래서 Job 이 어떻게 되는가"는 {@link ReportAutoCrtJobTest} 가 진다.
 *
 * <h2>🔴 {@code MockRestServiceServer} 가 못 잡는 것</h2>
 * 이 테스트는 <b>전송 계층을 타지 않는다</b> — 실제 소켓도 실제 헤더도 없다. 그래서
 * {@code Transfer-Encoding: chunked} 함정(케이스 10)은 원리적으로 여기서 잡히지 않는다.
 * 그 회귀는 {@link BackendReportTransportTest} 가 실소켓으로 진다.
 *
 * <h2>🔴 {@code requestFactory} 를 삼키는 프록시가 필요한 이유</h2>
 * {@code MockRestServiceServer.bindTo(builder).build()} 는 빌더에 <b>자기 요청 팩토리를 심는다</b>.
 * 그런데 {@link BackendReportClient} 의 생성자는 그 뒤에 자기 팩토리
 * ({@code BufferingClientHttpRequestFactory})를 다시 심어 <b>목을 덮어써</b> 실제 소켓으로 나간다.
 * 그래서 {@link #ignoringRequestFactory(RestClient.Builder)} 로 <b>{@code requestFactory(..)} 호출만</b>
 * 무시하는 얇은 프록시를 끼운다 — 운영 코드를 테스트 때문에 고치지 않기 위한 장치다.
 */
class BackendReportClientTest {

    private static final String BASE_URL = "http://backend.test";

    private static final String API_KEY = "TEST-ONLY-NOT-A-REAL-KEY";

    private static final String URL = BASE_URL + BackendReportClient.AUTO_CRT_PATH;

    private static final ReportAutoCrtCommand COMMAND = new ReportAutoCrtCommand(
            7001L, "CVLCPT", "2026-04", "20260401", "20260430",
            "제주시", "50110101", "ALL", "MONTH");

    private MockRestServiceServer server;

    /** 목 서버에 물린 클라이언트. API 키는 기본으로 채워 둔다(헤더 단언이 함께 돌게). */
    private BackendReportClient client(String apiKey) {
        RestClient.Builder delegate = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(delegate).build();
        BackendReportProperties properties =
                new BackendReportProperties(BASE_URL, apiKey, 1_000, 2_000);
        return new BackendReportClient(properties, ignoringRequestFactory(delegate));
    }

    private BackendReportClient client() {
        return client(API_KEY);
    }

    // ------------------------------------------------------------------ 케이스 1

    @Test
    @DisplayName("1. 201 이면 status=201 과 본문 4칸(rptpId·excnId·rptpNm·trgtPrdCd)이 그대로 올라온다")
    void parses201Created() {
        BackendReportClient c = client();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.stngId").value(7001))
                .andExpect(jsonPath("$.trgtPrdCd").value("2026-04"))
                .andExpect(jsonPath("$.bgngYmd").value("20260401"))
                .andExpect(jsonPath("$.endYmd").value("20260430"))
                .andExpect(jsonPath("$.crltnCyclCd").value("MONTH"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"rptpId":901,"excnId":401,"rptpNm":"2026년 4월 민원 분석 보고서",
                                 "trgtPrdCd":"2026-04","created":true}"""));

        BackendReportClient.Result result = c.autoCreate(COMMAND);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.rptpId()).isEqualTo(901L);
        assertThat(result.excnId()).isEqualTo(401L);
        assertThat(result.rptpNm()).isEqualTo("2026년 4월 민원 분석 보고서");
        assertThat(result.trgtPrdCd()).isEqualTo("2026-04");
        assertThat(result.created()).isTrue();
        server.verify();
    }

    // ------------------------------------------------------------------ 케이스 2

    @Test
    @DisplayName("2. 200 created=false 는 예외가 아니다 — '이미 있다'는 정상 경로다")
    void parses200AlreadyExists() {
        BackendReportClient c = client();
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"rptpId":901,"excnId":null,"rptpNm":"2026년 4월 민원 분석 보고서",
                                 "trgtPrdCd":"2026-04","created":false}"""));

        BackendReportClient.Result result = c.autoCreate(COMMAND);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.created()).isFalse();
        assertThat(result.rptpId()).isEqualTo(901L);
        // excnId 가 null 인 것도 계약이다 — 집계를 새로 돌리지 않았다는 뜻이다.
        assertThat(result.excnId()).isNull();
        server.verify();
    }

    // ------------------------------------------------------------------ 케이스 3

    @Test
    @DisplayName("3. 🔴 400 은 예외로 바뀌지 않는다 — status=400 이 그대로 올라온다(그 설정만의 확정 실패)")
    void passesThrough400() {
        BackendReportClient c = client();
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"RPTP_TYPE_UNSUPPORTED","message":"지원하지 않는 보고서 유형"}"""));

        BackendReportClient.Result result = c.autoCreate(COMMAND);

        assertThat(result.status()).isEqualTo(400);
        // 🔴 본문은 파싱하지 않는다 — 2xx 가 아니면 네 칸 모두 null 이다.
        assertThat(result.rptpId()).isNull();
        assertThat(result.excnId()).isNull();
        assertThat(result.rptpNm()).isNull();
        assertThat(result.created()).isNull();
        server.verify();
    }

    // ------------------------------------------------------------------ 케이스 4

    @Test
    @DisplayName("4. 🔴 500 도 예외가 아니다 — status=500 이 올라와야 Writer 가 '일시 실패'로 가른다")
    void passesThrough500() {
        BackendReportClient c = client();
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        BackendReportClient.Result result = c.autoCreate(COMMAND);

        assertThat(result.status()).isEqualTo(500);
        assertThat(result.rptpId()).isNull();
        server.verify();
    }

    // ------------------------------------------------------------------ 케이스 5

    @Test
    @DisplayName("5. 🔴 401 도 예외가 아니다 — status=401 이 올라와야 Writer 가 Job 을 끊는다")
    void passesThrough401() {
        BackendReportClient c = client();
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(""));

        BackendReportClient.Result result = c.autoCreate(COMMAND);

        assertThat(result.status()).isEqualTo(401);
        server.verify();
    }

    // ------------------------------------------------------------------ 케이스 6

    @Test
    @DisplayName("6. 🔴 503 도 예외가 아니다 — status=503 이 올라와야 Writer 가 Job 을 끊는다")
    void passesThrough503() {
        BackendReportClient c = client();
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(""));

        BackendReportClient.Result result = c.autoCreate(COMMAND);

        assertThat(result.status()).isEqualTo(503);
        server.verify();
    }

    // ------------------------------------------------------------------ 곁가지 계약

    @Test
    @DisplayName("2xx 인데 본문이 비어 있어도 죽지 않는다 — status 만 살고 나머지는 null")
    void survivesEmptyBody() {
        BackendReportClient c = client();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CREATED).body(""));

        BackendReportClient.Result result = c.autoCreate(COMMAND);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.created()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("2xx 인데 본문이 JSON 이 아니어도 죽지 않는다 — 해석 실패는 로그로만 남는다")
    void survivesNonJsonBody() {
        BackendReportClient c = client();
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.CREATED).body("<html>502 Bad Gateway</html>"));

        BackendReportClient.Result result = c.autoCreate(COMMAND);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.rptpId()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("🔴 API 키가 비면 X-API-Key 헤더 자체를 보내지 않는다 — 빈 값으로 보내면 필터가 403 이다")
    void omitsApiKeyHeaderWhenBlank() {
        BackendReportClient c = client("   ");
        server.expect(requestTo(URL))
                .andExpect(headerDoesNotExist(BackendReportClient.API_KEY_HEADER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"rptpId\":1,\"created\":true}"));

        assertThat(c.autoCreate(COMMAND).status()).isEqualTo(201);
        server.verify();
    }

    @Test
    @DisplayName("🔴 base-url 이 비면 소켓을 열기 전에 원인이 보이는 예외로 끊는다")
    void failsFastWhenBaseUrlMissing() {
        BackendReportProperties blank = new BackendReportProperties("  ", API_KEY, 1_000, 2_000);

        assertThatThrownBy(() -> new BackendReportClient(blank, RestClient.builder()).autoCreate(COMMAND))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BACKEND_BASE_URL");
    }

    // ------------------------------------------------------------------ 헬퍼

    /**
     * {@code requestFactory(..)} 호출만 무시하고 나머지는 그대로 넘기는 {@link RestClient.Builder} 프록시.
     *
     * <p>🔴 이것이 없으면 {@link BackendReportClient} 생성자가 목 팩토리를 덮어써
     * <b>{@value #BASE_URL} 로 실제 DNS 조회·소켓 연결</b>이 일어난다(테스트가 네트워크에 의존한다).
     * 체이닝을 지키려고 위임 대상이 자기 자신을 돌려준 경우 프록시로 바꿔 돌려준다.
     */
    private static RestClient.Builder ignoringRequestFactory(RestClient.Builder delegate) {
        return (RestClient.Builder) Proxy.newProxyInstance(
                RestClient.Builder.class.getClassLoader(),
                new Class<?>[] {RestClient.Builder.class},
                (proxy, method, args) -> {
                    if ("requestFactory".equals(method.getName())) {
                        return proxy;
                    }
                    try {
                        Object result = method.invoke(delegate, args);
                        return result == delegate ? proxy : result;
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }
}
