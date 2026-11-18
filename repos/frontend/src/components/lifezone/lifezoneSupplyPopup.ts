import type { LifezoneSupplyCell } from '../../api/lifezoneSupply'
import { formatShortagePct, supplyCellZoneNm } from '../../api/lifezoneSupply'
import { escapeHtml } from '../../lib/report/reportTemplate'
import { GRADE_BADGE_CLASS } from './lifezoneGrade'

/*
 * 수급 격자 셀 팝업의 HTML 조립 (TSK-4089 · 인계서 §4-3 · 🔁 TSK-4258 시안 정합).
 *
 * 🔴 **컴포넌트가 아니라 별도 모듈에 둔다** — `lifezoneCellPopup.ts` 와 같은 이유다.
 *   1. Leaflet 은 팝업 내용을 **HTML 문자열**로 받으므로 이 함수가 XSS 경계다. 순수 함수로
 *      떼어 두어야 브라우저 없이 문자열만으로 이스케이프를 확인할 수 있다.
 *   2. `.tsx` 에서 컴포넌트가 아닌 것을 export 하면 fast-refresh 가 깨진다.
 *
 * 🔴 **판정·문구를 여기서 만들지 않는다.** 등급명은 응답 `gradeNm`, 등급은 응답 `grade` 가 정본이다
 * (부족률 등간격 판정은 서버 — D6·D19). 조건을 여기에 다시 적으면 서버와 갈린다.
 */

/**
 * 수급 셀 팝업 HTML — 민원건수 팝업(`.gp`)과 같은 마크업 계열을 쓴다.
 *
 * 표시 항목(시안 — D12): 머리(등급 배지 · 지역명 — POI 구역명이 있으면 그것, 없으면 시군구 읍면동 · TSK-4272) · 큰 글씨 `부족률 N%` · `수요 N대 · 공급 N면`.
 * 🔁 격자ID 줄은 사용자 요청으로 뺐다(2026-11-09).
 * 순위·민원 건수·주차장 수는 팝업에 싣지 않는다(순위는 결과 리스트가 보인다).
 * 수요 = `demandIndex`(공영주차면 + 민원), 공급 = `pkltCpctySum`(공영주차면).
 *
 * 🔴 **공급 0 이면 `공급 0면` 을 그대로 보인다**(D17) — 지우거나 `-` 로 바꾸지 않는다. 상위 등급이
 * "주차장 없는 격자" 로 채워지는 구조적 한계를 사용자가 직접 보게 하는 장치다.
 * 🔴 **"주차 부족 N면" 이라고 쓰지 않는다**(인계서 §4-4) — 공급량 자체이므로 "공급 N면" 이다.
 *
 * ⚠️ 문자열 조립이라 XSS 경로다 — 읍면동명·등급명 같은 서버 문자열은 예외 없이
 * `escapeHtml` 을 통과시킨다.
 */
export function buildSupplyPopupHtml(cell: LifezoneSupplyCell): string {
  const emd = cell.cntrPoiNm
    ? supplyCellZoneNm(cell)
    : cell.emdNm
      ? `${cell.sggNm ? `${cell.sggNm} ` : ''}${cell.emdNm}`
      : '읍면동 미상'

  return `<div class="gp gp--supply">
    <div class="gp__h">
      <span class="${GRADE_BADGE_CLASS[cell.grade]}">${escapeHtml(cell.gradeNm)}</span>
      <span class="gp__loc">${escapeHtml(emd)}</span>
    </div>
    <div class="gp__big">부족률 ${formatShortagePct(cell.shortageRatio)}<span>%</span></div>
    <div class="gp__bd">
      <span>수요 <b>${cell.demandIndex.toLocaleString()}대</b></span>
      <span>공급 <b>${cell.pkltCpctySum.toLocaleString()}면</b></span>
    </div>
  </div>`
}
