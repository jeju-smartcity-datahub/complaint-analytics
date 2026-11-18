import { useMemo } from 'react'
import type {
  LifezoneCell,
  LifezoneDtl,
  LifezoneGradeCriterion,
  LifezoneGridSz,
  LifezoneRank,
} from '../../api/lifezone'
import GridCellMap, { type GridMapCell, type GridMapLegendRow } from './GridCellMap'
import { GRADE_COLOR_VAR, GRADE_LABEL, resolveGradeColor } from './lifezoneGrade'
import { buildCellPopupHtml } from './lifezoneCellPopup'

/*
 * 생활권 **민원건수** 격자 히트맵 (TSK-4088 Step 9).
 *
 * 🔴 2026-09-13(TSK-4089) 에 Leaflet 셸을 {@link GridCellMap} 으로 꺼냈다 — 수급 탭이 같은
 * 지도를 쓰면서 색·팝업만 달리하기 때문이다. **이 파일의 공개 props 는 그대로**이고,
 * 하는 일은 "민원건수 응답 → 지도가 이해하는 모양" 으로 바꾸는 것 하나다.
 * 지도 초기화·가림 보정·팝업 autoPan 우회 같은 까다로운 코드는 전부 그쪽에 있다.
 */

/** 격자 크기별 초기 줌 — 잘게 쪼갤수록 가까이 붙는다. */
const GRID_ZOOM: Record<LifezoneGridSz, number> = {
  1000: 12.5,
  500: 13,
  250: 14,
  100: 15,
}

const NO_CELLS: LifezoneCell[] = []
const NO_RANKING: LifezoneRank[] = []
const NO_CRITERIA: LifezoneGradeCriterion[] = []
const NO_DTL: LifezoneDtl[] = []

/** 목록에서 고른 셀. `seq` 는 같은 셀을 다시 골라도 지도가 반응하게 하는 증가값이다. */
export interface LifezoneMapFocus {
  gridCd: string
  seq: number
}

export interface LifezoneMapProps {
  cells?: LifezoneCell[]
  ranking?: LifezoneRank[]
  gradeCriteria?: LifezoneGradeCriterion[]
  unit?: string
  gridSz: LifezoneGridSz
  gridSzNm?: string
  onCellSelect?: (gridCd: string) => void
  focus?: LifezoneMapFocus | null
  showLegend?: boolean
  /** 마지막 "실행" 에 적용된 시군구 코드 — {@link GridCellMap} 으로 그대로 넘긴다(TSK-4227 R17 · D18). */
  sggCd?: string
  /** 마지막 "실행" 에 적용된 읍면동코드 — 그대로 넘긴다(TSK-4227 R17 · D18). */
  emdCd?: string
  /** 실행 회차 — 바뀔 때만 지도가 이동한다. `0` = 실행 전(TSK-4227 R24 · D18). 그대로 넘긴다. */
  regionSeq?: number
  /**
   * 값 포매터(TSK-4090 — 혼잡도 `÷100` 점수). 셀 라벨과 팝업 값에 쓴다. 없으면 정수 그대로다(민원건수).
   */
  formatVl?: (vl: number) => string
  /**
   * 셀별 추가 표현(TSK-4090 — 혼잡도 팝업 설명 줄 · `dimmed` 는 범용 슬롯으로 현재 미사용). 페이지가 혼잡도일 때만 넘긴다.
   * 🔴 인자 타입이 `LifezoneCell` 이다 — 혼잡도 셀을 받는 함수를 그대로 넘기면 strictFunctionTypes 에
   * 걸린다. 호출부가 `isCngstCell` 로 좁혀 쓴다.
   */
  cellExtra?: (cell: LifezoneCell) => { dimmed?: boolean; lines?: string[] }
  /**
   * 조회 기간 개월수(TSK-4227 R6). **민원건수일 때만** 넘긴다 — 팝업 값이 `52건/3개월` 이 된다.
   * 🔴 응답 `bgngYmd`~`endYmd` 에서 계산한 값이다(조회 뒤 설정 패널 기간을 바꿔도 지도는 지난 조회 결과다).
   */
  months?: number
  /**
   * 혼잡도 결과인지(TSK-4227 R19). 참이면 셀 팝업 값 앞에 `혼잡 지수 ` 를 붙인다(시안 `혼잡 지수 100.6점`).
   * 민원건수 팝업은 무변경이다.
   */
  isCngst?: boolean
}

