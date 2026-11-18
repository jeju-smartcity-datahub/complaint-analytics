import * as L from 'leaflet'
import 'leaflet/dist/leaflet.css'
/*
 * 라벨 클러스터 — `leaflet.markercluster@1.5.3`.
 * 🔴 `L.markerClusterGroup(...)` 을 쓰지 않는다. 이 패키지는 UMD 팩토리 안에서 **전역 `window.L`** 을
 *    변이한다(`L.MarkerClusterGroup = L.FeatureGroup.extend(...)` · `L.markerClusterGroup = ...` —
 *    `require('leaflet')` 가 아니다). 우리 `L` 은 모듈 네임스페이스라, 번들러가 CJS 를 감쌀 때
 *    네임스페이스 키를 스냅숏하면 나중에 붙은 `markerClusterGroup` 이 보이지 않는다(런타임 undefined).
 *    대신 패키지가 **자기 exports 에 싣는 `MarkerClusterGroup` 클래스**(`exports.MarkerClusterGroup`)를
 *    직접 쓴다. `@types/leaflet.markercluster` 는 'leaflet' 모듈 보강만 선언하고 이 named export 를
 *    선언하지 않아 타입만 보강 클래스(`L.MarkerClusterGroup`)로 맞춘다.
 *    ⚠️ 팩토리가 평가 시점에 `window.L` 을 읽으므로 이 import 는 반드시 'leaflet' import **뒤**에 둔다
 *    (leaflet 1.9 UMD 가 `window.L = exports` 를 항상 설정한다).
 */
import * as markerCluster from 'leaflet.markercluster'
import 'leaflet.markercluster/dist/MarkerCluster.css'
import 'leaflet.markercluster/dist/MarkerCluster.Default.css'
import { useEffect, useRef, useState, type CSSProperties } from 'react'
import type {
  AdminDistrictFeatureCollection,
  HotspotHeatmapPoint,
  HotspotRankingZone,
  ParkingLotItem,
} from '../../api/complaints'
import LayerCard, { type DisplayMode, type LayerToggles, type LayerView } from './LayerCard.tsx'
import LegendCard from './LegendCard.tsx'
import { createHeatLayer, type HeatLayer } from './HeatLayer.ts'
import { NEUTRAL_COLOR, RISK_GRADE_COLOR, pickRiskGrade, type RiskGrade } from './legendConstants.ts'
import MapTypeSwitch from '../common/MapTypeSwitch'
import { MAP_KEY, fixLeafletAnchors, createBasemapController, LIST_FOCUS_ZOOM, focusZoom } from '../common/mapTiles'
import type { BaseMapKind, BasemapController } from '../common/mapTiles'

/*
 * 민원현황 §6 — 지도 컴포넌트 (§4 토글 연동).
 *
 * 사양: 배경지도(일반/항공 토글) + 줌 인아웃 + 분석레이어 polygon·마커 + 토글 의존
 *       마커/폴리곤 (공영주차장 / 행정동 경계).
 *
 * 원래 사양은 "openlayers 기반" 이었지만 코드베이스의 다른 지도 컴포넌트
 * (HotspotMap / LifezoneMap) 가 모두 leaflet 1.9 (+ 직접 DOM ref) 로 통일돼 있어
 * 그 패턴을 차용한다. react-leaflet 미설치.
 *
 * 키 (VITE_VWORLD_API_KEY) 가 비어 있으면 타일 요청을 보내지 않고 안내만 띄운다
 * (HotspotMap 의 no-key 방어와 같은 의도). Production 키는 Dockerfile build ARG
 * 로 주입한다.
 *
 * §4 토글과 데이터:
 *   - props `view` / `zones` / `toggles` / `parkingLots` / `adminDistricts` 가 바뀌면
 *     polygon·마커 레이어를 다시 그린다 (effect 의 deps).
 *   - polygon 은 행정동 경계(`adminBoundary=true` 일 때만) + 분석레이어 hotspot zone
 *     (zone 별 rank 백분위 색상) 두 종류 — view 부재(빈 zones) 시 hotspot 레이어는 안 그린다.
 *   - 분석레이어 hotspot zone (2026-09-21 갱신) — `bndryGeoJson` 이 있으면 polygon,
 *     그리고 **항상** `mrkLat`/`mrkLot`(ST_PointOnSurface — polygon 내부 보장) 위치에 중심점 라벨
 *     마커(원 + `{동} {값}` 상시 라벨)를 그린다. hover tooltip 은 없다. 세 view 모두 등급(심각·경고·주의)
 *     밖(50% 초과) zone 을 그리지 않는다(범례 3단계와 일치). polygon·마커 클릭은 같은 팝업.
 *   - 라벨 마커는 클러스터 그룹(`leaflet.markercluster`, 시안 옵션)에 담는다 — 줌 아웃 시 숫자 원으로
 *     묶이고 줌 인하면 풀린다. polygon 은 클러스터 대상이 아니다(zone layer 에 그대로).
 *   - 🔴 순위 카드(§7) 행 클릭도 **같은 경로**다 (2026-09-30) — `selectedEmdCd`
 *     prop 이 바뀌면 redraw 루프가 채운 `zoneClickRef` 레지스트리에서 그 zone 의 `onZoneClick` 을
 *     꺼내 부른다. 강조·팝업 코드는 그 함수 한 곳에만 있다(복제 금지).
 *     레지스트리 값은 `{ open, lat, lot }` — `open` 이 그 `onZoneClick` 이고, `lat`/`lot` 은 팝업과
 *     **같은 앵커**(`mrkLat`/`mrkLot`)다. 🔴 **리스트 클릭만** 그 좌표로 `setView`(확대) 해 동을 화면
 *     가운데 둔다(이동 → 이동+확대로 개정) — polygon·마커 직접 클릭은
 *     종전대로 화면을 움직이지 않고 zoom 도 바꾸지 않는다.
 *   - 공영주차장 마커(`publicParking=true` 일 때만)는 클릭 팝업(이름·주소·행정동·주차장 유형·면수·요금)을 띄운다 —
 *     hover tooltip 은 없다. 행정동 경계는 `interactive: false` 점선 윤곽뿐이다(hover·클릭 없음).
 *
 * 히트맵 모드 (`displayMode='heatmap'`, 민원 다발·단속 집중만):
 *   - zone redraw effect 는 기존 정리 경로(`clearLayers`·`closePopup`·강조 ref·레지스트리 비우기)를
 *     그대로 탄 뒤 **그리지 않고 return** 한다 — polygon·라벨 마커가 없고 레지스트리도 빈다.
 *   - `HeatLayer` 는 마운트 effect 가 만들어 `heatLayerRef` 에 두고, add/remove·`setData` 는 별도
 *     effect(`[displayMode, heatPoints]`)가 한다. 강도 상한은 칸 건수 **P95**(최소 1)이며 계산은 그 effect 한 곳.
 *   - 히트맵은 비대화형이다(클릭 팝업·툴팁 없음). 공영주차장·행정동 경계 토글은 그대로 동작한다.
 *   - 순위 카드 행 클릭·벨 포커스는 선택 effect 가 `displayModeRef` 로 갈린다 — 레지스트리가 아니라
 *     **`zones` 에서** 그 동을 찾아 polygon MBR 로 `fitBounds`(강조·팝업 없음), polygon 이 없으면
 *     `mrkLat`/`mrkLot` 로 `setView` 폴백. 그래서 히트맵 모드에서도 `zones` 는 계속 받는다(그리지만 않는다).
 *
 * 인터랙션 (2026-09-08 갱신):
 *   - 스크롤 휠 줌 on (사용자 확정) — Leaflet 기본. polygon 위도 의도치 않은 동작 아님.
 *   - 더블클릭 줌 off — `L.map({ doubleClickZoom: false })`. polygon
 *     위 더블클릭이 의도치 않은 줌 인을 일으키는 것 방지.
 *   - polygon Fill = Stroke 동일 값 (사용자 확정) — 현재 marker 의 단일 색 정책 유지.
 *     행정동 경계 토글의 점선(`dashArray: '3 3'`, 회색)과 시각 구분.
 */

