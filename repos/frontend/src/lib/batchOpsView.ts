/*
 * 배치 운영 화면 표시 규칙 (TSK-4337 Step 8) — 순수 함수만 둔다(화면·모달이 함께 쓴다).
 */

/** 실행 중으로 보는 BatchStatus (배치 `JobExecution.isRunning()` 과 같은 집합). */
export const RUNNING_STATUSES: readonly string[] = ['STARTING', 'STARTED', 'STOPPING']

export function isRunningStatus(status: string | null | undefined): boolean {
  return !!status && RUNNING_STATUSES.includes(status)
}

/** 좀비 판정 유예 — 시작 직후 레지스트리 등록 전 틈을 좀비로 보지 않는다. */
export const ZOMBIE_GRACE_MS = 10_000

/**
 * 좀비(배치 프로세스에서 돌지 않는데 DB 상 실행 중으로 남은 실행) 판정 — 목록·상세가 **이 함수 하나**를 쓴다.
 * 조건: 상태 STARTED/STOPPING(🔴 STARTING 제외 — 막 시작한 실행) · 프로세스 실행 목록에 없음(`processRunning === false`,
 * 카탈로그를 못 받으면 null → 판정 안 함) · 시작 시각이 10초 넘게 지났음.
 */
export function isZombieExecution(
  status: string | null | undefined,
  startTime: string | null | undefined,
  processRunning: boolean | null,
  now: number = Date.now(),
): boolean {
  if (status !== 'STARTED' && status !== 'STOPPING') return false
  if (processRunning !== false || !startTime) return false
  const t = new Date(startTime).getTime()
  if (Number.isNaN(t)) return false
  return now - t > ZOMBIE_GRACE_MS
}

/** 중지 응답이 zombie=true 일 때의 안내. */
export const ZOMBIE_STOP_NOTICE =
  '이 실행은 배치 프로세스에서 돌고 있지 않습니다 — [실패 처리]로 정리하세요.'

/** 이력 필터 select 옵션 — 백엔드 `BatchOpsController.STATUSES` 와 같은 값. */
export const STATUS_FILTERS: readonly string[] = [
  'STARTING',
  'STARTED',
  'STOPPING',
  'STOPPED',
  'COMPLETED',
  'FAILED',
  'ABANDONED',
  'UNKNOWN',
]

const STATUS_LABEL: Record<string, string> = {
  STARTING: '시작 중',
  STARTED: '실행 중',
  STOPPING: '중지 중',
  STOPPED: '중지됨',
  COMPLETED: '완료',
  FAILED: '실패',
  ABANDONED: '폐기됨',
  UNKNOWN: '알 수 없음',
}

/** `dashboard.css` 의 공용 `.badge--*` 변형. */
const STATUS_BADGE: Record<string, string> = {
  STARTING: 'badge--progress',
  STARTED: 'badge--progress',
  STOPPING: 'badge--pending',
  STOPPED: 'badge--neutral',
  COMPLETED: 'badge--done',
  FAILED: 'badge--failed',
  ABANDONED: 'badge--neutral',
  UNKNOWN: 'badge--neutral',
}

export function statusLabel(status: string | null | undefined): string {
  if (!status) return '-'
  return STATUS_LABEL[status] ?? status
}

export function statusBadgeClass(status: string | null | undefined): string {
  return `badge ${(status && STATUS_BADGE[status]) || 'badge--neutral'}`
}

const EXCN_SE_LABEL: Record<string, string> = {
  SCHDL: '스케줄',
  MANUAL: '수동',
  CHAIN: '연쇄',
  API: 'API 트리거',
}

/** 실행구분. 도입 전 실행은 null → '-'. */
export function excnSeLabel(cd: string | null | undefined): string {
  if (!cd) return '-'
  return EXCN_SE_LABEL[cd] ?? cd
}

const SUB_SE_LABEL: Record<string, string> = {
  CHAIN: '연쇄',
}

/** 하위 결과 구분 — 후속 "경로별 결과" 가 붙으면 여기에 한 줄 더한다. */
export function subSeLabel(cd: string | null | undefined): string {
  if (!cd) return '-'
  return SUB_SE_LABEL[cd] ?? cd
}