/** 혼잡도 팝업 값 앞 문구 — 시안 `<div class="gp__big">혼잡 지수 ${e}점</div>` (TSK-4227 R19). */
const CNGST_BIG_PREFIX = '혼잡 지수 '

export default function LifezoneMap({
  cells = NO_CELLS,
  ranking = NO_RANKING,
  gradeCriteria = NO_CRITERIA,
  unit = '',
  gridSz,
  gridSzNm,
  onCellSelect,
  focus = null,
  showLegend = true,
  sggCd,
  emdCd,
  regionSeq,
  formatVl,
  cellExtra,
  months,
  isCngst = false,
}: LifezoneMapProps) {
  /*
   * 셀 → 지도 셀. 세부 내역(`dtl`)과 POI 구역명은 **순위 행에만** 붙어 오므로 여기서 합친다
   * (순위 밖 셀은 폴백 이름과 빈 내역으로 그린다).
   */
  const mapCells = useMemo<GridMapCell[]>(() => {
    const gradeNmOf = new Map(gradeCriteria.map((c) => [c.grade, c.gradeNm]))
    const dtlOf = new Map(ranking.map((r) => [r.gridCd, r.dtl]))
    const nmOf = new Map(ranking.map((r) => [r.gridCd, r.cntrPoiNm]))
    return cells.map((cell) => {
      const extra = cellExtra?.(cell)
      const vlText = formatVl ? formatVl(cell.anlsVl) : undefined
      return {
        key: cell.gridCd,
        ring: cell.ring,
        ctrLat: cell.ctrLat,
        ctrLot: cell.ctrLot,
        label: vlText ?? String(cell.anlsVl),
        fillColor: resolveGradeColor(cell.grade),
        dimmed: extra?.dimmed,
        popupHtml: buildCellPopupHtml({
          grade: cell.grade,
          gradeNm: gradeNmOf.get(cell.grade) ?? GRADE_LABEL[cell.grade],
          cntrPoiNm: nmOf.get(cell.gridCd) ?? cell.cntrPoiNm,
          anlsVl: cell.anlsVl,
          unit,
          dtl: dtlOf.get(cell.gridCd) ?? NO_DTL,
          vlText,
          lines: extra?.lines,
          months,
          bigPrefix: isCngst ? CNGST_BIG_PREFIX : undefined,
        }),
      }
    })
  }, [cells, ranking, gradeCriteria, unit, formatVl, cellExtra, months, isCngst])

  /** 범례 행. 응답 순서(`SEVERE`→`LOW`)를 그대로 쓰고 프론트에서 정렬·조립하지 않는다. */
  const legend = useMemo<GridMapLegendRow[]>(
    () =>
      gradeCriteria.map((c) => ({
        key: c.grade,
        colorVar: GRADE_COLOR_VAR[c.grade],
        // 🔴 응답 `lgndTxt` 를 그대로 찍는다 — 문구를 프론트에서 조립하지 않는다.
        text: c.lgndTxt,
      })),
    [gradeCriteria],
  )

  return (
    <GridCellMap
      cells={mapCells}
      legend={legend}
      /*
       * 🔴 제목에 **"이번 조회 기준"** 을 박는다(R11). 등급 경계는 고정 임계값이 아니라 조회
       * 결과에서 매번 다시 산출되므로(D17), 같은 색이라도 다른 조회의 색과 비교하면 틀린 결론이
       * 나온다. 그 사실을 알릴 곳이 이 화면에는 범례뿐이다.
       */
      legendTitle="범례 (이번 조회 기준)"
      zoom={GRID_ZOOM[gridSz]}
      gridSzNm={gridSzNm}
      onCellSelect={onCellSelect}
      focus={focus ? { key: focus.gridCd, seq: focus.seq } : null}
      showLegend={showLegend}
      sggCd={sggCd}
      emdCd={emdCd}
      regionSeq={regionSeq}
    />
  )
}
