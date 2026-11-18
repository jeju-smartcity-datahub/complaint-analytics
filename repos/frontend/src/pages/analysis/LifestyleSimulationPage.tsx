import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Breadcrumb from '../../components/common/Breadcrumb'
import TopbarActions from '../../components/common/TopbarActions'
import LifezoneMap from '../../components/lifezone/LifezoneMap'
import { monthsOfRange } from '../../components/lifezone/lifezoneCellPopup'
import LifezoneResultPanel from '../../components/lifezone/LifezoneResultPanel'
import LifezoneSupplyMap from '../../components/lifezone/LifezoneSupplyMap'
import LifezoneSupplyResultPanel from '../../components/lifezone/LifezoneSupplyResultPanel'
import LifezoneSettingPanel, {
  DEFAULT_GRID_SZ,
  DEFAULT_PERIOD,
  type LifezonePeriodKey,
} from '../../components/lifezone/LifezoneSettingPanel'
import {
  fetchLifezoneAnalysis,
  fetchLifezoneCongestion,
  fetchLifezoneTargets,
  type LifezoneAnalysisResult,
  type LifezoneCell,
  type LifezoneCngstResult,
  type LifezoneGridSz,
  type LifezoneTargetCd,
  type LifezoneTargetInfo,
} from '../../api/lifezone'
import {
  fetchLifezoneSupply,
  type LifezoneSupplyGridSz,
  type LifezoneSupplyResult,
} from '../../api/lifezoneSupply'
import { fetchRegions, type RegionItem } from '../../api/regions'
import { formatCngstSupply, formatCngstVl, isCngstCell } from '../../components/lifezone/lifezoneGrade'
import { formatPeriodLabel, toYmd, validateRange, type RangeError } from '../../lib/dateRange'
import type { VrblVls } from '../../api/analysisWeight'
import type { CoeffScope } from '../../lib/analysis/coefficientMeta'
import {
  useAnalysisCoefficient,
  type CoeffRequest,
} from '../../lib/analysis/useAnalysisCoefficient'

/*
 * 생활권 시뮬레이션 `/analysis/lifestyle-simulation` (TSK-4088 Step 8).
 *
 * 레이아웃은 시안(https://innopamsolution.github.io/jeju-datahub/)의
 * `content--analysis > section.sim-stage`(전면 지도 + 오버레이 패널)를 그대로 옮겼다 —
 * 집중구역 화면(`HotspotPage`)과 **같은 셸**이다. 스타일은 `src/styles/analysis.css`
 * (TSK-4032 Step 9 + TSK-4088 Step 6), 조회는 Step 7 의 `api/lifezone.ts` 가 담당한다.
 *
 * 🔴 **진입 직후에는 분석을 호출하지 않는다**(D1). 시안은 결과가 열린 채 시작하지만(목데이터),
 * 실데이터에서는 "무엇을 분석한 결과인지 모르는 화면"이 된다 — `생활권 분석 실행` 을 눌러야 호출한다.
 * 그래서 분석을 거는 `useEffect` 가 없고, **세그먼트를 채우는 목록 조회 두 건**
 * (`/analysis/lifezone/targets` · `/regions`)만 마운트 시 각각 한 번 받는다.
 *
 * 이 단계(Step 8)의 범위
 *   - ✅ 화면 셸(topbar + 스테이지) · 설정 패널 4필드 · 실행/대기/실패/0건 안내
 *   - ✅ (Step 9) 격자 히트맵 레이어(`LifezoneMap`) — 셀 폴리곤·값 라벨·hover 팝업·범례 5행.
 *   - ✅ (Step 10) 우측 결과 패널(`LifezoneResultPanel`) — 요약 6행 + 순위 15행,
 *        셀 ↔ 목록 양방향 연동. 🔴 **선택 상태(`selection`)의 주인은 이 페이지 하나**다 —
 *        목록과 지도가 각자 상태를 들면 서로 다른 셀을 가리킨다.
 *   - ✅ (Step 12) 결과 패널의 `내보내기` 가 저장(POST) → PDF / Word 를 만든다.
 *        🔴 **이 페이지는 저장 결과로 화면을 갈아 끼우지 않는다.** 저장 응답은 스냅샷이라
 *        `dataBgngYmd`·`dataEndYmd` 를 의도적으로 비우고, 서버가 같은 조건을 결정적으로
 *        재분석하므로 숫자도 화면과 같다 — 대입하면 잃는 것만 생긴다.
 *
 * 🆕 **분석 계수 일시 조정(TSK-4325)** — 설정 패널 제목 줄의 버튼. 수급 탭 `LIFEZONE_SPLY` · 혼잡도 탭
 * `LIFEZONE_CNGST` 이고, 값은 한 벌로 두되 **요청에는 현재 탭 값만** 싣는다(D13). 🔴 `민원 건수` 탭은 버튼·배너가
 * 없다(D12 — 그 API 는 `vrblVls` 를 받지 않는다). `적용`·`초기화` 는 그 탭의 결과가 있으면 재분석하고(D6),
 * 보고서는 결과를 만든 실행의 값(`ranVrblVls`)을 패널 prop 으로 받아 싣는다(D7).
 */

