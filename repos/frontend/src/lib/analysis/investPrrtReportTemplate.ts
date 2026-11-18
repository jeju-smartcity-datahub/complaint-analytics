import type { InvestPrrtLvlCd, InvestPrrtReport } from '../../api/investPrrt'
import { resolveCssColor } from '../../components/analysis/hotspotGrade'
import {
  PRRT_LVL_COLOR_VAR,
  VRBL_SCR_FIELD,
  buildLegendRows,
  buildWeightNote,
  fmtCnt,
  fmtScr,
  isScrVrbl,
  prrtLvlLabel,
} from '../../components/analysis/investPrrtView'
import {
  basisBlockHtml,
  changedSettingsBlockHtml,
  changedSettingsBoxOf,
  escapeHtml,
  type ChangedSettingsBox,
} from '../report/reportTemplate'

/*
 * 투자우선순위 보고서 본문 생성기.
 *
 * **미리보기 · PDF · DOCX 세 경로가 공유하는 단일 소스다**(집중구역·생활권 템플릿과 같은 규약).
 *   - 분석 화면 `내보내기` → 저장 API 응답(201)으로 이 함수 → `exportPdf`/`exportDocx`
 *   - 보고서 목록 → GET 스냅샷으로 이 함수 → 미리보기 모달 · 재다운로드
 *
 * 두 단계로 나뉜다 — **스냅샷 → 문서 모델**(`buildInvestPrrtReportModel`, DOM 없이 돈다) →
 * **문서 모델 → HTML**(`buildInvestPrrtReportHtml`). 구성은 시안의 내보내기 그대로다:
 * 제목 · 부제(가중치 안내문) · ① 차트 · ② 목록 표 · ③ 세부 지표 표.
 *
 * 🔴 **`lots` 를 전부 담는다** — 화면의 상위 N 이 아니라 행정구역 필터를 통과한 전체다. 차트만 상위 20곳.
 * 🔴 순위 열은 `rnk`(전 지역 순위)가 아니라 **이 보고서에 담긴 `lots` 안에서의 순번(1..n)** 이다 —
 * 화면과 같은 원칙이다(사용자 결정 2026-10-14). `lots` 는 `rnk` 오름차순으로 오고 그 순서를 유지한 채
 * 번호만 매긴다. 전 지역 보고서는 `rnk` 와 같은 숫자가 나온다. 스냅샷 객체는 변형하지 않는다.
 * 🔴 `smry` 를 쓰지 않는다 — 필터를 걸어도 전 지역 값이라 "이 보고서의 건수" 가 아니다.
 * 🔴 `warnings`·`scoreFormula`·한계 안내문은 넣지 않는다(사용자 결정 — 시안의 파일 구성 그대로).
 * 🔴 문구를 박지 않는다 — 부제·세부 지표의 열·등급 라벨이 전부 스냅샷(`weights`·`gradeCriteria`)에서 온다.
 *
 * ⚠️ 결과 HTML 은 `dangerouslySetInnerHTML` 로 그려지고 DOCX 에도 실린다 — 바깥에서 온 값은
 * 예외 없이 `escapeHtml` 을 거친다(주차장 이름·주소는 원천 문자열이다).
 * 스타일은 전부 인라인 · 색은 리터럴(hex)이다 — Word 는 앱 스타일시트와 `var()` 를 모른다.
 */

/* ── 1. 문서 모델 ─────────────────────────────────────── */

/** 템플릿이 실제로 읽는 칸. 저장 응답·스냅샷(`InvestPrrtReport`)을 그대로 넘길 수 있다. */
export type InvestPrrtReportDoc = Pick<
  InvestPrrtReport,
  'regionNm' | 'anlsPrdExpln' | 'crtYmd' | 'weights' | 'gradeCriteria' | 'lots' | 'vrblAjmt'
>

/** 차트 막대 한 줄. */
export interface InvestPrrtChartItem {
  label: string
  /** 종합점수 (0~100) — 막대 길이다. */
  value: number
  valueLabel: string
  /** 막대 색을 고르는 등급. */
  prrtLvlCd: InvestPrrtLvlCd
}

