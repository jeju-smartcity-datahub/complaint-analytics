import axios from 'axios'
import { api } from './client'

/*
 * AI 어시스턴트 대화 이력 API 클라이언트.
 *
 * 백엔드 계약:
 *   GET /api/ai/sessions              ?limit(1–100, 기본 20) &cursor  → AiSessionListResponse
 *   GET /api/ai/sessions/{sessionId}  (쿼리 없음)                     → AiSessionDetail
 *   DELETE /api/ai/sessions/{sessionId} (쿼리·바디 없음)              → 204 (본문 없음)
 *
 * 🔴 백엔드가 프록시한다 — 프론트는 Agent API 를 직접 부르지 않는다. 여기서 쓰는 `api`
 *    인스턴스(`./client`)에 세션 쿠키와 401 인터셉터가 붙어 있으므로 **새 axios 인스턴스를
 *    만들지 않는다.**
 * 🔴 `agentId` 는 백엔드 설정값(`app.agent.agent-id`)이다 — 프론트가 지정하지 않는다.
 *    목록 응답에는 표시용으로 실려 온다.
 * 🔴 응답은 camelCase 로 변환돼 온다. 원천 snake_case 대응표는 백엔드 VO 주석이 정본이다.
 * 🔴 `role` 이 `system`·`tool` 인 메시지와 빈 `content` 는 **백엔드가 걸러서 아예 내리지 않는다**.
 * 🔴 `assistant` 본문의 `<think>…</think>` 도 **백엔드가 제거한다** — 프론트에서 다시 걷어내지 않는다.
 *
 * 에러는 본문 `{"code": "..."}` 로 온다. status 와의 대응은 `AgentErrorCode` 주석 참고.
 */

/**
 * 메시지 작성 주체.
 *
 * 🔴 백엔드가 이미 걸러서 내린다 — 유니온에 `system`·`tool` 을 두지 않는다.
 *    두면 "프론트가 또 걸러야 하나?" 로 읽혀 필터가 두 곳에 생긴다.
 */
export type AiMessageRole = 'user' | 'assistant'

/** GET /api/ai/sessions 목록 행. */
export interface AiSessionItem {
  /** 세션 ID. 상세 조회의 경로 변수이자 목록의 유일 키다. */
  sessionId: string
  /** Agent ID. 백엔드 설정값이 표시용으로 실려 온다 — 프론트가 보내는 값이 아니다. */
  agentId: string
  /** Agent 표시명. 원천이 주지 않으면 null. */
  agentName?: string | null
  /** 대화 제목. 없으면 null — 화면이 대체 문구를 정한다. */
  title?: string | null
  /** 미리보기 문구. 없으면 null. */
  preview?: string | null
  /** 생성일시 (ISO-8601 문자열). */
  createdAt: string
  /** 최종 갱신일시 (ISO-8601 문자열). */
  updatedAt: string
}

/** GET /api/ai/sessions 응답. */
export interface AiSessionListResponse {
  /** 세션 목록. 서버가 정렬해 내려주므로 프론트에서 다시 정렬하지 않는다. */
  sessions: AiSessionItem[]
  /** 다음 페이지 커서. 더 없으면 null/생략 — `더 보기` 노출 여부의 판정 입력이다. */
  nextCursor?: string | null
}

/** 대화 메시지 한 건. */
export interface AiMessage {
  /** 작성 주체 — `user` | `assistant` 뿐이다. */
  role: AiMessageRole
  /** 본문. assistant 는 마크다운이 섞여 온다. `<think>` 는 이미 제거돼 있다. */
  content: string
  /** 작성일시 (ISO-8601 문자열). 실측상 실제로 오지만 방어적으로 없을 수 있다. */
  createdAt?: string | null
  /**
   * 메시지 ID — assistant 는 업스트림 `completion_id`(전송 응답은 최상위 `id`, 둘이 같다).
   * user 메시지는 `null`. 🔴 보고서 저장 요청(`createAiReport`)의 대상 키다 — 순번(index)을 대신 쓰지 않는다.
   * 구 응답·로컬 인사말에는 없다(선택).
   */
  msgId?: string | null
  /**
   * 본문 속 HTML 보고서 영역. `content` 의 그 자리에는 자리표시 `[[AI_REPORT:{idx}]]` 가 있다.
   * 🔴 assistant 에만 온다 — user 본문은 서버가 추출하지 않는다(그래서 화면도 assistant 만 분할한다).
   */
  reports?: AiReportBlock[]
}

