import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import Breadcrumb from '../../components/common/Breadcrumb'
import Switch from '../../components/common/Switch'
import TopbarActions from '../../components/common/TopbarActions'
import Icon from '../../components/Icon'
import BatchConfirmModal from '../../components/batchops/BatchConfirmModal'
import BatchExecuteModal from '../../components/batchops/BatchExecuteModal'
import BatchExecutionModal from '../../components/batchops/BatchExecutionModal'
import BatchHistoryPanel from '../../components/batchops/BatchHistoryPanel'
import BatchProgressText from '../../components/batchops/BatchProgressText'
import BatchScheduleModal from '../../components/batchops/BatchScheduleModal'
import BatchSubRsltList from '../../components/batchops/BatchSubRsltList'
import {
  abandonBatchExecution,
  batchOpsErrorText,
  fetchBatchExecution,
  fetchBatchJobs,
  stopBatchExecution,
  updateBatchSchedule,
  type BatchOpsJob,
  type BatchOpsJobs,
  type BatchOpsProgress,
} from '../../api/batchOps'
import { formatDotDateTime } from '../../lib/datetime'
import {
  describeCron,
  excnSeLabel,
  formatCount,
  formatDuration,
  isRunningStatus,
  isZombieExecution,
  statusBadgeClass,
  statusLabel,
  ZOMBIE_STOP_NOTICE,
} from '../../lib/batchOpsView'
import { useVisiblePolling } from '../../lib/useVisiblePolling'

/*
 * 배치 운영 `/ops/batch` (TSK-4337 Step 8) — 숨김 메뉴 M0604.
 *
 * 🔴 사이드바(`navConfig.ts`·`Sidebar.tsx`)에 넣지 않는다(D1·R1) — 주소로만 들어오고, 접근은 `App.tsx` 의
 *    `RequireMenu path="/ops/batch"`(메뉴 경로 정확 일치 · `useMenuAccess()` 한 곳)가 막는다.
 *
 * 구성
 *   - 잡 목록 표(백엔드가 카탈로그를 병합한 순서 그대로) — 마지막 실행 · 처리 건수(countLabel) · 마지막 성공 ·
 *     스케줄(cron 설명 · 다음 실행 · env 차단 배지) · 사용 토글 · [실행] [중지]/[실패 처리] [스케줄] [이력]
 *   - 연쇄 서랍 — 머리 잡(chainNext 가 있는 잡) 아래에 마지막 실행의 하위 결과(`subRslts`)와 꼬리 잡 행을 들여 쓴다.
 *     하위 결과는 연쇄 전용으로 가정하지 않는다(후속 "경로별 결과" 도 같은 모양 — `BatchSubRsltList`).
 *   - 실행 중이면 진행 단계(`n/전체 단계명 · 처리 건수`) — 실행 상세의 `progress` 를 2초마다 받는다.
 *   - 이력 패널(잡 선택 시) → 상세 모달(Step 별 표 · 파라미터 · 메시지 · 텍스트 로그)
 *
 * 폴링 — 화면이 보일 때만(`useVisiblePolling`). 실행 중 잡이 있으면 2초, 없으면 10초.
 * 503(배치 불통)은 공용 인터셉터가 로그인으로 보내지 않는다(401 만 보낸다) — 화면이 문구로 보인다.
 * `catalogAvailable=false` 면 상단 경고 + 실행·스케줄 버튼을 막는다(조회는 DB 직접이라 그대로 보인다).
 */

const POLL_RUNNING_MS = 2000
const POLL_IDLE_MS = 10_000
const COL_COUNT = 7
const HEADERS = ['잡', '마지막 실행', '처리 건수', '마지막 성공', '스케줄', '사용', '관리'] as const

type ConfirmState = { kind: 'stop' | 'abandon'; jobExecutionId: number } | null

