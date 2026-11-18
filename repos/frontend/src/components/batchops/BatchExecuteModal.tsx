import { useCallback, useEffect, useState } from 'react'
import Icon from '../Icon'
import Modal from '../common/Modal'
import BatchConfirmModal from './BatchConfirmModal'
import {
  abandonBatchExecution,
  batchOpsError,
  batchOpsErrorText,
  executeBatchJob,
  fetchBatchExecution,
  type BatchOpsExcnDetail,
  type BatchOpsJob,
} from '../../api/batchOps'
import { openDatePicker } from '../../lib/datePicker'
import { formatDotDateTime } from '../../lib/datetime'
import {
  formatCount,
  formatDuration,
  isRunningStatus,
  statusBadgeClass,
  statusLabel,
} from '../../lib/batchOpsView'
import { useVisiblePolling } from '../../lib/useVisiblePolling'
import BatchProgressText from './BatchProgressText'

/*
 * 배치 실행 모달 (TSK-4337 Step 8).
 *
 * 1) 카탈로그 파라미터 명세로 폼을 만든다(DATE = date input · ENUM = select). 비운 칸은 보내지 않고
 *    배치가 기본값(`defaultDesc`)을 쓴다.
 * 2) 202 를 받으면 그 실행을 **2초 간격**으로 폴링해 상태·진행 단계를 갱신하고, 끝나면 처리 건수를 보인다.
 * 3) 409 `JOB_ALREADY_RUNNING` 이면 메시지를 보이고, `zombie=true`(배치 프로세스에서 돌지 않는 실행)면
 *    [실패 처리] 버튼(abandon — 확인 대화상자)을 준다.
 */

const POLL_MS = 2000

interface BatchExecuteModalProps {
  job: BatchOpsJob
  onClose: () => void
  /** 실행 시작·실패 처리 뒤 목록을 다시 받게 한다. */
  onChanged: () => void
  /** 상세(이력) 열기. */
  onOpenExecution: (jobExecutionId: number) => void
}

