import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import Breadcrumb from '../components/common/Breadcrumb'
import Icon from '../components/Icon'
import ConfirmDeleteModal from '../components/common/ConfirmDeleteModal'
import Pager from '../components/common/Pager'
import TopbarActions from '../components/common/TopbarActions'
import ReportPreviewModal from '../components/reports/ReportPreviewModal'
import FileDownloadIcon from '../components/reports/FileDownloadIcon'
import ManualReportToolbar from '../components/reports/ManualReportToolbar'
import {
  EMPTY_MANUAL_FILTER,
  SRC_ALL,
  type ManualReportFilter,
} from '../components/reports/manualReportFilter'
import type { CommonCodeItem } from '../api/commonCodes'
import {
  deleteReport,
  fetchReports,
  fetchReportSrcOptions,
  type PageResponse,
  type ReportListFilter,
  type ReportListItem,
} from '../api/reports'
import {
  buildBody,
  exportXlsx,
  isXlsxReport,
  notifyIfSnapshotMissing,
} from '../lib/report/reportDownload'
import { exportDocx, exportPdf } from '../lib/report/reportExport'
import { useReportPreview } from '../lib/report/useReportPreview'

/*
 * 보고서 목록 `/reports`.
 *
 * 레이아웃·DOM 은 시안을 그대로 옮겼고,
 * 데이터만 목데이터 대신 실 API(`src/api/reports.ts`)에서 받는다.
 *
 * 시안 로직과 동일한 부분
 *   - 세그먼트 탭(자동/직접)에 따라 **컬럼 구성이 통째로 바뀐다**
 *   - **탭마다 페이지 번호를 따로 기억한다** — 탭을 왕복해도 각자 보던 페이지로 돌아온다
 *   - 페이저는 페이지당 10행 고정, 총 1페이지면 렌더링하지 않는다(`Pager` 가 처리)
 *
 * 시안에 없어 이번에 추가한 부분 (시안은 정적 목데이터라 실패 경로가 없다)
 *   - 조회 중 / 조회 실패(+ `다시 시도`) / 빈 목록 상태를 표 안에서 각각 표시
 *
 * 목록 순서는 서버 정렬(최신순)을 그대로 따르고 프론트에서 재정렬하지 않는다.
 *
 * 추가된 것 — 집중 구역 보고서
 *   - **집중 구역 보고서는 미리보기·다운로드가 비동기**다 — 저장된 스냅샷을 받아 문서를 다시 만든다.
 *     그 밖의 유형은 지금처럼 동기 샘플 본문 그대로다(`buildBody` 참고).
 *   - 미리보기 모달에 조회 중 / 조회 실패(+ `다시 시도`) 상태가 생겼다.
 *
 * 추가된 것 — 생활권 시뮬레이션 보고서
 *   - **생활권 시뮬레이션 보고서**도 같은 스냅샷 경로를 탄다(`GET /reports/{id}/lifezone`).
 *     🔴 스냅샷 조회는 **재분석하지 않으므로 재다운로드로 `tb_rptp` 행이 늘지 않는다** —
 *     보고서가 늘어나는 것은 분석 화면의 `내보내기` 뿐이다.
 *
 * 추가된 것 — 직접 생성 탭 필터
 *   - **직접 생성 탭에만** 필터 툴바(보고서명 · 출처 · 생성일 · 초기화)가 붙는다(시안 이식).
 *     🔴 필터는 **서버가 건다** — 서버 페이징이라 프론트에서 거르면 총건수·페이저가 틀린다.
 *   - 보고서명은 **300ms 디바운스** 뒤에 조회하고, 출처·날짜는 즉시 조회한다.
 *   - 서버로 가는 필터가 바뀌면 직접 생성 탭을 1페이지로 되돌리고 1회성 강조를 끈다.
 *     🔴 되돌림은 effect 가 아니라 **값을 바꾸는 그 자리**(핸들러·디바운스 확정 시점)에서 한다 —
 *        effect 로 하면 "옛 페이지 + 새 필터" 요청이 한 번 더 나간다.
 *   - 필터는 탭을 왕복해도 유지된다(탭별 페이지 기억과 같은 원칙). 자동 생성 탭 조회에는
 *     필터를 넘기지 않으며, 필터 값이 바뀌어도 자동 생성 탭은 재조회하지 않는다.
 *   - 빈 목록 문구가 갈린다 — 필터가 걸려 있으면 `조건에 맞는 보고서가 없습니다.`
 *
 * 직접 생성 보고서 삭제
 *   - **직접 생성 탭에만** `다운로드` 옆에 `삭제` 컬럼이 붙고, 행마다 × 버튼이 있다.
 *     누르면 확인 모달 → `DELETE /reports/{id}` → 같은 페이지를 다시 조회한다.
 *     지워서 현재 페이지가 비면(마지막 행) 한 페이지 앞으로 간다.
 */

