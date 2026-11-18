// ⚠️ `?raw` 는 top-level 에 정적 import 하지 않는다 — 정적이면 모듈이 메인 번들에 포함돼
// 400KB 가 앱 초기 로딩부터 따라온다. PDF/DOCX 누를 때만 가져오도록 `loadHtmlDocx` 안에서
// 동적 import 한다 (아래 주석 참고).

/*
 * 보고서 내보내기 (TSK-4026 Step 10).
 *
 * 입력은 항상 `reportTemplate.buildReportHtml()` 의 결과다 — 미리보기 모달이 그리는 것과
 * **완전히 같은 HTML** 한 벌에서 PDF·DOCX 를 만든다(서식이 갈리지 않게 한다).
 *
 * ⚠️ PDF 는 **이미지 기반**이다(html2canvas 로 캡처 → jsPDF 에 이미지로 첨부).
 * 그래서 내려받은 PDF 에서는 텍스트 선택·검색이 되지 않는다. 샘플 단계에서는 허용하고,
 * 실 보고서 본문을 붙일 때 `jsPDF.html()` 또는 서버 생성으로 바꾼다(범위 밖).
 */

import { layoutPdfPages } from './pdfPageLayout'

/** 윈도우에서 파일명에 쓸 수 없는 문자. 하나라도 남으면 저장이 실패한다. */
const INVALID_FILENAME_CHARS = /[/\\:*?"<>|]/g

/** 보고서명이 공백뿐이거나 전부 치환됐을 때 쓰는 대체 파일명. */
const FALLBACK_FILE_BASE = 'report'

/**
 * 다운로드 시작 전에 object URL 을 회수하면 일부 브라우저(Safari)가 파일을 받지 못한다.
 * `a.click()` 이후 한 틱 이상 여유를 주고 회수한다.
 */
const REVOKE_DELAY_MS = 1000

/** PDF 캡처 캔버스의 세로 상한(px). 브라우저 상한(32,767)보다 조금 낮게 잡는다. */
const MAX_CANVAS_HEIGHT_PX = 32000

/** 보고서명을 파일명으로 쓸 수 있게 다듬는다. 확장자는 호출부에서 붙인다. */
function sanitizeFileBase(fileBase: string): string {
  const cleaned = fileBase.replace(INVALID_FILENAME_CHARS, '_').trim()
  return cleaned || FALLBACK_FILE_BASE
}

/**
 * Blob 을 파일로 내려받는다. `a[download]` 를 잠깐 붙였다 지우는 표준 수법이며,
 * 실패해도 앵커와 object URL 이 남지 않도록 `finally` 에서 정리한다.
 */
function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  document.body.appendChild(anchor)

  try {
    anchor.click()
  } finally {
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), REVOKE_DELAY_MS)
  }
}

/**
 * 이미 만든 파일(Blob)을 `{보고서명}.{ext}` 로 내려받는다 — 파일명 정리·다운로드 규칙은 PDF·DOCX 와 같다.
 * 집중구역 엑셀(TSK-4338 — `lib/analysis/hotspotXlsx`)처럼 HTML 을 거치지 않는 형식이 쓴다.
 *
 * @param fileBase 확장자를 뺀 파일명(보고서명)
 * @param ext      확장자(점 없이, 예 `xlsx`)
 */
export function exportBlob(blob: Blob, fileBase: string, ext: string): void {
  downloadBlob(blob, `${sanitizeFileBase(fileBase)}.${ext}`)
}

/**
 * HTML 을 PDF 로 내려받는다.
 *
 * 1. 화면 밖(`position:fixed; left:-9999px`)에 임시 노드로 렌더 — 화면을 깜빡이게 하지 않으면서
 *    실제 레이아웃(폰트·줄바꿈)을 태운다. `display:none` 은 크기가 0 이라 캡처가 되지 않는다.
 * 2. 표·상자가 쪽 경계에서 잘리지 않게 배치한다(`layoutPdfPages`).
 * 3. `html2canvas` 로 2배 해상도 캡처.
 * 4. A4 폭에 맞춰 붙이고, 세로가 A4 를 넘으면 이미지를 위로 밀며 `addPage()` 를 반복한다.
 *
 * @param html     `buildReportHtml()` 결과
 * @param fileBase 확장자를 뺀 파일명(보고서명)
 */
