import type { CellValue, Worksheet } from 'exceljs'
import type {
  LifezoneCngstWorkbook,
  LifezoneCngstWorkbookCell,
  LifezoneGrade,
  LifezoneWorkbookVrbl,
} from '../../api/lifezone'
import { formatCngstSupply, formatCngstVl, GRADE_LABEL } from '../../components/lifezone/lifezoneGrade'
import { COEFF_SCOPE_META } from './coefficientMeta'
import type { LifezoneCngstReportDoc } from './lifezoneCngstReportTemplate'
import { aplcnValues, findVrbl, type VrblValues } from './lifezoneSupplyXlsx'
import {
  addInputRows,
  colRange as sheetColRange,
  createWorkbook,
  defineName,
  downloadXlsx,
  finishWorkbook,
  increasingAll,
  increasingNeighbors,
  NOTE_FONT,
  numericRule,
  rangeText,
  sheetRef,
  styleHeader,
  xlRound15,
  xlStr,
  type InputRow,
} from './xlsxKit'

/*
 * 생활권 **혼잡도** 엑셀(.xlsx) 생성기.
 *
 * 저장 응답·신규 스냅샷의 `wbk`(공급>0 격자 전부의 원자료 · `gx, gy` 순 + 개월수 + 계수 10행)로 2장을 만든다.
 *   - 시트 1 `혼잡도 분석` — 계수 10행(노란 입력 셀) · 상태 셀 · 산정 기준(범례 수식) · 요약 · 표(심각·경고·주의 전부)
 *   - 시트 2 `격자 데이터` — 원자료 1행씩 + 상수. 정의된 이름으로 참조한다.
 *
 * 🔴 수식은 `LifezoneCngstServiceImpl.score` 와 **같은 double 연산을 같은 순서로** 한다(`pda + manual` 먼저 · `/30*GEO`).
 *    점수값 `ROUND(H*10000,0)` · 등급 경계 `ROUND(경계*100,0)` 은 엑셀 15자리 반올림(`xlRound15`)으로 재현한다.
 *    표시순번은 `SUMPRODUCT`(`COUNTIF(">"&H)` 는 15자리 문자열 비교라 double 과 어긋난다)이며
 *    🔴 동점 항은 **첫 데이터 행이면 0**(범위 `P$5:P4` 가 뒤집혀 머리글이 끼는 것을 막는다).
 *    백엔드 정답지는 `LifezoneWorkbookIntegrationTest.recomputeCngst` — 이 파일의 `computeCngstWorkbook` 과 같은 규칙이다.
 * 🔴 Excel 2010 호환 함수만 쓴다. ExcelJS 값 import 는 `xlsxKit` 의 동적 import 1건뿐이다.
 */

/**
 * 회색 조건 셀 1행(유효성 없음) — `investPrrtXlsx.ts` `conditionRow` 와 같은 꼴(그쪽은 `ZoneRcmdtnWorkbookVrbl` 을 받아 로컬에 둔다).
 * 🔴 `name` 이 입력 행과 같아 정의된 이름을 참조하는 수식은 그대로 동작한다.
 */
const conditionRow = (v: LifezoneWorkbookVrbl, hint?: string): InputRow => ({
  name: v.vrblCd,
  label: v.vrblNm,
  value: Number(v.aplcnVl),
  hint,
  condition: true,
})

/* ── 1. 순수 재계산 (수식 셀의 result 값) ─────────────── */

const OCC = 'PCSHR_CRTR_VL'
const BNDRY_CDS = ['CNGST_GRD_BNDRY1', 'CNGST_GRD_BNDRY2', 'CNGST_GRD_BNDRY3', 'CNGST_GRD_BNDRY4'] as const
const DSCL = 'DSCL_MLTP'
const FIX = 'FIX_WGVL'
const GEO = 'GEO_CVRG_CRCT_VL'
const CVW = 'CVLCPT_WGVL'
const MINCPCTY = 'MIN_CPCTY_VL'
/** 한 달 일수 — 서버 `DAYS_PER_MONTH`. */
const DAYS_PER_MONTH = 30
/** 100점 = 점수값 10000 — 서버 `CNGST_FULL_VL`. */
const FULL_VL = 10000

