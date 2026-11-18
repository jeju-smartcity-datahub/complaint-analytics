import type { LifezoneQuadrant } from '../../api/lifezoneSupply'
import { resolveCssColor } from './lifezoneGrade'

/*
 * 수급 분석의 사분면 → 색·배지 매핑 (TSK-4089).
 *
 * 🔴 **범주형 4색이다 — 순차 팔레트가 아니다**(인계서 §4-2). 등급(심각→낮음)은 하나의 값을
 * 크기순으로 자른 **순서 있는** 구간이라 색이 진해지는 램프가 맞지만, 사분면은 민원·공급 두 축을
 * 교차시킨 **순서 없는 범주**다. 우선순위 개념은 `Q1`(부족 의심) 하나뿐이고 `Q2`~`Q4` 사이에는
 * "더 나쁨" 이 없다 — 그래서 색을 램프로 읽히게 배치하면 없는 순서를 만들어 낸다.
 *
 * 🔴 **사분면명·범례 문구를 이 파일에 적지 않는다.** 이름은 응답 `qdrnNm`, 범례 문구는
 * `qdrnCriteria[].lgndTxt`(임계값 숫자가 박혀 있다)가 정본이다. 여기는 **색만** 고른다 —
 * `lifezoneGrade.ts` 가 등급에 대해 하는 일과 같은 역할이다.
 *
 * 🔁 **TSK-4258 — 구 스냅샷(사분면) 보고서 렌더 전용으로 남는다**(D14). 화면(지도·패널·팝업)은 부족률
 * 등간격 5단계(D19)로 바뀌어 `lifezoneGrade.ts` 를 쓰며 이 파일을 import 하지 않는다. 지우면 TSK-4258
 * 이전에 저장된 보고서의 미리보기·다운로드가 사분면 색을 잃는다.
 */

/**
 * 사분면 색 (CSS 변수).
 *
 * `Q1` 이 붉은 계열인 것은 인계서 §4-2 가 정한 것이다(최우선 = 부족 의심). 나머지 셋은 서로
 * 구분되기만 하면 되며, 기존 5등급 팔레트와 같은 계열에서 골라 두 탭이 한 시스템으로 보이게 했다.
 */
export const QUADRANT_COLOR_VAR: Record<LifezoneQuadrant, string> = {
  Q1: 'var(--red-50)',
  Q2: 'var(--orange-50)',
  Q3: 'var(--blue-50)',
  Q4: 'var(--cool-neutral-80)',
}

/**
 * 사분면 배지 클래스 — 등급 배지(`analysis.css` · `dashboard.css`)를 재사용한다.
 *
 * ⚠️ 클래스 **이름**은 등급에서 왔지만(`badge--severe` 등) 여기서는 색 팔레트로만 쓴다.
 * 배지에 찍는 **글자는 응답 `qdrnNm`**(`부족 의심` 등)이라 화면에 "심각" 이 나타나지는 않는다.
 * 배지 색을 따로 정의하지 않은 이유는 색 규칙이 두 벌이 되는 것을 피하려는 것이다.
 */
export const QUADRANT_BADGE_CLASS: Record<LifezoneQuadrant, string> = {
  Q1: 'badge badge--severe',
  Q2: 'badge badge--warn',
  Q3: 'badge badge--caution',
  Q4: 'badge badge--low',
}

/** 범례·요약이 도는 순서. 응답도 이 순서로 오지만 화면이 응답 순서에 기대지 않게 고정해 둔다. */
export const QUADRANT_ORDER: LifezoneQuadrant[] = ['Q1', 'Q2', 'Q3', 'Q4']

const quadrantColorCache = new Map<LifezoneQuadrant, string>()

/**
 * 사분면 채움색을 **해석된 값**으로 돌려준다.
 *
 * 🔴 Leaflet 은 `var(--red-50)` 을 읽지 못한다(SVG 속성이라 CSS 변수가 걸리지 않는다) — DOM 에
 * 한 번 태워 계산값을 꺼낸다. 한 번 구한 값은 캐시한다(셀마다 DOM 을 만들면 수천 번이 된다).
 */
export function resolveQuadrantColor(qdrn: LifezoneQuadrant): string {
  const hit = quadrantColorCache.get(qdrn)
  if (hit) return hit
  const resolved = resolveCssColor(QUADRANT_COLOR_VAR[qdrn])
  quadrantColorCache.set(qdrn, resolved)
  return resolved
}

/** 테마 전환 등으로 색이 바뀌었을 때 캐시를 비운다. */
export function clearQuadrantColorCache(): void {
  quadrantColorCache.clear()
}
