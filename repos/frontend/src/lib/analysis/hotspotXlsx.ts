import type { CellValue } from 'exceljs'
import type {
  GradeCriterion,
  HotspotGrade,
  HotspotWorkbook,
  HotspotWorkbookZone,
} from '../../api/analysis'
import { describeGradeRanks, GRADE_LABEL } from '../../components/analysis/hotspotGrade'
import { zoneDetail } from './hotspotExport'
import {
  SCORE_BASIS_CLUSTER,
  SCORE_BASIS_LIST_MAX,
  type HotspotReportDoc,
} from './hotspotReportTemplate'
import {
  colRange as sheetColRange,
  createWorkbook,
  defineName,
  downloadXlsx,
  finishWorkbook,
  HINT_FONT,
  NOTE_FONT,
  setCustomValidation,
  sheetRef,
  styleHeader,
  styleInput,
  xlRound,
  xlStr,
} from './xlsxKit'

/** `xlRound` 는 `xlsxKit` 으로 옮겼다(TSK-4340 Step 5) — 기존 import 경로 호환용 재export. */
export { xlRound }

/*
 * 집중 구역 순위 엑셀(.xlsx) 생성기 (TSK-4338 Step 4).
 *
 * 저장 응답(`HotspotReportResponse`)·스냅샷(`HotspotReportDoc`)의 `wbk`(후보 구역 + 상수)로 통합문서 2장을 만든다.
 *   - 시트 1 `집중구역 분석` — 참고 엑셀(`docs/report/불법주차_집중구역_순위.xlsx`) 양식. B3(민원 가중치)만 입력 가능.
 *   - 시트 2 `구역 데이터`  — 계산용. 후보 구역 1행씩 + 상수 + 가중치별 모집단 표. 정의된 이름으로 참조한다.
 *
 * 🔴 엑셀 수식은 서버 산식(`maxed`→`wgted`→`scored`→`ranked`→`top`)을 그대로 재현한다(계획서 3-2).
 *    B3 를 바꾸면 화면에서 같은 가중치로 다시 실행한 결과와 같아야 한다. 백엔드 정답지는
 *    `HotspotWorkbookIntegrationTest`(Java double 재계산) — 이 파일의 `computeHotspotWorkbook` 과 같은 규칙이다.
 * 🔴 Excel 2010 호환 함수만 쓴다(INDEX·MATCH·COUNTIF(S)·ROUNDUP·ROUND·IF·AND·NOT·ABS·ISNUMBER·MAX).
 * 🔴 시트명에 공백이 있어 시트를 넘는 참조는 작은따옴표로 감싼다(`'집중구역 분석'!$B$3`).
 * 🔴 ExcelJS 는 동적 import 한다 — 메인 번들에 넣지 않는다(로드·서식·보호·다운로드는 공용 `xlsxKit`, TSK-4340 Step 5).
 */

/* ── 1. 순수 재계산 (수식 셀의 result 값) ─────────────── */

/** 종합점수 — `ROUND(ROUND(IF(max=0,0,w/max)*100, 9), 2)` (SQL `scored`). */
export const hotspotScore = (wsum: number, max: number): number =>
  xlRound(xlRound((max === 0 ? 0 : wsum / max) * 100, 9), 2)

/** 등급 기준 순위 — `ROUNDUP(ROUND(N*p/100, 6), 0)` (SQL numeric `CEIL`). */
export const hotspotCutoff = (n: number, p: number): number => Math.ceil(xlRound((n * p) / 100, 6))

/** 등급 비율 3개(심각·경고·주의 — 상위 %)와 라벨. `gradeCriteria` 에서 뽑는다(D6). */
export interface GradePcts {
  pct: [number, number, number]
  label: [string, string, string]
}

const SHOWN_GRADES: HotspotGrade[] = ['SEVERE', 'WARN', 'CAUTION']

export function gradePcts(criteria: GradeCriterion[] | null | undefined): GradePcts {
  const found = SHOWN_GRADES.map((g) => criteria?.find((c) => c.grade === g))
  if (found.some((c) => !c)) {
    throw new Error('등급 기준(gradeCriteria)이 없어 엑셀을 만들 수 없습니다.')
  }
  const [s, w, c] = found as GradeCriterion[]
  return {
    pct: [s.maxRankPct, w.maxRankPct, c.maxRankPct],
    label: [s, w, c].map((x) => x.gradeNm || GRADE_LABEL[x.grade]) as [string, string, string],
  }
}

