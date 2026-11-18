import { api } from './client'
import {
  vrblVlsBody,
  vrblVlsQuery,
  type AnlsVrblAjmt,
  type VrblVls,
} from './analysisWeight'

/**
 * 생활권 분석·보고서 호출 타임아웃(ms) — TSK-4272.
 *
 * 생활권 3종(민원건수·혼잡도·수급)은 응답 전에 순위 행 구역명을 VWorld 로 조회한다(백엔드 `ZoneNameResolver` —
 * 요청당 외부 조회 상한 `analysis.zone-name.max-lookups-per-run` 50 · 캐시 히트는 미소모). 첫 조회는 캐시가 비어
 * 공용 10초를 넘을 수 있다(실측: 상한 200 일 때 수급 10.2~11.2초 → 화면에서 끊김). 🔴 보고서 저장(POST)도
 * **서버가 같은 분석을 다시 돌려** 같은 조회를 타므로 함께 늘린다.
 *
 * 30초 근거 — 정상(상한 50) 5~6초 · VWorld 장애는 연속 일시 실패 3건 차단으로 약 24초에 끊긴다.
 * 🔴 공용 `api` 의 10초는 올리지 않는다(모든 화면의 실패가 그만큼 늦게 보인다 — `ai.ts` 와 같은 관례).
 * ⚠️ VWorld 가 "느리지만 성공" 하는 날(호출당 1~2초)은 차단이 걸리지 않아 30초를 넘길 수 있다.
 */
export const LIFEZONE_ANALYSIS_TIMEOUT_MS = 30_000

/*
 * 생활권 시뮬레이션(격자 분석) 실 API 클라이언트 (TSK-4088 Step 7).
 * 백엔드 계약(`jeju.datahub.lifezone.web.LifezoneAnalysisController` · 2026-08-31 로컬 실측):
 *   GET  /api/analysis/lifezone/targets → LifezoneTargetInfo[]     (설정 패널 세그먼트용 경량 목록)
 *   GET  /api/analysis/lifezone         → LifezoneAnalysisResult   (격자 셀 전량 + 순위 목록)
 *   POST /api/analysis/lifezone/reports → LifezoneReportResponse   (201 · 내보내기 저장 — Step 12)
 *   GET  /api/analysis/lifezone/congestion → LifezoneCngstResult    (혼잡도 — TSK-4090 · §7)
 *
 * 🔴 **`api/analysis.ts`(집중구역)를 확장하지 않고 새 파일로 둔다** — 응답 타입이 전혀 다르다.
 * 집중구역은 DBSCAN 군집 15건에 종합점수·가중치가 붙지만, 생활권은 격자 셀 전량(값>0)에
 * 단일 지표 값 하나가 붙는다. 등급도 4종 ↔ 5종으로 갈린다.
 *
 * **조회가 GET 인 이유** — 분석 결과를 저장하지 않는 순수 조회다. 조건이 URL 에 남아 재현이 쉽다.
 * 저장은 **`내보내기` 시점에만** 일어나며 아래 `createLifezoneReport`(§6)가 맡는다.
 * ⚠️ 저장된 보고서를 **다시 여는** `GET /api/reports/{rptpId}/lifezone` 은 이 파일이 아니라
 * `api/reports.ts` 에 있다 — URL 접두가 보고서 도메인이고 목록 화면이 부르는 함수라,
 * 집중구역(`fetchHotspotReport`)과 같은 자리에 둔다.
 *
 * 응답은 조회 조건(`bgngYmd`·`endYmd`·`sggNm`·`emdCd`·`anlsTrgtCd`·`gridSz`)을 **되비추고**,
 * 화면 문구(`anlsTrgtNm`·`gridSzNm`·`unit`·`sectionTitle`·`sectionSub`)와 범례 문구(`lgndTxt`)까지
 * 서버가 만들어 내린다 — 응답 하나로 화면을 그리고 결과를 설명할 수 있게 하려는 설계다.
 *
 * 🔴 **임계값·등급 문구·유형명을 이 파일에 적지 않는다.** 구간 경계는 조회 결과에서 매번 다시
 * 산출되므로(아래 `LifezoneGradeScaleInfo`) 프론트에 박을 상수가 애초에 존재하지 않는다.
 * 민원 유형명도 공통코드에서 오므로 화면은 `cvlcptTypeNm` 을 그대로 출력한다.
 *
 * ⚠️ 숫자 필드 중 `ctrLat`·`ctrLot`·`ring` 원소는 백엔드가 `BigDecimal` 이라 본문에는
 * `33.4899899481` 처럼 실려 오지만 `JSON.parse` 가 JS `number` 로 만든다 — 프론트에서는 전부 `number` 다.
 */

/* ── 1. 코드 타입 ─────────────────────────────────────── */

/**
 * 등급코드 — **5종**이다(집중구역은 4종).
 *
 * 🔴 등급 경계는 **조회 결과에서 산출**되므로(기본 자연분류 5구간) 고정 임계값이 없다.
 * 판정은 전부 백엔드가 하고 프론트는 이 코드로 **색만** 고른다
 * (`components/lifezone/lifezoneGrade.ts` — 집중구역 등급 매핑과는 별개 파일이다).
 * 라벨은 응답의 `gradeNm`(심각/경고/주의/보통/낮음)을, 범례 문구는 `lgndTxt` 를 그대로 쓴다.
 */
export type LifezoneGrade = 'SEVERE' | 'WARN' | 'CAUTION' | 'NORMAL' | 'LOW'

/**
 * 분석내용코드. 공통코드 `ANLS_TRGT_CD` 의 `cd_vl` 과 같은 값이다.
 *
 * ⚠️ 지금 실제로 도는 것은 `CVLCPT_CNT` 뿐이다 — 나머지 둘은 원천 데이터가 없어 **400** 이다.
 * 어느 것이 가용한지는 화면이 판단하지 말고 `availableTargets[].available` 을 보고 그린다.
 */
export type LifezoneTargetCd = 'CVLCPT_CNT' | 'SPLY_DMND' | 'CNGST'

/** 격자크기(m). `TB_GRID` 가 보유한 4종뿐이며 그 밖의 값은 **400** 이다. */
export type LifezoneGridSz = 1000 | 500 | 250 | 100

