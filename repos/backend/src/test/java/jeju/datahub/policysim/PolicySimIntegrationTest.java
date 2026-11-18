package jeju.datahub.policysim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.auth.service.AuthLookupService;
import jeju.datahub.common.PageResponse;
import jeju.datahub.policysim.service.PolicySimRequest;
import jeju.datahub.policysim.service.PolicySimResultVO;
import jeju.datahub.policysim.service.PolicySimSavedResultVO;
import jeju.datahub.policysim.service.PolicySimService;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 정책 시뮬레이션 API 통합 테스트 (TSK-4037 Step 5 — {@code GET /api/analysis/policy-sim} ·
 * {@code POST /api/analysis/policy-sim/results} · Step 7-4 내 저장 목록 {@code GET .../results} · 삭제 {@code DELETE .../results/{rsltId}})
 * + TSK-4038 Step 4 이면도로 정비({@code plcyType=REPAIR}) 계산·검증·저장.
 *
 * <p>🔴 <b>실데이터 값을 단언하지 않는다</b> — 민원 구간은 재적재마다 이동한다(DEC-009). 응답 키 존재·상태코드·
 * null 패턴·저장 행 존재와 데이터 독립 불변식(공한지 3개월 ΔS_eff &gt; 12개월)만 본다.
 *
 * <p>🔴 <b>정리는 이 테스트가 만든 행만</b> — {@code RANDOM_PORT} 라 롤백이 걸리지 않는다. 저장 직전
 * {@code max(excn_id)} 를 기억해 두고, 그 뒤에 생긴 {@code POLICY_SIM} 행만 결과 → 실행 순(FK 방향)으로 지운다.
 * {@code TB_ANLS_EXCN} 은 여러 분석이 공유하는 허브라 범위 DELETE 를 하지 않는다.
 *
 * <p>🔴 <b>저장 API 는 로그인 사용자만</b> — test 프로파일은 permitAll 체인이라 principal 이 null 이고,
 * 그래서 익명 HTTP POST 는 401 만 확인한다. 저장 규칙(excutr_id = 넘긴 user_id 포함)은 서비스를 직접 불러 검증한다.
 * 저장 HTTP 상태(정상 201 · 차단 상태 200 — TSK-4226)만은 {@link IntegrationLoginSupport} 세션으로 POST 해 본다.
 *
 * <p>응답 {@code message} 문자열은 단언하지 않는다(devtools 유무로 갈린다). 실패가 예상되는 호출은
 * {@code String.class} 로 받는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicySimIntegrationTest {

    private static final String SIM_URL = "/api/analysis/policy-sim";
    private static final String SAVE_URL = "/api/analysis/policy-sim/results";

    /** 연동 — TB_ADMDST 고정 코드 */
    private static final String EMD_YEONDONG = "50110137";

    /** 서비스 직접 호출 시 넘기는 실행자 — 실 사용자와 겹치지 않는 값(VARCHAR(20)) */
    private static final String TEST_EXCUTR_ID = "test-policysim";

    /** 목록·삭제 소유권 검증용 두 사용자 — 실 사용자와 겹치지 않는 값 */
    private static final String USER_A = "test-policysim-a";
    private static final String USER_B = "test-policysim-b";

    /** null 이어도 키는 있어야 하는 응답 필드 (계획서 Step 5 검증 항목) */
    private static final List<String> REQUIRED_KEYS = List.of(
            "anlsRngCn", "crtrCvlcptCnt", "crtrIlglCnt", "crtrCngstIdx", "exsPkltCpcty", "vldAddPkltSlot",
            "cvlcptRdctRt", "cngstImpvRt", "aftrCngstIdx", "aftrCvlcptCnt", "calcSttsCd", "excsYn",
            "cstAmt", "cstLvlCd", "aplcnParam");

    private static final List<String> APLCN_KEYS = List.of(
            "occ", "occTh", "alpha", "m", "wFixed", "rRet", "gSgg", "cVacant", "cPublic", "cMultflr",
            "cstLowBndry", "cstHighBndry", "cvlcptPrd", "crdnPrd",
            "n100Rsdnc", "n100Cmrc", "alphaB", "balloonRt");   // 이면도로 4계수 — 확충은 null (TSK-4038)

    /** 이면도로 전용 응답 키 (TSK-4038 Step 4-3) — 확충은 null 이어도 키는 있다 */
    private static final List<String> REPAIR_KEYS = List.of("roadMtncType", "roadMtncLen", "adjcCvlcptCnt", "adjcEmdCnt");

    /** 이면도로 저장 검증용 사용자 — 실 사용자와 겹치지 않는 값 */
    private static final String USER_R = "test-policysim-r";

    /** 연동 600m 주거지역형 — 계획서 Step 4 검증 조건 */
    private static final String REPAIR_Q = "?plcyType=REPAIR&roadMtncType=RSDNC&roadMtncLen=600&predcMm=12";

    @Autowired
    private TestRestTemplate restTemplate;

    /** 대괄호를 인코딩한 그대로 보내려고 절대 URI 를 만든다 (TSK-4332 케이스 7) */
    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PolicySimService policySimService;

    @Autowired
    private AuthLookupService authLookupService;

    /** Spring 설정이 걸린 매퍼 — 서비스 반환 VO 를 응답 JSON 과 같은 모양으로 바꾼다 */
    @Autowired
    private ObjectMapper springObjectMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 이 테스트가 만든 실행 아이디 — 이것만 지운다 */
    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    /** 로그인 세션이 필요한 테스트만 만든다(TSK-4226) — 나머지 테스트에서는 null */
    private IntegrationLoginSupport auth;

    @AfterEach
    void deleteOnlyWhatThisTestCreated() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long id : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?", id);
        }
        for (Long id : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", id);
        }
        createdExcnIds.clear();
        // 🔴 실행 행을 지운 **뒤** — mock 사용자 행·로그인 이력(lgn_sn > startSn)을 정리한다
        if (auth != null) {
            try {
                auth.cleanUp();
            } catch (RuntimeException e) {
                failures.add(e);
            }
            auth = null;
        }
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Object id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    @Test
    @DisplayName("GET 읍면동 — 200 · 계약 필드 키 전부 존재 · aplcnParam 18키(이면도로 4키는 null) · 범위 라벨은 '{시} {읍면동}'")
    void simulateEmdReturnsFullContract() throws Exception {
        ResponseEntity<String> res = restTemplate.getForEntity(
                SIM_URL + "?plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=100&predcMm=12&emdCd=" + EMD_YEONDONG,
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(res.getBody());
        for (String key : REQUIRED_KEYS) {
            assertThat(body.has(key)).as("응답 키 %s 가 없다 (null 이어도 키는 있어야 한다)", key).isTrue();
        }
        JsonNode ap = body.get("aplcnParam");
        for (String key : APLCN_KEYS) {
            assertThat(ap.has(key)).as("aplcnParam.%s 가 없다", key).isTrue();
        }
        assertThat(ap.get("gSgg").has("제주시")).isTrue();
        assertThat(ap.get("gSgg").has("서귀포시")).isTrue();
        assertThat(body.get("anlsRngCn").asText()).startsWith("제주시 ");
        assertThat(body.get("cstLvlCd").asText()).isIn("LOW", "MID", "HIGH");
        assertNullPattern(body);
        // 확충에는 이면도로 키가 null 로 있다 (TSK-4038)
        for (String key : REPAIR_KEYS) {
            assertThat(body.has(key) && body.get(key).isNull()).as("확충 응답 %s = null", key).isTrue();
        }
        assertThat(ap.get("alphaB").isNull()).isTrue();
    }

    @Test
    @DisplayName("GET 시·전체 — 200 · 범위 라벨 '제주시 전체' · '전체 지역'")
    void simulateSggAndAll() throws Exception {
        JsonNode sgg = getOk("?plcyType=EXPAND&expandMethod=NEW&addPkltSlot=50&predcMm=6&sggNm=제주시");
        assertThat(sgg.get("anlsRngCn").asText()).isEqualTo("제주시 전체");
        assertNullPattern(sgg);

        JsonNode all = getOk("?plcyType=EXPAND&expandMethod=MULTFLR&addPkltSlot=200&predcMm=24");
        assertThat(all.get("anlsRngCn").asText()).isEqualTo("전체 지역");
        assertNullPattern(all);
    }

    @Test
    @DisplayName("GET 공한지 — 예측기간이 길수록 반환분만큼 유효 증설 면수가 줄어든다 (3개월 > 12개월)")
    void vaclotShrinksWithPeriod() throws Exception {
        String base = "?plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=100&emdCd=" + EMD_YEONDONG + "&predcMm=";
        double v3 = getOk(base + "3").get("vldAddPkltSlot").asDouble();
        double v12 = getOk(base + "12").get("vldAddPkltSlot").asDouble();
        assertThat(v3).isGreaterThan(v12);
    }

    @Test
    @DisplayName("GET 잘못된 입력 — 전부 400 (500 으로 새지 않는다)")
    void invalidInputsAre400() {
        String ok = "plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=100&predcMm=12";
        List<String> bad = List.of(
                "plcyType=FEE&expandMethod=VACLOT&addPkltSlot=100&predcMm=12",      // FEE 필수(feeAplcnMthd) 누락 (TSK-4039)
                "plcyType=REPAIR&expandMethod=VACLOT&addPkltSlot=100&predcMm=12",   // REPAIR 필수(방식·연장) 누락
                "plcyType=EXPAND&expandMethod=XXX&addPkltSlot=100&predcMm=12",      // 방식 목록 밖
                "plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=70&predcMm=12",    // 면수 목록 밖
                "plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=100&predcMm=5",    // 기간 목록 밖
                "plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=abc&predcMm=12",   // 타입 불일치
                "expandMethod=VACLOT&addPkltSlot=100&predcMm=12",                   // 필수 누락
                ok + "&emdCd=99999999",                                              // 없는 읍면동 (IllegalArgumentException)
                ok + "&emdCd=",                                                      // 빈 값
                ok + "&sggNm=부산시");                                               // 시 목록 밖
        for (String q : bad) {
            ResponseEntity<String> res = restTemplate.getForEntity(SIM_URL + "?" + q, String.class);
            assertThat(res.getStatusCode()).as("query=%s", q).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("POST 익명 — 401 · 아무 행도 남지 않는다 (test 체인은 permitAll 이라 principal 이 null)")
    void anonymousSaveIs401AndInsertsNothing() {
        Long before = maxExcnId();
        ResponseEntity<String> res = restTemplate.postForEntity(SAVE_URL, validRequestMap(), String.class);
        List<Long> ids = policySimIdsAfter(before);
        createdExcnIds.addAll(ids);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ids).isEmpty();
    }

    @Test
    @DisplayName("POST 로그인 · 차단 상태(NO_ENF·NO_SPLY) — 200 · 응답 calcSttsCd 는 차단 코드 · 두 표 행 수 불변 (TSK-4226)")
    void saveBlockedStatusIs200AndInsertsNothing() throws Exception {
        // 🔴 읍면동을 박지 않는다 — 단속 행이 없는 읍면동 중 GET 이 실제로 차단 상태를 내는 첫 후보를 고른다
        List<String> candidates = jdbcTemplate.queryForList(
                "SELECT a.emd_cd FROM parking.tb_admdst a"
                        + " WHERE NOT EXISTS (SELECT 1 FROM parking.tb_crdn_dsctn c WHERE c.emd_cd = a.emd_cd)"
                        + " ORDER BY a.emd_cd",
                String.class);
        Map<String, Object> body = null;
        String blockedStts = null;
        for (String emdCd : candidates) {
            Map<String, Object> req = validRequestMap();
            req.put("emdCd", emdCd);
            ResponseEntity<String> sim = restTemplate.getForEntity(
                    SIM_URL + "?plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=100&predcMm=12&emdCd=" + emdCd,
                    String.class);
            if (!sim.getStatusCode().is2xxSuccessful()) {
                continue;
            }
            JsonNode stts = objectMapper.readTree(sim.getBody()).get("calcSttsCd");
            if (stts != null && !stts.isNull() && PolicySimService.isBlocked(stts.asText())) {
                body = req;
                blockedStts = stts.asText();
                break;
            }
        }
        assumeTrue(body != null, "차단 상태(NO_ENF·NO_SPLY)를 내는 읍면동이 지금 DB 에 없다 — 건너뛴다");

        HttpHeaders session = mockSession();
        Long before = maxExcnId();
        ResponseEntity<String> res;
        try {
            res = restTemplate.exchange(SAVE_URL, HttpMethod.POST, auth.jsonEntity(session, body), String.class);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }

        assertThat(res.getStatusCode()).as("body=%s", res.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(res.getBody()).get("calcSttsCd").asText()).isEqualTo(blockedStts);
        // 🔴 표 전체 count 를 보지 않는다 — 공유 허브라 사용자 서버·배치의 동시 쓰기에 흔들린다
        assertThat(policySimIdsAfter(before)).as("POLICY_SIM 실행 행이 생기지 않는다").isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_plcy_smlt_rslt WHERE excn_id > ?", Long.class, before))
                .as("before 이후 실행을 참조하는 결과 행").isZero();
    }

    @Test
    @DisplayName("POST 로그인 · 정상 — 201 · TB_ANLS_EXCN · TB_PLCY_SMLT_RSLT 각 +1 (TSK-4226)")
    void saveNormalIs201AndInsertsTwoRows() throws Exception {
        HttpHeaders session = mockSession();
        Long before = maxExcnId();
        ResponseEntity<String> res;
        try {
            res = restTemplate.exchange(SAVE_URL, HttpMethod.POST, auth.jsonEntity(session, validRequestMap()), String.class);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }

        assertThat(res.getStatusCode()).as("body=%s", res.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(PolicySimService.isBlocked(objectMapper.readTree(res.getBody()).get("calcSttsCd").asText(null)))
                .isFalse();
        List<Long> ids = policySimIdsAfter(before);
        assertThat(ids).as("POLICY_SIM 실행 1행").hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?", Long.class, ids.get(0)))
                .as("그 실행의 결과 1행").isEqualTo(1L);
    }

    @Test
    @DisplayName("POST 바인딩 실패 — 인증 판정 전에 400 (빈 바디)")
    void emptyBodyIs400() {
        ResponseEntity<String> empty = restTemplate.postForEntity(SAVE_URL, Map.of(), String.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("저장(서비스 직접) — TB_ANLS_EXCN(POLICY_SIM) 1행 + TB_PLCY_SMLT_RSLT 1행 · excutr_id = 넘긴 user_id")
    void saveInsertsOneExecutionAndOneResult() throws Exception {
        Long before = maxExcnId();

        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("EXPAND");
        req.setExpandMethod("VACLOT");
        req.setAddPkltSlot(100);
        req.setPredcMm(12);
        req.setSggNm("서귀포시");      // emdCd 가 있으면 무시되고 TB_ADMDST 값으로 덮인다
        req.setEmdCd(EMD_YEONDONG);
        PolicySimResultVO vo;
        try {
            vo = policySimService.save(req, TEST_EXCUTR_ID);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }

        JsonNode body = springObjectMapper.valueToTree(vo);
        for (String key : REQUIRED_KEYS) {
            assertThat(body.has(key)).as("응답 키 %s", key).isTrue();
        }
        List<Long> ids = policySimIdsAfter(before);
        assertThat(ids).as("POLICY_SIM 실행 1행").hasSize(1);
        Long excnId = ids.get(0);

        Map<String, Object> excn = jdbcTemplate.queryForMap(
                "SELECT excutr_id, excn_stts_cd, sgg_nm, emd_cd, anls_bgng_ymd, anls_end_ymd, anls_param_json::text AS p"
                        + "  FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        assertThat(excn.get("excutr_id")).as("로그인 사용자 user_id 가 그대로 저장된다").isEqualTo(TEST_EXCUTR_ID);
        assertThat(excn.get("excn_stts_cd")).isEqualTo("DONE");
        assertThat(excn.get("emd_cd")).isEqualTo(EMD_YEONDONG);
        assertThat(excn.get("sgg_nm")).as("emdCd 가 있으면 TB_ADMDST 의 시군구명").isEqualTo("제주시");
        assertThat(String.valueOf(excn.get("anls_bgng_ymd"))).matches("\\d{8}");
        assertThat(String.valueOf(excn.get("anls_end_ymd"))).matches("\\d{8}");
        JsonNode p = objectMapper.readTree((String) excn.get("p"));
        assertThat(p.get("plcyType").asText()).isEqualTo("EXPAND");
        assertThat(p.get("addPkltSlot").asInt()).isEqualTo(100);

        List<Map<String, Object>> rslts = jdbcTemplate.queryForList(
                "SELECT plcy_type_cd, predc_mm, add_pklt_slot, rcmdtn_yn, excs_yn, cst_lvl_cd, calc_stts_cd,"
                        + "       cvlcpt_rdct_rt, cngst_impv_rt, aftr_cvlcpt_cnt, aftr_cngst_idx, crtr_cngst_idx,"
                        + "       road_mtnc_type, fee_incrs_rt, aplcn_param_json::text AS ap"
                        + "  FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?", excnId);
        assertThat(rslts).as("결과 1행").hasSize(1);
        Map<String, Object> r = rslts.get(0);
        assertThat(r.get("plcy_type_cd")).isEqualTo("EXPAND");
        assertThat(r.get("predc_mm")).isEqualTo(12);
        assertThat(r.get("add_pklt_slot")).isEqualTo(100);
        assertThat(r.get("rcmdtn_yn")).isEqualTo(false);
        assertThat(r.get("excs_yn")).isEqualTo(body.get("excsYn").asBoolean());
        assertThat(r.get("cst_lvl_cd")).isEqualTo(body.get("cstLvlCd").asText());
        assertThat(r.get("road_mtnc_type")).isNull();
        assertThat(r.get("fee_incrs_rt")).isNull();
        JsonNode ap = objectMapper.readTree((String) r.get("ap"));
        for (String key : APLCN_KEYS) {
            assertThat(ap.has(key)).as("aplcn_param_json.%s", key).isTrue();
        }
        // 응답과 저장의 null 패턴이 같다
        assertThat(r.get("calc_stts_cd") == null).isEqualTo(body.get("calcSttsCd").isNull());
        assertThat(r.get("cvlcpt_rdct_rt") == null).isEqualTo(body.get("cvlcptRdctRt").isNull());
        assertThat(r.get("cngst_impv_rt") == null).isEqualTo(body.get("cngstImpvRt").isNull());
        assertThat(r.get("aftr_cvlcpt_cnt") == null).isEqualTo(body.get("aftrCvlcptCnt").isNull());
        assertThat(r.get("aftr_cngst_idx") == null).isEqualTo(body.get("aftrCngstIdx").isNull());
        assertThat(r.get("crtr_cngst_idx") == null).isEqualTo(body.get("crtrCngstIdx").isNull());
    }

    @Test
    @DisplayName("저장(서비스 직접) 잘못된 입력 — 400 · 아무 행도 남지 않는다")
    void saveInvalidIs400AndInsertsNothing() {
        Long before = maxExcnId();
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("EXPAND");
        req.setExpandMethod("VACLOT");
        req.setAddPkltSlot(100);
        req.setPredcMm(12);
        req.setEmdCd("99999999");
        Throwable thrown;
        try {
            thrown = catchThrowable(() -> policySimService.save(req, TEST_EXCUTR_ID));
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(policySimIdsAfter(before)).isEmpty();
    }

    @Test
    @DisplayName("GET 목록·DELETE 익명 — 401 (test 체인은 permitAll 이라 principal 이 null)")
    void anonymousListAndDeleteAre401() {
        ResponseEntity<String> list = restTemplate.getForEntity(SAVE_URL + "?page=0&size=10", String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> del = restTemplate.exchange(SAVE_URL + "/1", HttpMethod.DELETE, null, String.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("내 저장 목록(서비스 직접) — 내 행만 · 최신순 · 결과 필드·요청 조건·rsltId · 페이징 total · aplcnParam 키 보존 · cstAmt 재구성")
    void listReturnsOnlyMyRowsNewestFirst() throws Exception {
        assertThat(countOf(USER_A)).as("사전 조건 — 테스트 사용자 A 의 잔여 행이 없어야 한다").isZero();
        Long before = maxExcnId();
        try {
            policySimService.save(request("VACLOT", 100, 12), USER_A);
            policySimService.save(request("MULTFLR", 50, 6), USER_A);
            policySimService.save(request("NEW", 80, 3), USER_B);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }

        PageResponse<PolicySimSavedResultVO> page0 = policySimService.selectSavedResults(USER_A, 0, 1);
        assertThat(page0.getTotalElements()).isEqualTo(2);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getPage()).isZero();
        assertThat(page0.getSize()).isEqualTo(1);
        assertThat(page0.getContent()).hasSize(1);
        PageResponse<PolicySimSavedResultVO> page1 = policySimService.selectSavedResults(USER_A, 1, 1);
        assertThat(page1.getContent()).hasSize(1);

        PolicySimSavedResultVO newest = page0.getContent().get(0);
        PolicySimSavedResultVO older = page1.getContent().get(0);
        assertThat(newest.getExpandMethod()).as("최신순 — 나중에 저장한 복층화가 첫 줄").isEqualTo("MULTFLR");
        assertThat(older.getExpandMethod()).isEqualTo("VACLOT");
        assertThat(newest.getRsltId()).isGreaterThan(older.getRsltId());
        assertThat(newest.getExcnDt()).isAfterOrEqualTo(older.getExcnDt());

        List<Long> myRsltIds = rsltIdsOf(USER_A);
        assertThat(List.of(newest.getRsltId(), older.getRsltId())).containsExactlyInAnyOrderElementsOf(myRsltIds);

        // 요청 조건 (anls_param_json)
        assertThat(newest.getPlcyType()).isEqualTo("EXPAND");
        assertThat(newest.getAddPkltSlot()).isEqualTo(50);
        assertThat(newest.getPredcMm()).isEqualTo(6);
        assertThat(newest.getEmdCd()).isEqualTo(EMD_YEONDONG);

        // 응답 JSON 모양 — 결과 필드 전부 + 식별자·조건 키, aplcnParam 14키(gSgg·wFixed·cVacant 보존)
        JsonNode row = springObjectMapper.valueToTree(newest);
        for (String key : REQUIRED_KEYS) {
            assertThat(row.has(key)).as("목록 행 키 %s", key).isTrue();
        }
        for (String key : List.of("rsltId", "excnDt", "plcyType", "expandMethod", "addPkltSlot", "predcMm", "sggNm", "emdCd")) {
            assertThat(row.has(key)).as("목록 행 키 %s", key).isTrue();
        }
        JsonNode ap = row.get("aplcnParam");
        for (String key : APLCN_KEYS) {
            assertThat(ap.has(key)).as("aplcnParam.%s", key).isTrue();
        }
        assertThat(ap.get("gSgg").has("제주시")).isTrue();
        assertNullPattern(row);

        // cstAmt = addPkltSlot × 방식별 단가 (저장된 aplcnParam 으로만 — 재계산하지 않는다)
        assertThat(newest.getCstAmt()).isEqualByComparingTo(
                newest.getAplcnParam().cMultflr().multiply(java.math.BigDecimal.valueOf(50)));
        assertThat(older.getCstAmt()).isEqualByComparingTo(
                older.getAplcnParam().cVacant().multiply(java.math.BigDecimal.valueOf(100)));

        // 범위 밖 페이지는 빈 content · total 유지
        PageResponse<PolicySimSavedResultVO> beyond = policySimService.selectSavedResults(USER_A, 5, 10);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("저장 결과 삭제(서비스 직접) — 남의 것·없는 id 는 404 · 내 것은 결과·실행 두 표에서 사라진다")
    void deleteOnlyMine() {
        Long before = maxExcnId();
        try {
            policySimService.save(request("VACLOT", 100, 12), USER_A);
            policySimService.save(request("VACLOT", 100, 12), USER_B);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }
        Long mine = rsltIdsOf(USER_A).get(0);
        Long others = rsltIdsOf(USER_B).get(0);

        assertNotFound(catchThrowable(() -> policySimService.deleteSavedResult(USER_A, others)));
        assertNotFound(catchThrowable(() -> policySimService.deleteSavedResult(USER_A, Long.MAX_VALUE)));
        assertThat(rsltIdsOf(USER_B)).as("남의 행은 그대로").containsExactly(others);

        Long myExcnId = jdbcTemplate.queryForObject(
                "SELECT excn_id FROM parking.tb_plcy_smlt_rslt WHERE rslt_id = ?", Long.class, mine);
        policySimService.deleteSavedResult(USER_A, mine);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_plcy_smlt_rslt WHERE rslt_id = ?", Integer.class, mine)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_excn WHERE excn_id = ?", Integer.class, myExcnId)).isZero();

        // 이미 지운 것을 다시 지우면 404
        assertNotFound(catchThrowable(() -> policySimService.deleteSavedResult(USER_A, mine)));
    }

    // ─────────────────────────── 이면도로 정비 (TSK-4038 Step 4) ───────────────────────────

    @Test
    @DisplayName("GET REPAIR 연동 600m 주거 — 200 · ΔS 86.40 · TBD · cstAmt null · 인접 0~6곳 · aplcnParam 이면도로 4계수 · aftr÷crtr−1 ≈ R")
    void repairEmd() throws Exception {
        JsonNode body = getOk(REPAIR_Q + "&emdCd=" + EMD_YEONDONG);
        for (String key : REQUIRED_KEYS) {
            assertThat(body.has(key)).as("응답 키 %s", key).isTrue();
        }
        for (String key : REPAIR_KEYS) {
            assertThat(body.has(key)).as("응답 키 %s", key).isTrue();
        }
        JsonNode ap = body.get("aplcnParam");
        for (String key : APLCN_KEYS) {
            assertThat(ap.has(key)).as("aplcnParam.%s", key).isTrue();
        }
        for (String key : List.of("n100Rsdnc", "n100Cmrc", "alphaB", "balloonRt")) {
            assertThat(ap.get(key).isNull()).as("REPAIR aplcnParam.%s 는 값", key).isFalse();
        }
        assertThat(body.get("anlsRngCn").asText()).startsWith("제주시 ");
        assertThat(body.get("roadMtncType").asText()).isEqualTo("RSDNC");
        assertThat(body.get("roadMtncLen").asInt()).isEqualTo(600);
        // ΔS = 6 × n_100(주거) — 계수는 설정 화면에서 바뀌므로 응답의 적용값으로 단언한다
        assertThat(body.get("vldAddPkltSlot").decimalValue())
                .isEqualByComparingTo(ap.get("n100Rsdnc").decimalValue().multiply(java.math.BigDecimal.valueOf(6))
                        .setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(body.get("cstLvlCd").asText()).isEqualTo("TBD");
        assertThat(body.get("cstAmt").isNull()).isTrue();
        // 연동의 이웃은 경계상 6곳(Step 2) — 배분은 그중 E0 > 0 인 곳만
        assertThat(body.get("adjcEmdCnt").asInt()).isBetween(0, 6);
        assertThat(body.get("adjcCvlcptCnt").asDouble()).isGreaterThanOrEqualTo(0.0);
        assertNullPattern(body);
        if (!body.get("cvlcptRdctRt").isNull() && body.get("crtrCvlcptCnt").asDouble() > 0) {
            double ratio = body.get("aftrCvlcptCnt").asDouble() / body.get("crtrCvlcptCnt").asDouble() - 1;
            assertThat(ratio).as("aftrCvlcptCnt 는 인접 증가분 포함 — aftr ÷ crtr − 1 = R")
                    .isCloseTo(body.get("cvlcptRdctRt").asDouble() / 100, org.assertj.core.api.Assertions.within(5e-4));
        }
    }

    @Test
    @DisplayName("GET REPAIR 제주 전체 — 인접 공집합이라 adjcEmdCnt 0 · adjcCvlcptCnt 0 · 라벨 '전체 지역'")
    void repairAllHasNoAdjacent() throws Exception {
        JsonNode body = getOk(REPAIR_Q);
        assertThat(body.get("anlsRngCn").asText()).isEqualTo("전체 지역");
        assertThat(body.get("adjcEmdCnt").asInt()).isZero();
        assertThat(body.get("adjcCvlcptCnt").asDouble()).isZero();
        assertNullPattern(body);
    }

    @Test
    @DisplayName("GET REPAIR 잘못된 입력 — 전부 400 (연장·방식·예측기간 누락 · 연장 범위 밖 · 방식 목록 밖)")
    void repairInvalidInputsAre400() {
        String loc = "&emdCd=" + EMD_YEONDONG;
        List<String> bad = List.of(
                "plcyType=REPAIR&roadMtncType=RSDNC&predcMm=12" + loc,                     // roadMtncLen 누락
                "plcyType=REPAIR&roadMtncType=RSDNC&roadMtncLen=600" + loc,                // predcMm 누락
                "plcyType=REPAIR&roadMtncLen=600&predcMm=12" + loc,                        // roadMtncType 누락
                "plcyType=REPAIR&roadMtncType=RSDNC&roadMtncLen=0&predcMm=12" + loc,       // 하한 밖
                "plcyType=REPAIR&roadMtncType=RSDNC&roadMtncLen=10001&predcMm=12" + loc,   // 상한 밖
                "plcyType=REPAIR&roadMtncType=XXX&roadMtncLen=600&predcMm=12" + loc,       // 방식 목록 밖
                "plcyType=REPAIR&roadMtncType=RSDNC&roadMtncLen=600&predcMm=5" + loc,      // 기간 목록 밖
                "plcyType=REPAIR&roadMtncType=RSDNC&roadMtncLen=600&predcMm=12&emdCd=99999999"); // 없는 읍면동
        for (String q : bad) {
            ResponseEntity<String> res = restTemplate.getForEntity(SIM_URL + "?" + q, String.class);
            assertThat(res.getStatusCode()).as("query=%s", q).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("GET 확충 필드 없는 EXPAND · 이면도로 필드만 있는 EXPAND — 400 (조건부 필수)")
    void expandStillRequiresItsFields() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                SIM_URL + "?plcyType=EXPAND&roadMtncType=RSDNC&roadMtncLen=600&predcMm=12&emdCd=" + EMD_YEONDONG, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST REPAIR — 연장 누락은 인증 판정 전에 400 · 정상 바디 익명은 401 · 아무 행도 남지 않는다")
    void repairPostBindingAndAuth() {
        Long before = maxExcnId();
        Map<String, Object> body = repairRequestMap();
        body.remove("roadMtncLen");
        ResponseEntity<String> bad = restTemplate.postForEntity(SAVE_URL, body, String.class);
        ResponseEntity<String> anon = restTemplate.postForEntity(SAVE_URL, repairRequestMap(), String.class);
        List<Long> ids = policySimIdsAfter(before);
        createdExcnIds.addAll(ids);

        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ids).isEmpty();
    }

    @Test
    @DisplayName("저장 REPAIR(서비스 직접) — REPAIR|12|RSDNC|600|ΔS|adjc=응답값|TBD · add_pklt_slot NULL · anls_param_json 에 roadMtnc* · 내 목록에 이면도로 값")
    void repairSaveAndList() throws Exception {
        assertThat(countOf(USER_R)).as("사전 조건 — 테스트 사용자 R 의 잔여 행이 없어야 한다").isZero();
        Long before = maxExcnId();
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("REPAIR");
        req.setRoadMtncType("RSDNC");
        req.setRoadMtncLen(600);
        req.setPredcMm(12);
        req.setAddPkltSlot(100);        // REPAIR 에서는 쓰지 않는다 — 저장되지 않아야 한다
        req.setEmdCd(EMD_YEONDONG);
        PolicySimResultVO vo;
        try {
            vo = policySimService.save(req, USER_R);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }
        List<Long> ids = policySimIdsAfter(before);
        assertThat(ids).hasSize(1);
        Long excnId = ids.get(0);

        Map<String, Object> r = jdbcTemplate.queryForMap(
                "SELECT plcy_type_cd, predc_mm, road_mtnc_type, road_mtnc_len, vld_add_pklt_slot, adjc_cvlcpt_cnt,"
                        + "       cst_lvl_cd, add_pklt_slot, rcmdtn_yn, excs_yn, aplcn_param_json::text AS ap"
                        + "  FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?", excnId);
        assertThat(r.get("plcy_type_cd")).isEqualTo("REPAIR");
        assertThat(r.get("predc_mm")).isEqualTo(12);
        assertThat(r.get("road_mtnc_type")).isEqualTo("RSDNC");
        assertThat(r.get("road_mtnc_len")).isEqualTo(600);
        assertThat((java.math.BigDecimal) r.get("vld_add_pklt_slot")).isEqualByComparingTo(vo.getVldAddPkltSlot());
        assertThat((java.math.BigDecimal) r.get("adjc_cvlcpt_cnt")).isEqualByComparingTo(vo.getAdjcCvlcptCnt());
        assertThat(r.get("cst_lvl_cd")).isEqualTo("TBD");
        assertThat(r.get("add_pklt_slot")).isNull();
        assertThat(r.get("rcmdtn_yn")).isEqualTo(false);
        assertThat(r.get("excs_yn")).isEqualTo(vo.isExcsYn());
        JsonNode ap = objectMapper.readTree((String) r.get("ap"));
        assertThat(ap.get("alphaB").isNull()).isFalse();
        assertThat(ap.get("balloonRt").isNull()).isFalse();

        JsonNode p = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?", String.class, excnId));
        assertThat(p.get("plcyType").asText()).isEqualTo("REPAIR");
        assertThat(p.get("roadMtncType").asText()).isEqualTo("RSDNC");
        assertThat(p.get("roadMtncLen").asInt()).isEqualTo(600);
        assertThat(p.get("addPkltSlot").isNull()).as("REPAIR 는 면수를 남기지 않는다").isTrue();
        assertThat(p.get("expandMethod").isNull()).isTrue();

        // 내 목록 — 저장값 그대로 (adjcEmdCnt 는 저장 안 함 → null, cstAmt 는 단가 없음 → null)
        PolicySimSavedResultVO saved = policySimService.selectSavedResults(USER_R, 0, 10).getContent().get(0);
        assertThat(saved.getPlcyType()).isEqualTo("REPAIR");
        assertThat(saved.getRoadMtncType()).isEqualTo("RSDNC");
        assertThat(saved.getRoadMtncLen()).isEqualTo(600);
        assertThat(saved.getAdjcCvlcptCnt()).isEqualByComparingTo(vo.getAdjcCvlcptCnt());
        assertThat(saved.getAdjcEmdCnt()).isNull();
        assertThat(saved.getCstAmt()).isNull();
        assertThat(saved.getCstLvlCd()).isEqualTo("TBD");
        assertThat(saved.getExpandMethod()).isNull();
        assertThat(saved.getAddPkltSlot()).isNull();
        assertThat(saved.getAplcnParam().alphaB()).isNotNull();
    }

    @Test
    @DisplayName("AuthLookupService.findUserId — 없는 cert_id · 빈 값은 null")
    void findUserIdReturnsNullForUnknown() {
        assertThat(authLookupService.findUserId("no-such-sub-" + UUID.randomUUID())).isNull();
        assertThat(authLookupService.findUserId(null)).isNull();
        assertThat(authLookupService.findUserId(" ")).isNull();
    }

    // ─────────────────────────── 분석 계수 일시 조정 (TSK-4332 Step 4 — 케이스 1~7 · 10) ───────────────────────────
    // 🔴 실데이터 값을 단언하지 않는다 — "입력 없는 같은 조건" 과 비교하고, 설정값은 응답 vrblAjmt.stngVl 에서 읽는다.

    @Test
    @DisplayName("TSK-4332 1. EXPAND ALPHA_VL=0.8 (로그인 POST 저장) — 결과 변화 · aplcnParam.alpha 0.8 · vrblAjmt.ajmtYn Y · 저장·목록에 적용 내역")
    void ajmtExpandAlphaChangesResultAndIsSaved() throws Exception {
        JsonNode plain = tree(policySimService.simulate(request("VACLOT", 100, 12)));
        assumeTrue(stngVl(plain, "ALPHA_VL").compareTo(new BigDecimal("0.8")) != 0,
                "ALPHA_VL 설정값이 이미 0.8 이다 — 변화를 비교할 수 없다");

        HttpHeaders session = mockSession();
        Map<String, Object> body = validRequestMap();
        body.put("vrblVls", Map.of("ALPHA_VL", 0.8));
        Long before = maxExcnId();
        ResponseEntity<String> res;
        try {
            res = restTemplate.exchange(SAVE_URL, HttpMethod.POST, auth.jsonEntity(session, body), String.class);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }
        assertThat(res.getStatusCode()).as("body=%s", res.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode adj = objectMapper.readTree(res.getBody());

        assertDecimal(adj.at("/aplcnParam/alpha"), "0.8");
        assertThat(adj.at("/vrblAjmt/ajmtYn").asText()).isEqualTo("Y");
        assertDecimal(aplcnVlNode(adj, "ALPHA_VL"), "0.8");
        assertThat(anyDiffers(plain, adj, "cvlcptRdctRt", "aftrCvlcptCnt", "cngstImpvRt", "aftrCngstIdx"))
                .as("α 를 바꾸면 효과 예측이 달라진다").isTrue();

        List<Long> ids = policySimIdsAfter(before);
        assertThat(ids).hasSize(1);
        Map<String, Object> saved = jdbcTemplate.queryForMap(
                "SELECT e.anls_param_json -> 'vrblAjmt' ->> 'ajmtYn' AS ajmt_yn,"
                        + "       r.aplcn_param_json ->> 'alpha' AS alpha"
                        + "  FROM parking.tb_anls_excn e JOIN parking.tb_plcy_smlt_rslt r ON r.excn_id = e.excn_id"
                        + " WHERE e.excn_id = ?", ids.get(0));
        assertThat(saved.get("ajmt_yn")).isEqualTo("Y");
        assertThat(new BigDecimal((String) saved.get("alpha"))).isEqualByComparingTo("0.8");

        PolicySimSavedResultVO newest = policySimService
                .selectSavedResults(IntegrationLoginSupport.MOCK_ID, 0, 1).getContent().get(0);
        assertThat(newest.getVrblAjmt()).as("저장 목록 vrblAjmt").isNotNull();
        assertThat(newest.getVrblAjmt().get("ajmtYn")).isEqualTo("Y");
    }

    @Test
    @DisplayName("TSK-4332 2. REPAIR N100_RSDNC=10 — 확보 구획 ΔS = 6×10 · aplcnParam.n100Rsdnc 10")
    void ajmtRepairN100ChangesSlot() throws Exception {
        JsonNode plain = tree(policySimService.simulate(repairRequest(null)));
        assumeTrue(stngVl(plain, "N100_RSDNC").compareTo(BigDecimal.TEN) != 0, "N100_RSDNC 설정값이 이미 10 이다");

        JsonNode adj = tree(policySimService.simulate(repairRequest(Map.of("N100_RSDNC", BigDecimal.TEN))));
        assertDecimal(adj.at("/aplcnParam/n100Rsdnc"), "10");
        assertDecimal(adj.get("vldAddPkltSlot"), "60");
        assertThat(adj.get("vldAddPkltSlot").decimalValue())
                .isNotEqualByComparingTo(plain.get("vldAddPkltSlot").decimalValue());
        assertThat(adj.at("/vrblAjmt/ajmtYn").asText()).isEqualTo("Y");
    }

    @Test
    @DisplayName("TSK-4332 3. FEE RAISE +30% PRC_ELST_VL=-0.6(범위 끝) — 200 · 결과 변화 · aplcnParam.eps -0.6 · 민감도 범위 있음")
    void ajmtFeeRaiseEpsChangesResult() throws Exception {
        JsonNode plain = tree(policySimService.simulate(feeRaiseRequest(null)));
        assumeTrue(stngVl(plain, "PRC_ELST_VL").compareTo(new BigDecimal("-0.6")) != 0, "PRC_ELST_VL 설정값이 이미 -0.6 이다");

        JsonNode adj = tree(policySimService.simulate(feeRaiseRequest(Map.of("PRC_ELST_VL", new BigDecimal("-0.6")))));
        assertDecimal(adj.at("/aplcnParam/eps"), "-0.6");
        assertThat(adj.get("cvlcptRdctRtRng").isNull()).as("민감도 범위").isFalse();
        assertThat(anyDiffers(plain, adj, "cvlcptRdctRt", "aftrCvlcptCnt", "lvngVhclCnt", "absrpCnt", "trnsfCnt"))
                .as("ε 는 RAISE 에서 계산에 들어간다 — 결과가 달라진다").isTrue();
        assertThat(adj.at("/vrblAjmt/ajmtYn").asText()).isEqualTo("Y");
    }

    @Test
    @DisplayName("TSK-4332 4. 제주시 범위 GEO_CVRG_CRCT_JEJU=2 — 기준선 E0(crtrIlglCnt) 변화(비율 = 2/설정값) · aplcnParam.gSgg.제주시 2")
    void ajmtGeoJejuChangesBaseline() throws Exception {
        PolicySimRequest base = request("NEW", 50, 6);
        base.setEmdCd(null);
        base.setSggNm("제주시");
        JsonNode plain = tree(policySimService.simulate(base));
        BigDecimal g0 = stngVl(plain, "GEO_CVRG_CRCT_JEJU");
        assumeTrue(g0.compareTo(BigDecimal.valueOf(2)) != 0, "GEO_CVRG_CRCT_JEJU 설정값이 이미 2 이다");

        PolicySimRequest req = request("NEW", 50, 6);
        req.setEmdCd(null);
        req.setSggNm("제주시");
        req.setVrblVls(Map.of("GEO_CVRG_CRCT_JEJU", BigDecimal.valueOf(2)));
        JsonNode adj = tree(policySimService.simulate(req));

        assertDecimal(adj.at("/aplcnParam/gSgg/제주시"), "2");
        double e0Plain = plain.get("crtrIlglCnt").asDouble();
        double e0Adj = adj.get("crtrIlglCnt").asDouble();
        assumeTrue(e0Plain >= 10, "제주시 E0 가 너무 작다 — 비율 비교가 반올림에 흔들린다");
        assertThat(e0Adj).as("E0 = m × Enf ÷ 30 × g_sgg — g 를 바꾸면 기준선이 바뀐다").isNotEqualTo(e0Plain);
        assertThat(e0Adj / e0Plain).isCloseTo(2.0 / g0.doubleValue(), org.assertj.core.api.Assertions.within(5e-3));
    }

    @Test
    @DisplayName("TSK-4332 5. 허용 목록 밖 — EXPAND TRNSF_RT · 전 유형 CST_VACLOT·PCSHR_THRSH(고정값) → 400 · 로그인 POST 도 400 · 행 없음")
    void ajmtOutsideScopeIs400() {
        List<PolicySimRequest> bad = new ArrayList<>();
        bad.add(withVrbl(request("VACLOT", 100, 12), "TRNSF_RT", "0.25"));
        for (String code : List.of("CST_VACLOT", "PCSHR_THRSH")) {
            bad.add(withVrbl(request("VACLOT", 100, 12), code, "0.85"));
            bad.add(withVrbl(repairRequest(null), code, "0.85"));
            bad.add(withVrbl(feeRaiseRequest(null), code, "0.85"));
        }
        for (PolicySimRequest req : bad) {
            Throwable thrown = catchThrowable(() -> policySimService.simulate(req));
            assertThat(thrown).as("plcyType=%s vrblVls=%s", req.getPlcyType(), req.getVrblVls())
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        HttpHeaders session = mockSession();
        Map<String, Object> body = validRequestMap();
        body.put("vrblVls", Map.of("TRNSF_RT", 0.25));
        Long before = maxExcnId();
        ResponseEntity<String> res;
        try {
            res = restTemplate.exchange(SAVE_URL, HttpMethod.POST, auth.jsonEntity(session, body), String.class);
        } finally {
            createdExcnIds.addAll(policySimIdsAfter(before));
        }
        assertThat(res.getStatusCode()).as("body=%s", res.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(policySimIdsAfter(before)).as("400 이면 저장하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("TSK-4332 6. 입력 없음 — GET 결과와 같다 · vrblAjmt.ajmtYn N · aplcnVl = stngVl · 빈 {}·설정값과 같은 값도 같은 결과")
    void noAjmtEqualsPlainResult() throws Exception {
        JsonNode get = getOk("?plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=100&predcMm=12&emdCd=" + EMD_YEONDONG);
        JsonNode svc = tree(policySimService.simulate(request("VACLOT", 100, 12)));
        assertThat(svc).as("서비스(입력 없음) = GET").isEqualTo(get);
        assertThat(get.at("/vrblAjmt/ajmtYn").asText()).isEqualTo("N");
        JsonNode vrbls = get.at("/vrblAjmt/vrbls");
        assertThat(vrbls.size()).as("EXPAND 스코프 = 공통 4").isEqualTo(4);
        for (JsonNode v : vrbls) {
            assertThat(v.get("aplcnVl").decimalValue()).as(v.get("vrblCd").asText())
                    .isEqualByComparingTo(v.get("stngVl").decimalValue());
        }

        PolicySimRequest empty = request("VACLOT", 100, 12);
        empty.setVrblVls(Map.of());
        assertThat(tree(policySimService.simulate(empty))).as("vrblVls {}").isEqualTo(get);

        PolicySimRequest same = withVrbl(request("VACLOT", 100, 12), "ALPHA_VL", stngVl(get, "ALPHA_VL").toPlainString());
        assertThat(tree(policySimService.simulate(same))).as("설정값과 같은 값 — 조정 아님(값 비교)").isEqualTo(get);
    }

    @Test
    @DisplayName("TSK-4332 7. GET 쿼리 vrblVls[ALPHA_VL]=0.9 · vrblVls.ALPHA_VL=0.9 — 500 아님 · 무시되어 입력 없는 GET 과 같다")
    void getIgnoresVrblVlsQuery() throws Exception {
        String q = "?plcyType=EXPAND&expandMethod=VACLOT&addPkltSlot=100&predcMm=12&emdCd=" + EMD_YEONDONG;
        JsonNode plain = getOk(q);
        for (String extra : List.of("&vrblVls%5BALPHA_VL%5D=0.9", "&vrblVls.ALPHA_VL=0.9")) {
            ResponseEntity<String> res = restTemplate.getForEntity(
                    java.net.URI.create("http://localhost:" + port + SIM_URL + q + extra), String.class);
            assertThat(res.getStatusCode()).as("extra=%s body=%s", extra, res.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode body = objectMapper.readTree(res.getBody());
            assertThat(body).as("extra=%s — 바인딩이 막혀 결과가 같다", extra).isEqualTo(plain);
            assertThat(body.at("/vrblAjmt/ajmtYn").asText()).isEqualTo("N");
        }
    }

    @Test
    @DisplayName("TSK-4332 10. 조정 실행(3유형) 뒤 설정 표 tb_anls_vrbl_stng · 변경 이력 그대로")
    void ajmtDoesNotTouchSettingTable() throws Exception {
        List<Map<String, Object>> before = settingSnapshot();
        Long hstryBefore = maxHstrySn();

        policySimService.simulate(withVrbl(request("VACLOT", 100, 12), "ALPHA_VL", "0.8"));
        policySimService.simulate(repairRequest(Map.of("N100_RSDNC", BigDecimal.TEN)));
        policySimService.simulate(feeRaiseRequest(Map.of("PRC_ELST_VL", new BigDecimal("-0.6"))));

        assertThat(settingSnapshot()).as("설정 표 전 행").isEqualTo(before);
        assertThat(maxHstrySn()).as("변경 이력 max(chg_hstry_sn)").isEqualTo(hstryBefore);
    }

    private List<Map<String, Object>> settingSnapshot() {
        return jdbcTemplate.queryForList(
                "SELECT anls_type_cd, vrbl_cd, stng_vl::text AS stng_vl, mdfr_id, mdfcn_dt::text AS mdfcn_dt"
                        + "  FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd");
    }

    private Long maxHstrySn() {
        return jdbcTemplate.queryForObject(
                "SELECT coalesce(max(chg_hstry_sn), 0) FROM parking.tb_anls_vrbl_hstry", Long.class);
    }

    /** 서비스 반환 VO → 응답 JSON 과 같은 트리 (Spring 매퍼로 직렬화 → GET 과 같은 파서로 읽는다) */
    private JsonNode tree(Object vo) throws Exception {
        return objectMapper.readTree(springObjectMapper.writeValueAsString(vo));
    }

    private static JsonNode vrblNode(JsonNode result, String code) {
        for (JsonNode v : result.at("/vrblAjmt/vrbls")) {
            if (code.equals(v.get("vrblCd").asText())) {
                return v;
            }
        }
        throw new AssertionError("vrblAjmt.vrbls 에 " + code + " 가 없다: " + result.get("vrblAjmt"));
    }

    private static BigDecimal stngVl(JsonNode result, String code) {
        return vrblNode(result, code).get("stngVl").decimalValue();
    }

    private static JsonNode aplcnVlNode(JsonNode result, String code) {
        return vrblNode(result, code).get("aplcnVl");
    }

    /** 스케일(0.8 vs 0.80)에 흔들리지 않는 숫자 비교 */
    private static void assertDecimal(JsonNode node, String expected) {
        assertThat(node.isMissingNode() || node.isNull()).as("값이 있어야 한다").isFalse();
        assertThat(node.decimalValue()).isEqualByComparingTo(expected);
    }

    private static boolean anyDiffers(JsonNode a, JsonNode b, String... keys) {
        for (String k : keys) {
            JsonNode x = a.get(k);
            JsonNode y = b.get(k);
            boolean xn = x == null || x.isNull();
            boolean yn = y == null || y.isNull();
            if (xn != yn || (!xn && x.decimalValue().compareTo(y.decimalValue()) != 0)) {
                return true;
            }
        }
        return false;
    }

    private static PolicySimRequest withVrbl(PolicySimRequest req, String code, String value) {
        req.setVrblVls(Map.of(code, new BigDecimal(value)));
        return req;
    }

    /** 연동 600m 주거지역형 — {@link #REPAIR_Q} 와 같은 조건 */
    private static PolicySimRequest repairRequest(Map<String, BigDecimal> vrblVls) {
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("REPAIR");
        req.setRoadMtncType("RSDNC");
        req.setRoadMtncLen(600);
        req.setPredcMm(12);
        req.setEmdCd(EMD_YEONDONG);
        req.setVrblVls(vrblVls);
        return req;
    }

    /** 제주 전체 RAISE +30% — 🔴 ε 는 RAISE 에서만 계산에 들어간다 */
    private static PolicySimRequest feeRaiseRequest(Map<String, BigDecimal> vrblVls) {
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("FEE");
        req.setFeeAplcnMthd("RAISE");
        req.setFeeIncrsRt(30);
        req.setFeeAplcnZone("ALL");
        req.setPredcMm(12);
        req.setVrblVls(vrblVls);
        return req;
    }

    private int countOf(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_excn WHERE anls_type_cd = 'POLICY_SIM' AND excutr_id = ?",
                Integer.class, userId);
    }

    private List<Long> rsltIdsOf(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT r.rslt_id FROM parking.tb_plcy_smlt_rslt r JOIN parking.tb_anls_excn e ON e.excn_id = r.excn_id"
                        + " WHERE e.anls_type_cd = 'POLICY_SIM' AND e.excutr_id = ? ORDER BY r.rslt_id",
                Long.class, userId);
    }

    private static PolicySimRequest request(String method, int slot, int predcMm) {
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("EXPAND");
        req.setExpandMethod(method);
        req.setAddPkltSlot(slot);
        req.setPredcMm(predcMm);
        req.setEmdCd(EMD_YEONDONG);
        return req;
    }

    private static void assertNotFound(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * 로그인 세션 — {@code PolicySimReportIntegrationTest} 선례. 🔴 {@code markStart()} 를 {@code mockSession()} 보다 먼저
     * 부른다(빠지면 cleanUp 이 로그인 이력 전체를 지운다). 정리는 {@code @AfterEach} 가 실행 행을 지운 뒤 한다.
     */
    private HttpHeaders mockSession() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        return auth.mockSession();
    }

    private Long maxExcnId() {
        return jdbcTemplate.queryForObject("SELECT coalesce(max(excn_id), 0) FROM parking.tb_anls_excn", Long.class);
    }

    private List<Long> policySimIdsAfter(Long before) {
        return jdbcTemplate.queryForList(
                "SELECT excn_id FROM parking.tb_anls_excn WHERE excn_id > ? AND anls_type_cd = 'POLICY_SIM' ORDER BY excn_id",
                Long.class, before);
    }

    private static Map<String, Object> validRequestMap() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("plcyType", "EXPAND");
        req.put("expandMethod", "VACLOT");
        req.put("addPkltSlot", 100);
        req.put("predcMm", 12);
        req.put("emdCd", EMD_YEONDONG);
        return req;
    }

    private static Map<String, Object> repairRequestMap() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("plcyType", "REPAIR");
        req.put("roadMtncType", "RSDNC");
        req.put("roadMtncLen", 600);
        req.put("predcMm", 12);
        req.put("emdCd", EMD_YEONDONG);
        return req;
    }

    private JsonNode getOk(String query) throws Exception {
        ResponseEntity<String> res = restTemplate.getForEntity(SIM_URL + query, String.class);
        assertThat(res.getStatusCode()).as("query=%s", query).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(res.getBody());
    }

    /** calcSttsCd 별 null 규칙 (Step 1-2 · Step 4) */
    private static void assertNullPattern(JsonNode b) {
        String stts = b.get("calcSttsCd").isNull() ? null : b.get("calcSttsCd").asText();
        if ("NO_ENF".equals(stts)) {
            assertThat(b.get("cvlcptRdctRt").isNull()).isTrue();
            assertThat(b.get("cngstImpvRt").isNull()).isTrue();
            assertThat(b.get("aftrCvlcptCnt").isNull()).isTrue();
            assertThat(b.get("aftrCngstIdx").isNull()).isTrue();
            assertThat(b.get("crtrCvlcptCnt").isNull()).isFalse();
        } else if ("NO_SPLY".equals(stts)) {
            assertThat(b.get("cngstImpvRt").isNull()).isTrue();
            assertThat(b.get("crtrCngstIdx").isNull()).isTrue();
            assertThat(b.get("aftrCngstIdx").isNull()).isTrue();
        } else {
            assertThat(stts).isNull();
            assertThat(b.get("aftrCvlcptCnt").isNull()).isFalse();
            assertThat(b.get("crtrCngstIdx").isNull()).isFalse();
            assertThat(b.get("aftrCngstIdx").isNull()).isFalse();
        }
    }
}
