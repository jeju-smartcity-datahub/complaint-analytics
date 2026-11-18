import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import Modal from '../common/Modal'
import FileDownloadIcon from './FileDownloadIcon'
import { exportDocx, exportPdf } from '../../lib/report/reportExport'
import {
  NO_XLSX_DATA_MESSAGE,
  xlsxSource,
  type ReportXlsxDoc,
} from '../../lib/report/reportDownload'
import { decorateReportTables } from '../../lib/report/previewA11y'

/*
 * 보고서 미리보기 모달 (TSK-4026 Step 9 · 내보내기 연결은 Step 10 · 비동기 본문은 TSK-4078 Step 10).
 *
 * **얇은 셸이다.** 본문 HTML 을 만들지 않는다 — 문자열을 받아서 그대로 그린다.
 * 본문 생성기는 `lib/report/reportDownload.buildBody` 가 유형(`anlsTypeCd`)별로 고른다 — 스냅샷을
 * 받아 만든 유형별 템플릿 결과이거나, 스냅샷이 없는 유형이면 샘플 본문(`buildReportHtml`)이다.
 *
 * 🔴 **집중 구역 보고서는 본문이 서버 왕복을 거치므로 `html` 이 곧바로 오지 않는다.**
 * 그래서 `html: string` 이 아니라 `html | loading | error | onRetry` 네 칸을 받는다 —
 * 상태를 소유하는 것은 호출부의 `useReportPreview` 훅(`ReportsPage` · AI 어시스턴트 `RecentReportList`,
 * TSK-4297 — 두 화면이 같은 모양으로 연다)이고, 이 모달은 그 상태를 그리기만 한다.
 * ⚠️ 이 모달의 PDF·DOCX 버튼도 같은 `html` 을 쓰므로 **본문이 없는 동안은 두 버튼을 잠근다** —
 * 잠그지 않으면 빈 문서가 파일로 내려간다.
 *
 * ⚠️ `dangerouslySetInnerHTML` 을 쓰는 이유: 미리보기에 그린 것과 **완전히 같은 HTML** 을
 * PDF·DOCX 내보내기가 다시 쓴다(문서 서식이 두 벌로 갈리지 않게 한다).
 * 그래서 넘어오는 `html` 은 **프론트가 템플릿으로 조립한 값만** 허용한다 — 서버·사용자 입력을
 * 그대로 흘려보내지 않는다(조립 시 이스케이프는 `buildReportHtml` 의 책임).
 * 🔁 **예외 — AI 보고서 본문(TSK-4293)** 은 외부 서비스가 준 HTML 이다. 그래서 두 겹을 거친 값만 받는다:
 * ① 서버 jsoup `Safelist` 새니타이즈(script·이벤트 속성·`javascript:` 제거, D3) ② 프론트 `.rpt-doc` 템플릿 래핑
 * (`reportDownload.buildAiReportHtml` — 고정 위치 박스를 가두는 `contain` 래퍼). 원문을 이 모달에 바로 넘기지 않는다.
 */

/**
 * 조회 실패 문구 스타일. `ReportsPage.ERROR_TEXT` · 아래 `내보내기 실패` 와 **같은 토큰**을 쓴다 —
 * 보고서 화면의 오류 문구가 한 가지 색·크기로 보이게 한다.
 */
const ERROR_TEXT: CSSProperties = {
  color: 'var(--status-negative)',
  fontSize: 'var(--label1-size)',
}

/**
 * 본문 템플릿의 문서 폭(A4, 각 템플릿의 `A4_WIDTH_PX`). 템플릿은 내보내기와 같은 HTML 이라
 * 폭을 줄이지 않고, 미리보기에서만 모달 폭에 맞춰 축소해 가로 스크롤을 없앤다.
 */
const DOC_WIDTH_PX = 794

interface ReportPreviewModalProps {
  /**
   * 머리말 제목이자 내려받을 파일명(TSK-4293 — 종전 `report: ReportListItem` 에서 쓰던 칸이 `rptpNm` 하나뿐이었다).
   * 목록 행이면 `rptpNm`, 저장 전 AI 답변 카드면 영역 제목이다(`useReportPreview().title`).
   */
  title: string
  /**
   * 본문 HTML. **`buildBody(report)` 결과**(`useReportPreview().html`)를 넣는다.
   * 조회 중·실패면 `null` 이고, 빈 문자열이면 준비 중 안내를 대신 그린다.
   */
  html: string | null
  /**
   * 엑셀 대상 유형의 스냅샷 유니온(`useReportPreview().xlsxDoc` — 집중구역 TSK-4338 · 생활권 3종·구역추천 TSK-4340).
   * 값이 있을 때만 Excel 버튼을 그린다. 🔴 유형마다 prop 을 늘리지 않는다 — `kind` 로 가른다.
   * 🔴 **필수 prop 이다** — 선택값이면 호출부(`ReportsPage`·`RecentReportList`·`AiAssistantPage`)가 빠뜨려도
   * 컴파일되고 그 화면에서만 버튼이 조용히 사라진다. 다른 유형은 `null` 을 넘긴다.
   */
  xlsxDoc: ReportXlsxDoc | null
  /**
   * 엑셀 대상 유형인가(`useReportPreview().xlsxReport` — TSK-4331). 참이면 **PDF·Excel**, 거짓이면 **PDF·DOCX** 다.
   * 🔴 필수 prop — 위 `xlsxDoc` 과 같은 이유(빠뜨려도 컴파일되면 형식이 조용히 바뀐다).
   */
  xlsxReport: boolean
  /** 본문 조회 중. 안내 문구를 그리고 내보내기 두 버튼을 잠근다. */
  loading: boolean
  /** 본문 조회 실패. 안내 문구 + `다시 시도` 를 그린다. */
  error: boolean
  /** `다시 시도` — 호출부가 본문 조회를 다시 돈다. */
  onRetry: () => void
  /** 닫기 요청 (overlay 클릭 · Escape · 닫기 버튼). */
  onClose: () => void
}