/**
 * 등급 **분류방법**. 백엔드 `LifezoneClsfMthd` 상수명과 같은 값이며 그 밖의 값은 **400** 이다.
 *
 * - `JENKS` — 자연분류(Fisher-Jenks natural breaks). 종전 민원건수 기본값 — TSK-4227 로 허용 목록에서 빠졌다(과거 보고서 표시용).
 * - `EQUAL_INTERVAL` — 등간격. 값 범위를 5등분해 폭이 균등하다. 🔁 **민원건수 유형 기본값**(TSK-4227 — 유일 허용값).
 *
 * - `QUANTILE` — 분위(등급대상을 칸 수 기준으로 5등분). 종전 혼잡도 기본값(TSK-4090) — TSK-4227 로 허용 목록에서 빠졌다.
 * - `CNGST_ABSOLUTE` — 혼잡 1.0 기준 = 100점(지수 1.0) 기준 고정 경계(설정 경계 `[88, 92, 96, 100]`점 · 2026-10-08). 🔁 **혼잡도 유형 기본값**(TSK-4227 — 유일 허용값).
 * - `EQUAL_INTERVAL_MIN` — 등간격(최솟값 기준). **100점 초과 = 심각**, 나머지는 **최솟값~100점**을 4등분해
 *   낮음·보통·주의·경고. 🆕 혼잡도 전용(2026-10-08 · ⑪ 개정). ⚠️ 혼잡 점수는 85~91점에 몰려 대부분이 '낮음'에 간다 — 알고 고르는 선택지다.
 *
 * 🔁 **TSK-4227 — 화면은 분류방법을 보내지 않는다.** 유형마다 1종으로 확정돼 서버가 유형 기본값
 * (민원건수 `EQUAL_INTERVAL` · 혼잡도 `CNGST_ABSOLUTE`)을 쓴다. 허용 목록(`LifezoneTargetInfo.clsfMthds`) 밖 값을
 * 보내면 400 이다. 상수 목록을 남긴 것은 응답 `clsfMthd`·과거 보고서 스냅샷 표시용이며, 결과 설명은 응답 `clsfMthdNm` 을 쓴다.
 * ⚠️ 등간격은 쏠린다(100m 격자는 대부분 최하위 — GVF 실측은 백엔드 `LifezoneClsfMthd` 주석).
 */
export type LifezoneClsfMthd =
  | 'JENKS'
  | 'EQUAL_INTERVAL'
  | 'EQUAL_INTERVAL_P95'
  | 'QUANTILE'
  | 'CNGST_ABSOLUTE'
  | 'EQUAL_INTERVAL_MIN'

/**
 * 분류방법 → 화면 라벨 (백엔드 `LifezoneClsfMthd` 의 표시명과 같은 표기).
 *
 * 🔴 **목록(무엇을 보일지)은 서버, 라벨(어떻게 부를지)만 프론트다.** 조회 전에도 세그먼트를 그려야
 * 해서 라벨만 여기 둔다. `Record` 전수 매핑이라 유니온에 상수가 늘면 컴파일이 잡는다.
 */
export const CLSF_MTHD_NM: Record<LifezoneClsfMthd, string> = {
  JENKS: '자연분류',
  EQUAL_INTERVAL: '등간격',
  // 자의 길이를 P95 로 짧게 잡은 등간격 — 하위가 잘게 갈리는 대신 상위 5% 가 최상위 등급에 뭉친다.
  EQUAL_INTERVAL_P95: '등간격 P95',
  QUANTILE: '분위',
  CNGST_ABSOLUTE: '혼잡 1.0 기준',
  EQUAL_INTERVAL_MIN: '등간격(최솟값 기준)',
}

/* ── 2. 조회 파라미터 ─────────────────────────────────── */

/**
 * GET /api/analysis/lifezone 조회 파라미터.
 *
 * 🔴 **`전체` 를 고른 지역 필터는 키를 아예 넣지 않는다**(`sggNm`·`emdCd`).
 * 빈 문자열은 **400** 이다 — 백엔드 `LifezoneAnalysisRequest` 가 빈 값을 명시적으로 거절한다
 * ("키를 아예 넣지 않는 것이 전체"). 집중구역 `HotspotAnalysisParams` 와 같은 규약이다.
 *
 * 화면 상태는 `전체` 를 `''` 로 두면 되고, 빈 값 제거는 아래 `fetchLifezoneAnalysis` 가 책임진다.
 */
export interface LifezoneAnalysisParams {
  /** 분석 시작일 (YYYYMMDD, 필수). 형식이 어긋나면 400. */
  bgngYmd: string
  /** 분석 종료일 (YYYYMMDD, 필수 · **당일 포함**). bgngYmd 보다 앞서면 400. */
  endYmd: string
  /** 분석내용코드. 생략 = 서버 기본값 `CVLCPT_CNT`. 미구현 유형이면 400. */
  anlsTrgtCd?: LifezoneTargetCd
  /** 격자크기(m). 생략 = 서버 기본값 `500`. */
  gridSz?: LifezoneGridSz
  /** 등급 분류방법. 🔁 TSK-4227 — 화면은 보내지 않는다. 생략 = 유형 기본값(민원건수 `EQUAL_INTERVAL` · 혼잡도 `CNGST_ABSOLUTE`). 허용 밖 값이면 400. */
  clsfMthd?: LifezoneClsfMthd
  /** 시군구명. 예) '제주시'. **전체면 넣지 않는다**(`''`·생략 = 전 지역). 미등록 시군구면 400. */
  sggNm?: string
  /** 읍면동코드. 예) '50110137'. **전체면 넣지 않는다**(`''`·생략 = 시군구 전체). 미존재면 400. */
  emdCd?: string
  /**
   * 순위 개수. 🔁 TSK-4227 D21 — **생략 = 상한 없음**(심각·경고·주의 전부) · 지정하면 그 값까지(1 이상, 0 이하는 400).
   * 화면은 보내지 않는다. 셀 목록(`cells`)은 이 값과 무관하게 전량이다.
   */
  limit?: number
}

/* ── 3. 응답 타입 ─────────────────────────────────────── */

