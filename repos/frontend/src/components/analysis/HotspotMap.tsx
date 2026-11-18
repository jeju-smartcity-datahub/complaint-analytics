import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import 'leaflet/dist/leaflet.css'
import * as L from 'leaflet'
import type { GradeCriterion, HotspotZone } from '../../api/analysis'
import MapTypeSwitch from '../common/MapTypeSwitch'
import { MAP_KEY, fixLeafletAnchors, createBasemapController, LIST_FOCUS_ZOOM, focusZoom, bindMarkerLabelZoom } from '../common/mapTiles'
import type { BaseMapKind, BasemapController } from '../common/mapTiles'
import {
  buildLegendRows,
  GRADE_BADGE_CLASS,
  GRADE_COLOR_VAR,
  GRADE_LABEL,
  resolveGradeColor,
} from './hotspotGrade'
import { formatDecimal } from '../../lib/analysis/hotspotExport'

/*
 * 집중 구역 분석 배경지도 + 등급 마커·팝업·범례 (TSK-4032 Step 8 → Step 12 확장).
 *
 * 마커/팝업 마크업은 시안(https://innopamsolution.github.io/jeju-datahub/)의 것을 그대로 옮겼다(D15) —
 * 44×56 물방울 `divIcon`(순위 숫자 + 구역명 라벨)과 `.gp` 팝업(등급 배지 · 읍면동 · 구역명 · 민원/단속 건수).
 * 스타일은 Step 9 에서 `src/styles/analysis.css` 로 이미 이식돼 있다(`.cmk*` · `.gp*` · `.map-legend` · `.sim-legend`).
 *
 * 이 단계의 범위
 *   - ✅ 마커·팝업·범례 · 결과 도착 시 `fitBounds`
 *   - ✅ (Step 13) 리스트 ↔ 마커 양방향 연동 — 마커 → 리스트는 `onMarkerSelect`,
 *        리스트 → 마커는 `focus`(해당 마커로 `setView`(확대) + `openPopup`)로 주고받는다.
 *        🔁 TSK-4241(2026-10-19) 개정 — 종전 이동만 하던 것을 공용 상수(mapTiles)의 불법주차 키 값까지
 *        확대하도록 바꿨다(확대만 — 이미 더 들어가 있으면 축소하지 않는다).
 *
 * 🔴 이 지도는 **오버레이 패널에 좌우가 가려져 있다**(좌 설정 패널 · 우 결과 패널). 그래서 시야를
 *    옮기는 곳(`focus` 팬 · `fitBounds`)은 컨테이너 정중앙이 아니라 **보이는 영역의 중앙**을 쓴다 —
 *    가림폭은 `measureOcclusion` 이 실측하고, 보정은 `centerFor` 가 한다.
 */

const JEJU_CENTER: L.LatLngExpression = [33.486, 126.512] // 시안과 동일
const JEJU_ZOOM = 13

/** 마커가 한 개뿐이면 `fitBounds` 가 최대 줌까지 당겨 버려 주변 맥락이 사라진다 — 이 줌으로 고정한다. */
const SINGLE_ZONE_ZOOM = 15

/**
 * 지도 가장자리에 남길 최소 여백(px). 마커는 앵커 위로 54px 솟아 있고 팝업은 그 위로 더 열려 넉넉히 준다.
 * 좌우에는 아래 `measureOcclusion` 이 실측한 **패널 가림폭이 더해진다**.
 */
const FIT_GAP = 80
/** 팝업 autoPan 이 남길 여백. `fitBounds` 처럼 넓게 잡으면 가장자리 마커에서 지도가 크게 튄다. */
const AUTOPAN_GAP = 24

/**
 * 지도를 덮는 오버레이 패널 — `.sim-stage` 안에서 지도의 **형제**로 절대배치돼 있다.
 * 폭·위치는 `analysis.css` 가 정하고 여기서는 **런타임 실측만** 한다 (값을 옮겨 적으면 CSS 가 바뀌는 순간 어긋난다).
 * 좌하단 범례(`.sim-legend`)는 좌측 설정 패널보다 좁아 그 가림폭 안에 들어가므로 따로 세지 않는다.
 */