const GRADE_BY_BNDRY: LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION', 'NORMAL']
const GRADE_ORDER: LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION', 'NORMAL', 'LOW']
const LISTED: readonly LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION']

const need = (vals: VrblValues, cd: string): number => {
  const v = vals[cd]
  if (v == null || !Number.isFinite(v)) throw new Error(`엑셀 계수 ${cd} 값이 없습니다.`)
  return v
}

/** 원자료 1행의 재계산 결과 — 각 칸이 데이터 시트의 열 1개에 대응한다. */
export interface CngstXlsxRow {
  cell: LifezoneCngstWorkbookCell
  /** 월 단속 */
  enf: number
  /** 월 민원 */
  cvm: number
  /** 초과주차 추정량(대) */
  est: number
  /** 근거 없음 — `NOT(추정량>0)` */
  noEnf: boolean
  /** 면수 하한 미만 */
  refr: boolean
  /** 분석 대상 */
  target: boolean
  /** 혼잡 지수 H */
  h: number
  /** 점수값 `ROUND(H*10000,0)` (= 응답 `anlsVl`) */
  vl: number
  /** 등급 — 대상이 아니면 `null` */
  grade: LifezoneGrade | null
  /** 표 대상(대상 · 심각·경고·주의) */
  listed: boolean
  /** 표 순번(= 순위) — 표 대상이 아니면 `null` */
  dispSeq: number | null
  /** 세부 내역 — 화면 `formatCngstSupply` 문구 */
  detail: string
}

export interface CngstXlsxCalc {
  rows: CngstXlsxRow[]
  /** 경계 점수값 `ROUND(경계*100,0)` — 보통·주의·경고·심각 */
  bndVl: [number, number, number, number]
  /** 분석 격자 수(대상) */
  gridCnt: number
  noEnfCnt: number
  /** 면수 하한 미만(근거 없음과 겹치면 근거 없음으로 센다) */
  refrCnt: number
  /** 100점 초과(대상 중 점수값 > 10000) */
  over1Cnt: number
  gradeCnt: Record<LifezoneGrade, number>
  dispCnt: number
}

/**
 * 계수 `vals` 일 때 엑셀 수식이 낼 값을 계산한다(double · 같은 연산 순서 · 엑셀 ROUND).
 */
