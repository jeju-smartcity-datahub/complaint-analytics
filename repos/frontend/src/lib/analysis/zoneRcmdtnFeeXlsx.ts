import type { CellValue, Workbook, Worksheet } from 'exceljs'
import type {
  FeeRvwGrdCd,
  ZoneRcmdtnCrtrVls,
  ZoneRcmdtnFeeWorkbook,
  ZoneRcmdtnLot,
  ZoneRcmdtnWorkbookVrbl,
} from '../../api/zoneRcmdtn'
import {
  FEE_RVW_CRTR_SET_NOTE,
  FEE_RVW_GRDS,
  FEE_RVW_LEGEND_TITLE,
  NOTE_RANK_ONLY,
  NOTE_TREND_WINDOW_SHORT,
  RANK_SEC_TITLE,
  feeRvwFormulaDesc,
  feeRvwGrdNm,
  fmtDecimal,
  fmtInt,
  lotDisplayNm,
  noteAltmntRng,
  noteAsgn,
  noteIncrsRtNull,
  noteTrendBfrFallback,
} from '../../components/analysis/zoneRcmdtnView'
import { COEFF_SCOPE_META, type CoeffScope } from './coefficientMeta'
import { prdText, type ZoneRcmdtnFeeReportDoc } from './zoneRcmdtnReportTemplate'
import {
  addInputRows,
  colRange as sheetColRange,
  createWorkbook,
  defineName,
  downloadXlsx,
  finishWorkbook,
  NOTE_FONT,
  numericRule,
  rangeText,
  sheetRef,
  styleCondition,
  styleHeader,
  textFormula,
  xlNumText,
  xlRound15,
  xlStr,
  type InputRow,
  type TextSlot,
} from './xlsxKit'

/*
 * 구역추천 **요금제 적용** 엑셀(.xlsx) 생성기 (TSK-4340 구역추천 Step 6 — 계획서 3-1).
 *
 * 저장 응답·스냅샷의 `fee.lots`(전 후보) + `wbk`(개월수 · 고정 상수 · 계수 10행)로 2장을 만든다.
 *   - 시트 1 `요금제 분석` — 계수 입력(노란 셀) · 조건(회색 · D1·D2) · 산정 기준 · 요약 · 순위표 (🔁 2026-11-11 읍면동 판정·`(참고용)` 경고·필요 면수 도달은 시트 1 에 두지 않는다)
 *   - 시트 2 `계산 데이터` — 상수 · 주차장 1행씩(D8 2차 키로 미리 정렬). 정의된 이름으로 참조한다.
 *
 * 🔴 수식은 백엔드 정답지 `ZoneRcmdtnWorkbookIntegrationTest.recomputeFee` 와 같은 규칙이다 — 나눗셈마다 `ROUND(…,6)` ·
 *    종합지수 `ROUND(검토지수×증가계수,10)` · 비교 곱 `ROUND(…,10)`(D9) · 표시순번 `SUMPRODUCT(--(P>Pᵢ))` + 앞 행 동점 수 + 1
 *    (첫 데이터 행은 동점 항 0 — D8). 이 파일의 `computeFeeWorkbook` 이 같은 규칙의 TS 재계산(수식 셀 `result`)이다.
 * 🔴 단계 경계는 `wbk.fixed.prrtTop/prrtHigh`(서버 상수)에서 읽는다 — 프론트 문구 상수를 쓰지 않는다.
 * 🔴 문구는 화면 함수를 그대로 부르고(🔁 TSK-4346 — PDF 는 숫자를 뺀 보고서 전용 변형 `*ForReport` 를 쓴다), 계수에 따라 바뀌는 문장은 `textFormula`(탐침 값 → 수식)로 다시 조립한다.
 * 🔴 Excel 2010 호환 함수만 쓴다. ExcelJS 값 import 는 `xlsxKit` 의 동적 import 1건뿐이다(여기서는 `import type` 만).
 */

/* ── 0. 계수 공용(요금제·공유주차제) ─────────────────── */

/** 계수 맵 `vrblCd → 값`. */
export type ZoneRcmdtnVrblValues = Record<string, number>

/** `wbk.vrbls` 의 적용값(`aplcnVl`) — 엑셀 입력 셀 초기값(D5). */
export const zoneRcmdtnAplcnValues = (vrbls: ZoneRcmdtnWorkbookVrbl[]): ZoneRcmdtnVrblValues =>
  Object.fromEntries(vrbls.map((v) => [v.vrblCd, Number(v.aplcnVl)]))

/** 계수 값 — 없으면 예외(호출부가 실패 문구로 처리). */
export const vrblValue = (vals: ZoneRcmdtnVrblValues, cd: string): number => {
  const v = vals[cd]
  if (v == null || !Number.isFinite(v)) throw new Error(`엑셀 계수 ${cd} 값이 없습니다.`)
  return v
}

