package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 생활권 수급·혼잡도 보고서의 <b>엑셀 재계산 데이터({@code wbk})</b> 통합 테스트 (TSK-4340 Step 4).
 *
 * <p>이 테스트가 엑셀 수식의 <b>정답지</b>다 — 응답 {@code wbk} 를 계획서 3-1(수급)·3-2(혼잡도)의 엑셀 수식과
 * <b>같은 규칙</b>으로 Java 로 다시 계산해, 같은 계수의 {@code GET} 조회 결과와 맞는지 본다.
 * 🔴 서버 코드(서비스·{@code LifezoneGrade}·{@code LifezoneGradeScale})를 호출해 재계산하지 않는다 — 그러면 같은 코드끼리
 * 비교하는 것이라 엑셀 수식의 정합을 검증하지 못한다. 상수·규칙은 이 파일이 독립적으로 다시 적는다.
 *
 * <h2>비교 구조 — 계수 조합마다 저장 1회 · 조회 1회</h2>
 * <ol>
 *   <li>조합 k 로 저장 → {@code wbk_k} — 그 {@code vrbls[].aplcnVl} 이 조합 계수와 같다(엑셀 입력 셀 초기값)</li>
 *   <li>재계산({@code wbk_k}, 조합 k) = 저장 응답 {@code cells}(초기 조합 · 엑셀을 연 직후 상태)</li>
 *   <li>재계산({@code wbk_k}, 조합 k) = 조합 k 의 {@code GET}</li>
 *   <li>🔴 재계산({@code wbk_기본}, 조합 k) = 조합 k 의 {@code GET} — <b>기본 계수로 저장한 엑셀의 입력 셀을 조합 k 로 바꾼 상황</b>.
 *       엑셀은 저장 당시 원자료로 다른 계수를 계산하므로(계획서 Step 4) 이것이 실제 사용 시나리오다.</li>
 * </ol>
 *
 * <h2>정리</h2>
 * 응답으로 받은 {@code rptpId}·{@code excnId} 만 결과 → 보고서 → 실행 순으로 지우고(선례
 * {@code HotspotWorkbookIntegrationTest}), 로그인 이력은 {@link IntegrationLoginSupport#cleanUp()} 이
 * {@code lgn_sn > startSn} 만 지운다. 🔴 설정값({@code stng_vl})은 바꾸지 않는다 — 계수는 전부 {@code vrblVls} 로만 보낸다.
 * 날짜는 박지 않는다 — 각 탭 GET 응답의 {@code dataBgngYmd}·{@code dataEndYmd} 로 끝 3개월을 잡는다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // VWorld 호출 차단 — 구역명은 비교 대상이 아니다(HotspotWorkbookIntegrationTest 와 같은 프로퍼티)
        properties = "analysis.zone-name.vworld.api-key=")
