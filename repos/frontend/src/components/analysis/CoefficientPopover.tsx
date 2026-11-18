import { useEffect, useId, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import SrLabel from '../common/SrLabel'
import type { CoeffScope } from '../../lib/analysis/coefficientMeta'
import {
  formatCoeff,
  type AnalysisCoefficient,
  type CoeffBanner,
  type CoeffRequest,
  type CoeffRow,
} from '../../lib/analysis/useAnalysisCoefficient'

/*
 * 분석 계수 조정 — 버튼 + 팝오버 + 배너. 시안 인라인 스타일을 옮겼다(집중구역 팝오버와 같은 값).
 * 🔴 색은 `var(--primary)` — 시안의 `--primary-normal` 은 레포에 없다.
 * 🔴 슬라이더 레이블은 `SrLabel` 이다 — OpenWAX 는 `aria-label` 을 레이블로 보지 않는다.
 *
 * 상태는 `useAnalysisCoefficient` 가 들고 여기서는 그리기만 한다. 열림 여부·세그먼트만 여기 둔다.
 *   - 구역추천: 세그먼트 없이 현재 탭(`scope`) 계수만.
 *   - 생활권: `segments` — 화면 스코프 세그먼트를 그리고 현재 탭으로 열린다.
 *   - 투자: `placement="portal"` — `createPortal` + `position: fixed`.
 *   - 정책 시뮬레이션: `placement="below"`(버튼 아래 오른쪽 정렬) + `layout="policy"` —
 *     머리 세그먼트 대신 본문에 `공통` 절 → `정책 유형별` 세그먼트 → 그 유형 행(없으면 `note`).
 *     `layout` 은 **그리기만** 바꾼다 — `shown`·`syncDraft`·`apply`·`resetDraft` 는 `segments` 와 같다.
 */

const PRIMARY_TINT = 'rgba(59,112,247,0.08)'
const POPOVER_WIDTH_RIGHT = 320
const POPOVER_WIDTH_PORTAL = 340
const POPOVER_WIDTH_BELOW = 340

const TOGGLE: CSSProperties = {
  height: 30,
  padding: '0 10px',
  fontSize: 12,
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
}
const TOGGLE_OPEN: CSSProperties = {
  ...TOGGLE,
  borderColor: 'var(--primary)',
  color: 'var(--primary)',
  background: PRIMARY_TINT,
}
const POPOVER_BASE: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--card-bg)',
  border: '1px solid var(--line-normal)',
  borderRadius: 12,
  boxShadow: '0 12px 32px rgba(23,23,25,0.18)',
}
const POPOVER_RIGHT: CSSProperties = {
  ...POPOVER_BASE,
  position: 'absolute',
  left: 'calc(100% + 10px)',
  top: -8,
  zIndex: 1100,
  width: POPOVER_WIDTH_RIGHT,
  maxHeight: 460,
}
/** 정책 시뮬레이션 — 시안 `right:0; top: calc(100% + 8px)`. */
const POPOVER_BELOW: CSSProperties = {
  ...POPOVER_BASE,
  position: 'absolute',
  right: 0,
  top: 'calc(100% + 8px)',
  zIndex: 1100,
  width: POPOVER_WIDTH_BELOW,
  maxHeight: 460,
}
const POPOVER_PORTAL: CSSProperties = {
  ...POPOVER_BASE,
  position: 'fixed',
  zIndex: 3000,
  width: POPOVER_WIDTH_PORTAL,
  maxHeight: 'min(560px, calc(100vh - 24px))',
}
const TITLE: CSSProperties = {
  fontSize: 13,
  fontWeight: 700,
  color: 'var(--text-strong)',
  padding: '16px 16px 10px',
  flexShrink: 0,
}
const SEG_WRAP: CSSProperties = { display: 'flex', gap: 6, padding: '0 16px 10px', flexShrink: 0 }
const SEG_BTN: CSSProperties = {
  flex: 1,
  height: 28,
  fontSize: 12,
  borderRadius: 6,
  border: '1px solid var(--line-normal)',
  background: 'var(--fill-normal)',
  color: 'var(--text-neutral)',
  cursor: 'pointer',
}
const SEG_BTN_ON: CSSProperties = {
  ...SEG_BTN,
  borderColor: 'var(--primary)',
  background: PRIMARY_TINT,
  color: 'var(--primary)',
  fontWeight: 600,
}
const SECTION_TITLE: CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  color: 'var(--text-neutral)',
  marginBottom: 8,
}
const SECTION_TITLE_SPLIT: CSSProperties = {
  ...SECTION_TITLE,
  margin: '4px 0 8px',
  paddingTop: 12,
  borderTop: '1px solid var(--line-alternative)',
}
const NOTE: CSSProperties = {
  fontSize: 10.5,
  color: 'var(--text-alternative)',
  margin: '-6px 0 8px',
}
/** 정책 `정책 유형별` 세그먼트 버튼 — 공용 `.segment__btn` 위에 시안 크기만 덮는다. */
const POLICY_SEG_WRAP: CSSProperties = { marginBottom: 12 }
const POLICY_SEG_BTN: CSSProperties = { fontSize: 11, padding: '0 6px' }
const POLICY_NOTE: CSSProperties = {
  fontSize: 11,
  color: 'var(--text-alternative)',
  lineHeight: 1.5,
  marginBottom: 12,
}
const W_ROW: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  fontSize: 12,
  marginBottom: 8,
}
const W_AUTO_ROW: CSSProperties = { ...W_ROW, color: 'var(--text-alternative)' }
const W_LABEL: CSSProperties = { width: 64, flexShrink: 0 }
const W_VALUE: CSSProperties = { width: 40, textAlign: 'right', flexShrink: 0 }
const C_ROW: CSSProperties = { display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }
const C_VALUE: CSSProperties = { minWidth: 48, textAlign: 'right', whiteSpace: 'nowrap' }
const HINT: CSSProperties = { fontSize: 10.5, color: 'var(--text-alternative)', marginTop: 2 }
const RANGE: CSSProperties = { fontSize: 10.5, color: 'var(--text-assistive)', marginTop: 2 }
const ERRORS: CSSProperties = {
  margin: '0 0 12px',
  padding: 0,
  listStyle: 'none',
  fontSize: 11,
  color: 'var(--status-negative)',
}
const ACTIONS: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '12px 16px',
  borderTop: '1px solid var(--line-alternative)',
  background: 'var(--card-bg)',
  flexShrink: 0,
}
const DEFAULT_BTN: CSSProperties = {
  background: 'none',
  border: 'none',
  padding: 0,
  cursor: 'pointer',
  fontSize: 12,
  color: 'var(--text-alternative)',
  textDecoration: 'underline',
}
const APPLY_BTN: CSSProperties = {
  height: 28,
  padding: '0 14px',
  fontSize: 12,
  fontWeight: 600,
  border: 'none',
  borderRadius: 6,
  cursor: 'pointer',
  background: 'var(--primary)',
  color: 'var(--text-on-color)',
}
const DISABLED: CSSProperties = { opacity: 0.6, cursor: 'not-allowed' }