/**
 * 조회 상태 3분기.
 * - `idle`   : 진입 직후 = 보여줄 결과가 없는 상태(D1). **실패도 여기로 되돌린다** —
 *              조건과 맞지 않는 직전 결과를 남겨 두면 격자 셀(Step 9)이 거짓을 가리킨다.
 * - `loading`: 조회 중(실행 버튼 비활성 + `분석 중…`)
 * - `done`   : 응답 도착. `cells` 가 빈 배열이어도 **정상 상태**다.
 */
type AnalysisStatus = 'idle' | 'loading' | 'done'

/**
 * 조회 결과 — 분석 유형마다 응답 형태가 달라 **태그를 함께 든다**(TSK-4089).
 *
 * `kind` 는 조회를 건 시점의 유형이며, 이후 사용자가 세그먼트만 바꿔도 결과는 그대로다 —
 * 화면이 설명하는 것은 "지금 고른 조건" 이 아니라 "돌린 결과" 이기 때문이다.
 */
/**
 * 선택된 셀 — 키와 증가값 한 쌍.
 *
 * 🔴 **선택 상태의 주인은 이 페이지 하나**다. 목록과 지도가 각자 상태를 들면 서로 다른 셀을 가리킨다.
 * 출처(목록/지도)와 무관하게 선택 이벤트마다 `seq` 를 올려 **새 객체**로 만든다 — 같은 셀을 다시
 * 골랐을 때도 목록이 그 행을 다시 화면 안으로 끌어오게 하기 위해서다.
 *
 * `key` 는 유형마다 다른 식별자다(민원건수 `gridCd` · 수급 `gridId`) — 페이지는 문자열로만 다루고
 * 각 패널이 자기 이름으로 받아 간다.
 */
type CellSelection = { key: string; seq: number } | null

type AnalysisResult =
  | { kind: 'cvlcpt'; data: LifezoneAnalysisResult }
  | { kind: 'supply'; data: LifezoneSupplyResult }
  /*
   * 혼잡도(TSK-4090) — 민원건수 컴포넌트를 재사용하지만 kind 를 나눈다. 셀·요약 필드가 더 있어
   * 같은 kind 면 혼잡도 필드를 읽을 곳에서 타입이 깨진다(`LifezoneCngstResult extends LifezoneAnalysisResult`
   * 라 렌더의 else 분기에는 그대로 들어간다).
   */
  | { kind: 'cngst'; data: LifezoneCngstResult }
  | null

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
 * 🔴 민원 보유 구간은 시드가 재적재될 때마다 통째로 옮겨지므로 날짜를 박지 않는다.
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
 * 실패 문구.
 *
 * 🔴 **응답 바디에서 사유를 꺼내지 않는다.** 백엔드는 `server.error.include-message` 를 설정하지
 * 않았고(Boot 기본 `never`) 전역 `@ControllerAdvice` 도 없어 **400 바디에 `message` 가 없다**
 * (2026-08-31 실측). 꺼내려 하면 빈 문자열이 되므로 처음부터 고정 문구를 쓴다 —
 * 미구현 분석유형의 사유는 400 이 아니라 `targets` 응답의 `unavailableRsn` 에서 읽는다(D2).
 */
const ERROR_TEXT = '생활권 분석에 실패했습니다. 조건을 확인하고 잠시 후 다시 시도해 주세요.'

/**
 * 수급 전용 조회 경로의 접미사. 서버 `LifezoneTarget.anlsPathSfx` 와 **문자 단위로 같아야 한다**.
 *
 * 🔴 유형 코드(`SPLY_DMND`)로 분기하지 않기 위한 값이다 — 어느 유형이 전용 파이프라인을 쓰는지는
 * 서버가 정하고, 화면은 "전용 경로가 있으면 그쪽 조회 함수를 쓴다" 만 안다.
 */
const SUPPLY_PATH_SFX = '/supply'

/**
 * 혼잡도 전용 조회 경로의 접미사(TSK-4090). 서버 `LifezoneTarget.anlsPathSfx` 와 문자 단위로 같아야 한다 —
 * 유형 코드(`CNGST`)로 분기하지 않기 위한 값이다(위 `SUPPLY_PATH_SFX` 와 같은 이유).
 */
const CNGST_PATH_SFX = '/congestion'

/**
 * 조회 경로 → 계수 스코프(TSK-4325). 🔴 유형 코드가 아니라 경로 접미사로 고른다(위 두 상수와 같은 이유).
 * 그 밖(민원 건수)은 `null` — 버튼·배너를 그리지 않고 요청에 싣지 않는다(D12).
 */
function coeffScopeOf(anlsPathSfx: string | null | undefined): CoeffScope | null {
  if (anlsPathSfx === SUPPLY_PATH_SFX) return 'LIFEZONE_SPLY'
  if (anlsPathSfx === CNGST_PATH_SFX) return 'LIFEZONE_CNGST'
  return null
}

