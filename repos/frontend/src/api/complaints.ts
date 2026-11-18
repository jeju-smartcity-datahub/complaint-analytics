import { api } from './client'

/*
 * 민원현황(GIS) 핵심 API + 부속 3종 클라이언트 (TSK-3880 §1, TSK-3869 §1).
 *
 * 백엔드 계약:
 *   GET /api/complaints/gis        → ComplaintKpiResponse  (§1 Header 의 lastSyncDt + §3 KPI 5필드 + §7·§8)
 *     ⚠️ `lastSyncDt` 는 화면이 더 이상 읽지 않는다(TSK-4256 — 부제는 `DataFreshness`). 필드는 계약이라 남긴다.
 *   GET /api/hotspots/ranking      → HotspotRankingResultVO  (§6 GIS 분석레이어 순위, view 3종)
 *   GET /api/hotspots/heatmap      → HotspotHeatmapResultVO  (§6 GIS 히트맵 좌표 칸, view 2종 — TSK-4295)
 *   GET /api/parking-lots          → ParkingLotItem[]       (§6 GIS 공영주차장 마커)
 *   GET /api/admin-districts       → GeoJSON FeatureCollection (§4 행정동 경계, gzip 응답)
 *
 * ⚠️ 이 라운드(§1)에서는 `lastSyncDt` 하나만 사용한다 — 본문(KPI·지도·패널)은 §2 이후.
 *    (🔁 TSK-4256 — 이제 화면은 `lastSyncDt` 를 더 이상 읽지 않는다.)
 *    응답 타입은 envelope 전체를 노출해서 §2 에서 별도 import 변경 없이 바로 쓸 수 있게 한다.
 */

/* ─────────────────────────────────────────────────────────────
 * 공통 쿼리 파라미터
 * ───────────────────────────────────────────────────────────── */

/** 조회 단위 enum. 백엔드 표준. */
export type ComplaintUnit = 'YESTERDAY' | 'WEEK' | 'MONTH'

/** 민원현황(GIS) 공통 조회 파라미터. 핵심 API / 부속 3종 모두 같은 파라미터를 받는다. */
export interface ComplaintQuery {
  /** 조회 단위. 생략 시 `YESTERDAY`. */
  unit?: ComplaintUnit
  /** 기간 시작 `YYYY-MM-DD`. `startDt`+`endDt` 가 모두 있으면 `unit` 보다 우선. */
  startDt?: string
  /** 기간 종료 `YYYY-MM-DD`. `startDt`+`endDt` 가 모두 있으면 `unit` 보다 우선. */
  endDt?: string
  /** 시군구 5자리 (50110=제주시, 50130=서귀포시). */
  sggCd?: string
  /** 읍면동 8자리. */
  emdCd?: string
  /** 민원유형 공통코드 01~05. */
  cvlcptTypeCd?: string
}

/* ─────────────────────────────────────────────────────────────
 * §3 KPI 응답 (TSK-3880 §3 · §7 · §8 통합)
 * ───────────────────────────────────────────────────────────── */

/** 민원 감정 분포. `negative` / `positive` / `neutral` 합이 `totalCount` 와 같지는 않을 수 있다 — 미분류 제외. */
export interface SentimentDist {
  positive: number
  neutral: number
  negative: number
}

/** §7 읍면동 비교(민원 다발 순위) 행. */
export interface EmdCompareItem {
  /** 1-base rank. */
  rank: number
  /** 읍면동 코드. */
  emdCd: string
  /** 읍면동명 (TB_ADMDST 매핑). */
  emdNm: string
  /** 현 기간 민원 건수. */
  cvlcptCnt: number
  /**
   * 직전 동일 길이 기간 민원 건수 (TSK-4311 — 문서 §3 `전기 민원` 열).
   * 🔴 이 키가 없는 구 스냅샷이 있다 — 0 으로 대체하지 않는다(0 은 실제 0건이다).
   */
  prevCnt?: number | null
  /** 전기 대비 증감률(%). 직전 0이면 null → 서비스에서 `null` 그대로 두고 UI 만 표시. */
  deltaPct: number | null
  /** 해당 zone 의 최다 민원유형 코드. */
  topTypeCd: string | null
  /** 최다 민원유형명. */
  topTypeNm: string | null
  /**
   * 최다 유형이 차지하는 비율. 🔴 **0.0~1.0 이다 — 퍼센트가 아니다**(2026-09-21 정정).
   * 종전 주석이 `(%)` 라 적혀 있었으나 백엔드 `EmdCompareVO.topTypeRatio` 는 비율이고
   * `EmdCompareCard`·보고서 템플릿 모두 `×100` 해서 쓴다. 곱하지 않으면 `42%` 가 `0.42%` 로 나온다.
   */
  topTypeRatio: number | null
  /** `deltaPct` 가 추론값인지 (직전 0건 케이스). */
  deltaPctInferred: boolean
}