export async function exportPdf(html: string, fileBase: string): Promise<void> {
  // jspdf·html2canvas 는 합쳐서 600KB 가 넘는다. 정적 import 하면 보고서를 열지도 않은 사용자까지
  // 첫 로딩에 받아가므로 여기서 동적 import 해 별도 청크로 뺀다.
  const [{ default: html2canvas }, { jsPDF }] = await Promise.all([
    import('html2canvas'),
    import('jspdf'),
  ])

  const host = document.createElement('div')
  host.style.position = 'fixed'
  host.style.left = '-9999px'
  host.style.top = '0'
  // 뷰포트가 좁아도 본문(794px)이 눌리지 않도록 내용 폭을 그대로 쓴다.
  host.style.width = 'max-content'
  host.style.background = '#fff'
  host.innerHTML = html
  document.body.appendChild(host)

  try {
    // 템플릿 루트(`.rpt-doc`, 폭 고정)를 캡처한다. 없으면 래퍼째 캡처한다.
    const target = (host.firstElementChild as HTMLElement | null) ?? host

    const doc = new jsPDF({ unit: 'mm', format: 'a4' })
    const pageWidth = doc.internal.pageSize.getWidth()
    const pageHeight = doc.internal.pageSize.getHeight()
    // 스케일 계산이 문서 높이를 읽으므로 캡처 전에 밀어 둔다.
    layoutPdfPages(target, (target.getBoundingClientRect().width * pageHeight) / pageWidth)

    /*
     * 문서 전체를 **한 장의 캔버스**로 캡처한다. 브라우저마다 캔버스 한 변의 상한이 있어
     * (Firefox·Safari 32,767px) 그보다 길면 캡처가 빈 이미지가 된다. 2배 해상도로 상한을 넘는
     * 긴 문서(수백 행 표 — 투자우선순위 TSK-4036)만 배율을 낮춘다.
     * 🔴 높이 16,000px 이하 문서는 종전과 똑같이 2배다 — 기존 보고서의 출력은 바뀌지 않는다.
     */
    const scale = Math.min(2, MAX_CANVAS_HEIGHT_PX / Math.max(1, target.scrollHeight))
    const canvas = await html2canvas(target, { scale, backgroundColor: '#fff' })
    const imgData = canvas.toDataURL('image/png')

    // 가로를 A4 폭에 맞췄을 때의 전체 이미지 높이(mm).
    const imageHeight = (canvas.height * pageWidth) / canvas.width

    doc.addImage(imgData, 'PNG', 0, 0, pageWidth, imageHeight)

    // 남은 높이만큼 페이지를 추가하며 같은 이미지를 위로 밀어 넣는다(A4 높이 단위로 잘라 보이게).
    // 부동소수 오차로 빈 페이지가 하나 더 생기지 않도록 1mm 미만은 남지 않은 것으로 본다.
    let remaining = imageHeight - pageHeight
    let offsetY = 0
    while (remaining > 1) {
      offsetY -= pageHeight
      doc.addPage()
      doc.addImage(imgData, 'PNG', 0, offsetY, pageWidth, imageHeight)
      remaining -= pageHeight
    }

    doc.save(`${sanitizeFileBase(fileBase)}.pdf`)
  } finally {
    // 캡처가 실패해도 임시 노드를 DOM 에 남기지 않는다.
    host.remove()
  }
}

/** 브라우저 번들이 `window` 에 올리는 `html-docx-js` API. 필요한 함수만 좁게 선언한다. */
interface HtmlDocx {
  asBlob(html: string): Blob
}

/** 스크립트 로딩은 한 번만 한다. 실패하면 다시 시도할 수 있게 null 로 되돌린다. */
let htmlDocxLoading: Promise<HtmlDocx> | null = null