export type InvestPrrtReportSection =
  | { type: 'chart'; title: string; items: InvestPrrtChartItem[] }
  | { type: 'table'; title: string; columns: string[]; rows: string[][] }
  /** 표 아래 기준 상자 — 섹션 제목 줄 없이 상자만 그린다. */
  | { type: 'basis'; title: string; lines: string[] }
  /** `적용 설정` 상자. 구 스냅샷 ①(`vrblAjmt` 없음)이면 섹션 자체가 없다. */
  | { type: 'settings'; box: ChangedSettingsBox }

export interface InvestPrrtReportModel {
  /** 내려받는 파일명(확장자 제외). */
  fileBase: string
  title: string
  /**
   * 가중치 안내문. 지표가 하나도 없으면 빈 문자열(줄 생략).
   * 적용 내역이 있는 스냅샷은 빈 문자열이다(가중치는 `적용 설정` 상자가 맡는다 · 대체 문장 없음).
   */
  subtitle: string
  /** `분석 지역 : … · 조회 기간 : …`. 둘 다 없으면 빈 문자열. */
  meta: string
  sections: InvestPrrtReportSection[]
}

/** 파일명 — 시안 그대로. 확장자는 내보내기가 붙인다. */
export const INVEST_PRRT_FILE_BASE = '투자보강_우선순위'

/** 문서 제목 — PDF·Word·엑셀(시트 1 A1) 공용. */
export const DOC_TITLE = '투자·보강 우선순위'

/** 기준 상자 제목 — 🔴 이 보고서만 공통 `단계 구분 기준` 이 아니다. */
const RANK_BASIS_TITLE = '순위 산정 기준'

/** 차트에 그리는 막대 수. 전부(509곳) 그리면 읽을 수 없다 — 표가 전체를 담는다. */
const CHART_TOP_N = 20

/** 값이 없는 칸. */
const EMPTY = '-'

/**
 * 준공연도 칸 — `2001 (25년 경과)`. `instlYm`(YYYYMM)이 없으면 `-`.
 * 🔴 경과 연수는 **보고서 생성 연도** 기준이다 — "오늘" 로 세면 같은 보고서를 해를 넘겨 다시 열 때
 * 숫자가 달라진다. 생성 연도를 모르면 연도만 적는다. 엑셀도 같은 함수를 쓴다.
 */
export function instlCell(instlYm: string | null, baseYear: number | null): string {
  if (!instlYm || !/^\d{4}/.test(instlYm)) return EMPTY
  const year = Number(instlYm.slice(0, 4))
  if (baseYear == null || baseYear < year) return String(year)
  return `${year} (${baseYear - year}년 경과)`
}

