import ReactMarkdown from 'react-markdown'
import rehypeSanitize from 'rehype-sanitize'
import remarkGfm from 'remark-gfm'
import type { ReactNode } from 'react'
import AiReportCard from './AiReportCard'
import type { AiMessage, AiReportBlock } from '../../api/ai'

/*
 * 말풍선 한 개.
 *
 * 시안의 `.msg` / `.bubble` 구조를 그대로 쓴다 — `user` 는 `.msg--user` + `.bubble--user`(우측),
 * `assistant` 는 아바타(`.av`) + `.bubble`(좌측).
 *
 * 🔴 **assistant 말풍선만 마크다운으로 렌더한다**. 사용자 말풍선은 자기가 친 평문이라
 *    마크다운으로 돌리면 `*`·`#` 같은 기호가 본인이 친 것과 다르게 보인다.
 * 🔴 **`rehype-sanitize` 를 빼지 않는다** — 이 문자열은 우리가 만든 것이 아니라 **외부 서비스가
 *    준 것**이고, 그 서비스에 프롬프트를 넣은 사람이 HTML 을 섞을 수 있다.
 * 🔴 **링크는 새 탭 + `rel` 안전값**(아래 `components.a` 참고) — 우리가 검증한 링크가
 *    아니므로 화면을 벗어나게 하고, 열린 탭이 `window.opener` 로 원래 탭을 조작하지 못하게 막는다.
 * 🔴 원문 HTML 을 그대로 DOM 에 밀어 넣는 React 이스케이프 해제 API 를 직접 쓰지 않는다 —
 *    렌더는 오직 아래 마크다운 파이프라인(sanitize 포함)만 거친다.
 * 🔴 `role` 은 `user` | `assistant` 뿐이다 — `system`·`tool` 은 **백엔드가 이미 걸러서 내리지
 *    않는다**. 여기서 또 거르지 않는다(같은 규칙이 두 곳에 생긴다).
 *
 * **답변 속 HTML 보고서 영역은 카드로 그린다.** 서버가 영역을 자리표시 `[[AI_REPORT:{n}]]` 로
 *    바꾸고 `reports[]` 를 따로 내린다. 여기서는 `content` 를 자리표시로 쪼개 마크다운 조각 사이에
 *    `AiReportCard` 를 끼운다. 🔴 보고서 HTML 은 마크다운 파이프라인을 타지 않는다(`rehype-sanitize` 그대로).
 *    🔴 분할은 **assistant 만** 한다 — user 본문은 서버가 추출하지 않으므로, 사용자가 자리표시를 직접 쳐도
 *    그대로 보여야 한다. 자리표시가 있는데 `reports[n]` 이 없으면(구 응답) 그 자리표시는 숨긴다.
 *    🔴 새 prop 넷(`sessionId`·`msgId`·`onPreview`·`onReportSaved`)은 **모두 선택**이다 — 인사말 호출부
 *    (`ConversationView`)는 `message` 만 넘긴다.
 *
 * ⚠️ 마크다운은 **단일 개행을 줄바꿈으로 치지 않는다.** 실측상 답변이 문단 구분에
 *    빈 줄을 쓰므로 `remark-breaks` 는 넣지 않았다 — 단일 개행이 많아지면 그때 추가한다.
 */

/**
 * AI 아바타. 시안 번들의 장식용 마스코트 일러스트라 색을 그대로 옮겼다.
 * 의미가 없는 그림이므로 `aria-hidden` — 발화 주체는 말풍선 위치로 구분된다.
 *
 * 🔴 **export 하는 이유**: 타이핑 인디케이터 말풍선(`ConversationView`)이
 *    같은 아바타를 써야 답변이 도착했을 때 아바타가 튀지 않는다. SVG 를 복사하면 두 벌이 된다.
 */
