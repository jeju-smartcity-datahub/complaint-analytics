import { api } from './client'
import {
  vrblVlsBody,
  vrblVlsQuery,
  type AnalysisWeight,
  type AnlsVrblAjmt,
  type VrblVls,
} from './analysisWeight'

/*
 * 불법주차 집중구역(핫스팟) 분석 실 API 클라이언트.
 * 백엔드 계약(`jeju.datahub.analysis.web.HotspotAnalysisController` · 2026-08-26 / 08-27 로컬 실측):
 *   GET  /api/analysis/hotspot         → HotspotAnalysisResult   (순위 목록 + 지도 마커를 한 응답으로)
 *   POST /api/analysis/hotspot/reports → HotspotReportResponse   (재분석 + 3테이블 저장, 201)
 *
 * **조회가 GET 인 이유** — 분석 결과를 저장하지 않는 순수 조회다. 조건이 URL 에 남아 링크 공유·재현이 쉽다.
 * **저장이 POST 인 이유** — 같은 조건으로 재분석해 `TB_ANLS_EXCN`·`TB_HTSPT_ANLS_RSLT`·`TB_RPTP`
 * 세 테이블에 행을 만든다. 화면이 보낸 숫자를 그대로 받지 않는 것이 핵심이다.
 *
 * 응답은 조회 조건(bgngYmd·endYmd·sggNm·emdCd)과 군집 파라미터(eps·minPoints)·산식·가중치·원천 보유
 * 구간까지 **되비춘다** — 응답 하나로 결과를 재현·설명할 수 있게 하려는 설계다. 특히 `weights` 는
 * 화면 부제(`민원 60% / 단속 40%`)의 출처라 **프론트가 가중치를 따로 조회하지 않는다**.
 *
 * `zones` 는 **종합점수 내림차순으로 서버가 정렬**해 내려주고 `rnk` 도 서버가 매긴다.
 * 프론트에서 재정렬하지 말고 받은 순서를 그대로 렌더링한다.
 *
 * ⚠️ 숫자 필드 중 `snthsScr`·`mrkLat`·`mrkLot`·`stngVl` 은 백엔드가 `BigDecimal` 이라 본문에는
 * `259.20` 처럼 실려 오지만 `JSON.parse` 가 JS `number` 로 만든다 — 프론트에서는 전부 `number` 다.
 */

/* ── 1. 조회 파라미터 ──────────────────────────────────── */

/**
 * GET /api/analysis/hotspot 조회 파라미터.
 *
 * 🔴 **`전체` 를 고른 지역 필터는 키를 아예 넣지 않는다**(`sggNm`·`emdCd`).
 * 빈 문자열은 **400** 이다 — 실측(2026-08-26): `?sggNm=` →
 * `400 "sggNm 를 빈 값으로 보낼 수 없습니다. 전 지역이면 파라미터를 생략하세요."`
 * (알림 조회 화면 `AlertInquiryPage` 의 `buildParams` 와 같은 규약이며, 그쪽 서버가 빈 값을 전체로
 * 정규화해 주는 것과 달리 이 API 는 정말로 거절한다.)
 *
 * 화면 상태는 `전체` 를 `''` 로 두면 되고, 빈 값 제거는 아래 `fetchHotspotAnalysis` 가 책임진다.
 */
