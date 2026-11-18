import { useEffect, useRef, useState, type CSSProperties } from 'react'
import 'leaflet/dist/leaflet.css'
import * as L from 'leaflet'
import MapTypeSwitch from '../common/MapTypeSwitch'
import { MAP_KEY, fixLeafletAnchors, createBasemapController, LIST_FOCUS_ZOOM, focusZoom } from '../common/mapTiles'
import type { BaseMapKind, BasemapController } from '../common/mapTiles'
import { fetchAdminDistricts } from '../../api/complaints'

/*
 * 격자 셀 지도의 **표현 계층**.
 *
 * 🔴 **분석유형을 모른다.** 등급·사분면·임계값·단위를 이 파일에 적지 않는다 — 받은 셀을
 * 받은 색으로 그리고, 받은 HTML 을 팝업에 띄우고, 받은 범례 행을 찍을 뿐이다.
 *
 * 생활권 화면의 분석유형 두 종(민원건수 = 순서형 5등급 · 수급 = 범주형 4사분면)은 셀에 붙는
 * 지표도 색의 의미도 다르지만, **지도 자체가 하는 일은 같다** — 폴리곤·라벨·팝업·범례를 그리고,
 * 목록에서 고른 셀로 패널에 가리지 않게 이동한다. 그 공통분을 여기 한 곳에 둔다
 * ({@code LifezoneMap} · {@code LifezoneSupplyMap} 이 각자 자기 데이터를 이 모양으로 바꿔 넘긴다).
 * 🔁 2026-10-19 개정 — 목록 → 셀 이동이 종전 이동만 하던 것을 공용 상수(mapTiles)의
 * 생활권 키 값까지 확대하도록 바꿨다(확대만 — 이미 더 들어가 있으면 축소하지 않는다).
 *
 * 이렇게 나누지 않으면 Leaflet 초기화·가림 보정·팝업 autoPan 우회 같은 **까다로운 코드가 두 벌**이
 * 되고, 그중 한쪽만 고쳐지는 일이 반드시 생긴다.
 */

/** 지도에 그릴 셀 1칸 — 색과 문구는 **이미 정해진 채로** 온다. */
export interface GridMapCell {
  /** 셀 식별자. 선택·포커스의 키다(민원건수는 `gridCd`, 수급은 `gridId`) */
  key: string
  /** 꼭짓점 링 `[[위도, 경도], …]`. 🔴 서버가 내린 값을 그대로 쓴다 */
  ring: [number, number][]
  /** 중심 위도 — 라벨을 놓는 자리 */
  ctrLat: number
  /** 중심 경도 */
  ctrLot: number
  /** 셀 위에 찍는 짧은 라벨(보통 값 하나) */
  label: string
  /** 채움색. CSS 변수가 아니라 **이미 해석된 색 문자열**이어야 한다(Leaflet 은 변수를 못 읽는다) */
  fillColor: string
  /** 팝업 HTML. 🔴 조립·이스케이프는 호출부 책임이다 */
  popupHtml: string
  /**
   * 흐리게 그릴지. 색은 그대로 두고 채움 투명도만 낮추는 **범용** 표현이다.
   * ⚠️ 현재 넘기는 호출부가 없다 — 혼잡도 참고값(`refYn='Y'`)용이었으나 2026-10-08 그 칸이 응답에서
   * 제외돼 도달하지 않는다. 다시 "참고만 하라" 칸이 생기면 여기로 넘긴다.
   */
  dimmed?: boolean
}

/** 기본 채움 투명도 · 흐리게(`dimmed`) 투명도. */
const FILL_OPACITY = 0.58
const FILL_OPACITY_DIMMED = 0.22

/** 범례 1행. */
export interface GridMapLegendRow {
  key: string
  /** 점 색 — 여기는 CSS 변수를 써도 된다(DOM 이 해석한다) */
  colorVar: string
  /** 문구. 🔴 서버가 만든 것을 그대로 찍는다 — 프론트에서 조립하지 않는다 */
  text: string
}

