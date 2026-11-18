package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import jeju.datahub.lifezone.service.LifezoneClsfMthd;
import jeju.datahub.lifezone.service.LifezoneGrade;
import jeju.datahub.lifezone.service.LifezoneSupply;
import jeju.datahub.lifezone.service.LifezoneSupplyCellVO;
import jeju.datahub.lifezone.service.LifezoneSupplyGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneSupplyResultVO;
import jeju.datahub.lifezone.service.LifezoneTarget;
import jeju.datahub.lifezone.service.LifezoneTargetVO;

/**
 * 생활권 <b>수급</b> 분석 API 통합 테스트. (TSK-4089 — {@code GET /api/analysis/lifezone/supply})
 *
 * <p>🔁 <b>TSK-4258 — 사분면(P80·P70) → 부족률 등간격 5단계로 바뀌었다(D19 — D13 순위 백분위 개정).</b>
 * 인계서 §5 의 절대값 대조(40,874건 · Q1 칸수 · P80/P70)와 그것을 켜고 끄던 데이터 지문은 사분면 시절 기준이라 통째로 걷었다.
 * 남은 것은 <b>데이터가 바뀌어도 성립해야 하는 불변 성질</b>뿐이다.
 *
 * <h2>🔴 기대값은 테스트가 기준 SQL 로 다시 계산한다 — 날짜·건수를 박지 않는다</h2>
 * 시드가 날짜만 옮겨 재적재되면 박아 둔 값이 한꺼번에 깨진다. 그래서 모집단 수·민원 합·공급 합은
 * {@link #referenceOf} 가 매퍼와 <b>독립적으로 다시 적은 SQL</b>(유형 필터 + 격자 집계 +
 * {@code HAVING count(*) * 30 >= 설정값 * 조회일수})로 그때그때 구한다. 매퍼가 쓰는 상수(유형 목록)도
 * 테스트가 다시 적는다 — 서비스 상수를 읽으면 양쪽이 함께 틀려도 초록불이다. 최소 기준은 아래 D20 절대로 DB 에서 읽는다.
 *
 * <h2>🔴 조회 기간을 상수로 박지 않는다 — DB 에서 읽는다</h2>
 * {@link #dataBgngYmd}~{@link #dataEndYmd} 는 매퍼 {@code selectDataRange} 와 같은 조건(좌표 유효 ·
 * 민원 유형 01~05)으로 읽은 <b>전 구간</b>이고, 366일 상한에 맞춰 시작일만 자른다.
 *
 * <h2>🔴 기준값(최소 월평균 민원·경계 4개)도 DB 설정 표에서 읽는다 (D20)</h2>
 * 사용자가 관리 화면(M0603)에서 바꿨을 수 있다 — 기본값 5·20/40/60/80 을 박지 않는다.
 * {@link #resolveDataFixtures()} 가 {@code tb_anls_vrbl_stng}(LIFEZONE/SPLY_DMND)를 읽어 {@link #minMnthAvgCvlcpt}·
 * {@link #bndrys} 에 담고, 기준 SQL·재판정·범례 문구 기대값이 모두 그 값으로 계산된다.
 *
 * <h2>되돌릴 것 — 설정 변경 케이스만</h2>
 * 조회 API 는 쓰기가 없다. 단 케이스 2-3(설정 변경 반영)은 설정 5행을 JDBC 로 직접 바꾸므로
 * {@link #restoreSplySettings()} 가 {@code @BeforeEach} 에서 떠 둔 원래 값으로 <b>반드시</b> 되돌린다
 * (🔴 로컬 실설정을 망가뜨리지 않는다). PUT 이 아니라 JDBC 라 이력 행({@code tb_anls_vrbl_hstry})은 남지 않는다.
 * {@code @Transactional} 롤백을 쓰지 않는 이유는 {@code RANDOM_PORT} 요청이 별도 커넥션이라 감싸지 못해서다.
 * 🔁 TSK-4339 — 구역명 캐시 키 케이스 2개(목록 대상 · 보통·낮음)는 {@code tb_zone_nm_cache} 1행을 심고, 별도 {@code @AfterEach}
 * {@link #restorePlantedZoneName()} 가 그 키 한 행만 되돌린다(설정 복원과 섞지 않는다 · 전체 삭제 금지).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("생활권 수급 분석 API (GET /api/analysis/lifezone/supply)")
class LifezoneSupplyIntegrationTest {

    private static final String SUPPLY_URL = "/api/analysis/lifezone/supply";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** {@code jeju.datahub.common.DateRanges.MAX_RANGE_DAYS} — 테스트가 독립적으로 다시 적는다. */
    private static final int MAX_RANGE_DAYS = 366;

    /** 수급 격자 4종 — 🔁 민원건수와 같은 목록이다(TSK-4206). */
    private static final int[] GRID_SIZES = { 1000, 500, 250, 100 };

    /** 좌표 유효범위 (인계서 DEC-004). 매퍼와 같은 값이어야 한다 — 테스트가 다시 적는다. */
    private static final String COORD_BBOX =
            " lat BETWEEN 33.10 AND 33.65 AND lot BETWEEN 126.10 AND 127.00 ";

    /**
     * 민원 유형 필터 (TSK-4258 D3) — 매퍼 {@code selectSupplyCells}·{@code selectDataRange} 와
     * {@code 76-JENKS-SHORTAGE-SORT-FIX.sql} 과 같은 목록. 테스트가 다시 적는다.
     */
    private static final String CVLCPT_TYPE_FILTER = " AND cvlcpt_type_cd IN ('01','02','03','04','05') ";

    /** 주차장 유형 필터 (TSK-4258 D3) — 매퍼와 같은 목록. 테스트가 다시 적는다. */
    private static final String PKLT_TYPE_FILTER = " AND pklt_type_cd IN ('PBLPKLT','ONSTR','VACLOT') ";

    /** 월평균 환산 일수 (D11) — 매퍼 {@code HAVING count(*) * 30 >= #{minMnthAvgCvlcpt} * #{prdDays}} 의 짝. 테스트가 다시 적는다. */
    private static final int MNTH_DAYS = 30;

    /** 🔁 D20 — 수급 설정 5행의 키. 테스트가 다시 적는다(서비스 상수를 읽지 않는다) */
    private static final String STNG_WHERE = " WHERE anls_type_cd = 'LIFEZONE' AND anls_trgt_cd = 'SPLY_DMND' ";
    private static final String VRBL_MIN = "SPLY_MIN_MNTH_AVG_CVLCPT";
    private static final String[] VRBL_BNDRYS = { "SPLY_GRD_BNDRY1", "SPLY_GRD_BNDRY2", "SPLY_GRD_BNDRY3", "SPLY_GRD_BNDRY4" };

    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 원천 보유 전 구간(발생일시 기준) — 매퍼 {@code selectDataRange} 와 같은 SQL 로 읽는다. */
    private String dataBgngYmd;
    private String dataEndYmd;

    /** 366일 상한에 맞춰 자른 요청 시작일. 보유 구간이 1년 이내면 {@link #dataBgngYmd} 와 같다. */
    private String queryBgngYmd;

    /** 조회 일수 = endYmd − bgngYmd + 1 (D11). {@code LifezoneSupply.prdDaysOf} 를 쓰지 않고 다시 계산한다. */
    private int prdDays;

    /** 🔁 D20 — DB 현재 설정의 최소 월평균 민원 (기본 5) */
    private int minMnthAvgCvlcpt;

    /** 🔁 D20 — DB 현재 설정의 경계 4개 = 보통·주의·경고·심각 하한(%, 오름차순 · 기본 20/40/60/80) */
    private int[] bndrys;

    /** 설정 5행 원래 값 (vrbl_cd → stng_vl) — 케이스 2-3 복원용 */
    private Map<String, BigDecimal> originalStng;

    /** 🔁 TSK-4339 — 구역명 캐시 키 케이스가 심은 키. {@code null} 이면 심지 않았다(건너뜀). */
    private BigDecimal plantedLatKey;
    private BigDecimal plantedLotKey;

    /** 심기 전 그 키의 원래 캐시 행. {@code null} 이면 원래 행이 없었다 → 복원은 DELETE 다. */
    private CachedZoneNm originalCachedZoneNm;

    @BeforeEach
    void resolveDataFixtures() {
        // 전 구간 — 매퍼 selectDataRange 와 같은 조건(좌표 유효 + 🔁 TSK-4258 민원 유형 01~05)
        String[] range = jdbcTemplate.queryForObject(
                "SELECT to_char(min(ocrn_dt), 'YYYYMMDD'), to_char(max(ocrn_dt), 'YYYYMMDD')"
                        + "  FROM parking.tb_cvlcpt WHERE" + COORD_BBOX + CVLCPT_TYPE_FILTER,
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        dataBgngYmd = range[0];
        dataEndYmd = range[1];
        assertThat(dataBgngYmd)
                .as("좌표가 유효한 민원(TB_CVLCPT)이 없다 — 배치 적재분을 먼저 확인하라")
                .matches("\\d{8}");
        assertThat(dataEndYmd).matches("\\d{8}");
        queryBgngYmd = clampToMaxRange(dataBgngYmd, dataEndYmd);
        prdDays = (int) ChronoUnit.DAYS.between(
                LocalDate.parse(queryBgngYmd, YMD), LocalDate.parse(dataEndYmd, YMD)) + 1;

        // 🔁 D20 — 기준값은 설정 표에서 읽는다(화면에서 바꿨을 수 있다)
        originalStng = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT vrbl_cd, stng_vl FROM parking.tb_anls_vrbl_stng" + STNG_WHERE,
                rs -> {
                    originalStng.put(rs.getString(1), rs.getBigDecimal(2));
                });
        assertThat(originalStng.keySet())
                .as("수급 설정 5행(마이그레이션 91)이 없다 — ./local.sh infra 로 마이그레이션을 적용하라")
                .contains(VRBL_MIN).contains(VRBL_BNDRYS);
        minMnthAvgCvlcpt = originalStng.get(VRBL_MIN).intValueExact();
        bndrys = new int[VRBL_BNDRYS.length];
        for (int i = 0; i < bndrys.length; i++) {
            bndrys[i] = originalStng.get(VRBL_BNDRYS[i]).intValueExact();
        }
    }

    /**
     * 🔴 설정 5행을 {@code @BeforeEach} 에서 떠 둔 값으로 되돌린다 — 케이스 2-3 이 실패·중단해도 돈다.
     * 바꾸지 않은 케이스에서는 같은 값을 다시 쓰는 무해한 UPDATE 다.
     */
    @AfterEach
    void restoreSplySettings() {
        if (originalStng == null) {
            return;
        }
        originalStng.forEach((cd, vl) -> jdbcTemplate.update(
                "UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?" + STNG_WHERE + " AND vrbl_cd = ?", vl, cd));
    }

    /**
     * 🔁 TSK-4339 — 구역명 캐시 키 케이스가 심은 캐시 1행을 되돌린다. 심은 키 한 행만 다룬다(공유 캐시라
     * 전체 삭제 금지). 설정 복원({@link #restoreSplySettings()})과 섞지 않는다.
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

    // ══════════════════════════════ 불변 성질 ══════════════════════════════

    @Test
    @DisplayName("1. 합계 — 격자 크기마다 totCvlcptCnt == sum(cells.cvlcptCnt) == 기준 SQL(최소 기준 포함)의 민원 합")
    void complaintTotalMatchesReferenceSqlPerGridSize() {
        for (int gridSz : GRID_SIZES) {
            LifezoneSupplyResultVO body = analyzeOk(gridSz);
            int sumOfCells = body.getCells().stream().mapToInt(LifezoneSupplyCellVO::getCvlcptCnt).sum();
            int sumOfSupply = body.getCells().stream().mapToInt(LifezoneSupplyCellVO::getPkltCpctySum).sum();
            Reference ref = referenceOf(gridSz, true, true);

            assertThat(body.getTotCvlcptCnt())
                    .as("%dm — 응답 요약(totCvlcptCnt)과 셀 합이 갈리면 안 된다", gridSz)
                    .isEqualTo(sumOfCells);
            /*
             * 🔴 "격자 크기와 무관하게 원천 건수와 같다" 는 더 이상 성립하지 않는다(TSK-4258 D11) —
             *    최소 월평균 기준이 격자마다 걸러 내는 민원이 격자 크기에 따라 다르다.
             *    그래서 격자 크기마다 기준 SQL 의 합과 대조한다.
             */
            assertThat((long) sumOfCells)
                    .as("%dm — 기준 SQL(유형 필터 + HAVING)의 격자별 민원 합과 다르다", gridSz)
                    .isEqualTo(ref.cvlcptSum());
            assertThat(body.getTotPkltCpctySum()).as("%dm — 공급 요약과 셀 합", gridSz).isEqualTo(sumOfSupply);
            assertThat((long) sumOfSupply)
                    .as("%dm — 기준 SQL 의 격자별 공급 합과 다르다", gridSz)
                    .isEqualTo(ref.splySum());
        }
    }

    @Test
    @DisplayName("2. 모집단 — N == 기준 SQL N · 등급 수 == 셀 재판정 수 · 공급 0 ⊂ 심각 · 5등급 합 == N · 범례 5행 합 보존 (격자 4종)")
    void gradeCountsSumToPopulation() {
        for (int gridSz : GRID_SIZES) {
            LifezoneSupplyResultVO body = analyzeOk(gridSz);
            Reference ref = referenceOf(gridSz, true, true);
            int n = body.getTotGridCnt();

            assertThat((long) n)
                    .as("%dm — 모집단 수가 기준 SQL(최소 기준 포함)의 N 과 다르다", gridSz)
                    .isEqualTo(ref.gridCnt());
            assertThat(n).as("%dm — totGridCnt == cells.size()", gridSz).isEqualTo(body.getCells().size());
            assertThat(n).as("%dm — 조회 구간에 평가 대상 격자가 없어 이 케이스가 아무것도 검증하지 않았다", gridSz)
                    .isPositive();

            // 🔁 D19 — 등간격이라 심각 수는 floor(N×0.1) 로 고정되지 않는다. 등급별 수 == 셀을 다시 판정해 센 수
            Map<String, Integer> expectedCnt = new HashMap<>();
            for (LifezoneSupplyCellVO cell : body.getCells()) {
                expectedCnt.merge(expectedGradeOf(cell.getCvlcptCnt(), cell.getDemandIndex()), 1, Integer::sum);
            }
            assertThat(body.getSevereCnt()).as("%dm 심각 수", gridSz).isEqualTo(expectedCnt.getOrDefault("SEVERE", 0));
            assertThat(body.getWarnCnt()).as("%dm 경고 수", gridSz).isEqualTo(expectedCnt.getOrDefault("WARN", 0));
            assertThat(body.getCautionCnt()).as("%dm 주의 수", gridSz).isEqualTo(expectedCnt.getOrDefault("CAUTION", 0));
            assertThat(body.getNormalCnt()).as("%dm 보통 수", gridSz).isEqualTo(expectedCnt.getOrDefault("NORMAL", 0));
            assertThat(body.getLowCnt()).as("%dm 낮음 수", gridSz).isEqualTo(expectedCnt.getOrDefault("LOW", 0));
            assertThat(body.getSevereCnt())
                    .as("%dm — 공급 0 격자는 부족률 100% 라 전부 심각이다(D19)", gridSz)
                    .isGreaterThanOrEqualTo(body.getZeroSplyGridCnt());
            int sumOfGrades = body.getSevereCnt() + body.getWarnCnt() + body.getCautionCnt()
                    + body.getNormalCnt() + body.getLowCnt();
            assertThat(sumOfGrades)
                    .as("%dm — 등급 어디에도 안 들어간(또는 두 번 센) 격자가 있다", gridSz)
                    .isEqualTo(n);
            assertThat((long) body.getZeroSplyGridCnt())
                    .as("%dm — 공급 0 격자 수가 기준 SQL 과 다르다", gridSz)
                    .isEqualTo(ref.zeroSplyGridCnt());

            // 범례 5행 — 0건 등급도 남긴다(색↔의미 대응이 조회마다 바뀌면 안 된다)
            List<LifezoneSupplyGradeCriterionVO> criteria = body.getGradeCriteria();
            assertThat(criteria).as("%dm — 범례는 5행", gridSz).hasSize(LifezoneGrade.values().length);
            assertThat(criteria).extracting(LifezoneSupplyGradeCriterionVO::getGrade)
                    .containsExactly("SEVERE", "WARN", "CAUTION", "NORMAL", "LOW");
            assertThat(criteria.stream().mapToInt(LifezoneSupplyGradeCriterionVO::getGridCnt).sum())
                    .as("%dm — 범례 격자수 합", gridSz)
                    .isEqualTo(n);
            assertThat(criteria.stream().mapToInt(LifezoneSupplyGradeCriterionVO::getCvlcptCnt).sum())
                    .as("%dm — 범례 민원수 합", gridSz)
                    .isEqualTo(body.getTotCvlcptCnt());
            assertThat(criteria.stream().mapToInt(LifezoneSupplyGradeCriterionVO::getZeroSplyGridCnt).sum())
                    .as("%dm — 범례 공급 0 격자 합", gridSz)
                    .isEqualTo(body.getZeroSplyGridCnt());

            // 셀 등급은 민원·수요지수로 다시 판정한 값과 같다 — 정수 비교·아래 경계 포함(D6·D19). 테스트가 다시 적는다.
            for (LifezoneSupplyCellVO cell : body.getCells()) {
                String expected = expectedGradeOf(cell.getCvlcptCnt(), cell.getDemandIndex());
                assertThat(cell.getGrade())
                        .as("%dm %s — 민원 %d / 수요지수 %d", gridSz, cell.getGridId(),
                                cell.getCvlcptCnt(), cell.getDemandIndex())
                        .isEqualTo(expected);
                if (cell.getPkltCpctySum() == 0) {
                    assertThat(cell.getGrade()).as("%dm %s — 공급 0", gridSz, cell.getGridId()).isEqualTo("SEVERE");
                }
                assertThat(cell.getGradeNm()).isEqualTo(LifezoneGrade.valueOf(expected).getGradeNm());
            }
        }
    }

    @Test
    @DisplayName("2-1. 최소 월평균 기준(D11) — 모든 셀이 민원 × 30 ≥ 설정값 × 조회일수 · minMnthAvgCvlcpt == 설정값(D20)")
    void everyCellMeetsMinimumMonthlyAverage() {
        assertThat(prdDays).isPositive();
        for (int gridSz : GRID_SIZES) {
            LifezoneSupplyResultVO body = analyzeOk(gridSz);
            assertThat(body.getMinMnthAvgCvlcpt())
                    .as("%dm — 범례가 '월평균 민원 N건 이상 격자만 평가' 를 띄우는 근거(D16) · 설정 표 값(D20)", gridSz)
                    .isEqualTo(minMnthAvgCvlcpt);
            for (LifezoneSupplyCellVO cell : body.getCells()) {
                assertThat((long) cell.getCvlcptCnt() * MNTH_DAYS)
                        .as("%dm %s — 민원 %d건 · 조회 %d일: 기준 미달 격자가 새어 나왔다",
                                gridSz, cell.getGridId(), cell.getCvlcptCnt(), prdDays)
                        .isGreaterThanOrEqualTo((long) minMnthAvgCvlcpt * prdDays);
            }
            // 기준 이상 격자가 빠지지 않는 쪽은 케이스 2(N == 기준 SQL N)가 덮는다
        }
    }

    /**
     * 🔁 D20 — 설정을 바꾸면 <b>다음 조회부터</b> 등급·모집단이 바뀐다(캐시 없음).
     *
     * <p>바꾸는 값은 현재 설정에서 파생한다 — 최소 기준은 +1(상한 30 이면 −1), 경계는 현재와 다른 유효한 한 세트
     * (현재가 10/30/50/90 이 아니면 그것, 맞으면 20/40/60/80). 기대값은 바꾼 값으로 기준 SQL·재판정을 다시 돌려 얻는다.
     * 🔴 복원은 {@link #restoreSplySettings()} 가 한다.
     */
    @Test
    @DisplayName("2-3. 🔁 설정 변경 반영(D20) — 최소 기준·경계를 바꾸면 다음 조회의 모집단·등급·범례가 그 값을 따른다 · 복원")
    void settingChangeIsReflectedOnNextQuery() {
        int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;
        int newMin = minMnthAvgCvlcpt < 30 ? minMnthAvgCvlcpt + 1 : minMnthAvgCvlcpt - 1;
        int[] newBndrys = Arrays.equals(bndrys, new int[] { 10, 30, 50, 90 })
                ? new int[] { 20, 40, 60, 80 }
                : new int[] { 10, 30, 50, 90 };

        updateStng(VRBL_MIN, newMin);
        for (int i = 0; i < VRBL_BNDRYS.length; i++) {
            updateStng(VRBL_BNDRYS[i], newBndrys[i]);
        }
        minMnthAvgCvlcpt = newMin;   // 기준 SQL·재판정 헬퍼가 읽는 필드 — 바꾼 값으로 기대값을 낸다
        bndrys = newBndrys;

        LifezoneSupplyResultVO body = analyzeOk(gridSz);
        Reference ref = referenceOf(gridSz, true, true);
        assertThat(body.getMinMnthAvgCvlcpt()).as("응답 최소 기준이 바꾼 설정값이다").isEqualTo(newMin);
        assertThat((long) body.getTotGridCnt()).as("모집단이 바꾼 최소 기준의 기준 SQL N 과 같다").isEqualTo(ref.gridCnt());
        assertThat(body.getTotGridCnt()).as("검증할 격자가 있어야 한다").isPositive();
        for (LifezoneSupplyCellVO cell : body.getCells()) {
            assertThat(cell.getGrade())
                    .as("%s — 민원 %d / 수요지수 %d (경계 %s)", cell.getGridId(), cell.getCvlcptCnt(),
                            cell.getDemandIndex(), Arrays.toString(newBndrys))
                    .isEqualTo(expectedGradeOf(cell.getCvlcptCnt(), cell.getDemandIndex()));
        }
        assertThat(body.getGradeCriteria())
                .extracting(LifezoneSupplyGradeCriterionVO::getLgndTxt)
                .containsExactly(expectedLgndTxts(newBndrys));
        assertThat(body.getGradeCriteria())
                .extracting(LifezoneSupplyGradeCriterionVO::getMinShrtRt)
                .containsExactly(newBndrys[3], newBndrys[2], newBndrys[1], newBndrys[0], 0);
    }

    @Test
    @DisplayName("2-2. gridNotice(D17) — 100m 에서만 값이 있고 그 밖 격자 크기에서는 키가 생략된다")
    void gridNoticeOnlyAt100m() {
        for (int gridSz : GRID_SIZES) {
            JsonNode body = analyzeRaw(paramsOf(gridSz));
            if (gridSz == 100) {
                assertThat(body.path("gridNotice").asText())
                        .as("100m 는 공급 0 격자가 대부분이라 해석 주의 안내를 붙인다")
                        .isEqualTo(LifezoneSupply.SMALL_GRID_NOTICE);
            } else {
                assertThat(body.has("gridNotice"))
                        .as("%dm — gridNotice 는 NON_NULL 로 키가 생략돼야 한다", gridSz)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("3. 민원 유형 99·주차장 유형 3종 밖은 집계에 들어가지 않는다 (D3)")
    void excludedTypesAreNotAggregated() {
        boolean discriminated = false;
        for (int gridSz : GRID_SIZES) {
            LifezoneSupplyResultVO body = analyzeOk(gridSz);
            Reference filtered = referenceOf(gridSz, true, true);
            Reference cvlcptUnfiltered = referenceOf(gridSz, false, true);
            Reference pkltUnfiltered = referenceOf(gridSz, true, false);

            assertThat((long) body.getTotCvlcptCnt()).as("%dm 민원 합 = 유형 필터 기준", gridSz)
                    .isEqualTo(filtered.cvlcptSum());
            assertThat((long) body.getTotGridCnt()).as("%dm 격자 수 = 유형 필터 기준", gridSz)
                    .isEqualTo(filtered.gridCnt());
            assertThat((long) body.getTotPkltCpctySum()).as("%dm 공급 합 = 유형 필터 기준", gridSz)
                    .isEqualTo(filtered.splySum());

            if (!cvlcptUnfiltered.equals(filtered)) {
                discriminated = true;
                assertThat((long) body.getTotCvlcptCnt())
                        .as("%dm — 민원 유형 99 가 섞였다", gridSz)
                        .isNotEqualTo(cvlcptUnfiltered.cvlcptSum());
            }
            if (!pkltUnfiltered.equals(filtered)) {
                discriminated = true;
                assertThat((long) body.getTotPkltCpctySum())
                        .as("%dm — 주차장 유형 3종 밖(ETC·MULTFLR 등)이 공급에 섞였다", gridSz)
                        .isNotEqualTo(pkltUnfiltered.splySum());
            }
        }
        assumeThat(discriminated)
                .as("필터 밖 유형(민원 99 · 주차장 3종 밖)이 결과를 바꿀 만큼 적재돼 있지 않다 — 대조 대상이 없다")
                .isTrue();
    }

    @Test
    @DisplayName("4. 부족률 — cvlcpt / (sply + cvlcpt) 4자리 HALF_UP · 공급 0 격자는 1.0000 · 수요 = 면수 + 민원")
    void shortageRatioFollowsFormula() {
        long zeroSupplyCellsOverall = 0;
        for (int gridSz : GRID_SIZES) {
            JsonNode raw = analyzeRaw(paramsOf(gridSz));
            LifezoneSupplyResultVO body = toVo(raw);
            for (LifezoneSupplyCellVO cell : body.getCells()) {
                int dmnd = cell.getPkltCpctySum() + cell.getCvlcptCnt();
                assertThat(cell.getDemandIndex()).as("%dm %s 수요 = 면수 + 민원", gridSz, cell.getGridId())
                        .isEqualTo(dmnd);
                if (cell.getPkltCpctySum() == 0) {
                    zeroSupplyCellsOverall++;
                    assertThat(cell.getShortageRatio())
                            .as("%dm %s — 공급 0 이면 부족률 1", gridSz, cell.getGridId())
                            .isEqualByComparingTo(new BigDecimal("1.0000"));
                } else {
                    assertThat(cell.getShortageRatio())
                            .as("%dm %s — 민원 %d · 면수 %d", gridSz, cell.getGridId(),
                                    cell.getCvlcptCnt(), cell.getPkltCpctySum())
                            .isEqualByComparingTo(BigDecimal.valueOf(cell.getCvlcptCnt())
                                    .divide(BigDecimal.valueOf(dmnd), 4, RoundingMode.HALF_UP));
                }
            }
            // 신규 응답에는 사분면 시절 필드가 없다(D10 — NON_NULL 로 생략)
            for (JsonNode cell : raw.path("cells")) {
                assertThat(cell.has("cvlcptPerSlot")).as("%dm 신규 셀에 cvlcptPerSlot 이 남았다", gridSz).isFalse();
                assertThat(cell.has("qdrn")).as("%dm 신규 셀에 qdrn 이 남았다", gridSz).isFalse();
            }
            assertThat(raw.has("cvlcptHighThld")).isFalse();
            assertThat(raw.has("splyLowThld")).isFalse();
            assertThat(raw.has("qdrnCriteria")).isFalse();
            assertThat(raw.has("q1Cnt")).isFalse();
        }
        assertThat(zeroSupplyCellsOverall)
                .as("격자 %s 어디에도 공급 0 격자가 없어 1.0000 분기가 검증되지 않았다",
                        Arrays.toString(GRID_SIZES))
                .isPositive();
    }

    @Test
    @DisplayName("5. 순위 — rnk 가 1..N 연속·중복 없음 · 부족률(원값) 내림차순 → 민원 내림차순 (D11)")
    void ranksAreContiguousAndOrderedByShortage() {
        for (int gridSz : GRID_SIZES) {
            List<LifezoneSupplyCellVO> cells = analyzeOk(gridSz).getCells();
            for (int i = 0; i < cells.size(); i++) {
                assertThat(cells.get(i).getRnk())
                        .as("%dm — 응답 순서 %d 번째의 순위(ROW_NUMBER 라 1..N 연속)", gridSz, i + 1)
                        .isEqualTo(i + 1);
            }
            for (int i = 1; i < cells.size(); i++) {
                LifezoneSupplyCellVO a = cells.get(i - 1);
                LifezoneSupplyCellVO b = cells.get(i);
                /*
                 * 🔴 반올림된 shortageRatio 가 아니라 원값으로 비교한다 — 4자리 반올림값은 동점처럼
                 *    보여 tie-break 검증이 틀어진다. psdr(면수/민원) ASC 를 교차곱 정수 비교로 본다.
                 */
                long lhs = (long) a.getPkltCpctySum() * b.getCvlcptCnt();
                long rhs = (long) b.getPkltCpctySum() * a.getCvlcptCnt();
                assertThat(lhs)
                        .as("%dm %s(%d면/%d건) → %s(%d면/%d건) — 부족률 내림차순", gridSz,
                                a.getGridId(), a.getPkltCpctySum(), a.getCvlcptCnt(),
                                b.getGridId(), b.getPkltCpctySum(), b.getCvlcptCnt())
                        .isLessThanOrEqualTo(rhs);
                if (lhs == rhs) {
                    assertThat(a.getCvlcptCnt())
                            .as("%dm %s → %s — 부족률 동점이면 민원 많은 격자가 위", gridSz,
                                    a.getGridId(), b.getGridId())
                            .isGreaterThanOrEqualTo(b.getCvlcptCnt());
                }
            }
        }
    }

    @Test
    @DisplayName("좌표 변환 왕복 — center 를 EPSG:5179 로 되돌리면 gridId 와 일치한다")
    void gridCentersRoundTripToTheirGridId() {
        for (int gridSz : GRID_SIZES) {
            LifezoneSupplyResultVO body = analyzeOk(gridSz);
            assertThat(body.getCrs()).isEqualTo(LifezoneSupply.CRS);

            // 응답 전량을 DB 로 왕복시키면 느리다 — 부족 순위 상위 30칸만 본다
            List<LifezoneSupplyCellVO> sample = body.getCells().subList(0, Math.min(30, body.getCells().size()));
            for (LifezoneSupplyCellVO cell : sample) {
                long[] gxgy = gxgyOf(cell.getGridId(), gridSz);
                long[] roundTrip = jdbcTemplate.queryForObject(
                        "SELECT floor(ST_X(p) / ?)::bigint, floor(ST_Y(p) / ?)::bigint FROM ("
                                + "  SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 5179) AS p) t",
                        (rs, rowNum) -> new long[] { rs.getLong(1), rs.getLong(2) },
                        (double) gridSz, (double) gridSz,
                        cell.getCtrLot().doubleValue(), cell.getCtrLat().doubleValue());
                assertThat(roundTrip).isNotNull();
                assertThat(roundTrip[0]).as("%s — 중심 경도 왕복", cell.getGridId()).isEqualTo(gxgy[0]);
                assertThat(roundTrip[1]).as("%s — 중심 위도 왕복", cell.getGridId()).isEqualTo(gxgy[1]);

                assertThat(cell.getRing())
                        .as("%s — 격자 폴리곤은 서버가 4점으로 내린다(프론트가 ±size/2 로 그리면 어긋난다)",
                                cell.getGridId())
                        .hasSize(4);
            }
        }
    }

    // ══════════════════════════════ 계약 — 경로 분리 · 응답 문구 ══════════════════════════════

    @Test
    @DisplayName("기본 핸들러로 SPLY_DMND 를 보내면 400 — 민원건수 결과가 '수급' 이름표를 달고 나가지 않는다")
    void defaultHandlerRejectsSupplyTarget() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bgngYmd", queryBgngYmd);
        params.put("endYmd", dataEndYmd);
        params.put("anlsTrgtCd", LifezoneTarget.SPLY_DMND.name());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/analysis/lifezone?bgngYmd={bgngYmd}&endYmd={endYmd}&anlsTrgtCd={anlsTrgtCd}",
                HttpMethod.GET, null, String.class, params);
        assertThat(response.getStatusCode().value())
                .as("전용 경로가 있는 유형을 기본 핸들러가 처리하면 조용한 오답이 된다")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("지원하지 않는 격자크기(300m)는 400 — 수급은 1000|500|250|100 만 받는다 (TSK-4206)")
    void unsupportedGridSizeIsRejected() {
        Map<String, Object> params = paramsOf(300);
        assertThat(statusOf(params))
                .as("300m 는 종전(인계서) 목록에만 있던 크기다 — TSK-4206 으로 민원건수와 같은 목록이 됐다")
                .isEqualTo(400);
        assertThat(LifezoneSupply.GRID_SZS).containsExactly(1000, 500, 250, 100);
    }

    @Test
    @DisplayName("응답이 표현 규칙을 지킨다 — disclaimer 상시 · 제목·부제(D12) · 등급 범례 문구 · anlsMthd=SHRT_RT")
    void responseCarriesWordingRules() {
        LifezoneSupplyResultVO body = analyzeOk(LifezoneSupply.DEFAULT_GRID_SZ);

        assertThat(body.getDisclaimer())
                .as("🔴 화면에 상시 표기해야 하는 문구다 — 빠지면 산출물이 '주차 부족 면수' 로 읽힌다")
                .isEqualTo(LifezoneSupply.DISCLAIMER);
        assertThat(body.getSectionTitle())
                .as("🔁 TSK-4227 D8 — 제목은 '주차 수급 부족 구역'")
                .isEqualTo("주차 수급 부족 구역")
                .doesNotContain("위험");
        assertThat(body.getSectionSub())
                .as("🔁 TSK-4258 D12 — 시안 부제")
                .isEqualTo("격자 기반 수요·공급 격차 (심각, 경고, 주의)");
        assertThat(body.getAnlsMthd())
                .as("🔴 조회 응답에도 싣는다 — 프론트 unsaved 경로가 조회 결과로 보고서를 그린다(D8)")
                .isEqualTo("SHRT_RT");
        assertThat(body.getAnlsTrgtCd()).isEqualTo(LifezoneTarget.SPLY_DMND.name());
        assertThat(body.getGridSz()).isEqualTo(LifezoneSupply.DEFAULT_GRID_SZ);

        // 범례 문구는 서버가 조립한다 — 🔴 경고·주의·보통에는 '이상'/'미만' 이 없으므로 정확히 비교한다(D19)
        //    🔁 D20 — 숫자는 설정 표 값이다(기본 80/60/40/20 을 박지 않는다). 서식은 그대로다.
        assertThat(body.getGradeCriteria())
                .extracting(LifezoneSupplyGradeCriterionVO::getLgndTxt)
                .containsExactly(expectedLgndTxts(bndrys));
        assertThat(body.getGradeCriteria())
                .extracting(LifezoneSupplyGradeCriterionVO::getMinShrtRt)
                .containsExactly(bndrys[3], bndrys[2], bndrys[1], bndrys[0], 0);
    }

    @Test
    @DisplayName("소수 지점 집중 주의 판정은 서버가 한다 — 고유좌표 3 이하 & 민원 50 이상")
    void concentrationWarningIsDecidedByServer() {
        for (int gridSz : GRID_SIZES) {
            for (LifezoneSupplyCellVO cell : analyzeOk(gridSz).getCells()) {
                boolean expected = cell.getDstncPtCnt() <= LifezoneSupply.CNCNT_WARN_MAX_DSTNC_PT
                        && cell.getCvlcptCnt() >= LifezoneSupply.CNCNT_WARN_MIN_CVLCPT;
                assertThat(cell.getCncntWarnYn())
                        .as("%dm %s — 고유좌표 %d · 민원 %d", gridSz, cell.getGridId(),
                                cell.getDstncPtCnt(), cell.getCvlcptCnt())
                        .isEqualTo(expected ? "Y" : "N");
            }
        }
    }

    @Test
    @DisplayName("🔁 TSK-4339 — 보통·낮음 셀은 외부 조회 없이 캐시 이름(POI·역지오코딩)만 싣고 없으면 생략한다 · 목록 대상은 resolver (TSK-4272)")
    void poiZoneNameOnlyOnListedGrades() {
        // 🔁 TSK-4339 Step 6 — 종전 "보통·낮음은 항상 null" 은 폐기됐다: 매퍼 JOIN 으로 캐시 이름이 실린다.
        //    외부를 부르지 않는다는 계약은 그대로다 → "캐시에 이름이 있으면 그 이름 · 없으면 null" 로 단언한다.
        //    기준은 호출 전 스냅샷뿐이다 — 셀 SQL 이 resolver 보다 먼저 돌아 같은 요청이 채운 키는 셀에 반영될 수 없다.
        //    호출 뒤 스냅샷을 허용하면 "셀이 외부 조회로 이름을 얻는" 회귀를 놓친다.
        for (int gridSz : GRID_SIZES) {
            Map<String, String> cacheBefore = cachedZoneNames();
            List<LifezoneSupplyCellVO> cells = analyzeOk(gridSz).getCells();
            for (LifezoneSupplyCellVO cell : cells) {
                if (!LifezoneGrade.valueOf(cell.getGrade()).isListed()) {
                    String key = cacheKey(cell.getCtrLat(), cell.getCtrLot());
                    String before = cacheBefore.get(key);
                    if (before != null) {
                        assertThat(cell.getCntrPoiNm()).as("%dm %s (%s) — 캐시 이름", gridSz, cell.getGridId(), cell.getGrade())
                                .isEqualTo(before);
                    } else {
                        assertThat(cell.getCntrPoiNm())
                                .as("%dm %s (%s) — 호출 전 캐시가 없던 셀은 생략(외부 조회 금지)",
                                        gridSz, cell.getGridId(), cell.getGrade())
                                .isNull();
                    }
                } else if (cell.getCntrPoiNm() != null) {
                    // resolver 자기 폴백('… 일대' · '위치 미상')은 싣지 않는다 — 화면이 읍면동명으로 폴백한다
                    assertThat(cell.getCntrPoiNm()).as("%dm %s", gridSz, cell.getGridId())
                            .doesNotEndWith(" 일대").isNotEqualTo("위치 미상");
                }
            }
        }
    }

    @Test
    @DisplayName("🔁 TSK-4339 — 목록 대상 셀의 POI 구역명은 격자 중심(ctrLat/ctrLot) 키로 캐시를 찾는다 (D10 개정 · 종전 민원 무게중심)")
    void poiZoneNameIsLookedUpByGridCenterKey() {
        int gridSz = GRID_SIZES[1];
        List<LifezoneSupplyCellVO> listed = analyzeOk(gridSz).getCells().stream()
                .filter(cell -> LifezoneGrade.valueOf(cell.getGrade()).isListed())
                .toList();
        assumeThat(listed)
                .as("%dm 에 목록 대상 등급(심각·경고·주의) 셀이 0개다 — 데이터 부재는 실패가 아니다", gridSz)
                .isNotEmpty();
        LifezoneSupplyCellVO target = listed.get(0);
        assertThat(target.getCtrLat()).as("%s 격자 중심 위도", target.getGridId()).isNotNull();
        assertThat(target.getCtrLot()).as("%s 격자 중심 경도", target.getGridId()).isNotNull();

        // 🔴 격자 중심 좌표로 캐시를 심는다 — 서비스가 무게중심으로 조회하면 키가 달라 이 이름이 안 나온다
        String planted = plantZoneName(target.getCtrLat(), target.getCtrLot());

        LifezoneSupplyCellVO again = analyzeOk(gridSz).getCells().stream()
                .filter(cell -> target.getGridId().equals(cell.getGridId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("재조회 cells 에 " + target.getGridId() + " 가 없다"));
        assertThat(again.getCntrPoiNm())
                .as("%s — 격자 중심 키(%s, %s)에 심은 캐시 이름이 실려야 한다",
                        target.getGridId(), plantedLatKey, plantedLotKey)
                .isEqualTo(planted);
    }

    @Test
    @DisplayName("🆕 TSK-4339 — 보통·낮음 셀도 격자 중심 키의 캐시(POI) 이름을 싣는다 · 키는 SQL round(numeric,5) == Java HALF_UP")
    void unlistedCellCarriesCachedZoneName() {
        int gridSz = GRID_SIZES[1];
        List<LifezoneSupplyCellVO> cells = analyzeOk(gridSz).getCells();
        List<LifezoneSupplyCellVO> unlisted = cells.stream()
                .filter(cell -> !LifezoneGrade.valueOf(cell.getGrade()).isListed())
                .filter(cell -> cell.getCtrLat() != null && cell.getCtrLot() != null)
                .toList();
        assumeThat(unlisted)
                .as("%dm 에 보통·낮음 셀이 0개다 — 데이터 부재는 실패가 아니다", gridSz)
                .isNotEmpty();

        // 🔴 매퍼 JOIN 은 round(ctr_lat, 5)(numeric) · resolver 저장 키는 setScale(5, HALF_UP) — 갈리면 셀이 캐시를 못 찾는다.
        //    응답 셀 전량의 좌표를 SQL 로 반올림한 값과 Java 키를 대조한다.
        StringBuilder lats = new StringBuilder();
        StringBuilder lots = new StringBuilder();
        for (LifezoneSupplyCellVO cell : cells) {
            lats.append(lats.length() == 0 ? "" : ",").append(cell.getCtrLat().toPlainString());
            lots.append(lots.length() == 0 ? "" : ",").append(cell.getCtrLot().toPlainString());
        }
        List<String> sqlKeys = jdbcTemplate.query(
                "SELECT round(k.lat, 5)::text || ',' || round(k.lot, 5)::text"
                        + "  FROM unnest(string_to_array(?, ',')::numeric[], string_to_array(?, ',')::numeric[])"
                        + "       WITH ORDINALITY AS k(lat, lot, ord) ORDER BY k.ord",
                (rs, rowNum) -> rs.getString(1), lats.toString(), lots.toString());
        assertThat(sqlKeys).hasSameSizeAs(cells);
        for (int i = 0; i < cells.size(); i++) {
            assertThat(cacheKey(cells.get(i).getCtrLat(), cells.get(i).getCtrLot()))
                    .as("%s 키 — Java HALF_UP == SQL round", cells.get(i).getGridId())
                    .isEqualTo(sqlKeys.get(i));
        }

        LifezoneSupplyCellVO target = unlisted.get(0);
        String planted = plantZoneName(target.getCtrLat(), target.getCtrLot());

        LifezoneSupplyCellVO again = analyzeOk(gridSz).getCells().stream()
                .filter(cell -> target.getGridId().equals(cell.getGridId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("재조회 cells 에 " + target.getGridId() + " 가 없다"));
        assertThat(again.getGrade()).as("심은 캐시가 등급을 바꾸지 않는다").isEqualTo(target.getGrade());
        assertThat(again.getCntrPoiNm())
                .as("%s (%s) — 보통·낮음 셀도 격자 중심 키(%s, %s)에 심은 캐시 이름이 실려야 한다",
                        target.getGridId(), target.getGrade(), plantedLatKey, plantedLotKey)
                .isEqualTo(planted);
    }

    @Test
    @DisplayName("🔴 targets 가 유형별 원천 보유 구간을 내린다 — 화면이 실행 전 0건을 경고하는 근거")
    void targetsCarryPerTypeDataRange() {
        LifezoneTargetVO[] targets = restTemplate.getForObject(
                "/api/analysis/lifezone/targets", LifezoneTargetVO[].class);
        assertThat(targets).isNotNull().hasSize(LifezoneTarget.values().length);

        LifezoneTargetVO supply = targetOf(targets, LifezoneTarget.SPLY_DMND);
        LifezoneTargetVO cvlcpt = targetOf(targets, LifezoneTarget.CVLCPT_CNT);

        // 수급 구간은 이 테스트가 매퍼와 같은 SQL(좌표 유효 + 유형 01~05)로 읽어 둔 값과 같아야 한다
        assertThat(supply.getDataBgngYmd())
                .as("수급 보유 구간은 발생일시(ocrn_dt) 기준이다 — 화면이 이 값으로 기간을 안내한다")
                .isEqualTo(dataBgngYmd);
        assertThat(supply.getDataEndYmd()).isEqualTo(dataEndYmd);
        assertThat(supply.getUnit()).as("🔁 TSK-4258 — 부족률(%)").isEqualTo("%");

        /*
         * 🔁 2026-09-13 — 수급이 ocrn_dt 로 옮겨와 **두 구간이 거의 같아졌다.** 지금 남은 차이는
         *    집계 조건뿐이다(민원건수는 미분류 제외·좌표 무관 / 수급은 🔁 TSK-4258 유형 01~05·좌표 유효).
         *    🔴 그러니 "다르다" 가 아니라 **둘 다 실재한다** 를 단언한다 — 어느 한쪽이
         *    비면 화면이 기간 안내를 못 한다.
         */
        assertThat(cvlcpt.getDataBgngYmd()).as("민원건수 보유 구간").matches("\\d{8}");
        assertThat(cvlcpt.getDataEndYmd()).matches("\\d{8}");

        LifezoneTargetVO cngst = targetOf(targets, LifezoneTarget.CNGST);
        assertThat(cngst.getDataBgngYmd()).as("혼잡도 보유 구간(단속 crdn_dt)").matches("\\d{8}");
        assertThat(cngst.getDataEndYmd()).matches("\\d{8}");

        // 격자 목록·조회 경로도 유형마다 갈린다(프론트가 탭과 경로를 이 값으로 고른다)
        assertThat(supply.getGridSzs()).containsExactlyElementsOf(LifezoneSupply.GRID_SZS);
        assertThat(supply.getAnlsPathSfx()).isEqualTo("/supply");
        assertThat(cvlcpt.getAnlsPathSfx()).isEmpty();
        // 🔁 TSK-4206 — 수급 격자 목록은 민원건수와 같다(유형을 바꿔도 같은 격자로 비교된다)
        assertThat(supply.getGridSzs()).containsExactlyElementsOf(cvlcpt.getGridSzs());
    }

    @Test
    @DisplayName("🔁 TSK-4227 — targets 의 CNGST 는 활성이고 분류방법은 유형별 1종(목록·기본값)이다")
    void targetsCarryCongestionAndPerTypeClsfMthds() {
        LifezoneTargetVO[] targets = restTemplate.getForObject(
                "/api/analysis/lifezone/targets", LifezoneTargetVO[].class);
        assertThat(targets).isNotNull();

        LifezoneTargetVO cngst = targetOf(targets, LifezoneTarget.CNGST);
        assertThat(cngst.isAvailable()).isTrue();
        assertThat(cngst.getUnavailableRsn()).isNull();
        assertThat(cngst.getUnit()).isEqualTo("점");
        assertThat(cngst.getAnlsTrgtNm()).isEqualTo("혼잡도");
        assertThat(cngst.getAnlsPathSfx()).isEqualTo("/congestion");
        assertThat(cngst.getGridSzs()).containsExactly(1000, 500, 250, 100);
        assertThat(cngst.getDfltGridSz()).isEqualTo(500);
        assertThat(cngst.getClsfMthds()).containsExactly(LifezoneClsfMthd.CNGST_ABSOLUTE);
        assertThat(cngst.getDfltClsfMthd()).isEqualTo(LifezoneClsfMthd.CNGST_ABSOLUTE);

        // 🔁 TSK-4227 — 민원건수는 등간격 1종 · 수급은 부족률 등간격 고정(TSK-4258 D19)이라 고를 분류방법이 없다
        LifezoneTargetVO cvlcpt = targetOf(targets, LifezoneTarget.CVLCPT_CNT);
        assertThat(cvlcpt.getClsfMthds()).containsExactly(LifezoneClsfMthd.EQUAL_INTERVAL);
        assertThat(cvlcpt.getDfltClsfMthd()).isEqualTo(LifezoneClsfMthd.EQUAL_INTERVAL);
        LifezoneTargetVO supply = targetOf(targets, LifezoneTarget.SPLY_DMND);
        assertThat(supply.getClsfMthds()).isEmpty();
        assertThat(supply.getDfltClsfMthd()).isNull();
    }

    @Test
    @DisplayName("🔴 보유 구간 전체를 요청하면 366일 상한에 걸리지 않는다 (화면의 '이 구간으로 맞추기')")
    void fullDataRangeFitsWithinMaxRange() {
        // 화면 버튼이 그대로 보내는 값이다 — 상한을 넘으면 버튼이 400 을 부르는 함정이 된다
        assertThat(analyzeOk(LifezoneSupply.DEFAULT_GRID_SZ).getTotGridCnt()).isPositive();
        assertThat(queryBgngYmd)
                .as("보유 구간이 366일을 넘어 잘렸다면 '이 구간으로 맞추기' 가 전 구간을 주지 못한다")
                .isEqualTo(dataBgngYmd);
    }

    @Test
    @DisplayName("🔁 dclr_ymd 가 없는 신규 적재분도 분석에 잡힌다 (ocrn_dt 로 바꾼 이유)")
    void rowsWithoutDclrYmdAreIncluded() {
        Integer newRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt WHERE dclr_ymd IS NULL AND" + COORD_BBOX
                        + CVLCPT_TYPE_FILTER,
                Integer.class);
        assumeThat(newRows)
                .as("dclr_ymd 가 없는 행이 없다 — 이 케이스가 검증할 대상이 아직 없다")
                .isNotNull()
                .isPositive();

        // 기대값 = dclr_ymd 조건이 없는 기준 SQL(유형 필터 + HAVING)의 500m 민원 합
        Reference ref = referenceOf(LifezoneSupply.DEFAULT_GRID_SZ, true, true);
        assertThat((long) analyzeOk(LifezoneSupply.DEFAULT_GRID_SZ).getTotCvlcptCnt())
                .as("🔴 dclr_ymd 로 거르면 %d건이 통째로 빠진다 — 그것이 ocrn_dt 로 바꾼 이유다", newRows)
                .isEqualTo(ref.cvlcptSum());
    }

    // ══════════════════════════════ 분석 계수 일시 입력 (TSK-4325 Step 5) ══════════════════════════════

    /**
     * 🔴 입력값은 이번 요청에만 쓰이고 설정 표에 쓰지 않는다 — 기대값은 입력값으로 기준 SQL·재판정을 다시 돌려 얻는다.
     * 입력은 현재 설정에서 파생한다(케이스 2-3 과 같은 규칙) — 상수를 박지 않는다.
     */
    @Test
    @DisplayName("V1. vrblVls — 최소 기준·경계 입력이 모집단·등급·범례·minMnthAvgCvlcpt·vrblAjmt 에 반영되고 설정 표는 그대로다")
    void vrblVlsInputIsAppliedToSupply() {
        String settingsSql = "SELECT * FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd";
        List<Map<String, Object>> settingsBefore = jdbcTemplate.queryForList(settingsSql);
        int[] curBndrys = bndrys.clone();
        int curMin = minMnthAvgCvlcpt;

        int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;
        int newMin = curMin < 30 ? curMin + 1 : curMin - 1;
        int[] newBndrys = Arrays.equals(curBndrys, new int[] { 10, 30, 50, 90 })
                ? new int[] { 20, 40, 60, 80 }
                : new int[] { 10, 30, 50, 90 };
        Map<String, Object> params = paramsOf(gridSz);
        params.put("vrblVls", splyJson(newMin, newBndrys));

        LifezoneSupplyResultVO body = toVo(analyzeRaw(params));

        minMnthAvgCvlcpt = newMin;   // 기준 SQL·재판정 헬퍼가 읽는 필드 — 입력값으로 기대값을 낸다
        bndrys = newBndrys;
        Reference ref = referenceOf(gridSz, true, true);
        assertThat(body.getMinMnthAvgCvlcpt()).as("응답 최소 기준이 입력값이다").isEqualTo(newMin);
        assertThat((long) body.getTotGridCnt()).as("모집단이 입력 최소 기준의 기준 SQL N 과 같다").isEqualTo(ref.gridCnt());
        assertThat(body.getTotGridCnt()).as("검증할 격자가 있어야 한다").isPositive();
        for (LifezoneSupplyCellVO cell : body.getCells()) {
            assertThat(cell.getGrade())
                    .as("%s — 민원 %d / 수요지수 %d (입력 경계 %s)", cell.getGridId(), cell.getCvlcptCnt(),
                            cell.getDemandIndex(), Arrays.toString(newBndrys))
                    .isEqualTo(expectedGradeOf(cell.getCvlcptCnt(), cell.getDemandIndex()));
        }
        assertThat(body.getGradeCriteria())
                .extracting(LifezoneSupplyGradeCriterionVO::getMinShrtRt)
                .containsExactly(newBndrys[3], newBndrys[2], newBndrys[1], newBndrys[0], 0);
        assertThat(body.getGradeCriteria())
                .extracting(LifezoneSupplyGradeCriterionVO::getLgndTxt)
                .containsExactly(expectedLgndTxts(newBndrys));

        // D10 — 적용 내역: 허용 목록 5행 전부 · 설정값(stngVl)은 그대로 · 적용값은 입력값
        assertThat(body.getVrblAjmt()).containsEntry("ajmtYn", "Y");
        assertThat(vrblCdsOf(body.getVrblAjmt())).containsExactly(VRBL_MIN,
                VRBL_BNDRYS[0], VRBL_BNDRYS[1], VRBL_BNDRYS[2], VRBL_BNDRYS[3]);
        assertThat(vrblValue(body.getVrblAjmt(), VRBL_MIN, "aplcnVl")).isEqualByComparingTo(BigDecimal.valueOf(newMin));
        assertThat(vrblValue(body.getVrblAjmt(), VRBL_MIN, "stngVl")).isEqualByComparingTo(BigDecimal.valueOf(curMin));
        for (int i = 0; i < VRBL_BNDRYS.length; i++) {
            assertThat(vrblValue(body.getVrblAjmt(), VRBL_BNDRYS[i], "aplcnVl"))
                    .isEqualByComparingTo(BigDecimal.valueOf(newBndrys[i]));
            assertThat(vrblValue(body.getVrblAjmt(), VRBL_BNDRYS[i], "stngVl"))
                    .isEqualByComparingTo(BigDecimal.valueOf(curBndrys[i]));
        }

        assertThat(jdbcTemplate.queryForList(settingsSql)).as("D8 — 설정 표에 쓰지 않는다").isEqualTo(settingsBefore);
    }

    @Test
    @DisplayName("V2. vrblVls 생략 · 설정과 같은 값(소수점 표기) — 설정값대로이고 ajmtYn 은 N 이다 (D11)")
    void omittedOrSameVrblVlsIsNotAdjustment() {
        int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;
        LifezoneSupplyResultVO base = analyzeOk(gridSz);
        assertThat(base.getVrblAjmt()).containsEntry("ajmtYn", "N");
        assertThat(vrblCdsOf(base.getVrblAjmt())).hasSize(5);
        assertThat(base.getMinMnthAvgCvlcpt()).isEqualTo(minMnthAvgCvlcpt);

        StringBuilder same = new StringBuilder("{\"" + VRBL_MIN + "\":" + minMnthAvgCvlcpt + ".00");
        for (int i = 0; i < VRBL_BNDRYS.length; i++) {
            same.append(",\"").append(VRBL_BNDRYS[i]).append("\":").append(bndrys[i]).append(".0");
        }
        Map<String, Object> params = paramsOf(gridSz);
        params.put("vrblVls", same.append('}').toString());
        LifezoneSupplyResultVO sameBody = toVo(analyzeRaw(params));
        assertThat(sameBody.getVrblAjmt()).containsEntry("ajmtYn", "N");
        assertThat(sameBody.getTotGridCnt()).isEqualTo(base.getTotGridCnt());
        assertThat(sameBody.getSevereCnt()).isEqualTo(base.getSevereCnt());
    }

    @Test
    @DisplayName("V3. vrblVls 위반 — 소수 · 역전 · 설정과 합친 역전 · 범위 밖 · 혼잡도 코드 · null · 형식 오류 전부 400")
    void invalidVrblVlsReturns400() {
        assertThat(statusWith("{\"" + VRBL_BNDRYS[0] + "\":" + bndrys[0] + ".5}")).as("소수 — 정수 전용").isEqualTo(400);
        assertThat(statusWith("{\"" + VRBL_MIN + "\":1.5}")).as("최소 기준 소수").isEqualTo(400);
        assertThat(statusWith(splyJson(minMnthAvgCvlcpt, new int[] { 40, 20, 60, 80 }))).as("역전").isEqualTo(400);
        assertThat(statusWith("{\"" + VRBL_BNDRYS[0] + "\":" + bndrys[1] + "}"))
                .as("설정값과 합쳐 역전(보통 = 주의)").isEqualTo(400);
        assertThat(statusWith("{\"" + VRBL_BNDRYS[3] + "\":100}")).as("범위 밖").isEqualTo(400);
        assertThat(statusWith("{\"PCSHR_CRTR_VL\":0.8}")).as("다른 탭(혼잡도) 코드").isEqualTo(400);
        assertThat(statusWith("{\"" + VRBL_MIN + "\":null}")).as("null 값").isEqualTo(400);
        assertThat(statusWith("not-json")).as("형식 오류").isEqualTo(400);
    }

    @Test
    @DisplayName("V4. 🔴 쿼리 vrblVlsMap[KEY]=값 은 값으로 먹지 않는다 — 함께 온 vrblVls 도 무시되지 않는다 (Step 1)")
    void vrblVlsMapQueryIsNotBound() throws IOException {
        int bound = minMnthAvgCvlcpt < 29 ? minMnthAvgCvlcpt + 2 : minMnthAvgCvlcpt - 2;
        int viaJson = minMnthAvgCvlcpt < 30 ? minMnthAvgCvlcpt + 1 : minMnthAvgCvlcpt - 1;
        String period = "?bgngYmd=" + queryBgngYmd + "&endYmd=" + dataEndYmd;
        String mapParam = "&vrblVlsMap%5B" + VRBL_MIN + "%5D=" + bound;

        // 🔴 200 을 단언한다 — 400 을 허용하면 바인딩과 무관한 400(방화벽 등)에 공허하게 통과한다.
        ResponseEntity<String> only = getRaw(period + mapParam);
        assertThat(only.getStatusCode().value()).as("금지 필드는 무시되고 200 — 500 이면 @InitBinder 가 빠졌다").isEqualTo(200);
        JsonNode tree = RESPONSE_MAPPER.readTree(only.getBody());
        assertThat(tree.path("minMnthAvgCvlcpt").asInt()).as("값이 먹으면 최소 기준이 바뀐다")
                .isEqualTo(minMnthAvgCvlcpt);
        assertThat(tree.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("N");

        String json = URLEncoder.encode("{\"" + VRBL_MIN + "\":" + viaJson + "}", StandardCharsets.UTF_8);
        ResponseEntity<String> both = getRaw(period + mapParam + "&vrblVls=" + json);
        assertThat(both.getStatusCode().value()).isEqualTo(200);
        JsonNode bothTree = RESPONSE_MAPPER.readTree(both.getBody());
        assertThat(bothTree.path("minMnthAvgCvlcpt").asInt())
                .as("함께 온 vrblVls 가 적용된다 — vrblVlsMap 값이 아니다").isEqualTo(viaJson).isNotEqualTo(bound);
        assertThat(bothTree.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("Y");
    }

    @Test
    @DisplayName("V5. Swagger — 수급·혼잡도 조회에 vrblVls 는 문서에 있고 vrblVlsMap 은 숨겨진다 (Step 1)")
    void swaggerHidesVrblVlsMap() throws IOException {
        ResponseEntity<String> docs = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(docs.getStatusCode().value()).isEqualTo(200);
        assertThat(docs.getBody()).doesNotContain("vrblVlsMap");
        JsonNode paths = RESPONSE_MAPPER.readTree(docs.getBody()).path("paths");
        for (String path : List.of(SUPPLY_URL, "/api/analysis/lifezone/congestion")) {
            List<String> names = new java.util.ArrayList<>();
            paths.path(path).path("get").path("parameters").forEach(n -> names.add(n.path("name").asText()));
            assertThat(names).as("%s 파라미터", path).contains("vrblVls");
        }
    }

    // ─────────────────────────────────────────── 분석 계수 헬퍼

    private int statusWith(String vrblVls) {
        Map<String, Object> params = paramsOf(LifezoneSupply.DEFAULT_GRID_SZ);
        params.put("vrblVls", vrblVls);
        return statusOf(params);
    }

    /**
     * 대괄호를 인코딩한 쿼리를 그대로 보낸다 — URI 템플릿을 거치면 변수명으로 해석된다.
     * 🔴 루트 URI 끝 {@code /} 를 걷는다 — {@code context-path: /} 라 그대로 이으면 {@code //api/…} 가 되어
     * 방화벽(StrictHttpFirewall)이 바인딩과 무관한 400 을 낸다(이 케이스가 공허하게 통과한다).
     */
    private ResponseEntity<String> getRaw(String query) {
        String root = restTemplate.getRootUri().replaceAll("/+$", "");
        return restTemplate.exchange(URI.create(root + SUPPLY_URL + query),
                HttpMethod.GET, null, String.class);
    }

    private static String splyJson(int min, int[] b) {
        StringBuilder json = new StringBuilder("{\"" + VRBL_MIN + "\":" + min);
        for (int i = 0; i < VRBL_BNDRYS.length; i++) {
            json.append(",\"").append(VRBL_BNDRYS[i]).append("\":").append(b[i]);
        }
        return json.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> vrblCdsOf(Map<String, Object> vrblAjmt) {
        return ((List<Map<String, Object>>) vrblAjmt.get("vrbls")).stream()
                .map(v -> String.valueOf(v.get("vrblCd"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static BigDecimal vrblValue(Map<String, Object> vrblAjmt, String vrblCd, String key) {
        return ((List<Map<String, Object>>) vrblAjmt.get("vrbls")).stream()
                .filter(v -> vrblCd.equals(v.get("vrblCd")))
                .map(v -> new BigDecimal(String.valueOf(v.get(key))))
                .findFirst()
                .orElseThrow();
    }

    // ─────────────────────────────────────────────────────── 헬퍼

    /** 기준 SQL 이 낸 격자 모집단 요약 — {N, 민원 합, 공급 합, 공급 0 격자 수}. */
    private record Reference(long gridCnt, long cvlcptSum, long splySum, long zeroSplyGridCnt) {
    }

    /**
     * 매퍼와 독립적으로 다시 적은 <b>기준 SQL</b>(계획서 Step 1 검증 쿼리 형태) — 전 구간·지역 없음.
     *
     * <p>🔴 유형 필터를 끌 수 있게 한 것은 케이스 3(필터 밖 유형이 결과를 바꾸는가) 대조용이다.
     * 민원은 매퍼와 같이 {@code geom} 을 5179 로 옮겨 격자에 배정하고, 공급은 {@code lot/lat} 점으로 만든다.
     */
    private Reference referenceOf(int gridSz, boolean cvlcptTypeFilter, boolean pkltTypeFilter) {
        String sql = "WITH c AS ("
                + "  SELECT ST_Transform(geom, 5179) AS g5 FROM parking.tb_cvlcpt"
                + "   WHERE" + COORD_BBOX + (cvlcptTypeFilter ? CVLCPT_TYPE_FILTER : "")
                + "     AND ocrn_dt >= ? AND ocrn_dt < ?"
                + "), gc AS ("
                + "  SELECT floor(ST_X(g5) / ?::float8)::bigint AS gx, floor(ST_Y(g5) / ?::float8)::bigint AS gy,"
                + "         count(*) AS n FROM c GROUP BY 1, 2"
                + "  HAVING count(*) * " + MNTH_DAYS + " >= " + minMnthAvgCvlcpt + " * ?"
                + "), p AS ("
                + "  SELECT coalesce(pklt_cpcty, 0) AS cap,"
                + "         ST_Transform(ST_SetSRID(ST_MakePoint(lot::float8, lat::float8), 4326), 5179) AS g5"
                + "    FROM parking.tb_pblpklt WHERE" + COORD_BBOX + (pkltTypeFilter ? PKLT_TYPE_FILTER : "")
                + "), sup AS ("
                + "  SELECT floor(ST_X(g5) / ?::float8)::bigint AS gx, floor(ST_Y(g5) / ?::float8)::bigint AS gy,"
                + "         sum(cap) AS s FROM p GROUP BY 1, 2"
                + ")"
                + " SELECT count(*), coalesce(sum(gc.n), 0), coalesce(sum(coalesce(sup.s, 0)), 0),"
                + "        count(*) FILTER (WHERE coalesce(sup.s, 0) = 0)"
                + "   FROM gc LEFT JOIN sup USING (gx, gy)";
        Reference ref = jdbcTemplate.queryForObject(sql,
                (rs, rowNum) -> new Reference(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                LocalDate.parse(queryBgngYmd, YMD).atStartOfDay(),
                LocalDate.parse(dataEndYmd, YMD).plusDays(1).atStartOfDay(),
                (double) gridSz, (double) gridSz,
                prdDays,
                (double) gridSz, (double) gridSz);
        assertThat(ref).isNotNull();
        return ref;
    }

    /**
     * 부족률 등간격 → 등급 (D6·D19) — 🔴 서비스({@code LifezoneGrade.ofShrtRt})를 부르지 않고 다시 적는다.
     * 반올림 전 정수 비교 {@code 민원 × 100 >= 경계 × 수요지수}, 아래 경계 포함. 🔁 경계는 DB 설정값({@link #bndrys} — D20).
     */
    private String expectedGradeOf(int cvlcptCnt, int dmndIdx) {
        long scaled = (long) cvlcptCnt * 100;
        if (scaled >= (long) bndrys[3] * dmndIdx) {
            return "SEVERE";
        }
        if (scaled >= (long) bndrys[2] * dmndIdx) {
            return "WARN";
        }
        if (scaled >= (long) bndrys[1] * dmndIdx) {
            return "CAUTION";
        }
        if (scaled >= (long) bndrys[0] * dmndIdx) {
            return "NORMAL";
        }
        return "LOW";
    }

    /** 범례 문구 5행 기대값 — 서식 {@code 심각 (B4% 이상)}·{@code 경고 (B3~B4%)}…{@code 낮음 (B1% 미만)}. 테스트가 다시 적는다 */
    private static String[] expectedLgndTxts(int[] b) {
        return new String[] {
                "심각 (" + b[3] + "% 이상)",
                "경고 (" + b[2] + "~" + b[3] + "%)",
                "주의 (" + b[1] + "~" + b[2] + "%)",
                "보통 (" + b[0] + "~" + b[1] + "%)",
                "낮음 (" + b[0] + "% 미만)" };
    }

    private void updateStng(String vrblCd, int stngVl) {
        int updated = jdbcTemplate.update(
                "UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?" + STNG_WHERE + " AND vrbl_cd = ?",
                BigDecimal.valueOf(stngVl), vrblCd);
        assertThat(updated).as("설정 행 %s", vrblCd).isEqualTo(1);
    }

    private static LifezoneTargetVO targetOf(LifezoneTargetVO[] targets, LifezoneTarget target) {
        for (LifezoneTargetVO vo : targets) {
            if (target.name().equals(vo.getAnlsTrgtCd())) {
                return vo;
            }
        }
        throw new AssertionError("분석유형 목록에 " + target.name() + " 이 없다");
    }

    /**
     * 격자 중심 좌표의 캐시 키(소수 5자리 HALF_UP — {@code ZoneNameResolver.key()} 와 같은 규칙, 그 메서드는
     * 패키지 전용이다)에 테스트 이름을 {@code POI} 출처로 심는다. 원래 행은 먼저 떠 둔다(복원용).
     */
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

    /**
     * 구역명 캐시 중 셀 이름으로 쓰이는 행(출처 {@code POI}·{@code REVERSE_GEOCODE} · 이름 있음)을 키 → 이름으로 일괄 조회한다.
     */
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

    /** 구역명 캐시 1행의 복원 대상 4열. */
    private record CachedZoneNm(String zoneNm, String zoneNmSrc, Integer poiDistM, Timestamp inqDt) {
    }

    private LifezoneSupplyResultVO analyzeOk(int gridSz) {
        return toVo(analyzeRaw(paramsOf(gridSz)));
    }

    /** 키 생략(NON_NULL)을 단언해야 하는 케이스용 — 역직렬화 전 원문 트리. */
    private JsonNode analyzeRaw(Map<String, Object> params) {
        ResponseEntity<String> response = call(params);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        try {
            return RESPONSE_MAPPER.readTree(response.getBody());
        } catch (IOException e) {
            throw new IllegalStateException("응답 파싱 실패: " + e, e);
        }
    }

    private static LifezoneSupplyResultVO toVo(JsonNode tree) {
        try {
            return RESPONSE_MAPPER.treeToValue(tree, LifezoneSupplyResultVO.class);
        } catch (IOException e) {
            throw new IllegalStateException("응답 역직렬화 실패: " + e, e);
        }
    }

    private int statusOf(Map<String, Object> params) {
        return call(params).getStatusCode().value();
    }

    private ResponseEntity<String> call(Map<String, Object> params) {
        return restTemplate.exchange(uriTemplate(params), HttpMethod.GET, null, String.class, params);
    }

    /** 쿼리스트링은 URI 템플릿 + 변수로 만든다 — 값을 이어 붙이면 인코딩이 환경마다 갈린다. */
    private static String uriTemplate(Map<String, Object> params) {
        StringBuilder uri = new StringBuilder(SUPPLY_URL);
        String separator = "?";
        for (String key : params.keySet()) {
            uri.append(separator).append(key).append("={").append(key).append('}');
            separator = "&";
        }
        return uri.toString();
    }

    /** 전 구간 조회 파라미터. */
    private Map<String, Object> paramsOf(int gridSz) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bgngYmd", queryBgngYmd);
        params.put("endYmd", dataEndYmd);
        params.put("gridSz", gridSz);
        return params;
    }

    /** {@code {격자크기}m_{gx}_{gy}} 에서 격자 인덱스를 꺼낸다. */
    private static long[] gxgyOf(String gridId, int gridSz) {
        String prefix = gridSz + "m_";
        assertThat(gridId).as("격자ID 접두는 격자크기다").startsWith(prefix);
        String[] parts = gridId.substring(prefix.length()).split("_");
        assertThat(parts).hasSize(2);
        return new long[] { Long.parseLong(parts[0]), Long.parseLong(parts[1]) };
    }

    /** 366일 상한에 맞춰 시작일을 자른다. 구간이 1년 이내면 그대로 돌려준다. */
    private static String clampToMaxRange(String bgngYmd, String endYmd) {
        LocalDate bgng = LocalDate.parse(bgngYmd, YMD);
        LocalDate floor = LocalDate.parse(endYmd, YMD).minusDays(MAX_RANGE_DAYS - 1L);
        return bgng.isBefore(floor) ? floor.format(YMD) : bgngYmd;
    }
}
