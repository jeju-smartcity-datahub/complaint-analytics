import type { LifezoneGrade } from '../../api/lifezone'
import {
  formatPerSlot,
  formatShortagePct,
  supplyCellZoneNm,
  type LegacyLifezoneSupplyCell,
  type LifezoneQuadrant,
  type LifezoneQuadrantCriterion,
  type LifezoneSupplyCell,
  type LifezoneSupplyGradeCriterion,
  type LifezoneSupplyWorkbook,
} from '../../api/lifezoneSupply'
import {
  GRADE_LABEL,
  isListedGrade,
  resolveGradeColor,
} from '../../components/lifezone/lifezoneGrade'
import { QUADRANT_ORDER, resolveQuadrantColor } from '../../components/lifezone/lifezoneQuadrant'
import type { AnlsVrblAjmt } from '../../api/analysisWeight'
import { changedSettingsBlockHtml, changedSettingsBoxOf, escapeHtml } from '../report/reportTemplate'

/*
 * 생활권 **수급** 보고서 문서 HTML (TSK-4089).
 *
 * 🔴 **민원건수 템플릿(`lifezoneReportTemplate.ts`)을 확장하지 않는다** — 요약이 순서형 5등급이
 * 아니라 범주형 4사분면이고, 표의 열도 값 하나가 아니라 민원·공급 두 축이다. 한 템플릿에 넣으면
 * 분기가 절반을 차지하고 어느 쪽에서도 빈 칸이 생긴다.
 *
 * 🔴 **이 산출물은 "주차 부족 면수" 가 아니다.** 문서에도 그 사실을 남긴다 — 화면을 떠난 파일이
 * 근거 없이 돌아다니는 것을 막는 유일한 장치가 문서 안의 문구다(인계서 §4-4).
 *
 * ⚠️ 문자열 조립이라 XSS 경로다 — 서버에서 온 문자열은 예외 없이 `escapeHtml` 을 통과시킨다.
 * 인라인 스타일만 쓰는 이유는 PDF(html2canvas)·Word(html-docx) 변환이 외부 CSS 를 따라가지
 * 않기 때문이다(민원건수 템플릿과 같은 제약).
 *
 * 🔁 **TSK-4258 — 템플릿이 둘이다.** `doc.anlsMthd === 'SHRT_RT'` 이면 신규(부족률 등간격 5단계 — D19),
 * 그 밖(부재 포함)은 **저장 당시의 사분면 템플릿**이다(D14 — 구 스냅샷을 재해석하지 않는다).
 * 🔴 "anlsMthd 가 없으면 구" 가 아니라 "SHRT_RT 이면 신" 으로 판정한다 — 조회 결과·생성 응답·신규 스냅샷은
 * 모두 `SHRT_RT` 를 싣고, 구 스냅샷만 값이 없다. 구 경로 코드(`Legacy*`·사분면 함수)는 원문 그대로 둔다.
 */

/**
 * 🔁 신규(부족률 등간격) 문서 모양 (TSK-4258).
 *
 * 조회 응답(`LifezoneSupplyResult`)·저장 응답(`LifezoneSupplyReportResponse`)·신규 스냅샷 셋 다 이 모양을
 * 만족한다 — 저장이 실패해도 화면 결과로 문서를 만들 수 있다(`lifezoneSupplyExport.ts`).
 */
export interface ShrtRtLifezoneSupplyReportDoc {
  rptpNm?: string
  regionNm?: string
  anlsPrdExpln?: string
  crtYmd?: string

  /** 🔴 신·구 판정의 유일한 표지(D8) */
  anlsMthd: 'SHRT_RT'
  anlsTrgtNm: string
  gridSzNm: string
  sectionTitle: string
  sectionSub: string
  /** 🔴 문서에도 상시 표기한다 */
  disclaimer: string
  /** 격자 해석 주의 안내 — 100m 에서만 온다(D17). 서버 문구 그대로 */
  gridNotice?: string

  severeCnt: number
  warnCnt: number
  cautionCnt: number
  normalCnt: number
  lowCnt: number
  zeroSplyGridCnt: number
  minMnthAvgCvlcpt: number
  gradeCriteria: LifezoneSupplyGradeCriterion[]

  totGridCnt: number
  totCvlcptCnt: number
  totPkltCpctySum: number

  /** 표에 실을 등급 — `null`·생략 = 전체(화면은 보내지 않는다). 셀은 전량이고 이 값은 표 행만 거른다 */
  grade?: LifezoneGrade | null

  cells: LifezoneSupplyCell[]

  /** 🆕 엑셀 재계산 데이터(TSK-4340) — 신규 스냅샷·저장 응답에만 있다. 구 스냅샷·조회 결과는 없음/`null`. 문서(PDF·DOCX)는 쓰지 않는다 */
  wbk?: LifezoneSupplyWorkbook | null
  /** 🆕 TSK-4346 — 수급 5행의 적용 내역 → 등급 기준표 뒤 `적용 설정` 상자. 구 스냅샷은 키 생략(`NON_NULL`)이라 상자가 없다 */
  vrblAjmt?: AnlsVrblAjmt | null
}

