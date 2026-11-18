package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareBaseVO;
import jeju.datahub.analysis.service.impl.ZoneRcmdtnShareMapper;

/**
 * 구역추천(공유주차제 적용) 읍면동 판정 매퍼 테스트 (TSK-4208 Step 2 검증).
 *
 * <p>1차 목적은 {@code ZoneRcmdtnShareMapper.xml} 이 <b>MyBatis 로 실제 로드·바인딩·매핑되는지</b>다 —
 * {@code psql} 로 SQL 을 직접 돌리는 검증은 {@code @Param} 이름·{@link LocalDateTime} 바인딩·
 * {@code map-underscore-to-camel-case} 매핑(특히 {@code vaclot_excl_pklt_cpcty} 같은 긴 이름)을
 * 타지 않는다.
 *
 * <p>로컬 PostgreSQL 을 그대로 쓴다. 전부 {@code SELECT} 이고 {@code @Transactional} 로 감싸 DB 를
 * 바꾸지 않는다.
 *
 * <h2>🔴 조회 날짜·건수를 코드에 박지 않는다</h2>
 * 같은 패키지의 {@code ZoneRcmdtnMapperTest} 는 OGNL 분기 검증 때문에 예외로 고정 구간·기대값을
 * 박았지만, 이 매퍼에는 분기가 없어 그럴 이유가 없다. 시드가 날짜만 옮겨 재적재되면 박아 둔 값이
 * 한꺼번에 깨진다(2026-10-13 에 실제로 일어났다). 그래서 창은 {@code selectDataRange()} 로 읽은 보유
 * 구간에서 만들고, 단언은 <b>불변식</b>으로만 쓴다.
 */
@SpringBootTest
@Transactional
@DisplayName("TSK-4208 Step 2 — 구역추천 공유주차제 읍면동 판정 매퍼")
class ZoneRcmdtnShareMapperTest {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 매퍼 XML 의 민원 조건과 <b>같은 조건</b>의 원천 건수 — 합계 대조의 기준이다 */
    private static final String SRC_COUNT_SQL =
            "SELECT count(*) FROM parking.tb_cvlcpt"
            + " WHERE cvlcpt_type_cd <> '99' AND ocrn_dt >= ? AND ocrn_dt < ? AND emd_cd IS NOT NULL";

    @Autowired
    private ZoneRcmdtnShareMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 보유 구간. 대상 민원이 아예 없으면(빈 DB) 창을 만들 수 없어 건너뛴다 */
    private DataRangeVO range() {
        DataRangeVO range = mapper.selectDataRange();
        assertThat(range).as("집계 쿼리라 대상이 0건이어도 행은 1건 나온다").isNotNull();
        assumeTrue(range.getDataBgngYmd() != null && range.getDataEndYmd() != null,
                "대상 민원이 없다 — 보유 구간으로 창을 만들 수 없다");
        return range;
    }

    /** 보유 구간 시작(<b>포함</b>) */
    private static LocalDateTime from(DataRangeVO range) {
        return LocalDate.parse(range.getDataBgngYmd(), YMD).atStartOfDay();
    }

    /** 보유 구간 종료(<b>미포함</b>) = 종료일 + 1일. 반열림이라 종료일 당일 민원이 창 안에 든다 */
    private static LocalDateTime toExcl(DataRangeVO range) {
        return LocalDate.parse(range.getDataEndYmd(), YMD).plusDays(1).atStartOfDay();
    }

