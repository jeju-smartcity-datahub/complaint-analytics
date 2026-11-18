import { Link } from 'react-router-dom'
import Icon from '../Icon'
import type { RegionItem } from '../../api/dashboard'
import { TYPE_META } from './complaintTypes'

/*
 * 읍·면·동 민원 순위 — 시안의 `.region-row` 5카드를 그대로 옮겼다.
 * 카드마다 순위·건수·전기대비 증감·유형별 누적바(hover 시 유형별 건수 툴팁)를 보여준다.
 * 누적바 유형→색은 tokens.css 의 `--series-*` 토큰만 참조한다 (하드코딩 금지).
 *
 * 데이터는 DashboardPage 가 /api/dashboard/regions 로 받아 props 로 내려준다.
 * 백엔드는 전체 읍면동을 count 내림차순으로 주며, 여기서 상위 5개만 렌더한다.
 */

interface RegionRankingProps {
  /** /api/dashboard/regions 응답(전체 읍면동). null 이면 미수신. */
  regions: RegionItem[] | null
  loading: boolean
  error: boolean
  /** 증감 비교 기준 — 어제 `전일` · 주간 `전주` · 월간 `전월` · 기간 선택 `전기`. */
  compareLabel: '전일' | '전주' | '전월' | '전기'
}

export default function RegionRanking({
  regions,
  loading,
  error,
  compareLabel,
}: RegionRankingProps) {
  const top5 = (regions ?? []).slice(0, 5)

  return (
    <section>
      <div className="region-head">
        <div>
          <h2 className="section-title">읍·면·동 민원 순위</h2>
          <p className="section-sub">민원 건수 · 전기 대비 변화 비교 · 민원 유형별 분포</p>
        </div>
        <Link to="/complaints" className="card-link">
          자세히 보기 <Icon name="chevron-right" size={16} />
        </Link>
      </div>

      {error ? (
        <div className="card region-state">읍·면·동 순위를 불러오지 못했습니다.</div>
      ) : loading && top5.length === 0 ? (
        <div className="card region-state">불러오는 중…</div>
      ) : top5.length === 0 ? (
        <div className="card region-state">표시할 데이터가 없습니다.</div>
      ) : (
        <div className="region-row">
          {top5.map((r, i) => {
            /*
             * 시안(2026-11) — `region__delta` 한 줄이 방향·비교기준·증감률을 함께 말한다:
             *   `▲ 전일대비 17% 민원증가` / `▼ 전주대비 9% 민원하락` / `전월대비 동일`.
             * 비교기준은 조회단위가 정한다(어제→전일 · 주간→전주 · 월간→전월 · 기간 선택→전기).
             * 🔴 색은 줄 전체에 `t-up`(빨강)/`t-down`(초록)이다 — 민원 증가는 나쁜 일이다
             *    (`dashboard.css` `.t-up` 주석 · `legendConstants.ts` §7 과 같은 규약).
             * 🔄 종전 `region__trend`(순위 변동 배지)는 시안에서 빠져 걷었다 —
             *    응답의 `rankDelta` 는 그대로 두되 이 카드가 읽지 않는다.
             */
            const dir: 'up' | 'down' | 'flat' =
              r.deltaPct > 0 ? 'up' : r.deltaPct < 0 ? 'down' : 'flat'
            const deltaText =
              dir === 'flat'
                ? `${compareLabel}대비 동일`
                : `${dir === 'up' ? '▲' : '▼'} ${compareLabel}대비 ${Math.abs(r.deltaPct)}% ${
                    dir === 'up' ? '민원증가' : '민원하락'
                  }`

            // 누적바 폭은 유형별 건수 합을 분모로 계산한다 (합이 0이면 세그먼트 없음).
            const bdTotal = r.breakdown.reduce((s, b) => s + b.count, 0)
            const segs = [...r.breakdown]
              .sort((a, b) => a.typeCd.localeCompare(b.typeCd))
              .map((b) => {
                const pct = bdTotal > 0 ? (b.count / bdTotal) * 100 : 0
                const meta = TYPE_META[b.typeCd] ?? {
                  token: '--line-alternative',
                  fallback: '#70737c14',
                  label: b.typeCd,
                }
                return { ...b, pct, meta }
              })

            return (
              <div className="card region" key={r.emdCd}>
                <div className="region__top">
                  <span className="region__id">
                    {/* 색은 CSS 가 진다(시안: --fill-strong 배경 · --text-neutral
                        글씨). 인라인 색을 두지 않는다. */}
                    <span className="region__num">{i + 1}</span>
                    <span className="region__name">{r.emdNm}</span>
                  </span>
                </div>

                <div className="region__value">
                  <b>{r.count}</b>
                  <span>건</span>
                </div>

                <div className="region__delta">
                  <span className={`t-${dir}`}>{deltaText}</span>
                </div>

                <div className="stackbar">
                  {segs.map((b) => (
                    <span className="stackbar__seg" style={{ width: `${b.pct}%` }} key={b.typeCd}>
                      <i style={{ background: `var(${b.meta.token})` }} />
                      <span className="stackbar__tip">
                        <span className="dot" style={{ background: `var(${b.meta.token})` }} />
                        {b.meta.label} <b>{b.count}건</b> ({b.pct.toFixed(1)}%)
                      </span>
                    </span>
                  ))}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}
