import { useEffect, useMemo, useRef, useState, type SelectHTMLAttributes } from 'react'
import Icon from '../components/Icon'
import TopbarActions from '../components/common/TopbarActions'
import DateRangePopover from '../components/common/DateRangePopover'
import TrendChart from '../components/dashboard/TrendChart'
import HotspotRanking from '../components/dashboard/HotspotRanking'
import RegionRanking from '../components/dashboard/RegionRanking'
import AnalysisPanel from '../components/dashboard/AnalysisPanel'
import ReportPanel from '../components/dashboard/ReportPanel'
import AiAssistantPanel from '../components/dashboard/AiAssistantPanel'
import DataFreshness from '../components/dashboard/DataFreshness'
import {
  fetchKpi,
  fetchRegions,
  fetchTrend,
  type DashboardQuery,
  type DashboardUnit,
  type KpiResponse,
  type RegionItem,
  type TrendResponse,
} from '../api/dashboard'
import { daysUntilYesterday, toYmd, validateRange } from '../lib/dateRange'
import SrLabel from '../components/common/SrLabel'

/* ──────────────────────────────────────────────────────────
 * 필터 코드값. 그대로 API 파라미터가 된다.
 * ────────────────────────────────────────────────────────── */

/** 조회 단위. 수집 배치가 매일 02:00 에 전날분을 가져오므로 "오늘"은 항상 비어 있다 — 첫 칸을
 *  "어제"로 둔다. */
const UNITS = ['어제', '주간', '월간'] as const
type Unit = (typeof UNITS)[number]

/** 화면 조회단위(한글) → API `unit` enum. */
const UNIT_ENUM: Record<Unit, DashboardUnit> = {
  어제: 'YESTERDAY',
  주간: 'WEEK',
  월간: 'MONTH',
}

/**
 * 행정구역. select value 를 그대로 API `sggCd`(시군구 5자리)로 보낸다. '전체'는 미전송.
 *
 * `sggNm` 을 같은 상수에 함께 담아 집중구역 딥링크(`sggCd → sggNm`) 매핑까지 겸한다 —
 * 별도 매핑 테이블을 두면 지역이 늘 때 한쪽만 고쳐져 어긋난다. `전체` 는 빈 문자열이라 링크에서 빠진다.
 */
const REGIONS = [
  { value: '', label: '제주도 전체', sggNm: '' },
  { value: '50110', label: '제주시', sggNm: '제주시' },
  { value: '50130', label: '서귀포시', sggNm: '서귀포시' },
] as const

/** 조회단위 → [시작일 며칠 전, 종료일 며칠 전]. 백엔드 DashboardUnit 과 같은 값이어야 한다. */
const UNIT_DAYS: Record<Unit, [number, number]> = { 어제: [1, 1], 주간: [6, 0], 월간: [29, 0] }

/** 조회단위 → 읍면동 순위 카드의 증감 비교 기준(시안 `전일대비`·`전주대비`·`전월대비`). 기간 선택은 `전기`. */
const UNIT_COMPARE: Record<Unit, '전일' | '전주' | '전월'> = { 어제: '전일', 주간: '전주', 월간: '전월' }

/* ──────────────────────────────────────────────────────────
 * 위험 단계 카드는 알림 이력 `TB_RISK_STP_NOTI_HSTRY` 집계다.
 *
 * ⚠️ 초기에 이 카드를 `tb_cvlcpt.risk_lvl_cd`(AI 분석 산출물) 기준으로
 * 잘못 짚어 placeholder 로 두었다. 실제 출처는 위험단계 **알림 이력**이며,
 * KPI 응답의 `riskAlert` 로 내려온다. `tb_cvlcpt.risk_lvl_cd` 는 더 이상 쓰지 않는다.
 *
 * 🔴 민원유형(`typeCd`)·위험 단계(`riskLvlCd`) 필터를 화면에서 걷었다. 되살린다면
 * 적용 대상이 갈린다는 것을 기억할 것: `typeCd` 는 민원 지표에만(알림 이력에 유형 컬럼이 없다),
 * `riskLvlCd` 는 알림 지표에만 걸린다. 지금 `riskAlert` 는 항상 전 단계 합계다.
 * ────────────────────────────────────────────────────────── */

