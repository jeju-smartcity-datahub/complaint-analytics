import type {
  InvestPrrtGradeCriterion,
  InvestPrrtLot,
  InvestPrrtLvlCd,
  InvestPrrtResult,
  InvestPrrtWeight,
} from '../../api/investPrrt'
import { dashedYmd } from '../../lib/dateRange'

/*
 * 투자우선순위 보드의 표시 규칙.
 * 등급 → 색·배지, 라벨·점수 포맷터, 그리고 **표시 집합 선택 함수**를 한곳에 둔다.
 *
 * 🔴 **등급 판정은 백엔드가 한다** — 여기에 점수 비교(80·60)를 넣지 않는다. 응답의 `prrtLvlCd` 로
 * 색만 고르고, 라벨·임계 문구는 응답 `gradeCriteria` 에서 읽는다.
 */

/* ── 등급 → 색·배지·라벨 ──────────────────────────────── */

/** 등급 → 색(CSS 변수 표기). SVG `fill` 에는 `hotspotGrade.resolveCssColor()` 로 풀어 넣는다. */
export const PRRT_LVL_COLOR_VAR: Record<InvestPrrtLvlCd, string> = {
  H: 'var(--red-50)',
  M: 'var(--orange-50)',
  L: 'var(--blue-50)',
}

/** 등급 → 배지 클래스(`dashboard.css` 의 공용 `.badge`). 시안의 high/mid/low 대응 그대로다. */
export const PRRT_LVL_BADGE_CLASS: Record<InvestPrrtLvlCd, string> = {
  H: 'badge badge--severe',
  M: 'badge badge--warn',
  L: 'badge badge--caution',
}

/**
 * 등급 라벨 — 응답 `gradeCriteria[].prrtLvlNm` 을 쓴다(공통코드를 따로 부르지 않는다 — 같은 이름이
 * 응답에 실려 온다). 기준표에 없는 코드면 코드값을 그대로 보여 준다(빈 배지보다 낫다).
 */
export function prrtLvlLabel(cd: InvestPrrtLvlCd, criteria: InvestPrrtGradeCriterion[]): string {
  return criteria.find((c) => c.prrtLvlCd === cd)?.prrtLvlNm || cd
}

/* ── 포맷터 ───────────────────────────────────────────── */

/** 점수 — 소수 1자리(`96.5`). */
export function fmtScr(scr: number): string {
  return scr.toFixed(1)
}

/** 건수 — 천 단위 구분. */
export function fmtCnt(n: number): string {
  return n.toLocaleString('ko-KR')
}

/** 가중치 값 — 정수면 소수점 없이(`25`), 아니면 뒤의 0 을 뗀다(`12.5`). */
export function fmtWgvl(v: number): string {
  return Number.isInteger(v) ? String(v) : String(Number(v.toFixed(4)))
}

/* ── 가중치 문구 (🔴 응답 `weights` 로 조립 — 고정 문구 금지) ── */

/** 목록 카드 안내문 — `종합점수 기준 : 안전위험도 25% / 민원빈도 25% / …`. 지표가 없으면 빈 문자열. */
export function buildWeightNote(weights: InvestPrrtWeight[]): string {
  if (weights.length === 0) return ''
  const parts = weights.map((w) => `${w.vrblNm} ${fmtWgvl(w.stngVl)}${w.unitNm ?? '%'}`)
  return `종합점수 기준 : ${parts.join(' / ')}`
}

/**
 * 목록 카드의 `분석 기간` 값. 고정 문구가 아니라 응답 구간(`YYYYMMDD`)에서 만든다 —
 * 시작일이 종료일의 정확히 N개월 전이면 `최근 N개월`(12의 배수면 `최근 N년` — `최근 12개월` → `최근 1년`),
 * 아니면 날짜 그대로다(서버 기본 구간이 바뀌어도 틀린 문구가 나가지 않는다).
 * 월말 보정: 종료일이 그 달에 없으면 말일이다(서버 `minusMonths` 와 같다 — 05-31 의 3개월 전 = 02-28).
 */
export function buildPeriodLabel(bgngYmd: string, endYmd: string): string {
  const parse = (ymd: string) => [ymd.slice(0, 4), ymd.slice(4, 6), ymd.slice(6, 8)].map(Number)
  const [by, bm, bd] = parse(bgngYmd)
  const [ey, em, ed] = parse(endYmd)
  const months = (ey - by) * 12 + em - bm
  const lastDay = new Date(by, bm, 0).getDate()
  if (months <= 0 || bd !== Math.min(ed, lastDay))
    return `${dashedYmd(bgngYmd)} ~ ${dashedYmd(endYmd)}`
  return months % 12 === 0 ? `최근 ${months / 12}년` : `최근 ${months}개월`
}

