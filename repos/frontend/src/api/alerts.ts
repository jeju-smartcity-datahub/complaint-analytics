import { api } from './client'
import type { PageResponse } from './reports'

/*
 * 위험단계 알림 실 API 클라이언트 (TSK-4028 Step 8).
 * 백엔드 계약(`jeju.datahub.risknoti.web.*Controller` 실측):
 *   GET    /api/risk-alerts                        → RiskAlertPage             (목록 + 요약 한 응답, D8)
 *   GET    /api/risk-alerts/daily                  → RiskAlertDaily            (팝오버 전용, 파라미터 없음)
 *   PATCH  /api/risk-alerts/{notiId}/read          → 204 (읽음 처리, 멱등)
 *   GET    /api/noti-receivers?page&size           → PageResponse<NotiReceiverItem>
 *   GET    /api/noti-receivers/candidates          → NotiReceiverCandidate[]   (M0404 — TSK-4250 R12·Q1)
 *   POST   /api/noti-receivers                     → NotiReceiverItem          (201 · 중복 409 RCVR_DUPLICATED)
 *   PUT    /api/noti-receivers/{rcvrId}            → NotiReceiverItem          (지역·단계 통째 교체 — R10)
 *   PATCH  /api/noti-receivers/{rcvrId}/activation → NotiReceiverItem          (없거나 삭제됨 → 404)
 *   DELETE /api/noti-receivers/{rcvrId}            → 204 (소프트 삭제, 응답 바디 없음)
 *   GET    /api/risk-criteria                      → RiskCriteriaItem[]        (항상 3건)
 *   PUT    /api/risk-criteria                      → RiskCriteriaItem[]        (3건 일괄 UPSERT)
 *
 * 목록은 **서버가 정렬해서 내려준다** — 알림 이력은 최신순(ocrn_dt DESC, noti_id DESC),
 * 수신자는 rcvr_id 순, 기준은 TB_COM_CD.sort_seq 순(CRIT → WARN → CAUT).
 * 프론트에서 다시 정렬하지 말고 받은 순서를 그대로 렌더링한다.
 *
 * 🔴 지역 조회 함수는 여기 두지 않는다 — 이미 있다. 지역 select → `api/regions.ts` 의 `fetchRegions()`.
 *    수신자 모달의 소속 select 는 후보 API(`fetchReceiverCandidates`)의 `deptCd`·`deptNm` 에서 만든다(TSK-4250).
 *
 * 🔴 페이징 형식(`PageResponse<T>`)도 여기서 재선언하지 않고 `api/reports.ts` 것을 import 한다
 *    (백엔드 `jeju.datahub.common.PageResponse` 와 1:1 — 형식을 두 벌 만들지 않는다).
 *
 * ⚠️ 단계 라벨(심각/경고/주의)은 어떤 응답에도 없다 — `components/common/RiskBadge` 가 코드값에서 만든다.
 */

/* ── 1. 알림 이력 조회 ─────────────────────────────────── */

/**
 * GET /api/risk-alerts 조회 파라미터.
 *
 * 🔴 **'전체' 를 고른 필터는 키를 아예 넣지 않는다**(`undefined` 로 두면 axios 가 뺀다).
 * 서버는 `all` 과 빈 문자열을 **모두 null(전체)로 정규화**해 받아 준다(Step 5 `normalizeFilter` — 2026-08-21 E2E 실측:
 * `riskLvlCd=all` → 200 전량, `riskLvlCd=` → 200 전량, `riskLvlCd=FOO` → 400).
 * 그래도 프론트가 먼저 끊는다 — 서버 정규화는 방어선이지 계약이 아니고, 의미 없는 파라미터를 실어 보내지 않는다.
 */
export interface RiskAlertSearchParams {
  /** 위험단계 코드. CRIT | WARN | CAUT. 전체면 넣지 않는다. */
  riskLvlCd?: string
  /** 시군구명. 예) '제주시'. 전체면 넣지 않는다. */
  sggNm?: string
  /** 읍면동코드. 예) '50110104'. 전체면 넣지 않는다. */
  emdCd?: string
  /** 조회 시작일 (yyyy-MM-dd). 서버가 00:00:00 으로 펼친다. */
  from?: string
  /** 조회 종료일 (yyyy-MM-dd). 서버가 **23:59:59 까지 포함**한다. */
  to?: string
  /** 페이지 번호 — **0-base**. 기본 0. */
  page?: number
  /** 페이지 크기. 기본 10, 상한 100(초과 시 400). */
  size?: number
}

