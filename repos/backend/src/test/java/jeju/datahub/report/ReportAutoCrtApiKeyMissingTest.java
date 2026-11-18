package jeju.datahub.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 케이스 13 — <b>키 검증 ON + 키 미설정 → 503 (fail-closed)</b>.
 *
 * <h2>🔴 왜 또 하나의 클래스인가</h2>
 *
 * <p>케이스 12 와 <b>프로퍼티가 달라</b>({@code app.internal-api.key} 가 빈 값이다) 같은 클래스에
 * 담을 수 없다 — {@code @SpringBootTest(properties=…)} 는 클래스 단위다.
 *
 * <h2>🔴 503 이지 401 이 아니다</h2>
 *
 * <p>{@code enabled=true} 인데 키가 없으면 <b>서버 설정이 잘못된 것</b>이지 호출자가 틀린 것이 아니다.
 * 401 로 내면 배치가 "키를 잘못 보냈나" 를 뒤지게 되고, 무인증으로 열어 두면 보고서를 무제한 생성할
 * 수 있는 쓰기 경로가 그대로 뚫린다. 그래서 <b>전부 막고 503</b> 으로 드러낸다 —
 * {@code INTERNAL_API_KEY} 를 안 넣은 배포가 조용히 성공하지 않게 하는 장치다.
 *
 * <p>🔴 <b>올바른 키란 것이 없다</b> — 서버에 키가 없으므로 어떤 헤더를 실어도 503 이다.
 * 그 점을 함께 단언한다(401 로 갈리면 fail-closed 가 아니다).
 *
 * <p>🔴 DB 를 건드리지 않는다 — 요청이 필터에서 끊긴다. 착수 상태 유지를 행수로 못박는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "app.auth.security-enabled=true",
                       "app.internal-api.enabled=true",
                       "app.internal-api.key=" })
class ReportAutoCrtApiKeyMissingTest {

    private static final String AUTO_CRT_URL = "/api/internal/reports/auto-crt";
    private static final String HEADER = "X-API-Key";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private long[] baseline;

    @BeforeEach
    void captureBaseline() {
        baseline = tableCounts();
    }

    @AfterEach
    void assertNothingChanged() {
        assertThat(tableCounts())
                .as("fail-closed 경로는 DB 를 건드리지 않는다 (보고서·실행·로그인이력)")
                .containsExactly(baseline[0], baseline[1], baseline[2]);
    }

    @Test
    @DisplayName("13. 키 검증 ON + 서버에 키 미설정 — 503 (fail-closed · 어떤 헤더를 실어도 같다)")
    void case13_unconfiguredKeyIs503() {
        assertThat(post(null))
                .as("무인증으로 열리는 것보다 전부 막는 쪽이 안전하다 — 503")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(post("any-key"))
                .as("서버에 키가 없으니 '맞는 키' 가 존재하지 않는다 — 401 로 갈리면 fail-closed 가 아니다")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private HttpStatus post(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.add(HEADER, apiKey);
        }
        ResponseEntity<String> res = rest.postForEntity(
                AUTO_CRT_URL, new HttpEntity<>("{}", headers), String.class);
        return HttpStatus.valueOf(res.getStatusCode().value());
    }

    private long[] tableCounts() {
        return new long[] {
                count("parking.tb_rptp"),
                count("parking.tb_anls_excn"),
                count("parking.tb_lgn_hstry") };
    }

    private long count(String table) {
        Long c = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return c == null ? 0L : c;
    }
}
