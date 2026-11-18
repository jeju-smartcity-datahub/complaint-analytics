import { api } from './client'
import { LIFEZONE_ANALYSIS_TIMEOUT_MS } from './lifezone'
import type {
  LifezoneFileFmt,
  LifezoneGrade,
  LifezoneTargetCd,
  LifezoneWorkbookVrbl,
} from './lifezone'
import {
  vrblVlsBody,
  vrblVlsQuery,
  type AnlsVrblAjmt,
  type VrblVls,
} from './analysisWeight'

/*
 * 생활권 시뮬레이션 **수급** 분석 API 클라이언트 (TSK-4089).
 * 백엔드 계약: `jeju.datahub.lifezone.web.LifezoneAnalysisController#supply`
 *   GET /api/analysis/lifezone/supply → LifezoneSupplyResult
 *
 * 🔴 **`api/lifezone.ts`(민원건수)를 확장하지 않고 새 파일로 둔다** — 같은 화면의 다른 탭이지만
 * 응답 형태가 전혀 다르다. 민원건수는 격자마다 값 하나에 **순서형 5등급**이 붙지만, 수급은
 * 민원·공급 두 축을 교차시킨 **범주형 4사분면**이라 요약·범례·셀 지표가 겹치지 않는다.
 * 격자크기 목록은 🔁 민원건수와 같다(`1000/500/250/100` — TSK-4206).
 *
 * 🔴 **이 산출물은 "주차 부족 면수" 가 아니다.** 주차 수요 자료(등록차량·법정주차대수·실태조사)가
 * 없어 확보율을 계산할 수 없다 — 낼 수 있는 최대 표현은 **"공영주차 공급 대비 민원 발생량"** 이다.
 * 그래서 응답의 `disclaimer` 를 화면에 **상시** 표기해야 하고, 문구에 "주차 부족 N면"·"위험 지역"
 * 같은 단정을 쓰지 않는다(인계서 §4-4).
 *
 * 🔴 **임계값·사분면 문구·집중 주의 판정을 이 파일에 적지 않는다.** 임계값은 조회 결과 안에서
 * 매번 다시 산출되고(격자크기·기간·지역이 바뀌면 함께 바뀐다), 사분면명·범례 문구·
 * `cncntWarnYn` 판정은 전부 서버가 내린다. 프론트는 색만 고른다
 * (`components/lifezone/lifezoneQuadrant.ts`).
 *
 * 🔁 **TSK-4258 — 사분면 → 부족률 고정 구간 5단계(D19 — 경계는 설정 표 `SPLY_GRD_BNDRY1~4` · D20).** 등급은 민원건수와 같은 5종 코드(`LifezoneGrade`)를
 * 쓰고 판정은 서버가 한다(D6·D19 — 프론트에서 다시 계산하지 않는다). 색은 `components/lifezone/lifezoneGrade.ts`.
 * 구 모양(사분면)은 **구 스냅샷 렌더 전용**으로 `Legacy*` 이름에 남긴다(D10·D14) — 화면 경로는 참조하지 않는다.
 */

/* ── 1. 코드 타입 ─────────────────────────────────────── */

/**
 * 사분면코드 — **범주형 4종**이다(민원건수는 순서형 5등급).
 *
 * 우선순위 개념은 `Q1`(부족 의심) 하나뿐이며 `Q2`~`Q4` 사이에는 "더 나쁨" 이 없다.
 * 그래서 지도 색도 단계색(순차 팔레트)이 아니라 **범주형 4색**이어야 한다(인계서 §4-2).
 */
export type LifezoneQuadrant = 'Q1' | 'Q2' | 'Q3' | 'Q4'

/**
 * 수급이 지원하는 격자크기(m).
 *
 * 🔁 민원건수(`1000 | 500 | 250 | 100`)와 **같다**(TSK-4206 — 종전 `100 | 200 | 300 | 500`).
 * 수급은 격자 마스터(`TB_GRID`)를 쓰지 않고 EPSG:5179 좌표로 격자를 **계산**하지만, 유형을 바꿔도
 * 같은 격자로 비교되게 목록을 맞췄다. 목록·기본값은 여전히 화면에 박지 말고
 * `/analysis/lifezone/targets` 의 `gridSzs`·`dfltGridSz` 를 쓴다.
 */