/** GET /api/risk-alerts 목록 행. **1행 = 판정 사건 1건**이다 (D13 — 수신자 필드가 없다). */
export interface RiskAlertItem {
  /** 알림아이디 (BIGINT). */
  notiId: number
  /** 위험단계 코드. CRIT | WARN | CAUT. 라벨은 `RiskBadge` 가 만든다. */
  riskLvlCd: string
  /** 시군구명. 예) '제주시'. */
  sggNm: string | null
  /** 읍면동코드. 판정은 언제나 읍면동 단위라 항상 값이 있다 (D12). */
  emdCd: string
  /** 읍면동명. 예) '이도이동'. */
  emdNm: string | null
  /** 지역 표시명 — 이력은 **항상 읍면동명**이다 (D12. 수신자의 3분기 규칙과 다르다). */
  regionNm: string
  /** 알림 내용 — 판정 시점에 저장된 문구 그대로 (D6. 프론트가 재조립하지 않는다). */
  notiCtt: string
  /** 전일 대비 민원 증가율 (%). */
  incrsRt: number | null
  /** 판정 당시 기준 증가율 (%) — D7 스냅샷. 기준을 바꿔도 소급 변경되지 않는다. */
  incrsRtThrsh: number | null
  /** 판정 대상 읍면동의 당일 민원 건수. */
  cvlcptCnt: number | null
  /** 발생일시 (ISO-8601 LocalDateTime 문자열. 예) '2026-05-28T11:47:00'). */
  ocrnDt: string
  /** 처리상태 코드. 이번 범위는 'OPEN' 고정이다 (실질 상태는 prslYn·idntyYn — D17). */
  prcsSttsCd: string
  /** 읽음여부 (Y | N). */
  prslYn: string | null
  /** 읽은일시. 미읽음이면 null. */
  prslDt: string | null
  /** 확인여부 (Y | N). 확인은 읽음을 전제한다. */
  idntyYn: string | null
  /** 확인일시. 미확인이면 null. */
  idntyDt: string | null
}

/**
 * 알림 조회 화면 상단 요약 카드.
 *
 * ⚠️ **페이지가 아니라 필터 전체 기준**이다 — 페이지를 넘겨도 값이 변하지 않는다.
 * 페이징된 `content` 로 프론트가 다시 집계하면 총계가 틀린다.
 */
export interface RiskAlertSummary {
  /** 전체 알림 건수 (필터 적용 후). */
  totalCnt: number
  /** 심각(CRIT) 건수. */
  critCnt: number
  /** 경고(WARN) 건수. */
  warnCnt: number
  /** 주의(CAUT) 건수. */
  cautCnt: number
  /** 민원 건수 합계. 대상이 0건이면 0. */
  cvlcptSumCnt: number
}

/**
 * GET /api/risk-alerts 응답 — 목록 + 페이징 메타 + 요약을 **한 응답**으로 받는다 (D8).
 * 백엔드 `RiskAlertPageVO extends PageResponse<RiskAlertVO>` 와 1:1 이다.
 */
export type RiskAlertPage = PageResponse<RiskAlertItem> & {
  /** 요약 카드 3종 (필터 전체 기준). */
  summary: RiskAlertSummary
}

/**
 * 알림 이력 목록 + 요약 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지).
 * 오류: riskLvlCd 가 화이트리스트 밖 · from > to · page/size 범위 밖 → 400.
 */
export async function fetchRiskAlerts(params: RiskAlertSearchParams = {}): Promise<RiskAlertPage> {
  const { data } = await api.get<RiskAlertPage>('/risk-alerts', { params })
  return data
}

/* ── 2. 알림 수신자 ────────────────────────────────────── */

/**
 * 수신 지역 원소 (TSK-4250 R3·R6) — 응답 `regions[]` 의 한 칸.
 * 3형태: 읍면동(`sggNm`·`emdCd` 있음) · 시군구 전체(`emdCd` null) · 전체 지역(둘 다 null).
 */
export interface NotiReceiverRegion {
  /** 시군구명. null 이면 전체 지역. */
  sggNm: string | null
  /** 읍면동코드. null 이면 시군구 전체(또는 전체 지역). */
  emdCd: string | null
  /** 읍면동명. */
  emdNm: string | null
  /** 원소 표시명 — '제주시 연동' | '제주시 전체' | '전체 지역'. 서버가 조립한다. */
  regionNm: string
}

