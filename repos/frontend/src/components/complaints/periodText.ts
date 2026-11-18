/*
 * 민원현황 조회 조건 → 화면 문구 (시안 `Z5`·`$5`·`xwe` 대응).
 *
 * | 조회 조건        | 기간 문구(범례)            | 비교 문구(증감 툴팁) |
 * | 어제             | 어제                       | 전일 대비            |
 * | 주간(어제까지 7일) | 최근 1주일                 | 전주 대비            |
 * | 월간(어제까지 30일)| 최근 1개월                 | 전월 대비            |
 * | 기간 선택        | yyyy.mm.dd ~ yyyy.mm.dd    | 전기 대비            |
 *
 * 비교 구간은 백엔드 `ComplaintUnit.prevStart` — **바로 앞의 같은 일수 구간**이다. 그래서 주간의 "전주" 는
 * 달력 주가 아니라 직전 7일, 월간의 "전월" 은 직전 30일이다. 사용자 지정 기간도 같은 규칙이라 "전기" 로 쓴다.
 */

export interface PeriodRange {
  from: string
  to: string
}

const UNIT_TEXT: Record<string, { period: string; compare: string }> = {
  어제: { period: '어제', compare: '전일 대비' },
  주간: { period: '최근 1주일', compare: '전주 대비' },
  월간: { period: '최근 1개월', compare: '전월 대비' },
}

const dot = (ymd: string) => ymd.replace(/-/g, '.')

/** 범례 등 "어느 기간의 값인가" 문구. 사용자 지정 기간이 우선한다. */
export function periodLabel(unit: string, range: PeriodRange | null): string {
  if (range) return `${dot(range.from)} ~ ${dot(range.to)}`
  return UNIT_TEXT[unit]?.period ?? unit
}

/** 증감 툴팁 머리말. 사용자 지정 기간이면 `전기 대비`. */
export function compareLabel(unit: string, range: PeriodRange | null): string {
  if (range) return '전기 대비'
  return UNIT_TEXT[unit]?.compare ?? '전기 대비'
}
