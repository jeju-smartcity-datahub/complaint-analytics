import type {
  ComplaintReportDoc,
  CrdnCompareItem,
  EmdCompareItem,
  NegEmdCompareItem,
  SentimentDist,
  ShortageCompareItem,
} from '../../api/complaints'
import { GRADE_BASIS_TITLE, basisBlockHtml, escapeHtml } from '../report/reportTemplate'
import { PCT_10, PCT_25, PCT_50, pickRiskGrade } from './riskGrade'

/*
 * 민원현황 보고서 본문 HTML 생성기 (TSK-4164 Step 7 · TSK-4197 Step 7 에서 7섹션으로).
 *
 * 원본은 `docs/report/민원현황2.pdf` 이고 **7섹션 구조를 그대로 옮긴다**
 * (🔁 2026-10-02 — 종전 시안 `민원현황.pdf` 의 5섹션 사이에 §4 단속 순위 · §5 수요부족 순위가
 * 끼어들어 기존 §4·§5 가 §6·§7 로 밀렸다).
 * `buildHotspotReportHtml`(TSK-4078 Step 8)과 **같은 규약**이다 —
 * 미리보기 · PDF · DOCX 세 경로가 이 함수 하나의 결과만 쓴다. 화면에서 본 것과
 * 내려받은 파일의 내용이 갈리면 "표가 틀렸다" 로 읽히기 때문이다.
 *
 * 🔴 결과 문자열은 `dangerouslySetInnerHTML` 로 그려지고 DOCX 에도 그대로 실린다.
 *    따라서 **바깥에서 들어온 값은 예외 없이 `escapeHtml` 을 통과시킨다** —
 *    `emdNm`·`topTypeNm`·`regionNm`·`anlsPrdExpln` 은 전부 서버 응답이다.
 *
 * 🔴 스타일은 전부 인라인이다. DOCX 변환기(html-docx-js)가 외부 스타일시트를 따라가지 않고,
 *    PDF 는 화면 밖 임시 노드를 캡처하므로 앱 CSS 가 적용된다는 보장이 없다.
 *    같은 이유로 막대는 `<div>` + `width:N%` 로만 그린다(그리기 라이브러리·비트맵 요소는
 *    DOCX 로 넘어가지 않는다).
 *
 * 🔴 `NegEmdCompareItem.prevNegativeCount` 는 **쓰지 않는다**(백엔드 D9). 타입에는 필수로 선언돼
 *    있지만 백엔드 VO 에 그 컴포넌트가 없다 — 쓰면 컴파일은 통과하고 값만 `undefined` 다.
 * 🔁 `EmdCompareItem.prevCnt` 는 TSK-4311 부터 오며 §3 `전기 민원` 열이 쓴다.
 *    구 스냅샷에는 키가 없어 `—` 로 그린다(`formatPrevCount`).
 *
 * 🔴 헤더 메타는 서버가 만든 `regionNm`·`anlsPrdExpln` 을 그대로 쓴다(D8).
 *    샘플 PDF 헤더의 `조회 기간: 어제` 같은 **상대 표기는 만들지 않는다** —
 *    스냅샷은 몇 달 뒤에 열리고, 그때 `어제` 는 아무 뜻도 없다.
 *
 * 🔴 0건 보고서가 정상 입력이다(D5). 배열이 비어도 **섹션을 숨기지 않고**
 *    표·막대 자리에 빈 상태 한 줄을 그린다 — 문서 구조가 보고서마다 달라지면 목차가 흔들린다.
 */

/* ── 1. 고정 문구·치수 ────────────────────────────────── */

/** 문서 제목 — 샘플 PDF 고정 문구(`rptpNm` 을 쓰지 않는다 — 그쪽은 파일명·모달 제목용). */
const DOC_TITLE = '민원현황'

/** 부제 1 — 샘플 PDF 고정 문구. */
const DOC_SUBTITLE = '제주 주차민원분석 솔루션'

/**
 * A4 문서 폭(px). 210mm @ 96dpi ≈ 794px.
 *
 * ⚠️ 출처는 `src/lib/report/reportTemplate.ts` 의 `A4_WIDTH_PX` 지만 그쪽은 **모듈 private** 라
 * import 할 수 없다. `hotspotReportTemplate.ts` 와 같은 이유로 **같은 값을 여기 선언**한다 —
 * 값이 바뀌면 세 곳을 함께 고쳐야 한다.
 */
const A4_WIDTH_PX = 794

/**
 * 폰트 스택은 **토큰(`var(--font-body)`)이 아니라 리터럴**로 적는다.
 * DOCX 는 앱 스타일시트 없이 Word 가 직접 렌더하므로 `var()` 가 해석되지 않아 폰트가 통째로 날아간다.
 * 값은 `tokens.css` 의 `--font-body` 와 동일.
 */
const DOC_FONT = "'Pretendard', 'Wanted Sans', system-ui, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"

/** 섹션 제목 왼쪽 파란 막대(샘플 PDF). */
const SECTION_ACCENT = '#2563EB'

