import { escapeHtml } from './reportTemplate'

/*
 * AI 보고서 본문 템플릿.
 *
 * AI 답변 속 HTML 보고서 영역(서버가 jsoup 으로 새니타이즈한 값)을 우리 문서 틀 `.rpt-doc`(A4 폭)으로
 * 감싼다. 미리보기 모달 · PDF · DOCX 가 이 결과 한 벌을 쓴다(`reportTemplate` 과 같은 원칙).
 *
 * 🔴 **이 파일을 직접 import 하는 곳은 `reportDownload.ts` 하나다**(`buildAiReportHtml`) — 미리보기 훅(`openHtml`)과
 *    저장본 재열람(`buildPreview` AI 분기)이 그 진입점을 함께 쓴다. 화면이 여기를 직접 부르면 래핑 규칙이 갈린다.
 * 🔴 **AI 본문 래퍼에 `position: relative; overflow: hidden; contain: layout paint` 를 건다** — 새니타이저가
 *    `style` 을 허용해 `position:fixed; z-index:99999` 로 화면 전체를 덮는 입력이 통과한다.
 *    `contain: paint` 가 고정 위치 박스의 기준을 이 래퍼로 바꿔 가두고, `overflow: hidden` 이 넘친 부분을 자른다.
 *    빼면 미리보기 모달 위로 AI 가 만든 박스가 떠 닫기 버튼까지 가릴 수 있다.
 * ⚠️ 본문은 이스케이프하지 않는다 — HTML 그대로 그려야 하는 값이다. 안전성은 서버 새니타이즈가 진다.
 *    제목(`title`)만 바깥 값이라 `escapeHtml` 을 거친다.
 *
 * 기본 디자인(`applyAiReportStyles`) — Agent HTML 은 class 만 주고 style 태그가 없다(있어도 새니타이저가 버린다).
 * 🔴 **스타일은 요소마다 인라인으로 박는다 — style 태그 블록을 쓰지 않는다.** DOCX(html-docx-js → Word)가 style 태그의
 *    class 규칙을 반영하지 않아, 블록으로 두면 미리보기·PDF 에만 디자인이 있고 DOCX 는 맨 글자가 된다.
 *    같은 이유로 색·글꼴은 `var()` 가 아니라 리터럴이다(`lifezoneReportTemplate` 의 DOC_FONT·ACCENT_COLOR 와 같은 값).
 * 🔴 **병합 순서 = 태그 규칙 → class 규칙 → AI 원래 인라인 style.** 뒤가 이긴다 — `.bar-fill` 의 `width: 75%` 같은
 *    AI 값이 기본값을 덮어야 막대 길이가 산다.
 * 대상 class 목록 출처: 2026-11-10 `agent.5471a6e2` 실측 출력(report·report-header·kicker·subtitle·meta·key-points·
 *    report-section·table-wrap·num·inline-bars·bar-item·bar-track·bar-fill·report-footer · stat-grid·stat·label·value·detail·sources). 다른 class 는 무시된다(글자 크기는 래퍼 14px 을 물려받는다).
 * ⚠️ html2canvas(PDF) 보수 호환 — flex 는 쓰되 `gap` 대신 margin. Word 는 flex 를 몰라 막대가 깨지지만 라벨·값 텍스트는 남는다.
 */

/** A4 문서 폭(px) — `reportTemplate` 의 `A4_WIDTH_PX` 와 같은 값(미리보기 모달 `DOC_WIDTH_PX` 축소 기준). */
const A4_WIDTH_PX = 794

/** 본문 가둠 래퍼 스타일 — 위 🔴 참고. */
const CONTAIN_STYLE = 'position:relative;overflow:hidden;contain:layout paint;'

/**
 * AI 본문 기본 글자 — 규칙이 없는 요소(Agent 가 새로 쓴 class 의 `span`·`div` 등)가 브라우저 기본 16px 로
 * 튀지 않게 래퍼에서 14px 을 물려준다(2026-11-10 `stat-grid` 가 규칙 없이 16px 로 보였던 사례).
 */
const BASE_TEXT_STYLE = 'font-size:14px;line-height:1.7;color:#171717;'

/* ── 기본 디자인 ─────────────────────────────── */

/** 본문 글꼴 — `lifezoneReportTemplate` DOC_FONT 와 같은 값(리터럴 — DOCX 는 `var()` 를 못 읽는다). */
const DOC_FONT =
  "'Pretendard', 'Wanted Sans', system-ui, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"

/** 팔레트 — 기존 보고서 템플릿과 같은 리터럴. */
const C = {
  accent: '#0066ff',
  text: '#171717',
  sub: '#70737c',
  line: '#e5e6ea',
  rowLine: '#f0f0f0',
  bg: '#f5f6f8',
} as const