/** §8 부정 감정 상위 지역 행. */
/** §8 감성 분포는 §3 KPI의 공통 `SentimentDist` 타입을 재사용한다. */

export interface NegEmdCompareItem {
  rank: number
  emdCd: string
  emdNm: string
  /** 부정 감정 민원 건수. 가이드 §8 envelope 응답 키 `negativeCount`. */
  negativeCount: number
  /** 직전 동일 길이 부정 감정 건수. deltaPct 분자. */
  prevNegativeCount: number
  /** 그 zone 의 감성 분석 완료 distinct 민원 건수 (= 부정 비율 분모). */
  totalCount: number
  /** 감성 분포 (시안 sentiment bar 원천). */
  sentimentDist: SentimentDist
  /** zone 전체 민원 중 부정이 차지하는 비율 (0.0~1.0). 직전 0건이면 null. */
  negativeRatio: number | null
  deltaPct: number | null
  deltaPctInferred: boolean
}

/**
 * 보고서 §4 — 읍·면·동 단속 순위 1행 (TSK-4197 D7).
 * 백엔드 `CrdnCompareVO`(record 10칸)와 **1:1** 이다.
 *
 * 🔴 조회 응답의 `HotspotRankingZone` 을 재사용하지 않는다 — 그쪽엔 `bndryGeoJson`(수 MB)이
 * 붙어 있어 스냅샷 1건이 수 MB 가 된다. 보고서가 그리는 칸만 담은 경량 타입이다.
 */
export interface CrdnCompareItem {
  /** 1-base 순위. 🔴 SQL 이 `RANK()` 라 **동률은 같은 순위**이고 다음 순위가 건너뛴다(1,1,3 — D13). */
  rank: number
  /** 읍면동 코드. */
  emdCd: string
  /** 읍면동명 (TB_ADMDST 매핑). */
  emdNm: string
  /** 현 기간 단속 건수(정렬 기준). */
  crdnCnt: number
  /** 같은 동·같은 기간 민원 건수 — 🔁 TSK-4322 이후 구 스냅샷의 `세부 내역` 표시에만 쓴다. */
  cvlcptCnt: number
  /** 전기 대비 단속 증감률(%). 직전 0건이면 분모 1 치환 + `deltaPctInferred`. 산출 불가면 `null`. */
  deltaPct: number | null
  /** "직전 0건이라 분모를 1 로 치환했다". 🔴 `ShortageCompareItem` 쪽과 의미가 다르다(D12). */
  deltaPctInferred: boolean
  /** 전기 단속 건수(TSK-4311 — 문서 §4). 🔴 구 스냅샷에는 없다 — 0 으로 대체하지 않는다. */
  prevCrdnCnt?: number | null
  /** 최다 단속유형(`crdn_mthd` 원문, TSK-4322 — 문서 §4 `세부 내역`). 🔴 구 스냅샷에는 없다. */
  topCrdnMthd?: string | null
  /** 최다 단속유형 비율(0~1, TSK-4322). 🔴 구 스냅샷에는 없다. */
  topCrdnMthdRatio?: number | null
}

/**
 * 보고서 §5 — 읍·면·동 수요부족 순위 1행 (TSK-4197 D7).
 * 백엔드 `ShortageCompareVO`(record 9칸)와 **1:1** 이다.
 *
 * 🔴 **`shortageRatio` 와 `shortageDeltaPp` 는 단위가 다르다** — 아래 각 칸 주석 참고.
 */
