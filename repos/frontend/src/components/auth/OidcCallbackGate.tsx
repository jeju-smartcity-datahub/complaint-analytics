import { useEffect, useRef, useState, type ReactNode } from 'react'
import { postCallback } from '../../api/auth'

/**
 * 루트 콜백 게이트.
 *
 * 등록된 redirect_uri 가 경로 없는 `http://localhost:5173` 이라 콜백이 **SPA 루트**로 떨어진다.
 * 라우팅보다 **먼저** `?code=` 를 가로채야 하므로 App 최상단(라우터 바깥)에 둔다.
 *
 * 🔴 props 타입 명시 필수 (react 18.2 — 암시적 children 없음). 여기선 children 이 필수라 `?` 를 붙이지 않는다.
 */
export default function OidcCallbackGate({ children }: { children: ReactNode }) {
  const handled = useRef(false) // 🔴 StrictMode 이중 실행 방지
  const [done, setDone] = useState(false) // 🔴 콜백 판정이 끝나기 전에는 children 을 렌더하지 않는다

  useEffect(() => {
    // 🔴 가드는 반드시 **파싱보다 먼저** 온다. 아래에 두면 StrictMode 2회차가, 1회차가 이미
    //    replaceState 로 지워 버린 URL 을 다시 읽어 code=null → setDone(true) 로 조기 확정한다.
    //    그러면 postCallback 이 아직 in-flight 인데 children(RequireAuth)이 마운트돼
    //    GET /api/auth/me 가 401 → /login 으로 튕긴다 — 아래 "계약" 주석이 경고한 그 실패 모드다.
    //    (state 는 같은 인스턴스에 보존되므로 2회차가 early return 해도 1회차의 done 이 살아 있다)
    if (handled.current) return
    handled.current = true

    const q = new URLSearchParams(window.location.search)
    const code = q.get('code')
    const state = q.get('state')
    const error = q.get('error')

    if (error) {
      window.history.replaceState({}, '', '/login?error')
      setDone(true)
      return
    }
    if (!code || !state) {
      setDone(true)
      return
    }

    // 🔴 await 하기 "전에" URL 을 먼저 지운다 — 새로고침으로 소모된 code 가 재전송되면 400 이다
    window.history.replaceState({}, '', '/')

    postCallback({ code, state })
      .then(() => setDone(true))
      .catch(() => window.location.assign('/login?error'))
  }, [])

  // 🔴 계약: done 이 true 일 때만 children 을 렌더한다. 빠뜨리면 RequireAuth 가 콜백 교환보다 먼저
  //    GET /api/auth/me 를 불러 401 → /login 으로 튕기고, 정상 로그인이 실패로 보인다.
  if (!done) return null
  return <>{children}</>
}