/**
 * 혼잡도 셀 팝업 설명 줄(TSK-4090 §7-1 · §7-2).
 *
 * 🔁 TSK-4333 — `점유율 0.85(설정값)` 과 `피크시간 통행 데이터 없음` 줄을 팝업에서 걷었다(사용자 요청).
 * 점유율 설정값 안내는 결과 패널 기준 상자·보고서에 남아 있다.
 * 불법주차 근거(단속·민원)가 없는 칸과 면수 하한 미만 칸은 응답에서 제외되므로(2026-10-08) 그 상태 문구·
 * 흐리게 표시는 없다(종전 `공급 N면 — 참고값` 줄과 `dimmed` 는 도달하지 않아 걷었다).
 * 단속 0 · 민원>0 칸만 근거를 밝힌다.
 */
function cngstLines(cell: LifezoneCell): { lines?: string[] } {
  if (!isCngstCell(cell)) return {}
  const lines = [formatCngstSupply(cell)]
  if (cell.crdnCnt === 0 && cell.cvlcptCnt > 0) {
    lines.push(`단속 기록 없음 · 민원 ${cell.cvlcptCnt.toLocaleString()}건`)
  }
  return { lines }
}

export default function LifestyleSimulationPage() {
  // ── 분석 유형 (서버가 가용 여부까지 내린다 — D2) ──
  const [targets, setTargets] = useState<LifezoneTargetInfo[]>([])
  const [targetsFailed, setTargetsFailed] = useState(false)
  const [reloadTargetsKey, setReloadTargetsKey] = useState(0)
  /**
   * 선택된 분석내용코드. 초기값이 `''` 인 것은 **기본 유형을 프론트에 박지 않기 위해서**다 —
   * 목록을 받는 즉시 `available` 인 첫 항목으로 채운다. 끝내 못 받으면 `''` 인 채로 두고,
   * `fetchLifezoneAnalysis` 가 빈 값을 쿼리에서 빼 서버 기본값(`CVLCPT_CNT`)이 적용된다.
   */
  const [anlsTrgtCd, setAnlsTrgtCd] = useState<LifezoneTargetCd | ''>('')

  // ── 설정 패널 입력 (지역 `''` = 전체) ──
  const [regions, setRegions] = useState<RegionItem[]>([])
  // 지역 목록 조회 실패 여부 — 실패를 조용히 넘기면 select 이 `전체` 만 남아 원인을 알 수 없다.
  const [regionsFailed, setRegionsFailed] = useState(false)
  // `다시 시도` 를 누르면 값이 바뀌어 목록 조회 effect 가 다시 돈다.
  const [reloadRegionsKey, setReloadRegionsKey] = useState(0)
  const [sggNm, setSggNm] = useState('')
  const [emdCd, setEmdCd] = useState('')
  /**
   * 지도 이동 대상 — **마지막 "실행" 에 적용된 지역**(TSK-4227 R24 · D18 — D13 개정).
   *
   * 🔴 설정 패널 선택값(`sggNm`·`emdCd`)을 지도에 바로 넘기지 않는다 — 고르기만 하고 실행하지 않으면
   * 지도가 움직이지 않아야 한다. `runAnalysis` 가 그 실행의 지역으로 확정하고 `regionSeq` 를 올린다.
   */
  const [appliedRegion, setAppliedRegion] = useState({ sggCd: '', emdCd: '' })
  /**
   * 실행 회차 — 지도는 이 값이 바뀔 때만 이동한다(InvestPrrtMap `focusSeq` 선례). `0` = 아직 실행 전(이동 안 함).
   * 같은 지역으로 다시 실행해도 값이 올라 그 지역으로 다시 맞춘다(사용자가 지도를 움직였어도 실행 = 그 지역 보기).
   */
  const [regionSeq, setRegionSeq] = useState(0)
  const [period, setPeriod] = useState<LifezonePeriodKey>(DEFAULT_PERIOD)
  // 직접설정 칸은 기본 기간으로 채워 둔다 — 빈 칸으로 열면 실행 버튼이 막힌 채 시작한다.
  const [customFrom, setCustomFrom] = useState(() => recentMonths(Number(DEFAULT_PERIOD)).from)
  const [customTo, setCustomTo] = useState(() => recentMonths(Number(DEFAULT_PERIOD)).to)
  /**
   * 격자 크기(m).
   *
   * 유효 목록은 분석 유형마다 서버가 `targets[].gridSzs` 로 내리고, 유형을 바꿀 때 아래 effect 가
   * 범위 밖 값을 되돌린다. 🔁 TSK-4206 부터 민원건수·수급 모두 `1000/500/250/100` 이다.
   */
  const [gridSz, setGridSz] = useState<number>(DEFAULT_GRID_SZ)

  // ── 조회 상태 ──
  const [status, setStatus] = useState<AnalysisStatus>('idle')
  /**
   * 조회 결과 — **태그 붙은 합집합**이다 (TSK-4089).
   *
   * 🔴 두 유형의 응답을 한 타입으로 합치지 않는다. 민원건수는 순서형 5등급, 수급은 범주형
   * 4사분면이라 요약·범례·셀 지표가 겹치지 않는다. 태그(`kind`)를 함께 들면 **지도·패널이
   * 응답과 짝이 어긋날 수 없다** — 유형 세그먼트(현재 입력)로 고르면 조회 후 세그먼트만 바꿔도
   * 이전 결과가 엉뚱한 컴포넌트로 들어간다.
   */
  const [analysis, setAnalysis] = useState<AnalysisResult>(null)
  /** **결과를 만든 실행**의 기간 라벨. 성공 시에만 갱신한다 — 실행 뒤 기간 세그먼트를 바꿔도 패널 설명은 그대로다. */
  const [ranPeriodLabel, setRanPeriodLabel] = useState('')
  const [error, setError] = useState<string | null>(null)

  // ── 분석 계수 일시 조정 (TSK-4325) — React 상태만(D8) ──
  /** 초안·적용·마지막 실행값 3벌은 훅이 든다. 수급·혼잡도 두 탭의 값을 한 벌로 둔다(D13). */
  const coeff = useAnalysisCoefficient('LIFEZONE')
  /**
   * **결과를 만든 실행**의 계수(`vrblVls`) — 내보내기가 패널 prop 으로 받는다(D7). `null` = 설정값.
   * 🔴 성공 시에만 갱신한다 — 실패하면 `analysis` 가 비어 내보내기가 닫히고, 다음 성공이 다시 덮어쓴다.
   */
  const [ranVrblVls, setRanVrblVls] = useState<VrblVls | null>(null)
  /** 🔴 늦게 도착한 응답이 최신 실행을 덮지 않게 요청마다 번호를 붙인다(재분석 `적용` 이 겹칠 수 있다). */
  const runSeq = useRef(0)

  // ── 결과 패널 (Step 10) ──
  /**
   * 결과 패널 열림 여부. `X`(또는 뒤로가기)로 닫아도 **다시 실행하면 열린다**.
   * 닫으면 범례(`.sim-legend`)도 함께 걷히고 **지도 셀은 남는다** — 오버레이만 치우는 것이 `닫기` 다.
   */
  const [panelOpen, setPanelOpen] = useState(true)
  /**
   * 🔴 **선택 상태의 주인은 이 페이지 하나**다. 목록과 지도가 각자 상태를 들면 서로 다른 셀을 가리킨다.
   *
   * 출처(목록/지도)와 무관하게 선택 이벤트마다 `seq` 를 올려 **새 객체**로 만든다 — 같은 셀을 다시
   * 골랐을 때도 목록이 그 행을 다시 화면 안으로 끌어오게 하기 위해서다(`LifezoneSelection` 주석).
   * 하이라이트만 보는 곳은 `gridCd` 만 쓰면 된다.
   */
  const [selection, setSelection] = useState<CellSelection>(null)
  /**
   * 목록 → 지도 요청. 목록에서 고른 셀로만 지도를 움직인다.
   * 셀 클릭은 이 값을 건드리지 않는다 — 보고 있는 셀을 매번 중앙으로 끌어오면 지도가 튄다.
   */
  const [mapFocus, setMapFocus] = useState<CellSelection>(null)

  /**
   * 목록 행 클릭 — 선택 + 지도 이동 요청.
   * 지도 쪽 `seq` 도 따로 올린다. 같은 행을 연달아 눌러도 `setView`(확대, TSK-4241)·`openPopup` 이 다시 걸려야 하기
   * 때문이다(지도에서 다른 셀을 본 뒤 같은 행으로 돌아오는 경우가 그렇다).
   */
  const selectFromList = useCallback((key: string) => {
    setSelection((prev) => ({ key, seq: (prev?.seq ?? 0) + 1 }))
    setMapFocus((prev) => ({ key, seq: (prev?.seq ?? 0) + 1 }))
  }, [])

  /**
   * 지도 셀 클릭 — 선택만 바꾼다(지도는 이미 그 셀을 보고 있어 다시 움직이면 화면이 튄다).
   *
   * 🔴 **목록에 없는 셀이면 선택을 해제한다.** 셀은 수천 칸인데 목록은 15행이라 **이 경로가
   * 기본값에 가깝다** — 대응 행이 없는 키를 선택으로 세우면 목록에 아무것도 활성화되지 않는데
   * 상태만 남아, 다음에 그 행이 생겼을 때 엉뚱하게 되살아난다.
   * 지도 팝업은 Leaflet 이 클릭에서 알아서 열므로 여기서 할 일이 없다.
   *
   * ⚠️ 수급은 목록이 **사분면 필터에 따라 달라지므로** 여기서 "목록에 있는지" 를 판정할 수 없다
   * (필터 상태는 패널이 든다). 그래서 수급은 셀 전량을 후보로 보고 선택을 세운다 — 필터 밖 셀을
   * 골랐다면 목록에 활성 행이 없을 뿐이고, 다음 조회에서 선택은 어차피 초기화된다.
   */
  const selectFromCell = useCallback(
    (key: string) => {
      // 순위표가 있는 유형(민원건수·혼잡도)만 "목록에 있는지" 를 판정한다(수급은 아래 주석).
      if (analysis && analysis.kind !== 'supply') {
        const inRanking = analysis.data.ranking.some((r) => r.gridCd === key)
        if (!inRanking) {
          setSelection(null)
          return
        }
      }
      setSelection((prev) => ({ key, seq: (prev?.seq ?? 0) + 1 }))
    },
    [analysis],
  )

  /*
   * 분석유형 목록. 세그먼트를 그리기 위한 **경량 조회**라 D1(진입 직후 자동 분석 금지)에 걸리지 않는다
   * — 분석 API(`/api/analysis/lifezone`)와는 다른 엔드포인트이며 격자 집계를 돌리지 않는다.
   */
  useEffect(() => {
    let cancelled = false
    fetchLifezoneTargets()
      .then((list) => {
        if (cancelled) return
        setTargets(list)
        setTargetsFailed(false)
        // 기본 선택은 서버가 가용하다고 알려 준 첫 유형이다(지금은 `민원건수`).
        setAnlsTrgtCd((prev) => prev || (list.find((t) => t.available)?.anlsTrgtCd ?? ''))
      })
      .catch(() => {
        if (cancelled) return
        setTargets([])
        setTargetsFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [reloadTargetsKey])

  // 지역 select 원본도 마운트 때 한 번만 받는다.
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
  // 규칙은 `lib/dateRange` 하나만 쓴다 — 집중구역과 같은 입력이 같은 문구로 막혀야 한다.
  const rangeError: RangeError | null = validateRange(range.from, range.to)

  /**
   * 결과가 없을 때 민원건수 지도에 넘길 격자 크기 — **초기 줌을 정하는 용도뿐**이다.
   * 민원건수 줌 표에 없는 값이면 기본값으로 떨어뜨린다(🔁 TSK-4206 이후 수급도 같은 목록이라
   * 실제로 걸릴 일은 없고, 해상도가 다른 유형이 생길 때를 위한 방어다).
   */
  const fallbackGridSz: LifezoneGridSz = ([1000, 500, 250, 100] as number[]).includes(gridSz)
    ? (gridSz as LifezoneGridSz)
    : DEFAULT_GRID_SZ

  /**
   * 기간을 선택 유형의 원천 보유 구간으로 맞춘다 (TSK-4089).
   *
   * 🔴 **세그먼트를 `직접설정` 으로 함께 옮긴다.** `최근 N개월` 은 오늘 기준으로 매번 다시 펴지는
   * 값이라, 세그먼트를 그대로 둔 채 날짜만 바꾸면 다음 렌더에서 되돌아간다(`range` 가 `period`
   * 에서 파생된다).
   */
  const useDataRange = (from: string, to: string) => {
    setCustomFrom(from)
    setCustomTo(to)
    setPeriod('custom')
  }

  /**
   * 혼잡도 지도 추가 표현(TSK-4090). 결과가 혼잡도일 때만 지도에 넘긴다.
   * 참조가 결과마다 하나여야 지도 셀 레이어가 렌더마다 다시 만들어지지 않는다.
   */
  const isCngstAnalysis = analysis?.kind === 'cngst'
  const cngstCellExtra = useMemo(() => (isCngstAnalysis ? cngstLines : undefined), [isCngstAnalysis])

  /** 시군구를 바꾸면 읍면동을 `전체` 로 되돌린다 — 안 하면 제주시 읍면동이 서귀포시에 남는다. */
  const changeSgg = (next: string) => {
    setSggNm(next)
    setEmdCd('')
  }

  /**
   * 지도 이동용 시군구 코드(TSK-4227 R17 · 실행 때 `appliedRegion` 으로 확정 — D18). 🔴 `RegionItem` 에 `sggCd` 가 없어 그 시군구 **소속 읍면동코드
   * 앞 5자리**로 만든다 — 백엔드 `AdminDistrictMapper` 의 `sggCd` 필터도 `LEFT(emd_cd, 5)` 라 같은 값이다.
   * 읍면동이 없는 시군구면 빈 값(이동하지 않고 기본 시야)이다.
   */
  const sggCd = useMemo(
    () => regions.find((r) => r.sggNm === sggNm)?.emds[0]?.emdCd.slice(0, 5) ?? '',
    [regions, sggNm],
  )

  /** 선택된 분석 유형. 격자 목록·호출 경로가 여기서 갈린다 (TSK-4089). */
  const selectedTarget = useMemo(
    () => targets.find((t) => t.anlsTrgtCd === anlsTrgtCd),
    [targets, anlsTrgtCd],
  )

  /** 현재 탭의 계수 스코프. 민원 건수 탭이면 `null`(D12). */
  const coeffScope = coeffScopeOf(selectedTarget?.anlsPathSfx)

  /**
   * 유형 전환.
   *
   * 🔁 TSK-4227 — 분류방법은 서버 유형 기본값이다(프론트 미전송 — 민원건수 `EQUAL_INTERVAL` ·
   * 혼잡도 `CNGST_ABSOLUTE`). 종전처럼 `dfltClsfMthd` 를 state 로 들고 있다가 실어 보내지 않는다.
   *
   * 🔁 TSK-4227 R18 — 유형을 바꾸면 **분석지역을 `전체` 로 되돌린다**(시군구·읍면동 모두). 같은 유형
   * 재선택은 무시한다. 🔁 D18 — 지도는 이 초기화로 움직이지 **않는다** — 다음 "실행" 때 기본 시야로 간다.
   *
   * 🔁 TSK-4227 R27 · D22 — 유형을 바꾸면 **분석 결과 요약 패널도 닫는다**. 결과 패널 X(`onClose`)와
   * **같은 상태 변경**(`setPanelOpen(false)`)이다 — 패널·범례만 닫고 지도 셀은 다음 "실행" 까지 둔다.
   * X 와 다른 "닫기" 를 따로 만들면 상태가 갈린다. 다시 여는 것은 실행 성공 시(`setPanelOpen(true)`)뿐이다.
   */
  const changeTarget = (next: LifezoneTargetCd) => {
    // 같은 유형을 다시 누른 것이면 아무것도 하지 않는다 — 고른 지역이 날아가지 않게(TSK-4227 R18).
    if (next === anlsTrgtCd) return
    setAnlsTrgtCd(next)
    setSggNm('')
    setEmdCd('')
    // 결과 패널 X 와 같은 닫기(R27 · D22).
    setPanelOpen(false)
  }

  /**
   * 유형을 바꾸면 격자 크기를 그 유형이 지원하는 값으로 되돌린다 (TSK-4089).
   *
   * 🔴 없으면 목록이 다른 유형으로 넘어가는 순간 **서버가 400** 이다. 현재 값이 목록 안이면
   * 그대로 둔다 — 🔁 TSK-4206 부터 민원건수·수급 목록이 같아 유형만 바꿔 비교할 때 선택이 유지된다.
   */
  useEffect(() => {
    if (!selectedTarget) return
    if (selectedTarget.gridSzs.includes(gridSz)) return
    setGridSz(selectedTarget.dfltGridSz)
  }, [selectedTarget, gridSz])

  /**
   * 실행 — **이 화면에서 분석 API 를 호출하는 유일한 경로**다(D1).
   *
   * 🔴 호출 경로를 **서버가 내린 `anlsPathSfx`** 로 고른다 — 유형 코드(`SPLY_DMND`)를 화면에
   * 박지 않는다. 유형이 늘어도 여기서 분기를 추가하는 대신 서버가 경로를 알려 주면 된다.
   * `전체`(`''`)인 지역 필터는 각 조회 함수가 쿼리에서 빼 준다(빈 값을 그대로 보내면 400).
   *
   * 🆕 TSK-4325 — `req` 는 이번 실행의 계수(기본 = 현재 탭 적용값, 민원 건수 탭은 `null`). 현재 탭 값만 싣는다(D13).
   * 성공했을 때만 마지막 실행값으로 남긴다(`markRan`·`ranVrblVls` — 실패·늦은 응답은 남기지 않는다, D7).
   * 🔴 패널에는 인자 없이 부르는 화살표 함수로 넘긴다 — 클릭 이벤트가 `req` 로 들어오지 않게.
   */
  const runAnalysis = async (
    req: CoeffRequest | null = coeffScope ? coeff.appliedRequest(coeffScope) : null,
  ) => {
    if (rangeError) return
    const seq = ++runSeq.current
    setStatus('loading')
    setError(null)
    // 직전 결과를 비운다 — 남겨 두면 조회 중에 이전 조건의 결과가 그대로 보인다.
    setAnalysis(null)
    /*
     * 선택도 함께 푼다. 격자 키는 조건이 바뀌어도 같은 값이 다시 나올 수 있어(격자는 고정 격자망이다)
     * 남겨 두면 **새 결과의 엉뚱한 행이 선택된 채로** 열린다 — 이번 조회에서 고른 적이 없는 셀이다.
     */
    setSelection(null)
    setMapFocus(null)
    /*
     * 지도 이동(TSK-4227 R24 · D18) — 이 실행에 쓰는 지역으로 확정하고 회차를 올린다. 결과 도착이 아니라
     * **실행 시작** 시점이다(조회가 실패해도 "그 지역을 보려 했다" 는 동작은 같다). 결과가 와도 다시 옮기지 않는다.
     */
    setAppliedRegion({ sggCd, emdCd })
    setRegionSeq((n) => n + 1)

    const bgngYmd = toCompactYmd(range.from)
    const endYmd = toCompactYmd(range.to)

    try {
      if (selectedTarget?.anlsPathSfx === SUPPLY_PATH_SFX) {
        const res = await fetchLifezoneSupply({
          bgngYmd,
          endYmd,
          gridSz: gridSz as LifezoneSupplyGridSz,
          sggNm,
          emdCd,
          vrblVls: req?.scope === 'LIFEZONE_SPLY' ? req.vrblVls : undefined,
        })
        if (seq !== runSeq.current) return
        setAnalysis({ kind: 'supply', data: res })
      } else if (selectedTarget?.anlsPathSfx === CNGST_PATH_SFX) {
        const res = await fetchLifezoneCongestion({
          bgngYmd,
          endYmd,
          gridSz: gridSz as LifezoneGridSz,
          sggNm,
          emdCd,
          vrblVls: req?.scope === 'LIFEZONE_CNGST' ? req.vrblVls : undefined,
        })
        if (seq !== runSeq.current) return
        setAnalysis({ kind: 'cngst', data: res })
      } else {
        const res = await fetchLifezoneAnalysis({
          bgngYmd,
          endYmd,
          anlsTrgtCd: anlsTrgtCd || undefined,
          gridSz: gridSz as LifezoneGridSz,
          sggNm,
          emdCd,
        })
        if (seq !== runSeq.current) return
        setAnalysis({ kind: 'cvlcpt', data: res })
      }
      // 결과와 같은 지점에서 굳힌다 — 내보내기가 이 값을 쓴다(D7). 민원 건수는 계수가 없다(`null`).
      if (req) coeff.markRan(req)
      setRanVrblVls(req?.vrblVls ?? null)
      setRanPeriodLabel(formatPeriodLabel(period, range))
      setStatus('done')
      // 닫아 둔 패널을 다시 연다 — 실행했는데 결과가 어디에도 안 보이는 상태를 만들지 않는다.
      setPanelOpen(true)
    } catch {
      if (seq !== runSeq.current) return
      // 입력값은 그대로 두고 문구만 띄운다 — 사용자가 고른 조건을 날리지 않는다.
      // 🔴 결과는 비운 채 `idle` 로 되돌린다(실패한 조건의 직전 결과를 지도에 남기지 않는다).
      setError(ERROR_TEXT)
      setStatus('idle')
    }
  }

  /**
   * 계수 `적용`(D6) — **현재 탭의 결과**가 있으면 확정된 요청으로 재분석한다. 결과가 없거나 다른 탭의 결과면
   * 값만 남기고 다음 `실행` 이 쓴다(유형을 바꾼 뒤 적용 한 번으로 다른 분석이 돌지 않게).
   * 🔴 `setState` 직후 `appliedRequest` 는 이전 값이라 훅이 돌려준 요청을 그대로 넘긴다.
   */
  const applyCoeff = (req: CoeffRequest) => {
    const kind = req.scope === 'LIFEZONE_SPLY' ? 'supply' : 'cngst'
    if (status === 'done' && analysis?.kind === kind) void runAnalysis(req)
  }
  /** 배너 `초기화` — 현재 탭 적용값을 기본값으로 되돌리고, 그 탭 결과가 있으면 재분석한다. */
  const resetCoeff = () => {
    if (coeffScope) applyCoeff(coeff.reset(coeffScope))
  }

  /*
   * ⚠️ Step 8 이 설정 패널에 임시로 두었던 `resultNote`(0건 안내 / `N개 격자 조회됨`)는 여기서
   * **걷어냈다** — 결과를 설명하는 자리는 이제 결과 패널(`LifezoneResultPanel`)이다.
   * 0건 안내와 원천 보유 구간도 그쪽이 갖는다(두 곳에 두면 문구가 갈린다).
   */

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="분석·시뮬레이션" page="생활권 시뮬레이션" />
          <h1 className="page-title">생활권 시뮬레이션</h1>
          <p className="page-sub">격자(Grid) 기반 민원·수급·혼잡도 분석</p>
        </div>
        {/* `AI 대화 시작하기` 버튼 + 알림 벨. 시안의 topbar 우측 묶음을 그대로 쓴다. */}
        <TopbarActions />
      </header>

      <div className="content content--analysis">
        <section className="sim-stage">
          {/*
           * 격자 히트맵(Step 9). 배경지도는 스테이지의 **직계 자식**이어야 한다
           * (`analysis.css` 의 `.sim-stage > .leaflet-container` 가 그 형태를 전제로 크기를 잡는다).
           * 오버레이 패널은 `position:absolute; z-index:1000` 이라 흐름상 뒤에 있어도 지도 위에 얹힌다.
           *
           * 🔴 셀·범례는 **응답값만** 넘긴다 — 등급 판정도 범례 문구도 서버가 내린 것을 그대로 쓴다.
           * 격자 크기만 예외로 결과가 없을 때 설정 패널의 선택값을 쓴다(초기 줌을 정해야 하므로).
           * 결과가 오면 **적용된** `gridSz` 를 따라가 셀과 줌이 어긋나지 않는다.
           */}
          {analysis?.kind === 'supply' ? (
            <LifezoneSupplyMap
              cells={analysis.data.cells}
              gradeCriteria={analysis.data.gradeCriteria}
              gridSz={analysis.data.gridSz}
              gridSzNm={analysis.data.gridSzNm}
              onCellSelect={selectFromCell}
              focus={mapFocus ? { gridId: mapFocus.key, seq: mapFocus.seq } : null}
              showLegend={panelOpen}
              /* 마지막 실행에 적용된 지역 + 실행 회차 — 선택만으로는 움직이지 않는다(TSK-4227 R24 · D18) */
              sggCd={appliedRegion.sggCd}
              emdCd={appliedRegion.emdCd}
              regionSeq={regionSeq}
            />
          ) : (
            <LifezoneMap
              cells={analysis?.data.cells}
              ranking={analysis?.data.ranking}
              gradeCriteria={analysis?.data.gradeCriteria}
              unit={analysis?.data.unit}
              /*
               * 결과가 없을 때만 설정 패널의 선택값을 쓴다(초기 줌을 정해야 하므로).
               * 결과가 오면 **적용된** `gridSz` 를 따라가 셀과 줌이 어긋나지 않는다.
               * ⚠️ 이 표에 없는 격자를 골라 둔 채 결과가 없으면 민원건수 기본값으로 떨어뜨린다
               *    — 줌만 정하는 값이라 화면에 영향이 없다.
               */
              gridSz={analysis?.data.gridSz ?? fallbackGridSz}
              gridSzNm={analysis?.data.gridSzNm}
              onCellSelect={selectFromCell}
              focus={mapFocus ? { gridCd: mapFocus.key, seq: mapFocus.seq } : null}
              showLegend={panelOpen}
              sggCd={appliedRegion.sggCd}
              emdCd={appliedRegion.emdCd}
              regionSeq={regionSeq}
              /* 혼잡도만 — 팝업 `혼잡 지수 N점` (TSK-4227 R19) */
              isCngst={analysis?.kind === 'cngst'}
              /* 혼잡도만 — 값 ÷100 점수 표기 · 팝업 설명 줄 (TSK-4090) */
              formatVl={analysis?.kind === 'cngst' ? formatCngstVl : undefined}
              cellExtra={cngstCellExtra}
              /* 민원건수만 — 팝업 `N건/M개월`. 🔴 응답의 기간이다(설정 패널 값이 아니다 · TSK-4227 R6) */
              months={
                analysis?.kind === 'cvlcpt'
                  ? monthsOfRange(analysis.data.bgngYmd, analysis.data.endYmd)
                  : undefined
              }
            />
          )}

          <LifezoneSettingPanel
            targets={targets}
            targetsFailed={targetsFailed}
            onRetryTargets={() => setReloadTargetsKey((k) => k + 1)}
            anlsTrgtCd={anlsTrgtCd}
            onTargetChange={changeTarget}
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
            gridSz={gridSz}
            onGridSzChange={setGridSz}
            rangeFrom={range.from}
            rangeTo={range.to}
            onUseDataRange={useDataRange}
            rangeError={rangeError}
            running={status === 'loading'}
            onRun={() => {
              void runAnalysis()
            }}
            error={error}
            coeff={coeff}
            coeffScope={coeffScope}
            onApplyCoeff={applyCoeff}
            onResetCoeff={resetCoeff}
          />

          {/*
           * 결과 패널은 **분석을 한 번 돌린 뒤에만** 나타난다(D1 — 진입 직후에는 결과가 없다).
           * 0건이어도 연다 — 그 안내(원천 보유 구간)를 담을 곳이 이 패널이기 때문이다.
           *
           * 🔴 선택 상태를 이 페이지가 들고 지도·목록 **양쪽에 같은 값을 내려 준다.**
           * 목록 → 지도는 `mapFocus`(이동 + 팝업), 지도 → 목록은 `onCellSelect`(선택 + 스크롤)다.
           */}
          {status === 'done' &&
            analysis &&
            panelOpen &&
            (analysis.kind === 'supply' ? (
              <LifezoneSupplyResultPanel
                result={analysis.data}
                periodLabel={ranPeriodLabel}
                selection={selection ? { gridId: selection.key, seq: selection.seq } : null}
                onSelectCell={selectFromList}
                /* 닫으면 패널과 범례만 걷힌다 — 지도 셀은 그대로 남는다. */
                onClose={() => setPanelOpen(false)}
                /* 🆕 TSK-4325 D7 — 이 결과를 만든 실행의 계수. */
                vrblVls={ranVrblVls}
              />
            ) : (
              <LifezoneResultPanel
                result={analysis.data}
                periodLabel={ranPeriodLabel}
                selection={selection ? { gridCd: selection.key, seq: selection.seq } : null}
                onSelectRank={selectFromList}
                onClose={() => setPanelOpen(false)}
                formatVl={analysis.kind === 'cngst' ? formatCngstVl : undefined}
                /* 🆕 TSK-4325 D7 — 이 결과를 만든 실행의 혼잡도 계수(민원 건수 결과면 `null`). */
                vrblVls={ranVrblVls}
              />
            ))}
        </section>
      </div>
    </>
  )
}
