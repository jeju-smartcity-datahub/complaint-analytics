import type { CellValue, Workbook, Worksheet } from 'exceljs'
import type {
  InvestPrrtGradeCriterion,
  InvestPrrtLot,
  InvestPrrtLvlCd,
  InvestPrrtReport,
  InvestPrrtWorkbook,
  InvestPrrtWorkbookRow,
} from '../../api/investPrrt'
import type { ZoneRcmdtnWorkbookVrbl } from '../../api/zoneRcmdtn'
import {
  VRBL_SCR_FIELD,
  buildLegendRows,
  buildWeightNote,
  fmtWgvl,
  isScrVrbl,
  prrtLvlLabel,
} from '../../components/analysis/investPrrtView'
import { COEFF_SCOPE_META } from './coefficientMeta'
import { DOC_TITLE, INVEST_PRRT_FILE_BASE, instlCell } from './investPrrtReportTemplate'
import {
  addInputRows,
  colRange as sheetColRange,
  createWorkbook,
  defineName,
  downloadXlsx,
  finishWorkbook,
  HINT_FONT,
  increasingAll,
  increasingNeighbors,
  NOTE_FONT,
  numericRule,
  rangeText,
  sheetRef,
  styleCondition,
  styleHeader,
  textFormula,
  xlRound15,
  xlStr,
  type InputRow,
  type TextSlot,
} from './xlsxKit'

/*
 * 투자우선순위 보드 엑셀(.xlsx) 생성기 (TSK-4343 Step 4 — 계획서 3절).
 *
 * 저장 응답·스냅샷의 `lots`(필터 통과 전부) + `wbk`(전 지역 대상 수 `n` · 주차장별 `k` 3개 · 계수 8행)로 2장을 만든다.
 *   - 시트 1 `투자우선순위` — 제목 · 지역/기간 · 계수 입력(노란 셀 · D2·D3) · 조건(회색 · D1·D4) · 산정 기준 · 요약(D11) · 표(D10)
 *   - 시트 2 `계산 데이터`   — 상수 · 주차장 1행씩(`pkltId↑` 로 미리 정렬 — D8). 정의된 이름으로 참조한다.
 *
 * 🔴 같은 산식이 **네 곳**이다 — 서버 `InvestPrrtCalculator`(BigDecimal) · 이 파일의 엑셀 수식 · 이 파일의 TS 재계산
 *    `computeInvestPrrtWorkbook`(수식 셀 `result`) · 백엔드 정답지 `InvestPrrtWorkbookIntegrationTest.recompute`(Java double).
 *    하나를 고치면 넷을 함께 고친다.
 *      분자 = SAFETY·S·MAX(n−1,1) + 100·(COMPLAINT·kC + USAGE·kU + ACCIDENT·kA)      (나눗셈 없이 곱·덧셈만 — D7)
 *      분모 = MAX(n−1,1)·(SAFETY + COMPLAINT + USAGE + ACCIDENT)
 *      P    = ROUND(분자/분모, 9)                                                    (엑셀 ROUND = 유효숫자 15자리 → 0 에서 먼 쪽)
 *      등급 = 경계 역전이면 "-" · P ≥ UP 높음 · P ≥ MD 중간 · 그 밖 낮음
 *      순번 = SUMPRODUCT(--(P열>P)) + 앞 행(pkltId↑) 중 동점 수 + 1                    (D8 · COUNTIF(">"&P) 금지)
 * 🔴 `n` 은 `wbk.trgtCnt`(필터 **전**)다 — `lots.length` 로 잡으면 필터를 건 보고서만 점수가 갈린다(3-3).
 * 🔴 `MAX(n−1,1)` 은 분모와 안전 항 분자 **두 곳**이다(n = 1 에서 종합점수가 S 가 되어야 한다).
 * 🔴 Excel 2010 호환 함수만 쓴다(IF·AND·OR·NOT·ISNUMBER·ABS·ROUND·INT·TEXT·MAX·COUNT·COUNTIF·SUMPRODUCT·INDEX·MATCH).
 *    `fullCalcOnLoad`(`createWorkbook`)로 엑셀이 열 때 전부 다시 계산한다 — 수식 셀의 `result` 는 초기 계수 기준이다.
 * 🔴 ExcelJS 값 import 는 `xlsxKit` 의 동적 import 1건뿐이다(여기서는 `import type` 만).
 */

/* ── 1. 순수 재계산 (수식 셀의 result 값) ─────────────── */