export interface HotspotAnalysisParams {
  /** 분석 시작일 (YYYYMMDD, 필수). 형식이 어긋나면 400. */
  bgngYmd: string
  /** 분석 종료일 (YYYYMMDD, 필수 · **당일 포함**). bgngYmd 보다 앞서면 400. */
  endYmd: string
  /** 시군구명. 예) '제주시'. **전체면 넣지 않는다**(`''`·생략 = 전 지역). 미등록 시군구면 400. */
  sggNm?: string
  /** 읍면동코드. 예) '50110560'. **전체면 넣지 않는다**(`''`·생략 = 시군구 전체). 미존재면 400. */
  emdCd?: string
  /** 결과 구역 개수. 기본 15 · **상한 30**(초과하면 400). 순위 목록과 지도 마커가 같은 집합이다. */
  limit?: number
  /**
   * 민원 가중치(%) — 이번 조회에만 쓴다. `crdnWgvl` 과 **함께** 보내거나 함께 생략하고 합계는 100 이다.
   * 둘 다 생략 = 설정값. 한쪽만·합계 불일치·소수 셋째 자리 이상·범위 밖·꺼진 지표는 400.
   */
  cvlcptWgvl?: number | null
  /** 단속 가중치(%). 규칙은 `cvlcptWgvl` 과 같다. */
  crdnWgvl?: number | null
  /**
   * 등급 비율 `GRD_BNDRY1~3` 일시 조정(경계 묶음이라 바뀌면 3행 전부). 생략·빈 객체 = 설정값.
   * 🔴 가중치는 여기 넣지 않는다 — `cvlcptWgvl`·`crdnWgvl` 이 맡는다(`COMPLAINT` 는 400).
   */
  vrblVls?: VrblVls
}

/**
 * 합계 100 의 나머지 쪽 가중치(%).
 *
 * 🔴 `단속 = 100 − 민원` 과 슬라이더 범위 계산은 **이 함수로만** 한다. 그냥 빼면
 * `100 - 70.1 = 29.900000000000006` 이 화면에 나오고, 요청에 실리면 소수 3자리라 400 이다.
 */
export function restWgvl(x: number): number {
  return Number((100 - x).toFixed(2))
}

/* ── 2. 응답 타입 ─────────────────────────────────────── */

/**
 * 등급코드. 임계값(80/60/40)의 단일 출처는 백엔드 `HotspotGrade` 이며,
 * 프론트는 이 코드 → 색만 매핑한다. 라벨은 응답의 `gradeNm`(심각/경고/주의/양호)을 그대로 쓴다.
 */
export type HotspotGrade = 'SEVERE' | 'WARN' | 'CAUTION' | 'NORMAL'

/**
 * 등급 판정 기준 한 줄 (`gradeCriteria[]`). 점수 경계가 아니라 **순위 비율**이고 설정값이다.
 * 하한은 오지 않는다. **바로 위 등급의 `maxRankPct`** 가 하한이다.
 */
export interface GradeCriterion {
  /** 등급코드. `zones[].grade` 와 같은 값이라 색·순서를 이 코드로 맞춘다. */
  grade: HotspotGrade
  /** 화면 표시용 등급명 (`심각`/`경고`/`주의`/`양호`). */
  gradeNm: string
  /** 이 등급의 순위 비율 상한(%). 상위 몇 %까지인가. */
  maxRankPct: number
}

/**
 * 구역명 출처 — 폴백 체인 중 어느 단계에서 나온 이름인지.
 * `CRDN_ADDR`(단속주소) → `POI`(VWorld 최근접) → `REVERSE_GEOCODE`(VWorld 역지오코딩) → `EMD`(`{읍면동명} 일대`).
 */
export type HotspotZoneNmSrc = 'CRDN_ADDR' | 'POI' | 'REVERSE_GEOCODE' | 'EMD'

/** 집중구역 1건 = DBSCAN 군집 1개. 목록 행과 지도 마커가 **같은 객체**다. */
export interface HotspotZone {
  /** 순위 — 종합점수 내림차순 1부터. 서버가 매긴다. */
  rnk: number
  /** 구역명. 폴백 체인의 결과라 **최종적으로 비지 않는다**. */
  zoneNm: string
  /** 읍면동명. 구역 중심좌표가 속한 행정동(TB_ADMDST). */
  emdNm: string | null
  /** 구역명 출처. 이름 신뢰도 표시·디버깅용. */
  zoneNmSrc: HotspotZoneNmSrc
  /** 민원건수 — 군집에 속한 민원 합계. */
  cvlcptCnt: number
  /** 단속건수 — 군집에 속한 단속 합계 (민원 기반 단속을 빼지 않는다). */
  crdnCnt: number
  /**
   * 종합점수 0~100 — 조회 결과 1위를 100 으로 둔 상대 점수.
   * 🔴 조건이 다르면 비교할 수 없다.
   */
  snthsScr: number
  /** 등급코드. `snthsScr` 로 백엔드가 판정한다 — 프론트에서 다시 계산하지 않는다. */
  grade: HotspotGrade
  /** 등급명 (심각 | 경고 | 주의 | 양호). */
  gradeNm: string
  /** 마커 위도 (EPSG:4326) — 군집 중심좌표. */
  mrkLat: number | null
  /** 마커 경도 (EPSG:4326) — 군집 중심좌표. */
  mrkLot: number | null
}

