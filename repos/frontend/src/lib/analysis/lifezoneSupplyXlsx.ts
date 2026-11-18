import type { CellValue } from 'exceljs'
import type { LifezoneGrade, LifezoneWorkbookVrbl } from '../../api/lifezone'
import {
  supplyCellZoneNm,
  type LifezoneSupplyWorkbook,
  type LifezoneSupplyWorkbookCell,
} from '../../api/lifezoneSupply'
import { GRADE_LABEL } from '../../components/lifezone/lifezoneGrade'
import { COEFF_SCOPE_META } from './coefficientMeta'
import {
  SHRT_RT_FORMULA_TEXT,
  type ShrtRtLifezoneSupplyReportDoc,
} from './lifezoneSupplyReportTemplate'
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
 * 생활권 **주차 수급** 엑셀(.xlsx) 생성기.
 *
 * 저장 응답·신규 스냅샷의 `wbk`(N = 하한 기준 후보 격자 전부 · 서버 순위 키 순 + 조회 일수 + 계수 5행)로 2장을 만든다.
 *   - 시트 1 `주차 수급 분석` — 계수 5행(노란 입력 셀) · 상태 셀 · 산정 기준(범례 수식) · 요약 · 표(심각·경고·주의 전부)
 *   - 시트 2 `격자 데이터`   — 후보 1행씩 + 상수. 정의된 이름으로 참조한다.
 *
 * 🔴 수식은 서버 규칙을 그대로 재현한다 — 포함 `민원*30>=N*prdDays`(SQL HAVING) · 등급은 **반올림 전 정수 비교**
 *    `민원*100>=경계*수요지수`(`LifezoneGrade.ofShrtRt`) · 순위는 포함된 행만 누적 번호(`rnk` — 정렬 키가 계수와 무관해서 성립).
 *    백엔드 정답지는 `LifezoneWorkbookIntegrationTest.recomputeSupply` — 이 파일의 `computeSupplyWorkbook` 과 같은 규칙이다.
 * 🔴 Excel 2010 호환 함수만 쓴다(IF·AND·OR·NOT·COUNTIF·SUMIF·INDEX·MATCH·ROUND·ABS·ISNUMBER).
 * 🔴 ExcelJS 값 import 는 `xlsxKit` 의 동적 import 1건뿐이다 — 여기서는 `import type` 만.
 */

/* ── 1. 순수 재계산 (수식 셀의 result 값) ─────────────── */

const N_CD = 'SPLY_MIN_MNTH_AVG_CVLCPT'
/** 경계 보통·주의·경고·심각(하한 %) — 엄격 증가 묶음. */
const BNDRY_CDS = ['SPLY_GRD_BNDRY1', 'SPLY_GRD_BNDRY2', 'SPLY_GRD_BNDRY3', 'SPLY_GRD_BNDRY4'] as const

/** 등급 — 높은 쪽부터(경계 4 → 1) · 그 밖은 낮음. 라벨은 화면 `GRADE_LABEL`. */
const GRADE_BY_BNDRY: LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION', 'NORMAL']
const GRADE_ORDER: LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION', 'NORMAL', 'LOW']
const LISTED: readonly LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION']

/** 후보 격자 1행의 재계산 결과 — 각 칸이 데이터 시트의 열 1개에 대응한다. */
export interface SupplyXlsxRow {
  cell: LifezoneSupplyWorkbookCell
  /** 포함 — 월평균 민원 N건 이상 */
  included: boolean
  /** 수요지수 = 면수 + 민원 */
  dmndIdx: number
  /** 부족률 `ROUND(민원/수요지수, 4)` — 수요지수 0 이면 `null` */
  shrtRt: number | null
  /** 등급 — 포함이 아니면 `null` */
  grade: LifezoneGrade | null
  /** 전체 순위(포함 행 누적 번호) — 포함이 아니면 `null` */
  rnk: number | null
  /** 표 대상(심각·경고·주의) */
  listed: boolean
  /** 표 순번 — 표 대상이 아니면 `null` */
  dispSeq: number | null
}

export interface SupplyXlsxCalc {
  rows: SupplyXlsxRow[]
  /** 분석 격자 수(포함) */
  gridCnt: number
  /** 포함 격자 민원 합 */
  cvlcptSum: number
  gradeCnt: Record<LifezoneGrade, number>
  /** 표 대상 수 */
  dispCnt: number
}

