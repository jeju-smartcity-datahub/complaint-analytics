import type { IconName } from './icons'

export interface NavLeaf {
  to: string
  label: string
}

/** 사이드바에 하위 메뉴로 펼쳐지는 그룹. */
export interface NavGroup {
  icon: IconName
  label: string
  items: NavLeaf[]
}

export const ANALYSIS_GROUP: NavGroup = {
  icon: 'chart',
  label: '분석·시뮬레이션',
  items: [
    { to: '/analysis/hotspot', label: '불법 주차 집중 구역 분석' },
    { to: '/analysis/policy-simulation', label: '정책 효과 시뮬레이션' },
    { to: '/analysis/lifestyle-simulation', label: '생활권 시뮬레이션' },
    { to: '/analysis/recommendation', label: '구역추천' },
    { to: '/analysis/investment-priority', label: '투자우선순위 보드' },
  ],
}

export const REPORT_GROUP: NavGroup = {
  icon: 'document',
  label: '보고서·알림',
  items: [
    { to: '/reports', label: '보고서 목록' },
    { to: '/reports/management', label: '보고서 관리' },
    { to: '/alerts/inquiry', label: '위험단계 알림 조회' },
    { to: '/alerts/management', label: '위험단계 알림 관리' },
  ],
}

/**
 * 🔴 D13 — 설정(`/settings`)은 **어느 메뉴의 경로도 아니다**(탭 경로 분리).
 *    그래서 접근 판정은 하위 경로를 **명시 목록으로** 두고 각각 정확 일치로 검사한다
 *    (접두 매칭 금지 — R5). 사이드바는 `firstAllowed(SETTINGS_PATHS)` 로 링크 목적지를 정한다.
 *
 * ⚠️ 순서가 곧 기본 탭 순서다 — 시드 `sort_seq`(M0602=1 · M0603=2)와 같은 순서를 유지한다.
 */
export const SETTINGS_PATHS = [
  '/settings/menu-authority',
  '/settings/analysis-weight',
] as const
