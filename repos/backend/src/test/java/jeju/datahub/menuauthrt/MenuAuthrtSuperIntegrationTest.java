package jeju.datahub.menuauthrt;

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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.support.IntegrationLoginSupport;

/**
 * {@code SUPER} 권한 자동 보정 통합 테스트 (TSK-4115 Step 2 / D7 재개정).
 *
 * <p>고정하는 계약은 한 문장이다 — <b>"{@code SUPER} 유형 권한은 어떤 요청으로도 메뉴에서 빠지지 않고,
 * 저장은 항상 성공한다"</b>. 400 으로 거절하지 않고 저장 직전에 조용히 더하므로, 요청 본문과 저장 결과가
 * 다를 수 있다(응답이 저장 결과를 돌려주므로 화면은 즉시 올바른 상태를 본다).
 *
 * <p>🔴 <b>테스트도 롤 이름을 기대값으로 쓰지 않는다.</b> 어떤 권한이 {@code SUPER} 인지는
 * {@code TB_AUTHRT.authrt_type_cd} 에서 읽어 온다 — 권한 이름이 바뀌어도 이 테스트는 그대로 돈다.
 * ({@code 'SUPER'} 라는 값 자체는 D7 이 수용한 코드 상수다.)
 *
 * <p>운영 필터체인을 켠 채({@code app.auth.security-enabled=true}) 로그인 세션으로 도는 이유는, 실제
 * 화면이 거치는 경로(세션 쿠키 + {@code X-XSRF-TOKEN})에서 200 이 그대로 나오는지까지 고정하기
 * 위해서다. 익명 {@code PUT} 회귀는 {@code MenuAuthrtIntegrationTest} 가 그대로 담당한다.
 *
 * <p>🔴 {@code RANDOM_PORT} 는 롤백이 걸리지 않는다 — {@link #restoreSeed()} 가 매핑 40행 복원 +
 * {@code mock-user} 의 {@code TB_USER} 행 삭제 + 이 테스트가 만든 로그인 이력 삭제까지 수동으로 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.security-enabled=true")
class MenuAuthrtSuperIntegrationTest {

    /** 편집 대상 화면 — "메뉴 권한 관리" 화면 자체(M0602). 자기 창구를 갈아엎어도 SUPER 는 남아야 한다. */
    private static final String TARGET_MENU = "M0602";
    /** 필요한 곳에서만 쓰는 비-SUPER 권한 (서비스 관리자) */
    private static final String PLAIN_AUTHRT = "ROLE_SERVICE_ADMIN";

    private static final String MENUS_URL = "/api/menu-authorities/menus";
    private static final String AUTHORITIES_URL = "/api/menu-authorities/authorities";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 공용 로그인 헬퍼 (TSK-4115 Step 7-1). 운영 체인을 켠 클래스라 {@code forProductionChain} 이다. */
    private IntegrationLoginSupport auth;

    /** DB 가 알려 주는 SUPER 유형 권한코드 목록 — 롤 이름을 테스트에 박지 않기 위한 출처다. */
    private List<String> superCds;

    @BeforeEach
    void markStart() {
        auth = IntegrationLoginSupport.forProductionChain(rest, jdbcTemplate);
        auth.markStart();
        superCds = jdbcTemplate.queryForList(
                "SELECT authrt_cd FROM parking.tb_authrt WHERE authrt_type_cd = 'SUPER' AND use_yn = 'Y'",
                String.class);
        assertThat(superCds).as("시드에 SUPER 유형 권한이 최소 1건 있어야 한다 (Step 0-C)").isNotEmpty();
    }

    /**
     * 매핑 테이블을 시드 상태(40행)로 되돌리고, 로그인이 남긴 흔적을 지운다.
     *
     * <p>세 INSERT 의 WHERE 조건은 시드({@code database/migrations/sql/seeding/02-TB_MENU_AUTHRT.sql})와
     * 동일하다 — 시스템 관리자분은 이름이 아니라 <b>유형 기준</b>이다. {@code TB_AUTHRT} 는 저장 경로가
     * 건드리지 않으므로 복원 대상이 아니다.
     */
    @AfterEach
    void restoreSeed() {
        jdbcTemplate.update("DELETE FROM parking.tb_authrt_menu");
        jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                SELECT a.authrt_cd, m.menu_id, 'system', now()
                  FROM parking.tb_authrt a
                 CROSS JOIN parking.tb_menu m
                 WHERE a.authrt_type_cd = 'SUPER' AND a.use_yn = 'Y'
                   AND m.menu_path_nm IS NOT NULL""");
        jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                SELECT 'ROLE_SERVICE_ADMIN', menu_id, 'system', now()
                  FROM parking.tb_menu
                 WHERE menu_path_nm IS NOT NULL""");
        jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                SELECT 'ROLE_USER', menu_id, 'system', now()
                  FROM parking.tb_menu
                 WHERE menu_path_nm IS NOT NULL
                   AND (up_menu_id IS DISTINCT FROM 'M06')
                   AND menu_id NOT IN ('M0402', 'M0404')""");

        // mock 계정(승격분 포함)·로그인 이력 정리는 공용 헬퍼가 진다 (TSK-4115 Step 7-1).
        // 🔴 `OR user_id IS NULL` 로 넓히지 않는다 — 실패 행은 user_id 가 null 이라
        //    테스트가 만들지 않은 실 실패 이력까지 지운다 (TSK-4104 Step 8).
        auth.cleanUp();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_authrt_menu", Integer.class))
                .as("복원 후 시드 40행이어야 한다").isEqualTo(40);
    }

    // ---------------------------------------------------------------- 자동 보정

    @Test
    @DisplayName("1. 🔴 SUPER 를 뺀 PUT → 200, 저장 결과(응답 · DB)에 SUPER 가 도로 들어가 있다")
    void omittedSuperAuthorityIsRestored() {
        HttpHeaders session = mockSession();

        ResponseEntity<Map> res = put(session, TARGET_MENU, List.of(PLAIN_AUTHRT), Map.class);

        // 400 이면 종전의 '필수 권한 제거 거절' 가드가 남아 있는 것이다 — D7 재개정은 자동 보정이다
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(authrtCdsOf(res)).containsAll(superCds).contains(PLAIN_AUTHRT);
        assertThat(authoritiesInDb(TARGET_MENU)).containsAll(superCds).contains(PLAIN_AUTHRT);
    }

    @Test
    @DisplayName("2. 빈 배열 PUT → 200, 저장 결과는 SUPER 뿐이다 (매핑이 완전히 비지 않는다)")
    void emptyRequestStillKeepsSuperAuthority() {
        HttpHeaders session = mockSession();

        ResponseEntity<Map> res = put(session, TARGET_MENU, List.of(), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authrtCdsOf(res)).containsExactlyInAnyOrderElementsOf(superCds);
        assertThat(authoritiesInDb(TARGET_MENU)).containsExactlyInAnyOrderElementsOf(superCds);
    }

    @Test
    @DisplayName("3. SUPER 를 포함한 PUT → 200, 중복 없이 그대로 (복합 PK 충돌 없음)")
    void requestAlreadyContainingSuperIsNotDuplicated() {
        HttpHeaders session = mockSession();

        ResponseEntity<Map> res = put(session, TARGET_MENU, withSuper(PLAIN_AUTHRT), Map.class);

        // 500 이면 LinkedHashSet 중복 제거가 빠져 복합 PK (authrt_cd, menu_id) 가 충돌한 것이다
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authrtCdsOf(res))
                .containsExactlyInAnyOrderElementsOf(withSuper(PLAIN_AUTHRT))
                .doesNotHaveDuplicates();
        assertThat(authoritiesInDb(TARGET_MENU))
                .containsExactlyInAnyOrderElementsOf(withSuper(PLAIN_AUTHRT));
    }

    @Test
    @DisplayName("4. 🔴 연속 3회 같은 저장 → 3회 모두 200 · 매핑 수 동일 (멱등)")
    void repeatedSaveIsIdempotent() {
        HttpHeaders session = mockSession();
        List<String> requested = List.of(PLAIN_AUTHRT);   // SUPER 를 뺀 요청 = 매번 보정이 도는 경로

        for (int i = 1; i <= 3; i++) {
            ResponseEntity<Map> res = put(session, TARGET_MENU, requested, Map.class);
            assertThat(res.getStatusCode()).as("%d회차", i).isEqualTo(HttpStatus.OK);
            assertThat(authoritiesInDb(TARGET_MENU)).as("%d회차", i)
                    .containsExactlyInAnyOrderElementsOf(withSuper(PLAIN_AUTHRT));
        }
    }

    // ---------------------------------------------------------------- 기존 판정 회귀

    @Test
    @DisplayName("5. 🔴 없는 메뉴는 404 · 폴더 메뉴는 400 그대로 — 보정이 앞지르지 않는다")
    void unknownMenuStays404AndFolderMenuStays400() {
        HttpHeaders session = mockSession();

        assertThat(put(session, "M99", List.of(PLAIN_AUTHRT), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(session, "M03", List.of(PLAIN_AUTHRT), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // 보정이 판정보다 먼저 돌았다면 없는/폴더 메뉴에 SUPER 매핑이 생겼을 것이다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_authrt_menu WHERE menu_id IN ('M99', 'M03')",
                Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- 조회 계약

    @Test
    @DisplayName("6. GET /authorities 에 authrtTypeCd 가 실린다 — SUPER 권한만 'SUPER', 나머지는 null")
    void authorityListCarriesType() {
        HttpHeaders session = mockSession();

        ResponseEntity<List<Map<String, Object>>> res = rest.exchange(AUTHORITIES_URL, HttpMethod.GET,
                new HttpEntity<>(session), new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> authorities = res.getBody();
        assertThat(authorities).isNotNull().hasSize(3);

        assertThat(authorities).filteredOn(a -> "SUPER".equals(a.get("authrtTypeCd")))
                .extracting(a -> (String) a.get("authrtCd"))
                .containsExactlyInAnyOrderElementsOf(superCds);
        assertThat(authorities).filteredOn(a -> !superCds.contains(a.get("authrtCd")))
                .allSatisfy(a -> assertThat(a.get("authrtTypeCd")).isNull());
    }

    @Test
    @DisplayName("7. 🔴 GET /menus 의 authrtCds 는 여전히 문자열 배열이다 (컬렉션 매핑 무손상)")
    void menuListAuthrtCdsStayStringArray() {
        HttpHeaders session = mockSession();

        ResponseEntity<List<Map<String, Object>>> res = rest.exchange(MENUS_URL, HttpMethod.GET,
                new HttpEntity<>(session), new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> menus = res.getBody();
        assertThat(menus).isNotNull().hasSize(15);

        for (Map<String, Object> menu : menus) {
            assertThat((List<?>) menu.get("authrtCds")).doesNotContainNull()
                    .allSatisfy(cd -> assertThat(cd).isInstanceOf(String.class));
            // SUPER 는 시드가 전 화면에 걸어 둔다 (Step 0-C — 자동 보정은 저장할 때만 돈다)
            @SuppressWarnings("unchecked")
            List<String> cds = (List<String>) menu.get("authrtCds");
            assertThat(cds).as("%s", menu.get("menuId")).containsAll(superCds);
        }
    }

    // ---------------------------------------------------------------- 헬퍼

    /** 요청 목록에 SUPER 를 더한 기대값 (중복 없음). */
    private List<String> withSuper(String... authrtCds) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(List.of(authrtCds));
        merged.addAll(superCds);
        return List.copyOf(merged);
    }

    private List<String> authoritiesInDb(String menuId) {
        return jdbcTemplate.queryForList(
                "SELECT authrt_cd FROM parking.tb_authrt_menu WHERE menu_id = ?", String.class, menuId);
    }

    @SuppressWarnings("unchecked")
    private List<String> authrtCdsOf(ResponseEntity<Map> res) {
        return (List<String>) res.getBody().get("authrtCds");
    }

    private <T> ResponseEntity<T> put(HttpHeaders session, String menuId,
                                      List<String> authrtCds, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(session);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of("authrtCds", authrtCds), headers);
        return rest.exchange(MENUS_URL + "/" + menuId + "/authorities", HttpMethod.PUT,
                request, responseType);
    }

    // --- 인증 (TSK-4115 Step 7-1 공용 헬퍼)

    /**
     * 세션 확립 + <b>롤 승격</b>.
     *
     * <p>🔴 매핑 부여가 아니라 승격을 쓴다 — 이 클래스의 {@code PUT} 은 요구 메뉴({@code M0602})의
     * 매핑을 통째로 교체하므로, {@code ROLE_USER} 에 매핑을 부여해 두면 <b>첫 호출이 자기 권한을
     * 지워</b> 테스트 4(연속 3회)의 두 번째 호출이 403 이 된다. 승격 대상은 SUPER 권한이라
     * 자동 보정 덕에 어떤 요청 본문에서도 {@code M0602} 매핑을 잃지 않는다 — 판정은 여전히
     * 표 파생이다({@code TB_AUTHRT_MENU} 를 매 요청 조회).
     *
     * <p>✅ 승격 뒤 <b>재로그인하지 않는다</b> — 판정이 세션에 굳은 authority 가 아니기 때문이다.
     */
    private HttpHeaders mockSession() {
        HttpHeaders session = auth.mockSession();
        auth.promoteTo(superCds.get(0));   // 🔴 롤 이름을 박지 않는다 — DB 가 알려 준 SUPER 권한
        return session;
    }
}
