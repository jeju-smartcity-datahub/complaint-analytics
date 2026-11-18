/*
 * 민원유형 공통코드(01~05, 미분류 99) → 색 토큰 · 라벨.
 * 읍면동 순위 누적바와 추이 차트가 함께 쓴다 — 한쪽만 색이 바뀌는 것을 막기 위해 공용 모듈로 둔다.
 */

/** 유형별 색 토큰 + ECharts 폴백 색 + 라벨. */
export const TYPE_META: Record<string, { token: string; fallback: string; label: string }> = {
  '02': { token: '--series-illegal', fallback: '#06f', label: '인도·횡단보도 점유' },
  '01': { token: '--series-double', fallback: '#ff9200', label: '안전시설 인근 위반' },
  '03': { token: '--series-facility', fallback: '#00bf40', label: '장애인전용구역 위반' },
  '05': { token: '--series-etc', fallback: '#cb59ff', label: '기타 불법주정차' },
  '04': { token: '--series-ev', fallback: '#00bdde', label: '친환경차충전구역 위반' },
  '99': { token: '--line-alternative', fallback: '#70737c14', label: '기타(미분류)' },
}

/** 차트 범례·스택 순서. 미분류 99 는 백엔드가 집계에서 제외하므로 넣지 않는다. */
export const TYPE_ORDER = ['01', '02', '03', '04', '05'] as const

/**
 * CSS 커스텀 프로퍼티를 실제 색 문자열로 해석한다.
 *
 * ECharts 는 캔버스에 그리므로 `var(--series-illegal)` 을 이해하지 못한다 — 값을 미리 풀어야 한다.
 * `getComputedStyle` 은 var() 치환이 끝난 값을 주지만, 토큰이 아직 안 붙었거나 브라우저가 원본
 * 토큰열을 돌려주는 경우가 있어 폴백 헥스를 함께 둔다.
 */
export function readSeriesColor(token: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const v = getComputedStyle(document.documentElement).getPropertyValue(token).trim()
  return v && !v.startsWith('var(') ? v : fallback
}