export function computeCngstWorkbook(wbk: LifezoneCngstWorkbook, vals: VrblValues): CngstXlsxCalc {
  const mnth = wbk.mnthCnt
  const occ = need(vals, OCC)
  const dscl = need(vals, DSCL)
  const fix = need(vals, FIX)
  const geo = need(vals, GEO)
  const cvw = need(vals, CVW)
  const minCpcty = need(vals, MINCPCTY)
  const bndVl = BNDRY_CDS.map((cd) => xlRound15(need(vals, cd) * 100, 0)) as CngstXlsxCalc['bndVl']
  const gradeCnt: Record<LifezoneGrade, number> = { SEVERE: 0, WARN: 0, CAUTION: 0, NORMAL: 0, LOW: 0 }
  let gridCnt = 0
  let noEnfCnt = 0
  let refrCnt = 0
  let over1Cnt = 0

  const base = wbk.cells.map((cell) => {
    const s = cell.pkltCpctySum
    const enf = (cell.nPda + cell.nManual + fix * cell.nFixed) / mnth
    const cvm = cell.cvlcptCnt / mnth
    const est = ((dscl * enf + cvw * cvm) / DAYS_PER_MONTH) * geo
    const noEnf = !(est > 0)
    const refr = s < minCpcty
    const target = !noEnf && !refr
    const h = noEnf ? occ : occ + est / s
    const vl = xlRound15(h * 10000, 0)
    let grade: LifezoneGrade | null = null
    if (target) {
      grade = 'LOW'
      for (let i = 3; i >= 0; i--) {
        if (vl >= bndVl[i]) {
          grade = GRADE_BY_BNDRY[3 - i]
          break
        }
      }
    }
    if (noEnf) noEnfCnt++
    else if (refr) refrCnt++
    if (target && grade) {
      gridCnt++
      gradeCnt[grade]++
      if (vl > FULL_VL) over1Cnt++
    }
    const listed = target && grade != null && LISTED.includes(grade)
    const detail = formatCngstSupply({
      pkltCpctySum: cell.pkltCpctySum,
      pkltCnt: cell.pkltCnt,
      excsPrkPrsmpVl: xlRound15(noEnf ? 0 : est, 1),
    })
    return { cell, enf, cvm, est, noEnf, refr, target, h, vl, grade, listed, detail }
  })

  let dispCnt = 0
  const rows: CngstXlsxRow[] = base.map((r, i) => {
    if (!r.listed) return { ...r, dispSeq: null }
    let greater = 0
    for (const o of base) if (o.listed && o.h > r.h) greater++
    let tie = 0 // 🔴 첫 데이터 행이면 0
    for (let j = 0; j < i; j++) if (base[j].listed && base[j].h === r.h) tie++
    dispCnt++
    return { ...r, dispSeq: greater + tie + 1 }
  })
  return { rows, bndVl, gridCnt, noEnfCnt, refrCnt, over1Cnt, gradeCnt, dispCnt }
}

/**
 * 점수값(점수 × 100 정수) → 범례 표기 — 소수 첫째 자리 반올림(0 에서 먼 쪽) · `.0` 생략.
 * 서버 `LifezoneCngstServiceImpl.formatScore` 와 같은 결과다(엑셀 수식 `scoreText` 와 같다).
 * 🔴 규칙의 정본은 화면 `formatCngstVl` 한 곳이다(화면·PDF·엑셀이 같은 함수) — 여기서 다시 구현하지 않는다.
 */
export const cngstScoreText: (v: number) => string = formatCngstVl

/** 범례 5줄 — 서버 `LifezoneGradeCriterionVO.lgndTxt`(혼잡 1.0 기준) 와 같은 문구(`심각 (100점 이상)` · `경고 (96~100점)` …). */
export function cngstLegendLines(bndVl: CngstXlsxCalc['bndVl'], unit: string): string[] {
  const [v1, v2, v3, v4] = bndVl
  const t = cngstScoreText
  return [
    `${GRADE_LABEL.SEVERE} (${t(v4)}${unit} 이상)`,
    `${GRADE_LABEL.WARN} (${t(v3)}~${t(v4 - 1)}${unit})`,
    `${GRADE_LABEL.CAUTION} (${t(v2)}~${t(v3 - 1)}${unit})`,
    `${GRADE_LABEL.NORMAL} (${t(v1)}~${t(v2 - 1)}${unit})`,
    `${GRADE_LABEL.LOW} (${t(v1)}${unit} 미만)`,
  ]
}

/* ── 2. 통합문서 ──────────────────────────────────────── */

/** 생성기 입력 — 저장 응답·신규 스냅샷 공용. 호출부가 `wbk` 존재를 판정한 뒤 `{ ...res, wbk: res.wbk }` 로 넘긴다. */
export type LifezoneCngstXlsxSource = Pick<
  LifezoneCngstReportDoc,
  'regionNm' | 'anlsPrdExpln' | 'gridSzNm' | 'sectionTitle' | 'unit'
> & { wbk: LifezoneCngstWorkbook }

