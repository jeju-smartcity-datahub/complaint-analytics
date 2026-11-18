import { api } from './client'
import type { PageResponse } from './reports'
import { vrblVlsBody, type AnlsVrblAjmt, type VrblVls } from './analysisWeight'

/*
 * 정책 효과 시뮬레이션(주차장 확충 · 이면도로 정비 · 요금제 적용) 실 API 클라이언트.
 * 백엔드 계약(`jeju.datahub.policysim.web.PolicySimController`):
 *   GET  /api/analysis/policy-sim         → PolicySimResult        (계산만 한다 — 저장하지 않는다)
 *   POST /api/analysis/policy-sim/results → PolicySimResult (201 저장 · 200 NO_ENF·NO_SPLY 미저장)  (서버가 같은 조건으로 계산해 저장)
 *   GET  /api/analysis/policy-sim/results?page&size → PageResponse<SavedPolicySim> (**내** 저장분, 최신순)
 *   DELETE /api/analysis/policy-sim/results/{rsltId} → 204 (없거나 남의 것 → 404)
 *
 * 응답은 기준선(C0·E0·H0·S)과 **적용 계수·기준 구간(`aplcnParam`)을 함께 되비춘다** — 응답 하나로
 * 결과를 재현할 수 있게 하려는 설계다. 숫자 필드는 백엔드 `BigDecimal` 이지만 JS 에서는 전부 `number` 다.
 */

/* ── 1. 조회 파라미터 ──────────────────────────────────── */

/**
 * 정책유형코드(공통코드 `PLCY_TYPE_CD`).
 * 셋 다 서버가 계산한다.
 */
export type PlcyTypeCd = 'EXPAND' | 'REPAIR' | 'FEE'

/** 확충 방식 — 단가 계수 `CST_VACLOT`(공한지) · `CST_MULTFLR`(복층화) · `CST_PBLPKLT`(신규). */
export type ExpandMethod = 'VACLOT' | 'MULTFLR' | 'NEW'

/** 주차면 증설 수 — 서버 허용 목록과 같다(목록 밖이면 400). */
export const ADD_PKLT_SLOTS = [50, 80, 100, 150, 200] as const
export type AddPkltSlot = (typeof ADD_PKLT_SLOTS)[number]

/** 도로 정비 유형 — `RSDNC`(주거지역형 · `N100_RSDNC`) · `CMRC`(상업지역형 · `N100_CMRC`). */
export type RoadMtncType = 'RSDNC' | 'CMRC'

/** 정비 구간 연장(m) 허용 범위 — 서버 검증과 같다(정수 1~10000, 밖이면 400). */
export const ROAD_MTNC_LEN_MIN = 1
export const ROAD_MTNC_LEN_MAX = 10000

/** 요금 적용 방식 — `RAISE`(요금 인상 · 유료 주차장) · `CONVERT`(무료 → 유료 전환 · 무료 주차장). */
export type FeeAplcnMthd = 'RAISE' | 'CONVERT'

/** 적용 급지 — `ALL` 전체 · `Z1` 1급지(동) · `Z2` 2급지(읍·면). `emdCd` 를 보내면 서버가 무시하고 채운다. */
export type FeeAplcnZone = 'ALL' | 'Z1' | 'Z2'

/** 요금 인상률(%) — 서버 허용 목록과 같다(목록 밖이면 400). `RAISE` 전용. */
export const FEE_INCRS_RTS = [10, 20, 30] as const
export type FeeIncrsRt = (typeof FEE_INCRS_RTS)[number]

/** 예측 기간(개월) — 서버 허용 목록과 같다(목록 밖이면 400). 공한지 반환분 계산에만 쓰인다. */
export const PREDC_MMS = [3, 6, 12, 24] as const
export type PredcMm = (typeof PREDC_MMS)[number]

/**
 * GET /api/analysis/policy-sim 조회 파라미터.
 *
 * 🔴 **`전체` 를 고른 지역 필터는 키를 아예 넣지 않는다**(`sggNm`·`emdCd`) — 빈 문자열은 **400** 이다.
 * 화면 상태는 `전체` 를 `''` 로 두면 되고, 빈 값 제거는 아래 `fetchPolicySim` 이 책임진다.
 *
 * 유형별 필수 — `EXPAND`: `expandMethod`·`addPkltSlot` · `REPAIR`: `roadMtncType`·`roadMtncLen` ·
 * `FEE`: `feeAplcnMthd`(+ `RAISE` 면 `feeIncrsRt`) · `feeAplcnZone` 은 `emdCd` 가 없을 때만 싣는다(생략 = `ALL`).
 * 다른 유형의 칸은 null 로 두며 `toQuery` 가 **현재 유형의 키만** 싣는다(`predcMm` 은 공통 필수).
 */