/**
 * 답변 속 보고서 영역 1건.
 *
 * 🔴 `html` 은 서버가 jsoup 으로 새니타이즈한 값이다. 그래도 말풍선에 직접 그리지 않는다 —
 *    미리보기 모달만 `.rpt-doc` 템플릿으로 감싸 그린다(`reportDownload.buildAiReportHtml`).
 */
export interface AiReportBlock {
  /** 자리표시 `[[AI_REPORT:{idx}]]` 의 번호이자 저장 요청의 `rptIdx`. */
  idx: number
  /** 보고서명(`<title>` → 첫 `<h1>`). 없으면 null — 화면이 대체 문구를 정한다. */
  title: string | null
  /** 새니타이즈된 본문. `DONE` 이 아니면 null. */
  html: string | null
  /** `DONE` 완성 · `PENDING` 생성 중(끝 마커 없음 — 재조회 대상) · `TRUNCATED` 길이 상한 잘림(재조회해도 같다). */
  stts: 'DONE' | 'PENDING' | 'TRUNCATED'
}

/** GET /api/ai/sessions/{sessionId} 응답. */
export interface AiSessionDetail {
  /** 세션 ID. */
  sessionId: string
  /** 대화 메시지 목록(시간순). */
  messages: AiMessage[]
  /** 컨텍스트 토큰 사용량. 원천이 주지 않으면 null. */
  context?: { usedTokens: number; budgetTokens: number; limitTokens: number } | null
}

/**
 * Agent 프록시 에러 코드 8종(`AGENT_TOOL_CALL_UNRESOLVED`).
 *
 * 여기 빠진 code 가 오면 화면이 "그 밖" 분기로 떨어져 **붙이면 안 되는 `다시 시도` 를 붙인다**.
 */
export type AgentErrorCode =
  | 'AGENT_BAD_REQUEST' // 400 — sessionId 오염(정상 조작으로는 안 나온다)
  | 'AGENT_NOT_CONFIGURED' // 503 — 설정 미주입
  | 'AGENT_TOKEN_UNAVAILABLE' // 503 — 세션에 Agent 토큰 없음: mock 로그인 또는 갱신 실패 후
  | 'AGENT_UNAUTHORIZED' // 401 — 인터셉터가 /login 으로 보내 화면은 대개 못 본다
  | 'AGENT_UPSTREAM_REJECTED' // 403/404 — upstream 판정 그대로
  | 'AGENT_UPSTREAM_ERROR' // 502 — 유일한 `다시 시도` 경로
  | 'AGENT_NO_ANSWER' // 502 — AI 가 답변을 만들지 못했다 (원인을 단정하지 않는다)
  | 'AGENT_TOOL_CALL_UNRESOLVED' // 409 — 도구 호출 자동 거절 뒤에도 또 도구 호출. 다시 눌러도 같다 → 관리자 문의

/**
 * 런타임 판정용 코드 목록. 위 유니온과 **한 벌로 유지한다.**
 *
 * 🔴 **판정하는 것은 이 배열이다.** 유니온에만 코드를 추가하면 `agentErrorCode()` 가 `null` 로
 *    접어 화면에 기본 문구가 나가고, `readonly AgentErrorCode[]` 는 전수성을 강제하지 않아
 *    **컴파일도 통과한다**. 유니온을 늘리면 여기도 반드시 함께 늘린다.
 */
const AGENT_ERROR_CODES: readonly AgentErrorCode[] = [
  'AGENT_BAD_REQUEST',
  'AGENT_NOT_CONFIGURED',
  'AGENT_TOKEN_UNAVAILABLE',
  'AGENT_UNAUTHORIZED',
  'AGENT_UPSTREAM_REJECTED',
  'AGENT_UPSTREAM_ERROR',
  'AGENT_NO_ANSWER',
  'AGENT_TOOL_CALL_UNRESOLVED',
]

