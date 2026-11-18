import type { CellValue, Workbook } from 'exceljs'
import type { CommonCodeItem } from '../../api/commonCodes'
import type {
  ZoneRcmdtnShareCrtrVls,
  ZoneRcmdtnShareCutnCd,
  ZoneRcmdtnShareGrdCd,
  ZoneRcmdtnShareWorkbook,
  ZoneRcmdtnShareWorkbookRow,
} from '../../api/zoneRcmdtn'
import {
  NOTE_TREND_WINDOW_SHORT,
  SHARE_LEGEND_TITLE,
  SHARE_LVL_BASE,
  SHARE_NOTE_SCOPE,
  SHARE_NOTE_SCR,
  SHARE_SEC_TITLE,
  fmtDecimal,
  fmtInt,
  noteTrendBfrFallback,
  shareEmptyCandText,
  shareGrdNm,
  shareLegendRows,
  shareLvl,
  shareLvlNm,
  shareScrDesc,
  shareTrendDesc,
  shareUnableNote,
} from '../../components/analysis/zoneRcmdtnView'
import {
  PROBE_INCRS_UPLMT,
  PROBE_INCRS_WGVL,
  coeffConditionRow,
  coeffHint,
  coeffInputRow,
  displaySeq,
  incrsSlots,
  textExpr,
  vrblValue,
  zoneRcmdtnAplcnValues,
  type ZoneRcmdtnVrblValues,
} from './zoneRcmdtnFeeXlsx'
import { prdText, type ZoneRcmdtnShareReportDoc } from './zoneRcmdtnReportTemplate'
import {
  addInputRows,
  colRange as sheetColRange,
  createWorkbook,
  defineName,
  downloadXlsx,
  finishWorkbook,
  NOTE_FONT,
  sheetRef,
  styleHeader,
  textFormula,
  xlNumText,
  xlRound15,
  xlRoundDown15,
  xlStr,
  type InputRow,
  type TextSlot,
} from './xlsxKit'

/*
 * 구역추천 **공유주차제 적용** 엑셀(.xlsx) 생성기.
 *
 * 저장 응답·스냅샷의 `wbk`(표시 읍면동 원자료 · 확정 블록 건수 · 개월수 · 고정 상수 · 계수 4행)로 2장을 만든다.
 *   - 시트 1 `공유주차제 분석` — 계수 입력 4행 · 산정 기준 · 요약(보이는 목록 기준) · 표(🔴 화면 목록과 같은 **전 읍면동** · 자동 필터)
 *   - 시트 2 `계산 데이터`     — 상수 · 읍면동 1행씩(`cvlcptCnt↓ · emdCd↑` — 서버가 그 순서로 싣는다)
 *
 * 🔴 수식은 백엔드 정답지 `ZoneRcmdtnWorkbookIntegrationTest.recomputeShare` 와 같은 규칙이다 — 나눗셈 `ROUND(…,6)` ·
 *    검토 지수 `ROUNDDOWN(ROUND(x,10),6)` · 종합지수 `ROUND(S×증가계수,10)` · 경계 비교 곱 `ROUND(…,10)` ·
 *    표시순번은 지수가 있는 행끼리 같은 식 · 판정 불가(`UNABLE`)는 순번 없이 맨 뒤.
 * 🔴 도 전체 `smry` 는 쓰지 않는다 — 요약은 이 표의 행에서 센다(화면 `ZoneRcmdtnSharePanel` 과 같다).
 * 🔴 Excel 2010 호환 함수만 쓴다. ExcelJS 값 import 는 `xlsxKit` 의 동적 import 1건뿐이다.
 */

/* ── 1. 순수 재계산 (수식 셀의 result 값) ─────────────── */

const C = {
  mm: 'CVLCPT_MM_CRTR_VL',
  rt: 'CVLCPT_RT_CRTR_VL',
  wi: 'CVLCPT_INCRS_WGVL',
  up: 'CVLCPT_INCRS_RT_UPLMT_VL',
} as const

