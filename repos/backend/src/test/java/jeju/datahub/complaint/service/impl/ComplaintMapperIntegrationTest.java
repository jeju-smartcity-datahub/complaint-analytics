package jeju.datahub.complaint.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.complaint.service.EmdCompareRow;
import jeju.datahub.complaint.service.EmdTopTypeRow;
import jeju.datahub.complaint.service.NegEmdCompareRow;

/**
 * 민원현황(GIS) 핵심 API 매퍼 통합 테스트.
 *
 * <p><b>무엇을 지키는 테스트인가</b> — PostgreSQL 전용 문법({@code FILTER}, {@code DISTINCT ON},
 * {@code NULLIF}, {@code LEFT JOIN tb_com_cd})이 매퍼 XML 에서 정확히 동작하는지. 단위 테스트로는
 * 검증 불가 — 실 DB 가 필요하다.
 *
 * <p><b>왜 기대값을 상수로 박지 않는가</b> — 가용 데이터(2026-08-31 실측 17,811건) 가
 * 배치 적재에 따라 늘 수 있어 카운트가 흔들린다. 대신 {@link JdbcTemplate} 으로 동일한
 * WHERE/FILTER 를 직접 실행해 기대 카운트를 계산한 뒤 매퍼 SELECT 결과와 1:1 비교한다.
 *
 * <p><b>조회 구간</b> — 🔴 날짜를 박지 않는다. {@link #resolveRange()} 가
 * {@code LEAST(max(ocrn_dt), max(crdn_dt))} 를 끝점으로 {@value #WINDOW_DAYS}일 창을 잡고, 직전
 * 구간은 같은 길이 바로 앞이다. 매퍼 SQL 의 prev/current 분기 + 정렬 + HAVING 을 모두 검증한다.
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra}) 필요. H2 없음.
 */
@SpringBootTest
class ComplaintMapperIntegrationTest {

    /**
     * 조회 창 길이(일). 🔴 날짜가 아니라 <b>길이</b>다 — 끝점은 {@link #resolveRange()} 가 DB 에서 읽는다.
     */
    private static final int WINDOW_DAYS = 30;

    /**
     * 조회 구간 — 🔴 상수로 박지 않는다.
     * 종전엔 {@code 2025-12-01~12-31} 을 박아 두었는데, 민원 적재분이 배치 재적재로 통째로 옮겨가자
     * (이 구간이 비게 되어) {@code selectEmdCompare} 정렬 테스트가 빈 목록으로 깨졌다.
     * 이제 {@code LEAST(max(ocrn_dt), max(crdn_dt))} 를 끝점으로 길이 {@link #WINDOW_DAYS} 창을 잡고,
     * 직전 구간은 같은 길이 바로 앞이다(매퍼의 prev 정의 = {@code [prevStart, curStart)}).
     */
    private LocalDate curStart;
    private LocalDate curEnd;
    private LocalDate prevStart;

    private static final String SGG_JEJU = "50110";   // 제주시
    private static final String SGG_SEOG  = "50130";   // 서귀포시

    @Autowired
    private ComplaintMapper complaintMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * §3 KPI 의 filters 가 자동으로 {@code cvlcpt_type_cd<>'99'} + {@code emd_cd IS NOT NULL} 적용.
     * §7/§8 도 동일. 가용 데이터 17,811건 중 9,073건(`99`) 제외 = 8,738건. 통합 테스트 시작 전 sanity check.
     */
    /** 🔴 조회 구간을 데이터에서 읽는다 — 날짜를 코드에 박지 않는다. */
    @BeforeEach
    void resolveRange() {
        curEnd = jdbc.queryForObject(
                "SELECT LEAST((SELECT max(ocrn_dt)::date FROM parking.tb_cvlcpt),"
              + "             (SELECT max(crdn_dt)::date FROM parking.tb_crdn_dsctn))",
                (rs, rowNum) -> rs.getObject(1, LocalDate.class));
        assertThat(curEnd).as("민원·단속 원천이 비어 있다 — 배치 적재분을 먼저 확인하라").isNotNull();
        curStart  = curEnd.minusDays(WINDOW_DAYS - 1L);
        prevStart = curStart.minusDays(WINDOW_DAYS);
    }