export type LifezoneSupplyGridSz = 1000 | 500 | 250 | 100

/* ── 2. 요청 ──────────────────────────────────────────── */

/** 수급 분석 조회 조건. `sggNm`·`emdCd` 는 생략이 "전체" 이고 빈 문자열은 400 이다. */
export interface LifezoneSupplyParams {
  /** 분석 시작일 (YYYYMMDD) */
  bgngYmd: string
  /** 분석 종료일 (YYYYMMDD, 당일 포함) */
  endYmd: string
  /** 격자크기(m). 생략하면 서버 기본값 */
  gridSz?: LifezoneSupplyGridSz
  /** 시군구명 (생략 = 전 지역) */
  sggNm?: string
  /** 읍면동코드 (생략 = 시군구 전체) */
  emdCd?: string
  /** 🆕 수급 계수 일시 조정(TSK-4325 — 정수만). 생략·빈 객체 = 설정값. 경계 4개는 묶음 전체(D21). */
  vrblVls?: VrblVls
}

/* ── 3. 응답 ──────────────────────────────────────────── */

/** 사분면 범례 1행. 문구는 서버가 임계값 숫자를 박아 만든다 — 프론트가 조립하지 않는다. */
export interface LifezoneQuadrantCriterion {
  qdrn: LifezoneQuadrant
  /** 사분면명 (부족 의심 / 민원 집중 / 공급 부족 / 양호) */
  qdrnNm: string
  /** 판정 근거 요약 */
  qdrnExpln: string
  /** 범례 문구 — 임계값 실제 숫자가 들어 있다. 🔴 그대로 출력한다 */
  lgndTxt: string
  /** 해당 사분면 격자 수 */
  gridCnt: number
  /** 해당 사분면 민원 합계 */
  cvlcptCnt: number
  /** 해당 사분면 중 공영주차면 0 인 격자 수 */
  zeroSplyGridCnt: number
}

/**
 * (구 스냅샷 전용) 사분면 방식 셀 1칸 — TSK-4258 이전 보고서 스냅샷을 그대로 렌더하기 위해 남긴다(D14).
 * 🔴 화면·신규 생성 경로는 쓰지 않는다 — 신규 응답에는 `qdrn`·`qdrnNm`·`cvlcptPerSlot` 키가 없다.
 */
export interface LegacyLifezoneSupplyCell {
  /** 격자ID `{격자크기}m_{gx}_{gy}` — 선택 상태의 키다 */
  gridId: string
  qdrn: LifezoneQuadrant
  qdrnNm: string
  /** 사분면 내 순위 (민원건수 내림차순) */
  rnk: number
  sggNm: string | null
  /** 읍면동명 — 격자 안 민원의 **최빈** 읍면동이다(격자가 경계를 걸칠 수 있다) */
  emdNm: string | null
  emdCd: string | null
  cvlcptCnt: number
  /** 공영주차면수 — 🔴 "부족 면수" 가 아니라 **공급량 자체**다 */
  pkltCpctySum: number
  pkltCnt: number
  /**
   * 면당 민원. 🔴 주차면 0 이면 `null` 이다 — `0`·`Infinity` 가 아니다.
   * 🔁 TSK-4258 — 스냅샷 재직렬화가 NON_NULL 이라 `null` 은 **키 생략(`undefined`)** 으로 도착한다 → 비교는 `== null`.
   */
  cvlcptPerSlot?: number | null
  /** 격자 안 민원의 고유 좌표 개수 */
  dstncPtCnt: number
  /**
   * 소수 지점 집중 주의 여부.
   *
   * 🔴 **판정은 서버가 한다**(고유좌표 ≤ 3 && 민원 ≥ 50 — 인계서 §4-3). 프론트에 같은 조건을
   * 다시 적으면 기준이 갈린다. 화면은 이 값이 `'Y'` 일 때 안내 문구만 띄운다.
   */
  cncntWarnYn: 'Y' | 'N'
  ctrLat: number
  ctrLot: number
  /**
   * 격자 꼭짓점 링 `[[위도, 경도], …]` 4점.
   *
   * 🔴 **서버가 EPSG:5179 정방형을 4326 으로 변환해 내린다.** 중심 위경도에 `±size/2` 를 더해
   * 그리면 실제 격자와 어긋난다 — 경도 1도의 실제 거리가 위도에 따라 달라지기 때문이다.
   */
  ring: [number, number][]
}

