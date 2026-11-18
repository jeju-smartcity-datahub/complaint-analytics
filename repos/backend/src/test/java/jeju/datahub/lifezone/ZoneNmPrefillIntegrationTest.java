package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 구역명 캐시 미리 채우기 내부 API 통합 테스트. (TSK-4339 Step 7 — {@code POST /api/internal/zone-names/prefill})
 *
 * <p>다른 생활권 통합 테스트와 같은 구성이다 — {@code RANDOM_PORT} + {@link TestRestTemplate} 로 실제 HTTP 를 태우고
 * 로컬 PostgreSQL 적재분을 그대로 쓴다. 로그인·{@code X-API-Key} 없이 부른다 — test 프로파일은
 * {@code app.auth.security-enabled=false} 라 {@code SecurityConfig} 체인이 통째로 빠지고 {@code InternalApiKeyFilter} 도
 * 함께 사라진다({@code ReportAutoCrtIntegrationTest} 와 같은 근거).
 *
 * <h2>🔴 VWorld 키 유무로 분기한다</h2>
 * 200 케이스({@code limit=1})는 외부를 실제로 부를 수 있다.
 * <ul>
 *   <li><b>키 없음</b> — 장소 검색·역지오코딩이 일시 실패로 끝나 캐시에 굳히지 않는다 →
 *       {@code resolvedCnt = 0} · 캐시 행 수 불변.</li>
 *   <li><b>키 있음</b> — 실호출 1건이 실 캐시 행을 만들 수 있다. 실데이터라 무해하므로 <b>지우지 않는다</b>
 *       (응답에 저장된 키가 없어 테스트가 만든 행을 특정할 수도 없다). 증가가 1 이하인지만 본다.</li>
 * </ul>
 * 이 클래스는 캐시에 테스트 이름을 심지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("구역명 캐시 미리 채우기 내부 API (POST /api/internal/zone-names/prefill)")
class ZoneNmPrefillIntegrationTest {

    private static final String PREFILL_URL = "/api/internal/zone-names/prefill";

    /** 대상이 가장 적은 격자 — 200 케이스가 쓴다(어느 크기든 계약은 같다). */
    private static final int GRID_SZ = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** VWorld 키 — 비었으면 외부 조회가 전부 일시 실패다(캐시에 굳히지 않는다). */
    @Value("${analysis.zone-name.vworld.api-key:}")
    private String vworldApiKey;

    @Test
    @DisplayName("400 — 목록 밖 gridSz(300) · limit=0 · limit=201")
    void rejectsOutOfRangeParams() {
        assertThat(post("gridSz=300&limit=1").getStatusCode().value()).as("gridSz=300").isEqualTo(400);
        assertThat(post("gridSz=" + GRID_SZ + "&limit=0").getStatusCode().value()).as("limit=0").isEqualTo(400);
        assertThat(post("gridSz=" + GRID_SZ + "&limit=201").getStatusCode().value()).as("limit=201").isEqualTo(400);
    }

    @Test
    @DisplayName("200 — limit=1 응답 6필드 · targetCnt ≥ remainingCnt · 처리 ≤ 1 · 키 없으면 캐시 불변 / 있으면 증가 ≤ 1")
    void prefillOneReturnsContractFields() throws IOException {
        long before = cacheRowCount();

        ResponseEntity<String> response = post("gridSz=" + GRID_SZ + "&limit=1");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getBody());

        long after = cacheRowCount();

        for (String field : new String[] { "gridSz", "targetCnt", "resolvedCnt", "transientCnt", "remainingCnt", "tripped" }) {
            assertThat(body.has(field)).as("응답 필드 %s", field).isTrue();
        }
        assertThat(body.get("gridSz").asInt()).isEqualTo(GRID_SZ);
        assertThat(body.get("tripped").isBoolean()).isTrue();
        int targetCnt = body.get("targetCnt").asInt();
        int resolvedCnt = body.get("resolvedCnt").asInt();
        int transientCnt = body.get("transientCnt").asInt();
        int remainingCnt = body.get("remainingCnt").asInt();
        assertThat(targetCnt).as("대상 격자 수").isPositive();
        assertThat(remainingCnt).as("남은 수는 대상 수를 넘지 않는다").isBetween(0, targetCnt);
        assertThat(resolvedCnt).isNotNegative();
        assertThat(transientCnt).isNotNegative();
        assertThat(resolvedCnt + transientCnt).as("limit=1 — 한 호출이 처리하는 격자는 1개 이하").isLessThanOrEqualTo(1);

        if (vworldApiKey == null || vworldApiKey.isBlank()) {
            // 키 없음 — 외부 조회가 일시 실패로 끝나 캐시에 굳히지 않는다(남은 대상이 있었다면 transient 1)
            assertThat(resolvedCnt).as("키 없음 — 저장 0").isZero();
            assertThat(after).as("키 없음 — 캐시 행 수 불변").isEqualTo(before);
        } else {
            // 키 있음 — 실호출 1건이 실 캐시 행을 만들 수 있다(실데이터 · 지우지 않는다)
            assertThat(after - before).as("키 있음 — 캐시 증가는 1 이하").isBetween(0L, 1L);
        }
    }

    private ResponseEntity<String> post(String query) {
        return restTemplate.postForEntity(PREFILL_URL + "?" + query, null, String.class);
    }

    private long cacheRowCount() {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_zone_nm_cache", Long.class);
        return n == null ? 0 : n;
    }
}
