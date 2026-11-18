import {
  FEE_INCRS_RTS,
  ROAD_MTNC_LEN_MAX,
  ROAD_MTNC_LEN_MIN,
  type CstLvlCd,
  type ExpandMethod,
  type FeeIncrsRt,
  type PlcyTypeCd,
  type PolicySimAplcnParam,
  type PolicySimParams,
  type PolicySimResult,
  type RoadMtncType,
  type SavedPolicySim,
} from '../../api/policySim'
import type { AnalysisTypeWeight, AnlsVrblAjmt } from '../../api/analysisWeight'

/*
 * 정책 효과 시뮬레이션 — 표시 규칙 · 시나리오 · 추천 판정 (TSK-4037 Step 7).
 * 컴포넌트 파일에 두면 react-refresh 규칙(컴포넌트만 export)에 걸려 따로 뺐다.
 */

/** 효과 예측·비교 목록이 그리는 결과 — 저장분은 `aplcnParam` 이 null 일 수 있어 계산 응답보다 한 칸 느슨하다. */
export type ScenarioResult = Omit<PolicySimResult, 'aplcnParam'> & {
  aplcnParam: PolicySimAplcnParam | null
}

/**
 * 시나리오 비교 목록의 한 행 = **내 저장분** 1건. [시뮬레이션 실행]이 곧 저장이라(TSK-4226) 모든 행이 저장분이다.
 * 팝업으로 끝난 NO_ENF·NO_SPLY 실행은 서버가 저장하지 않아 행이 되지 않는다.
 */
export interface Scenario {
  /** 목록 키 — 항상 `s-{rsltId}`(선택·체크·추천이 모두 이 키를 쓴다). */
  key: string
  /** 결과아이디(`DELETE`·내보내기 대상). */
  rsltId: number
  params: PolicySimParams
  result: ScenarioResult
  /** 실행(저장) 시각 `YYYY.MM.DD HH:mm`(시안 표기). */
  at: string
  /** 실행(저장) 시각 epoch ms — 추천 동률의 마지막 기준(클수록 최근). */
  ts: number
}

/** 저장 일시(ISO 로컬 시각, 소수 초 가능) → Date. 초 아래는 버린다(브라우저마다 6자리 소수 해석이 갈린다). */
function parseExcnDt(excnDt: string): Date {
  return new Date(excnDt.slice(0, 19))
}

/** 저장분 1행 → 목록 행. 값은 **저장 당시 그대로** 옮긴다(재계산하지 않는다). */
export function toSavedScenario(row: SavedPolicySim): Scenario {
  const { rsltId, excnDt, plcyType, expandMethod, addPkltSlot, predcMm, sggNm, emdCd, ...result } =
    row
  // REPAIR 조건(`roadMtncType`·`roadMtncLen`)은 결과 칸에 함께 실려 온다 — 결과에도 남기고 조건으로도 옮긴다.
  const d = parseExcnDt(excnDt)
  const valid = !Number.isNaN(d.getTime())
  return {
    key: `s-${rsltId}`,
    rsltId,
    params: {
      plcyType,
      expandMethod,
      addPkltSlot,
      roadMtncType: result.roadMtncType ?? null,
      roadMtncLen: result.roadMtncLen ?? null,
      // FEE 조건(TSK-4039)도 결과 칸에 함께 실려 온다 — REPAIR 와 같이 조건으로도 옮긴다.
      feeAplcnMthd: result.feeAplcnMthd ?? null,
      feeAplcnZone: result.feeAplcnZone ?? null,
      feeIncrsRt: toFeeIncrsRt(result.feeIncrsRt),
      predcMm,
      sggNm: sggNm ?? '',
      emdCd: emdCd ?? '',
    },
    result,
    at: valid ? fmtAt(d) : excnDt,
    ts: valid ? d.getTime() : 0,
  }
}

export const EXPAND_METHOD_NM: Record<ExpandMethod, string> = {
  VACLOT: '공한지 조성',
  MULTFLR: '복층화',
  NEW: '신규 조성',
}

