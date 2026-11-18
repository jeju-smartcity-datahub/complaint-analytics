import { useState, type CSSProperties } from 'react'
import Icon from '../Icon'
import type { RegionItem } from '../../api/regions'
import { RANGE_ERROR_TEXT, type RangeError } from '../../lib/dateRange'
import MonthRangeField from '../common/MonthRangeField'
import SrLabel from '../common/SrLabel'
import CoefficientPopover, { CoefficientBanner } from './CoefficientPopover'
import type {
  AnalysisCoefficient,
  CoeffRequest,
} from '../../lib/analysis/useAnalysisCoefficient'

/*
 * 불법 주차 집중 구역 분석 — 좌상단 설정 패널 (TSK-4032 Step 11).
 *
 * 마크업·클래스는 시안(https://innopamsolution.github.io/jeju-datahub/)의
 * `.card.sim-panel.sim-panel--left` 를 그대로 옮겼고, 스타일은 Step 9 의
 * `src/styles/analysis.css` 가 이미 갖고 있다 — 여기서 CSS 를 새로 만들지 않는다.
 *
 * 이 컴포넌트는 **완전한 controlled 프레젠테이션 컴포넌트**다. 조회 상태(idle/loading/done)와
 * 실제 API 호출은 전부 `HotspotPage` 가 들고 있고, 여기서는 값을 그리고 이벤트를 올려 보내기만 한다
 * — 설정 패널이 스스로 조회하면 D10(진입 직후 자동 조회 금지)을 지키기 어렵다.
 * 분석 계수 조정(TSK-4306 → TSK-4325)의 초안·적용·실행값은 `useAnalysisCoefficient` 훅이 들고(페이지 소유),
 * 여기서는 팝오버 열림 여부만 패널 z-index 용으로 둔다.
 *
 * 시안과 의도적으로 다른 부분
 *   - 지역 select 을 **`GET /api/regions`** 로 채운다(시안의 하드코딩 목록을 옮기지 않는다 — D13)
 *   - `실행` 중에는 버튼을 비활성 + `분석 중…` 으로 바꾼다(시안은 정적 목데이터라 대기 상태가 없다)
 *   - 실패 문구 · 결과 요약 문구를 패널 하단에 인라인으로 띄운다
 *   - 🔁 TSK-4321 — 시안 `분석 계수` 절의 월기준값 2개는 삭제됐다(점수가 조회 결과 1위 = 100 인 상대값).
 *     🔁 TSK-4325 — 그 자리에 등급 비율 `GRD_BNDRY1~3` 이 들어가 버튼 이름이 다시 `분석 계수 조정` 이다
 */

/** 분석 기간 세그먼트 키. 숫자 키는 `최근 N개월`, `custom` 만 from~to 입력을 노출한다. */
export type HotspotPeriodKey = '1' | '3' | '6' | 'custom'

/**
 * 첫 진입 기간 — **`최근 1개월`**(D11).
 * 오늘 기준이라 지금 데이터(2025-11-04~12-31)로는 0건이 뜬다 — 그 상태를 정식 화면으로 안내한다.
 */
export const DEFAULT_PERIOD: HotspotPeriodKey = '1'

/** 기간 세그먼트. 라벨은 시안 그대로 붙여 쓴다(4열 격자라 공백을 넣으면 줄이 넘친다). */
const PERIODS: { key: HotspotPeriodKey; label: string }[] = [
  { key: '1', label: '최근1개월' },
  { key: '3', label: '최근3개월' },
  { key: '6', label: '최근6개월' },
  { key: 'custom', label: '직접설정' },
]

/*
 * 패널 하단 인라인 문구.
 * 전용 클래스를 새로 만들지 않고 `.field__label`(layout.css) 위에 색만 얹는다 —
 * analysis.css 는 Step 9 산출물이라 이 단계에서 건드리지 않는다.
 * `<p>` 기본 여백은 `.sim-panel` 의 `gap` 과 겹치므로 margin 을 0 으로 눌러 둔다.
 */