/** C열 안내 — 화면 팝오버 메타(`coefficientMeta`)의 `hint` 를 `vrblCd` 로 찾는다(문구 복사 금지 · D4). */
export function coeffHint(scope: CoeffScope, vrblCd: string): string | undefined {
  const meta = COEFF_SCOPE_META[scope]
  return [...(meta.weight?.vars ?? []), ...meta.coeffs].find((m) => m.vrblCd === vrblCd)?.hint
}

/** 노란 입력 셀 1행 — 유효성은 범위(`minVl~maxVl`) + 소수 넷째 자리까지(D4 · 정수 전용 계수 없음). */
export function coeffInputRow(v: ZoneRcmdtnWorkbookVrbl, addr: string, hint?: string): InputRow {
  const range = rangeText(v.minVl, v.maxVl)
  return {
    name: v.vrblCd,
    label: v.vrblNm,
    value: Number(v.aplcnVl),
    hint,
    validation: {
      formula: numericRule(addr, {
        min: v.minVl,
        max: v.maxVl,
        step: v.intOnlyYn === 'Y' ? 'int' : 'dec4',
      }),
      title: v.vrblNm,
      error:
        v.intOnlyYn === 'Y'
          ? `${range ? `${range} 사이의 ` : ''}정수를 입력하세요`
          : `${range ? `${range} 사이의 ` : ''}값을 소수 넷째 자리까지 입력하세요`,
    },
  }
}

/** 회색 조건 셀 1행(잠금 · 적용값 고정) — 정의된 이름은 입력 셀과 같이 단다. */
export const coeffConditionRow = (v: ZoneRcmdtnWorkbookVrbl, hint?: string): InputRow => ({
  name: v.vrblCd,
  label: v.vrblNm,
  value: Number(v.aplcnVl),
  hint,
  condition: true,
})

/** 문장 수식 → 다른 수식 안의 식(고정 문장이면 문자열 상수). */
export const textExpr = (tf: ReturnType<typeof textFormula>): string =>
  typeof tf === 'string' ? xlStr(tf) : tf.formula

/** 탐침 값 — 문장 안 다른 글자와 겹치지 않는 숫자(`textFormula`). 증가계수 줄은 요금제·공유주차제가 같은 탐침을 쓴다. */
export const PROBE_INCRS_WGVL = 3.71
export const PROBE_INCRS_UPLMT = 831.7

/** 증가계수 범위 문장의 빈칸 4개(`w`·상한·하한·상한값) — `feeRvwFormulaDesc`·`shareTrendDesc` 가 같은 식으로 만든다. */
export function incrsSlots(wCd: string, upCd: string, w: number, up: number): TextSlot[] {
  const lo = (wv: number, uv: number) => 1 - (wv * Math.min(uv, 100)) / 100
  const hi = (wv: number, uv: number) => 1 + (wv * uv) / 100
  const pw = PROBE_INCRS_WGVL
  const pu = PROBE_INCRS_UPLMT
  return [
    { probe: fmtDecimal(lo(pw, pu), 2), xl: xlNumText(`1-(${wCd}*MIN(${upCd},100))/100`, 2), js: fmtDecimal(lo(w, up), 2) },
    { probe: fmtDecimal(hi(pw, pu), 2), xl: xlNumText(`1+(${wCd}*${upCd})/100`, 2), js: fmtDecimal(hi(w, up), 2) },
    { probe: fmtDecimal(pw, 2), xl: xlNumText(wCd, 2), js: fmtDecimal(w, 2) },
    { probe: fmtDecimal(pu), xl: xlNumText(upCd, 1), js: fmtDecimal(up) },
  ]
}

/** D8 표시순번 — `SUMPRODUCT(--(P>Pᵢ))` + 앞 행 중 동점 수 + 1. `scored` 가 주어지면 그 행끼리만 센다. */
export function displaySeq(p: readonly number[], scored?: readonly boolean[]): number[] {
  return p.map((pi, i) => {
    if (scored && !scored[i]) return 0
    let gt = 0
    let tie = 0
    for (let j = 0; j < p.length; j++) {
      if (scored && !scored[j]) continue
      if (p[j] > pi) gt++
      else if (j < i && p[j] === pi) tie++
    }
    return gt + tie + 1
  })
}

/* ── 1. 순수 재계산 (수식 셀의 result 값) ─────────────── */

const SGP = '서귀포시'
const C = {
  thrsh: 'STSF_RT_THRSH',
  mmCrtr: 'CVLCPT_MM_CRTR_VL',
  ilgl: 'ILGL_CRTR_VL',
  w1: 'FEE_CVLCPT_CNT_WGVL',
  w2: 'FEE_CVLCPT_RT_WGVL',
  mmJeju: 'FEE_CVLCPT_MM_CRTR_JEJU',
  mmSgp: 'FEE_CVLCPT_MM_CRTR_SEOGWIPO',
  rtJeju: 'FEE_CVLCPT_RT_CRTR_JEJU',
  rtSgp: 'FEE_CVLCPT_RT_CRTR_SEOGWIPO',
  wi: 'FEE_CVLCPT_INCRS_WGVL',
  up: 'FEE_CVLCPT_INCRS_RT_UPLMT_VL',
} as const
/** D1 — 공간 배정 계수(엑셀이 다시 계산할 수 없다). */
const SPATIAL_CDS: readonly string[] = ['ALTMNT_RNG_VL', 'SWTC_TRGT_MIN_CPCTY']
/** 🔁 TSK-4340 — 스코프에서 빠진 읍면동 판정 계수(구 스냅샷 `wbk.vrbls` 에서 걸러 낸다). */
const JUDGE_CDS: readonly string[] = [C.thrsh, C.mmCrtr, C.ilgl]

