/*
 * rank 백분위 → 위험 등급 판정 공용 모듈.
 *
 * 🔁 2026-10-02 이동 — 종전 위치는 `components/complaints/legendConstants.ts` 였다.
 * 보고서 템플릿(`lib/analysis/complaintReportTemplate.ts`)이 §4·§5 행 선별에 같은 규칙을
 * 써야 하는데, 템플릿이 화면 컴포넌트(`components/complaints`)를 역참조하지 않는다는
 * 규약과 부딪혔다. 🔴 **함수를 복제하지 않는다** — 판정 규칙이 두 벌이 되면 화면 카드와
 * 문서의 행 수가 소리 없이 갈린다. 그래서 판정 쪽만 `lib/` 로 내리고,
 * `legendConstants.ts` 가 이 모듈을 **re-export** 해 기존 소비자 4곳
 * (`MinwonMap` · `EmdCompareCard` · `EmdCrdnRankCard` · `EmdShortageRankCard`)의
 * import 경로를 그대로 둔다.
 *
 * 🔴 색 상수(`RISK_GRADE_COLOR`·`RISK_LEVEL_THRESHOLDS`)는 **옮기지 않았다** — 화면 전용이고
 * 문서 템플릿은 hex 리터럴을 자체 선언한다. 따라서 의존은 `legendConstants` → 이 모듈
 * **단방향**이다(반대로 두면 순환 import 가 된다).
 */

/**
 * rank 백분위 임계값 (포함). 예: 10 = 상위 10% 이내.
 *
 * 🔴 **`PCT_10` 은 백엔드와 짝이다** — KPI `단속 집중 지역`·`수요 부족 지역` 이
 * `ComplaintMapper.xml` 의 `selectEnforcementCount`·`selectShortageCount` 에서 `FLOOR(N * 0.1)` 로
 * **같은 규칙을 다시 센다**. 이 값을 고치면 그 SQL 두 개도 함께 고쳐야 KPI 와 리스트가 어긋나지 않는다.
 */
export const PCT_10 = 10
export const PCT_25 = 25
export const PCT_50 = 50

/** 위험 등급 3종. 주의 초과(백분위 50% 밖)는 등급이 없다 — `null`. */
export type RiskGrade = '심각' | '경고' | '주의'

/**
 * rank 백분위 → 위험 등급. 🔴 **분모는 전체 zone 수**(지도·리스트·문서 공용).
 * 지도 polygon · 순위 카드 3종 · 보고서 §4·§5 가 같은 등급을 말하게 하는 단일 출처다.
 */
export function pickRiskGrade(rank: number, totalZones: number): RiskGrade | null {
  if (rank <= 0 || totalZones <= 0) return null
  const pct = (rank / totalZones) * 100
  if (pct <= PCT_10) return '심각'
  if (pct <= PCT_25) return '경고'
  if (pct <= PCT_50) return '주의'
  return null
}
