import { useState, type CSSProperties } from 'react'
import Icon from '../Icon'
import type { RegionItem } from '../../api/regions'
import {
  type LifezoneGridSz,
  type LifezoneTargetCd,
  type LifezoneTargetInfo,
} from '../../api/lifezone'
import {
  MAX_RANGE_DAYS,
  RANGE_ERROR_TEXT,
  rangeDays,
  toYmd,
  type RangeError,
} from '../../lib/dateRange'
import { openDatePicker } from '../../lib/datePicker'
import SrLabel from '../common/SrLabel'
import CoefficientPopover, { CoefficientBanner } from '../analysis/CoefficientPopover'
import type { CoeffScope } from '../../lib/analysis/coefficientMeta'
import type {
  AnalysisCoefficient,
  CoeffRequest,
} from '../../lib/analysis/useAnalysisCoefficient'

/*
 * 생활권 시뮬레이션 — 좌상단 설정 패널 (TSK-4088 Step 8).
 *
 * 마크업·클래스는 시안(https://innopamsolution.github.io/jeju-datahub/)의
 * `.card.sim-panel.sim-panel--left` 를 그대로 옮겼고, 스타일은 `src/styles/analysis.css`
 * (TSK-4032 Step 9 + TSK-4088 Step 6)가 이미 갖고 있다 — 여기서 CSS 를 새로 만들지 않는다.
 *
 * 🔴 **`HotspotSettingPanel` 을 재사용하지 않는다.** 필드 구성이 다르다 —
 * 생활권은 `분석 유형`(배타 선택)·`격자 크기` 두 필드가 더 있고, 반대로 집중구역의
 * `짧은 기간 안내`(종합점수에 기간 보정이 없어 붙은 안내, TSK-4032 R4)는 없다.
 * 생활권은 등급 경계를 조회 결과에서 매번 다시 산출하므로(D17) 기간이 짧아도
 * 등급이 통째로 낮아지지 않는다 — 그 안내를 옮기면 근거 없는 경고가 된다.
 *
 * ⚠️ 지역 select · 기간 세그먼트 마크업이 `HotspotSettingPanel` 과 같은 모양이다.
 * 공용 하위 컴포넌트로 뽑지 않은 이유는 **집중구역 화면(검증 완료분)을 건드리지 않기 위해서**다 —
 * 추출은 두 화면이 다 자리 잡은 뒤 별도 작업으로 한다. 지역 목록의 출처(`GET /api/regions`)와
 * 클래스(`.field__region-row` 등)는 같으므로 스타일은 이미 공용이다.
 *
 * 이 컴포넌트는 **완전한 controlled 프레젠테이션 컴포넌트**다. 조회 상태(idle/loading/done)와
 * 실제 API 호출은 전부 `LifestyleSimulationPage` 가 들고 있고, 여기서는 값을 그리고 이벤트를
 * 올려 보내기만 한다 — 설정 패널이 스스로 조회하면 D1(진입 직후 자동 조회 금지)을 지키기 어렵다.
 *
 * 🆕 **분석 계수 조정(TSK-4325)** — 제목 줄 오른쪽 버튼 + 그 아래 배너(집중구역 패널과 같은 조립).
 * 팝오버는 `주차 수급`·`혼잡도` 세그먼트를 그리고 **현재 탭으로 열린다**(D13 — 값은 한 벌, 요청은 현재 탭 값만).
 * 🔴 `민원 건수` 탭에서는 버튼·배너를 그리지 않는다(D12 — 그 API 는 `vrblVls` 를 받지 않는다) — 페이지가
 * `coeffScope` 를 `null` 로 내린다.
 */

/** 분석 기간 세그먼트 키. 숫자 키는 `최근 N개월`, `custom` 만 from~to 입력을 노출한다. */
export type LifezonePeriodKey = '1' | '3' | '6' | 'custom'

/**
 * 첫 진입 기간 — **`최근 1개월`**.
 * 기준 시각은 고정 날짜가 아니라 **오늘**이다(구간 계산은 페이지의 `recentMonths()`가 한다) —
 * 시드가 재적재될 때마다 민원 보유 구간이 통째로 옮겨지므로 날짜를 박으면 곧 어긋난다.
 */
export const DEFAULT_PERIOD: LifezonePeriodKey = '1'

