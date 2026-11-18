import { useNavigate } from 'react-router-dom'
import type { AgentErrorCode } from '../../api/ai'

/*
 * Agent 오류 안내 블록 + 코드별 문구 표 (`ConversationView` 로부터 분리).
 */

/** 실패 안내 문구의 공통 스타일. `.chat-scroll`·`.recent` 어느 쪽에 놓여도 같게 보인다. */
const NOTICE_STYLE = {
  padding: 'var(--space-16) 0',
  fontSize: 'var(--body2-size)',
  color: 'var(--text-neutral)',
  textAlign: 'center',
} as const

/**
 * Agent 에러 코드별 안내 문구.
 *
 * 🔴 **원인을 단정하는 문구를 쓰지 않는다** — `AGENT_TOKEN_UNAVAILABLE` 의 도달 경로가
 *    mock 로그인만이 아니라 **갱신 실패 후**도 있어, "표준 로그인에서만" 류는 OIDC 로 정상
 *    로그인한 사용자에게 거짓말이 된다.
 */
const AGENT_ERROR_TEXT: Record<AgentErrorCode, string> = {
  AGENT_TOKEN_UNAVAILABLE: '세션이 만료됐거나 인증 토큰이 없습니다. 다시 로그인해 주세요.',
  AGENT_NOT_CONFIGURED: 'AI 서버 연결 설정이 없습니다. 관리자에게 문의하세요.',
  AGENT_UPSTREAM_REJECTED: 'AI 서버가 요청을 거절했습니다. 권한 또는 대상 대화를 확인하세요.',
  /*
   * ⚠️ 실제로 프론트에서는 이 코드에 도달하지 않는다 — `fetchAiSession` 이
   * `encodeURIComponent` 를 쓰므로 `/` 는 `%2F` 가 되어 Tomcat 이 **본문 없는 400** 으로 먼저
   * 끊는다. 즉 그 경우는 `agentErrorCode → null` 로 "그 밖" 분기에 떨어진다. 문구는 남기되
   * 여기로 오기를 기대하지 않는다(정상 조작으로는 안 나온다 — 버그 신호).
   */
  AGENT_BAD_REQUEST: '잘못된 대화 요청입니다.',
  /* 401 — 공용 인터셉터가 `/login` 으로 보내므로 화면은 대개 이 문구를 못 본다. */
  AGENT_UNAUTHORIZED: '세션이 만료됐거나 인증 토큰이 없습니다. 다시 로그인해 주세요.',
  /* 502 — upstream 일시 장애. 유일하게 다시 눌러 볼 값이 있는 코드다. */
  AGENT_UPSTREAM_ERROR: 'AI 서버와 통신하지 못했습니다.',
  /*
   * 502. 🔴 **원인을 단정하지 않는다**: 쿼터 초과일 수도, 인증 만료일 수도 있어
   * (§2-3 H1/H2) `AGENT_RATE_LIMITED`·`AGENT_UNAUTHORIZED` 로 단정하면 틀렸을 때 사용자를
   * 엉뚱한 조치로 보낸다. 우리가 확실히 아는 것은 "답변을 만들지 못했다" 하나뿐이다.
   */
  AGENT_NO_ANSWER:
    'AI 가 답변을 만들지 못했습니다. 다시 로그인하거나 잠시 후 다시 시도해 주세요.',
  /*
   * 409. 서버가 도구 호출(예: 슬라이드 보고서 생성 승인 요청)을 자동으로 한 번 거절했는데
   * 또 도구 호출이 왔다. 다시 보내도 같은 결과라 재시도·재로그인 대신 관리자 확인으로 보낸다.
   */
  AGENT_TOOL_CALL_UNRESOLVED: 'AI 가 처리할 수 없는 작업을 요청했습니다. 관리자에게 문의하세요.',
}

/**
 * `다시 시도` 버튼을 붙이지 않는 코드들.
 *
 * 🔴 눌러도 결과가 같기 때문이다 — 설정이 없거나(NOT_CONFIGURED) upstream 이 거절했거나
 *    (UPSTREAM_REJECTED) 요청 자체가 잘못됐다(BAD_REQUEST). 재시도 버튼을 붙이면 사용자가
 *    같은 실패를 반복하게 된다.
 */
