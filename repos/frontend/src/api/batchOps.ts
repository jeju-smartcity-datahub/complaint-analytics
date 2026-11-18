import axios from 'axios'
import { api } from './client'
import type { PageResponse } from './reports'

/*
 * 배치 운영 화면(`/ops/batch` — 숨김 메뉴 M0604) API 클라이언트.
 * 백엔드 계약(`jeju.datahub.batchops.web.BatchOpsController` 실측):
 *   GET  /api/ops/batch/jobs                         → BatchOpsJobs   (카탈로그 병합은 백엔드가 한다)
 *   GET  /api/ops/batch/executions?jobNm&status&page&size → PageResponse<BatchOpsExcn> (0-base · size ≤ 100)
 *   GET  /api/ops/batch/executions/{id}              → BatchOpsExcnDetail (실행 중이면 progress)
 *   POST /api/ops/batch/jobs/{jobNm}/executions      → 202 {jobExecutionId, jobNm, status}
 *   PUT  /api/ops/batch/schedules/{jobNm}            → 200 {state, registered, blockedByEnv, nextRuns}
 *   POST /api/ops/batch/executions/{id}/stop         → 202 {jobExecutionId, status, zombie}
 *   POST /api/ops/batch/executions/{id}/abandon      → 200 {jobExecutionId, jobNm, status, exitMessage}
 *   GET  /api/ops/batch/executions/{id}/log?tailKb=|fromByte= → {content, nextByte, jobRunning}
 *
 * 오류 본문 `{code, message, jobExecutionId?, status?, zombie?}` — 배치의 400·404·409 는 그대로,
 * 배치 불통은 503 `BATCH_UNAVAILABLE`, 미설정은 503 `BATCH_OPS_NOT_CONFIGURED`.
 *
 * 🔴 공용 `api` 인스턴스만 쓴다 — POST·PUT 은 CSRF 헤더(`X-XSRF-TOKEN`)가 필요하다. 전역 타임아웃(10s)은
 *    건드리지 않는다(실행은 비동기 202 라 오래 걸리지 않는다).
 * 🔴 시각 — `LocalDateTime` 칸(startTime 등)은 오프셋 없는 문자열, `nextRuns` 는 UTC ISO(`…Z`)다.
 *    둘 다 문자열을 잘라 쓰지 말고 `lib/datetime.ts` 의 `formatDotDateTime`(Date 파싱 + 로컬 getter)으로 그린다.
 */

/** 파라미터 명세 (배치 카탈로그). */
export interface BatchOpsParamSpec {
  /** 실행 요청 `params` 의 키. */
  name: string
  /** `DATE`(yyyy-MM-dd) · `ENUM`(options 중 하나). */
  type: string
  required: boolean
  /** 비웠을 때의 동작 설명. */
  defaultDesc: string | null
  options: string[]
  /** 화면 라벨. */
  description: string | null
}

export interface BatchOpsStepSpec {
  stepNm: string
  stepKorNm: string
}

/** 하위 결과 — 지금은 연쇄 실행(`CHAIN`)뿐이고 후속 "경로별 결과" 도 같은 모양으로 붙는다. */
export interface BatchOpsSubRslt {
  /** 하위 구분 (`CHAIN` = 연쇄 실행). */
  subSeCd: string
  /** 하위 이름 (CHAIN 이면 Job 이름). */
  subNm: string | null
  jobExecutionId: number | null
  status: string | null
  startTime: string | null
  endTime: string | null
  durationMs: number | null
  readCnt: number | null
  writeCnt: number | null
  message: string | null
  upExcnId: number | null
}

/** 실행 요약 (목록 · 마지막 실행). */
export interface BatchOpsExcn {
  jobExecutionId: number
  jobNm: string
  /** Spring Batch BatchStatus. */
  status: string
  exitCode: string | null
  exitMessage: string | null
  createTime: string | null
  startTime: string | null
  endTime: string | null
  lastUpdated: string | null
  durationMs: number | null
  /** N 이면 이력 기록 실패 — 이력 칸이 비어 있다. */
  hstryYn: string | null
  readCnt: number | null
  writeCnt: number | null
  /** SCHDL · MANUAL · CHAIN · API (도입 전 실행은 null). */
  excnSeCd: string | null
  excutrId: string | null
  upExcnId: number | null
  runMode: string | null
  targetRange: string | null
  failureMessage: string | null
  subRslts: BatchOpsSubRslt[]
}

