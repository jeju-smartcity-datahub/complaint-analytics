import { api } from './client'
import { fetchCommonCodes, type CommonCodeItem } from './commonCodes'
import type { HotspotReportDoc } from '../lib/analysis/hotspotReportTemplate'
import type { LifezoneCngstReportDoc } from '../lib/analysis/lifezoneCngstReportTemplate'
import type { LifezoneReportDoc } from '../lib/analysis/lifezoneReportTemplate'
import type { LifezoneSupplyReportDoc } from '../lib/analysis/lifezoneSupplyReportTemplate'
import type { ComplaintReportDoc } from './complaints'

/*
 * 보고서 실 API 클라이언트 (TSK-4026 Step 8).
 * 백엔드 계약(`jeju.datahub.report.web.ReportController` 기준):
 *   GET    /api/reports?crtType&page&size[&keyword&anlsTypeCd&fromYmd&toYmd]
 *                                           → PageResponse<ReportListItem> (필터 4종은 선택 · TSK-4266)
 *   GET    /api/reports/{rptpId}            → ReportListItem            (없으면 404)
 *   GET    /api/reports/{rptpId}/hotspot    → HotspotReportDoc          (TSK-4078 Step 6 · 404 3종)
 *   GET    /api/reports/{rptpId}/lifezone   → LifezoneReportDoc         (TSK-4088 Step 11 · 404 3종)
 *   GET    /api/reports/{rptpId}/complaint  → ComplaintReportDoc        (TSK-4164 Step 6 · 404 3종)
 *   GET    /api/report-settings             → ReportSettingItem[]       (페이징 없음, 전체)
 *   POST   /api/report-settings             → ReportSettingItem         (201)
 *   PUT    /api/report-settings/{stngId}    → ReportSettingItem         (없거나 삭제됨 → 404)
 *   DELETE /api/report-settings/{stngId}    → 204 (소프트 삭제, 응답 바디 없음)
 *
 * 목록은 **서버가 정렬해서 내려준다**(보고서=최신순, 설정=최신 등록순 crt_dt DESC).
 * 프론트에서 다시 정렬하지 말고 받은 순서를 그대로 렌더링한다.
 */

/**
 * 페이징 응답 공통 형식. 백엔드 `jeju.datahub.common.PageResponse` 와 1:1 이다 (TSK-4026 D8).
 * 알림 클라이언트(TSK-4028)도 이 타입을 import 해서 쓴다 — 형식을 두 벌 만들지 않는다.
 */
export interface PageResponse<T> {
  /** 현재 페이지의 행 목록. */
  content: T[]
  /** 페이지 번호 — **0-base**. 화면 표시(1-base) 변환은 `components/common/Pager` 가 전담한다. */
  page: number
  /** 페이지 크기. 기본 10, 상한 100. */
  size: number
  /** 필터 전체 건수. */
  totalElements: number
  /** 전체 페이지 수 (`totalElements / size` 올림). */
  totalPages: number
}

/**
 * GET /api/reports 목록 행 (자동 생성 / 직접 생성 공용).
 *
 * **생성 구분에 따라 채워지는 칸이 다르다.**
 * - `AUTO` — `cyclCd`/`cyclNm`/`deptCd`/`deptNm` 이 차고, `excnId`/`srcNm`/`anlsPrdExpln` 은 null.
 * - `MANUAL` — `excnId`/`srcNm`/`anlsPrdExpln` 이 차고, `cyclCd`/`cyclNm`/`deptCd`/`deptNm` 은 null.
 */