/**
 * (구 스냅샷 전용) 사분면 방식 응답 전문 — TSK-4258 이전 보고서 스냅샷 렌더용(D14).
 * 🔴 화면 조회 결과는 이 모양이 아니다({@link LifezoneSupplyResult}).
 */
export interface LegacyLifezoneSupplyResult {
  /** 🔁 TSK-4258 — 구 스냅샷엔 분석 방법 키가 없다. 신규 결과(`'SHRT_RT'`)와 유니언을 가르는 자리 */
  anlsMthd?: undefined
  bgngYmd: string
  endYmd: string
  sggNm: string | null
  emdCd: string | null
  anlsTrgtCd: LifezoneTargetCd
  anlsTrgtNm: string
  gridSz: LifezoneSupplyGridSz
  gridSzNm: string
  /** 격자 좌표계 (`EPSG:5179`) */
  crs: string
  sectionTitle: string
  sectionSub: string
  /** 🔴 화면에 **상시** 표기해야 하는 안내 문구 (인계서 §4-4) */
  disclaimer: string
  /**
   * 민원高 임계값 P80 **원값**. 보간 결과라 정수가 아닐 수 있다(300m = 23.4).
   * 🔴 표시할 때만 올림하고 판정은 원값으로 한다.
   */
  cvlcptHighThld: number
  /** 공급低 임계값 P70 원값 */
  splyLowThld: number
  /** 사분면 범례 4행 (0건 사분면도 남는다 — 색↔의미 대응이 조회마다 바뀌면 안 된다) */
  qdrnCriteria: LifezoneQuadrantCriterion[]
  /** 총 격자 수 (민원>0 격자) */
  totGridCnt: number
  /** 총 민원 건수 — 🔴 격자크기를 바꿔도 같아야 한다(합계 보존) */
  totCvlcptCnt: number
  /** 총 공영주차면 수 (민원>0 격자) */
  totPkltCpctySum: number
  q1Cnt: number
  q2Cnt: number
  q3Cnt: number
  q4Cnt: number
  /**
   * 원천 보유 시작일 — 발생일시(`ocrn_dt`) 기준이며 민원건수 분석과 **같은 컬럼**이다
   * (🔁 2026-09-13 — 그전에는 신고일 `dclr_ymd` 였다).
   *
   * ⚠️ 그래도 민원건수와 값이 **완전히 같지는 않다** — 집계 조건이 다르다(민원건수는 미분류
   * 제외·좌표 무관 / 수급은 전 유형·좌표 유효 범위만).
   */
  dataBgngYmd: string | null
  dataEndYmd: string | null
  /** 격자 셀 목록 (민원>0 전량 · 사분면 → 민원건수 내림차순). 서버 정렬을 그대로 쓴다 */
  cells: LegacyLifezoneSupplyCell[]
  /**
   * 표에 실은 사분면(TSK-4227 D17) — **저장 스냅샷에만** 있다. `null`·생략 = 전체.
   * 🔁 TSK-4258 — 신규 생성 응답은 `grade` 로 바뀌어 이 키가 없다(NON_NULL).
   */
  qdrn?: LifezoneQuadrant | null
}

