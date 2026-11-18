import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import axios from 'axios'
import { useLocation, useNavigate } from 'react-router-dom'
import Breadcrumb from '../../components/common/Breadcrumb'
import TopbarActions from '../../components/common/TopbarActions'
import ConfirmDeleteModal from '../../components/common/ConfirmDeleteModal'
import Modal from '../../components/common/Modal'
import PolicySettingPanel from '../../components/policysim/PolicySettingPanel'
import EffectPredictionPanel from '../../components/policysim/EffectPredictionPanel'
import ScenarioCompareTable from '../../components/policysim/ScenarioCompareTable'
import CoefficientPopover, { CoefficientBanner } from '../../components/analysis/CoefficientPopover'
import { useAnalysisCoefficient } from '../../lib/analysis/useAnalysisCoefficient'
import type { CoeffScope } from '../../lib/analysis/coefficientMeta'
import type { PolicySimExportCriteria } from '../../lib/analysis/policySimExport'
import {
  parseRoadMtncLen,
  pickRecommended,
  pickRepairN100,
  toSavedScenario,
  type RepairN100,
  type Scenario,
} from '../../components/policysim/policySimView'
import { fetchAnalysisWeights } from '../../api/analysisWeight'
import { fetchRegions, type RegionItem } from '../../api/regions'
import type { PageResponse } from '../../api/reports'
import {
  deletePolicySim,
  fetchSavedPolicySims,
  savePolicySim,
  type AddPkltSlot,
  type ExpandMethod,
  type FeeAplcnMthd,
  type FeeAplcnZone,
  type FeeIncrsRt,
  type PlcyTypeCd,
  type PolicySimParams,
  type PredcMm,
  type RoadMtncType,
  type SavedPolicySim,
} from '../../api/policySim'

/*
 * 정책 효과 시뮬레이션 `/analysis/policy-simulation` (TSK-4037 Step 6 · 7 · TSK-4038 Step 5 이면도로 정비 ·
 * TSK-4039 Step 6 요금제 적용).
 *
 * 레이아웃은 시안(https://innopamsolution.github.io/jeju-datahub/)의 정책효과시뮬레이션 화면 —
 * `content` 안에 2열(좌 `정책 변수 설정` · 우 `효과 예측`) + 아래 `시나리오 비교` 카드다(지도 없음).
 *
 * 🔴 **진입 직후에는 계산하지 않는다** — 생활권·집중구역과 같다. 마운트 시에는 지역 목록(`GET /api/regions`)과
 * 내 저장분 최신 100건만 받는다.
 * 🔴 **[시뮬레이션 실행] = 계산 + 저장**(TSK-4226) — `POST /api/analysis/policy-sim/results` 1회다.
 * 서버가 저장하면 201, 차단 상태(`NO_ENF`·`NO_SPLY`)면 저장하지 않고 200 이다. 저장 뒤 목록(최신 100건)을 다시 받아
 * 첫 줄(저장 시각 최신순)을 효과 예측 카드에 세운다 — 카드 값은 **POST 응답**이다(목록 행엔 응답 전용 칸이 비어 있다).
 *
 * 시나리오 비교 목록 = **내 저장분**(`GET /api/analysis/policy-sim/results`, 최신순 100건 1회 조회 — 페이징·별도 카드 없음 · TSK-4226 D4).
 * 행을 누르면 **저장 당시 값 그대로** 그린다(재계산 호출 없음). 삭제는 확인 팝업 → `DELETE`.
 * 🔴 `calcSttsCd` 가 `NO_ENF`·`NO_SPLY` 면 **팝업 하나만** 띄우고 결과 카드·차트·목록 행을
 * 만들지 않는다(Step 7-1 · 사용자 결정 2026-10-06). `NO_ENF` 가 우선이고, 같이 온 `excsYn` 은 무시된다.
 *
 * 분석 계수 일시 조정(TSK-4332) — 훅 `useAnalysisCoefficient('POLICY')` 는 **이 페이지**가 갖고, 설정 패널에는
 * 버튼(`coeffAction`)·배너(`coeffBanner`)·열림(`coeffOpen`) 슬롯만 넘긴다. 스코프는 현재 정책 유형(`PLCY_COEFF_SCOPE`).
 * 🔴 `적용`·`초기화` 는 API 를 부르지 않는다(D3 — 실행 1회 = 저장 1행). 다음 [시뮬레이션 실행]의 POST 바디에
 * `appliedRequest(scope).vrblVls`(공통 + 현재 유형 변경분 — D4)가 실린다. 보고서 요청은 `rsltId` 전용이라
 * `markRan`/`ranRequest` 를 쓰지 않는다(시나리오별 계수는 서버가 저장분 `vrblAjmt` 에서 옮긴다 — D10).
 * 이면도로 확보 구획 힌트는 훅의 **적용값** `N100_*` 이며(D8), 훅이 그 행을 못 그리면 종전 조회값(`repairN100`)을 쓴다.
 * 설정 조회가 실패하면 버튼이 숨고 설정값으로 실행된다(D16).
 */

