import { api } from './client'
import type { LifezoneWorkbookVrbl } from './lifezone'
import {
  vrblVlsBody,
  vrblVlsQuery,
  type AnlsVrblAjmt,
  type VrblVls,
} from './analysisWeight'

/*
 * 구역추천 분석 실 API 클라이언트 (요금제 적용 · 공유주차제 적용).
 * 백엔드 계약(`jeju.datahub.analysis.web.ZoneRcmdtnController` · 2026-10-12 로컬 실측):
 *   GET /api/analysis/zone-recommendation/zones → ZoneRcmdtnZone[]   (읍면동 74행 · 참고 목록)
 *   GET /api/analysis/zone-recommendation       → ZoneRcmdtnResult   (주력 — 주차장 순위 + 배정률)
 * 백엔드 계약(`…web.ZoneRcmdtnShareController` · 2026-10-13 로컬 실측 — 이 파일 맨 아래 4·5절):
 *   GET /api/analysis/zone-recommendation/share → ZoneRcmdtnShareResult (공유주차 검토 후보 읍면동)
 * 보고서 저장 · 스냅샷(이 파일 맨 아래 6절):
 *   POST /api/analysis/zone-recommendation/reports       → ZoneRcmdtnReport (요금제 적용)
 *   POST /api/analysis/zone-recommendation/share/reports → ZoneRcmdtnReport (공유주차제 적용)
 *   GET  /api/reports/{rptpId}/zone-recommendation       → ZoneRcmdtnReport (저장 시점 스냅샷)
 *
 * **창구가 둘인 이유** — 이 화면이 답하는 질문은 *"이 읍면동에서 유료화한다면 어느 주차장부터
 * 해야 하나"* 다. 읍면동은 **추천 대상이 아니라 입력**이라, 고르기 위한 참고 목록(`/zones`)과
 * 고른 뒤의 순위(`/zone-recommendation`)가 나뉘어 있다.
 *
 * 🔴 **`/zones` 는 실행 이력을 남기지 않고 `/zone-recommendation` 은 남긴다**(`TB_ANLS_EXCN` 1행 +
 * `TB_ANLS_RSLT` 1행 · 응답 `excnId`). 그래서 읍면동 select 을 채우는 호출을 주력 쪽으로 바꾸면
 * 실행 이력이 조회 로그가 된다 — 두 함수를 섞지 않는다.
 *
 * 🔴 **공용 `api` 인스턴스를 쓴다**(`client.ts`). 새 axios 인스턴스를 만들면 세션 쿠키(`withCredentials`)와
 * `X-XSRF-TOKEN` 이 빠진다. 보고서 저장 2종이 **POST** 라 이제 CSRF 가 실제로 걸린다
 * (그 헤더가 없으면 403). **401 → `/login`** 인터셉터도 그 인스턴스에만 있다.
 *
 * ⚠️ 숫자 필드 중 `cvlcptMmAvg`·`crtrIlglCnt`·`needCpcty`·`stsfRt`·`cvlcptPerSlot`·`cumCpcty`·
 * `lat`·`lot`·`asgnRt` 과 `crtrVls` 5종은 백엔드가 `BigDecimal` 이라 본문에 `300.0` 처럼 실려 오지만
 * `JSON.parse` 가 JS `number` 로 만든다 — 프론트에서는 전부 `number` 다.
 */

/* ── 1. 조회 파라미터 ──────────────────────────────────── */

/**
 * 후보 주차장 유형 범위 (§0-6).
 * - `NARROW`(기본) — 공영 · 노외(공영) · 복층화. **실제로 유료화되는 유형** 3종.
 * - `WIDE` — + 노상 · 노상(23년 조사) · 공한지. 관리요원을 두면 가능하나 전례가 드물다.
 *
 * 🔴 **설정 변수가 아니라 화면 필터다**(기획 확정 전). 저장된 기본값이 되면 *"원하는 숫자를 만드는
 * 칸"* 이 되므로 요청 파라미터로만 받고, 적용값은 응답 `crtrVls.trgtTypeSe` 와 `anls_param_json` 에
 * 굳어 어느 정의로 낸 결과인지 사후에 가릴 수 있다. 그 밖의 값은 **400** 이다.
 */
export type ZoneRcmdtnTrgtTypeSe = 'NARROW' | 'WIDE'

/**
 * 두 창구가 공유하는 조회 파라미터.
 *
 * 🔴 **민원 창(`bgngYmd`·`endYmd`)과 단속 창(`crdnBgngYmd`·`crdnEndYmd`)은 다른 구간이다.**
 * 단속 보유 구간이 민원과 달라(`tb_crdn_dsctn` 은 2025-02~2026-10, 이도이동은 2025-09~2026-07뿐)
 * 민원 창을 그대로 쓰면 단속 0건 → `E0=0` → **전 읍면동이 `E0_BELOW_CRTR`(판정 불가)** 가 된다.
 * 단속 창을 생략하면 서버가 분석 정본과 같은 20개월(`20250101`~`20260831`)을 채운다.
 *
 * ⚠️ 상한은 **한 창당 36개월**이다(공용 `DateRanges.validate` 의 366일이 아니다 — 단속 기본 창이
 * 20개월이라 그 검증에 걸린다). 넘기면 400.
 */
export interface ZoneRcmdtnParams {
  /** 민원 조회 시작일 (YYYYMMDD, 필수). 형식이 어긋나면 400. */
  bgngYmd: string
  /** 민원 조회 종료일 (YYYYMMDD, 필수 · 당일 포함). `bgngYmd` 보다 앞서면 400. */
  endYmd: string
  /** 단속 창 시작일 (YYYYMMDD). 생략하면 서버 기본값 `20250101`. */
  crdnBgngYmd?: string
  /** 단속 창 종료일 (YYYYMMDD, 포함). 생략하면 서버 기본값 `20260831`. */
  crdnEndYmd?: string
  /** 후보 주차장 유형 범위. 생략하면 서버 기본값 `NARROW`. */
  trgtTypeSe?: ZoneRcmdtnTrgtTypeSe
  /**
   * 요금제 탭 계수 10개(공통 2 + `FEE_*` 8 — 판정 계수 3개 삭제) 일시 조정. 생략·빈 객체 = 설정값.
   * 🔴 `/zones`·상세·보고서에 **같은 값**을 보낸다 — 다르면 판정이 갈린다(`buildQuery` 한 곳이 싣는다).
   */
  vrblVls?: VrblVls
}

/**
 * 상세 조회 — 위 조건 + 읍면동 1개 **또는** 시군구 1개.
 *
 * `emdCd`·`sggNm` 은 서버에 **정확히 하나만** 가야 한다(둘 다 없음·둘 다 있음 → 400).
 * 읍면동 모드에서도 화면의 시군구는 늘 선택돼 있으므로 둘 다 채워 넘겨도 된다 —
 * 🔴 배타 규칙(`emdCd` 우선)은 `fetchZoneRcmdtn` 한 곳이 진다.
 */
export interface ZoneRcmdtnDetailParams extends ZoneRcmdtnParams {
  /** 읍면동코드(8자리). 있으면 이것만 보낸다(읍면동 모드). 실재하지 않으면 **404**. */
  emdCd?: string
  /**
   * 시군구명(시군구 모드). `emdCd` 가 비었을 때만 보낸다.
   * 없는 시군구·40자 초과는 **400**.
   */
  sggNm?: string
}

/* ── 2. 응답 타입 ─────────────────────────────────────── */

/**
 * 요금제 주차장 우선도 단계 코드(`lots[].rvwGrdCd`).
 *
 * 순위값과 같은 **우선도**(`incrsRfltRvwScr` 원값)로 매긴다 — 100↑ `TOP` 최우선 · 60↑ `HIGH` 우선 ·
 * 그 밖(60 미만) `LOW` 후순위. 그래서 응답 순서대로 단계가 비감소다.
 * 🔁 2026-10-26 사용자 요청 — 4단계(보통 `MID` 30↑ 포함) → 3단계, 30 경계 삭제(종전 `MID`·`LOW` 가 `LOW` 로 합쳐졌다).
 * 🔴 **공유주차제 `ZoneRcmdtnShareGrdCd` 와 키 이름(`rvwGrdCd`)은 같지만 값 집합이 다르다** — 타입을 섞지 않는다.
 */
export type FeeRvwGrdCd = 'TOP' | 'HIGH' | 'LOW'

/**
 * 읍면동 1건(위치만). `/zones` 배열의 원소이자 상세 응답의 `zone` 과 **같은 모양**이다
 * (백엔드 `ZoneRcmdtnResultVO.Zone` 하나를 두 창구가 함께 쓴다).
 * 🔁 2026-11-11 사용자 요청 — 읍면동별 요금제 대상 판정(민원 월평균 · E0 · 필요 면수 · 전환 가능 면수 ·
 * 충족률 · 추천유형 · 판정 불가 사유)을 서버·화면·보고서에서 모두 걷었다. 순위(검토지수·종합지수)는 판정과 무관하다.
 * ⚠️ 구 보고서 스냅샷에는 옛 칸이 남아 있지만 읽지 않는다.
 */
