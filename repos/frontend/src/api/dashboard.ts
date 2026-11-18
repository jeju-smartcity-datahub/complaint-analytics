import { api } from './client'
import type { HotspotGrade } from './analysis'

/*
 * 대시보드 실 API 클라이언트.
 * 백엔드 계약:
 *   GET /api/dashboard/kpi      → KpiResponse
 *   GET /api/dashboard/regions  → RegionItem[] (count 내림차순, 전체 읍면동)
 * 공통 쿼리파라미터는 전부 optional 이며 비어 있으면 미전송한다.
 */

/** 조회 단위 enum. 화면의 한글 조회단위(어제/주간/월간)를 이 값으로 매핑해 보낸다.
 *  `TODAY` 는 백엔드가 여전히 지원하지만 화면 세그먼트에서는 빠졌다. */
export type DashboardUnit = 'TODAY' | 'YESTERDAY' | 'WEEK' | 'MONTH'

/** KPI·읍면동 순위 공통 조회 파라미터. */
export interface DashboardQuery {
  /** 조회 단위. startDt/endDt 가 있으면 백엔드에서 기간이 우선한다. */
  unit?: DashboardUnit
  /** 기간 시작일 YYYY-MM-DD. */
  startDt?: string
  /** 기간 종료일 YYYY-MM-DD. */
  endDt?: string
  /** 행정구역(시군구) 5자리 코드. 제주시=50110, 서귀포시=50130. */
  sggCd?: string
  /** 민원유형 공통코드 01~05. */
  typeCd?: string
  /** 위험단계 코드 CRIT/WARN/CAUT. **riskAlert 집계에만** 적용된다(민원 지표에는 무영향). */
  riskLvlCd?: string
  /** 추이 x축 버킷. `/dashboard/trend` 에서만 쓰인다. 생략하면 서버가 정한다. */
  bucket?: TrendBucket
}

/** 위험단계별 알림 건수. 건수 0 인 단계는 배열에 없다. */
export interface RiskLevelCount {
  /** 위험단계 코드 CRIT/WARN/CAUT. */
  riskLvlCd: string
  /** 해당 단계 알림 건수. */
  count: number
}

/**
 * 위험단계 알림 집계.
 *
 * ⚠️ 같은 응답의 `total`(민원)과 **모수가 다르다** — 이쪽은 알림 이력
 * `TB_RISK_STP_NOTI_HSTRY` 다. 필터도 갈린다:
 *   · typeCd    → 민원 전용 (알림 이력에 유형 컬럼이 없다)
 *   · riskLvlCd → 알림 전용 (민원 쪽에서는 제거했다)
 *   · 기간·sggCd → 양쪽 공통
 */
export interface RiskAlertKpi {
  /** 알림 총 건수. 알림이 없으면 0(응답이 null 이 되지는 않는다). */
  total: number
  /** 단계별 분해. 라벨·정렬은 프론트가 RISK_LEVELS 순서로 담당한다. */
  breakdown: RiskLevelCount[]
}