export interface ShortageCompareItem {
  /** 1-base 순위. `RANK()` 라 동률은 같은 순위다(D13). */
  rank: number
  /** 읍면동 코드. */
  emdCd: string
  /** 읍면동명. */
  emdNm: string
  /**
   * 부족률. 🔴 **0~1 소수다 — 퍼센트가 아니다.** 화면·문서가 표시할 때 **×100** 한다
   * (`0.7838` → `78%`). 분모 0 이면 `null` → `—`(0% 로 쓰면 "부족 없음" 으로 읽혀 뜻이 반대다).
   */
  shortageRatio: number | null
  /** 공급 면수. `0` 은 정상값(공영주차장 없음). */
  pkltCpctySum: number
  /** 주차 수요 지수 = 공급 면수 + 주차 관련 민원 수. 면+건이라 단위가 없다. */
  demandIndex: number
  /**
   * 전기 대비 부족률 증감. 🔴 **이미 %p 단위**(소수 1자리)라 **다시 ×100 하지 않는다**.
   * 직전 분모가 0 이면 `null`.
   */
  shortageDeltaPp: number | null
  /** "직전 분모가 0 이라 `shortageDeltaPp` 를 낼 수 없었다". 🔴 `CrdnCompareItem` 과 의미가 다르다(D12). */
  deltaPctInferred: boolean
  /**
   * 전기 부족률(TSK-4311 — 문서 §5). 🔴 `shortageRatio` 와 같은 **0~1 소수**라 표시할 때 ×100 한다.
   * 직전 분모가 0 이거나 구 스냅샷이면 없다 → `—`.
   */
  prevShortageRatio?: number | null
}

/**
 * `GET /api/complaints/gis` 응답. §1 헤더의 `lastSyncDt` + §3 KPI 5필드 + §7·§8.
 * ⚠️ `lastSyncDt` 는 화면이 더 이상 읽지 않는다(TSK-4256) — 필드는 지우지 않는다.
 */
export interface ComplaintKpiResponse {
  /** 마지막 일일 동기화 시각. 헤더 부제의 원천. */
  lastSyncDt: string
  /** `cvlcpt_type_cd <> '99'` 자동 적용된 총 민원 건수. */
  totalCount: number
  /** `sentimentDist.negative / (positive + neutral + negative)` */
  negativityRatio: number
  sentimentDist: SentimentDist
  /** 단속 집중 지역 수 — 🔁 2026-09-30 TSK-4167 D16: 단속이 있는 동 중 **심각 등급**(rank 상위 10%) 수. 리스트의 심각 행 수와 같다. */
  enforcementCount: number
  /** 수요 부족 지역 수 — 🔁 2026-10-02 TSK-4197 D14: **주차민원 > 0 인 전 zone** 중 `FLOOR(모집단 × 0.1)` 인 동 수(= 리스트의 `심각` 행 수).
   *  (⚠️ 옛 정의 둘은 모두 폐기 — `민원 0건 zone`(~2026-09-04) · `Jenks 심각 안의 상위 10%`(~2026-10-02)) */
  shortageCount: number
  emdCompare: EmdCompareItem[]
  negEmdCompare: NegEmdCompareItem[]
  /**
   * 서버가 **실제로 적용한** 조회 시작일 `YYYYMMDD` (TSK-4164 D3).
   *
   * 화면의 조회 단위는 `YESTERDAY`/`WEEK`/`MONTH` 라 프론트가 실제 날짜를 모른다 —
   * 내보내기(`createComplaintReport`) 는 이 두 칸을 **그대로 되보낸다**.
   * 직접 계산하면 자정을 넘길 때 화면이 본 구간과 저장된 구간이 갈린다.
   */
  bgngYmd: string
  /** 서버가 실제로 적용한 조회 종료일 `YYYYMMDD` (당일 포함, TSK-4164 D3). */
  endYmd: string
}

/* ─────────────────────────────────────────────────────────────
 * §6 GIS 부속 API 응답 — 타입만 노출, 화면 호출은 §2 이후 라운드
 * ───────────────────────────────────────────────────────────── */

export type HotspotView = 'most-complaint' | 'enforcement' | 'shortage'