const LEFT_PANEL_SELECTOR = '.sim-panel--left'
const RIGHT_PANEL_SELECTOR = '.sim-result'

/** 좌·우 패널이 지도 컨테이너를 각각 몇 px 덮는지. 패널이 없으면(닫힌 결과 패널) 0. */
interface Occlusion {
  left: number
  right: number
}

const NO_OCCLUSION: Occlusion = { left: 0, right: 0 }

/** 보정하고도 이만큼은 남아야 "보이는 중앙" 에 뜻이 있다 — 창이 좁아 그 아래면 보정을 포기한다. */
const MIN_VISIBLE_WIDTH = 120

/**
 * 오버레이 패널이 지도 컨테이너의 좌/우를 덮는 폭을 **실측**한다.
 *
 * 🔴 이 화면은 컨테이너의 기하학적 중앙과 **눈에 보이는 영역의 중앙이 다르다** — 좌측 설정 패널과
 * 우측 결과 패널이 지도 위에 얹혀 있기 때문이다. 매번 `getBoundingClientRect()` 로 재므로
 * CSS 폭이 바뀌어도, 결과 패널을 닫아도(그러면 우측 0) 그대로 따라간다.
 */
function measureOcclusion(container: HTMLElement | null): Occlusion {
  const stage = container?.parentElement
  if (!container || !stage) return NO_OCCLUSION

  const box = container.getBoundingClientRect()
  if (box.width <= 0) return NO_OCCLUSION

  // 패널이 없거나 `display:none` 이면 rect 가 전부 0 이라 자연히 0 이 된다.
  const cover = (selector: string, fromRight: boolean): number => {
    const rect = stage.querySelector(selector)?.getBoundingClientRect()
    if (!rect || rect.width <= 0) return 0
    const covered = fromRight ? box.right - rect.left : rect.right - box.left
    return Math.min(Math.max(covered, 0), box.width)
  }

  const left = cover(LEFT_PANEL_SELECTOR, false)
  const right = cover(RIGHT_PANEL_SELECTOR, true)
  // 두 패널이 지도를 거의 다 덮으면 보이는 중앙이라는 것이 없다 — 컨테이너 정중앙으로 되돌린다.
  if (box.width - left - right < MIN_VISIBLE_WIDTH) return NO_OCCLUSION
  return { left, right }
}

/**
 * `latlng` 이 **보이는 영역의 한가운데**에 오게 하는 지도 중심을 구한다.
 *
 * 보이는 영역의 중앙은 컨테이너 중앙에서 오른쪽으로 `(left - right) / 2` 만큼 떨어져 있다.
 * 마커를 거기 놓으려면 지도 중심을 같은 양만큼 **왼쪽**으로 옮겨야 하므로 투영 좌표에서 **뺀다**
 * (부호를 뒤집으면 마커가 오히려 패널 밑으로 더 들어간다).
 *
 * `dy` 는 0 이다 — 두 패널 사이 띠에는 위아래를 가리는 것이 없고, 팝업이 마커 **위로** 열리므로
 * 세로로 밀면 팝업만 상단에 붙는다.
 */
function centerFor(map: L.Map, latlng: L.LatLng, zoom: number, occ: Occlusion): L.LatLng {
  const dx = (occ.left - occ.right) / 2
  if (dx === 0) return latlng
  return map.unproject(map.project(latlng, zoom).subtract([dx, 0]), zoom)
}

