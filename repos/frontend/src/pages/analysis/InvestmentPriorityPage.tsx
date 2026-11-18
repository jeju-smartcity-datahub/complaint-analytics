import { useCallback, useEffect, useMemo, useState } from 'react'
import axios from 'axios'
import Breadcrumb from '../../components/common/Breadcrumb'
import TopbarActions from '../../components/common/TopbarActions'
import InvestPrrtStats from '../../components/analysis/InvestPrrtStats'
import InvestPrrtList from '../../components/analysis/InvestPrrtList'
import InvestPrrtMap from '../../components/analysis/InvestPrrtMap'
import InvestPrrtControls from '../../components/analysis/InvestPrrtControls'
import InvestPrrtFreshness from '../../components/analysis/InvestPrrtFreshness'
import CoefficientPopover, { CoefficientBanner } from '../../components/analysis/CoefficientPopover'
import {
  useAnalysisCoefficient,
  type CoeffRequest,
} from '../../lib/analysis/useAnalysisCoefficient'
import {
  DEFAULT_FILTER,
  PRRT_LVL_COLOR_VAR,
  buildCondLabel,
  buildFreshnessRows,
  buildLegendRows,
  buildPeriodLabel,
  buildWeightNote,
  selectDisplayLots,
  type InvestPrrtDataPrd,
  type InvestPrrtFilter,
} from '../../components/analysis/investPrrtView'
import {
  fetchInvestPrrt,
  type InvestPrrtGradeCriterion,
  type InvestPrrtLot,
  type InvestPrrtResult,
  type InvestPrrtWeight,
} from '../../api/investPrrt'

/*
 * 투자우선순위 보드 `/analysis/investment-priority` (TSK-4036 Step 8 · 9).
 *
 * 화면 구성(시안 그대로)
 *   `content`
 *     ├ `InvestPrrtStats`   `stat-row` 3카드 — 🔴 숫자는 전체(`smry`) 기준이다
 *     └ `section.gis-row`
 *         ├ `card map-card`  `InvestPrrtControls`(필터·정렬) + `map-body`(`InvestPrrtMap` · 범례)
 *         └ `side-col > InvestPrrtList`  순위 목록
 *
 * 🔴 **조회는 진입 시 1회다**(날짜 생략 = 오늘 기준 최근 1년). 필터·정렬·행 선택은 전부
 * 클라이언트에서 처리하고 다시 조회하지 않는다.
 *
 * 🔴 **표시 집합(`displayLots`)의 주인은 이 페이지 하나다** — 행정구역 필터 → 정렬 → 순위 부여 → 등급 필터 → 상위 N 을 여기서 한 번
 * 계산해 목록과 지도에 **같은 배열**을 내려준다. N 은 응답 `crtrVls.indctPkltCnt` 다.
 * 🔴 선택 상태(`selectedPkltId`)도 같다 — 목록과 지도가 각자 들면 서로 다른 주차장을 가리킨다.
 * 필터·정렬로 선택된 주차장이 표시 집합에서 빠지면 선택을 푼다.
 * 🔴 화면의 순위는 **표시 순위 `dspRnk`**(행정구역·정렬 결과 안에서의 순번 — 등급 필터로는 바뀌지 않는다)다 — 필터·정렬을 걸면 그 결과
 * 안에서 다시 매겨진다(사용자 결정 2026-10-14). `selectDisplayLots` 한 곳에서 붙이고, 목록과 지도는
 * 그 값을 읽기만 한다. 점수와 응답의 `rnk`(전 지역 순위)는 바뀌지 않는다.
 *
 * 분석 계수 조정(TSK-4325) — 내보내기 옆 버튼(`createPortal` + `fixed` 팝오버). `적용`·`초기화` 는 그 값으로
 * **재조회**한다(D6 — 이 화면의 조회 경로는 진입 조회 effect 하나라 요청을 상태로 넘겨 다시 돌린다).
 * 내보내기는 마지막으로 성공한 조회의 값(`ranRequest`)을 보낸다(D7).
 *
 * ⚠️ 한계 안내 문구·`warnings` 는 화면에 넣지 않는다(사용자 결정 2026-10-13 — 시안대로).
 * 라우팅·메뉴는 이미 있다(`navConfig` · `TB_MENU` M0305 · `RequireMenu`).
 */