/** 읍면동 1행 — 시트 2 행 순서 그대로. */
export interface ShareXlsxRow {
  row: ZoneRcmdtnShareWorkbookRow
  mm: number
  /** 100면당 월 민원 — 면수 0 이면 `null`(`UNABLE`). */
  rt: number | null
  grd: ZoneRcmdtnShareGrdCd
  cand: boolean
  /** 검토 지수 원값 — `UNABLE` 이면 `null`. */
  s: number | null
  /** 증가율(비율) — 계산하지 않았으면 `null`. */
  g: number | null
  cfc: number
  /** 종합지수 원값 — `UNABLE` 이면 `null`. */
  p: number | null
  /** 표시순번(= 화면 `rnk`) — `UNABLE` 이면 `null`. */
  seq: number | null
  /** 표 위치(1..n — 순번 있는 행 → 판정 불가). */
  pos: number
  cutnCds: ZoneRcmdtnShareCutnCd[]
  vaclotCand: boolean
}

export interface ShareXlsxCalc {
  rows: ShareXlsxRow[]
  /** 표 순서(`pos` 순 — 서버 `DISPLAY_ORDER` 와 같다). */
  ordered: ShareXlsxRow[]
  candCnt: number
  unableCnt: number
  scoredCnt: number
}

const isCand = (grd: ZoneRcmdtnShareGrdCd) => grd === 'CORE' || grd === 'CAND'

/** 서버 `grade()` — 면수 0 → 판정 불가 · 두 기준 → 후보(둘 다 핵심 배수면 핵심) · 민원만 · 그 밖. */
function gradeOf(mm: number, rt: number | null, mmC: number, rtC: number, core: number): ZoneRcmdtnShareGrdCd {
  if (rt == null) return 'UNABLE'
  if (mm >= mmC && rt >= rtC) return mm >= mmC * core && rt >= rtC * core ? 'CORE' : 'CAND'
  return mm >= mmC ? 'CVLCPT_ONLY' : 'NONE'
}

/** `ROUNDDOWN(ROUND(x,10),6)`. */
const ratio = (a: number, b: number) => xlRoundDown15(xlRound15(a / b, 10), 6)

/** 계수 `vals` 일 때 엑셀 수식이 낼 값(정답지 `recomputeShare` 와 같은 규칙). */
export function computeShareWorkbook(
  wbk: Pick<ZoneRcmdtnShareWorkbook, 'rows' | 'months' | 'trendMode' | 'fixed'>,
  vals: ZoneRcmdtnVrblValues,
): ShareXlsxCalc {
  const v = (cd: string) => vrblValue(vals, cd)
  const { months, trendMode } = wbk
  const { coreMltp: core, bndryRt, minTrendBase } = wbk.fixed
  const mmC = v(C.mm)
  const rtC = v(C.rt)
  const wi = v(C.wi)
  const up = v(C.up)

  const rows = wbk.rows.map((row): ShareXlsxRow => {
    const mm = xlRound15(row.cvlcptCnt / months, 6)
    const rt = row.pkltCpcty <= 0 ? null : xlRound15((mm * 100) / row.pkltCpcty, 6)
    const grd = gradeOf(mm, rt, mmC, rtC, core)
    const cand = isCand(grd)
    const s = rt == null ? null : mmC <= 0 ? ratio(rt, rtC) : Math.min(ratio(mm, mmC), ratio(rt, rtC))
    const bf = row.bfrCnt
    const g =
      trendMode === 'NONE' || bf == null || bf < minTrendBase
        ? null
        : xlRound15(((row.rcntCnt ?? 0) - bf) / bf, 6)
    const cfc = g == null ? 1 : 1 + wi * Math.max(Math.min(g, up / 100), -up / 100)
    const p = s == null ? null : xlRound15(s * cfc, 10)

    const cutnCds: ZoneRcmdtnShareCutnCd[] = []
    if (cand && rt != null) {
      const mmEx = xlRound15((row.cvlcptCnt - row.topPntCvlcptCnt) / months, 6)
      const rtEx = row.pkltCpcty <= 0 ? null : xlRound15((mmEx * 100) / row.pkltCpcty, 6)
      if (!isCand(gradeOf(mmEx, rtEx, mmC, rtC, core))) cutnCds.push('TOP_PNT')
      let bndry = mm < xlRound15(mmC * bndryRt, 10) || rt < xlRound15(rtC * bndryRt, 10)
      if (!bndry && grd === 'CORE') {
        bndry = mm < xlRound15(mmC * core * bndryRt, 10) || rt < xlRound15(rtC * core * bndryRt, 10)
      }
      if (bndry) cutnCds.push('CRTR_BNDRY')
    }
    const vac = row.vaclotExclPkltCpcty
    const vaclotCand = isCand(gradeOf(mm, vac <= 0 ? null : xlRound15((mm * 100) / vac, 6), mmC, rtC, core))
    return { row, mm, rt, grd, cand, s, g, cfc, p, seq: null, pos: 0, cutnCds, vaclotCand }
  })

  const scored = rows.map((r) => r.p != null)
  const seqs = displaySeq(
    rows.map((r) => r.p ?? 0),
    scored,
  )
  const scoredCnt = scored.filter(Boolean).length
  let unableSeen = 0
  rows.forEach((r, i) => {
    if (scored[i]) {
      r.seq = seqs[i]
      r.pos = seqs[i]
    } else {
      // 지수가 없는 행 = 면수 0(`UNABLE`)뿐이다 — 행 순서대로 순번 행 뒤에 선다.
      unableSeen++
      r.pos = scoredCnt + unableSeen
    }
  })
  const ordered = [...rows].sort((a, b) => a.pos - b.pos)
  return {
    rows,
    ordered,
    candCnt: rows.filter((r) => r.cand).length,
    unableCnt: rows.filter((r) => r.grd === 'UNABLE').length,
    scoredCnt,
  }
}