export interface PolicySimParams {
  plcyType: PlcyTypeCd
  expandMethod: ExpandMethod | null
  addPkltSlot: AddPkltSlot | null
  /** 도로 정비 유형 — REPAIR 전용. */
  roadMtncType: RoadMtncType | null
  /** 정비 구간 연장(정수 m, 1~10000) — REPAIR 전용. */
  roadMtncLen: number | null
  /** 요금 적용 방식 — FEE 전용. 옛 행·다른 유형은 null/생략. */
  feeAplcnMthd?: FeeAplcnMthd | null
  /** 적용 급지 — FEE 전용. 🔴 `emdCd` 가 있으면 보내지 않는다(서버가 그 읍면동 급지로 채운다). */
  feeAplcnZone?: FeeAplcnZone | null
  /** 요금 인상률(%) — FEE·RAISE 전용. */
  feeIncrsRt?: FeeIncrsRt | null
  predcMm: PredcMm
  /** 시군구명(`제주시`·`서귀포시`). `''`·생략 = 제주 전체. */
  sggNm?: string
  /** 읍면동코드 8자리. `''`·생략 = 시 전체. 오면 서버가 `sggNm` 을 `TB_ADMDST` 값으로 채운다. */
  emdCd?: string
}

/* ── 2. 응답 타입 ─────────────────────────────────────── */

/** 산출상태(공통코드 `CALC_STTS_CD`, **조회 범위 단위**). `null` = 정상. */
export type CalcSttsCd = 'NO_ENF' | 'NO_SPLY'

/** 비용수준(공통코드 `CST_LVL_CD`). `TBD` = 미정 — 이면도로는 1m당 사업비 단가가 없어 항상 TBD 다. */
export type CstLvlCd = 'LOW' | 'MID' | 'HIGH' | 'TBD'

/** 산출 시점 적용 계수 13종 + 기준 구간. 키 이름은 서버 `PolicySimAplcnParam` 과 같다. */
export interface PolicySimAplcnParam {
  occ: number
  occTh: number
  alpha: number
  m: number
  wFixed: number
  rRet: number
  /** 시군구별 좌표 결측 보정 — 키는 시군구명(`제주시`·`서귀포시`). */
  gSgg: Record<string, number>
  cVacant: number
  cPublic: number
  cMultflr: number
  cstLowBndry: number
  cstHighBndry: number
  /** 민원 기준 구간 `YYYYMMDD~YYYYMMDD`(양끝 포함). 민원 0건이면 null. */
  cvlcptPrd: string | null
  /** 단속 기준 구간 `YYYYMMDD~YYYYMMDD`(양끝 포함). */
  crdnPrd: string | null
  /*
   * 이면도로 4계수 — 확충 응답에서는 null. 옛 저장분 JSON 에는 키가 없을 수 있어 optional 이다.
   */
  /** 주거지역형 100m당 확보 구획 `N100_RSDNC`. */
  n100Rsdnc?: number | null
  /** 상업지역형 100m당 확보 구획 `N100_CMRC`. */
  n100Cmrc?: number | null
  /** 이면도로 흡수 계수 `ALPHA_B_VL`. */
  alphaB?: number | null
  /** 풍선효과 비율 s `BALLOON_RT`(0~1). */
  balloonRt?: number | null
  /*
   * 요금제 4계수 — 다른 정책 응답에서는 null. 옛 저장분 JSON 에는 키가 없을 수 있어 optional 이다.
   */
  /** 가격탄력성 ε `PRC_ELST_VL`(음수). */
  eps?: number | null
  /** 유료화 수요 감소율 λ `PAID_DMND_RT`(0~1). */
  lambda?: number | null
  /** 전이율 τ `TRNSF_RT`(0~1). */
  tau?: number | null
  /** 전이 중 대상 지역 몫 τ_local `TRNSF_LOCAL_RT`(0~1). */
  tauLocal?: number | null
}

/**
 * 민원 감소율 민감도 — 계수 하나를 설정 범위 안에서 흔든 R(%)의 `[min, max]`.
 * `CONVERT` → `lambda`·`tau` · `RAISE` → `eps`·`tau`. 범위가 없는 계수는 그 키만 null. 현재 R 을 항상 포함한다.
 */
