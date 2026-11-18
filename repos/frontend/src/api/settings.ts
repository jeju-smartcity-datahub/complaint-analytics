import { api } from './client'

/*
 * 설정 > 메뉴 권한 관리 실 API 클라이언트 (TSK-4019 Step 6).
 * 백엔드 계약(Step 5 로컬 실측):
 *   GET /api/menu-authorities/authorities              → AuthrtItem[]      (실측 길이 3)
 *   GET /api/menu-authorities/menus                    → MenuAuthrtItem[]  (2026-08-28 실측 14건 — 사용자 정보 메뉴 삭제 후)
 *   PUT /api/menu-authorities/menus/{menuId}/authorities → MenuAuthrtItem  (갱신된 1건)
 *
 * 두 목록 API 모두 **서버가 정렬해서 내려준다**(메뉴는 menuLvl·sortSeq 기준).
 * 프론트에서 다시 정렬하지 말고 받은 순서를 그대로 렌더링한다.
 */

/** GET /api/menu-authorities/authorities 배열 항목 (권한 마스터). */
export interface AuthrtItem {
  /** 권한 코드. ROLE_SYSTEM_ADMIN / ROLE_SERVICE_ADMIN / ROLE_USER. */
  authrtCd: string
  /** 권한 이름. 예) '시스템 관리자'. */
  authrtNm: string
  /** 권한 설명. 없으면 null. */
  authrtExpln: string | null
  /**
   * 권한 유형 코드 (TSK-4115 D7 재개정). 유형이 없으면 null.
   *
   * `'SUPER'` 이면 **모든 화면 메뉴에 접근이 보장되는** 권한이다 — 화면에서 뺄 수 없고
   * (체크박스가 항상 체크 + 비활성), 저장 요청에서 빠져 있어도 서버가 도로 채워 넣는다.
   * ⚠️ 롤 이름(`ROLE_SYSTEM_ADMIN`)을 프론트에 하드코딩하지 않는다 — **유형으로만** 판정한다.
   */
  authrtTypeCd: string | null
}

/** GET /api/menu-authorities/menus 배열 항목 (메뉴 + 부여된 권한 코드). */
export interface MenuAuthrtItem {
  /** 메뉴 ID. 예) 'M01'. */
  menuId: string
  /** 상위 메뉴 ID. 최상위면 null. */
  upMenuId: string | null
  /** 메뉴 이름. 예) '대시보드'. */
  menuNm: string
  /** 메뉴 경로. 예) '/'. 폴더 메뉴는 경로가 비어 있을 수 있다. */
  menuPathNm: string
  /** 메뉴 깊이. 최상위=1. */
  menuLvl: number
  /** 같은 depth 안에서의 정렬 순서. */
  sortSeq: number
  /**
   * 메뉴 구분 이름. **일반 사용자(ROLE_USER) 권한이 부여된 메뉴면 '메뉴', 아니면 '관리'** (TSK-4077).
   * 서버 파생값이라 프론트가 계산하지 않는다 — 권한을 저장하면 PUT 응답의 이 값도 함께 뒤집힌다.
   */
  menuSeNm: '메뉴' | '관리'
  /** 이 메뉴에 부여된 권한 코드 목록. 없으면 []. */
  authrtCds: string[]
}

/** PUT /api/menu-authorities/menus/{menuId}/authorities 요청 바디. */
export interface MenuAuthrtSaveRequest {
  /** 이 메뉴에 남길 권한 코드 전체. 서버가 기존 매핑을 이 목록으로 전체 교체한다. */
  authrtCds: string[]
}

/** 권한 마스터 조회 (서버 정렬 순서 그대로 사용). */
export async function fetchAuthorities(): Promise<AuthrtItem[]> {
  const { data } = await api.get<AuthrtItem[]>('/menu-authorities/authorities')
  return data
}

/** 메뉴 + 권한 매핑 목록 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지). */
export async function fetchMenuAuthorities(): Promise<MenuAuthrtItem[]> {
  const { data } = await api.get<MenuAuthrtItem[]>('/menu-authorities/menus')
  return data
}

/**
 * 메뉴 1건의 권한 매핑을 전체 교체 저장하고 갱신된 항목을 돌려받는다.
 * 오류: 없는 menuId → 404 / 폴더 메뉴·잘못된 권한코드·바디 누락 → 400.
 *
 * ⚠️ `SUPER` 유형 권한(`AuthrtItem.authrtTypeCd === 'SUPER'`)은 요청에서 빠져 있어도 서버가 저장
 *    직전에 더한다 (TSK-4115 D7 재개정) — **요청과 응답이 다를 수 있으므로 응답을 정본으로 삼는다.**
 */
export async function saveMenuAuthorities(
  menuId: string,
  authrtCds: string[],
): Promise<MenuAuthrtItem> {
  const body: MenuAuthrtSaveRequest = { authrtCds }
  const { data } = await api.put<MenuAuthrtItem>(
    `/menu-authorities/menus/${encodeURIComponent(menuId)}/authorities`,
    body,
  )
  return data
}