/**
 * 분석유형 1건 — 설정 패널 세그먼트의 단일 출처다(`/targets` 와 분석 응답이 같은 모양을 쓴다).
 *
 * 🔴 **가용 여부를 프론트가 판단하지 않는다.** `available === false` 인 항목은 세그먼트를
 * 비활성으로 그리고 `unavailableRsn` 을 안내 문구로 쓴다 — 원천이 붙는 날 백엔드만 바뀌면 된다.
 */
export interface LifezoneTargetInfo {
  /** 분석내용코드. 조회 파라미터 `anlsTrgtCd` 에 그대로 넣는다. */
  anlsTrgtCd: LifezoneTargetCd
  /** 화면 표시용 유형명 (`민원건수`/`수급`/`혼잡도`). */
  anlsTrgtNm: string
  /**
   * 값의 단위 (`건`/`%`/`점`). 유형마다 다르므로 화면·범례에 단위를 **박지 않는다**.
   * ⚠️ 미가용 유형에도 채워져 온다 — 세그먼트에 단위를 함께 보여 줄 수 있게 하려는 것이다.
   */
  unit: string
  /** 지금 조회 가능한 유형인지. `false` 면 요청해도 400 이다. */
  available: boolean
  /** 미가용 사유. `available === true` 면 null. */
  unavailableRsn: string | null
  /**
   * 이 유형이 지원하는 격자크기(m) 목록.
   *
   * 🔴 **유형마다 다르다** — 민원건수는 `[1000, 500, 250, 100]`(격자 마스터 보유분), 수급은
   * `[100, 200, 300, 500]`(계산 격자). 격자 탭을 **이 값으로** 그린다. 목록을 화면에 박으면
   * 유형을 바꿔도 탭이 그대로 남아 서버가 400 을 낸다.
   */
  gridSzs: number[]
  /** 기본 격자크기(m). 유형을 바꿀 때 현재 격자가 `gridSzs` 밖이면 이 값으로 떨어진다. */
  dfltGridSz: number
  /**
   * 조회 경로 접미사 — `/analysis/lifezone` 뒤에 붙는다.
   *
   * `''` 면 기본 경로(`fetchLifezoneAnalysis`), `'/supply'` 면 수급 전용 경로
   * (`api/lifezoneSupply.ts` 의 `fetchLifezoneSupply`)다. 🔴 유형→경로 대응을 화면에 박지 않고
   * 이 값으로 고른다 — 유형이 늘어도 화면이 아니라 서버가 정한다.
   */
  anlsPathSfx: string
  /**
   * 이 유형의 원천 보유 시작일 (`YYYYMMDD`). 미구현 유형이면 `null`.
   *
   * ⚠️ **유형마다 다를 수 있다.** 🔁 2026-09-13 에 수급이 `dclr_ymd`(신고일)에서
   * `ocrn_dt`(발생일시)로 옮겨와 지금은 두 유형의 구간이 거의 같지만, 집계 조건이 달라
   * (민원건수는 미분류 제외·좌표 무관 / 수급은 전 유형·좌표 유효) 값이 어긋날 수 있다.
   *
   * 🔴 **그래서 실행 전에 겹치는지 확인해 경고한다.** 기간 세그먼트는 **오늘** 기준이라 보유
   * 구간이 과거로 밀리면 기본값이 그대로 0건이 된다 — 돌린 뒤 "결과 0건" 안내로 알리면
   * 사용자는 화면이 고장 난 것으로 읽는다. (옮기기 전 수급이 정확히 그 상태였다.)
   */
  dataBgngYmd: string | null
  /**
   * 이 유형의 원천 보유 종료일 (`YYYYMMDD`). 미구현 유형이면 `null`.
   * ⚠️ 혼잡도(단속 `crdn_dt`)는 **오늘보다 미래**일 수 있다(원천 데이터 이슈 — 2026-10 실측 `20261030`).
   */
  dataEndYmd: string | null
  /**
   * 이 유형이 허용하는 등급 분류방법 (TSK-4090). 🔁 TSK-4227 — 유형마다 1종이며 화면은 더 이상 선택지를 그리지 않는다.
   * 등급 분류가 없는 유형(수급 — 사분면)은 `[]` 다.
   */
  clsfMthds: LifezoneClsfMthd[]
  /** 기본 분류방법(서버가 생략 시 쓰는 값). 등급 분류가 없는 유형이면 `null`. 🔁 TSK-4227 — 화면은 이 값을 state 로 들지 않는다. */
  dfltClsfMthd: LifezoneClsfMthd | null
}

/**
 * 등급 판정 기준 한 줄 — **범례 문구의 출처**다.
 *
 * 🔴 `lgndTxt`(`심각 (56건 이상)`)를 **서버가 완성해 내린다.** 프론트가 `minVl` 로 문구를
 * 조립하지 않는다 — 경계가 조회마다 달라지고 단위도 유형마다 달라서, 조립을 프론트에 두면
 * 화면·문서·범례가 서로 다른 문구를 갖게 된다. 화면은 이 문자열을 그대로 출력한다.
 *
 * `minVl` 은 정렬·툴팁처럼 숫자 자체가 필요할 때만 쓴다. 상한은 오지 않는다 —
 * 바로 위 등급의 `minVl - 1` 이 상한이며 두 번 적으면 어긋나므로 백엔드가 싣지 않는다.
 */
export interface LifezoneGradeCriterion {
  /** 등급코드. `cells[].grade`·`ranking[].grade` 와 같은 값이라 색·순서를 이 코드로 맞춘다. */
  grade: LifezoneGrade
  /** 화면 표시용 등급명 (`심각`/`경고`/`주의`/`보통`/`낮음`). */
  gradeNm: string
  /** 이 등급으로 판정되는 최소값(이상). 최하위(`LOW`)는 1 — 값 0 인 격자는 그리지 않는다. */
  minVl: number
  /** 범례에 그대로 찍는 문구. **프론트에서 다시 만들지 않는다.** */
  lgndTxt: string
}

/**
 * 구간 경계 산출 정보 — **재현·설명용**이다. 화면 렌더에는 쓰지 않는다.
 *
 * 🔴 경계는 고정 임계값이 아니라 **조회 결과에서 매번 산출**된다. 그래서 같은 격자라도 조회
 * 조건이 바뀌면 등급이 달라진다 — 조회 간 비교는 등급이 아니라 `anlsVl` 자체로 해야 한다.
 * 이 값들은 "왜 이 경계가 나왔나"를 설명할 때 쓴다.
 *
 * ⚠️ **키는 3개 그대로다**(`method`·`cap`·`width`). 분류방법이 늘어도 계약이 넓어지지 않게
 * 방법 자체는 `method` 로만 나간다.
 */