/**
 * 첫 진입 격자 크기 — **500m**(D3).
 * 1km 는 제주 전역이 20여 셀로 뭉개지고, 100m 는 18만 셀이라 첫 화면으로 무겁다.
 */
export const DEFAULT_GRID_SZ: LifezoneGridSz = 500

/**
 * 격자 크기 목록을 **서버 응답에서** 만든다 (TSK-4089).
 *
 * 🔁 지금은 민원건수·수급이 **같은 목록**(`1km/500m/250m/100m`)이다(TSK-4206 — 종전 수급은
 * `100/200/300/500m`). 그래도 목록은 유형별 `targets[].gridSzs` 가 정본이다 — 화면에 박아 두면
 * 해상도가 다른 유형이 생길 때 탭이 그대로 남아 **서버가 400** 을 낸다.
 *
 * ⚠️ 목록을 아직 못 받았을 때만 쓰는 폴백을 둔다(`fallbackGridSzs`) — 세그먼트가 통째로 비면
 * 격자를 고를 수 없어 실행 자체가 막힌다.
 */
const FALLBACK_GRID_SZS = [1000, 500, 250, 100]

/** 격자 크기 표시명. 조회 전에도 그려야 해서 프론트가 만든다(응답 `gridSzNm` 과 같은 표기다). */
function gridSzLabel(gridSz: number): string {
  return gridSz === 1000 ? '1km' : `${gridSz}m`
}

/** `YYYYMMDD` → `yyyy-MM-dd`. 보유 구간 안내와 `맞추기` 버튼이 쓴다. */
function dashYmd(ymd: string): string {
  return `${ymd.slice(0, 4)}-${ymd.slice(4, 6)}-${ymd.slice(6, 8)}`
}

/**
 * 조회 구간이 원천 보유 구간과 **한 날이라도 겹치는가**.
 *
 * 🔴 이 판정이 없으면 화면은 **실행한 뒤에야** 0건을 알린다. 기간 세그먼트는 **오늘** 기준이라
 * 보유 구간이 과거로 밀린 유형은 기본값이 그대로 0건이 되는데, 그 상태는 고장으로 읽힌다.
 * (🔁 2026-09-13 이전 수급이 정확히 그랬다 — `dclr_ymd` 기준이라 민원건수와 1년 어긋나 있었다.)
 *
 * 겹침만 본다(포함이 아니라) — 일부만 걸쳐도 결과가 나오므로 경고할 일이 아니다.
 */
function overlapsDataRange(
  from: string,
  to: string,
  dataBgngYmd: string | null,
  dataEndYmd: string | null,
): boolean {
  if (!dataBgngYmd || !dataEndYmd) return true // 구간을 모르면 막지 않는다(경고는 근거가 있을 때만)
  if (!from || !to) return true // 형식 오류는 rangeError 가 따로 잡는다
  return from.replace(/-/g, '') <= dataEndYmd && to.replace(/-/g, '') >= dataBgngYmd
}

/**
 * 원천 보유 구간을 **조회 가능한 최대 길이**(`MAX_RANGE_DAYS`)로 자른다 — 종료일 기준 (TSK-4090).
 *
 * 🔴 혼잡도(단속 보유 `20250201~20261030`, 637일)처럼 보유 구간이 1년을 넘으면 그대로 맞출 때
 * `TOO_LONG` 에 걸려 실행이 막힌다. 최신 데이터를 남기도록 **종료일을 고정하고 시작일을 당긴다**.
 * 366일 이하면 그대로 돌려준다(민원건수·수급 동작 불변). 입력·출력은 `yyyy-MM-dd`.
 */
function clipToMaxRange(from: string, to: string): { from: string; to: string; clipped: boolean } {
  if (rangeDays(from, to) <= MAX_RANGE_DAYS) return { from, to, clipped: false }
  const [y, m, d] = to.split('-').map(Number)
  const start = new Date(y, m - 1, d)
  start.setDate(start.getDate() - (MAX_RANGE_DAYS - 1))
  return { from: toYmd(start), to, clipped: true }
}

/** 기간 세그먼트. 라벨은 시안 그대로 붙여 쓴다(4열 격자라 공백을 넣으면 줄이 넘친다). */
const PERIODS: { key: LifezonePeriodKey; label: string }[] = [
  { key: '1', label: '최근1개월' },
  { key: '3', label: '최근3개월' },
  { key: '6', label: '최근6개월' },
  { key: 'custom', label: '직접설정' },
]

