import axios from 'axios'
import {
  fetchAiReport,
  fetchComplaintReport,
  fetchHotspotReport,
  fetchLifezoneCngstReport,
  fetchLifezoneReport,
  fetchLifezoneSupplyReport,
  type ReportListItem,
} from '../../api/reports'
import { fetchInvestPrrtReportSnapshot, type InvestPrrtReport } from '../../api/investPrrt'
import { fetchPolicySimReportSnapshot } from '../../api/policySim'
import { fetchZoneRcmdtnReportSnapshot, type ZoneRcmdtnReport } from '../../api/zoneRcmdtn'
import { buildComplaintReportHtml } from '../analysis/complaintReportTemplate'
import { buildHotspotReportHtml, type HotspotReportDoc } from '../analysis/hotspotReportTemplate'
import { downloadHotspotXlsx } from '../analysis/hotspotXlsx'
import { buildInvestPrrtReportHtml } from '../analysis/investPrrtReportTemplate'
import {
  buildLifezoneCngstReportHtml,
  type LifezoneCngstReportDoc,
} from '../analysis/lifezoneCngstReportTemplate'
import { buildLifezoneReportHtml, type LifezoneReportDoc } from '../analysis/lifezoneReportTemplate'
import {
  buildLifezoneSupplyReportHtml,
  type LifezoneSupplyReportDoc,
} from '../analysis/lifezoneSupplyReportTemplate'
import { buildPolicySimReportHtml } from '../analysis/policySimReportTemplate'
import { zoneRcmdtnXlsxTask } from '../analysis/zoneRcmdtnExport'
import { buildZoneRcmdtnReportHtml } from '../analysis/zoneRcmdtnReportTemplate'
import { buildAiReportDocHtml } from './aiReportTemplate'
import { buildReportHtml } from './reportTemplate'

/*
 * 보고서 본문 생성.
 *
 * `ReportsPage.tsx` 안에 있던 `buildBody(report)` 를 **그대로 옮겨 온 것**이다 —
 * AI 어시스턴트 우측 `최근 리포트` 카드(`components/ai/RecentReportList`)도 같은 본문·같은
 * 내보내기를 써야 하는데, 화면마다 스냅샷 분기를 복제하면 규칙이 두 벌이 되어 갈린다.
 *
 * 내보내기(`exportPdf`·`exportDocx`)와 샘플 본문 템플릿(`buildReportHtml`)은 이미 `lib/report/`
 * 공용이었다. 남아 있던 마지막 화면 종속 조각이 `buildBody` 였고, 그것을 여기로 모았다.
 * **두 화면(`ReportsPage`·`RecentReportList`) 모두 미리보기는 `useReportPreview` →
 * `buildBody`, 내보내기는 미리보기 모달(및 `ReportsPage` 의 `DownloadCell`)이 `buildBody` 결과로
 * `exportPdf`·`exportDocx` 를 부른다.** 종전 `RecentReportList` 의 행 클릭 = PDF 다운로드
 * 전용 함수는 호출부가 0이 되어 지웠다.
 *
 * 스냅샷 분기는 그 뒤로 더 늘었다 — 생활권·생활권 수급·생활권 혼잡도에 이어
 * **민원현황** · **투자우선순위** · **구역추천** · **정책 시뮬레이션** 까지 여덟 유형이다.
 * 구역추천은 한 코드(`RECOMMEND`)에 두 보기(요금제 · 공유주차제)라 스냅샷 응답이 본문을 정한다.
 * 늘어나도 고치는 곳은 여전히 이 함수 하나다.
 */

/** 스냅샷이 없을 때(404) 사용자에게 띄우는 안내 문구 — ✅ 사용자 지정 2026-09-30. */
export const SNAPSHOT_MISSING_MESSAGE =
  '보고서가 생성되지 않았습니다. 죄송합니다. 수동으로 시도해주세요.'