const NOTE: CSSProperties = { margin: 0 }
const NOTE_ERROR: CSSProperties = { ...NOTE, color: 'var(--status-negative)' }
/** `.btn-run` 에는 `:disabled` 규칙이 없어(시안에 대기 상태가 없었다) 최소한의 시각 피드백만 얹는다. */
const RUN_DISABLED: CSSProperties = { opacity: 0.6, cursor: 'not-allowed' }

/* ── 분석 계수 조정 (TSK-4306 → TSK-4325) — 버튼·팝오버·배너는 공통 `CoefficientPopover` 가 그린다 ── */
const TITLE_ROW: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
}
/** 배너 자리 — 패널 `gap` 을 당겨 제목 줄에 붙인다(TSK-4306 과 같은 여백). */
const COEFF_BANNER: CSSProperties = { margin: '-12px 0 0' }

interface HotspotSettingPanelProps {
  /** 지역 목록 조회 실패 여부. true 면 select 대신 안내와 재시도 버튼을 보여준다. */
  regionsFailed?: boolean
  /** 지역 목록 다시 불러오기. */
  onRetryRegions?: () => void
  /** 시군구 → 읍면동 트리 (`GET /api/regions`). 아직 못 받았으면 `[]` — select 은 `전체` 만 남는다. */
  regions: RegionItem[]
  /** 선택된 시군구명. `''` = 전체. */
  sggNm: string
  /** 선택된 읍면동코드. `''` = 시군구 전체. */
  emdCd: string
  onSggChange: (next: string) => void
  onEmdChange: (next: string) => void
  /** 선택된 기간 세그먼트. */
  period: HotspotPeriodKey
  onPeriodChange: (next: HotspotPeriodKey) => void
  /** 직접설정 시작일 (yyyy-MM-dd). 월로 고르면 그 달 1일이다. `period !== 'custom'` 이면 입력이 숨겨진다. */
  customFrom: string
  /** 직접설정 종료일 (yyyy-MM-dd). 월로 고르면 그 달 말일이다. */
  customTo: string
  onCustomFromChange: (next: string) => void
  onCustomToChange: (next: string) => void
  /** 직접설정 기간 검증 결과(빈 값·역전·최대 1년 초과). null 이 아니면 서버가 400 을 내므로 실행을 막는다. */
  rangeError: RangeError | null
  /** 조회 중 여부(`status === 'loading'`). */
  running: boolean
  onRun: () => void
  /** 조회 실패 문구. 실패해도 위 입력값은 그대로 둔다. */
  error?: string | null
  /**
   * 조회 결과 안내(0건 안내 / `N건 조회됨`).
   * ⚠️ **Step 13 의 결과 패널이 들어오면 이 자리는 사라진다** — 지금은 결과가 어딘가 보여야 하므로 임시로 둔다.
   */
  resultNote?: string | null
  /**
   * 분석 계수 상태(`useAnalysisCoefficient('HOTSPOT')`). 설정 조회 실패·가중치 지표 꺼짐이면 버튼이 숨는다
   * (TSK-4306 D3·D6 · TSK-4325 D16).
   */
  coeff: AnalysisCoefficient
  /** `적용` — 확정된 요청으로 재분석한다(D6). 팝오버 닫기는 공통 컴포넌트가 한다. */
  onApplyCoeff: (req: CoeffRequest) => void
  /** 배너 `초기화` — 기본값으로 되돌린다(TSK-4306 D5). */
  onResetCoeff: () => void
}