function unitText(row: CoeffRow): string {
  return row.unitNm ? ` ${row.unitNm}` : ''
}

export interface CoefficientPopoverProps {
  coeff: AnalysisCoefficient
  /** 화면의 현재 탭 스코프. `적용` 시 이 스코프의 요청이 `onApply` 로 간다. */
  scope: CoeffScope
  /** 화면 스코프 세그먼트를 그린다. 기본 false — 현재 탭 계수만. */
  segments?: boolean
  /**
   * `right`(기본) = 버튼 오른쪽 absolute · `portal` = body 에 fixed(투자) ·
   * `below` = 버튼 아래 오른쪽 정렬 absolute, 너비 340(정책 시뮬레이션).
   */
  placement?: 'right' | 'portal' | 'below'
  /**
   * `policy` = 공통 절 + `정책 유형별` 세그먼트 + 유형 행(없으면 `note`)으로 그린다.
   * `segments` 와 함께 넘긴다. 기본 `default` — 종전 그리기.
   */
  layout?: 'default' | 'policy'
  /** 실행 중 — `적용` 을 막는다. */
  disabled?: boolean
  /** `적용` — 값이 확정된 뒤 현재 탭 요청을 넘긴다. 재분석 여부는 화면이 정한다. */
  onApply: (req: CoeffRequest) => void
  /** 열림 변화 — 패널 z-index 를 올릴 때 쓴다. */
  onOpenChange?: (open: boolean) => void
  /** 버튼·팝오버 제목. 기본 `분석 계수 조정`. */
  title?: string
}