/** GET /api/dashboard/kpi 응답. */
export interface KpiResponse {
  /** 총 민원 건수. */
  total: number
  /** 직전 기간 총 민원 건수. */
  prevTotal: number
  /** 전기 대비 증감률(%) — 정수, 음수 가능. */
  deltaPct: number
  /**
   * 현재 기간 단속 건수. 🔴 `total`(민원)과 **모수가 다르다** —
   * `parking.TB_CRDN_DSCTN` 집계다. 종전에 이 카드가 `total` 을 재사용했던 것은 끝났다.
   *
   * 필터 적용도 갈린다:
   *   · 기간·sggCd → 민원과 공통
   *   · typeCd    → **적용되지 않는다** (단속의 `crdn_type_cd` 는 민원유형과 코드체계가 달라
   *                 걸면 "고르는 순간 항상 0건" 이 유일한 효과다)
   *   · riskLvlCd → **적용되지 않는다** (알림 전용 — 단속에 위험단계 컬럼이 없다)
   *
   * 🔴 집계는 `emd_cd IS NOT NULL` 을 **전 지역에서도 고정으로** 건다(2026-10-01 결정) —
   * 그래서 **전체 = 제주시 + 서귀포시** 로 합이 맞는다. 대가로 공간매칭 실패분(좌표 없는 단속)이
   * 전 지역 집계에서도 빠져 "단속 실적 총량" 보다 작게 나온다 — 어느 지역 카드에도 못 올라가는
   * 행이라 그게 맞다.
   *
   * 직전 기간은 집계하지 않는다(카드에 증감 배지가 없다 — 시안 정합).
   */
  crdnCnt: number
  /**
   * 공영주차장 현황 — 선택 지역의 활성 주차장 수 (`parking.TB_PBLPKLT`).
   *
   * 🔴 **기간 필터를 타지 않는다** — `total`·`crdnCnt` 는 기간 집계지만 이 값은 **시점 현황**이다.
   * `unit`·`startDt`·`endDt` 를 백엔드가 **무시**하고 `sggCd`(행정구역)만 적용한다.
   * 조회단위를 바꿔도 값이 그대로인 것이 정상이며, 버그가 아니다.
   *
   * 소멸분(`extsh_dt IS NOT NULL`)은 제외한다.
   *
   * 2026-10-01 이전에는 2번 카드가 `crdnCnt`(불법주정차 단속 건수)였다 — 카드가 바뀌며 이 필드로
   * 갈아탔고, `crdnCnt` 는 API 가 계속 내린다(화면만 안 쓴다).
   */
  pkltCnt: number
  /** 위험단계 알림 집계. 민원과 모수·필터가 다르다. */
  riskAlert: RiskAlertKpi
}

/** 읍면동 순위 항목의 유형별 내역. */
export interface RegionBreakdown {
  /** 민원유형 공통코드 01~05. */
  typeCd: string
  /** 해당 유형 건수. */
  count: number
}

/** GET /api/dashboard/regions 배열 항목. */
export interface RegionItem {
  /** 읍면동 코드. */
  emdCd: string
  /** 읍면동 이름. */
  emdNm: string
  /** 총 민원 건수. */
  count: number
  /** 전기 대비 증감률(%) — 정수, 음수 가능. */
  deltaPct: number
  /**
   * 직전 기간 대비 순위 변동. 양수=상승, 음수=하락, 0=동일.
   * 🔴 `null` 은 **직전 기간 0건(신규)** 이다 — 0 과 구분한다(0 은 "순위가 그대로").
   * 🔴 `deltaPct`(건수 증감률)와 **다른 지표**다 — 이쪽은 순위가 몇 칸 움직였는지다.
   */
  rankDelta: number | null
  /** 유형별 내역. */
  breakdown: RegionBreakdown[]
}

/** 총 민원·불법주차·위험단계 알림 KPI 조회. */
export async function fetchKpi(params?: DashboardQuery): Promise<KpiResponse> {
  const { data } = await api.get<KpiResponse>('/dashboard/kpi', { params })
  return data
}

/** 읍면동 민원 순위 조회 (count 내림차순, 전체 읍면동). */
export async function fetchRegions(params?: DashboardQuery): Promise<RegionItem[]> {
  const { data } = await api.get<RegionItem[]>('/dashboard/regions', { params })
  return data
}

/*
 * 민원 발생 추이.
 * 백엔드는 빈 칸도 total=0 으로 채워 주므로, 프론트는 받은 순서대로 그리기만 한다.
 */

/** x축 한 칸이 묶는 단위. HOUR 는 항상 24칸(구간 내 같은 시각 합산). */
export type TrendBucket = 'HOUR' | 'DAY'