export interface ReportListItem {
  /** 보고서ID (BIGINT). */
  rptpId: number
  /** 분석실행ID (BIGINT). 직접 생성(MANUAL)만 값을 갖는다. */
  excnId: number | null
  /** 생성구분 코드. AUTO | MANUAL. */
  crtType: string
  /** 생성구분명. 예) '자동'. */
  crtTypeNm: string
  /** 보고서명. */
  rptpNm: string
  /** 배포주기 코드. 직접 생성이면 null. */
  cyclCd: string | null
  /** 배포주기명. 직접 생성이면 null. */
  cyclNm: string | null
  /** 출처명(분석 실행 유형 코드명). 자동 생성이면 null. */
  srcNm: string | null
  /**
   * 분석유형 코드 — `HOTSPOT` | `LIFEZONE` | `LIFEZONE_SPLY` | `LIFEZONE_CNGST` | `CVLCPT` | `INVEST` | `RECOMMEND` | `POLICY_SIM` | `DASHBOARD`
   * (TSK-4078 D10 · TSK-4088 Step 11 · TSK-4036 Step 10 · TSK-4240 Step 6).
   * ⚠️ `RECOMMEND` 는 구역추천의 두 보기(요금제 적용 · 공유주차제 적용)가 함께 쓴다 — 어느 보기인지는
   * 이 칸이 아니라 스냅샷 응답의 `rcmdtnTypeCd` 가 말한다.
   *
   * **스냅샷 조회 가능 여부를 화면이 코드로 판별하는 칸**이다 — 표시명(`srcNm`)으로 분기하지 않는다.
   * 🔴 **`crtType` 은 보지 않는다** (TSK-4027 Step 11-1 — 예외: 이 값이 null 인 AI 어시스턴트 보고서는 `crtType === 'AI'` 로 가른다, TSK-4293). 종전에는 시드 AUTO 목데이터가 스냅샷 없이
   * `anlsTypeCd` 만 갖고 있어 `crtType === 'MANUAL'` 을 함께 봤지만, 그 목데이터가 걷히고 배치가
   * **실제 스냅샷이 있는 AUTO 보고서**를 만들면서 그 가드가 오히려 자동 생성분을 샘플로 떨어뜨렸다.
   * 지금은 `anlsTypeCd` 로 스냅샷을 조회하고 **404 면 안내**한다(`lib/report/reportDownload.ts` 의
   * `buildBody` — TSK-4121 Step 8-B 에서 `ReportsPage` 밖 공용으로 옮겼다).
   *
   * `tb_anls_excn` 을 **LEFT JOIN** 해 채우므로 실행이 연결되지 않은 보고서는 `null` 이다.
   */
  anlsTypeCd: string | null
  /** 분석기간 설명. 직접 생성만 값을 갖는다. */
  anlsPrdExpln: string | null
  /** 생성자명. */
  creatrNm: string
  /** 배포부서 코드. 직접 생성이면 null. */
  deptCd: string | null
  /** 배포부서명. 직접 생성이면 null. */
  deptNm: string | null
  /** 상태코드. 예) 'DONE'. */
  sttsCd: string
  /** 상태명. 예) '완료'. */
  sttsNm: string
  /** 파일형식. 예) 'PDF'. 자동 생성은 null (TSK-4027 D14 — 설정에 형식 칸이 없다). */
  fileFmt: string | null
  /** 생성일시 (ISO-8601 LocalDateTime 문자열. 예) '2026-07-01T09:00:00'). */
  crtYmd: string
}

/**
 * GET /api/report-settings 배열 항목 (보고서 자동 생성 설정).
 *
 * 지역 표시명(`regionNm`)은 **서버가 3단 규칙으로 조립해 내린다**(TSK-4026 D12) —
 * 프론트가 `sggNm`/`emdNm` 을 다시 조합하지 않는다.
 */