/**
 * 스냅샷 조회가 **404** 일 때만 던지는 전용 에러.
 *
 * 🔴 **"실패" 가 아니라 "아직 없음" 이다.** 호출부는 이 에러를 만나면 자기 실패 표시
 * (미리보기 `다시 시도` · 다운로드 `내보내기 실패`)를 띄우지 않고 안내만 한다 —
 * 다시 눌러도 결과가 같으므로 `다시 시도` 를 권하는 것이 거짓말이 된다.
 *
 * 🔴 **404 만 좁게 잡는다.** 네트워크·500 까지 이 에러로 뭉개면 진짜 장애가
 * "생성되지 않았습니다" 로 가려진다 — 그것들은 기존 실패 경로 그대로 던진다.
 */
export class ReportSnapshotMissingError extends Error {
  constructor() {
    super(SNAPSHOT_MISSING_MESSAGE)
    this.name = 'ReportSnapshotMissingError'
  }
}

/**
 * 스냅샷 조회의 **404 만** `ReportSnapshotMissingError` 로 바꾼다. 그 밖은 원본 그대로 던진다.
 *
 * 상태코드는 저장소 관례대로 `axios.isAxiosError(e) && e.response?.status === 404` 로 꺼낸다
 * (`pages/reports/ReportManagementPage.tsx`·`pages/alerts/AlertManagementPage.tsx` 와 같은 모양).
 * 네트워크 단절·타임아웃은 `e.response` 가 **undefined** 라 여기에 걸리지 않는다.
 */
async function snapshot<T>(load: () => Promise<T>): Promise<T> {
  try {
    return await load()
  } catch (e) {
    if (axios.isAxiosError(e) && e.response?.status === 404) {
      throw new ReportSnapshotMissingError()
    }
    throw e
  }
}

/**
 * 호출부 공용 — 이 에러가 "스냅샷 없음" 이면 **안내만 띄우고 `true`** 를 돌려준다.
 * `false` 면 호출부는 기존 실패 표시를 그대로 켠다.
 *
 * 🔴 판정과 문구를 화면마다 복제하지 않기 위해 여기 둔다(`buildBody` 와 같은 이유).
 * 안내 수단은 `window.alert` 로 못박혀 있다 — ✅ 사용자 지정 2026-09-30.
 */
export function notifyIfSnapshotMissing(e: unknown): boolean {
  if (e instanceof ReportSnapshotMissingError) {
    window.alert(SNAPSHOT_MISSING_MESSAGE)
    return true
  }
  return false
}

/**
 * 미리보기·PDF·DOCX **세 경로가 함께 쓰는 본문 생성기**.
 * 세 경로가 같은 함수를 부르므로 화면에서 본 문서와 내려받은 파일이 갈릴 수 없다.
 *
 * 🔴 **분기는 코드로 한다 — 표시명(`srcNm`)이 아니다.**
 *
 * 🔴 **판정 기준은 `crtType` 이 아니라 "스냅샷이 있는가" 다** (✅ 2026-09-30) — 예외: AI 어시스턴트 보고서(`crtType === 'AI' && anlsTypeCd == null`).
 * 종전에는 네 유형 모두 `crtType === 'MANUAL'` 을 **이중 가드**로 걸었다. 근거는 시드 목데이터였다 —
 * 시드 AUTO 14행은 `TB_ANLS_RSLT` 가 없어 스냅샷이 404 였고, 가드를 빼면 자동 생성 탭 미리보기가
 * 통째로 깨졌다. **그 목데이터를 걷어 전제가 사라졌는데 가드만 남아**, 배치가 실제로
 * 만든 AUTO 보고서(`rptpId=50` CVLCPT — `GET /reports/50/complaint` 200)까지 전부 샘플 본문으로
 * 떨어졌다. **내용이 다른 문서가 성공한 척 내려가고 있었다.**
 *
 * 그래서 `crtType` 가드를 걷고, 스냅샷이 없는 경우(404)는 **샘플로 떨어지지 않고**
 * `ReportSnapshotMissingError` 를 던져 호출부가 안내하게 한다. 네트워크·500 은 종전대로 그대로 던진다.
 *
 * 🔁 **AI 보고서** — 분석 실행이 없어 `anlsTypeCd` 가 null 이라 `anlsTypeCd` 로는 가를 수
 * 없고, **생성 유형이 AI 이면서 분석 유형 코드가 비어 있는 행**만 AI 본문 스냅샷(`GET /reports/{id}/ai`)으로 연다.
 * 종전에는 조회할 엔드포인트가 없어 샘플 본문이었다 — 이제는 404 신호를 받으므로 샘플로 떨어지지 않고
 * 다른 유형과 같이 `ReportSnapshotMissingError` 로 안내한다(남겨 두면 진짜 AI 보고서가 샘플로 보인다).
 * 🔴 분석 유형 코드가 있는 AI 행(로컬 DB 의 DB 직접 수정 테스트 데이터 10행)은 아래 유형별 분기로 계속 연다 —
 *    생성 유형만 보면 그 행들이 `/ai` 404 로 떨어져 지금 열리던 스냅샷이 "생성되지 않았습니다" 가 된다.
 * ⚠️ `anlsTypeCd` 가 null 인 그 밖의 유형(`DASHBOARD` 등)은 여전히 샘플 본문이다 — 조회할 스냅샷
 * 엔드포인트 자체가 없어 404 라는 신호도 못 받는다.
 *
 * 🔴 스냅샷 경로는 **읽기 전용**이다 — 네 GET 모두 저장된 결과를 다시 읽을 뿐 보고서를 새로
 * 만들지 않으므로, 미리보기·재다운로드를 몇 번 눌러도 목록 건수가 변하지 않는다.
 */
