package jeju.datahub.auth.service;

import java.util.Map;

/**
 * OIDC claim → {@code parking.TB_USER} 동기화 (TSK-4076 Step 4).
 *
 * <p>🔴 <b>여기가 두 콜백 방식의 공통 지점이다.</b> 입력은 {@code Map<String,Object> claims} 하나로 받는다 —
 * 옵션 B 의 수동 콜백({@code AuthController})도, mock 경로({@code MockAuthController})도,
 * 나중에 옵션 A 로 갈아탄 {@code OidcUserService} 도 <b>이것을 호출</b>한다.
 * {@code OidcUserRequest} 같은 Spring 타입에 의존하지 않게 시그니처를 좁게 잡는다(D5).
 *
 * <p>🔴 넘기는 맵은 <b>id_token claims 와 {@code /userinfo} 를 병합한 것</b>이며 id_token 이 우선이다.
 * {@code /userinfo} 단독을 넘기지 않는다 — OIDC 가 {@code /userinfo} 에 보장하는 claim 은 {@code sub}
 * 뿐이라 {@code preferred_username} 이 비면 {@code TB_USER.user_id} NOT NULL 위반이다.
 */
public interface AuthUserSyncService {

    /**
     * claim 맵으로 사용자 행을 upsert 하고 확정된 값을 돌려준다.
     *
     * @param claims id_token + userinfo 병합 맵 (mock 경로는 고정 claim 맵)
     * @return 확정된 사용자 정보
     * @throws org.springframework.security.oauth2.core.OAuth2AuthenticationException
     *         {@code use_yn='N'} 으로 정지된 사용자일 때
     */
    AuthUserVO sync(Map<String, Object> claims);
}
