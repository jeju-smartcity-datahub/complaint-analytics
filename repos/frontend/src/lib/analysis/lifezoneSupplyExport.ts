import {
  createLifezoneSupplyReport,
  type LifezoneSupplyReportResponse,
  type LifezoneSupplyResult,
} from '../../api/lifezoneSupply'
import type { VrblVls } from '../../api/analysisWeight'
import type { LifezoneFileFmt } from '../../api/lifezone'
import { exportDocx, exportPdf } from '../report/reportExport'
import {
  buildLifezoneSupplyReportHtml,
  type ShrtRtLifezoneSupplyReportDoc,
} from './lifezoneSupplyReportTemplate'

/*
 * 생활권 **수급** 내보내기.
 *
 * 민원건수(`lifezoneExport.ts`)와 **같은 순서**다 — 저장(POST) → 문서 조립 → 파일 변환.
 * 유형별로 저장 경로와 템플릿만 갈린다.
 *
 * 🔴 **저장이 실패해도 파일은 내려간다.** 사용자가 원한 것은 문서이고, 목록에 남는 것은 부가
 * 가치다 — 저장 실패로 파일까지 막으면 "아무 일도 안 일어난" 화면이 된다. 대신 무엇이 안 됐는지
 * (`'unsaved'`)를 돌려주어 호출부가 문구로 알린다.
 */

/** 저장까지 됐는지. 파일은 두 경우 모두 내려갔고 다른 것은 목록에 남았는지뿐이다. */
export type LifezoneSupplyExportStatus = 'saved' | 'unsaved'

/** 저장 실패 시 쓰는 파일명. 저장되면 서버가 만든 보고서명을 쓴다. */
const FALLBACK_FILE_BASE = '생활권_주차수급_분석결과'

export interface LifezoneSupplyExportArgs {
  /**
   * 분석 응답 **전체**.
   *
   * 🔴 설정 패널의 현재 입력값이 아니라 **응답이 되비춘 "적용된 조건"** 을 저장에 쓴다 —
   * 분석을 돌린 뒤 설정만 바꿔 놓아도 문서가 실제 결과를 설명하게 하려는 것이다.
   */
  result: LifezoneSupplyResult
  fileFmt: LifezoneFileFmt
  /**
   * 🆕 `result` 를 만든 실행의 수급 계수 일시 조정. 설정값으로 돌렸으면 `null`.
   * 🔴 필수 칸이다 — 선택 칸이면 빠뜨려도 컴파일이 통과해 **보고서만 설정값으로 계산**된다.
   * 팝오버 초안·미실행 적용값을 넣지 않는다.
   */
  vrblVls: VrblVls | null
  /*
   * 표 거르기 값(`grade`, 종전 `qdrn`)은 넘기지 않는다. 화면 리스트가
   * 칩 없이 심각·경고·주의 전부라 생략(= 전체)이 곧 화면과 같은 표다. 백엔드 `grade` 계약은 남아 있다.
   */
  onSaved?: (report: LifezoneSupplyReportResponse) => void
}

export async function exportLifezoneSupplyReport({
  result,
  fileFmt,
  vrblVls,
  onSaved,
}: LifezoneSupplyExportArgs): Promise<LifezoneSupplyExportStatus> {
  /*
   * 🔴 엑셀은 아래 저장 실패 폴백을 **타지 않는다** — 그래서 try/catch 앞에서 갈라낸다.
   * 재계산 입력(`wbk`)은 서버 응답에만 있어 화면 결과로는 만들 수 없다. 저장 실패·`wbk` 부재·
   * 구 사분면 응답(`anlsMthd !== 'SHRT_RT'`)은 그대로 던져 호출부가 `내보내기 실패` 를 띄운다.
   * 요청 조건은 PDF 와 같다(결과를 만든 실행의 조건 + 일시 조정 `vrblVls`). 파일명은 응답 `rptpNm`.
   * 생성기는 동적 import — 메인 번들에 넣지 않는다.
   */
  if (fileFmt === 'XLSX') {
    const res = await createLifezoneSupplyReport({
      bgngYmd: result.bgngYmd,
      endYmd: result.endYmd,
      gridSz: result.gridSz,
      sggNm: result.sggNm,
      emdCd: result.emdCd,
      vrblVls: vrblVls ?? undefined,
      fileFmt,
    })
    onSaved?.(res)
    if (res.anlsMthd !== 'SHRT_RT' || !res.wbk) {
      throw new Error('저장 응답에 엑셀 데이터(wbk)가 없습니다.')
    }
    const { downloadLifezoneSupplyXlsx } = await import('./lifezoneSupplyXlsx')
    await downloadLifezoneSupplyXlsx({ ...res, wbk: res.wbk }, res.rptpNm || FALLBACK_FILE_BASE)
    return 'saved'
  }

  let report: LifezoneSupplyReportResponse | null = null
  try {
    report = await createLifezoneSupplyReport({
      bgngYmd: result.bgngYmd,
      endYmd: result.endYmd,
      gridSz: result.gridSz,
      sggNm: result.sggNm,
      emdCd: result.emdCd,
      vrblVls: vrblVls ?? undefined,
      fileFmt,
    })
  } catch {
    // 저장 실패는 삼킨다 — 아래에서 화면 결과로 문서를 만든다(위 주석 참고).
    report = null
  }
  if (report) onSaved?.(report)

  /*
   * 🔴 저장이 됐으면 **저장 응답으로** 문서를 만든다 — 서버가 다시 분석한 결과이고 보고서명·
   * 지역명·기간 문구까지 들어 있다. 실패했을 때만 화면 결과로 만든다(문서 자체는 같은 숫자다).
   */
  // 저장 응답은 `grade` 를 보내지 않았으니 `null`(= 전체)이고, unsaved 경로의 화면 결과도 전체다.
  // 둘 다 `anlsMthd: 'SHRT_RT'` 라 신규 템플릿으로 그려진다(조회 응답도 서버가 항상 채운다).
  const doc: ShrtRtLifezoneSupplyReportDoc = report ?? result
  if (doc.cells.length === 0) return report ? 'saved' : 'unsaved'

  const html = buildLifezoneSupplyReportHtml(doc)
  const fileBase = report ? report.rptpNm : FALLBACK_FILE_BASE
  if (fileFmt === 'PDF') await exportPdf(html, fileBase)
  else await exportDocx(html, fileBase)
  return report ? 'saved' : 'unsaved'
}
