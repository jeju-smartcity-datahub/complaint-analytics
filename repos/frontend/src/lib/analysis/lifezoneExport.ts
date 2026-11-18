import {
  createLifezoneReport,
  type LifezoneAnalysisResult,
  type LifezoneFileFmt,
  type LifezoneReportResponse,
} from '../../api/lifezone'
import { exportDocx, exportPdf } from '../report/reportExport'
import { buildLifezoneReportHtml } from './lifezoneReportTemplate'

/*
 * 생활권 시뮬레이션 내보내기 — **저장(POST) → 문서 → 다운로드**.
 *
 * **파이프라인을 새로 만들지 않는다.** 캡처·페이지 분할·다운로드·파일명 정리는 전부 보고서의
 * `lib/report/reportExport` 가 이미 갖고 있으므로, 이 모듈은 **무엇을 어떤 이름으로 내보낼지**만 정한다.
 *   - `exportPdf(html, fileBase)`  : 화면 밖 임시 노드 → html2canvas → jsPDF(A4 다중 페이지) → `save()`
 *   - `exportDocx(html, fileBase)` : `html-docx-js` 를 런타임 스크립트로 로드 → `asBlob()` → `a[download]`
 *   - 두 함수 모두 내부에서 `sanitizeFileBase()` 로 윈도우 금지문자를 치환하므로 여기서 확장자·금지문자를
 *     다루지 않는다. 보고서명(`생활권 민원건수 보고서 전체 지역 500m`)에도 금지문자가 없어 그대로 통과한다.
 *
 * 🔴 **집중구역 `hotspotExport.ts` 를 재사용하지 않는다.** 두 모듈이 공유하는 것은 이미 공용인
 * `lib/report/reportExport`(캡처·변환·다운로드) 뿐이고, 나머지는 전부 도메인 값이다 —
 * 저장 API·요청 조건 필드·문서 템플릿·폴백 파일명이 모두 다르다. 한 함수로 합치면 매개변수가
 * 두 도메인의 합집합이 되고 어느 쪽도 그 절반을 안 쓴다.
 *
 * 🔴 **문서 HTML 은 이 파일이 만들지 않는다.** `lifezoneReportTemplate.buildLifezoneReportHtml` 이
 * 미리보기·PDF·DOCX 세 경로의 단일 소스다. 서식이 두 벌로 갈리면 "목록에서 미리 본 것과 내려받은
 * 파일이 다르다" 가 된다.
 *
 * 🔴 **내보내기 1회 = 보고서 1건**이다. PDF·DOCX 를 각각 누르면 보고서도 2건 남는다 —
 * 저장 행에 `file_fmt` 가 있어 "무엇으로 내보냈는지" 가 목록의 기록이기 때문이다.
 * 서버는 화면이 보낸 숫자를 믿지 않고 같은 조건으로 **재분석**하므로 저장 응답이 곧 정본이다.
 *
 * ⚠️ PDF 는 **이미지 기반**이라 텍스트 선택·검색이 되지 않는다 — 보고서와 같은 기존 한계다
 *    (`reportExport.exportPdf` 주석 참고).
 */

/**
 * 저장 실패 폴백의 파일명(확장자 제외) — 시안 기본값 그대로다
 * (`생활권_시뮬레이션_분석결과.pdf` / `.docx`).
 *
 * 🔴 **저장에 성공하면 이 값을 쓰지 않는다** — 응답의 `rptpNm`
 * (`생활권 민원건수 보고서 전체 지역 500m`)이 파일명이 되어, 내려받은 파일과 보고서 목록의 행이
 * 같은 이름으로 이어진다. 폴백에는 그 이름이 없다 — 보고서명 규칙(분석유형명·3단 지역명·격자)은
 * 서버(`LifezoneReportNaming`)가 유일한 출처라 프론트에서 두 번째로 구현하지 않는다.
 */
const FALLBACK_FILE_BASE = '생활권_시뮬레이션_분석결과'

/**
 * 내보내기 결과 — 파일은 **두 경우 모두 내려간다**. 다른 것은 목록에 남았는지뿐이다.
 * - `saved`   : 보고서 목록 > 직접 생성 보고서에 1건 남았고, 그 스냅샷으로 문서를 만들었다.
 * - `unsaved` : 저장에 실패해 화면 결과로 문서를 만들었다. 부제 3 이 없고 파일명은 기본값이다.
 */
export type LifezoneExportStatus = 'saved' | 'unsaved'

