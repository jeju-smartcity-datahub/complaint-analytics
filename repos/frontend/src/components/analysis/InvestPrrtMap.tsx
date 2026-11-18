import { useEffect, useRef, useState, type CSSProperties } from 'react'
import 'leaflet/dist/leaflet.css'
import * as L from 'leaflet'
import MapTypeSwitch from '../common/MapTypeSwitch'
import { MAP_KEY, fixLeafletAnchors, createBasemapController, LIST_FOCUS_ZOOM, focusZoom, bindMarkerLabelZoom } from '../common/mapTiles'
import type { BaseMapKind, BasemapController } from '../common/mapTiles'
import { resolveCssColor } from './hotspotGrade'
import type {
  InvestPrrtGradeCriterion,
  InvestPrrtLot,
  InvestPrrtLvlCd,
  InvestPrrtWeight,
} from '../../api/investPrrt'
import {
  PRRT_LVL_BADGE_CLASS,
  PRRT_LVL_COLOR_VAR,
  VRBL_SCR_FIELD,
  escapeHtml,
  fmtCnt,
  fmtInstl,
  fmtScr,
  isScrVrbl,
  prrtLvlLabel,
  type InvestPrrtDisplayLot,
} from './investPrrtView'

/*
 * 투자우선순위 보드 지도. 시안의 핀(`cmk`)·팝업(`lp`)·연동 동작을 옮겼다.
 * Leaflet 을 직접 만든다(`ZoneRcmdtnMap` 과 같은 형식) — 배경지도는 프로젝트 공용 VWorld +
 * `MapTypeSwitch` 다(시안의 OSM 타일을 쓰지 않는다).
 *
 * 🔴 **받은 `lots` 를 전부 핀으로 그리고, 그 밖은 아무것도 그리지 않는다.** 여기서 거르거나 자르지
 * 않는다 — 표시 집합(행정구역 필터 → 정렬 → 순위 부여 → 등급 필터 → 상위 N)은 페이지가 한 번 계산해 목록과 이 지도에 **같은 배열**로
 * 내려준다. 지도가 따로 자르면 목록에 있는데 눌러도 갈 곳이 없는 행이 생긴다.
 *
 * 연동
 *   - 핀 클릭 → 팝업이 열린다 → `popupopen` → `onSelect(pkltId)` → 목록 행 강조·스크롤.
 *   - 목록 클릭 → 페이지가 `selectedPkltId` 와 함께 `focusSeq` 를 올린다 → 그 핀으로 `setView`(확대) + 팝업.
 *     2026-10-19 — 이동만이 아니라 공용 상수(mapTiles)의 투자우선순위 키 값까지 확대한다
 *     (확대만 — 이미 더 들어가 있으면 축소하지 않는다). 가림폭 보정은 없다(핀을 컨테이너 정중앙으로).
 *     🔴 이동은 `focusSeq` 가 바뀔 때만 한다 — `selectedPkltId` 변화에 걸면 핀을 직접 누를 때도
 *     지도가 끌려가고(시안은 그러지 않는다), 팝업을 닫은 뒤 같은 행을 다시 눌러도 열리지 않는다.
 *   - 첫 `lots` 가 도착하면 표시 집합의 첫 행 팝업을 연다(시안의 "진입 시 1위 팝업").
 *
 * 🔴 핀·팝업의 HTML 은 문자열로 조립해 innerHTML 로 들어간다 — 이름·주소·지표명은 전부
 * `escapeHtml()` 을 거친다.
 */

/** 첫 화면 — 제주 전역(시안 값). 핀이 그려지면 그 범위로 맞춘다. */
const ISLAND_CENTER: L.LatLngExpression = [33.39, 126.55]
const ISLAND_ZOOM = 10.5
/** 표시 집합이 한 동에 몰려도 이보다 더 당기지 않는다. */
const FIT_MAX_ZOOM = 15
const FIT_PAD: L.PointTuple = [56, 56]
/** 진입 직후 팝업을 여는 지연 — 레이아웃이 자리 잡은 뒤여야 팝업 위치가 맞는다(시안 값). */
const OPEN_DELAY_MS = 300

const MAP_STYLE: CSSProperties = { position: 'absolute', inset: 0, zIndex: 0 }

