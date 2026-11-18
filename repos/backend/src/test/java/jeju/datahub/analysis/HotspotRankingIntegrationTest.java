package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

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

import jeju.datahub.analysis.service.CvlcptTypeCntVO;
import jeju.datahub.analysis.service.HotspotRankingResultVO;
import jeju.datahub.analysis.service.HotspotRankingZoneVO;

/**
 * 분석레이어 순위 API 통합 테스트. ({@code GET /api/hotspots/ranking})
 *
 * <p>이 테스트가 생기기 전에는 {@code /api/hotspots/ranking} 을 타는 테스트가 0건이라
 * {@code HotspotRankingMapper.xml} 은 {@code ./gradlew test} 에서 한 번도 실행되지 않았다
 * (MyBatis XML 은 {@code compileJava} 대상도 아니다).
 *
 * <p>전략은 {@code HotspotAnalysisIntegrationTest} 와 같다 — {@code RANDOM_PORT} + {@link TestRestTemplate},
 * {@code test} 프로파일이 보안 체인을 꺼서 인증 없이 호출된다. DB 를 바꾸지 않으므로 복원이 필요 없다.
 *
 * <h2>🔴 날짜·기대값을 상수로 박지 않는다</h2>
 * 민원 적재분은 배치가 소유한다 — 재적재 한 번이면 구간이 통째로 옮겨간다. 그래서
 * <ul>
 *   <li>조회 구간은 {@link #resolveDataRange()} 가 <b>DB 에서</b> 읽는다.</li>
 *   <li>🔴 구간은 <b>반드시 넘긴다</b> — 비우면 서비스가 실제 오늘 기준 {@code YESTERDAY} 로 폴백해
 *       {@code zones} 가 0건이 되고, 아래 불변식이 한 번도 실행되지 않은 채 초록불이 된다.
 *       같은 이유로 모든 케이스가 {@code isNotEmpty()} 를 <b>먼저</b> 단언한다.</li>
 *   <li>기대값은 리터럴이 아니라 <b>불변식</b>이다 — 유형별 합 == 총계, 주차 수요 = 면수 + 민원,
 *       부족률 rank 순 단조 비증가(PSDR 의 단조 감소 변환), 마커 점이 경계 안.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HotspotRankingIntegrationTest {

    private static final String RANKING_URL = "/api/hotspots/ranking";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 조회 시작일 (yyyy-MM-dd) — DB 의 민원 최소 발생일. */
    private String startDt;

    /** 조회 종료일 (yyyy-MM-dd) — DB 의 민원 최대 발생일. */
    private String endDt;

    /**
     * 조회 구간을 DB 에서 읽는다. 매퍼와 같은 조건({@code cvlcpt_type_cd <> '99'}, {@code emd_cd IS NOT NULL}).
     * 적재분이 비어 있으면 여기서 원인을 드러내며 실패한다.
     */
    @BeforeEach
    void resolveDataRange() {
        String[] range = jdbcTemplate.queryForObject(
                "SELECT to_char(min(ocrn_dt), 'YYYY-MM-DD'), to_char(max(ocrn_dt), 'YYYY-MM-DD')"
                        + "  FROM parking.tb_cvlcpt"
                        + " WHERE cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL",
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        startDt = range[0];
        endDt = range[1];
        assertThat(startDt)
                .as("원천(TB_CVLCPT)이 비어 있다 — 배치 적재분을 먼저 확인하라")
                .matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(endDt).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    // ────────────────────────────────────────────────────────────── most-complaint

    @Test
    @DisplayName("most-complaint — 전 zone 에서 typeCnts 합 == cvlcptCnt, 유형명 전부 채움")
    void mostComplaintTypeBreakdownSumsToTotal() {
        List<HotspotRankingZoneVO> zones = rank("most-complaint", null);
        assertThat(zones).isNotEmpty();

        for (HotspotRankingZoneVO z : zones) {
            assertThat(z.getTypeCnts())
                    .as("zone %s 의 typeCnts 가 비었다", z.getEmdCd())
                    .isNotEmpty();
            long sum = z.getTypeCnts().stream().mapToLong(CvlcptTypeCntVO::getCvlcptCnt).sum();
            assertThat(sum)
                    .as("zone %s 의 유형별 합과 총계가 다르다 — 행 단위 count(*) 기준 불일치",
                            z.getEmdCd())
                    .isEqualTo(z.getCvlcptCnt());
            assertThat(z.getTypeCnts())
                    .as("유형명이 null — TB_COM_CD(CVLCPT_TYPE_CD) 조인 실패")
                    .allSatisfy(t -> {
                        assertThat(t.getCvlcptTypeNm()).isNotNull();
                        assertThat(t.getCvlcptTypeCd()).isNotEqualTo("99");
                        assertThat(t.getCvlcptCnt()).isPositive();
                    });
            // 수요 부족 전용 필드는 이 view 에서 null
            assertThat(z.getPkltCpctySum()).isNull();
            assertThat(z.getDemandIndex()).isNull();
            assertThat(z.getShortageRatio()).isNull();
        }
        assertRankOrderedByDesc(zones, HotspotRankingZoneVO::getCvlcptCnt);
    }

    @Test
    @DisplayName("most-complaint — 유형 필터를 걸어도 typeCnts 합 == cvlcptCnt (필터 1종만 남는다)")
    void mostComplaintTypeBreakdownRespectsTypeFilter() {
        String typeCd = jdbcTemplate.queryForObject(
                "SELECT cvlcpt_type_cd FROM parking.tb_cvlcpt"
                        + " WHERE cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL"
                        + " GROUP BY cvlcpt_type_cd ORDER BY count(*) DESC, cvlcpt_type_cd LIMIT 1",
                String.class);

        List<HotspotRankingZoneVO> zones = rank("most-complaint", typeCd);
        assertThat(zones).isNotEmpty();
        for (HotspotRankingZoneVO z : zones) {
            assertThat(z.getTypeCnts()).extracting(CvlcptTypeCntVO::getCvlcptTypeCd).containsOnly(typeCd);
            long sum = z.getTypeCnts().stream().mapToLong(CvlcptTypeCntVO::getCvlcptCnt).sum();
            assertThat(sum).as("zone %s", z.getEmdCd()).isEqualTo(z.getCvlcptCnt());
        }
    }

    // ────────────────────────────────────────────────────────────── enforcement

    @Test
    @DisplayName("enforcement — cvlcptCnt 가 같은 동·기간의 most-complaint 민원 수와 같다 (0 하드코딩 폐지)")
    void enforcementCarriesComplaintCount() {
        List<HotspotRankingZoneVO> zones = rank("enforcement", null);
        assertThat(zones).isNotEmpty();

        Map<String, Long> complaintByEmd = rank("most-complaint", null).stream()
                .collect(Collectors.toMap(HotspotRankingZoneVO::getEmdCd, HotspotRankingZoneVO::getCvlcptCnt));

        for (HotspotRankingZoneVO z : zones) {
            assertThat(z.getCvlcptCnt()).isGreaterThanOrEqualTo(0L);
            assertThat(z.getCvlcptCnt())
                    .as("zone %s — 지도 전체가 같은 민원 수 정의여야 한다", z.getEmdCd())
                    .isEqualTo(complaintByEmd.getOrDefault(z.getEmdCd(), 0L));
            assertThat(z.getTypeCnts()).as("enforcement 는 빈 목록(null 아님)").isNotNull().isEmpty();
            assertThat(z.getPkltCpctySum()).isNull();
            assertThat(z.getDemandIndex()).isNull();
            assertThat(z.getShortageRatio()).isNull();
        }
        assertThat(zones).as("민원 수가 전부 0 이면 서브쿼리가 죽은 것이다")
                .anySatisfy(z -> assertThat(z.getCvlcptCnt()).isPositive());
        assertRankOrderedByDesc(zones, HotspotRankingZoneVO::getCrdnCnt);
    }

    // ────────────────────────────────────────────────────────────── shortage

    @Test
    @DisplayName("shortage — demandIndex = 면수 + 민원수, shortageRatio 0~1 · rank 순 단조 비증가, 민원 0 동 없음")
    void shortageCarriesDemandAndRatio() {
        List<HotspotRankingZoneVO> zones = rank("shortage", null);
        assertThat(zones).isNotEmpty();

        // 같은 조건의 함수 결과 — 민원수(out_cvlcpt_sum)의 기대값 출처.
        // 🔴 6번째 인자(등급 필터)를 '심각' → NULL::text 로 바꾸고 매퍼와 같은
        //   WHERE(out_cvlcpt_sum > 0)를 건다. 응답 모집단이 "Jenks 심각" 에서 "주차민원이 있는
        //   전 zone" 으로 넓어져, '심각' 로 부르면 응답에는 있고 이 맵에는 없는 zone 이 생긴다.
        Map<String, Long> cvlcptSumByEmd = jdbcTemplate.query(
                "SELECT out_emd_cd, out_cvlcpt_sum"
                        + "  FROM parking.fn_jenks_shortage(?::date, ?::date, NULL, NULL, NULL, NULL::text)"
                        + " WHERE out_cvlcpt_sum > 0",
                (rs, rowNum) -> Map.entry(rs.getString(1), rs.getLong(2)),
                startDt, endDt).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        BigDecimal prevRatio = null;
        for (HotspotRankingZoneVO z : zones) {
            assertThat(z.getPkltCpctySum()).as("zone %s pkltCpctySum", z.getEmdCd()).isNotNull();
            assertThat(z.getDemandIndex()).as("zone %s demandIndex", z.getEmdCd()).isNotNull();
            assertThat(z.getShortageRatio()).as("zone %s shortageRatio", z.getEmdCd()).isNotNull();
            assertThat(z.getTypeCnts()).as("shortage 는 빈 목록(null 아님)").isNotNull().isEmpty();

            Long cvlcptSum = cvlcptSumByEmd.get(z.getEmdCd());
            assertThat(cvlcptSum).as("zone %s 가 함수 결과에 없다", z.getEmdCd()).isNotNull();
            assertThat(cvlcptSum).as("민원 0 동은 배제된다").isPositive();
            assertThat(z.getDemandIndex())
                    .as("zone %s — 주차 수요 = 공급 면수 + 민원 수", z.getEmdCd())
                    .isEqualByComparingTo(z.getPkltCpctySum().add(BigDecimal.valueOf(cvlcptSum)));

            BigDecimal expectedRatio = BigDecimal.valueOf(cvlcptSum)
                    .divide(z.getDemandIndex(), 4, RoundingMode.HALF_UP);
            assertThat(z.getShortageRatio()).isEqualByComparingTo(expectedRatio);
            assertThat(z.getShortageRatio()).isGreaterThan(BigDecimal.ZERO);
            assertThat(z.getShortageRatio()).isLessThanOrEqualTo(BigDecimal.ONE);

            if (prevRatio != null) {
                assertThat(z.getShortageRatio())
                        .as("rank %d — 부족률은 rank 순 단조 비증가(동률 허용)여야 한다", z.getRank())
                        .isLessThanOrEqualTo(prevRatio);
            }
            prevRatio = z.getShortageRatio();
        }
    }

    // ────────────────────────────────────────────────────────────── 마커 좌표 (세 view 공통)

    /**
     * 경계 polygon 이 있는 zone 의 마커 {@code (mrkLot, mrkLat)} 는 그 경계 <b>안</b>에 있다.
     * {@code ST_PointOnSurface} 가 보장하는 성질이라 centroid({@code ctr_lat}) 로 되돌아가면 오목한 동에서 깨진다.
     * 🔴 {@code ST_MakePoint(x=경도, y=위도)} 순서.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "most-complaint", "enforcement", "shortage" })
    @DisplayName("세 view — 경계가 있는 zone 의 마커 점은 경계 polygon 안에 있다 (ST_PointOnSurface)")
    void markerLiesInsideBoundary(String view) {
        List<HotspotRankingZoneVO> zones = rank(view, null);
        assertThat(zones).isNotEmpty();

        int checked = 0;
        for (HotspotRankingZoneVO z : zones) {
            List<Boolean> contains = jdbcTemplate.queryForList(
                    "SELECT ST_Contains(bndry_geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))"
                            + "  FROM parking.tb_admdst"
                            + " WHERE emd_cd = ? AND bndry_geom IS NOT NULL",
                    Boolean.class, z.getMrkLot(), z.getMrkLat(), z.getEmdCd());
            if (contains.isEmpty()) {
                continue; // 경계 없는 zone — ctr_lat/ctr_lot fallback 대상이라 이 불변식 밖
            }
            assertThat(z.getMrkLat()).as("zone %s mrkLat", z.getEmdCd()).isNotNull();
            assertThat(z.getMrkLot()).as("zone %s mrkLot", z.getEmdCd()).isNotNull();
            assertThat(contains)
                    .as("zone %s 의 마커 (%s, %s) 가 경계 밖이다", z.getEmdCd(), z.getMrkLot(), z.getMrkLat())
                    .containsOnly(Boolean.TRUE);
            checked++;
        }
        assertThat(checked).as("경계가 있는 zone 이 0건이면 불변식이 실행되지 않은 것이다").isPositive();
    }

    // ────────────────────────────────────────────────────────────── 헬퍼

    /**
     * 정렬 키가 rank 순으로 비증가이고, rank 가 {@code RANK()} 의 정의를 지키는지.
     *
     * <p>🔴 종전의 {@code rank == i + 1} 연속 번호 단언을 <b>버렸다</b>.
     * 그 단언은 {@code ROW_NUMBER()} 전제였는데 세 view 모두 {@code RANK()} 로 바뀌어
     * <b>동률 뒤 번호를 건너뛴다</b>(1,1,…,1,7). 대신 세 가지를 본다 —
     * ① 정렬 키가 rank 순 비증가(기존 유지) · ② 같은 키면 같은 rank · ③ rank 는 비감소.
     */
    private static void assertRankOrderedByDesc(List<HotspotRankingZoneVO> zones,
                                                Function<HotspotRankingZoneVO, Long> key) {
        // ② 같은 키면 같은 rank — 값으로 묶었을 때 묶음마다 rank 가 1종이어야 한다.
        Map<Long, Set<Long>> ranksByKey = new LinkedHashMap<>();
        for (HotspotRankingZoneVO z : zones) {
            ranksByKey.computeIfAbsent(key.apply(z), k -> new TreeSet<>()).add(z.getRank());
        }
        for (Map.Entry<Long, Set<Long>> e : ranksByKey.entrySet()) {
            assertThat(e.getValue())
                    .as("값 %s 인 zone 들이 서로 다른 rank 를 받았다 — RANK() 가 아니라 ROW_NUMBER() 다",
                            e.getKey())
                    .hasSize(1);
        }

        for (int i = 1; i < zones.size(); i++) {
            // ① 표시 숫자가 rank 순으로 비증가
            assertThat(key.apply(zones.get(i)))
                    .as("출력 %d번째 — 표시 숫자와 순위가 어긋났다", i + 1)
                    .isLessThanOrEqualTo(key.apply(zones.get(i - 1)));
            // ③ rank 는 비감소 (동률이면 같고, 아니면 건너뛰며 커진다)
            assertThat(zones.get(i).getRank())
                    .as("출력 %d번째 — rank 가 출력 순서를 거슬러 작아졌다", i + 1)
                    .isGreaterThanOrEqualTo(zones.get(i - 1).getRank());
        }
        if (!zones.isEmpty()) {
            assertThat(zones.get(0).getRank()).as("첫 행의 rank 는 1 이어야 한다").isEqualTo(1L);
        }
    }

    // ────────────────────────────────────────────────────────────── 최다 단속유형

    @Test
    @DisplayName("enforcement — topCrdnMthd·비율이 현재 구간 원천 집계의 1위와 같다 (직전 구간 미혼입)")
    void enforcementTopMthdMatchesCurrentPeriodSource() {
        List<HotspotRankingZoneVO> zones = rank("enforcement", null);
        assertThat(zones).isNotEmpty();

        Map<String, String> topMthd = new LinkedHashMap<>();
        Map<String, Long> topCnt = new LinkedHashMap<>();
        // 🔴 구현과 독립으로 다시 센다 — 동률은 crdn_mthd 오름차순.
        jdbcTemplate.query(
                "SELECT emd_cd, crdn_mthd, n FROM ("
                        + "  SELECT emd_cd, crdn_mthd, count(*) n,"
                        + "         row_number() OVER (PARTITION BY emd_cd ORDER BY count(*) DESC, crdn_mthd) k"
                        + "    FROM parking.tb_crdn_dsctn"
                        + "   WHERE crdn_dt::date BETWEEN ?::date AND ?::date"
                        + "     AND emd_cd IS NOT NULL AND NULLIF(BTRIM(crdn_mthd), '') IS NOT NULL"
                        + "   GROUP BY emd_cd, crdn_mthd) t WHERE k = 1",
                rs -> {
                    topMthd.put(rs.getString("emd_cd"), rs.getString("crdn_mthd"));
                    topCnt.put(rs.getString("emd_cd"), rs.getLong("n"));
                },
                startDt, endDt);

        for (HotspotRankingZoneVO z : zones) {
            assertThat(z.getTopCrdnMthd()).as("zone %s 의 최다 유형", z.getEmdCd())
                    .isEqualTo(topMthd.get(z.getEmdCd()));
            assertThat(z.getTopCrdnMthdRatio()).as("zone %s 의 비율 = 1위 건수 ÷ crdnCnt", z.getEmdCd())
                    .isEqualTo((double) topCnt.get(z.getEmdCd()) / z.getCrdnCnt())
                    .isGreaterThan(0.0)
                    .isLessThanOrEqualTo(1.0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "most-complaint", "shortage" })
    @DisplayName("enforcement 외 view — 최다 단속유형 2필드는 null")
    void otherViewsHaveNoTopMthd(String view) {
        List<HotspotRankingZoneVO> zones = rank(view, null);
        assertThat(zones).isNotEmpty();
        assertThat(zones).allSatisfy(z -> {
            assertThat(z.getTopCrdnMthd()).isNull();
            assertThat(z.getTopCrdnMthdRatio()).isNull();
        });
    }

    private List<HotspotRankingZoneVO> rank(String view, String cvlcptTypeCd) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("view", view);
        params.put("startDt", startDt);
        params.put("endDt", endDt);
        if (cvlcptTypeCd != null) {
            params.put("cvlcptTypeCd", cvlcptTypeCd);
        }
        StringBuilder uri = new StringBuilder(RANKING_URL);
        String separator = "?";
        for (String k : params.keySet()) {
            uri.append(separator).append(k).append("={").append(k).append('}');
            separator = "&";
        }
        ResponseEntity<HotspotRankingResultVO> response = restTemplate.exchange(
                uri.toString(), HttpMethod.GET, null, HotspotRankingResultVO.class, params);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getView()).isEqualTo(view);
        assertThat(response.getBody().getZones()).isNotNull();
        return response.getBody().getZones();
    }
}
