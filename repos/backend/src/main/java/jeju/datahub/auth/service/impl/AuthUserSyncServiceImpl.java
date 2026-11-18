package jeju.datahub.auth.service.impl;

import org.springframework.transaction.TransactionException;
import org.springframework.dao.DataAccessException;
import jeju.datahub.auth.service.AuthProperties;
import jeju.datahub.auth.service.AuthUserSyncService;
import jeju.datahub.auth.service.AuthUserVO;
import jeju.datahub.dept.service.impl.DeptRegistrar;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * claim → {@code TB_USER} upsert + {@code type} 롤 매핑.
 *
 * <p>🔴 이 클래스는 <b>Spring Security 의 인증 타입을 만들지 않는다</b> — {@code DefaultOidcUser} ·
 * {@code OAuth2AuthenticationToken} 조립은 책임이다(이식성).
 * 유일한 예외가 {@link OAuth2AuthenticationException} 인데, 이건 "인증 거부" 를 표현하는 수단이라
 * 호출부(콜백·mock)가 같은 방식으로 처리할 수 있게 여기서 던진다.
 */
@Service
public class AuthUserSyncServiceImpl extends EgovAbstractServiceImpl implements AuthUserSyncService {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(AuthUserSyncServiceImpl.class);

    /** 레포 관례 (MenuAuthrtServiceImpl:37 과 같은 값). 범위 밖 #3 에서 로그인 사용자 ID 로 교체된다 */
    private static final String SYSTEM_USER_ID = "system";

    private static final int USER_ID_MAX = 20;

    /** 🔴 {@code TB_DEPT.dept_cd}·{@code TB_USER.dept_cd} 는 VARCHAR(30) 이다 */
    private static final int DEPT_CODE_MAX = 30;

    private final UserRepository userRepository;
    private final AuthProperties props;
    /**
     * 🔴 {@code auth} → {@code dept} 접점은 <b>이 빈 하나</b>다.
     * {@code DeptRepository} 를 직접 주입하지 않는다(등재·조회 모두 이 창구를 지난다).
     */
    private final DeptRegistrar deptRegistrar;

    public AuthUserSyncServiceImpl(UserRepository userRepository, AuthProperties props,
                                   DeptRegistrar deptRegistrar) {
        this.userRepository = userRepository;
        this.props = props;
        this.deptRegistrar = deptRegistrar;
    }

