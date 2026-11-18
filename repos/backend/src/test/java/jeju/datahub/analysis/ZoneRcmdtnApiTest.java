package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.ZoneRcmdtnTestData.Cand;
import jeju.datahub.analysis.ZoneRcmdtnTestData.Src;
import jeju.datahub.analysis.ZoneRcmdtnTestData.Win;
import jeju.datahub.analysis.service.ZoneRcmdtnLotVO;
import jeju.datahub.analysis.service.ZoneRcmdtnResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnService;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 구역추천 API 통합 테스트 (TSK-4207 Step 5 — {@code GET /api/analysis/zone-recommendation[/zones]}).
 *
 * <h2>왜 {@code RANDOM_PORT} + {@link TestRestTemplate} 인가</h2>
 * 🔴 이 레포엔 <b>MockMvc 가 0건</b>이고 {@code spring-security-test} 도 넣지 않는다 — HTTP 경로를
 * 타야 하는 검증(쿼리 파라미터 바인딩 · 400 상태코드 · 응답 JSON 계약)은 모두 실제 포트로 부른다
 * ({@code FeeIntegrationTest} 선례). test 프로파일은 {@code app.auth.security-enabled=false} 라
 * permitAll 체인이 걸려 세션 쿠키 없이도 {@code GET} 이 통과한다.
 *
 * <h2>🔴 정리는 "내가 만든 id" 만 지운다 — 범위로 지우지 않는다</h2>
 * ⚠️ {@code RANDOM_PORT} 에서는 {@code @Transactional} 롤백이 <b>먹지 않는다</b>(요청이 별 스레드다).
 * 상세 조회는 호출 1회마다 {@code TB_ANLS_EXCN}·{@code TB_ANLS_RSLT} 에 1행씩 남기므로, 200 응답의
 * {@code excnId} 를 {@link #createdExcnIds} 에 모아 {@link #deleteOnlyWhatThisTestCreated()} 가
 * <b>그 id 만</b> 결과 → 실행 순(FK 방향)으로 지운다({@code ZoneRcmdtnShareIntegrationTest} 선례).
 * 🔴 {@code excn_id > 시작값} 같은 범위 DELETE 는 같은 시각의 수동 실행·다른 테스트가 만든 행까지 지운다
 * (공유주차제 실행도 같은 {@code RECOMMEND} 유형이다). 400 으로 끊긴 호출은 행을 만들지 않는다.
 *
 * <h2>🔴 날짜·읍면동·건수를 박지 않는다 — 로컬 데이터가 날짜째 옮겨진다</h2>
 * 종전에는 "2026-10-12 로컬 DB 실측"(이도이동 {@code E0≈14.90} · 1위 배정 218건 · 배정률 30.0% …)을
 * 기대값으로 박아 두었고, 단속 적재분이 옮겨진 날 코드 결함 없이 깨졌다. 지금은 ① 조회 기간을 DB 의
 * {@code min/max} 에서 읽고 ② 읍면동을 조건으로 고르며(후보 좌표가 충분한 동 중 민원이 가장 많은 동 — {@code busyEmd})
 * ③ 기대값을 {@link ZoneRcmdtnTestData} 의 독립 SQL·산식 재계산으로 낸다. 기준값도 상수가 아니라
 * 설정 테이블의 적용값과 비교한다. 폴백·판정 불가·창 부족 같은 분기는 <b>창을 계산해서</b> 만든다
 * (자료 시작일 이전 구간은 반드시 0건이다).
 * ⚠️ 조건에 맞는 대상이 없으면 실패가 아니라 <b>사유와 함께 건너뛴다</b>. 민원·단속이 통째로 비었으면
 * {@code @BeforeEach} 가 그 사실을 메시지로 드러내며 실패한다.
 *
 * <p>응답 바디의 {@code message} 문자열은 단언하지 않는다(devtools 유무로 갈린다) — 상태코드만 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TSK-4207 Step 5 — 구역추천 API · 실행이력·결과 적재")
class ZoneRcmdtnApiTest {

    private static final String DETAIL_URL = "/api/analysis/zone-recommendation";
    private static final String ZONES_URL = "/api/analysis/zone-recommendation/zones";

    /** 재계산(double) 대조 허용 오차 — 응답 반올림 폭의 절반 + 여유 */
    private static final double TOL_1 = 0.051;

    private static final String FALLBACK = ZoneRcmdtnTestData.WARN_TREND_BFR_FALLBACK;
    private static final String SHORT = ZoneRcmdtnTestData.WARN_TREND_WINDOW_SHORT;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 🔁 TSK-4259 — 시군구 결과 ↔ 읍면동 결과 합집합 대조용. 읍면동 수십 개를 HTTP 로 부르면 호출마다 실행 이력이
     * 1행씩 쌓이고 느리므로, 이력을 남기지 않는 순수 조회({@code selectZoneRcmdtn})를 직접 부른다.
     */
    @Autowired
    private ZoneRcmdtnService zoneRcmdtnService;

    /** 🔴 소수를 {@link BigDecimal} 로 읽는다 — 좌표를 double 로 읽으면 좌표 키가 독립 SQL 과 어긋난다 */
    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private ZoneRcmdtnTestData data;

    /** 민원 보유 구간(자르지 않은 원래 구간) */
    private Win cvRange;
    /** 단속 보유 구간 */
    private Win crdnRange;
    /** 민원 창 — 자료 시작일부터(상한 36개월). 🔴 1차 '이전' 블록이 반드시 0건이라 폴백이 보장된다 */
    private Win cv;
    /** 단속 창 — 단속 보유 구간(상한 36개월). 🔴 민원 창과 다른 구간이다 */
    private Win crdn;
    /** 설정 테이블의 적용값 — 후보 면수 하한 · 배정 반경 */
    private BigDecimal minCpcty;
    private BigDecimal rng;
    /** 조건으로 고른 읍면동 — NARROW 후보 좌표가 충분한({@code RANK_LOCS} 곳 이상 · 없으면 최다) 동 중 창 안 민원이 가장 많은 동({@code busyEmd}). 없으면 {@code null} */
    private String emd;

    /**
     * 🔴 이 테스트가 만든 실행 아이디. {@link #deleteOnlyWhatThisTestCreated()} 가 <b>이것만</b> 지운다.
     * JUnit 은 테스트 메서드마다 인스턴스를 새로 만들므로 케이스 간에 섞이지 않는다.
     */
    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    /**
     * 🔴 TSK-4249 — 조회 실행자({@code excutr_id})를 세션에서 정하므로 익명 GET 은 401 이다.
     * mock-login 세션({@link IntegrationLoginSupport#MOCK_ID})으로 부른다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void resolveWindowsAndTarget() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();   // 🔴 빠지면 cleanUp() 이 로그인 이력 전량을 지운다
        session = auth.mockSession();

        data = new ZoneRcmdtnTestData(jdbcTemplate);
        cvRange = data.cvRange();
        assertThat(cvRange)
                .as("주차 민원(parking.tb_cvlcpt · 유형 99 제외 · emd_cd 있음)이 비어 있다 — 적재분을 먼저 확인하라")
                .isNotNull();
        crdnRange = data.crdnRange();
        assertThat(crdnRange)
                .as("단속(parking.tb_crdn_dsctn · emd_cd 있음)이 비어 있다 — 적재분을 먼저 확인하라")
                .isNotNull();
        cv = ZoneRcmdtnTestData.head(cvRange);
        crdn = ZoneRcmdtnTestData.head(crdnRange);
        minCpcty = data.appliedVl("RECOMMEND", "SWTC_TRGT_MIN_CPCTY");
        rng = data.appliedVl("RECOMMEND", "ALTMNT_RNG_VL");
        assertThat(minCpcty).as("RECOMMEND 설정 행(SWTC_TRGT_MIN_CPCTY)이 없다 — 마이그레이션 84 를 확인하라").isNotNull();
        assertThat(rng).as("RECOMMEND 설정 행(ALTMNT_RNG_VL)이 없다 — 마이그레이션 84 를 확인하라").isNotNull();
        emd = data.busyEmd(cv, minCpcty, false, 2);
    }

    @AfterEach
    void deleteOnlyWhatThisTestCreated() {
        // 한 문장이 터져도 나머지를 마저 지운다 — 범위 DELETE 로 쓸어 담을 수 없으므로 남기면 손으로 지워야 한다.
        List<RuntimeException> failures = new ArrayList<>();
        // FK(tb_anls_rslt.excn_id → tb_anls_excn.excn_id) 때문에 결과를 먼저 지운다.
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdExcnIds.clear();
        // 🔴 failures 단언 앞 — 단언이 실패해도 mock-user·로그인 이력은 되돌린다
        auth.cleanUp();
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    // ───────────────────────── 상세 조회 (주력) ─────────────────────────

    @Test
    @DisplayName("상세 — 주차장 순위는 추세 반영 검토지수 내림차순이고 주차장(좌표)별 배정 민원이 독립 재계산과 같다")
    void detailRanksLotsByAssignedComplaints() {
        JsonNode body = getOk(detailUrl(targetEmd(), cv, crdn) + "&trgtTypeSe=NARROW");
        JsonNode lots = body.path("lots");

        assertThat(lots.size()).as("고른 동은 후보가 2개소 이상이다").isGreaterThanOrEqualTo(2);
        assertThat(lots.path(0).path("rnk").asInt()).as("1위 순위 번호").isEqualTo(1);
        assertThat(byLoc(lots, l -> l.path("asgnCvlcptCnt").asLong()))
                .as("주차장(좌표)별 배정 민원 = 최근접 배정의 독립 재계산")
                .isEqualTo(data.asgnByLoc(Src.CV, emd, cv, minCpcty, false, rng));
        assertThat(byLoc(lots, l -> l.path("asgnCrdnCnt").asLong()))
                .as("주차장(좌표)별 배정 단속 — 🔴 단속 창으로 센다")
                .isEqualTo(data.asgnByLoc(Src.CRDN, emd, crdn, minCpcty, false, rng));
        assertRankOrder(lots);
    }

    @Test
    @DisplayName("상세 — rnk 가 1..n(후보 전 개소)으로 빠짐없이 채워진다 (🔴 null 0건)")
    void detailFillsEveryRnk() {
        JsonNode lots = getOk(detailUrl(targetEmd(), cv, crdn)).path("lots");

        // 🔁 Step 16 — 증가율을 못 낸 주차장도 목록·지도에 나온다. 행 수는 독립 SQL 의 후보 개소 수다
        assertThat(lots.size()).as("후보 주차장 전 개소").isEqualTo((int) data.cand(emd, minCpcty, false).cnt());

        long cum = 0;
        for (int i = 0; i < lots.size(); i++) {
            JsonNode lot = lots.get(i);
            // 🔴 매퍼 ORDER BY 는 순서만 정한다 — 번호는 서비스 루프가 넣어야 한다.
            //    빠뜨리면 컴파일은 통과하고 이 칸만 null 이 된다.
            assertThat(lot.hasNonNull("rnk")).as("rnk 가 null 이 아니다: %s", lot.path("pkltNm").asText())
                    .isTrue();
            assertThat(lot.path("rnk").asInt()).as("rnk 는 1 부터 빠짐없이 이어진다").isEqualTo(i + 1);
            cum += lot.path("pkltCpcty").asLong();
            assertThat(lot.path("cumCpcty").decimalValue()).as("누적 면수")
                    .usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.valueOf(cum));
        }
    }

    @Test
    @DisplayName("상세 — zone 은 위치 3필드(emdCd·emdNm·sggNm)뿐이다 · 🔁 TSK-4340 읍면동 판정 제거 · lots[].reachNeed 없음")
    void detailZoneHasLocationOnly() {
        JsonNode zone = getOk(detailUrl(targetEmd(), cv, crdn)).path("zone");

        Map<String, Object> admdst = data.admdst(emd);
        assertThat(zone.path("emdCd").asText()).isEqualTo(emd);
        assertThat(zone.path("emdNm").asText()).isEqualTo(admdst.get("emd_nm"));
        assertThat(zone.path("sggNm").asText()).isEqualTo(admdst.get("sgg_nm"));
        assertKeys(zone);
    }

    /** 🔁 TSK-4340 — 읍면동 블록은 위치 3필드뿐이다(판정 7필드가 다시 생기면 깨진다) */
    private static void assertKeys(JsonNode zone) {
        List<String> keys = new ArrayList<>();
        zone.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).as("zone 키").containsExactlyInAnyOrder("emdCd", "emdNm", "sggNm");
    }

    @Test
    @DisplayName("상세 — 🔴 asgn 블록: 총건수 = 창 안 민원 수 · 배정 합 = 후보 반경 안 민원 수 · 배정률 = 그 비")
    void detailExposesAsgnCoverage() {
        // 🔴 이 블록을 빼면 순위가 민원 전체를 설명하는 것처럼 보인다.
        JsonNode asgn = getOk(detailUrl(targetEmd(), cv, crdn)).path("asgn");

        long tot = data.cvCountByEmd(cv).getOrDefault(emd, 0L);
        long asgnTot = data.asgnTotal(Src.CV, emd, cv, minCpcty, false, rng);
        assertThat(tot).as("고른 동은 창 안에 민원이 있다").isPositive();
        assertThat(asgn.path("cvlcptTot").asLong()).as("조회 구간 민원 총건수").isEqualTo(tot);
        assertThat(asgn.path("cvlcptAsgn").asLong()).as("배정된 민원 합").isEqualTo(asgnTot);
        assertThat(asgn.path("asgnRt").asDouble()).as("배정률(%)")
                .isCloseTo(asgnTot * 100.0 / tot, within(TOL_1));
    }

    @Test
    @DisplayName("🔁 TSK-4284 부분 달 창 — 재현 메타 months = PrdMonths.of(소수) · asgn.cvlcptTot = 직접 count")
    void detailUsesFractionalMonthsOnPartialMonthWindow() throws Exception {
        Win w = ZoneRcmdtnShareIntegrationTest.partialMonthWindow(cvRange.bgng(), cvRange.end());
        Map<String, Long> direct = data.cvCountByEmd(w);
        // 🔴 먼저 — 창이 자료 밖이면 0 = 0 ÷ months 로 헛통과한다
        assertThat(ZoneRcmdtnTestData.sum(direct)).as("전제: 부분 달 창 %s ~ %s 에 민원이 있어야 한다",
                w.bgng(), w.end()).isPositive();
        // 창 안 민원 최다 읍면동(동률은 코드 오름차순) — 날짜·읍면동을 박지 않는다
        String target = direct.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .findFirst().orElseThrow().getKey();
        long cnt = direct.get(target);

        BigDecimal months = w.months();
        assertThat(months.stripTrailingZeros().scale()).as("🔴 부분 달이라 정수가 아니다: %s", months).isPositive();

        JsonNode body = getOk(detailUrl(target, w, crdn));
        assertThat(body.path("asgn").path("cvlcptTot").asLong()).as("asgn.cvlcptTot = 직접 count").isEqualTo(cnt);
        // 🔁 TSK-4340 — zone.cvlcptMmAvg(C0)는 판정과 함께 응답에서 빠졌다

        // 재현 메타 — 응답에는 months 가 실리지 않는다(anls_param_json 에 굳는다)
        JsonNode param = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?", String.class,
                body.path("excnId").asLong()));
        assertThat(param.path("months").decimalValue()).as("anls_param_json.months = PrdMonths.of")
                .usingComparator(BigDecimal::compareTo).isEqualTo(months);
        assertThat(param.path("bgngYmd").asText()).isEqualTo(w.bgngYmd());
        assertThat(param.path("endYmd").asText()).isEqualTo(w.endYmd());
    }

    @Test
    @DisplayName("TSK-4276 ⑤ 상세 — exclLots 배열이 있고 각 행 exclRsnCd 는 4종 중 하나 · 후보와 겹치지 않고 합 = 동 안 전체")
    void detailCarriesExclLots() {
        JsonNode body = getOk(detailUrl(targetEmd(), cv, crdn) + "&trgtTypeSe=WIDE");
        JsonNode excl = body.path("exclLots");

        assertThat(excl.isArray()).as("exclLots 는 배열이다(없으면 빈 배열)").isTrue();
        Set<Long> lotIds = new LinkedHashSet<>(ids(body.path("lots")));
        Set<Long> exclIds = new LinkedHashSet<>();
        for (JsonNode x : excl) {
            assertThat(x.path("exclRsnCd").asText()).as("사유 코드 — %s", x.path("pkltNm").asText())
                    .isIn("PAID", "CPCTY_LACK", "PRVT", "TYPE_EXCL");
            assertThat(x.hasNonNull("lat") && x.hasNonNull("lot")).as("좌표가 있다").isTrue();
            assertThat(x.has("rnk")).as("🔴 순위가 없다 — 후보가 아니다").isFalse();
            assertThat(exclIds.add(x.path("pkltId").asLong())).as("중복 %s", x.path("pkltId")).isTrue();
        }
        assertThat(exclIds).as("후보와 후보 밖이 겹친다").doesNotContainAnyElementsOf(lotIds);
        Long alive = jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_pblpklt WHERE emd_cd = ?"
                + " AND extsh_dt IS NULL AND lat IS NOT NULL AND lot IS NOT NULL", Long.class, emd);
        assertThat((long) lotIds.size() + exclIds.size()).as("후보 + 후보 밖 = 동 안 살아 있는 좌표 보유 주차장(독립 SQL)")
                .isEqualTo(alive);
    }

    @Test
    @DisplayName("상세 — 정상 경로는 계수 폴백을 타지 않고(COEF_FALLBACK 없음) 응답 기준값 = 설정 테이블 적용값이다")
    void detailHasNoCoefWarningOnHappyPath() {
        JsonNode body = getOk(detailUrl(targetEmd(), cv, crdn));
        JsonNode warnings = body.path("warnings");

        assertThat(warnings.isArray()).isTrue();
        // 🔴 COEF_FALLBACK 이 보이면 RECOMMEND 설정 행이 지워졌거나 값이 0 이하다.
        assertThat(texts(warnings)).as("정상 경로의 warnings: %s", warnings).doesNotContain("COEF_FALLBACK");
        // 추세 경고는 창이 정한다 — 자료 시작일에서 출발하는 창은 1차 '이전' 이 자료 시작 전이라 폴백이다
        assertThat(trendWarnings(body)).isEqualTo(expectedTrendWarnings(emd, cv, false));

        JsonNode crtr = body.path("crtrVls");
        assertThat(crtr.path("altmntRngVl").decimalValue()).usingComparator(BigDecimal::compareTo).isEqualTo(rng);
        assertThat(crtr.path("swtcTrgtMinCpcty").decimalValue()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(minCpcty);
        // 🔁 TSK-4340 — 판정 계수 3칸은 crtrVls 에서 빠졌다
        for (String gone : List.of("stsfRtThrsh", "cvlcptMmCrtrVl", "ilglCrtrVl", "pcshrCrtrVl", "paidDmndRt")) {
            assertThat(crtr.has(gone)).as("crtrVls.%s 없음", gone).isFalse();
        }
    }

    @Test
    @DisplayName("상세 — 🔁 추세: 블록 건수 = 실제로 쓴 블록의 독립 재계산 · 증가율 = 블록 건수에서 재계산 · trendMm 합 = 최근 블록")
    void detailExposesTrendOfEveryLot() {
        assumeTrue(cv.trendOk(), "민원 보유 구간이 14일 미만이라 추세 블록을 만들 수 없다");
        JsonNode body = getOk(detailUrl(targetEmd(), cv, crdn));
        JsonNode lots = body.path("lots");

        // 🔴 자료 시작일에서 출발하는 창 → 1차 '이전' 이 자료 시작 전 → 폴백(반 분할)
        assertThat(trendWarnings(body)).containsExactly(FALLBACK);
        assertTrend(body, emd, cv, false);

        // 🔴 '3m' 접미가 붙은 옛 키가 응답에 남아 있지 않다 — 남으면 프론트가 어느 것을 읽는지 갈린다
        List<String> lotKeys = new ArrayList<>();
        lots.path(0).fieldNames().forEachRemaining(lotKeys::add);
        assertThat(lotKeys).isNotEmpty().noneSatisfy(k -> assertThat(k).endsWith("3m"));

        // 🔁 Step 16 — 작은 분모 행도 응답 목록에 있다. 🔴 증가율만 null 이고 순위·면수·추이는 그대로다
        int minTrendBase = body.path("crtrVls").path("minTrendBase").asInt();
        int nullIncrs = 0;
        int belowBase = 0;
        for (JsonNode lot : lots) {
            if (!lot.hasNonNull("cvlcptIncrsRt")) {
                nullIncrs++;
            }
            if (lot.path("asgnCvlcptCntBfr").asLong() < minTrendBase) {
                belowBase++;
            }
        }
        assertThat(nullIncrs).as("🔴 증가율이 비는 행 수 = 분모가 하한(%d) 미만인 행 수", minTrendBase)
                .isEqualTo(belowBase);
        assertThat(lots.size()).as("🔴 그 행들을 걷어내지 않는다 — 전 후보다")
                .isEqualTo((int) data.cand(emd, minCpcty, false).cnt());
    }

    @Test
    @DisplayName("상세 — 🔴 trendDayCnt: 폴백이면 D/2 · D=13 은 TREND_WINDOW_SHORT(null) · 직전 기간에 민원이 있으면 1차라 D")
    void detailCarriesTrendDayCntAndWarnsOnShortWindow() {
        assumeTrue(cv.trendOk(), "민원 보유 구간이 14일 미만이라 추세 블록을 만들 수 없다");
        JsonNode body = getOk(detailUrl(targetEmd(), cv, crdn));
        // 증가율 분모 하한 — 설정도 데이터도 아닌 계산기의 코드 상수(10)다
        assertThat(body.path("crtrVls").path("minTrendBase").asInt()).isEqualTo(10);
        // 🔁 Step 13 — 적용된 블록 '일수' 가 응답에 함께 실린다(없으면 증가율을 재현할 수 없다)
        assertThat(body.path("crtrVls").path("trendDayCnt").asInt())
                .as("자료 시작일에서 출발하는 창은 폴백이라 D/2 다").isEqualTo(cv.half());
        // 🔴 옛 '개월' 키가 응답에 남아 있지 않다 — 남으면 프론트가 어느 것을 읽는지 갈린다
        //    (리터럴을 적지 않는다 — 폐지된 이름은 코드에 한 글자도 남기지 않는 것이 Step 13 의 검증이다)
        List<String> crtrKeys = new ArrayList<>();
        body.path("crtrVls").fieldNames().forEachRemaining(crtrKeys::add);
        assertThat(crtrKeys).isNotEmpty().noneSatisfy(k -> assertThat(k).endsWith("Months"));

        // 🔴 블록이 7일 미만이면 비교가 성립하지 않는다 — 0 이 아니라 null 이고 경고가 뜬다
        //    (자료 시작일부터 13일 → half 6)
        Win d13 = new Win(cvRange.bgng(), cvRange.bgng().plusDays(12));
        JsonNode shortWin = getOk(detailUrl(emd, d13, crdn));
        assertThat(trendWarnings(shortWin)).containsExactly(SHORT);
        assertThat(shortWin.path("crtrVls").path("trendDayCnt").isNull())
                .as("🔴 0 이 아니라 null — '0일 블록' 이 아니라 '내지 않았다' 다").isTrue();
        assertThat(shortWin.path("lots").size()).as("🔴 창이 짧다고 목록을 비우지 않는다").isPositive();
        for (JsonNode lot : shortWin.path("lots")) {
            assertThat(lot.path("cvlcptIncrsRt").isNull()).isTrue();
            assertThat(lot.path("asgnCvlcptCntRcnt").isNull()).as("🔴 0 이 아니라 null").isTrue();
            assertThat(lot.path("asgnCvlcptCntBfr").isNull()).isTrue();
            assertThat(lot.path("trendMm").size()).isZero();
        }

        // 🔁 Step 15 의 존재 이유 — 자료 끝 31일 창은 직전 31일이 보유 구간 안이라 <b>1차</b>로 성립할 수 있다.
        //    🔴 그때 trendDayCnt 는 15 가 아니라 31 이라 보조줄의 '기간 31일' 과 증가율이 보는 구간이 일치한다.
        assumeTrue(cvRange.days() >= 62, "민원 보유 구간이 62일 미만이라 '31일 + 직전 31일' 을 만들 수 없다");
        Win recent = ZoneRcmdtnTestData.tail(cvRange, 31);
        JsonNode oneMonth = getOk(detailUrl(emd, recent, crdn));
        boolean fallback = assertTrend(oneMonth, emd, recent, false);
        assumeTrue(!fallback, "고른 읍면동의 직전 31일(" + recent.bfr().bgng() + " ~ " + recent.bfr().end()
                + ")에 반경 안 민원이 없다 — 1차가 성립하는 창이 아니다");
        assertThat(trendWarnings(oneMonth)).as("🔴 1차가 성립하면 TREND_BFR_FALLBACK 이 없다").isEmpty();
        assertThat(oneMonth.path("crtrVls").path("trendDayCnt").asInt())
                .as("🔴 D=31 그대로다 — 반 분할(15)이 아니다").isEqualTo(31);
        for (JsonNode lot : oneMonth.path("lots")) {
            assertThat(lot.path("asgnCvlcptCntRcnt").asLong())
                    .as("🔴 1차는 최근 = 선택 기간 전체").isEqualTo(lot.path("asgnCvlcptCnt").asLong());
        }
    }

    // ───────────────────────── 참고 목록 ─────────────────────────

    @Test
    @DisplayName("/zones — tb_admdst 전 행이고 원소는 {emdCd, emdNm, sggNm} 뿐이다(🔁 TSK-4340 판정 제거)")
    void zonesListEveryEmdWithLocationOnly() {
        JsonNode body = getOk(ZONES_URL + "?" + prd(cv, crdn) + "&trgtTypeSe=NARROW");

        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).as("🔴 읍면동 전부다").isEqualTo(data.admdstCount());
        List<String> emdCds = new ArrayList<>();
        for (JsonNode zone : body) {
            assertKeys(zone);
            emdCds.add(zone.path("emdCd").asText());
        }
        assertThat(emdCds).containsExactlyInAnyOrderElementsOf(data.emdCds());
    }

    @Test
    @DisplayName("/zones — 원천 0건이어도 목록에 남는다: 단속 자료 시작 전 창이어도 행 수는 그대로다")
    void zonesKeepRowsWithoutSource() {
        Win noCrdn = new Win(crdnRange.bgng().minusDays(60), crdnRange.bgng().minusDays(1));
        JsonNode body = getOk(ZONES_URL + "?" + prd(cv, noCrdn));

        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).as("단속 0건인 동이 목록에서 빠지지 않는다").isEqualTo(data.admdstCount());
    }

    // ───────────────────────── 400 ─────────────────────────

    @Test
    @DisplayName("400 — trgtTypeSe 화이트리스트 밖(WIDE2)은 400, 생략하면 NARROW 로 동작한다")
    void rejectsUnknownTrgtTypeSe() {
        String anyEmd = data.emdCds().get(0);
        // 🔴 검증 없이 흘리면 매퍼 <choose> 가 조용히 NARROW 로 떨어져 오타가 통과한다.
        assertThat(get(detailUrl(anyEmd, cv, crdn) + "&trgtTypeSe=WIDE2").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(ZONES_URL + "?" + prd(cv, crdn) + "&trgtTypeSe=WIDE2").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode omitted = getOk(detailUrl(anyEmd, cv, crdn));
        assertThat(omitted.path("crtrVls").path("trgtTypeSe").asText())
                .as("생략하면 NARROW").isEqualTo("NARROW");
    }

    @Test
    @DisplayName("400 — 한 창이 36개월을 넘으면 400(37개월) · 꼭 36개월은 통과 · 단속 기본 창은 막히지 않는다")
    void rejectsPeriodOverCap() {
        String anyEmd = data.emdCds().get(0);
        // 창은 날짜 계산으로 만든다 — 폭 검증은 데이터를 읽기 전에 끝난다
        LocalDate end = cv.end();
        Win cv37 = new Win(end.withDayOfMonth(1).minusMonths(36), end);
        Win cv36 = new Win(end.withDayOfMonth(1).minusMonths(35), end);
        Win crdn37 = new Win(crdn.end().withDayOfMonth(1).minusMonths(36), crdn.end());
        // 🔴 상한은 걸친 달 수다(D5) — 월평균 분모(months() = 비율 합)가 아니다
        assertThat(cv37.spanMonths()).isEqualTo(ZoneRcmdtnTestData.MAX_PRD_MONTHS + 1);

        assertThat(get(detailUrl(anyEmd, cv37, crdn)).getStatusCode())
                .as("민원 창 37개월").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(detailUrl(anyEmd, cv36, crdn)).getStatusCode())
                .as("🔴 경계 — 꼭 36개월은 통과한다").isEqualTo(HttpStatus.OK);

        // 단속 창도 따로 센다 — 민원 창이 짧아도 막힌다.
        assertThat(get(detailUrl(anyEmd, cv, crdn37)).getStatusCode())
                .as("단속 창 37개월").isEqualTo(HttpStatus.BAD_REQUEST);

        // 🔴 한쪽만 준 요청도 기본값으로 채워 센다 — 상한을 비껴가지 않는다.
        String tenYearsBeforeDefaultEnd = LocalDate
                .parse(ZoneRcmdtnService.DEFAULT_CRDN_END_YMD, ZoneRcmdtnTestData.YMD)
                .minusYears(10).format(ZoneRcmdtnTestData.YMD);
        assertThat(get(DETAIL_URL + "?emdCd=" + anyEmd + "&bgngYmd=" + cv.bgngYmd() + "&endYmd=" + cv.endYmd()
                + "&crdnBgngYmd=" + tenYearsBeforeDefaultEnd).getStatusCode())
                .as("단속 시작일만 준 10년 창").isEqualTo(HttpStatus.BAD_REQUEST);

        // 🔴 단속 기본 창(인터페이스 상수)은 통과해야 한다 — 막히면 평시 호출이 전부 400 이다.
        assertThat(get(DETAIL_URL + "?emdCd=" + anyEmd + "&bgngYmd=" + cv.bgngYmd() + "&endYmd=" + cv.endYmd())
                .getStatusCode()).as("단속 창 생략 = 기본 창").isEqualTo(HttpStatus.OK);
    }

    // ───────────────────────── 실행이력·결과 적재 ─────────────────────────

    @Test
    @DisplayName("🔴 적재 — 실행 1회 = TB_ANLS_EXCN 1행 + TB_ANLS_RSLT 1행, anls_param_json 에 "
            + "적용 계수가 굳는다(🔁 TSK-4340 판정 계수 없음)")
    void recordsOneExcnAndOneRsltWithFullCoefficients() throws Exception {
        long before = countRecommendExcn();
        JsonNode body = getOk(detailUrl(targetEmd(), cv, crdn) + "&trgtTypeSe=NARROW");

        assertThat(body.hasNonNull("excnId")).as("응답에 excnId 가 채워진다").isTrue();
        long excnId = body.path("excnId").asLong();
        assertThat(countRecommendExcn()).as("실행 1회 = 실행이력 1행").isEqualTo(before + 1);

        Integer rsltCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_rslt WHERE excn_id = ?", Integer.class, excnId);
        assertThat(rsltCnt).as("실행 1건 = 결과 1행 (excn_id UNIQUE)").isEqualTo(1);

        Map<String, Object> excn = jdbcTemplate.queryForMap("""
                SELECT anls_type_cd, excutr_id, emd_cd, sgg_nm, anls_bgng_ymd, anls_end_ymd, excn_stts_cd,
                       anls_param_json::text AS param_json
                  FROM parking.tb_anls_excn WHERE excn_id = ?
                """, excnId);
        assertThat(excn.get("anls_type_cd")).isEqualTo("RECOMMEND");
        assertThat(excn.get("excutr_id")).as("🔴 실행자 = 로그인 사용자(TSK-4249)").isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(excn.get("emd_cd")).as("🔴 emd_cd 를 채운다").isEqualTo(emd);
        assertThat(excn.get("sgg_nm")).as("sgg_nm 이 비면 스냅샷의 지역명이 사라진다")
                .isEqualTo(data.admdst(emd).get("sgg_nm"));
        assertThat(excn.get("anls_bgng_ymd").toString().trim()).isEqualTo(cv.bgngYmd());
        assertThat(excn.get("anls_end_ymd").toString().trim()).isEqualTo(cv.endYmd());
        assertThat(excn.get("excn_stts_cd")).isEqualTo("DONE");

        JsonNode param = objectMapper.readTree((String) excn.get("param_json"));
        // 조회 조건 — 🔴 단속 창이 민원 창과 달라 반드시 남아야 한다.
        assertThat(param.path("emdCd").asText()).isEqualTo(emd);
        assertThat(param.path("bgngYmd").asText()).isEqualTo(cv.bgngYmd());
        assertThat(param.path("endYmd").asText()).isEqualTo(cv.endYmd());
        assertThat(param.path("crdnBgngYmd").asText()).isEqualTo(crdn.bgngYmd());
        assertThat(param.path("crdnEndYmd").asText()).as("🔴 반열림 상한을 -1일 해 '포함' 종료일로 되돌린다")
                .isEqualTo(crdn.endYmd());
        // 🔁 TSK-4284 — 개월 수는 소수(달별 조회 비율 합)라 decimalValue 로 비교한다(D4)
        assertThat(param.path("crdnMonths").decimalValue()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(crdn.months());
        assertThat(param.path("months").decimalValue()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(cv.months());
        assertThat(param.path("trgtTypeSe").asText()).isEqualTo("NARROW");
        // 🔴 추세 창 — 없으면 "어느 블록과 어느 블록을 비교했는지" 가 남지 않는다(Step 9)
        //    🔁 Step 15 — 1차 4칸 + 폴백 4칸을 함께 굳힌다. 1차는 선택 기간 전체 ↔ 직전 같은 길이이고,
        //       폴백은 반 분할이다. 기대값은 민원 창에서 다시 파생한다.
        //    🔴 상한 칸은 '미포함' 이고, 폴백에서 D 가 홀수면 가운데 1일이 두 블록 사이에 떠 있다.
        assumeTrue(cv.trendOk(), "민원 보유 구간이 14일 미만이라 추세 창이 굳지 않는다");
        assertThat(param.path("trendFrom").asText()).isEqualTo(cv.from().toString());
        assertThat(param.path("trendToExcl").asText()).isEqualTo(cv.toExcl().toString());
        assertThat(param.path("bfrFrom").asText()).isEqualTo(cv.bfr().from().toString());
        assertThat(param.path("bfrToExcl").asText()).isEqualTo(cv.from().toString());
        assertThat(param.path("splitTrendFrom").asText()).isEqualTo(cv.splitRcnt().from().toString());
        assertThat(param.path("splitTrendToExcl").asText()).isEqualTo(cv.toExcl().toString());
        assertThat(param.path("splitBfrFrom").asText()).isEqualTo(cv.from().toString());
        assertThat(param.path("splitBfrToExcl").asText()).isEqualTo(cv.splitBfr().toExcl().toString());
        // 🔴 어느 쪽을 실제로 썼는지는 warnings 가 말한다 — 응답과 같은 목록이 굳어 있다
        assertThat(texts(param.path("warnings"))).isEqualTo(texts(body.path("warnings")));
        assertThat(trendWarnings(body)).as("자료 시작일에서 출발하는 창은 폴백이다").containsExactly(FALLBACK);

        // 적용 계수 — 🔁 TSK-4340 읍면동 판정 제거로 판정 계수·POLICY_SIM 2종·적발배수는 굳히지 않는다
        JsonNode cfc = param.path("crtrVls");
        for (String gone : List.of("pcshrCrtrVl", "paidDmndRt", "dsclMltp", "stsfRtThrsh", "cvlcptMmCrtrVl",
                "ilglCrtrVl")) {
            assertThat(cfc.has(gone)).as("anls_param_json.crtrVls.%s 없음", gone).isFalse();
        }
        assertThat(cfc.path("minTrendBase").asInt()).as("🔴 증가율 분모 하한도 남긴다 — 바꾸면 증가율이 달라진다")
                .isEqualTo(body.path("crtrVls").path("minTrendBase").asInt());
        // 🔁 Step 13 — 블록 일수도 남긴다. 파생 규칙이 바뀌면 같은 기간이 다른 길이로 갈린다
        assertThat(cfc.path("trendDayCnt").asInt()).as("🔴 적용 블록 길이(일수)")
                .isEqualTo(body.path("crtrVls").path("trendDayCnt").asInt()).isEqualTo(cv.half());
        // 응답 crtrVls 와 같은 공통 2종 + FEE 1종(대표)
        for (String key : List.of("altmntRngVl", "swtcTrgtMinCpcty", "feeCvlcptMmCrtrJeju")) {
            assertThat(cfc.hasNonNull(key)).as("anls_param_json.crtrVls.%s", key).isTrue();
            assertThat(cfc.path(key).decimalValue()).as("anls_param_json.crtrVls.%s = 응답 crtrVls.%s", key, key)
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(body.path("crtrVls").path(key).decimalValue());
        }

        // 결과 JSON — 🔴 객체로 감싼다(배열이 아니다). asgn 블록을 빼지 않는다. 응답과 같은 내용이다
        String rsltJson = jdbcTemplate.queryForObject(
                "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?",
                String.class, excnId);
        JsonNode rslt = objectMapper.readTree(rsltJson);
        assertThat(rslt.isObject()).isTrue();
        assertThat(rslt.path("zone").path("emdCd").asText()).isEqualTo(emd);
        assertThat(rslt.path("zone")).as("rslt_json.zone = 응답 zone(위치 3필드)").isEqualTo(body.path("zone"));
        assertThat(rslt.has("asgn")).isTrue();
        assertThat(rslt.path("asgn").path("cvlcptAsgn").asLong())
                .isEqualTo(body.path("asgn").path("cvlcptAsgn").asLong());
        assertThat(rslt.path("asgn").path("cvlcptTot").asLong())
                .isEqualTo(body.path("asgn").path("cvlcptTot").asLong());
        // 🔁 Step 16 — 적재되는 목록이 전 후보라 배정률과 행 수의 기준이 같다
        assertThat(ids(rslt.path("lots"))).as("적재된 목록 = 응답 목록(같은 순서)")
                .isEqualTo(ids(body.path("lots"))).isNotEmpty();
        assertThat(rslt.path("lots").path(0).path("rnk").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("적재 — 🔴 참고 목록(/zones)은 실행 이력을 남기지 않는다")
    void zonesDoNotRecordLedger() {
        long before = countRecommendExcn();
        getOk(ZONES_URL + "?" + prd(cv, crdn));

        assertThat(countRecommendExcn())
                .as("동을 고르려고 매번 부르는 경로다 — 여기서 적재하면 이력이 조회 로그가 된다")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("적재 — 400 으로 끊긴 요청은 두 표 모두 늘리지 않는다")
    void rejectedRequestLeavesNoLedgerRow() {
        long before = countRecommendExcn();
        long rsltBefore = countRslt();
        assertThat(get(detailUrl(data.emdCds().get(0), cv, crdn) + "&trgtTypeSe=WIDE2").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(countRecommendExcn()).isEqualTo(before);
        assertThat(countRslt()).isEqualTo(rsltBefore);
    }

    // ───────────────────────── 🔁 TSK-4259 — 시군구 모드 ─────────────────────────

    /** 시군구 모드 표본 — 행정구역 마스터의 시군구명(데이터가 아니라 마스터 상수다) */
    private static final String SGG = "제주시";

    @Test
    @DisplayName("TSK-4259 🔴 시군구 — (pkltId, 배정 민원, 배정 단속) 집합 = 그 시군구 각 읍면동 결과의 합집합 (D2 — 동 경계 유지)")
    void sggScopeEqualsUnionOfEmdScopes() {
        // 🔴 HTTP 가 아니라 서비스 순수 조회로 비교한다 — 읍면동마다 HTTP 로 부르면 실행 이력이 동 수만큼 쌓이고,
        //    이 단언이 보려는 것은 매퍼 SQL 의 배정 범위(동 경계)이지 HTTP 계약이 아니다(HTTP 계약은 아래 케이스들).
        //    읍면동은 그 시군구 중 후보 주차장이 있는 동이다 — 후보가 없는 동은 lots 가 비어 합집합에 보탤 것이 없다.
        List<String> emds = sggEmdCds();
        Map<String, Cand> cand = data.candByEmd(minCpcty, false);
        emds.retainAll(cand.keySet());
        assumeTrue(!emds.isEmpty(), SGG + " 에 NARROW 후보 주차장이 있는 읍면동이 없다");

        ZoneRcmdtnResultVO sgg = zoneRcmdtnService.selectZoneRcmdtn(null, SGG, cv.bgngYmd(), cv.endYmd(),
                crdn.bgngYmd(), crdn.endYmd(), null);

        Map<Long, String> union = new LinkedHashMap<>();
        Map<Long, String> emdOf = new LinkedHashMap<>();
        for (String e : emds) {
            ZoneRcmdtnResultVO r = zoneRcmdtnService.selectZoneRcmdtn(e, cv.bgngYmd(), cv.endYmd(),
                    crdn.bgngYmd(), crdn.endYmd(), null);
            for (ZoneRcmdtnLotVO l : r.getLots()) {
                assertThat(union.put(l.getPkltId(), asgnKey(l)))
                        .as("주차장 %d 가 두 읍면동 결과에 함께 나온다", l.getPkltId()).isNull();
                emdOf.put(l.getPkltId(), e);
            }
        }
        Map<Long, String> sggSet = new LinkedHashMap<>();
        for (ZoneRcmdtnLotVO l : sgg.getLots()) {
            assertThat(sggSet.put(l.getPkltId(), asgnKey(l))).as("시군구 결과에 주차장 %d 가 중복", l.getPkltId())
                    .isNull();
            assertThat(l.getEmdCd()).as("주차장 %d 의 읍면동 = 그 주차장이 나온 읍면동 실행", l.getPkltId())
                    .isEqualTo(emdOf.get(l.getPkltId()));
        }
        assertThat(sggSet).as("🔴 시군구 결과 = 읍면동 결과 합집합 — 어긋나면 배정이 동 경계를 넘었다(D2)")
                .isNotEmpty().isEqualTo(union);
    }

    @Test
    @DisplayName("TSK-4259 시군구 — rnk 1..N 연속 · 추세 반영 검토지수 비증가 (정렬 계약은 읍면동 모드와 같다 — D3)")
    void sggScopeOrdersByRvwScrDesc() {
        JsonNode lots = getOk(sggUrl(SGG, cv, crdn)).path("lots");
        assumeTrue(lots.size() >= 2, SGG + " 의 후보 주차장이 2개소 미만이다");

        for (int i = 0; i < lots.size(); i++) {
            assertThat(lots.get(i).path("rnk").asInt()).as("rnk 는 1 부터 빠짐없이 이어진다").isEqualTo(i + 1);
            if (i > 0) {
                // 🔁 TSK-4282 — 배정 민원이 아니라 추세 반영 검토지수가 비증가다
                assertThat(lots.get(i).path("incrsRfltRvwScr").decimalValue())
                        .as("%d위 추세 반영 검토지수는 앞 행 이하", i + 1)
                        .isLessThanOrEqualTo(lots.get(i - 1).path("incrsRfltRvwScr").decimalValue());
            }
        }
        assertRankOrder(lots);
    }

    @Test
    @DisplayName("TSK-4259 시군구 — scope=SGG · zone·asgn null · 전 행 cumCpcty null · emdNm 존재 (D4·D7·D8)")
    void sggScopeHasNoZoneJudgement() {
        JsonNode body = getOk(sggUrl(SGG, cv, crdn));
        JsonNode lots = body.path("lots");

        assertThat(body.path("scope").asText()).isEqualTo("SGG");
        assertThat(body.path("sggNm").asText()).isEqualTo(SGG);
        assertThat(body.hasNonNull("zone")).as("🔴 시군구 모드는 zone 이 없다(D4)").isFalse();
        assertThat(body.hasNonNull("asgn")).as("🔴 시군구 배정률은 없다(D4)").isFalse();
        assertThat(lots.size()).as("시군구 후보 전 개소").isPositive();

        List<String> sggEmds = sggEmdCds();
        for (JsonNode lot : lots) {
            String nm = lot.path("pkltNm").asText();
            assertThat(lot.hasNonNull("cumCpcty")).as("%s — 누적 면수 없음", nm).isFalse();
            assertThat(lot.has("reachNeed")).as("%s — 🔁 TSK-4340 reachNeed 없음", nm).isFalse();
            assertThat(lot.path("emdNm").asText()).as("%s — 행마다 읍면동명이 있다(D7)", nm).isNotBlank();
            assertThat(lot.path("emdCd").asText()).as("%s — %s 안의 읍면동이다", nm, SGG).isIn(sggEmds);
        }
    }

    @Test
    @DisplayName("TSK-4259 읍면동 — scope=EMD · zone/asgn/cumCpcty 종전대로 · lots[].emdCd = 요청 emdCd (D5)")
    void emdScopeUnchanged() {
        String e = targetEmd();
        JsonNode body = getOk(detailUrl(e, cv, crdn));
        Map<String, Object> admdst = data.admdst(e);

        assertThat(body.path("scope").asText()).isEqualTo("EMD");
        assertThat(body.path("sggNm").asText()).as("읍면동 모드의 sggNm = zone.sggNm").isEqualTo(admdst.get("sgg_nm"))
                .isEqualTo(body.path("zone").path("sggNm").asText());
        assertThat(body.path("zone").path("emdCd").asText()).isEqualTo(e);
        assertThat(body.hasNonNull("asgn")).as("배정 커버리지는 그대로 있다").isTrue();
        JsonNode lots = body.path("lots");
        assertThat(lots.size()).isPositive();
        long cum = 0;
        for (JsonNode lot : lots) {
            cum += lot.path("pkltCpcty").asLong();
            assertThat(lot.path("cumCpcty").decimalValue()).as("누적 면수는 종전대로 채운다")
                    .usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.valueOf(cum));
            assertThat(lot.has("reachNeed")).as("🔁 TSK-4340 reachNeed 없음").isFalse();
            assertThat(lot.path("emdCd").asText()).as("lots[].emdCd = 요청 emdCd").isEqualTo(e);
            assertThat(lot.path("emdNm").asText()).isEqualTo(admdst.get("emd_nm"));
        }
    }

    @Test
    @DisplayName("TSK-4259 400 — emdCd·sggNm 둘 다 / 둘 다 없음(공백 포함) → 400 · 이력 0 · 공백 emdCd + sggNm → 200 SGG (D6)")
    void rejectsBothOrNeitherScope() {
        String anyEmd = sggEmdCds().get(0);
        long before = countRecommendExcn();
        long rsltBefore = countRslt();

        assertThat(get(DETAIL_URL + "?emdCd=" + anyEmd + "&sggNm=" + SGG + "&" + prd(cv, crdn)).getStatusCode())
                .as("둘 다").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(DETAIL_URL + "?" + prd(cv, crdn)).getStatusCode())
                .as("둘 다 없음").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(DETAIL_URL + "?emdCd=&sggNm=&" + prd(cv, crdn)).getStatusCode())
                .as("🔴 둘 다 빈 문자열 = 없음").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(DETAIL_URL + "?emdCd= &sggNm= &" + prd(cv, crdn)).getStatusCode())
                .as("🔴 둘 다 공백 = 없음").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(countRecommendExcn()).as("400 은 실행 이력을 남기지 않는다").isEqualTo(before);
        assertThat(countRslt()).isEqualTo(rsltBefore);

        JsonNode blankEmd = getOk(DETAIL_URL + "?emdCd=&sggNm=" + SGG + "&" + prd(cv, crdn));
        assertThat(blankEmd.path("scope").asText()).as("🔴 공백 emdCd 는 '없음' — 시군구 모드다").isEqualTo("SGG");
        assertThat(blankEmd.path("sggNm").asText()).isEqualTo(SGG);
    }

    @Test
    @DisplayName("TSK-4259 400 — 없는 sggNm · 41자 sggNm → 400 · 이력 0 (D6)")
    void rejectsUnknownSggNm() {
        long before = countRecommendExcn();
        long rsltBefore = countRslt();
        String unknown = "없는시군구";
        assertThat(sggEmdCds(unknown)).as("전제 — 행정구역 마스터에 없는 시군구명").isEmpty();
        String tooLong = "가".repeat(41);

        assertThat(get(sggUrl(unknown, cv, crdn)).getStatusCode()).as("없는 시군구 — 빈 200 이 아니다")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(sggUrl(tooLong, cv, crdn)).getStatusCode()).as("41자 — 적재까지 가면 500 이다")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(countRecommendExcn()).isEqualTo(before);
        assertThat(countRslt()).isEqualTo(rsltBefore);
    }

    @Test
    @DisplayName("TSK-4259 404 — 없는 emdCd(sggNm 없음)는 종전대로 404 · 이력 0 (requireEmdCd → resolveScope 회귀)")
    void unknownEmdCdStill404() {
        String missing = "99999999";
        assertThat(data.emdCds()).as("전제 — 행정구역 마스터에 없는 읍면동 코드").doesNotContain(missing);
        long before = countRecommendExcn();
        long rsltBefore = countRslt();

        assertThat(get(detailUrl(missing, cv, crdn)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(countRecommendExcn()).isEqualTo(before);
        assertThat(countRslt()).isEqualTo(rsltBefore);
    }

    @Test
    @DisplayName("TSK-4259 적재 — 시군구 실행: sgg_nm = 요청값 · emd_cd NULL · param.scope=SGG · rslt.zone null")
    void recordsSggScopeLedger() throws Exception {
        long before = countRecommendExcn();
        JsonNode body = getOk(sggUrl(SGG, cv, crdn));
        assertThat(body.hasNonNull("excnId")).isTrue();
        long excnId = body.path("excnId").asLong();
        assertThat(countRecommendExcn()).as("실행 1회 = 실행이력 1행").isEqualTo(before + 1);

        Map<String, Object> excn = jdbcTemplate.queryForMap("""
                SELECT excutr_id, emd_cd, sgg_nm, excn_stts_cd, anls_param_json::text AS param_json
                  FROM parking.tb_anls_excn WHERE excn_id = ?
                """, excnId);
        assertThat(excn.get("excutr_id")).isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(excn.get("emd_cd")).as("🔴 시군구 모드는 emd_cd NULL").isNull();
        assertThat(excn.get("sgg_nm")).isEqualTo(SGG);
        assertThat(excn.get("excn_stts_cd")).isEqualTo("DONE");

        JsonNode param = objectMapper.readTree((String) excn.get("param_json"));
        assertThat(param.path("scope").asText()).isEqualTo("SGG");
        assertThat(param.path("sggNm").asText()).isEqualTo(SGG);
        assertThat(param.hasNonNull("emdCd")).as("시군구 모드는 emdCd 를 싣지 않는다").isFalse();

        JsonNode rslt = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class, excnId));
        assertThat(rslt.hasNonNull("zone")).as("🔴 결과 JSON 의 zone 은 null").isFalse();
        assertThat(rslt.hasNonNull("asgn")).isFalse();
        assertThat(ids(rslt.path("lots"))).as("적재된 목록 = 응답 목록(같은 순서)")
                .isEqualTo(ids(body.path("lots"))).isNotEmpty();
    }

    /** 시군구 모드 상세 URL */
    private static String sggUrl(String sggNm, Win cvWin, Win crdnWin) {
        return DETAIL_URL + "?sggNm=" + sggNm + "&" + prd(cvWin, crdnWin);
    }

    /** 표본 시군구의 읍면동 코드(행정구역 마스터) */
    private List<String> sggEmdCds() {
        return sggEmdCds(SGG);
    }

    private List<String> sggEmdCds(String sggNm) {
        return new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT emd_cd FROM parking.tb_admdst WHERE sgg_nm = ? ORDER BY emd_cd", String.class, sggNm));
    }

    /** 합집합 대조 키 — 배정 민원 / 배정 단속 */
    private static String asgnKey(ZoneRcmdtnLotVO l) {
        return l.getAsgnCvlcptCnt() + "/" + l.getAsgnCrdnCnt();
    }

    // ───────────────────────── 기대값 도구 ─────────────────────────

    /** 고른 읍면동이 있어야 도는 테스트의 전제 */
    private String targetEmd() {
        assumeTrue(emd != null, "NARROW 후보 좌표가 2곳 이상이면서 민원이 있는 읍면동이 없다");
        return emd;
    }

    /** 민원 창 + 단속 창 쿼리스트링 */
    private static String prd(Win cvWin, Win crdnWin) {
        return "bgngYmd=" + cvWin.bgngYmd() + "&endYmd=" + cvWin.endYmd()
                + "&crdnBgngYmd=" + crdnWin.bgngYmd() + "&crdnEndYmd=" + crdnWin.endYmd();
    }

    private static String detailUrl(String emdCd, Win cvWin, Win crdnWin) {
        return DETAIL_URL + "?emdCd=" + emdCd + "&" + prd(cvWin, crdnWin);
    }

    /** 이 창에서 기대되는 추세 경고 — 창이 짧으면 SHORT, 1차 '이전' 블록에 배정이 0건이면 FALLBACK */
    private List<String> expectedTrendWarnings(String emdCd, Win cvWin, boolean wide) {
        if (!cvWin.trendOk()) {
            return List.of(SHORT);
        }
        // 🆕 TSK-4282 D13 — 1차 이전 블록이 자료 시작일 앞이면 합과 무관하게 폴백이다(OR)
        boolean bfrBeforeData = cvWin.bfr().bgng().isBefore(cvRange.bgng());
        return bfrBeforeData || data.asgnTotal(Src.CV, emdCd, cvWin.bfr(), minCpcty, wide, rng) == 0
                ? List.of(FALLBACK) : List.of();
    }

    /**
     * 추세 4칸을 데이터에서 다시 계산한 기대값과 맞춘다 — 경고 · {@code trendDayCnt} · 블록 건수(실제로 쓴
     * 블록의 독립 재계산) · 증가율(블록 건수에서 재계산) · {@code trendMm}(최근 블록이 걸친 달 전부 · 합 =
     * 최근 블록 건수). 창이 성립해야 부른다.
     *
     * @return 폴백(반 분할)으로 내려앉았는가
     */
    private boolean assertTrend(JsonNode body, String emdCd, Win cvWin, boolean wide) {
        List<String> expectedWarn = expectedTrendWarnings(emdCd, cvWin, wide);
        assertThat(trendWarnings(body)).isEqualTo(expectedWarn);
        boolean fallback = expectedWarn.contains(FALLBACK);
        Win rcnt = fallback ? cvWin.splitRcnt() : cvWin;
        Win bfr = fallback ? cvWin.splitBfr() : cvWin.bfr();
        JsonNode crtr = body.path("crtrVls");
        assertThat(crtr.path("trendDayCnt").asInt()).as("🔴 실제로 쓴 블록 일수 — 1차면 D, 폴백이면 D/2")
                .isEqualTo(fallback ? cvWin.half() : cvWin.days());

        JsonNode lots = body.path("lots");
        assertThat(byLoc(lots, l -> l.path("asgnCvlcptCntRcnt").asLong()))
                .as("최근 블록 %s ~ %s", rcnt.bgng(), rcnt.end())
                .isEqualTo(data.asgnByLoc(Src.CV, emdCd, rcnt, minCpcty, wide, rng));
        assertThat(byLoc(lots, l -> l.path("asgnCvlcptCntBfr").asLong()))
                .as("이전 블록 %s ~ %s", bfr.bgng(), bfr.end())
                .isEqualTo(data.asgnByLoc(Src.CV, emdCd, bfr, minCpcty, wide, rng));
        int minTrendBase = crtr.path("minTrendBase").asInt();
        for (JsonNode lot : lots) {
            String nm = lot.path("pkltNm").asText();
            assertThat(lot.hasNonNull("asgnCvlcptCntRcnt")).as("%s — 창이 성립하면 0 이어도 null 이 아니다", nm)
                    .isTrue();
            assertThat(lot.hasNonNull("asgnCvlcptCntBfr")).isTrue();
            long r = lot.path("asgnCvlcptCntRcnt").asLong();
            long b = lot.path("asgnCvlcptCntBfr").asLong();
            BigDecimal expected = ZoneRcmdtnTestData.incrsRt(r, b, minTrendBase);
            if (expected == null) {
                assertThat(lot.path("cvlcptIncrsRt").isNull()).as("%s — 분모 %d < 하한 %d", nm, b, minTrendBase)
                        .isTrue();
            } else {
                assertThat(lot.path("cvlcptIncrsRt").decimalValue())
                        .as("%s — (최근 %d − 이전 %d) ÷ 이전 × 100", nm, r, b)
                        .isCloseTo(expected, within(new BigDecimal("0.1")));
            }
            // 🔴 trendMm 은 '블록이 걸친 달' 전부이고 합이 asgnCvlcptCntRcnt 와 같다(빈 달은 0)
            List<String> yms = new ArrayList<>();
            long sum = 0L;
            for (JsonNode mm : lot.path("trendMm")) {
                yms.add(mm.path("ym").asText());
                sum += mm.path("cnt").asLong();
            }
            assertThat(yms).as("%s — 최근 블록이 걸친 달 전부", nm).isEqualTo(rcnt.yms());
            assertThat(sum).as("%s — 🔴 합 = 최근 블록 건수 (양 끝이 부분 달이어도 깨지지 않는다)", nm).isEqualTo(r);
        }
        return fallback;
    }

    /** 주차장 목록(JSON)을 좌표 단위로 묶은 건수(0 인 좌표는 뺀다) */
    private static Map<String, Long> byLoc(JsonNode lots, ToLongFunction<JsonNode> cnt) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (JsonNode lot : lots) {
            long v = cnt.applyAsLong(lot);
            if (v != 0L) {
                out.merge(ZoneRcmdtnTestData.locKey(lot.path("lat").decimalValue(),
                        lot.path("lot").decimalValue()), v, Long::sum);
            }
        }
        return out;
    }

    /**
     * 정렬 계약 — 🔁 TSK-4282 우선도({@code incrsRfltRvwScr}) ↓ (§0-2) · 🆕 단계 비감소(D14).
     *
     * <p>⚠️ 응답 점수는 소수 1자리 반올림이라, <b>반올림 값이 같은 인접 행은 2차 키(배정 민원 → 면수 → pkltId)를
     * 단언하지 않는다</b> — 원값은 다를 수 있어 2차 키 순서가 뒤집혀 보여도 정상이다.
     */
    private static void assertRankOrder(JsonNode lots) {
        for (int i = 1; i < lots.size(); i++) {
            JsonNode a = lots.get(i - 1);
            JsonNode b = lots.get(i);
            assertThat(a.hasNonNull("incrsRfltRvwScr") && b.hasNonNull("incrsRfltRvwScr"))
                    .as("%d위·%d위 — 추세 반영 검토지수가 실린다", i, i + 1).isTrue();
            assertThat(a.path("incrsRfltRvwScr").decimalValue().compareTo(b.path("incrsRfltRvwScr").decimalValue()))
                    .as("%d위 '%s'(%s) → %d위 '%s'(%s) 의 순서 — 🔴 추세 반영 검토지수(↓)다", i, a.path("pkltNm").asText(),
                            a.path("incrsRfltRvwScr").asText(), i + 1, b.path("pkltNm").asText(),
                            b.path("incrsRfltRvwScr").asText())
                    .isGreaterThanOrEqualTo(0);
            // 🆕 D14 — 단계는 순위값(우선도)으로 매기므로 응답 순서대로 비감소(TOP → HIGH → LOW)
            int ga = GRD_ORDER.indexOf(a.path("rvwGrdCd").asText(null));
            int gb = GRD_ORDER.indexOf(b.path("rvwGrdCd").asText(null));
            assertThat(ga).as("%d위 '%s' — 단계가 실린다(%s)", i, a.path("pkltNm").asText(), a.path("rvwGrdCd"))
                    .isGreaterThanOrEqualTo(0);
            assertThat(gb).as("%d위 '%s' — 단계가 실린다(%s)", i + 1, b.path("pkltNm").asText(), b.path("rvwGrdCd"))
                    .isGreaterThanOrEqualTo(0);
            assertThat(ga).as("🔴 %d위 '%s'(%s) → %d위 '%s'(%s) — 단계는 응답 순서대로 비감소다(D14)",
                            i, a.path("pkltNm").asText(), a.path("rvwGrdCd").asText(),
                            i + 1, b.path("pkltNm").asText(), b.path("rvwGrdCd").asText())
                    .isLessThanOrEqualTo(gb);
        }
    }

    /** 🆕 D14 — 우선도 단계 순서(위가 먼저) */
    private static final List<String> GRD_ORDER = List.of("TOP", "HIGH", "LOW");

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(n.asText());
        }
        return out;
    }

    private static List<String> trendWarnings(JsonNode body) {
        return texts(body.path("warnings")).stream().filter(w -> w.startsWith("TREND_")).toList();
    }

    private static List<Long> ids(JsonNode lots) {
        List<Long> out = new ArrayList<>();
        for (JsonNode lot : lots) {
            out.add(lot.path("pkltId").asLong());
        }
        return out;
    }

    // ───────────────────────── 헬퍼 ─────────────────────────

    private long countRecommendExcn() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_excn WHERE anls_type_cd = 'RECOMMEND'", Long.class);
        return n == null ? 0L : n;
    }

    private long countRslt() {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_anls_rslt", Long.class);
        return n == null ? 0L : n;
    }

    /** 🔴 200 으로 끝난 상세 조회는 실행 이력을 남긴다 — 그 id 를 기억해 두었다가 그것만 지운다 */
    private ResponseEntity<String> get(String url) {
        ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(session), String.class);
        if (res.getStatusCode().is2xxSuccessful() && url.startsWith(DETAIL_URL + "?") && res.getBody() != null) {
            try {
                JsonNode body = objectMapper.readTree(res.getBody());
                if (body.hasNonNull("excnId")) {
                    createdExcnIds.add(body.path("excnId").asLong());
                }
            } catch (JsonProcessingException e) {   // 4.3(TSK-4336) — readTree 가 던지는 파싱 예외만
                throw new IllegalStateException("응답 JSON 파싱 실패(만든 실행 id 를 기억하지 못했다): " + url, e);
            }
        }
        return res;
    }

    private JsonNode getOk(String url) {
        ResponseEntity<String> res = get(url);
        assertThat(res.getStatusCode()).as("GET %s → %s", url, res.getBody()).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readTree(res.getBody());
        } catch (JsonProcessingException e) {   // 4.3(TSK-4336) — readTree 가 던지는 파싱 예외만
            throw new IllegalStateException("응답 JSON 파싱 실패: " + url, e);
        }
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Long id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