const MAIN_SHEET = '혼잡도 분석'
const DATA_SHEET = '격자 데이터'
const COEFF_TITLE = '분석 계수 조정 (이 셀을 바꾸면 아래가 자동 재계산됩니다)'
const BNDRY_WARN = '⚠ 경계는 보통 < 주의 < 경고 < 심각 이어야 합니다'
const HEADERS = ['순위', '구역', '읍면동', '혼잡 점수', '등급', '세부 내역']
const MAIN_WIDTHS = [26, 26, 14, 12, 10, 44]
const NOT_OCCUPANCY =
  '혼잡 점수는 공영주차 공급 대비 추정 주차 수요이며, 실측 점유율이 아니고 도로 혼잡도가 아닙니다.'

/**
 * 혼잡 점수 칸(숫자 · `0.0`)이 정수면 `.0` 을 숨긴다 — 조건부 서식 `=AND(ISNUMBER(셀),셀=ROUND(셀,0))` → 표시 형식 `0`.
 * 값은 숫자 그대로라 정렬·필터가 유지되고 화면(`162`·`97.6`)과 같은 표기가 된다. 빈 문자열(`""`)은 ISNUMBER 가 거른다.
 */
function hideIntDecimal(ws: Worksheet, col: string, from: number, to: number): void {
  const c = `${col}${from}`
  ws.addConditionalFormatting({
    ref: `${col}${from}:${col}${to}`,
    rules: [
      { type: 'expression', priority: 1, formulae: [`AND(ISNUMBER(${c}),${c}=ROUND(${c},0))`], style: { numFmt: '0' } },
    ],
  })
}

const COL = {
  gridCd: 'A',
  zoneNm: 'B',
  emdNm: 'C',
  cpcty: 'D',
  pkltCnt: 'E',
  nFixed: 'F',
  nPda: 'G',
  nManual: 'H',
  cvlcptCnt: 'I',
  enf: 'J',
  cvm: 'K',
  est: 'L',
  noEnf: 'M',
  refr: 'N',
  target: 'O',
  h: 'P',
  vl: 'Q',
  scr: 'R',
  grade: 'S',
  listed: 'T',
  dispSeq: 'U',
  detail: 'V',
} as const
type ColKey = keyof typeof COL

const COL_NAMES: Partial<Record<ColKey, string>> = {
  zoneNm: '구역명',
  emdNm: '읍면동',
  noEnf: '근거없음열',
  refr: '하한미만열',
  target: '대상열',
  h: '지수열',
  vl: '점수값열',
  scr: '혼잡점수열',
  grade: '등급열',
  listed: '표시열',
  dispSeq: '표시순번',
  detail: '세부내역',
}

const DATA_HEADERS: Record<ColKey, string> = {
  gridCd: '격자코드',
  zoneNm: '구역명',
  emdNm: '읍면동',
  cpcty: '공영주차면',
  pkltCnt: '공영주차장 수',
  nFixed: '고정형 단속',
  nPda: 'PDA 단속',
  nManual: '수동 단속',
  cvlcptCnt: '민원 건수',
  enf: '월 단속',
  cvm: '월 민원',
  est: '초과주차 추정',
  noEnf: '근거 없음',
  refr: '면수 하한 미만',
  target: '분석 대상',
  h: '혼잡 지수 H',
  vl: '점수값',
  scr: '혼잡 점수',
  grade: '등급',
  listed: '표시',
  dispSeq: '표시순번',
  detail: '세부 내역',
}

/** 구역 표기 — 구역명 → 읍면동 → 격자코드(수급 `supplyCellZoneNm` 과 같은 3단 폴백). */
const zoneNmOf = (c: LifezoneCngstWorkbookCell): string => c.cntrPoiNm || c.emdNm || c.gridCd

/** 점수값 식 `e`(정수) → 범례 표기 수식(소수 첫째 자리 · `.0` 생략) — {@link cngstScoreText} 와 같다. */
const scoreTextFormula = (e: string): string => {
  const t = `ROUND((${e})/10,0)/10`
  return `IF(RIGHT(TEXT(${t},"0.0"),2)=".0",TEXT(${t},"0"),TEXT(${t},"0.0"))`
}

