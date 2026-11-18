import { useEffect, useState, type ReactNode } from 'react'
import ScrollWrap from './ScrollWrap'
import { AgentErrorNotice } from './AgentErrorNotice'
import { formatDotDateTime } from '../../lib/datetime'
import { fetchReports, type ReportListItem } from '../../api/reports'
import { useReportPreview } from '../../lib/report/useReportPreview'
import ReportPreviewModal from '../reports/ReportPreviewModal'

/*
 * 우측 `최근 리포트` 카드의 목록 (TSK-4121 Step 8-B).
 *
 * 시안의 `.report` / `.report__row` 구조를 그대로 쓰고, 목데이터 대신 실 API
 * (`GET /api/reports?crtType=AI`)의 결과를 그린다.
 *
 * 🔴 **`AI` 유형만 조회한다**(D12) — 전체 보고서를 보여 주면 대시보드 `보고서` 카드와 중복이다.
 *    그래서 쓰는 함수는 **`api/reports.ts` 의 `fetchReports(crtType, page, size)`** 다.
 *    ⚠️ `api/dashboard.ts` 에 **같은 이름의 `fetchReports(limit)`** 이 따로 있는데 그쪽은
 *    유형 필터가 없어 대시보드 카드와 똑같은 목록이 나온다 — 여기서 쓰지 않는다.
 * 🔁 `AI` 유형 보고서는 답변 카드의 `보고서로 저장`(TSK-4293)이 만든다 — 저장 전에는 **빈 상태가 정상**이며,
 *    그래서 빈 문구를 "리포트가 없습니다" 로 쓰지 않는다(전체 보고서는 있다 — 사용자가 목록 장애로 오해한다).
 *
 * 시안에 없어 이번에 추가한 것 (시안은 정적 목데이터라 실패도 다운로드도 없었다)
 *   - 조회 중 / 조회 실패(+ `다시 시도`) / 빈 목록 상태 — `RecentSessionList` 와 같은 규칙
 *   - 행 클릭 → **미리보기 모달 → PDF·DOCX** (TSK-4297). 행 우측 ↓ 아이콘
 *     (`.report__dl`)은 시안 모양대로 남긴 **장식**이다(`aria-hidden` — D2-A). 다운로드는 모달 안 버튼으로만.
 *   - 🔁 모달은 **보고서 목록(`/reports`) 미리보기와 똑같은 모양**이다(머리말 = 보고서명 · `PDF`·`DOCX` 태그) —
 *     ✅ 사용자 지정 2026-10-30. 시안(UI-PAR-005-03)의 `리포트 미리보기` 머리말·좌측 `PDF 다운로드` 버튼은 쓰지 않는다.
 *
 * 🔴 미리보기는 **공용 훅 `useReportPreview()`**(`lib/report/`) + **공용 `ReportPreviewModal`** 만 쓴다 —
 *    본문은 훅 안의 `buildBody` 가 유형별로 만들고, 내보내기·실패 표시는 모달 몫이다. 본문 생성기와
 *    내보내기 함수를 여기서 직접 조합하면 `ReportsPage` 와 본문 규칙이 갈린다
 *    (그래서 `lib/report/` 의 내보내기·템플릿 함수는 이 폴더에서 직접 import 하지 않는다 — 훅만 예외).
 * ⚠️ 모달은 portal 이 아니므로 `ScrollWrap`(`.report`, 내부 스크롤) **밖 형제**로 두고, 목록의
 *    조회 중·실패·빈 분기와 **무관하게** 렌더한다(재조회 중에도 열린 모달이 사라지지 않게).
 *
 * 목록 순서는 **서버 정렬(최신순) 그대로** 쓴다 — 프론트에서 다시 정렬하지 않는다.
 *
 * ⚠️ 세션 목록(`RecentSessionList`)과 달리 **이 카드는 스스로 조회한다.** 좌측 대화 선택과
 *    아무 상태도 공유하지 않아 페이지로 끌어올릴 이유가 없다(끌어올리면 페이지가 서로 무관한
 *    두 조회의 상태를 6개 더 들고 있게 된다).
 */