export async function buildBody(report: ReportListItem): Promise<string> {
  return (await buildPreview(report)).html
}

/**
 * 엑셀을 만들 수 있는 보고서 유형의 스냅샷 — **판별 유니온 하나**(종전 집중구역 전용 prop 을 대체).
 * 🔴 유형이 늘어도 prop·상태를 탭마다 따로 늘리지 않는다 — `kind` 를 하나 더한다.
 * `kind` 는 목록 행의 `anlsTypeCd` 그대로다 — 예외는 구역추천(`RECOMMEND`)으로, 한 코드에 두 보기라
 * 스냅샷 `rcmdtnTypeCd` 로 `RECOMMEND_FEE`·`RECOMMEND_SHARE` 두 갈래가 된다.
 */
export type ReportXlsxDoc =
  | { kind: 'HOTSPOT'; doc: HotspotReportDoc }
  | { kind: 'LIFEZONE'; doc: LifezoneReportDoc }
  | { kind: 'LIFEZONE_SPLY'; doc: LifezoneSupplyReportDoc }
  | { kind: 'LIFEZONE_CNGST'; doc: LifezoneCngstReportDoc }
  | { kind: 'RECOMMEND_FEE'; doc: Extract<ZoneRcmdtnReport, { rcmdtnTypeCd: 'FEE' }> }
  | { kind: 'RECOMMEND_SHARE'; doc: Extract<ZoneRcmdtnReport, { rcmdtnTypeCd: 'SHARE' }> }
  | { kind: 'INVEST'; doc: InvestPrrtReport }

export type ReportXlsxKind = ReportXlsxDoc['kind']

/** 엑셀 대상 **분석 유형 코드**(목록 행 `anlsTypeCd`) — 구역추천은 코드 하나(`RECOMMEND`)가 두 `kind` 로 갈린다. */
const XLSX_KINDS: readonly string[] = ['HOTSPOT', 'LIFEZONE', 'LIFEZONE_SPLY', 'LIFEZONE_CNGST', 'RECOMMEND', 'INVEST']

/**
 * 목록 다운로드 칸에 `XLSX` 를 그릴 유형인가(집중구역 · 생활권 3종 · 구역추천 · 투자우선순위).
 * 🔴 **다운로드 형식 판정의 정본이다** — 참이면 **PDF·XLSX**, 거짓이면 **PDF·DOCX**
 * (민원현황·정책 시뮬레이션 · 그 밖에 엑셀이 없는 AI 보고서 등). 목록 다운로드 칸·미리보기 창이 함께 쓴다.
 */
export function isXlsxReport(report: Pick<ReportListItem, 'anlsTypeCd'>): boolean {
  return report.anlsTypeCd != null && XLSX_KINDS.includes(report.anlsTypeCd)
}

