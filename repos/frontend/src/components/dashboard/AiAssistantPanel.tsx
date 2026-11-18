import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Icon from '../Icon'
import { fetchAiSessions, type AiSessionItem } from '../../api/ai'
import SrLabel from '../common/SrLabel'

/*
 * 하단 3카드 중 "AI 어시스턴트" 패널 — 시안의 `.panel` + `.ai-input` / `.ai-suggest`.
 *
 * 🔁 **TSK-4169 Step 11 — 목록이 `추천 질문(FAQ)` 에서 `최근 대화` 로 바뀌었다**(요구 #19·#20).
 *    출처도 `/api/dashboard/ai-suggestions` → **`GET /api/ai/sessions`** 로 교체했다 —
 *    AI 어시스턴트 화면의 `최근 대화` 와 **같은 API**이고 `limit` 만 3이다. 정렬은 서버가 한다
 *    (프론트에서 다시 정렬하지 않는다).
 *    항목을 누르면 **그 기존 대화를 연다** — `navigate('/ai-assistant', { state: { sessionId } })`.
 *    🔴 받는 쪽 계약(`AiEntryState.sessionId`)은 Step 11-2-1 이 `AiAssistantPage` 에 만들었다.
 *    🔴 **세션 ID 를 프론트가 만들지 않는다**(CLAUDE.md AI §5) — 여기서 넘기는 값은 목록 API 가
 *       준 **기존** 세션 ID 뿐이다.
 *
 * 입력창(클릭 시 AI 페이지로 이동)은 데이터가 아니므로 그대로 유지한다.
 */

/**
 * 카드에 보여 줄 최근 대화 건수 (요구 #19 — 3건).
 *
 * 그대로 `GET /api/ai/sessions` 의 `limit` 으로 나간다 — 넉넉히 받아 프론트에서 자르지 않는다
 * (서버 정렬 그대로가 정본이다).
 */
const RECENT_LIMIT = 3

export default function AiAssistantPanel() {
  const navigate = useNavigate()

  const [items, setItems] = useState<AiSessionItem[] | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let cancelled = false
    // AI 어시스턴트 화면의 `최근 대화` 와 **같은 API**를 쓴다(limit 만 3). 정렬은 서버가 한다.
    fetchAiSessions({ limit: RECENT_LIMIT })
      .then((res) => {
        if (!cancelled) setItems(res.sessions)
      })
      .catch(() => {
        if (!cancelled) {
          setItems(null)
          setError(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  /** 입력창 초안. 🔴 전송은 하지 않는다 — AI 어시스턴트 화면으로 **들고 가기만** 한다. */
  const [draft, setDraft] = useState('')

  /*
   * 🔴 **채우되 보내지 않는다**(TSK-4169). 진입 계약의 `prefill` 이 정확히 그 뜻이고,
   * `submit`(채우고 즉시 전송)은 `AiAssistantPage` 가 **의도적으로 지원하지 않는다** —
   * 새로고침 재전송·StrictMode 이중 실행 가드를 검증 없이 지고 가게 되기 때문이다.
   * `focus: true` 를 함께 넘겨 이어서 바로 고쳐 쓸 수 있게 한다.
   */
  const carryDraft = () => {
    const text = draft.trim()
    if (!text) return
    navigate('/ai-assistant', { state: { prefill: text, focus: true } })
  }

  return (
    <div className="card panel">
      <div className="card-head">
        <h2 className="card-head__title">
          <span className="panel__icon panel__icon--violet">
            <Icon name="sparkle" size={20} />
          </span>
          AI 어시스턴트
        </h2>
        <Link to="/ai-assistant" state={{ focus: true }} className="card-link">
          대화 열기 <Icon name="arrow-right" size={16} />
        </Link>
      </div>

      {/* 🔄 TSK-4169 — 클릭만 받던 자리표시 div 를 **실제 입력창**으로 바꿨다(시안 `.ai-input--live`).
          엔터·전송 버튼이 같은 `carryDraft()` 를 탄다. 🔴 빈 문자열이면 이동하지 않는다. */}
      <div className="ai-input ai-input--live">
        <SrLabel text="AI 어시스턴트에게 보낼 질문">
          <input
            type="text"
            aria-label="AI 어시스턴트에게 보낼 질문"
            placeholder="이번달 민원 현황을 요약해줘..."
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              // 🔴 한글 IME 조합 중의 Enter 는 "조합 확정" 이지 전송이 아니다 — 막지 않으면
              //    마지막 글자가 확정되기 전의 값을 들고 가 **첫 글자가 잘린다**(`학교` → `교`).
              //    `Composer.tsx:79` 가 같은 처리를 한다 — 그 선례를 따른다.
              if (e.nativeEvent.isComposing) return
              if (e.key === 'Enter') {
                e.preventDefault()
                carryDraft()
              }
            }}
          />
        </SrLabel>
        {draft.trim() !== '' && (
          <button
            className="ai-input__send"
            type="button"
            aria-label="AI 어시스턴트로 가져가기"
            onClick={carryDraft}
          >
            {/* 시안 번들의 종이비행기 SVG 원문. */}
            <svg viewBox="0 0 24 24" fill="none" width="16" height="16" aria-hidden="true">
              <path d="M3.6 20.3 21 12 3.6 3.7 3.6 10.2 14.5 12 3.6 13.8 3.6 20.3Z" fill="currentColor" />
            </svg>
          </button>
        )}
      </div>

      {error ? (
        /* 🔴 503(AGENT_TOKEN_UNAVAILABLE)을 `대화기록이 없음` 으로 뭉개지 않는다(TSK-4169 Step 11-4)
           — 대화가 없는 것과 연동이 안 되는 것은 다른 상태다. 다만 도달 경로가 여러 개라
           (mock 로그인 / 토큰 부재 / 업스트림 장애) 원인을 단정하지 않는다.
           ⚠️ OIDC 로그인 세션에서는 **항상** 이 상태다(CLAUDE.md — `aud` 에 `dhub2-agent` 없음 · B1). */
        <div className="panel-state">대화 목록을 불러올 수 없습니다</div>
      ) : items === null ? (
        <div className="panel-state">불러오는 중…</div>
      ) : items.length === 0 ? (
        /* 🔄 TSK-4169(2026-10-02) — 빈 목록이면 **아무것도 그리지 않는다**(입력창만 남는다).
           종전에는 `대화기록이 없음` 을 띄웠는데, 바로 위 입력창이 "여기서 시작하라" 는 안내를
           이미 하고 있어 빈 상태 문구가 자리만 차지했다.
           🔴 `error` 분기는 **남긴다** — 연동 실패(503)는 "대화가 없다" 와 다른 상태이고,
           그때는 입력창만 남기면 사용자가 원인을 알 수 없다. */
        null
      ) : (
        <div className="ai-suggest">
          {items.map((s) => (
            <button
              type="button"
              key={s.sessionId}
              /* 🔴 쿼리스트링이 아니라 `state` 로 넘긴다 — `AiAssistantPage` 의 진입 정본이 state 이고,
                 세션 ID 가 주소창에 남아 공유·북마크되는 것도 막는다(TSK-4169 Step 11-2-1). */
              onClick={() => navigate('/ai-assistant', { state: { sessionId: s.sessionId } })}
            >
              <Icon className="chev" name="chevron-right" size={16} />
              {/* `title` 은 null 일 수 있다(`AiSessionItem` 계약) — 공백만 있는 제목도 `.trim()` 으로
                  걸러 `preview` → 고정 문구로 떨어뜨린다. 화면이 대체 문구를 정한다. */}
              {s.title?.trim() || s.preview?.trim() || '제목 없는 대화'}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
