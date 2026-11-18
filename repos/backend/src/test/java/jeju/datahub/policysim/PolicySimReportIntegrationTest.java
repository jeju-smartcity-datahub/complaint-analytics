package jeju.datahub.policysim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jeju.datahub.dashboard.service.AnalysisVO;
import jeju.datahub.dashboard.service.impl.DashboardPanelMapper;
import jeju.datahub.policysim.service.PolicySimReportCreateRequest;
import jeju.datahub.policysim.service.PolicySimReportScenarioRequest;
import jeju.datahub.policysim.service.PolicySimReportScenarioVO;
import jeju.datahub.policysim.service.PolicySimReportService;
import jeju.datahub.policysim.service.PolicySimReportVO;
import jeju.datahub.policysim.service.PolicySimRequest;
import jeju.datahub.policysim.service.PolicySimSavedResultVO;
import jeju.datahub.policysim.service.PolicySimService;
import jeju.datahub.policysim.service.impl.PolicySimMapper;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.impl.ReportMapper;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 정책 시뮬레이션 시나리오 비교 보고서 통합 테스트 (케이스 1~11 · 13. 케이스 12 는
 * {@code service/impl/PolicySimReportRecommenderTest}).
 * {@code POST /api/analysis/policy-sim/reports} · {@code GET /api/reports/{rptpId}/policy-sim}.
 *
 * <h2>방식은 두 갈래다</h2>
 * <ul>
 *   <li><b>세션이 필요한 단언</b>(생성자 = 세션 사용자 · 소유 확인 · 익명 401 · 바디 400)은 {@code mock-login}
 *       실세션의 HTTP 로 본다. test 체인은 permitAll + CSRF off 라 {@code X-XSRF-TOKEN} 없이 보낸다.</li>
 *   <li><b>그 밖의 저장 규칙</b>(임의 {@code userId} · 60자 이름 · 남의 {@code rsltId} · 재계산 여부)은
 *       {@code PolicySimIntegrationTest} 선례대로 서비스를 직접 부른다.</li>
 * </ul>
 *
 * <h2>🔴 {@code RANDOM_PORT} 라 롤백이 안 먹는다 — 이 테스트가 만든 것만 지운다</h2>
 * {@link #setUp()} 이 {@code max(rptp_id)}·{@code max(excn_id)} 를 기준선으로 잡고 {@link #cleanUp()} 이
 * <b>기준선 초과 + 생성자/실행자가 이 테스트의 사용자</b>({@value #TESTER} · {@value #U2} ·
 * {@link IntegrationLoginSupport#MOCK_ID})인 행만 참조 역순(보고서 → 분석 결과 → 시뮬레이션 결과 → 실행)으로
 * 지운다. 기준선 초과만으로 지우면 같은 DB 에 붙은 로컬 서버가 만든 행까지 날아간다.
 *
 * <h2>🔴 날짜·지역 건수를 박지 않는다</h2>
 * 읍면동은 {@code PolicySimIntegrationTest} 와 같은 연동을 쓰고, 값은 "저장 전후가 같다" 로 단언한다.
 * 보고서명의 날짜는 응답 {@code crtYmd} 에서 뽑는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("정책 시뮬레이션 보고서 저장 · 스냅샷 · 소유 · 이름")
class PolicySimReportIntegrationTest {

    private static final String CREATE_URL = "/api/analysis/policy-sim/reports";
    private static final String SNAPSHOT_URL = "/api/reports/{rptpId}/policy-sim";

    /** 연동 — TB_ADMDST 고정 코드 ({@code PolicySimIntegrationTest} 와 같다) */
    private static final String EMD_YEONDONG = "50110137";

    /** 서비스 직접 호출용 생성자 — 실 사용자와 겹치지 않는 값(VARCHAR(20)) · 정리 범위의 표지 */
    private static final String TESTER = "test-policysim-rpt";
    private static final String TESTER_NM = "정책보고서테스터";

    /** 남의 {@code user_id} — 세션을 만들 수 없는 쪽 ({@code ReportOwnerFilterTest} 선례) */
    private static final String U2 = "owner-u2";

    private static final String MOCK = IntegrationLoginSupport.MOCK_ID;

    private static final String NAME_TITLE = "_정책 시뮬레이션 시나리오 비교";
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PolicySimReportService reportService;

    @Autowired
    private PolicySimService policySimService;

    @Autowired
    private PolicySimMapper policySimMapper;

    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private DashboardPanelMapper dashboardPanelMapper;

    /** 🔴 스프링 빈 — 서비스가 저장·복원에 쓰는 바로 그 매퍼로 트리를 만든다 */
    @Autowired
    private ObjectMapper objectMapper;

    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    /** 기준선 — 시작 시점의 max(id) */
    private long baseRptpId;
    private long baseExcnId;

    @BeforeEach
    void setUp() {
        baseRptpId = maxId("parking.tb_rptp", "rptp_id");
        baseExcnId = maxId("parking.tb_anls_excn", "excn_id");
        // 🔴 markStart() 를 mockSession() 보다 먼저 — 빠지면 startSn=0 이라 cleanUp 이 로그인 이력 전체를 지운다
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
    }

    /** 이 테스트가 남긴 것만 되돌린다. 한 문장이 터져도 나머지를 마저 실행하고 실패는 끝에서 한 번에 드러낸다. */
    @AfterEach
    void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();
        String users = "(?, ?, ?)";
        String excnSub = "(SELECT excn_id FROM parking.tb_anls_excn WHERE excn_id > ? AND excutr_id IN " + users + ")";
        runQuietly(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id > ? AND creatr_id IN " + users,
                baseRptpId, TESTER, U2, MOCK);
        runQuietly(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id IN " + excnSub,
                baseExcnId, TESTER, U2, MOCK);
        runQuietly(failures, "DELETE FROM parking.tb_plcy_smlt_rslt WHERE excn_id IN " + excnSub,
                baseExcnId, TESTER, U2, MOCK);
        runQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id > ? AND excutr_id IN " + users,
                baseExcnId, TESTER, U2, MOCK);
        try {
            auth.cleanUp();
        } catch (RuntimeException e) {
            failures.add(e);
        }
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_rptp WHERE rptp_id > ?"
                + " AND creatr_id IN " + users, Long.class, baseRptpId, TESTER, U2, MOCK))
                .as("남은 테스트 보고서").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_anls_excn WHERE excn_id > ?"
                + " AND excutr_id IN " + users, Long.class, baseExcnId, TESTER, U2, MOCK))
                .as("남은 테스트 실행").isZero();
    }

    // ───────────────────────── 1 · 2. HTTP 401 · 400 ─────────────────────────

    @Test
    @DisplayName("1. HTTP POST 익명(정상 바디) — 401 · 세 표 행 수 불변")
    void anonymousCreateIs401() {
        long[] before = tableCounts();
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = restTemplate.postForEntity(CREATE_URL,
                new HttpEntity<>(body("PDF", 0, List.of(condItem(condMap("VACLOT", 100, 12)))), json), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(tableCounts()).as("401 이면 아무것도 저장하지 않는다").containsExactly(before);
    }

    @Test
    @DisplayName("2. HTTP POST 바디 검증 — 0건 · 16건 · fileFmt 오타 · rsltId+cond · {} · cond 필수 누락 · 평면 조건 → 전부 400 · 행 수 불변")
    void invalidBodiesAre400() {
        long[] before = tableCounts();
        Map<String, Object> ok = condItem(condMap("VACLOT", 100, 12));
        List<Map<String, Object>> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            sixteen.add(condItem(condMap("VACLOT", 100, 12)));
        }
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("rsltId", 1L);
        both.put("cond", condMap("VACLOT", 100, 12));
        Map<String, Object> flat = new LinkedHashMap<>(condMap("VACLOT", 100, 12));   // cond 없이 평면 — "둘 다 없음"
        Map<String, Object> partial = condItem(Map.of("predcMm", 3));

        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        cases.put("scenarios 0건", body("PDF", 0, List.of()));
        cases.put("scenarios 16건", body("PDF", 0, sixteen));
        cases.put("fileFmt 오타", body("HWP", 0, List.of(ok)));
        cases.put("rsltId + cond 둘 다", body("PDF", 0, List.of(both)));
        cases.put("둘 다 없음 {}", body("PDF", 0, List.of(new LinkedHashMap<>())));
        cases.put("cond 안 필수 누락", body("PDF", 0, List.of(partial)));
        cases.put("평면 조건", body("PDF", 0, List.of(flat)));
        for (Map.Entry<String, Map<String, Object>> c : cases.entrySet()) {
            assertThat(postStatus(c.getValue())).as(c.getKey()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(tableCounts()).as("400 이면 아무것도 저장하지 않는다").containsExactly(before);
    }

    // ───────────────────────── 3 · 4. 서비스 직접 — 저장 규칙 ─────────────────────────

    @Test
    @DisplayName("3. 미저장 2건 — 세 표 1행씩 · 실행자=생성자=userId · creatr_nm 50자 절단 · MANUAL · file_path NULL · emdCd 만으로 sgg_nm·regionNm 채움")
    void createTwoUnsavedScenarios() {
        String longNm = "가".repeat(60);
        PolicySimReportVO vo = reportService.createReport(
                request("PDF", 0, unsaved(cond("VACLOT", 100, 12)), unsaved(cond("MULTFLR", 50, 6))), TESTER, longNm);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_anls_excn WHERE excn_id > ?"
                + " AND excutr_id = ?", Long.class, baseExcnId, TESTER)).as("실행 1행").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_anls_rslt WHERE excn_id = ?",
                Long.class, vo.getExcnId())).as("분석 결과 1행").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_rptp WHERE excn_id = ?",
                Long.class, vo.getExcnId())).as("보고서 1행").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_plcy_smlt_rslt WHERE excn_id = ?",
                Long.class, vo.getExcnId())).as("🔴 시뮬레이션 결과 표에는 쓰지 않는다").isZero();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT e.excutr_id, e.anls_type_cd, e.excn_stts_cd, e.sgg_nm, e.emd_cd,"
                        + "       r.creatr_id, r.creatr_nm, r.crt_type, r.file_path, r.file_fmt, r.stts_cd"
                        + "  FROM parking.tb_rptp r JOIN parking.tb_anls_excn e ON e.excn_id = r.excn_id"
                        + " WHERE r.rptp_id = ?", vo.getRptpId());
        assertThat(row.get("excutr_id")).isEqualTo(TESTER);
        assertThat(row.get("creatr_id")).isEqualTo(TESTER);
        assertThat(row.get("creatr_nm")).as("60자 → 앞 50자").isEqualTo(longNm.substring(0, 50));
        assertThat(row.get("anls_type_cd")).isEqualTo("POLICY_SIM");
        assertThat(row.get("excn_stts_cd")).isEqualTo("DONE");
        assertThat(row.get("crt_type")).isEqualTo("MANUAL");
        assertThat(row.get("stts_cd")).isEqualTo("DONE");
        assertThat(row.get("file_fmt")).isEqualTo("PDF");
        assertThat(row.get("file_path")).isNull();
        assertThat(row.get("emd_cd")).isEqualTo(EMD_YEONDONG);
        assertThat(row.get("sgg_nm")).as("sggNm 을 생략해도 TB_ADMDST 의 시군구명이 채워진다").isNotNull();

        ReportListVO header = reportMapper.selectReportById(vo.getRptpId());
        assertThat(header.getRegionNm()).as("목록 지역명이 NULL 이 되지 않는다").isNotNull();

        assertThat(vo.getHeadSeq()).isEqualTo(1);
        assertThat(vo.getScenarios()).extracting(PolicySimReportScenarioVO::getSeq).containsExactly(1, 2);
        assertThat(vo.getScenarios()).allSatisfy(s -> assertThat(s.getRsltId()).isNull());
    }

    @Test
    @DisplayName("4. 저장분 1건 + 미저장 1건 — 저장분 result 는 저장값과 필드 단위로 같다 · TB_PLCY_SMLT_RSLT 행 수 불변")
    void savedScenarioKeepsStoredValues() {
        policySimService.save(cond("VACLOT", 100, 12), TESTER);
        PolicySimSavedResultVO saved = policySimService.selectSavedResults(TESTER, 0, 1).getContent().get(0);
        long plcyBefore = countOf("parking.tb_plcy_smlt_rslt");

        PolicySimReportVO vo = reportService.createReport(
                request("DOCX", 1, stored(saved.getRsltId()), unsaved(cond("NEW", 80, 3))), TESTER, TESTER_NM);

        assertThat(countOf("parking.tb_plcy_smlt_rslt")).as("보고서 생성은 결과 표에 쓰지 않는다").isEqualTo(plcyBefore);
        PolicySimReportScenarioVO s1 = vo.getScenarios().get(0);
        assertThat(s1.getRsltId()).isEqualTo(saved.getRsltId());
        JsonNode result = objectMapper.valueToTree(s1.getResult());
        JsonNode savedTree = objectMapper.valueToTree(saved);
        assertThat(result.size()).isPositive();
        for (Iterator<String> it = result.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            assertThat(result.get(key)).as("result.%s = 저장값", key).isEqualTo(savedTree.get(key));
        }
        assertThat(s1.getParams().get("plcyType")).isEqualTo(saved.getPlcyType());
        assertThat(s1.getParams().get("expandMethod")).isEqualTo(saved.getExpandMethod());
        assertThat(s1.getParams().get("emdCd")).isEqualTo(saved.getEmdCd());
        assertThat(vo.getHeadSeq()).as("headIdx 1 → headSeq 2").isEqualTo(2);
        assertThat(vo.getAnlsPrdExpln()).as("대표(2번) 시나리오의 예측 기간").isEqualTo("예측 기간 3개월");
    }

    // ───────────────────────── 5 · 6. 404 · 400 ─────────────────────────

    @Test
    @DisplayName("5. 남의 rsltId — 404 · 행 수 불변")
    void othersRsltIdIs404() {
        policySimService.save(cond("VACLOT", 100, 12), U2);
        Long othersRsltId = policySimService.selectSavedResults(U2, 0, 1).getContent().get(0).getRsltId();
        long[] before = tableCounts();

        Throwable thrown = catchThrowable(() -> reportService.createReport(
                request("PDF", 0, stored(othersRsltId)), TESTER, TESTER_NM));

        assertStatus(thrown, HttpStatus.NOT_FOUND);
        assertThat(tableCounts()).containsExactly(before);
    }

    @Test
    @DisplayName("6. headIdx 범위 밖 · 같은 rsltId 두 번 — 400 · 행 수 불변")
    void headIdxOutOfRangeAndDuplicateRsltIdAre400() {
        policySimService.save(cond("VACLOT", 100, 12), TESTER);
        Long rsltId = policySimService.selectSavedResults(TESTER, 0, 1).getContent().get(0).getRsltId();
        long[] before = tableCounts();

        assertStatus(catchThrowable(() -> reportService.createReport(
                request("PDF", 1, unsaved(cond("VACLOT", 100, 12))), TESTER, TESTER_NM)), HttpStatus.BAD_REQUEST);
        assertStatus(catchThrowable(() -> reportService.createReport(
                request("PDF", 0, stored(rsltId), stored(rsltId)), TESTER, TESTER_NM)), HttpStatus.BAD_REQUEST);

        assertThat(tableCounts()).containsExactly(before);
    }

    // ───────────────────────── 7 · 8. 스냅샷 ─────────────────────────

    @Test
    @DisplayName("7. 스냅샷 서비스 = 생성 반환값 (JsonNode 비교 · crtYmd 는 따로 1µs 이내)")
    void snapshotEqualsCreated() {
        policySimService.save(cond("MULTFLR", 50, 6), TESTER);
        Long rsltId = policySimService.selectSavedResults(TESTER, 0, 1).getContent().get(0).getRsltId();
        PolicySimReportVO created = reportService.createReport(
                request("PDF", 0, unsaved(cond("VACLOT", 100, 12)), stored(rsltId), unsaved(cond("NEW", 80, 3))),
                TESTER, TESTER_NM);
        PolicySimReportVO snapshot = reportService.selectReportSnapshot(created.getRptpId());

        assertThat(created.getCrtYmd()).isNotNull();
        assertThat(snapshot.getCrtYmd()).isNotNull();
        assertThat(Duration.between(created.getCrtYmd(), snapshot.getCrtYmd()).abs())
                .as("crtYmd: 생성 %s ↔ 스냅샷 %s", created.getCrtYmd(), snapshot.getCrtYmd())
                .isLessThanOrEqualTo(Duration.ofNanos(1_000));

        ObjectNode c = objectMapper.valueToTree(created);
        ObjectNode s = objectMapper.valueToTree(snapshot);
        c.remove("crtYmd");
        s.remove("crtYmd");
        assertThat(s.get("scenarios").size()).isEqualTo(3);
        assertThat(s.get("headSeq")).isEqualTo(c.get("headSeq"));
        assertThat(s.get("scenarios")).as("scenarios 전건 (seq · rcmdYn · rsltId · params · result)")
                .isEqualTo(c.get("scenarios"));
        assertThat(s).as("응답 전체 (crtYmd 제외)").isEqualTo(c);
    }

    @Test
    @DisplayName("8. 없는·다른 유형 rptpId 404 · 세션 POST 201(생성자=세션) · 내 GET 200 · 남(owner-u2) GET 404 · 익명 GET 401")
    void snapshotNotFoundAndOwnership() {
        // 서비스 — 없는 rptpId · 다른 유형
        long missing = maxId("parking.tb_rptp", "rptp_id") + 1_000_000L;
        assertStatus(catchThrowable(() -> reportService.selectReportSnapshot(missing)), HttpStatus.NOT_FOUND);
        long otherType = insertOtherTypeReport();
        assertStatus(catchThrowable(() -> reportService.selectReportSnapshot(otherType)), HttpStatus.NOT_FOUND);

        // 세션 HTTP POST — 생성자 = 세션 사용자
        ResponseEntity<String> created = restTemplate.exchange(CREATE_URL, HttpMethod.POST,
                auth.jsonEntity(session, body("PDF", 0, List.of(condItem(condMap("VACLOT", 100, 12))))), String.class);
        assertThat(created.getStatusCode()).as("세션 POST").isEqualTo(HttpStatus.CREATED);
        long mine = parse(created.getBody()).path("rptpId").asLong();
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT r.creatr_id, e.excutr_id FROM parking.tb_rptp r"
                + " JOIN parking.tb_anls_excn e ON e.excn_id = r.excn_id WHERE r.rptp_id = ?", mine);
        assertThat(row.get("creatr_id")).isEqualTo(MOCK);
        assertThat(row.get("excutr_id")).isEqualTo(MOCK);

        // 대조군 — 내 것은 200
        assertThat(getStatus(mine)).as("내 보고서").isEqualTo(HttpStatus.OK);

        // 남의 것 — 미저장 cond 1건으로 owner-u2 가 만든 보고서
        PolicySimReportVO others = reportService.createReport(
                request("PDF", 0, unsaved(cond("VACLOT", 100, 12))), U2, "남의 이름");
        assertThat(getStatus(others.getRptpId())).as("🔴 남의 보고서 — 원인은 소유자다(대조군 200)")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 익명
        assertThat(restTemplate.getForEntity(SNAPSHOT_URL, String.class, mine).getStatusCode())
                .as("익명 GET").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ───────────────────────── 9 · 10 · 11. 저장분과의 관계 · 대시보드 ─────────────────────────

    @Test
    @DisplayName("9. 저장분을 담은 보고서를 만든 뒤 그 저장분 삭제 — 성공(FK 없음) · 스냅샷은 여전히 200")
    void deletingSavedResultKeepsSnapshot() {
        policySimService.save(cond("VACLOT", 100, 12), MOCK);
        Long rsltId = policySimService.selectSavedResults(MOCK, 0, 1).getContent().get(0).getRsltId();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("rsltId", rsltId);
        ResponseEntity<String> created = restTemplate.exchange(CREATE_URL, HttpMethod.POST,
                auth.jsonEntity(session, body("PDF", 0, List.of(item))), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long rptpId = parse(created.getBody()).path("rptpId").asLong();

        policySimService.deleteSavedResult(MOCK, rsltId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_plcy_smlt_rslt WHERE rslt_id = ?",
                Long.class, rsltId)).as("저장분 삭제 성공").isZero();

        ResponseEntity<String> snap = restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET,
                new HttpEntity<>(session), String.class, rptpId);
        assertThat(snap.getStatusCode()).as("스냅샷은 여전히 열린다").isEqualTo(HttpStatus.OK);
        assertThat(parse(snap.getBody()).path("scenarios").get(0).path("rsltId").asLong()).isEqualTo(rsltId);
    }

    @Test
    @DisplayName("10. 내 저장 결과 목록 — 보고서 생성 전후 totalCount 같다")
    void savedListUnchangedByReport() {
        policySimService.save(cond("VACLOT", 100, 12), TESTER);
        long before = policySimService.selectSavedResults(TESTER, 0, 10).getTotalElements();
        Long rsltId = policySimService.selectSavedResults(TESTER, 0, 1).getContent().get(0).getRsltId();

        reportService.createReport(request("PDF", 0, stored(rsltId), unsaved(cond("NEW", 80, 3))), TESTER, TESTER_NM);

        assertThat(policySimService.selectSavedResults(TESTER, 0, 10).getTotalElements()).isEqualTo(before);
    }

    @Test
    @DisplayName("11. 대시보드 분석 카드(POLICY_SIM) — 보고서용 excn_id 는 없다 · 저장분 실행은 있다(대조군)")
    void dashboardExcludesReportExecutions() {
        policySimService.save(cond("VACLOT", 100, 12), TESTER);
        Long savedExcnId = jdbcTemplate.queryForObject("SELECT max(excn_id) FROM parking.tb_anls_excn"
                + " WHERE excn_id > ? AND excutr_id = ? AND anls_type_cd = 'POLICY_SIM'", Long.class, baseExcnId, TESTER);
        PolicySimReportVO vo = reportService.createReport(
                request("PDF", 0, unsaved(cond("VACLOT", 100, 12))), TESTER, TESTER_NM);

        List<Long> ids = dashboardPanelMapper.selectRecentAnalyses(50, "POLICY_SIM", TESTER).stream()
                .map(AnalysisVO::getExcnId).toList();
        assertThat(ids).as("대조군 — 결과 행이 있는 저장 실행은 카드에 있다").contains(savedExcnId);
        assertThat(ids).as("🔴 보고서용 실행은 카드에 없다").doesNotContain(vo.getExcnId());
    }

    // ───────────────────────── 13. 보고서 이름 ─────────────────────────

    @Test
    @DisplayName("13. 이름 — 같은 사용자 base+1·base+2 · 다른 사용자는 따로 · 첫 것을 지워도 다음은 base+3 (최댓값+1) · 날짜는 응답 crtYmd")
    void reportNameSequence() {
        // 🔴 base 는 첫 호출 직전에 잰다(이전 실행이 기준선 아래에 남긴 행이 있어도 깨지지 않게)
        String baseYmd = YMD.format(LocalDateTime.now());
        int base = policySimMapper.selectMaxRptpSeqOfDay(TESTER, baseYmd);
        int baseU2 = policySimMapper.selectMaxRptpSeqOfDay(U2, baseYmd);

        PolicySimReportVO r1 = createSimple(TESTER);
        String ymd = YMD.format(r1.getCrtYmd().toLocalDate());
        assumeTrue(ymd.equals(baseYmd), "기준선을 잰 뒤 자정을 넘겼다 — 순번 비교가 성립하지 않는다");
        PolicySimReportVO r2 = createSimple(TESTER);
        PolicySimReportVO u2 = createSimple(U2);

        assertThat(r1.getRptpNm()).isEqualTo(name(r1, base + 1));
        assertThat(r2.getRptpNm()).isEqualTo(name(r2, base + 2));
        assertThat(u2.getRptpNm()).as("다른 사용자는 자기 순번").isEqualTo(name(u2, baseU2 + 1));

        jdbcTemplate.update("DELETE FROM parking.tb_rptp WHERE rptp_id = ?", r1.getRptpId());
        PolicySimReportVO r4 = createSimple(TESTER);
        assertThat(r4.getRptpNm()).as("개수가 아니라 최댓값 + 1").isEqualTo(name(r4, base + 3));

        assertThat(jdbcTemplate.queryForObject("SELECT rptp_nm FROM parking.tb_rptp WHERE rptp_id = ?",
                String.class, r4.getRptpId())).isEqualTo(r4.getRptpNm());
    }

    // ───────────────────────── 분석 계수 일시 조정 (케이스 8 · 9) ─────────────────────────

    @Test
    @DisplayName("8. 저장분(ALPHA_VL 조정) + 미저장분(PCSHR_CRTR_VL 조정) + 미저장분(조정 없음) — 시나리오별 params.vrblAjmt 가 각자 값")
    void mixedScenariosKeepOwnVrblAjmt() {
        policySimService.save(withVrbl(cond("VACLOT", 100, 12), "ALPHA_VL", "0.8"), TESTER);
        PolicySimSavedResultVO saved = policySimService.selectSavedResults(TESTER, 0, 1).getContent().get(0);
        BigDecimal alphaStng = vrblVal(saved.getVrblAjmt(), "ALPHA_VL", "stngVl");
        BigDecimal pcshrStng = vrblVal(saved.getVrblAjmt(), "PCSHR_CRTR_VL", "stngVl");
        assumeTrue(alphaStng.compareTo(new BigDecimal("0.8")) != 0, "ALPHA_VL 설정값이 이미 0.8 이다");
        assumeTrue(pcshrStng.compareTo(new BigDecimal("0.6")) != 0, "PCSHR_CRTR_VL 설정값이 이미 0.6 이다");

        PolicySimReportVO vo = reportService.createReport(request("PDF", 0,
                stored(saved.getRsltId()),
                unsaved(withVrbl(cond("MULTFLR", 50, 6), "PCSHR_CRTR_VL", "0.6")),
                unsaved(cond("NEW", 80, 3))), TESTER, TESTER_NM);

        Map<String, Object> a1 = ajmtOf(vo.getScenarios().get(0));
        Map<String, Object> a2 = ajmtOf(vo.getScenarios().get(1));
        Map<String, Object> a3 = ajmtOf(vo.getScenarios().get(2));
        assertThat(a1.get("ajmtYn")).as("저장분 — 저장 당시 조정").isEqualTo("Y");
        assertThat(vrblVal(a1, "ALPHA_VL", "aplcnVl")).isEqualByComparingTo("0.8");
        assertThat(vrblVal(a1, "PCSHR_CRTR_VL", "aplcnVl")).isEqualByComparingTo(pcshrStng);
        assertThat(a2.get("ajmtYn")).as("미저장분 — cond 의 계수").isEqualTo("Y");
        assertThat(vrblVal(a2, "PCSHR_CRTR_VL", "aplcnVl")).isEqualByComparingTo("0.6");
        assertThat(vrblVal(a2, "ALPHA_VL", "aplcnVl")).as("저장분 계수가 새지 않는다").isEqualByComparingTo(alphaStng);
        assertThat(a3.get("ajmtYn")).as("조정 없는 미저장분").isEqualTo("N");

        // 저장 스냅샷(rslt_json)에서 읽어도 같다
        PolicySimReportVO snap = reportService.selectReportSnapshot(vo.getRptpId());
        for (int i = 0; i < 3; i++) {
            JsonNode got = objectMapper.valueToTree(snap.getScenarios().get(i).getParams().get("vrblAjmt"));
            JsonNode want = objectMapper.valueToTree(vo.getScenarios().get(i).getParams().get("vrblAjmt"));
            assertThat(got).as("scenarios[%d].params.vrblAjmt", i).isEqualTo(want);
        }
    }

    @Test
    @DisplayName("9. 재다운로드 재현 — 조정 보고서 생성 → 설정값 직접 변경 → 스냅샷 재조회는 숫자·vrblAjmt 그대로 · 설정값 원복")
    void snapshotSurvivesSettingChange() {
        List<String> codes = List.of("ALPHA_VL", "GEO_CVRG_CRCT_JEJU");
        // 🔴 시작 시점 값을 읽어 두고 finally 에서 되돌린다. 설정 저장 API 를 타지 않아 변경 이력이 생기지 않는다
        List<Map<String, Object>> orig = jdbcTemplate.queryForList(
                "SELECT vrbl_cd, stng_vl, min_vl, max_vl, mdfr_id, mdfcn_dt FROM parking.tb_anls_vrbl_stng"
                        + " WHERE anls_type_cd = 'POLICY_SIM' AND vrbl_cd IN (?, ?) ORDER BY vrbl_cd",
                codes.get(0), codes.get(1));
        assertThat(orig).hasSize(2);
        try {
            PolicySimReportVO created = reportService.createReport(request("PDF", 0,
                    unsaved(withVrbl(cond("VACLOT", 100, 12), "ALPHA_VL", "0.8")),
                    unsaved(cond("NEW", 80, 3))), TESTER, TESTER_NM);

            for (Map<String, Object> r : orig) {
                BigDecimal cur = (BigDecimal) r.get("stng_vl");
                BigDecimal min = (BigDecimal) r.get("min_vl");
                BigDecimal max = (BigDecimal) r.get("max_vl");
                BigDecimal next = cur.compareTo(min) != 0 ? min : max;
                jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?"
                        + " WHERE anls_type_cd = 'POLICY_SIM' AND vrbl_cd = ?", next, r.get("vrbl_cd"));
            }
            // 대조군 — 지금 다시 계산하면 설정값이 바뀐 것이 보인다
            PolicySimReportVO fresh = reportService.createReport(request("PDF", 0,
                    unsaved(withVrbl(cond("VACLOT", 100, 12), "ALPHA_VL", "0.8"))), TESTER, TESTER_NM);
            assertThat(vrblVal(ajmtOf(fresh.getScenarios().get(0)), "GEO_CVRG_CRCT_JEJU", "stngVl"))
                    .as("대조군 — 새 계산은 바뀐 설정값을 읽는다")
                    .isNotEqualByComparingTo(vrblVal(ajmtOf(created.getScenarios().get(0)), "GEO_CVRG_CRCT_JEJU", "stngVl"));

            PolicySimReportVO snapshot = reportService.selectReportSnapshot(created.getRptpId());
            ObjectNode c = objectMapper.valueToTree(created);
            ObjectNode s = objectMapper.valueToTree(snapshot);
            c.remove("crtYmd");
            s.remove("crtYmd");
            assertThat(s.get("scenarios")).as("숫자·vrblAjmt 가 생성 당시 그대로").isEqualTo(c.get("scenarios"));
            assertThat(s).as("응답 전체 (crtYmd 제외)").isEqualTo(c);
        } finally {
            for (Map<String, Object> r : orig) {
                jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?, mdfr_id = ?, mdfcn_dt = ?"
                                + " WHERE anls_type_cd = 'POLICY_SIM' AND vrbl_cd = ?",
                        r.get("stng_vl"), r.get("mdfr_id"), r.get("mdfcn_dt"), r.get("vrbl_cd"));
            }
        }
        List<Map<String, Object>> after = jdbcTemplate.queryForList(
                "SELECT vrbl_cd, stng_vl, min_vl, max_vl, mdfr_id, mdfcn_dt FROM parking.tb_anls_vrbl_stng"
                        + " WHERE anls_type_cd = 'POLICY_SIM' AND vrbl_cd IN (?, ?) ORDER BY vrbl_cd",
                codes.get(0), codes.get(1));
        assertThat(after).as("설정값 원복").isEqualTo(orig);
    }

    private static PolicySimRequest withVrbl(PolicySimRequest req, String code, String value) {
        req.setVrblVls(Map.of(code, new BigDecimal(value)));
        return req;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ajmtOf(PolicySimReportScenarioVO s) {
        Object a = s.getParams().get("vrblAjmt");
        assertThat(a).as("seq=%d params.vrblAjmt", s.getSeq()).isInstanceOf(Map.class);
        return (Map<String, Object>) a;
    }

    /** {@code vrblAjmt.vrbls[vrblCd=code].field} — 스케일·타입(Double·Integer·BigDecimal)에 흔들리지 않게 BigDecimal 로 */
    @SuppressWarnings("unchecked")
    private static BigDecimal vrblVal(Map<String, Object> ajmt, String code, String field) {
        assertThat(ajmt).as("vrblAjmt").isNotNull();
        for (Map<String, Object> v : (List<Map<String, Object>>) ajmt.get("vrbls")) {
            if (code.equals(v.get("vrblCd"))) {
                return new BigDecimal(String.valueOf(v.get(field)));
            }
        }
        throw new AssertionError("vrblAjmt.vrbls 에 " + code + " 가 없다: " + ajmt);
    }

    // ───────────────────────── 헬퍼 ─────────────────────────

    private PolicySimReportVO createSimple(String userId) {
        return reportService.createReport(request("PDF", 0, unsaved(cond("VACLOT", 100, 12))), userId, userId);
    }

    /** 기대 이름 — 날짜는 응답 {@code crtYmd} 에서 뽑는다 */
    private static String name(PolicySimReportVO vo, int seq) {
        return YMD.format(vo.getCrtYmd().toLocalDate()) + "(" + seq + ")" + NAME_TITLE;
    }

    private static PolicySimRequest cond(String method, int slot, int predcMm) {
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("EXPAND");
        req.setExpandMethod(method);
        req.setAddPkltSlot(slot);
        req.setPredcMm(predcMm);
        req.setEmdCd(EMD_YEONDONG);     // sggNm 은 생략 — 서버가 TB_ADMDST 에서 채운다
        return req;
    }

    private static PolicySimReportScenarioRequest unsaved(PolicySimRequest cond) {
        PolicySimReportScenarioRequest s = new PolicySimReportScenarioRequest();
        s.setCond(cond);
        return s;
    }

    private static PolicySimReportScenarioRequest stored(Long rsltId) {
        PolicySimReportScenarioRequest s = new PolicySimReportScenarioRequest();
        s.setRsltId(rsltId);
        return s;
    }

    private static PolicySimReportCreateRequest request(String fileFmt, int headIdx,
                                                        PolicySimReportScenarioRequest... scenarios) {
        PolicySimReportCreateRequest req = new PolicySimReportCreateRequest();
        req.setFileFmt(fileFmt);
        req.setHeadIdx(headIdx);
        req.setScenarios(List.of(scenarios));
        return req;
    }

    private static Map<String, Object> condMap(String method, int slot, int predcMm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plcyType", "EXPAND");
        m.put("expandMethod", method);
        m.put("addPkltSlot", slot);
        m.put("predcMm", predcMm);
        m.put("emdCd", EMD_YEONDONG);
        return m;
    }

    private static Map<String, Object> condItem(Map<String, Object> cond) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("cond", cond);
        return item;
    }

    private static Map<String, Object> body(String fileFmt, int headIdx, List<Map<String, Object>> scenarios) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("fileFmt", fileFmt);
        b.put("headIdx", headIdx);
        b.put("scenarios", scenarios);
        return b;
    }

    private HttpStatus postStatus(Map<String, Object> body) {
        return HttpStatus.valueOf(restTemplate.exchange(CREATE_URL, HttpMethod.POST,
                auth.jsonEntity(session, body), String.class).getStatusCode().value());
    }

    private HttpStatus getStatus(long rptpId) {
        return HttpStatus.valueOf(restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET,
                new HttpEntity<>(session), String.class, rptpId).getStatusCode().value());
    }

    /** 다른 유형(CVLCPT) 실행 1행 + 보고서 1행 — 생성자·실행자는 {@value #TESTER} 라 정리 대상이다 */
    private long insertOtherTypeReport() {
        Long excnId = jdbcTemplate.queryForObject("INSERT INTO parking.tb_anls_excn"
                + " (anls_type_cd, excutr_id, excn_stts_cd, excn_dt) VALUES ('CVLCPT', ?, 'DONE', now()) RETURNING excn_id",
                Long.class, TESTER);
        return jdbcTemplate.queryForObject("INSERT INTO parking.tb_rptp"
                + " (crt_type, excn_id, rptp_nm, stts_cd, file_fmt, creatr_id, creatr_nm, crt_ymd)"
                + " VALUES ('MANUAL', ?, '[테스트] 다른 유형 보고서 (CVLCPT)', 'DONE', 'PDF', ?, ?, now()) RETURNING rptp_id",
                Long.class, excnId, TESTER, TESTER_NM);
    }

    private static void assertStatus(Throwable thrown, HttpStatus expected) {
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(expected);
    }

    /** {@code [tb_anls_excn, tb_anls_rslt, tb_rptp]} 행 수 */
    private long[] tableCounts() {
        return new long[] {countOf("parking.tb_anls_excn"), countOf("parking.tb_anls_rslt"),
                countOf("parking.tb_rptp")};
    }

    private long countOf(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }

    private long maxId(String table, String idCol) {
        Long v = jdbcTemplate.queryForObject("SELECT coalesce(max(" + idCol + "), 0) FROM " + table, Long.class);
        return v == null ? 0L : v;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {   // 4.3 — readTree 가 던지는 파싱 예외만
            throw new IllegalStateException("JSON 파싱 실패: " + json, e);
        }
    }

    private void runQuietly(List<RuntimeException> failures, String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