/** 미리보기 결과 — 본문 HTML 과, 엑셀 대상 유형이면 그 스냅샷. */
export interface ReportPreviewBody {
  html: string
  /** 엑셀 대상 유형(`ReportXlsxDoc`)의 스냅샷. 그 밖의 유형은 `null` — 미리보기 창이 Excel 버튼을 그리지 않는다. */
  xlsxDoc: ReportXlsxDoc | null
}

/**
 * `buildBody` 의 본체 — 본문과 함께 엑셀용 스냅샷을 돌려준다.
 * 🔴 미리보기 훅은 스냅샷 함수를 직접 부르지 않고 여기를 거친다. 분기 규칙은 `buildBody` 주석과 같다.
 * 🔴 스냅샷 GET 은 유형당 1회 — 본문과 엑셀이 같은 객체를 쓴다(클릭 때 다시 조회하지 않는다).
 */
export async function buildPreview(report: ReportListItem): Promise<ReportPreviewBody> {
  // 🔴 유형별 분기들 **앞**이다. 조건의 `anlsTypeCd == null` 을 빼지 않는다(위 주석 — 분석 유형이 있는 AI 행).
  if (report.crtType === 'AI' && report.anlsTypeCd == null) {
    const doc = await snapshot(() => fetchAiReport(report.rptpId))
    return { html: buildAiReportHtml(doc.rptpNm ?? report.rptpNm, doc.html), xlsxDoc: null }
  }
  const xlsxDoc = await loadXlsxDoc(report)
  if (xlsxDoc) return { html: xlsxDocHtml(xlsxDoc), xlsxDoc }
  return { html: await buildOtherBody(report), xlsxDoc: null }
}

/** 엑셀 대상 유형이면 스냅샷을 받아 유니온으로 감싼다. 그 밖의 유형은 `null`(조회하지 않는다). 404 는 `ReportSnapshotMissingError`. */
async function loadXlsxDoc(report: ReportListItem): Promise<ReportXlsxDoc | null> {
  switch (report.anlsTypeCd) {
    case 'HOTSPOT':
      return { kind: 'HOTSPOT', doc: await snapshot(() => fetchHotspotReport(report.rptpId)) }
    // 생활권 시뮬레이션.
    case 'LIFEZONE':
      return { kind: 'LIFEZONE', doc: await snapshot(() => fetchLifezoneReport(report.rptpId)) }
    /*
     * 🔴 생활권 **수급**은 별도 코드·별도 스냅샷이다. 같은 화면이지만 저장된 결과
     * JSON 의 모양이 달라(5등급 ↔ 4사분면) 파서가 다르다 — `LIFEZONE` 으로 묶으면 수급 보고서를
     * 민원건수 파서가 읽어 **조용히 빈 표**가 나온다(목록에서는 정상으로 보인다).
     * 같은 코드 안에서 신(부족률 5단계)·구(사분면) 스냅샷이 갈린다. 템플릿이 `anlsMthd` 로 고른다.
     */
    case 'LIFEZONE_SPLY':
      return {
        kind: 'LIFEZONE_SPLY',
        doc: await snapshot(() => fetchLifezoneSupplyReport(report.rptpId)),
      }
    // 🔴 생활권 **혼잡도**. 이 분기가 빠지면 목록에서 열 때 샘플 문서가 나온다.
    case 'LIFEZONE_CNGST':
      return {
        kind: 'LIFEZONE_CNGST',
        doc: await snapshot(() => fetchLifezoneCngstReport(report.rptpId)),
      }
    /*
     * 구역추천 — 🔴 한 코드에 두 보기(요금제 · 공유주차제)다. 목록의 anlsTypeCd 로는 가를 수 없어
     * 스냅샷 응답의 rcmdtnTypeCd 가 갈래를 정한다(본문은 템플릿 안에서 분기 · `buildOtherBody` 에서 옮겼다).
     */
    case 'RECOMMEND': {
      const doc = await snapshot(() => fetchZoneRcmdtnReportSnapshot(report.rptpId))
      return doc.rcmdtnTypeCd === 'FEE'
        ? { kind: 'RECOMMEND_FEE', doc }
        : { kind: 'RECOMMEND_SHARE', doc }
    }
    /*
     * 투자우선순위 (`buildOtherBody` 에서 옮겼다). 분석 화면의 `내보내기` 가 저장한
     * 스냅샷을 **같은 템플릿**으로 그린다. 재계산이 없어 가중치 설정을 바꾼 뒤에 열어도 저장 시점 내용이다.
     */
    case 'INVEST':
      return { kind: 'INVEST', doc: await snapshot(() => fetchInvestPrrtReportSnapshot(report.rptpId)) }
    default:
      return null
  }
}