export interface ZoneRcmdtnZone {
  /** 읍면동코드 (8자리). 예) '50110104'. */
  emdCd: string
  /** 읍면동명. 예) '이도이동'. */
  emdNm: string
  /** 시군구명. 🔴 목록을 **시군구로 묶어** 보여주는 근거다(§0-4 — 시군구를 가로지르는 순위를 만들지 않는다). */
  sggNm: string
}

/**
 * 적용된 기준값 — 응답이 되비추는 "이 결과를 만든 계수".
 *
 * 🔴 **화면 문구(`배정 범위(300m)를 바꾸면 순위가 달라집니다`)의 출처다.** 300 을 코드에 박으면
 * 설정(`RECOMMEND` 분석변수)이 바뀔 때 문구만 조용히 틀려진다.
 *
 * 읍면동 판정 기준값(`stsfRtThrsh`·`cvlcptMmCrtrVl`·`ilglCrtrVl`)을 뺐다(판정 삭제).
 */
export interface ZoneRcmdtnCrtrVls {
  /** 적용된 후보 주차장 유형 범위. 요청값을 되비춘다. */
  trgtTypeSe: ZoneRcmdtnTrgtTypeSe
  /** 최근접 배정 반경 (m) — 설정 `ALTMNT_RNG_VL`(기본 300). */
  altmntRngVl: number
  /** 후보 주차장 최소 면수 하한 — 설정 `SWTC_TRGT_MIN_CPCTY`(기본 20). */
  swtcTrgtMinCpcty: number
  /**
   * 증가율 분모 하한 (건) — `ZoneRcmdtnCalculator.MIN_TREND_BASE`(10 · 설정이 아니라 코드 상수).
   *
   * 🔴 **`cvlcptIncrsRt` 가 `null` 인 이유를 화면이 설명하는 숫자다**(§9-1). 이전 블록이 이 값
   * 미만이면 증가율을 계산하지 않는다 — 분모 3건에 최근 17건이면 그대로 계산하면 **+466.7%** 로
   * 1위가 되는데, 실제로 늘어난 것은 14건이다.
   * ⚠️ `BigDecimal` 이 아니라 `int` 라 `10` 으로 온다(다른 `crtrVls` 값과 달리 소수점이 없다).
   */
  minTrendBase: number
  /**
   * 🔁 **증가율 비교 블록 길이(일수)**. 월 단위가 폐지됐다.
   *
   * **1차면 `D`(선택 기간 전체) · 폴백이면 `D / 2`** 다. 즉 "실제로 쓴 블록 일수" 다.
   * - **1차**(기본): 최근 = 선택 기간 전체 · 이전 = **직전 같은 길이의 기간**. 🔴 그래서
   *   보조줄의 `기간 N일` 과 증가율이 보는 구간이 **일치**한다.
   * - **폴백**: 직전 기간에 민원이 **0건**이면 조회 기간을 반으로 가른다(`warnings` 에
   *   `TREND_BFR_FALLBACK`). 🔴 `D` 가 홀수면 **가운데 1일을 버려** 두 블록 일수를 같게 둔다.
   *
   * ⚠️ **폴백이면 보조줄의 `기간 N일` 과 이 값이 어긋난다**(243일 ↔ 121일) — 그래서 그때만
   * 순위 섹션 아래에 안내 한 줄을 띄운다(`noteTrendBfrFallback`).
   *
   * 🔁 **왜 월에서 일로 바뀌었나** — 월 단위는 "완전한 달" 만 셌고, 그래서 `최근1개월`
   * (9/12~10/12)은 양쪽 달이 다 불완전해 **완전한 달 0개 = 증가율 영구 `—`** 였다.
   *
   * 🔴 **화면 문구의 `N` 이 이 값이다** — `최근 N일(약 M개월) …건` · `이전 N일 10건 미만`.
   * 개월 환산은 **30일 이상일 때만** 괄호로 병기한다(15일에 "약 1개월" 은 거짓이다).
   *
   * ⚠️ **`null` 이면 증가율을 내지 않았다**(블록 7일 미만 = 조회 기간 14일 미만 · `warnings` 에
   * `TREND_WINDOW_SHORT`). `0` 이 아니라 `null` 이라 `?? ` 로 폴백을 깔지 않는다 —
   * 그 경로는 숫자를 보여주는 경로가 아니다.
   */
  trendDayCnt: number | null
  /*
   * 요금제 검토지수 설정 8키(`RECOMMEND` 분석변수 `FEE_*`). 화면 InfoTip 의 가중치·기준값 출처다.
   * ⚠️ **전부 optional** — 이 작업 전의 응답·보고서 스냅샷(`fee`)에는 키가 없다.
   */
  /** 민원강도 가중치 w1 — 설정 `FEE_CVLCPT_CNT_WGVL`(기본 70). */
  feeCvlcptCntWgvl?: number
  /** 면당압력 가중치 w2 — 설정 `FEE_CVLCPT_RT_WGVL`(기본 30). */
  feeCvlcptRtWgvl?: number
  /** 제주시 월배정민원 기준값 A (건/월) — 설정 `FEE_CVLCPT_MM_CRTR_JEJU`. */
  feeCvlcptMmCrtrJeju?: number
  /** 서귀포시 월배정민원 기준값 A (건/월) — 설정 `FEE_CVLCPT_MM_CRTR_SEOGWIPO`. */
  feeCvlcptMmCrtrSeogwipo?: number
  /** 제주시 100면당 월민원 기준값 B — 설정 `FEE_CVLCPT_RT_CRTR_JEJU`. */
  feeCvlcptRtCrtrJeju?: number
  /** 서귀포시 100면당 월민원 기준값 B — 설정 `FEE_CVLCPT_RT_CRTR_SEOGWIPO`. */
  feeCvlcptRtCrtrSeogwipo?: number
  /** 증가 가중치 wi — 설정 `FEE_CVLCPT_INCRS_WGVL`(기본 0.5). */
  feeCvlcptIncrsWgvl?: number
  /** 증가율 반영 상한 U (%) — 설정 `FEE_CVLCPT_INCRS_RT_UPLMT_VL`(기본 100). */
  feeCvlcptIncrsRtUplmtVl?: number
}

/**
 * 배정 커버리지.
 *
 * 🔴 **"순위가 민원의 몇 %를 설명하는가" 다.** 배정률 30% 는 *"민원 70% 는 이 주차장들로
 * 설명되지 않는다"* 는 뜻이라, 이 블록을 화면에서 빼면 순위가 전체를 설명하는 것처럼 보인다.
 */
export interface ZoneRcmdtnAsgn {
  /** 조회 구간 민원 총건수(= 월평균 × 개월수). */
  cvlcptTot: number
  /** 후보 주차장 배정 반경 안에 들어온 민원 합. */
  cvlcptAsgn: number
  /** 배정률 (%). 총건수가 0 이면 null. */
  asgnRt: number | null
}

/**
 * 최근 블록의 월별 배정 민원 1칸 (차트용).
 *
 * ⚠️ 배정 0건인 달도 `cnt: 0` 으로 들어 있어 **칸 수가 주차장마다 같고** 합은
 * `asgnCvlcptCntRcnt` 와 같다. 칸 수는 블록 일수가 아니라 **블록이 걸친 달 수**다
 * (15일이면 1~2칸 · 121일이면 4~5칸).
 * 🔴 **양 끝은 부분 달이다**("부분 달 버림" 은 폐지됐다 — 그 규칙이 `최근1개월`을
 * 통째로 쓸 수 없게 만들었다).
 * 🔴 단 블록이 7일 미만이면 **빈 배열**이다(`warnings` 에 `TREND_WINDOW_SHORT`).
 */
export interface ZoneRcmdtnTrendPoint {
  /** 연-월 (`YYYY-MM`). 예) '2025-06'. */
  ym: string
  /** 그 달의 배정 민원 수. */
  cnt: number
}

/**
 * 후보 주차장 1건 — 순서·순위(`rnk`)·누적(`cumCpcty`)은 서버가 매긴다(프론트 재정렬 금지).
 * 새 방식(`anlsMthd === 'RVW_SCR'`)은 **우선도(`incrsRfltRvwScr`) 내림차순**,
 * 구 스냅샷(`anlsMthd` 없음)은 **배정 민원 내림차순**이다.
 *
 * 🔴 **효과(감소 건수)는 없다** — E0 가 읍면동 단위라 주차장 단위 효과 산식이 없다. 순위는
 * *"어디부터"* 만 말한다.
 */
