import { useCallback, useEffect, useMemo, useState } from 'react'
import { restWgvl } from '../../api/analysis'
import {
  fetchAnalysisWeights,
  type AnalysisTypeWeight,
  type AnalysisWeight,
  type VrblVls,
} from '../../api/analysisWeight'
import {
  COEFF_SCOPE_META,
  SCREEN_SCOPES,
  type CoeffScope,
  type CoeffScreen,
  type CoeffVarMeta,
} from './coefficientMeta'

/*
 * 분석 계수 일시 조정 — 화면 상태 훅 (TSK-4325 Step 8 · TSK-4332 Step 6).
 *
 * 맡는 것: 설정 조회 · 초안(draft)/적용(applied)/마지막 실행(ran) 3벌 · 변경분(`vrblVls`) 계산 · 검증 · 배너 문구.
 * 값은 React 상태에만 둔다(D8 — URL·localStorage 금지).
 *
 *   draft   팝오버 슬라이더. 열 때 applied 로 맞추고, 바닥 `기본값` 은 이것만 되돌린다.
 *   applied `적용` 으로 확정한 값 — 다음 실행이 쓰고 배너의 근거다.
 *   ran     결과를 만든 실행의 값 — 내보내기(보고서)가 쓴다(D7). 실행 성공 시 화면이 `markRan` 을 부른다.
 *
 * 기본값은 설정값 `stngVl` 이다(D5 — `bscStngVl` 이 아니다).
 * 설정 조회 실패·그릴 행 0개면 `isAvailable` 이 false — 버튼을 숨기고 설정값 분석은 막지 않는다(D16).
 *
 * 🔁 TSK-4332 — 메타 `common` 코드(정책 공통 4)는 같은 화면 형제 스코프와 값을 공유한다. 저장소(`ValueSets`)는
 * 스코프별 한 벌 그대로이고, `setDraftValue`·`reset` 두 함수만 형제 스코프의 공통 코드를 함께 쓰고/지운다.
 * `common` 이 없는 스코프(4화면)에서는 두 분기 모두 no-op 이다.
 */

/** 변수코드 → 값. 그려지는 행(설정에 있고 `useYn='Y'`·`expsrYn='Y'`)만 담는다. */
export type CoeffValues = Record<string, number>

/** 그려지는 행 1개 — 메타 + 설정 응답을 합친 것. */
export interface CoeffRow {
  vrblCd: string
  label: string
  unitNm: string | null
  minVl: number
  maxVl: number
  /** 기본값(= 설정값, D5). */
  stngVl: number
  /** 설정값이 눈금 위에 없으면 `'any'`(D15 — 브라우저가 값을 조용히 바꾸지 않게). */
  step: number | 'any'
  hint?: string
  intOnly: boolean
}

export interface ResolvedWeight {
  mode: 'pair' | 'redistribute'
  title: string
  note?: string
  inVrblVls: boolean
  /** pair 면 [슬라이더 행, 자동 행]. redistribute 면 마지막 행이 잠긴다. */
  rows: CoeffRow[]
}

export interface ResolvedScope {
  scope: CoeffScope
  label: string
  weight: ResolvedWeight | null
  coeffs: CoeffRow[]
  /** 그려지는 행만 남긴 엄격 증가 묶음(2행 이상). */
  chains: CoeffRow[][]
  /** 그려지는 행 중 형제 스코프와 공유하는 코드(TSK-4332 — 정책 `공통` 절). 없으면 빈 배열. */
  commonCds: string[]
  /** 공통 밖 계수가 0행일 때 유형 절에 보이는 문구(정책 확충). */
  note?: string
}

/** 실행·내보내기 요청에 실을 값 한 벌. */
export interface CoeffRequest {
  scope: CoeffScope
  /** 그 시점의 전체 값(기본값 포함). `markRan` 에 그대로 넘긴다. */
  values: CoeffValues
  /** 보낼 계수. 비어 있으면 `undefined` — API 함수가 파라미터를 뺀다. */
  vrblVls: VrblVls | undefined
  /** 집중구역 전용(D2). 기본값이면 null(= 설정값). */
  cvlcptWgvl: number | null
  crdnWgvl: number | null
  /** 기본값과 값으로 다른 행이 하나라도 있다(D11). */
  adjusted: boolean
  /** 가중치 묶음이 기본값과 다르다(투자 부제 `(조정됨)` 등). */
  weightAdjusted: boolean
}

