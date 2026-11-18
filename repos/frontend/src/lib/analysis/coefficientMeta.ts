/*
 * 분석 계수 일시 조정 — 화면·탭별 변수 메타 (TSK-4325 Step 8 · TSK-4332 Step 6).
 *
 * 🔴 **변수 목록은 백엔드 `AnlsVrblScope`(analysis/service/AnlsVrblScope.java)와 같아야 한다**(D3).
 * 목록 밖 코드를 보내면 400 이다. 허용 목록을 고치면 이 파일도 함께 고친다(D17 — 줄이는 판단도 여기와 enum 두 곳).
 *
 * 🔴 범위(`minVl`·`maxVl`)·기본값(`stngVl`)·라벨(`vrblNm`)·단위는 **설정 응답**에서 읽는다(D5·D15 —
 * TSK-4140 D3 범위 하드코딩 금지). 여기에는 `step`·힌트·묶음 구성만 둔다.
 *
 * 묶음(D21) — 합계 100 가중치 묶음과 등급 경계 묶음은 한 행이라도 다르면 **묶음 전체**를 보낸다.
 * 🔴 집중구역 가중치(`COMPLAINT`·`ENFORCE`)는 `vrblVls` 가 아니라 기존 `cvlcptWgvl`·`crdnWgvl` 이다(D2).
 *
 * 🔁 TSK-4332 — 정책 시뮬레이션 3스코프(`POLICY_EXPAND`·`POLICY_REPAIR`·`POLICY_FEE`). 공통 4행은
 * `POLICY_COMMON_META` 하나를 세 스코프 `coeffs` 앞쪽에 spread 하고 `common` 으로 형제 스코프와 값을 공유한다.
 * 🔴 고정값(`min_vl = max_vl`) 9행은 메타에 두지 않는다(TSK-4332 D2) — 확충은 공통 4행 + `note` 뿐이다.
 */

/** 백엔드 `AnlsVrblScope` 상수명과 같다. */
export type CoeffScope =
  | 'HOTSPOT'
  | 'INVEST'
  | 'LIFEZONE_SPLY'
  | 'LIFEZONE_CNGST'
  | 'RECOMMEND_FEE'
  | 'RECOMMEND_SHARE'
  | 'POLICY_EXPAND'
  | 'POLICY_REPAIR'
  | 'POLICY_FEE'

/** 계수 조정 버튼이 붙는 화면. 화면 하나가 탭별로 스코프 여러 개를 가질 수 있다. */
export type CoeffScreen = 'HOTSPOT' | 'INVEST' | 'LIFEZONE' | 'RECOMMEND' | 'POLICY'

/** 화면 → 스코프(탭 순서). 생활권은 팝오버 세그먼트가 이 순서로 그려진다(D13). */
export const SCREEN_SCOPES: Record<CoeffScreen, readonly CoeffScope[]> = {
  HOTSPOT: ['HOTSPOT'],
  INVEST: ['INVEST'],
  LIFEZONE: ['LIFEZONE_SPLY', 'LIFEZONE_CNGST'],
  RECOMMEND: ['RECOMMEND_FEE', 'RECOMMEND_SHARE'],
  // 정책 유형 순서 = 팝오버 `정책 유형별` 세그먼트 순서(TSK-4332 D5).
  POLICY: ['POLICY_EXPAND', 'POLICY_REPAIR', 'POLICY_FEE'],
}

/** 변수 1개의 화면 메타. */
export interface CoeffVarMeta {
  vrblCd: string
  /** 슬라이더 눈금. 설정값이 `minVl + k·step` 이 아니면 그 행은 `step="any"` 로 그린다(D15). */
  step: number
  /** 행 아래 한 줄 설명. */
  hint?: string
  /** 라벨 덮어쓰기. 없으면 설정 응답의 `vrblNm`(설정 화면과 같은 이름). */
  label?: string
  /** 서버 정수 전용(`GRD_BNDRY*`·`SPLY_*`) — 보낼 때 정수로 반올림한다. */
  intOnly?: boolean
}

