import type { Borders, Cell, Fill, Font, Workbook, Worksheet } from 'exceljs'
import type * as ExcelJsModule from 'exceljs'
import { exportBlob } from '../report/reportExport'

/*
 * 분석 엑셀(.xlsx) 공용 도구 (TSK-4340 Step 5 — TSK-4338 `hotspotXlsx` 에서 탭과 무관한 것만 뽑았다).
 *
 * 생성기(집중구역 `hotspotXlsx` · 생활권 3종)가 같은 모양의 통합문서를 만들도록 다음만 둔다.
 *   - 엑셀 `ROUND` 와 같은 반올림(`xlRound`·`xlRound15`) · `ROUNDDOWN`(`xlRoundDown15`) · 수식 문자열 리터럴(`xlStr`)
 *   - ExcelJS 동적 로드 + `fullCalcOnLoad`(`createWorkbook`)
 *   - 시트 참조(`sheetRef`·`colRange`) · 정의된 이름(`defineName`)
 *   - 서식 — 머리글(`FFF2F2F2`) · 노란 입력 셀(`FFFFF2CC`·테두리·`locked:false`) · 회색 조건 셀(`FFD9D9D9`·잠금) · 사용자 지정 유효성 · 안내 글꼴
 *   - 시트 보호(`await protect`) → `writeBuffer`(`finishWorkbook`) → `a[download]`(`downloadXlsx`)
 *
 * 🔴 산식(점수·등급·순위)은 여기 두지 않는다 — 탭마다 정답지(백엔드 Java 재계산)와 짝을 이루는 파일에 남긴다
 *    (예: `hotspotScore`·`hotspotCutoff`·`computeHotspotWorkbook` 은 `hotspotXlsx.ts`).
 * 🔴 ExcelJS 값 import 는 이 파일의 `loadExcelJs` 동적 import 1건뿐이다 — 메인 번들에 넣지 않는다(다른 파일은 `import type` 만).
 */

/* ── 1. 수치 · 수식 문자열 ─────────────────────────────── */

/**
 * 엑셀 `ROUND` 와 같은 규칙(0 에서 먼 쪽). `Math.round(x*100)/100` 은 `12.345` 경계에서 서버와 갈린다.
 */
export const xlRound = (x: number, d: number): number => {
  const f = 10 ** d
  return (Math.sign(x) * Math.round(Math.abs(x) * f + 1e-9)) / f
}

/**
 * 엑셀 `ROUND` 의 15자리 규칙(TSK-4340) — 값을 **유효숫자 15자리로 먼저 맞춘 뒤** 0 에서 먼 쪽으로 반올림한다.
 * double 끝자리가 `.5` 경계 바로 아래(`8612.4999999999998`)일 때 엑셀은 15자리 보정으로 올리고 `Math.round` 는 내린다.
 * 백엔드 정답지 `LifezoneWorkbookIntegrationTest.excelRound0`(BigDecimal 15자리 → HALF_UP)와 같은 규칙이다.
 * ⚠️ `xlRound`(집중구역)와 다른 함수다 — 집중구역 결과를 바꾸지 않도록 그쪽은 그대로 둔다.
 */
export const xlRound15 = (x: number, d: number): number => {
  if (x === 0 || !Number.isFinite(x)) return x
  const f = 10 ** d
  const a = Number(Math.abs(x).toPrecision(15))
  return (Math.sign(x) * Math.round(Number((a * f).toPrecision(15)))) / f
}

/**
 * 엑셀 `ROUNDDOWN` 의 15자리 규칙(TSK-4340 구역추천) — 유효숫자 15자리로 먼저 맞춘 뒤 **0 쪽으로** 자른다(음수도 0 쪽).
 * 백엔드 정답지 `ZoneRcmdtnWorkbookIntegrationTest.xlRoundDown`(BigDecimal 15자리 → `DOWN`)와 같은 규칙이다.
 * 🔴 호출부는 D9 대로 `xlRoundDown15(xlRound15(x, 10), d)` 모양(= 엑셀 `ROUNDDOWN(ROUND(x,10),d)`)으로 부른다 —
 *    1ulp 아래로 떨어진 몫(`0.3/0.1 = 2.9999999999999996`)을 한 칸 깎지 않게 하는 것이 그 선보정이다.
 */