export interface LifezoneGradeScaleInfo {
  /** 구간 분류 방식 식별자 — 응답 루트의 `clsfMthd` 와 **항상 같은 값**이다. */
  method: string
  /** 분류 상한 = 조회 결과의 **최대값**. 분류방법과 무관하다. 결과가 0건이면 0. */
  cap: number
  /**
   * 구간 폭 — 🔴 **등간격에서만 의미가 있다**(경계가 이 값씩 커진다, `ceil(cap / 5)`).
   * 자연분류(`JENKS`)는 간격이 일정하지 않아 **항상 0** 이다.
   */
  width: number
}

/** 순위 행의 유형별 내역 1건 — 상위 2종만 온다. 화면·문서는 `formatDtl` 로 이어 붙인다. */
export interface LifezoneDtl {
  /** 민원유형코드 (공통코드 `CVLCPT_TYPE_CD`). */
  cvlcptTypeCd: string
  /** 민원유형명. 🔴 **프론트에 유형명을 박지 않는다** — 공통코드가 바뀌면 이 값만 따라간다. */
  cvlcptTypeNm: string
  /** 해당 유형의 건수. */
  cnt: number
}

/**
 * 격자 셀 1건 = 지도에 그리는 폴리곤 하나. **값>0 인 격자만** 온다(0 은 아예 싣지 않는다).
 */
export interface LifezoneCell {
  /** 격자코드. 순위 행(`LifezoneRank.gridCd`)과 셀을 잇는 키다(양방향 연동에 쓴다). */
  gridCd: string
  /** 분석값. 단위는 응답 루트의 `unit`(유형마다 다르다). */
  anlsVl: number
  /** 등급코드. 서버가 판정한다 — 프론트에서 다시 계산하지 않는다. */
  grade: LifezoneGrade
  /** 격자 무게중심 위도 (EPSG:4326). */
  ctrLat: number
  /** 격자 무게중심 경도 (EPSG:4326). */
  ctrLot: number
  /**
   * 격자 꼭짓점 링 — `[lat, lot]` **4점**이다. Leaflet `L.polygon` 에 그대로 넣는다.
   *
   * 🔴 **bbox 가 아니라 꼭짓점 링인 이유**: 격자는 미터 좌표계에서 만들어져 EPSG:4326 에서는
   * 축정렬이 아니다. `L.rectangle` 로 그리면 이웃 셀과 겹치거나 벌어진다.
   * 순서는 위경도(`[위도, 경도]`)이며 Leaflet 의 `LatLngExpression` 과 같은 순서다.
   */
  ring: [number, number][]
  /** 격자 중심이 속한 읍면동명. 매칭되지 않으면 null. */
  emdNm: string | null
  /** 구역명. 셀 팝업 제목으로 쓴다 — 폴백이 있어 비지 않는다. */
  cntrPoiNm: string
}

/**
 * 순위 행 1건 — 심각·경고·주의 셀 전부(🔁 TSK-4227 D21 — 15개 상한 없음). `cells` 와 **같은 격자를 가리키지만 다른 객체**다
 * (순위 행만 `rnk`·`dtl` 을 갖고, `cntrPoiNm` 은 이쪽이 VWorld POI 로 더 정확하다).
 * 셀 ↔ 목록 연동은 `gridCd` 로 맞춘다.
 */
export interface LifezoneRank {
  /** 순위 — 분석값 내림차순 1부터. **서버가 매긴다**(프론트 재정렬 금지). */
  rnk: number
  /** 격자코드. 지도 셀(`LifezoneCell.gridCd`)과 잇는 키다. */
  gridCd: string
  /** 구역명 — 상위 행만 VWorld POI 로 대체된다. 폴백이 있어 비지 않는다. */
  cntrPoiNm: string
  /** 분석값. 단위는 응답 루트의 `unit`. */
  anlsVl: number
  /** 등급코드. */
  grade: LifezoneGrade
  /** 격자 무게중심 위도 — **순위 행 클릭 시 지도 이동용**. */
  ctrLat: number
  /** 격자 무게중심 경도 — 위와 같다. */
  ctrLot: number
  /**
   * 유형별 세부 내역 — **최대 2종**의 구조화 배열이다.
   * 화면·문서는 `formatDtl()` 로 ` · ` 이어 붙인다(문자열 조립을 서버가 하지 않는 이유는
   * 화면마다 이어 붙이는 방식이 다를 수 있어서다). 내역이 없으면 빈 배열이다.
   */
  dtl: LifezoneDtl[]
}

