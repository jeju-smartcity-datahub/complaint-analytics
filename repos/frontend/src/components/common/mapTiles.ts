// src/components/common/mapTiles.ts
import * as L from 'leaflet'

/*
 * 배경지도(VWorld) 타일 정본.
 *
 * 지금까지 `MAP_KEY`·타일 URL·attribution 이 지도 컴포넌트마다 복사돼 있었다(3벌).
 * 시안 기준 이 컨트롤이 붙을 화면이 5개라 사본이 계속 늘어나는 구조였고,
 * 아래 두 함정이 사본마다 다시 재현될 수 있어 한곳으로 모은다.
 *   ① `Satellite` 만 확장자가 `.jpeg` 다 — `.png` 로 부르면 타일이 **조용히** 안 온다.
 *   ② VWorld WMTS 는 `{z}/{y}/{x}` 순서다 — OSM 의 `{z}/{x}/{y}` 로 쓰면 엉뚱한 타일이 온다.
 */

/**
 * 런타임 값(`window.__APP_ENV__`, 컨테이너 시작 시 `docker-entrypoint.sh` 가 `/env.js` 로 씀)이
 * 있으면 그 값을, 없으면 빌드 시점 인라인 값(`import.meta.env`)을 쓴다. 그래서 운영은 compose `.env` 만
 * 바꾸고 재기동하면 되고, 로컬(vite dev)은 `public/env.js` 가 비어 있어 종전처럼 `.env` 값을 쓴다.
 */
const runtimeEnv = (name: keyof AppRuntimeEnv, buildValue: string | undefined): string => {
  const v = (typeof window !== 'undefined' ? window.__APP_ENV__?.[name] : undefined) ?? ''
  return v.trim() || (buildValue ?? '').trim()
}

/** VWorld 배경지도 키. 값이 없을 수도 있어 방어적으로 읽는다. */
export const MAP_KEY = runtimeEnv('VITE_VWORLD_API_KEY', import.meta.env.VITE_VWORLD_API_KEY)

/**
 * VWorld 타일 호스트. 프록시 경유 환경이면 `VITE_VWORLD_BASE_URL` 로 바꾼다(키와 같은 우선순위).
 * 비면 공식 호스트다. 끝의 `/` 는 잘라 `//req/wmts` 를 막는다.
 */
const VWORLD_BASE_URL =
  runtimeEnv('VITE_VWORLD_BASE_URL', import.meta.env.VITE_VWORLD_BASE_URL).replace(/\/+$/, '') || 'https://api.vworld.kr'

export const VWORLD_ATTRIBUTION =
  '&copy; <a href="https://www.vworld.kr/" target="_blank" rel="noreferrer">VWorld</a>'

/** 배경지도 종류. 화면을 떠나면 `base` 로 돌아온다(저장하지 않는다). */
export type BaseMapKind = 'base' | 'aerial'

const wmts = (layer: string, ext: string) =>
  `${VWORLD_BASE_URL}/req/wmts/1.0.0/${MAP_KEY}/${layer}/{z}/{y}/{x}.${ext}`

/** VWorld WMTS 가 실제로 주는 최대 레벨. 이 위는 이 레벨 타일을 확대해 보인다(overzoom). */
const MAX_NATIVE_ZOOM = 19
/** 지도가 들어갈 수 있는 최대 zoom. 🔴 `LIST_FOCUS_ZOOM` 의 어떤 값보다 작으면 조용히 잘린다. */
const MAX_ZOOM = 20

/**
 * 결과 패널 리스트 클릭 시 확대할 zoom. 확대만 한다 — 현재 zoom 이 더 크면 유지.
 * 화면마다 대상 크기가 달라(핀 / 격자 셀 / 동 polygon) 키를 나눴다. 조정은 여기서만 한다.
 */
export const LIST_FOCUS_ZOOM = {
  complaints: 17,   // 민원현황 (민원다발 · 단속집중 · 수요부족)
  hotspot: 17,      // 불법주차집중구역분석
  lifezone: 17,     // 생활권시뮬레이션 (민원건수 · 주차수급 · 혼잡도)
  zoneRcmdtn: 17,   // 구역추천 — 요금제적용
  zoneShare: 17,    // 구역추천 — 공유주차제적용
  investPrrt: 17,   // 투자우선순위보드
} as const

/** 리스트 포커스 목표 zoom — 확대만 한다. */
export function focusZoom(map: L.Map, target: number): number {
  return Math.max(map.getZoom(), Math.min(target, MAX_ZOOM))
}

/**
 * 이 zoom **미만**이면 마커 라벨(`.cmk__lab`)을 숨긴다 — 저배율에서 라벨끼리 겹쳐 지도가 글자로 덮인다.
 * 대상은 `.cmk__lab` 을 쓰는 지도 전부(집중구역 · 구역추천 · 투자우선순위보드)다. 조정은 여기서만 한다.
 * 🔁 2026-10-21 화면 확인 후 "14 이하 숨김" → **13 부터 표시**. 🔴 "이하(`<=`)" 가 아니라 "미만(`<`)" 이다 —
 * 투자우선순위보드는 `zoomSnap: 0.5` 라 `<= 12` 로 쓰면 12.5 에서 라벨이 보인다.
 */