/** 추이 x축 한 칸. */
export interface TrendPoint {
  /** HOUR 면 '00'~'23', DAY 면 '2025-12-04'. */
  bucketKey: string
  /** 이 칸의 총건수. */
  total: number
  /** 유형별 분해. 건수 0 인 유형은 빠져 있다. */
  breakdown: RegionBreakdown[]
}

/** GET /api/dashboard/trend 응답. */
export interface TrendResponse {
  /** 실제 적용된 버킷(서버가 정할 수 있으므로 응답으로 받는다). */
  bucket: TrendBucket
  /** 집계 구간 시작일 YYYY-MM-DD. */
  startDt: string
  /** 집계 구간 종료일 YYYY-MM-DD. */
  endDt: string
  /** x축 칸 목록(빈 칸 포함, 키 오름차순). */
  points: TrendPoint[]
}

/** 민원 발생 추이 조회. 파라미터는 kpi·regions 와 동일하며 bucket 만 추가된다. */
export async function fetchTrend(params?: DashboardQuery): Promise<TrendResponse> {
  const { data } = await api.get<TrendResponse>('/dashboard/trend', { params })
  return data
}

/*
 * 하단/집중구역 카드용 실 API (백엔드 VO와 1:1).
 * 전부 GET /api/dashboard/... 이며 배열을 반환한다(데이터 없으면 []).
 * limit 은 optional — 비어 있으면 미전송(백엔드 기본 개수).
 */

/**
 * GET /api/dashboard/hotspots 배열 항목 (불법주차 집중구역 순위).
 *
 * ⚠️ **저장된 분석 결과가 아니라 실시간 군집 분석 결과다**. 백엔드가 분석 화면과
 * 같은 서비스(`GET /api/analysis/hotspot`)를 호출해 카드용으로 추린 것이라, 산식·등급 임계값이
 * 집중구역 화면과 동일하다. 다만 **기간은 대시보드 조회단위(어제/주간/월간)를 따르지 않고
 * 기준일 기준 최근 30일 고정**이다 — 하루치로는 군집이 형성되지 않아 카드가 늘 비기 때문이다.
 * 🔴 그 **기준일은 오늘이 아니라 최신 민원 발생일**이다(민원 0건이거나 조회가
 * 실패하면 오늘로 폴백한다).
 *
 * 🔴 **기간은 여전히 안 받지만 지역은 받는다** — `fetchHotspots` 가 `sggCd` 를
 * 넘긴다. 그래서 이 API 는 `DashboardQuery` 를 통째로 받지는 않고 `sggCd` 만 받는다.
 */
export interface HotspotItem {
  /** 순위(1부터). */
  rnk: number
  /** 구역명. */
  zoneNm: string
  /** 민원 건수. */
  cvlcptCnt: number
  /** 단속 건수. */
  crdnCnt: number
  /** 종합 점수. */
  snthsScr: number
  /**
   * 등급코드. 판정은 백엔드(`HotspotGrade`)가 하며 프론트는 색만 고른다 —
   * 타입은 집중구역 화면과 **같은 정의**를 재사용해 코드값이 갈리지 않게 한다.
   */
  grade: HotspotGrade
  /** 등급명 (심각 | 경고 | 주의 | 양호). 화면 표시는 이 응답값을 그대로 쓴다. */
  gradeNm: string
  /** 대표점 위도. 없으면 null. */
  mrkLat: number | null
  /** 대표점 경도. 없으면 null. */
  mrkLot: number | null
}

