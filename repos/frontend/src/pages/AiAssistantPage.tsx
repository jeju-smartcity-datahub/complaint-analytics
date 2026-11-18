import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import ConfirmDeleteModal from '../components/common/ConfirmDeleteModal'
import TopbarActions from '../components/common/TopbarActions'
import Composer from '../components/ai/Composer'
import ConversationView from '../components/ai/ConversationView'
import RecentReportList from '../components/ai/RecentReportList'
import RecentSessionList from '../components/ai/RecentSessionList'
import { useSatisfactionSurvey } from '../components/ai/useSatisfactionSurvey'
import ReportPreviewModal from '../components/reports/ReportPreviewModal'
import { useReportPreview } from '../lib/report/useReportPreview'
import {
  agentErrorCode,
  deleteAiSession,
  fetchAiSession,
  fetchAiSessions,
  isUpstreamNotFound,
  sendAiMessage,
  type AgentErrorCode,
  type AiMessage,
  type AiSessionItem,
} from '../api/ai'

/*
 * AI 어시스턴트 `/ai-assistant` — **과거 대화 이력 조회**.
 *
 * 레이아웃·DOM 은 시안을 그대로 옮겼고,
 * 데이터만 목데이터 대신 실 API(`src/api/ai.ts` → 백엔드 프록시)에서 받는다.
 * 상단에 breadcrumb 이 없는 것도 시안 그대로다(`h1.page-title` + `p.page-sub` + `TopbarActions`).
 *
 * 구조: `.ai-row` 2단 그리드 = 좌측 `.chat-card`(`.chat-scroll` + `.chat-input`) + 우측 `.ai-side`.
 *
 * **입력창을 활성화했다.** 입력창은 `Composer` 로 분리했고, 전송은
 *    `sendAiMessage`(백엔드 프록시)가 한다. 흐름은 **낙관적 렌더**다: 사용자 말풍선을 즉시 붙이고
 *    → 타이핑 점 → 응답이 오면 답변 말풍선으로 이어붙인다.
 * **`＋ 새 대화` 버튼 + 진입 리셋 + 세션 생성·목록 반영을 붙였다.**
 *    ① `최근 대화` 카드 헤더에 시안(`§2-1`)의 `＋ 새 대화` 버튼을 달았고,
 *    ② `/ai-assistant` 진입은 **기본이 새 대화**다 — `＋ 새 대화` 버튼과 진입이 **같은
 *       `startNewChat()`** 을 부른다(두 벌로 만들면 한쪽만 고쳐진다).
 *       **예외가 하나 생겼다.** 대시보드 `AI 어시스턴트` 카드가
 *       `state.sessionId` 로 **기존 대화를 지목**하면 새 대화가 아니라 그 대화를 연다 —
 *       목록에서 고른 것과 같은 경로(`handleSelectSession`)를 탄다. 🔴 **프론트가 세션 ID 를
 *       만들지 않는다** — 넘어오는 값은 목록 API 가 준 **기존** 세션 ID 뿐이다,
 *    ③ 새 대화의 첫 전송은 **세션 ID 없는 `POST /api/ai/messages`** 로 나가고 응답의
 *       `sessionId`(백엔드가 만든 값)를 받아 그때부터 현재 세션으로 삼는다
 *       — 🔴 **프론트는 세션 ID 를 만들지 않는다**(UUID 생성은 백엔드 몫이다).
 * **진입 계약(`focus`/`prefill`)을 실제로 활성화하고 잠금을 고쳤다.**
 *    진입 처리 잠금이 **첫 마운트 1회**였던 탓에, 이미 이 화면에 있는 상태에서 상단
 *    `AI 대화 시작하기` 를 다시 눌러도 포커스도 새 대화 리셋도 일어나지 않았다.
 *    잠금을 **히스토리 엔트리(`location.key`) 기준**으로 바꿔 재진입에서도 발동한다.
 * 🔴 우측 `최근 리포트` 카드는 **AI 유형 보고서만** 보여 준다 —
 *    이번 범위에서는 그 유형이 0건이라 **항상 빈 상태가 뜨는 것이 정상**이다.
 *    조회·미리보기(UI-PAR-005-03)·PDF/DOCX 다운로드는 카드가 스스로 하므로(`RecentReportList`)
 *    이 페이지는 껍데기만 그린다.
 * **대화 삭제를 붙였다.** `최근 대화` 행의 ✕(시안) → 확인 팝업(`ConfirmDeleteModal`,
 *    시안 문구 `대화를 삭제하시겠습니까?`) → `DELETE /api/ai/sessions/{id}`. 성공·404(이미 지워짐)면
 *    **목록에서 로컬 제거**하고(재조회하지 않는다), 지운 것이 지금 열린 대화면 `startNewChat()`
 *    으로 넘어간다. 실패는 팝업 안에 띄우고 팝업을 닫지 않는다. 전송 중에는 모든 ✕ 를
 *    잠근다.
 *
 * 시안에 없어 이번에 추가한 것 (시안은 정적 목데이터라 실패 경로가 없다)
 *   - 좌·우 각각의 조회 중 / 조회 실패(+ 코드별 안내·버튼) / 빈 상태
 *   - 목록 `더 보기`(`nextCursor` 기반, 무한 스크롤 아님)
 *
 * 🔴 프론트에서 `agentId` 를 지정하지 않는다 — 백엔드 설정값이다.
 */

/** `PENDING` 보고서가 있는 대화의 자동 재조회 간격·상한. */
const PENDING_POLL_INTERVAL_MS = 10_000
const PENDING_POLL_MAX_MS = 180_000

/** 목록 1페이지 크기. `더 보기`로 이어붙인다. */
const PAGE_SIZE = 20

/** 입력창 안내. 시안 원문 그대로다("준비 중" 안내를 되돌렸다). */
const COMPOSER_PLACEHOLDER = '행정 정보를 검색하거나 민원 분석을 요청하세요.'

/**
 * `＋ 새 대화` 버튼의 아이콘 (시안 §2-1 실측 — 14×14 `M12 5v14M5 12h14`).
 *
 * 🔴 레이블은 텍스트 `새 대화` 이고 `＋` 는 **문자가 아니라 SVG** 다. 문자로 쓰면 폰트에 따라
 *    전각 `＋` 가 들쭉날쭉해지고, 시안과 굵기·크기가 어긋난다.
 */
const NewChatIcon = (
  <svg viewBox="0 0 24 24" fill="none" width="14" height="14" aria-hidden="true">
    <path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
  </svg>
)

