package jeju.datahub.complaint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.complaint.service.DashboardTrendVO;
import jeju.datahub.complaint.service.TrendBucket;
import jeju.datahub.complaint.service.TrendPointVO;
import jeju.datahub.complaint.service.TypeCountVO;

/**
 * 민원 추이 차트 API 통합 테스트.
 *
 * <p><b>무엇을 지키는 테스트인가</b> — {@code GET /api/dashboard/trend} 의 <b>버킷 결정 · 버킷
 * 접힘 · 제로필 · 유형 필터 · 잘못된 입력의 400</b> 을 고정한다. 이 네 가지는 SQL 한 줄만
 * 바뀌어도 차트 모양이 조용히 달라지는 규칙이다.
 *
 * <p>🔴 <b>테스트의 성격이 바뀌었다.</b> 종전에는 시드 실측값
 * ({@code 117}·{@code 936}·{@code 49}건)을 리터럴로 박아 <b>"시드 데이터가 변형되지 않았는가"</b> 를
 * 함께 잡았다. 민원 적재분이 배치 재적재로 날짜째 옮겨가(종전 구간의 행 수가 0 이 되어) 그 방식이
 * 성립하지 않으므로, 이제 기대값을 <b>매퍼·서비스를 거치지 않은 독립 SQL</b>(=
 * {@link JdbcTemplate} 으로 {@code parking.tb_cvlcpt} 를 직접 집계)로 만든다. 따라서 이 테스트가
 * 검증하는 것은 <b>"시드 실측값 고정" 이 아니라 "쿼리 간 정합"</b> 이다 — 버킷 합산·제로필·필터
 * 로직이 같은 구간을 센 생SQL 과 어긋나지 않는지를 본다. 시드 자체의 변형 감지는 이 테스트가
 * 더 이상 지지 않는다.
 *
 * <p>🔴 <b>자기 자신과 비교하지 않는다.</b> 기대값 SQL 은 매퍼 XML 을 호출하지 않고 조회 정책
 * ({@code cvlcpt_type_cd <> '99'} · {@code emd_cd IS NOT NULL})만 독립적으로 다시 적는다.
 * 🔴 <b>데이터가 0건이면 조용히 통과하지 않는다</b> — 각 단언 앞에 모수가 0 이 아님을 먼저 못박는다.
 *
 * <p><b>조회 구간</b> — 🔴 날짜를 박지 않는다. {@link #resolveRange()} 가
 * {@code LEAST(max(ocrn_dt), max(crdn_dt))} 를 끝점으로 잡고({@code ComplaintMapperIntegrationTest}
 * 와 같은 패턴 — 두 baseline 테스트가 같은 창을 보게 한다), 여러 날 케이스는 그 끝점에서
 * {@value #WINDOW_DAYS}일 창을 뒤로 잡는다.
 *
 * <p><b>CI 안전장치</b> — "오늘" 은 서버 실제 날짜({@code Asia/Seoul})라 실행일마다 달라진다.
 * 그래서 <b>{@code unit} 케이스는 구간 해석(시작·종료일·칸 수)만 보고, 값은 명시 구간으로 본다</b>.
 * {@code unit} 기대값은 호출 전후의 {@code LocalDate.now(SEOUL)} 둘 다 허용한다(자정 경계).
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리해 테스트 트랜잭션이 서버 쪽을 감싸지 못한다({@code ReportIntegrationTest} 와 같은
 * 전략). 다만 이 테스트는 <b>조회 전용</b>이라 DB 를 전혀 바꾸지 않는다 — 그래서 시드
 * 복원({@code @AfterEach})도 두지 않는다.
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComplaintTrendIntegrationTest {

    private static final String TREND_URL = "/api/dashboard/trend";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 대시보드 조회 정책 — 매퍼 XML 의 {@code filters} 조각을 독립적으로 다시 적은 것이다. */
    private static final String FILTERS = " AND cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL ";

    /** 여러 날 케이스의 창 길이(일, 양끝 포함). 🔴 날짜가 아니라 <b>길이</b>다. */
    private static final int WINDOW_DAYS = 7;

    /**
     * 조회 가능한 최대 구간(일, 양끝 포함). {@code jeju.datahub.common.DateRanges.MAX_RANGE_DAYS} 와
     * 같은 값이어야 한다 — 테스트가 독립적으로 다시 적는다.
     */
    private static final int MAX_RANGE_DAYS = 366;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    /** 데이터가 있는 구간의 끝점 — DB 에서 읽는다. 단일 날짜 케이스는 이 하루만 본다. */
    private LocalDate dataEnd;

    /** 여러 날 케이스의 시작일 = {@code dataEnd - (WINDOW_DAYS-1)}. */
    private LocalDate rangeStart;

    /** 유형 필터 케이스가 고를 유형 — 그 날 가장 많은 유형을 SQL 로 뽑는다(0건 필터를 피한다). */
    private String topTypeCd;

    /**
     * 🔴 조회 구간을 데이터에서 읽는다 — 날짜를 코드에 박지 않는다.
     * 끝점은 {@code ComplaintMapperIntegrationTest} 와 같은 {@code LEAST(max(...), max(...))} 다.
     */
    @BeforeEach
    void resolveRange() {
        dataEnd = jdbc.queryForObject(
                "SELECT LEAST((SELECT max(ocrn_dt)::date FROM parking.tb_cvlcpt),"
              + "             (SELECT max(crdn_dt)::date FROM parking.tb_crdn_dsctn))",
                (rs, rowNum) -> rs.getObject(1, LocalDate.class));
        assertThat(dataEnd).as("민원·단속 원천이 비어 있다 — 배치 적재분을 먼저 확인하라").isNotNull();
        rangeStart = dataEnd.minusDays(WINDOW_DAYS - 1L);

        // 🔴 끝점 하루가 조회 정책 적용 후 0건이면 아래 단언들이 전부 '0 == 0' 으로 조용히 통과한다.
        Long dayCnt = jdbc.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt WHERE ocrn_dt::date = ?" + FILTERS,
                Long.class, dataEnd);
        assertThat(dayCnt).as("구간 끝점(%s)에 조회 정책을 통과하는 민원이 0건이다", dataEnd).isPositive();

        topTypeCd = jdbc.queryForObject(
                "SELECT cvlcpt_type_cd FROM parking.tb_cvlcpt WHERE ocrn_dt::date = ?" + FILTERS
              + "GROUP BY cvlcpt_type_cd ORDER BY count(*) DESC, cvlcpt_type_cd LIMIT 1",
                String.class, dataEnd);
        assertThat(topTypeCd).as("유형 필터에 쓸 유형 코드를 찾지 못했다").isNotNull();
    }

    // ------------------------------------------------------------------
    // 1. 조회단위 YESTERDAY → HOUR 버킷 24칸
    // ------------------------------------------------------------------

    @Test
    @DisplayName("조회단위 YESTERDAY 는 HOUR 버킷 24칸으로 오고 기간이 어제 하루로 잡힌다")
    void yesterdayReturnsTwentyFourHourBuckets() {
        // 자정 경계 대비 — 호출 전후 날짜를 둘 다 허용한다.
        LocalDate before = LocalDate.now(SEOUL);
        DashboardTrendVO body = getTrend("?unit=YESTERDAY");
        LocalDate after = LocalDate.now(SEOUL);

        assertThat(body.getBucket()).isEqualTo(TrendBucket.HOUR);
        assertThat(body.getPoints()).hasSize(24);
        assertThat(body.getStartDt()).isIn(before.minusDays(1), after.minusDays(1));
        assertThat(body.getEndDt()).isIn(before.minusDays(1), after.minusDays(1));
    }

    // ------------------------------------------------------------------
    // 2. 제로필 — 민원이 없는 시간대도 칸이 남는다
    // ------------------------------------------------------------------

    /**
     * <b>무엇을 검증하나</b> — 종전엔 "그 시드 날짜의 01시 칸은 비어 있다" 를 알고 {@code '01'} 을
     * 박았다. 이제 <b>비어 있는 시간대 자체를 독립 SQL 로 찾아</b>(그 날 집계에 나타나지 않는 시각)
     * 그 칸이 모두 {@code total=0}·빈 breakdown 으로 채워져 오는지를 본다 — 제로필 규칙의 정합이다.
     * 🔴 비어 있는 시간대가 하나도 없으면 아무것도 검증하지 못하므로 그 경우를 먼저 실패로 끊는다.
     */
    @Test
    @DisplayName("민원이 없는 시간대도 total=0, breakdown 빈 리스트로 제로필된다")
    void emptyHourBucketIsZeroFilled() {
        Set<String> hoursWithData = new HashSet<>(jdbc.queryForList(
                "SELECT lpad(extract(hour from ocrn_dt)::text, 2, '0') "
              + "  FROM parking.tb_cvlcpt WHERE ocrn_dt::date = ?" + FILTERS
              + " GROUP BY 1",
                String.class, dataEnd));
        List<String> emptyHours = IntStream.range(0, 24)
                .mapToObj(h -> String.format("%02d", h))
                .filter(h -> !hoursWithData.contains(h))
                .toList();
        assertThat(emptyHours)
                .as("%s 의 24개 시간대가 전부 차 있어 제로필을 검증할 수 없다", dataEnd)
                .isNotEmpty();

        DashboardTrendVO body = getTrend("?startDt=" + dataEnd + "&endDt=" + dataEnd);

        for (String hour : emptyHours) {
            TrendPointVO point = pointOf(body, hour);
            assertThat(point).as("bucketKey='%s' 칸이 제로필로 존재해야 한다", hour).isNotNull();
            assertThat(point.getTotal()).as("bucketKey='%s' 의 total", hour).isZero();
            assertThat(point.getBreakdown()).as("bucketKey='%s' 의 breakdown", hour).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 3. 유형별 breakdown 합계
    // ------------------------------------------------------------------

    /**
     * <b>무엇을 검증하나</b> — 종전엔 시드 실측 {@code 117}건과 유형별 5개 값을 고정 기대값으로 두어
     * 데이터 변형까지 잡았다. 이제 <b>같은 하루·같은 시각(0시)을 독립 SQL 로 센 값</b>과 비교한다 —
     * {@code lpad(extract(hour …))} 버킷 키 생성과 유형별 breakdown 합산이 생SQL 과 1:1 로 맞는지를 본다.
     */
    @Test
    @DisplayName("00시 칸의 total 과 유형별 breakdown 이 같은 구간을 센 독립 SQL 과 일치한다")
    void hourZeroBreakdownMatchesIndependentCount() {
        Map<String, Long> expected = jdbc.query(
                "SELECT cvlcpt_type_cd AS type_cd, count(*) AS cnt "
              + "  FROM parking.tb_cvlcpt "
              + " WHERE ocrn_dt::date = ? AND extract(hour from ocrn_dt) = 0" + FILTERS
              + " GROUP BY 1",
                (rs, i) -> Map.entry(rs.getString("type_cd"), rs.getLong("cnt")),
                dataEnd).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        long expectedTotal = expected.values().stream().mapToLong(Long::longValue).sum();
        assertThat(expectedTotal).as("%s 00시에 민원이 0건이라 검증이 성립하지 않는다", dataEnd).isPositive();

        DashboardTrendVO body = getTrend("?startDt=" + dataEnd + "&endDt=" + dataEnd);

        TrendPointVO hour00 = pointOf(body, "00");
        assertThat(hour00).as("bucketKey='00' 칸이 존재해야 한다").isNotNull();
        assertThat(hour00.getTotal()).isEqualTo(expectedTotal);
        assertThat(breakdownAsMap(hour00)).containsExactlyInAnyOrderEntriesOf(expected);
    }

    // ------------------------------------------------------------------
    // 4. 버킷 접힘 — 여러 날이 24개 시간 버킷으로 접힌다
    // ------------------------------------------------------------------

    /**
     * {@code unit=WEEK} 의 <b>구간 해석</b>만 본다 — [오늘-7, 오늘-1] 이 24칸으로 접히는지.
     * 합계는 "오늘" 에 따라 달라지므로 단언하지 않는다(합산은 케이스 5 가 명시 구간으로 지킨다).
     */
    @Test
    @DisplayName("조회단위 WEEK 는 어제까지 7일(오늘-7 ~ 오늘-1)로 해석되고 24칸으로 접힌다")
    void weekFoldsIntoTwentyFourHourBuckets() {
        LocalDate before = LocalDate.now(SEOUL);
        DashboardTrendVO body = getTrend("?unit=WEEK");
        LocalDate after = LocalDate.now(SEOUL);

        assertThat(body.getStartDt()).isIn(before.minusDays(7), after.minusDays(7));
        assertThat(body.getEndDt()).isIn(before.minusDays(1), after.minusDays(1));
        assertThat(body.getPoints()).hasSize(24);
    }

    @Test
    @DisplayName(". 조회단위 MONTH 는 어제까지 30일(오늘-30 ~ 오늘-1)이다")
    void monthEndsYesterdayWithThirtyDays() {
        LocalDate before = LocalDate.now(SEOUL);
        DashboardTrendVO body = getTrend("?unit=MONTH&bucket=DAY");
        LocalDate after = LocalDate.now(SEOUL);

        assertThat(body.getStartDt()).isIn(before.minusDays(30), after.minusDays(30));
        assertThat(body.getEndDt()).isIn(before.minusDays(1), after.minusDays(1));
        assertThat(body.getPoints()).hasSize(30);
    }

    // ------------------------------------------------------------------
    // 5. 버킷 기본값은 언제나 HOUR, DAY 는 명시할 때만
    // ------------------------------------------------------------------

    /**
     * 🔴 이 케이스가 개정의 회귀 가드다. 초판은 "기간 직접 지정이면 DAY 자동" 이었는데,
     * 화면에서 기간을 고르면 추이 카드만 일별로 튀는 것이 의도와 달라 <b>기본을 항상 HOUR</b> 로
     * 바꿨다. 이 단언이 깨지면 그 결정이 되돌아간 것이다.
     *
     * <p><b>합계 단언이 무엇을 검증하나</b> — 종전엔 시드 실측 {@code 936}건을 고정값으로 두었다.
     * 이제 <b>같은 {@value #WINDOW_DAYS}일 구간을 독립 SQL 로 센 값</b>과 비교한다 — 여러 날이
     * 24칸으로 접히면서 <b>버킷이 새거나 겹치지 않았는지</b>(접힘의 보존성)를 본다.
     *
     * <p>🔁 <b>2026-10-02 </b> — 집계가 <b>행 단위 {@code count(*)}</b> 로 되돌아가
     * <b>버킷 합 == 구간 총건수</b>가 다시 구조적으로 성립한다(한 행은 정확히 한 버킷에만 들어간다).
     * 그래도 기대값은 <b>버킷별 집계의 합</b>으로 세운다 — 버킷 키 생성·GROUP BY·제로필이 생SQL 과
     * 1:1 인지를 보는 것이 이 케이스의 목적이기 때문이다. 총계와의 등식 자체는
     * {@code bucketSumEqualsRangeTotal_hour/day} 가 따로 못박는다.
     */
    @Test
    @DisplayName("기간을 직접 지정해도 버킷 기본값은 HOUR 24칸이고, 접힌 합계가 버킷별 독립 집계의 합과 같다 (개정)")
    void explicitRangeStillDefaultsToHourBucket() {
        Long expectedTotal = jdbc.queryForObject(
                "SELECT coalesce(sum(cnt), 0) FROM ("
              + "  SELECT count(*) AS cnt FROM parking.tb_cvlcpt "
              + "   WHERE ocrn_dt::date BETWEEN ? AND ?" + FILTERS
              + "   GROUP BY extract(hour from ocrn_dt), cvlcpt_type_cd) q",
                Long.class, rangeStart, dataEnd);
        assertThat(expectedTotal).as("%s~%s 구간이 0건이라 접힘 보존을 검증할 수 없다", rangeStart, dataEnd)
                .isPositive();

        DashboardTrendVO body = getTrend("?startDt=" + rangeStart + "&endDt=" + dataEnd);

        assertThat(body.getBucket()).isEqualTo(TrendBucket.HOUR);
        assertThat(body.getPoints()).hasSize(24);
        assertThat(body.getPoints().get(0).getBucketKey()).isEqualTo("00");
        // 여러 날을 24칸에 접었으므로 전 칸 합 = 버킷별 독립 집계의 합과 같아야 한다.
        assertThat(totalOf(body)).isEqualTo(expectedTotal.longValue());
    }

    @Test
    @DisplayName("bucket=DAY 를 명시하면 날짜당 한 칸씩 온다")
    void explicitDayBucketReturnsOnePointPerDate() {
        DashboardTrendVO body = getTrend("?startDt=" + rangeStart + "&endDt=" + dataEnd + "&bucket=DAY");

        assertThat(body.getBucket()).isEqualTo(TrendBucket.DAY);
        assertThat(body.getPoints()).hasSize(WINDOW_DAYS);
        assertThat(body.getPoints().get(0).getBucketKey()).isEqualTo(rangeStart.toString());
    }

    // ------------------------------------------------------------------
    // 6. typeCd 필터
    // ------------------------------------------------------------------

    /**
     * <b>무엇을 검증하나</b> — 종전엔 시드 실측 {@code 49}건(유형 {@code 02})을 고정값으로 두었다.
     * 이제 <b>그 날 가장 많은 유형을 SQL 로 고르고, 같은 조건으로 센 독립 SQL 값</b>과 비교한다 —
     * {@code typeCd} 필터가 합계와 breakdown <b>양쪽</b>에 동일하게 걸리는지를 본다.
     */
    @Test
    @DisplayName("typeCd 필터를 주면 해당 유형만 합산되고 breakdown 도 그 유형만 남는다")
    void typeCdFilterNarrowsBothTotalAndBreakdown() {
        // 🔁 2026-10-02 행 단위 집계라 "그 날 전체" 와 "버킷 합" 이 같다. 표현은
        //    버킷 합 그대로 둔다(typeCd 필터가 합계·breakdown 양쪽에 걸리는지가 이 케이스의 목적).
        Long expectedTotal = jdbc.queryForObject(
                "SELECT coalesce(sum(cnt), 0) FROM ("
              + "  SELECT count(*) AS cnt FROM parking.tb_cvlcpt "
              + "   WHERE ocrn_dt::date = ? AND cvlcpt_type_cd = ?" + FILTERS
              + "   GROUP BY extract(hour from ocrn_dt)) q",
                Long.class, dataEnd, topTypeCd);
        assertThat(expectedTotal).as("유형 %s 가 %s 에 0건이다", topTypeCd, dataEnd).isPositive();

        DashboardTrendVO body =
                getTrend("?startDt=" + dataEnd + "&endDt=" + dataEnd + "&typeCd=" + topTypeCd);

        assertThat(totalOf(body)).isEqualTo(expectedTotal.longValue());
        assertThat(body.getPoints())
                .flatExtracting(TrendPointVO::getBreakdown)
                .extracting(TypeCountVO::getTypeCd)
                .containsOnly(topTypeCd);
    }

    // ------------------------------------------------------------------
    // 7. 잘못된 입력 → 400 (에러 바디는 String 으로 받는다)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("허용되지 않은 bucket 값(WEEK)은 400 으로 거절된다")
    void unsupportedBucketValueIsRejected() {
        ResponseEntity<String> res =
                restTemplate.getForEntity(TREND_URL + "?unit=YESTERDAY&bucket=WEEK", String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    /**
     * 🔴 <b>종전 {@code overlyLongDayRangeIsRejected} 를 고쳐 쓴 것이다</b>.
     * 예전에는 {@code bucket=DAY} 경로의 {@code MAX_DAY_BUCKETS} 분기가 400 을 냈고 이름도 그것을
     * 가리켰다. 그 분기는 사라지고 검사 주체가 {@code resolveParam()} 의 {@code DateRanges} 로 옮겨졌다 —
     * 이름이 가리키는 것을 더 이상 검증하지 않는 테스트가 되지 않게 함께 고친다.
     *
     * <p>🔴 <b>응답 {@code message} 는 단언하지 않는다.</b> 이 레포의 6개 통합테스트가 같은 이유를
     * 못박고 있고({@code RiskNotiIntegrationTest} 등), {@code developmentOnly} 인 devtools 는 test
     * 런타임 클래스패스에 없어 {@code ./gradlew test} 응답에는 {@code message} 가 아예 없다.
     * 상태코드만으로 판별한다.
     *
     * <p>⚠️ 구간은 {@code LocalDate.now()} 기준 상대값이다 — 366/367 경계를 고정 날짜로 세면
     * 윤년에 따라 하루가 어긋난다.
     */
    @Test
    @DisplayName("조회 기간이 366일을 넘으면 400 으로 거절된다 (bucket 무관)")
    void overlyLongRangeIsRejected() {
        LocalDate end = LocalDate.now();
        // 367일(상한 +1). 365 를 빼면 366일이므로 366 을 빼면 367일이다.
        String over = "?startDt=" + end.minusDays(MAX_RANGE_DAYS) + "&endDt=" + end;

        // (1) bucket=DAY — 종전에도 400 이었다(주체만 바뀌었다)
        assertThat(status(over + "&bucket=DAY")).isEqualTo(400);

        // (2) 🔴 출처 판별 케이스 — bucket 미지정(기본 HOUR). 종전 HOUR 경로는 MAX_DAY_BUCKETS 를
        //     타지 않아 200 이었다. 여기가 400 이라는 것이 곧 "검사 주체가 DateRanges 로 옮겨졌다"는
        //     증거다. 문구에 기대지 않고 그것을 고정하는 유일한 환경 독립적 방법이다.
        assertThat(status(over)).as("bucket 미지정(HOUR)도 같은 규칙으로 거절돼야 한다").isEqualTo(400);
    }

    @Test
    @DisplayName("366일(상한과 같음) 구간은 통과하고 DAY 버킷 366칸으로 온다")
    void maxRangeIsAcceptedWithOneBucketPerDate() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(MAX_RANGE_DAYS - 1L);   // 양끝 포함 366일

        DashboardTrendVO body = getTrend("?startDt=" + start + "&endDt=" + end + "&bucket=DAY");

        assertThat(body.getBucket()).isEqualTo(TrendBucket.DAY);
        // 상한을 넘는 쪽(위 케이스)과 지키는 쪽을 둘 다 잡아야 경계가 고정된다
        assertThat(body.getPoints()).hasSize(MAX_RANGE_DAYS);
        assertThat(body.getPoints().get(0).getBucketKey()).isEqualTo(start.toString());
        assertThat(body.getPoints().get(MAX_RANGE_DAYS - 1).getBucketKey()).isEqualTo(end.toString());
    }

    // ------------------------------------------------------------------
    // 8. 🔁 복원된 불변식 — 버킷 합 == 구간 총건수
    // ------------------------------------------------------------------

    /**
     * 🔴 <b>이 등식이 이번 변경의 핵심 이득이다.</b> 집계가 행 단위 {@code count(*)} 이면 한 행은
     * 정확히 한 버킷에만 들어가므로 <b>버킷 합 == 구간 총건수</b>가 <b>구조적으로</b> 성립한다.
     *
     * <p>DISTINCT 기준(2026-09-04 ~ 2026-10-02)에서는 같은 본문 민원이 여러 버킷에 걸쳐 각 버킷에서
     * 1건씩 세어 이 등식이 깨졌다 — 시간대는 데이터의 우연으로 맞았고(실측 합 911 == 911) 일자는
     * 실제로 어긋났다(실측 합 1,220 vs 총계 911). 되돌아가면 이 케이스가 깨진다.
     *
     * <p>🔴 기대값은 <b>독립 SQL 한 번</b>(구간 전체 count)이다 — 버킷별 집계의 합이 아니다.
     * 버킷별 합을 기대값으로 쓰면 self-referential 이라 이 불변식을 전혀 검증하지 못한다.
     */
    @Test
    @DisplayName("🔁 불변식 — 시간대(HOUR) 버킷 합이 구간 총건수와 같다")
    void hourBucketSumEqualsRangeTotal() {
        Long rangeTotal = jdbc.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt WHERE ocrn_dt::date BETWEEN ? AND ?" + FILTERS,
                Long.class, rangeStart, dataEnd);
        assertThat(rangeTotal).as("%s~%s 구간이 0건이라 불변식을 검증할 수 없다", rangeStart, dataEnd)
                .isNotNull().isPositive();

        DashboardTrendVO body = getTrend("?startDt=" + rangeStart + "&endDt=" + dataEnd);

        assertThat(body.getBucket()).isEqualTo(TrendBucket.HOUR);
        assertThat(totalOf(body))
                .as("시간대 버킷 합 != 구간 총건수 — 민원 집계가 행 단위가 아닌 기준으로 되돌아갔다")
                .isEqualTo(rangeTotal.longValue());
    }

    /** 일자 버킷. DISTINCT 기준에서 <b>실제로</b> 어긋나던 쪽이라 회귀 가드로서 값이 크다. */
    @Test
    @DisplayName("🔁 불변식 — 일자(DAY) 버킷 합이 구간 총건수와 같다")
    void dayBucketSumEqualsRangeTotal() {
        Long rangeTotal = jdbc.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt WHERE ocrn_dt::date BETWEEN ? AND ?" + FILTERS,
                Long.class, rangeStart, dataEnd);
        assertThat(rangeTotal).isNotNull().isPositive();

        DashboardTrendVO body = getTrend("?startDt=" + rangeStart + "&endDt=" + dataEnd + "&bucket=DAY");

        assertThat(body.getBucket()).isEqualTo(TrendBucket.DAY);
        assertThat(totalOf(body))
                .as("일자 버킷 합 != 구간 총건수 — 민원 집계가 행 단위가 아닌 기준으로 되돌아갔다")
                .isEqualTo(rangeTotal.longValue());
    }

    /**
     * 유형별 breakdown 의 합도 같은 총계여야 한다(버킷 안에서 유형이 새거나 겹치지 않는다).
     * 🔴 {@code total} 이 아니라 <b>breakdown 을 더해</b> 비교한다 — 서비스가 total 을 따로 계산하므로
     * 둘이 갈릴 수 있고, 그 어긋남을 잡는 것이 이 케이스다.
     */
    @Test
    @DisplayName("🔁 불변식 — 유형별 breakdown 합이 구간 총건수와 같다")
    void typeBreakdownSumEqualsRangeTotal() {
        Long rangeTotal = jdbc.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt WHERE ocrn_dt::date BETWEEN ? AND ?" + FILTERS,
                Long.class, rangeStart, dataEnd);
        assertThat(rangeTotal).isNotNull().isPositive();

        DashboardTrendVO body = getTrend("?startDt=" + rangeStart + "&endDt=" + dataEnd);

        long breakdownSum = body.getPoints().stream()
                .flatMap(p -> p.getBreakdown().stream())
                .mapToLong(TypeCountVO::getCount)
                .sum();
        assertThat(breakdownSum)
                .as("유형별 breakdown 합 != 구간 총건수")
                .isEqualTo(rangeTotal.longValue());
    }

    // ------------------------------------------------------------------
    // helper
    // ------------------------------------------------------------------

    private DashboardTrendVO getTrend(String query) {
        ResponseEntity<DashboardTrendVO> res =
                restTemplate.getForEntity(TREND_URL + query, DashboardTrendVO.class);

        assertThat(res.getStatusCode().value()).as("GET %s%s", TREND_URL, query).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    /** 상태코드만 본다 — 에러 바디는 String 으로 받는다(역직렬화 실패 회피). */
    private int status(String query) {
        return restTemplate.getForEntity(TREND_URL + query, String.class).getStatusCode().value();
    }

    /** 주어진 bucketKey 칸을 찾는다 — 없으면 null (제로필 누락을 단언으로 드러내기 위함). */
    private TrendPointVO pointOf(DashboardTrendVO body, String bucketKey) {
        return body.getPoints().stream()
                .filter(p -> bucketKey.equals(p.getBucketKey()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Long> breakdownAsMap(TrendPointVO point) {
        List<TypeCountVO> breakdown = point.getBreakdown();
        return breakdown.stream()
                .collect(Collectors.toMap(TypeCountVO::getTypeCd, TypeCountVO::getCount));
    }

    private long totalOf(DashboardTrendVO body) {
        return body.getPoints().stream().mapToLong(TrendPointVO::getTotal).sum();
    }
}