/** 유니온 스냅샷 → 미리보기·PDF·DOCX 본문(종전 유형별 분기와 같은 템플릿). */
function xlsxDocHtml(x: ReportXlsxDoc): string {
  switch (x.kind) {
    case 'HOTSPOT':
      return buildHotspotReportHtml(x.doc)
    case 'LIFEZONE':
      return buildLifezoneReportHtml(x.doc)
    case 'LIFEZONE_SPLY':
      return buildLifezoneSupplyReportHtml(x.doc)
    case 'LIFEZONE_CNGST':
      return buildLifezoneCngstReportHtml(x.doc)
    case 'RECOMMEND_FEE':
    case 'RECOMMEND_SHARE':
      return buildZoneRcmdtnReportHtml(x.doc)
    case 'INVEST':
      return buildInvestPrrtReportHtml(x.doc)
  }
}

/**
 * AI 보고서 본문(서버 새니타이즈 HTML)을 미리보기·PDF·DOCX 공용 문서 틀로 감싼다.
 *
 * 🔴 `aiReportTemplate` 을 import 하는 **유일한 곳**이다 — 미리보기 훅의 `openHtml`(저장 전 답변 카드)과
 *    `buildPreview` 의 AI 분기(저장본 재열람)가 이 함수를 함께 쓴다. 래핑이 두 벌이 되면
 *    카드에서 본 문서와 `최근 리포트` 에서 다시 연 문서가 갈린다.
 */
export function buildAiReportHtml(title: string, html: string): string {
  return buildAiReportDocHtml(title, html)
}

/** 엑셀 데이터(`wbk`)가 없는 구 보고서 안내 — 미리보기 창·목록 다운로드 칸 공용. */
export const NO_XLSX_DATA_MESSAGE = '이 보고서는 엑셀 데이터가 없습니다.'

/**
 * 스냅샷으로 엑셀을 만들 수 있으면 **내려받기 작업**을, 없으면 `null` 을 돌려준다(종전 `hotspotXlsxSource`).
 * 미리보기 창과 목록 다운로드 칸이 같은 판정을 쓴다.
 *
 * - `HOTSPOT` — `wbk`·`gradeCriteria` 가 있어야 한다.
 * - `LIFEZONE`(민원건수) — `wbk` 가 없는 정적 엑셀이라 구 보고서도 나온다.
 * - `LIFEZONE_SPLY` — 🔴 구 사분면 스냅샷(`anlsMthd !== 'SHRT_RT'`)·`wbk` 부재면 `null`.
 * - `LIFEZONE_CNGST` — `wbk` 부재면 `null`.
 * - `RECOMMEND_FEE`·`RECOMMEND_SHARE` — 본문 `fee.wbk`·`share.wbk` 부재면 `null`(구 보고서 · 요금제 구 방식 스냅샷).
 *   판정·생성기 선택은 결과 패널과 같은 `zoneRcmdtnXlsxTask` 한 곳이다.
 * - `INVEST` — `wbk` 부재면 `null`(구 보고서 · `ENFORCE` 가중치 실행).
 *
 * 파일명은 스냅샷 `rptpNm`, 없으면 `fallbackName`(목록 행의 보고서명).
 * 생활권 생성기는 동적 import — 메인 번들에 넣지 않는다.
 */
