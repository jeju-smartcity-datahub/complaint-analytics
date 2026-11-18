/*
 * 구역추천 — 표 ↔ 지도 선택 신호 (TSK-4207 Step 6).
 *
 * 순위 리스트(`ZoneRcmdtnResultPanel`)와 지도(`ZoneRcmdtnMap`)가 **같은 선택 하나**를 봐야 양방향 연동이
 * 어긋나지 않는다. 그 타입만 떼어 둔 모듈이다 — 두 컴포넌트가 서로를 import 하면 순환이 된다.
 */

/**
 * 선택된 주차장.
 *
 * 🔴 `pkltId` 만으로는 부족해 `seq` 를 함께 받는다 — 핀을 눌러 다른 곳을 본 뒤 **같은 행을 다시**
 * 누르면 `pkltId` 가 그대로라 effect 가 돌지 않아 지도가 움직이지 않는다. 부모가 선택 이벤트마다
 * `seq` 를 올려 "매번 새 요청" 임을 알린다(집중구역 화면 `HotspotSelection` 과 같은 장치).
 *
 * ⚠️ `rnk` 를 키로 쓰지 않는다 — 유형 범위를 바꾸면 같은 1위가 **다른 주차장**이다.
 */
export interface ZoneRcmdtnSelection {
  pkltId: number
  seq: number
}

/**
 * 공유주차제 보기의 선택 — 후보 **읍면동** 하나 (TSK-4208 Step 5).
 *
 * 요금제 보기와 단위가 달라(주차장 ↔ 읍면동) 타입을 가른다 — 한 타입에 두 키를 넣으면 보기를
 * 바꿨을 때 다른 보기의 선택이 엉뚱한 대상을 가리킨다. `seq` 의 뜻은 위와 같다.
 *
 * ⚠️ `rnk` 를 키로 쓰지 않는다 — 기간을 바꾸면 같은 번호가 **다른 읍면동**이다.
 */
export interface ZoneRcmdtnShareSelection {
  emdCd: string
  seq: number
}