/** 계수 맵 `vrblCd → 값`. */
export type VrblValues = Record<string, number>

/** `wbk.vrbls` 의 적용값(`aplcnVl`)을 맵으로 — 엑셀 입력 셀 초기값. */
export const aplcnValues = (vrbls: LifezoneWorkbookVrbl[]): VrblValues =>
  Object.fromEntries(vrbls.map((v) => [v.vrblCd, Number(v.aplcnVl)]))

const need = (vals: VrblValues, cd: string): number => {
  const v = vals[cd]
  if (v == null || !Number.isFinite(v)) throw new Error(`엑셀 계수 ${cd} 값이 없습니다.`)
  return v
}

/**
 * 계수 `vals` 일 때 엑셀 수식이 낼 값을 계산한다(전부 정수 비교라 부동소수 오차가 없다).
 */
export function computeSupplyWorkbook(wbk: LifezoneSupplyWorkbook, vals: VrblValues): SupplyXlsxCalc {
  const n = need(vals, N_CD)
  const bnd = BNDRY_CDS.map((cd) => need(vals, cd))
  const gradeCnt: Record<LifezoneGrade, number> = { SEVERE: 0, WARN: 0, CAUTION: 0, NORMAL: 0, LOW: 0 }
  let rnk = 0
  let dispSeq = 0
  let cvlcptSum = 0
  const rows = wbk.cells.map((cell): SupplyXlsxRow => {
    const cv = cell.cvlcptCnt
    const dmndIdx = cell.pkltCpctySum + cv
    const shrtRt = dmndIdx === 0 ? null : xlRound15(cv / dmndIdx, 4)
    const included = cv * 30 >= n * wbk.prdDays
    if (!included) {
      return { cell, included, dmndIdx, shrtRt, grade: null, rnk: null, listed: false, dispSeq: null }
    }
    let grade: LifezoneGrade = 'LOW'
    for (let i = 3; i >= 0; i--) {
      if (cv * 100 >= bnd[i] * dmndIdx) {
        grade = GRADE_BY_BNDRY[3 - i]
        break
      }
    }
    rnk++
    cvlcptSum += cv
    gradeCnt[grade]++
    const listed = LISTED.includes(grade)
    if (listed) dispSeq++
    return { cell, included, dmndIdx, shrtRt, grade, rnk, listed, dispSeq: listed ? dispSeq : null }
  })
  return { rows, gridCnt: rnk, cvlcptSum, gradeCnt, dispCnt: dispSeq }
}

/** 범례 5줄 — 서버 `LifezoneSupplyGradeCriterionVO.lgndTxt` 와 같은 문구(`심각 (80% 이상)` · `경고 (60~80%)` … `낮음 (20% 미만)`). */
export function supplyLegendLines(vals: VrblValues): string[] {
  const [b1, b2, b3, b4] = BNDRY_CDS.map((cd) => need(vals, cd))
  return [
    `${GRADE_LABEL.SEVERE} (${b4}% 이상)`,
    `${GRADE_LABEL.WARN} (${b3}~${b4}%)`,
    `${GRADE_LABEL.CAUTION} (${b2}~${b3}%)`,
    `${GRADE_LABEL.NORMAL} (${b1}~${b2}%)`,
    `${GRADE_LABEL.LOW} (${b1}% 미만)`,
  ]
}

/* ── 2. 통합문서 ──────────────────────────────────────── */

/** 생성기 입력 — 저장 응답·신규 스냅샷 공용. 호출부가 `wbk` 존재를 판정한 뒤 `{ ...res, wbk: res.wbk }` 로 넘긴다. */
export type LifezoneSupplyXlsxSource = Pick<
  ShrtRtLifezoneSupplyReportDoc,
  'regionNm' | 'anlsPrdExpln' | 'gridSzNm' | 'disclaimer'
> & { wbk: LifezoneSupplyWorkbook }

