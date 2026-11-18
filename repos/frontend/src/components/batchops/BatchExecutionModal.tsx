import { useCallback, useEffect, useState } from 'react'
import Modal from '../common/Modal'
import BatchConfirmModal from './BatchConfirmModal'
import BatchLogView from './BatchLogView'
import BatchProgressText from './BatchProgressText'
import BatchSubRsltList from './BatchSubRsltList'
import {
  abandonBatchExecution,
  batchOpsErrorText,
  fetchBatchExecution,
  stopBatchExecution,
  type BatchOpsExcnDetail,
} from '../../api/batchOps'
import { formatDotDateTime } from '../../lib/datetime'
import {
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
 * 실행 상세 모달 — [실행 정보] Step 별 카운트 · 파라미터 · 메시지 · 지표 / [텍스트 로그] 실시간.
 * 실행 중이면 2초마다 상세를 다시 받는다(진행 단계 · Step 카운트). 실행 중이면 [중지], 좀비면 [실패 처리].
 */

const POLL_MS = 2000

/** 상세 지표 — 값이 있는 것만 보인다(잡마다 쓰는 칸이 다르다). */
const METRICS: { key: keyof BatchOpsExcnDetail; label: string }[] = [
  { key: 'skipGeocodeFailed', label: '지오코딩 실패 스킵' },
  { key: 'skipEmdUnresolved', label: '읍면동 미해석 스킵' },
  { key: 'filteredTransient', label: '일시 실패 제외' },
  { key: 'geocodeApiLookups', label: '지오코딩 API 호출' },
  { key: 'geocodeCacheHits', label: '지오코딩 캐시 히트' },
  { key: 'geocodePlaceRecoveries', label: '장소검색 폴백 복구' },
  { key: 'geocodeStripped', label: '주소 정제 후 재시도' },
  { key: 'emdContains', label: '공간매칭 포함' },
  { key: 'emdBuffer', label: '공간매칭 버퍼' },
  { key: 'emdNameFallback', label: '읍면동명 폴백' },
  { key: 'unclsfCnt', label: '미분류' },
]

type Tab = 'info' | 'log'

interface BatchExecutionModalProps {
  jobExecutionId: number
  /** 잡 이름 → 한글명 (연쇄 결과 표시용). */
  jobKorNm: (jobNm: string | null) => string
  /** 처리 건수 문구 (잡 카탈로그 countLabel). */
  countLabel: (jobNm: string) => string
  /** 배치 프로세스에서 실제로 실행 중인 실행 ID (카탈로그 — 못 받으면 null → 좀비 판정을 하지 않는다). */
  isProcessRunning: (jobExecutionId: number) => boolean | null
  /** 배치 연결이 없으면 명령 버튼을 막는다. */
  commandsEnabled: boolean
  initialTab?: Tab
  onClose: () => void
  onChanged: () => void
  onOpenExecution: (jobExecutionId: number) => void
}

export default function BatchExecutionModal({
  jobExecutionId,
  jobKorNm,
  countLabel,
  isProcessRunning,
  commandsEnabled,
  initialTab = 'info',
  onClose,
  onChanged,
  onOpenExecution,
}: BatchExecutionModalProps) {
  const [tab, setTab] = useState<Tab>(initialTab)
  const [detail, setDetail] = useState<BatchOpsExcnDetail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirm, setConfirm] = useState<'stop' | 'abandon' | null>(null)
  const [cmdBusy, setCmdBusy] = useState(false)
  const [cmdError, setCmdError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setDetail(await fetchBatchExecution(jobExecutionId))
      setLoadError(null)
    } catch (e) {
      setLoadError(batchOpsErrorText(e, '실행 상세를 불러오지 못했습니다.'))
    }
  }, [jobExecutionId])

  useEffect(() => {
    setDetail(null)
    void load()
  }, [load])

  const running = isRunningStatus(detail?.status)
  useVisiblePolling(load, running ? POLL_MS : null)

  const procRunning = isProcessRunning(jobExecutionId)
  // 백엔드 @JsonRawValue — 객체로 온다. 방어적으로 객체가 아니면 비운다(문자열·배열 등).
  const rawUnknown = detail?.unknownTypes as unknown
  const unknownEntries: [string, number][] =
    rawUnknown && typeof rawUnknown === 'object' && !Array.isArray(rawUnknown)
      ? Object.entries(rawUnknown as Record<string, number>)
      : []
  const zombie = isZombieExecution(detail?.status, detail?.startTime, procRunning)

  const runCommand = async () => {
    if (!confirm) return
    setCmdBusy(true)
    setCmdError(null)
    try {
      if (confirm === 'stop') {
        const res = await stopBatchExecution(jobExecutionId)
        setNotice(
          res.zombie === true ? ZOMBIE_STOP_NOTICE : '중지 요청됨 — 현재 청크가 끝나면 멈춥니다.',
        )
      } else {
        await abandonBatchExecution(jobExecutionId)
        setNotice('실패 처리했습니다.')
      }
      setConfirm(null)
      onChanged()
      void load()
    } catch (e) {
      setCmdError(batchOpsErrorText(e, '요청하지 못했습니다. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setCmdBusy(false)
    }
  }

  const closeConfirm = useCallback(() => setConfirm(null), [])

  const title = detail
    ? `${jobKorNm(detail.jobNm)} · 실행 #${jobExecutionId}`
    : `실행 #${jobExecutionId}`

  return (
    <>
      <Modal title={title} onClose={onClose}>
        <div className="segment" style={{ marginBottom: 16 }}>
          {(
            [
              ['info', '실행 정보'],
              ['log', '텍스트 로그'],
            ] as const
          ).map(([k, label]) => (
            <button
              key={k}
              type="button"
              className={`segment__btn${tab === k ? ' segment__btn--active' : ''}`}
              aria-pressed={tab === k}
              onClick={() => setTab(k)}
            >
              {label}
            </button>
          ))}
        </div>

        {loadError && (
          <p className="bo-error" role="alert">
            {loadError}
          </p>
        )}
        {notice && <p className="bo-text">{notice}</p>}

        {running && (
          <div className="bo-actions" style={{ marginBottom: 12 }}>
            {!zombie && (
              <button
                type="button"
                className="mt-act mt-act--del"
                disabled={!commandsEnabled || detail?.status === 'STOPPING'}
                onClick={() => setConfirm('stop')}
              >
                {detail?.status === 'STOPPING' ? '중지 요청됨' : '중지'}
              </button>
            )}
            {zombie && (
              <button
                type="button"
                className="mt-act mt-act--del"
                disabled={!commandsEnabled}
                onClick={() => setConfirm('abandon')}
              >
                실패 처리
              </button>
            )}
            {zombie && (
              <span className="bo-sub">
                배치 프로세스에서 돌고 있지 않은 실행입니다(비정상 종료로 남은 실행).
              </span>
            )}
          </div>
        )}

        {tab === 'log' && <BatchLogView jobExecutionId={jobExecutionId} running={running} />}

        {tab === 'info' && !detail && !loadError && <p className="bo-sub">불러오는 중...</p>}

        {tab === 'info' && detail && (
          <>
            <div className="bo-kv">
              <span className="bo-kv__k">상태</span>
              <span>
                <span className={statusBadgeClass(detail.status)}>
                  {statusLabel(detail.status)}
                </span>
                {detail.exitCode && detail.exitCode !== detail.status && (
                  <span className="bo-sub"> · 종료 코드 {detail.exitCode}</span>
                )}
              </span>
              {detail.progress && (
                <>
                  <span className="bo-kv__k">진행</span>
                  <span>
                    <BatchProgressText progress={detail.progress} />
                  </span>
                </>
              )}
              <span className="bo-kv__k">시작 ~ 종료</span>
              <span>
                {formatDotDateTime(detail.startTime) || '-'} ~{' '}
                {formatDotDateTime(detail.endTime) || (running ? '실행 중' : '-')}
              </span>
              <span className="bo-kv__k">소요</span>
              <span>{formatDuration(detail.durationMs)}</span>
              <span className="bo-kv__k">실행구분</span>
              <span>
                {excnSeLabel(detail.excnSeCd)}
                {detail.excutrId && ` · ${detail.excutrId}`}
                {detail.upExcnId != null && (
                  <>
                    {' · 앞 실행 '}
                    <button
                      type="button"
                      className="bo-link"
                      onClick={() => onOpenExecution(detail.upExcnId as number)}
                    >
                      #{detail.upExcnId}
                    </button>
                  </>
                )}
              </span>
              <span className="bo-kv__k">{countLabel(detail.jobNm)}</span>
              <span>
                {formatCount(detail.writeCnt)}건
                {detail.readCnt != null && ` (읽기 ${formatCount(detail.readCnt)})`}
              </span>
              {detail.runMode && (
                <>
                  <span className="bo-kv__k">실행 방식</span>
                  <span>{detail.runMode}</span>
                </>
              )}
              {detail.targetRange && (
                <>
                  <span className="bo-kv__k">대상 구간</span>
                  <span>{detail.targetRange}</span>
                </>
              )}
              {detail.hstryYn === 'N' && (
                <>
                  <span className="bo-kv__k">이력</span>
                  <span className="bo-sub">
                    실행 이력 행이 없습니다(이력 기록 실패) — 건수 칸이 비어 있을 수 있습니다.
                  </span>
                </>
              )}
            </div>

            {(detail.failureMessage || detail.exitMessage) && (
              <>
                <h3 className="bo-h3">메시지</h3>
                <pre className="bo-msg">{detail.failureMessage || detail.exitMessage}</pre>
              </>
            )}

            <h3 className="bo-h3">Step 별</h3>
            <div className="rt-wrap">
              <table className="rt">
                <caption className="sr-only">Step 별 카운트</caption>
                <thead>
                  <tr>
                    <th scope="col">Step</th>
                    <th scope="col">상태</th>
                    <th scope="col">읽기</th>
                    <th scope="col">쓰기</th>
                    <th scope="col">필터</th>
                    <th scope="col">스킵(읽기·처리·쓰기)</th>
                    <th scope="col">커밋</th>
                    <th scope="col">롤백</th>
                    <th scope="col">소요</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.steps.length === 0 && (
                    <tr>
                      <td className="col-center" colSpan={9}>
                        Step 실행 기록이 없습니다.
                      </td>
                    </tr>
                  )}
                  {detail.steps.map((s) => (
                    <tr key={s.stepExecutionId}>
                      <td>
                        <span className="rt-name">{s.stepKorNm ?? s.stepName}</span>
                        {s.stepKorNm && <div className="bo-sub bo-mono">{s.stepName}</div>}
                        {s.exitMessage && (
                          <div className="bo-msg bo-msg--cell">{s.exitMessage}</div>
                        )}
                      </td>
                      <td>
                        <span className={statusBadgeClass(s.status)}>{statusLabel(s.status)}</span>
                      </td>
                      <td>{formatCount(s.readCount)}</td>
                      <td>{formatCount(s.writeCount)}</td>
                      <td>{formatCount(s.filterCount)}</td>
                      <td>
                        {formatCount(s.readSkipCount)} · {formatCount(s.processSkipCount)} ·{' '}
                        {formatCount(s.writeSkipCount)}
                      </td>
                      <td>{formatCount(s.commitCount)}</td>
                      <td>{formatCount(s.rollbackCount)}</td>
                      <td>
                        {s.startTime && s.endTime
                          ? formatDuration(
                              new Date(s.endTime).getTime() - new Date(s.startTime).getTime(),
                            )
                          : '-'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <h3 className="bo-h3">파라미터</h3>
            {detail.params.length === 0 ? (
              <p className="bo-sub">파라미터가 없습니다.</p>
            ) : (
              <div className="bo-kv">
                {detail.params.map((p) => (
                  <ParamRow key={p.parameterName} name={p.parameterName} value={p.parameterValue} />
                ))}
              </div>
            )}

            {(METRICS.some((m) => detail[m.key] != null) || unknownEntries.length > 0) && (
              <>
                <h3 className="bo-h3">이력 지표</h3>
                <div className="bo-kv">
                  {METRICS.filter((m) => detail[m.key] != null).map((m) => (
                    <ParamRow
                      key={m.key}
                      name={m.label}
                      value={formatCount(detail[m.key] as number)}
                    />
                  ))}
                  {unknownEntries.length > 0 && (
                    <ParamRow
                      name="미분류 유형별"
                      value={unknownEntries.map(([k, v]) => `${k} ${formatCount(v)}건`).join(', ')}
                    />
                  )}
                </div>
              </>
            )}

            {detail.subRslts.length > 0 && (
              <>
                <h3 className="bo-h3">이어서 돈 결과</h3>
                <BatchSubRsltList
                  subRslts={detail.subRslts}
                  jobKorNm={jobKorNm}
                  onOpenExecution={onOpenExecution}
                />
              </>
            )}
          </>
        )}
      </Modal>

      {confirm && (
        <BatchConfirmModal
          title={confirm === 'stop' ? '실행 중지' : '실패 처리'}
          message={
            confirm === 'stop'
              ? '현재 묶음까지 처리하고 멈춥니다. 이어서 도는 잡은 실행되지 않습니다.'
              : `실행 #${jobExecutionId} 을(를) 실패(FAILED)로 정리합니다. 실제로 돌고 있지 않은 실행에만 쓰세요.`
          }
          confirmLabel={confirm === 'stop' ? '중지' : '실패 처리'}
          busy={cmdBusy}
          error={cmdError}
          onCancel={closeConfirm}
          onConfirm={() => {
            void runCommand()
          }}
        />
      )}
    </>
  )
}

function ParamRow({ name, value }: { name: string; value: string | null }) {
  return (
    <>
      <span className="bo-kv__k bo-mono">{name}</span>
      <span className="bo-mono">{value ?? '-'}</span>
    </>
  )
}