/** 주차장 1행 — 시트 2 행 순서(D8 2차 키) 그대로. */
export interface FeeXlsxRow {
  lot: ZoneRcmdtnLot
  sgp: boolean
  mm: number
  s1: number
  s2: number
  rvw: number
  cfc: number
  prrt: number
  grd: FeeRvwGrdCd
  /** 표시순번(= 화면 `rnk`). */
  seq: number
  /** 누적 면수 — 읍면동 모드만. */
  cum: number | null
}

export interface FeeXlsxCalc {
  emdMode: boolean
  /** 시트 2 행 순서(배정 민원↓ · 면수↓ · pkltId↑). */
  rows: FeeXlsxRow[]
  /** 표시 순서(표시순번 순). */
  ordered: FeeXlsxRow[]
  grdCnt: Record<FeeRvwGrdCd, number>
}

/** 단계 — 경계는 서버 상수(`wbk.fixed`). */
const grdOf = (p: number, fixed: ZoneRcmdtnFeeWorkbook['fixed']): FeeRvwGrdCd =>
  p >= fixed.prrtTop ? 'TOP' : p >= fixed.prrtHigh ? 'HIGH' : 'LOW'

/**
 * 계수 `vals` 일 때 엑셀 수식이 낼 값 — 계획서 3-1 시트 2 수식 그대로(정답지 `recomputeFee` 와 같은 규칙).
 * `lots` 는 응답 `fee.lots` 그대로(순서 무관 — 여기서 D8 2차 키로 다시 정렬한다).
 */
export function computeFeeWorkbook(
  lots: readonly ZoneRcmdtnLot[],
  wbk: Pick<ZoneRcmdtnFeeWorkbook, 'months' | 'fixed'>,
  vals: ZoneRcmdtnVrblValues,
  scope: ZoneRcmdtnFeeReportDoc['fee']['scope'],
): FeeXlsxCalc {
  const v = (cd: string) => vrblValue(vals, cd)
  const { months, fixed } = wbk
  // 🔴 모드는 응답 `scope` 하나로 가른다(없으면 `EMD` — 옛 스냅샷). `zoneBase` 유무로 따로 판정하면
  //    시트 1(scope 기준)과 시트 2(이름 정의)가 어긋나 `#NAME?` 이 된다.
  const emdMode = scope !== 'SGG'
  // 🔁 2026-11-11 — 읍면동별 요금제 대상 판정(E0 · 필요 면수 · 충족률 · 추천유형 · 필요 면수 도달)은 계산하지 않는다.

  const sorted = [...lots].sort(
    (a, b) => b.asgnCvlcptCnt - a.asgnCvlcptCnt || b.pkltCpcty - a.pkltCpcty || a.pkltId - b.pkltId,
  )
  const w1 = v(C.w1)
  const w2 = v(C.w2)
  const wi = v(C.wi)
  const up = v(C.up)
  const base = sorted.map((lot) => {
    const sgp = lot.sggNm === SGP
    const mm = xlRound15(lot.asgnCvlcptCnt / months, 6)
    const s1 = xlRound15((mm * 100) / (sgp ? v(C.mmSgp) : v(C.mmJeju)), 6)
    const s2 =
      lot.pkltCpcty <= 0
        ? 0
        : xlRound15((xlRound15((mm * 100) / lot.pkltCpcty, 6) * 100) / (sgp ? v(C.rtSgp) : v(C.rtJeju)), 6)
    const rvw = w1 + w2 <= 0 ? 0 : xlRound15((w1 * s1 + w2 * s2) / (w1 + w2), 6)
    const g = lot.cvlcptIncrsRt
    const cfc = g == null ? 1 : 1 + wi * Math.max(Math.min(g / 100, up / 100), -up / 100)
    const prrt = xlRound15(rvw * cfc, 10)
    return { lot, sgp, mm, s1, s2, rvw, cfc, prrt, grd: grdOf(prrt, fixed) }
  })
  const seqs = displaySeq(base.map((r) => r.prrt))
  const rows: FeeXlsxRow[] = base.map((r, i) => ({ ...r, seq: seqs[i], cum: null }))
  const ordered = [...rows].sort((a, b) => a.seq - b.seq)
  let cum = 0
  const grdCnt: Record<FeeRvwGrdCd, number> = { TOP: 0, HIGH: 0, LOW: 0 }
  ordered.forEach((r) => {
    grdCnt[r.grd]++
    if (!emdMode) return
    cum += r.lot.pkltCpcty
    r.cum = cum
  })
  return { emdMode, rows, ordered, grdCnt }
}

