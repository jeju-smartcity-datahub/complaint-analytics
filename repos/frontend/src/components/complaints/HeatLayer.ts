import * as L from 'leaflet'
/*
 * 히트맵 엔진 — `simpleheat@0.4.0`.
 *
 * 🔴 `leaflet.heat` 를 쓰지 않는다. markercluster 와 같은 함정이다 — UMD 가 **전역 `window.L`** 만
 *    변이하고(`L.HeatLayer = L.Layer.extend(...)` · `L.heatLayer = ...`) 자기 exports 가 없어, 우리
 *    모듈 네임스페이스 `L` 에서는 `L.heatLayer` 가 런타임 undefined 다(`MinwonMap.tsx` 상단 주석의
 *    그 문제). 게다가 2015년 이후 유지보수가 없다. 그래서 그 내부 엔진인 simpleheat 만 가져와
 *    이 파일의 얇은 `L.Layer` 확장으로 감싼다.
 *
 * 🔴 **0.4.0 고정 이유** — npm `latest` 는 1.0.0 이고 ESM 전용(`type: module`)이다.
 *    `@types/simpleheat`(0.4.3)는 0.4 API 기준이고, 0.4.0 은 CJS(`module.exports = simpleheat`)라
 *    지금의 default import·타입이 그대로 맞는다. "최신으로 올리기" 를 할 때는 import 방식과 타입을
 *    함께 검토할 것(package.json 의 `^0.4.0` 은 0.4.x 안에서만 움직인다).
 *    ⚠️ 0.4.0 의 `resize()` 는 타입과 달리 `this` 를 반환하지 않는다 — 체이닝하지 않는다.
 */
import simpleheat from 'simpleheat'
import { HEATMAP_GRADIENT } from './legendConstants.ts'

/*
 * 민원현황 §6 — 히트맵 레이어.
 *
 * 지도 overlay pane 위에 **지도 컨테이너 크기의 canvas 한 장**을 깔고, 매 이동·줌이 끝날 때마다
 * 점을 화면 좌표로 다시 투영해 simpleheat 로 새로 그린다(leaflet.heat 와 같은 방식).
 *
 *   - 입력은 백엔드 `/api/hotspots/heatmap` 의 칸(`{lat, lot, cnt}` — 100m 반올림)을 그대로 받는다.
 *     구조만 맞으면 되므로 API 타입을 import 하지 않고 `HeatLayerPoint` 로 받는다.
 *   - 강도 상한 `max` 는 호출부가 정한다 — 기준은 **칸 건수 P95** 다(최댓값이면 상습 지점 한 칸
 *     때문에 나머지가 전부 파랑이 된다). 상한을 넘는 칸은 simpleheat 가 1.0 으로 자른다.
 *   - 🔴 canvas 는 `pointer-events: none` 이다(비대화형) — 아래 행정동 경계 클릭·지도 드래그를
 *     가리지 않는다. 히트맵 자체에는 클릭·툴팁이 없다.
 *   - 🔴 매 `_reset` 마다 canvas 를 `containerPointToLayerPoint([0, 0])` 로 다시 놓는다 — 지도 pane
 *     은 드래그만큼 transform 되므로, 안 하면 드래그 뒤 그림이 밀린다.
 *   - 줌 애니메이션 중에는 canvas 를 **숨긴다**(`zoomstart` → `zoomend`). 안 하면 줌 동안 히트맵이
 *     제자리에 남았다가 끝에 튄다. transform 추종은 Leaflet 사설 API(`_getCenterOffset`)가 필요해
 *     택하지 않았다.
 *   - 지도 `resize`(부모의 `invalidateSize` 가 일으킨다)에서 canvas 크기를 다시 잡고 simpleheat
 *     `resize()` 후 다시 그린다.
 *   - 반경·블러 기본 `radius 18 · blur 15`. 초기값 — 화면 확인 후 조정.
 *     P95 상한의 근거는 위 `max` 항목(호출부 `MinwonMap.heatP95`) 참고.
 *
 * 🔴 작성 형식은 `L.Layer.extend({...})` 다(Leaflet 확장 관례 — TS `class` 의 미사용 `private`
 *    멤버가 `noUnusedLocals` TS6133 이 되는 함정도 피한다). 바깥에는 팩토리 `createHeatLayer` 와
 *    인터페이스 `HeatLayer` 만 내보낸다.
 */

/** 히트맵 입력 점. `HotspotHeatmapPoint` 와 같은 모양이다. */
export interface HeatLayerPoint {
  lat: number
  lot: number
  cnt: number
}

export interface HeatLayerOptions extends L.LayerOptions {
  /** 점 반경(px). 기본 18. */
  radius?: number
  /** 블러 반경(px). 기본 15. */
  blur?: number
  /** 최소 불투명도(0~1). 기본 0.05(simpleheat 기본값). */
  minOpacity?: number
  /** 그라디언트 `{정지점: 색}`. 기본 `HEATMAP_GRADIENT`. */
  gradient?: Readonly<Record<number, string>>
}

/** `createHeatLayer` 가 돌려주는 레이어. `L.Layer` 이므로 `addTo`·`remove` 를 그대로 쓴다. */
export interface HeatLayer extends L.Layer {
  /**
   * 점과 강도 상한을 바꾸고 (지도에 붙어 있으면) 즉시 다시 그린다.
   * `max` 가 0 이하이면 1 로 본다(빈 데이터·0건 방어 — 0 으로 나누면 전 칸이 최대 강도가 된다).
   */
  setData(points: ReadonlyArray<HeatLayerPoint>, max: number): this
}