/**
 * 섹션 2 막대 색 — `components/complaints/legendConstants.ts` 의 `COLOR_SEVERE` ·
 * `COLOR_WARNING` · `COLOR_CAUTION` · `NEUTRAL_COLOR` 와 **같은 값**이다.
 *
 * 🔁 **2026-10-02 (TSK-4197) — 색을 고르는 기준이 바뀌었다.** 종전엔 `1위 빨강 / 2·3위 주황 /
 * 나머지 파랑` 고정이었다(시안 PDF §2 를 눈으로 보고 옮긴 것). 🔴 그 결과 **같은 동이 화면에선
 * 빨강인데 보고서에선 파랑**이 됐다 — 67개 zone 기준 15개 순위 중 **11개가 어긋났다**(2026-10-02
 * 실측: 화면은 상위 10%=1~6위가 빨강인데 보고서는 1위만 빨강). 이제 **화면과 같은
 * `pickRiskGrade` 백분위**를 쓴다. §4·§5 도 같은 함수를 쓰므로 문서 전체가 한 규칙이다.
 *
 * 🔴 import 하지 않고 리터럴로 둔 이유는 **DOCX 가 CSS 변수를 해석하지 못해서가 아니라**
 * (그쪽도 hex 리터럴이다) 문서 템플릿(`lib/analysis`)이 화면 컴포넌트
 * (`components/complaints`)를 역참조하지 않게 하기 위해서다 — 대신 값이 갈리지 않도록
 * 여기에 출처를 적어 둔다. 색을 바꾸면 두 곳을 함께 고친다.
 * 🔴 `GRADE_BASIS_LINES`(TSK-4312)가 이 색의 **이름**을 적는다 — 색을 바꾸면 그 문구도 함께 고친다.
 */
const GRADE_COLOR_SEVERE = '#DC2626'
const GRADE_COLOR_WARNING = '#F97316'
const GRADE_COLOR_CAUTION = '#2563EB'
/** 등급 밖(백분위 50% 초과) — 화면 지도의 `NEUTRAL_COLOR` 와 같은 값. */
const GRADE_COLOR_NEUTRAL = '#94A3B8'

/**
 * 감정 3색 — `legendConstants.ts` 의 `COLOR_SENTIMENT_POSITIVE`(#22C55E) ·
 * `COLOR_SENTIMENT_NEUTRAL`(#94A3B8) · `COLOR_SENTIMENT_NEGATIVE`(#DC2626) 과 **같은 값**이다.
 * 화면(`NegEmdCompareCard`)의 감정 막대와 문서의 감정 막대가 같은 색이어야 한다.
 */
const SENTIMENT_POSITIVE = '#22C55E'
const SENTIMENT_NEUTRAL = '#94A3B8'
const SENTIMENT_NEGATIVE = '#DC2626'

/** 막대 뒤 트랙(빈 부분) 색 — 샘플 PDF 의 연회색. */
const BAR_TRACK = '#EEF0F3'

/** 표 머리글 칸 공통 스타일. `text-align` 만 열마다 다르다. */
const TH_STYLE = 'padding:8px 10px;border-bottom:1px solid #e5e6ea;font-weight:700;'

/** 표 본문 칸 공통 스타일. */
const TD_STYLE = 'padding:8px 10px;border-bottom:1px solid #f0f0f0;'

/** 보조 텍스트 색(샘플 PDF 의 회색 라벨). */
const MUTED = '#70737c'

/**
 * 🔴 상위 몇 행까지 그릴지 — **자르는 곳은 이 템플릿 한 곳이다**(샘플 PDF 와 동일하게 15).
 * 서버는 전체를 저장한다(스냅샷을 자르면 나중에 더 보여줄 수가 없다).
 *
 * ⚠️ 🔁 **2026-10-02 — 이 상한은 이제 §6·§7 전용이다.** §2·§3 도 `gradedRows` 로 옮겼고
 * (화면 민원 카드와 같은 행을 보이기 위해), §4·§5 는 처음부터 상한이 없었다.
 * §6·§7 만 남은 이유는 그쪽 화면 카드가 `EMD_COMPARE_MAX_LIMIT`(15) 상한을 쓰기 때문이다.
 */
const MAX_ROWS = 15

/** 0건일 때 표·막대 자리에 그리는 문구(D5). 섹션 자체는 그대로 남는다. */
const EMPTY_TEXT = '해당 기간에 민원이 없습니다'

/**
 * 🔴 §4·§5 는 **민원이 아니라 단속·수요부족**을 센다 — `EMPTY_TEXT` 를 쓰면
 * "단속 순위" 표에 *"민원이 없습니다"* 가 찍혀 뜻이 어긋난다(2026-10-02 E2E 지적).
 * ⚠️ 두 섹션의 빈 줄은 원인이 둘이다 — ①그 기간에 실제로 0건 ②**구 스냅샷**(2026-10-02 이전
 * 보고서라 `crdnCompare`·`shortageCompare` 키가 없다). 문구로 둘을 가르지 않고 **중립적으로** 적는다.
 */
const EMPTY_TEXT_CRDN = '해당 기간에 단속 자료가 없습니다'
const EMPTY_TEXT_SHORTAGE = '해당 기간에 수요부족 자료가 없습니다'

