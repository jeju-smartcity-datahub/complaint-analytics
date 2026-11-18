package jeju.datahub.policysim.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.impl.AnlsVrblOvrdResolver;
import jeju.datahub.policysim.service.PolicySimRequest;
import jeju.datahub.policysim.service.PolicySimResultVO;
import jeju.datahub.policysim.service.PolicySimSavedResultVO;
import jeju.datahub.policysim.service.PolicySimService;

/**
 * 요금제 적용(FEE) API 통합 테스트 ({@code GET /api/analysis/policy-sim?plcyType=FEE} ·
 * {@code POST .../results} 저장 · EXPAND/REPAIR 회귀).
 *
 * <p>🔴 <b>실데이터 고정값을 단언하지 않는다</b> — 민원 구간은 재적재마다 이동하고 {@code tb_pblpklt} 는 분기마다 다시 적재된다.
 * 면수는 <b>독립 SQL</b>(주차장 표 직접 합산 — {@link FeeTargetTest} 와 같은 식)로, 이탈·흡수·전이는 <b>관계식</b>으로 본다.
 * <pre>
 * trnsfCnt     = τ × lvngVhclCnt                       (전이 총량 — τ_local 은 총량을 줄이지 않는다)
 * lvngVhclCnt  ≤ aplcnTrgtSlot × occ × (λ | −ε × p/100)  (NO_ENF 읍면동 면수는 V 에서 빠진다)
 * absrpCnt     ≤ crtrIlglCnt                           (흡수 = Σ min(E0_e, α × V_e))
 * 단일 읍면동  : R × C0 = (τ·τ_local·V − 흡수 + [인접 없음이면 τ(1−τ_local)V]) × C0/E0 + adjcCvlcptCnt
 * </pre>
 *
 * <p>🔴 <b>정리는 이 테스트가 만든 행만</b> — {@code RANDOM_PORT} 라 롤백이 안 먹는다. 저장 직전 {@code max(excn_id)} 이후의
 * {@code POLICY_SIM} 행만 결과 → 실행 순(FK)으로 지운다({@code PolicySimIntegrationTest} 와 같은 규칙).
 * 저장 API(HTTP)는 test 프로파일 permitAll 체인에서 principal 이 null 이라 401 만 확인하고, 저장 규칙은 서비스를 직접 불러 본다 —
 * 그래서 인증 경로를 타지 않아 로그인 이력({@code tb_lgn_hstry}) 행도 만들지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeeIntegrationTest {

    private static final String SIM_URL = "/api/analysis/policy-sim";
    private static final String SAVE_URL = "/api/analysis/policy-sim/results";

    /** 연동 — 동(1급지) */
    private static final String EMD_YEONDONG = "50110137";

    private static final String TEST_EXCUTR_ID = "test-policysim-fee";

    private static final String CONVERT_ALL = "?plcyType=FEE&feeAplcnMthd=CONVERT&feeAplcnZone=ALL&predcMm=12";
    private static final String RAISE_10 = "?plcyType=FEE&feeAplcnMthd=RAISE&feeIncrsRt=10&feeAplcnZone=ALL&predcMm=12";

    /** FEE 응답에만 값이 있는 키 (다른 유형은 null 이어도 키는 있다) */
    private static final List<String> FEE_KEYS = List.of(
            "feeAplcnMthd", "feeAplcnZone", "feeIncrsRt", "aplcnTrgtSlot", "lvngVhclCnt", "absrpCnt", "trnsfCnt",
            "cvlcptRdctRtRng", "wrnngCn");

    /** 4037·4038 계약 키 + 인접 키 (FEE 도 재사용) */
    private static final List<String> COMMON_KEYS = List.of(
            "anlsRngCn", "crtrCvlcptCnt", "crtrIlglCnt", "crtrCngstIdx", "exsPkltCpcty", "vldAddPkltSlot",
            "cvlcptRdctRt", "cngstImpvRt", "aftrCngstIdx", "aftrCvlcptCnt", "calcSttsCd", "excsYn",
            "cstAmt", "cstLvlCd", "adjcCvlcptCnt", "adjcEmdCnt", "aplcnParam");

    private static final List<String> FEE_APLCN_KEYS = List.of("eps", "lambda", "tau", "tauLocal");

    /** 응답 소수 둘째 자리 반올림 오차 허용 */
    private static final double ROUND = 0.02;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PolicySimService policySimService;

    @Autowired
    private ObjectMapper springObjectMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    @AfterEach
    void deleteOnlyWhatThisTestCreated() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long id : createdExcnIds) {
            try {
                jdbcTemplate.update("DELETE FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?", id);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        for (Long id : createdExcnIds) {
            try {
                jdbcTemplate.update("DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", id);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        createdExcnIds.clear();
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    // ─────────────────────────── 400 ───────────────────────────

    @Test
    @DisplayName("GET FEE 잘못된 입력 — 전부 400 (방식 누락·목록 밖 · RAISE 인상률 누락·15 · 급지 목록 밖 · predcMm 누락 · 없는 읍면동)")
    void invalidInputsAre400() {
        List<String> bad = List.of(
                "plcyType=FEE&feeAplcnZone=ALL&predcMm=12",                                   // feeAplcnMthd 누락
                "plcyType=FEE&feeAplcnMthd=XXX&predcMm=12",                                   // 방식 목록 밖
                "plcyType=FEE&feeAplcnMthd=RAISE&feeAplcnZone=ALL&predcMm=12",                // RAISE 인상률 누락
                "plcyType=FEE&feeAplcnMthd=RAISE&feeIncrsRt=15&feeAplcnZone=ALL&predcMm=12",  // 인상률 목록 밖
                "plcyType=FEE&feeAplcnMthd=RAISE&feeIncrsRt=200&predcMm=12",                  // d > 1 경로 — 500 이 아니다
                "plcyType=FEE&feeAplcnMthd=CONVERT&feeAplcnZone=Z3&predcMm=12",               // 급지 목록 밖
                "plcyType=FEE&feeAplcnMthd=CONVERT&feeAplcnZone=ALL",                         // predcMm 누락
                "plcyType=FEE&feeAplcnMthd=CONVERT&predcMm=12&emdCd=99999999");              // 없는 읍면동
        for (String q : bad) {
            ResponseEntity<String> res = restTemplate.getForEntity(SIM_URL + "?" + q, String.class);
            assertThat(res.getStatusCode()).as("query=%s", q).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("POST FEE — 방식 누락은 인증 판정 전에 400 · 정상 바디 익명은 401 · 아무 행도 남지 않는다")
    void postBindingAndAuth() {
        Long before = maxExcnId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plcyType", "FEE");
        body.put("feeAplcnZone", "ALL");
        body.put("predcMm", 12);
        ResponseEntity<String> bad = restTemplate.postForEntity(SAVE_URL, body, String.class);
        body.put("feeAplcnMthd", "CONVERT");
        ResponseEntity<String> anon = restTemplate.postForEntity(SAVE_URL, body, String.class);
        List<Long> ids = policySimIdsAfter(before);
        createdExcnIds.addAll(ids);

        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ids).isEmpty();
    }

    // ─────────────────────────── 200 ───────────────────────────

    @Test
    @DisplayName("GET FEE CONVERT 제주 전체 — 200 · 키 전부 · 면수 = 독립 SQL · trnsf = τ×V · V ≤ 면수×occ×λ · 흡수 ≤ E0 · 인접 없음 · TBD")
    void convertAll() throws Exception {
        JsonNode b = getOk(CONVERT_ALL);
        assertKeys(b);
        JsonNode ap = b.get("aplcnParam");

        assertThat(b.get("anlsRngCn").asText()).isEqualTo("전체 지역");
        assertThat(b.get("feeAplcnMthd").asText()).isEqualTo("CONVERT");
        assertThat(b.get("feeAplcnZone").asText()).isEqualTo("ALL");
        assertThat(b.get("feeIncrsRt").isNull()).isTrue();
        assertThat(b.get("aplcnTrgtSlot").asLong()).isPositive().isEqualTo(expectedSlot("N", null, null));
        assertThat(b.get("vldAddPkltSlot").isNull()).as("공급 불변").isTrue();
        assertThat(b.get("cstAmt").isNull()).isTrue();
        assertThat(b.get("cstLvlCd").asText()).isEqualTo("TBD");
        assertThat(b.get("adjcEmdCnt").asInt()).as("제주 전체는 인접 없음").isZero();
        assertThat(b.get("adjcCvlcptCnt").asDouble()).isZero();

        double occ = ap.get("occ").asDouble();
        double lambda = ap.get("lambda").asDouble();
        double tau = ap.get("tau").asDouble();
        assertRelations(b, b.get("aplcnTrgtSlot").asLong() * occ * lambda, tau);

        // 판정 — V 상한과의 차 = NO_ENF 읍면동 무료 면수 × occ × λ (독립 SQL) · 흡수 < 전이 · R > 0
        long slot = b.get("aplcnTrgtSlot").asLong();
        long noEnf = noEnfSlot("N");
        double v = b.get("lvngVhclCnt").asDouble();
        assertThat(slot * occ * lambda - v).as("V 상한 차 = NO_ENF 무료 면수(%d) × occ × λ", noEnf)
                .isCloseTo(noEnf * occ * lambda, within(ROUND));
        assertThat(b.get("absrpCnt").asDouble()).as("CONVERT 제주 전체 — 흡수 < 전이").isLessThan(b.get("trnsfCnt").asDouble());

        // 민감도 — CONVERT 는 lambda·tau, 각 2원소 퍼센트
        JsonNode rng = b.get("cvlcptRdctRtRng");
        assertThat(fieldNames(rng)).containsExactly("lambda", "tau");
        assertPair(rng.get("lambda"));
        assertPair(rng.get("tau"));
        // R 이 τ 에 선형이라 tau 범위는 base R 을 포함한다
        double r = b.get("cvlcptRdctRt").asDouble();
        assertThat(rng.get("tau").get(0).asDouble()).isLessThanOrEqualTo(r + ROUND);
        assertThat(rng.get("tau").get(1).asDouble()).isGreaterThanOrEqualTo(r - ROUND);
        assertThat(b.get("aftrCvlcptCnt").asDouble() / b.get("crtrCvlcptCnt").asDouble() - 1)
                .isCloseTo(r / 100, within(5e-4));
        assertThat(r).as("CONVERT 제주 전체 — 전이가 흡수보다 커 민원 증가(R > 0)").isPositive();
    }

    @Test
    @DisplayName("GET FEE RAISE +10% 제주 전체 — 면수 = 유료 독립 SQL · V ≤ 면수×occ×(−ε)×0.1 · 민감도 eps·tau(lambda 없음) · feeIncrsRt 10")
    void raiseAll() throws Exception {
        JsonNode b = getOk(RAISE_10);
        assertKeys(b);
        JsonNode ap = b.get("aplcnParam");
        assertThat(b.get("feeAplcnMthd").asText()).isEqualTo("RAISE");
        assertThat(b.get("feeIncrsRt").asInt()).isEqualTo(10);
        assertThat(b.get("aplcnTrgtSlot").asLong()).isPositive().isEqualTo(expectedSlot("Y", null, null));
        double d = -ap.get("eps").asDouble() * 0.10;
        assertRelations(b, b.get("aplcnTrgtSlot").asLong() * ap.get("occ").asDouble() * d, ap.get("tau").asDouble());
        JsonNode rng = b.get("cvlcptRdctRtRng");
        assertThat(fieldNames(rng)).containsExactly("eps", "tau");
        assertPair(rng.get("eps"));

        // 판정 — V = (유료 면수 − NO_ENF 유료 면수) × occ × (−ε) × 0.1 (NO_ENF 유료가 0면이면 = 면수 × occ × (−ε) × 0.1)
        long slot = b.get("aplcnTrgtSlot").asLong();
        long noEnf = noEnfSlot("Y");
        double occ = ap.get("occ").asDouble();
        assertThat(b.get("lvngVhclCnt").asDouble()).as("V = (면수 − NO_ENF 유료 %d) × occ × (−ε) × 0.1", noEnf)
                .isCloseTo((slot - noEnf) * occ * d, within(ROUND));
        assertThat(b.get("absrpCnt").asDouble()).as("RAISE 제주 전체 — 흡수 > 전이").isGreaterThan(b.get("trnsfCnt").asDouble());
        assertThat(b.get("cvlcptRdctRt").asDouble()).as("RAISE 제주 전체 — R < 0").isNegative();
    }

    @Test
    @DisplayName("GET FEE CONVERT Z1 / Z2 — 면수가 각 급지 독립 SQL 과 같고 Z1 + Z2 = ALL · 흡수 Z1 + Z2 ≈ ALL (제주 전체·제주시)")
    void zones() throws Exception {
        JsonNode bz1 = getOk("?plcyType=FEE&feeAplcnMthd=CONVERT&feeAplcnZone=Z1&predcMm=12");
        JsonNode bz2 = getOk("?plcyType=FEE&feeAplcnMthd=CONVERT&feeAplcnZone=Z2&predcMm=12");
        JsonNode ball = getOk(CONVERT_ALL);
        long z1 = bz1.get("aplcnTrgtSlot").asLong();
        long z2 = bz2.get("aplcnTrgtSlot").asLong();
        long all = ball.get("aplcnTrgtSlot").asLong();
        assertThat(z1).isEqualTo(expectedSlot("N", "Z1", null));
        assertThat(z2).isEqualTo(expectedSlot("N", "Z2", null));
        assertThat(z1 + z2).isEqualTo(all);
        // 흡수는 읍면동별 자기 V 로만 정해져 대상을 급지로 나눠도 가산적이다 — 응답 2자리 반올림 허용
        assertThat(bz1.get("absrpCnt").asDouble() + bz2.get("absrpCnt").asDouble())
                .isCloseTo(ball.get("absrpCnt").asDouble(), within(ROUND));
        // 생략 = ALL
        assertThat(getOk("?plcyType=FEE&feeAplcnMthd=CONVERT&predcMm=12").get("aplcnTrgtSlot").asLong()).isEqualTo(all);

        // 🔴 제주시 — 대상 = 제주시 ∩ 급지. 고정 숫자 없이 불변식만 본다(재적재 대비)
        JsonNode sz1 = getOk("?plcyType=FEE&feeAplcnMthd=CONVERT&feeAplcnZone=Z1&predcMm=12&sggNm=제주시");
        JsonNode sz2 = getOk("?plcyType=FEE&feeAplcnMthd=CONVERT&feeAplcnZone=Z2&predcMm=12&sggNm=제주시");
        JsonNode sall = getOk("?plcyType=FEE&feeAplcnMthd=CONVERT&feeAplcnZone=ALL&predcMm=12&sggNm=제주시");
        assertThat(sz1.get("aplcnTrgtSlot").asLong() + sz2.get("aplcnTrgtSlot").asLong())
                .isEqualTo(sall.get("aplcnTrgtSlot").asLong());
        assertThat(sz1.get("absrpCnt").asDouble() + sz2.get("absrpCnt").asDouble())
                .isCloseTo(sall.get("absrpCnt").asDouble(), within(ROUND));
        // R 분모(C0)는 같은 기준선이라 급지로 나눠도 가산적 — 범위 전체 NO_ENF 인 급지는 C0 정의가 달라 제외(R 합은 비가산이라 보지 않는다)
        if (!isNoEnf(sz1) && !isNoEnf(sz2) && !isNoEnf(sall)) {
            assertThat(sz1.get("crtrCvlcptCnt").asDouble() + sz2.get("crtrCvlcptCnt").asDouble())
                    .isCloseTo(sall.get("crtrCvlcptCnt").asDouble(), within(ROUND));
        }
        assertThat(sz1.get("crtrCvlcptCnt").asDouble()).as("제주시 Z1 기준 민원 < 제주시 전체 — 대상이 동으로 좁혀졌다")
                .isLessThan(sall.get("crtrCvlcptCnt").asDouble());
    }

    private static boolean isNoEnf(JsonNode b) {
        return "NO_ENF".equals(b.path("calcSttsCd").asText(null));
    }

    @Test
    @DisplayName("GET FEE 연동 + feeAplcnZone=Z2 — 급지 무시 · 응답 Z1 · 면수 = 연동 무료 독립 SQL · 단일 읍면동 R 분해식")
    void emdIgnoresZone() throws Exception {
        JsonNode b = getOk("?plcyType=FEE&feeAplcnMthd=CONVERT&feeAplcnZone=Z2&predcMm=12&emdCd=" + EMD_YEONDONG);
        assertKeys(b);
        assertThat(b.get("anlsRngCn").asText()).startsWith("제주시 ");
        assertThat(b.get("feeAplcnZone").asText()).isEqualTo("Z1");
        assertThat(b.get("aplcnTrgtSlot").asLong()).isEqualTo(expectedSlot("N", null, EMD_YEONDONG));
        JsonNode ap = b.get("aplcnParam");
        assertRelations(b, b.get("aplcnTrgtSlot").asLong() * ap.get("occ").asDouble() * ap.get("lambda").asDouble(),
                ap.get("tau").asDouble());

        // 단일 읍면동 — k = C0/E0 하나라 R 이 이탈·흡수·전이로 분해된다(부호의 근거)
        if (b.get("calcSttsCd").isNull() && b.get("crtrCvlcptCnt").asDouble() > 0) {
            double c0 = b.get("crtrCvlcptCnt").asDouble();
            double e0 = b.get("crtrIlglCnt").asDouble();
            double v = b.get("lvngVhclCnt").asDouble();
            double tau = ap.get("tau").asDouble();
            double tauLocal = ap.get("tauLocal").asDouble();
            double dE = tau * tauLocal * v - b.get("absrpCnt").asDouble()
                    + (b.get("adjcEmdCnt").asInt() == 0 ? tau * (1 - tauLocal) * v : 0.0);
            double expected = (dE * c0 / e0 + b.get("adjcCvlcptCnt").asDouble()) / c0 * 100;
            assertThat(b.get("cvlcptRdctRt").asDouble()).isCloseTo(expected, within(Math.max(0.05, Math.abs(expected) * 5e-3)));
            assertThat(Math.signum(b.get("cvlcptRdctRt").asDouble())).isEqualTo(Math.signum(expected));
        }
    }

    // ─────────────────────────── 저장 ───────────────────────────

    @Test
    @DisplayName("저장 FEE CONVERT 제주 전체(서비스 직접) — FEE|12|CONVERT|ALL|NULL|면수|V|흡수|전이|TBD · 나머지 NULL · anls_param_json fee*")
    void saveConvertAll() throws Exception {
        Long before = maxExcnId();
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("FEE");
        req.setFeeAplcnMthd("CONVERT");
        req.setFeeAplcnZone("ALL");
        req.setFeeIncrsRt(20);         // CONVERT 에서는 무시 — 저장 NULL
        req.setAddPkltSlot(100);       // 다른 정책 필드 — 저장 안 함
        req.setPredcMm(12);
        PolicySimResultVO vo;
        try {
            vo = policySimService.save(req, TEST_EXCUTR_ID);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }
        JsonNode body = springObjectMapper.valueToTree(vo);
        for (String key : FEE_KEYS) {
            assertThat(body.has(key)).as("저장 응답 키 %s", key).isTrue();
        }
        List<Long> ids = policySimIdsAfter(before);
        assertThat(ids).hasSize(1);
        Long excnId = ids.get(0);

        Map<String, Object> r = jdbcTemplate.queryForMap(
                "SELECT plcy_type_cd, predc_mm, fee_aplcn_mthd, fee_aplcn_zone, fee_incrs_rt, aplcn_trgt_slot, lvng_vhcl_cnt,"
                        + "       absrp_cnt, trnsf_cnt, adjc_cvlcpt_cnt, cst_lvl_cd, add_pklt_slot, road_mtnc_type, road_mtnc_len,"
                        + "       vld_add_pklt_slot, rcmdtn_yn, excs_yn, cvlcpt_rdct_rt, aplcn_param_json::text AS ap"
                        + "  FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?", excnId);
        assertThat(r.get("plcy_type_cd")).isEqualTo("FEE");
        assertThat(r.get("predc_mm")).isEqualTo(12);
        assertThat(r.get("fee_aplcn_mthd")).isEqualTo("CONVERT");
        assertThat(r.get("fee_aplcn_zone")).isEqualTo("ALL");
        assertThat(r.get("fee_incrs_rt")).isNull();
        assertThat(((Number) r.get("aplcn_trgt_slot")).longValue()).isEqualTo(vo.getAplcnTrgtSlot());
        assertThat((BigDecimal) r.get("lvng_vhcl_cnt")).isEqualByComparingTo(vo.getLvngVhclCnt());
        assertThat((BigDecimal) r.get("absrp_cnt")).isEqualByComparingTo(vo.getAbsrpCnt());
        assertThat((BigDecimal) r.get("trnsf_cnt")).isEqualByComparingTo(vo.getTrnsfCnt());
        assertThat((BigDecimal) r.get("adjc_cvlcpt_cnt")).isEqualByComparingTo(vo.getAdjcCvlcptCnt());
        assertThat(r.get("cst_lvl_cd")).isEqualTo("TBD");
        assertThat(r.get("add_pklt_slot")).isNull();
        assertThat(r.get("road_mtnc_type")).isNull();
        assertThat(r.get("road_mtnc_len")).isNull();
        assertThat(r.get("vld_add_pklt_slot")).isNull();
        assertThat(r.get("rcmdtn_yn")).isEqualTo(false);
        assertThat(r.get("excs_yn")).isEqualTo(vo.isExcsYn());
        assertThat(r.get("cvlcpt_rdct_rt") == null).isEqualTo(vo.getCvlcptRdctRt() == null);
        JsonNode ap = objectMapper.readTree((String) r.get("ap"));
        for (String key : FEE_APLCN_KEYS) {
            assertThat(ap.get(key).isNull()).as("aplcn_param_json.%s 는 값", key).isFalse();
        }

        JsonNode p = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?", String.class, excnId));
        assertThat(p.get("plcyType").asText()).isEqualTo("FEE");
        assertThat(p.get("feeAplcnMthd").asText()).isEqualTo("CONVERT");
        assertThat(p.get("feeAplcnZone").asText()).isEqualTo("ALL");
        assertThat(p.get("feeIncrsRt").isNull()).isTrue();
        assertThat(p.get("addPkltSlot").isNull()).isTrue();
        assertThat(p.get("expandMethod").isNull()).isTrue();

        // 내 목록 — FEE 7칸이 저장값 그대로 (CONVERT 는 feeIncrsRt null). 민감도·경고는 응답 전용 → null
        PolicySimSavedResultVO saved = latestSaved();
        assertThat(saved.getFeeAplcnMthd()).isEqualTo("CONVERT");
        assertThat(saved.getFeeAplcnZone()).isEqualTo("ALL");
        assertThat(saved.getFeeIncrsRt()).isNull();
        assertThat(saved.getAplcnTrgtSlot()).isEqualTo(vo.getAplcnTrgtSlot());
        assertThat(saved.getLvngVhclCnt()).isEqualByComparingTo(vo.getLvngVhclCnt());
        assertThat(saved.getAbsrpCnt()).isEqualByComparingTo(vo.getAbsrpCnt());
        assertThat(saved.getTrnsfCnt()).isEqualByComparingTo(vo.getTrnsfCnt());
        assertThat(saved.getAdjcCvlcptCnt()).isEqualByComparingTo(vo.getAdjcCvlcptCnt());
        assertThat(saved.getCvlcptRdctRtRng()).isNull();
        assertThat(saved.getWrnngCn()).isNull();
        assertThat(saved.getRoadMtncType()).isNull();
    }

    @Test
    @DisplayName("저장 FEE RAISE 20% 연동(서비스 직접) — fee_incrs_rt 20 · fee_aplcn_zone = 서버가 채운 Z1")
    void saveRaiseEmd() {
        Long before = maxExcnId();
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("FEE");
        req.setFeeAplcnMthd("RAISE");
        req.setFeeAplcnZone("Z2");
        req.setFeeIncrsRt(20);
        req.setPredcMm(6);
        req.setEmdCd(EMD_YEONDONG);
        PolicySimResultVO vo;
        try {
            vo = policySimService.save(req, TEST_EXCUTR_ID);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }
        Long excnId = policySimIdsAfter(before).get(0);
        Map<String, Object> r = jdbcTemplate.queryForMap(
                "SELECT fee_aplcn_mthd, fee_aplcn_zone, fee_incrs_rt, predc_mm FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?",
                excnId);
        assertThat(r.get("fee_aplcn_mthd")).isEqualTo("RAISE");
        assertThat(r.get("fee_aplcn_zone")).isEqualTo("Z1");
        assertThat((BigDecimal) r.get("fee_incrs_rt")).isEqualByComparingTo("20");
        assertThat(r.get("predc_mm")).isEqualTo(6);
        Map<String, Object> e = jdbcTemplate.queryForMap(
                "SELECT anls_param_json ->> 'feeAplcnZone' AS z, (anls_param_json ->> 'feeIncrsRt')::int AS rt"
                        + "  FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        assertThat(e.get("z")).isEqualTo("Z1");
        assertThat(e.get("rt")).isEqualTo(20);

        // 내 목록 — RAISE 는 feeIncrsRt 가 정수 20 (DB NUMERIC(5,2) → 응답 Integer)
        PolicySimSavedResultVO saved = latestSaved();
        assertThat(saved.getFeeAplcnMthd()).isEqualTo("RAISE");
        assertThat(saved.getFeeAplcnZone()).isEqualTo("Z1");
        assertThat(saved.getFeeIncrsRt()).isEqualTo(20);
        assertThat(saved.getAplcnTrgtSlot()).isEqualTo(vo.getAplcnTrgtSlot());
        assertThat(saved.getLvngVhclCnt()).isEqualByComparingTo(vo.getLvngVhclCnt());
        assertThat(saved.getAbsrpCnt()).isEqualByComparingTo(vo.getAbsrpCnt());
        assertThat(saved.getTrnsfCnt()).isEqualByComparingTo(vo.getTrnsfCnt());
    }

    /** 테스트 사용자의 내 목록 첫 행(최신 저장분) — 이 테스트가 방금 저장한 FEE 행이어야 한다 */
    private PolicySimSavedResultVO latestSaved() {
        List<PolicySimSavedResultVO> list = policySimService.selectSavedResults(TEST_EXCUTR_ID, 0, 1).getContent();
        assertThat(list).isNotEmpty();
        PolicySimSavedResultVO saved = list.get(0);
        assertThat(saved.getPlcyType()).isEqualTo("FEE");
        return saved;
    }

    @Test
    @DisplayName("저장 FEE CONVERT 단속 최소 읍면동(서비스 직접) — numeric overflow 없이 저장 · 저장 R = 응답 R (NUMERIC(7,2))")
    void saveSingleEmdConvert() {
        Long before = maxExcnId();
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("FEE");
        req.setFeeAplcnMthd("CONVERT");
        req.setPredcMm(12);
        req.setEmdCd(fewestCrdnFreeEmd());
        PolicySimResultVO vo;
        try {
            vo = policySimService.save(req, TEST_EXCUTR_ID);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }
        List<Long> ids = policySimIdsAfter(before);
        assertThat(ids).hasSize(1);
        BigDecimal saved = jdbcTemplate.queryForObject(
                "SELECT cvlcpt_rdct_rt FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?", BigDecimal.class, ids.get(0));
        if (vo.getCvlcptRdctRt() == null) {
            assertThat(saved).isNull();
        } else {
            assertThat(saved).isEqualByComparingTo(vo.getCvlcptRdctRt());
        }
    }

    // ─────────────────────────── 회귀 ───────────────────────────

    @Test
    @DisplayName("EXPAND · REPAIR 회귀 — 여전히 200 · 요금제 키는 null · aplcnParam 요금제 4계수 null")
    void otherPoliciesUnchanged() throws Exception {
        for (String q : List.of(
                "?plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=100&predcMm=12&emdCd=" + EMD_YEONDONG,
                "?plcyType=REPAIR&roadMtncType=RSDNC&roadMtncLen=600&predcMm=12&emdCd=" + EMD_YEONDONG)) {
            JsonNode b = getOk(q);
            assertThat(b.get("cvlcptRdctRt").isNull()).as("query=%s", q).isFalse();
            for (String key : FEE_KEYS) {
                assertThat(b.has(key) && b.get(key).isNull()).as("%s → %s = null", q, key).isTrue();
            }
            for (String key : FEE_APLCN_KEYS) {
                assertThat(b.get("aplcnParam").get(key).isNull()).as("%s → aplcnParam.%s = null", q, key).isTrue();
            }
        }
    }

    // ─────────────────────────── 통합 검증 ───────────────────────────

    @Test
    @DisplayName("occ=0.70 CONVERT 제주 전체 — DB 계수 불변 · 계수만 주입한 서비스로 실 기준선 계산 · 흡수 0 · R 이 0.85 때보다 큰 양수 · 경고 문구")
    void occ070Convert() {
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("FEE");
        req.setFeeAplcnMthd("CONVERT");
        req.setFeeAplcnZone("ALL");
        req.setPredcMm(12);

        PolicySimResultVO base = policySimService.simulate(req);
        PolicySimResultVO low = serviceWithOcc(new BigDecimal("0.70")).simulate(req);

        assertThat(low.getAplcnParam().occ()).as("주입한 occ 가 적용값으로 기록된다").isEqualByComparingTo("0.70");
        assertThat(low.getAbsrpCnt()).as("occ < occ_th → 흡수 0").isEqualByComparingTo("0");
        assertThat(low.getWrnngCn()).isEqualTo(FeePolicyCalculator.WRNNG_NO_ABSORB);
        assertThat(base.getWrnngCn()).as("0.85 는 occ_th 이상 — 경고 없음").isNull();
        assertThat(low.getCvlcptRdctRt()).isNotNull();
        assertThat(low.getCvlcptRdctRt().signum()).isPositive();
        assertThat(low.getCvlcptRdctRt()).as("흡수가 꺼지면 R 이 더 커진다").isGreaterThan(base.getCvlcptRdctRt());
        // 기준선 민원·단속은 occ 와 무관
        assertThat(low.getCrtrCvlcptCnt()).isEqualByComparingTo(base.getCrtrCvlcptCnt());
        assertThat(low.getCrtrIlglCnt()).isEqualByComparingTo(base.getCrtrIlglCnt());
        // DB 계수는 그대로 0.85
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stng_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = 'POLICY_SIM' AND vrbl_cd = 'PCSHR_CRTR_VL'",
                BigDecimal.class)).isEqualByComparingTo(base.getAplcnParam().occ());
    }

    @Test
    @DisplayName("세 정책 공통 기준선 — 연동 predcMm=12 EXPAND·REPAIR·FEE 의 crtrCvlcptCnt·crtrIlglCnt·crtrCngstIdx 동일")
    void threePoliciesShareBaseline() throws Exception {
        String base = "?predcMm=12&emdCd=" + EMD_YEONDONG + "&";
        List<JsonNode> res = new ArrayList<>();
        for (String q : List.of("plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=100",
                "plcyType=REPAIR&roadMtncType=RSDNC&roadMtncLen=600",
                "plcyType=FEE&feeAplcnMthd=CONVERT")) {
            res.add(getOk(base + q));
        }
        for (String key : List.of("crtrCvlcptCnt", "crtrIlglCnt", "crtrCngstIdx")) {
            assertThat(res.get(0).get(key).isNull()).as("연동 %s 는 값(비교가 공허하지 않다)", key).isFalse();
            assertThat(res.get(1).get(key).decimalValue()).as("REPAIR %s", key).isEqualByComparingTo(res.get(0).get(key).decimalValue());
            assertThat(res.get(2).get(key).decimalValue()).as("FEE %s", key).isEqualByComparingTo(res.get(0).get(key).decimalValue());
        }
    }

    @Test
    @DisplayName("내 저장 목록 — EXPAND·REPAIR·FEE 저장분이 한 목록에 섞여 최신순(excn_dt DESC, rslt_id DESC)으로 나온다")
    void savedListMixesThreePolicies() {
        Long before = maxExcnId();
        List<String> order = List.of("EXPAND", "REPAIR", "FEE");
        try {
            for (String type : order) {
                PolicySimRequest req = new PolicySimRequest();
                req.setPlcyType(type);
                req.setPredcMm(12);
                req.setEmdCd(EMD_YEONDONG);
                switch (type) {
                    case "EXPAND" -> { req.setExpandMethod("VACLOT"); req.setAddPkltSlot(100); }
                    case "REPAIR" -> { req.setRoadMtncType("RSDNC"); req.setRoadMtncLen(600); }
                    default -> req.setFeeAplcnMthd("CONVERT");
                }
                policySimService.save(req, TEST_EXCUTR_ID);
            }
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }
        assertThat(policySimIdsAfter(before)).hasSize(3);
        List<PolicySimSavedResultVO> list = policySimService.selectSavedResults(TEST_EXCUTR_ID, 0, 3).getContent();
        assertThat(list).extracting(PolicySimSavedResultVO::getPlcyType).containsExactly("FEE", "REPAIR", "EXPAND");
        for (int i = 1; i < list.size(); i++) {
            PolicySimSavedResultVO prev = list.get(i - 1);
            PolicySimSavedResultVO cur = list.get(i);
            assertThat(prev.getExcnDt()).isAfterOrEqualTo(cur.getExcnDt());
            if (prev.getExcnDt().isEqual(cur.getExcnDt())) {
                assertThat(prev.getRsltId()).isGreaterThan(cur.getRsltId());
            }
        }
    }

    @Autowired
    private PolicySimMapper policySimMapper;

    @Autowired
    private jeju.datahub.analysis.service.impl.AnalysisWeightMapper analysisWeightMapper;

    @Autowired
    private jeju.datahub.analysis.service.impl.AnlsExcnRepository anlsExcnRepository;

    @Autowired
    private PlcySmltRsltRepository plcySmltRsltRepository;

    /**
     * {@code PCSHR_CRTR_VL} 만 바꿔 읽히는 서비스 — 🔴 DB 계수를 바꾸지 않는다(4037 공유 계수라 다른 정책·테스트 기준선이 함께 틀어진다).
     * 매퍼 응답 사본의 그 행만 고치고, 기준선 원자료(민원·단속·면수)는 실 매퍼로 읽는다. simulate 전용(저장하지 않는다).
     */
    private PolicySimServiceImpl serviceWithOcc(BigDecimal occ) {
        jeju.datahub.analysis.service.impl.AnalysisWeightMapper real = analysisWeightMapper;
        jeju.datahub.analysis.service.impl.AnalysisWeightMapper overridden =
                (jeju.datahub.analysis.service.impl.AnalysisWeightMapper) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {jeju.datahub.analysis.service.impl.AnalysisWeightMapper.class},
                        (proxy, method, args) -> {
                            Object out = method.invoke(real, args);
                            if ("selectWeightsByType".equals(method.getName())) {
                                @SuppressWarnings("unchecked")
                                List<jeju.datahub.analysis.service.AnalysisWeightVO> rows =
                                        (List<jeju.datahub.analysis.service.AnalysisWeightVO>) out;
                                rows.stream().filter(r -> PolicySimParams.PCSHR_CRTR_VL.equals(r.getVrblCd()))
                                        .forEach(r -> { r.setStngVl(occ); r.setUseYn("Y"); });
                            }
                            return out;
                        });
        return new PolicySimServiceImpl(new PolicySimBaselineCalculator(overridden, policySimMapper),
                new ExpandPolicyCalculator(), new RepairPolicyCalculator(), new PolicySimOutputCalculator(),
                anlsExcnRepository, plcySmltRsltRepository, policySimMapper, springObjectMapper,
                new AnlsVrblOvrdResolver(overridden));
    }

    /**
     * 기준 구간에 단속이 있으면서 가장 적은, 무료 공영주차장이 있는 읍면동 — 단일 읍면동 CONVERT 의 R 이 999.99% 를
     * 넘을 수 있어 {@code cvlcpt_rdct_rt NUMERIC(7,2)} 확인에 쓴다. 🔴 구간이 실행일로 움직여 코드를 박지 않는다.
     */
    private String fewestCrdnFreeEmd() {
        PolicySimBaselineCalculator.BaselineWindow window =
                new PolicySimBaselineCalculator(analysisWeightMapper, policySimMapper).currentWindow();
        return jdbcTemplate.queryForObject("""
                SELECT d.emd_cd
                  FROM parking.tb_crdn_dsctn d
                 WHERE d.crdn_dt >= ? AND d.crdn_dt < ?
                   AND EXISTS (SELECT 1 FROM parking.tb_pblpklt p
                                WHERE p.emd_cd = d.emd_cd
                                  AND p.extsh_dt IS NULL
                                  AND p.ownshp_type_nm IS DISTINCT FROM '민영'
                                  AND coalesce(p.prk_crg_yn, 'N') = 'N'
                                  AND coalesce(p.pklt_cpcty, 0) > 0)
                 GROUP BY d.emd_cd
                 ORDER BY count(*), d.emd_cd
                 LIMIT 1
                """, String.class, window.bgngYmd().atStartOfDay(), window.endYmdExcl().atStartOfDay());
    }

    /** NO_ENF 읍면동(단속 구간에 단속 0건 → E0 = 0)의 무료(N)/유료(Y) 면수 — 독립 SQL. 🔴 FIX_WGVL·g_sgg &gt; 0 전제(현 계수) */
    private long noEnfSlot(String crgYn) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = 'POLICY_SIM'"
                        + " AND vrbl_cd IN ('FIX_WGVL','DSCL_MLTP','GEO_CVRG_CRCT_JEJU','GEO_CVRG_CRCT_SEOGWIPO')"
                        + " AND coalesce(CASE WHEN use_yn = 'Y' THEN stng_vl END, bsc_stng_vl) > 0", Integer.class))
                .as("NO_ENF = 단속 0건 등가 전제 — 단속 가중 계수 4종 > 0").isEqualTo(4);
        PolicySimBaselineCalculator.BaselineWindow window =
                new PolicySimBaselineCalculator(analysisWeightMapper, policySimMapper).currentWindow();
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(p.pklt_cpcty), 0)
                  FROM parking.tb_pblpklt p
                  JOIN parking.tb_admdst a ON a.emd_cd = p.emd_cd
                 WHERE (p.ownshp_type_nm IS NULL OR p.ownshp_type_nm <> '민영')
                   AND p.extsh_dt IS NULL
                   AND CASE WHEN p.prk_crg_yn = 'Y' THEN 'Y' WHEN p.prk_crg_yn IS NULL OR p.prk_crg_yn = 'N' THEN 'N' ELSE p.prk_crg_yn END = ?
                   AND NOT EXISTS (SELECT 1 FROM parking.tb_crdn_dsctn d
                                    WHERE d.emd_cd = a.emd_cd
                                      AND d.crdn_dt >= ? AND d.crdn_dt < ?)
                """, Long.class, crgYn, window.bgngYmd().atStartOfDay(), window.endYmdExcl().atStartOfDay());
    }

    // ─────────────────────────── 도우미 ───────────────────────────

    /** 이탈·흡수·전이 관계식 — 반올림 오차(소수 둘째 자리) 허용 */
    private static void assertRelations(JsonNode b, double vUpper, double tau) {
        double v = b.get("lvngVhclCnt").asDouble();
        double abs = b.get("absrpCnt").asDouble();
        double tr = b.get("trnsfCnt").asDouble();
        assertThat(v).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(vUpper + ROUND);
        assertThat(tr).as("trnsfCnt = τ × lvngVhclCnt").isCloseTo(tau * v, within(ROUND));
        assertThat(abs).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(b.get("crtrIlglCnt").asDouble() + ROUND);
        if (b.get("wrnngCn").isNull()) {
            assertThat(abs).as("흡수가 켜졌으면 흡수 ≤ α × V").isLessThanOrEqualTo(b.get("aplcnParam").get("alpha").asDouble() * v + ROUND);
        } else {
            assertThat(abs).as("occ < occ_th → 흡수 0").isZero();
        }
    }

    private static void assertKeys(JsonNode b) {
        for (String key : COMMON_KEYS) {
            assertThat(b.has(key)).as("응답 키 %s", key).isTrue();
        }
        for (String key : FEE_KEYS) {
            assertThat(b.has(key)).as("응답 키 %s", key).isTrue();
        }
        for (String key : FEE_APLCN_KEYS) {
            assertThat(b.get("aplcnParam").get(key).isNull()).as("aplcnParam.%s 는 값", key).isFalse();
        }
    }

    private static void assertPair(JsonNode pair) {
        assertThat(pair.isArray()).isTrue();
        assertThat(pair).hasSize(2);
        assertThat(pair.get(0).asDouble()).isLessThanOrEqualTo(pair.get(1).asDouble());
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> names = new ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** 독립 대조 — 주차장 표 직접 합산(매퍼와 다른 식, {@link FeeTargetTest} 와 같다). zone: Z1/Z2/null(전체) */
    private long expectedSlot(String crgYn, String zone, String emdCd) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(p.pklt_cpcty), 0)
                  FROM parking.tb_pblpklt p
                  JOIN parking.tb_admdst a ON a.emd_cd = p.emd_cd
                 WHERE (p.ownshp_type_nm IS NULL OR p.ownshp_type_nm <> '민영')
                   AND p.extsh_dt IS NULL
                   AND CASE WHEN p.prk_crg_yn = 'Y' THEN 'Y' WHEN p.prk_crg_yn IS NULL OR p.prk_crg_yn = 'N' THEN 'N' ELSE p.prk_crg_yn END = ?
                """);
        List<Object> args = new ArrayList<>(List.of(crgYn));
        if (emdCd != null) {
            sql.append(" AND a.emd_cd = ?");
            args.add(emdCd);
        } else {
            if ("Z1".equals(zone)) sql.append(" AND right(a.emd_nm, 1) NOT IN ('읍','면')");
            if ("Z2".equals(zone)) sql.append(" AND right(a.emd_nm, 1) IN ('읍','면')");
        }
        return jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
    }

    private JsonNode getOk(String query) throws Exception {
        ResponseEntity<String> res = restTemplate.getForEntity(SIM_URL + query, String.class);
        assertThat(res.getStatusCode()).as("query=%s body=%s", query, res.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(res.getBody());
    }

    private Long maxExcnId() {
        return jdbcTemplate.queryForObject("SELECT coalesce(max(excn_id), 0) FROM parking.tb_anls_excn", Long.class);
    }

    private List<Long> policySimIdsAfter(Long before) {
        return jdbcTemplate.queryForList(
                "SELECT excn_id FROM parking.tb_anls_excn WHERE excn_id > ? AND anls_type_cd = 'POLICY_SIM' ORDER BY excn_id",
                Long.class, before);
    }
}