/**
 * 버튼 + 팝오버. 그릴 행이 없거나 설정 조회가 실패하면 아무것도 그리지 않는다.
 */
export default function CoefficientPopover({
  coeff,
  scope,
  segments = false,
  placement = 'right',
  layout = 'default',
  disabled = false,
  onApply,
  onOpenChange,
  title = '분석 계수 조정',
}: CoefficientPopoverProps) {
  const [open, setOpen] = useState(false)
  const [seg, setSeg] = useState<CoeffScope>(scope)
  const [pos, setPos] = useState({ top: 0, left: 0 })
  const wrapRef = useRef<HTMLDivElement>(null)
  const btnRef = useRef<HTMLButtonElement>(null)
  const popRef = useRef<HTMLDivElement>(null)
  const titleId = useId()
  const idBase = useId()
  const policySegTitleId = useId()
  const policy = layout === 'policy'

  const shown: CoeffScope[] = segments
    ? coeff.screenScopes.filter((s) => coeff.isAvailable(s))
    : [scope]
  const available = coeff.isAvailable(scope)

  const setOpenState = (next: boolean) => {
    setOpen(next)
    onOpenChange?.(next)
  }

  /* 바깥 클릭 · Esc 로 닫는다. 캡처 단계 — stopPropagation 하는 요소를 눌러도 닫혀야 한다. */
  useEffect(() => {
    if (!open) return
    const onDocClick = (e: MouseEvent) => {
      const t = e.target as Node
      if (wrapRef.current?.contains(t) || popRef.current?.contains(t)) return
      setOpen(false)
      onOpenChange?.(false)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
        onOpenChange?.(false)
        btnRef.current?.focus()
      }
    }
    document.addEventListener('click', onDocClick, true)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('click', onDocClick, true)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open, onOpenChange])

  // 버튼이 사라지면(탭 전환으로 스코프가 없어짐 등) 열린 채로 남지 않게 한다.
  useEffect(() => {
    if (!available && open) {
      setOpen(false)
      onOpenChange?.(false)
    }
  }, [available, open, onOpenChange])

  if (!available) return null

  const toggle = () => {
    if (open) {
      setOpenState(false)
      return
    }
    coeff.syncDraft(shown)
    setSeg(scope)
    if (placement === 'portal' && btnRef.current) {
      const r = btnRef.current.getBoundingClientRect()
      setPos({ top: r.bottom + 8, left: Math.max(8, r.right - POPOVER_WIDTH_PORTAL) })
    }
    setOpenState(true)
  }

  const active: CoeffScope = shown.includes(seg) ? seg : scope
  const rs = coeff.resolved[active]
  const values = coeff.draftOf(active)
  const errorsByScope = shown.map((s) => ({ s, errs: coeff.validateDraft(s) }))
  const hasError = errorsByScope.some((e) => e.errs.length > 0)
  const applyDisabled = disabled || hasError

  const doApply = () => {
    const out = coeff.apply(shown)
    if (!out) return
    setOpenState(false)
    const req = out[scope]
    if (req) onApply(req)
  }

  const slider = (row: CoeffRow, style: CSSProperties, locked = false) => (
    <SrLabel text={`${row.label}${locked ? ' (자동)' : ''}`}>
      <input
        id={`${idBase}-${active}-${row.vrblCd}`}
        type="range"
        min={row.minVl}
        max={row.maxVl}
        step={row.step}
        value={values[row.vrblCd] ?? row.stngVl}
        disabled={locked}
        onChange={(e) => coeff.setDraftValue(active, row.vrblCd, Number(e.target.value))}
        style={locked ? { ...style, opacity: 0.5 } : style}
      />
    </SrLabel>
  )

  const coeffRow = (row: CoeffRow) => (
    <div key={row.vrblCd} style={{ marginBottom: 12 }}>
      <div style={C_ROW}>
        <span style={{ flex: 1 }}>{row.label}</span>
        {slider(row, { width: 96 })}
        <b style={C_VALUE}>
          {formatCoeff(values[row.vrblCd])}
          {unitText(row)}
        </b>
      </div>
      {row.hint && <div style={HINT}>{row.hint}</div>}
      <div style={RANGE}>
        범위 {formatCoeff(row.minVl)} ~ {formatCoeff(row.maxVl)} · 기본값 {formatCoeff(row.stngVl)}
      </div>
    </div>
  )

  /*
   * 오류 목록. 정책 레이아웃은 공통 행 문구가 스코프 수만큼 겹치므로 같은 문구를 한 번만 보이고,
   * 현재 유형에도 있는 문구에는 유형 접두를 붙이지 않는다.
   */
  const activeErrs = errorsByScope.find((e) => e.s === active)?.errs ?? []
  const seenErr = new Set<string>()
  const errorItems = errorsByScope.flatMap(({ s, errs }) =>
    errs.flatMap((msg) => {
      if (policy) {
        if (seenErr.has(msg)) return []
        seenErr.add(msg)
      }
      const prefixed = s !== active && !(policy && activeErrs.includes(msg))
      return [
        <li key={`${s}-${msg}`}>
          {prefixed ? `[${coeff.resolved[s]?.label ?? s}] ` : ''}
          {msg}
        </li>,
      ]
    }),
  )

  const errorList = hasError && (
    <ul role="alert" style={ERRORS}>
      {errorItems}
    </ul>
  )

  /* 정책 레이아웃 — 공통 절은 세그먼트 위에 한 번(값은 `draftOf(active)` — 세 벌이 같다). */
  const policyBody = rs && (
    <div className="coeff-scroll">
      {rs.commonCds.length > 0 && (
        <>
          <div style={SECTION_TITLE}>공통</div>
          {rs.coeffs.filter((r) => rs.commonCds.includes(r.vrblCd)).map(coeffRow)}
        </>
      )}
      <div
        id={policySegTitleId}
        style={rs.commonCds.length > 0 ? SECTION_TITLE_SPLIT : SECTION_TITLE}
      >
        정책 유형별
      </div>
      <div style={POLICY_SEG_WRAP}>
        <div className="segment" role="group" aria-labelledby={policySegTitleId}>
          {shown.map((s) => (
            <button
              key={s}
              type="button"
              className={`segment__btn${s === active ? ' segment__btn--active' : ''}`}
              aria-pressed={s === active}
              style={POLICY_SEG_BTN}
              onClick={() => setSeg(s)}
            >
              {coeff.resolved[s]?.label ?? s}
            </button>
          ))}
        </div>
      </div>
      {(() => {
        const rest = rs.coeffs.filter((r) => !rs.commonCds.includes(r.vrblCd))
        return rest.length > 0
          ? rest.map(coeffRow)
          : rs.note && <div style={POLICY_NOTE}>{rs.note}</div>
      })()}
      {errorList}
    </div>
  )

  const body = rs && (
    <div className="coeff-scroll">
      {rs.weight && (
        <>
          <div style={SECTION_TITLE}>{rs.weight.title}</div>
          {rs.weight.note && <div style={NOTE}>{rs.weight.note}</div>}
          {rs.weight.mode === 'pair' ? (
            <>
              <div style={W_ROW}>
                <span style={W_LABEL}>{rs.weight.rows[0].label}</span>
                {slider(rs.weight.rows[0], { flex: 1 })}
                <b style={W_VALUE}>{formatCoeff(values[rs.weight.rows[0].vrblCd])}%</b>
              </div>
              <div style={W_AUTO_ROW}>
                <span style={W_LABEL}>{rs.weight.rows[1].label}</span>
                <span style={{ flex: 1 }}>자동 (합계 100%)</span>
                <b style={W_VALUE}>{formatCoeff(values[rs.weight.rows[1].vrblCd])}%</b>
              </div>
            </>
          ) : (
            rs.weight.rows.map((row, i, arr) => (
              <div key={row.vrblCd} style={W_ROW}>
                <span style={W_LABEL}>{row.label}</span>
                {slider(row, { flex: 1 }, i === arr.length - 1)}
                <b style={W_VALUE}>{formatCoeff(values[row.vrblCd])}%</b>
              </div>
            ))
          )}
        </>
      )}

      {rs.coeffs.length > 0 && (
        <>
          <div style={rs.weight ? SECTION_TITLE_SPLIT : SECTION_TITLE}>분석 계수</div>
          {rs.coeffs.map(coeffRow)}
        </>
      )}

      {errorList}
    </div>
  )

  const dialog = open && (
    <div
      ref={popRef}
      role="dialog"
      aria-labelledby={titleId}
      style={
        placement === 'portal'
          ? { ...POPOVER_PORTAL, top: pos.top, left: pos.left }
          : placement === 'below'
            ? POPOVER_BELOW
            : POPOVER_RIGHT
      }
    >
      <div id={titleId} style={TITLE}>
        {title}
      </div>
      {/* 🔴 정책 레이아웃은 본문 공통 절 뒤에서만 세그먼트를 그린다(두 번 그리기 방지). */}
      {!policy && segments && shown.length > 1 && (
        <div style={SEG_WRAP}>
          {shown.map((s) => (
            <button
              key={s}
              type="button"
              aria-pressed={s === active}
              style={s === active ? SEG_BTN_ON : SEG_BTN}
              onClick={() => setSeg(s)}
            >
              {coeff.resolved[s]?.label ?? s}
            </button>
          ))}
        </div>
      )}
      {policy ? policyBody : body}
      <div style={ACTIONS}>
        {/* 초안만 되돌린다 — 적용·재분석은 `적용` 을 눌러야 일어난다. */}
        <button type="button" style={DEFAULT_BTN} onClick={() => coeff.resetDraft(shown)}>
          기본값
        </button>
        <button
          type="button"
          disabled={applyDisabled}
          style={applyDisabled ? { ...APPLY_BTN, ...DISABLED } : APPLY_BTN}
          onClick={doApply}
        >
          적용
        </button>
      </div>
    </div>
  )

  return (
    <div ref={wrapRef} style={{ position: 'relative', flexShrink: 0 }}>
      <button
        ref={btnRef}
        className="btn"
        type="button"
        style={open ? TOGGLE_OPEN : TOGGLE}
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={toggle}
      >
        {title}
        <svg
          viewBox="0 0 12 12"
          width="10"
          height="10"
          aria-hidden="true"
          style={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform .15s' }}
        >
          <path
            d="M2.5 4.5 6 8l3.5-3.5"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>
      {dialog && (placement === 'portal' ? createPortal(dialog, document.body) : dialog)}
    </div>
  )
}

