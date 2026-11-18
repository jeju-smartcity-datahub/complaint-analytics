/**
 * 상단 알림 벨 → 민원현황 "해당 읍면동 포커스" 요청 계약.
 *
 * 벨(`NotificationBell`)이 `navigate('/complaints', { state })` 로 싣고, 민원현황(`ComplaintsPage`)이
 * 받아 필터를 알림 조건으로 맞춘 뒤 그 동을 §7 순위 카드 행 클릭과 같은 경로로 활성화한다.
 *
 * 🔴 **1회성이다** — 받는 쪽이 적용 즉시 history state 를 지운다. 남기면 새로고침·뒤로가기 때 다시 강조된다.
 *    쿼리스트링이 아니라 state 로 싣는 이유도 같다(`ReportsPage` 의 `highlightRptpId` 선례).
 * 페이지 ↔ 컴포넌트가 서로를 import 하지 않도록 계약은 여기 둔다.
 */

/** 벨 알림의 동을 지도에서 활성화하지 못했을 때 (✅ 사용자 결정 2026-10-21). */
export const FOCUS_EMD_MISSING_MESSAGE =
  '선택한 지역을 지도에서 찾지 못했습니다. 기간이나 지역 조건을 확인해 주세요.'

export interface ComplaintFocus {
  /** 활성화할 읍면동코드. */
  emdCd: string
  /** 알림일 `yyyy-MM-dd` — 민원현황 기간을 이 날로 맞춘다. */
  ymd: string
}

export interface ComplaintFocusState {
  focusEmd?: ComplaintFocus
}

const YMD = /^\d{4}-\d{2}-\d{2}$/

/** `location.state`(unknown) 에서 포커스 요청을 꺼낸다. 모양이 다르면 `null`. */
export function readComplaintFocus(state: unknown): ComplaintFocus | null {
  const focus = (state as ComplaintFocusState | null)?.focusEmd
  if (!focus || typeof focus.emdCd !== 'string' || !focus.emdCd) return null
  if (typeof focus.ymd !== 'string' || !YMD.test(focus.ymd)) return null
  return { emdCd: focus.emdCd, ymd: focus.ymd }
}