/** 배너 문구. 기본값과 같으면 해당 줄이 null 이다. */
export interface CoeffBanner {
  /** `민원 건수 70% + 단속 건수 30%` / `안전위험도 40% / 민원 건수 20% / …` */
  weightText: string | null
  /** `배정범위값 300→500 · 상위등급기준점수 80→85` */
  coeffText: string | null
}

type ValueSets = Partial<Record<CoeffScope, CoeffValues>>

/** 서버 자릿수(`NUMERIC(12,4)` · 정수 전용)에 맞춰 반올림 — 슬라이더 부동소수 오차도 여기서 걷힌다. */
function roundSend(row: CoeffRow, v: number): number {
  return row.intOnly ? Math.round(v) : Number(v.toFixed(4))
}

/** 값 비교(D11 — `60.00` = `60`). */
function sameVl(row: CoeffRow, a: number, b: number): boolean {
  return roundSend(row, a) === roundSend(row, b)
}

/** 화면 표시용 숫자 — 정수는 그대로, 소수는 넷째 자리까지. */
export function formatCoeff(v: number): string {
  return String(Number.isInteger(v) ? v : Math.round(v * 1e4) / 1e4)
}

/** `minVl + k·step` 위에 있는가. 아니면 그 행은 step="any" 로 그린다(D15). */
function onStep(v: number, min: number, step: number): boolean {
  const k = (v - min) / step
  return Math.abs(k - Math.round(k)) < 1e-6
}

function resolveRow(meta: CoeffVarMeta, w: AnalysisWeight): CoeffRow {
  return {
    vrblCd: meta.vrblCd,
    label: meta.label ?? w.vrblNm,
    unitNm: w.unitNm,
    minVl: w.minVl,
    maxVl: w.maxVl,
    stngVl: w.stngVl,
    step: onStep(w.stngVl, w.minVl, meta.step) ? meta.step : 'any',
    hint: meta.hint,
    intOnly: !!meta.intOnly,
  }
}

/**
 * 설정 응답 → 스코프별 그릴 행. 행이 없거나 `useYn='N'` 이거나 숨김(`expsrYn !== 'Y'`, TSK-4346)인 변수는
 * 그리지도 보내지도 않는다. 🔴 숨김 판정은 DB `expsr_yn` 하나가 정본 — `coefficientMeta.ts` 목록은 고치지 않는다.
 * 여기서 빠진 코드는 `defaultsOf`·`valuesOf`(값 한 벌)·`buildRequest`(`vrblVls`) 어디에도 들어가지 않는다.
 */
function resolveScope(scope: CoeffScope, types: AnalysisTypeWeight[]): ResolvedScope | null {
  const meta = COEFF_SCOPE_META[scope]
  const rows = types.find((t) => t.anlsTypeCd === meta.anlsTypeCd)?.weights ?? []
  const pick = (m: CoeffVarMeta) => {
    const w = rows.find((r) => r.vrblCd === m.vrblCd && r.useYn === 'Y' && r.expsrYn === 'Y')
    return w ? resolveRow(m, w) : null
  }

  let weight: ResolvedWeight | null = null
  if (meta.weight) {
    const wRows = meta.weight.vars.map(pick).filter((r): r is CoeffRow => r !== null)
    // pair 는 두 행이 다 있어야 성립한다. redistribute 는 꺼진 행을 빼고 남은 행끼리 합계 100(묶음에서 'N' 제외).
    const ok = meta.weight.mode === 'pair' ? wRows.length === 2 : wRows.length > 0
    if (ok) {
      weight = {
        mode: meta.weight.mode,
        title: meta.weight.title,
        note: meta.weight.note,
        inVrblVls: meta.weight.inVrblVls,
        rows: wRows,
      }
    } else if (meta.weight.required) {
      return null // 집중구역 — 가중치 지표가 꺼지면 버튼을 숨긴다(TSK-4306 D3)
    }
  }

  const coeffs = meta.coeffs.map(pick).filter((r): r is CoeffRow => r !== null)
  if (!weight && coeffs.length === 0) return null

  const byCd = new Map(coeffs.map((r) => [r.vrblCd, r]))
  const chains = meta.chains
    .map((codes) => codes.map((cd) => byCd.get(cd)).filter((r): r is CoeffRow => !!r))
    .filter((c) => c.length >= 2)

  const common = new Set(meta.common ?? [])
  const commonCds = coeffs.filter((r) => common.has(r.vrblCd)).map((r) => r.vrblCd)

  return { scope, label: meta.label, weight, coeffs, chains, commonCds, note: meta.note }
}

