package jeju.datahub.batch.zonenm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
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

import jeju.datahub.batch.rptp.BackendReportProperties;

/**
 * {@link BackendZoneNmClient} — 상태코드를 삼키지 않고 그대로 올리는지(200·400·401·503·5xx).
 *
 * <p>⚠️ {@code MockRestServiceServer} 는 전송 계층을 타지 않아 chunked 함정은 여기서 못 잡는다
 * (rptp {@code BackendReportTransportTest} 가 같은 팩토리 구성의 회귀를 진다).
 */
class BackendZoneNmClientTest {

    private static final String BASE_URL = "http://backend.test";
    private static final String API_KEY = "TEST-ONLY-NOT-A-REAL-KEY";
    private static final String URL = BASE_URL + BackendZoneNmClient.PREFILL_PATH + "?gridSz=500&limit=100";

    private MockRestServiceServer server;

    private BackendZoneNmClient client(String apiKey) {
        RestClient.Builder delegate = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(delegate).build();
        BackendReportProperties properties = new BackendReportProperties(BASE_URL, apiKey, 1_000, 2_000);
        return new BackendZoneNmClient(properties, ignoringRequestFactory(delegate));
    }

    @Test
    @DisplayName("200 이면 6칸이 그대로 올라오고 X-API-Key·쿼리 파라미터가 실린다")
    void parses200() {
        BackendZoneNmClient c = client(API_KEY);
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", API_KEY))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"gridSz":500,"targetCnt":1191,"resolvedCnt":97,"transientCnt":3,
                                 "remainingCnt":965,"tripped":false}"""));

        BackendZoneNmClient.Result r = c.prefill(500, 100);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(new BackendZoneNmClient.PrefillResponse(500, 1191, 97, 3, 965, false));
        server.verify();
    }

    @Test
    @DisplayName("200 이어도 필드가 빠지면 body=null — 반쯤 읽은 값으로 진척을 판정하지 않는다")
    void missingFieldYieldsNullBody() {
        BackendZoneNmClient c = client(API_KEY);
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"gridSz\":500,\"resolvedCnt\":1}"));

        BackendZoneNmClient.Result r = c.prefill(500, 100);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isNull();
    }

    @Test
    @DisplayName("키가 비면 X-API-Key 헤더 자체를 보내지 않는다")
    void noHeaderWithoutKey() {
        BackendZoneNmClient c = client("");
        server.expect(requestTo(URL))
                .andExpect(headerDoesNotExist("X-API-Key"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"gridSz":500,"targetCnt":1,"resolvedCnt":1,"transientCnt":0,
                                 "remainingCnt":0,"tripped":false}"""));

        assertThat(c.prefill(500, 100).status()).isEqualTo(200);
        server.verify();
    }

    @Test
    @DisplayName("🔴 400·401·503·500·502 는 예외로 바뀌지 않고 status 가 그대로 올라온다")
    void passesThroughErrorStatuses() {
        for (HttpStatus s : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED,
                HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY}) {
            BackendZoneNmClient c = client(API_KEY);
            server.expect(requestTo(URL)).andRespond(withStatus(s).body("{\"message\":\"x\"}"));

            BackendZoneNmClient.Result r = c.prefill(500, 100);

            assertThat(r.status()).as("status %s", s).isEqualTo(s.value());
            assertThat(r.body()).isNull();
            server.verify();
        }
    }

    @Test
    @DisplayName("base-url 이 비면 IllegalStateException — Job FAILED 갈래")
    void blankBaseUrlFails() {
        RestClient.Builder delegate = RestClient.builder();
        BackendZoneNmClient c = new BackendZoneNmClient(
                new BackendReportProperties("", API_KEY, 1_000, 2_000), ignoringRequestFactory(delegate));

        assertThatThrownBy(() -> c.prefill(500, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    /**
     * {@code requestFactory(...)} 호출만 무시하는 빌더 프록시 — 클라이언트가 Buffering 팩토리를 끼우면
     * {@code MockRestServiceServer} 가 바인딩한 팩토리가 덮여 목 서버가 우회된다(rptp 테스트와 같은 장치).
     */
    private static RestClient.Builder ignoringRequestFactory(RestClient.Builder delegate) {
        return (RestClient.Builder) Proxy.newProxyInstance(
                RestClient.Builder.class.getClassLoader(),
                new Class<?>[] {RestClient.Builder.class},
                (proxy, m, args) -> {
                    if ("requestFactory".equals(m.getName())) {
                        return proxy;
                    }
                    try {
                        Object result = m.invoke(delegate, args);
                        return result == delegate ? proxy : result;
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }
}