/** GET /api/analysis/lifezone 응답 루트. */
export interface LifezoneAnalysisResult {
  /** 서버가 정규화한 분석 시작일 (YYYYMMDD). 요청 문자열이 아니라 적용된 값이다. */
  bgngYmd: string
  /** 서버가 정규화한 분석 종료일 (YYYYMMDD, 당일 포함). */
  endYmd: string
  /** 적용된 시군구명. 전 지역이면 null. */
  sggNm: string | null
  /** 적용된 읍면동코드. 시군구 전체면 null. */
  emdCd: string | null
  /** 적용된 분석내용코드. */
  anlsTrgtCd: LifezoneTargetCd
  /** 적용된 분석유형명 (`민원건수`). 화면 제목·문서가 쓴다. */
  anlsTrgtNm: string
  /** 적용된 격자크기(m). */
  gridSz: LifezoneGridSz
  /** 격자크기 표시명 (`500m`). 세그먼트 라벨을 프론트가 만들지 않는다. */
  gridSzNm: string
  /** 적용된 등급 분류방법. `gradeScale.method` 와 같은 값이다. */
  clsfMthd: LifezoneClsfMthd
  /** 분류방법명 (`자연분류`/`등간격`). 🔴 화면 문구를 프론트에 박지 않고 이 값을 쓴다. */
  clsfMthdNm: string
  /** 값의 단위 (`건`). 🔴 유형마다 다르므로 화면에 단위를 박지 않고 이 값을 쓴다. */
  unit: string
  /** 결과 섹션 제목 (`민원 집중 구역`). 유형에 따라 달라져 서버가 내린다. */
  sectionTitle: string
  /** 결과 섹션 부제 (`격자 기반 민원 집중 구역`). */
  sectionSub: string
  /** 분석유형 가용 목록 — 설정 패널 세그먼트를 이 목록으로 그린다(`/targets` 와 같은 내용). */
  availableTargets: LifezoneTargetInfo[]
  /** 구간 경계 산출 정보 (재현·설명용). */
  gradeScale: LifezoneGradeScaleInfo
  /** 등급 판정 기준 **5종** — `minVl` 내림차순(`SEVERE` → `LOW`). 범례를 이 배열 순서로 세운다. */
  gradeCriteria: LifezoneGradeCriterion[]
  /**
   * 적용 가중치 — **항상 빈 배열**이다. 분석유형을 배타 선택하므로 지표를 섞지 않는다.
   * 키가 내려오는 것은 집중구역과 응답 모양을 맞추기 위함이며, 원소 타입은 아직 정해지지 않았다.
   */
  weights: unknown[]
  /**
   * 값>0 격자 수 = 그리는 셀 수. 등급별 카운트 5개의 합과 같다(R10).
   * 혼잡도도 같다 — 불법주차 근거 없는 격자는 셀·요약에서 제외된다(`LifezoneCngstResult` 참고).
   */
  totGridCnt: number
  /** 심각 등급 격자 수. */
  severeCnt: number
  /** 경고 등급 격자 수. */
  warnCnt: number
  /** 주의 등급 격자 수. */
  cautionCnt: number
  /** 보통 등급 격자 수. */
  normalCnt: number
  /** 낮음 등급 격자 수. */
  lowCnt: number
  /** 원천 보유 시작일 (YYYYMMDD). **0건 안내 문구용**. 원천이 비면 null. */
  dataBgngYmd: string | null
  /** 원천 보유 종료일 (YYYYMMDD). 원천이 비면 null. */
  dataEndYmd: string | null
  /**
   * 격자 셀 **전량**(값>0). 지도 히트맵이 이 배열을 그린다 — `limit` 의 영향을 받지 않는다.
   * ⚠️ **빈 배열도 200 이며 정상 상태다**(조건에 데이터가 없는 경우) — 오류로 처리하지 말고
   * `dataBgngYmd`~`dataEndYmd` 를 곁들인 안내를 띄운다.
   */
  cells: LifezoneCell[]
  /** 순위 목록 — 심각·경고·주의 셀 전부(분석값 내림차순 · 🔁 TSK-4227 D21 — 15개 상한 없음). 서버 정렬 순서를 그대로 렌더링한다. */
  ranking: LifezoneRank[]
}

/* ── 4. 조회 함수 ─────────────────────────────────────── */

/**
 * 분석유형 가용 목록 조회 — 설정 패널이 마운트 시 1회 부른다.
 *
 * 진입 직후에는 분석을 자동으로 돌리지 않으므로, 세그먼트를 그리는 데 필요한 정보만 따로 받는다
 * (`/api/regions` 와 같은 성격의 경량 조회다). 분석을 한 번 돌리고 나면 같은 목록이
 * `LifezoneAnalysisResult.availableTargets` 로도 오므로 재호출할 필요가 없다.
 */
export async function fetchLifezoneTargets(): Promise<LifezoneTargetInfo[]> {
  const { data } = await api.get<LifezoneTargetInfo[]>('/analysis/lifezone/targets')
  return data
}

/**
 * 생활권 격자 분석 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지).
 *
 * 🔴 `sggNm`·`emdCd` 는 **값이 있을 때만** 쿼리에 얹는다 — `''`(전체)를 그대로 보내면 400 이기
 * 때문이다. 페이지가 `전체` 를 `''` 로 들고 있어도 여기서 끊어 주므로 화면마다 같은 방어를 반복하지 않는다.
 * `anlsTrgtCd`·`gridSz`·`clsfMthd`·`limit` 도 값이 있을 때만 얹고, 없으면 서버 기본값
 * (`CVLCPT_CNT`·`500`·유형 기본 분류방법·순위 상한 없음)을 따른다 — 기본값을 프론트에 복제하지 않으려는 것이다.
 *
 * 오류: bgngYmd/endYmd 형식 오류·역전 · 빈 sggNm/emdCd · 미등록 시군구 · 미존재 읍면동 ·
 * 미구현 분석유형 · 미지원 격자크기 · 미지원 분류방법 · limit 0 이하 → 400.
 */
export async function fetchLifezoneAnalysis(
  params: LifezoneAnalysisParams,
): Promise<LifezoneAnalysisResult> {
  const query: Record<string, string | number> = {
    bgngYmd: params.bgngYmd,
    endYmd: params.endYmd,
  }
  if (params.anlsTrgtCd) query.anlsTrgtCd = params.anlsTrgtCd
  if (params.gridSz) query.gridSz = params.gridSz
  if (params.clsfMthd) query.clsfMthd = params.clsfMthd
  if (params.sggNm) query.sggNm = params.sggNm
  if (params.emdCd) query.emdCd = params.emdCd
  if (params.limit) query.limit = params.limit

  const { data } = await api.get<LifezoneAnalysisResult>('/analysis/lifezone', {
    params: query,
    timeout: LIFEZONE_ANALYSIS_TIMEOUT_MS,
  })
  return data
}

/* ── 4-1. 혼잡도 (TSK-4090) ──────────────────────────── */

/**
 * 혼잡도 셀 1칸 — 민원건수 셀을 **확장**한다(백엔드 `LifezoneCngstCellVO extends LifezoneCellVO`).
 *
 * 상속 필드 중 `anlsVl` 은 **혼잡 점수 × 100 정수**다(점수 = 지수 × 100 · 상한 없음 · 8500 = 85점 · 10000 = 100점)
 * — 표시는 `formatCngstVl`. 점수 = 지수 × 100 이라 원 지수 필드는 없다(2026-10-08 ⑪ 제거).
 * 불법주차 근거(단속·민원)가 없는 격자는 응답에 없다(2026-10-08).
 */