export interface ZoneRcmdtnLot {
  /**
   * 주차장이 속한 읍면동코드(두 모드 공통 · 하위 호환 추가).
   * 시군구 모드에서 행마다 어느 동인지 보이기 위한 칸이다. ⚠️ 옛 스냅샷에는 없다.
   */
  emdCd?: string
  /** 주차장이 속한 읍면동명. ⚠️ 옛 스냅샷에는 없다. */
  emdNm?: string
  /** 순위 — 1부터. 🔴 매퍼가 아니라 서비스가 채운다(비면 서비스 루프가 빠뜨린 것이다). */
  rnk: number
  /** 주차장 ID (`TB_PBLPKLT.pklt_id` — DB 채번. 🔴 값에 업무적 의미가 없다). */
  pkltId: number
  /** 주차장명. ⚠️ **지번이라 지도 없이는 못 읽는다** — 표 행과 핀을 반드시 함께 보여준다. */
  pkltNm: string
  /** 주차장 유형명 (공통코드 `PKLT_TYPE_CD` 의 `cd_nm`). 예) '공영'. */
  pkltTypeNm: string | null
  /** 주차면수. */
  pkltCpcty: number
  /** 주차장 주소. */
  pkltAddr: string | null
  /** 위도 (EPSG:4326). */
  lat: number | null
  /** 경도 (EPSG:4326). */
  lot: number | null
  /**
   * 배정 민원 수 (최근접 배정). 새 방식에서는 검토지수의 입력이자 **동점 1차 타이브레이크**이고,
   * 구 스냅샷에서는 이 값이 순위다.
   */
  asgnCvlcptCnt: number
  /**
   * 🔁 최근 블록 배정 민원 — 증가율의 **분자** (`…3m` 키를 대체했다).
   *
   * 블록 길이는 `crtrVls.trendDayCnt`(**일수**)다 — 월 단위를 걷었다.
   * ⚠️ 키 이름은 그대로다 — `Rcnt`(최근)는 **단위와 무관**하다.
   * 🔴 `asgnCvlcptCnt`(전 구간)와 **다른 칸**이다. 정렬은 새 방식이면 우선도(추세 반영 검토지수),
   * 구 스냅샷이면 전 구간 건수다(이 칸이 직접 정렬하지 않는다).
   * 블록이 7일 미만이면 **`null`**(0 을 넣으면 "최근 0건" 으로 읽힌다).
   */
  asgnCvlcptCntRcnt: number | null
  /**
   * 🔁 이전 블록 배정 민원 — 증가율의 **분모** (`…Prev3m` 키를 대체했다).
   *
   * 최근 블록과 **같은 일수**다(총 일수가 홀수면 가운데 1일을 버려 길이를 맞춘다).
   * 🔴 이 값이 `crtrVls.minTrendBase`(10) 미만이면 `cvlcptIncrsRt` 가 `null` 이다(§9-1).
   * ⚠️ `Bfr` 는 데이터표준사전 표준단어 **이전 = BFR**(Before)이다.
   */
  asgnCvlcptCntBfr: number | null
  /**
   * 민원 증가율 (%) = `(최근 − 이전) ÷ 이전 × 100`.
   *
   * 🔴 **`null` 이 정상값이다** — 이전 블록이 `crtrVls.minTrendBase`(10) 미만이면 계산하지 않는다
   * (0건 포함). 작은 분모에서 폭발하기 때문이다. 화면은 **`—`** 로 표시하고 그 이유를 한 줄 적는다.
   * 새 방식은 이 값이 **증가계수(`incrsCfc`)로 순서에 반영된다**(등급에는 반영되지 않는다).
   * 구 스냅샷은 정렬 기준이 아니다(순위 = `asgnCvlcptCnt` 내림차순). `null` 이면 증가계수 1 이다.
   */
  cvlcptIncrsRt: number | null
  /**
   * 최근 블록의 월별 배정 민원 — 추이 차트의 데이터원.
   * ⚠️ 칸 수는 **블록이 걸친 달 수**(0건인 달도 들어 있다)이고, 블록이 7일 미만이면
   * **빈 배열**이다.
   */
  trendMm: ZoneRcmdtnTrendPoint[]
  /** 배정 단속 수 (최근접 배정). */
  asgnCrdnCnt: number
  /** 면당 민원 = 배정 민원 ÷ 주차면수. */
  cvlcptPerSlot: number
  /**
   * 누적 주차면수 — 이 순위대로 담았을 때의 면수 합. 목록을 자르지는 않는다.
   * 시군구 모드는 **`null`** 이다.
   * `reachNeed`(필요 면수 도달)는 읍면동 판정과 함께 걷었다.
   */
  cumCpcty: number | null
  /*
   * 검토지수 칸(§0-2). ⚠️ **전부 optional** — 구 스냅샷에는 없다(`anlsMthd` 로 가른다).
   */
  /** 주차장이 속한 시군구명 — 기준값 A·B 를 고르는 키. */
  sggNm?: string | null
  /** 민원강도 원점수 s1 = 월배정민원 ÷ A × 100 (상한 없음). */
  cvlcptCntScr?: number | null
  /** 면당압력 원점수 s2 = 100면당 월민원 ÷ B × 100 (상한 없음). */
  cvlcptRtScr?: number | null
  /** 검토지수 원점수 = (w1·s1 + w2·s2) ÷ (w1 + w2) — 상한 없음. */
  rvwScr?: number | null
  /** 우선도 단계(우선도 100/60). */
  rvwGrdCd?: FeeRvwGrdCd | null
  /** 증가계수 = 1 + wi × clamp(증가율, ±U). 증가율 `null` 이면 1. */
  incrsCfc?: number | null
  /** 🔴 **순서 기준이자 단계 판정값** — 우선도 = `rvwScr × incrsCfc`. 화면 큰 값이다. */
  incrsRfltRvwScr?: number | null
}

/**
 * 후보에서 빠진 주차장의 사유 코드(**하나만** 온다).
 * 우선순위 `PAID` > `CPCTY_LACK` > `PRVT` > `TYPE_EXCL`(여러 사유에 걸려도 앞의 것 하나).
 * - `PAID` — 유료 주차장
 * - `CPCTY_LACK` — 면수가 `crtrVls.swtcTrgtMinCpcty` 미만. 🔴 **면수 NULL 도 여기**다(화면 문구 `면수 미상`)
 * - `PRVT` — 민영 주차장
 * - `TYPE_EXCL` — 후보 유형 범위(`trgtTypeSe`) 밖
 */
export type ZoneRcmdtnExclRsnCd = 'PAID' | 'CPCTY_LACK' | 'PRVT' | 'TYPE_EXCL'

/**
 * 후보 밖 주차장 1건 (지도 회색 `P` 핀 전용).
 *
 * 🔴 **목록에 넣지 않는다** — 시안도 지도 전용이다(결과 패널에 행이 없다). 그래서 순위·배정 칸이 없다.
 * 범위는 후보 SQL 과 같은 읍면동/시군구 · 소멸분 제외 · 좌표 있음.
 */
export interface ZoneRcmdtnExclLot {
  /** 주차장 ID (`TB_PBLPKLT.pklt_id` — 업무적 의미 없음). 마커 키로만 쓴다. */
  pkltId: number
  /** 주차장명 — 팝업 제목. */
  pkltNm: string
  /** 주차면수. ⚠️ **`null` 이 있다**(면수 미상 — `CPCTY_LACK` 로 온다). */
  pkltCpcty: number | null
  /** 위도 (EPSG:4326). 백엔드가 좌표 없는 행을 빼지만 타입은 후보와 맞춘다. */
  lat: number | null
  /** 경도 (EPSG:4326). */
  lot: number | null
  /** 후보에서 빠진 사유(하나). */
  exclRsnCd: ZoneRcmdtnExclRsnCd
}