const MarkerClusterGroup = (markerCluster as unknown as {
  MarkerClusterGroup: typeof L.MarkerClusterGroup
}).MarkerClusterGroup

/**
 * 공영주차장 클러스터 아이콘 — 시안의 "보라 P 원 + 우상단 개수 배지".
 * 시안 인라인 style 을 `.minwon-map__parking-cluster*` 클래스로 옮겼다. 색은 개별 P 마커와 같은 값을
 * 공유한다(`--minwon-parking-color`). `className: ''` 로 markercluster 기본 `.marker-cluster` 원형 배경을 막는다.
 */
function createParkingClusterIcon(cluster: L.MarkerCluster): L.DivIcon {
  const root = document.createElement('div')
  root.className = 'minwon-map__parking-cluster'
  const letter = document.createElement('span')
  letter.className = 'minwon-map__parking-cluster__letter'
  letter.textContent = 'P'
  const count = document.createElement('span')
  count.className = 'minwon-map__parking-cluster__count'
  count.textContent = String(cluster.getChildCount())
  root.append(letter, count)
  return L.divIcon({ className: '', iconSize: [28, 28], iconAnchor: [14, 14], html: root })
}

const JEJU_CENTER: L.LatLngExpression = [33.486, 126.512] // 시안과 동일
const JEJU_ZOOM = 13

/**
 * polygon 자신이 자기 원래 스타일을 들고 있게 한다 — 복원은 언제나 '그 레이어의' 값으로.
 * 종전엔 *새로 클릭한* polygon 의 baseStyle 로 *이전* polygon 을 복원해, 1위(빨강) → 4위(파랑) 순으로
 * 누르면 1위가 파랑이 되고 zone 에 없던 점선까지 생겼다.
 * 모듈 최상단에 둔다. 2026-09-21 — 행정동 경계가 비대화형이 되어 이제 zone effect 만 쓴다.
 */
type Restorable = L.Polygon & { __baseStyle?: L.PathOptions }

/** 컨테이너를 페이지 본문 영역에 맞춰 꽉 채운다. 세부 패딩은 `minwon.css` 가 맡음. */
/** 범례 제목의 지표 이름 — 시안 `{hotspot:'민원 건수', enforce:'단속 건수', demand:'부족률'}`. */
const LEGEND_METRIC: Record<LayerView, string> = {
  'most-complaint': '민원 건수',
  enforcement: '단속 건수',
  shortage: '부족률',
}

const MAP_STYLE: CSSProperties = { width: '100%', height: '100%' }

const NO_KEY_STYLE: CSSProperties = {
  ...MAP_STYLE,
  background: 'var(--cool-neutral-99)',
  color: 'var(--text-alternative)',
  fontSize: 14,
}

/** 등급 라벨 → 공용 `.badge--*` 수식어 (`dashboard.css`). */
const BADGE_MODIFIER: Record<RiskGrade, string> = {
  심각: 'badge--severe',
  경고: 'badge--warn',
  주의: 'badge--caution',
}

/**
 * 분석레이어 hotspot zone 의 rank 백분위 → 색상 + 등급 라벨.
 * 2026-09-21 — 팝업 배지가 색과 어긋나지 않도록 **같은 분기에서** `label` 을 함께
 * 돌려준다. 50% 초과는 대응하는 등급이 없어 `label: null`(배지 생략).
 * 2026-09-30 — 임계값 분기를 자체 보유하지 않고 공용
 * `pickRiskGrade` 에 위임한다(지도·순위 카드 3종이 같은 등급을 말한다).
 * 🔴 **동작·반환 계약 불변**: `{color, fill, label}` 3필드 · 등급 없으면 무색
 * (`#94A3B8` + `fill: 'transparent'` + `label: null`) · `totalZones <= 0` 이면 종전처럼 `심각`.
 */
function pickZoneStyle(
  rank: number,
  totalZones: number,
): { color: string; fill: string; label: RiskGrade | null } {
  // 종전 분기 그대로 — zone 수를 모를 때는 `심각` 으로 칠한다(`pickRiskGrade` 는 여기서 null).
  const grade: RiskGrade | null = totalZones <= 0 ? '심각' : pickRiskGrade(rank, totalZones)
  if (grade === null) return { color: NEUTRAL_COLOR, fill: 'transparent', label: null }
  const color = RISK_GRADE_COLOR[grade]
  return { color, fill: color, label: grade }
}

/** 팝업 한 행 — 라벨 왼쪽, 굵은 값 오른쪽. 값은 textContent 로만 넣는다. 값 요소를 돌려준다(색 수식용). */
function appendPopupRow(parent: HTMLElement, label: string, value: string): HTMLElement {
  const row = document.createElement('div')
  row.className = 'minwon-map__popup__row'
  const k = document.createElement('span')
  k.className = 'minwon-map__popup__label'
  k.textContent = label
  const v = document.createElement('span')
  v.className = 'minwon-map__popup__value'
  v.textContent = value
  row.append(k, v)
  parent.appendChild(row)
  return v
}

/** 요금 여부 → 표기·색 수식 클래스. `null` 은 호출부가 행을 생략한다. */
const PARKING_FEE: Record<'Y' | 'N', { text: string; modifier: string }> = {
  Y: { text: '유료', modifier: 'minwon-map__popup__value--paid' },
  N: { text: '무료', modifier: 'minwon-map__popup__value--free' },
}

/**
 * 공영주차장 마커 클릭 팝업 본문 — 시안: 제목 / `주소: {주소}` /
 * 행정동 → 주차장 유형 → 주차 면수 → 요금 행 (순서 고정).
 * 🔴 XSS — 이름·주소·동은 원천(tb_pblpklt) 외부 입력이다. DOM API + textContent 로만 넣는다.
 * 행정동·주차장 유형·면수·요금이 비면 그 행을 생략한다(`—` 로 자리만 채우지 않는다).
 */