export const xlRoundDown15 = (x: number, d: number): number => {
  if (x === 0 || !Number.isFinite(x)) return x
  const f = 10 ** d
  const a = Number(Math.abs(x).toPrecision(15))
  // `+ 0` — 0 으로 잘린 음수(`-0`)를 `0` 으로(정답지 BigDecimal 에는 -0 이 없다).
  return (Math.sign(x) * Math.trunc(Number((a * f).toPrecision(15)))) / f + 0
}

/** 수식 문자열 안 문자열 리터럴 — `"` 로 감싸고 안쪽 `"` 는 `""` 로 이스케이프한다. */
export const xlStr = (s: string): string => `"${s.replace(/"/g, '""')}"`

/**
 * 수식 안의 수치 → 문구 조각 — 화면 `fmtDecimal(x, d)`(꼬리 0 제거 · 천 단위 쉼표)와 같은 꼴(TSK-4340 구역추천).
 * `d = 0` 은 `fmtInt`(정수 반올림 · 쉼표)와 같다.
 */
export function xlNumText(expr: string, d: number): string {
  const r = `ROUND(${expr},${d})`
  if (d === 0) return `TEXT(${r},"#,##0")`
  return `TEXT(${r},IF(${r}=INT(${r}),"#,##0","#,##0.${'#'.repeat(d)}"))`
}

/**
 * 문장 수식의 빈칸 1개 — `probe` 는 문구 함수에 **탐침 값**을 넣어 만든 문장에서 그 값이 찍힌 자리(문자열),
 * `xl` 은 그 자리에 들어갈 엑셀 식(문자열을 내는 식 — {@link xlNumText}), `js` 는 실제 값으로 그 자리에 찍힐 문자열이다.
 */
export interface TextSlot {
  probe: string
  xl: string
  js: string
}

/**
 * 화면·PDF 문구 함수의 결과를 **그대로** 엑셀 문장 수식으로 바꾼다(TSK-4340 구역추천 — 문구 복사 금지).
 *
 * `probeText` = 같은 함수에 탐침 값을 넣은 결과 · `actual` = 실제 값을 넣은 결과(수식 셀의 `result`).
 * 탐침 문자열 자리를 `xl` 식으로 바꿔 `"…"&식&"…"` 를 만들고, 같은 자리를 `js` 로 바꾼 문장이 `actual` 과 같은지 확인한다.
 * 🔴 다르면(탐침이 문장 안 다른 글자와 겹침 · 함수가 값에 따라 갈래를 바꿈) 수식을 버리고 **고정 문장**(`actual`)을 돌려준다 —
 *    틀린 수식보다 계수에 따라 안 바뀌는 문장이 낫다. 탐침 값은 문장에 나올 리 없는 숫자(예: `913.7`)로 고른다.
 */
export function textFormula(
  probeText: string,
  slots: TextSlot[],
  actual: string,
): { formula: string; result: string } | string {
  const ordered = [...slots].sort((a, b) => b.probe.length - a.probe.length)
  const parts: string[] = []
  // 엑셀 수식의 문자열 상수는 255자까지다 — 긴 조각은 나눠 잇는다.
  const pushLit = (t: string) => {
    for (let k = 0; k < t.length; k += 200) parts.push(xlStr(t.slice(k, k + 200)))
  }
  let mirror = ''
  let lit = ''
  let used = 0
  for (let i = 0; i < probeText.length; ) {
    const hit = ordered.find((s) => s.probe && probeText.startsWith(s.probe, i))
    if (hit) {
      if (lit) pushLit(lit)
      lit = ''
      parts.push(hit.xl)
      mirror += hit.js
      used++
      i += hit.probe.length
    } else {
      lit += probeText[i]
      mirror += probeText[i]
      i++
    }
  }
  if (lit) pushLit(lit)
  if (used === 0 || mirror !== actual) return actual
  return { formula: parts.join('&'), result: actual }
}