export interface LifezoneCngstCell extends LifezoneCell {
  /** 공영주차면 합계 S (항상 > 0 — 공급 0 격자는 응답에 없다). */
  pkltCpctySum: number
  /** 공영주차장 수. */
  pkltCnt: number
  /** 단속 건수(전 수단 · 민원 기원 포함, 가중치 없는 원 건수). */
  crdnCnt: number
  /** 같은 기간 창의 민원건수. */
  cvlcptCnt: number
  /** 초과주차 추정량(대, 소수 1). */
  excsPrkPrsmpVl: number
  /**
   * 불법주차 근거 없음 여부. 그런 칸은 응답에서 제외되므로 **항상 `'N'`** 이다.
   * 🔴 필드는 혼잡도 셀 타입가드(`isCngstCell` — `'noEnfYn' in c`)가 쓰므로 지우지 않는다.
   */
  noEnfYn: 'Y' | 'N'
  /**
   * 면수 하한(`MIN_CPCTY_VL`) 미만 여부. 그런 칸은 등급 계산 전에 응답에서 제외되므로(2026-10-08)
   * **항상 `'N'`** 이다(하위호환 필드).
   */
  refYn: 'Y' | 'N'
}

/**
 * GET /api/analysis/lifezone/congestion 응답 — 민원건수 응답의 필드를 **같은 이름**으로 싣고
 * 혼잡도 요약을 루트에 더한다(백엔드 `LifezoneCngstResultVO`).
 *
 * 🔴 `totGridCnt` = 5등급 합 = `grdTrgtGridCnt` = `cells.length`(R10 성립 — 불법주차 근거 없음·면수 하한 미만 격자 제외).
 * `noEnfGridCnt`·`refGridCnt` 는 그렇게 **제외된 격자 수**다(겹치면 noEnf 로 센다 — 셋의 합 = 공급>0 격자 수). `gradeCriteria[].minVl`·`gradeScale.cap` 은 점수 × 100 정수다(최대 10000).
 */