function buildParkingPopup(p: ParkingLotItem): HTMLElement {
  const root = document.createElement('div')
  root.className = 'minwon-map__popup__body minwon-map__popup--parking'

  const head = document.createElement('div')
  head.className = 'minwon-map__popup__head'
  const title = document.createElement('span')
  title.className = 'minwon-map__popup__title'
  title.textContent = p.pkltNm ?? ''
  head.appendChild(title)
  root.appendChild(head)

  const addr = document.createElement('div')
  addr.className = 'minwon-map__popup__addr'
  const addrVal = document.createElement('b')
  addrVal.className = 'minwon-map__popup__addr-value'
  addrVal.textContent = p.pkltAddr ?? ''
  addr.append('주소: ', addrVal)
  root.appendChild(addr)

  const rows = document.createElement('div')
  rows.className = 'minwon-map__popup__rows'
  // 🔴 순서 고정: 주소(위 __addr 블록) → 행정동 → 주차장 유형 → 주차 면수 → 요금.
  // 값이 비면 '—' 로 자리를 채우지 않고 행을 생략한다 (면수·요금이 이미 따르는 규약).
  if (p.emdNm && p.emdNm.trim()) appendPopupRow(rows, '행정동', p.emdNm)
  if (p.pkltTypeNm) appendPopupRow(rows, '주차장 유형', p.pkltTypeNm)
  if (p.pkltCpcty != null) appendPopupRow(rows, '주차 면수', `${p.pkltCpcty.toLocaleString('ko-KR')}면`)
  if (p.prkCrgYn != null) {
    const fee = PARKING_FEE[p.prkCrgYn]
    if (fee) appendPopupRow(rows, '요금', fee.text).classList.add(fee.modifier)
  }
  root.appendChild(rows)
  return root
}

/**
 * polygon 클릭 팝업 본문.
 * 🔴 XSS — zoneNm·유형명은 외부 입력이다. HTML 문자열 조립(inner-HTML 대입) 금지.
 * DOM API 로만 만들고 값은 textContent 로 넣는다 (기존 tooltip 과 같은 규칙).
 * 값이 `null` 인 행은 그리지 않는다(`—` 로 자리만 채우지 않는다).
 */
function buildZonePopup(
  z: HotspotRankingZone,
  view: LayerView,
  color: string,
  label: RiskGrade | null,
): HTMLElement {
  const root = document.createElement('div')
  root.className = 'minwon-map__popup__body'

  // ── 헤더: ● 색 dot + 지역명 + 등급 배지 ──
  const head = document.createElement('div')
  head.className = 'minwon-map__popup__head'
  const dot = document.createElement('span')
  dot.className = 'minwon-map__popup__dot'
  dot.style.background = color // 색은 우리 상수(hex)라 외부 입력이 아니다
  const title = document.createElement('span')
  title.className = 'minwon-map__popup__title'
  title.textContent = z.zoneNm ?? ''
  head.append(dot, title)
  if (label) {
    // 50% 초과 zone 은 대응 등급이 없다 — 빈 배지·임의 문구를 만들지 않는다.
    const badge = document.createElement('span')
    badge.className = `badge ${BADGE_MODIFIER[label]} minwon-map__popup__badge`
    badge.textContent = label
    head.appendChild(badge)
  }
  root.appendChild(head)

  // ── 행: view 별 구성 ──
  const rows = document.createElement('div')
  rows.className = 'minwon-map__popup__rows'
  const fmt = (n: number) => n.toLocaleString('ko-KR')
  if (view === 'most-complaint') {
    appendPopupRow(rows, '총 민원', `${fmt(z.cvlcptCnt)}건`)
    // 2026-09-21 — 건수 많은 유형 **상위 2개만**. 0건 유형은 제외.
    // 🔴 복사본을 정렬한다 — 원본 `sort()` 는 zones state 를 제자리에서 오염시킨다.
    //    Array.prototype.sort 는 ES2019+ 안정 정렬이라 동률은 원래 순서(백엔드 sort_seq)를 유지한다.
    const top2 = (z.typeCnts ?? [])
      .filter((t) => t && t.cvlcptCnt > 0)
      .sort((a, b) => b.cvlcptCnt - a.cvlcptCnt)
      .slice(0, 2)
    for (const t of top2) appendPopupRow(rows, t.cvlcptTypeNm, `${fmt(t.cvlcptCnt)}건`)
  } else if (view === 'enforcement') {
    appendPopupRow(rows, '단속 건수', `${fmt(z.crdnCnt)}건`)
    appendPopupRow(rows, '민원 건수', `${fmt(z.cvlcptCnt)}건`)
  } else if (view === 'shortage') {
    if (z.shortageRatio != null) {
      appendPopupRow(rows, '부족률', `${Math.round(z.shortageRatio * 100)}%`)
    }
    // 🔴 주차 수요는 면(面)+건(件) 합이라 단위를 붙이지 않는다.
    if (z.demandIndex != null) appendPopupRow(rows, '주차 수요', fmt(z.demandIndex))
    // 0 은 정상값(null 아님) — `0면` 으로 그린다.
    if (z.pkltCpctySum != null) appendPopupRow(rows, '공급 면수', `${fmt(z.pkltCpctySum)}면`)
  }
  root.appendChild(rows)
  return root
}

/**
 * zone 중심점 라벨 마커 아이콘 — 18px 원 + 오른쪽 흰 pill 라벨 `{동} {값}`.
 * 🔴 XSS — divIcon 의 `html` 에 문자열을 넣지 않는다(HTML 로 파싱된다). Leaflet 1.9 는
 * `html: HTMLElement` 를 받으므로 DOM API 로 만들고 zoneNm 은 textContent 로 넣는다.
 * `className: ''` — Leaflet 기본 `.leaflet-div-icon`(흰 박스·테두리)을 붙이지 않는다(시안 동일).
 */
function buildZoneMarkerIcon(z: HotspotRankingZone, view: LayerView, color: string): L.DivIcon {
  const el = document.createElement('div')
  el.className = 'minwon-map__zone-marker'
  const dot = document.createElement('span')
  dot.className = 'minwon-map__zone-marker__dot'
  dot.style.background = color // 색은 우리 상수(hex)라 외부 입력이 아니다
  const pill = document.createElement('span')
  pill.className = 'minwon-map__zone-marker__label'
  const nm = document.createElement('span')
  nm.textContent = z.zoneNm ?? ''
  pill.appendChild(nm)
  // 값: 민원 다발 `N건` · 단속 집중 `N건` · 수요 부족 `부족률 N%`(null 이면 값 생략).
  let value: string | null
  if (view === 'shortage') {
    value = z.shortageRatio != null ? `부족률 ${Math.round(z.shortageRatio * 100)}%` : null
  } else {
    const n = view === 'enforcement' ? z.crdnCnt : z.cvlcptCnt
    value = `${n.toLocaleString('ko-KR')}건`
  }
  if (value != null) {
    const val = document.createElement('span')
    val.className = 'minwon-map__zone-marker__value'
    val.textContent = value
    pill.appendChild(val)
  }
  el.append(dot, pill)
  return L.divIcon({ className: '', html: el, iconSize: [18, 18], iconAnchor: [9, 9] })
}