/**
 * 셀렉터 → 기본 스타일. **배열 순서가 곧 적용 순서**다(뒤가 이긴다) — 태그 규칙을 앞에, class 규칙을 뒤에 둔다
 * (예: `th` 의 `text-align:left` 를 `.num` 의 `right` 가 덮는다). 판정은 `Element.matches`.
 */
const STYLE_RULES: ReadonlyArray<readonly [string, string]> = [
  // ── 태그
  ['main, header, section, footer, article', 'display:block;'],
  [
    'h1',
    `font-family:${DOC_FONT};font-size:24px;font-weight:800;line-height:1.35;color:${C.text};margin:0 0 8px;`,
  ],
  [
    'h2',
    `font-family:${DOC_FONT};border-left:4px solid ${C.accent};padding-left:10px;font-size:17px;font-weight:800;` +
      `line-height:1.4;color:${C.text};margin:0 0 12px;`,
  ],
  [
    'h3',
    `font-family:${DOC_FONT};font-size:15px;font-weight:700;line-height:1.4;color:${C.text};margin:16px 0 8px;`,
  ],
  ['p', `font-size:14px;line-height:1.7;color:${C.text};margin:0 0 10px;`],
  ['ul, ol', `font-size:14px;line-height:1.7;color:${C.text};margin:0 0 12px;padding-left:20px;`],
  ['li', 'margin:0 0 4px;'],
  ['strong', `font-weight:700;color:${C.text};`],
  ['a', `color:${C.accent};text-decoration:underline;`],
  ['table', `width:100%;border-collapse:collapse;font-size:14px;color:${C.text};margin:0 0 12px;`],
  [
    'th',
    `padding:8px 10px;background:${C.bg};border-top:1px solid ${C.line};border-bottom:1px solid ${C.line};` +
      'font-weight:700;text-align:left;white-space:nowrap;',
  ],
  ['td', `padding:8px 10px;border-bottom:1px solid ${C.rowLine};vertical-align:top;`],
  ['dl', 'margin:0 0 12px;'],
  ['dt', `font-size:12px;font-weight:600;color:${C.sub};`],
  ['dd', `font-size:13px;color:${C.text};margin:0 0 6px;`],
  [
    'blockquote',
    `margin:0 0 12px;padding:10px 14px;border-left:3px solid ${C.line};background:${C.bg};color:${C.sub};font-size:14px;`,
  ],
  ['hr', `border:0;border-top:1px solid ${C.line};margin:20px 0;height:0;`],
  // ── class (Agent 실측)
  ['.report', `font-family:${DOC_FONT};color:${C.text};`],
  ['.report-header', `padding:0 0 18px;margin:0 0 24px;border-bottom:2px solid ${C.text};`],
  [
    '.kicker',
    `font-size:11px;font-weight:700;letter-spacing:0.08em;color:${C.accent};margin:0 0 6px;`,
  ],
  ['.subtitle', `font-size:14px;color:${C.sub};margin:0 0 14px;`],
  [
    '.meta',
    `display:flex;flex-wrap:wrap;margin:0;padding:10px 14px;background:${C.bg};border-radius:6px;`,
  ],
  ['.meta > div', 'display:flex;align-items:baseline;margin:2px 24px 2px 0;'],
  ['.meta dt', 'margin:0 8px 0 0;'],
  ['.meta dd', 'margin:0;font-weight:600;'],
  [
    '.key-points',
    `margin:0 0 12px;padding:14px 16px;background:${C.bg};border-left:4px solid ${C.accent};border-radius:0 6px 6px 0;`,
  ],
  ['.key-points strong', `color:${C.accent};`],
  ['.report-section', 'margin:0 0 28px;'],
  ['.table-wrap', 'margin:0 0 12px;'],
  ['.table-wrap table', 'margin:0;'],
  ['.num', 'text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums;'],
  ['.inline-bars', 'margin:0 0 12px;'],
  ['.bar-item', `display:flex;align-items:center;margin:0 0 8px;font-size:14px;color:${C.text};`],
  ['.bar-item > span:first-child', 'flex:0 0 140px;width:140px;'],
  [
    '.bar-track',
    `flex:1 1 auto;height:10px;margin:0 12px;background:${C.line};border-radius:5px;overflow:hidden;`,
  ],
  ['.bar-fill', `display:block;height:10px;background:${C.accent};border-radius:5px;`],
  ['.bar-item > .num', 'flex:0 0 52px;width:52px;font-weight:700;'],
  [
    '.report-footer',
    `margin:32px 0 0;padding:12px 0 0;border-top:1px solid ${C.line};font-size:12px;line-height:1.6;color:${C.sub};`,
  ],
  // ── 통계 카드(2026-11-10 실측 — `stat-grid > stat > label·value·detail`). `.label`·`.value` 는 이름이 흔해 `.stat` 아래로 좁힌다.
  ['.stat-grid', 'display:flex;flex-wrap:wrap;margin:0 -6px 16px;'],
  [
    '.stat',
    `flex:1 1 200px;margin:0 6px 12px;padding:14px 16px;background:${C.bg};border:1px solid ${C.line};border-radius:8px;`,
  ],
  [
    '.stat .label',
    `display:block;font-size:12px;font-weight:600;line-height:1.4;color:${C.sub};margin:0 0 6px;`,
  ],
  [
    '.stat .value',
    `display:block;font-family:${DOC_FONT};font-size:24px;font-weight:800;line-height:1.2;color:${C.accent};margin:0 0 4px;`,
  ],
  ['.stat .detail', `display:block;font-size:12px;line-height:1.4;color:${C.sub};`],
  // ── 출처 목록(2026-11-10 실측 — `ul.sources`)
  ['.sources', `font-size:13px;color:${C.sub};`],
]