/**
 * GET /api/noti-receivers 목록 행. **1행 = 사람 1명**이다 (TSK-4250 R9 — 한 사용자 = 수신자 1행).
 * 등록(POST) · 수정(PUT) · 활성화 토글(PATCH) 응답도 **같은 형태**라 같은 렌더러로 처리한다.
 *
 * 🔴 최상위 `sggNm`·`emdCd`·`emdNm` 은 없다(R6) — 지역은 `regions` 배열이다.
 */
export interface NotiReceiverItem {
  /** 수신자ID (BIGINT). */
  rcvrId: number
  /** 소속부서 코드 (`TB_DEPT.dept_cd`). 무소속이면 null. */
  deptCd: string | null
  /** 소속부서명. 예) '주차관리'. */
  deptNm: string | null
  /** 수신자명 (TB_USER 에서 복사 — R8). */
  rcvrNm: string
  /** 이메일 (TB_USER 에서 복사 — R8). */
  eml: string
  /** 수신 지역 목록. 행 0개면 [] (= 수신 지역 없음 — R3). */
  regions: NotiReceiverRegion[]
  /**
   * 지역 표시명 — 원소 `regionNm` 을 서버가 `", "` 로 이어 내린다
   * ('제주시 연동, 제주시 노형동' | '전체 지역' | '지역 없음'). 프론트에서 다시 조합하지 않는다.
   */
  regionNm: string
  /** 수신 대상 위험단계 코드 목록. 매핑이 0건이면 []. */
  riskLvlCds: string[]
  /** 사용여부 (Y | N) — 화면의 토글 스위치. 삭제(del_yn)와는 뜻이 다르다. */
  useYn: string
}

/**
 * 요청 지역 원소. **전체 지역은 원소 `{}`**(둘 다 생략)다 — 빈 배열은 400 이다(R3).
 * `emdCd` 를 보내면 서버가 `sggNm` 을 무시하고 다시 채운다.
 */
export interface NotiReceiverRegionRequest {
  /** 시군구명 (시군구 전체일 때만). */
  sggNm?: string
  /** 읍면동코드 (읍면동 지정일 때만). */
  emdCd?: string
}

/**
 * POST /api/noti-receivers 요청 바디 (TSK-4250 R6·R8).
 *
 * 이름·이메일·부서는 보내지 않는다 — 서버가 `userId` 로 `TB_USER` 에서 복사한다.
 * `useYn`·`delYn`·`ntfctnChCd`·`rcvrId` 도 서버가 채운다.
 */
export interface NotiReceiverCreateRequest {
  /** 사용자아이디 (`GET /api/noti-receivers/candidates` 의 `userId`). 없거나 사용 중지면 400. */
  userId: string
  /** 수신 지역 (최소 1개, 겹침 400 — R11). */
  regions: NotiReceiverRegionRequest[]
  /** 수신 대상 위험단계 코드 목록 (최소 1개). 중복은 서버가 제거한다. */
  riskLvlCds: string[]
}

/** PUT /api/noti-receivers/{rcvrId} 요청 바디 (R10) — 사람 필드가 없다(TB_USER 가 정본). */
export interface NotiReceiverUpdateRequest {
  /** 수신 지역 (최소 1개 — 통째 교체). */
  regions: NotiReceiverRegionRequest[]
  /** 수신 대상 위험단계 코드 목록 (최소 1개 — 통째 교체). */
  riskLvlCds: string[]
}

/**
 * GET /api/noti-receivers/candidates 원소 — 활성 사용자 1명 (R12).
 * 정렬은 서버(부서 정렬순서 → 부서명 → 사용자명, 무소속 맨 뒤) 그대로 쓴다.
 */
export interface NotiReceiverCandidate {
  userId: string
  userNm: string
  /** 무소속이면 null (Q2). */
  deptCd: string | null
  /** 무소속이면 null (Q2). */
  deptNm: string | null
  eml: string
  /** 이미 활성 수신자면 true — 선택 불가(R9). */
  registered: boolean
}

/** PATCH /api/noti-receivers/{rcvrId}/activation 요청 바디. */
export interface NotiReceiverActivationRequest {
  /** 사용여부. Y | N 외 값은 400. */
  useYn: 'Y' | 'N'
}

