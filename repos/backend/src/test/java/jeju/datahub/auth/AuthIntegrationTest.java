package jeju.datahub.auth;

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
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인증 엔드포인트 통합테스트 (②).
 *
 * <p>하네스({@code SPRING_PROFILES_ACTIVE=test}, {@code app.auth.mode=mock},
 * {@code security-enabled=false}) 위에서 돈다 — 테스트 체인은 {@code permitAll} 이라
 * <b>인가가 아니라 컨트롤러 로직</b>을 고정한다(인증·CSRF 회귀는 전용 테스트가 맡는다).
 *
 * <p>🔴 spring-security-test 의 모의 사용자 애노테이션을 쓰지 않는다(의존성 자체가 없다) —
 * 이 레포는 {@code RANDOM_PORT}+{@code TestRestTemplate} 이라 테스트 스레드의
 * {@code SecurityContextHolder} 를 채워도 서버 필터체인에 닿지 않는다.
 * 대신 {@code mock-login} 으로 실제 세션을 만든다.
 *
 * <p>🔴 이 레포는 httpclient5 의존성이 없어 {@code TestRestTemplate} 이
 * {@code SimpleClientHttpRequestFactory} 로 돌고 <b>쿠키 저장소가 없다</b> —
 * {@code Set-Cookie} 를 꺼내 다음 요청의 {@code Cookie} 헤더에 직접 실어야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 구현이 리터럴 상수로 못박은 mock 식별자 */
    private static final String MOCK_ID = "mock-user";

    /** 테스트가 만들고 지우는 부서 행. 실 IdP 슬러그와 겹치지 않게 접두를 단다(30자 이내) */
    private static final String TEST_DEPT_CD = "tsk4261-test-dept";
    private static final String TEST_DEPT_NM = "TESTDATA 테스트 부서";

    /**
     * 이 테스트가 만든 로그인 이력 행의 경계.
     *
     * <p>mock-login/logout 에 이력 기록이 끼워지면서 이 테스트도 부수적으로
     * {@code TB_LGN_HSTRY} 행을 남기게 됐다. {@code RANDOM_PORT} 는 별 스레드·별 커넥션이라
     * {@code @Transactional} 롤백이 먹지 않아 실제로 커밋된다 — 지우지 않으면 실행할 때마다 쌓인다.
     */
    private long startSn;

    @BeforeEach
    void markStart() {
        startSn = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(lgn_sn), 0) FROM parking.tb_lgn_hstry", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM parking.tb_user WHERE user_id = ?", MOCK_ID);
        // 🔴 FK(fk_tb_user_dept) 때문에 사용자 행 뒤에 지운다
        jdbcTemplate.update("DELETE FROM parking.tb_dept WHERE dept_cd = ?", TEST_DEPT_CD);
        // 🔴 이 테스트가 만든 행만 지운다. `OR user_id IS NULL` 로 넓히지 않는다 —
        //    실패 행은 user_id 가 null 이라 테스트가 만들지 않은 실 실패 이력까지 지운다.
        jdbcTemplate.update("DELETE FROM parking.tb_lgn_hstry WHERE lgn_sn > ?", startSn);
    }

    /** Set-Cookie 를 다음 요청의 Cookie 헤더로 이어 붙인다 (쿠키 저장소가 없다). */
    private static HttpHeaders cookiesOf(ResponseEntity<?> res) {
        HttpHeaders h = new HttpHeaders();
        List<String> setCookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies != null) {
            h.add(HttpHeaders.COOKIE, String.join("; ",
                    setCookies.stream().map(c -> c.split(";", 2)[0]).toList()));
        }
        return h;
    }

    private ResponseEntity<Map> mockLogin() {
        ResponseEntity<Map> res = rest.exchange("/api/auth/mock-login", HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res;
    }

    @Test
    @DisplayName("1. mock-login 이 세션을 만들고 TB_USER 행을 커밋한다")
    void mockLoginCreatesSession() {
        ResponseEntity<Map> res = mockLogin();

        assertThat(res.getBody()).containsEntry("userId", MOCK_ID);
        assertThat(res.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anySatisfy(c -> assertThat(c).contains("JSESSIONID"));
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_user WHERE user_id = ?", Integer.class, MOCK_ID);
        assertThat(cnt).isEqualTo(1);
    }

    @Test
    @DisplayName("2. 그 세션으로 /me 가 6필드를 돌려준다 (userAuthrtCd — authrtCd 가 아니다)")
    void meReturnsLoginUser() {
        HttpHeaders cookies = cookiesOf(mockLogin());

        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(cookies), Map.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsKeys(
                "userId", "userNm", "userEmlAddr", "userAuthrtCd", "authrtNm", "menuPathNms");
        // 🔴 필드명은 authrtCd 가 아니라 userAuthrtCd 다
        assertThat(me.getBody()).doesNotContainKey("authrtCd");
        assertThat(me.getBody().get("userAuthrtCd")).isEqualTo("ROLE_USER");
        assertThat(me.getBody().get("authrtNm")).isEqualTo("일반 사용자");
    }

    @Test
    @DisplayName("2-1. 부서가 있으면 /me 의 deptNm 이 tb_dept.dept_nm 이다")
    void meReturnsDeptNm() {
        HttpHeaders cookies = cookiesOf(mockLogin());
        jdbcTemplate.update("INSERT INTO parking.tb_dept (dept_cd, dept_nm, rgtr_id, reg_dt) "
                + "VALUES (?, ?, 'test', now())", TEST_DEPT_CD, TEST_DEPT_NM);
        jdbcTemplate.update("UPDATE parking.tb_user SET dept_cd = ? WHERE user_id = ?", TEST_DEPT_CD, MOCK_ID);

        // /me 는 매 요청 selectLoginUser 를 다시 돌리므로 재로그인 없이 반영된다
        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(cookies), Map.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("deptNm")).isEqualTo(TEST_DEPT_NM);
    }

    @Test
    @DisplayName("2-2. 🔴 dept_cd 가 NULL 이어도 /me 는 200 + deptNm null 이다 (LEFT JOIN 회귀 방지)")
    void meReturnsNullDeptNmWhenNoDept() {
        HttpHeaders cookies = cookiesOf(mockLogin());
        jdbcTemplate.update("UPDATE parking.tb_user SET dept_cd = NULL WHERE user_id = ?", MOCK_ID);

        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(cookies), Map.class);

        // 401 이면 tb_dept 가 LEFT JOIN 이 아니라 JOIN 으로 회귀한 것이다
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsKey("deptNm");
        assertThat(me.getBody().get("deptNm")).isNull();
    }

    @Test
    @DisplayName("3. 🔴 /me 의 menuPathNms 가 ROLE_USER 10개다 (DISTINCT 적용)")
    void menuPathNmsIsDistinct() {
        HttpHeaders cookies = cookiesOf(mockLogin());
        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(cookies), Map.class);

        List<?> paths = (List<?>) me.getBody().get("menuPathNms");
        assertThat(paths).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("4. 🔴 승격하면 재로그인 없이 곧바로 menuPathNms 15개가 된다")
    void promotionReflectsWithoutRelogin() {
        HttpHeaders cookies = cookiesOf(mockLogin());
        jdbcTemplate.update(
                "UPDATE parking.tb_user SET user_authrt_cd = 'ROLE_SYSTEM_ADMIN' WHERE user_id = ?", MOCK_ID);

        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(cookies), Map.class);

        // /me 는 매 요청 selectLoginUser 를 다시 돌리므로 세션을 끊을 필요가 없다
        assertThat(me.getBody().get("userAuthrtCd")).isEqualTo("ROLE_SYSTEM_ADMIN");
        // 🔴 2026-09-03 탭 경로 분리로 행 14 = 경로 14 가 됐다(숨김 메뉴 M0604 로 15).
        //    따라서 이 단언은 더 이상 AuthMapper.xml 의 SELECT DISTINCT 회귀를 잡지 못한다
        //    (DISTINCT 를 지워도 15 가 나온다). DISTINCT 는 지금 사실상 no-op 이지만,
        //    한 메뉴가 다시 경로를 공유할 때를 대비해 남겨 둔다.
        assertThat((List<?>) me.getBody().get("menuPathNms")).hasSize(15);
    }

    @Test
    @DisplayName("5. 🔴 use_yn='N' 인 잔존 세션의 /me 는 401 이다 (500 이면 null 분기 누락)")
    void meRejectsDisabledUser() {
        HttpHeaders cookies = cookiesOf(mockLogin());
        jdbcTemplate.update("UPDATE parking.tb_user SET use_yn = 'N' WHERE user_id = ?", MOCK_ID);

        ResponseEntity<String> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(cookies), String.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("6. 🔴 logout 은 200 + idpLogoutUrl null 이다 (mock 엔 id_token 이 없다)")
    void logoutReturnsNullIdpUrlOnMock() {
        HttpHeaders cookies = cookiesOf(mockLogin());

        ResponseEntity<Map> out = rest.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(cookies), Map.class);

        // 500 이면 Map.of(null) NPE 회귀이거나 (OidcUser) 캐스팅 회귀다
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(out.getBody()).containsKey("idpLogoutUrl");
        assertThat(out.getBody().get("idpLogoutUrl")).isNull();
        // 만료 쿠키를 직접 내려야 브라우저의 JSESSIONID 가 지워진다
        assertThat(out.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anySatisfy(c -> assertThat(c).contains("JSESSIONID=").contains("Max-Age=0"));
    }

    @Test
    @DisplayName("7. 로그아웃한 세션으로 /me 는 401 이다 (세션 무효화 확인)")
    void meAfterLogoutIsUnauthorized() {
        HttpHeaders cookies = cookiesOf(mockLogin());
        rest.exchange("/api/auth/logout", HttpMethod.POST, new HttpEntity<>(cookies), Map.class);

        ResponseEntity<String> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(cookies), String.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("9. 🔴 세션과 다른 state 로 콜백하면 400 이다 (state 위조 차단)")
    void callbackRejectsForgedState() {
        // authorize-url 로 세션에 state·verifier 를 심는다
        ResponseEntity<Map> authz = rest.exchange("/api/auth/authorize-url", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(authz.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders h = cookiesOf(authz);
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> res = rest.exchange("/api/auth/callback", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"dummy\",\"state\":\"forged-state\"}", h), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("10. 🔴 같은 state 를 두 번 쓰면 두 번째가 400 이다 (대조 직후 세션에서 제거)")
    void callbackRejectsReusedState() {
        ResponseEntity<Map> authz = rest.exchange("/api/auth/authorize-url", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        String url = (String) authz.getBody().get("authorizeUrl");
        String state = UriComponentsBuilder.fromUriString(url).build()
                .getQueryParams().getFirst("state");
        assertThat(state).isNotNull().hasSizeGreaterThanOrEqualTo(43);   // 1.3절 — 32바이트 이상

        HttpHeaders h = cookiesOf(authz);
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":\"dummy\",\"state\":\"" + state + "\"}";

        // 1회차: state 는 통과하고 토큰 교환에서 실패한다(가짜 code) → 401. 400 이면 state 검증 회귀다
        ResponseEntity<String> first = rest.exchange("/api/auth/callback", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 2회차: 같은 state 는 세션에서 이미 지워졌다
        ResponseEntity<String> second = rest.exchange("/api/auth/callback", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("8. 미인증 /me 는 401 이다 (테스트 체인이 permitAll 이라 컨트롤러가 직접 막는다)")
    void meWithoutSessionIsUnauthorized() {
        ResponseEntity<String> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
