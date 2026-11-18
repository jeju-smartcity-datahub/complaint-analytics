import { api } from './client'

/*
 * 부서 실 API 클라이언트 (TSK-4183 Step 4).
 * 백엔드 계약(`jeju.datahub.dept.web.DeptController` 기준):
 *   GET /api/depts → DeptItem[]  (사용중 use_yn='Y' 만)
 *
 * 부서 select 를 쓰는 화면(보고서 설정의 `배포 부서` · 알림 수신자 등록의 `소속`)이 **모두 이 하나**를
 * 쓴다 — 종전 부서 공통코드 그룹을 대체한다(D4·D8: 부서 목록은 공통코드로 관리하지 않는다).
 *
 * 🔴 `TB_DEPT` 는 마스터가 아니라 **IdP `groups` claim 의 투영**이라(D5) 빈 표로 시작하고 로그인이
 * 채운다 — 아직 아무도 로그인하지 않았다면 **빈 배열이 정상**이다(버그로 오진하지 않는다).
 *
 * 목록은 **서버가 정렬해서 내려준다**(`sort_seq NULLS LAST, dept_cd`).
 * 프론트에서 다시 정렬하지 말고 받은 순서를 그대로 렌더링한다.
 */

/** GET /api/depts 배열 항목 (`parking.TB_DEPT`). */
export interface DeptItem {
  /** 부서코드 — IdP 그룹 슬러그(최대 30자). 예) 'innopam-group2'. */
  deptCd: string
  /** 부서명 — 화면 표시 라벨. 자동 등재분의 초기값은 슬러그와 같다(표시명은 사후 보정). */
  deptNm: string
  /** 정렬순서. 서버가 이 순서로 내려준다. 🔴 자동 등재분은 `null` 이다(목록이 NULLS LAST). */
  sortSeq: number | null
  /** 사용여부 (Y/N). 목록은 사용중(Y)만 내려온다. */
  useYn: string
}

/** 부서 목록 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지). */
export async function fetchDepts(): Promise<DeptItem[]> {
  const { data } = await api.get<DeptItem[]>('/depts')
  return data
}