export interface ReportSettingItem {
  /** 설정아이디 (BIGINT). */
  stngId: number
  /** 보고서종류 코드. HOTSPOT | CVLCPT | DASHBOARD. */
  rptpTypeCd: string
  /** 보고서종류명. 예) '민원현황'. */
  rptpTypeNm: string
  /** 배포부서 코드. */
  crltnDeptCd: string
  /** 배포부서명. 예) '종합민원실'. */
  crltnDeptNm: string
  /** 배포주기 코드. MONTH | QUARTER | HALF | YEAR. */
  crltnCyclCd: string
  /** 배포주기명. 예) '월간'. */
  crltnCyclNm: string
  /** 배포기준월 (1~12). 주기가 월간이면 null. */
  crltnMm: number | null
  /** 배포기준일 (1~28). */
  crltnDd: number
  /** 다음 배포 예정일 (ISO 날짜 'YYYY-MM-DD'). 화면에 표시하지 않는다. */
  crltnYmd: string
  /** 활성여부. 삭제 여부와는 다른 뜻이다(D11). */
  actvtnYn: boolean
  /** 생성자ID. 인증 도입 전 상수 'system'. */
  creatrId: string
  /** 생성자명 (화면 표시용). */
  creatrNm: string
  /** 생성일시 (ISO-8601 LocalDateTime 문자열). */
  crtDt: string
  /** 최근생성일시. null 이면 화면에 `미생성` 으로 표시한다. */
  lastCrtDt: string | null
  /** 시군구명. null 이면 전체 지역. */
  sggNm: string | null
  /** 읍면동코드. null 이면 시군구 전체. */
  emdCd: string | null
  /** 읍면동명. */
  emdNm: string | null
  /** 지역 표시명 — 서버 조립 (`전체 지역` | `{시군구} 전체` | `{시군구} {읍면동}`). */
  regionNm: string
}

/**
 * POST /api/report-settings · PUT /api/report-settings/{stngId} 요청 바디.
 *
 * 다음 배포 예정일·활성여부·생성자·생성일시는 **서버가 채우므로 보내지 않는다**(D2·D5).
 */
export interface ReportSettingRequest {
  /** 보고서종류 코드 (필수). HOTSPOT | CVLCPT | DASHBOARD. */
  rptpTypeCd: string
  /** 배포부서 코드 (필수). */
  crltnDeptCd: string
  /** 배포주기 코드 (필수). MONTH | QUARTER | HALF | YEAR. */
  crltnCyclCd: string
  /** 배포기준월 (1~12). 월간이면 무시되고, 분기·반기·연간이면 **필수**(없으면 400). */
  crltnMm?: number | null
  /** 배포기준일 (1~28, 필수). 말일 편차를 피해 28 로 제한한다. */
  crltnDd: number
  /** 시군구명 (선택). 비우면 전체 지역. `emdCd` 를 함께 보내면 서버가 다시 채우므로 무시된다. */
  sggNm?: string | null
  /** 읍면동코드 (선택). 비우면 시군구 전체, `sggNm` 도 비우면 전체 지역. */
  emdCd?: string | null
}

/**
 * 보고서 목록 필터 (TSK-4266 D2) — **전부 선택**이다. 비우면 지금과 같은 결과다(하위 호환).
 *
 * 🔴 필터는 **서버가 건다**(D1) — 목록이 서버 페이징이라 프론트에서 거르면 현재 페이지 안에서만
 * 걸러지고 총건수·페이저가 틀린다. 서버는 목록·총건수에 **같은 조건**을 건다(D9).
 */
export interface ReportListFilter {
  /** 보고서명 부분 일치(대소문자 구분, D5). 비우면 미전송. 서버가 trim · 최대 100자(초과 400). */
  keyword?: string
  /** 출처 = 분석유형 코드(D3 — 이름 `srcNm` 이 아니라 코드로 건다). `'all'` 은 화면에서 undefined 로 바꿔 보낸다. 미존재 코드는 400. */
  anlsTypeCd?: string
  /** 생성일 시작 'YYYY-MM-DD' (input[type=date] 값 그대로). */
  fromYmd?: string
  /** 생성일 끝 'YYYY-MM-DD' — **당일 포함**(서버가 `< toYmd + 1일` 로 건다, D6). `fromYmd > toYmd` 면 400. */
  toYmd?: string
}