/** 신·구 문서 유니언 — `anlsMthd` 로 좁힌다. 스냅샷 조회(`api/reports.ts`)·내보내기가 이 타입을 쓴다. */
export type LifezoneSupplyReportDoc = ShrtRtLifezoneSupplyReportDoc | LegacyLifezoneSupplyReportDoc

/**
 * (구 스냅샷 전용 — TSK-4258 D14) 사분면 문서가 필요로 하는 최소 모양.
 *
 * 조회 응답(`LifezoneSupplyResult`)과 저장 응답(`LifezoneSupplyReportResponse`) 둘 다 이 모양을
 * 만족하므로, 저장이 실패해도 화면 결과로 문서를 만들 수 있다.
 */
export interface LegacyLifezoneSupplyReportDoc {
  /** 보고서 헤더 — 저장 응답에만 있다(조회 결과로 만들 때는 없다) */
  rptpNm?: string
  regionNm?: string
  anlsPrdExpln?: string
  crtYmd?: string

  anlsTrgtNm: string
  gridSzNm: string
  sectionTitle: string
  sectionSub: string
  /** 🔴 문서에도 상시 표기한다 */
  disclaimer: string
  /** 🔁 TSK-4258 — 구 스냅샷엔 이 키가 없다(신규 문서와 유니언을 가르는 자리) */
  anlsMthd?: undefined

  cvlcptHighThld: number
  splyLowThld: number
  qdrnCriteria: LifezoneQuadrantCriterion[]

  totGridCnt: number
  totCvlcptCnt: number
  totPkltCpctySum: number

  /**
   * 표에 실을 사분면(TSK-4227 D17). `null`·생략 = 전체.
   * 🔁 D19 — 화면에서 칩을 걷어 지금은 보내지 않는다(= 전체). 값이 있는 것은 **Step 18 이후·D19 이전에
   * 저장된 스냅샷**뿐이며, 그 보고서는 저장 당시 화면과 같게 그 사분면으로 거른다.
   * 이 값이 없던 과거 스냅샷도 `null` 이라 전체를 그린다.
   */
  qdrn?: LifezoneQuadrant | null

  cells: LegacyLifezoneSupplyCell[]
}

const DOC_TITLE = '생활권 시뮬레이션 주차 수급 분석 결과'
const DOC_SUBTITLE = '제주 주차민원분석 솔루션 · 격자 기반 공영주차 공급 대비 민원 발생량'
const SUMMARY_TITLE = '1. 분석 결과 요약'
const LIST_TITLE_PREFIX = '2. '
const NOTE_TITLE_PREFIX = '3. '
const NOTE_TITLE = '분석 기준과 한계'

/**
 * 목록에 싣는 구역 수 — 🔴 **화면 순위 목록과 보고서 표가 같은 값을 쓴다**(TSK-4227 D17).
 * 지도는 셀 전량이지만 목록은 읽을 수 있는 분량으로 자른다. 종전 문서는 30 이라 화면(15)과 갈렸다.
 */
export const SUPPLY_LIST_LIMIT = 15

/**
 * 목록 한 행 — 셀과 **표시 순위**(1~N, 리스트 순서).
 *
 * 🔴 응답 `cell.rnk` 는 **사분면 내 순위**라 사분면이 섞인 목록에 그대로 찍으면 1·1·2… 로 겹친다
 * (TSK-4227 D19). 화면 `rl__rank` 와 보고서 순위 열은 둘 다 이 `dispRnk` 를 쓴다.
 */
export interface SupplyListRow<C> {
  cell: C
  dispRnk: number
}

/**
 * {@link supplyListRows}·{@link filterSupplyCells} 가 받는 셀의 최소 모양(TSK-4258 — 셀 타입 제네릭).
 * 🔴 `gridId` 를 넣는 이유: 선택 속성(`qdrn?`)만 있는 제약은 weak type 이라 `qdrn` 이 없는 신규 셀이 TS2559 로 거부된다.
 */
type QdrnFilterable = { gridId: string; qdrn?: LifezoneQuadrant | null }

/**
 * 사분면으로 거른 셀 — `qdrn` 이 없으면 전체.
 *
 * 화면은 칩이 없어(TSK-4227 D19) 늘 `null`(전체)이다. 사분면이 오는 것은 **Step 18 이후
 * 저장된 스냅샷**(`anls_param_json.qdrn`)뿐이며, 그때는 저장 당시 화면과 같게 거른다.
 */
export function filterSupplyCells<C extends QdrnFilterable>(
  cells: C[],
  qdrn: LifezoneQuadrant | null | undefined,
): C[] {
  return qdrn ? cells.filter((c) => c.qdrn === qdrn) : cells
}