export interface HotspotRankingZone {
  /** 1-base 순위. 🔁 **2026-10-02 TSK-4197 — 세 view 모두 `RANK()` 다**(종전 `ROW_NUMBER()`).
   *  🔴 **같은 값이면 같은 순위**라 번호가 건너뛰고(1,1,3) 배열 index 와 어긋난다 — `rank` 를
   *  연속 번호로 가정하지 않는다.
   *  연혁: 2026-09-03 enforcement 추가 · 2026-09-04 most-complaint 를 tb_cvlcpt 직접 집계로 전환 ·
   *  2026-09-04 shortage view 추가(당시엔 Jenks "심각" 등급 zone 만 노출 — 2026-10-02 폐기). */
  rank: number
  zoneNm: string | null
  emdCd: string | null
  cvlcptCnt: number
  crdnCnt: number
  /** 최다 단속유형(`crdn_mthd` 원문, TSK-4322). `view=enforcement` 만 채우고 그 외 `null`. */
  topCrdnMthd: string | null
  /** 최다 단속유형 비율(0~1) = 그 유형 건수 ÷ `crdnCnt`. `view=enforcement` 만 채우고 그 외 `null`. */
  topCrdnMthdRatio: number | null
  mrkLat: number | null
  mrkLot: number | null
  /**
   * 행정동 경계 polygon (2026-09-08 추가, TSK-4125).
   * PostGIS `ST_AsGeoJSON(tb_admdst.bndry_geom)` 결과 — JSON 문자열로 받는다.
   * 매핑 실패 zone (`tb_admdst.bndry_geom IS NULL` 또는 admdst 미등록) 은 `null`.
   * 프런트에서는 `null` 이면 polygon 만 생략한다 — 중심점 라벨 마커(`mrkLat`/`mrkLot`)는
   * polygon 유무와 무관하게 항상 그린다(TSK-4165 ⑦).
   */
  bndryGeoJson: string | null
  /**
   * 민원유형별 건수 (TSK-4165 D2). `most-complaint` 만 채워지고 다른 view 는 빈 배열
   * (`null` 아님). 순서는 백엔드 `sort_seq` 순이다 — 프론트에서 다시 정렬하지 않는다.
   */
  typeCnts: CvlcptTypeCnt[]
  /** 공급 면수 (TSK-4165 D3). `shortage` 만 값이 있고 나머지 view 는 `null`. 0 은 정상값. */
  pkltCpctySum: number | null
  /** 주차 수요 = 공급 면수 + 주차 민원 수 (D3, 단위 없음). `shortage` 외 `null`. */
  demandIndex: number | null
  /** 부족률 = 주차 민원 수 ÷ 주차 수요 (D3, 0~1). 화면은 ×100 정수 반올림 %. `shortage` 외 `null`. */
  shortageRatio: number | null
  /**
   * 단속 건수 증감률 % (TSK-4197 Step 2). 🔴 **`enforcement` 전용** — `shortage` ·
   * `most-complaint` 는 항상 `null`. 직전 동일 길이 구간 대비이며 정수(소수 자리 없음).
   */
  deltaPct: number | null
  /**
   * 부족률 증감 %p (TSK-4197 Step 2). 🔴 **`shortage` 전용** — 다른 view 는 항상 `null`.
   * 소수 1자리. 직전 분모(공급 + 민원)가 0 이면 `null` 이고 `deltaPctInferred = true` (D12).
   */
  shortageDeltaPp: number | null
  /**
   * 증감값이 추론값인지 (TSK-4197 Step 2).
   * 🔴 **view 마다 의미가 다르다** (D12) — `enforcement` 는 "직전 0건이라 분모를 1로 치환했다"
   * (그래서 `deltaPct` 에 값이 있다), `shortage` 는 "직전 분모가 0 이라 `shortageDeltaPp` 를
   * 낼 수 없었다"(그래서 값이 `null` 이다). `most-complaint` 는 항상 `false`.
   */
  deltaPctInferred: boolean
}

/** zone 의 민원유형별 건수 (TSK-4165 D2). 유형 이름은 `TB_COM_CD`(`CVLCPT_TYPE_CD`)의 `cd_nm`. */
export interface CvlcptTypeCnt {
  cvlcptTypeCd: string
  cvlcptTypeNm: string
  cvlcptCnt: number
}

export interface HotspotRankingResultVO {
  view: HotspotView;
  /**
   * 응답 envelope 의 `unit` echo-back. 핵심 API 와 같은 enum 으로 좁힌다 — 호출부에서
   * 분기 누락/오타를 컴파일 단계에 잡는다. placeholder 단계에서 SQL 미반영이지만 백엔드
   * enum 검증은 동일하게 적용됨(가이드 §4).
   */
  unit: ComplaintUnit;
  zones: HotspotRankingZone[];
}

/**
 * 히트맵 칸 1개 (TSK-4295). 원천 좌표를 **소수 3자리(0.001° ≈ 100m)로 반올림**해 묶은 칸이다
 * — 개별 신고 지점이 아니다. 같은 `(lat, lot)` 은 한 번만 나오고, 배열 순서는 의미 없다.
 */
export interface HotspotHeatmapPoint {
  /** 칸 위도 (EPSG:4326, 소수 3자리). */
  lat: number
  /** 칸 경도 (EPSG:4326, 소수 3자리). */
  lot: number
  /** 그 칸의 민원(또는 단속) 행 단위 `COUNT(*)` — 항상 1 이상. */
  cnt: number
}

