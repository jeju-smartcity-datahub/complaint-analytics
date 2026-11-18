import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  createComplaintReport,
  fetchAdminDistricts,
  fetchComplaintKpi,
  fetchHotspotHeatmap,
  fetchHotspotRanking,
  fetchParkingLots,
  type AdminDistrictFeatureCollection,
  type ComplaintFileFmt,
  type ComplaintKpiResponse,
  type ComplaintUnit,
  type HotspotHeatmapPoint,
  type HotspotRankingZone,
  type ParkingLotItem,
} from '../api/complaints'
import Icon from '../components/Icon'
import DateRangePopover from '../components/common/DateRangePopover'
import Modal from '../components/common/Modal'
import TopbarActions from '../components/common/TopbarActions'
import EmdCompareCard from '../components/complaints/EmdCompareCard'
import EmdCrdnRankCard from '../components/complaints/EmdCrdnRankCard'
import EmdShortageRankCard from '../components/complaints/EmdShortageRankCard'
import KpiBar from '../components/complaints/KpiBar'
import {
  HEATMAP_VIEWS,
  type DisplayMode,
  type LayerToggles,
  type LayerView,
} from '../components/complaints/LayerCard'
import MinwonMap from '../components/complaints/MinwonMap'
import NegEmdCompareCard from '../components/complaints/NegEmdCompareCard'
import DataFreshness from '../components/dashboard/DataFreshness'
import { buildComplaintReportHtml } from '../lib/analysis/complaintReportTemplate'
import { FOCUS_EMD_MISSING_MESSAGE, readComplaintFocus } from '../lib/complaintFocus'
import { daysUntilYesterday, recentDays, validateRange } from '../lib/dateRange'
import { exportDocx, exportPdf } from '../lib/report/reportExport'
import { compareLabel, periodLabel } from '../components/complaints/periodText'

/*
 * 민원현황 화면 `/complaints` (TSK-3869 §1 헤더 · TSK-3870 §2 필터 · TSK-3880 §3·§4·§5·§6).
 *
 * 시안(https://innopamsolution.github.io/jeju-datahub/) 의 화면 상단 + 본문 최상단 필터바를
 * 그대로 옮긴다. 다른 페이지의 인라인 패턴과 정합 — `<header className="topbar">` 안에
 * `<TopbarActions />` 를 박고, 본문 위에 `<div className="filterbar">` 를 둔다.
 *
 *   · 헤더: 제목(`민원현황`) + 부제(`DataFreshness` — 대시보드 공용 · `/dashboard/data-freshness`, TSK-4256) + 우측 액션
 *   · 필터바: 조회 단위 세그먼트(어제·주간·월간) · 기간 팝오버 · 시군구 select
 *   · §3 KPI 카드 4종
 *   · §4 레이어 설정 (분석레이어 라디오 + 공영주차장/행정동 경계 토글)
 *   · §5 범례 (지도 우측 하단)
 *   · §6 GIS 지도 (배경지도 + 분석레이어 hotspot + 토글 의존 마커/폴리곤)
 *
 * 본문 데이터(KPI 5필드 + §7·§8) 는 한 응답(`fetchComplaintKpi`)에 들어있어 한 번의 호출로 같이
 * 갱신된다. 헤더 부제는 그 응답을 읽지 않는다 — `DataFreshness` 가 마운트 시 1회 따로 조회한다
 * (필터와 무관, TSK-4256). §4·§5·§6 데이터(분석레이어·공영주차장·행정동 경계) 는
 * 각각 별도 fetch — §2 필터가 바뀌면 동시에 재호출된다 (DashboardPage 와 같은 패턴).
 */

/* ─────────────────────────────────────────────────────────────
 * 조회 단위 / 한글 매핑 — DashboardPage 와 동일 패턴.
 * ───────────────────────────────────────────────────────────── */
const UNITS = ['어제', '주간', '월간'] as const
type Unit = (typeof UNITS)[number]

const UNIT_ENUM: Record<Unit, ComplaintUnit> = {
  어제: 'YESTERDAY',
  주간: 'WEEK',
  월간: 'MONTH',
}

/** 시군구 옵션. 화면엔 '전체'(=미전송) + 제주시 + 서귀포시. 화면에선 5자리 코드를 그대로 백엔드로 보낸다. */
const REGION_OPTIONS = [
  { value: '', label: '제주도 전체' },
  { value: '50110', label: '제주시' },
  { value: '50130', label: '서귀포시' },
] as const

