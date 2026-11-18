package jeju.datahub.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합테스트 공용 로그인 헬퍼 (TSK-4115 Step 7-1).
 *
 * <p>Step 7 이 쓰기 API 에 {@code @PreAuthorize} 를 걸면서 <b>익명 호출로 돌던 통합테스트가 전부
 * 403</b> 이 된다. {@code @PreAuthorize} 는 필터체인이 아니라 메서드 보안이라 test 체인의
 * {@code permitAll} 과 무관하게 동작하기 때문이다. 다섯 클래스가 같은 절차를 복붙하지 않도록
 * 여기로 뽑았다.
 *
 * <p><b>절차는 2단이다 — 재로그인이 없다.</b> Step 7-2 의 판정({@code MenuAccessGuard})이 세션에 굳은
 * authority 가 아니라 <b>매 요청 {@code TB_AUTHRT_MENU} 를 조회</b>하므로, 로그인 뒤에 매핑을 넣기만 하면
 * 다음 요청이 곧바로 그것을 본다.
 * <pre>
 *   HttpHeaders session = support.mockSession();     // ① mock-user 행 생성 (ROLE_USER)
 *   support.grantMenu("M0603");                // ② 그 롤에 대상 메뉴 부여 — 재로그인 불필요
 * </pre>
 *
 * <p>🔴 <b>CSRF 는 분기한다(단언을 지우는 게 아니다).</b> 기본 test 체인은
 * {@link TestSecurityConfig} 가 {@code csrf().disable()} 이라 {@code CsrfCookieFilter} 가 돌지 않아
 * {@code XSRF-TOKEN} 쿠키 자체가 내려오지 않는다. 운영 체인
 * ({@code app.auth.security-enabled=true})을 켠 테스트는 {@link #forProductionChain} 으로 만들어
 * "토큰이 반드시 내려온다" 를 그대로 단언하고, 기본 체인을 쓰는 테스트는
 * {@link #forTestChain} 으로 만들어 토큰이 없으면 헤더를 붙이지 않는다.
 *
 * <p>🔴 <b>정리 대상 3종</b>({@link #cleanUp()}) — {@code RANDOM_PORT} 는 롤백이 안 먹어 전부 실제로
 * 커밋된다: ① 헬퍼가 넣은 {@code TB_AUTHRT_MENU} 행(시드 40행 검증이 어긋나지 않게 <b>넣은 것만</b>
 * 지운다) ② {@code mock-user} 의 {@code TB_USER} 행 ③ {@code lgn_sn > startSn} 인 로그인 이력.
 * ⚠️ 이력은 {@code OR user_id IS NULL} 로 넓히지 않는다(CLAUDE.md) — 실패 행은 {@code user_id} 가
 * null 이라 테스트가 만들지 않은 실 실패 이력까지 지운다.
 */
public final class IntegrationLoginSupport {

    /** {@code MockAuthController} 가 리터럴 상수로 못박은 mock 식별자 */
    public static final String MOCK_ID = "mock-user";

    /** {@code app.auth.default-authrt-cd} — mock 로그인이 만드는 계정의 롤 (application-test.yml) */
    public static final String MOCK_AUTHRT_CD = "ROLE_USER";

    private final TestRestTemplate rest;
    private final JdbcTemplate jdbcTemplate;

    /** 운영 필터체인(= CSRF 활성)을 켠 테스트인가. 켰으면 XSRF-TOKEN 이 반드시 내려와야 한다. */
    private final boolean csrfExpected;

    /** 이 헬퍼가 <b>실제로 INSERT 한</b> 매핑만 담는다 — 시드에 이미 있던 행은 지우지 않는다. */
    private final Set<String> grantedMenuIds = new LinkedHashSet<>();

    private long startSn;

    /**
     * mock 세션 연속 실패 상한(시큐어코딩 2.16 — TSK-4336). 이만큼 연달아 실패하면 이 헬퍼는 더 요청하지 않고
     * 즉시 실패한다 — 서버가 비정상일 때 테스트마다 세션 요청을 반복하지 않게 한다. 성공하면 초기화된다.
     */
    static final int MAX_SESSION_ATTEMPTS = 3;

    /** 연속 실패 수 — 이 헬퍼 인스턴스 범위. */
    private int failedSessionAttempts;

    private IntegrationLoginSupport(TestRestTemplate rest, JdbcTemplate jdbcTemplate, boolean csrfExpected) {
        this.rest = rest;
        this.jdbcTemplate = jdbcTemplate;
        this.csrfExpected = csrfExpected;
    }

    /** 기본 test 체인({@code security-enabled=false} — permitAll + CSRF off)을 쓰는 테스트용. */
    public static IntegrationLoginSupport forTestChain(TestRestTemplate rest, JdbcTemplate jdbcTemplate) {
        return new IntegrationLoginSupport(rest, jdbcTemplate, false);
    }

    /** 운영 체인({@code security-enabled=true})을 켠 테스트용 — CSRF 토큰 발급을 단언한다. */
    public static IntegrationLoginSupport forProductionChain(TestRestTemplate rest, JdbcTemplate jdbcTemplate) {
        return new IntegrationLoginSupport(rest, jdbcTemplate, true);
    }

    // ---------------------------------------------------------------- 생명주기

    /** {@code @BeforeEach} 에서 부른다 — 이 테스트가 만들 로그인 이력의 경계를 잡는다. */
    public void markStart() {
        Long max = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(lgn_sn), 0) FROM parking.tb_lgn_hstry", Long.class);
        this.startSn = max == null ? 0L : max;
        this.grantedMenuIds.clear();
    }

    /** {@code @AfterEach} 에서 부른다 — 매핑·mock 계정·로그인 이력을 되돌린다. */
    public void cleanUp() {
        for (String menuId : grantedMenuIds) {
            jdbcTemplate.update(
                    "DELETE FROM parking.tb_authrt_menu WHERE authrt_cd = ? AND menu_id = ?",
                    MOCK_AUTHRT_CD, menuId);
        }
        grantedMenuIds.clear();
        jdbcTemplate.update("DELETE FROM parking.tb_lgn_hstry WHERE lgn_sn > ?", startSn);
        jdbcTemplate.update("DELETE FROM parking.tb_user WHERE user_id = ?", MOCK_ID);
    }

    // ---------------------------------------------------------------- 로그인

    /**
     * 세션 확립: {@code /actuator/health} 로 XSRF 를 받고 → (있으면) CSRF 헤더를 실어 {@code mock-login}.
     *
     * <p>반환 헤더는 {@code Cookie}(+ 운영 체인이면 {@code X-XSRF-TOKEN})를 담아 그대로 요청에 실을 수
     * 있다. 🔴 이 레포는 httpclient5 가 없어 {@code TestRestTemplate} 에 쿠키 저장소가 없다 —
     * {@code Set-Cookie} → {@code Cookie} 를 직접 이어 붙인다.
     */
    public HttpHeaders mockSession() {
        if (failedSessionAttempts >= MAX_SESSION_ATTEMPTS) {
            throw new AssertionError("mock 세션 연속 " + failedSessionAttempts
                    + "회 실패 — 더 요청하지 않는다. 테스트 서버 상태를 확인하세요.");
        }
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        String cookies = cookieHeader(health);
        String xsrf = xsrfOf(cookies);
        if (csrfExpected) {
            assertThat(xsrf).as("CsrfCookieFilter 가 XSRF-TOKEN 을 내려야 한다").isNotBlank();
        }

        HttpHeaders h = new HttpHeaders();
        if (!cookies.isEmpty()) {
            h.add(HttpHeaders.COOKIE, cookies);
        }
        if (hasText(xsrf)) {
            h.add("X-XSRF-TOKEN", xsrf);
        }

        ResponseEntity<Map> res = rest.exchange("/api/auth/mock-login", HttpMethod.POST,
                new HttpEntity<>(h), Map.class);
        if (res.getStatusCode() != HttpStatus.OK || !cookieHeader(res).contains("JSESSIONID")) {
            failedSessionAttempts++;
        }
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cookieHeader(res)).contains("JSESSIONID");
        failedSessionAttempts = 0;

        // 로그인 후 세션 ID 가 바뀌므로(changeSessionId) 새 쿠키로 갱신한다
        HttpHeaders session = new HttpHeaders();
        session.add(HttpHeaders.COOKIE,
                cookieHeader(res) + (hasText(xsrf) ? "; XSRF-TOKEN=" + xsrf : ""));
        if (hasText(xsrf)) {
            session.add("X-XSRF-TOKEN", xsrf);
        }
        return session;
    }

    /** {@link #mockSession()} + 대상 메뉴 부여를 한 번에. 재로그인은 필요 없다(매 요청 조회). */
    public HttpHeaders mockSessionWithMenus(String... menuIds) {
        HttpHeaders session = mockSession();
        for (String menuId : menuIds) {
            grantMenu(menuId);
        }
        return session;
    }

    /**
     * mock 계정의 롤({@code ROLE_USER})에 메뉴 매핑을 부여한다 — <b>재로그인 불필요</b>.
     *
     * <p>🔴 롤 승격({@code UPDATE tb_user})이 아니라 매핑 부여를 쓴다. "이 API 는 이 메뉴를 요구한다"는
     * Step 7-2 의 계약을 그대로 시험하기 때문이다. 시드에 이미 있던 행이면(= INSERT 0건) 정리 대상에
     * 넣지 않는다.
     */
    public void grantMenu(String menuId) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                VALUES (?, ?, 'test', now())
                ON CONFLICT DO NOTHING""", MOCK_AUTHRT_CD, menuId);
        if (inserted > 0) {
            grantedMenuIds.add(menuId);
        }
    }

    /**
     * mock 계정의 롤 자체를 바꾼다 — <b>재로그인 불필요</b>(표 파생 판정이 매 요청 조회다).
     *
     * <p>기본은 {@link #grantMenu(String)} 다("이 API 는 이 메뉴를 요구한다"는 계약을 그대로 시험한다).
     * 🔴 <b>테스트가 대상 메뉴의 매핑 자체를 갈아엎는 경우</b>에만 이 승격을 쓴다 —
     * {@code MenuAuthrtSuperIntegrationTest} 처럼 {@code PUT} 이 {@code M0602} 의 매핑을 통째로
     * 교체하면 첫 호출이 자기 권한을 지워 두 번째 호출이 403 이 되기 때문이다.
     * {@code TB_USER} 행은 {@link #cleanUp()} 이 통째로 지우므로 되돌릴 것이 없다.
     */
    public void promoteTo(String authrtCd) {
        int updated = jdbcTemplate.update(
                "UPDATE parking.tb_user SET user_authrt_cd = ? WHERE user_id = ?", authrtCd, MOCK_ID);
        assertThat(updated).as("mock 계정이 있어야 승격할 수 있다 — mockSession() 뒤에 부른다").isEqualTo(1);
    }

    /** 매핑을 회수한다 — "권한 없으면 403" 케이스용. */
    public void revokeMenu(String menuId) {
        jdbcTemplate.update("DELETE FROM parking.tb_authrt_menu WHERE authrt_cd = ? AND menu_id = ?",
                MOCK_AUTHRT_CD, menuId);
        grantedMenuIds.remove(menuId);
    }

    // ---------------------------------------------------------------- 요청 조립

    /** 세션 헤더 사본 + {@code Content-Type: application/json} (바디가 있는 요청용). */
    public HttpHeaders json(HttpHeaders session) {
        HttpHeaders h = new HttpHeaders();
        h.addAll(session);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** 바디 있는 요청 엔티티 (세션 + JSON). */
    public <T> HttpEntity<T> jsonEntity(HttpHeaders session, T body) {
        return new HttpEntity<>(body, json(session));
    }

    // ---------------------------------------------------------------- 쿠키

    public static String cookieHeader(ResponseEntity<?> res) {
        List<String> setCookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return "";
        }
        return String.join("; ", setCookies.stream().map(c -> c.split(";", 2)[0]).toList());
    }

    public static String xsrfOf(String cookieHeader) {
        for (String c : cookieHeader.split("; ")) {
            if (c.startsWith("XSRF-TOKEN=")) {
                return c.substring("XSRF-TOKEN=".length());
            }
        }
        return null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