const DEFAULT_RADIUS = 18
const DEFAULT_BLUR = 15
const DEFAULT_MIN_OPACITY = 0.05

/**
 * `extend` 안 메서드의 `this` 모양 — 이 파일 밖으로 내보내지 않는다.
 * ⚠️ `HeatLayer`(= `L.Layer`)를 extends 하지 않는다 — `L.Layer._map` 이 `protected` 라
 *    클래스가 아닌 인터페이스가 그 멤버를 다시 선언하면 TS2430 이다.
 */
interface HeatLayerInternal {
  options: HeatLayerOptions
  _map: L.Map | undefined
  _canvas: HTMLCanvasElement | undefined
  _heat: simpleheat.Instance | undefined
  _points: ReadonlyArray<HeatLayerPoint>
  _max: number
  setData(points: ReadonlyArray<HeatLayerPoint>, max: number): this
  _reset(): void
  _resizeCanvas(): void
  _onZoomStart(): void
  _onZoomEnd(): void
}

/**
 * ⚠️ `L.Layer.extend` 의 반환 타입은 `typeof Layer` 와의 교차라 `new` 가 인자 0개로 추론된다
 *    (TS2554) — 생성자 시그니처를 여기서 한 번 좁혀 둔다.
 */
const HeatLayerImpl = L.Layer.extend({
  options: {
    radius: DEFAULT_RADIUS,
    blur: DEFAULT_BLUR,
    minOpacity: DEFAULT_MIN_OPACITY,
    gradient: HEATMAP_GRADIENT,
  } satisfies HeatLayerOptions,

  initialize(this: HeatLayerInternal, options?: HeatLayerOptions) {
    L.setOptions(this, options)
    this._points = []
    this._max = 1
  },

  onAdd(this: HeatLayerInternal, map: L.Map) {
    const canvas = L.DomUtil.create('canvas', 'leaflet-layer jdh-heat-layer')
    canvas.style.pointerEvents = 'none'
    this._canvas = canvas
    this._resizeCanvas()

    const opts = this.options
    const heat = simpleheat(canvas)
    heat.radius(opts.radius ?? DEFAULT_RADIUS, opts.blur ?? DEFAULT_BLUR)
    heat.gradient(opts.gradient ?? HEATMAP_GRADIENT)
    this._heat = heat

    map.getPanes().overlayPane.appendChild(canvas)
    this._reset()
    return this
  },

  onRemove(this: HeatLayerInternal) {
    if (this._canvas) L.DomUtil.remove(this._canvas)
    this._canvas = undefined
    this._heat = undefined
    return this
  },

  /** Leaflet 이 add/remove 시 자동으로 on/off 한다(컨텍스트 = 이 레이어). */
  getEvents(this: HeatLayerInternal) {
    return {
      moveend: this._reset,
      zoomstart: this._onZoomStart,
      zoomend: this._onZoomEnd,
      resize: this._resizeCanvas,
    }
  },

  setData(this: HeatLayerInternal, points: ReadonlyArray<HeatLayerPoint>, max: number) {
    this._points = points
    this._max = max > 0 ? max : 1
    if (this._map) this._reset()
    return this
  },

  /** 지도 컨테이너 크기에 canvas 를 맞춘다. `resize` 이벤트에서도 불린다. */
  _resizeCanvas(this: HeatLayerInternal) {
    const map = this._map
    const canvas = this._canvas
    if (!map || !canvas) return
    const size = map.getSize()
    canvas.width = size.x
    canvas.height = size.y
    if (this._heat) {
      this._heat.resize()
      this._reset()
    }
  },

  /** 위치 재설정 + 재투영 + 다시 그리기. */
  _reset(this: HeatLayerInternal) {
    const map = this._map
    const canvas = this._canvas
    const heat = this._heat
    if (!map || !canvas || !heat) return

    // 🔴 pane transform 을 상쇄 — 안 하면 드래그 뒤 좌표가 밀린다.
    L.DomUtil.setPosition(canvas, map.containerPointToLayerPoint([0, 0]))

    // 화면 밖 점은 반경+블러만큼 여유를 두고 거른다(가장자리에서 번짐이 잘리지 않게).
    const size = map.getSize()
    const pad = (this.options.radius ?? DEFAULT_RADIUS) + (this.options.blur ?? DEFAULT_BLUR)
    const data: Array<[number, number, number]> = []
    for (const p of this._points) {
      const pt = map.latLngToContainerPoint([p.lat, p.lot])
      if (pt.x < -pad || pt.y < -pad || pt.x > size.x + pad || pt.y > size.y + pad) continue
      data.push([Math.round(pt.x), Math.round(pt.y), p.cnt])
    }

    heat.max(this._max).data(data).draw(this.options.minOpacity ?? DEFAULT_MIN_OPACITY)
  },

  _onZoomStart(this: HeatLayerInternal) {
    if (this._canvas) this._canvas.style.visibility = 'hidden'
  },

  _onZoomEnd(this: HeatLayerInternal) {
    this._reset()
    if (this._canvas) this._canvas.style.visibility = ''
  },
}) as unknown as new (options?: HeatLayerOptions) => HeatLayer

/** 히트맵 레이어를 만든다. 데이터는 `setData` 로 넣는다. */
export function createHeatLayer(options?: HeatLayerOptions): HeatLayer {
  return new HeatLayerImpl(options)
}
