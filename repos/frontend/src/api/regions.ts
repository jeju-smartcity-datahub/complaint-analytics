import { api } from './client'

/*
 * 행정구역(시군구 → 읍면동) 실 API 클라이언트 (TSK-4026 Step 8).
 * 백엔드 계약(`jeju.datahub.region.web.RegionController` 기준):
 *   GET /api/regions → RegionItem[]  (시군구별로 하위 읍면동 배열을 함께 내린다)
 *
 * 지역 select 를 쓰는 화면(보고서 설정 D12 · 알림 조회/수신자 관리 TSK-4028 D9)이 **모두 이 하나**를
 * 쓴다 — 시안의 하드코딩 지역 목록을 화면마다 옮기지 않기 위한 API 다.
 *
 * 목록은 **서버가 정렬해서 내려준다**(읍면동코드 emd_cd 순 — 앞 5자리가 시군구를 뜻해 시군구 묶음이
 * 유지된다). 프론트에서 다시 정렬하지 말고 받은 순서를 그대로 렌더링한다.
 */

/** 읍면동 1건. `RegionItem.emds` 의 원소. */
export interface EmdItem {
  /** 읍면동코드 (8자리). 예) '50110560'. */
  emdCd: string
  /** 읍면동명. 예) '연동'. */
  emdNm: string
}

/** GET /api/regions 배열 항목 (시군구 + 하위 읍면동 목록). */
export interface RegionItem {
  /** 시군구명. 예) '제주시'. */
  sggNm: string
  /** 이 시군구에 속한 읍면동 목록. 없으면 []. */
  emds: EmdItem[]
}

/** 시군구 → 읍면동 트리 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지). */
export async function fetchRegions(): Promise<RegionItem[]> {
  const { data } = await api.get<RegionItem[]>('/regions')
  return data
}
