package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.AnalysisWeightService;
import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneCngstCellVO;
import jeju.datahub.lifezone.service.LifezoneCngstRequest;
import jeju.datahub.lifezone.service.LifezoneCngstResultVO;
import jeju.datahub.lifezone.service.LifezoneCngstService;
import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneGradeScale;
import jeju.datahub.lifezone.service.LifezoneRankVO;
import jeju.datahub.lifezone.service.LifezoneSupply;
import jeju.datahub.lifezone.service.LifezoneTarget;
import jeju.datahub.lifezone.service.LifezoneTargetVO;
import jeju.datahub.lifezone.service.impl.LifezoneCngstMapper;
import jeju.datahub.lifezone.service.impl.LifezoneCngstRow;
import jeju.datahub.lifezone.service.impl.LifezoneSupplyQueryParam;

/**
 * 생활권 <b>혼잡도</b> 격자 집계 매퍼 통합 테스트.
 *
 * <p>매퍼를 직접 주입해 <b>불변 성질만</b> 단언한다 — 공급&gt;0 칸만 나온다 · 격자 중복 없음 ·
 * 단속 3분류 합계 보존 · 격자 4종 조회 · 읍면동 필터 · {@code ring_json} 파싱 · 보유 구간.
 *
 * <p>🔴 실측 칸 수·날짜를 박지 않는다(시드가 재적재되면 한꺼번에 깨진다). 조회 구간은
 * {@code selectDataRange()} 결과(단속 보유 전 구간)에서 유도한다. 실측 칸 수 대조는 수동 절차다.
 *
 * <p>🆕 <b>서비스({@link LifezoneCngstService})를 직접 호출</b>해 혼잡지수 산정·세 상태·등급 불변식을
 * 본다(HTTP 핸들러는). 계수를 바꾸는 케이스는 {@code parking.tb_anls_vrbl_stng.stng_vl} 을
 * {@code JdbcTemplate} 로 바꾸므로 🔴 <b>{@link #restoreLifezoneCoefficients()} 가 {@code @AfterEach} 에서
 * LIFEZONE 행을 {@code bsc_stng_vl} 로 되돌린다</b>({@code AnalysisWeightIntegrationTest.resetVariableSeed()}
 * 선례 · 범위는 {@code anls_type_cd='LIFEZONE'} · 이력 표는 건드리지 않는다). 서비스는 계수를 캐시하지 않는다.
 * 조회 구간은 단속 보유 구간 끝에서 최대 365일(조회 상한 366일 안)로 유도한다 — 날짜를 박지 않는다.
 *
 * <p>🆕 <b>HTTP 핸들러({@code GET /api/analysis/lifezone/congestion})</b>를 {@link TestRestTemplate}
 * 로 부른다(test 프로파일은 permitAll 체인 — 미인증 401 은 {@code AuthSecurityIntegrationTest} 가 진다).
 * 응답은 {@link JsonNode} 로 읽는다 — 직렬화된 키 이름 자체가 프론트와의 계약이다.
 *
 * <p>🔁 <b>셀 구역명은 캐시 이름 아니면 폴백</b>이다(매퍼가 격자 중심 키로 캐시를 JOIN · 외부 호출 0).
 * 순위 밖 셀 케이스는 {@code tb_zone_nm_cache} 1행을 심고, 별도 {@code @AfterEach} {@link #restorePlantedZoneName()} 가
 * 그 키 한 행만 되돌린다(계수 복원과 섞지 않는다 · 공유 캐시라 전체 삭제 금지).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("생활권 혼잡도 격자 집계 매퍼 (LifezoneCngstMapper)")
class LifezoneCngstIntegrationTest {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String COORD_BBOX =
            " lat BETWEEN 33.10 AND 33.65 AND lot BETWEEN 126.10 AND 127.00 ";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private LifezoneCngstMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LifezoneCngstService cngstService;

    @Autowired
    private AnalysisWeightService analysisWeightService;

    private DataRangeVO range;

    /** 순위 밖 셀 케이스가 심은 캐시 키. {@code null} 이면 심지 않았다(건너뜀) — 복원할 것이 없다. */
    private BigDecimal plantedLatKey;
    private BigDecimal plantedLotKey;

    /** 심기 전 그 키의 원래 캐시 행. {@code null} 이면 원래 행이 없었다 → 복원은 DELETE 다. */
    private CachedZoneNm originalCachedZoneNm;

    @BeforeEach
    void resolveRange() {
        range = mapper.selectDataRange();
        assertThat(range).as("단속 좌표 유효 데이터가 있어야 이 테스트가 의미 있다").isNotNull();
        assertThat(range.getDataBgngYmd()).matches("\\d{8}");
        assertThat(range.getDataEndYmd()).matches("\\d{8}");
    }

    /** 단속 보유 전 구간 파라미터 (반열림 — 종료일 +1일 00:00) */
    private LifezoneSupplyQueryParam param(int gridSz, String emdCd, String sggCd) {
        LifezoneSupplyQueryParam p = new LifezoneSupplyQueryParam();
        p.setGridSz(gridSz);
        p.setBgngDt(LocalDate.parse(range.getDataBgngYmd(), YMD).atStartOfDay());
        p.setEndDtExcl(LocalDate.parse(range.getDataEndYmd(), YMD).plusDays(1).atStartOfDay());
        p.setEmdCd(emdCd);
        p.setSggCd(sggCd);
        return p;
    }

    @Test
    @DisplayName("격자 4종 모두 조회되고 · 공급>0 칸만 · (gx,gy) 중복 없음 · ring_json 파싱 가능")
    void allGridSizesYieldSupplyPositiveUniqueCells() throws Exception {
        for (int gridSz : LifezoneSupply.GRID_SZS) {
            List<LifezoneCngstRow> rows = mapper.selectCngstCells(param(gridSz, null, null));
            assertThat(rows).as("%dm 격자가 비었다", gridSz).isNotEmpty();

            Set<String> keys = new HashSet<>();
            for (LifezoneCngstRow r : rows) {
                assertThat(r.getPkltCpctySum()).as("%dm (%d,%d) 공급", gridSz, r.getGx(), r.getGy())
                        .isPositive();
                assertThat(r.getPkltCnt()).isPositive();
                assertThat(r.getNFixed()).isNotNegative();
                assertThat(r.getNPda()).isNotNegative();
                assertThat(r.getNManual()).isNotNegative();
                assertThat(r.getCvlcptCnt()).isNotNegative();
                assertThat(keys.add(r.getGx() + "_" + r.getGy()))
                        .as("%dm 격자 중복 (%d,%d)", gridSz, r.getGx(), r.getGy()).isTrue();

                JsonNode ring = JSON.readTree(r.getRingJson());
                assertThat(ring.path("type").asText()).isEqualTo("Polygon");
                JsonNode coords = ring.path("coordinates").path(0);
                assertThat(coords.size()).isEqualTo(5);   // 닫힌 사각형
                // 중심좌표가 폴리곤 경위도 범위 안
                double minLot = Double.MAX_VALUE, maxLot = -Double.MAX_VALUE;
                double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
                for (JsonNode pt : coords) {
                    minLot = Math.min(minLot, pt.get(0).asDouble());
                    maxLot = Math.max(maxLot, pt.get(0).asDouble());
                    minLat = Math.min(minLat, pt.get(1).asDouble());
                    maxLat = Math.max(maxLat, pt.get(1).asDouble());
                }
                assertThat(r.getCtrLot().doubleValue()).isBetween(minLot, maxLot);
                assertThat(r.getCtrLat().doubleValue()).isBetween(minLat, maxLat);
            }
        }
    }

    @Test
    @DisplayName("n_fixed + n_pda + n_manual 합계 = 공급>0 격자에 떨어진 같은 조건 단속 건수 (전 수단)")
    void crackdownSplitPreservesTotal() {
        for (int gridSz : LifezoneSupply.GRID_SZS) {
            LifezoneSupplyQueryParam p = param(gridSz, null, null);
            long mapped = mapper.selectCngstCells(p).stream()
                    .mapToLong(r -> (long) r.getNFixed() + r.getNPda() + r.getNManual()).sum();

            // 독립 집계: 공급>0 격자 집합에 떨어진 단속을 직접 센다(수단 분류 없이 count(*))
            Long expected = jdbcTemplate.queryForObject(
                    "WITH sup AS ("
                            + " SELECT floor(ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(lot::float8, lat::float8), 4326), 5179)) / ?::float8)::bigint gx,"
                            + "        floor(ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(lot::float8, lat::float8), 4326), 5179)) / ?::float8)::bigint gy"
                            + "   FROM parking.tb_pblpklt WHERE" + COORD_BBOX
                            + "  GROUP BY 1, 2 HAVING sum(coalesce(pklt_cpcty, 0)) > 0)"
                            + " SELECT count(*) FROM parking.tb_crdn_dsctn d"
                            + "  JOIN sup ON sup.gx = floor(ST_X(ST_Transform(d.geom, 5179)) / ?::float8)::bigint"
                            + "          AND sup.gy = floor(ST_Y(ST_Transform(d.geom, 5179)) / ?::float8)::bigint"
                            + "  WHERE d.lat BETWEEN 33.10 AND 33.65 AND d.lot BETWEEN 126.10 AND 127.00"
                            + "    AND d.crdn_dt >= ? AND d.crdn_dt < ?",
                    Long.class, gridSz, gridSz, gridSz, gridSz, p.getBgngDt(), p.getEndDtExcl());
            assertThat(mapped).as("%dm 단속 합계 보존", gridSz).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("읍면동 필터 — 결과 모든 행의 emd_cd 가 그 값 (격자 최빈 읍면동 기준)")
    void emdFilterSelectsByModalEmd() {
        int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;
        List<LifezoneCngstRow> all = mapper.selectCngstCells(param(gridSz, null, null));
        // 칸이 가장 많은 읍면동을 고른다(날짜·코드를 박지 않는다)
        String emdCd = all.stream().filter(r -> r.getEmdCd() != null)
                .collect(Collectors.groupingBy(LifezoneCngstRow::getEmdCd, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();

        List<LifezoneCngstRow> filtered = mapper.selectCngstCells(param(gridSz, emdCd, null));
        assertThat(filtered).isNotEmpty().allSatisfy(r -> assertThat(r.getEmdCd()).isEqualTo(emdCd));
        // 필터는 격자 선택만 한다 — 같은 격자의 집계값은 전 지역 조회와 같다
        Map<String, LifezoneCngstRow> byKey = all.stream()
                .collect(Collectors.toMap(r -> r.getGx() + "_" + r.getGy(), Function.identity()));
        for (LifezoneCngstRow r : filtered) {
            LifezoneCngstRow same = byKey.get(r.getGx() + "_" + r.getGy());
            assertThat(same).isNotNull();
            assertThat(r.getPkltCpctySum()).isEqualTo(same.getPkltCpctySum());
            assertThat(r.getNFixed() + r.getNPda() + r.getNManual())
                    .isEqualTo(same.getNFixed() + same.getNPda() + same.getNManual());
            assertThat(r.getCvlcptCnt()).isEqualTo(same.getCvlcptCnt());
        }
        assertThat(filtered).hasSize((int) all.stream().filter(r -> emdCd.equals(r.getEmdCd())).count());

        // 시군구 필터(앞 5자리)도 같은 기준
        String sggCd = emdCd.substring(0, 5);
        assertThat(mapper.selectCngstCells(param(gridSz, null, sggCd)))
                .isNotEmpty().allSatisfy(r -> assertThat(r.getEmdCd()).startsWith(sggCd));
    }

    @Test
    @DisplayName("보유 구간 = 단속 crdn_dt(좌표 유효) min/max · /targets 의 CNGST 가 같은 값을 싣는다")
    void dataRangeMatchesCrackdownMinMax() {
        String[] expected = jdbcTemplate.queryForObject(
                "SELECT to_char(min(crdn_dt), 'YYYYMMDD'), to_char(max(crdn_dt), 'YYYYMMDD')"
                        + " FROM parking.tb_crdn_dsctn WHERE" + COORD_BBOX,
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
        assertThat(range.getDataBgngYmd()).isEqualTo(expected[0]);
        assertThat(range.getDataEndYmd()).isEqualTo(expected[1]);

        LifezoneTargetVO[] targets = restTemplate.getForObject(
                "/api/analysis/lifezone/targets", LifezoneTargetVO[].class);
        assertThat(targets).isNotNull();
        LifezoneTargetVO cngst = java.util.Arrays.stream(targets)
                .filter(t -> LifezoneTarget.CNGST.name().equals(t.getAnlsTrgtCd()))
                .findFirst().orElseThrow();
        assertThat(cngst.getDataBgngYmd()).isEqualTo(expected[0]);
        assertThat(cngst.getDataEndYmd()).isEqualTo(expected[1]);
    }

    @Test
    @DisplayName("기간 창 밖(단속 보유 구간 이전)이면 단속은 0 이고 공급 칸은 그대로 나온다")
    void outOfWindowKeepsSupplyCellsWithZeroCounts() {
        LifezoneSupplyQueryParam p = param(LifezoneSupply.DEFAULT_GRID_SZ, null, null);
        LifezoneSupplyQueryParam before = param(LifezoneSupply.DEFAULT_GRID_SZ, null, null);
        before.setEndDtExcl(p.getBgngDt());
        before.setBgngDt(p.getBgngDt().minusDays(1));
        assertThatCode(() -> mapper.selectCngstCells(before)).doesNotThrowAnyException();
        List<LifezoneCngstRow> rows = mapper.selectCngstCells(before);
        assertThat(rows).hasSameSizeAs(mapper.selectCngstCells(p));
        assertThat(rows).allSatisfy(r -> assertThat(r.getNFixed() + r.getNPda() + r.getNManual()).isZero());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 서비스 직접 호출 (혼잡지수 · 세 상태 · 등급)
    // ═══════════════════════════════════════════════════════════════════════════

    /** 🔴 계수를 바꾼 케이스가 있으므로 매번 LIFEZONE 설정값을 기본설정값으로 되돌린다 */
    @AfterEach
    void restoreLifezoneCoefficients() {
        jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = bsc_stng_vl"
                + " WHERE anls_type_cd = 'LIFEZONE'");
    }

    /**
     * 순위 밖 셀 케이스가 심은 캐시 1행을 되돌린다. 심은 키 한 행만 다룬다(공유 캐시라 전체 삭제 금지).
     * 계수 복원({@link #restoreLifezoneCoefficients()})과 섞지 않는다.
     */
    @AfterEach
    void restorePlantedZoneName() {
        if (plantedLatKey == null || plantedLotKey == null) {
            return;
        }
        if (originalCachedZoneNm != null) {
            jdbcTemplate.update("UPDATE parking.tb_zone_nm_cache"
                            + "   SET zone_nm = ?, zone_nm_src = ?, poi_dist_m = ?, inq_dt = ?"
                            + " WHERE lat_key = ? AND lot_key = ?",
                    originalCachedZoneNm.zoneNm(), originalCachedZoneNm.zoneNmSrc(),
                    originalCachedZoneNm.poiDistM(), originalCachedZoneNm.inqDt(),
                    plantedLatKey, plantedLotKey);
        } else {
            jdbcTemplate.update("DELETE FROM parking.tb_zone_nm_cache WHERE lat_key = ? AND lot_key = ?",
                    plantedLatKey, plantedLotKey);
        }
        plantedLatKey = null;
        plantedLotKey = null;
        originalCachedZoneNm = null;
    }

    private void setCoefficient(String vrblCd, String value) {
        int n = jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?::numeric"
                + " WHERE anls_type_cd = 'LIFEZONE' AND vrbl_cd = ?", value, vrblCd);
        assertThat(n).as("LIFEZONE/%s 행", vrblCd).isEqualTo(1);
    }

    private double coefficient(String vrblCd) {
        return analysisWeightService.coefficient("LIFEZONE", vrblCd).doubleValue();
    }

    /** 단속 보유 구간 끝에서 최대 365일 (조회 상한 366일 안) */
    private LocalDate[] window() {
        LocalDate end = LocalDate.parse(range.getDataEndYmd(), YMD);
        LocalDate bgng = end.minusDays(364);
        LocalDate dataBgng = LocalDate.parse(range.getDataBgngYmd(), YMD);
        return new LocalDate[] {bgng.isBefore(dataBgng) ? dataBgng : bgng, end};
    }

    private LifezoneCngstResultVO analyze(int gridSz, LifezoneClsfMthd mthd) {
        LocalDate[] w = window();
        LifezoneCngstRequest req = new LifezoneCngstRequest();
        req.setGridSz(gridSz);
        req.setBgngYmd(w[0].format(YMD));
        req.setEndYmd(w[1].format(YMD));
        req.setClsfMthd(mthd == null ? null : mthd.name());
        return cngstService.analyze(req);
    }

    /** 독립 오라클 — 달력 월 수(부분월 일수 비례) */
    private static double months(LocalDate bgng, LocalDate end) {
        double sum = 0;
        for (LocalDate m = bgng.withDayOfMonth(1); !m.isAfter(end); m = m.plusMonths(1)) {
            LocalDate from = bgng.isAfter(m) ? bgng : m;
            LocalDate mEnd = m.withDayOfMonth(m.lengthOfMonth());
            LocalDate to = end.isBefore(mEnd) ? end : mEnd;
            sum += (double) (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1) / m.lengthOfMonth();
        }
        return sum;
    }

    private static int gradeCnt(LifezoneCngstResultVO r) {
        return r.getSevereCnt() + r.getWarnCnt() + r.getCautionCnt() + r.getNormalCnt() + r.getLowCnt();
    }

    @Test
    @DisplayName("불변식 — 분류방법 1종(CNGST_ABSOLUTE): tot = 5등급 합 = grdTrgt = cells · 근거 없음·면수 하한 미만 격자는 cells 에 없다 · noEnf + ref + tot = 공급>0")
    void serviceInvariantsForAllMethods() {
        int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;
        int occVl = (int) Math.round(coefficient("PCSHR_CRTR_VL") * 10000);
        double minCpcty = coefficient("MIN_CPCTY_VL");
        LocalDate[] w = window();
        int mapperCnt = mapper.selectCngstCells(param(gridSz, null, null)).size();   // 비교용 — 구간이 달라도 칸 수는 공급만으로 정해진다

        // 혼잡도 허용 목록이 1종으로 확정됐다(나머지 상수는 400 — httpRejectsOutOfListValues)
        for (LifezoneClsfMthd mthd : List.of(LifezoneClsfMthd.CNGST_ABSOLUTE)) {
            Map<String, String> cacheBefore = cachedZoneNames();
            LifezoneCngstResultVO r = analyze(gridSz, mthd);
            assertThat(r.getClsfMthd()).isEqualTo(mthd.name());
            assertThat(r.getAvailableClsfMthds()).containsExactly("CNGST_ABSOLUTE");
            assertThat(r.getAnlsTrgtCd()).isEqualTo("CNGST");
            assertThat(r.getWeights()).isEmpty();
            assertThat(r.getTotGridCnt()).isEqualTo(r.getCells().size());
            assertThat(gradeCnt(r)).as(mthd + " 5등급 합 = tot").isEqualTo(r.getTotGridCnt());
            assertThat(r.getGrdTrgtGridCnt()).as(mthd + " grdTrgt = tot (하위호환)").isEqualTo(r.getTotGridCnt());
            assertThat(r.getNoEnfGridCnt() + r.getRefGridCnt() + r.getTotGridCnt())
                    .as(mthd + " noEnf + ref + tot = 공급>0 격자 수 (두 제외 사유는 겹쳐 세지 않는다)")
                    .isEqualTo(mapperCnt);
            assertThat(r.getNoEnfGridCnt()).isNotNegative();
            assertThat(r.getRefGridCnt()).isNotNegative();
            assertThat(r.getOccUsedVl()).isEqualByComparingTo(BigDecimal.valueOf(occVl / 10000.0));
            assertThat(r.getGradeCriteria()).hasSize(5);
            // 🔁 2026-10-08 — 단위가 점(H×100 · 상한 없음 ⑪)이고 범례는 점수 소수 첫째 자리(.0 생략)다
            assertThat(r.getUnit()).isEqualTo("점");
            assertThat(r.getGradeCriteria().get(4).getLgndTxt()).endsWith("점 미만)");
            assertThat(r.getGradeCriteria().get(0).getLgndTxt()).matches("심각 \\(\\d{1,4}(\\.\\d)?점 이상\\)");
            assertThat(r.getGradeCriteria()).allSatisfy(g ->
                    assertThat(g.getLgndTxt()).as("'.0점' 은 지운다").doesNotContain(".0점"));

            int over1 = 0;
            Integer prev = null;
            for (LifezoneCngstCellVO c : r.getCells()) {
                assertThat(c.getGridCd()).matches(gridSz + "m_\\d+_\\d+");
                // 종전 "전 셀 폴백(… 격자 {코드})" → 캐시 이름이 있으면 그 이름 · 없으면 폴백(셀은 외부를 부르지 않는다)
                assertCellZoneNm(c, cacheBefore);
                assertThat(c.getPkltCpctySum()).isPositive();
                if (prev != null) {
                    assertThat(c.getAnlsVl()).as("혼잡지수 내림차순").isLessThanOrEqualTo(prev);
                }
                prev = c.getAnlsVl();
                assertThat(c.getNoEnfYn()).as("근거 없는 격자는 cells 에서 제외된다").isEqualTo("N");
                assertThat(c.getCrdnCnt() + c.getCvlcptCnt()).as("남은 칸은 단속 또는 민원 근거가 있다").isPositive();
                assertThat(c.getAnlsVl()).isGreaterThanOrEqualTo(occVl);
                assertThat(c.getRefYn()).as("면수 하한 미만 격자는 cells 에서 제외된다").isEqualTo("N");
                assertThat((double) c.getPkltCpctySum()).as("남은 칸은 면수 ≥ MIN_CPCTY_VL")
                        .isGreaterThanOrEqualTo(minCpcty);
                if (c.getAnlsVl() > LifezoneGradeScale.CNGST_FULL_VL) {
                    over1++;
                }
            }
            // 🔁 ⑪ — over1GridCnt = 100점 초과(anlsVl > 10000) 격자 수. 상한이 없으므로 셀에서 그대로 센다
            assertThat(r.getOver1GridCnt()).as(mthd + " over1 = 100점 초과 격자 수").isEqualTo(over1);

            // 순위 — cells 중 심각·경고·주의(listed) 전부 순서대로(limit 생략 = 상한 없음), dtl 빈 배열
            //        (제외 격자는 이미 cells 에 없다 · 보통·낮음은 지도·요약에만 남는다)
            Map<String, LifezoneCngstCellVO> byCd = r.getCells().stream()
                    .collect(Collectors.toMap(LifezoneCngstCellVO::getGridCd, Function.identity()));
            List<LifezoneCngstCellVO> listed = r.getCells().stream()
                    .filter(c -> LifezoneGrade.valueOf(c.getGrade()).isListed()).toList();
            assertThat(r.getRanking())
                    .as(mthd + " limit 생략이면 심각·경고·주의 셀 전부가 순위에 실린다 (15개 상한 없음)")
                    .hasSameSizeAs(listed)
                    .hasSize(r.getSevereCnt() + r.getWarnCnt() + r.getCautionCnt());
            for (int i = 0; i < r.getRanking().size(); i++) {
                LifezoneRankVO rk = r.getRanking().get(i);
                LifezoneCngstCellVO c = byCd.get(rk.getGridCd());
                assertThat(LifezoneGrade.valueOf(rk.getGrade()).isListed())
                        .as("ranking 은 SEVERE·WARN·CAUTION 만 싣는다 (%s)", rk.getGrade()).isTrue();
                assertThat(rk.getGridCd()).as("rank 는 cells 중 listed 만 순서대로").isEqualTo(listed.get(i).getGridCd());
                assertThat(rk.getRnk()).isEqualTo(i + 1);
                assertThat(c.getRefYn()).isEqualTo("N");
                assertThat(c.getNoEnfYn()).isEqualTo("N");
                assertThat(rk.getAnlsVl()).isEqualTo(c.getAnlsVl());
                assertThat(rk.getGrade()).isEqualTo(c.getGrade());
                assertThat(rk.getDtl()).isEmpty();
                // 순위 구역명 = 격자 중심 최근접 POI, 외부 조회 실패(키 없음 등)면 셀 폴백 — 어느 쪽이든 비지 않는다
                assertThat(rk.getCntrPoiNm()).as("순위 구역명은 폴백까지 거쳐 비지 않는다").isNotBlank();
            }
        }
        assertThat(w[0]).isBeforeOrEqualTo(w[1]);
    }

    @Test
    @DisplayName("순위 밖 셀도 격자 중심 키의 캐시(POI) 이름을 싣는다 (전 셀 구역명)")
    void unrankedCellCarriesCachedZoneName() {
        int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;
        LifezoneCngstResultVO first = analyze(gridSz, LifezoneClsfMthd.CNGST_ABSOLUTE);
        Set<String> ranked = first.getRanking().stream().map(LifezoneRankVO::getGridCd).collect(Collectors.toSet());
        List<LifezoneCngstCellVO> unranked = first.getCells().stream()
                .filter(c -> !ranked.contains(c.getGridCd()))
                .filter(c -> c.getCtrLat() != null && c.getCtrLot() != null)
                .toList();
        assumeThat(unranked).as("%dm 순위 밖 셀이 0개다 — 데이터 부재는 실패가 아니다", gridSz).isNotEmpty();
        LifezoneCngstCellVO target = unranked.get(0);

        String planted = plantZoneName(target.getCtrLat(), target.getCtrLot());

        LifezoneCngstCellVO again = analyze(gridSz, LifezoneClsfMthd.CNGST_ABSOLUTE).getCells().stream()
                .filter(c -> target.getGridCd().equals(c.getGridCd()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("재조회 cells 에 " + target.getGridCd() + " 가 없다"));
        assertThat(again.getCntrPoiNm())
                .as("%s — 순위 밖 셀도 격자 중심 키(%s, %s)에 심은 캐시 이름이 실려야 한다",
                        target.getGridCd(), plantedLatKey, plantedLotKey)
                .isEqualTo(planted);
    }

    /**
     * 셀 구역명 = 호출 전 캐시 이름(있으면) · 아니면 폴백({@code … 격자 {코드}}). 셀 SQL 이 순위 resolver 보다
     * 먼저 돌아 같은 요청이 채운 키는 셀에 반영될 수 없다 — 호출 뒤 스냅샷을 허용하면 외부 조회 회귀를 놓친다.
     */
    private static void assertCellZoneNm(LifezoneCngstCellVO c, Map<String, String> cacheBefore) {
        String key = cacheKey(c.getCtrLat(), c.getCtrLot());
        String before = cacheBefore.get(key);
        if (before != null) {
            assertThat(c.getCntrPoiNm()).as("%s — 캐시(%s)에 이름이 있으면 그 이름", c.getGridCd(), key).isEqualTo(before);
        } else {
            assertThat(c.getCntrPoiNm()).as("%s — 캐시가 없는 셀은 폴백(셀은 외부를 부르지 않는다)", c.getGridCd())
                    .endsWith("격자 " + c.getGridCd());
        }
        assertThat(c.getCntrPoiNm()).doesNotEndWith(" 일대").isNotEqualTo("위치 미상");
    }

    /** 구역명 캐시 중 셀 이름으로 쓰이는 행(출처 {@code POI}·{@code REVERSE_GEOCODE} · 이름 있음) — 키 → 이름. */
    private Map<String, String> cachedZoneNames() {
        Map<String, String> names = new HashMap<>();
        jdbcTemplate.query("SELECT lat_key, lot_key, zone_nm FROM parking.tb_zone_nm_cache"
                        + " WHERE zone_nm_src IN ('POI', 'REVERSE_GEOCODE') AND zone_nm IS NOT NULL",
                rs -> {
                    names.put(cacheKey(rs.getBigDecimal(1), rs.getBigDecimal(2)), rs.getString(3));
                });
        return names;
    }

    /** 캐시 키 문자열 — {@code ZoneNameResolver.key()} 와 같은 소수 5자리 HALF_UP. */
    private static String cacheKey(BigDecimal lat, BigDecimal lot) {
        return lat.setScale(5, RoundingMode.HALF_UP).toPlainString() + ","
                + lot.setScale(5, RoundingMode.HALF_UP).toPlainString();
    }

    /** 격자 중심 키에 테스트 이름을 {@code POI} 출처로 심는다. 원래 행은 먼저 떠 둔다(복원용). */
    private String plantZoneName(BigDecimal ctrLat, BigDecimal ctrLot) {
        BigDecimal latKey = ctrLat.setScale(5, RoundingMode.HALF_UP);
        BigDecimal lotKey = ctrLot.setScale(5, RoundingMode.HALF_UP);
        List<CachedZoneNm> original = jdbcTemplate.query(
                "SELECT zone_nm, zone_nm_src, poi_dist_m, inq_dt FROM parking.tb_zone_nm_cache"
                        + " WHERE lat_key = ? AND lot_key = ?",
                (rs, rowNum) -> new CachedZoneNm(rs.getString(1), rs.getString(2),
                        (Integer) rs.getObject(3), rs.getTimestamp(4)),
                latKey, lotKey);
        originalCachedZoneNm = original.isEmpty() ? null : original.get(0);
        plantedLatKey = latKey;
        plantedLotKey = lotKey;

        String planted = "테스트-격자중심-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO parking.tb_zone_nm_cache (lat_key, lot_key, zone_nm, zone_nm_src)"
                        + " VALUES (?, ?, ?, 'POI')"
                        + " ON CONFLICT (lat_key, lot_key) DO UPDATE"
                        + " SET zone_nm = EXCLUDED.zone_nm, zone_nm_src = EXCLUDED.zone_nm_src",
                latKey, lotKey, planted);
        return planted;
    }

    /** 구역명 캐시 1행의 복원 대상 4열. */
    private record CachedZoneNm(String zoneNm, String zoneNmSrc, Integer poiDistM, Timestamp inqDt) {
    }

    @Test
    @DisplayName("산식 — 셀마다 anlsVl = round((occ + 추정량/S)×10000) (= 점수 H×100 × 100 · 상한 없음) · 추정량 = (m·Enf + w_cv·Cv)/30·g_geo")
    void formulaMatchesIndependentRecomputation() {
        int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;
        LocalDate[] w = window();
        double mo = months(w[0], w[1]);
        double occ = coefficient("PCSHR_CRTR_VL");
        double m = coefficient("DSCL_MLTP");
        double fix = coefficient("FIX_WGVL");
        double geo = coefficient("GEO_CVRG_CRCT_VL");
        double wcv = coefficient("CVLCPT_WGVL");
        double minCpcty = coefficient("MIN_CPCTY_VL");

        LifezoneSupplyQueryParam p = param(gridSz, null, null);
        p.setBgngDt(w[0].atStartOfDay());
        p.setEndDtExcl(w[1].plusDays(1).atStartOfDay());
        Map<String, LifezoneCngstRow> rows = mapper.selectCngstCells(p).stream()
                .collect(Collectors.toMap(r -> gridSz + "m_" + r.getGx() + "_" + r.getGy(), Function.identity()));

        LifezoneCngstResultVO r = analyze(gridSz, null);
        assertThat(r.getClsfMthd()).as("생략 = 유형 기본값").isEqualTo("CNGST_ABSOLUTE");
        // 독립 재계산 — 추정량 0(근거 없음) → noEnf, 그 밖에 면수 < 하한 → ref, 나머지 = cells
        Set<String> expectedKept = new HashSet<>();
        int expectedNoEnf = 0, expectedRef = 0;
        for (Map.Entry<String, LifezoneCngstRow> e : rows.entrySet()) {
            LifezoneCngstRow row = e.getValue();
            double est = (m * (row.getNPda() + row.getNManual() + fix * row.getNFixed()) / mo
                    + wcv * row.getCvlcptCnt() / mo) / 30.0 * geo;
            if (!(est > 0)) {
                expectedNoEnf++;
            } else if (row.getPkltCpctySum() < minCpcty) {
                expectedRef++;
            } else {
                expectedKept.add(e.getKey());
            }
        }
        assertThat(r.getCells()).extracting(LifezoneCngstCellVO::getGridCd)
                .containsExactlyInAnyOrderElementsOf(expectedKept);
        assertThat(r.getNoEnfGridCnt()).isEqualTo(expectedNoEnf);
        assertThat(r.getRefGridCnt()).isEqualTo(expectedRef);
        for (LifezoneCngstCellVO c : r.getCells()) {
            LifezoneCngstRow row = rows.get(c.getGridCd());
            assertThat(row).isNotNull();
            double enf = (row.getNPda() + row.getNManual() + fix * row.getNFixed()) / mo;
            double est = (m * enf + wcv * row.getCvlcptCnt() / mo) / 30.0 * geo;
            double h = occ + est / row.getPkltCpctySum();
            assertThat(c.getAnlsVl()).as(c.getGridCd()).isEqualTo((int) Math.round(h * 10000));
            assertThat(c.getExcsPrkPrsmpVl().doubleValue()).isCloseTo(est, org.assertj.core.data.Offset.offset(0.051));
            assertThat(c.getCrdnCnt()).isEqualTo(row.getNFixed() + row.getNPda() + row.getNManual());
            assertThat(c.getCvlcptCnt()).isEqualTo(row.getCvlcptCnt());
            assertThat(c.getNoEnfYn()).isEqualTo("N");
        }
    }

    @Test
    @DisplayName("CNGST_ABSOLUTE — 경계 = 설정 점수×100 · BND2 를 BND1 과 사이로 내리면 주의 이상이 줄지 않는다")
    void absoluteBoundsComeFromSettings() {
        LifezoneCngstResultVO before = analyze(LifezoneSupply.DEFAULT_GRID_SZ, LifezoneClsfMthd.CNGST_ABSOLUTE);
        List<Integer> expected = List.of(
                (int) Math.round(coefficient("CNGST_GRD_BNDRY4") * 100),
                (int) Math.round(coefficient("CNGST_GRD_BNDRY3") * 100),
                (int) Math.round(coefficient("CNGST_GRD_BNDRY2") * 100),
                (int) Math.round(coefficient("CNGST_GRD_BNDRY1") * 100), 1);
        assertThat(before.getGradeCriteria()).extracting(LifezoneGradeCriterionVO::getMinVl)
                .containsExactlyElementsOf(expected);
        int cautionUpBefore = before.getSevereCnt() + before.getWarnCnt() + before.getCautionCnt();

        // 새 BND2 = BND1 과 BND2 의 중간(점) — 값을 박지 않고 설정에서 끌어온다(단조 유지)
        BigDecimal lowered = BigDecimal.valueOf(coefficient("CNGST_GRD_BNDRY1"))
                .add(BigDecimal.valueOf(coefficient("CNGST_GRD_BNDRY2")))
                .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
        setCoefficient("CNGST_GRD_BNDRY2", lowered.toPlainString());
        LifezoneCngstResultVO after = analyze(LifezoneSupply.DEFAULT_GRID_SZ, LifezoneClsfMthd.CNGST_ABSOLUTE);
        assertThat(after.getGradeCriteria().get(2).getMinVl())
                .isEqualTo(lowered.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact());
        assertThat(after.getSevereCnt() + after.getWarnCnt() + after.getCautionCnt())
                .isGreaterThanOrEqualTo(cautionUpBefore);
        assertThat(gradeCnt(after)).isEqualTo(after.getTotGridCnt());
    }

    @Test
    @DisplayName("CVLCPT_WGVL=0 → 명세 방식: 단속 0 칸이 제외된다 · 제외 칸이 늘거나 같다 · 기본(1.0)은 단속0·민원0 칸만 제외")
    void cvlcptWeightZeroFallsBackToSpec() {
        LifezoneCngstResultVO dflt = analyze(LifezoneSupply.DEFAULT_GRID_SZ, null);
        if (coefficient("CVLCPT_WGVL") > 0) {
            assertThat(dflt.getCells()).allSatisfy(c ->
                    assertThat(c.getCrdnCnt() + c.getCvlcptCnt()).as(c.getGridCd()).isPositive());
        }
        setCoefficient("CVLCPT_WGVL", "0");
        LifezoneCngstResultVO spec = analyze(LifezoneSupply.DEFAULT_GRID_SZ, null);
        assertThat(spec.getCells()).allSatisfy(c -> {
            assertThat(c.getCrdnCnt()).as(c.getGridCd()).isPositive();
            assertThat(c.getNoEnfYn()).isEqualTo("N");
        });
        assertThat(spec.getNoEnfGridCnt()).isGreaterThanOrEqualTo(dflt.getNoEnfGridCnt());
        // 공급>0 격자 수(= tot + 두 제외)는 가중치와 무관하다
        assertThat(spec.getTotGridCnt() + spec.getNoEnfGridCnt() + spec.getRefGridCnt())
                .isEqualTo(dflt.getTotGridCnt() + dflt.getNoEnfGridCnt() + dflt.getRefGridCnt());
        assertThat(spec.getTotGridCnt()).isEqualTo(spec.getGrdTrgtGridCnt()).isEqualTo(gradeCnt(spec));
    }

    @Test
    @DisplayName("MIN_CPCTY_VL 을 올리면 tot 가 줄고 ref 가 는다(단조) · 남은 셀은 면수 ≥ 하한 · H 는 그대로 · noEnf 불변")
    void minCapacityExcludesSmallSupplyCells() {
        LifezoneCngstResultVO base = analyze(LifezoneSupply.DEFAULT_GRID_SZ, null);   // 분류 검증이 아니다 — 유형 기본값(③)
        double raised = Math.max(coefficient("MIN_CPCTY_VL"), 1.0) * 2.5;   // 현재 하한보다 확실히 높게 — 수치 하드코딩 없이
        setCoefficient("MIN_CPCTY_VL", BigDecimal.valueOf(raised).toPlainString());
        LifezoneCngstResultVO r = analyze(LifezoneSupply.DEFAULT_GRID_SZ, null);

        assertThat(r.getTotGridCnt()).as("하한을 올리면 tot 가 줄거나 같다").isLessThanOrEqualTo(base.getTotGridCnt());
        assertThat(r.getRefGridCnt()).as("하한을 올리면 ref 가 늘거나 같다").isGreaterThanOrEqualTo(base.getRefGridCnt());
        assertThat(r.getRefGridCnt() - base.getRefGridCnt()).as("tot 감소분 = ref 증가분")
                .isEqualTo(base.getTotGridCnt() - r.getTotGridCnt());
        assertThat(r.getNoEnfGridCnt()).as("근거 없음 판정은 면수 하한과 무관하다").isEqualTo(base.getNoEnfGridCnt());
        assertThat(r.getTotGridCnt()).isEqualTo(r.getCells().size()).isEqualTo(gradeCnt(r));

        // 남은 셀은 면수 ≥ 하한이고, base 의 부분집합이며, H(anlsVl)는 하한과 무관하다 (등급은 분모가 바뀌어 달라질 수 있다)
        Map<String, LifezoneCngstCellVO> b = base.getCells().stream()
                .collect(Collectors.toMap(LifezoneCngstCellVO::getGridCd, Function.identity()));
        assertThat(r.getCells()).allSatisfy(c -> {
            assertThat((double) c.getPkltCpctySum()).isGreaterThanOrEqualTo(raised);
            assertThat(c.getRefYn()).isEqualTo("N");
            assertThat(b).containsKey(c.getGridCd());
            assertThat(c.getAnlsVl()).isEqualTo(b.get(c.getGridCd()).getAnlsVl());
        });
        long baseAtOrAbove = base.getCells().stream().filter(c -> c.getPkltCpctySum() >= raised).count();
        assertThat(r.getCells()).as("base 중 면수 ≥ 새 하한인 칸이 그대로 남는다").hasSize((int) baseAtOrAbove);

        // 순위 행은 전부 cells 에 있다 — "심각인데 순위엔 없음" 이 생기지 않는다
        Set<String> cellCds = r.getCells().stream().map(LifezoneCngstCellVO::getGridCd).collect(Collectors.toSet());
        assertThat(r.getRanking()).allSatisfy(rk -> assertThat(cellCds).contains(rk.getGridCd()));
        // 순위는 심각·경고·주의만 싣는다
        assertThat(r.getRanking()).allSatisfy(rk -> assertThat(LifezoneGrade.valueOf(rk.getGrade()).isListed()).isTrue());
        // limit 생략 = 상한 없음: cells 중 listed 전부가 같은 순서로 실린다
        List<String> listedCds = r.getCells().stream()
                .filter(c -> LifezoneGrade.valueOf(c.getGrade()).isListed())
                .map(LifezoneCngstCellVO::getGridCd).toList();
        assertThat(r.getRanking()).extracting(LifezoneRankVO::getGridCd).containsExactlyElementsOf(listedCds);
        assertThat(r.getRanking()).hasSize(r.getSevereCnt() + r.getWarnCnt() + r.getCautionCnt());
    }

    @Test
    @DisplayName("occ +0.05 → 전 격자 anlsVl +500(±1) · 혼잡 1.0 기준(CNGST_ABSOLUTE) 경계 불변")
    void occShiftMovesAllCellsMonotonically() {
        // 혼잡도는 CNGST_ABSOLUTE 1종. 종전 'QUANTILE 경계도 +500' 단언은
        //    LifezoneGradeScaleTest.Quantile 단위 테스트로 이관했다
        double occ = coefficient("PCSHR_CRTR_VL");
        LifezoneCngstResultVO a0 = analyze(LifezoneSupply.DEFAULT_GRID_SZ, LifezoneClsfMthd.CNGST_ABSOLUTE);

        setCoefficient("PCSHR_CRTR_VL", BigDecimal.valueOf(occ).add(new BigDecimal("0.05")).toPlainString());
        LifezoneCngstResultVO a1 = analyze(LifezoneSupply.DEFAULT_GRID_SZ, LifezoneClsfMthd.CNGST_ABSOLUTE);

        Map<String, LifezoneCngstCellVO> before = a0.getCells().stream()
                .collect(Collectors.toMap(LifezoneCngstCellVO::getGridCd, Function.identity()));
        // 🔁 ⑪ 상한이 없으므로 모든 격자가 그대로 +500(반올림 ±1) 이다
        assertThat(a1.getCells()).hasSameSizeAs(a0.getCells()).allSatisfy(c -> {
            LifezoneCngstCellVO b = before.get(c.getGridCd());
            assertThat(c.getAnlsVl() - (b.getAnlsVl() + 500)).isBetween(-1, 1);
        });
        assertThat(a1.getNoEnfGridCnt()).isEqualTo(a0.getNoEnfGridCnt());
        assertThat(a1.getGradeCriteria()).extracting(LifezoneGradeCriterionVO::getMinVl)
                .containsExactlyElementsOf(a0.getGradeCriteria().stream()
                        .map(LifezoneGradeCriterionVO::getMinVl).toList());
        assertThat(a1.getOccUsedVl()).isEqualByComparingTo(BigDecimal.valueOf(occ).add(new BigDecimal("0.05")));
    }

    @Test
    @DisplayName("GEO_CVRG_CRCT_VL=1.0 → H 최대가 내려간다(보정 작동)")
    void geoCorrectionLowersMax() {
        LifezoneCngstResultVO base = analyze(LifezoneSupply.DEFAULT_GRID_SZ, null);   // 분류 검증이 아니다 — 유형 기본값(③)
        org.junit.jupiter.api.Assumptions.assumeTrue(base.getGrdTrgtGridCnt() > 0
                && coefficient("GEO_CVRG_CRCT_VL") > 1.0);
        setCoefficient("GEO_CVRG_CRCT_VL", "1.0");
        LifezoneCngstResultVO r = analyze(LifezoneSupply.DEFAULT_GRID_SZ, null);
        // 🔁 ⑪ 점수 상한이 없어 anlsVl 최대로 바로 본다(내림차순이라 첫 셀이 최대)
        assertThat(r.getCells().get(0).getAnlsVl()).isLessThan(base.getCells().get(0).getAnlsVl());
    }

    @Test
    @DisplayName("격자 4종 · 지역 필터 — 불변식 유지 · 목록 밖 분류방법은 400")
    void allGridSizesAndBadMethod() {
        for (int gridSz : LifezoneSupply.GRID_SZS) {
            LifezoneCngstResultVO r = analyze(gridSz, null);
            assertThat(r.getTotGridCnt()).isEqualTo(r.getGrdTrgtGridCnt()).isEqualTo(r.getCells().size());
            assertThat(gradeCnt(r)).isEqualTo(r.getTotGridCnt());
            assertThat(r.getNoEnfGridCnt()).isNotNegative();
            assertThat(r.getRefGridCnt()).isNotNegative();
            assertThat(r.getCells()).allSatisfy(c -> {
                assertThat(c.getNoEnfYn()).isEqualTo("N");
                assertThat(c.getRefYn()).isEqualTo("N");
            });
            assertThat(r.getGridSz()).isEqualTo(gridSz);
        }
        LifezoneCngstRequest req = new LifezoneCngstRequest();
        LocalDate[] w = window();
        req.setBgngYmd(w[0].format(YMD));
        req.setEndYmd(w[1].format(YMD));
        req.setClsfMthd("EQUAL_INTERVAL");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cngstService.analyze(req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
        req.setClsfMthd(null);
        req.setGridSz(200);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cngstService.analyze(req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HTTP 핸들러 (GET /api/analysis/lifezone/congestion)
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String CNGST_URL = "/api/analysis/lifezone/congestion";

    /** 단속 보유 구간 끝에서 최대 365일 + 주어진 추가 파라미터 */
    private Map<String, Object> httpParams(Map<String, Object> extra) {
        LocalDate[] w = window();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bgngYmd", w[0].format(YMD));
        params.put("endYmd", w[1].format(YMD));
        params.putAll(extra);
        return params;
    }

    private ResponseEntity<String> call(Map<String, Object> params) {
        StringBuilder uri = new StringBuilder(CNGST_URL);
        String sep = "?";
        for (String key : params.keySet()) {
            uri.append(sep).append(key).append("={").append(key).append('}');
            sep = "&";
        }
        return restTemplate.getForEntity(uri.toString(), String.class, params);
    }

    private JsonNode callOk(Map<String, Object> params) throws Exception {
        ResponseEntity<String> res = call(params);
        assertThat(res.getStatusCode().value()).as("%s %s", CNGST_URL, params).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        return JSON.readTree(res.getBody());
    }

    @Test
    @DisplayName("HTTP 200 — 기본 500m · clsfMthd 생략=CNGST_ABSOLUTE · availableClsfMthds=[CNGST_ABSOLUTE] · 요약 세 숫자 · availableClsfMthds · 민원건수 공통 필드")
    void httpDefaultsAndSummary() throws Exception {
        JsonNode body = callOk(httpParams(Map.of()));

        assertThat(body.path("gridSz").asInt()).isEqualTo(LifezoneSupply.DEFAULT_GRID_SZ);
        assertThat(body.path("anlsTrgtCd").asText()).isEqualTo(LifezoneTarget.CNGST.name());
        assertThat(body.path("clsfMthd").asText()).isEqualTo(LifezoneClsfMthd.CNGST_ABSOLUTE.name());
        List<String> mthds = new ArrayList<>();
        body.path("availableClsfMthds").forEach(n -> mthds.add(n.asText()));
        assertThat(mthds).containsExactly(LifezoneClsfMthd.CNGST_ABSOLUTE.name());

        // 🔴 요약 — tot = 5등급 합 = grdTrgt (성립) · noEnf·ref = 제외된 격자 수
        for (String key : List.of("totGridCnt", "grdTrgtGridCnt", "noEnfGridCnt", "refGridCnt",
                "over1GridCnt", "occUsedVl", "severeCnt", "warnCnt", "cautionCnt", "normalCnt", "lowCnt",
                "dataBgngYmd", "dataEndYmd", "gradeCriteria", "cells", "ranking", "weights",
                "unit", "sectionTitle", "sectionSub", "availableTargets")) {
            assertThat(body.has(key)).as("응답 키 %s", key).isTrue();
        }
        int tot = body.path("totGridCnt").asInt();
        int grdTrgt = body.path("grdTrgtGridCnt").asInt();
        int noEnf = body.path("noEnfGridCnt").asInt();
        assertThat(tot).isPositive().isEqualTo(grdTrgt);
        assertThat(noEnf).isNotNegative();
        assertThat(body.path("refGridCnt").asInt()).isNotNegative();
        assertThat(body.path("severeCnt").asInt() + body.path("warnCnt").asInt()
                + body.path("cautionCnt").asInt() + body.path("normalCnt").asInt()
                + body.path("lowCnt").asInt()).isEqualTo(tot);
        body.path("cells").forEach(c -> {
            assertThat(c.path("noEnfYn").asText()).isEqualTo("N");
            assertThat(c.path("refYn").asText()).isEqualTo("N");
        });
        assertThat(body.path("cells").size()).isEqualTo(tot);
        assertThat(body.path("weights").size()).isZero();
        assertThat(body.path("occUsedVl").decimalValue())
                .isEqualByComparingTo(BigDecimal.valueOf(coefficient("PCSHR_CRTR_VL")));
        assertThat(body.path("dataBgngYmd").asText()).isEqualTo(range.getDataBgngYmd());
        assertThat(body.path("dataEndYmd").asText()).isEqualTo(range.getDataEndYmd());

        // 셀 신설 필드(데이터표준사전 식별자)가 직렬화된다
        JsonNode cell = body.path("cells").path(0);
        for (String key : List.of("gridCd", "anlsVl", "grade", "pkltCpctySum", "pkltCnt", "crdnCnt",
                "cvlcptCnt", "excsPrkPrsmpVl", "noEnfYn", "refYn")) {
            assertThat(cell.has(key)).as("셀 키 %s", key).isTrue();
        }
        // 🔁 ⑪ 원 지수 필드는 걷었다 — 점수 = 지수 × 100 이라 중복이다
        assertThat(cell.has("cngstIdxVl")).as("원 지수 필드 제거").isFalse();
    }

    @Test
    @DisplayName("HTTP 400 — clsfMthd=EQUAL_INTERVAL·QUANTILE(목록 밖) · gridSz=200(목록 밖) / gridSz=1000 은 200")
    void httpRejectsOutOfListValues() throws Exception {
        assertThat(call(httpParams(Map.of("clsfMthd", "EQUAL_INTERVAL"))).getStatusCode().value())
                .isEqualTo(400);
        // 종전 기본값 QUANTILE 도 이제 목록 밖이다(혼잡도 대표 1건 — ①)
        assertThat(call(httpParams(Map.of("clsfMthd", LifezoneClsfMthd.QUANTILE.name()))).getStatusCode().value())
                .isEqualTo(400);
        assertThat(call(httpParams(Map.of("gridSz", 200))).getStatusCode().value()).isEqualTo(400);
        assertThat(callOk(httpParams(Map.of("gridSz", 1000))).path("gridSz").asInt()).isEqualTo(1000);
    }

    @Test
    @DisplayName("🔴 보유 구간 전체가 366일을 넘으면 400 — 화면 '이 구간으로 맞추기' 는 끝에서 366일로 잘라 보내야 한다")
    void fullDataRangeVersusMaxRange() throws Exception {
        LocalDate bgng = LocalDate.parse(range.getDataBgngYmd(), YMD);
        LocalDate end = LocalDate.parse(range.getDataEndYmd(), YMD);
        long days = java.time.temporal.ChronoUnit.DAYS.between(bgng, end) + 1;
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("bgngYmd", range.getDataBgngYmd());
        full.put("endYmd", range.getDataEndYmd());
        // 수급(fullDataRangeFitsWithinMaxRange)과 달리 단속 보유 구간은 1년을 넘을 수 있다 — 구간 길이로 기대값을 가른다
        assertThat(call(full).getStatusCode().value())
                .as("보유 구간 %d일 (상한 366일)", days)
                .isEqualTo(days > 366 ? 400 : 200);

        // 끝에서 366일(양끝 포함)로 자른 구간은 항상 200 이다 — 프론트가 보내야 할 값
        LocalDate clamped = end.minusDays(365);
        Map<String, Object> fit = new LinkedHashMap<>();
        fit.put("bgngYmd", (clamped.isBefore(bgng) ? bgng : clamped).format(YMD));
        fit.put("endYmd", range.getDataEndYmd());
        assertThat(callOk(fit).path("totGridCnt").asInt()).isPositive();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 분석 계수 일시 입력 (vrblVls)
    //   🔴 입력값은 이번 요청에만 쓰인다 — 설정 표를 바꾸지 않고, 기대값은 DB 현재 설정값에서 파생한다.
    // ═══════════════════════════════════════════════════════════════════════════

    /** 혼잡도 탭 허용 목록 — 설정 표 sort_seq 순(테스트가 다시 적는다). */
    private static final List<String> CNGST_VRBLS = List.of("PCSHR_CRTR_VL",
            "CNGST_GRD_BNDRY1", "CNGST_GRD_BNDRY2", "CNGST_GRD_BNDRY3", "CNGST_GRD_BNDRY4",
            "DSCL_MLTP", "FIX_WGVL", "GEO_CVRG_CRCT_VL", "CVLCPT_WGVL", "MIN_CPCTY_VL");

    private static final String SETTINGS_SQL = "SELECT * FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd";

    // 숨김(expsr_yn='N') 5계수(PCSHR_CRTR_VL·DSCL_MLTP·FIX_WGVL·CVLCPT_WGVL·MIN_CPCTY_VL)는 일시 조정이 400 이다.
    //    V1~V5 의 조정 대상을 노출 계수(경계 CNGST_GRD_BNDRY1 · 좌표 보정 GEO_CVRG_CRCT_VL)로 바꿨다. 적용 내역은 여전히 10행이다.

    @Test
    @DisplayName("V1. vrblVls CNGST_GRD_BNDRY1 을 BND1~BND2 중간으로 → gradeCriteria 의 BND1 경계만 입력값 · anlsVl 그대로 · vrblAjmt 가 입력값 · 설정 표 그대로")
    void boundaryInputMovesGradeCriteria() throws Exception {
        List<Map<String, Object>> settingsBefore = jdbcTemplate.queryForList(SETTINGS_SQL);
        BigDecimal b1 = stngVl("CNGST_GRD_BNDRY1");
        BigDecimal b2 = stngVl("CNGST_GRD_BNDRY2");
        BigDecimal newB1 = b1.add(b2).divide(BigDecimal.valueOf(2));   // 단조 유지(BND1 < 새 값 < BND2)

        JsonNode base = callOk(httpParams(Map.of()));
        JsonNode adjusted = callOk(httpParams(Map.of("vrblVls",
                "{\"CNGST_GRD_BNDRY1\":" + newB1.toPlainString() + "}")));

        Map<String, Integer> before = new LinkedHashMap<>();
        base.path("cells").forEach(c -> before.put(c.path("gridCd").asText(), c.path("anlsVl").asInt()));
        assertThat(adjusted.path("cells").size()).as("경계는 제외 판정과 무관 — 격자 집합이 같다")
                .isEqualTo(base.path("cells").size()).isPositive();
        adjusted.path("cells").forEach(c -> assertThat(c.path("anlsVl").asInt())
                .as("격자 %s — 경계는 점수를 바꾸지 않는다", c.path("gridCd").asText())
                .isEqualTo(before.get(c.path("gridCd").asText())));
        assertThat(adjusted.path("occUsedVl").decimalValue()).isEqualByComparingTo(stngVl("PCSHR_CRTR_VL"));

        int oldMin = b1.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
        int newMin = newB1.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
        List<Integer> expected = new ArrayList<>(minVls(base));
        assertThat(expected).as("기본 gradeCriteria 에 BND1 경계가 있다").contains(oldMin);
        expected.set(expected.indexOf(oldMin), newMin);
        assertThat(minVls(adjusted)).as("🔴 BND1 경계만 입력값으로 바뀐다").isEqualTo(expected);

        JsonNode ajmt = adjusted.path("vrblAjmt");
        assertThat(ajmt.path("ajmtYn").asText()).isEqualTo("Y");
        assertThat(vrblCds(ajmt)).containsExactlyElementsOf(CNGST_VRBLS);
        assertThat(vrbl(ajmt, "CNGST_GRD_BNDRY1").path("aplcnVl").decimalValue()).isEqualByComparingTo(newB1);
        assertThat(vrbl(ajmt, "CNGST_GRD_BNDRY1").path("stngVl").decimalValue()).isEqualByComparingTo(b1);
        assertThat(vrbl(ajmt, "GEO_CVRG_CRCT_VL").path("aplcnVl").decimalValue())
                .as("조정 안 한 행은 설정값").isEqualByComparingTo(stngVl("GEO_CVRG_CRCT_VL"));

        assertThat(jdbcTemplate.queryForList(SETTINGS_SQL)).as("설정 표에 쓰지 않는다").isEqualTo(settingsBefore);
    }

    @Test
    @DisplayName("V2. vrblVls GEO_CVRG_CRCT_VL 상향 → 격자 집합·tot·ref·noEnf 그대로 · 단속 있는 칸 anlsVl 은 줄지 않는다 · vrblAjmt 가 입력값")
    void geoCorrectionInputRaisesScores() throws Exception {
        BigDecimal cur = stngVl("GEO_CVRG_CRCT_VL");
        BigDecimal max = jdbcTemplate.queryForObject("SELECT max_vl FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = 'LIFEZONE' AND vrbl_cd = 'GEO_CVRG_CRCT_VL'", BigDecimal.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(max != null && cur.compareTo(max) < 0,
                "현재 보정값이 이미 최대 — 올릴 수 없다");
        BigDecimal raised = max;   // 설정 범위 안에서 가장 높게 — 수치 하드코딩 없이

        JsonNode base = callOk(httpParams(Map.of()));
        JsonNode r = callOk(httpParams(Map.of("vrblVls", "{\"GEO_CVRG_CRCT_VL\":" + raised.toPlainString() + "}")));

        assertThat(r.path("totGridCnt").asInt()).as("보정은 제외 판정과 무관").isEqualTo(base.path("totGridCnt").asInt());
        assertThat(r.path("refGridCnt").asInt()).isEqualTo(base.path("refGridCnt").asInt());
        assertThat(r.path("noEnfGridCnt").asInt()).isEqualTo(base.path("noEnfGridCnt").asInt());

        Map<String, Integer> b = new LinkedHashMap<>();
        base.path("cells").forEach(c -> b.put(c.path("gridCd").asText(), c.path("anlsVl").asInt()));
        assertThat(r.path("cells").size()).isEqualTo(base.path("cells").size());
        r.path("cells").forEach(c -> assertThat(c.path("anlsVl").asInt())
                .as("격자 %s — 추정 방문이 g_geo 에 비례해 늘어 점수가 줄지 않는다", c.path("gridCd").asText())
                .isGreaterThanOrEqualTo(b.get(c.path("gridCd").asText())));
        assertThat(vrbl(r.path("vrblAjmt"), "GEO_CVRG_CRCT_VL").path("aplcnVl").decimalValue())
                .isEqualByComparingTo(raised);
        assertThat(r.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("Y");
    }

    @Test
    @DisplayName("V3. vrblVls 생략 · 설정과 같은 값 — 설정값대로이고 ajmtYn 은 N · 10행 · occUsedVl 은 설정값")
    void omittedOrSameVrblVlsIsNotAdjustment() throws Exception {
        JsonNode base = callOk(httpParams(Map.of()));
        assertThat(base.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("N");
        assertThat(vrblCds(base.path("vrblAjmt"))).containsExactlyElementsOf(CNGST_VRBLS);
        assertThat(base.path("occUsedVl").decimalValue()).isEqualByComparingTo(stngVl("PCSHR_CRTR_VL"));

        String same = "{\"GEO_CVRG_CRCT_VL\":" + stngVl("GEO_CVRG_CRCT_VL").setScale(4).toPlainString() + "}";
        JsonNode sameBody = callOk(httpParams(Map.of("vrblVls", same)));
        assertThat(sameBody.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("N");
        assertThat(sameBody.path("totGridCnt").asInt()).isEqualTo(base.path("totGridCnt").asInt());
    }

    @Test
    @DisplayName("V4. vrblVls 위반 — 경계 역전 · 설정과 합친 역전 · 범위 밖 · 수급 코드 · 🆕 숨김 코드 · null · 형식 오류 전부 400")
    void invalidVrblVlsReturns400() {
        BigDecimal b2 = stngVl("CNGST_GRD_BNDRY2");
        BigDecimal b3 = stngVl("CNGST_GRD_BNDRY3");
        assertThat(statusWith("{\"CNGST_GRD_BNDRY2\":" + b3.toPlainString() + ",\"CNGST_GRD_BNDRY3\":"
                + b2.toPlainString() + "}")).as("역전").isEqualTo(400);
        assertThat(statusWith("{\"CNGST_GRD_BNDRY1\":" + b2.toPlainString() + "}"))
                .as("설정값과 합쳐 역전(보통 = 주의)").isEqualTo(400);
        assertThat(statusWith("{\"CNGST_GRD_BNDRY1\":40}")).as("범위 밖(50~100)").isEqualTo(400);
        assertThat(statusWith("{\"GEO_CVRG_CRCT_VL\":5}")).as("범위 밖(1~4)").isEqualTo(400);
        assertThat(statusWith("{\"SPLY_MIN_MNTH_AVG_CVLCPT\":3}")).as("다른 탭(수급) 코드").isEqualTo(400);
        // 숨김 5계수는 설정과 같은 값이어도 400
        for (String hidden : List.of("PCSHR_CRTR_VL", "DSCL_MLTP", "FIX_WGVL", "CVLCPT_WGVL", "MIN_CPCTY_VL")) {
            assertThat(statusWith("{\"" + hidden + "\":" + stngVl(hidden).toPlainString() + "}"))
                    .as("숨김 코드 %s", hidden).isEqualTo(400);
        }
        assertThat(statusWith("{\"GEO_CVRG_CRCT_VL\":null}")).as("null 값").isEqualTo(400);
        assertThat(statusWith("[1,2]")).as("형식 오류").isEqualTo(400);
    }

    @Test
    @DisplayName("V5. 🔴 쿼리 vrblVlsMap[KEY]=값 은 값으로 먹지 않는다 — 함께 온 vrblVls 도 무시되지 않는다")
    void vrblVlsMapQueryIsNotBound() throws Exception {
        // 판별 계수를 숨김 PCSHR_CRTR_VL(occUsedVl) → 노출 GEO_CVRG_CRCT_VL(vrblAjmt.aplcnVl)로 바꿨다
        BigDecimal geo = stngVl("GEO_CVRG_CRCT_VL");
        BigDecimal bound = geo.compareTo(new BigDecimal("3.9")) <= 0 ? geo.add(new BigDecimal("0.1"))
                : geo.subtract(new BigDecimal("0.1"));
        BigDecimal viaJson = geo.compareTo(new BigDecimal("3.95")) <= 0 ? geo.add(new BigDecimal("0.05"))
                : geo.subtract(new BigDecimal("0.05"));
        LocalDate[] w = window();
        String period = "?bgngYmd=" + w[0].format(YMD) + "&endYmd=" + w[1].format(YMD);
        String mapParam = "&vrblVlsMap%5BGEO_CVRG_CRCT_VL%5D=" + bound.toPlainString();

        // 🔴 200 을 단언한다 — 400 을 허용하면 바인딩과 무관한 400(방화벽 등)에 공허하게 통과한다.
        ResponseEntity<String> only = getRaw(period + mapParam);
        assertThat(only.getStatusCode().value()).as("금지 필드는 무시되고 200 — 500 이면 @InitBinder 가 빠졌다").isEqualTo(200);
        JsonNode onlyTree = JSON.readTree(only.getBody());
        assertThat(vrbl(onlyTree.path("vrblAjmt"), "GEO_CVRG_CRCT_VL").path("aplcnVl").decimalValue())
                .as("값이 먹으면 적용값이 바뀐다").isEqualByComparingTo(geo);
        assertThat(onlyTree.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("N");

        String json = URLEncoder.encode("{\"GEO_CVRG_CRCT_VL\":" + viaJson.toPlainString() + "}", StandardCharsets.UTF_8);
        ResponseEntity<String> both = getRaw(period + mapParam + "&vrblVls=" + json);
        assertThat(both.getStatusCode().value()).isEqualTo(200);
        JsonNode bothTree = JSON.readTree(both.getBody());
        assertThat(vrbl(bothTree.path("vrblAjmt"), "GEO_CVRG_CRCT_VL").path("aplcnVl").decimalValue())
                .as("함께 온 vrblVls 가 적용된다 — vrblVlsMap 값이 아니다").isEqualByComparingTo(viaJson);
        assertThat(bothTree.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("Y");
    }

    private BigDecimal stngVl(String vrblCd) {
        return jdbcTemplate.queryForObject("SELECT stng_vl FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = 'LIFEZONE' AND vrbl_cd = ?", BigDecimal.class, vrblCd);
    }

    private int statusWith(String vrblVls) {
        return call(httpParams(Map.of("vrblVls", vrblVls))).getStatusCode().value();
    }

    /**
     * 대괄호를 인코딩한 쿼리를 그대로 보낸다 — URI 템플릿을 거치면 변수명으로 해석된다.
     * 🔴 루트 URI 끝 {@code /} 를 걷는다 — {@code context-path: /} 라 그대로 이으면 {@code //api/…} 가 되어
     * 방화벽(StrictHttpFirewall)이 바인딩과 무관한 400 을 낸다(이 케이스가 공허하게 통과한다).
     */
    private ResponseEntity<String> getRaw(String query) {
        String root = restTemplate.getRootUri().replaceAll("/+$", "");
        return restTemplate.exchange(URI.create(root + CNGST_URL + query),
                HttpMethod.GET, null, String.class);
    }

    private static List<Integer> minVls(JsonNode body) {
        List<Integer> mins = new ArrayList<>();
        body.path("gradeCriteria").forEach(c -> mins.add(c.path("minVl").asInt()));
        return mins;
    }

    private static List<String> vrblCds(JsonNode ajmt) {
        List<String> cds = new ArrayList<>();
        ajmt.path("vrbls").forEach(v -> cds.add(v.path("vrblCd").asText()));
        return cds;
    }

    private static JsonNode vrbl(JsonNode ajmt, String vrblCd) {
        for (JsonNode v : ajmt.path("vrbls")) {
            if (vrblCd.equals(v.path("vrblCd").asText())) {
                return v;
            }
        }
        throw new AssertionError("vrblAjmt 에 " + vrblCd + " 가 없다");
    }
}
