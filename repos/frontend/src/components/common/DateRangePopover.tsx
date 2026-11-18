import type { CSSProperties } from 'react'

import { MAX_RANGE_DAYS, RANGE_ERROR_TEXT, toYmd, validateRange } from '../../lib/dateRange'

/**
 * 기간 선택 팝오버(`.date-pop` 안쪽).
 *
 * ⚠️ **소유 경계** — 이 컴포넌트는 `.date-pop` **안쪽만** 그린다.
 * 바깥 `.date-pick` wrapper 와 `datePickRef`, 토글 버튼, `dateOpen` 상태,
 * 바깥클릭·Escape `useEffect` 는 **페이지에 그대로 남는다**.
 * ref 를 여기로 옮기면 팝오버가 닫혀 있을 때 `datePickRef.current` 가 null 이라
 * 바깥클릭 닫기가 조용히 죽는다 — props 에 `open`·토글이 없는 이유가 이것이다.
 *
 * CSS 는 새로 만들지 않는다 — 기존 `.date-pop*`(styles/dashboard.css)을 그대로 쓴다.
 */
interface DateRangePopoverProps {
  from: string
  to: string
  onFromChange: (v: string) => void
  onToChange: (v: string) => void
  /** 검증 통과 시에만 호출된다. */
  onApply: (range: { from: string; to: string }) => void
  onCancel: () => void
}

/**
 * 에러 문구용 전용 클래스가 없어 인라인으로 최소한만 얹는다
 * (`HotspotSettingPanel` 의 `NOTE_ERROR` 와 같은 방식).
 */
const NOTE_ERROR: CSSProperties = {
  margin: 'var(--space-12) 0 0',
  color: 'var(--status-negative)',
  fontSize: 'var(--label2-size)',
}

/** `.btn--apply` 에는 `:disabled` 규칙이 없어 최소한의 시각 피드백만 얹는다. */
const APPLY_DISABLED: CSSProperties = { height: 38, opacity: 0.6, cursor: 'not-allowed' }

/** `시작일 + (MAX_RANGE_DAYS - 1)일` = 선택 가능한 마지막 종료일. */
function maxTo(from: string): string | undefined {
  if (!from) return undefined
  const [y, m, d] = from.split('-').map(Number)
  const last = new Date(y, m - 1, d)
  last.setDate(last.getDate() + (MAX_RANGE_DAYS - 1))
  return toYmd(last)
}

export default function DateRangePopover({
  from,
  to,
  onFromChange,
  onToChange,
  onApply,
  onCancel,
}: DateRangePopoverProps) {
  // 🔴 미래 날짜는 막지 않는다 — `max = 오늘` 을 걸면 **민원 최종 발생일**(`MAX(ocrn_dt)`)이
  //    오늘 이후인 데이터를 조회할 수 없게 된다. 제약은 "최대 1년 · 시작일 ≤ 종료일" 둘뿐이다.
  //    (화면 상단 표기는 `최종 업데이트` 에서 `최신 갱신`(배치 실행일)으로 바뀌었지만,
  //     이 근거는 그 표기가 아니라 **민원 발생일**이므로 여전히 유효하다.)
  const error = validateRange(from, to)

  return (
    <div className="date-pop">
      <div className="date-pop__row">
        <label className="date-pop__field">
          <span>시작일</span>
          <input
            type="date"
            value={from}
            max={to || undefined}
            onChange={(e) => onFromChange(e.target.value)}
          />
        </label>
        <label className="date-pop__field">
          <span>종료일</span>
          <input
            type="date"
            value={to}
            min={from || undefined}
            max={maxTo(from)}
            onChange={(e) => onToChange(e.target.value)}
          />
        </label>
      </div>

      {error && (
        <p role="alert" style={NOTE_ERROR}>
          {RANGE_ERROR_TEXT[error]}
        </p>
      )}

      <div className="date-pop__foot">
        <button className="btn" type="button" style={{ height: 38 }} onClick={onCancel}>
          취소
        </button>
        <button
          className="btn btn--apply"
          type="button"
          style={error ? APPLY_DISABLED : { height: 38 }}
          disabled={error !== null}
          onClick={() => onApply({ from, to })}
        >
          적용
        </button>
      </div>
    </div>
  )
}