const PAGE_TITLE = '공영주차장 투자·보강 우선순위 보드'

/*
 * 조회 전·실패 시의 빈 값. 🔴 매 렌더 `[]` 를 새로 만들면 지도의 핀 effect 가 렌더마다 돌아
 * 핀을 다시 그린다(열린 팝업이 닫힌다) — 같은 참조를 내려준다.
 */
const NO_LOTS: InvestPrrtLot[] = []
const NO_WEIGHTS: InvestPrrtWeight[] = []
const NO_CRITERIA: InvestPrrtGradeCriterion[] = []

/** 실패 문구. 서버 `message` 는 없을 수 있어 기본 문구로 받는다(`HotspotPage.errorText` 와 같은 관례). */
function errorText(e: unknown): string {
  const msg = axios.isAxiosError(e)
    ? (e.response?.data as { message?: string } | undefined)?.message
    : undefined
  // 🔴 `??` 가 아니라 `||` — 서버가 빈 문자열을 주면 오류가 "문구 없음" 이 되어 0건 안내로 보인다.
  return msg || '투자우선순위를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

export default function InvestmentPriorityPage() {
  const [result, setResult] = useState<InvestPrrtResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  /** `다시 시도` 를 누르면 값이 바뀌어 조회 effect 가 다시 돈다. */
  const [reloadKey, setReloadKey] = useState(0)

  /**
   * 분석 계수 일시 조정(TSK-4325). 초안·적용·마지막 실행값은 훅이 든다(React 상태만 — D8).
   * `runReq` 는 다음 조회에 실을 요청이다 — `적용`·`초기화` 가 새 객체를 넣어 조회 effect 를 다시 돌린다.
   * `null` = 진입 조회(설정값). 🔴 `setState` 직후 `appliedRequest` 는 이전 값이라 훅이 돌려준 요청을 그대로 넣는다.
   */
  const coeff = useAnalysisCoefficient('INVEST')
  const { markRan, ranRequest } = coeff
  const [runReq, setRunReq] = useState<CoeffRequest | null>(null)

  /**
   * 화면 필터·정렬. 🔴 조회 파라미터가 아니다 — 바뀌어도 재조회하지 않고 표시 집합만 다시 계산한다.
   * `InvestPrrtControls` 가 이 상태를 편집한다.
   */
  const [filter, setFilter] = useState<InvestPrrtFilter>(DEFAULT_FILTER)

  /** 선택된 주차장(목록 행 ↔ 지도 핀). */
  const [selectedPkltId, setSelectedPkltId] = useState<number | null>(null)
  /** 핀을 누르거나 팝업이 열렸을 때 — 선택만 바꾼다(지도는 이미 그 자리다). */
  const selectLot = useCallback((pkltId: number) => setSelectedPkltId(pkltId), [])
  /**
   * 목록 행을 누를 때마다 오른다 — 지도가 이 값의 변화를 보고 그 핀으로 이동해 팝업을 연다.
   * 선택 ID 만으로는 "팝업을 닫은 뒤 같은 행을 다시 누름" 을 지도가 알 수 없다(값이 그대로다).
   */
  const [focusSeq, setFocusSeq] = useState(0)
  const focusLot = useCallback((pkltId: number) => {
    setSelectedPkltId(pkltId)
    setFocusSeq((n) => n + 1)
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    fetchInvestPrrt({ vrblVls: runReq?.vrblVls })
      .then((res) => {
        if (cancelled) return
        // 성공했을 때만 마지막 실행값으로 남긴다(D7). 진입 조회(`null`)는 설정값이라 남길 것이 없다.
        if (runReq) markRan(runReq)
        setResult(res)
        setLoading(false)
      })
      .catch((e) => {
        if (cancelled) return
        setResult(null)
        setError(errorText(e))
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [reloadKey, runReq, markRan])

  /** 🔴 표시 집합 — 목록과 지도가 함께 쓰는 **하나의 배열**이다. */
  const displayLots = useMemo(
    () => (result ? selectDisplayLots(result.lots, filter, result.crtrVls.indctPkltCnt) : []),
    [result, filter],
  )

  // 선택된 주차장이 표시 집합에서 빠지면(필터·정렬·재조회) 선택을 푼다 — 목록에 없는 행을 가리키지 않게.
  useEffect(() => {
    if (selectedPkltId != null && !displayLots.some((l) => l.pkltId === selectedPkltId)) {
      setSelectedPkltId(null)
    }
  }, [displayLots, selectedPkltId])

  const allLots = result?.lots ?? NO_LOTS
  const weights = result?.weights ?? NO_WEIGHTS
  const gradeCriteria = result?.gradeCriteria ?? NO_CRITERIA
  /** 결과를 만든 조회의 계수 — 내보내기·부제 `(조정됨)` 의 근거(D7). 렌더마다 새 객체가 되지 않게 고정한다. */
  const ranReq = useMemo(() => ranRequest('INVEST'), [ranRequest])
  /**
   * 부제 `종합점수 기준 : … (조정됨)`. 🔴 `(조정됨)` 은 여기(화면 호출부)에서만 붙인다 —
   * `buildWeightNote` 는 보고서 템플릿도 써서 안에 넣으면 문서에 찍힌다(D10).
   */
  const weightNoteBase = buildWeightNote(weights)
  const weightNote =
    weightNoteBase && result && ranReq.weightAdjusted
      ? `${weightNoteBase} (조정됨)`
      : weightNoteBase
  const coeffBanner = coeff.banner('INVEST')

  /** 범례 줄 — 임계는 응답 `gradeCriteria` 에서 온다. */
  const legendRows = useMemo(() => buildLegendRows(gradeCriteria), [gradeCriteria])

  /**
   * 내보내기(= 보고서 저장) 대상 — 🔴 **행정구역 필터 + 결과를 만든 조회의 계수(`ranReq`, D7)뿐이다.**
   * 우선순위 세그먼트·정렬은 화면 표시용이라 싣지 않고, 저장되는 것도 표시 집합(상위 N)이 아니라 그 행정구역의 전체다.
   * 🔴 팝오버 초안·아직 조회하지 않은 적용값은 싣지 않는다.
   * 조회 전·조회 중·실패이거나 그 행정구역에 주차장이 없으면 `null`(버튼 비활성).
   */
  const exportCriteria = useMemo(() => {
    if (loading || error || !result) return null
    const { sggNm, emdCd } = filter
    const has = result.lots.some(
      (l) => (!sggNm || l.sggNm === sggNm) && (!emdCd || l.emdCd === emdCd),
    )
    return has ? { sggNm, emdCd, vrblVls: ranReq.vrblVls } : null
  }, [loading, error, result, filter, ranReq])

  /** 분석 기간 라벨 — 사고 카드와 목록 InfoTip 이 **같은 값**을 쓴다(TSK-4352). 조회 전·실패면 null. */
  const periodLabel = result ? buildPeriodLabel(result.bgngYmd, result.endYmd) : null

  /** 목록 카드 `조회조건 : {기간} · {행정구역} · 우선순위 {등급}` — 시안 `.prio-cond`(TSK-4352). 필터가 바뀌면 따라 바뀐다. */
  const condLabel = buildCondLabel(periodLabel, filter, allLots, gradeCriteria)

  /** 지표별 집계·산정 기간 — 목록 InfoTip 의 `집계·산정 기간 —` 줄(TSK-4352 Step 6-1). 조회 전·실패면 null. */
  const dataPrd = useMemo<InvestPrrtDataPrd | null>(
    () =>
      result
        ? {
            bgngYmd: result.bgngYmd,
            endYmd: result.endYmd,
            utztnBgngYm: result.utztnBgngYm,
            utztnEndYm: result.utztnEndYm,
            sftyCrtrYmd: result.sftyCrtrYmd,
          }
        : null,
    [result],
  )

  /** 헤더 `최근 갱신` 4행(TSK-4352 Step 6-2). 조회 전·실패면 null(`최근 갱신 —`). */
  const freshnessRows = useMemo(
    () => (result ? buildFreshnessRows(result, weights) : null),
    [result, weights],
  )

  /** 행 대신 보여 줄 문구. 0건은 "대상이 없다" 와 "조건에 맞는 것이 없다" 를 가른다. */
  const listNote = loading
    ? '불러오는 중…'
    : error
      ? error
      : !result || result.lots.length === 0
        ? '분석 대상 주차장이 없습니다.'
        : displayLots.length === 0
          ? '조건에 맞는 주차장이 없습니다.'
          : null

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="분석·시뮬레이션" page="투자우선순위 보드" />
          <h1 className="page-title">{PAGE_TITLE}</h1>
          {/* TSK-4352 D2 — 지표 원천의 자료 날짜(배치 실행일이 아니다 — 대시보드 것과 다른 컴포넌트다). */}
          <InvestPrrtFreshness rows={freshnessRows} />
        </div>
        <TopbarActions />
      </header>

      <div className="content">
        <InvestPrrtStats
          smry={result?.smry ?? null}
          loading={loading}
          periodLabel={periodLabel}
          lots={result?.lots ?? null}
        />

        <section className="gis-row">
          <div className="card map-card">
            <InvestPrrtControls
              lots={allLots}
              weights={weights}
              gradeCriteria={gradeCriteria}
              filter={filter}
              onChange={setFilter}
            />
            <div className="map-body">
              {/* 🔴 목록과 **같은 `displayLots`** 를 내려준다 — 지도가 따로 자르지 않는다. */}
              <InvestPrrtMap
                lots={displayLots}
                gradeCriteria={gradeCriteria}
                weights={weights}
                selectedPkltId={selectedPkltId}
                onSelect={selectLot}
                focusSeq={focusSeq}
              />
              {legendRows.length > 0 && (
                <div className="map-legend">
                  <div className="map-legend__title">투자 우선순위</div>
                  {legendRows.map((row) => (
                    <div className="row" key={row.prrtLvlCd}>
                      <span
                        className="dot"
                        style={{ background: PRRT_LVL_COLOR_VAR[row.prrtLvlCd] }}
                      />
                      {row.label}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="side-col">
            <InvestPrrtList
              lots={displayLots}
              gradeCriteria={gradeCriteria}
              // TSK-4242 — 산정 기준 InfoTip 의 반경·표시 수. 조회 전·실패면 null(그 줄을 생략한다).
              altmntRngVl={result?.crtrVls.altmntRngVl ?? null}
              indctPkltCnt={result?.crtrVls.indctPkltCnt ?? null}
              condLabel={condLabel}
              dataPrd={dataPrd}
              weightNote={weightNote}
              sort={filter.sort}
              weights={weights}
              selectedPkltId={selectedPkltId}
              onSelect={focusLot}
              note={listNote}
              exportCriteria={exportCriteria}
              onRetry={error && !loading ? () => setReloadKey((k) => k + 1) : undefined}
              coeffAction={
                <CoefficientPopover
                  coeff={coeff}
                  scope="INVEST"
                  placement="portal"
                  disabled={loading}
                  // `적용` = 그 값으로 재조회(D6). 실패하면 `markRan` 하지 않아 내보내기 값이 그대로다(D7).
                  onApply={setRunReq}
                />
              }
              coeffBanner={
                coeffBanner ? (
                  <CoefficientBanner
                    banner={coeffBanner}
                    disabled={loading}
                    onReset={() => setRunReq(coeff.reset('INVEST'))}
                  />
                ) : undefined
              }
            />
          </div>
        </section>
      </div>
    </>
  )
}