/* ── 2. 통합문서 ──────────────────────────────────────── */

/**
 * 생성기 입력 — 저장 응답·스냅샷 공용. 호출부가 `share.wbk` 존재를 판정한 뒤 `{ ...report, wbk: report.share.wbk }` 로 넘긴다.
 * - `grdCodes` — 공통코드 `SHRN_RVW_GRD_CD`(검토 등급 라벨). 없으면 코드값 원문이다(화면 `shareGrdNm` 과 같은 폴백).
 * - `sggNm` — 시군구 필터. 없으면 원자료 행의 시군구가 하나뿐일 때 그것으로 본다(필터를 안 걸면 두 시군구가 섞인다).
 */
export type ZoneRcmdtnShareXlsxSource = ZoneRcmdtnShareReportDoc & {
  wbk: ZoneRcmdtnShareWorkbook
  grdCodes?: CommonCodeItem[]
  sggNm?: string | null
}

const MAIN_SHEET = '공유주차제 분석'
const DATA_SHEET = '계산 데이터'
const COEFF_TITLE = '분석 계수 조정 (노란 셀을 바꾸면 아래가 자동 재계산됩니다)'
const HINT_LOCKED = '설정에서 조정이 꺼진 계수라 적용값으로 고정됩니다'
const DATA_NOTE = `'${DATA_SHEET}' 시트는 재계산에 쓰는 원자료·상수입니다(보호됨 — 값을 고치면 화면 결과와 달라집니다).`
const SCORE_FMT = '0.00'
const PCT_FMT = '+0.0"%";-0.0"%";0.0"%"'

const COL = {
  emdCd: 'A',
  sggNm: 'B',
  emdNm: 'C',
  emdLabel: 'D',
  cnt: 'E',
  pkltCnt: 'F',
  cpcty: 'G',
  topPnt: 'H',
  vacCpcty: 'I',
  rcnt: 'J',
  bfr: 'K',
  mm: 'L',
  rt: 'M',
  grd: 'N',
  cand: 'O',
  s: 'P',
  g: 'Q',
  cfc: 'R',
  p: 'S',
  seq: 'T',
  pos: 'U',
  mmEx: 'V',
  rtEx: 'W',
  topPntYn: 'X',
  bndryYn: 'Y',
  cutn: 'Z',
  vacCand: 'AA',
  sDisp: 'AB',
  pDisp: 'AC',
  gDisp: 'AD',
  cfcDisp: 'AE',
  lvl: 'AF',
  rmk: 'AG',
  candYn: 'AH',
} as const
type ColKey = keyof typeof COL

const COL_NAMES: Partial<Record<ColKey, string>> = {
  emdLabel: '읍면동열',
  cnt: '민원열',
  grd: '등급열',
  cand: '후보열',
  p: '종합지수열',
  seq: '표시순번',
  pos: '표시위치',
  cutn: '주의열',
  sDisp: '검토지수표시',
  pDisp: '종합지수표시',
  gDisp: '증가율표시',
  lvl: '단계열',
  rmk: '비고열',
  candYn: '후보표시',
}

