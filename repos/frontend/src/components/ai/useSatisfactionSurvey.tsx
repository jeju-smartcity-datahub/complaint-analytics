import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchAiSurveyStatus } from '../../api/aiSurvey'
import SatisfactionSurveyModal from './SatisfactionSurveyModal'
import SurveyThanksModal from './SurveyThanksModal'

/*
 * AI 어시스턴트 만족도 설문 노출 규칙.
 *
 * - 이 화면 방문 중 **성공 답변이 SURVEY_AFTER_ANSWERS 번째**에 닿으면 그때 서버 `status` 를 1회 묻는다
 *   (진입 시 미리 부르지 않는다). 실패한 전송은 세지 않는다 — 호출부가 성공 분기에서만 부른다.
 * - 서버가 대상(`eligible`)이라 해도 **`blocked`(전송 중·다른 팝업 열림)가 풀릴 때까지** 기다렸다 연다.
 * - 한 방문에 한 번만 — 닫히면(제출·미룸·조회 실패 어느 쪽이든) 다시 열지 않는다.
 * - `나중에` 는 localStorage 에 **만료 시각**을 남긴다. 일수는 서버 공통코드(`postponeDays`)이고,
 *   만료 시각으로 저장하므로 나중에 설정이 바뀌어도 이미 미룬 사람의 만료일은 그대로다.
 *   🔴 localStorage 는 막혀 있을 수 있어(사생활 모드 등) 모든 접근을 try/catch 한다 — 실패해도
 *   이번 방문 억제만으로 충분하다.
 * - 재질문(제출 후 N일)은 서버가 정본이다 — 프론트는 기록하지 않는다.
 */

const SURVEY_AFTER_ANSWERS = 3
const POSTPONE_KEY = 'ai-survey-postpone-until'
const DAY_MS = 24 * 60 * 60 * 1000

type Phase = 'idle' | 'checking' | 'ready' | 'survey' | 'thanks' | 'done'

function isPostponed(): boolean {
  try {
    const until = Number(window.localStorage.getItem(POSTPONE_KEY))
    return Number.isFinite(until) && until > Date.now()
  } catch {
    return false
  }
}

function postpone(days: number) {
  try {
    window.localStorage.setItem(POSTPONE_KEY, String(Date.now() + days * DAY_MS))
  } catch {
    /* 저장 실패 — 이번 방문 억제만 적용된다 */
  }
}

export function useSatisfactionSurvey({
  blocked,
  sesnId,
}: {
  blocked: boolean
  sesnId: string | null
}) {
  const [phase, setPhaseState] = useState<Phase>('idle')
  /*
   * 🔴 판정은 ref 로 한다 — 호출부(`handleSend` 의 `.then`)는 **전송을 시작할 때의** 콜백을 붙잡고 있어,
   *    state 로 판정하면 이미 지나간 'idle' 을 보고 status 를 두 번 부를 수 있다.
   */
  const phaseRef = useRef<Phase>('idle')
  const setPhase = useCallback((p: Phase) => {
    phaseRef.current = p
    setPhaseState(p)
  }, [])
  const answersRef = useRef(0)
  const postponeDaysRef = useRef(0)

  const onAnswerReceived = useCallback(() => {
    if (phaseRef.current !== 'idle') return
    answersRef.current += 1
    if (answersRef.current < SURVEY_AFTER_ANSWERS) return
    if (isPostponed()) {
      setPhase('done')
      return
    }
    setPhase('checking')
    fetchAiSurveyStatus()
      .then((s) => {
        postponeDaysRef.current = s.postponeDays
        setPhase(s.eligible ? 'ready' : 'done')
      })
      // 설문 때문에 화면 오류를 만들지 않는다 — 조용히 이번 방문은 건너뛴다.
      .catch(() => setPhase('done'))
  }, [setPhase])

  useEffect(() => {
    if (phase === 'ready' && !blocked) setPhase('survey')
  }, [phase, blocked, setPhase])

  const surveyElement =
    phase === 'survey' ? (
      <SatisfactionSurveyModal
        sesnId={sesnId}
        onDismiss={() => {
          if (postponeDaysRef.current > 0) postpone(postponeDaysRef.current)
          setPhase('done')
        }}
        onSubmitted={() => setPhase('thanks')}
      />
    ) : phase === 'thanks' ? (
      <SurveyThanksModal onClose={() => setPhase('done')} />
    ) : null

  return { onAnswerReceived, surveyElement }
}
