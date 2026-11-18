import { useId, useRef, useState, type KeyboardEvent } from 'react'
import Modal from '../common/Modal'
import { isSurveyAlreadySubmitted, submitAiSurvey, SURVEY_OPINION_MAX } from '../../api/aiSurvey'

/*
 * AI 어시스턴트 만족도 설문 팝업 (TSK-4369 · 시안 1장째).
 *
 * - 점수 1~10 은 라디오 그룹이다(`role="radiogroup"` + 각 버튼 `role="radio"`·`aria-checked`).
 *   이름은 버튼 안의 **보이는 숫자**가 준다 — `aria-label` 단독·`title` 의존을 하지 않는다(접근성 점검 기준).
 *   선택된 버튼(없으면 첫 버튼)만 Tab 으로 들어오고, 화살표·Home·End 로 옮긴다.
 * - `나중에`·✕·Esc·오버레이는 모두 `onDismiss` 하나로 들어온다(미룸 처리는 호출부 몫).
 * - 409(이미 응답함)는 실패가 아니라 성공과 같이 감사 팝업으로 넘긴다.
 * - 전송 중에는 닫기·재제출을 막는다(이중 제출 방지 — 서버는 UNIQUE 로 막지 않는다).
 */

const SCORES = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as const

interface Props {
  /** 응답 당시 대화 세션 ID(참고용 — 새 대화 화면이면 null). */
  sesnId: string | null
  onDismiss: () => void
  onSubmitted: () => void
}

export default function SatisfactionSurveyModal({ sesnId, onDismiss, onSubmitted }: Props) {
  const [score, setScore] = useState<number | null>(null)
  const [opinion, setOpinion] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const btnRefs = useRef<(HTMLButtonElement | null)[]>([])
  const guideId = useId()
  const opinionId = useId()

  const close = () => {
    if (!submitting) onDismiss()
  }

  const moveTo = (idx: number) => {
    const next = (idx + SCORES.length) % SCORES.length
    setScore(SCORES[next])
    btnRefs.current[next]?.focus()
  }

  const onScaleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const cur = score == null ? -1 : SCORES.indexOf(score as (typeof SCORES)[number])
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      e.preventDefault()
      moveTo(cur + 1)
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      e.preventDefault()
      moveTo(cur < 0 ? SCORES.length - 1 : cur - 1)
    } else if (e.key === 'Home') {
      e.preventDefault()
      moveTo(0)
    } else if (e.key === 'End') {
      e.preventDefault()
      moveTo(SCORES.length - 1)
    }
  }

  const handleSubmit = () => {
    if (score == null || submitting) return
    setSubmitting(true)
    setError(null)
    submitAiSurvey({ dgstfnScr: score, opnnCn: opinion, sesnId })
      .then(() => onSubmitted())
      .catch((e: unknown) => {
        if (isSurveyAlreadySubmitted(e)) {
          onSubmitted()
          return
        }
        setError('제출하지 못했습니다. 잠시 후 다시 시도해 주세요.')
        setSubmitting(false)
      })
  }

  return (
    <Modal
      title="AI 어시스턴트 만족도 조사"
      variant="sm"
      onClose={close}
      footSplit
      footer={
        <>
          <button type="button" className="ai-srvy__later" disabled={submitting} onClick={close}>
            나중에
          </button>
          <button
            type="button"
            className="ai-srvy__submit"
            disabled={score == null || submitting}
            onClick={handleSubmit}
          >
            {submitting ? '제출 중…' : '제출'}
          </button>
        </>
      }
    >
      <p className="ai-srvy__guide" id={guideId}>
        AI 어시스턴트 사용 경험은 어떠셨나요? 1점(매우 불만족)부터 10점(매우 만족)까지 선택해
        주세요.
      </p>

      <div
        className="ai-srvy__scale"
        role="radiogroup"
        aria-labelledby={guideId}
        onKeyDown={onScaleKeyDown}
      >
        {SCORES.map((n, i) => {
          const checked = score === n
          const tabbable = checked || (score == null && i === 0)
          return (
            <button
              key={n}
              ref={(el) => {
                btnRefs.current[i] = el
              }}
              type="button"
              role="radio"
              aria-checked={checked}
              tabIndex={tabbable ? 0 : -1}
              className={`ai-srvy__score${checked ? ' is-active' : ''}`}
              disabled={submitting}
              onClick={() => setScore(n)}
            >
              {n}
            </button>
          )
        })}
      </div>
      <div className="ai-srvy__ends" aria-hidden="true">
        <span>매우 불만족</span>
        <span>매우 만족</span>
      </div>

      <label className="ai-srvy__label" htmlFor={opinionId}>
        의견 (선택)
      </label>
      <textarea
        id={opinionId}
        className="ai-srvy__opinion"
        placeholder="개선할 점이나 좋았던 점을 자유롭게 남겨 주세요."
        maxLength={SURVEY_OPINION_MAX}
        value={opinion}
        disabled={submitting}
        onChange={(e) => setOpinion(e.target.value)}
      />

      {error && (
        <p className="ai-srvy__error" role="alert">
          {error}
        </p>
      )}
    </Modal>
  )
}