/**
 * 보고서 목록 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지).
 *
 * @param crtType 생성구분. AUTO | MANUAL | AI — **필수**다(빠지거나 그 밖의 값이면 400).
 *                `AI` 는 TSK-4121 Step 8-A 에서 백엔드 화이트리스트에 추가됐다(그전에는 400).
 * @param page    **0-base** 페이지 번호
 * @param size    페이지 크기 (1 ~ 100)
 * @param filter  목록 필터 (선택, TSK-4266). 빈 값(`''`·공백만·undefined)은 params 에서 **키째 뺀다** —
 *                `?keyword=` 가 가도 서버가 trim 후 미적용하지만, 로그·캐시 키를 흐리지 않게 한다.
 */
export async function fetchReports(
  crtType: string,
  page = 0,
  size = 10,
  filter?: ReportListFilter,
): Promise<PageResponse<ReportListItem>> {
  const params: Record<string, string | number> = { crtType, page, size }
  if (filter) {
    for (const [key, value] of Object.entries(filter) as [keyof ReportListFilter, string | undefined][]) {
      const trimmed = value?.trim()
      if (trimmed) params[key] = trimmed
    }
  }
  const { data } = await api.get<PageResponse<ReportListItem>>('/reports', { params })
  return data
}

/**
 * 보고서 출처 필터에서 **빼는** 분석유형 코드 (TSK-4266 D4).
 *
 * `DASHBOARD` 는 보고서를 만들지 않는 실행 유형이다(로컬 실측 — MANUAL 보고서의 유형은 7종, `DASHBOARD` 0건).
 * 고를 수 있게 두면 항상 0건인 선택지가 된다.
 * 🔴 `addlAtrb1`(RPTP/BOTH)로 거르지 않는다 — 그건 **자동 생성 설정용** 구분이라 `POLICY_SIM` 등 5종이 빠진다.
 */
export const REPORT_SRC_EXCLUDE: readonly string[] = ['DASHBOARD']

/**
 * 보고서 출처 필터 옵션 (TSK-4266 D4) — `ANLS_TYPE_CD` 에서 {@link REPORT_SRC_EXCLUDE} 를 뺀 목록.
 *
 * 새 API 를 만들지 않고 공통코드를 재사용한다(서버 페이징이라 현재 페이지에서 옵션을 뽑을 수 없다).
 * 순서는 **서버가 `sort_seq` 로 정렬해 내린 그대로**다(`CommonCodeMapper.xml` `ORDER BY c.sort_seq`) —
 * 프론트에서 다시 정렬하지 않는다.
 */
export async function fetchReportSrcOptions(): Promise<CommonCodeItem[]> {
  const codes = await fetchCommonCodes('ANLS_TYPE_CD')
  return codes.filter((c) => !REPORT_SRC_EXCLUDE.includes(c.cdVl))
}

/** 보고서 단건 조회(미리보기 헤더용). 없으면 404. */
export async function fetchReport(rptpId: number): Promise<ReportListItem> {
  const { data } = await api.get<ReportListItem>(`/reports/${rptpId}`)
  return data
}

/**
 * 직접 생성 보고서 삭제 — `DELETE /reports/{rptpId}` (204).
 * 생성자 본인만 지울 수 있고 남의 것·없는 것은 404, 자동 생성 보고서는 400 이다.
 * 공용 `api` 인스턴스라 `X-XSRF-TOKEN` 이 자동으로 실린다.
 */
export async function deleteReport(rptpId: number): Promise<void> {
  await api.delete(`/reports/${rptpId}`)
}