/* ── 2. 통합문서 · 참조 · 정의된 이름 ──────────────────── */

/** ExcelJS 모듈 — 브라우저 빌드(UMD)는 `default` 아래에, 타입은 최상위에 둔다. */
export async function loadExcelJs(): Promise<typeof ExcelJsModule> {
  const mod = await import('exceljs')
  return (mod as unknown as { default?: typeof ExcelJsModule }).default ?? mod
}

/**
 * 빈 통합문서. 🔴 `fullCalcOnLoad = true` — 수식 셀에 넣은 `result` 는 초기 계수 기준이라, 엑셀이 열 때 전부 다시 계산하게 한다.
 */
export async function createWorkbook(): Promise<Workbook> {
  const ExcelJS = await loadExcelJs()
  const wb: Workbook = new ExcelJS.Workbook()
  wb.calcProperties.fullCalcOnLoad = true
  return wb
}

/** 시트를 넘는 참조 — 시트명은 항상 작은따옴표로 감싼다(공백·한글 시트명). 안쪽 `'` 는 `''`. */
export const sheetRef = (sheet: string, addr: string): string =>
  `'${sheet.replace(/'/g, "''")}'!${addr}`

/** 열 하나의 절대 범위 — `'시트'!$C$first:$C$last`. 정의된 이름(열 이름)에 쓴다. */
export const colRange = (sheet: string, col: string, first: number, last: number): string =>
  sheetRef(sheet, `$${col}$${first}:$${col}$${last}`)

/**
 * 통합문서 범위 정의된 이름을 단다. 🔴 셀 주소처럼 읽히는 이름(`P1`·`T1`·`ABC12`)·`R`·`C` 는 엑셀이 거부해
 * 파일이 손상으로 열리므로 여기서 끊는다(TSK-4338 — `p1`→`grdPct1`).
 */
export function defineName(wb: Workbook, ref: string, name: string): void {
  if (/^[A-Za-z]{1,3}\d+$/.test(name) || /^[RrCc]$/.test(name)) {
    throw new Error(`정의된 이름 '${name}' 은 셀 주소와 겹쳐 쓸 수 없습니다.`)
  }
  wb.definedNames.add(ref, name)
}

/* ── 3. 서식 ──────────────────────────────────────────── */

export const THIN: Borders = {
  top: { style: 'thin' },
  left: { style: 'thin' },
  bottom: { style: 'thin' },
  right: { style: 'thin' },
  diagonal: {},
}
export const solid = (argb: string): Fill => ({
  type: 'pattern',
  pattern: 'solid',
  fgColor: { argb },
})
export const HEADER_FILL = solid('FFF2F2F2')
export const INPUT_FILL = solid('FFFFF2CC')
/** 회색 "조건" 셀 — 적용값만 보이고 엑셀에서 바꿀 수 없는 계수(TSK-4340 구역추천 D1·D2). */
export const CONDITION_FILL = solid('FFD9D9D9')
/** 입력 셀 옆 안내(C열 hint 등) — 10 · 옅은 회색. */
export const HINT_FONT: Partial<Font> = { size: 10, color: { argb: 'FF888888' } }
/** 산정 기준 줄 — 10 · 회색. */
export const NOTE_FONT: Partial<Font> = { size: 10, color: { argb: 'FF555555' } }

/** 머리글 셀 — 굵게 + 회색 채움. 값을 함께 주면 넣는다. */
export function styleHeader(cell: Cell, value?: string): void {
  if (value !== undefined) cell.value = value
  cell.font = { bold: true }
  cell.fill = HEADER_FILL
}

/**
 * 노란 입력 셀 — 채움 · 얇은 테두리 · 잠금 해제(시트 보호 중에도 편집 가능).
 * `locked: true` 는 "조정이 꺼진 설정" 처럼 같은 모양으로 보이되 고칠 수 없게 할 때만 쓴다.
 */
export function styleInput(cell: Cell, opts: { numFmt?: string; locked?: boolean } = {}): void {
  if (opts.numFmt !== undefined) cell.numFmt = opts.numFmt
  cell.fill = INPUT_FILL
  cell.border = THIN
  cell.protection = { locked: opts.locked ?? false }
}

