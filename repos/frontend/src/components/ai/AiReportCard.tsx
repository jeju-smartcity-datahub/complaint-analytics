import { useState } from 'react'
import axios from 'axios'
import { AgentErrorNotice } from './AgentErrorNotice'
import {
  agentErrorCode,
  createAiReport,
  type AgentErrorCode,
  type AiReportBlock,
} from '../../api/ai'

/*
 * 답변 속 HTML 보고서 카드 (TSK-4293 Step 6).
 *
 * 말풍선(`MessageBubble`)이 자리표시 `[[AI_REPORT:{n}]]` 자리에 끼워 그린다. 시안의 `.cite` 카드 모양을 쓴다
 * (`ai.css` — TSK-4121 이 응답 렌더용으로 옮겨 둔 규칙).
 *
 * 🔴 **카드는 HTML 을 그리지 않는다** — 제목과 버튼만 그린다. 본문은 미리보기 모달만 그린다
 *    (`components/ai` 에 이스케이프 해제 API 금지 규칙 유지).
 * 🔴 **훅·모달을 갖지 않는다** — `onPreview(title, html)` 콜백만 받는다. `useReportPreview()` 와
 *    `ReportPreviewModal` 은 `AiAssistantPage` 에 **한 벌** 있고 대화 스크롤 영역 밖 형제로 렌더된다
 *    (모달이 portal 이 아니다 — 말풍선마다 두면 스크롤 컨테이너 안에 갇힌다). PDF·DOCX 는 모달 안 버튼이다(D5).
 * 🔴 `lib/report` 의 내보내기·템플릿을 import 하지 않는다(`RecentReportList` 와 같은 규칙).
 * 🔴 `보고서로 저장` 은 본문을 보내지 않는다 — `{msgId, rptIdx}` 만 보내고 서버가 다시 받아 꺼낸다(D6).
 *    그래서 `sessionId`·`msgId` 가 없으면(새 대화 첫 응답 전 · 구 응답) 잠근다.
 *
 * 🔁 D4 — `PENDING`(끝 마커 없음)은 "생성 중" 으로 그리고 두 버튼을 잠근다. 재조회는 카드가 아니라
 *    **페이지가 한다**(10초 간격 · 최대 3분). 3분이 지나면 `pendingExpired` 가 켜져 미완성 안내 +
 *    `다시 불러오기`(1회 재조회)로 바뀐다. `TRUNCATED`(길이 상한 잘림)는 기다리지 않고 곧바로 그 안내다.
 */

/** 영역 제목이 없을 때(D8 — 서버가 `<title>`·`<h1>` 를 못 찾았다) 화면·모달 머리말. */
const FALLBACK_TITLE = 'AI 보고서'

/** 저장 대상이 서버에 없을 때(404) — Step 7 계획서 문구 그대로. 버튼은 다시 누를 수 있게 둔다. */
const NOT_FOUND_TEXT = '보고서를 찾을 수 없습니다 — 대화를 새로고침해 주세요'

/** 미완성 안내 — D4 문구 그대로. */
const INCOMPLETE_TEXT = '보고서가 완성되지 않았어요 — 다시 요청해 주세요'

const NOTE_STYLE = {
  margin: 'var(--space-10) 0 var(--space-12)',
  fontSize: 'var(--body2-size)',
  color: 'var(--text-neutral)',
} as const

const ERROR_STYLE = {
  margin: 'var(--space-10) 0 0',
  fontSize: 'var(--label1-size)',
  color: 'var(--status-negative)',
} as const

/** 저장 상태. `failed` 는 404 와 그 밖을 가른다(404 는 status 로 판정 — 본문 `code` 가 없을 수 있다). */
type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved' }
  | { kind: 'notFound' }
  | { kind: 'failed'; code: AgentErrorCode | null }

