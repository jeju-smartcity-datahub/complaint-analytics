package jeju.datahub.auth.web;

import org.springframework.transaction.TransactionException;
import org.springframework.dao.DataAccessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jeju.datahub.auth.service.AuthMethod;
import jeju.datahub.auth.service.AuthProperties;
import jeju.datahub.auth.service.AuthUserSyncService;
import jeju.datahub.auth.service.AuthUserVO;
import jeju.datahub.common.SesnIdHasher;
import jeju.datahub.lgnhstry.service.LgnHstryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * mock 모드 로그인 (개발·CI 전용).
 *
 * <p>🔴 {@code app.auth.mode=mock} 일 때만 등록된다 — 아니면 이 엔드포인트는 <b>404</b> 다.
 * {@code SecurityConfig} 의 permitAll matcher 는 모드와 무관하게 항상 열려 있다.
 *
 * <p>🔴 <b>dev 는 이 모드가 기본값이라 공개된 무인증 세션 발급 경로가 열린다</b>.
 * 완화책으로 식별자에 {@code mock-} 접두를 고정하고 기동 시 WARN 을 남긴다.
 * 범위 밖 #7(dev 클라이언트 발급) 완료 즉시 {@code AUTH_MODE=oidc} 로 되돌린다.
 *
 * <p>🔴 <b>하류 의존</b>: ·8 이 이 경로에 로그인 이력 기록을 끼우고 통합테스트가
 * 여기에 걸린다 — "쓸 일 없으니 뺀다" 로 정리하지 않는다.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "mock")
public class MockAuthController {

    private static final Logger log = LoggerFactory.getLogger(MockAuthController.class);

    /**
     * 🔴 리터럴 상수로 못박는다 — {@code DELETE ... WHERE user_id='mock-user'} 가 이 값에
     * 걸린다. 바꾸면 그 검증도 함께 고쳐야 한다. {@code user_id} VARCHAR(20) 안에 들어간다.
     */
    private static final String MOCK_ID = "mock-user";
    private static final String MOCK_NM = "mock 사용자";
    private static final String MOCK_EML = "mock-user@example.com";

    private final AuthProperties props;
    private final AuthUserSyncService authUserSyncService;
    private final SecurityContextRepository securityContextRepository;
    /** 로그인 이력 기록 (dev 무인증 경로의 유일한 흔적). */
    private final LgnHstryService lgnHstryService;

    public MockAuthController(AuthProperties props,
                              AuthUserSyncService authUserSyncService,
                              SecurityContextRepository securityContextRepository,
                              LgnHstryService lgnHstryService) {
        this.props = props;
        this.authUserSyncService = authUserSyncService;
        this.securityContextRepository = securityContextRepository;
        this.lgnHstryService = lgnHstryService;
        log.warn("[auth] mock 모드가 켜져 있습니다 — POST /api/auth/mock-login 이 무인증으로 세션을 발급합니다. 운영 반영 금지(R12).");
    }

    /**
     * 🔴 세션 확립 절차는 {@code /api/auth/callback} 과 <b>완전히 같다</b> —
     * ① {@code changeSessionId()} ② 더미 {@code OidcIdToken} 기반 {@code DefaultOidcUser}
     * ③ {@code saveContext()}. 셋 중 하나라도 빠지면 응답은 200 인데 다음 요청이 401 이 되어
     * {@code PUT → 403/200} 검증이 성립하지 않는다.
     */
    @PostMapping("/mock-login")
    public Map<String, Object> mockLogin(HttpServletRequest request, HttpServletResponse response) {
        // 🔴 고정 claim 맵에 sub·preferred_username·name·email 을 반드시 담는다.
        //    OidcIdToken 은 빈 claims 를 거부하고(Assert.notEmpty), DefaultOidcUser 는
        //    nameAttributeKey(preferred_username) 클레임이 없으면 생성자에서 죽는다.
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", MOCK_ID);                 // → TB_USER.cert_id
        claims.put(props.getUsernameClaim(), MOCK_ID);   // → TB_USER.user_id
        claims.put(props.getNameClaim(), MOCK_NM);
        claims.put(props.getEmailClaim(), MOCK_EML);

        // mock 경로는 병합 대상이 없어 고정 claim 맵을 그대로 넘긴다(흐름 1번).
        // 🔴 use_yn='N' 이면 여기서 OAuth2AuthenticationException 이다 — 실 IdP 없이 USER_DISABLED
        //    실패 행을 만들 수 있는 유일한 경로라 반드시 기록한다.
        AuthUserVO vo;
        try {
            vo = authUserSyncService.sync(claims);
        } catch (OAuth2AuthenticationException e) {
            try {
                lgnHstryService.recordFailure(MOCK_ID, request.getRemoteAddr(), "USER_DISABLED");
            } catch (DataAccessException | TransactionException | IllegalArgumentException | IllegalStateException ex) { // 4.3: 이력 기록(REQUIRES_NEW JPA)이 낼 수 있는 갈래 — 쿼리·커밋·인자·세션 해시. 로그인을 끊지 않는다
                log.warn("[lgn-hstry] 실패 기록 실패 (rsn=USER_DISABLED, userId={})", MOCK_ID, ex);
            }
            throw e;
        }

        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken("mock", now, now.plus(1, ChronoUnit.HOURS), claims);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(vo.getUserAuthrtCd()));
        DefaultOidcUser oidcUser = new DefaultOidcUser(
                authorities, idToken, new OidcUserInfo(claims), props.getUsernameClaim());

        // ① 세션 고정 공격 차단 — 콜백과 같은 순서다(로그인 이력은 이 이후의 세션 ID 를 해시한다)
        //    🔴 changeSessionId() 는 **세션이 이미 있어야** 동작한다 — 없으면
        //       `IllegalStateException: Cannot change session ID` 로 500 이다.
        //       콜백은 HttpSession 을 파라미터로 받아(= getSession(true)) 항상 세션이 있지만,
        //       이 경로는 그렇지 않다. 기존 세션이 있으면 ID 를 바꾸고, 없으면 새로 만든다
        //       (새 세션은 ID 도 새것이라 애초에 고정 공격 대상이 아니다).
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        // 🔴 로그인 방식 기록. AuthController#logout 은 이 값이 "oidc" 일 때만 IdP
        //    로그아웃 URL 을 만든다. 종전엔 props.getMode()=="mock" 플래그가 이 경로를 끊었는데,
        //    비밀번호 로그인이 생기면서 그 판정이 세션 attribute 로 일반화됐다 —
        //    여기서 기록하지 않으면 mock 세션이 실 IdP 경로로 샌다.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(AuthMethod.AUTH_METHOD, AuthMethod.AUTH_METHOD_MOCK);
        }

        var auth = new OAuth2AuthenticationToken(oidcUser, authorities, props.getRegistrationId());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        // ③ SecurityConfig 가 선언한 것과 같은 빈 인스턴스
        securityContextRepository.saveContext(ctx, request, response);

        // 🔴 로그인 성공 기록 — ① changeSessionId() **이후**의 세션 ID 를 해시한다.
        //    changeSessionId() 는 void 라 새 ID 는 request 에서 다시 읽는다.
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
}
