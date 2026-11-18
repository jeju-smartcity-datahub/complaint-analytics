package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import jeju.datahub.analysis.ZoneRcmdtnTestData.Cand;
import jeju.datahub.analysis.ZoneRcmdtnTestData.Near;
import jeju.datahub.analysis.ZoneRcmdtnTestData.Src;
import jeju.datahub.analysis.ZoneRcmdtnTestData.Win;
import jeju.datahub.analysis.service.ZoneRcmdtnBaselineVO;
import jeju.datahub.analysis.service.ZoneRcmdtnExclLotVO;
import jeju.datahub.analysis.service.ZoneRcmdtnLotVO;
import jeju.datahub.analysis.service.impl.ZoneRcmdtnMapper;
import jeju.datahub.analysis.service.impl.ZoneRcmdtnQueryParam;
import jeju.datahub.analysis.service.impl.ZoneRcmdtnTrendCountVO;

/**
 * 구역추천 읍면동 기준선 매퍼 테스트 (검증).
 *
 * <h2>🔴 이 테스트의 1차 목적은 MyBatis XML 의 <b>런타임 파싱</b> 검증이다</h2>
 * {@code ZoneRcmdtnMapper.xml} 의 {@code <choose><when test="…">} 은 <b>OGNL</b> 로 평가된다.
 * OGNL 은 홑따옴표 리터럴을 길이에 따라 다르게 해석해(1글자면 {@code char}) 비교가 조용히
 * 거짓이 되는 함정이 있다 — 분기가 안 타도 <b>예외가 나지 않고</b> {@code <otherwise>} 로
 * 떨어져 "유형 필터가 먹지 않는" 상태가 된다.
 * 🔴 {@code xmllint}(XML 문법)·{@code psql}(SQL 실행)로는 원리적으로 잡히지 않는다 —
 * <b>JVM 으로 실제 분기를 태워야</b> 드러난다. {@link #wideWidensAbleCpcty()} 가 그 단언이다.
 *
 * <p>함께 확인하는 것 — {@code #{}} 바인딩 타입(민원·단속 모두 {@code timestamp}/{@link LocalDateTime}),
 * {@code TB_ADMDST} 기준 {@code LEFT JOIN} 의 행 수, {@code map-underscore-to-camel-case} 로 가는
 * 컬럼 → VO 필드 매핑.
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다. 전부 {@code SELECT} 이고
 * {@code @Transactional} 로 감싸 DB 를 바꾸지 않는다({@code WebEnvironment.MOCK} 이라 포트를 열지
 * 않으므로 롤백이 실제로 걸린다 — {@code RANDOM_PORT} 테스트들과 다른 점이다).
 *
 * <h2>🔴 날짜·읍면동·건수를 박지 않는다 — 로컬 데이터가 날짜째 옮겨진다</h2>
 * 종전에는 "고정 구간의 재현성" 을 이유로 조회 날짜와 이도이동의 실측값(388.75·397·1128·218 …)을
 * 박아 두었는데, 민원·단속 적재분이 날짜째 옮겨지고 행이 바뀔 때마다 <b>코드 결함 없이 깨졌다</b>.
 * 지금은 ① 조회 창을 DB 의 {@code min/max} 에서 읽고 ② 대상 읍면동을 조건으로 고르며(후보 좌표가
 * 충분한 동 중 민원이 가장 많은 동 — {@code busyEmd} · "하한을 내리면 후보가 늘어나는 동" 등) ③ 기대값은
 * {@link ZoneRcmdtnTestData} 의 <b>매퍼와 다른 경로의 SQL</b>로 다시 센다.
 * 🔴 OGNL 분기 검증은 그대로 성립한다 — "세 변형의 차이가 데이터 때문인지 분기 때문인지" 는
 * 독립 SQL 이 유형 범위별 기대값을 따로 내 주므로 가려진다({@code WIDE} 기대값 ≠ {@code NARROW}
 * 기대값인 동을 골라 쓴다).
 * ⚠️ 조건에 맞는 대상이 데이터에 없으면 실패가 아니라 <b>사유와 함께 건너뛴다</b>. 민원·단속이
 * 통째로 비었으면 {@code @BeforeEach} 가 그 사실을 메시지로 드러내며 실패한다.
 *
 * <p>⚠️ 아래 숫자 리터럴({@code 20}·{@code 0}·{@code 100/300/500})은 실측값이 아니라 매퍼에 넣는
 * <b>입력</b>(면수 하한 · 배정 반경)이다.
 *
 * <h2>🔴 알려진 검증 공백 — 작성 시점(2026-10-13) 로컬 데이터 기준</h2>
 * 기대값을 독립 SQL 로 다시 세는 방식은 <b>데이터가 그 조건으로 결과를 가를 때만</b> 조건을 검증한다.
 * 아래 네 조건은 지금 매퍼 XML 에서 지워도 <b>이 클래스의 어떤 테스트도 깨지지 않는다</b>(초록불이
 * "검증됨" 이 아니다). 건수는 2026-10-13 에 {@code SELECT} 로 다시 센 값이고, 데이터가 바뀌면 달라진다.
 * <ol>
 *   <li><b>민영 제외</b>({@code ownshp_type_nm <> '민영'}) — 민영 55개소가 전부 {@code pklt_type_nm='민영'}
 *       이라 후보 유형(NARROW·WIDE 어느 쪽에도 '민영' 이 없다)에서 먼저 걸러진다. 후보 유형이면서 민영인
 *       주차장이 0개소다.</li>
 *   <li><b>소멸 제외</b>({@code extsh_dt IS NULL}) — 소멸 주차장이 0개소다(2,263개소 전부 {@code NULL}).
 *       가능 면수뿐 아니라 <b>기존 유료 면수</b>의 같은 조건도 공백이다.</li>
 *   <li><b>유형 99 제외</b>({@code cvlcpt_type_cd <> '99'}) — 유형 99 민원 46,581건이 전부 {@code emd_cd}
 *       {@code NULL}(좌표도 없다)이라 {@code emd_cd IS NOT NULL} · {@code emd_cd = #{emdCd}} 가 먼저 걸러 낸다.</li>
 *   <li><b>좌표 가드</b>({@code lat IS NOT NULL}) — {@code emd_cd} 가 있는 민원·단속 중 좌표 없는 행이 0건이다
 *       (좌표 없는 행은 위 유형 99 민원 46,581건과 단속 88,255건인데 전부 {@code emd_cd} {@code NULL}).
 *       🔴 이 조건은 <b>데이터가 생겨도 결과로는 검증되지 않는다</b> — {@code geom} 이 {@code lat}/{@code lot}
 *       에서 만든 생성컬럼이라 좌표가 없으면 {@code NULL} 이고, {@code ST_DWithin(…, NULL)} 이 {@code NULL} 이라
 *       가드가 없어도 그 행은 배정되지 않는다. 가드가 지는 것은 결과가 아니라 의도·비용이다.</li>
 * </ol>
 * <b>데이터가 그 조건을 가르게 되면</b>
 * <ul>
 *   <li>자동으로 켜지는 것 — 1~3 은 테스트 2(전 읍면동의 가능 면수·유료 면수·민원 월평균 = 독립 SQL)가
 *       매퍼 쪽 조건 누락을 잡는다(독립 SQL 이 같은 조건을 따로 적고 있다). 고른 읍면동에 생기면 6·12 도
 *       잡는다. 단 유형 99 는 <b>좌표까지 있어야</b> 주차장 배정(6·9)에 드러난다.</li>
 *   <li>🔴 자동으로 안 켜지는 것 — 독립 SQL 과 매퍼가 <b>같은 조건을 함께 잃으면</b> 둘이 같이 틀려
 *       초록불이다. 그래서 테스트 18~21 이 독립 SQL 의 후보 조건을 거치지 않고 <b>"그 행이 결과에 없다"</b>
 *       를 직접 단언한다. 지금은 대상이 없어 <b>사유와 함께 건너뛴다</b>(스킵 4건이 정상이다).</li>
 *   <li>4 는 위 이유로 21 이 켜져도 "가드가 있다" 는 증명이 아니다 — 21 은 좌표 없는 행이 쿼리를 깨지
 *       않고 배정에 들지 않는다는 <b>결과</b>만 본다.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@DisplayName("·3 — 구역추천 기준선·주차장 배정 매퍼")
class ZoneRcmdtnMapperTest {

    // --- 매퍼 입력 (실측값이 아니다) ---
    /** 후보 면수 하한 — 기본 */
    private static final BigDecimal MIN_20 = new BigDecimal("20");
    /** 후보 면수 하한 — 하한이 실제로 걸리는지 보려고 내린 값 */
    private static final BigDecimal MIN_0 = BigDecimal.ZERO;
    private static final BigDecimal RNG_100 = new BigDecimal("100");
    /** 기본 배정 반경(m) */
    private static final BigDecimal RNG_300 = new BigDecimal("300");
    private static final BigDecimal RNG_500 = new BigDecimal("500");

    /** 월평균(numeric 나눗셈) 비교 허용 오차 — 스케일은 DB 가 정한다 */
    private static final BigDecimal EPS = new BigDecimal("0.000001");

    @Autowired
    private ZoneRcmdtnMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ZoneRcmdtnTestData data;

    /** 민원 보유 구간(자르지 않은 원래 구간) */
    private Win cvRange;
    /** 민원 창 — 자료 시작일부터(상한 36개월). 🔴 시작이 자료 시작일이라 1차 '이전' 블록이 반드시 0건이다 */
    private Win cv;
    /** 단속 창 — 단속 보유 구간(상한 36개월). 🔴 민원 창과 다른 구간이다 */
    private Win crdn;
    /** 조건으로 고른 읍면동 — NARROW 후보 좌표가 충분한({@code RANK_LOCS} 곳 이상 · 없으면 최다) 동 중 창 안 민원이 가장 많은 동({@code busyEmd}). 없으면 {@code null} */
    private String emd;

    @BeforeEach
    void resolveWindowsAndTarget() {
        data = new ZoneRcmdtnTestData(jdbcTemplate);
        cvRange = data.cvRange();
        assertThat(cvRange)
                .as("주차 민원(parking.tb_cvlcpt · 유형 99 제외 · emd_cd 있음)이 비어 있다 — 적재분을 먼저 확인하라")
                .isNotNull();
        Win crdnRange = data.crdnRange();
        assertThat(crdnRange)
                .as("단속(parking.tb_crdn_dsctn · emd_cd 있음)이 비어 있다 — 적재분을 먼저 확인하라")
                .isNotNull();
        cv = ZoneRcmdtnTestData.head(cvRange);
        crdn = ZoneRcmdtnTestData.head(crdnRange);
        emd = data.busyEmd(cv, MIN_20, false, 2);
    }

    /** 고른 읍면동이 있어야 도는 테스트의 전제 */
    private String targetEmd() {
        assumeTrue(emd != null, "NARROW·하한 20면 후보 좌표가 2곳 이상이면서 민원이 있는 읍면동이 없다");
        return emd;
    }

    /** 조건을 만족하는 첫 읍면동(코드 오름차순). 없으면 사유와 함께 건너뛴다 */
    private String pickEmd(Predicate<String> cond, String what) {
        String picked = data.emdCds().stream().filter(cond).findFirst().orElse(null);
        assumeTrue(picked != null, what + " 읍면동이 데이터에 없다");
        return picked;
    }

    private ZoneRcmdtnQueryParam param(String emdCd, Win cvWin, BigDecimal minCpcty,
                                       String trgtTypeSe, BigDecimal rng) {
        return ZoneRcmdtnTestData.queryParam(emdCd, cvWin, crdn, minCpcty, trgtTypeSe, rng);
    }

    private ZoneRcmdtnQueryParam param(String emdCd, BigDecimal minCpcty, String trgtTypeSe) {
        return param(emdCd, cv, minCpcty, trgtTypeSe, RNG_300);
    }

    private static ZoneRcmdtnBaselineVO row(List<ZoneRcmdtnBaselineVO> rows, String emdCd) {
        return rows.stream()
                .filter(r -> emdCd.equals(r.getEmdCd()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("읍면동(" + emdCd + ") 행이 없다"));
    }

    private ZoneRcmdtnBaselineVO baseline(String emdCd, BigDecimal minCpcty, String trgtTypeSe) {
        return row(mapper.selectBaseline(param(emdCd, minCpcty, trgtTypeSe)), emdCd);
    }

    private static BigDecimal avg(long cnt, BigDecimal months) {
        return BigDecimal.valueOf(cnt).divide(months, 10, RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("1. 기본 호출 — 행 수 = tb_admdst 행 수 · sggNm 전 행 NOT NULL")
    void baselineReturnsAllAdmdstRows() {
        List<ZoneRcmdtnBaselineVO> rows = mapper.selectBaseline(param(null, MIN_20, "NARROW"));

        assertThat(rows).as("LEFT JOIN 이라 원천 0건인 동도 남는다").hasSize(data.admdstCount());
        assertThat(rows).extracting(ZoneRcmdtnBaselineVO::getEmdCd).doesNotHaveDuplicates();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getEmdCd()).isNotBlank();
            assertThat(r.getEmdNm()).isNotBlank();
            assertThat(r.getSggNm()).as("sggNm 은 TB_ADMDST NOT NULL 이라 비지 않는다").isNotBlank();
            // COALESCE 로 0 을 채우므로 null 이 없다 — 서비스가 null 체크 없이 계산할 수 있는 근거
            assertThat(r.getCvlcptMmAvg()).isNotNull();
            assertThat(r.getCrdnMmAvg()).isNotNull();
            assertThat(r.getAbleCpcty()).isNotNull();
            assertThat(r.getPaidCpcty()).isNotNull();
        });
    }

    /**
     * 기준선 네 칸을 <b>전 읍면동</b>에 대해 독립 SQL 과 맞춘다 — 민원 월평균 = 창 안 건수 ÷ 개월수,
     * 단속 월평균 = 단속 창 건수 ÷ 단속 개월수, 가능 면수 = 후보 면수 합, 유료 면수 = 유료·미소멸 면수 합.
     *
     * <p>🔴 민원 창과 단속 창이 <b>서로 다른 구간</b>이라, 한쪽 바인딩이 다른 쪽 값을 돌려쓰면 여기서
     * 어긋난다(개월수 분모도 따로다).
     */
    @Test
    @DisplayName("2. 기준선 = 독립 SQL — 민원·단속 월평균(건수 ÷ 개월수) · 가능 면수 · 유료 면수 (전 읍면동)")
    void baselineMatchesIndependentCounts() {
        List<ZoneRcmdtnBaselineVO> rows = mapper.selectBaseline(param(null, MIN_20, "NARROW"));

        Map<String, Long> cvCnt = data.cvCountByEmd(cv);
        Map<String, Long> crdnCnt = data.crdnCountByEmd(crdn);
        Map<String, Cand> cand = data.candByEmd(MIN_20, false);
        Map<String, Long> paid = data.paidCpctyByEmd();
        // 창이 보유 구간에서 나왔으므로 원천이 비어 있을 수 없다 — 아래 대조가 0 = 0 으로 끝나지 않는다
        assertThat(ZoneRcmdtnTestData.sum(cvCnt)).as("민원 창 안 건수").isPositive();
        assertThat(ZoneRcmdtnTestData.sum(crdnCnt)).as("단속 창 안 건수").isPositive();

        for (ZoneRcmdtnBaselineVO r : rows) {
            String e = r.getEmdCd();
            // 🔴 numeric 나눗셈이라 스케일이 DB 가 정한다 — 값만 본다
            assertThat(r.getCvlcptMmAvg()).as("%s 민원 월평균", e)
                    .isCloseTo(avg(cvCnt.getOrDefault(e, 0L), cv.months()), within(EPS));
            assertThat(r.getCrdnMmAvg()).as("%s 단속 월평균 — 단속 timestamp 바인딩·단속 개월수", e)
                    .isCloseTo(avg(crdnCnt.getOrDefault(e, 0L), crdn.months()), within(EPS));
            assertThat(r.getAbleCpcty()).as("%s 가능 면수", e).usingComparator(BigDecimal::compareTo)
                    .isEqualTo(BigDecimal.valueOf(cand.getOrDefault(e, Cand.NONE).cpcty()));
            assertThat(r.getPaidCpcty()).as("%s 기존 유료 면수", e).usingComparator(BigDecimal::compareTo)
                    .isEqualTo(BigDecimal.valueOf(paid.getOrDefault(e, 0L)));
        }
    }

    @Test
    @DisplayName("3. 🔴 minCpcty=0 — 하한이 실제로 걸린다 (하한을 내리면 후보가 늘어나는 동에서 값이 달라진다)")
    void minCpctyLowerBoundActuallyApplies() {
        Map<String, Cand> min20 = data.candByEmd(MIN_20, false);
        Map<String, Cand> min0 = data.candByEmd(MIN_0, false);
        String e = pickEmd(c -> min0.getOrDefault(c, Cand.NONE).cpcty() > min20.getOrDefault(c, Cand.NONE).cpcty(),
                "하한 20면 미달인 NARROW 후보 주차장이 있는");

        assertThat(baseline(e, MIN_0, "NARROW").getAbleCpcty())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(BigDecimal.valueOf(min0.get(e).cpcty()));
        // 하한 20 과 값이 같다면 #{minCpcty} 가 바인딩되지 않은 것이다
        assertThat(baseline(e, MIN_20, "NARROW").getAbleCpcty())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(BigDecimal.valueOf(min20.getOrDefault(e, Cand.NONE).cpcty()))
                .isNotEqualTo(BigDecimal.valueOf(min0.get(e).cpcty()));
    }

    /**
     * 🔴 <b>OGNL 분기 검증</b> — {@code <when test="trgtTypeSe == 'WIDE'">} 가 실제로 타는가.
     *
     * <p>타지 않으면 {@code <otherwise>}(NARROW) 로 떨어져 <b>NARROW 의 값이 그대로 나온다</b> —
     * 예외도 경고도 없다. 그래서 {@code WIDE} 기대값이 {@code NARROW} 기대값과 <b>다른 동</b>을 골라
     * "WIDE 기대값이다" 를 단언한다(두 기대값은 독립 SQL 이 유형 범위별로 따로 낸다).
     */
    @Test
    @DisplayName("4. 🔴 trgtTypeSe=WIDE — OGNL 분기가 타서 ableCpcty 가 WIDE 기대값이다 (NARROW 값이면 분기 실패)")
    void wideWidensAbleCpcty() {
        Map<String, Cand> narrow = data.candByEmd(MIN_20, false);
        Map<String, Cand> wide = data.candByEmd(MIN_20, true);
        String e = pickEmd(c -> wide.getOrDefault(c, Cand.NONE).cpcty() > narrow.getOrDefault(c, Cand.NONE).cpcty(),
                "WIDE 에서만 후보가 되는 주차장(노상·공한지)이 있는");

        assertThat(baseline(e, MIN_20, "WIDE").getAbleCpcty())
                .as("WIDE 인데 NARROW 값(%s)이면 <when> 이 안 탄 것이다 — XML 을 안전형(\"WIDE\".equals(…))으로 고쳐야 한다",
                        narrow.getOrDefault(e, Cand.NONE).cpcty())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(BigDecimal.valueOf(wide.get(e).cpcty()))
                .isNotEqualTo(BigDecimal.valueOf(narrow.getOrDefault(e, Cand.NONE).cpcty()));
    }

    @Test
    @DisplayName("5. 유형·하한은 가능 면수에만 영향 — cvlcptMmAvg·crdnMmAvg·paidCpcty 는 불변")
    void typeAndMinCpctyDoNotAffectOtherMetrics() {
        Map<String, Cand> n20 = data.candByEmd(MIN_20, false);
        Map<String, Cand> n0 = data.candByEmd(MIN_0, false);
        Map<String, Cand> w20 = data.candByEmd(MIN_20, true);
        String e = pickEmd(c -> {
            long a = n20.getOrDefault(c, Cand.NONE).cpcty();
            long b = n0.getOrDefault(c, Cand.NONE).cpcty();
            long d = w20.getOrDefault(c, Cand.NONE).cpcty();
            return a != b && a != d && b != d;
        }, "세 변형(NARROW/20 · NARROW/0 · WIDE/20)의 가능 면수가 서로 다른");

        ZoneRcmdtnBaselineVO narrow20 = baseline(e, MIN_20, "NARROW");
        ZoneRcmdtnBaselineVO narrow0 = baseline(e, MIN_0, "NARROW");
        ZoneRcmdtnBaselineVO wide20 = baseline(e, MIN_20, "WIDE");

        BigDecimal cvAvg = avg(data.cvCountByEmd(cv).getOrDefault(e, 0L), cv.months());
        BigDecimal paid = BigDecimal.valueOf(data.paidCpctyByEmd().getOrDefault(e, 0L));
        for (ZoneRcmdtnBaselineVO vo : List.of(narrow20, narrow0, wide20)) {
            assertThat(vo.getCvlcptMmAvg()).as("민원 월평균은 후보 조건과 무관하다").isCloseTo(cvAvg, within(EPS));
            assertThat(vo.getPaidCpcty()).as("기존 유료 면수는 후보 조건과 무관한 현황값이다")
                    .usingComparator(BigDecimal::compareTo).isEqualTo(paid);
            // 단속 월평균도 세 변형에서 같은 값이어야 한다(기간만이 입력이다)
            assertThat(vo.getCrdnMmAvg()).usingComparator(BigDecimal::compareTo)
                    .isEqualTo(narrow20.getCrdnMmAvg());
        }
        // 세 변형이 가능 면수만 서로 다르다 — 그런 동을 골랐으므로 같으면 조건이 바인딩되지 않은 것이다
        assertThat(List.of(narrow20.getAbleCpcty(), narrow0.getAbleCpcty(), wide20.getAbleCpcty()))
                .extracting(BigDecimal::stripTrailingZeros)
                .doesNotHaveDuplicates();
    }

    /**
     * 배정률 총건수는 월평균 × 개월수 되곱이 아니라 매퍼의 <b>원 건수</b>({@code cvlcptCnt})다.
     * 그 값이 직접 {@code count(*)} 와 같고, 월평균이 같은 원 건수를 같은 분모({@code PrdMonths})로 나눈 값인지 본다.
     */
    @Test
    @DisplayName("5-1. 🔴 cvlcptCnt = 직접 count(*) · cvlcptMmAvg ≈ cvlcptCnt ÷ months (전 읍면동)")
    void baselineCarriesRawCvlcptCnt() {
        List<ZoneRcmdtnBaselineVO> rows = mapper.selectBaseline(param(null, MIN_20, "NARROW"));
        Map<String, Long> cvCnt = data.cvCountByEmd(cv);
        assertThat(ZoneRcmdtnTestData.sum(cvCnt)).as("민원 창 안 건수 — 0 = 0 대조로 끝나지 않는다").isPositive();

        for (ZoneRcmdtnBaselineVO r : rows) {
            String e = r.getEmdCd();
            assertThat(r.getCvlcptCnt()).as("%s 민원 원 건수 — COALESCE 라 null 이 아니다", e)
                    .isNotNull()
                    .isEqualTo(cvCnt.getOrDefault(e, 0L));
            assertThat(r.getCvlcptMmAvg()).as("%s 민원 월평균 = 원 건수 ÷ 개월수(PrdMonths)", e)
                    .isCloseTo(avg(r.getCvlcptCnt(), cv.months()), within(EPS));
        }
    }

    // =====================================================================================
    // 주차장 최근접 배정 (selectLotRanking)
    // =====================================================================================

    /** 후보 주차장의 배정 민원 합 */
    private static long asgnSum(List<ZoneRcmdtnLotVO> lots) {
        return lots.stream().mapToLong(ZoneRcmdtnLotVO::getAsgnCvlcptCnt).sum();
    }

    /** 후보 주차장의 주차면수 합 */
    private static long cpctySum(List<ZoneRcmdtnLotVO> lots) {
        return lots.stream().mapToLong(ZoneRcmdtnLotVO::getPkltCpcty).sum();
    }

    /** 정렬 계약(배정 민원 ↓ · 면수 ↓ · pklt_id ↑)을 이웃한 쌍마다 확인한다 */
    private static void assertRankOrder(List<ZoneRcmdtnLotVO> lots) {
        for (int i = 1; i < lots.size(); i++) {
            assertThat(ZoneRcmdtnTestData.inRankOrder(lots.get(i - 1), lots.get(i)))
                    .as("%d위 '%s' → %d위 '%s' 의 순서 (배정 민원 ↓ · 면수 ↓ · pklt_id ↑)",
                            i, lots.get(i - 1).getPkltNm(), i + 1, lots.get(i).getPkltNm())
                    .isTrue();
        }
    }

    /**
     * 주차장별 배정 건수를 <b>독립 재계산</b>과 맞춘다 — 민원은 민원 창, 단속은 단속 창으로 따로 센다.
     * 후보 개소·면수 합은 {@code selectBaseline} 의 가능 면수와 같은 조각을 공유하므로 독립 SQL 의 후보
     * 집계와 같아야 한다(한쪽만 바뀌면 "가능 면수와 목록 합이 다르다").
     */
    @Test
    @DisplayName("6. 주차장 목록 = 독립 SQL — 후보 개소·면수 합 · 주차장별 배정 민원/단속 · 면당 민원")
    void lotRankingMatchesIndependentAssignment() {
        String e = targetEmd();
        List<ZoneRcmdtnLotVO> lots = mapper.selectLotRanking(param(e, MIN_20, "NARROW"));

        Cand cand = data.cand(e, MIN_20, false);
        assertThat(lots).hasSize((int) cand.cnt());
        assertThat(cpctySum(lots)).as("후보 면수 합은 selectBaseline 의 ableCpcty 와 같아야 한다")
                .isEqualTo(cand.cpcty())
                .isEqualTo(baseline(e, MIN_20, "NARROW").getAbleCpcty().longValueExact());

        Map<String, Long> cvAsgn = data.asgnByLoc(Src.CV, e, cv, MIN_20, false, RNG_300);
        Map<String, Long> crdnAsgn = data.asgnByLoc(Src.CRDN, e, crdn, MIN_20, false, RNG_300);
        assumeTrue(ZoneRcmdtnTestData.sum(cvAsgn) > 0, "고른 읍면동에 반경 안 민원이 한 건도 없다");
        assertThat(ZoneRcmdtnTestData.byLoc(lots, ZoneRcmdtnLotVO::getAsgnCvlcptCnt))
                .as("주차장(좌표)별 배정 민원").isEqualTo(cvAsgn);
        assertThat(ZoneRcmdtnTestData.byLoc(lots, ZoneRcmdtnLotVO::getAsgnCrdnCnt))
                .as("주차장(좌표)별 배정 단속 — 🔴 단속 창(민원 창과 다르다)으로 센다").isEqualTo(crdnAsgn);
        assertRankOrder(lots);

        // 🔴 매퍼는 순서만 정한다 — 번호·누적·도달 여부는 서비스가 채운다
        assertThat(lots).allSatisfy(l -> {
            assertThat(l.getPkltId()).isNotNull();
            assertThat(l.getLat()).as("tb_pblpklt.lat 은 NOT NULL 이라 비지 않는다").isNotNull();
            assertThat(l.getLot()).isNotNull();
            assertThat(l.getAsgnCvlcptCnt()).as("COALESCE 로 0 을 채우므로 null 이 없다").isNotNull();
            assertThat(l.getAsgnCrdnCnt()).isNotNull();
            // 면당 민원 = 배정 민원 ÷ 면수 (소수 2자리)
            assertThat(l.getCvlcptPerSlot()).as("%s 면당 민원", l.getPkltNm())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(BigDecimal.valueOf(l.getAsgnCvlcptCnt())
                            .divide(BigDecimal.valueOf(l.getPkltCpcty()), 2, RoundingMode.HALF_UP));
            assertThat(l.getRnk()).as("rnk 는 매퍼가 채우지 않는다").isNull();
            assertThat(l.getCumCpcty()).as("cumCpcty 는 매퍼가 채우지 않는다").isNull();
        });
    }

    /**
     * 🔴 <b>동순위 안정성</b> — 배정 0건인 주차장이 흔해 1차 키({@code asgnCvlcptCnt})만으로는
     * 호출마다 순서가 바뀐다. {@code ORDER BY} 의 2·3차 키({@code pklt_cpcty DESC, pklt_id})가
     * 그것을 막는다. 후보가 많은 {@code WIDE} 로 본다(동순위가 생기기 쉽다).
     */
    @Test
    @DisplayName("7. 🔴 같은 호출 2회 — 순서 동일 · 이웃한 쌍마다 2·3차 키까지 정렬돼 있다")
    void lotRankingIsStableAcrossCalls() {
        String e = targetEmd();
        List<ZoneRcmdtnLotVO> first = mapper.selectLotRanking(param(e, MIN_20, "WIDE"));
        List<ZoneRcmdtnLotVO> second = mapper.selectLotRanking(param(e, MIN_20, "WIDE"));

        assertThat(first).as("고른 동은 후보가 2개소 이상이다").hasSizeGreaterThanOrEqualTo(2);
        assertThat(second).extracting(ZoneRcmdtnLotVO::getPkltId)
                .as("2·3차 키가 없으면 동순위 구간의 순서가 호출마다 바뀐다")
                .containsExactlyElementsOf(first.stream().map(ZoneRcmdtnLotVO::getPkltId).toList());
        // 🔴 동순위가 데이터에 있든 없든 성립하는 형태로 본다 — 순서가 세 키로 완전히 결정돼 있다
        assertRankOrder(first);
    }

    /**
     * 🔴 <b>중복 없음</b> — 민원 1건은 가장 가까운 주차장 <b>1곳에만</b> 센다
     * ({@code CROSS JOIN LATERAL … LIMIT 1}). {@code ST_DWithin} 만으로 반경 집계를 하면
     * 주차장이 겹치는 구간의 민원이 여러 번 세어져 이 상한을 <b>넘는다</b>.
     */
    @Test
    @DisplayName("8. 🔴 중복 없음 — SUM(asgnCvlcptCnt) ≤ 그 동 좌표 보유 민원 수 (반경 100/300/500 · NARROW/WIDE)")
    void noDoubleCountingWithinEmd() {
        String e = targetEmd();
        long coord = data.cvCoordCount(e, cv);
        for (String type : List.of("NARROW", "WIDE")) {
            for (BigDecimal rng : List.of(RNG_100, RNG_300, RNG_500)) {
                long sum = asgnSum(mapper.selectLotRanking(param(e, cv, MIN_20, type, rng)));
                assertThat(sum)
                        .as("%s 반경 %sm — 최근접 배정이면 좌표 보유 민원 수(%d)를 넘을 수 없다", type, rng, coord)
                        .isLessThanOrEqualTo(coord);
            }
        }
    }

    @Test
    @DisplayName("9. altmntRng 100/300/500 — 배정 합 = 반경별 독립 재계산 · 반경이 커지면 줄지 않는다 (미터 단위 바인딩)")
    void altmntRngChangesAssignedTotal() {
        String e = targetEmd();
        long prev = -1;
        for (BigDecimal rng : List.of(RNG_100, RNG_300, RNG_500)) {
            long sum = asgnSum(mapper.selectLotRanking(param(e, cv, MIN_20, "NARROW", rng)));
            // 🔴 독립 SQL 의 반경도 미터다 — 단위가 어긋나면(도 단위 등) 여기서 갈린다
            assertThat(sum).as("반경 %sm 배정 합", rng)
                    .isEqualTo(data.asgnTotal(Src.CV, e, cv, MIN_20, false, rng));
            assertThat(sum).as("반경 %sm — 반경이 커지면 배정 합은 줄지 않는다", rng).isGreaterThanOrEqualTo(prev);
            prev = sum;
        }
    }

    /**
     * 🔴 <b>반경을 바꾸면 주차장별 배정(= 순위의 근거)이 바뀐다</b> — 응답에 적용 반경을 함께 담아야
     * 하는 근거다. 같은 동이라도 반경이 다르면 다른 목록이므로, 반경 없이 순위만 보여주면 재현되지 않는다.
     */
    @Test
    @DisplayName("10. 🔴 altmntRng 가 주차장별 배정을 바꾼다 — 100m·300m 각각 독립 재계산과 일치 · 후보 집합은 같다")
    void altmntRngChangesRanking() {
        String e = targetEmd();
        Map<String, Long> exp100 = data.asgnByLoc(Src.CV, e, cv, MIN_20, false, RNG_100);
        Map<String, Long> exp300 = data.asgnByLoc(Src.CV, e, cv, MIN_20, false, RNG_300);
        assumeTrue(!exp100.equals(exp300), "고른 읍면동은 100m 와 300m 의 배정이 같다 — 반경 효과를 볼 수 없다");

        List<ZoneRcmdtnLotVO> at100 = mapper.selectLotRanking(param(e, cv, MIN_20, "NARROW", RNG_100));
        List<ZoneRcmdtnLotVO> at300 = mapper.selectLotRanking(param(e, cv, MIN_20, "NARROW", RNG_300));

        // 후보 개소는 반경과 무관하다 (후보 조건이 반경을 안 쓴다)
        assertThat(at100).extracting(ZoneRcmdtnLotVO::getPkltId)
                .containsExactlyInAnyOrderElementsOf(at300.stream().map(ZoneRcmdtnLotVO::getPkltId).toList());
        assertThat(ZoneRcmdtnTestData.byLoc(at100, ZoneRcmdtnLotVO::getAsgnCvlcptCnt)).isEqualTo(exp100);
        assertThat(ZoneRcmdtnTestData.byLoc(at300, ZoneRcmdtnLotVO::getAsgnCvlcptCnt)).isEqualTo(exp300);
        // 각 반경에서 순서는 그 반경의 배정 건수가 정한다
        assertRankOrder(at100);
        assertRankOrder(at300);
    }

    @Test
    @DisplayName("11. 후보 0개소 — 예외가 아니라 빈 목록 (어떤 주차장보다 큰 하한 · 실제로 후보가 없는 동)")
    void emdWithoutCandidateReturnsEmptyList() {
        // 🔴 데이터에 기대지 않는 구성 — 전 주차장 최대 면수보다 큰 하한이면 어느 동이든 후보가 없다
        BigDecimal overMax = BigDecimal.valueOf(data.maxPkltCpcty() + 1);
        String any = data.emdCds().get(0);
        assertThat(mapper.selectLotRanking(param(any, overMax, "WIDE")))
                .as("후보가 없는 동은 예외가 아니라 빈 목록이다")
                .isEmpty();

        // 기본 하한에서 실제로 후보가 없는 동이 있으면 그것도 본다
        Map<String, Cand> narrow = data.candByEmd(MIN_20, false);
        data.emdCds().stream().filter(c -> !narrow.containsKey(c)).findFirst().ifPresent(none ->
                assertThat(mapper.selectLotRanking(param(none, MIN_20, "NARROW")))
                        .as("%s — NARROW·하한 20면 후보가 없는 동", none).isEmpty());
    }

    /**
     * 🔴 <b>유형 필터가 실제로 결과를 바꾼다</b> — {@code convertibleLot} 조각의 OGNL 분기가
     * {@code selectLotRanking} 에서도 타는지 확인한다({@link #wideWidensAbleCpcty()} 와 같은 함정).
     */
    @Test
    @DisplayName("12. 🔴 유형 필터 — NARROW·WIDE 각각 후보 개소·면수·배정 합이 독립 재계산과 같고 WIDE 가 더 많다")
    void trgtTypeSeChangesLotList() {
        Map<String, Cand> narrowCand = data.candByEmd(MIN_20, false);
        Map<String, Cand> wideCand = data.candByEmd(MIN_20, true);
        Predicate<String> wider =
                c -> wideCand.getOrDefault(c, Cand.NONE).cnt() > narrowCand.getOrDefault(c, Cand.NONE).cnt();
        // 고른 동이 조건에 맞으면 그 동을 쓴다(민원이 많아 배정 합 대조가 0 = 0 으로 끝나지 않는다)
        String e = emd != null && wider.test(emd) ? emd
                : pickEmd(wider, "WIDE 에서 후보가 늘어나는");

        List<ZoneRcmdtnLotVO> narrow = mapper.selectLotRanking(param(e, MIN_20, "NARROW"));
        List<ZoneRcmdtnLotVO> wide = mapper.selectLotRanking(param(e, MIN_20, "WIDE"));

        Cand n = narrowCand.getOrDefault(e, Cand.NONE);
        assertThat(narrow).hasSize((int) n.cnt());
        assertThat(cpctySum(narrow)).isEqualTo(n.cpcty());
        assertThat(asgnSum(narrow)).isEqualTo(data.asgnTotal(Src.CV, e, cv, MIN_20, false, RNG_300));

        assertThat(wide).as("WIDE 인데 NARROW 개소 수(%d)면 <when> 이 안 탄 것이다", n.cnt())
                .hasSize((int) wideCand.get(e).cnt())
                .hasSizeGreaterThan(narrow.size());
        assertThat(cpctySum(wide)).isEqualTo(wideCand.get(e).cpcty());
        // 🔴 후보가 늘면 배정 대상도 늘어난다(줄지 않는다)
        assertThat(asgnSum(wide)).isEqualTo(data.asgnTotal(Src.CV, e, cv, MIN_20, true, RNG_300))
                .isGreaterThanOrEqualTo(asgnSum(narrow));
        // 🔴 그래도 중복은 없다
        assertThat(asgnSum(wide)).isLessThanOrEqualTo(data.cvCoordCount(e, cv));
    }

    /**
     * 🔴 유형 범위가 "후보가 아예 없는 동" 의 수까지 바꾼다 — {@code NARROW} 에서 후보 0개소인 동마다
     * {@code WIDE} 후보 개소가 독립 재계산과 같다(0개소로 남는 동은 0, 구제되는 동은 그 개소 수).
     */
    @Test
    @DisplayName("13. 🔴 NARROW 후보 0개소인 동 전부 — NARROW 는 빈 목록 · WIDE 개소 수는 독립 재계산과 같다")
    void wideRescuesZeroCandidateEmds() {
        Map<String, Cand> narrow = data.candByEmd(MIN_20, false);
        Map<String, Cand> wide = data.candByEmd(MIN_20, true);
        List<String> zeroNarrow = data.emdCds().stream().filter(c -> !narrow.containsKey(c)).toList();
        assumeTrue(!zeroNarrow.isEmpty(), "NARROW·하한 20면 후보가 0개소인 읍면동이 없다");

        for (String e : zeroNarrow) {
            assertThat(mapper.selectLotRanking(param(e, MIN_20, "NARROW")))
                    .as("%s — NARROW 에서는 후보 0개소다", e)
                    .isEmpty();
            assertThat(mapper.selectLotRanking(param(e, MIN_20, "WIDE")))
                    .as("%s — WIDE 후보 개소", e)
                    .hasSize((int) wide.getOrDefault(e, Cand.NONE).cnt());
        }
    }

    /**
     * 성능 — 기준은 "읍면동 1개 기준 1초 미만" 이다.
     *
     * <p>🔴 측정 전에 한 번 호출해 둔다(JIT·커넥션·플랜 캐시 워밍). 첫 호출을 그대로 재면
     * 스프링 컨텍스트 기동 직후의 비용이 섞여 쿼리 자체의 비용이 아니다.
     */
    @Test
    @DisplayName("14. 성능 — 고른 동(후보 좌표가 충분한 동 중 민원 최다) 1개 조회가 1초 미만")
    void lotRankingFinishesUnderOneSecond() {
        String e = targetEmd();
        mapper.selectLotRanking(param(e, MIN_20, "NARROW"));   // 워밍

        long nano = System.nanoTime();
        List<ZoneRcmdtnLotVO> lots = mapper.selectLotRanking(param(e, MIN_20, "NARROW"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - nano);

        assertThat(lots).isNotEmpty();
        assertThat(elapsed).as("%s 1개 동 조회 실측 %dms", e, elapsed.toMillis())
                .isLessThan(Duration.ofSeconds(1));
    }

    // =====================================================================================
    // 블록별 배정 집계 (최근 블록 · 이전 블록 · 월별 추이)
    // 블록이 '최근 3개월 / 직전 3개월' 도, '완전한 달 n/2' 도 아니라 '선택 창의 절반
    //    일수' 다. 매퍼는 ocrn_dt 날짜 반열림으로 센다.
    // =====================================================================================

    /** 추세 행을 좌표 단위로 묶은 건수(0 은 뺀다) — {@code ym} 을 주면 그 달만 */
    private static Map<String, Long> trendByLoc(List<ZoneRcmdtnTrendCountVO> trend, List<ZoneRcmdtnLotVO> lots,
                                                String ym, ToLongFunction<ZoneRcmdtnTrendCountVO> cnt) {
        Map<Long, String> locOf = new LinkedHashMap<>();
        for (ZoneRcmdtnLotVO l : lots) {
            locOf.put(l.getPkltId(), ZoneRcmdtnTestData.locKey(l));
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (ZoneRcmdtnTrendCountVO r : trend) {
            long v = cnt.applyAsLong(r);
            if (v != 0L && (ym == null || ym.equals(r.getYm()))) {
                assertThat(locOf).as("추세 행의 주차장은 후보 목록 안에 있다").containsKey(r.getPkltId());
                out.merge(locOf.get(r.getPkltId()), v, Long::sum);
            }
        }
        return out;
    }

    /**
     * 🔴 <b>{@code selectLotRanking} 은 추세 블록을 내지 않는다</b>. 1차 "이전" 블록이
     * <b>민원 창 밖</b>이라 이 쿼리의 스캔 범위로는 셀 수 없고, 범위를 넓히면 <b>정렬 기준인
     * {@code asgnCvlcptCnt} 가 커진다</b>(15-2 · 아래 테스트 17 이 실증한다).
     *
     * <p>그래서 블록 두 칸은 {@code selectLotTrend} + {@code applyTrend} 가 채운다. 이 쿼리가 지는
     * 것은 <b>전 구간 집계와 정렬</b>이다 — 🔴 전 구간 건수가 <b>민원 창만</b> 센 독립 재계산과 같다
     * (다르면 추세가 아니라 민원 창을 건드린 것이다).
     */
    @Test
    @DisplayName("15. 🔴 selectLotRanking — 전 구간 건수는 민원 창만 센 값 · 🔁 추세 4칸은 내지 않는다")
    void lotRankingCarriesOnlyWholeWindowCount() {
        String e = targetEmd();
        List<ZoneRcmdtnLotVO> lots = mapper.selectLotRanking(param(e, MIN_20, "NARROW"));

        assertThat(ZoneRcmdtnTestData.byLoc(lots, ZoneRcmdtnLotVO::getAsgnCvlcptCnt))
                .as("🔴 전 구간 집계 = 민원 창의 최근접 배정").isEqualTo(
                        data.asgnByLoc(Src.CV, e, cv, MIN_20, false, RNG_300));
        assertRankOrder(lots);

        // 추세 4칸(블록 건수 2 · 증가율 · 월별)은 전부 이 쿼리 밖이다
        assertThat(lots).isNotEmpty().allSatisfy(l -> {
            assertThat(l.getAsgnCvlcptCntRcnt()).as("%s — 최근 블록은 selectLotTrend 의 몫이다",
                    l.getPkltNm()).isNull();
            assertThat(l.getAsgnCvlcptCntBfr()).isNull();
            assertThat(l.getCvlcptIncrsRt()).as("cvlcptIncrsRt 는 매퍼가 채우지 않는다").isNull();
            assertThat(l.getTrendMm()).as("trendMm 은 매퍼가 채우지 않는다").isNull();
        });
    }

    /**
     * 🔴 <b>{@code selectLotTrend} 가 네 칸을 한 번에 낸다</b> — 1차(최근/이전)와 폴백(최근/이전)
     * 폴백 판정이 조회 결과(1차 '이전' 블록의 읍면동 합)에 달려 있어 쿼리를 두 번
     * 쏠 수 없다.
     *
     * <p>🔴 <b>민원 창이 자료 시작일에서 시작하므로 1차 '이전' 은 반드시 0건이다</b>(그 앞에는 자료가
     * 없다) — 서비스가 폴백으로 내려앉는 바로 그 모양이다. 폴백 2칸은 반 분할 블록을 따로 센 독립
     * 재계산과 같아야 하고, 월별 칸도 "그 달 ∩ 블록" 을 따로 센 값과 같아야 한다(양 끝은 부분 달이다).
     *
     * <p>🔴 그리고 <b>배정 0건인 달은 행이 없다</b> — 빈 달을 0 으로 채우는 것은 서비스의 몫이고,
     * 그 근거가 "모든 행은 스캔 범위 안에 배정이 1건 이상 있다" 는 단언이다.
     */
    @Test
    @DisplayName("16. 🔴 selectLotTrend 네 칸 — 1차 이전은 0(자료 시작 전) · 1차 최근 = 전 구간 · 폴백 2칸·월별 = 독립 재계산")
    void lotTrendCountsFourBlocksAtOnce() {
        String e = targetEmd();
        assumeTrue(cv.trendOk(), "민원 보유 구간이 14일 미만이라 추세 블록을 만들 수 없다");
        ZoneRcmdtnQueryParam p = param(e, MIN_20, "NARROW");
        p.setTrendScan(true);   // 🔴 서비스가 이 쿼리에만 켠다 — 1차 '이전' 이 민원 창 밖이다
        List<ZoneRcmdtnLotVO> lots = mapper.selectLotRanking(param(e, MIN_20, "NARROW"));
        List<ZoneRcmdtnTrendCountVO> trend = mapper.selectLotTrend(p);

        assumeTrue(asgnSum(lots) > 0, "고른 읍면동에 반경 안 민원이 한 건도 없다");
        assertThat(trend).isNotEmpty();
        // 🔴 폴백 사유 — 1차 '이전' 블록은 자료 시작일 이전이라 읍면동 전체에서 0건이다
        assertThat(trend.stream().mapToLong(ZoneRcmdtnTrendCountVO::getCntBfr).sum())
                .as("🔴 %s ~ %s 에는 민원이 없다(자료 시작 전) — 그래서 반 분할로 내려앉는다",
                        cv.bfr().bgng(), cv.bfr().end()).isZero();
        // 1차 '최근' 은 선택 기간 전체라 전 구간 집계와 같다
        for (ZoneRcmdtnLotVO l : lots) {
            assertThat(rows(trend, l).stream().mapToLong(ZoneRcmdtnTrendCountVO::getCntRcnt).sum())
                    .as("%s — 1차 최근 = 선택 기간 전체 = asgnCvlcptCnt", l.getPkltNm())
                    .isEqualTo(l.getAsgnCvlcptCnt());
        }

        // 🔴 폴백 2칸 = 반 분할 블록의 독립 재계산 — 다르면 폴백 구현이 틀렸다
        Win splitRcnt = cv.splitRcnt();
        Win splitBfr = cv.splitBfr();
        assertThat(trendByLoc(trend, lots, null, ZoneRcmdtnTrendCountVO::getCntRcntSplit))
                .as("폴백 최근 블록 %s ~ %s", splitRcnt.bgng(), splitRcnt.end())
                .isEqualTo(data.asgnByLoc(Src.CV, e, splitRcnt, MIN_20, false, RNG_300));
        assertThat(trendByLoc(trend, lots, null, ZoneRcmdtnTrendCountVO::getCntBfrSplit))
                .as("폴백 이전 블록 %s ~ %s", splitBfr.bgng(), splitBfr.end())
                .isEqualTo(data.asgnByLoc(Src.CV, e, splitBfr, MIN_20, false, RNG_300));

        // 🔴 폴백 두 블록은 겹치지 않고 민원 창 안에 있다 — 합이 전 구간 집계를 넘을 수 없다
        for (ZoneRcmdtnLotVO l : lots) {
            List<ZoneRcmdtnTrendCountVO> mine = rows(trend, l);
            assertThat(mine.stream().mapToLong(r -> r.getCntRcntSplit() + r.getCntBfrSplit()).sum())
                    .as("%s — 폴백 최근 + 이전 ≤ 전 구간", l.getPkltNm())
                    .isLessThanOrEqualTo(l.getAsgnCvlcptCnt());
        }

        // 월별 칸 — 폴백 최근 블록이 걸친 달마다 "그 달 ∩ 블록" 의 독립 재계산과 같다(양 끝은 부분 달)
        for (String ym : splitRcnt.yms()) {
            assertThat(trendByLoc(trend, lots, ym, ZoneRcmdtnTrendCountVO::getCntRcntSplit))
                    .as("%s 의 폴백 최근 블록 건수", ym)
                    .isEqualTo(data.asgnByLoc(Src.CV, e, splitRcnt.within(ym), MIN_20, false, RNG_300));
        }
        assertThat(trend).filteredOn(r -> r.getCntRcntSplit() > 0)
                .extracting(ZoneRcmdtnTrendCountVO::getYm)
                .as("폴백 최근 블록 건수는 그 블록이 걸친 달에만 있다").isSubsetOf(splitRcnt.yms());

        // 🔴 배정이 없는 달은 행이 없다 — 빈 달을 0 으로 채우는 것은 서비스의 몫이다.
        //    (종전 단언 "cntRcnt + cntBfr > 0" 은 GROUP BY 결과라 항상 참이었다 — 행이 '있어야 할 칸' 에만
        //     있는지를 보지 못했다.) 지금은 민원 창이 걸친 달마다 1차 최근 건수를 독립 재계산과 맞추고,
        //     행이 있는 (좌표 · 달) 칸의 집합이 "독립 재계산이 1건 이상인 칸" 과 정확히 같은지 본다 —
        //     0 으로 채운 행이 끼어도, 배정이 있는 달의 행이 빠져도 깨진다.
        Map<Long, String> locOf = new LinkedHashMap<>();
        lots.forEach(l -> locOf.put(l.getPkltId(), ZoneRcmdtnTestData.locKey(l)));
        Set<String> expCells = new TreeSet<>();
        for (String ym : cv.yms()) {
            Map<String, Long> exp = data.asgnByLoc(Src.CV, e, cv.within(ym), MIN_20, false, RNG_300);
            assertThat(trendByLoc(trend, lots, ym, ZoneRcmdtnTrendCountVO::getCntRcnt))
                    .as("%s 의 1차 최근 건수 = 그 달 ∩ 민원 창의 독립 재계산", ym).isEqualTo(exp);
            exp.keySet().forEach(loc -> expCells.add(loc + " @ " + ym));
        }
        Set<String> cells = new TreeSet<>();
        trend.forEach(r -> cells.add(locOf.get(r.getPkltId()) + " @ " + r.getYm()));
        assertThat(cells)
                .as("🔴 행이 있는 (좌표 · 달) 칸 = 배정이 1건 이상인 칸 — 빈 달은 행이 없고, 있는 달은 빠지지 않는다")
                .isEqualTo(expCells);
        assertThat(trend).extracting(r -> r.getPkltId() + " @ " + r.getYm())
                .as("(주차장 · 달) 은 한 행이다").doesNotHaveDuplicates();
    }

    /**
     * 🔴 <b>15-2 의 함정 그 자체</b> — {@code cv} 스캔 범위를 넓히면 {@code selectLotRanking} 의
     * {@code asgnCvlcptCnt} 가 <b>실제로 커진다</b>. 그 값이 <b>정렬 기준</b>이라 순위가 통째로
     * 바뀐다. 그래서 서비스는 {@code trendScan} 을 <b>{@code selectLotRanking} 이 끝난 뒤에만</b>
     * 켠다(공용 조각은 범위만 파라미터로 받는다).
     *
     * <p>자료 시작일에서 출발하는 창으로는 드러나지 않는다 — 1차 '이전' 에 민원이 없어 넓혀도 숫자가
     * 같다. 🔴 그래서 <b>자료 종료일에서 거꾸로 잡은 창</b>(길이 = 보유 일수의 절반 이하)으로 잰다 —
     * 1차 '이전' 이 보유 구간 안이라 넓힌 만큼 건수가 더해진다.
     *
     * <p>⚠️ 함께 <b>스캔량 2배의 비용</b>도 잰다(15-2 ⚠️).
     */
    @Test
    @DisplayName("17. 🔴 scanFrom 분리 — 넓히면 asgnCvlcptCnt 에 1차 이전 블록이 더해진다(그래서 ranking 은 좁게 쓴다)")
    void wideningScanWouldInflateRankingCount() {
        String e = targetEmd();
        assumeTrue(cvRange.days() >= 4 * ZoneRcmdtnTestData.MIN_TREND_DAYS,
                "민원 보유 구간이 28일 미만이라 '직전 동일 기간' 이 보유 구간 안에 드는 창을 만들 수 없다");
        // D = 보유 일수의 절반(최대 31일) — 1차 '이전' 블록이 통째로 보유 구간 안이다
        Win tail = ZoneRcmdtnTestData.tail(cvRange, Math.min(cvRange.days() / 2, 31));
        Win bfr = tail.bfr();
        ZoneRcmdtnQueryParam narrowScan = param(e, tail, MIN_20, "NARROW", RNG_300);
        ZoneRcmdtnQueryParam wideScan = param(e, tail, MIN_20, "NARROW", RNG_300);
        wideScan.setTrendScan(true);

        assertThat(narrowScan.getScanFrom()).as("🔴 기본은 민원 창이다").isEqualTo(tail.from());
        assertThat(wideScan.getScanFrom()).as("🔴 켜면 1차 이전 블록까지 넓어진다").isEqualTo(bfr.from());

        Map<String, Long> inTail = data.asgnByLoc(Src.CV, e, tail, MIN_20, false, RNG_300);
        Map<String, Long> inBfr = data.asgnByLoc(Src.CV, e, bfr, MIN_20, false, RNG_300);
        Map<String, Long> inBoth = data.asgnByLoc(Src.CV, e, new Win(bfr.bgng(), tail.end()), MIN_20, false, RNG_300);

        List<ZoneRcmdtnLotVO> kept = mapper.selectLotRanking(narrowScan);
        List<ZoneRcmdtnLotVO> inflated = mapper.selectLotRanking(wideScan);
        assertThat(ZoneRcmdtnTestData.byLoc(kept, ZoneRcmdtnLotVO::getAsgnCvlcptCnt))
                .as("🔴 종전 범위 — 선택 기간(%d일)의 배정 민원", tail.days()).isEqualTo(inTail);
        assertThat(ZoneRcmdtnTestData.byLoc(inflated, ZoneRcmdtnLotVO::getAsgnCvlcptCnt))
                .as("🔴 넓히면 1차 이전 블록까지 섞여 정렬 기준이 커진다").isEqualTo(inBoth);

        // 추세 쿼리는 넓힌 범위로 1차 두 칸을 따로 센다 — '이전' 이 민원 창 밖이어도 세어진다
        List<ZoneRcmdtnTrendCountVO> trend = mapper.selectLotTrend(wideScan);
        assertThat(trendByLoc(trend, kept, null, ZoneRcmdtnTrendCountVO::getCntRcnt)).isEqualTo(inTail);
        assertThat(trendByLoc(trend, kept, null, ZoneRcmdtnTrendCountVO::getCntBfr))
                .as("🔴 1차 이전 블록 %s ~ %s (민원 창 밖)", bfr.bgng(), bfr.end()).isEqualTo(inBfr);

        // ⚠️ 비용 — 넓힌 범위로 추세 4칸을 내는 데 걸리는 시간(가장 긴 창 = 스캔량 2배)
        ZoneRcmdtnQueryParam base = param(e, MIN_20, "NARROW");
        base.setTrendScan(true);
        mapper.selectLotTrend(base);   // 워밍
        long nano = System.nanoTime();
        mapper.selectLotTrend(base);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - nano);
        assertThat(elapsed).as("🔴 가장 긴 창(%d일)의 추세 조회 실측 %dms", cv.days(), elapsed.toMillis())
                .isLessThan(Duration.ofSeconds(2));

        // 🔴 "실제로 커진다" — 1차 이전 블록에 배정이 있어야 보인다
        assumeTrue(ZoneRcmdtnTestData.sum(inBfr) > 0,
                "고른 읍면동의 1차 이전 블록(" + bfr.bgng() + " ~ " + bfr.end() + ")에 반경 안 민원이 없다");
        assertThat(asgnSum(inflated)).as("🔴 넓힌 스캔의 배정 합 = 좁은 스캔 + 1차 이전 블록")
                .isEqualTo(asgnSum(kept) + ZoneRcmdtnTestData.sum(inBfr))
                .isGreaterThan(asgnSum(kept));
    }

    // =====================================================================================
    // 🔴 알려진 검증 공백 (클래스 머리 주석) — 데이터가 그 조건을 가르는 순간 켜진다
    // 독립 SQL 의 후보 조건을 거치지 않고 "그 행이 결과에 없다" 를 직접 본다.
    // =====================================================================================

    /**
     * 후보 조건 하나만 어긴 주차장이 있는 읍면동마다 — 그 주차장이 목록에 없고, 목록 개소·가능 면수가
     * 독립 SQL 의 후보 집계와 같다. 🔴 {@code WIDE}·하한 0 으로 부른다(유형·면수로 먼저 걸러지면
     * 보려던 조건이 다시 가려진다).
     */
    private void assertNearCandidatesExcluded(Near which, String skipRsn) {
        Map<String, List<Long>> near = data.nearCandidates(which);
        assumeTrue(!near.isEmpty(), skipRsn);
        Map<String, Cand> wide0 = data.candByEmd(MIN_0, true);
        near.forEach((e, ids) -> {
            List<ZoneRcmdtnLotVO> lots = mapper.selectLotRanking(param(e, MIN_0, "WIDE"));
            Cand cand = wide0.getOrDefault(e, Cand.NONE);
            assertThat(lots).extracting(ZoneRcmdtnLotVO::getPkltId)
                    .as("%s — %s 주차장 %s 은 후보 목록에 없어야 한다", e, which, ids)
                    .doesNotContainAnyElementsOf(ids);
            assertThat(lots).as("%s — 후보 개소", e).hasSize((int) cand.cnt());
            assertThat(baseline(e, MIN_0, "WIDE").getAbleCpcty()).as("%s — 가능 면수에도 들지 않는다", e)
                    .usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.valueOf(cand.cpcty()));
        });
    }

    @Test
    @DisplayName("18. 검증 공백(민영 제외) — 후보 유형·무료인 민영 주차장은 후보 목록·가능 면수에 없다")
    void gapPrivateLotIsNeverCandidate() {
        assertNearCandidatesExcluded(Near.PRIVATE,
                "검증 공백 — 후보 유형(공영·노외·복층화·노상·공한지)이면서 무료·미소멸인 '민영' 주차장이 없다"
                        + "(민영이 전부 유형 '민영' 이라 유형 필터가 먼저 거른다) → 민영 제외 조건을 가를 데이터가 없다");
    }

    @Test
    @DisplayName("19. 검증 공백(소멸 제외) — 소멸(extsh_dt) 주차장은 후보 목록·가능 면수에 없다")
    void gapExtinctLotIsNeverCandidate() {
        assertNearCandidatesExcluded(Near.EXTINCT,
                "검증 공백 — 소멸일시(extsh_dt)가 있는 후보 유형·무료·비민영 주차장이 없다"
                        + " → 소멸 제외 조건을 가를 데이터가 없다");
    }

    @Test
    @DisplayName("20. 검증 공백(유형 99 제외) — emd_cd 를 단 유형 99 민원은 민원 월평균에 세지 않는다")
    void gapType99ComplaintIsNotCounted() {
        String e = data.emdWithType99(cv);
        assumeTrue(e != null, "검증 공백 — 민원 창 안에 emd_cd 가 있는 유형 99 민원이 없다"
                + "(유형 99 는 전부 emd_cd NULL 이라 읍면동 조건이 먼저 거른다) → 유형 99 제외 조건을 가를 데이터가 없다");
        long parking = data.cvCountByEmd(cv).getOrDefault(e, 0L);
        long all = data.cvCountAllTypes(e, cv);
        assertThat(all).as("탐침이 고른 동에는 유형 99 가 있다").isGreaterThan(parking);

        assertThat(baseline(e, MIN_20, "NARROW").getCvlcptMmAvg())
                .as("%s 민원 월평균 — 유형 99 를 뺀 %d건 기준이다(전 유형 %d건이 아니다)", e, parking, all)
                .isCloseTo(avg(parking, cv.months()), within(EPS))
                .isNotCloseTo(avg(all, cv.months()), within(EPS));
        // 유형 99 가 좌표까지 갖고 있으면 주차장 배정에도 들면 안 된다 — 배정 합의 상한이 주차 민원 수다
        assertThat(asgnSum(mapper.selectLotRanking(param(e, MIN_0, "WIDE"))))
                .as("%s 배정 합 ≤ 유형 99 를 뺀 좌표 보유 민원 수", e)
                .isLessThanOrEqualTo(data.cvCoordCount(e, cv));
    }

    /**
     * ⚠️ 이 테스트가 켜져도 <b>{@code lat IS NOT NULL} 가드가 있다는 증명은 아니다</b>(머리 주석 4) —
     * 좌표가 없으면 {@code geom} 이 {@code NULL} 이라 가드 없이도 배정되지 않는다. 보는 것은 결과다:
     * 좌표 없는 행이 쿼리를 깨지 않고, 배정 합이 좌표 보유 건수를 넘지 않으며, 월평균(분모는 좌표와
     * 무관하다)에는 그대로 세어진다.
     */
    @Test
    @DisplayName("21. 검증 공백(좌표 없는 민원·단속) — 배정에는 들지 않고 월평균에는 그대로 세어진다")
    void gapRowsWithoutCoordinateAreNotAssigned() {
        String cvEmd = data.emdWithCvNoCoord(cv);
        String crdnEmd = data.emdWithCrdnNoCoord(crdn);
        assumeTrue(cvEmd != null || crdnEmd != null,
                "검증 공백 — emd_cd 는 있는데 좌표(lat)가 없는 주차 민원·단속이 없다"
                        + "(좌표 없는 행은 전부 emd_cd 도 NULL 이다) → 좌표 없는 행의 처리를 볼 데이터가 없다");
        if (cvEmd != null) {
            long coord = data.cvCoordCount(cvEmd, cv);
            long tot = data.cvCountByEmd(cv).getOrDefault(cvEmd, 0L);
            assertThat(tot).as("탐침이 고른 동에는 좌표 없는 민원이 있다").isGreaterThan(coord);
            assertThat(asgnSum(mapper.selectLotRanking(param(cvEmd, MIN_0, "WIDE"))))
                    .as("%s 민원 배정 합 = 독립 재계산 ≤ 좌표 보유 %d건 (총 %d건)", cvEmd, coord, tot)
                    .isEqualTo(data.asgnTotal(Src.CV, cvEmd, cv, MIN_0, true, RNG_300))
                    .isLessThanOrEqualTo(coord);
            assertThat(baseline(cvEmd, MIN_20, "NARROW").getCvlcptMmAvg())
                    .as("%s 민원 월평균은 좌표와 무관하게 전 건을 센다", cvEmd)
                    .isCloseTo(avg(tot, cv.months()), within(EPS));
        }
        if (crdnEmd != null) {
            long coord = data.crdnCoordCount(crdnEmd, crdn);
            long tot = data.crdnCountByEmd(crdn).getOrDefault(crdnEmd, 0L);
            assertThat(tot).as("탐침이 고른 동에는 좌표 없는 단속이 있다").isGreaterThan(coord);
            long asgn = mapper.selectLotRanking(param(crdnEmd, MIN_0, "WIDE")).stream()
                    .mapToLong(ZoneRcmdtnLotVO::getAsgnCrdnCnt).sum();
            assertThat(asgn).as("%s 단속 배정 합 = 독립 재계산 ≤ 좌표 보유 %d건 (총 %d건)", crdnEmd, coord, tot)
                    .isEqualTo(data.asgnTotal(Src.CRDN, crdnEmd, crdn, MIN_0, true, RNG_300))
                    .isLessThanOrEqualTo(coord);
            assertThat(baseline(crdnEmd, MIN_20, "NARROW").getCrdnMmAvg())
                    .as("%s 단속 월평균은 좌표와 무관하게 전 건을 센다", crdnEmd)
                    .isCloseTo(avg(tot, crdn.months()), within(EPS));
        }
    }

    // ───────────────────────── 후보 밖 주차장(지도 회색 핀) ─────────────────────────

    /** 시군구 모드 표본 — 행정구역 마스터의 시군구명(데이터가 아니라 마스터 상수다) */
    private static final String SGG = "제주시";

    /** 제외 사유 코드 4종 — 응답 계약이다 */
    private static final Set<String> EXCL_RSN = Set.of("PAID", "CPCTY_LACK", "PRVT", "TYPE_EXCL");

    private ZoneRcmdtnQueryParam sggParam(String sggNm, BigDecimal minCpcty, String trgtTypeSe) {
        ZoneRcmdtnQueryParam p = param(null, minCpcty, trgtTypeSe);
        p.setSggNm(sggNm);
        return p;
    }

    /**
     * 범위 안 <b>살아 있는 좌표 보유</b> 주차장 수 — 매퍼와 다른 경로(행정구역 JOIN)로 센다.
     * 후보 조건을 하나도 쓰지 않으므로 후보 + 후보 밖의 합 기대값이 된다.
     */
    private long aliveLotCount(String emdCd, String sggNm) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_pblpklt p"
                + " JOIN parking.tb_admdst a ON a.emd_cd = p.emd_cd"
                + " WHERE (CAST(? AS varchar) IS NULL OR p.emd_cd = ?) AND (CAST(? AS varchar) IS NULL OR a.sgg_nm = ?)"
                + " AND p.extsh_dt IS NULL AND p.lat IS NOT NULL AND p.lot IS NOT NULL",
                Long.class, emdCd, emdCd, sggNm, sggNm);
    }

    private static Set<Long> lotIds(List<ZoneRcmdtnLotVO> lots) {
        Set<Long> out = new TreeSet<>();
        lots.forEach(l -> out.add(l.getPkltId()));
        return out;
    }

    private static Map<Long, ZoneRcmdtnExclLotVO> exclById(List<ZoneRcmdtnExclLotVO> excl) {
        Map<Long, ZoneRcmdtnExclLotVO> out = new LinkedHashMap<>();
        excl.forEach(x -> assertThat(out.put(x.getPkltId(), x)).as("후보 밖 %d 중복", x.getPkltId()).isNull());
        return out;
    }

    /**
     * 픽스처 1행 — 그 읍면동의 기존 주차장 좌표를 빌려 넣는다. 🔴 이 클래스는 {@code @Transactional}
     * ({@code MOCK})이라 테스트가 끝나면 <b>롤백으로 사라진다</b>(남는 행이 없다). 넣는 행은 후보가 아니므로
     * 배정 결과에 영향이 없다.
     */
    private long insertLot(String emdCd, Integer cpcty, String typeNm, String crgYn, String ownshpNm) {
        Map<String, Object> at = jdbcTemplate.queryForMap(
                "SELECT lat, lot FROM parking.tb_pblpklt WHERE emd_cd = ? ORDER BY pklt_id LIMIT 1", emdCd);
        return jdbcTemplate.queryForObject("INSERT INTO parking.tb_pblpklt"
                + " (pklt_nm, emd_cd, lat, lot, pklt_cpcty, pklt_type_nm, prk_crg_yn, ownshp_type_nm)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING pklt_id", Long.class,
                "TESTDATA 테스트 주차장", emdCd, at.get("lat"), at.get("lot"), cpcty, typeNm, crgYn, ownshpNm);
    }

    @Test
    @DisplayName("① 후보 밖 ∩ 후보 = ∅ · 합 = 범위 내 살아 있는 좌표 보유 주차장 수 (읍면동·시군구 · NARROW·WIDE)")
    void exclLotsAreDisjointFromCandidates() {
        String e = targetEmd();
        for (String type : List.of("NARROW", "WIDE")) {
            for (ZoneRcmdtnQueryParam p : List.of(param(e, MIN_20, type), sggParam(SGG, MIN_20, type))) {
                String scope = p.getEmdCd() != null ? p.getEmdCd() : p.getSggNm();
                Set<Long> cand = lotIds(mapper.selectLotRanking(p));
                List<ZoneRcmdtnExclLotVO> excl = mapper.selectExclLots(p);
                Set<Long> exclIds = exclById(excl).keySet();

                assertThat(exclIds).as("%s·%s — 후보와 후보 밖이 겹친다", scope, type).doesNotContainAnyElementsOf(cand);
                assertThat((long) cand.size() + exclIds.size())
                        .as("%s·%s — 후보 %d + 후보 밖 %d = 범위 내 전체(독립 SQL)", scope, type, cand.size(), exclIds.size())
                        .isEqualTo(aliveLotCount(p.getEmdCd(), p.getSggNm()));
                assertThat(excl).as("%s·%s — 사유는 4종 중 하나 · 좌표 있음", scope, type).allSatisfy(x -> {
                    assertThat(x.getExclRsnCd()).isIn(EXCL_RSN);
                    assertThat(x.getLat()).isNotNull();
                    assertThat(x.getLot()).isNotNull();
                });
            }
        }
    }

    @Test
    @DisplayName("② 면수 NULL 주차장은 후보에 없고 후보 밖에 CPCTY_LACK 으로 있다 (🔴 NOT(NULL) 로 빠지지 않는다)")
    void nullCpctyLotIsExcludedAsCpctyLack() {
        String e = targetEmd();
        // 🔴 로컬 데이터에 의존하지 않게 무료·공영·민영 아님·면수 NULL 1행을 넣는다(롤백으로 사라진다).
        //    면수만 빼면 후보가 됐을 행이라, NOT (convertibleLot) 이 NULL 로 접히지 않으면 양쪽에서 다 사라진다.
        long id = insertLot(e, null, "공영", "N", null);
        // 기존 데이터의 면수 NULL 행도 함께 본다(있으면)
        List<Long> nullIds = jdbcTemplate.queryForList("SELECT pklt_id FROM parking.tb_pblpklt"
                + " WHERE emd_cd = ? AND pklt_cpcty IS NULL AND extsh_dt IS NULL", Long.class, e);
        assertThat(nullIds).as("전제 — 넣은 행이 보인다").contains(id);

        for (String type : List.of("NARROW", "WIDE")) {
            ZoneRcmdtnQueryParam p = param(e, MIN_20, type);
            Set<Long> cand = lotIds(mapper.selectLotRanking(p));
            Map<Long, ZoneRcmdtnExclLotVO> excl = exclById(mapper.selectExclLots(p));
            for (Long nullId : nullIds) {
                assertThat(cand).as("%s — 면수 NULL %d 는 후보가 아니다", type, nullId).doesNotContain(nullId);
                ZoneRcmdtnExclLotVO x = excl.get(nullId);
                assertThat(x).as("%s — 면수 NULL %d 가 후보 밖에서도 빠졌다(NOT(NULL))", type, nullId).isNotNull();
                assertThat(x.getPkltCpcty()).as("면수 null 그대로 내린다").isNull();
                if (!jdbcTemplate.queryForObject("SELECT COALESCE(prk_crg_yn,'N') = 'Y' FROM parking.tb_pblpklt"
                        + " WHERE pklt_id = ?", Boolean.class, nullId)) {
                    assertThat(x.getExclRsnCd()).as("%s — 무료 면수 NULL %d 의 사유", type, nullId).isEqualTo("CPCTY_LACK");
                }
            }
        }
    }

    @Test
    @DisplayName("③ 사유는 하나만 · 우선순위 PAID > CPCTY_LACK > PRVT > TYPE_EXCL")
    void exclRsnFollowsPriority() {
        String e = targetEmd();
        // 유료 + 면수 미달 + 민영 + 유형 밖 — 네 사유에 모두 걸린다 → PAID
        long paid = insertLot(e, 5, "민영", "Y", "민영");
        // 무료 + 면수 미달 + 민영 + 유형 밖 → CPCTY_LACK
        long cpcty = insertLot(e, 5, "민영", "N", "민영");
        // 무료 + 면수 충분 + 민영 + 후보 유형 → PRVT
        long prvt = insertLot(e, 100, "공영", "N", "민영");
        // 무료 + 면수 충분 + 민영 아님 + 유형 밖 → TYPE_EXCL
        long type = insertLot(e, 100, "민영", "N", null);

        Map<Long, ZoneRcmdtnExclLotVO> excl = exclById(mapper.selectExclLots(param(e, MIN_20, "WIDE")));
        assertThat(excl.get(paid)).isNotNull().extracting(ZoneRcmdtnExclLotVO::getExclRsnCd).isEqualTo("PAID");
        assertThat(excl.get(cpcty)).isNotNull().extracting(ZoneRcmdtnExclLotVO::getExclRsnCd).isEqualTo("CPCTY_LACK");
        assertThat(excl.get(prvt)).isNotNull().extracting(ZoneRcmdtnExclLotVO::getExclRsnCd).isEqualTo("PRVT");
        assertThat(excl.get(type)).isNotNull().extracting(ZoneRcmdtnExclLotVO::getExclRsnCd).isEqualTo("TYPE_EXCL");

        // 실데이터 전 행 — 유료면 반드시 PAID(독립 SQL 로 유료 여부를 다시 읽는다)
        Set<Long> paidIds = new TreeSet<>(jdbcTemplate.queryForList("SELECT pklt_id FROM parking.tb_pblpklt"
                + " WHERE emd_cd = ? AND prk_crg_yn = 'Y' AND extsh_dt IS NULL", Long.class, e));
        assertThat(excl.keySet()).as("유료 주차장은 전부 후보 밖이다").containsAll(paidIds);
        excl.values().forEach(x -> assertThat("PAID".equals(x.getExclRsnCd()))
                .as("%d(%s) — 유료면 PAID, 무료면 PAID 가 아니다 (사유 %s)", x.getPkltId(), x.getPkltNm(), x.getExclRsnCd())
                .isEqualTo(paidIds.contains(x.getPkltId())));
    }

    @Test
    @DisplayName("④ minCpcty 를 올리면 빠진 후보가 후보 밖 CPCTY_LACK 으로 옮겨 간다")
    void minCpctyMovesLotsBetweenLists() {
        String e = targetEmd();
        List<ZoneRcmdtnLotVO> low = mapper.selectLotRanking(param(e, MIN_20, "WIDE"));
        // 하한을 '후보 중 최대 면수' 로 올린다 — 그보다 작은 후보는 전부 빠진다(입력이지 실측값이 아니다)
        int max = low.stream().mapToInt(ZoneRcmdtnLotVO::getPkltCpcty).max().orElse(0);
        BigDecimal high = BigDecimal.valueOf(max);
        assumeTrue(low.stream().anyMatch(l -> l.getPkltCpcty() < max),
                e + " 의 WIDE 후보 면수가 모두 같다 — 하한을 올려 옮겨 갈 후보가 없다");

        ZoneRcmdtnQueryParam hp = param(e, high, "WIDE");
        Set<Long> moved = lotIds(low);
        moved.removeAll(lotIds(mapper.selectLotRanking(hp)));
        assertThat(moved).as("하한 %s → %s 로 빠진 후보", MIN_20, high).isNotEmpty();

        Set<Long> exclLow = exclById(mapper.selectExclLots(param(e, MIN_20, "WIDE"))).keySet();
        Map<Long, ZoneRcmdtnExclLotVO> exclHigh = exclById(mapper.selectExclLots(hp));
        for (Long id : moved) {
            assertThat(exclHigh.get(id)).as("빠진 후보 %d 가 후보 밖으로 왔다", id).isNotNull()
                    .extracting(ZoneRcmdtnExclLotVO::getExclRsnCd).isEqualTo("CPCTY_LACK");
        }
        Set<Long> expected = new TreeSet<>(exclLow);
        expected.addAll(moved);
        assertThat(new TreeSet<>(exclHigh.keySet())).as("후보 밖(높은 하한) = 후보 밖(낮은 하한) ∪ 빠진 후보")
                .isEqualTo(expected);
    }

    /** 한 주차장의 추세 행만 추린다 */
    private static List<ZoneRcmdtnTrendCountVO> rows(List<ZoneRcmdtnTrendCountVO> trend,
                                                     ZoneRcmdtnLotVO l) {
        return trend.stream().filter(r -> l.getPkltId().equals(r.getPkltId())).toList();
    }
}
