package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.analysis.service.HotspotHeatmapPointVO;
import jeju.datahub.analysis.service.HotspotHeatmapResultVO;
import jeju.datahub.analysis.service.HotspotRankingResultVO;
import jeju.datahub.analysis.service.HotspotRankingZoneVO;

/**
 * 분석레이어 히트맵 API 통합 테스트. ({@code GET /api/hotspots/heatmap})
 *
 * <p>전략은 {@link HotspotRankingIntegrationTest} 와 같다 — {@code RANDOM_PORT} + {@link TestRestTemplate},
 * {@code test} 프로파일이 보안 체인을 꺼서 인증 없이 호출된다. DB 를 바꾸지 않으므로 복원이 필요 없다.
 *
 * <h2>🔴 날짜·기대값을 상수로 박지 않는다</h2>
 * <ul>
 *   <li>조회 구간은 {@link #resolveDataRanges()} 가 <b>DB 의 최대일 기준 상대 구간</b>(최근 30일)으로 읽는다.
 *       민원·단속은 적재 구간이 달라 원천별로 따로 잡는다.</li>
 *   <li>🔴 구간은 <b>반드시 넘긴다</b> — 비우면 서비스가 실제 오늘 기준 {@code YESTERDAY} 로 폴백해
 *       0건이 되고 불변식이 실행되지 않은 채 초록불이 된다. 그래서 모든 케이스가 비어 있지 않음을 먼저 단언한다.</li>
 *   <li>기대값은 리터럴이 아니라 <b>불변식</b>이다 — 히트맵 합계 == 같은 조건 순위 API 합계(매퍼 WHERE 가 같은 식).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HotspotHeatmapIntegrationTest {

    private static final String HEATMAP_URL = "/api/hotspots/heatmap";
    private static final String RANKING_URL = "/api/hotspots/ranking";

    /** 제주시 시군구 코드 — emd_cd 앞 5자리. */
    private static final String SGG_JEJU = "50110";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 민원 조회 구간 [시작, 종료] (yyyy-MM-dd) — 민원 최대 발생일 기준 최근 30일. */
    private String[] cvlcptRange;

    /** 단속 조회 구간 [시작, 종료] (yyyy-MM-dd) — 단속 최대 단속일 기준 최근 30일. */
    private String[] crdnRange;

    /** 조회 구간을 DB 에서 읽는다. 매퍼와 같은 조건(민원: 유형 99 제외 · 둘 다 emd_cd NOT NULL). */
    @BeforeEach
    void resolveDataRanges() {
        cvlcptRange = recentRange(
                "SELECT to_char(max(ocrn_dt)::date - 29, 'YYYY-MM-DD'), to_char(max(ocrn_dt), 'YYYY-MM-DD')"
                        + "  FROM parking.tb_cvlcpt"
                        + " WHERE cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL",
                "TB_CVLCPT");
        crdnRange = recentRange(
                "SELECT to_char(max(crdn_dt)::date - 29, 'YYYY-MM-DD'), to_char(max(crdn_dt), 'YYYY-MM-DD')"
                        + "  FROM parking.tb_crdn_dsctn"
                        + " WHERE emd_cd IS NOT NULL",
                "TB_CRDN_DSCTN");
    }

    private String[] recentRange(String sql, String table) {
        String[] range = jdbcTemplate.queryForObject(sql,
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        assertThat(range[0])
                .as("원천(%s)이 비어 있다 — 배치 적재분을 먼저 확인하라", table)
                .matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(range[1]).matches("\\d{4}-\\d{2}-\\d{2}");
        return range;
    }

    // ────────────────────────────────────────────────────────────── 1. most-complaint

    @Test
    @DisplayName("1. most-complaint — sum(points.cnt) == totalCnt == 순위 API sum(zones.cvlcptCnt)")
    void mostComplaintSumMatchesRanking() {
        HotspotHeatmapResultVO heatmap = heatmap("most-complaint", cvlcptRange, null);
        long rankingSum = rank("most-complaint", cvlcptRange, null).stream()
                .mapToLong(HotspotRankingZoneVO::getCvlcptCnt).sum();

        assertThat(heatmap.getPoints()).isNotEmpty();
        assertThat(heatmap.getUnit()).as("명시 구간이면 unit 은 null").isNull();
        assertThat(heatmap.getCellDeg()).isEqualByComparingTo("0.001");
        assertThat(pointSum(heatmap)).isEqualTo(heatmap.getTotalCnt());
        assertThat(heatmap.getTotalCnt())
                .as("히트맵과 순위 API 의 WHERE 가 갈렸다 — 읍면동 합계와 히트맵 합계가 다르다")
                .isEqualTo(rankingSum);
        assertThat((long) heatmap.getPoints().size())
                .as("100m 칸 집계가 안 됐다 — 칸 수가 건수보다 작아야 한다")
                .isLessThanOrEqualTo(heatmap.getTotalCnt());
    }

    // ────────────────────────────────────────────────────────────── 2. enforcement

    @Test
    @DisplayName("2. enforcement — sum(points.cnt) == totalCnt == 순위 API sum(zones.crdnCnt) (현재 구간만)")
    void enforcementSumMatchesRanking() {
        HotspotHeatmapResultVO heatmap = heatmap("enforcement", crdnRange, null);
        long rankingSum = rank("enforcement", crdnRange, null).stream()
                .mapToLong(HotspotRankingZoneVO::getCrdnCnt).sum();

        assertThat(heatmap.getPoints()).isNotEmpty();
        assertThat(pointSum(heatmap)).isEqualTo(heatmap.getTotalCnt());
        assertThat(heatmap.getTotalCnt())
                .as("히트맵이 직전 구간(prevStart)까지 센 것 아닌지 — 순위 crdnCnt 합계와 달라졌다")
                .isEqualTo(rankingSum);
    }

    // ────────────────────────────────────────────────────────────── 3. sggCd

    @Test
    @DisplayName("3. sggCd=50110 — 히트맵 합계 == 제주시 순위 합계 == DB 의 제주시 건수, 전체보다 작다")
    void sggFilterRestrictsToJejuCity() {
        HotspotHeatmapResultVO all = heatmap("most-complaint", cvlcptRange, null);
        HotspotHeatmapResultVO jeju = heatmap("most-complaint", cvlcptRange, SGG_JEJU);
        List<HotspotRankingZoneVO> zones = rank("most-complaint", cvlcptRange, SGG_JEJU);

        assertThat(jeju.getPoints()).isNotEmpty();
        assertThat(zones).isNotEmpty()
                .allSatisfy(z -> assertThat(z.getEmdCd()).startsWith(SGG_JEJU));
        assertThat(pointSum(jeju)).isEqualTo(jeju.getTotalCnt());
        assertThat(jeju.getTotalCnt())
                .isEqualTo(zones.stream().mapToLong(HotspotRankingZoneVO::getCvlcptCnt).sum());

        Long dbCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt"
                        + " WHERE ocrn_dt::date BETWEEN ?::date AND ?::date"
                        + "   AND emd_cd IS NOT NULL AND cvlcpt_type_cd <> '99'"
                        + "   AND LEFT(emd_cd, 5) = ?",
                Long.class, cvlcptRange[0], cvlcptRange[1], SGG_JEJU);
        assertThat(jeju.getTotalCnt()).as("제주시(emd_cd 접두 %s) 행 수", SGG_JEJU).isEqualTo(dbCnt);
        assertThat(jeju.getTotalCnt())
                .as("시군구 필터가 먹지 않았다 — 전체와 같다")
                .isLessThan(all.getTotalCnt());
    }

    // ────────────────────────────────────────────────────────────── 4. shortage

    @Test
    @DisplayName("4. view=shortage → 400 (HEATMAP_VIEW_UNSUPPORTED — 상태코드만 계약)")
    void shortageIsBadRequest() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                HEATMAP_URL + "?view=shortage&startDt={s}&endDt={e}", String.class,
                cvlcptRange[0], cvlcptRange[1]);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    // ────────────────────────────────────────────────────────────── 5. 반올림

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "most-complaint", "enforcement" })
    @DisplayName("5. 좌표는 소수 3자리 이하(BigDecimal scale <= 3), 건수는 양수, 칸 좌표는 중복 없음")
    void coordinatesRoundedToThreeDecimals(String view) {
        HotspotHeatmapResultVO heatmap = heatmap(view, "enforcement".equals(view) ? crdnRange : cvlcptRange, null);
        assertThat(heatmap.getPoints()).isNotEmpty();
        assertThat(heatmap.getPoints()).allSatisfy(p -> {
            assertThat(p.getLat()).isNotNull();
            assertThat(p.getLot()).isNotNull();
            assertThat(p.getLat().scale()).as("lat %s", p.getLat()).isLessThanOrEqualTo(3);
            assertThat(p.getLot().scale()).as("lot %s", p.getLot()).isLessThanOrEqualTo(3);
            assertThat(p.getCnt()).isPositive();
        });
        long distinctCells = heatmap.getPoints().stream()
                .map(p -> p.getLat().stripTrailingZeros().toPlainString() + ","
                        + p.getLot().stripTrailingZeros().toPlainString())
                .distinct().count();
        assertThat(distinctCells).as("같은 칸이 두 번 나왔다 — GROUP BY 가 반올림값 기준이 아니다")
                .isEqualTo(heatmap.getPoints().size());
    }

    // ────────────────────────────────────────────────────────────── 헬퍼

    private static long pointSum(HotspotHeatmapResultVO vo) {
        return vo.getPoints().stream().mapToLong(HotspotHeatmapPointVO::getCnt).sum();
    }

    private HotspotHeatmapResultVO heatmap(String view, String[] range, String sggCd) {
        ResponseEntity<HotspotHeatmapResultVO> response = restTemplate.exchange(
                buildUri(HEATMAP_URL, view, range, sggCd), HttpMethod.GET, null,
                HotspotHeatmapResultVO.class, params(view, range, sggCd));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getView()).isEqualTo(view);
        assertThat(response.getBody().getPoints()).isNotNull();
        return response.getBody();
    }

    private List<HotspotRankingZoneVO> rank(String view, String[] range, String sggCd) {
        ResponseEntity<HotspotRankingResultVO> response = restTemplate.exchange(
                buildUri(RANKING_URL, view, range, sggCd), HttpMethod.GET, null,
                HotspotRankingResultVO.class, params(view, range, sggCd));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getZones()).isNotNull();
        return response.getBody().getZones();
    }

    private static Map<String, Object> params(String view, String[] range, String sggCd) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("view", view);
        params.put("startDt", range[0]);
        params.put("endDt", range[1]);
        if (sggCd != null) {
            params.put("sggCd", sggCd);
        }
        return params;
    }

    private static String buildUri(String base, String view, String[] range, String sggCd) {
        StringBuilder uri = new StringBuilder(base);
        String separator = "?";
        for (String k : params(view, range, sggCd).keySet()) {
            uri.append(separator).append(k).append("={").append(k).append('}');
            separator = "&";
        }
        return uri.toString();
    }
}
