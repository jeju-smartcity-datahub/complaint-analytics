// 권한 가드 없음 — 로그인 도입 시 전 화면 일괄 적용
import { useCallback, useEffect, useState, type CSSProperties, type SelectHTMLAttributes } from 'react'
import axios from 'axios'
import Breadcrumb from '../../components/common/Breadcrumb'
import ConfirmDeleteModal from '../../components/common/ConfirmDeleteModal'
import TopbarActions from '../../components/common/TopbarActions'
import Icon from '../../components/Icon'
import { fetchCommonCodes, type CommonCodeItem } from '../../api/commonCodes'
import { fetchDepts, type DeptItem } from '../../api/depts'
import { fetchRegions, type RegionItem } from '../../api/regions'
import {
  createReportSetting,
  deleteReportSetting,
  fetchReportSettings,
  updateReportSetting,
  type ReportSettingItem,
  type ReportSettingRequest,
} from '../../api/reports'
import SrLabel from '../../components/common/SrLabel'

/*
 * 보고서 관리 `/reports/management` — 자동 생성 설정 등록/수정/삭제.
 *
 * 레이아웃·DOM 은 시안을 그대로 옮겼고,
 * select 옵션은 시안의 하드코딩 배열 대신 **공통코드 API**(`ANLS_TYPE_CD`·`CRLTN_CYCL_CD`)와
 * **부서 API**(`/api/depts` — 배포 부서, 공통코드가 아니다) ·
 * **행정구역 API**(`/api/regions`)에서 받는다 — 라벨 정본은 DB 한 곳이다.
 *
 * 시안과 의도적으로 다른 부분
 *   - **지역(시/군구 + 읍면동) 필드·컬럼 추가**. 목록의 `지역` 칸은 서버가 조립한 `regionNm` 을
 *     그대로 찍는다 — 프론트가 `sggNm`/`emdNm` 을 다시 조합하지 않는다
 *   - **관리자 가드를 옮기지 않는다**(파일 첫 줄 주석)
 *   - `보고서 종류` 는 `addlAtrb1 IN ('RPTP','BOTH')` 인 코드만 노출 — 분석 화면 전용 유형을 고르면 400 이다
 *   - `배포 주기` 는 `WEEK` 를 뺀 4종만 노출하고 기본값은 `MONTH`. `CRLTN_CYCL_CD` 에는 `WEEK` 가
 *     `sort_seq=1` 로 들어 있어 무필터면 첫 옵션이 주간이 되고, 그대로 저장하면 서버가 400 을 낸다
 *     (월+일로는 "몇 주차"를 특정할 수 없어 서버가 막는 것이 의도다)
 *   - `배포 일자` 의 월 select 를 **분기·반기·연간에 모두** 노출한다(시안은 연간만). 서버가 세 주기
 *     전부에서 `crltnMm` 을 요구하므로 서버 규칙이 우선이다
 *   - 조회 중 / 조회 실패 / 빈 목록 · 등록·수정·삭제 실패 문구 (시안은 목데이터라 실패 경로가 없다)
 *
 * ⚠️ `보고서 자동 생성` 버튼은 **설정 등록**이다 — 누른다고 보고서(TB_RPTP 행)가 생기지 않는다.
 * 주기가 도래했을 때 실제 보고서를 만드는 것은 배치의 몫이라 신규 등록분의 `최근 생성 일시` 는
 * `미생성` 으로 뜬다(시안은 목데이터라 즉시 날짜가 찍힌다).
 */

/** 보고서 종류로 쓸 수 있는 `ANLS_TYPE_CD` 부분집합 — `addlAtrb1`(용도 구분) 화이트리스트. */
const RPTP_TYPE_ADDL_ATRB1 = ['RPTP', 'BOTH']

/** 자동 생성이 지원하는 배포 주기 — `WEEK` 는 제외한다(서버가 400). */
const CYCLE_CODES = ['MONTH', 'QUARTER', 'HALF', 'YEAR']

/** 기본 배포 주기. 필터 후 첫 옵션(분기)이 기본이 되지 않도록 명시한다. */
const DEFAULT_CYCLE = 'MONTH'

