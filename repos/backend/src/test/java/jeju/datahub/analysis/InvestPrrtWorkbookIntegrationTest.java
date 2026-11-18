package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.InvestPrrtTestData.Win;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 투자우선순위 엑셀 재계산 정답지 (TSK-4343 Step 3) — <b>보고서 {@code wbk} + {@code lots} 를 엑셀 수식 규칙으로 다시
 * 계산한 결과 = 같은 조건 + 그 계수로 화면이 다시 조회한 결과</b>(순서 · {@code snthsScr} · {@code prrtLvlCd}).
 *
 * <h2>🔴 재계산기는 서버 코드를 복사하지 않는다</h2>
 * 서버({@code InvestPrrtCalculator})는 {@code BigDecimal}, 엑셀은 double 이다. {@link #recompute} 는 계획서 3-2 표의
 * <b>엑셀 수식 그대로</b> 적는다 —
 * <pre>
 * 분자 = SAFETY·S·MAX(n−1,1) + 100·(COMPLAINT·kC + USAGE·kU + ACCIDENT·kA)
 * 분모 = MAX(n−1,1)·(SAFETY + COMPLAINT + USAGE + ACCIDENT)
 * P    = ROUND(분자/분모, 9)          (엑셀 ROUND = 유효숫자 15자리 → HALF_UP)
 * 등급 = P ≥ UP → H / P ≥ MD → M / 그 밖 L
 * 순번 = SUMPRODUCT(--(P열 > P)) + 앞 행(pkltId↑ 로 미리 정렬) 중 동점 수 + 1   (D8)
 * </pre>
 * {@code n} 은 {@code wbk.trgtCnt}(필터 <b>전</b>) 이고 {@code S} 는 {@code lots[].sftyRiskScr}(0/50/100), {@code k} 는
 * {@code wbk.rows} 다. 가중치 0 인 항은 서버가 분자·분모에서 빼지만 엑셀은 0 을 곱하고 더한다 — 값이 같아야 한다.
 *
 * <h2>🔴 비교 상대는 "같은 조건 + 다른 계수" GET 이다</h2>
 * 보고서는 조건(필터)마다 <b>DB 현재 설정값</b>으로 1건만 만들고, 조합마다 그 {@code wbk} 를 계수만 바꿔 재계산한다.
 * {@code ALTMNT_RNG_VL}(공간 배정 — D1)·{@code INDCT_PKLT_CNT}(표시 수 — D4)는 조합에 넣지 않는다.
 *
 * <h2>🔴 설정 표를 바꾸지 않는다 · 날짜를 박지 않는다 · 내가 만든 행만 지운다</h2>
 * 계수는 전부 {@code vrblVls} 로만 넘기고 테스트 전후 {@code tb_anls_vrbl_stng} 을 대조한다. 기간은
 * {@link InvestPrrtTestData#recentWin()}. 보고서 3행은 {@link #post} 가 모은 id 만 결과 → 보고서 → 실행 순으로 지운다
 * ({@code InvestPrrtReportIntegrationTest} 선례 — 조회 GET 은 실행 행을 남기지 않는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TSK-4343 Step 3 — 투자우선순위 엑셀 재계산(wbk) = 같은 계수의 화면 조회")
class InvestPrrtWorkbookIntegrationTest {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(InvestPrrtWorkbookIntegrationTest.class);

    private static final String QUERY_URL = "/api/analysis/investment-priority";
    private static final String CREATE_URL = "/api/analysis/investment-priority/reports";
    private static final String SNAPSHOT_URL = "/api/reports/{rptpId}/investment-priority";

    private static final String INVEST = "INVEST";
    private static final List<String> WEIGHTS = List.of("SAFETY", "COMPLAINT", "USAGE", "ACCIDENT");
    private static final String UP = "UP_GRD_CRTR_SCR";
    private static final String MD = "MD_GRD_CRTR_SCR";

    /** 엑셀 ROUND 의 유효숫자 15자리 규칙 */
    private static final MathContext MC15 = new MathContext(15, RoundingMode.HALF_EVEN);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final List<Long> createdRptpIds = new ArrayList<>();
    private final List<Long> createdExcnIds = new ArrayList<>();

    private IntegrationLoginSupport auth;
    private HttpHeaders session;
    private String bgngYmd;
    private String endYmd;
    private long investExcnCntBefore;
    private List<Map<String, Object>> stngBefore;

    @BeforeEach
    void setUp() {
        stngBefore = readSettings();
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
        InvestPrrtTestData data = new InvestPrrtTestData(jdbcTemplate);
        Win win = data.recentWin();
        assumeTrue(!data.targetIds().isEmpty(), "노상·공한지가 아닌 미소멸 주차장이 없다");
        assumeTrue(win != null, "좌표 있는 주차 민원이 없어 창을 잡을 수 없다");
        bgngYmd = win.bgng().format(InvestPrrtTestData.YMD);
        endYmd = win.end().format(InvestPrrtTestData.YMD);
        investExcnCntBefore = investExcnCnt();
    }

    @AfterEach
    void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long excnId : createdExcnIds) {
            run(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long rptpId : createdRptpIds) {
            run(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        }
        for (Long excnId : createdExcnIds) {
            run(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ? AND anls_type_cd = ?", excnId, INVEST);
        }
        createdRptpIds.clear();
        createdExcnIds.clear();
        auth.cleanUp();
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다").isEmpty();
        assertThat(investExcnCnt()).as("INVEST 실행 이력 수가 시작 때로 돌아온다").isEqualTo(investExcnCntBefore);
        assertThat(readSettings()).as("🔴 설정 표(stng_vl)를 바꾸지 않는다").isEqualTo(stngBefore);
    }

    // ═════════════════════════════════════════════ 1. 전 지역

    @Test
    @DisplayName("전 지역 — 계수 5조합(기본 · 40/30/20/10 · 0/50/50/0 · 100/0/0/0 · 등급 70/30): 재계산 = 같은 계수의 GET")
    void allRegionRecomputeEqualsQuery() {
        Report rep = createReport(null, null);

        // 기본값 = DB 현재 설정값(사용 중 행) — wbk.vrbls 의 적용값이 그 값이다
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT vrbl_cd, stng_vl FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = ? AND use_yn = 'Y'", INVEST)) {
            BigDecimal aplcn = rep.base().get((String) row.get("vrbl_cd"));
            assertThat(aplcn).as("wbk.vrbls[%s].aplcnVl = DB stng_vl", row.get("vrbl_cd")).isNotNull();
            assertThat(aplcn).isEqualByComparingTo((BigDecimal) row.get("stng_vl"));
        }
        assertThat(rep.wbk().path("trgtCnt").asInt()).as("필터가 없으면 n = lots 수")
                .isEqualTo(rep.lots().size());

        List<String> mismatches = new ArrayList<>();
        Map<String, Integer> compared = new LinkedHashMap<>();
        Set<String> orders = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, BigDecimal>> combo : combos().entrySet()) {
            Recomputed mine = recompute(rep, merge(rep.base(), combo.getValue()));
            JsonNode query = query(null, null, combo.getValue());
            compare("전 지역 " + combo.getKey(), mine, query, mismatches);
            compared.put(combo.getKey(), mine.order().size());
            orders.add(mine.order().toString());

            if ("안전만(100/0/0/0)".equals(combo.getKey())) {
                long distinct = mine.score().values().stream().distinct().count();
                assertThat(distinct).as("안전만 — 종합점수가 S(0/50/100) 뿐이라 동점이 다수다(D7·D8 동점 깨기 검증 전제)")
                        .isLessThan(mine.order().size());
            }
        }
        LOG.info("[TSK-4343 Step3] 전 지역 비교 행 수 = {}", compared);   // 6.2(TSK-4336) — System.out 대신 로거
        assertThat(mismatches).as("전 지역 재계산 ≠ 화면 조회 (불일치 %d건)", mismatches.size()).isEmpty();
        assertThat(orders.size()).as("가중치 조합이 순서를 실제로 바꾼다(재계산이 의미 있다)").isGreaterThan(1);
    }

    // ═════════════════════════════════════════════ 2. 행정구역 필터

    @Test
    @DisplayName("시군구 필터 — n 은 필터 전 전 지역 · 재계산 = 같은 계수의 GET(필터 통과분 순서·점수·등급)")
    void sggFilterRecomputeEqualsQuery() {
        JsonNode full = query(null, null, Map.of());
        String sggNm = null;
        for (JsonNode l : full.path("lots")) {
            if (l.hasNonNull("sggNm")) {
                sggNm = l.path("sggNm").asText();
                break;
            }
        }
        assumeTrue(sggNm != null, "시군구가 채워진 대상 주차장이 없다");
        verifyFiltered("시군구 " + sggNm, sggNm, null, full.path("lots").size());
    }

    @Test
    @DisplayName("읍면동 필터 — n 은 필터 전 전 지역 · 재계산 = 같은 계수의 GET(필터 통과분 순서·점수·등급)")
    void emdFilterRecomputeEqualsQuery() {
        JsonNode full = query(null, null, Map.of());
        Map<String, Integer> perEmd = new LinkedHashMap<>();
        for (JsonNode l : full.path("lots")) {
            if (l.hasNonNull("emdCd") && l.hasNonNull("sggNm")) {
                perEmd.merge(l.path("emdCd").asText(), 1, Integer::sum);
            }
        }
        // 동점·순번 식이 의미 있게 — 주차장이 가장 많은 읍면동(동수면 코드순 첫째)
        String emdCd = perEmd.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse(null);
        assumeTrue(emdCd != null, "읍면동이 채워진 대상 주차장이 없다");
        verifyFiltered("읍면동 " + emdCd, null, emdCd, full.path("lots").size());
    }

    private void verifyFiltered(String label, String sggNm, String emdCd, int fullCnt) {
        Report rep = createReport(sggNm, emdCd);
        assertThat(rep.lots().size()).as("%s 필터 통과분이 있다", label).isPositive();
        assertThat(rep.wbk().path("trgtCnt").asInt()).as("🔴 n = 필터 전 전 지역 대상 수").isEqualTo(fullCnt);

        List<String> mismatches = new ArrayList<>();
        Map<String, Integer> compared = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal>> combo : combos().entrySet()) {
            Recomputed mine = recompute(rep, merge(rep.base(), combo.getValue()));
            JsonNode query = query(sggNm, emdCd, combo.getValue());
            compare(label + " " + combo.getKey(), mine, query, mismatches);
            compared.put(combo.getKey(), mine.order().size());
        }
        LOG.info("[TSK-4343 Step3] {} 비교 행 수 = {}", label, compared);   // 6.2(TSK-4336)
        assertThat(mismatches).as("%s 재계산 ≠ 화면 조회 (불일치 %d건)", label, mismatches.size()).isEmpty();
    }

    // ═════════════════════════════════════════════ 조합

    private static Map<String, Map<String, BigDecimal>> combos() {
        Map<String, Map<String, BigDecimal>> c = new LinkedHashMap<>();
        c.put("기본값", Map.of());
        c.put("40/30/20/10", weights(40, 30, 20, 10));
        c.put("0/50/50/0", weights(0, 50, 50, 0));
        c.put("안전만(100/0/0/0)", weights(100, 0, 0, 0));
        Map<String, BigDecimal> grade = new LinkedHashMap<>();
        grade.put(UP, BigDecimal.valueOf(70));
        grade.put(MD, BigDecimal.valueOf(30));
        c.put("등급 UP70·MD30", grade);
        return c;
    }

    private static Map<String, BigDecimal> weights(int s, int c, int u, int a) {
        Map<String, BigDecimal> w = new LinkedHashMap<>();
        w.put("SAFETY", BigDecimal.valueOf(s));
        w.put("COMPLAINT", BigDecimal.valueOf(c));
        w.put("USAGE", BigDecimal.valueOf(u));
        w.put("ACCIDENT", BigDecimal.valueOf(a));
        return w;
    }

    private static Map<String, BigDecimal> merge(Map<String, BigDecimal> base, Map<String, BigDecimal> combo) {
        Map<String, BigDecimal> m = new HashMap<>(base);
        m.putAll(combo);
        return m;
    }

    // ═════════════════════════════════════════════ 재계산 (엑셀 수식 그대로)

    /** 보고서 1건에서 재계산에 쓰는 것 — 스냅샷 GET 의 {@code wbk}·{@code lots} 와 계수 적용값 */
    private record Report(JsonNode wbk, List<JsonNode> lots, Map<String, BigDecimal> base) {
    }

    /** @param order 표시순번 순 pkltId · @param score 엑셀 P · @param grade 등급 코드 */
    private record Recomputed(List<Long> order, Map<Long, Double> score, Map<Long, String> grade) {
    }

    private Recomputed recompute(Report rep, Map<String, BigDecimal> vl) {
        JsonNode wbk = rep.wbk();
        int n = wbk.path("trgtCnt").asInt();
        double n1 = Math.max(n - 1, 1);
        double wS = vl.get("SAFETY").doubleValue();
        double wC = vl.get("COMPLAINT").doubleValue();
        double wU = vl.get("USAGE").doubleValue();
        double wA = vl.get("ACCIDENT").doubleValue();
        double up = vl.get(UP).doubleValue();
        double md = vl.get(MD).doubleValue();

        Map<Long, JsonNode> kById = new HashMap<>();
        for (JsonNode r : wbk.path("rows")) {
            kById.put(r.path("pkltId").asLong(), r);
        }

        // 시트 2 — 행을 pkltId↑ 로 미리 정렬(D8)
        List<JsonNode> sheet = new ArrayList<>(rep.lots());
        sheet.sort(Comparator.comparingLong(l -> l.path("pkltId").asLong()));
        int m = sheet.size();
        long[] id = new long[m];
        double[] p = new double[m];
        for (int i = 0; i < m; i++) {
            JsonNode lot = sheet.get(i);
            id[i] = lot.path("pkltId").asLong();
            JsonNode k = kById.get(id[i]);
            assertThat(k).as("wbk.rows 에 pkltId %d 가 있다", id[i]).isNotNull();
            double s = lot.path("sftyRiskScr").asDouble();
            double num = wS * s * n1
                    + 100 * (wC * k.path("cvlcptLtCnt").asInt() + wU * k.path("utztnLtCnt").asInt()
                            + wA * k.path("acdntLtCnt").asInt());
            double den = n1 * (wS + wC + wU + wA);
            p[i] = xlRound(num / den, 9);
        }

        Map<Long, Double> score = new LinkedHashMap<>();
        Map<Long, String> grade = new LinkedHashMap<>();
        Long[] bySeq = new Long[m];
        for (int i = 0; i < m; i++) {
            int greater = 0;
            int tiesBefore = 0;
            for (int j = 0; j < m; j++) {
                if (p[j] > p[i]) {
                    greater++;
                } else if (j < i && p[j] == p[i]) {
                    tiesBefore++;
                }
            }
            int seq = greater + tiesBefore + 1;
            assertThat(bySeq[seq - 1]).as("표시순번 %d 중복", seq).isNull();
            bySeq[seq - 1] = id[i];
            score.put(id[i], p[i]);
            grade.put(id[i], md >= up ? "-" : p[i] >= up ? "H" : p[i] >= md ? "M" : "L");
        }
        return new Recomputed(List.of(bySeq), score, grade);
    }

    /** 엑셀 {@code ROUND(x,n)} — 유효숫자 15자리로 맞춘 뒤 HALF_UP */
    private static double xlRound(double x, int n) {
        if (x == 0 || !Double.isFinite(x)) {
            return x;
        }
        return new BigDecimal(x).round(MC15).setScale(n, RoundingMode.HALF_UP).doubleValue();
    }

    private static void compare(String label, Recomputed mine, JsonNode query, List<String> mismatches) {
        JsonNode lots = query.path("lots");
        if (lots.size() != mine.order().size()) {
            mismatches.add(label + ": 행 수 재계산 " + mine.order().size() + " ≠ 조회 " + lots.size());
            return;
        }
        for (int i = 0; i < lots.size(); i++) {
            JsonNode q = lots.get(i);
            long qId = q.path("pkltId").asLong();
            long myId = mine.order().get(i);
            if (qId != myId) {
                mismatches.add(label + ": 순번 " + (i + 1) + " 주차장 재계산 " + myId + " ≠ 조회 " + qId);
                continue;
            }
            BigDecimal myScr = BigDecimal.valueOf(mine.score().get(myId));
            BigDecimal qScr = q.path("snthsScr").decimalValue();
            if (myScr.compareTo(qScr) != 0) {
                mismatches.add(label + ": pkltId " + myId + " 점수 재계산 " + myScr.toPlainString()
                        + " ≠ 조회 " + qScr.toPlainString());
            }
            String qGrd = q.path("prrtLvlCd").asText();
            if (!mine.grade().get(myId).equals(qGrd)) {
                mismatches.add(label + ": pkltId " + myId + " 등급 재계산 " + mine.grade().get(myId) + " ≠ 조회 " + qGrd);
            }
        }
    }

    // ═════════════════════════════════════════════ HTTP

    /** 조건(필터)마다 DB 현재 설정값으로 XLSX 보고서 1건 → 스냅샷 GET 의 wbk·lots */
    private Report createReport(String sggNm, String emdCd) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("bgngYmd", bgngYmd);
        req.put("endYmd", endYmd);
        if (sggNm != null) {
            req.put("sggNm", sggNm);
        }
        if (emdCd != null) {
            req.put("emdCd", emdCd);
        }
        req.put("fileFmt", "XLSX");
        ResponseEntity<String> res = post(req);
        assertThat(res.getStatusCode()).as("POST %s → %s", req, res.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = parse(res.getBody());

        JsonNode snapshot = getOk(SNAPSHOT_URL, Map.of("rptpId", created.path("rptpId").asLong()));
        JsonNode wbk = snapshot.path("wbk");
        assumeTrue(wbk.isObject(), "wbk 가 없다(ENFORCE 가중치 행이 있으면 D2-1 로 null) — 재계산 대상 아님");
        assertThat(wbk).as("스냅샷 wbk = POST wbk").isEqualTo(created.path("wbk"));

        List<JsonNode> lots = new ArrayList<>();
        snapshot.path("lots").forEach(lots::add);
        assertThat(wbk.path("rows").size()).as("rows = lots").isEqualTo(lots.size());

        Map<String, BigDecimal> base = new HashMap<>();
        for (JsonNode v : wbk.path("vrbls")) {
            assertThat(v.path("aplcnVl").isNumber()).as("vrbls[%s].aplcnVl", v.path("vrblCd").asText()).isTrue();
            base.put(v.path("vrblCd").asText(), v.path("aplcnVl").decimalValue());
        }
        for (String cd : WEIGHTS) {
            assertThat(base).as("wbk.vrbls 에 가중치 %s", cd).containsKey(cd);
        }
        assertThat(base).containsKeys(UP, MD);
        return new Report(wbk, lots, base);
    }

    private JsonNode query(String sggNm, String emdCd, Map<String, BigDecimal> combo) {
        StringBuilder url = new StringBuilder(QUERY_URL + "?bgngYmd={b}&endYmd={e}");
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("b", bgngYmd);
        vars.put("e", endYmd);
        if (sggNm != null) {
            url.append("&sggNm={sgg}");
            vars.put("sgg", sggNm);
        }
        if (emdCd != null) {
            url.append("&emdCd={emd}");
            vars.put("emd", emdCd);
        }
        if (!combo.isEmpty()) {
            url.append("&vrblVls={v}");
            try {
                vars.put("v", objectMapper.writeValueAsString(combo));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        return getOk(url.toString(), vars);
    }

    /** 201 이면 단언보다 먼저 id 를 수집한다 — 정리는 이 목록만 본다 */
    private ResponseEntity<String> post(Map<String, Object> request) {
        ResponseEntity<String> res = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, request), String.class);
        if (res.getStatusCode() == HttpStatus.CREATED && res.getBody() != null) {
            JsonNode body = parse(res.getBody());
            if (body.hasNonNull("rptpId")) {
                createdRptpIds.add(body.path("rptpId").asLong());
            }
            if (body.hasNonNull("excnId")) {
                createdExcnIds.add(body.path("excnId").asLong());
            }
        }
        return res;
    }

    private JsonNode getOk(String url, Map<String, ?> vars) {
        ResponseEntity<String> res = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(session), String.class, vars);
        assertThat(res.getStatusCode()).as("GET %s %s → %s", url, vars, res.getBody()).isEqualTo(HttpStatus.OK);
        return parse(res.getBody());
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 파싱 실패: " + json, e);
        }
    }

    // ═════════════════════════════════════════════ DB

    private List<Map<String, Object>> readSettings() {
        return jdbcTemplate.queryForList("SELECT anls_type_cd, vrbl_cd, stng_vl, use_yn, mdfcn_dt"
                + " FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd");
    }

    private long investExcnCnt() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_excn WHERE anls_type_cd = ?", Long.class, INVEST);
        return n == null ? 0L : n;
    }

    private void run(List<RuntimeException> failures, String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