/** GET /api/analysis/zone-recommendation 응답 루트. */
export interface ZoneRcmdtnResult {
  /**
   * 실행 범위. `EMD`(읍면동) | `SGG`(시군구).
   * 🔴 모드는 이 칸으로 가른다 — `zone == null` 로 추측하지 않는다.
   * ⚠️ 이 칸이 생기기 전의 스냅샷에는 없다 — **없으면 `EMD`** 로 간주한다.
   */
  scope?: 'EMD' | 'SGG'
  /**
   * 시군구명. 시군구 모드는 요청값(trim), 읍면동 모드는 `zone.sggNm` 과 같다.
   * ⚠️ 옛 스냅샷에는 없다.
   */
  sggNm?: string | null
  /**
   * 분석 방법. `'RVW_SCR'` 이면 **검토지수 방식**(순서 = 우선도 · 색 = `rvwGrdCd` 우선도 단계)이다.
   * 🔴 **없으면 구 방식**(순서 = 배정 민원 · 색 = min-max 3등분 `feePrrt*`)으로 그린다 —
   * 이 작업 전에 만든 보고서 스냅샷에는 키가 없다. 판정은 `anlsMthd === 'RVW_SCR'` 하나다.
   */
  anlsMthd?: 'RVW_SCR' | null
  /** 분석실행 ID (`TB_ANLS_EXCN` PK). 적재 실패 시 null. */
  excnId: number | null
  /** 적용된 기준값. 화면 문구의 숫자는 **전부 여기서 온다**. */
  crtrVls: ZoneRcmdtnCrtrVls
  /**
   * 경고 코드 목록. 정상이면 **빈 배열**이다. 현재 값 3종:
   * - `COEF_FALLBACK` — 분석변수 행이 없어 코드 상수로 떨어졌다(평시 경로가 아니다).
   * - `TREND_WINDOW_SHORT` — 🔁 조회 기간이 **14일 미만**이라 블록이 7일을 못 채운다
   *   ("완전한 달 2개 미만" 판정은 폐지됐다 — `최근1개월`도 이제
   *   15+15 로 증가율이 나온다).
   *   이때 `lots[].asgnCvlcptCntRcnt`·`asgnCvlcptCntBfr`·`cvlcptIncrsRt` 가 **전부 `null`**,
   *   `trendMm` 이 **빈 배열**, `crtrVls.trendDayCnt` 도 `null` 이다.
   *   🔴 **분모 미달(`minTrendBase`)과 다른 사유다** — 화면 문구를 갈라 쓴다.
   * - `TREND_BFR_FALLBACK` — 🔁 **증가율의 "이전" 이 반 분할로 내려앉았다**.
   *   1차는 **직전 같은 길이의 기간**을 분모로 쓰는데, 그 기간에 이 읍면동의 배정 민원이
   *   **한 건도 없으면** 조회 기간을 반으로 갈라 비교한다. 🔴 `ocrn_dt` 보유 구간이
   *   **2026-01-01 ~ 2026-10-31(304일)** 뿐이라 `D ≤ 152일`(약 5개월)에서만 1차가 성립한다 —
   *   드문 경로가 아니다(정본 창 243일이 바로 폴백이다).
   *   🔴 **판정은 읍면동 단위로 한 번**이라 표 안에서 행마다 방식이 갈리지 않는다.
   *   🔴 이때 `crtrVls.trendDayCnt` 가 `D` 가 아니라 `D / 2` 이므로 **안내 한 줄을 띄운다**
   *   (`noteTrendBfrFallback`) — 보조줄의 `기간 N일` 과 어긋나는 것을 설명하지 않으면
   *   사용자가 증가율이 어느 구간을 본 것인지 알 수 없다.
   */
  warnings: string[]
  /**
   * 읍면동 판정. `/zones` 원소와 같은 모양이다.
   * 시군구 모드는 **`null`**(시군구 판정은 정의된 적이 없다).
   */
  zone: ZoneRcmdtnZone | null
  /** 배정 커버리지. 시군구 모드는 **`null`**. */
  asgn: ZoneRcmdtnAsgn | null
  /**
   * 후보 주차장 순위 — **전 후보가 들어 있다.**
   * ⚠️ **빈 배열도 200 이며 빈 화면이 아니라 결론이다** — "유료화할 공영주차장이 없음 → 공유주차제 검토".
   *
   * **증가율(`cvlcptIncrsRt`)이 `null` 인 주차장도 여기 있다.** 그 행을
   * 순위 계산 전에 걷어냈던 것을 **사용자 결정(2026-10-13)으로 철회**했다 — 증가율은 참고 지표
   * 하나일 뿐인데 그것으로 후보가 목록·지도에서 사라지면 "유료화 후보" 목록이 불완전해지고,
   * 누적 면수 최대값이 요약의 `전환 가능 면수` 와 어긋났다. 🔴 **같은 제안을 다시 하지 않는다.**
   * 증가율이 없는 행은 `—` + 회색 점으로 그린다.
   */
  lots: ZoneRcmdtnLot[]
  /**
   * 후보 밖 주차장(지도 회색 핀). 두 모드 공통.
   * ⚠️ **옵셔널이다** — 배포 전 응답·옛 스냅샷에는 키가 없다. 🔴 보고서 스냅샷·생성 응답에는
   * 항상 **빈 배열**이다(지도 참고용이라 문서에 남기지 않는다).
   */
  exclLots?: ZoneRcmdtnExclLot[]
  /**
   * 요금제 탭 계수 10행(구 스냅샷 13행)의 적용 내역(상세만 — `/zones` 에는 없다). 구 스냅샷은 키가 없고,
   * 보고서 응답에서는 본문(`fee.vrblAjmt`)이 `null` 이고 머리 `vrblAjmt` 에 온다. 문서에는 그리지 않는다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
  /**
   * 🆕 엑셀 재계산 데이터 — **보고서 저장 응답·스냅샷에만** 온다(조회 GET 에는 키가 없다).
   * `RVW_SCR` 방식 새 보고서는 형식(PDF·DOCX·XLSX)과 무관하게 실리고, 이 작업 전 스냅샷은 `null`/없음.
   */
  wbk?: ZoneRcmdtnFeeWorkbook | null
}

/**
 * 엑셀 분석 계수 1행(`wbk.vrbls[]` — 백엔드 공용 `AnlsWorkbookVrblVO`). 생활권 행에 `inptYn` 이 더해진 모양이다.
 * 🔴 엑셀 입력 셀 초기값의 유일한 출처는 `aplcnVl`(이번 실행이 실제로 쓴 값)이다.
 */
export interface ZoneRcmdtnWorkbookVrbl extends LifezoneWorkbookVrbl {
  /**
   * 입력 셀 여부 — `Y` 노란 입력 셀 · `N` 회색 조건 셀(공간 계수 · 시군구 모드 판정 계수 · `=100-민원강도` 짝 ·
   * 설정 행 없음/꺼짐). 🔴 판정은 서버가 했다 — 프론트가 다시 정하지 않는다.
   */
  inptYn: 'Y' | 'N'
}

/** 요금제 엑셀 재계산 데이터(백엔드 `ZoneRcmdtnFeeWorkbookVO`). 주차장 행은 응답 `lots[]` 를 그대로 쓴다. */
export interface ZoneRcmdtnFeeWorkbook {
  /** 조회 개월 수(`PrdMonths` — 소수 가능). 월평균의 분모. */
  months: number
  /**
   * 고정 상수 — 종합지수 단계 경계(`PRRT_TOP`·`PRRT_HIGH`).
   * 🔁 2026-11-11 — 읍면동 판정 원값(`zoneBase`)·판정 상수(적발배수·기준점유율·유료수요율)는 싣지 않는다(엑셀에 판정을 두지 않는다).
   */
  fixed: {
    prrtTop: number
    prrtHigh: number
  }
  /** `RECOMMEND_FEE` 10행(설정 순서 · 구 스냅샷 13행). */
  vrbls: ZoneRcmdtnWorkbookVrbl[]
}

/* ── 3. 조회 함수 ─────────────────────────────────────── */

/** 값이 있을 때만 쿼리에 얹는다 — 빈 문자열을 그대로 보내면 화이트리스트·형식 검증에 걸린다. */
function buildQuery(params: ZoneRcmdtnParams): Record<string, string> {
  const query: Record<string, string> = {
    bgngYmd: params.bgngYmd,
    endYmd: params.endYmd,
  }
  if (params.crdnBgngYmd) query.crdnBgngYmd = params.crdnBgngYmd
  if (params.crdnEndYmd) query.crdnEndYmd = params.crdnEndYmd
  if (params.trgtTypeSe) query.trgtTypeSe = params.trgtTypeSe
  // GET 은 JSON 문자열 1개. 비면 키를 뺀다 — `/zones`·상세가 이 함수 하나로 같은 값을 싣는다.
  const vrblVls = vrblVlsQuery(params.vrblVls)
  if (vrblVls) query.vrblVls = vrblVls
  return query
}

/**
 * 읍면동 참고 목록 — **읍면동 select 을 채우는 창구**다(`TB_ADMDST` 전량 74행).
 *
 * 🔴 원천이 0건인 동도, 제외 규칙에 걸린 동도 빠지지 않고 `rsnCd` 와 함께 온다.
 * ⚠️ 실행 이력을 남기지 않는다 — 동을 고르려고 매번 부르는 경로라서다.
 *
 * 오류: 날짜 형식 오류·역전 · 36개월 초과 · `trgtTypeSe` 오타 → 400.
 */
export async function fetchZoneRcmdtnZones(params: ZoneRcmdtnParams): Promise<ZoneRcmdtnZone[]> {
  const { data } = await api.get<ZoneRcmdtnZone[]>('/analysis/zone-recommendation/zones', {
    params: buildQuery(params),
  })
  return data
}