/** 기준월(`crltnMm`)이 **필수**인 주기 — 서버 규칙과 같은 목록이다. `MONTH` 는 매월이라 기준월이 없다. */
const MONTH_REQUIRED_CYCLES = ['QUARTER', 'HALF', 'YEAR']

/** 배포 기준월 후보. */
const MONTH_OPTIONS = Array.from({ length: 12 }, (_, i) => i + 1)

/** 배포 기준일 후보. 말일 편차(30/31·2월)를 피해 서버와 같은 **1~28** 로 제한한다. */
const DAY_OPTIONS = Array.from({ length: 28 }, (_, i) => i + 1)

/** 목록 표의 열 수. 지역 컬럼이 늘어 7 → 8 이다 — 상태 행 `colSpan` 이 이 값을 쓴다. */
const COL_COUNT = 8

/** 목록 표 헤더. */
const HEADERS = [
  '생성자',
  '배포 부서',
  '지역',
  '배포 주기',
  '배포 일자',
  '최근 생성 일시',
  '수정',
  '삭제',
] as const

/** 가운데 정렬할 헤더(시안 — 버튼 열). */
const CENTER_COLS: readonly string[] = ['수정', '삭제']

/**
 * 오류 문구 스타일. 시안엔 실패 경로가 없어 전용 클래스도 없다.
 * reports.css 는 공용 스타일이라 이번에 늘리지 않고 최소 스타일만 인라인으로 준다.
 */
const ERROR_TEXT: CSSProperties = {
  color: 'var(--status-negative)',
  fontSize: 'var(--label1-size)',
}

/** 한 칸(폼 field · 표 셀) 안에 select 를 두 개 이상 나란히 둘 때의 간격. */
const INLINE_FIELDS: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 'var(--space-8)',
}

/** `보고서 자동 생성` 버튼의 ▶ 아이콘. `.btn-gen svg` 가 색을 흰색으로 맞춘다. */
const PlayIcon = (
  <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
    <path d="M8 5.5v13l11-6.5z" />
  </svg>
)

/** 폼(등록·인라인 수정)이 다루는 값. 서버 요청으로는 `toRequest` 가 변환한다. */
interface SettingForm {
  rptpTypeCd: string
  crltnDeptCd: string
  crltnCyclCd: string
  /** 기준월. 주기가 `MONTH` 면 서버로 보내지 않는다. */
  crltnMm: number
  crltnDd: number
  /** 시군구명. `''` = 전체 지역. */
  sggNm: string
  /** 읍면동코드. `''` = 시군구 전체. */
  emdCd: string
}

const EMPTY_FORM: SettingForm = {
  rptpTypeCd: '',
  crltnDeptCd: '',
  crltnCyclCd: DEFAULT_CYCLE,
  crltnMm: 1,
  crltnDd: 1,
  sggNm: '',
  emdCd: '',
}

/** 이 주기가 기준월을 요구하나. 월 select 노출 조건과 전송 조건이 **같은 함수**를 쓴다. */
function needsMonth(crltnCyclCd: string): boolean {
  return MONTH_REQUIRED_CYCLES.includes(crltnCyclCd)
}

/**
 * 폼 → 요청 바디.
 *
 * 지역 전송 규칙: 읍면동을 고르면 **`emdCd` 만** 보낸다(서버가 `sggNm` 을 다시 채운다).
 * 시군구만 고르면 `sggNm`, 둘 다 전체면 **아무것도 보내지 않는다**(= 전체 지역).
 */
function toRequest(form: SettingForm): ReportSettingRequest {
  const request: ReportSettingRequest = {
    rptpTypeCd: form.rptpTypeCd,
    crltnDeptCd: form.crltnDeptCd,
    crltnCyclCd: form.crltnCyclCd,
    crltnDd: form.crltnDd,
  }
  if (needsMonth(form.crltnCyclCd)) request.crltnMm = form.crltnMm
  if (form.emdCd) request.emdCd = form.emdCd
  else if (form.sggNm) request.sggNm = form.sggNm
  return request
}