/**
 * 합계 100 가중치 묶음.
 * - `pair`: 첫 행만 슬라이더, 둘째 행은 `100 - 첫 행` 자동(`자동 (합계 100%)`) — 집중구역·구역추천 요금제(D22)
 * - `redistribute`: 위 항목을 조정하면 아래 항목을 비율대로 재분배, 마지막 항목 잠금 — 투자우선순위
 */
export interface CoeffWeightMeta {
  mode: 'pair' | 'redistribute'
  /** 절 제목. */
  title: string
  /** 절 제목 아래 안내(선택). */
  note?: string
  /** `false` = `vrblVls` 에 싣지 않는다(집중구역 D2 — `cvlcptWgvl`·`crdnWgvl` 로 보낸다). */
  inVrblVls: boolean
  /** 가중치 행이 없거나 꺼지면 버튼 자체를 숨긴다(집중구역 TSK-4306 D3). */
  required?: boolean
  vars: CoeffVarMeta[]
}

export interface CoeffScopeMeta {
  scope: CoeffScope
  /** 설정 행을 찾는 키 — `(anlsTypeCd, vrblCd)` 가 PK 라 행이 하나로 정해진다. */
  anlsTypeCd: string
  /** 세그먼트·오류 문구에 쓰는 탭 이름. */
  label: string
  weight: CoeffWeightMeta | null
  /** 가중치 밖 계수 — 팝오버 `분석 계수` 절 순서. */
  coeffs: CoeffVarMeta[]
  /** 엄격 증가 묶음(작은 값부터). 백엔드 `bndryChains` 와 같고, 투자 `중간 < 상위` 도 여기 있다. */
  chains: string[][]
  /**
   * 같은 화면의 형제 스코프와 **값을 공유하는** 변수코드(TSK-4332 — 정책 공통 4). 행 메타는 `coeffs` 앞쪽에도 있어야 한다.
   * 없으면 공유하지 않는다 — 🔴 구역추천 두 탭의 `CVLCPT_MM_CRTR_VL` 은 코드가 같아도 독립이다(TSK-4325 D13).
   */
  common?: readonly string[]
  /** 공통 밖 계수가 없을 때 그 유형 절에 보이는 문구(정책 확충 `※` 문구). */
  note?: string
}

const WEIGHT_TITLE = '종합점수 가중치 (합계 100%)'

const HOTSPOT_GRD: string[] = ['GRD_BNDRY1', 'GRD_BNDRY2', 'GRD_BNDRY3']
const SPLY_GRD: string[] = [
  'SPLY_GRD_BNDRY1',
  'SPLY_GRD_BNDRY2',
  'SPLY_GRD_BNDRY3',
  'SPLY_GRD_BNDRY4',
]
const CNGST_GRD: string[] = [
  'CNGST_GRD_BNDRY1',
  'CNGST_GRD_BNDRY2',
  'CNGST_GRD_BNDRY3',
  'CNGST_GRD_BNDRY4',
]

/**
 * 정책 시뮬레이션 공통(trgt NULL) 조정 가능 4행 — 백엔드 `AnlsVrblScope.Codes.POLICY_COMMON` 과 같은 순서.
 * 🔴 `GEO_*` step 0.005 — 설정값 2.89·1.385 가 눈금(min 1) 위에 온다. 힌트는 시안에 없어 비운다.
 */
const POLICY_COMMON_META: CoeffVarMeta[] = [
  {
    vrblCd: 'PCSHR_CRTR_VL',
    step: 0.05,
    hint: '정상이용 U0 = 면수 × 이 값 (점유율 실측 결측으로 상수)',
  },
  { vrblCd: 'ALPHA_VL', step: 0.05, hint: '확충 면수 중 불법주차를 흡수하는 비율' },
  { vrblCd: 'GEO_CVRG_CRCT_JEJU', step: 0.005 },
  { vrblCd: 'GEO_CVRG_CRCT_SEOGWIPO', step: 0.005 },
]
const POLICY_COMMON: readonly string[] = POLICY_COMMON_META.map((m) => m.vrblCd)

