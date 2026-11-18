import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import Icon from '../Icon'
import { fetchHotspots, type HotspotItem } from '../../api/dashboard'
import { GRADE_BADGE_CLASS } from '../analysis/hotspotGrade'

/*
 * 불법주차 집중구역 순위 카드 — 시안의 `.hotspots` 카드 구조를 그대로 쓴다 (문구 변경: TSK-4180)
 * (`.hot` = `.hot__rank` + `.hot__body` + 위험 배지).
 * 데이터는 /api/dashboard/hotspots 로 카드가 직접 조회한다 (마운트 시 + 행정구역 변경 시).
 *
 * 위험 배지(심각/경고/주의)는 **되살렸다**(TSK-4032 Step 15). 예전에는 백엔드에 등급 컬럼이 없어
 * 제거했지만, 지금은 카드가 실시간 군집 분석 결과를 받으면서 `grade`·`gradeNm` 이 함께 온다(D8).
 * 배지 색·클래스는 집중구역 화면(`analysis/hotspotGrade.ts`)의 매핑을 **재사용**한다 —
 * 같은 등급이 화면마다 다른 색으로 보이는 일을 막는다. 라벨은 응답의 `gradeNm` 을 그대로 쓴다.
 *
 * 시안과 다른 점
 *   - 시안의 `연동 · 반경 200m` 대신 실제 컬럼(`민원 N건 · 단속 N건`)을 쓴다 — 반경은 응답에 없다.
 *   - 부제가 `최근 1개월 기준` 이다(TSK-4169). 기준일은 "오늘" 이 아니라 **최신 민원 발생일**이며,
 *     거기서 1개월을 거슬러 집계한다 — 수집이 며칠 밀려도 목록이 비지 않게 하기 위함이다.
 *
 * 🔄 TSK-4169 — **지역은 목록도 따른다. 기간만 조회단위(어제/주간/월간)와 무관한 1개월 고정이다.**
 * 그래서 카드는 마운트 1회가 아니라 행정구역(`sggCd`)이 바뀔 때마다 재조회한다. `자세히 보기`
 * 링크는 대시보드의 현재 필터 전체(기간 포함)를 타며, 주소는 페이지가 조립해 `gisHref` 로
 * 내려 준다. ⚠️ 지역을 좁히면 군집이 0개가 되어 `데이터 없음` 이 뜰 수 있다 — 고장이 아니다.
 */

interface Props {
  /** 집중구역 화면 딥링크. 대시보드가 현재 기간·행정구역으로 조립한 주소다. */
  gisHref: string
  /** 필터바의 행정구역(시군구 5자리). 빈 문자열이면 전 지역. */
  sggCd: string
}

export default function HotspotRanking({ gisHref, sggCd }: Props) {
  const [items, setItems] = useState<HotspotItem[] | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let cancelled = false
    fetchHotspots(5, sggCd || undefined)
      .then((d) => {
        if (!cancelled) setItems(d)
      })
      .catch(() => {
        if (!cancelled) {
          setItems(null)
          setError(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [sggCd])

  return (
    <div className="card hotspots">
      <div className="card-head">
        <div>
          <h2 className="card-head__title">불법주차 집중구역 순위</h2>
          <p className="card-head__sub">최근 1개월 기준</p>
        </div>
        <Link to={gisHref} className="card-link">
          자세히 보기 <Icon name="chevron-right" size={16} />
        </Link>
      </div>

      {error ? (
        <div className="panel-state">불러오기 실패</div>
      ) : items === null ? (
        <div className="panel-state">불러오는 중…</div>
      ) : items.length === 0 ? (
        <div className="panel-state">데이터 없음</div>
      ) : (
        <div className="hot-list">
          {items.map((h) => (
            <div className="hot" key={h.rnk}>
              {/* 🔄 TSK-4169 — 색은 CSS 가 진다(시안 2026-09-30 개정: 회색 단색 — `.region__num` 과
                  같은 값). 🔴 등급은 오른쪽 badge 가 지므로 번호에 등급색을 넣지 않는다.
                  되돌리려면 계획서 9-3. */}
              <span className="hot__rank">{h.rnk}</span>
              <div className="hot__body">
                <div className="hot__name">{h.zoneNm}</div>
                <div className="hot__meta">
                  민원 {h.cvlcptCnt}건 · 단속 {h.crdnCnt}건
                </div>
              </div>
              <span className={GRADE_BADGE_CLASS[h.grade]}>{h.gradeNm}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