/** 목록 행 → 편집 폼. `취소` 는 이 변환을 버리는 것이라 원본 행은 그대로 남는다. */
function toForm(item: ReportSettingItem): SettingForm {
  return {
    rptpTypeCd: item.rptpTypeCd,
    crltnDeptCd: item.crltnDeptCd,
    crltnCyclCd: item.crltnCyclCd,
    crltnMm: item.crltnMm ?? 1,
    crltnDd: item.crltnDd,
    sggNm: item.sggNm ?? '',
    emdCd: item.emdCd ?? '',
  }
}

/** `1월 15일` / `매월 15일`. 기준월이 없는 주기(MONTH)는 `매월` 로 읽힌다. */
function formatSchedule(crltnMm: number | null, crltnDd: number): string {
  return crltnMm ? `${crltnMm}월 ${crltnDd}일` : `매월 ${crltnDd}일`
}

/**
 * `2026-07-01T09:00:00` → `2026-07-01 09:00`.
 * 서버가 내리는 LocalDateTime 문자열을 그대로 자른다 — `Date` 로 파싱하면 타임존에 따라 밀린다.
 */
function formatDateTime(isoDateTime: string): string {
  return `${isoDateTime.slice(0, 10)} ${isoDateTime.slice(11, 16)}`
}

/**
 * 실패 문구. 서버는 전역 예외 핸들러가 없어 400 사유가 본문에 실려 오지 않으므로
 * (`server.error.include-message` 기본값 = never) 상태코드로만 갈라 안내한다.
 */
function errorText(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error) && error.response?.status === 400) {
    return '입력값을 확인해 주세요. (지원하지 않는 코드이거나 필수값이 빠졌습니다)'
  }
  return fallback
}

/** 시안의 `.ds-select` — 네이티브 select 위에 chevron 아이콘을 얹는다. */
function DsSelect({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className={`ds-select${className ? ` ${className}` : ''}`}>
      <SrLabel text={rest['aria-label']}>
        <select {...rest}>{children}</select>
      </SrLabel>
      <span className="ds-select__ic">
        <Icon name="chevron-down" size={16} />
      </span>
    </div>
  )
}

interface RegionFieldsProps {
  regions: RegionItem[]
  sggNm: string
  emdCd: string
  onChange: (next: { sggNm: string; emdCd: string }) => void
}

/**
 * 지역 select 2개(시/군구 + 읍면동). 등록 폼과 인라인 수정 행이 **같은 규칙**을 쓴다.
 *
 * - 시/군구 기본 `전체` · 읍면동 기본 `읍면동 전체`
 * - 시/군구가 `전체` 면 읍면동은 **비활성**
 * - 시/군구를 바꾸면 읍면동을 **`전체` 로 리셋**한다 — 안 하면 제주시 읍면동이 서귀포시에 남아
 *   잘못된 조합이 전송된다
 */
function RegionFields({ regions, sggNm, emdCd, onChange }: RegionFieldsProps) {
  const emds = regions.find((r) => r.sggNm === sggNm)?.emds ?? []

  return (
    <div style={INLINE_FIELDS}>
      <DsSelect
        value={sggNm}
        aria-label="시/군구"
        onChange={(e) => onChange({ sggNm: e.target.value, emdCd: '' })}
      >
        <option value="">제주도 전체</option>
        {regions.map((r) => (
          <option key={r.sggNm} value={r.sggNm}>
            {r.sggNm}
          </option>
        ))}
      </DsSelect>

      <DsSelect
        value={emdCd}
        aria-label="읍면동"
        disabled={!sggNm}
        onChange={(e) => onChange({ sggNm, emdCd: e.target.value })}
      >
        <option value="">읍면동 전체</option>
        {emds.map((emd) => (
          <option key={emd.emdCd} value={emd.emdCd}>
            {emd.emdNm}
          </option>
        ))}
      </DsSelect>
    </div>
  )
}