export interface HotspotHeatmapResultVO {
  /** 🔴 `shortage` 는 히트맵 대상이 아니다(요청 시 400) — 응답은 두 값뿐이다. */
  view: Exclude<HotspotView, 'shortage'>
  /**
   * 조회 단위 echo-back. 🔴 기간 직접 지정(`startDt`+`endDt`)이면 **`null`** 이다
   * (§2 순위와 같은 규칙 — `docs/API-guide/minwon.md` §2-1).
   */
  unit: ComplaintUnit | null
  /** 칸 크기(도). `0.001` 고정. */
  cellDeg: number
  /**
   * 전 칸 건수 합 = `sum(points[].cnt)`.
   * 🔴 같은 조건 순위 합계(`sum(zones[].cvlcptCnt | crdnCnt)`)와 같다 — 두 모드의 숫자가 갈리면 백엔드 회귀다.
   */
  totalCnt: number
  /** 칸 목록. 0건이면 빈 배열. */
  points: HotspotHeatmapPoint[]
}

export interface ParkingLotItem {
  /** 주차장 ID (TB_PBLPKLT.pklt_id). 백엔드 VO 는 `long` 이지만 응답에서 JS number 로 받는다. */
  pkltId: number
  /** 주차장명 (TB_PBLPKLT.pklt_nm). */
  pkltNm: string
  /** 위도 (TB_PBLPKLT.lat, NOT NULL). */
  lat: number
  /** 경도 (TB_PBLPKLT.lot, NOT NULL). */
  lot: number
  /** 주소 (TB_PBLPKLT.pklt_addr). 팝업 `주소:` 줄 (TSK-4165 F8). */
  pkltAddr: string
  /** 읍면동명 (TB_PBLPKLT.emd_nm). 🔴 nullable — 결측이면 팝업이 행정동 행을 생략한다 (D8) */
  emdNm: string | null
  /** 주차장구분코드 (TB_PBLPKLT.pklt_type_cd). 공통코드 PKLT_TYPE_CD. `null`=원천 결측 */
  pkltTypeCd: string | null
  /** 주차장구분명 — 공통코드 PKLT_TYPE_CD 의 cd_nm (🔴 DB 의 pklt_type_nm 원문이 아니다). `null`=행 생략 */
  pkltTypeNm: string | null
  /** 주차 면수 (TB_PBLPKLT.pklt_cpcty). `null` 이면 팝업이 면수 행을 생략한다. */
  pkltCpcty: number | null
  /** 요금 여부 (TB_PBLPKLT.prk_crg_yn) — `Y` 유료 · `N` 무료. `null` 이면 요금 행 생략. */
  prkCrgYn: 'Y' | 'N' | null
}

/*
 * §4 행정동 경계 GeoJSON 응답 (RFC 7946).
 *
 * 백엔드 `AdminDistrictServiceImpl` 응답 구조 그대로 — `type="FeatureCollection"` 고정,
 * `features[]` 각 원소는 `geometry` (PostGIS `ST_AsGeoJSON(bndry_geom)`) + `properties`
 * (`json_build_object('emdCd', …, 'emdNm', …, 'sggCd', …)`).
 *
 * ⚠️ `@types/geojson` 의존성을 추가하지 않고 직접 정의한다 — 본 파일은 민원현황 한 화면의
 *    호출 모듈이고, 행정동 경계의 geometry 는 `Polygon` / `MultiPolygon` 둘 뿐이라 표준
 *    전체를 끌어올 이유가 없다. 응답 사용처(§2 GIS 지도)가 좌표만 읽으면 충분.
 */

/** GeoJSON `coordinates` — `[lng, lat]` 순서. 링은 첫/끝 좌표가 같다 (RFC 7946). */
export type LngLat = readonly [number, number]
export type LinearRing = readonly LngLat[]

export interface PolygonGeometry {
  type: 'Polygon'
  /** 다중 ring — 외부 링이 첫 번째. */
  coordinates: readonly LinearRing[]
}

export interface MultiPolygonGeometry {
  type: 'MultiPolygon'
  coordinates: readonly (readonly LinearRing[])[]
}

/** 본 API 가 돌려주는 geometry — 행정동 경계 = 다각형/다중 다각형. */
export type AdminDistrictGeometry = PolygonGeometry | MultiPolygonGeometry

/** GeoJSON Feature 의 `properties` — 백엔드 `json_build_object` 매핑 그대로. */
export interface AdminDistrictProperties {
  emdCd: string
  emdNm: string
  sggCd: string
}

export interface AdminDistrictFeature {
  type: 'Feature'
  geometry: AdminDistrictGeometry
  properties: AdminDistrictProperties
}

export interface AdminDistrictFeatureCollection {
  type: 'FeatureCollection'
  features: AdminDistrictFeature[]
}

/* ─────────────────────────────────────────────────────────────
 * 호출자
 * ───────────────────────────────────────────────────────────── */