/**
 * 목록에 실을 행 — **화면(`LifezoneSupplyResultPanel`)과 보고서 표가 이 함수 하나를 쓴다**(TSK-4227 D17·D19).
 *
 * 거른 뒤 상위 {@link SUPPLY_LIST_LIMIT}. 🔴 서버 정렬(민원건수 내림차순)을 그대로 쓴다 — 재정렬하지
 * 않는다. 순위는 응답 `rnk` 가 아니라 **이 리스트의 순서**(1~N)로 매긴다({@link SupplyListRow}).
 */
export function supplyListRows<C extends QdrnFilterable>(
  cells: C[],
  qdrn: LifezoneQuadrant | null | undefined,
): SupplyListRow<C>[] {
  return filterSupplyCells(cells, qdrn)
    .slice(0, SUPPLY_LIST_LIMIT)
    .map((cell, i) => ({ cell, dispRnk: i + 1 }))
}

/**
 * 🔁 **신규(부족률 등간격) 목록 행** — 화면(`LifezoneSupplyResultPanel`)과 신규 보고서 표가 이 함수 하나를
 * 쓴다(TSK-4258 · TSK-4227 D17 "화면 = 보고서 표 한 함수" 원칙의 신규 경로 자리).
 *
 * 서버 `grade` 가 목록 대상(심각·경고·주의 — `isListedGrade`)인 셀만 거르고 **slice·재정렬하지 않는다**.
 * 순위는 서버 `rnk`(전체 순위 1..N, 응답이 `rnk` 순)를 그대로 쓴다 — 걸러도 순위가 바뀌지 않는다.
 * ⚠️ 위 {@link supplyListRows}·{@link filterSupplyCells} 는 **구 스냅샷(사분면) 전용**으로 남는다.
 */
export function listedSupplyRows(cells: LifezoneSupplyCell[]): SupplyListRow<LifezoneSupplyCell>[] {
  return cells.filter((c) => isListedGrade(c.grade)).map((cell) => ({ cell, dispRnk: cell.rnk }))
}

const A4_WIDTH_PX = 794
const DOC_PADDING_PX = 40
const DOC_FONT =
  "'Pretendard', 'Wanted Sans', system-ui, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"
const ACCENT_COLOR = '#0066ff'
const SECTION_TITLE_STYLE =
  `border-left:4px solid ${ACCENT_COLOR};padding-left:10px;font-size:15px;font-weight:800;` +
  'line-height:1.4;margin:0 0 12px;'
const TH_STYLE = 'padding:8px 10px;border-bottom:1px solid #e5e6ea;font-weight:700;'
const TD_STYLE = 'padding:8px 10px;border-bottom:1px solid #f0f0f0;'
const NOTE_STYLE =
  'margin:0;padding:12px 14px;border-radius:8px;background:#f5f6f8;color:#4a4d55;' +
  'font-size:11px;line-height:1.7;'

const COLUMNS: { label: string; align: 'left' | 'right' | 'center'; width: number | null }[] = [
  { label: '순위', align: 'left', width: 38 },
  { label: '구역', align: 'left', width: 120 },
  { label: '민원', align: 'right', width: 62 },
  { label: '공영주차면', align: 'right', width: 72 },
  { label: '주차장', align: 'right', width: 54 },
  { label: '비고', align: 'left', width: null }, // 남는 폭 전부
  { label: '구분', align: 'center', width: 66 },
]

/** `rgb(…)` → `#rrggbb`. html-docx 가 `rgb()` 를 못 읽어 배지 색이 통째로 빠진다. */
function toHexColor(color: string): string {
  const m = /^rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(color)
  if (!m) return color
  const hex = m
    .slice(1)
    .map((v) => Number(v).toString(16).padStart(2, '0'))
    .join('')
  return `#${hex}`
}

function quadrantColor(qdrn: LifezoneQuadrant): string {
  return toHexColor(resolveQuadrantColor(qdrn))
}

function quadrantBadge(qdrn: LifezoneQuadrant, qdrnNm: string): string {
  return (
    `<span style="display:inline-block;padding:2px 8px;border-radius:999px;font-size:11px;` +
    `font-weight:700;color:#fff;white-space:nowrap;background-color:${escapeHtml(quadrantColor(qdrn))};">` +
    `${escapeHtml(qdrnNm)}</span>`
  )
}

function targetCaption(doc: LifezoneSupplyReportDoc): string {
  return `분석 유형 : ${doc.anlsTrgtNm} · 격자 크기 : ${doc.gridSzNm}`
}

function metaCaption(doc: LifezoneSupplyReportDoc): string | null {
  const parts: string[] = []
  if (doc.regionNm) parts.push(`분석 지역 : ${doc.regionNm}`)
  if (doc.anlsPrdExpln) parts.push(`분석 기간 : ${doc.anlsPrdExpln}`)
  if (doc.crtYmd) parts.push(`생성일 : ${doc.crtYmd.slice(0, 10)}`)
  if (parts.length === 0) return null
  return parts.join(' · ')
}

