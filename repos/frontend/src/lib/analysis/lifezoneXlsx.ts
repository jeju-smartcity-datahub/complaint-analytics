import { formatDtl, type LifezoneGrade } from '../../api/lifezone'
import { GRADE_LABEL } from '../../components/lifezone/lifezoneGrade'
import { GRADE_BASIS_TITLE } from '../report/reportTemplate'
import {
  EMPTY_RANKING_TEXT,
  gradeBasisLines,
  type LifezoneReportDoc,
} from './lifezoneReportTemplate'
import {
  createWorkbook,
  downloadXlsx,
  finishWorkbook,
  NOTE_FONT,
  styleHeader,
} from './xlsxKit'

/*
 * 생활권 **민원건수** 엑셀(.xlsx) 생성기 (TSK-4340 Step 6 — 계획서 3-3 · D1).
 *
 * 🔴 **정적 엑셀이다** — 입력 셀·수식·`wbk` 가 없다. 화면에도 계수 조정이 없어(`SCREEN_SCOPES.LIFEZONE` 에 민원건수 스코프 없음)
 *    엑셀에만 계수를 만들면 화면에서 재현할 수 없다(D1). 저장 응답·스냅샷(`LifezoneReportDoc`)의 요약·순위표를 값으로 싣는다.
 * 🔴 `단계 구분 기준` 문구는 PDF 와 **같은 함수**(`gradeBasisLines`)로 만든다 — 범례가 없으면 그 절을 통째로 뺀다(PDF 와 같다).
 *    구 보고서(`clsfMthd` 가 `JENKS` 등)도 스냅샷 값 그대로 싣는다(D7-1).
 */

const SHEET = '민원건수 분석'
/** PDF `DOC_TITLE` 과 같은 문구. */
const TITLE = '생활권 시뮬레이션 분석 결과'
const SUMMARY_TITLE = '분석 결과 요약'
const TOTAL_LABEL = '총 분석 격자'
const HEADERS = ['순위', '구역', '민원 건수', '등급', '세부 내역']
const WIDTHS = [8, 28, 12, 10, 50]
/** 요약 등급 순서 폴백 — `gradeCriteria` 가 비었을 때(PDF `FALLBACK_GRADE_ORDER` 와 같은 순서). */
const FALLBACK_GRADE_ORDER: LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION', 'NORMAL', 'LOW']

/** 생성기 입력 — 저장 응답·스냅샷 공용(`LifezoneReportDoc` 그대로). 저장 실패 폴백(화면 결과)도 이 모양이다(D7-2). */
export type LifezoneXlsxSource = LifezoneReportDoc

/** 요약 6행 — PDF `summaryRows` 와 같은 순서·라벨(`총 분석 격자` + `gradeCriteria` 순 5행). */
function summaryRows(doc: LifezoneReportDoc): [string, number][] {
  const cntOf: Record<LifezoneGrade, number> = {
    SEVERE: doc.severeCnt,
    WARN: doc.warnCnt,
    CAUTION: doc.cautionCnt,
    NORMAL: doc.normalCnt,
    LOW: doc.lowCnt,
  }
  const criteria =
    doc.gradeCriteria.length > 0
      ? doc.gradeCriteria
      : FALLBACK_GRADE_ORDER.map((grade) => ({ grade, gradeNm: GRADE_LABEL[grade] }))
  return [
    [TOTAL_LABEL, doc.totGridCnt],
    ...criteria.map((c): [string, number] => [c.gradeNm || GRADE_LABEL[c.grade], cntOf[c.grade]]),
  ]
}

/** 순위 행의 등급명 — `gradeCriteria` 에서 코드로 찾고, 없으면 `GRADE_LABEL`(PDF `gradeNmOf` + `gradeBadge` 와 같다). */
const gradeNmOf = (doc: LifezoneReportDoc, grade: LifezoneGrade): string =>
  doc.gradeCriteria.find((c) => c.grade === grade)?.gradeNm || GRADE_LABEL[grade]

/** 조건 줄 — 분석 기간 · 분석 지역 · 격자 크기. 헤더가 없으면(저장 실패 폴백) 그 칸만 뺀다. */
function conditionText(doc: LifezoneReportDoc): string {
  return [
    doc.anlsPrdExpln ? `분석 기간 : ${doc.anlsPrdExpln}` : null,
    doc.regionNm ? `분석 지역 : ${doc.regionNm}` : null,
    `격자 크기 : ${doc.gridSzNm}`,
  ]
    .filter(Boolean)
    .join(' · ')
}

/** 통합문서(.xlsx) 바이트 — 시트 1장, 전부 값, 시트 보호. */
export async function buildLifezoneWorkbook(doc: LifezoneXlsxSource): Promise<ArrayBuffer> {
  const wb = await createWorkbook()
  const ws = wb.addWorksheet(SHEET)
  WIDTHS.forEach((w, i) => (ws.getColumn(i + 1).width = w))

  ws.getCell('A1').value = TITLE
  ws.getCell('A1').font = { bold: true, size: 14 }
  ws.getCell('A2').value = conditionText(doc)

  let row = 4
  const basis = gradeBasisLines(doc)
  if (basis.length > 0) {
    ws.getCell(`A${row}`).value = GRADE_BASIS_TITLE
    ws.getCell(`A${row}`).font = { bold: true }
    basis.forEach((line) => {
      row++
      ws.getCell(`A${row}`).value = line
      ws.getCell(`A${row}`).font = NOTE_FONT
    })
    row += 2
  }

  ws.getCell(`A${row}`).value = SUMMARY_TITLE
  ws.getCell(`A${row}`).font = { bold: true }
  summaryRows(doc).forEach(([label, cnt]) => {
    row++
    ws.getCell(`A${row}`).value = label
    ws.getCell(`B${row}`).value = cnt
    ws.getCell(`B${row}`).numFmt = '#,##0"개"'
  })
  row += 2

  HEADERS.forEach((h, i) => styleHeader(ws.getCell(row, i + 1), h))
  if (doc.ranking.length === 0) {
    row++
    ws.getCell(`A${row}`).value = EMPTY_RANKING_TEXT
  }
  doc.ranking.forEach((r) => {
    row++
    ws.getCell(`A${row}`).value = r.rnk
    ws.getCell(`B${row}`).value = r.cntrPoiNm
    ws.getCell(`C${row}`).value = r.anlsVl
    ws.getCell(`C${row}`).numFmt = `#,##0"${doc.unit.replace(/"/g, '')}"`
    ws.getCell(`D${row}`).value = gradeNmOf(doc, r.grade)
    ws.getCell(`E${row}`).value = formatDtl(r.dtl)
  })

  return finishWorkbook(wb, [ws])
}

/** 엑셀을 만들어 `{fileBase}.xlsx` 로 내려받는다(D10 — 파일명은 호출부가 응답 `rptpNm`·탭 폴백으로 정한다). */
export async function downloadLifezoneXlsx(doc: LifezoneXlsxSource, fileBase: string): Promise<void> {
  downloadXlsx(await buildLifezoneWorkbook(doc), fileBase)
}
