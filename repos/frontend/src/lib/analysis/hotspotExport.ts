import {
  createHotspotReport,
  type HotspotFileFmt,
  type HotspotReportResponse,
  type HotspotWeight,
  type HotspotZone,
  type WgvlAjmtYn,
} from '../../api/analysis'
import type { VrblVls } from '../../api/analysisWeight'
import { exportDocx, exportPdf } from '../report/reportExport'
import { buildHotspotReportHtml, weightsCaption } from './hotspotReportTemplate'
import { downloadHotspotXlsx } from './hotspotXlsx'

/*
 * 집중 구역 순위 내보내기 — **저장(POST) → 문서 → 다운로드**.
 *
 * **파이프라인을 새로 만들지 않는다.** 캡처·페이지 분할·다운로드·파일명 정리는 전부 보고서의
 * `lib/report/reportExport` 가 이미 갖고 있으므로, 이 모듈은 **무엇을 어떤 이름으로 내보낼지**만 정한다.
 *   - `exportPdf(html, fileBase)`  : 화면 밖 임시 노드 → html2canvas → jsPDF(A4 다중 페이지) → `save()`
 *   - `exportDocx(html, fileBase)` : `html-docx-js` 를 런타임 스크립트로 로드 → `asBlob()` → `a[download]`
 *   - 두 함수 모두 내부에서 `sanitizeFileBase()` 로 윈도우 금지문자를 치환하므로 여기서 확장자·금지문자를
 *     다루지 않는다. 보고서명(`집중 구역 분석 보고서 제주시 연동`)에도 금지문자가 없어 그대로 통과한다.
 *
 * 🔴 **문서 HTML 은 이 파일이 만들지 않는다**. `hotspotReportTemplate.buildHotspotReportHtml`
 * 이 미리보기·PDF·DOCX 세 경로의 단일 소스이며, 종전에 이 파일이 들고 있던 표 조립(제목·부제·5열 표·
 * 등급 배지·색 변환)은 그쪽으로 옮겨 **중복 구현을 걷어냈다**. 서식이 두 벌로 갈리면
 * "목록에서 미리 본 것과 내려받은 파일이 다르다"가 된다.
 *
 * 🔴 **내보내기는 곧 저장이다**. `내보내기` 한 번에 보고서 1건이 남고, 그 저장 결과로 문서를 만든다 —
 * 화면 · 파일 · DB 가 같은 숫자를 보게 하려는 것이다. 서버는 화면이 보낸 숫자를 믿지 않고 같은 조건으로
 * **재분석**하므로, 저장 응답이 곧 정본이고 화면 결과도 그 값으로 갈아 끼운다.
 *
 * ⚠️ PDF 는 **이미지 기반**이라 텍스트 선택·검색이 되지 않는다 — 보고서와 같은 기존 한계다
 *    (`reportExport.exportPdf` 주석 참고).
 */

/* ── 1. 화면과 공유하는 표기 헬퍼 ───────────────────────
 *
 * 🔴 이 절의 세 함수는 **결과 패널(`HotspotResultPanel`)이 그대로 가져다 쓴다.**
 * 화면에서 본 값과 내려받은 문서의 값이 어긋나면 "표가 틀렸다"로 보이므로, 표기 규칙을 한곳에 둔다.
 * (반대 방향으로 두면 `패널 → 내보내기 → 패널` 순환 import 가 된다 — 그래서 이쪽이 원본이다.)
 */

/**
 * 소수점이 붙는 값(BigDecimal 유래)을 화면 문자열로.
 *
 * `snthsScr` 는 **0~100** 범위이고 소수 2자리로 온다 — 조회 결과 1위를 100 으로 둔 상대 점수라
 * `87.43` 처럼도, `100.00` 처럼 정수로도 온다.
 * 가중치 `stngVl` 도 `60.0000` 이다. 그대로 그리면 `87.43점` 과 `44점` 이 섞이므로 표기를 하나로 맞춘다 —
 * 정수면 소수를 떼고, 아니면 1자리까지만 남긴다.
 */
export function formatDecimal(value: number): string {
  return value.toLocaleString(undefined, { maximumFractionDigits: 1 })
}

/**
 * **화면 전용** 가중치 문구 — **응답의 `weights` 로 조립한다**.
 * `weights` 는 점수에 실제로 반영된 지표만, `sort_seq` 순으로 온다(재정렬 금지).
 * 비어 있으면(가중치가 전부 `use_yn='N'`) 문구 자체를 만들지 않는다 — `종합점수 기준 :` 만 남기지 않는다.
 *
 * 🔴 **구현은 `hotspotReportTemplate.ts` 에 있고 여기서 re-export 한다**.
 * 종전에는 여기서 `vrblNm`(변수명)으로 조립해 화면이 `민원빈도 60% / 단속건수 40%` 를 냈는데, 그것은
 * 산식 문서와 어긋나 있었다.
 * 라벨은 고정(`민원 건수`/`단속`)이고 비율만 응답에서 온다.
 * 🔁 문서는 `단속 건수`·` (가중치 조정 적용)` 을 쓰는 **다른 문자열**이다
 * (템플릿의 `reportWeightsCaption`). 라벨 표와 비율 표기만 공유한다.
 */
