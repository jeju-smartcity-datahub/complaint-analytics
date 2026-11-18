import Icon from '../Icon'
import type { CommonCodeItem } from '../../api/commonCodes'
import { SRC_ALL, type ManualReportFilter } from './manualReportFilter'
import SrLabel from '../common/SrLabel'

/*
 * 보고서 목록 — 직접 생성 탭 필터 툴바.
 *
 * 마크업·클래스·문구는 시안(번들 `n === 'manual'` 분기)을 그대로 옮겼다:
 *   div.rpt-toolbar > div.rpt-search · span.filterbar__label "출처" · div.ds-select > select ·
 *   span.filterbar__label "생성일" · input.rpt-date ~ input.rpt-date · button.btn "초기화"
 *
 * 🔴 시안과 다른 점 — **필터는 서버가 건다**. 이 컴포넌트는 입력 원본만 다루고,
 *    디바운스·페이지 되돌림·조회는 `ReportsPage` 가 한다.
 */

interface ManualReportToolbarProps {
  value: ManualReportFilter
  onChange: (next: ManualReportFilter) => void
  /** 출처 옵션(`DASHBOARD` 제외). 조회 실패면 빈 배열이라 `전체` 만 남는다. */
  srcOptions: CommonCodeItem[]
  onReset: () => void
}

export default function ManualReportToolbar({
  value,
  onChange,
  srcOptions,
  onReset,
}: ManualReportToolbarProps) {
  // 시안 조건 그대로 — 하나라도 걸려 있으면 `초기화` 를 보인다.
  const hasFilter =
    value.keyword.trim() !== '' || value.anlsTypeCd !== SRC_ALL || value.fromYmd !== '' || value.toYmd !== ''

  return (
    <div className="rpt-toolbar">
      <div className="rpt-search">
        <Icon name="search" size={16} />
        <SrLabel text="보고서명 검색">
          <input
            type="text"
            placeholder="보고서명 검색"
            aria-label="보고서명 검색"
            // 서버 상한(trim 후 100자 — 초과면 400)과 맞춘다
            maxLength={100}
            value={value.keyword}
            onChange={(e) => onChange({ ...value, keyword: e.target.value })}
          />
        </SrLabel>
      </div>

      <span className="filterbar__label">출처</span>
      <div className="ds-select">
        <SrLabel text="출처">
          <select
            aria-label="출처"
            value={value.anlsTypeCd}
            onChange={(e) => onChange({ ...value, anlsTypeCd: e.target.value })}
          >
            <option value={SRC_ALL}>전체</option>
            {srcOptions.map((o) => (
              <option key={o.cdVl} value={o.cdVl}>
                {o.cdNm}
              </option>
            ))}
          </select>
        </SrLabel>
        <span className="ds-select__ic">
          <Icon name="chevron-down" size={18} />
        </span>
      </div>

      <span className="filterbar__label">생성일</span>
      {/* 시작·종료가 서로의 min/max 를 건다 — 역전을 화면에서 먼저 막는다(서버 400 은 방어선). */}
      <SrLabel text="생성일 시작">
        <input
          type="date"
          className="rpt-date"
          aria-label="생성일 시작"
          value={value.fromYmd}
          max={value.toYmd || undefined}
          onChange={(e) => onChange({ ...value, fromYmd: e.target.value })}
        />
      </SrLabel>
      <span className="rpt-toolbar__tilde">~</span>
      <SrLabel text="생성일 종료">
        <input
          type="date"
          className="rpt-date"
          aria-label="생성일 종료"
          value={value.toYmd}
          min={value.fromYmd || undefined}
          onChange={(e) => onChange({ ...value, toYmd: e.target.value })}
        />
      </SrLabel>

      {hasFilter && (
        <button type="button" className="btn" style={{ height: 40 }} onClick={onReset}>
          초기화
        </button>
      )}
    </div>
  )
}