/**
 * 🔴 임계값 문구 — 표시값만 올림/내림하고 **판정은 서버가 원값으로** 했다.
 * 격자 크기·기간·지역이 바뀌면 이 숫자도 바뀌므로 문서에 반드시 남긴다(없으면 사분면이
 * 무엇을 뜻하는지 문서만으로 알 수 없다).
 */
function thresholdCaption(doc: LegacyLifezoneSupplyReportDoc): string {
  return (
    `이번 분석 기준 — 민원 ${Math.ceil(doc.cvlcptHighThld).toLocaleString()}건 이상 · ` +
    `공영주차면 ${Math.floor(doc.splyLowThld).toLocaleString()}면 이하`
  )
}

/** 요약 행: 총 격자 + 사분면 4종 + 민원·공급 합계. */
function summaryRows(
  doc: LegacyLifezoneSupplyReportDoc,
): { label: string; color: string | null; value: string }[] {
  const criterionOf = new Map(doc.qdrnCriteria.map((c) => [c.qdrn, c]))
  const rows: { label: string; color: string | null; value: string }[] = [
    { label: '총 분석 격자', color: null, value: `${doc.totGridCnt.toLocaleString()}개` },
  ]
  for (const qdrn of QUADRANT_ORDER) {
    const criterion = criterionOf.get(qdrn)
    if (!criterion) continue
    rows.push({
      label: criterion.qdrnNm,
      color: quadrantColor(qdrn),
      value: `${criterion.gridCnt.toLocaleString()}개 (민원 ${criterion.cvlcptCnt.toLocaleString()}건)`,
    })
  }
  rows.push({ label: '민원 건수', color: null, value: `${doc.totCvlcptCnt.toLocaleString()}건` })
  // 🔴 "공영주차면" 이다 — "부족 면수" 가 아니다(인계서 §4-4).
  rows.push({
    label: '공영주차면',
    color: null,
    value: `${doc.totPkltCpctySum.toLocaleString()}면`,
  })
  return rows
}

/**
 * 표의 `비고` 칸 — 면당 민원(Q2 한정).
 */
function noteOf(cell: LegacyLifezoneSupplyCell): string {
  const parts: string[] = []
  if (cell.qdrn === 'Q2' && cell.cvlcptPerSlot != null) {
    parts.push(`면당 민원 ${formatPerSlot(cell.cvlcptPerSlot)}건`)
  }
  return parts.join(' · ')
}

/**
 * 표에 실을 행 — 화면과 같은 {@link supplyListRows}(TSK-4227 D17·D19). 넘치면 아래에 몇 곳이 더
 * 있는지 적는다. 🔴 `doc.qdrn` 은 Step 18 이후 저장된 스냅샷에만 있다 — 있으면 그 사분면으로 거르고
 * (저장 당시 화면과 같게), 없으면 전체(지금 화면 · 과거 스냅샷 · 저장 실패 경로).
 */
function rowsOf(doc: LegacyLifezoneSupplyReportDoc): SupplyListRow<LegacyLifezoneSupplyCell>[] {
  return supplyListRows(doc.cells, doc.qdrn)
}

/** 표 사분면 이름 — 스냅샷 `qdrn` 의 범례명(`qdrnCriteria.qdrnNm`), 없으면 "전체". */
function listQdrnNm(doc: LegacyLifezoneSupplyReportDoc): string {
  if (!doc.qdrn) return '전체'
  return doc.qdrnCriteria.find((c) => c.qdrn === doc.qdrn)?.qdrnNm ?? doc.qdrn
}