/**
 * §2~§5 아래 `단계 구분 기준`(TSK-4312).
 *
 * 🔴 경계 숫자는 `riskGrade.ts` 의 `PCT_*` 에서만 온다 — 판정(`pickRiskGrade`)과 문구가 갈리지 않게 한다.
 * 🔴 문서 표에 단계 열이 없어 §2 막대 색으로만 단계가 드러나므로 색 이름을 함께 적는다.
 */
const GRADE_BASIS_LINES = [
  `단계 : 심각(빨강) 상위 ${PCT_10}% 이내 · 경고(주황) 상위 ${PCT_10}~${PCT_25}% · 주의(파랑) 상위 ${PCT_25}~${PCT_50}% — 주의 이상 지역만 표시됩니다.`,
  '상위 % = 순위 ÷ 조회 지역의 읍·면·동 수입니다. 민원은 민원 건수, 단속은 단속 건수, 수요부족은 부족률이 높은 순입니다.',
  '핵심 지표의 단속 집중·수요 부족 지역은 심각 단계에 해당하는 읍·면·동 수입니다.',
  `6·7절은 단계를 나누지 않고 부정 비율이 높은 상위 ${MAX_ROWS}곳을 싣습니다.`,
]

/** §1 아래 안내 — 전기를 달력 전월로 오해하기 쉬워 문서에 적는다(TSK-4311). */
const PREV_PERIOD_NOTE =
  '전기는 분석 기간 바로 앞의 같은 일수 구간입니다. 3~5절의 전기 대비도 이 구간과 비교합니다.'

/* ── 2. 표기 헬퍼 ─────────────────────────────────────── */

/** `247` → `247건`. */
function formatCount(n: number): string {
  return `${n.toLocaleString()}건`
}

/**
 * 전기 건수 표기 — 구 스냅샷처럼 값이 없으면 `—`.
 * 🔴 `== null` 로 판정한다. 참/거짓으로 가르면 실제 `0건` 이 `—` 로 사라진다.
 */
function formatPrevCount(n: number | null | undefined): string {
  return n == null ? '—' : formatCount(n)
}

/**
 * §5 전기 부족률 표기 — `0.65` → `65%`. 값이 없으면 `—`(0% 는 "부족 없음" 이라 대체하면 뜻이 반대다).
 */
function formatPrevRatio(ratio: number | null | undefined): string {
  return ratio == null || !Number.isFinite(ratio) ? '—' : `${Math.round(ratio * 100)}%`
}

/**
 * 0.0~1.0 비율 → 정수 퍼센트.
 *
 * 🔴 `negativityRatio`·`topTypeRatio`·`negativeRatio` 는 전부 **0.0~1.0** 이다.
 * `×100` 을 빠뜨리면 `부정 0.62%` 가 되는데 표가 그럴듯해 보여 조용히 넘어간다.
 */
function ratioToPct(ratio: number): number {
  return Math.round(ratio * 100)
}

/** `분모 <= 0` 이면 0 — `NaN%`/`Infinity%` 가 문서에 찍히는 것을 막는다. */
function sharePct(part: number, total: number): number {
  if (!(total > 0)) return 0
  const pct = (part / total) * 100
  return Number.isFinite(pct) ? pct : 0
}

/**
 * §3·§4·§5 공용 `전기 대비` 표기 — 양수 `▲13%` · 음수 `▼3%` · 그 밖(0 · `null` · 추론값)은 `—`.
 * 샘플 PDF §3 5행 `삼도동` 이 `—` 인 이유가 이것이다.
 *
 * 🔴 **행(row)이 아니라 값을 받는다**(TSK-4197 Step 7-3). §3 은 `EmdCompareItem`, §4 는
 * `CrdnCompareItem`, §5 는 `ShortageCompareItem` 이라 행 타입이 셋이지만 본문이 읽는 것은
 * `deltaPct`·`inferred` 둘뿐이다 — 타입마다 오버로드를 두지 않는다.
 *
 * 🔴 `unit` 은 §5 때문에 있다. `shortageDeltaPp` 는 **이미 %p 단위**라 `%` 로 적으면 단위가 틀린다
 * (🔴 그렇다고 ×100 하지 않는다 — 백엔드가 이미 곱해 보낸다).
 *
 * 🔴 **문서는 수치를 유지한다 — 화면(`DeltaBadge` 화살표 + 툴팁)과 일부러 다르다**(TSK-4322 D13).
 * PDF·DOCX 에는 툴팁이 없어 화살표만 남기면 수치를 볼 방법이 사라진다. 추론값은 `—` 로만 적는다.
 */
