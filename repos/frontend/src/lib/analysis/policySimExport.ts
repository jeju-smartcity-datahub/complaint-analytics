import axios from 'axios'
import {
  createPolicySimReport,
  type PolicySimReport,
  type PolicySimReportFileFmt,
  type PolicySimReportScenarioReq,
} from '../../api/policySim'
import { exportDocx, exportPdf } from '../report/reportExport'
import { buildPolicySimReportHtml } from './policySimReportTemplate'

/*
 * 정책 시뮬레이션 시나리오 비교 내보내기 — **저장(POST) → 문서 → 다운로드**.
 * `investPrrtExport`·`zoneRcmdtnExport` 와 같은 모양이다.
 *
 * 캡처·페이지 분할·다운로드는 보고서 공용 엔진(`lib/report/reportExport`)이 하고, 문서 본문은
 * `policySimReportTemplate` 이 만든다 — 이 모듈은 순서와 실패 갈래만 정한다.
 *
 * 🔴 **내보내기는 곧 저장이다.** 한 번에 보고서 1건이 남고, 파일은 **그 저장 응답(스냅샷)으로** 만든다
 * (저장분 `rsltId` 만 보낸다 = 저장값 그대로). 화면에 떠 있는 숫자로 만들지 않는다.
 *
 * 🔴 **파일명은 저장 응답의 `rptpNm`** 이다(`20261014(1)_정책 시뮬레이션 시나리오 비교`). 선례처럼 파일명 상수를
 * 두면 화면에서 받은 파일과 보고서 목록에서 다시 받은 파일의 이름이 갈린다("보고서 제목" 절).
 *
 * 🔴 **저장이 실패하면 파일도 만들지 않는다.** 생성자는 보내지 않는다 — 서버가 세션에서 읽는다.
 */

/**
 * 저장 대상 — 페이지가 **목록에 보이는 순서**로 조립한다.
 * `scenarios` 1~15건 · `headIdx` 는 현재 항상 `0`(선택한 것 중 목록 첫 행).
 */
export interface PolicySimExportCriteria {
  headIdx: number
  scenarios: PolicySimReportScenarioReq[]
}

/**
 * 저장(POST) 실패. 🔴 이 에러가 났다면 **보고서도 파일도 없다.**
 * `status` 는 HTTP 상태코드(응답을 못 받았으면 null) — 400 은 요청 검증 실패,
 * 404 는 선택한 저장 결과가 없거나 남의 것이다.
 */
export class PolicySimSaveError extends Error {
  readonly status: number | null
  constructor(status: number | null) {
    super('정책 시뮬레이션 보고서 저장 실패')
    this.name = 'PolicySimSaveError'
    this.status = status
  }
}

/**
 * `내보내기 > PDF/Word` 한 번의 전체 동작. 저장된 보고서를 돌려준다.
 *
 * - 저장 실패 → `PolicySimSaveError` (파일을 만들지 않는다)
 * - 저장은 됐는데 파일 생성 실패 → 원본 에러를 그대로 던진다. 🔴 이때 보고서는 **이미 목록에 있다** —
 *   호출부는 "다시 시도" 를 권하지 않는다(누르면 한 건이 더 생긴다). 목록에서 다시 내려받으면 된다.
 */
export async function exportPolicySimReport(
  criteria: PolicySimExportCriteria,
  fileFmt: PolicySimReportFileFmt,
): Promise<PolicySimReport> {
  let report: PolicySimReport
  try {
    report = await createPolicySimReport({
      fileFmt,
      headIdx: criteria.headIdx,
      scenarios: criteria.scenarios,
    })
  } catch (e) {
    throw new PolicySimSaveError(axios.isAxiosError(e) ? (e.response?.status ?? null) : null)
  }

  // 서버가 0건이면 400 으로 막아 닿지 않는 경로다. 그래도 머리글만 있는 빈 파일은 만들지 않는다.
  if (report.scenarios.length === 0) return report

  const html = buildPolicySimReportHtml(report)
  if (fileFmt === 'PDF') await exportPdf(html, report.rptpNm)
  else await exportDocx(html, report.rptpNm)
  return report
}