/** 후보 구역 1건의 재계산 결과. 각 칸이 데이터 시트의 열 1개에 대응한다. */
export interface HotspotXlsxRow {
  zone: HotspotWorkbookZone
  /** 민원비 = 민원 / maxCv (max 0 이면 0) */
  cvlcptRt: number
  /** 단속비 = 단속 / maxCd */
  crdnRt: number
  /** 가중합 = w·민원비 + (1−w)·단속비 */
  wsum: number
  /** 종합점수 (소수 2자리) */
  snthsScr: number
  /** 점수순위 — 동점 동순위 (`RANK`) */
  scrRnk: number
  /** 표시 여부 — 점수 > 0 · 점수순위 ≤ T3 */
  shown: boolean
  /** 등급명 — 표시 대상이 아니면 `''` */
  gradeNm: string
  /** 표시 순번 — 점수 desc → 민원 desc → cid asc 고유 순번(전 후보 대상) */
  dispSeq: number
}

export interface HotspotXlsxCalc {
  /** 0점 제외 군집 수 N */
  popN: number
  /** 적용 가중치 행의 N (B3 초기값이 비정수일 때 쓰는 값) */
  popNApld: number
  /** 기준 순위 T1·T2·T3 */
  cutoffs: [number, number, number]
  /** 표시 대상(주의 이상) 수 */
  dispCnt: number
  /** 후보 구역 — `wbk.zones` 순서 그대로 */
  rows: HotspotXlsxRow[]
}

/**
 * B3 = `b3`(비율, 0.6) 일 때 엑셀 수식이 낼 값을 계산한다 — 계획서 3-2 의 순서·반올림 그대로.
 * 🔴 `scrCnts` 에 필요한 행이 없으면 예외 — 엑셀에서도 `#N/A` 가 시트 전체로 번진다.
 */
export function computeHotspotWorkbook(
  wbk: HotspotWorkbook,
  grades: GradePcts,
  b3: number,
): HotspotXlsxCalc {
  const apld = wbk.scrCnts.find((r) => r.cvlcptWgvl === wbk.cvlcptWgvl)
  if (!apld) throw new Error(`scrCnts 에 적용 가중치 ${wbk.cvlcptWgvl}% 행이 없습니다.`)
  const pct100 = b3 * 100
  const pctInt = xlRound(pct100, 0)
  let popN = apld.scrCnt
  if (Math.abs(pct100 - pctInt) < 1e-9) {
    const hit = wbk.scrCnts.find((r) => r.cvlcptWgvl === pctInt)
    if (!hit) throw new Error(`scrCnts 에 가중치 ${pctInt}% 행이 없습니다.`)
    popN = hit.scrCnt
  }
  const cutoffs = grades.pct.map((p) => hotspotCutoff(popN, p)) as [number, number, number]

  const maxCv = wbk.maxCvlcptCnt
  const maxCd = wbk.maxCrdnCnt
  const base = wbk.zones.map((zone) => {
    const cvlcptRt = maxCv === 0 ? 0 : zone.cvlcptCnt / maxCv
    const crdnRt = maxCd === 0 ? 0 : zone.crdnCnt / maxCd
    return { zone, cvlcptRt, crdnRt, wsum: b3 * cvlcptRt + (1 - b3) * crdnRt }
  })
  const maxW = base.reduce((m, r) => Math.max(m, r.wsum), 0)
  const scored = base.map((r) => ({ ...r, snthsScr: hotspotScore(r.wsum, maxW) }))

  const rows: HotspotXlsxRow[] = scored.map((r) => {
    const scrRnk = scored.filter((o) => o.snthsScr > r.snthsScr).length + 1
    const shown = r.snthsScr > 0 && scrRnk <= cutoffs[2]
    const dispSeq =
      scored.filter(
        (o) =>
          o.snthsScr > r.snthsScr ||
          (o.snthsScr === r.snthsScr && o.zone.cvlcptCnt > r.zone.cvlcptCnt) ||
          (o.snthsScr === r.snthsScr &&
            o.zone.cvlcptCnt === r.zone.cvlcptCnt &&
            o.zone.cid < r.zone.cid),
      ).length + 1
    const gradeNm = !shown
      ? ''
      : scrRnk <= cutoffs[0]
        ? grades.label[0]
        : scrRnk <= cutoffs[1]
          ? grades.label[1]
          : grades.label[2]
    return { ...r, scrRnk, shown, gradeNm, dispSeq }
  })
  return { popN, popNApld: apld.scrCnt, cutoffs, dispCnt: rows.filter((r) => r.shown).length, rows }
}