/**
 * 회색 조건 셀 — 채움 · 얇은 테두리 · **잠금**(시트 보호 중 편집 불가). 값은 적용값 그대로 두고 수식이 정의된 이름으로 참조한다.
 * 노란 입력 셀(`styleInput`)과 모양이 달라야 "바꿀 수 있는 칸" 으로 읽히지 않는다(TSK-4340 구역추천 D1·D2).
 */
export function styleCondition(cell: Cell, opts: { numFmt?: string } = {}): void {
  if (opts.numFmt !== undefined) cell.numFmt = opts.numFmt
  cell.fill = CONDITION_FILL
  cell.border = THIN
  cell.protection = { locked: true }
}

/**
 * 사용자 지정 유효성(`type: custom`) — 위반 입력을 막고(`stop`) 오류 상자를 띄운다.
 * `formula` 는 `=` 없이, 셀 자신은 상대 주소(`B3`)로 쓴다.
 */
export function setCustomValidation(
  cell: Cell,
  v: { formula: string; title: string; error: string },
): void {
  cell.dataValidation = {
    type: 'custom',
    allowBlank: false,
    formulae: [v.formula],
    showErrorMessage: true,
    errorStyle: 'stop',
    errorTitle: v.title,
    error: v.error,
  }
}

/**
 * 숫자 입력 셀의 유효성 조건식(`=` 없이) — 숫자 · 범위(`min`·`max` 중 `null` 인 쪽은 뺀다) · 자릿수 + `extra` 조건.
 * `step` — `'int'` 정수(`ABS(x-ROUND(x,0))<1E-9`) · `'dec4'` 소수 넷째 자리까지(`ABS(x*10000-ROUND(x*10000,0))<1E-6`).
 * (TSK-4340 D3 — 생활권 수급·혼잡도 입력 셀 공용)
 */
export function numericRule(
  addr: string,
  opts: { min?: number | null; max?: number | null; step: 'int' | 'dec4'; extra?: string[] },
): string {
  const conds = [`ISNUMBER(${addr})`]
  if (opts.min != null) conds.push(`${addr}>=${opts.min}`)
  if (opts.max != null) conds.push(`${addr}<=${opts.max}`)
  conds.push(
    opts.step === 'int'
      ? `ABS(${addr}-ROUND(${addr},0))<1E-9`
      : `ABS(${addr}*10000-ROUND(${addr}*10000,0))<1E-6`,
  )
  conds.push(...(opts.extra ?? []))
  return `AND(${conds.join(',')})`
}

/** 범위 문구 — `1~30` · `0 이상` · `100 이하` · 둘 다 없으면 `''`. 유효성 오류 상자에 쓴다. */
export function rangeText(min: number | null | undefined, max: number | null | undefined): string {
  if (min != null && max != null) return `${min}~${max}`
  if (min != null) return `${min} 이상`
  if (max != null) return `${max} 이하`
  return ''
}

/**
 * 엄격 증가 묶음(경계)의 이웃 비교식 — `addrs[i]` 기준 앞·뒤 이웃과의 `<` 조건(`wrap` 으로 비교값을 감싼다).
 * 유효성(그 셀만)과 상태 셀(묶음 전체 — {@link increasingAll})이 같은 비교식을 쓴다.
 */
export function increasingNeighbors(
  addrs: readonly string[],
  i: number,
  wrap: (a: string) => string = (a) => a,
): string[] {
  const out: string[] = []
  if (i > 0) out.push(`${wrap(addrs[i - 1])}<${wrap(addrs[i])}`)
  if (i < addrs.length - 1) out.push(`${wrap(addrs[i])}<${wrap(addrs[i + 1])}`)
  return out
}

/** 묶음 전체가 엄격 증가인지 — `AND(a<b,b<c,…)`(`=` 없이). */
export function increasingAll(addrs: readonly string[], wrap: (a: string) => string = (a) => a): string {
  const pairs = addrs.slice(1).map((a, i) => `${wrap(addrs[i])}<${wrap(a)}`)
  return pairs.length ? `AND(${pairs.join(',')})` : 'TRUE'
}

