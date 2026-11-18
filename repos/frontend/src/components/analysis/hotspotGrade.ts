import type { GradeCriterion, HotspotGrade } from '../../api/analysis'

/*
 * 집중 구역 등급 → 색·라벨 매핑.
 *
 * 🔴 **등급 판정은 백엔드가 한다**. 점수 산식이 백엔드에 있으므로 임계값도 같은 곳(`HotspotGrade` enum)에
 * 두고, 프론트는 응답의 `grade` 코드를 받아 **색만 고른다** — 이 파일에 점수 비교 코드를 넣지 않는다.
 * 넣는 순간 지도·목록·PDF·DOCX·대시보드가 서로 다른 등급을 보게 된다.
 *
 * 등급은 점수가 아니라 순위 비율로 판정된다.
 * 이 모듈을 쓰는 곳: 지도 마커·팝업·범례 · 순위 목록의 점 · 내보내기 표.
 */

/**
 * 등급 → 색. **CSS 변수 표기 그대로**라 DOM 요소의 `style` 에는 바로 넣을 수 있지만,
 * SVG `fill` 속성에는 쓸 수 없다 — 그쪽은 아래 `resolveGradeColor()` 로 실제 색을 뽑아 넣는다.
 *
 * `--cool-neutral-80` 은 시안 원시 팔레트 값(`#aeb0b6`)으로, 이 작업에서 `tokens.css` 에 추가했다.
 * 네 색 전부 토큰만 참조하며 폴백 리터럴을 두지 않는다.
 */
export const GRADE_COLOR_VAR: Record<HotspotGrade, string> = {
  SEVERE: 'var(--red-50)',
  WARN: 'var(--orange-50)',
  CAUTION: 'var(--blue-50)',
  NORMAL: 'var(--cool-neutral-80)',
}

/**
 * 등급 → 라벨. 응답에 `gradeNm` 이 함께 오므로 **화면 표시는 응답값을 우선**하고,
 * 이 표는 범례처럼 응답 행과 무관하게 등급 자체를 설명해야 할 때의 기본값이다.
 */
export const GRADE_LABEL: Record<HotspotGrade, string> = {
  SEVERE: '심각',
  WARN: '경고',
  CAUTION: '주의',
  NORMAL: '양호',
}

/**
 * 등급 → 배지 클래스(`dashboard.css` 의 공용 `.badge`).
 * `severe`/`warn`/`caution` 은 `dashboard.css` 것을 그대로 쓰고, 시안에 없던 `normal` 만
 * `analysis.css` 에 같은 형태로 추가했다 — 인라인 스타일을 쓰지 않는다.
 */
export const GRADE_BADGE_CLASS: Record<HotspotGrade, string> = {
  SEVERE: 'badge badge--severe',
  WARN: 'badge badge--warn',
  CAUTION: 'badge badge--caution',
  NORMAL: 'badge badge--normal',
}

/** 범례에 세우는 등급 순서 — 시안과 동일하게 심각 → 경고 → 주의 3행이다(`NORMAL` 은 범례에 없다). */
export const LEGEND_GRADES: HotspotGrade[] = ['SEVERE', 'WARN', 'CAUTION']

/** 범례 한 줄. `text` 는 화면에 그대로 찍는 문구이고, `grade` 는 점 색을 고르는 데 쓴다. */
export interface LegendRow {
  grade: HotspotGrade
  text: string
}

/**
 * 응답의 등급 기준(`gradeCriteria`)으로 범례 문구를 조립한다 — `심각 (상위 5%)` · `경고 (상위 5~10%)`.
 *
 * 🔴 **비율을 여기 적지 않는다.** 단일 출처는 백엔드 `HotspotGrade` enum 이고,
 * 이 함수는 응답이 실어 준 `maxRankPct` 만 늘어놓는다. 각 줄의 하한은 바로 위 줄의 값이다.
 * `gradeCriteria` 가 없거나 비면(구버전 백엔드) **라벨만** 보여 준다.
 */