/* ── 2. 통합문서 ──────────────────────────────────────── */

/** 생성기 입력 — 저장 응답·스냅샷 공용. 호출부가 `wbk` 존재를 판정한 뒤 `{ ...res, wbk: res.wbk }` 로 넘긴다. */
export type HotspotXlsxSource = Pick<
  HotspotReportDoc,
  'rptpNm' | 'regionNm' | 'anlsPrdExpln' | 'weights' | 'gradeCriteria'
> & { wbk: HotspotWorkbook }

const MAIN_SHEET = '집중구역 분석'
const DATA_SHEET = '구역 데이터'
/** 시트 1 B3 — 데이터 시트 수식에서 쓰는 절대 참조(작은따옴표 필수). */
const B3_REF = sheetRef(MAIN_SHEET, '$B$3')

/** 저장 실패·`rptpNm` 없음일 때의 파일명 — `hotspotExport` 의 `FALLBACK_FILE_BASE` 와 같은 값. */
const FALLBACK_FILE_BASE = '불법주차_집중구역_순위'

/** 파일명(확장자 제외) — 응답 `rptpNm`(D10), 없으면 `불법주차_집중구역_순위`. */
export function hotspotXlsxFileBase(src: { rptpNm?: string }): string {
  return src.rptpNm?.trim() || FALLBACK_FILE_BASE
}

const C3_TEXT = '← 이 셀을 바꾸면 종합점수·등급·순위가 자동 재계산됩니다 (0~100%)'
const C3_TEXT_LOCKED = '가중치 조정이 꺼진 설정이라 고정값입니다'
const B3_ERROR = '0~100% 사이의 정수 퍼센트를 입력하세요'
const HEADERS = ['순위', '구역', '민원 점수', '단속 점수', '종합점수', '등급', '세부 내역']
const MAIN_WIDTHS = [8, 24, 12, 12, 12, 10, 34]

/**
 * `점수 산정 기준` 4줄(D9). 1·2줄은 `hotspotReportTemplate` 의 상수(`SCORE_BASIS_CLUSTER`·`SCORE_BASIS_LIST_MAX`)를
 * 그대로 쓴다 — 문서와 엑셀 문구가 갈리지 않는다. 3줄은 `describeGradeRanks()`, 4줄은 데이터 시트 설명.
 */
function scoreBasisLines(criteria: GradeCriterion[]): string[] {
  return [
    SCORE_BASIS_CLUSTER,
    SCORE_BASIS_LIST_MAX,
    `등급 : 점수 순위 ${describeGradeRanks(criteria)} — 주의 이상 구역만 표시됩니다.`,
    `'${DATA_SHEET}' 시트는 재계산에 쓰는 후보 구역·상수입니다(보호됨 — 값을 고치면 화면 결과와 달라집니다).`,
  ]
}

/** 데이터 시트 열 배치 — 구역 행 영역. */
const COL = {
  cid: 'A',
  zoneNm: 'B',
  emdNm: 'C',
  cvlcptCnt: 'D',
  crdnCnt: 'E',
  cvlcptRt: 'F',
  crdnRt: 'G',
  wsum: 'H',
  scr: 'I',
  scrRnk: 'J',
  shown: 'K',
  grade: 'L',
  dispSeq: 'M',
  detail: 'N',
} as const

/** 열 → 정의된 이름(통합문서 범위). 시트 1 은 이 이름을 `INDEX`/`MATCH` 한다. */
const COL_NAMES: Partial<Record<keyof typeof COL, string>> = {
  cid: 'cid열',
  zoneNm: '구역명',
  cvlcptCnt: '민원건수열',
  cvlcptRt: '민원비',
  crdnRt: '단속비',
  wsum: '가중합열',
  scr: '종합점수열',
  scrRnk: '점수순위',
  shown: '표시여부',
  grade: '등급',
  dispSeq: '표시순번',
  detail: '세부내역',
}

const DATA_HEADERS: Record<keyof typeof COL, string> = {
  cid: 'cid',
  zoneNm: '구역명',
  emdNm: '읍면동',
  cvlcptCnt: '민원 건수',
  crdnCnt: '단속 건수',
  cvlcptRt: '민원비',
  crdnRt: '단속비',
  wsum: '가중합',
  scr: '종합점수',
  scrRnk: '점수순위',
  shown: '표시여부',
  grade: '등급',
  dispSeq: '표시순번',
  detail: '세부 내역',
}

