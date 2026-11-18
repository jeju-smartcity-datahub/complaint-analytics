import axios from 'axios'
import {
  createZoneRcmdtnFeeReport,
  createZoneRcmdtnShareReport,
  type ZoneRcmdtnFileFmt,
  type ZoneRcmdtnReport,
  type ZoneRcmdtnTrgtTypeSe,
} from '../../api/zoneRcmdtn'
import type { VrblVls } from '../../api/analysisWeight'
import { fetchCommonCodes, type CommonCodeItem } from '../../api/commonCodes'
import { SHARE_GRD_GROUP } from '../../components/analysis/zoneRcmdtnView'
import { exportDocx, exportPdf } from '../report/reportExport'
import {
  ZONE_RCMDTN_FEE_FILE_BASE,
  ZONE_RCMDTN_SHARE_FILE_BASE,
  buildZoneRcmdtnReportHtml,
} from './zoneRcmdtnReportTemplate'

/*
 * 구역추천 내보내기 — **저장(POST) → 문서 → 다운로드** (TSK-4240 Step 8).
 * 요금제 적용 · 공유주차제 적용 2종이며 `investPrrtExport` 와 같은 모양이다.
 *
 * 캡처·페이지 분할·다운로드는 보고서 공용 엔진(`lib/report/reportExport`)이 하고, 문서 본문은
 * `zoneRcmdtnReportTemplate` 이 만든다 — 이 모듈은 순서와 실패 갈래만 정한다.
 *
 * 🔴 **내보내기는 곧 저장이다**(D4). 한 번에 보고서 1건이 남고, 서버가 조건으로 **다시 계산한다** —
 * 화면이 들고 있는 순위를 보내지 않는다. 파일은 **그 저장 응답(스냅샷)으로** 만든다.
 * 화면에 떠 있는 값으로 만들지 않는다 — 저장된 것과 내려받은 것이 달라지면 안 된다.
 *
 * 🔴 **저장이 실패하면 파일도 만들지 않는다**(D11 — 투자우선순위와 같다).
 *
 * 🔴 **생성자를 보내지 않는다** — 서버가 세션에서 읽는다. 조건 타입에 사용자 칸을 만들지 않는다.
 */

/**
 * 요금제 적용의 저장 조건 — 🔴 **조회에 적용된 값**이다(설정 패널의 지금 입력이 아니다).
 * 단속 창은 조회와 마찬가지로 싣지 않는다(서버 기본값이 분석 정본이다).
 */
export interface ZoneRcmdtnFeeExportCriteria {
  /**
   * 🔁 TSK-4259 — 읍면동코드. **조회에 적용된 범위**(`applied.emdCd`)의 것이다 — 시군구 모드엔
   * 결과에 `zone` 이 없다. `sggNm` 과 배타이며 둘 다 있으면 `createZoneRcmdtnFeeReport` 가 이쪽만 싣는다.
   */
  emdCd?: string
  /** 🔁 TSK-4259 — 시군구명(시군구 모드). `emdCd` 가 없을 때만 요청에 실린다. */
  sggNm?: string
  /** 민원 창 시작일 (YYYYMMDD). */
  bgngYmd: string
  /** 민원 창 종료일 (YYYYMMDD). */
  endYmd: string
  /** 후보 주차장 유형 범위 — 🔴 빼면 서버 기본값(`NARROW`)이라 화면과 다른 순위가 저장된다. */
  trgtTypeSe: ZoneRcmdtnTrgtTypeSe
  /**
   * 결과를 만든 실행의 요금제 계수 일시 조정(TSK-4325 D7). 설정값으로 돌렸으면 `undefined`.
   * 🔴 필수 칸이다 — 선택 칸이면 빠뜨려도 컴파일이 통과해 보고서만 설정값으로 계산된다.
   * 🔴 팝오버 초안·실행 전 적용값을 넣지 않는다.
   */
  vrblVls: VrblVls | undefined
}

/** 공유주차제 적용의 저장 조건 — 🔴 **조회에 적용된 값**이다. */
export interface ZoneRcmdtnShareExportCriteria {
  /** 민원 창 시작일 (YYYYMMDD). */
  bgngYmd: string
  /** 민원 창 종료일 (YYYYMMDD). */
  endYmd: string
  /** 시군구명 필터. `null` = 전체 지역(요청에 싣지 않는다). */
  sggNm: string | null
  /** 결과를 만든 실행의 공유주차제 계수 일시 조정(TSK-4325 D7). 설정값이면 `undefined`. 🔴 필수 칸(위와 같은 이유). */
  vrblVls: VrblVls | undefined
  /**
   * 공통코드 `SHRN_RVW_GRD_CD` — 엑셀(TSK-4340)의 검토 등급 라벨에만 쓴다. 🔴 요청에는 싣지 않는다.
   * 화면이 이미 받은 목록을 넘긴다. 없으면 엑셀 생성 시 다시 받고, 그것도 실패하면 코드값 원문이다.
   */
  grdCodes?: CommonCodeItem[]
}

/**
 * 저장(POST) 실패. 🔴 이 에러가 났다면 **보고서도 파일도 없다.**
 * `status` 는 HTTP 상태코드(응답을 못 받았으면 null) — 400 은 조건 문제(D9 의 0건 포함)다.
 */
export class ZoneRcmdtnSaveError extends Error {
  readonly status: number | null
  constructor(status: number | null) {
    super('구역추천 보고서 저장 실패')
    this.name = 'ZoneRcmdtnSaveError'
    this.status = status
  }
}

