import { useEffect, useRef, useState } from 'react'
import MessageBubble, { AiAvatar } from './MessageBubble'
import { AgentErrorNotice } from './AgentErrorNotice'
import type { AgentErrorCode, AiMessage } from '../../api/ai'

/*
 * 좌측 대화 재생 영역 `.chat-scroll` (TSK-4121 Step 7).
 *
 * 시안은 정적 목데이터라 실패 경로가 없었다 — 조회 중 / 실패 / 비어 있음 3종을 여기서 그린다.
 *
 * 🔴 `system`·`tool` 메시지를 **여기서 거르지 않는다** — 백엔드가 이미 뗐다(D6). 프론트 타입
 *    (`AiMessageRole`)에도 두 값이 없다. 여기서 또 거르면 같은 규칙이 두 곳이 되고 어느 쪽이
 *    정본인지 알 수 없어진다.
 * 🔴 대화를 로드한 뒤 스크롤을 **맨 아래로** 내린다(가장 최근 발화가 보이는 것이 기본 위치다).
 */

/** 실패 안내 문구의 공통 스타일. `.chat-scroll`·`.recent` 어느 쪽에 놓여도 같게 보인다. */
const NOTICE_STYLE = {
  padding: 'var(--space-16) 0',
  fontSize: 'var(--body2-size)',
  color: 'var(--text-neutral)',
  textAlign: 'center',
} as const

/**
 * 새 대화의 인사말 (TSK-4122 Step 7-2 · 시안 §2-1 원문 그대로).
 *
 * 🔴 **로컬 표시 전용이다.** 전송 payload 에 넣지 않는다 — 우리 전송 계약은 `{ "message": "..." }`
 *    **단일 문자열**(Step 2)이라 구조적으로 막혀 있고, 🔴 **배열을 보내는 형태로 바꾸지 않는다**
 *    (바꾸면 서버 대화에 우리가 만든 가짜 assistant 턴이 적재된다).
 * 🔴 **새로고침하면 사라진다 — 버그가 아니다.** 대화의 단일 정본은 Agent API 이고(D3) 서버에는
 *    이 인사말이 없다. 재조회 결과에 안 보이는 것이 정상이다.
 */
const NEW_CHAT_GREETING =
  '안녕하세요! 주차민원 관련 행정 정보 검색, 조례 확인, 민원 요약 등을 도와드립니다.'

/**
 * 타이핑 인디케이터를 "멈춘 화면" 으로 오인하기 시작하는 시점(ms).
 *
 * 🔴 동기 경로라 점 3개가 **최대 120초** 떠 있을 수 있다(D12 의 대가) — 시안의 900ms 감각이 아니다.
 */
const LONG_WAIT_MS = 5_000

/**
 * 전송 중 표시. 🔴 **가짜 진행률 바를 만들지 않는다** — 동기 응답에는 진행 정보가 실제로 없다.
 *    (스트림에는 `llm_progress` 가 실재하므로 진짜 진행 표시는 TSK-4135 의 몫이다.)
 */
function TypingBubble() {
  const [longWait, setLongWait] = useState(false)

  useEffect(() => {
    const t = window.setTimeout(() => setLongWait(true), LONG_WAIT_MS)
    return () => window.clearTimeout(t)
  }, [])

  return (
    <div className="msg msg--ai">
      <span className="av">{AiAvatar}</span>
      <div className="bubble">
        <span className="typing" role="status" aria-label="답변 생성 중">
          <i />
          <i />
          <i />
        </span>
        {longWait && (
          <div
            style={{
              marginTop: 'var(--space-8)',
              fontSize: 'var(--body2-size)',
              color: 'var(--text-neutral)',
            }}
          >
            분석 중입니다… 최대 2분 정도 걸릴 수 있어요.
          </div>
        )}
      </div>
    </div>
  )
}