/**
 * 응답 `weights[]` 의 원소.
 *
 * 🔴 가중치 타입의 정본은 `api/analysisWeight.ts` 라 재정의하지 않고 그대로 쓴다.
 * `Pick<AnalysisWeight, 'vrblCd' | 'vrblNm' | 'stngVl'>` 로 좁히지 않는다 —
 * **2026-08-26 실측 결과 응답은 VO 필드 전부**를 싣는다 — 백엔드가
 * `AnalysisWeightService#selectEffectiveWeights` 가 돌려준 `AnalysisWeightVO` 를 그대로 담고
 * (`HotspotAnalysisServiceImpl#scoredWeights`), Jackson 의 null 제외 설정도 없기 때문이다.
 * 실측(이후 키가 `vrblCd`·`vrblNm`·`stngVl` 로 바뀌고 `vrblSeCd`·`minVl`·`maxVl`·
 * `unitNm` 4종이 늘었다):
 *   `{"anlsTypeCd":"HOTSPOT","vrblCd":"COMPLAINT","vrblNm":"민원빈도",
 *     "vrblExpln":"수집플랫폼 표준 민원 데이터","vrblSeCd":"WGVL","stngVl":60.0000,
 *     "bscStngVl":60.0000,"minVl":0.0000,"maxVl":100.0000,"unitNm":"%",
 *     "useYn":"Y","sortSeq":1,"mdfcnDt":null}`
 * 3필드로 좁히면 실제 응답에 있는 키가 타입에서 사라져 계약이 거짓이 된다.
 *
 * ⚠️ `stngVl` 은 **use_yn='N' 을 뺀 뒤 재정규화된 값**이라 마스터 저장값과 다를 수 있다.
 * 화면 부제에는 이 값을 쓴다 — 실제 계산에 쓰인 비율이 이쪽이다.
 * ⚠️ 이 배열에는 **계수(`vrblSeCd === 'CFC'`)가 실리지 않는다** — 백엔드가 `WGVL`·`use_yn='Y'`
 * 만 남겨 재정규화하기 때문이다.
 */
export type HotspotWeight = AnalysisWeight

/** 가중치 조정여부 값. */
export type WgvlAjmtYn = 'Y' | 'N'