/** 정책 유형 → 계수 스코프(TSK-4332 D1). 서버도 `plcyType` 으로 같은 스코프를 고른다. */
const PLCY_COEFF_SCOPE: Record<PlcyTypeCd, CoeffScope> = {
  EXPAND: 'POLICY_EXPAND',
  REPAIR: 'POLICY_REPAIR',
  FEE: 'POLICY_FEE',
}

/** 조회 상태. 실패·팝업은 `idle` 로 되돌린다(조건과 맞지 않는 직전 결과를 남기지 않는다). */
type SimStatus = 'idle' | 'loading'

/** NO_ENF·NO_SPLY 팝업 문구 — 계획서 Step 7-1 원문. */
const BLOCK_MSG: Record<'NO_ENF' | 'NO_SPLY', (nm: string) => string> = {
  NO_ENF: (nm) => `${nm}의 단속기록이 없습니다.`,
  NO_SPLY: (nm) => `${nm}의 공영주차장이 없습니다.`,
}

/** 저장분 조회 건수(항상 0페이지 1회) — 서버 상한과 같은 값 — 넘기면 400. */
const SAVED_FETCH_SIZE = 100

/** 저장 목록 조회 실패 문구(목록 안 한 줄). */
const SAVED_LOAD_ERROR = '저장된 시나리오를 불러오지 못했습니다.'
/** 삭제 404 — 없거나 남의 것(서버가 둘을 구분하지 않는다). */
const DELETE_GONE_MSG = '이미 삭제되었거나 볼 수 없는 시나리오입니다.'
/** 대시보드에서 고른 행이 목록(최신 100건)에 없다 — 삭제됐거나 100건 밖이다(TSK-4255). */
const SELECT_GONE_MSG = '선택한 시나리오를 찾을 수 없습니다. 삭제되었거나 최근 100건 밖의 시나리오입니다.'

/* ── 명세 2.1 기본값 ─────────────────────────────────── */
const DEFAULT_PLCY_TYPE: PlcyTypeCd = 'EXPAND'
const DEFAULT_SGG_NM = '제주시'
/** 화면 입력 폐지(TSK-4226 D6) · 서버 필수값(@NotNull)이라 고정 전송 · 공한지 조성 반환분 T 로만 쓰인다. */
const FIXED_PREDC_MM: PredcMm = 3
const DEFAULT_EXPAND_METHOD: ExpandMethod = 'VACLOT'
/** 명세 2.1 표에 기본 표시가 없어 시안 select 의 첫 옵션(50면)을 그대로 쓴다. */
const DEFAULT_ADD_PKLT_SLOT: AddPkltSlot = 50
/** 이면도로 기본값(TSK-4038 Step 5-1) — 주거지역형 · 600m. */
const DEFAULT_ROAD_MTNC_TYPE: RoadMtncType = 'RSDNC'
const DEFAULT_ROAD_MTNC_LEN = '600'
/**
 * 요금제 기본값(TSK-4039 Step 6-1 — 세그먼트 첫 옵션) — 요금 인상 · +10%.
 * 적용 급지(TSK-4291)는 첫 옵션 `전체` — 시·읍면동을 바꿔도 유지하고, 읍면동을 지정하면 세그먼트를 숨기고 요청에서 뺀다.
 */
