import { useEffect, useRef, useState, type CSSProperties } from 'react'
import 'leaflet/dist/leaflet.css'
import * as L from 'leaflet'
import MapTypeSwitch from '../common/MapTypeSwitch'
import { MAP_KEY, fixLeafletAnchors, createBasemapController, LIST_FOCUS_ZOOM, focusZoom, bindMarkerLabelZoom } from '../common/mapTiles'
import type { BaseMapKind, BasemapController } from '../common/mapTiles'
import { resolveCssColor } from './hotspotGrade'
import type { ZoneRcmdtnSelection, ZoneRcmdtnShareSelection } from './zoneRcmdtnSelection'
import type {
  ZoneRcmdtnExclLot,
  ZoneRcmdtnLot,
  ZoneRcmdtnShareCrtrVls,
  ZoneRcmdtnShareZone,
} from '../../api/zoneRcmdtn'
import {
  fetchAdminDistricts,
  type AdminDistrictGeometry,
  type LinearRing,
} from '../../api/complaints'
import {
  EXCL_LOT_COLOR,
  FEE_PRRT_LVLS,
  SHARE_SCR_LABEL,
  drawableExclLots,
  exclRsnText,
  feePrrtBndry,
  feePrrtLvl,
  feeRvwGrdBadge,
  feeRvwGrdColor,
  feeRvwGrdNm,
  isFeeRvwScr,
  type FeePrrtBndry,
  fmtDecimal,
  fmtIncrsRt,
  fmtInt,
  fmtRvwScr,
  shareZoneLvl,
  shareLvlBadge,
  shareLvlColor,
  shareLvlNm,
} from './zoneRcmdtnView'

/*
 * 구역추천 — 주차장 핀 + 읍면동 폴리곤 지도 (TSK-4207 Step 6).
 *
 * 🔴 **핀은 구역이 아니라 실제 주차장이다**(§0-1 — 폐지된 `TB_ZONE_RCMDTN_RSLT` 의 DBSCAN 구역
 * 설계로 돌아가지 않는다). 순위 숫자를 핀에 얹고, 표 행을 누르면 그 핀이 강조·중앙으로 온다.
 * 🔁 TSK-4241(2026-10-19) — 표 행 클릭은 이제 이동만이 아니라 `setView`(확대)다. 공용 상수(mapTiles)의
 * 구역추천 키 값까지 확대한다(확대만 — 이미 더 들어가 있으면 축소하지 않는다). 공유주차제 깃발도 같다.
 *
 * 🔴 **읍면동 폴리곤 위에 핀이 겹친다.** 선택한 동의 경계가 없으면 `이도이동 1994-1` 같은 지번
 * 이름만으로는 "그 동 안인지" 를 읽을 수 없다. 경계는 민원현황이 이미 쓰는
 * `GET /api/admin-districts?emdCd=` 를 재사용한다(새 API 를 만들지 않는다).
 *
 * 배경지도 배선은 `docs/Front-guide/map-basemap.md` 의 3줄 그대로다 — 타일 URL·키를 여기 적지 않는다.
 *
 * 🔁 **Step 10 — `.card` 래퍼를 걷었다.** 이 지도는 `.sim-stage` 의 **직계 자식**이어야 한다 —
 *    `analysis.css` 의 `.sim-stage > .leaflet-container { width:100%; height:100% }` 가 그 형태를
 *    전제로 크기를 잡기 때문이다(`HotspotMap`·`HotspotPage` 주석이 같은 함정을 적어 뒀다).
 *    그래서 `HotspotMap` 처럼 **조각(fragment)** 을 돌려주고, 배경지도 전환 버튼도 지도의 **형제**로
 *    둔다 — `.sim-stage > .map-type` 위치 규칙이 직계 자식에만 걸린다.
 *
 * 🔁 **TSK-4208 — 공유주차제 적용 보기의 깃발 층이 더해졌다**(파일 아래쪽 "공유주차제 층").
 *    지도 인스턴스는 **하나**이고 층만 둘이다 — 요금제 층은 주차장 핀 + 읍면동 경계, 공유주차제 층은
 *    후보 **읍면동 중심**의 깃발이다(숫자 = 표시 순번 `rnk`, 색 = 검토 등급).
 *    🔁 2026-10-13 사용자 요청 — 그 등급은 이제 **검토 지수 3단계**다(`shareLvl`). 깃발은 지수 1 이상만
 *    서므로 색은 `매우 높음`·`높음` 둘이다. 팝업의 등급 배지도 그 이름이고, 시군구명·주의 배지는 걷었다.
 *    `rnk` 는 서버가 조회 결과(시군구 필터 적용 후) 안에서 매긴 값 그대로다 — 여기서 다시 세지 않는다.
 *    🔴 두 층이 **동시에 보이지 않는다** — 페이지가 보기에 맞는 쪽 데이터만 넘기고(다른 쪽은
 *    `undefined`), 각 층의 effect 는 자기 데이터가 비면 자기 마커를 걷는다. 요금제 층의 코드는
 *    손대지 않았다.
 *
 * 🆕 **TSK-4276 Step 4 — 요금제 층에 후보 밖 주차장(회색 `P` 원형 핀)이 더해졌다**(파일 아래쪽 "후보 밖 층").
 *    별도 `L.layerGroup` · 별도 effect 라 후보 핀·선택·`fitBounds` 코드와 섞이지 않는다(D5).
 *    🔴 줌 14 미만에서는 레이어째 지도에서 뗀다(D1 — 시군구 모드 제주시 1,433곳이 후보 핀을 덮는다).
 *
 * ⚠️ 배정 반경(`altmntRngVl`) 원을 그리지 않는다. 13~40개 원이 겹치면 읍면동 전체가 덮여 핀이
 *    묻히고, "민원이 그 원 안에 있다" 는 사실은 요약의 배정률 문구가 이미 말한다.
 */

/**
 * 제주 전도 — 읍면동을 고르기 전(또는 좌표·경계가 모두 없을 때)의 시야. `HotspotMap` 과 같은 값이다.
 * 🔁 분석 내용을 바꿨을 때 돌아가는 시야도 이 값이다(`resetSeq` effect) — 첫 진입과 같은 화면이어야 한다.
 */
const JEJU_CENTER: L.LatLngExpression = [33.486, 126.512]
const JEJU_ZOOM = 13

/** 핀이 한 개뿐이면 `fitBounds` 가 최대 줌까지 당겨 주변 맥락이 사라진다 — 이 줌으로 고정한다. */
const SINGLE_LOT_ZOOM = 16
/** 지도 가장자리 여백(px). 핀은 앵커 위로 54px 솟아 있고 팝업은 그 위로 더 열려 넉넉히 준다. */
const FIT_PAD: L.PointTuple = [48, 64]