/** GET /api/analysis/hotspot 응답 루트. */
export interface HotspotAnalysisResult {
  /** 서버가 정규화한 분석 시작일 (YYYYMMDD). 요청 문자열이 아니라 적용된 값이다. */
  bgngYmd: string
  /** 서버가 정규화한 분석 종료일 (YYYYMMDD, 당일 포함). */
  endYmd: string
  /** 적용된 시군구명. 전 지역이면 null. */
  sggNm: string | null
  /** 적용된 읍면동코드. 시군구 전체면 null. */
  emdCd: string | null
  /** 적용된 DBSCAN 반경(m) — 재현용. 미터 좌표계(EPSG:5179) 기준이라 단위가 m 이다. */
  eps: number
  /** 적용된 DBSCAN 최소 포인트 수 — 유일좌표 기준이라 "서로 다른 지점 수"다. */
  minPoints: number
  /** 점수 산식 표기. 예) `CVLCPT*0.6 + CRDN*0.4`. 계수가 0 인 항은 빠진다. */
  scoreFormula: string
  /** 계산에 실제로 쓴 가중치 목록. 점수에 반영되는 지표만 담긴다(sort_seq 순 — 재정렬 금지). */
  weights: HotspotWeight[]
  /**
   * 가중치 조정 여부. `Y` = 설정값과 다른 입력으로 계산했다.
   * 화면 ` (조정됨)` 판정은 이 값이 아니라 결과를 만든 실행의 입력값(`ranCvlcpt`)이다.
   */
  wgvlAjmtYn?: WgvlAjmtYn | null
  /**
   * 등급 비율 3행의 적용 내역. 가중치만 조정하면 `ajmtYn='N'` 이다(가중치는 `wgvlAjmtYn`).
   * 화면 배너·`(조정됨)` 은 이 값이 아니라 계수 훅 상태로 그린다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
  /** 원천 보유 시작일 (YYYYMMDD). **0건 안내 문구용**. 원천이 비면 null. */
  dataBgngYmd: string | null
  /** 원천 보유 종료일 (YYYYMMDD). 원천이 비면 null. */
  dataEndYmd: string | null
  /**
   * 등급 판정 기준 — `maxRankPct` 오름차순(`SEVERE` → `WARN` → `CAUTION`).
   * **`NORMAL` 까지 전부 온다** — `zones[].grade` 로 `NORMAL` 이 내려오므로 응답이 자기완결적이어야
   * 하기 때문이다. 범례에 3행만 세우는 것은 화면의 결정이라 `LEGEND_GRADES` 로 프론트가 거른다.
   *
   * 구버전 백엔드 호환을 위해 선택 필드로 둔다 — 없으면 화면은 임계값 없이 라벨만 보여 준다.
   */
  gradeCriteria?: GradeCriterion[]
  /**
   * 집중구역 목록 — 종합점수 내림차순 상위 `limit` 건.
   * ⚠️ **빈 배열도 200 이며 정상 상태다**(조건에 데이터가 없거나 군집이 형성되지 않은 경우) —
   * 오류로 처리하지 말고 `dataBgngYmd`~`dataEndYmd` 를 곁들인 안내를 띄운다.
   */
  zones: HotspotZone[]
}

/* ── 3. 조회 함수 ─────────────────────────────────────── */

/**
 * 집중구역 분석 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지).
 *
 * 🔴 `sggNm`·`emdCd` 는 **값이 있을 때만** 쿼리에 얹는다 — `''`(전체)를 그대로 보내면 400 이기
 * 때문이다. 페이지가 `전체` 를 `''` 로 들고 있어도 여기서 끊어 주므로 화면마다 같은 방어를 반복하지 않는다.
 * `limit` 도 마찬가지로 값이 있을 때만 얹고, 없으면 서버 기본값(15)을 따른다.
 *
 * 오류: bgngYmd/endYmd 형식 오류·역전 · 빈 sggNm/emdCd · 미등록 시군구 · 미존재 읍면동 · limit 범위 밖 → 400.
 */
export async function fetchHotspotAnalysis(
  params: HotspotAnalysisParams,
): Promise<HotspotAnalysisResult> {
  const query: Record<string, string | number> = {
    bgngYmd: params.bgngYmd,
    endYmd: params.endYmd,
  }
  if (params.sggNm) query.sggNm = params.sggNm
  if (params.emdCd) query.emdCd = params.emdCd
  if (params.limit) query.limit = params.limit
  // 🔴 `!= null` 이다 — `if (params.cvlcptWgvl)` 로 쓰면 민원 0% 가 빠져 한쪽만 보낸 400 이 된다.
  if (params.cvlcptWgvl != null) query.cvlcptWgvl = params.cvlcptWgvl
  if (params.crdnWgvl != null) query.crdnWgvl = params.crdnWgvl
  // GET 은 JSON 문자열 1개. 비면 키를 뺀다.
  const vrblVls = vrblVlsQuery(params.vrblVls)
  if (vrblVls) query.vrblVls = vrblVls

  const { data } = await api.get<HotspotAnalysisResult>('/analysis/hotspot', { params: query })
  return data
}