/** 카드에 보여 줄 건수. 시안 목데이터도 3건이고 `.report` 높이(356px)가 그만큼이다. */
const PAGE_SIZE = 3

/** 목록 안 상태 문구(조회 중·빈 목록)의 공통 스타일. `RecentSessionList` 와 같은 값이다. */
const NOTICE_STYLE = {
  padding: 'var(--space-16) 0',
  fontSize: 'var(--body2-size)',
  color: 'var(--text-neutral)',
  textAlign: 'center',
} as const

/** 시안 `.report__dl` 안의 다운로드 아이콘(아래 화살표 + 바닥선). */
const DownloadIcon = (
  <svg viewBox="0 0 24 24" fill="none" width="18" height="18" aria-hidden="true">
    <path
      d="M12 4v10m0 0 4-4m-4 4-4-4M5 19h14"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
)

export default function RecentReportList({
  reloadKey: externalReloadKey = 0,
}: {
  /**
   * 외부 재조회 신호(TSK-4293 Step 7) — 답변 카드에서 `보고서로 저장` 이 성공하면 페이지가 올린다.
   * 🔴 내부 `reloadKey`(`다시 시도`)와 이름이 겹쳐 별칭으로 받는다. 자체 조회는 그대로 두고 신호만 받는다.
   */
  reloadKey?: number
} = {}) {
  const [reports, setReports] = useState<ReportListItem[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  /** `다시 시도` 로 재조회하기 위한 트리거(`ReportsPage`·`AiAssistantPage` 와 같은 수법). */
  const [reloadKey, setReloadKey] = useState(0)
  /** 행 클릭으로 여는 미리보기(모달) 상태 — `ReportsPage` 와 같은 훅이다(D3). */
  const previewState = useReportPreview()

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setFailed(false)

    fetchReports('AI', 0, PAGE_SIZE)
      .then((res) => {
        if (cancelled) return
        setReports(res.content)
      })
      .catch(() => {
        if (cancelled) return
        setReports([])
        setFailed(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [reloadKey, externalReloadKey])

  let list: ReactNode
  if (loading) {
    list = <div style={NOTICE_STYLE}>불러오는 중…</div>
  } else if (failed) {
    /*
     * `code` 는 **항상 `null`** 이다 — 이 카드는 Agent 프록시가 아니라 우리 보고서 API 를 부르므로
     * `AgentErrorCode` 가 나올 수 없다. 그래도 같은 컴포넌트를 쓰는 이유는 실패 안내 문구·`다시 시도`
     * 버튼의 규칙을 카드마다 새로 만들지 않기 위해서다(`code=null` → 헤드라인 + `다시 시도`).
     */
    list = (
      <AgentErrorNotice
        headline="최근 리포트를 불러오지 못했습니다."
        code={null}
        onRetry={() => setReloadKey((k) => k + 1)}
      />
    )
  } else if (reports.length === 0) {
    list = <div style={NOTICE_STYLE}>아직 AI가 생성한 리포트가 없습니다.</div>
  } else {
    list = (
      <ScrollWrap className="report">
        {reports.map((r) => (
          <button
            key={r.rptpId}
            type="button"
            className="report__row"
            aria-label={`${r.rptpNm} 미리보기`}
            onClick={() => previewState.open(r)}
          >
            {/* 시안 실측 그대로의 문서 아이콘 글자. `.report__ic` 배경색과 한 세트다. */}
            <span className="report__ic">P</span>
            {/* TSK-4319 — button 안에는 phrasing 만 허용돼 div 대신 span(display:block 은 ai.css). */}
            <span className="report__main">
              <span className="report__name">{r.rptpNm}</span>
              <span className="report__time">{formatDotDateTime(r.crtYmd)}</span>
            </span>
            {/* 장식 아이콘(D2-A) — 행 전체가 미리보기 버튼이다. */}
            <span className="report__dl" aria-hidden="true">
              {DownloadIcon}
            </span>
          </button>
        ))}
      </ScrollWrap>
    )
  }

  return (
    <>
      {list}
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
    </>
  )
}