function formatDelta(deltaPct: number | null, inferred: boolean, unit: '%' | '%p' = '%'): string {
  if (inferred) return '—'
  const d = deltaPct
  if (d == null || !Number.isFinite(d) || d === 0) return '—'
  // 🔴 자리수를 `unit` 에 묶는다 — 화면 `DeltaBadge` 와 같은 규칙이다.
  //    `%p`(부족률 차이)는 백엔드가 `ROUND(…, 1)` 로 주므로 **소수 1자리로 고정**하고,
  //    `%`(건수 증감률)는 정수라 그대로 찍는다(여기에 toFixed 를 걸면 `▲13%` 가 `▲13.0%` 로 회귀한다).
  // ⚠️ 시안(`민원현황2.pdf` §5)의 `▲4%p` 는 수작업 샘플이라 정수지만, 실데이터는 67행 중 16행만
  //    정수다(2026-10-02 실측). 그 16행에서 화면(`4.0%p`)과 문서(`4%p`)가 갈리므로 화면에 맞춘다.
  const label = unit === '%p' ? Math.abs(d).toFixed(1) : String(Math.abs(d))
  return d > 0 ? `▲${label}${unit}` : `▼${label}${unit}`
}

/**
 * 🔴 등급 3종(심각·경고·주의)만. **행 수 상한을 걸지 않는다**(D6 — 사용자 결정 2026-09-30).
 *   - 신규 §4·§5 전용이다. 기존 §2·§3·§6·§7 은 `slice(0, MAX_ROWS)` 를 **그대로 둔다**.
 *   - 등급 판정은 화면 카드와 같은 `pickRiskGrade` 규칙이고 분모는 **배열 전체 길이**(필터 전)다.
 *   - 그래서 §3(15행)과 §4(21행·2026-10-02 실측)의 행 수가 다르다 — 의도된 비대칭이다.
 *     목적이 다르다: 신규 두 섹션은 화면 카드의 `더보기` 펼친 상태와 같은 행을 보이고,
 *     기존 섹션은 종전 문서와 같은 모양을 유지한다.
 * 🔴 자르는 곳은 이 템플릿 한 곳이다 — 서버 스냅샷은 전량을 담는다.
 */
function gradedRows<T extends { rank: number }>(rows: T[]): T[] {
  const total = rows.length
  return rows.filter((r) => pickRiskGrade(r.rank, total) !== null)
}

/**
 * §5 `세부 내역` 의 수요·공급 수치 — 값이 없거나 비정상이면 `—`(0 으로 쓰지 않는다).
 * 구 스냅샷에는 `shortageCompare` 키 자체가 없어 `undefined` 가 흘러들 수 있으므로 넓게 받는다.
 */
function formatIndex(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return n.toLocaleString()
}

/** §3 `주요 유형` — `인도·횡단보도 점유 42%`. 유형이 없으면 `—`. */
function formatTopType(row: EmdCompareItem): string {
  if (!row.topTypeNm) return '—'
  if (row.topTypeRatio == null || !Number.isFinite(row.topTypeRatio)) return row.topTypeNm
  return `${row.topTypeNm} ${ratioToPct(row.topTypeRatio)}%`
}

/**
 * 부정 비율(%) — 서버가 준 `negativeRatio` 를 우선하고, `null` 이면 분포에서 계산한다.
 * 둘 다 불가(분모 0)면 `null` → 화면과 같은 `—` 표기(`NegEmdCompareCard` 규칙).
 */
function negPct(row: NegEmdCompareItem): number | null {
  if (row.negativeRatio != null && Number.isFinite(row.negativeRatio)) {
    return ratioToPct(row.negativeRatio)
  }
  if (row.totalCount > 0) return Math.round(sharePct(row.negativeCount, row.totalCount))
  return null
}

/** §7 `감정 분포` — `긍정 6% · 보통 16% · 부정 78%`. */
function formatSentiment(dist: SentimentDist, total: number): string {
  const p = Math.round(sharePct(dist.positive, total))
  const n = Math.round(sharePct(dist.neutral, total))
  const g = Math.round(sharePct(dist.negative, total))
  return `긍정 ${p}% · 보통 ${n}% · 부정 ${g}%`
}

/**
 * 헤더 메타 한 줄 — `분석 지역 : 제주시 연동 · 분석 기간 : 2026.09.01 ~ 2026.09.17 ·
 * 비교 기간 : 2026.08.15 ~ 2026.08.31 · 생성일 : 2026-09-18`.
 * 비교 기간은 TSK-4311 부터 있다 — 구 스냅샷에는 값이 없어 그 칸을 뺀다.
 *
 * 🔴 지역·기간 문구는 **서버가 만든 것을 그대로 쓴다**(D8) — 프론트가 3단 지역 규칙·기간 표기를
 * 두 번째로 구현하지 않는다. `crtYmd` 는 `2026-09-18T14:03:11` 로 오므로 앞 10자만 쓴다
 * (`ReportsPage.formatDate` 와 같은 규칙).
 */
function metaCaption(doc: ComplaintReportDoc): string {
  const parts: string[] = []
  if (doc.regionNm) parts.push(`분석 지역 : ${doc.regionNm}`)
  if (doc.anlsPrdExpln) parts.push(`분석 기간 : ${doc.anlsPrdExpln}`)
  if (doc.kpi?.prevAnlsPrdExpln) parts.push(`비교 기간 : ${doc.kpi.prevAnlsPrdExpln}`)
  if (doc.crtYmd) parts.push(`생성일 : ${doc.crtYmd.slice(0, 10)}`)
  return parts.join(' · ')
}