/**
 * 상수 영역 `A1:B11`(라벨 · 값). 🔴 계획서 이름 `p1~p3`·`T1~T3` 은 셀 주소(P1·T1)와 겹쳐 정의된 이름이 될 수 없어
 * `grdPct1~3`·`grdRnk1~3` 으로, `N` 은 함수 `N()` 과 헷갈리지 않게 `popN` 으로 쓴다.
 */
const CONST_ROWS = [
  'maxCv',
  'maxCd',
  'grdPct1',
  'grdPct2',
  'grdPct3',
  'popNApld',
  'popN',
  'grdRnk1',
  'grdRnk2',
  'grdRnk3',
  'dispCnt',
] as const
type ConstName = (typeof CONST_ROWS)[number]
const constRow = (name: ConstName) => CONST_ROWS.indexOf(name) + 1

const CONST_LABELS: Record<ConstName, string> = {
  maxCv: '민원 최대 건수',
  maxCd: '단속 최대 건수',
  grdPct1: '등급 비율 심각(상위 %)',
  grdPct2: '등급 비율 경고(상위 %)',
  grdPct3: '등급 비율 주의(상위 %)',
  popNApld: '적용 가중치 모집단',
  popN: '모집단 N(0점 제외 구역 수)',
  grdRnk1: '기준 순위 심각',
  grdRnk2: '기준 순위 경고',
  grdRnk3: '기준 순위 주의',
  dispCnt: '표시 구역 수',
}

/**
 * 통합문서(.xlsx) 바이트를 만든다. 반환은 `ArrayBuffer`(`writeBuffer()` 결과) — 다운로드는 `downloadHotspotXlsx`.
 * 🔴 `gradeCriteria` 가 없으면 예외 — 등급 비율 없이는 등급을 계산할 수 없다(호출부가 비활성/실패 처리).
 */