/**
 * 마커를 **직접 클릭**해 팝업이 열릴 때 쓸 autoPan 여백을 패널 가림폭으로 갱신한다.
 *
 * 목록 클릭 경로는 팝업의 `autoPan` 을 끄지만(아래 focus effect), 이 경로는 **켜 둔다** —
 * 끄면 가장자리 마커를 눌렀을 때 팝업이 화면 밖으로 잘린다(팝업은 앵커 위로 48px + 본문 높이만큼 열린다).
 * 다만 Leaflet 기본 여백(5px)은 **컨테이너** 가장자리 기준이라 팝업이 패널 밑으로 밀려 들어가므로,
 * 가림폭을 여백에 실어 **패널 안쪽 경계**를 기준으로 삼게 한다.
 */
function syncAutoPanPadding(marker: L.Marker, container: HTMLElement | null): void {
  const popup = marker.getPopup()
  if (!popup) return
  const { left, right } = measureOcclusion(container)
  popup.options.autoPanPaddingTopLeft = L.point(left + AUTOPAN_GAP, AUTOPAN_GAP)
  popup.options.autoPanPaddingBottomRight = L.point(right + AUTOPAN_GAP, AUTOPAN_GAP)
}

/** 스테이지를 꽉 채운다. 세부 배치는 Step 9 의 `.sim-stage > .leaflet-container` 가 맡는다. */
const MAP_STYLE: CSSProperties = { width: '100%', height: '100%' }

const NO_KEY_STYLE: CSSProperties = {
  ...MAP_STYLE,
  display: 'grid',
  placeItems: 'center',
  background: 'var(--cool-neutral-99)',
  color: 'var(--text-alternative)',
  fontSize: 14,
}

/**
 * 결과가 없을 때 쓰는 **고정** 빈 배열.
 * 매 렌더마다 `[]` 를 새로 만들면 마커 effect 의 의존성이 계속 바뀌어 마커를 지웠다 다시 그리고
 * `fitBounds` 까지 다시 걸린다(사용자가 지도를 움직여도 계속 되돌아간다).
 */
const NO_ZONES: HotspotZone[] = []

/**
 * 팝업·마커 라벨에 들어가는 문자열 이스케이프.
 * 구역명은 VWorld POI·단속 주소에서 온 **서버 데이터**라 `&`·`<` 가 섞일 수 있는데,
 * `divIcon`/`bindPopup` 은 HTML 문자열을 그대로 파싱한다 — 시안은 목데이터라 필요 없던 방어다.
 */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 마커 `divIcon` 내부 HTML — 시안 마크업 그대로다.
 * `color` 는 CSS 변수가 아니라 **해석된 실제 색**이어야 한다(SVG `fill` 은 `var()` 를 받지 않는다).
 */
function markerHtml(rnk: number, color: string, zoneNm: string): string {
  return `<svg class="cmk__flag" width="44" height="56" viewBox="0 0 44 56" fill="none">
    <path d="M22 54 C22 54 39 33 39 19 A17 17 0 1 0 5 19 C5 33 22 54 22 54 Z" fill="${color}" stroke="#fff" stroke-width="2.5"/>
    <circle cx="22" cy="19" r="12.5" fill="rgba(255,255,255,0.92)"/>
    <text x="22" y="19" text-anchor="middle" dominant-baseline="central" font-family="'Pretendard',sans-serif" font-size="12" font-weight="800" fill="${color}">${rnk}</text>
  </svg><span class="cmk__lab">${escapeHtml(zoneNm)}</span>`
}

/**
 * 팝업 HTML — 시안 `.gp` 마크업 그대로.
 * 등급 라벨은 응답의 `gradeNm` 을 우선한다(백엔드가 판정한 이름이 정본 — D5).
 * `emdNm` 은 좌표가 행정동 경계 밖일 때 null 이 올 수 있어 있을 때만 줄을 채운다.
 * 하단 줄은 시안대로 `종합점수 <b>N점</b>` + `민원 건수 N건 · 단속 N건`(굵게 없음)이다(TSK-4244).
 * 점수 표기는 순위 목록(`HotspotResultPanel`)과 같은 `formatDecimal` 을 쓴다.
 */