/** (구 스냅샷 전용) 사분면 문서 본문 — TSK-4258 이전 템플릿 원문 그대로(D14). */
function buildLegacyHtml(doc: LegacyLifezoneSupplyReportDoc): string {
  const meta = metaCaption(doc)
  const contentWidth = A4_WIDTH_PX - DOC_PADDING_PX * 2

  const summary = summaryRows(doc)
    .map(
      (row) => `<tr>
            <td style="${TD_STYLE}">${
              row.color
                ? `<span style="color:${escapeHtml(row.color)};font-size:13px;">&#9679;</span> `
                : ''
            }${escapeHtml(row.label)}</td>
            <td style="${TD_STYLE}text-align:right;font-weight:700;">${escapeHtml(row.value)}</td>
          </tr>`,
    )
    .join('')

  const cols = COLUMNS.map((c) => {
    const width = c.width ?? contentWidth - COLUMNS.reduce((sum, x) => sum + (x.width ?? 0), 0)
    return `<col style="width:${width}px;" />`
  }).join('')

  const head = COLUMNS.map(
    (c) => `<th style="${TH_STYLE}text-align:${c.align};">${escapeHtml(c.label)}</th>`,
  ).join('')

  const rows = rowsOf(doc)
  /** 🔴 "상위 N곳만" 의 분모는 **거른 집합**이다 — 전체 격자 수가 아니다(D17) */
  const listTotal = filterSupplyCells(doc.cells, doc.qdrn).length
  const qdrnNm = listQdrnNm(doc)
  const body = rows
    .map(
      ({ cell, dispRnk }) => `<tr>
            <td style="${TD_STYLE}">${escapeHtml(String(dispRnk))}</td>
            <td style="${TD_STYLE}font-weight:700;word-break:keep-all;">${escapeHtml(
              cell.emdNm ?? cell.gridId,
            )}<div style="font-weight:400;color:#9ea1a8;font-size:10px;">${escapeHtml(
              cell.gridId,
            )}</div></td>
            <td style="${TD_STYLE}text-align:right;font-weight:700;white-space:nowrap;">${escapeHtml(
              `${cell.cvlcptCnt.toLocaleString()}건`,
            )}</td>
            <td style="${TD_STYLE}text-align:right;white-space:nowrap;">${escapeHtml(
              `${cell.pkltCpctySum.toLocaleString()}면`,
            )}</td>
            <td style="${TD_STYLE}text-align:right;white-space:nowrap;">${escapeHtml(
              `${cell.pkltCnt.toLocaleString()}개소`,
            )}</td>
            <td style="${TD_STYLE}color:#70737c;font-size:11px;line-height:1.5;word-break:keep-all;">${escapeHtml(
              noteOf(cell),
            )}</td>
            <td style="${TD_STYLE}text-align:center;">${quadrantBadge(cell.qdrn, cell.qdrnNm)}</td>
          </tr>`,
    )
    .join('')

  const more =
    listTotal > rows.length
      ? `<div data-pdf-keep style="margin-top:8px;font-size:11px;color:#9ea1a8;">${escapeHtml(
          `표에는 ${qdrnNm} ${listTotal.toLocaleString()}곳 중 상위 ${rows.length.toLocaleString()}곳만 실었습니다. 나머지는 화면에서 확인할 수 있습니다.`,
        )}</div>`
      : ''

  return `<div style="font-family:${DOC_FONT};color:#171717;width:${A4_WIDTH_PX}px;padding:${DOC_PADDING_PX}px;background:#fff;box-sizing:border-box;">
      <div style="font-size:22px;font-weight:800;margin-bottom:6px;">${escapeHtml(DOC_TITLE)}</div>
      <div style="margin-bottom:24px;">
        <div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(DOC_SUBTITLE)}</div>
        <div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(targetCaption(doc))}</div>
        ${meta ? `<div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(meta)}</div>` : ''}
        <div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(thresholdCaption(doc))}</div>
      </div>

      <div data-pdf-keep-next style="${SECTION_TITLE_STYLE}">${escapeHtml(SUMMARY_TITLE)}</div>
      <table style="width:100%;border-collapse:collapse;font-size:12px;margin-bottom:28px;">
        <tbody>${summary}</tbody>
      </table>

      <div data-pdf-keep-next style="${SECTION_TITLE_STYLE}">${escapeHtml(`${LIST_TITLE_PREFIX}${doc.sectionTitle}`)}</div>
      <div data-pdf-keep-next style="font-size:11px;color:#9ea1a8;margin:-6px 0 10px;">${escapeHtml(
        // 사분면 표기는 사분면으로 거른 스냅샷(Step 18 이후·D19 이전 저장분)에만 — 전체(현행)는 붙이지 않는다
        doc.qdrn ? `${doc.sectionSub} · 표시 사분면 : ${qdrnNm}` : doc.sectionSub,
      )}</div>
      <table style="width:100%;table-layout:fixed;border-collapse:collapse;font-size:12px;">
        <colgroup>${cols}</colgroup>
        <thead>
          <tr style="background:#f5f6f8;">${head}</tr>
        </thead>
        <tbody>${body}</tbody>
      </table>
      ${more}

      <div data-pdf-keep-next style="${SECTION_TITLE_STYLE}margin-top:28px;">${escapeHtml(`${NOTE_TITLE_PREFIX}${NOTE_TITLE}`)}</div>
      <p data-pdf-keep style="${NOTE_STYLE}">
        ${escapeHtml(doc.disclaimer)}<br />
        ${escapeHtml(
          '사분면은 이번 분석 결과 안에서 민원 상위 20%(P80)와 공영주차면 하위 70%(P70)를 경계로 나눈 것이며, ' +
            '격자 크기·기간·지역이 바뀌면 경계도 함께 바뀝니다. 다른 조건으로 만든 보고서와 구분을 직접 비교하지 마십시오.',
        )}
      </p>
    </div>`
}

/* ── 🔁 신규 — 부족률 등간격 5단계 (TSK-4258 D19) ───────────────── */

/** 산식 설명 — 🔴 판정(순위·등급)은 서버가 했다. 문서는 무엇을 계산했는지만 적는다 */
export const SHRT_RT_FORMULA_TEXT = '부족률 = 주차 민원 ÷ (공영주차장 면수 + 주차 민원)'

/** 순위가 비었을 때 표 본문 안내 1행 — 민원건수 템플릿·화면의 빈 상태 문구와 같다 */
const SHRT_RT_EMPTY_TEXT = '심각·경고·주의 등급에 해당하는 격자가 없습니다.'

/** 요약 행의 등급 순서 — `gradeCriteria` 가 비었을 때만 쓰는 기본값(정본은 응답 순서다) */
const SHRT_RT_GRADE_ORDER: readonly LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION', 'NORMAL', 'LOW']

/**
 * 신규 표 열 — `순위 · 지역 · 등급 · 부족률 · 수요(대) · 공급(면)`(Step 6).
 * 종전 `민원`·`면수` 는 `수요`·`공급` 으로 대체하고, `개소`·사분면 배지 열은 뺐다. 🔁 `비고`(소수 지점 집중 주의) 열도 사용자 요청으로 뺐다(2026-10-21).
 * 구 `COLUMNS` 는 그대로 둔다(D14).
 * 🔁 열 폭은 **모두 같다**(본문 폭 ÷ 열 수 — 2026-10-21 사용자 요청). 그래서 열마다 폭을 적지 않는다.
 */
const SHRT_RT_COLUMNS: { label: string; align: 'left' | 'right' | 'center' }[] = [
  { label: '순위', align: 'left' },
  { label: '지역', align: 'left' },
  { label: '등급', align: 'center' },
  { label: '부족률', align: 'right' },
  { label: '수요(대)', align: 'right' },
  { label: '공급(면)', align: 'right' },
]

/** 같은 폭 `<col>` n 개 — 신규 경로의 두 표(목록·등급 기준)가 함께 쓴다. */
function equalCols(n: number, contentWidth: number): string {
  const width = Math.floor(contentWidth / n)
  return Array.from({ length: n }, () => `<col style="width:${width}px;" />`).join('')
}

/**
 * 등급 색(hex) — 민원건수 보고서 템플릿과 같은 방식(`resolveGradeColor` → `toHexColor`).
 * 지도·범례·목록 점과 같은 출처(`lifezoneGrade.ts` 5색)이고, DOCX 가 `rgb()` 를 못 읽어 hex 로 바꾼다.
 */
function gradeColor(grade: LifezoneGrade): string {
  return toHexColor(resolveGradeColor(grade))
}

/** 등급 배지 — 색 배경 + 흰 글자. 라벨은 응답 `gradeNm` 우선, 비면 `GRADE_LABEL` */
function gradeBadge(grade: LifezoneGrade, gradeNm: string): string {
  const label = gradeNm || GRADE_LABEL[grade]
  return (
    `<span style="display:inline-block;padding:2px 8px;border-radius:999px;font-size:11px;` +
    `font-weight:700;color:#fff;white-space:nowrap;background-color:${escapeHtml(gradeColor(grade))};">` +
    `${escapeHtml(label)}</span>`
  )
}