export interface MinwonMapProps {
  /** 컨테이너 크기 보정 트리거 카운트. 페이지가 본 컴포넌트를 가리는
   *  패널을 토글할 때(`invalidateSize++`) 부모에서 알려준다. */
  invalidateSize?: number
  /** 범례 제목의 기간 문구 — 페이지가 §2 조회 조건으로 만든다(`periodText.periodLabel`). */
  periodLabel?: string
  /** §4 분석레이어 view (라벨 N 값 결정용: enforcement 면 crdnCnt, 그 외 cvlcptCnt). */
  view?: LayerView
  /** 분석레이어 hotspot zone 목록. */
  zones?: HotspotRankingZone[]
  /** §4 토글 state. */
  toggles?: LayerToggles
  /** 공영주차장 마커 목록. `toggles.publicParking=true` 일 때만 표시. */
  parkingLots?: ParkingLotItem[]
  /** 행정동 경계 GeoJSON. `toggles.adminBoundary=true` 일 때만 표시. */
  adminDistricts?: AdminDistrictFeatureCollection | null
  /**
   * 순위 카드(§7)에서 고른 읍·면·동 코드. `null` 이면 선택 없음.
   *
   * 🔴 **강조·팝업을 이 prop 때문에 새로 만들지 않는다.** zone redraw 루프가 채운
   * `zoneClickRef` 레지스트리에서 그 zone 의 `onZoneClick`(지도 polygon·마커 클릭이 부르는
   * **바로 그 함수**)을 꺼내 부르는 것이 전부다 — 복제하면 강조 해제가 두 곳으로 갈린다.
   */
  selectedEmdCd?: string | null
  /**
   * 같은 행을 다시 눌렀을 때도 반응하게 하는 **단조 증가 nonce**.
   * `selectedEmdCd` 만으로는 값이 같아 호출 effect 가 돌지 않는다 — 부모가 클릭마다 +1 한다.
   * (레이어·기간·지역 변경으로 선택이 `null` 로 풀려도 이 값은 되돌리지 않는다.)
   */
  selectedEmdSeq?: number
  /**
   * 선택 적용 결과 보고. `found=false` 는 레지스트리 미스(`zones` 에 없거나 등급 밖이라
   * 그리지 않음) 또는 앵커(`mrkLat`/`mrkLot`) 없음 — 지도에서 활성화하지 못했다는 뜻이다.
   * 🔴 판정을 여기서 하는 이유: `zones` 에 있어도 등급 밖이면 그리지 않아 페이지는 알 수 없다.
   *    안내를 띄울지는 부모가 정한다(벨 요청에만 — 카드 클릭은 종전대로 무동작).
   */
  onSelectResult?: (emdCd: string, seq: number, found: boolean) => void
  /** §4 분석레이어 라디오 변경 핸들러. 미지정 시 LayerCard 내부에서 호출해도 무시 (읽기 전용). */
  onViewChange?: (next: LayerView) => void
  /** 공영주차장 토글 변경 핸들러. */
  onTogglePublicParking?: (next: boolean) => void
  /** 행정동 경계 토글 변경 핸들러. */
  onToggleAdminBoundary?: (next: boolean) => void
  /**
   * §4 지도 표시 방식. 상태는 `ComplaintsPage` 가 들고, LayerCard 세그먼트로 넘긴다.
   * `heatmap` 이면 zone polygon·라벨 대신 히트맵을 그린다(상단 설명 주석 "히트맵 모드").
   */
  displayMode?: DisplayMode
  /** 표시 방식 세그먼트 변경 핸들러. */
  onDisplayModeChange?: (next: DisplayMode) => void
  /**
   * 히트맵 좌표 칸 — 부모가 `heatmap` 모드일 때만 조회해 넘긴다.
   * `emd` 모드에서는 쓰지 않는다(히트맵 effect 가 레이어를 떼기만 한다).
   */
  heatPoints?: HotspotHeatmapPoint[]
}

/** 히트맵 모드 리스트·벨 포커스의 `fitBounds` 여백(px) — 동 경계가 카드에 붙지 않게. */
const HEATMAP_FOCUS_PADDING: L.PointExpression = [40, 40]

/**
 * 히트맵 강도 상한 — 칸 건수 `cnt` 의 P95(최근접 순위법), 최소 1.
 * 빈 배열이면 1. 원본을 정렬하지 않는다(props 배열 오염 방지).
 */
function heatP95(points: ReadonlyArray<HotspotHeatmapPoint>): number {
  if (!points.length) return 1
  const cnts = points.map((p) => p.cnt).sort((a, b) => a - b)
  const idx = Math.min(cnts.length - 1, Math.ceil(cnts.length * 0.95) - 1)
  return Math.max(1, cnts[idx])
}

/** GeoJSON Polygon / MultiPolygon 의 ring → leaflet LatLng[] 변환. */
function ringToLatLngs(ring: readonly (readonly [number, number])[]): L.LatLng[] {
  // GeoJSON 좌표 순서는 [lng, lat]. leaflet 은 [lat, lng].
  const out: L.LatLng[] = []
  for (const [lng, lat] of ring) out.push(L.latLng(lat, lng))
  return out
}

/**
 * hotspot zone 의 {@code bndryGeoJson} (PostGIS ST_AsGeoJSON 결과 JSON 문자열) 을
 * leaflet 의 polygon 입력 형식으로 변환. (2026-09-08 추가)
 *
 * <p>반환 형식:
 * <ul>
 *   <li>{@code Polygon} → {@code L.LatLng[][]} (rings)</li>
 *   <li>{@code MultiPolygon} → {@code L.LatLng[][][]} (polygons × rings)</li>
 *   <li>그 외 / 파싱 실패 / null → {@code null} (호출부는 polygon 만 생략한다)</li>
 * </ul>
 *
 * <p>호출부(zone effect) 는 결과가 있으면 {@code L.polygon} 입력으로 넘기고, 없으면 polygon 을
 * 생략한다. 중심점 라벨 마커는 결과와 무관하게 항상 그린다(종전 circleMarker fallback 폐지).
 */
function parseBndryGeoJson(raw: string | null | undefined): L.LatLng[][] | L.LatLng[][][] | null {
  if (!raw || typeof raw !== 'string') return null
  let geom: { type?: string; coordinates?: unknown } | null = null
  try {
    geom = JSON.parse(raw)
  } catch {
    return null
  }
  if (!geom || typeof geom !== 'object') return null
  const coords = geom.coordinates
  if (!Array.isArray(coords)) return null
  if (geom.type === 'Polygon') {
    // Polygon: [ [ [lng,lat], ... ], [hole] ]
    const rings: L.LatLng[][] = []
    for (const ring of coords) {
      if (!Array.isArray(ring)) return null
      rings.push(ringToLatLngs(ring as [number, number][]))
    }
    return rings
  }
  if (geom.type === 'MultiPolygon') {
    // MultiPolygon: [ Polygon, Polygon, ... ]
    const polys: L.LatLng[][][] = []
    for (const poly of coords) {
      if (!Array.isArray(poly)) return null
      const rings: L.LatLng[][] = []
      for (const ring of poly) {
        if (!Array.isArray(ring)) return null
        rings.push(ringToLatLngs(ring as [number, number][]))
      }
      polys.push(rings)
    }
    return polys
  }
  return null
}