/** GET /api/dashboard/analyses 배열 항목 (분석 실행 목록). */
export interface AnalysisItem {
  /** 실행 ID. */
  excnId: number
  /** 분석유형 코드. */
  anlsTypeCd: string
  /** 분석유형 이름. */
  anlsTypeNm: string
  /** 실행상태 코드(DONE/RUN/WAIT/FAIL 등). */
  excnSttsCd: string
  /** 실행상태 이름. */
  excnSttsNm: string
  /** 실행 일시. */
  excnDt: string
  /** 대상 읍면동 코드. 없으면 null. */
  emdCd: string | null
  /*
   * ── 정책 시뮬레이션(POLICY_SIM) 행 제목 재료 ──
   * 다른 유형·재료 없음은 null. 🔴 숫자 칸도 문자열이다(백엔드가 anls_param_json 을 캐스팅 없이 내린다)
   * — 해석·조합은 `policySimRunTitle()`(components/policysim/policySimView.ts) 한 곳에서 한다.
   */
  /**
   * 결과아이디. 카드 행을 누르면 정책 시뮬레이션 화면이 이 값으로 시나리오 비교의 그 행을 선택한다.
   * POLICY_SIM 이 아니면 null.
   */
  rsltId: number | null
  /** 분석 범위(결과 행 anls_rng_cn — "제주시 연동" · "제주시 전체" · "전체 지역"). */
  anlsRngCn: string | null
  /** 정책 유형(EXPAND / REPAIR / FEE). */
  plcyType: string | null
  /** 확충 방식(VACLOT / MULTFLR / NEW). */
  expandMethod: string | null
  /** 추가 주차면수(문자열). */
  addPkltSlot: string | null
  /** 도로 정비 유형(RSDNC / CMRC). */
  roadMtncType: string | null
  /** 도로 정비 연장 m(문자열). */
  roadMtncLen: string | null
  /** 요금 적용 방식(RAISE / CONVERT). */
  feeAplcnMthd: string | null
  /** 요금 인상률 %(문자열). */
  feeIncrsRt: string | null
}

/** GET /api/dashboard/reports 배열 항목 (보고서 목록). */
export interface ReportItem {
  /** 보고서 ID. */
  rptpId: number
  /** 보고서 이름. */
  rptpNm: string
  /** 상태 코드(WAIT 이면 대기). */
  sttsCd: string
  /** 상태 이름. */
  sttsNm: string
  /** 파일 포맷. 자동 생성은 null (설정에 형식 칸이 없다). */
  fileFmt: string | null
  /** 생성 일시. 없으면 null. */
  crtYmd: string | null
}

/**
 * GET /api/dashboard/ai-suggestions 배열 항목 (AI 추천 질문).
 *
 * ⚠️ **현재 프론트 호출부 없음** — 대시보드 AI 카드가 FAQ 대신 `최근 대화`
 *    (`GET /api/ai/sessions`)를 보여 주게 바뀌었다. 백엔드 엔드포인트가 살아 있어 **지우지 않는다.**
 */
export interface AiSuggestionItem {
  /** FAQ ID. */
  faqId: number
  /** 추천 질문 문구. */
  qstn: string
}

/**
 * 집중구역 순위 조회 (종합점수 상위순).
 *
 * `sggCd` 를 주면 그 시군구만(미전송·빈 값이면 전 지역). 기간은 조회단위와 무관하게
 * 최근 30일 고정이라 받지 않는다.
 */
export async function fetchHotspots(limit?: number, sggCd?: string): Promise<HotspotItem[]> {
  const params: Record<string, string | number> = {}
  if (limit != null) params.limit = limit
  if (sggCd) params.sggCd = sggCd
  const { data } = await api.get<HotspotItem[]>('/dashboard/hotspots', {
    params: Object.keys(params).length > 0 ? params : undefined,
  })
  return data
}

/**
 * 분석 실행 목록 조회 (최신순). `anlsTypeCd` 를 주면 그 실행유형만.
 *
 * 빈 값·미전송이면 서버가 종전대로 전체 유형을 내린다.
 */
export async function fetchAnalyses(limit?: number, anlsTypeCd?: string): Promise<AnalysisItem[]> {
  const params: Record<string, string | number> = {}
  if (limit != null) params.limit = limit
  if (anlsTypeCd != null) params.anlsTypeCd = anlsTypeCd
  const { data } = await api.get<AnalysisItem[]>('/dashboard/analyses', {
    params: Object.keys(params).length > 0 ? params : undefined,
  })
  return data
}