/** 수급 등급 범례 1행 — 부족률 등간격 5단계(TSK-4258 D13 · 🔁 D19). 백엔드 `LifezoneSupplyGradeCriterionVO` 와 같은 필드다. */
export interface LifezoneSupplyGradeCriterion {
  /** 등급코드 — 셀 `grade` 와 같은 값 */
  grade: LifezoneGrade
  /** 등급명 (심각/경고/주의/보통/낮음) */
  gradeNm: string
  /**
   * 부족률 하한(%, 포함) — 기본값 기준 80 · 60 · 40 · 20 · 0(설정 `SPLY_GRD_BNDRY1~4`). 위 등급의 하한이 곧 이 등급의 상한이다(상한 필드는 없다).
   * 🔁 D19 — 종전 `rnkRtUplmt`(순위 백분위 상한 10·25·50·75·100)를 대신한다.
   */
  minShrtRt: number
  /** 해당 등급 격자 수 */
  gridCnt: number
  /** 해당 등급 민원 합계 */
  cvlcptCnt: number
  /** 해당 등급 중 공영주차면 0 인 격자 수 */
  zeroSplyGridCnt: number
  /** 범례 문구(기본값 기준 `심각 (80% 이상)` · `경고 (60~80%)` … `낮음 (20% 미만)`). 🔴 서버가 완성해 내린다 — 그대로 출력한다 */
  lgndTxt: string
}

/** 수급 격자 셀 1칸 (TSK-4258 — 부족률 등간격 등급, D19). */
export interface LifezoneSupplyCell {
  /** 격자ID `{격자크기}m_{gx}_{gy}` — 선택 상태의 키다 */
  gridId: string
  /** 등급코드 — 🔴 서버 판정 그대로 쓴다(부족률 ≥ 설정 경계 `SPLY_GRD_BNDRY1~4` — D6·D19·D20). 프론트에서 다시 계산하지 않는다 */
  grade: LifezoneGrade
  /** 등급명 (심각/경고/주의/보통/낮음) */
  gradeNm: string
  /** 부족률 = 민원 ÷ (공영주차면 + 민원), 0~1 · 소수 4자리. 화면은 ×100 반올림해 `%` 로 보인다. 공급 0 이면 `1` */
  shortageRatio: number
  /** 수요지수 = 공영주차면 + 민원 — 시안 `수요 N대` 자리 */
  demandIndex: number
  /** 전체 순위 1..N (부족률 내림차순 — 응답은 이 순서로 온다). 🔁 D19 — 리스트 순서에만 쓰고 등급 판정에는 쓰지 않는다 */
  rnk: number
  sggNm: string | null
  /** 읍면동명 — 격자 안 민원의 **최빈** 읍면동이다(격자가 경계를 걸칠 수 있다) */
  emdNm: string | null
  /**
   * 구역명 — VWorld POI(없으면 역지오코딩 지번)에 읍면동 접두를 붙인 이름(TSK-4272).
   * 🔴 **목록 대상 등급(심각·경고·주의)에만** 오고, 못 얻으면 키가 생략된다 — 표시는 {@link supplyCellZoneNm} 한 곳에서 한다.
   */
  cntrPoiNm?: string | null
  emdCd: string | null
  cvlcptCnt: number
  /** 공영주차면수 — 시안 `공급 N면` 자리. 🔴 "부족 면수" 가 아니라 **공급량 자체**다. 0 이면 `0면` 그대로(D17) */
  pkltCpctySum: number
  pkltCnt: number
  /** 격자 안 민원의 고유 좌표 개수 */
  dstncPtCnt: number
  /** 소수 지점 집중 주의 여부 — 🔴 판정은 서버가 한다 */
  cncntWarnYn: 'Y' | 'N'
  ctrLat: number
  ctrLot: number
  /** 격자 꼭짓점 링 `[[위도, 경도], …]` 4점 — 🔴 서버가 EPSG:5179 → 4326 변환해 내린다 */
  ring: [number, number][]
}

