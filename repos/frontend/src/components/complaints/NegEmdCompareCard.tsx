/*
 * 민원현황(GIS) §8 — "부정 민원 상위 지역" 카드.
 *
 * 결정 (2026-08-21):
 *   - 정렬: negativeRatio DESC, emdCd ASC tie-breaker (2026-09-04 갱신으로 SQL 에서 정렬).
 *   - 색상: §3 KPI sentimentDist / PDF section 4 의 3색 막대와 동일 색 재사용.
 *   - 기본 5행, 더보기 시 최대 15행 (§7과 동일 패턴).
 *   - 부정 비율 산식 = negativeCount / (positive + neutral + negative). NULL 행 제외.
 *
 * 시안 정합 (2026-09-04):
 *   - row 레이아웃 — 시안 `.rl__row` 로 §7 EmdCompareCard 와 같은 틀
 *     (`__row--stack` · `__main` · `__top` 공용):
 *       [rank] [윗줄: 지역명 ········ 부정 비율(78%)]
 *              [아랫줄: 감정 막대(120px) ···· 부정 N건]
 *     종전 3칸 grid(우측 컬럼에 비율·부정 N건 세로 stack)는 폐지.
 *   - sentiment bar — 부정(빨강) / 보통(회색) / 긍정(초록) 의 3색 가로 막대.
 *     width = (count / totalCount) * 100%. 응답 envelope 의 sentimentDist.{positive,neutral,negative}
 *     와 totalCount 가 원천.
 *   - 색 dot — 제거 (시안엔 없다). 색 dot 은 범례에만 남는다.
 *   - footnote/범례 — `--caption-size`/`--title-2-size` 오타로 정의되지 않은 토큰을
 *     참조하던 버그 수정 (`--caption1-size`/`--title2-size`) — 실제 폰트 크기가
 *     시안보다 작게 보이던 원인 중 하나.
 *   - footnote "부정 비율 = 부정 민원 ÷ 지역 전체 민원".
 */

import { useState } from 'react'
import type { NegEmdCompareItem } from '../../api/complaints'
import Icon from '../Icon'
import {
  COLOR_SENTIMENT_NEGATIVE,
  COLOR_SENTIMENT_NEUTRAL,
  COLOR_SENTIMENT_POSITIVE,
  EMD_COMPARE_DEFAULT_LIMIT,
  EMD_COMPARE_MAX_LIMIT,
} from './legendConstants.ts'
import SentimentCriteriaTip from './SentimentCriteriaTip.tsx'

export interface NegEmdCompareCardProps {
  /** 핵심 API 응답의 negEmdCompare[]. negativeCount > 0 인 모든 읍면동. */
  negEmdCompare: NegEmdCompareItem[]
  /** 로딩 상태. */
  loading?: boolean
}

