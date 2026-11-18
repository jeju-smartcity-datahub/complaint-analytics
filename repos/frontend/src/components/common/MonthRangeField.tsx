import { useEffect, useRef, useState } from 'react'
import Icon from '../Icon'
import { firstDayOfMonth, lastDayOfMonth } from '../../lib/dateRange'

/*
 * 시작월~종료월 입력. 시안 `_7({type:'month'})` 의 칸 2개 + 연도 이동·12개월 팝오버다.
 *
 * 🔴 값은 `yyyy-MM-dd` 그대로다 — 호출부의 기간 검증과 API 계약이 바뀌지 않는다.
 * 시작월은 1일, 종료월은 말일로 넣는다.
 *
 * 🔴 일 단위 값을 월로 뭉개 표시하지 않는다 — 대시보드 딥링크는 하루짜리 구간도 준다.
 * 월 경계 값(시작 1일 · 종료 말일)만 `yyyy-MM` 으로, 나머지는 `yyyy-MM-dd` 로 그린다.
 */

/** 팝오버에서 갈 수 있는 가장 이른 연도. 시안과 같다. */
const MIN_YEAR = 2025

type Target = 'from' | 'to'

interface MonthRangeFieldProps {
  /** 시작일 (`yyyy-MM-dd`) */
  from: string
  /** 종료일 (`yyyy-MM-dd`) */
  to: string
  onFromChange: (next: string) => void
  onToChange: (next: string) => void
}

/** 팝오버에서 고른 연·월 → `yyyy-MM-dd` 변환의 입력. */
function ymOf(year: number, month: number): string {
  return `${year}-${String(month).padStart(2, '0')}-01`
}

function formatFrom(ymd: string): string {
  return ymd.endsWith('-01') ? ymd.slice(0, 7) : ymd
}

function formatTo(ymd: string): string {
  if (!ymd) return ymd
  return lastDayOfMonth(ymd) === ymd ? ymd.slice(0, 7) : ymd
}

export default function MonthRangeField({ from, to, onFromChange, onToChange }: MonthRangeFieldProps) {
  const [open, setOpen] = useState<Target | null>(null)
  const [year, setYear] = useState(() => new Date().getFullYear())
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDocClick = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(null)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(null)
    }
    document.addEventListener('click', onDocClick, true)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('click', onDocClick, true)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  const value = open === 'from' ? from : to
  const valueYear = Number(value.slice(0, 4))
  const valueMonth = Number(value.slice(5, 7))

  const toggle = (target: Target) => {
    const current = target === 'from' ? from : to
    setYear(Number(current.slice(0, 4)) || new Date().getFullYear())
    setOpen((prev) => (prev === target ? null : target))
  }

  const pick = (month: number) => {
    const ym = ymOf(year, month)
    if (open === 'from') onFromChange(firstDayOfMonth(ym))
    else onToChange(lastDayOfMonth(ym))
    setOpen(null)
  }

  return (
    <div className="field__dates" ref={rootRef} style={{ position: 'relative' }}>
      <button
        type="button"
        className="field__date"
        aria-haspopup="dialog"
        aria-expanded={open === 'from'}
        onClick={() => toggle('from')}
      >
        <span className="sr-only">분석 시작월 </span>
        <span>{formatFrom(from) || '시작월'}</span>
        <Icon name="calendar" size={16} />
      </button>
      <span className="field__tilde">~</span>
      <button
        type="button"
        className="field__date"
        aria-haspopup="dialog"
        aria-expanded={open === 'to'}
        onClick={() => toggle('to')}
      >
        <span className="sr-only">분석 종료월 </span>
        <span>{formatTo(to) || '종료월'}</span>
        <Icon name="calendar" size={16} />
      </button>

      {open && (
        <div
          className="month-pop"
          role="dialog"
          aria-label={open === 'from' ? '시작월 선택' : '종료월 선택'}
        >
          <div className="month-pop__head">
            <button
              type="button"
              className="month-pop__nav"
              disabled={year <= MIN_YEAR}
              onClick={() => setYear((y) => Math.max(MIN_YEAR, y - 1))}
            >
              <span className="sr-only">이전 연도</span>
              <Icon name="chevron-left" size={16} />
            </button>
            <span className="month-pop__year">{year}년</span>
            <button type="button" className="month-pop__nav" onClick={() => setYear((y) => y + 1)}>
              <span className="sr-only">다음 연도</span>
              <Icon name="chevron-right" size={16} />
            </button>
          </div>
          <div className="month-pop__grid">
            {Array.from({ length: 12 }, (_, i) => i + 1).map((month) => {
              const active = year === valueYear && month === valueMonth
              return (
                <button
                  key={month}
                  type="button"
                  aria-pressed={active}
                  className={`month-pop__cell${active ? ' is-active' : ''}`}
                  onClick={() => pick(month)}
                >
                  {month}월
                </button>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
