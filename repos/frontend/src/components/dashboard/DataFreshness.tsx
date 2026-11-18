import { useEffect, useState } from 'react'
import { fetchDataFreshness, type DataFreshness as DataFreshnessResponse } from '../../api/dashboard'
import InfoTip from '../common/InfoTip'

/*
 * 상단 `최근 갱신` 표기 + 데이터 기준일 InfoTip (공용화). 시안 `.page-sub.freshness` 구조 그대로다.
 * p 안에 표를 둘 수 없어 div
 *
 * 🔴 문구는 `최근 갱신` 이다(🔁 2026-10-19 — 2026-10-01 확정이던 `최근 업데이트` 를 바꿨다.
 *    시안 번들 문구와 같아졌다. 종전 화면은 `최종 업데이트` 였다) — **여기 적힌 것이 정본**이다.
 * 🔴 출처는 민원 발생일이 아니라 **수집 배치 실행일**이다(`/api/dashboard/data-freshness`).
 *    종전 `최종 업데이트` 가 쓰던 `/data-status` 와 다른 사실을 말한다 — 그 API 는 집중구역 카드
 *    기준일로 계속 쓰이므로 살아 있다.
 * 🔴 `DashboardPage` 가 상태를 떠안지 않게 **조회·포맷·렌더를 이 컴포넌트에 가둔다**.
 * 🔴 InfoTip 은 **CSS 전용**으로 열린다(`:hover`/`:focus-within` → `display:flex`) — 열림 JS 상태를 만들지
 *    않는다(Esc 는 blur 로 닫을 뿐이다). 시안이 그렇고, 포커스 대상인 아이콘 `button` 덕분에 키보드 포커스로도
 *    열린다.
 *    그 원칙은 `common/InfoTip` 이 진다 — 여기서 열림 상태를 따로 만들지 않는다.
 *
 * ⚠️ 팝오버는 `position:absolute` + `z-index:60` 이라 필터바를 덮는다(의도된 동작).
 *    🔴 조상에 `overflow:hidden` 이 있다 — `layout.css` 의 `.main{height:100vh; overflow:hidden}`
 *    이다(`.topbar` 자체는 flex 뿐이라 무해하다). 원리상 클리핑 대상이지만, 팝오버가 열리는
 *    위치(상단 ~58px · `min-width:320px`)가 `.main` 박스 안에 완전히 들어가 **실제로는 잘리지 않는다**.
 *    🔴 **InfoTip 팝업의 위치·크기를 바꾸면 이 전제가 깨진다** — 넓히거나 위로 올리면 `.main` 이 잘라 먹는다.
 *    그때는 InfoTip 팝업(`.info-tip__pop`)을 `position: fixed` 로 올리고 좌표를 계산해야 한다.
 */

/**
 * `2026-09-30T02:14:31` → `2026.09.30 · 02:14`. 값이 없거나 형식이 어긋나면 `—`.
 *
 * 🔴 `new Date` 에 넘기지 않는다 — 타임존 없는 로컬 일시라 파싱 규칙에 따라 시각이 밀린다.
 * 종전 `DashboardPage.formatLastUpdated` 와 같은 수법으로 문자열을 그대로 잘라 쓴다
 * (그 함수를 여기로 옮겨 왔고 `DashboardPage` 에서는 사라졌다).
 */
function fmt(dt: string | null): string {
  const m = dt?.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  if (!m) return '—'
  const [, y, mo, d, hh, mi] = m
  return `${y}.${mo}.${d} · ${hh}:${mi}`
}

export default function DataFreshness() {
  const [data, setData] = useState<DataFreshnessResponse | null>(null)

  // 필터와 무관해 마운트 시 1회만 조회한다.
  useEffect(() => {
    let cancelled = false
    void fetchDataFreshness()
      .then((d) => {
        if (!cancelled) setData(d)
      })
      .catch(() => {
        // ⚠️ 조회 실패도 `—` 다 — 페이지 제목 옆이라 **화면을 죽이지 않는다**.
        //    그때 InfoTip 안의 표는 비어 있다(items 가 없다).
        if (!cancelled) setData(null)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div className="page-sub freshness">
      최근 갱신 {fmt(data?.latestDt ?? null)}
      <InfoTip placement="center" size={14} title="데이터 기준일" label="데이터 기준일 안내">
        <table className="freshness__table">
          <caption className="sr-only">데이터 출처 및 갱신 주기</caption>
          <thead>
            <tr>
              <th scope="col">데이터</th>
              <th scope="col">기준일</th>
              <th scope="col">갱신 주기</th>
            </tr>
          </thead>
          <tbody>
            {(data?.items ?? []).map((it) => (
              <tr key={it.jobNm}>
                <td>{it.dataNm}</td>
                <td>{fmt(it.lastExcnDt)}</td>
                <td>{it.cyclNm}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </InfoTip>
    </div>
  )
}
