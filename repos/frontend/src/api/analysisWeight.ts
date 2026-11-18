import { api } from './client'

/*
 * 설정 > 분석 지표 가중치 설정 실 API 클라이언트 (변수 모델).
 * 백엔드 계약(로컬 실측 · `jeju.datahub.analysis.web.AnalysisWeightController` 기준):
 *   GET /api/analysis-weights                → AnalysisTypeWeight[]  (실측 길이 4 · 변수 합 13)
 *   PUT /api/analysis-weights/{anlsTypeCd}   → AnalysisTypeWeight    (갱신된 1건)
 *   GET /api/analysis-weights/histories?size&anlsTypeCd → AnalysisWeightHistory[]  (유형 필터는 선택)
 *
 * 목록 순서는 **서버 정렬**(분석유형 sort_seq · 변수 sort_seq)이다. 프론트에서 재정렬하지 않는다.
 *
 * ⚠️ 숫자 필드는 백엔드가 `BigDecimal`(NUMERIC(12,4)) 이라 JSON 본문에는 `60.0000` 으로 실려 오지만,
 * `JSON.parse` 가 JS `number` 60 으로 만든다 — 프론트에서는 전부 `number` 로 다룬다(실측).
 * 합계 100 판정도 문자열이 아니라 값으로 비교한다.
 */

/** 변수구분코드 — `WGVL`(가중치, 단위 %) | `CFC`(계수, 행별 단위). */
export type VariableSeCd = 'WGVL' | 'CFC'

/** GET /api/analysis-weights 의 변수 1건 (parking.TB_ANLS_VRBL_STNG). */
export interface AnalysisWeight {
  /** 분석유형코드 — 상위 `AnalysisTypeWeight.anlsTypeCd` 와 같은 값. 백엔드가 함께 내린다. */
  anlsTypeCd: string
  /** 변수코드. 분석유형 안에서 변수를 식별한다. 예) 'COMPLAINT' · 'MIN_CPCTY_VL'. */
  vrblCd: string
  /** 변수명. 예) '민원빈도' · '혼잡도 면수 하한'. */
  vrblNm: string
  /** 변수설명 (원천 데이터·산식 안내). 없으면 null. */
  vrblExpln: string | null
  /**
   * 변수구분코드. 🔴 **화면 섹션(가중치 / 계수)을 가르는 기준**이며 합계 100 검증도
   * `WGVL` 에만 적용된다. 프론트가 코드값을 유추하지 않는다.
   */
  vrblSeCd: VariableSeCd
  /** 현재 설정값 — 가중치는 %, 계수는 `unitNm` 단위. */
  stngVl: number
  /** 기본설정값 — 최초 등재값(불변). `기본값으로 초기화` 의 원천. */
  bscStngVl: number
  /**
   * 허용 하한. 🔴 **프론트에 범위 상수를 두지 않는다** —
   * 입력 clamp·`<input min>` 은 전부 이 값에서 온다. DB CHECK 과 앱 검증이 정본이다.
   */
  minVl: number
  /** 허용 상한. `minVl` 과 같은 이유로 응답에서만 온다. */
  maxVl: number
  /** 단위명 (화면 표시용 — `%` · `면/건`). 없을 수 있다. */
  unitNm: string | null
  /**
   * 분석내용코드(하위 유형). null 이면 대메뉴 공통 묶음. 예) LIFEZONE 혼잡도 → 'CNGST'.
   * 🔴 **optional** 이다 — 집중구역 응답(`api/analysis.ts`)·보고서 스냅샷이 같은 타입을 쓰고,
   * 옛 스냅샷에는 키가 없다.
   */
  anlsTrgtCd?: string | null
  /** 분석내용명(하위 유형 이름, 공통코드 cd_nm). 공통 묶음이거나 이름 미등재면 null. optional 인 이유는 위와 같다. */
  anlsTrgtNm?: string | null
  /** 'N' 이면 원천 데이터 미도입 변수 — 점수 산출에서 제외되고 나머지로 재정규화된다. */
  useYn: 'Y' | 'N'
  /**
   * 노출여부. 'N' 이면 화면(설정 메뉴 M0603 · 분석 계수 조정 패널)에 그리지 않는 고정값이다 —
   * 🔴 계산 스위치(`useYn`)와 별개다(숨김 행도 저장값 `stngVl` 로 계산된다).
   * 🔴 **optional** — 집중구역·투자 보고서 스냅샷 `weights[]` 가 같은 타입이고 옛 스냅샷엔 키가 없다
   * (`anlsTrgtCd?` 와 같은 이유). 설정 화면 응답에는 늘 오므로 화면 판정은 `expsrYn === 'Y'` 다.
   */
  expsrYn?: 'Y' | 'N'
  sortSeq: number
  /** 수정일시 'YYYY-MM-DDTHH:mm:ss'. 한 번도 저장하지 않았으면 null. */
  mdfcnDt: string | null
}

