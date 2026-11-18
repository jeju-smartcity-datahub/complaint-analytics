package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.GradeCriterionVO;
import jeju.datahub.analysis.service.HotspotAnalysisResultVO;
import jeju.datahub.analysis.service.HotspotGrade;
import jeju.datahub.analysis.service.HotspotZoneVO;

/**
 * 불법주차 집중구역 분석 API 통합 테스트. (TSK-4032 Step 7 — {@code GET /api/analysis/hotspot})
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리해 테스트 트랜잭션이 서버 쪽을 감싸지 못한다({@code AnalysisWeightIntegrationTest} ·
 * {@code ComplaintDashboardIntegrationTest} 와 같은 전략). 그래서 케이스 8·9 처럼 DB 를 바꾸는 테스트는
 * <b>실제 커밋 + {@link #restoreHotspotIndicators()} 수동 복원</b>으로 처리한다.
 *
 * <p>🔴 <b>복원이 이 테스트의 생명선이다.</b> 케이스 8·9 는 {@code parking.TB_ANLS_VRBL_STNG} 의
 * {@code use_yn} 을 {@code 'N'} 으로 내린다. 남겨 두면 같은 패키지의 {@code AnalysisWeightIntegrationTest}
 * (TSK-4020)가 전역 상태(HOTSPOT 60/40 · 합계 100 · {@code selectEffectiveWeights} 2건)를 단언하다 함께
 * 깨진다. 복원은 {@code @BeforeEach} + {@code @AfterEach} <b>양쪽</b>에 걸어 테스트 순서·이전 실행의
 * 비정상 종료와 무관하게 성립시킨다.
 *
 * <h2>🔴 날짜·기대값을 상수로 박지 않는다 (데이터 독립성)</h2>
 * 민원·단속 적재분은 <b>배치가 소유</b>한다 — 재적재 한 번이면 구간이 통째로 옮겨간다(실제로
 * {@code 2025-11~12} → {@code 2026-07~08} 로 옮겨져 이 테스트 7건이 한꺼번에 죽었다). 그래서
 * <ul>
 *   <li><b>조회 기간</b>은 {@link #resolveDataFixtures()} 가 매퍼 {@code selectDataRange} 와 <b>같은
 *       조건</b>으로 DB 에서 읽는다({@link #dataBgngYmd} ~ {@link #dataEndYmd}).</li>
 *   <li><b>점수·건수 기대값</b>은 리터럴 대신 <b>불변식</b>으로 단언한다 — 점수는 0~100 의 상대 점수
 *       ({@link #assertScoreSatisfiesNormalizedFormula}), 등급 = 표시 3종
 *       ({@link #assertGradeMatchesScore}), 구역명 = 읍면동명 접두. 이 셋은 데이터가 어떻게 바뀌어도 성립한다.</li>
 *   <li><b>지역·기간이 결과를 줄이기만 하는지</b>는 전 지역·전 구간 결과와 <b>비교</b>해서 본다
 *       (케이스 3·4). 어느 쪽도 절대값을 박지 않는다.</li>
 * </ul>
 * 다만 {@code zones} 개수 단언({@code 15}·{@code 30})은 <b>데이터 양이 아니라 limit 계약</b>(D12)을 본다 —
 * 전 구간에서 상위 30건조차 안 나온다면 적재분이 비정상적으로 얇은 것이므로 그때는 실패가 맞다.
 *
 * <p>🔴 <b>VWorld 실호출에 의존하지 않는다.</b> 구역명 폴백(D6)의 ②POI·③역지오코딩은 API 키가 비면
 * HTTP 를 <b>한 번도 타지 않고</b> 일시 실패로 수렴한다({@code VworldPlaceClient} ·
 * {@code VworldReverseGeocodeClient} 의 {@code apiKey.isEmpty()} 분기). 그래서 키를 빈 값으로 고정해
 * 외부가 죽어도·키 없는 CI 에서도 같은 결과가 나오게 한다. 일시 실패는 캐시에 굳히지 않으므로
 * ({@code rule_batch-project.md} 의 캐시 원칙과 같은 사고방식) 이 테스트는
 * {@code parking.TB_ZONE_NM_CACHE} 에 <b>단 한 행도 남기지 않는다</b> — 케이스 1 이 그것까지 단언한다.
 * ({@code @MockitoBean} 을 쓰지 않은 이유: 스텁이 돌려줄 {@code LookupStatus} 가
 * {@code jeju.datahub.analysis.service.impl} 의 package-private 타입이라 이 패키지에서 이름을 쓸 수 없다.
 * 같은 목적의 {@code ZoneNameCacheWriterTransactionTest} 는 그 패키지 안에 있어 스텁이 가능하다.)
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다 — 모든 프로파일이 PostgreSQL 이고 H2 는 없다.
 * 민원({@code TB_CVLCPT}) · 단속({@code TB_CRDN_DSCTN}) 적재분과 마이그레이션 53 의 가중치 시드
 * (HOTSPOT 60/40)를 전제로 한다. <b>적재 구간이 어디든 상관없다</b>(위 "데이터 독립성" 참고).
 *
 * <p>🔴 {@link BigDecimal} 단언은 전부 {@code isEqualByComparingTo} 다 — 점수는 SQL 의
 * {@code ROUND(...,2)} 라 {@code 164.00} 으로 오고 리터럴은 {@code 164} 라 {@code isEqualTo} 는
 * 스케일 차이로 실패한다.
 *
 * <p>응답 바디의 {@code message} 는 단언하지 않는다 — devtools 유무에 따라 있고 없고가 갈려
 * 환경에 따라 깨지는 단언이 된다. <b>상태코드만</b> 본다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 키를 비워 VWorld HTTP 호출 경로를 원천 차단한다 (위 클래스 주석 참고).
        properties = "analysis.zone-name.vworld.api-key=")
class HotspotAnalysisIntegrationTest {

    private static final String HOTSPOT_URL = "/api/analysis/hotspot";

    /** 응답 {@code bgngYmd}/{@code endYmd} · 보유 구간과 같은 표기 형식. */
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 조회 가능한 최대 구간(일, 양끝 포함). {@code jeju.datahub.common.DateRanges.MAX_RANGE_DAYS} 와
     * 같은 값이어야 한다 — 테스트가 독립적으로 다시 적는다(등급 임계값과 같은 방식).
     */
    private static final int MAX_RANGE_DAYS = 366;

    /**
     * 형식·검증만 보는 케이스(6)가 쓰는 <b>아무 유효 날짜</b>. 조회 결과를 보지 않으므로
     * 데이터 유무와 무관하다 — 결과를 보는 케이스는 이 값을 쓰지 않는다({@link #dataBgngYmd} 를 쓴다).
     */
    private static final String ANY_VALID_BGNG = "20260101";
    private static final String ANY_VALID_END = "20261231";

    /** 연동 읍면동코드 — 케이스 3 의 지역 한정 입력값. {@code TB_ADMDST} 의 고정 코드라 상수로 둔다. */
    private static final String EMD_YEONDONG = "50110137";

    /** 가중치 시드가 유일하게 켜 둔 분석유형 (COMPLAINT 60 / ENFORCE 40) */
    private static final String HOTSPOT = "HOTSPOT";
    private static final String VRBL_COMPLAINT = "COMPLAINT";
    private static final String VRBL_ENFORCE = "ENFORCE";

    /**
     * {@code limit} <b>상한이 없다</b>는 것을 보일 때 쓰는 큰 값 (🔁 TSK-4172 D8).
     *
     * <p>🔴 종전의 기본값 15·상한 30 을 담은 두 테스트 상수는 <b>함께 사라졌다</b> —
     * {@code limit} 은 미지정이면 전량이고 {@code @Max} 가 없다. 그래서 개수를 리터럴로 단언하지 않고,
     * "전량 == 아무리 큰 limit" 이라는 성질로 본다({@link #omittedLimitMeansNoCeiling()}).
     */
    private static final int WIDE_LIMIT = 9999;

    /** {@link #omittedLimitMeansNoCeiling()} 이 "전량이 더 많다" 를 비교할 작은 limit. */
    private static final int SMALL_LIMIT = 1;

    /** 점수 상한 (TSK-4321). 조회 결과 1위가 정확히 이 값이다. */
    private static final BigDecimal SCORE_MAX = new BigDecimal("100");

    /**
     * 기대 {@code scoreFormula} (TSK-4321) — 시드 가중치 60/40 전제다.
     * 리터럴로 두는 이유: 응답에서 다시 읽어 조립하면 구현의 복사가 되어 아무것도 못 잡는다.
     */
    private static final String FORMULA_BOTH = "(CVLCPT/MAX_CVLCPT*60 + CRDN/MAX_CRDN*40) / MAX * 100";

    /** 사용자 입력 80/20(케이스 18)의 산식 — 곱셈 쪽만 입력값으로 바뀐다. */
    private static final String FORMULA_USER_80_20 = "(CVLCPT/MAX_CVLCPT*80 + CRDN/MAX_CRDN*20) / MAX * 100";

    /** ENFORCE 를 끈 뒤(케이스 8)의 산식 — 민원 계수가 1.0 이라 퍼센트 표기는 {@code 100} 이다. */
    private static final String FORMULA_CVLCPT_ONLY = "(CVLCPT/MAX_CVLCPT*100) / MAX * 100";

    /** {@code ROUND(…, 2)} 두 번(원본·기대값)에서 생기는 오차 한계. */
    private static final BigDecimal ROUNDING_TOLERANCE = new BigDecimal("0.02");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 원천 보유 구간 시작일 — 매퍼 {@code selectDataRange} 와 같은 조건으로 DB 에서 읽는다. */
    private String dataBgngYmd;

    /** 원천 보유 구간 종료일. */
    private String dataEndYmd;

    /**
     * 실제로 <b>요청 기간으로 쓰는</b> 시작일 = {@code max(dataBgngYmd, dataEndYmd - 365일)}.
     *
     * <p>🔴 보유 구간을 그대로 요청하면 상한이 없다 — {@code min} 은 가장 오래된 행에 고정인데
     * {@code max} 는 수집 배치가 매일 밀어 올려 구간이 하루씩 자란다. 366일
     * ({@code DateRanges.MAX_RANGE_DAYS})을 넘는 순간 {@link #fullPeriod()} 를 쓰는 전 케이스가
     * 한꺼번에 400 을 받는다(과거 구간 백필이 들어오면 즉시다). 그래서 시작일만 자른다 —
     * 보유 구간이 1년 이내면 {@code dataBgngYmd} 와 같은 값이라 동작 변화가 없다.
     *
     * <p>⚠️ <b>요청 조건 echo</b>({@code body.getBgngYmd()})만 이 값과 대조한다.
     * <b>보유 구간 echo</b>({@code body.getDataBgngYmd()})는 요청과 무관하므로(D11)
     * {@link #dataBgngYmd} 그대로 대조한다 — 둘을 같이 바꾸면 보유 구간 검증이 무의미해진다.
     */
    private String queryBgngYmd;

    /**
     * 원천 행이 <b>가장 적은 하루</b>(단, 0건인 날은 제외). 케이스 4 가 쓴다 — 하루로 좁혔을 때
     * 군집이 가장 안 생길 법한 날을 고르되 "그날 데이터는 있다"는 전제를 SQL 로 보장한다.
     */
    private String sparsestDayYmd;

    /**
     * HOTSPOT 지표를 시드 상태({@code use_yn='Y'})로 되돌린다.
     *
     * <p>🔴 {@code @BeforeEach} 와 {@code @AfterEach} 양쪽에 건다 — 케이스 8·9 가 각자 정리하고 끝나므로
     * 테스트 순서 의존이 생기지 않고, 이전 실행이 비정상 종료해 'N' 이 남아 있어도 첫 테스트 전에 복구된다.
     * {@code stng_vl} 는 건드리지 않는다(이 테스트는 비율을 바꾸지 않는다 — 그쪽 복원은 TSK-4020 소관).
     */
    @BeforeEach
    @AfterEach
    void restoreHotspotIndicators() {
        jdbcTemplate.update(
                "UPDATE parking.tb_anls_vrbl_stng SET use_yn = 'Y' WHERE anls_type_cd = ?", HOTSPOT);

        Integer disabled = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND use_yn <> 'Y'",
                Integer.class, HOTSPOT);
        assertThat(disabled)
                .as("HOTSPOT 지표 use_yn 원복 실패 — 이 상태로 두면 AnalysisWeightIntegrationTest 까지 깨진다")
                .isZero();
    }

    /**
     * 조회 기간을 <b>DB 에서</b> 읽어 둔다 (상수 박기 금지 — 클래스 주석 "데이터 독립성" 참고).
     *
     * <p>적재분이 통째로 비어 있으면 이 테스트 전체가 의미를 잃으므로 여기서 먼저 <b>명확한 메시지로</b>
     * 실패시킨다 — 개별 케이스가 {@code Expected size: 15 but was: 0} 으로 죽는 것보다 원인이 드러난다.
     */
    @BeforeEach
    void resolveDataFixtures() {
        String[] range = jdbcTemplate.queryForObject(dataRangeSql(),
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        dataBgngYmd = range[0];
        dataEndYmd = range[1];
        assertThat(dataBgngYmd)
                .as("원천(TB_CVLCPT·TB_CRDN_DSCTN)이 비어 있다 — 배치 적재분을 먼저 확인하라")
                .matches("\\d{8}");
        assertThat(dataEndYmd).matches("\\d{8}");
        queryBgngYmd = clampToMaxRange(dataBgngYmd, dataEndYmd);

        sparsestDayYmd = jdbcTemplate.queryForObject(sparsestDaySql(), String.class);
        assertThat(sparsestDayYmd).as("좌표가 있는 원천 행이 하루도 없다").matches("\\d{8}");
    }

    // ────────────────────────────────────────────────────────────── 1 · 2. 정상 조회

    @Test
    @DisplayName("1. 전 지역 · 보유 구간 전체 — 200 · zones 1건 이상 · rnk 1..n · snthsScr 내림차순")
    void allRegionsReturnsRankedZones() {
        // 외부 호출이 없다는 것을 캐시 행 수로도 확인한다 (일시 실패는 캐시에 굳히지 않는다).
        int cachedBefore = zoneNameCacheCount();

        ResponseEntity<HotspotAnalysisResultVO> response = analyze(fullPeriod());
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        HotspotAnalysisResultVO body = response.getBody();
        assertThat(body).isNotNull();

        // 조건 echo — 전 지역이므로 지역 필드는 null 이다 (요청 기간은 clamp 된 시작일이다)
        assertThat(body.getBgngYmd()).isEqualTo(queryBgngYmd);
        assertThat(body.getEndYmd()).isEqualTo(dataEndYmd);
        assertThat(body.getSggNm()).isNull();
        assertThat(body.getEmdCd()).isNull();

        // 재현용 군집 파라미터 — 결과가 이 두 값에 묶여 있어 함께 못박는다
        assertThat(body.getEps()).isEqualTo(50);
        assertThat(body.getMinPoints()).isEqualTo(3);

        // 보유 구간 echo 는 요청 기간과 무관하게 매퍼가 채운다 (D11)
        assertThat(body.getDataBgngYmd()).isEqualTo(dataBgngYmd);
        assertThat(body.getDataEndYmd()).isEqualTo(dataEndYmd);

        // 시드 가중치(60/40)가 그대로 적용됐다 — 산식·echo 가 갈리지 않는지 함께 본다
        assertThat(body.getScoreFormula()).isEqualTo(FORMULA_BOTH);
        assertThat(body.getWeights()).extracting(AnalysisWeightVO::getVrblCd)
                .containsExactly(VRBL_COMPLAINT, VRBL_ENFORCE);
        assertThat(body.getWeights().get(0).getStngVl()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(body.getWeights().get(1).getStngVl()).isEqualByComparingTo(new BigDecimal("40"));

        List<HotspotZoneVO> zones = body.getZones();
        // 🔴 개수를 리터럴로 단언하지 않는다 (🔁 TSK-4172) — limit 미지정은 전량이고, 응답에는
        //    주의 이상만 실린다. 몇 칸이 남는지는 적재분·가중치에 달렸으므로
        //    "비어 있지 않다" 만 본다. 상한·하한의 계약은 케이스 7·15·16 이 전담한다.
        assertThat(zones)
                .as("보유 구간 전체에서 주의 이상이 한 칸도 없다 — 적재분을 확인하라")
                .isNotEmpty();

        // 점수 0~100 · 등급 · 구역명 · 순위의 계약을 한 번에 본다
        assertZoneContract(zones, "0.6", "0.4");

        assertThat(zoneNameCacheCount())
                .as("VWorld 를 부르지 않았으므로 구역명 캐시에 새 행이 생기면 안 된다")
                .isEqualTo(cachedBefore);
    }

    @Test
    @DisplayName("2. 1위 구역 — 점수가 0~100 · 등급이 임계값과 일치 · zoneNm 이 읍면동명으로 시작")
    void topZoneSatisfiesScoreAndGradeInvariants() {
        HotspotAnalysisResultVO body = analyzeOk(fullPeriod());
        List<HotspotZoneVO> zones = body.getZones();
        assertThat(zones).isNotEmpty();

        HotspotZoneVO top = zones.get(0);
        assertThat(top.getRnk()).isEqualTo(1);

        assertScoreSatisfiesNormalizedFormula(top, "0.6", "0.4");
        assertThat(top.getSnthsScr()).as("1위 구역은 정확히 100 점이다 (TSK-4321)").isEqualByComparingTo(SCORE_MAX);
        assertThat(top.getCvlcptCnt() + top.getCrdnCnt()).as("1위 구역은 원천 건수를 갖는다").isPositive();

                assertGradeMatchesScore(top);

        // 1위는 목록 전체의 최고점이다 (ORDER BY snths_scr DESC)
        for (HotspotZoneVO zone : zones) {
            assertThat(top.getSnthsScr()).isGreaterThanOrEqualTo(zone.getSnthsScr());
        }

        // 구역명은 어느 폴백 단계를 타든 읍면동명 접두를 유지한다
        // (①SQL 의 CASE 와 ZoneNameResolver.compose 가 같은 규칙)
        assertThat(top.getEmdNm()).as("1위 구역의 중심점은 행정경계 안에 있다").isNotBlank();
        assertThat(top.getZoneNm())
                .as("구역명(%s)이 읍면동명(%s)으로 시작해야 한다", top.getZoneNm(), top.getEmdNm())
                .startsWith(top.getEmdNm());

        // 1위만이 아니라 목록 전체가 같은 규칙을 지킨다 (읍면동 밖이면 emdNm 이 null 이라 건너뛴다)
        for (HotspotZoneVO zone : zones) {
            if (zone.getEmdNm() != null && !zone.getEmdNm().isBlank()) {
                assertThat(zone.getZoneNm()).startsWith(zone.getEmdNm());
            }
        }
    }

    // ────────────────────────────────────────────────────────────── 3. 지역 한정

    @Test
    @DisplayName("3. emdCd=50110137(연동) — 결과가 연동으로 한정되고 emdCd 를 되비춘다")
    void emdFilterLimitsResultToSingleEmd() {
        // 비교 기준: 같은 기간의 전 지역 결과. 지역 필터는 결과를 '줄이기만' 해야 한다.
        List<HotspotZoneVO> allRegionZones = analyzeOk(fullPeriod()).getZones();
        assertThat(allRegionZones).isNotEmpty();

        Map<String, Object> params = fullPeriod();
        params.put("emdCd", EMD_YEONDONG);
        HotspotAnalysisResultVO body = analyzeOk(params);

        // sggNm 없이 emdCd 만 왔으므로 시군구 echo 는 null 이다
        assertThat(body.getEmdCd()).isEqualTo(EMD_YEONDONG);
        assertThat(body.getSggNm()).isNull();

        List<HotspotZoneVO> zones = body.getZones();

        // 🔴 연동에 군집이 없을 수도 있다(0건도 정상 — D11). 결과가 있으면 전부 연동이어야 한다.
        String yeondong = emdNmOf(EMD_YEONDONG);
        assertThat(zones)
                .as("연동으로 한정했으므로 다른 읍면동이 섞이면 안 된다")
                .allSatisfy(zone -> assertThat(zone.getEmdNm()).isEqualTo(yeondong));

        // 필터는 점을 덜어내기만 하므로 어떤 군집도 전 지역 1위 점수를 넘어설 수 없다
        if (!zones.isEmpty()) {
            assertThat(zones.get(0).getSnthsScr())
                    .as("지역 필터가 점수를 키울 수는 없다")
                    .isLessThanOrEqualTo(allRegionZones.get(0).getSnthsScr());
        }
        // 전 지역보다 구역 수가 늘어날 수는 없다 (🔁 TSK-4172 — 리터럴 상한이 아니라 전 지역 결과와 견준다)
        assertThat(zones.size())
                .as("지역 한정 결과(%d칸)가 전 지역(%d칸)보다 많을 수는 없다",
                        zones.size(), allRegionZones.size())
                .isLessThanOrEqualTo(allRegionZones.size());

        // 결과가 있다면 순위·점수·등급 계약은 전 지역과 똑같이 성립한다
        assertZoneContract(zones, "0.6", "0.4");
    }

    // ────────────────────────────────────────────────────────────── 4 · 5. 빈 결과도 200

    @Test
    @DisplayName("4. 하루 구간 — 200 · 데이터는 있는데 군집이 거의 형성되지 않아 결과가 전 구간보다 적다")
    void singleDayFormsFewerOrNoClusters() {
        // 전 구간 결과(=limit 을 꽉 채운다)와 비교하기 위한 기준값
        int fullPeriodZoneCount = analyzeOk(fullPeriod()).getZones().size();
        assertThat(fullPeriodZoneCount).isPositive();

        HotspotAnalysisResultVO body = analyzeOk(period(sparsestDayYmd, sparsestDayYmd));

        // 🔴 그날 데이터 자체는 있다 — '데이터 없음'이 아니라 '군집 미형성'임을 못박는 핵심 단언
        assertThat(sourceRowCount(sparsestDayYmd))
                .as("%s 의 원천 행 수", sparsestDayYmd)
                .isPositive();

        assertThat(body.getBgngYmd()).isEqualTo(sparsestDayYmd);
        assertThat(body.getEndYmd()).isEqualTo(sparsestDayYmd);
        assertThat(body.getDataBgngYmd()).isNotBlank();
        assertThat(body.getDataEndYmd()).isNotBlank();

        // 하루로 좁히면 유일좌표 밀도가 minPoints(3)/eps(50m) 아래로 떨어져 군집이 거의 안 생긴다.
        // 0건이어도 오류가 아니다(D11). '몇 건이 되는지'는 데이터에 달렸으므로 절대값 대신 전 구간과 비교한다.
        // (2026-08-27 실측: 가장 얇은 하루 20260704 = 101행 → 군집 1개, 전 구간 270개 중 상위 15건)
        assertThat(body.getZones().size())
                .as("하루 구간(%s)은 전 구간(%d건)보다 결과가 적어야 한다", sparsestDayYmd, fullPeriodZoneCount)
                .isLessThan(fullPeriodZoneCount);

        // 0건이면 아무것도 검사하지 않고, 몇 건이 나왔다면 계약은 그대로 성립해야 한다
        assertZoneContract(body.getZones(), "0.6", "0.4");
    }

    @Test
    @DisplayName("5-1. 🔴 366일을 넘는 조회 기간은 400 (TSK-4085 D9) — 366일 경계는 통과한다")
    void overlyLongRangeIsRejected() {
        // 🔴 날짜를 상수로 박지 않는다 — 오늘 기준 상대값이라 시드가 옮겨져도 성립한다.
        //    고정 날짜로 366/367 을 세면 윤년이 끼는 해에 하루가 어긋난다.
        LocalDate end = LocalDate.now();

        // 367일(상한 +1) → 400. message 는 단언하지 않는다(devtools 없는 ./gradlew test 에는 없다)
        assertThat(statusOf(period(end.minusDays(MAX_RANGE_DAYS).format(YMD), end.format(YMD))))
                .as("367일 구간은 거절돼야 한다").isEqualTo(400);

        // 366일(상한과 같음) → 200. 경계의 양쪽을 다 잡아야 상한이 고정된다.
        assertThat(statusOf(period(end.minusDays(MAX_RANGE_DAYS - 1L).format(YMD), end.format(YMD))))
                .as("366일 구간은 통과해야 한다").isEqualTo(200);
    }

    @Test
    @DisplayName("5. 보유 구간 밖의 기간 — 200 · 빈 배열 · 보유 구간은 채워진다")
    void emptyPeriodStillCarriesDataRange() {
        // 🔴 '데이터 없는 기간'도 상수로 박지 않는다 — 보유 구간 종료일 + 1년부터 한 달을 계산해 쓴다
        LocalDate outsideBgng = LocalDate.parse(dataEndYmd, YMD).plusYears(1);
        LocalDate outsideEnd = outsideBgng.plusMonths(1);
        HotspotAnalysisResultVO body =
                analyzeOk(period(outsideBgng.format(YMD), outsideEnd.format(YMD)));

        assertThat(body.getZones()).isEmpty();

        // 🔴 보유 구간은 상수로 박지 않는다 — 적재분은 배치가 소유하므로 매퍼와 같은 조건으로 DB 에서 읽는다
        assertThat(body.getDataBgngYmd()).isEqualTo(dataBgngYmd);
        assertThat(body.getDataEndYmd()).isEqualTo(dataEndYmd);
        assertThat(body.getDataBgngYmd()).matches("\\d{8}");
        assertThat(body.getDataEndYmd()).matches("\\d{8}");

        // 0건이어도 가중치·군집 파라미터는 그대로 실려 화면이 조건을 설명할 수 있다
        assertThat(body.getWeights()).hasSize(2);
        assertThat(body.getScoreFormula()).isEqualTo(FORMULA_BOTH);
    }

    // ────────────────────────────────────────────────────────────── 6. 잘못된 입력 = 400

    @Test
    @DisplayName("6-1. bgngYmd > endYmd — 400")
    void reversedPeriodReturns400() {
        // 데이터 유무와 무관한 검증이라 보유 구간이 아니라 고정 유효 날짜를 쓴다
        // (보유 구간이 하루뿐이면 bgng>end 가 성립하지 않아 케이스 자체가 무너진다).
        assertThat(statusOf(period(ANY_VALID_END, ANY_VALID_BGNG))).isEqualTo(400);
    }

    @Test
    @DisplayName("6-2. 날짜 형식 오류 · 실재하지 않는 날짜 — 400")
    void malformedYmdReturns400() {
        assertThat(statusOf(period("2026-01-01", ANY_VALID_END))).as("하이픈 포함 → @Pattern").isEqualTo(400);
        assertThat(statusOf(period("202601", ANY_VALID_END))).as("자릿수 부족 → @Pattern").isEqualTo(400);
        assertThat(statusOf(period(ANY_VALID_BGNG, "20261301"))).as("13월 → STRICT 파싱").isEqualTo(400);
        assertThat(statusOf(period(ANY_VALID_BGNG, "20260230"))).as("2월 30일 → STRICT 파싱").isEqualTo(400);
        assertThat(statusOf(period("", ANY_VALID_END))).as("bgngYmd 빈 값 → @NotBlank").isEqualTo(400);

        Map<String, Object> onlyEnd = new LinkedHashMap<>();
        onlyEnd.put("endYmd", ANY_VALID_END);
        assertThat(statusOf(onlyEnd)).as("bgngYmd 파라미터 자체를 생략 → @NotBlank").isEqualTo(400);
    }

    @Test
    @DisplayName("6-3. 미등록 시군구명 · 존재하지 않는 읍면동코드 — 400 (조용히 전 지역으로 넓히지 않는다)")
    void unknownRegionReturns400() {
        Map<String, Object> badSgg = period(ANY_VALID_BGNG, ANY_VALID_END);
        badSgg.put("sggNm", "제주도");
        assertThat(statusOf(badSgg)).isEqualTo(400);

        Map<String, Object> badEmd = period(ANY_VALID_BGNG, ANY_VALID_END);
        badEmd.put("emdCd", "99999999");
        assertThat(statusOf(badEmd)).isEqualTo(400);
    }

    // ────────────────────────────────────────────────────────────── 7. limit 경계

    @Test
    @DisplayName("7. limit 경계 — 0→400 · -1→400 · 1→200(1건) · 9999→200 (🔁 TSK-4172 D8 상한 없음)")
    void limitBoundaries() {
        // 🔴 남은 계약은 하한뿐이다 ({@code @Min(1)}) — 종전 {@code @Max(30)} 은 TSK-4172 D8 로 사라졌다.
        assertThat(statusOf(withLimit(0))).as("@Min(1)").isEqualTo(400);
        assertThat(statusOf(withLimit(-1))).as("@Min(1)").isEqualTo(400);

        assertThat(analyzeOk(withLimit(1)).getZones())
                .as("limit=1 은 정확히 1건이다 (하한이 살아 있다는 반대쪽 증거)")
                .hasSize(1);

        // 🔴 상한이 없다 — 종전엔 31·999 가 400 이었다. 지금은 200 이고 '있는 만큼' 온다.
        assertThat(statusOf(withLimit(31))).as("31 은 더 이상 400 이 아니다 (@Max 제거)").isEqualTo(200);
        assertThat(analyzeOk(withLimit(WIDE_LIMIT)).getZones())
                .as("limit=%d 은 거절되지 않고 남은 구역 전부를 돌려준다", WIDE_LIMIT)
                .isNotEmpty();
    }

    // ────────────────────────────────────────────────────────────── 8 · 9. 가중치 (DB 변경)

    @Test
    @DisplayName("8. ENFORCE 를 use_yn='N' — COMPLAINT 가 100 으로 재정규화되어 점수 순서 = 민원건수 순서")
    void disablingEnforceRenormalizesComplaintToHundred() {
        disableIndicator(VRBL_ENFORCE);

        HotspotAnalysisResultVO body = analyzeOk(fullPeriod());

        // 계수 1.0 / 0 — 0 배인 항은 산식 표기에서 빠진다(산식에 없는 것과 0 배는 같다).
        // 🔁 TSK-4196: 곱셈 쪽은 계수가 아니라 퍼센트라 1.0 → 100 이다.
        assertThat(body.getScoreFormula()).isEqualTo(FORMULA_CVLCPT_ONLY);
        assertThat(body.getWeights()).extracting(AnalysisWeightVO::getVrblCd).containsExactly(VRBL_COMPLAINT);
        assertThat(body.getWeights().get(0).getStngVl())
                .as("남은 지표가 100 으로 재정규화된다 (TSK-4020 selectEffectiveWeights)")
                .isEqualByComparingTo(new BigDecimal("100"));

        List<HotspotZoneVO> zones = body.getZones();
        // 🔁 TSK-4172 — 개수를 고정하지 않는다. 아래 순서 단언은 몇 칸이든 성립한다.
        assertThat(zones)
                .as("ENFORCE 를 끈 뒤에도 민원만으로 주의 이상인 구역이 있어야 한다")
                .isNotEmpty();

        // 계수가 1/0 으로 바뀌었을 뿐 계약은 그대로다 — 점수 = 민원 ÷ 최대 민원 × 100
        assertZoneContract(zones, "1", "0");

        // 단속 계수가 0 이라 "점수 순서 = 민원건수 순서" 가 된다 — 건수·기간이 어떻든 성립한다.
        HotspotZoneVO top = zones.get(0);
        assertThat(top.getCvlcptCnt()).as("단속 계수가 0 이므로 1위는 민원을 갖는 구역이다").isPositive();
        for (HotspotZoneVO zone : zones) {
            assertThat(top.getCvlcptCnt())
                    .as("1위는 민원이 가장 많은 구역이어야 한다 (구역 %s)", zone.getZoneNm())
                    .isGreaterThanOrEqualTo(zone.getCvlcptCnt());
        }
        for (int i = 1; i < zones.size(); i++) {
            assertThat(zones.get(i).getCvlcptCnt())
                    .as("민원이 더 많은데 순위가 뒤일 수는 없다 (rnk %d)", zones.get(i).getRnk())
                    .isLessThanOrEqualTo(zones.get(i - 1).getCvlcptCnt());
        }

        // 이 테스트가 DB 를 바꿨다는 사실을 명시적으로 남긴다 (복원은 @AfterEach 가 한다)
        assertThat(disabledIndicatorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("9. HOTSPOT 지표 전부 use_yn='N' — 500 이 아니라 409 (조용히 0점을 만들지 않는다)")
    void disablingAllHotspotIndicatorsReturnsMeaningfulError() {
        disableIndicator(VRBL_COMPLAINT);
        disableIndicator(VRBL_ENFORCE);
        assertThat(disabledIndicatorCount()).isEqualTo(2);

        int status = statusOf(fullPeriod());

        // 0점 결과는 '데이터가 없다'와 구분되지 않는다 — 설정 실수를 정상 응답으로 위장시키지 않는다
        assertThat(status).as("가중치 미설정은 서버 버그(500)가 아니라 설정 충돌(409)이다").isEqualTo(409);
        assertThat(status).isNotEqualTo(500);
    }

    // ─────────────────────────────────────────────────── 10. 등급 판정 기준 (범례 문구의 출처)

    @Test
    @DisplayName("10. gradeCriteria — HotspotGrade enum 이 그대로 실린다 (지도 범례 문구의 출처, D5)")
    void gradeCriteriaMirrorsGradeEnum() {
        HotspotAnalysisResultVO body = analyzeOk(fullPeriod());
        List<GradeCriterionVO> criteria = body.getGradeCriteria();

        // (1) 라벨은 테스트가 독립적으로 다시 적는다 — enum 을 빌려 오면 값이 바뀌어도 함께 따라간다.
        //     🔴 비율의 정본은 설정 표(GRD_BNDRY1~3)라 상수가 아니라 DB 현재값과 대조한다 (TSK-4321).
        BigDecimal[] bndry = gradeBoundaries();
        assertThat(criteria)
                .as("범례가 설정 비율로 심각·경고·주의 문구를 만들 수 있어야 한다")
                .extracting(GradeCriterionVO::getGrade, GradeCriterionVO::getGradeNm,
                        GradeCriterionVO::getMaxRankPct)
                .containsExactly(
                        tuple("SEVERE", "심각", bndry[0].intValueExact()),
                        tuple("WARN", "경고", bndry[1].intValueExact()),
                        tuple("CAUTION", "주의", bndry[2].intValueExact()));

        // (2) enum 과 응답이 어긋나지 않는다 — 등급을 추가하고 응답 조립을 잊으면 여기서 걸린다.
        //     값이 아니라 '구성'을 보는 단언이라 (1) 의 독립성을 해치지 않는다.
        //     🔴 대조 대상은 values()(4개)가 아니라 displayedValues()(3개)다 — NORMAL 은 enum 에 남아
        //     있지만(D1) 응답에는 싣지 않는다. values() 로 두면 이 단언이 확정 실패한다.
        assertThat(criteria).extracting(GradeCriterionVO::getGrade)
                .as("응답의 등급 목록이 HotspotGrade.displayedValues() 선언 순서와 같아야 한다")
                .containsExactly(HotspotGrade.displayedValues().stream()
                        .map(Enum::name).toArray(String[]::new));

        // (3) NORMAL 이 응답에서 빠진 이유 (🔁 TSK-4172) — 조회 자체가 주의 이상만 돌려주므로
        //     '양호' 를 설명할 행이 필요 없다. 뒤집어 말하면 zones[] 의 모든 등급이 이 3행으로
        //     설명돼야 한다 — 양호 구역이 한 칸이라도 섞여 들어오면 여기서 걸린다.
        //     (종전에는 4행을 내려보내고 프론트가 범례에서 양호를 걸러 냈다.)
        List<String> codes = criteria.stream().map(GradeCriterionVO::getGrade).toList();
        assertThat(body.getZones()).isNotEmpty();
        assertThat(body.getZones()).extracting(HotspotZoneVO::getGrade).isSubsetOf(codes);

        // (4) 조회 결과와 무관한 상수다 — 0건 응답에도 그대로 실린다(범례는 결과가 없어도 설명 가능해야 한다).
        //     기간은 케이스 5 와 같은 방식으로 보유 구간 밖을 계산해 쓴다(상수 금지).
        LocalDate outsideBgng = LocalDate.parse(dataEndYmd, YMD).plusYears(1);
        HotspotAnalysisResultVO empty =
                analyzeOk(period(outsideBgng.format(YMD), outsideBgng.plusMonths(1).format(YMD)));
        assertThat(empty.getZones()).isEmpty();
        assertThat(empty.getGradeCriteria())
                .as("0건이어도 등급 기준은 채워 보낸다")
                .extracting(GradeCriterionVO::getGrade)
                .containsExactlyElementsOf(codes);
    }

    // ──────────────────────── 11 ~ 14. 조회 결과 최댓값 기준 상대 점수 (TSK-4321)

    @Test
    @DisplayName("11. 🔴 1위 = 100 — 전 지역 · 시군구 · 가중치 조정 어느 조건에서도 1위가 정확히 100 이다 (TSK-4321)")
    void topScoreIsAlwaysHundred() {
        Map<String, Object> sgg = fullPeriod();
        sgg.put("sggNm", "서귀포시");

        for (Map<String, Object> params : List.of(fullPeriod(), sgg, withWeights("80", "20"))) {
            List<HotspotZoneVO> zones = analyzeOk(params).getZones();
            assertThat(zones).as("조건 %s 에 군집이 없다", params).isNotEmpty();
            assertThat(zones.get(0).getSnthsScr())
                    .as("조건 %s 의 1위 점수", params)
                    .isEqualByComparingTo(SCORE_MAX);
            assertThat(zones.get(0).getGrade()).isEqualTo("SEVERE");
        }
    }

    @Test
    @DisplayName("12. 🔴 산식 재현 — 지표별 최댓값으로 나눠 가중합한 뒤 1위 대비 100 으로 환산한 값과 같다 (TSK-4321 D2)")
    void scoreMatchesListMaxFormula() {
        // 🔴 최댓값은 응답에 없고 40점 미만 구역은 응답에서 빠진다. 대신 100/0 · 0/100 조회의 1위가 곧 지표별
        //    최댓값이다 — DBSCAN 을 테스트에 복사하지 않고 응답만으로 재현한다.
        BigDecimal maxCvlcpt = BigDecimal.valueOf(analyzeOk(withWeights("100", "0")).getZones().get(0).getCvlcptCnt());
        BigDecimal maxCrdn = BigDecimal.valueOf(analyzeOk(withWeights("0", "100")).getZones().get(0).getCrdnCnt());

        List<HotspotZoneVO> zones = analyzeOk(fullPeriod()).getZones();
        assertThat(zones).isNotEmpty();
        BigDecimal topWgted = wgtedVl(zones.get(0), maxCvlcpt, maxCrdn);

        for (HotspotZoneVO zone : zones) {
            BigDecimal expected = wgtedVl(zone, maxCvlcpt, maxCrdn)
                    .divide(topWgted, 10, RoundingMode.HALF_UP)
                    .multiply(SCORE_MAX);
            assertThat(zone.getSnthsScr().subtract(expected).abs())
                    .as("구역 %s (민원 %d · 단속 %d) 점수 %s · 기대 %s",
                            zone.getZoneNm(), zone.getCvlcptCnt(), zone.getCrdnCnt(), zone.getSnthsScr(), expected)
                    .isLessThanOrEqualTo(ROUNDING_TOLERANCE);
        }
    }

    @Test
    @DisplayName("13. 🔴 limit 독립 — 최댓값을 LIMIT 앞에서 구해 limit 을 줘도 같은 구역의 점수가 같다 (TSK-4321 D4)")
    void limitDoesNotChangeScores() {
        List<HotspotZoneVO> all = analyzeOk(fullPeriod()).getZones();
        assertThat(all).as("비교하려면 2칸 이상이어야 한다 — 적재분을 확인하라").hasSizeGreaterThanOrEqualTo(2);

        List<HotspotZoneVO> limited = analyzeOk(withLimit(2)).getZones();
        assertThat(limited).hasSize(2);
        for (int i = 0; i < limited.size(); i++) {
            assertThat(limited.get(i).getSnthsScr()).isEqualByComparingTo(all.get(i).getSnthsScr());
        }
    }

    @Test
    @DisplayName("14. 🔴 0 나눗셈 없음 — 조건 안 군집의 민원이 모두 0 인데 민원 100/단속 0 이어도 500 이 아니다 (TSK-4321)")
    void allZeroMetricDoesNotDivideByZero() {
        String minCvlcpt = jdbcTemplate.queryForObject(
                "SELECT to_char(min(ocrn_dt), 'YYYYMMDD') FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'",
                String.class);
        LocalDate end = LocalDate.parse(minCvlcpt, YMD).minusDays(1);
        LocalDate bgng = end.minusDays(MAX_RANGE_DAYS - 1);
        assumeTrue(!end.isBefore(LocalDate.parse(dataBgngYmd, YMD)), "민원 시작일 이전에 단속 자료가 없다");

        Map<String, Object> params = period(bgng.format(YMD), end.format(YMD));
        params.put("cvlcptWgvl", "100");
        params.put("crdnWgvl", "0");

        // 전 구역 0점 → 0점은 줄에 세우지 않아 빈 목록이다.
        assertThat(analyzeOk(params).getZones()).isEmpty();
    }

    @Test
    @DisplayName("14-1. 🔴 등급 비율 설정 — 저장값이 범례·등급·표시 범위에 바로 반영되고 순서가 깨지면 기본값으로 떨어진다 (TSK-4321)")
    void gradeBoundariesFollowSettings() {
        // 🔴 현재 설정값을 기본값으로 덮어 두고 끝내지 않는다 — 읽어 둔 값으로 되돌린다
        BigDecimal[] original = gradeBoundaries();
        try {
            setGradeBoundary(5, 10, 15);
            int base = analyzeOk(fullPeriod()).getZones().size();

            setGradeBoundary(10, 20, 30);
            HotspotAnalysisResultVO wide = analyzeOk(fullPeriod());
            assertThat(wide.getGradeCriteria()).extracting(GradeCriterionVO::getMaxRankPct).containsExactly(10, 20, 30);
            assertThat(wide.getZones().size()).as("표시 범위가 15%% → 30%% 로 넓어졌다").isGreaterThan(base);

            setGradeBoundary(20, 10, 30);
            HotspotAnalysisResultVO broken = analyzeOk(fullPeriod());
            assertThat(broken.getGradeCriteria()).extracting(GradeCriterionVO::getMaxRankPct)
                    .as("DB 직접 수정으로 순서가 깨지면 500 이 아니라 기본값이다").containsExactly(5, 10, 15);
            assertThat(broken.getZones()).hasSize(base);
        } finally {
            setGradeBoundary(original[0], original[1], original[2]);
        }
    }

    /** 현재 설정의 {@code GRD_BNDRY1~3}. 기대값은 상수가 아니라 이 값으로 계산한다. */
    private BigDecimal[] gradeBoundaries() {
        return jdbcTemplate.queryForList(
                "SELECT stng_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ?"
                        + " AND vrbl_cd IN ('GRD_BNDRY1', 'GRD_BNDRY2', 'GRD_BNDRY3') ORDER BY vrbl_cd",
                BigDecimal.class, HOTSPOT).toArray(new BigDecimal[0]);
    }

    /** 매퍼 {@code CEIL(scr_cnt * pct / 100.0)} 과 같은 식의 기준 순위. */
    private static long rankCutoff(long total, int pct) {
        return (total * pct + 99) / 100;
    }

    /**
     * {@code RANK()} 가 기준 순위 이하인 칸 수 — 동점은 같은 순위라 기준 칸과 동점인 칸까지 든다.
     *
     * @param zonesByRank 점수 내림차순 전 구역
     */
    private static long zonesWithinRank(List<HotspotZoneVO> zonesByRank, long cutoff) {
        BigDecimal cutoffScr = zonesByRank.get((int) cutoff - 1).getSnthsScr();
        return zonesByRank.stream().filter(z -> z.getSnthsScr().compareTo(cutoffScr) >= 0).count();
    }

    private void setGradeBoundary(Number severe, Number warn, Number caution) {
        jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = CASE vrbl_cd"
                + " WHEN 'GRD_BNDRY1' THEN ? WHEN 'GRD_BNDRY2' THEN ? WHEN 'GRD_BNDRY3' THEN ? END"
                + " WHERE anls_type_cd = ? AND vrbl_cd IN ('GRD_BNDRY1', 'GRD_BNDRY2', 'GRD_BNDRY3')",
                severe, warn, caution, HOTSPOT);
    }

    // ─────────────────────── 15 ~ 17. 표시 하한(주의 40점) · 순위 연속성 · limit 상한 없음 (TSK-4172)

    @Test
    @DisplayName("15. 🔴 zones — 주의 비율 밖(양호) 구역은 응답에 없고 등급 경계가 비율을 지킨다 (TSK-4321)")
    void zonesExcludeNormalGrade() {
        List<HotspotZoneVO> zones = analyzeOk(fullPeriod()).getZones();
        assertThat(zones).as("표시 대상이 한 칸도 없으면 이 단언이 공허해진다").isNotEmpty();

        // ① 순위순으로 심각 → 경고 → 주의 가 되돌아가지 않고, 심각·주의 칸 수가 설정 비율을 지킨다.
        List<String> order = List.of("SEVERE", "WARN", "CAUTION");
        for (int i = 1; i < zones.size(); i++) {
            assertThat(order.indexOf(zones.get(i).getGrade()))
                    .as("rnk %d 의 등급이 앞 순위보다 높을 수 없다", zones.get(i).getRnk())
                    .isGreaterThanOrEqualTo(order.indexOf(zones.get(i - 1).getGrade()));
        }
        assertThat(zones.get(0).getGrade()).isEqualTo("SEVERE");
        long severe = zones.stream().filter(z -> "SEVERE".equals(z.getGrade())).count();

        // 전체 구역 수(0점 제외)는 응답에 없다 — 주의 비율 100 입력이면 전 구역이 실리므로 그 건수로 얻는다.
        // 입력은 이번 요청에만 적용되고 설정 표에 쓰지 않는다(케이스 25 가 단언).
        BigDecimal[] bndry = gradeBoundaries();
        int severePct = bndry[0].intValueExact();
        int cautionPct = bndry[2].intValueExact();
        List<HotspotZoneVO> allZones =
                analyzeOk(withVrblVls(bndryJson(severePct, bndry[1].intValueExact(), 100))).getZones();
        long total = allZones.size();

        // 칸 수는 매퍼 CEIL(전체 × 비율) · 동점 RANK 로 정확히 정해진다 — 허용 오차를 두지 않는다.
        long severeCutoff = rankCutoff(total, severePct);
        long cautionCutoff = rankCutoff(total, cautionPct);
        assertThat(severe).as("심각 칸 수 (전체 %d칸 · 상위 %d%%)", total, severePct)
                .isEqualTo(zonesWithinRank(allZones, severeCutoff));
        assertThat((long) zones.size()).as("표시 칸 수 (전체 %d칸 · 상위 %d%%)", total, cautionPct)
                .isEqualTo(zonesWithinRank(allZones, cautionCutoff));

        // 비율은 심각÷주의 — 어긋남은 CEIL 의 올림(1칸 미만)과 경계 동점 칸 수로 설명되는 만큼만 허용한다.
        long tieExtra = Math.max(severe - severeCutoff, zones.size() - cautionCutoff);
        double ratioSlack = (1 + tieExtra) / (total * cautionPct / 100.0);
        assertThat((double) severe / zones.size())
                .as("심각이 표시 구역의 %d/%d 이어야 한다 (표시 %d칸 중 %d칸)", severePct, cautionPct, zones.size(), severe)
                .isCloseTo((double) severePct / cautionPct, within(ratioSlack));

        // ② 등급 — 표시 3종 안이고 '양호' 는 한 칸도 없다.
        assertThat(zones).extracting(HotspotZoneVO::getGrade)
                .as("표시 등급은 심각·경고·주의 3종뿐이다")
                .isSubsetOf("SEVERE", "WARN", "CAUTION")
                .doesNotContain("NORMAL");
        assertThat(zones).extracting(HotspotZoneVO::getGradeNm).doesNotContain("양호");
    }

    @Test
    @DisplayName("16. rnk — 하한 필터 뒤에도 1 부터 연속이다 (TSK-4172 D2)")
    void rankIsContiguousAfterFilter() {
        // 🔴 필터가 LIMIT·순위 부여 '앞' 에 걸리는지를 보는 단언이다 — 뒤에 걸리면 걸러진 자리가
        //    구멍으로 남아 rnk 가 1,2,5,… 처럼 튄다(화면의 순위표가 그대로 어긋난다).
        List<HotspotZoneVO> zones = analyzeOk(fullPeriod()).getZones();
        assertThat(zones).isNotEmpty();

        Integer[] expected = new Integer[zones.size()];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = i + 1;
        }
        assertThat(zones).extracting(HotspotZoneVO::getRnk)
                .as("rnk 는 1..%d 과 정확히 같아야 한다 (하한 필터가 순위 부여 앞에 걸린다)", zones.size())
                .containsExactly(expected);
    }

    @Test
    @DisplayName("17. 🔴 limit 미지정 = 전량 — 아무리 큰 limit 과 결과가 같고, 작은 limit 보다 많다 (TSK-4172 D8)")
    void omittedLimitMeansNoCeiling() {
        int all = analyzeOk(fullPeriod()).getZones().size();
        int wide = analyzeOk(withLimit(WIDE_LIMIT)).getZones().size();
        int small = analyzeOk(withLimit(SMALL_LIMIT)).getZones().size();

        // 🔴 '전량이 30보다 크다' 로 단언하지 않는다 — 전량 칸 수는 적재분·가중치에 달려 있어
        //    데이터가 바뀌면 곧 깨진다. 데이터와 무관한 두 성질만 본다.
        assertThat(all)
                .as("limit 을 생략하면 전량이다 — 상한이 없으므로 limit=%d 과 같아야 한다 "
                        + "(종전처럼 기본값 15 로 잘리면 여기서 걸린다)", WIDE_LIMIT)
                .isEqualTo(wide);
        assertThat(all)
                .as("전량(%d칸)이 limit=%d(%d칸)보다 많아야 '전량' 이라는 말이 성립한다",
                        all, SMALL_LIMIT, small)
                .isGreaterThan(SMALL_LIMIT);
        assertThat(small).as("작은 limit 은 그만큼만 돌려준다").isEqualTo(SMALL_LIMIT);
    }

    // ────────────────────────────────────────────────────────────── 18~21. 사용자 입력 가중치 (TSK-4306)

    @Test
    @DisplayName("18. 입력 80/20 — weights·scoreFormula 에 반영되고 설정값 조회와 점수가 다르다")
    void userWeightsAreAppliedToWeightsFormulaAndScores() {
        HotspotAnalysisResultVO base = analyzeOk(fullPeriod());
        HotspotAnalysisResultVO adjusted = analyzeOk(withWeights("80", "20"));

        assertThat(adjusted.getWeights()).extracting(AnalysisWeightVO::getVrblCd)
                .containsExactly(VRBL_COMPLAINT, VRBL_ENFORCE);
        assertThat(adjusted.getWeights().get(0).getStngVl()).isEqualByComparingTo("80");
        assertThat(adjusted.getWeights().get(1).getStngVl()).isEqualByComparingTo("20");
        assertThat(adjusted.getScoreFormula()).isEqualTo(FORMULA_USER_80_20);

        assertThat(adjusted.getZones()).as("80/20 에서도 주의 이상 구역이 있어야 비교가 성립한다").isNotEmpty();
        assertZoneContract(adjusted.getZones(), "0.8", "0.2");
        assertThat(scoresOf(adjusted))
                .as("입력 가중치가 점수에 닿지 않으면 설정값 조회와 같아진다")
                .isNotEqualTo(scoresOf(base));
    }

    @Test
    @DisplayName("19. 입력 합계 99 · 한쪽만 — 400 (조용히 재정규화·추정하지 않는다)")
    void invalidUserWeightsReturn400() {
        assertThat(statusOf(withWeights("60", "39"))).as("합계 99").isEqualTo(400);

        Map<String, Object> cvlcptOnly = fullPeriod();
        cvlcptOnly.put("cvlcptWgvl", "80");
        assertThat(statusOf(cvlcptOnly)).as("민원만").isEqualTo(400);

        Map<String, Object> crdnOnly = fullPeriod();
        crdnOnly.put("crdnWgvl", "20");
        assertThat(statusOf(crdnOnly)).as("단속만").isEqualTo(400);
    }

    @Test
    @DisplayName("20. 입력 80/20 조회 — 설정 표·변경 이력에 아무것도 쓰지 않는다")
    void userWeightsDoNotTouchSettings() {
        String settingsSql = "SELECT * FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd";
        String historySql = "SELECT count(*) FROM parking.tb_anls_vrbl_hstry";
        List<Map<String, Object>> settingsBefore = jdbcTemplate.queryForList(settingsSql);
        Long historyBefore = jdbcTemplate.queryForObject(historySql, Long.class);

        analyzeOk(withWeights("80", "20"));

        assertThat(jdbcTemplate.queryForList(settingsSql)).isEqualTo(settingsBefore);
        assertThat(jdbcTemplate.queryForObject(historySql, Long.class)).isEqualTo(historyBefore);
    }

    @Test
    @DisplayName("21. 숫자가 아닌 입력은 400 · 0E-999999999 + 100 은 200 — 500 이 아니다")
    void malformedAndExtremeScaleUserWeightsAreNot500() {
        assertThat(statusOf(withWeights("abc", "100"))).as("바인딩 실패").isEqualTo(400);

        // 🔴 정규화 전에 더하면 자릿수를 맞추느라 자원을 소진하거나 ArithmeticException 이 난다
        assertThat(statusOf(withWeights("0E-999999999", "100"))).as("지수 표기 0").isEqualTo(200);
    }

    @Test
    @DisplayName("22. wgvlAjmtYn — 입력 없음 N · 설정과 같은 입력 N · 다른 입력 Y")
    void adjustmentFlagFollowsInputAgainstSettings() {
        HotspotAnalysisResultVO base = analyzeOk(fullPeriod());
        assertThat(base.getWgvlAjmtYn()).as("입력 없음").isEqualTo("N");

        // 🔴 설정값을 상수로 박지 않는다 — 응답이 되비춘 현재 설정값을 그대로 다시 보낸다
        BigDecimal cvlcpt = base.getWeights().get(0).getStngVl();
        BigDecimal crdn = base.getWeights().get(1).getStngVl();
        assertThat(analyzeOk(withWeights(cvlcpt.toPlainString(), crdn.toPlainString())).getWgvlAjmtYn())
                .as("설정과 같은 입력").isEqualTo("N");

        // 설정과 반드시 다른 값 — 민원을 1 옮긴다(설정이 100/0 쪽이면 반대로)
        BigDecimal step = cvlcpt.compareTo(BigDecimal.valueOf(100)) >= 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        assertThat(analyzeOk(withWeights(cvlcpt.add(step).toPlainString(), crdn.subtract(step).toPlainString()))
                .getWgvlAjmtYn())
                .as("다른 입력").isEqualTo("Y");
    }

    // ────────────────────────────────────────────────────────────── 22~27. 등급 비율 일시 입력 (TSK-4325)

    @Test
    @DisplayName("22. vrblVls 등급 비율 — gradeCriteria·적용 내역·등급별 건수가 입력값을 따르고 조회 범위(CEIL)도 함께 넓어진다")
    void gradeBoundaryInputIsAppliedToGradesAndRange() {
        BigDecimal[] cur = gradeBoundaries();
        int severe = cur[0].intValueExact();
        int warn = cur[1].intValueExact();
        int caution = cur[2].intValueExact() + 10;
        assertThat(caution).as("전제 — 현재 주의 비율 + 10 이 100 이하").isLessThanOrEqualTo(100);

        HotspotAnalysisResultVO base = analyzeOk(fullPeriod());
        HotspotAnalysisResultVO adjusted = analyzeOk(withVrblVls(bndryJson(severe, warn, caution)));

        assertThat(adjusted.getGradeCriteria()).extracting(GradeCriterionVO::getMaxRankPct)
                .containsExactly(severe, warn, caution);
        assertThat(gradeCount(adjusted, "SEVERE")).as("심각 비율 그대로").isEqualTo(gradeCount(base, "SEVERE"));
        assertThat(gradeCount(adjusted, "WARN")).as("경고 비율 그대로").isEqualTo(gradeCount(base, "WARN"));
        assertThat(gradeCount(adjusted, "CAUTION")).as("주의 비율만 넓어졌다").isGreaterThan(gradeCount(base, "CAUTION"));
        // 🔴 매퍼 CEIL 과 gradeOf 가 다른 값을 받으면 양호 구역이 응답에 실린다
        assertThat(adjusted.getZones()).extracting(HotspotZoneVO::getGrade).doesNotContain("NORMAL");

        assertThat(adjusted.getVrblAjmt()).containsEntry("ajmtYn", "Y");
        assertThat(aplcnVlOf(adjusted, "GRD_BNDRY3")).isEqualByComparingTo(BigDecimal.valueOf(caution));
        assertThat(stngVlOf(adjusted, "GRD_BNDRY3")).isEqualByComparingTo(cur[2]);
        assertThat(aplcnVlOf(adjusted, "GRD_BNDRY1")).as("조정 안 한 행도 담는다").isEqualByComparingTo(cur[0]);
    }

    @Test
    @DisplayName("23. vrblVls 생략 · 설정과 같은 값(60.00 형) — 설정값대로이고 ajmtYn 은 N 이다 (D11)")
    void omittedOrSameGradeBoundaryIsNotAdjustment() {
        BigDecimal[] cur = gradeBoundaries();
        HotspotAnalysisResultVO base = analyzeOk(fullPeriod());

        assertThat(base.getGradeCriteria()).extracting(GradeCriterionVO::getMaxRankPct)
                .containsExactly(cur[0].intValueExact(), cur[1].intValueExact(), cur[2].intValueExact());
        assertThat(base.getVrblAjmt()).containsEntry("ajmtYn", "N");
        assertThat(vrblsOf(base)).extracting(v -> v.get("vrblCd"))
                .as("D2 — 가중치는 담지 않는다").containsExactly("GRD_BNDRY1", "GRD_BNDRY2", "GRD_BNDRY3");

        String sameWithScale = "{\"GRD_BNDRY1\":" + cur[0].setScale(2) + ",\"GRD_BNDRY2\":" + cur[1].setScale(2)
                + ",\"GRD_BNDRY3\":" + cur[2].setScale(2) + "}";
        HotspotAnalysisResultVO same = analyzeOk(withVrblVls(sameWithScale));
        assertThat(same.getVrblAjmt()).containsEntry("ajmtYn", "N");
        assertThat(same.getZones()).hasSameSizeAs(base.getZones());

        assertThat(analyzeOk(withWeights("80", "20")).getVrblAjmt())
                .as("가중치만 조정 — vrblAjmt 는 N 이다 (D10)").containsEntry("ajmtYn", "N");
    }

    @Test
    @DisplayName("24. vrblVls 위반 — 역전·설정과 합친 역전·목록 밖·범위 밖·소수·null·형식 오류 전부 400")
    void invalidGradeBoundaryInputReturns400() {
        BigDecimal[] cur = gradeBoundaries();
        int caution = cur[2].intValueExact();

        assertThat(statusOf(withVrblVls(bndryJson(20, 10, 30)))).as("역전").isEqualTo(400);
        assertThat(statusOf(withVrblVls("{\"GRD_BNDRY1\":" + caution + "}"))).as("설정값과 합쳐 역전").isEqualTo(400);
        assertThat(statusOf(withVrblVls("{\"COMPLAINT\":50}"))).as("D2 — 가중치는 대상이 아니다").isEqualTo(400);
        assertThat(statusOf(withVrblVls("{\"NOPE\":1}"))).as("모르는 코드").isEqualTo(400);
        assertThat(statusOf(withVrblVls(bndryJson(1, 2, 101)))).as("범위 밖").isEqualTo(400);
        assertThat(statusOf(withVrblVls("{\"GRD_BNDRY1\":1.5}"))).as("정수 전용").isEqualTo(400);
        assertThat(statusOf(withVrblVls("{\"GRD_BNDRY1\":null}"))).as("null 값").isEqualTo(400);
        assertThat(statusOf(withVrblVls("not-json"))).as("형식 오류").isEqualTo(400);
    }

    @Test
    @DisplayName("25. vrblVls 조회 — 설정 표·변경 이력에 아무것도 쓰지 않는다 (D8)")
    void gradeBoundaryInputDoesNotTouchSettings() {
        String settingsSql = "SELECT * FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd";
        String historySql = "SELECT count(*) FROM parking.tb_anls_vrbl_hstry";
        List<Map<String, Object>> settingsBefore = jdbcTemplate.queryForList(settingsSql);
        Long historyBefore = jdbcTemplate.queryForObject(historySql, Long.class);

        BigDecimal[] cur = gradeBoundaries();
        analyzeOk(withVrblVls(bndryJson(cur[0].intValueExact(), cur[1].intValueExact(), cur[2].intValueExact() + 1)));

        assertThat(jdbcTemplate.queryForList(settingsSql)).isEqualTo(settingsBefore);
        assertThat(jdbcTemplate.queryForObject(historySql, Long.class)).isEqualTo(historyBefore);
    }

    @Test
    @DisplayName("26. 🔴 쿼리 vrblVlsMap[KEY]=값 은 값으로 먹지 않는다 — 함께 온 vrblVls 도 무시되지 않는다 (Step 1)")
    void vrblVlsMapQueryIsNotBound() {
        BigDecimal[] cur = gradeBoundaries();
        int severe = cur[0].intValueExact();
        int warn = cur[1].intValueExact();
        int caution = cur[2].intValueExact();
        int bound = caution + 3;
        String period = "?bgngYmd=" + queryBgngYmd + "&endYmd=" + dataEndYmd;
        String mapParam = "&vrblVlsMap%5BGRD_BNDRY3%5D=" + bound;

        // 🔴 200 을 단언한다 — 400 을 허용하면 바인딩과 무관한 400(방화벽 등)에 공허하게 통과한다.
        // 컨트롤러 @InitBinder 가 vrblVlsMap* 를 금지 필드로 버려 값이 무시된다(없으면 500).
        ResponseEntity<String> only = getRaw(period + mapParam);
        assertThat(only.getStatusCode().value()).as("금지 필드는 무시되고 200 — 500 이면 @InitBinder 가 빠졌다").isEqualTo(200);
        assertThat(only.getBody()).as("값이 먹으면 주의 비율이 바뀐다")
                .contains("\"maxRankPct\":" + caution)
                .doesNotContain("\"maxRankPct\":" + bound)
                .contains("\"ajmtYn\":\"N\"");

        String json = java.net.URLEncoder.encode(bndryJson(severe, warn, caution + 1),
                java.nio.charset.StandardCharsets.UTF_8);
        ResponseEntity<String> both = getRaw(period + mapParam + "&vrblVls=" + json);
        assertThat(both.getStatusCode().value()).isEqualTo(200);
        assertThat(both.getBody()).as("함께 온 vrblVls 가 적용된다 — vrblVlsMap 값이 아니다")
                .contains("\"maxRankPct\":" + (caution + 1))
                .doesNotContain("\"maxRankPct\":" + bound)
                .contains("\"ajmtYn\":\"Y\"");
    }

    @Test
    @DisplayName("27. Swagger — vrblVls 는 문서에 있고 vrblVlsMap 은 숨겨진다 (Step 1)")
    void swaggerHidesVrblVlsMap() {
        ResponseEntity<String> docs = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(docs.getStatusCode().value()).isEqualTo(200);
        assertThat(docs.getBody()).contains("\"name\":\"vrblVls\"").doesNotContain("vrblVlsMap");
    }

    // ────────────────────────────────────────────────────────────── 헬퍼

    /**
     * 대괄호를 인코딩한 쿼리를 그대로 보낸다 — URI 템플릿을 거치면 변수명으로 해석된다.
     * 🔴 루트 URI 끝 {@code /} 를 걷는다 — {@code context-path: /} 라 그대로 이으면 {@code //api/…} 가 되어
     * 방화벽(StrictHttpFirewall)이 바인딩과 무관한 400 을 낸다(이 케이스가 공허하게 통과한다).
     */
    private ResponseEntity<String> getRaw(String query) {
        String root = restTemplate.getRootUri().replaceAll("/+$", "");
        return restTemplate.exchange(URI.create(root + HOTSPOT_URL + query),
                HttpMethod.GET, null, String.class);
    }

    /** 보유 구간 전체 + 분석 계수 입력. */
    private Map<String, Object> withVrblVls(String vrblVls) {
        Map<String, Object> params = fullPeriod();
        params.put("vrblVls", vrblVls);
        return params;
    }

    private static String bndryJson(int severe, int warn, int caution) {
        return "{\"GRD_BNDRY1\":" + severe + ",\"GRD_BNDRY2\":" + warn + ",\"GRD_BNDRY3\":" + caution + "}";
    }

    private static long gradeCount(HotspotAnalysisResultVO result, String grade) {
        return result.getZones().stream().filter(zone -> grade.equals(zone.getGrade())).count();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vrblsOf(HotspotAnalysisResultVO result) {
        return (List<Map<String, Object>>) result.getVrblAjmt().get("vrbls");
    }

    private static BigDecimal aplcnVlOf(HotspotAnalysisResultVO result, String vrblCd) {
        return vrblValue(result, vrblCd, "aplcnVl");
    }

    private static BigDecimal stngVlOf(HotspotAnalysisResultVO result, String vrblCd) {
        return vrblValue(result, vrblCd, "stngVl");
    }

    private static BigDecimal vrblValue(HotspotAnalysisResultVO result, String vrblCd, String key) {
        return vrblsOf(result).stream()
                .filter(v -> vrblCd.equals(v.get("vrblCd")))
                .map(v -> new BigDecimal(String.valueOf(v.get(key))))
                .findFirst()
                .orElseThrow();
    }

    /** 보유 구간 전체 + 입력 가중치. */
    private Map<String, Object> withWeights(String cvlcptWgvl, String crdnWgvl) {
        Map<String, Object> params = fullPeriod();
        params.put("cvlcptWgvl", cvlcptWgvl);
        params.put("crdnWgvl", crdnWgvl);
        return params;
    }

    /** 점수 목록 비교용 — 스케일 차이로 다르게 보이지 않게 표기를 맞춘다. */
    private static List<String> scoresOf(HotspotAnalysisResultVO result) {
        return result.getZones().stream()
                .map(zone -> zone.getSnthsScr().stripTrailingZeros().toPlainString())
                .toList();
    }

    /** 정상 응답을 기대하는 호출. 상태코드까지 확인하고 바디를 돌려준다. */
    private HotspotAnalysisResultVO analyzeOk(Map<String, Object> params) {
        ResponseEntity<HotspotAnalysisResultVO> response = analyze(params);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ResponseEntity<HotspotAnalysisResultVO> analyze(Map<String, Object> params) {
        return restTemplate.exchange(uriTemplate(params), HttpMethod.GET, null,
                HotspotAnalysisResultVO.class, params);
    }

    /**
     * 실패가 예상되는 호출은 응답을 {@code String} 으로 받는다 — 에러 바디를 VO 로 역직렬화하려다
     * 터지는 것을 막는다({@code AnalysisWeightIntegrationTest} 와 같은 이유).
     */
    private int statusOf(Map<String, Object> params) {
        return restTemplate.exchange(uriTemplate(params), HttpMethod.GET, null, String.class, params)
                .getStatusCode().value();
    }

    /**
     * 쿼리스트링을 <b>URI 템플릿 + 변수</b>로 만든다. 값을 문자열로 이어 붙이면 {@code 제주시} 같은
     * 한글 파라미터가 인코딩되지 않아 환경에 따라 다른 요청이 나간다.
     */
    private static String uriTemplate(Map<String, Object> params) {
        StringBuilder uri = new StringBuilder(HOTSPOT_URL);
        String separator = "?";
        for (String key : params.keySet()) {
            uri.append(separator).append(key).append("={").append(key).append('}');
            separator = "&";
        }
        return uri.toString();
    }

    /** 필수 파라미터 두 개만 담은 가변 맵. 호출부가 지역·limit 을 덧붙인다. */
    private static Map<String, Object> period(String bgngYmd, String endYmd) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bgngYmd", bgngYmd);
        params.put("endYmd", endYmd);
        return params;
    }

    /** 원천 보유 구간 전체를 조회 기간으로 쓰는 파라미터. 결과를 보는 케이스는 전부 이것을 쓴다. */
    private Map<String, Object> fullPeriod() {
        return period(queryBgngYmd, dataEndYmd);
    }

    /**
     * 366일 상한({@code DateRanges.MAX_RANGE_DAYS})에 맞춰 시작일을 자른다.
     * 365 를 빼면 양끝 포함 366일이다. 구간이 1년 이내면 {@code bgngYmd} 를 그대로 돌려준다.
     */
    private static String clampToMaxRange(String bgngYmd, String endYmd) {
        LocalDate bgng = LocalDate.parse(bgngYmd, YMD);
        LocalDate floor = LocalDate.parse(endYmd, YMD).minusDays(MAX_RANGE_DAYS - 1);
        return bgng.isBefore(floor) ? floor.format(YMD) : bgngYmd;
    }

    private Map<String, Object> withLimit(int limit) {
        Map<String, Object> params = fullPeriod();
        params.put("limit", limit);
        return params;
    }

    /**
     * 목록이 지켜야 할 계약을 한 번에 확인한다 — 데이터가 어떻게 바뀌어도 성립하는 것만 담는다.
     * 빈 목록이면 아무것도 검사하지 않는다(0건은 오류가 아니다 — D11).
     */
    private static void assertZoneContract(List<HotspotZoneVO> zones, String cvlcptWgt, String crdnWgt) {
        for (int i = 0; i < zones.size(); i++) {
            HotspotZoneVO zone = zones.get(i);
            assertThat(zone.getRnk()).as("rnk 는 1 부터 빈틈없이 오름차순").isEqualTo(i + 1);
            assertThat(zone.getZoneNm()).as("구역명은 폴백까지 거쳐 최종적으로 비지 않는다").isNotBlank();
            assertThat(zone.getZoneNmSrc()).isNotBlank();
            assertThat(zone.getSnthsScr()).isNotNull();
            assertThat(zone.getMrkLat()).isNotNull();
            assertThat(zone.getMrkLot()).isNotNull();
            assertScoreSatisfiesNormalizedFormula(zone, cvlcptWgt, crdnWgt);
            assertGradeMatchesScore(zone);
            if (zone.getEmdNm() != null && !zone.getEmdNm().isBlank()) {
                assertThat(zone.getZoneNm())
                        .as("구역명(%s)이 읍면동명(%s)으로 시작해야 한다", zone.getZoneNm(), zone.getEmdNm())
                        .startsWith(zone.getEmdNm());
            }
            if (i > 0) {
                assertThat(zone.getSnthsScr())
                        .as("종합점수는 내림차순이어야 한다 (rnk %d)", zone.getRnk())
                        .isLessThanOrEqualTo(zones.get(i - 1).getSnthsScr());
            }
        }
    }

    /**
     * 종합점수의 <b>성질</b>을 확인한다 — 데이터·조건이 어떻게 바뀌어도 성립하는 것만 본다 (🔁 TSK-4321).
     * <ol>
     *   <li>조회 결과 1위 = 100 이라 점수는 <b>항상 0~100</b> 이다.</li>
     *   <li>계수가 살아 있는 지표의 건수가 모두 0 이면 점수도 0 이다(기여할 것이 없다).</li>
     *   <li>등급은 표시 3종이다.</li>
     * </ol>
     *
     * <p>산식 재현·1위 100·{@code limit} 독립은 케이스 11~14 가 전담한다.
     * 🔴 {@code HotspotReportIntegrationTest} 의 같은 이름 헬퍼와 <b>같은 방식</b>으로 유지한다.
     */
    private static void assertScoreSatisfiesNormalizedFormula(HotspotZoneVO zone,
                                                             String cvlcptWgt, String crdnWgt) {
        BigDecimal score = zone.getSnthsScr();
        assertThat(score)
                .as("구역 %s 의 점수는 0~100 이어야 한다", zone.getZoneNm())
                .isBetween(BigDecimal.ZERO, SCORE_MAX);

        boolean scoredCountIsZero = (isZeroCoefficient(cvlcptWgt) || zone.getCvlcptCnt() == 0)
                && (isZeroCoefficient(crdnWgt) || zone.getCrdnCnt() == 0);
        if (scoredCountIsZero) {
            assertThat(score)
                    .as("계수가 살아 있는 지표의 건수가 0 이면 점수도 0 이다 (구역 %s)", zone.getZoneNm())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        assertGradeMatchesScore(zone);
    }

    /** 계수 표기가 0 인지 — 0 배인 지표는 점수에 기여하지 않는다(산식에 없는 것과 같다). */
    private static boolean isZeroCoefficient(String coefficient) {
        return new BigDecimal(coefficient).signum() == 0;
    }

    /** 표시 등급 3종이고 코드와 이름이 맞는지 — 순위 비율 판정은 케이스 15 와 {@code HotspotGradeTest} 가 본다. */
    private static void assertGradeMatchesScore(HotspotZoneVO zone) {
        String expectedNm = switch (zone.getGrade()) {
            case "SEVERE" -> "심각";
            case "WARN" -> "경고";
            case "CAUTION" -> "주의";
            default -> throw new AssertionError("표시 대상이 아닌 등급: " + zone.getGrade());
        };
        assertThat(zone.getGradeNm()).isEqualTo(expectedNm);
    }

    private void disableIndicator(String vrblCd) {
        int updated = jdbcTemplate.update(
                "UPDATE parking.tb_anls_vrbl_stng SET use_yn = 'N' WHERE anls_type_cd = ? AND vrbl_cd = ?",
                HOTSPOT, vrblCd);
        assertThat(updated).as("HOTSPOT/%s 지표가 없다 — 마이그레이션 53 시드를 확인하라", vrblCd).isEqualTo(1);
    }

    /** 지표별 최댓값으로 나눈 가중합 — 케이스 12 의 기대값 계산용(가중치 60/40). */
    private static BigDecimal wgtedVl(HotspotZoneVO zone, BigDecimal maxCvlcpt, BigDecimal maxCrdn) {
        return new BigDecimal("0.6")
                .multiply(BigDecimal.valueOf(zone.getCvlcptCnt()).divide(maxCvlcpt, 10, RoundingMode.HALF_UP))
                .add(new BigDecimal("0.4")
                        .multiply(BigDecimal.valueOf(zone.getCrdnCnt()).divide(maxCrdn, 10, RoundingMode.HALF_UP)));
    }

    private int disabledIndicatorCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND use_yn = 'N'",
                Integer.class, HOTSPOT);
        return count == null ? 0 : count;
    }

    private int zoneNameCacheCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_zone_nm_cache", Integer.class);
        return count == null ? 0 : count;
    }

    private String emdNmOf(String emdCd) {
        return jdbcTemplate.queryForObject(
                "SELECT emd_nm FROM parking.tb_admdst WHERE emd_cd = ?", String.class, emdCd);
    }

    /** 하루치 원천 행 수(민원 + 단속). 케이스 4 의 "데이터는 있는데 군집이 안 생긴다"를 뒷받침한다. */
    private int sourceRowCount(String ymd) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT (SELECT count(*) FROM parking.tb_cvlcpt"
                        + "  WHERE cvlcpt_type_cd <> '99' AND lat IS NOT NULL"
                        + "    AND ocrn_dt >= to_date(?, 'YYYYMMDD') AND ocrn_dt < to_date(?, 'YYYYMMDD') + 1)"
                        + "     + (SELECT count(*) FROM parking.tb_crdn_dsctn"
                        + "  WHERE lat IS NOT NULL"
                        + "    AND crdn_dt >= to_date(?, 'YYYYMMDD') AND crdn_dt < to_date(?, 'YYYYMMDD') + 1)",
                Integer.class, ymd, ymd, ymd, ymd);
        return count == null ? 0 : count;
    }

    /**
     * 매퍼 {@code selectDataRange} 와 <b>같은 조건</b>으로 보유 구간 경계를 읽는다 (상수 박기 회피).
     * 조건이 갈리면 케이스 5 의 echo 단언이 이유 없이 깨지므로 매퍼 XML 과 함께 고친다.
     */
    private static String dataRangeSql() {
        return "SELECT to_char(LEAST(   (SELECT min(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                + "                     (SELECT min(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD'),"
                + "       to_char(GREATEST((SELECT max(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                + "                     (SELECT max(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD')";
    }

    /**
     * 좌표가 있는 원천 행이 <b>가장 적은 하루</b>(0건인 날은 애초에 후보에 없다).
     *
     * <p>케이스 4 가 "데이터는 있는데 군집이 (거의) 안 생긴다"를 보이기 위한 날이다 — 가장 얇은 날을
     * 고르는 것이 그 취지에 가장 가깝고, {@code sourceRowCount > 0} 을 SQL 이 보장한다.
     * 동률이면 이른 날짜를 택해 실행 간 결과가 흔들리지 않게 한다.
     */
    private static String sparsestDaySql() {
        return "SELECT to_char(d, 'YYYYMMDD') FROM ("
                + "  SELECT d, sum(n) AS cnt FROM ("
                + "    SELECT ocrn_dt::date AS d, count(*) AS n FROM parking.tb_cvlcpt"
                + "     WHERE cvlcpt_type_cd <> '99' AND lat IS NOT NULL GROUP BY 1"
                + "    UNION ALL"
                + "    SELECT crdn_dt::date, count(*) FROM parking.tb_crdn_dsctn"
                + "     WHERE lat IS NOT NULL GROUP BY 1"
                + "  ) t GROUP BY d"
                + ") u ORDER BY cnt, d LIMIT 1";
    }
}
