/*
 * §5 범례 + hotspot zone 색상 매핑 공통 상수 (TSK-3874 정리).
 *
 * 2026-09-04 갱신: 백분위 임계값(10/25/50) 과 위험 단계 색상이 MinwonMap.tsx 와
 * LegendCard.tsx(RISK_LEVEL_THRESHOLDS) 두 곳에 중복되어 있어, 한쪽만 갱신되면
 * hotspot zone 색과 범례 색이 어긋나는 회귀가 잠재 (PR #33 Copilot review).
 *
 * 2026-09-30 갱신 (TSK-4167 Step 9 · D14): 임계값 분기가 세 곳(이 파일의 dead zone 색상
 * 함수 · MinwonMap 의 로컬 구현 · 분모가 **노출 행 수**였던 §7 카드 dot 색상 함수)에
 * 흩어져 있던 것을 `pickRiskGrade` 하나로 모았다. 앞뒤의 두 함수는 **제거**했다 —
 * 틀린 규칙(노출 행 수 분모)을 코드에 남기면 다음 사람이 그것을 쓴다.
 *
 * 본 파일이 단일 출처(single source of truth) 이다:
 *   - RISK_LEVEL_THRESHOLDS — 범례 카드에서 색상/라벨/임계값을 그대로 노출.
 *   - pickRiskGrade / RISK_GRADE_COLOR — 지도 polygon 과 순위 카드 3종이 **같은
 *     등급**을 말하게 하는 공용 판정 (분모는 전체 zone 수 — D13).
 *     🔁 2026-10-02 (TSK-4197 Step 7-2) — 판정 쪽(`pickRiskGrade`·`PCT_*`·`RiskGrade`)은
 *     `lib/analysis/riskGrade.ts` 로 옮기고 여기서 re-export 한다. 보고서 템플릿이
 *     같은 규칙을 써야 하는데 `lib/` 가 `components/` 를 역참조할 수 없어서다.
 *     색은 화면 전용이라 그대로 남는다.
 *   - COLOR_*, NEUTRAL_COLOR — 여러 곳에서 공유하는 원시 값.
 *
 * 가이드 §5 의 '고정 3개 라벨·임계값 자체는 그대로 표시' 결정 정합 — 라벨/
 * 임계값은 한 곳에서 관리하고 양쪽이 같이 본다.
 *
 * 색상 토큰 (var(--color-danger-600) 등) 이 디자인 시스템에 추가되면
 * RISK_LEVEL_THRESHOLDS[*].color / COLOR_* 자리를 토큰으로 갈아끼우면 된다.
 */

/**
 * 🔁 2026-10-02 (TSK-4197 Step 7-2) — `PCT_10/25/50` · `RiskGrade` · `pickRiskGrade` 는
 * `lib/analysis/riskGrade.ts` 로 **옮겼다**. 보고서 템플릿(`lib/analysis`)이 같은 판정을
 * 써야 하는데 화면 컴포넌트를 역참조할 수 없어서다(규칙 복제 금지 — 복제하면 카드와
 * 문서의 행 수가 소리 없이 갈린다). `PCT_10` 의 "백엔드 `FLOOR(N * 0.1)` 과 짝" 경고도
 * 상수와 함께 그쪽으로 갔다.
 *
 * 🔴 여기서는 **re-export** 만 한다 — 기존 소비자 4곳(`MinwonMap` · `EmdCompareCard` ·
 * `EmdCrdnRankCard` · `EmdShortageRankCard`)의 import 경로를 깨뜨리지 않기 위해서다.
 * 색(`RISK_GRADE_COLOR`·`RISK_LEVEL_THRESHOLDS`)은 화면 전용이라 이 파일에 남는다
 * → 의존은 `legendConstants` → `riskGrade` **단방향**이다.
 */
import { PCT_10, PCT_25, PCT_50, type RiskGrade } from '../../lib/analysis/riskGrade.ts'

export { PCT_10, PCT_25, PCT_50, pickRiskGrade } from '../../lib/analysis/riskGrade.ts'
export type { RiskGrade } from '../../lib/analysis/riskGrade.ts'

/** 가이드 §5 의 위험 단계 색상. */
export const COLOR_SEVERE = '#DC2626'
export const COLOR_WARNING = '#F97316'
export const COLOR_CAUTION = '#2563EB'

/**
 * 히트맵 표시 모드의 5색 그라디언트 (TSK-4295 §3-3). simpleheat `.gradient()` 형식
 * `{정지점(0~1): 색}` 이다.
 *
 * 사용처 두 곳이 이 상수 하나를 본다 — `HeatLayer` 의 기본 gradient + `LegendCard` 의
 * 그라디언트 막대(Step 5). 한쪽만 바꾸면 지도 색과 범례 색이 어긋난다.
 *
 * 🔴 끝색은 `COLOR_SEVERE` 를 **참조**한다(심각 색과 통일 — 값을 다시 적지 않는다).
 * 🔴 색은 **조회 범위 안의 상대 밀도**다(강도 상한 = 칸 건수 P95). 범례에 심각·경고 같은
 *    등급 단어를 붙이지 않는다 — 같은 빨강이라도 "어제" 와 "1년" 의 건수는 전혀 다르다.
 */