    private static BigDecimal sumCvlcptCnt(List<ZoneRcmdtnShareBaseVO> rows) {
        return rows.stream().map(ZoneRcmdtnShareBaseVO::getCvlcptCnt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long srcCount(LocalDateTime from, LocalDateTime toExcl) {
        Long n = jdbc.queryForObject(SRC_COUNT_SQL, Long.class,
                Timestamp.valueOf(from), Timestamp.valueOf(toExcl));
        return n == null ? 0L : n;
    }

    @Test
    @DisplayName("1. 자료 구간 — YYYYMMDD 8자리이고 시작 ≤ 종료")
    void dataRangeIsWellFormed() {
        DataRangeVO range = range();

        assertThat(range.getDataBgngYmd()).matches("\\d{8}");
        assertThat(range.getDataEndYmd()).matches("\\d{8}");
        assertThat(range.getDataBgngYmd()).isLessThanOrEqualTo(range.getDataEndYmd());
    }

    @Test
    @DisplayName("2. 읍면동 전부 — 행 수 = tb_admdst 행 수 · emdCd 중복 없음 · 숫자 칸에 null 없음")
    void returnsEveryAdmdstRowOnce() {
        DataRangeVO range = range();
        List<ZoneRcmdtnShareBaseVO> rows = mapper.selectShareBase(from(range), toExcl(range), null, null, null);

        Integer admdstRows = jdbc.queryForObject("SELECT count(*) FROM parking.tb_admdst", Integer.class);
        assertThat(rows).as("LEFT JOIN 이라 원천 0건인 동도 남는다").hasSize(admdstRows);
        assertThat(rows).extracting(ZoneRcmdtnShareBaseVO::getEmdCd).doesNotHaveDuplicates();
        assertThat(rows).extracting(ZoneRcmdtnShareBaseVO::getEmdCd)
                .as("ORDER BY a.emd_cd").isSorted();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getEmdCd()).isNotBlank();
            assertThat(r.getSggNm()).isNotBlank();
            assertThat(r.getEmdNm()).isNotBlank();
            // COALESCE 로 0 을 채우므로 null 이 없다 — 서비스가 null 체크 없이 계산할 수 있는 근거.
            // 🔴 컬럼 → 필드 매핑이 한 곳이라도 어긋나면 여기서 null 로 드러난다.
            assertThat(r.getCvlcptCnt()).isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(r.getTopPntCvlcptCnt()).isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(r.getPkltCnt()).isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(r.getPkltCpcty()).isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(r.getVaclotExclPkltCpcty()).isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("3. 행 불변식 — 최다 지점 ≤ 민원 건수 · 공한지 제외 면수 ≤ 전체 면수")
    void rowInvariantsHold() {
        DataRangeVO range = range();
        List<ZoneRcmdtnShareBaseVO> rows = mapper.selectShareBase(from(range), toExcl(range), null, null, null);

        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getTopPntCvlcptCnt())
                    .as("%s — 한 좌표의 건수가 동 전체 건수를 넘을 수 없다", r.getEmdNm())
                    .isLessThanOrEqualTo(r.getCvlcptCnt());
            assertThat(r.getVaclotExclPkltCpcty())
                    .as("%s — 공한지를 뺀 면수가 전체 면수를 넘을 수 없다", r.getEmdNm())
                    .isLessThanOrEqualTo(r.getPkltCpcty());
        });
    }

    @Test
    @DisplayName("4. 🔴 Σ cvlcptCnt = 같은 조건의 원천 건수 (보유 구간 전체)")
    void cvlcptCntSumMatchesSource() {
        DataRangeVO range = range();
        LocalDateTime from = from(range);
        LocalDateTime toExcl = toExcl(range);

        long src = srcCount(from, toExcl);
        assertThat(src).as("보유 구간 전체인데 0건이면 창을 잘못 만든 것이다").isPositive();
        assertThat(sumCvlcptCnt(mapper.selectShareBase(from, toExcl, null, null, null)))
                .as("읍면동별 합이 원천과 다르면 JOIN 이 행을 불리거나 조건이 갈린 것이다")
                .isEqualByComparingTo(BigDecimal.valueOf(src));
    }

    /**
     * 🔴 <b>기간 바인딩이 실제로 먹는가</b> — 보유 구간 전체만 넣으면 {@code #{cvFrom}}·{@code #{cvToExcl}}
     * 가 무시돼도 4번이 통과한다. 창을 첫 하루로 줄여 원천과 다시 맞춘다.
     */
    @Test
    @DisplayName("5. 🔴 창을 줄이면 합도 그만큼 준다 — 보유 구간 첫 하루")
    void windowActuallyBinds() {
        DataRangeVO range = range();
        LocalDateTime from = from(range);
        LocalDateTime toExcl = from.plusDays(1);

        assertThat(sumCvlcptCnt(mapper.selectShareBase(from, toExcl, null, null, null)))
                .isEqualByComparingTo(BigDecimal.valueOf(srcCount(from, toExcl)));
    }

    @Test
    @DisplayName("6. 민원이 없는 창 — 행은 그대로이고 민원 칸만 0 (면수는 기간과 무관)")
    void emptyWindowKeepsRowsAndSupply() {
        DataRangeVO range = range();
        List<ZoneRcmdtnShareBaseVO> full = mapper.selectShareBase(from(range), toExcl(range), null, null, null);
        // 보유 구간 종료일 다음 날부터 하루 — 정의상 대상 민원이 없다
        List<ZoneRcmdtnShareBaseVO> empty =
                mapper.selectShareBase(toExcl(range), toExcl(range).plusDays(1), null, null, null);

        assertThat(empty).hasSameSizeAs(full);
        assertThat(empty).allSatisfy(r -> {
            assertThat(r.getCvlcptCnt()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(r.getTopPntCvlcptCnt()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        for (int i = 0; i < full.size(); i++) {
            assertThat(empty.get(i).getEmdCd()).isEqualTo(full.get(i).getEmdCd());
            assertThat(empty.get(i).getPkltCpcty()).isEqualByComparingTo(full.get(i).getPkltCpcty());
            assertThat(empty.get(i).getVaclotExclPkltCpcty())
                    .isEqualByComparingTo(full.get(i).getVaclotExclPkltCpcty());
        }
    }

    // ── TSK-4283 Step 2 — 추세 블록 건수 3칸 ─────────────────────────────────────────────────

    /** 읍면동별 원천 건수 — 매퍼 XML cvt 의 민원 조건과 <b>같은 조건</b>이다 */
    private static final String SRC_COUNT_BY_EMD_SQL =
            "SELECT emd_cd, count(*) AS n FROM parking.tb_cvlcpt"
            + " WHERE cvlcpt_type_cd <> '99' AND ocrn_dt >= ? AND ocrn_dt < ? AND emd_cd IS NOT NULL"
            + " GROUP BY emd_cd";

    private Map<String, Long> srcCountByEmd(LocalDateTime from, LocalDateTime toExcl) {
        return jdbc.query(SRC_COUNT_BY_EMD_SQL,
                        (rs, i) -> Map.entry(rs.getString("emd_cd"), rs.getLong("n")),
                        Timestamp.valueOf(from), Timestamp.valueOf(toExcl))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * 추세 창 — 계획서 Step 2 표의 식 그대로(요금제 {@code applyTrendWindow} 와 같다).
     * {@code D = DAYS.between(bgng, end) + 1} · {@code half = D / 2}(정수 나눗셈).
     */
    private record TrendWindow(LocalDateTime trendFrom, LocalDateTime cvFrom, LocalDateTime splitMid,
                               LocalDateTime splitMidExcl, LocalDateTime cvToExcl) {
        static TrendWindow of(LocalDate bgng, LocalDate end) {
            long d = ChronoUnit.DAYS.between(bgng, end) + 1;
            long half = d / 2;
            LocalDateTime cvToExcl = end.plusDays(1).atStartOfDay();
            return new TrendWindow(bgng.minusDays(d).atStartOfDay(), bgng.atStartOfDay(),
                    bgng.plusDays(half).atStartOfDay(), cvToExcl.minusDays(half), cvToExcl);
        }
    }

    /**
     * 보유 구간 끝에서 고른 조회 창 2개 — D 짝수·홀수를 모두 탄다(홀수면 가운데 1일을 버린다).
     * D 는 보유 일수의 1/3 이라 이전 블록(D일)까지 보유 구간 안에 든다. 날짜 상수는 박지 않는다.
     */
    private List<TrendWindow> trendWindows(DataRangeVO range) {
        LocalDate first = LocalDate.parse(range.getDataBgngYmd(), YMD);
        LocalDate end = LocalDate.parse(range.getDataEndYmd(), YMD);
        long total = ChronoUnit.DAYS.between(first, end) + 1;
        long d = Math.max(2, total / 3);
        assumeTrue(total >= d * 2 + 1, "보유 구간이 너무 짧아 이전 블록까지 담을 수 없다");
        return List.of(TrendWindow.of(end.minusDays(d - 1), end),      // D
                       TrendWindow.of(end.minusDays(d), end));         // D + 1 (홀짝 반대)
    }

    @Test
    @DisplayName("7. 🔴 추세 3칸 = 같은 조건의 원천 직접 집계 (읍면동별 · D 짝수/홀수)")
    void trendCountsMatchSource() {
        DataRangeVO range = range();
        for (TrendWindow w : trendWindows(range)) {
            List<ZoneRcmdtnShareBaseVO> rows = mapper.selectShareBase(
                    w.cvFrom(), w.cvToExcl(), w.trendFrom(), w.splitMid(), w.splitMidExcl());

            Map<String, Long> bfr = srcCountByEmd(w.trendFrom(), w.cvFrom());
            Map<String, Long> halfBfr = srcCountByEmd(w.cvFrom(), w.splitMid());
            Map<String, Long> halfAftr = srcCountByEmd(w.splitMidExcl(), w.cvToExcl());
            assertThat(bfr.values().stream().mapToLong(Long::longValue).sum())
                    .as("%s — 이전 블록이 보유 구간 안인데 0건이면 창을 잘못 만든 것이다", w).isPositive();

            assertThat(rows).allSatisfy(r -> {
                assertThat(r.getBfrCvlcptCnt()).as("%s bfr", r.getEmdNm())
                        .isEqualByComparingTo(BigDecimal.valueOf(bfr.getOrDefault(r.getEmdCd(), 0L)));
                assertThat(r.getHalfBfrCvlcptCnt()).as("%s halfBfr", r.getEmdNm())
                        .isEqualByComparingTo(BigDecimal.valueOf(halfBfr.getOrDefault(r.getEmdCd(), 0L)));
                assertThat(r.getHalfAftrCvlcptCnt()).as("%s halfAftr", r.getEmdNm())
                        .isEqualByComparingTo(BigDecimal.valueOf(halfAftr.getOrDefault(r.getEmdCd(), 0L)));
            });
        }
    }

    @Test
    @DisplayName("8. 🔴 판정용 cvlcptCnt 는 추세 파라미터 유무와 무관하다 (D5 — 범위를 넓히지 않는다)")
    void cvlcptCntUnaffectedByTrendParams() {
        DataRangeVO range = range();
        for (TrendWindow w : trendWindows(range)) {
            Map<String, ZoneRcmdtnShareBaseVO> without = mapper
                    .selectShareBase(w.cvFrom(), w.cvToExcl(), null, null, null).stream()
                    .collect(Collectors.toMap(ZoneRcmdtnShareBaseVO::getEmdCd, Function.identity()));
            List<ZoneRcmdtnShareBaseVO> with = mapper.selectShareBase(
                    w.cvFrom(), w.cvToExcl(), w.trendFrom(), w.splitMid(), w.splitMidExcl());

            assertThat(with).hasSameSizeAs(without.values());
            assertThat(with).allSatisfy(r -> {
                assertThat(r.getCvlcptCnt()).as("%s cvlcptCnt", r.getEmdNm())
                        .isEqualByComparingTo(without.get(r.getEmdCd()).getCvlcptCnt());
                assertThat(r.getTopPntCvlcptCnt()).as("%s topPntCvlcptCnt", r.getEmdNm())
                        .isEqualByComparingTo(without.get(r.getEmdCd()).getTopPntCvlcptCnt());
            });
            assertThat(sumCvlcptCnt(with)).isEqualByComparingTo(
                    BigDecimal.valueOf(srcCount(w.cvFrom(), w.cvToExcl())));
        }
    }

    @Test
    @DisplayName("9. 추세 파라미터 null(D/2 < 7) — 세 칸 모두 0 (null 이 아니다)")
    void nullTrendParamsYieldZero() {
        DataRangeVO range = range();
        List<ZoneRcmdtnShareBaseVO> rows =
                mapper.selectShareBase(from(range), toExcl(range), null, null, null);

        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getBfrCvlcptCnt()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(r.getHalfBfrCvlcptCnt()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(r.getHalfAftrCvlcptCnt()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
        });
    }
}