/**
 * 화면 표기용 `YYYY-MM-DD` → `YYYY.MM.DD` (시안 정합, TSK-4165 Step 1).
 *
 * 🔴 상태값(`range.from`/`to`)은 그대로 API 로 가는 값이라 하이픈 포맷을 유지한다 —
 * 라벨을 그리는 직전에만 변환한다.
 */
const dotYmd = (v: string) => v.replace(/-/g, '.')

/** §4·§6 의 토글 state 기본값. */
const DEFAULT_TOGGLES: LayerToggles = {
  publicParking: false,
  adminBoundary: false,
}

/**
 * 내보내기(=보고서 저장) 요청에 실을 조회 조건 (TSK-4164 Step 8 · D3).
 *
 * 🔴 필터 select 의 **현재 입력이 아니라 응답이 되비춘 적용 조건**이다. 조회 effect 는 재조회 때
 * `kpi` 를 null 로 되돌리지 않아, 시군구를 바꾼 직후 in-flight 구간에는 `kpi.bgngYmd`(이전 조건)와
 * `sggCd`(새 조건)가 한 요청에 섞인다 — 화면 표와 다른 조건이 저장된다. 그래서 응답을 받은
 * **그 시점의 조건을 한 벌로** 스냅샷한다.
 */
type ComplaintExportCriteria = { bgngYmd: string; endYmd: string; sggCd?: string }

/** 내보내기 실패 문구. `ReportsPage.ERROR_TEXT` 와 같은 토큰을 쓴다. */
const EXPORT_ERROR_TEXT: CSSProperties = {
  position: 'absolute',
  top: 'calc(100% + var(--space-10))',
  right: 0,
  whiteSpace: 'nowrap',
  color: 'var(--status-negative)',
  fontSize: 'var(--label1-size)',
}

/** 안내 팝업 본문 — `ReportsPage.MODAL_MSG` 와 같은 토큰. */
const MODAL_MSG: CSSProperties = {
  margin: 0,
  fontSize: 'var(--body2-size)',
  color: 'var(--text-strong)',
}

/** 내보내는 중인 메뉴 항목 표현. `.export-pop__item` 에는 `:disabled` 규칙이 없어 최소한만 인라인으로 얹는다. */
const EXPORT_ITEM_DISABLED: CSSProperties = { opacity: 0.5, cursor: 'not-allowed' }