/**
 * 지표별 집계·산정 기간 — 산정 기준 InfoTip 의 `집계·산정 기간 —` 줄 재료.
 * 응답에서 페이지가 묶어 목록 카드로 내린다. 민원·사고는 `bgngYmd`~`endYmd`, 이용률은 월 범위, 안전위험도는 기준일이다.
 * ⚠️ 보고서 스냅샷에는 `utztn*`·`sftyCrtrYmd` 가 없다(`undefined`) — 그 지표 줄은 생략된다.
 */
export type InvestPrrtDataPrd = Pick<
  InvestPrrtResult,
  'bgngYmd' | 'endYmd' | 'utztnBgngYm' | 'utztnEndYm' | 'sftyCrtrYmd'
>

/** `YYYYMM` → `YYYY-MM`. 형식이 어긋나면 원문 그대로(`dashedYmd` 와 같은 동작). */
export function dashedYm(ym: string): string {
  return ym.replace(/^(\d{4})(\d{2})$/, '$1-$2')
}

/** 헤더 `최근 갱신` 표의 한 행. `dt` 는 원값 — 일 `YYYYMMDD` · 이용률 `YYYYMM` · 없음 null. */
export interface InvestPrrtFreshnessRow {
  vrblCd: 'SAFETY' | 'COMPLAINT' | 'USAGE' | 'ACCIDENT'
  nm: string
  dt: string | null
}

/**
 * 헤더 `지표별 최근 갱신일` 4행 — 시안 순서(안전위험도 → 민원 건수 → 이용률 → 사고 이력) 고정.
 * 이름은 `weights.vrblNm`, 없으면 시안 문구로 폴백한다.
 * 🔴 가중치 0 으로 `weights` 에 없는 지표도 **행을 남긴다** — "자료 상태" 표라 점수 사용 여부와 무관하다
 *    (InfoTip `집계·산정 기간` 줄의 생략 규칙과 일부러 다르다).
 */
export function buildFreshnessRows(
  result: Pick<
    InvestPrrtResult,
    'sftyChckLastYmd' | 'cvlcptLastYmd' | 'utztnLastYm' | 'acdntLastYmd'
  >,
  weights: { vrblCd: string; vrblNm: string }[],
): InvestPrrtFreshnessRow[] {
  const nmOf = (cd: InvestPrrtFreshnessRow['vrblCd'], fallback: string) =>
    weights.find((w) => w.vrblCd === cd)?.vrblNm || fallback
  return [
    { vrblCd: 'SAFETY', nm: nmOf('SAFETY', '안전위험도'), dt: result.sftyChckLastYmd ?? null },
    { vrblCd: 'COMPLAINT', nm: nmOf('COMPLAINT', '민원 건수'), dt: result.cvlcptLastYmd ?? null },
    { vrblCd: 'USAGE', nm: nmOf('USAGE', '이용률'), dt: result.utztnLastYm ?? null },
    { vrblCd: 'ACCIDENT', nm: nmOf('ACCIDENT', '사고 이력'), dt: result.acdntLastYmd ?? null },
  ]
}

/* ── 표시 집합 ────────────────────────────────────────── */

/**
 * 정렬 기준. `SNTHS` 는 종합점수 높은순(= 전 지역 순위 순), 나머지는 지표의 `vrblCd` 다 —
 * 정렬 선택지를 응답 `weights` 에서 만들 수 있게 같은 코드를 쓴다.
 */
export type InvestPrrtSort = 'SNTHS' | 'SAFETY' | 'COMPLAINT' | 'USAGE' | 'ACCIDENT' | 'ENFORCE'

/**
 * 지표 `vrblCd` → 그 지표의 점수 필드. 팝업의 지표 막대도 이 표를 읽는다.
 * 🔴 `ENFORCE` 는 지우지 않는다 — INVEST 단속 설정 행은 삭제됐지만 단속 가중치가 실린
 * 저장 보고서 스냅샷을 다시 그릴 때 필요하다.
 */
export const VRBL_SCR_FIELD: Record<
  Exclude<InvestPrrtSort, 'SNTHS'>,
  'sftyRiskScr' | 'cvlcptFrqScr' | 'utztnPttnScr' | 'acdntScr' | 'crdnScr'
> = {
  SAFETY: 'sftyRiskScr',
  COMPLAINT: 'cvlcptFrqScr',
  USAGE: 'utztnPttnScr',
  ACCIDENT: 'acdntScr',
  ENFORCE: 'crdnScr',
}