export function buildLegendRows(criteria?: GradeCriterion[]): LegendRow[] {
  if (!criteria || criteria.length === 0) {
    return LEGEND_GRADES.map((grade) => ({ grade, text: GRADE_LABEL[grade] }))
  }

  return [...criteria]
    .sort((a, b) => a.maxRankPct - b.maxRankPct)
    .map((criterion, i, sorted) => {
      // 라벨은 응답값 우선 — 등급명의 정본도 백엔드다. 비어 있을 때만 로컬 표를 쓴다.
      const label = criterion.gradeNm || GRADE_LABEL[criterion.grade] || criterion.grade
      const text =
        i === 0
          ? `${label} (상위 ${criterion.maxRankPct}%)`
          : `${label} (상위 ${sorted[i - 1].maxRankPct}~${criterion.maxRankPct}%)`
      return { grade: criterion.grade, text }
    })
    .filter((row) => LEGEND_GRADES.includes(row.grade))
}

/**
 * 등급 기준 한 줄 설명 — `심각 상위 5% · 경고 상위 5~10% · 주의 상위 10~15%`.
 * 툴팁·문서가 같은 문구를 쓴다. 비율은 설정값이라 반드시 응답·저장값으로 조립한다.
 */
export function describeGradeRanks(criteria: GradeCriterion[]): string {
  return [...criteria]
    .filter((c) => c.grade !== 'NORMAL')
    .sort((a, b) => a.maxRankPct - b.maxRankPct)
    .map((c, i, sorted) => `${c.gradeNm} 상위 ${i === 0 ? '' : `${sorted[i - 1].maxRankPct}~`}${c.maxRankPct}%`)
    .join(' · ')
}

/**
 * CSS 변수 표기(`var(--red-50)`)를 **실제 색 문자열**(`rgb(255, 66, 66)`)로 해석한다.
 *
 * SVG 의 `fill` 속성(프레젠테이션 속성)은 `var()` 를 받지 않고, 마커는 HTML 문자열로 만들어
 * Leaflet `divIcon` 에 넘기므로 CSS 상속으로 우회할 수도 없다. 그래서 시안과 같은 방법을 쓴다 —
 * 화면 밖 임시 노드에 색을 얹고 `getComputedStyle` 로 브라우저가 해석한 값을 읽어 온다.
 *
 * 임시 노드는 같은 함수 안에서 반드시 제거한다(누수 방지). SSR 환경은 이 앱의 대상이 아니지만
 * `document` 가 없으면 계산할 수 없으므로 표기 문자열을 그대로 돌려준다.
 */
export function resolveCssColor(cssColor: string): string {
  if (typeof document === 'undefined') return cssColor
  const probe = document.createElement('span')
  probe.style.cssText = 'position:absolute;left:-9999px;top:-9999px'
  probe.style.color = cssColor
  document.body.appendChild(probe)
  const resolved = getComputedStyle(probe).color
  document.body.removeChild(probe)
  return resolved
}

/**
 * 등급 → 실제 색 문자열. 마커 SVG·내보내기(캔버스/문서)처럼 `var()` 를 못 쓰는 곳에서 쓴다.
 *
 * ⚠️ 해석에 임시 DOM 노드가 필요해 호출 1회가 **강제 리플로우**를 부른다. 마커 12개를 도는 루프에서
 * 그대로 부르면 12회가 되고 내보내기에서 더 커지므로, 등급이 4개뿐인 점을 이용해 캐시한다.
 * 테마 전환처럼 토큰 값이 런타임에 바뀌는 기능은 아직 없다 — 생기면 `clearGradeColorCache()` 를 부른다.
 */
const gradeColorCache = new Map<HotspotGrade, string>()

export function resolveGradeColor(grade: HotspotGrade): string {
  const hit = gradeColorCache.get(grade)
  if (hit) return hit
  const resolved = resolveCssColor(GRADE_COLOR_VAR[grade])
  gradeColorCache.set(grade, resolved)
  return resolved
}

/** 색 캐시를 비운다(테마 전환 등 토큰 값이 바뀌었을 때). */
export function clearGradeColorCache(): void {
  gradeColorCache.clear()
}