/** GET /api/analysis-weights 배열 항목 (분석유형 1건 + 변수 목록). */
export interface AnalysisTypeWeight {
  anlsTypeCd: string
  /** 분석유형명 — TB_COM_CD.cd_nm (세그먼트 라벨). */
  anlsTypeNm: string
  /** 세그먼트 표시 순서. 서버가 이 값으로 정렬해 내린다 — 프론트 재정렬 금지. */
  sortSeq: number
  /** 이 분석유형의 변수 목록 (sort_seq 순). 변수가 없으면 빈 배열(null 이 아니다). */
  weights: AnalysisWeight[]
  /**
   * 서버가 계산한 설정값 합계.
   * 🔴 합산 대상은 **`vrblSeCd === 'WGVL'` 이고 `useYn === 'Y'`** 인 행뿐이다 —
   * 계수(CFC)는 합계 개념이 없고, 미사용 가중치도 재정규화에서 빠지므로 합에 들어가지 않는다.
   */
  stngVlSum: number
  /**
   * 하위 유형 묶음별 가중치 합계. 순서 = 화면 묶음 순서(공통 묶음 먼저 → 공통코드 sort_seq) —
   * **서버가 정한다**, 프론트 재정렬 금지. 합계 100 검사는 이 단위다(위 `stngVlSum` 은 하위호환용 대메뉴 전체 합).
   * ⚠️ `stngVlSum` 은 **저장된 값**의 합이다 — 편집 중 합계는 화면이 draft 로 다시 계산한다.
   */
  trgtSums: AnalysisTrgtSum[]
}

/** 하위 유형 묶음 1건의 가중치 합계 (`AnalysisTypeWeight.trgtSums` 원소). */
export interface AnalysisTrgtSum {
  /** 분석내용코드. null 이면 대메뉴 공통 묶음. */
  anlsTrgtCd: string | null
  /** 분석내용명. 공통 묶음이거나 이름 미등재면 null. */
  anlsTrgtNm: string | null
  /** 묶음의 WGVL·useYn='Y' 행 stngVl 합. 계수만 있는 묶음은 0. */
  stngVlSum: number
  /** 'Y' 면 합계 100 검사 대상 묶음(WGVL·Y 행이 있다). */
  wgvlYn: 'Y' | 'N'
}

/** GET /api/analysis-weights/histories 배열 항목 (이력 1행 = 분석유형 1건의 저장 1회). */
export interface AnalysisWeightHistory {
  /** 변경이력일련번호 — 이력 표의 행 key. */
  chgHstrySn: number
  /** 변경 대상 분석유형코드. */
  anlsTypeCd: string
  chgDt: string          // 'YYYY-MM-DDTHH:mm:ss'
  chnrgNm: string        // 로그인 도입 전 '시스템'
  chgArtclNm: string     // 변경 항목 = 분석유형명
  chgCn: string          // '민원빈도 60 → 55, 단속건수 40 → 45'
  chgRsn: string | null
}

/**
 * PUT /api/analysis-weights/{anlsTypeCd} 요청 바디 (전체 교체 저장).
 *
 * 서버가 해당 분석유형의 변수를 이 목록으로 통째로 바꾼다. 최소 1건이어야 하고,
 * **그 분석유형의 변수 집합 전체**(가중치 + 계수)를 함께 보낸다 — 빠지면 집합 불일치로 400 이다.
 *
 * ⚠️ `stngVl` 은 더 이상 정수 전용이 아니다 — 계수(`CFC`)는 `0.5` 같은 소수를 받는다
 * (컬럼 `NUMERIC(12,4)`). 합계 100 검증은 **`WGVL`·`useYn='Y'` 행에만** 걸리고,
 * 값의 허용 범위는 행별 `minVl`~`maxVl` 이 진다.
 */
export interface AnalysisWeightSaveRequest {
  weights: { vrblCd: string; stngVl: number }[]
  /** 변경 사유(선택, 최대 300자). 이력의 `chgRsn` 에 그대로 기록된다. */
  chgRsn?: string
}

/** 분석유형별 변수 목록 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지). */
export async function fetchAnalysisWeights(): Promise<AnalysisTypeWeight[]> {
  const { data } = await api.get<AnalysisTypeWeight[]>('/analysis-weights')
  return data
}

/**
 * 분석유형 1건의 변수를 전체 교체 저장하고 갱신된 1건을 돌려받는다.
 * 오류: 없는 anlsTypeCd → 404 / 합계 100 아님(WGVL·Y 한정)·변수 집합 불일치·
 * `minVl`~`maxVl` 범위 밖·바디 누락 → 400.
 */