/**
 * 집중 구역 보고서 **스냅샷** 조회 (TSK-4078 Step 10). 저장된 순위 15행을 그대로 다시 읽는다.
 *
 * 🔴 반환 타입이 `HotspotReportResponse`(POST 응답)가 **아니다**.
 * GET 스냅샷의 원천 `TB_HTSPT_ANLS_RSLT` 에는 `emd_nm`·`zone_nm_src` 컬럼이 아예 없어 두 칸이
 * `null` 로 실려 온다(2026-08-27 실측). 그런데 `HotspotZone.zoneNmSrc` 는 **non-nullable** 이라
 * POST 응답 타입을 재사용하면 계약이 거짓이 된다 — 문서 템플릿이 실제로 쓰는 7칸만 선언한
 * `HotspotReportDoc`(Step 8)을 그대로 쓴다.
 *
 * 타입은 `lib/analysis/hotspotReportTemplate` 에서 가져온다 — **`api` → `lib` 한 방향**이며,
 * 문서 템플릿이 요구하는 칸이 곧 이 API 의 계약이다.
 *
 * 404 3종: 보고서 없음 · HOTSPOT 실행이 아님 · 스냅샷 0행. 호출부는 셋을 구분하지 않는다
 * (셋 다 "이 보고서에는 스냅샷이 없다" 라 화면 처리가 같다).
 */
export async function fetchHotspotReport(rptpId: number): Promise<HotspotReportDoc> {
  const { data } = await api.get<HotspotReportDoc>(`/reports/${rptpId}/hotspot`)
  return data
}

/**
 * 생활권 시뮬레이션 보고서 **스냅샷** 조회 (TSK-4088 Step 12). 저장된 요약·순위 15행을 그대로 다시 읽는다.
 *
 * 🔴 **보고서를 새로 만들지 않는다** — 재분석 없이 저장된 `rslt_json`·`anls_param_json` 만 읽으므로
 * 이 호출로 `tb_rptp` 행이 늘지 않는다. 원천 데이터가 그 사이 바뀌어도 보고서는 만들어진 날의 기록이다.
 *
 * 🔴 반환 타입이 `LifezoneReportResponse`(POST 응답)가 **아니다**. 스냅샷에는
 * `dataBgngYmd`·`dataEndYmd` 가 `null` 로 오고(원천 보유 구간은 "지금" 의 값이라 스냅샷이 아니다)
 * `cells` 는 문서가 쓰지 않는다 — 문서 템플릿이 실제로 쓰는 칸만 선언한
 * `LifezoneReportDoc`(Step 12)을 그대로 쓴다. 타입은 `lib/analysis/lifezoneReportTemplate` 에서
 * 가져온다 — **`api` → `lib` 한 방향**이며, 문서 템플릿이 요구하는 칸이 곧 이 API 의 계약이다
 * (`fetchHotspotReport` 와 같은 규약).
 *
 * 404 3종: 보고서 없음 · LIFEZONE 실행이 아님 · 스냅샷 0행. 호출부는 셋을 구분하지 않는다.
 */
export async function fetchLifezoneReport(rptpId: number): Promise<LifezoneReportDoc> {
  const { data } = await api.get<LifezoneReportDoc>(`/reports/${rptpId}/lifezone`)
  return data
}

/**
 * 생활권 **수급** 보고서 스냅샷 (TSK-4089).
 *
 * 🔴 **민원건수 스냅샷(`/lifezone`)과 경로가 갈린다.** 저장된 결과 JSON 의 모양이 달라
 * (5등급 ↔ 4사분면) 파서가 다르고, 서로의 보고서를 부르면 **404** 다 — 조용히 읽어 빈 문서를
 * 만드는 대신 끊는다. 어느 쪽을 부를지는 목록의 `anlsTypeCd`
 * (`LIFEZONE` / `LIFEZONE_SPLY` / `LIFEZONE_CNGST`)가 정한다(`lib/report/reportDownload.ts`).
 *
 * 🔁 TSK-4258 — 반환은 **신·구 유니언**이다. 신규 스냅샷은 `anlsMthd: 'SHRT_RT'`(부족률 5단계), 구 스냅샷은
 * 그 키가 없다(사분면 — D14). 어느 템플릿으로 그릴지는 `buildLifezoneSupplyReportHtml` 이 이 값으로 고른다.
 */
export async function fetchLifezoneSupplyReport(rptpId: number): Promise<LifezoneSupplyReportDoc> {
  const { data } = await api.get<LifezoneSupplyReportDoc>(`/reports/${rptpId}/lifezone/supply`)
  return data
}