function popupHtml(zone: HotspotZone): string {
  const gradeNm = escapeHtml(zone.gradeNm || GRADE_LABEL[zone.grade])
  const emd = zone.emdNm ? `<span class="gp__loc">${escapeHtml(zone.emdNm)}</span>` : ''
  return `<div class="gp">
    <div class="gp__h">
      <span class="${GRADE_BADGE_CLASS[zone.grade]}">${gradeNm}</span>
      ${emd}
    </div>
    <div class="gp__big">${escapeHtml(zone.zoneNm)}</div>
    <div class="gp__bd"><span>종합점수 <b>${formatDecimal(zone.snthsScr)}점</b></span><span>민원 건수 ${zone.cvlcptCnt}건 · 단속 ${zone.crdnCnt}건</span></div>
  </div>`
}

/**
 * 리스트에서 온 "이 구역을 보여 달라" 요청 (Step 13).
 *
 * 🔴 `rnk` 만으로는 부족해 `seq` 를 함께 받는다 — 같은 행을 연달아 누르면 `rnk` 가 그대로라
 * effect 가 다시 돌지 않아 지도가 움직이지 않는다(마커 클릭으로 다른 곳을 본 뒤 같은 행을 다시
 * 누르는 경우가 그렇다). 부모가 클릭마다 `seq` 를 올려 매번 새 요청임을 알린다.
 */
export interface HotspotMapFocus {
  rnk: number
  seq: number
}

export interface HotspotMapProps {
  /**
   * 지도에 얹을 집중 구역. **서버 정렬 순서 그대로**이며 `rnk` 도 서버가 매긴 값이다.
   * 좌표(`mrkLat`/`mrkLot`)가 null 인 구역은 마커를 만들지 않고 건너뛴다.
   */
  zones?: HotspotZone[]
  /**
   * 등급 판정 기준(응답 `gradeCriteria`). 범례가 이 값으로 `심각 (상위 5%)` 같은 문구를 만든다.
   *
   * 🔴 임계값을 화면에 박지 않기 위한 통로다(D5 — 단일 출처는 백엔드 `HotspotGrade` enum).
   * 없거나 비면 범례는 예전처럼 **라벨만** 보여 준다(하위호환 — 임계값을 추측하지 않는다).
   */
  gradeCriteria?: GradeCriterion[]
  /**
   * 이 구역의 팝업이 열렸다는 신호. 순위 목록(Step 13)이 받아 해당 행을 활성화·스크롤한다.
   * 마커를 눌러서 열든 목록에서 코드로 열든 **같은 한 갈래**로 올라온다.
   */
  onMarkerSelect?: (rnk: number) => void
  /**
   * 순위 목록에서 고른 구역으로 지도를 이동시킨다 — 그 마커가 **좌우 패널 사이 보이는 영역의
   * 한가운데**에 오도록 팬한 뒤 팝업을 연다.
   *
   * 마커 클릭으로 생긴 선택은 여기로 오지 **않는다** — 이미 보고 있는 마커를 다시 중앙으로 끌어오면
   * 클릭할 때마다 지도가 튄다. 좌표가 없어 마커가 없는 구역이면 조용히 아무 일도 하지 않는다.
   */
  focus?: HotspotMapFocus | null
}

