import type { MouseEvent } from 'react'
import { Outlet } from 'react-router-dom'

/**
 * 운영 화면 셸 — 좌측 사이드바 없이 본문만 그린다 (배치 운영 `/ops/batch`).
 *
 * 운영 화면은 사이드바(`navConfig`)에 없는 **숨김 메뉴**라, 사이드바를 그려도 현재 위치가 강조되지 않고
 * 메뉴 목록만 자리를 차지한다. 상단 바(경로 표시·`TopbarActions`)는 각 화면이 직접 그리므로 그대로 남는다.
 * 🔴 인증·메뉴 인가는 라우트 쪽(`RequireAuth` · `RequireMenu`)이 그대로 진다 — 셸만 다르다.
 */
export default function OpsLayout() {
  // AppLayout 과 같다 — 해시 이동을 막아 `location.key` 로 진입을 판정하는 화면이 다시 돌지 않게 한다.
  const skipToMain = (e: MouseEvent<HTMLAnchorElement>) => {
    e.preventDefault()
    document.getElementById('main-content')?.focus()
  }

  return (
    <div className="app app--no-sidebar">
      <a className="skip-link" href="#main-content" onClick={skipToMain}>
        본문 바로가기
      </a>
      <main id="main-content" className="main" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}