/**
 * 보고서 목록 조회 (최신순).
 *
 * `crtType`(생성구분 `RPTP_CRT_TYPE_CD` — AUTO=자동 · MANUAL=직접 · AI=AI 생성)을 주면 그
 * 생성구분만. 대시보드 보고서 카드는 `MANUAL` 이다. 미전송·빈 값이면 종전대로
 * 전체(자동+직접)이고, 미등록 코드를 줘도 오류가 아니라 빈 배열(200)이다.
 */
export async function fetchReports(limit?: number, crtType?: string): Promise<ReportItem[]> {
  const params: Record<string, string | number> = {}
  if (limit != null) params.limit = limit
  if (crtType) params.crtType = crtType
  const { data } = await api.get<ReportItem[]>('/dashboard/reports', {
    params: Object.keys(params).length > 0 ? params : undefined,
  })
  return data
}

/**
 * AI 추천 질문 조회.
 *
 * ⚠️ **현재 프론트 호출부 없음**(대시보드 AI 카드가 `최근 대화` 로 교체됐다).
 *    백엔드 `/api/dashboard/ai-suggestions` 가 남아 있으므로 **지우지 않는다.**
 */
export async function fetchAiSuggestions(limit?: number): Promise<AiSuggestionItem[]> {
  const { data } = await api.get<AiSuggestionItem[]>('/dashboard/ai-suggestions', {
    params: limit != null ? { limit } : undefined,
  })
  return data
}

/*
 * 데이터 최신성.
 * GET /api/dashboard/data-status — 파라미터 없음. 집중구역 카드 기준일에 쓴다.
 * 🔴 화면 상단 `최신 갱신` 표기는 `/dashboard/data-freshness` 다 — 이 API 는 민원이
 *    언제 **발생**했나이고, 그쪽은 원천을 언제 **수집**했나(배치 실행일)라 다른 사실이다.
 */

/** GET /api/dashboard/data-status 응답. */
export interface DataStatus {
  /** 민원 최종 발생일시. 데이터가 없으면 null. */
  lastOcrnDt: string | null
  /** 집계 대상 민원 총건수. */
  cvlcptCnt: number
}

/** 민원 데이터 최신성 조회. 필터와 무관하므로 마운트 시 1회만 부른다. */
export async function fetchDataStatus(): Promise<DataStatus> {
  const { data } = await api.get<DataStatus>('/dashboard/data-status')
  return data
}

/*
 * 데이터 최신 갱신.
 * GET /api/dashboard/data-freshness — 파라미터 없음. 화면 상단 `최신 갱신` 표기 + 데이터 기준일 InfoTip 이 쓴다.
 * 🔴 위 `data-status`(민원 **발생**일)와 다른 사실이다 — 이쪽은 수집 배치 **실행**일이다.
 */

/** GET /api/dashboard/data-freshness 의 행. 원천 1종 = 1행이며 **항상 4행**이다. */
export interface DataFreshnessItem {
  jobNm: string
  /** 화면 표시명 — `민원` · `단속` · `공영주차장 현황` · `공영주차장 이용률`. */
  dataNm: string
  /** 마지막 **성공** 배치 실행 일시. 한 번도 안 돌았으면 null. */
  lastExcnDt: string | null
  /** 갱신 주기 문구. 🔴 단속은 스케줄이 없어 `수동 실행` 이다. */
  cyclNm: string
}

export interface DataFreshness {
  /** items 중 가장 최신 실행 일시. 전부 안 돌았으면 null. */
  latestDt: string | null
  items: DataFreshnessItem[]
}

/** 데이터 최신 갱신 조회. 필터와 무관하므로 마운트 시 1회만 부른다. */
export async function fetchDataFreshness(): Promise<DataFreshness> {
  const { data } = await api.get<DataFreshness>('/dashboard/data-freshness')
  return data
}