/* ── 4. 보고서 생성 (POST) ────────────────────────────── */

/**
 * 보고서 파일형식. 공통코드 `FILE_FMT_CD` 의 값이며 그 밖의 값은 **400** 이다
 * (백엔드 `HotspotReportCreateRequest#fileFmt` 의 `@Pattern`).
 */
export type HotspotFileFmt = 'PDF' | 'DOCX' | 'XLSX'

/**
 * 엑셀 재계산용 후보 구역 1건 (`wbk.zones[]` — 백엔드 `HotspotWorkbookZoneVO`).
 * 점수·등급은 오지 않는다 — 엑셀이 B3(민원 가중치)로 다시 계산한다.
 */
export interface HotspotWorkbookZone {
  /** DBSCAN 군집 번호. 표시 순번의 마지막 정렬키(동점 처리)이며 식별자로 쓰지 않는다. */
  cid: number
  /** 구역명 (폴백 체인 결과). */
  zoneNm: string
  /** 읍면동명. */
  emdNm: string | null
  /** 민원건수. */
  cvlcptCnt: number
  /** 단속건수. */
  crdnCnt: number
}

/** 민원 가중치별 0점 제외 군집 수 1행 (`wbk.scrCnts[]` — 백엔드 `HotspotScrCntVO`). 엑셀 등급 모집단 N. */
export interface HotspotScrCnt {
  /** 민원 가중치(%) — 0~100 정수 또는 적용 민원 가중치(비정수, 예 62.5). */
  cvlcptWgvl: number
  /** 그 가중치에서 종합점수 > 0 인 군집 수(반올림 뒤 값으로 센다). */
  scrCnt: number
}

/**
 * 집중구역 보고서의 엑셀 재계산 데이터 (`rslt_json.wbk` — 백엔드 `HotspotWorkbookVO`).
 * 생성 응답·스냅샷 응답에 실리며 엑셀 도입 이전 보고서는 `null`/없음이다.
 */
export interface HotspotWorkbook {
  /** 적용 민원 가중치(%) — 서비스가 실제 계산에 쓴 값. 🔴 엑셀 B3 초기값의 유일한 출처이며 `scrCnts` 에 같은 값의 행이 있다. */
  cvlcptWgvl: number
  /** 전체 군집 중 최대 민원 건수 (군집이 없으면 0). */
  maxCvlcptCnt: number
  /** 전체 군집 중 최대 단속 건수 (군집이 없으면 0). */
  maxCrdnCnt: number
  /** 민원 가중치별 0점 제외 군집 수 — 0~100 101행 + 적용 가중치가 비정수면 1행(가중치 오름차순). */
  scrCnts: HotspotScrCnt[]
  /** 후보 구역 — 어느 정수 가중치(및 적용 가중치)에서든 주의 이상에 드는 구역(cid 오름차순). */
  zones: HotspotWorkbookZone[]
}

/**
 * POST /api/analysis/hotspot/reports 요청 바디.
 *
 * 조회(`HotspotAnalysisParams`)와 **같은 조건 + `fileFmt` 한 칸**이다 — 서버는 화면이 보낸 숫자를
 * 믿지 않고 이 조건으로 **다시 분석**해 그 결과를 저장한다. 그래서 저장 결과가 화면과 같으려면
 * 조건을 그대로 넘겨야 한다.
 *
 * 🔴 지역 조건은 `''`(전체)를 그대로 실으면 **400** 이다 — 조회 API 와 같은 규약이라
 * 아래 `createHotspotReport` 가 빈 값을 빼 준다. `null` 도 받는데, 화면이 넘기는 값이
 * **적용된 조건**(`HotspotAnalysisResult.sggNm`/`emdCd` — 전체면 `null`)이기 때문이다.
 */