/** `cd` 를 `common` 으로 함께 가진 같은 화면의 다른 스코프(TSK-4332). `common` 이 없으면 빈 배열. */
function commonSiblings(
  screenScopes: readonly CoeffScope[],
  scope: CoeffScope,
  cd: string,
): CoeffScope[] {
  if (!COEFF_SCOPE_META[scope].common?.includes(cd)) return []
  return screenScopes.filter(
    (s) => s !== scope && (COEFF_SCOPE_META[s].common?.includes(cd) ?? false),
  )
}

function allRows(rs: ResolvedScope): CoeffRow[] {
  return [...(rs.weight?.rows ?? []), ...rs.coeffs]
}

function defaultsOf(rs: ResolvedScope): CoeffValues {
  const v: CoeffValues = {}
  for (const r of allRows(rs)) v[r.vrblCd] = r.stngVl
  return v
}

function changed(rows: CoeffRow[], values: CoeffValues): boolean {
  return rows.some((r) => !sameVl(r, values[r.vrblCd] ?? r.stngVl, r.stngVl))
}

/**
 * 투자 가중치 재분배 — 시안 규칙. 위 항목 합을 뺀 잔여 안으로 값을 자르고, 아래 항목을 현재 비율로
 * `Math.round` 재분배하며(아래 합이 0 이면 균등), 마지막 항목은 잔여값으로 잠근다.
 * 🔴 시안 그대로면 반올림 합이 잔여를 넘어 합계 101 이 된다(`0/1/99/0` 에서 첫 항목 50 → `50/1/50/0`) —
 * 중간 항목을 `min(반올림값, 남은 잔여)` 로 자른다.
 */
function redistribute(
  rows: CoeffRow[],
  values: CoeffValues,
  idx: number,
  next: number,
): CoeffValues {
  const cur = rows.map((r) => values[r.vrblCd] ?? r.stngVl)
  const s = [...cur]
  const avail = 100 - cur.slice(0, idx).reduce((a, b) => a + b, 0)
  const below = rows.length - idx - 1
  if (below === 0) {
    s[idx] = avail
  } else {
    const row = rows[idx]
    const e = Math.max(Math.max(0, row.minVl), Math.min(avail, row.maxVl, next))
    s[idx] = e
    const rest = avail - e
    const lower = cur.slice(idx + 1)
    const lowerSum = lower.reduce((a, b) => a + b, 0)
    let used = 0
    for (let k = 0; k < below; k += 1) {
      const i = idx + 1 + k
      if (k === below - 1) {
        s[i] = Math.max(0, rest - used)
      } else {
        const share = Math.round(lowerSum > 0 ? (rest * lower[k]) / lowerSum : rest / below)
        const v = Math.max(0, Math.min(share, rest - used))
        used += v
        s[i] = v
      }
    }
  }
  const out = { ...values }
  rows.forEach((r, i) => {
    out[r.vrblCd] = Number(s[i].toFixed(4))
  })
  return out
}

/** 검증 — 서버 400 이 최종 방어다. 경계·합계는 그 묶음을 건드렸을 때만 본다(서버와 같다). */
function validateScope(rs: ResolvedScope, values: CoeffValues): string[] {
  const errs: string[] = []
  const vl = (r: CoeffRow) => values[r.vrblCd] ?? r.stngVl

  const w = rs.weight
  if (w) {
    if (w.mode === 'pair') {
      const auto = w.rows[1]
      const v = vl(auto)
      if (v < auto.minVl || v > auto.maxVl) {
        errs.push(
          `${auto.label} 자동값 ${formatCoeff(v)}% 가 허용 범위(${formatCoeff(auto.minVl)} ~ ${formatCoeff(auto.maxVl)})를 벗어났습니다.`,
        )
      }
    } else if (changed(w.rows, values)) {
      const sum = Number(w.rows.reduce((a, r) => a + vl(r), 0).toFixed(4))
      if (sum !== 100) errs.push(`가중치 합계가 100 이 아닙니다 (현재 ${formatCoeff(sum)}).`)
    }
  }

  for (const r of allRows(rs)) {
    if (w?.mode === 'pair' && r === w.rows[1]) continue // 위에서 문구를 따로 냈다
    const v = vl(r)
    if (v < r.minVl || v > r.maxVl) {
      errs.push(
        `${r.label} 값이 허용 범위(${formatCoeff(r.minVl)} ~ ${formatCoeff(r.maxVl)})를 벗어났습니다.`,
      )
    }
  }

  for (const chain of rs.chains) {
    if (!changed(chain, values)) continue
    for (let i = 0; i + 1 < chain.length; i += 1) {
      const lo = chain[i]
      const hi = chain[i + 1]
      if (!(roundSend(lo, vl(lo)) < roundSend(hi, vl(hi)))) {
        errs.push(
          `${lo.label}(${formatCoeff(vl(lo))}) < ${hi.label}(${formatCoeff(vl(hi))}) 이어야 합니다.`,
        )
      }
    }
  }
  return errs
}