/**
 * 진입 계약. 세 진입점(대시보드 `AI 어시스턴트` 패널의 입력창·추천 질문·`대화 열기` 링크, 그리고
 * 상단 `AI 대화 시작하기`)이 모두 이 모양의 `location.state` 를 넘긴다 — 키 이름은 통일돼 있고
 * **바꾸지 않았다.**
 *
 * | state | 동작 |
 * | --- | --- |
 * | `focus: true` | 입력창 포커스 |
 * | `prefill: '...'` | 입력창에 **채우되 보내지 않는다** |
 * | `sessionId: '...'` | **기존 대화를 연다**(새 대화가 아니다) |
 *
 * 🔴 **`sessionId` 는 쿼리스트링이 아니라 이 `state` 통로로 받는다** — 이 화면의 진입
 *    정본이 state 이고, 세션 ID 가 주소창에 남아 공유·북마크되는 것도 막는다.
 * 🔴 **프론트가 세션 ID 를 만들지 않는다** — 여기로 들어오는 값은
 *    `GET /api/ai/sessions` 가 준 **기존** 세션 ID 뿐이다. 새 대화는 끝까지 "`sessionId` 를
 *    주지 않는 것" 으로 표현한다.
 *
 * 🔴 **`submit`(채우고 즉시 전송)은 없다**(범위 밖 #8) — 그것을 보내는 진입점이 하나도 없어,
 *    새로고침 재전송·StrictMode 이중 실행 가드를 **검증도 못 한 채** 지고 가게 된다.
 *    필요해지는 진입점과 같이 만든다.
 */
type AiEntryState = { focus?: boolean; prefill?: string; sessionId?: string }