export interface LifezoneExportArgs {
  /**
   * 화면에 떠 있는 분석 결과 **전체**.
   *
   * 🔴 **설정 패널의 현재 입력값이 아니라 응답이 되비춘 "적용된 조건"** 이 여기서 나온다
   * (`bgngYmd`·`endYmd`·`sggNm`·`emdCd`·`anlsTrgtCd`·`gridSz`·`clsfMthd` 를 응답이 전부 echo 한다).
   * 사용자가 분석을 돌린 뒤 지역·기간 select 만 바꿔 놓고 `내보내기` 를 누르는 일이 흔한데, 그때
   * 입력값을 보내면 **화면에 떠 있는 표와 저장된 보고서가 다른 조건**이 된다.
   * 저장 실패 폴백의 문서 본문도 이 값으로 그린다.
   */
  result: LifezoneAnalysisResult
  /** 어떤 파일로 내보낼지. 저장 행의 `file_fmt` 로도 그대로 남는다. */
  fileFmt: LifezoneFileFmt
  /**
   * 저장 성공 직후 호출된다(선택). **다운로드보다 먼저** 부른다 — DB 에 행이 남은 이상
   * 캡처·변환이 그 뒤에 실패하더라도 호출부가 저장 사실을 알 수 있어야 한다.
   */
  onSaved?: (report: LifezoneReportResponse) => void
}

/**
 * `내보내기 > PDF/Word` 한 번의 전체 동작.
 *
 * 1. `POST /api/analysis/lifezone/reports` — 같은 조건으로 **서버가 재분석**해 저장한다.
 *    ⚠️ `limit` 을 보내지 않는다 — 조회(`fetchLifezoneAnalysis`)도 보내지 않아 둘 다 서버 기본값(상한 없음 —
 *    심각·경고·주의 전부)이다.
 *    응답이 `limit` 을 echo 하지 않아 화면 결과에서 되살릴 수도 없으므로, 양쪽 모두 기본값에 맡긴다.
 * 2. 성공하면 **응답 스냅샷**으로 문서를 만들고 파일명도 응답 `rptpNm` 을 쓴다(부제 3 포함).
 * 3. 실패하면 **화면 결과**로 문서를 만들어 다운로드는 진행한다 — 서버가 죽었다고 사용자가
 *    방금 본 표를 못 받게 하지 않는다. 이 경로엔 헤더 4칸이 없어 템플릿이 부제 3 을 알아서 생략한다.
 *
 * 저장 실패만 삼키고 **다운로드 실패는 그대로 던진다** — 호출부가 `내보내기 실패` 문구를 띄운다.
 */
export async function exportLifezoneReport({
  result,
  fileFmt,
  onSaved,
}: LifezoneExportArgs): Promise<LifezoneExportStatus> {
  let report: LifezoneReportResponse | null = null
  try {
    report = await createLifezoneReport({
      bgngYmd: result.bgngYmd,
      endYmd: result.endYmd,
      sggNm: result.sggNm,
      emdCd: result.emdCd,
      anlsTrgtCd: result.anlsTrgtCd,
      gridSz: result.gridSz,
      clsfMthd: result.clsfMthd,
      fileFmt,
    })
  } catch {
    // 네트워크 단절·5xx·400 어느 쪽이든 처리가 같다 — 저장은 포기하고 다운로드는 진행한다.
    report = null
  }

  if (report) onSaved?.(report)

  /*
   * 🔴 셀 0 일 때만 파일을 만들지 않는다 — 순위 0 은 정상이다.
   * 호출부(`LifezoneResultPanel`)가 셀 0 이면 내보내기 버튼을 잠그고 서버도 셀 0 이면 400 이라
   * 실제로는 닿지 않는 경로다. 그래도 **빈 문서**가 내려가는 것보다는 아무 것도 하지 않는 편이 낫다.
   * ⚠️ 순위는 심각·경고·주의로 한정돼 셀이 있어도 비는 일이 흔하다 — 그때는 템플릿이 안내 1행을
   * 넣은 문서를 만든다. 순위로 판정하면 **보고서는 저장됐는데 파일이 안 내려간다.**
   */
  const doc = report ?? result
  if (doc.totGridCnt === 0) return report ? 'saved' : 'unsaved'

  /*
   * 저장 성공 경로는 응답을 **그대로** 템플릿에 넘긴다 — 넓은 타입(`LifezoneReportResponse`)
   * → 좁은 타입(`LifezoneReportDoc`) 대입이 성립하기 때문이다.
   * 문서용으로 옮겨 담으면 칸이 하나 늘 때 조용히 누락된다.
   */
  const fileBase = report ? report.rptpNm : FALLBACK_FILE_BASE
  /*
   * 🆕 엑셀 — 민원건수는 재계산 입력(`wbk`)이 없는 정적 엑셀이라 PDF·DOCX 와 같은
   * 저장 실패 폴백(화면 결과 · 폴백 파일명)을 그대로 탄다. 생성기는 동적 import — 메인 번들에 넣지 않는다.
   */
  if (fileFmt === 'XLSX') {
    const { downloadLifezoneXlsx } = await import('./lifezoneXlsx')
    await downloadLifezoneXlsx(doc, fileBase)
  } else if (fileFmt === 'PDF') await exportPdf(buildLifezoneReportHtml(doc), fileBase)
  else await exportDocx(buildLifezoneReportHtml(doc), fileBase)

  return report ? 'saved' : 'unsaved'
}