/** 도로 정비 유형 라벨 — `roadMtncType`(TSK-4038 §4-1). 저장 칸 `road_mtnc_type` 의 코드값 그대로. */
export const ROAD_MTNC_TYPE_NM: Record<string, string> = {
  RSDNC: '주거지역형',
  CMRC: '상업지역형',
}

/** 요금 적용 방식 라벨 — `feeAplcnMthd`(TSK-4039 §5-1). */
export const FEE_APLCN_MTHD_NM: Record<string, string> = {
  RAISE: '요금인상',
  CONVERT: '유료화',
}

/** 요금 적용 방식 — 화면 표기(설정 패널 세그먼트·결과 카드·시나리오 이름). */
export const FEE_APLCN_MTHD_LABEL: Record<string, string> = {
  RAISE: '요금 인상',
  CONVERT: '무료 → 유료 전환',
}

/** 응답 인상률(숫자) → 화면 상태 타입. 허용 목록 밖·null 은 null. */
function toFeeIncrsRt(v: number | null | undefined): FeeIncrsRt | null {
  return v != null && (FEE_INCRS_RTS as readonly number[]).includes(v) ? (v as FeeIncrsRt) : null
}

/**
 * 대시보드 "정책 효과 시뮬레이션" 카드 행 제목의 재료 — `GET /api/dashboard/analyses` 응답의 부분집합.
 * 🔴 숫자 칸도 **문자열**이다(백엔드가 `anls_param_json ->>` 를 캐스팅 없이 내린다). 전부 null 가능.
 */
export interface PolicySimRunTitleSrc {
  anlsRngCn?: string | null
  plcyType?: string | null
  expandMethod?: string | null
  addPkltSlot?: string | null
  roadMtncType?: string | null
  roadMtncLen?: string | null
  feeAplcnMthd?: string | null
  feeIncrsRt?: string | null
}

/** 코드 → 라벨. 모르는 코드는 null — `constructor` 같은 프로토타입 키가 라벨로 새지 않게 자기 키만 본다. */
function codeNm(map: Record<string, string>, code: string | null | undefined): string | null {
  return code != null && Object.prototype.hasOwnProperty.call(map, code) ? map[code] : null
}

/** 양의 정수 문자열만 숫자로 — 그 밖(빈 값·소수·문자)은 null. */
function posInt(v: string | null | undefined): number | null {
  if (v == null || !/^\d+$/.test(v.trim())) return null
  const n = Number(v.trim())
  return n > 0 ? n : null
}

/** 양수(소수 허용) 문자열 → 표기용 문자열(`20.00` → `20`). 그 밖은 null. */
function posNumText(v: string | null | undefined): string | null {
  if (v == null || !/^\d+(\.\d+)?$/.test(v.trim())) return null
  const n = Number(v.trim())
  return Number.isFinite(n) && n > 0 ? String(n) : null
}

/**
 * 저장된 정책 시뮬레이션 실행 1건의 제목 — 대시보드 카드 행(TSK-4037).
 * 🔁 TSK-4255 — `{지역} · {정책 유형} · {방식} · {값}` 순서로 통일했다(화면·보고서의 `scenarioFullName` 과 같은 순서).
 *   - EXPAND : `{지역} · 주차장 확충 · {확충 방식} · {면수}면` (`제주시 연동 · 주차장 확충 · 공한지 조성 · 100면`)
 *   - REPAIR : `{지역} · 이면도로 정비 · {정비 유형} · {연장}m` (`제주시 연동 · 이면도로 정비 · 주거지역형 · 600m`)
 *   - FEE    : `{지역} · 요금제 적용 · {적용 방식}` + RAISE 면 ` · {인상률}%`
 *              (`제주시 연동 · 요금제 적용 · 요금인상 · 20%` · `제주시 전체 · 요금제 적용 · 유료화`)
 * 재료가 하나라도 없거나 모르는 코드면 **null** — 호출부가 `anlsTypeNm` 으로 폴백한다.
 */