/** 값 한 벌 → 요청. 묶음(D21)은 한 행이라도 다르면 묶음 전체, 그 밖은 변경분만. */
function buildRequest(rs: ResolvedScope, values: CoeffValues): CoeffRequest {
  const vl = (r: CoeffRow) => values[r.vrblCd] ?? r.stngVl
  const vrblVls: VrblVls = {}
  const put = (r: CoeffRow) => {
    vrblVls[r.vrblCd] = roundSend(r, vl(r))
  }

  const w = rs.weight
  const weightAdjusted = !!w && changed(w.rows, values)
  let cvlcptWgvl: number | null = null
  let crdnWgvl: number | null = null
  if (w && weightAdjusted) {
    if (w.inVrblVls) {
      w.rows.forEach(put)
    } else {
      // 집중구역(D2) — 기존 파라미터. 단속 = 100 - 민원은 restWgvl 로만 계산한다(TSK-4306).
      cvlcptWgvl = Number(vl(w.rows[0]).toFixed(2))
      crdnWgvl = restWgvl(cvlcptWgvl)
    }
  }

  const inChain = new Set<string>()
  for (const chain of rs.chains) {
    chain.forEach((r) => inChain.add(r.vrblCd))
    if (changed(chain, values)) chain.forEach(put)
  }
  for (const r of rs.coeffs) {
    if (!inChain.has(r.vrblCd) && !sameVl(r, vl(r), r.stngVl)) put(r)
  }

  const adjusted = changed(allRows(rs), values)
  return {
    scope: rs.scope,
    values: { ...defaultsOf(rs), ...values },
    vrblVls: Object.keys(vrblVls).length > 0 ? vrblVls : undefined,
    cvlcptWgvl,
    crdnWgvl,
    adjusted,
    weightAdjusted,
  }
}

function buildBanner(rs: ResolvedScope, values: CoeffValues): CoeffBanner | null {
  const vl = (r: CoeffRow) => values[r.vrblCd] ?? r.stngVl
  const w = rs.weight
  const weightText =
    w && changed(w.rows, values)
      ? w.rows
          .map((r) => `${r.label} ${formatCoeff(vl(r))}%`)
          .join(w.mode === 'pair' ? ' + ' : ' / ')
      : null
  const diff = rs.coeffs.filter((r) => !sameVl(r, vl(r), r.stngVl))
  const coeffText =
    diff.length > 0
      ? diff.map((r) => `${r.label} ${formatCoeff(r.stngVl)}→${formatCoeff(vl(r))}`).join(' · ')
      : null
  return weightText || coeffText ? { weightText, coeffText } : null
}

export type CoeffLoadStatus = 'loading' | 'ready' | 'failed'

