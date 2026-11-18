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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import jeju.datahub.menuauthrt.service.AuthrtVO;
import jeju.datahub.menuauthrt.service.MenuAuthrtVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 메뉴 권한 관리 API 통합 테스트 (TSK-4019 Step 5).
 *
 * <p><b>왜 {@code @Transactional} 롤백을 쓰지 않는가</b> — {@code RANDOM_PORT} 는 요청을 별도 스레드·별도
 * 커넥션으로 처리하므로 테스트 트랜잭션이 서버 쪽 쓰기를 감싸지 못한다(롤백이 걸리지 않는다). 더구나 롤백이
 * 걸리는 {@code MockMvc + @Transactional} 조합을 택하면 <b>테스트 3(트랜잭션 밖 재조회)</b>이 성립하지 않아
 * 정작 잡으려던 stale-read 버그를 못 잡는다. 그래서 "실제 커밋 + {@link #restoreSeed()} 수동 복원"을 쓴다.
 *
 * <p>로컬 PostgreSQL({@code ./local.sh infra})을 그대로 쓴다 — 모든 프로파일이 PostgreSQL 이고 H2 는 없다.
 *
 * <p>응답 바디의 {@code message} 문자열은 단언하지 않는다 — 로컬에서는 devtools 가
 * {@code server.error.include-message=always} 로 덮어써 보이지만 jar 배포에는 devtools 가 없어 사라진다.
 * 환경에 따라 깨지는 단언이 되므로 <b>상태코드만</b> 단언한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MenuAuthrtIntegrationTest {

    private static final String AUTHORITIES_URL = "/api/menu-authorities/authorities";
    private static final String MENUS_URL = "/api/menu-authorities/menus";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 🔴 TSK-4115 Step 7 — {@code PUT /menus/{menuId}/authorities} 가 {@code M0602}(메뉴 권한 관리)
     * 매핑을 요구하게 됐다({@code @PreAuthorize}). 메서드 보안은 test 체인의 {@code permitAll} 과
     * 무관하게 익명 호출을 403 으로 막으므로 3·4·5·6 의 {@code PUT} 이 세션을 타야 한다.
     * 조회(1·2)는 종전대로 익명이다 — 쓰기 API 만 잠갔다.
     *
     * <p>🔴 여기서는 <b>매핑 부여가 아니라 롤 승격</b>을 쓴다. 테스트 2 가
     * {@code menuSeNm}("ROLE_USER 매핑이 없으면 '관리'" — TSK-4077 D1)을 단언하므로,
     * {@code ROLE_USER × M0602} 를 넣으면 {@code M0602} 가 '메뉴' 로 바뀌어 그 단언이 깨진다.
     * 승격은 시드 40행을 건드리지 않고 {@code ROLE_SYSTEM_ADMIN} 의 기존 {@code M0602} 매핑을
     * 그대로 탄다 — 판정은 여전히 표 파생이다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void loginAsMenuAuthorityManager() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();
        auth.promoteTo("ROLE_SYSTEM_ADMIN");   // 재로그인 불필요 — 표 파생 판정은 매 요청 조회다
    }

    /**
     * 매핑 테이블을 시드 상태(40행 — SYSTEM 15 · SERVICE 15 · USER 10)로 되돌린다.
     *
     * <p>순서 고정: <b>전체 DELETE 를 먼저</b> 하지 않으면 테스트가 건드리지 않은 메뉴의 행이 남아 있는 채로
     * INSERT 가 돌아 복합 PK {@code (authrt_cd, menu_id)} 충돌로 헬퍼 자체가 터진다.
     *
     * <p>세 INSERT 의 WHERE 조건은 시드({@code database/migrations/sql/seeding/02-TB_MENU_AUTHRT.sql})와 동일하다 —
     * 어긋나면 복원 후 40행이 되지 않는다. {@code TB_MENU}/{@code TB_AUTHRT} 는 테스트가 건드리지 않으므로
     * 복원 대상이 아니다(매핑만 되돌린다).
     */
    @AfterEach
    void restoreSeed() {
        // TSK-4115 Step 7-1 — mock 계정(승격분 포함)·로그인 이력을 되돌린다
        auth.cleanUp();
        jdbcTemplate.update("DELETE FROM parking.tb_authrt_menu");

        // SUPER 유형 권한 = 화면 15종 전부 (TSK-4115 D7 재개정 — 시드와 같은 유형 기준 SQL 을 쓴다)
        jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                SELECT a.authrt_cd, m.menu_id, 'system', now()
                  FROM parking.tb_authrt a
                 CROSS JOIN parking.tb_menu m
                 WHERE a.authrt_type_cd = 'SUPER' AND a.use_yn = 'Y'
                   AND m.menu_path_nm IS NOT NULL""");

        // 서비스 관리자 = 화면 15종 전부 (2026-09-03 — 설정 개방)
        jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                SELECT 'ROLE_SERVICE_ADMIN', menu_id, 'system', now()
                  FROM parking.tb_menu
                 WHERE menu_path_nm IS NOT NULL""");

        // 일반 사용자 = 설정 하위 + 보고서 관리 + 위험단계 알림 관리 제외
        jdbcTemplate.update("""
                INSERT INTO parking.tb_authrt_menu (authrt_cd, menu_id, rgtr_id, reg_dt)
                SELECT 'ROLE_USER', menu_id, 'system', now()
                  FROM parking.tb_menu
                 WHERE menu_path_nm IS NOT NULL
                   AND (up_menu_id IS DISTINCT FROM 'M06')
                   AND menu_id NOT IN ('M0402', 'M0404')""");
    }

    // ---------------------------------------------------------------- 조회

    @Test
    @DisplayName("1. GET /authorities — 사용중 권한 3건이 등록일시 오름차순으로 내려온다")
    void selectAuthorities() {
        ResponseEntity<List<AuthrtVO>> response = restTemplate.exchange(
                AUTHORITIES_URL, HttpMethod.GET, null, new ParameterizedTypeReference<List<AuthrtVO>>() {});

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<AuthrtVO> authorities = response.getBody();
        assertThat(authorities).isNotNull().hasSize(3);
        assertThat(authorities).extracting(AuthrtVO::getAuthrtCd)
                .containsExactly("ROLE_SYSTEM_ADMIN", "ROLE_SERVICE_ADMIN", "ROLE_USER");
        assertThat(authorities).extracting(AuthrtVO::getAuthrtNm)
                .containsExactly("시스템 관리자", "서비스 관리자", "일반 사용자");
    }

    @Test
    @DisplayName("2. GET /menus — 폴더 제외 15건, menuSeNm 파생('관리' 5건), 트리 표시 순서")
    void selectMenus() {
        List<MenuAuthrtVO> menus = getMenus();

        assertThat(menus).hasSize(15);
        // 폴더 메뉴(M03·M04·M06)는 목록에서 제외된다 (D4)
        // 사용자 정보(M0601)는 TSK-4077 에서 시드에서 제거됐다
        assertThat(menus).extracting(MenuAuthrtVO::getMenuId).doesNotContain("M03", "M04", "M06", "M0601");
        assertThat(menus).allSatisfy(menu -> assertThat(menu.getMenuPathNm()).isNotNull());

        // menuSeNm 은 ROLE_USER 매핑이 없으면 '관리' (TSK-4077 D1)
        assertThat(menus).filteredOn(menu -> "관리".equals(menu.getMenuSeNm()))
                .extracting(MenuAuthrtVO::getMenuId)
                .containsExactly("M0402", "M0404", "M0602", "M0603", "M0604");
        assertThat(menus).filteredOn(menu -> "메뉴".equals(menu.getMenuSeNm())).hasSize(10);

        // 트리 표시 순서: 상위 그룹 → 상위가 먼저 → sort_seq
        assertThat(menus.get(0).getMenuId()).isEqualTo("M01");
        assertThat(menus.get(menus.size() - 1).getMenuId()).isEqualTo("M0604");
    }

    // ---------------------------------------------------------------- 저장

    @Test
    @DisplayName("3. PUT 저장 — 저장 응답이 요청값과 같고, 새 요청으로 재조회해도 같다 (stale-read 회귀 방지)")
    void saveAuthoritiesReflectedInResponseAndOnRefetch() {
        // M0402(보고서 관리)는 시드에서 SYSTEM+SERVICE 2개를 갖는다 → SERVICE 를 USER 로 바꿔 저장하면
        // stale-read 시 저장 전 조합(SYSTEM+SERVICE)이 돌아와 실패한다
        List<String> requested = List.of("ROLE_SYSTEM_ADMIN", "ROLE_USER");

        ResponseEntity<MenuAuthrtVO> saved = putAuthorities("M0402", requested, MenuAuthrtVO.class);

        // (1) 저장 응답 자체가 요청값과 일치해야 한다 (2026-08-10 실측 버그 — MyBatis 1차 캐시 stale read)
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getBody()).isNotNull();
        assertThat(saved.getBody().getMenuId()).isEqualTo("M0402");
        assertThat(saved.getBody().getAuthrtCds())
                .containsExactlyInAnyOrderElementsOf(requested);

        // (2) 트랜잭션 밖 새 요청으로 재조회해도 같아야 한다 (JPA flush 누락 회귀 방지)
        MenuAuthrtVO refetched = findMenu(getMenus(), "M0402");
        assertThat(refetched.getAuthrtCds()).containsExactlyInAnyOrderElementsOf(requested);

        // (3) DB 에도 실제로 커밋됐는지 확인
        List<String> inDb = jdbcTemplate.queryForList(
                "SELECT authrt_cd FROM parking.tb_authrt_menu WHERE menu_id = ?", String.class, "M0402");
        assertThat(inDb).containsExactlyInAnyOrderElementsOf(requested);

        // 일반 사용자를 넣었으므로 구분이 '메뉴'로 바뀐다 (TSK-4077 D1 — 저장 응답에도 반영된다)
        assertThat(saved.getBody().getMenuSeNm()).isEqualTo("메뉴");
        assertThat(findMenu(getMenus(), "M0402").getMenuSeNm()).isEqualTo("메뉴");
    }

    @Test
    @DisplayName("4. 🔴 매핑 0건 메뉴는 authrtCds 가 [null] 이 아니라 빈 배열이다 (notNullColumn 회귀 방지)")
    void menuWithoutMappingReturnsEmptyList() {
        /*
         * 🔴 이 테스트는 종전에 "PUT 빈 배열 → 빈 리스트" 로 짜여 있었다. TSK-4115 D7 재개정으로
         *    SUPER 유형 권한이 저장 직전 자동 보정되면서 **저장 API 로는 매핑을 완전히 비울 수 없게**
         *    됐다(빈 배열을 보내도 SUPER 가 붙는다). 그래서 매핑을 DB 에서 직접 지운 뒤 조회로 확인한다 —
         *    원 목적인 "LEFT JOIN 미매칭 행이 null 원소로 들어오지 않는다" 는 그대로다.
         *    (자동 보정 자체의 회귀는 MenuAuthrtSuperIntegrationTest 가 담당한다.)
         */
        jdbcTemplate.update("DELETE FROM parking.tb_authrt_menu WHERE menu_id = ?", "M0603");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_authrt_menu WHERE menu_id = ?", Integer.class, "M0603"))
                .isZero();

        // LEFT JOIN 미매칭이 null 원소로 들어오면 isEmpty() 가 깨진다 (authrtCds: [null])
        MenuAuthrtVO refetched = findMenu(getMenus(), "M0603");
        assertThat(refetched.getAuthrtCds()).isNotNull().doesNotContainNull().isEmpty();

        // 권한 0건이면 '관리'다 (TSK-4077 D2)
        assertThat(refetched.getMenuSeNm()).isEqualTo("관리");

        // 매핑 복원은 @AfterEach 의 restoreSeed() 가 전량 재구성으로 진다(40행 복원).
    }

    @Test
    @DisplayName("5. PUT 잘못된 권한코드 — 400")
    void saveWithInvalidAuthorityCodeReturns400() {
        ResponseEntity<String> response =
                putAuthorities("M01", List.of("ROLE_SYSTEM_ADMIN", "ROLE_NOT_EXISTS"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        // 검증 실패 시 기존 매핑이 지워지면 안 된다 (한 트랜잭션 · 검증이 삭제보다 앞선다)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_authrt_menu WHERE menu_id = ?", Integer.class, "M01"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("6. PUT 없는 메뉴 → 404 / 폴더 메뉴(M03) → 400")
    void saveUnknownMenuReturns404AndFolderMenuReturns400() {
        ResponseEntity<String> unknown = putAuthorities("M99", List.of("ROLE_SYSTEM_ADMIN"), String.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);

        // M03(분석·시뮬레이션)은 menu_path_nm 이 NULL 인 폴더 메뉴 → 권한 지정 대상이 아니다 (D4)
        ResponseEntity<String> folder = putAuthorities("M03", List.of("ROLE_SYSTEM_ADMIN"), String.class);
        assertThat(folder.getStatusCode().value()).isEqualTo(400);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_authrt_menu WHERE menu_id IN ('M99', 'M03')", Integer.class))
                .isZero();
    }

    // ---------------------------------------------------------------- 헬퍼

    private List<MenuAuthrtVO> getMenus() {
        ResponseEntity<List<MenuAuthrtVO>> response = restTemplate.exchange(
                MENUS_URL, HttpMethod.GET, null, new ParameterizedTypeReference<List<MenuAuthrtVO>>() {});
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /** 🔴 쓰기는 반드시 세션을 실어 보낸다 (TSK-4115 Step 7 — @PreAuthorize). */
    private <T> ResponseEntity<T> putAuthorities(String menuId, List<String> authrtCds, Class<T> responseType) {
        HttpEntity<Map<String, Object>> request =
                auth.jsonEntity(session, Map.of("authrtCds", authrtCds));
        return restTemplate.exchange(
                MENUS_URL + "/" + menuId + "/authorities", HttpMethod.PUT, request, responseType);
    }

    private MenuAuthrtVO findMenu(List<MenuAuthrtVO> menus, String menuId) {
        return menus.stream()
                .filter(menu -> menuId.equals(menu.getMenuId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("메뉴를 목록에서 찾지 못했습니다: " + menuId));
    }
}
