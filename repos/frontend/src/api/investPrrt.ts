import { api } from './client'
import type { ZoneRcmdtnWorkbookVrbl } from './zoneRcmdtn'
import {
  vrblVlsBody,
  vrblVlsQuery,
  type AnalysisWeight,
  type AnlsVrblAjmt,
  type VrblVls,
} from './analysisWeight'

/*
 * 투자우선순위 보드 실 API 클라이언트.
 * 백엔드 계약(2026-10-14 로컬 실측):
 *   GET /api/analysis/investment-priority → InvestPrrtResult
 *
 * 🔴 **공용 `api` 인스턴스를 쓴다**(`client.ts`) — 세션 쿠키·`X-XSRF-TOKEN`·401 → `/login` 인터셉터가
 * 그 인스턴스에만 있다. 보고서 저장(POST)도 같은 인스턴스로 붙인다.
 *
 * ⚠️ 응답은 `rnk` 오름차순이고, 값이 없는 필드는 **키를 유지한 채 `null`** 로 온다.
 * ⚠️ 점수는 백엔드가 `BigDecimal` 이라 `96.535326087` 처럼 긴 소수로 온다 — 자릿수는 화면이 정한다.
 */

/* ── 1. 조회 파라미터 ──────────────────────────────────── */

/**
 * 전부 선택이다. 🔴 **화면은 날짜를 보내지 않는다** — 생략하면 서버가 "오늘 기준 최근 1년" 을 채운다.
 * 행정구역 필터는 화면이 클라이언트에서 거르므로(재조회 없음) 조회에는 싣지 않는다.
 */
export interface InvestPrrtParams {
  /** 조회 시작일 (YYYYMMDD). */
  bgngYmd?: string
  /** 조회 종료일 (YYYYMMDD, 포함). */
  endYmd?: string
  /** 시군구명. 빈 값은 생략으로 취급된다. */
  sggNm?: string
  /** 읍면동코드(8자리). 빈 값은 생략으로 취급된다. */
  emdCd?: string
  /**
   * 가중치 4·계수 4 일시 조정. 생략·빈 객체 = 설정값. 가중치·경계는 묶음 전체.
   * GET 쿼리에는 JSON 문자열로 싣는다(`fetchInvestPrrt` 가 바꾼다).
   */
  vrblVls?: VrblVls
}

/* ── 2. 응답 타입 ─────────────────────────────────────── */

/** 우선순위 등급코드 — `H`(높음) · `M`(중간) · `L`(낮음). 🔴 판정은 백엔드가 한다(프론트는 색만 고른다). */
export type InvestPrrtLvlCd = 'H' | 'M' | 'L'

/**
 * 반영된 지표 1건 — 분석변수 설정 VO 전체다(집중구역 `HotspotWeight` 와 같은 선례).
 * `vrblCd` 는 `SAFETY`·`COMPLAINT`·`USAGE`·`ACCIDENT`·`ENFORCE`(반영된 것만), 가중치 값은 `stngVl`(% · 예 25).
 */
export type InvestPrrtWeight = AnalysisWeight

/** 등급 판정 기준 한 줄. 상한은 오지 않는다 — 바로 위 등급의 `minScr` 미만이다. */
export interface InvestPrrtGradeCriterion {
  prrtLvlCd: InvestPrrtLvlCd
  /** 화면 표시용 등급명 (`높음`/`중간`/`낮음`). 🔴 배지·범례 라벨의 출처다. */
  prrtLvlNm: string
  /** 이 등급으로 판정되는 최소 종합점수(이상). 예) 80 · 60 · 0. */
  minScr: number
}

/** 적용된 기준값. */
export interface InvestPrrtCrtrVls {
  /** 민원·단속 최근접 배정 반경 (m) — 설정 `ALTMNT_RNG_VL`(기본 300). */
  altmntRngVl: number
  /** 🔴 **목록 행 수 = 지도 핀 수의 상한 N** — 설정 `INDCT_PKLT_CNT`(기본 50). 소스에 50 을 박지 않는다. */
  indctPkltCnt: number
}

/**
 * 한계 안내. 🔴 **화면에 그리지 않는다**(사용자 결정 2026-10-13 — 시안대로).
 * API 에는 남아 있어 타입만 둔다(나중에 안내를 넣기로 하면 프론트만 고친다).
 */