export interface BatchOpsParam {
  parameterName: string
  parameterType: string | null
  parameterValue: string | null
  identifying: string | null
}

export interface BatchOpsStep {
  stepExecutionId: number
  stepName: string
  stepKorNm: string | null
  status: string | null
  startTime: string | null
  endTime: string | null
  readCount: number | null
  writeCount: number | null
  filterCount: number | null
  readSkipCount: number | null
  writeSkipCount: number | null
  processSkipCount: number | null
  commitCount: number | null
  rollbackCount: number | null
  exitCode: string | null
  exitMessage: string | null
}

/** 진행 단계 (실행 중일 때만). 전체 Step 수는 카탈로그에서 오므로 배치가 꺼져 있으면 null. */
export interface BatchOpsProgress {
  currentStep: string | null
  currentStepKorNm: string | null
  cmptnStepCnt: number
  totalStepCnt: number | null
  readCount: number | null
  writeCount: number | null
  /** 단계별 수치 — 민원 수집이 배치 프로세스에서 도는 동안만, 그 밖은 null */
  live: BatchOpsLive | null
}

/**
 * 실행 중 단계별 수치 — "API 수집 → 지오코딩 → DB 적재".
 * DB 적재 수는 여기 없다 — `BatchOpsProgress.writeCount`(청크 커밋마다 갱신)가 정본이다.
 * 원천 총건수는 모른다(일자별 수집) — 진행률은 `dayIdx / dayCnt`.
 */
export interface BatchOpsLive {
  dayIdx: number | null
  dayCnt: number | null
  crntYmd: string | null
  /** API 수집 — 원천에서 받은 행 수 */
  fetchCnt: number | null
  /** 가공(분류·지오코딩) 처리 행 수 — 스킵·필터 포함 */
  prcsCnt: number | null
  geocodedCnt: number | null
  unclsfCnt: number | null
  filteredTransient: number | null
  geocodeCacheHits: number | null
  geocodeApiLookups: number | null
  skipGeocodeFailed: number | null
  skipEmdUnresolved: number | null
}

export interface BatchOpsExcnDetail extends BatchOpsExcn {
  skipGeocodeFailed: number | null
  skipEmdUnresolved: number | null
  filteredTransient: number | null
  geocodeApiLookups: number | null
  geocodeCacheHits: number | null
  geocodePlaceRecoveries: number | null
  geocodeStripped: number | null
  emdContains: number | null
  emdBuffer: number | null
  emdNameFallback: number | null
  unclsfCnt: number | null
  /** 미분류 유형별 건수 — 백엔드 `@JsonRawValue`(jsonb 원문)라 **객체**로 온다. 예) `{"불법광고물": 9683}`. */
  unknownTypes: Record<string, number> | null
  params: BatchOpsParam[]
  steps: BatchOpsStep[]
  progress: BatchOpsProgress | null
}

export interface BatchOpsSchedule {
  /** Spring 6필드 cron. */
  cronExpr: string
  useYn: string
  mdfrId: string | null
  mdfcnDt: string | null
  /** 아래 4칸은 카탈로그 — 못 받으면 null. */
  registered: boolean | null
  blockedByEnv: boolean | null
  envKey: string | null
  /** UTC ISO 문자열. */
  nextRuns: string[] | null
}

/** 잡 한 줄. 카탈로그 칸(한글명·params·steps 등)은 배치가 꺼져 있으면 null 이다. */
export interface BatchOpsJob {
  jobNm: string
  jobKorNm: string | null
  description: string | null
  countLabel: string | null
  schedulable: boolean | null
  chainNext: string | null
  chainCondition: string | null
  runningJobExecutionIds: number[] | null
  params: BatchOpsParamSpec[] | null
  steps: BatchOpsStepSpec[] | null
  lastExcn: BatchOpsExcn | null
  lastSuccessDt: string | null
  lastSuccessExcnId: number | null
  schedule: BatchOpsSchedule | null
}

export interface BatchOpsJobs {
  /** false 면 배치 카탈로그를 못 받아 DB 의 잡 이름만으로 그린 목록이다. */
  catalogAvailable: boolean
  jobs: BatchOpsJob[]
}

export interface BatchOpsExecuteResult {
  jobExecutionId: number
  jobNm: string
  status: string
}

export interface BatchOpsScheduleResult {
  jobNm: string
  cronExpr: string
  useYn: string
  mdfrId: string | null
  /** REGISTERED · DISABLED · BLOCKED … (배치 ScheduleState). */
  state: string
  registered: boolean
  blockedByEnv: boolean
  nextRuns: string[]
}