/* ── 3. 조각 ──────────────────────────────────────────── */

/** 섹션 제목 — 파란 세로 막대 + `N. 제목`(샘플 PDF). */
function sectionTitle(text: string): string {
  return `<div data-pdf-keep-next style="display:flex;align-items:center;margin:26px 0 12px;">
        <span style="display:inline-block;width:4px;height:16px;background:${SECTION_ACCENT};margin-right:8px;"></span>
        <span style="font-size:14px;font-weight:700;color:#171717;">${escapeHtml(text)}</span>
      </div>`
}

/** 표 머리글 한 줄. */
function tableHead(columns: { label: string; align: 'left' | 'right' | 'center' }[]): string {
  const cells = columns
    .map((c) => `<th style="${TH_STYLE}text-align:${c.align};">${escapeHtml(c.label)}</th>`)
    .join('')
  return `<tr style="background:#f5f6f8;">${cells}</tr>`
}

/** 0건일 때의 표 본문 한 줄(D5) — 머리글만 남은 표가 되지 않게 한다. */
function tableEmptyRow(colSpan: number, text: string = EMPTY_TEXT): string {
  return `<tr><td colspan="${colSpan}" style="${TD_STYLE}text-align:center;color:${MUTED};">${escapeHtml(text)}</td></tr>`
}

/** 0건일 때의 막대 자리 한 줄(D5) — 섹션은 그대로 두고 안쪽만 빈 상태로 그린다. */
function barEmptyRow(): string {
  return `<div style="padding:14px 0;text-align:center;font-size:12px;color:${MUTED};">${escapeHtml(EMPTY_TEXT)}</div>`
}

/**
 * 섹션 2 막대 색 — 🔴 **화면 카드 dot·지도 polygon 과 같은 `pickRiskGrade` 규칙**이다.
 * 🔴 `total` 은 **자르기 전 전체 길이**여야 한다(D6 — 15행으로 자른 뒤의 길이로 재면 경계가
 * 움직여 화면과 어긋난다). 등급 밖이면 무채색이다.
 */
function gradeColor(rank: number, total: number): string {
  switch (pickRiskGrade(rank, total)) {
    case '심각':
      return GRADE_COLOR_SEVERE
    case '경고':
      return GRADE_COLOR_WARNING
    case '주의':
      return GRADE_COLOR_CAUTION
    default:
      return GRADE_COLOR_NEUTRAL
  }
}

/**
 * 섹션 2 막대 한 줄 — `[동명] [막대] [N건]`.
 *
 * 폭은 상위 1건 대비 비율이다. 🔴 `max` 가 0 이면 나눗셈이 `NaN%` 가 되므로 `sharePct` 로 막는다.
 * 표 형태로 짜는 이유는 Word 가 flex 를 제대로 접지 못해 3열이 겹치기 때문이다.
 */
function countBarRow(row: EmdCompareItem, max: number, total: number): string {
  const width = sharePct(row.cvlcptCnt, max)
  return `<tr>
        <td style="width:90px;padding:3px 8px 3px 0;text-align:right;font-size:11px;color:#171717;white-space:nowrap;">${escapeHtml(row.emdNm)}</td>
        <td style="padding:3px 0;">
          <div style="background:${BAR_TRACK};border-radius:999px;height:14px;width:100%;">
            <div style="background:${gradeColor(row.rank, total)};border-radius:999px;height:14px;width:${width.toFixed(2)}%;"></div>
          </div>
        </td>
        <td style="width:52px;padding:3px 0 3px 8px;font-size:11px;color:${MUTED};white-space:nowrap;">${escapeHtml(formatCount(row.cvlcptCnt))}</td>
      </tr>`
}

/**
 * §6 누적 막대 한 줄 — `[동명] [긍정|보통|부정] [부정 78%]`.
 *
 * 세 조각의 폭 합이 100% 이므로 한 줄짜리 3칸 표로 그린다(Word 가 inline-block 을 접는 것을 피한다).
 * 🔴 `totalCount` 가 0 이면 세 폭이 전부 0 이다 — `NaN%` 가 아니다(`sharePct`).
 */
function sentimentBarRow(row: NegEmdCompareItem): string {
  const pos = sharePct(row.sentimentDist.positive, row.totalCount)
  const neu = sharePct(row.sentimentDist.neutral, row.totalCount)
  const neg = sharePct(row.sentimentDist.negative, row.totalCount)
  const pct = negPct(row)
  const seg = (w: number, color: string) =>
    `<td style="width:${w.toFixed(2)}%;height:14px;background:${color};padding:0;"></td>`
  return `<tr>
        <td style="width:90px;padding:3px 8px 3px 0;text-align:right;font-size:11px;color:#171717;white-space:nowrap;">${escapeHtml(row.emdNm)}</td>
        <td style="padding:3px 0;">
          <table style="width:100%;border-collapse:collapse;table-layout:fixed;background:${BAR_TRACK};">
            <tr>${seg(pos, SENTIMENT_POSITIVE)}${seg(neu, SENTIMENT_NEUTRAL)}${seg(neg, SENTIMENT_NEGATIVE)}</tr>
          </table>
        </td>
        <td style="width:62px;padding:3px 0 3px 8px;font-size:11px;color:${MUTED};white-space:nowrap;">${escapeHtml(pct == null ? '부정 —' : `부정 ${pct}%`)}</td>
      </tr>`
}