const DEFAULT_FEE_APLCN_MTHD: FeeAplcnMthd = 'RAISE'
const DEFAULT_FEE_APLCN_ZONE: FeeAplcnZone = 'ALL'
const DEFAULT_FEE_INCRS_RT: FeeIncrsRt = 10

/**
 * 실패 문구. 503 은 원인이 분명해(계수 행 누락 — 마이그레이션 81 미적용) 따로 알린다.
 * 400 바디에는 `message` 가 실리지 않는 경우가 있어(Boot 기본 `include-message: never`) 고정 문구를 쓴다.
 */
function errorText(e: unknown): string {
  if (axios.isAxiosError(e)) {
    const code = (e.response?.data as { code?: string } | undefined)?.code
    if (e.response?.status === 503 && code === 'POLICY_SIM_PARAM_MISSING') {
      return '정책 시뮬레이션 계수가 설정되어 있지 않습니다. 관리자에게 문의해 주세요.'
    }
  }
  return '정책 효과 계산에 실패했습니다. 조건을 확인하고 잠시 후 다시 시도해 주세요.'
}

/* ── 시안 인라인 스타일 ────────────────────────────────── */
const CONTENT: CSSProperties = { gap: 20 }
const TWO_COL: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 20,
  alignItems: 'stretch',
}
const MODAL_MSG: CSSProperties = {
  margin: 0,
  fontSize: 'var(--body2-size)',
  color: 'var(--text-strong)',
}