export { weightsCaption }

/** 구역 1건의 세부 내역 — 목록 행의 `.rl__sub` 이자 문서 표의 `세부 내역` 칸이다. */
export function zoneDetail(zone: Pick<HotspotZone, 'cvlcptCnt' | 'crdnCnt'>): string {
  return `민원 건수 ${zone.cvlcptCnt.toLocaleString()}건 · 단속 ${zone.crdnCnt.toLocaleString()}건`
}

/* ── 2. 문서 HTML ──────────────────────────────────── */

/**
 * 저장 실패 폴백의 파일명(확장자 제외) — 기본값 그대로다
 * (`불법주차_집중구역_순위.pdf` / `.docx`).
 *
 * 🔴 **저장에 성공하면 이 값을 쓰지 않는다** — 응답의 `rptpNm`(`집중 구역 분석 보고서 제주시 연동`)이
 * 파일명이 되어, 내려받은 파일과 보고서 목록의 행이 같은 이름으로 이어진다.
 * 폴백에는 그 이름이 없다 — 3단 지역 규칙은 서버(`HotspotReportNaming`)가 유일한 출처라
 * 프론트에서 두 번째로 구현하지 않는다.
 */
const FALLBACK_FILE_BASE = '불법주차_집중구역_순위'

/**
 * 문서 본문 HTML. **PDF·DOCX·미리보기가 공유하는 단일 소스**이며 구현은 문서 템플릿이 갖는다.
 *
 * 헤더 4칸(`rptpNm`·`regionNm`·`anlsPrdExpln`·`crtYmd`)이 없는 호출이라 **`분석 기간` 줄과 부제 3(분석 지역·
 * 생성일)이 빠진다** — 저장 없이 화면 결과만으로 문서를 만드는 경로(저장 실패 폴백)가 이 모양이다.
 * 저장 응답이 없으니 조정 여부는 호출부가 실행 입력값으로 정해 넘긴다.
 */
export function buildHotspotHtml(
  zones: HotspotZone[],
  weights: HotspotWeight[],
  wgvlAjmtYn: WgvlAjmtYn,
): string {
  return buildHotspotReportHtml({ weights, zones, wgvlAjmtYn, anlsMthd: 'LIST_MAX' })
}

/* ── 3. 내보내기 진입점 ────────────────────────────── */

/**
 * 저장 요청에 실을 분석 조건.
 *
 * 🔴 **설정 패널의 현재 입력값이 아니라 `HotspotAnalysisResult` 가 되비춘 "적용된 조건"** 을 넘긴다.
 * 사용자가 분석을 돌린 뒤 지역·기간 select 만 바꿔 놓고 `내보내기` 를 누르는 일이 흔한데, 그때
 * 입력값을 보내면 **화면에 떠 있는 표와 저장된 보고서가 다른 조건**이 된다.
 * 전 지역이면 `null` 이 오므로 `null` 을 허용한다(빈 값은 `createHotspotReport` 가 떼어낸다).
 */
export interface HotspotExportCriteria {
  bgngYmd: string
  endYmd: string
  sggNm: string | null
  emdCd: string | null
  /**
   * 결과를 만든 실행의 가중치(%). 설정값으로 돌렸으면 둘 다 `null` 이다.
   * 🔴 팝오버의 현재 값을 넣지 않는다 — 화면 결과와 보고서의 순위가 달라진다.
   */
  cvlcptWgvl: number | null
  crdnWgvl: number | null
  /**
   * 결과를 만든 실행의 등급 비율 일시 조정. 설정값으로 돌렸으면 `undefined`.
   * 🔴 팝오버 초안·실행 전 적용값을 넣지 않는다 — 가중치와 같은 원칙이다.
   */
  vrblVls?: VrblVls
}

/**
 * 내보내기 결과 — 파일은 **두 경우 모두 내려간다**. 다른 것은 목록에 남았는지뿐이다.
 * - `saved`   : 보고서 목록 > 직접 생성 보고서에 1건 남았고, 그 스냅샷으로 문서를 만들었다.
 * - `unsaved` : 저장에 실패해 화면 결과로 문서를 만들었다. 기간 줄·부제 3 이 없고 파일명은 기본값이다.
 */
export type HotspotExportStatus = 'saved' | 'unsaved'

export interface HotspotExportArgs {
  /** 저장 요청 조건 — 위 `HotspotExportCriteria` 주석 참고. */
  criteria: HotspotExportCriteria
  /** 어떤 파일로 내보낼지. 저장 행의 `file_fmt` 로도 그대로 남는다. */
  fileFmt: HotspotFileFmt
  /** 화면에 떠 있는 순위 목록 — **저장 실패 폴백에서만** 쓴다. */
  zones: HotspotZone[]
  /** 화면에 떠 있는 가중치 — 마찬가지로 폴백 전용. */
  weights: HotspotWeight[]
  /**
   * 저장 성공 직후 호출된다. **다운로드보다 먼저** 부르는 것이 중요하다 —
   * DB 에 행이 남은 이상 화면도 그 스냅샷을 보여야 하고, 캡처·변환이 그 뒤에 실패하더라도
   * "DB 와 화면이 다른" 상태로 남지 않는다.
   */
  onSaved?: (report: HotspotReportResponse) => void
}

