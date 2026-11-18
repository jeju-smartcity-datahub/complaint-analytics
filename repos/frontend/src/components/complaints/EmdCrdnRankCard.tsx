/*
 * 민원현황(GIS) §7 — "읍·면·동 단속 순위" 카드.
 *
 * 결정:
 *   - 신규 API 를 만들지 않는다. `GET /api/hotspots/ranking` 의 `zones[]`
 *     (view=`enforcement`) 를 그대로 다시 그린다. 레이어를 바꾸면 그 배열이 재조회되므로
 *     카드도 함께 갱신된다.
 *   - 민원 순위 카드는 `emdCompare[]`(증감률·대표유형 보유), 단속·부족 카드는 `zones[]` 다.
 *     데이터 소스가 다른 것은 의도된 비대칭이다.
 *   - 행 틀은 §7 `EmdCompareCard` · §8 `NegEmdCompareCard` 와 공용인
 *     `minwon-emd-compare__*` 클래스만 재사용한다 (CSS 신규 규칙 0건).
 *   - 🔴 응답 `rank` 순 그대로 그린다. 프론트에서 다시 정렬하지 않는다.
 *   - 2026-09-30 — 🔴 **등급 3종(심각·경고·주의)만 노출하고 종전 15행 상한을
 *     쓰지 않는다.** 등급 판정의 분모는 **지도와 같은 전체 zone 수**(= 필터 *전* `zones.length`)이며
 *     공용 `pickRiskGrade` 한 곳이 판정한다 — 지도 polygon 과 이 카드가 같은 등급을 말한다.
 *     🔴 필터 *후* 길이로 다시 재면 경계가 움직여 순환한다. 무색 dot 은 사라진다.
 *   - `zoneNm` 이 `null` 일 수 있다(`LEFT JOIN tb_admdst` 미매칭). 그때는
 *     `미매칭 지역` 같은 문구가 아니라 `emdCd` 를 그대로 보여준다(디버깅 가능성 유지).
 *     React key 도 `emdCd` 단독이 아니라 `` `${emdCd ?? 'x'}-${rank}` `` 로 만든다.
 *
 *   - 🔴 건수 옆에 증감 배지(`DeltaBadge`)를 붙인다. 정의는 공용
 *     `DeltaBadge.tsx` 한 곳이며 색·기호 규칙은 민원 카드와 같다(단위 기본 `%`).
 *
 * 아랫줄은 그 동·기간의 최다 단속유형과 비율이다(민원 카드의 대표유형과 같은 형식).
 *    종전 `민원 N건 대비` 는 카드에서 내려갔고, 민원 수는 지도 팝업에 남는다.
 */

import { useState } from 'react'
import type { HotspotRankingZone } from '../../api/complaints'
import Icon from '../Icon'
import DeltaBadge from './DeltaBadge.tsx'
import InfoTip from '../common/InfoTip.tsx'
import { EMD_COMPARE_DEFAULT_LIMIT, RANK_DELTA_NOTE, RANK_GRADE_NOTE, RISK_GRADE_COLOR, pickRiskGrade } from './legendConstants.ts'

export interface EmdCrdnRankCardProps {
  /** `GET /api/hotspots/ranking?view=enforcement` 의 zones[]. 정렬은 응답 `rank` 순 그대로. */
  zones: HotspotRankingZone[]
  /** zones 전용 로딩 상태 — KPI 의 `loading` 과 다른 state 다 (ComplaintsPage 의 `zonesLoading`). */
  loading?: boolean
  /**
   * 행 클릭 → 지도(§6) 강조·팝업. 미지정이면 행은 클릭 대상이 아니다.
   * 🔴 강조·팝업을 여기서 만들지 않는다 — `emdCd` 만 올려보내고 `MinwonMap` 의 `onZoneClick`
   *    (지도 클릭과 같은 함수)이 전부 처리한다.
   */
  onSelectEmd?: (emdCd: string) => void
  /** 증감 툴팁 머리말 — 조회 조건별 `전일·전주·전월·전기 대비`(`periodText.compareLabel`). */
  compareLabel?: string
}