/**
 * §1·§3·§7·§8 통합 응답. 헤더 부제(`lastSyncDt`) + KPI + 읍면동 비교까지 한 번에.
 * ⚠️ `lastSyncDt` 는 화면이 더 이상 읽지 않는다(TSK-4256 — 부제는 `DataFreshness`).
 */
export async function fetchComplaintKpi(params?: ComplaintQuery): Promise<ComplaintKpiResponse> {
  const { data } = await api.get<ComplaintKpiResponse>('/complaints/gis', { params })
  return data
}

/**
 * §4 분석레이어 순위 조회 (TSK-3880 §4·§6).
 *
 * 백엔드 계약(`HotspotRankingController`):
 *   GET /api/hotspots/ranking?view=most-complaint|enforcement|shortage
 *       &unit=YESTERDAY|WEEK|MONTH&sggCd=&emdCd=&cvlcptTypeCd=
 *
 * - view=most-complaint (default) / enforcement / shortage. 2026-09-04 갱신으로 view=least-complaint 폐기 —
 *   요청 시 백엔드가 400 으로 응답 (enum 화이트리스트 미스).
 * - most-complaint 는 §2 필터를 SQL 에 반영 — TB_CVLCPT 직접 집계. 🔁 **2026-10-02 TSK-4197 —
 *   집계식은 행 단위 `COUNT(*)` 다**(종전 DISTINCT 기준 둘은 폐기 — `cvlcpt_id`(2026-09-04 이전) ·
 *   `cvlcpt_cn`(~2026-10-02)). 같은 내용이 정형 문구라 중복이 아니었다.
 * - enforcement view (2026-09-03 갱신) 는 §2 필터를 SQL 에 반영 — TB_CRDN_DSCTN 직접 집계.
 * - shortage view 는 `parking.fn_jenks_shortage` 결과를 노출하되 🔴 **등급 칸(`out_grade`)은 쓰지
 *   않는다**(2026-10-02 TSK-4197 D14 — 6번째 인자가 `NULL::text`). 함수 이름과 달리 Jenks 등급
 *   분류는 판정에 관여하지 않고, PSDR·민원합·공급면수 계산기로만 쓴다. 등급은 프론트
 *   `pickRiskGrade` 백분위 한 곳이 정한다.
 *
 * 응답의 envelope `unit` 은 백엔드가 echo-back 한 값으로, 요청에 보낸 값과 일치한다.
 */
export async function fetchHotspotRanking(
  params: ComplaintQuery & { view: HotspotView }
): Promise<HotspotRankingResultVO> {
  const { data } = await api.get<HotspotRankingResultVO>('/hotspots/ranking', { params })
  return data
}

/**
 * §6 GIS 히트맵 좌표 칸 (TSK-4295).
 *
 * 백엔드 계약(`HotspotRankingController`):
 *   GET /api/hotspots/heatmap?view=most-complaint|enforcement
 *       &unit=YESTERDAY|WEEK|MONTH&startDt=&endDt=&sggCd=&emdCd=&cvlcptTypeCd=
 *
 * - 파라미터는 `fetchHotspotRanking` 과 **같다** — 순위 API 와 같은 WHERE 라 같은 조건이면
 *   `totalCnt` 가 순위 합계와 일치한다.
 * - 🔴 `view=shortage` 는 **400** 이다(수요 부족은 동 단위 비율이라 점으로 그릴 것이 없다 — Q4).
 *   호출부가 히트맵 모드를 민원·단속에서만 열어 이 경로로 오지 않게 한다.
 * - ⚠️ 단속(`enforcement`)에 `cvlcptTypeCd` 를 걸면 순위와 같은 한계로 **항상 0칸**이다.
 * - 히트맵 모드일 때만 부른다(읍면동 모드 사용자에게 추가 호출 없음 — 계획서 §3-2).
 */
export async function fetchHotspotHeatmap(
  params: ComplaintQuery & { view: HotspotView }
): Promise<HotspotHeatmapResultVO> {
  const { data } = await api.get<HotspotHeatmapResultVO>('/hotspots/heatmap', { params })
  return data
}

/**
 * §4 공영주차장 마커 목록 (TSK-3880 §4·§6).
 *
 * 백엔드 계약(`RefDataController`):
 *   GET /api/parking-lots?sggCd=
 *
 * 좌표 있는 행만 반환 — 지오코딩 미완료 행은 매퍼 SQL 의 `WHERE lat IS NOT NULL AND lot IS NOT NULL`
 * 에서 제외된다. sggCd optional — null 이면 전체.
 */