export function xlsxSource(
  x: ReportXlsxDoc | null,
  fallbackName: string,
): (() => Promise<void>) | null {
  if (!x) return null
  switch (x.kind) {
    case 'HOTSPOT': {
      const doc = x.doc
      if (!doc.wbk || !doc.gradeCriteria?.length) return null
      const src = { ...doc, rptpNm: doc.rptpNm ?? fallbackName, wbk: doc.wbk }
      return () => downloadHotspotXlsx(src)
    }
    case 'LIFEZONE': {
      const doc = x.doc
      return async () => {
        const { downloadLifezoneXlsx } = await import('../analysis/lifezoneXlsx')
        await downloadLifezoneXlsx(doc, doc.rptpNm || fallbackName)
      }
    }
    case 'LIFEZONE_SPLY': {
      const doc = x.doc
      if (doc.anlsMthd !== 'SHRT_RT' || !doc.wbk) return null
      const src = { ...doc, wbk: doc.wbk }
      return async () => {
        const { downloadLifezoneSupplyXlsx } = await import('../analysis/lifezoneSupplyXlsx')
        await downloadLifezoneSupplyXlsx(src, doc.rptpNm || fallbackName)
      }
    }
    case 'LIFEZONE_CNGST': {
      const doc = x.doc
      if (!doc.wbk) return null
      const src = { ...doc, wbk: doc.wbk }
      return async () => {
        const { downloadLifezoneCngstXlsx } = await import('../analysis/lifezoneCngstXlsx')
        await downloadLifezoneCngstXlsx(src, doc.rptpNm || fallbackName)
      }
    }
    case 'RECOMMEND_FEE':
    case 'RECOMMEND_SHARE':
      return zoneRcmdtnXlsxTask(x.doc, fallbackName)
    case 'INVEST': {
      const doc = x.doc
      if (!doc.wbk) return null
      const src = { ...doc, wbk: doc.wbk }
      return async () => {
        const { downloadInvestPrrtXlsx } = await import('../analysis/investPrrtXlsx')
        await downloadInvestPrrtXlsx(src, doc.rptpNm || fallbackName)
      }
    }
  }
}

/**
 * 보고서 목록 다운로드 칸의 `XLSX` (종전 `exportHotspotXlsx` 일반화) — 스냅샷을 받아 엑셀을 내려받는다.
 *
 * 구 보고서(`wbk` 없음 · 구 수급 사분면)는 클릭 후 안내(`NO_XLSX_DATA_MESSAGE`)만 띄운다 — 실패가 아니다
 * (다시 눌러도 결과가 같다 · 2026-11-09 사용자 결정). 목록 행에는 `wbk` 유무가 없어 클릭 전에 잠글 수 없다.
 * 스냅샷 404 는 PDF·DOCX 와 같이 `ReportSnapshotMissingError` 로 던진다(호출부가 `notifyIfSnapshotMissing`).
 */
export async function exportXlsx(report: ReportListItem): Promise<void> {
  const task = xlsxSource(await loadXlsxDoc(report), report.rptpNm)
  if (!task) {
    window.alert(NO_XLSX_DATA_MESSAGE)
    return
  }
  await task()
}

/** 엑셀 대상(`ReportXlsxDoc`) 밖 유형의 본문 — 종전 `buildBody` 의 나머지 분기 그대로다(생활권 3종·구역추천·투자우선순위는 `loadXlsxDoc` 으로 옮겼다). */
async function buildOtherBody(report: ReportListItem): Promise<string> {
  /*
   * 민원현황. 자동 생성이 만드는 두 유형 중 하나다.
   */
  if (report.anlsTypeCd === 'CVLCPT') {
    return buildComplaintReportHtml(await snapshot(() => fetchComplaintReport(report.rptpId)))
  }
  /*
   * 정책 시뮬레이션. 분석 화면의 `내보내기` 가 저장한 스냅샷을 **같은 템플릿**으로 그린다.
   * 🔴 이 분기가 빠지면 목록에서는 정상으로 보이는데 열면 샘플 문서가 나온다(위 머리 주석의 그 사고다).
   */
  if (report.anlsTypeCd === 'POLICY_SIM') {
    const snap = await snapshot(() => fetchPolicySimReportSnapshot(report.rptpId))
    return buildPolicySimReportHtml(snap)
  }
  // 스냅샷 API 가 없는 유형(AI · DASHBOARD · 실행 미연결)만 샘플 본문으로 남는다 — 위 ⚠️ 참고.
  return buildReportHtml(report)
}