/**
 * 핀 — 시안 `cmk__flag`(44×56 물방울, 등급 색 + 순위 숫자) + 이름 라벨.
 * 🔴 숫자는 **표시 순위 `dspRnk`**(행정구역·정렬 결과 안에서의 순번 — 등급 필터로는 바뀌지 않는다)다 — 목록의 순위 원과 같은 값이다.
 */
function pinHtml(dspRnk: number, color: string, name: string): string {
  // 숫자 크기 12 — 시안 갱신. 세 자리 순위도 원(지름 25) 안에 들어가 자릿수 분기가 없어졌다.
  return (
    '<svg class="cmk__flag" width="44" height="56" viewBox="0 0 44 56" fill="none">' +
    `<path d="M22 54 C22 54 39 33 39 19 A17 17 0 1 0 5 19 C5 33 22 54 22 54 Z" fill="${color}" stroke="#fff" stroke-width="2.5"></path>` +
    '<circle cx="22" cy="19" r="12.5" fill="rgba(255,255,255,0.95)"></circle>' +
    `<text x="22" y="19" text-anchor="middle" dominant-baseline="central" font-family="'Pretendard', sans-serif" font-size="12" font-weight="800" fill="${color}">${dspRnk}</text>` +
    '</svg>' +
    `<span class="cmk__lab">${escapeHtml(name)}</span>`
  )
}

/** 팝업 — 시안 `lp` 구성 그대로. 지표 막대는 응답 `weights` 에 있는 지표만(끈 지표는 줄이 없다). */
function popupHtml(
  lot: InvestPrrtLot,
  color: string,
  weights: InvestPrrtWeight[],
  criteria: InvestPrrtGradeCriterion[],
): string {
  const badge = PRRT_LVL_BADGE_CLASS[lot.prrtLvlCd] ?? 'badge'
  const addr = [lot.pkltAddr, lot.pkltCpcty != null ? `총 ${fmtCnt(lot.pkltCpcty)}면` : null]
    .filter((s): s is string => !!s)
    .map(escapeHtml)
    .join(' · ')
  const instl = fmtInstl(lot.instlYm)

  const rows = weights
    .map((w) => {
      if (!isScrVrbl(w.vrblCd)) return ''
      const raw = Number(lot[VRBL_SCR_FIELD[w.vrblCd]])
      const num = Number.isFinite(raw) ? Math.round(raw) : 0
      const width = Math.min(100, Math.max(0, num))
      return (
        `<div class="lp__row"><span class="lp__lab">${escapeHtml(w.vrblNm)}</span>` +
        `<span class="lp__bar"><i style="width:${width}%;background:${color}"></i></span>` +
        // 4지표 모두 0~100 점수라 `점` 을 붙인다(시안 `.lp__unit` · 막대 폭·반올림은 그대로)
        `<b class="lp__num">${num}<em class="lp__unit">점</em></b></div>`
      )
    })
    .join('')

  return (
    '<div class="lp"><div class="lp__top">' +
    `<span class="${badge}">${escapeHtml(prrtLvlLabel(lot.prrtLvlCd, criteria))}</span>` +
    // 종합점수도 같은 단위 — 한 팝업에서 단위가 갈리지 않게(시안 `uTe`)
    `<span class="lp__score">${fmtScr(lot.snthsScr)}<em class="lp__score-unit">점</em></span>` +
    `<span class="lp__name">${escapeHtml(lot.pkltNm)}</span></div>` +
    (addr ? `<div class="lp__meta">${addr}</div>` : '') +
    (instl ? `<div class="lp__meta">${escapeHtml(instl)}</div>` : '') +
    (rows ? `<div class="lp__metrics">${rows}</div>` : '') +
    '</div>'
  )
}

interface Props {
  /** 표시 집합 — 페이지의 `displayLots` 그대로(목록과 같은 배열). */
  lots: InvestPrrtDisplayLot[]
  gradeCriteria: InvestPrrtGradeCriterion[]
  weights: InvestPrrtWeight[]
  selectedPkltId: number | null
  /** 핀을 누르거나 팝업이 열리면 올린다. */
  onSelect: (pkltId: number) => void
  /** 목록에서 행을 누를 때마다 페이지가 올리는 수 — 바뀌면 `selectedPkltId` 의 핀으로 이동해 팝업을 연다. */
  focusSeq: number
}