@DisplayName("생활권 엑셀 재계산 (wbk) — Java 재계산 = 화면 조회")
class LifezoneWorkbookIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LifezoneWorkbookIntegrationTest.class);

    private static final String SUPPLY_URL = "/api/analysis/lifezone/supply";
    private static final String SUPPLY_CREATE_URL = "/api/analysis/lifezone/supply/reports";
    private static final String SUPPLY_SNAPSHOT_URL = "/api/reports/{rptpId}/lifezone/supply";
    private static final String CNGST_URL = "/api/analysis/lifezone/congestion";
    private static final String CNGST_CREATE_URL = "/api/analysis/lifezone/congestion/reports";
    private static final String CNGST_SNAPSHOT_URL = "/api/reports/{rptpId}/lifezone/congestion";
    private static final String CVLCPT_CREATE_URL = "/api/analysis/lifezone/reports";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int GRID_SZ = 500;

    /** 🔴 서버 상수와 같은 값 — 테스트가 다시 적는다(AnlsVrblScope · LifezoneSupply · LifezoneCngstServiceImpl). */
    private static final String SPLY_N = "SPLY_MIN_MNTH_AVG_CVLCPT";
    private static final String SPLY_B = "SPLY_GRD_BNDRY";
    private static final String OCC = "PCSHR_CRTR_VL";
    private static final String CNGST_B = "CNGST_GRD_BNDRY";
    private static final String DSCL = "DSCL_MLTP";
    private static final String FIX = "FIX_WGVL";
    private static final String GEO = "GEO_CVRG_CRCT_VL";
    private static final String CVW = "CVLCPT_WGVL";
    private static final String MINCPCTY = "MIN_CPCTY_VL";

    /** 엑셀 등급 문자열 → 응답 코드 */
    private static final Map<String, String> GRADE_CD = Map.of(
            "심각", "SEVERE", "경고", "WARN", "주의", "CAUTION", "보통", "NORMAL", "낮음", "LOW");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Set<Long> createdRptpIds = new LinkedHashSet<>();
    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
    }

    @AfterEach
    void tearDown() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long rptpId : createdRptpIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        }
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdRptpIds.clear();
        createdExcnIds.clear();
        auth.cleanUp();
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Object id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    // ═════════════════════════════════════════════ 0. 전제

    @Test
    @DisplayName("0. 검증할 계수가 설정 min_vl~max_vl 안이다 (범위 밖이면 400 이라 대조가 성립하지 않는다)")
    void casesFitSettingRanges() {
        Map<String, BigDecimal[]> range = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT vrbl_cd, min_vl, max_vl FROM parking.tb_anls_vrbl_stng"
                        + " WHERE anls_type_cd = 'LIFEZONE' AND use_yn = 'Y'",
                rs -> {
                    range.put(rs.getString(1), new BigDecimal[] { rs.getBigDecimal(2), rs.getBigDecimal(3) });
                });
        for (Map<String, Object> c : supplyCases().values()) {
            c.forEach((cd, v) -> assertWithin(range, cd, new BigDecimal(v.toString())));
        }
        for (Map<String, Object> c : cngstCases().values()) {
            c.forEach((cd, v) -> assertWithin(range, cd, new BigDecimal(v.toString())));
        }
    }

    private static void assertWithin(Map<String, BigDecimal[]> range, String cd, BigDecimal v) {
        assertThat(range).as("설정 행 %s", cd).containsKey(cd);
        assertThat(v).as("%s 하한", cd).isGreaterThanOrEqualTo(range.get(cd)[0]);
        assertThat(v).as("%s 상한", cd).isLessThanOrEqualTo(range.get(cd)[1]);
    }

    // ═════════════════════════════════════════════ 1. 수급 (계획서 3-1)

    /** 계수 조합 4종 — 첫 번째(기본)는 vrblVls 를 보내지 않는다. */
    private static Map<String, Map<String, Object>> supplyCases() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put("기본", Map.of());
        m.put("N=1", Map.of(SPLY_N, 1));
        m.put("N=10", Map.of(SPLY_N, 10));
        m.put("경계 10/30/50/70", Map.of(SPLY_B + 1, 10, SPLY_B + 2, 30, SPLY_B + 3, 50, SPLY_B + 4, 70));
        return m;
    }

    @Test
    @DisplayName("1. 수급 4종(기본·N=1·N=10·경계 10/30/50/70) — wbk 재계산(포함·rnk·등급·리스트) = 저장 응답 = 같은 계수 GET · 기본 wbk 로 계산해도 같다")
    void supplyRecomputeMatchesAnalysis() throws JsonProcessingException {
        String[] period = recentPeriod(SUPPLY_URL);
        JsonNode baseWbk = null;
        Map<String, BigDecimal> baseCoeffs = null;

        for (Map.Entry<String, Map<String, Object>> c : supplyCases().entrySet()) {
            String name = c.getKey();
            Map<String, Object> vrblVls = c.getValue();

            // ① 저장 (조합당 1회)
            Map<String, Object> body = periodBody(period);
            body.put("fileFmt", "XLSX");
            if (!vrblVls.isEmpty()) {
                body.put("vrblVls", vrblVls);
            }
            JsonNode saved = createOk(SUPPLY_CREATE_URL, body);
            JsonNode wbk = saved.path("wbk");
            assertThat(wbk.isObject()).as("%s wbk", name).isTrue();
            assertThat(wbk.path("prdDays").asInt()).as("%s prdDays = 조회 일수(양끝 포함)", name)
                    .isEqualTo(daysInclusive(period));
            assertThat(wbk.path("vrbls")).as("%s vrbls 5행", name).hasSize(5);
            assertThat(wbk.path("cells").size()).as("%s 후보 ≥ 분석 격자", name)
                    .isGreaterThanOrEqualTo(saved.path("cells").size());

            Map<String, BigDecimal> coeffs = aplcnOf(wbk);
            vrblVls.forEach((cd, v) -> assertThat(coeffs.get(cd)).as("%s 적용값 %s", name, cd)
                    .isEqualByComparingTo(new BigDecimal(v.toString())));
            if (baseWbk == null) {
                baseWbk = wbk;
                baseCoeffs = coeffs;
                assertDefaultsAreSettings(coeffs);
            } else {
                assertThat(supplyCandKeys(wbk)).as("%s 후보 원자료는 계수와 무관하다(N=min_vl)", name)
                        .isEqualTo(supplyCandKeys(baseWbk));
            }

            // ② 같은 계수 GET (조합당 1회)
            JsonNode shown = getOk(SUPPLY_URL, period, vrblVls);

            // ③ 대조
            List<String> expected = recomputeSupply(wbk, coeffs);
            assertThat(expected).as("%s 분석 격자가 있어야 대조가 의미 있다", name).isNotEmpty();
            assertThat(expected).as("%s 재계산 = 저장 응답 cells (gridId|rnk|grade)", name)
                    .containsExactlyElementsOf(supplyKeys(saved.path("cells")));
            assertThat(expected).as("%s 재계산 = GET cells (gridId|rnk|grade)", name)
                    .containsExactlyElementsOf(supplyKeys(shown.path("cells")));

            Map<String, BigDecimal> fromBase = new LinkedHashMap<>(baseCoeffs);
            vrblVls.forEach((cd, v) -> fromBase.put(cd, new BigDecimal(v.toString())));
            assertThat(recomputeSupply(baseWbk, fromBase)).as("%s 기본 wbk 의 입력 셀을 바꾼 재계산 = GET", name)
                    .containsExactlyElementsOf(expected);

            // 요약 — 분석 격자 수 · 민원 합 · 등급별 5칸 (계획서 3-1 요약 행)
            assertSupplySummary(name, wbk, coeffs, shown);
            long listed = expected.stream().filter(k -> isListedCd(k.substring(k.lastIndexOf('|') + 1))).count();
            log.info("[TSK-4340] 수급 {} {}~{} 후보 {} · 분석 격자 {} · 리스트 {}",
                    name, period[0], period[1], wbk.path("cells").size(), expected.size(), listed);
        }
    }

    /**
     * 계획서 3-1 시트 2 수식을 그대로 옮긴다.
     * 포함 {@code =민원*30>=N*prdDays} · 수요지수 {@code =면수+민원} · 등급(반올림 전 정수 비교 {@code 민원*100>=B*수요지수})
     * · 순위 {@code =COUNTIF(포함$첫행:포함현재행,TRUE)}.
     *
     * @return 포함 격자의 {@code gridId|rnk|grade} — 후보 행 순서(= 서버 순위 키 순)
     */
    private static List<String> recomputeSupply(JsonNode wbk, Map<String, BigDecimal> coeffs) {
        long n = intOf(coeffs, SPLY_N);
        long b1 = intOf(coeffs, SPLY_B + 1);
        long b2 = intOf(coeffs, SPLY_B + 2);
        long b3 = intOf(coeffs, SPLY_B + 3);
        long b4 = intOf(coeffs, SPLY_B + 4);
        long prdDays = wbk.path("prdDays").asLong();
        List<String> out = new ArrayList<>();
        int rnk = 0;
        for (JsonNode cell : wbk.path("cells")) {
            long cv = cell.path("cvlcptCnt").asLong();
            long sup = cell.path("pkltCpctySum").asLong();
            boolean included = cv * 30 >= n * prdDays;
            if (!included) {
                continue;
            }
            rnk++;
            long dmnd = sup + cv;
            String grade = cv * 100 >= b4 * dmnd ? "심각"
                    : cv * 100 >= b3 * dmnd ? "경고"
                    : cv * 100 >= b2 * dmnd ? "주의"
                    : cv * 100 >= b1 * dmnd ? "보통" : "낮음";
            out.add(cell.path("gridId").asText() + "|" + rnk + "|" + GRADE_CD.get(grade));
        }
        return out;
    }

    private static void assertSupplySummary(String name, JsonNode wbk, Map<String, BigDecimal> coeffs, JsonNode shown) {
        List<String> keys = recomputeSupply(wbk, coeffs);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        wbk.path("cells").forEach(c -> byId.put(c.path("gridId").asText(), c));
        int cvSum = 0;
        Map<String, Integer> byGrade = new LinkedHashMap<>();
        for (String k : keys) {
            String[] p = k.split("\\|");
            cvSum += byId.get(p[0]).path("cvlcptCnt").asInt();
            byGrade.merge(p[2], 1, Integer::sum);
        }
        assertThat(keys.size()).as("%s 분석 격자 수", name).isEqualTo(shown.path("totGridCnt").asInt());
        assertThat(cvSum).as("%s 민원 합", name).isEqualTo(shown.path("totCvlcptCnt").asInt());
        assertThat(byGrade.getOrDefault("SEVERE", 0)).as("%s 심각", name).isEqualTo(shown.path("severeCnt").asInt());
        assertThat(byGrade.getOrDefault("WARN", 0)).as("%s 경고", name).isEqualTo(shown.path("warnCnt").asInt());
        assertThat(byGrade.getOrDefault("CAUTION", 0)).as("%s 주의", name).isEqualTo(shown.path("cautionCnt").asInt());
        assertThat(byGrade.getOrDefault("NORMAL", 0)).as("%s 보통", name).isEqualTo(shown.path("normalCnt").asInt());
        assertThat(byGrade.getOrDefault("LOW", 0)).as("%s 낮음", name).isEqualTo(shown.path("lowCnt").asInt());
    }

    private static List<String> supplyKeys(JsonNode cells) {
        List<String> out = new ArrayList<>();
        cells.forEach(c -> out.add(c.path("gridId").asText() + "|" + c.path("rnk").asInt() + "|" + c.path("grade").asText()));
        return out;
    }

    private static List<String> supplyCandKeys(JsonNode wbk) {
        List<String> out = new ArrayList<>();
        wbk.path("cells").forEach(c -> out.add(c.path("gridId").asText() + "|" + c.path("cvlcptCnt").asInt()
                + "|" + c.path("pkltCpctySum").asInt() + "|" + c.path("pkltCnt").asInt()));
        return out;
    }

    // ═════════════════════════════════════════════ 2. 혼잡도 (계획서 3-2)

    /**
     * 계수 조합 6종 — 첫 번째(기본)는 vrblVls 를 보내지 않는다.
     *
     * <p>🔁 TSK-4346 D10 — 숨김(expsr_yn='N') 5계수(PCSHR_CRTR_VL·DSCL_MLTP·FIX_WGVL·CVLCPT_WGVL·MIN_CPCTY_VL)는 일시 조정이
     * 400 이라 조합에서 뺐다. 노출 계수(GEO_CVRG_CRCT_VL·경계 4개)만 바꿔 보고, 숨김 5계수는 기본값(설정값)으로만 대조된다.
     */
    private static Map<String, Map<String, Object>> cngstCases() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put("기본", Map.of());
        m.put("GEO_CVRG_CRCT_VL=1", Map.of(GEO, 1));
        m.put("GEO_CVRG_CRCT_VL=4", Map.of(GEO, 4));
        m.put("GEO_CVRG_CRCT_VL=2.5", Map.of(GEO, new BigDecimal("2.5")));
        m.put("GEO_CVRG_CRCT_VL=3,경계 86/91/94/98", Map.of(GEO, 3, CNGST_B + 1, 86, CNGST_B + 2, 91,
                CNGST_B + 3, 94, CNGST_B + 4, 98));
        m.put("경계 85/90/95/99.5", Map.of(CNGST_B + 1, 85, CNGST_B + 2, 90, CNGST_B + 3, 95,
                CNGST_B + 4, new BigDecimal("99.5")));
        return m;
    }

    /** 재계산 결과 — 요약 4수치 · 대상 셀(gridCd → anlsVl|grade) · 표시 순서(gridCd) */
    private record CngstRecomputed(int kept, int noEnf, int refr, int over1,
                                   Map<String, String> cells, List<String> listed, int pyRoundDiff) {
    }

    @Test
    @DisplayName("2. 혼잡도 6종 — wbk 재계산(double · 같은 연산 순서 · 엑셀 ROUND · 동점 행 순)의 요약 4수치·셀 anlsVl·grade·리스트 순서 = 저장 응답 = 같은 계수 GET · 기본 wbk 로 계산해도 같다")
    void cngstRecomputeMatchesAnalysis() throws JsonProcessingException {
        String[] period = recentPeriod(CNGST_URL);
        JsonNode baseWbk = null;
        Map<String, BigDecimal> baseCoeffs = null;

        for (Map.Entry<String, Map<String, Object>> c : cngstCases().entrySet()) {
            String name = c.getKey();
            Map<String, Object> vrblVls = c.getValue();

            Map<String, Object> body = periodBody(period);
            body.put("fileFmt", "XLSX");
            if (!vrblVls.isEmpty()) {
                body.put("vrblVls", vrblVls);
            }
            JsonNode saved = createOk(CNGST_CREATE_URL, body);
            JsonNode wbk = saved.path("wbk");
            assertThat(wbk.isObject()).as("%s wbk", name).isTrue();
            assertThat(wbk.path("vrbls")).as("%s vrbls 10행", name).hasSize(10);
            // 🆕 TSK-4346 Step 5 — 숨김 5계수는 회색 조건 셀(inptYn=N) · 노출 계수(GEO·경계 4개)는 입력 셀(Y)
            Map<String, String> inpt = new LinkedHashMap<>();
            wbk.path("vrbls").forEach(v -> inpt.put(v.path("vrblCd").asText(), v.path("inptYn").asText()));
            for (String cd : List.of(OCC, DSCL, FIX, CVW, MINCPCTY)) {
                assertThat(inpt.get(cd)).as("%s 숨김 계수 %s inptYn", name, cd).isEqualTo("N");
            }
            assertThat(inpt.get(GEO)).as("%s %s inptYn", name, GEO).isEqualTo("Y");
            for (int i = 1; i <= 4; i++) {
                assertThat(inpt.get(CNGST_B + i)).as("%s %s%d inptYn", name, CNGST_B, i).isEqualTo("Y");
            }
            assertThat(wbk.path("cells").size()).as("%s 원자료 = 공급>0 격자 = tot + noEnf + ref", name)
                    .isEqualTo(saved.path("totGridCnt").asInt() + saved.path("noEnfGridCnt").asInt()
                            + saved.path("refGridCnt").asInt());
            assertThat(wbk.path("cells").get(0).has("nFixed")).as("%s 원자료 키 nFixed (소문자 nfixed 아님)", name).isTrue();
            assertThat(wbk.path("mnthCnt").asDouble()).as("%s 개월수", name).isPositive();

            Map<String, BigDecimal> coeffs = aplcnOf(wbk);
            vrblVls.forEach((cd, v) -> assertThat(coeffs.get(cd)).as("%s 적용값 %s", name, cd)
                    .isEqualByComparingTo(new BigDecimal(v.toString())));
            if (baseWbk == null) {
                baseWbk = wbk;
                baseCoeffs = coeffs;
                assertDefaultsAreSettings(coeffs);
            } else {
                assertThat(cngstRawKeys(wbk)).as("%s 원자료는 계수와 무관하다", name).isEqualTo(cngstRawKeys(baseWbk));
                assertThat(wbk.path("mnthCnt").asDouble()).isEqualTo(baseWbk.path("mnthCnt").asDouble());
            }

            JsonNode shown = getOk(CNGST_URL, period, vrblVls);

            CngstRecomputed exp = recomputeCngst(wbk, coeffs);
            assertThat(exp.kept()).as("%s 분석 격자가 있어야 대조가 의미 있다", name).isPositive();
            assertCngstMatches(name + " (저장 응답)", exp, saved);
            assertCngstMatches(name + " (GET)", exp, shown);

            Map<String, BigDecimal> fromBase = new LinkedHashMap<>(baseCoeffs);
            vrblVls.forEach((cd, v) -> fromBase.put(cd, new BigDecimal(v.toString())));
            assertCngstMatches(name + " (기본 wbk 입력 셀 변경 → GET)", recomputeCngst(baseWbk, fromBase), shown);

            log.info("[TSK-4340] 혼잡도 {} {}~{} 원자료 {} · 개월 {} · 대상 {} · 근거없음 {} · 하한미만 {} · 100점초과 {} · 리스트 {}"
                            + " · Math.round 와 엑셀 ROUND 가 갈린 셀 {}",
                    name, period[0], period[1], wbk.path("cells").size(), wbk.path("mnthCnt").asDouble(),
                    exp.kept(), exp.noEnf(), exp.refr(), exp.over1(), exp.listed().size(), exp.pyRoundDiff());
        }
    }

    /**
     * 계획서 3-2 시트 2 수식을 같은 순서의 double 연산으로 옮긴다.
     * <pre>
     *   월 단속 =(PDA+수동+FIX*고정)/mnthCnt      월 민원 =민원/mnthCnt
     *   추정량  =(DSCL*월단속+CVW*월민원)/30*GEO
     *   근거없음 =NOT(추정량>0)   하한미만 =면수<MINCPCTY   대상 =AND(NOT(근거없음),NOT(하한미만))
     *   H =IF(근거없음,OCC,OCC+추정량/면수)   점수값 =ROUND(H*10000,0)
     *   등급 =IF(점수값>=ROUND(B심각*100,0),"심각",…,"낮음")
     *   표시 =AND(대상,OR(심각,경고,주의))
     *   표시순번 = SUMPRODUCT(표시*(H>H_i)) + 동점 항(첫 행 0 · 둘째 행부터 앞 행들의 표시*(H=H_i)) + 1
     * </pre>
     */
    private static CngstRecomputed recomputeCngst(JsonNode wbk, Map<String, BigDecimal> coeffs) {
        double mnth = wbk.path("mnthCnt").asDouble();
        double occ = coeffs.get(OCC).doubleValue();
        double dscl = coeffs.get(DSCL).doubleValue();
        double fix = coeffs.get(FIX).doubleValue();
        double geo = coeffs.get(GEO).doubleValue();
        double cvw = coeffs.get(CVW).doubleValue();
        double minCpcty = coeffs.get(MINCPCTY).doubleValue();
        long[] bnd = new long[4];
        for (int i = 0; i < 4; i++) {
            bnd[i] = excelRound0(coeffs.get(CNGST_B + (i + 1)).doubleValue() * 100);
        }

        List<JsonNode> rows = new ArrayList<>();
        wbk.path("cells").forEach(rows::add);
        int k = rows.size();
        double[] h = new double[k];
        boolean[] show = new boolean[k];
        int kept = 0;
        int noEnfCnt = 0;
        int refrCnt = 0;
        int over1 = 0;
        int roundDiff = 0;
        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < k; i++) {
            JsonNode r = rows.get(i);
            int s = r.path("pkltCpctySum").asInt();
            int nFixed = r.path("nFixed").asInt();
            int nPda = r.path("nPda").asInt();
            int nManual = r.path("nManual").asInt();
            int cv = r.path("cvlcptCnt").asInt();

            double enf = (nPda + nManual + fix * nFixed) / mnth;
            double cvm = cv / mnth;
            double est = (dscl * enf + cvw * cvm) / 30 * geo;
            boolean noEnf = !(est > 0);
            boolean refr = s < minCpcty;
            boolean target = !noEnf && !refr;
            h[i] = noEnf ? occ : occ + est / s;
            long vl = excelRound0(h[i] * 10000);
            if (vl != Math.round(h[i] * 10000)) {
                roundDiff++;
            }
            String grade = vl >= bnd[3] ? "심각" : vl >= bnd[2] ? "경고" : vl >= bnd[1] ? "주의"
                    : vl >= bnd[0] ? "보통" : "낮음";

            if (noEnf) {
                noEnfCnt++;
            } else if (refr) {
                refrCnt++;   // COUNTIFS(근거없음,FALSE,하한미만,TRUE)
            }
            if (target) {
                kept++;
                if (vl > 10000) {
                    over1++;
                }
                cells.put(r.path("gridCd").asText(), vl + "|" + GRADE_CD.get(grade));
            }
            show[i] = target && ("심각".equals(grade) || "경고".equals(grade) || "주의".equals(grade));
        }

        // 표시순번 — 엑셀 결과 표는 MATCH(1..K, 표시순번, 0) 로 채운다
        String[] byOrder = new String[k + 1];
        int listedCnt = 0;
        for (int i = 0; i < k; i++) {
            if (!show[i]) {
                continue;
            }
            int greater = 0;
            for (int j = 0; j < k; j++) {
                if (show[j] && h[j] > h[i]) {
                    greater++;
                }
            }
            int tie = 0;   // 🔴 첫 데이터 행이면 0 (범위가 뒤집히지 않게)
            for (int j = 0; j < i; j++) {
                if (show[j] && h[j] == h[i]) {
                    tie++;
                }
            }
            int ord = greater + tie + 1;
            assertThat(byOrder[ord]).as("표시순번 %d 중복", ord).isNull();
            byOrder[ord] = rows.get(i).path("gridCd").asText();
            listedCnt++;
        }
        List<String> listed = new ArrayList<>();
        for (int o = 1; o <= listedCnt; o++) {
            assertThat(byOrder[o]).as("표시순번 %d 누락", o).isNotNull();
            listed.add(byOrder[o]);
        }
        return new CngstRecomputed(kept, noEnfCnt, refrCnt, over1, cells, listed, roundDiff);
    }

    private static void assertCngstMatches(String where, CngstRecomputed exp, JsonNode actual) {
        assertThat(exp.kept()).as("%s 분석 격자(totGridCnt)", where).isEqualTo(actual.path("totGridCnt").asInt());
        assertThat(exp.noEnf()).as("%s 근거 없음 제외(noEnfGridCnt)", where).isEqualTo(actual.path("noEnfGridCnt").asInt());
        assertThat(exp.refr()).as("%s 면수 하한 미만 제외(refGridCnt)", where).isEqualTo(actual.path("refGridCnt").asInt());
        assertThat(exp.over1()).as("%s 100점 초과(over1GridCnt)", where).isEqualTo(actual.path("over1GridCnt").asInt());

        Map<String, String> act = new LinkedHashMap<>();
        actual.path("cells").forEach(c -> act.put(c.path("gridCd").asText(),
                c.path("anlsVl").asLong() + "|" + c.path("grade").asText()));
        assertThat(exp.cells()).as("%s 셀별 anlsVl|grade", where).containsExactlyInAnyOrderEntriesOf(act);

        List<String> actRank = new ArrayList<>();
        actual.path("ranking").forEach(r -> actRank.add(r.path("gridCd").asText()));
        assertThat(exp.listed()).as("%s 리스트 순서(gridCd)", where).containsExactlyElementsOf(actRank);
    }

    private static List<String> cngstRawKeys(JsonNode wbk) {
        List<String> out = new ArrayList<>();
        wbk.path("cells").forEach(c -> out.add(c.path("gridCd").asText() + "|" + c.path("pkltCpctySum").asInt()
                + "|" + c.path("pkltCnt").asInt() + "|" + c.path("nFixed").asInt() + "|" + c.path("nPda").asInt()
                + "|" + c.path("nManual").asInt() + "|" + c.path("cvlcptCnt").asInt()));
        return out;
    }

    /**
     * 엑셀 {@code ROUND(x,0)} — 엑셀은 값을 유효숫자 15자리로 보고 0 에서 먼 쪽으로 반올림한다.
     * {@code Math.round} 와는 15자리 아래 끝자리가 {@code .5} 경계에 걸릴 때만 갈린다(계획서 3-2 · 5장 위험).
     */
    private static long excelRound0(double x) {
        return new BigDecimal(x).round(new MathContext(15, RoundingMode.HALF_EVEN))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    // ═════════════════════════════════════════════ 3. 저장 형식 · 구 스냅샷

    @Test
    @DisplayName("3. fileFmt=XLSX → TB_RPTP.file_fmt='XLSX' (세 탭) · XLS 는 400 이고 아무것도 남기지 않는다 (세 탭)")
    void xlsxAcceptedAndXlsRejected() {
        String[] supplyPeriod = recentPeriod(SUPPLY_URL);
        String[] cngstPeriod = recentPeriod(CNGST_URL);
        Map<String, String[]> urls = new LinkedHashMap<>();
        urls.put(SUPPLY_CREATE_URL, supplyPeriod);
        urls.put(CNGST_CREATE_URL, cngstPeriod);
        urls.put(CVLCPT_CREATE_URL, supplyPeriod);

        for (Map.Entry<String, String[]> e : urls.entrySet()) {
            Map<String, Object> body = periodBody(e.getValue());
            body.put("fileFmt", "XLSX");
            JsonNode saved = createOk(e.getKey(), body);
            assertThat(saved.path("fileFmt").asText()).as("%s 응답 fileFmt", e.getKey()).isEqualTo("XLSX");
            assertThat(jdbcTemplate.queryForObject("SELECT file_fmt FROM parking.tb_rptp WHERE rptp_id = ?",
                    String.class, saved.path("rptpId").asLong())).as("%s TB_RPTP.file_fmt", e.getKey()).isEqualTo("XLSX");
        }

        long[] before = tableCounts();
        for (Map.Entry<String, String[]> e : urls.entrySet()) {
            Map<String, Object> body = periodBody(e.getValue());
            body.put("fileFmt", "XLS");
            ResponseEntity<String> res = restTemplate.exchange(
                    e.getKey(), HttpMethod.POST, auth.jsonEntity(session, body), String.class);
            if (res.getStatusCode().is2xxSuccessful()) {
                track(parse(res.getBody()));
            }
            assertThat(res.getStatusCode().value()).as("%s XLS", e.getKey()).isEqualTo(400);
        }
        assertThat(tableCounts()).as("400 은 실행·결과·보고서 어디에도 행을 남기지 않는다").containsExactly(before);
    }

    @Test
    @DisplayName("4. 스냅샷 wbk = 생성 응답 wbk (수급·혼잡도) · rslt_json 에서 wbk 키를 지운 구 스냅샷은 wbk null · 본문은 그대로")
    void snapshotCarriesWorkbookAndLegacyIsNull() {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put(SUPPLY_CREATE_URL, SUPPLY_SNAPSHOT_URL);
        pairs.put(CNGST_CREATE_URL, CNGST_SNAPSHOT_URL);
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            String[] period = recentPeriod(e.getKey().equals(SUPPLY_CREATE_URL) ? SUPPLY_URL : CNGST_URL);
            Map<String, Object> body = periodBody(period);
            body.put("fileFmt", "PDF");   // D7 — 형식과 무관하게 wbk 를 싣는다
            JsonNode created = createOk(e.getKey(), body);
            assertThat(created.path("wbk").isObject()).as("%s PDF 저장도 wbk", e.getKey()).isTrue();

            JsonNode snap = snapshotOk(e.getValue(), created.path("rptpId").asLong());
            assertThat(snap.path("wbk")).as("%s 스냅샷 wbk = 생성 응답 wbk", e.getValue()).isEqualTo(created.path("wbk"));

            int updated = jdbcTemplate.update(
                    "UPDATE parking.tb_anls_rslt SET rslt_json = rslt_json - 'wbk' WHERE excn_id = ?",
                    created.path("excnId").asLong());
            assertThat(updated).isEqualTo(1);
            JsonNode legacy = snapshotOk(e.getValue(), created.path("rptpId").asLong());
            assertThat(legacy.path("wbk").isNull() || legacy.path("wbk").isMissingNode())
                    .as("%s 구 스냅샷 wbk null", e.getValue()).isTrue();
            assertThat(legacy.path("cells")).as("%s 본문 cells 는 그대로", e.getValue()).isEqualTo(created.path("cells"));
        }
    }

    // ═════════════════════════════════════════════ 헬퍼

    /** 기본 조합(vrblVls 없음)의 적용값 = 설정값 — 엑셀 초기값 출처가 하나다(D4). */
    private void assertDefaultsAreSettings(Map<String, BigDecimal> aplcn) {
        aplcn.forEach((cd, v) -> {
            BigDecimal stng = jdbcTemplate.queryForObject("SELECT stng_vl FROM parking.tb_anls_vrbl_stng"
                    + " WHERE anls_type_cd = 'LIFEZONE' AND vrbl_cd = ?", BigDecimal.class, cd);
            assertThat(v).as("기본 조합 적용값 %s = 설정값", cd).isEqualByComparingTo(stng);
        });
    }

    private static Map<String, BigDecimal> aplcnOf(JsonNode wbk) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        wbk.path("vrbls").forEach(v -> m.put(v.path("vrblCd").asText(), v.path("aplcnVl").decimalValue()));
        return m;
    }

    private static long intOf(Map<String, BigDecimal> coeffs, String cd) {
        BigDecimal v = coeffs.get(cd);
        assertThat(v).as("계수 %s", cd).isNotNull();
        return v.longValueExact();
    }

    private static boolean isListedCd(String cd) {
        return "SEVERE".equals(cd) || "WARN".equals(cd) || "CAUTION".equals(cd);
    }

    /** 탭 GET 의 보유 구간({@code dataBgngYmd}·{@code dataEndYmd}) 끝 3개월 — 날짜를 박지 않는다. */
    private String[] recentPeriod(String analyzeUrl) {
        JsonNode range = parse(restTemplate.exchange(analyzeUrl + "?bgngYmd=20000101&endYmd=20000102&gridSz=" + GRID_SZ,
                HttpMethod.GET, new HttpEntity<>(session), String.class).getBody());
        assertThat(range.path("dataEndYmd").asText()).as("%s 보유 구간", analyzeUrl).matches("\\d{8}");
        LocalDate end = LocalDate.parse(range.path("dataEndYmd").asText(), YMD);
        LocalDate dataBgng = LocalDate.parse(range.path("dataBgngYmd").asText(), YMD);
        LocalDate bgng = end.minusMonths(3).plusDays(1);
        if (bgng.isBefore(dataBgng)) {
            bgng = dataBgng;
        }
        return new String[] { bgng.format(YMD), end.format(YMD) };
    }

    private static int daysInclusive(String[] period) {
        return (int) ChronoUnit.DAYS.between(LocalDate.parse(period[0], YMD), LocalDate.parse(period[1], YMD)) + 1;
    }

    private static Map<String, Object> periodBody(String[] period) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bgngYmd", period[0]);
        m.put("endYmd", period[1]);
        m.put("gridSz", GRID_SZ);
        return m;
    }

    private JsonNode getOk(String url, String[] period, Map<String, Object> vrblVls) throws JsonProcessingException {
        String uri = url + "?bgngYmd={b}&endYmd={e}&gridSz={g}";
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("b", period[0]);
        vars.put("e", period[1]);
        vars.put("g", GRID_SZ);
        if (!vrblVls.isEmpty()) {
            uri += "&vrblVls={v}";
            vars.put("v", MAPPER.writeValueAsString(vrblVls));
        }
        ResponseEntity<String> res = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(session), String.class, vars);
        assertThat(res.getStatusCode().value()).as("GET %s %s → %s", url, vrblVls, res.getBody()).isEqualTo(200);
        return parse(res.getBody());
    }

    private JsonNode createOk(String url, Map<String, Object> body) {
        ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.POST, auth.jsonEntity(session, body), String.class);
        JsonNode node = res.getStatusCode().is2xxSuccessful() ? parse(res.getBody()) : null;
        if (node != null) {
            track(node);
        }
        assertThat(res.getStatusCode().value()).as("생성 201 %s (요청: %s) → %s", url, body, res.getBody()).isEqualTo(201);
        return node;
    }

    private JsonNode snapshotOk(String url, long rptpId) {
        ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(session), String.class, rptpId);
        assertThat(res.getStatusCode().value()).as("스냅샷 %s", url).isEqualTo(200);
        return parse(res.getBody());
    }

    private void track(JsonNode node) {
        if (node.hasNonNull("rptpId")) {
            createdRptpIds.add(node.get("rptpId").asLong());
        }
        if (node.hasNonNull("excnId")) {
            createdExcnIds.add(node.get("excnId").asLong());
        }
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("JSON 이 아니다: " + json, e);
        }
    }

    private long[] tableCounts() {
        return new long[] { countOf("parking.tb_anls_excn"), countOf("parking.tb_anls_rslt"), countOf("parking.tb_rptp") };
    }

    private long countOf(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
