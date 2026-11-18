/*
 * 위험단계 배지.
 *
 * 🔴 **코드 → (라벨 · 클래스) 매핑은 이 파일 한 곳에만 둔다.**
 * 백엔드 응답 어디에도 단계 라벨이 없다 — 알림 이력(`RiskAlertVO`)·수신자(`NotiReceiverVO`)·
 * 기준(`RiskCriteriaVO`) 모두 `riskLvlCd` 코드값만 내린다(의도된 계약). 화면마다 라벨을 지어내면
 * '심각/위험' 처럼 표기가 갈리므로 알림 조회·알림 관리 두 화면이 이 컴포넌트만 쓴다.
 *
 * 클래스(`.badge--severe/--warn/--caution`)는 `dashboard.css:620~` 의 시안 색을 그대로 쓴다.
 *
 * 배지가 아닌 자리(조회 화면의 단계 세그먼트 · 수신자 추가 모달의 단계 칩)에서도 같은 라벨이 필요해
 * 매핑 자체를 **named export** 로 연다. 화면이 라벨을 다시 적지 않게 하려는 것이므로
 * 소비처는 이 상수를 import 해서 쓰고 자체 매핑을 만들지 않는다.
 */

/**
 * RISK_LVL_CD 코드 → 배지 라벨 + 색상 변형자.
 * 순서는 `TB_COM_CD.sort_seq`(심각 → 경고 → 주의)와 같아 그대로 렌더링하면 시안 순서가 된다.
 */
export const RISK_LEVELS = [
  { code: 'CRIT', label: '심각', modifier: 'badge--severe' },
  { code: 'WARN', label: '경고', modifier: 'badge--warn' },
  { code: 'CAUT', label: '주의', modifier: 'badge--caution' },
] as const

interface RiskBadgeProps {
  /** 위험단계 코드. CRIT | WARN | CAUT. */
  code: string
  /** 함께 붙일 클래스. 예) 기준 카드의 `crit-row__tag`. */
  className?: string
}

export default function RiskBadge({ code, className }: RiskBadgeProps) {
  // 모르는 코드는 색 없이 코드값을 그대로 보여준다 — 조용히 빈 배지가 되지 않게 한다.
  const level = RISK_LEVELS.find((l) => l.code === code)
  const classes = ['badge', level?.modifier, className].filter(Boolean).join(' ')

  return <span className={classes}>{level ? level.label : code}</span>
}
