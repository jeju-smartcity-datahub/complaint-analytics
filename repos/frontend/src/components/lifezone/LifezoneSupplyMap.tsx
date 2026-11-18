import { useMemo } from 'react'
import type {
  LifezoneSupplyCell,
  LifezoneSupplyGradeCriterion,
  LifezoneSupplyGridSz,
} from '../../api/lifezoneSupply'
import GridCellMap, { type GridMapCell, type GridMapLegendRow } from './GridCellMap'
import { GRADE_COLOR_VAR, resolveGradeColor } from './lifezoneGrade'
import { buildSupplyPopupHtml } from './lifezoneSupplyPopup'

/*
 * 생활권 **수급** 격자 지도.
 *
 * Leaflet 셸은 {@link GridCellMap} 이 진다 — 이 파일은 "수급 응답 → 지도가 이해하는 모양"
 * 으로 바꾸는 일만 한다(민원건수 쪽 `LifezoneMap` 과 같은 구조다).
 *
 * **색은 등급 5색**(심각 → 낮음)이다. 민원건수 탭과 같은 시안 램프(`lifezoneGrade.ts`)를
 *    그대로 쓴다 — 새 색표를 만들지 않는다. 지도는 **5단계 전부** 칠한다(보통·낮음 포함 — 시안).
 *    등급은 서버 `grade` 그대로다(프론트에서 다시 계산하지 않는다).
 *    구 사분면 4색(`lifezoneQuadrant.ts`)은 구 스냅샷 보고서 렌더 전용으로 남고 이 지도는 쓰지 않는다.
 * 🔴 **범례 문구는 응답 `lgndTxt` 를 그대로 찍는다**(기본값 기준 `심각 (80% 이상)` …) — 프론트가 조립하지 않는다.
 */

/**
 * 격자 크기별 초기 줌.
 *
 * 수급 격자 목록이 민원건수({@code LifezoneMap})와 같아져 값도 그쪽과 맞췄다.
 * 표를 하나로 합치지 않은 것은 두 유형의 격자 타입이 서버 계약상 따로 있어서다.
 */
const GRID_ZOOM: Record<LifezoneSupplyGridSz, number> = {
  1000: 12.5,
  500: 13,
  250: 14,
  100: 15,
}

const NO_CELLS: LifezoneSupplyCell[] = []
const NO_CRITERIA: LifezoneSupplyGradeCriterion[] = []

/** 목록에서 고른 셀. `seq` 는 같은 셀을 다시 골라도 지도가 반응하게 하는 증가값이다. */
export interface LifezoneSupplyMapFocus {
  gridId: string
  seq: number
}

export interface LifezoneSupplyMapProps {
  cells?: LifezoneSupplyCell[]
  gradeCriteria?: LifezoneSupplyGradeCriterion[]
  gridSz: LifezoneSupplyGridSz
  gridSzNm?: string
  onCellSelect?: (gridId: string) => void
  focus?: LifezoneSupplyMapFocus | null
  showLegend?: boolean
  /** 마지막 "실행" 에 적용된 시군구 코드 — {@link GridCellMap} 으로 그대로 넘긴다. */
  sggCd?: string
  /** 마지막 "실행" 에 적용된 읍면동코드 — 그대로 넘긴다. */
  emdCd?: string
  /** 실행 회차 — 바뀔 때만 지도가 이동한다. `0` = 실행 전. 그대로 넘긴다. */
  regionSeq?: number
}

export default function LifezoneSupplyMap({
  cells = NO_CELLS,
  gradeCriteria = NO_CRITERIA,
  gridSz,
  gridSzNm,
  onCellSelect,
  focus = null,
  showLegend = true,
  sggCd,
  emdCd,
  regionSeq,
}: LifezoneSupplyMapProps) {
  const mapCells = useMemo<GridMapCell[]>(
    () =>
      cells.map((cell) => ({
        key: cell.gridId,
        // 🔴 서버가 EPSG:5179 정방형을 4326 으로 변환해 내린 4점이다. 여기서 만들지 않는다.
        ring: cell.ring,
        ctrLat: cell.ctrLat,
        ctrLot: cell.ctrLot,
        // 라벨은 민원 건수다 — 값의 주축이고, 공급은 팝업에서 본다.
        label: String(cell.cvlcptCnt),
        fillColor: resolveGradeColor(cell.grade),
        popupHtml: buildSupplyPopupHtml(cell),
      })),
    [cells],
  )

  const legend = useMemo<GridMapLegendRow[]>(
    () =>
      gradeCriteria.map((c) => ({
        key: c.grade,
        colorVar: GRADE_COLOR_VAR[c.grade],
        // 🔴 서버가 완성한 범례 문구를 그대로 쓴다 (기본값 기준 `심각 (80% 이상)` … `낮음 (20% 미만)`).
        text: c.lgndTxt,
      })),
    [gradeCriteria],
  )

  return (
    <GridCellMap
      cells={mapCells}
      legend={legend}
      /*
       * 🔴 "이번 조회 기준" 을 제목에 박는다. 등급 경계는 부족률 **고정 구간**이 됐지만(경계·최소 민원의
       * 정본은 설정 표 `tb_anls_vrbl_stng` SPLY_*), 부족률·평가 대상(월평균 최소 민원 이상 격자)은
       * **이 조회의 기간·지역·격자크기로 산출**된다 — 조건을 바꾸면
       * 같은 격자의 등급도 바뀐다(민원건수 지도와 같은 제목을 쓴다).
       */
      legendTitle="범례 (이번 조회 기준)"
      zoom={GRID_ZOOM[gridSz]}
      gridSzNm={gridSzNm}
      onCellSelect={onCellSelect}
      focus={focus ? { key: focus.gridId, seq: focus.seq } : null}
      showLegend={showLegend}
      sggCd={sggCd}
      emdCd={emdCd}
      regionSeq={regionSeq}
    />
  )
}
