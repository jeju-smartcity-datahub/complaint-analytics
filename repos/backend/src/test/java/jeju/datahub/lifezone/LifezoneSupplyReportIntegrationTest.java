package jeju.datahub.lifezone;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.lifezone.service.LifezoneSupplyGradeCriterionVO;
import jeju.datahub.lifezone.service.LifezoneSupply;
import jeju.datahub.lifezone.service.LifezoneSupplyCellVO;
import jeju.datahub.lifezone.service.LifezoneSupplyReportVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 생활권 <b>수급 보고서</b> 통합 테스트. (내보내기 저장·스냅샷)
 *
 * <h2>🔴 이 테스트는 행을 남긴다 — 스스로 지운다</h2>
 * {@code RANDOM_PORT} 는 요청이 별도 커넥션이라 {@code @Transactional} 롤백이 서버 쪽을 감싸지
 * 못한다(조회 전용인 {@code LifezoneSupplyIntegrationTest} 와 달리 이쪽은 <b>쓰기</b>다).
 * 그래서 만든 {@code rptpId} 를 들고 있다가 {@code @AfterEach} 에서 <b>참조 역순</b>으로 지운다.
 * 🔴 "이 테스트가 만들지 않은 행" 을 지우지 않도록 <b>만든 ID 만</b> 지운다 — 조건을 넓히면
 * 사람이 만든 보고서까지 날아간다.
 *
 * <h2>무엇을 검증하나</h2>
 * <ul>
 *   <li>저장이 <b>다시 분석</b>한다 — 화면이 보낸 숫자를 쓰지 않으므로 조회 결과와 같아야 한다</li>
 *   <li>🔴 {@code anls_type_cd} 가 <b>{@code LIFEZONE_SPLY}</b> 다 — {@code LIFEZONE} 이면
 *       보고서 목록이 민원건수 파서로 보내 조용히 빈 표가 나온다</li>
 *   <li>스냅샷이 <b>등급·순위·범례</b>를 그대로 되살린다 — 굳히지 않으면 같은 셀이 다른 등급이 된다
 *       (종전 임계값·사분면)</li>
 *   <li>두 스냅샷 경로가 <b>서로를 거부</b>한다(404) — 파서가 달라 섞이면 안 된다</li>
 *   <li>보고서 표 등급 {@code grade} 를 굳히고(SEVERE·WARN·CAUTION 밖이면 400),
 *       키 없는 스냅샷은 {@code null}(전체). 종전 {@code qdrn} 계약을 대신한다</li>
 *   <li>사분면 시절 구 스냅샷({@code anlsMthd} 없음)이 원문 그대로 열린다</li>
 *   <li>{@code anls_param_json.crtrVls} 에 생성 시점 설정값이 남고, 설정을 바꿔도
 *       옛 보고서의 범례·셀 등급은 그대로다(케이스 14). 🔴 바꾼 설정은 {@link #restoreSplySettings()} 가
 *       떠 둔 원래 값으로 되돌린다 — JDBC 로 바꾸므로 이력 행은 남지 않는다</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("생활권 수급 보고서 (POST /api/analysis/lifezone/supply/reports)")
class LifezoneSupplyReportIntegrationTest {

    private static final String CREATE_URL = "/api/analysis/lifezone/supply/reports";
    private static final String SUPPLY_SNAPSHOT_URL = "/api/reports/{rptpId}/lifezone/supply";
    private static final String CVLCPT_SNAPSHOT_URL = "/api/reports/{rptpId}/lifezone";
    private static final String ANALYZE_URL = "/api/analysis/lifezone/supply";

    /** 🔴 서비스 상수와 같은 값이어야 한다 — 테스트가 독립적으로 다시 적는다. */
    private static final String ANLS_TYPE_LIFEZONE_SPLY = "LIFEZONE_SPLY";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_RANGE_DAYS = 366;
    private static final String COORD_BBOX =
            " lat BETWEEN 33.10 AND 33.65 AND lot BETWEEN 126.10 AND 127.00 ";
    /** 매퍼 selectDataRange 와 같은 민원 유형 목록. 테스트가 다시 적는다. */
    private static final String CVLCPT_TYPE_FILTER = " AND cvlcpt_type_cd IN ('01','02','03','04','05') ";
    /** 🔴 서비스 상수 {@code LifezoneSupply.ANLS_MTHD_SHRT_RT} 와 같은 값 — 테스트가 다시 적는다. */
    private static final String ANLS_MTHD_SHRT_RT = "SHRT_RT";

    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String queryBgngYmd;
    private String dataEndYmd;

    /** 수급 설정 5행 키. 테스트가 다시 적는다 */
    private static final String STNG_WHERE = " WHERE anls_type_cd = 'LIFEZONE' AND anls_trgt_cd = 'SPLY_DMND' ";

    /** 수급 설정 5행 원래 값 (vrbl_cd → stng_vl) — 케이스 14 복원용 */
    private final Map<String, BigDecimal> originalStng = new LinkedHashMap<>();

    /** 이 테스트가 만든 보고서. {@code @AfterEach} 가 이것만 지운다. */
    private final List<Long> createdRptpIds = new java.util.ArrayList<>();

    /**
     * 🔴 저장은 {@code creatr_id} 에 실사용자를 적고 스냅샷은 소유자 가드를
     * 거친다. 익명은 401 이라 {@code mock-login} 세션을 쓰고, 익명 401 은 케이스 7 이 지킨다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void loginAsMockUser() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
    }

    @AfterEach
    void logoutMockUser() {
        auth.cleanUp();
    }

    @BeforeEach
    void resolveDataRange() {
        // 🔁 2026-09-13 — 기간 기준이 발생일시(ocrn_dt)다 (매퍼 selectDataRange 와 같은 조건 · 유형 01~05)
        String[] range = jdbcTemplate.queryForObject(
                "SELECT to_char(min(ocrn_dt), 'YYYYMMDD'), to_char(max(ocrn_dt), 'YYYYMMDD')"
                        + "  FROM parking.tb_cvlcpt WHERE" + COORD_BBOX + CVLCPT_TYPE_FILTER,
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        assertThat(range[0]).as("좌표가 유효한 민원이 없다 — 적재분을 먼저 확인하라").matches("\\d{8}");
        dataEndYmd = range[1];
        LocalDate bgng = LocalDate.parse(range[0], YMD);
        LocalDate floor = LocalDate.parse(dataEndYmd, YMD).minusDays(MAX_RANGE_DAYS - 1L);
        queryBgngYmd = bgng.isBefore(floor) ? floor.format(YMD) : range[0];

        originalStng.clear();
        jdbcTemplate.query("SELECT vrbl_cd, stng_vl FROM parking.tb_anls_vrbl_stng" + STNG_WHERE,
                rs -> {
                    originalStng.put(rs.getString(1), rs.getBigDecimal(2));
                });
        assertThat(originalStng).as("수급 설정 5행(마이그레이션 91)").hasSize(5);
    }

    /** 🔴 설정 5행을 떠 둔 값으로 되돌린다 — 케이스 14 가 실패·중단해도 돈다 */
    @AfterEach
    void restoreSplySettings() {
        originalStng.forEach((cd, vl) -> jdbcTemplate.update(
                "UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?" + STNG_WHERE + " AND vrbl_cd = ?", vl, cd));
    }

    /**
     * 🔴 만든 행만 참조 역순으로 지운다 ({@code TB_RPTP} → {@code TB_ANLS_RSLT} → {@code TB_ANLS_EXCN}).
     * 조건을 "오늘 만든 것 전부" 처럼 넓히면 테스트가 만들지 않은 보고서까지 날아간다.
     */
    @AfterEach
    void cleanUp() {
        for (Long rptpId : createdRptpIds) {
            Long excnId = jdbcTemplate.queryForObject(
                    "SELECT excn_id FROM parking.tb_rptp WHERE rptp_id = ?", Long.class, rptpId);
            jdbcTemplate.update("DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
            if (excnId != null) {
                jdbcTemplate.update("DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
                jdbcTemplate.update("DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
            }
        }
        createdRptpIds.clear();
    }

    @Test
    @DisplayName("1. 저장 — 201 · 실행/결과/보고서 3표 · anls_type_cd 는 LIFEZONE_SPLY")
    void createsReportWithSupplyAnalysisType() {
        LifezoneSupplyReportVO report = createOk("PDF");

        assertThat(report.getRptpId()).isNotNull();
        assertThat(report.getExcnId()).isNotNull();
        assertThat(report.getRptpNm()).contains("수급").contains(report.getGridSzNm());
        assertThat(report.getRegionNm()).isEqualTo("전체 지역");
        assertThat(report.getFileFmt()).isEqualTo("PDF");
        assertThat(report.getCells()).isNotEmpty();

        String anlsTypeCd = jdbcTemplate.queryForObject(
                "SELECT anls_type_cd FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, report.getExcnId());
        assertThat(anlsTypeCd)
                .as("🔴 LIFEZONE 으로 저장하면 보고서 목록이 민원건수 파서로 보내 빈 표가 나온다")
                .isEqualTo(ANLS_TYPE_LIFEZONE_SPLY);

        // 결과 1행이 실제로 남았는지 (excn_id UNIQUE)
        Integer rsltCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_rslt WHERE excn_id = ?",
                Integer.class, report.getExcnId());
        assertThat(rsltCnt).isEqualTo(1);
    }

    @Test
    @DisplayName("2. 🔴 저장은 다시 분석한다 — 조회 결과와 숫자가 같다")
    void savedReportMatchesFreshAnalysis() {
        LifezoneSupplyReportVO report = createOk("DOCX");
        LifezoneSupplyReportVO fresh = restTemplate.getForObject(
                ANALYZE_URL + "?bgngYmd={b}&endYmd={e}&gridSz={g}",
                LifezoneSupplyReportVO.class,
                Map.of("b", queryBgngYmd, "e", dataEndYmd, "g", LifezoneSupply.DEFAULT_GRID_SZ));
        assertThat(fresh).isNotNull();

        assertThat(fresh.getAnlsMthd()).as("조회 응답도 SHRT_RT 다").isEqualTo(ANLS_MTHD_SHRT_RT);
        assertThat(report.getAnlsMthd()).as("🔴 생성 응답도 SHRT_RT 다 — copyBody 가 빠뜨리면 키가 사라진다")
                .isEqualTo(ANLS_MTHD_SHRT_RT);

        assertThat(report.getTotGridCnt()).isEqualTo(fresh.getTotGridCnt());
        assertThat(report.getTotCvlcptCnt()).isEqualTo(fresh.getTotCvlcptCnt());
        assertThat(report.getTotPkltCpctySum()).isEqualTo(fresh.getTotPkltCpctySum());
        // 🔴 null == null 로 무의미하게 통과하지 않게 값이 실재함을 먼저 본다
        assertThat(fresh.getSevereCnt()).isNotNull();
        assertThat(report.getSevereCnt()).isEqualTo(fresh.getSevereCnt());
        assertThat(report.getWarnCnt()).isNotNull().isEqualTo(fresh.getWarnCnt());
        assertThat(report.getCautionCnt()).isNotNull().isEqualTo(fresh.getCautionCnt());
        assertThat(report.getNormalCnt()).isNotNull().isEqualTo(fresh.getNormalCnt());
        assertThat(report.getLowCnt()).isNotNull().isEqualTo(fresh.getLowCnt());
        assertThat(report.getZeroSplyGridCnt()).isNotNull().isEqualTo(fresh.getZeroSplyGridCnt());
        assertThat(report.getMinMnthAvgCvlcpt()).isNotNull().isEqualTo(fresh.getMinMnthAvgCvlcpt());
        assertCriteriaEqual(report.getGradeCriteria(), fresh.getGradeCriteria());
        assertCellsEqual(report.getCells(), fresh.getCells());
    }

    @Test
    @DisplayName("3. 스냅샷 — anlsMthd·등급 수·범례·셀 등급/순위가 저장 당시 그대로 되살아난다")
    void snapshotRestoresGradesAndRanks() {
        LifezoneSupplyReportVO saved = createOk("PDF");
        LifezoneSupplyReportVO snap = restTemplate.exchange(SUPPLY_SNAPSHOT_URL, HttpMethod.GET,
                new HttpEntity<>(session), LifezoneSupplyReportVO.class, saved.getRptpId()).getBody();
        assertThat(snap).isNotNull();

        assertThat(snap.getRptpId()).isEqualTo(saved.getRptpId());
        assertThat(snap.getRptpNm()).isEqualTo(saved.getRptpNm());
        assertThat(snap.getGridSz()).isEqualTo(saved.getGridSz());

        // 🔴 굳히지 않으면 재현할 수 없는 값들이다
        assertThat(saved.getAnlsMthd()).isEqualTo(ANLS_MTHD_SHRT_RT);
        assertThat(snap.getAnlsMthd())
                .as("🔴 스냅샷이 SHRT_RT 를 싣지 않으면 프론트가 구 사분면 템플릿으로 그린다")
                .isEqualTo(ANLS_MTHD_SHRT_RT);
        assertThat(snap.getSevereCnt()).isNotNull().isEqualTo(saved.getSevereCnt());
        assertThat(snap.getWarnCnt()).isNotNull().isEqualTo(saved.getWarnCnt());
        assertThat(snap.getCautionCnt()).isNotNull().isEqualTo(saved.getCautionCnt());
        assertThat(snap.getNormalCnt()).isNotNull().isEqualTo(saved.getNormalCnt());
        assertThat(snap.getLowCnt()).isNotNull().isEqualTo(saved.getLowCnt());
        assertThat(snap.getZeroSplyGridCnt()).isNotNull().isEqualTo(saved.getZeroSplyGridCnt());
        assertThat(snap.getMinMnthAvgCvlcpt()).isNotNull().isEqualTo(saved.getMinMnthAvgCvlcpt());
        assertCriteriaEqual(snap.getGradeCriteria(), saved.getGradeCriteria());

        // 신규 스냅샷은 구 키를 읽지 않는다 — 0.0/0 이 채워지면 NON_NULL 생략이 깨진다
        assertThat(snap.getCvlcptHighThld()).isNull();
        assertThat(snap.getSplyLowThld()).isNull();
        assertThat(snap.getQdrnCriteria()).isNull();
        assertThat(snap.getQ1Cnt()).isNull();

        assertThat(snap.getTotGridCnt()).isEqualTo(saved.getTotGridCnt());
        assertThat(snap.getTotCvlcptCnt()).isEqualTo(saved.getTotCvlcptCnt());
        assertCellsEqual(snap.getCells(), saved.getCells());

        LifezoneSupplyCellVO first = snap.getCells().get(0);
        assertThat(first.getRnk()).as("셀은 부족 순위 순으로 굳는다").isEqualTo(1);
        assertThat(first.getRing()).as("격자 폴리곤도 굳힌다 — 스냅샷은 재계산하지 않는다").hasSize(4);

        // ⚠️ 보유 구간은 "지금" 의 값이라 스냅샷이 아니다 — 의도적으로 비어 있다
        assertThat(snap.getDataBgngYmd()).isNull();
        assertThat(snap.getDataEndYmd()).isNull();
    }

    @Test
    @DisplayName("4. 🔴 두 스냅샷 경로가 서로를 거부한다 — 민원건수 경로로 수급 보고서를 부르면 404")
    void cvlcptSnapshotRejectsSupplyReport() {
        LifezoneSupplyReportVO saved = createOk("PDF");
        ResponseEntity<String> wrong = restTemplate.exchange(
                CVLCPT_SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session), String.class, saved.getRptpId());
        assertThat(wrong.getStatusCode().value())
                .as("파서가 다르다 — 조용히 읽어 빈 문서를 만드는 대신 끊어야 한다")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("5. 결과 0건이면 400 — 빈 보고서를 남기지 않는다 (조회는 200 인 것과 다르다)")
    void emptyResultIsRejected() {
        // 보유 구간보다 뒤 — 조회로는 200 + 빈 cells 지만 보고서로는 만들지 않는다
        LocalDate after = LocalDate.parse(dataEndYmd, YMD).plusYears(5);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", after.format(YMD));
        body.put("endYmd", after.plusDays(10).format(YMD));
        body.put("fileFmt", "PDF");

        assertThat(post(body).getStatusCode().value()).isEqualTo(400);

        // 같은 조건의 조회는 200 이다 — 둘의 차이가 이 케이스의 요점이다
        assertThat(restTemplate.getForEntity(
                ANALYZE_URL + "?bgngYmd={b}&endYmd={e}", String.class,
                Map.of("b", after.format(YMD), "e", after.plusDays(10).format(YMD)))
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("6. fileFmt 가 PDF|DOCX|XLSX 밖이면 400")
    void invalidFileFmtIsRejected() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", queryBgngYmd);
        body.put("endYmd", dataEndYmd);
        body.put("fileFmt", "HWP");
        assertThat(post(body).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("7. 🔴 익명 HTTP 는 저장·스냅샷 모두 401 이고 보고서가 한 행도 늘지 않는다")
    void anonymousIs401AndInsertsNothing() {
        // 바디는 유효하다 — 같은 바디를 세션으로 보내면 201 이다(= 401 의 원인이 바디가 아니다)
        LifezoneSupplyReportVO ok = createOk("PDF");
        long before = rptpCount();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", queryBgngYmd);
        body.put("endYmd", dataEndYmd);
        body.put("gridSz", LifezoneSupply.DEFAULT_GRID_SZ);
        body.put("fileFmt", "PDF");
        assertThat(restTemplate.postForEntity(CREATE_URL, body, String.class).getStatusCode().value())
                .as("익명 저장").isEqualTo(401);
        assertThat(restTemplate.getForEntity(SUPPLY_SNAPSHOT_URL, String.class, ok.getRptpId())
                .getStatusCode().value()).as("익명 스냅샷 — 내 보고서라도 세션이 없으면 401").isEqualTo(401);

        assertThat(rptpCount()).as("401 이면 아무것도 저장하지 않는다").isEqualTo(before);
    }

    @Test
    @DisplayName("8. grade=WARN 으로 저장하면 응답·스냅샷 모두 grade=WARN (보고서 표 = 화면에서 고른 칩)")
    void gradeIsSavedAndRestored() {
        LifezoneSupplyReportVO saved = createOk("PDF", "WARN");
        assertThat(saved.getGrade()).isEqualTo("WARN");

        LifezoneSupplyReportVO snap = snapshot(saved.getRptpId());
        assertThat(snap.getGrade())
                .as("🔴 굳히지 않으면 다시 열었을 때 화면과 다른 표가 그려진다")
                .isEqualTo("WARN");
        assertThat(snap.getCells())
                .as("셀은 등급과 무관하게 전량 저장한다 — 표만 거른다")
                .hasSameSizeAs(saved.getCells());
        assertThat(snap.getQdrn()).as("신규 스냅샷에는 qdrn 이 없다").isNull();
    }

    @Test
    @DisplayName("9. grade 생략이면 응답·스냅샷 grade 는 null (= 전체)")
    void omittedGradeIsNull() {
        LifezoneSupplyReportVO saved = createOk("PDF");
        assertThat(saved.getGrade()).isNull();
        assertThat(snapshot(saved.getRptpId()).getGrade()).isNull();
    }

    @Test
    @DisplayName("10. grade 가 SEVERE·WARN·CAUTION 밖(NORMAL·LOW·미지원)이면 400 · 3표가 한 행도 늘지 않는다")
    void invalidGradeIsRejected() {
        // 🔴 NORMAL·LOW 는 실재하는 등급이지만 리스트 대상이 아니라 표가 0행이 된다 — 그래서 400
        for (String grade : List.of("NORMAL", "LOW", "Q9", "Q1")) {
            long[] before = tableCounts();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("bgngYmd", queryBgngYmd);
            body.put("endYmd", dataEndYmd);
            body.put("gridSz", LifezoneSupply.DEFAULT_GRID_SZ);
            body.put("fileFmt", "PDF");
            body.put("grade", grade);
            ResponseEntity<String> response = post(body);

            assertThat(response.getStatusCode().value()).as("grade=%s", grade).isEqualTo(400);
            assertThat(tableCounts())
                    .as("🔴 grade=%s — 분석 전에 끊는다 — 잘못된 값으로 행을 남기지 않는다", grade)
                    .containsExactly(before);
        }
    }

    @Test
    @DisplayName("11. grade 는 대소문자·공백을 관대하게 받아 대문자로 정규화한다 (severe → SEVERE)")
    void gradeIsNormalizedToUpperCase() {
        LifezoneSupplyReportVO saved = createOk("PDF", "severe");
        assertThat(saved.getGrade()).isEqualTo("SEVERE");
        assertThat(snapshot(saved.getRptpId()).getGrade()).as("스냅샷도 정규화된 값을 굳힌다").isEqualTo("SEVERE");

        LifezoneSupplyReportVO caution = createOk("PDF", " Caution ");
        assertThat(caution.getGrade()).isEqualTo("CAUTION");
    }

    @Test
    @DisplayName("12. 🔴 grade 키가 없는 스냅샷은 null (= 전체)")
    void snapshotWithoutGradeKeyIsNull() {
        LifezoneSupplyReportVO saved = createOk("PDF", "CAUTION");
        // 키 없는 스냅샷 재현 — 🔴 자기 생성 행만 UPDATE 한다
        int updated = jdbcTemplate.update(
                "UPDATE parking.tb_anls_excn SET anls_param_json = anls_param_json - 'grade' WHERE excn_id = ?",
                saved.getExcnId());
        assertThat(updated).isEqualTo(1);
        Boolean hasKey = jdbcTemplate.queryForObject(
                // 🔴 jsonb ? 연산자는 JDBC 자리표시자와 겹친다 — -> 로 본다
                "SELECT (anls_param_json -> 'grade') IS NOT NULL FROM parking.tb_anls_excn WHERE excn_id = ?",
                Boolean.class, saved.getExcnId());
        assertThat(hasKey).as("전제 — 키가 정말 빠졌다").isFalse();

        LifezoneSupplyReportVO snap = snapshot(saved.getRptpId());
        assertThat(snap.getGrade()).as("🔴 키가 없으면 전체로 그린다 — 보고서가 400·500 이 되면 안 된다").isNull();
        assertThat(snap.getAnlsMthd()).as("anlsMthd 는 그대로라 신규 분기로 읽힌다").isEqualTo(ANLS_MTHD_SHRT_RT);
        assertThat(snap.getCells()).hasSameSizeAs(saved.getCells());
    }

    @Test
    @DisplayName("13. 🔴 사분면 구 스냅샷(anlsMthd 없음)은 200 이고 원문 필드가 그대로 · 신규 키는 생략")
    void legacyQuadrantSnapshotIsReturnedAsIs() throws IOException {
        LifezoneSupplyReportVO saved = createOk("PDF");
        // 🔴 자기 생성 행을 고정 구 형식 JSON 으로 덮는다 — 로컬 기존 rptp_id 에 기대지 않는다.
        //    행 삭제는 @AfterEach cleanUp 이 createdRptpIds 로 한다.
        assertThat(jdbcTemplate.update(
                "UPDATE parking.tb_anls_excn SET anls_param_json = ?::jsonb WHERE excn_id = ?",
                LEGACY_PARAM_JSON, saved.getExcnId())).isEqualTo(1);
        assertThat(jdbcTemplate.update(
                "UPDATE parking.tb_anls_rslt SET rslt_json = ?::jsonb WHERE excn_id = ?",
                LEGACY_RSLT_JSON, saved.getExcnId())).isEqualTo(1);

        ResponseEntity<String> response = restTemplate.exchange(SUPPLY_SNAPSHOT_URL, HttpMethod.GET,
                new HttpEntity<>(session), String.class, saved.getRptpId());
        assertThat(response.getStatusCode().value()).as("구 스냅샷이 열리지 않으면 과거 보고서가 사라진다").isEqualTo(200);
        JsonNode body = RESPONSE_MAPPER.readTree(response.getBody());

        // 신규 분기 키는 키 자체가 없다 (NON_NULL)
        for (String key : List.of("anlsMthd", "severeCnt", "warnCnt", "cautionCnt", "normalCnt", "lowCnt",
                "zeroSplyGridCnt", "minMnthAvgCvlcpt", "gradeCriteria", "gridNotice", "vrblAjmt")) {
            assertThat(body.has(key)).as("구 스냅샷 응답에 신규 키 %s 가 생겼다", key).isFalse();
        }
        // 🔁 ReportVO grade 는 NON_NULL 이 없다 — 종전 qdrn 처럼 "grade": null(= 전체)로 온다
        assertThat(body.has("grade")).as("grade 키는 null 로 실린다").isTrue();
        assertThat(body.get("grade").isNull()).isTrue();

        // 원문 필드 그대로 (재해석하지 않는다)
        assertThat(body.path("qdrn").asText()).isEqualTo("Q1");
        assertThat(body.path("cvlcptHighThld").asDouble()).isEqualTo(40.0);
        assertThat(body.path("splyLowThld").asDouble()).isEqualTo(17.5);
        assertThat(body.path("q1Cnt").asInt()).isEqualTo(1);
        assertThat(body.path("q2Cnt").asInt()).isEqualTo(0);
        assertThat(body.path("q3Cnt").asInt()).isEqualTo(1);
        assertThat(body.path("q4Cnt").asInt()).isEqualTo(0);
        assertThat(body.path("totGridCnt").asInt()).isEqualTo(2);
        assertThat(body.path("totCvlcptCnt").asInt()).isEqualTo(65);
        assertThat(body.path("totPkltCpctySum").asInt()).isEqualTo(10);
        assertThat(body.path("sectionSub").asText()).as("저장 당시 부제를 쓴다").isEqualTo("공영주차 공급 대비 민원 발생량");

        JsonNode criteria = body.path("qdrnCriteria");
        assertThat(criteria.isArray()).isTrue();
        assertThat(criteria.size()).isEqualTo(4);
        assertThat(criteria.get(0).path("qdrn").asText()).isEqualTo("Q1");
        assertThat(criteria.get(0).path("lgndTxt").asText()).isEqualTo("부족 의심 (민원 40건 이상 · 공영주차면 17면 이하)");
        assertThat(criteria.get(0).path("gridCnt").asInt()).isEqualTo(1);
        assertThat(criteria.get(2).path("zeroSplyGridCnt").asInt()).isEqualTo(1);

        JsonNode cells = body.path("cells");
        assertThat(cells.size()).isEqualTo(2);
        JsonNode withSupply = cells.get(0);
        assertThat(withSupply.path("gridId").asText()).isEqualTo("500m_1812_3000");
        assertThat(withSupply.path("qdrn").asText()).isEqualTo("Q1");
        assertThat(withSupply.path("qdrnNm").asText()).isEqualTo("부족 의심");
        assertThat(withSupply.path("rnk").asInt()).isEqualTo(1);
        assertThat(withSupply.path("cvlcptPerSlot").decimalValue()).isEqualByComparingTo("6.0000");
        JsonNode zeroSupply = cells.get(1);
        assertThat(zeroSupply.path("qdrn").asText()).isEqualTo("Q3");
        assertThat(zeroSupply.has("cvlcptPerSlot"))
                .as("원문이 null 인 값은 NON_NULL 로 키 자체가 생략된다")
                .isFalse();
        for (JsonNode cell : cells) {
            for (String key : List.of("grade", "gradeNm", "shortageRatio", "demandIndex")) {
                assertThat(cell.has(key)).as("구 셀에 신규 키 %s 가 생겼다", key).isFalse();
            }
        }
    }

    /**
     * 사분면 시절 {@code anls_param_json} 고정 형식 (로컬 실 스냅샷의 키 구성을 그대로 옮겼다).
     * {@code anlsMthd}·{@code grade}·{@code gradeCriteria} 키가 없다.
     */
    private static final String LEGACY_PARAM_JSON = """
            {"crs": "EPSG:5179", "qdrn": "Q1", "gridSz": 500, "gridSzNm": "500m",
             "anlsTrgtCd": "SPLY_DMND", "anlsTrgtNm": "주차 수급",
             "disclaimer": "공영주차장 기준이며 민영·부설주차장은 포함되지 않습니다. 민원 발생량 기준으로, 주차 수요를 직접 측정한 값이 아닙니다.",
             "sectionSub": "공영주차 공급 대비 민원 발생량", "sectionTitle": "주차 수급 부족 구역",
             "cvlcptHighThld": 40.0, "splyLowThld": 17.5,
             "qdrnCriteria": [
               {"qdrn": "Q1", "qdrnNm": "부족 의심", "gridCnt": 1, "lgndTxt": "부족 의심 (민원 40건 이상 · 공영주차면 17면 이하)", "cvlcptCnt": 60, "qdrnExpln": "민원 많음 · 공영주차면 적음", "zeroSplyGridCnt": 0},
               {"qdrn": "Q2", "qdrnNm": "민원 집중", "gridCnt": 0, "lgndTxt": "민원 집중 (민원 40건 이상 · 공영주차면 17면 초과)", "cvlcptCnt": 0, "qdrnExpln": "민원 많음 · 공영주차면 있음", "zeroSplyGridCnt": 0},
               {"qdrn": "Q3", "qdrnNm": "공급 부족", "gridCnt": 1, "lgndTxt": "공급 부족 (민원 40건 미만 · 공영주차면 17면 이하)", "cvlcptCnt": 5, "qdrnExpln": "민원 적음 · 공영주차면 적음", "zeroSplyGridCnt": 1},
               {"qdrn": "Q4", "qdrnNm": "양호", "gridCnt": 0, "lgndTxt": "양호 (민원 40건 미만 · 공영주차면 17면 초과)", "cvlcptCnt": 0, "qdrnExpln": "민원 적음 · 공영주차면 있음", "zeroSplyGridCnt": 0}
             ]}
            """;

    /** 사분면 시절 {@code rslt_json} 고정 형식 — 공급 있는 셀(cvlcptPerSlot 있음) 1 · 공급 0 셀(키 없음) 1. */
    private static final String LEGACY_RSLT_JSON = """
            {"summary": {"totGridCnt": 2, "totCvlcptCnt": 65, "totPkltCpctySum": 10,
                         "q1Cnt": 1, "q2Cnt": 0, "q3Cnt": 1, "q4Cnt": 0},
             "cells": [
               {"rnk": 1, "qdrn": "Q1", "qdrnNm": "부족 의심", "gridId": "500m_1812_3000",
                "ring": [[33.487713451, 126.488163657], [33.492222578, 126.48811121], [33.49226641, 126.493492871], [33.487757275, 126.49354504]],
                "emdCd": "50110137", "emdNm": "연동", "sggNm": "제주시", "ctrLat": 33.4899900, "ctrLot": 126.4908282,
                "pkltCnt": 1, "cvlcptCnt": 60, "dstncPtCnt": 12, "cncntWarnYn": "N", "pkltCpctySum": 10, "cvlcptPerSlot": 6.0000},
               {"rnk": 1, "qdrn": "Q3", "qdrnNm": "공급 부족", "gridId": "500m_1812_2999",
                "ring": [[33.483204319, 126.488216093], [33.487713451, 126.488163657], [33.487757275, 126.49354504], [33.483248137, 126.493597197]],
                "emdCd": "50110137", "emdNm": "연동", "sggNm": "제주시", "ctrLat": 33.4854808, "ctrLot": 126.4908805,
                "pkltCnt": 0, "cvlcptCnt": 5, "dstncPtCnt": 3, "cncntWarnYn": "N", "pkltCpctySum": 0}
             ]}
            """;

    @Test
    @DisplayName("14. crtrVls 에 생성 시점 설정값이 남고, 설정을 바꿔도 옛 보고서 범례·셀 등급·최소 기준은 불변")
    void crtrVlsIsFrozenAndSettingChangeDoesNotAlterOldReport() throws IOException {
        LifezoneSupplyReportVO saved = createOk("PDF");

        // ① anls_param_json.crtrVls == 생성 시점 DB 설정값 (🔴 기본값 5·20/40/60/80 을 박지 않는다)
        String paramJson = jdbcTemplate.queryForObject(
                "SELECT anls_param_json FROM parking.tb_anls_excn WHERE excn_id = ?", String.class, saved.getExcnId());
        JsonNode crtrVls = RESPONSE_MAPPER.readTree(paramJson).path("crtrVls");
        assertThat(crtrVls.isObject()).as("anls_param_json 에 crtrVls 가 있다").isTrue();
        assertThat(crtrVls.path("minMnthAvgCvlcpt").asInt())
                .isEqualTo(originalStng.get("SPLY_MIN_MNTH_AVG_CVLCPT").intValueExact());
        assertThat(crtrVls.path("grdBndrys")).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(crtrVls.path("grdBndrys").get(i).asInt()).as("grdBndrys[%d] = BNDRY%d", i, i + 1)
                    .isEqualTo(originalStng.get("SPLY_GRD_BNDRY" + (i + 1)).intValueExact());
        }
        assertThat(saved.getMinMnthAvgCvlcpt())
                .isEqualTo(originalStng.get("SPLY_MIN_MNTH_AVG_CVLCPT").intValueExact());

        // ② 설정을 바꾼다 — 현재와 다른 유효한 경계 한 세트 + 최소 기준 ±1
        int[] cur = new int[4];
        for (int i = 0; i < 4; i++) {
            cur[i] = originalStng.get("SPLY_GRD_BNDRY" + (i + 1)).intValueExact();
        }
        int[] changed = java.util.Arrays.equals(cur, new int[] { 10, 30, 50, 90 })
                ? new int[] { 20, 40, 60, 80 } : new int[] { 10, 30, 50, 90 };
        int curMin = originalStng.get("SPLY_MIN_MNTH_AVG_CVLCPT").intValueExact();
        updateStng("SPLY_MIN_MNTH_AVG_CVLCPT", curMin < 30 ? curMin + 1 : curMin - 1);
        for (int i = 0; i < 4; i++) {
            updateStng("SPLY_GRD_BNDRY" + (i + 1), changed[i]);
        }

        // ③ 옛 보고서 스냅샷은 생성 당시 범례·셀·최소 기준 그대로다 (재판정하지 않는다)
        LifezoneSupplyReportVO snap = snapshot(saved.getRptpId());
        assertCriteriaEqual(snap.getGradeCriteria(), saved.getGradeCriteria());
        assertCellsEqual(snap.getCells(), saved.getCells());
        assertThat(snap.getMinMnthAvgCvlcpt()).isEqualTo(saved.getMinMnthAvgCvlcpt());

        // 대조군 — 지금 조회하면 범례 하한이 바뀐 값이다(설정이 실제로 먹었다)
        LifezoneSupplyReportVO fresh = restTemplate.getForObject(
                ANALYZE_URL + "?bgngYmd={b}&endYmd={e}&gridSz={g}", LifezoneSupplyReportVO.class,
                Map.of("b", queryBgngYmd, "e", dataEndYmd, "g", LifezoneSupply.DEFAULT_GRID_SZ));
        assertThat(fresh).isNotNull();
        assertThat(fresh.getGradeCriteria())
                .extracting(LifezoneSupplyGradeCriterionVO::getMinShrtRt)
                .containsExactly(changed[3], changed[2], changed[1], changed[0], 0);
    }

    private void updateStng(String vrblCd, int stngVl) {
        int updated = jdbcTemplate.update(
                "UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ?" + STNG_WHERE + " AND vrbl_cd = ?",
                BigDecimal.valueOf(stngVl), vrblCd);
        assertThat(updated).as("설정 행 %s", vrblCd).isEqualTo(1);
    }

    /** 범례 5행이 코드·문구·하한·집계까지 같다. */
    private static void assertCriteriaEqual(List<LifezoneSupplyGradeCriterionVO> actual,
                                            List<LifezoneSupplyGradeCriterionVO> expected) {
        assertThat(expected).as("기준 범례는 5행").hasSize(5);
        assertThat(actual).as("범례 5행").hasSize(5);
        for (int i = 0; i < expected.size(); i++) {
            LifezoneSupplyGradeCriterionVO a = actual.get(i);
            LifezoneSupplyGradeCriterionVO e = expected.get(i);
            assertThat(a.getGrade()).isEqualTo(e.getGrade());
            assertThat(a.getGradeNm()).isEqualTo(e.getGradeNm());
            assertThat(a.getLgndTxt()).isEqualTo(e.getLgndTxt());
            assertThat(a.getMinShrtRt()).isEqualTo(e.getMinShrtRt());
            assertThat(a.getGridCnt()).as("%s 격자 수", e.getGrade()).isEqualTo(e.getGridCnt());
            assertThat(a.getCvlcptCnt()).as("%s 민원 수", e.getGrade()).isEqualTo(e.getCvlcptCnt());
            assertThat(a.getZeroSplyGridCnt()).as("%s 공급 0", e.getGrade()).isEqualTo(e.getZeroSplyGridCnt());
        }
    }

    /** 셀 전량이 같은 순서로 격자·등급·순위·부족률까지 같다 — 등급은 재판정하지 않고 저장값을 쓴다. */
    private static void assertCellsEqual(List<LifezoneSupplyCellVO> actual, List<LifezoneSupplyCellVO> expected) {
        assertThat(expected).isNotEmpty();
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            LifezoneSupplyCellVO a = actual.get(i);
            LifezoneSupplyCellVO e = expected.get(i);
            assertThat(a.getGridId()).as("%d 번째 셀", i).isEqualTo(e.getGridId());
            assertThat(a.getGrade()).as("%s 등급", e.getGridId()).isNotNull().isEqualTo(e.getGrade());
            assertThat(a.getRnk()).as("%s 순위", e.getGridId()).isEqualTo(e.getRnk());
            assertThat(a.getShortageRatio()).as("%s 부족률", e.getGridId())
                    .isNotNull().isEqualByComparingTo(e.getShortageRatio());
            assertThat(a.getDemandIndex()).as("%s 수요", e.getGridId()).isEqualTo(e.getDemandIndex());
            assertThat(a.getCvlcptCnt()).isEqualTo(e.getCvlcptCnt());
            assertThat(a.getPkltCpctySum()).isEqualTo(e.getPkltCpctySum());
        }
    }

    private LifezoneSupplyReportVO snapshot(Long rptpId) {
        ResponseEntity<LifezoneSupplyReportVO> response = restTemplate.exchange(SUPPLY_SNAPSHOT_URL,
                HttpMethod.GET, new HttpEntity<>(session), LifezoneSupplyReportVO.class, rptpId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /** 실행·결과·보고서 3표 행 수 ({@code HotspotReportIntegrationTest} 와 같은 형태). */
    private long[] tableCounts() {
        return new long[] {
                countOf("parking.tb_anls_excn"),
                countOf("parking.tb_anls_rslt"),
                countOf("parking.tb_rptp") };
    }

    private long countOf(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private long rptpCount() {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_rptp", Long.class);
        return n == null ? 0L : n;
    }

    /** 세션을 실은 저장 호출 (400 을 기대하는 케이스용). */
    private ResponseEntity<String> post(Map<String, Object> body) {
        return restTemplate.exchange(CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body), String.class);
    }

    // ─────────────────────────────────────────────────────── 헬퍼

    private LifezoneSupplyReportVO createOk(String fileFmt) {
        return createOk(fileFmt, null);
    }

    /** @param grade 보고서 표 등급(종전 qdrn). {@code null} 이면 키를 싣지 않는다(= 생략). */
    private LifezoneSupplyReportVO createOk(String fileFmt, String grade) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", queryBgngYmd);
        body.put("endYmd", dataEndYmd);
        body.put("gridSz", LifezoneSupply.DEFAULT_GRID_SZ);
        body.put("fileFmt", fileFmt);
        if (grade != null) {
            body.put("grade", grade);
        }

        ResponseEntity<LifezoneSupplyReportVO> response = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body), LifezoneSupplyReportVO.class);
        assertThat(response.getStatusCode().value()).as("저장은 201 이다").isEqualTo(201);
        LifezoneSupplyReportVO report = response.getBody();
        assertThat(report).isNotNull();
        createdRptpIds.add(report.getRptpId());   // 🔴 반드시 등록 — @AfterEach 가 이것만 지운다
        return report;
    }

    // ─────────────────────────────────────────────────────── 분석 계수 일시 입력

    /**
     * 🔴 입력은 현재 설정에서 파생한다 — 최소 기준 ±1, 경계는 현재와 다른 유효한 한 세트(케이스 14 와 같은 규칙).
     * 설정 표는 바꾸지 않는다.
     */
    @Test
    @DisplayName("V1. vrblVls — 적용값이 POST·crtrVls·vrblAjmt·스냅샷에 남고 같은 입력의 조회와 숫자가 같으며 설정 표는 그대로다")
    void vrblVlsInputReachesReport() throws IOException {
        String settingsSql = "SELECT * FROM parking.tb_anls_vrbl_stng ORDER BY anls_type_cd, vrbl_cd";
        List<Map<String, Object>> settingsBefore = jdbcTemplate.queryForList(settingsSql);
        int curMin = originalStng.get("SPLY_MIN_MNTH_AVG_CVLCPT").intValueExact();
        int[] cur = new int[4];
        for (int i = 0; i < cur.length; i++) {
            cur[i] = originalStng.get("SPLY_GRD_BNDRY" + (i + 1)).intValueExact();
        }
        int newMin = curMin < 30 ? curMin + 1 : curMin - 1;
        int[] nb = java.util.Arrays.equals(cur, new int[] { 10, 30, 50, 90 })
                ? new int[] { 20, 40, 60, 80 }
                : new int[] { 10, 30, 50, 90 };
        Map<String, Object> vrblVls = new LinkedHashMap<>();
        vrblVls.put("SPLY_MIN_MNTH_AVG_CVLCPT", newMin);
        for (int i = 0; i < nb.length; i++) {
            vrblVls.put("SPLY_GRD_BNDRY" + (i + 1), nb[i]);
        }

        LifezoneSupplyReportVO created = createOkWith(vrblVls);
        assertThat(created.getMinMnthAvgCvlcpt()).as("적용 최소 기준").isEqualTo(newMin);
        assertThat(created.getGradeCriteria()).extracting(LifezoneSupplyGradeCriterionVO::getMinShrtRt)
                .containsExactly(nb[3], nb[2], nb[1], nb[0], 0);
        assertThat(created.getVrblAjmt()).as("POST 응답도 채운다").containsEntry("ajmtYn", "Y");

        // 🔴 저장 블록 crtrVls 는 적용값이다(재현 근거)
        JsonNode param = RESPONSE_MAPPER.readTree(jdbcTemplate.queryForObject(
                "SELECT anls_param_json::text FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, created.getExcnId()));
        assertThat(param.path("crtrVls").path("minMnthAvgCvlcpt").asInt()).isEqualTo(newMin);
        List<Integer> savedBndrys = new java.util.ArrayList<>();
        param.path("crtrVls").path("grdBndrys").forEach(n -> savedBndrys.add(n.asInt()));
        assertThat(savedBndrys).containsExactly(nb[0], nb[1], nb[2], nb[3]);
        assertThat(param.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("Y");
        assertThat(param.path("vrblAjmt").path("vrbls").size()).as("허용 목록 5행 전부").isEqualTo(5);

        LifezoneSupplyReportVO snap = snapshot(created.getRptpId());
        assertThat(snap.getVrblAjmt()).as("POST·GET 이 같은 값이다").isEqualTo(created.getVrblAjmt());
        assertCriteriaEqual(snap.getGradeCriteria(), created.getGradeCriteria());

        ResponseEntity<LifezoneSupplyReportVO> queried = restTemplate.getForEntity(
                ANALYZE_URL + "?bgngYmd={b}&endYmd={e}&gridSz={g}&vrblVls={v}", LifezoneSupplyReportVO.class,
                queryBgngYmd, dataEndYmd, LifezoneSupply.DEFAULT_GRID_SZ,
                RESPONSE_MAPPER.writeValueAsString(vrblVls));
        assertThat(queried.getStatusCode().value()).isEqualTo(200);
        LifezoneSupplyReportVO fresh = queried.getBody();
        assertThat(fresh).isNotNull();
        assertThat(created.getTotGridCnt()).isEqualTo(fresh.getTotGridCnt());
        assertThat(created.getSevereCnt()).isNotNull().isEqualTo(fresh.getSevereCnt());
        assertThat(created.getVrblAjmt()).isEqualTo(fresh.getVrblAjmt());
        assertCriteriaEqual(created.getGradeCriteria(), fresh.getGradeCriteria());
        assertCellsEqual(snap.getCells(), fresh.getCells());

        assertThat(jdbcTemplate.queryForList(settingsSql)).as("설정 표는 그대로다").isEqualTo(settingsBefore);
    }

    @Test
    @DisplayName("V2. vrblVls 생략 — vrblAjmt ajmtYn N 이 POST·스냅샷에 같다 / 위반(소수·역전)은 400 이고 3표가 늘지 않는다")
    void omittedAndInvalidVrblVls() {
        LifezoneSupplyReportVO plain = createOk("PDF");
        assertThat(plain.getVrblAjmt()).containsEntry("ajmtYn", "N");
        assertThat(snapshot(plain.getRptpId()).getVrblAjmt()).isEqualTo(plain.getVrblAjmt());

        long[] before = tableCounts();
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("SPLY_GRD_BNDRY1", new BigDecimal("10.5")),
                Map.of("SPLY_GRD_BNDRY1", originalStng.get("SPLY_GRD_BNDRY2")),
                Map.of("PCSHR_CRTR_VL", new BigDecimal("0.8")))) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("bgngYmd", queryBgngYmd);
            body.put("endYmd", dataEndYmd);
            body.put("gridSz", LifezoneSupply.DEFAULT_GRID_SZ);
            body.put("fileFmt", "PDF");
            body.put("vrblVls", bad);
            assertThat(post(body).getStatusCode().value()).as("%s", bad).isEqualTo(400);
        }
        assertThat(tableCounts()).as("400 은 아무것도 남기지 않는다").containsExactly(before);
    }

    private LifezoneSupplyReportVO createOkWith(Map<String, Object> vrblVls) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", queryBgngYmd);
        body.put("endYmd", dataEndYmd);
        body.put("gridSz", LifezoneSupply.DEFAULT_GRID_SZ);
        body.put("fileFmt", "PDF");
        body.put("vrblVls", vrblVls);
        ResponseEntity<LifezoneSupplyReportVO> response = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body), LifezoneSupplyReportVO.class);
        assertThat(response.getStatusCode().value()).as("저장은 201 이다").isEqualTo(201);
        LifezoneSupplyReportVO report = response.getBody();
        assertThat(report).isNotNull();
        createdRptpIds.add(report.getRptpId());   // 🔴 반드시 등록 — @AfterEach 가 이것만 지운다
        return report;
    }
}