const W = { s: 'SAFETY', c: 'COMPLAINT', u: 'USAGE', a: 'ACCIDENT' } as const
const UP = 'UP_GRD_CRTR_SCR'
const MD = 'MD_GRD_CRTR_SCR'
const ALTMNT = 'ALTMNT_RNG_VL'
const INDCT = 'INDCT_PKLT_CNT'
/** 입력 셀 가중치(위 3개) — 마지막 `ACCIDENT` 는 `=100-(앞 3개 합)` 수식이다(D2). */
const INPUT_WEIGHTS: readonly string[] = [W.s, W.c, W.u]
const WEIGHT_CDS: readonly string[] = [W.s, W.c, W.u, W.a]
/** 경계 묶음 — 엄격 증가 순서(중간 < 상위). */
const BNDRY_CDS: readonly string[] = [MD, UP]

/** 계수 맵 `vrblCd → 값`. */
export type InvestPrrtVrblValues = Record<string, number>

/** `wbk.vrbls` 의 적용값(`aplcnVl`) — 엑셀 입력 셀 초기값(D5). */
export const investPrrtAplcnValues = (vrbls: ZoneRcmdtnWorkbookVrbl[]): InvestPrrtVrblValues =>
  Object.fromEntries(vrbls.map((v) => [v.vrblCd, Number(v.aplcnVl)]))

const need = (vals: InvestPrrtVrblValues, cd: string): number => {
  const v = vals[cd]
  if (v == null || !Number.isFinite(v)) throw new Error(`엑셀 계수 ${cd} 값이 없습니다.`)
  return v
}

/** 주차장 1행 — 시트 2 행 순서(`pkltId↑`) 그대로. */
export interface InvestPrrtXlsxRow {
  lot: InvestPrrtLot
  /** 안전 점수 S(0/50/100 — `lots[].sftyRiskScr`). */
  s: number
  k: InvestPrrtWorkbookRow
  num: number
  den: number
  /** 종합점수 `ROUND(분자/분모, 9)`. */
  p: number
  /** 등급 — 경계 역전이면 `null`(엑셀 `-`). */
  grade: InvestPrrtLvlCd | null
  /** 표시순번(1..K — 보고서 안 순번, D9). */
  seq: number
}

export interface InvestPrrtXlsxCalc {
  /** 경계 정상(중간 < 상위). */
  bndryOk: boolean
  /** 시트 2 행 순서(`pkltId↑`). */
  rows: InvestPrrtXlsxRow[]
  /** 표시 순서(표시순번 순). */
  ordered: InvestPrrtXlsxRow[]
  grdCnt: Record<InvestPrrtLvlCd, number>
}

/**
 * 계수 `vals` 일 때 엑셀 수식이 낼 값 — 계획서 3-2 그대로(정답지 `InvestPrrtWorkbookIntegrationTest.recompute` 와 같은 규칙).
 * `vals` 를 생략하면 `wbk.vrbls` 적용값이다. 🔴 `ACCIDENT` 는 받은 값 그대로 쓴다(엑셀은 `=100-…` 이라 합 100 이면 같다).
 * 🔴 `wbk.rows` 에 없는 주차장이 `lots` 에 있으면 예외(엑셀이 `k` 를 가질 수 없다).
 */
export function computeInvestPrrtWorkbook(
  report: Pick<InvestPrrtReport, 'lots'> & { wbk: InvestPrrtWorkbook },
  vals: InvestPrrtVrblValues = investPrrtAplcnValues(report.wbk.vrbls),
): InvestPrrtXlsxCalc {
  const { wbk } = report
  const n1 = Math.max(wbk.trgtCnt - 1, 1)
  const wS = need(vals, W.s)
  const wC = need(vals, W.c)
  const wU = need(vals, W.u)
  const wA = need(vals, W.a)
  const up = need(vals, UP)
  const md = need(vals, MD)
  const bndryOk = md < up

  const kById = new Map(wbk.rows.map((r) => [r.pkltId, r]))
  const sorted = [...report.lots].sort((a, b) => a.pkltId - b.pkltId)
  const base = sorted.map((lot) => {
    const k = kById.get(lot.pkltId)
    if (!k) throw new Error(`wbk.rows 에 주차장 ${lot.pkltId} 가 없습니다.`)
    const s = Number(lot.sftyRiskScr)
    // 엑셀 수식과 같은 연산 순서 — `SAFETY*S*MAX(n-1,1)+100*(COMPLAINT*kC+USAGE*kU+ACCIDENT*kA)`.
    const num = wS * s * n1 + 100 * (wC * k.cvlcptLtCnt + wU * k.utztnLtCnt + wA * k.acdntLtCnt)
    const den = n1 * (wS + wC + wU + wA)
    const p = xlRound15(num / den, 9)
    const grade: InvestPrrtLvlCd | null = !bndryOk ? null : p >= up ? 'H' : p >= md ? 'M' : 'L'
    return { lot, s, k, num, den, p, grade }
  })
  const rows: InvestPrrtXlsxRow[] = base.map((r, i) => {
    let gt = 0
    let tie = 0
    for (let j = 0; j < base.length; j++) {
      if (base[j].p > r.p) gt++
      else if (j < i && base[j].p === r.p) tie++
    }
    return { ...r, seq: gt + tie + 1 }
  })
  const ordered = [...rows].sort((a, b) => a.seq - b.seq)
  const grdCnt: Record<InvestPrrtLvlCd, number> = { H: 0, M: 0, L: 0 }
  rows.forEach((r) => {
    if (r.grade) grdCnt[r.grade]++
  })
  return { bndryOk, rows, ordered, grdCnt }
}

