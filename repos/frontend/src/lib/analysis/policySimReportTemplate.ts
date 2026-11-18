import type { CstLvlCd, PolicySimReport, PolicySimReportScenario } from '../../api/policySim'
import {
  COEFF_AJMT_MARK,
  CST_LVL_META,
  PLCY_TYPE_NM,
  coeffAjmtNote,
  fmtPct,
  scenarioFullName,
} from '../../components/policysim/policySimView'
import { escapeHtml } from '../report/reportTemplate'

/*
 * 정책 시뮬레이션 시나리오 비교 보고서 본문 생성기.
 *
 * **미리보기 · PDF · DOCX 세 경로가 공유하는 단일 소스다**(투자우선순위·구역추천 템플릿과 같은 규약).
 *   - 시나리오 비교 `내보내기` → 저장 API 응답(201)으로 이 함수 → `exportPdf`/`exportDocx`
 *   - 보고서 목록 → GET 스냅샷으로 이 함수 → 미리보기 모달 · 재다운로드
 *
 * 구성(시안 2026-10-14 개정):
 *   제목 · 부제 · 머리말(대표의 정책 유형) → 1. 시나리오 비교 표 → (2. 분석 계수 조정 내역) → 민원 감소율 막대 →
 *   혼잡도 개선 막대 → 맺음 한계 고지(시안에 없다).
 * 2026-11-13 배포 시안 반영("보고서에 계수를 싣지 않는다" 개정):
 *   계수를 일시 조정한 시나리오는 1절 이름 뒤에 `(계수조정)` · 2절 `분석 계수 조정 내역` 에 `{이름} — {계수 설정값→적용값}`.
 *   조정한 시나리오가 없으면 2절을 두지 않고 막대 절이 2·3절이다(시안과 같다). 내역은 화면 툴팁과 같은 `coeffAjmtNote`.
 *
 * 🔴 **동기 함수다** — 차트 이미지가 없어 기다릴 것이 없다(막대는 HTML 표다).
 * 🔴 **막대는 `<table>` 셀 너비·배경색만으로 그린다** — PDF(html2canvas)와 Word(html-docx-js)에 같은 마크업이
 * 그대로 실린다. `<div>` 막대·CSS 그러데이션·벡터 그림·차트 라이브러리는 Word 에서 빠지므로 쓰지 않는다.
 * 🔴 **표기 헬퍼를 새로 만들지 않는다** — `fmtPct`·`CST_LVL_META`·`PLCY_TYPE_NM`·이름 함수는 화면과 같은 것이다
 * (같은 시나리오가 화면에서 `-26%`, 보고서에서 `-25.78%` 로 보이면 안 된다).
 * 🔴 파일명 상수를 두지 않는다 — 파일 이름은 저장 응답의 `rptpNm` 이다.
 *
 * ⚠️ 결과 HTML 은 `dangerouslySetInnerHTML` 로 그려지고 DOCX 에도 실린다 — 바깥에서 온 값(`anlsRngCn` 등)은
 * 예외 없이 `escapeHtml` 을 거친다. 스타일은 전부 인라인 · 색은 리터럴(hex)이다
 * (Word 와 화면 밖 캡처 노드는 앱 스타일시트와 `var()` 를 모른다).
 */

/** 문서 맨 위 큰 제목 — 날짜·순번 없이 이름 부분만("보고서 제목" 절 ③). */
export const POLICY_SIM_REPORT_TITLE = '정책 시뮬레이션 시나리오 비교'

/** 부제 첫 줄 — 시안 그대로. */
const DOC_SUBTITLE = '제주 주차민원분석 솔루션'

/** 값이 없는 칸 — 화면 `fmtPct` 의 null 표기와 같다. */
const EMPTY = '—'

/* ── 1. 막대 길이 ─────────────────────────────────────── */

/**
 * 막대 길이(정수 %) — 그 절의 **좋은 방향 크기 ÷ 선택 안 최댓값**. 두 절이 같은 함수를 쓴다.
 *   - 입력은 "클수록 좋은" 값이다 — 민원은 부호를 뒤집어(`-cvlcptRdctRt`), 혼잡은 그대로(`cngstImpvRt`) 넘긴다.
 *   - 0 이하(나쁜 방향: 민원 증가 · 혼잡 악화)·null·비유한 값은 **0**(빈 막대).
 *   - 최댓값이 0 이면 전부 0 — 0 으로 나누지 않는다.
 */