export default function PolicySimulationPage() {
  // ── 정책 변수 입력 (지역 `''` = 전체) ──
  const [plcyType, setPlcyType] = useState<PlcyTypeCd>(DEFAULT_PLCY_TYPE)
  const [regions, setRegions] = useState<RegionItem[]>([])
  const [regionsFailed, setRegionsFailed] = useState(false)
  const [reloadRegionsKey, setReloadRegionsKey] = useState(0)
  const [sggNm, setSggNm] = useState(DEFAULT_SGG_NM)
  const [emdCd, setEmdCd] = useState('')
  const [expandMethod, setExpandMethod] = useState<ExpandMethod>(DEFAULT_EXPAND_METHOD)
  const [addPkltSlot, setAddPkltSlot] = useState<AddPkltSlot>(DEFAULT_ADD_PKLT_SLOT)
  const [roadMtncType, setRoadMtncType] = useState<RoadMtncType>(DEFAULT_ROAD_MTNC_TYPE)
  /** 정비 구간 연장 입력 문자열 — 판정은 `parseRoadMtncLen`(1~10000 정수). */
  const [roadMtncLenText, setRoadMtncLenText] = useState(DEFAULT_ROAD_MTNC_LEN)
  /** 이면도로 환산 힌트용 n_100(`GET /api/analysis-weights` POLICY_SIM 행). 이면도로를 처음 고를 때 한 번 받는다. */
  const [repairN100, setRepairN100] = useState<RepairN100 | null>(null)
  const [repairN100Failed, setRepairN100Failed] = useState(false)
  const [feeAplcnMthd, setFeeAplcnMthd] = useState<FeeAplcnMthd>(DEFAULT_FEE_APLCN_MTHD)
  const [feeAplcnZone, setFeeAplcnZone] = useState<FeeAplcnZone>(DEFAULT_FEE_APLCN_ZONE)
  const [feeIncrsRt, setFeeIncrsRt] = useState<FeeIncrsRt>(DEFAULT_FEE_INCRS_RT)

  // ── 분석 계수 조정 (TSK-4332) ──
  const coeff = useAnalysisCoefficient('POLICY')
  const [coeffOpen, setCoeffOpen] = useState(false)
  const coeffScope = PLCY_COEFF_SCOPE[plcyType]
  const coeffBanner = coeff.banner(coeffScope)
  /**
   * 이면도로 힌트 n_100 — 훅의 **적용값**(조정하지 않았으면 설정값)이 1순위다(D8). 훅이 그 행을 그리지 못하면
   * (설정 조회 실패·`useYn='N'` 등) 종전 `pickRepairN100` 조회값(`bscStngVl` 폴백 포함)으로 떨어진다.
   */
  const coeffRepairN100 = ((): RepairN100 | null => {
    if (!coeff.isAvailable('POLICY_REPAIR')) return null
    const v = coeff.appliedRequest('POLICY_REPAIR').values
    const rsdnc = v.N100_RSDNC
    const cmrc = v.N100_CMRC
    return typeof rsdnc === 'number' && typeof cmrc === 'number'
      ? { RSDNC: rsdnc, CMRC: cmrc }
      : null
  })()

  // ── 조회 상태 ──
  const [status, setStatus] = useState<SimStatus>('idle')
  const [error, setError] = useState<string | null>(null)
  /** NO_ENF·NO_SPLY 팝업 문구. null 이면 팝업 없음. */
  const [blockMsg, setBlockMsg] = useState<string | null>(null)

  // ── 시나리오 비교 ──
  /** 내 저장분 최신 100건(서버 응답 그대로). null = 아직 한 번도 못 받음. */
  const [savedPage, setSavedPage] = useState<PageResponse<SavedPolicySim> | null>(null)
  const [savedLoading, setSavedLoading] = useState(false)
  const [savedError, setSavedError] = useState<string | null>(null)
  /**
   * 효과 예측 카드에 그려진 행 — 키가 아니라 **행 자체**를 들고 있다. 재조회로 그 행이 목록에서
   * 빠져도(삭제·100건 밖) 카드는 그대로 남는다(목록 강조만 사라진다).
   */
  const [active, setActive] = useState<Scenario | null>(null)
  const [checkedKeys, setCheckedKeys] = useState<string[]>([])
  /** 저장 목록 응답 순서 가드 — 늦게 도착한 이전 요청이 최신 목록을 덮지 않게 한다(실행·삭제가 연달아 재조회를 부른다). */
  const savedReqRef = useRef(0)

  /*
   * 대시보드 "정책 효과 시뮬레이션" 카드에서 넘어온 행(TSK-4255 — `state: { selectRsltId }`).
   * 첫 목록 응답이 오면 그 행을 **행 클릭과 똑같이** 선택하고(`setActive` — 재계산 없음) 한 번만 쓴다.
   * history state 도 비운다 — 남겨 두면 새로고침·뒤로가기로 돌아올 때마다 다시 선택된다.
   */
  const location = useLocation()
  const navigate = useNavigate()
  const pendingRsltIdRef = useRef<number | null>(
    (location.state as { selectRsltId?: unknown } | null)?.selectRsltId != null
      ? Number((location.state as { selectRsltId: unknown }).selectRsltId)
      : null,
  )

  // ── 저장분 삭제 확인 ──
  const [confirmDel, setConfirmDel] = useState<Scenario | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [delError, setDelError] = useState<string | null>(null)

  // 지역 select 원본은 마운트 때 한 번만 받는다(계산 API 와 무관한 목록 조회).
  useEffect(() => {
    let cancelled = false
    fetchRegions()
      .then((list) => {
        if (cancelled) return
        setRegions(list)
        setRegionsFailed(false)
      })
      .catch(() => {
        // 조용히 비우지 않는다 — 목록이 비면 "지역 설정이 안 된다" 로 보이므로 원인을 알린다.
        if (cancelled) return
        setRegions([])
        setRegionsFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [reloadRegionsKey])

  /**
   * 내 저장분 최신 `SAVED_FETCH_SIZE` 건(0페이지)을 불러온다(TSK-4226 D4 — 페이징 없음).
   * 성공하면 받은 응답을, 실패·추월당하면 null 을 돌려준다.
   */
  const loadSaved = useCallback(async (): Promise<PageResponse<SavedPolicySim> | null> => {
    const req = ++savedReqRef.current
    setSavedLoading(true)
    try {
      const data = await fetchSavedPolicySims(0, SAVED_FETCH_SIZE)
      if (req !== savedReqRef.current) return null
      setSavedPage(data)
      setSavedError(null)
      return data
    } catch {
      if (req !== savedReqRef.current) return null
      setSavedError(SAVED_LOAD_ERROR)
      return null
    } finally {
      if (req === savedReqRef.current) setSavedLoading(false)
    }
  }, [])

  /*
   * 이면도로 힌트 계수 — 이면도로를 처음 고를 때만 받는다(확충만 쓰는 화면에는 호출을 더하지 않는다).
   * 조회 GET 은 메뉴 인가 대상이 아니다. 실패하면 힌트 자리에 안내만 두고 실행은 막지 않는다
   * (결과 카드는 응답 `vldAddPkltSlot` 을 쓰므로 힌트가 없어도 계산은 정확하다).
   */
  const needN100 = plcyType === 'REPAIR' && repairN100 === null && !repairN100Failed
  useEffect(() => {
    if (!needN100) return
    let cancelled = false
    fetchAnalysisWeights()
      .then((types) => {
        if (cancelled) return
        const v = pickRepairN100(types)
        if (v) setRepairN100(v)
        else setRepairN100Failed(true) // 계수 행 없음(마이그레이션 82 미적용) — 하드코딩으로 메우지 않는다
      })
      .catch(() => {
        if (!cancelled) setRepairN100Failed(true)
      })
    return () => {
      cancelled = true
    }
  }, [needN100])

  // 진입 시 내 저장분 최신 100건을 받는다 — 계산 API 는 부르지 않는다.
  useEffect(() => {
    void loadSaved()
  }, [loadSaved])

  /** 보이는 행 = 불러온 저장분 전부(최신 100건 · 서버 최신순 그대로). */
  const scenarios = useMemo<Scenario[]>(
    () => (savedPage?.content ?? []).map(toSavedScenario),
    [savedPage],
  )
  // 대시보드에서 고른 행 선택(TSK-4255) — 목록을 처음 받았을 때 한 번만. 조회 실패면 기다린다(재시도 성공 때 고른다).
  useEffect(() => {
    const rsltId = pendingRsltIdRef.current
    if (rsltId == null || !savedPage) return
    pendingRsltIdRef.current = null
    navigate(location.pathname, { replace: true, state: null })
    const hit = scenarios.find((s) => s.rsltId === rsltId)
    if (hit) setActive(hit)
    else setBlockMsg(SELECT_GONE_MSG)
  }, [savedPage, scenarios, navigate, location.pathname])

  // 🔴 추천은 **보이는 행** 기준으로, 목록이 바뀔 때마다(실행·삭제·재조회) 다시 판정한다(Step 7-2·7-4).
  const recommendedKey = useMemo(() => pickRecommended(scenarios), [scenarios])

  /*
   * 내보내기 요청 조립(TSK-4229 Step 8 · D4·D5). 🔴 순서는 **목록에 보이는 순서**(`scenarios` = 불러온
   * 저장분 전부)에서 체크된 것만이다 — `checkedKeys` 는 체크한 순서로 쌓이므로 그 배열 순서를 쓰지 않는다.
   * 모든 행이 저장분이라 `rsltId` 만 보낸다(TSK-4226 — 실행이 곧 저장).
   * `headIdx` = 대표 = 선택한 것 중 목록 첫 행 — 대표 규칙이 바뀌면 이 한 줄만 고친다.
   */
  const exportCriteria = useMemo<PolicySimExportCriteria>(
    () => ({
      headIdx: 0,
      scenarios: scenarios
        .filter((s) => checkedKeys.includes(s.key))
        .map((s) => ({ rsltId: s.rsltId })),
    }),
    [scenarios, checkedKeys],
  )

  // 시를 바꾸면 읍면동은 `전체` 로 되돌린다 — 다른 시의 읍면동 코드가 남으면 그 읍면동이 계산된다.
  const changeSgg = (next: string) => {
    setSggNm(next)
    setEmdCd('')
  }

  /** 팝업의 `{읍면동명}` — 선택한 읍면동 이름(`/api/regions` 의 `emdNm`). 없으면 응답 `anlsRngCn`. */
  const rangeName = (params: PolicySimParams, anlsRngCn: string): string => {
    if (params.emdCd) {
      for (const g of regions) {
        const hit = g.emds.find((e) => e.emdCd === params.emdCd)
        if (hit) return hit.emdNm
      }
    }
    return anlsRngCn
  }

  /**
   * 현재 입력 → 요청 조건. 유형별 조건만 채운다 — 다른 유형 칸은 null(목록 이름·저장 바디가 섞이지 않게).
   * FEE 는 화면에서 고른 급지를 싣는다(TSK-4291) — 읍면동을 지정하면 `toQuery` 가 생략하고 서버가 그 읍면동의 급지로
   * 채운다 · 인상률은 RAISE 만.
   */
  const buildParams = (roadMtncLen: number | null): PolicySimParams => {
    const isExpand = plcyType === 'EXPAND'
    const isRepair = plcyType === 'REPAIR'
    const isFee = plcyType === 'FEE'
    return {
      plcyType,
      expandMethod: isExpand ? expandMethod : null,
      addPkltSlot: isExpand ? addPkltSlot : null,
      roadMtncType: isRepair ? roadMtncType : null,
      roadMtncLen: isRepair ? roadMtncLen : null,
      feeAplcnMthd: isFee ? feeAplcnMthd : null,
      feeAplcnZone: isFee ? feeAplcnZone : null,
      feeIncrsRt: isFee && feeAplcnMthd === 'RAISE' ? feeIncrsRt : null,
      predcMm: FIXED_PREDC_MM,
      sggNm,
      emdCd,
    }
  }

  /**
   * 적용 대상 면수 힌트(TSK-4039 Step 6-1) — 효과 예측에 그려진 FEE 결과가 **지금 입력과 같은 조건**일 때만
   * 그 `aplcnTrgtSlot` 을 보인다(사전 조회 API 가 없어 실행 전에는 숨긴다). 예측 기간·인상률은 면수와 무관해 비교하지 않는다.
   * 급지(TSK-4291)는 **읍면동이 없을 때만** 비교한다 — 읍면동 지정 시 요청에서 빠지고 서버가 그 읍면동 급지로 채운다.
   * 비교 대상은 화면 상태가 아니라 `buildParams` 가 싣는 값이며 null ≡ `'ALL'` 로 맞춘다.
   */
  const feeAplcnTrgtSlot = (() => {
    if (plcyType !== 'FEE' || !active || active.params.plcyType !== 'FEE') return null
    const a = active.params
    // 여기서는 plcyType === 'FEE' 라 buildParams 가 싣는 급지 = 상태값(null 이 될 수 없다).
    const sameZone = !!emdCd || (a.feeAplcnZone ?? 'ALL') === feeAplcnZone
    const same =
      a.feeAplcnMthd === feeAplcnMthd &&
      (a.sggNm ?? '') === sggNm &&
      (a.emdCd ?? '') === emdCd &&
      sameZone
    return same ? (active.result.aplcnTrgtSlot ?? null) : null
  })()

  /**
   * [시뮬레이션 실행] = 계산 + 저장(TSK-4226) — `POST …/results` 1회. `running` 잠금이 곧 중복 저장 방지다.
   * 저장됐으면 저장분 목록을 다시 받아 **첫 줄**(저장 시각 최신순 = 방금 저장한 행)로 잇는다 — POST 응답에는
   * `rsltId` 가 없다. 카드는 **POST 응답 값**으로 그린다(목록 행엔 `wrnngCn` 등 응답 전용 칸이 null 이다).
   */
  const runSimulation = async () => {
    // REPAIR 는 연장이 유효할 때만(버튼도 같은 판정으로 닫힌다).
    const roadMtncLen = parseRoadMtncLen(roadMtncLenText)
    if (plcyType === 'REPAIR' && roadMtncLen === null) return
    const params: PolicySimParams = buildParams(roadMtncLen)
    setStatus('loading')
    setError(null)
    // 효과 예측 카드를 비운다 — 남겨 두면 계산 중에 이전 조건의 결과가 그대로 보인다.
    setActive(null)
    try {
      // 201 저장 · 200 = NO_ENF·NO_SPLY(저장 안 함). 계수는 적용값 중 공통 + 현재 유형 변경분만(TSK-4332 D4).
      const result = await savePolicySim(
        params,
        coeff.appliedRequest(PLCY_COEFF_SCOPE[params.plcyType]).vrblVls,
      )
      // 🔴 NO_ENF 우선. 판정은 `calcSttsCd` 로만 한다(null 패턴 역추론 금지). 서버가 저장하지 않았다 — 목록 재조회 없음.
      if (result.calcSttsCd === 'NO_ENF' || result.calcSttsCd === 'NO_SPLY') {
        setBlockMsg(BLOCK_MSG[result.calcSttsCd](rangeName(params, result.anlsRngCn)))
        return
      }
      const page = await loadSaved()
      const first = page?.content[0]
      if (!first) {
        // 재조회 실패·추월 — 저장은 끝났지만 `rsltId` 를 몰라 행을 만들 수 없다(임시 키·가짜 rsltId 를 만들지 않는다).
        setError('결과는 저장됐습니다. 시나리오 비교 목록에서 행을 눌러 확인해 주세요.')
        return
      }
      const row: Scenario = { ...toSavedScenario(first), result }
      setCheckedKeys((keys) => [...keys, row.key])
      setActive(row)
    } catch (e) {
      setError(errorText(e))
    } finally {
      setStatus('idle')
    }
  }

  // 팝업을 닫으면 조건 입력 상태로 돌아간다(입력값은 그대로 둔다).
  const closeBlock = useCallback(() => setBlockMsg(null), [])

  const toggleCheck = (key: string) =>
    setCheckedKeys((keys) => (keys.includes(key) ? keys.filter((x) => x !== key) : [...keys, key]))
  // 전체 선택은 **보이는 행**(= 불러온 전부) 단위 — 목록에서 빠진 행(삭제·100건 밖)의 체크는 건드리지 않는다.
  // `visible` = 표에 보이는(정책 유형 필터를 통과한) 행 — 필터 밖에서 체크한 행은 건드리지 않는다.
  const toggleAll = (visible: string[]) =>
    setCheckedKeys((keys) => {
      const allOn = visible.length > 0 && visible.every((k) => keys.includes(k))
      return allOn
        ? keys.filter((k) => !visible.includes(k))
        : [...keys, ...visible.filter((k) => !keys.includes(k))]
    })
  /** 행 클릭 — 저장분도 목록 행의 값을 그대로 카드에 넘긴다(재계산 호출 없음). */
  const selectRow = (key: string) => {
    const hit = scenarios.find((s) => s.key === key)
    if (!hit) return
    setActive(hit)
  }
  /** 목록·카드·체크에서 그 행을 걷어낸다(화면 상태만). */
  const dropFromView = (key: string) => {
    setCheckedKeys((keys) => keys.filter((x) => x !== key))
    if (active?.key === key) setActive(null)
  }
  // 삭제는 항상 확인 팝업 뒤 DELETE(Step 7-4 · TSK-4226 — 모든 행이 저장분이다).
  const deleteRow = (key: string) => {
    const hit = scenarios.find((s) => s.key === key)
    if (!hit) return
    setDelError(null)
    setConfirmDel(hit)
  }

  const closeConfirmDel = useCallback(() => {
    if (deleting) return
    setConfirmDel(null)
    setDelError(null)
  }, [deleting])

  const confirmDelete = async () => {
    if (!confirmDel || deleting) return
    const row = confirmDel
    setDeleting(true)
    setDelError(null)
    try {
      await deletePolicySim(row.rsltId) // 204
    } catch (e) {
      if (!(axios.isAxiosError(e) && e.response?.status === 404)) {
        setDelError('삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.')
        setDeleting(false)
        return
      }
      // 404 = 이미 지워졌거나 남의 것 — 어느 쪽이든 목록에서 걷어내고 알린다.
      setBlockMsg(DELETE_GONE_MSG)
    }
    setDeleting(false)
    setConfirmDel(null)
    dropFromView(row.key)
    // 목록(최신 100건)을 다시 받는다 — 100건 밖에 있던 행이 한 줄 올라온다.
    void loadSaved()
  }

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="분석·시뮬레이션" page="정책 효과 시뮬레이션" />
          <h1 className="page-title">정책효과시뮬레이션</h1>
          <p className="page-sub" style={{ color: 'var(--orange-39)' }}>
            ※ 참고: 가정값에 기반한 시뮬레이션 결과이며 실제 정책 효과를 보장하지 않습니다.
          </p>
        </div>
        <TopbarActions />
      </header>

      <div className="content" style={CONTENT}>
        <section style={TWO_COL}>
          <PolicySettingPanel
            plcyType={plcyType}
            onPlcyTypeChange={setPlcyType}
            regions={regions}
            regionsFailed={regionsFailed}
            onRetryRegions={() => setReloadRegionsKey((k) => k + 1)}
            sggNm={sggNm}
            emdCd={emdCd}
            onSggChange={changeSgg}
            onEmdChange={setEmdCd}
            expandMethod={expandMethod}
            onExpandMethodChange={setExpandMethod}
            addPkltSlot={addPkltSlot}
            onAddPkltSlotChange={setAddPkltSlot}
            roadMtncType={roadMtncType}
            onRoadMtncTypeChange={setRoadMtncType}
            roadMtncLenText={roadMtncLenText}
            onRoadMtncLenChange={setRoadMtncLenText}
            repairN100={coeffRepairN100 ?? repairN100}
            repairN100Failed={repairN100Failed}
            feeAplcnMthd={feeAplcnMthd}
            onFeeAplcnMthdChange={setFeeAplcnMthd}
            feeAplcnZone={feeAplcnZone}
            onFeeAplcnZoneChange={setFeeAplcnZone}
            feeIncrsRt={feeIncrsRt}
            onFeeIncrsRtChange={setFeeIncrsRt}
            feeAplcnTrgtSlot={feeAplcnTrgtSlot}
            running={status === 'loading'}
            onRun={runSimulation}
            error={error}
            coeffAction={
              <CoefficientPopover
                coeff={coeff}
                scope={coeffScope}
                segments
                layout="policy"
                placement="below"
                disabled={status === 'loading'}
                // 🔴 D3 — `적용` 은 값만 확정한다(재실행·API 호출 없음). 다음 [시뮬레이션 실행]부터 실린다.
                onApply={() => {}}
                onOpenChange={setCoeffOpen}
              />
            }
            coeffBanner={
              coeffBanner ? (
                <CoefficientBanner
                  banner={coeffBanner}
                  disabled={status === 'loading'}
                  // 🔴 D3 — 초기화도 API 를 부르지 않는다.
                  onReset={() => {
                    coeff.reset(coeffScope)
                  }}
                />
              ) : undefined
            }
            coeffOpen={coeffOpen}
          />

          <EffectPredictionPanel
            scenario={active}
            loading={status === 'loading'}
            plcyType={plcyType}
          />
        </section>

        <ScenarioCompareTable
          scenarios={scenarios}
          activeKey={active?.key ?? null}
          recommendedKey={recommendedKey}
          checkedKeys={checkedKeys}
          onToggleCheck={toggleCheck}
          onToggleAll={toggleAll}
          onSelect={selectRow}
          onDelete={deleteRow}
          savedLoading={savedLoading && savedPage === null}
          savedError={savedError}
          onRetrySaved={() => {
            void loadSaved()
          }}
          exportCriteria={exportCriteria}
          onExportNotFound={() => {
            // 404 = 선택한 저장 결과가 이미 지워졌거나 남의 것 — 목록을 다시 받는다.
            void loadSaved()
          }}
          truncated={!!savedPage && savedPage.totalElements > savedPage.content.length}
        />
      </div>

      {blockMsg && (
        <Modal
          title="안내"
          variant="sm"
          onClose={closeBlock}
          footer={
            <button className="btn-save" type="button" onClick={closeBlock}>
              확인
            </button>
          }
        >
          <p style={MODAL_MSG}>{blockMsg}</p>
        </Modal>
      )}

      {confirmDel && (
        <ConfirmDeleteModal
          message="저장된 시나리오를 삭제하시겠습니까?"
          busy={deleting}
          error={delError}
          onCancel={closeConfirmDel}
          onConfirm={() => {
            void confirmDelete()
          }}
        />
      )}
    </>
  )
}
