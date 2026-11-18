import { forwardRef, useCallback, useLayoutEffect, useRef, type KeyboardEvent } from 'react'
import SrLabel from '../common/SrLabel'

/*
 * 입력창 `.chat-input`.
 *
 * `AiAssistantPage` 안에 `disabled` 로 박아 두었던 입력창을 **분리하고 활성화**한다.
 * DOM·클래스(`.chat-input` > `.composer` > `.composer__pill` > `.composer__field` + `.composer__send`)는
 * 시안 그대로다 — CSS 는 `src/styles/ai.css` 가 이미 갖고 있다.
 *
 * **`locked` prop 을 뗐다.** 종전에는 "고른 대화가 없으면 잠금" 이었는데,
 *    이제 **새 대화 상태에서의 첫 전송**(`POST /api/ai/messages`)을 붙여 전송 대상이 없는
 *    상태 자체가 사라졌다. 🔴 이제 잠기는 조건은 **전송 중 하나뿐**이다.
 *
 * 🔴 **입력값은 부모가 들고 있는다**(제어 컴포넌트). 전송 실패 시 부모가 입력글을 되돌려 놓고,
 *    진입 계약(`prefill`)도 부모가 채우기 때문이다 — 여기서 또 state 를 들면 두 곳이 어긋난다.
 * 🔴 **`maxLength={4000}`** — 백엔드 `AiChatRequest` 의 `@Size(max = 4000)` 위반은 본문에 `code` 가
 *    없는 400 이라 화면이 "그 밖" 기본 문구로 떨어진다. 도달 자체를 여기서 막는다.
 * 🔴 **전송 중에는 입력·버튼을 잠근다** — Enter 연타가 그대로 요청 폭주가 된다(동기 경로라
 *    한 번의 왕복이 최대 120초다).
 * 🔴 **한글 IME 조합 중의 Enter 는 전송이 아니다**(`isComposing`) — 막지 않으면 조합을 확정하는
 *    Enter 가 곧바로 전송되어 "쓰다 만 문장" 이 나간다.
 */

/** 시안 `.composer__send` 안의 종이비행기 아이콘. */
const SendIcon = (
  <svg viewBox="0 0 24 24" fill="none" width="22" height="22" aria-hidden="true">
    <path d="M3.6 20.3 21 12 3.6 3.7 3.6 10.2 14.5 12 3.6 13.8 3.6 20.3Z" fill="currentColor" />
  </svg>
)

/**
 * 🔴 입력창 최대 높이(줄 수) — 넘으면 더 자라지 않고 내부 스크롤로 넘긴다.
 *    없으면 긴 질문에서 입력창이 대화 영역을 통째로 밀어낸다.
 */
const MAX_ROWS = 6

const Composer = forwardRef<
  HTMLTextAreaElement,
  {
    /** 입력값(부모 소유). */
    value: string
    onChange: (v: string) => void
    /** 전송 요청. 빈 문자열·전송 중에는 부모가 무시한다(여기서도 한 번 거른다). */
    onSend: () => void
    /** 전송 진행 중인지. 🔴 **이때만** 입력창이 잠긴다(`locked` 를 뗀 뒤 유일한 조건). */
    sending: boolean
    placeholder: string
  }
>(function Composer({ value, onChange, onSend, sending, placeholder }, ref) {
  const blocked = sending

  /*
   * 🔴 자동 줄바꿈·자동 높이 — `<input>` 은 넘치는 글을 한 줄에 가두고 가로로 흘린다.
   *    `<textarea>` 로 바꾸고 내용에 맞춰 높이를 다시 잰다(MAX_ROWS 에서 멈춘다).
   */
  const innerRef = useRef<HTMLTextAreaElement | null>(null)
  const setRefs = useCallback(
    (el: HTMLTextAreaElement | null) => {
      innerRef.current = el
      if (typeof ref === 'function') ref(el)
      else if (ref) ref.current = el
    },
    [ref],
  )

  useLayoutEffect(() => {
    const el = innerRef.current
    if (!el) return
    // 🔴 먼저 auto 로 되돌린다 — 안 그러면 글을 지워도 높이가 줄지 않는다.
    el.style.height = 'auto'
    const line = parseFloat(getComputedStyle(el).lineHeight) || 24
    el.style.height = `${Math.min(el.scrollHeight, line * MAX_ROWS)}px`
  }, [value])

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    // 🔴 Shift+Enter 는 줄바꿈이다 — 전송이 아니다.
    if (e.key !== 'Enter' || e.shiftKey) return
    // 🔴 IME 조합 확정 Enter 도 전송이 아니다.
    if (e.nativeEvent.isComposing) return
    e.preventDefault()
    if (blocked) return
    onSend()
  }

  return (
    <div className="chat-input">
      <div className="composer">
        <div className="composer__pill">
          <SrLabel text="AI 어시스턴트에게 보낼 메시지">
            <textarea
              ref={setRefs}
              className="composer__field"
              aria-label="AI 어시스턴트에게 보낼 메시지"
              rows={1}
              placeholder={placeholder}
              autoComplete="off"
              maxLength={4000}
              value={value}
              disabled={blocked}
              onChange={(e) => onChange(e.target.value)}
              onKeyDown={handleKeyDown}
            />
          </SrLabel>
        </div>
        <button
          className="composer__send"
          type="button"
          aria-label="전송"
          disabled={blocked || value.trim().length === 0}
          onClick={onSend}
        >
          {SendIcon}
        </button>
      </div>
    </div>
  )
})

export default Composer