/*
 * 🔴 상단 표기는 `최종 업데이트`(민원 발생일, `/dashboard/data-status`)에서 `최신 갱신`
 *    (수집 배치 실행일, `/dashboard/data-freshness`)으로 바뀌었다.
 *    조회·포맷·렌더는 전부 `DataFreshness` 컴포넌트 안에 있다 — 종전 표기 상수·포맷 함수·state·
 *    `/data-status` 조회 effect 를 이 파일에서 모두 걷어냈다(남기면 `noUnusedLocals` 로 빌드가 깨진다).
 *    `api/dashboard.ts` 의 `/data-status` 클라이언트 함수 정의는 남아 있다 —
 *    집중구역 카드 기준일이 계속 쓴다.
 */

/**
 * KPI 숫자 표기 — 천 단위 콤마 (2026-10-01 요구).
 *
 * 🔴 `toLocaleString()` 을 **인자 없이** 부르지 않는다 — 실행 환경 로케일에 좌우돼 브라우저마다
 * 구분자가 갈린다(일부 로케일은 `.` 을 쓴다). `ko-KR` 를 못박아 `4,441` 로 고정한다.
 */
function formatCount(n: number): string {
  return n.toLocaleString('ko-KR')
}

/** 시안의 `.ds-select` — 네이티브 select 위에 chevron 아이콘을 얹는다. */
function DsSelect({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className={`ds-select${className ? ` ${className}` : ''}`}>
      <SrLabel text={rest['aria-label']}>
        <select {...rest}>{children}</select>
      </SrLabel>
      <span className="ds-select__ic">
        <Icon name="chevron-down" size={16} />
      </span>
    </div>
  )
}