export interface HotspotReportCreateRequest {
  /** 시군구명. `''`·`null`·생략 = 전 지역. */
  sggNm?: string | null
  /** 읍면동코드. `''`·`null`·생략 = 시군구 전체. */
  emdCd?: string | null
  /** 분석 시작일 (YYYYMMDD, 필수). */
  bgngYmd: string
  /** 분석 종료일 (YYYYMMDD, 필수 · 당일 포함). */
  endYmd: string
  /**
   * 결과 구역 개수. **생략 = 서버 기본값 15** — 조회(`GET /api/analysis/hotspot`)와 같은 값이다.
   *
   * 🔴 화면은 이 값을 **보내지 않는다.** `HotspotPage.runAnalysis()` 가 `fetchHotspotAnalysis` 에
   * `limit` 을 넘기지 않고 서버 기본값에 맡기므로, 저장 요청만 다른 개수를 보내면 화면에 15행이
   * 떠 있는데 파일·DB 에는 다른 개수가 남는다. 그래서 **선택값**이다 — 필수로 두면 호출부마다
   * 숫자를 적게 되고 그 숫자가 조회와 갈린다.
   */
  limit?: number
  /** 파일형식 (필수 · 기본값 없음). 화면의 `PDF 파일 (.pdf)` / `Word 파일 (.docx)` 두 항목이 정한다. */
  fileFmt: HotspotFileFmt
  /** 민원 가중치(%) — 규칙은 `HotspotAnalysisParams.cvlcptWgvl` 과 같다. `null`·생략 = 설정값. */
  cvlcptWgvl?: number | null
  /** 단속 가중치(%). `null`·생략 = 설정값. */
  crdnWgvl?: number | null
  /** 등급 비율 일시 조정 — 조회와 같은 규칙. 🔴 결과를 만든 실행의 값을 보낸다. */
  vrblVls?: VrblVls
}

/**
 * POST /api/analysis/hotspot/reports 응답 (201). **저장된 스냅샷 그대로**다.
 *
 * 🔴 `HotspotReportDoc`(문서 템플릿의 입력)을 그대로 쓰지 않는다 — 그쪽 `zones` 는
 * 템플릿이 실제로 쓰는 7칸 `Pick<>` 이라, 이 응답으로 화면 상태
 * (`HotspotAnalysisResult.zones: HotspotZone[]`)를 갱신하면 `emdNm`·`zoneNmSrc` 가 없어
 * **TS2739 로 `npm run build` 가 깨진다.** 서버는 두 칸을 실제로 싣는다(2026-08-27 실측:
 * `{"rnk":1,"zoneNm":"연동 신광로 제원사거리","emdNm":"연동","zoneNmSrc":"CRDN_ADDR",…}`).
 *
 * 넓은 타입(`HotspotZone`) → 좁은 타입(`Pick<>`) 대입은 성립하므로
 * **이 응답을 그대로 `buildHotspotReportHtml()` 에 넘길 수 있다** — 문서용 타입으로 다시 옮겨 담지 않는다.
 *
 * 헤더 4칸(`rptpNm`·`regionNm`·`anlsPrdExpln`·`crtYmd`)은 **서버 산출물**이다
 * (`HotspotReportNaming` 이 3단 지역 규칙·기간 표기의 유일한 출처 — 프론트가 다시 구현하지 않는다).
 */