export const COEFF_SCOPE_META: Record<CoeffScope, CoeffScopeMeta> = {
  HOTSPOT: {
    scope: 'HOTSPOT',
    anlsTypeCd: 'HOTSPOT',
    label: '집중 구역',
    weight: {
      mode: 'pair',
      title: WEIGHT_TITLE,
      inVrblVls: false,
      required: true,
      // 라벨은 TSK-4306 화면 문구를 유지한다(설정 vrblNm 은 `민원빈도`·`단속건수`).
      vars: [
        { vrblCd: 'COMPLAINT', step: 5, label: '민원 건수' },
        { vrblCd: 'ENFORCE', step: 5, label: '단속 건수' },
      ],
    },
    coeffs: [
      {
        vrblCd: 'GRD_BNDRY1',
        step: 1,
        intOnly: true,
        hint: '종합점수 순위 상위 이 비율 안이면 심각',
      },
      {
        vrblCd: 'GRD_BNDRY2',
        step: 1,
        intOnly: true,
        hint: '심각 다음부터 상위 이 비율 안이면 경고',
      },
      {
        vrblCd: 'GRD_BNDRY3',
        step: 1,
        intOnly: true,
        hint: '경고 다음부터 상위 이 비율 안이면 주의 (그 밖은 표시 안 함)',
      },
    ],
    chains: [HOTSPOT_GRD],
  },

  INVEST: {
    scope: 'INVEST',
    anlsTypeCd: 'INVEST',
    label: '투자우선순위',
    weight: {
      mode: 'redistribute',
      title: WEIGHT_TITLE,
      note: '위에서 조정하면 아래 항목이 자동 재분배됩니다 (마지막 항목 자동).',
      inVrblVls: true,
      vars: [
        { vrblCd: 'SAFETY', step: 5 },
        { vrblCd: 'COMPLAINT', step: 5 },
        { vrblCd: 'USAGE', step: 5 },
        { vrblCd: 'ACCIDENT', step: 5 },
      ],
    },
    coeffs: [
      {
        vrblCd: 'ALTMNT_RNG_VL',
        step: 10,
        hint: '민원·단속 1건을 최근접 대상 주차장 1곳에 배정하는 범위',
      },
      { vrblCd: 'UP_GRD_CRTR_SCR', step: 1, hint: '종합점수 이 값 이상이면 우선순위 높음(H)' },
      {
        vrblCd: 'MD_GRD_CRTR_SCR',
        step: 1,
        hint: '이상이고 상위 미만이면 중간(M), 미만이면 낮음(L)',
      },
      // 🔴 step 1 — 서버 정수 전용 대상이 아니라 소수가 오면 crtrVls(반올림)·aplcnVl(원값) 표시가 갈린다.
      { vrblCd: 'INDCT_PKLT_CNT', step: 1, hint: '목록·지도 표시 수 (점수 계산엔 미사용)' },
    ],
    chains: [['MD_GRD_CRTR_SCR', 'UP_GRD_CRTR_SCR']],
  },

  LIFEZONE_SPLY: {
    scope: 'LIFEZONE_SPLY',
    anlsTypeCd: 'LIFEZONE',
    label: '주차 수급',
    weight: null,
    coeffs: [
      {
        vrblCd: 'SPLY_MIN_MNTH_AVG_CVLCPT',
        step: 1,
        intOnly: true,
        hint: '월평균 민원이 이 건수 미만인 격자는 수급 분석에서 제외',
      },
      { vrblCd: 'SPLY_GRD_BNDRY1', step: 1, intOnly: true, hint: '부족률 ≥ 이 값이면 보통(하한)' },
      { vrblCd: 'SPLY_GRD_BNDRY2', step: 1, intOnly: true, hint: '부족률 ≥ 이 값이면 주의(하한)' },
      { vrblCd: 'SPLY_GRD_BNDRY3', step: 1, intOnly: true, hint: '부족률 ≥ 이 값이면 경고(하한)' },
      { vrblCd: 'SPLY_GRD_BNDRY4', step: 1, intOnly: true, hint: '부족률 ≥ 이 값이면 심각(하한)' },
    ],
    chains: [SPLY_GRD],
  },

  LIFEZONE_CNGST: {
    scope: 'LIFEZONE_CNGST',
    anlsTypeCd: 'LIFEZONE',
    label: '혼잡도',
    weight: null,
    coeffs: [
      {
        vrblCd: 'PCSHR_CRTR_VL',
        step: 0.05,
        hint: '정상이용 U0 = 면수 × 이 값 (점유율 실측 결측으로 상수)',
      },
      { vrblCd: 'CNGST_GRD_BNDRY1', step: 1, hint: '지수 1.0 = 100점 기준 고정 경계 — 보통 하한' },
      { vrblCd: 'CNGST_GRD_BNDRY2', step: 1, hint: '지수 1.0 = 100점 기준 고정 경계 — 주의 하한' },
      { vrblCd: 'CNGST_GRD_BNDRY3', step: 1, hint: '지수 1.0 = 100점 기준 고정 경계 — 경고 하한' },
      { vrblCd: 'CNGST_GRD_BNDRY4', step: 1, hint: '지수 1.0 = 100점 기준 고정 경계 — 심각 하한' },
      { vrblCd: 'DSCL_MLTP', step: 0.5, hint: '미적발 불법주차 보정 배수(민원엔 미적용)' },
      { vrblCd: 'FIX_WGVL', step: 0.05, hint: '고정형·주행형·버스장착형 CCTV 단속 1건의 가중치' },
      { vrblCd: 'GEO_CVRG_CRCT_VL', step: 0.005, hint: '1 ÷ 단속 좌표 보유율(평균 0.551)' },
      { vrblCd: 'CVLCPT_WGVL', step: 0.1, hint: '0이면 명세 방식(단속만)' },
      { vrblCd: 'MIN_CPCTY_VL', step: 1, hint: '이 면수 미만 격자는 분석에서 제외' },
    ],
    chains: [CNGST_GRD],
  },

  RECOMMEND_FEE: {
    scope: 'RECOMMEND_FEE',
    anlsTypeCd: 'RECOMMEND',
    label: '요금제',
    // D22 — 민원강도만 슬라이더, 면당민원은 `100 - 민원강도` 자동.
    weight: {
      mode: 'pair',
      title: '검토지수 가중치 (합계 100%)',
      inVrblVls: true,
      vars: [
        { vrblCd: 'FEE_CVLCPT_CNT_WGVL', step: 5 },
        { vrblCd: 'FEE_CVLCPT_RT_WGVL', step: 5 },
      ],
    },
    coeffs: [
      {
        vrblCd: 'ALTMNT_RNG_VL',
        step: 10,
        hint: '민원·단속 1건을 최근접 후보 주차장 1곳에 배정하는 범위 — 순위를 좌우',
      },
      { vrblCd: 'SWTC_TRGT_MIN_CPCTY', step: 1, hint: '이 면수 미만 주차장은 제외' },
      // 🔁 TSK-4340 — 읍면동 판정 계수 3개(STSF_RT_THRSH·CVLCPT_MM_CRTR_VL·ILGL_CRTR_VL)는 판정과 함께 걷었다(백엔드 RECOMMEND_FEE 와 같이).
      {
        vrblCd: 'FEE_CVLCPT_MM_CRTR_JEJU',
        step: 1,
        hint: '제주시 월배정 민원이 이 값이면 민원강도 100점',
      },
      {
        vrblCd: 'FEE_CVLCPT_MM_CRTR_SEOGWIPO',
        step: 1,
        hint: '서귀포시 월배정 민원이 이 값이면 민원강도 100점',
      },
      {
        vrblCd: 'FEE_CVLCPT_RT_CRTR_JEJU',
        step: 1,
        hint: '제주시 100면당 월 민원이 이 값이면 면당압력 100점',
      },
      {
        vrblCd: 'FEE_CVLCPT_RT_CRTR_SEOGWIPO',
        step: 1,
        hint: '서귀포시 100면당 월 민원이 이 값이면 면당압력 100점',
      },
      {
        vrblCd: 'FEE_CVLCPT_INCRS_WGVL',
        step: 0.05,
        hint: '종합지수 = 검토지수 × (1 + 이 값 × 민원증가율)',
      },
      {
        vrblCd: 'FEE_CVLCPT_INCRS_RT_UPLMT_VL',
        step: 10,
        hint: '민원증가율을 ±이 값(%)에서 잘라 반영',
      },
    ],
    chains: [],
  },

  RECOMMEND_SHARE: {
    scope: 'RECOMMEND_SHARE',
    anlsTypeCd: 'RECOMMEND',
    label: '공유주차제',
    weight: null,
    coeffs: [
      {
        vrblCd: 'CVLCPT_MM_CRTR_VL',
        step: 1,
        hint: '월평균 민원이 이 건수 미만인 읍면동은 추천 제외',
      },
      {
        vrblCd: 'CVLCPT_RT_CRTR_VL',
        step: 0.5,
        hint: '공영 100면당 월 민원이 이 값 이상이면 공유주차 검토 후보',
      },
      {
        vrblCd: 'CVLCPT_INCRS_WGVL',
        step: 0.05,
        hint: '추세 반영 지수 = 검토 지수 × (1 + 이 값 × 민원증가율)',
      },
      {
        vrblCd: 'CVLCPT_INCRS_RT_UPLMT_VL',
        step: 10,
        hint: '민원증가율을 ±이 값(%)에서 잘라 반영',
      },
    ],
    chains: [],
  },

  POLICY_EXPAND: {
    scope: 'POLICY_EXPAND',
    anlsTypeCd: 'POLICY_SIM',
    label: '주차장 확충',
    weight: null,
    // 🔴 trgt EXPAND 6행은 모두 고정값이라 공통만 있다(D2).
    coeffs: [...POLICY_COMMON_META],
    chains: [],
    common: POLICY_COMMON,
    note: '※ 주차장 확충 유형의 산식 계수(조성비 등)는 명세 고정값이라 조정 대상이 아닙니다. 공통 계수만 적용됩니다.',
  },

  POLICY_REPAIR: {
    scope: 'POLICY_REPAIR',
    anlsTypeCd: 'POLICY_SIM',
    label: '이면도로 정비',
    weight: null,
    coeffs: [
      ...POLICY_COMMON_META,
      { vrblCd: 'N100_RSDNC', step: 0.1 },
      { vrblCd: 'N100_CMRC', step: 0.1 },
      { vrblCd: 'ALPHA_B_VL', step: 0.05 },
      { vrblCd: 'BALLOON_RT', step: 0.05 },
    ],
    chains: [],
    common: POLICY_COMMON,
  },

  POLICY_FEE: {
    scope: 'POLICY_FEE',
    anlsTypeCd: 'POLICY_SIM',
    label: '요금제 적용',
    weight: null,
    coeffs: [
      ...POLICY_COMMON_META,
      { vrblCd: 'PRC_ELST_VL', step: 0.05 },
      { vrblCd: 'PAID_DMND_RT', step: 0.05 },
      { vrblCd: 'TRNSF_RT', step: 0.05 },
      { vrblCd: 'TRNSF_LOCAL_RT', step: 0.05 },
    ],
    chains: [],
    common: POLICY_COMMON,
  },
}
