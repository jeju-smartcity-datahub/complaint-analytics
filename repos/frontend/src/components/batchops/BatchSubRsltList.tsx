import type { BatchOpsSubRslt } from '../../api/batchOps'
import { formatDotDateTime } from '../../lib/datetime'
import {
  formatCount,
  formatDuration,
  statusBadgeClass,
  statusLabel,
  subSeLabel,
} from '../../lib/batchOpsView'

/*
 * 실행의 하위 결과 목록 (연쇄 서랍 · 실행 상세 공용).
 *
 * 🔴 연쇄 전용으로 만들지 않는다 — 응답 `subRslts` 는 범용 모양(`subSeCd` 로 갈린다)이고,
 *    후속 "민원 유입경로별 결과" 도 같은 칸으로 붙는다. 구분 라벨은 `subSeLabel` 한 곳에서 늘린다.
 *    `CHAIN` 일 때만 `subNm` 을 잡 이름으로 보고 한글명으로 바꾼다.
 */

interface BatchSubRsltListProps {
  subRslts: BatchOpsSubRslt[]
  jobKorNm: (jobNm: string | null) => string
  onOpenExecution: (jobExecutionId: number) => void
}

export default function BatchSubRsltList({
  subRslts,
  jobKorNm,
  onOpenExecution,
}: BatchSubRsltListProps) {
  return (
    <div className="rt-wrap">
      <table className="rt">
        <caption className="sr-only">하위 결과</caption>
        <thead>
          <tr>
            <th scope="col">구분</th>
            <th scope="col">이름</th>
            <th scope="col">상태</th>
            <th scope="col">시작</th>
            <th scope="col">소요</th>
            <th scope="col">처리 건수</th>
            <th scope="col">메시지</th>
          </tr>
        </thead>
        <tbody>
          {subRslts.map((s, i) => (
            <tr key={`${s.subSeCd}-${s.jobExecutionId ?? s.subNm ?? i}`}>
              <td>{subSeLabel(s.subSeCd)}</td>
              <td>
                {s.subSeCd === 'CHAIN' ? jobKorNm(s.subNm) : (s.subNm ?? '-')}
                {s.jobExecutionId != null && (
                  <>
                    {' '}
                    <button
                      type="button"
                      className="bo-link"
                      onClick={() => onOpenExecution(s.jobExecutionId as number)}
                    >
                      #{s.jobExecutionId}
                    </button>
                  </>
                )}
              </td>
              <td>
                <span className={statusBadgeClass(s.status)}>{statusLabel(s.status)}</span>
              </td>
              <td className="rt-date">{formatDotDateTime(s.startTime) || '-'}</td>
              <td>{formatDuration(s.durationMs)}</td>
              <td>{formatCount(s.writeCnt)}</td>
              <td className="bo-msg--cell">{s.message ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