    @Override
    @Transactional
    public AuthUserVO sync(Map<String, Object> claims) {
        String certId = asText(claims.get("sub"));
        if (!StringUtils.hasText(certId)) {
            // sub 는 OIDC 가 유일하게 보장하는 claim 이다. 없으면 신원을 특정할 수 없다.
            throw authException("invalid_token", "id_token 에 sub 가 없습니다.");
        }

        String username = asText(claims.get(props.getUsernameClaim()));
        String userNm = resolveUserNm(claims, username, certId);
        // user_nm 과 같은 이유로 길이를 맞춘다 — TB_USER.user_eml_addr 는 VARCHAR(200) 이고,
        // 초과하면 value too long 으로 로그인 전체가 500 이 된다(nullable 이라 값이 없는 건 정상).
        String userEmlAddr = truncateNullable(asText(claims.get(props.getEmailClaim())), 200);
        String mappedAuthrtCd = resolveAuthrtCd(claims);
        // 🔴 claim 판정만 여기서 한다. 등재·저장은 useYn 가드 뒤로 미룬다
        //    (정지된 사용자의 로그인이 TB_DEPT 에 흔적을 남기지 않게 한다).
        Optional<String> resolvedDeptCd = resolveDeptCd(claims);

        LocalDateTime now = LocalDateTime.now();
        Optional<UserEntity> found = userRepository.findByCertId(certId);

        UserEntity user;
        if (found.isEmpty()) {
            user = new UserEntity();
            user.setCertId(certId);
            // 3단 규칙 — 결과는 항상 20자 이하다
            user.setUserId(allocateUserId(username, certId));
            user.setUserAuthrtCd(mappedAuthrtCd != null ? mappedAuthrtCd : props.getDefaultAuthrtCd());
            user.setUseYn("Y");
            // 🔴 rgtr_id·reg_dt 는 NOT NULL 인데 DB DEFAULT 가 없다 — 코드가 채운다.
            user.setRgtrId(SYSTEM_USER_ID);
            user.setRegDt(now);
        } else {
            user = found.orElseThrow();   // 위 isEmpty 분기 뒤라 항상 값이 있다(5.1 — Optional.get 대신)
            // 🔴 role-claim 이 실려 오면 **로그인마다 덮어쓴다**(IdP 가 권한의 정본).
            //    null 은 "claim 부재" 한 경우뿐이고, 그때만 기존 롤을 유지한다 —
            //    IdP 응답 누락·장애로 관리자가 일괄 강등되는 것을 막는 장치다.
            //    ⚠️ 그 대가로 DB 수동 승격은 다음 로그인에 사라진다(복구 창구가 IdP 로 옮겨갔다).
            if (mappedAuthrtCd != null) {
                user.setUserAuthrtCd(mappedAuthrtCd);
            }
            user.setMdfrId(SYSTEM_USER_ID);
            user.setMdfcnDt(now);
        }

        user.setUserNm(userNm);
        user.setUserEmlAddr(userEmlAddr);
        user.setLastLgnDt(now);

        // 🔴 정지 판정은 저장 전에 한다 — 정지된 사용자의 last_lgn_dt 를 갱신하지 않는다.
        if ("N".equals(user.getUseYn())) {
            throw authException("access_denied", "정지된 사용자입니다.");
        }

        // 🔴 FK(fk_tb_user_dept)가 걸려 있으니 등재가 먼저다. 저장 직전에 한다.
        applyDeptCd(user, resolvedDeptCd);

        UserEntity saved = userRepository.save(user);
        return new AuthUserVO(saved.getUserId(), saved.getUserNm(),
                saved.getUserEmlAddr(), saved.getUserAuthrtCd());
    }

    /**
     * {@code user_nm} 폴백 — {@code name} → {@code preferred_username} → {@code sub}.
     *
     * <p>🔴 {@code user_nm} 은 {@code user_eml_addr} 와 달리 <b>NOT NULL</b> 인데 {@code name} 의 실제
     * 탑재는 에서야 확인된다. 비면 최초 로그인이 not-null 위반(500)으로 끊긴다.
     */
    private String resolveUserNm(Map<String, Object> claims, String username, String certId) {
        String name = asText(claims.get(props.getNameClaim()));
        if (StringUtils.hasText(name)) {
            return truncate(name, 100);
        }
        if (StringUtils.hasText(username)) {
            log.warn("[auth] '{}' claim 부재 — user_nm 을 username 으로 폴백 (sub={})",
                    props.getNameClaim(), certId);
            return truncate(username, 100);
        }
        log.warn("[auth] '{}'·'{}' 둘 다 부재 — user_nm 을 sub 로 폴백",
                props.getNameClaim(), props.getUsernameClaim());
        return truncate(certId, 100);
    }

