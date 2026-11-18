import { api } from './client'

/**
 * 인증 API 클라이언트 (TSK-4076 Step 7).
 *
 * 🔴 필드명은 백엔드 `LoginUserVO` 와 1:1 이다 — DB 컬럼명이 `map-underscore-to-camel-case` 로
 *    그대로 API JSON 키가 되므로, 이름을 바꾸려면 DB → VO → API → 여기까지 한 커밋에 가야 한다.
 * 🔴 `userAuthrtCd` 이지 `authrtCd` 가 아니다 (D12).
 */
export type LoginUser = {
  userId: string
  userNm: string
  userEmlAddr?: string
  userAuthrtCd: string
  authrtNm: string
  /** 부서명(TSK-4261). 부서 미투영·무소속이면 null — 사이드바가 `authrtNm` 으로 폴백한다 */
  deptNm?: string | null
  /** 허용 메뉴 경로. TSK-4115 Step 3·5 의 `useMenuAccess()`·`RequireMenu` 가 쓴다 */
  menuPathNms: string[]
}

export const fetchMe = () => api.get<LoginUser>('/auth/me').then((r) => r.data)

export const fetchAuthorizeUrl = () =>
  api.get<{ authorizeUrl: string }>('/auth/authorize-url').then((r) => r.data)

export const postCallback = (b: { code: string; state: string }) => api.post('/auth/callback', b)

export const logout = () =>
  api.post<{ idpLogoutUrl?: string }>('/auth/logout').then((r) => r.data)

/**
 * 비밀번호 로그인 성공 응답 (TSK-4134 Step 2). 🔴 **토큰이 없다** — BFF 유지(D1)라
 * 세션 쿠키만 서고 브라우저는 토큰을 보지 못한다. 이 값을 저장소에 넣지 않는다.
 */
export type PasswordLoginResult = {
  userId: string
  userNm: string
  userAuthrtCd: string
}

/**
 * `POST /api/auth/login` — 이메일·비밀번호 로그인 (TSK-4134).
 *
 * 🔴 `api` 인스턴스를 그대로 쓴다(새 axios 인스턴스 금지) — permitAll 이어도 POST 는
 *    `X-XSRF-TOKEN` 이 없으면 403 이라, 이 인스턴스의 xsrfCookieName/xsrfHeaderName 이 필요하다.
 * 🔴 실패는 **401 고정**이고 본문 문구는 하나뿐이다(D5). 429(시도 제한)도 **같은 문구**를 준다 —
 *    호출부는 상태코드로 문구를 갈라서는 안 된다.
 * 🔴 인자를 로깅하지 않는다(D6).
 */
export const loginWithPassword = (b: { email: string; password: string }) =>
  api.post<PasswordLoginResult>('/auth/login', b).then((r) => r.data)