const DATA_HEADERS: Record<ColKey, string> = {
  emdCd: '읍면동코드',
  sggNm: '시군구',
  emdNm: '읍면동',
  emdLabel: '읍면동 표기',
  cnt: '민원 건수',
  pkltCnt: '공영주차장 수',
  cpcty: '공영 면수',
  topPnt: '최다 지점 민원',
  vacCpcty: '공한지 제외 면수',
  rcnt: '최근 블록 민원',
  bfr: '이전 블록 민원',
  mm: '월평균 민원',
  rt: '100면당 월 민원',
  grd: '검토 등급 코드',
  cand: '후보',
  s: '검토 지수',
  g: '증가율(비율)',
  cfc: '증가계수',
  p: '종합지수',
  seq: '표시순번',
  pos: '표시 위치',
  mmEx: '최다 지점 제외 월평균',
  rtEx: '최다 지점 제외 100면당',
  topPntYn: '주의 — 최다 지점',
  bndryYn: '주의 — 기준 경계',
  cutn: '주의 표시',
  vacCand: '공한지 제외 후보',
  sDisp: '검토 지수(표시)',
  pDisp: '종합지수(표시)',
  gDisp: '민원 증가율(표시 %)',
  cfcDisp: '증가계수(표시)',
  lvl: '종합지수 단계',
  rmk: '비고',
  candYn: '후보(Y/N)',
}

type ConstName = 'months' | 'trendMode' | 'coreMltp' | 'bndryRt' | 'minTrendBase' | 'candCnt' | 'unableCnt' | 'scoredCnt'
const CONST_LABELS: Record<ConstName, string> = {
  months: '조회 개월 수',
  trendMode: '증가율 블록 방식',
  coreMltp: '핵심 후보 배수',
  bndryRt: '기준 경계 주의 비율',
  minTrendBase: '증가율 분모 하한(건)',
  candCnt: '검토 후보 수',
  unableCnt: '판정 불가 수',
  scoredCnt: '순번 있는 읍면동 수',
}