/**
 * 수신자 목록 조회 (rcvr_id 순, 소프트 삭제분 제외).
 * @param page 0-base 페이지 번호. @param size 페이지 크기(상한 100).
 */
export async function fetchNotiReceivers(
  page = 0,
  size = 10,
): Promise<PageResponse<NotiReceiverItem>> {
  const { data } = await api.get<PageResponse<NotiReceiverItem>>('/noti-receivers', {
    params: { page, size },
  })
  return data
}

/**
 * 수신자 후보(활성 사용자 전원) 조회 — M0404 권한이 없으면 403 (Q1).
 */
export async function fetchReceiverCandidates(): Promise<NotiReceiverCandidate[]> {
  const { data } = await api.get<NotiReceiverCandidate[]>('/noti-receivers/candidates')
  return data
}

/**
 * 수신자 1건 등록 → 생성된 항목(201).
 * 오류: 없는 userId·코드값·지역 · 지역 겹침 · 빈 regions/riskLvlCds → 400 /
 * 같은 사용자의 활성 수신자가 이미 있으면 409 `{"code":"RCVR_DUPLICATED"}`(R9).
 */
export async function createNotiReceiver(
  body: NotiReceiverCreateRequest,
): Promise<NotiReceiverItem> {
  const { data } = await api.post<NotiReceiverItem>('/noti-receivers', body)
  return data
}

/**
 * 수신자 수정 — 지역·단계 통째 교체 → 갱신된 항목 (R10).
 * 오류: 없는 코드값·지역 · 지역 겹침 · 빈 배열 → 400 / 없거나 삭제된 rcvrId → 404.
 */
export async function updateReceiver(
  rcvrId: number,
  body: NotiReceiverUpdateRequest,
): Promise<NotiReceiverItem> {
  const { data } = await api.put<NotiReceiverItem>(`/noti-receivers/${rcvrId}`, body)
  return data
}

/**
 * 수신자 활성화 토글(`use_yn`) → 갱신된 항목.
 * 오류: Y/N 외 값 → 400 / 없거나 이미 삭제된 rcvrId → 404.
 */
export async function updateReceiverActivation(
  rcvrId: number,
  useYn: 'Y' | 'N',
): Promise<NotiReceiverItem> {
  const body: NotiReceiverActivationRequest = { useYn }
  const { data } = await api.patch<NotiReceiverItem>(`/noti-receivers/${rcvrId}/activation`, body)
  return data
}

/**
 * 수신자 삭제 — 204, 응답 바디 없음.
 * ⚠️ **소프트 삭제**(`del_yn='Y'`)다 (D5) — 목록에서만 사라지고 단계 매핑·과거 발송기록은 남는다.
 * 없거나 이미 삭제된 rcvrId 는 404.
 */
export async function deleteNotiReceiver(rcvrId: number): Promise<void> {
  await api.delete(`/noti-receivers/${rcvrId}`)
}

/* ── 3. 위험단계 알림 기준 ─────────────────────────────── */

/** GET·PUT /api/risk-criteria 응답 행 (단계당 1건 — 항상 3건). */
export interface RiskCriteriaItem {
  /** 기준아이디. 'THRSH_' + riskLvlCd 고정 (D7 — 서버가 만든다). */
  thrshId: string
  /** 위험단계 코드. CRIT | WARN | CAUT. */
  riskLvlCd: string
  /** 증가율 기준 (%). 전일 대비 민원 증가율이 이 값 이상이면 해당 단계 알림. */
  incrsRt: number
  /**
   * 최소 민원수 하한 (TSK-4092). **판정일 당일** 민원이 이 값 미만이면 그 단계 알림에서 제외된다.
   * `0` 이면 하한 없음. 증가율의 대안이 아니라 **전제 조건**이다 — 둘 다 넘겨야 알림이 난다.
   */
  minCvlcptCnt: number
  /**
   * 기본 최소 민원수 — **최초 등재값, 불변**(TSK-4092 D11). `최소 건수 기본값으로` 버튼의 기준값이다.
   * 🔴 **조회 전용이다** — `PUT` 바디에 넣어도 서버가 무시한다(`RiskCriteriaSaveItem` 에 없다).
   */
  bscMinCvlcptCnt: number
}