const MAIN_SHEET = '주차 수급 분석'
const DATA_SHEET = '격자 데이터'
const TITLE = '주차 수급 부족 구역'
const COEFF_TITLE = '분석 계수 조정 (이 셀을 바꾸면 아래가 자동 재계산됩니다)'
const BNDRY_WARN = '⚠ 경계는 보통 < 주의 < 경고 < 심각 이어야 합니다'
const HEADERS = ['순위', '구역', '읍면동', '민원 건수', '공영주차면(개소)', '부족률', '등급']
const MAIN_WIDTHS = [26, 26, 14, 12, 18, 10, 10]

const COL = {
  gridId: 'A',
  zoneNm: 'B',
  emdNm: 'C',
  cvlcptCnt: 'D',
  cpcty: 'E',
  pkltCnt: 'F',
  sply: 'G',
  included: 'H',
  dmndIdx: 'I',
  shrtRt: 'J',
  grade: 'K',
  rnk: 'L',
  listed: 'M',
  dispSeq: 'N',
} as const
type ColKey = keyof typeof COL

const COL_NAMES: Partial<Record<ColKey, string>> = {
  zoneNm: '구역명',
  emdNm: '읍면동',
  cvlcptCnt: '민원열',
  sply: '공급열',
  included: '포함열',
  shrtRt: '부족률열',
  grade: '등급열',
  rnk: '순위열',
  listed: '표시열',
  dispSeq: '표시순번',
}

const DATA_HEADERS: Record<ColKey, string> = {
  gridId: '격자ID',
  zoneNm: '구역명',
  emdNm: '읍면동',
  cvlcptCnt: '민원 건수',
  cpcty: '공영주차면',
  pkltCnt: '공영주차장 수',
  sply: '공영주차면(개소)',
  included: '포함',
  dmndIdx: '수요지수',
  shrtRt: '부족률',
  grade: '등급',
  rnk: '순위',
  listed: '표시',
  dispSeq: '표시순번',
}

/** 구역 표기 — 화면 `supplyCellZoneNm` 3단 폴백(구역명 → 읍면동 → 격자ID) 그대로. */
const zoneNmOf = (c: LifezoneSupplyWorkbookCell): string =>
  supplyCellZoneNm({ gridId: c.gridId, cntrPoiNm: c.cntrPoiNm, emdNm: c.emdNm ?? null })

/** 공급 표기 — `40면 (2개소)` (값 — 계수와 무관). */
const splyText = (c: LifezoneSupplyWorkbookCell): string =>
  `${c.pkltCpctySum.toLocaleString()}면 (${c.pkltCnt.toLocaleString()}개소)`

export const findVrbl = (wbk: { vrbls: LifezoneWorkbookVrbl[] }, cd: string): LifezoneWorkbookVrbl => {
  const v = wbk.vrbls.find((x) => x.vrblCd === cd)
  if (!v) throw new Error(`엑셀 계수 ${cd} 가 wbk.vrbls 에 없습니다.`)
  return v
}

