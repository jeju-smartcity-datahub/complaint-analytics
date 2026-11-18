import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { batchOpsError, batchOpsErrorText, fetchBatchLog } from '../../api/batchOps'
import { appendLog } from '../../lib/batchOpsView'
import { useVisiblePolling } from '../../lib/useVisiblePolling'

/*
 * 실행별 텍스트 로그 (TSK-4337 Step 8 · D7 ② · 실시간 로그).
 *
 * - 처음은 `tailKb=200`(마지막 200KB), 이후는 `fromByte=직전 nextByte` 로 **새 줄만** 2초마다 이어 붙인다.
 * - 응답 `jobRunning=false` 가 오면 폴링을 멈춘다. 실행이 DB 상 끝났어도 마지막 응답이 `jobRunning=true` 였다면
 *   한 번 더 받아 꼬리를 채운다.
 * - 🔴 404 `LOG_NOT_FOUND` — 실행이 **실행 중인 동안**은 "로그 대기 중"(첫 로그 전 · 404 에는 jobRunning 이 없다)으로 보고
 *   계속 폴링한다. "로그 없음 — 보존기간" 문구는 **끝난 실행의 404** 에만 쓴다.
 * - 화면에 쌓는 양은 `LOG_MAX_CHARS`(2M자) 상한 — 넘으면 앞부분을 줄 단위로 버린다.
 */

const POLL_MS = 2000
const TAIL_KB = 200

type Phase = 'loading' | 'waiting' | 'ok' | 'notfound' | 'error'

interface BatchLogViewProps {
  jobExecutionId: number
  /** DB 기준 실행 중인가 (상세 status). */
  running: boolean
}

export default function BatchLogView({ jobExecutionId, running }: BatchLogViewProps) {
  const [text, setText] = useState('')
  const [phase, setPhase] = useState<Phase>('loading')
  const [error, setError] = useState<string | null>(null)
  const [trimmed, setTrimmed] = useState(false)
  const [follow, setFollow] = useState(true)
  /** 마지막 로그 응답의 jobRunning. null = 아직 성공 응답이 없다. */
  const [lastJobRunning, setLastJobRunning] = useState<boolean | null>(null)

  const nextByteRef = useRef<number | null>(null)
  const inFlightRef = useRef(false)
  const runningRef = useRef(running)
  const preRef = useRef<HTMLPreElement>(null)

  useEffect(() => {
    runningRef.current = running
  }, [running])

  const load = useCallback(async () => {
    if (inFlightRef.current) return
    inFlightRef.current = true
    try {
      const from = nextByteRef.current
      const res = await fetchBatchLog(
        jobExecutionId,
        from == null ? { tailKb: TAIL_KB } : { fromByte: from },
      )
      const chunk = res.content ?? ''
      if (from == null) {
        const r = appendLog('', chunk)
        setText(r.text)
        if (r.trimmed) setTrimmed(true)
      } else if (chunk) {
        setText((prev) => {
          const r = appendLog(prev, chunk)
          if (r.trimmed) setTrimmed(true)
          return r.text
        })
      }
      if (res.nextByte != null) nextByteRef.current = res.nextByte
      else if (from == null) nextByteRef.current = null
      setLastJobRunning(res.jobRunning === true)
      setPhase('ok')
      setError(null)
    } catch (e) {
      const { status } = batchOpsError(e)
      if (status === 404) {
        // 성공 응답을 받은 뒤의 404 는 없을 일이지만, 받은 본문은 지우지 않는다.
        if (nextByteRef.current == null) setPhase(runningRef.current ? 'waiting' : 'notfound')
        if (!runningRef.current) setLastJobRunning(false)
      } else {
        setPhase((p) => (p === 'ok' ? p : 'error'))
        setError(batchOpsErrorText(e, '로그를 불러오지 못했습니다.'))
      }
    } finally {
      inFlightRef.current = false
    }
  }, [jobExecutionId])

  useEffect(() => {
    void load()
  }, [load])

  // 대기 중에 실행이 끝났으면 한 번 더 확인해 "로그 없음" 으로 확정하거나 본문을 받는다.
  useEffect(() => {
    if (!running && phase === 'waiting') void load()
  }, [running, phase, load])

  const polling = phase !== 'notfound' && (running || lastJobRunning === true)
  useVisiblePolling(load, polling ? POLL_MS : null)

  useLayoutEffect(() => {
    if (follow && preRef.current) preRef.current.scrollTop = preRef.current.scrollHeight
  }, [text, follow])

  return (
    <div className="bo-log">
      <div className="bo-log__bar">
        <span className="bo-sub" style={{ margin: 0 }}>
          {phase === 'loading' && '로그를 불러오는 중...'}
          {phase === 'waiting' && '로그 대기 중 — 첫 로그가 쓰이면 이어서 보입니다.'}
          {phase === 'ok' && (polling ? '실시간으로 이어 받는 중 (2초 간격)' : '마지막 200KB 기준')}
          {phase === 'error' && '로그를 불러오지 못했습니다.'}
          {trimmed && ' · 화면 상한을 넘어 앞부분을 생략했습니다'}
        </span>
        <label className="bo-check">
          <input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} />맨
          아래 따라가기
        </label>
      </div>
      {error && (
        <p className="bo-error" role="alert">
          {error}
        </p>
      )}
      {phase === 'notfound' ? (
        <p className="bo-empty">
          로그 없음 — 로그 기능 도입 전 실행이거나 보존기간(90일)이 지났습니다.
        </p>
      ) : (
        <pre ref={preRef} className="bo-log__pre">
          {text || (phase === 'ok' ? '(빈 로그)' : '')}
        </pre>
      )}
    </div>
  )
}