/**
 * PUT /api/risk-criteria 요청 원소. `thrshId` 는 보내지 않는다(서버가 만든다).
 *
 * 🔴 **`RiskCriteriaItem` 을 그대로 보내지 않는다** — `bscMinCvlcptCnt` 가 섞여 들어간다.
 * 서버는 무시하지만(Jackson 이 버린다) "보내면 기본값이 바뀐다"는 오해를 코드에 남기지 않는다.
 * `handleSave` 는 반드시 필드를 **골라서** 조립한다.
 */
export interface RiskCriteriaSaveItem {
  /** 위험단계 코드. CRIT | WARN | CAUT. */
  riskLvlCd: string
  /** 증가율 기준 (%) — 0 초과 100 이하, 소수 2자리까지. 벗어나면 400. */
  incrsRt: number
  /** 최소 민원수 하한 — 0 이상 1000 이하 정수. **생략하면 400** — 서버가 필수로 본다. */
  minCvlcptCnt: number
}

/** PUT /api/risk-criteria 요청 바디 — 배열을 `criteria` 로 **감싼다**(응답은 감싸지 않는다). */
export interface RiskCriteriaSaveRequest {
  /** 3단계 전부. 하나라도 빠지거나 중복이면 400 (부분 저장 불가 — D7). */
  criteria: RiskCriteriaSaveItem[]
}

/** 단계별 기준 3건 조회 (sort_seq 순 = 심각 → 경고 → 주의. 받은 순서 그대로 렌더링). */
export async function fetchRiskCriteria(): Promise<RiskCriteriaItem[]> {
  const { data } = await api.get<RiskCriteriaItem[]>('/risk-criteria')
  return data
}

/**
 * 단계별 기준 3건 일괄 저장(UPSERT) → 저장 후 재조회한 3건 (GET 과 순서·형태가 같다).
 *
 * 🔴 요청 바디는 `{ criteria }` 로 감싼다. 배열을 그대로 보내면 400 이다.
 * ⚠️ 기준을 바꿔도 과거 알림 이력의 `incrsRtThrsh`(판정 당시 스냅샷)는 변하지 않는다 (D7).
 */
export async function saveRiskCriteria(
  criteria: RiskCriteriaSaveItem[],
): Promise<RiskCriteriaItem[]> {
  const body: RiskCriteriaSaveRequest = { criteria }
  const { data } = await api.put<RiskCriteriaItem[]>('/risk-criteria', body)
  return data
}

/* ── 4. 상단 알림 벨 (TSK-4086) ────────────────────────── */

/**
 * GET /api/risk-alerts/daily 응답 — 벨 팝오버 전용.
 *
 * ⚠️ `totalCnt` 와 `unreadCnt` 는 **뜻이 다른 숫자**다 (TSK-4086 D2·D4).
 *    - `totalCnt`  → 헤더 문구 `전일 기준 N건` (그날 생성된 전체)
 *    - `unreadCnt` → 벨 배지 (아직 안 읽은 것만)
 *    둘을 바꿔 쓰면 읽어도 배지가 안 줄거나, 헤더 숫자가 목록 길이와 어긋난다.
 */
export interface RiskAlertDaily {
  /** 기준일(판정일) = 전일. yyyy-MM-dd. **서버가 정한다** — 프론트가 계산하지 않는다(D8). */
  baseDe: string
  /** 기준일 생성 알림 전체 건수. `items.length` 와 항상 같다(D3 — 개수 제한 없음). */
  totalCnt: number
  /** 그중 미읽음 건수. 배지 숫자. */
  unreadCnt: number
  /** 목록 전부. 서버 정렬(ocrn_dt DESC, noti_id DESC) 그대로 — 프론트에서 재정렬하지 않는다. 하루치라 ocrn_dt 는 동률이 많아 실질 정렬은 noti_id DESC 다. */
  items: RiskAlertItem[]
}

/** 벨 팝오버용 — 판정일 하루치 알림. 0건이어도 200 이며 `items` 는 빈 배열이다. */
export async function fetchDailyAlerts(): Promise<RiskAlertDaily> {
  const { data } = await api.get<RiskAlertDaily>('/risk-alerts/daily')
  return data
}

/**
 * 알림 1건 읽음 처리 — 204, 응답 바디 없음.
 * 멱등이라 이미 읽은 건을 다시 불러도 200 계열이고 읽은일시는 유지된다.
 * 없는 notiId 는 404.
 */
export async function markAlertRead(notiId: number): Promise<void> {
  await api.patch(`/risk-alerts/${notiId}/read`)
}
