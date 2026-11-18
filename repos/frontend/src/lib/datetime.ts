/**
 * 일시 표시 포맷 공통 유틸.
 *
 * 🔴 **손으로 +9 시간을 더하지 않는다.** `new Date(iso)` 가 문자열의 오프셋을 해석해 절대시각을
 *    만들고, `getFullYear()`·`getHours()` 류 **로컬 getter 가 KST 로 돌려준다** — 더하면 9시간이
 *    두 번 밀린다. `toISOString()` 은 UTC 로 되돌리므로 쓰지 않는다
 *    (같은 경고가 `dateRange.ts` 의 `toYmd` 에도 있다).
 *
 * 선례: `components/dashboard/ReportPanel.tsx` 의 `formatTime`(로컬 getter + `padStart`).
 */

/** 2자리 0채움. `getMonth()` 류가 돌려주는 수를 표시용 문자열로 만든다. */
function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

/**
 * ISO-8601 문자열 → `2026.06.01 09:12`(시안 `.recent__time` 표기).
 *
 * 값이 없거나(`null`·`undefined`) 파싱되지 않으면 **빈 문자열**을 돌려준다 —
 * 호출부가 `Invalid Date` 를 화면에 그리지 않게 여기서 막는다.
 */
export function formatDotDateTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}.${pad2(d.getMonth() + 1)}.${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}
