import Icon from '../Icon'
import type { InvestPrrtLot, InvestPrrtSmry } from '../../api/investPrrt'
import { buildAcdntLotsNote, fmtCnt } from './investPrrtView'

/*
 * 투자우선순위 보드 상단 카드 3개. 시안 `section.stat-row > .card.stat ×3` 그대로다(제목 없는 section 경고로 태그만 div).
 *
 * 🔴 숫자는 **표시 집합(상위 N)이 아니라 전체**(`smry`)다 — 화면 필터를 걸어도 바뀌지 않는다.
 *
 * 카드 아래 작은 글씨(`stat__delta`)는 시안 그대로다: `제주도 전역` · `{기간} 기준` · 사고 난 곳(`buildAcdntLotsNote`).
 * 기간·곳수는 응답에서 오고 조회 전·실패면 그 줄을 그리지 않는다.
 */

interface Props {
  /** 조회 전·실패면 null. */
  smry: InvestPrrtSmry | null
  /** 조회 중이면 값 자리에 `…`, 아니면(실패) `—` 를 둔다. */
  loading: boolean
  /**
   * 사고 카드 라벨의 기간 — 응답 구간에서 만든 `buildPeriodLabel` 값(고정 기간 문구 폐기).
   * 조회 전·실패면 null 이고 라벨은 `사고` 만 남는다.
   */
  periodLabel: string | null
  /** 응답 `lots` 전체(필터 전) — 사고 카드 아래 글씨용. 조회 전·실패면 null. */
  lots: InvestPrrtLot[] | null
}

export default function InvestPrrtStats({ smry, loading, periodLabel, lots }: Props) {
  const blank = loading ? '…' : '—'
  const acdntNote = buildAcdntLotsNote(lots)

  return (
    <div className="stat-row">
      <div className="card stat">
        <div className="stat__icon stat__icon--blue">
          <Icon name="company" size={28} />
        </div>
        <div>
          <div className="stat__label">분석대상 주차장</div>
          <div className="stat__value">
            {smry ? fmtCnt(smry.trgtCnt) : blank}
            <span className="stat__unit">개소</span>
          </div>
          <div className="stat__delta">제주도 전역</div>
        </div>
      </div>

      <div className="card stat">
        <div className="stat__icon stat__icon--red">
          <Icon name="warning" size={28} />
        </div>
        <div>
          <div className="stat__label">높음 우선순위</div>
          <div className="stat__value">
            {smry ? fmtCnt(smry.highCnt) : blank}
            <span className="stat__unit">개소</span>
          </div>
          {periodLabel && <div className="stat__delta">{periodLabel} 기준</div>}
        </div>
      </div>

      <div className="card stat">
        <div className="stat__icon stat__icon--orange">
          <Icon name="car" size={28} />
        </div>
        <div>
          <div className="stat__label">{periodLabel ? `${periodLabel} 사고` : '사고'}</div>
          <div className="stat__value">
            {/*
             * 사고이력 표가 비어 `acdntCnt` 가 null 이어도 **0** 으로 그린다("값 없으면 0").
             * API 의 null 은 `warnings.ACDNT_NO_DATA` 판정 근거로 남아 있다. 조회 전·실패는 다른 카드와 같은 `blank`.
             */}
            {smry ? fmtCnt(smry.acdntCnt ?? 0) : blank}
            <span className="stat__unit">건</span>
          </div>
          {acdntNote && <div className="stat__delta">{acdntNote}</div>}
        </div>
      </div>
    </div>
  )
}