export function policySimRunTitle(src: PolicySimRunTitleSrc): string | null {
  const region = src.anlsRngCn?.trim()
  if (!region) return null
  switch (src.plcyType) {
    case 'EXPAND': {
      const method = codeNm(EXPAND_METHOD_NM, src.expandMethod)
      const slot = posInt(src.addPkltSlot)
      return method && slot != null
        ? `${region} · ${PLCY_TYPE_NM.EXPAND} · ${method} · ${slot}면`
        : null
    }
    case 'REPAIR': {
      const type = codeNm(ROAD_MTNC_TYPE_NM, src.roadMtncType)
      const len = posInt(src.roadMtncLen)
      return type && len != null ? `${region} · ${PLCY_TYPE_NM.REPAIR} · ${type} · ${len}m` : null
    }
    case 'FEE': {
      const mthd = codeNm(FEE_APLCN_MTHD_NM, src.feeAplcnMthd)
      if (!mthd) return null
      if (src.feeAplcnMthd !== 'RAISE') return `${region} · ${PLCY_TYPE_NM.FEE} · ${mthd}`
      const rt = posNumText(src.feeIncrsRt)
      return rt != null ? `${region} · ${PLCY_TYPE_NM.FEE} · ${mthd} · ${rt}%` : null
    }
    default:
      return null
  }
}

/**
 * 비용 수준 라벨·배지 색 — 시안 `c9` 그대로(`low`·`mid`·`high`).
 * `TBD`(미정, TSK-4038 이면도로 — 사업비 단가 없음)는 회색 배지(`.badge--normal` 계열 색).
 */
export const CST_LVL_META: Record<CstLvlCd, { label: string; bg: string; color: string }> = {
  LOW: { label: '저', bg: '#dcfce7', color: '#15803d' },
  MID: { label: '중', bg: 'var(--orange-95)', color: 'var(--orange-39)' },
  HIGH: { label: '고', bg: 'var(--red-95)', color: 'var(--red-40)' },
  TBD: { label: '미정', bg: 'var(--cool-neutral-98)', color: 'var(--text-alternative)' },
}

/**
 * 추천 동률 판정용 비용 순서(명세 7.5 "저 < 중 < 고 < 미정") — `LOW` < `MID` < `HIGH` < `TBD`.
 * 🔴 `TBD` 를 빼면 미정 행의 순위값이 정의되지 않아 동률 판정이 흔들린다(TSK-4038 Step 5-2).
 * 비용 수준이 아예 없으면(null) 그보다도 뒤.
 */
const CST_RANK: Record<CstLvlCd, number> = { LOW: 0, MID: 1, HIGH: 2, TBD: 3 }
const CST_RANK_NONE = 4

/** 정책 유형 라벨(공통코드 `PLCY_TYPE_CD` 화면 표기). */
export const PLCY_TYPE_NM: Record<PlcyTypeCd, string> = {
  EXPAND: '주차장 확충',
  REPAIR: '이면도로 정비',
  FEE: '요금제 적용',
}

/**
 * 퍼센트 표시(Step 4 표시 규칙 · 명세 7.1·7.2) — 정수 % · **부호 포함**(`-9%`·`+2%`) · 반올림해 0 이면 `0%`.
 * 입력은 응답 그대로의 **퍼센트**(`-25.78`)다. 반올림은 절댓값 기준 half-up(`-2.5` → `-3%`)이라
 * 부호에 따라 결과가 비대칭이 되지 않는다. null 은 `—`.
 */
export function fmtPct(v: number | null): string {
  if (v === null || v === undefined) return '—'
  const n = Math.round(Math.abs(v))
  if (n === 0) return '0%'
  return `${v < 0 ? '-' : '+'}${n}%`
}

/** 일반 숫자 표시(천 단위 구분). null 은 `—`. */
export function fmtNum(v: number | null, digits: number): string {
  if (v === null || v === undefined) return '—'
  return v.toLocaleString('ko-KR', { minimumFractionDigits: 0, maximumFractionDigits: digits })
}