/* ── 4. 섹션 ──────────────────────────────────────────── */

/**
 * 섹션 1 — 핵심 지표 2열 표.
 *
 * 🔴 0건이어도 이 섹션은 **정상 렌더**다(`0건` · `부정 0%`) — 그 자체가 보고할 값이다(D5).
 */
function section1(doc: ComplaintReportDoc): string {
  const k = doc.kpi
  const rows: [string, string][] = [
    ['총 민원 건수', formatCount(k.totalCount)],
    ['전기 민원 건수', formatPrevCount(k.prevTotalCount)],
    ['민원 감정 분석', `부정 ${ratioToPct(k.negativityRatio)}%`],
    ['단속 집중 지역', `${k.enforcementCount.toLocaleString()}개동 (심각 단계 기준)`],
    ['수요 부족 지역', `${k.shortageCount.toLocaleString()}개동 (심각 단계 기준)`],
  ]
  const body = rows
    .map(
      ([label, value]) => `<tr>
            <td style="${TD_STYLE}width:45%;">${escapeHtml(label)}</td>
            <td style="${TD_STYLE}font-weight:700;">${escapeHtml(value)}</td>
          </tr>`,
    )
    .join('')
  return `${sectionTitle('1. 핵심 지표')}
      <table style="width:100%;border-collapse:collapse;font-size:12px;">
        <thead>${tableHead([
          { label: '지표', align: 'left' },
          { label: '값', align: 'left' },
        ])}</thead>
        <tbody>${body}</tbody>
      </table>
      <div style="margin-top:6px;font-size:11px;color:${MUTED};line-height:1.5;">${escapeHtml(PREV_PERIOD_NOTE)}</div>`
}

/** 섹션 2 — 지역별 민원 건수 수평 막대(상위 15). */
function section2(rows: EmdCompareItem[], total: number): string {
  const max = rows.reduce((m, r) => Math.max(m, r.cvlcptCnt), 0)
  const body = rows.length === 0 ? '' : rows.map((r) => countBarRow(r, max, total)).join('')
  const inner =
    rows.length === 0
      ? barEmptyRow()
      : `<table style="width:100%;border-collapse:collapse;">${body}</table>`
  return `${sectionTitle('2. 지역별 민원 건수')}${inner}`
}

/** 섹션 3 — 읍·면·동 민원 순위 표(상위 15 — `MAX_ROWS`). */
function section3(rows: EmdCompareItem[]): string {
  const body =
    rows.length === 0
      ? tableEmptyRow(6)
      : rows
          .map(
            (r) => `<tr>
            <td style="${TD_STYLE}">${escapeHtml(String(r.rank))}</td>
            <td style="${TD_STYLE}font-weight:700;">${escapeHtml(r.emdNm)}</td>
            <td style="${TD_STYLE}">${escapeHtml(formatCount(r.cvlcptCnt))}</td>
            <td style="${TD_STYLE}">${escapeHtml(formatPrevCount(r.prevCnt))}</td>
            <td style="${TD_STYLE}">${escapeHtml(formatDelta(r.deltaPct, r.deltaPctInferred))}</td>
            <td style="${TD_STYLE}color:${MUTED};">${escapeHtml(formatTopType(r))}</td>
          </tr>`,
          )
          .join('')
  return `${sectionTitle('3. 읍·면·동 민원 순위')}
      <table style="width:100%;border-collapse:collapse;font-size:12px;">
        <thead>${tableHead([
          { label: '순위', align: 'left' },
          { label: '지역', align: 'left' },
          { label: '민원 건수', align: 'left' },
          { label: '전기 민원', align: 'left' },
          { label: '전기 대비', align: 'left' },
          { label: '주요 유형', align: 'left' },
        ])}</thead>
        <tbody>${body}</tbody>
      </table>`
}

/** §4 `세부 내역` — 최다 단속유형 + 비율. 구 스냅샷(유형 없음)은 종전 문구 (TSK-4322). */
function crdnDetail(r: CrdnCompareItem): string {
  if (r.topCrdnMthd == null) return `민원 ${formatCount(r.cvlcptCnt)} 대비`
  return r.topCrdnMthdRatio != null
    ? `${r.topCrdnMthd} ${Math.round(r.topCrdnMthdRatio * 100)}%`
    : r.topCrdnMthd
}

/**
 * 섹션 4 — 읍·면·동 단속 순위 표 (TSK-4197 · 시안 `민원현황2.pdf` §4).
 *
 * 🔴 행 수 상한이 없다 — 호출부가 `gradedRows` 로 등급 3종만 걸러 넘긴다(D6).
 *    화면 `EmdCrdnRankCard` 의 `더보기` 를 펼친 상태와 같은 행 집합이다.
 * 🔴 `세부 내역` 은 최다 단속유형과 비율이다 — 화면 카드와 **같은 값**(`고정형CCTV 50%`, TSK-4322).
 *    그 칸이 없는 구 스냅샷은 저장된 민원 수로 종전 문구(`민원 N건 대비`)를 그린다.
 */