/**
 * `html-docx-js` 를 **런타임에 클래식 스크립트로** 불러온다.
 *
 * ⚠️ 왜 `import` 하지 않는가 — 이 패키지는 두 방향 모두 번들에 넣을 수 없다.
 *   1. 패키지 `main`(`build/api.js`)은 node 빌드라 `require('fs')` 로 템플릿을 읽는다.
 *      브라우저 번들에서 `fs` 를 해석하지 못한다.
 *   2. 브라우저용 `dist/html-docx.js` 는 lodash 템플릿을 미리 컴파일해 넣었는데 그 코드가
 *      **`with (obj) { ... }`** 를 쓴다. ESM 은 항상 strict mode 라 `with` 가 문법 오류다 —
 *      rollup(vite build)이 CommonJS→ESM 변환 중
 *      `not implemented: Cannot convert Stmt::With` 로 빌드를 깬다. esbuild 도 같은 이유로 막힌다.
 *
 * 그래서 `?url` 로 **파일을 그대로 산출물에 복사**해 주소만 받고, `<script>` 태그로 실행한다.
 * 클래식 스크립트는 sloppy mode 라 `with` 가 정상 동작하고, `eval`/`new Function` 을 쓰지 않아
 * CSP(`unsafe-eval`)도 요구하지 않는다. UMD 라 로드되면 `window.htmlDocx` 에 API 가 붙는다.
 * 덤으로 400KB 남짓한 이 파일이 메인 번들에서 빠져, DOCX 를 실제로 누를 때만 내려온다.
 */
function loadHtmlDocx(): Promise<HtmlDocx> {
  const loaded = (window as unknown as { htmlDocx?: HtmlDocx }).htmlDocx
  if (loaded) return Promise.resolve(loaded)
  if (htmlDocxLoading) return htmlDocxLoading

  htmlDocxLoading = (async () => {
    // 🔴 동적 import — 정적이면 `?raw` 의 400KB 가 메인 번들에 들어간다. 호출 시점에만
    //   비동기로 청크를 받아온다. Vite 는 `import('...raw')` 를 정식 지원.
    const mod = await import('html-docx-js/dist/html-docx.js?raw')
    const htmlDocxScript = mod.default

    return new Promise<HtmlDocx>((resolve, reject) => {
      // raw 소스를 Blob 으로 감싸 object URL 로 평가 — Vite 의 `?raw` 가 텍스트를 돌려주므로
      // URL 을 만들려면 Blob 이 가장 단순. 평가 후 `onload` 에서 revoke + <script> 도 DOM 에서 제거.
      // 누적 방지를 위해 두 핸들러 모두 정리.
      const blob = new Blob([htmlDocxScript], {
        type: "application/javascript",
      });
      const url = URL.createObjectURL(blob);
      const script = window.document.createElement("script");
      script.src = url;
      script.async = true;
      script.onload = () => {
        URL.revokeObjectURL(url);
        script.remove();
        const api = (window as unknown as { htmlDocx?: HtmlDocx }).htmlDocx;
        if (api) resolve(api);
        else
          reject(
            new Error("html-docx-js 로드 후 window.htmlDocx 가 없습니다."),
          );
      };
      script.onerror = () => {
        URL.revokeObjectURL(url);
        script.remove();
        reject(new Error("html-docx-js 스크립트를 불러오지 못했습니다."));
      };
      window.document.head.appendChild(script);
    });
  })().catch((e: unknown) => {
    htmlDocxLoading = null
    throw e
  })

  return htmlDocxLoading
}

/**
 * HTML 을 DOCX 로 내려받는다.
 *
 * `html-docx-js` 는 HTML 을 MHT 조각으로 감싸 Word 문서에 얹는 방식이라 **완전한 HTML 문서**를
 * 기대한다. 본문(`html`)은 그대로 두고 `<html><head><meta charset>` 껍데기만 씌운다 —
 * 미리보기·PDF 와 본문이 달라지지 않으며, charset 선언이 있어야 한글 제목이 깨지지 않는다.
 *
 * @param html     `buildReportHtml()` 결과
 * @param fileBase 확장자를 뺀 파일명(보고서명)
 */
export async function exportDocx(html: string, fileBase: string): Promise<void> {
  const htmlDocx = await loadHtmlDocx()
  const docHtml = `<!DOCTYPE html><html><head><meta charset="utf-8"></head><body>${html}</body></html>`
  downloadBlob(htmlDocx.asBlob(docHtml), `${sanitizeFileBase(fileBase)}.docx`)
}