/** 목록에서 셀을 고를 때 쓰는 포커스 신호. `seq` 는 같은 셀을 다시 골라도 반응하게 하는 증가값이다. */
export interface GridMapFocus {
  key: string
  seq: number
}

export interface GridCellMapProps {
  cells?: GridMapCell[]
  legend?: GridMapLegendRow[]
  /** 범례 제목 — 유형마다 다르다 */
  legendTitle?: string
  /** 초기·격자 변경 시 적용할 줌 레벨 */
  zoom: number
  /** 격자 크기 표시명 (`500m`). 범례 아래 칩에 쓴다 */
  gridSzNm?: string
  onCellSelect?: (key: string) => void
  focus?: GridMapFocus | null
  showLegend?: boolean
  /**
   * 마지막 "실행" 에 적용된 시군구 코드(5자리 — 그 시군구 읍면동코드 앞 5자리).
   * 🔴 설정 패널 선택값이 아니다 — 페이지가 실행 때 확정한 값이다. 이 값이 바뀌는 것만으로는 이동하지 않고
   * {@link regionSeq} 가 바뀔 때 읽힌다. `emdCd` 가 있으면 무시된다.
   */
  sggCd?: string
  /** 마지막 "실행" 에 적용된 읍면동코드(8자리). 있으면 그 동 경계로 이동한다. */
  emdCd?: string
  /**
   * 실행 회차. **이 값이 바뀔 때만** 지역 이동을 한다. `0`(기본) = 실행 전이라 이동하지 않는다.
   * 같은 지역으로 다시 실행해도 값이 올라 다시 맞춘다.
   */
  regionSeq?: number
}

const JEJU_CENTER: L.LatLngExpression = [33.483, 126.512]

const LEFT_PANEL_SELECTOR = '.sim-panel--left'
const RIGHT_PANEL_SELECTOR = '.sim-result'

/** 좌우 패널이 지도를 덮은 폭(px). 셀을 "보이는 영역" 가운데로 보내는 데 쓴다. */
interface Occlusion {
  left: number
  right: number
}

const NO_OCCLUSION: Occlusion = { left: 0, right: 0 }

/** 지역 경계로 `fitBounds` 할 때 패널 가림 위에 더하는 여백(px) — HotspotMap `FIT_GAP` 과 같은 역할. */
const REGION_FIT_GAP = 24

/** 패널을 뺀 가시 폭이 이보다 좁으면 보정을 포기한다 — 좁은 화면에서 오히려 화면 밖으로 민다. */
const MIN_VISIBLE_WIDTH = 120

function measureOcclusion(container: HTMLElement | null): Occlusion {
  const stage = container?.parentElement
  if (!container || !stage) return NO_OCCLUSION
  const box = container.getBoundingClientRect()
  if (box.width <= 0) return NO_OCCLUSION
  const cover = (selector: string, fromRight: boolean): number => {
    const rect = stage.querySelector(selector)?.getBoundingClientRect()
    if (!rect || rect.width <= 0) return 0
    const covered = fromRight ? box.right - rect.left : rect.right - box.left
    return Math.min(Math.max(covered, 0), box.width)
  }
  const left = cover(LEFT_PANEL_SELECTOR, false)
  const right = cover(RIGHT_PANEL_SELECTOR, true)
  if (box.width - left - right < MIN_VISIBLE_WIDTH) return NO_OCCLUSION
  return { left, right }
}

/** 패널에 가리지 않는 중앙으로 목표 좌표를 옮긴다(픽셀 기준 보정이라 투영을 거친다). */
function centerFor(map: L.Map, latlng: L.LatLng, zoom: number, occ: Occlusion): L.LatLng {
  const dx = (occ.left - occ.right) / 2
  if (dx === 0) return latlng
  return map.unproject(map.project(latlng, zoom).subtract([dx, 0]), zoom)
}

const MAP_STYLE: CSSProperties = { width: '100%', height: '100%' }
const NO_KEY_STYLE: CSSProperties = {
  ...MAP_STYLE,
  display: 'grid',
  placeItems: 'center',
  background: 'var(--cool-neutral-99)',
  color: 'var(--text-alternative)',
  fontSize: 14,
}

