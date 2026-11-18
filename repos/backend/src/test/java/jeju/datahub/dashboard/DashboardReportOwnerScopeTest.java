package jeju.datahub.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
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

import jeju.datahub.dashboard.service.ReportVO;
import jeju.datahub.report.service.ReportListFilter;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.ReportService;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 대시보드 <b>"최근 리포트" 카드</b>의 소유자 범위 통합 테스트.
 * (TSK-4202 Step 5 케이스 9~11 — {@code GET /api/dashboard/reports})
 *
 * <h2>왜 보고서 목록과 따로 테스트하는가</h2>
 * 🔴 <b>카드와 목록은 다른 매퍼 조각을 쓴다.</b> 목록({@code ownerWhere})은 {@code crtType} 이 필수라
 * "{@code AUTO} 가 아니면 소유자로 좁힌다" 로 끝나지만, 카드는 {@code crtType} 이 <b>nullable</b> 이라
 * OGNL 에서 {@code null != 'AUTO'} 가 <b>true</b> 가 된다 — 목록 조각을 그대로 include 하면
 * {@code crtType} 미전송 호출에서 <b>보여야 할 자동 생성 보고서까지 통째로 사라진다</b>.
 * 그래서 카드는 행 단위로 판정하는 {@code ownerWhereRowwise} 를 쓰고, <b>케이스 11 이 그 구분을 지킨다</b>.
 * 🔁 TSK-4257 — 카드의 {@code AUTO} 행도 목록과 같은 조각({@code autoVisible})으로 배포 부서 · {@code ALL} ·
 * 관리자({@code M0402})만 남는다. 케이스 11 은 {@code mock-user} 의 부서를 A 로 심어 그 판정까지 본다.
 *
 * <p>그리고 카드는 <b>목록의 축약</b>이어야 한다(케이스 10) — 두 화면이 같은 사용자에게 다른 집합을
 * 보여주면 그게 더 혼란이다.
 *
 * <h2>🔴 두 사용자 · 기대 건수를 박지 않는다</h2>
 * u1 은 {@code mock-login} 세션({@code mock-user}), u2 는 세션을 만들 수 없는 남이라 행만 심는다.
 * 단언은 모두 <b>이 테스트가 심은 {@code rptpId}</b> 와 <b>독립 SQL</b> 로 한다 —
 * {@code tb_rptp} 는 시드·배치·사람이 함께 소유하므로 숫자를 굳히면 남의 변경에 깨진다.
 *
 * <p>🔴 {@code RANDOM_PORT} 라 롤백이 안 먹는다 — {@link #cleanUp()} 이 심은 id 만 지운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TSK-4202 Step 5 — 대시보드 최근 리포트 카드의 소유자 범위 (케이스 9~11)")
class DashboardReportOwnerScopeTest {

    private static final String CARD_URL = "/api/dashboard/reports";

    /** 남의 {@code user_id} — {@code varchar(20)} 안에 들어간다. */
    private static final String U2 = "owner-u2";

    /** D4 가 "아무에게도 보이지 않는다" 고 못박은 과거 행의 생성자. */
    private static final String LEGACY = "system";

    /** TSK-4257 — 부서 A("내 부서") · B("남의 부서"). 접두 t4257-dash- 로 시드·다른 테스트와 겹치지 않는다. */
    private static final String DEPT_A = "t4257-dash-a";
    private static final String DEPT_B = "t4257-dash-b";

    /**
     * 카드의 {@code LIMIT} · 목록의 {@code size} 상한(100, D8)에 맞춘 값.
     * 🔴 카드 기본값 5 로 부르면 심은 행이 상위 5건에 가려 아무것도 검증하지 못한다.
     */
    private static final int LIMIT = 100;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReportService reportService;

    private IntegrationLoginSupport auth;
    private HttpHeaders session;
    private String u1;

    private final List<Long> createdRptpIds = new ArrayList<>();
    private final List<Long> createdExcnIds = new ArrayList<>();

    private long u1Manual1;
    private long u1Manual2;
    private long u1Ai;
    private long u2Manual;
    private long legacyManual;

    /** TSK-4257 — 남(u2)이 만든 {@code AUTO} 3행. 배포 부서만 {@code ALL} · A · B 로 다르다. */
    private long autoAll;
    private long autoA;
    private long autoB;

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
        u1 = IntegrationLoginSupport.MOCK_ID;

        // 🔴 crt_ymd = now() — 카드는 crt_ymd DESC 정렬이라 심은 행이 LIMIT 안에 반드시 들어와야 한다
        u1Manual1 = insertReport("MANUAL", u1);
        u1Manual2 = insertReport("MANUAL", u1);
        u1Ai = insertReport("AI", u1);
        u2Manual = insertReport("MANUAL", U2);
        legacyManual = insertReport("MANUAL", LEGACY);

        // 🔴 FK — tb_user.dept_cd → tb_dept. 부서를 먼저 넣는다(보고서의 crltn_dept_cd 에는 FK 가 없다)
        for (String deptCd : new String[] {DEPT_A, DEPT_B}) {
            jdbcTemplate.update("INSERT INTO parking.tb_dept (dept_cd, dept_nm, use_yn, rgtr_id, reg_dt)"
                    + " VALUES (?, ?, 'Y', 'test', now()) ON CONFLICT (dept_cd) DO NOTHING", deptCd, deptCd);
        }
        autoAll = insertAutoReport("ALL");
        autoA = insertAutoReport(DEPT_A);
        autoB = insertAutoReport(DEPT_B);
    }

    @AfterEach
    void cleanUp() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long rptpId : createdRptpIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        }
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdRptpIds.clear();
        createdExcnIds.clear();
        // 🔴 FK 순서 — 사용자(mock-user 부서 원복 → 행 삭제) → 부서. 테스트가 만든 코드만 지운다
        deleteQuietly(failures, "UPDATE parking.tb_user SET dept_cd = NULL WHERE user_id = ?",
                IntegrationLoginSupport.MOCK_ID);
        auth.cleanUp();
        for (String deptCd : new String[] {DEPT_A, DEPT_B}) {
            deleteQuietly(failures, "DELETE FROM parking.tb_dept WHERE dept_cd = ?", deptCd);
        }
        assertThat(failures).as("테스트가 심은 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    // ───────────────────────────── 9. 카드도 내 것만

    @Test
    @DisplayName("9. crtType=MANUAL 카드 — 내 것만 보인다 · 남의 것과 과거 system 행은 없다 (요청 ①)")
    void cardWithManualShowsOnlyMine() {
        Set<Long> ids = cardIds("&crtType=MANUAL");

        assertThat(ids).as("내가 만든 MANUAL 2건은 카드에 뜬다").contains(u1Manual1, u1Manual2);
        assertThat(ids).as("🔴 Step 4 를 목록에만 걸면 B 의 대시보드에 A 의 보고서가 계속 뜬다 (요청 ① 위반)")
                .doesNotContain(u2Manual, legacyManual);
        // 돌아온 전 행이 정말 내 것인가 — 전수로 본다
        assertThat(ownersOf(ids)).as("MANUAL 카드의 생성자 집합").containsOnly(u1);
    }

    // ───────────────────────────── 10. 카드 = 목록의 축약

    @Test
    @DisplayName("10. 🔴 카드 ↔ 보고서 목록의 rptpId 집합이 같다 — 두 화면이 다른 집합을 보여주면 그게 더 혼란이다")
    void cardMatchesReportList() {
        Set<Long> card = cardIds("&crtType=MANUAL");
        Set<Long> list = new LinkedHashSet<>(
                reportService.selectReports("MANUAL", u1, ReportListFilter.NONE, 0, LIMIT).getContent().stream()
                        .map(ReportListVO::getRptpId).toList());

        // 🔴 건수가 아니라 집합을 비교한다 — 정렬이 달라도(카드는 crt_ymd DESC NULLS LAST,
        //    목록은 crt_ymd DESC + rptp_id DESC) 담기는 행은 같아야 한다.
        assertThat(card).as("카드는 목록의 축약이다 (같은 ownerId · 같은 crtType)").isEqualTo(list);
        assertThat(list).as("비교가 공집합끼리 성립하면 아무것도 검증하지 못한다").isNotEmpty();

        // 남(u2)의 눈으로도 두 창구가 같다 — 카드는 세션이 필요해 목록 쪽을 서비스로 직접 본다
        Set<Long> u2List = new LinkedHashSet<>(
                reportService.selectReports("MANUAL", U2, ReportListFilter.NONE, 0, LIMIT).getContent().stream()
                        .map(ReportListVO::getRptpId).toList());
        assertThat(u2List).as("u2 의 목록에는 u2 것만 있다").contains(u2Manual)
                .doesNotContain(u1Manual1, u1Manual2);
    }

    // ───────────────────────────── 11. crtType 미전송 — 보이는 AUTO 가 사라지면 안 된다

    @Test
    @DisplayName("11. 🔴 crtType 미전송 카드 — autoVisible 집합(ALL·내 부서) + 내 MANUAL·AI 만 (TSK-4257 · ownerWhere 를 잘못 include 하면 여기서 잡힌다)")
    void cardWithoutCrtTypeKeepsVisibleAutoAndNarrowsMine() {
        // mock-user = ROLE_USER(M0402 없음). 부서를 A 로 심는다 — 재로그인 불필요(판정이 매 요청 tb_user 를 본다)
        assertThat(jdbcTemplate.update("UPDATE parking.tb_user SET dept_cd = ? WHERE user_id = ?", DEPT_A, u1))
                .as("mock-user 행이 있어야 부서를 심을 수 있다").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_authrt_menu WHERE authrt_cd = ? AND menu_id = 'M0402'",
                Long.class, IntegrationLoginSupport.MOCK_AUTHRT_CD))
                .as("전제: ROLE_USER 에 M0402 가 없다 — 있으면 관리자로 판정돼 B 도 보인다(D1)").isZero();

        Set<Long> ids = cardIds("");

        // ① AUTO 는 배포 부서로 걸러진다 — 생성자(남)와 무관하다
        assertThat(ids).as("🔴 ownerWhere 를 include 하면 crtType=null 일 때 보여야 할 AUTO 가 통째로 사라진다")
                .contains(autoAll, autoA);
        assertThat(ids).as("🔴 남의 부서 AUTO 는 카드에도 없다 (TSK-4257 D3)").doesNotContain(autoB);
        assertThat(ids).as("심지 않은 기존 AUTO 중 내게 보이는 것(ALL·부서 A)은 모두 들어온다")
                .containsAll(visibleAutoRptpIds());

        // ② 직접·AI 는 내 것만
        assertThat(ids).as("내 MANUAL·AI 는 보인다").contains(u1Manual1, u1Manual2, u1Ai);
        assertThat(ids).as("남의 MANUAL·과거 system 행은 보이지 않는다")
                .doesNotContain(u2Manual, legacyManual);

        // ③ 돌아온 전 행은 '보이는 AUTO 이거나 내 것' 이다 — 행 단위 판정을 전수로 확인한다
        for (Long id : ids) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT crt_type, creatr_id, crltn_dept_cd FROM parking.tb_rptp WHERE rptp_id = ?", id);
            boolean visibleAuto = "AUTO".equals(row.get("crt_type"))
                    && ("ALL".equals(row.get("crltn_dept_cd")) || DEPT_A.equals(row.get("crltn_dept_cd")));
            boolean mine = !"AUTO".equals(row.get("crt_type")) && u1.equals(row.get("creatr_id"));
            assertThat(visibleAuto || mine)
                    .as("rptpId=%d 는 '배포 부서 ALL·A 인 AUTO 이거나 내 것' 이어야 한다 — %s", id, row)
                    .isTrue();
        }
    }

    // ───────────────────────────── 미인증

    @Test
    @DisplayName("12. 🔴 익명 카드 호출은 401 이다 — 폴백으로 전체 공개하지 않는다 (D5)")
    void anonymousCardIs401() {
        assertThat(restTemplate.getForEntity(CARD_URL + "?limit=" + LIMIT, String.class).getStatusCode())
                .as("crtType 미전송").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(CARD_URL + "?limit=" + LIMIT + "&crtType=MANUAL", String.class)
                .getStatusCode()).as("crtType=MANUAL").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(CARD_URL + "?limit=" + LIMIT + "&crtType=AUTO", String.class)
                .getStatusCode()).as("crtType=AUTO — 열람 범위 판정에 보는 사람이 필요하다(TSK-4257) · 세션 없으면 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ───────────────────────────── 헬퍼

    /** 세션(u1)으로 카드를 부르고 {@code rptpId} 집합을 돌려준다. */
    private Set<Long> cardIds(String extraQuery) {
        String url = CARD_URL + "?limit=" + LIMIT + extraQuery;
        ResponseEntity<ReportVO[]> res = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(session), ReportVO[].class);
        assertThat(res.getStatusCode().value()).as("GET %s", url).isEqualTo(200);
        assertThat(res.getBody()).as("빈 결과도 배열이어야 한다(null 금지)").isNotNull();
        return new LinkedHashSet<>(Arrays.stream(res.getBody()).map(ReportVO::getRptpId).toList());
    }

    private List<String> ownersOf(Set<Long> rptpIds) {
        assertThat(rptpIds).as("카드가 비어 있으면 '전부 내 것' 이 아무것도 검증하지 못한다").isNotEmpty();
        return rptpIds.stream().map(id -> jdbcTemplate.queryForObject(
                "SELECT creatr_id FROM parking.tb_rptp WHERE rptp_id = ?", String.class, id)).toList();
    }

    /** 🔴 독립 SQL — 부서 A · M0402 없는 사용자에게 보여야 할 AUTO(배포 부서 ALL·A). 숫자를 박지 않는다. */
    private List<Long> visibleAutoRptpIds() {
        return jdbcTemplate.queryForList(
                "SELECT rptp_id FROM parking.tb_rptp WHERE crt_type = 'AUTO' AND crltn_dept_cd IN ('ALL', ?)"
                        + " ORDER BY crt_ymd DESC NULLS LAST LIMIT " + LIMIT, Long.class, DEPT_A);
    }

    /** 남(u2)이 만든 {@code AUTO} 1건 — 배포 부서만 다르게 심는다. */
    private long insertAutoReport(String crltnDeptCd) {
        long rptpId = insertReport("AUTO", U2);
        jdbcTemplate.update("UPDATE parking.tb_rptp SET crltn_dept_cd = ? WHERE rptp_id = ?", crltnDeptCd, rptpId);
        return rptpId;
    }

    /** 실행 1행 + 보고서 1행. 결과 행은 넣지 않는다(카드는 스냅샷을 읽지 않는다). */
    private long insertReport(String crtType, String creatrId) {
        Long excnId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_anls_excn (anls_type_cd, excutr_id, excn_stts_cd, excn_dt)"
                        + " VALUES ('CVLCPT', ?, 'DONE', now()) RETURNING excn_id",
                Long.class, creatrId);
        assertThat(excnId).isNotNull();
        createdExcnIds.add(excnId);

        Long rptpId = jdbcTemplate.queryForObject(
                "INSERT INTO parking.tb_rptp"
                        + " (crt_type, excn_id, rptp_nm, anls_prd_expln, stts_cd, file_fmt,"
                        + "  creatr_id, creatr_nm, crt_ymd)"
                        + " VALUES (?, ?, '카드 소유자 판정용 보고서', '테스트 기간', 'DONE', 'PDF', ?, ?, now())"
                        + " RETURNING rptp_id",
                Long.class, crtType, excnId, creatrId, creatrId + " 이름");
        assertThat(rptpId).isNotNull();
        createdRptpIds.add(rptpId);
        return rptpId;
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Object arg) {
        try {
            jdbcTemplate.update(sql, arg);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