/** 스냅샷 → 문서 모델. DOM 을 쓰지 않는다. */
export function buildInvestPrrtReportModel(doc: InvestPrrtReportDoc): InvestPrrtReportModel {
  const { lots, gradeCriteria } = doc
  // 점수 필드를 아는 지표만 — 설정에서 끈 지표는 `weights` 에 없어 열도 생기지 않는다.
  const vrbls = doc.weights.filter((w) => isScrVrbl(w.vrblCd))
  const baseYear = /^\d{4}/.test(doc.crtYmd ?? '') ? Number(doc.crtYmd.slice(0, 4)) : null

  const meta = [
    doc.regionNm ? `분석 지역 : ${doc.regionNm}` : '',
    doc.anlsPrdExpln ? `조회 기간 : ${doc.anlsPrdExpln}` : '',
  ]
    .filter(Boolean)
    .join(' · ')

  const chartLots = lots.slice(0, CHART_TOP_N)
  /** `null` = 구 스냅샷 ① — 상자 없이 종전 가중치 부제를 남긴다. */
  const settingsBox = changedSettingsBoxOf(doc.vrblAjmt, 'INVEST')
  const settingsSection: InvestPrrtReportSection[] = settingsBox ? [{ type: 'settings', box: settingsBox }] : []

  return {
    fileBase: INVEST_PRRT_FILE_BASE,
    title: DOC_TITLE,
    subtitle: settingsBox ? '' : buildWeightNote(doc.weights),
    meta,
    sections: [
      {
        type: 'chart',
        // 잘렸을 때만 그 사실을 제목에 적는다 — 막대 20개를 전체로 읽지 않게.
        title:
          lots.length > CHART_TOP_N
            ? `주차장별 종합점수 (상위 ${CHART_TOP_N}곳)`
            : '주차장별 종합점수',
        items: chartLots.map((l) => ({
          label: l.pkltNm,
          value: l.snthsScr,
          valueLabel: `${fmtScr(l.snthsScr)}점`,
          prrtLvlCd: l.prrtLvlCd,
        })),
      },
      // 첫 페이지(차트 바로 뒤 · 목록 표 앞)에 둔다. 🔴 목록 표 뒤로 옮기지 않는다 — 표가 수백 행이라
      // PDF 한가운데에 묻혀 상자가 없는 것처럼 보였다(2026-11-13 사용자 지적). 다른 4종은 기준 상자 뒤라 첫 화면에 있다.
      ...settingsSection,
      {
        type: 'table',
        title: '투자·보강 우선순위 목록',
        columns: ['순위', '주차장', '종합점수', '우선순위', '주소', '면수', '준공연도'],
        rows: lots.map((l, i) => [
          String(i + 1),
          l.pkltNm,
          `${fmtScr(l.snthsScr)}점`,
          prrtLvlLabel(l.prrtLvlCd, gradeCriteria),
          l.pkltAddr || EMPTY,
          l.pkltCpcty == null ? EMPTY : `${fmtCnt(l.pkltCpcty)}면`,
          instlCell(l.instlYm, baseYear),
        ]),
      },
      // 🔴 등급 열이 있는 목록 표 바로 아래다 — 표가 수백 행이라 문서 끝에 두면 너무 멀다.
      // 문구는 화면 범례와 같은 함수이고 임계는 스냅샷 `minScr` 이다. 기준이 없으면 섹션을 두지 않는다.
      ...(gradeCriteria.length > 0
        ? [
            {
              type: 'basis' as const,
              title: RANK_BASIS_TITLE,
              lines: [`우선순위 : ${buildLegendRows(gradeCriteria).map((r) => r.label).join(' · ')}`],
            },
          ]
        : []),
      {
        type: 'table',
        title: vrbls.length
          ? `세부 지표 (${vrbls.map((w) => w.vrblNm).join(' · ')})`
          : '세부 지표',
        columns: ['순위', '주차장', ...vrbls.map((w) => w.vrblNm)],
        rows: lots.map((l, i) => [
          String(i + 1),
          l.pkltNm,
          ...vrbls.map((w) => (isScrVrbl(w.vrblCd) ? fmtScr(l[VRBL_SCR_FIELD[w.vrblCd]]) : EMPTY)),
        ]),
      },
    ],
  }
}

/* ── 2. HTML ──────────────────────────────────────────── */

/** A4 문서 폭(px) — `reportTemplate.ts` 의 값과 같다(그쪽은 모듈 private 다). */
const A4_WIDTH_PX = 794
const DOC_PADDING_PX = 40
const DOC_FONT = "'Pretendard', 'Wanted Sans', system-ui, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"
const ACCENT_COLOR = '#0066ff'
const MUTED = '#70737c'
const BAR_TRACK = '#EEF0F3'

const SECTION_TITLE_STYLE =
  `border-left:4px solid ${ACCENT_COLOR};padding-left:10px;font-size:15px;font-weight:800;` +
  'line-height:1.4;margin:28px 0 12px;'

/*
 * 🔴 표가 수백 행이다(전 지역이면 509행 × 2표). 다른 보고서(15행)보다 칸을 좁게 잡아 문서 길이를 줄인다 —
 * PDF 는 문서 전체를 한 장의 캔버스로 캡처하므로 길이가 곧 캔버스 높이다.
 */
const TH_STYLE = 'padding:6px 8px;border-bottom:1px solid #e5e6ea;font-weight:700;text-align:left;'
const TD_STYLE = 'padding:5px 8px;border-bottom:1px solid #f0f0f0;'

/** `rgb(255, 66, 66)` / `rgb(255 66 66)` → `#ff4242`. Word 는 hex 를 가장 확실하게 읽는다. */
export function toHexColor(color: string): string {
  const m = /^rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(color)
  if (!m) return color
  return `#${m
    .slice(1)
    .map((v) => Number(v).toString(16).padStart(2, '0'))
    .join('')}`
}

/** 등급 → 실제 색. 화면(핀·범례)과 같은 토큰을 읽는다. 등급이 3개라 한 번씩만 해석한다. */
const lvlColorCache = new Map<InvestPrrtLvlCd, string>()
function lvlColor(cd: InvestPrrtLvlCd): string {
  const hit = lvlColorCache.get(cd)
  if (hit) return hit
  const color = toHexColor(resolveCssColor(PRRT_LVL_COLOR_VAR[cd] ?? PRRT_LVL_COLOR_VAR.L))
  lvlColorCache.set(cd, color)
  return color
}