/** 최소 기준 문구(D16) — 걸러진 격자가 "민원 없음" 으로 읽히지 않게 문서에도 남긴다 */
function minCriterionCaption(doc: ShrtRtLifezoneSupplyReportDoc): string {
  return `월평균 민원 ${doc.minMnthAvgCvlcpt.toLocaleString()}건 이상 격자만 평가`
}

/**
 * 공급 0 격자 비율 안내(D17) — 분모는 **평가 대상 격자 수**(`totGridCnt`, 최소 기준 통과분)다.
 * 공급 0 이면 부족률이 100% 로 계산돼 상위 등급을 채우므로, 그 비중을 문서에 숫자로 남긴다.
 */
function zeroSupplyCaption(doc: ShrtRtLifezoneSupplyReportDoc): string {
  const pct = doc.totGridCnt > 0 ? Math.round((doc.zeroSplyGridCnt / doc.totGridCnt) * 100) : 0
  return (
    `공영주차면이 0면인 격자 ${doc.zeroSplyGridCnt.toLocaleString()}개 ` +
    `(평가 격자 ${doc.totGridCnt.toLocaleString()}개 중 ${pct}%) — 공급이 0면이면 부족률은 100%입니다.`
  )
}

/**
 * 요약 행: 총 격자 + 등급 5행 + 민원·공급 합계.
 * 개수는 응답 요약값(`severeCnt`…`lowCnt`), 순서·등급명은 범례(`gradeCriteria`) — 화면 패널과 같은 규칙.
 */
function shrtRtSummaryRows(
  doc: ShrtRtLifezoneSupplyReportDoc,
): { label: string; color: string | null; value: string }[] {
  const cntOf: Record<LifezoneGrade, number> = {
    SEVERE: doc.severeCnt,
    WARN: doc.warnCnt,
    CAUTION: doc.cautionCnt,
    NORMAL: doc.normalCnt,
    LOW: doc.lowCnt,
  }
  const criteria =
    doc.gradeCriteria.length > 0
      ? doc.gradeCriteria
      : SHRT_RT_GRADE_ORDER.map((grade) => ({ grade, gradeNm: GRADE_LABEL[grade] }))
  return [
    { label: '총 분석 격자', color: null, value: `${doc.totGridCnt.toLocaleString()}개` },
    ...criteria.map((c) => ({
      label: c.gradeNm || GRADE_LABEL[c.grade],
      color: gradeColor(c.grade),
      value: `${(cntOf[c.grade] ?? 0).toLocaleString()}개`,
    })),
    { label: '민원 건수', color: null, value: `${doc.totCvlcptCnt.toLocaleString()}건` },
    // 🔴 "공영주차면" 이다 — "부족 면수" 가 아니다(인계서 §4-4).
    { label: '공영주차면', color: null, value: `${doc.totPkltCpctySum.toLocaleString()}면` },
  ]
}