/**
 * 선택한 읍면동의 판정 + 후보 주차장 순위 + 배정 커버리지 (**주력**).
 * 읍면동 없이 시군구만 주면 그 시군구 안 전 읍면동의 후보 주차장 순위(한 목록)를
 * 내고 `zone`·`asgn` 은 `null`, `lots[].cumCpcty` 는 `null` 이다(`scope: 'SGG'`).
 *
 * 🔴 호출 1회가 **실행 이력 1행**이다(`TB_ANLS_EXCN` + `TB_ANLS_RSLT`) — 실행 버튼을 누를 때만
 * 부르고, 리렌더마다 부르지 않는다.
 *
 * 🔴 **배타 규칙은 이 함수 한 곳이다** — `emdCd` 가 있으면 `emdCd` 만, 없을 때만 `sggNm` 을 싣는다.
 * 읍면동 모드에서도 시군구는 늘 선택돼 있어 "값 있는 쪽만" 으로 실으면 둘 다 나가 400 이다.
 * 한글 `sggNm` 은 axios `params` 로 보낸다(`/share` 관례).
 *
 * 오류: 날짜 형식 오류·역전 · 36개월 초과 · `trgtTypeSe` 오타 → 400 ·
 * `emdCd`·`sggNm` 둘 다 없음/둘 다 있음 · 없는 시군구 · 시군구명 40자 초과 → 400 ·
 * 미존재 읍면동 → 404.
 */
export async function fetchZoneRcmdtn(params: ZoneRcmdtnDetailParams): Promise<ZoneRcmdtnResult> {
  const scope: Record<string, string> = params.emdCd
    ? { emdCd: params.emdCd }
    : params.sggNm
      ? { sggNm: params.sggNm }
      : {}
  const { data } = await api.get<ZoneRcmdtnResult>('/analysis/zone-recommendation', {
    params: { ...scope, ...buildQuery(params) },
  })
  return data
}

/* ── 4. 공유주차제 적용 — 타입 ─────────
 *
 * 🔴 **요금제 트랙과 단위가 다르다.** 위 1~3절은 읍면동을 **고른 뒤** 그 안의 주차장 순위를 내고,
 * 이쪽은 기간만 주면 **읍면동 목록**을 낸다(지역은 입력이 아니라 필터다 — 화면은 시군구만 건다).
 *
 * 🔴 **`/share` 는 호출마다 실행 이력을 남긴다**(`TB_ANLS_EXCN` 1행 + `TB_ANLS_RSLT` 1행 ·
 * `anls_param_json.rcmdtnTypeCd = SHARE`). `/zones` 처럼 마운트·조건 변경에 맞춰 부르면 이력이
 * 조회 로그가 된다 — 화면은 **`실행` 버튼의 핸들러에서만** 부른다.
 *
 * ⚠️ 타입은 **실 응답 원문**(2026-10-13 E2E `share_default.json`·`share_sgp.json`·`share_empty.json`)에
 * 맞췄다. 키 이름이 요금제 쪽과 다르다 — 등급은 `rvwGrdCd`, 주의 표시는 `cutnCds` 다.
 */

/**
 * 검토 등급 코드 (공통코드 `SHRN_RVW_GRD_CD`).
 *
 * 🔴 **라벨을 여기 적지 않는다** — 정본은 공통코드의 `cd_nm` 이다(위 `ZoneRcmdtnTypeCd` 와 같은 규칙).
 * 🔁 **화면의 검토 등급은 이 코드가 아니다**(2026-10-13) — `rvwScr` 로 정하는 3단계다
 * (`zoneRcmdtnView.shareLvl`). 화면이 이 코드를 읽는 곳은 `UNABLE` 인지 묻는 한 군데뿐이고,
 * 공통코드 라벨도 그 하나에만 쓴다.
 * - `CORE` · `CAND` — 후보다(`rvwTrgtYn = 'Y'` · `rvwScr >= 1`). `CORE` 는 `rvwScr >= crtrVls.coreMltp`.
 *   이 둘에만 지도 깃발이 선다(🔁 순번 `rnk` 는 이제 후보 밖에도 있다).
 * - `CVLCPT_ONLY` — 월평균 민원 기준은 넘고 100면당 기준은 못 넘었다.
 * - `UNABLE` — 읍면동 안에 등록된 공영주차장이 없어(`pkltCpcty = 0`) 비율을 내지 못했다
 *   (`rvwScr`·`rnk` 가 `null`).
 * - `NONE` — 그 밖.
 */
export type ZoneRcmdtnShareGrdCd = 'CORE' | 'CAND' | 'CVLCPT_ONLY' | 'UNABLE' | 'NONE'

/**
 * 주의 표시 코드 — 후보(`CORE`·`CAND`)에만 붙는다. 🔁 응답에는 계속 오지만 **화면은 쓰지 않는다**(2026-10-13).
 * - `TOP_PNT` — 민원이 가장 많은 좌표 1곳을 빼고 다시 판정하면 후보 조건에 못 미친다.
 * - `CRTR_BNDRY` — 월평균 민원 또는 100면당 민원이 적용 기준의 +5% 안쪽이다.
 */
export type ZoneRcmdtnShareCutnCd = 'TOP_PNT' | 'CRTR_BNDRY'

/** `/share` 조회 파라미터. */
export interface ZoneRcmdtnShareParams {
  /** 민원 조회 시작일 (YYYYMMDD, 필수). 형식이 어긋나면 400. */
  bgngYmd: string
  /** 민원 조회 종료일 (YYYYMMDD, 필수 · 당일 포함). 역전·36개월 초과면 400. */
  endYmd: string
  /** 시군구명 필터(40자 이내). 생략하면 걸지 않는다. 없는 이름이면 400. */
  sggNm?: string
  // 🔁 `emdCd`(읍면동 필터)는 뺐다 — 백엔드 API 는 계속 받지만 화면은 보내지 않는다(시군구만 고른다).
  /**
   * 공유주차제 탭 계수 4개 일시 조정. 생략·빈 객체 = 설정값. 요금제 변수(`FEE_*` 등)는 400.
   */
  vrblVls?: VrblVls
}

/**
 * 적용된 판정 기준 — 제목 밑 산출 설명(`검토 지수 = …`)의 숫자가 **전부 여기서 온다**.
 * 🔴 50·7·2 를 코드에 박지 않는다 — `RECOMMEND` 분석변수가 바뀌면 문구만 조용히 틀려진다.
 */
export interface ZoneRcmdtnShareCrtrVls {
  /** 월평균 민원 기준 (건/월) — 설정 `CVLCPT_MM_CRTR_VL`. 꼬리 0 을 걷은 값으로 온다(`50`). */
  cvlcptMmCrtrVl: number
  /** 공영 100면당 월평균 민원 기준 (건) — 설정 `CVLCPT_RT_CRTR_VL`(`7`). */
  cvlcptRtCrtrVl: number
  /** `CORE` 배수 — 설정이 아니라 백엔드 코드 상수(`2`). */
  coreMltp: number
  /** 조회 개월 수 — 월평균 분모. 달별 조회 비율 합이라 소수 가능(`1.005376`), 온전한 달이면 정수(`8`). */
  months: number
  /**
   * 민원증가가중치 `w` (설정 `RECOMMEND.CVLCPT_INCRS_WGVL` · 기본 `0.5`).
   * 증가계수 = `1 + w × clamp(증가율, ±incrsRtUplmtVl)`. `0` 이면 추세 미반영(계수가 항상 1).
   * ⚠️ **이 작업 이전에 저장된 보고서 스냅샷에는 키가 없다**(`undefined`) — 그때는 계수 범위 문장을 생략한다.
   */
  incrsWgvl?: number | null
  /** 민원증가율상한값 (%) — 설정 `RECOMMEND.CVLCPT_INCRS_RT_UPLMT_VL`(기본 `100`). 옛 스냅샷에는 없다. */
  incrsRtUplmtVl?: number | null
  /**
   * 증가율 분모 하한 (건) — 코드 상수(요금제 `crtrVls.minTrendBase` 와 같은 `10`).
   * 이전 블록이 이 값 미만이면 `cvlcptIncrsRt` 가 `null` · `incrsCfc` 가 `1` 이다. 옛 스냅샷에는 없다.
   */
  minTrendBase?: number | null
  /**
   * 증가율 블록 일수. 1차(직전 동일 기간)면 조회 일수 `D`, 반 분할 폴백(`TREND_BFR_FALLBACK`)이면
   * `D / 2`, 조회 기간 14일 미만(`TREND_WINDOW_SHORT`)이면 `null`. 요금제 `crtrVls.trendDayCnt` 와 같은 뜻이다.
   * 옛 스냅샷에는 없다.
   */
  trendDayCnt?: number | null
}

/**
 * 요약. 🔴 **지역 필터와 무관한 도 전체 값이다** — `sggNm` 을 걸어도 달라지지 않는다
 * (`zones` 만 줄어든다). 그래서 필터를 건 화면은 이 숫자가 "도 전체 기준" 임을 밝혀야 한다.
 */