export interface AnalysisCoefficient {
  status: CoeffLoadStatus
  /** 이 화면의 스코프(탭 순서). */
  screenScopes: readonly CoeffScope[]
  /** 그릴 행이 있는 스코프만 담긴다. */
  resolved: Partial<Record<CoeffScope, ResolvedScope>>
  /** 버튼을 그릴지 — 조회 성공 + 그릴 행 1개 이상(D16). */
  isAvailable: (scope: CoeffScope) => boolean
  draftOf: (scope: CoeffScope) => CoeffValues
  /** 슬라이더 1개 변경. 가중치 묶음이면 pair 자동값·재분배를 함께 한다. */
  setDraftValue: (scope: CoeffScope, vrblCd: string, value: number) => void
  /** 팝오버를 열 때 — 초안을 적용값으로 맞춘다. */
  syncDraft: (scopes: readonly CoeffScope[]) => void
  /** 바닥 `기본값` — 초안만 기본값으로(적용·재분석 없음). */
  resetDraft: (scopes: readonly CoeffScope[]) => void
  /** 초안 검증 문구. 비어 있으면 `적용` 가능. */
  validateDraft: (scope: CoeffScope) => string[]
  /**
   * `적용` — 초안을 적용값으로 확정하고 각 스코프의 요청을 돌려준다. 하나라도 오류면 아무것도 바꾸지 않고 null.
   * 🔴 `setState` 직후 `appliedRequest` 는 이전 값이다 — 실행은 돌려받은 요청으로 한다.
   */
  apply: (scopes: readonly CoeffScope[]) => Partial<Record<CoeffScope, CoeffRequest>> | null
  /** 배너 `초기화` — 적용값·초안을 기본값으로. 기본값 요청을 돌려준다. */
  reset: (scope: CoeffScope) => CoeffRequest
  /** 모든 스코프의 3벌을 비운다 — 구역추천 탭 전환(D13). */
  resetAll: () => void
  appliedRequest: (scope: CoeffScope) => CoeffRequest
  /** 결과를 만든 실행의 요청 — 내보내기 전용(D7). */
  ranRequest: (scope: CoeffScope) => CoeffRequest
  /** 실행 성공 시 그 요청을 마지막 실행값으로 남긴다. */
  markRan: (req: CoeffRequest) => void
  /** 적용값 기준 배너. 기본값과 같으면 null. */
  banner: (scope: CoeffScope) => CoeffBanner | null
}

const EMPTY_REQUEST = (scope: CoeffScope): CoeffRequest => ({
  scope,
  values: {},
  vrblVls: undefined,
  cvlcptWgvl: null,
  crdnWgvl: null,
  adjusted: false,
  weightAdjusted: false,
})

