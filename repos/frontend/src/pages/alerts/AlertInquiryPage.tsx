import { useEffect, useRef, useState, type SelectHTMLAttributes } from 'react'
import Breadcrumb from '../../components/common/Breadcrumb'
import DateRangePopover from '../../components/common/DateRangePopover'
import Icon from '../../components/Icon'
import Pager from '../../components/common/Pager'
import RiskBadge, { RISK_LEVELS } from '../../components/common/RiskBadge'
import TopbarActions from '../../components/common/TopbarActions'
import {
  fetchRiskAlerts,
  fetchRiskCriteria,
  type RiskAlertItem,
  type RiskAlertPage,
  type RiskAlertSearchParams,
  type RiskCriteriaItem,
} from '../../api/alerts'
import { fetchRegions, type RegionItem } from '../../api/regions'
import { toYmd, validateRange } from '../../lib/dateRange'
import SrLabel from '../../components/common/SrLabel'

/*
 * 위험단계 알림 조회 `/alerts/inquiry`.
 *
 * 레이아웃·DOM 은 시안을 그대로 옮겼고,
 * 데이터는 목데이터 대신 실 API(`src/api/alerts.ts` · `src/api/regions.ts`)에서 받는다.
 *
 * 시안과 같은 부분
 *   - 필터 4종(단계 세그먼트 · 시/도 · 읍면동 · 기간 세그먼트 + 날짜 팝오버)
 *   - 날짜 팝오버 `적용` 시 기간 세그먼트 선택이 **해제**된다(둘 중 하나만 활성)
 *   - 표 컬럼은 `단계 | 지역 | 내용 | 일시 | 민원 수` 그대로, 페이지당 10행 고정
 *
 * 시안과 의도적으로 다른 부분
 *   - 지역 select 를 **`GET /api/regions`** 로 채운다(시안의 하드코딩 목록을 옮기지 않는다)
 *   - 요약 3카드는 **서버 `summary`** 를 그대로 찍는다. 페이징된 `content` 로 다시 집계하지 않는다
 *     — 한 페이지치로 합계를 내면 총계가 틀린다
 *   - 표의 `지역` 칸은 응답 `regionNm`(항상 읍면동명) 그대로다 — 시안의 `region === '전체' ? city : region`
 *     분기를 옮기지 않는다(판정이 언제나 읍면동 단위라 `지역 = 전체` 행 자체가 없다)
 *   - ⚠️ **읽음 상태 배지는 화면에 두지 않는다**(2026-08-21 사용자 지시). 응답의 `prslYn`·`idntyYn` 은 그대로 내려오지만
 *     단계 칸에는 위험단계 배지만 찍는다. 갱신 API 도 없다(범위 밖 #9) —
 *     눌러도 바뀌지 않으므로 버튼이 아니라 배지로 렌더한다
 *   - 조회 중 / 조회 실패(+ `다시 시도`) 상태 표시 (시안은 정적 목데이터라 실패 경로가 없다)
 *   - **첫 진입 기간이 `어제`** 다(시안은 `최근 3개월`). 알림은 배치가 전일 민원을 판정해 만드는 것이라
 *     운영자가 먼저 볼 것이 "어제 뜬 알림" 이고, 상단 알림 벨도 전일 하루치를 보여 준다.
 *     자세한 근거는 `DEFAULT_PERIOD` 주석 참조
 *
 * 목록 순서는 서버 정렬(`ocrn_dt DESC, noti_id DESC`)을 그대로 따르고 프론트에서 재정렬하지 않는다.
 */

/** 페이지당 행 수. 서버 `size` 와 `Pager.perPage` 가 같은 값을 써야 총 페이지 수가 맞는다. */
const PER_PAGE = 10

/**
 * 단계 세그먼트. **첫 항목의 빈 코드(`''`)가 `전체`** 다.
 *
 * 🔴 시안은 이 값이 `all` 이었다 — 그대로 서버로 넘기면 화이트리스트에 걸려 400 이다.
 * 라벨은 `RiskBadge` 의 `RISK_LEVELS` 에서 가져온다(매핑을 화면에 복제하지 않는다).
 */
const LEVEL_TABS: { code: string; label: string }[] = [
  { code: '', label: '전체' },
  ...RISK_LEVELS.map((l) => ({ code: l.code as string, label: l.label })),
]

