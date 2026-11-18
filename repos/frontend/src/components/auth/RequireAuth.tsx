import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { fetchMe, type LoginUser } from '../../api/auth'
import { AuthProvider } from '../../lib/authContext'

/**
 * 인증 가드 (TSK-4076 D8) — 마운트 시 `GET /api/auth/me` 1회로 판정한다.
 *
 * 🔴 props 타입 명시 필수 (react 18.2 — 암시적 children 이 없다. `TopbarActions.tsx:50` 관례).
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<LoginUser | null>(null)
  const [ready, setReady] = useState(false)
  const fetched = useRef(false) // 🔴 StrictMode 이중 실행 방지

  useEffect(() => {
    // 🔴 가드는 최상단이다 — main.tsx 가 StrictMode 라 없으면 /me 가 2회 나간다
    if (fetched.current) return
    fetched.current = true
    fetchMe()
      .then(setUser)
      .catch(() => setUser(null))
      .finally(() => setReady(true))
  }, [])

  // 🔴 bfcache 복원 재판정 (TSK-4301) — 로그아웃 뒤 뒤로가기를 누르면 브라우저가 이전 페이지를
  //    JS 상태째 복원해 위 마운트 판정이 다시 돌지 않는다(이전 화면이 그대로 보인다).
  //    `persisted` 일 때만 /me 를 다시 묻고, 세션이 없으면 히스토리를 남기지 않고 /login 으로 간다.
  useEffect(() => {
    const onPageShow = (e: PageTransitionEvent) => {
      if (!e.persisted) return
      fetchMe()
        .then(setUser)
        .catch(() => window.location.replace('/login'))
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [])

  // 🔴 계약: 판정 전에는 아무것도 렌더하지 않는다. ready 를 건너뛰면 첫 프레임에서
  //    user=null 로 보여 정상 로그인이 /login 으로 튕긴다.
  if (!ready) return null
  if (!user) return <Navigate to="/login" replace />
  return <AuthProvider value={user}>{children}</AuthProvider>
}
