package jeju.datahub.batch.risknoti;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Reader 계약 검증 (TSK-4069 Step 4) — <b>실 DB 를 읽는다</b>.
 *
 * <p>지키는 것은 넷이다.
 * <ul>
 *   <li>읍면동 × 판정일 집계가 <b>독립 오라클과 같은 값</b>을 낸다</li>
 *   <li>🔴 <b>2페이지 이상</b>에서도 죽지 않는다 — 파생 테이블로 감싸지 않으면 Spring Batch 가
 *       덧붙이는 {@code AND ((emd_cd > ?))} 가 {@code column reference "emd_cd" is ambiguous} 로
 *       터진다. 페이지 크기를 일부러 {@value #PAGED_PAGE_SIZE} 로 줄여 여러 페이지를 돌게 만든다</li>
 *   <li>⚠️ 전일 0건 읍면동이 <b>살아 있다</b> — {@code INNER JOIN} 으로 바꾸면 통째로 사라진다(D2)</li>
 *   <li>🔴 {@code NOT EXISTS} 가 없다 — 재판정이 정상 경로다(D6)</li>
 * </ul>
 *
 * <h2>🔴 판정일과 기대값을 소스에 박지 않는다 (2026-09-01)</h2>
 * 종전에는 {@code TARGET_DATE = "2025-12-31"} 과 그날의 실측치(34개 읍면동 / 168건 / 전일 0건 11개)를
 * 상수로 두었다. <b>시드가 날짜만 옮겨 재적재되자 세 테스트가 한꺼번에 깨졌다</b> — 로컬 시드가
 * 2026-08~09 구간으로 이동해 2025-12-31 에는 한 행도 없었고, 실패 메시지("expected 34 but was 0")는
 * Reader 가 아니라 <b>데이터가 옮겨졌다</b>는 뜻이었다. 그런 실패는 진짜 회귀를 가린다.
 *
 * <p>그래서 이 테스트는 <b>DB 를 보고 판정일을 고르고</b>(읍면동이 가장 많은 날), 기대값도
 * <b>같은 DB 에서 독립적으로 만든다.</b> "무엇을 세는가"는 데이터가 정하고, 이 테스트가 지키는 것은
 * <b>Reader 의 SQL 이 그 답과 일치하는가</b>다.
 *
 * <h2>오라클이 자기잠식하지 않는 이유</h2>
 * 기대값을 Reader 와 같은 SQL 로 만들면 무엇도 증명하지 못한다. 그래서 오라클은
 * <b>원시 행을 그대로 읽어 자바에서 센다</b>({@link #countByEmd}) — Reader 가 쓰는 파생 테이블·
 * {@code LEFT JOIN}·{@code COALESCE}·keyset 페이징을 하나도 거치지 않는다. 두 경로가 같은 답을
 * 내야 한다는 것이 검증의 내용이다.
 *
 * <p>데이터가 검증을 공허하게 만드는 경우(민원 0건, 읍면동이 너무 적어 페이징이 안 됨, 전일 0건
 * 읍면동이 없음)는 <b>통과가 아니라 skip</b> 으로 드러낸다.
 *
 * <p><b>DB 를 바꾸지 않는다</b>(순수 조회)라 {@code @AfterEach} 정리가 필요 없다.
 *
 * <p>⚠️ Reader 를 빈으로 주입받지 않고 <b>직접 생성</b>한다 — {@code @StepScope} 라 Step 실행
 * 밖에서는 프록시가 풀리지 않는다. 여기서 검증하려는 것은 Step 조립(Step 7)이 아니라 SQL 이다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 테스트 컨텍스트에 02:00 크론이 등록될 필요는 없다.
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class RiskAlertTargetItemReaderTest {

    /** 페이징 경로를 강제하는 작은 페이지 크기. 읍면동이 이 값보다 많아야 2페이지 이상이 된다. */
    private static final int PAGED_PAGE_SIZE = 5;

    /** 한 번에 다 읽는 크기 — 실제 청크 기본값과 같다. */
    private static final int SINGLE_PAGE_SIZE = 200;

    /**
     * 판정일을 데이터에서 고른다 — <b>읍면동이 가장 많은 날</b>.
     *
     * <p>그 날이라야 페이징(여러 페이지)·전일 비교·LEFT JOIN 이 한 번에 검증된다. 동률이면 최신일을
     * 골라 실행마다 흔들리지 않게 한다.
     */
    private static final String BUSIEST_DAY_SQL = """
            SELECT ocrn_dt::date AS d
              FROM parking.tb_cvlcpt
             WHERE emd_cd IS NOT NULL
             GROUP BY 1
             ORDER BY count(DISTINCT emd_cd) DESC, d DESC
             LIMIT 1
            """;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    /** 데이터에서 고른 판정일. */
    private LocalDate targetDate;

    /** Reader 와 독립적으로 만든 기대 결과(emd_cd 오름차순). */
    private List<RiskAlertTargetRow> expected;

    @BeforeEach
    void resolveTargetDateFromData() {
        LocalDate busiest = jdbc.query(BUSIEST_DAY_SQL,
                rs -> rs.next() ? rs.getDate("d").toLocalDate() : null);

        Assumptions.assumeTrue(busiest != null,
                "parking.tb_cvlcpt 에 emd_cd 가 채워진 행이 없어 Reader 를 검증할 수 없습니다 — "
                        + "complaintFetchJob 을 한 번 돌리거나 시드를 적재한 뒤 다시 실행할 것");

        targetDate = busiest;
        expected = buildExpectedRows(busiest);
    }

    @Test
    @DisplayName("판정일 하루치를 읍면동으로 묶어 (당일, 전일) 건수를 낸다 — 독립 집계와 일치")
    void aggregatesByEmdForTargetDate() throws Exception {
        List<RiskAlertTargetRow> rows = readAll(targetDate, SINGLE_PAGE_SIZE);

        // 🔴 전량 비교다. 건수·읍면동 집합·emd_nm 이 하나라도 어긋나면 여기서 잡힌다.
        assertThat(rows).containsExactlyElementsOf(expected);

        assertThat(rows).extracting(RiskAlertTargetRow::targetCnt)
                .allSatisfy(cnt -> assertThat(cnt).isPositive());   // D4 — 당일 0건은 애초에 안 읽는다

        // emd_nm 은 tb_admdst 조인 결과다 — 비어 있으면 알림 문구(D8)가 빈 칸으로 나간다.
        assertThat(rows).extracting(RiskAlertTargetRow::emdNm)
                .allSatisfy(nm -> assertThat(nm).isNotBlank());

        // 🔴 두 컬럼(당일/전일)이 뒤바뀌는 사고는 값이 서로 다른 행이 있어야만 검출된다.
        //    전 행이 우연히 같은 값이면 위 비교가 통과해도 아무것도 증명하지 못한다.
        assertThat(rows)
                .as("당일 건수와 전일 건수가 다른 행이 하나도 없어 두 컬럼이 뒤바뀌어도 드러나지 않는다")
                .anyMatch(row -> row.targetCnt() != row.prevCnt());
    }

    @Test
    @DisplayName("🔴 페이지가 여러 장이어도 죽지 않는다 — 파생 테이블이 아니면 emd_cd 가 ambiguous 로 터진다")
    void survivesKeysetPagingAcrossManyPages() throws Exception {
        Assumptions.assumeTrue(expected.size() > PAGED_PAGE_SIZE,
                "읍면동이 %d개뿐이라 페이지가 한 장이다 — 페이징 경로를 검증할 수 없습니다(판정일 %s)"
                        .formatted(expected.size(), targetDate));

        List<RiskAlertTargetRow> paged = readAll(targetDate, PAGED_PAGE_SIZE);
        List<RiskAlertTargetRow> single = readAll(targetDate, SINGLE_PAGE_SIZE);

        // 페이지 경계에서 행이 새거나 겹치지 않는다.
        assertThat(paged).containsExactlyElementsOf(single);
        // keyset 정렬 키는 emd_cd 오름차순 — 이 순서가 깨지면 다음 페이지 시작값이 어긋난다.
        assertThat(paged).extracting(RiskAlertTargetRow::emdCd).isSorted();
        assertThat(paged).extracting(RiskAlertTargetRow::emdCd).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("⚠️ 전일 0건 읍면동이 남아 있다 — INNER JOIN 이면 신규 급증(0→N)이 통째로 사라진다")
    void keepsEmdWithoutPreviousDayComplaints() throws Exception {
        long expectedPrevZero = expected.stream().filter(row -> row.prevCnt() == 0L).count();
        Assumptions.assumeTrue(expectedPrevZero > 0,
                "판정일 %s 에는 전일 0건인 읍면동이 없어 LEFT JOIN 여부를 구분할 수 없습니다"
                        .formatted(targetDate));

        List<RiskAlertTargetRow> rows = readAll(targetDate, SINGLE_PAGE_SIZE);

        // INNER JOIN 이면 이 행들이 통째로 빠져 개수가 줄어든다.
        assertThat(rows).filteredOn(row -> row.prevCnt() == 0L).hasSize((int) expectedPrevZero);
        // COALESCE 가 빠지면 NULL 이 되어 RowMapper 의 getLong 이 0 을 주지만, 위 개수 비교가
        // 먼저 어긋난다(행 자체가 사라지므로).
        assertThat(rows).extracting(RiskAlertTargetRow::prevCnt)
                .allSatisfy(cnt -> assertThat(cnt).isNotNegative());
    }

    @Test
    @DisplayName("판정일이 다르면 결과도 다르다 — :targetDate 가 실제로 SQL 에 바인딩된다")
    void bindsTargetDateParameter() throws Exception {
        List<RiskAlertTargetRow> onTargetDate = readAll(targetDate, SINGLE_PAGE_SIZE);
        // 데이터 구간 밖 — 당일 민원이 한 건도 없어 한 행도 나오지 않아야 한다.
        List<RiskAlertTargetRow> outOfRange = readAll(dayAfterLastData(), SINGLE_PAGE_SIZE);

        assertThat(onTargetDate).isNotEmpty();
        assertThat(outOfRange).isEmpty();
    }

    @Test
    @DisplayName("🔴 NOT EXISTS 가 없다 — 재판정이 정상 경로다(D6). 멱등성은 Writer 의 upsert 가 낸다")
    void doesNotFilterAlreadyJudgedRows() {
        String from = RiskAlertTargetItemReader.FROM_CLAUSE.toUpperCase(java.util.Locale.ROOT);

        assertThat(from).doesNotContain("NOT EXISTS");
        // 이력 테이블을 아예 보지 않는다 — 보는 순간 재판정 경로가 막힌다.
        assertThat(from).doesNotContain("TB_RISK_STP_NOTI_HSTRY");
        // 정렬 키에 한정자가 붙으면 2페이지에서 "column name t.emd_cd was not found" 로 죽는다.
        assertThat(RiskAlertTargetItemReader.SORT_KEY).isEqualTo("emd_cd");
    }

    @Test
    @DisplayName("targetDate 가 없거나 비어 있으면 '어제' 로 떨어진다 — 파싱은 Reader 가 한다")
    void parsesOptionalTargetDate() {
        // 순수 문자열 파싱이라 DB 와 무관하다 — 여기 날짜는 임의값이어도 된다.
        assertThat(RiskAlertTargetItemReader.parseTargetDate(null)).isNull();
        assertThat(RiskAlertTargetItemReader.parseTargetDate("  ")).isNull();
        assertThat(RiskAlertTargetItemReader.parseTargetDate(" 2025-12-31 "))
                .isEqualTo(LocalDate.of(2025, 12, 31));
    }

    // --- 오라클 -----------------------------------------------------------------------

    /**
     * 기대 결과를 <b>Reader 와 다른 경로로</b> 만든다 — 원시 행을 읽어 자바에서 센다.
     *
     * <p>Reader 는 파생 테이블 + {@code GROUP BY} + {@code LEFT JOIN} + {@code COALESCE} 로 같은
     * 답을 내야 한다. 여기서 같은 SQL 을 쓰면 검증이 순환한다.
     */
    private List<RiskAlertTargetRow> buildExpectedRows(LocalDate day) {
        Map<String, Long> targetCounts = countByEmd(day);
        Map<String, Long> prevCounts = countByEmd(day.minusDays(1));
        Map<String, String> emdNames = emdNames();

        List<RiskAlertTargetRow> rows = new ArrayList<>(targetCounts.size());
        targetCounts.forEach((emdCd, cnt) -> {
            String emdNm = emdNames.get(emdCd);
            if (emdNm != null) {   // tb_admdst 와의 JOIN 에 해당 — FK 가 있어 실제로는 전부 매칭된다
                rows.add(new RiskAlertTargetRow(emdCd, emdNm, cnt, prevCounts.getOrDefault(emdCd, 0L)));
            }
        });
        rows.sort(Comparator.comparing(RiskAlertTargetRow::emdCd));   // Reader 의 ORDER BY emd_cd
        return List.copyOf(rows);
    }

    /** 하루치 원시 행을 읽어 읍면동별로 <b>자바에서</b> 센다. 반개구간은 Reader 와 같다. */
    private Map<String, Long> countByEmd(LocalDate day) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("""
                SELECT emd_cd
                  FROM parking.tb_cvlcpt
                 WHERE emd_cd IS NOT NULL
                   AND ocrn_dt >= ?
                   AND ocrn_dt <  ?
                """,
                rs -> {
                    counts.merge(rs.getString("emd_cd"), 1L, Long::sum);
                },
                Date.valueOf(day), Date.valueOf(day.plusDays(1)));
        return counts;
    }

    /** {@code emd_cd → emd_nm}. Reader 의 {@code JOIN tb_admdst} 에 대응한다. */
    private Map<String, String> emdNames() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT emd_cd, emd_nm FROM parking.tb_admdst",
                rs -> {
                    names.put(rs.getString("emd_cd"), rs.getString("emd_nm"));
                });
        return names;
    }

    /** 데이터가 확실히 없는 날 — 마지막 민원 다음 날. "구간 밖" 을 날짜 상수로 박지 않기 위한 것이다. */
    private LocalDate dayAfterLastData() {
        LocalDate last = jdbc.query(
                "SELECT max(ocrn_dt)::date AS d FROM parking.tb_cvlcpt WHERE emd_cd IS NOT NULL",
                rs -> rs.next() && rs.getDate("d") != null ? rs.getDate("d").toLocalDate() : null);
        return last == null ? targetDate.plusYears(1) : last.plusDays(1);
    }

    /** Reader 를 열어 끝까지 읽고 닫는다. {@code pageSize} 를 줄이면 페이징 경로를 강제할 수 있다. */
    private List<RiskAlertTargetRow> readAll(LocalDate targetDate, int pageSize) throws Exception {
        RiskAlertTargetItemReader reader =
                new RiskAlertTargetItemReader(dataSource, pageSize, targetDate.toString());
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());
        try {
            List<RiskAlertTargetRow> rows = new ArrayList<>();
            RiskAlertTargetRow row;
            while ((row = reader.read()) != null) {
                rows.add(row);
            }
            return rows;
        } finally {
            reader.close();
        }
    }
}