export default function AiReportCard({
  report,
  sessionId,
  msgId,
  onPreview,
  onReportSaved,
  pendingExpired = false,
  onReloadPending,
}: {
  report: AiReportBlock
  /** 현재 대화 세션 ID. 없으면 `보고서로 저장` 을 잠근다. */
  sessionId?: string | null
  /** 이 답변의 메시지 ID(D11). 없으면 `보고서로 저장` 을 잠근다. */
  msgId?: string | null
  /** 미리보기 열기 — 페이지의 `useReportPreview().openHtml`. 없으면 `미리보기` 를 잠근다. */
  onPreview?: (title: string, html: string) => void
  /** 저장 성공(생성 201·멱등 200) 알림 — 페이지가 `최근 리포트` 를 재조회한다(Step 7). */
  onReportSaved?: () => void
  /** D4 — 자동 재조회 3분이 끝났는데도 `PENDING` 이다. */
  pendingExpired?: boolean
  /** D4 — 미완성 안내의 `다시 불러오기`(1회 재조회). */
  onReloadPending?: () => void
}) {
  const [save, setSave] = useState<SaveState>({ kind: 'idle' })

  const title = report.title?.trim() || FALLBACK_TITLE
  const done = report.stts === 'DONE' && report.html != null
  const incomplete = report.stts === 'TRUNCATED' || (report.stts === 'PENDING' && pendingExpired)
  const generating = report.stts === 'PENDING' && !pendingExpired

  const canPreview = done && onPreview != null
  const canSave =
    done && sessionId != null && msgId != null && save.kind !== 'saving' && save.kind !== 'saved'

  const handleSave = () => {
    if (!canSave || sessionId == null || msgId == null) return
    setSave({ kind: 'saving' })
    createAiReport(sessionId, { msgId, rptIdx: report.idx })
      .then(() => {
        setSave({ kind: 'saved' })
        onReportSaved?.()
      })
      .catch((e: unknown) => {
        if (axios.isAxiosError(e) && e.response?.status === 404) {
          setSave({ kind: 'notFound' })
        } else {
          setSave({ kind: 'failed', code: agentErrorCode(e) })
        }
      })
  }

  return (
    <div className="cite" style={{ margin: 'var(--space-12) 0' }}>
      <div className="cite__title">
        <span className="cite__mark" aria-hidden="true" />
        {generating ? '보고서 생성 중…' : title}
      </div>

      {generating && (
        <p style={NOTE_STYLE} role="status">
          AI 가 보고서를 마저 쓰고 있어요. 완성되면 자동으로 바뀝니다.
        </p>
      )}

      {incomplete && (
        <p style={NOTE_STYLE} role="status">
          {INCOMPLETE_TEXT}
        </p>
      )}

      {done && <p style={NOTE_STYLE}>미리보기에서 PDF·DOCX 로 내려받을 수 있어요.</p>}

      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-16)' }}>
        {/* 🔴 미완성(`PENDING`·`TRUNCATED`)은 두 버튼을 잠근다(D4). */}
        <button
          type="button"
          className="cite__link"
          disabled={!canPreview}
          onClick={() => {
            if (canPreview && report.html != null) onPreview(title, report.html)
          }}
        >
          미리보기
        </button>
        <button type="button" className="cite__link" disabled={!canSave} onClick={handleSave}>
          {save.kind === 'saving' ? '저장 중…' : save.kind === 'saved' ? '저장됨' : '보고서로 저장'}
        </button>
        {report.stts === 'PENDING' && pendingExpired && onReloadPending && (
          <button type="button" className="cite__link" onClick={onReloadPending}>
            다시 불러오기
          </button>
        )}
      </div>

      {save.kind === 'notFound' && (
        <p style={ERROR_STYLE} role="alert">
          {NOT_FOUND_TEXT}
        </p>
      )}

      {/* 그 밖 실패는 Agent 오류 문구 규칙(`agentErrorCode`)을 그대로 따른다 — 문구 표를 복사하지 않는다. */}
      {save.kind === 'failed' && (
        <AgentErrorNotice
          headline="보고서를 저장하지 못했습니다."
          code={save.code}
          onRetry={handleSave}
          retryLabel="다시 저장"
        />
      )}
    </div>
  )
}
