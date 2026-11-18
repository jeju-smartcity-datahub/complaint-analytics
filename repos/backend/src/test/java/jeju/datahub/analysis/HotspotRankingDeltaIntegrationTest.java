package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
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

import jeju.datahub.analysis.service.HotspotRankingResultVO;
import jeju.datahub.analysis.service.HotspotRankingZoneVO;
import jeju.datahub.complaint.service.ComplaintKpiVO;

/**
 * 분석레이어 순위 API 의 <b>증감 3필드</b>와 <b>{@code RANK()} · Jenks 미사용 전환</b> 회귀 테스트
 * ({@code GET /api/hotspots/ranking}, {@code GET /api/complaints/gis}).
 *
 * <p>무엇을 지키는가 — 네 가지다.
 * <ol>
 *   <li><b>Jenks 등급을 읽지 않는다</b>. shortage 응답 모집단은 등급 필터가 아니라
 *       {@code out_cvlcpt_sum > 0} 하나가 정하고, KPI {@code shortageCount} 도 그 모집단의
 *       {@code FLOOR(× 0.1)} 이다 — 종전의 {@code fn_shortage_count}(Jenks '심각' 수)가 아니다.
 *       🔴 <b>등급 경계가 옳은지는 단언하지 않는다</b> — 그 결함은 함수에 그대로 남아 있고,
 *       이 테스트가 지키는 것은 <b>그 값을 읽지 않는다는 사실</b>이다.</li>
 *   <li><b>증감 3필드</b>({@code deltaPct} · {@code shortageDeltaPp} · {@code deltaPctInferred})가
 *       view 별로 맞는 자리에만, 맞는 값으로 실린다.</li>
 *   <li><b>{@code RANK()} 전환</b> — 같은 값이면 같은 순위다.</li>
 *   <li>enforcement 의 {@code HAVING} — 직전 구간에만 단속이 있던 동이 {@code crdnCnt = 0} 으로
 *       목록에 섞이지 않는다.</li>
 * </ol>
 *
 * <h2>🔴 날짜·기대값을 상수로 박지 않는다</h2>
 * 민원·단속 적재분은 배치가 소유한다 — 재적재 한 번이면 구간이 통째로 옮겨간다(실제로 한 번
 * 일어났다). 그래서 조회 구간은 {@link #resolveDataRange()} 가 <b>DB 에서</b>
 * {@code LEAST(max(ocrn_dt), max(crdn_dt))} 로 읽고, 기대값은 리터럴이 아니라 같은 조건의 SQL 로
 * 그때그때 센 값이다.
 *
 * <p>🔴 <b>불변식이 실행되지 않는 창이면 건너뛰지 않고 창을 다시 고른다</b> —
 * {@link #firstWindowSatisfying} 이 길이 후보({@link #WINDOW_DAY_CANDIDATES})를 차례로 재서
 * 조건을 만족하는 첫 창을 쓴다. {@code @Disabled}·조기 return 으로 넘기면 초록불인 채 아무것도
 * 지키지 않는다.
 *
 * <p>전략은 {@code HotspotRankingIntegrationTest} 와 같다 — {@code RANDOM_PORT} +
 * {@link TestRestTemplate}, {@code test} 프로파일이 보안 체인을 꺼서 인증 없이 호출된다.
 * DB 를 바꾸지 않지만, 인증 경로가 남길 수 있는 로그인 이력은
 * {@code lgn_sn > startSn} 범위로만 정리한다(🔴 {@code OR user_id IS NULL} 로 넓히지 않는다 —
 * 실패 행은 {@code user_id} 가 null 이라 이 테스트가 만들지 않은 실 이력까지 지운다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HotspotRankingDeltaIntegrationTest {

    private static final String RANKING_URL = "/api/hotspots/ranking";
    private static final String GIS_URL = "/api/complaints/gis";

    /**
     * 조회 창 길이(일) 후보. 첫 값이 기본이고, 불변식이 실행되지 않는 창이면 다음 길이를 쓴다.
     * 🔴 날짜가 아니라 <b>길이</b>다 — 끝점은 항상 데이터에서 읽은 {@link #dataEnd} 다.
     */
    private static final int[] WINDOW_DAY_CANDIDATES = { 30, 14, 7, 60, 90, 180 };

    /** 등급 필터 없는 모집단 조회 — 매퍼 {@code selectShortage} 의 호출·WHERE 와 같은 식이다. */
    private static final String SHORTAGE_FN =
            "parking.fn_jenks_shortage(?::date, ?::date, NULL, NULL, NULL, NULL::text)";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 민원·단속이 모두 있는 상한일. 세 소스가 겹치는 지점이라 구간의 끝점이 된다. */
    private LocalDate dataEnd;

    /** 이 테스트가 만든 로그인 이력 행의 경계. 이 값보다 큰 {@code lgn_sn} 만 정리한다. */
    private long startSn;

    @BeforeEach
    void resolveDataRange() {
        dataEnd = jdbcTemplate.queryForObject(
                "SELECT LEAST((SELECT max(ocrn_dt)::date FROM parking.tb_cvlcpt),"
                        + "             (SELECT max(crdn_dt)::date FROM parking.tb_crdn_dsctn))",
                (rs, rowNum) -> rs.getObject(1, LocalDate.class));
        assertThat(dataEnd)
                .as("민원·단속 원천이 비어 있다 — 배치 적재분을 먼저 확인하라")
                .isNotNull();

        Long sn = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(lgn_sn), 0) FROM parking.tb_lgn_hstry", Long.class);
        startSn = sn != null ? sn : 0L;
    }

    @AfterEach
    void cleanUpLoginHistory() {
        jdbcTemplate.update("DELETE FROM parking.tb_lgn_hstry WHERE lgn_sn > ?", startSn);
    }

    // ────────────────────────────────────────────────── 1·2. Jenks 미사용 회귀

    @Test
    @DisplayName("1. Jenks 미사용 — shortage 응답 zone 수 == 등급 필터 없는 모집단(out_cvlcpt_sum > 0) 수")
    void shortageZoneCountEqualsUngradedPopulation() {
        Window w = defaultWindow();

        long population = shortagePopulation(w.curStart(), w.curEnd());
        assertThat(population)
                .as("모집단이 0 이면 이 불변식이 한 번도 실행되지 않는다 — 적재분 확인")
                .isPositive();

        List<HotspotRankingZoneVO> zones = rank("shortage", w);
        assertThat(zones)
                .as("등급 필터가 걸려 있으면 응답이 모집단보다 작다 (종전 '심각' 필터 회귀)")
                .hasSize((int) population);
    }

    @Test
    @DisplayName("2. Jenks 미사용 — KPI shortageCount == FLOOR(등급 필터 없는 모집단 × 0.1)")
    void shortageKpiEqualsTenPercentOfUngradedPopulation() {
        Window w = defaultWindow();

        long population = shortagePopulation(w.curStart(), w.curEnd());
        assertThat(population).as("모집단이 0 이면 10% 판정 자체가 무의미").isPositive();

        // 🔴 FLOOR 는 SQL 에 맡긴다 — double 의 0.1 은 정확히 1/10 이 아니라 경계에서 갈릴 수 있다.
        Long expected = jdbcTemplate.queryForObject(
                "SELECT FLOOR(?::numeric * 0.1)", Long.class, population);

        ComplaintKpiVO kpi = kpi(w);
        assertThat(kpi.shortageCount())
                .as("KPI 가 fn_shortage_count(Jenks '심각' 수)를 다시 세고 있으면 어긋난다")
                .isEqualTo(expected);
    }

    // ────────────────────────────────────────────────── 3~5·8. enforcement

    @Test
    @DisplayName("3. enforcement — 모든 zone 의 crdnCnt 가 0 보다 크다 (HAVING)")
    void enforcementExcludesZonesWithNoCurrentCrackdown() {
        Window w = defaultWindow();
        List<HotspotRankingZoneVO> zones = rank("enforcement", w);
        assertThat(zones).isNotEmpty();

        assertThat(zones)
                .as("직전 구간에만 단속이 있던 동이 crdnCnt=0 으로 섞였다 — HAVING 이 빠졌다")
                .allSatisfy(z -> assertThat(z.getCrdnCnt()).isPositive());
    }

    @Test
    @DisplayName("4. enforcement — 상위 3개 동의 deltaPct 가 현재·직전 건수 손검산값과 같다")
    void enforcementDeltaPctMatchesHandCalculation() {
        Window w = defaultWindow();
        List<HotspotRankingZoneVO> zones = rank("enforcement", w);
        assertThat(zones).isNotEmpty();

        Map<String, long[]> counts = crdnCountsByEmd(w);

        int checked = 0;
        for (HotspotRankingZoneVO z : zones.subList(0, Math.min(3, zones.size()))) {
            long[] cp = counts.get(z.getEmdCd());
            assertThat(cp).as("zone %s 가 직접 집계 결과에 없다", z.getEmdCd()).isNotNull();

            assertThat(z.getCrdnCnt())
                    .as("zone %s — 현재 구간 FILTER 가 두 구간 합을 세고 있다", z.getEmdCd())
                    .isEqualTo(cp[0]);

            // 서비스와 같은 규칙: 직전 0건이면 분모 1 치환.
            long diff = cp[0] - cp[1];
            int expected = cp[1] == 0
                    ? (int) Math.round(diff / 1.0 * 100)
                    : (int) Math.round((double) diff / cp[1] * 100);

            assertThat(z.getDeltaPct())
                    .as("zone %s — 현재 %d · 직전 %d 의 증감률", z.getEmdCd(), cp[0], cp[1])
                    .isEqualTo(expected);
            assertThat(z.getShortageDeltaPp())
                    .as("enforcement 에 부족률 %%p 가 실렸다 — 단위가 다른 두 값이 한 필드에 섞였다")
                    .isNull();
            checked++;
        }
        assertThat(checked).as("단속 zone 이 0건이면 불변식이 실행되지 않은 것이다").isPositive();
    }

    @Test
    @DisplayName("5. enforcement — 출력은 crdnCnt DESC · emdCd ASC 이고 rank 는 비감소다")
    void enforcementOrderedByCrdnCntThenEmdCd() {
        Window w = defaultWindow();
        List<HotspotRankingZoneVO> zones = rank("enforcement", w);
        assertThat(zones).isNotEmpty();

        for (int i = 1; i < zones.size(); i++) {
            HotspotRankingZoneVO prev = zones.get(i - 1);
            HotspotRankingZoneVO cur = zones.get(i);
            if (prev.getCrdnCnt() == cur.getCrdnCnt()) {
                assertThat(cur.getEmdCd())
                        .as("동률 tie-break(emd_cd ASC)가 깨졌다 — 출력 순서가 비결정적이다")
                        .isGreaterThan(prev.getEmdCd());
            } else {
                assertThat(cur.getCrdnCnt())
                        .as("단속 건수가 rank 순으로 비증가여야 한다")
                        .isLessThan(prev.getCrdnCnt());
            }
            assertThat(cur.getRank())
                    .as("rank 는 출력 순서를 따라 비감소여야 한다")
                    .isGreaterThanOrEqualTo(prev.getRank());
        }
    }

    @Test
    @DisplayName("8. enforcement — 직전 구간 단속 0건인 동은 deltaPctInferred = true 다")
    void enforcementMarksZonesWithoutPreviousCrackdownAsInferred() {
        // 🔴 그런 동이 없는 창이면 건너뛰지 않고 창을 다시 고른다.
        Window w = firstWindowSatisfying(
                win -> crdnCountsByEmd(win).values().stream().anyMatch(cp -> cp[1] == 0),
                "직전 구간 단속 0건인 동");

        Map<String, long[]> counts = crdnCountsByEmd(w);
        Set<String> expectedInferred = counts.entrySet().stream()
                .filter(e -> e.getValue()[1] == 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(expectedInferred).isNotEmpty();

        List<HotspotRankingZoneVO> zones = rank("enforcement", w);
        Set<String> actualInferred = zones.stream()
                .filter(HotspotRankingZoneVO::isDeltaPctInferred)
                .map(HotspotRankingZoneVO::getEmdCd)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actualInferred)
                .as("직전 0건(분모 1 치환)인 동 집합이 어긋났다")
                .isEqualTo(expectedInferred);
    }

    // ────────────────────────────────────────────────── 6·8-1·8-2. shortage

    @Test
    @DisplayName("6. shortage — shortageDeltaPp 부호가 (현재 부족률 − 직전 부족률) 부호와 같다")
    void shortageDeltaPpSignFollowsRatioDifference() {
        Window w = defaultWindow();
        List<HotspotRankingZoneVO> zones = rank("shortage", w);
        assertThat(zones).isNotEmpty();

        Map<String, BigDecimal> curRatio = shortageRatioByEmd(w.curStart(), w.curEnd());
        Map<String, BigDecimal> prevRatio = shortageRatioByEmd(w.prevStart(), w.prevEnd());

        int checked = 0;
        int nonZero = 0;
        for (HotspotRankingZoneVO z : zones) {
            BigDecimal pp = z.getShortageDeltaPp();
            if (pp == null) {
                continue; // 직전 분모 0 — 8-2 가 따로 본다
            }
            BigDecimal cur = curRatio.get(z.getEmdCd());
            BigDecimal prev = prevRatio.get(z.getEmdCd());
            assertThat(cur).as("zone %s 가 현재 구간 함수 결과에 없다", z.getEmdCd()).isNotNull();
            assertThat(prev).as("zone %s 가 직전 구간 함수 결과에 없다", z.getEmdCd()).isNotNull();

            // 응답은 %p 소수 1자리로 반올림된 값이라, 미세한 차이는 0.0 으로 접힌다.
            BigDecimal rawPp = cur.subtract(prev).multiply(BigDecimal.valueOf(100));
            if (pp.signum() == 0) {
                assertThat(rawPp.abs())
                        .as("zone %s — 0.0%%p 인데 원 차이가 반올림 범위를 넘는다", z.getEmdCd())
                        .isLessThan(new BigDecimal("0.05"));
            } else {
                assertThat(pp.signum())
                        .as("zone %s — 부호가 뒤집혔다 (현재 %s · 직전 %s)", z.getEmdCd(), cur, prev)
                        .isEqualTo(rawPp.signum());
                nonZero++;
            }
            checked++;
        }
        assertThat(checked).as("shortageDeltaPp 가 전부 null 이면 직전 구간 조인이 죽은 것이다").isPositive();
        assertThat(nonZero).as("증감이 전부 0.0%p 면 두 구간이 같은 값을 읽고 있는 것이다").isPositive();
    }

    @Test
    @DisplayName("8-1. shortage — 현재·직전 호출이 같은 모집단(경계 보유 읍면동 전량)을 준다")
    void shortageCurrentAndPreviousCallsShareSamePopulation() {
        Window w = defaultWindow();

        long curAll = fnRowCount(w.curStart(), w.curEnd());
        long prevAll = fnRowCount(w.prevStart(), w.prevEnd());
        Long admdst = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_admdst"
                        + " WHERE bndry_geom IS NOT NULL AND emd_cd IS NOT NULL",
                Long.class);

        assertThat(admdst).isNotNull();
        assertThat(admdst).as("경계 보유 읍면동이 0 이면 비교가 무의미").isPositive();
        assertThat(curAll)
                .as("현재·직전 호출의 모집단이 다르다 — 한쪽에 등급 필터가 남아 있다")
                .isEqualTo(prevAll);
        assertThat(curAll)
                .as("등급 필터가 NULL 이면 경계 보유 읍면동 전량이어야 한다")
                .isEqualTo(admdst);

        // 🔴 따라서 LEFT JOIN 의 unmatched 는 0 이다 — p.out_emd_cd IS NULL 로는 판정하지 않는다.
        Long unmatched = jdbcTemplate.queryForObject(
                "WITH cur AS (SELECT out_emd_cd cd FROM " + SHORTAGE_FN + " WHERE out_cvlcpt_sum > 0),"
                        + "     prv AS (SELECT out_emd_cd cd FROM " + SHORTAGE_FN + ")"
                        + " SELECT count(*) FROM cur c LEFT JOIN prv p ON p.cd = c.cd WHERE p.cd IS NULL",
                Long.class,
                w.curStart().toString(), w.curEnd().toString(),
                w.prevStart().toString(), w.prevEnd().toString());
        assertThat(unmatched).isZero();
    }

    @Test
    @DisplayName("8-2. shortage — 직전 분모 0 인 동만 deltaPctInferred = true 이고 그 동만 pp 가 null 이다")
    void shortageInferredMatchesPreviousZeroDenominator() {
        Window w = defaultWindow();

        // 🔴 대상 행 수를 SQL 로 먼저 센다 — 있으면 ①, 없으면 ② 로 단언이 갈린다.
        List<String> expectedInferredList = jdbcTemplate.queryForList(
                "WITH cur AS (SELECT out_emd_cd cd FROM " + SHORTAGE_FN + " WHERE out_cvlcpt_sum > 0),"
                        + "     prv AS (SELECT out_emd_cd cd, (out_pklt_sum + out_cvlcpt_sum) AS denom"
                        + "               FROM " + SHORTAGE_FN + ")"
                        + " SELECT c.cd FROM cur c LEFT JOIN prv p ON p.cd = c.cd"
                        + "  WHERE COALESCE(p.denom, 0) = 0",
                String.class,
                w.curStart().toString(), w.curEnd().toString(),
                w.prevStart().toString(), w.prevEnd().toString());
        Set<String> expectedInferred = new TreeSet<>(expectedInferredList);

        List<HotspotRankingZoneVO> zones = rank("shortage", w);
        assertThat(zones).isNotEmpty();

        Set<String> actualInferred = zones.stream()
                .filter(HotspotRankingZoneVO::isDeltaPctInferred)
                .map(HotspotRankingZoneVO::getEmdCd)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> nullPp = zones.stream()
                .filter(z -> z.getShortageDeltaPp() == null)
                .map(HotspotRankingZoneVO::getEmdCd)
                .collect(Collectors.toCollection(TreeSet::new));

        if (expectedInferred.isEmpty()) {
            // ② 직전 분모 0 인 동이 없다 — 전 행이 false 이고 pp 가 전부 채워진다.
            assertThat(actualInferred)
                    .as("직전 분모 0 인 동이 없는데 inferred 가 켜졌다")
                    .isEmpty();
            assertThat(nullPp)
                    .as("직전 분모가 모두 > 0 인데 shortageDeltaPp 가 비었다")
                    .isEmpty();
        } else {
            // ① 그 동들만 true 이고, 그 동들만 pp 가 null 이다.
            assertThat(actualInferred)
                    .as("직전 분모 0 인 동 집합이 어긋났다")
                    .isEqualTo(expectedInferred);
            assertThat(nullPp)
                    .as("직전 분모 0 인 동은 0.0%p 가 아니라 null 이어야 한다")
                    .isEqualTo(expectedInferred);
        }
    }

    // ────────────────────────────────────────────────── 7. most-complaint

    @Test
    @DisplayName("7. most-complaint — deltaPct · shortageDeltaPp 가 전부 null 이고 inferred 는 false 다")
    void mostComplaintCarriesNoDeltaFields() {
        Window w = defaultWindow();
        List<HotspotRankingZoneVO> zones = rank("most-complaint", w);
        assertThat(zones).isNotEmpty();

        assertThat(zones)
                .as("민원 카드는 emdCompare[] 를 쓴다 — 같은 값을 두 경로로 내면 두 숫자가 갈린다")
                .allSatisfy(z -> {
                    assertThat(z.getDeltaPct()).isNull();
                    assertThat(z.getShortageDeltaPp()).isNull();
                    assertThat(z.isDeltaPctInferred()).isFalse();
                });
    }

    // ────────────────────────────────────────────────── 9. RANK() 전환

    @Test
    @DisplayName("9. RANK() 전환 — 단속 건수·PSDR 이 같은 zone 은 rank 도 같다")
    void equalValuesShareTheSameRank() {
        // enforcement — 같은 crdnCnt 묶음이 있는 창을 데이터에서 고른다.
        Window ew = firstWindowSatisfying(
                win -> hasDuplicateValue(crdnCountsByEmd(win).values().stream()
                        .map(cp -> (Object) cp[0]).toList()),
                "단속 건수 동률 묶음");
        List<HotspotRankingZoneVO> enforcement = rank("enforcement", ew);
        assertThat(enforcement).isNotEmpty();
        assertSingleRankPerGroup(enforcement, z -> (Object) z.getCrdnCnt(), "단속 건수");

        // shortage — 같은 PSDR 묶음이 있는 창을 데이터에서 고른다.
        //   🔴 PSDR 은 응답에 없으므로 함수에서 읽어 묶는다(shortageRatio 는 4자리 반올림이라
        //      서로 다른 PSDR 이 같은 값으로 접힐 수 있다).
        Window sw = firstWindowSatisfying(
                win -> hasDuplicateValue(new ArrayList<>(psdrByEmd(win.curStart(), win.curEnd()).values())),
                "PSDR 동률 묶음");
        Map<String, BigDecimal> psdr = psdrByEmd(sw.curStart(), sw.curEnd());
        List<HotspotRankingZoneVO> shortage = rank("shortage", sw);
        assertThat(shortage).isNotEmpty();
        assertSingleRankPerGroup(shortage, z -> psdr.get(z.getEmdCd()), "PSDR");
    }

    /** 같은 값으로 묶었을 때 묶음마다 서로 다른 rank 가 1개뿐인지 — {@code RANK()} 의 정의 그대로. */
    private static void assertSingleRankPerGroup(List<HotspotRankingZoneVO> zones,
                                                 java.util.function.Function<HotspotRankingZoneVO, Object> keyFn,
                                                 String keyLabel) {
        Map<Object, Set<Long>> ranksByValue = new LinkedHashMap<>();
        Map<Object, Integer> membersByValue = new LinkedHashMap<>();
        for (HotspotRankingZoneVO z : zones) {
            Object key = keyFn.apply(z);
            assertThat(key).as("zone %s 의 %s 를 읽지 못했다", z.getEmdCd(), keyLabel).isNotNull();
            ranksByValue.computeIfAbsent(key, k -> new TreeSet<>()).add(z.getRank());
            membersByValue.merge(key, 1, Integer::sum);
        }
        for (Map.Entry<Object, Set<Long>> e : ranksByValue.entrySet()) {
            assertThat(e.getValue())
                    .as("%s=%s 인 zone 들이 서로 다른 rank 를 받았다 — ROW_NUMBER 회귀", keyLabel, e.getKey())
                    .hasSize(1);
        }
        long tiedGroups = membersByValue.values().stream().filter(n -> n > 1).count();
        assertThat(tiedGroups)
                .as("동률 묶음이 하나도 없으면 RANK() 와 ROW_NUMBER() 를 구분하지 못한다 — 창을 다시 골라야 한다")
                .isPositive();
    }

    // ────────────────────────────────────────────────── 구간 (날짜를 박지 않는다)

    /** 조회 구간 1벌 — 현재 구간과 직전 동일 길이 구간. 서비스의 {@code ComplaintUnit.prevStart} 와 같은 식이다. */
    private record Window(LocalDate curStart, LocalDate curEnd, LocalDate prevStart, LocalDate prevEnd) {
    }

    private Window window(int days) {
        LocalDate curStart = dataEnd.minusDays(days - 1L);
        return new Window(curStart, dataEnd, curStart.minusDays(days), curStart.minusDays(1L));
    }

    private Window defaultWindow() {
        return window(WINDOW_DAY_CANDIDATES[0]);
    }

    /**
     * 불변식이 실제로 실행되는 첫 창을 고른다. 🔴 조건을 만족하는 창이 하나도 없으면
     * <b>건너뛰지 않고 실패</b>한다 — 초록불인 채 아무것도 지키지 않는 것을 막는다.
     */
    private Window firstWindowSatisfying(Predicate<Window> condition, String what) {
        for (int days : WINDOW_DAY_CANDIDATES) {
            Window w = window(days);
            if (condition.test(w)) {
                return w;
            }
        }
        throw new AssertionError("어떤 창 길이에서도 " + what + " 을 찾지 못했다 — 적재분을 확인하라");
    }

    // ────────────────────────────────────────────────── 기대값 SQL

    /** 매퍼 {@code selectShortage} 와 같은 모집단(등급 필터 없음 + {@code out_cvlcpt_sum > 0}). */
    private long shortagePopulation(LocalDate start, LocalDate end) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + SHORTAGE_FN + " WHERE out_cvlcpt_sum > 0",
                Long.class, start.toString(), end.toString());
        return n != null ? n : 0L;
    }

    /** 등급 필터 없는 함수 반환 행 수(WHERE 없음) — 8-1 의 모집단 비교용. */
    private long fnRowCount(LocalDate start, LocalDate end) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + SHORTAGE_FN,
                Long.class, start.toString(), end.toString());
        return n != null ? n : 0L;
    }

    /** 부족률 = 민원수 ÷ (공급면수 + 민원수). 분모 0 이면 담지 않는다(매퍼의 null 규약과 같다). */
    private Map<String, BigDecimal> shortageRatioByEmd(LocalDate start, LocalDate end) {
        Map<String, BigDecimal> out = new TreeMap<>();
        jdbcTemplate.query(
                "SELECT out_emd_cd, out_pklt_sum, out_cvlcpt_sum FROM " + SHORTAGE_FN,
                rs -> {
                    BigDecimal pklt = rs.getBigDecimal(2);
                    BigDecimal cvlcpt = BigDecimal.valueOf(rs.getLong(3));
                    BigDecimal denom = (pklt != null ? pklt : BigDecimal.ZERO).add(cvlcpt);
                    if (denom.signum() > 0) {
                        out.put(rs.getString(1), cvlcpt.divide(denom, 10, RoundingMode.HALF_UP));
                    }
                },
                start.toString(), end.toString());
        return out;
    }

    /** PSDR 원값 — 응답에 없는 정렬 키라 함수에서 직접 읽는다. */
    private Map<String, BigDecimal> psdrByEmd(LocalDate start, LocalDate end) {
        Map<String, BigDecimal> out = new TreeMap<>();
        jdbcTemplate.query(
                "SELECT out_emd_cd, out_psdr FROM " + SHORTAGE_FN + " WHERE out_cvlcpt_sum > 0",
                rs -> {
                    out.put(rs.getString(1), rs.getBigDecimal(2));
                },
                start.toString(), end.toString());
        return out;
    }

    /**
     * 읍면동별 {@code [현재 구간 단속 건수, 직전 구간 단속 건수]}.
     * 매퍼 {@code selectEnforcement} 와 같은 WHERE·FILTER·HAVING 이다.
     */
    private Map<String, long[]> crdnCountsByEmd(Window w) {
        String cur = w.curStart().toString();
        String curEnd = w.curEnd().toString();
        String prev = w.prevStart().toString();
        String prevEnd = w.prevEnd().toString();

        Map<String, long[]> out = new TreeMap<>();
        jdbcTemplate.query(
                "SELECT c.emd_cd,"
                        + "       count(*) FILTER (WHERE c.crdn_dt::date BETWEEN ?::date AND ?::date) AS cur_cnt,"
                        + "       count(*) FILTER (WHERE c.crdn_dt::date BETWEEN ?::date AND ?::date) AS prev_cnt"
                        + "  FROM parking.tb_crdn_dsctn c"
                        + " WHERE c.crdn_dt::date BETWEEN ?::date AND ?::date"
                        + "   AND c.emd_cd IS NOT NULL"
                        + " GROUP BY c.emd_cd"
                        + " HAVING count(*) FILTER (WHERE c.crdn_dt::date BETWEEN ?::date AND ?::date) > 0",
                rs -> {
                    out.put(rs.getString(1), new long[] { rs.getLong(2), rs.getLong(3) });
                },
                cur, curEnd, prev, prevEnd, prev, curEnd, cur, curEnd);
        return out;
    }

    private static boolean hasDuplicateValue(List<?> values) {
        return values.size() != values.stream().distinct().count();
    }

    // ────────────────────────────────────────────────── 호출 헬퍼

    private List<HotspotRankingZoneVO> rank(String view, Window w) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("view", view);
        params.put("startDt", w.curStart().toString());
        params.put("endDt", w.curEnd().toString());

        ResponseEntity<HotspotRankingResultVO> response = restTemplate.exchange(
                RANKING_URL + "?view={view}&startDt={startDt}&endDt={endDt}",
                HttpMethod.GET, null, HotspotRankingResultVO.class, params);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getView()).isEqualTo(view);
        assertThat(response.getBody().getZones()).isNotNull();
        return response.getBody().getZones();
    }

    private ComplaintKpiVO kpi(Window w) {
        ResponseEntity<ComplaintKpiVO> response = restTemplate.exchange(
                GIS_URL + "?startDt={startDt}&endDt={endDt}",
                HttpMethod.GET, null, ComplaintKpiVO.class,
                Map.of("startDt", w.curStart().toString(), "endDt", w.curEnd().toString()));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }
}
