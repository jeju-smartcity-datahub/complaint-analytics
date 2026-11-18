import type { LifezoneCngstCell, LifezoneCngstWorkbook } from '../../api/lifezone'
import { formatCngstSupply, formatCngstVl } from '../../components/lifezone/lifezoneGrade'
import type { AnlsVrblAjmt } from '../../api/analysisWeight'
import { changedSettingsBlockHtml, changedSettingsBoxOf } from '../report/reportTemplate'
import { renderLifezoneReportHtml, type LifezoneReportDoc } from './lifezoneReportTemplate'

/*
 * 생활권 **혼잡도** 보고서 본문. 미리보기·PDF·DOCX 공용이다.
 *
 * 구조·서식은 민원건수 보고서와 같고 값·세부 내역·기준 문구 3칸만 다르다(`renderLifezoneReportHtml`).
 *
 * 🔴 `anlsVl` 은 점수 × 100 이다 — `toLocaleString()` 을 쓰면 `159.7점` 이 `15,973점` 이 된다.
 * 🔴 "주차 혼잡이며 실측 점유율이 아니다" 는 예외 승인 조건이라 문서에서 숨기지 않는다.
 * 🔴 경계·제외 수·점유율은 스냅샷 값이다 — 설정이 바뀌어도 옛 보고서는 생성 당시 기준으로 그려진다.
 */

/** 문서가 쓰는 칸 — 민원건수 문서 + 혼잡도 안내값. 칸이 곧 스냅샷 API 의 계약이다. */
export interface LifezoneCngstReportDoc extends LifezoneReportDoc {
  /** 이번 계산에 쓴 기준 점유율(설정값). */
  occUsedVl: number
  /** 불법주차 근거가 없어 제외된 격자 수. */
  noEnfGridCnt: number
  /** 면수 하한 미만으로 제외된 격자 수. */
  refGridCnt: number
  /** 순위 행 — 세부 내역의 셀을 찾으려고 `gridCd` 를 더 요구한다. */
  ranking: (LifezoneReportDoc['ranking'][number] & { gridCd: string })[]
  /**
   * 세부 내역 `공급 N면 (N개소) · 초과주차 추정 N대` 의 재료(화면 순위 행과 같은 문구).
   * 스냅샷·POST 응답 모두 분석 응답과 같은 모양이라 `cells` 가 온다. 없거나 셀을 못 찾으면 빈칸이다.
   */
  cells?: Pick<LifezoneCngstCell, 'gridCd' | 'pkltCpctySum' | 'pkltCnt' | 'excsPrkPrsmpVl'>[]
  /** 🆕 엑셀 재계산 데이터 — 신규 스냅샷·저장 응답에만 있다. 구 스냅샷은 없음/`null`. 문서(PDF·DOCX)는 쓰지 않는다 */
  wbk?: LifezoneCngstWorkbook | null
  /**
   * 혼잡도 계수의 적용 내역 → `적용 설정` 상자(숨김 계수 제외). 구 스냅샷 ①은 `null`/없음이라 상자가 없다.
   * 🔴 점유율 예외 문장(`occUsedVl`)은 숨김 계수지만 예외 승인 조건이라 본문에 그대로 남긴다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
}

/** `단계 구분 기준` 상자의 줄. 범례가 없으면 경계 없이 일반 문구만 남기지 않는다. */
function cngstBasisLines(doc: LifezoneCngstReportDoc): string[] {
  const legends = doc.gradeCriteria.map((c) => c.lgndTxt).filter(Boolean)
  if (legends.length === 0) return []
  return [
    `격자(${doc.gridSzNm}) 단위로 조회 조건(기간·지역) 안의 공영주차 면수와 단속·민원 건수로 혼잡 점수를 계산합니다.`,
    `등급 : ${legends.join(' · ')}`,
    '혼잡 점수는 공영주차 공급 대비 추정 주차 수요를 나타내는 혼잡 지수의 100배입니다. 100점을 넘으면 수요가 면수를 넘는 곳입니다.',
    '등급은 혼잡 지수 1.0(100점)을 기준으로 한 경계이며, 이 보고서를 만들 때의 설정값입니다.',
    `점유율 실측값이 없어 설정값(${Number(doc.occUsedVl).toFixed(2)})을 사용하므로 격자 간 상대 비교에만 쓰십시오.`,
    `불법주차 근거가 없는 ${doc.noEnfGridCnt.toLocaleString()}칸과 면수 하한 미만 ${doc.refGridCnt.toLocaleString()}칸은 분석에서 제외했습니다.`,
    '표에는 심각·경고·주의 등급만 싣습니다.',
  ]
}

export function buildLifezoneCngstReportHtml(doc: LifezoneCngstReportDoc): string {
  const cellByGrid = new Map((doc.cells ?? []).map((c) => [c.gridCd, c]))
  // 템플릿이 넘기는 행은 `doc.ranking` 의 원소 그대로라 객체로 찾는다(그 타입엔 `gridCd` 가 없다).
  const cellByRank = new Map<
    LifezoneReportDoc['ranking'][number],
    NonNullable<LifezoneCngstReportDoc['cells']>[number]
  >()
  for (const r of doc.ranking) {
    const cell = cellByGrid.get(r.gridCd)
    if (cell) cellByRank.set(r, cell)
  }
  return renderLifezoneReportHtml(doc, {
    formatVl: (rank) => formatCngstVl(rank.anlsVl),
    formatDetail: (rank) => {
      const cell = cellByRank.get(rank)
      return cell ? formatCngstSupply(cell) : ''
    },
    basisLines: cngstBasisLines(doc),
    settingsHtml: changedSettingsBlockHtml(changedSettingsBoxOf(doc.vrblAjmt, 'LIFEZONE_CNGST')),
  })
}
