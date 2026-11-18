import { useMemo } from 'react'
import { useAuth } from './authContext'

/**
 * 메뉴 권한 판정.
 *
 * 🔴 판정 로직은 **이 파일 한 곳**에만 둔다 — 사이드바·라우트 가드·탭이 각자 판정하면
 *    한쪽만 고쳐져 "메뉴는 안 보이는데 URL 로는 들어가지는" 상태가 된다.
 *
 * 🔴 근거는 `GET /api/auth/me` 가 주는 `menuPathNms` **하나뿐**이다(메뉴 ID 는 쓰지 않는다).
 *    `LoginUser` 타입에 필드를 더하지 않는다.
 *
 * 🔴 2026-09-03 — 경로를 롤로 막는 상수를 두지 않는다.
 *    `/settings` 도 다른 경로와 똑같이 `menuPathNms` 만 본다. 관리자가 부여했으면 열린다.
 *    ⚠️ 관리자 전용 경로 목록 같은 롤 기반 차단 상수를 되살리지 않는다 —
 *       화면만 막고 API 는 표를 보면 두 판정이 갈린다(표 파생으로 바뀐 이유).
 */
export function useMenuAccess() {
  const user = useAuth()
  const paths = useMemo(() => new Set(user?.menuPathNms ?? []), [user])

  /**
   * 🔴 **정확 일치**다. 접두 매칭으로 하면 `/reports` 권한이 `/reports/management` 까지
   *    열어 준다 — 시드가 그 둘을 분리하고 있으므로(ROLE_USER 는 `/reports` 만) 접두 매칭은 권한을 뚫는다.
   */
  const canAccessPath = (path: string) => paths.has(path)

  return {
    /** 라우트·사이드바·탭 판정 — 전부 경로 기준. menuPathNms 가 유일한 근거다 */
    canAccessPath,
    /**
     * 🔴 경로 없는 묶음(`/settings`)용. 주어진 하위 경로 중 첫 허용 경로를 돌려준다.
     *    없으면 null → 호출부가 그 항목을 렌더하지 않는다.
     *    ⚠️ 접두 매칭이 아니다 — 호출부가 **하위 경로를 명시적으로 넘기고**, 각각을 정확 일치로 검사한다.
     */
    firstAllowed: (candidates: readonly string[]): string | null =>
      candidates.find(canAccessPath) ?? null,
    /** 🔴 랜딩 경로 — `/` 가 없는 롤이 있을 수 있다. 비어 있으면 null */
    landingPath: (): string | null =>
      canAccessPath('/') ? '/' : (user?.menuPathNms?.find(canAccessPath) ?? null),
    hasAnyMenu: (user?.menuPathNms ?? []).some(canAccessPath),
  }
}