/**
 * 표에 실을 행 — 화면과 같은 {@link listedSupplyRows}(심각·경고·주의 전부 · 상한 없음).
 * `doc.grade` 가 있으면 **먼저** 그 등급 셀로 거른 뒤 넘긴다 — `dispRnk` 는 서버 `rnk` 라 걸러도 순위가 그대로다.
 */
function shrtRtRowsOf(doc: ShrtRtLifezoneSupplyReportDoc): SupplyListRow<LifezoneSupplyCell>[] {
  const cells = doc.grade ? doc.cells.filter((c) => c.grade === doc.grade) : doc.cells
  return listedSupplyRows(cells)
}

/** 표 등급 이름 — 범례의 `gradeNm`, 없으면 `GRADE_LABEL` */
function shrtRtGradeNmOf(doc: ShrtRtLifezoneSupplyReportDoc, grade: LifezoneGrade): string {
  return doc.gradeCriteria.find((c) => c.grade === grade)?.gradeNm || GRADE_LABEL[grade]
}

/** 신규 문서 본문 (TSK-4258). 바깥에서 온 값은 예외 없이 `escapeHtml` 을 통과시킨다. */
function buildShrtRtHtml(doc: ShrtRtLifezoneSupplyReportDoc): string {
  const meta = metaCaption(doc)
  const contentWidth = A4_WIDTH_PX - DOC_PADDING_PX * 2

  const summary = shrtRtSummaryRows(doc)
    .map(
      (row) => `<tr>
            <td style="${TD_STYLE}">${
              row.color
                ? `<span style="color:${escapeHtml(row.color)};font-size:13px;">&#9679;</span> `
                : ''
            }${escapeHtml(row.label)}</td>
            <td style="${TD_STYLE}text-align:right;font-weight:700;">${escapeHtml(row.value)}</td>
          </tr>`,
    )
    .join('')

  /* 등급 기준표 — 🔴 `lgndTxt` 를 그대로 싣는다(템플릿이 문구를 조립하지 않는다) */
  const criteria = doc.gradeCriteria
    .map(
      (c) => `<tr>
            <td style="${TD_STYLE}"><span style="color:${escapeHtml(gradeColor(c.grade))};font-size:13px;">&#9679;</span> ${escapeHtml(
              c.lgndTxt,
            )}</td>
            <td style="${TD_STYLE}text-align:right;">${escapeHtml(`${c.gridCnt.toLocaleString()}개`)}</td>
            <td style="${TD_STYLE}text-align:right;">${escapeHtml(`${c.cvlcptCnt.toLocaleString()}건`)}</td>
            <td style="${TD_STYLE}text-align:right;">${escapeHtml(`${c.zeroSplyGridCnt.toLocaleString()}개`)}</td>
          </tr>`,
    )
    .join('')

  const cols = equalCols(SHRT_RT_COLUMNS.length, contentWidth)

  const head = SHRT_RT_COLUMNS.map(
    (c) => `<th style="${TH_STYLE}text-align:${c.align};">${escapeHtml(c.label)}</th>`,
  ).join('')

  const rows = shrtRtRowsOf(doc)
  const body =
    rows.length === 0
      ? `<tr>
            <td colspan="${SHRT_RT_COLUMNS.length}" style="${TD_STYLE}text-align:center;color:#70737c;">${escapeHtml(
              SHRT_RT_EMPTY_TEXT,
            )}</td>
          </tr>`
      : rows
          .map(
            ({ cell, dispRnk }) => `<tr>
            <td style="${TD_STYLE}">${escapeHtml(String(dispRnk))}</td>
            <td style="${TD_STYLE}font-weight:700;word-break:keep-all;">${escapeHtml(
              supplyCellZoneNm(cell),
            )}<div style="font-weight:400;color:#9ea1a8;font-size:10px;">${escapeHtml(
              cell.gridId,
            )}</div></td>
            <td style="${TD_STYLE}text-align:center;">${gradeBadge(cell.grade, cell.gradeNm)}</td>
            <td style="${TD_STYLE}text-align:right;font-weight:700;white-space:nowrap;">${escapeHtml(
              `${formatShortagePct(cell.shortageRatio)}%`,
            )}</td>
            <td style="${TD_STYLE}text-align:right;white-space:nowrap;">${escapeHtml(
              `${cell.demandIndex.toLocaleString()}대`,
            )}</td>
            <td style="${TD_STYLE}text-align:right;white-space:nowrap;">${escapeHtml(
              `${cell.pkltCpctySum.toLocaleString()}면`,
            )}</td>
          </tr>`,
          )
          .join('')

  return `<div style="font-family:${DOC_FONT};color:#171717;width:${A4_WIDTH_PX}px;padding:${DOC_PADDING_PX}px;background:#fff;box-sizing:border-box;">
      <div style="font-size:22px;font-weight:800;margin-bottom:6px;">${escapeHtml(DOC_TITLE)}</div>
      <div style="margin-bottom:24px;">
        <div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(DOC_SUBTITLE)}</div>
        <div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(targetCaption(doc))}</div>
        ${meta ? `<div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(meta)}</div>` : ''}
        <div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(
          `${SHRT_RT_FORMULA_TEXT} · ${minCriterionCaption(doc)}`,
        )}</div>
      </div>

      <div data-pdf-keep-next style="${SECTION_TITLE_STYLE}">${escapeHtml(SUMMARY_TITLE)}</div>
      <table style="width:100%;border-collapse:collapse;font-size:12px;margin-bottom:8px;">
        <tbody>${summary}</tbody>
      </table>
      <div style="font-size:11px;color:#70737c;margin-bottom:${doc.gridNotice ? 8 : 28}px;">${escapeHtml(
        zeroSupplyCaption(doc),
      )}</div>
      ${
        doc.gridNotice
          ? `<p data-pdf-keep style="${NOTE_STYLE}margin-bottom:28px;">${escapeHtml(doc.gridNotice)}</p>`
          : ''
      }

      <div data-pdf-keep-next style="${SECTION_TITLE_STYLE}">${escapeHtml(`${LIST_TITLE_PREFIX}${doc.sectionTitle}`)}</div>
      <div data-pdf-keep-next style="font-size:11px;color:#9ea1a8;margin:-6px 0 10px;">${escapeHtml(
        // 등급 표기는 등급으로 거른 스냅샷에만 — 전체(현행 화면)는 붙이지 않는다
        doc.grade ? `${doc.sectionSub} · 표시 등급 : ${shrtRtGradeNmOf(doc, doc.grade)}` : doc.sectionSub,
      )}</div>
      <table style="width:100%;table-layout:fixed;border-collapse:collapse;font-size:12px;">
        <colgroup>${cols}</colgroup>
        <thead>
          <tr style="background:#f5f6f8;">${head}</tr>
        </thead>
        <tbody>${body}</tbody>
      </table>

      <div data-pdf-keep-next style="${SECTION_TITLE_STYLE}margin-top:28px;">${escapeHtml(`${NOTE_TITLE_PREFIX}${NOTE_TITLE}`)}</div>
      <table style="width:100%;table-layout:fixed;border-collapse:collapse;font-size:12px;margin-bottom:12px;">
        <colgroup>${equalCols(4, contentWidth)}</colgroup>
        <thead>
          <tr style="background:#f5f6f8;">
            <th style="${TH_STYLE}text-align:left;">${escapeHtml('등급 기준 (부족률 구간)')}</th>
            <th style="${TH_STYLE}text-align:right;">${escapeHtml('격자')}</th>
            <th style="${TH_STYLE}text-align:right;">${escapeHtml('민원')}</th>
            <th style="${TH_STYLE}text-align:right;">${escapeHtml('공급 0면 격자')}</th>
          </tr>
        </thead>
        <tbody>${criteria}</tbody>
      </table>
      ${changedSettingsBlockHtml(changedSettingsBoxOf(doc.vrblAjmt, 'LIFEZONE_SPLY'))}
      <p data-pdf-keep style="${NOTE_STYLE}">
        ${escapeHtml(doc.disclaimer)}<br />
        ${escapeHtml(
          // 🔁 D20 — 경계는 설정값이라 숫자를 박지 않고 서버 범례(`lgndTxt`)로 조립한다. 범례가 비면(방어) 구간 나열을 뺀다.
          `등급은 부족률 구간으로 나눕니다${
            doc.gradeCriteria.length > 0 ? `(${doc.gradeCriteria.map((c) => c.lgndTxt).join(' · ')})` : ''
          }. ` +
            '부족률과 평가 대상 격자는 격자 크기·기간·지역에 따라 다시 산출되므로, 다른 조건으로 만든 보고서와는 등급 수를 직접 비교하지 마십시오.',
        )}
      </p>
    </div>`
}

/**
 * 수급 보고서 문서 HTML — 미리보기·PDF·DOCX 공용 진입점.
 *
 * 🔴 `anlsMthd === 'SHRT_RT'` 이면 신규, 그 밖(부재 포함 — 구 스냅샷)은 저장 당시의 사분면 템플릿(D14).
 */
export function buildLifezoneSupplyReportHtml(doc: LifezoneSupplyReportDoc): string {
  if (doc.anlsMthd === 'SHRT_RT') return buildShrtRtHtml(doc)
  return buildLegacyHtml(doc)
}
