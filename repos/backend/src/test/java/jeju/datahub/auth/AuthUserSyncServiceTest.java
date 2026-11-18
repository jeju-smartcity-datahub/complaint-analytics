package jeju.datahub.auth;

import jeju.datahub.auth.service.AuthProperties;
import jeju.datahub.auth.service.AuthUserSyncService;
import jeju.datahub.auth.service.AuthUserVO;
import jeju.datahub.auth.service.impl.AuthUserSyncServiceImpl;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code AuthUserSyncService} 통합테스트 (검증 7종).
 *
 * <p>하네스({@code SPRING_PROFILES_ACTIVE=test}) 위에서 <b>실 DB</b>를 쓴다 —
 * {@code cert_id} UNIQUE 충돌·{@code user_id} PK 충돌·NOT NULL 제약은 실제 제약이 있어야 의미가 있다.
 *
 * <p>🔴 테스트가 만든 {@code TB_USER} 행은 {@code @AfterEach} 로 지운다 — 실 커밋 경로라 롤백되지 않는다
 * ({@code MenuAuthrtIntegrationTest.restoreSeed()} 와 같은 관례).
 */
@SpringBootTest
class AuthUserSyncServiceTest {

    /** 이 테스트가 만드는 행을 한 번에 지우기 위한 접두 (실사용자·mock-user 와 겹치지 않는다) */
    private static final String P = "t4076-";

    /**
     * 🔴 테스트가 {@code TB_DEPT} 에 등재시키는 부서코드 전용 접두.
     *
     * <p>{@code @AfterEach} 가 이 접두로만 지운다 — <b>접두 없이 범위를 넓히지 않는다.</b>
     * 통합테스트 픽스처가 넣는 {@code PARK}·{@code TRFC} 등 5코드까지 지우면
     * {@code ReportIntegrationTest}·{@code RiskNotiIntegrationTest} 의 JOIN 검증이 통째로 빈다.
     */
    private static final String DP = "t4183-";

    @Autowired private AuthUserSyncService sync;
    @Autowired private AuthProperties props;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 🔴 싱글턴 {@code AuthProperties} 의 {@code role-mapping} 원본.
     *
     * <p>공통 {@code application.yml} 의 {@code manager}·{@code admin} 매핑이 바인딩된 것을
     * 테스트가 건드리면 같은 컨텍스트를 공유하는 뒤 테스트가 <b>빈 매핑</b>을 보고 전부
     * {@code ROLE_USER} 로 떨어진다 — {@code clear()} 만 두지 않고 반드시 <b>복원</b>한다.
     */
    private Map<String, String> roleMappingSnapshot;

    /** 다부서 WARN 1줄을 실제로 확인하기 위한 캡처 (선례: {@code LgnHstryServiceTest}) */
    private final WarnCapturingAppender warnAppender = new WarnCapturingAppender();