export interface LifezoneCngstResult extends LifezoneAnalysisResult {
  cells: LifezoneCngstCell[]
  /** 이 유형이 허용하는 분류방법(확인용 — UI 는 `/targets` 의 `clsfMthds` 로 그린다). */
  availableClsfMthds: LifezoneClsfMthd[]
  /** 등급 대상 칸 수 = 5등급 합 = `totGridCnt`(하위호환으로 유지). */
  grdTrgtGridCnt: number
  /** 제외된 격자 수 — 공급>0 이지만 단속·민원이 모두 0 이라 불법주차 근거가 없어 `cells` 에서 빠진 칸. */
  noEnfGridCnt: number
  /** 면수 하한(`MIN_CPCTY_VL`) 미만으로 `cells` 에서 빠진 칸 수(근거 없음과 겹치면 `noEnfGridCnt` 쪽으로 센다). */
  refGridCnt: number
  /** 100점 초과(`anlsVl > 10000` — 수요가 면수 초과) 칸 수. 🔴 `occUsedVl` 과 반드시 함께 읽는다(설정값의 함수다). */
  over1GridCnt: number
  /** 이번 계산에 쓴 기준 점유율(설정값 `PCSHR_CRTR_VL` — 🆕 TSK-4325 일시 조정 시 적용값). */
  occUsedVl: number
  /**
   * 🆕 혼잡도 10행의 적용 내역(TSK-4325 D10). 구 스냅샷은 `null`. 문서에는 그리지 않는다.
   * 화면 배너는 이 값이 아니라 계수 훅 상태로 그린다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
}

/**
 * 혼잡도 조회 파라미터 — 민원건수와 같다(`anlsTrgtCd` 는 경로가 정하므로 보내지 않는다).
 * 🆕 TSK-4325 — `vrblVls`(혼잡도 계수 일시 조정)를 더 받는다. 🔴 민원 건수 API 는 받지 않는다(D12).
 */
export type LifezoneCngstParams = Omit<LifezoneAnalysisParams, 'anlsTrgtCd'> & {
  /** 혼잡도 계수 일시 조정. 생략·빈 객체 = 설정값. 경계 4개는 묶음 전체(D21). */
  vrblVls?: VrblVls
}

/**
 * 혼잡도 격자 조회. 빈 값 처리 규약은 `fetchLifezoneAnalysis` 와 같다(`''` 지역은 쿼리에서 뺀다).
 *
 * 오류: 민원건수와 같은 400 목록 + 허용 밖 분류방법(`EQUAL_INTERVAL` 등) → 400.
 */
export async function fetchLifezoneCongestion(
  params: LifezoneCngstParams,
): Promise<LifezoneCngstResult> {
  const query: Record<string, string | number> = {
    bgngYmd: params.bgngYmd,
    endYmd: params.endYmd,
  }
  if (params.gridSz) query.gridSz = params.gridSz
  if (params.clsfMthd) query.clsfMthd = params.clsfMthd
  if (params.sggNm) query.sggNm = params.sggNm
  if (params.emdCd) query.emdCd = params.emdCd
  if (params.limit) query.limit = params.limit
  // GET 은 JSON 문자열 1개(TSK-4325 D1). 비면 키를 뺀다.
  const vrblVls = vrblVlsQuery(params.vrblVls)
  if (vrblVls) query.vrblVls = vrblVls

  const { data } = await api.get<LifezoneCngstResult>('/analysis/lifezone/congestion', {
    params: query,
    timeout: LIFEZONE_ANALYSIS_TIMEOUT_MS,
  })
  return data
}

/** 혼잡도 보고서 생성 요청 — 조회 조건 + 파일형식. 분류방법은 1종이라 보내지 않는다(TSK-4317). */
export type LifezoneCngstReportCreateParams = Pick<
  LifezoneReportCreateParams,
  'bgngYmd' | 'endYmd' | 'sggNm' | 'emdCd' | 'gridSz' | 'fileFmt'
> & {
  /** 🆕 혼잡도 계수 일시 조정(TSK-4325) — 🔴 결과를 만든 실행의 값을 보낸다(D7). */
  vrblVls?: VrblVls
}

/**
 * 엑셀 분석 계수 1행 (`wbk.vrbls[]` — 백엔드 `AnlsWorkbookVrblVO`, TSK-4340 D4). 수급·혼잡도 공용.
 * 🔴 엑셀 입력 셀 초기값의 유일한 출처는 `aplcnVl`(이번 실행이 실제로 쓴 값)이다 — 팝오버 상태·설정 API 를 따로 읽지 않는다.
 */
export interface LifezoneWorkbookVrbl {
  vrblCd: string
  /** 변수명 — 엑셀 라벨(설정 화면과 같은 이름). */
  vrblNm: string
  /** 적용값 — 엑셀 입력 셀 초기값. */
  aplcnVl: number
  /** 최솟값 — 🔴 `null` 일 수 있다(유효성에서 그 쪽 범위를 뺀다). */
  minVl: number | null
  /** 최댓값 — 🔴 `null` 일 수 있다. */
  maxVl: number | null
  /** 정수 전용 여부. */
  intOnlyYn: 'Y' | 'N'
  /**
   * 입력 셀 여부 — `Y` 노란 입력 셀 · `N` 회색 조건 셀(설정 행 없음 · 🆕 TSK-4346 숨김 계수 `expsr_yn='N'`).
   * 🔴 판정은 서버(`LifezoneWorkbookVrbls.of`)가 했다 — 프론트가 다시 정하지 않는다.
   */
  inptYn: 'Y' | 'N'
}

/** 혼잡도 엑셀 원자료 1행 (`wbk.cells[]` — 백엔드 `LifezoneCngstWorkbookCellVO`). 공급>0 격자 전부 · `gx, gy` 순. */
export interface LifezoneCngstWorkbookCell {
  gridCd: string
  /** 구역명 — 없으면 키 생략. */
  cntrPoiNm?: string | null
  emdNm?: string | null
  pkltCpctySum: number
  pkltCnt: number
  /** 고정형 단속 건수. */
  nFixed: number
  /** PDA 단속 건수. */
  nPda: number
  /** 수동 단속 건수(민원 기원 포함). */
  nManual: number
  cvlcptCnt: number
}

/**
 * 혼잡도 보고서의 엑셀 재계산 데이터 (`rslt_json.wbk` — 백엔드 `LifezoneCngstWorkbookVO`, TSK-4340 D6·D7).
 * 생성 응답·스냅샷에 실리며 TSK-4340 이전 보고서는 `null`/없음이다(D7-1).
 */
export interface LifezoneCngstWorkbook {
  /** 개월수 — 부분월 일수 비례 · 반올림 없음(double 그대로). */
  mnthCnt: number
  bgngYmd: string
  endYmd: string
  vrbls: LifezoneWorkbookVrbl[]
  cells: LifezoneCngstWorkbookCell[]
}

/** POST /api/analysis/lifezone/congestion/reports 응답 = 혼잡도 분석 응답 + 보고서 헤더. */
export interface LifezoneCngstReportResponse
  extends LifezoneCngstResult,
    Pick<
      LifezoneReportResponse,
      'rptpId' | 'excnId' | 'rptpNm' | 'regionNm' | 'anlsPrdExpln' | 'fileFmt' | 'crtYmd'
    > {
  /** 🆕 엑셀 재계산 데이터(TSK-4340) — 형식과 무관하게 새 보고서 전부에 실린다. 구 스냅샷은 `null`/없음. */
  wbk?: LifezoneCngstWorkbook | null
}

/**
 * 생활권 혼잡도 보고서 생성 (201 · TSK-4317). 서버가 같은 조건으로 다시 분석해 저장한다.
 *
 * 🔴 재분석이 돌아 공용 기본 타임아웃 10초로는 정상 요청도 실패한다 — `LIFEZONE_ANALYSIS_TIMEOUT_MS` 를 건다.
 * 🔴 `전체` 지역은 키를 넣지 않는다(`sggNm: ''` 는 400).
 */
export async function createLifezoneCngstReport(
  params: LifezoneCngstReportCreateParams,
): Promise<LifezoneCngstReportResponse> {
  const body: Record<string, string | number | VrblVls> = {
    bgngYmd: params.bgngYmd,
    endYmd: params.endYmd,
    fileFmt: params.fileFmt,
  }
  if (params.sggNm) body.sggNm = params.sggNm
  if (params.emdCd) body.emdCd = params.emdCd
  if (params.gridSz) body.gridSz = params.gridSz
  // POST 는 바디 객체(D1). 비면 키를 뺀다.
  const vrblVls = vrblVlsBody(params.vrblVls)
  if (vrblVls) body.vrblVls = vrblVls

  const { data } = await api.post<LifezoneCngstReportResponse>(
    '/analysis/lifezone/congestion/reports',
    body,
    { timeout: LIFEZONE_ANALYSIS_TIMEOUT_MS },
  )
  return data
}

/* ── 5. 표시 헬퍼 ─────────────────────────────────────── */

/**
 * 순위 행의 세부 내역을 한 줄 문자열로 잇는다 — `{유형명} {건수}` 를 ` · ` 로 잇는다.
 *
 * 🔴 **유형명을 이 함수가 알지 못한다.** 이름·건수 모두 응답값(`cvlcptTypeNm`·`cnt`)이라
 * 공통코드가 바뀌면 문구가 저절로 따라간다 — 프론트에 유형 목록을 박지 않기 위한 형태다.
 *
 * ⚠️ 단위(`건`)를 여기에 붙이지 않는다 — 분석유형마다 단위가 달라(`건`/`%`/`점`) 호출부가
 * 응답 루트의 `unit` 을 곁들여야 하기 때문이다. 이 함수는 `{유형명} {건수}` 만 만든다.
 * 내역이 비면 빈 문자열을 돌려주므로 호출부에서 그대로 렌더링해도 안전하다.
 */
export function formatDtl(dtl: LifezoneDtl[]): string {
  return dtl.map((d) => `${d.cvlcptTypeNm} ${d.cnt}`).join(' · ')
}

/* ── 6. 보고서 저장 (POST) ────────────────────────────── */

/**
 * 내보낼 파일형식. 저장 행의 `file_fmt` 로 그대로 남으며 그 밖의 값이면 **400** 이다.
 * 🔴 문서를 만드는 것은 프론트다 — 서버는 파일을 굽지 않고 "무엇으로 내보냈는지" 만 기록한다.
 */
export type LifezoneFileFmt = 'PDF' | 'DOCX' | 'XLSX'

/**
 * POST /api/analysis/lifezone/reports 요청 바디.
 *
 * 🔴 **건수·등급을 보내지 않는다**(Step 11 D12). 서버가 같은 조건으로 **재분석**해 그 결과를
 * 저장하므로, 화면이 계산한 숫자를 실을 자리가 애초에 없다 — 화면의 오류가 보고서에 영구 보존되는
 * 것을 막는 설계다. 그래서 이 바디는 조회 파라미터 + `fileFmt` 다.
 *
 * 🔴 **필드명은 `fileFmt` 다**(`fileFmtCd` 가 아니다 — 그러면 `@NotBlank` 에 걸려 400).
 *
 * 🔴 `sggNm`·`emdCd` 는 조회와 같은 규약이다 — **`전체` 는 키를 아예 넣지 않는다.**
 * 빈 문자열·`null` 은 400 이며, 제거는 아래 `createLifezoneReport` 가 책임진다.
 */
export interface LifezoneReportCreateParams {
  /** 분석 시작일 (YYYYMMDD, 필수). */
  bgngYmd: string
  /** 분석 종료일 (YYYYMMDD, 필수 · 당일 포함). */
  endYmd: string
  /** 시군구명. 전 지역이면 `null`·생략. */
  sggNm?: string | null
  /** 읍면동코드. 시군구 전체면 `null`·생략. */
  emdCd?: string | null
  /** 분석내용코드. 생략 = 서버 기본값 `CVLCPT_CNT`. */
  anlsTrgtCd?: LifezoneTargetCd
  /** 격자크기(m). 생략 = 서버 기본값 `500`. */
  gridSz?: LifezoneGridSz
  /** 등급 분류방법. 🔁 TSK-4227 — 화면은 보내지 않는다. 생략 = 유형 기본값(민원건수 `EQUAL_INTERVAL` · 혼잡도 `CNGST_ABSOLUTE`). */
  clsfMthd?: LifezoneClsfMthd
  /** 순위 개수. 생략 = 상한 없음(심각·경고·주의 전부 — TSK-4227 D21). 조회와 마찬가지로 **보내지 않는 것이 기본**이다. */
  limit?: number
  /** 파일형식 (필수). `PDF` | `DOCX`. */
  fileFmt: LifezoneFileFmt
}

/**
 * POST /api/analysis/lifezone/reports 응답 = **저장 스냅샷**.
 *
 * 분석 조회 응답(`LifezoneAnalysisResult`)에 **보고서 헤더 7칸**이 더해진 모양이다 —
 * `GET /api/reports/{rptpId}/lifezone`(재다운로드)도 같은 형식으로 온다.
 *
 * ⚠️ 저장 응답에서 `dataBgngYmd`·`dataEndYmd` 는 **의미가 없다**(스냅샷 조회에서는 `null` 로 온다) —
 * 원천 보유 구간은 "지금" 의 값이라 스냅샷이 아니기 때문이다. 0건 안내 전용 칸이며 저장 경로는
 * 0건이 400 이라 애초에 닿지 않는다.
 */
export interface LifezoneReportResponse extends LifezoneAnalysisResult {
  /** 보고서아이디. 목록 행(`ReportListItem.rptpId`)과 같은 값이다. */
  rptpId: number
  /** 분석실행아이디. */
  excnId: number
  /** 보고서명 `생활권 {분석유형명} 보고서 {지역표시명} {격자}`(D14). **다운로드 파일명이 된다.** */
  rptpNm: string
  /** 지역 표시명 (`전체 지역` | `{시군구} 전체` | `{시군구} {읍면동}`). */
  regionNm: string
  /** 분석기간설명 (`2026.08.04 ~ 2026.09.30`). */
  anlsPrdExpln: string
  /** 저장된 파일형식. 요청한 `fileFmt` 그대로다. */
  fileFmt: LifezoneFileFmt
  /** 생성일시 (ISO-8601 `2026-08-31T14:03:11`). */
  crtYmd: string
}

/**
 * 생활권 시뮬레이션 보고서 생성 (201) — **내보내기 1회 = 보고서 1건**이다.
 *
 * 실행 이력(`TB_ANLS_EXCN`) 1행 · 분석 결과(`TB_ANLS_RSLT`) 1행 · 보고서(`TB_RPTP`) 1행을
 * 한 트랜잭션으로 남기고 저장된 스냅샷을 그대로 돌려준다. 응답으로 문서를 만들면
 * **화면 · 파일 · DB 가 같은 숫자**를 보게 된다.
 *
 * 🔴 빈 값을 그대로 보내지 않는다 — 조회(`fetchLifezoneAnalysis`)와 **같은 규약**이며
 * `sggNm: ''`(전체)를 실으면 400 이다. 화면이 `전체` 를 `''`·`null` 어느 쪽으로 들고 있어도
 * 여기서 끊어 주므로 호출부마다 같은 방어를 반복하지 않는다.
 *
 * 오류: 조회와 같은 400 목록(기간 형식·역전 · 빈/미등록 지역 · 미구현 분석유형 · 미지원 격자·분류방법 ·
 * limit 0 이하) + `fileFmt` 가 `PDF`/`DOCX` 가 아닐 때 + **분석 결과 0건**(열 수 없는 빈 보고서를
 * 목록에 남기지 않는다).
 */
export async function createLifezoneReport(
  params: LifezoneReportCreateParams,
): Promise<LifezoneReportResponse> {
  const body: Record<string, string | number> = {
    bgngYmd: params.bgngYmd,
    endYmd: params.endYmd,
    fileFmt: params.fileFmt,
  }
  if (params.sggNm) body.sggNm = params.sggNm
  if (params.emdCd) body.emdCd = params.emdCd
  if (params.anlsTrgtCd) body.anlsTrgtCd = params.anlsTrgtCd
  if (params.gridSz) body.gridSz = params.gridSz
  if (params.clsfMthd) body.clsfMthd = params.clsfMthd
  if (params.limit) body.limit = params.limit

  const { data } = await api.post<LifezoneReportResponse>('/analysis/lifezone/reports', body, {
    timeout: LIFEZONE_ANALYSIS_TIMEOUT_MS,
  })
  return data
}