export default function HotspotSettingPanel({
  regions,
  regionsFailed = false,
  onRetryRegions,
  sggNm,
  emdCd,
  onSggChange,
  onEmdChange,
  period,
  onPeriodChange,
  customFrom,
  customTo,
  onCustomFromChange,
  onCustomToChange,
  rangeError,
  running,
  onRun,
  error,
  resultNote,
  coeff,
  onApplyCoeff,
  onResetCoeff,
}: HotspotSettingPanelProps) {
  // 시군구를 고르지 않으면 읍면동 목록이 없다 — select 자체를 비활성으로 둔다.
  const emds = regions.find((r) => r.sggNm === sggNm)?.emds ?? []
  const runDisabled = running || !!rangeError

  // 팝오버가 열리면 패널을 결과 패널 위로 올린다(팝오버가 패널 오른쪽 밖으로 나간다).
  const [coeffOpen, setCoeffOpen] = useState(false)

  return (
    <div className="card sim-panel sim-panel--left" style={{ zIndex: coeffOpen ? 1200 : undefined }}>
      <div style={TITLE_ROW}>
        <h2 className="sim-panel__title" style={{ margin: 0, fontSize: 18, whiteSpace: 'nowrap' }}>
          불법 주차 집중 구역 분석 설정
        </h2>

        <CoefficientPopover
          coeff={coeff}
          scope="HOTSPOT"
          disabled={running}
          onApply={onApplyCoeff}
          onOpenChange={setCoeffOpen}
        />
      </div>

      <CoefficientBanner
        banner={coeff.banner('HOTSPOT')}
        onReset={onResetCoeff}
        disabled={running}
        style={COEFF_BANNER}
      />

      <div className="field">
        <span className="field__label">분석 지역</span>
        <div className="field__region-row">
          <div className="field__select-wrap">
            <SrLabel text="시군구">
              <select
                className="field__select-native"
                aria-label="시군구"
                value={sggNm}
                onChange={(e) => onSggChange(e.target.value)}
              >
                <option value="">제주도 전체</option>
                {regions.map((r) => (
                  <option key={r.sggNm} value={r.sggNm}>
                    {r.sggNm}
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
                value={emdCd}
                disabled={!sggNm}
                onChange={(e) => onEmdChange(e.target.value)}
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

        {/*
          지역 목록을 못 받으면 select 에 `전체` 만 남아 "지역 설정이 안 된다" 로 보인다.
          실제 원인은 대개 백엔드 미기동이므로 원인과 조치를 함께 알려 준다.
        */}
        {regionsFailed && (
          <p
            className="field__label"
            style={{ color: 'var(--status-negative)', fontWeight: 'var(--weight-regular)' }}
          >
            지역 목록을 불러오지 못했습니다. 백엔드가 실행 중인지 확인해 주세요.{' '}
            {onRetryRegions && (
              <button
                type="button"
                onClick={onRetryRegions}
                style={{
                  background: 'none',
                  border: 'none',
                  padding: 0,
                  color: 'var(--primary)',
                  cursor: 'pointer',
                  textDecoration: 'underline',
                  font: 'inherit',
                }}
              >
                다시 시도
              </button>
            )}
          </p>
        )}
      </div>

      <div className="field">
        <span className="field__label">분석 기간</span>
        <div className="grid-seg">
          {PERIODS.map((p) => (
            <button
              key={p.key}
              type="button"
              aria-pressed={period === p.key}
              className={period === p.key ? 'is-active' : undefined}
              onClick={() => onPeriodChange(p.key)}
            >
              {p.label}
            </button>
          ))}
        </div>

        {/* 직접설정일 때만 월 선택 칸이 열린다. */}
        {period === 'custom' && (
          <MonthRangeField
            from={customFrom}
            to={customTo}
            onFromChange={onCustomFromChange}
            onToChange={onCustomToChange}
          />
        )}
      </div>

      {/* 이쪽은 서버가 400 을 내는 조건이라 버튼에서 먼저 끊는다. 문구는 5화면 공통이다. */}
      {rangeError && (
        <p className="field__label" role="alert" style={NOTE_ERROR}>
          {RANGE_ERROR_TEXT[rangeError]}
        </p>
      )}

      <button
        type="button"
        className="btn-run"
        disabled={runDisabled}
        style={runDisabled ? RUN_DISABLED : undefined}
        onClick={onRun}
      >
        {running ? '분석 중…' : '▷ 집중 구역 분석 실행'}
      </button>

      {error && (
        <p className="field__label" role="alert" style={NOTE_ERROR}>
          {error}
        </p>
      )}

      {resultNote && (
        <p className="field__label" style={NOTE}>
          {resultNote}
        </p>
      )}
    </div>
  )
}
