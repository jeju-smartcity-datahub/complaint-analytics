import { useState } from 'react'
import Modal from '../common/Modal'
import Switch from '../common/Switch'
import {
  batchOpsErrorText,
  updateBatchSchedule,
  type BatchOpsJob,
  type BatchOpsScheduleResult,
} from '../../api/batchOps'
import { formatDotDateTime } from '../../lib/datetime'
import { cronHourMinute, describeCron, presetCron, type CronPreset } from '../../lib/batchOpsView'

/*
 * 스케줄 편집 모달.
 *
 * cron 입력(Spring 6필드) + 프리셋(매일 HH:mm · 매월 1일 · 분기 첫날) + 사용 토글 → 저장(PUT).
 * 저장 응답의 `nextRuns`(다음 3회)를 그대로 보인다 — 다음 실행 계산은 배치가 한다(프론트가 cron 을 풀지 않는다).
 * 🔴 `nextRuns` 는 UTC ISO 라 `formatDotDateTime`(Date 파싱 + 로컬 getter)으로만 그린다.
 * 🔴 env 차단(`blockedByEnv`)은 저장은 되지만 등록되지 않는다 — 배지·문구로 알린다.
 */

const PRESETS: { key: CronPreset; label: string }[] = [
  { key: 'daily', label: '매일' },
  { key: 'monthly', label: '매월 1일' },
  { key: 'quarterly', label: '분기 첫날' },
]

const STATE_LABEL: Record<string, string> = {
  REGISTERED: '등록됨',
  DISABLED: '사용 안 함',
  BLOCKED: '환경설정으로 차단됨',
  INVALID_CRON: 'cron 형식 오류',
  NO_TARGET: '실행 대상 없음',
  NO_ROW: '스케줄 행 없음',
}

interface BatchScheduleModalProps {
  job: BatchOpsJob
  onClose: () => void
  onSaved: () => void
}

export default function BatchScheduleModal({ job, onClose, onSaved }: BatchScheduleModalProps) {
  const sch = job.schedule
  const [cron, setCron] = useState(sch?.cronExpr ?? '')
  const [useOn, setUseOn] = useState(sch ? sch.useYn === 'Y' : false)
  const [hm, setHm] = useState(cronHourMinute(sch?.cronExpr) ?? '02:00')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<BatchOpsScheduleResult | null>(null)

  const desc = describeCron(cron)
  const blocked = result ? result.blockedByEnv : sch?.blockedByEnv === true

  const handleSave = async () => {
    if (busy || !cron.trim()) return
    setBusy(true)
    setError(null)
    try {
      const res = await updateBatchSchedule(job.jobNm, cron.trim(), useOn ? 'Y' : 'N')
      setResult(res)
      onSaved()
    } catch (e) {
      setResult(null)
      setError(batchOpsErrorText(e, '스케줄을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title={`${job.jobKorNm ?? job.jobNm} 스케줄`}
      variant="sm"
      onClose={onClose}
      footSplit
      footer={
        <>
          <button className="btn" type="button" style={{ height: 44 }} onClick={onClose}>
            닫기
          </button>
          <button
            className="btn-save"
            type="button"
            disabled={busy || !cron.trim()}
            onClick={() => {
              void handleSave()
            }}
          >
            {busy ? '저장 중…' : '저장'}
          </button>
        </>
      }
    >
      {blocked && (
        <p className="bo-banner" role="note">
          <span className="badge badge--pending">환경설정으로 차단됨</span>{' '}
          {sch?.envKey ? `${sch.envKey}=false` : '차단 스위치'} 라서 저장해도 스케줄이 등록되지
          않습니다.
        </p>
      )}

      <div className="form-field">
        <label className="form-field__label" htmlFor="bo-cron">
          cron (초 분 시 일 월 요일)
        </label>
        <input
          id="bo-cron"
          className="form-inp bo-mono"
          value={cron}
          placeholder="0 0 2 * * *"
          onChange={(e) => {
            setCron(e.target.value)
            setResult(null)
          }}
        />
        <span className="bo-sub">
          {desc ?? '설명할 수 없는 형식입니다 — 저장하면 다음 실행 시각으로 확인할 수 있습니다.'}
        </span>
      </div>

      <div className="form-field">
        <span className="form-field__label">프리셋</span>
        <div className="bo-actions">
          <input
            className="form-inp"
            type="time"
            aria-label="프리셋 시각"
            value={hm}
            style={{ height: 36 }}
            onChange={(e) => setHm(e.target.value)}
          />
          {PRESETS.map((p) => (
            <button
              key={p.key}
              type="button"
              className="btn-edit"
              onClick={() => {
                setCron(presetCron(p.key, hm || '00:00'))
                setResult(null)
              }}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>

      <div className="form-field">
        <span className="form-field__label">사용</span>
        <span className="bo-actions">
          <Switch
            checked={useOn}
            label="스케줄 사용"
            onChange={(next) => {
              setUseOn(next)
              setResult(null)
            }}
          />
          <span className="bo-sub">
            {useOn ? '사용 — 저장하면 바로 등록됩니다' : '사용 안 함 — 스케줄 실행이 멈춥니다'}
          </span>
        </span>
      </div>

      {error && (
        <p className="bo-error" role="alert">
          {error}
        </p>
      )}

      {result && (
        <div className="bo-result">
          <p className="bo-text" style={{ margin: 0 }}>
            저장했습니다 · 상태 <b>{STATE_LABEL[result.state] ?? result.state}</b>
          </p>
          {result.nextRuns.length > 0 ? (
            <ul className="bo-list">
              {result.nextRuns.map((r) => (
                <li key={r}>다음 실행 {formatDotDateTime(r)}</li>
              ))}
            </ul>
          ) : (
            <p className="bo-sub">등록되지 않아 다음 실행이 없습니다.</p>
          )}
        </div>
      )}

      {sch?.mdfrId && (
        <p className="bo-sub">
          마지막 변경 {sch.mdfrId} · {formatDotDateTime(sch.mdfcnDt)}
        </p>
      )}
    </Modal>
  )
}