export const AiAvatar = (
  <svg viewBox="0 0 36 36" fill="none" width="30" height="30" aria-hidden="true">
    <path d="M18.6 8.4c0-2.6 2.1-4.6 4.7-4.6 0 2.6-2.1 4.6-4.7 4.6Z" fill="#3DA35D" />
    <circle cx="18" cy="21" r="12.5" fill="#F79009" />
    <circle cx="13.6" cy="19.6" r="1.4" fill="#4A3415" opacity="0.85" />
    <circle cx="22.4" cy="19.6" r="1.4" fill="#4A3415" opacity="0.85" />
    <path
      d="M14.9 24.2a3.9 3.9 0 0 0 6.2 0"
      stroke="#4A3415"
      strokeWidth="1.3"
      strokeLinecap="round"
      fill="none"
      opacity="0.85"
    />
  </svg>
)

/** 자리표시 `[[AI_REPORT:{n}]]`. `split` 에 쓰므로 캡처 그룹 하나 — 홀수 칸이 번호다. */
const REPORT_PLACEHOLDER = /\[\[AI_REPORT:(\d+)\]\]/

/** 마크다운 조각 하나 — 종전 말풍선 렌더 그대로. */
function Markdown({ text }: { text: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[rehypeSanitize]}
      components={{
        /*
         * `node`(hast 노드)는 렌더 대상이 아니므로 spread 에서 떼어낸다 — 떼지 않으면
         * DOM `<a>` 에 알 수 없는 속성으로 실려 React 경고가 난다. 그래서 "쓰지 않는 값"
         * 인 것이 의도다.
         */
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        a: ({ node, ...p }) => <a {...p} target="_blank" rel="noopener noreferrer" />,
      }}
    >
      {text}
    </ReactMarkdown>
  )
}

export default function MessageBubble({
  message,
  sessionId,
  msgId,
  onPreview,
  onReportSaved,
  pendingExpired,
  onReloadPending,
}: {
  message: AiMessage
  /** 현재 대화 세션 ID — 보고서 저장 대상. 없으면 카드가 `보고서로 저장` 을 잠근다. */
  sessionId?: string | null
  /** 이 메시지의 ID. 없으면 카드가 `보고서로 저장` 을 잠근다. */
  msgId?: string | null
  /** 보고서 미리보기 열기(페이지의 `useReportPreview().openHtml`). 전달만 한다. */
  onPreview?: (title: string, html: string) => void
  /** 보고서 저장 성공 알림(`최근 리포트` 재조회). 전달만 한다. */
  onReportSaved?: () => void
  /** 페이지의 자동 재조회 3분이 끝났는데도 `PENDING` 이 남았다. 전달만 한다. */
  pendingExpired?: boolean
  /** 미완성 안내의 `다시 불러오기`. 전달만 한다. */
  onReloadPending?: () => void
}) {
  if (message.role === 'user') {
    // 사용자 발화는 평문 그대로. React 가 이스케이프하므로 별도 처리가 필요 없다.
    return (
      <div className="msg msg--user">
        <div className="bubble bubble--user">{message.content}</div>
      </div>
    )
  }

  /**
   * 자리표시로 쪼갠다. `split` 결과는 [본문, 번호, 본문, 번호, …, 본문] — 짝수 칸이 마크다운, 홀수 칸이 번호다.
   * 자리표시가 없으면 종전처럼 마크다운 한 덩어리다.
   */
  function renderAssistant(): ReactNode {
    const parts = message.content.split(new RegExp(REPORT_PLACEHOLDER.source, 'g'))
    if (parts.length === 1) return <Markdown text={message.content} />

    const byIdx = new Map<number, AiReportBlock>((message.reports ?? []).map((r) => [r.idx, r]))
    return parts.map((part, i) => {
      if (i % 2 === 0) {
        return part.trim() ? <Markdown key={i} text={part} /> : null
      }
      const report = byIdx.get(Number(part))
      // 구 응답 — 자리표시는 있는데 영역 정보가 없다. 자리표시 문자열을 노출하지 않고 숨긴다.
      if (!report) return null
      return (
        <AiReportCard
          key={i}
          report={report}
          sessionId={sessionId}
          msgId={msgId}
          onPreview={onPreview}
          onReportSaved={onReportSaved}
          pendingExpired={pendingExpired}
          onReloadPending={onReloadPending}
        />
      )
    })
  }

  return (
    <div className="msg msg--ai">
      <span className="av">{AiAvatar}</span>
      <div className="bubble">{renderAssistant()}</div>
    </div>
  )
}
