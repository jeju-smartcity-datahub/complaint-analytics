import axios from 'axios'
import { api } from './client'

/*
 * AI 어시스턴트 만족도 설문 API.
 *
 * 🔴 공용 `api` 인스턴스를 쓴다 — POST 는 CSRF 대상이라 `X-XSRF-TOKEN` 이 자동으로 실려야 한다.
 *    새 axios 인스턴스를 만들면 그 헤더가 빠져 403 이다.
 * 🔴 재질문(제출 후)·미룸(나중에) 기간은 서버 공통코드가 정본이다 — 프론트는 상수로 두지 않고
 *    `status` 응답의 값을 쓴다.
 */

export interface AiSurveyStatus {
  /** true 면 지금 설문을 띄워도 된다(최근 제출이 없거나 제출 기간이 지났다). */
  eligible: boolean
  /** 내 마지막 제출 일시(없으면 null). */
  lastSbmsnDt: string | null
  /** `나중에` 선택 시 다시 묻지 않는 일수(공통코드 AI_SRVY_PSTP_PRD_CD). */
  postponeDays: number
}

export const SURVEY_OPINION_MAX = 1000

export async function fetchAiSurveyStatus(): Promise<AiSurveyStatus> {
  const { data } = await api.get<AiSurveyStatus>('/ai/surveys/status')
  return data
}

export async function submitAiSurvey(p: {
  dgstfnScr: number
  opnnCn?: string
  sesnId?: string | null
}): Promise<{ srvySn: number }> {
  const { data } = await api.post<{ srvySn: number }>('/ai/surveys', {
    dgstfnScr: p.dgstfnScr,
    opnnCn: p.opnnCn?.trim() ? p.opnnCn.trim() : undefined,
    sesnId: p.sesnId ?? undefined,
  })
  return data
}

/** 제출 기간 안 재제출(409 `SURVEY_ALREADY_SUBMITTED`) — 실패가 아니라 "이미 응답함" 으로 다룬다. */
export function isSurveyAlreadySubmitted(e: unknown): boolean {
  return (
    axios.isAxiosError(e) &&
    e.response?.status === 409 &&
    (e.response.data as { code?: string } | undefined)?.code === 'SURVEY_ALREADY_SUBMITTED'
  )
}