export default function EmdCrdnRankCard({
  zones,
  loading = false,
  onSelectEmd,
  compareLabel,
}: EmdCrdnRankCardProps) {
  const [expanded, setExpanded] = useState(false)
  // 🔴 분모는 필터 **전** 전체 길이다 (필터 후 길이로 재면 순환한다).
  const total = zones.length
  const graded = zones.filter((z) => pickRiskGrade(z.rank, total) !== null)
  // 🔴 15행 상한 제거 — 더보기를 누르면 등급이 있는 행 전부.
  const limit = expanded ? graded.length : EMD_COMPARE_DEFAULT_LIMIT
  const slice = graded.slice(0, limit)
  const showMore = graded.length > EMD_COMPARE_DEFAULT_LIMIT
  const moreCount = Math.max(0, graded.length - EMD_COMPARE_DEFAULT_LIMIT)

  return (
    <aside className="minwon-emd-compare" aria-label="읍·면·동 단속 순위">
      <header className="minwon-emd-compare__header">
        <div className="minwon-emd-compare__title-row">
          <h3 className="minwon-emd-compare__title">읍·면·동 단속 순위</h3>
          {/* 출처: GET /api/hotspots/ranking?view=enforcement zones[].
              ⚠️ 이 화면은 민원 유형 필터를 보내지 않아 문구에 유형을 적지 않는다. 유형 필터를 붙이면 `selectEnforcement` 가
                 민원 유형 코드를 `crdn_type_cd`(원천 전 행 NULL)에 걸어 단속 순위가 0행이 된다 — 그때 이 문구도 함께 고친다. */}
          <InfoTip
            placement="block"
            label="읍·면·동 단속 순위 안내"
            title="읍·면·동 단속 순위"
            items={[
              '조회 기간·행정구역의 불법 주차 단속 건수가 많은 순입니다.',
              RANK_GRADE_NOTE,
              `${RANK_DELTA_NOTE} 직전 기간이 0건이면 1건으로 바꿔 계산합니다 — 실제 증가는 더 클 수 있습니다.`,
              '아랫줄은 같은 지역·같은 기간 단속 중 가장 많은 단속 유형과 그 비율입니다.',
            ]}
          />
        </div>
        <p className="minwon-emd-compare__subtitle">불법 주차 단속 건수 집계</p>
      </header>

      {loading && zones.length === 0 ? (
        <p className="minwon-emd-compare__empty">불러오는 중…</p>
      ) : graded.length === 0 ? (
        <p className="minwon-emd-compare__empty">표시할 데이터가 없습니다</p>
      ) : (
        <ol className="minwon-emd-compare__list">
          {slice.map((zone) => {
            // 필터를 통과했으므로 등급이 반드시 있다 — 항상 색이 붙는다.
            const dotColor = RISK_GRADE_COLOR[pickRiskGrade(zone.rank, total)!]
            // 🔴 `emdCd` 가 `null` 인 zone(`LEFT JOIN tb_admdst` 미매칭)은 클릭 대상에서 제외한다 —
            //    지도 레지스트리의 키가 `emdCd` 라 부를 대상이 없다.
            const emdCd = zone.emdCd
            const select = onSelectEmd && emdCd ? () => onSelectEmd(emdCd) : undefined
            return (
              <li
                // `emdCd` 는 타입상 null 일 수 있어 단독 key 로 쓰지 않는다.
                key={`${zone.emdCd ?? 'x'}-${zone.rank}`}
                className="minwon-emd-compare__row minwon-emd-compare__row--stack"
              >
                {/* 🔴 행 안을 진짜 `<button>` 으로 감싼다(카드 3종 동일 방식).
                    종전 `<li role="button">` 은 `<li>` 의 암묵 `listitem` 을 덮어써 스크린리더가
                    "N개 중 M번째" 를 읽지 못했다. `emdCd` 가 `null` 인 행은 `disabled` —
                    포커스를 받지 않고 `listitem` 은 유지된다. */}
                <button
                  type="button"
                  className="minwon-emd-compare__row-btn"
                  disabled={!select}
                  onClick={select}
                >
                  <span className="minwon-emd-compare__rank">{zone.rank}</span>
                  <span className="minwon-emd-compare__main">
                    <span className="minwon-emd-compare__top">
                      <span className="minwon-emd-compare__emd-name">
                        <span
                          className="minwon-emd-compare__dot"
                          style={{ background: dotColor }}
                          aria-hidden="true"
                        />
                        {zone.zoneNm ?? zone.emdCd ?? '—'}
                      </span>
                      <span className="minwon-emd-compare__count">
                        <span className="minwon-emd-compare__count-num">
                          {zone.crdnCnt.toLocaleString()}건
                        </span>
                        {/* 직전 동일 길이 구간 대비 단속 건수 증감.
                            🔴 민원 카드와 같은 `DeltaBadge` 한 곳을 쓴다 — 화면은 화살표, 수치는 툴팁이다. */}
                        <DeltaBadge
                          deltaPct={zone.deltaPct ?? null}
                          inferred={zone.deltaPctInferred}
                          compareLabel={compareLabel}
                        />
                      </span>
                    </span>
                    {/* 최다 단속유형 + 비율 (민원 카드의 대표유형과 같은 형식). */}
                    <span className="minwon-emd-compare__top-type">
                      {zone.topCrdnMthd ?? '—'}{' '}
                      {zone.topCrdnMthdRatio != null ? `${Math.round(zone.topCrdnMthdRatio * 100)}%` : ''}
                    </span>
                  </span>
                </button>
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
          {/* 🔴 15행 상한이 없으므로 `moreCount` 를 캡하지 않는다. */}
          {expanded ? '접기' : `더보기 (${moreCount})`}
          <Icon
            name="chevron-down"
            size={14}
            className={`minwon-emd-compare__more-ic${expanded ? ' is-expanded' : ''}`}
          />
        </button>
      )}
    </aside>
  )
}