export async function saveAnalysisWeights(
  anlsTypeCd: string,
  body: AnalysisWeightSaveRequest,
): Promise<AnalysisTypeWeight> {
  const { data } = await api.put<AnalysisTypeWeight>(
    `/analysis-weights/${encodeURIComponent(anlsTypeCd)}`,
    body,
  )
  return data
}

/**
 * 가중치 변경 이력 최신순 조회. `size` 기본 20 · **서버 상한 100**(벗어나면 400).
 * 정렬(chg_dt DESC)은 서버가 확정한다.
 *
 * @param anlsTypeCd 선택 — 주면 그 분석유형의 이력만 받는다.
 *   🔴 **거르는 일은 서버(SQL)가 한다.** 받은 배열을 프론트에서 `anlsTypeCd` 로 거르면
 *   `LIMIT` 이 먼저 걸린 뒤라 한 유형을 `size` 번 저장했을 때 다른 탭 이력이 0건으로 보인다.
 *   생략하면 종전대로 전 분석유형을 섞어 내려준다. 없는 코드는 400 이 아니라 빈 배열이다.
 */
export async function fetchAnalysisWeightHistories(
  size = 20,
  anlsTypeCd?: string,
): Promise<AnalysisWeightHistory[]> {
  const { data } = await api.get<AnalysisWeightHistory[]>('/analysis-weights/histories', {
    // undefined 인 params 는 axios 가 쿼리에서 빼므로 미지정(전 유형) 경로를 그대로 탄다.
    params: { size, anlsTypeCd },
  })
  return data
}

/* ── 분석 계수 일시 조정 ─────────────────────────
 * 설정 표·이력에는 쓰지 않는 **1회용 조회 조건**이다. 화면 상태(useAnalysisCoefficient)가 값을 만든다.
 */

/**
 * 일시 조정 입력 — 키 `vrblCd`, 값 숫자. 기본값과 다른 값만, 단 합계 100·경계 묶음은 묶음 전체.
 * GET 은 {@link vrblVlsQuery}, POST 바디는 {@link vrblVlsBody} 로 바꿔 싣는다.
 */
export type VrblVls = Record<string, number>

/** 적용 내역 1행 (`vrblAjmt.vrbls[]`). 조정 안 한 행은 `aplcnVl === stngVl`. */
export interface AnlsVrblAjmtItem {
  vrblCd: string
  vrblNm: string
  /** 단위. 단위 없는 계수는 null. */
  unitNm: string | null
  /** 당시 설정값. */
  stngVl: number
  /** 적용값. */
  aplcnVl: number
  /**
   * 생성 당시 기본설정값. 🔴 **optional** — 그 이전 스냅샷(구 스냅샷 ②)에는 키가 없다.
   * 보고서 `적용 설정` 상자가 이 값과 적용값을 비교한다.
   */
  bscStngVl?: number
  /** 생성 당시 노출여부. `N` = 숨김 고정값 — 보고서 `적용 설정` 상자에 싣지 않는다. 구 스냅샷엔 키가 없다. */
  expsrYn?: 'Y' | 'N'
}

/**
 * 적용 내역 (`vrblAjmt`). 응답 타입에는 `vrblAjmt?: AnlsVrblAjmt | null` 로 붙인다 —
 * 구 보고서는 `null`, 생활권 수급은 키 자체가 생략된다(`NON_NULL`). VO 초기값을 두지 않는다.
 */
export interface AnlsVrblAjmt {
  /** `Y` = 설정값과 값으로 다른 입력이 하나라도 있다(`60.00` = `60`). */
  ajmtYn: 'Y' | 'N'
  vrbls: AnlsVrblAjmtItem[]
}

/**
 * GET 쿼리 값 — JSON 문자열(axios 가 인코딩한다). 🔴 비어 있으면 `undefined` 를 돌려 **파라미터 자체를 빼게** 한다
 * (axios 는 undefined params 를 쿼리에서 뺀다). 프론트에 `paramsSerializer` 가 없어 객체를 그대로 넣으면
 * `vrblVls[KEY]=` 로 깨진다.
 */
export function vrblVlsQuery(vrblVls: VrblVls | null | undefined): string | undefined {
  return vrblVls && Object.keys(vrblVls).length > 0 ? JSON.stringify(vrblVls) : undefined
}

/** POST 바디 값 — 객체 그대로. 비어 있으면 `undefined`(JSON 직렬화에서 키가 빠진다). */
export function vrblVlsBody(vrblVls: VrblVls | null | undefined): VrblVls | undefined {
  return vrblVls && Object.keys(vrblVls).length > 0 ? vrblVls : undefined
}