/* ── 배너 — 기본값과 다를 때만 ──────────────────────────── */

const BANNER: CSSProperties = {
  padding: '8px 12px',
  borderRadius: 8,
  fontSize: 12,
  background: PRIMARY_TINT,
  color: 'var(--primary)',
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 8,
}
const RESET_BTN: CSSProperties = {
  background: 'none',
  border: 'none',
  padding: 0,
  cursor: 'pointer',
  fontSize: 12,
  color: 'inherit',
  textDecoration: 'underline',
  flexShrink: 0,
}

export interface CoefficientBannerProps {
  /** `coeff.banner(scope)`. null 이면 그리지 않는다. */
  banner: CoeffBanner | null
  /** `초기화` — 화면이 `coeff.reset(scope)` 후 재분석 여부를 정한다. */
  onReset: () => void
  disabled?: boolean
  /** 자리별 여백 조정(집중구역 패널은 `margin: '-12px 0 0'`). */
  style?: CSSProperties
}

/** `적용 가중치 : …` / `적용 계수 : 라벨 기본→적용 · …` + `초기화`. */
export function CoefficientBanner({
  banner,
  onReset,
  disabled = false,
  style,
}: CoefficientBannerProps) {
  if (!banner) return null
  return (
    <div style={{ ...BANNER, ...style }}>
      <span>
        {banner.weightText && <>적용 가중치 : {banner.weightText}</>}
        {banner.weightText && banner.coeffText && <br />}
        {banner.coeffText && <>적용 계수 : {banner.coeffText}</>}
      </span>
      <button
        type="button"
        disabled={disabled}
        style={disabled ? { ...RESET_BTN, ...DISABLED } : RESET_BTN}
        onClick={onReset}
      >
        초기화
      </button>
    </div>
  )
}
