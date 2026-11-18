import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useMenuAccess } from '../../lib/useMenuAccess'

/**
 * 라우트 가드 — 라우트 하나를 **경로 권한**으로 막는다.
 *
 * 메뉴를 숨기는 것만으로는 부족하다 — 주소창에 직접 치면 화면이 그대로 뜬다.
 *
 * 🔴 `path` 는 반드시 **절대경로(선행 `/`)** 다. `App.tsx` 의 자식 라우트는 상대경로
 *    (`path="complaints"`)로 적혀 있는데, 판정 근거인 `menuPathNms` 는 `/complaints` 처럼
 *    선행 슬래시가 붙은 값이고 `canAccessPath` 는 **정확 일치**다.
 *    라우트 `path` 를 그대로 넘기면 전부 false 가 되어 모든 사용자가 랜딩으로 튕긴다.
 *
 * 🔴 판정은 `useMenuAccess()` 한 곳에서만 한다 — 여기서 다시 규칙을 적지 않는다.
 *
 * 🔴 props 타입 명시 필수 (react 18.2 — 암시적 children 이 없다. `RequireAuth.tsx` 관례).
 */
export default function RequireMenu({ path, children }: { path: string; children: ReactNode }) {
  const { canAccessPath, landingPath, hasAnyMenu } = useMenuAccess()
  if (canAccessPath(path)) return <>{children}</>
  if (!hasAnyMenu) return <Navigate to="/no-access" replace />
  const to = landingPath()
  return <Navigate to={to ?? '/no-access'} replace />
}

/**
 * catch-all 전용 랜딩 폴백. **같은 판정을 쓰므로 `RequireMenu` 와 파일을 나누지 않는다.**
 *
 * 🔴 `<Navigate to="/" />` 를 쓰지 않는다 — `/` 가 없는 롤이면 무한 리다이렉트다.
 */
export function LandingRedirect() {
  const { landingPath, hasAnyMenu } = useMenuAccess()
  if (!hasAnyMenu) return <Navigate to="/no-access" replace />
  return <Navigate to={landingPath() ?? '/no-access'} replace />
}