/*
 * 패널 하단 인라인 문구. 전용 클래스를 새로 만들지 않고 `.field__label`(layout.css) 위에 색만 얹는다
 * — `analysis.css` 는 Step 6 에서 닫았고 이 단계에서 다시 건드리지 않는다.
 */
const NOTE_ERROR: CSSProperties = { margin: 0, color: 'var(--status-negative)' }

/**
 * 보유 구간 밖 안내 — 🔴 **오류색(빨강)을 쓰지 않는다.** 서버가 거절하는 상황이 아니라
 * 정상 200 + 0건이라, 빨강으로 칠하면 "잘못된 입력" 으로 읽혀 사용자가 기간을 고치는 대신
 * 화면을 의심한다. 주의색으로 "이대로 돌리면 빈 결과" 만 알린다.
 */
const NOTE_WARN: CSSProperties = {
  margin: 0,
  color: 'var(--status-cautionary)',
  fontWeight: 'var(--weight-regular)',
  lineHeight: 1.6,
}

/** 문단 안에 끼는 인라인 버튼(지역·유형 목록 재시도 링크와 같은 모양). */
const LINK_BTN: CSSProperties = {
  background: 'none',
  border: 'none',
  padding: 0,
  color: 'var(--primary)',
  cursor: 'pointer',
  textDecoration: 'underline',
  font: 'inherit',
}
/** `.btn-run` 에는 `:disabled` 규칙이 없어(시안에 대기 상태가 없었다) 최소한의 시각 피드백만 얹는다. */
const RUN_DISABLED: CSSProperties = { opacity: 0.6, cursor: 'not-allowed' }

/**
 * `시작일 + (MAX_RANGE_DAYS - 1)일` = 선택 가능한 마지막 종료일 (`DateRangePopover`·집중구역 패널과 같은 규칙).
 * 🔴 미래 날짜는 막지 않는다 — 상한은 "최대 1년" 뿐이다.
 */
function maxTo(from: string): string | undefined {
  if (!from) return undefined
  const [y, m, d] = from.split('-').map(Number)
  const last = new Date(y, m - 1, d)
  last.setDate(last.getDate() + (MAX_RANGE_DAYS - 1))
  return toYmd(last)
}
/** `.segment__btn` 에도 `:disabled` 규칙이 없다 — 미구현 유형이 눌리지 않는다는 것을 색으로 알린다. */
const SEG_DISABLED: CSSProperties = { opacity: 0.45, cursor: 'not-allowed' }
/** 미구현 버튼의 툴팁 래퍼. 클래스를 새로 만들지 않으려 인라인으로 둔다(아래 주석 참고). */
const SEG_SLOT: CSSProperties = { display: 'inline-flex' }
/** `unavailableRsn` 이 비어 온 경우의 최후 문구. 정상 응답이면 서버 사유가 그대로 쓰인다. */
const UNAVAILABLE_FALLBACK = '아직 제공되지 않는 분석 유형입니다.'

/* ── 분석 계수 조정 (TSK-4325) — 집중구역 `HotspotSettingPanel` 과 같은 자리·여백 ── */
const TITLE_ROW: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
}
/** 배너 자리 — 패널 `gap` 을 당겨 제목 줄에 붙인다. */
const COEFF_BANNER: CSSProperties = { margin: '-12px 0 0' }

interface LifezoneSettingPanelProps {
  /**
   * 분석유형 목록 (`GET /api/analysis/lifezone/targets`). 아직 못 받았으면 `[]`.
   * 🔴 **가용 여부·사유를 프론트가 판단하지 않는다** — `available`/`unavailableRsn` 을 그대로 그린다.
   */
  targets: LifezoneTargetInfo[]
  /** 분석유형 목록 조회 실패 여부. 실패해도 조회 자체는 막지 않는다(서버 기본 유형으로 돈다). */
  targetsFailed?: boolean
  /** 분석유형 목록 다시 불러오기. */
  onRetryTargets?: () => void
  /** 선택된 분석내용코드. `''` = 아직 목록을 못 받아 서버 기본값(`CVLCPT_CNT`)에 맡기는 상태. */
  anlsTrgtCd: LifezoneTargetCd | ''
  onTargetChange: (next: LifezoneTargetCd) => void

