import { useCallback, useEffect, useState } from 'react'
import type { ReportListItem } from '../../api/reports'
import {
  buildAiReportHtml,
  buildPreview,
  isXlsxReport,
  notifyIfSnapshotMissing,
  type ReportXlsxDoc,
} from './reportDownload'

/**
 * 보고서 미리보기 상태 (TSK-4297 Step 1 — `ReportsPage` 의 미리보기 상태·effect·`openPreview` 를 동작 그대로 옮김).
 *
 * 🔴 보고서 목록(`ReportsPage`)과 AI 어시스턴트 `최근 리포트`(`RecentReportList`)가 **이 훅 하나**를 쓴다(D3) —
 *    화면마다 복제하면 늦은 응답 버림·404 처리 규칙이 두 벌이 되어 갈린다(`buildBody` 를 `lib/report/` 로
 *    옮긴 TSK-4121 Step 8-B 와 같은 이유).
 * 🔴 본문은 **`buildPreview(preview)`(= `buildBody` 의 본체) 하나만** 부른다 — 유형별 템플릿·스냅샷 함수를
 *    여기서 직접 import 하지 않는다(엑셀용 스냅샷 `xlsxDoc` 도 그 반환값으로 받는다 — TSK-4338 · TSK-4340).
 *    AI 본문(`openHtml`)은 `buildAiReportHtml` — 같은 `reportDownload` 의 진입점이다(TSK-4293).
 * 🔴 **두 모드가 배타다**(TSK-4293 Step 6) — `open(report)` 은 목록 행(`preview`)으로 본문을 비동기 조립하고,
 *    `openHtml(title, html)` 은 저장 전 AI 답변 카드의 본문(`htmlSrc`)을 곧바로 감싸 넣는다. 둘·`close` 는
 *    서로 상대 상태를 비운다. 🔴 가짜 `ReportListItem` 을 `preview` 에 넣지 않는다 — effect 가 `buildPreview` 로
 *    본문을 덮어쓴다. 모달 열림 판정은 `preview` 가 아니라 **`title`** 이다(두 모드 공통).
 * ⚠️ 화면 고유 동작(예: `ReportsPage` 의 1회성 강조 해제 `setHighlightId(null)`)은 넣지 않는다 — 호출부가 감싼다.
 */
export interface ReportPreviewState {
  /** 미리보기 대상(목록 행 모드). `openHtml` 모드에서는 `null` 이다 — 열림 판정은 `title` 로 한다. */
  preview: ReportListItem | null
  /**
   * 모달 제목이자 **열림 판정**(TSK-4293). `preview?.rptpNm ?? htmlSrc?.title` — null 이면 모달을 닫는다.
   * 호출부는 `previewState.title && <ReportPreviewModal title={previewState.title} …/>` 로 연다.
   */
  title: string | null
  /**
   * 미리보기 본문. **비동기다** — 스냅샷을 받아와야 만들어지는 유형이 있다(`buildBody`).
   * 조회 중·실패면 `null` 이라 모달이 본문 대신 안내 문구를 그린다.
   */
  html: string | null
  /** 엑셀 대상 유형(집중구역·생활권 3종)의 스냅샷 유니온(TSK-4338 → TSK-4340). 다른 유형·조회 중·실패면 `null`. */
  xlsxDoc: ReportXlsxDoc | null
  /**
   * 엑셀 대상 유형인가(TSK-4331 — `isXlsxReport(preview)`). 참이면 모달이 PDF·Excel, 거짓이면 PDF·DOCX 를 그린다.
   * 🔴 `xlsxDoc` 으로 판정하지 않는다 — 조회 중에는 `null` 이라 DOCX 가 잠깐 보였다가 Excel 로 바뀐다.
   */
  xlsxReport: boolean
  loading: boolean
  /** 재시도 가능한 실패. 스냅샷 없음(404)은 켜지 않는다. */
  error: boolean
  /** 미리보기 열기. 앞 본문을 즉시 비운다(깜빡임 방지). */
  open: (report: ReportListItem) => void
  /**
   * 이미 가진 AI 본문으로 열기(TSK-4293 Step 6 — 저장 전 답변 카드). 서버 왕복이 없어 곧바로 본문이 찬다.
   * 본문은 `buildAiReportHtml` 이 `.rpt-doc` 틀로 감싼다.
   */
  openHtml: (title: string, html: string) => void
  /** 모달의 `다시 시도` — 본문 조회를 재실행한다. `openHtml` 모드에서는 아무 일도 하지 않는다(실패가 없다). */
  retry: () => void
  /** 모달 닫기. */
  close: () => void
}

