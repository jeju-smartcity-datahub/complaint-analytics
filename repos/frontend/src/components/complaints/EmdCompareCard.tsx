/*
 * 민원현황(GIS) §7 — "지역별 민원 현황" 카드 (TSK-3874 §7 구현).
 *
 * 가이드 §7 결정 (2026-08-27) + TSK-4167 Step 9 개정 (2026-09-30 · D13·D14):
 *   - 🔴 **등급 3종(심각·경고·주의)만 노출한다.** 주의 초과(백분위 50% 밖)는 행 자체를 뺀다 —
 *     결과적으로 모든 행에 색이 붙고 무색 dot 이 사라진다.
 *   - 🔴 **종전 15행 상한을 쓰지 않는다** (그 상수는 §8 부정 민원 카드 전용으로 남았다).
 *     더보기를 누르면 등급이 있는 행 전부다.
 *   - 🔴 등급 판정의 분모는 **지도와 같은 전체 zone 수**(= 필터 *전* `emdCompare.length`)다.
 *     노출 행 수로 재면 안 된다 — 필터 후 길이로 다시 재면 경계가 움직여 순환한다.
 *     지도 polygon 과 이 카드가 처음으로 같은 등급을 말하게 된다(`pickRiskGrade` 단일 출처).
 *   - deltaPct 색상: ▲빨강(증가=나쁨) / ▼초록(감소=좋음) / —검정 / ⚠️추론.
 *     🔴 TSK-4197 Step 4-1 — 그 배지(`DeltaBadge`)는 이 파일 안의 로컬 함수였으나 단속·수요부족
 *     카드와 공용이 되어 `./DeltaBadge.tsx` 로 옮겼다. 정의는 거기 한 곳뿐이다.
 *
 * 데이터 소스: GET /api/complaints/gis 응답의 emdCompare[] (§7 2026-08-21 확정).
 * 핵심 API 응답 envelope 의 EmdCompareItem 그대로 사용.
 */

import { useState } from 'react'
import type { EmdCompareItem } from '../../api/complaints'
import Icon from '../Icon'
import DeltaBadge from './DeltaBadge.tsx'
import InfoTip from '../common/InfoTip.tsx'
import { EMD_COMPARE_DEFAULT_LIMIT, RANK_DELTA_NOTE, RANK_GRADE_NOTE, RISK_GRADE_COLOR, pickRiskGrade } from './legendConstants.ts'

export interface EmdCompareCardProps {
  /** 핵심 API 응답의 emdCompare[]. 0건 초과 전부 (가이드 §7). */
  emdCompare: EmdCompareItem[]
  /** 로딩 상태 — spinner 또는 빈 상태 표기 결정. */
  loading?: boolean
  /**
   * 행 클릭 → 지도(§6) 강조·팝업 (TSK-4167 Step 11). 미지정이면 행은 클릭 대상이 아니다.
   * 🔴 강조·팝업을 여기서 만들지 않는다 — `emdCd` 만 올려보내고 `MinwonMap` 의 `onZoneClick`
   *    (지도 클릭과 같은 함수)이 전부 처리한다.
   */
  onSelectEmd?: (emdCd: string) => void
  /** 증감 툴팁 머리말 — 조회 조건별 `전일·전주·전월·전기 대비`(`periodText.compareLabel`). */
  compareLabel?: string
}