/** 화면 필터·정렬 상태. 빈 문자열은 "전체" 다. */
export interface InvestPrrtFilter {
  /** 시군구명. */
  sggNm: string
  /** 읍면동코드. 🔴 이름이 아니라 코드로 거른다. */
  emdCd: string
  /** 우선순위 등급. */
  grade: InvestPrrtLvlCd | ''
  sort: InvestPrrtSort
}

/**
 * 표시 집합의 한 행 — 응답의 주차장에 **표시 순위**를 붙인 것. `selectDisplayLots` 만 만든다.
 * 🔴 식별자는 여전히 `pkltId` 다 — `dspRnk` 는 필터·정렬마다 바뀌므로 선택·key 로 쓰지 않는다.
 */
export interface InvestPrrtDisplayLot extends InvestPrrtLot {
  /**
   * 표시 순위 — **행정구역 필터 → 정렬**까지 적용한 목록에서의 순번. 화면이 그리는 순위는 이 값이다.
   * 우선순위(등급) 필터는 이 번호를 바꾸지 않는다(걸러 보여 줄 뿐이라 번호가 건너뛸 수 있다).
   */
  dspRnk: number
}

/**
 * 목록 행의 보조 문구 — **종합점수는 항상** 보여 주고, 지표로 정렬했을 때는 그 **옆에** 정렬 기준의
 * 값을 덧붙인다: `종합점수 82.4점 · 민원 건수 100.0점` (사용자 결정 2026-10-14 — 정렬을 바꿨는데
 * 종합점수만 보이면 왜 그 순서인지 알 수 없고, 종합점수를 지우면 화면의 주 지표가 사라진다).
 * 종합점수 순이면 `종합점수 96.5점` 이다(단위 `점`).
 * 지표 이름은 응답 `weights` 의 `vrblNm` 이다(소스에 박지 않는다). 못 찾으면 코드값을 그대로 쓴다.
 */
export function buildRowMeta(
  lot: InvestPrrtLot,
  sort: InvestPrrtSort,
  weights: { vrblCd: string; vrblNm: string }[],
): string {
  // 점수 단위 `점`(사용자 요청 · 지도 팝업과 같은 단위. 시안 목록은 단위가 없다)
  const base = `종합점수 ${fmtScr(lot.snthsScr)}점`
  if (sort === 'SNTHS') return base
  const name = weights.find((w) => w.vrblCd === sort)?.vrblNm ?? sort
  return `${base} · ${name} ${fmtScr(lot[VRBL_SCR_FIELD[sort]])}점`
}

/** 첫 진입·초기화 상태 — 필터 없음 · 종합점수 높은순. */
export const DEFAULT_FILTER: InvestPrrtFilter = { sggNm: '', emdCd: '', grade: '', sort: 'SNTHS' }

/**
 * 표시 집합 — 🔴 **목록의 행이자 지도의 핀**이다.
 * 순서는 **행정구역 필터 → 정렬 → 순위 부여 → 우선순위(등급) 필터 → 상위 N 자르기**.
 *
 * 🔴 페이지가 **한 번만** 불러 그 배열을 목록과 지도에 함께 내려준다. 각자 자르면 목록에 있는데
 * 지도에 없는 행이 생긴다.
 * 🔴 **표시 순위(`dspRnk`)를 여기서 — 여기서만 — 붙인다.** 행정구역 필터와 정렬을 걸면 그 결과
 * 안에서 1부터 다시 매겨진다(사용자 결정 2026-10-14). 🔴 **우선순위(등급) 필터는 순위를 바꾸지
 * 않는다**(같은 날 사용자 결정) — 순위를 매긴 **뒤에** 등급으로 거르므로, '중간' 만 보면 번호가
 * 11, 12, … 처럼 그 행정구역 안에서의 순위 그대로 나온다. 기본 상태에서는 전 지역 순위 `rnk` 와 같다.
 * 목록·핀·순위 원 색이 전부 이 값을 읽는다 — 각자 index 로 세지 않는다.
 * 원본 `lots` 와 그 원소는 변형하지 않는다(`rnk`·점수는 전 지역 기준 그대로 남는다).
 *
 * @param n 상위 몇 곳까지 그릴지 — 응답 `crtrVls.indctPkltCnt`.
 */