const NO_RETRY_CODES: readonly AgentErrorCode[] = [
  'AGENT_NOT_CONFIGURED',
  'AGENT_UPSTREAM_REJECTED',
  'AGENT_BAD_REQUEST',
  /* 다시 보내도 같은 도구 호출이 온다(서버의 자동 거절은 1회뿐). */
  'AGENT_TOOL_CALL_UNRESOLVED',
  /*
   * 🔴 `AGENT_NO_ANSWER` 는 **여기에 넣지 않는다**. 전송 실패 화면이 이 코드에 다는 버튼은
   *    `다시 보내기` 가 아니라 **`대화 새로고침`**(`retryLabel` prop)이라, 눌러 볼 값이 있다 —
   *    서버 이력을 다시 읽어 `user` 턴이 실제로 쌓였는지 보여 준다.
   */
]

/**
 * 재로그인이 실제 해결 수단인 코드 — 이때만 `다시 로그인` 을 단다.
 *
 * 🔴 `AGENT_NO_ANSWER` 를 **넣지 않는다** — 재로그인이 확실한 해결책이 아니다(쿼터 초과일
 *    수도 있다). 단독으로 `다시 로그인` 을 달면 쿼터 초과 사용자를 무의미한 로그아웃으로 보낸다.
 */
const RELOGIN_CODES: readonly AgentErrorCode[] = ['AGENT_TOKEN_UNAVAILABLE', 'AGENT_UNAUTHORIZED']

/**
 * 실패 안내 블록. **좌측 대화·우측 목록·보고서 카드가 같은 규칙을 써야 하므로 한 곳에 둔다**
 * (`ConversationView`·`RecentSessionList`·`RecentReportList`·`AiReportCard` 가 import 한다).
 * 종전에는 `ConversationView` 가 export 했는데, `AiReportCard` 가 그것을 import 하면
 *    `ConversationView → MessageBubble → AiReportCard → ConversationView` 순환이 생겨 이 파일로 뺐다.
 *
 * 🔴 **버튼 레이블은 고정이 아니다**. 조회 실패는 `다시 시도` 지만 **전송 실패는
 *    `대화 새로고침`** 이다 — `다시 보내기` 를 달면 upstream 에 이미 적재됐을 수 있는 `user` 턴
 *    위에 같은 질문을 한 번 더 쌓는다(판정 불가로 끝나 "적재 됨" 이
 *    기본값이다). 🔴 **두 벌로 만들지 않고** 레이블만 prop 으로 받는다 — 기존 호출부가 깨지지
 *    않도록 **옵셔널 + 기본값**이다.
 *
 * @param headline   무엇을 못 불러왔는지 (좌·우가 다르다)
 * @param code       `agentErrorCode()` 판정 결과. 아는 7종이 아니면 `null` → 재시도 버튼
 * @param onRetry    재시도 동작. 붙이지 않는 코드면 호출되지 않는다
 * @param retryLabel 재시도 버튼 문구 (기본 `다시 시도`)
 */
export function AgentErrorNotice({
  headline,
  code,
  onRetry,
  retryLabel = '다시 시도',
}: {
  headline: string
  code: AgentErrorCode | null
  onRetry: () => void
  retryLabel?: string
}) {
  const navigate = useNavigate()
  const detail = code ? AGENT_ERROR_TEXT[code] : null
  const relogin = code != null && RELOGIN_CODES.includes(code)
  const retryable = code == null || !NO_RETRY_CODES.includes(code)

  return (
    <div style={NOTICE_STYLE} role="alert">
      <div>{detail ?? headline}</div>
      {relogin && (
        <button
          type="button"
          className="btn"
          style={{ marginTop: 'var(--space-12)' }}
          onClick={() => navigate('/login')}
        >
          다시 로그인
        </button>
      )}
      {!relogin && retryable && (
        <button
          type="button"
          className="btn"
          style={{ marginTop: 'var(--space-12)' }}
          onClick={onRetry}
        >
          {retryLabel}
        </button>
      )}
    </div>
  )
}
