import InfoTip from '../common/InfoTip'
import type { InvestPrrtFreshnessRow } from './investPrrtView'

/*
 * 투자우선순위 보드 헤더 `최근 갱신 ⓘ` — 지표별 최근 갱신일 (시안 `mZ` 구조).
 * 시안 `.page-sub.freshness` 구조 그대로 — 클래스·InfoTip 사용법은 `dashboard/DataFreshness` 와 같다
 * (CSS 는 `dashboard.css` 1359행~, `index.css` 가 전역 import).
 *
 * 🔴 **`dashboard/DataFreshness` 를 고치거나 재사용하지 않는다** — 그쪽은 스스로 `/api/dashboard/data-freshness`
 *    (수집 **배치 실행일**)를 부르고 열이 3개다. 여기는 지표 원천의 **자료 날짜**(응답 `*LastYmd`·`utztnLastYm`)다.
 * 🔴 표시 전용이다 — 조회하지 않고 받은 `rows` 만 그린다. 행 조립(순서·이름 폴백)은 `investPrrtView.buildFreshnessRows`.
 * 조회 전·실패면 `rows = null` → `최근 갱신 —` + 빈 표(DataFreshness 의 실패 동작과 같다 — 헤더라 화면을 죽이지 않는다).
 * ⚠️ 팝업은 `.main{overflow:hidden}` 안에서 열린다 — 위치·크기를 DataFreshness 와 같게 둔다(넓히면 잘린다).
 */

/** `YYYYMMDD` → `YYYY.MM.DD` · `YYYYMM` → `YYYY.MM`(월 단위 원천). 값이 없거나 형식이 어긋나면 `—`. */
function fmt(dt: string | null): string {
  if (!dt) return '—'
  if (/^\d{8}$/.test(dt)) return `${dt.slice(0, 4)}.${dt.slice(4, 6)}.${dt.slice(6, 8)}`
  if (/^\d{6}$/.test(dt)) return `${dt.slice(0, 4)}.${dt.slice(4, 6)}`
  return '—'
}

/** 비교용 `YYYYMMDD` — `YYYYMM` 은 그 달 말일로 본다(표시는 원값 형식 그대로). 형식이 어긋나면 null. */
function cmpKey(dt: string | null): string | null {
  if (!dt) return null
  if (/^\d{8}$/.test(dt)) return dt
  if (/^\d{6}$/.test(dt)) {
    const lastDay = new Date(Number(dt.slice(0, 4)), Number(dt.slice(4, 6)), 0).getDate()
    return `${dt}${String(lastDay).padStart(2, '0')}`
  }
  return null
}

/** 4행 중 가장 늦은 값(원값). 전부 없으면 null. */
function latestOf(rows: InvestPrrtFreshnessRow[]): string | null {
  let best: { key: string; dt: string } | null = null
  for (const r of rows) {
    const key = cmpKey(r.dt)
    if (key && r.dt && (!best || key > best.key)) best = { key, dt: r.dt }
  }
  return best?.dt ?? null
}

interface Props {
  /** 지표별 최근 자료일 — `buildFreshnessRows` 결과. 조회 전·실패면 null. */
  rows: InvestPrrtFreshnessRow[] | null
}

export default function InvestPrrtFreshness({ rows }: Props) {
  return (
    <div className="page-sub freshness">
      최근 갱신 {fmt(rows ? latestOf(rows) : null)}
      <InfoTip placement="center" size={14} title="지표별 최근 갱신일" label="지표별 최근 갱신일 안내">
        <table className="freshness__table">
          <caption className="sr-only">지표별 최근 갱신일</caption>
          <thead>
            <tr>
              <th scope="col">지표</th>
              <th scope="col">최근 갱신일</th>
            </tr>
          </thead>
          <tbody>
            {(rows ?? []).map((r) => (
              <tr key={r.vrblCd}>
                <td>{r.nm}</td>
                <td>{fmt(r.dt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </InfoTip>
    </div>
  )
}