/** 생활권 **혼잡도** 보고서 스냅샷 (TSK-4317). 다른 유형의 보고서를 부르면 404 다. */
export async function fetchLifezoneCngstReport(rptpId: number): Promise<LifezoneCngstReportDoc> {
  const { data } = await api.get<LifezoneCngstReportDoc>(`/reports/${rptpId}/lifezone/congestion`)
  return data
}

/**
 * 민원현황 보고서 **스냅샷** 조회 (TSK-4164 Step 6). 저장된 KPI·지역 카드 목록을 그대로 다시 읽는다.
 *
 * 🔴 **보고서를 새로 만들지 않는다** — 재집계 없이 저장된 `rslt_json` 만 읽으므로 이 호출로
 * `tb_rptp` 행이 늘지 않는다. 원천 데이터가 그 사이 바뀌어도 보고서는 만들어진 날의 기록이다.
 *
 * 반환 타입은 생성(`POST /api/complaints/reports`) 응답과 **같은 `ComplaintReportDoc`** 이다 —
 * 백엔드가 두 경로에서 같은 `ComplaintReportVO` 를 내리므로 문서 템플릿이 두 경로를 구분하지 않는다.
 * 타입은 도메인 파일 `api/complaints` 에서 가져온다(집중구역·생활권이 `lib/analysis/*Template`
 * 에서 가져오는 것과 배치만 다르다 — 이쪽은 클라이언트가 템플릿보다 먼저다).
 *
 * 404 3종: 보고서 없음 · 민원현황 실행이 아님 · 스냅샷 0행. 호출부는 셋을 구분하지 않는다.
 */
export async function fetchComplaintReport(rptpId: number): Promise<ComplaintReportDoc> {
  const { data } = await api.get<ComplaintReportDoc>(`/reports/${rptpId}/complaint`)
  return data
}

/** AI 보고서 본문 스냅샷 응답 (TSK-4293 Step 5). */
export interface AiReportDoc {
  rptpId: number
  rptpNm: string
  /** 🔴 서버(jsoup)가 새니타이즈한 HTML — 그대로 쓰지 않고 `reportDownload.buildAiReportHtml` 로 감싼다. */
  html: string
}

/**
 * AI 보고서 본문 조회 — `GET /api/reports/{rptpId}/ai` (TSK-4293 Step 7).
 * 404 = 남의 것·없음(같은 404). 호출부(`buildPreview`)가 `ReportSnapshotMissingError` 로 바꾼다.
 */
export async function fetchAiReport(rptpId: number): Promise<AiReportDoc> {
  const { data } = await api.get<AiReportDoc>(`/reports/${rptpId}/ai`)
  return data
}

/** 자동 생성 설정 목록 조회 — 페이징 없이 전체. 서버 정렬(crt_dt DESC) 그대로 사용한다. */
export async function fetchReportSettings(): Promise<ReportSettingItem[]> {
  const { data } = await api.get<ReportSettingItem[]>('/report-settings')
  return data
}

/** 자동 생성 설정 등록 (201). 없는 코드값·주기별 필수값 누락·없는 지역은 400. */
export async function createReportSetting(
  request: ReportSettingRequest,
): Promise<ReportSettingItem> {
  const { data } = await api.post<ReportSettingItem>('/report-settings', request)
  return data
}

/** 자동 생성 설정 수정 (200). 없거나 이미 삭제된 설정이면 404, 입력이 잘못되면 400. */
export async function updateReportSetting(
  stngId: number,
  request: ReportSettingRequest,
): Promise<ReportSettingItem> {
  const { data } = await api.put<ReportSettingItem>(`/report-settings/${stngId}`, request)
  return data
}

/**
 * 자동 생성 설정 삭제 (204) — **소프트 삭제**다(D11).
 * 행은 남고 `deleted_at` 만 채워져 목록에서 빠진다. 보고서가 참조 중이어도 성공한다.
 */
export async function deleteReportSetting(stngId: number): Promise<void> {
  await api.delete(`/report-settings/${stngId}`)
}