export function barPct(values: ReadonlyArray<number | null | undefined>): number[] {
  const good = values.map((v) => (v != null && Number.isFinite(v) && v > 0 ? v : 0))
  const max = good.reduce((m, v) => Math.max(m, v), 0)
  return good.map((v) => (max > 0 ? Math.min(100, Math.round((v / max) * 100)) : 0))
}

/* ── 2. 스타일 ─────────────────────────────────────────── */

/*
 * 아래 값은 투자우선순위·구역추천 템플릿과 **같다** — 시안의 모양이 같다
 * (그쪽 상수는 모듈 private 이라 같은 값을 여기 둔다).
 */
const A4_WIDTH_PX = 794
const DOC_PADDING_PX = 40
const DOC_FONT =
  "'Pretendard', 'Wanted Sans', system-ui, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"
const ACCENT_COLOR = '#0066ff'
const MUTED = '#70737c'
const BAR_TRACK = '#EEF0F3'
/** 추천 행 막대 — 시안의 주황. 나머지 행은 `ACCENT_COLOR`(파랑). */
const BAR_RCMD = '#f97316'

const SECTION_TITLE_STYLE =
  `border-left:4px solid ${ACCENT_COLOR};padding-left:10px;font-size:15px;font-weight:800;` +
  'line-height:1.4;margin:28px 0 12px;'

const TH_STYLE = 'padding:6px 8px;border-bottom:1px solid #e5e6ea;font-weight:700;text-align:left;'
const TD_STYLE = 'padding:5px 8px;border-bottom:1px solid #f0f0f0;'

/** 막대 이름 칸 고정 폭(px) — `{순번}. {전체 이름}` 이 이 폭 안에서 줄바꿈으로 접힌다(말줄임 없음). */
const BAR_NAME_W = 240
/** 막대 값 칸 고정 폭(px) — `-23% (추천)` 이 한 줄에 든다. */
const BAR_VALUE_W = 90
const BAR_H = 14

/* ── 3. 조각 ──────────────────────────────────────────── */

/** 비용 수준 라벨(`저`/`중`/`고`/`미정`) — 모르는 코드·null 은 `—`(자기 키만 본다). */
function cstLabel(cd: CstLvlCd | null): string {
  return cd != null && Object.prototype.hasOwnProperty.call(CST_LVL_META, cd)
    ? CST_LVL_META[cd].label
    : EMPTY
}

/** 1절 시나리오 비교 표. 흡수 포화(`excsYn`) 행은 민원 감소 칸에 `*`(맺음 주석이 설명한다). */
function compareTableHtml(scenarios: PolicySimReportScenario[]): string {
  const columns = ['순번', '시나리오', '민원 감소', '혼잡 개선', '비용 수준', '추천']
  const head = columns.map((c) => `<th style="${TH_STYLE}">${escapeHtml(c)}</th>`).join('')
  const body = scenarios
    .map((s) => {
      const r = s.result
      const cells = [
        { text: String(s.seq), extra: 'width:36px;white-space:nowrap;' },
        {
          text: `${scenarioFullName(s.params, r.anlsRngCn)}${coeffAjmtNote(r.vrblAjmt) ? ` ${COEFF_AJMT_MARK}` : ''}`,
          extra: 'font-weight:700;word-break:keep-all;',
        },
        {
          text: `${fmtPct(r.cvlcptRdctRt)}${r.excsYn ? '*' : ''}`,
          extra: 'width:64px;white-space:nowrap;',
        },
        { text: fmtPct(r.cngstImpvRt), extra: 'width:64px;white-space:nowrap;' },
        { text: cstLabel(r.cstLvlCd), extra: 'width:60px;white-space:nowrap;' },
        { text: s.rcmdYn ? '추천' : EMPTY, extra: 'width:44px;white-space:nowrap;' },
      ]
      return `<tr>${cells
        .map((c) => `<td style="${TD_STYLE}${c.extra}">${escapeHtml(c.text)}</td>`)
        .join('')}</tr>`
    })
    .join('')
  return `<table style="width:100%;border-collapse:collapse;font-size:11px;line-height:1.4;">
        <thead><tr style="background:#f5f6f8;">${head}</tr></thead>
        <tbody>${body}</tbody>
      </table>`
}

