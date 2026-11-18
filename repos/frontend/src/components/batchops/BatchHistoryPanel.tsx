import { useEffect, useState } from 'react'
import Icon from '../Icon'
import Pager from '../common/Pager'
import { batchOpsErrorText, fetchBatchExecutions, type BatchOpsExcn } from '../../api/batchOps'
import type { PageResponse } from '../../api/reports'
import { formatDotDateTime } from '../../lib/datetime'
import {
  excnSeLabel,
  formatCount,
  formatDuration,
  STATUS_FILTERS,
  statusBadgeClass,
  statusLabel,
} from '../../lib/batchOpsView'

/*
 * 실행 이력 패널 — 잡 하나의 실행 목록(최신순 · 10행 페이저 · 상태 필터).
 * 행을 누르면 상세 모달(Step 별 표·파라미터·메시지·텍스트 로그)을 연다.
 * `refreshToken` 이 바뀌면(그 잡의 마지막 실행 ID·상태가 바뀌면) 현재 페이지를 다시 받는다.
 */

const PER_PAGE = 10
const COL_COUNT = 8

interface BatchHistoryPanelProps {
  jobNm: string
  jobKorNm: string
  countLabel: string
  refreshToken: string
  onOpenExecution: (jobExecutionId: number) => void
  onClose: () => void
}

export default function BatchHistoryPanel({
  jobNm,
  jobKorNm,
  countLabel,
  refreshToken,
  onOpenExecution,
  onClose,
}: BatchHistoryPanelProps) {
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<PageResponse<BatchOpsExcn> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  // 잡·필터가 바뀌면 첫 페이지로
  useEffect(() => {
    setPage(0)
  }, [jobNm, status])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetchBatchExecutions({ jobNm, status: status || undefined, page, size: PER_PAGE })
      .then((res) => {
        if (cancelled) return
        setData(res)
        setError(null)
      })
      .catch((e) => {
        if (cancelled) return
        setError(batchOpsErrorText(e, '실행 이력을 불러오지 못했습니다.'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [jobNm, status, page, reloadKey, refreshToken])

  const rows = data?.content ?? []

  return (
    <div className="card bo-card">
      <div className="bo-card__head">
        <h2 className="card-head__title">
          실행 이력 · {jobKorNm} <span className="bo-sub bo-mono">{jobNm}</span>
        </h2>
        <div className="bo-actions">
          <div className="ds-select">
            <select
              aria-label="상태 필터"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">전체 상태</option>
              {STATUS_FILTERS.map((s) => (
                <option key={s} value={s}>
                  {statusLabel(s)}
                </option>
              ))}
            </select>
            <span className="ds-select__ic">
              <Icon name="chevron-down" size={18} />
            </span>
          </div>
          <button type="button" className="btn-edit" onClick={() => setReloadKey((k) => k + 1)}>
            새로 고침
          </button>
          <button type="button" className="btn-edit" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>

      {error && (
        <p className="bo-error" role="alert" style={{ padding: '0 var(--space-28)' }}>
          {error}
        </p>
      )}

      <div className="rt-wrap">
        <table className="rt">
          <caption className="sr-only">{jobKorNm} 실행 이력</caption>
          <thead>
            <tr>
              <th scope="col">실행</th>
              <th scope="col">상태</th>
              <th scope="col">시작</th>
              <th scope="col">소요</th>
              <th scope="col">{countLabel}</th>
              <th scope="col">실행구분</th>
              <th scope="col">실행자</th>
              <th scope="col">메시지</th>
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
            {!loading && rows.length === 0 && !error && (
              <tr>
                <td className="col-center" colSpan={COL_COUNT}>
                  실행 기록이 없습니다.
                </td>
              </tr>
            )}
            {rows.map((r) => (
              <tr
                key={r.jobExecutionId}
                className="bo-row--click"
                tabIndex={0}
                onClick={() => onOpenExecution(r.jobExecutionId)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') onOpenExecution(r.jobExecutionId)
                }}
              >
                <td className="bo-mono">#{r.jobExecutionId}</td>
                <td>
                  <span className={statusBadgeClass(r.status)}>{statusLabel(r.status)}</span>
                </td>
                <td className="rt-date">{formatDotDateTime(r.startTime ?? r.createTime) || '-'}</td>
                <td>{formatDuration(r.durationMs)}</td>
                <td>{formatCount(r.writeCnt)}</td>
                <td>
                  {excnSeLabel(r.excnSeCd)}
                  {r.subRslts.length > 0 && (
                    <span className="bo-sub"> · 이어서 {r.subRslts.length}건</span>
                  )}
                </td>
                <td>{r.excutrId ?? '-'}</td>
                <td className="bo-msg--cell">{r.failureMessage || r.exitMessage || ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {data && (
          <Pager total={data.totalElements} page={page} setPage={setPage} perPage={PER_PAGE} />
        )}
      </div>
    </div>
  )
}