function section4(rows: CrdnCompareItem[]): string {
  const body =
    rows.length === 0
      ? tableEmptyRow(6, EMPTY_TEXT_CRDN)
      : rows
          .map(
            (r) => `<tr>
            <td style="${TD_STYLE}">${escapeHtml(String(r.rank))}</td>
            <td style="${TD_STYLE}font-weight:700;">${escapeHtml(r.emdNm)}</td>
            <td style="${TD_STYLE}">${escapeHtml(formatCount(r.crdnCnt))}</td>
            <td style="${TD_STYLE}">${escapeHtml(formatPrevCount(r.prevCrdnCnt))}</td>
            <td style="${TD_STYLE}">${escapeHtml(formatDelta(r.deltaPct, r.deltaPctInferred))}</td>
            <td style="${TD_STYLE}color:${MUTED};">${escapeHtml(crdnDetail(r))}</td>
          </tr>`,
          )
          .join('')
  return `${sectionTitle('4. 읍·면·동 단속 순위')}
      <table style="width:100%;border-collapse:collapse;font-size:12px;">
        <thead>${tableHead([
          { label: '순위', align: 'left' },
          { label: '지역', align: 'left' },
          { label: '단속 건수', align: 'left' },
          { label: '전기 단속', align: 'left' },
          { label: '전기 대비', align: 'left' },
          { label: '세부 내역', align: 'left' },
        ])}</thead>
        <tbody>${body}</tbody>
      </table>`
}

/**
 * 섹션 5 — 읍·면·동 수요부족 순위 표 (TSK-4197 · 시안 `민원현황2.pdf` §5).
 *
 * 🔴 행 수 상한이 없다 — §4 와 같이 `gradedRows` 가 호출부에서 등급 3종만 거른다(D6).
 *
 * 🔴 **`부족률` 은 `ratioToPct` 로 ×100 한다** — `shortageRatio` 는 **0~1 소수**라 그대로 찍으면
 *    전 행이 `부족률 0.72%` 가 된다(표가 그럴듯해 보여 조용히 넘어간다). 화면
 *    `EmdShortageRankCard` 도 `Math.round(shortageRatio * 100)` 로 같은 값을 만든다.
 *    `null` 이면 `—` 다 — `0%` 로 쓰면 "부족 없음" 으로 읽혀 뜻이 반대가 된다.
 * 🔴 **반대로 `전기 대비` 는 ×100 하지 않는다** — `shortageDeltaPp` 는 SQL 이 이미 ×100 한 `%p` 다.
 *
 * 🔴 `세부 내역` 표기는 화면 카드(`EmdShortageRankCard`)와 **동일한** `수요 N대 · 공급 N면` 이다(D10).
 *    `demandIndex` 는 면+건을 더한 지수라 `대` 가 엄밀한 단위는 아니지만 **사용자가 알고 택한 표기**이며,
 *    화면과 문서가 갈리지 않게 같은 문자열을 쓴다.
 */
function section5(rows: ShortageCompareItem[]): string {
  const body =
    rows.length === 0
      ? tableEmptyRow(6, EMPTY_TEXT_SHORTAGE)
      : rows
          .map((r) => {
            const ratio =
              r.shortageRatio == null || !Number.isFinite(r.shortageRatio)
                ? '—'
                : `부족률 ${ratioToPct(r.shortageRatio)}%`
            const detail = `수요 ${formatIndex(r.demandIndex)}대 · 공급 ${formatIndex(r.pkltCpctySum)}면`
            return `<tr>
            <td style="${TD_STYLE}">${escapeHtml(String(r.rank))}</td>
            <td style="${TD_STYLE}font-weight:700;">${escapeHtml(r.emdNm)}</td>
            <td style="${TD_STYLE}">${escapeHtml(ratio)}</td>
            <td style="${TD_STYLE}">${escapeHtml(formatPrevRatio(r.prevShortageRatio))}</td>
            <td style="${TD_STYLE}">${escapeHtml(formatDelta(r.shortageDeltaPp, r.deltaPctInferred, '%p'))}</td>
            <td style="${TD_STYLE}color:${MUTED};">${escapeHtml(detail)}</td>
          </tr>`
          })
          .join('')
  return `${sectionTitle('5. 읍·면·동 수요부족 순위')}
      <table style="width:100%;border-collapse:collapse;font-size:12px;">
        <thead>${tableHead([
          { label: '순위', align: 'left' },
          { label: '지역', align: 'left' },
          { label: '부족률', align: 'left' },
          { label: '전기 부족률', align: 'left' },
          { label: '전기 대비', align: 'left' },
          { label: '세부 내역', align: 'left' },
        ])}</thead>
        <tbody>${body}</tbody>
      </table>`
}