export interface BatchOpsStopResult {
  jobExecutionId: number
  status: string
  zombie: boolean
}

export interface BatchOpsLog {
  content: string
  /** 다음 증분 조회 `fromByte` (원본 파일 바이트 기준). */
  nextByte: number | null
  /** false(또는 null)면 폴링을 멈춘다. */
  jobRunning: boolean | null
}

/** 오류 본문. */
export interface BatchOpsErrorBody {
  code?: string
  message?: string
  jobExecutionId?: number
  status?: string
  zombie?: boolean
}

export async function fetchBatchJobs(): Promise<BatchOpsJobs> {
  const { data } = await api.get<BatchOpsJobs>('/ops/batch/jobs')
  return data
}

export interface BatchExcnSearch {
  jobNm?: string
  /** BatchStatus. 전체면 넣지 않는다. */
  status?: string
  page?: number
  size?: number
}

export async function fetchBatchExecutions(
  params: BatchExcnSearch,
): Promise<PageResponse<BatchOpsExcn>> {
  const { data } = await api.get<PageResponse<BatchOpsExcn>>('/ops/batch/executions', { params })
  return data
}

export async function fetchBatchExecution(id: number): Promise<BatchOpsExcnDetail> {
  const { data } = await api.get<BatchOpsExcnDetail>(`/ops/batch/executions/${id}`)
  return data
}

/** 실행 요청. 비운 파라미터는 넣지 않는다(배치가 기본값을 쓴다). */
export async function executeBatchJob(
  jobNm: string,
  params: Record<string, string>,
): Promise<BatchOpsExecuteResult> {
  const { data } = await api.post<BatchOpsExecuteResult>(
    `/ops/batch/jobs/${encodeURIComponent(jobNm)}/executions`,
    { params },
  )
  return data
}

export async function updateBatchSchedule(
  jobNm: string,
  cronExpr: string,
  useYn: 'Y' | 'N',
): Promise<BatchOpsScheduleResult> {
  const { data } = await api.put<BatchOpsScheduleResult>(
    `/ops/batch/schedules/${encodeURIComponent(jobNm)}`,
    { cronExpr, useYn },
  )
  return data
}

export async function stopBatchExecution(id: number): Promise<BatchOpsStopResult> {
  const { data } = await api.post<BatchOpsStopResult>(`/ops/batch/executions/${id}/stop`)
  return data
}

export async function abandonBatchExecution(id: number): Promise<void> {
  await api.post(`/ops/batch/executions/${id}/abandon`)
}

/** 텍스트 로그 — `tailKb`(마지막 N KB) 와 `fromByte`(증분) 중 하나만 보낸다(둘 다면 400). */
export async function fetchBatchLog(
  id: number,
  query: { tailKb: number } | { fromByte: number },
): Promise<BatchOpsLog> {
  const { data } = await api.get<BatchOpsLog>(`/ops/batch/executions/${id}/log`, { params: query })
  return data
}

/** axios 오류에서 상태코드·본문을 꺼낸다. 응답이 없으면(타임아웃·네트워크) status 는 undefined. */
export function batchOpsError(e: unknown): { status?: number; body: BatchOpsErrorBody } {
  if (axios.isAxiosError(e)) {
    const data = e.response?.data
    const body: BatchOpsErrorBody =
      data && typeof data === 'object' ? (data as BatchOpsErrorBody) : {}
    return { status: e.response?.status, body }
  }
  return { body: {} }
}

/**
 * 실패 문구. 🔴 503 은 원인을 단정하지 않는다("연결할 수 없습니다" 까지만).
 * 400·404·409 는 배치가 준 `message` 를 그대로 쓴다(이미 한국어 문구다).
 */
export function batchOpsErrorText(e: unknown, fallback: string): string {
  const { status, body } = batchOpsError(e)
  if (status === 503) {
    if (body.code === 'BATCH_OPS_NOT_CONFIGURED')
      return '배치 서버 연결 설정이 비어 있습니다. 관리자에게 문의해 주세요.'
    return '배치 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  }
  if (status === 403) return '이 작업을 할 권한이 없습니다.'
  if (status === 409 && body.code === 'JOB_ALREADY_RUNNING' && body.jobExecutionId != null) {
    return `이미 실행 중입니다(실행 #${body.jobExecutionId}).`
  }
  if (status && status >= 400 && status < 500 && body.message) return body.message
  return fallback
}