/** 수급 분석 응답 전문 (TSK-4258). 조회 조건을 되비추고 화면 문구까지 함께 내린다. */
export interface LifezoneSupplyResult {
  /**
   * 분석 방법 — 🔴 조회·생성 응답·신규 스냅샷 모두 항상 `'SHRT_RT'`(D8). 보고서 템플릿이 이 값으로 신·구를 가른다
   * (`lifezoneSupplyReportTemplate.ts` — 구 스냅샷만 값이 없다).
   */
  anlsMthd: 'SHRT_RT'
  bgngYmd: string
  endYmd: string
  sggNm: string | null
  emdCd: string | null
  anlsTrgtCd: LifezoneTargetCd
  anlsTrgtNm: string
  gridSz: LifezoneSupplyGridSz
  gridSzNm: string
  /** 격자 좌표계 (`EPSG:5179`) */
  crs: string
  /** `주차 수급 부족 구역` */
  sectionTitle: string
  /** `격자 기반 수요·공급 격차 (심각, 경고, 주의)` — 목록 부제로 그대로 쓴다 */
  sectionSub: string
  /** 🔴 화면에 **상시** 표기해야 하는 안내 문구 (인계서 §4-4) */
  disclaimer: string
  /** 격자 해석 주의 안내 — **100m 에서만** 온다(그 밖은 키 생략 · D17). 문구는 서버 것 그대로 */
  gridNotice?: string
  severeCnt: number
  warnCnt: number
  cautionCnt: number
  normalCnt: number
  lowCnt: number
  /** 공영주차면 0 인 격자 수 (D17) */
  zeroSplyGridCnt: number
  /** 평가 대상 최소 기준 — 월평균 민원 N건 이상 격자만 평가한다(D16) */
  minMnthAvgCvlcpt: number
  /** 등급 범례 5행 (0건 등급도 남는다 — 색↔의미 대응이 조회마다 바뀌면 안 된다) */
  gradeCriteria: LifezoneSupplyGradeCriterion[]
  /** 총 격자 수 (최소 기준 통과 격자) */
  totGridCnt: number
  /** 총 민원 건수 (최소 기준 통과 격자의 합) */
  totCvlcptCnt: number
  /** 총 공영주차면 수 (최소 기준 통과 격자의 합) */
  totPkltCpctySum: number
  /** 원천 보유 시작일 — 발생일시(`ocrn_dt`) 기준 */
  dataBgngYmd: string | null
  dataEndYmd: string | null
  /** 격자 셀 목록 (최소 기준 통과 전량 · `rnk` 오름차순). 서버 정렬을 그대로 쓴다 */
  cells: LifezoneSupplyCell[]
  /**
   * 🆕 수급 5행의 적용 내역(TSK-4325 D10). 🔴 구 스냅샷은 **키 생략**(`NON_NULL`)이라 `undefined` 다.
   * 문서에는 그리지 않는다. 화면 배너는 이 값이 아니라 계수 훅 상태로 그린다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
}

/* ── 4. 조회 함수 ─────────────────────────────────────── */

/**
 * 수급 격자 분석 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지).
 *
 * 🔴 `sggNm`·`emdCd` 는 **값이 있을 때만** 쿼리에 얹는다 — `''`(전체)를 그대로 보내면 400 이다.
 * `gridSz` 도 값이 있을 때만 얹고, 없으면 서버 기본값을 따른다(기본값을 프론트에 복제하지 않는다).
 *
 * 오류: bgngYmd/endYmd 형식 오류·역전·366일 초과 · 빈 sggNm/emdCd · 미등록 시군구 ·
 * 미존재 읍면동 · 미지원 격자크기(예: 1000m) → 400.
 */
export async function fetchLifezoneSupply(
  params: LifezoneSupplyParams,
): Promise<LifezoneSupplyResult> {
  const query: Record<string, string | number> = {
    bgngYmd: params.bgngYmd,
    endYmd: params.endYmd,
  }
  if (params.gridSz) query.gridSz = params.gridSz
  if (params.sggNm) query.sggNm = params.sggNm
  if (params.emdCd) query.emdCd = params.emdCd
  // GET 은 JSON 문자열 1개(TSK-4325 D1). 비면 키를 뺀다.
  const vrblVls = vrblVlsQuery(params.vrblVls)
  if (vrblVls) query.vrblVls = vrblVls

  const { data } = await api.get<LifezoneSupplyResult>('/analysis/lifezone/supply', {
    params: query,
    timeout: LIFEZONE_ANALYSIS_TIMEOUT_MS,
  })
  return data
}

/* ── 5. 표시 헬퍼 ─────────────────────────────────────── */

/**
 * 면당 민원 표시 문자열.
 *
 * 🔴 `null`(주차면 0)을 `0` 이나 `∞` 로 바꾸지 않는다 — 계산할 수 없다는 뜻이므로 `-` 로 둔다.
 * `0` 으로 적으면 "민원이 없다", `∞` 로 적으면 "무한히 부족하다" 로 읽혀 둘 다 사실이 아니다.
 */
export function formatPerSlot(cvlcptPerSlot: number | null | undefined): string {
  // 🔁 TSK-4258 — `== null`: 구 스냅샷의 null 은 NON_NULL 로 생략돼 `undefined` 로 온다
  if (cvlcptPerSlot == null) return '-'
  return cvlcptPerSlot.toLocaleString(undefined, { maximumFractionDigits: 2 })
}

/**
 * 부족률(0~1) → 표시 퍼센트 정수(`82`). 화면 목록·지도 팝업이 같은 함수를 쓴다(TSK-4258 D12 — 시안 `82%`).
 * 🔴 반올림만 한다 — 등급 판정에 쓰지 않는다(등급은 서버 `grade`).
 */
export function formatShortagePct(shortageRatio: number): number {
  return Math.round(shortageRatio * 100)
}

/**
 * 수급 셀 표시 지역명 — POI 구역명 → 읍면동명 → 격자ID 순 폴백(TSK-4272).
 * 결과 목록·지도 팝업·보고서 표가 같은 함수를 쓴다(🔴 규칙을 복제하면 화면과 보고서의 이름이 갈린다).
 * POI 가 없는 셀(보통·낮음 · 외부 조회 상한 초과 · 구 스냅샷)은 종전처럼 읍면동명이다.
 */
export function supplyCellZoneNm(
  cell: Pick<LifezoneSupplyCell, 'cntrPoiNm' | 'emdNm' | 'gridId'>,
): string {
  return cell.cntrPoiNm || cell.emdNm || cell.gridId
}

/* ── 6. 보고서 저장 (POST) ────────────────────────────── */

/**
 * 수급 보고서 생성 요청.
 *
 * 🔴 **조회 조건만 보낸다 — 화면이 들고 있던 숫자는 보내지 않는다.** 서버가 같은 조건으로
 * **다시 분석해** 그 결과를 저장한다. 화면이 보낸 결과를 저장하면 화면 조작이 곧 보고서 위조다.
 */
export interface LifezoneSupplyReportCreateParams {
  bgngYmd: string
  endYmd: string
  gridSz?: LifezoneSupplyGridSz
  sggNm?: string | null
  emdCd?: string | null
  /**
   * 보고서 표에 실을 등급(🔁 TSK-4258 — 종전 `qdrn`). `null`·생략 = 전체.
   * 화면은 칩이 없어 보내지 않는다(계약만 남겨 둔다 — TSK-4227 D19).
   * 🔴 셀은 그대로 전량 저장된다 — 이 값은 표의 행만 거른다. `SEVERE`·`WARN`·`CAUTION` 밖은 400.
   */
  grade?: LifezoneGrade | null
  /** 🔴 서버는 파일을 굽지 않는다 — 문서는 브라우저가 만들고, 이 값은 "무엇으로 내보냈는지" 기록이다 */
  fileFmt: LifezoneFileFmt
  /** 🆕 수급 계수 일시 조정(TSK-4325) — 🔴 결과를 만든 실행의 값을 보낸다(D7). */
  vrblVls?: VrblVls
}

/**
 * 저장 응답 = 분석 응답 + 보고서 헤더 7칸.
 *
 * ⚠️ `dataBgngYmd`·`dataEndYmd` 는 **비어 온다**(스냅샷이 아니라 "지금" 의 값이라 굳히지 않는다).
 */
export interface LifezoneSupplyReportResponse extends LifezoneSupplyResult {
  rptpId: number
  excnId: number
  rptpNm: string
  regionNm: string
  anlsPrdExpln: string
  fileFmt: LifezoneFileFmt
  crtYmd: string
  /**
   * 표에 실은 등급(🔁 TSK-4258 — 종전 `qdrn`). 🔴 `null` = 전체 — 백엔드가 NON_NULL 이 아니라 보통 `null` 로 온다.
   * 구 `qdrn` 은 신규 응답에 없다(구 스냅샷 쪽 {@link LegacyLifezoneSupplyResult}).
   */
  grade?: LifezoneGrade | null
  /** 🆕 엑셀 재계산 데이터(TSK-4340) — 형식과 무관하게 새 보고서 전부에 실린다. 구 스냅샷은 `null`/없음. */
  wbk?: LifezoneSupplyWorkbook | null
}

/** 수급 엑셀 후보 격자 1행 (`wbk.cells[]` — 백엔드 `LifezoneSupplyWorkbookCellVO`). 🔴 격자 키는 `gridId`(혼잡도는 `gridCd`). */
export interface LifezoneSupplyWorkbookCell {
  gridId: string
  /** 구역명 — 없으면 키 생략. */
  cntrPoiNm?: string | null
  emdNm?: string | null
  cvlcptCnt: number
  pkltCpctySum: number
  pkltCnt: number
}

/**
 * 수급 보고서의 엑셀 재계산 데이터 (`rslt_json.wbk` — 백엔드 `LifezoneSupplyWorkbookVO`, TSK-4340 D5·D7).
 * `cells` 는 N = `SPLY_MIN_MNTH_AVG_CVLCPT.min_vl` 기준 후보 전부이며 **서버 순위 키 순**이다.
 */
export interface LifezoneSupplyWorkbook {
  /** 조회 일수(양끝 포함 — SQL `#{prdDays}` 와 같은 값). */
  prdDays: number
  vrbls: LifezoneWorkbookVrbl[]
  cells: LifezoneSupplyWorkbookCell[]
}

/**
 * 수급 보고서 저장 — **201**.
 *
 * 오류: 분석 결과 0건 · `fileFmt` 가 `PDF`/`DOCX` 밖 · 조회 조건 오류 → **400**.
 * 🔴 0건이 400 인 것은 조회(`fetchLifezoneSupply`, 200 + 빈 배열)와 **다르다** — 머리글만 있는
 * 빈 문서가 목록에 남으면 "분석했더니 결과가 있다" 로 읽히기 때문이다.
 */
export async function createLifezoneSupplyReport(
  params: LifezoneSupplyReportCreateParams,
): Promise<LifezoneSupplyReportResponse> {
  const body: Record<string, string | number | VrblVls> = {
    bgngYmd: params.bgngYmd,
    endYmd: params.endYmd,
    fileFmt: params.fileFmt,
  }
  if (params.gridSz) body.gridSz = params.gridSz
  if (params.sggNm) body.sggNm = params.sggNm
  if (params.emdCd) body.emdCd = params.emdCd
  if (params.grade) body.grade = params.grade
  // POST 는 바디 객체(D1). 비면 키를 뺀다.
  const vrblVls = vrblVlsBody(params.vrblVls)
  if (vrblVls) body.vrblVls = vrblVls

  const { data } = await api.post<LifezoneSupplyReportResponse>(
    '/analysis/lifezone/supply/reports',
    body,
    { timeout: LIFEZONE_ANALYSIS_TIMEOUT_MS },
  )
  return data
}
