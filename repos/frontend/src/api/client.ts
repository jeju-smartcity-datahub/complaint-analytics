import axios from 'axios'

/**
 * 백엔드(eGovFrame) API 클라이언트.
 * 개발 시 vite.config.ts 의 프록시가 /api 요청을 http://localhost:8180 으로 전달한다.
 *
 * 인증은 BFF 방식이다 — 토큰을 브라우저에 두지 않고 HttpOnly 세션 쿠키만 받는다.
 */
export const api = axios.create({
  baseURL: '/api',
  timeout: 10_000,
  withCredentials: true,          // 세션 쿠키를 싣는다
  xsrfCookieName: 'XSRF-TOKEN',   // 백엔드가 CookieCsrfTokenRepository 로 내려 준다
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

// 세션 만료 시 로그인 화면으로. /login 은 제외(무한 루프 방지)
//
// 🔴 `/auth/me` 도 제외한다 — 그 401 은 "세션 만료" 신호가 아니라 RequireAuth 의 **판정 입력**이다.
//    제외하지 않으면 미로그인 첫 진입에서 같은 401 로 인터셉터의 assign('/login') 과
//    RequireAuth 의 <Navigate to="/login" replace /> 가 동시에 발동한다. 무한 루프는 아니지만
//    (인터셉터가 항상 먼저 이겨) SPA 전환이어야 할 자리가 늘 풀 페이지 리로드가 되고,
//    가드가 두 벌이 되어 어느 쪽이 로그인 화면으로 보냈는지 추적할 수 없다.
//    **미인증 판정은 RequireAuth 한 곳, 세션 만료 처리는 인터셉터 한 곳** 으로 나눈다.
//
// 🔴 `/auth/callback` 도 같은 이유로 제외한다 — 백엔드 콜백은 userinfo 빈 응답·sub 불일치·
//    토큰 교환 실패에서 **401** 을 낸다. 제외하지 않으면 인터셉터의 assign('/login') 과
//    게이트의 assign('/login?error') 가 같은 틱에 겹쳐 실패 안내(?error)가 소실될 수 있다.
//    **로그인 흐름의 401 은 그 흐름의 주인이 처리한다.**
api.interceptors.response.use(undefined, (err) => {
  const url = err.config?.url ?? ''
  // RequireAuth(/auth/me) · 콜백 게이트(/auth/callback) 가 스스로 처리하는 401
  //
  // 🔴 `/ai/**` 도 제외한다 — Agent 프록시의 401(`AGENT_UNAUTHORIZED`)은
  //    "우리 세션이 죽었다" 가 아니라 **업스트림이 우리 토큰을 거절했다** 는 뜻이다.
  //    지금 실제 원인은 `aud` 에 `dhub2-agent` 가 없는 것이라 **재로그인해도 낫지 않는다** —
  //    제외하지 않으면 메뉴를 누를 때마다 /login 으로 튕겨 무한 반복이 된다.
  //    화면은 이미 그 코드의 안내 문구와 `다시 로그인` 버튼을 갖고 있다(ConversationView).
  const selfHandled =
    url.endsWith('/auth/me') || url.endsWith('/auth/callback') || url.startsWith('/ai/')
  if (err.response?.status === 401 && !selfHandled && window.location.pathname !== '/login') {
    window.location.assign('/login')
  }
  return Promise.reject(err)
})