/** 페이지당 행 수. 서버 `size` 와 `Pager.perPage` 가 같은 값을 써야 총 페이지 수가 맞는다. */
const PER_PAGE = 10

/** 세그먼트 탭 정의. `crtType` 은 서버 쿼리 파라미터 값과 1:1 이다. */
const TABS = [
  { crtType: 'AUTO', label: '자동 생성 보고서' },
  { crtType: 'MANUAL', label: '직접 생성 보고서' },
] as const

type CrtType = (typeof TABS)[number]['crtType']

/** 탭별 표 헤더. 자동/직접은 컬럼 수(7)는 같고 3칸의 의미가 다르다. */
const HEADERS: Record<CrtType, readonly string[]> = {
  AUTO: ['보고서 명', '배포 주기', '생성자', '배포 부서', '생성일', '미리보기', '다운로드'],
  MANUAL: ['보고서 명', '출처', '분석 기간', '생성일', '생성자', '미리보기', '다운로드', '삭제'],
}

/** 행 삭제 × 버튼 — 정책 시뮬레이션 시나리오 비교표의 삭제 버튼과 같은 모양이다. */
const DEL_BTN: CSSProperties = {
  width: 32,
  height: 32,
  border: 'none',
  borderRadius: 'var(--radius-full)',
  background: 'none',
  color: 'var(--text-neutral)',
  cursor: 'pointer',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
}

/** 보고서명 입력 디바운스(ms) — 키 입력마다 서버를 부르지 않게 한다. */
const KEYWORD_DEBOUNCE_MS = 300

/**
 * `2026-07-01T09:00:00` → `2026-07-01`.
 * 서버가 내리는 LocalDateTime 문자열을 그대로 자른다 — `Date` 로 파싱하면 타임존에 따라
 * 날짜가 하루 밀릴 수 있어 문자열 절단이 안전하다.
 */
function formatDate(isoDateTime: string): string {
  return isoDateTime.slice(0, 10)
}

/** 내보내기 실패 문구. 보고서 관리 화면(`ReportManagementPage`)의 오류 문구와 같은 토큰을 쓴다. */
const ERROR_TEXT: CSSProperties = {
  color: 'var(--status-negative)',
  fontSize: 'var(--label1-size)',
}

/*
 * 🔴 본문 생성기 `buildBody(report)` 는 **`lib/report/reportDownload.ts` 로 옮겼다**.
 * AI 어시스턴트의 `최근 리포트` 카드도 같은 본문·같은 내보내기를 쓰기
 * 때문이다 — 스냅샷 분기를 화면마다 복제하면 규칙이 두 벌이 되어 갈린다.
 * 이 화면의 동작(미리보기 모달 · PDF/DOCX 2버튼)은 그대로다.
 */

/**
 * 다운로드 태그 2개(PDF·DOCX). `.rt td` 에는 gap 이 없어 래퍼에서만 간격을 준다.
 *
 * 미리보기 모달과 **같은 `buildBody(report)`** 를 내보낸다 — 화면에서 본 문서와
 * 내려받은 파일의 내용이 갈리지 않게 한다.
 */