export default function HotspotMap({
  zones = NO_ZONES,
  gradeCriteria,
  onMarkerSelect,
  focus = null,
}: HotspotMapProps) {
  /**
   * 범례 행. 조회 1회당 한 번만 조립하면 되는 순수 계산이라 렌더마다 다시 만들지 않는다
   * (문구 조립 규칙 자체는 `buildLegendRows` 가 갖는다 — 이 파일에 임계값이 없다).
   */
  const legendRows = useMemo(() => buildLegendRows(gradeCriteria), [gradeCriteria])

  const elRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  /** 배경지도 전환기. 지도와 생명주기가 같다(마운트에서 만들고 정리에서 버린다). */
  const basemapRef = useRef<BasemapController | null>(null)
  /** 🔴 선택은 저장하지 않는다(D4) — 화면을 떠나면 `base` 로 돌아온다. */
  const [baseMap, setBaseMap] = useState<BaseMapKind>('base')
  /**
   * 현재 지도에 올라가 있는 마커를 `rnk` 로 찾을 수 있게 담아 둔다.
   * 재실행 때 전부 지우고 다시 채우며, 목록에서 온 `focus` 도 이 표에서 마커를 찾는다.
   */
  const markersRef = useRef(new Map<number, L.Marker>())

  /**
   * 콜백을 ref 에 담아 둔다 — 아래 마커 effect 의 의존성에 콜백을 넣으면 부모가 인라인 함수를
   * 넘길 때마다(매 렌더) 마커가 통째로 다시 그려지고 `fitBounds` 가 다시 걸린다.
   */
  const selectRef = useRef(onMarkerSelect)
  useEffect(() => {
    selectRef.current = onMarkerSelect
  }, [onMarkerSelect])

  // ── 지도 생성 (마운트 1회) ──
  useEffect(() => {
    // 키가 없으면 지도 자체를 만들지 않는다 — 타일 요청이 전부 실패해 콘솔 에러가 폭주하는 것을 막는다.
    if (!MAP_KEY) return
    const el = elRef.current
    if (!el) return

    const map = L.map(el, {
      center: JEJU_CENTER,
      zoom: JEJU_ZOOM,
      zoomControl: false, // 기본 topleft 대신 시안과 같은 topright 로 다시 붙인다
    })
    L.control.zoom({ position: 'topright' }).addTo(map)
    fixLeafletAnchors(map)
    basemapRef.current = createBasemapController(map) // 일반지도 1장을 붙인다
    const unbindLabel = bindMarkerLabelZoom(map) // zoom < `LABEL_MIN_ZOOM` 이면 마커 라벨 숨김(TSK-4244)
    mapRef.current = map

    // 정리 함수에서 `markersRef.current` 를 다시 읽지 않으려고 여기서 잡아 둔다
    // (`react-hooks/exhaustive-deps` 경고 회피 — Map 인스턴스 자체는 재할당되지 않아 같은 객체다).
    const markers = markersRef.current

    // StrictMode 이중 마운트/언마운트 대비 — 정리하지 않으면 재마운트가
    // "Map container is already initialized" 로 죽는다.
    return () => {
      unbindLabel() // map.remove() 뒤에는 off 가 의미 없다
      map.remove()
      mapRef.current = null
      basemapRef.current = null
      markers.clear()
    }
  }, [])

  // ── 배경지도 전환 ──
  // 마운트 effect 보다 나중에 도는 것이 보장된다(같은 컴포넌트, 선언 순서). 항공 레이어는
  // 컨트롤러가 처음 `aerial` 일 때 만든다(D11).
  useEffect(() => {
    basemapRef.current?.apply(baseMap)
  }, [baseMap])

  // ── 마커 갱신 (결과가 바뀔 때마다) ──
  useEffect(() => {
    const map = mapRef.current
    if (!map) return

    /*
     * 🔴 **이전 마커를 반드시 먼저 제거한다.** 조건을 바꿔 재실행하면 구역이 통째로 달라지는데,
     * 지우지 않으면 이전 조건의 마커가 그대로 남아 순위가 겹쳐 쌓인다(1위가 두 개로 보인다).
     */
    markersRef.current.forEach((marker) => map.removeLayer(marker))
    markersRef.current.clear()

    const points: L.LatLngTuple[] = []
    zones.forEach((zone) => {
      // 좌표가 없는 구역은 지도에 찍을 수 없다 — 목록(Step 13)에는 남고 마커만 건너뛴다.
      if (zone.mrkLat == null || zone.mrkLot == null) return
      const position: L.LatLngTuple = [zone.mrkLat, zone.mrkLot]
      points.push(position)

      const icon = L.divIcon({
        className: 'cmk',
        iconSize: [44, 56],
        // 물방울 꼬리 끝(= 실제 지점)이 좌표에 오도록 시안과 같은 앵커를 쓴다 (D15).
        iconAnchor: [22, 54],
        html: markerHtml(zone.rnk, resolveGradeColor(zone.grade), zone.zoneNm),
      })
      const marker = L.marker(position, { icon, riseOnHover: true })

      /*
       * 🔴 **`bindPopup` 보다 먼저** 등록한다. Leaflet 은 핸들러를 등록 순서대로 부르는데,
       * 팝업을 열면서 `_adjustPan`(autoPan)을 **동기 실행**하는 click 핸들러는 `bindPopup` 이 붙인다 —
       * 뒤에 등록하면 팬이 이미 끝난 뒤라 여백을 갱신해도 늦는다.
       */
      marker.on('click', () => syncAutoPanPadding(marker, elRef.current))
      marker.bindPopup(popupHtml(zone), { closeButton: false, offset: [0, -48] }).addTo(map)

      /*
       * 🔴 **`popupopen` 하나만 구독한다**(`click` 은 잡지 않는다).
       *
       * 두 이벤트를 모두 잡으면 마커 1클릭에 콜백이 2회 올라간다. 그런데 `click` 이 잡아 주는
       * 유일한 추가 경우 — **이미 팝업이 열린 마커를 다시 클릭** — 은 Leaflet 이 팝업을 **닫는**
       * 동작이라(`Layer._openPopup` 의 토글) 거기서 "선택됨"을 쏘는 것이 오히려 틀리다.
       * 그 밖의 모든 경로(마커 클릭·목록에서 `openPopup()`)는 `popupopen` 을 반드시 거치므로
       * 신호를 잃지 않으면서 중복만 사라진다.
       */
      marker.on('popupopen', () => selectRef.current?.(zone.rnk))
      markersRef.current.set(zone.rnk, marker)
    })

    /*
     * 전체 보기에도 패널 가림을 반영한다 — 균등 여백(`padding`)으로 맞추면 결과가 패널 밑에 깔린다.
     * `fitBounds` 는 좌우를 따로 받는 `paddingTopLeft`/`paddingBottomRight` 를 지원한다.
     */
    const occlusion = measureOcclusion(elRef.current)
    if (points.length === 1) {
      // 한 점을 `fitBounds` 하면 최대 줌까지 당겨져 주변이 보이지 않는다.
      const only = L.latLng(points[0])
      map.setView(centerFor(map, only, SINGLE_ZONE_ZOOM, occlusion), SINGLE_ZONE_ZOOM)
    } else if (points.length > 1) {
      map.fitBounds(L.latLngBounds(points), {
        paddingTopLeft: [occlusion.left + FIT_GAP, FIT_GAP],
        paddingBottomRight: [occlusion.right + FIT_GAP, FIT_GAP],
      })
    }
    // 결과가 0건이면 시야를 건드리지 않는다 — 직전 화면을 유지하는 편이 덜 놀랍다.
  }, [zones])

  /*
   * ── 목록 → 지도 (Step 13) ──
   * 위 마커 effect 보다 **뒤에 선언**해야 한다 — 같은 렌더에서 `zones` 와 `focus` 가 함께 바뀌면
   * effect 는 선언 순서대로 도므로, 마커가 다시 만들어진 뒤에 그 마커를 찾게 된다.
   */
  useEffect(() => {
    const map = mapRef.current
    if (!map || !focus) return
    const marker = markersRef.current.get(focus.rnk)
    // 좌표가 없어 마커를 못 만든 구역이다 — 목록 선택만 되고 지도는 그대로 둔다.
    if (!marker) return

    // 보이는 영역(두 패널 사이)의 한가운데로 옮긴다 — 컨테이너 정중앙은 패널에 가려 중앙이 아니다.
    // 🔁 TSK-4241(2026-10-19) — 리스트 클릭에서만 · 확대만. 🔴 보정은 현재가 아니라 **목표 zoom** 으로 계산한다.
    const targetZoom = focusZoom(map, LIST_FOCUS_ZOOM.hotspot)
    const target = centerFor(map, marker.getLatLng(), targetZoom, measureOcclusion(elRef.current)) // 🔴 목표 zoom
    map.setView(target, targetZoom, { animate: true, duration: 0.5 }) // duration 은 같은 zoom(panBy) 경로에 그대로 전달된다

    /*
     * 🔴 이 한 번만 팝업 `autoPan` 을 끈다 — 켜 두면 방금 계산한 중앙이 그대로 무너진다.
     *   1. `Popup._adjustPan` 이 **맨 먼저 `map._panAnim.stop()`** 을 부른다. 위 `setView` 는 같은 zoom 이면
     *      0.5초 팬 애니메이션이라 아직 출발점 근처인데, 그 상태로 멈춰 버린다(중앙에 닿지도 못한다).
     *      (zoom 차이 >4 면 애니메이션 없이 즉시 `_resetView` 라 이 문제는 없다 — TSK-4241)
     *   2. 이어서 팝업이 화면에 "들어오기만" 하도록 지도를 다시 민다 — 중앙 정렬이 아니다.
     * `_adjustPan` 은 `openPopup()` 안에서 **동기로** 끝나므로(`DivOverlay.update` → `_adjustPan`,
     * `popupopen` 발화보다 앞) 바로 다음 줄에서 원래 값으로 되돌려도 안전하다.
     * 마커 직접 클릭 경로는 계속 autoPan 을 쓴다 — 근거는 `syncAutoPanPadding` 주석.
     */
    const popup = marker.getPopup()
    const autoPan = popup?.options.autoPan
    if (popup) popup.options.autoPan = false
    /*
     * 이 호출은 `popupopen` 을 발화시켜 `onMarkerSelect` 가 한 번 더 올라간다 — 부모는 같은 순위로
     * 선택을 다시 세우고, 목록은 이미 보이는 행이라 스크롤을 건너뛴다(멱등). 팝업을 여는 것이
     * 연동의 목적이므로 끊지 않는다.
     */
    marker.openPopup()
    if (popup) popup.options.autoPan = autoPan
  }, [focus])

  if (!MAP_KEY) {
    return (
      <div className="hotspot-map hotspot-map--nokey" style={NO_KEY_STYLE}>
        배경지도 키가 설정되지 않았습니다
      </div>
    )
  }

  return (
    <>
      <div ref={elRef} className="hotspot-map" style={MAP_STYLE} />

      {/*
       * 배경지도 전환(일반/항공). 지도 div 의 **형제**로 둔다 — `.sim-stage` 직계 자식이어야
       * `analysis.css` 의 `.sim-stage > .map-type` 위치 규칙이 걸린다(범례와 같은 조건).
       * 범례와 달리 **결과 유무와 무관하게 항상** 뜬다 — 배경지도는 조회 전에도 보이기 때문이다.
       */}
      <MapTypeSwitch value={baseMap} onChange={setBaseMap} />

      {/*
       * 범례는 **결과가 있을 때만** 띄운다 — 진입 직후에는 마커가 없어(D10) 설명할 대상이 없다.
       * `.sim-legend` 는 `.sim-stage` 기준 좌하단 절대배치라 지도의 형제로 두어야 한다.
       */}
      {zones.length > 0 && (
        <div className="sim-legend">
          <div className="map-legend">
            <div className="map-legend__title">종합점수</div>
            {legendRows.map((row) => (
              <div className="row" key={row.grade}>
                <span className="dot" style={{ background: GRADE_COLOR_VAR[row.grade] }} />
                {row.text}
              </div>
            ))}
          </div>
        </div>
      )}
    </>
  )
}