/** 입력 셀 1행 — A 라벨 · B 노란 입력 셀(정의된 이름 `name`) · C 안내. `condition` 이면 B 가 회색 조건 셀이다. */
export interface InputRow {
  /** 정의된 이름(통합문서 범위) — 수식이 셀 주소 대신 이 이름으로 참조한다. */
  name: string
  label: string
  value: number
  hint?: string
  numFmt?: string
  /** 유효성 — `formula` 는 `=` 없이, 셀 자신은 상대 주소(`B4`)로. 입력 셀이면 필수 · 조건 셀이면 쓰지 않는다. */
  validation?: { formula: string; title: string; error: string }
  /**
   * 회색 조건 셀(잠금 · 유효성 없음 · 적용값 표시)로 쓴다(TSK-4340 구역추천 D1·D2). 정의된 이름은 입력 셀과 똑같이 단다 —
   * 수식은 입력·조건을 가리지 않고 같은 이름으로 참조한다. 기본 `false`(노란 입력 셀).
   */
  condition?: boolean
}

/** 입력 셀을 `firstRow` 부터 1행씩 B열에 쓴다(TSK-4340 D2 — 계수 1개 = 1행). 반환은 각 행의 B열 주소. */
export function addInputRows(wb: Workbook, ws: Worksheet, rows: InputRow[], firstRow: number): string[] {
  return rows.map((r, i) => {
    const n = firstRow + i
    ws.getCell(`A${n}`).value = r.label
    const cell = ws.getCell(`B${n}`)
    cell.value = r.value
    if (r.condition) {
      styleCondition(cell, { numFmt: r.numFmt })
    } else {
      if (!r.validation) throw new Error(`입력 셀 '${r.name}' 에 유효성이 없습니다.`)
      styleInput(cell, { numFmt: r.numFmt })
      setCustomValidation(cell, r.validation)
    }
    if (r.hint) {
      ws.getCell(`C${n}`).value = r.hint
      ws.getCell(`C${n}`).font = HINT_FONT
    }
    defineName(wb, sheetRef(ws.name, `$B$${n}`), r.name)
    return `B${n}`
  })
}

/* ── 4. 보호 · 쓰기 · 다운로드 ─────────────────────────── */

/**
 * 비밀번호 없는 시트 보호(TSK-4338 D1) — 잠긴 셀·풀린 셀 모두 선택은 된다.
 * `autoFilter` — 보호 중에도 자동 필터(드롭다운) 사용을 허용한다. 기본값은 **그 시트에 `ws.autoFilter` 가 걸려 있는지**다
 * (TSK-4340 구역추천 D10 — 필터를 걸어 두고 보호로 막으면 드롭다운이 죽는다). 자동 필터가 없는 시트는 종전과 같다.
 * ⚠️ 정렬은 허용하지 않는다 — 잠긴 셀 범위의 정렬은 보호 옵션과 무관하게 엑셀이 막는다.
 */
export function protectSheet(ws: Worksheet, opts: { autoFilter?: boolean } = {}): Promise<void> {
  const autoFilter = opts.autoFilter ?? ws.autoFilter != null
  return ws.protect('', {
    selectLockedCells: true,
    selectUnlockedCells: true,
    ...(autoFilter ? { autoFilter: true } : {}),
  })
}

/**
 * 시트를 차례로 보호한 뒤 바이트로 쓴다. 🔴 `protect()` 는 Promise 라 전부 await 한 뒤 `writeBuffer` 해야 보호가 실린다.
 */
export async function finishWorkbook(wb: Workbook, protect: Worksheet[]): Promise<ArrayBuffer> {
  for (const ws of protect) await protectSheet(ws)
  return (await wb.xlsx.writeBuffer()) as ArrayBuffer
}

export const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

/** `{fileBase}.xlsx` 로 내려받는다(파일명 정리는 `exportBlob`). */
export function downloadXlsx(buf: ArrayBuffer, fileBase: string): void {
  exportBlob(new Blob([buf], { type: XLSX_MIME }), fileBase, 'xlsx')
}