function DownloadCell({ report }: { report: ReportListItem }) {
  /** 내보내는 동안 두 버튼을 함께 잠근다 — 연타로 같은 파일이 여러 번 만들어지는 것을 막는다. */
  const [exporting, setExporting] = useState(false)
  const [failed, setFailed] = useState(false)

  /*
   * `exporting` 잠금이 **본문 생성까지 감싼다** — 스냅샷 유형(집중 구역·생활권 등)은 여기서 서버 왕복
   * (`GET /reports/{id}/hotspot` 등)이 일어나므로, 잠금이 그 앞에서 풀리면 연타로 같은 조회가 겹친다.
   */
  const runExport = (exportFn: (html: string, fileBase: string) => Promise<void>) =>
    runTask(async () => exportFn(await buildBody(report), report.rptpNm))

  /** 세 버튼 공통 — 잠금·404 안내·실패 문구(XLSX 가 함께 쓰도록 뺐다. PDF·DOCX 동작은 그대로). */
  const runTask = async (task: () => Promise<void>) => {
    setExporting(true)
    setFailed(false)
    try {
      await task()
    } catch (e) {
      // 🔴 스냅샷 없음(404)은 **실패가 아니라 "아직 없음"** 이다 —
      //    안내만 띄우고 `내보내기 실패` 는 켜지 않는다(다시 눌러도 결과가 같다).
      //    그 밖의 실패는 종전대로 — 조회 실패든 캡처·변환 실패든 할 일은 같다(다시 누른다).
      if (!notifyIfSnapshotMissing(e)) setFailed(true)
    } finally {
      setExporting(false)
    }
  }

  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--space-6)' }}>
      <button
        type="button"
        className="tag tag--file"
        aria-label={`${report.rptpNm} PDF 다운로드`}
        disabled={exporting}
        onClick={() => {
          void runExport(exportPdf)
        }}
      >
        <FileDownloadIcon />
        PDF
      </button>
      {/*
       * 🔴 형식은 유형으로 갈린다 — 판정은 `isXlsxReport` 한 곳.
       * 엑셀 대상(집중구역·생활권 3종·구역추천·투자우선순위)은 PDF·XLSX, 그 밖(민원현황·정책 시뮬레이션 등)은 PDF·DOCX.
       */}
      {!isXlsxReport(report) && (
        <button
          type="button"
          className="tag tag--file"
          aria-label={`${report.rptpNm} DOCX 다운로드`}
          disabled={exporting}
          onClick={() => {
            void runExport(exportDocx)
          }}
        >
          <FileDownloadIcon />
          DOCX
        </button>
      )}
      {isXlsxReport(report) && (
        <button
          type="button"
          className="tag tag--file"
          aria-label={`${report.rptpNm} XLSX 다운로드`}
          disabled={exporting}
          onClick={() => {
            void runTask(() => exportXlsx(report))
          }}
        >
          <FileDownloadIcon />
          XLSX
        </button>
      )}
      {failed && (
        <span style={ERROR_TEXT} role="alert">
          내보내기 실패
        </span>
      )}
    </span>
  )
}