/* ── 2. 통합문서 ──────────────────────────────────────── */

/** 생성기 입력 — 저장 응답·스냅샷 공용. 호출부가 `fee.wbk` 존재를 판정한 뒤 `{ ...report, wbk: report.fee.wbk }` 로 넘긴다. */
export type ZoneRcmdtnFeeXlsxSource = ZoneRcmdtnFeeReportDoc & { wbk: ZoneRcmdtnFeeWorkbook }

const MAIN_SHEET = '요금제 분석'
const DATA_SHEET = '계산 데이터'
const COEFF_TITLE = '분석 계수 조정 (노란 셀을 바꾸면 아래가 자동 재계산됩니다)'
const COND_TITLE = '조건 (엑셀에서 바꿀 수 없음)'
const HINT_SPATIAL = '이 값을 바꾸려면 화면에서 다시 조회하세요 (공간 배정이라 엑셀에서 다시 계산할 수 없습니다)'
const HINT_LOCKED = '설정에서 조정이 꺼진 계수라 적용값으로 고정됩니다'
/** D3 예외 — 가중치 한쪽이 꺼지면 `=100-민원강도` 짝 수식이 꺼진 0 을 되살리므로 둘 다 고정한다. */
const HINT_WEIGHT_OFF = '가중치 한쪽이 설정에서 꺼져 있어 두 가중치를 적용값으로 고정합니다 (꺼진 쪽은 0)'
const HINT_PAIR = (cntNm: string) => `자동 계산: 100 − ${cntNm} (합계 100%)`
const DATA_NOTE = `'${DATA_SHEET}' 시트는 재계산에 쓰는 원자료·상수입니다(보호됨 — 값을 고치면 화면 결과와 달라집니다).`
/** 후보 유형 범위 표기(`trgtTypeSe` — API 문서 §0-6 의 유형 목록). */
const TRGT_TYPE_LABEL: Record<string, string> = {
  NARROW: '공영 · 노외(공영) · 복층화',
  WIDE: '공영 · 노외(공영) · 복층화 · 노상 · 노상(23년 조사) · 공한지',
}
const PCT_FMT = '+0.0"%";-0.0"%";0.0"%"'

const COL = {
  pkltId: 'A',
  lotNm: 'B',
  emdNm: 'C',
  sggNm: 'D',
  cpcty: 'E',
  asgn: 'F',
  incrsRt: 'G',
  sgp: 'H',
  mm: 'I',
  s1: 'J',
  s2: 'K',
  rvw: 'L',
  cfc: 'M',
  prrt: 'N',
  grd: 'O',
  seq: 'P',
  cum: 'Q',
  prrtDisp: 'R',
  rvwDisp: 'S',
  cfcDisp: 'T',
} as const
type ColKey = keyof typeof COL

const COL_NAMES: Partial<Record<ColKey, string>> = {
  lotNm: '주차장열',
  emdNm: '읍면동열',
  cpcty: '면수열',
  asgn: '배정민원열',
  incrsRt: '증가율열',
  prrt: '종합지수열',
  grd: '단계열',
  seq: '표시순번',
  cum: '누적면수열',
  prrtDisp: '종합지수표시',
  rvwDisp: '검토지수표시',
  cfcDisp: '증가계수표시',
}

const DATA_HEADERS: Record<ColKey, string> = {
  pkltId: '주차장ID',
  lotNm: '주차장',
  emdNm: '읍면동',
  sggNm: '시군구',
  cpcty: '면수',
  asgn: '배정 민원',
  incrsRt: '증가율(%)',
  sgp: '서귀포',
  mm: '월배정 민원',
  s1: '민원강도',
  s2: '면당압력',
  rvw: '검토지수',
  cfc: '증가계수',
  prrt: '종합지수',
  grd: '단계',
  seq: '표시순번',
  cum: '누적 면수',
  prrtDisp: '종합지수(표시)',
  rvwDisp: '검토지수(표시)',
  cfcDisp: '증가계수(표시)',
}

/**
 * 시트 2 B열 상수(정의된 이름).
 * 🔁 2026-11-11 사용자 요청 — 읍면동별 요금제 대상 판정(E0 · 필요 면수 · 충족률 · 추천유형 · 판정 불가 사유 · 필요 면수 도달)을
 *    엑셀에 두지 않는다(화면도 TSK-4259 에서 걷었다). 그 판정에만 쓰이던 상수(적발배수 · 기준점유율 · 유료수요율)도 뺐다.
 */
type ConstName = 'months' | 'prrtTop' | 'prrtHigh' | 'lotCnt'
const CONST_LABELS: Record<ConstName, string> = {
  months: '조회 개월 수',
  prrtTop: '단계 경계 — 높음',
  prrtHigh: '단계 경계 — 중간',
  lotCnt: '후보 주차장 수',
}