/** 통합문서(.xlsx) 바이트. 🔴 계수 10행 중 하나라도 없으면 예외(호출부가 실패 문구로 처리). */
export async function buildLifezoneCngstWorkbook(src: LifezoneCngstXlsxSource): Promise<ArrayBuffer> {
  const { wbk } = src
  const vals = aplcnValues(wbk.vrbls)
  const calc = computeCngstWorkbook(wbk, vals)

  const wb = await createWorkbook()
  const main = wb.addWorksheet(MAIN_SHEET)
  const data = wb.addWorksheet(DATA_SHEET)

  /* ── 시트 1 상단 — 계수 입력(정의된 이름 = vrblCd) ── */
  MAIN_WIDTHS.forEach((w, i) => (main.getColumn(i + 1).width = w))
  main.getCell('A1').value = src.sectionTitle
  main.getCell('A1').font = { bold: true, size: 14 }
  main.getCell('A2').value = [
    src.anlsPrdExpln ? `분석 기간 : ${src.anlsPrdExpln}` : null,
    src.regionNm ? `분석 지역 : ${src.regionNm}` : null,
    `격자 : ${src.gridSzNm}`,
  ]
    .filter(Boolean)
    .join(' · ')
  main.getCell('A3').value = COEFF_TITLE
  main.getCell('A3').font = { bold: true }

  // 순서 = 화면 팝오버(`COEFF_SCOPE_META.LIFEZONE_CNGST.coeffs`) = AnlsVrblScope.LIFEZONE_CNGST
  const metas = COEFF_SCOPE_META.LIFEZONE_CNGST.coeffs
  const firstInputRow = 4
  const addrs = metas.map((_, i) => `B${firstInputRow + i}`)
  const bndAddrs = BNDRY_CDS.map((cd) => addrs[metas.findIndex((m) => m.vrblCd === cd)])
  const r100 = (a: string) => `ROUND(${a}*100,0)`
  const inputRows: InputRow[] = metas.map((m, i) => {
    const v = findVrbl(wbk, m.vrblCd)
    // 숨김 계수(`inptYn=N`)는 회색 조건 셀로 잠근다(정의된 이름은 같아 수식은 그대로)
    if (v.inptYn !== 'Y') return conditionRow(v, m.hint)
    const bi = (BNDRY_CDS as readonly string[]).indexOf(m.vrblCd)
    const range = rangeText(v.minVl, v.maxVl)
    return {
      name: m.vrblCd,
      label: v.vrblNm,
      value: Number(v.aplcnVl),
      hint: m.hint,
      validation: {
        formula: numericRule(addrs[i], {
          min: v.minVl,
          max: v.maxVl,
          step: 'dec4',
          extra: bi >= 0 ? increasingNeighbors(bndAddrs, bi, r100) : [],
        }),
        title: v.vrblNm,
        error:
          `${range ? `${range} 사이의 ` : ''}값을 소수 넷째 자리까지 입력하세요` +
          (bi >= 0 ? ' (경계는 보통 < 주의 < 경고 < 심각)' : ''),
      },
    }
  })
  addInputRows(wb, main, inputRows, firstInputRow)

  const statusRow = firstInputRow + metas.length
  const bndOk = calc.bndVl.every((v, i) => i === 0 || calc.bndVl[i - 1] < v)
  main.getCell(`A${statusRow}`).value = {
    formula: `IF(NOT(${increasingAll(bndAddrs, r100)}),${xlStr(BNDRY_WARN)},"")`,
    result: bndOk ? '' : BNDRY_WARN,
  }
  main.getCell(`A${statusRow}`).font = { bold: true, color: { argb: 'FFD92D20' } }

  /* ── 시트 2 — 상수(A1:B13) · 원자료 행 ── */
  const legends = cngstLegendLines(calc.bndVl, src.unit)
  const consts: [string, string, CellValue][] = [
    ['개월수', 'mnthCnt', wbk.mnthCnt],
    ['표시 격자 수', 'dispCnt', { formula: 'COUNTIF(표시열,TRUE)', result: calc.dispCnt }],
    ...BNDRY_CDS.map((cd, i): [string, string, CellValue] => [
      `경계 점수값 ${i + 1}`,
      `bndVl${i + 1}`,
      { formula: `ROUND(${cd}*100,0)`, result: calc.bndVl[i] },
    ]),
    ...BNDRY_CDS.map((_, i): [string, string, CellValue] => [
      `경계 표기 ${i + 1}`,
      `bndTx${i + 1}`,
      { formula: scoreTextFormula(`bndVl${i + 1}`), result: cngstScoreText(calc.bndVl[i]) },
    ]),
    ...[2, 3, 4].map((i): [string, string, CellValue] => [
      `경계 표기 ${i} − 0.01`,
      `bndUp${i}`,
      { formula: scoreTextFormula(`bndVl${i}-1`), result: cngstScoreText(calc.bndVl[i - 1] - 1) },
    ]),
  ]
  consts.forEach(([label, name, v], i) => {
    data.getCell(`A${i + 1}`).value = label
    data.getCell(`B${i + 1}`).value = v
    defineName(wb, sheetRef(DATA_SHEET, `$B$${i + 1}`), name)
  })

  const K = wbk.cells.length
  const headerRow = consts.length + 2
  const first = headerRow + 1
  const last = headerRow + Math.max(K, 1)
  ;(Object.keys(COL) as ColKey[]).forEach((key) => {
    styleHeader(data.getCell(`${COL[key]}${headerRow}`), DATA_HEADERS[key])
    const nm = COL_NAMES[key]
    if (nm) defineName(wb, sheetColRange(DATA_SHEET, COL[key], first, last), nm)
  })

  calc.rows.forEach((r, i) => {
    const n = first + i
    const at = (key: ColKey) => `${COL[key]}${n}`
    const set = (key: ColKey, v: CellValue) => {
      data.getCell(at(key)).value = v
    }
    const label = (g: LifezoneGrade) => xlStr(GRADE_LABEL[g])
    set('gridCd', r.cell.gridCd)
    set('zoneNm', zoneNmOf(r.cell))
    set('emdNm', r.cell.emdNm ?? '')
    set('cpcty', r.cell.pkltCpctySum)
    set('pkltCnt', r.cell.pkltCnt)
    set('nFixed', r.cell.nFixed)
    set('nPda', r.cell.nPda)
    set('nManual', r.cell.nManual)
    set('cvlcptCnt', r.cell.cvlcptCnt)
    // 🔴 덧셈 순서까지 서버와 같게 — (PDA + 수동) 먼저
    set('enf', { formula: `(${at('nPda')}+${at('nManual')}+${FIX}*${at('nFixed')})/mnthCnt`, result: r.enf })
    set('cvm', { formula: `${at('cvlcptCnt')}/mnthCnt`, result: r.cvm })
    set('est', {
      formula: `(${DSCL}*${at('enf')}+${CVW}*${at('cvm')})/${DAYS_PER_MONTH}*${GEO}`,
      result: r.est,
    })
    set('noEnf', { formula: `NOT(${at('est')}>0)`, result: r.noEnf })
    set('refr', { formula: `${at('cpcty')}<${MINCPCTY}`, result: r.refr })
    set('target', { formula: `AND(NOT(${at('noEnf')}),NOT(${at('refr')}))`, result: r.target })
    set('h', { formula: `IF(${at('noEnf')},${OCC},${OCC}+${at('est')}/${at('cpcty')})`, result: r.h })
    set('vl', { formula: `ROUND(${at('h')}*10000,0)`, result: r.vl })
    set('scr', { formula: `${at('vl')}/100`, result: r.vl / 100 })
    set('grade', {
      formula:
        `IF(NOT(${at('target')}),"",IF(${at('vl')}>=bndVl4,${label('SEVERE')},` +
        `IF(${at('vl')}>=bndVl3,${label('WARN')},IF(${at('vl')}>=bndVl2,${label('CAUTION')},` +
        `IF(${at('vl')}>=bndVl1,${label('NORMAL')},${label('LOW')})))))`,
      result: r.grade ? GRADE_LABEL[r.grade] : '',
    })
    set('listed', {
      formula:
        `AND(${at('target')},OR(${at('grade')}=${label('SEVERE')},${at('grade')}=${label('WARN')},` +
        `${at('grade')}=${label('CAUTION')}))`,
      result: r.listed,
    })
    // SUMPRODUCT 로 double 원값 비교. 🔴 동점 항은 첫 데이터 행이면 0(범위 뒤집힘 방지).
    const tie =
      n === first
        ? '0'
        : `SUMPRODUCT($${COL.listed}$${first}:$${COL.listed}$${n - 1}*($${COL.h}$${first}:$${COL.h}$${n - 1}=${at('h')}))`
    set('dispSeq', {
      formula: `IF(${at('listed')},SUMPRODUCT(표시열*(지수열>${at('h')}))+${tie}+1,"")`,
      result: r.dispSeq ?? '',
    })
    // 화면 `formatCngstSupply` 와 같은 문구 — 면·개소는 천 단위 쉼표, 추정량은 쉼표 없는 소수 첫째 자리(`toFixed(1)`)
    set('detail', {
      formula:
        `"공급 "&TEXT(${at('cpcty')},"#,##0")&"면 ("&TEXT(${at('pkltCnt')},"#,##0")&"개소) · 초과주차 추정 "` +
        `&TEXT(ROUND(IF(${at('noEnf')},0,${at('est')}),1),"0.0")&"대"`,
      result: r.detail,
    })
  })
  data.getColumn('A').width = 18
  data.getColumn('B').width = 26
  data.getColumn('V').width = 44
  ;['J', 'K', 'L'].forEach((c) => (data.getColumn(c).numFmt = '0.0000'))
  data.getColumn('P').numFmt = '0.000000'
  data.getColumn('R').numFmt = '0.0'
  hideIntDecimal(data, COL.scr, first, last)

  /* ── 시트 1 — 산정 기준(표 위) · 요약 · 표 ── */
  const nm = (cd: string) => findVrbl(wbk, cd).vrblNm
  const legendFormulas = [
    `${xlStr(`· ${GRADE_LABEL.SEVERE} (`)}&bndTx4&${xlStr(`${src.unit} 이상)`)}`,
    `${xlStr(`· ${GRADE_LABEL.WARN} (`)}&bndTx3&"~"&bndUp4&${xlStr(`${src.unit})`)}`,
    `${xlStr(`· ${GRADE_LABEL.CAUTION} (`)}&bndTx2&"~"&bndUp3&${xlStr(`${src.unit})`)}`,
    `${xlStr(`· ${GRADE_LABEL.NORMAL} (`)}&bndTx1&"~"&bndUp2&${xlStr(`${src.unit})`)}`,
    `${xlStr(`· ${GRADE_LABEL.LOW} (`)}&bndTx1&${xlStr(`${src.unit} 미만)`)}`,
  ]
  const basis: CellValue[] = [
    `· 월 단속 = (PDA + 수동 + ${nm(FIX)} × 고정형) ÷ 개월수 · 월 민원 = 민원 ÷ 개월수`,
    `· 초과주차 추정 = (${nm(DSCL)} × 월 단속 + ${nm(CVW)} × 월 민원) ÷ ${DAYS_PER_MONTH} × ${nm(GEO)}`,
    `· 혼잡 지수 H = ${nm(OCC)} + 초과주차 추정 ÷ 공영주차면 (단속·민원 근거가 없으면 ${nm(OCC)})`,
    `· 혼잡 점수 = H × 100 — 근거 없음·${nm(MINCPCTY)} 미만 격자는 분석에서 제외`,
    ...legendFormulas.map((f, i) => ({ formula: f, result: `· ${legends[i]}` })),
    `· ${NOT_OCCUPANCY}`,
    `· '${DATA_SHEET}' 시트는 재계산에 쓰는 원자료·상수입니다(보호됨 — 값을 고치면 화면 결과와 달라집니다).`,
  ]
  const basisRow = statusRow + 2
  main.getCell(`A${basisRow}`).value = '산정 기준'
  main.getCell(`A${basisRow}`).font = { bold: true }
  basis.forEach((v, i) => {
    const cell = main.getCell(`A${basisRow + 1 + i}`)
    cell.value = v
    cell.font = NOTE_FONT
  })

  const sumRow = basisRow + basis.length + 2
  main.getCell(`A${sumRow}`).value = '분석 결과 요약'
  main.getCell(`A${sumRow}`).font = { bold: true }
  const summary: [string, string, number][] = [
    ['총 분석 격자', 'COUNTIF(대상열,TRUE)', calc.gridCnt],
    ['근거 없음 제외', 'COUNTIF(근거없음열,TRUE)', calc.noEnfCnt],
    ['면수 하한 미만 제외', 'COUNTIFS(근거없음열,FALSE,하한미만열,TRUE)', calc.refrCnt],
    ['100점 초과', `COUNTIFS(대상열,TRUE,점수값열,">${FULL_VL}")`, calc.over1Cnt],
    ...GRADE_ORDER.map(
      (g): [string, string, number] => [
        GRADE_LABEL[g],
        `COUNTIF(등급열,${xlStr(GRADE_LABEL[g])})`,
        calc.gradeCnt[g],
      ],
    ),
  ]
  summary.forEach(([label, formula, result], i) => {
    const n = sumRow + 1 + i
    main.getCell(`A${n}`).value = label
    main.getCell(`B${n}`).value = { formula, result }
    main.getCell(`B${n}`).numFmt = '#,##0'
  })

  const tableRow = sumRow + summary.length + 2
  HEADERS.forEach((h, i) => styleHeader(main.getCell(tableRow, i + 1), h))
  const bySeq = new Map(calc.rows.filter((r) => r.dispSeq != null).map((r) => [r.dispSeq, r]))
  const pick: [string, string, (r: CngstXlsxRow) => string | number][] = [
    ['B', '구역명', (r) => zoneNmOf(r.cell)],
    ['C', '읍면동', (r) => r.cell.emdNm ?? ''],
    ['D', '혼잡점수열', (r) => r.vl / 100],
    ['E', '등급열', (r) => (r.grade ? GRADE_LABEL[r.grade] : '')],
    ['F', '세부내역', (r) => r.detail],
  ]
  for (let k = 1; k <= K; k++) {
    const n = tableRow + k
    const r = bySeq.get(k)
    // 순위 = 표 순번(서버 `rnk = ranking.size()+1`)
    main.getCell(`A${n}`).value = { formula: `IF(${k}<=dispCnt,${k},"")`, result: r ? k : '' }
    pick.forEach(([col, name, val]) => {
      main.getCell(`${col}${n}`).value = {
        formula: `IF(${k}<=dispCnt,INDEX(${name},MATCH(${k},표시순번,0)),"")`,
        result: r ? val(r) : '',
      }
    })
    main.getCell(`D${n}`).numFmt = '0.0'
  }
  if (K > 0) hideIntDecimal(main, 'D', tableRow + 1, tableRow + K)

  return finishWorkbook(wb, [main, data])
}

/** 엑셀을 만들어 `{fileBase}.xlsx` 로 내려받는다(파일명은 호출부가 응답 `rptpNm`·탭 폴백으로 정한다). */
export async function downloadLifezoneCngstXlsx(
  doc: LifezoneCngstXlsxSource,
  fileBase: string,
): Promise<void> {
  downloadXlsx(await buildLifezoneCngstWorkbook(doc), fileBase)
}