/** 기간 세그먼트. `key` 는 화면 전용 값이라 서버로 나가지 않는다(아래 `periodRange` 가 날짜로 바꾼다). */
const PERIODS = [
  { key: 'yesterday', label: '어제' },
  { key: 'week', label: '최근 1주일' },
  { key: '1', label: '최근 1개월' },
  { key: '3', label: '최근 3개월' },
] as const

type PeriodKey = (typeof PERIODS)[number]['key']

/**
 * 첫 진입 기간 — **`어제`** 다.
 *
 * 🔴 시안은 `최근 3개월` 이지만 의도적으로 다르게 간다. 알림은 배치가 매일 02:00 에 **전일** 민원을
 * 판정해 만드는 것이라 운영자가 가장 먼저 볼 것은 "어제 뜬 알림" 이다.
 * 상단 알림 벨도 전일 하루치를 보여 주고 `전체보기` 로 이 화면에 오므로,
 * 기본이 3개월이면 팝오버에서 넘어온 순간 기준이 갈려 같은 알림을 다시 찾아야 한다.
 *
 * ⚠️ 기간을 넓혀 보려면 세그먼트에서 `최근 1주일`·`최근 1개월`·`최근 3개월` 을 고르면 된다.
 */
const DEFAULT_PERIOD: PeriodKey = 'yesterday'

/** 조회 기간 (yyyy-MM-dd). 서버가 `from` 을 00:00:00, `to` 를 **23:59:59 까지** 펼친다. */
interface DateRange {
  from: string
  to: string
}

/** 기간 세그먼트 → 실제 조회 구간. 시안과 달리 기준 시각이 고정 목데이터가 아니라 **오늘**이다. */
function periodRange(period: PeriodKey): DateRange {
  const today = new Date()

  if (period === 'yesterday') {
    const yesterday = new Date(today)
    yesterday.setDate(yesterday.getDate() - 1)
    const ymd = toYmd(yesterday)
    return { from: ymd, to: ymd }
  }

  const start = new Date(today)
  if (period === 'week') start.setDate(start.getDate() - 7)
  else start.setMonth(start.getMonth() - Number(period))

  return { from: toYmd(start), to: toYmd(today) }
}

/** 화면이 들고 있는 필터 상태. `''` 는 전부 `전체`(= 파라미터 미전송)를 뜻한다. */
interface AlertFilters {
  riskLvlCd: string
  sggNm: string
  emdCd: string
  period: PeriodKey | ''
  range: DateRange | null
  page: number
}

/**
 * 필터 상태 → `GET /api/risk-alerts` 쿼리 파라미터.
 *
 * 🔴 **`전체` 를 고른 필터는 키를 아예 넣지 않는다**(`riskLvlCd`·`sggNm`·`emdCd`).
 * 시안 select 의 `전체` 값(`all`)을 그대로 넘기면 서버 화이트리스트에 걸려 **400**,
 * 빈 문자열(`sggNm=`)이면 **0건**이 된다. 그래서 화면 상태는 `전체` 를 `''` 로 두고
 * 여기서 **값이 있을 때만** 키를 얹는다 — 서버도 방어적으로 접지만 프론트가 먼저 끊는다.
 */
function buildParams(filters: AlertFilters): RiskAlertSearchParams {
  const params: RiskAlertSearchParams = { page: filters.page, size: PER_PAGE }

  if (filters.riskLvlCd) params.riskLvlCd = filters.riskLvlCd
  if (filters.sggNm) params.sggNm = filters.sggNm
  if (filters.emdCd) params.emdCd = filters.emdCd

  // 사용자 지정 기간이 있으면 그것이 우선이고, 없으면 세그먼트를 날짜로 편다(둘 다 없으면 전체 기간).
  const range = filters.range ?? (filters.period ? periodRange(filters.period) : null)
  if (range) {
    params.from = range.from
    params.to = range.to
  }

  return params
}

/**
 * `2026-05-28T11:47:00` → `2026.05.28 11:47` (시안 표기).
 * 서버가 내리는 LocalDateTime 문자열을 그대로 자른다 — `Date` 로 파싱하면 타임존에 따라 하루 밀린다.
 *
 * 🔴 **시각을 버리지 않는다.** 시안 목데이터도 `2026-05-28 11:47` 로 분까지 찍고(번들 실측),
 * 무엇보다 `제주시 전체` 판정을 3개 읍면동으로 펼친 묶음이 **같은 `ocrn_dt` 를 공유**한다.
 * 날짜만 남기면 그 3행이 화면에서 구분되지 않는다.
 */