/**
 * 막대 차트 — `[이름] [막대] [점수]` 를 표로 짠다(Word 가 flex·inline-block 을 접지 못한다).
 * 막대 길이는 점수 그대로다(0~100점 = 0~100%). 막대도 2칸 표라 Word 에서 폭이 유지된다.
 */
function chartHtml(items: InvestPrrtChartItem[]): string {
  const rows = items
    .map((it) => {
      const pct = Math.max(0, Math.min(100, Number.isFinite(it.value) ? it.value : 0))
      const rest = 100 - pct
      return `<tr>
          <td style="width:200px;padding:3px 8px 3px 0;text-align:right;font-size:11px;word-break:keep-all;">${escapeHtml(it.label)}</td>
          <td style="padding:3px 0;">
            <table style="width:100%;border-collapse:collapse;table-layout:fixed;background:${BAR_TRACK};"><tr>
              ${pct > 0 ? `<td style="width:${pct.toFixed(2)}%;height:14px;padding:0;background:${escapeHtml(lvlColor(it.prrtLvlCd))};"></td>` : ''}
              ${rest > 0 ? `<td style="width:${rest.toFixed(2)}%;height:14px;padding:0;"></td>` : ''}
            </tr></table>
          </td>
          <td style="width:56px;padding:3px 0 3px 8px;font-size:11px;font-weight:700;white-space:nowrap;">${escapeHtml(it.valueLabel)}</td>
        </tr>`
    })
    .join('')
  return `<table style="width:100%;border-collapse:collapse;">${rows}</table>`
}

/** 표 — 앞 두 열(순위·주차장)만 폭·굵기를 따로 준다. */
function tableHtml(columns: string[], rows: string[][]): string {
  const head = columns.map((c) => `<th style="${TH_STYLE}">${escapeHtml(c)}</th>`).join('')
  const body = rows
    .map(
      (row) =>
        `<tr>${row
          .map((cell, i) => {
            const extra =
              i === 0
                ? 'width:36px;white-space:nowrap;'
                : i === 1
                  ? 'font-weight:700;word-break:keep-all;'
                  : 'word-break:keep-all;'
            return `<td style="${TD_STYLE}${extra}">${escapeHtml(cell)}</td>`
          })
          .join('')}</tr>`,
    )
    .join('')
  return `<table style="width:100%;border-collapse:collapse;font-size:11px;line-height:1.4;">
        <thead><tr style="background:#f5f6f8;">${head}</tr></thead>
        <tbody>${body}</tbody>
      </table>`
}

/** 문서 모델 → HTML. */
export function renderInvestPrrtReportModel(model: InvestPrrtReportModel): string {
  const caption = (text: string) =>
    text
      ? `<div style="font-size:12px;color:${MUTED};line-height:1.6;">${escapeHtml(text)}</div>`
      : ''

  const sections = model.sections
    .map((s) =>
      // 🔴 `settings` 를 먼저 가른다 — 아래 3항식의 끝 분기는 `s.columns`·`s.items` 를 읽는다.
      s.type === 'settings'
        ? changedSettingsBlockHtml(s.box)
        : s.type === 'basis'
        ? basisBlockHtml(s.title, s.lines)
        : `<div data-pdf-keep-next style="${SECTION_TITLE_STYLE}">${escapeHtml(s.title)}</div>` +
          (s.type === 'chart' ? chartHtml(s.items) : tableHtml(s.columns, s.rows)),
    )
    .join('')

  return `<div style="font-family:${DOC_FONT};color:#171717;width:${A4_WIDTH_PX}px;padding:${DOC_PADDING_PX}px;background:#fff;box-sizing:border-box;">
      <div style="font-size:22px;font-weight:800;margin-bottom:6px;">${escapeHtml(model.title)}</div>
      ${caption(model.subtitle)}
      ${caption(model.meta)}
      ${sections}
    </div>`
}

/** 미리보기·PDF·DOCX 공용 본문 — 스냅샷을 그대로 넘긴다. */
export function buildInvestPrrtReportHtml(doc: InvestPrrtReportDoc): string {
  return renderInvestPrrtReportModel(buildInvestPrrtReportModel(doc))
}