export const HEATMAP_GRADIENT: Readonly<Record<number, string>> = {
  0.2: '#2B6CFF', // 파랑
  0.4: '#22C55E', // 초록
  0.6: '#FACC15', // 노랑
  0.8: '#F97316', // 주황
  1.0: COLOR_SEVERE, // 빨강 (#DC2626)
}

/** 임계값 밖 (rank 백분위 50% 초과) 의 무채색 fallback. */
export const NEUTRAL_COLOR = '#94A3B8'

/**
 * §5 고정 3개 위험 단계 — 색상/라벨/임계값 보조 텍스트 한 곳에서 관리.
 * 2026-08-27 회의 결정: rank 백분위 기준 3단계. 이전 count 절대값(40/20/10) 4단계 폐기.
 *
 * 🔁 **2026-10-02 (TSK-4197) — shortage 의 "{label} 그룹" 표기는 폐지됐다.**
 * 그 표기는 'Jenks 등급 분류 결과물' 임을 드러내려는 것이었는데 **수요부족이 더 이상
 * Jenks 등급을 쓰지 않는다**(D14). 세 레이어가 같은 `pickRiskGrade` 규칙이므로
 * `LegendCard` 는 이 상수를 **가공 없이 그대로** 쓴다(`view` prop 도 없앴다).
 * ⚠️ 종전에 `LegendCard` 가 같은 이름의 상수를 **로컬로 중복 정의**하고 있었다 —
 * 이제 이 파일 하나가 정본이다.
 */
export const RISK_LEVEL_THRESHOLDS: ReadonlyArray<{
  percentile: number
  label: string
  sub: string
  color: string
}> = [
  { percentile: PCT_10, label: '심각', sub: '상위 10%', color: COLOR_SEVERE },
  { percentile: PCT_25, label: '경고', sub: '상위 10~25%', color: COLOR_WARNING },
  { percentile: PCT_50, label: '주의', sub: '상위 25~50%', color: COLOR_CAUTION },
]

/**
 * 순위 카드 3종 InfoTip 공통 문장 (TSK-4242 Step 4-2).
 * 🔴 경계 숫자는 `PCT_*` 에서만 온다(D5) — 문자열에 다시 적지 않는다.
 * D6 — 팝업 안 문장이라 `※` 를 붙이지 않는다.
 */
export const RANK_GRADE_NOTE =
  `색은 순위 백분위입니다 — 상위 ${PCT_10}% 심각 · ${PCT_25}% 경고 · ${PCT_50}% 주의. 주의 밖 지역은 목록에서 뺍니다.`
export const DELTA_HOVER_NOTE = '마우스를 올리면 수치를 볼 수 있습니다.'
export const RANK_DELTA_NOTE =
  `▲▼ 는 직전 같은 길이 기간과 비교한 증감입니다(▲ 증가 · ▼ 감소). ${DELTA_HOVER_NOTE}`

export const RISK_GRADE_COLOR: Record<RiskGrade, string> = {
  심각: COLOR_SEVERE, 경고: COLOR_WARNING, 주의: COLOR_CAUTION,
}

/**
 * 카드 노출 행 한도. 기본 5 (2026-08-27 확정).
 * 🔴 `EMD_COMPARE_MAX_LIMIT`(15) 은 이제 §8 `NegEmdCompareCard` **하나만** 쓴다 —
 * 순위 카드 3종은 상한을 없애고 `pickRiskGrade` 등급 유무로 노출 범위를 정한다(D13).
 */
export const EMD_COMPARE_DEFAULT_LIMIT = 5
export const EMD_COMPARE_MAX_LIMIT = 15

/**
 * §7 'deltaPct' 색상 (민원 건수 증감율) — §7 자체 규칙 (§5 무관).
 *
 *   - deltaPct > 0 → 빨강(나쁨 — 민원 증가)
 *   - deltaPct < 0 → 초록(좋음 — 민원 감소)
 *   - deltaPct = 0 → 검정(변화 없음)
 *   - deltaPctInferred = true → 빨강 + ⚠️ (이전 0건 → 1로 치환, 비교 부정확)
 *
 * mock 의 ▲초록/▼빨강(매출 기준) 과 반대 — 민원은 늘면 나쁜 것이므로 반전 (2026-08-21 확정).
 */
export const COLOR_DELTA_UP = '#DC2626'
export const COLOR_DELTA_DOWN = '#16A34A'
export const COLOR_DELTA_FLAT = '#475569'

/** §8 sentiment 3색 (가이드 §8). 디자인 토큰이 따로 없으면 hex 그대로. */
export const COLOR_SENTIMENT_POSITIVE = '#22C55E'
export const COLOR_SENTIMENT_NEUTRAL = '#94A3B8'
export const COLOR_SENTIMENT_NEGATIVE = '#DC2626'
