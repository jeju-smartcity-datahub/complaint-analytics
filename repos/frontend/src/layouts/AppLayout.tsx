import type { MouseEvent } from 'react'
import { Outlet } from 'react-router-dom'
import Sidebar from '../components/Sidebar'

/** 앱 셸 — 좌측 사이드바 고정, 우측에 라우팅된 화면을 그린다. */
export default function AppLayout() {
  // 🔴 해시 이동을 막는다 — 새 히스토리 항목이 생기면 `location.key` 로 진입을 판정하는 화면이 다시 돈다.
  const skipToMain = (e: MouseEvent<HTMLAnchorElement>) => {
    e.preventDefault()
    document.getElementById('main-content')?.focus()
  }

  return (
    <div className="app">
      <a className="skip-link" href="#main-content" onClick={skipToMain}>
        본문 바로가기
      </a>
      <Sidebar />
      <main id="main-content" className="main" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}
