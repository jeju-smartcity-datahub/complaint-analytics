import type { BatchOpsLive, BatchOpsProgress } from '../../api/batchOps'
import { formatCount } from '../../lib/batchOpsView'

/*
 * 진행 단계 한 줄 — `2/3 지오코딩 · 1,200건 처리`.
 *
 * - 순번 = 완료 Step 수 + 1(현재 Step 이 있을 때). 전체 Step 수는 카탈로그에서 오므로 배치가 꺼져 있으면 `n / ?`.
 * - 처리 건수는 **현재 Step** 의 쓰기 수(없으면 읽기 수)다. Spring Batch 가 청크 커밋마다 갱신하므로
 *   지오코딩 API 왕복처럼 청크 안에서 오래 걸리는 구간은 숫자가 멈춰 보인다 — 정상이다.
 * - 🆕 `progress.live` 가 있으면(민원 수집) 그 아래에 **API 수집 → 지오코딩 → DB 적재** 3줄을 더 그린다.
 *   세 단계는 100건 청크마다 번갈아 돌아 "지금 어느 단계" 가 없다 — 단계마다 건수로 보인다.
 *   API 수집·지오코딩은 청크 안에서도 늘고 DB 적재는 커밋 때만 늘어 잠깐 차이가 벌어지는 것이 정상이다.
 */
export default function BatchProgressText({ progress }: { progress: BatchOpsProgress }) {
  const n = progress.cmptnStepCnt + (progress.currentStep ? 1 : 0)
  const total = progress.totalStepCnt ?? '?'
  const name = progress.currentStepKorNm ?? progress.currentStep ?? '단계 전환 중'
  const cnt = progress.writeCount ?? progress.readCount
  const live = progress.live
  return (
    <span className="bo-progress">
      {n}/{total} {name}
      {!live && cnt != null && ` · ${formatCount(cnt)}건 처리`}
      {live && <BatchLiveStages live={live} writeCount={progress.writeCount} />}
    </span>
  )
}

function BatchLiveStages({ live, writeCount }: { live: BatchOpsLive; writeCount: number | null }) {
  const day =
    live.dayCnt != null && live.dayCnt > 0
      ? `일자 ${live.dayIdx ?? 0}/${live.dayCnt}${live.crntYmd ? ` (${live.crntYmd})` : ''}`
      : null
  const geoDetail = [
    live.geocodeCacheHits != null && `캐시 ${formatCount(live.geocodeCacheHits)}`,
    live.geocodeApiLookups != null && `API ${formatCount(live.geocodeApiLookups)}`,
    !!live.skipGeocodeFailed && `실패 ${formatCount(live.skipGeocodeFailed)}`,
    !!live.skipEmdUnresolved && `행정동 미해석 ${formatCount(live.skipEmdUnresolved)}`,
    !!live.filteredTransient && `일시 장애 ${formatCount(live.filteredTransient)}`,
  ].filter(Boolean)
  return (
    <>
      {day && <span className="bo-sub"> · {day}</span>}
      <span className="bo-stages">
        <span className="bo-stage">
          <span className="bo-stage__k">API 수집</span>
          <span>{formatCount(live.fetchCnt ?? 0)}건</span>
        </span>
        <span className="bo-stage">
          <span className="bo-stage__k">지오코딩</span>
          <span>
            {formatCount(live.prcsCnt ?? 0)}건
            {geoDetail.length > 0 && <span className="bo-sub"> ({geoDetail.join(' · ')})</span>}
          </span>
        </span>
        <span className="bo-stage">
          <span className="bo-stage__k">DB 적재</span>
          <span>{formatCount(writeCount ?? 0)}건</span>
        </span>
      </span>
    </>
  )
}
