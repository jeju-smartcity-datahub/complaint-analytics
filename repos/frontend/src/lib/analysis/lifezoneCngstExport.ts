import {
  createLifezoneCngstReport,
  type LifezoneCngstReportResponse,
  type LifezoneCngstResult,
  type LifezoneFileFmt,
} from '../../api/lifezone'
import type { VrblVls } from '../../api/analysisWeight'
import { exportDocx, exportPdf } from '../report/reportExport'
import type { LifezoneExportStatus } from './lifezoneExport'
import { buildLifezoneCngstReportHtml } from './lifezoneCngstReportTemplate'

/*
 * 생활권 **혼잡도** 내보내기 (TSK-4317) — 저장(POST) → 문서 조립 → 파일 변환.
 *
 * 🔴 저장이 실패해도 파일은 내려간다(민원건수·수급과 같다). 대신 `unsaved` 로 알려 호출부가 문구를 띄운다.
 */

/** 저장 실패 시 쓰는 파일명. 저장되면 서버가 만든 보고서명을 쓴다. */
const FALLBACK_FILE_BASE = '생활권_혼잡도_분석결과'

export interface LifezoneCngstExportArgs {
  /** 🔴 설정 패널의 현재 입력값이 아니라 응답이 되비춘 "적용된 조건" 을 저장에 쓴다. */
  result: LifezoneCngstResult
  fileFmt: LifezoneFileFmt
  /**
   * 🆕 `result` 를 만든 실행의 혼잡도 계수 일시 조정(TSK-4325 D7). 설정값으로 돌렸으면 `null`.
   * 🔴 필수 칸이다 — 선택 칸이면 빠뜨려도 컴파일이 통과해 보고서만 설정값으로 계산된다.
   */
  vrblVls: VrblVls | null
}

export async function exportLifezoneCngstReport({
  result,
  fileFmt,
  vrblVls,
}: LifezoneCngstExportArgs): Promise<LifezoneExportStatus> {
  /*
   * 🔴 엑셀(TSK-4340 D7-2)은 아래 저장 실패 폴백을 **타지 않는다** — 그래서 try/catch 앞에서 갈라낸다.
   * 재계산 입력(`wbk`)은 서버 응답에만 있어 화면 결과로는 만들 수 없다. 저장 실패·`wbk` 부재는 그대로
   * 던져 호출부가 `내보내기 실패` 를 띄운다. 요청 조건은 PDF 와 같다(+ 일시 조정 `vrblVls`).
   * 파일명은 응답 `rptpNm`(D10). 생성기는 동적 import — 메인 번들에 넣지 않는다.
   */
  if (fileFmt === 'XLSX') {
    const res = await createLifezoneCngstReport({
      bgngYmd: result.bgngYmd,
      endYmd: result.endYmd,
      gridSz: result.gridSz,
      sggNm: result.sggNm,
      emdCd: result.emdCd,
      vrblVls: vrblVls ?? undefined,
      fileFmt,
    })
    if (!res.wbk) throw new Error('저장 응답에 엑셀 데이터(wbk)가 없습니다.')
    const { downloadLifezoneCngstXlsx } = await import('./lifezoneCngstXlsx')
    await downloadLifezoneCngstXlsx({ ...res, wbk: res.wbk }, res.rptpNm || FALLBACK_FILE_BASE)
    return 'saved'
  }

  let report: LifezoneCngstReportResponse | null = null
  try {
    report = await createLifezoneCngstReport({
      bgngYmd: result.bgngYmd,
      endYmd: result.endYmd,
      gridSz: result.gridSz,
      sggNm: result.sggNm,
      emdCd: result.emdCd,
      vrblVls: vrblVls ?? undefined,
      fileFmt,
    })
  } catch {
    // 저장 실패는 삼킨다 — 아래에서 화면 결과로 문서를 만든다.
    report = null
  }

  const doc = report ?? result
  if (doc.cells.length === 0) return report ? 'saved' : 'unsaved'

  const html = buildLifezoneCngstReportHtml(doc)
  const fileBase = report ? report.rptpNm : FALLBACK_FILE_BASE
  if (fileFmt === 'PDF') await exportPdf(html, fileBase)
  else await exportDocx(html, fileBase)
  return report ? 'saved' : 'unsaved'
}