export default function InvestPrrtMap({
  lots,
  gradeCriteria,
  weights,
  selectedPkltId,
  onSelect,
  focusSeq,
}: Props) {
  const elRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const basemapRef = useRef<BasemapController | null>(null)
  const [baseMap, setBaseMap] = useState<BaseMapKind>('base')

  /** `pkltId` → 핀. 🔴 지도에 올라간 핀은 전부 여기 있다(정리의 정본). */
  const pinsRef = useRef(new Map<number, L.Marker>())
  /** 진입 시 첫 행 팝업을 이미 열었는지. */
  const openedOnceRef = useRef(false)
  /** 마지막으로 처리한 `focusSeq`. */
  const focusSeqRef = useRef(focusSeq)

  // 핀 핸들러가 항상 최신 콜백을 부르게 한다(핀을 다시 만들지 않고).
  const selectRef = useRef(onSelect)
  useEffect(() => {
    selectRef.current = onSelect
  }, [onSelect])

  // ── 지도 생성 (마운트 1회) ──
  useEffect(() => {
    // 키가 없으면 지도 자체를 만들지 않는다 — 타일 요청이 전부 실패해 콘솔 에러가 폭주한다.
    if (!MAP_KEY) return
    const el = elRef.current
    if (!el) return

    const map = L.map(el, {
      center: ISLAND_CENTER,
      zoom: ISLAND_ZOOM,
      zoomSnap: 0.5,
      zoomControl: false,
    })
    L.control.zoom({ position: 'topright' }).addTo(map)
    fixLeafletAnchors(map)
    basemapRef.current = createBasemapController(map) // 일반지도 1장을 붙인다
    const unbindLabel = bindMarkerLabelZoom(map) // zoom < `LABEL_MIN_ZOOM` 이면 마커 라벨 숨김
    mapRef.current = map

    // 카드 크기가 바뀌면(창 크기 · 사이드바 접힘) 타일 격자를 다시 맞춘다.
    const observer = new ResizeObserver(() => map.invalidateSize())
    observer.observe(el)

    const pins = pinsRef.current
    // StrictMode 이중 마운트 대비 — 정리하지 않으면 재마운트가
    // "Map container is already initialized" 로 죽는다.
    return () => {
      observer.disconnect()
      unbindLabel() // map.remove() 뒤에는 off 가 의미 없다
      map.remove() // 붙어 있는 핀·팝업·타일을 함께 떼어 낸다
      mapRef.current = null
      basemapRef.current = null
      pins.clear()
      openedOnceRef.current = false
    }
  }, [])

  // ── 배경지도 전환 ──
  useEffect(() => {
    basemapRef.current?.apply(baseMap)
  }, [baseMap])

  // ── 핀 갱신 (표시 집합이 바뀔 때마다) ──
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const pins = pinsRef.current

    // 🔴 이전 핀을 먼저 전부 뗀다 — 남기면 필터를 바꿀 때마다 핀이 쌓여 목록과 수가 어긋난다.
    pins.forEach((pin) => map.removeLayer(pin))
    pins.clear()

    const colors = new Map<InvestPrrtLvlCd, string>()
    const colorOf = (cd: InvestPrrtLvlCd): string => {
      let c = colors.get(cd)
      if (c == null) {
        c = resolveCssColor(PRRT_LVL_COLOR_VAR[cd] ?? 'var(--blue-50)')
        colors.set(cd, c)
      }
      return c
    }

    const points: L.LatLngTuple[] = []
    lots.forEach((lot, i) => {
      // 좌표가 없으면 찍을 수 없다(API 는 좌표 있는 주차장만 준다 — 방어).
      if (lot.lat == null || lot.lot == null) return
      const position: L.LatLngTuple = [lot.lat, lot.lot]
      points.push(position)
      const color = colorOf(lot.prrtLvlCd)

      const marker = L.marker(position, {
        icon: L.divIcon({
          className: 'cmk',
          iconSize: [44, 56],
          iconAnchor: [22, 54], // 물방울 꼬리 끝(= 실제 지점)이 좌표에 오게 한다
          html: pinHtml(lot.dspRnk, color, lot.pkltNm),
        }),
        riseOnHover: true,
        // 겹치면 목록에서 앞선 행의 핀이 위에 온다.
        zIndexOffset: (lots.length - i) * 10,
      })
      marker
        .bindPopup(popupHtml(lot, color, weights, gradeCriteria), {
          closeButton: true,
          offset: [0, -46],
          minWidth: 236,
          maxWidth: 280,
          className: 'lot-popup',
        })
        .addTo(map)
      /*
       * 🔴 `popupopen` 하나만 구독한다 — 핀 클릭도 팝업을 열어 이 이벤트를 거치므로 `click` 까지
       * 잡으면 1클릭에 콜백이 두 번 올라간다. 코드로 `openPopup()` 하는 경로도 여기로 온다.
       */
      marker.on('popupopen', () => selectRef.current(lot.pkltId))
      pins.set(lot.pkltId, marker)
    })

    if (points.length === 1) {
      map.setView(points[0], FIT_MAX_ZOOM, { animate: false })
    } else if (points.length > 1) {
      map.fitBounds(L.latLngBounds(points), {
        padding: FIT_PAD,
        maxZoom: FIT_MAX_ZOOM,
        animate: false,
      })
    }

    // 진입 시(첫 표시 집합) 첫 행의 팝업을 연다. 필터·정렬로 다시 그릴 때는 열지 않는다.
    let timer: number | undefined
    if (!openedOnceRef.current && lots.length > 0) {
      const firstId = lots[0].pkltId
      timer = window.setTimeout(() => {
        openedOnceRef.current = true
        map.invalidateSize()
        pins.get(firstId)?.openPopup()
      }, OPEN_DELAY_MS)
    }
    return () => {
      if (timer != null) window.clearTimeout(timer)
    }
  }, [lots, weights, gradeCriteria])

  /*
   * ── 목록 → 지도 ──
   * 위 핀 effect 보다 **뒤에 선언**해야 한다 — effect 는 선언 순서대로 도므로, 핀이 다시 만들어진
   * 뒤에 그 핀을 찾게 된다.
   */
  useEffect(() => {
    if (focusSeqRef.current === focusSeq) return
    focusSeqRef.current = focusSeq
    const map = mapRef.current
    if (!map || selectedPkltId == null) return
    const pin = pinsRef.current.get(selectedPkltId)
    if (!pin) return
    // 2026-10-19 — 리스트 클릭에서만 · 확대만. duration 은 같은 zoom(panBy) 경로에 그대로 전달된다.
    map.setView(pin.getLatLng(), focusZoom(map, LIST_FOCUS_ZOOM.investPrrt), { animate: true, duration: 0.5 })

    /*
     * 🔴 이 한 번만 팝업 `autoPan` 을 끈다 — 켜 두면 위 `setView` 가 무효가 된다(`ZoneRcmdtnMap` 과 같은 처리).
     *   1. `Popup._adjustPan` 이 **맨 먼저 `map._panAnim.stop()`** 을 부른다. 위 `setView` 는 같은 zoom 이면
     *      0.5초 팬 애니메이션이라 아직 출발점인데 그 상태로 멈춰 버린다(그 주차장으로 가지 못한다).
     *      (zoom 차이 >4 면 애니메이션 없이 즉시 `_resetView` 라 이 문제는 없다)
     *   2. 이어서 팝업이 화면에 "들어오기만" 하도록 민다 — 핀이 이미 시야 안이면 지도는 그대로다.
     * `_adjustPan` 은 `openPopup()` 안에서 **동기로** 끝나므로 바로 다음 줄에서 되돌려도 안전하다.
     * 핀 직접 클릭·진입 시 첫 행 팝업 경로는 이 effect 를 거치지 않아 계속 autoPan 을 쓴다.
     */
    const popup = pin.getPopup()
    const autoPan = popup?.options.autoPan
    if (popup) popup.options.autoPan = false
    // `popupopen` 이 발화해 `onSelect` 가 한 번 더 올라간다 — 같은 주차장이라 멱등이다.
    pin.openPopup()
    if (popup) popup.options.autoPan = autoPan
  }, [focusSeq, selectedPkltId])

  return (
    <>
      <div ref={elRef} style={MAP_STYLE} />
      {/* 지도 div 의 형제로 둔다 — 자리는 `analysis.css` 의 `.map-body > .map-type` 이 잡는다. */}
      <MapTypeSwitch value={baseMap} onChange={setBaseMap} />
    </>
  )
}
