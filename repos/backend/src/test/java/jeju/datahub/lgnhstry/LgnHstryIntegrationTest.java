package jeju.datahub.lgnhstry;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.common.SesnIdHasher;

/**
 * 로그인 이력 적재 통합테스트 (TSK-4104 Step 8) — mock 경로 3지점 + XFF 회귀.
 *
 * <p>🔴 <b>MockMvc 를 쓰지 않는다.</b> 이 레포에는 MockMvc 가 0건이고 spring-security-test 도 없다
 * (TSK-4076 Step 2·6). {@code RANDOM_PORT} + {@code TestRestTemplate} 이며, httpclient5 가 없어
 * {@code SimpleClientHttpRequestFactory} 로 돌아 <b>쿠키 저장소가 없다</b> —
 * {@code Set-Cookie} → {@code Cookie} 를 테스트가 직접 이어 붙이고 XSRF 토큰을 헤더로 실어 보낸다
 * ({@code AuthSecurityIntegrationTest} 와 같은 규약. 그 파일을 건드리지 않으려고 헬퍼만 사본으로 둔다).
 *
 * <p>🔴 {@code properties = "app.auth.security-enabled=true"} 로 <b>운영 필터체인</b>을 켠다 —
 * {@code TestSecurityConfig}({@code havingValue="false"})가 꺼지고 CSRF·세션 복원이 실제 경로로 돈다.
 * ({@code app.auth.mode=mock} 은 {@code application-test.yml} 에서 상속한다.)
 *
 * <p>🔴 <b>{@code @Transactional} 롤백이 안 먹는다</b> — {@code RANDOM_PORT} 는 서버가 별 스레드·별
 * 커넥션으로 돌아 테스트 트랜잭션 바깥에서 커밋한다. 그래서 {@link #cleanUpHistory()} 가
 * <b>이 테스트가 만든 행만</b>({@code lgn_sn > startSn}) DELETE 한다.
 * 🔴 {@code OR user_id IS NULL} 로 넓히지 <b>않는다</b> — 실패 행은 {@code user_id} 가 null 일 수 있어
 * (D2) 테스트가 만들지 않은 실 실패 이력까지 지운다.
 *
 * <p>🔴 {@code use_yn} 은 반드시 {@code 'Y'} 로 되돌린다 — 되돌리지 않으면 다음 테스트와 로컬
 * 개발의 mock 로그인이 전부 막힌다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.security-enabled=true")
class LgnHstryIntegrationTest {

    /** {@code MockAuthController} 가 리터럴 상수로 못박은 mock 식별자 */
    private static final String MOCK_ID = "mock-user";

    /** 테스트용 XFF 값 — RFC 5737 문서용 대역(TEST-NET-3)이라 실 IP 와 겹치지 않는다. */
    private static final String XFF_IP = "203.0.113.9";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 이 테스트가 만든 행의 경계. 이 값보다 큰 lgn_sn 만 정리한다. */
    private long startSn;

    @BeforeEach
    void markStart() {
        startSn = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(lgn_sn), 0) FROM parking.tb_lgn_hstry", Long.class);
    }

    @AfterEach
    void cleanUpHistory() {
        // 🔴 정지 해제가 먼저다 — DELETE 가 실패해도 use_yn 은 반드시 되돌아가야 한다.
        jdbcTemplate.update("UPDATE parking.tb_user SET use_yn = 'Y' WHERE user_id = ?", MOCK_ID);
        jdbcTemplate.update("DELETE FROM parking.tb_lgn_hstry WHERE lgn_sn > ?", startSn);
    }

    // ---------------------------------------------------------------- helper

    /** 쿠키 저장소가 없어 Set-Cookie 를 직접 이어 붙인다. */
    private static String cookieHeader(ResponseEntity<?> res) {
        List<String> setCookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return "";
        }
        return String.join("; ", setCookies.stream().map(c -> c.split(";", 2)[0]).toList());
    }

    private static String cookieValue(String cookieHeader, String name) {
        for (String c : cookieHeader.split("; ")) {
            if (c.startsWith(name + "=")) {
                return c.substring(name.length() + 1);
            }
        }
        return null;
    }

    /** {@code /actuator/health} 로 XSRF 쿠키를 받아 CSRF 헤더까지 갖춘 요청 헤더를 만든다. */
    private HttpHeaders csrfHeaders() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        String cookies = cookieHeader(health);
        String xsrf = cookieValue(cookies, "XSRF-TOKEN");
        assertThat(xsrf).as("CsrfCookieFilter 가 XSRF-TOKEN 을 내려야 한다").isNotBlank();

        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, cookies);
        h.add("X-XSRF-TOKEN", xsrf);
        return h;
    }

    /**
     * mock-login 왕복. {@code xff} 가 null 이 아니면 {@code X-Forwarded-For} 를 실어 보낸다
     * (⚠️ {@code RANDOM_PORT} 는 루프백 직접 연결이라 {@code RemoteIpValve} 의 {@code internalProxies}
     * 기본 정규식에 {@code 127.0.0.1} 이 포함돼 XFF 가 채택된다 — D4 의 한계가 여기서는 검증 수단이다).
     */
    private ResponseEntity<Map> mockLogin(String xff) {
        HttpHeaders h = csrfHeaders();
        if (xff != null) {
            h.add("X-Forwarded-For", xff);
        }
        return rest.exchange("/api/auth/mock-login", HttpMethod.POST, new HttpEntity<>(h), Map.class);
    }

    /** 로그인 응답의 새 JSESSIONID(=changeSessionId 이후) 로 이후 요청용 세션 헤더를 만든다. */
    private static HttpHeaders sessionOf(ResponseEntity<?> loginRes, HttpHeaders loginReqHeaders) {
        String xsrf = loginReqHeaders.getFirst("X-XSRF-TOKEN");
        HttpHeaders session = new HttpHeaders();
        session.add(HttpHeaders.COOKIE, cookieHeader(loginRes) + "; XSRF-TOKEN=" + xsrf);
        session.add("X-XSRF-TOKEN", xsrf);
        return session;
    }

    /** 이 테스트가 만든 이력 행만 lgn_sn 오름차순으로 읽는다. */
    private List<Map<String, Object>> newRows() {
        return jdbcTemplate.queryForList(
                "SELECT * FROM parking.tb_lgn_hstry WHERE lgn_sn > ? ORDER BY lgn_sn", startSn);
    }

    // ---------------------------------------------------------------- 1. 성공

    @Test
    @DisplayName("1. mock-login → 성공 행 1건 · lgn_yn='Y' · 🔴 sesn_id 는 세션 원문이 아니다(해시 16자)")
    void mockLoginRecordsSuccessRowWithHashedSessionId() {
        HttpHeaders req = csrfHeaders();
        ResponseEntity<Map> res = rest.exchange("/api/auth/mock-login", HttpMethod.POST,
                new HttpEntity<>(req), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        String rawSessionId = cookieValue(cookieHeader(res), "JSESSIONID");
        assertThat(rawSessionId).as("changeSessionId 후 새 JSESSIONID 가 내려와야 한다").isNotBlank();

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("user_id", MOCK_ID).containsEntry("lgn_yn", "Y");
        assertThat(row.get("lgn_fail_rsn")).isNull();
        assertThat(row.get("cntn_end_dt")).as("로그인 직후에는 열려 있다").isNull();

        String sesnId = (String) row.get("sesn_id");
        // 🔴 D5 — 원문을 저장하지 않는다. 원문과 다르고, 해시 규격(hex 소문자 16자)이며,
        //    실제로 그 세션 원문의 SesnIdHasher.shortHash 여야 한다(closeSession 매칭 계약).
        assertThat(sesnId).isNotEqualTo(rawSessionId);
        assertThat(sesnId).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(sesnId).isEqualTo(SesnIdHasher.shortHash(rawSessionId));
    }

    // ---------------------------------------------------------------- 2. 로그아웃

    @Test
    @DisplayName("2. 같은 세션 logout → 그 행의 cntn_end_dt 채워짐 · 🔴 새 행이 생기지 않는다")
    void logoutClosesTheSameRow() {
        HttpHeaders req = csrfHeaders();
        ResponseEntity<Map> login = rest.exchange("/api/auth/mock-login", HttpMethod.POST,
                new HttpEntity<>(req), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> afterLogin = newRows();
        assertThat(afterLogin).hasSize(1);
        Object lgnSn = afterLogin.get(0).get("lgn_sn");
        assertThat(afterLogin.get(0).get("cntn_end_dt")).isNull();

        ResponseEntity<Map> out = rest.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(sessionOf(login, req)), Map.class);
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> afterLogout = newRows();
        // 🔴 새 행이 아니라 **그 행**이 닫혀야 한다 — count 로 고정한다
        assertThat(afterLogout).hasSize(1);
        assertThat(afterLogout.get(0).get("lgn_sn")).isEqualTo(lgnSn);
        assertThat(afterLogout.get(0).get("cntn_end_dt")).as("로그아웃 시각이 채워진다").isNotNull();
    }

    // ---------------------------------------------------------------- 3. 정지 사용자

    @Test
    @DisplayName("3. use_yn='N' 사용자 → 실패 행 lgn_yn='N' · lgn_fail_rsn='USER_DISABLED'")
    void disabledUserRecordsFailureRow() {
        // TB_USER 의 mock-user 행은 mock-login 이 upsert 로 만든다 — 먼저 만들고 나서 정지시킨다.
        assertThat(mockLogin(null).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbcTemplate.update("UPDATE parking.tb_user SET use_yn = 'N' WHERE user_id = ?", MOCK_ID);

        ResponseEntity<Map> denied = mockLogin(null);
        assertThat(denied.getStatusCode().is2xxSuccessful())
                .as("정지 사용자는 세션을 받지 못한다").isFalse();

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(2);                     // 성공 1 + 실패 1
        Map<String, Object> fail = rows.get(1);
        assertThat(fail).containsEntry("lgn_yn", "N")
                .containsEntry("lgn_fail_rsn", "USER_DISABLED")
                .containsEntry("user_id", MOCK_ID);
        assertThat(fail.get("sesn_id")).as("실패에는 확정된 세션이 없다").isNull();
        // @AfterEach 가 use_yn 을 'Y' 로 되돌린다 — 되돌리지 않으면 이후 모든 mock 로그인이 막힌다.
    }

    // ---------------------------------------------------------------- 4. XFF 회귀

    @Test
    @DisplayName("4. 🔴 X-Forwarded-For 를 실어 mock-login → lgn_ip_addr 이 XFF 값 (RemoteIpValve 회귀)")
    void forwardedForIsStoredAsLoginIp() {
        assertThat(mockLogin(XFF_IP).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = newRows();
        assertThat(rows).hasSize(1);
        // forward-headers-strategy: NATIVE 가 켠 RemoteIpValve 가 복원한 값이 그대로 적재돼야 한다.
        // 127.0.0.1 이 나오면 그 설정이 빠졌거나 기록 시점이 밸브 앞으로 옮겨간 회귀다.
        assertThat(rows.get(0)).containsEntry("lgn_ip_addr", XFF_IP);
    }
}