/**
 * 시나리오 이름의 **지역 없는 재료** — 화면·보고서에 그리는 이름은 `scenarioFullName` 이 여기에 지역을 끼운 것이다.
 *   - EXPAND : `주차장 확충 · {확충 방식} · {면수}면`
 *   - REPAIR : `이면도로 정비 · {정비 유형} · {연장}m` (TSK-4038)
 *   - FEE    : `요금제 적용 · {적용 방식}[ · +{인상률}%]` (TSK-4039 — 급지는 여기가 아니라 `scenarioFullName` 의 지역 칸에 붙는다, TSK-4291)
 * 예측 기간 꼬리는 붙이지 않는다(TSK-4226 D6 — 화면 입력을 없애 새 행은 전부 같은 값이다).
 * 조건이 비어 있으면(옛 저장분 등) 그 칸을 `—` 로 둔다 — 목록 행이 통째로 깨지지 않게.
 */
export function scenarioName(p: PolicySimParams): string {
  switch (p.plcyType) {
    case 'REPAIR': {
      const type = codeNm(ROAD_MTNC_TYPE_NM, p.roadMtncType) ?? '—'
      const len = p.roadMtncLen != null ? `${fmtNum(p.roadMtncLen, 0)}m` : '—'
      return `${PLCY_TYPE_NM.REPAIR} · ${type} · ${len}`
    }
    case 'EXPAND': {
      const method = codeNm(EXPAND_METHOD_NM, p.expandMethod) ?? '—'
      const slot = p.addPkltSlot != null ? `${p.addPkltSlot}면` : '—'
      return `${PLCY_TYPE_NM.EXPAND} · ${method} · ${slot}`
    }
    case 'FEE': {
      // 방식을 모르면(조건 칸이 빈 옛 행 등) `—` 하나로 둔다.
      const mthd = codeNm(FEE_APLCN_MTHD_LABEL, p.feeAplcnMthd)
      if (!mthd) return `${PLCY_TYPE_NM.FEE} · —`
      const rt = p.feeAplcnMthd === 'RAISE' ? ` · +${p.feeIncrsRt ?? '—'}%` : ''
      return `${PLCY_TYPE_NM.FEE} · ${mthd}${rt}`
    }
    default:
      return PLCY_TYPE_NM[p.plcyType] ?? p.plcyType
  }
}

/**
 * 보고서 이름 함수가 받는 조건 — 화면 상태(`PolicySimParams`, 지역 `''`)와 보고서 스냅샷
 * (`PolicySimReportParams`, 지역 null) 둘 다 받는다.
 */
export type ReportNameParams = Omit<PolicySimParams, 'sggNm' | 'emdCd'> & {
  sggNm?: string | null
  emdCd?: string | null
}

/** 급지 라벨 — 시나리오 이름(화면·보고서) 공용. `ALL`·null 은 붙이지 않는다. */
const FEE_ZONE_SUFFIX: Record<string, string> = { Z1: ' 1급지', Z2: ' 2급지' }

/**
 * 시나리오 이름의 `{지역}{급지}` — `anlsRngCn`(서버 범위 라벨) 그대로 + 요금제 급지.
 * 🔴 급지는 **요금제 · `emdCd` 없음(시군구·전체 범위) · `Z1`/`Z2`** 일 때만 붙인다. 단일 읍면동은 서버가 그 읍면동의
 * 급지로 `feeAplcnZone` 을 채워 값이 늘 있고 지역 이름이 이미 급지를 정하므로 붙이지 않는다(중복 정보).
 */
function reportRegion(p: ReportNameParams, anlsRngCn: string | null | undefined): string {
  const region = anlsRngCn?.trim() || '—'
  const zone =
    p.plcyType === 'FEE' && !p.emdCd ? (codeNm(FEE_ZONE_SUFFIX, p.feeAplcnZone) ?? '') : ''
  return `${region}${zone}`
}