export interface ZoneRcmdtnShareSmry {
  /** 판정한 읍면동 수(도 전체). */
  emdCnt: number
  /** 후보 수 = `CORE` + `CAND`. */
  rvwTrgtCnt: number
  /** `CORE` 수. */
  coreCnt: number
  /** `CVLCPT_ONLY` 수. */
  cvlcptOnlyCnt: number
  /** `UNABLE` 수. ⚠️ 주차장 유무로 정해져 민원이 0건인 기간에도 0 이 아니다. */
  unableCnt: number
  /** 공한지 주차장을 공급에서 뺐을 때의 후보 수. */
  vaclotExclRvwTrgtCnt: number
  /** 조회 구간 민원 총건수(도 전체). */
  cvlcptTot: number
  /** 후보 읍면동의 민원이 전체에서 차지하는 비중 (%). 🔴 **민원이 0건이면 `null`**. */
  rvwTrgtCvlcptRt: number | null
}

/**
 * 읍면동 판정 1건.
 *
 * 🔴 **등급·검토 지수는 필터와 무관하다**(도 전체로 볼 때와 같은 값). **순번(`rnk`)만 조회 결과
 * (필터 적용 후) 안에서 매긴다** — 서귀포시만 보면 첫 행 강정동의 `rnk` 가 `1` 이다(도 전체로는 `14`).
 */
export interface ZoneRcmdtnShareZone {
  /**
   * 표시 순번 — 🔴 **우선순위가 아니다.** 지도 깃발의 숫자이자 목록 행의 번호다.
   *
   * 🔁 **2026-10-13 뜻이 바뀌었다** — 종전에는 후보에만 `1..18` 이었다. 이제 **`rvwScr` 이 있는 모든
   * 읍면동**에 `1..n`(실측 — 도 전체 65)이 붙고, `UNABLE` 만 `null` 이다.
   * **순서 = 추세 반영 지수(`incrsRfltRvwScr` · 화면 표기 우선도) 내림차순 → 민원 건수
   * 내림차순 → `emdCd`.** 종전의 "후보 여부 먼저" 키는 걷었다 — 기준 미달 읍면동이 후보 앞에 설 수
   * 있어 **후보(깃발)의 번호가 1..n 으로 이어지지 않을 수 있다.** ⚠️ 저장된 보고서 스냅샷의 `rnk` 는 저장 당시
   * 규칙이다(시기에 따라 검토 지수 내림차순 또는 후보 먼저 → 추세 반영 지수).
   * 🔁 **모집단은 응답에 실린 행이다** — `sggNm` 을 걸면 그 시군구 안에서 1 부터 다시 이어진다.
   * 프론트는 다시 계산하지 않는다.
   * 🔴 그래서 **"후보인가" 를 `rnk != null` 로 묻지 않는다** — 그 질문은 `rvwTrgtYn === 'Y'` 다
   * (`rnk` 로 거르면 지도에 65개 깃발이 선다).
   */
  rnk: number | null
  /**
   * 🆕 **검토 지수** = `min(cvlcptMmAvg ÷ crtrVls.cvlcptMmCrtrVl, cvlcptRt ÷ crtrVls.cvlcptRtCrtrVl)`.
   * 두 조건 각각의 "기준 대비 배수" 중 **작은 값**이다. 서버가 소수 2자리로 **버려서** 준다
   * (프론트가 다시 계산하지 않는다 — 버림 규칙이 갈리면 `0.99` 가 `1.00` 으로 보인다).
   *
   * - `1` 이상 = 후보(`rvwTrgtYn = 'Y'`) · `crtrVls.coreMltp`(2) 이상 = `CORE`.
   * - 🔴 **`UNABLE`(공영 면수 0)이면 `null`** — 분모가 0 이라 지수가 없다(0 을 채우지 않는다).
   *
   * ⚠️ 필드명이 `rvwScr` 인 이유 — 표준사전에 `지수` 가 없어 `검토 RVW` + `점수 SCR` 을 썼다.
   * 🔴 **화면 표기는 "검토 지수"** 다.
   * ⚠️ 백엔드 배포 전에는 **키 자체가 없을 수 있다**(`undefined`) — 화면은 `null` 과 같게 다룬다
   * (`fmtRvwScr`). 타입은 계약대로 `number | null` 로 둔다.
   */
  rvwScr: number | null
  /** 읍면동코드 (8자리). */
  emdCd: string
  /** 시군구명. 🔁 응답에는 계속 오지만 **화면은 쓰지 않는다**(2026-10-13 — 행·팝업에서 걷었다). */
  sggNm: string
  /** 읍면동명. */
  emdNm: string
  /** 읍면동 **중심** 위도 (`tb_admdst.ctr_lat`). 🔴 민원이 몰린 지점이 아니다. 없으면 `null`. */
  lat: number | null
  /** 읍면동 중심 경도. 없으면 `null`. */
  lot: number | null
  /** 조회 구간 민원 건수(미분류 99 제외). */
  cvlcptCnt: number
  /** 월평균 민원 (건/월 · 소수 1자리). */
  cvlcptMmAvg: number
  /** 공영주차장 수(소멸분 제외). */
  pkltCnt: number
  /** 공영 주차면수 합. `0` 이면 `UNABLE` 이다. */
  pkltCpcty: number
  /** 공영 100면당 월평균 민원. 🔴 **`UNABLE` 이면 `null`** (분모가 0 이다 — 0 을 채우지 않는다). */
  cvlcptRt: number | null
  /** 검토 등급(서버 판정). 🔁 화면은 `UNABLE` 인지에만 쓴다 — 표시 등급은 `rvwScr` 로 정한다. */
  rvwGrdCd: ZoneRcmdtnShareGrdCd
  /** 후보 여부 (`Y`/`N`) — `CORE`·`CAND` 면 `Y`. */
  rvwTrgtYn: 'Y' | 'N'
  /** 주의 표시. 없으면 **빈 배열**이다. 🔁 **화면은 쓰지 않는다**(2026-10-13 — 주의 배지를 걷었다). */
  cutnCds: ZoneRcmdtnShareCutnCd[]
  /** 민원 최다 좌표 1곳의 비중 (%). 민원이 0건이면 `null`. 🔁 화면은 쓰지 않는다(위 주의 배지의 숫자였다). */
  topPntRt: number | null
  /** 공한지를 뺀 면수로 계산한 100면당 월평균 민원. 그 면수가 0 이면 `null`. */
  vaclotExclCvlcptRt: number | null
  /** 공한지를 뺀 값으로 판정했을 때의 후보 여부 (`Y`/`N`). */
  vaclotExclRvwTrgtYn: 'Y' | 'N'
  /*
   * 민원 추세 4필드. 🔴 **이 작업 이전에 저장된 보고서 스냅샷에는 키가 없다**
   * (다시 계산하지 않는다) — 그래서 전부 optional 이고, 화면·보고서는 부재·`null` 을 `—` 로 그린다.
   */
  /**
   * 증가율의 분모로 쓴 **이전 블록** 민원 건수(1차 = 직전 동일 기간, 폴백 = 조회 기간 앞 `D/2`).
   * 이전 블록이 0건이면 `0`, 조회 기간 14일 미만이면 `null`. ⚠️ `Bfr` = 표준단어 이전 `BFR`.
   */
  bfrCvlcptCnt?: number | null
  /**
   * 민원 증가율 (%, 소수 1자리) = `(최근 − 이전) ÷ 이전 × 100`.
   * 🔴 **`null` 이 정상값이다** — 이전 블록이 `crtrVls.minTrendBase`(10) 미만이거나 조회 기간 14일 미만.
   * 화면은 `—` 로 쓴다(`fmtIncrsRt` — 0% 로 쓰지 않는다).
   */
  cvlcptIncrsRt?: number | null
  /**
   * 증가계수 = `1 + crtrVls.incrsWgvl × clamp(증가율, ±crtrVls.incrsRtUplmtVl)` (소수 3자리).
   * 증가율이 `null` 이면 `1`(추세 중립). 기본 설정이면 `0.5 ~ 1.5`.
   */
  incrsCfc?: number
  /**
   * 🆕 **추세 반영 지수** = `rvwScr × incrsCfc` (서버가 소수 2자리로 **버린다** — `rvwScr` 와 같은 관례).
   * 🔴 **순서(`rnk`)에만 쓴다 — 화면 등급(`shareLvl`)·후보 판정은 여전히 `rvwScr` 다**. 1 이상이어도 후보가
   * 아닐 수 있고, 1 미만이어도 후보일 수 있다. `UNABLE` 이면 `null`. 목록 행의 큰 값이 이것이다.
   */
  incrsRfltRvwScr?: number | null
}