    @BeforeEach
    @DisplayName("사전 검증 — 가용 데이터의 filters 적용 후 건수")
    void sanity_check_filters() {
        Integer usable = jdbc.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt "
              + "WHERE cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL",
                Integer.class);
        // 2026-08-31 실측 = 8,738. 배치 적재로 늘면 같이 늘어도 OK — 단위 일관성만 확인.
        assertThat(usable).isNotNull().isPositive();
    }

    // ------------------------------------------------------------------------
    // §7 selectEmdCompare
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("§7 selectEmdCompare — 매퍼 결과와 JdbcTemplate 직접 집계 카운트가 1:1 일치")
    void selectEmdCompare_matches_jdbc_count() {
        List<EmdCompareRow> rows = complaintMapper.selectEmdCompare(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        // 직접 JdbcTemplate 으로 같은 조건 집계 — 매퍼 SELECT 의 동작을 외부에서 검증
        org.springframework.jdbc.core.RowMapper<Map.Entry<String, Map<String, Long>>> emdAggMapper =
                (rs, i) -> Map.entry(rs.getString("emd_cd"),
                        // prev_count 가 0 이면 매퍼 응답에서 deltaPctInferred=true 가 됨 — 검증 단순화 위해 raw 만
                        Map.of("count", rs.getLong("count"), "prev_count", rs.getLong("prev_count")));
        Object[] emdAggArgs = new Object[] {
                curStart, curEnd,            // current count BETWEEN
                prevStart, curEnd, curStart, // prev BETWEEN (prevStart, curStart 미포함)
                prevStart, curEnd,            // outer WHERE BETWEEN
                SGG_JEJU,                       // sgg filter
                curStart, curEnd              // HAVING
        };
        Map<String, Long> expectedByEmd = jdbc.query(
                "SELECT c.emd_cd, "
              // 🔁 2026-10-02: 매퍼가 DISTINCT 기준(2026-09-04 cvlcpt_cn → 09-21 (cvlcpt_cn,
              //    cvlcpt_type_cd))을 폐기하고 **행 단위 count(*)** 로 돌아갔다 — 대조 SQL 도 같은 식이어야 한다.
              //    근거는 mapper/complaint/ComplaintMapper.xml 상단 「민원 집계 기준」 주석.
              //    매퍼·대조 SQL 양쪽 모두 '99' 제외(매퍼는 filters 조각) — 같은 모집단.
              + "       count(*) FILTER (WHERE c.ocrn_dt::date BETWEEN ? AND ?) AS count, "
              + "       count(*) FILTER (WHERE c.ocrn_dt::date BETWEEN ? AND ? "
              + "                        AND c.ocrn_dt::date < ?) AS prev_count "
              + "FROM parking.tb_cvlcpt c "
              + "WHERE c.ocrn_dt::date BETWEEN ? AND ? "
              + "  AND c.cvlcpt_type_cd <> '99' "
              + "  AND c.emd_cd IS NOT NULL "
              + "  AND LEFT(c.emd_cd, 5) = ? "
              + "GROUP BY c.emd_cd "
              + "HAVING count(*) FILTER (WHERE c.ocrn_dt::date BETWEEN ? AND ?) > 0",
                emdAggMapper, emdAggArgs
        ).stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().get("count")));

        // 매퍼가 반환한 행 수 = HAVING 통과한 emd 수. 0건 zone 제외는 매퍼 SQL 의 정책 그대로.
        assertThat(rows).hasSize(expectedByEmd.size());

        // 매퍼가 매 행의 count 를 정확히 반환
        for (EmdCompareRow row : rows) {
            Long expectedCount = expectedByEmd.get(row.getEmdCd());
            assertThat(expectedCount).as("emd_cd=%s 가 직접 집계 결과에 있어야 함", row.getEmdCd())
                    .isNotNull();
            assertThat(row.getCvlcptCnt()).as("emd_cd=%s 의 cvlcptCnt 일치", row.getEmdCd())
                    .isEqualTo(expectedCount);
        }
    }

    @Test
    @DisplayName("§7 selectEmdCompare — count DESC, emd_cd ASC 결정적 정렬")
    void selectEmdCompare_sorted_count_desc_emd_asc() {
        List<EmdCompareRow> rows = complaintMapper.selectEmdCompare(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        // 적어도 1행 이상 (현 데이터에 12월 민원 있는 zone 이 있어야 함)
        assertThat(rows).isNotEmpty();

        for (int i = 0; i < rows.size() - 1; i++) {
            EmdCompareRow a = rows.get(i);
            EmdCompareRow b = rows.get(i + 1);
            // cvlcptCnt DESC
            if (a.getCvlcptCnt() != b.getCvlcptCnt()) {
                assertThat(a.getCvlcptCnt()).isGreaterThan(b.getCvlcptCnt());
            } else {
                // 동률이면 emd_cd ASC tie-breaker — 결정적 rank 보장
                assertThat(a.getEmdCd()).isLessThan(b.getEmdCd());
            }
        }
    }

    @Test
    @DisplayName("§7 selectEmdCompare — prev_count 도 매퍼 SQL 의 FILTER 가 직접 집계와 일치")
    void selectEmdCompare_prev_count_matches() {
        List<EmdCompareRow> rows = complaintMapper.selectEmdCompare(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        for (EmdCompareRow row : rows) {
            Long expectedPrevCount = jdbc.queryForObject(
                    // 🔁 2026-10-02: 매퍼 prevCnt 와 같은 **행 단위 count(*)** 기준.
                    "SELECT count(*) FROM parking.tb_cvlcpt "
                  + "WHERE emd_cd = ? "
                  + "  AND ocrn_dt::date BETWEEN ? AND ? "
                  + "  AND ocrn_dt::date < ? "
                  + "  AND cvlcpt_type_cd <> '99'",
                    Long.class,
                    row.getEmdCd(), prevStart, curEnd, curStart);
            assertThat(row.getPrevCnt()).as("emd_cd=%s 의 prevCnt", row.getEmdCd())
                    .isEqualTo(expectedPrevCount);
        }
    }

    // ------------------------------------------------------------------------
    // 🔁 복원된 불변식 — 민원 집계를 행 단위 count(*) 로 되돌리며 되찾은 등식
    // ------------------------------------------------------------------------

    /**
     * 🔴 <b>§3 KPI totalCount == Σ §7 cvlcptCnt.</b> 두 집계({@code selectKpi} ·
     * {@code selectEmdCompare})는 같은 모집단({@code filters})·같은 식이라 전역 조회에서 합이
     * 정확히 같아야 한다.
     *
     * <p>DISTINCT 기준(2026-09-04 ~ 2026-10-02)에서는 같은 본문 민원이 여러 동에 흩어져 동마다
     * 1건씩 세어 <b>Σ 동별 &gt; totalCount</b> 가 됐다(2026-10-02 실측: 정형화된 신고 문구 하나가
     * 46~53개 동에 걸쳐 있었다). 기준이 되돌아가면 이 케이스가 깨진다.
     *
     * <p>🔴 기대값을 매퍼로 만들지 않는다 — 독립 SQL 한 번(구간 전체 count)이 제3의 기준이다.
     */
    @Test
    @DisplayName("🔁 불변식 — §3 KPI totalCount 가 §7 읍면동별 cvlcptCnt 합과 같다")
    void kpiTotalEqualsSumOfEmdCompare() {
        // 전역(시군구 필터 없음) — HAVING 으로 빠지는 동은 현재 구간 0건이라 합에 영향이 없다.
        var kpi = complaintMapper.selectKpi(curStart, curEnd, prevStart, null, null, null);
        List<EmdCompareRow> rows = complaintMapper.selectEmdCompare(curStart, curEnd, prevStart, null, null, null);

        Long independent = jdbc.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt "
              + "WHERE ocrn_dt::date BETWEEN ? AND ? "
              + "  AND cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL",
                Long.class, curStart, curEnd);
        assertThat(independent).as("구간에 민원이 0건이라 불변식을 검증할 수 없다").isNotNull().isPositive();

        long emdSum = rows.stream().mapToLong(EmdCompareRow::getCvlcptCnt).sum();

        assertThat(kpi.totalCount())
                .as("KPI totalCount 가 독립 집계와 다르다")
                .isEqualTo(independent);
        assertThat(emdSum)
                .as("Σ 읍면동 cvlcptCnt != KPI totalCount — 민원 집계가 행 단위가 아닌 기준으로 되돌아갔다")
                .isEqualTo(kpi.totalCount());
    }

    // ------------------------------------------------------------------------
    // §7 selectTopTypeByEmd
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("§7 selectTopTypeByEmd — DISTINCT ON + LEFT JOIN tb_com_cd 동작")
    void selectTopTypeByEmd_distinct_on_with_code_nm() {
        List<EmdCompareRow> emdRows = complaintMapper.selectEmdCompare(curStart, curEnd, prevStart, SGG_JEJU, null, null);
        List<EmdTopTypeRow> topRows = complaintMapper.selectTopTypeByEmd(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        // selectTopTypeByEmd 는 HAVING 없이 모든 emd_cd 의 최다 유형 반환 (12월 민원 있는 zone 만).
        // emdCompare 와 같은 zone 집합을 가져야 함 (HAVING 의 count>0 이 동일 조건).
        assertThat(topRows).hasSize(emdRows.size());

        // 매 행의 typeCount 도 직접 집계로 검증
        //
        // 🔴 대조 집계는 매퍼와 **같은 베이스**여야 한다.
        //    2026-09-04 ~ 09-21 에는 DISTINCT 기준이었고(대조 SQL 만 옛 기준으로 남아 매퍼 10 ↔ 기대 19
        //    로 어긋난 전례가 있다), 🔁 2026-10-02 **행 단위 count(*)** 로 되돌아갔다
        //    (근거: mapper/complaint/ComplaintMapper.xml 상단 「민원 집계 기준」 주석).
        for (EmdTopTypeRow top : topRows) {
            Long expectedTypeCount = jdbc.queryForObject(
                    "SELECT count(*) FROM parking.tb_cvlcpt "
                  + "WHERE emd_cd = ? "
                  + "  AND cvlcpt_type_cd = ? "
                  + "  AND ocrn_dt::date BETWEEN ? AND ? "
                  + "  AND cvlcpt_type_cd <> '99'",
                    Long.class,
                    top.emdCd(), top.typeCd(), curStart, curEnd);
            assertThat(top.typeCount()).as("emd=%s typeCd=%s typeCount", top.emdCd(), top.typeCd())
                    .isEqualTo(expectedTypeCount);
        }
    }

    @Test
    @DisplayName("§7 selectTopTypeByEmd — cd_nm LEFT JOIN 결과 (코드 없으면 null)")
    void selectTopTypeByEmd_cd_nm_left_join() {
        List<EmdTopTypeRow> topRows = complaintMapper.selectTopTypeByEmd(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        for (EmdTopTypeRow top : topRows) {
            // TB_COM_CD 에 정의된 코드면 cd_nm 이 매핑됨. 정의 안 된 코드면 null (현 데이터엔 없음).
            String expectedNm = jdbc.query(
                    "SELECT cd_nm FROM parking.tb_com_cd "
                  + "WHERE group_cd = 'CVLCPT_TYPE_CD' AND cd_vl = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    top.typeCd());
            assertThat(top.typeNm()).as("typeCd=%s 의 LEFT JOIN 결과", top.typeCd())
                    .isEqualTo(expectedNm);
        }
    }

    // ------------------------------------------------------------------------
    // §8 selectNegEmdCompare
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("§8 selectNegEmdCompare — 매퍼 결과와 JdbcTemplate 직접 집계가 일치")
    void selectNegEmdCompare_matches_jdbc_count() {
        List<NegEmdCompareRow> rows = complaintMapper.selectNegEmdCompare(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        org.springframework.jdbc.core.RowMapper<Map.Entry<String, Long>> negAggMapper =
                (rs, i) -> Map.entry(rs.getString("emd_cd"), rs.getLong("count"));
        Object[] negAggArgs = new Object[] {
                curStart, curEnd,
                prevStart, curEnd, curStart,
                prevStart, curEnd,
                SGG_JEJU,
                curStart, curEnd
        };
        Map<String, Long> expectedByEmd = jdbc.query(
                "SELECT c.emd_cd, "
              + "       count(*) FILTER (WHERE c.aprs_cd = 'NEGATIVE' "
              + "                        AND c.ocrn_dt::date BETWEEN ? AND ?) AS count, "
              + "       count(*) FILTER (WHERE c.aprs_cd = 'NEGATIVE' "
              + "                        AND c.ocrn_dt::date BETWEEN ? AND ? "
              + "                        AND c.ocrn_dt::date < ?) AS prev_count "
              + "FROM parking.tb_cvlcpt c "
              + "WHERE c.ocrn_dt::date BETWEEN ? AND ? "
              + "  AND c.cvlcpt_type_cd <> '99' "
              + "  AND c.emd_cd IS NOT NULL "
              + "  AND LEFT(c.emd_cd, 5) = ? "
              + "GROUP BY c.emd_cd "
              + "HAVING count(*) FILTER (WHERE c.aprs_cd = 'NEGATIVE' "
              + "                       AND c.ocrn_dt::date BETWEEN ? AND ?) > 0",
                negAggMapper, negAggArgs
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(rows).hasSize(expectedByEmd.size());
        for (NegEmdCompareRow row : rows) {
            Long expected = expectedByEmd.get(row.getEmdCd());
            assertThat(expected).as("emd_cd=%s 가 직접 집계 결과에 있어야 함", row.getEmdCd()).isNotNull();
            assertThat(row.getNegativeCount()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("§8 selectNegEmdCompare — NULLIF ratio 가 zone 전체 0건일 때 NULL 반환")
    void selectNegEmdCompare_ratio_null_when_zone_zero() {
        List<NegEmdCompareRow> rows = complaintMapper.selectNegEmdCompare(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        for (NegEmdCompareRow row : rows) {
            // ratio = neg_count / total_count. total_count=0 이면 SQL NULLIF → null → 서비스 0.0 치환.
            // 매퍼 SELECT 가 zone 자체를 HAVING 으로 neg_count>0 으로 거르므로 total_count 도 1 이상이어야 정상.
            // 만약 total_count=0 인데 neg_count>0 인 비정상 케이스가 있으면 ratio 가 null 로 옴.
            // 현 데이터엔 그런 케이스 없음 — 모든 행 ratio 가 non-null 임을 확인.
            assertThat(row.getNegativeRatio()).as("emd=%s 의 negativeRatio (현 데이터엔 total>0 정상 케이스만)",
                    row.getEmdCd()).isNotNull();
        }
    }

    @Test
    @DisplayName("§8 selectNegEmdCompare — ratio 가 직접 SQL 계산값과 일치 (소수 5째 자리 반올림)")
    void selectNegEmdCompare_ratio_matches_sql() {
        List<NegEmdCompareRow> rows = complaintMapper.selectNegEmdCompare(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        for (NegEmdCompareRow row : rows) {
            Double expectedRatio = jdbc.queryForObject(
                    "SELECT (count(*) FILTER (WHERE c.aprs_cd = 'NEGATIVE' "
                  + "                          AND c.ocrn_dt::date BETWEEN ? AND ?))::numeric "
                  + "       / NULLIF(count(*) FILTER (WHERE c.aprs_cd IS NOT NULL "
                  + "                                AND c.ocrn_dt::date BETWEEN ? AND ?), 0) "
                  + "FROM parking.tb_cvlcpt c "
                  + "WHERE c.emd_cd = ? "
                  + "  AND c.cvlcpt_type_cd <> '99'",
                    Double.class,
                    curStart, curEnd, curStart, curEnd, row.getEmdCd());
            // double 비교는 부동소수 — 5째 자리 반올림 비교
            assertThat(row.getNegativeRatio()).as("emd=%s 의 negativeRatio", row.getEmdCd())
                    .isCloseTo(expectedRatio, org.assertj.core.data.Offset.offset(0.00001));
        }
    }

    @Test
    @DisplayName("§8 selectNegEmdCompare — HAVING 으로 부정 0건 zone 제외")
    void selectNegEmdCompare_having_excludes_zero_negative() {
        // 시군구 = 서귀포시로 좁혀서 neg 가 거의 없는 zone 이 많음
        List<NegEmdCompareRow> rows = complaintMapper.selectNegEmdCompare(curStart, curEnd, prevStart, SGG_SEOG, null, null);

        for (NegEmdCompareRow row : rows) {
            assertThat(row.getNegativeCount()).as("HAVING 통과한 행은 negativeCount > 0").isPositive();
        }
    }

    // ------------------------------------------------------------------------
    // §3 selectEnforcementCount / selectShortageCount (안 B 원천 기반)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("§3 selectEnforcementCount — 단속 동 모집단의 상위 10%(심각 등급 동 수)와 일치")
    void selectEnforcementCount_matches_direct_aggregate() {
        // 🔴 2026-09-30: 매퍼가 모집단이 아니라 FLOOR(모집단 × 0.1) 을 준다.
        //    기대 SQL 도 같은 FLOOR 를 씌운다 — 안 씌우면 43 vs 4 로 깨진다.
        long mapper = complaintMapper.selectEnforcementCount(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        Long expected = jdbc.queryForObject(
                "SELECT FLOOR(COUNT(DISTINCT c.emd_cd) * 0.1) FROM parking.tb_crdn_dsctn c "
              + "WHERE c.crdn_dt::date BETWEEN ? AND ? "
              + "  AND c.emd_cd IS NOT NULL "
              + "  AND LEFT(c.emd_cd, 5) = ?",
                Long.class,
                curStart, curEnd, SGG_JEJU);

        assertThat(mapper).isEqualTo(expected);
    }

    @Test
    @DisplayName("§3 selectEnforcementCount — 모집단의 10%(심각 등급 규칙)를 실제로 좁힌다")
    void selectEnforcementCount_narrows_population_to_ten_percent() {
        // 🔴 회귀 가드 — FLOOR(× 0.1) 이 빠지면(= 종전 정의로 되돌아가면) 이 단언이 깨진다.
        //    위 테스트는 기대 SQL 을 함께 고치면 통과해 버리므로, "모집단과 다르다" 를 따로 못박는다.
        long mapper = complaintMapper.selectEnforcementCount(curStart, curEnd, prevStart, SGG_JEJU, null, null);

        Long population = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT c.emd_cd) FROM parking.tb_crdn_dsctn c "
              + "WHERE c.crdn_dt::date BETWEEN ? AND ? "
              + "  AND c.emd_cd IS NOT NULL "
              + "  AND LEFT(c.emd_cd, 5) = ?",
                Long.class,
                curStart, curEnd, SGG_JEJU);

        assertThat(population).as("모집단이 0 이면 10% 판정 자체가 무의미 — 시드 확인 필요").isNotNull();
        assertThat(mapper)
                .as("KPI 는 모집단 %d 의 상위 10%%(FLOOR)이어야 한다", population)
                .isEqualTo((long) Math.floor(population * 0.1));
    }

    @Test
    @DisplayName("§3 selectShortageCount — 주차민원이 있는 zone 모집단의 상위 10% 와 일치")
    void selectShortageCount_matches_direct_aggregate() {
        // 2026-09-04: 본 매퍼는 parking.fn_shortage_count 호출. prevStart 없음 — zone 단위 점수.
        // 2026-09-30: 함수 결과는 **모집단**이고 매퍼는 FLOOR(× 0.1) 을 준다.
        // 🔴 (현행) — 매퍼가 더 이상 fn_shortage_count(Jenks '심각' 수)를 부르지 않는다.
        //    세는 대상이 "등급 필터 없는 fn_jenks_shortage 중 out_cvlcpt_sum > 0" 으로 바뀌었다.
        //    ⚠️ 종전 기대 SQL 은 **지금 우연히 통과할 수 있다**(구·신 집계가 둘 다 0 인 구간이 있다) —
        //    그래서 기대값을 현행 모집단으로 갈아 둔다. 함수 인자·WHERE 는 매퍼와 글자 그대로 같다.
        long mapper = complaintMapper.selectShortageCount(curStart, curEnd, SGG_JEJU, null, null);

        Long expected = jdbc.queryForObject(
                "SELECT FLOOR(count(*) * 0.1)"
              + "  FROM parking.fn_jenks_shortage(?, ?, ?, NULL, NULL, NULL::text) s"
              + " WHERE s.out_cvlcpt_sum > 0",
                Long.class,
                curStart, curEnd, SGG_JEJU);

        assertThat(mapper).isEqualTo(expected);
    }

    @Test
    @DisplayName("§3 selectShortageCount — 모집단 전체가 아니라 그 10% 다")
    void selectShortageCount_narrows_population_to_ten_percent() {
        // 🔴 회귀 가드 — 위 테스트와 같은 이유로 "모집단과 다르다" 를 따로 못박는다.
        // 🔴 모집단도 Jenks '심각' 이 아니라 등급 필터 없는 전 zone(민원 > 0)이다.
        long mapper = complaintMapper.selectShortageCount(curStart, curEnd, SGG_JEJU, null, null);

        Long population = jdbc.queryForObject(
                "SELECT count(*)"
              + "  FROM parking.fn_jenks_shortage(?, ?, ?, NULL, NULL, NULL::text) s"
              + " WHERE s.out_cvlcpt_sum > 0",
                Long.class,
                curStart, curEnd, SGG_JEJU);

        assertThat(population).as("모집단이 null 이면 함수 계약이 바뀐 것").isNotNull();
        // 🔴 FLOOR 는 SQL 에 맡긴다 — double 의 0.1 은 정확히 1/10 이 아니라 경계에서 갈릴 수 있다.
        Long expected = jdbc.queryForObject("SELECT FLOOR(?::numeric * 0.1)", Long.class, population);
        assertThat(mapper)
                .as("KPI 는 모집단 %d 의 상위 10%%(FLOOR)이어야 한다", population)
                .isEqualTo(expected);
    }
}