/* ── 2. 통합문서 ──────────────────────────────────────── */

/** 생성기 입력 — 저장 응답·스냅샷 공용. 호출부가 `wbk` 존재를 판정한 뒤 `{ ...report, wbk: report.wbk }` 로 넘긴다(D13). */
export type InvestPrrtXlsxSource = Pick<
  InvestPrrtReport,
  'rptpNm' | 'regionNm' | 'anlsPrdExpln' | 'crtYmd' | 'weights' | 'gradeCriteria' | 'lots'
> & { wbk: InvestPrrtWorkbook }

/** 파일명(확장자 제외) — 응답 `rptpNm`, 없으면 `투자보강_우선순위`(D15). */
export function investPrrtXlsxFileBase(src: { rptpNm?: string }): string {
  return src.rptpNm?.trim() || INVEST_PRRT_FILE_BASE
}

const MAIN_SHEET = '투자우선순위'
const DATA_SHEET = '계산 데이터'
const COEFF_TITLE = '분석 계수 조정 (노란 셀을 바꾸면 아래가 자동 재계산됩니다)'
const COND_TITLE = '조건 (엑셀에서 바꿀 수 없음)'
/** D1 — 구역추천 D1 과 같은 문구. */
const HINT_SPATIAL = '이 값을 바꾸려면 화면에서 다시 조회하세요 (공간 배정이라 엑셀에서 다시 계산할 수 없습니다)'
/** D4. */
const HINT_INDCT = '목록·지도 표시 수 — 엑셀·PDF 는 선택한 행정구역의 전체 주차장을 담습니다'
const HINT_LOCKED = '설정에서 조정이 꺼진 계수라 적용값으로 고정됩니다'
/** D2-1 — 가중치 하나라도 꺼지면 서버가 재정규화하므로 `=100-…` 식을 쓰지 않고 넷 다 고정한다. */
const HINT_WEIGHT_OFF = '가중치 중 설정에서 꺼진 것이 있어 네 가중치를 적용값으로 고정합니다 (꺼진 쪽은 0)'
/** D3 — `InvestPrrtStngResolver` 의 사용자용 문장. */
const BNDRY_WARN = '중간등급기준점수는 상위등급기준점수보다 작아야 합니다 (화면에서는 적용할 수 없습니다)'
const PCTL_NOTE = '민원·이용·사고 점수는 전 지역 대상 중 순위 백분위입니다 (행정구역을 골라도 전 지역 기준)'
const DATA_NOTE = `'${DATA_SHEET}' 시트는 재계산에 쓰는 원자료·상수입니다(보호됨 — 값을 고치면 화면 결과와 달라집니다).`
const EMPTY = '-'
const WARN_FONT = { bold: true, color: { argb: 'FFD92D20' } }

const COL = {
  pkltId: 'A',
  lotNm: 'B',
  addr: 'C',
  cpcty: 'D',
  instl: 'E',
  s: 'F',
  kC: 'G',
  kU: 'H',
  kA: 'I',
  scrS: 'J',
  scrC: 'K',
  scrU: 'L',
  scrA: 'M',
  num: 'N',
  den: 'O',
  p: 'P',
  grd: 'Q',
  seq: 'R',
} as const
type ColKey = keyof typeof COL

const COL_NAMES: Partial<Record<ColKey, string>> = {
  lotNm: '주차장열',
  addr: '주소열',
  cpcty: '면수열',
  instl: '준공연도열',
  scrS: '안전점수열',
  scrC: '민원점수열',
  scrU: '이용점수열',
  scrA: '사고점수열',
  p: '종합점수열',
  grd: '등급열',
  seq: '표시순번',
}