/** GET /api/analysis/zone-recommendation/share 응답 루트. */
export interface ZoneRcmdtnShareResult {
  /** 분석실행 ID (`TB_ANLS_EXCN` PK). */
  excnId: number | null
  /** 적용된 판정 기준. */
  crtrVls: ZoneRcmdtnShareCrtrVls
  /**
   * 민원 **자료 구간** 시작일 (YYYYMMDD) — 조회 기간과 무관하다.
   * 🔴 집중구역 API 의 같은 이름 필드와 섞지 않는다(그쪽은 단속 구간이 섞인 값이다).
   */
  dataBgngYmd: string | null
  /** 민원 자료 구간 종료일 (YYYYMMDD). */
  dataEndYmd: string | null
  /**
   * 경고 코드. 정상이면 **빈 배열**.
   * - `NO_CVLCPT_IN_RANGE` — 조회 기간에 민원이 0건이다. 이때도 200 이고 `zones` 는 전 읍면동이
   *   후보 아님으로 온다(`smry.rvwTrgtCvlcptRt` 는 `null`).
   * - 🆕 `TREND_WINDOW_SHORT` — 조회 기간 14일 미만이라 증가율을 계산하지 않았다
   *   (전 행 `cvlcptIncrsRt` `null` · `incrsCfc` `1` · `crtrVls.trendDayCnt` `null`). 문구는 요금제 `NOTE_TREND_WINDOW_SHORT` 재사용.
   * - 🆕 `TREND_BFR_FALLBACK` — 직전 동일 기간이 **전 읍면동 합 0건**이라 조회 기간을 반으로 갈라 비교했다
   *   (🔴 판정은 도 전체 1회 — 요금제는 읍면동 단위다). 문구는 요금제 `noteTrendBfrFallback` 재사용.
   */
  warnings: string[]
  /** 요약 — 🔴 도 전체 값. */
  smry: ZoneRcmdtnShareSmry
  /**
   * 읍면동 판정 목록 — 🔴 **후보만이 아니라 필터에 걸린 읍면동 전부**다.
   * 🔁 순서는 **`rnk` 오름차순(= 추세 반영 지수 내림차순 → 민원 건수 내림차순 →
   * `emdCd`) → 그 뒤에 `UNABLE`** 이며 서버가 정한다
   * (프론트 재정렬 금지). 🔴 **후보가 앞쪽에 모여 있다고 가정하지 않는다** — 후보인지는 `rvwTrgtYn` 으로 묻는다.
   */
  zones: ZoneRcmdtnShareZone[]
  /**
   * 공유주차제 탭 계수 4행의 적용 내역. 보고서 응답에서는 본문(`share.vrblAjmt`)이 `null` 이고
   * 머리 `vrblAjmt` 에 온다. 문서에는 그리지 않는다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
  /** 🆕 엑셀 재계산 데이터 — 보고서 저장 응답·스냅샷에만 온다. 이 작업 전 스냅샷은 `null`/없음. */
  wbk?: ZoneRcmdtnShareWorkbook | null
}

/**
 * 공유주차제 엑셀 원자료 1행(백엔드 `ZoneRcmdtnShareWorkbookRowVO`) — 응답 `zones` 와 같은 읍면동(필터 뒤).
 * `rcntCnt`·`bfrCnt` 는 서버가 이미 고른 블록(`trendMode`)의 건수이고 `NONE` 이면 `null` 이다.
 */
export interface ZoneRcmdtnShareWorkbookRow {
  emdCd: string
  sggNm: string
  emdNm: string
  cvlcptCnt: number
  pkltCnt: number
  pkltCpcty: number
  /** 민원 최다 좌표 1곳의 건수(`TOP_PNT` 주의 판정). */
  topPntCvlcptCnt: number
  /** 공한지를 뺀 공영 면수(공한지 제외 후보 판정). */
  vaclotExclPkltCpcty: number
  rcntCnt: number | null
  bfrCnt: number | null
}

/** 공유주차제 엑셀 재계산 데이터(백엔드 `ZoneRcmdtnShareWorkbookVO`). */
export interface ZoneRcmdtnShareWorkbook {
  /** 읍면동 원자료 — `cvlcptCnt↓ · emdCd↑`(서버가 그 순서로 싣는다). */
  rows: ZoneRcmdtnShareWorkbookRow[]
  /** 조회 개월 수(소수 가능). */
  months: number
  /** 증가율 블록 방식(도 전체 1회 판정 — 계수와 무관). `NONE` 이면 증가율을 내지 않는다. */
  trendMode: 'NONE' | 'PRIMARY' | 'FALLBACK'
  /** 고정 상수 — 핵심 배수 · 경계 주의 비율(1.05) · 증가율 분모 하한(10). */
  fixed: { coreMltp: number; bndryRt: number; minTrendBase: number }
  /** `RECOMMEND_SHARE` 4행. */
  vrbls: ZoneRcmdtnWorkbookVrbl[]
}

/* ── 5. 공유주차제 적용 — 조회 함수 ───────────────────── */

/**
 * 공유주차 검토 후보 읍면동 판정.
 *
 * 🔴 호출 1회가 **실행 이력 1행**이다 — `실행` 버튼의 핸들러에서만 부른다
 * (effect 에서 부르면 StrictMode 이중 실행·탭 왕복마다 이력이 쌓인다).
 *
 * ⚠️ 한글 `sggNm` 은 URL 에 직접 잇지 않고 axios `params` 로 넘긴다 — 인코딩을 axios 가 한다.
 * 빈 문자열은 싣지 않는다(백엔드도 빈 값은 "걸지 않음" 으로 읽지만 요청을 깨끗이 둔다).
 *
 * 오류: 날짜 형식 오류·역전 · 36개월 초과 · 없는 `sggNm` · 40자를 넘는 `sggNm` → 400
 * (그때는 이력이 남지 않는다).
 */
export async function fetchZoneRcmdtnShare(
  params: ZoneRcmdtnShareParams,
): Promise<ZoneRcmdtnShareResult> {
  const query: Record<string, string> = { bgngYmd: params.bgngYmd, endYmd: params.endYmd }
  if (params.sggNm) query.sggNm = params.sggNm
  const vrblVls = vrblVlsQuery(params.vrblVls)
  if (vrblVls) query.vrblVls = vrblVls
  const { data } = await api.get<ZoneRcmdtnShareResult>('/analysis/zone-recommendation/share', {
    params: query,
  })
  return data
}

/* ── 6. 보고서 저장 · 스냅샷 ───────────
 *
 * 🔴 **내보내기 = 저장**이다. 서버가 같은 조건으로 **다시 계산해** 저장하고 그 스냅샷을 201 로 돌려준다 —
 * 그래서 요청에는 조회 조건과 `fileFmt` 만 있다(화면이 보낸 순위를 굳히면 조작된 순위가 보고서로 남는다).
 * 🔴 **요청에 사용자 식별 칸이 없다** — 생성자는 서버가 세션(로그인 사용자)에서 읽는다.
 *
 * 🔴 **두 보기가 분석유형코드(`RECOMMEND`)를 함께 쓴다** — 보고서 목록의 `anlsTypeCd` 만으로는 요금제와
 * 공유주차제를 가를 수 없어, 응답 최상위 `rcmdtnTypeCd` 가 본문 모양을 정한다(`fee` 또는 `share` 중 하나만 채운다).
 */

/** 보고서 파일형식. 🔴 대문자다 — 그 밖의 값은 400. 🆕 `XLSX`(엑셀도 보고서 1건). */
export type ZoneRcmdtnFileFmt = 'PDF' | 'DOCX' | 'XLSX'

/**
 * POST /api/analysis/zone-recommendation/reports 요청 (요금제 적용).
 * 조회(`fetchZoneRcmdtn`)의 쿼리 파라미터와 **같은 이름 · 같은 규칙**이다 — 범위(`emdCd` 또는 `sggNm`)와
 * 민원 창은 필수, 단속 창과 유형 범위는 생략할 수 있다(생략하면 서버 기본값 — 유형 범위는 `NARROW`).
 * 범위는 `emdCd`·`sggNm` **중 정확히 하나**다(배타 — 둘 다/둘 다 없음은 400).
 */
export interface ZoneRcmdtnFeeReportCreateRequest {
  /** 읍면동코드(8자리). 실재하지 않으면 404. `sggNm` 과 배타(있으면 이쪽만 보낸다). */
  emdCd?: string
  /** 시군구명(시군구 모드, 40자 이내). `emdCd` 가 비었을 때만 보낸다. 없는 시군구면 400. */
  sggNm?: string
  /** 민원 창 시작일 (YYYYMMDD, 필수). */
  bgngYmd: string
  /** 민원 창 종료일 (YYYYMMDD, 필수 · 당일 포함). */
  endYmd: string
  /** 단속 창 시작일 (YYYYMMDD). 생략하면 서버 기본값. */
  crdnBgngYmd?: string
  /** 단속 창 종료일 (YYYYMMDD, 포함). 생략하면 서버 기본값. */
  crdnEndYmd?: string
  /** 후보 주차장 유형 범위. 🔴 화면이 조회에 쓴 값을 그대로 보낸다 — 생략하면 `NARROW` 라 화면과 다른 순위가 저장된다. */
  trgtTypeSe?: ZoneRcmdtnTrgtTypeSe
  fileFmt: ZoneRcmdtnFileFmt
  /** 요금제 탭 계수 일시 조정 — 🔴 결과를 만든 실행의 값을 보낸다. */
  vrblVls?: VrblVls
}