/**
 * 응답 본문의 `{"code": …}` 를 꺼낸다. 없으면 null(네트워크 오류·비정형 응답).
 *
 * 🔴 **아는 8종이 아니면 null 이다.** 문자열을 그대로 통과시키면 화면이 모르는 코드를 아는 척
 * 분기해 잘못된 안내를 붙인다 — 모르면 "그 밖" 분기로 보내는 것이 옳다.
 */
export function agentErrorCode(e: unknown): AgentErrorCode | null {
  if (!axios.isAxiosError(e)) return null
  const data: unknown = e.response?.data
  if (typeof data !== 'object' || data === null) return null
  const code: unknown = (data as { code?: unknown }).code
  if (typeof code !== 'string') return null
  return AGENT_ERROR_CODES.includes(code as AgentErrorCode) ? (code as AgentErrorCode) : null
}

/**
 * 업스트림이 404 로 거절했는가 — `AGENT_UPSTREAM_REJECTED` 이면서 응답 status 가 404.
 *
 * 🔴 삭제에서 이 경우는 **"이미 지워짐" 으로 보고 성공과 같이 처리한다** — 다른 탭·기기가
 *    먼저 지웠다. 실패로 남기면 목록에 남은 행을 다시 눌러도 계속 실패한다.
 * 🔴 같은 코드의 403 은 성공이 아니다 — code 만 보지 말고 **status 까지 함께 본다**(403·404 가
 *    한 코드로 온다). 페이지가 axios 를 직접 뜯지 않게 판정을 여기 한 곳에 둔다.
 */
export function isUpstreamNotFound(e: unknown): boolean {
  if (agentErrorCode(e) !== 'AGENT_UPSTREAM_REJECTED') return false
  return axios.isAxiosError(e) && e.response?.status === 404
}

/**
 * 세션 목록 조회. 서버 정렬 순서 그대로 사용한다.
 *
 * @param p.limit  1–100 (백엔드 기본 20). 비우면 미전송한다.
 * @param p.cursor 다음 페이지 커서 — 직전 응답의 `nextCursor` 를 그대로 넘긴다(`더 보기`).
 */
export async function fetchAiSessions(p: {
  limit?: number
  cursor?: string
}): Promise<AiSessionListResponse> {
  const params: Record<string, string | number> = {}
  if (p.limit != null) params.limit = p.limit
  if (p.cursor) params.cursor = p.cursor
  const { data } = await api.get<AiSessionListResponse>('/ai/sessions', {
    params: Object.keys(params).length > 0 ? params : undefined,
  })
  return data
}

/**
 * 세션 상세(대화 전문) 조회.
 *
 * 🔴 `agentId` 를 받지 않는다 — 백엔드 설정값이다. 프론트가 Agent 를 고르는 화면이 아니며,
 *    클라이언트가 조회 범위를 정할 수 있으면 그것이 곧 다른 Agent 대화 열람 경로다.
 */
export async function fetchAiSession(sessionId: string): Promise<AiSessionDetail> {
  const { data } = await api.get<AiSessionDetail>(
    `/ai/sessions/${encodeURIComponent(sessionId)}`,
    { timeout: DETAIL_TIMEOUT_MS },
  )
  return data
}

/**
 * 세션 상세 전용 타임아웃. 긴 대화는 첫 조회가 5초를 넘긴다(백엔드 `app.agent.detail-read-timeout-ms`).
 * 🔴 백엔드 값(+ connect 2s)보다 커야 한다 — 작으면 백엔드가 받은 대화를 프론트가 먼저 끊어 버린다.
 * 🔴 전역 10_000 은 올리지 않는다(전송 `CHAT_TIMEOUT_MS` 와 같은 원칙).
 * 값은 백엔드 30s + connect 2s + 여유(2026-11-18 실측 — 대화가 바뀐 뒤 첫 조회 최대 14.5s).
 */
const DETAIL_TIMEOUT_MS = 35_000

/**
 * 세션 삭제. 성공은 204 — 반환값 없음.
 *
 * 🔴 공용 `api` 인스턴스를 쓴다 — DELETE 도 CSRF 대상이라 새 인스턴스면 403 이다.
 * 🔴 `agentId` 를 받지 않는다.
 * ⚠️ 404 는 실패가 아니라 "이미 지워짐" 이다 — 호출부가 `isUpstreamNotFound(e)` 로 가른다.
 */