export function selectDisplayLots(
  lots: InvestPrrtLot[],
  filter: InvestPrrtFilter,
  n: number,
): InvestPrrtDisplayLot[] {
  // 🔴 등급은 여기서 거르지 않는다 — 순위를 매긴 뒤에 거른다(아래).
  const filtered = lots.filter(
    (l) => (!filter.sggNm || l.sggNm === filter.sggNm) && (!filter.emdCd || l.emdCd === filter.emdCd),
  )

  // `filter()` 가 새 배열을 주므로 제자리 정렬해도 원본(`lots`)은 그대로다.
  if (filter.sort === 'SNTHS') {
    filtered.sort((a, b) => a.rnk - b.rnk)
  } else {
    const field = VRBL_SCR_FIELD[filter.sort]
    // 동점이면 전 지역 순위가 앞선 쪽 — 정렬이 매번 같은 순서를 낸다.
    filtered.sort((a, b) => b[field] - a[field] || a.rnk - b.rnk)
  }

  const limit = Number.isFinite(n) ? Math.max(0, Math.floor(n)) : 0
  const ranked: InvestPrrtDisplayLot[] = filtered.map((lot, i) => ({ ...lot, dspRnk: i + 1 }))
  const shown = filter.grade ? ranked.filter((l) => l.prrtLvlCd === filter.grade) : ranked
  return shown.slice(0, limit)
}

/* ── 컨트롤 선택지 · 범례 · 팝업 보조 ─────────── */

/** `vrblCd` 가 정렬 가능한 지표인지(= 점수 필드가 있는지). 모르는 지표는 선택지·막대에서 뺀다. */
export function isScrVrbl(vrblCd: string): vrblCd is Exclude<InvestPrrtSort, 'SNTHS'> {
  return Object.prototype.hasOwnProperty.call(VRBL_SCR_FIELD, vrblCd)
}

/** 시군구 선택지 — 응답 `lots` 에 실제로 있는 이름만. */
export function buildSggOptions(lots: InvestPrrtLot[]): string[] {
  return [...new Set(lots.map((l) => l.sggNm).filter(Boolean))].sort((a, b) =>
    a.localeCompare(b, 'ko'),
  )
}

/** 읍면동 선택지 — 고른 시군구 안의 것만. 🔴 값은 `emdCd` 다(같은 이름의 동이 있어도 갈린다). */
export function buildEmdOptions(
  lots: InvestPrrtLot[],
  sggNm: string,
): { emdCd: string; emdNm: string }[] {
  if (!sggNm) return []
  const byCd = new Map<string, string>()
  lots.forEach((l) => {
    if (l.sggNm === sggNm && l.emdCd && !byCd.has(l.emdCd)) byCd.set(l.emdCd, l.emdNm || l.emdCd)
  })
  return [...byCd.entries()]
    .map(([emdCd, emdNm]) => ({ emdCd, emdNm }))
    .sort((a, b) => a.emdNm.localeCompare(b.emdNm, 'ko') || a.emdCd.localeCompare(b.emdCd))
}

/** 정렬 선택지 — 종합점수 + 응답 `weights` 에 있는 지표(설정에서 끈 지표는 나오지 않는다). */
export function buildSortOptions(
  weights: InvestPrrtWeight[],
): { value: InvestPrrtSort; label: string }[] {
  const opts: { value: InvestPrrtSort; label: string }[] = [
    { value: 'SNTHS', label: '종합점수 높은순' },
  ]
  weights.forEach((w) => {
    if (isScrVrbl(w.vrblCd)) opts.push({ value: w.vrblCd, label: `${w.vrblNm} 높은순` })
  })
  return opts
}

/**
 * 범례 줄 — `높음 (80점 이상)` · `중간 (60~79점)` · `낮음 (60점 미만)`.
 * 🔴 임계는 응답 `minScr` 에서 계산한다(소스에 80·60 을 두지 않는다). 상한은 바로 위 등급의 `minScr`.
 */
export function buildLegendRows(
  criteria: InvestPrrtGradeCriterion[],
): { prrtLvlCd: InvestPrrtLvlCd; label: string }[] {
  const sorted = [...criteria].sort((a, b) => b.minScr - a.minScr)
  return sorted.map((c, i) => {
    const upper = i > 0 ? sorted[i - 1].minScr : null
    let range: string
    if (upper == null) range = `${fmtWgvl(c.minScr)}점 이상`
    else if (i === sorted.length - 1 || c.minScr <= 0) range = `${fmtWgvl(upper)}점 미만`
    else if (Number.isInteger(upper) && Number.isInteger(c.minScr)) range = `${c.minScr}~${upper - 1}점`
    else range = `${fmtWgvl(c.minScr)}점 이상 ${fmtWgvl(upper)}점 미만`
    return { prrtLvlCd: c.prrtLvlCd, label: `${c.prrtLvlNm} (${range})` }
  })
}

/**
 * 준공 문구 — `준공 2001년 (25년 경과)`. `instlYm`(YYYYMM)이 없거나 형식이 다르면 null(줄 생략).
 * 대상의 92% 가 결측이라 없는 쪽이 보통이다.
 */