/**
 * 시나리오 이름(화면·보고서 공용) — `scenarioName` 앞에 `{지역}{급지}` 를 붙인 것.
 *   `제주시 연동 · 주차장 확충 · 공한지 조성 · 150면` · `전체 지역 1급지 · 요금제 적용 · 요금 인상 · +20%`
 * 🔁 TSK-4255 — 순서가 `{지역} · {정책 유형} · {방식} · {값}` 이다(종전: 정책 유형 뒤에 지역). 대시보드 카드
 * (`policySimRunTitle`)도 같은 순서다.
 * 🔴 화면(시나리오 비교 표 · 효과 예측 카드 캡션)과 보고서 1~3절이 **이 함수 하나**를 쓴다(TSK-4226 — 화면의 조회 범위 열을
 * 걷고 이름 안으로 옮겼다). 두 곳의 이름이 갈리지 않게, 지역을 다시 붙이는 호출부를 따로 만들지 않는다.
 * 범위는 요청의 `sggNm`·`emdCd` 로 조립하지 않고 응답 `anlsRngCn`(서버가 만든 표시 라벨)을 쓴다.
 */
export function scenarioFullName(
  p: ReportNameParams,
  anlsRngCn: string | null | undefined,
): string {
  const base = scenarioName({ ...p, sggNm: p.sggNm ?? undefined, emdCd: p.emdCd ?? undefined })
  return `${reportRegion(p, anlsRngCn)} · ${base}`
}

/** 시나리오 비교 표·보고서의 계수 조정 표식 문구(시안 그대로 — 붙여 쓴다). */
export const COEFF_AJMT_MARK = '(계수조정)'

/**
 * 계수 조정 내역 한 줄(시안 `coeffNote` — `흡수율 0.5→0.65 · 기준점유율 0.85→0.8`) — 저장 당시 **일시 조정한 계수**만
 * (`aplcnVl ≠ stngVl` — `ajmtYn` 과 같은 판정) 설정값→적용값으로 잇는다. 이름은 DB `vrbl_nm`, 단위는 적용값 뒤에 붙인다.
 * 화면 표식 툴팁과 보고서 `분석 계수 조정 내역`(TSK-4346 — D9 개정 · 2026-11-13 사용자 요청)이 이 함수 하나를 쓴다.
 * 숨김 고정값(`expsrYn='N'`)은 일시 조정이 400 이라 여기 올 일이 없지만 방어로 거른다.
 * 조정한 계수가 없거나 구 저장분(`vrblAjmt` null)이면 `null`.
 */
export function coeffAjmtNote(vrblAjmt: AnlsVrblAjmt | null | undefined): string | null {
  if (!vrblAjmt || vrblAjmt.ajmtYn !== 'Y') return null
  const fmt = (v: number) => Number(v).toLocaleString('ko-KR', { maximumFractionDigits: 4 })
  const items = vrblAjmt.vrbls
    .filter((v) => v.expsrYn !== 'N' && Number(v.aplcnVl) !== Number(v.stngVl))
    .map((v) => `${v.vrblNm} ${fmt(v.stngVl)}→${fmt(v.aplcnVl)}${v.unitNm ? ` ${v.unitNm}` : ''}`)
  return items.length > 0 ? items.join(' · ') : null
}

/** 효과 발현 시차 lag(개월) — 명세 7.3 `{확충 3, 이면도로 3, 요금제 1}`. 차트 `pol(t)` 식에 쓴다. */
export const LAG_MM: Record<PlcyTypeCd, number> = { EXPAND: 3, REPAIR: 3, FEE: 1 }

/** 이면도로 100m당 확보 구획 n_100 — 정비 유형에 맞는 계수(응답 `aplcnParam`). 없으면 null. */
export function repairN100(
  param: PolicySimAplcnParam | null,
  type: string | null | undefined,
): number | null {
  if (!param) return null
  const v = type === 'RSDNC' ? param.n100Rsdnc : type === 'CMRC' ? param.n100Cmrc : null
  return v ?? null
}