export function useAnalysisCoefficient(screen: CoeffScreen): AnalysisCoefficient {
  const screenScopes = SCREEN_SCOPES[screen]
  const [status, setStatus] = useState<CoeffLoadStatus>('loading')
  const [resolved, setResolved] = useState<Partial<Record<CoeffScope, ResolvedScope>>>({})
  const [draft, setDraft] = useState<ValueSets>({})
  const [applied, setApplied] = useState<ValueSets>({})
  const [ran, setRan] = useState<ValueSets>({})

  // 설정 조회. 실패해도 설정값 분석은 막지 않고 버튼만 숨긴다(D16).
  useEffect(() => {
    let cancelled = false
    fetchAnalysisWeights()
      .then((types) => {
        if (cancelled) return
        const next: Partial<Record<CoeffScope, ResolvedScope>> = {}
        for (const s of screenScopes) {
          const rs = resolveScope(s, types)
          if (rs) next[s] = rs
        }
        setResolved(next)
        setStatus('ready')
      })
      .catch(() => {
        if (cancelled) return
        setResolved({})
        setStatus('failed')
      })
    return () => {
      cancelled = true
    }
  }, [screenScopes])

  /** 저장된 값 한 벌 — 없으면 기본값. 다른 행 값이 섞이지 않게 그려지는 행만 남긴다. */
  const valuesOf = useCallback(
    (sets: ValueSets, scope: CoeffScope): CoeffValues => {
      const rs = resolved[scope]
      if (!rs) return {}
      const base = defaultsOf(rs)
      const saved = sets[scope]
      if (saved) for (const cd of Object.keys(base)) if (cd in saved) base[cd] = saved[cd]
      return base
    },
    [resolved],
  )

  const isAvailable = useCallback(
    (scope: CoeffScope) => status === 'ready' && !!resolved[scope],
    [status, resolved],
  )

  const draftOf = useCallback((scope: CoeffScope) => valuesOf(draft, scope), [valuesOf, draft])

  const setDraftValue = useCallback(
    (scope: CoeffScope, vrblCd: string, value: number) => {
      const rs = resolved[scope]
      if (!rs) return
      setDraft((prev) => {
        const cur = valuesOf(prev, scope)
        const w = rs.weight
        const wi = w ? w.rows.findIndex((r) => r.vrblCd === vrblCd) : -1
        let next: CoeffValues
        if (w && wi >= 0) {
          if (w.mode === 'pair') {
            if (wi !== 0) return prev // 자동 행은 손대지 않는다
            const row = w.rows[0]
            const v = Number(Math.max(row.minVl, Math.min(row.maxVl, value)).toFixed(2))
            next = { ...cur, [row.vrblCd]: v, [w.rows[1].vrblCd]: restWgvl(v) }
          } else {
            if (wi === w.rows.length - 1) return prev // 마지막 항목 잠금
            next = redistribute(w.rows, cur, wi, value)
          }
        } else {
          next = { ...cur, [vrblCd]: value }
        }
        const out: ValueSets = { ...prev, [scope]: next }
        // 공통 코드면 형제 스코프 초안에도 같은 값을 쓴다(TSK-4332 D4 — 값은 한 벌).
        for (const sib of commonSiblings(screenScopes, scope, vrblCd)) {
          if (resolved[sib]) out[sib] = { ...valuesOf(prev, sib), [vrblCd]: value }
        }
        return out
      })
    },
    [resolved, valuesOf, screenScopes],
  )

  const syncDraft = useCallback(
    (scopes: readonly CoeffScope[]) => {
      setDraft((prev) => {
        const next = { ...prev }
        for (const s of scopes) next[s] = valuesOf(applied, s)
        return next
      })
    },
    [applied, valuesOf],
  )

  const resetDraft = useCallback((scopes: readonly CoeffScope[]) => {
    setDraft((prev) => {
      const next = { ...prev }
      for (const s of scopes) delete next[s]
      return next
    })
  }, [])

  const validateDraft = useCallback(
    (scope: CoeffScope) => {
      const rs = resolved[scope]
      return rs ? validateScope(rs, valuesOf(draft, scope)) : []
    },
    [resolved, valuesOf, draft],
  )

  const apply = useCallback(
    (scopes: readonly CoeffScope[]) => {
      const out: Partial<Record<CoeffScope, CoeffRequest>> = {}
      for (const s of scopes) {
        const rs = resolved[s]
        if (!rs) continue
        const v = valuesOf(draft, s)
        if (validateScope(rs, v).length > 0) return null
        out[s] = buildRequest(rs, v)
      }
      setApplied((prev) => {
        const next = { ...prev }
        for (const s of scopes) if (out[s]) next[s] = out[s]!.values
        return next
      })
      return out
    },
    [resolved, valuesOf, draft],
  )

  const reset = useCallback(
    (scope: CoeffScope) => {
      // 형제 스코프에서는 공유하는 공통 코드만 지운다 — 다른 유형 값은 유지한다(TSK-4332 D4).
      const shared = (COEFF_SCOPE_META[scope].common ?? []).map((cd) => ({
        cd,
        sibs: commonSiblings(screenScopes, scope, cd),
      }))
      const drop = (prev: ValueSets) => {
        const next = { ...prev }
        delete next[scope]
        for (const { cd, sibs } of shared) {
          for (const sib of sibs) {
            const v = next[sib]
            if (v && cd in v) {
              const rest = { ...v }
              delete rest[cd]
              next[sib] = rest
            }
          }
        }
        return next
      }
      setApplied(drop)
      setDraft(drop)
      const rs = resolved[scope]
      return rs ? buildRequest(rs, defaultsOf(rs)) : EMPTY_REQUEST(scope)
    },
    [resolved, screenScopes],
  )

  const resetAll = useCallback(() => {
    setDraft({})
    setApplied({})
    setRan({})
  }, [])

  const appliedRequest = useCallback(
    (scope: CoeffScope) => {
      const rs = resolved[scope]
      return rs ? buildRequest(rs, valuesOf(applied, scope)) : EMPTY_REQUEST(scope)
    },
    [resolved, valuesOf, applied],
  )

  const ranRequest = useCallback(
    (scope: CoeffScope) => {
      const rs = resolved[scope]
      return rs ? buildRequest(rs, valuesOf(ran, scope)) : EMPTY_REQUEST(scope)
    },
    [resolved, valuesOf, ran],
  )

  const markRan = useCallback((req: CoeffRequest) => {
    setRan((prev) => ({ ...prev, [req.scope]: req.values }))
  }, [])

  const banner = useCallback(
    (scope: CoeffScope) => {
      const rs = resolved[scope]
      return rs ? buildBanner(rs, valuesOf(applied, scope)) : null
    },
    [resolved, valuesOf, applied],
  )

  return useMemo(
    () => ({
      status,
      screenScopes,
      resolved,
      isAvailable,
      draftOf,
      setDraftValue,
      syncDraft,
      resetDraft,
      validateDraft,
      apply,
      reset,
      resetAll,
      appliedRequest,
      ranRequest,
      markRan,
      banner,
    }),
    [
      status,
      screenScopes,
      resolved,
      isAvailable,
      draftOf,
      setDraftValue,
      syncDraft,
      resetDraft,
      validateDraft,
      apply,
      reset,
      resetAll,
      appliedRequest,
      ranRequest,
      markRan,
      banner,
    ],
  )
}