/*
 * ── 오버레이 가림폭 보정 (집중구역 `HotspotMap` 에서 이식) ────────────────
 *
 * 🔴 이 화면은 컨테이너의 기하학적 중앙과 **눈에 보이는 영역의 중앙이 다르다** — 좌측 설정 패널과
 * 우측 결과 패널이 지도 **위에 얹혀** 있다. 그래서 `panTo` 로 핀만 가운데 두면 핀이 패널 밑으로 들어간다.
 */
const LEFT_PANEL_SELECTOR = '.sim-panel--left'
const RIGHT_PANEL_SELECTOR = '.sim-result'

interface Occlusion {
  left: number
  right: number
}

const NO_OCCLUSION: Occlusion = { left: 0, right: 0 }

/** 보정하고도 이만큼은 남아야 "보이는 중앙" 에 뜻이 있다 — 창이 좁아 그 아래면 보정을 포기한다. */
const MIN_VISIBLE_WIDTH = 120

/** 팝업 autoPan 여백 — 패널 **안쪽 경계**에서 이만큼 띄운다. */
const AUTOPAN_GAP = 16

/**
 * 오버레이 패널이 지도 컨테이너의 좌/우를 덮는 폭을 **실측**한다.
 * 매번 `getBoundingClientRect()` 로 재므로 CSS 폭이 바뀌어도, 결과 패널을 닫아도(그러면 우측 0)
 * 그대로 따라간다.
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
 * 핀을 거기 놓으려면 지도 중심을 같은 양만큼 **왼쪽**으로 옮겨야 하므로 투영 좌표에서 **뺀다**
 * (🔴 부호를 뒤집으면 핀이 오히려 패널 밑으로 더 들어간다).
 *
 * `dy` 는 0 이다 — 두 패널 사이 띠에는 위아래를 가리는 것이 없고, 팝업이 핀 **위로** 열리므로
 * 세로로 밀면 팝업만 상단에 붙는다.
 */
function centerFor(map: L.Map, latlng: L.LatLng, zoom: number, occ: Occlusion): L.LatLng {
  const dx = (occ.left - occ.right) / 2
  if (dx === 0) return latlng
  return map.unproject(map.project(latlng, zoom).subtract([dx, 0]), zoom)
}

/**
 * 핀을 **직접 클릭**해 팝업이 열릴 때 쓸 autoPan 여백을 패널 가림폭으로 갱신한다.
 *
 * 표 클릭 경로는 팝업의 `autoPan` 을 끄지만(아래 선택 effect) 이 경로는 **켜 둔다** — 끄면 가장자리
 * 핀을 눌렀을 때 팝업이 화면 밖으로 잘린다. 다만 Leaflet 기본 여백(5px)은 **컨테이너** 가장자리
 * 기준이라 팝업이 패널 밑으로 밀려 들어가므로, 가림폭을 여백에 실어 **패널 안쪽 경계**를 기준으로 삼는다.
 */
