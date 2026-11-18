package jeju.datahub.complaint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
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

import jeju.datahub.complaint.service.ComplaintKpiVO;
import jeju.datahub.complaint.service.ComplaintReportVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 민원현황 보고서 <b>생성 → 스냅샷 조회</b> 통합 테스트.
 * ({@code POST /api/complaints/reports} · {@code GET /api/reports/{rptpId}/complaint})
 *
 * <p>구조는 {@code jeju.datahub.analysis.HotspotReportIntegrationTest} 를 그대로 따른다 —
 * {@code RANDOM_PORT} + 실제 커밋 + "내가 만든 id 만" 수동 복원.
 *
 * <h2>🔴 왜 {@code @Transactional} 롤백을 쓰지 않는가</h2>
 * {@code RANDOM_PORT} 는 요청을 별도 스레드·별도 커넥션으로 처리해 테스트 트랜잭션이 서버 쪽 쓰기를
 * 감싸지 못한다(롤백이 걸리지 않는다). 더구나 이 테스트가 잡으려는 것이 <b>트랜잭션 밖에서 다시 읽은
 * 스냅샷</b>이라, 롤백이 걸리는 조합을 택하면 검증 대상 자체가 사라진다.
 *
 * <h2>🔴 정리는 "내가 만든 id" 만 지운다 — 범위로 지우지 않는다</h2>
 * {@link #deleteOnlyWhatThisTestCreated()} 가 각 케이스의 응답에서 모은 {@code rptpId}·{@code excnId} 를
 * <b>FK 방향(결과 → 보고서 → 실행)</b> 으로 지운다. {@code WHERE rptp_id >= 100} 같은 범위 삭제는
 * "이 기능이 만드는 모든 보고서" 를 지우는 것이라 사용자·수동 검증분까지 날린다.
 * {@code report.ReportIntegrationTest} 는 시드 범위({@code rptp_id < 100})만 건드리므로 겹치지 않는다 —
 * 실행 순서를 바꿔도 결과가 같다.
 *
 * <h2>🔴 조회 날짜를 상수로 박지 않는다</h2>
 * 민원 적재분은 배치가 소유한다 — 재적재 한 번이면 구간이 통째로 옮겨간다. 그래서 조회 구간은
 * {@link #resolveDataFixtures()} 가 {@code min/max(ocrn_dt)} 로 매번 DB 에서 읽고, "0건 구간" 도
 * 리터럴이 아니라 <b>보유 구간 밖</b>을 계산해 만든 뒤 정말 0건인지 SQL 로 먼저 확인한다.
 *
 * <p>응답 바디의 {@code message} 문자열은 단언하지 않는다 — devtools 유무에 따라 갈려 환경에 따라
 * 깨지는 단언이 된다. <b>상태코드만</b> 본다. 같은 이유로 실패가 예상되는 호출은 응답 타입을
 * {@code String.class} 로 받는다.
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다 — 모든 프로파일이 PostgreSQL 이고 H2 는 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComplaintReportIntegrationTest {

    private static final String CREATE_URL = "/api/complaints/reports";
    private static final String SNAPSHOT_URL = "/api/reports/{rptpId}/complaint";
    private static final String GIS_URL = "/api/complaints/gis";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 조회 가능한 최대 구간(일, 양끝 포함). {@code jeju.datahub.common.DateRanges.MAX_RANGE_DAYS} 와
     * 같은 값이어야 한다 — 테스트가 독립적으로 다시 적는다.
     */
    private static final int MAX_RANGE_DAYS = 366;

    /** 시군구코드 — 케이스 11 의 "시군구 전체" 형태 입력값 ({@code TB_ADMDST} 의 고정 코드다). */
    private static final String SGG_CD_JEJU = "50110";

    private static final String SGG_NM_JEJU = "제주시";

    /** 읍면동코드 — 케이스 11 의 "시군구 읍면동" 형태 입력값. */
    private static final String EMD_CD_YEONDONG = "50110137";

    /** {@code TB_ADMDST} 에 없는 코드 (케이스 6·7). 존재하지 않음을 테스트가 먼저 확인한다. */
    private static final String UNKNOWN_SGG_CD = "99999";
    private static final String UNKNOWN_EMD_CD = "99999999";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 원천({@code TB_CVLCPT}) 보유 구간 시작일 — 상수 박기 금지. */
    private String dataBgngYmd;

    /** 원천 보유 구간 종료일. */
    private String dataEndYmd;

    /**
     * 실제로 요청 기간으로 쓰는 시작일 = {@code max(dataBgngYmd, dataEndYmd - 365일)}.
     * 🔴 보유 구간을 그대로 요청하면 상한이 없다 — 수집 배치가 구간을 밀어 올려 366일
     * ({@code DateRanges.MAX_RANGE_DAYS})을 넘는 순간 {@link #fullPeriod()} 를 쓰는 전 케이스가
     * 한꺼번에 400 을 받는다. 시작일만 자른다.
     */
    private String queryBgngYmd;

    /** 🔴 이 테스트가 만든(또는 직접 심은) id. {@link #deleteOnlyWhatThisTestCreated()} 가 이것만 지운다. */
    private final Set<Long> createdRptpIds = new LinkedHashSet<>();
    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    /** 예상 밖 성공 응답에서 id 만 건져 내는 용도. 단언에는 쓰지 않는다. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 조회 기간을 <b>DB 에서</b> 읽어 둔다 (상수 박기 금지 — 클래스 주석 참고).
     *
     * <p>적재분이 비어 있으면 모든 케이스가 엉뚱한 곳에서 죽어 원인이 가려지므로 여기서 먼저
     * 명확한 메시지로 실패시킨다.
     */
    /**
     * 🔴 생성은 {@code creatr_id} 에 실사용자를 적고 스냅샷은 소유자 가드를
     * 거친다. {@code CurrentUserResolver} 가 principal 이 null 이면 401 이라 익명으로는 닿지
     * 않으므로 {@code mock-login} 세션을 쓴다. 익명이 401 인 것은 전용 케이스(13)가 지킨다.
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
    void resolveDataFixtures() {
        String[] range = jdbcTemplate.queryForObject(
                "SELECT to_char(min(ocrn_dt), 'YYYYMMDD'), to_char(max(ocrn_dt), 'YYYYMMDD')"
                        + "  FROM parking.tb_cvlcpt",
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        dataBgngYmd = range[0];
        dataEndYmd = range[1];
        assertThat(dataBgngYmd)
                .as("원천(TB_CVLCPT)이 비어 있다 — 배치 적재분·시드를 먼저 확인하라")
                .matches("\\d{8}");
        assertThat(dataEndYmd).matches("\\d{8}");
        queryBgngYmd = clampToMaxRange(dataBgngYmd, dataEndYmd);
    }

    /**
     * 🔴 <b>이 테스트가 만든 행만</b> 지운다. 순서는 FK 방향 그대로 <b>결과 → 보고서 → 실행</b> 이다 —
     * 뒤집으면 {@code fk_tb_anls_rslt_excn_id} / {@code fk_tb_rptp_excn_id} 위반으로 헬퍼 자체가 터진다.
     */
    @AfterEach
    void deleteOnlyWhatThisTestCreated() {
        // 한 문장이 터져도 나머지를 마저 지운다 — 남은 행이 다음 실행의 행수 단언을 흔들고,
        // 범위 DELETE 로 쓸어 담을 수도 없다.
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
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Object id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    // ──────────────────────────────────────── 1. 정상 생성 — 3표 각 +1 · rslt_json 키 3종

    @Test
    @DisplayName("1. 정상 생성 — 201 · 실행/결과/보고서가 한 벌씩 늘고 rslt_json 에 키 3종이 들어간다")
    void createReportInsertsExactlyOneRowPerTable() {
        long[] before = tableCounts();

        ComplaintReportVO body = createOk(request(fullPeriod(), "PDF"));

        assertThat(body.getRptpId()).isNotNull();
        assertThat(body.getExcnId()).isNotNull();
        assertThat(body.getFileFmt()).isEqualTo("PDF");
        assertThat(body.getRptpNm()).isNotBlank();
        assertThat(body.getRegionNm()).isEqualTo("전체 지역");
        assertThat(body.getAnlsPrdExpln()).isNotBlank();
        assertThat(body.getCrtYmd()).isNotNull();
        assertThat(body.getKpi()).as("핵심 지표가 본체다").isNotNull();

        long[] after = tableCounts();
        assertThat(after[0]).as("TB_ANLS_EXCN 은 1행 는다").isEqualTo(before[0] + 1);
        assertThat(after[1]).as("TB_ANLS_RSLT 는 1행 는다 (결과 전체가 rslt_json 한 칸이다)")
                .isEqualTo(before[1] + 1);
        assertThat(after[2]).as("TB_RPTP 는 1행 는다").isEqualTo(before[2] + 1);

        // 저장된 모양 — 목록 화면의 '직접 생성' 탭·파일 미생성 계약
        Map<String, Object> saved = jdbcTemplate.queryForMap(
                "SELECT crt_type, stts_cd, file_fmt, file_path, stng_id, excn_id"
                        + "  FROM parking.tb_rptp WHERE rptp_id = ?", body.getRptpId());
        assertThat(saved.get("crt_type")).as("MANUAL 이어야 직접 생성 탭에 뜬다").isEqualTo("MANUAL");
        assertThat(saved.get("stts_cd")).isEqualTo("DONE");
        assertThat(saved.get("file_fmt")).isEqualTo("PDF");
        assertThat(saved.get("file_path")).as("파일을 서버에 굽지 않는다").isNull();
        assertThat(saved.get("stng_id")).as("자동 생성 설정과 무관하다").isNull();
        assertThat(saved.get("excn_id")).isEqualTo(body.getExcnId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT anls_type_cd FROM parking.tb_anls_excn WHERE excn_id = ?",
                String.class, body.getExcnId()))
                .as("이 값이 목록의 출처 칸을 '민원현황' 으로 만들고 스냅샷 404-② 판정의 근거가 된다")
                .isEqualTo("CVLCPT");

        // 🔴 저장 모양 — object 로 감싼 {"kpi":…,"emdCompare":[…],"negEmdCompare":[…]}
        Map<String, Object> shape = rsltJsonShape(body.getExcnId());
        assertThat(shape.get("t")).as("배열이 아니라 객체로 감싼다").isEqualTo("object");
        assertThat(shape.get("has_kpi")).as("kpi 키").isEqualTo(Boolean.TRUE);
        assertThat(shape.get("has_emd")).as("emdCompare 키").isEqualTo(Boolean.TRUE);
        assertThat(shape.get("has_neg")).as("negEmdCompare 키").isEqualTo(Boolean.TRUE);
    }

    // ──────────────────────────────────────── 2. 같은 조건 2회 = 독립 보고서 2건

    @Test
    @DisplayName("2. 같은 조건으로 PDF·DOCX 두 번 — 보고서 2건 · 실행 2건 (멱등이 아니다)")
    void sameConditionTwiceCreatesTwoIndependentReports() {
        long[] before = tableCounts();

        ComplaintReportVO pdf = createOk(request(fullPeriod(), "PDF"));
        ComplaintReportVO docx = createOk(request(fullPeriod(), "DOCX"));

        assertThat(pdf.getRptpId()).as("두 번째 호출이 첫 보고서를 덮어쓰지 않는다")
                .isNotEqualTo(docx.getRptpId());
        assertThat(pdf.getExcnId()).as("실행 이력도 각각 남는다").isNotEqualTo(docx.getExcnId());
        assertThat(pdf.getFileFmt()).isEqualTo("PDF");
        assertThat(docx.getFileFmt()).isEqualTo("DOCX");

        // 같은 조건이므로 보고서명·지역명·기간표기는 같다 — 다른 것은 파일형식과 아이디뿐이다
        assertThat(docx.getRptpNm()).isEqualTo(pdf.getRptpNm());
        assertThat(docx.getRegionNm()).isEqualTo(pdf.getRegionNm());
        assertThat(docx.getAnlsPrdExpln()).isEqualTo(pdf.getAnlsPrdExpln());

        long[] after = tableCounts();
        assertThat(after[0]).as("실행 2행").isEqualTo(before[0] + 2);
        assertThat(after[1]).as("결과 2행").isEqualTo(before[1] + 2);
        assertThat(after[2]).as("보고서 2행").isEqualTo(before[2] + 2);
    }

    // ──────────────────────────────────────── 3. 🔴 0건 구간도 201 (집중구역과 다르다)

    @Test
    @DisplayName("3. 🔴 민원 0건 구간 — 400 이 아니라 201 이고 세 표가 한 벌씩 는다")
    void emptyPeriodStillCreatesReport() {
        String[] empty = emptyPeriod();
        long[] before = tableCounts();

        ComplaintReportVO body = createOk(request(period(empty[0], empty[1]), "PDF"));

        assertThat(body.getKpi().totalCount())
                .as("0건은 유효한 값이다 — '이 기간에 민원이 0건이었다' 도 보고할 값이다")
                .isZero();
        assertThat(body.getEmdCompare()).as("배열 2개는 빈 채로 저장된다").isEmpty();
        assertThat(body.getNegEmdCompare()).isEmpty();

        long[] after = tableCounts();
        assertThat(after[0]).as("실행 1행").isEqualTo(before[0] + 1);
        assertThat(after[1]).as("결과 1행").isEqualTo(before[1] + 1);
        assertThat(after[2]).as("보고서 1행 — 주기별 자동 생성이 붙어도 목록에 구멍이 나지 않는다")
                .isEqualTo(before[2] + 1);

        // 🔴 "빈 배열" 과 "키 없음" 은 다르다 — 키를 빠뜨리면 스냅샷 파서가 undefined 를 만난다
        Map<String, Object> shape = rsltJsonShape(body.getExcnId());
        assertThat(shape.get("has_kpi")).as("0건이어도 kpi 키가 있다").isEqualTo(Boolean.TRUE);
        assertThat(shape.get("has_emd")).as("0건이어도 emdCompare 키가 있다").isEqualTo(Boolean.TRUE);
        assertThat(shape.get("has_neg")).as("0건이어도 negEmdCompare 키가 있다").isEqualTo(Boolean.TRUE);
        assertThat(((Number) shape.get("emd_len")).intValue()).as("빈 배열로 저장된다").isZero();
        assertThat(((Number) shape.get("neg_len")).intValue()).isZero();
    }

    // ──────────────────────────────────────── 4. 🔴 0건 보고서 스냅샷은 200 (404 가 아니다)

    @Test
    @DisplayName("4. 🔴 0건 보고서 스냅샷 — 404 가 아니라 200 이고 emdCompare 가 빈 배열로 온다")
    void emptySnapshotIsReadableNot404() {
        String[] empty = emptyPeriod();
        ComplaintReportVO created = createOk(request(period(empty[0], empty[1]), "PDF"));

        ComplaintReportVO snapshot = snapshotOk(created.getRptpId());

        assertThat(snapshot.getKpi()).as("kpi 는 0건이어도 반드시 있다").isNotNull();
        assertThat(snapshot.getKpi().totalCount()).isZero();
        assertThat(snapshot.getEmdCompare())
                .as("집중구역의 '0행 → 404' 를 옮기면 0건 보고서가 목록에만 남고 영영 열리지 않는다")
                .isEmpty();
        assertThat(snapshot.getNegEmdCompare()).isEmpty();
        assertThat(snapshot.getRptpNm()).isEqualTo(created.getRptpNm());
    }

    // ──────────────────────────────────────── 5. fileFmt 화이트리스트

    @Test
    @DisplayName("5. fileFmt 가 PDF|DOCX 밖 — 400 (XLSX·소문자·빈값·누락 모두) · 아무것도 저장하지 않는다")
    void fileFmtOutsideWhitelistReturns400() {
        long[] before = tableCounts();

        assertThat(postStatus(request(fullPeriod(), "XLSX"))).as("미지원 형식 → @Pattern").isEqualTo(400);
        assertThat(postStatus(request(fullPeriod(), "HWP"))).as("미지원 형식 → @Pattern").isEqualTo(400);
        assertThat(postStatus(request(fullPeriod(), "pdf")))
                .as("소문자 → @Pattern (^(PDF|DOCX)$)").isEqualTo(400);
        assertThat(postStatus(request(fullPeriod(), ""))).as("빈 값 → @NotBlank").isEqualTo(400);
        assertThat(postStatus(request(fullPeriod(), null)))
                .as("키 자체를 생략 → @NotBlank (조용히 PDF 로 떨어뜨리지 않는다)").isEqualTo(400);

        assertThat(tableCounts()).as("검증 실패는 아무것도 저장하지 않는다")
                .containsExactly(before[0], before[1], before[2]);
    }

    // ──────────────────────────────────────── 6. 없는 시군구코드 → 400 (FK 위반 500 이 아니다)

    @Test
    @DisplayName("6. 없는 시군구코드(99999) — 400 이고 세 표 어디에도 행이 늘지 않는다")
    void unknownSggCdReturns400AndWritesNothing() {
        assertThat(admdstCountBySggCd(UNKNOWN_SGG_CD))
                .as("시군구코드 %s 가 TB_ADMDST 에 없어야 이 케이스가 성립한다", UNKNOWN_SGG_CD)
                .isZero();

        long[] before = tableCounts();

        Map<String, Object> req = request(fullPeriod(), "PDF");
        req.put("sggCd", UNKNOWN_SGG_CD);
        assertThat(postStatus(req))
                .as("없는 코드는 데이터가 아니라 잘못된 요청이다 — 저장 앞에서 끊는다 (예외)")
                .isEqualTo(400);

        assertThat(tableCounts()).as("실행 이력만 남는 반쪽 상태를 만들지 않는다")
                .containsExactly(before[0], before[1], before[2]);
    }

    // ──────────────────────────────────────── 7. 🔴 없는 읍면동코드 → 400 (FK 위반 500 이 아니다)

    @Test
    @DisplayName("7. 🔴 없는 읍면동코드(99999999) — 400 이다 (검증이 빠지면 TB_ANLS_EXCN.emd_cd FK 위반 500)")
    void unknownEmdCdReturns400AndWritesNothing() {
        assertThat(admdstCountByEmdCd(UNKNOWN_EMD_CD))
                .as("읍면동코드 %s 가 TB_ADMDST 에 없어야 이 케이스가 성립한다", UNKNOWN_EMD_CD)
                .isZero();

        long[] before = tableCounts();

        Map<String, Object> req = request(fullPeriod(), "PDF");
        req.put("emdCd", UNKNOWN_EMD_CD);
        assertThat(postStatus(req))
                .as("TB_ANLS_EXCN.emd_cd 는 TB_ADMDST FK 다 — 검증이 빠지면 400 이 아니라 500 이 된다")
                .isEqualTo(400);

        assertThat(tableCounts()).containsExactly(before[0], before[1], before[2]);
    }

    // ──────────────────────────────────────── 8. 🔴 잘못된 기간 → 400 (때문에 안 막으면 201 로 쌓인다)

    @Test
    @DisplayName("8. 🔴 실재하지 않는 날짜(20261345) · 역순 구간 — 둘 다 400 이고 아무것도 저장하지 않는다")
    void invalidPeriodReturns400() {
        long[] before = tableCounts();

        // ① 형식은 \d{8} 을 통과하지만 실재하지 않는 날짜 — @Pattern 만으론 통과해 500 이 된다
        assertThat(postStatus(request(period("20261345", dataEndYmd), "PDF")))
                .as("13월 45일 — STRICT 파싱이 없으면 SQL 로 흘러가 500 이다")
                .isEqualTo(400);
        assertThat(postStatus(request(period(queryBgngYmd, "20260230"), "PDF")))
                .as("2월 30일 — 종료일도 같은 규칙이다")
                .isEqualTo(400);

        // ② 역순 — 0건도 201 로 만들기 때문에, 안 막으면 역순 보고서가 조용히 쌓인다
        assertThat(postStatus(request(period(dataEndYmd, queryBgngYmd), "PDF")))
                .as("종료일이 시작일보다 빠르다 (DateRanges.validate)")
                .isEqualTo(400);

        assertThat(tableCounts()).as("잘못된 요청은 아무것도 남기지 않는다")
                .containsExactly(before[0], before[1], before[2]);
    }

    // ──────────────────────────────────────── 9. 스냅샷 왕복 — 10칸 전부

    @Test
    @DisplayName("9. 스냅샷 왕복 — GET 이 POST 201 과 같은 10칸을 그대로 돌려준다")
    void snapshotRoundTripKeepsPersistedValues() {
        Map<String, Object> req = request(fullPeriod(), "DOCX");
        req.put("sggCd", SGG_CD_JEJU);
        ComplaintReportVO created = createOk(req);

        ComplaintReportVO snapshot = snapshotOk(created.getRptpId());

        // 헤더 7칸 — POST 는 ComplaintReportNaming, GET 은 selectReportById 의 CASE 라 두 경로가 갈릴 수 있다
        assertThat(snapshot.getRptpId()).isEqualTo(created.getRptpId());
        assertThat(snapshot.getExcnId()).isEqualTo(created.getExcnId());
        assertThat(snapshot.getRptpNm()).isEqualTo(created.getRptpNm());
        assertThat(snapshot.getRegionNm()).isEqualTo(created.getRegionNm());
        assertThat(snapshot.getAnlsPrdExpln()).isEqualTo(created.getAnlsPrdExpln());
        assertThat(snapshot.getFileFmt())
                .as("출처가 selectReportById 뿐이라 빠지면 이 칸만 null 이 되어 화면이 PDF/DOCX 를 못 가린다")
                .isEqualTo("DOCX");
        // crt_ymd 는 timestamp(마이크로초)라 JVM 이 준 나노초와 자릿수가 다르다 — 초 단위로 맞춘다
        assertThat(snapshot.getCrtYmd()).isNotNull();
        assertThat(snapshot.getCrtYmd().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(created.getCrtYmd().truncatedTo(ChronoUnit.SECONDS));

        // 본문 3칸 — rslt_json 의 키가 조회 응답 필드명 그대로라 변환이 없다.
        // record 라 equals 가 값 비교이므로 필드 하나만 빠져도 잡힌다.
        assertThat(snapshot.getKpi()).as("kpi 7칸").isEqualTo(created.getKpi());
        assertThat(snapshot.getEmdCompare()).as("emdCompare 전체").isEqualTo(created.getEmdCompare());
        assertThat(snapshot.getNegEmdCompare()).as("negEmdCompare 전체")
                .isEqualTo(created.getNegEmdCompare());
        assertThat(snapshot.getEmdCompare())
                .as("보유 구간 전체를 제주시로 조회했는데 지역 카드가 하나도 없다 — 적재분을 확인하라")
                .isNotEmpty();
    }

    // ──────────────────────────────────────── 10. 유형 불일치 스냅샷 → 404

    @Test
    @DisplayName("10. 민원현황이 아닌 보고서(HOTSPOT · 결과행 있음)의 민원 스냅샷 조회 — 404")
    void snapshotOfNonComplaintReportReturns404() {
        // 🔴 판정 근거를 섞지 않으려고 '결과행이 있는' HOTSPOT 보고서를 직접 심는다.
        //    시드의 rptp_id=14(AUTO·HOTSPOT)는 tb_anls_rslt 가 0행이라 "유형 불일치" 와
        //    "결과 미저장" 이 둘 다 성립해 무엇 때문에 404 인지 알 수 없다.
        long rptpId = insertHotspotReportWithResult();

        assertThat(anlsTypeCdOfReport(rptpId)).isEqualTo("HOTSPOT");
        assertThat(rsltJsonPresentOfReport(rptpId))
                .as("결과행이 있어야 404 사유가 '유형 불일치' 하나로 좁혀진다").isTrue();

        assertThat(snapshotStatus(rptpId))
                .as("이 경로로 열 수 있는 것은 민원현황(CVLCPT)뿐이다 (404-②)")
                .isEqualTo(404);
    }

    // ──────────────────────────────────────── 11. 지역 3형태

    @Test
    @DisplayName("11. 지역 3형태 — '전체 지역' / '제주시 전체' / '제주시 연동' 으로 저장·복원된다")
    void regionNameHasThreeShapes() {
        // ① 전체 지역 — 지역 키를 아예 넣지 않는다
        ComplaintReportVO all = createOk(request(fullPeriod(), "PDF"));
        assertThat(all.getRegionNm()).isEqualTo("전체 지역");
        assertThat(all.getRptpNm()).isEqualTo("민원 현황 보고서 전체 지역");

        // ② 시군구 전체 — sggCd(코드)를 보내면 서버가 TB_ADMDST 로 이름을 찾는다
        Map<String, Object> sggReq = request(fullPeriod(), "PDF");
        sggReq.put("sggCd", SGG_CD_JEJU);
        ComplaintReportVO sgg = createOk(sggReq);
        assertThat(sgg.getRegionNm()).isEqualTo(SGG_NM_JEJU + " 전체");
        assertThat(sgg.getRptpNm()).isEqualTo("민원 현황 보고서 " + SGG_NM_JEJU + " 전체");

        // ③ 시군구 + 읍면동 — emdCd 만 왔어도 서버가 sgg_nm 을 함께 읽어 채운다
        Map<String, Object> emdReq = request(fullPeriod(), "PDF");
        emdReq.put("emdCd", EMD_CD_YEONDONG);
        ComplaintReportVO emd = createOk(emdReq);
        String expected = SGG_NM_JEJU + " " + emdNmOf(EMD_CD_YEONDONG);
        assertThat(emd.getRegionNm())
                .as("emdCd 만 온 요청에도 시군구가 채워져야 한다 (아니면 지역명이 통째로 NULL 이 된다)")
                .isEqualTo(expected);
        assertThat(emd.getRptpNm()).isEqualTo("민원 현황 보고서 " + expected);

        // 🔴 저장 경로(POST 조립)와 조회 경로(selectReportById 의 3단 CASE)가 같은 문자열을 내야 한다
        assertThat(snapshotOk(all.getRptpId()).getRegionNm()).isEqualTo(all.getRegionNm());
        assertThat(snapshotOk(sgg.getRptpId()).getRegionNm()).isEqualTo(sgg.getRegionNm());
        assertThat(snapshotOk(emd.getRptpId()).getRegionNm()).isEqualTo(emd.getRegionNm());

        // 지역 조건은 실행 이력에도 그대로 남는다 (재현 가능성) — 전 지역은 두 칸 모두 null 이다
        Map<String, Object> allExcn = jdbcTemplate.queryForMap(
                "SELECT sgg_nm, emd_cd FROM parking.tb_anls_excn WHERE excn_id = ?", all.getExcnId());
        assertThat(allExcn.get("sgg_nm")).as("전 지역은 시군구가 null 이다").isNull();
        assertThat(allExcn.get("emd_cd")).as("전 지역은 읍면동이 null 이다").isNull();

        Map<String, Object> emdExcn = jdbcTemplate.queryForMap(
                "SELECT sgg_nm, emd_cd FROM parking.tb_anls_excn WHERE excn_id = ?", emd.getExcnId());
        assertThat(emdExcn.get("sgg_nm"))
                .as("emdCd 만 온 요청이어도 실행 이력엔 시군구가 채워진다").isEqualTo(SGG_NM_JEJU);
        assertThat(emdExcn.get("emd_cd")).isEqualTo(EMD_CD_YEONDONG);
    }

    // ──────────────────────────────────────── 13. 익명은 401

    @Test
    @DisplayName("13. 🔴 익명 HTTP 는 생성·스냅샷 모두 401 이고 세 표가 한 행도 늘지 않는다")
    void anonymousIs401AndInsertsNothing() {
        // 바디는 유효하다 — 같은 바디를 세션으로 보내면 201 이다(= 401 의 원인이 바디가 아니다)
        Long rptpId = createOk(request(fullPeriod(), "PDF")).getRptpId();
        long[] before = tableCounts();

        assertThat(restTemplate.postForEntity(CREATE_URL, request(fullPeriod(), "PDF"), String.class)
                .getStatusCode().value()).as("익명 생성").isEqualTo(401);
        assertThat(restTemplate.getForEntity(SNAPSHOT_URL, String.class, rptpId)
                .getStatusCode().value()).as("익명 스냅샷 — 내 보고서라도 세션이 없으면 401").isEqualTo(401);

        assertThat(tableCounts()).as("401 이면 아무것도 저장하지 않는다")
                .containsExactly(before[0], before[1], before[2]);
    }

    // ──────────────────────────────────────── 12. "오늘" 의 출처

    @Test
    @DisplayName("12. unit=MONTH 의 적용 구간이 서버 실제 날짜(Asia/Seoul)를 따른다")
    void todayComesFromServerClock() {
        // ⚠️ 이 케이스가 부르는 것은 보고서 생성이 아니라 조회 API 다 (소관).
        // 자정 경계 대비 — 호출 전후 날짜를 둘 다 허용한다.
        LocalDate before = LocalDate.now(SEOUL);
        ResponseEntity<ComplaintKpiVO> response =
                restTemplate.getForEntity(GIS_URL + "?unit=MONTH", ComplaintKpiVO.class);
        LocalDate after = LocalDate.now(SEOUL);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ComplaintKpiVO body = response.getBody();
        assertThat(body).isNotNull();

        assertThat(body.endYmd())
                .as("종료일 = LocalDate.now(Asia/Seoul) 의 어제")
                .isIn(before.minusDays(1).format(YMD), after.minusDays(1).format(YMD));
        assertThat(body.bgngYmd())
                .as("MONTH = 어제까지 30일 [오늘-30, 오늘-1] (ComplaintUnit)")
                .isIn(before.minusDays(30).format(YMD), after.minusDays(30).format(YMD));
    }

    // ──────────────────────────────────────── 전기 기간 · 전기 민원 건수

    @Test
    @DisplayName(". 전기 기간은 분석 기간 바로 앞의 같은 일수 구간이다")
    void prevPeriodIsSameLengthRightBeforeRange() {
        LocalDate bgng = LocalDate.parse(dataEndYmd, YMD).minusDays(9);
        LocalDate end = LocalDate.parse(dataEndYmd, YMD);

        ComplaintReportVO body = createOk(request(period(bgng.format(YMD), end.format(YMD)), "PDF"));

        DateTimeFormatter dotted = DateTimeFormatter.ofPattern("uuuu.MM.dd");
        assertThat(body.getKpi().prevAnlsPrdExpln())
                .as("10일 구간의 전기는 시작일 앞 10일이다")
                .isEqualTo(bgng.minusDays(10).format(dotted) + " ~ " + bgng.minusDays(1).format(dotted));
    }

    @Test
    @DisplayName(". 전기 민원 건수는 같은 필터의 직접 집계와 같다 — 전체 지역")
    void prevTotalCountMatchesDirectCount() {
        LocalDate[] prev = prevOfLast30Days();
        LocalDate end = LocalDate.parse(dataEndYmd, YMD);

        ComplaintReportVO body = createOk(request(
                period(end.minusDays(29).format(YMD), end.format(YMD)), "PDF"));

        assertThat(body.getKpi().prevTotalCount()).isEqualTo(countCvlcpt(prev[0], prev[1], null));
    }

    @Test
    @DisplayName(". 전기 민원 건수에도 지역 필터가 걸린다")
    void prevTotalCountRespectsRegionFilter() {
        LocalDate[] prev = prevOfLast30Days();
        LocalDate end = LocalDate.parse(dataEndYmd, YMD);
        Map<String, Object> req = request(period(end.minusDays(29).format(YMD), end.format(YMD)), "PDF");
        req.put("sggCd", SGG_CD_JEJU);

        ComplaintReportVO body = createOk(req);

        long expected = countCvlcpt(prev[0], prev[1], SGG_CD_JEJU);
        assertThat(expected).as("전체 지역과 달라야 필터를 검증한 것이다")
                .isLessThan(countCvlcpt(prev[0], prev[1], null));
        assertThat(body.getKpi().prevTotalCount()).isEqualTo(expected);
    }

    @Test
    @DisplayName(". 🔴 전기가 0건이어도 null 이 아니라 0 이다")
    void prevTotalCountIsZeroNotNullWhenEmpty() {
        String[] empty = emptyPeriod();

        ComplaintReportVO body = createOk(request(period(empty[0], empty[1]), "PDF"));

        assertThat(body.getKpi().prevTotalCount()).as("null 이면 문서가 '값 없음' 으로 그린다").isNotNull();
        assertThat(body.getKpi().prevAnlsPrdExpln()).isNotBlank();
    }

    @Test
    @DisplayName(". 동별 전기 건수는 같은 동의 직접 집계와 같다")
    void emdPrevCntMatchesDirectCount() {
        LocalDate[] prev = prevOfLast30Days();
        LocalDate end = LocalDate.parse(dataEndYmd, YMD);

        ComplaintReportVO body = createOk(request(
                period(end.minusDays(29).format(YMD), end.format(YMD)), "PDF"));

        assertThat(body.getEmdCompare()).as("최근 30일에 민원이 있어야 검증이 된다").isNotEmpty();
        var first = body.getEmdCompare().get(0);
        Long expected = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt"
                        + " WHERE ocrn_dt::date BETWEEN ?::date AND ?::date"
                        + "   AND cvlcpt_type_cd <> '99' AND emd_cd = ?",
                Long.class, prev[0].toString(), prev[1].toString(), first.emdCd());
        assertThat(first.prevCnt()).isEqualTo(expected);
    }

    @Test
    @DisplayName(". 🔴 구 스냅샷 — 전기 키가 없어도 200 이고 값은 0 이 아니라 null 이다")
    void oldSnapshotWithoutPrevKeysReadsAsNull() {
        ComplaintReportVO created = createOk(request(fullPeriod(), "PDF"));
        assertThat(created.getEmdCompare()).isNotEmpty();
        jdbcTemplate.update(
                "UPDATE parking.tb_anls_rslt"
                        + "   SET rslt_json = jsonb_set(rslt_json #- '{kpi,prevTotalCount}' #- '{kpi,prevAnlsPrdExpln}',"
                        + "       '{emdCompare}',"
                        + "       (SELECT jsonb_agg(e - 'prevCnt') FROM jsonb_array_elements(rslt_json->'emdCompare') e))"
                        + " WHERE excn_id = ?",
                created.getExcnId());

        ComplaintReportVO snapshot = snapshotOk(created.getRptpId());

        assertThat(snapshot.getKpi().prevTotalCount()).as("0 이면 거짓 '0건' 이 찍힌다").isNull();
        assertThat(snapshot.getKpi().prevAnlsPrdExpln()).isNull();
        assertThat(snapshot.getKpi().totalCount()).isEqualTo(created.getKpi().totalCount());
        assertThat(snapshot.getEmdCompare()).isNotEmpty()
                .allSatisfy(row -> assertThat(row.prevCnt()).isNull());
    }

    /** 보유 구간 끝 30일의 전기 [시작, 종료]. 시드가 날짜째로 재적재돼도 깨지지 않게 DB 값에서 계산한다. */
    private LocalDate[] prevOfLast30Days() {
        LocalDate bgng = LocalDate.parse(dataEndYmd, YMD).minusDays(29);
        return new LocalDate[] { bgng.minusDays(30), bgng.minusDays(1) };
    }

    /** {@code ComplaintMapper.xml} 의 공통 필터와 같은 조건의 직접 집계. */
    private long countCvlcpt(LocalDate from, LocalDate to, String sggCd) {
        String sql = "SELECT count(*) FROM parking.tb_cvlcpt"
                + " WHERE ocrn_dt::date BETWEEN ?::date AND ?::date"
                + "   AND cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL";
        Long count = sggCd == null
                ? jdbcTemplate.queryForObject(sql, Long.class, from.toString(), to.toString())
                : jdbcTemplate.queryForObject(sql + " AND LEFT(emd_cd, 5) = ?",
                        Long.class, from.toString(), to.toString(), sggCd);
        return count == null ? 0 : count;
    }

    // ────────────────────────────────────────────────────────── 헬퍼 — 호출

    /**
     * 생성 성공을 기대하는 호출. 🔴 <b>응답을 받자마자 id 를 등록</b>한다 — 뒤 단언이 실패해도
     * {@link #deleteOnlyWhatThisTestCreated()} 가 그 행을 지울 수 있어야 한다.
     */
    private ComplaintReportVO createOk(Map<String, Object> body) {
        ResponseEntity<ComplaintReportVO> response =
                restTemplate.exchange(CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body),
                        ComplaintReportVO.class);
        ComplaintReportVO vo = response.getBody();
        track(vo);
        assertThat(response.getStatusCode().value())
                .as("생성 응답은 201 이다 (요청: %s)", body).isEqualTo(201);
        assertThat(vo).isNotNull();
        return vo;
    }

    /**
     * 실패가 예상되는 생성 호출 — 에러 바디를 VO 로 역직렬화하려다 터지는 것을 막으려 String 으로 받는다.
     *
     * <p>🔴 <b>200번대면 그 응답의 id 도 추적한다.</b> 구현이 깨져 실제로 생성되면 단언은 실패하고
     * 행은 남는데, 그 행은 어느 정리에도 걸리지 않아 다음 실행까지 오염된다.
     */
    private int postStatus(Map<String, Object> body) {
        ResponseEntity<String> response = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body), String.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            trackFromJson(response.getBody());
        }
        return response.getStatusCode().value();
    }

    private void trackFromJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.hasNonNull("rptpId")) {
                createdRptpIds.add(node.get("rptpId").asLong());
            }
            if (node.hasNonNull("excnId")) {
                createdExcnIds.add(node.get("excnId").asLong());
            }
        } catch (JsonProcessingException e) {   // 4.3 — readTree 가 던지는 파싱 예외만
            // 바디가 보고서 JSON 이 아니면 지울 것도 없다.
            org.slf4j.LoggerFactory.getLogger(ComplaintReportIntegrationTest.class).debug("응답 JSON 파싱 생략 — {}", e.getClass().getSimpleName());   // 4.2
        }
    }

    private ComplaintReportVO snapshotOk(Long rptpId) {
        ResponseEntity<ComplaintReportVO> response =
                restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session),
                        ComplaintReportVO.class, rptpId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private int snapshotStatus(Object rptpId) {
        return restTemplate.exchange(SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session),
                String.class, rptpId).getStatusCode().value();
    }

    private void track(ComplaintReportVO vo) {
        if (vo == null) {
            return;
        }
        if (vo.getRptpId() != null) {
            createdRptpIds.add(vo.getRptpId());
        }
        if (vo.getExcnId() != null) {
            createdExcnIds.add(vo.getExcnId());
        }
    }

    // ────────────────────────────────────────────────────────── 헬퍼 — 요청 조립

    private static Map<String, Object> period(String bgngYmd, String endYmd) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bgngYmd", bgngYmd);
        params.put("endYmd", endYmd);
        return params;
    }

    /** 원천 보유 구간 전체 — 결과를 보는 케이스는 전부 이것을 쓴다 (상수 박기 금지). */
    private Map<String, Object> fullPeriod() {
        return period(queryBgngYmd, dataEndYmd);
    }

    /**
     * 민원이 <b>정말 0건인</b> 구간을 계산한다 — 리터럴로 박지 않는다.
     * 보유 구간 종료일 + 1년부터 한 달이며, SQL 로 0건임을 먼저 확인한다.
     */
    private String[] emptyPeriod() {
        String bgng = LocalDate.parse(dataEndYmd, YMD).plusYears(1).format(YMD);
        String end = LocalDate.parse(bgng, YMD).plusMonths(1).format(YMD);
        assertThat(complaintRowCount(bgng, end))
                .as("계산한 구간 %s~%s 이 실제로 0건이어야 이 케이스가 성립한다", bgng, end)
                .isZero();
        return new String[] { bgng, end };
    }

    /** {@code fileFmt} 가 {@code null} 이면 <b>키 자체를 넣지 않는다</b> (누락 = @NotBlank 검증 대상). */
    private static Map<String, Object> request(Map<String, Object> period, String fileFmt) {
        if (fileFmt != null) {
            period.put("fileFmt", fileFmt);
        }
        return period;
    }

    /**
     * 366일 상한({@code DateRanges.MAX_RANGE_DAYS})에 맞춰 시작일을 자른다.
     * 365 를 빼면 양끝 포함 366일이다. 구간이 1년 이내면 그대로 돌려준다.
     */
    private static String clampToMaxRange(String bgngYmd, String endYmd) {
        LocalDate bgng = LocalDate.parse(bgngYmd, YMD);
        LocalDate floor = LocalDate.parse(endYmd, YMD).minusDays(MAX_RANGE_DAYS - 1L);
        return bgng.isBefore(floor) ? floor.format(YMD) : bgngYmd;
    }

    // ────────────────────────────────────────────────────────── 헬퍼 — DB

    /** {@code TB_ANLS_EXCN} · {@code TB_ANLS_RSLT} · {@code TB_RPTP} 행 수 (증가분 단언용). */
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

    /** {@code rslt_json} 의 모양 — 타입 · 키 3종 존재 · 배열 2개 길이. */
    private Map<String, Object> rsltJsonShape(Long excnId) {
        return jdbcTemplate.queryForMap(
                "SELECT jsonb_typeof(rslt_json)                      AS t,"
                        + "       jsonb_exists(rslt_json, 'kpi')            AS has_kpi,"
                        + "       jsonb_exists(rslt_json, 'emdCompare')     AS has_emd,"
                        + "       jsonb_exists(rslt_json, 'negEmdCompare')  AS has_neg,"
                        + "       COALESCE(jsonb_array_length(rslt_json->'emdCompare'), -1)    AS emd_len,"
                        + "       COALESCE(jsonb_array_length(rslt_json->'negEmdCompare'), -1) AS neg_len"
                        + "  FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
    }

    /**
     * 구간 안의 민원 행 수. {@code ComplaintMapper} 의 {@code filters} 와 <b>같은 조건</b>이라야
     * "0건인데 카드가 생겼다" 가 나오지 않는다 — 조건이 갈리면 매퍼 XML 과 함께 고친다.
     */
    private long complaintRowCount(String bgngYmd, String endYmd) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_cvlcpt c"
                        + " WHERE c.ocrn_dt::date BETWEEN to_date(?, 'YYYYMMDD') AND to_date(?, 'YYYYMMDD')"
                        + "   AND c.cvlcpt_type_cd <> '99' AND c.emd_cd IS NOT NULL",
                Long.class, bgngYmd, endYmd);
        return count == null ? 0L : count;
    }

    private long admdstCountBySggCd(String sggCd) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_admdst WHERE LEFT(emd_cd, 5) = ?", Long.class, sggCd);
        return count == null ? 0L : count;
    }

    private long admdstCountByEmdCd(String emdCd) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_admdst WHERE emd_cd = ?", Long.class, emdCd);
        return count == null ? 0L : count;
    }

    private String emdNmOf(String emdCd) {
        return jdbcTemplate.queryForObject(
                "SELECT emd_nm FROM parking.tb_admdst WHERE emd_cd = ?", String.class, emdCd);
    }

    private String anlsTypeCdOfReport(long rptpId) {
        return jdbcTemplate.queryForObject(
                "SELECT e.anls_type_cd FROM parking.tb_rptp r"
                        + "  LEFT JOIN parking.tb_anls_excn e ON e.excn_id = r.excn_id"
                        + " WHERE r.rptp_id = ?", String.class, rptpId);
    }

    private boolean rsltJsonPresentOfReport(long rptpId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp r"
                        + "  JOIN parking.tb_anls_rslt a ON a.excn_id = r.excn_id"
                        + " WHERE r.rptp_id = ? AND a.rslt_json IS NOT NULL", Long.class, rptpId);
        return count != null && count > 0;
    }

    /**
     * <b>결과행이 있는</b> MANUAL HOTSPOT 보고서 한 벌을 심는다 (케이스 10 전용).
     *
     * <p>🔴 시드의 {@code rptp_id=14}(AUTO·HOTSPOT)를 쓰지 않는 이유 — 그 행은 {@code TB_ANLS_RSLT}
     * 가 0행이라 <b>"유형 불일치"(404-②)와 "결과 미저장"(404-③)이 둘 다 성립</b>해 무엇 때문에 404
     * 인지 판정 근거가 뒤섞인다. 그리고 로컬마다 조건에 맞는 id 가 있다는 보장이 없다 —
     * 환경에 기대지 않고 테스트가 직접 만든다({@code HotspotReportIntegrationTest} 의
     * {@code insertLegacyHotspotReport} 와 같은 수법).
     *
     * <p>집중구역 생성 API 를 부르지 않는 것도 의도다 — DBSCAN 결과가 0건이면 그쪽이 400 이라
     * (그 도메인의) 이 케이스가 적재분에 따라 흔들린다.
     */
    private long insertHotspotReportWithResult() {
        String rsltJson = "{\"zones\":[{\"rnk\":1,\"zoneNm\":\"유형불일치 판정용 구역\",\"cvlcptCnt\":10,"
                + "\"crdnCnt\":5,\"snthsScr\":8.00,\"mrkLat\":33.4996,\"mrkLot\":126.5312}]}";

        Long excnId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_anls_excn"
                        + " (anls_type_cd, excutr_id, excn_stts_cd, excn_dt, anls_param_json)"
                        + " VALUES ('HOTSPOT', 'system', 'DONE', now(), '{}'::jsonb) RETURNING excn_id",
                Long.class);
        assertThat(excnId).isNotNull();
        createdExcnIds.add(excnId);

        jdbcTemplate.update(
                "INSERT INTO parking.tb_anls_rslt (excn_id, rslt_json) VALUES (?, ?::jsonb)",
                excnId, rsltJson);

        Long rptpId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_rptp"
                        + " (crt_type, excn_id, rptp_nm, anls_prd_expln, stts_cd, file_fmt,"
                        + "  creatr_id, creatr_nm, crt_ymd)"
                        + " VALUES ('MANUAL', ?, '유형 불일치 판정용 집중구역 보고서', '테스트 기간', 'DONE', 'PDF',"
                        + "         ?, '관리자', now()) RETURNING rptp_id",   // 🔴 소유자 가드 통과
                Long.class, excnId, IntegrationLoginSupport.MOCK_ID);
        assertThat(rptpId).isNotNull();
        createdRptpIds.add(rptpId);
        return rptpId;
    }
}
