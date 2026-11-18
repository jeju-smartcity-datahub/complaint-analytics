/*
 * PDF 쪽 나눔 배치 (keep 블록 · 표).
 *
 * PDF 는 문서 한 장을 이미지로 캡처해 A4 높이로 자르므로, 경계에 걸린 내용이 그대로 잘린다.
 * 캡처 전에 임시 노드의 위치를 고쳐 경계를 피한다 — 미리보기·DOCX 에는 영향이 없다.
 *
 * 모든 쪽의 위아래에 여백을 남긴다. 내용은 종류별로 다음처럼 다음 쪽으로 넘어간다.
 * - `table`              : 경계에 걸리는 행부터 표를 나눠 다음 쪽에 머리글과 함께 다시 그린다.
 * - `[data-pdf-keep]`    : 쪼개지 않고 통째로 넘긴다.
 * - `[data-pdf-keep-next]`: 제목처럼 뒤 내용과 떨어지면 안 되는 줄. 뒤 내용이 넘어가면 함께 넘어간다.
 * - 그 밖의 글           : 안쪽 블록까지 내려가 가장 작은 블록 단위로 넘긴다.
 *
 * 🔴 한 쪽에 다 들어가지 않는 행·블록은 밀어도 잘리므로 그대로 둔다.
 * 🔴 위치를 고치면 뒤 요소가 모두 움직이므로, 문서 순서대로 처리하며 그때그때 다시 잰다.
 */

/** 쪽 위 여백(px). 문서 템플릿의 위쪽 여백과 같다. */
const PAGE_TOP_GAP_PX = 40
/** 쪽 아래 여백(px). */
const PAGE_BOTTOM_GAP_PX = 40

/** 이 요소를 자식으로 가지면 묶음으로 보고 안쪽 블록을 하나씩 배치한다. */
const CHILD_BLOCK_SELECTOR = ':scope > :is(div, p, table, h1, h2, h3, h4, ul, ol)'

/** 표 하나의 나눔 판정 결과. 없으면 `null` — 모든 행이 경계에 걸리지 않는다. */
interface TableCut {
  rowIndex: number
  pageEnd: number
}

const heightOf = (el: Element) => el.getBoundingClientRect().height

/** 다음 쪽 시작 위치 — `top` 이 속한 쪽의 끝. */
const pageEndOf = (top: number, pageHeight: number) => (Math.floor(top / pageHeight) + 1) * pageHeight

/** `el` 앞에 붙어 있는 keep-next 줄 중 맨 앞 요소. 없으면 `el` 자신이다. */
function anchorOf(el: HTMLElement): HTMLElement {
  let anchor = el
  let prev = anchor.previousElementSibling as HTMLElement | null
  while (prev?.hasAttribute('data-pdf-keep-next')) {
    anchor = prev
    prev = anchor.previousElementSibling as HTMLElement | null
  }
  return anchor
}

/** 표의 행이 `tbody` 로 옮겨져도 열 너비가 달라지지 않게 지금 너비로 고정한다. */
function lockColumnWidths(table: HTMLTableElement): void {
  const reference = Array.from(table.rows).find((row) =>
    Array.from(row.cells).every((cell) => cell.colSpan === 1),
  )
  if (!reference) return

  const colgroup = document.createElement('colgroup')
  Array.from(reference.cells).forEach((cell) => {
    const col = document.createElement('col')
    col.style.width = `${cell.getBoundingClientRect().width}px`
    colgroup.appendChild(col)
  })
  table.querySelector(':scope > colgroup')?.remove()
  table.insertBefore(colgroup, table.firstChild)
  table.style.tableLayout = 'fixed'
}

/**
 * 문서를 A4 쪽 경계에 맞춰 배치한다.
 *
 * @param root       캡처 대상(임시 노드)
 * @param pageHeight `root` 폭 기준 A4 한 쪽의 높이(px)
 */
