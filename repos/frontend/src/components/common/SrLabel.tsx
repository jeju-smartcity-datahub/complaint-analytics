import type { ReactNode } from 'react'

/**
 * 화면에 안 보이는 레이블로 입력 칸을 감싼다(웹접근성 항목 19).
 *
 * 🔴 OpenWAX 는 `aria-label` 을 레이블로 보지 않고 `title` 은 "확인 필요" 로 친다 — `<label>` 만 정상이다.
 * `display: contents` 라 감싸도 칸은 원래 부모의 flex·grid 자식으로 남는다. `aria-label` 은 그대로 둔다.
 */
export default function SrLabel({ text, children }: { text?: string; children: ReactNode }) {
  return (
    <label className="sr-label">
      <span className="sr-only">{text}</span>
      {children}
    </label>
  )
}