/** 단계명 — 화면 `feeRvwGrdNm`(높음·중간·낮음). */
const grdLabel = (cd: FeeRvwGrdCd) => feeRvwGrdNm(cd)

/** 회색 조건 셀 1행(이름 없음 — 수식이나 값). */
function conditionCell(ws: Worksheet, row: number, label: string, value: CellValue, hint?: string, numFmt?: string) {
  ws.getCell(`A${row}`).value = label
  const cell = ws.getCell(`B${row}`)
  cell.value = value
  styleCondition(cell, { numFmt })
  if (hint) {
    ws.getCell(`C${row}`).value = hint
    ws.getCell(`C${row}`).font = { size: 10, color: { argb: 'FF888888' } }
  }
}

/** 통합문서(.xlsx) 바이트. 🔴 계수 10행 중 하나라도 없으면 예외(호출부가 실패 문구로 처리). */
export async function buildZoneRcmdtnFeeWorkbook(src: ZoneRcmdtnFeeXlsxSource): Promise<ArrayBuffer> {
  const { fee } = src
  // 🔁 TSK-4340 — 읍면동 판정 계수 3개는 스코프에서 빠졌다(새 보고서는 10행). 구 스냅샷의 13행도 같은 모양으로 읽는다.
  const wbk: ZoneRcmdtnFeeWorkbook = { ...src.wbk, vrbls: src.wbk.vrbls.filter((x) => !JUDGE_CDS.includes(x.vrblCd)) }
  const vals = zoneRcmdtnAplcnValues(wbk.vrbls)
  const calc = computeFeeWorkbook(fee.lots, wbk, vals, fee.scope)
  const { emdMode } = calc
  const crtrVls: ZoneRcmdtnCrtrVls = fee.crtrVls

  const wb: Workbook = await createWorkbook()
  const main = wb.addWorksheet(MAIN_SHEET)
  const data = wb.addWorksheet(DATA_SHEET)

  /* ── 시트 1 상단 — 제목 · 지역/기간 · 계수 입력 · 조건 ── */
  ;[30, 22, 60, 12, 12, 10, 10, 12, 26, 26].forEach((w, i) => (main.getColumn(i + 1).width = w))
  const isSgg = !emdMode
  const region =
    src.regionNm ||
    (isSgg
      ? `${fee.sggNm ?? ''} 전체`.trim()
      : fee.zone
        ? `${fee.zone.sggNm} ${fee.zone.emdNm}`.trim()
        : '')
  main.getCell('A1').value = RANK_SEC_TITLE
  main.getCell('A1').font = { bold: true, size: 14 }
  main.getCell('A2').value = [region, prdText(src)].filter(Boolean).join(' · ')
  main.getCell('A4').value = COEFF_TITLE
  main.getCell('A4').font = { bold: true }

  const byCd = new Map(wbk.vrbls.map((x) => [x.vrblCd, x]))
  const need = (cd: string): ZoneRcmdtnWorkbookVrbl => {
    const x = byCd.get(cd)
    if (!x) throw new Error(`엑셀 계수 ${cd} 가 wbk.vrbls 에 없습니다.`)
    return x
  }
  // D3 — 민원강도가 입력 셀이면 면당민원은 `=100-민원강도` 수식(잠금). 아니면(가중치 꺼짐) 둘 다 조건 셀.
  const pair = need(C.w1).inptYn === 'Y'
  const inputs = wbk.vrbls.filter((x) => x.inptYn === 'Y' || (pair && x.vrblCd === C.w2))
  const conds = wbk.vrbls.filter((x) => !inputs.includes(x))
  const scope: CoeffScope = 'RECOMMEND_FEE'
  const weightTitle = COEFF_SCOPE_META[scope].weight?.title

  const firstInput = 5
  const inputRows: InputRow[] = inputs.map((x, i) => {
    if (x.vrblCd === C.w2) return coeffConditionRow(x, HINT_PAIR(need(C.w1).vrblNm))
    return coeffInputRow(x, `B${firstInput + i}`, coeffHint(scope, x.vrblCd) ?? (x.vrblCd === C.w1 ? weightTitle : undefined))
  })
  addInputRows(wb, main, inputRows, firstInput)
  if (pair) {
    const r = firstInput + inputs.findIndex((x) => x.vrblCd === C.w2)
    main.getCell(`B${r}`).value = { formula: `100-${C.w1}`, result: 100 - vrblValue(vals, C.w1) }
  }

  const condTitleRow = firstInput + inputs.length + 1
  main.getCell(`A${condTitleRow}`).value = COND_TITLE
  main.getCell(`A${condTitleRow}`).font = { bold: true }
  const condHint = (x: ZoneRcmdtnWorkbookVrbl): string => {
    if (SPATIAL_CDS.includes(x.vrblCd)) return HINT_SPATIAL
    if (x.vrblCd === C.w1 || x.vrblCd === C.w2) return HINT_WEIGHT_OFF
    return HINT_LOCKED
  }
  const firstCond = condTitleRow + 1
  addInputRows(wb, main, conds.map((x) => coeffConditionRow(x, condHint(x))), firstCond)
  let r = firstCond + conds.length
  conditionCell(main, r++, '후보 주차장 유형 범위', TRGT_TYPE_LABEL[crtrVls.trgtTypeSe] ?? crtrVls.trgtTypeSe, HINT_SPATIAL)

  /* ── 시트 2 — 상수 · 주차장 행 ── */
  const K = calc.rows.length
  const constNames: ConstName[] = ['months', 'prrtTop', 'prrtHigh', 'lotCnt']
  const headerRow = constNames.length + 2
  const first = headerRow + 1
  const last = headerRow + Math.max(K, 1)
  const constVal = (name: ConstName): CellValue => {
    switch (name) {
      case 'months':
        return wbk.months
      case 'prrtTop':
      case 'prrtHigh':
        return wbk.fixed[name]
      case 'lotCnt':
        return { formula: 'COUNT(표시순번)', result: K }
    }
  }
  constNames.forEach((name, i) => {
    data.getCell(`A${i + 1}`).value = CONST_LABELS[name]
    data.getCell(`B${i + 1}`).value = constVal(name)
    defineName(wb, sheetRef(DATA_SHEET, `$B$${i + 1}`), name)
  })

  const cols = (Object.keys(COL) as ColKey[]).filter((k) => emdMode || k !== 'cum')
  cols.forEach((key) => {
    styleHeader(data.getCell(`${COL[key]}${headerRow}`), DATA_HEADERS[key])
    const nm = COL_NAMES[key]
    if (nm) defineName(wb, sheetColRange(DATA_SHEET, COL[key], first, last), nm)
  })
  calc.rows.forEach((row, i) => {
    const n = first + i
    const at = (key: ColKey) => `${COL[key]}${n}`
    const set = (key: ColKey, v: CellValue) => {
      data.getCell(at(key)).value = v
    }
    const { lot } = row
    set('pkltId', lot.pkltId)
    set('lotNm', lotDisplayNm(lot))
    set('emdNm', lot.emdNm ?? '')
    set('sggNm', lot.sggNm ?? '')
    set('cpcty', lot.pkltCpcty)
    set('asgn', lot.asgnCvlcptCnt)
    set('incrsRt', lot.cvlcptIncrsRt ?? '')
    set('sgp', { formula: `${at('sggNm')}=${xlStr(SGP)}`, result: row.sgp })
    set('mm', { formula: `ROUND(${at('asgn')}/months,6)`, result: row.mm })
    set('s1', {
      formula: `ROUND(${at('mm')}*100/IF(${at('sgp')},${C.mmSgp},${C.mmJeju}),6)`,
      result: row.s1,
    })
    set('s2', {
      formula:
        `IF(${at('cpcty')}<=0,0,ROUND(ROUND(${at('mm')}*100/${at('cpcty')},6)*100/` +
        `IF(${at('sgp')},${C.rtSgp},${C.rtJeju}),6))`,
      result: row.s2,
    })
    set('rvw', {
      formula: `IF(${C.w1}+${C.w2}<=0,0,ROUND((${C.w1}*${at('s1')}+${C.w2}*${at('s2')})/(${C.w1}+${C.w2}),6))`,
      result: row.rvw,
    })
    set('cfc', {
      formula: `IF(${at('incrsRt')}="",1,1+${C.wi}*MAX(MIN(${at('incrsRt')}/100,${C.up}/100),-${C.up}/100))`,
      result: row.cfc,
    })
    set('prrt', { formula: `ROUND(${at('rvw')}*${at('cfc')},10)`, result: row.prrt })
    set('grd', {
      formula:
        `IF(${at('prrt')}>=prrtTop,${xlStr(grdLabel('TOP'))},` +
        `IF(${at('prrt')}>=prrtHigh,${xlStr(grdLabel('HIGH'))},${xlStr(grdLabel('LOW'))}))`,
      result: grdLabel(row.grd),
    })
    // D8 — 동점 항은 첫 데이터 행이면 0(범위 `N$5:N4` 가 뒤집혀 머리글이 끼는 것을 막는다).
    const tie = n === first ? '0' : `SUMPRODUCT(--($${COL.prrt}$${first}:$${COL.prrt}$${n - 1}=${at('prrt')}))`
    set('seq', { formula: `SUMPRODUCT(--(종합지수열>${at('prrt')}))+${tie}+1`, result: row.seq })
    if (emdMode) {
      set('cum', { formula: `SUMPRODUCT(--(표시순번<=${at('seq')}),면수열)`, result: row.cum ?? 0 })
    }
    set('prrtDisp', { formula: `ROUND(${at('prrt')},1)`, result: xlRound15(row.prrt, 1) })
    set('rvwDisp', { formula: `ROUND(${at('rvw')},1)`, result: xlRound15(row.rvw, 1) })
    set('cfcDisp', { formula: `ROUND(${at('cfc')},3)`, result: xlRound15(row.cfc, 3) })
  })
  data.getColumn('A').width = 22
  data.getColumn('B').width = 26
  ;['I', 'J', 'K', 'L', 'M', 'N'].forEach((c) => (data.getColumn(c).numFmt = '0.000000'))

  /* ── 시트 1 — 산정 기준(표 위) ── */
  // 문장 수식 — 탐침 값으로 같은 함수를 불러 빈칸 자리를 찾는다(`textFormula`). 숫자는 문장에 나올 리 없는 값이다.
  const probeCrtr: ZoneRcmdtnCrtrVls = {
    ...crtrVls,
    feeCvlcptCntWgvl: 91.3,
    feeCvlcptRtWgvl: 8.7,
    feeCvlcptMmCrtrJeju: 213.4,
    feeCvlcptMmCrtrSeogwipo: 314.6,
    feeCvlcptRtCrtrJeju: 415.7,
    feeCvlcptRtCrtrSeogwipo: 516.8,
    feeCvlcptIncrsWgvl: PROBE_INCRS_WGVL,
    feeCvlcptIncrsRtUplmtVl: PROBE_INCRS_UPLMT,
  }
  const g = (cd: string) => vrblValue(vals, cd)
  const actCrtr: ZoneRcmdtnCrtrVls = {
    ...crtrVls,
    feeCvlcptCntWgvl: g(C.w1),
    feeCvlcptRtWgvl: g(C.w2),
    feeCvlcptMmCrtrJeju: g(C.mmJeju),
    feeCvlcptMmCrtrSeogwipo: g(C.mmSgp),
    feeCvlcptRtCrtrJeju: g(C.rtJeju),
    feeCvlcptRtCrtrSeogwipo: g(C.rtSgp),
    feeCvlcptIncrsWgvl: g(C.wi),
    feeCvlcptIncrsRtUplmtVl: g(C.up),
  }
  const pct = (w1: number, w2: number) => fmtDecimal((w1 / (w1 + w2)) * 100)
  const wSum = `(${C.w1}+${C.w2})`
  const formulaSlots: TextSlot[] = [
    { probe: pct(91.3, 8.7), xl: xlNumText(`${C.w1}/${wSum}*100`, 1), js: pct(g(C.w1), g(C.w2)) },
    { probe: pct(8.7, 91.3), xl: xlNumText(`${C.w2}/${wSum}*100`, 1), js: pct(g(C.w2), g(C.w1)) },
    { probe: fmtDecimal(213.4), xl: xlNumText(C.mmJeju, 1), js: fmtDecimal(g(C.mmJeju)) },
    { probe: fmtDecimal(314.6), xl: xlNumText(C.mmSgp, 1), js: fmtDecimal(g(C.mmSgp)) },
    { probe: fmtDecimal(415.7), xl: xlNumText(C.rtJeju, 1), js: fmtDecimal(g(C.rtJeju)) },
    { probe: fmtDecimal(516.8), xl: xlNumText(C.rtSgp, 1), js: fmtDecimal(g(C.rtSgp)) },
    ...incrsSlots(C.wi, C.up, g(C.wi), g(C.up)),
  ]
  const probeLines = feeRvwFormulaDesc(probeCrtr, fee.sggNm)
  const actLines = feeRvwFormulaDesc(actCrtr, fee.sggNm)
  const formulaLines: CellValue[] =
    probeLines.length === actLines.length
      ? actLines.map((line, i) => textFormula(probeLines[i], formulaSlots, line))
      : actLines
  // 범례 — 경계는 서버 상수(`wbk.fixed`). 모양은 화면 `feeRvwLegendLine` 과 같다(그 함수는 프론트 상수를 읽어 여기선 쓰지 않는다).
  const [top, high, low] = FEE_RVW_GRDS
  const legendLine =
    `${FEE_RVW_LEGEND_TITLE} : ${grdLabel(top)} ${wbk.fixed.prrtTop}↑ · ` +
    `${grdLabel(high)} ${wbk.fixed.prrtHigh}↑ · ${grdLabel(low)} ${wbk.fixed.prrtHigh} 미만`

  // `buildZoneRcmdtnFeeReportModel` 노트의 조건·순서를 따르되, 🔁 TSK-4346 PDF 와 달리 `noteAltmntRng` 을 남긴다(엑셀엔 `적용 설정` 상자가 없다).
  const lots = fee.lots
  const trendWindowShort = fee.warnings.includes('TREND_WINDOW_SHORT')
  const trendBfrFallback = fee.warnings.includes('TREND_BFR_FALLBACK')
  const hasNullIncrs = lots.some((l) => l.cvlcptIncrsRt == null)
  const basis: CellValue[] = [
    ...formulaLines,
    legendLine,
    FEE_RVW_CRTR_SET_NOTE,
    NOTE_RANK_ONLY,
    ...(fee.asgn
      ? [noteAsgn(fee.asgn.cvlcptTot, fee.asgn.cvlcptAsgn, fee.asgn.asgnRt, crtrVls.altmntRngVl)]
      : []),
    noteAltmntRng(crtrVls.altmntRngVl),
    ...(lots.length > 0 && trendBfrFallback ? [noteTrendBfrFallback(crtrVls.trendDayCnt)] : []),
    ...(lots.length > 0 && (trendWindowShort || hasNullIncrs)
      ? [
          trendWindowShort
            ? NOTE_TREND_WINDOW_SHORT
            : noteIncrsRtNull(crtrVls.minTrendBase, crtrVls.trendDayCnt, fee.anlsMthd),
        ]
      : []),
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

  /* ── 시트 1 — 요약 ── */
  main.getCell(`A${r}`).value = '분석 결과 요약'
  main.getCell(`A${r}`).font = { bold: true }
  r++
  const summary: [string, CellValue, string][] = [
    ['후보 주차장', { formula: 'lotCnt', result: K }, '#,##0"개소"'],
    ...FEE_RVW_GRDS.map((cd): [string, CellValue, string] => [
      grdLabel(cd),
      { formula: `COUNTIF(단계열,${xlStr(grdLabel(cd))})`, result: calc.grdCnt[cd] },
      '#,##0"개소"',
    ]),
  ]
  summary.forEach(([label, value, numFmt]) => {
    main.getCell(`A${r}`).value = label
    main.getCell(`B${r}`).value = value
    main.getCell(`B${r}`).numFmt = numFmt
    r++
  })
  r++

  /* ── 시트 1 — 순위표(D10) ── */
  main.getCell(`A${r}`).value = '추천 주차장 순위'
  main.getCell(`A${r}`).font = { bold: true }
  r++
  const tableRow = r
  type TablePick = [header: string, value: (m: string, k: number) => string, result: (x: FeeXlsxRow) => string | number, numFmt?: string]
  const idx = (name: string, m: string) => `INDEX(${name},${m})`
  const picks: TablePick[] = [
    ['순위', (_m, k) => `${k}`, (x) => x.seq],
    ['주차장', (m) => idx('주차장열', m), (x) => lotDisplayNm(x.lot)],
    ...(isSgg
      ? [['읍면동', (m: string) => idx('읍면동열', m), (x: FeeXlsxRow) => x.lot.emdNm ?? ''] as TablePick]
      : []),
    ['종합지수', (m) => idx('종합지수표시', m), (x) => xlRound15(x.prrt, 1)],
    ['검토지수', (m) => idx('검토지수표시', m), (x) => xlRound15(x.rvw, 1)],
    ['증가계수', (m) => idx('증가계수표시', m), (x) => xlRound15(x.cfc, 3), '0.000'],
    ['단계', (m) => idx('단계열', m), (x) => grdLabel(x.grd)],
    [
      '증가율',
      (m) => `IF(${idx('증가율열', m)}="","—",${idx('증가율열', m)})`,
      (x) => x.lot.cvlcptIncrsRt ?? '—',
      PCT_FMT,
    ],
    ['민원 건수', (m) => idx('배정민원열', m), (x) => x.lot.asgnCvlcptCnt, '#,##0"건"'],
    isSgg
      ? ['면수', (m) => idx('면수열', m), (x) => x.lot.pkltCpcty, '#,##0"면"']
      : [
          '면수(누적)',
          (m) =>
            `TEXT(${idx('면수열', m)},"#,##0")&"면 (누적 "&TEXT(${idx('누적면수열', m)},"#,##0")&"면)"`,
          (x) =>
            `${fmtInt(x.lot.pkltCpcty)}면 (누적 ${fmtDecimal(x.cum)}면)`,
        ],
  ]
  picks.forEach(([h], i) => styleHeader(main.getCell(tableRow, i + 1), h))
  for (let k = 1; k <= K; k++) {
    const n = tableRow + k
    const x = calc.ordered[k - 1]
    const m = `MATCH(${k},표시순번,0)`
    picks.forEach(([, f, res, numFmt], i) => {
      const cell = main.getCell(n, i + 1)
      cell.value = i === 0 ? k : { formula: f(m, k), result: res(x) }
      if (numFmt) cell.numFmt = numFmt
    })
  }
  return finishWorkbook(wb, [main, data])
}

/** 엑셀을 만들어 `{fileBase}.xlsx` 로 내려받는다(D14 — 파일명은 호출부가 `rptpNm`·`ZONE_RCMDTN_FEE_FILE_BASE` 로 정한다). */
export async function downloadZoneRcmdtnFeeXlsx(src: ZoneRcmdtnFeeXlsxSource, fileBase: string): Promise<void> {
  downloadXlsx(await buildZoneRcmdtnFeeWorkbook(src), fileBase)
}