/** 섹션 6 — 부정 민원 상위 지역 감정 분포 누적 막대(상위 15 — `MAX_ROWS`). */
function section6(rows: NegEmdCompareItem[]): string {
  const inner =
    rows.length === 0
      ? barEmptyRow()
      : `<table style="width:100%;border-collapse:collapse;">${rows.map(sentimentBarRow).join('')}</table>`
  return `${sectionTitle('6. 부정 민원 상위 지역 감정 분포 (긍정·보통·부정)')}${inner}`
}

/** 섹션 7 — 부정 민원 상위 지역 표(상위 15 — `MAX_ROWS`). */
function section7(rows: NegEmdCompareItem[]): string {
  const body =
    rows.length === 0
      ? tableEmptyRow(5)
      : rows
          .map((r) => {
            const pct = negPct(r)
            return `<tr>
            <td style="${TD_STYLE}">${escapeHtml(String(r.rank))}</td>
            <td style="${TD_STYLE}font-weight:700;">${escapeHtml(r.emdNm)}</td>
            <td style="${TD_STYLE}">${escapeHtml(pct == null ? '—' : `${pct}%`)}</td>
            <td style="${TD_STYLE}">${escapeHtml(formatCount(r.negativeCount))}</td>
            <td style="${TD_STYLE}color:${MUTED};">${escapeHtml(formatSentiment(r.sentimentDist, r.totalCount))}</td>
          </tr>`
          })
          .join('')
  return `${sectionTitle('7. 부정 민원 상위 지역')}
      <table style="width:100%;border-collapse:collapse;font-size:12px;">
        <thead>${tableHead([
          { label: '순위', align: 'left' },
          { label: '지역', align: 'left' },
          { label: '부정 비율', align: 'left' },
          { label: '부정 건수', align: 'left' },
          { label: '감정 분포', align: 'left' },
        ])}</thead>
        <tbody>${body}</tbody>
      </table>`
}

/* ── 5. 본문 ──────────────────────────────────────────── */

/**
 * 미리보기 · PDF · DOCX 공용 본문(샘플 PDF `민원현황2.pdf` 7섹션).
 *
 * 폭은 기존 보고서 템플릿과 같은 794px(A4 한 페이지 가로 정합)이며, 넘치는 분량은
 * `exportPdf` 가 자동으로 페이지를 나눈다(기존 동작 — 샘플도 3쪽이다).
 *
 * 🔴 **행을 자르는 곳이 여기 한 곳이다.** 서버 스냅샷은 전체를 담고 있다.
 *    규칙이 섹션마다 다르다(D6) — 기존 §2·§3·§6·§7 은 `slice(0, MAX_ROWS)`(15행 상한),
 *    신규 §4·§5 는 `gradedRows`(등급 3종 · **상한 없음**).
 * 🔴 배열이 비어도 7섹션이 모두 그려진다(D5).
 *    ⚠️ 2026-09-30 이전 스냅샷에는 `crdnCompare`·`shortageCompare` 키가 없다 — `?? []` 로 받아
 *    §4·§5 가 `tableEmptyRow` 한 줄로 그려진다(섹션이 사라지지 않는다).
 */
export function buildComplaintReportHtml(doc: ComplaintReportDoc): string {
  // 🔁 2026-10-02 (사용자 요청) — §2·§3 도 **화면 카드와 같은 등급 3종**으로 바꿨다.
  //    종전 `slice(0, MAX_ROWS)` 는 화면이 `더보기` 로 펼친 행 수와 어긋났다(10월 기준 15 ↔ 34).
  // 🔴 §6·§7 은 `MAX_ROWS` 를 **그대로 둔다** — 그쪽 화면 카드(`NegEmdCompareCard`)가
  //    `EMD_COMPARE_MAX_LIMIT`(15) 상한을 쓰므로 15행이 곧 화면과 같은 행이다.
  const emd = gradedRows(doc.emdCompare ?? [])
  const neg = (doc.negEmdCompare ?? []).slice(0, MAX_ROWS)
  const crdn = gradedRows(doc.crdnCompare ?? [])
  const shortage = gradedRows(doc.shortageCompare ?? [])
  const meta = metaCaption(doc)

  return `<div style="font-family:${DOC_FONT};color:#171717;width:${A4_WIDTH_PX}px;padding:40px;background:#fff;box-sizing:border-box;">
      <div style="font-size:22px;font-weight:800;margin-bottom:6px;">${escapeHtml(DOC_TITLE)}</div>
      <div style="margin-bottom:8px;">
        <div style="font-size:12px;color:${MUTED};line-height:1.6;">${escapeHtml(DOC_SUBTITLE)}</div>
        ${meta ? `<div style="font-size:12px;color:${MUTED};line-height:1.6;">${escapeHtml(meta)}</div>` : ''}
      </div>
      ${section1(doc)}
      ${section2(emd, doc.emdCompare?.length ?? 0)}
      ${section3(emd)}
      ${section4(crdn)}
      ${section5(shortage)}
      ${basisBlockHtml(GRADE_BASIS_TITLE, GRADE_BASIS_LINES)}
      ${section6(neg)}
      ${section7(neg)}
    </div>`
}