export default function BatchExecuteModal({
  job,
  onClose,
  onChanged,
  onOpenExecution,
}: BatchExecuteModalProps) {
  const specs = job.params ?? []
  const [values, setValues] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** 409 로 받은 기존 실행 (좀비면 실패 처리 대상). */
  const [conflict, setConflict] = useState<{ jobExecutionId: number; zombie: boolean } | null>(null)
  const [abandonOpen, setAbandonOpen] = useState(false)
  const [abandonBusy, setAbandonBusy] = useState(false)
  const [abandonError, setAbandonError] = useState<string | null>(null)

  const [execId, setExecId] = useState<number | null>(null)
  const [detail, setDetail] = useState<BatchOpsExcnDetail | null>(null)
  const [pollError, setPollError] = useState<string | null>(null)

  const running = execId !== null && (detail === null || isRunningStatus(detail.status))

  const missingRequired = specs.some((p) => p.required && !values[p.name])

  const loadDetail = useCallback(async () => {
    if (execId === null) return
    try {
      const d = await fetchBatchExecution(execId)
      setDetail(d)
      setPollError(null)
      if (!isRunningStatus(d.status)) onChanged()
    } catch (e) {
      setPollError(batchOpsErrorText(e, '실행 상태를 불러오지 못했습니다. 계속 다시 시도합니다.'))
    }
  }, [execId, onChanged])

  // 202 직후 첫 조회
  useEffect(() => {
    if (execId !== null) void loadDetail()
    // execId 가 정해진 순간 한 번만 — 이후는 폴링이 맡는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [execId])

  useVisiblePolling(loadDetail, running ? POLL_MS : null)

  const handleSubmit = async () => {
    if (busy || missingRequired) return
    setBusy(true)
    setError(null)
    setConflict(null)
    const params: Record<string, string> = {}
    for (const p of specs) {
      const v = values[p.name]?.trim()
      if (v) params[p.name] = v
    }
    try {
      const res = await executeBatchJob(job.jobNm, params)
      setExecId(res.jobExecutionId)
      onChanged()
    } catch (e) {
      const { status, body } = batchOpsError(e)
      if (status === 409 && body.code === 'JOB_ALREADY_RUNNING' && body.jobExecutionId != null) {
        setConflict({ jobExecutionId: body.jobExecutionId, zombie: body.zombie === true })
      }
      setError(batchOpsErrorText(e, '실행을 요청하지 못했습니다. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setBusy(false)
    }
  }

  const handleAbandon = async () => {
    if (!conflict) return
    setAbandonBusy(true)
    setAbandonError(null)
    try {
      await abandonBatchExecution(conflict.jobExecutionId)
      setAbandonOpen(false)
      setConflict(null)
      setError(
        `실행 #${conflict.jobExecutionId} 을(를) 실패 처리했습니다. 다시 실행할 수 있습니다.`,
      )
      onChanged()
    } catch (e) {
      setAbandonError(batchOpsErrorText(e, '실패 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setAbandonBusy(false)
    }
  }

  const title = `${job.jobKorNm ?? job.jobNm} 실행`
  const countLabel = job.countLabel ?? '처리'

  const footer =
    execId === null ? (
      <>
        <button className="btn" type="button" style={{ height: 44 }} onClick={onClose}>
          취소
        </button>
        <button
          className="btn-save"
          type="button"
          disabled={busy || missingRequired}
          onClick={() => {
            void handleSubmit()
          }}
        >
          {busy ? '요청 중…' : '실행'}
        </button>
      </>
    ) : (
      <>
        <button
          className="btn"
          type="button"
          style={{ height: 44 }}
          onClick={() => onOpenExecution(execId)}
        >
          상세·로그 보기
        </button>
        <button className="btn-save" type="button" onClick={onClose}>
          닫기
        </button>
      </>
    )

  return (
    <>
      <Modal title={title} variant="sm" onClose={onClose} footSplit footer={footer}>
        {execId === null ? (
          <>
            {job.description && (
              <p className="bo-sub" style={{ marginTop: 0 }}>
                {job.description}
              </p>
            )}
            {specs.length === 0 && <p className="bo-text">이 잡은 실행 파라미터가 없습니다.</p>}
            {specs.map((p) => {
              const id = `bo-param-${p.name}`
              const label = p.description ? `${p.description} (${p.name})` : p.name
              return (
                <div className="form-field" key={p.name}>
                  <label className="form-field__label" htmlFor={id}>
                    {label}
                    {p.required && ' *'}
                  </label>
                  {p.type === 'ENUM' ? (
                    <div className="ds-select" style={{ width: '100%' }}>
                      <select
                        id={id}
                        value={values[p.name] ?? ''}
                        onChange={(e) => setValues((v) => ({ ...v, [p.name]: e.target.value }))}
                      >
                        <option value="">기본값</option>
                        {p.options.map((o) => (
                          <option key={o} value={o}>
                            {o}
                          </option>
                        ))}
                      </select>
                      <span className="ds-select__ic">
                        <Icon name="chevron-down" size={18} />
                      </span>
                    </div>
                  ) : (
                    <input
                      id={id}
                      className="form-inp"
                      type={p.type === 'DATE' ? 'date' : 'text'}
                      value={values[p.name] ?? ''}
                      onClick={p.type === 'DATE' ? openDatePicker : undefined}
                      onChange={(e) => setValues((v) => ({ ...v, [p.name]: e.target.value }))}
                    />
                  )}
                  {p.defaultDesc && <span className="bo-sub">비우면 기본값: {p.defaultDesc}</span>}
                </div>
              )
            })}
            {job.chainNext && (
              <p className="bo-sub">운영 화면 수동 실행에는 연쇄 잡이 이어서 돌지 않습니다.</p>
            )}
            {error && (
              <p className="bo-error" role="alert">
                {error}
              </p>
            )}
            {conflict?.zombie && (
              <p className="bo-text">
                실행 #{conflict.jobExecutionId} 은(는) 배치 프로세스에서 돌고 있지 않습니다(비정상
                종료로 남은 실행).{' '}
                <button
                  className="mt-act mt-act--del"
                  type="button"
                  onClick={() => setAbandonOpen(true)}
                >
                  실패 처리
                </button>
              </p>
            )}
          </>
        ) : (
          <div className="bo-kv">
            <span className="bo-kv__k">실행</span>
            <span>#{execId}</span>
            <span className="bo-kv__k">상태</span>
            <span>
              <span className={statusBadgeClass(detail?.status ?? 'STARTING')}>
                {statusLabel(detail?.status ?? 'STARTING')}
              </span>
            </span>
            {detail?.progress && (
              <>
                <span className="bo-kv__k">진행</span>
                <span>
                  <BatchProgressText progress={detail.progress} />
                </span>
              </>
            )}
            <span className="bo-kv__k">시작</span>
            <span>{formatDotDateTime(detail?.startTime) || '-'}</span>
            {detail && !isRunningStatus(detail.status) && (
              <>
                <span className="bo-kv__k">소요</span>
                <span>{formatDuration(detail.durationMs)}</span>
                <span className="bo-kv__k">{countLabel}</span>
                <span>{formatCount(detail.writeCnt)}건</span>
              </>
            )}
            {detail &&
              !isRunningStatus(detail.status) &&
              (detail.failureMessage || detail.exitMessage) && (
                <>
                  <span className="bo-kv__k">메시지</span>
                  <span className="bo-msg">{detail.failureMessage || detail.exitMessage}</span>
                </>
              )}
            {pollError && (
              <>
                <span className="bo-kv__k" />
                <span className="bo-error" role="alert">
                  {pollError}
                </span>
              </>
            )}
          </div>
        )}
      </Modal>

      {abandonOpen && conflict && (
        <BatchConfirmModal
          title="실패 처리"
          message={`실행 #${conflict.jobExecutionId} 을(를) 실패(FAILED)로 정리합니다. 실제로 돌고 있지 않은 실행에만 쓰세요.`}
          confirmLabel="실패 처리"
          busy={abandonBusy}
          error={abandonError}
          onCancel={() => setAbandonOpen(false)}
          onConfirm={() => {
            void handleAbandon()
          }}
        />
      )}
    </>
  )
}