export default function AiAssistantPage() {
  const location = useLocation()
  const navigate = useNavigate()

  /* ── 진입 state(`focus` / `prefill`) ───────────── */
  /** 입력창의 값. 진입 `prefill` 로 초기값이 들어오고, 전송 실패 시 되돌려 놓는다. */
  const [input, setInput] = useState('')
  /**
   * 🔴 **진입 처리 잠금 — "마운트 1회" 가 아니라 "진입 이벤트 1회" 다**.
   *
   * 종전에는 `useRef(false)` 한 개로 **첫 마운트 1회**만 처리했다. 그래서 이미 `/ai-assistant`
   * 에 있는 상태에서 상단 `AI 대화 시작하기` 를 다시 눌러도 컴포넌트가 다시 마운트되지 않아
   * **아무 일도 일어나지 않았다** — `focus` 도, 붙인 **새 대화 리셋도**.
   * 종전에는 입력창이 `disabled` 라 무해했지만 활성화한 지금은 "버튼을 눌렀는데
   * 커서가 안 간다" 가 된다.
   *
   * 그래서 잠금을 **히스토리 엔트리 키(`location.key`)** 기준으로 바꿨다. react-router 는
   * `navigate()` 한 번마다 새 엔트리(=새 `key`)를 만들므로, **같은 경로로 다시 navigate 하면
   * 키가 달라져 재발동**한다. 별도의 nonce 를 state 에 심지 않아도 되므로 진입점 두 곳
   * (`AiAssistantPanel`·`TopbarActions`)의 **state 계약을 건드리지 않는다**(키 이름은 종전에
   * 통일한 `focus`/`prefill` 그대로다).
   *
   * 🔴 두 개인 이유 — 진입 state 가 **있는** 진입과 **없는** 진입의 판정이 다르다.
   *   - `handledEntryKeyRef`: state 를 실은 진입(대시보드 추천 질문 · 상단 버튼). 같은 키를 두 번
   *     처리하지 않으므로 **StrictMode 이중 실행에서도 1회**다(ref 는 이중 마운트에서 유지된다).
   *   - `plainEntryHandledRef`: state 없는 진입(사이드바 메뉴 · URL 직접). 이건 마운트 1회가 맞다.
   *     🔴 그리고 **진입 직후의 state 비우기(`navigate(..., {state: null})`)가 만든 재실행을
   *     삼키는 것도 이 값이다** — 그 재실행은 state 가 없어 "state 없는 진입" 과 구분되지 않으므로,
   *     state 를 실은 진입을 처리할 때도 이 값을 함께 올려 둔다. 안 그러면 방금 채운 `prefill` 이
   *     곧바로 `startNewChat()` 에 지워진다.
   */
  const handledEntryKeyRef = useRef<string | null>(null)
  const plainEntryHandledRef = useRef(false)
  /** 🔴 입력창은 이제 `Composer` 가 소유한다 — `forwardRef` 로 `input` 엘리먼트까지 닿는다. */
  const composerRef = useRef<HTMLTextAreaElement>(null)

  // 🔽 진입 state 처리 effect 는 `startNewChat` · `handleSelectSession` 정의 **뒤**에 있다
  //    (둘 다 부르기 때문이다 — `handleSelectSession` 을 그 앞으로 올렸다).

  /* ── 우측: 세션 목록 ─────────────────────────── */
  const [sessions, setSessions] = useState<AiSessionItem[]>([])
  const [listLoading, setListLoading] = useState(true)
  const [listFailed, setListFailed] = useState(false)
  const [listErrorCode, setListErrorCode] = useState<AgentErrorCode | null>(null)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  /** `더 보기` 실패. 🔴 첫 페이지 실패(`listFailed`)와 **분리한다** — 이미 그린 목록은 지우지 않고
   *  `더 보기` 자리에서만 알려야 사용자가 보던 것을 잃지 않는다. */
  const [moreFailed, setMoreFailed] = useState(false)
  /** `다시 시도` 로 첫 페이지를 재조회하기 위한 트리거(`ReportsPage` 와 같은 수법). */
  const [listReloadKey, setListReloadKey] = useState(0)

  /* ── 대화 삭제 ─────────────────────── */
  /** 확인 팝업의 대상. 🔴 `null` 이 아니면 팝업이 **마운트된다** — `ConfirmDeleteModal` 에는 `open` prop 이 없다. */
  const [deleteTarget, setDeleteTarget] = useState<AiSessionItem | null>(null)
  /** 삭제 요청 중 — 팝업의 두 버튼을 잠그고, 취소·Esc·오버레이 닫기를 무시한다. */
  const [deleting, setDeleting] = useState(false)
  /** 팝업 안의 실패 문구(원인을 단정하지 않는다). */
  const [deleteError, setDeleteError] = useState<string | null>(null)
  /**
   * 이 화면에서 지운 세션 ID. 🔴 **목록 응답이 지운 행을 되살리지 못하게** 거르는 표다.
   *
   * 다른 대화의 전송이 성공하면 기존 동작대로 `listReloadKey` 를 올려 첫 페이지를 다시 받는데, 그 조회가
   * 삭제 완료 **전에** 나갔으면 응답에 지운 행이 그대로 들어 있다. `더 보기` 응답도 같다. 그래서 목록을
   * 반영하는 모든 자리(첫 페이지 effect · `loadMore` 이어붙이기)에서 이 표로 거른다.
   * 세션 ID 는 백엔드가 `portal-{UUID}` 로 만들어 재사용되지 않으므로 페이지 수명 동안 유지해도 된다.
   */
  const deletedIdsRef = useRef<Set<string>>(new Set())

  /* ── 좌측: 선택한 대화 ───────────────────────── */
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null)
  const [messages, setMessages] = useState<AiMessage[] | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailFailed, setDetailFailed] = useState(false)
  const [detailErrorCode, setDetailErrorCode] = useState<AgentErrorCode | null>(null)
  const [detailReloadKey, setDetailReloadKey] = useState(0)
  /**
   * 🔴 **조용한 재조회** 표식 — 값이 지금 선택한 세션 ID 와 같으면 다음 상세 조회 1회는
   *    `불러오는 중…` 을 띄우지 않고 대화를 비우지 않는다(실패해도 이미 그린 대화를 지우지 않는다).
   *    `PENDING` 보고서 자동 재조회·`다시 불러오기` 가 쓴다. 상세 조회 effect·`detailReloadKey` 는 그대로 재사용한다.
   *    세션 ID 로 두는 이유: 표식을 세운 뒤 다른 대화를 고르면 그 대화의 조회까지 조용해지면 안 된다.
   */
  const silentReloadSessionRef = useRef<string | null>(null)
  /**
   * 지금 화면에 떠 있는 대화. 🔴 **전송 응답이 늦게 도착했을 때 "그 사이에 다른 대화로 옮겼는지"**
   * 를 판정하는 입력이다 — 동기 왕복이 최대 120초라 그 사이 목록에서 다른 대화를 고를 수 있고,
   * 그때 응답을 그대로 붙이면 **남의 대화에 답변이 끼어든다.**
   */
  const selectedSessionRef = useRef<string | null>(null)
  useEffect(() => {
    selectedSessionRef.current = selectedSessionId
  }, [selectedSessionId])
  /**
   * `＋ 새 대화`(또는 진입 리셋)로 시작한 상태인지.
   *
   * 🔴 **`selectedSessionId == null` 만으로는 갈리지 않는다** — "아직 아무것도 안 골랐다" 와
   *    "방금 새 대화를 열었다" 가 같은 값이 되어 새 대화에 `대화를 선택하세요` 가 뜬다.
   * 🔴 **첫 전송으로 세션이 생긴 뒤에도 `true` 로 둔다** — 시안이 인사말을 대화 내내 남겨 두고,
   *    답변이 도착하는 순간 인사말이 사라지면 화면이 튄다. 목록에서 다른 대화를 고를 때 내려간다.
   * 🔴 초기값이 `true` 인 이유: 진입은 **기본이 새 대화**다(`state.sessionId`
   *    예외가 생겼지만 그쪽은 effect 가 곧 `false` 로 내린다). `false` 로 두면 아래 진입 effect 가
   *    도는 첫 페인트 한 프레임 동안 `대화를 선택하세요` 가 깜빡인다.
   */
  const [newChat, setNewChat] = useState(true)
  /**
   * 방금 전송으로 **막 만들어진** 세션 ID. 🔴 상세 재조회를 **한 번** 건너뛰기 위한 표식이다.
   *
   * 새 대화의 첫 전송이 성공하면 `selectedSessionId` 가 채워지는데, 그러면 상세 조회 effect 가
   * 곧바로 돌아 방금 그린 말풍선을 지우고 `불러오는 중…` 을 띄운다 — **이미 갖고 있는 내용을
   * 다시 받으려고 화면을 비우는** 셈이다. 표식이 있으면 그 1회만 건너뛴다.
   * (`대화 새로고침`·다른 대화 선택은 표식이 이미 비워져 있어 정상적으로 조회한다.)
   *
   * ⚠️ **dev 에서는 이 표식이 무력화된다** — `main.tsx` 가 `StrictMode` 라 effect 가 두 번 돌고,
   *    첫 회가 표식을 소비해 두 번째가 그대로 조회한다. 즉 **dev 에서 `불러오는 중…` 이 스쳐
   *    지나가는 것은 회귀가 아니다.** prod(1회 실행)에서는 건너뛴다. 어느 쪽이든 결과는 같다 —
   *    목록의 `title`/`preview` 는 재조회로 서버값을 쓰고, 본문은 전송 응답 원본 그대로다.
   */
  const justCreatedSessionRef = useRef<string | null>(null)
  /**
   * 대화 "세대". 🔴 **전송이 나간 뒤 그 대화가 리셋됐는지**를 판정하는 값이다(`startNewChat` 이 올린다).
   *
   * `＋ 새 대화` 버튼을 `disabled={sending}` 로 막아 이 경우를 피했다. 그런데
   * **상단 `AI 대화 시작하기` 재클릭도 리셋**으로 만들면서 그 버튼은 전송 중에도 눌린다.
   * 세션 ID 만으로는 갈리지 않는다 — **새 대화에서 보낸 전송**은 대상도 `null`, 리셋 후 현재 세션도
   * `null` 이라 `selectedSessionRef` 비교를 그대로 통과해, 리셋으로 비운 화면에 **질문 없이 답변만**
   * 붙고 실패 안내까지 따라 들어온다. 세대가 다르면 그 응답은 "지금 화면" 의 것이 아니다.
   *
   * ⚠️ 남는 대가: 리셋 직후에도 **타이핑 점은 응답이 올 때까지 새 화면에 남는다**(`sending` 이
   *    reactive 판정이라 그렇다). 요청을 취소할 방법이 없어 잠금은 유지해야 하고, 점은 응답이
   *    도착하면 사라진다 — 잘못된 내용이 굳는 문제와 달리 스스로 걷힌다.
   */
  const chatEpochRef = useRef(0)

  /* ── 전송 ──────────────────── */
  /** 전송 중. 🔴 입력·버튼 잠금과 타이핑 점의 단일 판정 입력이다(Enter 연타 = 요청 폭주 방지). */
  const [sending, setSending] = useState(false)
  /**
   * 전송이 나간 **대상 세션**(새 대화면 `null`).
   *
   * 🔴 타이핑 점을 **보낸 대화에서만** 띄우기 위한 값이다. `sending` 하나로 판정하면
   *    전송 중에 목록에서 다른 대화를 열었을 때 **그 대화에 남의 타이핑 점**이 뜬다.
   */
  const [pendingSessionId, setPendingSessionId] = useState<string | null>(null)
  /** 전송 실패. 🔴 조회 실패(`detailFailed`)와 **분리한다** — 이미 그린 대화를 지우면 안 된다. */
  const [sendFailed, setSendFailed] = useState(false)
  const [sendErrorCode, setSendErrorCode] = useState<AgentErrorCode | null>(null)
  /** 조용한 재조회의 늦은 응답 판정용 — effect 의존성에 `sending` 을 넣지 않으려고 ref 로 둔다. */
  const sendingRef = useRef(false)
  useEffect(() => {
    sendingRef.current = sending
  }, [sending])

  /* ── 답변 속 보고서 ─────────── */
  /**
   * 미리보기 상태 **한 벌** — 카드는 `openHtml` 콜백만 받는다. 모달은 portal 이 아니라서 대화 스크롤
   * 영역(`.chat-scroll`) 밖 형제로 렌더한다(`RecentReportList` 와 같은 규칙). 말풍선마다 두면 갇힌다.
   */
  const previewState = useReportPreview()

  /* ── 만족도 설문 ─────────── */
  /**
   * 노출 규칙은 훅 한 곳이 진다(답변 3회째 · 서버 `eligible` · 미룸). 전송 중이거나 다른 팝업(보고서 미리보기·
   * 삭제 확인)이 열려 있으면 기다렸다 연다 — 모달을 겹쳐 띄우지 않는다.
   */
  const { onAnswerReceived: onSurveyAnswer, surveyElement } = useSatisfactionSurvey({
    blocked: sending || deleteTarget != null || !!previewState.title,
    sesnId: selectedSessionId ?? pendingSessionId,
  })
  /** 현재 대화에 `PENDING`(생성 중) 보고서가 하나라도 있는가 — 자동 재조회의 켜짐 조건. */
  const hasPendingReport = useMemo(
    () => messages?.some((m) => m.reports?.some((r) => r.stts === 'PENDING')) ?? false,
    [messages],
  )
  /** 자동 재조회 시작 시각(세션별). 세션이 바뀌거나 `PENDING` 이 사라지면 비운다. */
  const pendingPollRef = useRef<{ sessionId: string; startedAt: number } | null>(null)
  /** 3분 상한이 지났는데도 `PENDING` — 카드가 미완성 안내 + `다시 불러오기` 로 바뀐다. */
  const [pendingExpired, setPendingExpired] = useState(false)
  /**
   * `최근 리포트` 재조회 신호. 카드(`AiReportCard`)와 목록(`RecentReportList`)은 형제라
   * 페이지가 잇는다 — 저장 성공(201·200)마다 올리고, 목록은 자체 조회를 유지한 채 이 값이 바뀌면 다시 읽는다.
   */
  const [reportReloadKey, setReportReloadKey] = useState(0)
  const handleReportSaved = useCallback(() => setReportReloadKey((k) => k + 1), [])

  // 첫 페이지 조회. `다시 시도`(reloadKey)로도 돈다.
  useEffect(() => {
    let cancelled = false
    setListLoading(true)
    setListFailed(false)
    setListErrorCode(null)

    fetchAiSessions({ limit: PAGE_SIZE })
      .then((res) => {
        if (cancelled) return
        // 🔴 지운 행이 늦게 나간 조회로 되살아나지 않게 거른다(`deletedIdsRef` 주석).
        setSessions(res.sessions.filter((s) => !deletedIdsRef.current.has(s.sessionId)))
        setNextCursor(res.nextCursor ?? null)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        setSessions([])
        setNextCursor(null)
        setListFailed(true)
        setListErrorCode(agentErrorCode(e))
      })
      .finally(() => {
        if (!cancelled) setListLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [listReloadKey])

  // 선택한 대화 전문 조회. 선택이 없으면 아무것도 하지 않는다(좌측은 "대화를 선택하세요").
  useEffect(() => {
    if (!selectedSessionId) return
    /*
     * 🔴 방금 전송으로 만들어진 세션이면 **이번 1회는 조회하지 않는다**.
     *    이미 화면에 있는 것(사용자 질문 + 방금 받은 답변)을 다시 받으려고 대화를 통째로 비우면
     *    `불러오는 중…` 이 끼어들어 답변이 사라졌다 다시 나타난다. 표식은 즉시 소모한다 —
     *    `대화 새로고침`(detailReloadKey)·다른 대화 선택은 정상적으로 조회돼야 한다.
     */
    if (justCreatedSessionRef.current === selectedSessionId) {
      justCreatedSessionRef.current = null
      return
    }

    // 표식은 즉시 소모한다 — 다음 조회(다른 대화 선택·`대화 새로고침`)는 종전대로 화면을 비운다.
    const silent = silentReloadSessionRef.current === selectedSessionId
    silentReloadSessionRef.current = null

    let cancelled = false
    if (!silent) {
      setDetailLoading(true)
      setDetailFailed(false)
      setDetailErrorCode(null)
      // 앞 대화를 비운다 — 남겨두면 조회 중에 다른 대화의 말풍선이 그대로 보인다.
      setMessages(null)
      // 앞 대화에서 난 전송 실패 안내도 함께 지운다(다른 대화의 실패가 따라다니면 안 된다).
      setSendFailed(false)
      setSendErrorCode(null)
    }

    fetchAiSession(selectedSessionId)
      .then((res) => {
        if (cancelled) return
        // 🔴 조용한 재조회 중에 전송이 시작됐으면 버린다 — 낙관적으로 붙인 사용자 말풍선을 덮어쓴다.
        if (silent && sendingRef.current) return
        setMessages(res.messages)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        // 조용한 재조회 실패는 화면을 건드리지 않는다 — 다음 주기가 다시 시도한다.
        if (silent) return
        setMessages(null)
        setDetailFailed(true)
        setDetailErrorCode(agentErrorCode(e))
      })
      .finally(() => {
        if (!cancelled && !silent) setDetailLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [selectedSessionId, detailReloadKey])

  /**
   * 🔴 **새 대화 리셋 — 버튼과 진입이 함께 쓰는 단 하나의 함수다**.
   *
   * `＋ 새 대화` 버튼 · `/ai-assistant` 진입이 **같은 함수**를 부른다. 두 벌로 만들면 한쪽만
   * 고쳐져 "버튼으로는 리셋되는데 메뉴로 들어오면 안 된다" 같은 갈림이 생긴다.
   *
   * 하는 일: 세션 선택 해제 + 대화 배열 리셋 + 입력창 비움 + 앞 대화의 실패 안내 제거.
   * 🔴 **확인 다이얼로그 없이 즉시 리셋한다**(시안 그대로). 대화는 서버에 남아 있어 목록에서
   *    다시 열 수 있으므로 파괴적 동작이 아니다. ⚠️ 다만 **아직 보내지 않은 입력글은 사라진다.**
   * 🔴 세션 ID 를 여기서 만들지 않는다 — 새 대화는 "`sessionId` 를 주지 않는 것" 으로
   *    표현하고, 첫 전송의 응답으로 백엔드가 만든 세션 ID 를 받는다.
   */
  const startNewChat = useCallback(() => {
    // 🔴 전송 중 리셋에 대비해 세대를 올린다 (아래 `chatEpochRef` 주석 참고).
    chatEpochRef.current += 1
    setSelectedSessionId(null)
    setNewChat(true)
    setMessages(null)
    setInput('')
    setDetailLoading(false)
    setDetailFailed(false)
    setDetailErrorCode(null)
    setSendFailed(false)
    setSendErrorCode(null)
    /*
     * 🔴 진행 중이던 전송을 **화면에서 놓아준다**(2026-09-08 E2E 지적).
     *    안 하면 비워진 새 대화에 **질문 없이 타이핑 점만** 남아 "무엇을 기다리는지 모르는" 화면이 된다
     *    — 그 점이 가리키던 질문은 방금 리셋으로 사라졌다.
     *    ⚠️ 요청 자체는 취소하지 못한다(취소 API 가 없다). 응답이 와도 epoch 이 달라
     *    화면에는 붙지 않고, 목록 갱신만 수행돼 **그 세션은 `최근 대화` 에 정상적으로 나타난다.**
     *    입력창도 함께 풀어 새 질문을 바로 쓸 수 있게 한다.
     */
    setSending(false)
    setPendingSessionId(null)
  }, [])

  /**
   * 목록에서 대화 1건 선택. 🔴 **`setSelectedSessionId` 를 그대로 넘기지 않는다** — 새 대화
   * 표식(`newChat`)을 함께 내려야 인사말이 남의 대화 위에 계속 붙어 있지 않는다.
   *
   * **아래 진입 effect 보다 앞으로 올렸다.** 대시보드 AI 카드가 준
   *    `state.sessionId` 를 그 effect 가 이 함수로 처리하기 때문이다(분기를 두 벌로 만들지 않는다).
   */
  const handleSelectSession = useCallback((sessionId: string) => {
    setNewChat(false)
    setSelectedSessionId(sessionId)
  }, [])

  /**
   * `PENDING` 보고서 자동 재조회(10초 간격 · 최대 3분). 카드가 아니라 여기서 한다.
   *
   * 종료 조건: ① `PENDING` 이 사라짐(완성·잘림 확정) ② 다른 대화로 옮김(시작 시각을 새로 잡는다)
   * ③ 시작 후 3분 경과 → `pendingExpired` 를 켜고 멈춘다(카드가 `다시 불러오기` 를 띄운다).
   * 전송 중·조회 실패 화면에서는 쉰다. 매 재조회가 `detailReloadKey` 를 올리므로 그 값이 바뀔 때마다 다음 주기를 건다.
   */
  useEffect(() => {
    if (!hasPendingReport || selectedSessionId == null) {
      pendingPollRef.current = null
      setPendingExpired(false)
      return
    }
    let poll = pendingPollRef.current
    if (poll?.sessionId !== selectedSessionId) {
      poll = { sessionId: selectedSessionId, startedAt: Date.now() }
      pendingPollRef.current = poll
      setPendingExpired(false)
    }
    if (sending || detailFailed) return

    const elapsed = Date.now() - poll.startedAt
    if (elapsed >= PENDING_POLL_MAX_MS) {
      setPendingExpired(true)
      return
    }
    const target = selectedSessionId
    const t = window.setTimeout(
      () => {
        silentReloadSessionRef.current = target
        setDetailReloadKey((k) => k + 1)
      },
      Math.min(PENDING_POLL_INTERVAL_MS, PENDING_POLL_MAX_MS - elapsed),
    )
    return () => window.clearTimeout(t)
  }, [hasPendingReport, selectedSessionId, sending, detailFailed, detailReloadKey])

  /** 미완성 안내의 `다시 불러오기`: 조용한 재조회 1회. 상한이 지난 뒤라 자동 주기는 다시 켜지 않는다. */
  const reloadPendingReports = useCallback(() => {
    if (selectedSessionRef.current == null) return
    silentReloadSessionRef.current = selectedSessionRef.current
    setDetailReloadKey((k) => k + 1)
  }, [])

  /* ── 진입 state(`focus` / `prefill` / `sessionId`) 처리 ─────── */
  /*
   * 진입 계약. `focus: true` → 입력창 포커스, `prefill: '...'` → 입력창에 **채우기만**.
   * 🔴 **채운 문장을 자동으로 보내지 않는다**(`submit` 진입 계약은 만들지 않는다 — 범위 밖 #8).
   *    그것을 보내는 진입점이 하나도 없어, 부르는 곳 없는 재전송 가드를 검증도 못 한 채 지게 된다.
   * 🔴 **재진입에서도 돈다** — 위 두 ref 가 잠금을 "마운트" 가 아니라 "히스토리 엔트리" 로 잡는다.
   * **`sessionId: '...'` → 그 기존 대화를 연다**(새 대화가 아니다).
   *    대시보드 `AI 어시스턴트` 카드의 최근 대화 항목이 유일한 진입점이며, 🔴 **프론트가 세션 ID 를
   *    만들지 않는다** — 목록 API 가 준 값만 흘러 들어온다.
   */
  useEffect(() => {
    const entry = location.state as AiEntryState | null

    if (entry) {
      // state 를 실은 진입 — **키가 바뀌면 다시 처리한다**(같은 화면에서 재클릭 = 새 엔트리).
      if (handledEntryKeyRef.current === location.key) return
      handledEntryKeyRef.current = location.key
    } else if (plainEntryHandledRef.current) {
      // state 없는 진입은 마운트 1회. 진입 직후 state 를 비우며 생긴 재실행도 여기서 멈춘다.
      return
    }
    plainEntryHandledRef.current = true

    // 🔴 진입은 기본이 **새 대화**다. 예외는 하나 — 대시보드 AI 카드가 기존 대화를 지목한
    //    경우다. 그때는 목록에서 고른 것과 같은 경로를 탄다.
    if (entry?.sessionId) {
      handleSelectSession(entry.sessionId)
    } else {
      startNewChat()
    }
    if (!entry) return

    /*
     * 🔴 순서: `startNewChat()` 이 입력창을 비우므로 `prefill` 복사는 **그 뒤**여야 한다 —
     *    뒤집히면 미리 채운 문장이 즉시 지워진다.
     * 🔴 비우기는 `navigate(..., { state: null })` 로 한다(4121 과 동일) — `history.replaceState` 를
     *    직접 쓰면 react-router v6 가 쓰는 `{usr, key, idx}` 까지 지워져 뒤로가기·스크롤 복원이
     *    흔들린다. 새로고침 때 `prefill` 이 되살아나지 않게 하는 것이 목적이다.
     */
    if (typeof entry.prefill === 'string') setInput(entry.prefill)
    // ✅ 입력창은 이제 항상 활성이다(`locked` 제거) — 포커스가 그대로 먹는다.
    //    (전송 중에만 `disabled` 라 그 순간의 포커스는 무시된다 — 전송이 끝나면 다시 쓸 수 있다.)
    if (entry.focus) composerRef.current?.focus()

    navigate(location.pathname, { replace: true, state: null })
  }, [
    location.key,
    location.pathname,
    location.state,
    navigate,
    startNewChat,
    handleSelectSession,
  ])

  /**
   * 전송 실패 안내의 `대화 새로고침` — **서버 이력을 다시 읽는다**(재전송이 아니다).
   *
   * 🔴 목록도 함께 다시 읽는 이유: **새 대화의 첫 전송이 실패하면 아직 고른 세션이 없어**
   *    대화 전문 재조회가 아무 일도 하지 않는다. 그런데 실패 종류에 따라 upstream 에는 세션이
   *    생겼을 수 있으므로(판정 불가), 목록을 다시 읽으면 그 세션이 드러나 사용자가
   *    직접 열어 확인할 수 있다. 버튼이 "눌러도 아무 일이 없는" 상태로 남지 않게 하는 장치다.
   *
   * ⚠️ **대가**: 목록 재조회는 1페이지부터 다시 받으므로 `더 보기` 로 쌓아 둔 페이지가 **접힌다**
   *    (`nextCursor` 도 초기화된다). 전송 **실패** 경로 한정이고, 그 자리에서 "세션이 생겼는가" 를
   *    확인시켜 주는 이득이 더 크다고 보아 감수한다 — 사용자는 `더 보기` 를 다시 누르면 된다.
   */
  const handleRefreshConversation = useCallback(() => {
    setListReloadKey((k) => k + 1)
    setDetailReloadKey((k) => k + 1)
  }, [])

  /**
   * 전송 1회.
   *
   * 🔴 **낙관적 렌더** — 응답을 기다리지 않고 사용자 말풍선을 먼저 붙인다. 동기 경로라 왕복이
   *    최대 120초인데 그동안 자기가 친 문장이 화면에 없으면 "안 눌렸다" 로 읽힌다.
   * 🔴 **실패해도 그 말풍선을 지우지 않는다** — 지우면 사용자가 쓴 문장이 사라진다. 대신 입력글을
   *    composer 로 되돌려 사용자가 직접 판단하게 하고, 아래에 `대화 새로고침` 을 단다.
   * 🔴 **`createdAt` 을 만들어 넣지 않는다** — 전송 응답의 `message.createdAt` 은 `null` 이고
   *    (2026-09-08 실측) 조회분은 차 있다. 여기서 `new Date()` 로 채우면 서버 시각과 다른 값이
   *    섞이고, 재조회 후 값이 바뀐다. 없으면 생략하는 4121 의 방어적 처리를 그대로 따른다.
   * 🔴 **중복 전송 차단은 여기서도 한 번 더 한다** — 버튼 `disabled` 만으로는 경합을 못 막는다.
   */
  const handleSend = useCallback(() => {
    const text = input.trim()
    if (!text || sending) return

    setSending(true)
    setPendingSessionId(selectedSessionId)
    setSendFailed(false)
    setSendErrorCode(null)
    setMessages((prev) => [...(prev ?? []), { role: 'user', content: text }])
    setInput('')

    // 🔴 `null` 이면 **새 대화**다 — `sendAiMessage` 가 세션 ID 없는 `POST /api/ai/messages` 로
    //    보내고, 백엔드가 세션 ID(UUID)를 만들어 응답에 실어 준다.
    const target = selectedSessionId
    // 이 전송이 속한 세대. 응답이 돌아왔을 때 그 사이 새 대화로 리셋됐는지 가른다.
    const epoch = chatEpochRef.current
    sendAiMessage({ sessionId: target, message: text })
      .then((res) => {
        /*
         * 🔴 목록 재조회는 **stale 판정보다 먼저** 한다 — 화면 붙이기와 목록 갱신은 서로 다른
         *    관심사다. "전송이 성공했다" 는 사실은 사용자가 지금 어느 대화를 보고 있든 참이므로,
         *    stale 이라고 목록까지 건너뛰면 **새 대화의 세션이 서버에는 있는데 목록에만 없는**
         *    상태가 된다(전송 중 다른 대화를 열었을 때 실제로 나던 버그다).
         * 🔴 목록을 프론트에서 조립하지 않는다 — `title`/`preview` 는 서버가 user 메시지 원문을
         *    절단해 만들고, 정렬(`updatedAt`)도 서버 몫이다. 임시 제목을 만들어 두면
         *    재조회 순간 다른 문구로 바뀐다.
         */
        setListReloadKey((k) => k + 1)
        // 성공 답변 1회. 🔴 성공 분기에서만 센다(실패·503 은 세지 않는다). 보고 있던 대화가
        //    바뀌었어도 사용자는 답을 받았으므로 stale 판정보다 먼저 센다.
        onSurveyAnswer()

        // 🔴 그 사이 새 대화로 리셋됐으면 **화면에는 붙이지 않는다** — 목록 갱신은 위에서 이미 했다.
        if (chatEpochRef.current !== epoch) return

        /*
         * 🔴 새 대화였으면 응답의 `sessionId` 를 지금부터 현재 세션으로 삼는다. 상세 재조회를
         *    1회 건너뛰도록 표식을 **`setSelectedSessionId` 앞에** 심는다 — 뒤에 심으면 effect 가
         *    먼저 돌아 화면을 비운다.
         */
        if (target == null && selectedSessionRef.current == null) {
          justCreatedSessionRef.current = res.sessionId
          selectedSessionRef.current = res.sessionId
          setSelectedSessionId(res.sessionId)
        } else if (selectedSessionRef.current !== target) {
          // 그 사이 다른 대화로 옮겼으면 **붙이지만** 않는다(그 대화의 이력은 재조회가 정본이다).
          // 위 목록 재조회는 이미 돌았다 — 세션 채택·말풍선 합류만 건너뛴다.
          return
        }
        /*
         * 새 답변에 `PENDING` 보고서가 있으면 재조회 시작 시각·만료를 리셋한다. 안 하면 앞선 PENDING 이
         *    이미 3분을 다 쓴 대화에서 새 PENDING 이 재조회를 한 번도 받지 못하고 곧바로 미완성 안내가 된다.
         *    재조회 effect 는 아래 `finally` 의 `setSending(false)` 로 다시 돌며 새 시작 시각을 잡는다.
         */
        if (res.message.reports?.some((r) => r.stts === 'PENDING')) {
          pendingPollRef.current = null
          setPendingExpired(false)
        }
        setMessages((prev) => [...(prev ?? []), res.message])
      })
      .catch((e: unknown) => {
        /*
         * 🔴 **입력글 되돌리기는 stale 여부와 무관하다** — 사용자가 직접 쓴 문장이라
         *    다른 대화로 옮겼다는 이유로 소실시키면 안 된다. (전송 중에는 입력창이 잠겨 있어
         *    되돌린 값이 새로 친 문장을 덮어쓸 일은 없다.)
         */
        // 🔴 단, **새 대화로 리셋된 뒤**라면 되돌리지 않는다 — 사용자가 방금 비운 입력창에 옛 문장이
        //    되살아나고, 비운 대화에 남의 실패 안내가 뜬다. 명시적 리셋 의도가 복원보다 우선한다.
        if (chatEpochRef.current !== epoch) return
        setInput(text)
        // 실패 안내는 **보낸 그 대화에서만** 띄운다(다른 대화에 남의 실패가 따라다니면 안 된다).
        if (selectedSessionRef.current !== target) return
        setSendFailed(true)
        setSendErrorCode(agentErrorCode(e))
      })
      .finally(() => {
        // 🔴 리셋 뒤 늦게 끝난 **옛 요청**이 그 사이 시작된 **새 전송의 잠금**을 풀면 안 된다.
        //    리셋이 이미 sending 을 내렸으므로 여기서 또 내릴 것도 없다.
        if (chatEpochRef.current !== epoch) return
        setSending(false)
        setPendingSessionId(null)
      })
  }, [input, sending, selectedSessionId, onSurveyAnswer])

  /**
   * `더 보기`. 직전 응답의 `nextCursor` 를 그대로 넘겨 다음 구간을 **이어붙인다**.
   *
   * 🔴 이어붙일 때 **이미 있는 `sessionId` 는 버린다** — 커서 페이징은 조회 사이에 세션이
   *    갱신되면 경계 행이 두 페이지에 걸칠 수 있고, 그러면 같은 행이 두 번 그려져 React 키가
   *    중복된다.
   */
  const loadMore = useCallback(() => {
    if (!nextCursor || loadingMore) return
    setLoadingMore(true)
    setMoreFailed(false)
    fetchAiSessions({ limit: PAGE_SIZE, cursor: nextCursor })
      .then((res) => {
        setSessions((prev) => {
          const seen = new Set(prev.map((s) => s.sessionId))
          // 🔴 이 화면에서 지운 행도 함께 버린다(`deletedIdsRef` 주석).
          return [
            ...prev,
            ...res.sessions.filter(
              (s) => !seen.has(s.sessionId) && !deletedIdsRef.current.has(s.sessionId),
            ),
          ]
        })
        setNextCursor(res.nextCursor ?? null)
      })
      .catch(() => {
        /*
         * 🔴 이미 그려 둔 목록도 `nextCursor` 도 지우지 않는다 — 이어붙이기 실패로 앞 페이지까지
         * 사라지면 사용자가 보던 것을 잃고, 커서를 버리면 다시 눌러 볼 수도 없다.
         * 실패는 목록 상단이 아니라 `더 보기` 자리에서 알린다(코드별 안내는 첫 페이지 몫이다 —
         * 여기서는 다시 누르는 것 말고 할 일이 없다).
         */
        setMoreFailed(true)
      })
      .finally(() => {
        setLoadingMore(false)
      })
  }, [nextCursor, loadingMore])

  /** 행의 ✕ — 대상만 정하고 팝업을 연다. 앞 대상의 실패 문구는 지운다. */
  const openDelete = useCallback((s: AiSessionItem) => {
    setDeleteError(null)
    setDeleteTarget(s)
  }, [])

  /**
   * 팝업 닫기. 🔴 취소 버튼·Esc·오버레이가 **모두 이 하나로** 들어온다(`ConfirmDeleteModal` 의 `onCancel`)
   * — 그래서 "요청 중이면 무시" 를 여기서 한다. 버튼 `disabled` 만으로는 Esc·오버레이를 못 막는다.
   */
  const closeDelete = () => {
    if (deleting) return
    setDeleteTarget(null)
    setDeleteError(null)
  }

  /**
   * 삭제 확인.
   *
   * 🔴 성공(204)과 업스트림 404 는 **같은 처리**다 — 다른 탭·기기에서 먼저 지운 경우이고, 목록에
   *    남기면 다시 눌러도 계속 실패한다.
   * 🔴 목록 재조회(`listReloadKey`)를 **올리지 않는다** — 재조회하면 `더 보기` 로 이어붙인
   *    페이지가 첫 페이지로 접힌다. 로컬에서 그 행만 뺀다.
   * 🔴 "지금 보는 대화인가" 는 클로저의 `selectedSessionId` 가 아니라 **`selectedSessionRef.current`**
   *    로 판정한다 — 팝업이 열린 동안·요청이 도는 동안 선택이 바뀔 수 있다.
   * 🔴 실패는 팝업을 **닫지 않고** 그 안에 띄운다. 문구는
   *    원인을 단정하지 않는다(503 은 도달 경로가 둘이다).
   */
  const confirmDelete = async () => {
    if (!deleteTarget) return
    const id = deleteTarget.sessionId
    setDeleting(true)
    setDeleteError(null)
    try {
      try {
        await deleteAiSession(id)
      } catch (e: unknown) {
        // 404 는 "이미 지워짐" — 아래 성공 경로로 흘려보낸다.
        if (!isUpstreamNotFound(e)) throw e
      }
      deletedIdsRef.current.add(id)
      setSessions((prev) => prev.filter((s) => s.sessionId !== id))
      // 🔴 재조회하지 않는다 — 업스트림 커서는 keyset(`updated_at`·`session_id`)이라 앞 행을 지워도
      //    다음 `더 보기` 가 밀리지 않는다(2026-10-25 실측). offset 이었다면 k 건을 건너뛴다.
      // 🔴 지운 것이 지금 열린 대화면 새 대화로 넘어간다 — 계속 띄우면 다음 전송이 사라진 세션에
      //    이어 쓴다. 리셋은 버튼·진입과 **같은 `startNewChat()`** 하나다(두 벌로 만들지 않는다).
      if (selectedSessionRef.current === id) startNewChat()
      setDeleteTarget(null)
    } catch (e: unknown) {
      const code = agentErrorCode(e)
      // REJECTED 는 업스트림 403·404 둘뿐인데 404 는 위 `isUpstreamNotFound` 로 이미 빠졌다 — 여기 남는 것은 403 뿐이다.
      if (code === 'AGENT_UPSTREAM_REJECTED') {
        setDeleteError('이 대화를 삭제할 수 없습니다.')
      } else if (code === 'AGENT_UPSTREAM_ERROR') {
        setDeleteError('삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      } else {
        // 503(`AGENT_TOKEN_UNAVAILABLE`·`AGENT_NOT_CONFIGURED`) · 401 · 그 밖 — 원인을 단정하지 않는다.
        setDeleteError('삭제하지 못했습니다.')
      }
    } finally {
      setDeleting(false)
    }
  }

  return (
    <>
      <header className="topbar">
        <div>
          <h1 className="page-title">AI 어시스턴트</h1>
          <p className="page-sub">sLLM 기반 — RAG 정책 문서·조례 검색</p>
        </div>
        <TopbarActions />
      </header>

      <div className="content content--ai">
        <section className="ai-row">
          <div className="card chat-card">
            <ConversationView
              messages={messages}
              loading={detailLoading}
              errorCode={detailErrorCode}
              failed={detailFailed}
              selected={selectedSessionId != null}
              newChat={newChat}
              onRetry={() => setDetailReloadKey((k) => k + 1)}
              /* 🔴 타이핑 점은 **보낸 그 대화에서만** — 전송 중 다른 대화를 열면
                 그쪽에 점이 뜨면 안 된다. 새 대화는 양쪽 다 `null` 이라 그대로 일치한다. */
              pending={sending && pendingSessionId === selectedSessionId}
              sendFailed={sendFailed}
              sendErrorCode={sendErrorCode}
              /* 서버 이력을 다시 읽어 실제 상태를 보여 준다 — 재전송이 아니므로 중복이 안 쌓인다. */
              onRefresh={handleRefreshConversation}
              /* 새 대화는 첫 전송 응답의 세션 ID 가 `selectedSessionId` 가 된 뒤부터 저장이 열린다. */
              sessionId={selectedSessionId}
              onPreview={previewState.openHtml}
              onReportSaved={handleReportSaved}
              pendingExpired={pendingExpired}
              onReloadPending={reloadPendingReports}
            />

            <Composer
              ref={composerRef}
              value={input}
              onChange={setInput}
              onSend={handleSend}
              sending={sending}
              placeholder={COMPOSER_PLACEHOLDER}
            />
          </div>

          <div className="ai-side">
            <div className="card ai-card">
              {/*
                🔴 시안 §2-1 실측 그대로 — 제목과 `＋ 새 대화` 버튼을 flex 헤더 한 줄에 둔다.
                   인라인 스타일 대신 `ai.css` 규칙으로 이식했다.
                🔴 아래 `최근 리포트` 카드에는 붙이지 않는다 — 시안에 그쪽 버튼이 없다.
              */}
              <div className="ai-card__head">
                <h2 className="ai-card__title">최근 대화</h2>
                <button
                  type="button"
                  className="btn"
                  /* 🔴 전송 중에는 잠근다 — 옮겨 간 뒤 응답이 도착하면 엉뚱한 대화에 붙는다. */
                  disabled={sending}
                  onClick={startNewChat}
                >
                  {NewChatIcon}
                  새 대화
                </button>
              </div>
              <RecentSessionList
                sessions={sessions}
                loading={listLoading}
                errorCode={listErrorCode}
                failed={listFailed}
                selectedSessionId={selectedSessionId}
                hasMore={nextCursor != null}
                loadingMore={loadingMore}
                moreFailed={moreFailed}
                onSelect={handleSelectSession}
                onDelete={openDelete}
                /* 🔴 전송 중에는 **모든** ✕ 를 잠근다(`＋ 새 대화` 버튼과 같은 조건). 그 대화만
                   잠그면 B 로 전송 중 열어 둔 A 를 지울 때 `startNewChat()` 이 B 의 잠금까지 푼다. */
                deleteDisabled={sending}
                onLoadMore={loadMore}
                onRetry={() => setListReloadKey((k) => k + 1)}
              />
            </div>
            <div className="card ai-card">
              <h2 className="ai-card__title">최근 리포트</h2>
              <RecentReportList reloadKey={reportReloadKey} />
            </div>
          </div>
        </section>
      </div>

      {/* 🔴 답변 속 보고서 미리보기 — 대화 스크롤 영역 밖 형제. PDF·DOCX 는 모달 안 버튼이다. */}
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
          message="대화를 삭제하시겠습니까?"
          busy={deleting}
          error={deleteError}
          onCancel={closeDelete}
          onConfirm={() => {
            void confirmDelete()
          }}
        />
      )}

      {surveyElement}
    </>
  )
}