export type CvlcptRdctRtRng = Partial<Record<'lambda' | 'eps' | 'tau', [number, number] | null>>

/**
 * GET /api/analysis/policy-sim 응답. 값이 `null` 이어도 **키는 항상 있다**.
 *
 * 🔴 `NO_ENF`·`NO_SPLY`·흡수 포화(`excsYn`) 판정은 **`calcSttsCd`·`excsYn` 으로 한다** —
 * null 패턴으로 역추론하지 않는다.
 * 🔴 혼잡 지수는 **주차 혼잡**((정상 이용 + 불법주차) ÷ 공영주차면수)이며 도로 통행량이 아니다.
 */
export interface PolicySimResult {
  /** 조회 범위 라벨 — `{시} {읍면동}` · `{시} 전체` · `전체 지역`. */
  anlsRngCn: string
  /** 기준 월평균 민원 C0 합(건/월). */
  crtrCvlcptCnt: number | null
  /** 기준 불법주차 추정 E0 합(대). */
  crtrIlglCnt: number | null
  /** 기준 주차 혼잡 지수 H0. NO_SPLY 면 null. */
  crtrCngstIdx: number | null
  /** 기존 공영주차면수 S 합. */
  exsPkltCpcty: number | null
  /** 유효 증설 면수 ΔS_eff — 공한지만 예측 기간에 따라 줄어든다. REPAIR 는 환산 확보 면수 ΔS(= 연장 ÷ 100 × n_100). */
  vldAddPkltSlot: number | null
  /** 민원 감소율 R (**%**, 음수 = 감소). NO_ENF 면 null. */
  cvlcptRdctRt: number | null
  /** 주차 혼잡 개선율 I (**%**, 양수 = 개선). NO_ENF·NO_SPLY 면 null. */
  cngstImpvRt: number | null
  /** 확충 후 주차 혼잡 지수 H1. NO_ENF·NO_SPLY 면 null. */
  aftrCngstIdx: number | null
  /** 확충 후 월평균 민원 C1 합. NO_ENF 면 null. 🔴 REPAIR 는 **인접 증가분을 포함**한 순효과다. */
  aftrCvlcptCnt: number | null
  calcSttsCd: CalcSttsCd | null
  /** 흡수 포화(SATURATED, DEC-025) — `calcSttsCd` 와 독립이다. */
  excsYn: boolean
  /** 예상 비용(원) = N × 방식별 단가. */
  cstAmt: number | null
  cstLvlCd: CstLvlCd | null
  /** 도로 정비 유형(REPAIR 요청값). 확충이면 null. */
  roadMtncType: RoadMtncType | null
  /** 정비 구간 연장 m(REPAIR 요청값). 확충이면 null. */
  roadMtncLen: number | null
  /**
   * 인접 지역 월 민원 증가분 Σ(C1_n − C0_n)(풍선효과, REPAIR). R·C1 에 **이미 반영된 내역**이다.
   * 인접이 없거나 전부 E0=0 이면 0(풍선효과분을 대상에 가산). 확충이면 null.
   */
  adjcCvlcptCnt: number | null
  /** 풍선효과를 배분한 인접 읍면동 수(REPAIR). **응답 전용** — 저장 목록에서는 null. 확충이면 null. */
  adjcEmdCnt: number | null
  /*
   * 요금제 — 다른 정책에서는 null. 내 저장 목록도 저장값을 그대로 싣는다(민감도·경고만 응답 전용이라
   * 저장분에서는 null).
   */
  /** 요금 적용 방식(요청값). */
  feeAplcnMthd: FeeAplcnMthd | null
  /** 적용 급지 — `emdCd` 지정 시 **서버가 채운 값**. */
  feeAplcnZone: FeeAplcnZone | null
  /** 요금 인상률(%) — `CONVERT` 면 null. */
  feeIncrsRt: number | null
  /** 적용 대상 주차면수(대상 지역 ∩ 급지 ∩ 유무료). 단속 0건 읍면동 포함. */
  aplcnTrgtSlot: number | null
  /** 이탈 차량 수 ΣV(대) — 🔴 단속 0건(NO_ENF) 읍면동 제외. */
  lvngVhclCnt: number | null
  /** 빈자리로 흡수된 불법주차 수(대). */
  absrpCnt: number | null
  /** 요금 회피 전이 불법주차 수(대) = τ × 이탈(대상 내 + 인접). */
  trnsfCnt: number | null
  /** 민감도 범위 — 범위 NO_ENF 면 null. **응답 전용** — 저장 목록에서는 null. */
  cvlcptRdctRtRng: CvlcptRdctRtRng | null
  /** 경고 문구 — **응답 전용**(저장 안 함, 저장 목록에서는 null). */
  wrnngCn: string | null
  aplcnParam: PolicySimAplcnParam
  /**
   * 공통 + 그 정책 유형 계수의 적용 내역. 구 저장분은 `null`.
   * 시나리오 비교 표의 `(계수조정)` 표식 · 보고서 1절 표식과 2절 `분석 계수 조정 내역`이 읽는다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
}

/* ── 3. 호출 ──────────────────────────────────────────── */

/** 빈 지역 필터(`''`)를 키째 뺀다 — 빈 문자열은 서버가 400 으로 거절한다. */
function toQuery(params: PolicySimParams): Record<string, string | number> {
  const q: Record<string, string | number> = {
    plcyType: params.plcyType,
    predcMm: params.predcMm,
  }
  // 현재 유형의 키만 싣는다 — 서버는 다른 유형의 키를 무시하지만 null 이 "null" 문자열로 새지 않게 한다.
  if (params.plcyType === 'EXPAND') {
    if (params.expandMethod) q.expandMethod = params.expandMethod
    if (params.addPkltSlot != null) q.addPkltSlot = params.addPkltSlot
  } else if (params.plcyType === 'REPAIR') {
    if (params.roadMtncType) q.roadMtncType = params.roadMtncType
    if (params.roadMtncLen != null) q.roadMtncLen = params.roadMtncLen
  } else if (params.plcyType === 'FEE') {
    if (params.feeAplcnMthd) q.feeAplcnMthd = params.feeAplcnMthd
    // 🔴 읍면동을 지정하면 급지를 보내지 않는다 — 서버가 그 읍면동의 급지로 채운다.
    if (params.feeAplcnZone && !params.emdCd) q.feeAplcnZone = params.feeAplcnZone
    if (params.feeAplcnMthd === 'RAISE' && params.feeIncrsRt != null)
      q.feeIncrsRt = params.feeIncrsRt
  }
  if (params.sggNm) q.sggNm = params.sggNm
  if (params.emdCd) q.emdCd = params.emdCd
  return q
}

/** 정책 효과 계산(저장하지 않는다). 계수 행이 없으면 503 `POLICY_SIM_PARAM_MISSING`. */
export async function fetchPolicySim(params: PolicySimParams): Promise<PolicySimResult> {
  const { data } = await api.get<PolicySimResult>('/analysis/policy-sim', {
    params: toQuery(params),
  })
  return data
}

/**
 * 정책 효과 계산 + 저장 — 설정 패널의 [시뮬레이션 실행].
 * 서버가 **같은 조건으로 계산**해 `TB_ANLS_EXCN` + `TB_PLCY_SMLT_RSLT` 를 한 트랜잭션에 남긴다
 * — `201 저장 · 200 NO_ENF·NO_SPLY 미저장`. 어느 쪽이든 본문은 계산 결과 그대로다.
 *
 * 🔴 공용 `api` 인스턴스여야 한다 — POST 라 `X-XSRF-TOKEN` 이 필요하고(없으면 403), 그 헤더는 공용
 * 인스턴스의 `xsrfCookieName`·`xsrfHeaderName` 설정이 싣는다. 바디는 GET 과 같은 키이며 빈 지역 필터는 여기서도 키째 뺀다.
 *
 * 분석 계수 일시 조정 — 두 번째 인자는 계수 훅의 `appliedRequest(scope).vrblVls`(공통 + 현재 유형 변경분)다.
 * 비면 `vrblVlsBody` 가 `undefined` 를 돌려 바디에서 키가 빠진다. 🔴 `toQuery`·`PolicySimParams` 에 넣지 않는다 —
 * `toQuery` 는 GET `fetchPolicySim` 쿼리도 만들고, GET 은 계수를 받지 않는다.
 */
export async function savePolicySim(
  params: PolicySimParams,
  vrblVls?: VrblVls | null,
): Promise<PolicySimResult> {
  const { data } = await api.post<PolicySimResult>('/analysis/policy-sim/results', {
    ...toQuery(params),
    vrblVls: vrblVlsBody(vrblVls),
  })
  return data
}

/* ── 4. 내 저장분 ───────────────────────────── */

/**
 * 저장 목록의 한 행 = 계산 응답 필드 전부 + 결과아이디·저장 시각 + 저장 요청 조건.
 * 값은 **저장 당시 그대로**다(서버가 재계산하지 않는다) — 화면도 재계산하지 않고 그대로 그린다.
 *
 * ⚠️ `aplcnParam` 은 저장된 JSON 을 읽지 못하면 null 일 수 있다(그때 `cstAmt` 도 null).
 * REPAIR 행은 `roadMtncType`·`roadMtncLen`·`adjcCvlcptCnt` 가 저장값이고 `adjcEmdCnt`·`cstAmt` 는 null,
 * `expandMethod`·`addPkltSlot` 도 null 이다.
 * FEE 행은 `feeAplcnMthd`·`feeAplcnZone`·`feeIncrsRt`(CONVERT 면 null)·`aplcnTrgtSlot`·`lvngVhclCnt`·`absrpCnt`·
 * `trnsfCnt`·`adjcCvlcptCnt` 가 저장값이고, 응답 전용인 `cvlcptRdctRtRng`·`wrnngCn` 은 null 이다.
 */
export interface SavedPolicySim extends Omit<PolicySimResult, 'aplcnParam'> {
  aplcnParam: PolicySimAplcnParam | null
  /** 결과아이디 — `DELETE` 경로 변수. */
  rsltId: number
  /** 저장 일시(ISO-8601 로컬 시각, 소수 초 포함 가능). */
  excnDt: string
  plcyType: PlcyTypeCd
  /** 확충 조건 — REPAIR 행이면 null. */
  expandMethod: ExpandMethod | null
  addPkltSlot: AddPkltSlot | null
  /** 차트 가로축 — 저장 당시 예측 기간. */
  predcMm: PredcMm
  /** 저장 요청 지역 그대로(생략했으면 null). 표시 라벨은 `anlsRngCn`. */
  sggNm: string | null
  emdCd: string | null
}

/**
 * **내** 저장 목록(최신순 페이지). 사용자 ID 는 보내지 않는다 — 서버가 세션 사용자로 거른다.
 * `page` 는 0-base(`PageResponse.page` 와 같다).
 */
export async function fetchSavedPolicySims(
  page: number,
  size: number,
): Promise<PageResponse<SavedPolicySim>> {
  const { data } = await api.get<PageResponse<SavedPolicySim>>('/analysis/policy-sim/results', {
    params: { page, size },
  })
  return data
}

/**
 * 내 저장분 삭제(204). 없거나 남의 것이면 **404**(둘을 구분하지 않는다).
 * 🔴 공용 `api` 인스턴스여야 한다 — DELETE 도 `X-XSRF-TOKEN` 이 없으면 403 이다.
 */
export async function deletePolicySim(rsltId: number): Promise<void> {
  await api.delete(`/analysis/policy-sim/results/${rsltId}`)
}

/* ── 5. 시나리오 비교 보고서 ──────────── */

/** 보고서 파일형식. 🔴 대문자다 — 그 밖의 값은 400. */
export type PolicySimReportFileFmt = 'PDF' | 'DOCX'

/**
 * 보고서에 담을 시나리오 한 건 — **저장분 `rsltId` 만** 보낸다(화면의 숫자를 보내지 않는다 — 저장값 그대로).
 * [시뮬레이션 실행]이 곧 저장이라 화면의 모든 행에 `rsltId` 가 있다. 서버의 조건 재계산 경로는
 * 계약에 남아 있지만 화면은 쓰지 않는다.
 */
export type PolicySimReportScenarioReq = { rsltId: number }

/** POST /api/analysis/policy-sim/reports 요청(백엔드 `PolicySimReportCreateRequest`). */
export interface PolicySimReportCreateRequest {
  fileFmt: PolicySimReportFileFmt
  /** 대표 시나리오 — `scenarios` 의 0-base 인덱스. 프론트는 현재 항상 `0`. */
  headIdx: number
  /** 1~15건 — 화면에 보이던 순서 그대로(추천 마지막 동률 기준이 이 순서다). */
  scenarios: PolicySimReportScenarioReq[]
}

/**
 * 보고서 시나리오의 요청 조건 11키(백엔드 `Map<String,Object>` — 저장 경로 `anlsParam` 과 같은 키).
 * 유형이 쓰지 않는 칸과 생략한 지역은 **null** 이다(화면 상태의 `''` 와 다르다).
 */
export interface PolicySimReportParams {
  plcyType: PlcyTypeCd
  expandMethod: ExpandMethod | null
  addPkltSlot: AddPkltSlot | null
  roadMtncType: RoadMtncType | null
  roadMtncLen: number | null
  feeAplcnMthd: FeeAplcnMthd | null
  feeAplcnZone: FeeAplcnZone | null
  feeIncrsRt: FeeIncrsRt | null
  predcMm: PredcMm
  sggNm: string | null
  emdCd: string | null
}

/** 보고서 시나리오 한 건(백엔드 `PolicySimReportScenarioVO`). */
export interface PolicySimReportScenario {
  /** 순번(1-base, 요청 순서) — 1절 순번 · 2·3절 막대 순서. */
  seq: number
  /** 추천 — 이 보고서에 담긴 시나리오 범위에서 서버가 판정해 굳힌 값. 최대 1건, 없을 수 있다. */
  rcmdYn: boolean
  /** 저장분이면 결과아이디, 미저장분(재계산)이면 null. */
  rsltId: number | null
  params: PolicySimReportParams
  /**
   * 결과(`PolicySimResultVO` 칸) — 저장분은 저장값 그대로라 응답 전용 칸(`cvlcptRdctRtRng`·`wrnngCn`·
   * `adjcEmdCnt`)이 null 이고, `aplcnParam` 도 저장 JSON 을 읽지 못하면 null 일 수 있다.
   */
  result: Omit<PolicySimResult, 'aplcnParam'> & { aplcnParam: PolicySimAplcnParam | null }
}

/**
 * 저장된 보고서 한 건 = 메타 6칸 + 저장 시점 내용(백엔드 `PolicySimReportVO`).
 * 저장(201)과 스냅샷 조회(200)가 **같은 모양·같은 값**이라 문서 템플릿이 두 경로를 구분하지 않는다.
 */
export interface PolicySimReport {
  rptpId: number
  excnId: number
  /** 보고서명 `20261014(1)_정책 시뮬레이션 시나리오 비교` — 🔴 내려받는 파일 이름도 이 값이다. */
  rptpNm: string
  /** 분석기간설명 `예측 기간 3개월`(대표 시나리오 기준). */
  anlsPrdExpln: string
  fileFmt: PolicySimReportFileFmt
  /** 생성일시(ISO-8601 로컬 시각). */
  crtYmd: string
  /** 대표 시나리오 순번(1-base) — `seq === headSeq` 로 찾는다. */
  headSeq: number
  /** 요청 순서 그대로(seq 오름차순). */
  scenarios: PolicySimReportScenario[]
}

/** 보고서 생성 타임아웃(ms) — 시나리오 최대 15건을 한 트랜잭션에 담아 공용 10초를 넘을 수 있다. */
const REPORT_TIMEOUT_MS = 60_000

/**
 * 시나리오 비교 보고서 저장(201) — `TB_ANLS_EXCN` + `TB_ANLS_RSLT` + `TB_RPTP` 를 한 트랜잭션에 남기고
 * 그 스냅샷을 돌려준다. 문서는 **이 응답으로** 만든다.
 *
 * 🔴 **공용 `api` 인스턴스**로 보낸다 — `X-XSRF-TOKEN` 이 자동으로 실린다(새 인스턴스면 403).
 * 🔴 **이 호출에만** 타임아웃을 60초로 올린다 — 전역 값은 올리지 않는다.
 *
 * 오류: 400(요청 검증 실패) / 401 / 403(CSRF) / 404(`rsltId` 가 없거나 남의 것) /
 * 503(`POLICY_SIM_PARAM_MISSING`).
 */
export async function createPolicySimReport(
  req: PolicySimReportCreateRequest,
): Promise<PolicySimReport> {
  const body = {
    fileFmt: req.fileFmt,
    headIdx: req.headIdx,
    scenarios: req.scenarios.map((s) => ({ rsltId: s.rsltId })),
  }
  const { data } = await api.post<PolicySimReport>('/analysis/policy-sim/reports', body, {
    timeout: REPORT_TIMEOUT_MS,
  })
  return data
}

/**
 * 시나리오 비교 보고서 **스냅샷** 조회 — 재계산 없이 저장된 내용을 그대로 읽는다.
 * 404: 내 것이 아님 · 보고서 없음 · POLICY_SIM 실행이 아님 · 결과 행 없음. 호출부는 넷을 구분하지 않는다.
 */
export async function fetchPolicySimReportSnapshot(rptpId: number): Promise<PolicySimReport> {
  const { data } = await api.get<PolicySimReport>(`/reports/${rptpId}/policy-sim`)
  return data
}
