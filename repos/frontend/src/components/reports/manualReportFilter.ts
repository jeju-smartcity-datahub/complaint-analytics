/*
 * 직접 생성 탭 필터의 입력 원본 타입·상수 (TSK-4266 Step 5).
 * 툴바(`ManualReportToolbar`)와 화면(`ReportsPage`)이 함께 쓴다 — 컴포넌트 파일에 두면
 * Fast Refresh 규칙(react-refresh/only-export-components)에 걸려 따로 뺐다.
 */

/** 툴바 입력 원본. `anlsTypeCd` 의 `'all'` 은 화면 전용 값이다(서버로는 보내지 않는다). */
export interface ManualReportFilter {
  keyword: string
  anlsTypeCd: string
  fromYmd: string
  toYmd: string
}

/** 출처 셀렉트의 `전체` 값. */
export const SRC_ALL = 'all'

export const EMPTY_MANUAL_FILTER: ManualReportFilter = {
  keyword: '',
  anlsTypeCd: SRC_ALL,
  fromYmd: '',
  toYmd: '',
}
