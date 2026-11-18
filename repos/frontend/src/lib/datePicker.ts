import type { MouseEvent } from 'react'

/**
 * `<input type="date">` 칸을 아무 곳이나 눌러도 달력이 열리게 한다.
 *
 * 브라우저 기본 동작은 **오른쪽 끝 캘린더 아이콘을 정확히 눌렀을 때만** 달력을 연다.
 * 집중구역·생활권 설정 패널은 시안대로 네이티브 입력을 투명하게 덮어(`analysis.css` 의
 * `.field__date-input` — `opacity: 0`) 칸 전체가 트리거처럼 보이지만, 실제로는 **보이지 않는
 * 아이콘 위치**를 맞춰 눌러야 해서 "달력이 안 열린다" 로 느껴진다. 칸(날짜 텍스트 포함)을
 * 누르면 열리도록 `showPicker()` 를 직접 부른다.
 *
 * - `showPicker` 는 Chrome 99+ / Safari 16+ / Firefox 101+ 지원. 미지원 브라우저에서는
 *   optional call 로 조용히 넘어가 **기존 동작(아이콘 클릭)이 그대로 남는다** — 회귀가 없다.
 * - 사용자 제스처 밖이거나 입력이 비활성이면 예외를 던지므로 try/catch 로 감싼다.
 *   (클릭 핸들러 안이라 정상 경로에서는 제스처가 살아 있다.)
 * - `onChange` 는 건드리지 않는다 — 값 갱신 경로는 종전 그대로다.
 */
export function openDatePicker(e: MouseEvent<HTMLInputElement>) {
  try {
    e.currentTarget.showPicker?.()
  } catch {
    // 미지원·비활성 상태는 무시하고 브라우저 기본 동작에 맡긴다.
  }
}