export default function ConversationView({
  messages,
  loading,
  errorCode,
  failed,
  selected,
  newChat = false,
  onRetry,
  pending = false,
  sendFailed = false,
  sendErrorCode = null,
  onRefresh,
  sessionId = null,
  onPreview,
  onReportSaved,
  pendingExpired = false,
  onReloadPending,
}: {
  /** 대화 메시지. 아직 아무 대화도 고르지 않았으면 `null`. */
  messages: AiMessage[] | null
  loading: boolean
  /** `agentErrorCode()` 판정 결과. `failed` 가 true 일 때만 의미가 있다. */
  errorCode: AgentErrorCode | null
  failed: boolean
  /** 대화를 골랐는지. 빈 상태 문구가 갈린다(고르기 전 / 골랐는데 메시지 0건). */
  selected: boolean
  /**
   * `＋ 새 대화` 로 시작한(또는 진입으로 리셋된) 상태인지 (TSK-4122 Step 7-2).
   *
   * 🔴 **`selected === false` 만으로는 "아직 아무것도 안 골랐다" 와 "방금 새 대화를 열었다" 가
   *    구별되지 않는다.** 구별하지 않으면 `＋ 새 대화` 직후에 `대화를 선택하세요` 가 떠서
   *    방금 만든 새 대화가 "아무것도 안 고른 상태" 로 읽힌다.
   */
  newChat?: boolean
  onRetry: () => void
  /** 전송 중(TSK-4122 Step 5) — 낙관적으로 넣은 사용자 말풍선 **아래**에 타이핑 점을 띄운다. */
  pending?: boolean
  /** 전송 실패. 🔴 사용자 말풍선은 지우지 않고 그 아래에 안내만 붙인다. */
  sendFailed?: boolean
  sendErrorCode?: AgentErrorCode | null
  /** `대화 새로고침` — 서버 이력을 다시 읽는다(중복 적재를 만들지 않는 유일한 복구 수단). */
  onRefresh?: () => void
  /**
   * 현재 대화 세션 ID (TSK-4293) — 답변 속 보고서 카드의 저장 대상. 새 대화 첫 응답 전이면 `null`
   * (카드가 `보고서로 저장` 을 잠근다). 🔴 프론트가 만든 값이 아니다 — 목록·전송 응답이 준 값이다.
   */
  sessionId?: string | null
  /** 보고서 미리보기 열기 — 페이지 한 벌의 `useReportPreview().openHtml`. 말풍선 → 카드로 전달만 한다. */
  onPreview?: (title: string, html: string) => void
  /** 보고서 저장 성공(201·200) 알림 — 페이지가 `최근 리포트` 를 재조회한다(TSK-4293 Step 7). 전달만 한다. */
  onReportSaved?: () => void
  /** D4 — 자동 재조회 3분이 끝났는데도 `PENDING` 보고서가 남았다(카드가 미완성 안내로 바꾼다). */
  pendingExpired?: boolean
  /** D4 — 미완성 안내의 `다시 불러오기`(1회 재조회). */
  onReloadPending?: () => void
}) {
  const scrollRef = useRef<HTMLDivElement>(null)

  /*
   * 로드 후 맨 아래로. `messages` 가 바뀔 때마다 돌며, DOM 이 그려진 뒤 실행되는
   * `useEffect` 라 `scrollHeight` 가 새 내용을 반영한 값이다.
   */
  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollTop = el.scrollHeight
    // 🔴 전송 중·전송 실패도 스크롤을 내린다 — 새로 붙는 것이 목록 맨 아래라 안 내리면 안 보인다.
  }, [messages, pending, sendFailed])

  return (
    <div className="chat-scroll" ref={scrollRef}>
      {loading && <div style={NOTICE_STYLE}>불러오는 중…</div>}

      {!loading && failed && (
        <AgentErrorNotice
          headline="대화를 불러오지 못했습니다."
          code={errorCode}
          onRetry={onRetry}
        />
      )}

      {/*
        🔴 빈 상태는 두 갈래다(Step 7-2). 새 대화면 시안 인사말을 **assistant 말풍선으로** 띄운다 —
           시안이 "메시지 배열을 인사말 1건으로 리셋" 하는 것과 같은 모양이다.
        🔴 인사말은 첫 전송 뒤에도 남긴다(시안 그대로). 답변이 도착하는 순간 사라지면 화면이 튄다.
      */}
      {/*
        ⚠️ **현재 도달 불가 — 방어로 남긴다.** `AiAssistantPage` 의 `newChat` 초기값이 `true` 라
           (D11: 진입은 항상 새 대화) 진입 시엔 아래 인사말로 간다. 이 분기는 나중에 "새 대화가
           아닌 미선택 상태" 가 다시 생겼을 때를 위한 자리다.
      */}
      {!loading && !failed && !selected && !newChat && (
        <div style={NOTICE_STYLE}>대화를 선택하세요</div>
      )}

      {!loading && !failed && newChat && (
        <MessageBubble message={{ role: 'assistant', content: NEW_CHAT_GREETING }} />
      )}

      {!loading && !failed && !newChat && selected && messages != null && messages.length === 0 && (
        <div style={NOTICE_STYLE}>메시지가 없습니다</div>
      )}

      {!loading &&
        !failed &&
        messages?.map((m, i) => (
          // 원천이 메시지 ID 를 주지 않는다(`completion_id` 는 assistant 에만 있다) — 한 세션의
          // 목록은 통째로 교체될 뿐 중간 삽입·정렬이 없으므로 인덱스를 키로 쓴다.
          // 🔴 보고서 저장 대상은 순번 `i` 가 아니라 `msgId` 다(TSK-4293 D11) — 순번을 내리지 않는다.
          <MessageBubble
            key={i}
            message={m}
            msgId={m.msgId}
            sessionId={sessionId}
            onPreview={onPreview}
            onReportSaved={onReportSaved}
            pendingExpired={pendingExpired}
            onReloadPending={onReloadPending}
          />
        ))}

      {!loading && !failed && pending && <TypingBubble />}

      {/*
        🔴 전송 실패는 **대화 조회 실패(`failed`)와 분리**한다 — 조회는 성공했고 방금 보낸 것만
           실패한 상황이라, 이미 그려 둔 대화와 낙관적 사용자 말풍선을 지우면 안 된다.
        🔴 버튼은 `다시 보내기` 가 아니라 `대화 새로고침` 이다(§2-3 #2 · Step 1 ⑤-b).
      */}
      {!loading && !failed && sendFailed && onRefresh && (
        <AgentErrorNotice
          headline="메시지를 보내지 못했습니다."
          code={sendErrorCode}
          onRetry={onRefresh}
          retryLabel="대화 새로고침"
        />
      )}
    </div>
  )
}