const DATA_HEADERS: Record<ColKey, string> = {
  pkltId: '주차장ID',
  lotNm: '주차장',
  addr: '주소',
  cpcty: '면수',
  instl: '준공연도',
  s: '안전 S',
  kC: '민원 k',
  kU: '이용 k',
  kA: '사고 k',
  scrS: '안전 점수',
  scrC: '민원 점수',
  scrU: '이용 점수',
  scrA: '사고 점수',
  num: '분자',
  den: '분모',
  p: '종합점수',
  grd: '우선순위',
  seq: '표시순번',
}

/** 지표 `vrblCd` → 시트 2 점수 열(표시값 — 계수와 무관). */
const SCR_COL: Record<string, ColKey> = { SAFETY: 'scrS', COMPLAINT: 'scrC', USAGE: 'scrU', ACCIDENT: 'scrA' }

/** C열 안내 — 화면 팝오버 메타(`coefficientMeta` INVEST `coeffs`)의 `hint` 를 `vrblCd` 로 찾는다(문구 복사 금지). */
const coeffHint = (vrblCd: string): string | undefined =>
  COEFF_SCOPE_META.INVEST.coeffs.find((m) => m.vrblCd === vrblCd)?.hint

/** 수식 안 가중치·경계 → 문구 조각(화면 `fmtWgvl` 과 같은 꼴 — 정수면 소수점 없이, 아니면 넷째 자리까지 꼬리 0 제거). */
const xlWgvlText = (expr: string): string =>
  `TEXT(ROUND(${expr},4),IF(ROUND(${expr},4)=INT(ROUND(${expr},4)),"0","0.####"))`

/** 회색 조건 셀 1행(이름 없음 — 수식이나 값). */
function conditionCell(ws: Worksheet, row: number, label: string, value: CellValue, hint?: string, numFmt?: string) {
  ws.getCell(`A${row}`).value = label
  const cell = ws.getCell(`B${row}`)
  cell.value = value
  styleCondition(cell, { numFmt })
  if (hint) {
    ws.getCell(`C${row}`).value = hint
    ws.getCell(`C${row}`).font = HINT_FONT
  }
}

/** 노란 입력 셀 1행 — 범위(`minVl~maxVl`) + 소수 넷째 자리까지(INVEST 는 정수 전용 계수 없음) + `extra`. */
function inputRow(
  v: ZoneRcmdtnWorkbookVrbl,
  addr: string,
  extra: { conds: string[]; error: string },
  hint?: string,
): InputRow {
  const range = rangeText(v.minVl, v.maxVl)
  const int = v.intOnlyYn === 'Y'
  return {
    name: v.vrblCd,
    label: v.vrblNm,
    value: Number(v.aplcnVl),
    hint,
    validation: {
      formula: numericRule(addr, { min: v.minVl, max: v.maxVl, step: int ? 'int' : 'dec4', extra: extra.conds }),
      title: v.vrblNm,
      error:
        (int
          ? `${range ? `${range} 사이의 ` : ''}정수를 입력하세요`
          : `${range ? `${range} 사이의 ` : ''}값을 소수 넷째 자리까지 입력하세요`) + extra.error,
    },
  }
}

const conditionRow = (v: ZoneRcmdtnWorkbookVrbl, hint?: string): InputRow => ({
  name: v.vrblCd,
  label: v.vrblNm,
  value: Number(v.aplcnVl),
  hint,
  condition: true,
})

/**
 * 범례 문장 수식 — 화면 `buildLegendRows`(경계 셀 참조) 와 같은 꼴. 결과는 `우선순위 : 높음 (80점 이상) · …`(PDF 기준 상자와 같다).
 * 실제 값으로 만든 미러가 `buildLegendRows` 결과와 다르면(기준표가 H·M·L 3행이 아님 등) 고정 문장을 돌려준다.
 */