export default function ReportsPage() {
  const [searchParams] = useSearchParams()
  /*
   * 대시보드 '보고서 > 전체' 는 직접 생성 탭으로 들어온다.
   * 값이 없거나 화이트리스트 밖이면 기존 기본(AUTO).
   *
   * 🔴 **초기값으로만 읽는다.** `useEffect` 로 계속 동기화하면 사용자가 탭을 눌러도
   * URL 이 되돌려 놓는다. 탭을 바꿔도 URL 은 갱신하지 않는다(시안 동작 유지).
   */
  const initialTab: CrtType = searchParams.get('crtType') === 'MANUAL' ? 'MANUAL' : 'AUTO'
  const [tab, setTab] = useState<CrtType>(initialTab)
  const location = useLocation()
  /**
   * 대시보드 보고서 카드에서 넘어온 강조 대상(`rptpId`).
   *
   * 🔴 **1회성이다** — 사용자 조작(탭 변경·페이지 이동·미리보기 열기) 시점에 지운다.
   *    남겨 두면 탭·페이지를 옮겨도 계속 강조되고, 뒤로가기로 돌아올 때 다시 켜진다.
   *    ⚠️ `useEffect` 로 타이머를 걸지 않는다 — 스크롤해서 찾는 중에 사라진다.
   *    이름이 아니라 ID 로 받는다(동명 보고서 오강조 방지).
   *
   * ⚠️ 강조 대상이 1페이지에 없을 수 있지만(직접 생성분이 많으면) **페이지 탐색까지는 하지 않는다** —
   *    카드가 보여주는 것은 최신 4건이고, 목록도 최신순이라 그건 1페이지 안에 있다는 전제다.
   */
  const [highlightId, setHighlightId] = useState<number | null>(
    (location.state as { highlightRptpId?: number } | null)?.highlightRptpId ?? null,
  )
  /** 탭별 페이지(0-base). 탭 전환 시 서로의 위치를 지우지 않는다(시안 동작). */
  const [pages, setPages] = useState<Record<CrtType, number>>({ AUTO: 0, MANUAL: 0 })
  const [data, setData] = useState<PageResponse<ReportListItem> | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  /** `다시 시도` 로 재조회하기 위한 트리거. */
  const [reloadKey, setReloadKey] = useState(0)
  /** 미리보기 상태는 `useReportPreview`(AI 어시스턴트 `최근 리포트`와 공용). */
  const previewState = useReportPreview()

  /** 직접 생성 탭 필터 입력 원본. 탭을 왕복해도 지우지 않는다. */
  const [manualFilter, setManualFilter] = useState<ManualReportFilter>(EMPTY_MANUAL_FILTER)
  /** 서버로 보내는 보고서명 — 입력을 300ms 멈춘 뒤 확정된 **trim 된** 값. */
  const [debouncedKeyword, setDebouncedKeyword] = useState('')
  /** 출처 옵션. 조회 실패면 빈 배열이라 셀렉트에 `전체` 만 남는다(필터 하나가 목록을 막지 않게). */
  const [srcOptions, setSrcOptions] = useState<CommonCodeItem[]>([])

  /** 삭제 확인 대상. null 이면 확인 모달을 닫는다. */
  const [deleteTarget, setDeleteTarget] = useState<ReportListItem | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const page = pages[tab]
  /** 상태 행(조회 중·실패·빈 목록)의 colSpan — 탭마다 컬럼 수가 다르다(자동 7 · 직접 8). */
  const colCount = HEADERS[tab].length

  /**
   * 서버용 필터가 바뀌었을 때 — 직접 생성 탭을 1페이지로 되돌리고 1회성 강조를 끈다.
   * 🔴 effect 가 아니라 값을 바꾸는 자리에서 부른다(같은 렌더에 배치돼 목록 조회가 1번만 나간다).
   *    그래서 최초 마운트에는 불리지 않는다.
   */
  const resetManualPage = useCallback(() => {
    setHighlightId(null)
    setPages((prev) => (prev.MANUAL === 0 ? prev : { ...prev, MANUAL: 0 }))
  }, [])

  // 보고서명 디바운스. 확정 시점에 페이지 되돌림을 함께 한다(위 주석).
  useEffect(() => {
    const next = manualFilter.keyword.trim()
    if (next === debouncedKeyword) return
    const timer = setTimeout(() => {
      setDebouncedKeyword(next)
      resetManualPage()
    }, KEYWORD_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [manualFilter.keyword, debouncedKeyword, resetManualPage])

  /**
   * 목록 조회에 넘길 필터. **직접 생성 탭에서만** 값이 있고 자동 생성 탭에서는 항상 `undefined` 다.
   * 🔴 원시값으로 메모해 **객체 정체성**을 고정한다 — 자동 생성 탭에 있는 동안 디바운스가 늦게
   *    확정돼도 `undefined → undefined` 라 목록 effect 가 다시 돌지 않는다.
   */
  const listFilter = useMemo<ReportListFilter | undefined>(() => {
    if (tab !== 'MANUAL') return undefined
    const f: ReportListFilter = {
      keyword: debouncedKeyword || undefined,
      anlsTypeCd: manualFilter.anlsTypeCd === SRC_ALL ? undefined : manualFilter.anlsTypeCd,
      fromYmd: manualFilter.fromYmd || undefined,
      toYmd: manualFilter.toYmd || undefined,
    }
    return Object.values(f).some((v) => v !== undefined) ? f : undefined
  }, [tab, debouncedKeyword, manualFilter.anlsTypeCd, manualFilter.fromYmd, manualFilter.toYmd])

  /** 툴바 입력. 출처·날짜는 즉시 서버용 필터가 바뀌므로 그 자리에서 페이지를 되돌린다. */
  const changeManualFilter = (next: ManualReportFilter) => {
    const serverChanged =
      next.anlsTypeCd !== manualFilter.anlsTypeCd ||
      next.fromYmd !== manualFilter.fromYmd ||
      next.toYmd !== manualFilter.toYmd
    setManualFilter(next)
    if (serverChanged) resetManualPage()
  }

  /** `초기화` — 네 값을 비운다. 디바운스를 기다리지 않고 서버용 보고서명도 즉시 비운다. */
  const resetManualFilter = () => {
    const serverChanged =
      debouncedKeyword !== '' ||
      manualFilter.anlsTypeCd !== SRC_ALL ||
      manualFilter.fromYmd !== '' ||
      manualFilter.toYmd !== ''
    setManualFilter(EMPTY_MANUAL_FILTER)
    setDebouncedKeyword('')
    if (serverChanged) resetManualPage()
  }

  // 출처 옵션 — 마운트 시 1회. 실패는 삼킨다(`전체` 만 남고 목록은 계속 동작).
  useEffect(() => {
    let cancelled = false
    fetchReportSrcOptions()
      .then((opts) => {
        if (!cancelled) setSrcOptions(opts)
      })
      .catch(() => {
        if (!cancelled) setSrcOptions([])
      })
    return () => {
      cancelled = true
    }
  }, [])

  // 탭·페이지·(직접 생성 탭) 필터가 바뀔 때마다 서버에서 해당 구간만 받아온다(전체 캐싱 없음 — 서버 페이징이 원본).
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(false)
    // 이전 탭/페이지의 결과를 비운다 — 남겨두면 조회 중에 **다른 탭의 총 건수**로 페이저가 그려진다.
    setData(null)

    fetchReports(tab, page, PER_PAGE, listFilter)
      .then((res) => {
        if (cancelled) return
        setData(res)
      })
      .catch(() => {
        if (cancelled) return
        setData(null)
        setLoadError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [tab, page, reloadKey, listFilter])

  /** 미리보기 열기. 본문 비우기·조회는 훅이 한다 — 여기선 화면 고유 동작만 얹는다. */
  const openPreview = (report: ReportListItem) => {
    setHighlightId(null) // 사용자 조작이 일어났으면 1회성 강조를 끈다.
    previewState.open(report)
  }

  const setPage = (next: number) => {
    setHighlightId(null) // 페이지를 옮기면 강조를 끈다(1회성).
    setPages((prev) => ({ ...prev, [tab]: next }))
  }

  const rows = data ? data.content : []

  const openDelete = (report: ReportListItem) => {
    setHighlightId(null)
    setDeleteError(null)
    setDeleteTarget(report)
  }

  const closeDelete = () => {
    if (deleting) return
    setDeleteTarget(null)
    setDeleteError(null)
  }

  const confirmDelete = async () => {
    if (!deleteTarget) return
    setDeleting(true)
    setDeleteError(null)
    try {
      await deleteReport(deleteTarget.rptpId)
      setDeleteTarget(null)
      // 이 페이지의 마지막 행을 지웠으면 한 페이지 앞으로 — 아니면 같은 페이지를 다시 받는다.
      if (rows.length === 1 && page > 0) {
        setPages((prev) => ({ ...prev, MANUAL: prev.MANUAL - 1 }))
      } else {
        setReloadKey((k) => k + 1)
      }
    } catch {
      setDeleteError('삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setDeleting(false)
    }
  }

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="보고서·알림" page="보고서 목록" />
          <h1 className="page-title">보고서 목록</h1>
          <p className="page-sub">민원 분석 결과 보고서 목록</p>
        </div>
        <TopbarActions />
      </header>

      <div className="content content--reports">
        <div className="segment">
          {TABS.map((t) => (
            <button
              key={t.crtType}
              type="button"
              aria-pressed={tab === t.crtType}
              className={`segment__btn${tab === t.crtType ? ' segment__btn--active' : ''}`}
              onClick={() => {
                // 탭을 바꾸면 1회성 강조를 끈다(돌아와도 다시 켜지지 않는다).
                setHighlightId(null)
                setTab(t.crtType)
              }}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className={`card ${tab === 'AUTO' ? 'rpt-list' : 'rpt-manual'}`}>
          {/* 직접 생성 탭에만. 자동 생성 탭은 시안에 필터가 없다. */}
          {tab === 'MANUAL' && (
            <ManualReportToolbar
              value={manualFilter}
              onChange={changeManualFilter}
              srcOptions={srcOptions}
              onReset={resetManualFilter}
            />
          )}
          <div className="rt-wrap">
            <table className="rt">
              <caption className="sr-only">보고서 목록</caption>
              <thead>
                <tr>
                  {HEADERS[tab].map((h) => (
                    <th scope="col" key={h}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {loading && (
                  <tr>
                    <td className="col-center" colSpan={colCount}>
                      불러오는 중...
                    </td>
                  </tr>
                )}

                {!loading && loadError && (
                  <tr>
                    <td className="col-center" colSpan={colCount}>
                      보고서 목록을 불러오지 못했습니다.{' '}
                      <button
                        type="button"
                        className="btn-edit"
                        onClick={() => setReloadKey((k) => k + 1)}
                      >
                        다시 시도
                      </button>
                    </td>
                  </tr>
                )}

                {!loading && !loadError && rows.length === 0 && (
                  <tr>
                    <td className="col-center" colSpan={colCount}>
                      {listFilter ? '조건에 맞는 보고서가 없습니다.' : '생성된 보고서가 없습니다.'}
                    </td>
                  </tr>
                )}

                {!loading &&
                  !loadError &&
                  rows.map((r) => (
                    <tr
                      key={r.rptpId}
                      className={r.rptpId === highlightId ? 'rt-row--hl' : undefined}
                    >
                      <td>
                        <span className="rt-name">{r.rptpNm}</span>
                      </td>

                      {tab === 'AUTO' ? (
                        <>
                          <td>{r.cyclNm ?? '-'}</td>
                          <td>{r.creatrNm}</td>
                          <td>{r.deptNm ?? '-'}</td>
                        </>
                      ) : (
                        <>
                          <td>{r.srcNm ?? '-'}</td>
                          <td>{r.anlsPrdExpln ?? '-'}</td>
                        </>
                      )}

                      <td className="rt-date">{formatDate(r.crtYmd)}</td>

                      {tab === 'MANUAL' && <td>{r.creatrNm}</td>}

                      <td className="col-center">
                        <button type="button" className="tag" onClick={() => openPreview(r)}>
                          미리보기
                        </button>
                      </td>
                      <td className="col-center">
                        <DownloadCell report={r} />
                      </td>
                      {tab === 'MANUAL' && (
                        <td className="col-center">
                          <button
                            type="button"
                            aria-label={`${r.rptpNm} 삭제`}
                            title="삭제"
                            style={DEL_BTN}
                            onClick={() => openDelete(r)}
                            onMouseEnter={(e) => {
                              e.currentTarget.style.background = 'var(--fill-normal)'
                            }}
                            onMouseLeave={(e) => {
                              e.currentTarget.style.background = 'none'
                            }}
                          >
                            <Icon name="close" size={16} />
                          </button>
                        </td>
                      )}
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        </div>

        {data && <Pager total={data.totalElements} page={page} setPage={setPage} perPage={PER_PAGE} />}
      </div>

      {previewState.title && (
        <ReportPreviewModal
          title={previewState.title}
          html={previewState.html}
          xlsxDoc={previewState.xlsxDoc}
          xlsxReport={previewState.xlsxReport}
          loading={previewState.loading}
          error={previewState.error}
          onRetry={previewState.retry}
          onClose={previewState.close}
        />
      )}

      {deleteTarget && (
        <ConfirmDeleteModal
          message={`'${deleteTarget.rptpNm}' 보고서를 삭제하시겠습니까?`}
          busy={deleting}
          error={deleteError}
          onCancel={closeDelete}
          onConfirm={() => {
            void confirmDelete()
          }}
        />
      )}
    </>
  )
}