function formatOcrnDt(isoDateTime: string): string {
  return `${isoDateTime.slice(0, 10).replace(/-/g, '.')} ${isoDateTime.slice(11, 16)}`
}

/** `2026-05-28T00:00:00` → `05.28`. 문자열을 그대로 자른다(타임존 영향 없음). */
function mmdd(isoDateTime: string): string {
  return `${isoDateTime.slice(5, 7)}.${isoDateTime.slice(8, 10)}`
}

/** 발생일 하루 전 → `05.27`. 로컬 자정으로 만들어 날짜만 뺀다. */
function prevMmdd(isoDateTime: string): string {
  const [y, m, d] = isoDateTime.slice(0, 10).split('-').map(Number)
  const prev = new Date(y, m - 1, d - 1)
  return `${String(prev.getMonth() + 1).padStart(2, '0')}.${String(prev.getDate()).padStart(2, '0')}`
}

/**
 * 알림 이력 `내용` 칸 둘째 줄 — 시안 `전일 대비 · 05.27→05.28 · 증가 4건 (25→29건)`.
 *
 * 🔴 **전일 건수는 응답에 없어 증가율에서 역산한다.** 판정 배치(`RiskAlertJudgeProcessor`)가
 *    `incrsRt = (당일 − max(전일,1)) × 100 / max(전일,1)` (소수 2자리 반올림)로 저장하므로
 *    `전일 = round(당일 × 100 / (100 + incrsRt))` 로 되돌릴 수 있다(현실 범위 건수에서 오차 0.5 미만).
 *    ⚠️ 전일 0건은 분모 보정 때문에 `1` 로 보인다(하한이 1 이상이면 그런 알림 자체가 나지 않는다).
 *    `ocrnDt` 는 판정일 00:00 이고 `cvlcptCnt` 는 그날 건수라 비교 구간은 `판정일 전날 → 판정일` 이다.
 *
 * 증가율·건수 중 하나라도 없으면 `null`(둘째 줄을 그리지 않는다).
 */
function dailyDeltaText(r: RiskAlertItem): string | null {
  if (r.cvlcptCnt === null || r.incrsRt === null || r.incrsRt <= -100) return null
  const prevCnt = Math.round((r.cvlcptCnt * 100) / (100 + r.incrsRt))
  const diff = r.cvlcptCnt - prevCnt
  return (
    `전일 대비 · ${prevMmdd(r.ocrnDt)}→${mmdd(r.ocrnDt)} · ` +
    `증가 ${diff.toLocaleString()}건 (${prevCnt.toLocaleString()}→${r.cvlcptCnt.toLocaleString()}건)`
  )
}

/** 시안의 `.ds-select` — 네이티브 select 위에 chevron 아이콘을 얹는다. */
function DsSelect({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className={`ds-select${className ? ` ${className}` : ''}`}>
      <SrLabel text={rest['aria-label']}>
        <select {...rest}>{children}</select>
      </SrLabel>
      <span className="ds-select__ic">
        <Icon name="chevron-down" size={18} />
      </span>
    </div>
  )
}

