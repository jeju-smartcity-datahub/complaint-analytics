package jeju.datahub.auth.web;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.transaction.TransactionException;
import org.springframework.dao.DataAccessException;
import jeju.datahub.auth.service.impl.DhubTokenExchangeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jeju.datahub.agent.service.AgentTokenStore;
import jeju.datahub.auth.service.AuthMethod;
import jeju.datahub.auth.service.AuthProperties;
import jeju.datahub.auth.service.AuthUserSyncService;
import jeju.datahub.auth.service.AuthUserVO;
import jeju.datahub.auth.service.LoginUserVO;
import jeju.datahub.auth.service.OidcLoginTokenStore;
import jeju.datahub.auth.service.impl.AuthDiagLog;
import jeju.datahub.auth.service.impl.AuthMapper;
import jeju.datahub.auth.service.impl.DhubPasswordAuthClient;
import jeju.datahub.auth.service.impl.DhubTokenExchangeClient;
import jeju.datahub.auth.service.impl.DhubUserProfileClient;
import jeju.datahub.auth.service.impl.LoginAttemptGuard;
import jeju.datahub.auth.service.impl.OidcTokenRevoker;
import jeju.datahub.common.SesnIdHasher;
import jeju.datahub.lgnhstry.service.LgnHstryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static jeju.datahub.auth.service.AuthMethod.*;
import static jeju.datahub.auth.service.impl.DhubTokenExchangeClient.AUDIENCE_AGENT;
import static jeju.datahub.auth.service.impl.DhubTokenExchangeClient.AUDIENCE_MANAGER;