/** 저장 호출을 감싸 실패를 `ZoneRcmdtnSaveError` 하나로 모은다. */
async function save(create: () => Promise<ZoneRcmdtnReport>): Promise<ZoneRcmdtnReport> {
  try {
    return await create()
  } catch (e) {
    throw new ZoneRcmdtnSaveError(axios.isAxiosError(e) ? (e.response?.status ?? null) : null)
  }
}

/**
 * 저장된 구역추천 보고서(저장 응답 · 스냅샷 공용)로 엑셀을 내려받는 작업 — `wbk` 가 없으면 `null`(TSK-4340 D12).
 * 🔴 결과 패널(`download`)과 보고서 목록·미리보기(`reportDownload.xlsxSource`)가 **이 함수 하나**를 쓴다 —
 * 보기(`fee`/`share`)로 생성기를 고르는 규칙이 두 벌이 되지 않게.
 *
 * - 파일명은 `rptpNm`, 없으면 `fallbackName` → 보기별 기본 이름(D14).
 * - 공유주차제 `grdCodes` 가 없으면 그때 공통코드를 받는다 — 실패하면 빈 목록(코드값 원문 폴백 · 생성기 기존 동작).
 * - 생성기는 동적 import — ExcelJS 를 메인 번들에 넣지 않는다.
 */
export function zoneRcmdtnXlsxTask(
  report: ZoneRcmdtnReport,
  fallbackName?: string,
  grdCodes?: CommonCodeItem[],
): (() => Promise<void>) | null {
  if (report.rcmdtnTypeCd === 'FEE') {
    const wbk = report.fee.wbk
    if (!wbk) return null
    const src = { ...report, wbk }
    const fileBase = report.rptpNm || fallbackName || ZONE_RCMDTN_FEE_FILE_BASE
    return async () => {
      const { downloadZoneRcmdtnFeeXlsx } = await import('./zoneRcmdtnFeeXlsx')
      await downloadZoneRcmdtnFeeXlsx(src, fileBase)
    }
  }
  const wbk = report.share.wbk
  if (!wbk) return null
  const fileBase = report.rptpNm || fallbackName || ZONE_RCMDTN_SHARE_FILE_BASE
  return async () => {
    const codes = grdCodes ?? (await fetchCommonCodes(SHARE_GRD_GROUP).catch((): CommonCodeItem[] => []))
    const { downloadZoneRcmdtnShareXlsx } = await import('./zoneRcmdtnShareXlsx')
    await downloadZoneRcmdtnShareXlsx({ ...report, wbk, grdCodes: codes }, fileBase)
  }
}

/** 저장 응답 → 문서 → 다운로드. 여기서 난 에러는 "저장은 됐는데 파일 실패" 다(그대로 던진다). */
async function download(
  report: ZoneRcmdtnReport,
  fileFmt: ZoneRcmdtnFileFmt,
  fileBase: string,
  grdCodes?: CommonCodeItem[],
): Promise<void> {
  if (fileFmt === 'XLSX') {
    // 저장 직후라 정상 경로에선 `wbk` 가 있다 — 없으면 "저장은 됐는데 파일 실패" 갈래(목록에서 다시 받기 안내).
    const task = zoneRcmdtnXlsxTask(report, undefined, grdCodes)
    if (!task) throw new Error('구역추천 저장 응답에 엑셀 데이터(wbk)가 없습니다.')
    await task()
    return
  }
  const html = buildZoneRcmdtnReportHtml(report)
  if (fileFmt === 'PDF') await exportPdf(html, fileBase)
  else await exportDocx(html, fileBase)
}

/**
 * 요금제 적용 — `내보내기 > PDF/Word/Excel` 한 번의 전체 동작. 저장된 보고서를 돌려준다.
 *
 * - 저장 실패 → `ZoneRcmdtnSaveError` (파일을 만들지 않는다)
 * - 저장은 됐는데 파일 생성 실패 → 원본 에러를 그대로 던진다. 🔴 이때 보고서는 **이미 목록에 있다** —
 *   호출부는 "다시 시도" 를 권하지 않는다(누르면 한 건이 더 생긴다). 목록에서 다시 내려받으면 된다.
 */
export async function exportZoneRcmdtnFeeReport(
  criteria: ZoneRcmdtnFeeExportCriteria,
  fileFmt: ZoneRcmdtnFileFmt,
): Promise<ZoneRcmdtnReport> {
  const report = await save(() =>
    createZoneRcmdtnFeeReport({
      emdCd: criteria.emdCd,
      sggNm: criteria.sggNm,
      bgngYmd: criteria.bgngYmd,
      endYmd: criteria.endYmd,
      trgtTypeSe: criteria.trgtTypeSe,
      vrblVls: criteria.vrblVls,
      fileFmt,
    }),
  )
  await download(report, fileFmt, ZONE_RCMDTN_FEE_FILE_BASE)
  return report
}

/** 공유주차제 적용 — 실패 갈래는 {@link exportZoneRcmdtnFeeReport} 와 같다. */
export async function exportZoneRcmdtnShareReport(
  criteria: ZoneRcmdtnShareExportCriteria,
  fileFmt: ZoneRcmdtnFileFmt,
): Promise<ZoneRcmdtnReport> {
  const report = await save(() =>
    createZoneRcmdtnShareReport({
      bgngYmd: criteria.bgngYmd,
      endYmd: criteria.endYmd,
      // 조회(`runShare`)와 같은 모양 — 전체 지역이면 키 자체를 싣지 않는다.
      ...(criteria.sggNm ? { sggNm: criteria.sggNm } : {}),
      vrblVls: criteria.vrblVls,
      fileFmt,
    }),
  )
  await download(report, fileFmt, ZONE_RCMDTN_SHARE_FILE_BASE, criteria.grdCodes)
  return report
}