export function fmtInstl(instlYm: string | null, now: Date = new Date()): string | null {
  if (!instlYm || !/^\d{4}/.test(instlYm)) return null
  const year = Number(instlYm.slice(0, 4))
  const age = now.getFullYear() - year
  return age >= 0 ? `준공 ${year}년 (${age}년 경과)` : `준공 ${year}년`
}

/**
 * HTML 이스케이프. 🔴 Leaflet 의 `divIcon.html`·`bindPopup(string)` 은 **innerHTML 로 들어간다** —
 * 주차장 이름·주소처럼 원천에서 온 문자열은 반드시 이 함수를 거쳐 넣는다(XSS).
 */
export function escapeHtml(s: string | null | undefined): string {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 사고 카드 아래 글씨에 이름을 보일 읍면동 수(시안 목데이터가 최대 4곳 — 실자료는 수십 곳이라 3곳 + 곳수로 줄인다). */
const ACDNT_NOTE_EMD_MAX = 3

/**
 * 사고 카드(`stat__delta`) 아래 글씨 — 시안 `e9[...].lots`(`노형 · 연동 · 성산 · 중문 주차장` / 0건이면 `사고 없음`)의 실자료판.
 *
 * - 응답 `lots`(🔴 표시 집합이 아니라 행정구역 필터 전 전체 — 카드 숫자 `smry` 와 같은 축)에서 `acdntCnt > 0` 인 주차장을 센다.
 * - 이름은 주차장명이 아니라 **읍면동명**이다(시안이 지역명이고, 주차장명은 `이호이동 796-1, 2` 처럼 길다).
 *   사고 건수 합이 큰 읍면동 순(같으면 이름순)으로 최대 {@link ACDNT_NOTE_EMD_MAX}곳 → `서귀동 · 이도이동 · 이호이동 등 86곳`.
 *   주차장이 그 읍면동들에만 있으면 `등` 없이 `서귀동 · 연동 3곳`.
 * - ⚠️ 카드 숫자 `smry.acdntCnt` 는 사고이력 표 **전체**의 창 안 건수라(대상 밖 주차장 포함 — `selectAcdntCnt`) 이 곳수의 사고 합과 다를 수 있다.
 * - 조회 전·실패(`lots` null)면 null — 카드가 줄을 그리지 않는다.
 */
export function buildAcdntLotsNote(lots: InvestPrrtLot[] | null): string | null {
  if (!lots) return null
  const hit = lots.filter((l) => l.acdntCnt > 0)
  if (hit.length === 0) return '사고 없음'
  const byEmd = new Map<string, number>()
  hit.forEach((l) => {
    const nm = l.emdNm || '읍면동 미상'
    byEmd.set(nm, (byEmd.get(nm) ?? 0) + l.acdntCnt)
  })
  const emds = [...byEmd.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'ko'))
    .map(([nm]) => nm)
  const shown = emds.slice(0, ACDNT_NOTE_EMD_MAX)
  return `${shown.join(' · ')}${emds.length > shown.length ? ' 등' : ''} ${fmtCnt(hit.length)}곳`
}

/**
 * 목록 카드 `조회조건` 줄 — 시안 `조회조건 : {U} · {I} · 우선순위 {o}`.
 * - 기간: `buildPeriodLabel` 값(응답 구간). 없으면(조회 전·실패) null — 줄을 그리지 않는다.
 * - 행정구역: 시군구 없음 `제주 전체` · 시군구만 `{시군구}` · 읍면동까지 `{시군구} {읍면동}`(이름은 `lots` 에서 코드로 찾는다).
 * - 등급: 없음 `전체` · 있으면 `gradeCriteria` 의 이름(필터 버튼과 같은 문구).
 */
export function buildCondLabel(
  periodLabel: string | null,
  filter: InvestPrrtFilter,
  lots: InvestPrrtLot[],
  gradeCriteria: InvestPrrtGradeCriterion[],
): string | null {
  if (!periodLabel) return null
  let rgn = '제주 전체'
  if (filter.sggNm) {
    const emdNm = filter.emdCd ? lots.find((l) => l.emdCd === filter.emdCd)?.emdNm : undefined
    rgn = emdNm ? `${filter.sggNm} ${emdNm}` : filter.sggNm
  }
  const grd = filter.grade
    ? gradeCriteria.find((c) => c.prrtLvlCd === filter.grade)?.prrtLvlNm || filter.grade
    : '전체'
  return `조회조건 : ${periodLabel} · ${rgn} · 우선순위 ${grd}`
}