export const LABEL_MIN_ZOOM = 13

/** `href` 를 떼고 키보드 동작을 직접 잇는다. 다시 불려도 리스너가 겹치지 않는다(팝업 DOM 은 재사용된다). */
function dropHref(link: HTMLElement): void {
  if (!link.hasAttribute('href')) return
  link.removeAttribute('href')
  link.tabIndex = 0
  link.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return
    e.preventDefault()
    link.click()
  })
}

/**
 * Leaflet 이 줌 버튼에 `href="#"`, 팝업 닫기에 `href="#close"` 를 넣는다 — 없는 대상을 가리키는 링크라
 * 웹접근성 항목 12(건너뛰기 링크)에 걸린다. 줌 컨트롤을 붙인 **직후** 부른다(그 전이면 버튼이 없다).
 * 해제는 `map.remove()` 가 한다.
 */
export function fixLeafletAnchors(map: L.Map): void {
  map.getContainer().querySelectorAll<HTMLElement>('.leaflet-control-zoom a').forEach(dropHref)
  map.on('popupopen', (e) => {
    const close = e.popup.getElement()?.querySelector<HTMLElement>('.leaflet-popup-close-button')
    if (close) dropHref(close)
  })
}

/**
 * zoom 에 따라 지도 컨테이너에 `cmk-map--lab-off` 를 토글한다(라벨 숨김은 CSS 가 한다).
 * 🔴 마커를 다시 만들지 않는다 — 선택 강조가 `setIcon` 이라 재생성과 겹치면 강조가 풀린다. CSS 로만 숨긴다.
 * 반환값은 해제 함수다(마운트 effect 의 cleanup 에서 부른다).
 */
export function bindMarkerLabelZoom(map: L.Map): () => void {
  const el = map.getContainer()
  const sync = () => el.classList.toggle('cmk-map--lab-off', map.getZoom() < LABEL_MIN_ZOOM)
  sync()
  map.on('zoomend', sync)
  return () => {
    map.off('zoomend', sync)
    el.classList.remove('cmk-map--lab-off')
  }
}

/**
 * 배경지도 타일 페인의 겹침 순서. 지금은 한 번에 한 장만 올라가지만(라벨 오버레이 미사용)
 * 값을 명시해 둔다. `bringToBack()` 같은 DOM 추가 순서 의존을 쓰지 않기 위해서다.
 * 라벨 오버레이(`Hybrid`)를 나중에 되살리면 이 위(2)에 얹는다.
 */
const Z_BASEMAP = 1

const tile = (layer: string, ext: string) =>
  L.tileLayer(wmts(layer, ext), {
    maxNativeZoom: MAX_NATIVE_ZOOM,
    maxZoom: MAX_ZOOM,
    zIndex: Z_BASEMAP,
    // 🔴 두 레이어 모두에 준다 — `Base` 를 떼는 순간 크레딧이 사라지기 때문이다.
    attribution: VWORLD_ATTRIBUTION,
  })

/** 배경지도 전환기. 지도 1개당 1개를 만들어 쓰고, 지도와 함께 버린다. */
export interface BasemapController {
  /** 배경을 `kind` 로 맞춘다. 같은 값이면 아무 것도 하지 않는다(멱등). */
  apply(kind: BaseMapKind): void
}

/**
 * 지도에 **일반지도 1장을 붙이고** 전환기를 돌려준다. 지도를 만든 직후 한 번 부른다.
 *
 * 🔴 항공 레이어는 **처음 `apply('aerial')` 할 때** 만든다. 마운트 때 두 장을 다 붙이면
 * 항공을 한 번도 안 누른 사용자까지 위성 타일을 받아 VWorld 일일 호출 한도를 두 배로 쓴다.
 * 🔴 add/remove 로 갈아 끼운다 — `style.opacity` 를 직접 만지면 `layer.options.opacity` 와
 * 어긋나 pan/zoom 시 Leaflet 내부(`_resetView`)가 마운트 값으로 되돌린다(민원현황이 겪은 회귀).
 *
 * 정리(cleanup)는 두지 않는다 — 부모의 `map.remove()` 가 붙어 있는 레이어를 함께 떼어 낸다.
 * 여기서 또 지우면 이미 사라진 지도를 만지게 된다(StrictMode 이중 마운트에서 드러난다).
 */
export function createBasemapController(map: L.Map): BasemapController {
  const base = tile('Base', 'png').addTo(map)
  let aerial: L.TileLayer | null = null
  let current: BaseMapKind = 'base'

  return {
    apply(kind) {
      if (kind === current) return
      if (kind === 'aerial') {
        if (!aerial) aerial = tile('Satellite', 'jpeg')
        if (!map.hasLayer(aerial)) aerial.addTo(map)
        if (map.hasLayer(base)) map.removeLayer(base)
      } else {
        if (!map.hasLayer(base)) base.addTo(map)
        if (aerial && map.hasLayer(aerial)) map.removeLayer(aerial)
      }
      current = kind
    },
  }
}
