import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import NotificationBell from './NotificationBell'

/*
 * topbar 우측 액션 묶음 — `AI 대화 시작하기` 버튼 + 알림 벨.
 *
 * 시안의 모든 화면 상단에 같은 마크업이 있다.
 * 보고서 2화면이 함께 쓰므로 화면마다 복사하지 않고 여기로 모았다. 알림 화면도 이걸 쓴다.
 * 대시보드(`pages/DashboardPage.tsx`)도 자체 인라인 마크업을 버리고 이 컴포넌트를 쓴다 —
 * 대시보드에만 있는 `내보내기` 버튼은 `children` 으로 받는다.
 *
 * 🔴 `children` 은 **벨 앞**에 온다 — 시안 순서가 `AI 대화 시작하기 → 내보내기 → 🔔` 이다.
 * `children` 을 주지 않는 화면은 렌더 결과가 종전과 완전히 같다.
 *
 * 벨은 `NotificationBell` 이 그린다 — 배지 숫자는 실 API(`GET /api/risk-alerts/daily` 의 `unreadCnt`,
 * 전일 미읽음 건수)에 연동돼 있다.
 */

/** [AI 대화 시작하기] 버튼의 마스코트. 시안 번들(index-DNNYoE2G.js)의 SVG 원문 그대로다. */
const AiMascot = (
  <svg
    viewBox="0 0 36 36"
    fill="none"
    width="24"
    height="24"
    aria-hidden="true"
    style={{ flexShrink: 0 }}
  >
    <path d="M18.6 8.4c0-2.6 2.1-4.6 4.7-4.6 0 2.6-2.1 4.6-4.7 4.6Z" fill="#3DA35D" />
    <circle cx="18" cy="21" r="12.5" fill="#F79009" />
    <circle cx="13.6" cy="19.6" r="1.4" fill="#4A3415" opacity="0.85" />
    <circle cx="22.4" cy="19.6" r="1.4" fill="#4A3415" opacity="0.85" />
    <path
      d="M14.9 24.2a3.9 3.9 0 0 0 6.2 0"
      stroke="#4A3415"
      strokeWidth="1.3"
      strokeLinecap="round"
      fill="none"
      opacity="0.85"
    />
  </svg>
)

export default function TopbarActions({ children }: { children?: ReactNode }) {
  const navigate = useNavigate()

  return (
    <div className="topbar__actions">
      <button
        className="btn btn--ai"
        type="button"
        onClick={() => navigate('/ai-assistant', { state: { focus: true } })}
      >
        {AiMascot} AI 대화 시작하기
      </button>
      {children}
      <NotificationBell />
    </div>
  )
}