/**
 * POST /api/analysis/zone-recommendation/share/reports 요청 (공유주차제 적용).
 * 🔴 읍면동 필터(`emdCd`)는 받지 않는다 — 보고서는 시군구 필터까지다(화면도 그 칸을 걷었다).
 */
export interface ZoneRcmdtnShareReportCreateRequest {
  /** 민원 창 시작일 (YYYYMMDD, 필수). */
  bgngYmd: string
  /** 민원 창 종료일 (YYYYMMDD, 필수 · 당일 포함). */
  endYmd: string
  /** 시군구명 필터(40자 이내). 생략하면 전체 지역. 없는 이름이면 400. */
  sggNm?: string
  fileFmt: ZoneRcmdtnFileFmt
  /** 공유주차제 탭 계수 일시 조정 — 🔴 결과를 만든 실행의 값을 보낸다. */
  vrblVls?: VrblVls
}

/** 보고서 머리 9칸 — 두 보기가 같다(백엔드 `ZoneRcmdtnReportVO`). */
interface ZoneRcmdtnReportHead {
  rptpId: number
  /** 🔴 실행 ID 는 여기 한 곳이다 — 본문(`fee.excnId`·`share.excnId`)은 항상 `null` 로 온다. */
  excnId: number
  /** 보고서명 `구역추천 요금제 적용 보고서 제주시 이도이동`. 보고서 목록의 행 이름이다. */
  rptpNm: string
  /**
   * 지역 표시명 — `{시군구} {읍면동}` / `{시군구} 전체` / `전체 지역`.
   * 요금제 적용은 읍면동 모드면 첫째 꼴, 시군구 모드면 둘째 꼴이다.
   */
  regionNm: string | null
  /** 분석기간설명 `2025.01.01 ~ 2025.08.31`(민원 창). */
  anlsPrdExpln: string | null
  /** 파일형식 — 요청값이 그대로 저장돼 온다. */
  fileFmt: string | null
  /** 생성일시 (ISO-8601). */
  crtYmd: string
  /** 실제 적용한 분석 시작일 (YYYYMMDD). */
  bgngYmd: string
  /** 실제 적용한 분석 종료일 (YYYYMMDD, 포함). */
  endYmd: string
  /**
   * 생성 당시 계수 적용 내역(보기의 허용 목록 전체). 🔴 본문 `fee`·`share` 의 같은 칸은 `null` 이다.
   * 이 칸 저장 전 보고서는 `null`. 문서에는 그리지 않는다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
}

/**
 * 저장된 구역추천 보고서 한 건. 저장(201)과 스냅샷 조회(200)가 **같은 모양**이다.
 *
 * 🔴 판별 유니온 — `rcmdtnTypeCd` 가 본문을 정한다. 모르는 값은 서버가 404 로 끊는다.
 * 본문은 조회 응답과 같은 타입이다(분석 화면과 보고서가 같은 값을 다른 모양으로 받지 않는다).
 *
 * ⚠️ `share.smry` 는 **도 전체 값**이고 `share.zones` 는 시군구 필터 범위다 — `smry` 의 건수를
 * "이 보고서의 건수" 로 쓰지 않는다.
 */
export type ZoneRcmdtnReport =
  | (ZoneRcmdtnReportHead & { rcmdtnTypeCd: 'FEE'; fee: ZoneRcmdtnResult; share: null })
  | (ZoneRcmdtnReportHead & { rcmdtnTypeCd: 'SHARE'; fee: null; share: ZoneRcmdtnShareResult })

/**
 * 구역추천 **요금제 적용** 보고서 저장 — 서버가 다시 계산해 저장하고 그 스냅샷을 201 로 돌려준다.
 *
 * 🔴 **공용 `api` 인스턴스**로 보낸다 — `X-XSRF-TOKEN` 이 자동으로 실린다(새 인스턴스면 403).
 * 🔴 빈 값은 바디에서 뺀다 — 빈 문자열을 그대로 보내면 형식 검증(8자리 숫자)에 걸린다.
 * ⚠️ 타임아웃은 공용 `api` 의 값 그대로다(`createInvestPrrtReport` 와 같다 — 개별 오버라이드 없음).
 * 🔴 선택 동의 판정이 `SHARE`·판정 불가여도 후보 주차장이 있으면 만들어진다(문서가 참고용임을 밝힌다).
 *
 * 🔴 범위는 `emdCd` 가 있으면 `emdCd` 만, 없을 때만 `sggNm` 을 싣는다(배타 —
 * `fetchZoneRcmdtn` 과 같은 규칙. 시군구는 읍면동 모드에서도 늘 선택돼 있어 둘 다 실으면 400 이다).
 *
 * 오류: 400(`fileFmt` 오값 · 기간 형식·순서 오류 · 한 창이 36개월 초과 · `trgtTypeSe` 오값 ·
 * **후보 주차장 0개소** · `emdCd`·`sggNm` 둘 다 / 둘 다 없음 · 없거나 40자를 넘는 `sggNm`) / 401(미인증 · 사용자 없음) / 403(CSRF) / 404(없는 읍면동).
 * 어느 경우든 아무것도 저장되지 않는다.
 */
export async function createZoneRcmdtnFeeReport(
  req: ZoneRcmdtnFeeReportCreateRequest,
): Promise<ZoneRcmdtnReport> {
  const body: Record<string, string | VrblVls> = {
    bgngYmd: req.bgngYmd,
    endYmd: req.endYmd,
    fileFmt: req.fileFmt,
  }
  if (req.emdCd) body.emdCd = req.emdCd
  else if (req.sggNm) body.sggNm = req.sggNm
  if (req.crdnBgngYmd) body.crdnBgngYmd = req.crdnBgngYmd
  if (req.crdnEndYmd) body.crdnEndYmd = req.crdnEndYmd
  if (req.trgtTypeSe) body.trgtTypeSe = req.trgtTypeSe
  // POST 는 바디 객체. 비면 키를 뺀다.
  const vrblVls = vrblVlsBody(req.vrblVls)
  if (vrblVls) body.vrblVls = vrblVls

  const { data } = await api.post<ZoneRcmdtnReport>('/analysis/zone-recommendation/reports', body)
  return data
}

/**
 * 구역추천 **공유주차제 적용** 보고서 저장 — 위와 같은 원칙이다(서버 재계산 · 공용 `api` · 201).
 *
 * 오류: 400(`fileFmt` 오값 · 기간 형식·순서 오류 · 36개월 초과 · 없거나 40자를 넘는 `sggNm` ·
 * **검토 후보(`rvwTrgtYn = 'Y'`) 0곳**) / 401 / 403(CSRF). 어느 경우든 아무것도 저장되지 않는다.
 */
export async function createZoneRcmdtnShareReport(
  req: ZoneRcmdtnShareReportCreateRequest,
): Promise<ZoneRcmdtnReport> {
  const body: Record<string, string | VrblVls> = {
    bgngYmd: req.bgngYmd,
    endYmd: req.endYmd,
    fileFmt: req.fileFmt,
  }
  if (req.sggNm) body.sggNm = req.sggNm
  const vrblVls = vrblVlsBody(req.vrblVls)
  if (vrblVls) body.vrblVls = vrblVls

  const { data } = await api.post<ZoneRcmdtnReport>(
    '/analysis/zone-recommendation/share/reports',
    body,
  )
  return data
}

/**
 * 구역추천 보고서 **스냅샷** 조회 — 재계산 없이 저장된 결과를 그대로 읽는다(그 뒤 분석 변수 설정이나
 * 데이터가 바뀌어도 저장 시점 값이다). 이 호출로 보고서 행이 늘지 않는다.
 * 🔴 두 보기가 이 엔드포인트 하나를 쓴다 — 호출부는 응답의 `rcmdtnTypeCd` 로 가른다.
 *
 * 404: 보고서 없음 · 구역추천 보고서가 아님 · 보기를 알 수 없음 · 결과 행 없음. 호출부는 넷을 구분하지 않는다.
 */
export async function fetchZoneRcmdtnReportSnapshot(rptpId: number): Promise<ZoneRcmdtnReport> {
  const { data } = await api.get<ZoneRcmdtnReport>(`/reports/${rptpId}/zone-recommendation`)
  return data
}