export default function EmdCompareCard({
  emdCompare,
  loading = false,
  onSelectEmd,
  compareLabel,
}: EmdCompareCardProps) {
  const [expanded, setExpanded] = useState(false)
  // 🔴 분모는 필터 **전** 전체 길이다 (D13 — 필터 후 길이로 재면 순환한다).
  const total = emdCompare.length
  const graded = emdCompare.filter((r) => pickRiskGrade(r.rank, total) !== null)
  // 🔴 15행 상한 제거 — 더보기를 누르면 등급이 있는 행 전부.
  const limit = expanded ? graded.length : EMD_COMPARE_DEFAULT_LIMIT
  const slice = graded.slice(0, limit)
  const showMore = graded.length > EMD_COMPARE_DEFAULT_LIMIT
  const moreCount = Math.max(0, graded.length - EMD_COMPARE_DEFAULT_LIMIT)

  return (
    <aside className="minwon-emd-compare" aria-label="읍·면·동 민원 순위">
      <header className="minwon-emd-compare__header">
        {/* TSK-4181 Step 2-1 — 제목·부제만 교체. 행 구조·더보기는 그대로 (D2). */}
        <div className="minwon-emd-compare__title-row">
          <h3 className="minwon-emd-compare__title">읍·면·동 민원 순위</h3>
          {/* TSK-4242 — 출처: GET /api/complaints/gis emdCompare[] · 증감은 ComplaintServiceImpl(직전 0건 → 분모 1, deltaPctInferred).
              🔴 "같은 건수는 같은 순위" 를 쓰지 않는다 — 이 카드의 rank 는 서비스의 행번호(i + 1)라 동률도 갈린다
                 (단속·수요부족 카드는 SQL RANK() 라 다르다). */}
          <InfoTip
            placement="block"
            label="읍·면·동 민원 순위 안내"
            title="읍·면·동 민원 순위"
            items={[
              '조회 기간·행정구역의 민원 건수가 많은 순입니다.',
              RANK_GRADE_NOTE,
              `${RANK_DELTA_NOTE} 직전 기간이 0건이면 1건으로 바꿔 계산합니다 — 실제 증가는 더 클 수 있습니다.`,
              '아랫줄은 그 지역에서 가장 많은 민원 유형과 비율입니다.',
            ]}
          />
        </div>
        <p className="minwon-emd-compare__subtitle">민원 건수 집계</p>
      </header>

      {loading && emdCompare.length === 0 ? (
        <p className="minwon-emd-compare__empty">불러오는 중…</p>
      ) : graded.length === 0 ? (
        <p className="minwon-emd-compare__empty">표시할 데이터가 없습니다</p>
      ) : (
        <ol className="minwon-emd-compare__list">
          {slice.map((row) => {
            // 필터를 통과했으므로 등급이 반드시 있다 — 항상 색이 붙는다.
            const dotColor = RISK_GRADE_COLOR[pickRiskGrade(row.rank, total)!]
            // 🔴 `emdCd` 없는 행은 클릭 대상에서 제외한다(이 카드의 `emdCd` 는 항상 있지만 zone
            //    카드 2종과 **같은 방식**을 쓴다 — 셋이 같은 행 틀을 공유한다).
            const select =
              onSelectEmd && row.emdCd ? () => onSelectEmd(row.emdCd) : undefined
            return (
              <li
                key={row.emdCd}
                className="minwon-emd-compare__row minwon-emd-compare__row--stack"
              >
                {/* 🔴 TSK-4167 Step 11-5 — 행 안을 진짜 `<button>` 으로 감싼다.
                    종전 `<li role="button">` 은 ARIA 역할이 요소당 하나라 `<li>` 의 암묵
                    `listitem` 을 덮어써 스크린리더가 "N개 중 M번째" 를 읽지 못했다.
                    버튼이 포커스 순서·Enter/Space·커서를 기본 제공하므로 수동 부여가 전부 사라졌다.
                    클릭 대상이 아닌 행은 `disabled` — 포커스를 받지 않고 `listitem` 은 유지된다.
                    `__row-btn` 이 `<li>` 가 하던 flex 배치(gap 12 포함)를 이어받는다. */}
                <button
                  type="button"
                  className="minwon-emd-compare__row-btn"
                  disabled={!select}
                  onClick={select}
                >
                  <span className="minwon-emd-compare__rank">{row.rank}</span>
                  {/* TSK-4165 F6 — 시안 `.rl__row` 구조: 순위 + 본문[윗줄(지역명 · 건수+증감) + 아랫줄(유형)].
                      종전 3칸 grid(align-items:center)는 숫자가 두 줄 **가운데**에 떠서 지역명 줄과 어긋났다.
                      `--stack` · `__main` · `__top` 은 §8 부정 카드와 공용(F8). */}
                  <span className="minwon-emd-compare__main">
                    <span className="minwon-emd-compare__top">
                      {/* dot 은 emd-name 안 inline — 동 이름과 같은 baseline. dotColor 는 전체 zone 수 기준 등급 (지도와 동일 · D13). */}
                      <span className="minwon-emd-compare__emd-name">
                        <span
                          className="minwon-emd-compare__dot"
                          style={{ background: dotColor }}
                          aria-hidden="true"
                        />
                        {row.emdNm}
                      </span>
                      {/* 건수 + 증감 한 줄 ('52건 ▲13%') — 시안 `.rl__val`. */}
                      <span className="minwon-emd-compare__count">
                        <span className="minwon-emd-compare__count-num">
                          {row.cvlcptCnt.toLocaleString()}건
                        </span>
                        <DeltaBadge
                          deltaPct={row.deltaPct}
                          inferred={row.deltaPctInferred}
                          compareLabel={compareLabel}
                        />
                      </span>
                    </span>
                    <span className="minwon-emd-compare__top-type">
                      {row.topTypeNm ?? '—'}{' '}
                      {row.topTypeRatio != null ? `${Math.round(row.topTypeRatio * 100)}%` : ''}
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
          {/* 🔴 15행 상한이 없으므로 `moreCount` 를 캡하지 않는다 (D13). */}
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