export default function DashboardPage() {
  const [unit, setUnit] = useState<Unit>('어제')
  const [dateOpen, setDateOpen] = useState(false)
  const [range, setRange] = useState<{ from: string; to: string } | null>(null)
  // 기본 구간은 어제까지 30일(월간과 같은 구간). 날짜를 소스에 박지 않는다.
  const [{ from: defFrom, to: defTo }] = useState(() => daysUntilYesterday(30))
  const [from, setFrom] = useState(defFrom)
  const [to, setTo] = useState(defTo)

  const [region, setRegion] = useState('')

  // 실 API 상태. 백엔드 장애 시에도 화면이 죽지 않도록 카드별 에러 상태로 관리한다.
  const [kpi, setKpi] = useState<KpiResponse | null>(null)
  const [kpiError, setKpiError] = useState(false)
  const [regions, setRegions] = useState<RegionItem[] | null>(null)
  const [regionsError, setRegionsError] = useState(false)
  const [trend, setTrend] = useState<TrendResponse | null>(null)
  const [trendError, setTrendError] = useState(false)
  const [loading, setLoading] = useState(true)

  const datePickRef = useRef<HTMLDivElement>(null)

  // 조회단위·기간·행정구역이 바뀔 때마다 KPI·읍면동 순위를 재조회한다 (민원유형·위험단계 필터 제거).
  // 기간(range)이 지정되면 startDt/endDt 가 unit 보다 우선한다. 빈 필터는 미전송.
  useEffect(() => {
    const params: DashboardQuery = {}
    if (range) {
      params.startDt = range.from
      params.endDt = range.to
    } else {
      params.unit = UNIT_ENUM[unit]
    }
    if (region) params.sggCd = region

    let cancelled = false
    setLoading(true)
    setKpiError(false)
    setRegionsError(false)
    setTrendError(false)

    const kpiReq = fetchKpi(params)
      .then((d) => {
        if (!cancelled) setKpi(d)
      })
      .catch(() => {
        if (!cancelled) {
          setKpi(null)
          setKpiError(true)
        }
      })

    const regionsReq = fetchRegions(params)
      .then((d) => {
        if (!cancelled) setRegions(d)
      })
      .catch(() => {
        if (!cancelled) {
          setRegions(null)
          setRegionsError(true)
        }
      })

    // 추이는 KPI·순위와 같은 필터를 쓴다. bucket 은 보내지 않는다 —
    // 서버가 정한다(조회단위→HOUR, 기간 직접 지정→DAY).
    const trendReq = fetchTrend(params)
      .then((d) => {
        if (!cancelled) setTrend(d)
      })
      .catch(() => {
        if (!cancelled) {
          setTrend(null)
          setTrendError(true)
        }
      })

    void Promise.allSettled([kpiReq, regionsReq, trendReq]).then(() => {
      if (!cancelled) setLoading(false)
    })

    return () => {
      cancelled = true
    }
  }, [unit, range, region])

  // 기간 팝오버 — 바깥 클릭 / Esc 로 닫는다.
  useEffect(() => {
    if (!dateOpen) return
    const onClick = (e: MouseEvent) => {
      if (datePickRef.current && !datePickRef.current.contains(e.target as Node)) setDateOpen(false)
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

  // 팝오버가 이미 막지만, `setRange` 로 들어오는 값은 페이지가 최종 책임진다.
  const applyRange = (next: { from: string; to: string }) => {
    if (validateRange(next.from, next.to)) return
    setRange(next)
    setDateOpen(false)
  }

  /**
   * 집중구역 화면 딥링크 — 지금 대시보드에 적용된 **기간 + 행정구역**을 그대로 물려준다.
   *
   * 🔴 기간의 출처는 `trend.startDt`/`endDt` 다 — **서버가 실제로 적용한 구간**이라야 화면에 보이는
   * 추이와 집중구역 결과가 같은 구간을 가리킨다. 추이 조회가 실패해 `trend === null` 이면
   * 그때만 `range`(직접 지정) → `UNIT_DAYS[unit]`(조회단위) 순으로 프론트가 계산해 폴백한다.
   *
   * ⚠️ 민원유형·위험단계는 넘기지 않는다 — `/api/analysis/hotspot` 이 받지 않는 파라미터다.
   */
  const gisHref = useMemo(() => {
    const daysAgo = (days: number) => {
      const d = new Date()
      d.setDate(d.getDate() - days)
      return toYmd(d)
    }
    const [startBack, endBack] = UNIT_DAYS[unit]
    const linkFrom = trend?.startDt ?? range?.from ?? daysAgo(startBack)
    const linkTo = trend?.endDt ?? range?.to ?? daysAgo(endBack)

    const qs = new URLSearchParams({ from: linkFrom, to: linkTo })
    // `전체`(빈 sggNm)면 파라미터 자체를 생략한다 — 빈 값을 보내면 받는 쪽이 `전체` 와 구분하지 못한다.
    const sggNm = REGIONS.find((r) => r.value === region)?.sggNm ?? ''
    if (sggNm) qs.set('sggNm', sggNm)
    qs.set('run', '1')
    return `/analysis/hotspot?${qs.toString()}`
  }, [trend, range, unit, region])

  return (
    <>
      <header className="topbar">
        <div>
          <h1 className="page-title">대시보드</h1>
          <DataFreshness />
        </div>
        <TopbarActions />
      </header>

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
          <button
            className={`segment__btn${range ? ' segment__btn--active' : ''}`}
            type="button"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}
            aria-haspopup="true"
            aria-expanded={dateOpen}
            onClick={(e) => {
              e.stopPropagation()
              setDateOpen((v) => !v)
            }}
          >
            <Icon name="calendar" size={18} /> {range ? `${range.from} ~ ${range.to}` : '기간 선택'}
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
        <DsSelect
          aria-label="행정구역"
          value={region}
          onChange={(e) => setRegion(e.target.value)}
        >
          {REGIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </DsSelect>

        {/* 필터바 맨 끝이다. topbar 의 내려받기 버튼은 걷었다(대시보드 보고서를 만들지 않는다). */}
        <span className="filterbar__right">
          현재: <strong>{range ? `${range.from} ~ ${range.to}` : unit}</strong>
        </span>
      </div>

      <div className="content content--dashboard">
        <div className="stat-row">
          <div className="card stat">
            <div className="stat__icon stat__icon--blue">
              <Icon name="document" size={32} />
            </div>
            <div>
              <div className="stat__label">총 민원 건수</div>
              <div className="stat__value">{kpiError ? '—' : kpi ? formatCount(kpi.total) : '…'}</div>
            </div>
          </div>

          {/* 공영주차장 현황 — 🔴 민원·단속이 아니라 `parking.TB_PBLPKLT` 집계다(pkltCnt).
              🔴 **기간 필터를 타지 않는다** — 옆 카드들은 "그 기간에 몇 건" 인 기간 집계지만 이 카드는
              "지금 몇 개 있나" 인 시점 현황이다. 백엔드가 unit·startDt·endDt 를 무시하고 sggCd 만
              적용하므로 **조회단위를 바꿔도 값이 그대로인 것이 정상**이다(버그로 보고 고치지 말 것).
              🔴 소멸분(`extsh_dt IS NOT NULL`)은 제외한다 — 수집 배치가 "이번 수집에 없었다" 고
              판정한 주차장은 현황에서 뺀다.
              🔁 경위: 2026-10-01 이전에는 이 자리가 `불법주정차 단속 건수`(crdnCnt · TB_CRDN_DSCTN)
              였고, 그 전에는 민원 total 을 재사용했다. 사용자 요구로 공영주차장 현황이 됐다.
              crdnCnt 는 API 가 계속 내린다(되돌리기 쉽게) — 화면만 안 쓴다. */}
          <div className="card stat">
            <div className="stat__icon stat__icon--green">
              <Icon name="car" size={32} />
            </div>
            <div>
              <div className="stat__label">공영주차장 현황</div>
              {/* 🔴 단위 `개소` 는 **값이 있을 때만** 붙인다 — `— 개소`·`… 개소` 는 말이 안 된다. */}
              <div className="stat__value">
                {kpiError ? '—' : kpi ? (
                  <>
                    {formatCount(kpi.pkltCnt)}
                    <span className="stat__unit">개소</span>
                  </>
                ) : '…'}
              </div>
            </div>
          </div>

          {/* 위험단계알림 건수 — 알림 이력(TB_RISK_STP_NOTI_HSTRY) 집계.
              🔴 카드 아래 단계별 분해 문구(`⚠ 심각 1, 경고 2`·`· 유형 무관`)를 걷었다.
              시안이 아이콘+라벨+값 구성이라 카드에 증감·부가설명 줄 자체가 없다. */}
          <div className="card stat">
            <div className="stat__icon stat__icon--red">
              <Icon name="warning" size={32} />
            </div>
            <div>
              <div className="stat__label">위험단계알림 건수</div>
              <div className="stat__value">
                {kpiError ? '—' : kpi ? formatCount(kpi.riskAlert.total) : '…'}
              </div>
            </div>
          </div>
        </div>

        {/* 추이(좌) + 집중구역(우) 2단 그리드 — 시안 `.mid-row` */}
        <section className="mid-row">
          <TrendChart
            data={trend}
            error={trendError}
            periodLabel={range ? `${range.from} ~ ${range.to}` : unit}
            // 민원유형 필터를 걷어 고를 값이 없다. 항상 전체 유형이다.
            typeLabel="전체 유형"
          />
          {/* 목록도 행정구역 필터를 탄다(`sggCd`). 기간만 조회단위와 무관한 1개월
              고정이며, 기준일은 최신 민원 발생일이다. 링크는 기간까지 포함한 현재 필터를 탄다. */}
          <HotspotRanking gisHref={gisHref} sggCd={region} />
        </section>

        <RegionRanking
          regions={regions}
          loading={loading}
          error={regionsError}
          compareLabel={range ? '전기' : UNIT_COMPARE[unit]}
        />

        {/* 분석 · 보고서 · AI 3카드 — 시안 `.bottom-row` */}
        <section className="bottom-row">
          <AnalysisPanel />
          <ReportPanel />
          <AiAssistantPanel />
        </section>
      </div>
    </>
  )
}