export async function fetchParkingLots(params?: { sggCd?: string }): Promise<ParkingLotItem[]> {
  const { data } = await api.get<ParkingLotItem[]>('/parking-lots', { params })
  return data
}

/**
 * §4 행정동 경계 GeoJSON FeatureCollection (TSK-3880 §4·§6).
 *
 * 백엔드 계약(`AdminDistrictController`):
 *   GET /api/admin-districts?sggCd=&emdCd=
 *
 * ⚠️ 응답 크기 수 MB 가능 — `Accept-Encoding: gzip` 필수. axios 인터셉터에 gzip 자동 적용 확인 필요.
 * 행정동 경계는 분기 단위로만 변하므로 클라이언트 캐싱이 안전하다.
 */
export async function fetchAdminDistricts(
  params?: { sggCd?: string; emdCd?: string }
): Promise<AdminDistrictFeatureCollection> {
  const { data } = await api.get<AdminDistrictFeatureCollection>('/admin-districts', { params })
  return data
}

/* ─────────────────────────────────────────────────────────────
 * 민원현황 보고서 내보내기 (TSK-4164)
 *
 * 백엔드 계약:
 *   POST /api/complaints/reports        → ComplaintReportVO (201) — 저장된 스냅샷 그대로
 *   GET  /api/reports/{rptpId}/complaint → 같은 모양 (스냅샷 재열람, `api/reports.ts`)
 *
 * 🔴 타입(`ComplaintReportDoc`·`ComplaintReportCreateRequest`)을 **이 파일에** 둔다 —
 *    집중구역은 `HotspotReportDoc` 을 템플릿 파일에 두지만 그쪽은 템플릿이 먼저 있었다.
 *    여기서는 클라이언트가 먼저라, 문서 템플릿
 *    (`lib/analysis/complaintReportTemplate.ts`)이 이 모듈에서 타입을 가져간다.
 * ───────────────────────────────────────────────────────────── */

/** 보고서 파일형식. 백엔드 `ComplaintReportCreateRequest#fileFmt` 의 `@Pattern` 두 값 뿐 — 그 밖은 400. */
export type ComplaintFileFmt = 'PDF' | 'DOCX'

/**
 * POST /api/complaints/reports 요청 바디 (TSK-4164).
 *
 * 조회 조건 + `fileFmt` 한 칸이다. 🔴 **`unit` 을 받지 않는다** — 상대 기간을 저장할 칸이 없어
 * 화면은 조회 응답이 되비춘 실제 날짜(`ComplaintKpiResponse.bgngYmd`/`endYmd`)를 그대로 보낸다.
 *
 * 🔴 지역 조건은 **키를 아예 넣지 않는 것**이 "전체"다 — 빈 문자열(`''`)을 보내지 않는다.
 */
export interface ComplaintReportCreateRequest {
  /** 조회 시작일 (YYYYMMDD, 필수). 조회 응답의 `bgngYmd` 를 그대로 싣는다. */
  bgngYmd: string
  /** 조회 종료일 (YYYYMMDD, 필수 · 당일 포함). 조회 응답의 `endYmd` 를 그대로 싣는다. */
  endYmd: string
  /** 시군구 코드 5자리 (50110=제주시 · 50130=서귀포시). 생략 = 전 지역. */
  sggCd?: string
  /**
   * 읍면동코드 8자리. 생략 = 시군구 전체.
   *
   * 🔴 **`sggCd` 와 함께 오면 AND 로 좁혀진다(우선 아님)** — 집중구역의 `resolveRegion`
   * (emdCd 우선)과 동작이 다르다(백엔드 D4). 두 값이 어긋나면 결과가 0건이 된다.
   */
  emdCd?: string
  /** 민원유형코드 01~05. 생략 = 전 유형. */
  cvlcptTypeCd?: string
  /** 파일형식 (필수 · 기본값 없음). 화면의 PDF/DOCX 두 버튼이 정한다. */
  fileFmt: ComplaintFileFmt
}

/**
 * 민원현황 보고서 스냅샷 — 생성(201) 응답과 재열람(GET) 응답이 **같은 모양**이다.
 * 백엔드 `ComplaintReportVO`(10칸 + 중첩 `Kpi` 5칸)와 1:1.
 *
 * 🔴 `rptpNm`·`regionNm`·`anlsPrdExpln`·`crtYmd` 는 **서버가 만든 값**이다 — 지역 3단 규칙·
 * 기간 표기를 화면이 다시 조립하지 않는다(`ComplaintReportNaming` 이 유일한 출처 — 백엔드 D8).
 */
