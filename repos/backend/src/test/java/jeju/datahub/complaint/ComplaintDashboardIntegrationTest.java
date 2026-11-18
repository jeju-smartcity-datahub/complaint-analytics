package jeju.datahub.complaint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.complaint.service.DashboardKpiVO;
import jeju.datahub.complaint.service.DataStatusVO;
import jeju.datahub.complaint.service.RegionRankVO;
import jeju.datahub.complaint.service.RiskAlertKpiVO;
import jeju.datahub.complaint.service.RiskLevelCountVO;

/**
 * 민원 대시보드 KPI 집계 API 통합 테스트 (TSK-4058 Step 6).
 *
 * <p><b>무엇을 지키는 테스트인가</b> — {@code GET /api/dashboard/kpi} 한 응답에 <b>모수가 다른 두 집계</b>가
 * 실려 나간다: 민원({@code parking.TB_CVLCPT})과 위험단계 알림({@code parking.TB_RISK_STP_NOTI_HSTRY}).
 * 필터는 대칭적으로 갈린다 — {@code typeCd} 는 민원에만(D3), {@code riskLvlCd} 는 알림에만(D8),
 * 기간과 {@code sggCd} 는 양쪽 공통이다. 이 비대칭이 무너지면(예: 공통 {@code filters} 조각을
 * 알림 쿼리에 재사용) 카드 숫자가 조용히 틀어지므로 케이스 2·3·4 로 못박는다.
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리해 테스트 트랜잭션이 서버 쪽을 감싸지 못한다({@code RiskNotiIntegrationTest} ·
 * {@code MenuAuthrtIntegrationTest} · {@code ReportIntegrationTest} 와 같은 전략). 다만 이 테스트는
 * <b>조회 전용</b>이라 DB 를 전혀 바꾸지 않는다 — 그래서 시드 복원({@code @AfterEach})도 두지 않는다.
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다 — 모든 프로파일이 PostgreSQL 이고 H2 는 없다.
 *
 * <p>🔴 <b>기대값을 상수로 박지 않는다.</b> 알림 시드 18행은 TSK-4028 이, 민원 적재분은 배치가 소유하므로
 * 여기서 숫자를 굳히면 남의 변경에 이 테스트가 깨진다. 대신 <b>매퍼 SQL 과 같은 조건</b>을
 * {@link JdbcTemplate} 으로 직접 세어 기대값으로 쓴다({@link #dbAlertCount} · {@link #dbComplaintCount}).
 * 조회 구간도 알림 시드의 {@code min/max(ocrn_dt)} 에서 뽑는다({@link #resolveSeedRange}).
 *
 * <p>🔴 <b>{@code unit}(TODAY/WEEK/MONTH) 에 의존하지 않는다.</b> 기간은 전부
 * {@code startDt}/{@code endDt} 로 명시한다 — 환경변수가 없는 CI 에서 조회단위 기반 테스트가 조용히
 * 0건이 되는 것을 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComplaintDashboardIntegrationTest {

    private static final String KPI_URL = "/api/dashboard/kpi";

    /** 데이터 현황(TSK-4085 D7) — 필터를 받지 않는 전역 지표다. */
    private static final String DATA_STATUS_URL = "/api/dashboard/data-status";

    /** 읍면동 순위 — KPI 와 같은 파라미터를 받는다(불변식 케이스 8·9 가 쓴다). */
    private static final String REGIONS_URL = "/api/dashboard/regions";

    /**
     * 조회 가능한 최대 구간(일, 양끝 포함). {@code jeju.datahub.common.DateRanges.MAX_RANGE_DAYS} 와
     * 같은 값이어야 한다 — 테스트가 독립적으로 다시 적어 두 값이 갈리면 케이스가 드러낸다.
     */
    private static final int MAX_RANGE_DAYS = 366;

    /** 시군구 코드(제주시). 건수가 아니라 <b>필터 입력값</b>이라 상수로 둔다 — 기대 건수는 DB 에서 읽는다. */
    private static final String SGG_JEJU = "50110";

    /** 위험단계 코드. 마찬가지로 필터 입력값이다(화이트리스트 CRIT/WARN/CAUT). */
    private static final String LVL_CRIT = "CRIT";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 알림 시드 전 구간 — 이 구간을 조회하면 시드 전량이 잡힌다 (실측 2025-12-28 ~ 2026-05-28) */
    private LocalDate seedStart;
    private LocalDate seedEnd;

    /**
     * 조회 구간을 알림 시드에서 뽑는다. 날짜를 소스에 박으면 시드 날짜가 옮겨졌을 때(TSK-4028 소관)
     * 이 테스트가 조용히 0건을 보게 된다 — "전 구간"이라는 의미가 코드에 남게 DB 에서 읽는다.
     */
    @BeforeEach
    void resolveSeedRange() {
        seedStart = queryDate("SELECT min(ocrn_dt)::date::text FROM parking.tb_risk_stp_noti_hstry");
        seedEnd = queryDate("SELECT max(ocrn_dt)::date::text FROM parking.tb_risk_stp_noti_hstry");

        assertThat(seedStart)
                .as("알림 시드가 비어 있다 — ./local.sh infra 로 10-TB_NOTI_ALERT.sql 시드를 적재하라")
                .isNotNull();
        assertThat(seedEnd).isNotNull();

        // 🔴 366일 상한(DateRanges)에 맞춰 시작일을 자른다 (TSK-4085 Step 4).
        //    min(ocrn_dt) 는 시드 고정인데 max 는 알림 배치가 매일 밀어 올려 구간이 하루씩 자란다 —
        //    366일을 넘는 순간 이 클래스의 전 케이스가 400 으로 한꺼번에 죽는다.
        //    365 를 빼면 양끝 포함 366일. 전 구간이 1년 이내면 값이 그대로다.
        LocalDate clamped = seedEnd.minusDays(MAX_RANGE_DAYS - 1);
        if (seedStart.isBefore(clamped)) {
            seedStart = clamped;
        }
        assertThat(ChronoUnit.DAYS.between(seedStart, seedEnd) + 1)
                .as("조회 구간이 백엔드 상한(%d일)을 넘으면 전 케이스가 400 이 된다", MAX_RANGE_DAYS)
                .isLessThanOrEqualTo(MAX_RANGE_DAYS);
    }

    // ================================================================= 케이스 1~5

    @Test
    @DisplayName("1. 전 구간 조회 — riskAlert.total 이 시드 전량과 같고 breakdown 합이 total 이다")
    void case01_fullRangeMatchesSeedTotal() {
        DashboardKpiVO kpi = getKpi("");

        // (1) 시드 전량 — 상수(실측 18)가 아니라 같은 조건의 count(*) 와 비교한다
        long dbTotal = dbAlertCount(seedStart, seedEnd, null);
        assertThat(dbTotal).as("전 구간인데 알림이 0건이다 — 구간 산정이나 시드를 확인하라").isPositive();

        RiskAlertKpiVO riskAlert = kpi.getRiskAlert();
        assertThat(riskAlert).isNotNull();
        assertThat(riskAlert.getTotal()).isEqualTo(dbTotal);

        // (2) breakdown 합 == total — 어긋나면 GROUP BY 가 행을 늘렸거나 서비스 합산이 틀린 것이다
        assertThat(riskAlert.getBreakdown()).isNotNull().isNotEmpty();
        assertThat(sumOf(riskAlert)).isEqualTo(riskAlert.getTotal());

        // (3) 단계별 건수도 DB 집계와 1:1 (실측 CRIT 4 / WARN 8 / CAUT 6)
        assertThat(countsByLevel(riskAlert)).isEqualTo(dbAlertCountsByLevel(seedStart, seedEnd, null));

        // (4) 민원 집계도 같은 구간의 실데이터와 같다 (두 집계가 한 응답에 함께 실린다 — D5)
        assertThat(kpi.getTotal()).isEqualTo(dbComplaintCount(seedStart, seedEnd, null));
    }

    @Test
    @DisplayName("2. 🔴 typeCd 는 민원에만 걸린다 (D3) — riskAlert.total 불변, 민원 total 만 감소")
    void case02_typeFilterDoesNotTouchRiskAlert() {
        DashboardKpiVO base = getKpi("");
        // 어떤 유형을 걸어도 성립해야 한다 — 구간에서 가장 많은 유형을 DB 에서 골라 쓴다(상수 회피)
        String typeCd = dominantTypeCd();
        assertThat(typeCd).as("구간에 민원이 없어 유형 필터를 검증할 수 없다").isNotNull();

        DashboardKpiVO typed = getKpi("&typeCd=" + typeCd);

        // (1) 🔴 알림 집계는 그대로다 — 알림 이력에는 민원유형 컬럼 자체가 없다
        assertThat(typed.getRiskAlert().getTotal()).isEqualTo(base.getRiskAlert().getTotal());
        assertThat(countsByLevel(typed.getRiskAlert())).isEqualTo(countsByLevel(base.getRiskAlert()));

        // (2) 민원만 줄어든다 — 같은 조건의 DB count 와 일치하고, 무필터보다 작다
        long dbTyped = dbComplaintCount(seedStart, seedEnd, "c.cvlcpt_type_cd = '" + typeCd + "'");
        assertThat(typed.getTotal()).isEqualTo(dbTyped);
        assertThat(base.getTotal()).as("무필터 민원이 0건이라 '감소'를 검증할 수 없다").isPositive();
        assertThat(typed.getTotal()).isLessThan(base.getTotal());
    }

    @Test
    @DisplayName("3. 🔴 riskLvlCd 는 알림에만 걸린다 (D8) — 민원 total 불변, riskAlert 만 감소")
    void case03_riskLevelFilterDoesNotTouchComplaints() {
        DashboardKpiVO base = getKpi("");
        DashboardKpiVO crit = getKpi("&riskLvlCd=" + LVL_CRIT);

        // (1) 🔴 민원 집계는 그대로다 — 필터 없는 응답과 직접 비교한다(숫자를 박지 않는다)
        assertThat(crit.getTotal()).isEqualTo(base.getTotal());
        assertThat(crit.getPrevTotal()).isEqualTo(base.getPrevTotal());
        assertThat(crit.getDeltaPct()).isEqualTo(base.getDeltaPct());

        // (2) 알림만 CRIT 로 좁혀진다 (실측 4)
        long dbCrit = dbAlertCount(seedStart, seedEnd, "h.risk_lvl_cd = '" + LVL_CRIT + "'");
        assertThat(dbCrit).as("구간에 CRIT 알림이 없어 단계 필터를 검증할 수 없다").isPositive();

        RiskAlertKpiVO riskAlert = crit.getRiskAlert();
        assertThat(riskAlert.getTotal()).isEqualTo(dbCrit);
        assertThat(riskAlert.getTotal()).isLessThan(base.getRiskAlert().getTotal());
        assertThat(riskAlert.getBreakdown()).extracting(RiskLevelCountVO::getRiskLvlCd)
                .containsOnly(LVL_CRIT);
        assertThat(sumOf(riskAlert)).isEqualTo(riskAlert.getTotal());
    }

    @Test
    @DisplayName("4. sggCd 는 공통 필터 — 알림·민원이 함께 줄어든다")
    void case04_sggFilterNarrowsBothSides() {
        DashboardKpiVO base = getKpi("");
        DashboardKpiVO jeju = getKpi("&sggCd=" + SGG_JEJU);

        // (1) 알림 — LEFT(emd_cd,5) 대조 (실측 15/18)
        long dbAlert = dbAlertCount(seedStart, seedEnd, "LEFT(h.emd_cd, 5) = '" + SGG_JEJU + "'");
        assertThat(dbAlert).isPositive();
        assertThat(jeju.getRiskAlert().getTotal()).isEqualTo(dbAlert);
        assertThat(jeju.getRiskAlert().getTotal()).isLessThan(base.getRiskAlert().getTotal());
        assertThat(countsByLevel(jeju.getRiskAlert()))
                .isEqualTo(dbAlertCountsByLevel(seedStart, seedEnd,
                        "LEFT(h.emd_cd, 5) = '" + SGG_JEJU + "'"));
        assertThat(sumOf(jeju.getRiskAlert())).isEqualTo(jeju.getRiskAlert().getTotal());

        // (2) 민원 — 같은 시군구로 함께 좁혀진다
        long dbComplaint = dbComplaintCount(seedStart, seedEnd, "LEFT(c.emd_cd, 5) = '" + SGG_JEJU + "'");
        assertThat(jeju.getTotal()).isEqualTo(dbComplaint);
        assertThat(base.getTotal()).isPositive();
        assertThat(jeju.getTotal()).isLessThan(base.getTotal());
    }

    @Test
    @DisplayName("5. 🔴 데이터 없는 기간 — riskAlert 가 null 이 아니라 빈 집계(total=0, breakdown=[])")
    void case05_emptyPeriodReturnsEmptyAggregate() {
        // 알림 시드 최소일보다 앞선 한 달 — min(ocrn_dt) 기준이라 알림이 존재할 수 없다
        LocalDate emptyEnd = seedStart.minusDays(1);
        LocalDate emptyStart = emptyEnd.minusMonths(1);
        assertThat(dbAlertCount(emptyStart, emptyEnd, null))
                .as("빈 구간을 잡지 못했다 — 시드 최소일 이전인데 알림이 있다").isZero();

        DashboardKpiVO kpi = getKpi(emptyStart, emptyEnd, "");

        RiskAlertKpiVO riskAlert = kpi.getRiskAlert();
        assertThat(riskAlert).as("빈 구간에서 riskAlert 가 통째로 누락되면 프론트가 0 을 못 그린다").isNotNull();
        assertThat(riskAlert.getTotal()).isZero();
        assertThat(riskAlert.getBreakdown()).isNotNull().isEmpty();

        // 원시 JSON 에서도 null 이 아니어야 한다 (VO 만 보면 역직렬화가 가려줄 수 있다)
        String raw = restTemplate.getForObject(kpiUrl(emptyStart, emptyEnd, ""), String.class);
        assertThat(raw).contains("\"riskAlert\"").doesNotContain("\"riskAlert\":null");
        assertThat(raw).contains("\"breakdown\":[]");
    }

    // ================================================================= 케이스 6·7 (TSK-4085)

    @Test
    @DisplayName("6. 데이터 현황 — 200 · cvlcptCnt >= 0 · lastOcrnDt 가 실제 MAX(ocrn_dt) 와 같다")
    void case06_dataStatusReturnsLastOcrnDtAndCount() {
        ResponseEntity<DataStatusVO> response =
                restTemplate.getForEntity(DATA_STATUS_URL, DataStatusVO.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        DataStatusVO body = response.getBody();
        assertThat(body).isNotNull();

        // (1) 건수 — 대시보드 조회 정책(99 제외 · emd_cd NOT NULL)과 같은 모수를 세야 한다.
        //     상수를 박지 않고 같은 조건의 집계와 대조한다.
        //     🔁 2026-10-02 TSK-4197: 집계식은 selectKpi 와 같은 **행 단위 count(*)** 다
        //     (DISTINCT 기준 폐기 — mapper/complaint/ComplaintMapper.xml 상단 「민원 집계 기준」 주석).
        Long dbCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt c "
                        + "WHERE c.cvlcpt_type_cd <> '99' AND c.emd_cd IS NOT NULL", Long.class);
        assertThat(body.getCvlcptCnt()).isNotNegative().isEqualTo(dbCnt == null ? 0L : dbCnt);

        // (2) 최종 발생일시 — 출처가 MAX(ocrn_dt) 다(load_dt 가 아니다 — D7).
        String dbMax = jdbcTemplate.queryForObject(
                "SELECT to_char(max(c.ocrn_dt), 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM parking.tb_cvlcpt c "
                        + "WHERE c.cvlcpt_type_cd <> '99' AND c.emd_cd IS NOT NULL", String.class);
        if (dbMax == null) {
            assertThat(body.getLastOcrnDt()).as("민원이 없으면 null 이다").isNull();
        } else {
            assertThat(body.getLastOcrnDt()).as("최종 발생일시가 MAX(ocrn_dt) 와 다르다").isNotNull();
            assertThat(body.getLastOcrnDt().toString()).startsWith(dbMax.substring(0, 10));
        }
    }

    @Test
    @DisplayName("7. startDt > endDt 는 400 — 대시보드에도 역전 검증이 걸린다 (TSK-4085 D9)")
    void case07_reversedRangeIsRejected() {
        // 🔴 날짜를 박지 않는다 — 시드 구간과 무관하게 성립하는 역전만 만든다
        LocalDate today = LocalDate.now();
        ResponseEntity<String> res = restTemplate.getForEntity(
                KPI_URL + "?startDt=" + today + "&endDt=" + today.minusDays(1), String.class);

        // 🔴 message 는 단언하지 않는다 — devtools 가 없는 ./gradlew test 에서는 응답에 없다
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- 헬퍼 (호출)

    // ================================================================= 케이스 8·9 (TSK-4197)
    // 🔁 복원된 불변식 — 민원 집계를 행 단위 count(*) 로 되돌리면서 되찾은 등식이다.

    /**
     * 🔴 <b>KPI total == Σ 읍면동 count.</b> 두 집계({@code selectKpi} · {@code selectRegionRanks})는
     * 같은 모집단·같은 식이라 전역 조회에서는 합이 정확히 같아야 한다.
     *
     * <p>DISTINCT 기준(2026-09-04 ~ 2026-10-02)에서는 같은 본문 민원이 여러 동에 걸쳐 동마다 1건씩
     * 세어 <b>Σ 동별 > total</b> 이 됐다(실측: 정형 문구 하나가 46~53개 동에 흩어져 있었다).
     * 되돌아가면 이 케이스가 깨진다.
     *
     * <p>⚠️ 전역(필터 없음)에서만 단언한다 — {@code sggCd} 를 걸면 KPI 와 목록이 같은 필터를 받지만
     * {@code HAVING count > 0} 때문에 0건 동이 빠지는 것은 양쪽 모두 같아 결과는 여전히 같다.
     * 그래도 가장 단순한 형태로 못박는다.
     */
    @Test
    @DisplayName("8. 🔁 불변식 — KPI total 이 읍면동별 count 의 합과 같다 (TSK-4197)")
    void case08_kpiTotalEqualsSumOfRegionCounts() {
        DashboardKpiVO kpi = getKpi("");
        assertThat(kpi.getTotal()).as("구간에 민원이 0건이라 불변식을 검증할 수 없다").isPositive();

        List<RegionRankVO> regions = getRegions("");
        long sum = regions.stream().mapToLong(RegionRankVO::getCount).sum();

        assertThat(sum)
                .as("Σ 읍면동 count != KPI total — 민원 집계가 행 단위가 아닌 기준으로 되돌아갔다")
                .isEqualTo(kpi.getTotal());
    }

    /**
     * 🔴 <b>동별 유형 breakdown 합 == 그 동 count.</b> {@code selectTypeBreakdown} 과
     * {@code selectRegionRanks} 가 같은 모집단·같은 식이어야 성립한다.
     */
    @Test
    @DisplayName("9. 🔁 불변식 — 각 읍면동의 유형별 breakdown 합이 그 동 count 와 같다 (TSK-4197)")
    void case09_regionBreakdownSumEqualsRegionCount() {
        List<RegionRankVO> regions = getRegions("");
        assertThat(regions).as("읍면동 순위가 비어 불변식을 검증할 수 없다").isNotEmpty();

        for (RegionRankVO region : regions) {
            assertThat(region.getBreakdown())
                    .as("emd=%s 의 breakdown 이 비었다", region.getEmdNm())
                    .isNotNull().isNotEmpty();
            long sum = region.getBreakdown().stream()
                    .mapToLong(jeju.datahub.complaint.service.TypeCountVO::getCount)
                    .sum();
            assertThat(sum)
                    .as("emd=%s 의 유형별 합(%d) != 그 동 count(%d)", region.getEmdNm(), sum, region.getCount())
                    .isEqualTo(region.getCount());
        }
    }

    private List<RegionRankVO> getRegions(String extraQuery) {
        ResponseEntity<RegionRankVO[]> response = restTemplate.getForEntity(
                REGIONS_URL + "?startDt=" + seedStart + "&endDt=" + seedEnd + extraQuery,
                RegionRankVO[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return List.of(response.getBody());
    }

    private DashboardKpiVO getKpi(String extraQuery) {
        return getKpi(seedStart, seedEnd, extraQuery);
    }

    private DashboardKpiVO getKpi(LocalDate start, LocalDate end, String extraQuery) {
        ResponseEntity<DashboardKpiVO> response =
                restTemplate.getForEntity(kpiUrl(start, end, extraQuery), DashboardKpiVO.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /** 기간은 항상 startDt/endDt 로 명시한다 — unit 에 의존하지 않기 위함이다 */
    private String kpiUrl(LocalDate start, LocalDate end, String extraQuery) {
        return KPI_URL + "?startDt=" + start + "&endDt=" + end + extraQuery;
    }

    // ---------------------------------------------------------------- 헬퍼 (응답 집계)

    private long sumOf(RiskAlertKpiVO riskAlert) {
        return riskAlert.getBreakdown().stream().mapToLong(RiskLevelCountVO::getCount).sum();
    }

    private Map<String, Long> countsByLevel(RiskAlertKpiVO riskAlert) {
        return riskAlert.getBreakdown().stream().collect(Collectors.toMap(
                row -> row.getRiskLvlCd().trim(), RiskLevelCountVO::getCount));
    }

    // ---------------------------------------------------------------- 헬퍼 (기대값 = DB 직접 조회)

    /**
     * 알림 기대 건수. {@code selectRiskAlertCounts} 와 <b>같은 FROM·같은 기간 조건</b>을 쓴다
     * ({@code riskAlertFilters} 는 sggCd·riskLvlCd 만 얹는다 — 민원 전용 정책은 걸리지 않는다).
     * {@code extraWhere} 는 테스트가 만든 리터럴이라 사용자 입력 경로가 아니다.
     */
    private long dbAlertCount(LocalDate start, LocalDate end, String extraWhere) {
        String sql = "SELECT count(*) FROM parking.tb_risk_stp_noti_hstry h "
                + "WHERE h.ocrn_dt::date BETWEEN ? AND ?"
                + (extraWhere == null ? "" : " AND " + extraWhere);
        Long value = jdbcTemplate.queryForObject(sql, Long.class, start, end);
        return value == null ? 0L : value;
    }

    private Map<String, Long> dbAlertCountsByLevel(LocalDate start, LocalDate end, String extraWhere) {
        String sql = "SELECT h.risk_lvl_cd AS lvl, count(*) AS cnt "
                + "FROM parking.tb_risk_stp_noti_hstry h "
                + "WHERE h.ocrn_dt::date BETWEEN ? AND ?"
                + (extraWhere == null ? "" : " AND " + extraWhere)
                + " GROUP BY h.risk_lvl_cd";
        return jdbcTemplate.queryForList(sql, start, end).stream().collect(Collectors.toMap(
                row -> ((String) row.get("lvl")).trim(),
                row -> ((Number) row.get("cnt")).longValue()));
    }

    /**
     * 민원 기대 건수. {@code selectKpi} 의 {@code filters} 조각과 같은 조회 정책을 그대로 복사한다 —
     * 미분류(99) 제외 · {@code emd_cd IS NULL}(공간매칭 실패) 제외. 빠뜨리면 기대값이 부풀어 오른다.
     *
     * <p>🔁 2026-10-02 TSK-4197 — 집계식은 <b>행 단위 {@code count(*)}</b> 다. 같은 날 잠깐
     * {@code count(DISTINCT (cvlcpt_cn, cvlcpt_type_cd))} 였으나 사용자 결정으로 DISTINCT 기준 자체가
     * 폐기됐다(근거는 {@code mapper/complaint/ComplaintMapper.xml} 상단 「민원 집계 기준」 주석).
     * 대시보드와 민원현황({@code ComplaintMapper.selectKpi})이 <b>같은 식</b>이라는 요구는 그대로다.
     */
    private long dbComplaintCount(LocalDate start, LocalDate end, String extraWhere) {
        String sql = "SELECT count(*) FROM parking.tb_cvlcpt c "
                + "WHERE c.ocrn_dt::date BETWEEN ? AND ? "
                + "  AND c.cvlcpt_type_cd <> '99' AND c.emd_cd IS NOT NULL"
                + (extraWhere == null ? "" : " AND " + extraWhere);
        Long value = jdbcTemplate.queryForObject(sql, Long.class, start, end);
        return value == null ? 0L : value;
    }

    /** 구간에서 가장 많은 민원유형. 유형 코드를 소스에 박지 않기 위해 DB 에서 고른다. */
    private String dominantTypeCd() {
        List<String> types = jdbcTemplate.queryForList(
                "SELECT c.cvlcpt_type_cd FROM parking.tb_cvlcpt c "
                        + "WHERE c.ocrn_dt::date BETWEEN ? AND ? "
                        + "  AND c.cvlcpt_type_cd <> '99' AND c.emd_cd IS NOT NULL "
                        + "GROUP BY c.cvlcpt_type_cd ORDER BY count(*) DESC, c.cvlcpt_type_cd LIMIT 1",
                String.class, seedStart, seedEnd);
        return types.isEmpty() ? null : types.get(0).trim();
    }

    /** {@code ::text} 로 받아 파싱한다 — 드라이버별 {@code LocalDate} 매핑 차이를 타지 않게 한다 */
    private LocalDate queryDate(String sql) {
        String value = jdbcTemplate.queryForObject(sql, String.class);
        return value == null ? null : LocalDate.parse(value);
    }
}