/** 막대 한 줄의 재료. */
interface BarRow {
  name: string
  /** 정수 % — `barPct` 결과. */
  pct: number
  /** 값 칸 — `fmtPct` 그대로(null 은 `—`). */
  value: string
  rcmd: boolean
}

/**
 * 막대 칸 — 안쪽 `<table width="100%">` 한 줄. 채운 셀(색) + 나머지 셀(회색).
 * 🔴 너비는 **HTML 속성과 CSS 를 함께** 준다 — Word 변환은 CSS 너비를 버리는 경우가 있다.
 * 🔴 0% 셀은 만들지 않는다 — `width="0%"` 셀은 브라우저마다 1px 로 그려져 막대가 있는 것처럼 보인다
 * (같은 이유로 100% 면 회색 셀을 뺀다).
 */
function barCellHtml(pct: number, color: string): string {
  const rest = 100 - pct
  const cell = (w: number, bg: string) =>
    `<td width="${w}%" style="width:${w}%;height:${BAR_H}px;padding:0;background:${bg};"></td>`
  return `<table width="100%" style="width:100%;border-collapse:collapse;table-layout:fixed;"><tr>${
    pct > 0 ? cell(pct, color) : ''
  }${rest > 0 ? cell(rest, BAR_TRACK) : ''}</tr></table>`
}

/** 막대 절(2·3절 — 계수 조정 내역이 있으면 3·4절) 가로 막대 — 한 행 = `<tr>` 하나: 이름 칸 · 막대 칸 · 값 칸. 추천 행만 주황 + ` (추천)`. */
function barChartHtml(rows: BarRow[]): string {
  const body = rows
    .map(
      (r) => `<tr>
          <td width="${BAR_NAME_W}" style="width:${BAR_NAME_W}px;padding:4px 10px 4px 0;text-align:left;font-size:11px;font-weight:700;line-height:1.35;word-break:keep-all;">${escapeHtml(r.name)}</td>
          <td style="padding:4px 0;">${barCellHtml(r.pct, r.rcmd ? BAR_RCMD : ACCENT_COLOR)}</td>
          <td width="${BAR_VALUE_W}" style="width:${BAR_VALUE_W}px;padding:4px 0 4px 10px;font-size:11px;color:${MUTED};white-space:nowrap;">${escapeHtml(
            `${r.value}${r.rcmd ? ' (추천)' : ''}`,
          )}</td>
        </tr>`,
    )
    .join('')
  return `<table style="width:100%;border-collapse:collapse;">${body}</table>`
}

/**
 * 맺음 한계 고지 — ①②는 항상, ③은 **해당 행이 있을 때만**(민원 증가 행 · 흡수 포화 행 각각).
 * 시안에는 없지만 숫자만 나가면 `+137%` 같은 값이 개선으로 읽힌다.
 */
function noteLines(scenarios: PolicySimReportScenario[]): string[] {
  const hasIncrease = scenarios.some((s) => {
    const rt = s.result.cvlcptRdctRt
    return rt != null && rt > 0
  })
  const hasSaturated = scenarios.some((s) => s.result.excsYn)
  return [
    '※ 혼잡 지수는 주차 혼잡(정상 이용 + 불법주차 ÷ 공급 면수)이며 도로 통행량이 아닙니다.',
    '※ 계수는 가정값이며 인과 추정이 아닙니다 — 실제 정책 효과를 보장하지 않습니다.',
    hasIncrease
      ? '※ 민원 감소율이 양수(+)인 행은 민원이 늘어난다는 뜻입니다(요금제 적용). 그 행의 막대는 비워 둡니다.'
      : '',
    hasSaturated
      ? '※ 민원 감소에 * 이 붙은 행은 효과가 상한(전량 흡수)에 걸린 흡수 포화 결과입니다.'
      : '',
  ].filter(Boolean)
}

/* ── 4. 본문 ──────────────────────────────────────────── */

/**
 * 미리보기·PDF·DOCX 공용 본문 — 저장 응답·스냅샷을 그대로 넘긴다(동기).
 * 머리말은 **대표(`seq === headSeq`)** 의 정책 유형만 쓴다(대상 지역 없음).
 */