export default function ReportManagementPage() {
  // 공통코드·부서·행정구역 (select 옵션 원본)
  const [rptpTypes, setRptpTypes] = useState<CommonCodeItem[]>([])
  const [depts, setDepts] = useState<DeptItem[]>([])
  const [cycles, setCycles] = useState<CommonCodeItem[]>([])
  const [regions, setRegions] = useState<RegionItem[]>([])

  const [settings, setSettings] = useState<ReportSettingItem[]>([])

  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  /** `다시 시도` 로 초기 조회를 재실행하기 위한 트리거. */
  const [reloadKey, setReloadKey] = useState(0)

  // 등록 폼
  const [form, setForm] = useState<SettingForm>(EMPTY_FORM)
  const [creating, setCreating] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  // 인라인 수정 / 삭제
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editForm, setEditForm] = useState<SettingForm | null>(null)
  const [rowBusy, setRowBusy] = useState(false)
  const [listError, setListError] = useState<string | null>(null)
  /** 삭제 확인 대상 설정 ID. null 이면 확인 팝업을 닫는다. */
  const [deleteTargetId, setDeleteTargetId] = useState<number | null>(null)

  // 마운트(및 재시도) 시 select 옵션 3종 + 지역 + 설정 목록을 병렬 조회한다.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(false)

    Promise.all([
      fetchCommonCodes('ANLS_TYPE_CD'),
      fetchDepts(),
      fetchCommonCodes('CRLTN_CYCL_CD'),
      fetchRegions(),
      fetchReportSettings(),
    ])
      .then(([typeCodes, deptCodes, cyclCodes, regionList, settingList]) => {
        if (cancelled) return

        // 보고서 종류: 보고서용 용도(addlAtrb1)만. 배포 주기: WEEK 제외.
        const types = typeCodes.filter((c) => RPTP_TYPE_ADDL_ATRB1.includes(c.addlAtrb1 ?? ''))
        const cycleList = cyclCodes.filter((c) => CYCLE_CODES.includes(c.cdVl))

        setRptpTypes(types)
        setDepts(deptCodes)
        setCycles(cycleList)
        setRegions(regionList)
        setSettings(settingList)

        // 기본 선택값 = 서버가 준 첫 옵션. 주기만 MONTH 를 명시적으로 고른다.
        setForm({
          ...EMPTY_FORM,
          rptpTypeCd: types[0]?.cdVl ?? '',
          crltnDeptCd: deptCodes[0]?.deptCd ?? '',
          crltnCyclCd: cycleList.some((c) => c.cdVl === DEFAULT_CYCLE)
            ? DEFAULT_CYCLE
            : (cycleList[0]?.cdVl ?? ''),
        })
      })
      .catch(() => {
        if (cancelled) return
        setRptpTypes([])
        setDepts([])
        setCycles([])
        setRegions([])
        setSettings([])
        setLoadError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [reloadKey])

  /** 목록만 서버 기준으로 다시 맞춘다(등록·삭제 후). 서버 정렬 순서를 그대로 쓴다. */
  const refreshSettings = async () => {
    try {
      setSettings(await fetchReportSettings())
    } catch {
      setListError('목록을 새로 고치지 못했습니다. 잠시 후 다시 시도해 주세요.')
    }
  }

  /**
   * ⚠️ 설정 **등록**이다 — 보고서를 만들지 않는다. 등록 직후 `최근 생성 일시` 는 `미생성` 이다.
   */
  const handleCreate = async () => {
    if (creating || !form.rptpTypeCd || !form.crltnDeptCd || !form.crltnCyclCd) return
    setCreating(true)
    setFormError(null)
    setListError(null)
    try {
      await createReportSetting(toRequest(form))
      await refreshSettings()
    } catch (e) {
      setFormError(errorText(e, '설정 등록에 실패했습니다. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setCreating(false)
    }
  }

  const startEdit = (item: ReportSettingItem) => {
    setEditingId(item.stngId)
    setEditForm(toForm(item))
    setListError(null)
  }

  /** 취소 = 편집 폼을 버린다. 원본 행(`settings`)은 손대지 않았으므로 그대로 원복된다. */
  const cancelEdit = () => {
    setEditingId(null)
    setEditForm(null)
    setListError(null)
  }

  const handleUpdate = async () => {
    if (editingId === null || !editForm || rowBusy) return
    setRowBusy(true)
    setListError(null)
    try {
      const updated = await updateReportSetting(editingId, toRequest(editForm))
      // 서버 응답을 단일 진실로 삼아 그 행만 갈아끼운다(정렬 순서 유지).
      setSettings((prev) => prev.map((s) => (s.stngId === updated.stngId ? updated : s)))
      setEditingId(null)
      setEditForm(null)
    } catch (e) {
      if (axios.isAxiosError(e) && e.response?.status === 404) {
        // 이미 지워진 설정이다. 편집을 닫고 목록을 서버 상태에 맞춘다.
        setEditingId(null)
        setEditForm(null)
        setListError('이미 삭제된 설정입니다. 목록을 새로 고쳤습니다.')
        await refreshSettings()
      } else {
        setListError(errorText(e, '설정 수정에 실패했습니다. 잠시 후 다시 시도해 주세요.'))
      }
    } finally {
      setRowBusy(false)
    }
  }

  /**
   * 삭제. **소프트 삭제라 정상 경로에서는 항상 성공한다** — 보고서가 딸린 설정도 지워진다.
   * 확인 팝업(`ConfirmDeleteModal`)을 거친 뒤에만 부른다(시안에 팝업이 생겼다).
   * 404(이미 지워짐)면 목록만 새로 고쳐 화면을 맞춘다.
   */
  const closeDelete = useCallback(() => setDeleteTargetId(null), [])

  const handleDelete = async (stngId: number) => {
    if (rowBusy) return
    setRowBusy(true)
    setListError(null)
    try {
      await deleteReportSetting(stngId)
    } catch (e) {
      if (!(axios.isAxiosError(e) && e.response?.status === 404)) {
        setListError(errorText(e, '설정 삭제에 실패했습니다. 잠시 후 다시 시도해 주세요.'))
        setRowBusy(false)
        return
      }
    }
    if (editingId === stngId) {
      setEditingId(null)
      setEditForm(null)
    }
    await refreshSettings()
    setRowBusy(false)
  }

  /** 등록 폼의 지역 변경 — 시/군구를 바꾸면 읍면동이 함께 리셋된다(`RegionFields` 규칙). */
  const changeFormRegion = (next: { sggNm: string; emdCd: string }) => {
    setForm((prev) => ({ ...prev, ...next }))
  }

  const changeEditRegion = (next: { sggNm: string; emdCd: string }) => {
    setEditForm((prev) => (prev ? { ...prev, ...next } : prev))
  }

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="보고서·알림" page="보고서 관리" />
          <h1 className="page-title">보고서 관리</h1>
          <p className="page-sub">보고서 자동 생성 설정 관리</p>
        </div>
        <TopbarActions />
      </header>

      <div className="content content--reports">
        {/* ── 보고서 자동 생성 설정 (등록 폼) ── */}
        <div className="card rpt-cfg">
          <div className="rpt-cfg__head">
            <h2 className="card-head__title">보고서 자동 생성 설정</h2>
          </div>

          <div className="rpt-cfg__body">
            <div className="rpt-cfg__fields">
              <div className="field">
                <span className="field__label">보고서 종류</span>
                <DsSelect
                  value={form.rptpTypeCd}
                  aria-label="보고서 종류"
                  onChange={(e) => setForm((prev) => ({ ...prev, rptpTypeCd: e.target.value }))}
                >
                  {rptpTypes.map((c) => (
                    <option key={c.cdVl} value={c.cdVl}>
                      {c.cdNm}
                    </option>
                  ))}
                </DsSelect>
              </div>

              <div className="field">
                <span className="field__label">배포 부서</span>
                <DsSelect
                  value={form.crltnDeptCd}
                  aria-label="배포 부서"
                  onChange={(e) => setForm((prev) => ({ ...prev, crltnDeptCd: e.target.value }))}
                >
                  {depts.map((d) => (
                    <option key={d.deptCd} value={d.deptCd}>
                      {d.deptNm}
                    </option>
                  ))}
                </DsSelect>
              </div>

              <div className="field">
                <span className="field__label">지역</span>
                <RegionFields
                  regions={regions}
                  sggNm={form.sggNm}
                  emdCd={form.emdCd}
                  onChange={changeFormRegion}
                />
              </div>

              <div className="field">
                <span className="field__label">배포 주기</span>
                <DsSelect
                  value={form.crltnCyclCd}
                  aria-label="배포 주기"
                  onChange={(e) => setForm((prev) => ({ ...prev, crltnCyclCd: e.target.value }))}
                >
                  {cycles.map((c) => (
                    <option key={c.cdVl} value={c.cdVl}>
                      {c.cdNm}
                    </option>
                  ))}
                </DsSelect>
              </div>

              <div className="field">
                <span className="field__label">배포 일자</span>
                <div style={INLINE_FIELDS}>
                  {needsMonth(form.crltnCyclCd) && (
                    <DsSelect
                      value={form.crltnMm}
                      aria-label="배포 기준월"
                      onChange={(e) =>
                        setForm((prev) => ({ ...prev, crltnMm: Number(e.target.value) }))
                      }
                    >
                      {MONTH_OPTIONS.map((m) => (
                        <option key={m} value={m}>{`${m}월`}</option>
                      ))}
                    </DsSelect>
                  )}
                  <DsSelect
                    value={form.crltnDd}
                    aria-label="배포 기준일"
                    onChange={(e) =>
                      setForm((prev) => ({ ...prev, crltnDd: Number(e.target.value) }))
                    }
                  >
                    {DAY_OPTIONS.map((d) => (
                      <option key={d} value={d}>{`${d}일`}</option>
                    ))}
                  </DsSelect>
                </div>
              </div>
            </div>

            {/* `.rpt-cfg__foot` 는 이미 flex(우측 정렬)다 — 오류 문구와의 간격만 얹는다. */}
            <div
              className="rpt-cfg__foot"
              style={{ alignItems: 'center', gap: 'var(--space-12)' }}
            >
              {formError && (
                <span style={ERROR_TEXT} role="alert">
                  {formError}
                </span>
              )}
              <button
                type="button"
                className="btn-gen"
                disabled={loading || loadError || creating || !form.rptpTypeCd}
                onClick={() => {
                  void handleCreate()
                }}
              >
                {PlayIcon} 보고서 자동 생성
              </button>
            </div>
          </div>
        </div>

        {/* ── 자동 생성 설정 목록 ── */}
        <div className="card rpt-list">
          <div className="rpt-card__head">
            <h2 className="card-head__title">자동 생성 설정 목록</h2>
          </div>

          {listError && (
            <p style={{ ...ERROR_TEXT, padding: '0 var(--space-16)' }} role="alert">
              {listError}
            </p>
          )}

          <div className="rt-wrap">
            <table className="mt">
              <caption className="sr-only">보고서 자동 생성 설정 목록</caption>
              <thead>
                <tr>
                  {HEADERS.map((h) => (
                    <th scope="col" key={h} className={CENTER_COLS.includes(h) ? 'col-center' : undefined}>
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {loading && (
                  <tr>
                    <td className="col-center" colSpan={COL_COUNT}>
                      불러오는 중...
                    </td>
                  </tr>
                )}

                {!loading && loadError && (
                  <tr>
                    <td className="col-center" colSpan={COL_COUNT}>
                      자동 생성 설정을 불러오지 못했습니다.{' '}
                      <button
                        type="button"
                        className="btn-edit"
                        onClick={() => setReloadKey((k) => k + 1)}
                      >
                        다시 시도
                      </button>
                    </td>
                  </tr>
                )}

                {!loading && !loadError && settings.length === 0 && (
                  <tr>
                    <td className="col-center" colSpan={COL_COUNT}>
                      등록된 자동 생성 설정이 없습니다.
                    </td>
                  </tr>
                )}

                {!loading &&
                  !loadError &&
                  settings.map((s) => {
                    /** 이 행이 편집 중이면 편집 폼, 아니면 null — 셀마다 이 값으로 갈린다. */
                    const draft = editingId === s.stngId ? editForm : null

                    return (
                      <tr key={s.stngId}>
                        <td>{s.creatrNm}</td>

                        <td>
                          {draft ? (
                            <DsSelect
                              value={draft.crltnDeptCd}
                              aria-label="배포 부서"
                              onChange={(e) =>
                                setEditForm((prev) =>
                                  prev ? { ...prev, crltnDeptCd: e.target.value } : prev,
                                )
                              }
                            >
                              {depts.map((d) => (
                                <option key={d.deptCd} value={d.deptCd}>
                                  {d.deptNm}
                                </option>
                              ))}
                            </DsSelect>
                          ) : (
                            <span className="mt-dept">{s.crltnDeptNm}</span>
                          )}
                        </td>

                        <td>
                          {draft ? (
                            <RegionFields
                              regions={regions}
                              sggNm={draft.sggNm}
                              emdCd={draft.emdCd}
                              onChange={changeEditRegion}
                            />
                          ) : (
                            /* 서버가 조립한 표시명을 그대로 찍는다 — 프론트 조합 금지. */
                            s.regionNm
                          )}
                        </td>

                        <td>
                          {draft ? (
                            <DsSelect
                              value={draft.crltnCyclCd}
                              aria-label="배포 주기"
                              onChange={(e) =>
                                setEditForm((prev) =>
                                  prev ? { ...prev, crltnCyclCd: e.target.value } : prev,
                                )
                              }
                            >
                              {cycles.map((c) => (
                                <option key={c.cdVl} value={c.cdVl}>
                                  {c.cdNm}
                                </option>
                              ))}
                            </DsSelect>
                          ) : (
                            s.crltnCyclNm
                          )}
                        </td>

                        <td>
                          {draft ? (
                            <div style={INLINE_FIELDS}>
                              {needsMonth(draft.crltnCyclCd) && (
                                <DsSelect
                                  value={draft.crltnMm}
                                  aria-label="배포 기준월"
                                  onChange={(e) =>
                                    setEditForm((prev) =>
                                      prev ? { ...prev, crltnMm: Number(e.target.value) } : prev,
                                    )
                                  }
                                >
                                  {MONTH_OPTIONS.map((m) => (
                                    <option key={m} value={m}>{`${m}월`}</option>
                                  ))}
                                </DsSelect>
                              )}
                              <DsSelect
                                value={draft.crltnDd}
                                aria-label="배포 기준일"
                                onChange={(e) =>
                                  setEditForm((prev) =>
                                    prev ? { ...prev, crltnDd: Number(e.target.value) } : prev,
                                  )
                                }
                              >
                                {DAY_OPTIONS.map((d) => (
                                  <option key={d} value={d}>{`${d}일`}</option>
                                ))}
                              </DsSelect>
                            </div>
                          ) : (
                            formatSchedule(s.crltnMm, s.crltnDd)
                          )}
                        </td>

                        {/* 배치가 붙기 전 신규 등록분은 전부 `미생성` 이다 — 빈칸으로 두지 않는다. */}
                        <td className="rt-date">
                          {s.lastCrtDt ? formatDateTime(s.lastCrtDt) : '미생성'}
                        </td>

                        <td className="col-center">
                          {draft ? (
                            <button
                              type="button"
                              className="mt-act mt-act--save"
                              disabled={rowBusy}
                              onClick={() => {
                                void handleUpdate()
                              }}
                            >
                              저장
                            </button>
                          ) : (
                            <button type="button" className="mt-act" onClick={() => startEdit(s)}>
                              수정
                            </button>
                          )}
                        </td>

                        <td className="col-center">
                          {draft ? (
                            <button type="button" className="mt-act" onClick={cancelEdit}>
                              취소
                            </button>
                          ) : (
                            <button
                              type="button"
                              className="rt-del"
                              aria-label="삭제"
                              title="삭제"
                              disabled={rowBusy}
                              onClick={() => setDeleteTargetId(s.stngId)}
                            >
                              <Icon name="close" size={16} />
                            </button>
                          )}
                        </td>
                      </tr>
                    )
                  })}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {deleteTargetId !== null && (
        <ConfirmDeleteModal
          message="자동 생성 설정을 삭제하시겠습니까?"
          onCancel={closeDelete}
          onConfirm={() => {
            const id = deleteTargetId
            setDeleteTargetId(null)
            void handleDelete(id)
          }}
        />
      )}
    </>
  )
}