export async function buildHotspotWorkbook(src: HotspotXlsxSource): Promise<ArrayBuffer> {
  const { wbk } = src
  const grades = gradePcts(src.gradeCriteria)
  const criteria = src.gradeCriteria as GradeCriterion[]
  const b3 = wbk.cvlcptWgvl / 100
  const calc = computeHotspotWorkbook(wbk, grades, b3)
  // D11 — 점수 지표가 하나뿐이면 화면에서도 가중치 입력이 400 이라 B3 도 잠근다.
  const wgtLocked = src.weights.length < 2

  const wb = await createWorkbook()
  const main = wb.addWorksheet(MAIN_SHEET)
  const data = wb.addWorksheet(DATA_SHEET)

  /* ── 시트 2 — 상수 · 모집단 표 · 구역 행 ── */
  const K = wbk.zones.length
  const S = wbk.scrCnts.length
  const zoneHeaderRow = Math.max(CONST_ROWS.length, 1 + S) + 2
  const first = zoneHeaderRow + 1
  // K=0 이어도 이름이 빈 범위를 가리키지 않게 최소 1행을 잡는다(빈 셀 — 표시 구역 0).
  const last = zoneHeaderRow + Math.max(K, 1)
  const colRange = (col: string) => sheetColRange(DATA_SHEET, col, first, last)

  const constVal = (name: ConstName): CellValue => {
    switch (name) {
      case 'maxCv':
        return wbk.maxCvlcptCnt
      case 'maxCd':
        return wbk.maxCrdnCnt
      case 'grdPct1':
      case 'grdPct2':
      case 'grdPct3':
        return grades.pct[Number(name.slice(-1)) - 1]
      case 'popNApld':
        return calc.popNApld
      case 'popN':
        return {
          formula: `IF(ABS(${B3_REF}*100-ROUND(${B3_REF}*100,0))<1E-9,INDEX(scrCnt열,MATCH(ROUND(${B3_REF}*100,0),가중치열,0)),popNApld)`,
          result: calc.popN,
        }
      case 'grdRnk1':
      case 'grdRnk2':
      case 'grdRnk3': {
        const i = Number(name.slice(-1))
        return { formula: `ROUNDUP(ROUND(popN*grdPct${i}/100,6),0)`, result: calc.cutoffs[i - 1] }
      }
      case 'dispCnt':
        return { formula: 'COUNTIF(표시여부,TRUE)', result: calc.dispCnt }
    }
  }
  CONST_ROWS.forEach((name) => {
    const r = constRow(name)
    data.getCell(`A${r}`).value = CONST_LABELS[name]
    data.getCell(`B${r}`).value = constVal(name)
    defineName(wb, sheetRef(DATA_SHEET, `$B$${r}`), name)
  })

  styleHeader(data.getCell('D1'), '민원 가중치(%)')
  styleHeader(data.getCell('E1'), '0점 제외 구역 수')
  wbk.scrCnts.forEach((r, i) => {
    data.getCell(`D${i + 2}`).value = r.cvlcptWgvl
    data.getCell(`E${i + 2}`).value = r.scrCnt
  })
  defineName(wb, sheetColRange(DATA_SHEET, 'D', 2, 1 + S), '가중치열')
  defineName(wb, sheetColRange(DATA_SHEET, 'E', 2, 1 + S), 'scrCnt열')

  ;(Object.keys(COL) as (keyof typeof COL)[]).forEach((key) => {
    styleHeader(data.getCell(`${COL[key]}${zoneHeaderRow}`), DATA_HEADERS[key])
    const nm = COL_NAMES[key]
    if (nm) defineName(wb, colRange(COL[key]), nm)
  })

  calc.rows.forEach((r, i) => {
    const n = first + i
    const at = (key: keyof typeof COL) => `${COL[key]}${n}`
    const set = (key: keyof typeof COL, v: CellValue) => {
      data.getCell(at(key)).value = v
    }
    set('cid', r.zone.cid)
    set('zoneNm', r.zone.zoneNm)
    set('emdNm', r.zone.emdNm ?? '')
    set('cvlcptCnt', r.zone.cvlcptCnt)
    set('crdnCnt', r.zone.crdnCnt)
    set('cvlcptRt', { formula: `IF(maxCv=0,0,${at('cvlcptCnt')}/maxCv)`, result: r.cvlcptRt })
    set('crdnRt', { formula: `IF(maxCd=0,0,${at('crdnCnt')}/maxCd)`, result: r.crdnRt })
    set('wsum', {
      formula: `${B3_REF}*${at('cvlcptRt')}+(1-${B3_REF})*${at('crdnRt')}`,
      result: r.wsum,
    })
    set('scr', {
      formula: `ROUND(ROUND(IF(MAX(가중합열)=0,0,${at('wsum')}/MAX(가중합열))*100,9),2)`,
      result: r.snthsScr,
    })
    set('scrRnk', { formula: `COUNTIF(종합점수열,">"&${at('scr')})+1`, result: r.scrRnk })
    set('shown', { formula: `AND(${at('scr')}>0,${at('scrRnk')}<=grdRnk3)`, result: r.shown })
    set('grade', {
      formula:
        `IF(NOT(${at('shown')}),"",IF(${at('scrRnk')}<=grdRnk1,${xlStr(grades.label[0])},` +
        `IF(${at('scrRnk')}<=grdRnk2,${xlStr(grades.label[1])},${xlStr(grades.label[2])})))`,
      result: r.gradeNm,
    })
    set('dispSeq', {
      formula:
        `COUNTIF(종합점수열,">"&${at('scr')})` +
        `+COUNTIFS(종합점수열,${at('scr')},민원건수열,">"&${at('cvlcptCnt')})` +
        `+COUNTIFS(종합점수열,${at('scr')},민원건수열,${at('cvlcptCnt')},cid열,"<"&${at('cid')})+1`,
      result: r.dispSeq,
    })
    set('detail', zoneDetail(r.zone))
  })

  data.getColumn('A').width = 26
  data.getColumn('B').width = 24
  data.getColumn('C').width = 12
  data.getColumn('D').width = 14
  data.getColumn('E').width = 16
  data.getColumn('N').width = 34
  ;['F', 'G', 'H'].forEach((c) => (data.getColumn(c).numFmt = '0.0000'))
  data.getColumn('I').numFmt = '0.00'

  /* ── 시트 1 — 참고 엑셀 양식 ── */
  MAIN_WIDTHS.forEach((w, i) => (main.getColumn(i + 1).width = w))
  main.getCell('A1').value = '불법 주차 집중 구역 순위'
  main.getCell('A1').font = { bold: true, size: 14 }
  const a2 = [
    src.anlsPrdExpln ? `분석 기간 : ${src.anlsPrdExpln}` : null,
    src.regionNm ? `분석 지역 : ${src.regionNm}` : null,
  ].filter(Boolean)
  if (a2.length) main.getCell('A2').value = a2.join(' · ')

  const pctFmt = Math.abs(wbk.cvlcptWgvl - Math.round(wbk.cvlcptWgvl)) < 1e-9 ? '0%' : '0.0#%'
  main.getCell('A3').value = '민원 건수 가중치'
  const b3Cell = main.getCell('B3')
  b3Cell.value = b3
  styleInput(b3Cell, { numFmt: pctFmt, locked: wgtLocked })
  setCustomValidation(b3Cell, {
    formula: 'AND(ISNUMBER(B3),B3>=0,B3<=1,ABS(B3*100-ROUND(B3*100,0))<1E-9)',
    title: '민원 건수 가중치',
    error: B3_ERROR,
  })
  main.getCell('C3').value = wgtLocked ? C3_TEXT_LOCKED : C3_TEXT
  main.getCell('C3').font = HINT_FONT
  main.getCell('A4').value = '단속 건수 가중치'
  main.getCell('B4').value = { formula: '1-B3', result: 1 - b3 }
  main.getCell('B4').numFmt = pctFmt

  /*
   * 점수 산정 기준은 **표 위**(가중치 아래)에 둔다 — 표는 후보 K 행을 미리 깔고 B3 에 따라 표시 수만큼만 채우므로,
   * 표 아래에 두면 비는 행(K − 표시 수)만큼 표와 멀어진다(2026-11-09 사용자 지적).
   */
  const basisLines = scoreBasisLines(criteria)
  const basisRow = 6
  main.getCell(`A${basisRow}`).value = '점수 산정 기준'
  main.getCell(`A${basisRow}`).font = { bold: true }
  basisLines.forEach((line, i) => {
    const cell = main.getCell(`A${basisRow + 1 + i}`)
    cell.value = `· ${line}`
    cell.font = NOTE_FONT
  })
  const headerRow = basisRow + basisLines.length + 2

  HEADERS.forEach((h, i) => styleHeader(main.getCell(headerRow, i + 1), h))

  const bySeq = new Map(calc.rows.map((r) => [r.dispSeq, r]))
  for (let k = 1; k <= K; k++) {
    const n = headerRow + k
    const r = k <= calc.dispCnt ? bySeq.get(k) : undefined
    const iMatch = `MATCH(${k},표시순번,0)`
    const guard = (expr: string) => `IF(${k}<=dispCnt,${expr},"")`
    main.getCell(`A${n}`).value = { formula: guard(String(k)), result: r ? k : '' }
    main.getCell(`B${n}`).value = {
      formula: guard(`INDEX(구역명,${iMatch})`),
      result: r ? r.zone.zoneNm : '',
    }
    main.getCell(`C${n}`).value = {
      formula: guard(`INDEX(민원비,${iMatch})*100`),
      result: r ? r.cvlcptRt * 100 : '',
    }
    main.getCell(`D${n}`).value = {
      formula: guard(`INDEX(단속비,${iMatch})*100`),
      result: r ? r.crdnRt * 100 : '',
    }
    main.getCell(`E${n}`).value = {
      formula: guard(`INDEX(종합점수열,${iMatch})`),
      result: r ? r.snthsScr : '',
    }
    main.getCell(`F${n}`).value = {
      formula: guard(`INDEX(등급,${iMatch})`),
      result: r ? r.gradeNm : '',
    }
    main.getCell(`G${n}`).value = {
      formula: guard(`INDEX(세부내역,${iMatch})`),
      result: r ? zoneDetail(r.zone) : '',
    }
    main.getCell(`C${n}`).numFmt = '0.0'
    main.getCell(`D${n}`).numFmt = '0.0'
    main.getCell(`E${n}`).numFmt = '0.00'
  }

  // D1 — 비밀번호 없는 보호. 🔴 protect() 는 Promise 라 두 시트 모두 await 한 뒤 쓴다(`finishWorkbook`).
  return finishWorkbook(wb, [main, data])
}

/** 엑셀을 만들어 `{rptpNm}.xlsx` 로 내려받는다(D10). 예외(등급 기준 없음 등)는 호출부가 실패 문구로 처리한다. */
export async function downloadHotspotXlsx(src: HotspotXlsxSource): Promise<void> {
  downloadXlsx(await buildHotspotWorkbook(src), hotspotXlsxFileBase(src))
}
