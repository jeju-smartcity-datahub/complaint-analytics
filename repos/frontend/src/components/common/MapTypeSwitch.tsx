// src/components/common/MapTypeSwitch.tsx
import type { BaseMapKind } from './mapTiles'

/*
 * 배경지도 전환 컨트롤 — 시안의 `.map-type` 이식.
 * 마크업·클래스명은 시안 번들 원문 그대로다(버튼 2개 + `is-active`).
 *
 * 🔴 **leaflet 을 모른다.** 지도 인스턴스도, 타일 레이어도 받지 않는다 — 값과 콜백뿐이다.
 * 지도를 만지려면 부모의 마운트 effect 보다 자식 effect 가 먼저 도는 문제를 콜백으로
 * 우회해야 하는데(시안이 `getMap` 을 쓰는 이유), 상태를 부모에 두면 그 문제가 아예 없다.
 *
 * ⚠️ 민원현황은 자체 인라인 구현(`.minwon-map__basemap__btn`)을 쓴다 — 클래스명이 달라
 * 이 파일이 그 화면에 영향을 주지 않는다.
 */

export interface MapTypeSwitchProps {
  value: BaseMapKind
  onChange: (next: BaseMapKind) => void
}

export default function MapTypeSwitch({ value, onChange }: MapTypeSwitchProps) {
  return (
    <div className="map-type" role="group" aria-label="배경지도 종류">
      <button
        type="button"
        className={value === 'base' ? 'is-active' : ''}
        aria-pressed={value === 'base'}
        onClick={() => onChange('base')}
      >
        일반지도
      </button>
      <button
        type="button"
        className={value === 'aerial' ? 'is-active' : ''}
        aria-pressed={value === 'aerial'}
        onClick={() => onChange('aerial')}
      >
        항공지도
      </button>
    </div>
  )
}