/** 실행 시각 `YYYY.MM.DD HH:mm`. */
export function fmtAt(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 추천 판정(명세 7.5 · 계획서 Step 7-2) — **목록이 바뀔 때마다 다시 부른다**(실행·삭제).
 *   후보 = `cvlcptRdctRt < 0` 인 행 → 감소 폭(`−cvlcptRdctRt`) 최대 **1건**
 *   → 동률이면 비용 수준 낮은 안(`LOW` < `MID` < `HIGH` < `TBD`) → 그다음 최근 실행(`ts` 큰 쪽).
 * 비교는 응답 퍼센트 값(소수 둘째 자리) 그대로 한다 — 화면의 정수 %로 비교하면 동률이 늘어난다.
 * 후보가 없으면 null(배지를 표시하지 않는다).
 * 🔴 `list` 는 지금 **보이는 행**(불러온 최근 100건)이다 — 그보다 오래된 저장분은 보지 않는다.
 */
export function pickRecommended(list: Scenario[]): string | null {
  let best: Scenario | null = null
  for (const s of list) {
    const rt = s.result.cvlcptRdctRt
    if (rt === null || rt === undefined || !(rt < 0)) continue
    if (best === null || isBetter(s, best)) best = s
  }
  return best ? best.key : null
}

function isBetter(a: Scenario, b: Scenario): boolean {
  const ra = a.result.cvlcptRdctRt as number
  const rb = b.result.cvlcptRdctRt as number
  if (ra !== rb) return ra < rb // 더 음수 = 감소 폭이 크다
  const ca = cstRank(a.result.cstLvlCd)
  const cb = cstRank(b.result.cstLvlCd)
  if (ca !== cb) return ca < cb
  return a.ts > b.ts
}

/** 비용 순위 — 모르는 코드·null 은 맨 뒤(자기 키만 본다). */
function cstRank(cd: CstLvlCd | null): number {
  return cd != null && Object.prototype.hasOwnProperty.call(CST_RANK, cd)
    ? CST_RANK[cd]
    : CST_RANK_NONE
}

/**
 * 정비 구간 연장 입력 문자열 → 정수 m. 서버 허용 범위(1~10000 정수) 밖·소수·빈 값은 null.
 * 입력칸은 문자열로 들고(지우고 다시 칠 수 있게) 실행·힌트는 이 값으로 판정한다.
 */
export function parseRoadMtncLen(text: string): number | null {
  const t = text.trim()
  if (!/^\d+$/.test(t)) return null
  const n = Number(t)
  return n >= ROAD_MTNC_LEN_MIN && n <= ROAD_MTNC_LEN_MAX ? n : null
}

/** 이면도로 100m당 확보 구획 — 정비 유형별(`GET /api/analysis-weights` 의 POLICY_SIM 행). */
export type RepairN100 = Record<RoadMtncType, number>

/**
 * 분석 가중치 응답에서 이면도로 n_100 두 값을 꺼낸다 — **서버 계산과 같은 해석**(`PolicySimParams.applied`):
 * `useYn='Y'` 이고 `stngVl` 이 있으면 그 값, 아니면 `bscStngVl`. 하나라도 없으면 null(힌트를 그리지 않는다).
 * 🔴 화면에 14.4·12 를 하드코딩하지 않는다 — 관리자가 계수를 바꾸면 힌트도 따라가야 한다.
 */
export function pickRepairN100(types: AnalysisTypeWeight[]): RepairN100 | null {
  const rows = types.find((t) => t.anlsTypeCd === 'POLICY_SIM')?.weights ?? []
  const applied = (vrblCd: string): number | null => {
    const w = rows.find((r) => r.vrblCd === vrblCd)
    if (!w) return null
    const v = w.useYn === 'Y' && w.stngVl != null ? w.stngVl : w.bscStngVl
    return typeof v === 'number' && Number.isFinite(v) ? v : null
  }
  const rsdnc = applied('N100_RSDNC')
  const cmrc = applied('N100_CMRC')
  return rsdnc != null && cmrc != null ? { RSDNC: rsdnc, CMRC: cmrc } : null
}

/**
 * 효과 예측 카드 캡션 끝의 기간(시안 `… · 150면 · 3개월`) — 기준 구간의 개월수다.
 * 고정 문구가 아니라 응답 `aplcnParam` 구간에서 계산한다 — 옛 저장분은 그 행의 실제 값(8개월)이 나온다.
 * 민원 0건이면 `cvlcptPrd` 가 null 이라 단속 구간을 본다. 둘 다 없으면 null(캡션에 붙이지 않는다).
 */
export function baselineMonthsLabel(
  param: { cvlcptPrd: string | null; crdnPrd: string | null } | null | undefined,
): string | null {
  const parts = (param?.cvlcptPrd ?? param?.crdnPrd)?.split('~')
  if (parts?.length !== 2) return null
  const [bgng, end] = parts
  const months =
    (Number(end.slice(0, 4)) - Number(bgng.slice(0, 4))) * 12 +
    Number(end.slice(4, 6)) -
    Number(bgng.slice(4, 6)) +
    1
  return `${months}개월`
}

/* ── 효과 예측 표시 규칙 — "변화 없음" · 반올림에 가려진 차이 (시안 `le`·`ue`·`ge`) ── */

/**
 * 응답 정밀도 — 서버(`PolicySimOutputCalculator`)가 반올림(HALF_UP)해 내리는 자리수.
 * 혼잡 지수 소수 3자리 · 증감률(%) 소수 2자리. 화면은 그보다 거칠게(지수 2자리 · % 정수) 보인다.
 */
export const CNGST_IDX_SCALE = 3
export const RATE_SCALE = 2

/** 0 이 아닌데 화면 정수 %(`fmtPct`)로는 0 이 되는 값 — "1% 미만" 으로 보인다. */
export function isRoundedAway(v: number | null | undefined): boolean {
  return v != null && v !== 0 && Math.round(Math.abs(v)) === 0
}

/**
 * 혼잡도 "변화 없음" — 예측 전후 지수가 **응답 정밀도에서 완전히 같을 때**(또는 개선률이 정확히 0).
 * 🔴 화면 2자리 표기가 같다는 이유로 판정하지 않는다 — 셋째 자리 차이는 `fmtCngstIdxPair` 가 드러낸다.
 */
export function isNoCngstChange(
  r: Pick<ScenarioResult, 'crtrCngstIdx' | 'aftrCngstIdx' | 'cngstImpvRt'>,
): boolean {
  if (r.cngstImpvRt === 0) return true
  return r.crtrCngstIdx != null && r.aftrCngstIdx != null && r.crtrCngstIdx === r.aftrCngstIdx
}

/**
 * 혼잡 지수 전후 표기 — 기본 소수 2자리(시안). 두 값이 다른데 2자리로는 같아 보이면 3자리(응답 정밀도)로
 * 보이고 `refined` 를 켠다(카드에 `소수점 이하 변화(반올림 표시)` · 분석 근거에 정밀도 안내).
 */
export function fmtCngstIdxPair(
  h0: number | null,
  h1: number | null,
): { from: string; to: string; refined: boolean } {
  if (h0 == null || h1 == null)
    return { from: fmtFixed(h0, 2), to: fmtFixed(h1, 2), refined: false }
  const refined = h0 !== h1 && h0.toFixed(2) === h1.toFixed(2)
  const d = refined ? CNGST_IDX_SCALE : 2
  return { from: h0.toFixed(d), to: h1.toFixed(d), refined }
}

function fmtFixed(v: number | null, d: number): string {
  return v == null ? '—' : v.toFixed(d)
}

/**
 * 증감률 크기 문구 — `9%` · `1% 미만`(0 이 아닌데 반올림하면 0). 부호·방향 단어는 호출부가 붙인다.
 * 정확히 0·null 은 호출부가 먼저 `변화 없음`·`—` 으로 처리한다.
 */
export function fmtRateMagnitude(v: number): string {
  return isRoundedAway(v) ? '1% 미만' : `${Math.round(Math.abs(v))}%`
}

/** 월평균 민원 건수 차이 — `약 2건` · `1건 미만`. 두 값 중 하나라도 없으면 null. */
export function fmtCntDiff(c0: number | null, c1: number | null): string | null {
  if (c0 == null || c1 == null) return null
  const d = Math.abs(c0 - c1)
  if (d === 0) return null
  return d < 0.5 ? '1건 미만' : `약 ${Math.round(d).toLocaleString('ko-KR')}건`
}