export default function ComplaintsPage() {
  const [unit, setUnit] = useState<Unit>('어제')
  const [range, setRange] = useState<{ from: string; to: string } | null>(null)
  const [dateOpen, setDateOpen] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  // 기본 구간은 어제까지 30일(월간과 같은 구간 — TSK-4311).
  const [{ from: defFrom, to: defTo }] = useState(() => daysUntilYesterday(30))
  const [from, setFrom] = useState(defFrom)
  const [to, setTo] = useState(defTo)
  const [sggCd, setSggCd] = useState('')

  const [kpi, setKpi] = useState<ComplaintKpiResponse | null>(null)
  const [kpiError, setKpiError] = useState(false)
  const [loading, setLoading] = useState(true)

  /* §2-4 내보내기 (TSK-4164 Step 8) */
  const [exportCriteria, setExportCriteria] = useState<ComplaintExportCriteria | null>(null)
  /**
   * 내보내는 동안 토글 버튼과 **두 메뉴 항목을 함께** 잠근다.
   *
   * 🔴 내보내기 1회 = **보고서 1건 저장**(D6)이라 연타는 파일이 두 번 받아지는 정도가 아니라
   * 보고서 목록에 중복 행이 쌓이는 것이다. 잠금이 UX 편의가 아니라 정합성 장치다.
   */
  const [exporting, setExporting] = useState(false)
  const [exportFailed, setExportFailed] = useState(false)

  /* §4·§6 state */
  const [view, setView] = useState<LayerView>('most-complaint')
  const [toggles, setToggles] = useState<LayerToggles>(DEFAULT_TOGGLES)
  const [zones, setZones] = useState<HotspotRankingZone[]>([])
  /* §4 지도 표시 방식 (TSK-4295). 기본 `emd`(종전 동작). 🔴 레이어를 바꾸면 `emd` 로 리셋하는데(Q1),
     그 리셋은 `view` effect 가 아니라 아래 `<MinwonMap onViewChange>` 래핑에서만 한다 — 벨 포커스가
     내부에서 `setView('most-complaint')` 를 부르므로 `view` 변화에 걸면 벨이 히트맵을 끈다(Q2). */
  const [displayMode, setDisplayMode] = useState<DisplayMode>('emd')
  /* 히트맵 좌표 칸 (TSK-4295) — `heatmap` 모드일 때만 조회한다(읍면동 모드엔 추가 호출 없음 — §3-2). */
  const [heatPoints, setHeatPoints] = useState<HotspotHeatmapPoint[]>([])
  /* 🔴 zones 전용 로딩 (TSK-4181 Step 2-4). 기존 `loading` 은 KPI effect(`[unit, range, sggCd]` —
     `view` 가 없다)의 것이라 레이어만 바꾸면 `false` 로 남는다. `setZones([])` + `loading=false`
     조합은 카드의 `loading && length === 0` 분기를 비켜가 "표시할 데이터가 없습니다" 를 띄운다. */
  const [zonesLoading, setZonesLoading] = useState(true)
  /* §7 순위 카드 행 클릭 → §6 지도 강조·팝업 (TSK-4167 Step 11).
     🔴 강조·팝업 로직은 `MinwonMap` 의 `onZoneClick` 한 곳에만 있다 — 여기서는 **어느 동을 골랐나**
        만 들고 있고, 지도는 그 코드로 레지스트리를 조회해 지도 클릭과 같은 함수를 부른다. */
  const [selectedEmdCd, setSelectedEmdCd] = useState<string | null>(null)
  /* 🔴 같은 행을 다시 눌러도 지도가 반응하게 하는 단조 증가 nonce. `selectedEmdCd` 만 넘기면 값이
     같아 지도의 호출 effect 가 돌지 않는다(= 팝업을 닫은 뒤 같은 행을 눌러도 안 열린다).
     선택이 `null` 로 풀릴 때도 이 값은 되돌리지 않는다 — 되돌리면 같은 (코드, seq) 짝이 재현된다. */
  const [selectedEmdSeq, setSelectedEmdSeq] = useState(0)
  const [parkingLots, setParkingLots] = useState<ParkingLotItem[]>([])
  const [adminDistricts, setAdminDistricts] =
    useState<AdminDistrictFeatureCollection | null>(null)

  const datePickRef = useRef<HTMLDivElement>(null)
  const exportRef = useRef<HTMLDivElement>(null)

  const location = useLocation()
  const navigate = useNavigate()
  /* 상단 벨 → 민원현황 포커스 요청 (TSK-4267). 계약은 `lib/complaintFocus.ts`.
     `needSeq` = 이 번호 이상의 §4 분석레이어 조회가 끝난 뒤에 적용한다(아래 소비 effect). */
  const pendingFocusRef = useRef<{ emdCd: string; needSeq: number } | null>(null)
  /** 소비 effect 를 깨우는 틱 — 필터가 그대로면 조회 번호가 안 바뀌어 ref 만으로는 effect 가 안 돈다. */
  const [focusTick, setFocusTick] = useState(0)
  /** §4 분석레이어 조회 요청 번호 — fetch effect 가 요청마다 +1 한다 (TSK-4267). */
  const zonesReqSeqRef = useRef(0)
  /** 끝난(취소되지 않은) 마지막 조회의 요청 번호. 소비 effect 를 깨우려고 state 로 둔다. */
  const [zonesDoneSeq, setZonesDoneSeq] = useState(0)
  /** 벨 요청으로 일으킨 선택의 nonce — 이 선택의 결과에만 안내를 띄운다 (D8-1). */
  const bellFocusSeqRef = useRef<number | null>(null)
  const [focusMissOpen, setFocusMissOpen] = useState(false)
  /** `selectedEmdSeq` 의 거울 — 호출부가 방금 올린 nonce 를 알아야 해서 ref 로 센다. */
  const emdSeqRef = useRef(0)

  /** §7 카드 3종 공용 행 클릭 핸들러 (TSK-4167 Step 11). 코드 + nonce 를 함께 올린다.
      올린 nonce 를 반환한다 — 벨 포커스(TSK-4267)가 자기 선택의 결과를 가려낼 때 쓴다. */
  const handleSelectEmd = useCallback((emdCd: string) => {
    emdSeqRef.current += 1
    setSelectedEmdCd(emdCd)
    setSelectedEmdSeq(emdSeqRef.current)
    return emdSeqRef.current
  }, [])

  // ── §2 필터 → 핵심 API 재호출 ──
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setKpiError(false)

    const params: Parameters<typeof fetchComplaintKpi>[0] = {}
    if (range) {
      params.startDt = range.from
      params.endDt = range.to
    } else {
      params.unit = UNIT_ENUM[unit]
    }
    if (sggCd) params.sggCd = sggCd

    void fetchComplaintKpi(params)
      .then((res) => {
        if (cancelled) return
        setKpi(res)
        // 🔴 내보내기 조건은 여기서 한 벌로 굳힌다 — 응답이 되비춘 기간(D3) + 이 요청이 실제로 보낸 지역.
        setExportCriteria({ bgngYmd: res.bgngYmd, endYmd: res.endYmd, sggCd: params.sggCd })
        // 조건이 바뀌어 새로 조회됐으므로 지난 실패 문구는 거둔다 (남겨 두면 성공한 화면에 실패가 붙어 있다).
        setExportFailed(false)
      })
      .catch(() => {
        if (cancelled) return
        setKpi(null)
        setExportCriteria(null)
        setKpiError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [unit, range, sggCd])

  /* 🔴 레이어를 바꿀 때만 zones 를 비운다 (TSK-4181 Step 2-4).
     전환 직후 한 프레임 동안 이전 레이어 값이 새 제목 아래 보이는 것을 막는다.
     🔴 fetch effect(`[view, unit, range, sggCd]`) 머리에서 비우면 안 된다 — 같은 배열이 §6 지도
     polygon·라벨의 소스라 기간·지역만 바꿔도 지도가 한 프레임 비워진다. */
  useEffect(() => {
    setZones([])
    /* 히트맵 칸도 같은 이유·같은 시점에 비운다 (TSK-4295) — 레이어가 바뀌면 직전 레이어 히트맵이
       새 레이어 이름 아래 남는다(벨 포커스가 단속 → 민원으로 바꾸는 경로). 기간·지역만 바뀔 때는 비우지 않는다. */
    setHeatPoints([])
  }, [view])

  /* 🔴 레이어·조회단위·기간·지역이 바뀌면 §7 선택을 되돌린다 (TSK-4167 Step 11) — 옛 선택이 새
     레이어·새 기간에 남으면 리스트에 없는 동이 지도에만 강조된 채로 남는다.
     🔴 위 `setZones([])` 와 달리 **네 값 모두**를 본다(그 effect 는 `view` 만 본다 — 기간·지역만
        바뀔 때 §6 지도를 한 프레임 비우지 않기 위한 의도적 한정이다). */
  useEffect(() => {
    setSelectedEmdCd(null)
  }, [view, unit, range, sggCd])

  // ── §4 분석레이어 fetch ──
  useEffect(() => {
    let cancelled = false
    const reqSeq = ++zonesReqSeqRef.current
    setZonesLoading(true)

    const params: Parameters<typeof fetchHotspotRanking>[0] = { view }
    if (range) {
      params.startDt = range.from
      params.endDt = range.to
    } else {
      params.unit = UNIT_ENUM[unit]
    }
    if (sggCd) params.sggCd = sggCd

    void fetchHotspotRanking(params)
      .then((res) => {
        if (cancelled) return
        setZones(res.zones ?? [])
      })
      .catch(() => {
        if (cancelled) return
        setZones([])
      })
      .finally(() => {
        if (cancelled) return
        setZonesLoading(false)
        setZonesDoneSeq(reqSeq)
      })

    return () => {
      cancelled = true
    }
  }, [view, unit, range, sggCd])

  // ── §4 히트맵 fetch (TSK-4295) ──
  /* 🔴 `heatmap` 모드 + 히트맵 지원 레이어(`HEATMAP_VIEWS` — `shortage` 제외, Q4)일 때만 부른다.
     `shortage` 면 400 이라 아예 부르지 않는다 — `find` 로 `HeatmapView` 타입까지 좁혀 넘긴다.
     🔴 비우는 시점은 zones 관례(위 `[view]` effect 주석)를 따른다 — **`emd` 전환 · `view` 변경 · 조회 실패**
        때만 비운다. 기간·지역만 바뀌면 비우지 않고 응답이 오면 교체한다(지도 깜빡임 방지 — 그 사이 직전
        조건 히트맵이 잠깐 보이는 것은 zones 와 같은 감수). `view` 변경 시 비우기는 위 `[view]` effect 몫이다.
     🔴 `zones`(순위) 조회는 모드와 무관하게 위 effect 가 계속 한다 — 히트맵 모드의 §7 행 클릭 MBR 재료다(Step 5). */
  useEffect(() => {
    const heatView = HEATMAP_VIEWS.find((v) => v === view)
    if (displayMode !== 'heatmap' || !heatView) {
      // 이미 비어 있으면 같은 참조를 돌려 불필요한 리렌더를 막는다(읍면동 모드의 필터 변경마다 돈다).
      setHeatPoints((prev) => (prev.length === 0 ? prev : []))
      return
    }
    let cancelled = false

    const params: Parameters<typeof fetchHotspotHeatmap>[0] = { view: heatView }
    if (range) {
      params.startDt = range.from
      params.endDt = range.to
    } else {
      params.unit = UNIT_ENUM[unit]
    }
    if (sggCd) params.sggCd = sggCd

    void fetchHotspotHeatmap(params)
      .then((res) => {
        if (cancelled) return
        setHeatPoints(res.points ?? [])
      })
      .catch(() => {
        if (cancelled) return
        setHeatPoints([])
      })

    return () => {
      cancelled = true
    }
  }, [displayMode, view, unit, range, sggCd])

  /* ── 상단 벨 → 해당 읍면동 포커스 수신 (TSK-4267 Step 2) ──
     🔴 이 effect 는 위 §4 fetch effect **뒤에** 선언해야 한다 — 같은 커밋에서 fetch 가 먼저 돌아
        `zonesReqSeqRef` 가 이번 요청 번호를 가리킨 뒤에 `needSeq` 를 정한다(신규 마운트).
     🔴 키는 `location.key` 다 — 이미 민원현황에 있을 때 벨을 눌러도 리마운트가 없다(D7).
     필터를 알림 조건(민원다발 · 제주도 전체 · 알림일)으로 맞추고(D4·D5), 요청은 즉시 지운다(D6 — 1회성).
     필터가 바뀌면 그 재조회(= 다음 요청 번호)를, 그대로면 지금 요청(끝났으면 즉시)을 기다린다. */
  useEffect(() => {
    const focus = readComplaintFocus(location.state)
    if (!focus) return
    navigate(location.pathname, { replace: true, state: null })

    const yesterday = recentDays(1).from
    const nextRange = focus.ymd === yesterday ? null : { from: focus.ymd, to: focus.ymd }
    const rangeChanges =
      (range?.from ?? null) !== (nextRange?.from ?? null) ||
      (range?.to ?? null) !== (nextRange?.to ?? null)
    const filterChanges =
      view !== 'most-complaint' || sggCd !== '' || unit !== '어제' || rangeChanges

    pendingFocusRef.current = {
      emdCd: focus.emdCd,
      needSeq: zonesReqSeqRef.current + (filterChanges ? 1 : 0),
    }
    setView('most-complaint')
    setSggCd('')
    setUnit('어제')
    // 같은 값의 새 객체를 넣으면 deps 가 달라져 불필요한 재조회가 일어난다 — 바뀔 때만 넣는다
    if (rangeChanges) setRange(nextRange)
    setDateOpen(false)
    setFocusTick((n) => n + 1)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- location.key 로만 발화하는 1회성 요청이다
  }, [location.key])

  /* ── 벨 포커스 적용 (TSK-4267 Step 3) ──
     🔴 선택 초기화 effect(`[view, unit, range, sggCd]`)가 마운트·필터 변경 때 선택을 `null` 로 되돌리므로,
        진입 즉시가 아니라 **필요한 조회가 끝난 뒤** 고른다. 강조·팝업은 §7 카드와 같은 `handleSelectEmd`(D3).
     조회 결과가 0건이면 지도가 결과를 보고하지 못하므로(빈 `zones` 는 "아직 안 그림" 과 구분 불가)
     여기서 바로 안내한다(D8). */
  useEffect(() => {
    const p = pendingFocusRef.current
    if (!p || zonesDoneSeq < p.needSeq) return
    pendingFocusRef.current = null
    if (zones.length === 0) {
      setFocusMissOpen(true)
      return
    }
    bellFocusSeqRef.current = handleSelectEmd(p.emdCd)
  }, [focusTick, zonesDoneSeq, zones, handleSelectEmd])

  /** 지도의 선택 결과 — 벨 요청이 일으킨 선택이고 못 찾았을 때만 안내한다 (D8·D8-1). */
  const handleSelectResult = useCallback((_emdCd: string, seq: number, found: boolean) => {
    if (bellFocusSeqRef.current !== seq) return
    bellFocusSeqRef.current = null
    if (!found) setFocusMissOpen(true)
  }, [])

  // ── §4 공영주차장 fetch ──
  useEffect(() => {
    let cancelled = false
    void fetchParkingLots(sggCd ? { sggCd } : undefined)
      .then((res) => {
        if (cancelled) return
        setParkingLots(res ?? [])
      })
      .catch(() => {
        if (cancelled) return
        setParkingLots([])
      })
    return () => {
      cancelled = true
    }
  }, [sggCd])

  // ── §4 행정동 경계 fetch ──
  // 2026-09-04 갱신: 행정동 경계 페이로드는 수 MB — 토글 off 일 때 페치 자체를 막아
  // 네트워크/메모리 낭비를 없앤다 (PR #32 Copilot review). 토글이 on 으로 켜질 때만
  // 데이터를 받아오고, off 로 돌아가면 캐시도 비워 재진입 시 stale 상태를 만들지 않는다.
  useEffect(() => {
    if (!toggles.adminBoundary) {
      setAdminDistricts(null)
      return
    }
    let cancelled = false
    void fetchAdminDistricts(sggCd ? { sggCd } : undefined)
      .then((res) => {
        if (cancelled) return
        setAdminDistricts(res)
      })
      .catch(() => {
        if (cancelled) return
        setAdminDistricts(null)
      })
    return () => {
      cancelled = true
    }
  }, [sggCd, toggles.adminBoundary])

  // 기간 팝오버
  useEffect(() => {
    if (!dateOpen) return
    const onClick = (e: MouseEvent) => {
      if (datePickRef.current && !datePickRef.current.contains(e.target as Node))
        setDateOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setDateOpen(false)
    }
    document.addEventListener('click', onClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('click', onClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [dateOpen])

  // 내보내기 토글
  useEffect(() => {
    if (!exportOpen) return
    const onClick = (e: MouseEvent) => {
      if (exportRef.current && !exportRef.current.contains(e.target as Node))
        setExportOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setExportOpen(false)
    }
    document.addEventListener('click', onClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('click', onClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [exportOpen])

  const applyRange = (next: { from: string; to: string }) => {
    if (validateRange(next.from, next.to)) return
    setRange(next)
    setDateOpen(false)
  }

  /**
   * 🔴 비활성 조건은 `loading || exportCriteria === null` 이다 (계획서 Step 8).
   * `kpi === null` 하나로는 부족하다 — 재조회 중에도 이전 `kpi` 가 남아 버튼이 살아 있다.
   *
   * 🔴 **0건(`totalCount === 0`)은 막지 않는다 (D5).** "이 기간에 민원이 0건이었다" 도 정식 보고서이고,
   * 확인 창은 자동 생성 배치가 띄울 수단이 없어 화면과 배치의 동작이 갈린다.
   */
  const exportDisabled = loading || exportCriteria === null || exporting

  /**
   * `내보내기 > PDF/Word` 한 번의 전체 동작 — **메뉴를 먼저 닫고** 저장 → 본문 → 다운로드.
   *
   *   `createComplaintReport` (201 저장) → `buildComplaintReportHtml` (화면 = 파일 = DB)
   *   → `exportPdf`/`exportDocx`
   *
   * 🔴 파일명은 응답의 `rptpNm`(서버가 만든 이름 — D8)을 쓴다. 프론트가 이름 규칙을 두 번째로
   * 구현하지 않는다.
   * 저장 실패·다운로드 실패 어느 쪽이든 처리가 같다 — 삼키지 않고 `내보내기 실패` 를 띄운다.
   */
  const runExport = async (fileFmt: ComplaintFileFmt) => {
    setExportOpen(false)
    if (exportDisabled || !exportCriteria) return
    setExporting(true)
    setExportFailed(false)
    try {
      const report = await createComplaintReport({ ...exportCriteria, fileFmt })
      const html = buildComplaintReportHtml(report)
      if (fileFmt === 'PDF') await exportPdf(html, report.rptpNm)
      else await exportDocx(html, report.rptpNm)
    } catch {
      setExportFailed(true)
    } finally {
      setExporting(false)
    }
  }

  const handleExportPdf = () => {
    void runExport('PDF')
  }
  const handleExportDocx = () => {
    void runExport('DOCX')
  }

  // 디버깅용
  useEffect(() => {
    if (!import.meta.env.DEV) return
    const w = window as unknown as { __complaintsFetchDebug?: unknown }
    w.__complaintsFetchDebug = {
      loading,
      kpiError,
      hasKpi: kpi !== null,
      sggCd,
      unit,
      range,
      view,
      toggles,
      zones: zones.length,
      parkingLots: parkingLots.length,
      hasAdminDistricts: adminDistricts !== null,
    }
  }, [loading, kpiError, kpi, sggCd, unit, range, view, toggles, zones, parkingLots, adminDistricts])

  /** 범례 제목 기간 · 순위 카드 증감 툴팁 머리말 — 문구 정본은 `periodText.ts`. */
  const periodText = periodLabel(unit, range)
  const compareText = compareLabel(unit, range)

  return (
    <>
      <header className="topbar">
        <div>
          <h1 className="page-title">민원현황</h1>
          <DataFreshness />
        </div>
        <TopbarActions>
          {/* 시안대로 페이지 액션은 필터바에 둔다 — 헤더 액션 없음. */}
        </TopbarActions>
      </header>

      {/* ── §2 필터바 ── */}
      <div className="filterbar">
        <span className="filterbar__label">조회 단위</span>

        <div className="segment">
          {UNITS.map((u) => (
            <button
              key={u}
              type="button"
              className={`segment__btn${!range && unit === u ? ' segment__btn--active' : ''}`}
              onClick={() => {
                setUnit(u)
                setRange(null)
              }}
            >
              {u}
            </button>
          ))}
        </div>

        <div className="date-pick" ref={datePickRef}>
          {/* 좌측 `어제/주간/월간` 과 같은 부품(.segment__btn)으로 편입 — 높이·회색은 클래스가 가진다. */}
          <button
            className={`segment__btn${range ? ' segment__btn--active' : ''}`}
            type="button"
            aria-haspopup="true"
            aria-expanded={dateOpen}
            onClick={(e) => {
              e.stopPropagation()
              setDateOpen((v) => !v)
            }}
            style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}
          >
            <Icon name="calendar" size={18} />
            {range ? `${dotYmd(range.from)} ~ ${dotYmd(range.to)}` : '기간 선택'}
          </button>
          {dateOpen && (
            <DateRangePopover
              from={from}
              to={to}
              onFromChange={setFrom}
              onToChange={setTo}
              onApply={applyRange}
              onCancel={() => setDateOpen(false)}
            />
          )}
        </div>

        <span className="filter-sep" />

        <span className="filterbar__label">행정구역</span>

        <label className="ds-select">
          <select aria-label="행정구역" value={sggCd} onChange={(e) => setSggCd(e.target.value)}>
            {REGION_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <span className="ds-select__ic">
            <Icon name="chevron-down" size={16} />
          </span>
        </label>

        <span className="filterbar__right">
          {/* 2026-09-21 (TSK-4165 ①) — 버튼 라벨과 같은 점 구분 표기로 통일. 시안도
              적용 시 `replace(/-/g,'.')` 한 문자열을 '현재:' 에 그대로 쓴다. */}
          현재: <strong>{range ? `${dotYmd(range.from)} ~ ${dotYmd(range.to)}` : unit}</strong>
        </span>
        <div className="export-pick" ref={exportRef}>
          <button
            className="btn"
            type="button"
            style={exportDisabled ? EXPORT_ITEM_DISABLED : undefined}
            disabled={exportDisabled}
            aria-haspopup="true"
            aria-expanded={exportOpen}
            onClick={(e) => {
              e.stopPropagation()
              setExportOpen((v) => !v)
            }}
          >
            <Icon name="download" size={20} /> {exporting ? '내보내는 중…' : '내보내기'}
          </button>
          {exportOpen && (
            <div className="export-pop" onClick={(e) => e.stopPropagation()}>
              {/* 🔴 두 항목을 함께 잠근다 — 연타 = 보고서 중복 생성(D6). */}
              <button
                type="button"
                className="export-pop__item"
                style={exportDisabled ? EXPORT_ITEM_DISABLED : undefined}
                disabled={exportDisabled}
                onClick={handleExportPdf}
              >
                PDF 파일 (.pdf)
              </button>
              <button
                type="button"
                className="export-pop__item"
                style={exportDisabled ? EXPORT_ITEM_DISABLED : undefined}
                disabled={exportDisabled}
                onClick={handleExportDocx}
              >
                Word 파일 (.docx)
              </button>
            </div>
          )}
          {/*
           * 🔴 드롭다운이 열려 있으면 그리지 않는다 — `.export-pop`(z-index 40)과 좌표가 같아
           * 메뉴를 다시 열면 문구가 그 아래로 가려진다. 메뉴를 닫으면 다시 보인다.
           */}
          {exportFailed && !exportOpen && (
            <span style={EXPORT_ERROR_TEXT} role="alert">
              내보내기 실패
            </span>
          )}
        </div>
      </div>

      {/* 2026-09-04 갱신 — 본문 좌(§3+§6) / 우(§7+§8) 2열 레이아웃.
          가이드 §7/§8 결정에 따라 §7·§8 카드는 우측 사이드바에 배치 (이미지 mock 과 동일).
          핵심 API 응답 envelope 에 emdCompare[]/negEmdCompare[] 가 포함되어 있어
          같은 호출에서 한 번에 갱신 — §2 필터 변경 시 두 카드도 자동 재집계. */}
      {/* 2026-09-21 (TSK-4165) — 공용 `.content`(dashboard.css — padding 0 40 24 · gap 24)로 감싼다.
          다른 9개 화면은 모두 이 래퍼를 쓰는데 민원현황만 KPI·그리드가 각자 24px 여백을 가져,
          사이드바 ↔ 지도 사이가 시안(40px)보다 16px 좁고 필터바(40px)와 왼쪽 선이 어긋났다. */}
      <div className="content">
        {/* §3 — KPI 카드 4종 */}
        <KpiBar kpi={kpi} loading={loading} error={kpiError} />
        <div className="minwon-main-grid">
          <div className="minwon-main-grid__left">
            {/* §6 — Leaflet 지도 + §4·§5 통합 LayerCard (지도 안 좌상단 floating).
              분석레이어 라디오 + 토글 2종 + 범례가 한 카드에 묶여 있다. */}
            <MinwonMap
              view={view}
              periodLabel={periodText}
              zones={zones}
              toggles={toggles}
              parkingLots={parkingLots}
              adminDistricts={adminDistricts}
              selectedEmdCd={selectedEmdCd}
              selectedEmdSeq={selectedEmdSeq}
              onSelectResult={handleSelectResult}
              heatPoints={heatPoints}
              displayMode={displayMode}
              onDisplayModeChange={setDisplayMode}
              /* 🔴 Q1 리셋은 여기 한 곳 — LayerCard 라디오 → MinwonMap → 이 콜백 경로로만 불린다
                 (`view` effect 에 걸면 벨 포커스의 `setView` 까지 리셋된다 — 위 `displayMode` 주석). */
              onViewChange={(v) => {
                setView(v)
                setDisplayMode('emd')
              }}
              onTogglePublicParking={(next) => setToggles((t) => ({ ...t, publicParking: next }))}
              onToggleAdminBoundary={(next) => setToggles((t) => ({ ...t, adminBoundary: next }))}
            />
          </div>

          <aside
            className="minwon-main-grid__right"
            aria-label="읍·면·동 순위 / 부정 민원 카드"
          >
            {/* §7 — 분석레이어(`view`)에 따라 첫 카드를 바꾼다 (TSK-4181).
                민원은 `emdCompare[]`(증감률·대표유형 보유), 단속·부족은 `zones[]` — 의도된
                비대칭이다 (D2). */}
            {view === 'most-complaint' && (
              <EmdCompareCard
                emdCompare={kpi?.emdCompare ?? []}
                loading={loading}
                onSelectEmd={handleSelectEmd}
                compareLabel={compareText}
              />
            )}
            {view === 'enforcement' && (
              <EmdCrdnRankCard
                zones={zones}
                loading={zonesLoading}
                onSelectEmd={handleSelectEmd}
                compareLabel={compareText}
              />
            )}
            {view === 'shortage' && (
              <EmdShortageRankCard
                zones={zones}
                loading={zonesLoading}
                onSelectEmd={handleSelectEmd}
                compareLabel={compareText}
              />
            )}
            {/* §8 — 부정 민원 상위 지역. 레이어와 무관하게 항상 표시한다 (D3). */}
            <NegEmdCompareCard negEmdCompare={kpi?.negEmdCompare ?? []} loading={loading} />
          </aside>
        </div>
      </div>

      {/* 벨 알림의 동을 지도에서 활성화하지 못했을 때 (TSK-4267 D8). 원인을 단정하지 않는다. */}
      {focusMissOpen && (
        <Modal
          title="알림"
          variant="sm"
          onClose={() => setFocusMissOpen(false)}
          footer={
            <button className="btn-save" type="button" onClick={() => setFocusMissOpen(false)}>
              확인
            </button>
          }
        >
          <p style={MODAL_MSG}>{FOCUS_EMD_MISSING_MESSAGE}</p>
        </Modal>
      )}
    </>
  )
}