    /**
     * {@code type} claim → {@code authrt_cd}.
     *
     * <p>🔴 <b>{@code null} 을 돌려주는 것은 "claim 부재" 한 경우뿐이다</b>(키 없음 · 값이 {@code null}).
     * 그때만 호출부가 기존 롤을 <b>유지</b>한다(IdP 응답 누락으로 관리자가 일괄 강등되는 것을 막는다).
     * 값이 <b>있기만 하면</b> 어떤 모양이든 덮어쓸 코드를 돌려준다:
     * <ul>
     *   <li>{@code ""}(공백만 있는 값 포함) · {@code []} · 원소가 빈 값뿐인 배열 →
     *       {@code default-authrt-cd} ("값 없음" 이 아니라 <b>명시적 일반 사용자</b>라 강등이 정상이다)</li>
     *   <li>매핑 일치(배열이면 첫 일치) → 매핑값</li>
     *   <li>미등록 값 → {@code default-authrt-cd} + WARN 1줄 (최소 권한.
     *       모르는 값을 "유지" 로 두면 IdP 에서 강등된 사용자가 권한을 계속 가진다)</li>
     * </ul>
     *
     * <p>⚠️ claim 은 <b>리스트로도 문자열로도</b> 올 수 있고, 그 밖의 타입(숫자·불린)도 문자열로 취급한다.
     */
    private String resolveAuthrtCd(Map<String, Object> claims) {
        // 🔴 분기 기준은 Set 이 아니라 **raw 값**이다 — Set 으로 받으면 "키 부재"(유지)와
        //    "빈 배열 []"(강등)이 똑같이 빈 Set 이 되어 원리적으로 구분되지 않는다.
        //    설계 결정은 여기서 끝난다.
        Object raw = claims.get(props.getRoleClaim());
        if (raw == null) {
            return null;
        }

        // 🔴 빈 값 판정이 매핑 조회보다 **먼저**다(①②③). 뒤로 미루면 "" 가 매핑에서 miss 돼
        //    흘러 불필요한 WARN 이 찍힌다 — 반환값은 같아서 로그를 보기 전까지 드러나지 않는다.
        //    extractRoleValues() 는 trim 만 하므로(빈 값을 버리지 않는다) 거르는 판단은 여기가 전담한다.
        List<String> candidates = new ArrayList<>();
        for (String v : extractRoleValues(raw)) {
            if (StringUtils.hasText(v)) {
                candidates.add(v);
            }
        }
        if (candidates.isEmpty()) {
            return props.getDefaultAuthrtCd();      // WARN 없음(정상 경로다)
        }

        // ④ 🔴 mapping 선언·null 가드는 **여기**(빈 값 판정 뒤)에 둔다 — 앞에 두면 role-mapping 이
        //    빈 프로파일에서 빈 값·미등록 값이 null(= 유지)로 새어 설계 결정이 통째로 무력화된다.
        //    isEmpty() 를 가드에 넣지 않는 이유도 같다. null 가드는 mapping.get() NPE 방지용이다.
        Map<String, String> mapping = props.getRoleMapping();
        if (mapping == null) {
            return props.getDefaultAuthrtCd();
        }
        for (String value : candidates) {
            // 🔴 trim + 소문자 정규화 후 조회한다(yml 키는 항상 소문자).
            //    Locale.ROOT 를 빼면 터키어 로케일에서 'I' 가 'ı' 로 바뀌어 매칭이 깨진다.
            String mapped = mapping.get(value.trim().toLowerCase(Locale.ROOT));
            if (StringUtils.hasText(mapped)) {
                return mapped;                      // 배열이면 첫 일치
            }
        }

        // ⑤ 값은 있는데 매핑에 없다. 최소 권한으로 덮어쓰되 **무엇이 왔는지** 남긴다.
        log.warn("[auth] '{}' claim 값이 role-mapping 에 없습니다 — {} 로 덮어씁니다 (값={})",
                props.getRoleClaim(), props.getDefaultAuthrtCd(), candidates);
        return props.getDefaultAuthrtCd();
    }

