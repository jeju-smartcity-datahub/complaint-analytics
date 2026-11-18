/**
 * 조회 기간(from~to) 공통 유틸.
 *
 * 기간 규칙(최대 길이·역전 금지)을 화면마다 따로 두지 않고 이 파일 하나에만 둔다 —
 * 규칙이 갈리면 어떤 화면은 통과하고 어떤 화면은 백엔드에서 400 을 받는 상태가 된다.
 */

/** 조회 가능한 최대 구간(일, 양끝 포함). 백엔드 `DateRanges.MAX_RANGE_DAYS` 와 같은 값이어야 한다. */
export const MAX_RANGE_DAYS = 366

/**
 * `Date` → `yyyy-MM-dd`.
 * ⚠️ `toISOString()` 은 UTC 로 바꾸므로 한국 시간 오전 9시 이전에는 **날짜가 하루 밀린다** — 직접 조립한다.
 */
export function toYmd(date: Date): string {
  const mm = `${date.getMonth() + 1}`.padStart(2, '0')
  const dd = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${mm}-${dd}`
}

/** `yyyy-MM-dd` 가 속한 달의 1일. */
export function firstDayOfMonth(ymd: string): string {
  return `${ymd.slice(0, 7)}-01`
}

/** `yyyy-MM-dd` 가 속한 달의 말일. `new Date(y, m, 0)` 이 m월의 마지막 날이다. */
export function lastDayOfMonth(ymd: string): string {
  const [y, m] = ymd.split('-').map(Number)
  return toYmd(new Date(y, m, 0))
}

/** 오늘 기준 상대 구간. `daysBack=29` → [오늘-29, 오늘]. */
export function recentDays(daysBack: number): { from: string; to: string } {
  const today = new Date()
  const start = new Date(today)
  start.setDate(start.getDate() - daysBack)
  return { from: toYmd(start), to: toYmd(today) }
}

/**
 * 어제까지 N일. `days=30` → [오늘-30, 오늘-1].
 * 민원은 매일 02:00 에 전날분을 수집해 오늘 칸이 거의 비므로, 민원현황·대시보드의 기본 구간은 어제까지다
 * (백엔드 `ComplaintUnit`·`DashboardUnit` 의 월간과 같은 구간 — TSK-4311).
 */
export function daysUntilYesterday(days: number): { from: string; to: string } {
  const end = new Date()
  end.setDate(end.getDate() - 1)
  const start = new Date(end)
  start.setDate(start.getDate() - (days - 1))
  return { from: toYmd(start), to: toYmd(end) }
}

/**
 * 구간 길이(일, **당일 포함**).
 * 문자열을 UTC 자정으로 파싱해 뺀다 — 로컬 `Date` 로 계산하면 서머타임이 있는 지역에서 하루가 어긋난다.
 */
export function rangeDays(from: string, to: string): number {
  const utc = (ymd: string) => {
    const [y, m, d] = ymd.split('-').map(Number)
    return Date.UTC(y, m - 1, d)
  }
  return (utc(to) - utc(from)) / 86_400_000 + 1
}

/**
 * 결과 패널의 `분석 기간` 값. 세그먼트 키가 숫자면 `최근 N개월`, 직접설정(`custom`)이면 `from ~ to` 다.
 * 🔴 실행 시점에 만들어 **결과와 함께** 보관한다 — 지금 입력으로 그리면 실행 뒤 세그먼트만 바꿔도 결과 설명이 바뀐다.
 */
export function formatPeriodLabel(period: string, range: { from: string; to: string }): string {
  return period === 'custom' ? `${range.from} ~ ${range.to}` : `최근 ${period}개월`
}

/** `20260713` → `2026-07-13`. 서버가 정한 구간을 그대로 적는 화면용이다. */
export function dashedYmd(ymd: string): string {
  return ymd.replace(/^(\d{4})(\d{2})(\d{2})$/, '$1-$2-$3')
}

export type RangeError = 'EMPTY' | 'REVERSED' | 'TOO_LONG'

/** 검증 결과. 통과면 null. */
export function validateRange(from: string, to: string): RangeError | null {
  if (!from || !to) return 'EMPTY'
  if (from > to) return 'REVERSED'
  if (rangeDays(from, to) > MAX_RANGE_DAYS) return 'TOO_LONG'
  return null
}

/** 화면에 그대로 쓰는 안내 문구. */
export const RANGE_ERROR_TEXT: Record<RangeError, string> = {
  EMPTY: '시작일과 종료일을 모두 선택해 주세요.',
  REVERSED: '종료일은 시작일보다 빠를 수 없습니다.',
  TOO_LONG: `조회 기간은 최대 1년(${MAX_RANGE_DAYS}일)까지입니다.`,
}
