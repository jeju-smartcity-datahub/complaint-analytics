import axios from 'axios'
import {
  createInvestPrrtReport,
  type InvestPrrtFileFmt,
  type InvestPrrtReport,
} from '../../api/investPrrt'
import type { VrblVls } from '../../api/analysisWeight'
import { NO_XLSX_DATA_MESSAGE } from '../report/reportDownload'
import { exportDocx, exportPdf } from '../report/reportExport'
import { INVEST_PRRT_FILE_BASE, buildInvestPrrtReportHtml } from './investPrrtReportTemplate'

/*
 * 투자우선순위 내보내기 — **저장(POST) → 문서 → 다운로드** (TSK-4036 Step 10).
 *
 * 캡처·페이지 분할·다운로드는 보고서 공용 엔진(`lib/report/reportExport`)이 하고, 문서 본문은
 * `investPrrtReportTemplate` 이 만든다 — 이 모듈은 순서와 실패 갈래만 정한다.
 *
 * 🔴 **내보내기는 곧 저장이다.** 한 번에 보고서 1건이 남고, 파일은 **그 저장 응답(스냅샷)으로** 만든다.
 * 화면에 떠 있는 값으로 만들지 않는다 — 저장된 것과 내려받은 것이 달라지면 안 된다.
 *
 * 🔴 **집중구역(`hotspotExport`)과 다른 점: 저장이 실패하면 파일도 만들지 않는다**(계획서 Step 10).
 * 그쪽은 저장 실패 시 화면 결과로 파일을 만들지만, 여기는 화면이 상위 N 만 들고 있어 같은 문서가 될 수 없다.
 */

/**
 * 저장 대상 — 🔴 **행정구역 필터 + 결과를 만든 실행의 계수**뿐이다. 우선순위 세그먼트·정렬·날짜는 보내지 않는다.
 */
export interface InvestPrrtExportCriteria {
  /** 시군구명. 빈 문자열 = 전 지역. */
  sggNm: string
  /** 읍면동코드. 빈 문자열 = 시군구 전체. */
  emdCd: string
  /**
   * 결과를 만든 실행의 가중치·계수 일시 조정(TSK-4325 D7). 설정값으로 돌렸으면 `undefined`.
   * 🔴 팝오버 초안·실행 전 적용값을 넣지 않는다 — 화면 결과와 보고서가 같아야 한다.
   */
  vrblVls?: VrblVls
}

/**
 * 저장(POST) 실패. 🔴 이 에러가 났다면 **보고서도 파일도 없다.**
 * `status` 는 HTTP 상태코드(응답을 못 받았으면 null) — 400 은 조건 문제(필터 통과 0곳 포함)다.
 */
export class InvestPrrtSaveError extends Error {
  readonly status: number | null
  constructor(status: number | null) {
    super('투자우선순위 보고서 저장 실패')
    this.name = 'InvestPrrtSaveError'
    this.status = status
  }
}

/**
 * `내보내기 > PDF/Word/Excel` 한 번의 전체 동작. 저장된 보고서를 돌려준다.
 *
 * - 저장 실패 → `InvestPrrtSaveError` (파일을 만들지 않는다)
 * - Excel 인데 저장 응답에 `wbk` 가 없음(TSK-4343 D2-1 `ENFORCE` 갈래) → 파일 없이 `NO_XLSX_DATA_MESSAGE` 안내 후
 *   보고서를 돌려준다(D13). 🔴 실패가 아니다 — 보고서는 이미 저장됐고 다시 눌러도 결과가 같다.
 * - 저장은 됐는데 파일 생성 실패 → 원본 에러를 그대로 던진다. 🔴 이때 보고서는 **이미 목록에 있다** —
 *   호출부는 "다시 시도" 를 권하지 않는다(누르면 한 건이 더 생긴다). 목록에서 다시 내려받으면 된다.
 */
export async function exportInvestPrrtReport(
  criteria: InvestPrrtExportCriteria,
  fileFmt: InvestPrrtFileFmt,
): Promise<InvestPrrtReport> {
  let report: InvestPrrtReport
  try {
    report = await createInvestPrrtReport({
      sggNm: criteria.sggNm,
      emdCd: criteria.emdCd,
      fileFmt,
      vrblVls: criteria.vrblVls,
    })
  } catch (e) {
    throw new InvestPrrtSaveError(axios.isAxiosError(e) ? (e.response?.status ?? null) : null)
  }

  // 서버가 0곳이면 400 으로 막아 닿지 않는 경로다. 그래도 머리글만 있는 빈 파일은 만들지 않는다.
  if (report.lots.length === 0) return report

  /*
   * 🔴 Excel 갈래는 HTML 조립 **앞**에서 끝낸다(TSK-4343 Step 5) — 종전 `PDF ? … : exportDocx` 뒤에 붙이면
   * Excel 을 골라도 Word 가 내려간다. 생성기는 동적 import(ExcelJS 를 메인 번들에 넣지 않는다).
   */
  if (fileFmt === 'XLSX') {
    const wbk = report.wbk
    if (!wbk) {
      window.alert(NO_XLSX_DATA_MESSAGE)
      return report
    }
    const { downloadInvestPrrtXlsx } = await import('./investPrrtXlsx')
    await downloadInvestPrrtXlsx({ ...report, wbk })
    return report
  }

  const html = buildInvestPrrtReportHtml(report)
  if (fileFmt === 'PDF') await exportPdf(html, INVEST_PRRT_FILE_BASE)
  else if (fileFmt === 'DOCX') await exportDocx(html, INVEST_PRRT_FILE_BASE)
  return report
}