function syncAutoPanPadding(pin: L.Marker, container: HTMLElement | null): void {
  const popup = pin.getPopup()
  if (!popup) return
  const { left, right } = measureOcclusion(container)
  popup.options.autoPanPaddingTopLeft = L.point(left + AUTOPAN_GAP, AUTOPAN_GAP)
  popup.options.autoPanPaddingBottomRight = L.point(right + AUTOPAN_GAP, AUTOPAN_GAP)
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

/** 결과가 없을 때 쓰는 **고정** 빈 배열 — 매 렌더 `[]` 를 새로 만들면 핀이 계속 다시 그려진다. */
const NO_LOTS: ZoneRcmdtnLot[] = []

/**
 * 읍면동 경계 색 — 핀과 경쟁하지 않게 중립 회색을 쓴다.
 * 🔁 TSK-4276 — 요금제 핀 색은 고정 파랑(선택은 빨강)이 아니라 **우선순위 색**(`FEE_PRRT_LVLS`)이고,
 * 선택은 공유주차제 깃발처럼 **크기**로 표시한다(빨강이 이제 `높음` 이라 색으로 고를 수 없다).
 * 🔁 TSK-4282 — 새 방식(`anlsMthd === 'RVW_SCR'`)의 핀 색은 **`rvwGrdCd` 등급색**(`lotPinColor`)이다. 선택은 여전히 크기다.
 */
const BNDRY_COLOR_VAR = 'var(--cool-neutral-70)'

/**
 * 색 해석 캐시.
 *
 * 🔴 **SVG `fill`/`stroke` 는 `var()` 를 받지 않는다** — 핀은 HTML 문자열로 만들고(`divIcon`),
 * leaflet 벡터는 `stroke`/`fill` **속성**에 값을 그대로 넣기 때문이다. 그래서 토큰을 실제 색으로
 * 해석해 쓴다(`resolveCssColor`). 해석 1회가 강제 리플로우를 부르므로 색 3개를 캐시한다.
 *
 * ⚠️ 모듈 로드 시점이 아니라 **첫 호출 시점**에 해석한다 — 로드 때 하면 `tokens.css` 가 아직
 * 적용되지 않은 상태를 읽을 수 있다.
 */
const cssColorCache = new Map<string, string>()
function themeColor(cssColor: string): string {
  const hit = cssColorCache.get(cssColor)
  if (hit) return hit
  const resolved = resolveCssColor(cssColor)
  cssColorCache.set(cssColor, resolved)
  return resolved
}

/**
 * 🆕 TSK-4282 — 핀 색(실제 색 문자열). 새 방식(`rvwScr`)은 `rvwGrdCd` 우선도 단계색, 구 방식은 우선순위(`bndry`)다.
 * 🔴 결과 패널 행 점 · 범례와 같은 함수(`feeRvwGrdColor` / `feePrrtLvl`)를 읽는다.
 */
function lotPinColor(lot: ZoneRcmdtnLot, bndry: FeePrrtBndry | null, rvwScr: boolean): string {
  return themeColor(
    rvwScr ? feeRvwGrdColor(lot.rvwGrdCd) : FEE_PRRT_LVLS[feePrrtLvl(lot.asgnCvlcptCnt, bndry)].color,
  )
}

/** 읍면동 경계 스타일. 면은 아주 옅게만 깔아 핀·배경지도를 가리지 않는다. */
function bndryStyle(): L.PolylineOptions {
  const color = themeColor(BNDRY_COLOR_VAR)
  return {
    color,
    weight: 2,
    opacity: 0.9,
    fillColor: color,
    fillOpacity: 0.06,
    interactive: false, // 🔴 경계를 클릭 대상으로 두면 핀 클릭을 가로챈다
  }
}

/**
 * 팝업·핀 라벨 이스케이프. 주차장명·주소는 **원천 데이터**라 `&`·`<` 가 섞일 수 있는데
 * `divIcon`/`bindPopup` 은 HTML 문자열을 그대로 파싱한다.
 */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 핀 HTML — 집중구역 마커(`.cmk`)와 같은 물방울이다. 숫자는 순위, 라벨은 주차장명.
 *
 * `emphasize` 는 선택 표시다 — 두 층 모두 색이 **등급**이라(🔁 TSK-4276 부터 요금제 층도 우선순위 색)
 * 선택을 색으로 표시할 수 없어 크기로 한다. `.cmk__flag` 의
 * `transform-origin`(꼬리 끝)이 이미 있어 인라인 `scale` 만 얹는다 — CSS 클래스를 새로 만들지 않는다.
 */
function pinHtml(rnk: number | string, color: string, pkltNm: string, emphasize = false): string {
  const style = emphasize ? ' style="transform:scale(1.25)"' : ''
  return `<svg class="cmk__flag"${style} width="44" height="56" viewBox="0 0 44 56" fill="none">
    <path d="M22 54 C22 54 39 33 39 19 A17 17 0 1 0 5 19 C5 33 22 54 22 54 Z" fill="${color}" stroke="#fff" stroke-width="2.5"/>
    <circle cx="22" cy="19" r="12.5" fill="rgba(255,255,255,0.92)"/>
    <text x="22" y="19" text-anchor="middle" dominant-baseline="central" font-family="'Pretendard',sans-serif" font-size="12" font-weight="800" fill="${color}">${rnk}</text>
  </svg><span class="cmk__lab">${escapeHtml(pkltNm)}</span>`
}

/**
 * 팝업 HTML — 집중구역 `.gp` 마크업을 그대로 쓴다.
 * 🔴 **효과(민원이 몇 건 줄어드는지) 칸이 없다** — 주차장 단위 효과 산식이 없다(필수 문구 ① · `NOTE_RANK_ONLY`).
 * 🔁 TSK-4259 (사용자 요청 2026-10-21) — 본문은 **면수 · 배정 민원 두 줄만** 남긴다. 읍면동명(`emdNm`)·면당
 * 민원 줄을 걷었다. ⚠️ 시군구 결과에서 핀이 어느 동인지는 팝업으로도 알 수 없다(응답 `lots[].emdNm` 에는 남는다).
 * 🔁 TSK-4276 — 머리 배지는 `N위` 대신 시안의 **`우선순위 높음|중간|낮음`** 이다(순위 숫자는 핀이 말한다).
 * 🔁 TSK-4282 — 새 방식(`rvwScr`)이면 배지는 **우선도 단계명**(최우선·우선·후순위 — `rvwGrdCd` · D14)이다. 구 방식(`bndry` 경로)은 종전 그대로.
 * 🔁 TSK-4276 (사용자 요청 2026-10-26) — 맨 아래에 **민원 증가율** 줄을 둔다(시안 `민원 증가율(참고)` 자리).
 * 🔴 `null` 은 `—` 다(`fmtIncrsRt` — 0% 로 쓰면 "변화 없음" 이 된다). 색은 넣지 않는다 — 핀 색(우선순위 · 새 방식은 우선도 단계)과 겹친다.
 */
function popupHtml(lot: ZoneRcmdtnLot, bndry: FeePrrtBndry | null, rvwScr: boolean): string {
  const type = lot.pkltTypeNm ? `<span class="gp__loc">${escapeHtml(lot.pkltTypeNm)}</span>` : ''
  let badge: string
  if (rvwScr) {
    badge = `<span class="${feeRvwGrdBadge(lot.rvwGrdCd)}">${feeRvwGrdNm(lot.rvwGrdCd)}</span>`
  } else {
    const lvl = FEE_PRRT_LVLS[feePrrtLvl(lot.asgnCvlcptCnt, bndry)]
    badge = `<span class="${lvl.badge}">우선순위 ${lvl.nm}</span>`
  }
  return `<div class="gp">
    <div class="gp__h">${badge}${type}</div>
    <div class="gp__big">${escapeHtml(lot.pkltNm)}</div>
    <div class="gp__bd">
      <span>면수 <b>${fmtInt(lot.pkltCpcty)}면</b></span>
      <span>배정 민원 <b>${fmtInt(lot.asgnCvlcptCnt)}건</b></span>
    </div>
    <div class="gp__bd"><span>민원 증가율 <b>${fmtIncrsRt(lot.cvlcptIncrsRt)}</b></span></div>
  </div>`
}

/* ── 후보 밖 층 — 회색 `P` 핀 (TSK-4276 Step 4) ──────────────── */

/** 결과가 없을 때 쓰는 고정 빈 배열 — `NO_LOTS` 와 같은 이유다. */
const NO_EXCL_LOTS: ZoneRcmdtnExclLot[] = []

/**
 * 🔴 **이 줌 미만이면 회색 핀 레이어를 지도에서 뗀다**(D1 — 사용자 확정 2026-10-26).
 * 시군구 모드(제주시)는 후보 밖이 1,433곳이라 전도 시야에서는 후보 핀을 덮는다. 읍면동 하나(수십~200여 곳)는
 * 보통 `fitBounds` 가 14 이상으로 잡아 그대로 보인다. 확대하면 나타난다 — 범례 줄은 줌과 무관하게 남는다.
 */
const EXCL_MIN_ZOOM = 14

/**
 * 회색 핀 전용 pane — 🔴 **후보 핀 아래 · 읍면동 경계 위**(`overlayPane` 400 < **590** < `markerPane` 600).
 * 🔁 `zIndexOffset` 음수로 내리지 않는다 — 마커 z-index 는 `화면 y + zIndexOffset` 이라 같은 pane 안에서는
 * 화면 높이가 1000px 을 넘으면 아래쪽 회색 핀이 위쪽 후보 핀을 **덮는다**(-1000 이 뒤집힌다). pane 을 가르면
 * y 와 무관하게 순서가 고정된다. ⚠️ 팝업은 마커의 `pane` 을 물려받지 않는다 — leaflet 1.9 `Popup` 은 자기
 * 옵션 `pane: 'popupPane'`(700)을 쓰므로 회색 핀의 팝업도 후보 핀 위에 열린다.
 */
const EXCL_PANE = 'zoneExclPane'
const EXCL_PANE_Z = '590'

/** pane 을 한 번만 만든다 — `createPane` 을 같은 이름으로 두 번 부르면 같은 pane 이 둘 생긴다. */
function ensureExclPane(map: L.Map): void {
  if (map.getPane(EXCL_PANE)) return
  map.createPane(EXCL_PANE).style.zIndex = EXCL_PANE_Z
}

/**
 * 시안 원형 `P` 핀 — 인라인 스타일 그대로(26×26 · 흰 테두리 2.5px · 흰 `P` 13px/800).
 * 🔴 `className: ''` — 기본값(`leaflet-div-icon`)은 흰 배경·테두리 사각형을 덧씌운다. CSS 클래스를 새로 만들지 않는다.
 * 같은 아이콘을 전 핀이 공유한다(선택 상태가 없어 다시 만들 일이 없다).
 */
let exclIconCache: L.DivIcon | null = null
function exclIcon(): L.DivIcon {
  if (exclIconCache) return exclIconCache
  exclIconCache = L.divIcon({
    className: '',
    iconSize: [26, 26],
    iconAnchor: [13, 13], // 원 중심이 좌표다(물방울이 아니라 꼬리가 없다)
    popupAnchor: [0, -13], // 팝업이 원 위 가장자리에서 열리게 한다(기본 0 이면 원을 덮는다)
    html: `<div style="width:26px;height:26px;border-radius:50%;background:${EXCL_LOT_COLOR};border:2.5px solid #fff;box-shadow:0 2px 6px rgba(0,0,0,.25);box-sizing:border-box;display:flex;align-items:center;justify-content:center;color:#fff;font-family:'Pretendard',sans-serif;font-size:13px;font-weight:800;line-height:1">P</div>`,
  })
  return exclIconCache
}

/** 회색 핀 팝업 — 시안 마크업(`.gp`). 🔴 주차장명은 원천 데이터라 이스케이프한다. */
function exclPopupHtml(lot: ZoneRcmdtnExclLot, minCpcty: number | null): string {
  return `<div class="gp"><div class="gp__big">${escapeHtml(lot.pkltNm)}</div><div class="gp__bd"><span>후보 아님 — <b>${escapeHtml(exclRsnText(lot, minCpcty))}</b></span></div></div>`
}

/* ── 공유주차제 층 (TSK-4208 Step 5) ──────────────────────── */

/** 결과가 없을 때 쓰는 고정 빈 배열 — `NO_LOTS` 와 같은 이유다. */
const NO_SHARE_ZONES: ZoneRcmdtnShareZone[] = []

/** 후보가 한 곳뿐일 때의 줌 — 읍면동 하나가 화면에 들어오는 정도다(주차장 핀의 16 은 너무 가깝다). */
const SINGLE_ZONE_ZOOM = 13

/**
 * 공유주차제 깃발의 팝업 — `.gp` 마크업 그대로.
 *
 * 🔁 **등급 배지는 검토 지수 3단계의 이름이다**(`매우 높음`·`높음` — `shareLvl`). 종전의 공통코드 라벨 ·
 *    시군구명(`sggNm`) · 주의 배지(`cutnCds`)는 걷었다(사용자 요청 2026-10-13).
 * ⚠️ **깃발은 읍면동 중심점이다**(민원이 몰린 지점이 아니다). 🔁 그 안내 한 줄은 걷었다(사용자 요청 2026-10-13).
 * 🔴 효과(민원이 몇 건 줄어드는지) 칸이 없다 — 공유주차제의 효과 산식이 없다.
 */
function sharePopupHtml(zone: ZoneRcmdtnShareZone, crtrVls: ZoneRcmdtnShareCrtrVls | null): string {
  const lvl = shareZoneLvl(zone, crtrVls)
  // 등급이 없으면(지수 없는 구 응답) 빈 배지를 그리지 않는다.
  const badge = lvl
    ? `<span class="${shareLvlBadge(lvl)}">${escapeHtml(shareLvlNm(lvl))}</span>`
    : ''
  // 🔴 `null` 을 무조건 포맷하지 않는다 — 후보는 값이 있지만 계약상 null 가능 필드다.
  const rt = zone.cvlcptRt != null ? `${fmtDecimal(zone.cvlcptRt, 2)}건` : '—'
  // 🔁 검토 지수를 맨 앞에 둔다(뒤의 두 값이 그 재료다). 🔁 TSK-4283 — 목록 행의 큰 값은 **추세 반영 지수**,
  //    팝업은 **등급의 근거인 검토 지수**다(배지·깃발 색이 `rvwScr` 로 정해진다 — D1). 라벨이 달라 섞이지 않는다.
  //    `fmtRvwScr` 가 `null`·`undefined`(배포 전 응답)를 `—` 로 바꾼다.
  return `<div class="gp">
    <div class="gp__h">${badge}</div>
    <div class="gp__big">${escapeHtml(zone.emdNm)}</div>
    <div class="gp__bd">
      <span>${escapeHtml(SHARE_SCR_LABEL)} <b>${fmtRvwScr(zone.rvwScr)}</b></span>
      <span>월평균 <b>${fmtDecimal(zone.cvlcptMmAvg)}건</b></span>
      <span>100면당 <b>${rt}</b></span>
    </div>
  </div>`
}

/** 공유주차제 깃발 아이콘. 색은 검토 등급(지수 3단계), 선택은 크기로 표시한다(`pinHtml` 주석). */
function shareFlagIcon(
  zone: ZoneRcmdtnShareZone,
  crtrVls: ZoneRcmdtnShareCrtrVls | null,
  active: boolean,
): L.DivIcon {
  const color = themeColor(shareLvlColor(shareZoneLvl(zone, crtrVls)))
  return L.divIcon({
    className: 'cmk',
    iconSize: [44, 56],
    iconAnchor: [22, 54],
    // 🔁 2026-10-27 — 단계가 있는 행에만 깃발이 선다(그 행은 지수가 있어 `rnk` 도 있다 · `—` 는 타입 방어다).
    html: pinHtml(zone.rnk ?? '—', color, zone.emdNm, active),
  })
}

/** GeoJSON ring(`[lng, lat]`) → leaflet `LatLng[]`(`[lat, lng]`). 순서를 뒤집으면 바다에 그려진다. */
function ringToLatLngs(ring: LinearRing): L.LatLngTuple[] {
  return ring.map(([lng, lat]) => [lat, lng] as L.LatLngTuple)
}

/** 행정동 경계 geometry → `L.polygon` 입력. `Polygon` · `MultiPolygon` 두 가지만 온다. */
function geometryToLatLngs(geom: AdminDistrictGeometry): L.LatLngTuple[][] | L.LatLngTuple[][][] {
  return geom.type === 'Polygon'
    ? geom.coordinates.map(ringToLatLngs)
    : geom.coordinates.map((poly) => poly.map(ringToLatLngs))
}

export interface ZoneRcmdtnMapProps {
  /** 선택된 읍면동코드. 경계를 그릴 대상이며 `''` 이면 경계도 핀도 없다. */
  emdCd: string
  /** 후보 주차장 — 서버 정렬 순서 그대로(`rnk` 가 핀 숫자다). 좌표가 null 인 행은 핀을 만들지 않는다. */
  lots?: ZoneRcmdtnLot[]
  /**
   * 🆕 TSK-4282 — 응답 `anlsMthd`. `'RVW_SCR'` 이면 핀 색·팝업 배지가 `rvwGrdCd` 등급이고, 없으면 구 방식
   * (우선순위 — `feePrrt*`)이다. 🔴 `lots` 와 **같은 탭 잠금**으로 넘긴다(요금제 보기에서만).
   */
  anlsMthd?: 'RVW_SCR' | null
  /** 현재 선택(표 ↔ 핀). 바뀌면 그 핀을 강조하고 중앙으로 옮긴 뒤 팝업을 연다. */
  selection: ZoneRcmdtnSelection | null
  /** 핀 클릭 — 표의 해당 행을 활성화·스크롤한다. */
  onSelectLot: (pkltId: number) => void
  /**
   * 🆕 TSK-4276 — 후보 밖 주차장(회색 `P` 핀). 🔴 **요금제 보기일 때만** 넘긴다(공유주차제면 `undefined` —
   * 그러면 회색 핀이 걷힌다 · E5). 좌표 없는 행은 건너뛴다(`drawableExclLots` — 범례 개수와 같은 함수).
   * 선택·시야와 무관하다(D5).
   */
  exclLots?: ZoneRcmdtnExclLot[]
  /** 🆕 TSK-4276 — 응답 `crtrVls.swtcTrgtMinCpcty` — 팝업 사유 `{N}면 미만` 의 N. */
  exclMinCpcty?: number | null
  /**
   * 🆕 공유주차제 층 — `/share` 응답의 `zones`(서버 순서 그대로). 🔴 **`공유주차제 적용` 보기일 때만**
   * 넘긴다(요금제 보기에서는 `undefined` — 그러면 깃발이 걷힌다). 깃발은 중심 좌표가 있고 단계(높음·중간·낮음)가
   * 있는 행에 선다(🔁 2026-10-27 사용자 요청 — 종전 후보만 · `UNABLE` 은 제외).
   */
  shareZones?: ZoneRcmdtnShareZone[]
  /**
   * `/share` 응답의 적용 기준 — 깃발 색·팝업 배지의 검토 등급을 가르는 `coreMltp` 가 여기서 온다.
   * `shareZones` 와 **같은 응답**의 것을 넘긴다. 없으면 등급을 매기지 않는다(중립색 · 배지 없음).
   */
  shareCrtrVls?: ZoneRcmdtnShareCrtrVls | null
  /** 공유주차제 보기의 선택(행 ↔ 깃발). 🔁 후보가 아닌 행도 올 수 있다 — 그때는 깃발이 없어 지도는 가만히 있는다. */
  shareSelection?: ZoneRcmdtnShareSelection | null
  /** 깃발 클릭 — 목록의 해당 행을 활성화·스크롤한다. */
  onSelectShareZone?: (emdCd: string) => void
  /**
   * 🆕 초기화 신호 — 페이지가 **분석 내용을 바꿀 때마다 1씩 올린다**(`selection.seq` 와 같은 장치).
   * 바뀌면 열린 팝업을 닫고 시야를 첫 진입 값(`JEJU_CENTER`·`JEJU_ZOOM`)으로 되돌린다.
   * 🔴 마커를 걷는 일은 이 신호가 하지 않는다 — 페이지가 같은 렌더에서 두 층의 데이터를 비워 넘기고,
   * 각 층의 effect 가 제 마커를 걷는다(걷는 창구를 둘로 만들지 않는다).
   */
  resetSeq?: number
}

export default function ZoneRcmdtnMap({
  emdCd,
  lots = NO_LOTS,
  anlsMthd = null,
  selection,
  onSelectLot,
  exclLots = NO_EXCL_LOTS,
  exclMinCpcty = null,
  shareZones = NO_SHARE_ZONES,
  shareCrtrVls = null,
  shareSelection = null,
  onSelectShareZone,
  resetSeq = 0,
}: ZoneRcmdtnMapProps) {
  const elRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  /** 배경지도 전환기. 지도와 생명주기가 같다. */
  const basemapRef = useRef<BasemapController | null>(null)
  /** 🔴 배경 선택은 저장하지 않는다 — 화면을 떠나면 `base` 로 돌아온다. */
  const [baseMap, setBaseMap] = useState<BaseMapKind>('base')

  /** 읍면동 경계 레이어. 동을 바꿀 때마다 통째로 갈아 끼운다. */
  const bndryRef = useRef<L.Polygon | null>(null)
  /** 현재 올라가 있는 핀을 `pkltId` 로 찾는 표. 표에서 온 선택도 이 표에서 핀을 찾는다. */
  const pinsRef = useRef(new Map<number, L.Marker>())
  /** 강조를 되돌리려고 직전 선택을 기억한다(전체를 다시 그리지 않기 위해). */
  const activeRef = useRef<number | null>(null)

  /**
   * 콜백을 ref 에 담아 둔다 — 핀 effect 의 의존성에 콜백을 넣으면 부모가 인라인 함수를 넘길 때마다
   * (매 렌더) 핀이 통째로 다시 그려지고 `fitBounds` 가 다시 걸린다.
   */
  const selectRef = useRef(onSelectLot)
  useEffect(() => {
    selectRef.current = onSelectLot
  }, [onSelectLot])

  /** 공유주차제 층 — 깃발을 `emdCd` 로 찾는 표 · 직전 선택 · 콜백(위 요금제 층과 같은 세 가지). */
  const sharePinsRef = useRef(new Map<string, L.Marker>())
  const shareActiveRef = useRef<string | null>(null)
  const selectShareRef = useRef(onSelectShareZone)
  useEffect(() => {
    selectShareRef.current = onSelectShareZone
  }, [onSelectShareZone])

  // ── 지도 생성 (마운트 1회) ──
  useEffect(() => {
    // 키가 없으면 지도 자체를 만들지 않는다 — 타일 요청이 전부 실패해 콘솔 에러가 폭주한다.
    if (!MAP_KEY) return
    const el = elRef.current
    if (!el) return

    const map = L.map(el, { center: JEJU_CENTER, zoom: JEJU_ZOOM, zoomControl: false })
    L.control.zoom({ position: 'topright' }).addTo(map)
    fixLeafletAnchors(map)
    basemapRef.current = createBasemapController(map) // 일반지도 1장을 붙인다
    const unbindLabel = bindMarkerLabelZoom(map) // zoom < `LABEL_MIN_ZOOM` 이면 마커 라벨 숨김(TSK-4244)
    mapRef.current = map

    const pins = pinsRef.current
    const sharePins = sharePinsRef.current
    // StrictMode 이중 마운트 대비 — 정리하지 않으면 재마운트가
    // "Map container is already initialized" 로 죽는다.
    return () => {
      unbindLabel() // map.remove() 뒤에는 off 가 의미 없다
      map.remove()
      mapRef.current = null
      basemapRef.current = null
      bndryRef.current = null
      pins.clear()
      sharePins.clear()
    }
  }, [])

  // ── 배경지도 전환 ──
  useEffect(() => {
    basemapRef.current?.apply(baseMap)
  }, [baseMap])

  // ── 읍면동 경계 (동이 바뀔 때만) ──
  useEffect(() => {
    const map = mapRef.current
    if (!map) return

    // 직전 동의 경계를 먼저 걷는다 — 남기면 동을 바꿀 때마다 경계가 쌓인다.
    if (bndryRef.current) {
      map.removeLayer(bndryRef.current)
      bndryRef.current = null
    }
    if (!emdCd) return

    let cancelled = false
    fetchAdminDistricts({ emdCd })
      .then((fc) => {
        const feature = fc.features[0]
        if (cancelled || !mapRef.current || !feature) return
        const polygon = L.polygon(geometryToLatLngs(feature.geometry), bndryStyle())
        // 🔴 경계는 핀보다 **아래** 깔려야 한다 — leaflet 은 벡터를 `overlayPane`,
        //    divIcon 마커를 `markerPane`(더 위)에 두므로 추가 순서와 무관하게 핀이 위다.
        polygon.addTo(mapRef.current)
        bndryRef.current = polygon
        // 핀이 하나도 없는 동(후보 0개소)에서도 그 동이 어디인지 보여야 한다.
        if (pinsRef.current.size === 0) {
          mapRef.current.fitBounds(polygon.getBounds(), { padding: FIT_PAD })
        }
      })
      .catch(() => {
        // 경계는 보조 정보다 — 못 받아도 핀과 표는 그대로 쓸 수 있으므로 조용히 넘긴다.
      })

    return () => {
      cancelled = true
    }
  }, [emdCd])

  // ── 핀 갱신 (결과가 바뀔 때마다) ──
  useEffect(() => {
    const map = mapRef.current
    if (!map) return

    // 🔴 이전 핀을 반드시 먼저 제거한다 — 동이나 유형 범위를 바꾸면 후보가 통째로 달라지는데,
    //    지우지 않으면 이전 조건의 핀이 남아 1위가 두 개로 보인다.
    pinsRef.current.forEach((pin) => map.removeLayer(pin))
    pinsRef.current.clear()
    activeRef.current = null

    // 🔴 결과 패널 행 점 · 범례와 같은 `lots` 로 같은 경계를 낸다. 🆕 TSK-4282 — 경계는 구 방식 전용이다.
    const rvwScr = isFeeRvwScr(anlsMthd)
    const bndry = rvwScr ? null : feePrrtBndry(lots)
    const points: L.LatLngTuple[] = []
    lots.forEach((lot) => {
      // 좌표가 없는 주차장은 지도에 찍을 수 없다 — 표에는 남고 핀만 건너뛴다.
      if (lot.lat == null || lot.lot == null) return
      const position: L.LatLngTuple = [lot.lat, lot.lot]
      points.push(position)

      const marker = L.marker(position, {
        icon: L.divIcon({
          className: 'cmk',
          iconSize: [44, 56],
          iconAnchor: [22, 54], // 물방울 꼬리 끝(= 실제 지점)이 좌표에 오게 한다
          html: pinHtml(lot.rnk, lotPinColor(lot, bndry, rvwScr), lot.pkltNm),
        }),
        riseOnHover: true,
      })
      marker.bindPopup(popupHtml(lot, bndry, rvwScr), { closeButton: false, offset: [0, -48] }).addTo(map)
      /*
       * 🔴 팝업이 패널 밑으로 밀려 들어가지 않게 가림폭을 autoPan 여백에 싣는다(핀 직접 클릭 경로).
       * 🔴 **`click` 에 건다 — `popupopen` 이 아니다.** `_adjustPan`(여백을 실제로 쓰는 곳)은
       * `openPopup()` 안에서 **`popupopen` 발화보다 먼저** 동기로 끝나므로, `popupopen` 에 걸면
       * 여백이 **다음 열림에야** 적용된다(그 열림은 이미 밀려 있다). 집중구역도 `click` 이다.
       */
      marker.on('click', () => syncAutoPanPadding(marker, elRef.current))
      /*
       * 🔴 `popupopen` 하나만 구독한다(`click` 은 잡지 않는다) — 둘을 모두 잡으면 1클릭에 콜백이
       * 2회 올라간다. 표에서 코드로 `openPopup()` 하는 경로도 이 이벤트를 거치므로 신호를 잃지 않는다.
       */
      marker.on('popupopen', () => selectRef.current?.(lot.pkltId))
      pinsRef.current.set(lot.pkltId, marker)
    })

    if (points.length === 1) {
      map.setView(points[0], SINGLE_LOT_ZOOM)
    } else if (points.length > 1) {
      map.fitBounds(L.latLngBounds(points), { padding: FIT_PAD })
    } else if (bndryRef.current) {
      // 후보 0개소 — 핀은 없지만 고른 동은 보여 준다(빈 지도가 아니라 결론이다).
      map.fitBounds(bndryRef.current.getBounds(), { padding: FIT_PAD })
    }
  }, [lots, anlsMthd])

  /*
   * ── 표 → 지도 ──
   * 위 핀 effect 보다 **뒤에 선언**해야 한다 — 같은 렌더에서 `lots` 와 `selection` 이 함께 바뀌면
   * effect 는 선언 순서대로 도므로, 핀이 다시 만들어진 뒤에 그 핀을 찾게 된다.
   */
  useEffect(() => {
    const map = mapRef.current
    if (!map || !selection) return

    const rvwScr = isFeeRvwScr(anlsMthd)
    const bndry = rvwScr ? null : feePrrtBndry(lots)
    /** 핀 아이콘을 평시/강조(크기)로 다시 만든다 — `divIcon` 은 일부만 바꿀 수 없어 통째로 교체한다. */
    const paint = (pkltId: number, active: boolean) => {
      const pin = pinsRef.current.get(pkltId)
      const lot = lots.find((l) => l.pkltId === pkltId)
      if (!pin || !lot) return
      pin.setIcon(
        L.divIcon({
          className: 'cmk',
          iconSize: [44, 56],
          iconAnchor: [22, 54],
          html: pinHtml(lot.rnk, lotPinColor(lot, bndry, rvwScr), lot.pkltNm, active),
        }),
      )
      // 강조된 핀이 다른 핀에 가리지 않게 올린다(divIcon 은 z-index 를 직접 받는다).
      pin.setZIndexOffset(active ? 1000 : 0)
    }

    if (activeRef.current !== null && activeRef.current !== selection.pkltId) {
      paint(activeRef.current, false)
    }
    paint(selection.pkltId, true)
    activeRef.current = selection.pkltId

    const pin = pinsRef.current.get(selection.pkltId)
    // 좌표가 없어 핀을 못 만든 주차장이다 — 표 선택만 되고 지도는 그대로 둔다.
    if (!pin) return

    // 🔴 보이는 영역(두 패널 사이)의 한가운데로 옮긴다 — 컨테이너 정중앙은 패널에 가려 중앙이 아니다.
    // 🔁 TSK-4241(2026-10-19) — 리스트 클릭에서만 · 확대만. 🔴 보정은 현재가 아니라 **목표 zoom** 으로 계산한다.
    const targetZoom = focusZoom(map, LIST_FOCUS_ZOOM.zoneRcmdtn)
    const target = centerFor(map, pin.getLatLng(), targetZoom, measureOcclusion(elRef.current)) // 🔴 목표 zoom
    map.setView(target, targetZoom, { animate: true, duration: 0.5 }) // duration 은 같은 zoom(panBy) 경로에 그대로 전달된다

    /*
     * 🔴 이 한 번만 팝업 `autoPan` 을 끈다 — 켜 두면 방금 계산한 중앙이 그대로 무너진다.
     *   1. `Popup._adjustPan` 이 **맨 먼저 `map._panAnim.stop()`** 을 부른다. 위 `setView` 는 같은 zoom 이면
     *      0.5초 팬 애니메이션이라 아직 출발점 근처인데 그 상태로 멈춰 버린다(중앙에 닿지도 못한다).
     *      (zoom 차이 >4 면 애니메이션 없이 즉시 `_resetView` 라 이 문제는 없다 — TSK-4241)
     *   2. 이어서 팝업이 화면에 "들어오기만" 하도록 지도를 다시 민다 — 중앙 정렬이 아니다.
     * `_adjustPan` 은 `openPopup()` 안에서 **동기로** 끝나므로 바로 다음 줄에서 되돌려도 안전하다.
     * 핀 직접 클릭 경로는 계속 autoPan 을 쓴다 — 근거는 `syncAutoPanPadding` 주석.
     */
    const popup = pin.getPopup()
    const autoPan = popup?.options.autoPan
    if (popup) popup.options.autoPan = false
    /*
     * 이 호출은 `popupopen` 을 발화시켜 `onSelectLot` 이 한 번 더 올라간다 — 부모는 같은 주차장으로
     * 선택을 다시 세우고(멱등) 표는 이미 보이는 행이라 스크롤을 건너뛴다. 팝업을 여는 것이 연동의
     * 목적이므로 끊지 않는다.
     */
    pin.openPopup()
    if (popup) popup.options.autoPan = autoPan
  }, [selection, lots, anlsMthd])

  /*
   * ── 후보 밖 층 — 회색 `P` 핀 (TSK-4276 Step 4) ──
   *
   * 🔴 **`fitBounds`·`setView`·선택 강조에 들어가지 않는다**(D5) — 시야는 후보·경계가 정한다.
   * 핀 클릭은 팝업만 연다(`onSelectLot` 을 부르지 않는다 — 목록에 행이 없다).
   *
   * 🔴 D1 줌 처리 — 레이어를 **통째로** 붙였다 뗀다(`L.layerGroup`). `zoomend` 마다 판정하고,
   * 최초에도 현재 줌으로 한 번 판정한다. ⚠️ 위 핀 effect 의 `fitBounds` 가 애니메이션 중이면 이 시점의
   * `getZoom()` 은 아직 옛 줌이다 — 애니메이션이 끝나며 `zoomend` 가 와서 다시 판정하므로 문제없다.
   * 결과가 바뀌거나 언마운트되면 cleanup 이 리스너·레이어를 걷는다(StrictMode 이중 실행에도 안전).
   */
  useEffect(() => {
    const map = mapRef.current
    if (!map) return

    const drawable = drawableExclLots(exclLots)
    if (drawable.length === 0) return

    ensureExclPane(map)
    const layer = L.layerGroup()
    drawable.forEach((lot) => {
      const marker = L.marker([lot.lat, lot.lot], {
        icon: exclIcon(),
        pane: EXCL_PANE, // 후보 핀 아래 · 경계 위(`EXCL_PANE` 주석) — `zIndexOffset` 을 쓰지 않는다
        keyboard: false,
      })
      marker.bindPopup(exclPopupHtml(lot, exclMinCpcty), { closeButton: false })
      // 팝업이 패널 밑으로 밀리지 않게 하는 여백 — 후보 핀과 같은 이유·같은 이벤트(`click`)다.
      marker.on('click', () => syncAutoPanPadding(marker, elRef.current))
      layer.addLayer(marker)
    })

    const sync = () => {
      const show = map.getZoom() >= EXCL_MIN_ZOOM
      // 레이어를 떼면 그 안 마커의 열린 팝업도 함께 닫힌다(leaflet `bindPopup` 의 `remove` 구독).
      if (show && !map.hasLayer(layer)) layer.addTo(map)
      else if (!show && map.hasLayer(layer)) map.removeLayer(layer)
    }
    sync()
    map.on('zoomend', sync)

    return () => {
      map.off('zoomend', sync)
      // 언마운트 때는 지도 생성 effect 의 `map.remove()` 가 먼저 돌 수 있다 — 붙어 있지 않은 레이어 제거는 no-op 이다.
      map.removeLayer(layer)
    }
  }, [exclLots, exclMinCpcty])

  /*
   * ── 공유주차제 층 — 깃발 갱신 (TSK-4208) ──
   *
   * 요금제 층의 effect 들보다 뒤에 선언한다(층 순서대로 읽히게). 🔁 분석 내용을 바꾸면 **두 층이
   * 함께 비므로**(전환 = 초기화) 이 effect 와 위 핀 effect 는 마커만 걷고 시야를 건드리지 않는다
   * (점이 0개 · 경계도 걷힌 뒤다) — 그때의 시야는 맨 아래 `resetSeq` effect 가 정한다.
   *
   * 🔁 2026-10-27 사용자 요청 — 깃발은 **목록의 모든 행**에 찍는다(종전 후보 `rvwTrgtYn === 'Y'` 만 — 목록은
   * 74행인데 지도는 25개라 번호가 끊겼다). 색은 종합지수 단계(`shareZoneLvl`)라 `낮음` 깃발도 선다.
   * 중심 좌표가 없는 행과 단계가 없는 행(`UNABLE`)은 목록에는 남고 깃발만 건너뛴다. 자료 없는 기간은 페이지가 빈 목록을 넘긴다.
   */
  useEffect(() => {
    const map = mapRef.current
    if (!map) return

    // 이전 깃발을 먼저 걷는다 — 기간·지역을 바꿔 다시 실행하면 후보가 통째로 달라진다.
    sharePinsRef.current.forEach((pin) => map.removeLayer(pin))
    sharePinsRef.current.clear()
    shareActiveRef.current = null

    const points: L.LatLngTuple[] = []
    shareZones.forEach((zone) => {
      // 단계(높음·중간·낮음)가 없는 행(`UNABLE` — 회색)은 깃발을 세우지 않는다(2026-10-27 사용자 요청).
      if (zone.lat == null || zone.lot == null || shareZoneLvl(zone, shareCrtrVls) == null) return
      const position: L.LatLngTuple = [zone.lat, zone.lot]
      points.push(position)

      const marker = L.marker(position, {
        icon: shareFlagIcon(zone, shareCrtrVls, false),
        riseOnHover: true,
      })
      marker
        .bindPopup(sharePopupHtml(zone, shareCrtrVls), { closeButton: false, offset: [0, -48] })
        .addTo(map)
      // 아래 두 구독은 요금제 핀과 같은 이유·같은 이벤트다(`click` = autoPan 여백 · `popupopen` = 선택).
      marker.on('click', () => syncAutoPanPadding(marker, elRef.current))
      marker.on('popupopen', () => selectShareRef.current?.(zone.emdCd))
      sharePinsRef.current.set(zone.emdCd, marker)
    })

    if (points.length === 1) {
      map.setView(points[0], SINGLE_ZONE_ZOOM)
    } else if (points.length > 1) {
      /*
       * 🔴 후보는 도 전체에 흩어져 있어 **패널 가림폭을 여백에 싣는다** — 싣지 않으면 서쪽·동쪽 끝
       * 깃발이 좌우 패널 밑에 들어간 채로 열린다(요금제 층은 한 동 안이라 그 문제가 없다).
       */
      const occ = measureOcclusion(elRef.current)
      map.fitBounds(L.latLngBounds(points), {
        paddingTopLeft: [occ.left + FIT_PAD[0], FIT_PAD[1]],
        paddingBottomRight: [occ.right + FIT_PAD[0], FIT_PAD[1]],
      })
    }
  }, [shareZones, shareCrtrVls])

  /*
   * ── 공유주차제 층 — 목록 → 지도 ──
   * 절차는 위 요금제 층의 "표 → 지도" 와 같다(강조 교체 → 보이는 영역 중앙으로 이동 → autoPan 을
   * 끈 채 팝업 열기). 근거는 그 effect 의 주석에 있다. 다른 점은 강조 방식 하나다 — 색이 등급이라
   * 색을 바꾸지 않고 **크기**를 키운다.
   */
  useEffect(() => {
    const map = mapRef.current
    if (!map || !shareSelection) return

    const paint = (emdCd: string, active: boolean) => {
      const pin = sharePinsRef.current.get(emdCd)
      const zone = shareZones.find((z) => z.emdCd === emdCd)
      if (!pin || !zone) return
      pin.setIcon(shareFlagIcon(zone, shareCrtrVls, active))
      pin.setZIndexOffset(active ? 1000 : 0)
    }

    if (shareActiveRef.current !== null && shareActiveRef.current !== shareSelection.emdCd) {
      paint(shareActiveRef.current, false)
    }
    paint(shareSelection.emdCd, true)
    shareActiveRef.current = shareSelection.emdCd

    const pin = sharePinsRef.current.get(shareSelection.emdCd)
    /*
     * 깃발이 없는 읍면동이다(후보가 아니거나 중심 좌표가 없다) — 목록의 행 강조만 되고 지도는
     * **옮기지도, 팝업을 열지도 않는다.** 🔴 직전 깃발의 팝업만 닫는다 — 열어 두면 목록은 이 행을
     * 가리키는데 지도는 다른 읍면동의 숫자를 보여 준다(강조는 위에서 이미 풀었다).
     */
    if (!pin) {
      map.closePopup()
      return
    }

    // 🔁 TSK-4241(2026-10-19) — 요금제 층과 같다(확대만 · 목표 zoom 으로 보정). 키만 공유주차제 것이다.
    const targetZoom = focusZoom(map, LIST_FOCUS_ZOOM.zoneShare)
    const target = centerFor(map, pin.getLatLng(), targetZoom, measureOcclusion(elRef.current)) // 🔴 목표 zoom
    map.setView(target, targetZoom, { animate: true, duration: 0.5 })

    const popup = pin.getPopup()
    const autoPan = popup?.options.autoPan
    if (popup) popup.options.autoPan = false
    pin.openPopup()
    if (popup) popup.options.autoPan = autoPan
  }, [shareSelection, shareZones, shareCrtrVls])

  /*
   * ── 초기화 (분석 내용 전환) ──
   * 🔴 **모든 층 effect 보다 뒤에 선언**한다 — effect 는 선언 순서대로 돌므로, 같은 렌더에서 위
   * effect 들이 경계·핀·깃발을 걷은 **다음**에 시야를 되돌린다(시야를 정하는 마지막 주자다).
   * 지도 인스턴스는 그대로다 — 배경지도 선택(`baseMap`)도 지도의 것이라 유지된다.
   *
   * ⚠️ 마운트 때도 한 번 돈다(같은 값으로 `setView` — 아무 변화가 없다). 첫 실행을 건너뛰는 ref 를
   * 두지 않는 이유다. `animate: false` 는 선택으로 걸려 있던 `setView` 이동 애니메이션까지 끊고 즉시 옮긴다
   * (⚠️ zoom 애니메이션 도중은 예외 — 무시될 수 있다. TSK-4241) — 미끄러지며 돌아가면 그 사이에 걷힌 핀 자리를 지나가는 화면이 보인다.
   */
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    // 마커를 걷으면 그 팝업도 닫히지만, 여기서 한 번 더 닫아 순서에 기대지 않는다.
    map.closePopup()
    map.setView(JEJU_CENTER, JEJU_ZOOM, { animate: false })
  }, [resetSeq])

  if (!MAP_KEY) {
    return <div style={NO_KEY_STYLE}>배경지도 키가 설정되지 않았습니다</div>
  }

  return (
    <>
      <div ref={elRef} style={MAP_STYLE} />
      {/*
       * 배경지도 전환은 지도 div 의 **형제**로 둔다 — `.sim-stage` 직계 자식이어야
       * `analysis.css` 의 `.sim-stage > .map-type` 위치 규칙(좌측 패널 오른쪽 옆)이 걸린다.
       * **결과 유무와 무관하게 항상** 띄운다 — 조회 전에도 지도는 보인다.
       */}
      <MapTypeSwitch value={baseMap} onChange={setBaseMap} />
    </>
  )
}