export default function NegEmdCompareCard({
  negEmdCompare,
  loading = false,
}: NegEmdCompareCardProps) {
  const [expanded, setExpanded] = useState(false)
  const limit = expanded ? EMD_COMPARE_MAX_LIMIT : EMD_COMPARE_DEFAULT_LIMIT
  const slice = negEmdCompare.slice(0, limit)
  const showMore = negEmdCompare.length > EMD_COMPARE_DEFAULT_LIMIT
  const moreCount = Math.max(0, negEmdCompare.length - EMD_COMPARE_DEFAULT_LIMIT)

  return (
    <aside className="minwon-emd-compare minwon-emd-compare--neg-card" aria-label="부정 민원 상위 지역">
      <header className="minwon-emd-compare__header">
        {/* 감정 분류 기준 — 시안 `G5`. 옆 순위 카드들의 InfoTip 과 같은 자리(제목 바로 옆)에 둔다. */}
        <div className="minwon-emd-compare__title-row">
          <h3 className="minwon-emd-compare__title">부정 민원 상위 지역</h3>
          <SentimentCriteriaTip size={16} />
        </div>
        <p className="minwon-emd-compare__subtitle">
          감정 분석 기준 · 부정 비율 순
        </p>
      </header>

      {loading && negEmdCompare.length === 0 ? (
        <p className="minwon-emd-compare__empty">불러오는 중…</p>
      ) : slice.length === 0 ? (
        <p className="minwon-emd-compare__empty">표시할 데이터가 없습니다</p>
      ) : (
        <ol className="minwon-emd-compare__list">
          {slice.map((row) => {
            const ratioPct = row.negativeRatio == null ? null : Math.round(row.negativeRatio * 100)
            return (
              // 시안 `.rl__row`: 순위 + 본문[윗줄(지역명 · 부정 비율) + 아랫줄(감정 막대 · 부정 N건)].
              // §7 지역별 카드와 같은 행 틀(`__row--stack` · `__main` · `__top`)을 쓴다.
              <li key={row.emdCd} className="minwon-emd-compare__row minwon-emd-compare__row--stack">
                <span className="minwon-emd-compare__rank">{row.rank}</span>
                <div className="minwon-emd-compare__main">
                  <div className="minwon-emd-compare__top">
                    {/* 동 이름 앞 색 dot 없음 (시안 부정 카드엔 없다). */}
                    <span className="minwon-emd-compare__emd-name">{row.emdNm}</span>
                    {/* 부정 비율 — 시안 `.rl__val > .rl__cnt` (지역별 카드 건수와 같은 글자). */}
                    <span className="minwon-emd-compare__count">
                      <span className="minwon-emd-compare__count-num">
                        {ratioPct != null ? `${ratioPct}%` : '—'}
                      </span>
                    </span>
                  </div>
                  {/* 아랫줄 — 감정 막대(왼쪽) · "부정 N건"(오른쪽, 시안 `.rl__sub`).
                      totalCount=0 (이론상 발생 불가, 서비스 보장) 일 때 막대 0 폭. */}
                  <div className="minwon-emd-compare__bottom">
                    <SentimentBar sentimentDist={row.sentimentDist} totalCount={row.totalCount} />
                    <span className="minwon-emd-compare__top-type">
                      부정 {row.negativeCount.toLocaleString()}건
                    </span>
                  </div>
                </div>
              </li>
            )
          })}
        </ol>
      )}

      {showMore && (
        <button
          type="button"
          className="minwon-emd-compare__more"
          onClick={() => setExpanded((v) => !v)}
          aria-expanded={expanded}
        >
          {expanded
            ? '접기'
            : `더보기 (${Math.min(moreCount, EMD_COMPARE_MAX_LIMIT - EMD_COMPARE_DEFAULT_LIMIT)})`}
          <Icon
            name="chevron-down"
            size={14}
            className={`minwon-emd-compare__more-ic${expanded ? ' is-expanded' : ''}`}
          />
        </button>
      )}

      {/* 2026-09-04 갱신 — 색 dot 범례 위 단순 구분선 (사용자 요청). */}
      <hr className="minwon-emd-compare__divider" aria-hidden="true" />

      {/* footnote + 색 dot 범례 — 시안의 "● 긍정 ■ 보통 ● 부정" + 보조 텍스트 */}
      <div className="minwon-emd-compare__sentiment-legend" role="group" aria-label="감성 분포 범례">
        <span className="minwon-emd-compare__sentiment-item">
          <span
            className="minwon-emd-compare__sentiment-dot"
            style={{ background: COLOR_SENTIMENT_POSITIVE }}
            aria-hidden="true"
          />
          긍정
        </span>
        <span className="minwon-emd-compare__sentiment-item">
          <span
            className="minwon-emd-compare__sentiment-dot"
            style={{ background: COLOR_SENTIMENT_NEUTRAL }}
            aria-hidden="true"
          />
          보통
        </span>
        <span className="minwon-emd-compare__sentiment-item">
          <span
            className="minwon-emd-compare__sentiment-dot"
            style={{ background: COLOR_SENTIMENT_NEGATIVE }}
            aria-hidden="true"
          />
          부정
        </span>
      </div>
      {/* 2026-09-04 갱신 — footnote 정합. "부정 비율 = 부정 민원 ÷ 지역 전체 민원" */}
      <p className="minwon-emd-compare__footnote">부정 비율 = 부정 민원 ÷ 지역 전체 민원</p>
    </aside>
  )
}

/**
 * sentiment bar — 시안 `U7` 3색 가로 막대 (막대 전체 120×5px 은 CSS 가 진다).
 * 각 조각 width = (분포 count / totalCount) * 100%. totalCount=0 이면 0 폭.
 */
function SentimentBar({
  sentimentDist,
  totalCount,
}: {
  sentimentDist: NegEmdCompareItem['sentimentDist']
  totalCount: number
}) {
  if (totalCount <= 0) {
    return (
      <span className="minwon-emd-compare__sentiment-bar" aria-hidden="true">
        <span style={{ width: '0%' }} />
      </span>
    )
  }
  const posPct = (sentimentDist.positive / totalCount) * 100
  const neuPct = (sentimentDist.neutral / totalCount) * 100
  const negPct = (sentimentDist.negative / totalCount) * 100
  // 시안 `U7` 과 같이 부정 → 보통 → 긍정 순(KPI 카드 막대와 통일).
  // 하단 색 범례(긍정 · 보통 · 부정)는 시안도 그 순서라 그대로 둔다.
  return (
    <span
      className="minwon-emd-compare__sentiment-bar"
      role="img"
      aria-label={`부정 ${sentimentDist.negative}건, 보통 ${sentimentDist.neutral}건, 긍정 ${sentimentDist.positive}건`}
    >
      <span
        className="minwon-emd-compare__sentiment-bar-seg"
        style={{
          width: `${negPct}%`,
          background: COLOR_SENTIMENT_NEGATIVE,
        }}
        aria-hidden="true"
      />
      <span
        className="minwon-emd-compare__sentiment-bar-seg"
        style={{
          width: `${neuPct}%`,
          background: COLOR_SENTIMENT_NEUTRAL,
        }}
        aria-hidden="true"
      />
      <span
        className="minwon-emd-compare__sentiment-bar-seg"
        style={{
          width: `${posPct}%`,
          background: COLOR_SENTIMENT_POSITIVE,
        }}
        aria-hidden="true"
      />
    </span>
  )
}
