import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import axios from 'axios'
import Breadcrumb from '../../components/common/Breadcrumb'
import TopbarActions from '../../components/common/TopbarActions'
import HotspotMap, { type HotspotMapFocus } from '../../components/analysis/HotspotMap'
import HotspotResultPanel, {
  type HotspotSelection,
} from '../../components/analysis/HotspotResultPanel'
import HotspotSettingPanel, {
  DEFAULT_PERIOD,
  type HotspotPeriodKey,
} from '../../components/analysis/HotspotSettingPanel'
import {
  fetchHotspotAnalysis,
  type HotspotAnalysisResult,
  type HotspotReportResponse,
} from '../../api/analysis'
import {
  useAnalysisCoefficient,
  type CoeffRequest,
} from '../../lib/analysis/useAnalysisCoefficient'
import type { HotspotExportCriteria } from '../../lib/analysis/hotspotExport'
import { fetchRegions, type RegionItem } from '../../api/regions'
import { firstDayOfMonth, formatPeriodLabel, lastDayOfMonth, toYmd, validateRange, type RangeError } from '../../lib/dateRange'

/*
 * 불법 주차 집중 구역 분석 `/analysis/hotspot`.
 *
 * 레이아웃은 시안의
 * `content--analysis > section.sim-stage`(전면 지도 + 오버레이 패널)를 그대로 옮겼다.
 * 스타일은 `src/styles/analysis.css`, 배경지도는 `HotspotMap`,
 * 조회는 `fetchHotspotAnalysis` 가 각각 담당한다.
 *
 * 🔴 **진입 직후에는 조회하지 않는다**. 시안은 결과가 열린 채 시작하지만(목데이터),
 * 실데이터에서는 "무엇을 분석한 결과인지 모르는 화면"이 된다 — `집중 구역 분석 실행` 을 눌러야 호출한다.
 * 그래서 조회를 거는 `useEffect` 가 없고, 지역 목록(select 채우기)과 분석 계수 설정(
 * `useAnalysisCoefficient` 훅이 받는다)만 마운트 시 한 번 받는다.
 *
 * 이 단계의 범위
 *   - ✅ 화면 셸(topbar + 스테이지 + 배경지도) · 설정 패널 · 실행/대기/실패/0건 안내
 *   - ✅ 조회 결과를 `HotspotMap` 에 넘겨 등급 마커·팝업·범례를 얹는다.
 *   - ✅ 우측 순위 결과 패널 + 리스트 ↔ 마커 양방향 연동.
 *        **선택 상태(`selectedRnk`)의 주인은 이 페이지 하나**다 — 목록과 지도가 각자 상태를 들면
 *        서로 다른 구역을 가리키게 된다.
 *   - ✅ 내보내기(PDF / Word) 드롭다운.
 *   - ✅ 내보내기가 **보고서 저장**까지 한다. 이 페이지는 저장에 쓸 "적용된 조건"
 *        (`exportCriteria`)을 내려 주고, 저장된 스냅샷으로 결과를 갈아 끼운다(`applySavedReport`) —
 *        결과 상태의 주인이 이 페이지 하나라 갱신도 여기서 한다.
 */

/**
 * 조회 상태 3분기.
 * - `idle`   : 진입 직후 = 보여줄 결과가 없는 상태. **실패도 여기로 되돌린다** —
 *              조건과 맞지 않는 직전 결과를 남겨 두면 마커가 거짓을 가리킨다.
 * - `loading`: 조회 중(실행 버튼 비활성 + `분석 중…`)
 * - `done`   : 응답 도착. `zones` 가 빈 배열이어도 **정상 상태**다.
 */
type AnalysisStatus = 'idle' | 'loading' | 'done'

/** 조회 구간 (yyyy-MM-dd). 서버로 나갈 때 `YYYYMMDD` 로 바꾼다. */
interface DateRange {
  from: string
  to: string
}

/** `yyyy-MM-dd` → `YYYYMMDD`. API 파라미터 형식이며 어긋나면 400 이다. */
function toCompactYmd(ymd: string): string {
  return ymd.replace(/-/g, '')
}

/**
 * 기간 세그먼트 → 실제 조회 구간. 기준 시각은 고정값이 아니라 **오늘**이다.
 *
 * ⚠️ `setMonth(getMonth() - n)` 만 쓰면 **말일에 넘친다** — 3/31 에서 1개월을 빼면 2/31 이 없어
 * 3/3(윤년이면 3/2)으로 튕겨 구간이 29일로 줄어든다. 대상 월의 말일로 clamp 해서 3/31 → 2/28 이 되게 한다.
 */