export default function AlertInquiryPage() {
  // ── 필터 (전부 `''` = 전체) ──
  const [riskLvlCd, setRiskLvlCd] = useState('')
  const [sggNm, setSggNm] = useState('')
  const [emdCd, setEmdCd] = useState('')
  const [period, setPeriod] = useState<PeriodKey | ''>(DEFAULT_PERIOD)
  const [range, setRange] = useState<DateRange | null>(null)
  /** 서버와 같은 **0-base**. `Pager` 가 표시용 1-base 로만 바꾼다. */
  const [page, setPage] = useState(0)

  // ── 날짜 팝오버 ──
  const [dateOpen, setDateOpen] = useState(false)
  // 첫 진입 기간(어제)을 그대로 채워 둔다 — 빈 칸으로 두면 `적용` 이 눌리지 않는 상태로 열린다.
  // `어제` 는 from·to 가 같은 날이라 팝오버가 그 하루로 열린다.
  const [fromInput, setFromInput] = useState(() => periodRange(DEFAULT_PERIOD).from)
  const [toInput, setToInput] = useState(() => periodRange(DEFAULT_PERIOD).to)
  const datePickRef = useRef<HTMLDivElement>(null)

  // ── 서버 상태 ──
  const [data, setData] = useState<RiskAlertPage | null>(null)
  const [regions, setRegions] = useState<RegionItem[]>([])
  /** 필터 하단 기준정보 줄. 못 받으면 줄 자체를 그리지 않는다(조회 화면을 막지 않는다). */
  const [criteria, setCriteria] = useState<RiskCriteriaItem[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  /** `다시 시도` 로 재조회하기 위한 트리거. */
  const [reloadKey, setReloadKey] = useState(0)

  // 지역 select 원본은 한 번만 받는다(필터가 바뀌어도 목록은 그대로다).
  useEffect(() => {
    let cancelled = false
    fetchRegions()
      .then((list) => {
        if (!cancelled) setRegions(list)
      })
      .catch(() => {
        // 지역 목록이 없으면 select 가 `전체` 만 남는다 — 목록 조회까지 막지는 않는다.
        if (!cancelled) setRegions([])
      })
    return () => {
      cancelled = true
    }
  }, [])

  // 위험단계 기준(기준정보 줄)도 진입 시 한 번만 받는다 — 서버 순서 CRIT → WARN → CAUT.
  useEffect(() => {
    let cancelled = false
    fetchRiskCriteria()
      .then((list) => {
        if (!cancelled) setCriteria(list)
      })
      .catch(() => {
        if (!cancelled) setCriteria([])
      })
    return () => {
      cancelled = true
    }
  }, [])

  // 필터·페이지가 바뀔 때마다 서버에서 해당 구간 + 요약을 한 번에 받아온다.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(false)
    // 이전 조건의 결과를 비운다 — 남겨두면 조회 중에 **직전 조건의 총 건수**로 페이저가 그려진다.
    setData(null)

    fetchRiskAlerts(buildParams({ riskLvlCd, sggNm, emdCd, period, range, page }))
      .then((res) => {
        if (!cancelled) setData(res)
      })
      .catch(() => {
        if (cancelled) return
        setData(null)
        setLoadError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [riskLvlCd, sggNm, emdCd, period, range, page, reloadKey])

  // 기간 팝오버 — 바깥 클릭 / Escape 로 닫는다(대시보드와 같은 방식).
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

  /** 필터가 바뀌면 **첫 페이지(0)로 되돌린다** — 안 하면 2페이지에서 필터를 바꿨을 때 빈 표가 뜬다. */
  const changeLevel = (code: string) => {
    setRiskLvlCd(code)
    setPage(0)
  }

  /** 시/도를 바꾸면 읍면동을 `전체` 로 리셋한다 — 안 하면 제주시 읍면동이 서귀포시에 남는다. */
  const changeSgg = (next: string) => {
    setSggNm(next)
    setEmdCd('')
    setPage(0)
  }

  const changeEmd = (next: string) => {
    setEmdCd(next)
    setPage(0)
  }

  /** 기간 세그먼트 선택 — 사용자 지정 기간은 해제된다(둘 중 하나만 활성). */
  const changePeriod = (key: PeriodKey) => {
    setPeriod(key)
    setRange(null)
    setPage(0)
  }

  /**
   * 날짜 팝오버 `적용` — 세그먼트 선택을 **해제**한다(시안 동작).
   * 팝오버가 이미 막지만, `setRange` 로 들어오는 값은 페이지가 최종 책임진다.
   */
  const applyRange = (next: { from: string; to: string }) => {
    if (validateRange(next.from, next.to)) return
    setRange(next)
    setPeriod('')
    setPage(0)
    setDateOpen(false)
  }

  const emds = regions.find((r) => r.sggNm === sggNm)?.emds ?? []
  const summary = data?.summary ?? null
  const rows = data?.content ?? []
  /**
   * 요약 카드 보조 문구 (시안). `조회 알림 건수` 는 `{기간 라벨} 기준`(사용자 지정이면 `시작 ~ 종료`),
   * `누적 민원 수` 는 실제 조회 구간 `yyyy.mm.dd ~ yyyy.mm.dd 합계` 다. 구간은 `buildParams` 와 같은 규칙으로 편다.
   */
  const periodLabel = range
    ? `${range.from} ~ ${range.to}`
    : (PERIODS.find((p) => p.key === period)?.label ?? '선택 기간')
  const effectiveRange = range ?? (period ? periodRange(period) : null)
  const rangeLabel = effectiveRange
    ? `${effectiveRange.from.replace(/-/g, '.')} ~ ${effectiveRange.to.replace(/-/g, '.')}`
    : '전체 기간'
  const minCvlcptCnt = criteria.length > 0 ? Math.min(...criteria.map((c) => c.minCvlcptCnt)) : 0

  /** 요약 카드 숫자 — 실패는 `—`, 조회 중은 `…`. */
  const statValue = (value: number | undefined) => {
    if (loadError) return '—'
    if (value === undefined) return '…'
    return value.toLocaleString()
  }

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="보고서·알림" page="위험단계 알림 조회" />
          <h1 className="page-title">위험단계 알림 조회</h1>
          <p className="page-sub">민원 급증·위험 단계별 알림 이력 조회</p>
        </div>
        <TopbarActions />
      </header>

      {/* `filterbar--alert` = 알림 조회 전용 줄바꿈 변형자. 필터가 8개라 한 줄을 넘친다. */}
      <div className="filterbar filterbar--alert">
        <span className="filterbar__label">단계</span>
        <div className="segment">
          {LEVEL_TABS.map((t) => (
            <button
              key={t.code || 'all'}
              type="button"
              aria-pressed={riskLvlCd === t.code}
              className={`segment__btn${riskLvlCd === t.code ? ' segment__btn--active' : ''}`}
              onClick={() => changeLevel(t.code)}
            >
              {t.label}
            </button>
          ))}
        </div>

        <span className="filter-sep" />

        <span className="filterbar__label">시/도</span>
        <DsSelect
          className="alert-select"
          aria-label="시/도"
          value={sggNm}
          onChange={(e) => changeSgg(e.target.value)}
        >
          <option value="">제주도 전체</option>
          {regions.map((r) => (
            <option key={r.sggNm} value={r.sggNm}>
              {r.sggNm}
            </option>
          ))}
        </DsSelect>

        <DsSelect
          className="alert-select"
          aria-label="읍면동"
          value={emdCd}
          disabled={!sggNm}
          onChange={(e) => changeEmd(e.target.value)}
        >
          <option value="">읍면동 전체</option>
          {emds.map((emd) => (
            <option key={emd.emdCd} value={emd.emdCd}>
              {emd.emdNm}
            </option>
          ))}
        </DsSelect>

        <span className="filter-sep" />

        <span className="filterbar__label">기간</span>
        <div className="segment">
          {PERIODS.map((p) => (
            <button
              key={p.key}
              type="button"
              aria-pressed={period === p.key}
              className={`segment__btn${period === p.key ? ' segment__btn--active' : ''}`}
              onClick={() => changePeriod(p.key)}
            >
              {p.label}
            </button>
          ))}
        </div>

        <div className="date-pick" ref={datePickRef}>
          {/* 시안 — 기간 세그먼트와 같은 부품(.segment__btn). 사용자 지정 기간이면 활성. */}
          <button
            className={`segment__btn${range || dateOpen ? ' segment__btn--active' : ''}`}
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
              from={fromInput}
              to={toInput}
              onFromChange={setFromInput}
              onToChange={setToInput}
              onApply={applyRange}
              onCancel={() => setDateOpen(false)}
            />
          )}
        </div>

        <span className="filterbar__right">
          조회 결과 <strong>{loadError ? '—' : data ? `${data.totalElements}건` : '…'}</strong>
        </span>
      </div>

      {/* 시안 `.alert-criteria` — 현재 위험단계 기준. 증가율은 단계별, 최소 건수는 가장 낮은 단계 값을 보인다
          (알림 관리 화면 기준 카드 하단 문구와 같은 규칙). */}
      {criteria.length > 0 && (
        <div className="alert-criteria">
          {criteria.map((c, i) => (
            <span key={c.riskLvlCd} className="alert-criteria__item">
              <RiskBadge code={c.riskLvlCd} />
              {i === 0 ? ' 증가율 ' : ' '}
              {c.incrsRt}% 이상
            </span>
          ))}
          {/* 하한 0 = 꺼짐 — 그때는 `민원 0건 이상` 대신 문구를 뺀다. */}
          {minCvlcptCnt > 0 && (
            <span className="alert-criteria__note">
              · 민원 {minCvlcptCnt}건 이상일 때 발생 (최소 건수 미만이면 미발생)
            </span>
          )}
        </div>
      )}

      <div className="content content--alert">
        {/* 요약 3카드 — 값은 전부 서버 `summary`(필터 전체 기준)다. 프론트 재집계 금지. */}
        <section className="stat-row">
          <div className="card stat">
            <div className="stat__icon stat__icon--blue">
              <Icon name="bell" size={28} />
            </div>
            <div>
              <div className="stat__label">조회 알림 건수</div>
              <div className="stat__value">
                {statValue(summary?.totalCnt)}
                <span className="stat__unit">건</span>
              </div>
              <div className="stat__delta">{periodLabel} 기준</div>
            </div>
          </div>

          <div className="card stat">
            <div className="stat__icon stat__icon--red">
              <Icon name="warning" size={28} />
            </div>
            <div>
              <div className="stat__label">심각 단계</div>
              <div className="stat__value">
                {statValue(summary?.critCnt)}
                <span className="stat__unit">건</span>
              </div>
              <div className="stat__delta">
                경고 {statValue(summary?.warnCnt)} · 주의 {statValue(summary?.cautCnt)}
              </div>
            </div>
          </div>

          <div className="card stat">
            <div className="stat__icon stat__icon--orange">
              <Icon name="chart" size={28} />
            </div>
            <div>
              <div className="stat__label">누적 민원 수</div>
              <div className="stat__value">
                {statValue(summary?.cvlcptSumCnt)}
                <span className="stat__unit">건</span>
              </div>
              <div className="stat__delta">{rangeLabel} 합계</div>
            </div>
          </div>
        </section>

        <div className="card alert-card">
          <div className="alert-card__head">
            <h2 className="card-head__title">알림 이력</h2>
            <span className="alert-card__hint">상단 필터 조건에 따라 자동 갱신</span>
          </div>

          <div className="rt-wrap">
            <table className="rt">
              <caption className="sr-only">위험단계 알림 조회 결과</caption>
              <thead>
                <tr>
                  <th scope="col" className="col-center">
                    단계
                  </th>
                  <th scope="col" className="col-center">
                    지역
                  </th>
                  <th scope="col">내용</th>
                  <th scope="col" className="col-center">
                    일시
                  </th>
                  <th scope="col" className="col-center">
                    민원 수
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const delta = dailyDeltaText(r)
                  return (
                    <tr key={r.notiId}>
                      <td className="col-center">
                        <RiskBadge code={r.riskLvlCd} />
                      </td>
                      {/* 이력은 항상 읍면동 단위라 서버 `regionNm` 을 그대로 찍는다. */}
                      <td className="col-center">{r.regionNm}</td>
                      <td>
                        {/* 판정 시점에 저장된 문구 그대로. 프론트가 재조립하지 않는다. */}
                        <span className="rt-name">{r.notiCtt}</span>
                        {delta && <span className="rt-name__sub">{delta}</span>}
                      </td>
                      <td className="col-center rt-date">{formatOcrnDt(r.ocrnDt)}</td>
                      <td className="col-center">
                        {r.cvlcptCnt === null ? '-' : `${r.cvlcptCnt.toLocaleString()}건`}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>

            {loading && <div className="alert-empty">불러오는 중...</div>}

            {!loading && loadError && (
              <div className="alert-empty">
                알림 이력을 불러오지 못했습니다.{' '}
                <button
                  type="button"
                  className="btn-edit"
                  onClick={() => setReloadKey((k) => k + 1)}
                >
                  다시 시도
                </button>
              </div>
            )}

            {!loading && !loadError && rows.length === 0 && (
              <div className="alert-empty">조건에 해당하는 알림이 없습니다.</div>
            )}

            {data && (
              <Pager total={data.totalElements} page={page} setPage={setPage} perPage={PER_PAGE} />
            )}
          </div>
        </div>
      </div>
    </>
  )
}