export interface InvestPrrtWarning {
  code: string
  message: string
  /** 해당 주차장 수. 없으면 null. */
  cnt: number | null
}

/** 상단 카드의 숫자. 🔴 **필터 전 전체 기준**이다(표시 집합의 건수가 아니다). */
export interface InvestPrrtSmry {
  /** 분석대상 주차장 수. */
  trgtCnt: number
  /** 높음 등급 주차장 수. */
  highCnt: number
  /**
   * 조회 기간 안 사고 건수. 🔴 **사고이력 표가 비면 `null`** 이다(`warnings.ACDNT_NO_DATA` 판정 근거).
   * null 이면 화면은 0 으로 그린다("값 없으면 0").
   */
  acdntCnt: number | null
}

/** 주차장 1곳. */
export interface InvestPrrtLot {
  /**
   * **전 지역 순위**(종합점수 기준). 🔴 화면·보고서가 그리는 순위는 이 값이 아니라 **그 결과 안에서의
   * 순번**이다(화면은 `investPrrtView.selectDisplayLots` 의 `dspRnk`, 보고서는 템플릿이 매긴다).
   * 이 값은 기본 정렬과 동점 정렬 기준으로만 쓴다.
   */
  rnk: number
  /** 시군구 안 순위. 화면이 쓰지 않는다. */
  sggRnk: number | null
  /** 단속을 뺀 순위. 화면이 쓰지 않는다. */
  crdnExclRnk: number | null
  pkltId: number
  pkltNm: string
  pkltAddr: string
  sggNm: string
  /** 읍면동코드(8자리). 🔴 읍면동 필터는 이름이 아니라 이 코드로 거른다. */
  emdCd: string
  emdNm: string
  /** 주차면수. */
  pkltCpcty: number | null
  /** 설치연월 (YYYYMM). 대상의 92% 가 결측이다 — 있을 때만 표시한다. */
  instlYm: string | null
  lat: number
  lot: number
  /** 종합점수 (0~100). */
  snthsScr: number
  prrtLvlCd: InvestPrrtLvlCd
  /** 점수가 높은 이유 코드. 화면이 쓰지 않는다. */
  rsnCd: string
  /** 안전점검 결과코드. 점검 이력이 없으면 null. */
  sftyChckRsltCd: string | null
  /** 안전점검 일자 (YYYYMMDD). */
  sftyChckYmd: string | null
  /** 지표별 점수 (각 0~100). */
  sftyRiskScr: number
  cvlcptFrqScr: number
  utztnPttnScr: number
  crdnScr: number
  /** 사고 점수 — `acdntCnt` 의 대상 주차장 안 백분위. */
  acdntScr: number
  /** 배정된 민원 건수. */
  cvlcptCnt: number
  /** 일평균 회전율. 기간에 이용패턴 값이 없으면 null — 이용패턴이 아예 없는 대상 포함. */
  dayAvgTrnvrRt: number | null
  /** 배정된 단속 건수. */
  crdnCnt: number
  /** 그 주차장에 **기록된** 조회 기간 안 사고 건수(배정이 아니다). 없으면 0. */
  acdntCnt: number
}