export function layoutPdfPages(root: HTMLElement, pageHeight: number): void {
  const topOf = (el: Element) => el.getBoundingClientRect().top - root.getBoundingClientRect().top

  /**
   * `el` 의 위치를 `top` 으로 옮긴다. 앞 요소의 아래 여백과 겹치면(margin collapse) 한 번에
   * 맞지 않으므로 다시 재서 보정한다.
   */
  const moveTo = (el: HTMLElement, top: number) => {
    const shift = () => {
      const margin = parseFloat(getComputedStyle(el).marginTop) || 0
      el.style.marginTop = `${margin + top - topOf(el)}px`
    }
    shift()
    if (Math.abs(topOf(el) - top) > 0.5) shift()
  }

  /** 쪽 첫머리에 여백 없이 놓인 블록을 위 여백만큼 내린다. */
  const ensureTopGap = (el: HTMLElement) => {
    const anchor = anchorOf(el)
    const top = topOf(anchor)
    const pageStart = Math.floor(top / pageHeight) * pageHeight
    if (pageStart > 0 && top < pageStart + PAGE_TOP_GAP_PX) moveTo(anchor, pageStart + PAGE_TOP_GAP_PX)
  }

  /** 앞 제목까지 묶어 `pageEnd` 다음 쪽으로 넘긴다. 넘겨도 한 쪽에 안 들어가면 그대로 둔다. */
  const pushToNextPage = (el: HTMLElement, pageEnd: number, contentHeight: number): boolean => {
    const anchor = anchorOf(el)
    const needed = topOf(el) - topOf(anchor) + contentHeight + PAGE_TOP_GAP_PX + PAGE_BOTTOM_GAP_PX
    if (needed > pageHeight) return false
    moveTo(anchor, pageEnd + PAGE_TOP_GAP_PX)
    return true
  }

  const placeBlock = (block: HTMLElement) => {
    ensureTopGap(block)
    const top = topOf(block)
    const height = heightOf(block)
    const pageEnd = pageEndOf(top, pageHeight)
    if (top + height > pageEnd - PAGE_BOTTOM_GAP_PX) pushToNextPage(block, pageEnd, height)
  }

  const findCut = (table: HTMLTableElement, rows: HTMLTableRowElement[]): TableCut | null => {
    const headHeight = table.tHead ? heightOf(table.tHead) : 0
    for (let i = 0; i < rows.length; i++) {
      const height = heightOf(rows[i])
      if (headHeight + height + PAGE_TOP_GAP_PX + PAGE_BOTTOM_GAP_PX > pageHeight) continue
      // 첫 행은 머리글과 같은 쪽에 있어야 하므로 표 시작 위치로 쪽을 정한다.
      const pageEnd = pageEndOf(i === 0 ? topOf(table) : topOf(rows[i]), pageHeight)
      if (topOf(rows[i]) + height > pageEnd - PAGE_BOTTOM_GAP_PX) return { rowIndex: i, pageEnd }
    }
    return null
  }

  /** `rows` 를 새 표로 옮겨 다음 쪽 첫머리에 놓는다. 머리글·열 너비는 원본과 같다. */
  const splitTable = (table: HTMLTableElement, rows: HTMLTableRowElement[], pageEnd: number) => {
    const next = table.cloneNode(false) as HTMLTableElement
    const colgroup = table.querySelector(':scope > colgroup')
    if (colgroup) next.appendChild(colgroup.cloneNode(true))
    if (table.tHead) next.appendChild(table.tHead.cloneNode(true))
    const body = document.createElement('tbody')
    rows.forEach((row) => body.appendChild(row))
    next.appendChild(body)

    // 표 아래 여백은 나뉜 표의 마지막 조각이 이어받는다.
    table.style.marginBottom = '0'
    table.after(next)
    moveTo(next, pageEnd + PAGE_TOP_GAP_PX)
    return next
  }

  const placeTable = (start: HTMLTableElement) => {
    ensureTopGap(start)
    let table = start
    let locked = false
    for (;;) {
      const rows = Array.from(table.tBodies).flatMap((body) => Array.from(body.rows))
      const cut = findCut(table, rows)
      if (!cut) return

      if (cut.rowIndex === 0) {
        // 머리글만 남게 되면 제목과 함께 표를 통째로 넘긴다.
        const firstRowBottom = topOf(rows[0]) - topOf(table) + heightOf(rows[0])
        if (!pushToNextPage(table, cut.pageEnd, firstRowBottom)) return
        continue
      }
      if (!locked) {
        lockColumnWidths(table)
        locked = true
      }
      table = splitTable(table, rows.slice(cut.rowIndex), cut.pageEnd)
    }
  }

  const visit = (el: HTMLElement) => {
    if (el instanceof HTMLTableElement) {
      placeTable(el)
    } else if (
      el.hasAttribute('data-pdf-keep') ||
      el.hasAttribute('data-pdf-keep-next') ||
      !el.querySelector(CHILD_BLOCK_SELECTOR)
    ) {
      if (heightOf(el) > 0) placeBlock(el)
    } else {
      visitChildren(el)
    }
  }
  // 표를 나누면 자식 목록이 달라지므로 먼저 고정한다. 표 안쪽(막대 차트의 안쪽 표)은 행과 함께 움직인다.
  const visitChildren = (parent: HTMLElement) => {
    Array.from(parent.children).forEach((child) => visit(child as HTMLElement))
  }

  visitChildren(root)
}
