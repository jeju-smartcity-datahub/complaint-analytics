import ScrollWrap from './ScrollWrap'
import { AgentErrorNotice } from './AgentErrorNotice'
import { formatDotDateTime } from '../../lib/datetime'
import type { AgentErrorCode, AiSessionItem } from '../../api/ai'

/*
 * 우측 `최근 대화` 카드의 목록.
 *
 * 시안의 `.recent` / `.recent__row` 구조를 그대로 쓰고, 목데이터 대신 실 API
 * (`GET /api/ai/sessions`)의 결과를 그린다.
 *
 * 시안에 없어 이번에 추가한 것 (시안은 정적 목데이터라 실패도 페이징도 없었다)
 *   - 조회 중 / 조회 실패(+ 코드별 안내·버튼) / 빈 목록 상태
 *   - `더 보기` — `nextCursor` 가 있을 때만 하단에 붙는다. 무한 스크롤이 아니다
 *     (`.recent` 가 이미 `max-height:616px` 스크롤 박스다)
 *   - 지금 보고 있는 대화 행에 `is-active`
 *
 * **행마다 삭제 `✕` 를 붙였다.** ✕ 버튼(`.recent__x`)은 **시안에 있던 것**이고
 *    (§2 — 배포본 번들의 `recent__x` 1건) 이번에 옮겼다. 행 요소도 시안대로
 *    `<button>` → `<div role="button" tabIndex={0}>` 로 바꿨다 — 행이 `<button>` 인 채로 안에
 *    ✕ `<button>` 을 넣으면 **중첩 버튼(HTML 위반)** 이다. 구조는 `row > main + x`.
 *    시안에 없어 이번에 추가한 것
 *   - 키보드 `Space` 로도 행 열기(시안은 `Enter` 만) — `<button>` 이던 때의 동작을 잃지 않게
 *   - 전송 중(`deleteDisabled`) 모든 ✕ 잠금 — 전송 중인 대화를 지우면 응답이 지워진 세션에
 *     붙거나 세션이 되살아날 수 있다
 *   - 삭제 확인·실패 안내는 여기서 하지 않는다 — `onDelete` 로 대상만 올리고 페이지가 팝업을 띄운다
 *
 * 목록 순서는 **서버 정렬 그대로** 쓴다 — 프론트에서 다시 정렬하지 않는다.
 */

/** 목록 안 상태 문구(조회 중·빈 목록)의 공통 스타일. */
const NOTICE_STYLE = {
  padding: 'var(--space-16) 0',
  fontSize: 'var(--body2-size)',
  color: 'var(--text-neutral)',
  textAlign: 'center',
} as const

/**
 * 행의 삭제 `✕` 아이콘 (시안 §2 svg 그대로: viewBox 24 · 14×14 · `strokeWidth="2"`).
 *
 * 🔴 `Modal.tsx` 의 `CloseIcon` 을 가져다 쓰지 않는다 — export 되지 않은 지역 상수라 import 할 수 없고,
 *    export 로 바꾸면 이번 범위 밖 파일을 건드린다. 그래서 여기에 직접 선언했다.
 */
const CloseIcon = (
  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" aria-hidden="true">
    <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
  </svg>
)

export default function RecentSessionList({
  sessions,
  loading,
  errorCode,
  failed,
  selectedSessionId,
  hasMore,
  loadingMore,
  moreFailed,
  onSelect,
  onDelete,
  deleteDisabled,
  onLoadMore,
  onRetry,
}: {
  sessions: AiSessionItem[]
  /** 첫 페이지 조회 중. `더 보기` 로 이어붙이는 중은 `loadingMore` 다. */
  loading: boolean
  errorCode: AgentErrorCode | null
  failed: boolean
  selectedSessionId: string | null
  /** `nextCursor` 가 있는지 — `더 보기` 노출 판정. */
  hasMore: boolean
  loadingMore: boolean
  /** `더 보기` 실패. 이미 그린 목록은 그대로 두고 버튼 옆에서만 알린다. */
  moreFailed: boolean
  onSelect: (sessionId: string) => void
  /** 행의 ✕ 클릭 — 삭제 **대상만** 올린다. 확인 팝업·호출은 페이지 몫이다. */
  onDelete: (s: AiSessionItem) => void
  /** 🔴 전송 중 — **모든 행의** ✕ 를 잠근다. `＋ 새 대화` 버튼의 `disabled={sending}` 과 같은 규칙. */
  deleteDisabled: boolean
  onLoadMore: () => void
  onRetry: () => void
}) {
  if (loading) {
    return <div style={NOTICE_STYLE}>불러오는 중…</div>
  }

  if (failed) {
    return (
      <AgentErrorNotice
        headline="최근 대화를 불러오지 못했습니다."
        code={errorCode}
        onRetry={onRetry}
      />
    )
  }

  if (sessions.length === 0) {
    return <div style={NOTICE_STYLE}>최근 대화가 없습니다</div>
  }

  return (
    <>
      <ScrollWrap className="recent">
        {sessions.map((s) => {
          const active = s.sessionId === selectedSessionId
          // 제목 폴백: title → preview → '제목 없음' (둘 다 null 일 수 있다).
          // 🔴 변수 하나로 뽑아 제목과 ✕ 의 `aria-label` 이 **같은 값**을 쓰게 한다.
          const name = s.title ?? s.preview ?? '제목 없음'
          return (
            <div
              key={s.sessionId}
              role="button"
              tabIndex={0}
              className={`recent__row${active ? ' is-active' : ''}`}
              aria-current={active ? 'true' : undefined}
              onClick={() => onSelect(s.sessionId)}
              onKeyDown={(e) => {
                // 🔴 ✕ 버튼에서 난 키 이벤트는 여기까지 버블링된다 — ✕ 의 Enter 가 행 열기로 새지 않게 거른다.
                if (e.target !== e.currentTarget) return
                // `<button>` 이던 때처럼 Enter·Space 둘 다 연다. Space 는 기본 동작(스크롤)을 막는다.
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  onSelect(s.sessionId)
                }
              }}
            >
              <div className="recent__main">
                <div className="recent__name">{name}</div>
                <div className="recent__time">{formatDotDateTime(s.updatedAt)}</div>
              </div>
              <button
                type="button"
                className="recent__x"
                aria-label={`${name} 삭제`}
                title="삭제"
                disabled={deleteDisabled}
                onClick={(e) => {
                  // 🔴 행 `onClick`(대화 열기)으로 번지지 않게 막는다 — 지우려다 그 대화가 열린다.
                  e.stopPropagation()
                  onDelete(s)
                }}
              >
                {CloseIcon}
              </button>
            </div>
          )
        })}
      </ScrollWrap>

      {hasMore && (
        <>
          <button
            type="button"
            className="btn"
            style={{ width: '100%', marginTop: 'var(--space-12)' }}
            disabled={loadingMore}
            onClick={onLoadMore}
          >
            {loadingMore ? '불러오는 중…' : '더 보기'}
          </button>
          {moreFailed && (
            <div
              role="alert"
              style={{
                marginTop: 'var(--space-8)',
                fontSize: 'var(--caption1-size)',
                color: 'var(--status-negative)',
                textAlign: 'center',
              }}
            >
              더 불러오지 못했습니다. 다시 눌러 주세요.
            </div>
          )}
        </>
      )}
    </>
  )
}