/** GET /api/analysis/investment-priority 응답. */
export interface InvestPrrtResult {
  /** 적용된 조회 기간 (YYYYMMDD). 날짜를 생략하면 서버가 채운 값이 온다. */
  bgngYmd: string
  endYmd: string
  /** 산식 설명 문장. */
  scoreFormula: string
  /** 🔴 부제·가중치 안내문의 출처 — 고정 문구를 쓰지 않는다. */
  weights: InvestPrrtWeight[]
  gradeCriteria: InvestPrrtGradeCriterion[]
  crtrVls: InvestPrrtCrtrVls
  warnings: InvestPrrtWarning[]
  smry: InvestPrrtSmry
  /**
   * 가중치 4 + 계수 4 의 적용 내역. 구 스냅샷은 `null`. 문서에는 그리지 않는다.
   * 화면 배너·`(조정됨)` 은 이 값이 아니라 계수 훅 상태로 그린다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
  /** `rnk` 오름차순. */
  lots: InvestPrrtLot[]
  /*
   * 지표별 데이터 기간. 🔴 **화면 전용이다** — 보고서 스냅샷(`InvestPrrtReport`)에는 서버가 싣지 않아
   * 거기서는 항상 `undefined` 다(그래서 optional). 민원·사고 기간은 위 `bgngYmd`·`endYmd` 그대로다.
   * 최근 자료일 4칸은 "창 안" 이 아니라 **종료일 이하 전체**의 최댓값이고, 자료가 없으면 `null` 이다.
   */
  /** 이용률 집계 시작 달 (YYYYMM). */
  utztnBgngYm?: string | null
  /** 이용률 집계 끝 달 (YYYYMM) — 조회 종료 달. */
  utztnEndYm?: string | null
  /** 안전위험도 기준일 (YYYYMMDD) — "이 날짜 이전 가장 최근 점검" 의 기준(= 조회 종료일). */
  sftyCrtrYmd?: string | null
  /** 종료일 이하 주차 민원 최근 발생일 (YYYYMMDD). 헤더 `최근 갱신` 의 `민원 건수` 행. */
  cvlcptLastYmd?: string | null
  /** 종료 달 이하 대상 주차장의 값 있는 최근 달 (YYYYMM) — 월 단위 원천이라 달까지만. `이용률` 행. */
  utztnLastYm?: string | null
  /** 종료일 이하 대상 주차장의 최근 점검일 (YYYYMMDD). `안전위험도` 행. */
  sftyChckLastYmd?: string | null
  /** 종료일 이하 대상 주차장의 최근 사고일 (YYYYMMDD). `사고 이력` 행. */
  acdntLastYmd?: string | null
}

/* ── 3. 조회 ──────────────────────────────────────────── */

/** 투자우선순위 조회. 파라미터를 생략하면 전 지역 · 오늘 기준 최근 1년이다. */
export async function fetchInvestPrrt(params?: InvestPrrtParams): Promise<InvestPrrtResult> {
  // 🔴 `vrblVls` 는 객체 그대로 넘기면 `vrblVls[KEY]=` 로 깨진다 — JSON 문자열로 바꾸고, 비면 키를 뺀다.
  const { vrblVls, ...rest } = params ?? {}
  const query: Record<string, string> = {}
  for (const [k, v] of Object.entries(rest)) if (v) query[k] = v
  const vv = vrblVlsQuery(vrblVls)
  if (vv) query.vrblVls = vv
  const { data } = await api.get<InvestPrrtResult>('/analysis/investment-priority', {
    params: query,
  })
  return data
}

/* ── 4. 보고서 저장 · 스냅샷 ─────────────────── */

/** 보고서 파일형식. 🔴 대문자다 — 그 밖의 값은 400. */
export type InvestPrrtFileFmt = 'PDF' | 'DOCX' | 'XLSX'

/**
 * 엑셀 재계산 원자료 1행(`wbk.rows[]` — 백엔드 `InvestPrrtWorkbookRowVO`).
 * 지표별 `k` = **자기보다 값이 엄격히 작은 대상 수**(전 지역 모집단 기준 — 필터 전). 백분위 = k ÷ (n − 1) × 100.
 * ⚠️ `*LtCnt` 는 임시 이름이다(데이터표준사전 등재 요청 대상).
 */
export interface InvestPrrtWorkbookRow {
  pkltId: number
  /** 민원 건수 `k`. */
  cvlcptLtCnt: number
  /** 이용률 `k`. */
  utztnLtCnt: number
  /** 사고이력 `k`. */
  acdntLtCnt: number
}

/**
 * 투자우선순위 엑셀 재계산 데이터(백엔드 `InvestPrrtWorkbookVO`). 주차장 이름·주소·면수·안전 점수는 `lots[]` 를 쓴다.
 * 🔴 `trgtCnt` 는 **필터 전 전 지역 대상 수 `n`** 이다 — `lots.length` 가 아니다(백분위 분모).
 */
export interface InvestPrrtWorkbook {
  trgtCnt: number
  /** `lots` 와 같은 주차장·같은 순서. */
  rows: InvestPrrtWorkbookRow[]
  /** `INVEST` 8행(스코프 순서) — 공용 `AnlsWorkbookVrblVO`. */
  vrbls: ZoneRcmdtnWorkbookVrbl[]
}

/**
 * POST /api/analysis/investment-priority/reports 요청.
 *
 * 🔴 **화면은 행정구역 필터와 `fileFmt` 만 보낸다.** 날짜를 생략하면 서버가 "오늘 기준 최근 1년" 을
 * 채우고, 실제로 쓰인 날짜가 응답 `bgngYmd`·`endYmd` 로 온다. 우선순위 세그먼트·정렬은 화면 표시용이라
 * 저장 대상이 아니다(보낼 칸도 없다).
 */
