package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.ZoneRcmdtnTestData.Win;
import jeju.datahub.analysis.service.ZoneRcmdtnReportCreateRequest;
import jeju.datahub.analysis.service.ZoneRcmdtnReportService;
import jeju.datahub.analysis.service.ZoneRcmdtnReportVO;
import jeju.datahub.analysis.service.ZoneRcmdtnService;
import jeju.datahub.analysis.service.ZoneRcmdtnShareReportCreateRequest;
import jeju.datahub.analysis.service.ZoneRcmdtnShareResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareService;

/**
 * 구역추천 보고서 2종(요금제 적용 · 공유주차제 적용) <b>저장 → 스냅샷 조회</b> 통합 테스트.
 * ({@code POST /api/analysis/zone-recommendation/reports} ·
 * {@code POST /api/analysis/zone-recommendation/share/reports} ·
 * {@code GET /api/reports/{rptpId}/zone-recommendation})
 *
 * <h2>🔴 저장 규칙은 서비스를 직접 불러 본다 — HTTP 로는 401·400 만 본다</h2>
 * test 프로파일은 운영 필터체인을 끄고 permitAll 로 교대하므로 {@code @AuthenticationPrincipal} 이 null 이다.
 * 컨트롤러는 그때 서비스보다 앞에서 401 로 끊으므로 HTTP 로는 저장까지 갈 수 없다(이 레포엔 MockMvc 가 0건이라
 * {@code @WithMockUser} 도 없다 — {@code PolicySimIntegrationTest} 와 같은 방식이다). 그래서
 * <ul>
 *   <li>저장·생성자 기록·스냅샷·0건 400 은 {@link ZoneRcmdtnReportService} 를 <b>직접</b> 부르고,</li>
 *   <li>HTTP 는 "유효한 바디 = 401" · "잘못된 {@code fileFmt} = 400({@code @Valid} 가 401 판정보다 먼저)" 과
 *       그때 세 표가 그대로인 것만 본다.</li>
 * </ul>
 *
 * <h2>🔴 날짜·읍면동코드를 박지 않는다</h2>
 * 기간은 DB 의 민원·단속 보유 구간에서, 읍면동은 조건으로 고른다({@link ZoneRcmdtnTestData}).
 * "후보가 없는 읍면동" 은 행정동 마스터에서 후보 집계에 없는 동을, "민원이 없는 기간" 은 민원 보유 구간
 * <b>직전</b> 31일을 쓴다. 그런 데이터가 없으면 {@code Assumptions} 로 사유와 함께 건너뛴다.
 *
 * <h2>🔴 {@code RANDOM_PORT} 라 롤백이 안 먹는다 — 실제로 커밋하고 손으로 되돌린다</h2>
 * {@link #setUp()} 이 세 표의 {@code max(id)} 를 기준선으로 잡고 {@link #cleanUp()} 이 <b>기준선보다 큰 행 중
 * 이 테스트의 생성자({@value #TESTER})가 만든 것만</b> 보고서 → 결과 → 실행 순(FK 방향)으로 지운다. 기준선
 * 초과만으로 지우면 테스트가 도는 동안 같은 DB 에 붙은 로컬 서버가 만든 행까지 날아가므로 생성자 조건을 덧댔다.
 * 분석변수를 고치는 케이스(#7)는 고치기 <b>전에</b> 원본을 읽어 두고 그 값으로 되돌린다(단언이 실패해도 돈다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("구역추천 보고서 저장 · 스냅샷 조회 · 생성자 기록 · 401")
class ZoneRcmdtnReportIntegrationTest {

    private static final String FEE_URL = "/api/analysis/zone-recommendation/reports";
    private static final String SHARE_URL = "/api/analysis/zone-recommendation/share/reports";

    private static final String RECOMMEND = "RECOMMEND";

    /** 이 테스트가 넘기는 생성자 — 정리 범위의 표지이기도 하다 */
    private static final String TESTER = "tester01";
    private static final String TESTER_NM = "테스터";

    /** 분석변수 — 공유주차제 적용 계수({@code share.crtrVls.cvlcptMmCrtrVl}) 한 칸 */
    private static final String VRBL_CVLCPT_MM = "CVLCPT_MM_CRTR_VL";

    /**
     * 분석변수 — 요금제 적용 계수({@code fee.crtrVls.feeCvlcptIncrsRtUplmtVl}) 한 칸.
     * 요금제는 읍면동 판정 제거로 {@code CVLCPT_MM_CRTR_VL} 을 더 읽지 않는다.
     */
    private static final String VRBL_FEE_UPLMT = "FEE_CVLCPT_INCRS_RT_UPLMT_VL";

    @Autowired
    private ZoneRcmdtnReportService reportService;

    @Autowired
    private ZoneRcmdtnService zoneRcmdtnService;

    @Autowired
    private ZoneRcmdtnShareService zoneRcmdtnShareService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 🔴 스프링 빈이다 — 서비스가 저장·복원에 쓰는 바로 그 매퍼로 직렬화해 비교한다 */
    @Autowired
    private ObjectMapper objectMapper;

    private ZoneRcmdtnTestData data;

    /** 민원 창 · 단속 창 — 보유 구간에서 유도한다 */
    private Win cv;
    private Win crdn;

    private BigDecimal minCpcty;

    /** 기준선 — 이 테스트가 시작할 때의 세 표 max(id) */
    private long baseRptpId;
    private long baseRsltId;
    private long baseExcnId;

    /** 분석변수를 고치기 직전에 읽어 둔 RECOMMEND 설정 원본. null 이면 고치지 않았다 */
    private List<Map<String, Object>> stngBackup;

    @BeforeEach
    void setUp() {
        baseRptpId = maxId("parking.tb_rptp", "rptp_id");
        baseRsltId = maxId("parking.tb_anls_rslt", "rslt_id");
        baseExcnId = maxId("parking.tb_anls_excn", "excn_id");

        data = new ZoneRcmdtnTestData(jdbcTemplate);
        Win cvRange = data.cvRange();
        Win crdnRange = data.crdnRange();
        assertThat(cvRange).as("주차 민원(parking.tb_cvlcpt · 유형 99 제외 · emd_cd 있음)이 비어 있다").isNotNull();
        assertThat(crdnRange).as("단속(parking.tb_crdn_dsctn · emd_cd 있음)이 비어 있다").isNotNull();
        cv = ZoneRcmdtnTestData.head(cvRange);
        crdn = ZoneRcmdtnTestData.head(crdnRange);
        minCpcty = data.appliedVl(RECOMMEND, "SWTC_TRGT_MIN_CPCTY");
        assertThat(minCpcty).as("RECOMMEND 설정 행(SWTC_TRGT_MIN_CPCTY)이 없다 — 마이그레이션 84 를 확인하라").isNotNull();
    }

    /**
     * 이 테스트가 남긴 것만 되돌린다. 한 문장이 터져도 나머지를 마저 실행하고 실패는 끝에서 한 번에 드러낸다.
     */
    @AfterEach
    void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();

        // ① 분석변수 — 고쳤을 때만, 읽어 둔 원래 값으로
        if (stngBackup != null) {
            for (Map<String, Object> row : stngBackup) {
                runQuietly(failures, "UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?, use_yn = ?, mdfr_id = ?,"
                                + " mdfcn_dt = ? WHERE anls_type_cd = ? AND vrbl_cd = ?",
                        row.get("stng_vl"), row.get("use_yn"), row.get("mdfr_id"), row.get("mdfcn_dt"),
                        RECOMMEND, row.get("vrbl_cd"));
            }
            stngBackup = null;
        }

        // ② 세 표 — 기준선 초과 + 이 테스트의 생성자, FK 방향(보고서 → 결과 → 실행)
        runQuietly(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id > ? AND creatr_id = ?", baseRptpId, TESTER);
        runQuietly(failures, "DELETE FROM parking.tb_anls_rslt WHERE rslt_id > ? AND excn_id IN"
                + " (SELECT excn_id FROM parking.tb_anls_excn WHERE excn_id > ? AND excutr_id = ?)",
                baseRsltId, baseExcnId, TESTER);
        runQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id > ? AND excutr_id = ?",
                baseExcnId, TESTER);

        assertThat(failures).as("테스트가 만든 행·설정 복원에 실패했다 — 손으로 되돌려야 한다").isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_rptp WHERE rptp_id > ?"
                + " AND creatr_id = ?", Long.class, baseRptpId, TESTER)).as("남은 테스트 보고서").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_anls_excn WHERE excn_id > ?"
                + " AND excutr_id = ?", Long.class, baseExcnId, TESTER)).as("남은 테스트 실행").isZero();
    }

    private void runQuietly(List<RuntimeException> failures, String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    // ───────────────────────── #1 · #3 · #5(FEE) — 요금제 저장 ─────────────────────────

    @Test
    @DisplayName("#1·#3·#5 요금제 생성 — 세 표에 정확히 1행씩 · 생성자는 넘긴 값(system·관리자 아님) · rcmdtnTypeCd=FEE")
    void feeCreateInsertsOneRowEachAndRecordsCreator() {
        String emd = feeEmd();

        ZoneRcmdtnReportVO vo = reportService.createFeeReport(feeRequest(emd, "PDF"), TESTER, TESTER_NM);

        // #1 — 세 표에 정확히 1행씩
        assertThat(rowsAfterBase()).as("[tb_anls_excn, tb_anls_rslt, tb_rptp] 증가분").containsExactly(1, 1, 1);

        assertThat(vo.getRptpId()).isGreaterThan(baseRptpId);
        assertThat(vo.getExcnId()).isGreaterThan(baseExcnId);
        assertThat(vo.getRcmdtnTypeCd()).isEqualTo("FEE");
        assertThat(vo.getFee()).isNotNull();
        assertThat(vo.getShare()).as("다른 보기 본문은 null").isNull();
        assertThat(vo.getFee().getLots()).isNotEmpty();
        assertThat(vo.getFee().getExcnId()).as("실행 ID 는 머리 한 곳이다 — 본문은 null").isNull();
        assertThat(vo.getFileFmt()).isEqualTo("PDF");
        assertThat(vo.getBgngYmd()).isEqualTo(cv.bgngYmd());
        assertThat(vo.getEndYmd()).isEqualTo(cv.endYmd());
        assertThat(vo.getCrtYmd()).isNotNull();

        Map<String, Object> admdst = data.admdst(emd);
        String regionNm = admdst.get("sgg_nm") + " " + admdst.get("emd_nm");
        assertThat(vo.getRegionNm()).isEqualTo(regionNm);
        assertThat(vo.getRptpNm()).isEqualTo("구역추천 요금제 적용 보고서 " + regionNm);
        assertThat(vo.getAnlsPrdExpln()).isNotBlank();

        Map<String, Object> excn = excnRow(vo.getExcnId());
        assertThat(excn.get("anls_type_cd")).isEqualTo(RECOMMEND);
        assertThat(excn.get("excn_stts_cd")).isEqualTo("DONE");
        assertThat(excn.get("emd_cd")).isEqualTo(emd);
        assertThat(excn.get("sgg_nm")).isEqualTo(admdst.get("sgg_nm"));
        assertThat(excn.get("anls_bgng_ymd")).isEqualTo(cv.bgngYmd());
        assertThat(excn.get("anls_end_ymd")).isEqualTo(cv.endYmd());
        // #3 — 실행자
        assertThat(excn.get("excutr_id")).isEqualTo(TESTER).isNotEqualTo("system");
        // #5 — 판별 키
        assertThat(excn.get("rcmdtn_type_cd")).isEqualTo("FEE");

        JsonNode rslt = parse(jdbcTemplate.queryForObject(
                "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class, vo.getExcnId()));
        assertThat(fieldNames(rslt)).as("결과 JSON 9키 (scope·sggNm · anlsMthd · wbk 추가)")
                .containsExactlyInAnyOrder("crtrVls", "warnings", "zone", "asgn", "lots", "scope", "sggNm",
                        "anlsMthd", "wbk");
        assertThat(rslt.path("anlsMthd").asText()).as("anlsMthd").isEqualTo("RVW_SCR");
        assertThat(rslt.path("lots").size()).isEqualTo(vo.getFee().getLots().size());

        Map<String, Object> rptp = rptpRow(vo.getRptpId());
        assertThat(rptp.get("crt_type")).isEqualTo("MANUAL");
        assertThat(rptp.get("stts_cd")).isEqualTo("DONE");
        assertThat(rptp.get("file_fmt")).isEqualTo("PDF");
        assertThat(rptp.get("file_path")).as("파일을 서버에 굽지 않는다").isNull();
        assertThat(rptp.get("stng_id")).as("자동 생성 전용 칸").isNull();
        assertThat(((Number) rptp.get("excn_id")).longValue()).isEqualTo(vo.getExcnId());
        assertThat(rptp.get("rptp_nm")).isEqualTo(vo.getRptpNm());
        assertThat(rptp.get("anls_prd_expln")).isEqualTo(vo.getAnlsPrdExpln());
        // #3 — 생성자
        assertThat(rptp.get("creatr_id")).isEqualTo(TESTER).isNotEqualTo("system");
        assertThat(rptp.get("creatr_nm")).isEqualTo(TESTER_NM).isNotEqualTo("관리자");
    }

    // ───────────────────────── #2 · #3 · #5(SHARE) — 공유주차제 저장 ─────────────────────────

    @Test
    @DisplayName("#2·#3·#5 공유주차제 생성 — 세 표에 정확히 1행씩 · 생성자는 넘긴 값 · rcmdtnTypeCd=SHARE · zones 는 자르지 않는다")
    void shareCreateInsertsOneRowEachAndRecordsCreator() {
        assumeShareHasCandidate();

        // 공백뿐인 시군구 필터는 "필터 없음" 이다 — '' 가 들어가면 스냅샷 머리 지역명이 ' 전체' 가 된다
        ZoneRcmdtnReportVO vo = reportService.createShareReport(shareRequest(cv, "  ", "DOCX"), TESTER, TESTER_NM);

        // #2 — 세 표에 정확히 1행씩
        assertThat(rowsAfterBase()).as("[tb_anls_excn, tb_anls_rslt, tb_rptp] 증가분").containsExactly(1, 1, 1);

        assertThat(vo.getRcmdtnTypeCd()).isEqualTo("SHARE");
        assertThat(vo.getShare()).isNotNull();
        assertThat(vo.getFee()).as("다른 보기 본문은 null").isNull();
        assertThat(vo.getShare().getExcnId()).as("실행 ID 는 머리 한 곳이다 — 본문은 null").isNull();
        assertThat(vo.getFileFmt()).isEqualTo("DOCX");
        assertThat(vo.getBgngYmd()).isEqualTo(cv.bgngYmd());
        assertThat(vo.getEndYmd()).isEqualTo(cv.endYmd());
        assertThat(vo.getRegionNm()).isEqualTo("전체 지역");
        assertThat(vo.getRptpNm()).isEqualTo("구역추천 공유주차제 적용 보고서 전체 지역");
        // 🔴 후보만이 아니라 필터에 걸린 읍면동 전부다
        assertThat(vo.getShare().getZones()).hasSize(data.admdstCount());
        assertThat(vo.getShare().getZones()).anyMatch(z -> "Y".equals(z.getRvwTrgtYn()));

        Map<String, Object> excn = excnRow(vo.getExcnId());
        assertThat(excn.get("anls_type_cd")).isEqualTo(RECOMMEND);
        assertThat(excn.get("excn_stts_cd")).isEqualTo("DONE");
        assertThat(excn.get("emd_cd")).as("읍면동 조건이 없는 보기").isNull();
        assertThat(excn.get("sgg_nm")).as("공백뿐인 필터는 NULL 로 저장한다").isNull();
        assertThat(excn.get("anls_bgng_ymd")).isEqualTo(cv.bgngYmd());
        assertThat(excn.get("anls_end_ymd")).isEqualTo(cv.endYmd());
        assertThat(excn.get("excutr_id")).isEqualTo(TESTER).isNotEqualTo("system");
        assertThat(excn.get("rcmdtn_type_cd")).isEqualTo("SHARE");

        JsonNode rslt = parse(jdbcTemplate.queryForObject(
                "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class, vo.getExcnId()));
        assertThat(fieldNames(rslt)).as("결과 JSON 7키 (wbk 추가)").containsExactlyInAnyOrder(
                "crtrVls", "dataBgngYmd", "dataEndYmd", "warnings", "smry", "zones", "wbk");
        assertThat(rslt.path("zones").size()).as("zones 를 후보만으로 자르지 않는다").isEqualTo(data.admdstCount());

        Map<String, Object> rptp = rptpRow(vo.getRptpId());
        assertThat(rptp.get("crt_type")).isEqualTo("MANUAL");
        assertThat(rptp.get("stts_cd")).isEqualTo("DONE");
        assertThat(rptp.get("file_fmt")).isEqualTo("DOCX");
        assertThat(rptp.get("file_path")).isNull();
        assertThat(rptp.get("rptp_nm")).isEqualTo(vo.getRptpNm());
        assertThat(rptp.get("creatr_id")).isEqualTo(TESTER).isNotEqualTo("system");
        assertThat(rptp.get("creatr_nm")).isEqualTo(TESTER_NM).isNotEqualTo("관리자");
    }

    // ───────────────────────── #4 — 생성자명 50자 ─────────────────────────

    @Test
    @DisplayName("#4 creatr_nm 이 51자 이상이면 50자로 잘려 저장된다 (500 이 아니다) — 두 보기 모두")
    void longCreatorNameIsCutTo50() {
        String longNm = "가나다라마바사아자차".repeat(6);      // 60자
        assertThat(longNm).hasSize(60);
        String expected = longNm.substring(0, 50);

        ZoneRcmdtnReportVO fee = reportService.createFeeReport(feeRequest(feeEmd(), "PDF"), TESTER, longNm);
        assertThat(rptpRow(fee.getRptpId()).get("creatr_nm")).as("요금제").isEqualTo(expected);

        assumeShareHasCandidate();
        ZoneRcmdtnReportVO share = reportService.createShareReport(shareRequest(cv, null, "PDF"), TESTER, longNm);
        assertThat(rptpRow(share.getRptpId()).get("creatr_nm")).as("공유주차제").isEqualTo(expected);
    }

    // ───────────────────────── #6 — 스냅샷 == 생성 응답 ─────────────────────────

    @Test
    @DisplayName("#6 요금제 — 스냅샷 조회는 생성 응답과 직렬화한 JSON 이 같다 (crtYmd 한 칸만 따로 비교)")
    void feeSnapshotEqualsCreateResponse() {
        ZoneRcmdtnReportVO created = reportService.createFeeReport(feeRequest(feeEmd(), "PDF"), TESTER, TESTER_NM);
        ZoneRcmdtnReportVO snapshot = reportService.selectReportSnapshot(created.getRptpId());

        assertThat(snapshot.getRcmdtnTypeCd()).isEqualTo("FEE");
        assertThat(snapshot.getShare()).isNull();
        assertThat(snapshot.getFee().getLots()).hasSameSizeAs(created.getFee().getLots());
        assertSameResponse(created, snapshot, "fee");
    }

    @Test
    @DisplayName("#6 공유주차제 — 스냅샷 조회는 생성 응답과 직렬화한 JSON 이 같다 (crtYmd 한 칸만 따로 비교)")
    void shareSnapshotEqualsCreateResponse() {
        assumeShareHasCandidate();
        ZoneRcmdtnReportVO created = reportService.createShareReport(shareRequest(cv, null, "PDF"), TESTER, TESTER_NM);
        ZoneRcmdtnReportVO snapshot = reportService.selectReportSnapshot(created.getRptpId());

        assertThat(snapshot.getRcmdtnTypeCd()).isEqualTo("SHARE");
        assertThat(snapshot.getFee()).isNull();
        assertThat(snapshot.getShare().getZones()).hasSameSizeAs(created.getShare().getZones());
        assertSameResponse(created, snapshot, "share");
    }

    /**
     * 두 응답을 <b>직렬화한 JSON 문자열끼리</b> 비교한다(객체 비교는 {@code crtrVls} 의 {@code @JsonIgnore} 2칸이
     * 되읽으면 null 이라 갈린다. 트리 비교는 {@code DecimalNode} 가 {@code compareTo} 로 같다고 해서 자릿수 차이를
     * 가린다 — 그래서 문자열이다).
     *
     * <h3>🔴 {@code crtYmd} 한 칸만 따로 다룬다</h3>
     * 생성 응답은 {@code LocalDateTime.now()} 원값(나노초까지 올 수 있다), 조회는 DB 를 다녀온 값
     * ({@code timestamp} = 마이크로초)이라 소수초 자릿수가 다를 수 있다. 그래서
     * <ol>
     *   <li>두 값의 차이가 <b>1마이크로초 이하</b>임을 따로 단언하고(DB 가 반올림·절삭 어느 쪽을 해도 통과),</li>
     *   <li>각 JSON 에서 <b>최상위 {@code "crtYmd":<그 값>} 토큰이 정확히 한 번</b> 나오는지 확인한 뒤 그 토큰만
     *       같은 자리표시로 바꿔 전체 문자열을 비교한다.</li>
     * </ol>
     * 바꾸는 것은 그 한 토큰뿐이라 다른 칸의 차이(숫자 표기·null·키 순서 포함)는 그대로 드러난다.
     */
    private void assertSameResponse(ZoneRcmdtnReportVO created, ZoneRcmdtnReportVO snapshot, String bodyKey) {
        // 머리 — 문서의 제목·지역·기간 표기가 내려받은 파일과 재다운로드 문서에서 같아야 한다
        assertThat(snapshot.getRptpNm()).isEqualTo(created.getRptpNm()).isNotBlank();
        assertThat(snapshot.getRegionNm()).isEqualTo(created.getRegionNm()).isNotBlank();
        assertThat(snapshot.getAnlsPrdExpln()).isEqualTo(created.getAnlsPrdExpln()).isNotBlank();

        // crtYmd — 값으로 비교(마이크로초 한 눈금 이내)
        assertThat(created.getCrtYmd()).isNotNull();
        assertThat(snapshot.getCrtYmd()).isNotNull();
        assertThat(Duration.between(created.getCrtYmd(), snapshot.getCrtYmd()).abs())
                .as("crtYmd: 생성 응답 %s ↔ 스냅샷 %s", created.getCrtYmd(), snapshot.getCrtYmd())
                .isLessThanOrEqualTo(Duration.ofNanos(1_000));

        // 🔴 vrblAjmt 는 anls_param_json(jsonb)에서 Map 으로 되읽혀 키 순서가 jsonb 정렬을 따른다.
        //    그 칸만 값(트리)으로 비교하고 문자열 비교에서는 뺀다(다른 칸의 표기 차이는 그대로 드러난다).
        assertThat(parse(write(snapshot.getVrblAjmt()))).as("vrblAjmt (값 비교)")
                .isEqualTo(parse(write(created.getVrblAjmt())));
        assertThat(created.getVrblAjmt()).as("생성 응답은 적용 내역을 싣는다").isNotNull();
        snapshot.setVrblAjmt(null);
        created.setVrblAjmt(null);

        String createdJson = write(created);
        String snapshotJson = write(snapshot);

        // 본문만 따로도 본다 — 실패 메시지가 어느 쪽 차이인지 바로 보이게
        assertThat(parse(snapshotJson).path(bodyKey).toString()).as("본문 %s 가 있다", bodyKey).isNotEqualTo("");
        assertThat(write(bodyOf(snapshot, bodyKey))).as("본문 %s (직렬화 JSON)", bodyKey)
                .isEqualTo(write(bodyOf(created, bodyKey)));

        assertThat(maskCrtYmd(snapshotJson)).as("응답 전체 (직렬화 JSON · crtYmd 토큰만 가림)")
                .isEqualTo(maskCrtYmd(createdJson));
    }

    private static Object bodyOf(ZoneRcmdtnReportVO vo, String bodyKey) {
        return "fee".equals(bodyKey) ? vo.getFee() : vo.getShare();
    }

    /** 최상위 {@code "crtYmd":<값>} 토큰 하나만 자리표시로 바꾼다. 토큰이 정확히 한 번 나오지 않으면 실패다 */
    private String maskCrtYmd(String json) {
        JsonNode crtYmd = parse(json).get("crtYmd");
        assertThat(crtYmd).as("crtYmd 키가 응답에 있다").isNotNull();
        String token = "\"crtYmd\":" + crtYmd.toString();
        int first = json.indexOf(token);
        assertThat(first).as("crtYmd 토큰 %s 이 JSON 에 있다", token).isNotNegative();
        assertThat(json.indexOf(token, first + 1)).as("crtYmd 토큰은 한 번만 나온다").isNegative();
        return json.substring(0, first) + "\"crtYmd\":\"<crtYmd>\"" + json.substring(first + token.length());
    }

    // ───────────────────────── #7 — 분석변수를 바꿔도 스냅샷은 그대로 ─────────────────────────

    @Test
    @DisplayName("#7 분석변수(RECOMMEND)를 바꾼 뒤에도 두 보기의 스냅샷은 저장 시점 그대로다 (조회 계산은 새 값을 쓴다)")
    void snapshotSurvivesVariableChange() {
        String emd = feeEmd();
        assumeShareHasCandidate();
        ZoneRcmdtnReportVO fee = reportService.createFeeReport(feeRequest(emd, "PDF"), TESTER, TESTER_NM);
        ZoneRcmdtnReportVO share = reportService.createShareReport(shareRequest(cv, null, "PDF"), TESTER, TESTER_NM);
        String feeBefore = write(reportService.selectReportSnapshot(fee.getRptpId()));
        String shareBefore = write(reportService.selectReportSnapshot(share.getRptpId()));
        BigDecimal feeCrtrBefore = fee.getFee().getCrtrVls().getFeeCvlcptIncrsRtUplmtVl();
        BigDecimal shareCrtrBefore = share.getShare().getCrtrVls().getCvlcptMmCrtrVl();

        // 허용 범위 안에서 한 눈금 옮긴다(+1 이 상한을 넘으면 −1)
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT stng_vl, bsc_stng_vl, use_yn, min_vl, max_vl"
                + " FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND vrbl_cd = ?", RECOMMEND, VRBL_CVLCPT_MM);
        BigDecimal applied = data.appliedVl(RECOMMEND, VRBL_CVLCPT_MM);
        BigDecimal changed = applied.add(BigDecimal.ONE).compareTo((BigDecimal) row.get("max_vl")) <= 0
                ? applied.add(BigDecimal.ONE) : applied.subtract(BigDecimal.ONE);
        assumeTrue(changed.compareTo((BigDecimal) row.get("min_vl")) >= 0
                        && changed.compareTo((BigDecimal) row.get("max_vl")) <= 0,
                "RECOMMEND." + VRBL_CVLCPT_MM + " 을 ±1 옮길 여유가 없다(min=" + row.get("min_vl")
                        + ", max=" + row.get("max_vl") + ", 적용값=" + applied + ")");
        Map<String, Object> feeRow = jdbcTemplate.queryForMap("SELECT min_vl, max_vl"
                + " FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ? AND vrbl_cd = ?", RECOMMEND, VRBL_FEE_UPLMT);
        BigDecimal feeApplied = data.appliedVl(RECOMMEND, VRBL_FEE_UPLMT);
        BigDecimal feeChanged = feeApplied.add(BigDecimal.ONE).compareTo((BigDecimal) feeRow.get("max_vl")) <= 0
                ? feeApplied.add(BigDecimal.ONE) : feeApplied.subtract(BigDecimal.ONE);
        assumeTrue(feeChanged.compareTo((BigDecimal) feeRow.get("min_vl")) >= 0,
                "RECOMMEND." + VRBL_FEE_UPLMT + " 을 ±1 옮길 여유가 없다");

        // 🔴 고치기 전에 원본을 읽어 둔다 — 이 줄부터는 cleanUp() 이 반드시 되돌린다(단언이 실패해도)
        stngBackup = jdbcTemplate.queryForList("SELECT vrbl_cd, stng_vl, use_yn, mdfr_id, mdfcn_dt"
                + " FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = ?", RECOMMEND);
        assertThat(jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?, use_yn = 'Y'"
                + " WHERE anls_type_cd = ? AND vrbl_cd = ?", changed, RECOMMEND, VRBL_CVLCPT_MM)).isEqualTo(1);
        assertThat(jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?, use_yn = 'Y'"
                + " WHERE anls_type_cd = ? AND vrbl_cd = ?", feeChanged, RECOMMEND, VRBL_FEE_UPLMT)).isEqualTo(1);

        // 변경이 실제로 계산에 먹었다 — 같은 조건의 조회는 이제 다른 계수를 돌려준다
        assertThat(zoneRcmdtnService.selectZoneRcmdtn(emd, cv.bgngYmd(), cv.endYmd(), crdn.bgngYmd(), crdn.endYmd(),
                null).getCrtrVls().getFeeCvlcptIncrsRtUplmtVl()).as("요금제 조회는 바뀐 계수를 쓴다")
                .isEqualByComparingTo(feeChanged).isNotEqualByComparingTo(feeCrtrBefore);
        assertThat(zoneRcmdtnShareService.selectShare(cv.bgngYmd(), cv.endYmd(), null, null)
                .getCrtrVls().getCvlcptMmCrtrVl()).as("공유주차제 조회는 바뀐 계수를 쓴다")
                .isEqualByComparingTo(changed).isNotEqualByComparingTo(shareCrtrBefore);

        // 그래도 스냅샷은 저장 시점 그대로다
        ZoneRcmdtnReportVO feeAfter = reportService.selectReportSnapshot(fee.getRptpId());
        ZoneRcmdtnReportVO shareAfter = reportService.selectReportSnapshot(share.getRptpId());
        assertThat(write(feeAfter)).as("요금제 스냅샷 전체").isEqualTo(feeBefore);
        assertThat(write(shareAfter)).as("공유주차제 스냅샷 전체").isEqualTo(shareBefore);
        assertThat(feeAfter.getFee().getCrtrVls().getFeeCvlcptIncrsRtUplmtVl()).isEqualByComparingTo(feeCrtrBefore);
        assertThat(shareAfter.getShare().getCrtrVls().getCvlcptMmCrtrVl()).isEqualByComparingTo(shareCrtrBefore);

        // 되돌린 뒤 원래 값인지 여기서도 본다(실패하면 cleanUp 이 한 번 더 시도한다)
        restoreStng();
        assertThat(data.appliedVl(RECOMMEND, VRBL_CVLCPT_MM)).as("복원된 적용값").isEqualByComparingTo(applied);
        assertThat(data.appliedVl(RECOMMEND, VRBL_FEE_UPLMT)).as("복원된 적용값(요금제)").isEqualByComparingTo(feeApplied);
        assertThat(jdbcTemplate.queryForMap("SELECT stng_vl, use_yn FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = ? AND vrbl_cd = ?", RECOMMEND, VRBL_CVLCPT_MM))
                .containsEntry("stng_vl", row.get("stng_vl")).containsEntry("use_yn", row.get("use_yn"));
    }

    /** 읽어 둔 원본으로 되돌린다. 성공해야 백업을 비운다 — 실패하면 {@link #cleanUp()} 이 한 번 더 시도한다 */
    private void restoreStng() {
        for (Map<String, Object> row : stngBackup) {
            jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?, use_yn = ?, mdfr_id = ?,"
                            + " mdfcn_dt = ? WHERE anls_type_cd = ? AND vrbl_cd = ?",
                    row.get("stng_vl"), row.get("use_yn"), row.get("mdfr_id"), row.get("mdfcn_dt"),
                    RECOMMEND, row.get("vrbl_cd"));
        }
        stngBackup = null;
    }

    // ───────────────────────── #8 — 스냅샷 404 4종 ─────────────────────────

    @Test
    @DisplayName("#8 스냅샷 404 4종 — 없는 ID · 다른 유형(INVEST) · rcmdtnTypeCd 를 알 수 없는 RECOMMEND · 결과 행 없음")
    void snapshotNotFoundFourWays() {
        // 대조군 — 정상 보고서는 열린다(아래 404 가 '무엇을 줘도 404' 가 아님을 보인다)
        ZoneRcmdtnReportVO ok = reportService.createFeeReport(feeRequest(feeEmd(), "PDF"), TESTER, TESTER_NM);
        assertThat(reportService.selectReportSnapshot(ok.getRptpId()).getRptpId()).isEqualTo(ok.getRptpId());

        // ① 없는 ID
        long missing = maxId("parking.tb_rptp", "rptp_id") + 1_000_000L;
        assertNotFound(() -> reportService.selectReportSnapshot(missing), "보고서를 찾을 수 없습니다");

        // ② 다른 유형 — INVEST 실행에 붙은 보고서(결과 행도 있다 — 유형만으로 걸러져야 한다)
        long invest = insertReport("INVEST", "{\"rcmdtnTypeCd\":\"FEE\"}", "{}");
        assertNotFound(() -> reportService.selectReportSnapshot(invest), "구역추천 보고서가 아닙니다");

        // ③ 보기를 알 수 없다 — 키 없음 · 파라미터 자체가 NULL · 모르는 값. 결과 행은 있다(추측해 읽지 않는다)
        long noKey = insertReport(RECOMMEND, "{\"emdCd\":null}", "{\"lots\":[],\"zones\":[]}");
        long nullParam = insertReport(RECOMMEND, null, "{\"lots\":[],\"zones\":[]}");
        long unknown = insertReport(RECOMMEND, "{\"rcmdtnTypeCd\":\"ETC\"}", "{\"lots\":[],\"zones\":[]}");
        assertNotFound(() -> reportService.selectReportSnapshot(noKey), "보기를 알 수 없습니다");
        assertNotFound(() -> reportService.selectReportSnapshot(nullParam), "보기를 알 수 없습니다");
        assertNotFound(() -> reportService.selectReportSnapshot(unknown), "보기를 알 수 없습니다");

        // ④ 유형·보기는 맞는데 결과 행이 없다 — 빈 문서를 200 으로 내지 않는다
        long feeNoRslt = insertReport(RECOMMEND, "{\"rcmdtnTypeCd\":\"FEE\"}", null);
        long shareNoRslt = insertReport(RECOMMEND, "{\"rcmdtnTypeCd\":\"SHARE\"}", null);
        assertNotFound(() -> reportService.selectReportSnapshot(feeNoRslt), "저장된 분석 결과가 없습니다");
        assertNotFound(() -> reportService.selectReportSnapshot(shareNoRslt), "저장된 분석 결과가 없습니다");

        // HTTP 스냅샷은 이제 **소유자 가드**를 먼저 거친다. 종전 주석
        //    "GET 은 생성자 해석이 없어 그대로 닿는다" 는 폐기됐다 — 익명 GET 은 404 가 아니라 **401** 이다.
        //    (어느 쪽이든 본문은 새지 않지만, 상태코드가 섞이면 '보고서가 없다' 와 '로그인하지 않았다' 가
        //     구분되지 않아 프론트가 로그인으로 튕길지 안내만 할지 가를 수 없다.)
        for (long id : new long[] {missing, invest, noKey, feeNoRslt, ok.getRptpId()}) {
            assertThat(restTemplate.getForEntity("/api/reports/{id}/zone-recommendation", String.class, id)
                    .getStatusCode()).as("익명 GET rptpId=%d", id).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * 실행(+결과)+보고서 최소 행을 직접 만든다 — 생성자·실행자는 {@value #TESTER} 라 {@link #cleanUp()} 이 지운다.
     *
     * @param paramJson {@code anls_param_json}. null 이면 칸을 비운다
     * @param rsltJson  {@code rslt_json}. null 이면 결과 행을 만들지 않는다
     * @return 보고서아이디
     */
    private long insertReport(String anlsTypeCd, String paramJson, String rsltJson) {
        Long excnId = jdbcTemplate.queryForObject("INSERT INTO parking.tb_anls_excn"
                + " (anls_type_cd, excutr_id, anls_bgng_ymd, anls_end_ymd, anls_param_json, excn_stts_cd, excn_dt)"
                + " VALUES (?, ?, ?, ?, ?::jsonb, 'DONE', now()) RETURNING excn_id",
                Long.class, anlsTypeCd, TESTER, cv.bgngYmd(), cv.endYmd(), paramJson);
        if (rsltJson != null) {
            jdbcTemplate.update("INSERT INTO parking.tb_anls_rslt (excn_id, rslt_json) VALUES (?, ?::jsonb)",
                    excnId, rsltJson);
        }
        return jdbcTemplate.queryForObject("INSERT INTO parking.tb_rptp"
                + " (crt_type, excn_id, rptp_nm, stts_cd, file_fmt, creatr_id, creatr_nm, crt_ymd)"
                + " VALUES ('MANUAL', ?, ?, 'DONE', 'PDF', ?, ?, now()) RETURNING rptp_id",
                Long.class, excnId, "TESTDATA 테스트 보고서(" + anlsTypeCd + ")", TESTER, TESTER_NM);
    }

    // ───────────────────────── #9 · #10 — 0건이면 400 ─────────────────────────

    @Test
    @DisplayName("#9 요금제 — 후보 주차장이 0개소인 읍면동이면 400, 세 표 0행 증가")
    void feeWithNoLotsIs400AndInsertsNothing() {
        // 행정동 마스터에는 있지만 후보 집계(NARROW · 적용 면수 하한)에 없는 동
        List<String> noCand = new ArrayList<>(data.emdCds());
        noCand.removeAll(data.candByEmd(minCpcty, false).keySet());
        assumeTrue(!noCand.isEmpty(), "NARROW 후보 주차장이 0개소인 읍면동이 없다(전 읍면동에 후보가 있다)");
        String emd = noCand.get(0);
        // 조건이 실제로 성립한다 — 조회는 200 이고 lots 가 비어 있다(없는 읍면동의 404 와 다른 경로다)
        assertThat(zoneRcmdtnService.selectZoneRcmdtn(emd, cv.bgngYmd(), cv.endYmd(), crdn.bgngYmd(),
                crdn.endYmd(), null).getLots()).as("읍면동 %s 의 후보 주차장", emd).isEmpty();

        assertStatus(() -> reportService.createFeeReport(feeRequest(emd, "PDF"), TESTER, TESTER_NM),
                HttpStatus.BAD_REQUEST);

        assertThat(rowsAfterBase()).as("[tb_anls_excn, tb_anls_rslt, tb_rptp] 증가분").containsExactly(0, 0, 0);
    }

    @Test
    @DisplayName("#10 공유주차제 — 민원이 없는 기간이라 후보가 0곳이면 400, 세 표 0행 증가")
    void shareWithNoCandidateIs400AndInsertsNothing() {
        // 민원 보유 구간 '직전' 31일 — 날짜를 박지 않고 자료 시작일에서 거슬러 잡는다
        Win cvRange = data.cvRange();
        Win empty = new Win(cvRange.bgng().minusDays(31), cvRange.bgng().minusDays(1));
        assertThat(data.cvCountByEmd(empty)).as("자료 시작일 이전 %s ~ %s 의 민원", empty.bgng(), empty.end()).isEmpty();
        // 조건이 실제로 성립한다 — 조회는 200 이고 zones 는 전 읍면동인데 후보만 0곳이다
        ZoneRcmdtnShareResultVO query =
                zoneRcmdtnShareService.selectShare(empty.bgngYmd(), empty.endYmd(), null, null);
        assertThat(query.getZones()).as("후보가 없어도 zones 는 전 읍면동으로 온다").hasSize(data.admdstCount());
        assumeTrue(query.getZones().stream().noneMatch(z -> "Y".equals(z.getRvwTrgtYn())),
                "민원이 0건인 기간인데도 검토 후보가 나온다 — '후보 0곳' 조건을 만들 수 없다");

        assertStatus(() -> reportService.createShareReport(shareRequest(empty, null, "PDF"), TESTER, TESTER_NM),
                HttpStatus.BAD_REQUEST);

        assertThat(rowsAfterBase()).as("[tb_anls_excn, tb_anls_rslt, tb_rptp] 증가분").containsExactly(0, 0, 0);
    }

    // ───────────────────────── #11 — 잘못된 fileFmt (HTTP 만) ─────────────────────────

    @Test
    @DisplayName("#11 잘못된 fileFmt 는 인증 판정 전에 400 — 두 POST 모두, 세 표 0행 증가 (HTTP — @Valid 는 컨트롤러에서만 발동한다)")
    void invalidFileFmtIs400BeforeAuth() {
        String emd = feeEmd();

        for (String fileFmt : new String[] {"HWP", "pdf", "", null}) {
            assertThat(post(FEE_URL, feeBody(emd, fileFmt)).getStatusCode())
                    .as("요금제 fileFmt=%s", fileFmt).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(post(SHARE_URL, shareBody(fileFmt)).getStatusCode())
                    .as("공유주차제 fileFmt=%s", fileFmt).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        assertThat(rowsAfterBase()).as("[tb_anls_excn, tb_anls_rslt, tb_rptp] 증가분").containsExactly(0, 0, 0);
    }

    // ───────────────────────── #12 · #13 — 익명은 401 ─────────────────────────

    @Test
    @DisplayName("#12·#13 유효한 바디의 HTTP POST 2종은 401 이고(test 체인은 principal 이 null) 세 표 0행 증가")
    void anonymousPostIs401AndInsertsNothing() {
        String emd = feeEmd();
        assumeShareHasCandidate();
        // 🔴 바디는 유효하다 — 같은 조건을 서비스에 직접 넘기면 저장된다(= 401 의 원인이 바디가 아니다).
        //    이 확인이 만든 3행 × 2 는 기준선을 다시 잡아 아래 '0행 증가' 에서 뺀다.
        reportService.createFeeReport(feeRequest(emd, "PDF"), TESTER, TESTER_NM);
        reportService.createShareReport(shareRequest(cv, null, "PDF"), TESTER, TESTER_NM);
        int[] before = rowsAfterBase();
        assertThat(before).containsExactly(2, 2, 2);

        ResponseEntity<String> fee = post(FEE_URL, feeBody(emd, "PDF"));
        ResponseEntity<String> share = post(SHARE_URL, shareBody("PDF"));

        // #12
        assertThat(fee.getStatusCode()).as("요금제 POST → %s", fee.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(share.getStatusCode()).as("공유주차제 POST → %s", share.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // #13
        assertThat(rowsAfterBase()).as("401 이면 아무것도 저장하지 않는다").containsExactly(before);
    }

    // ───────────────────────── 시군구 보고서 · 옛 스냅샷 ─────────────────────────

    /** 시군구 모드 표본 — 행정구역 마스터의 시군구명 */
    private static final String SGG = "제주시";

    @Test
    @DisplayName("요금제 시군구 — 세 표 1행씩 · emd_cd NULL · rptpNm '… 제주시 전체' · 스냅샷 == 생성 응답 · 공백 emdCd 도 시군구")
    void feeReportBySggNm() {
        // 🔴 공백 emdCd + sggNm — 서비스는 시군구 모드인데 보고서 쪽이 요청 emdCd 로 모드를 가르면 zone NPE(500)다
        ZoneRcmdtnReportCreateRequest req = feeRequest("  ", "PDF");
        req.setSggNm(SGG);

        ZoneRcmdtnReportVO created = reportService.createFeeReport(req, TESTER, TESTER_NM);

        assertThat(rowsAfterBase()).as("[tb_anls_excn, tb_anls_rslt, tb_rptp] 증가분").containsExactly(1, 1, 1);
        assertThat(created.getRcmdtnTypeCd()).isEqualTo("FEE");
        assertThat(created.getFee().getScope()).isEqualTo("SGG");
        assertThat(created.getFee().getSggNm()).isEqualTo(SGG);
        assertThat(created.getFee().getZone()).as("시군구 판정은 없다").isNull();
        assertThat(created.getFee().getAsgn()).isNull();
        assertThat(created.getFee().getLots()).isNotEmpty();
        assertThat(created.getRegionNm()).isEqualTo(SGG + " 전체");
        assertThat(created.getRptpNm()).isEqualTo("구역추천 요금제 적용 보고서 " + SGG + " 전체");

        Map<String, Object> excn = excnRow(created.getExcnId());
        assertThat(excn.get("emd_cd")).as("🔴 시군구 모드는 emd_cd NULL").isNull();
        assertThat(excn.get("sgg_nm")).isEqualTo(SGG);
        assertThat(excn.get("excutr_id")).isEqualTo(TESTER);
        assertThat(excn.get("rcmdtn_type_cd")).isEqualTo("FEE");
        assertThat(jdbcTemplate.queryForObject("SELECT anls_param_json->>'scope' FROM parking.tb_anls_excn"
                + " WHERE excn_id = ?", String.class, created.getExcnId())).isEqualTo("SGG");

        JsonNode rslt = parse(jdbcTemplate.queryForObject(
                "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class,
                created.getExcnId()));
        assertThat(rslt.path("scope").asText()).isEqualTo("SGG");
        assertThat(rslt.path("sggNm").asText()).isEqualTo(SGG);
        assertThat(rslt.hasNonNull("zone")).isFalse();

        ZoneRcmdtnReportVO snapshot = reportService.selectReportSnapshot(created.getRptpId());
        assertThat(snapshot.getFee().getScope()).as("🔴 재다운로드도 시군구로 읽힌다").isEqualTo("SGG");
        assertThat(snapshot.getFee().getZone()).isNull();
        assertSameResponse(created, snapshot, "fee");
    }

    @Test
    @DisplayName("옛 스냅샷 — scope·sggNm 키가 없는 rslt_json 도 역직렬화된다 (scope=null · 본문은 그대로)")
    void legacySnapshotWithoutScopeStillReads() {
        // wbk 도 걷는다(안 빼면 구 스냅샷에 wbk 가 남는다).
        // 실제 읍면동 보고서의 결과 JSON 에서 키 2개만 걷어 '이전 스냅샷' 을 만든다 — 기존 행은 건드리지 않고,
        // 만든 행은 생성자 TESTER 라 cleanUp() 이 지운다(insertReport 와 같은 표지).
        ZoneRcmdtnReportVO current = reportService.createFeeReport(feeRequest(feeEmd(), "PDF"), TESTER, TESTER_NM);
        String legacyRslt = jdbcTemplate.queryForObject(
                "SELECT (rslt_json - 'scope' - 'sggNm' - 'anlsMthd' - 'wbk')::text FROM parking.tb_anls_rslt WHERE excn_id = ?",
                String.class, current.getExcnId());
        assertThat(fieldNames(parse(legacyRslt))).as("전제 — 구 5키")
                .containsExactlyInAnyOrder("crtrVls", "warnings", "zone", "asgn", "lots");
        long legacy = insertReport(RECOMMEND, "{\"rcmdtnTypeCd\":\"FEE\"}", legacyRslt);

        ZoneRcmdtnReportVO snapshot = reportService.selectReportSnapshot(legacy);

        assertThat(snapshot.getRcmdtnTypeCd()).isEqualTo("FEE");
        assertThat(snapshot.getFee().getScope()).as("키가 없으면 null — 프론트가 EMD 로 간주한다").isNull();
        assertThat(snapshot.getFee().getSggNm()).isNull();
        assertThat(snapshot.getFee().getAnlsMthd())
                .as("🔴 anlsMthd 키가 없는 구 스냅샷은 null 이다(VO 초기값 없음 → 구 방식으로 그린다)")
                .isNull();
        assertThat(snapshot.getFee().getZone()).as("읍면동 판정은 그대로 읽힌다").isNotNull();
        assertThat(snapshot.getFee().getZone().getEmdCd()).isEqualTo(current.getFee().getZone().getEmdCd());
        assertThat(write(snapshot.getFee().getLots())).as("순위 목록은 원본과 같다")
                .isEqualTo(write(current.getFee().getLots()));
    }

    @Test
    @DisplayName("⑥ 보고서 스냅샷 — rslt_json 에 exclLots 키가 없다 (읍면동·시군구)")
    void reportSnapshotOmitsExclLots() {
        ZoneRcmdtnReportCreateRequest sggReq = feeRequest(null, "PDF");
        sggReq.setSggNm(SGG);
        for (ZoneRcmdtnReportCreateRequest req : List.of(feeRequest(feeEmd(), "PDF"), sggReq)) {
            ZoneRcmdtnReportVO created = reportService.createFeeReport(req, TESTER, TESTER_NM);
            assertThat(created.getFee().getExclLots()).as("생성 응답도 exclLots 를 비운다(스냅샷과 같게)").isEmpty();
            JsonNode rslt = parse(jdbcTemplate.queryForObject(
                    "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class,
                    created.getExcnId()));
            assertThat(fieldNames(rslt)).as("🔴 %s — 지도 참고용 목록은 스냅샷에 남기지 않는다",
                    created.getRegionNm()).doesNotContain("exclLots");

            ZoneRcmdtnReportVO snapshot = reportService.selectReportSnapshot(created.getRptpId());
            assertThat(snapshot.getFee().getExclLots()).as("%s — 스냅샷 조회의 exclLots 는 빈 목록이다",
                    created.getRegionNm()).isEmpty();
        }
    }

    // ───────────────────────── 헬퍼 ─────────────────────────

    /** 요금제 대상 읍면동 — NARROW 후보가 있고 창 안에 좌표 보유 민원이 있는 동(조건으로 고른다) */
    private String feeEmd() {
        String emd = data.busyEmd(cv, minCpcty, false, 1);
        assumeTrue(emd != null, "NARROW 후보 주차장이 있으면서 민원이 있는 읍면동이 없다");
        return emd;
    }

    /** 공유주차제 정상 경로의 전제 — 민원 보유 구간에 검토 후보가 한 곳은 있어야 보고서가 만들어진다 */
    private void assumeShareHasCandidate() {
        ZoneRcmdtnShareResultVO query = zoneRcmdtnShareService.selectShare(cv.bgngYmd(), cv.endYmd(), null, null);
        assumeTrue(query.getZones().stream().anyMatch(z -> "Y".equals(z.getRvwTrgtYn())),
                "민원 보유 구간(" + cv.bgngYmd() + " ~ " + cv.endYmd() + ")에 공유주차 검토 후보가 0곳이다");
    }

    private ZoneRcmdtnReportCreateRequest feeRequest(String emdCd, String fileFmt) {
        ZoneRcmdtnReportCreateRequest req = new ZoneRcmdtnReportCreateRequest();
        req.setEmdCd(emdCd);
        req.setBgngYmd(cv.bgngYmd());
        req.setEndYmd(cv.endYmd());
        req.setCrdnBgngYmd(crdn.bgngYmd());
        req.setCrdnEndYmd(crdn.endYmd());
        req.setFileFmt(fileFmt);
        return req;
    }

    private static ZoneRcmdtnShareReportCreateRequest shareRequest(Win win, String sggNm, String fileFmt) {
        ZoneRcmdtnShareReportCreateRequest req = new ZoneRcmdtnShareReportCreateRequest();
        req.setBgngYmd(win.bgngYmd());
        req.setEndYmd(win.endYmd());
        req.setSggNm(sggNm);
        req.setFileFmt(fileFmt);
        return req;
    }

    /** HTTP 바디 — {@link #feeRequest} 와 같은 조건이다. null 인 fileFmt 는 싣지 않는다("생략") */
    private Map<String, Object> feeBody(String emdCd, String fileFmt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("emdCd", emdCd);
        body.put("bgngYmd", cv.bgngYmd());
        body.put("endYmd", cv.endYmd());
        body.put("crdnBgngYmd", crdn.bgngYmd());
        body.put("crdnEndYmd", crdn.endYmd());
        if (fileFmt != null) {
            body.put("fileFmt", fileFmt);
        }
        return body;
    }

    private Map<String, Object> shareBody(String fileFmt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", cv.bgngYmd());
        body.put("endYmd", cv.endYmd());
        if (fileFmt != null) {
            body.put("fileFmt", fileFmt);
        }
        return body;
    }

    private ResponseEntity<String> post(String url, Map<String, Object> body) {
        return restTemplate.postForEntity(url, body, String.class);
    }

    private static void assertStatus(ThrowingCallable call, HttpStatus expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).as("상태코드 (%s)", e.getReason())
                        .isEqualTo(expected.value()));
    }

    /** 404 이고 사유가 그 판정의 것이다 — 네 판정이 한 분기로 뭉개지지 않았음을 본다 */
    private static void assertNotFound(ThrowingCallable call, String reasonPart) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).as("상태코드 (%s)", e.getReason())
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(e.getReason()).contains(reasonPart);
        });
    }

    private Map<String, Object> excnRow(Long excnId) {
        return jdbcTemplate.queryForMap("SELECT anls_type_cd, excn_stts_cd, excutr_id, emd_cd, sgg_nm,"
                + " anls_bgng_ymd, anls_end_ymd, anls_param_json->>'rcmdtnTypeCd' AS rcmdtn_type_cd"
                + " FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
    }

    private Map<String, Object> rptpRow(Long rptpId) {
        return jdbcTemplate.queryForMap("SELECT crt_type, excn_id, stts_cd, file_fmt, file_path, rptp_nm,"
                + " anls_prd_expln, creatr_id, creatr_nm, stng_id FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
    }

    /** 기준선 뒤에 생긴 행 수 — {@code [tb_anls_excn, tb_anls_rslt, tb_rptp]} */
    private int[] rowsAfterBase() {
        return new int[] {
                countAfter("parking.tb_anls_excn", "excn_id", baseExcnId),
                countAfter("parking.tb_anls_rslt", "rslt_id", baseRsltId),
                countAfter("parking.tb_rptp", "rptp_id", baseRptpId)};
    }

    private int countAfter(String table, String idCol, long base) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + idCol + " > ?", Integer.class, base);
        return n == null ? 0 : n;
    }

    private long maxId(String table, String idCol) {
        Long v = jdbcTemplate.queryForObject("SELECT coalesce(max(" + idCol + "), 0) FROM " + table, Long.class);
        return v == null ? 0L : v;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 직렬화 실패: " + value, e);
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 파싱 실패: " + json, e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