const NO_CELLS: GridMapCell[] = []
const NO_LEGEND: GridMapLegendRow[] = []

export default function GridCellMap({
  cells = NO_CELLS,
  legend = NO_LEGEND,
  legendTitle = '범례 (이번 조회 기준)',
  zoom,
  gridSzNm,
  onCellSelect,
  focus = null,
  showLegend = true,
  sggCd = '',
  emdCd = '',
  regionSeq = 0,
}: GridCellMapProps) {
  const elRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const cellLayerRef = useRef<L.LayerGroup | null>(null)
  const labelLayerRef = useRef<L.LayerGroup | null>(null)
  const basemapRef = useRef<BasemapController | null>(null)
  const [baseMap, setBaseMap] = useState<BaseMapKind>('base')
  const cellsRef = useRef(new Map<string, L.Polygon>())

  // 콜백·줌은 ref 로 들고 있는다 — 지도 생성 effect 를 이 값들 때문에 다시 돌리지 않기 위해서다.
  const cellSelectRef = useRef(onCellSelect)
  cellSelectRef.current = onCellSelect
  const zoomRef = useRef(zoom)
  zoomRef.current = zoom
  // 이동 대상 지역도 ref 로 — 이동 effect 는 `regionSeq` 만 보고 돈다. 지역값 변화로는 돌지 않는다.
  const regionRef = useRef({ sggCd, emdCd })
  regionRef.current = { sggCd, emdCd }

  // 지도 1회 생성·정리
  useEffect(() => {
    const cellIndex = cellsRef.current
    if (!MAP_KEY) return
    const el = elRef.current
    if (!el) return
    const map = L.map(el, {
      center: JEJU_CENTER,
      zoom: zoomRef.current,
      zoomControl: false, // 기본 topleft 대신 시안과 같은 topright 로 다시 붙인다
      zoomSnap: 0.5,
    })
    L.control.zoom({ position: 'topright' }).addTo(map)
    fixLeafletAnchors(map)
    basemapRef.current = createBasemapController(map)
    mapRef.current = map
    return () => {
      map.remove()
      mapRef.current = null
      basemapRef.current = null
      cellLayerRef.current = null
      labelLayerRef.current = null
      cellIndex.clear()
    }
  }, [])

  useEffect(() => {
    basemapRef.current?.apply(baseMap)
  }, [baseMap])

  useEffect(() => {
    mapRef.current?.setZoom(zoom)
  }, [zoom])

  /*
   * "실행" → 그 실행에 적용된 지역으로 지도 이동 ("선택 즉시 이동" 을 개정).
   *
   * 🔴 **`regionSeq` 가 바뀔 때만** 돈다 — 설정 패널에서 지역을 고르기만 해서는 움직이지 않는다.
   *    의존성에 `cells`·`sggCd`·`emdCd` 를 넣지 않는다(지역은 `regionRef` 로 읽는다). 결과가 올 때마다
   *    다시 맞추면 사용자가 움직여 둔 지도가 되돌아간다(HotspotMap 주석의 같은 함정).
   * - `regionSeq === 0`(실행 전) → 이동하지 않는다. 지도는 생성 옵션의 기본 시야 그대로다.
   * - ⚠️ 재마운트(민원·혼잡 ↔ 수급은 다른 컴포넌트라 실행 직후·결과 도착 때 갈아끼워진다) — 새 지도는
   *   `JEJU_CENTER` 에서 시작하고 이 effect 가 마운트 때 한 번 돈다. `seq > 0` 이면 **마지막 실행 지역으로
   *   한 번** 맞춘다 — 의도된 동작이다(그 실행이 보려던 지역). "처리한 seq" ref 를 두지 않는 이유가 이것이며,
   *   ref 를 둬도 컴포넌트 로컬이라 재마운트 때 초기화돼 결과가 같다. 같은 컴포넌트가 유지되는 동안에는
   *   seq 가 그대로면 재렌더로 다시 돌지 않는다.
   * - 읍면동 → 그 동 경계 · 시군구만 → 그 시군구 소속 동 경계 합(`sggCd` 필터) · 둘 다 없음 → 기본 시야.
   * - 경계 polygon 은 **그리지 않는다**(요청은 "이동" — 그리면 격자와 겹친다). bounds 계산에만 쓴다.
   * - 좌우 패널 가림은 `paddingTopLeft`·`paddingBottomRight` 로 빼서 **보이는 영역 중앙**에 맞춘다.
   * - 실행을 연달아 누르면 늦게 온 응답이 덮지 않게 `cancelled`(ZoneRcmdtnMap 선례). 실패는 조용히 넘긴다.
   */
  useEffect(() => {
    if (regionSeq === 0) return
    const map = mapRef.current
    if (!map) return
    const { sggCd, emdCd } = regionRef.current
    if (!emdCd && !sggCd) {
      // "전체" = 진입 직후와 **같은** 시야(지도 생성 옵션과 동일 — 가림 보정을 더하지 않는다).
      map.setView(JEJU_CENTER, zoomRef.current)
      return
    }
    let cancelled = false
    fetchAdminDistricts(emdCd ? { emdCd } : { sggCd })
      .then((fc) => {
        const target = mapRef.current
        if (cancelled || !target || fc.features.length === 0) return
        // 좌표 순서([경도,위도] → LatLng) 변환은 L.geoJSON 에 맡긴다 — 지도에 add 하지 않는다.
        const bounds = L.geoJSON(fc as unknown as GeoJSON.FeatureCollection).getBounds()
        if (!bounds.isValid()) return
        const occ = measureOcclusion(elRef.current)
        target.fitBounds(bounds, {
          paddingTopLeft: [occ.left + REGION_FIT_GAP, REGION_FIT_GAP],
          paddingBottomRight: [occ.right + REGION_FIT_GAP, REGION_FIT_GAP],
        })
      })
      .catch(() => {
        // 지도 이동은 보조 동작이다 — 경계를 못 받아도 조회·결과는 그대로 쓸 수 있다.
      })
    return () => {
      cancelled = true
    }
  }, [regionSeq])

  // 셀 레이어 — 목록이 바뀌면 통째로 다시 만든다(부분 갱신은 선택 상태가 어긋난다)
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    if (cellLayerRef.current) map.removeLayer(cellLayerRef.current)
    if (labelLayerRef.current) map.removeLayer(labelLayerRef.current)
    cellLayerRef.current = null
    labelLayerRef.current = null
    cellsRef.current.clear()
    if (cells.length === 0) return

    const cellLayer = L.layerGroup()
    const labelLayer = L.layerGroup()
    cells.forEach((cell) => {
      const polygon = L.polygon(cell.ring, {
        color: '#fff',
        weight: 1,
        fillColor: cell.fillColor,
        fillOpacity: cell.dimmed ? FILL_OPACITY_DIMMED : FILL_OPACITY,
      })
      polygon.bindPopup(cell.popupHtml, { closeButton: false, offset: [0, -2] })
      polygon.on('mouseover', () => polygon.openPopup())
      polygon.on('click', () => cellSelectRef.current?.(cell.key))
      cellLayer.addLayer(polygon)
      cellsRef.current.set(cell.key, polygon)

      labelLayer.addLayer(
        L.marker([cell.ctrLat, cell.ctrLot], {
          interactive: false,
          keyboard: false,
          icon: L.divIcon({ className: 'gcell-lab', iconSize: [40, 16], html: cell.label }),
        }),
      )
    })
    cellLayer.addTo(map)
    labelLayer.addTo(map)
    cellLayerRef.current = cellLayer
    labelLayerRef.current = labelLayer
  }, [cells])

  // 목록에서 고른 셀로 이동 + 팝업 열기
  useEffect(() => {
    const map = mapRef.current
    if (!map || !focus) return
    const polygon = cellsRef.current.get(focus.key)
    if (!polygon) return
    // 2026-10-19 — 리스트 클릭에서만 · 확대만. 🔴 보정은 현재가 아니라 **목표 zoom** 으로 계산한다.
    const targetZoom = focusZoom(map, LIST_FOCUS_ZOOM.lifezone) // 🔴 prop `zoom` 과 이름을 겹치지 않는다
    const target = centerFor(map, polygon.getCenter(), targetZoom, measureOcclusion(elRef.current)) // 🔴 목표 zoom
    map.setView(target, targetZoom, { animate: true, duration: 0.5 }) // duration 은 같은 zoom(panBy) 경로에 그대로 전달된다

    /*
     * 🔴 이 경로에서만 팝업 `autoPan` 을 끈다 — 켜 두면 방금 계산한 중앙이 그대로 무너진다.
     *   1. `Popup._adjustPan` 이 **맨 먼저 `map._panAnim.stop()`** 을 부른다. 위 `setView` 는 같은 zoom 이면
     *      0.5초 팬 애니메이션이라 아직 출발점 근처인데 그 상태로 멈춰 버린다.
     *      (zoom 차이 >4 면 애니메이션 없이 즉시 `_resetView` 라 이 문제는 없다)
     *   2. 이어서 팝업이 화면에 "들어오기만" 하도록 지도를 다시 민다 — 중앙 정렬이 아니다.
     * `_adjustPan` 은 `openPopup()` 안에서 **동기로** 끝나므로 바로 다음 줄에서 되돌려도 안전하다.
     * hover·클릭으로 여는 경로는 계속 autoPan 을 쓴다(가장자리 셀의 팝업이 잘리지 않게).
     *
     * ⚠️ 이 `openPopup()` 은 폴리곤의 `click` 을 발화시키지 않으므로 `onCellSelect` 가 되돌아오지
     * 않는다 — 목록 클릭이 지도를 거쳐 다시 선택으로 튀는 순환이 없다.
     */
    const popup = polygon.getPopup()
    const autoPan = popup?.options.autoPan
    if (popup) popup.options.autoPan = false
    polygon.openPopup()
    if (popup) popup.options.autoPan = autoPan
  }, [focus])

  if (!MAP_KEY) {
    return (
      <div className="lifezone-map lifezone-map--nokey" style={NO_KEY_STYLE}>
        배경지도 키가 설정되지 않았습니다
      </div>
    )
  }

  return (
    <>
      <div ref={elRef} className="lifezone-map" style={MAP_STYLE} />

      {/*
       * 배경지도 전환(일반/항공). 지도 div 의 **형제**로 둔다 — `.sim-stage` 직계 자식이어야
       * `analysis.css` 의 `.sim-stage > .map-type` 위치 규칙이 걸린다(범례와 같은 조건).
       * 범례와 달리 **결과 유무와 무관하게 항상** 뜬다 — 배경지도는 조회 전에도 보이기 때문이다.
       */}
      <MapTypeSwitch value={baseMap} onChange={setBaseMap} />

      {/*
       * 범례는 **결과가 있을 때만** 띄운다 — 진입 직후에는 셀이 없어 설명할 대상이 없다.
       * `.sim-legend` 는 `.sim-stage` 기준 좌하단 절대배치라 지도의 형제로 두어야 한다.
       */}
      {showLegend && cells.length > 0 && legend.length > 0 && (
        <div className="sim-legend">
          <div className="map-legend">
            <div className="map-legend__title">{legendTitle}</div>
            {legend.map((row) => (
              <div className="row" key={row.key}>
                <span className="dot" style={{ background: row.colorVar }} />
                {row.text}
              </div>
            ))}
          </div>
          {gridSzNm && (
            <div className="sim-gridchip">
              {/* 시안 그대로 칩 맨 앞 파란 사각 — 크기·모양은 `.sim-gridchip .dot` */}
              <span className="dot" style={{ background: 'var(--blue-50)' }} />
              {`격자 ${gridSzNm} × ${gridSzNm}`}
            </div>
          )}
        </div>
      )}
    </>
  )
}