/**
 * OIDC 로그인 흐름 (옵션 B).
 *
 * <p>표준 {@code oauth2Login()} 을 쓰지 않는다 — 등록된 redirect_uri 가 경로 없는
 * {@code http://localhost:5173} 이라 {@code /login/oauth2/code/{id}} 콜백을 받을 수 없다.
 * 그래서 인가 URL 생성과 코드 교환을 여기서 직접 한다.
 *
 * <p>🔴 <b>하류 의존 있음</b> — 로그인 이력 기능이 이 컨트롤러의 <b>콜백 성공·실패·로그아웃 3지점</b>에
 * 로그인 이력 기록을 끼운다. 지점을 없애거나 합치지 않는다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // 레포 관례 — @Slf4j 가 아니라 LoggerFactory 로 선언한다
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** 세션 키 3종 — 프레임워크가 주지 않는다. 브라우저로 내려보내지 않는다. */
    private static final String OIDC_STATE = "OIDC_STATE";
    private static final String OIDC_VERIFIER = "OIDC_VERIFIER";
    private static final String OIDC_ISSUED_AT = "OIDC_ISSUED_AT";

    // 🔁 로그인 방식 세션 키·값(AUTH_METHOD · _PASSWORD · _OIDC · _MOCK)은
    //    AuthMethod(auth.service) 로 옮겼다 — SessionTokenRefresher(auth.service.impl)가 읽어야 해서다.

    /**
     * 🔴 실패는 <b>401 고정</b>이고 본문은 이 한 문장뿐이다.
     * Manager 가 "비밀번호 오류"와 "없는 계정"을 같은 응답으로 주므로 우리도 그 이상을
     * 노출하지 않는다. upstream 응답 본문을 그대로 전달하면 IdP 가 나중에 {@code "user is locked"}
     * 같은 것을 줄 때 <b>계정 열거 창구</b>가 된다.
     */
    private static final String LOGIN_FAILED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";

    /** 인가 요청의 유효 시간. 넘기면 콜백을 400 으로 거절한다. */
    private static final Duration AUTHZ_TTL = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final ClientRegistrationRepository repo;
    private final AuthProperties props;
    private final SecurityContextRepository securityContextRepository;
    private final AuthUserSyncService authUserSyncService;
    private final AuthMapper authMapper;
    /** 로그인 이력 기록 (콜백 성공·실패·로그아웃 3지점). */
    private final LgnHstryService lgnHstryService;
    /** Agent API 토큰 보관 (콜백 성공 뒤 세션에만 담는다). */
    private final AgentTokenStore agentTokenStore;
    /** 이메일/비밀번호 직접 로그인 (Manager 왕복은 전부 이 클래스가 한다). */
    private final DhubPasswordAuthClient passwordAuthClient;
    /** 시도 횟수 제한 (이 엔드포인트가 만든 브루트포스 창구를 완화한다). */
    private final LoginAttemptGuard loginAttemptGuard;
    /**
     * 🔴 롤 판정 값({@code type}) 조달 — {@code /userinfo} 가 아니라
     * Manager 자체 API {@code GET /api/v1/auth/me} 의 응답 필드다. OIDC 경로는
     * 로그인 토큰이 아니라 {@code dhub2-manager} 교환 토큰을 넘긴다(로그인 토큰은 그 API 에서 401).
     */
    private final DhubUserProfileClient profileClient;
    /** 🔴 대상별 토큰 교환 (교환은 이 클라이언트 한 곳에서만 한다). */
    private final DhubTokenExchangeClient tokenExchangeClient;
    /** OIDC 로그인 토큰 세션 보관 (키 상수는 그 클래스에만 있다). */
    private final OidcLoginTokenStore oidcLoginTokenStore;
    /** 로그아웃 시 로그인 토큰 revoke (실패해도 로그아웃은 계속한다). */
    private final OidcTokenRevoker tokenRevoker;

    public AuthController(ClientRegistrationRepository repo,
                          AuthProperties props,
                          SecurityContextRepository securityContextRepository,
                          AuthUserSyncService authUserSyncService,
                          AuthMapper authMapper,
                          LgnHstryService lgnHstryService,
                          AgentTokenStore agentTokenStore,
                          DhubPasswordAuthClient passwordAuthClient,
                          LoginAttemptGuard loginAttemptGuard,
                          DhubUserProfileClient profileClient,
                          DhubTokenExchangeClient tokenExchangeClient,
                          OidcLoginTokenStore oidcLoginTokenStore,
                          OidcTokenRevoker tokenRevoker) {
        this.repo = repo;
        this.props = props;
        this.securityContextRepository = securityContextRepository;
        this.authUserSyncService = authUserSyncService;
        this.authMapper = authMapper;
        this.lgnHstryService = lgnHstryService;
        this.agentTokenStore = agentTokenStore;
        this.passwordAuthClient = passwordAuthClient;
        this.loginAttemptGuard = loginAttemptGuard;
        this.profileClient = profileClient;
        this.tokenExchangeClient = tokenExchangeClient;
        this.oidcLoginTokenStore = oidcLoginTokenStore;
        this.tokenRevoker = tokenRevoker;
    }

    // ---------------------------------------------------------------- authorize-url

    /**
     * 인가 URL 발급 — {@code state}·{@code code_verifier} 를 만들어 <b>세션에만</b> 저장한다.
     *
     * <p>🔴 PKCE 는 우리가 직접 만든다 — {@code code_challenge} 가 빠지면 IdP 가 <b>422</b> 를 낸다.
     * {@code code_challenge_method} 는 {@code S256} 하나뿐이다(1.2절).
     * 🔴 {@code state} 는 32바이트 이상이어야 한다 — 짧으면 Manager 가 {@code 400 Invalid state} 다(1.3절).
     */
    @GetMapping("/authorize-url")
    public Map<String, String> authorizeUrl(HttpSession session) {
        ClientRegistration reg = repo.findByRegistrationId(props.getRegistrationId());
        if (reg == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "ClientRegistration 을 찾을 수 없습니다: " + props.getRegistrationId());
        }

        String state = base64Url(random(32));           // 🔴 32바이트 이상 (1.3절)
        String verifier = base64Url(random(64));
        String challenge = base64Url(sha256(verifier)); // S256 (1.2절)

        session.setAttribute(OIDC_STATE, state);
        session.setAttribute(OIDC_VERIFIER, verifier);  // 🔴 브라우저로 내리지 않는다
        session.setAttribute(OIDC_ISSUED_AT, Instant.now());

        // 🔴 .encode() 를 빠뜨리면 scope 의 공백이 그대로 남아 URL 이 깨진다.
        //    Spring 의 QUERY_PARAM 인코딩은 ':'·'/' 를 허용 문자로 둬서 redirect_uri 의 '://' 는
        //    그대로 남는다 — 등록값과 문자 단위로 일치해야 하는 값이라 오히려 다행이다(1.3절).
        String url = UriComponentsBuilder
                .fromUriString(reg.getProviderDetails().getAuthorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", reg.getClientId())
                .queryParam("redirect_uri", reg.getRedirectUri())
                .queryParam("scope", String.join(" ", reg.getScopes()))
                .queryParam("state", state)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .encode().build().toUriString();

        return Map.of("authorizeUrl", url);
    }

    // ---------------------------------------------------------------- callback

    /**
     * 콜백 — {@code code}·{@code state} 를 받아 토큰을 교환하고 세션 로그인을 확립한다.
     *
     * <p>🔴 {@code changeSessionId()} 이후의 세션 ID 가 {@code sesn_id} 해시 기준이다.
     */
    @PostMapping("/callback")
    public Map<String, Object> callback(@RequestBody Map<String, String> body,
                                        HttpSession session,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        ClientRegistration reg = repo.findByRegistrationId(props.getRegistrationId());

        // 1) state 대조 — 불일치·부재·만료면 400. 대조 후 세션에서 즉시 제거(재사용 차단)
        String code = body.get("code");
        String state = body.get("state");
        String sessionState = (String) session.getAttribute(OIDC_STATE);
        String verifier = (String) session.getAttribute(OIDC_VERIFIER);
        Instant issuedAt = (Instant) session.getAttribute(OIDC_ISSUED_AT);
        if (!StringUtils.hasText(code) || sessionState == null || !sessionState.equals(state)
                || verifier == null || issuedAt == null
                || issuedAt.plus(AUTHZ_TTL).isBefore(Instant.now())) {
            // 🔴 실패 기록은 예외를 던지기 **전에** 남긴다. 신원 확인 전이라 userId 는 null.
            try {
                lgnHstryService.recordFailure(null, request.getRemoteAddr(), "STATE_MISMATCH");
            } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) { // 4.3: 이력 기록(REQUIRES_NEW JPA)이 낼 수 있는 갈래 — 쿼리·커밋·인자·세션 해시. 로그인을 끊지 않는다
                log.warn("[lgn-hstry] 실패 기록 실패 (rsn=STATE_MISMATCH)", e);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state 가 유효하지 않습니다.");
        }
        session.removeAttribute(OIDC_STATE);        // 🔴 재사용 차단 — 대조 직후 제거
        session.removeAttribute(OIDC_VERIFIER);
        session.removeAttribute(OIDC_ISSUED_AT);

        // 2) 토큰 교환 — Spring 것을 그대로 쓴다 (client_secret_basic·code_verifier 를 알아서 싣는다)
        var tokenResponseClient = new RestClientAuthorizationCodeTokenResponseClient();  // SS 6.4 권장
        OAuth2AuthorizationRequest authzReq = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(reg.getProviderDetails().getAuthorizationUri())
                .clientId(reg.getClientId()).redirectUri(reg.getRedirectUri())
                .scopes(reg.getScopes()).state(state)
                .attributes(a -> a.put(PkceParameterNames.CODE_VERIFIER, verifier))
                .build();
        OAuth2AuthorizationResponse authzRes = OAuth2AuthorizationResponse.success(code)
                .redirectUri(reg.getRedirectUri()).state(state).build();
        OAuth2AccessTokenResponse tokenRes;
        try {
            tokenRes = tokenResponseClient.getTokenResponse(
                    new OAuth2AuthorizationCodeGrantRequest(reg,
                            new OAuth2AuthorizationExchange(authzReq, authzRes)));
        } catch (OAuth2AuthorizationException e) {
            // 🔴 Manager 는 Keycloak 앞의 브로커라 장애 지점이 둘이다. 어느 쪽이 거절했는지
            //    가르려면 응답 본문({"detail":...})이 로그에 남아야 한다.
            //    ⚠️ 여기에 code/verifier/client_secret 을 찍지 않는다 — 로그가 유출 경로가 된다.
            log.error("[oidc] 토큰 교환 실패 — error={}, description={}",
                    e.getError().getErrorCode(), e.getError().getDescription(), e);
            try {
                lgnHstryService.recordFailure(null, request.getRemoteAddr(), "TOKEN_EXCHANGE_FAILED");
            } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException ex) { // 4.3: 이력 기록(REQUIRES_NEW JPA)이 낼 수 있는 갈래 — 쿼리·커밋·인자·세션 해시. 로그인을 끊지 않는다
                log.warn("[lgn-hstry] 실패 기록 실패 (rsn=TOKEN_EXCHANGE_FAILED)", ex);
            }
            // 교환 실패는 인증 실패다. 401 로 내려 프론트 콜백 게이트가 /login?error 로 안내하게 한다
            // (500 + 스택트레이스로 내보내지 않는다).
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "토큰 교환에 실패했습니다.");
        }

        // 3) id_token 검증 → OidcIdToken · OidcUserInfo
        //    JwtDecoder 를 직접 만들지 않는다 — OidcIdTokenDecoderFactory 가
        //    jwks_uri·RS256·iss/aud/exp 검증까지 규격대로 해 준다(1.1절).
        Jwt jwt = new OidcIdTokenDecoderFactory().createDecoder(reg)
                .decode((String) tokenRes.getAdditionalParameters().get(OidcParameterNames.ID_TOKEN));
        OidcIdToken oidcIdToken = new OidcIdToken(
                jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
        Map<String, Object> userInfoClaims = fetchUserInfo(reg, tokenRes.getAccessToken().getTokenValue());

        // 🔴 **병합 전 DEBUG 로그** — 아래 401 가드보다 **먼저** 찍는다.
        //    ⓐ type·preferred_username 을 어느 쪽이 실어 주는지가 롤 매핑의 판단 근거이고,
        //    ⓑ sub 불일치로 401 이 나면 이 두 줄이 유일한 진단 자료다.
        //    ⚠️ 확인이 끝나면 제거하거나 마스킹한다 — claim 에 개인정보가 섞인다.
        log.debug("[oidc] id_token claims = {}", oidcIdToken.getClaims());
        log.debug("[oidc] userinfo claims = {}", userInfoClaims);

        // OIDC Core 5.3.2 — userinfo 의 sub 가 id_token 의 sub 와 같은지 RP 가 검증해야 한다(토큰 치환 방어).
        if (userInfoClaims == null || userInfoClaims.isEmpty()
                || !oidcIdToken.getSubject().equals(userInfoClaims.get("sub"))) {
            // 빈 응답과 sub 불일치는 원인이 달라 사유 코드를 가른다(둘 다 화이트리스트 6종 안).
            String failRsn = (userInfoClaims == null || userInfoClaims.isEmpty())
                    ? "USERINFO_EMPTY" : "SUB_MISMATCH";
            try {
                lgnHstryService.recordFailure(null, request.getRemoteAddr(), failRsn);
            } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) { // 4.3: 이력 기록(REQUIRES_NEW JPA)이 낼 수 있는 갈래 — 쿼리·커밋·인자·세션 해시. 로그인을 끊지 않는다
                log.warn("[lgn-hstry] 실패 기록 실패 (rsn={})", failRsn, e);
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "userinfo 의 sub 가 일치하지 않습니다.");
        }

        // 🔴 preferred_username 이 id_token·/userinfo 어디에도 없을 때의 폴백.
        //    폴백값은 userInfoClaims 에 넣는다 — DefaultOidcUser 는 우리 병합 맵이 아니라
        //    OidcUserAuthority.collectClaims(idToken, userInfo) 를 보기 때문이다.
        String usernameClaim = props.getUsernameClaim();
        Object username = oidcIdToken.getClaims()
                .getOrDefault(usernameClaim, userInfoClaims.get(usernameClaim));
        if (username == null || !StringUtils.hasText(username.toString())) {
            Object eml = oidcIdToken.getClaims()
                    .getOrDefault(props.getEmailClaim(), userInfoClaims.get(props.getEmailClaim()));
            String fallback = (eml != null && StringUtils.hasText(eml.toString()))
                    ? eml.toString().split("@")[0] : oidcIdToken.getSubject();
            userInfoClaims = new HashMap<>(userInfoClaims);   // fetchUserInfo 결과가 불변 맵일 수 있다
            userInfoClaims.put(usernameClaim, fallback);
            log.warn("[oidc] {} 부재 — 폴백 적용 (sub={})", usernameClaim, oidcIdToken.getSubject());
        }
        OidcUserInfo userInfo = new OidcUserInfo(userInfoClaims);

        // 4) 🔴 AuthUserSyncService 를 그대로 호출한다 (upsert 로직 복붙 금지).
        //    넘기는 것은 userInfo.getClaims() 가 아니라 **id_token 과 병합한 맵**이다(id_token 우선) —
        //    병합 순서를 DefaultOidcUser 내부(OidcUserAuthority.collectClaims)와 같게 맞춘다.
        //    어긋나면 sync 가 기록한 cert_id 와 /me 의 getSubject() 가 갈려 로그인 직후 튕긴다.
        Map<String, Object> claims = new HashMap<>(userInfoClaims);
        claims.putAll(oidcIdToken.getClaims());

        // 🔴 — 롤 판정 값(type)을 claim 맵에 주입한다. 2026-09-23 실측상
        //    type 은 id_token 에도 /userinfo 에도 없고 Manager 자체 API 의 응답 필드다.
        //    🔴 sync 는 claim 맵만 보는 계약 그대로다 — claim 으로 오게 되면 이 한 줄만 걷어낸다.
        // 🔴 로그인 토큰은 /api/v1/auth/me 에서 401 이다. dhub2-manager 로 교환해 보낸다.
        //    교환 실패는 삼킨다 → type 부재 → 기존 롤 유지. 로그인을 막지 않는다.
        String loginAccess = tokenRes.getAccessToken().getTokenValue();          // 구현이 재사용한다
        OAuth2AccessTokenResponse managerRes = exchangeQuietly(loginAccess, AUDIENCE_MANAGER);   // 실패 → null
        profileClient.injectType(claims,
                managerRes == null ? null : managerRes.getAccessToken().getTokenValue());   // null 이면 fetchType 이 empty 로 건너뛴다(기존 가드)

        AuthUserVO vo;
        try {
            vo = authUserSyncService.sync(claims);
        } catch (OAuth2AuthenticationException e) {
            // 🔴 use_yn='N' 정지 사용자는 access_denied 다(AuthUserSyncServiceImpl). 그 밖(sub 부재 등)은 UNKNOWN.
            //    이 시점에는 user_id 가 배정되기 전이라 userId 는 null 로 남긴다(실패 행은 userId 가 null 이어도 저장된다).
            String failRsn = "access_denied".equals(e.getError().getErrorCode()) ? "USER_DISABLED" : "UNKNOWN";
            try {
                lgnHstryService.recordFailure(null, request.getRemoteAddr(), failRsn);
            } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException ex) { // 4.3: 이력 기록(REQUIRES_NEW JPA)이 낼 수 있는 갈래 — 쿼리·커밋·인자·세션 해시. 로그인을 끊지 않는다
                log.warn("[lgn-hstry] 실패 기록 실패 (rsn={})", failRsn, ex);
            }
            throw e;
        }

        // 4-1) 🔴 Agent API 는 로그인 토큰을 받지 않는다(aud 에 dhub2-agent 없음).
        //      로그인 토큰은 OidcLoginTokenStore 에 따로 두고(재교환의 원재료), Agent 저장소에는
        //      dhub2-agent 로 **교환한 토큰**을 담는다. refresh 칸은 로그인 refresh 로 채운다(
        //      비면 refreshOrFail 이 갱신에 진입조차 못 하고 503). 교환 실패는 로그인을 막지 않는다(
        //      Agent 토큰 미저장 → AI 화면 503, 재로그인으로 복구).
        //      sync 성공(= 우리 사용자로 확정) **뒤에** 세션에만 담는다. 브라우저로 내려보내지 않는다.
        //      ⚠️ 토큰 값은 로그에 남기지 않는다 — AgentTokenStore 가 만료 시각만 찍는다.
        oidcLoginTokenStore.save(session, tokenRes);                                         // 로그인 토큰 — 갱신의 원재료
        OAuth2AccessTokenResponse agentRes = exchangeQuietly(loginAccess, AUDIENCE_AGENT);   // loginAccess 는 선언 · 실패 → null
        if (agentRes != null) {
            // withResponse 는 tokenType·expiresAt·scopes 를 그대로 복사한다 — refresh 칸만 로그인 refresh 로 채운다(갱신 진입 열쇠)
            agentTokenStore.save(session, OAuth2AccessTokenResponse.withResponse(agentRes)
                    .refreshToken(tokenRes.getRefreshToken() == null ? null
                                  : tokenRes.getRefreshToken().getTokenValue())
                    .build());
        }
        // agentRes == null 이면 저장하지 않는다 → AI 화면 503. 로그인은 계속.

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(vo.getUserAuthrtCd()));
        // 🔴 DefaultOidcUser 조립은 여기서만 한다 — 서비스는 Spring 타입을 만들지 않는다(이식성).
        DefaultOidcUser oidcUser =
                new DefaultOidcUser(authorities, oidcIdToken, userInfo, props.getUsernameClaim());

        // 5) 세션 고정 공격 차단 — 로그인 성공 시 세션 ID 를 바꾼다.
        //    🔴 하류 계약: 로그인 이력은 **이 호출 이후의** 세션 ID 를 해시해 sesn_id 로 남긴다.
        request.changeSessionId();

        // 5-1) 🔴 로그인 방식 기록. /logout 은 이 값이 "oidc" 일 때만 IdP 로그아웃 URL 을
        //      만든다. 세션 attribute 는 changeSessionId() 를 건너 살아남으므로 여기서 넣어도 된다.
        session.setAttribute(AUTH_METHOD, AUTH_METHOD_OIDC);

        // 6) principal 은 DefaultOidcUser, 토큰은 OAuth2AuthenticationToken
        var auth = new OAuth2AuthenticationToken(oidcUser, authorities, props.getRegistrationId());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        // 🔴 SS6 은 자동 저장하지 않는다 — 빠뜨리면 응답은 200 인데 다음 요청이 401 이다.
        //    SecurityConfig 의 체인이 쓰는 것과 같은 빈 인스턴스다.
        securityContextRepository.saveContext(ctx, request, response);

        // 7) 🔴 로그인 성공 기록 — 반드시 changeSessionId() **이후**의 세션 ID 를 해시한다.
        //    changeSessionId() 는 void 라 새 ID 는 request 에서 다시 읽는다. 앞에서 읽으면
        //    로그아웃의 closeSession 이 영원히 매칭되지 않아 cntn_end_dt 가 항상 빈다.
        try {
            HttpSession current = request.getSession(false);
            lgnHstryService.recordSuccess(vo.getUserId(), request.getRemoteAddr(),
                    current == null ? null : SesnIdHasher.shortHash(current.getId()));
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) { // 4.3: 이력 기록(REQUIRES_NEW JPA)이 낼 수 있는 갈래 — 쿼리·커밋·인자·세션 해시. 로그인을 끊지 않는다
            log.warn("[lgn-hstry] 성공 기록 실패 (userId={})", vo.getUserId(), e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", vo.getUserId());
        result.put("userNm", vo.getUserNm());
        result.put("userAuthrtCd", vo.getUserAuthrtCd());
        return result;
    }

    // ---------------------------------------------------------------- login (email/password)

    /**
     * 이메일/비밀번호 직접 로그인.
     *
     * <p>🔴 <b>세션 확립 절차는 {@code /api/auth/callback} 과 완전히 같다</b> —
     * ① {@code changeSessionId()} ② {@code DefaultOidcUser} ③ {@code saveContext()}.
     * 셋 중 하나라도 빠지면 응답은 200 인데 다음 요청이 401 이다.
     *
     * <p>🔴 <b>BFF</b> — Manager 를 부르는 것은 브라우저가 아니라 우리다. 토큰은 세션에만 남고
     * 응답 본문에 넣지 않는다. 비밀번호는 <b>어디에도 저장하지 않는다</b> — 받아서 넘기고 끝이다.
     *
     * <p>🔴 어떤 사유든 실패는 {@code 401} + {@link #LOGIN_FAILED_MESSAGE} 하나다.
     * 사유는 <b>이력 테이블에만</b> 남기고 응답으로 알려주지 않는다.
     *
     * <p>🔴 <b>CSRF</b> — {@code SecurityConfig} 의 permitAll 은 <b>CSRF 면제가 아니다</b>.
     * 이 POST 는 {@code X-XSRF-TOKEN} 헤더가 없으면 컨트롤러에 닿기 전에 <b>403</b> 이다
     * ({@code CsrfCookieFilter} 가 GET 응답에 {@code XSRF-TOKEN} 쿠키를 먼저 내려 준다).
     *
     * <p>🔴 자격증명은 {@code @RequestBody} 로만 받는다(쿼리스트링 금지).
     * 비밀번호·토큰은 어떤 레벨로도 로깅하지 않으며, 실패 응답에 요청 본문을 에코하지 않는다.
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        // 🔴 IP 는 getRemoteAddr() 하나면 된다 — forward-headers-strategy: NATIVE 가 켠 RemoteIpValve 가
        //    XFF 를 이미 반영해 덮어쓴 값이다. XFF 를 직접 파싱하면 헤더 위조로 시도 제한이 우회된다.
        String ip = request.getRemoteAddr();

        // 0) 🔴 시도 횟수 제한 — 임계치를 넘었으면 **Manager 를 부르지 않고** 여기서 끊는다.
        //    막는 것이 목적이므로 검사는 반드시 authenticate(...) **앞**이다.
        if (loginAttemptGuard.isBlocked(body.email(), ip)) {
            // 🔴 여기서는 이력을 남기지 않는다 — 차단 중 매 요청마다 REQUIRES_NEW 로 행을 쓰면
            //    막아 놓고도 공격자가 tb_lgn_hstry 를 무한히 부풀릴 수 있다(감사 테이블이 공격면이 된다).
            //    ATTEMPT_BLOCKED 는 아래 실패 경로에서 **차단이 시작되는 순간 1회만** 기록한다.
            return loginBlocked();
        }

        // 1) Manager 로그인 + /userinfo — 🔴 검증 지점은 /userinfo 하나다. 구현이 전담한다.
        DhubPasswordAuthClient.PasswordLoginResult result;
        try {
            result = passwordAuthClient.authenticate(body.email(), body.password());
        } catch (DhubPasswordAuthClient.PasswordLoginException e) {
            // 🔴 사유 코드 이름은 LgnHstryServiceImpl 의 화이트리스트와 같은 철자다
            //    (PASSWORD_MISMATCH 는 이 작업에서 그 목록에 등재했다 — 없으면 UNKNOWN 으로 접힌다).
            boolean justBlocked = loginAttemptGuard.recordFailure(body.email(), ip);
            recordFailureQuietly(null, ip, e.getReason().name());
            if (justBlocked) {
                // 🔴 차단 전이 시점에만 1회. 이후 차단된 요청들은 이력을 남기지 않는다(위 0단계 주석).
                recordFailureQuietly(null, ip, "ATTEMPT_BLOCKED");
            }
            return loginFailed();
        }
        // 🔴 자격증명이 통과한 순간 카운터를 비운다 — 오타 몇 번 뒤 성공한 정상 사용자가
        //    다음 오타로 곧장 잠기지 않게 한다. 아래 sync 실패(정지 계정 등)는 세지 않는다:
        //    비밀번호가 맞은 뒤의 실패는 브루트포스가 아니다.
        loginAttemptGuard.recordSuccess(body.email(), ip);

        // 2) 🔴 TB_USER upsert 는 기존 것을 그대로 쓴다 — claim 맵 계약이 같아 분기가 필요 없다.
        AuthUserVO vo;
        try {
            vo = authUserSyncService.sync(result.claims());
        } catch (OAuth2AuthenticationException e) {
            // use_yn='N' 정지 사용자는 access_denied 다(AuthUserSyncServiceImpl). 그 밖은 UNKNOWN.
            // 🔴 정지 계정임을 응답으로 알려주지 않는다 — 이력에만 남긴다.
            String failRsn = "access_denied".equals(e.getError().getErrorCode()) ? "USER_DISABLED" : "UNKNOWN";
            recordFailureQuietly(null, ip, failRsn);
            return loginFailed();
        }

        // 3) 🔴 principal 은 합성 OidcIdToken 기반 DefaultOidcUser (MockAuthController 선례).
        //    우리에겐 id_token 이 없고 access token 뿐인데 DefaultOidcUser 가 OidcIdToken 을 요구한다.
        //    이렇게 맞춰 두면 /me·/logout 이 principal 타입 분기 없이 그대로 동작한다.
        //    🔴 수명은 result.expiresInSec()(= 토큰 payload 의 exp - iat) 에서 온다 — 상수 하드코딩 금지.
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(result.expiresInSec());
        OidcIdToken idToken = new OidcIdToken(result.accessToken(), issuedAt, expiresAt, result.claims());
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(vo.getUserAuthrtCd()));
        DefaultOidcUser oidcUser = new DefaultOidcUser(
                authorities, idToken, new OidcUserInfo(result.claims()), props.getUsernameClaim());

        // 4) 세션 고정 공격 차단 — 🔴 changeSessionId() 는 **세션이 이미 있어야** 동작한다.
        //    없으면 `IllegalStateException: Cannot change session ID` 로 500 이다(mock-login 선례).
        //    콜백은 HttpSession 을 파라미터로 받아 항상 세션이 있지만 이 경로는 그렇지 않다.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        // 🔴 세션 attribute 는 changeSessionId() 를 건너 살아남지만, 여기서는 **바꾼 뒤의** 세션을
        //    잡아 이력의 세션 ID 와 같은 것을 쓴다.
        HttpSession session = request.getSession(false);

        // 5) 🔴 로그인 방식 기록. /logout 분기는 구현이 이 값을 읽는다.
        if (session != null) {
            session.setAttribute(AUTH_METHOD, AUTH_METHOD_PASSWORD);
        }

        // 6) 🔴 Agent API 토큰 보관. 이 경로의 토큰만 aud 에 dhub2-agent 를 갖는다.
        //    AgentTokenStore.save(...) 가 OAuth2AccessTokenResponse 를 요구하는데 Manager 의
        //    email/pw 응답은 {access_token, token_type} + Set-Cookie 라 그 타입이 아니다 — 조립해 넘긴다.
        //    🔴 refresh 는 body 가 아니라 Set-Cookie 에서 온 값이다(구현이 파싱). 저장소는 고치지 않는다.
        //    ⚠️ 토큰 값은 로그에 남기지 않는다 — AgentTokenStore 가 만료 시각만 찍는다.
        agentTokenStore.save(session, OAuth2AccessTokenResponse.withToken(result.accessToken())
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(result.expiresInSec())
                .refreshToken(result.refreshToken())
                .build());

        // 7) principal 은 DefaultOidcUser, 토큰은 OAuth2AuthenticationToken — 콜백과 같다.
        var auth = new OAuth2AuthenticationToken(oidcUser, authorities, props.getRegistrationId());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        // 🔴 SS6 은 자동 저장하지 않는다 — 빠뜨리면 응답은 200 인데 다음 요청이 401 이다.
        securityContextRepository.saveContext(ctx, request, response);

        // 8) 🔴 로그인 성공 기록 — 반드시 changeSessionId() **이후**의 세션 ID 를 해시한다.
        //    앞에서 읽으면 로그아웃의 closeSession 이 영원히 매칭되지 않아 cntn_end_dt 가 항상 빈다.
        try {
            HttpSession current = request.getSession(false);
            lgnHstryService.recordSuccess(vo.getUserId(), ip,
                    current == null ? null : SesnIdHasher.shortHash(current.getId()));
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) { // 4.3: 이력 기록(REQUIRES_NEW JPA)이 낼 수 있는 갈래 — 쿼리·커밋·인자·세션 해시. 로그인을 끊지 않는다
            log.warn("[lgn-hstry] 성공 기록 실패 (userId={})", vo.getUserId(), e);
        }

        Map<String, Object> result0 = new HashMap<>();
        result0.put("userId", vo.getUserId());
        result0.put("userNm", vo.getUserNm());
        result0.put("userAuthrtCd", vo.getUserAuthrtCd());
        return ResponseEntity.ok(result0);
    }

    /**
     * 🔴 실패 응답은 이 하나뿐이다. {@code ResponseStatusException} 을 쓰지 않는 이유는
     * 그쪽 기본 본문이 {@code detail} 키의 ProblemDetail 이라 계약({@code message})과 어긋나서다.
     * 🔴 요청 본문·upstream 응답 본문을 <b>절대 에코하지 않는다</b>.
     */
    private static ResponseEntity<Map<String, Object>> loginFailed() {
        Map<String, Object> body = new HashMap<>();
        body.put("message", LOGIN_FAILED_MESSAGE);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * 시도 횟수 제한에 걸린 응답 — {@code 429}.
     *
     * <p>🔴 <b>본문은 {@link #loginFailed()} 와 같은 한 문장이다</b>. "몇 번 남았다"·"언제 풀린다"·
     * "이 계정이 잠겼다" 를 알려주지 않는다 — 그런 문구는 곧 <b>계정 존재 여부</b>와 정책 파라미터를
     * 흘리는 창구다. 같은 이유로 {@code Retry-After} 도 싣지 않는다(차단 길이 = 우회 계획의 입력).
     * 차단 사실은 <b>이력 테이블({@code ATTEMPT_BLOCKED})과 서버 로그에만</b> 남는다.
     */
    private static ResponseEntity<Map<String, Object>> loginBlocked() {
        Map<String, Object> body = new HashMap<>();
        body.put("message", LOGIN_FAILED_MESSAGE);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    /**
     * 🔴 이력 기록 실패가 로그인 응답을 죽이면 안 된다 — 모든 호출을 try/catch 로 감싼다.
     */
    private void recordFailureQuietly(String userId, String ip, String failRsn) {
        try {
            lgnHstryService.recordFailure(userId, ip, failRsn);
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) { // 4.3: 이력 기록(REQUIRES_NEW JPA)이 낼 수 있는 갈래 — 쿼리·커밋·인자·세션 해시. 로그인을 끊지 않는다
            log.warn("[lgn-hstry] 실패 기록 실패 (rsn={})", failRsn, e);
        }
    }

    // ---------------------------------------------------------------- me

    /**
     * 로그인 사용자 + 권한 + 허용 메뉴 목록 (후속 작업이 백엔드 추가 없이 시작하게 한다).
     *
     * <p>🔴 <b>조회 키는 {@code cert_id}(= {@code sub})다</b> — {@code authentication.getName()} 을
     * 쓰면 안 된다. nameAttributeKey 가 {@code preferred_username} 이라 {@code getName()} 은 원문
     * 그대로인데, {@code user_id} 는 규칙으로 20자 절단·접미가 붙어 <b>설계상 둘이 다를 수 있다</b>.
     */
    @GetMapping("/me")
    public LoginUserVO me(@AuthenticationPrincipal OidcUser oidcUser, HttpServletRequest request) {
        // 🔴 익명 요청이면 @AuthenticationPrincipal 이 null 이다. 운영 체인은 401 이 먼저라 닿지 않지만,
        //    테스트 체인(anyRequest permitAll)에서는 그대로 NPE → 500 이라 같은 401 로 맞춘다.
        if (oidcUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String certId = oidcUser.getSubject();      // = 병합 claims 의 sub (콜백 3~4 구간과 같은 값)
        LoginUserVO vo = authMapper.selectLoginUser(certId);
        if (vo == null) {
            // 정지(use_yn='N')·삭제된 사용자의 잔존 세션. 방치하면 NPE(500) 이거나
            // 200 + 빈 본문이라 미인증=401 계약이 깨지고 프론트가 빈 화면을 렌더한다.
            SecurityContextHolder.clearContext();
            HttpSession s = request.getSession(false);
            if (s != null) {
                s.invalidate();
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        vo.setMenuPathNms(authMapper.selectMenuPathNms(vo.getUserAuthrtCd()));
        return vo;
    }

    // ---------------------------------------------------------------- logout

    /**
     * 로그아웃 (RP-Initiated Logout).
     *
     * <p>🔴 <b>순서가 전부다.</b> 세션을 먼저 무효화하면 principal 이 사라져 {@code id_token_hint} 를
     * 만들 수 없고, 그러면 IdP 가 로그아웃을 무시해 "로그아웃했는데 바로 다시 들어가진다" 가 된다.
     * ① id_token 선추출 → ② URL 조립 → ③ 세션 무효화 → ④ JSON 반환.
     *
     * <p>🔴 실제 이동은 프론트가 한다 — 302 를 axios XHR 이 따라가면 IdP 오리진에서 CORS 로 깨진다.
     *
     * <p>🔴 <b>하류 의존</b>: 로그인 이력 기능이 여기에 {@code closeSession} 을 끼운다.
     *
     * <p>🔴: IdP 로그아웃 URL 은 세션의 {@link AuthMethod#AUTH_METHOD} 가
     * {@link AuthMethod#AUTH_METHOD_OIDC} 일 때만 만든다. 비밀번호 로그인은 IdP SSO 세션을 만든 적이 없어
     * 우리 세션만 끊으면 로그아웃이 완결된다.
     *
     * <p>ℹ️ Agent 토큰은 {@code session.invalidate()} 로 세션과 함께 사라지므로
     * {@code AgentTokenStore#clear} 를 따로 부르지 않는다(그 메서드 javadoc 과 일치).
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        // ① 먼저 읽는다 — DefaultOidcUser 가 id_token 원문을 들고 있다.
        //    🔴 instanceof 만으로는 **mock 도 password 도** 걸러내지 못한다.
        //       · mock: MockAuthController 가 더미 OidcIdToken(tokenValue="mock") 을 담는다.
        //       · password: 구현이 access_token 을 담은 **합성 OidcIdToken** 을 담는다.
        //       두 경우 모두 {@code getIdToken() != null} 이 참이라 그 조건만으로 분기하면
        //       실 IdP 에 엉뚱한 토큰을 {@code id_token_hint} 로 보낸다. 지금은
        //       app.auth.idp-logout-enabled=false 라 무해하지만 켜는 순간 터진다.
        //       → 세션에 기록된 **로그인 방식**으로 가른다. 종전의 props.getMode() 기반
        //       mockMode 플래그를 이 조건으로 **일반화한 것**이다(mock 은 AUTH_METHOD=mock 이라
        //       자동으로 걸러진다).
        //    🔴 세션은 여기서 한 번만 잡는다 — ③ 의 invalidate() **이전**이라야 attribute 도
        //       세션 ID 도 읽을 수 있다(순서 함정).
        HttpSession session = request.getSession(false);
        boolean oidcSession =
                AUTH_METHOD_OIDC.equals(session == null ? null : session.getAttribute(AUTH_METHOD));
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = (authentication == null) ? null : authentication.getPrincipal();
        String idToken = (oidcSession && principal instanceof OidcUser oidc && oidc.getIdToken() != null)
                ? oidc.getIdToken().getTokenValue() : null;

        // ② URL 조립 — app.auth.end-session-endpoint, 비면 configurationMetadata 폴백.
        //    🔴 폴백을 코드로 쓴다 — test 프로파일은 discovery 를 안 써서 그 맵이 비어 있다.
        //       둘 다 비면 URL 을 만들지 않는다(fromUriString(null) 로 죽지 않게).
        ClientRegistration reg = repo.findByRegistrationId(props.getRegistrationId());
        String endSession = props.getEndSessionEndpoint();
        if (!StringUtils.hasText(endSession) && reg != null) {
            Object v = reg.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
            endSession = (v == null) ? null : v.toString();
        }
        // 🔴 2026-09-02 실측 — IdP 로그아웃 이동은 **기본으로 하지 않는다**(app.auth.idp-logout-enabled=false).
        //    Manager 가 브로커라 파라미터 유무로 동작이 갈리는데 **둘 다 설계 결정을 만족하지 못한다**:
        //      · post_logout_redirect_uri 있음 → Keycloak 으로 전달되지만 우리 id_token_hint 를 버리고
        //        client_id=dhub2-manager 로 바꿔 보내 Keycloak 이 400 ("We are sorry")
        //      · 없음 → Manager 자기 세션만 끊고 {"message":"Logged out successfully"} JSON 반환.
        //        Keycloak SSO 쿠키가 살아 있어 **다음 로그인이 자동 통과**되고, 사용자는 JSON 에 갇힌다.
        //    그래서 켜기 전까지는 URL 을 만들지 않는다 → 프론트가 곧바로 /login 으로 간다
        //    (우리 세션은 아래 ③ 에서 이미 끊긴다). 디토닉이 dhub2-manager 의 Valid post logout
        //    redirect URIs 에 우리 오리진을 등록해 주면 true 로 올린다(범위 밖 #7).
        String logoutUrl = null;
        if (props.isIdpLogoutEnabled() && idToken != null && StringUtils.hasText(endSession)) {
            logoutUrl = UriComponentsBuilder.fromUriString(endSession)
                    .queryParam("id_token_hint", idToken)
                    // 🔴 Manager 는 이 파라미터가 있어야 Keycloak 로그아웃으로 전달한다(실측).
                    //    등록이 끝난 뒤에만 이 경로가 성립하므로 플래그와 한 몸이다.
                    .queryParam("post_logout_redirect_uri", props.getFrontBaseUrl() + "/login")
                    .encode().build().toUriString();   // 🔴 authorize-url 과 같은 규약
        }

        // ②-1 🔴 IdP 토큰 revoke. 🔴 ③ invalidate() **앞**이어야 한다 — 세션이 끊기면 토큰을 꺼낼 수 없다.
        //      OIDC 세션만. 실패는 OidcTokenRevoker 가 전부 삼킨다 — 로그아웃은 항상 계속한다.
        revokeLoginTokens(session, oidcSession);

        // ③ 그 다음에 끊는다
        SecurityContextHolder.clearContext();
        // 🔴 세션 ID 는 invalidate() **이전**에 읽는다(뒤에서 읽으면 IllegalStateException).
        //    세션 인스턴스는 ① 에서 잡아 둔 것을 그대로 쓴다(AUTH_METHOD 판정과 같은 세션).
        String sesnIdHash = (session == null) ? null : SesnIdHasher.shortHash(session.getId());
        if (session != null) {
            session.invalidate();
        }
        try {
            lgnHstryService.closeSession(sesnIdHash);
        } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException e) { // 4.3: 이력 기록(REQUIRES_NEW JPA)이 낼 수 있는 갈래 — 쿼리·커밋·인자·세션 해시. 로그인을 끊지 않는다
            log.warn("[lgn-hstry] 접속 종료 기록 실패", e);
        }
        // 🔴 세션 무효화는 브라우저의 JSESSIONID 쿠키를 지우지 않는다 — 서버 쪽 표에서만 지운다.
        //    SecurityConfig 가 logout() 을 껐으므로 LogoutFilter 의 deleteCookies 도 없다.
        response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from("JSESSIONID", "").path("/").maxAge(0).httpOnly(true)
                        .secure(request.isSecure()).sameSite("Lax").build().toString());

        // ④ 반환 — 🔴 Map.of 를 쓰지 않는다. null 값에 NPE 를 던져(JDK 명세) mock 세션이면 200 대신
        //    500 이 나고, "200 + idpLogoutUrl null" 이 구조적으로 불가능해진다.
        Map<String, Object> body = new HashMap<>();
        body.put("idpLogoutUrl", logoutUrl);
        return body;
    }

    // ---------------------------------------------------------------- private

    /**
     * OIDC 세션이면 로그인 refresh → access 순으로 revoke. 그 밖은 skip 1줄.
     * access 는 만료된 것이면 생략한다(폐기할 의미가 없다).
     */
    private void revokeLoginTokens(HttpSession session, boolean oidcSession) {
        if (!props.isRevokeOnLogout()) {
            tokenRevoker.logSkip("DISABLED");
            return;
        }
        if (!oidcSession) {
            Object method = (session == null) ? null : session.getAttribute(AUTH_METHOD);
            tokenRevoker.logSkip("AUTH_METHOD_" + (method == null ? "none" : method));
            return;
        }
        tokenRevoker.revoke(oidcLoginTokenStore.refreshToken(session).orElse(null), OidcTokenRevoker.HINT_REFRESH);
        tokenRevoker.revoke(oidcLoginTokenStore.validAccessToken(session, Duration.ZERO).orElse(null),
                OidcTokenRevoker.HINT_ACCESS);
    }

    /**
     * 🔴 대상별 토큰 교환 — 실패를 <b>전부 삼킨다</b>. {@code DhubTokenExchangeException} 뿐
     * 아니라 registration 조회·assert 실패({@link RuntimeException} 전반)도 로그인을 막지 않게 {@code null} 로 접는다.
     * 🔴 문자열이 아니라 <b>응답 객체</b>를 돌려준다 — 교환 토큰의 수명({@code expiresAt})을 쓰는 호출부가 있다.
     * ⚠️ 로그에는 audience·예외 타입만 남긴다 — 메시지에 토큰·secret 이 섞일 수 있다.
     *
     * @return 교환 응답. 실패면 {@code null}
     */
    private OAuth2AccessTokenResponse exchangeQuietly(String subject, String audience) {
        try {
            return tokenExchangeClient.exchange(subject, audience);
        } catch (DhubTokenExchangeException | OAuth2AuthorizationException | RestClientException | IllegalArgumentException | IllegalStateException e) { // 4.3: 교환 실패·registration 부재(ISE)·Assert(IAE)·전송 오류 — 전부 null 로 접는다
            log.warn("[token-exchange] 교환 실패 — 로그인은 계속한다 (audience={}, cause={})",
                    audience, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * {@code /userinfo} 를 Bearer GET 한다 — 프레임워크가 주지 않아 직접 만든다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchUserInfo(ClientRegistration reg, String accessToken) {
        String uri = reg.getProviderDetails().getUserInfoEndpoint().getUri();
        // 🔴 한시적 진단 로그(스위치 OFF 면 찍지 않는다). 예외는 그대로 다시 던져 기존 흐름을 유지한다.
        boolean diag = props.isDiagLogEnabled();
        AuthDiagLog.userinfoRequest(diag, "oidc", uri, accessToken);
        long started = System.nanoTime();
        try {
            ResponseEntity<Map> res = RestClient.create().get().uri(uri)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve().toEntity(Map.class);
            AuthDiagLog.userinfoResponse(diag, "oidc", res.getStatusCode().value(), elapsedMs(started), res.getBody());
            return res.getBody();
        } catch (RestClientResponseException e) {
            AuthDiagLog.userinfoFailure(diag, "oidc", e.getStatusCode().value(), elapsedMs(started),
                    e.getResponseBodyAsString(), e.getClass().getSimpleName());
            throw e;
        } catch (RestClientException e) {
            AuthDiagLog.userinfoFailure(diag, "oidc", null, elapsedMs(started), null, e.getClass().getSimpleName());
            throw e;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static byte[] random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String base64Url(byte[] b) {
        return B64URL.encodeToString(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }
}