    @BeforeEach
    void snapshotRoleMapping() {
        roleMappingSnapshot = new LinkedHashMap<>(props.getRoleMapping());
        warnAppender.start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(AuthUserSyncServiceImpl.class))
                .addAppender(warnAppender);
    }

    @AfterEach
    void cleanUp() {
        ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(AuthUserSyncServiceImpl.class))
                .removeAppender(warnAppender);
        warnAppender.stop();
        jdbcTemplate.update("DELETE FROM parking.tb_user WHERE cert_id LIKE ?", P + "%");
        // 🔴 tb_user 를 지운 **뒤**에 지운다(fk_tb_user_dept 참조 순서).
        //    🔴 접두로만 지운다 — 통합테스트 픽스처의 5코드(PARK·TRFC…)는 건드리지 않는다.
        jdbcTemplate.update("DELETE FROM parking.tb_dept WHERE dept_cd LIKE ?", DP + "%");
        props.getRoleMapping().clear();
        props.getRoleMapping().putAll(roleMappingSnapshot);   // 🔴 복원 — clear() 만 두면 뒤 테스트가 빈 매핑을 본다
    }

    private Map<String, Object> claims(String sub, String username, String name, String email) {
        Map<String, Object> m = new HashMap<>();
        m.put("sub", sub);
        if (username != null) m.put("preferred_username", username);
        if (name != null) m.put("name", name);
        if (email != null) m.put("email", email);
        return m;
    }

    private String authrtCdOf(String certId) {
        return jdbcTemplate.queryForObject(
                "SELECT user_authrt_cd FROM parking.tb_user WHERE cert_id = ?", String.class, certId);
    }

    // ----------------------------------------------------- 부서 헬퍼

    /** {@code groups} claim 을 얹은 claim 맵 */
    private Map<String, Object> claimsWithGroups(String sub, String username, Object groups) {
        Map<String, Object> m = claims(sub, username, "부서", null);
        m.put("groups", groups);
        return m;
    }

    private String deptCdOf(String certId) {
        return jdbcTemplate.queryForObject(
                "SELECT dept_cd FROM parking.tb_user WHERE cert_id = ?", String.class, certId);
    }

    private long deptCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_dept", Long.class);
    }

    private boolean deptExists(String deptCd) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_dept WHERE dept_cd = ?", Long.class, deptCd) > 0;
    }

    @Test
    @DisplayName("1. 최초 로그인 시 ROLE_USER 행이 생성된다 (rgtr_id·reg_dt NOT NULL 충족)")
    void createsRowOnFirstLogin() {
        String sub = P + "first";
        AuthUserVO vo = sync.sync(claims(sub, P + "user1", "테스트", "a@example.com"));

        assertThat(vo.getUserAuthrtCd()).isEqualTo("ROLE_USER");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM parking.tb_user WHERE cert_id = ?", sub);
        assertThat(row.get("user_id")).isEqualTo(P + "user1");
        assertThat(row.get("user_nm")).isEqualTo("테스트");
        assertThat(row.get("use_yn")).isEqualTo("Y");
        // 🔴 DB DEFAULT 가 없으므로 서비스가 채워야만 NOT NULL 을 만족한다
        assertThat(row.get("rgtr_id")).isEqualTo("system");
        assertThat(row.get("reg_dt")).isNotNull();
        assertThat(row.get("last_lgn_dt")).isNotNull();
    }

    @Test
    @DisplayName("2. 재로그인 시 last_lgn_dt 만 갱신되고 user_authrt_cd 는 유지된다 (type claim 부재일 때)")
    void keepsAuthrtCdOnRelogin() {
        String sub = P + "relogin";
        sync.sync(claims(sub, P + "user2", "테스트", null));
        jdbcTemplate.update(
                "UPDATE parking.tb_user SET user_authrt_cd = 'ROLE_SYSTEM_ADMIN' WHERE cert_id = ?", sub);

        AuthUserVO vo = sync.sync(claims(sub, P + "user2", "테스트", null));

        // 🔴 type claim 이 아예 없으면 수동 승격을 덮지 않는다
        assertThat(vo.getUserAuthrtCd()).isEqualTo("ROLE_SYSTEM_ADMIN");
        assertThat(authrtCdOf(sub)).isEqualTo("ROLE_SYSTEM_ADMIN");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT mdfr_id, mdfcn_dt FROM parking.tb_user WHERE cert_id = ?", sub);
        assertThat(row.get("mdfr_id")).isEqualTo("system");
        assertThat(row.get("mdfcn_dt")).isNotNull();
    }

    @Test
    @DisplayName("3. use_yn='N' 이면 OAuth2AuthenticationException 으로 거부한다")
    void rejectsDisabledUser() {
        String sub = P + "disabled";
        sync.sync(claims(sub, P + "user3", "테스트", null));
        jdbcTemplate.update("UPDATE parking.tb_user SET use_yn = 'N' WHERE cert_id = ?", sub);

        assertThatThrownBy(() -> sync.sync(claims(sub, P + "user3", "테스트", null)))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("4. preferred_username 21자 이상이면 20자로 절단되고, 충돌 시 다른 user_id 를 배정한다")
    void truncatesAndAvoidsCollision() {
        String longName = "abcdefghij0123456789xyz";     // 23자
        assertThat(longName.length()).isGreaterThan(20);

        AuthUserVO a = sync.sync(claims(P + "long-a", longName, "A", null));
        AuthUserVO b = sync.sync(claims(P + "long-b", longName, "B", null));

        assertThat(a.getUserId()).isEqualTo(longName.substring(0, 20));
        assertThat(b.getUserId()).isNotEqualTo(a.getUserId());
        // ② 규칙: 앞 17자 + "-" + N
        assertThat(b.getUserId()).isEqualTo(longName.substring(0, 17) + "-2");
    }

    @Test
    @DisplayName("5. 🔴 절단·충돌 배정 결과의 user_id 길이가 전부 20 이하다 (value too long 회피)")
    void allocatedUserIdsFitColumn() {
        String longName = "abcdefghij0123456789xyz";
        for (int i = 1; i <= 3; i++) {
            AuthUserVO vo = sync.sync(claims(P + "fit-" + i, longName, "N" + i, null));
            assertThat(vo.getUserId()).hasSizeLessThanOrEqualTo(20);
        }
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT user_id FROM parking.tb_user WHERE cert_id LIKE ?", String.class, P + "fit-%");
        assertThat(ids).hasSize(3).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).hasSizeLessThanOrEqualTo(20));
    }

    @Test
    @DisplayName("6. type 이 리스트 / 문자열 / 없음 3케이스 모두 통과한다")
    void handlesTypeShapes() {
        // 리스트면 **첫 일치**를 쓴다 — 앞선 미등록 원소는 건너뛴다
        Map<String, Object> asList = claims(P + "t-list", P + "tl", "L", null);
        asList.put("type", List.of("others", "admin"));
        assertThat(sync.sync(asList).getUserAuthrtCd()).isEqualTo("ROLE_SYSTEM_ADMIN");

        Map<String, Object> asString = claims(P + "t-str", P + "ts", "S", null);
        asString.put("type", "admin");
        assertThat(sync.sync(asString).getUserAuthrtCd()).isEqualTo("ROLE_SYSTEM_ADMIN");

        // claim 자체가 없으면 신규는 기본값 (기존 행이면 유지)
        assertThat(sync.sync(claims(P + "t-none", P + "tn", "N", null)).getUserAuthrtCd())
                .isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("8. 🔴 type 이 빈 배열([])·빈 원소뿐이면 명시적 일반사용자라 ROLE_USER 로 덮어쓴다")
    void emptyTypeDemotesToDefaultRole() {
        Map<String, Object> empty = claims(P + "t-empty", P + "te", "E", null);
        empty.put("type", List.of());
        assertThat(sync.sync(empty).getUserAuthrtCd()).isEqualTo("ROLE_USER");

        // 원소가 ""·공백·null 뿐인 배열도 같은 취급이다 (미등록 값과 달리 WARN 이 없다)
        Map<String, Object> blanks = claims(P + "t-blank", P + "tb", "B", null);
        blanks.put("type", Arrays.asList("", "   ", null));
        assertThat(sync.sync(blanks).getUserAuthrtCd()).isEqualTo("ROLE_USER");

        // 값이 null 이면 claim 부재와 같다 — 신규 행이라 결과는 기본값으로 같다.
        // "기존 행을 유지한다" 는 13번이 전담한다.
        Map<String, Object> nul = claims(P + "t-null", P + "tnull", "N", null);
        nul.put("type", null);
        assertThat(sync.sync(nul).getUserAuthrtCd()).isEqualTo("ROLE_USER");

        // 🔴 반전 — 빈 값은 "값 없음" 이 아니라 명시적 일반 사용자다. 수동 승격은 살아남지 못한다.
        jdbcTemplate.update("UPDATE parking.tb_user SET user_authrt_cd='ROLE_SYSTEM_ADMIN' WHERE cert_id = ?",
                P + "t-empty");
        assertThat(sync.sync(empty).getUserAuthrtCd()).isEqualTo("ROLE_USER");
        assertThat(authrtCdOf(P + "t-empty")).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("7. 🔴 name claim 이 없어도 행이 생성되고 user_nm 이 폴백값으로 채워진다")
    void fallsBackWhenNameClaimMissing() {
        // name 없음 → preferred_username 으로 폴백
        String sub1 = P + "noname";
        assertThat(sync.sync(claims(sub1, P + "un", null, null)).getUserNm()).isEqualTo(P + "un");

        // name·preferred_username 둘 다 없음 → sub 로 폴백
        String sub2 = P + "nonothing";
        AuthUserVO vo = sync.sync(claims(sub2, null, null, null));
        assertThat(vo.getUserNm()).isEqualTo(sub2);
        // user_id 도 sub 기반으로 배정된다 (NOT NULL 위반이 나지 않는다)
        assertThat(vo.getUserId()).isNotBlank().hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("9. type=admin 이면 최초 로그인부터 ROLE_SYSTEM_ADMIN 으로 생성된다")
    void createsSystemAdminFromAdminType() {
        String sub = P + "t-admin";
        Map<String, Object> c = claims(sub, P + "ta", "관리자", null);
        c.put("type", "admin");

        assertThat(sync.sync(c).getUserAuthrtCd()).isEqualTo("ROLE_SYSTEM_ADMIN");
        assertThat(authrtCdOf(sub)).isEqualTo("ROLE_SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("10. 기존 ROLE_USER 행이 type=manager 로 재로그인하면 ROLE_SERVICE_ADMIN 으로 승격된다")
    void promotesExistingUserOnManagerType() {
        String sub = P + "t-promote";
        assertThat(sync.sync(claims(sub, P + "tp", "매니저", null)).getUserAuthrtCd())
                .isEqualTo("ROLE_USER");

        Map<String, Object> c = claims(sub, P + "tp", "매니저", null);
        c.put("type", "manager");

        assertThat(sync.sync(c).getUserAuthrtCd()).isEqualTo("ROLE_SERVICE_ADMIN");
        assertThat(authrtCdOf(sub)).isEqualTo("ROLE_SERVICE_ADMIN");
    }

    @Test
    @DisplayName("11. 🔴 기존 ROLE_SYSTEM_ADMIN 행이 type=\"\" 로 재로그인하면 ROLE_USER 로 강등된다")
    void demotesExistingAdminOnEmptyType() {
        String sub = P + "t-demote";
        sync.sync(claims(sub, P + "td", "강등", null));
        jdbcTemplate.update(
                "UPDATE parking.tb_user SET user_authrt_cd = 'ROLE_SYSTEM_ADMIN' WHERE cert_id = ?", sub);

        Map<String, Object> c = claims(sub, P + "td", "강등", null);
        c.put("type", "");

        // 🔴 빈 문자열은 "값 없음" 이 아니라 명시적 일반 사용자다 — DB 수동 승격이 사라진다
        assertThat(sync.sync(c).getUserAuthrtCd()).isEqualTo("ROLE_USER");
        assertThat(authrtCdOf(sub)).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("12. 🔴 매핑에 없는 type 값은 ROLE_USER 로 덮어쓴다 (최소 권한)")
    void demotesOnUnmappedType() {
        String sub = P + "t-unknown";
        sync.sync(claims(sub, P + "tu", "미등록", null));
        jdbcTemplate.update(
                "UPDATE parking.tb_user SET user_authrt_cd = 'ROLE_SYSTEM_ADMIN' WHERE cert_id = ?", sub);

        Map<String, Object> c = claims(sub, P + "tu", "미등록", null);
        c.put("type", "superadmin");

        // 모르는 값을 "유지" 로 두면 IdP 에서 강등된 사용자가 권한을 계속 가진다
        assertThat(sync.sync(c).getUserAuthrtCd()).isEqualTo("ROLE_USER");
        assertThat(authrtCdOf(sub)).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("13. 🔴 type claim 부재로 재로그인하면 기존 롤을 유지한다 (IdP 응답 누락 방어)")
    void keepsRoleWhenTypeClaimMissing() {
        String sub = P + "t-keep";
        Map<String, Object> withType = claims(sub, P + "tk", "유지", null);
        withType.put("type", "admin");
        assertThat(sync.sync(withType).getUserAuthrtCd()).isEqualTo("ROLE_SYSTEM_ADMIN");

        // claim 키 자체가 없는 재로그인 (mock 로그인도 이 경로다)
        assertThat(sync.sync(claims(sub, P + "tk", "유지", null)).getUserAuthrtCd())
                .isEqualTo("ROLE_SYSTEM_ADMIN");
        assertThat(authrtCdOf(sub)).isEqualTo("ROLE_SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("14. 🔴 설정 바인딩 — role-claim 은 type 이고 role-mapping 은 manager·admin 두 키다")
    void bindsRoleClaimAndMapping() {
        // 이게 없으면 yml 오타·키 누락이 "매핑이 안 걸렸는데 기본값이라 통과" 로 조용히 넘어간다
        assertThat(props.getRoleClaim()).isEqualTo("type");
        assertThat(props.getRoleMapping())
                .containsEntry("manager", "ROLE_SERVICE_ADMIN")
                .containsEntry("admin", "ROLE_SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("15. 🔴 type 값은 대소문자·앞뒤 공백을 무시하고 매칭된다 (trim + 소문자)")
    void normalizesTypeValue() {
        Map<String, Object> upperFirst = claims(P + "t-Admin", P + "tA", "A1", null);
        upperFirst.put("type", "Admin");
        assertThat(sync.sync(upperFirst).getUserAuthrtCd()).isEqualTo("ROLE_SYSTEM_ADMIN");

        Map<String, Object> allUpper = claims(P + "t-ADMIN", P + "tB", "A2", null);
        allUpper.put("type", "ADMIN");
        assertThat(sync.sync(allUpper).getUserAuthrtCd()).isEqualTo("ROLE_SYSTEM_ADMIN");

        Map<String, Object> padded = claims(P + "t-pad", P + "tC", "M1", null);
        padded.put("type", " manager ");
        assertThat(sync.sync(padded).getUserAuthrtCd()).isEqualTo("ROLE_SERVICE_ADMIN");
    }

    // ================================================ 부서(groups) 동기화

    @Test
    @DisplayName("16. 🔴 groups 원소 1개면 dept_cd 에 저장된다 — 빈 값이 섞여 있어도 유효 그룹을 쓴다")
    void savesDeptCdFromGroups() {
        String sub = P + "d-one";
        String slug = DP + "one";
        assertThat(sync.sync(claimsWithGroups(sub, P + "d1", List.of(slug))).getUserAuthrtCd())
                .isNotNull();
        assertThat(deptCdOf(sub)).isEqualTo(slug);

        // 🔴 해체 뒤 hasText 로 걸러내지 않으면 여기서 유효 그룹을 버리고 무소속이 된다
        String sub2 = P + "d-blankmix";
        String slug2 = DP + "mix";
        sync.sync(claimsWithGroups(sub2, P + "d2", Arrays.asList("", "   ", slug2)));
        assertThat(deptCdOf(sub2)).isEqualTo(slug2);
        // 걸러낸 뒤 원소가 1개라 다부서 WARN 은 찍히지 않는다
        assertThat(warnAppender.messages())
                .noneMatch(m -> m.contains("claim 원소가 2개 이상입니다"));
    }

    @Test
    @DisplayName("17. 🔴 groups 원소가 2개 이상이면 첫 원소를 쓰고 WARN 1줄을 남긴다")
    void usesFirstGroupAndWarnsOnMultiple() {
        String sub = P + "d-multi";
        String first = DP + "first";
        String second = DP + "second";

        sync.sync(claimsWithGroups(sub, P + "d3", List.of(first, second)));

        assertThat(deptCdOf(sub)).isEqualTo(first);
        // 🔴 문구는 검증 grep 이 세는 정본이다 — 바뀌면 여기서 깨진다
        assertThat(warnAppender.messages()).anySatisfy(m -> assertThat(m)
                .isEqualTo("[auth] 'groups' claim 원소가 2개 이상입니다 — 첫 원소를 씁니다 (값=["
                        + first + ", " + second + "])"));
        // 두 번째 원소는 등재하지 않는다 (쓰는 값만 등재한다)
        assertThat(deptExists(second)).isFalse();
    }

    @Test
    @DisplayName("18. 🔴 groups claim 키가 없으면 기존 dept_cd 를 유지한다 (IdP 응답 누락 방어)")
    void keepsDeptCdWhenClaimMissing() {
        String sub = P + "d-keep";
        String slug = DP + "keep";
        sync.sync(claimsWithGroups(sub, P + "d4", List.of(slug)));
        assertThat(deptCdOf(sub)).isEqualTo(slug);

        // claim 키 자체가 없는 재로그인 (mock 로그인도 이 경로다)
        sync.sync(claims(sub, P + "d4", "부서", null));
        assertThat(deptCdOf(sub)).isEqualTo(slug);

        // 값이 null 인 것도 "키 부재" 와 같다
        sync.sync(claimsWithGroups(sub, P + "d4", null));
        assertThat(deptCdOf(sub)).isEqualTo(slug);
    }

    @Test
    @DisplayName("19. 🔴 groups 가 []·빈 값뿐이면 dept_cd 를 NULL 로 비우고 TB_DEPT 행 수는 늘지 않는다")
    void emptyGroupsClearsDeptCdWithoutRegistering() {
        String sub = P + "d-empty";
        String slug = DP + "empty";
        sync.sync(claimsWithGroups(sub, P + "d5", List.of(slug)));
        assertThat(deptCdOf(sub)).isEqualTo(slug);

        long before = deptCount();

        // 빈 배열 → 명시적 무소속
        sync.sync(claimsWithGroups(sub, P + "d5", List.of()));
        assertThat(deptCdOf(sub)).isNull();
        // 🔴 hasText 가드가 없으면 dept_cd=''·dept_nm='' 행이 조용히 1행 생긴다
        assertThat(deptCount()).isEqualTo(before);

        // 원소가 ""·공백·null 뿐인 배열도 같은 취급이다
        sync.sync(claimsWithGroups(sub, P + "d5", List.of(slug)));
        sync.sync(claimsWithGroups(sub, P + "d5", Arrays.asList("", "   ", null)));
        assertThat(deptCdOf(sub)).isNull();
        assertThat(deptCount()).isEqualTo(before);

        // 빈 문자열(배열이 아닌 스칼라)도 같다
        sync.sync(claimsWithGroups(sub, P + "d5", List.of(slug)));
        sync.sync(claimsWithGroups(sub, P + "d5", ""));
        assertThat(deptCdOf(sub)).isNull();
        assertThat(deptCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("20. 🔴 미등록 그룹은 TB_DEPT 에 1행 자동 등재된다 (dept_nm 초기값 = 슬러그)")
    void registersUnknownGroupIntoTbDept() {
        String sub = P + "d-reg";
        String slug = DP + "reg";
        assertThat(deptExists(slug)).isFalse();
        long before = deptCount();

        sync.sync(claimsWithGroups(sub, P + "d6", List.of(slug)));

        assertThat(deptCount()).isEqualTo(before + 1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM parking.tb_dept WHERE dept_cd = ?", slug);
        assertThat(row.get("dept_nm")).isEqualTo(slug);     // 표시명은 사후 보정
        assertThat(row.get("sort_seq")).isNull();           // 목록이 NULLS LAST
        assertThat(row.get("use_yn")).isEqualTo("Y");
        assertThat(row.get("rgtr_id")).isEqualTo("system");
        assertThat(row.get("reg_dt")).isNotNull();

        // 🔴 existsById 가드 — 사후 보정한 dept_nm 을 재로그인이 슬러그로 되돌리지 않는다
        jdbcTemplate.update("UPDATE parking.tb_dept SET dept_nm = ? WHERE dept_cd = ?", "교통정책과", slug);
        sync.sync(claimsWithGroups(sub, P + "d6", List.of(slug)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT dept_nm FROM parking.tb_dept WHERE dept_cd = ?", String.class, slug))
                .isEqualTo("교통정책과");
        assertThat(deptCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("21. 🔴 31자 슬러그는 30자로 잘려 저장·등재된다 (VARCHAR(30) — value too long 회피)")
    void truncatesDeptCdToThirty() {
        String sub = P + "d-long";
        String longSlug = DP + "0123456789012345678901234";   // 6 + 25 = 31자
        assertThat(longSlug).hasSize(31);
        String cut = longSlug.substring(0, 30);

        sync.sync(claimsWithGroups(sub, P + "d7", List.of(longSlug)));

        assertThat(deptCdOf(sub)).isEqualTo(cut);
        // 등재도 **같은 잘린 값**을 쓴다 — 아니면 FK 위반이다
        assertThat(deptExists(cut)).isTrue();
        assertThat(deptExists(longSlug)).isFalse();
    }

    /** WARN 이상만 모으는 최소 appender (선례: {@code LgnHstryServiceTest}) */
    private static final class WarnCapturingAppender extends AbstractAppender {

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        private WarnCapturingAppender() {
            super("authUserSyncWarnCapture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> messages() {
            return List.copyOf(messages);
        }
    }
}
