import { api } from './client'

/*
 * 공통코드 실 API 클라이언트.
 * 백엔드 계약(`jeju.datahub.commoncode.web.CommonCodeController` 기준):
 *   GET /api/common-codes            → CommonCodeItem[]  (전체, `groupCd` 쿼리로 필터 가능)
 *   GET /api/common-codes/{groupCd}  → CommonCodeItem[]  (그룹 1개)
 *
 * 화면 select 의 라벨 정본은 **DB(TB_COM_CD)** 다 — 시안처럼 화면에 하드코딩하지 않는다.
 * 목록은 **서버가 정렬해서 내려준다**(sort_seq). 프론트에서 다시 정렬하지 않는다.
 *
 * ⚠️ 그룹 전체를 그대로 쓰지 않는 소비처가 있다. `addlAtrb1`(부가속성1)으로 부분집합을 고르거나
 * (`ANLS_TYPE_CD` 의 보고서 종류 = `addlAtrb1 IN ('RPTP','BOTH')`), 코드값 화이트리스트로
 * 거른다(`CRLTN_CYCL_CD` 의 `WEEK` 는 보고서 자동 생성이 지원하지 않는다). 거르는 기준은
 * 소비하는 화면이 가진다.
 */

/** GET /api/common-codes 배열 항목 (`parking.TB_COM_CD` + `TB_CD_GROUP`). */
export interface CommonCodeItem {
  /** 그룹코드. 예) 'ANLS_TYPE_CD'. */
  groupCd: string
  /** 그룹명. 예) '분석유형코드'. */
  groupNm: string
  /** 코드값. 예) 'CVLCPT'. */
  cdVl: string
  /** 코드명 — 화면 표시 라벨. 예) '민원현황'. */
  cdNm: string
  /** 정렬순서. 서버가 이 순서로 내려준다. */
  sortSeq: number
  /** 사용여부 (Y/N). 목록은 사용중(Y)만 내려온다. */
  useYn: string
  /** 부가속성1 — 그룹마다 의미가 다르다. `ANLS_TYPE_CD` 는 용도 구분(ANLS/RPTP/BOTH). 없으면 null. */
  addlAtrb1: string | null
}

/** 그룹별 공통코드 조회 (서버 정렬 순서 그대로 사용 — 프론트 재정렬 금지). */
export async function fetchCommonCodes(groupCd: string): Promise<CommonCodeItem[]> {
  const { data } = await api.get<CommonCodeItem[]>(`/common-codes/${groupCd}`)
  return data
}