    /**
     * {@code groups} claim → {@code dept_cd}.
     *
     * <p>🔴 <b>{@code type}(롤)과 다른 지점이 하나 있다</b> — {@code type} 은 빈 값이면
     * {@code default-authrt-cd} 로 강등되지만 <b>부서에는 기본값이 없다.</b>
     * 그래서 세 상태를 반환값으로 가른다:
     * <table border="1">
     *   <caption>claim 상태 ↔ 반환값 ↔ 저장</caption>
     *   <tr><th>claim 상태</th><th>반환</th><th>저장</th></tr>
     *   <tr><td>키 부재({@code null})</td><td>{@code Optional.empty()}</td>
     *       <td>기존 값 <b>유지</b> (type 설계 결정과 동일)</td></tr>
     *   <tr><td>{@code []} · {@code ""} · 빈 값만 있는 배열({@code [""]}·{@code ["  "]})</td>
     *       <td>{@code Optional.of("")}</td><td>{@code dept_cd = NULL} (<b>명시적 무소속</b>)</td></tr>
     *   <tr><td>값 있음</td><td>{@code Optional.of(}빈 값을 걸러낸 뒤의 첫 원소{@code )}</td>
     *       <td>덮어쓰기</td></tr>
     * </table>
     *
     * <p>⚠️ 위 3행은 <b>claim 판정 결과</b>다 — 등재가 실패하면 3행이라도 덮어쓰지 않는다
     * ({@link #applyDeptCd(UserEntity, Optional)}). 1행과 결과는 같지만 사유가 다르다
     * (claim 부재 ↔ {@code TB_DEPT} 등재 실패).
     *
     * <p>🔴 <b>분기 기준은 해체 결과가 아니라 raw 값이다</b> — {@link #extractRoleValues(Object)} 는
     * {@code raw == null} 과 {@code []} 를 <b>똑같이 빈 리스트</b>로 돌려주므로, 해체 결과로 갈라서는
     * 위 표의 1행(유지)과 2행(무소속)이 원리적으로 구분되지 않는다.
     * {@link #resolveAuthrtCd(Map)} 의 {@code if (raw == null) return null;} 과 같은 자리·같은 이유다.
     *
     * <p>🔴 <b>해체 뒤 {@code hasText} 로 빈 값을 걸러내고 그 결과의 첫 원소를 쓴다</b> — 걸러내지 않으면
     * {@code ["", "innopam-group2"]} 에서 유효 그룹을 버리고 무소속이 된다.
     * {@code resolveAuthrtCd} 도 {@code candidates} 로 빈 값을 먼저 걸러낸 뒤 첫 일치를 쓴다 —
     * <b>같은 파일 안에 비대칭을 만들지 않는다.</b>
     *
     * <p>🔴 <b>30자로 잘라서 돌려준다</b> — {@code dept_cd} 는 {@code VARCHAR(30)} 이고 초과하면
     * {@code value too long} 으로 로그인 전체가 500 이다({@code user_nm} 100자 · {@code user_eml_addr}
     * 200자 선례와 같은 방어). <b>등재도 같은 잘린 값을 쓴다.</b>
     */
    private Optional<String> resolveDeptCd(Map<String, Object> claims) {
        Object raw = claims.get(props.getDeptClaim());
        if (raw == null) {
            return Optional.empty();                // 1행 — 기존 값 유지
        }

        List<String> candidates = new ArrayList<>();
        for (String v : extractRoleValues(raw)) {   // 해체 방식은 롤과 공유한다(원소 trim · 빈 값 보존)
            if (StringUtils.hasText(v)) {
                candidates.add(v);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.of("");                 // 2행 — 명시적 무소속
        }

        // 🔴 (걸러낸 뒤) 원소가 2개 이상이면 첫 원소를 쓰고 WARN 1줄 — IdP 가 순서를 보장하지 않으면
        //    로그인마다 부서가 흔들린다. 그 사실을 로그로 드러내는 것이 목적이다(실측은 1개).
        //    ⚠️ 문구는 검증 grep 이 세므로 바꾸지 않는다.
        if (candidates.size() > 1) {
            log.warn("[auth] '{}' claim 원소가 2개 이상입니다 — 첫 원소를 씁니다 (값={})",
                    props.getDeptClaim(), candidates);
        }
        return Optional.of(truncate(candidates.get(0), DEPT_CODE_MAX));   // 3행 — 덮어쓰기
    }

    /**
     * {@link #resolveDeptCd(Map)} 판정을 엔티티에 반영한다 — <b>등재가 먼저, 세팅이 나중</b>이다.
     *
     * <p>🔴 <b>{@code hasText(cd)} 가 false 면 등재를 건너뛰고 {@code dept_cd} 만 {@code null} 로
     * 세팅한다</b> — 표 2행({@code []}·빈 문자열)이 여기로 흘러오는데,
     * {@link #extractRoleValues(Object)} 가 빈 문자열을 버리지 않으므로 가드가 없으면
     * {@code dept_cd=''}·{@code dept_nm=''} 행이 {@code TB_DEPT} 에 등재된다.
     * {@code dept_nm} 은 NOT NULL 이지만 {@code ''} 로 통과해 <b>조용히 1행</b>이 생기고,
     * {@code /api/depts} 에 빈 옵션이 뜨며 접두 기반 정리에도 걸리지 않는다.
     *
     * <p>🔴 <b>등재 실패 시 {@code dept_cd} 를 손대지 않는다(기존 값 유지)</b> — {@code null} 로 덮으면
     * claim 이 정상인데도 일시 장애가 <b>기존 부서 정보 삭제</b>로 번진다(취지와 반대).
     * 최초 로그인이면 값이 없어 자연히 {@code null} 이다.
     *
     * <p>⚠️ {@code existsById} + {@code save} 는 원자적이지 않아 <b>같은 신규 그룹의 동시 최초 로그인</b>이
     * 실제 도달 경로다 — catch 에서 {@link DeptRegistrar#exists(String)} 를 한 번 더 보고
     * <b>true 면(경합 상대가 먼저 넣었다) 그 값을 그대로 쓴다.</b>
     */
    private void applyDeptCd(UserEntity user, Optional<String> resolved) {
        if (resolved.isEmpty()) {
            return;                                 // 1행 — 손대지 않는다
        }
        String cd = resolved.orElseThrow();
        if (!StringUtils.hasText(cd)) {
            user.setDeptCd(null);                   // 2행 — 명시적 무소속. 🔴 등재는 건너뛴다
            return;
        }

        // 🔴 등재는 별도 빈(REQUIRES_NEW)이 한다 — 이 호출은 이미 프록시를 빠져나온 **경계 바깥**이라
        //    try/catch 가 실제로 동작한다. sync() 안에서 직접 save() 하면 INSERT 가 commit 시점에
        //    나가 이 catch 를 통째로 비껴간다(DeptRegistrar javadoc).
        try {
            deptRegistrar.register(cd);
        } catch (DataAccessException | TransactionException | IllegalStateException e) { // 4.3: DB 쿼리·트랜잭션(REQUIRES_NEW 커밋 포함) 실패 · 상태 오류(ISE)
            log.warn("[auth] TB_DEPT 등재 실패 — dept_cd 를 갱신하지 않습니다 (dept_cd={}, 사유={})",
                    cd, e.getClass().getSimpleName());
            log.debug("[auth] TB_DEPT 등재 실패 상세", e);   // 4.1 — 예외 원문은 DEBUG 로만
            if (!existsQuietly(cd)) {
                return;                             // 🔴 기존 값 유지 (FK 위반도 함께 피한다)
            }
            // 경합 상대가 먼저 넣었다 — 값 자체는 유효하므로 그대로 쓴다.
        }
        user.setDeptCd(cd);
    }

    /** 등재 실패 후의 재확인. 이 조회까지 터져도 로그인을 끊지 않는다. */
    private boolean existsQuietly(String deptCd) {
        try {
            return deptRegistrar.exists(deptCd);
        } catch (DataAccessException | TransactionException | IllegalStateException e) { // 4.3: DB 쿼리·트랜잭션(REQUIRES_NEW 커밋 포함) 실패 · 상태 오류(ISE)
            log.warn("[auth] TB_DEPT 존재 확인 실패 (dept_cd={}, 사유={})", deptCd, e.getClass().getSimpleName());
            log.debug("[auth] TB_DEPT 존재 확인 실패 상세 (dept_cd={})", deptCd, e);   // 4.1 — 예외 원문은 DEBUG 로만
            return false;
        }
    }

    /**
     * claim 값을 문자열 목록으로 편다 — <b>원소 {@code trim()} + 순서 보존</b>이 전부다.
     *
     * <p>🔴 <b>빈 문자열을 버리지 않는다</b> — 종전 구현에 있던 {@code hasText} 필터를 없앴다.
     * 여기서도 거르면 {@code [""]}(강등)와 키 부재(유지)가 다시 뭉개진다 —
     * 거르는 판단은 {@link #resolveAuthrtCd(Map)} 가 전담한다.
     *
     * <p>🔴 원소 {@code trim()} 을 빠뜨리면 앞뒤 공백만 trim 하는 규칙과 어긋나 {@code [" manager "]} 가
     * 강등 + WARN 으로 새고 {@code ["  "]} 가 조용한 강등이 아니라 WARN 강등이 된다.
     */
    private List<String> extractRoleValues(Object raw) {
        List<String> roleValues = new ArrayList<>();
        if (raw == null) {
            return roleValues;
        }
        if (raw instanceof Collection<?> col) {
            for (Object o : col) {
                String v = asText(o);
                roleValues.add(v == null ? null : v.trim());
            }
        } else {
            // 문자열도 컬렉션도 아니면(숫자·불린·맵 등) String.valueOf() 로 문자열 취급한다 —
            // 이 분기가 없으면 claim 이 문자열이 아닌 순간 아무 규칙에도 걸리지 않는다.
            String v = asText(raw);
            roleValues.add(v == null ? null : v.trim());
        }
        return roleValues;
    }

    /**
     * 20자 3단 규칙. <b>결과는 항상 20자 이하다.</b>
     *
     * <p>① 앞 20자 → ② 선점됐으면 앞 <b>17</b>자 + {@code -} + N(2..99) → ③ 99 초과면 앞 11자 +
     * {@code -} + {@code sha256(sub)} 앞 8자. 그마저 충돌하면 {@link IllegalStateException}.
     *
     * <p>🔴 17 인 이유는 {@code 20 - len("-") - len("99") = 17} 이다.
     * <b>"20자로 자른 뒤 접미를 붙인다" 로 구현하면 22자가 되어 {@code value too long} 으로 죽는다.</b>
     *
     * <p>🔴 잡을 제약은 {@code pk_tb_user (user_id)} 다 — 절단 충돌은 {@code sub} 가 서로 다른 두 사람
     * 사이에서 일어나므로 {@code uq_tb_user_cert_id} 는 통과하고 PK 만 깨진다.
     */
    private String allocateUserId(String username, String certId) {
        String base = StringUtils.hasText(username) ? username : certId;

        String candidate = truncate(base, USER_ID_MAX);
        if (!userRepository.existsByUserId(candidate)) {
            return candidate;
        }
        String stem = truncate(base, USER_ID_MAX - 3);   // 20 - "-" - "99" = 17
        for (int n = 2; n <= 99; n++) {
            String next = stem + "-" + n;
            if (!userRepository.existsByUserId(next)) {
                return next;
            }
        }
        String hashed = truncate(base, USER_ID_MAX - 9) + "-" + sha256Hex(certId).substring(0, 8);
        if (!userRepository.existsByUserId(hashed)) {
            return hashed;
        }
        // 조용히 남의 계정에 붙는 것보다 로그인을 실패시키는 편이 낫다.
        throw new IllegalStateException("user_id 후보를 배정할 수 없습니다 (sub=" + certId + ")");
    }

    private static String truncateNullable(String s, int max) {
        return s == null ? null : truncate(s, max);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String asText(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    private static OAuth2AuthenticationException authException(String code, String message) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, message, null), message);
    }
}