function recentMonths(months: number): DateRange {
  const today = new Date()
  const day = today.getDate()
  const start = new Date(today)
  start.setDate(1) // 먼저 1일로 내려 두면 월 이동에서 넘칠 일이 없다
  start.setMonth(start.getMonth() - months)
  const lastDayOfStartMonth = new Date(start.getFullYear(), start.getMonth() + 1, 0).getDate()
  start.setDate(Math.min(day, lastDayOfStartMonth))
  return { from: toYmd(start), to: toYmd(today) }
}

/**
 * 실패 문구. 서버 `message` 는 `server.error.include-message` 설정에 따라 없을 수 있어 기본 문구로 받는다
 * (설정 화면 `AnalysisWeightTab` 과 같은 저장소 관례 — `strict: true` 라 `unknown` 을 먼저 좁힌다).
 */
function errorText(e: unknown): string {
  const msg = axios.isAxiosError(e)
    ? (e.response?.data as { message?: string } | undefined)?.message
    : undefined
  return msg ?? '집중 구역 분석에 실패했습니다. 잠시 후 다시 시도해 주세요.'
}

export default function HotspotPage() {
  /*
   * 🔴 아래 두 훅은 `useState` 들보다 **위**에 있어야 한다 — `period`·`customFrom`·`customTo`·`sggNm`
   * 의 초기값이 `linked` 를 읽으므로, 밑으로 내리면
   * `Block-scoped variable 'linked' used before its declaration` 로 `tsc -b` 가 깨진다.
   */
  const [searchParams] = useSearchParams()

  /*
   * 대시보드 `자세히 보기` 딥링크(`?from=&to=&sggNm=&run=1`).
   * 초기 상태를 URL 로 덮어쓴다 — 설정 패널에 조건이 그대로 찍혀야 "무엇을 분석한 결과인지" 가 보인다.
   * (위 "진입 직후 조회 없음" 의 예외. 파라미터가 없으면 그 원칙 그대로다.)
   */
  const linked = useMemo(() => {
    const from = searchParams.get('from') ?? ''
    const to = searchParams.get('to') ?? ''
    if (!from || !to || validateRange(from, to)) return null // 잘못된 구간은 무시하고 평소처럼 연다
    return {
      from,
      to,
      sggNm: searchParams.get('sggNm') ?? '',
      run: searchParams.get('run') === '1',
    }
  }, []) // 마운트 시 한 번만 — 이후 URL 변화로 재실행되지 않게 한다

  // ── 설정 패널 입력 (지역 `''` = 전체) ──
  const [regions, setRegions] = useState<RegionItem[]>([])
  // 지역 목록 조회 실패 여부 — 실패를 조용히 넘기면 select 이 `전체` 만 남아 원인을 알 수 없다.
  const [regionsFailed, setRegionsFailed] = useState(false)
  // `다시 시도` 를 누르면 값이 바뀌어 목록 조회 effect 가 다시 돈다.
  const [reloadRegionsKey, setReloadRegionsKey] = useState(0)
  const [sggNm, setSggNm] = useState(linked?.sggNm ?? '')
  const [emdCd, setEmdCd] = useState('')
  // 딥링크는 구간이 정해져 있으므로 `직접설정` 으로 연다 — 세그먼트로 열면 화면 값과 실제 구간이 갈린다.
  const [period, setPeriod] = useState<HotspotPeriodKey>(linked ? 'custom' : DEFAULT_PERIOD)
  // 직접설정 칸은 기본 기간이 걸친 달로 채워 둔다 — 빈 칸으로 열면 실행 버튼이 막힌 채 시작한다.
  // 🔴 딥링크 구간은 월로 넓히지 않는다 — 화면 값과 실제 구간이 갈린다.
  const [customFrom, setCustomFrom] = useState(
    () => linked?.from ?? firstDayOfMonth(recentMonths(Number(DEFAULT_PERIOD)).from),
  )
  const [customTo, setCustomTo] = useState(
    () => linked?.to ?? lastDayOfMonth(recentMonths(Number(DEFAULT_PERIOD)).to),
  )

  // ── 조회 상태 ──
  const [status, setStatus] = useState<AnalysisStatus>('idle')
  const [result, setResult] = useState<HotspotAnalysisResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  // ── 분석 계수 일시 조정 (가중치 + 등급 비율) — React 상태에만 둔다. URL·localStorage 금지 ──
  /**
   * 초안·적용·마지막 실행값 3벌을 훅이 든다. 가중치는 `cvlcptWgvl`·`crdnWgvl`, 등급 비율만 `vrblVls`.
   * 설정 조회 실패·가중치 지표 꺼짐이면 버튼을 숨기고 설정값 분석은 막지 않는다.
   * 적용값(`appliedRequest`)이 실행 버튼이 쓰는 값이고 배너의 근거다. 결과와의 짝은 마지막 실행값(`ranRequest`)이
   * 진다 — 성공 시에만 `markRan` 으로 갱신하므로 적용 후 실행이 실패·생략되면 둘이 잠시 다르다.
   */
  const coeff = useAnalysisCoefficient('HOTSPOT')
  /**
   * **결과를 만든 실행**의 기간 라벨. `markRan` 과 같은 지점에서만 갱신한다 —
   * 실행 뒤 기간 세그먼트를 바꿔도, 내보내기 저장 응답으로 결과를 갈아 끼워도 그대로다.
   */
  const [ranPeriodLabel, setRanPeriodLabel] = useState('')
  /** 실행 번호. 실행을 시작할 때마다 올린다 — 이전 실행 결과로 시작한 내보내기의 늦은 응답을 가른다. */
  const runSeq = useRef(0)

  // ── 결과 패널 ──
  /** 결과 패널 열림 여부. `X` 로 닫아도 **다시 실행하면 열린다**. */
  const [panelOpen, setPanelOpen] = useState(true)
  /**
   * 현재 선택. **출처(목록/마커)와 무관하게 선택 이벤트마다 `seq` 를 올려 새 객체**로 만든다 —
   * 같은 구역을 다시 골랐을 때도 목록이 그 행을 다시 화면 안으로 끌어오게 하기 위해서다
   * (`HotspotSelection` 주석 참고). 하이라이트만 보는 곳은 `rnk` 만 쓰면 된다.
   */
  const [selection, setSelection] = useState<HotspotSelection | null>(null)
  /**
   * 목록 → 지도 요청. 목록에서 고른 구역으로만 지도를 움직인다.
   * 마커 클릭은 이 값을 건드리지 않는다 — 보고 있는 마커를 매번 중앙으로 끌어오면 지도가 튄다.
   */
  const [mapFocus, setMapFocus] = useState<HotspotMapFocus | null>(null)

  /** 선택 갱신 공통 경로 — 같은 순위를 다시 골라도 `seq` 가 올라 "새 선택"으로 전달된다. */
  const select = useCallback((rnk: number) => {
    setSelection((prev) => ({ rnk, seq: (prev?.seq ?? 0) + 1 }))
  }, [])

  /**
   * 목록 행 클릭 — 선택 + 지도 이동 요청.
   * 지도 쪽 `seq` 도 따로 올린다. 같은 행을 연달아 눌러도 `setView`(확대) 가 다시 걸려야 하기 때문이다
   * (마커를 눌러 다른 곳을 본 뒤 같은 행으로 돌아오는 경우가 그렇다).
   */
  const selectFromList = useCallback(
    (rnk: number) => {
      select(rnk)
      setMapFocus((prev) => ({ rnk, seq: (prev?.seq ?? 0) + 1 }))
    },
    [select],
  )

  /**
   * 지도 팝업 열림 — 선택만 바꾼다(지도는 이미 그 마커를 보고 있어 다시 움직이면 화면이 튄다).
   * `HotspotMap` 이 `popupopen` 하나만 구독하므로 마커 1클릭에 이 콜백도 1회다.
   */
  const selectFromMarker = select

  // 지역 select 원본은 마운트 때 한 번만 받는다. 분석 API 와 무관한 목록 조회라 자동 조회 금지에 걸리지 않는다.
  useEffect(() => {
    let cancelled = false
    fetchRegions()
      .then((list) => {
        if (cancelled) return
        setRegions(list)
        setRegionsFailed(false)
      })
      .catch(() => {
        // 화면 진입까지 막지는 않는다. 다만 **조용히 비우지 않는다** —
        // 목록이 비면 select 에 `전체` 만 남아 "지역 설정이 안 된다" 로 보이는데,
        // 그 대부분은 백엔드가 안 떠 있는 상황이라 원인을 알려 줘야 한다.
        if (cancelled) return
        setRegions([])
        setRegionsFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [reloadRegionsKey])

  /** 실행 시 서버로 나갈 구간. 세그먼트면 오늘 기준으로 펴고, 직접설정이면 입력값 그대로다. */
  const range = useMemo<DateRange>(
    () => (period === 'custom' ? { from: customFrom, to: customTo } : recentMonths(Number(period))),
    [period, customFrom, customTo],
  )

  // 서버가 400 을 내는 조건(빈 날짜·역전·최대 1년 초과)은 버튼에서 먼저 끊는다.
  // 규칙은 화면마다 두지 않고 `lib/dateRange` 하나만 쓴다 — 갈리면 화면마다 다르게 막힌다.
  const rangeError: RangeError | null = validateRange(range.from, range.to)

  /** 시군구를 바꾸면 읍면동을 `전체` 로 되돌린다 — 안 하면 제주시 읍면동이 서귀포시에 남는다. */
  const changeSgg = (next: string) => {
    setSggNm(next)
    setEmdCd('')
  }

  /**
   * 실행 — **이 화면에서 분석 API 를 호출하는 유일한 경로**다.
   * `전체`(`''`)인 지역 필터는 `fetchHotspotAnalysis` 가 쿼리에서 빼 준다(빈 값을 그대로 보내면 400).
   *
   * `req` 는 이번 실행의 계수 요청(기본 = 적용값). 기본값과 같은 값은 훅이 빼 두었다.
   * 성공했을 때만 그 요청을 마지막 실행값으로 남긴다(`markRan` — 실패 시 남기지 않는다).
   * 🔴 이벤트 핸들러에 함수를 그대로 넘기면 클릭 이벤트가 `req` 로 들어온다 — 패널에는 인자 없이 부르는 화살표 함수로 넘긴다.
   */
  const runAnalysis = async (req: CoeffRequest = coeff.appliedRequest('HOTSPOT')) => {
    if (rangeError) return
    runSeq.current += 1
    setStatus('loading')
    setError(null)
    // 직전 결과를 비운다 — 남겨 두면 조회 중에 이전 조건의 결과가 그대로 보인다.
    setResult(null)
    /*
     * 선택도 함께 푼다. 순위(`rnk`)는 결과 집합 안에서만 뜻이 있어, 조건이 바뀌면 같은 1위라도
     * 다른 구역이다 — 남겨 두면 새 결과의 엉뚱한 행이 선택된 채로 열린다.
     */
    setSelection(null)
    setMapFocus(null)

    try {
      const res = await fetchHotspotAnalysis({
        bgngYmd: toCompactYmd(range.from),
        endYmd: toCompactYmd(range.to),
        sggNm,
        emdCd,
        cvlcptWgvl: req.cvlcptWgvl,
        crdnWgvl: req.crdnWgvl,
        vrblVls: req.vrblVls,
      })
      coeff.markRan(req)
      setRanPeriodLabel(formatPeriodLabel(period, range))
      setResult(res)
      setStatus('done')
      // 닫아 둔 패널을 다시 연다 — 실행했는데 결과가 어디에도 안 보이는 상태를 만들지 않는다.
      setPanelOpen(true)
    } catch (e) {
      // 입력값은 그대로 두고 문구만 띄운다 — 사용자가 고른 조건을 날리지 않는다.
      setError(errorText(e))
      setStatus('idle')
    }
  }

  /**
   * `적용`. 🔴 `setState` 직후 `appliedRequest` 는 이전 값이라 훅이 돌려준 요청으로 실행한다.
   * 기간 입력이 잘못됐으면 `runAnalysis` 가 값만 남기고 돌아온다(결과가 없어도 실행한다).
   */
  const applyCoeff = (req: CoeffRequest) => {
    void runAnalysis(req)
  }

  /** `초기화`. 결과가 있을 때만(0건 포함) 재분석한다 — 없으면 값만 되돌린다. */
  const resetCoeff = () => {
    const req = coeff.reset('HOTSPOT')
    if (result !== null) void runAnalysis(req)
  }

  /*
   * 딥링크(`run=1`)로 들어왔을 때의 자동 실행 — **최초 1회만**.
   *
   * 🔴 `useRef` 가드가 없으면 지역 select 변경·지도 이동처럼 리렌더를 부르는 조작마다 다시 돈다.
   * 실행 뒤에도 **URL 은 지우지 않는다** — 새로고침·링크 공유로 같은 결과가 재현돼야 한다.
   */
  const autoRan = useRef(false)
  useEffect(() => {
    if (!linked?.run || autoRan.current) return
    autoRan.current = true
    void runAnalysis()
  }, [linked])

  /**
   * 내보내기(=저장) 요청 조건 — **응답이 되비춘 "적용된 조건"** 이다.
   *
   * 🔴 설정 패널의 현재 입력(`sggNm`·`emdCd`·`range`)을 쓰지 않는다. 분석을 돌린 뒤 select 만 바꿔 놓고
   * `내보내기` 를 누르는 일이 흔한데, 그때 입력값을 보내면 **화면에 떠 있는 표와 저장된 보고서가
   * 다른 조건**이 된다. 응답의 `bgngYmd`·`endYmd`·`sggNm`·`emdCd` 는 서버가 실제로 적용한 값이다.
   * 가중치·등급 비율도 같은 원칙이다 — 팝오버 초안·미실행 적용값이 아니라 결과를 만든 실행의 값(`ranRequest`)을
   * 보낸다.
   */
  // 렌더마다 새 객체를 만들지 않게 고정한다 — `exportCriteria` 가 매 렌더 바뀌지 않도록.
  const { ranRequest } = coeff
  const ranReq = useMemo(() => ranRequest('HOTSPOT'), [ranRequest])
  const exportCriteria = useMemo<HotspotExportCriteria | null>(
    () =>
      result
        ? {
            bgngYmd: result.bgngYmd,
            endYmd: result.endYmd,
            sggNm: result.sggNm,
            emdCd: result.emdCd,
            cvlcptWgvl: ranReq.cvlcptWgvl,
            crdnWgvl: ranReq.crdnWgvl,
            vrblVls: ranReq.vrblVls,
          }
        : null,
    [result, ranReq],
  )

  /**
   * 저장된 보고서 스냅샷을 화면에 반영한다 — **화면 = 파일 = DB**.
   *
   * 서버는 화면이 보낸 숫자를 믿지 않고 같은 조건으로 **재분석**해 저장하므로, 그 사이 원천이나
   * 가중치 설정이 바뀌었으면 화면의 표와 저장된 표가 미세하게 다를 수 있다. 그때 정본은 저장된 쪽이니
   * 화면을 갈아 끼운다 — 조회 조건(`bgngYmd`… )·`gradeCriteria` 등 나머지는 그대로 둔다.
   *
   * 선택(`selection`)은 건드리지 않는다. `rnk` 는 같은 조건의 결과 집합 안에서 뜻이 있고 재분석도
   * 같은 조건이라, 굳이 풀면 사용자가 보고 있던 구역만 놓친다(행이 사라졌으면 패널이 알아서 넘긴다).
   */
  const applySavedReport = useCallback((report: HotspotReportResponse, startRun: number) => {
    // 내보내기 뒤에 새 실행이 시작됐으면 저장은 유효해도 화면에는 반영하지 않는다 — 마지막 실행값과 짝이 깨진다.
    if (startRun !== runSeq.current) return
    setResult((prev) => (prev ? { ...prev, zones: report.zones, weights: report.weights } : prev))
  }, [])
  /*
   * 이 렌더의 실행 번호. 패널은 재실행 때 `result` 가 비어 내려가므로 지금 떠 있는 패널은 항상 이 번호의 결과이고,
   * `runExport` 가 클릭 시점의 `onReportSaved` 를 붙들어 곧 "내보내기를 시작한 실행" 이 된다.
   */
  const renderedRun = runSeq.current

  /**
   * 0건 안내. 0건은 오류가 아니라 정상 상태라, 원천 보유 구간을 곁들여 오해를 막는다.
   *
   * 건수가 있을 때 쓰던 임시 문구(`N건 조회됨`)는 **결과 패널이 대신하므로 걷어냈다** —
   * 순위 목록이 바로 옆에 떠 있는데 설정 패널에도 건수를 적으면 같은 말이 두 곳에 남는다.
   *
   * 🔴 **`데이터 없음` 접두를 뗐다**. 표시 하한이 생긴 뒤 읍면동 단위 조회는 **89%(66/74)가
   * 0건**이라(실측) 그 접두가 먼저 읽히면 *"내 지역 데이터가 없나"* 로 오해한다 — 실제로는 **데이터는
   * 있고 주의 미달**이다. 그래서 `주의 이상` 이라는 한정이 문구에 들어 있다(하한이 생겼다는 사실을
   * 문구가 말해야 한다 — 그냥 "집중 구역이 없습니다" 면 양호 구역까지 없는 것처럼 읽힌다).
   * 🔴 그 하한의 정본은 백엔드 `HotspotGrade.MIN_DISPLAYED`(현재 `CAUTION`)다 — **그것을 바꾸면 이
   * 문구의 `주의 이상` 도 함께 바꾼다.**
   */
  const resultNote =
    status === 'done' && result && result.zones.length === 0
      ? `해당 조건에 주의 이상 집중 구역이 없습니다 — 보유 구간: ${result.dataBgngYmd ?? '-'} ~ ${result.dataEndYmd ?? '-'}`
      : null

  /**
   * 결과 패널을 띄울 조건.
   * 0건이면 열지 않는다 — 빈 목록만 있는 패널보다 설정 패널의 0건 안내가 할 말을 다 한다.
   */
  const panelVisible = status === 'done' && result !== null && result.zones.length > 0 && panelOpen

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="분석·시뮬레이션" page="불법 주차 집중 구역 분석" />
          <h1 className="page-title">불법 주차 집중 구역 분석</h1>
          <p className="page-sub">불법 주차 집중 구역 자동 도출</p>
        </div>
        <TopbarActions />
      </header>

      <div className="content content--analysis">
        <section className="sim-stage">
          {/*
           * 배경지도는 스테이지의 **직계 자식**이어야 한다 —
           * `.sim-stage > .leaflet-container { width:100%; height:100% }` 가 그 형태를 전제로 크기를 잡는다.
           * 오버레이 패널은 `position:absolute; z-index:1000` 이라 흐름상 뒤에 있어도 지도 위에 얹힌다.
           */}
          {/*
           * `result?.zones` 를 그대로 넘긴다 — `?? []` 로 기본값을 만들면 렌더마다 새 배열이라
           * 마커가 매번 다시 그려지고 `fitBounds` 가 계속 걸린다(고정 빈 배열은 `HotspotMap` 이 들고 있다).
           * 실패·재실행 시 `result` 를 null 로 비우므로 마커도 함께 사라진다.
           */}
          {/*
           * 마커 → 목록은 `onMarkerSelect`, 목록 → 마커는 `focus` 로 오간다.
           * 패널을 닫아도 마커는 남으므로 `focus` 를 잘라내지 않는다 — 재실행 때 null 로 리셋된다.
           */}
          {/* `gradeCriteria` 는 범례 문구(`심각 (상위 5%)`)의 출처다 — 임계값을 화면에 박지 않는다. */}
          <HotspotMap
            zones={result?.zones}
            gradeCriteria={result?.gradeCriteria}
            onMarkerSelect={selectFromMarker}
            focus={mapFocus}
          />

          <HotspotSettingPanel
            regions={regions}
            regionsFailed={regionsFailed}
            onRetryRegions={() => setReloadRegionsKey((k) => k + 1)}
            sggNm={sggNm}
            emdCd={emdCd}
            onSggChange={changeSgg}
            onEmdChange={setEmdCd}
            period={period}
            onPeriodChange={setPeriod}
            customFrom={customFrom}
            customTo={customTo}
            onCustomFromChange={setCustomFrom}
            onCustomToChange={setCustomTo}
            rangeError={rangeError}
            running={status === 'loading'}
            onRun={() => runAnalysis()}
            error={error}
            resultNote={resultNote}
            coeff={coeff}
            onApplyCoeff={applyCoeff}
            onResetCoeff={resetCoeff}
          />

          {/*
           * 우측 순위 결과 패널. `result` 는 `panelVisible` 안에서 이미 null 이 아님이 확인되지만
           * TS 가 그 사실을 JSX 밖으로 이어 주지 못해 `result &&` 를 한 번 더 둔다.
           */}
          {panelVisible && result && exportCriteria && (
            <HotspotResultPanel
              zones={result.zones}
              weights={result.weights}
              weightsAdjusted={ranReq.weightAdjusted}
              periodLabel={ranPeriodLabel}
              eps={result.eps}
              minPoints={result.minPoints}
              gradeCriteria={result.gradeCriteria ?? []}
              criteria={exportCriteria}
              onReportSaved={(report) => applySavedReport(report, renderedRun)}
              selection={selection}
              onSelectZone={selectFromList}
              onClose={() => setPanelOpen(false)}
            />
          )}
        </section>
      </div>
    </>
  )
}