/** 통합문서(.xlsx) 바이트. 🔴 계수 5행 중 하나라도 없으면 예외(호출부가 실패 문구로 처리). */
export async function buildLifezoneSupplyWorkbook(src: LifezoneSupplyXlsxSource): Promise<ArrayBuffer> {
  const { wbk } = src
  const vals = aplcnValues(wbk.vrbls)
  const calc = computeSupplyWorkbook(wbk, vals)

  const wb = await createWorkbook()
  const main = wb.addWorksheet(MAIN_SHEET)
  const data = wb.addWorksheet(DATA_SHEET)

  /* ── 시트 1 상단 — 계수 입력(정의된 이름 = vrblCd) ── */
  MAIN_WIDTHS.forEach((w, i) => (main.getColumn(i + 1).width = w))
  main.getCell('A1').value = TITLE
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

  // 순서 = 화면 팝오버(`COEFF_SCOPE_META.LIFEZONE_SPLY.coeffs`) = B4 N · B5~B8 경계 보통·주의·경고·심각
  const metas = COEFF_SCOPE_META.LIFEZONE_SPLY.coeffs
  const firstInputRow = 4
  const addrs = metas.map((_, i) => `B${firstInputRow + i}`)
  const bndAddrs = BNDRY_CDS.map((cd) => addrs[metas.findIndex((m) => m.vrblCd === cd)])
  const inputRows: InputRow[] = metas.map((m, i) => {
    const v = findVrbl(wbk, m.vrblCd)
    const bi = (BNDRY_CDS as readonly string[]).indexOf(m.vrblCd)
    const range = rangeText(v.minVl, v.maxVl)
    return {
      name: m.vrblCd,
      label: v.vrblNm,
      value: Number(v.aplcnVl),
      hint: m.hint,
      numFmt: '0',
      validation: {
        formula: numericRule(addrs[i], {
          min: v.minVl,
          max: v.maxVl,
          step: 'int',
          extra: bi >= 0 ? increasingNeighbors(bndAddrs, bi) : [],
        }),
        title: v.vrblNm,
        error:
          `${range ? `${range} 사이의 ` : ''}정수를 입력하세요` +
          (bi >= 0 ? ' (경계는 보통 < 주의 < 경고 < 심각)' : ''),
      },
    }
  })
  addInputRows(wb, main, inputRows, firstInputRow)

  const statusRow = firstInputRow + metas.length
  main.getCell(`A${statusRow}`).value = {
    formula: `IF(NOT(${increasingAll(bndAddrs)}),${xlStr(BNDRY_WARN)},"")`,
    result: BNDRY_CDS.every((cd, i) => i === 0 || vals[BNDRY_CDS[i - 1]] < vals[cd]) ? '' : BNDRY_WARN,
  }
  main.getCell(`A${statusRow}`).font = { bold: true, color: { argb: 'FFD92D20' } }

  /* ── 시트 2 — 상수 · 후보 행 ── */
  const K = wbk.cells.length
  data.getCell('A1').value = '조회 일수'
  data.getCell('B1').value = wbk.prdDays
  defineName(wb, sheetRef(DATA_SHEET, '$B$1'), 'prdDays')
  data.getCell('A2').value = '표시 격자 수'
  data.getCell('B2').value = { formula: 'COUNTIF(표시열,TRUE)', result: calc.dispCnt }
  defineName(wb, sheetRef(DATA_SHEET, '$B$2'), 'dispCnt')

  const headerRow = 4
  const first = headerRow + 1
  const last = headerRow + Math.max(K, 1)
  ;(Object.keys(COL) as ColKey[]).forEach((key) => {
    styleHeader(data.getCell(`${COL[key]}${headerRow}`), DATA_HEADERS[key])
    const nm = COL_NAMES[key]
    if (nm) defineName(wb, sheetColRange(DATA_SHEET, COL[key], first, last), nm)
  })

  const [, b2, b3, b4] = BNDRY_CDS
  calc.rows.forEach((r, i) => {
    const n = first + i
    const at = (key: ColKey) => `${COL[key]}${n}`
    const set = (key: ColKey, v: CellValue) => {
      data.getCell(at(key)).value = v
    }
    const grd = (cd: string) => `${at('cvlcptCnt')}*100>=${cd}*${at('dmndIdx')}`
    set('gridId', r.cell.gridId)
    set('zoneNm', zoneNmOf(r.cell))
    set('emdNm', r.cell.emdNm ?? '')
    set('cvlcptCnt', r.cell.cvlcptCnt)
    set('cpcty', r.cell.pkltCpctySum)
    set('pkltCnt', r.cell.pkltCnt)
    set('sply', splyText(r.cell))
    set('included', { formula: `${at('cvlcptCnt')}*30>=${N_CD}*prdDays`, result: r.included })
    set('dmndIdx', { formula: `${at('cpcty')}+${at('cvlcptCnt')}`, result: r.dmndIdx })
    set('shrtRt', {
      formula: `IF(${at('dmndIdx')}=0,"",ROUND(${at('cvlcptCnt')}/${at('dmndIdx')},4))`,
      result: r.shrtRt ?? '',
    })
    set('grade', {
      formula:
        `IF(NOT(${at('included')}),"",IF(${grd(b4)},${xlStr(GRADE_LABEL.SEVERE)},` +
        `IF(${grd(b3)},${xlStr(GRADE_LABEL.WARN)},IF(${grd(b2)},${xlStr(GRADE_LABEL.CAUTION)},` +
        `IF(${grd(BNDRY_CDS[0])},${xlStr(GRADE_LABEL.NORMAL)},${xlStr(GRADE_LABEL.LOW)})))))`,
      result: r.grade ? GRADE_LABEL[r.grade] : '',
    })
    set('rnk', {
      formula: `IF(${at('included')},COUNTIF($${COL.included}$${first}:${at('included')},TRUE),"")`,
      result: r.rnk ?? '',
    })
    set('listed', {
      formula:
        `OR(${at('grade')}=${xlStr(GRADE_LABEL.SEVERE)},${at('grade')}=${xlStr(GRADE_LABEL.WARN)},` +
        `${at('grade')}=${xlStr(GRADE_LABEL.CAUTION)})`,
      result: r.listed,
    })
    set('dispSeq', {
      formula: `IF(${at('listed')},COUNTIF($${COL.listed}$${first}:${at('listed')},TRUE),"")`,
      result: r.dispSeq ?? '',
    })
  })
  data.getColumn('A').width = 18
  data.getColumn('B').width = 26
  data.getColumn('C').width = 12
  data.getColumn('G').width = 18
  data.getColumn('J').numFmt = '0.0%'

  /* ── 시트 1 — 산정 기준(표 위) · 요약 · 표 ── */
  const legends = supplyLegendLines(vals)
  const [ln1, ln2, ln3, ln4] = BNDRY_CDS
  const legendFormulas = [
    `${xlStr(`· ${GRADE_LABEL.SEVERE} (`)}&${ln4}&"% 이상)"`,
    `${xlStr(`· ${GRADE_LABEL.WARN} (`)}&${ln3}&"~"&${ln4}&"%)"`,
    `${xlStr(`· ${GRADE_LABEL.CAUTION} (`)}&${ln2}&"~"&${ln3}&"%)"`,
    `${xlStr(`· ${GRADE_LABEL.NORMAL} (`)}&${ln1}&"~"&${ln2}&"%)"`,
    `${xlStr(`· ${GRADE_LABEL.LOW} (`)}&${ln1}&"% 미만)"`,
  ]
  const basis: CellValue[] = [
    `· ${SHRT_RT_FORMULA_TEXT}`,
    {
      formula: `"· 월평균 민원 "&${N_CD}&"건 이상 격자만 평가"`,
      result: `· 월평균 민원 ${vals[N_CD]}건 이상 격자만 평가`,
    },
    ...legendFormulas.map((f, i) => ({ formula: f, result: `· ${legends[i]}` })),
    `· ${src.disclaimer}`,
    `· '${DATA_SHEET}' 시트는 재계산에 쓰는 후보 격자·상수입니다(보호됨 — 값을 고치면 화면 결과와 달라집니다).`,
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
    ['총 분석 격자', 'COUNTIF(포함열,TRUE)', calc.gridCnt],
    ['민원 건수', 'SUMIF(포함열,TRUE,민원열)', calc.cvlcptSum],
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
  const pick: [string, string, (r: SupplyXlsxRow) => string | number][] = [
    ['A', '순위열', (r) => r.rnk ?? ''],
    ['B', '구역명', (r) => zoneNmOf(r.cell)],
    ['C', '읍면동', (r) => r.cell.emdNm ?? ''],
    ['D', '민원열', (r) => r.cell.cvlcptCnt],
    ['E', '공급열', (r) => splyText(r.cell)],
    ['F', '부족률열', (r) => r.shrtRt ?? ''],
    ['G', '등급열', (r) => (r.grade ? GRADE_LABEL[r.grade] : '')],
  ]
  for (let k = 1; k <= K; k++) {
    const n = tableRow + k
    const r = bySeq.get(k)
    pick.forEach(([col, name, val]) => {
      main.getCell(`${col}${n}`).value = {
        formula: `IF(${k}<=dispCnt,INDEX(${name},MATCH(${k},표시순번,0)),"")`,
        result: r ? val(r) : '',
      }
    })
    main.getCell(`D${n}`).numFmt = '#,##0'
    main.getCell(`F${n}`).numFmt = '0.0%'
  }

  return finishWorkbook(wb, [main, data])
}

/** 엑셀을 만들어 `{fileBase}.xlsx` 로 내려받는다(파일명은 호출부가 응답 `rptpNm`·탭 폴백으로 정한다). */
export async function downloadLifezoneSupplyXlsx(
  doc: LifezoneSupplyXlsxSource,
  fileBase: string,
): Promise<void> {
  downloadXlsx(await buildLifezoneSupplyWorkbook(doc), fileBase)
}