/** 끝에 `;` 를 보장한다 — 이어 붙일 때 앞 선언과 뒤 선언이 붙어 깨지지 않게. */
function terminated(style: string): string {
  const t = style.trim()
  return t === '' || t.endsWith(';') ? t : `${t};`
}

/**
 * AI 보고서 본문에 기본 디자인을 인라인 style 로 입힌다(상단 주석 참고).
 *
 * 각 요소의 style = 매칭된 규칙(배열 순) + **AI 원래 인라인 style(맨 뒤 — 우선)**.
 * `DOMParser`(`text/html`)는 스크립트를 실행하지 않는다. 입력은 서버가 새니타이즈한 값이다.
 * 경고색(`#ff4242`)은 실측 출력에 대응 class 가 없어 아직 쓰지 않는다.
 *
 * @param html 서버가 새니타이즈한 본문 HTML 조각
 * @returns 스타일을 입힌 HTML 조각(브라우저 밖이면 원문 그대로)
 */
export function applyAiReportStyles(html: string): string {
  if (typeof DOMParser === 'undefined' || !html) return html
  const doc = new DOMParser().parseFromString(html, 'text/html')
  for (const el of Array.from(doc.body.querySelectorAll('*'))) {
    let base = ''
    for (const [selector, style] of STYLE_RULES) {
      if (el.matches(selector)) base += style
    }
    if (base === '') continue
    const own = terminated(el.getAttribute('style') ?? '')
    el.setAttribute('style', own === '' ? base : `${base}${own}`)
  }
  /*
   * 🔴 스타일을 다 입힌 **뒤에** AI 의 class 를 지운다 — 앱 전역 CSS 와 이름이 부딪힌다.
   *    실측(2026-11-10): Agent 의 `<main class="report">` 가 `최근 리포트` 카드의 `.report`
   *    (ai.css — `max-height:356px; overflow-y:auto; display:flex`)에 걸려, PDF 캡처용으로 문서에 붙인
   *    보고서가 356px 에서 잘렸다(표 아래가 PDF 에서 사라짐). class 는 위 STYLE_RULES 매칭에만 쓰이므로
   *    인라인으로 옮긴 뒤에는 필요 없다. 매칭(`.meta dt` 같은 하위 선택자)이 끝난 다음 별도 루프로 지운다.
   */
  for (const el of Array.from(doc.body.querySelectorAll('[class]'))) el.removeAttribute('class')
  return doc.body.innerHTML
}

/**
 * AI 보고서 본문을 `.rpt-doc` A4 틀로 감싼다.
 *
 * @param title 보고서명(머리말). 화면 표시용 — 본문에 `<h1>` 이 있어도 그대로 둔다.
 * @param html  서버가 새니타이즈한 본문 HTML.
 */
export function buildAiReportDocHtml(title: string, html: string): string {
  return `<div class="rpt-doc" style="width:${A4_WIDTH_PX}px;padding:40px;background:#fff;font-family:'Pretendard',sans-serif;color:#111;box-sizing:border-box;">
      <p style="color:#888;font-size:12px;margin:0 0 16px;">제주 주차민원분석 솔루션 · AI 보고서 · ${escapeHtml(title)}</p>
      <div class="rpt-doc__ai" style="${CONTAIN_STYLE}${BASE_TEXT_STYLE}">${applyAiReportStyles(html)}</div>
    </div>`
}
