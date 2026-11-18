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
 * 케이스 12 — <b>키 검증 ON + 헤더 없음 → 401</b> (TSK-4027 Step 6 · D12).
 *
 * <h2>🔴 왜 별도 클래스인가</h2>
 *
 * <p>{@code @SpringBootTest(properties=…)} 는 <b>클래스 단위</b>다. 그리고
 * {@code application-test.yml} 은 {@code app.auth.security-enabled: false} 라
 * {@code SecurityConfig.filterChain()} <b>빈 자체가 빠지고</b>
 * ({@code @ConditionalOnProperty(havingValue="true")}), {@code addFilterBefore} 로 거는
 * {@code InternalApiKeyFilter} 도 함께 사라진다 — 기본 체인에서는 이 케이스가 <b>성립하지 않는다.</b>
 * 그래서 이 클래스만 <b>운영 필터체인을 켠다</b>({@code AuthSecurityIntegrationTest} 와 같은 방식).
 *
 * <h2>🔴 이 케이스가 지키는 세 가지</h2>
 *
 * <ol>
 *   <li><b>401</b> — 키가 설정돼 있는데 헤더가 없거나 틀리면 막는다.</li>
 *   <li><b>permitAll 이 먼저 잡힌다</b> — {@code /api/internal/**} permitAll 이
 *       {@code /api/**} authenticated <b>뒤</b>에 있으면 세션 인증이 먼저 걸려
 *       <b>같은 401</b> 이 나온다. 그래서 <b>올바른 키를 실은 호출이 400(바디 검증)까지
 *       도달</b>하는 것을 함께 단언한다 — 401 이 필터에서 나온 것임을 이 대조가 증명한다.</li>
 *   <li><b>CSRF 면제</b> — {@code ignoringRequestMatchers("/api/internal/**")} 가 없으면
 *       그 정상 호출이 400 이 아니라 <b>403</b> 이다(permitAll 은 CSRF 면제가 아니다).</li>
 * </ol>
 *
 * <p>🔴 <b>DB 를 건드리지 않는다.</b> 401 은 필터에서 끊기고, 대조용 정상 호출도 <b>빈 바디</b>라
 * {@code @Valid} 에서 400 으로 끊긴다 — 보고서·실행·설정 어느 표에도 행이 생기지 않는다.
 * 세션도 만들지 않으므로 {@code tb_lgn_hstry} 도 늘지 않는다. 그래도 착수 상태가 유지되는지
 * {@link #captureBaseline()} / {@link #assertNothingChanged()} 가 행수로 못박는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "app.auth.security-enabled=true",
                       "app.internal-api.enabled=true",
                       "app.internal-api.key=test-key" })
class ReportAutoCrtApiKeyRequiredTest {

    private static final String AUTO_CRT_URL = "/api/internal/reports/auto-crt";
    private static final String HEADER = "X-API-Key";
    private static final String VALID_KEY = "test-key";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private long[] baseline;

    @BeforeEach
    void captureBaseline() {
        baseline = tableCounts();
    }

    /** 🔴 이 클래스는 쓰기를 한 줄도 남기지 않는다 — 정리할 것이 없다는 것을 단언으로 증명한다. */
    @AfterEach
    void assertNothingChanged() {
        assertThat(tableCounts())
                .as("인증 실패 경로는 DB 를 건드리지 않는다 (보고서·실행·로그인이력)")
                .containsExactly(baseline[0], baseline[1], baseline[2]);
    }

    @Test
    @DisplayName("12. 키 검증 ON + X-API-Key 헤더 없음 — 401 (키를 실으면 400 까지 도달한다: permitAll·CSRF 면제 대조)")
    void case12_missingApiKeyIs401() {
        assertThat(post(null))
                .as("헤더가 없으면 401 이다 — 무인증 쓰기 경로를 열어 두지 않는다 (D12)")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(post("wrong-key"))
                .as("틀린 키도 같은 401 이다 (비교는 MessageDigest.isEqual)")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // 🔴 대조군 — 올바른 키면 필터를 통과해 @Valid 의 400 까지 간다.
        //    401 이면 permitAll 순서가 틀린 것이고(세션 인증이 먼저 잡혔다),
        //    403 이면 CSRF 면제가 빠진 것이다(permitAll 은 CSRF 면제가 아니다).
        assertThat(post(VALID_KEY))
                .as("올바른 키 + 빈 바디 → 400. 401 이면 permitAll 순서, 403 이면 CSRF 면제가 빠졌다")
                .isEqualTo(HttpStatus.BAD_REQUEST);
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