export default function ReportPreviewModal({
  title,
  html,
  xlsxDoc,
  xlsxReport,
  loading,
  error,
  onRetry,
  onClose,
}: ReportPreviewModalProps) {
  /** 내보내는 동안 두 버튼을 함께 잠근다 — 연타로 같은 파일이 여러 번 만들어지는 것을 막는다. */
  const [exporting, setExporting] = useState(false)
  const [failed, setFailed] = useState(false)

  // 본문 영역 폭 ÷ 문서 폭 — 1 을 넘기지 않는다(넓은 창에서 확대하지 않는다).
  const frameRef = useRef<HTMLDivElement>(null)
  const [fit, setFit] = useState(1)
  // 표시에만 쓴다 — 내보내기는 아래처럼 원본 `html` 을 그대로 쓴다.
  const previewHtml = useMemo(() => (html ? decorateReportTables(html) : ''), [html])

  useEffect(() => {
    const frame = frameRef.current
    if (!frame) return
    const observer = new ResizeObserver(([entry]) => {
      setFit(Math.min(1, entry.contentRect.width / DOC_WIDTH_PX))
    })
    observer.observe(frame)
    return () => observer.disconnect()
  }, [html])

  // 화면에 그린 것과 **같은 문자열**을 그대로 내보낸다 — 다시 조립하지 않는다.
  const runExport = async (exportFn: (source: string, fileBase: string) => Promise<void>) => {
    // 버튼이 이미 잠겨 있어 도달하지 않는 경로지만, 본문 없이 빈 파일을 만드는 일은 막아 둔다.
    if (!html) return
    await runTask(() => exportFn(html, title))
  }

  /*
   * 엑셀(TSK-4338 · TSK-4340) — 본문 HTML 이 아니라 **같은 스냅샷**으로 만든다(판정·생성기 선택은 `xlsxSource` 한 곳).
   * 구 보고서(`wbk` 없음 · 구 수급 사분면)는 클릭 시 안내만 띄운다 — `내보내기 실패` 가 아니다(2026-11-09 사용자 결정 · D7-2).
   */
  const runXlsx = async () => {
    const task = xlsxSource(xlsxDoc, title)
    if (!task) {
      window.alert(NO_XLSX_DATA_MESSAGE)
      return
    }
    await runTask(task)
  }

  /** 세 버튼 공통 — 잠금·실패 문구. */
  const runTask = async (task: () => Promise<void>) => {
    setExporting(true)
    setFailed(false)
    try {
      await task()
    } catch {
      setFailed(true)
    } finally {
      setExporting(false)
    }
  }

  return (
    <Modal
      title={title}
      variant="rpt"
      onClose={onClose}
      footer={
        // `.modal__foot` 에는 gap 이 없다(시안 모달은 버튼이 하나뿐이었다). CSS 신설 대신
        // 버튼 두 개를 감싸는 래퍼에만 간격을 준다 — reports.css 는 Step 8 산출물이라 건드리지 않는다.
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-8)' }}>
          {failed && (
            <span style={ERROR_TEXT} role="alert">
              내보내기 실패
            </span>
          )}
          <button
            type="button"
            className="tag tag--file"
            disabled={exporting || loading || !html}
            onClick={() => {
              void runExport(exportPdf)
            }}
          >
            <FileDownloadIcon />
            PDF
          </button>
          {/* 🔴 형식은 유형으로 갈린다(TSK-4331) — 엑셀 대상이면 PDF·Excel, 아니면 PDF·DOCX. */}
          {!xlsxReport && (
            <button
              type="button"
              className="tag tag--file"
              disabled={exporting || loading || !html}
              onClick={() => {
                void runExport(exportDocx)
              }}
            >
              <FileDownloadIcon />
              DOCX
            </button>
          )}
          {xlsxReport && (
            <button
              type="button"
              className="tag tag--file"
              disabled={exporting || loading || !html || !xlsxDoc}
              onClick={() => {
                void runXlsx()
              }}
            >
              <FileDownloadIcon />
              Excel
            </button>
          )}
        </div>
      }
    >
      {loading && <p>조회 중…</p>}

      {!loading && error && (
        <p style={ERROR_TEXT} role="alert">
          본문을 불러오지 못했습니다.{' '}
          <button type="button" className="btn-edit" onClick={onRetry}>
            다시 시도
          </button>
        </p>
      )}

      {!loading && !error && (html ? (
        <div ref={frameRef}>
          <div style={{ zoom: fit }} dangerouslySetInnerHTML={{ __html: previewHtml }} />
        </div>
      ) : (
        <p>보고서 본문은 준비 중입니다.</p>
      ))}
    </Modal>
  )
}