export interface InvestPrrtReportCreateRequest {
  /** 시군구명. `''`·`null`·생략 = 전 지역. */
  sggNm?: string | null
  /** 읍면동코드(8자리). `''`·`null`·생략 = 시군구 전체. */
  emdCd?: string | null
  bgngYmd?: string
  endYmd?: string
  fileFmt: InvestPrrtFileFmt
  /** 가중치·계수 일시 조정 — 🔴 결과를 만든 실행의 값을 보낸다. */
  vrblVls?: VrblVls
}

/**
 * 저장된 보고서 한 건 — 메타 7칸 + 조회 응답과 같은 본문.
 * 저장(201)과 스냅샷 조회(200)가 **같은 모양**이라 문서 템플릿이 두 경로를 구분하지 않는다.
 *
 * 🔴 **`lots` 는 행정구역 필터를 통과한 전체**다(화면의 상위 N 이 아니다).
 * 🔴 **`smry` 는 필터를 걸어 저장해도 전 지역 값이다**(lots 30행인데 `trgtCnt` 509) —
 * "이 보고서의 건수" 는 `lots.length` 로 센다.
 */
export interface InvestPrrtReport extends InvestPrrtResult {
  rptpId: number
  excnId: number
  /** 보고서명 `투자 우선순위 보고서 전체 지역`. 보고서 목록의 행 이름이다. */
  rptpNm: string
  /** 지역 표시명 — `전체 지역` / `서귀포시 전체` 등. */
  regionNm: string
  /** 분석기간설명 `2026.07.13 ~ 2026.10.13`. */
  anlsPrdExpln: string
  fileFmt: InvestPrrtFileFmt
  /** 생성일시 (ISO-8601). */
  crtYmd: string
  /**
   * 🆕 엑셀 재계산 데이터 — 형식과 무관하게 새 보고서 전부에 실린다.
   * 구 스냅샷·`ENFORCE` 가중치가 있던 실행은 **키가 없다**.
   */
  wbk?: InvestPrrtWorkbook | null
}

/**
 * 투자우선순위 보고서 저장 — 서버가 같은 조건으로 다시 계산해 저장하고 그 스냅샷을 201 로 돌려준다.
 *
 * 🔴 **공용 `api` 인스턴스**로 보낸다 — `X-XSRF-TOKEN` 이 자동으로 실린다(새 인스턴스면 403).
 * 🔴 빈 값(`''`·`null`)은 바디에서 뺀다 — 화면 필터의 "전체" 가 빈 문자열이다.
 *
 * 오류: 400(`fileFmt` 오값 · 기간 오류 · 없는 `emdCd` · 시군구와 읍면동 불일치 · **필터 통과 0곳**) /
 * 403(CSRF) / 409(분석변수 설정 오류). 400 본문에는 사유 문구가 없다.
 */
export async function createInvestPrrtReport(
  req: InvestPrrtReportCreateRequest,
): Promise<InvestPrrtReport> {
  const body: Record<string, string | VrblVls> = { fileFmt: req.fileFmt }
  if (req.sggNm) body.sggNm = req.sggNm
  if (req.emdCd) body.emdCd = req.emdCd
  if (req.bgngYmd) body.bgngYmd = req.bgngYmd
  if (req.endYmd) body.endYmd = req.endYmd
  // POST 는 바디 객체. 비면 키를 뺀다.
  const vrblVls = vrblVlsBody(req.vrblVls)
  if (vrblVls) body.vrblVls = vrblVls

  const { data } = await api.post<InvestPrrtReport>('/analysis/investment-priority/reports', body)
  return data
}

/**
 * 투자우선순위 보고서 **스냅샷** 조회 — 재계산 없이 저장된 결과를 그대로 읽는다
 * (가중치 설정을 바꾼 뒤에도 저장 시점 값이다). 이 호출로 보고서 행이 늘지 않는다.
 *
 * 404: 보고서 없음 · INVEST 실행이 아님 · 결과 행 없음. 호출부는 셋을 구분하지 않는다.
 */
export async function fetchInvestPrrtReportSnapshot(rptpId: number): Promise<InvestPrrtReport> {
  const { data } = await api.get<InvestPrrtReport>(`/reports/${rptpId}/investment-priority`)
  return data
}