  /** 지역 목록 조회 실패 여부. true 면 select 아래에 안내와 재시도 버튼을 보여준다. */
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
  period: LifezonePeriodKey
  onPeriodChange: (next: LifezonePeriodKey) => void
  /** 직접설정 시작일 (yyyy-MM-dd). `period !== 'custom'` 이면 입력이 숨겨진다. */
  customFrom: string
  /** 직접설정 종료일 (yyyy-MM-dd). */
  customTo: string
  onCustomFromChange: (next: string) => void
  onCustomToChange: (next: string) => void

  /**
   * 선택된 격자 크기(m).
   *
   * 유효한 값인지는 선택된 유형의 `gridSzs` 가 정하며, 어긋나면 서버가 400 으로 끊는다.
   * (🔁 TSK-4206 으로 수급도 민원건수 4종과 같아졌지만, 목록의 정본은 여전히 서버다.)
   */
  gridSz: number
  onGridSzChange: (next: number) => void

  /** 현재 조회 구간 (`yyyy-MM-dd`). 보유 구간과 겹치는지 판정하는 데 쓴다 (TSK-4089). */
  rangeFrom: string
  rangeTo: string
  /**
   * 기간을 선택 유형의 원천 보유 구간으로 맞춘다 (TSK-4089).
   *
   * 🔴 **자동으로 바꾸지 않고 버튼으로 둔다** — 사용자가 고른 기간을 말없이 덮으면 "내가 고른 게
   * 아닌 결과" 가 되고, 왜 바뀌었는지 알 길이 없다. 경고로 알리고 한 번의 클릭으로 맞춰 준다.
   */
  onUseDataRange: (from: string, to: string) => void

  /** 직접설정 기간 검증 결과(빈 값·역전·최대 1년 초과). null 이 아니면 서버가 400 을 내므로 실행을 막는다. */
  rangeError: RangeError | null
  /** 조회 중 여부(`status === 'loading'`). */
  running: boolean
  onRun: () => void
  /** 조회 실패 문구. 실패해도 위 입력값은 그대로 둔다. */
  error?: string | null

  /** 🆕 분석 계수 상태(`useAnalysisCoefficient('LIFEZONE')`). 조회 실패·그릴 행 0개면 버튼이 숨는다(D16). */
  coeff: AnalysisCoefficient
  /**
   * 🆕 현재 탭의 스코프 — 수급 `LIFEZONE_SPLY` · 혼잡도 `LIFEZONE_CNGST`. 🔴 `null` 이면(민원 건수 탭) 버튼·배너를
   * 그리지 않는다(D12).
   */
  coeffScope: CoeffScope | null
  /** `적용` — 확정된 현재 탭 요청. 결과가 있으면 페이지가 재분석한다(D6). */
  onApplyCoeff: (req: CoeffRequest) => void
  /** 배너 `초기화`. */
  onResetCoeff: () => void
}