/** 통합문서(.xlsx) 바이트. 🔴 계수 4행 중 하나라도 없으면 예외(호출부가 실패 문구로 처리). */
export async function buildZoneRcmdtnShareWorkbook(src: ZoneRcmdtnShareXlsxSource): Promise<ArrayBuffer> {
  const { wbk, share } = src
  const vals = zoneRcmdtnAplcnValues(wbk.vrbls)
  const calc = computeShareWorkbook(wbk, vals)
  const { coreMltp, bndryRt } = wbk.fixed
  const codes = src.grdCodes ?? []
  const grdNm = (cd: ZoneRcmdtnShareGrdCd) => shareGrdNm(codes, cd)
  const sggSet = new Set(wbk.rows.map((x) => x.sggNm))
  const sggNm = src.sggNm !== undefined ? src.sggNm : sggSet.size === 1 ? [...sggSet][0] : null
  const lvlNm = (x: number | null) => shareLvlNm(shareLvl(x, { coreMltp }))
  const cutnLabel: Record<ZoneRcmdtnShareCutnCd, string> = {
    TOP_PNT: '민원 최다 지점 1곳 의존',
    CRTR_BNDRY: `기준값 +${fmtDecimal((bndryRt - 1) * 100)}% 이내`,
  }
  const cutnText = (cds: ZoneRcmdtnShareCutnCd[]) => cds.map((c) => cutnLabel[c]).join(' · ')
  const cvlcptOnlyNm = grdNm('CVLCPT_ONLY')
  const rmkText = (x: ShareXlsxRow) => (x.grd === 'CVLCPT_ONLY' ? cvlcptOnlyNm : cutnText(x.cutnCds))

  const wb: Workbook = await createWorkbook()
  const main = wb.addWorksheet(MAIN_SHEET)
  const data = wb.addWorksheet(DATA_SHEET)

  /* ── 시트 1 상단 — 제목 · 지역/기간 · 계수 입력 ── */
  ;[30, 12, 60, 12, 12, 12, 14, 8, 16, 26].forEach((w, i) => (main.getColumn(i + 1).width = w))
  main.getCell('A1').value = SHARE_SEC_TITLE
  main.getCell('A1').font = { bold: true, size: 14 }
  main.getCell('A2').value = [src.regionNm ?? '', prdText(src)].filter(Boolean).join(' · ')
  main.getCell('A4').value = COEFF_TITLE
  main.getCell('A4').font = { bold: true }
  const firstInput = 5
  const inputRows: InputRow[] = wbk.vrbls.map((x, i) =>
    x.inptYn === 'Y'
      ? coeffInputRow(x, `B${firstInput + i}`, coeffHint('RECOMMEND_SHARE', x.vrblCd))
      : coeffConditionRow(x, HINT_LOCKED),
  )
  for (const cd of Object.values(C)) vrblValue(vals, cd)
  addInputRows(wb, main, inputRows, firstInput)
  let r = firstInput + inputRows.length + 1

  /* ── 시트 2 — 상수 · 읍면동 행 ── */
  const K = calc.rows.length
  const constNames: ConstName[] = ['months', 'trendMode', 'coreMltp', 'bndryRt', 'minTrendBase', 'candCnt', 'unableCnt', 'scoredCnt']
  const headerRow = constNames.length + 2
  const first = headerRow + 1
  const last = headerRow + Math.max(K, 1)
  const constVal = (name: ConstName): CellValue => {
    switch (name) {
      case 'months':
        return wbk.months
      case 'trendMode':
        return wbk.trendMode
      case 'coreMltp':
      case 'bndryRt':
      case 'minTrendBase':
        return wbk.fixed[name]
      case 'candCnt':
        return { formula: 'COUNTIF(후보열,TRUE)', result: calc.candCnt }
      case 'unableCnt':
        return { formula: 'COUNTIF(등급열,"UNABLE")', result: calc.unableCnt }
      case 'scoredCnt':
        return { formula: 'COUNT(표시순번)', result: calc.scoredCnt }
    }
  }
  constNames.forEach((name, i) => {
    data.getCell(`A${i + 1}`).value = CONST_LABELS[name]
    data.getCell(`B${i + 1}`).value = constVal(name)
    defineName(wb, sheetRef(DATA_SHEET, `$B$${i + 1}`), name)
  })
  ;(Object.keys(COL) as ColKey[]).forEach((key) => {
    styleHeader(data.getCell(`${COL[key]}${headerRow}`), DATA_HEADERS[key])
    const nm = COL_NAMES[key]
    if (nm) defineName(wb, sheetColRange(DATA_SHEET, COL[key], first, last), nm)
  })

  const MM = C.mm
  const RT = C.rt
  calc.rows.forEach((x, i) => {
    const n = first + i
    const at = (key: ColKey) => `${COL[key]}${n}`
    const set = (key: ColKey, v: CellValue) => {
      data.getCell(at(key)).value = v
    }
    const { row } = x
    set('emdCd', row.emdCd)
    set('sggNm', row.sggNm)
    set('emdNm', row.emdNm)
    // 표기는 PDF `emdNmOf` 와 같다(`{시군구} {읍면동}`).
    set('emdLabel', `${row.sggNm} ${row.emdNm}`.trim())
    set('cnt', row.cvlcptCnt)
    set('pkltCnt', row.pkltCnt)
    set('cpcty', row.pkltCpcty)
    set('topPnt', row.topPntCvlcptCnt)
    set('vacCpcty', row.vaclotExclPkltCpcty)
    set('rcnt', row.rcntCnt ?? '')
    set('bfr', row.bfrCnt ?? '')
    set('mm', { formula: `ROUND(${at('cnt')}/months,6)`, result: x.mm })
    set('rt', { formula: `IF(${at('cpcty')}<=0,"",ROUND(${at('mm')}*100/${at('cpcty')},6))`, result: x.rt ?? '' })
    set('grd', {
      formula:
        `IF(${at('rt')}="","UNABLE",IF(AND(${at('mm')}>=${MM},${at('rt')}>=${RT}),` +
        `IF(AND(${at('mm')}>=${MM}*coreMltp,${at('rt')}>=${RT}*coreMltp),"CORE","CAND"),` +
        `IF(${at('mm')}>=${MM},"CVLCPT_ONLY","NONE")))`,
      result: x.grd,
    })
    set('cand', { formula: `OR(${at('grd')}="CORE",${at('grd')}="CAND")`, result: x.cand })
    // 🔴 `MM` 최솟값이 0 이라 0 이면 그 항을 뺀다(`RT` 최솟값은 3).
    set('s', {
      formula:
        `IF(${at('rt')}="","",IF(${MM}<=0,ROUNDDOWN(ROUND(${at('rt')}/${RT},10),6),` +
        `MIN(ROUNDDOWN(ROUND(${at('mm')}/${MM},10),6),ROUNDDOWN(ROUND(${at('rt')}/${RT},10),6))))`,
      result: x.s ?? '',
    })
    set('g', {
      formula:
        `IF(OR(trendMode="NONE",${at('bfr')}="",${at('bfr')}<minTrendBase),"",` +
        // 최근 블록이 빈칸이면 0 — 서버·TS(`rcntCnt ?? 0`)·Java 재계산과 같다(`""-숫자` 는 `#VALUE!`).
        `ROUND((N(${at('rcnt')})-${at('bfr')})/${at('bfr')},6))`,
      result: x.g ?? '',
    })
    set('cfc', {
      formula: `IF(${at('g')}="",1,1+${C.wi}*MAX(MIN(${at('g')},${C.up}/100),-${C.up}/100))`,
      result: x.cfc,
    })
    set('p', { formula: `IF(${at('s')}="","",ROUND(${at('s')}*${at('cfc')},10))`, result: x.p ?? '' })
    // 지수가 있는 행끼리만 센다(빈 문자열은 숫자보다 크게 비교되므로 `<>""` 로 곱해 뺀다). 첫 데이터 행은 동점 항 0.
    const pr = (to: number) => `$${COL.p}$${first}:$${COL.p}$${to}`
    const tie = n === first ? '0' : `SUMPRODUCT((${pr(n - 1)}<>"")*(${pr(n - 1)}=${at('p')}))`
    set('seq', {
      formula: `IF(${at('p')}="","",SUMPRODUCT((종합지수열<>"")*(종합지수열>${at('p')}))+${tie}+1)`,
      result: x.seq ?? '',
    })
    set('pos', {
      formula: `IF(${at('seq')}<>"",${at('seq')},scoredCnt+COUNTIF($${COL.grd}$${first}:${at('grd')},"UNABLE"))`,
      result: x.pos,
    })
    const mmEx = xlRound15((row.cvlcptCnt - row.topPntCvlcptCnt) / wbk.months, 6)
    const rtEx = row.pkltCpcty <= 0 ? null : xlRound15((mmEx * 100) / row.pkltCpcty, 6)
    set('mmEx', { formula: `ROUND((${at('cnt')}-${at('topPnt')})/months,6)`, result: mmEx })
    set('rtEx', { formula: `IF(${at('cpcty')}<=0,"",ROUND(${at('mmEx')}*100/${at('cpcty')},6))`, result: rtEx ?? '' })
    set('topPntYn', {
      formula: `AND(${at('cand')},NOT(AND(${at('mmEx')}>=${MM},${at('rtEx')}>=${RT})))`,
      result: x.cutnCds.includes('TOP_PNT'),
    })
    set('bndryYn', {
      formula:
        `AND(${at('cand')},OR(${at('mm')}<ROUND(${MM}*bndryRt,10),${at('rt')}<ROUND(${RT}*bndryRt,10),` +
        `AND(${at('grd')}="CORE",OR(${at('mm')}<ROUND(${MM}*coreMltp*bndryRt,10),${at('rt')}<ROUND(${RT}*coreMltp*bndryRt,10)))))`,
      result: x.cutnCds.includes('CRTR_BNDRY'),
    })
    set('cutn', {
      formula:
        `IF(${at('topPntYn')},${xlStr(cutnLabel.TOP_PNT)},"")&IF(AND(${at('topPntYn')},${at('bndryYn')})," · ","")` +
        `&IF(${at('bndryYn')},${xlStr(cutnLabel.CRTR_BNDRY)},"")`,
      result: cutnText(x.cutnCds),
    })
    set('vacCand', {
      formula: `IF(${at('vacCpcty')}<=0,FALSE,AND(${at('mm')}>=${MM},ROUND(${at('mm')}*100/${at('vacCpcty')},6)>=${RT}))`,
      result: x.vaclotCand,
    })
    const sDisp = x.s == null ? null : xlRoundDown15(xlRound15(x.s, 10), 2)
    const pDisp = x.p == null ? null : xlRoundDown15(xlRound15(x.p, 10), 2)
    set('sDisp', { formula: `IF(${at('s')}="","",ROUNDDOWN(ROUND(${at('s')},10),2))`, result: sDisp ?? '' })
    set('pDisp', { formula: `IF(${at('p')}="","",ROUNDDOWN(ROUND(${at('p')},10),2))`, result: pDisp ?? '' })
    set('gDisp', { formula: `IF(${at('g')}="","",ROUND(${at('g')}*100,1))`, result: x.g == null ? '' : xlRound15(x.g * 100, 1) })
    set('cfcDisp', { formula: `ROUND(${at('cfc')},3)`, result: xlRound15(x.cfc, 3) })
    // 단계 — 화면 `shareZoneLvl`(표시 종합지수 기준 · `coreMltp` 이상 · 1 이상 · 그 밑). 라벨도 화면 함수에서.
    set('lvl', {
      formula:
        `IF(${at('pDisp')}="","",IF(${at('pDisp')}>=coreMltp,${xlStr(lvlNm(coreMltp))},` +
        `IF(${at('pDisp')}>=${SHARE_LVL_BASE},${xlStr(lvlNm(SHARE_LVL_BASE))},${xlStr(lvlNm(0))})))`,
      result: lvlNm(pDisp),
    })
    // 비고 — 검토 등급 열을 걷고(2026-11-11 사용자 요청) `민원 기준만 충족` 만 남겨 주의 표시와 합친다.
    // 그 등급은 후보가 아니라 주의 표시(후보에만)와 겹치지 않는다.
    set('rmk', {
      formula: `IF(${at('grd')}="CVLCPT_ONLY",${xlStr(cvlcptOnlyNm)},${at('cutn')})`,
      result: rmkText(x),
    })
    set('candYn', { formula: `IF(${at('cand')},"Y","N")`, result: x.cand ? 'Y' : 'N' })
  })
  data.getColumn('A').width = 22
  data.getColumn('D').width = 18
  ;['L', 'M', 'P', 'Q', 'R', 'S'].forEach((c) => (data.getColumn(c).numFmt = '0.000000'))

  /* ── 시트 1 — 산정 기준(표 위) ── */
  const g = (cd: string) => vrblValue(vals, cd)
  const crtrVls: ZoneRcmdtnShareCrtrVls = share.crtrVls
  const probeCrtr: ZoneRcmdtnShareCrtrVls = {
    ...crtrVls,
    cvlcptMmCrtrVl: 213.4,
    cvlcptRtCrtrVl: 31.46,
    incrsWgvl: PROBE_INCRS_WGVL,
    incrsRtUplmtVl: PROBE_INCRS_UPLMT,
  }
  const actCrtr: ZoneRcmdtnShareCrtrVls = {
    ...crtrVls,
    cvlcptMmCrtrVl: g(C.mm),
    cvlcptRtCrtrVl: g(C.rt),
    incrsWgvl: g(C.wi),
    incrsRtUplmtVl: g(C.up),
  }
  const slots: TextSlot[] = [
    { probe: fmtDecimal(213.4), xl: xlNumText(C.mm, 1), js: fmtDecimal(g(C.mm)) },
    { probe: fmtDecimal(31.46, 2), xl: xlNumText(C.rt, 2), js: fmtDecimal(g(C.rt), 2) },
    ...incrsSlots(C.wi, C.up, g(C.wi), g(C.up)),
  ]
  const zip = (probe: string[], act: string[]): CellValue[] =>
    probe.length === act.length ? act.map((line, i) => textFormula(probe[i], slots, line)) : act
  const warnings = share.warnings ?? []
  const shown = calc.candCnt > 0
  const basis: CellValue[] = [
    ...zip(shareScrDesc(probeCrtr), shareScrDesc(actCrtr)),
    ...zip(shareTrendDesc(probeCrtr), shareTrendDesc(actCrtr)),
    // 범례 — 경계는 `wbk.fixed.coreMltp`(서버 상수) · 화면 `shareLegendRows` 와 같은 줄.
    `${SHARE_LEGEND_TITLE} : ${shareLegendRows({ coreMltp })
      .map((x) => x.label)
      .join(' · ')}`,
    SHARE_NOTE_SCOPE,
    SHARE_NOTE_SCR,
    ...(shown && warnings.includes('TREND_BFR_FALLBACK') ? [noteTrendBfrFallback(crtrVls.trendDayCnt ?? null)] : []),
    ...(shown && warnings.includes('TREND_WINDOW_SHORT') ? [NOTE_TREND_WINDOW_SHORT] : []),
    DATA_NOTE,
  ]
  main.getCell(`A${r}`).value = '산정 기준'
  main.getCell(`A${r}`).font = { bold: true }
  basis.forEach((v, i) => {
    const cell = main.getCell(`A${r + 1 + i}`)
    cell.value = v
    cell.font = NOTE_FONT
  })
  r += basis.length + 2

  /* ── 시트 1 — 요약(보이는 목록 기준 — 화면과 같다) ── */
  main.getCell(`A${r}`).value = '분석 결과 요약'
  main.getCell(`A${r}`).font = { bold: true }
  r++
  const unableNm = grdNm('UNABLE')
  const summary: [string, CellValue][] = [
    ['검토 후보', { formula: 'candCnt', result: calc.candCnt }],
    [unableNm, { formula: 'unableCnt', result: calc.unableCnt }],
  ]
  summary.forEach(([label, value]) => {
    main.getCell(`A${r}`).value = label
    main.getCell(`B${r}`).value = value
    main.getCell(`B${r}`).numFmt = '#,##0"곳"'
    r++
  })
  const empty = shareEmptyCandText(sggNm)
  main.getCell(`A${r}`).value = {
    formula: `IF(candCnt=0,${xlStr(empty)},"")`,
    result: calc.candCnt === 0 ? empty : '',
  }
  main.getCell(`A${r}`).font = { bold: true }
  r++
  const unableAct = shareUnableNote(unableNm, calc.unableCnt)
  const unableTf = textFormula(
    shareUnableNote(unableNm, 913),
    [{ probe: fmtInt(913), xl: xlNumText('unableCnt', 0), js: fmtInt(calc.unableCnt) }],
    unableAct,
  )
  main.getCell(`A${r}`).value = {
    formula: `IF(unableCnt>0,${textExpr(unableTf)},"")`,
    result: calc.unableCnt > 0 ? unableAct : '',
  }
  main.getCell(`A${r}`).font = NOTE_FONT
  r += 2

  /* ── 시트 1 — 표(화면 목록과 같은 전 읍면동 · 자동 필터) ── */
  main.getCell(`A${r}`).value = '추천 읍면동'
  main.getCell(`A${r}`).font = { bold: true }
  r++
  const tableRow = r
  type TablePick = [header: string, name: string, result: (x: ShareXlsxRow) => string | number, numFmt?: string, dash?: boolean]
  const picks: TablePick[] = [
    ['순위', '표시순번', (x) => x.seq ?? '—', undefined, true],
    ['읍면동', '읍면동열', (x) => `${x.row.sggNm} ${x.row.emdNm}`.trim()],
    ['종합지수', '종합지수표시', (x) => (x.p == null ? '—' : xlRoundDown15(xlRound15(x.p, 10), 2)), SCORE_FMT, true],
    ['검토 지수', '검토지수표시', (x) => (x.s == null ? '—' : xlRoundDown15(xlRound15(x.s, 10), 2)), SCORE_FMT, true],
    ['민원 증가율', '증가율표시', (x) => (x.g == null ? '—' : xlRound15(x.g * 100, 1)), PCT_FMT, true],
    ['민원 건수', '민원열', (x) => x.row.cvlcptCnt, '#,##0"건"'],
    ['종합지수 단계', '단계열', (x) => lvlNm(x.p == null ? null : xlRoundDown15(xlRound15(x.p, 10), 2))],
    ['후보', '후보표시', (x) => (x.cand ? 'Y' : 'N')],
    ['비고', '비고열', rmkText],
  ]
  picks.forEach(([h], i) => styleHeader(main.getCell(tableRow, i + 1), h))
  for (let k = 1; k <= K; k++) {
    const n = tableRow + k
    const x = calc.ordered[k - 1]
    const m = `MATCH(${k},표시위치,0)`
    picks.forEach(([, name, res, numFmt, dash], i) => {
      const cell = main.getCell(n, i + 1)
      const ix = `INDEX(${name},${m})`
      cell.value = { formula: dash ? `IF(${ix}="","—",${ix})` : ix, result: res(x) }
      if (numFmt) cell.numFmt = numFmt
    })
  }
  if (K > 0) {
    main.autoFilter = { from: { row: tableRow, column: 1 }, to: { row: tableRow + K, column: picks.length } }
  }

  return finishWorkbook(wb, [main, data])
}

/** 엑셀을 만들어 `{fileBase}.xlsx` 로 내려받는다(파일명은 호출부가 `rptpNm`·`ZONE_RCMDTN_SHARE_FILE_BASE` 로 정한다). */
export async function downloadZoneRcmdtnShareXlsx(src: ZoneRcmdtnShareXlsxSource, fileBase: string): Promise<void> {
  downloadXlsx(await buildZoneRcmdtnShareWorkbook(src), fileBase)
}
