/*
 * i 아이콘 정보 팝업(InfoTip). 산정 기준·데이터 기준일 같은 보충 설명을 아이콘 뒤에 숨긴다.
 * 스타일은 `styles/info-tip.css` 의 `.info-tip*`.
 *
 * `placement` 3종 — `center`(기본, 아이콘 아래 가운데) · `right`(아이콘 오른쪽 끝에 맞춤 —
 * 결과 패널 제목 줄 오른쪽 끝) · `block`(가장 가까운 positioned 조상 폭에 맞춤 — 좁은 사이드 컬럼 카드).
 *
 * 열림은 CSS 전용(`:hover`/`:focus-within`)이라 JS 상태가 없다. 포커스 대상은 아이콘 `button` 하나라
 * 키보드 포커스에도 열리고, Esc 를 누르면 버튼 포커스를 놓아 닫는다(TSK-4319 D1).
 */
import { useId, type CSSProperties, type KeyboardEvent, type ReactNode } from 'react'

/** 원 + 점 + 막대(i). 색은 `currentColor`. */
export function InfoIcon({ size = 16 }: { size?: 14 | 16 }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" />
      <circle cx="12" cy="8" r="1.3" fill="currentColor" />
      <path d="M12 11.5v5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

export interface InfoTipProps {
  /** 스크린리더 이름 — `{무엇} 안내`. */
  label: string
  /** 팝업 제목. */
  title: ReactNode
  /** 목록 항목. `false`/`null` 은 건너뛴다(조건부 줄을 `cond && '문장'` 으로 넘길 수 있게). */
  items?: (ReactNode | false | null | undefined)[]
  /** 목록 대신(또는 뒤에) 넣을 자유 본문 — 데이터 기준일 표. */
  children?: ReactNode
  /** D4 — center(기본) · right · block */
  placement?: 'center' | 'right' | 'block'
  size?: 14 | 16
  style?: CSSProperties
}

export default function InfoTip({ label, title, items, children, placement = 'center', size = 16, style }: InfoTipProps) {
  const rows = (items ?? []).filter((x) => x !== false && x != null)
  const popId = useId()
  // D3 — Esc 로 닫는다. 상태 없이 포커스만 놓는다.
  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === 'Escape') e.currentTarget.blur()
  }
  // TSK-4319 D1 — 루트·팝업은 div, 포커스 대상은 아이콘 button 하나. 팝업은 button 의 형제(button 안엔 ul·table 불가).
  return (
    <div className={`info-tip info-tip--${placement}`} style={style}>
      <button type="button" className="info-tip__btn" aria-label={label} aria-describedby={popId} onKeyDown={onKeyDown}>
        <InfoIcon size={size} />
      </button>
      <div className="info-tip__pop" role="tooltip" id={popId}>
        <div className="info-tip__title">{title}</div>
        {rows.length > 0 && (
          <ul className="info-tip__list">
            {rows.map((row, i) => <li key={i}>{row}</li>)}
          </ul>
        )}
        {children}
      </div>
    </div>
  )
}
