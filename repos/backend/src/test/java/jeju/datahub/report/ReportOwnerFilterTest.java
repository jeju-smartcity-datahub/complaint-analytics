package jeju.datahub.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.common.PageResponse;
import jeju.datahub.complaint.service.ComplaintReportCreateRequest;
import jeju.datahub.complaint.service.ComplaintReportService;
import jeju.datahub.complaint.service.ComplaintReportVO;
import jeju.datahub.report.service.ReportListFilter;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.ReportOwnershipGuard;
import jeju.datahub.report.service.ReportService;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 보고서 <b>소유자 필터</b> 통합 테스트. (~8 — 요청 ①②③④)
 *
 * <h2>무엇을 고정하는가</h2>
 * <ul>
 *   <li><b>{@code MANUAL}·{@code AI} 는 내 것만</b>(요청 ①) — 목록·총건수·단건·스냅샷 전부</li>
 *   <li><b>{@code AUTO} 는 배포 부서 · {@code ALL} · 관리자만</b>(종전 요청 ② 폐기) —
 *       생성자가 아니라 보고서 스냅샷 {@code crltn_dept_cd} 와 보는 사람의 {@code dept_cd}·보고서 관리 메뉴
 *       ({@code M0402}) 보유로 판정한다. 기대 결과표 4명 × 3행을 목록·단건 가드로 전부 단언한다(케이스 2·2-1)</li>
 *   <li><b>생성자는 세션 사용자</b>(요청 ③④) — {@code creatr_id}·{@code creatr_nm}·{@code excutr_id}</li>
 *   <li><b>미인증은 폴백이 아니라 401</b> — 목록도 생성도. 그리고 {@code ownerId=null} 로
 *       서비스를 직접 불러도 <b>0건</b>이다({@code 1=0} 분기 — "전체 공개" 로 열리지 않는다)</li>
 * </ul>
 *
 * <h2>🔴 두 사용자를 어떻게 만드는가</h2>
 * 이 레포엔 MockMvc 가 0건이라 {@code @WithMockUser} 를 쓸 수 없다. 그래서
 * <ul>
 *   <li><b>u1 = {@code mock-user}</b> — {@code mock-login} 으로 <b>실제 세션</b>을 얻는다
 *       ({@link IntegrationLoginSupport}). HTTP 로 "내 것만 보인다" 를 끝까지 태울 수 있다.</li>
 *   <li><b>u2 = {@value #U2}</b> — 세션을 만들 수 없는 <b>남</b>이다. 행을 JDBC 로 심어
 *       "u1 에게는 404" 를 본다. 남의 세션이 필요한 단언은 없다(가드는 {@code ownerId} 한 칸으로 판정한다).</li>
 * </ul>
 *
 * <h2>🔴 매트릭스 — 사용자 4명은 서비스·가드를 직접 부른다</h2>
 * HTTP 세션은 {@code mock-user} 하나뿐이라, 기대 결과표의 4명({@code ROLE_USER}+부서 A ·
 * {@code ROLE_USER}+부서 NULL · SERVICE · SYSTEM)은 {@code TB_USER} 행만 심고
 * {@code reportService.selectReports} · {@code reportOwnershipGuard.check} 를 직접 부른다.
 * HTTP 경로는 {@code mock-user} 의 부서를 A 로 {@code UPDATE} 해 한 번만 태운다(케이스 3-1).
 * <ul>
 *   <li>🔴 {@code mockSessionWithMenus("M0402")} 를 쓰지 않는다 — {@code ROLE_USER} 전체에 보고서 관리 메뉴를 주면
 *       {@code ROLE_USER} 픽스처가 전부 관리자로 판정돼 ❌ 칸이 ✅ 가 된다.</li>
 *   <li>🔴 관리자 판정은 실 {@code tb_authrt_menu} 의 {@code M0402} 매핑을 그대로 쓴다 — 권한 표를 고치지 않는다.
 *       전제가 바뀌면 {@link #assumeAdminRolesHoldM0402()} 가 먼저 알린다.</li>
 *   <li>🔴 {@code tb_user.dept_cd} 에 FK 가 있다 — 부서 A·B 를 먼저 넣고, 정리는 사용자 → 부서 순이다.</li>
 * </ul>
 *
 * <h2>🔴 기대 건수를 상수로 박지 않는다</h2>
 * {@code tb_rptp} 는 시드·배치·사람·다른 테스트가 함께 소유한다. 총건수 단언은 모두
 * <b>같은 조건의 독립 SQL</b>({@link #countReports})과 비교하고, 목록 단언은 이 테스트가 심은
 * {@code rptpId} 로만 한다.
 *
 * <h2>🔴 {@code RANDOM_PORT} 라 롤백이 안 먹는다 — 심은 행은 손으로 지운다</h2>
 * {@link #cleanUp()} 이 <b>이 테스트가 모아 둔 id 만</b> FK 방향(보고서 → 결과 → 실행)으로 지운다.
 * 범위 DELETE 를 쓰지 않는다 — 시드 보고서 26행과 사람이 만든 행까지 날아간다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("보고서 소유자 필터 · AUTO 열람 범위 · 생성자 기록 (케이스 1~8)")
class ReportOwnerFilterTest {

    private static final String REPORTS_URL = "/api/reports";
    private static final String HOTSPOT_SNAPSHOT_URL = "/api/reports/{rptpId}/hotspot";
    private static final String COMPLAINT_CREATE_URL = "/api/complaints/reports";

    /** 남의 {@code user_id} — 세션을 만들 수 없는 쪽이다. {@code varchar(20)} 안에 들어간다. */
    private static final String U2 = "owner-u2";

    /** 설계 결정이 "아무에게도 보이지 않는다" 고 못박은 과거 행의 생성자. */
    private static final String LEGACY = "system";

    // ── 픽스처 — 접두 t4257-own- 로 시드·다른 테스트와 겹치지 않는다 (user_id ≤20 · dept_cd ≤30)

    /** 부서 A — "내 부서" */
    private static final String DEPT_A = "t4257-own-a";
    /** 부서 B — "남의 부서" */
    private static final String DEPT_B = "t4257-own-b";
    /** 배포 부서 "전체" — {@code tb_rptp.crltn_dept_cd} 에는 FK 가 없어 부서 행이 없어도 된다 */
    private static final String DEPT_ALL = "ALL";

    /** {@code ROLE_USER} + 부서 A */
    private static final String USER_A = "t4257-own-ua";
    /** {@code ROLE_USER} + 부서 NULL */
    private static final String USER_NULL = "t4257-own-un";
    /** {@code ROLE_SERVICE_ADMIN} + 부서 B — 부서와 무관하게 전부 본다 */
    private static final String USER_SVC = "t4257-own-svc";
    /** {@code ROLE_SYSTEM_ADMIN} + 부서 NULL — 부서와 무관하게 전부 본다 */
    private static final String USER_SYS = "t4257-own-sys";

    /** 집중구역 스냅샷이 열리는 최소 결과 — 케이스 4 의 대조군·실험군이 같은 모양이어야 한다. */
    private static final String HOTSPOT_RSLT_JSON =
            "{\"zones\":[{\"rnk\":1,\"zoneNm\":\"소유자 판정용 구역\",\"cvlcptCnt\":10,\"crdnCnt\":5,"
                    + "\"snthsScr\":8.00,\"mrkLat\":33.4996,\"mrkLot\":126.5312}]}";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ComplaintReportService complaintReportService;

    @Autowired
    private ReportOwnershipGuard reportOwnershipGuard;

    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    /** u1 = 세션 사용자의 {@code user_id}. {@code mock-login} 이 만드는 계정이다. */
    private String u1;

    /** 정리 대상 — 심거나 만든 것만 담는다. */
    private final List<Long> createdRptpIds = new ArrayList<>();
    private final List<Long> createdExcnIds = new ArrayList<>();

    /** 픽스처 — {@code MANUAL} u1 2건 · u1 {@code AI} 1건 · u2 {@code MANUAL} 1건 · 과거 {@code system} 1건 */
    private long u1Manual1;
    private long u1Manual2;
    private long u1Ai;
    private long u2Manual;
    private long legacyManual;

    /**
     * {@code AUTO} 3행(배포 부서 {@code ALL} · A · B). 생성자는 모두 남(u2)이다 —
     * 생성자로 판정하지 않는다는 것까지 함께 보인다. 집중구역 결과를 물려 스냅샷도 열 수 있다.
     */
    private long autoAll;
    private long autoA;
    private long autoB;

    /** 케이스 4 — 같은 모양의 집중구역 보고서 2건(내 것 · 남의 것) */
    private long u1Hotspot;
    private long u2Hotspot;

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
        u1 = IntegrationLoginSupport.MOCK_ID;

        u1Manual1 = insertReport("MANUAL", u1, "CVLCPT", null);
        u1Manual2 = insertReport("MANUAL", u1, "CVLCPT", null);
        u1Ai = insertReport("AI", u1, "CVLCPT", null);
        u2Manual = insertReport("MANUAL", U2, "CVLCPT", null);
        legacyManual = insertReport("MANUAL", LEGACY, "CVLCPT", null);

        u1Hotspot = insertReport("MANUAL", u1, "HOTSPOT", HOTSPOT_RSLT_JSON);
        u2Hotspot = insertReport("MANUAL", U2, "HOTSPOT", HOTSPOT_RSLT_JSON);

        // 🔴 FK 순서 — 부서 → 사용자. 보고서의 crltn_dept_cd 에는 FK 가 없다
        insertDept(DEPT_A);
        insertDept(DEPT_B);
        insertUser(USER_A, "ROLE_USER", DEPT_A);
        insertUser(USER_NULL, "ROLE_USER", null);
        insertUser(USER_SVC, "ROLE_SERVICE_ADMIN", DEPT_B);
        insertUser(USER_SYS, "ROLE_SYSTEM_ADMIN", null);

        autoAll = insertAutoReport(DEPT_ALL);
        autoA = insertAutoReport(DEPT_A);
        autoB = insertAutoReport(DEPT_B);
    }

    /** 🔴 모아 둔 id 만 FK 방향으로 지운다. 한 문장이 터져도 나머지를 마저 실행한다. */
    @AfterEach
    void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long rptpId : createdRptpIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        }
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdRptpIds.clear();
        createdExcnIds.clear();
        // 🔴 FK 순서 — 사용자(mock-user 의 부서 원복 포함) → 부서. 테스트가 만든 코드만 지운다
        deleteQuietly(failures, "UPDATE parking.tb_user SET dept_cd = NULL WHERE user_id = ?",
                IntegrationLoginSupport.MOCK_ID);
        auth.cleanUp();
        for (String userId : new String[] {USER_A, USER_NULL, USER_SVC, USER_SYS}) {
            deleteQuietly(failures, "DELETE FROM parking.tb_user WHERE user_id = ?", userId);
        }
        for (String deptCd : new String[] {DEPT_A, DEPT_B}) {
            deleteQuietly(failures, "DELETE FROM parking.tb_dept WHERE dept_cd = ?", deptCd);
        }
        assertThat(failures).as("테스트가 심은 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    // ───────────────────────────── 1 · 2. 목록 (요청 ①②)

    @Test
    @DisplayName("1. MANUAL 목록 — 내 것만 보인다 · 남의 것과 과거 system 행은 없다 · 총건수도 같은 조건이다")
    void manualListShowsOnlyMine() {
        PageResponse<ReportListVO> page = reports("MANUAL");

        List<Long> ids = page.getContent().stream().map(ReportListVO::getRptpId).toList();
        assertThat(ids).as("내가 만든 MANUAL 2건은 보인다").contains(u1Manual1, u1Manual2);
        assertThat(ids).as("🔴 남의 것·과거 system 행은 보이지 않는다 (요청 ①)")
                .doesNotContain(u2Manual, legacyManual);

        // 🔴 돌아온 전 행이 정말 내 것인가 — "우연히 안 섞였다" 가 아니라 전수로 본다
        assertThat(ownersOf(ids)).as("MANUAL 목록의 생성자 집합").containsOnly(u1);

        // 총건수는 같은 조건의 독립 SQL 과 같다 (목록과 총건수가 다른 조건이면 마지막 페이지가 빈다)
        assertThat(page.getTotalElements())
                .as("totalElements 는 '내 MANUAL' 의 수다")
                .isEqualTo(countReports("MANUAL", u1));

        // AI 탭도 같은 규칙이다 — 개인 대화의 산물이라 MANUAL 과 같이 좁힌다
        assertThat(reports("AI").getContent().stream().map(ReportListVO::getRptpId).toList())
                .contains(u1Ai);
    }

    @Test
    @DisplayName("2. 🔴 AUTO 목록 — 기대 결과표 4명 × 3행 (생성자와 무관)")
    void autoListFollowsDeptAndAdminMatrix() {
        assumeAdminRolesHoldM0402();

        // 행: 보는 사람 → [ALL, A, B] 가 보이는가 (기대 결과표)
        assertAutoVisibility(USER_A, true, true, false);
        assertAutoVisibility(USER_NULL, true, false, false);
        assertAutoVisibility(USER_SVC, true, true, true);
        assertAutoVisibility(USER_SYS, true, true, true);

        // 세션 사용자(mock-user: ROLE_USER · 부서 NULL)도 USER_NULL 과 같은 행이다
        List<Long> mine = reports("AUTO").getContent().stream().map(ReportListVO::getRptpId).toList();
        assertThat(mine).as("mock-user(부서 NULL) — ALL 만").contains(autoAll).doesNotContain(autoA, autoB);

        // 총건수는 같은 규칙의 독립 SQL 과 같다 — 목록과 총건수가 같은 조각을 쓴다
        assertThat(reportService.selectReports("AUTO", USER_A, ReportListFilter.NONE, 0, 1).getTotalElements())
                .as("부서 A 사용자의 AUTO 총건수 = ALL + A").isEqualTo(countAutoInDepts(DEPT_ALL, DEPT_A));
        assertThat(reportService.selectReports("AUTO", USER_NULL, ReportListFilter.NONE, 0, 1).getTotalElements())
                .as("부서 NULL 사용자의 AUTO 총건수 = ALL 만").isEqualTo(countAutoInDepts(DEPT_ALL));
        assertThat(reportService.selectReports("AUTO", USER_SVC, ReportListFilter.NONE, 0, 1).getTotalElements())
                .as("관리자의 AUTO 총건수 = 전건").isEqualTo(countReports("AUTO", null));
        assertThat(reportService.selectReports("AUTO", USER_SYS, ReportListFilter.NONE, 0, 1).getTotalElements())
                .as("관리자의 AUTO 총건수 = 전건").isEqualTo(countReports("AUTO", null));
    }

    @Test
    @DisplayName("2-1. 🔴 AUTO 단건 가드 — 목록과 같은 매트릭스 · 안 보이는 칸은 403 이 아니라 404")
    void autoGuardFollowsSameMatrix() {
        assumeAdminRolesHoldM0402();

        assertGuard(USER_A, true, true, false);
        assertGuard(USER_NULL, true, false, false);
        assertGuard(USER_SVC, true, true, true);
        assertGuard(USER_SYS, true, true, true);

        // 미인증(ownerId=null) — 가드도 폴백 없이 404 다
        for (long id : new long[] {autoAll, autoA, autoB}) {
            assertThat(guardStatus(id, null)).as("ownerId=null · rptpId=%d", id).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ───────────────────────────── 3 · 4. 단건 · 스냅샷은 404

    @Test
    @DisplayName("3. 남의 MANUAL 보고서 단건 조회 — 404 (403 이 아니다: 존재 여부를 알려주지 않는다)")
    void othersReportDetailIs404() {
        assertThat(detailStatus(u1Manual1)).as("내 것은 열린다 — 아래 404 가 '무엇을 줘도 404' 가 아님을 보인다")
                .isEqualTo(HttpStatus.OK);
        assertThat(detailStatus(u2Manual)).as("남의 직접 생성 보고서").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(detailStatus(legacyManual)).as("과거 system 행").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("3-1. 🔴 HTTP — mock-user 부서 NULL 이면 ALL 만, 부서 A 로 바꾸면 A 도 열리고 B 는 단건·스냅샷 모두 404")
    void autoDetailAndSnapshotFollowDeptOverHttp() {
        // 부서 NULL — 생성자가 남이어도 ALL 은 열리고, A 는 404
        assertThat(detailStatus(autoAll)).as("ALL 은 전 사용자").isEqualTo(HttpStatus.OK);
        assertThat(detailStatus(autoA)).as("부서 없는 사용자에게 부서 A 보고서는 없다")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 재로그인 불필요 — 판정이 매 요청 tb_user 를 본다. 정리는 cleanUp() 이 원복한다
        assertThat(jdbcTemplate.update("UPDATE parking.tb_user SET dept_cd = ? WHERE user_id = ?",
                DEPT_A, u1)).as("mock-user 행이 있어야 부서를 심을 수 있다").isEqualTo(1);

        assertThat(detailStatus(autoA)).as("내 부서 AUTO 는 열린다").isEqualTo(HttpStatus.OK);
        assertThat(detailStatus(autoB)).as("🔴 남의 부서 AUTO 단건 — 403 이 아니라 404")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 스냅샷도 같은 가드다 — 대조군(내 부서)이 200 이라 404 의 원인은 부서 하나다
        assertThat(snapshotStatus(autoA)).as("내 부서 AUTO 스냅샷").isEqualTo(HttpStatus.OK);
        assertThat(snapshotStatus(autoB)).as("🔴 남의 부서 AUTO 스냅샷 — 목록만 막으면 본문이 그대로 열린다")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("4. 남의 보고서 /hotspot 스냅샷 — 404. 🔴 대조군(내 것)은 200 이라 '데이터 탓' 이 아니다")
    void othersSnapshotIs404() {
        assertThat(snapshotStatus(u1Hotspot))
                .as("같은 모양의 내 보고서는 열린다 — 아래 404 의 원인은 소유자 하나다")
                .isEqualTo(HttpStatus.OK);
        assertThat(snapshotStatus(u2Hotspot))
                .as("🔴 목록만 막으면 본문이 그대로 열린다 — 스냅샷도 같은 가드를 거친다")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ───────────────────────────── 5 · 5-1. 미인증

    @Test
    @DisplayName("5. 🔴 미인증 HTTP 목록은 401 이다 — 폴백으로 전체 공개하지 않는다")
    void anonymousListIs401() {
        for (String crtType : new String[] {"MANUAL", "AI", "AUTO"}) {
            assertThat(restTemplate.getForEntity(REPORTS_URL + "?crtType=" + crtType + "&size=100",
                    String.class).getStatusCode())
                    .as("익명 목록 crtType=%s — AUTO 도 세션은 필요하다", crtType)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(restTemplate.getForEntity(REPORTS_URL + "/" + autoAll, String.class).getStatusCode())
                .as("익명 단건").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(HOTSPOT_SNAPSHOT_URL, String.class, u1Hotspot).getStatusCode())
                .as("익명 스냅샷").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("5-1. 🔴 ownerId=null 로 서비스를 직접 불러도 0건이다 — `1=0` 분기 (AUTO 포함 · 전체 공개로 열리지 않는다)")
    void serviceWithNullOwnerReturnsNothing() {
        // AUTO 도 판정에 보는 사람이 필요해져 ownerId=null 이면 0건이다(종전엔 전건이었다)
        for (String crtType : new String[] {"MANUAL", "AI", "AUTO"}) {
            PageResponse<ReportListVO> page = reportService.selectReports(crtType, null, ReportListFilter.NONE, 0, 100);
            assertThat(page.getTotalElements()).as("crtType=%s · ownerId=null", crtType).isZero();
            assertThat(page.getContent()).as("crtType=%s · ownerId=null", crtType).isEmpty();
        }
    }

    // ───────────────────────────── 6. 과거 system 행

    @Test
    @DisplayName("6. creatr_id='system' 인 과거 행은 실사용자 누구의 목록에도 없다 (마이그레이션을 하지 않은 대가)")
    void legacySystemRowsAreInvisible() {
        assertThat(reports("MANUAL").getContent().stream().map(ReportListVO::getRptpId).toList())
                .doesNotContain(legacyManual);
        // 남(u2)의 눈으로도 없다 — 서비스를 직접 불러 세션 없이 확인한다
        assertThat(reportService.selectReports("MANUAL", U2, ReportListFilter.NONE, 0, 100).getContent().stream()
                .map(ReportListVO::getRptpId).toList())
                .as("소유자가 누구든 'system' 행은 그의 것이 아니다")
                .doesNotContain(legacyManual)
                .contains(u2Manual);
        assertThat(detailStatus(legacyManual)).isEqualTo(HttpStatus.NOT_FOUND);
        // ⚠️ 엄밀히는 user_id 가 문자 그대로 'system' 인 사용자만 볼 수 있다 — 설계 결정은 그런 계정을
        //    만들지 않는다는 운영 전제다. 코드로 막는 장치가 아니라는 점을 여기 남긴다.
    }

    // ───────────────────────────── 7 · 8. 생성자 기록 (요청 ③④)

    @Test
    @DisplayName("7. 🔴 생성 직후 creatr_id · creatr_nm · excutr_id 가 넘긴 사용자다 ('system'/'관리자' 가 아니다)")
    void createRecordsSessionUser() {
        ComplaintReportVO vo = complaintReportService.createReport(complaintRequest(), U2, "남의 이름");
        createdRptpIds.add(vo.getRptpId());
        createdExcnIds.add(vo.getExcnId());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT r.creatr_id, r.creatr_nm, e.excutr_id"
                        + "  FROM parking.tb_rptp r JOIN parking.tb_anls_excn e ON e.excn_id = r.excn_id"
                        + " WHERE r.rptp_id = ?", vo.getRptpId());

        assertThat(row.get("creatr_id")).as("보고서 생성자 (요청 ③)").isEqualTo(U2);
        assertThat(row.get("creatr_nm")).as("표시명도 함께 적는다 — 목록의 '생성자' 칸이다").isEqualTo("남의 이름");
        assertThat(row.get("excutr_id"))
                .as("🔴 분석 실행자도 같다 — 실행자만 system 이면 '누가 돌렸나' 가 보고서와 어긋난다")
                .isEqualTo(U2);

        // 그리고 그 보고서는 u2 의 것이라 u1 에게 보이지 않는다 — 기록과 조회가 같은 키를 쓴다
        assertThat(detailStatus(vo.getRptpId())).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("8. 🔴 미인증 생성은 401 이고 보고서가 한 행도 남지 않는다 (폴백 'system' 을 쌓지 않는다)")
    void anonymousCreateIs401AndInsertsNothing() {
        long[] before = tableCounts();

        // 🔴 바디는 유효하다 — 같은 조건을 서비스에 직접 넘기면 저장된다(= 401 의 원인이 바디가 아니다)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bgngYmd", complaintRequest().getBgngYmd());
        body.put("endYmd", complaintRequest().getEndYmd());
        body.put("fileFmt", "PDF");

        assertThat(restTemplate.postForEntity(COMPLAINT_CREATE_URL, body, String.class).getStatusCode())
                .as("익명 생성").isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(tableCounts()).as("401 이면 세 표 어디에도 행이 늘지 않는다")
                .containsExactly(before[0], before[1], before[2]);
    }

    // ───────────────────────────── 헬퍼 — 호출

    /** 세션(u1)으로 목록을 부른다. 🔴 익명이면 401 이므로 세션을 반드시 싣는다. */
    private PageResponse<ReportListVO> reports(String crtType) {
        // PageResponse 는 기본 생성자가 없어 역직렬화할 수 없다 — 서비스를 직접 불러 같은 값을 본다.
        // HTTP 경로(401·200)는 위 케이스 3·5 가 상태코드로 지킨다.
        return reportService.selectReports(crtType, u1, ReportListFilter.NONE, 0, 100);
    }

    private HttpStatus detailStatus(long rptpId) {
        return HttpStatus.valueOf(restTemplate.exchange(REPORTS_URL + "/" + rptpId, HttpMethod.GET,
                new HttpEntity<>(session), String.class).getStatusCode().value());
    }

    private HttpStatus snapshotStatus(long rptpId) {
        return HttpStatus.valueOf(restTemplate.exchange(HOTSPOT_SNAPSHOT_URL, HttpMethod.GET,
                new HttpEntity<>(session), String.class, rptpId).getStatusCode().value());
    }

    /** 민원현황 보고서 요청 — 🔴 0건도 정식 보고서라(그 도메인의) 기간을 고르지 않아도 201 이다. */
    private ComplaintReportCreateRequest complaintRequest() {
        String[] range = jdbcTemplate.queryForObject(
                "SELECT to_char(max(ocrn_dt) - interval '30 days', 'YYYYMMDD'),"
                        + "       to_char(max(ocrn_dt), 'YYYYMMDD') FROM parking.tb_cvlcpt",
                (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2)});
        assertThat(range).isNotNull();
        assertThat(range[1]).as("원천(TB_CVLCPT)이 비어 있다 — 배치 적재분을 먼저 확인하라").matches("\\d{8}");

        ComplaintReportCreateRequest req = new ComplaintReportCreateRequest();
        req.setBgngYmd(range[0]);
        req.setEndYmd(range[1]);
        req.setFileFmt("PDF");
        return req;
    }

    // ───────────────────────────── 헬퍼 — 매트릭스

    /** 목록에서 [ALL, A, B] 각각이 보이는지 단언한다. */
    private void assertAutoVisibility(String userId, boolean all, boolean a, boolean b) {
        List<Long> ids = reportService.selectReports("AUTO", userId, ReportListFilter.NONE, 0, 100).getContent().stream()
                .map(ReportListVO::getRptpId).toList();
        assertVisible(ids, userId, autoAll, "ALL", all);
        assertVisible(ids, userId, autoA, "A", a);
        assertVisible(ids, userId, autoB, "B", b);
    }

    private static void assertVisible(List<Long> ids, String userId, long rptpId, String label, boolean expected) {
        if (expected) {
            assertThat(ids).as("%s 의 AUTO 목록에 배포 부서 %s 보고서가 보여야 한다", userId, label).contains(rptpId);
        } else {
            assertThat(ids).as("%s 의 AUTO 목록에 배포 부서 %s 보고서가 없어야 한다", userId, label)
                    .doesNotContain(rptpId);
        }
    }

    /** 단건 가드로 [ALL, A, B] 각각을 단언한다 — 통과(200) 또는 404. */
    private void assertGuard(String userId, boolean all, boolean a, boolean b) {
        assertThat(guardStatus(autoAll, userId)).as("%s × ALL", userId)
                .isEqualTo(all ? HttpStatus.OK : HttpStatus.NOT_FOUND);
        assertThat(guardStatus(autoA, userId)).as("%s × A", userId)
                .isEqualTo(a ? HttpStatus.OK : HttpStatus.NOT_FOUND);
        assertThat(guardStatus(autoB, userId)).as("%s × B", userId)
                .isEqualTo(b ? HttpStatus.OK : HttpStatus.NOT_FOUND);
    }

    /** 가드를 직접 부른다 — 통과면 200, {@link ResponseStatusException} 이면 그 상태코드. */
    private HttpStatus guardStatus(long rptpId, String userId) {
        try {
            reportOwnershipGuard.check(rptpId, userId);
            return HttpStatus.OK;
        } catch (ResponseStatusException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }

    /**
     * 🔴 전제 확인 — 관리자 판정은 실 {@code tb_authrt_menu} 의 {@code M0402} 매핑에 기댄다.
     * 표를 고치지 않으므로, 운영이 매핑을 바꾸면 매트릭스 실패보다 이 메시지가 먼저 원인을 말한다.
     */
    private void assumeAdminRolesHoldM0402() {
        List<String> roles = jdbcTemplate.queryForList(
                "SELECT am.authrt_cd FROM parking.tb_authrt_menu am"
                        + "  JOIN parking.tb_menu m ON m.menu_id = am.menu_id"
                        + " WHERE am.menu_id = 'M0402' AND m.use_yn = 'Y'", String.class);
        assertThat(roles).as("전제: SERVICE·SYSTEM 관리자 롤에 M0402 가 있고 ROLE_USER 에는 없다")
                .contains("ROLE_SERVICE_ADMIN", "ROLE_SYSTEM_ADMIN")
                .doesNotContain(IntegrationLoginSupport.MOCK_AUTHRT_CD);
    }

    private void insertDept(String deptCd) {
        jdbcTemplate.update("INSERT INTO parking.tb_dept (dept_cd, dept_nm, use_yn, rgtr_id, reg_dt)"
                + " VALUES (?, ?, 'Y', 'test', now()) ON CONFLICT (dept_cd) DO NOTHING", deptCd, deptCd);
    }

    /** {@code cert_id} 는 유니크라 {@code user_id} 와 같은 값으로 둔다. 남은 행이 있어도 값을 다시 맞춘다. */
    private void insertUser(String userId, String authrtCd, String deptCd) {
        jdbcTemplate.update(
                "INSERT INTO parking.tb_user"
                        + " (user_id, cert_id, user_nm, user_authrt_cd, use_yn, rgtr_id, reg_dt, dept_cd)"
                        + " VALUES (?, ?, ?, ?, 'Y', 'test', now(), ?)"
                        + " ON CONFLICT (user_id) DO UPDATE"
                        + "    SET user_authrt_cd = EXCLUDED.user_authrt_cd, dept_cd = EXCLUDED.dept_cd, use_yn = 'Y'",
                userId, userId, userId, authrtCd, deptCd);
    }

    /** 남(u2)이 만든 집중구역 {@code AUTO} 보고서 1건 — 배포 부서만 다르게 심는다(스냅샷이 열린다). */
    private long insertAutoReport(String crltnDeptCd) {
        long rptpId = insertReport("AUTO", U2, "HOTSPOT", HOTSPOT_RSLT_JSON);
        jdbcTemplate.update("UPDATE parking.tb_rptp SET crltn_dept_cd = ? WHERE rptp_id = ?", crltnDeptCd, rptpId);
        return rptpId;
    }

    /** 🔴 독립 SQL — 배포 부서가 주어진 코드 중 하나인 AUTO 수. */
    private long countAutoInDepts(String... deptCds) {
        String in = String.join(",", Collections.nCopies(deptCds.length, "?"));
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp WHERE crt_type = 'AUTO' AND crltn_dept_cd IN (" + in + ")",
                Long.class, (Object[]) deptCds);
        return n == null ? 0L : n;
    }

    // ───────────────────────────── 헬퍼 — 픽스처 · 독립 SQL

    /**
     * 실행 1행 + (결과 1행) + 보고서 1행을 심고 {@code rptp_id} 를 돌려준다.
     *
     * @param rsltJson {@code null} 이면 결과 행을 만들지 않는다(스냅샷이 열리지 않는 보고서)
     */
    private long insertReport(String crtType, String creatrId, String anlsTypeCd, String rsltJson) {
        Long excnId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_anls_excn (anls_type_cd, excutr_id, excn_stts_cd, excn_dt)"
                        + " VALUES (?, ?, 'DONE', now()) RETURNING excn_id",
                Long.class, anlsTypeCd, creatrId);
        assertThat(excnId).isNotNull();
        createdExcnIds.add(excnId);

        if (rsltJson != null) {
            jdbcTemplate.update("INSERT INTO parking.tb_anls_rslt (excn_id, rslt_json) VALUES (?, ?::jsonb)",
                    excnId, rsltJson);
        }

        Long rptpId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_rptp"
                        + " (crt_type, excn_id, rptp_nm, anls_prd_expln, stts_cd, file_fmt,"
                        + "  creatr_id, creatr_nm, crt_ymd)"
                        + " VALUES (?, ?, '소유자 판정용 보고서', '테스트 기간', 'DONE', 'PDF', ?, ?, now())"
                        + " RETURNING rptp_id",
                Long.class, crtType, excnId, creatrId, creatrId + " 이름");
        assertThat(rptpId).isNotNull();
        createdRptpIds.add(rptpId);
        return rptpId;
    }

    /** 🔴 독립 SQL — 매퍼·API 를 다시 부르지 않는다. {@code ownerId} 가 null 이면 소유자로 좁히지 않는다. */
    private long countReports(String crtType, String ownerId) {
        Long n = ownerId == null
                ? jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM parking.tb_rptp WHERE crt_type = ?", Long.class, crtType)
                : jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM parking.tb_rptp WHERE crt_type = ? AND creatr_id = ?",
                        Long.class, crtType, ownerId);
        return n == null ? 0L : n;
    }

    /** 돌아온 {@code rptpId} 들의 실제 {@code creatr_id} 집합 — 목록이 전수로 내 것인지 본다. */
    private List<String> ownersOf(List<Long> rptpIds) {
        assertThat(rptpIds).as("목록이 비어 있으면 '전부 내 것' 이 아무것도 검증하지 못한다").isNotEmpty();
        return rptpIds.stream().map(id -> jdbcTemplate.queryForObject(
                "SELECT creatr_id FROM parking.tb_rptp WHERE rptp_id = ?", String.class, id)).toList();
    }

    /** {@code [tb_anls_excn, tb_anls_rslt, tb_rptp]} 행 수. */
    private long[] tableCounts() {
        return new long[] {countOf("parking.tb_anls_excn"), countOf("parking.tb_anls_rslt"),
                countOf("parking.tb_rptp")};
    }

    private long countOf(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Object arg) {
        try {
            jdbcTemplate.update(sql, arg);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