export async function deleteAiSession(sessionId: string): Promise<void> {
  await api.delete(`/ai/sessions/${encodeURIComponent(sessionId)}`)
}

/** POST /api/ai/messages · POST /api/ai/sessions/{sessionId}/messages 응답. */
export interface AiChatResponse {
  /** 이 전송에 실제로 쓰인 세션 ID. 새 대화면 **백엔드가 만든** `portal-{UUID}` 다. */
  sessionId: string
  /**
   * assistant 답변 1건.
   *
   * ⚠️ **`createdAt` 은 `null` 이다**(2026-09-08 실측 — upstream 이 POST 응답에 시각을 주지 않는다).
   *    조회로 온 메시지는 차 있으므로, 🔴 **이 필드로 정렬하거나 무조건 렌더하면 방금 받은 답변만
   *    비어 정렬이 튄다.** 없으면 생략하는 4121 의 방어적 처리를 그대로 따른다.
   */
  message: AiMessage
}

/**
 * 🔴 전송 전용 타임아웃.
 *
 * 공용 `api` 인스턴스는 `client.ts` 에서 **`timeout: 10_000`** 이다 — 백엔드 전송 타임아웃이
 * 120초(`app.agent.chat-timeout-ms`)인데 프론트가 10초에 먼저 끊으면 **정상 답변이 항상 실패로
 * 보인다.** 🔴 **전역 값을 올리지 않는다**(모든 화면의 실패가 2분씩 느려진다) — 이 호출에만 넘긴다.
 * 값은 백엔드 120s + 여유 5s.
 */
const CHAT_TIMEOUT_MS = 125_000

/**
 * 대화 전송(동기). 응답이 올 때까지 기다린다 — 이번 범위는 스트리밍이 아니다.
 *
 * 🔴 **새 axios 인스턴스를 만들지 않는다** — 전송은 POST 라 `X-XSRF-TOKEN` 이 필요하고,
 *    그 헤더는 공용 `api` 인스턴스가 자동으로 싣는다. 새로 만들면 **403** 이다.
 * 🔴 **세션 ID 를 프론트가 만들지 않는다** — "새 대화" 는 `sessionId` 를 **주지 않는 것**으로
 *    표현하고, 백엔드가 `portal-{UUID}` 를 만들어 응답에 실어 준다.
 *
 * @param p.sessionId 이어쓸 대화. 비우면 **새 대화**(백엔드가 세션을 만든다).
 * @param p.message   보낼 문장. 상한 4000자는 composer 가 `maxLength` 로 막는다.
 */
export async function sendAiMessage(p: {
  sessionId?: string | null
  message: string
}): Promise<AiChatResponse> {
  const url = p.sessionId
    ? `/ai/sessions/${encodeURIComponent(p.sessionId)}/messages`
    : '/ai/messages'
  const { data } = await api.post<AiChatResponse>(
    url,
    { message: p.message },
    { timeout: CHAT_TIMEOUT_MS },
  )
  return data
}

/** POST /api/ai/sessions/{sessionId}/reports 응답. 201 생성 · 200 이미 저장됨. */
export interface AiReportCreateResponse {
  rptpId: number
  rptpNm: string
}

/**
 * 답변 속 보고서 영역을 `AI` 유형 보고서로 저장한다.
 *
 * 🔴 본문을 보내지 않는다 — 서버가 세션 상세를 다시 받아 `msgId`·`rptIdx` 로 영역을 꺼낸다.
 * 🔴 공용 `api` 인스턴스를 쓴다 — POST 라 `X-XSRF-TOKEN` 이 필요하다(새 인스턴스면 403).
 * ⚠️ 같은 요청을 두 번 보내도 새 행이 생기지 않는다(200 + 기존 `rptpId`). 404 는 "그 메시지·영역이 서버에
 *    없다" 이다 — 호출부가 응답 **status** 로 가른다(본문 `code` 가 없을 수 있다).
 */
export async function createAiReport(
  sessionId: string,
  p: { msgId: string; rptIdx: number },
): Promise<AiReportCreateResponse> {
  const { data } = await api.post<AiReportCreateResponse>(
    `/ai/sessions/${encodeURIComponent(sessionId)}/reports`,
    { msgId: p.msgId, rptIdx: p.rptIdx },
  )
  return data
}