export function useReportPreview(): ReportPreviewState {
  const [preview, setPreview] = useState<ReportListItem | null>(null)
  /** `openHtml` 모드의 원본(TSK-4293). `preview` 와 동시에 차지 않는다. */
  const [htmlSrc, setHtmlSrc] = useState<{ title: string; html: string } | null>(null)
  const [html, setHtml] = useState<string | null>(null)
  const [xlsxDoc, setXlsxDoc] = useState<ReportXlsxDoc | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  /** `다시 시도` 로 본문 조회를 재실행하는 트리거 (목록의 `reloadKey` 와 같은 수법). */
  const [reloadKey, setReloadKey] = useState(0)

  /*
   * 본문 조립. 모달을 열 때 · `다시 시도` 를 누를 때 돈다.
   * `cancelled` 플래그로 늦게 도착한 응답을 버린다 — 모달을 닫았다가 다른 행을 곧바로 여는 경우
   * 앞 요청이 뒤 본문을 덮어쓰는 것을 막는다.
   */
  useEffect(() => {
    if (!preview) return

    let cancelled = false
    setLoading(true)
    setError(false)
    setHtml(null)
    setXlsxDoc(null)

    buildPreview(preview)
      .then((body) => {
        if (cancelled) return
        setHtml(body.html)
        setXlsxDoc(body.xlsxDoc)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        setHtml(null)
        setXlsxDoc(null)
        // 🔴 스냅샷 없음(404)은 `다시 시도` 대상이 아니다 — 안내만 띄우고 모달은 빈 채로 둔다.
        if (!notifyIfSnapshotMissing(e)) setError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [preview, reloadKey])

  /**
   * 🔴 앞 보고서의 본문을 **여는 즉시** 비운다 — effect 는 paint 뒤에 돌아서,
   *    비우지 않으면 다른 행의 본문이 한 프레임 깜빡인다.
   */
  const open = useCallback((report: ReportListItem) => {
    setHtmlSrc(null)
    setPreview(report)
    setHtml(null)
    setXlsxDoc(null)
    setError(false)
    setLoading(true)
  }, [])

  /**
   * 🔴 `preview` 를 비워 effect 가 돌지 않게 한다(effect 는 `preview` 가 없으면 곧바로 빠진다) —
   *    그래서 여기서 넣은 `html` 을 비동기 조립이 덮어쓰지 않는다.
   */
  const openHtml = useCallback((title: string, body: string) => {
    setPreview(null)
    setHtmlSrc({ title, html: body })
    setHtml(buildAiReportHtml(title, body))
    setXlsxDoc(null)
    setError(false)
    setLoading(false)
  }, [])

  // html 모드는 실패가 없다 — 키를 올려도 `preview` 가 null 이라 effect 가 돌지 않지만, 의도를 분명히 해 둔다.
  const retry = useCallback(() => {
    if (htmlSrc) return
    setReloadKey((k) => k + 1)
  }, [htmlSrc])

  const close = useCallback(() => {
    setPreview(null)
    setHtmlSrc(null)
  }, [])

  const title = preview?.rptpNm ?? htmlSrc?.title ?? null
  const xlsxReport = preview != null && isXlsxReport(preview)

  return { preview, title, html, xlsxDoc, xlsxReport, loading, error, open, openHtml, retry, close }
}