/**
 * `내보내기 > PDF/Word/Excel` 한 번의 전체 동작. 엑셀은 저장 실패 폴백이 없다(아래 XLSX 갈래).
 *
 * 1. `POST /api/analysis/hotspot/reports` — 같은 조건으로 **서버가 재분석**해 저장한다.
 *    ⚠️ `limit` 을 보내지 않는다 — 조회(`fetchHotspotAnalysis`)도 보내지 않아 둘 다 서버 기본값 15 다.
 * 2. 성공하면 **응답 스냅샷**으로 문서를 만들고 파일명도 응답 `rptpNm` 을 쓴다(기간 줄·부제 3 포함).
 * 3. 실패하면 **화면 결과**로 문서를 만들어 다운로드는 진행한다 — 서버가 죽었다고 사용자가
 *    방금 본 표를 못 받게 하지 않는다. 이 경로엔 헤더 4칸이 없어 템플릿이 기간 줄·부제 3 을 알아서 생략한다.
 *
 * 저장 실패만 삼키고 **다운로드 실패는 그대로 던진다** — 호출부가 기존 `내보내기 실패` 문구를 띄운다.
 */
export async function exportHotspotReport({
  criteria,
  fileFmt,
  zones,
  weights,
  onSaved,
}: HotspotExportArgs): Promise<HotspotExportStatus> {
  /*
   * 🔴 엑셀은 아래 저장 실패 폴백을 **타지 않는다** — 그래서 try/catch 앞에서 갈라낸다.
   * 재계산에 필요한 후보 구역(`wbk`)은 서버 응답에만 있어, 화면 행으로 만들면 B3 를 바꿨을 때 틀린 결과가
   * 나온다. 저장 실패·`wbk`/`gradeCriteria` 부재는 그대로 던져 호출부가 `내보내기 실패` 를 띄운다.
   * 파일명은 응답 `rptpNm`.
   */
  if (fileFmt === 'XLSX') {
    const res = await createHotspotReport({
      bgngYmd: criteria.bgngYmd,
      endYmd: criteria.endYmd,
      sggNm: criteria.sggNm,
      emdCd: criteria.emdCd,
      fileFmt,
      cvlcptWgvl: criteria.cvlcptWgvl,
      crdnWgvl: criteria.crdnWgvl,
      vrblVls: criteria.vrblVls,
    })
    onSaved?.(res)
    if (!res.wbk || !res.gradeCriteria?.length) {
      throw new Error('저장 응답에 엑셀 데이터(wbk·gradeCriteria)가 없습니다.')
    }
    await downloadHotspotXlsx({ ...res, wbk: res.wbk })
    return 'saved'
  }

  let report: HotspotReportResponse | null = null
  try {
    report = await createHotspotReport({
      bgngYmd: criteria.bgngYmd,
      endYmd: criteria.endYmd,
      sggNm: criteria.sggNm,
      emdCd: criteria.emdCd,
      fileFmt,
      cvlcptWgvl: criteria.cvlcptWgvl,
      crdnWgvl: criteria.crdnWgvl,
      vrblVls: criteria.vrblVls,
    })
  } catch {
    // 네트워크 단절·5xx·400 어느 쪽이든 처리가 같다 — 저장은 포기하고 다운로드는 진행한다.
    report = null
  }

  if (report) onSaved?.(report)

  /*
   * 🔴 0건이면 파일을 만들지 않는다.
   * 호출부(`HotspotResultPanel`)가 0건이면 내보내기 버튼을 잠그고, 그 위의 `HotspotPage` 는 0건이면
   * 패널 자체를 열지 않으므로 실제로는 닿지 않는 경로다. 그래도 **머리글만 있는 빈 표 파일**이
   * 내려가는 것보다는 아무 것도 하지 않는 편이 낫다 — 사용자는 "빈 파일"을 성공으로 오해한다.
   */
  if ((report ? report.zones : zones).length === 0) return report ? 'saved' : 'unsaved'

  /*
   * 저장 성공 경로는 응답을 **그대로** 템플릿에 넘긴다 — 넓은 타입
   * (`HotspotReportResponse.zones: HotspotZone[]`) → 좁은 타입(`HotspotReportDoc.zones` 7칸 `Pick<>`)
   * 대입이 성립하기 때문이다. 문서용으로 옮겨 담으면 칸이 하나 늘 때 조용히 누락된다.
   */
  const html = report
    ? buildHotspotReportHtml(report)
    : buildHotspotHtml(zones, weights, criteria.cvlcptWgvl != null ? 'Y' : 'N')
  const fileBase = report ? report.rptpNm : FALLBACK_FILE_BASE
  if (fileFmt === 'PDF') await exportPdf(html, fileBase)
  else await exportDocx(html, fileBase)

  return report ? 'saved' : 'unsaved'
}