function legendCell(criteria: InvestPrrtGradeCriterion[], up: number, md: number): CellValue {
  const actual = (u: number, m: number) =>
    `우선순위 : ${buildLegendRows(
      criteria.map((c) => ({ ...c, minScr: c.prrtLvlCd === 'H' ? u : c.prrtLvlCd === 'M' ? m : c.minScr })),
    )
      .map((r) => r.label)
      .join(' · ')}`
  const result = actual(up, md)
  const nm = (cd: InvestPrrtLvlCd) => prrtLvlLabel(cd, criteria)
  const l = criteria.find((c) => c.prrtLvlCd === 'L')
  const ok = criteria.length === 3 && ['H', 'M'].every((cd) => criteria.some((c) => c.prrtLvlCd === cd)) && l?.minScr === 0
  // 미러 — `buildLegendRows` 의 갈래(정수 경계면 `MD~UP-1점` · 아니면 `MD점 이상 UP점 미만` · MD ≤ 0 이면 `UP점 미만`).
  const mirror = (u: number, m: number) => {
    const midRange =
      m <= 0
        ? `${fmtWgvl(u)}점 미만`
        : Number.isInteger(u) && Number.isInteger(m)
          ? `${m}~${u - 1}점`
          : `${fmtWgvl(m)}점 이상 ${fmtWgvl(u)}점 미만`
    return `우선순위 : ${nm('H')} (${fmtWgvl(u)}점 이상) · ${nm('M')} (${midRange}) · ${nm('L')} (${fmtWgvl(m)}점 미만)`
  }
  if (!ok || md >= up || mirror(up, md) !== result) return result
  const isInt = `AND(${UP}=INT(${UP}),${MD}=INT(${MD}))`
  const mid =
    `IF(${MD}<=0,${xlWgvlText(UP)}&"점 미만",IF(${isInt},${MD}&"~"&(${UP}-1)&"점",` +
    `${xlWgvlText(MD)}&"점 이상 "&${xlWgvlText(UP)}&"점 미만"))`
  const formula =
    `${xlStr(`우선순위 : ${nm('H')} (`)}&${xlWgvlText(UP)}&${xlStr(`점 이상) · ${nm('M')} (`)}&${mid}&` +
    `${xlStr(`) · ${nm('L')} (`)}&${xlWgvlText(MD)}&"점 미만)"`
  return { formula, result }
}

/** 통합문서(.xlsx) 바이트. 🔴 계수 8행 중 하나라도 없으면 예외(호출부가 실패 문구로 처리). */
export async function buildInvestPrrtWorkbook(src: InvestPrrtXlsxSource): Promise<ArrayBuffer> {
  const { wbk, gradeCriteria } = src
  const vals = investPrrtAplcnValues(wbk.vrbls)
  const calc = computeInvestPrrtWorkbook(src, vals)
  const byCd = new Map(wbk.vrbls.map((x) => [x.vrblCd, x]))
  const vrbl = (cd: string): ZoneRcmdtnWorkbookVrbl => {
    const x = byCd.get(cd)
    if (!x) throw new Error(`엑셀 계수 ${cd} 가 wbk.vrbls 에 없습니다.`)
    return x
  }
  ;[...WEIGHT_CDS, UP, MD, ALTMNT, INDCT].forEach(vrbl)
  const grdNm = (cd: InvestPrrtLvlCd) => prrtLvlLabel(cd, gradeCriteria)

  const wb: Workbook = await createWorkbook()
  const main = wb.addWorksheet(MAIN_SHEET)
  const data = wb.addWorksheet(DATA_SHEET)

  /* ── 시트 1 상단 — 제목 · 지역/기간 ── */
  ;[26, 30, 12, 10, 34, 10, 18, 10, 10, 10, 10].forEach((w, i) => (main.getColumn(i + 1).width = w))
  main.getCell('A1').value = DOC_TITLE
  main.getCell('A1').font = { bold: true, size: 14 }
  // PDF `meta` 와 같은 조립(`buildInvestPrrtReportModel`).
  main.getCell('A2').value = [
    src.regionNm ? `분석 지역 : ${src.regionNm}` : '',
    src.anlsPrdExpln ? `조회 기간 : ${src.anlsPrdExpln}` : '',
  ]
    .filter(Boolean)
    .join(' · ')
  main.getCell('A4').value = COEFF_TITLE
  main.getCell('A4').font = { bold: true }

  /* ── 계수 — 가중치(D2·D2-1) ── */
  // D2 — 위 3개가 모두 입력 셀이면 `ACCIDENT` 는 `=100-(앞 3개 합)` 수식(잠금). 아니면(D2-1 — 서버가 넷 다 N) 넷 다 조건 셀.
  const pair = INPUT_WEIGHTS.every((cd) => vrbl(cd).inptYn === 'Y')
  const firstW = 5
  const wAddrs = WEIGHT_CDS.map((_, i) => `B${firstW + i}`)
  const wNms = INPUT_WEIGHTS.map((cd) => vrbl(cd).vrblNm)
  const sum3 = `${wAddrs[0]}+${wAddrs[1]}+${wAddrs[2]}`
  const accNm = vrbl(W.a).vrblNm
  const weightRows: InputRow[] = WEIGHT_CDS.map((cd, i) => {
    const v = vrbl(cd)
    if (!pair) return conditionRow(v, HINT_WEIGHT_OFF)
    if (cd === W.a) return conditionRow(v, `자동 계산: 100 − (${wNms.join(' + ')}) (합계 100%)`)
    return inputRow(
      v,
      wAddrs[i],
      { conds: [`${sum3}<=100`], error: ` (${wNms.join('+')} 합은 100 이하 — ${accNm} 자동)` },
      i === 0 ? COEFF_SCOPE_META.INVEST.weight?.title : undefined,
    )
  })
  addInputRows(wb, main, weightRows, firstW)
  if (pair) {
    main.getCell(wAddrs[3]).value = {
      formula: `100-(${W.s}+${W.c}+${W.u})`,
      result: 100 - (vals[W.s] + vals[W.c] + vals[W.u]),
    }
  }
  const wStatusRow = firstW + WEIGHT_CDS.length
  const sumOkText = `합계 100 (${accNm} 자동)`
  const sumBadText = `${wNms.join('+')} 합이 100 을 넘었습니다 — ${accNm}(자동) 값이 음수가 되어 화면에서는 적용할 수 없습니다`
  if (pair) {
    main.getCell(`A${wStatusRow}`).value = {
      formula: `IF(${W.s}+${W.c}+${W.u}<=100,${xlStr(sumOkText)},${xlStr(sumBadText)})`,
      result: vals[W.s] + vals[W.c] + vals[W.u] <= 100 ? sumOkText : sumBadText,
    }
    // 붙여넣기로 유효성을 우회해 합이 100 을 넘으면 등급 열이 `-` 가 된다(경계 역전 D3 ② 와 같은 방어선).
    main.getCell(`A${wStatusRow}`).font = WARN_FONT
  }

  /* ── 계수 — 등급 기준(D3 2겹) ── */
  const firstG = wStatusRow + 1
  const gCds = [UP, MD]
  const gAddrs = gCds.map((_, i) => `B${firstG + i}`)
  const bndAddrs = BNDRY_CDS.map((cd) => gAddrs[gCds.indexOf(cd)])
  const gradeRows: InputRow[] = gCds.map((cd, i) => {
    const v = vrbl(cd)
    if (v.inptYn !== 'Y') return conditionRow(v, HINT_LOCKED)
    const bi = BNDRY_CDS.indexOf(cd)
    return inputRow(
      v,
      gAddrs[i],
      { conds: increasingNeighbors(bndAddrs, bi), error: ` (${vrbl(MD).vrblNm} < ${vrbl(UP).vrblNm})` },
      coeffHint(cd),
    )
  })
  addInputRows(wb, main, gradeRows, firstG)
  const bStatusRow = firstG + gCds.length
  main.getCell(`A${bStatusRow}`).value = {
    formula: `IF(NOT(${increasingAll(BNDRY_CDS)}),${xlStr(BNDRY_WARN)},"")`,
    result: calc.bndryOk ? '' : BNDRY_WARN,
  }
  main.getCell(`A${bStatusRow}`).font = WARN_FONT

  /* ── 조건(D1·D4 · n) ── */
  const condTitleRow = bStatusRow + 2
  main.getCell(`A${condTitleRow}`).value = COND_TITLE
  main.getCell(`A${condTitleRow}`).font = { bold: true }
  addInputRows(
    wb,
    main,
    [conditionRow(vrbl(ALTMNT), HINT_SPATIAL), conditionRow(vrbl(INDCT), HINT_INDCT)],
    condTitleRow + 1,
  )
  let r = condTitleRow + 3
  conditionCell(
    main,
    r++,
    '대상 주차장 수 (전 지역)',
    { formula: 'trgtCnt', result: wbk.trgtCnt },
    `전 지역 ${wbk.trgtCnt.toLocaleString('ko-KR')}곳 기준 백분위 — 행정구역을 골라도 바뀌지 않습니다`,
    '#,##0"곳"',
  )

  /* ── 시트 2 — 상수 · 주차장 행 ── */
  const K = calc.rows.length
  data.getCell('A1').value = '대상 주차장 수 n (전 지역)'
  data.getCell('B1').value = wbk.trgtCnt
  defineName(wb, sheetRef(DATA_SHEET, '$B$1'), 'trgtCnt')
  data.getCell('A2').value = '등급 경계 정상'
  data.getCell('B2').value = { formula: increasingAll(BNDRY_CDS), result: calc.bndryOk }
  defineName(wb, sheetRef(DATA_SHEET, '$B$2'), 'bndryOk')
  data.getCell('A3').value = '보고서 주차장 수'
  data.getCell('B3').value = { formula: 'COUNT(표시순번)', result: K }
  defineName(wb, sheetRef(DATA_SHEET, '$B$3'), 'lotCnt')

  const headerRow = 5
  const first = headerRow + 1
  const last = headerRow + Math.max(K, 1)
  ;(Object.keys(COL) as ColKey[]).forEach((key) => {
    styleHeader(data.getCell(`${COL[key]}${headerRow}`), DATA_HEADERS[key])
    const nm = COL_NAMES[key]
    if (nm) defineName(wb, sheetColRange(DATA_SHEET, COL[key], first, last), nm)
  })
  const baseYear = /^\d{4}/.test(src.crtYmd ?? '') ? Number(src.crtYmd.slice(0, 4)) : null
  const n1 = 'MAX(trgtCnt-1,1)'
  // 등급을 `-` 로 비우는 조건 — 경계 역전(D3 ②) · 가중치 3개 합 > 100(사고이력 음수 — 화면은 400 이라 비교 불가).
  const grdBlocked = pair ? `OR(NOT(bndryOk),${W.s}+${W.c}+${W.u}>100)` : 'NOT(bndryOk)'
  calc.rows.forEach((row, i) => {
    const n = first + i
    const at = (key: ColKey) => `${COL[key]}${n}`
    const set = (key: ColKey, v: CellValue) => {
      data.getCell(at(key)).value = v
    }
    const { lot, k } = row
    set('pkltId', lot.pkltId)
    set('lotNm', lot.pkltNm)
    set('addr', lot.pkltAddr || EMPTY)
    set('cpcty', lot.pkltCpcty ?? EMPTY)
    set('instl', instlCell(lot.instlYm, baseYear))
    set('s', row.s)
    set('kC', k.cvlcptLtCnt)
    set('kU', k.utztnLtCnt)
    set('kA', k.acdntLtCnt)
    set('scrS', Number(lot.sftyRiskScr))
    set('scrC', Number(lot.cvlcptFrqScr))
    set('scrU', Number(lot.utztnPttnScr))
    set('scrA', Number(lot.acdntScr))
    set('num', {
      formula:
        `${W.s}*${at('s')}*${n1}+100*(${W.c}*${at('kC')}+${W.u}*${at('kU')}+${W.a}*${at('kA')})`,
      result: row.num,
    })
    set('den', { formula: `${n1}*(${W.s}+${W.c}+${W.u}+${W.a})`, result: row.den })
    set('p', { formula: `ROUND(${at('num')}/${at('den')},9)`, result: row.p })
    set('grd', {
      formula:
        `IF(${grdBlocked},${xlStr(EMPTY)},IF(${at('p')}>=${UP},${xlStr(grdNm('H'))},` +
        `IF(${at('p')}>=${MD},${xlStr(grdNm('M'))},${xlStr(grdNm('L'))})))`,
      result: row.grade ? grdNm(row.grade) : EMPTY,
    })
    // D8 — 동점 항은 첫 데이터 행이면 0(범위 `P$6:P5` 가 뒤집혀 머리글이 끼는 것을 막는다).
    const tie = n === first ? '0' : `SUMPRODUCT(--($${COL.p}$${first}:$${COL.p}$${n - 1}=${at('p')}))`
    set('seq', { formula: `SUMPRODUCT(--(종합점수열>${at('p')}))+${tie}+1`, result: row.seq })
  })
  data.getColumn('A').width = 26
  data.getColumn('B').width = 30
  data.getColumn('C').width = 30
  data.getColumn('E').width = 16
  ;['J', 'K', 'L', 'M'].forEach((c) => (data.getColumn(c).numFmt = '0.000000'))
  data.getColumn('P').numFmt = '0.000000000'

  /* ── 시트 1 — 산정 기준(표 위) ── */
  // 부제 `buildWeightNote`(화면·엑셀 공용 — 🔁 TSK-4346 PDF 는 `적용 설정` 상자로 옮겼다)를 가중치 셀 참조 문장 수식으로 — 탐침 값 자리를 셀 참조로 바꾼다(`textFormula`).
  // 🔴 문장에 나오는 지표는 생성 시점 `weights` 그대로다(D10 — 0 이던 가중치를 엑셀에서 올려도 문장·열은 늘지 않는다).
  const probes: Record<string, number> = { SAFETY: 91.37, COMPLAINT: 72.13, USAGE: 53.91, ACCIDENT: 34.79 }
  const reflected = src.weights.filter((w) => w.vrblCd in probes)
  const weightLine =
    reflected.length === src.weights.length && reflected.length > 0
      ? textFormula(
          buildWeightNote(src.weights.map((w) => ({ ...w, stngVl: probes[w.vrblCd] }))),
          reflected.map(
            (w): TextSlot => ({
              probe: fmtWgvl(probes[w.vrblCd]),
              xl: xlWgvlText(w.vrblCd),
              js: fmtWgvl(vals[w.vrblCd]),
            }),
          ),
          buildWeightNote(src.weights.map((w) => (w.vrblCd in vals ? { ...w, stngVl: vals[w.vrblCd] } : w))),
        )
      : buildWeightNote(src.weights)
  const basis: CellValue[] = [
    ...(weightLine ? [weightLine] : []),
    ...(gradeCriteria.length > 0 ? [legendCell(gradeCriteria, vals[UP], vals[MD])] : []),
    PCTL_NOTE,
    DATA_NOTE,
  ]
  const basisRow = r + 1
  main.getCell(`A${basisRow}`).value = '산정 기준'
  main.getCell(`A${basisRow}`).font = { bold: true }
  basis.forEach((v, i) => {
    const cell = main.getCell(`A${basisRow + 1 + i}`)
    cell.value = v
    cell.font = NOTE_FONT
  })
  r = basisRow + basis.length + 2

  /* ── 시트 1 — 요약(D11 — `smry` 를 쓰지 않는다) ── */
  main.getCell(`A${r}`).value = '분석 결과 요약'
  main.getCell(`A${r}`).font = { bold: true }
  r++
  const LVLS: InvestPrrtLvlCd[] = ['H', 'M', 'L']
  const summary: [string, CellValue][] = [
    ['대상 주차장', { formula: 'lotCnt', result: K }],
    ...LVLS.map((cd): [string, CellValue] => [
      grdNm(cd),
      { formula: `COUNTIF(등급열,${xlStr(grdNm(cd))})`, result: calc.grdCnt[cd] },
    ]),
  ]
  summary.forEach(([label, value]) => {
    main.getCell(`A${r}`).value = label
    main.getCell(`B${r}`).value = value
    main.getCell(`B${r}`).numFmt = '#,##0"곳"'
    r++
  })
  r++

  /* ── 시트 1 — 표(D10) ── */
  main.getCell(`A${r}`).value = '투자·보강 우선순위 목록'
  main.getCell(`A${r}`).font = { bold: true }
  r++
  const tableRow = r
  type TablePick = [header: string, name: string, result: (x: InvestPrrtXlsxRow) => string | number, numFmt?: string]
  // 세부 지표 열 — 생성 시점 `weights` 중 점수 필드가 있는 것만(PDF 세부 지표 표와 같다 · D10).
  const detail: TablePick[] = src.weights
    .filter((w) => isScrVrbl(w.vrblCd) && SCR_COL[w.vrblCd])
    .map((w): TablePick => {
      const field = VRBL_SCR_FIELD[w.vrblCd as keyof typeof VRBL_SCR_FIELD]
      return [w.vrblNm, COL_NAMES[SCR_COL[w.vrblCd]] as string, (x) => Number(x.lot[field]), '0.0']
    })
  const picks: TablePick[] = [
    ['주차장', '주차장열', (x) => x.lot.pkltNm],
    ['종합점수', '종합점수열', (x) => x.p, '0.0"점"'],
    ['우선순위', '등급열', (x) => (x.grade ? grdNm(x.grade) : EMPTY)],
    ['주소', '주소열', (x) => x.lot.pkltAddr || EMPTY],
    ['면수', '면수열', (x) => x.lot.pkltCpcty ?? EMPTY, '#,##0"면"'],
    ['준공연도', '준공연도열', (x) => instlCell(x.lot.instlYm, baseYear)],
    ...detail,
  ]
  styleHeader(main.getCell(tableRow, 1), '순위')
  picks.forEach(([h], i) => styleHeader(main.getCell(tableRow, i + 2), h))
  for (let k = 1; k <= K; k++) {
    const n = tableRow + k
    const x = calc.ordered[k - 1]
    main.getCell(n, 1).value = k
    picks.forEach(([, name, res, numFmt], i) => {
      const cell = main.getCell(n, i + 2)
      cell.value = { formula: `INDEX(${name},MATCH(${k},표시순번,0))`, result: res(x) }
      if (numFmt) cell.numFmt = numFmt
    })
  }
  main.autoFilter = { from: { row: tableRow, column: 1 }, to: { row: tableRow + Math.max(K, 1), column: picks.length + 1 } }

  return finishWorkbook(wb, [main, data])
}

/** 엑셀을 만들어 `{fileBase}.xlsx` 로 내려받는다(D15 — 파일명은 `investPrrtXlsxFileBase`). */
export async function downloadInvestPrrtXlsx(src: InvestPrrtXlsxSource, fileBase?: string): Promise<void> {
  downloadXlsx(await buildInvestPrrtWorkbook(src), fileBase ?? investPrrtXlsxFileBase(src))
}