export default function MinwonMap({
  invalidateSize = 0,
  view = 'most-complaint',
  periodLabel,
  zones = [],
  toggles = { publicParking: false, adminBoundary: false },
  parkingLots = [],
  adminDistricts = null,
  selectedEmdCd = null,
  selectedEmdSeq = 0,
  onSelectResult,
  onViewChange,
  onTogglePublicParking,
  onToggleAdminBoundary,
  displayMode = 'emd',
  onDisplayModeChange,
  heatPoints = [],
}: MinwonMapProps) {
  const elRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<L.Map | null>(null)
  /** 배경지도 전환기(`common/mapTiles`). 지도와 생명주기가 같다. */
  const basemapRef = useRef<BasemapController | null>(null)
  /** 분석레이어 hotspot zone layer group. zones 가 바뀔 때마다 clear + redraw. */
  const zoneLayerRef = useRef<L.LayerGroup | null>(null)
  /** 분석레이어 zone 라벨 마커 클러스터 그룹. zone layer 와 함께 clear + redraw. */
  const zoneClusterRef = useRef<L.MarkerClusterGroup | null>(null)
  /** 행정동 경계 polygon layer group. adminDistricts / 토글 state 가 바뀔 때마다 재구성. */
  const boundaryLayerRef = useRef<L.LayerGroup | null>(null)
  /**
   * 공영주차장 마커 클러스터 그룹 (종전 LayerGroup 대체). parkingLots / 토글 state 가
   * 바뀔 때마다 clearLayers + 재구성. 🔴 zone 라벨 클러스터(`zoneClusterRef`)와 별개 그룹이다.
   */
  const parkingLayerRef = useRef<L.MarkerClusterGroup | null>(null)
  /**
   * 현재 highlight 된 polygon layer 보관 (2026-09-08 추가).
   * polygon click 시 다른 polygon 의 highlight 해제 + 새 polygon 외곽선을 따라 highlight.
   * zone redraw 시 clearLayers 가 모든 layer 를 정리하므로 highlight 도
   * 자연 해제되지만, 같은 layer group 안에서 다른 polygon 클릭 시 이전 highlight 명시적 해제 필요.
   * highlight 는 {@link setStyle} 로 외곽선 불투명·채움 농도만 올려 표현한다(두께는 기본값 유지 — 2026-09-21).
   */
  const highlightedZoneLayerRef = useRef<L.Polygon | null>(null)
  /**
   * `emdCd` → `{ open, lat, lot }` 레지스트리 (11-4 에서 값 확장).
   *
   * 순위 카드(§7) 행 클릭이 **지도 클릭과 같은 경로**를 타게 하는 유일한 연결점이다 —
   * 강조(`highlightZonePolygon`)·팝업 생성·`openPopup` 은 `open`(= `onZoneClick`) 한 곳에만 있다.
   * zone redraw 루프가 매번 `clear()` 후 다시 채운다(옛 클로저는 제거된 레이어를 가리킨다).
   *
   * 🔴 `lat`/`lot` 은 **호출 effect 의 `setView`(확대) 전용**이다. 값을 `() => void`
   *    대신 객체로 넓힌 이유가 이것뿐이다 — `onZoneClick` 은 polygon·마커·리스트 **셋이 공유**하므로 그 안에
   *    이동·확대를 넣으면 지도를 직접 클릭할 때도 화면이 튄다(회귀). 좌표는 팝업 앵커와 같은 값
   *    (`mrkLat`/`mrkLot` = `ST_PointOnSurface`)이고, `null` 이면 이동하지 않는다.
   *
   * 🔴 등급 밖(`label === null`) zone 은 루프가 `continue` 로 건너뛰므로 레지스트리에도 없다.
   *    순위 카드도 같은 기준(`pickRiskGrade` 단일 출처)이라 **리스트의 모든 행이 여기
   *    있다**는 전제가 성립한다. 둘 중 한쪽 기준이 바뀌면 이 전제가 깨진다.
   */
  const zoneClickRef = useRef<Map<string, { open: () => void; lat: number | null; lot: number | null }>>(
    new Map(),
  )
  /**
   * 히트맵 레이어. 마운트 effect 가 **만들기만** 하고(지도에 add 하지 않는다), add/remove 는
   * 히트맵 effect 가 모드로 정한다. cleanup 에서 remove + `null`(StrictMode 이중 마운트 안전).
   */
  const heatLayerRef = useRef<HeatLayer | null>(null)
  const [base, setBase] = useState<BaseMapKind>('base')

  // ── 지도 생성 (마운트 1회) ──
  useEffect(() => {
    if (!MAP_KEY) return
    const el = elRef.current
    if (!el) return

    const map = L.map(el, {
      center: JEJU_CENTER,
      zoom: JEJU_ZOOM,
      zoomControl: false,
      // 2026-09-08 갱신: 인터랙션 결정 — 스크롤 휠 줌 on (사용자 확정),
      // 더블클릭 줌 off. polygon 위 더블클릭이 의도치 않은 줌 인을
      // 일으키는 것 방지. 휠 줌은 지도 어디서든 줌 인/아웃 가능 — polygon 위도 의도치 않은
      // 동작이 아니므로 그대로 둠. Leaflet 기본값이 scrollWheelZoom=true 라 명시하지 않음.
      doubleClickZoom: false,
    })
    L.control.zoom({ position: 'topright' }).addTo(map)
    fixLeafletAnchors(map)

    basemapRef.current = createBasemapController(map) // 일반지도 1장을 붙인다

    // polygon / marker 를 담을 layer group 생성 — 맵에 add 한 뒤 clear + redraw 만 한다.
    const zoneLayer = L.layerGroup().addTo(map)
    const boundaryLayer = L.layerGroup().addTo(map)
    // 공영주차장 클러스터 — 시안 옵션(maxClusterRadius 50). 아이콘은 보라 P + 개수 배지.
    const parkingLayer = new MarkerClusterGroup({
      maxClusterRadius: 50,
      showCoverageOnHover: false,
      iconCreateFunction: createParkingClusterIcon,
    }).addTo(map)
    // 라벨 마커 클러스터 — 시안 옵션 그대로. 마운트 1회 생성 후 redraw 마다 clearLayers 로 재사용.
    const zoneCluster = new MarkerClusterGroup({ maxClusterRadius: 44, showCoverageOnHover: false }).addTo(map)
    zoneLayerRef.current = zoneLayer
    zoneClusterRef.current = zoneCluster
    boundaryLayerRef.current = boundaryLayer
    parkingLayerRef.current = parkingLayer
    heatLayerRef.current = createHeatLayer()

    mapRef.current = map

    // StrictMode 이중 마운트/언마운트 대비 — 정리하지 않으면 같은 컨테이너에
    // Leaflet 이 "Map container is already initialized" 로 죽는다.
    return () => {
      heatLayerRef.current?.remove()
      heatLayerRef.current = null
      map.remove()
      mapRef.current = null
      basemapRef.current = null
      zoneLayerRef.current = null
      zoneClusterRef.current = null
      boundaryLayerRef.current = null
      parkingLayerRef.current = null
    }
  }, [])

  // ── 배경지도 전환 ──
  // 마운트 effect 보다 나중에 도는 것이 보장된다(같은 컴포넌트, 선언 순서). 항공 레이어는
  // 컨트롤러가 처음 `aerial` 일 때 만든다 — 안 누른 사용자는 위성 타일을 받지 않는다.
  useEffect(() => {
    basemapRef.current?.apply(base)
  }, [base])

  // ── 분석레이어 hotspot zone redraw ──
  useEffect(() => {
    const layer = zoneLayerRef.current
    const cluster = zoneClusterRef.current
    if (!layer || !cluster) return
    layer.clearLayers()
    cluster.clearLayers() // 라벨 마커(클러스터) — polygon 과 함께 비운다
    // 🔴 팝업은 map 의 자식이라 clearLayers 로 안 닫힌다 — 라디오·기간 변경 시 옛 팝업 제거.
    //    닫히면 popup 'remove' 가 돌지만 가드(ref === 그 polygon)가 아래 ref 초기화와 무관하게 안전하다.
    mapRef.current?.closePopup()
    // redraw 로 옛 polygon 이 모두 제거됐다 — ref 가 사라진 레이어를 가리키지 않게 비운다.
    highlightedZoneLayerRef.current = null
    // 순위 카드용 레지스트리도 같은 이유로 비운다 — 옛 클로저를 부르면
    // 이미 제거된 polygon 을 강조하게 된다. 아래 루프가 이번 zone 들로 다시 채운다.
    zoneClickRef.current.clear()
    // 히트맵 모드는 **위 정리까지만** 하고 그리지 않는다(polygon·라벨·레지스트리 모두 빈 채).
    //    🔴 return 을 정리 **뒤**에 둬야 모드 전환 시 옛 polygon·팝업·강조가 남지 않는다. 히트맵 자체는
    //    아래 별도 effect 가 붙인다.
    if (displayMode === 'heatmap') return
    // 2026-09-08 추가 — polygon click 시 외곽선 따라 highlight (bounding box 가 아닌 stroke 강조).
    // 2026-09-21 — 이전 polygon 은 **자기 `__baseStyle`** 로 복원한다.
    // 2026-09-21 — 재클릭 토글 제거. 해제는 팝업 `remove` 가 맡는다(✕·빈 곳·ESC·autoClose).
    const highlightZonePolygon = (poly: Restorable, baseStyle: L.PathOptions): void => {
      const prev = highlightedZoneLayerRef.current as Restorable | null
      if (prev && prev.__baseStyle) prev.setStyle(prev.__baseStyle) // 언제나 '자기 값' 으로 복원
      poly.__baseStyle = baseStyle
      // 🔁 2026-09-21 사용자 요청 — 두께 증가(weight 4)를 없애고 채움도 낮췄다(.55 → .3).
      //    두께는 기본값 그대로, 외곽선 불투명(opacity 1)과 채움 농도만으로 선택을 드러낸다.
      poly.setStyle({ ...baseStyle, opacity: 1, fillOpacity: 0.3, dashArray: undefined })
      poly.bringToFront()
      highlightedZoneLayerRef.current = poly
    }
    if (!zones.length) return

    const totalZones = zones.length
    // 2026-09-04 갱신: `view=least-complaint` 폐기 — 색상 미적용 분기 제거.
    // 모든 view 가 SQL 의 ROW_NUMBER() 로 rank 매겨지므로 (most/enforcement 모두) 색상 코딩 적용.

    for (let i = 0; i < zones.length; i++) {
      const z = zones[i]
      // 2026-09-04 갱신: 모든 view 가 SQL 의 ROW_NUMBER() 로 rank 매김 (placeholder 없음).
      const rank = z.rank > 0 ? z.rank : i + 1
      // 2026-09-08 갱신: Fill = Stroke 동일 값 — 현재 marker 의 단일 색 정책 유지.
      // weight: 1 (행정동 경계 토글의 점선과 시각 구분).
      const { color, fill, label } = pickZoneStyle(rank, totalZones)
      // ① 등급(심각·경고·주의) 밖 zone 은 polygon·마커 모두 그리지 않는다.
      //    처음에는 민원 다발만이었으나 2026-09-21 에 **세 view 모두**로 확장 —
      //    범례(LegendCard)가 view 와 무관하게 3단계만 보이므로 지도도 맞춘다.
      //    🔁 2026-10-02 — 종전엔 shortage 만 백엔드가 Jenks '심각' 을 먼저 걸러
      //    그 안에서 다시 좁혀지는 2단이었다. 이제 **세 view 모두** 전 zone 을 받아 이 한 층만 탄다.
      if (label === null) continue

      // 시안 zone polygon `{ weight:2, opacity:.7, fillOpacity:.12 }` — 연하게 칠하고 테두리로
      // 경계를 드러낸다. 종전 `fillOpacity:.45 · weight:1` 은 색이 지도(도로·지명)를 덮었다.
      // 🔴 `opacity` 를 여기 둬야 강조(`opacity:1`) 해제 시 자기 값(.7)으로 복원된다(`__baseStyle`).
      const baseStyle: L.PathOptions = { color, fillColor: fill, fillOpacity: 0.12, weight: 2, opacity: 0.7 }
      const lat = z.mrkLat
      const lot = z.mrkLot
      const hasAnchor = lat != null && lot != null

      // polygon — bndryGeoJson 이 있을 때만. 없으면(bndry_geom IS NULL·admdst 미등록) 마커만 그린다.
      const poly = parseBndryGeoJson(z.bndryGeoJson)
      const polygonLayer: Restorable | null = poly
        ? L.polygon(poly, {
            ...baseStyle,
            // 더블클릭 줌은 map 옵션의 doubleClickZoom:false 로 글로벌 차단.
            interactive: true,
          })
        : null

      // 🔴 팝업은 클릭 시 **지연 생성**(최초 1회). 레이어 바인딩 팝업(bind-Popup) 은 쓰지 않는다 —
      //    polygon·마커가 한 팝업을 공유하고, 강조·해제를 한 곳에서 다루기 위해 지도에 직접 띄운다.
      //    앵커는 마커 좌표(ST_PointOnSurface) — 중심점과 팝업 위치가 일치하고 polygon 없는 zone 도 같은 경로.
      let popup: L.Popup | null = null
      /** zone 단위 클릭 — polygon·마커가 공유한다. polygon 이 없으면 강조 없이 팝업만. */
      const onZoneClick = () => {
        if (!hasAnchor) return // 앵커가 없으면 팝업을 띄울 자리가 없다
        if (polygonLayer) highlightZonePolygon(polygonLayer, baseStyle)
        if (!popup) {
          popup = L.popup({
            className: 'minwon-map__popup',
            closeButton: true, // ④ 시안(레이어 바인딩 팝업) 기본값 — 기본 content 우측 margin 24px 가 ✕ 자리
            minWidth: 168,     // 시안 최소폭
            maxWidth: 260,
            autoPan: true,
          })
            .setContent(buildZonePopup(z, view, color, label))
            .setLatLng([lat, lot])
          // 🔴 닫힘 뒷정리는 여기 한 곳 — ✕·빈 곳 클릭(preclick)·ESC·autoClose·redraw 를 모두 덮는다.
          // 🔴 가드: 이 zone 의 polygon 이 **지금 강조 대상일 때만** 해제한다. 다른 zone 클릭 시 autoClose 가
          //    새 zone 강조 **뒤에** 이전 팝업을 제거하므로, 무조건 해제하면 새 강조까지 지워진다(§5-1).
          popup.on('remove', () => {
            if (polygonLayer && highlightedZoneLayerRef.current === polygonLayer) {
              if (polygonLayer.__baseStyle) polygonLayer.setStyle(polygonLayer.__baseStyle)
              highlightedZoneLayerRef.current = null
            }
          })
        }
        mapRef.current?.openPopup(popup)
      }
      // 순위 카드(§7) 행 클릭용 등록 — polygon·마커에 붙이는 **같은 함수**다.
      // ⚠️ 알려진 한계: `mrkLat`/`mrkLot` 이 `null` 이면 위 함수가 early return 하므로 카드 행을
      //    눌러도 아무 일이 없다(지도 클릭도 같다). `bndry_geom`·`ctr_lat` 이 모두 없는 동에서만
      //    생긴다(2026-09-30 로컬 실측 0건).
      // 🔴 `lat`/`lot` 은 호출 effect 의 `setView`(확대 — 11-4) 용이다 — `onZoneClick` 안에서는 쓰지 않는다.
      if (z.emdCd) zoneClickRef.current.set(z.emdCd, { open: onZoneClick, lat, lot })

      if (polygonLayer) {
        polygonLayer.on('click', onZoneClick)
        layer.addLayer(polygonLayer)
      }
      // ⑦ 중심점 라벨 마커 — 항상(좌표가 있을 때). 기존 fallback circleMarker 를 대체한다.
      // zone layer 가 아니라 클러스터 그룹에 넣는다. 클릭은 마커 레이어 이벤트라 클러스터가
      //      DOM 을 붙였다 떼도 그대로 동작한다(팝업은 지도에 직접 띄우고 앵커는 마커 좌표).
      if (hasAnchor) {
        const marker = L.marker([lat, lot], {
          icon: buildZoneMarkerIcon(z, view, color),
          riseOnHover: true,
        })
        marker.on('click', onZoneClick)
        cluster.addLayer(marker)
      }
    }
    // 🔴 `displayMode` 를 deps 에 둔다 — 빠지면 모드 전환 시 polygon 이 남거나 돌아오지 않는다.
  }, [zones, view, displayMode])

  // ── 히트맵 레이어 add/remove · 데이터 ──
  // `heatmap` 이면 붙이고 칸·강도 상한을 넘긴다. `emd` 면 뗀다(데이터는 두어도 그려지지 않는다).
  // 🔴 강도 상한 `max` 는 칸 건수 **P95**(최소 1) — 최댓값이면 상습 지점 한 칸 때문에 나머지가 전부
  //    파랑이 된다. 계산 위치는 여기 한 곳이다.
  useEffect(() => {
    const map = mapRef.current
    const heat = heatLayerRef.current
    if (!map || !heat) return
    if (displayMode !== 'heatmap') {
      heat.remove()
      return
    }
    if (!map.hasLayer(heat)) heat.addTo(map)
    heat.setData(heatPoints, heatP95(heatPoints))
  }, [displayMode, heatPoints])

  // ── 순위 카드(§7) 행 클릭 → 지도 polygon 강조 + 팝업 ──
  // 🔴 여기서 강조·팝업을 **만들지 않는다**. 위 redraw 루프가 채운 레지스트리에서 그 zone 의
  //    `onZoneClick`(지도 클릭이 부르는 바로 그 함수)을 꺼내 부르는 것이 전부다.
  // 🔴 deps 에 `zones`·`view` 를 둬야 **redraw 뒤에** 돈다 — 선언 순서상 redraw effect 가 먼저
  //    돌아 레지스트리를 새로 채운 다음이라야 옛 클로저를 부르지 않는다.
  // `selectedEmdSeq`(nonce) 덕에 **같은 행 재클릭**도 반응한다. 이미 팝업이 열려 있으면 Leaflet
  //    `Popup.openOn` 이 `map.hasLayer(this)` 로 걸러 아무 것도 하지 않아 강조가 유지되고,
  //    사용자가 ✕ 로 닫아 둔 상태면 다시 열린다.
  // ── 선택한 동을 지도 가운데로 + 확대 (2026-09-30 사용자 요청) ──
  // 2026-10-19 — 리스트 클릭은 공용 상수(mapTiles)의 민원현황 키 값까지 확대한다.
  //    지도 직접 클릭(polygon·마커)은 여전히 zoom 불변이다. 종전 11-4 의 "`panTo` 만 쓴다(`setView`·
  //    `flyTo` 금지) — 사용자 배율은 어떤 경우에도 바뀌지 않는다" 는 이 개정으로 폐기됐다.
  //    확대만 한다(`focusZoom` — 사용자가 이미 더 들어가 있으면 축소하지 않는다). `flyTo` 는 여전히
  //    쓰지 않는다(원거리에서 축소 후 재확대하는 곡선 애니메이션). `duration` 을 주지 않는 것은 종전
  //    기본값(0.25초)을 같은 zoom 경로(panBy)에서 그대로 유지하기 위해서다.
  // 🔴 이동·확대는 **이 effect 안에만** 둔다 — `onZoneClick` 은 polygon·마커·리스트 셋이 공유하므로
  //    거기에 넣으면 지도를 직접 클릭할 때도 화면이 튀고 배율이 바뀐다(회귀). 리스트 클릭만 움직인다.
  // 🔴 순서는 `open()` → `setView` 다. 팝업의 `autoPan: true` 가 먼저 화면을 조금 밀 수 있는데,
  //    `setView` 를 **뒤에** 둬야 최종 위치가 우리 값으로 확정된다(반대면 autoPan 이 중앙을 흐트러뜨린다).
  // 🔴 팝업은 이동 호출 직후 **동기로** 연 상태를 유지한다 — `moveend` 를 기다렸다 열지 **않는다**.
  //    `moveend` 는 경로에 따라 **동기로** 발화한다(offset 0 이면 `panBy` 가 즉시, zoom 차이 >4 면
  //    `_resetView` 가 즉시) — `setView` **뒤에** 리스너를 걸면 이미 지나가 놓쳐 팝업이 안 열린다.
  //    `zoomend` 는 이미 목표 zoom 이면 아예 오지 않는다. 위치가 어긋나 기다려야 한다면
  //    **`setView` 호출 전에** `map.once('moveend', …)` 를 등록한다.
  // 앵커는 팝업과 같은 좌표(`mrkLat`/`mrkLot` = `ST_PointOnSurface`)다. `null` 이면 `open()` 만 하고
  //    이동하지 않는다(11-3 의 한계 그대로 — 그 zone 은 팝업도 뜨지 않는다).
  // 결과(found)를 부모에 보고한다. 콜백은 ref 로 들고 deps 에 넣지 않는다
  //    (부모가 인라인 함수를 넘겨도 이 effect 가 다시 돌아 팝업을 또 열지 않게).
  // ⚠️ `!zones.length` 분기는 보고하지 않는다 — "아직 안 그림" 과 "0건" 을 여기서는 구분할 수 없다.
  //    0건 판정은 로딩 완료를 아는 부모가 한다.
  // — 히트맵 모드면 레지스트리(비어 있다)가 아니라 `zones` 에서 동을 찾아 MBR 로만
  //    이동한다(강조·팝업 없음). 🔴 `displayMode` 는 deps 에 **넣지 않는다** — 넣으면 모드 버튼만 눌러도
  //    직전 선택으로 이동·팝업이 재발동한다(선택 초기화 effect 가 모드 전환에는 돌지 않는다).
  //    실행 시점의 모드는 `displayModeRef` 로 읽는다(`onSelectResultRef` 와 같은 패턴).
  //    ⚠️ `found` 의미 차이 — 읍면동 모드는 등급 밖 zone 이 레지스트리에 없어 `false` 지만, 히트맵 모드는
  //    등급과 무관하게 zone·좌표만 있으면 `true` 다(히트맵은 등급 필터 없이 전부 그린다 — 의도된 차이).
  const onSelectResultRef = useRef(onSelectResult)
  onSelectResultRef.current = onSelectResult
  const displayModeRef = useRef(displayMode)
  displayModeRef.current = displayMode
  useEffect(() => {
    if (!selectedEmdCd || !zones.length) return
    if (displayModeRef.current === 'heatmap') {
      const map = mapRef.current
      const z = zones.find((it) => it.emdCd === selectedEmdCd)
      // `bndryGeoJson` 은 JSON 문자열이다 — `L.geoJSON` 에 그대로 넣지 않고 기존 파서를 쓴다.
      const parsed = z ? parseBndryGeoJson(z.bndryGeoJson) : null
      const lat = z?.mrkLat
      const lot = z?.mrkLot
      const hasAnchor = lat != null && lot != null
      if (map && parsed) {
        // polygon 은 MBR 계산용일 뿐 지도에 add 하지 않는다.
        map.fitBounds(L.polygon(parsed).getBounds(), { padding: HEATMAP_FOCUS_PADDING })
      } else if (map && hasAnchor) {
        map.setView([lat, lot], focusZoom(map, LIST_FOCUS_ZOOM.complaints), { animate: true })
      }
      // 🔴 히트맵 분기도 반드시 보고한다 — 안 하면 벨 판정(`handleSelectResult`)이 영원히 대기한다.
      onSelectResultRef.current?.(selectedEmdCd, selectedEmdSeq, !!z && (parsed != null || hasAnchor))
      return
    }
    const entry = zoneClickRef.current.get(selectedEmdCd)
    if (!entry) {
      onSelectResultRef.current?.(selectedEmdCd, selectedEmdSeq, false)
      return
    }
    entry.open() // 강조 + 팝업 — 지도 클릭과 같은 경로
    const map = mapRef.current
    const { lat, lot } = entry
    const hasAnchor = lat != null && lot != null
    if (map && lat != null && lot != null) {
      // 🔴 리스트 클릭에서만 · 확대만
      map.setView([lat, lot], focusZoom(map, LIST_FOCUS_ZOOM.complaints), { animate: true })
    }
    // 앵커가 없으면 `open()` 이 팝업 없이 끝난다 — 사용자에게 보이는 것이 없으므로 못 찾음이다
    onSelectResultRef.current?.(selectedEmdCd, selectedEmdSeq, hasAnchor)
  }, [selectedEmdCd, selectedEmdSeq, zones, view])

  // ── 행정동 경계 redraw ──
  useEffect(() => {
    const layer = boundaryLayerRef.current
    if (!layer) return
    layer.clearLayers()
    if (!toggles.adminBoundary || !adminDistricts) return
    // 2026-09-21 — 시안 스타일(회색 얇은 점선, 채움 없음) + `interactive: false`.
    // 사용자 요청으로 hover tooltip·클릭 강조를 없앴다. 부수 효과: 종전 `fillOpacity 0.01 + interactive`
    // 경계 면이 zone polygon 위에 깔려 클릭을 가로채던 문제가 사라진다.
    const style: L.PathOptions = {
      color: '#9e9e9e',
      weight: 1.2,
      opacity: 0.8,
      dashArray: '3 3',
      fillOpacity: 0,
      interactive: false,
    }
    for (const f of adminDistricts.features) {
      const geom = f.geometry
      if (geom.type === 'Polygon') {
        layer.addLayer(L.polygon(geom.coordinates.map(ringToLatLngs), style))
      } else if (geom.type === 'MultiPolygon') {
        layer.addLayer(L.polygon(geom.coordinates.map((poly) => poly.map(ringToLatLngs)), style))
      }
    }
  }, [toggles.adminBoundary, adminDistricts])

  // ── 공영주차장 마커 redraw ──
  useEffect(() => {
    const layer = parkingLayerRef.current
    if (!layer) return
    layer.clearLayers()
    if (!toggles.publicParking || !parkingLots.length) return

    // 클러스터 그룹은 addLayers 로 한 번에 넣어야 클러스터 계산이 1회로 끝난다.
    const markers: L.Marker[] = []
    for (const p of parkingLots) {
      if (typeof p.lat !== 'number' || typeof p.lot !== 'number') continue
      // 2026-09-04 갱신: "보라색 원형 + P 글자" 마커 — divIcon 으로 텍스트 마커.
      // 기존 circleMarker (색만 칠한 원) 는 화면 mock 과 어긋남. CSS className 으로 스타일 통제.
      const icon = L.divIcon({
        className: 'minwon-map__parking-icon',
        html: '<span class="minwon-map__parking-icon__letter">P</span>',
        iconSize: [28, 28],
        iconAnchor: [14, 14],
        popupAnchor: [0, -14],
      })
      // 2026-09-21 — hover tooltip 제거(시안에 없고 팝업 제목과 중복, 클릭 시 둘이 겹친다).
      // 강조 로직이 없어 레이어 바인딩 팝업으로 충분하다(zone 이 L.popup 을 쓴 건 강조·생명주기 때문).
      // 클러스터 안 마커도 동작한다 — bindPopup 은 마커 레이어의 click 이벤트라 클러스터가 DOM 을
      // 붙였다 떼도 유지된다. 본문은 클릭 시점에 만든다(함수 content — 2천여 개 DOM 을 미리 만들지 않는다).
      const marker = L.marker([p.lat, p.lot], { icon, riseOnHover: true }).bindPopup(
        () => buildParkingPopup(p),
        { className: 'minwon-map__popup', minWidth: 180 },
      )
      markers.push(marker)
    }
    layer.addLayers(markers)
  }, [toggles.publicParking, parkingLots])

  // ── 컨테이너 크기 보정 (부모 패널/필터 변화 반영) ──
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    map.invalidateSize()
  }, [invalidateSize])

  if (!MAP_KEY) {
    return (
      <div className="minwon-map minwon-map--nokey" style={NO_KEY_STYLE}>
        배경지도 키(VITE_VWORLD_API_KEY) 가 설정되지 않았습니다
      </div>
    )
  }

  return (
    <div className="minwon-map-area">
      <div ref={elRef} className="minwon-map" style={MAP_STYLE} />

      {/*
       * §4 통합 카드 + 배경지도 토글 — `.minwon-map__layer-card-container` 하나만
       * `.minwon-map-area` 기준 absolute 로 좌상단에 놓고, 내부 두 자식은
       * flex(column)로 세로 정렬한다 (개별 absolute로 두면 컨테이너의 padding box
       * 기준으로 각자 흩어져 flex 정렬이 무의미해짐). leaflet container 의
       * *형제* 라 leaflet sizing 계산에 영향 없음.
       */}
      <div className="minwon-map__layer-card-container">
        <LayerCard
          view={view}
          onViewChange={(next) => onViewChange?.(next)}
          toggles={toggles}
          onTogglePublicParking={(next) => onTogglePublicParking?.(next)}
          onToggleAdminBoundary={(next) => onToggleAdminBoundary?.(next)}
          displayMode={displayMode}
          onDisplayModeChange={(next) => onDisplayModeChange?.(next)}
        />

        <MapTypeSwitch value={base} onChange={setBase} />
      </div>

      {/* §5 범례 — 지도 우측 하단 floating 카드. §4 토글 state 만 prop 으로 받아 동적 2개 행을
          조건부 렌더한다. 🔁 2026-10-02 — 종전의 `view` prop 과 shortage 전용
          '{등급} 그룹' 표기는 **걷었다**(Jenks 등급을 쓰지 않아 근거가 사라졌다 — 세 view 가 같은
          규칙이라 범례도 같은 문구다). leaflet container 의 형제 — leaflet sizing 영향 없음. */}
      <div className="minwon-map__legend-container">
        <LegendCard
          toggles={toggles}
          displayMode={displayMode}
          metricLabel={LEGEND_METRIC[view]}
          periodLabel={periodLabel}
        />
      </div>
    </div>
  )
}
