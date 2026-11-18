/** 앞선 요소 중 가장 가까운 글자 — 표 바로 위 섹션 제목을 캡션으로 쓴다. */
function nearestHeadingText(table: Element): string {
  for (let el: Element | null = table; el && el.tagName !== 'BODY'; el = el.parentElement) {
    for (let prev = el.previousElementSibling; prev; prev = prev.previousElementSibling) {
      const text = prev.textContent?.replace(/\s+/g, ' ').trim()
      if (text) return text.slice(0, 80)
    }
  }
  return ''
}

/**
 * 보고서 미리보기의 표에 caption·scope 를 더한다(웹접근성 항목 18).
 *
 * 🔴 템플릿을 고치지 않는다 — 같은 `html` 이 PDF·DOCX 로 나가므로 caption 이 파일에 보이게 된다.
 * 그래서 **화면에 그릴 때만** 변환한다. 저장 시점의 과거 보고서에도 그대로 적용된다.
 * 🔴 `th` 없는 표(막대 등 레이아웃 용도)에는 caption 을 넣지 않는다 — OpenWAX 는 "caption 있고 th 없음" 을 오류로 친다.
 */
export function decorateReportTables(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  doc.querySelectorAll('table').forEach((table) => {
    const ths = table.querySelectorAll(':scope > thead > tr > th, :scope > tbody > tr > th, :scope > tr > th')
    if (ths.length === 0) {
      table.setAttribute('role', 'presentation')
      return
    }
    if (!table.caption) {
      const caption = doc.createElement('caption')
      caption.className = 'sr-only'
      caption.textContent = nearestHeadingText(table) || '보고서 표'
      table.prepend(caption)
    }
    ths.forEach((th) => {
      if (th.hasAttribute('scope')) return
      const isHeadRow = th.closest('thead') !== null || th.parentElement?.querySelector('td') === null
      th.setAttribute('scope', isHeadRow ? 'col' : 'row')
    })
  })
  return doc.body.innerHTML
}