export function buildPolicySimReportHtml(report: PolicySimReport): string {
  const scenarios = [...report.scenarios].sort((a, b) => a.seq - b.seq)
  const head = scenarios.find((s) => s.seq === report.headSeq) ?? scenarios[0]
  const headLine = head
    ? `정책 유형: ${PLCY_TYPE_NM[head.params.plcyType] ?? head.params.plcyType}`
    : ''

  // 민원은 감소가 좋은 방향이라 부호를 뒤집어 넘긴다.
  const cvlcptPct = barPct(
    scenarios.map((s) => (s.result.cvlcptRdctRt == null ? null : -s.result.cvlcptRdctRt)),
  )
  const cngstPct = barPct(scenarios.map((s) => s.result.cngstImpvRt))

  // 막대 절 이름은 1절 표의 순번·시나리오 칸과 같은 값이다 — 표와 막대를 순번으로 맞춰 읽는다.
  const barName = (s: PolicySimReportScenario) =>
    `${s.seq}. ${scenarioFullName(s.params, s.result.anlsRngCn)}`
  const cvlcptRows: BarRow[] = scenarios.map((s, i) => ({
    name: barName(s),
    pct: cvlcptPct[i],
    value: fmtPct(s.result.cvlcptRdctRt),
    rcmd: s.rcmdYn,
  }))
  const cngstRows: BarRow[] = scenarios.map((s, i) => ({
    name: barName(s),
    pct: cngstPct[i],
    value: fmtPct(s.result.cngstImpvRt),
    rcmd: s.rcmdYn,
  }))

  const subLines = [DOC_SUBTITLE, headLine]
    .filter(Boolean)
    .map(
      (line) =>
        `<div style="font-size:12px;color:${MUTED};line-height:1.6;">${escapeHtml(line)}</div>`,
    )
    .join('')
  const sectionTitle = (text: string) =>
    `<div data-pdf-keep-next style="${SECTION_TITLE_STYLE}">${escapeHtml(text)}</div>`
  // 2절 `분석 계수 조정 내역`. 조정한 시나리오만 · 순번 순.
  // 시안(배포본 `notes` 섹션)과 같은 모양 — 회색 상자 · 11px #555. 이름은 막대 절과 같은 `{순번}. {시나리오}`
  // (1절 표 순번과 맞춰 읽는다 — 2026-11-13 사용자 요청).
  const ajmtLines = scenarios
    .map((s) => ({ s, note: coeffAjmtNote(s.result.vrblAjmt) }))
    .filter((x): x is { s: PolicySimReportScenario; note: string } => x.note != null)
    .map(({ s, note }) => `${barName(s)} — ${note}`)
  const ajmtHtml = `<div style="padding:14px 16px;background:#f5f6f8;border-radius:8px;">${ajmtLines
    .map(
      (line) =>
        `<div style="font-size:11px;color:#555;line-height:1.7;word-break:keep-all;">· ${escapeHtml(line)}</div>`,
    )
    .join('')}</div>`
  // 절 번호 — 2절이 없으면 막대 절이 2·3이다.
  const no = ajmtLines.length > 0 ? 3 : 2

  const notes = noteLines(scenarios)
    .map(
      (note) =>
        `<div style="font-size:11px;color:${MUTED};line-height:1.6;">${escapeHtml(note)}</div>`,
    )
    .join('')

  return `<div style="font-family:${DOC_FONT};color:#171717;width:${A4_WIDTH_PX}px;padding:${DOC_PADDING_PX}px;background:#fff;box-sizing:border-box;">
      <div style="font-size:22px;font-weight:800;margin-bottom:6px;">${escapeHtml(POLICY_SIM_REPORT_TITLE)}</div>
      ${subLines}
      ${sectionTitle('1. 시나리오 비교')}
      ${compareTableHtml(scenarios)}
      ${ajmtLines.length > 0 ? `${sectionTitle('2. 분석 계수 조정 내역')}<div data-pdf-keep>${ajmtHtml}</div>` : ''}
      ${sectionTitle(`${no}. 시나리오별 민원 감소율 비교`)}
      ${barChartHtml(cvlcptRows)}
      ${sectionTitle(`${no + 1}. 시나리오별 혼잡도 개선 비교`)}
      ${barChartHtml(cngstRows)}
      <div data-pdf-keep style="margin-top:20px;padding-top:12px;border-top:1px solid #e5e6ea;">${notes}</div>
    </div>`
}
