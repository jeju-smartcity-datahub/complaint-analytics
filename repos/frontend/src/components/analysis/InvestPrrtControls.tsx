import { useMemo } from 'react'
import Icon from '../Icon'
import type {
  InvestPrrtGradeCriterion,
  InvestPrrtLot,
  InvestPrrtLvlCd,
  InvestPrrtWeight,
} from '../../api/investPrrt'
import {
  DEFAULT_FILTER,
  buildEmdOptions,
  buildSggOptions,
  buildSortOptions,
  type InvestPrrtFilter,
  type InvestPrrtSort,
} from './investPrrtView'
import SrLabel from '../common/SrLabel'

/*
 * 투자우선순위 보드 지도 컨트롤 (TSK-4036 Step 9). 시안 `.map-controls` 구성 그대로다 —
 * 행정구역 · 정렬 기준 · 우선순위 세그먼트 · 초기화.
 *
 * 시안의 select 는 장식(`span.select`)이라, 실제로 동작하는 네이티브 `<select>` 로 바꿨다
 * (집중구역 설정 패널의 `.field__select-wrap` + `.field__select-native` 를 그대로 쓴다).
 *
 * 🔴 **전부 클라이언트 필터다 — 재조회하지 않는다.** 이 컴포넌트는 필터 상태를 갖지 않고,
 * 값과 `onChange` 만 받는다(상태의 주인은 페이지 — 목록·지도가 같은 표시 집합을 쓰기 위해서다).
 * 🔴 선택지는 응답에서 만든다 — 행정구역은 `lots`(전체), 정렬은 `weights`, 등급은 `gradeCriteria`.
 * 🔴 읍면동 값은 이름이 아니라 **`emdCd`** 다.
 */

interface Props {
  /** 🔴 응답의 **전체** `lots` — 표시 집합이 아니다(필터를 걸어도 선택지가 줄지 않아야 한다). */
  lots: InvestPrrtLot[]
  weights: InvestPrrtWeight[]
  gradeCriteria: InvestPrrtGradeCriterion[]
  filter: InvestPrrtFilter
  onChange: (next: InvestPrrtFilter) => void
}

export default function InvestPrrtControls({
  lots,
  weights,
  gradeCriteria,
  filter,
  onChange,
}: Props) {
  const sggs = useMemo(() => buildSggOptions(lots), [lots])
  const emds = useMemo(() => buildEmdOptions(lots, filter.sggNm), [lots, filter.sggNm])
  const sorts = useMemo(() => buildSortOptions(weights), [weights])

  const grades: { value: InvestPrrtLvlCd | ''; label: string }[] = [
    { value: '', label: '전체' },
    ...gradeCriteria.map((c) => ({ value: c.prrtLvlCd, label: c.prrtLvlNm || c.prrtLvlCd })),
  ]

  return (
    <div className="map-controls">
      <div className="ctrl">
        <span className="ctrl__label">행정구역</span>
        <div className="field__select-wrap">
          <SrLabel text="시군구">
            <select
              className="field__select-native"
              aria-label="시군구"
              value={filter.sggNm}
              // 🔴 시를 바꾸면 읍면동을 비운다 — 남기면 다른 시의 동 코드로 걸러 0건이 된다.
              onChange={(e) => onChange({ ...filter, sggNm: e.target.value, emdCd: '' })}
            >
              <option value="">제주도 전체</option>
              {sggs.map((sggNm) => (
                <option key={sggNm} value={sggNm}>
                  {sggNm}
                </option>
              ))}
            </select>
          </SrLabel>
          <Icon name="chevron-down" size={18} />
        </div>
        <div className="field__select-wrap">
          <SrLabel text="읍면동">
            <select
              className="field__select-native"
              aria-label="읍면동"
              value={filter.emdCd}
              // 시군구를 고르지 않으면 읍면동 목록이 없다(집중구역 패널과 같은 관례).
              disabled={!filter.sggNm}
              onChange={(e) => onChange({ ...filter, emdCd: e.target.value })}
            >
              <option value="">읍면동 전체</option>
              {emds.map((emd) => (
                <option key={emd.emdCd} value={emd.emdCd}>
                  {emd.emdNm}
                </option>
              ))}
            </select>
          </SrLabel>
          <Icon name="chevron-down" size={18} />
        </div>
      </div>

      <div className="risk-group">
        <span className="ctrl__label">우선순위</span>
        <div className="risk-seg" role="group" aria-label="우선순위">
          {grades.map((g) => (
            <button
              key={g.value || 'ALL'}
              type="button"
              className={filter.grade === g.value ? 'is-active' : ''}
              aria-pressed={filter.grade === g.value}
              onClick={() => onChange({ ...filter, grade: g.value })}
            >
              {g.label}
            </button>
          ))}
        </div>
        {/* 시안과 같이 등급·행정구역·정렬을 전부 처음 상태로 돌린다. */}
        <button className="btn-reset" type="button" onClick={() => onChange(DEFAULT_FILTER)}>
          초기화
        </button>
      </div>
      {/* 시안 순서(2026-11-12 번들): 행정구역 → 우선순위 → 정렬 기준. 세 묶음 모두 왼쪽부터 붙는다(TSK-4352 · analysis.css `.risk-group` 주석). */}
      <div className="ctrl">
        <span className="ctrl__label">정렬 기준</span>
        <div className="field__select-wrap">
          <SrLabel text="정렬 기준">
            <select
              className="field__select-native"
              aria-label="정렬 기준"
              value={filter.sort}
              onChange={(e) => onChange({ ...filter, sort: e.target.value as InvestPrrtSort })}
            >
              {sorts.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </SrLabel>
          <Icon name="chevron-down" size={18} />
        </div>
      </div>

    </div>
  )
}