/** 소요 ms → `1시간 2분` · `3분 12초` · `12초` · `1초 미만`. 없으면 '-'. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || ms < 0) return '-'
  if (ms < 1000) return '1초 미만'
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}시간 ${m}분`
  if (m > 0) return `${m}분 ${s}초`
  return `${s}초`
}

/** 건수 천 단위 구분. 없으면 '-'. */
export function formatCount(n: number | null | undefined): string {
  if (n == null) return '-'
  return n.toLocaleString('ko-KR')
}

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function isNum(s: string): boolean {
  return /^\d+$/.test(s)
}

const QUARTER_MONTHS = ['1,4,7,10', '*/3', '1/3']

/**
 * Spring 6필드 cron(초 분 시 일 월 요일) → 한국어 설명. 풀 수 없는 모양이면 **null**
 * (호출부는 cron 원문 + 배치가 준 다음 실행 시각으로 보인다 — 계획서 Step 8 기본안).
 *
 * 푸는 모양: 매일 HH:mm · 매월 D일 HH:mm · 분기 첫날 HH:mm · `1·7월 1일 HH:mm`(월 목록).
 * 초는 0, 분·시는 숫자 하나, 요일은 `*`/`?` 일 때만 푼다.
 */
export function describeCron(cron: string | null | undefined): string | null {
  if (!cron) return null
  const f = cron.trim().split(/\s+/)
  if (f.length !== 6) return null
  const [sec, min, hour, dom, mon, dow] = f
  if (sec !== '0' || !isNum(min) || !isNum(hour)) return null
  if (dow !== '*' && dow !== '?') return null
  const m = Number(min)
  const h = Number(hour)
  if (m > 59 || h > 23) return null
  const hm = `${pad2(h)}:${pad2(m)}`

  if ((dom === '*' || dom === '?') && mon === '*') return `매일 ${hm}`
  if (!isNum(dom)) return null
  const d = Number(dom)
  if (d < 1 || d > 31) return null
  if (mon === '*') return `매월 ${d}일 ${hm}`
  if (d === 1 && QUARTER_MONTHS.includes(mon)) return `분기 첫날 ${hm}`
  if (/^\d+(,\d+)*$/.test(mon)) {
    const months = mon.split(',').map(Number)
    if (months.some((x) => x < 1 || x > 12)) return null
    return `${months.join('·')}월 ${d}일 ${hm}`
  }
  return null
}

/** cron 에서 시·분을 꺼낸다(프리셋 시각 입력의 초기값). 숫자가 아니면 null. */
export function cronHourMinute(cron: string | null | undefined): string | null {
  if (!cron) return null
  const f = cron.trim().split(/\s+/)
  if (f.length !== 6 || !isNum(f[1]) || !isNum(f[2])) return null
  return `${pad2(Number(f[2]))}:${pad2(Number(f[1]))}`
}

export type CronPreset = 'daily' | 'monthly' | 'quarterly'

/** 프리셋 → cron. `hm` 은 `HH:mm`. */
export function presetCron(preset: CronPreset, hm: string): string {
  const [hh, mm] = hm.split(':').map((x) => Number(x))
  const h = Number.isFinite(hh) ? hh : 0
  const m = Number.isFinite(mm) ? mm : 0
  if (preset === 'daily') return `0 ${m} ${h} * * *`
  if (preset === 'monthly') return `0 ${m} ${h} 1 * *`
  return `0 ${m} ${h} 1 1,4,7,10 *`
}

/** 화면에 쌓는 로그 상한(문자 수). 넘으면 앞부분을 줄 단위로 버린다. */
export const LOG_MAX_CHARS = 2 * 1024 * 1024

/**
 * 로그 이어붙이기. 상한을 넘으면 **앞부분을 버리고** 잘린 첫 줄은 통째로 뺀다(줄 중간부터 보이지 않게).
 * 반환 `trimmed` 는 이번 호출에서 앞부분을 버렸는지다.
 */
export function appendLog(
  prev: string,
  chunk: string,
  max: number = LOG_MAX_CHARS,
): { text: string; trimmed: boolean } {
  const joined = prev + chunk
  if (joined.length <= max) return { text: joined, trimmed: false }
  let cut = joined.length - max
  const nl = joined.indexOf('\n', cut)
  cut = nl >= 0 ? nl + 1 : cut
  return { text: joined.slice(cut), trimmed: true }
}