export interface ComplaintReportDoc {
  /** 보고서아이디 (TB_RPTP PK). */
  rptpId: number
  /** 분석실행아이디 (TB_ANLS_EXCN PK). */
  excnId: number
  /** 보고서명 — `민원 현황 보고서 {regionNm}`. */
  rptpNm: string
  /** 지역명 — `전체 지역` / `{시군구} 전체` / `{시군구} {읍면동}` 3단 (백엔드 D4·D8). */
  regionNm: string
  /** 분석기간설명 — `yyyy.MM.dd ~ yyyy.MM.dd` (시작=종료면 한 번만). */
  anlsPrdExpln: string
  /** 파일형식 — 요청값을 그대로 저장·반환한다. */
  fileFmt: ComplaintFileFmt
  /** 생성일시 (ISO-8601 `yyyy-MM-ddTHH:mm:ss`). 문서 부제의 생성일 출처. */
  crtYmd: string
  /** 보고서가 담는 KPI 7칸. 🔴 조회 응답 KPI(10칸)와 **일부러 다른 모양**이다. */
  kpi: ComplaintReportKpi
  /**
   * 지역별 민원 현황 카드 목록. 조회 응답의 `emdCompare[]` 를 그대로 싣는다.
   *
   * 🔁 **`prevCnt` 는 TSK-4311 부터 온다.** 그 이전 스냅샷에는 키가 없으므로 `null` 판정이 필요하다.
   */
  emdCompare: EmdCompareItem[]
  /**
   * 부정 민원 상위 지역 카드 목록. 조회 응답의 `negEmdCompare[]` 를 그대로 싣는다.
   *
   * 🔴 **`prevNegativeCount` 는 실제로 오지 않는다** — 필수 필드로 선언돼 있을 뿐
   * 백엔드 `NegEmdCompareVO` 에 컴포넌트가 없다(D9). 문서 템플릿이 쓰면 `undefined` 다.
   */
  negEmdCompare: NegEmdCompareItem[]
  /**
   * 읍·면·동 단속 순위 카드 목록 (문서 §4). 조회의 `rank("enforcement")` 결과를 경량화해 싣는다.
   *
   * ⚠️ 2026-09-30 이전에 만들어진 스냅샷에는 이 키가 **없다** — 역직렬화가 `undefined` 를 주므로
   * 소비자는 `?? []` 로 받는다(문서 템플릿이 그렇게 한다).
   */
  crdnCompare: CrdnCompareItem[]
  /**
   * 읍·면·동 수요부족 순위 카드 목록 (문서 §5).
   *
   * ⚠️ `crdnCompare` 와 같은 이유로 구 스냅샷에는 없다 — `?? []`.
   */
  shortageCompare: ShortageCompareItem[]
}

/** 보고서 KPI 7칸 (백엔드 `ComplaintReportVO.Kpi` record 와 1:1). */
export interface ComplaintReportKpi {
  /** 총 민원 건수. */
  totalCount: number
  /** 부정 비율 (0.0~1.0). */
  negativityRatio: number
  /** 감성 분포 — 조회 응답과 같은 `SentimentDist` 를 재사용한다. */
  sentimentDist: SentimentDist
  /** 단속 집중 지역 수. */
  enforcementCount: number
  /** 수요 부족 지역 수 — 🔁 2026-10-02 TSK-4197 D14: 주차민원 > 0 인 전 zone 중 `FLOOR(모집단 × 0.1)` 인 동 수.
   *  ⚠️ **구 스냅샷은 옛 정의(Jenks `심각` 안의 상위 10%)로 굳어 있다** — 재계산하지 않는다. */
  shortageCount: number
  /** 전기 기간 표기(서버 조립 — TSK-4311). 🔴 구 스냅샷에는 없다. */
  prevAnlsPrdExpln?: string | null
  /** 전기 민원 건수(TSK-4311). 🔴 구 스냅샷에는 없다 — 0 으로 대체하지 않는다(0 은 실제 0건이다). */
  prevTotalCount?: number | null
}

/**
 * 민원현황 보고서 생성(내보내기) — 201 로 저장된 스냅샷을 그대로 돌려받는다.
 *
 * 🔴 공용 `api`(axios) 인스턴스를 쓴다. 새 인스턴스를 만들면 `X-XSRF-TOKEN` 이 빠져 **403** 이다.
 */
export async function createComplaintReport(
  body: ComplaintReportCreateRequest,
): Promise<ComplaintReportDoc> {
  const { data } = await api.post<ComplaintReportDoc>('/complaints/reports', body)
  return data
}