export default function BatchOpsPage() {
  const [data, setData] = useState<BatchOpsJobs | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  /** 실행 ID → 진행 단계 (실행 중인 마지막 실행만). */
  const [progress, setProgress] = useState<Record<number, BatchOpsProgress>>({})
  /** 접힌 머리 잡 (기본은 모두 펼침). */
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())

  const [historyJobNm, setHistoryJobNm] = useState<string | null>(null)
  const [execJob, setExecJob] = useState<BatchOpsJob | null>(null)
  const [schedJob, setSchedJob] = useState<BatchOpsJob | null>(null)
  const [openExec, setOpenExec] = useState<{ id: number; tab: 'info' | 'log' } | null>(null)

  const [confirm, setConfirm] = useState<ConfirmState>(null)
  const [cmdBusy, setCmdBusy] = useState(false)
  const [cmdError, setCmdError] = useState<string | null>(null)
  const [rowBusy, setRowBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [listError, setListError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const res = await fetchBatchJobs()
      setData(res)
      setLoadError(null)
      const runningIds = res.jobs
        .map((j) => j.lastExcn)
        .filter((e) => e && isRunningStatus(e.status))
        .map((e) => e!.jobExecutionId)
      if (runningIds.length === 0) {
        setProgress({})
      } else {
        const settled = await Promise.allSettled(runningIds.map((id) => fetchBatchExecution(id)))
        const next: Record<number, BatchOpsProgress> = {}
        settled.forEach((r, i) => {
          if (r.status === 'fulfilled' && r.value.progress) next[runningIds[i]] = r.value.progress
        })
        setProgress(next)
      }
    } catch (e) {
      setLoadError(batchOpsErrorText(e, '배치 잡 목록을 불러오지 못했습니다.'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const jobs = useMemo(() => data?.jobs ?? [], [data])
  const catalogOk = data?.catalogAvailable === true
  const anyRunning = jobs.some((j) => isRunningStatus(j.lastExcn?.status))
  useVisiblePolling(load, anyRunning ? POLL_RUNNING_MS : POLL_IDLE_MS)

  const byName = useMemo(() => new Map(jobs.map((j) => [j.jobNm, j])), [jobs])
  /** 꼬리 잡 이름 → 머리 잡 (머리·꼬리 둘 다 목록에 있을 때만). */
  const headOf = useMemo(() => {
    const m = new Map<string, BatchOpsJob>()
    for (const j of jobs) if (j.chainNext && byName.has(j.chainNext)) m.set(j.chainNext, j)
    return m
  }, [jobs, byName])

  const jobKorNm = useCallback(
    (jobNm: string | null) => (jobNm ? (byName.get(jobNm)?.jobKorNm ?? jobNm) : '-'),
    [byName],
  )
  const countLabelOf = useCallback(
    (jobNm: string) => byName.get(jobNm)?.countLabel ?? '처리 건수',
    [byName],
  )
  /** 배치 프로세스에서 실제로 도는가 — 카탈로그를 못 받았으면 null(판정하지 않는다). */
  const isProcessRunning = useCallback(
    (id: number): boolean | null => {
      if (!catalogOk) return null
      const lists = jobs
        .map((j) => j.runningJobExecutionIds)
        .filter((x): x is number[] => x != null)
      if (lists.length === 0) return null
      return lists.some((l) => l.includes(id))
    },
    [catalogOk, jobs],
  )

  const reload = useCallback(() => {
    void load()
  }, [load])

  const handleToggle = async (job: BatchOpsJob, next: boolean) => {
    if (!job.schedule || rowBusy) return
    setRowBusy(true)
    setListError(null)
    setNotice(null)
    try {
      await updateBatchSchedule(job.jobNm, job.schedule.cronExpr, next ? 'Y' : 'N')
      setNotice(`${job.jobKorNm ?? job.jobNm} 스케줄을 ${next ? '켰습니다' : '껐습니다'}.`)
      await load()
    } catch (e) {
      setListError(
        batchOpsErrorText(e, '스케줄 사용 여부를 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.'),
      )
    } finally {
      setRowBusy(false)
    }
  }

  const runConfirm = async () => {
    if (!confirm) return
    setCmdBusy(true)
    setCmdError(null)
    try {
      if (confirm.kind === 'stop') {
        const res = await stopBatchExecution(confirm.jobExecutionId)
        setNotice(
          res.zombie === true
            ? `실행 #${confirm.jobExecutionId}: ${ZOMBIE_STOP_NOTICE}`
            : `실행 #${confirm.jobExecutionId} 중지 요청됨 — 현재 청크가 끝나면 멈춥니다.`,
        )
      } else {
        await abandonBatchExecution(confirm.jobExecutionId)
        setNotice(`실행 #${confirm.jobExecutionId} 을(를) 실패 처리했습니다.`)
      }
      setConfirm(null)
      await load()
    } catch (e) {
      setCmdError(batchOpsErrorText(e, '요청하지 못했습니다. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setCmdBusy(false)
    }
  }

  const closeConfirm = useCallback(() => setConfirm(null), [])
  const openExecution = useCallback((id: number) => setOpenExec({ id, tab: 'info' }), [])

  const toggleCollapse = (jobNm: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(jobNm)) next.delete(jobNm)
      else next.add(jobNm)
      return next
    })

  const renderJobRow = (job: BatchOpsJob, child: boolean) => {
    const last = job.lastExcn
    const running = isRunningStatus(last?.status)
    const proc = last ? isProcessRunning(last.jobExecutionId) : null
    const zombie = last ? isZombieExecution(last.status, last.startTime, proc) : false
    const head = headOf.get(job.jobNm)
    const isHead = !child && !!job.chainNext && byName.has(job.chainNext)
    const expandable = isHead || (last?.subRslts.length ?? 0) > 0
    const open = expandable && !collapsed.has(job.jobNm)
    const sch = job.schedule
    const prog = last ? progress[last.jobExecutionId] : undefined
    const firstSub = last?.subRslts[0]

    return (
      <tr key={job.jobNm} className={child ? 'bo-row--child' : undefined}>
        <td>
          <span className="bo-jobcell">
            {expandable && !child ? (
              <button
                type="button"
                className="bo-caret"
                aria-expanded={open}
                aria-label={`${job.jobKorNm ?? job.jobNm} 이어서 도는 잡 ${open ? '접기' : '펼치기'}`}
                onClick={() => toggleCollapse(job.jobNm)}
              >
                <Icon name={open ? 'chevron-down' : 'chevron-right'} size={16} />
              </button>
            ) : (
              <span className="bo-caret bo-caret--blank">{child ? '↳' : ''}</span>
            )}
            <span>
              <span className="rt-name" title={job.description ?? undefined}>
                {job.jobKorNm ?? job.jobNm}
              </span>
              <span className="bo-sub bo-mono" style={{ display: 'block' }}>
                {job.jobNm}
              </span>
            </span>
          </span>
        </td>
        <td>
          {last ? (
            <>
              <span className={statusBadgeClass(last.status)}>{statusLabel(last.status)}</span>{' '}
              <button
                type="button"
                className="bo-link"
                onClick={() => openExecution(last.jobExecutionId)}
              >
                #{last.jobExecutionId}
              </button>
              <span className="bo-sub" style={{ display: 'block' }}>
                {formatDotDateTime(last.startTime ?? last.createTime) || '-'}
                {!running && ` · ${formatDuration(last.durationMs)}`} · {excnSeLabel(last.excnSeCd)}
              </span>
              {running && prog && (
                <span style={{ display: 'block' }}>
                  <BatchProgressText progress={prog} />
                </span>
              )}
              {zombie && (
                <span className="bo-sub bo-warn" style={{ display: 'block' }}>
                  배치 프로세스에서 돌고 있지 않습니다(비정상 종료로 남은 실행)
                </span>
              )}
              {isHead && firstSub && (
                <span className="bo-sub" style={{ display: 'block' }}>
                  → {jobKorNm(firstSub.subNm)} {statusLabel(firstSub.status)}
                </span>
              )}
            </>
          ) : (
            <span className="bo-sub">실행 기록 없음</span>
          )}
        </td>
        <td>
          {last && last.writeCnt != null ? (
            <>
              <span className="bo-sub">{job.countLabel ?? '처리'}</span>{' '}
              {formatCount(last.writeCnt)}건
            </>
          ) : (
            '-'
          )}
        </td>
        <td className="rt-date">{formatDotDateTime(job.lastSuccessDt) || '-'}</td>
        <td>
          {head && (
            <span
              className="bo-sub"
              style={{ display: 'block' }}
              title={head.chainCondition ?? undefined}
            >
              {head.jobKorNm ?? head.jobNm} 완료 시 자동
            </span>
          )}
          {sch ? (
            <>
              <span title={sch.cronExpr}>
                {describeCron(sch.cronExpr) ?? <span className="bo-mono">{sch.cronExpr}</span>}
              </span>
              {sch.blockedByEnv && (
                <span
                  className="badge badge--pending bo-badge"
                  title={sch.envKey ? `${sch.envKey}=false` : undefined}
                >
                  환경설정으로 차단됨
                </span>
              )}
              {sch.useYn !== 'Y' && (
                <span className="badge badge--neutral bo-badge">사용 안 함</span>
              )}
              {sch.registered && sch.nextRuns && sch.nextRuns.length > 0 && (
                <span className="bo-sub" style={{ display: 'block' }}>
                  다음 {formatDotDateTime(sch.nextRuns[0])}
                </span>
              )}
            </>
          ) : (
            !head && <span className="bo-sub">{job.schedulable === false ? '수동 전용' : '-'}</span>
          )}
        </td>
        <td className="col-center">
          {sch ? (
            <Switch
              checked={sch.useYn === 'Y'}
              disabled={!catalogOk || rowBusy}
              label={`${job.jobKorNm ?? job.jobNm} 스케줄 사용`}
              onChange={(next) => {
                void handleToggle(job, next)
              }}
            />
          ) : (
            '-'
          )}
        </td>
        <td>
          <span className="bo-actions">
            <button
              type="button"
              className="mt-act mt-act--save"
              disabled={!catalogOk || running}
              onClick={() => setExecJob(job)}
            >
              실행
            </button>
            {running && !zombie && (
              <button
                type="button"
                className="mt-act mt-act--del"
                disabled={!catalogOk || last?.status === 'STOPPING'}
                onClick={() => {
                  setCmdError(null)
                  setConfirm({ kind: 'stop', jobExecutionId: last!.jobExecutionId })
                }}
              >
                {last?.status === 'STOPPING' ? '중지 요청됨' : '중지'}
              </button>
            )}
            {zombie && (
              <button
                type="button"
                className="mt-act mt-act--del"
                disabled={!catalogOk}
                onClick={() => {
                  setCmdError(null)
                  setConfirm({ kind: 'abandon', jobExecutionId: last!.jobExecutionId })
                }}
              >
                실패 처리
              </button>
            )}
            {sch && job.schedulable !== false && (
              <button
                type="button"
                className="mt-act"
                disabled={!catalogOk}
                onClick={() => setSchedJob(job)}
              >
                스케줄
              </button>
            )}
            <button
              type="button"
              className="mt-act"
              aria-pressed={historyJobNm === job.jobNm}
              onClick={() => setHistoryJobNm(job.jobNm)}
            >
              이력
            </button>
          </span>
        </td>
      </tr>
    )
  }

  /** 머리 잡 아래 서랍 — 마지막 실행의 하위 결과 + 앞 잡 미완료 안내. */
  const renderDrawer = (job: BatchOpsJob) => {
    const last = job.lastExcn
    const subs = last?.subRslts ?? []
    const headStopped = last?.status === 'STOPPED' || last?.status === 'FAILED'
    return (
      <tr key={`${job.jobNm}-drawer`} className="bo-drawer">
        <td colSpan={COL_COUNT}>
          {job.chainNext && headStopped && (
            <p className="bo-sub bo-warn" style={{ marginTop: 0 }}>
              연쇄 잡은 앞 잡이 완료일 때만 실행됩니다 — 마지막 실행이 {statusLabel(last?.status)}{' '}
              로 끝나 이어서 돌지 않았습니다.
            </p>
          )}
          {subs.length > 0 ? (
            <>
              <p className="bo-sub" style={{ marginTop: 0 }}>
                마지막 실행 #{last?.jobExecutionId} 에 이어서 돈 결과
              </p>
              <BatchSubRsltList
                subRslts={subs}
                jobKorNm={jobKorNm}
                onOpenExecution={openExecution}
              />
            </>
          ) : (
            <p className="bo-sub" style={{ margin: 0 }}>
              마지막 실행에 이어서 돈 결과가 없습니다.
              {job.chainCondition && ` (${job.chainCondition})`}
            </p>
          )}
        </td>
      </tr>
    )
  }

  const historyJob = historyJobNm ? byName.get(historyJobNm) : undefined
  const topJobs = jobs.filter((j) => !headOf.has(j.jobNm))

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="운영" page="배치 운영" />
          <h1 className="page-title">배치 운영</h1>
          <p className="page-sub">배치 실행 현황 · 수동 실행 · 스케줄 · 실행 로그</p>
        </div>
        <TopbarActions />
      </header>

      <div className="content">
        {data && !catalogOk && (
          <p className="bo-banner" role="alert">
            배치 서버 정보를 받지 못했습니다 — 목록은 DB 기록으로 보이며 실행·스케줄 변경·중지는 할
            수 없습니다.
          </p>
        )}

        <div className="card bo-card">
          <div className="bo-card__head">
            <h2 className="card-head__title">배치 잡</h2>
            <span className="bo-actions">
              <span className="bo-sub">
                {anyRunning ? '실행 중 — 2초마다 갱신' : '10초마다 갱신'}
              </span>
              <button type="button" className="btn-edit" onClick={reload}>
                새로 고침
              </button>
            </span>
          </div>

          {(loadError || listError) && (
            <p className="bo-error" role="alert" style={{ padding: '0 var(--space-28)' }}>
              {listError ?? loadError}
            </p>
          )}
          {notice && (
            <p className="bo-text" style={{ padding: '0 var(--space-28)' }}>
              {notice}
            </p>
          )}

          <div className="rt-wrap">
            <table className="rt">
              <caption className="sr-only">배치 잡 목록</caption>
              <thead>
                <tr>
                  {HEADERS.map((h) => (
                    <th scope="col" key={h} className={h === '사용' ? 'col-center' : undefined}>
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {loading && !data && (
                  <tr>
                    <td className="col-center" colSpan={COL_COUNT}>
                      불러오는 중...
                    </td>
                  </tr>
                )}
                {!loading && !data && loadError && (
                  <tr>
                    <td className="col-center" colSpan={COL_COUNT}>
                      배치 잡 목록을 불러오지 못했습니다.{' '}
                      <button type="button" className="btn-edit" onClick={reload}>
                        다시 시도
                      </button>
                    </td>
                  </tr>
                )}
                {data && jobs.length === 0 && (
                  <tr>
                    <td className="col-center" colSpan={COL_COUNT}>
                      표시할 배치 잡이 없습니다.
                    </td>
                  </tr>
                )}
                {topJobs.map((job) => {
                  const tail = job.chainNext ? byName.get(job.chainNext) : undefined
                  const expandable = !!tail || (job.lastExcn?.subRslts.length ?? 0) > 0
                  const open = expandable && !collapsed.has(job.jobNm)
                  return (
                    <Fragment key={job.jobNm}>
                      {renderJobRow(job, false)}
                      {open && renderDrawer(job)}
                      {open && tail && renderJobRow(tail, true)}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>

        {historyJob && (
          <BatchHistoryPanel
            jobNm={historyJob.jobNm}
            jobKorNm={historyJob.jobKorNm ?? historyJob.jobNm}
            countLabel={historyJob.countLabel ?? '처리 건수'}
            refreshToken={`${historyJob.lastExcn?.jobExecutionId ?? ''}:${historyJob.lastExcn?.status ?? ''}`}
            onOpenExecution={openExecution}
            onClose={() => setHistoryJobNm(null)}
          />
        )}
      </div>

      {execJob && (
        <BatchExecuteModal
          job={execJob}
          onClose={() => setExecJob(null)}
          onChanged={reload}
          onOpenExecution={(id) => {
            setExecJob(null)
            setHistoryJobNm(execJob.jobNm)
            setOpenExec({ id, tab: 'log' })
          }}
        />
      )}

      {schedJob && (
        <BatchScheduleModal job={schedJob} onClose={() => setSchedJob(null)} onSaved={reload} />
      )}

      {openExec && (
        <BatchExecutionModal
          key={openExec.id}
          jobExecutionId={openExec.id}
          initialTab={openExec.tab}
          jobKorNm={jobKorNm}
          countLabel={countLabelOf}
          isProcessRunning={isProcessRunning}
          commandsEnabled={catalogOk}
          onClose={() => setOpenExec(null)}
          onChanged={reload}
          onOpenExecution={openExecution}
        />
      )}

      {confirm && (
        <BatchConfirmModal
          title={confirm.kind === 'stop' ? '실행 중지' : '실패 처리'}
          message={
            confirm.kind === 'stop'
              ? '현재 묶음까지 처리하고 멈춥니다. 이어서 도는 잡은 실행되지 않습니다.'
              : `실행 #${confirm.jobExecutionId} 을(를) 실패(FAILED)로 정리합니다. 실제로 돌고 있지 않은 실행에만 쓰세요.`
          }
          confirmLabel={confirm.kind === 'stop' ? '중지' : '실패 처리'}
          busy={cmdBusy}
          error={cmdError}
          onCancel={closeConfirm}
          onConfirm={() => {
            void runConfirm()
          }}
        />
      )}
    </>
  )
}
