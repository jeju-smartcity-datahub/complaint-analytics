package jeju.datahub.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 인증 설정 (TSK-4076 — {@code app.auth.*}).
 *
 * <p>🔴 <b>prefix 는 {@code app.auth} 다.</b> {@code app} 은 앱 공통 설정의 네임스페이스라
 * 인증 설정은 그 아래 {@code auth} 로 한 단계 내려 둔다.
 *
 * <p>🔴 <b>모든 필드에 Java 기본값을 준다</b> — 프로파일 yml 이 키를 빠뜨려도 null 이 흘러가지 않게 한다.
 * {@code usernameClaim}/{@code registrationId} 가 null 이면 Step 5 의 {@code DefaultOidcUser}·
 * {@code OAuth2AuthenticationToken} 생성자가 {@code Assert.hasText} 로 즉시
 * {@code IllegalArgumentException}(→ 500)이고, {@code defaultAuthrtCd} 가 null 이면
 * {@code TB_USER.user_authrt_cd} NOT NULL + FK 위반이다.
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** oidc | mock (D11) */
    private String mode = "oidc";
    private String registrationId = "dhub2";
    private String usernameClaim = "preferred_username";
    private String nameClaim = "name";
    private String emailClaim = "email";
    /** 🔴 TSK-4163 — 롤 판정 claim 은 {@code type} 이다(Dtonic 확정 2026-09-21) */
    private String roleClaim = "type";
    /**
     * 🔴 TSK-4183 D1·D2 — 부서 판정 claim 은 {@code groups} 다.
     *
     * <p>{@code type}(롤)과 달리 <b>{@code /userinfo} 표준 claim</b> 이라 OIDC·비밀번호 로그인
     * 양쪽에서 그대로 온다 — audience 벽이 없어 {@code DhubUserProfileClient} 같은
     * <b>별도 API 주입이 필요 없다</b>(D2).
     */
    private String deptClaim = "groups";
    /** 신규(D3) · 빈 값(D4) · 미등록 값(D5) 에 부여할 최소 권한 */
    private String defaultAuthrtCd = "ROLE_USER";
    /**
     * {type 값} → {authrt_cd} (TSK-4163 D2). 🔴 <b>claim 이 있으면 로그인마다 덮어쓴다</b> —
     * IdP 가 권한의 정본이라 DB 수동 승격은 다음 로그인에 사라진다.
     * 🔴 <b>키는 항상 소문자</b>로 적는다(D6) — 조회가 {@code trim().toLowerCase(Locale.ROOT)}
     * 정규화 뒤에 일어나고, Spring Boot relaxed binding 도 대괄호 없는 Map 키를 소문자로 정규화한다.
     * 매핑에 없는 값은 {@code defaultAuthrtCd} + WARN 1줄이다(D5).
     */
    private Map<String, String> roleMapping = new LinkedHashMap<>();
    /**
     * 🔴 TSK-4163 Step 5 (D8) — {@code type} 을 조달하는 Manager 자체 API 경로.
     * {@code type} 은 OIDC claim 이 아니라 이 API 의 <b>응답 필드</b>다(2026-09-23 실측).
     * base 는 issuer 에서 조립한다 — 여기에는 <b>경로만</b> 적는다.
     * 🔴 <b>비면 호출 자체를 건너뛴다</b>(끄는 스위치 — {@code DhubUserProfileClient}).
     */
    private String profileEndpoint = "/api/v1/auth/me";
    /** 위 조달 호출의 connect·read 타임아웃(ms). 🔴 무한 대기는 로그인 스레드를 묶는다. */
    private int profileTimeoutMs = 3000;
    /** 비면 Step 5 가 ClientRegistration 의 configurationMetadata 로 폴백한다 */
    private String endSessionEndpoint = "";
    private String frontBaseUrl = "http://localhost:5173";
    /**
     * IdP RP-Initiated Logout 으로 브라우저를 보낼지 (D9). <b>기본 false.</b>
     *
     * <p>🔴 <b>2026-09-02 실측 — 지금은 켤 수 없다.</b> Manager 가 브로커라 파라미터 유무로 동작이 갈린다:
     * <ul>
     *   <li>{@code post_logout_redirect_uri} <b>있음</b> → Manager 가 Keycloak 로그아웃으로 전달하되
     *       우리 {@code id_token_hint} 를 버리고 자기 {@code client_id=dhub2-manager} 로 바꾼다.
     *       Keycloak 이 그 클라이언트의 등록 목록과 대조해 <b>400</b>("We are sorry").</li>
     *   <li><b>없음</b> → Manager 가 자기 세션만 끊고 {@code {"message":"Logged out successfully"}}
     *       JSON 을 반환한다. <b>Keycloak SSO 쿠키가 살아 있어 다음 로그인이 자동 통과된다</b>
     *       (= D9 가 막으려던 바로 그 상태). 게다가 사용자는 그 JSON 페이지에 갇힌다.</li>
     * </ul>
     * 즉 <b>둘 다 D9 를 만족하지 못한다.</b> 그래서 기본값은 "IdP 로 보내지 않는다" 이고, 프론트는
     * {@code idpLogoutUrl} 이 null 이라 곧바로 {@code /login} 으로 간다(우리 세션은 이미 끊겼다).
     *
     * <p>디토닉이 {@code dhub2-manager} 의 <b>Valid post logout redirect URIs</b> 에
     * 우리 오리진({@code http://localhost:5173/login} 등)을 등록해 주면 이 값을 true 로 올린다
     * (Step 0 문의 3번 · 범위 밖 #7). 그때 비로소 Keycloak SSO 까지 끊긴다.
     */
    private boolean idpLogoutEnabled = false;
    /** 🔴 운영 필터체인 스위치. test 프로파일만 false (Step 3·3.5 의 배타 스위치) */
    private boolean securityEnabled = true;

    /**
     * 🔴 TSK-4349 D2 — 한시적 진단 로그 스위치({@code AuthDiagLog}). 켜면 토큰 지문·claim 과 userinfo·me 응답이
     * INFO 로 남는다(토큰 원문은 남기지 않는다). 운영서버 확인 후 끄고, 코드는 Step 8 에서 걷어낸다.
     */
    private boolean diagLogEnabled = false;

    /**
     * TSK-4349 D6 — RFC 7009 revoke 엔드포인트. 비면 discovery {@code revocation_endpoint} 폴백, 그것도 없으면 skip.
     */
    private String revocationEndpoint = "";

    /** TSK-4349 D3 — OIDC 세션 로그아웃 시 로그인 토큰 revoke 를 시도할지. */
    private boolean revokeOnLogout = true;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getRegistrationId() { return registrationId; }
    public void setRegistrationId(String registrationId) { this.registrationId = registrationId; }

    public String getUsernameClaim() { return usernameClaim; }
    public void setUsernameClaim(String usernameClaim) { this.usernameClaim = usernameClaim; }

    public String getNameClaim() { return nameClaim; }
    public void setNameClaim(String nameClaim) { this.nameClaim = nameClaim; }

    public String getEmailClaim() { return emailClaim; }
    public void setEmailClaim(String emailClaim) { this.emailClaim = emailClaim; }

    public String getRoleClaim() { return roleClaim; }
    public void setRoleClaim(String roleClaim) { this.roleClaim = roleClaim; }

    public String getDeptClaim() { return deptClaim; }
    public void setDeptClaim(String deptClaim) { this.deptClaim = deptClaim; }

    public String getDefaultAuthrtCd() { return defaultAuthrtCd; }
    public void setDefaultAuthrtCd(String defaultAuthrtCd) { this.defaultAuthrtCd = defaultAuthrtCd; }

    public Map<String, String> getRoleMapping() { return roleMapping; }
    public void setRoleMapping(Map<String, String> roleMapping) { this.roleMapping = roleMapping; }

    public String getProfileEndpoint() { return profileEndpoint; }
    public void setProfileEndpoint(String profileEndpoint) { this.profileEndpoint = profileEndpoint; }

    public int getProfileTimeoutMs() { return profileTimeoutMs; }
    public void setProfileTimeoutMs(int profileTimeoutMs) { this.profileTimeoutMs = profileTimeoutMs; }

    public String getEndSessionEndpoint() { return endSessionEndpoint; }
    public void setEndSessionEndpoint(String endSessionEndpoint) { this.endSessionEndpoint = endSessionEndpoint; }

    public String getFrontBaseUrl() { return frontBaseUrl; }
    public void setFrontBaseUrl(String frontBaseUrl) { this.frontBaseUrl = frontBaseUrl; }

    public boolean isIdpLogoutEnabled() { return idpLogoutEnabled; }
    public void setIdpLogoutEnabled(boolean idpLogoutEnabled) { this.idpLogoutEnabled = idpLogoutEnabled; }

    public boolean isSecurityEnabled() { return securityEnabled; }
    public void setSecurityEnabled(boolean securityEnabled) { this.securityEnabled = securityEnabled; }

    public boolean isDiagLogEnabled() { return diagLogEnabled; }
    public void setDiagLogEnabled(boolean diagLogEnabled) { this.diagLogEnabled = diagLogEnabled; }

    public String getRevocationEndpoint() { return revocationEndpoint; }
    public void setRevocationEndpoint(String revocationEndpoint) { this.revocationEndpoint = revocationEndpoint; }

    public boolean isRevokeOnLogout() { return revokeOnLogout; }
    public void setRevokeOnLogout(boolean revokeOnLogout) { this.revokeOnLogout = revokeOnLogout; }
}