export interface HotspotReportResponse {
  /** 보고서아이디 (TB_RPTP PK). 목록 화면의 미리보기·재다운로드 키다. */
  rptpId: number
  /** 분석실행아이디 (TB_ANLS_EXCN PK). 순위 행이 이 값으로 묶인다. */
  excnId: number
  /** 보고서명 `집중 구역 분석 보고서 {regionNm}`. **다운로드 파일명의 출처**다(확장자는 내보내기가 붙인다). */
  rptpNm: string
  /** 지역 표시명 — `전체 지역` / `제주시 전체` / `제주시 연동`. 문서 부제 3 의 `분석 지역`. */
  regionNm: string
  /** 분석기간설명 `2026.08.01 ~ 2026.08.31`(시작=종료면 한 번만). 문서의 `분석 기간` 줄. */
  anlsPrdExpln: string
  /** 생성일시 (ISO-8601 `2026-08-27T14:56:23.741913`). 문서 부제 3 의 `생성일` — 앞 10자만 쓴다. */
  crtYmd: string
  /** 요청한 파일형식을 그대로 되비춘다. */
  fileFmt: HotspotFileFmt
  /** 저장 시점의 가중치. **문서 부제 2 와 화면 부제가 같이 쓰는 값**이다(문구는 서로 다르다). */
  weights: HotspotWeight[]
  /**
   * 가중치 조정 여부. `Y` = 설정값과 다른 입력으로 계산했다.
   * 구 스냅샷은 키가 없어 `null`/없음이고 표시하지 않는다.
   */
  wgvlAjmtYn?: WgvlAjmtYn | null
  /** 점수 산정 방식. 문서 `점수 산정 기준` 문구를 고른다. */
  anlsMthd?: 'LIST_MAX' | null
  /** 생성 당시 등급 비율. 구 스냅샷은 없다. */
  gradeCriteria?: GradeCriterion[] | null
  /** 생성 당시 등급 비율 적용 내역. 구 스냅샷은 `null`. 문서에는 그리지 않는다. */
  vrblAjmt?: AnlsVrblAjmt | null
  /** 저장된 순위 목록 — **9필드 전부**. 화면 결과 갱신과 문서 렌더 양쪽에 쓴다. */
  zones: HotspotZone[]
  /** 엑셀 재계산 데이터. 형식과 무관하게 새 보고서에 실린다. */
  wbk?: HotspotWorkbook | null
}

/**
 * 집중구역 분석 보고서 생성 — **분석 결과를 DB 에 남기는 유일한 경로**다.
 *
 * 서버가 같은 조건으로 재분석해 `TB_ANLS_EXCN` · `TB_HTSPT_ANLS_RSLT` · `TB_RPTP` 세 테이블에
 * 저장하고 그 스냅샷을 201 로 돌려준다. 호출부는 이 응답으로 **문서를 만들고 화면도 갱신**한다 —
 * 화면 · 내려받은 파일 · DB 가 같은 숫자를 보게 하려는 것이다.
 *
 * 🔴 `sggNm`·`emdCd` 는 **값이 있을 때만** 바디에 얹는다(`''`/`null` → 400). 조회 함수와 같은 방어다.
 * `limit` 도 값이 있을 때만 얹어 기본값 15 를 조회와 맞춘다.
 *
 * 오류: 날짜 형식·역전 · 빈 지역값 · 미등록 시군구 · 미존재 읍면동 · limit 범위 밖 · `fileFmt` 오타 → 400.
 * 실패해도 **다운로드는 막지 않는다** — 저장 실패 폴백은 `lib/analysis/hotspotExport` 가 처리한다.
 */
export async function createHotspotReport(
  req: HotspotReportCreateRequest,
): Promise<HotspotReportResponse> {
  const body: Record<string, string | number | VrblVls> = {
    bgngYmd: req.bgngYmd,
    endYmd: req.endYmd,
    fileFmt: req.fileFmt,
  }
  if (req.sggNm) body.sggNm = req.sggNm
  if (req.emdCd) body.emdCd = req.emdCd
  if (req.limit) body.limit = req.limit
  // 🔴 `!= null` 이다 — 0 이 빠지면 안 된다(조회 함수와 같은 이유).
  if (req.cvlcptWgvl != null) body.cvlcptWgvl = req.cvlcptWgvl
  if (req.crdnWgvl != null) body.crdnWgvl = req.crdnWgvl
  // POST 는 바디 객체. 비면 키를 뺀다.
  const vrblVls = vrblVlsBody(req.vrblVls)
  if (vrblVls) body.vrblVls = vrblVls

  const { data } = await api.post<HotspotReportResponse>('/analysis/hotspot/reports', body)
  return data
}