export default function LifezoneSettingPanel({
  targets,
  targetsFailed = false,
  onRetryTargets,
  anlsTrgtCd,
  onTargetChange,
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
  gridSz,
  onGridSzChange,
  rangeFrom,
  rangeTo,
  onUseDataRange,
  rangeError,
  running,
  onRun,
  error,
  coeff,
  coeffScope,
  onApplyCoeff,
  onResetCoeff,
}: LifezoneSettingPanelProps) {
  // 팝오버가 열리면 패널을 결과 패널 위로 올린다(팝오버가 패널 오른쪽 밖으로 나간다).
  const [coeffOpen, setCoeffOpen] = useState(false)

  // 시군구를 고르지 않으면 읍면동 목록이 없다 — select 자체를 비활성으로 둔다.
  const emds = regions.find((r) => r.sggNm === sggNm)?.emds ?? []
  /**
   * 목록은 받았는데 가용 유형이 하나도 없는 상태.
   * 지금은 `민원 건수` 하나가 살아 있어 발생하지 않지만, 원천이 통째로 비면 실행이 곧 400 이라 미리 막는다.
   */
  const noTargetAvailable = targets.length > 0 && !targets.some((t) => t.available)
  const runDisabled = running || !!rangeError || noTargetAvailable

  /** 선택된 분석 유형. 격자 크기 목록이 여기서 갈린다 (TSK-4089). */
  const selectedTarget = targets.find((t) => t.anlsTrgtCd === anlsTrgtCd)
  /** 이 유형이 지원하는 격자 크기. 목록을 아직 못 받았으면 폴백으로 세그먼트를 비우지 않는다. */
  const gridSzs = selectedTarget?.gridSzs ?? FALLBACK_GRID_SZS

  /**
   * 🔴 선택한 기간에 이 유형의 데이터가 **한 건도 없는** 상태인지 (TSK-4089).
   *
   * 기간 세그먼트는 **오늘** 기준이라 보유 구간이 과거로 밀린 유형은 `최근 1/3/6개월` 로 닿지
   * 않는다. 실행하면 전부 0 이 나오는데, 그것을 돌려 본 뒤에야 알게 두면 화면이 고장 난 것으로
   * 읽힌다. (🔁 2026-09-13 이전 수급이 그랬다 — 지금은 두 유형이 같은 컬럼을 쓴다.)
   */
  const dataBgngYmd = selectedTarget?.dataBgngYmd ?? null
  const dataEndYmd = selectedTarget?.dataEndYmd ?? null
  const outOfDataRange =
    !rangeError && !overlapsDataRange(rangeFrom, rangeTo, dataBgngYmd, dataEndYmd)
  /** `이 구간으로 맞추기` 가 실제로 넣을 구간 — 1년을 넘으면 종료일 기준으로 자른다(TSK-4090). */
  const fitRange =
    dataBgngYmd && dataEndYmd ? clipToMaxRange(dashYmd(dataBgngYmd), dashYmd(dataEndYmd)) : null

  return (
    <div className="card sim-panel sim-panel--left" style={{ zIndex: coeffOpen ? 1200 : undefined }}>
      <div style={TITLE_ROW}>
        <h2 className="sim-panel__title" style={{ margin: 0, whiteSpace: 'nowrap' }}>
          생활권 분석 설정
        </h2>

        {/* 🔴 민원 건수 탭에는 없다(D12). 세그먼트는 현재 탭으로 열린다(D13). */}
        {coeffScope && (
          <CoefficientPopover
            coeff={coeff}
            scope={coeffScope}
            segments
            disabled={running}
            onApply={onApplyCoeff}
            onOpenChange={setCoeffOpen}
          />
        )}
      </div>

      {coeffScope && (
        <CoefficientBanner
          banner={coeff.banner(coeffScope)}
          onReset={onResetCoeff}
          disabled={running}
          style={COEFF_BANNER}
        />
      )}

      {/*
       * 분석 유형 — 배타 선택(라디오 성격)이라 `.segment` 를 쓴다.
       * 🔴 버튼 목록·라벨·비활성 사유가 **전부 서버 응답**에서 온다(D2). 유형명을 프론트에 박으면
       * 원천이 붙는 날 화면도 함께 고쳐야 한다.
       */}
      <div className="field">
        <span className="field__label">분석 유형</span>
        <div className="segment" role="group" aria-label="분석 유형">
          {targets.map((t) => {
            const rsn = t.available ? undefined : (t.unavailableRsn ?? UNAVAILABLE_FALLBACK)
            /*
             * ⚠️ `disabled` 버튼은 마우스 이벤트를 받지 않아 브라우저에 따라 `title` 툴팁이 뜨지 않는다.
             * 그래서 사유를 **감싼 span 에도 함께** 건다 — 버튼의 `title` 은 계획서(D2)대로 그대로 두고,
             * 툴팁이 확실히 뜨게만 보강한 것이다. 가용 유형은 span 에 아무 속성도 붙지 않는다.
             */
            return (
              <span key={t.anlsTrgtCd} title={rsn} style={SEG_SLOT}>
                <button
                  type="button"
                  className={`segment__btn${t.anlsTrgtCd === anlsTrgtCd ? ' segment__btn--active' : ''}`}
                  aria-pressed={t.anlsTrgtCd === anlsTrgtCd}
                  disabled={!t.available}
                  title={rsn}
                  style={t.available ? undefined : SEG_DISABLED}
                  onClick={() => onTargetChange(t.anlsTrgtCd)}
                >
                  {t.anlsTrgtNm}
                </button>
              </span>
            )
          })}
        </div>

        {/*
          목록을 못 받으면 세그먼트가 통째로 비어 "유형을 고를 수 없다" 로 보인다.
          원인은 대개 백엔드 미기동이므로 원인과 조치를 함께 알려 준다(지역 목록과 같은 처리).
        */}
        {targetsFailed && (
          <p
            className="field__label"
            style={{ color: 'var(--status-negative)', fontWeight: 'var(--weight-regular)' }}
          >
            분석 유형 목록을 불러오지 못했습니다. 백엔드가 실행 중인지 확인해 주세요.{' '}
            {onRetryTargets && (
              <button
                type="button"
                onClick={onRetryTargets}
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

        {/* 직접설정일 때만 날짜 칸이 열린다. 네이티브 date 입력을 투명하게 덮어 칸 전체가 트리거다. */}
        {period === 'custom' && (
          <div className="field__dates">
            <label className="field__date">
              <span>{customFrom || '시작일'}</span>
              <Icon name="calendar" size={16} />
              <input
                className="field__date-input"
                type="date"
                onClick={openDatePicker}
                aria-label="분석 시작일"
                value={customFrom}
                max={customTo || undefined}
                onChange={(e) => onCustomFromChange(e.target.value)}
              />
            </label>
            <span className="field__tilde">~</span>
            <label className="field__date">
              <span>{customTo || '종료일'}</span>
              <Icon name="calendar" size={16} />
              <input
                className="field__date-input"
                type="date"
                onClick={openDatePicker}
                aria-label="분석 종료일"
                value={customTo}
                min={customFrom || undefined}
                max={maxTo(customFrom)}
                onChange={(e) => onCustomToChange(e.target.value)}
              />
            </label>
          </div>
        )}
      </div>

      {/*
       * 격자 크기 — 🔴 목록이 **선택된 분석 유형**에서 온다(TSK-4089). 유형을 바꾸면 탭도 함께
       * 갈린다. 🔁 TSK-4206 부터 민원건수·수급 모두 `1km/500m/250m/100m` 다.
       */}
      <div className="field">
        <span className="field__label">격자 크기</span>
        <div className="grid-seg">
          {gridSzs.map((g) => (
            <button
              key={g}
              type="button"
              aria-pressed={gridSz === g}
              className={gridSz === g ? 'is-active' : undefined}
              onClick={() => onGridSzChange(g)}
            >
              {gridSzLabel(g)}
            </button>
          ))}
        </div>
      </div>

      {/* 서버가 400 을 내는 조건이라 버튼에서 먼저 끊는다. 문구는 5화면 공통이다. */}
      {/*
       * 🔴 **실행 전에** 알린다 (TSK-4089). 서버가 400 을 내는 상황이 아니라 **정상 200 + 0건**
       * 이라 실행을 막지는 않는다 — 사용자가 일부러 빈 구간을 확인할 수도 있다. 대신 왜 비는지와
       * 어디를 보면 되는지를 함께 주고, 한 번의 클릭으로 맞출 수 있게 한다.
       */}
      {outOfDataRange && dataBgngYmd && dataEndYmd && fitRange && (
        <p className="field__label" role="status" style={NOTE_WARN}>
          선택한 기간에 <b>{selectedTarget?.anlsTrgtNm}</b> 데이터가 없습니다. 실행하면 결과가
          0건입니다.
          <br />
          원천 보유 구간: {dashYmd(dataBgngYmd)} ~ {dashYmd(dataEndYmd)}{' '}
          <button
            type="button"
            style={LINK_BTN}
            onClick={() => onUseDataRange(fitRange.from, fitRange.to)}
          >
            이 구간으로 맞추기
          </button>
          {/* 1년을 넘는 보유 구간은 말없이 자르지 않는다 — 실제로 넣을 구간을 함께 보인다. */}
          {fitRange.clipped && (
            <>
              <br />
              (조회는 최대 {MAX_RANGE_DAYS}일이라 종료일 기준 {fitRange.from} ~ {fitRange.to} 로
              맞춥니다)
            </>
          )}
        </p>
      )}

      {rangeError && (
        <p className="field__label" role="alert" style={NOTE_ERROR}>
          {RANGE_ERROR_TEXT[rangeError]}
        </p>
      )}

      {noTargetAvailable && (
        <p className="field__label" style={NOTE_ERROR}>
          현재 조회 가능한 분석 유형이 없습니다.
        </p>
      )}

      <button
        type="button"
        className="btn-run"
        disabled={runDisabled}
        style={runDisabled ? RUN_DISABLED : undefined}
        onClick={onRun}
      >
        {running ? '분석 중…' : '▷ 생활권 분석 실행'}
      </button>

      {error && (
        <p className="field__label" role="alert" style={NOTE_ERROR}>
          {error}
        </p>
      )}
    </div>
  )
}
