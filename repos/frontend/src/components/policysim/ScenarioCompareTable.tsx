import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import Icon from '../Icon'
import type { PlcyTypeCd, PolicySimReportFileFmt } from '../../api/policySim'
import {
  PolicySimSaveError,
  exportPolicySimReport,
  type PolicySimExportCriteria,
} from '../../lib/analysis/policySimExport'
import {
  CST_LVL_META,
  PLCY_TYPE_NM,
  fmtPct,
  isNoCngstChange,
  isRoundedAway,
  scenarioFullName,
  COEFF_AJMT_MARK,
  coeffAjmtNote,
  type Scenario,
} from './policySimView'
import SrLabel from '../common/SrLabel'

/*
 * 정책 효과 시뮬레이션 — `시나리오 비교` 카드.
 *
 * 시안 구조(체크박스 · 시나리오 · 민원 감소 · 혼잡 개선 · 비용 수준 · 추천 · 삭제) 7열 그대로다.
 * 분석 범위(`anlsRngCn`)는 열이 아니라 시나리오 이름 안에 들어간다(`scenarioFullName` — 보고서와 같은 함수).
 * 셀 스타일은 시안 `l9`(th)·`u9`(td) 그대로다.
 *
 * - 내보내기 — 시안의 `sim-export` 메뉴(PDF · Word, 바깥 클릭·Esc 로 닫힘).
 *   🔴 **내보내기 = 보고서 저장이다.** 형식을 고르면 체크한 시나리오로 보고서 1건을 저장하고 **그 저장 응답으로**
 *   파일을 만든다(순서·실패 갈래는 `lib/analysis/policySimExport`). 저장이 실패하면 파일도 만들지 않는다.
 *   대상은 페이지가 **목록에 보이는 순서**로 조립해 `exportCriteria` 로 넘긴다(체크 순서가 아니다).
 *   체크 1~15건일 때만 열린다.
 * - 추천 배지는 부모가 `pickRecommended()` 로 목록이 바뀔 때마다 다시 판정해 `recommendedId` 로 넘긴다.
 * - 행을 누르면 그 시나리오가 효과 예측 카드에 그려진다(시안 동작).
 *
 * 🔴 **별도 카드를 만들지 않는다.** 이 목록은 **내 저장분** 최신 100건이다 — [시뮬레이션 실행]이
 * 곧 저장이라 모든 행이 저장분이다. 페이지 넘김 없이 표 영역만 스크롤한다.
 * 100건을 넘으면 마지막 행 아래에 `최근 100건만 표시합니다.` 한 줄을 둔다(`truncated`).
 * 저장 목록 조회 실패는 목록 안의 한 줄로만 알린다 — 화면의 나머지는 그대로 쓸 수 있다.
 *
 * 정책 유형 필터(시안 `.sc-filter`) — `전체 (N)` + 목록에 있는 유형만 `{유형} (n)` 칩.
 * 거르는 것은 **표에 보이는 행**뿐이다 — 전체 선택은 보이는 행에만 걸리고, 체크·내보내기·추천은
 * 필터와 무관하게 불러온 행 전부가 대상이다(시안도 내보내기 건수는 필터 밖 체크까지 센다).
 *
 * 분석 계수 표식(시안 반영) — 저장 당시 계수를 조정한 시나리오(`result.vrblAjmt.ajmtYn === 'Y'`)는
 * 이름 뒤에 `(계수조정)`(파란 굵은 글씨 · 툴팁 `분석 계수 조정: {내역}`)을 붙인다(구 저장분은 `vrblAjmt` 가 null 이라 붙지 않는다).
 * 🔴 `scenarioFullName` 에 붙이지 않는다 — 막대 이름·효과 예측 캡션도 그 함수를 쓴다. 보고서는 1절 표 셀과
 * 2절 `분석 계수 조정 내역` 에서 따로 붙인다(`coeffAjmtNote` 공용).
 */

interface ScenarioCompareTableProps {
  /** 보이는 행 — 저장분 최신 100건(서버 최신순 그대로). */
  scenarios: Scenario[]
  /** 효과 예측 카드에 그려지고 있는 행의 키. */
  activeKey: string | null
  /** 추천 배지를 붙일 행(없으면 null — 배지 없음). */
  recommendedKey: string | null
  checkedKeys: string[]
  onToggleCheck: (key: string) => void
  /** 전체 선택 — 지금 표에 보이는(필터를 통과한) 행의 키를 넘긴다. */
  onToggleAll: (keys: string[]) => void
  onSelect: (key: string) => void
  onDelete: (key: string) => void
  /** 저장 목록을 처음 불러오는 중(아직 한 번도 못 받았을 때만 true). */
  savedLoading: boolean
  /** 저장 목록 조회 실패 문구. null 이면 정상. */
  savedError: string | null
  onRetrySaved: () => void
  /** 저장분이 불러온 건수보다 많다(서버 `totalElements > content.length`) — 목록 끝에 안내 한 줄. */
  truncated: boolean
  /**
   * 내보내기(= 보고서 저장) 대상 — 보이는 행 중 체크한 것을 **목록 순서**로 조립한 요청.
   * `scenarios` 건수로 버튼을 잠근다(0건 · 16건 이상).
   */
  exportCriteria: PolicySimExportCriteria
  /** 저장이 404(선택한 저장 결과가 없거나 남의 것)로 끝났을 때 — 저장 목록을 다시 받는다. */
  onExportNotFound: () => void
}

/** 한 보고서에 담을 수 있는 시나리오 수 상한(서버도 16건 이상은 400). */
const EXPORT_MAX = 15

/**
 * 내보내기 결과 문구 4종 + 404(`InvestPrrtList` 와 같은 모양).
 * - `saved`        저장 + 파일 생성 완료
 * - `saveFailed`   저장 실패(네트워크·5xx 등) — 보고서도 파일도 없다
 * - `saveRejected` 저장 실패(400 — 요청 검증 실패) — 보고서도 파일도 없다
 * - `notFound`     저장 실패(404 — 선택한 저장 결과가 없거나 남의 것) — 저장 목록을 다시 받는다
 * - `fileFailed`   저장은 됐고 파일 생성만 실패 — 🔴 다시 누르면 보고서가 한 건 더 생긴다
 */
type ExportNotice = 'saved' | 'saveFailed' | 'saveRejected' | 'notFound' | 'fileFailed'

const NOTICE_TEXT: Record<ExportNotice, string> = {
  saved: '보고서 목록 > 직접 생성 보고서 에 저장되었습니다.',
  saveFailed: '보고서를 저장하지 못해 내보내지 않았습니다. 잠시 후 다시 시도해 주세요.',
  saveRejected:
    '보고서를 저장하지 못해 내보내지 않았습니다. 선택한 시나리오의 조건으로 지금 계산할 수 있는지 확인해 주세요.',
  notFound: '선택한 저장 결과를 찾을 수 없습니다. 목록을 새로 고친 뒤 다시 시도하세요.',
  fileFailed:
    '보고서는 저장되었지만 파일을 만들지 못했습니다. 보고서 목록에서 다시 내려받을 수 있습니다.',
}

/** 저장 성공 안내를 띄워 두는 시간(ms). 실패 안내는 다음 내보내기까지 남는다. */
const SAVED_NOTICE_MS = 5000

/*
 * 높이 — 🔴 상한은 `.content`(높이 고정 flex 열 · `.main` 100vh) 의 **남은 높이**다. `flex: 1`(기준 0%)이라
 * 행 수가 카드 높이를 밀어 올리지 않고, 100행이어도 표 래퍼(`SCROLL`)만 스크롤된다(시안 구조 그대로).
 * 하한 360px — 남은 높이가 이보다 작은 낮은 화면에서 카드가 머리만 남고 접히지 않게 한다(그때는 `.content` 가
 * 스크롤된다). 360 ≈ 머리 77 + 표 머리 44 + 행(이름 + 저장 시각) 약 70 × 3.
 */
const CARD: CSSProperties = {
  overflow: 'hidden',
  display: 'flex',
  flexDirection: 'column',
  flex: 1,
  minHeight: 360,
}
const HEAD: CSSProperties = {
  padding: '20px 24px',
  borderBottom: '1px solid var(--line-alternative)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 16,
}
const TITLE: CSSProperties = {
  margin: 0,
  fontSize: 17,
  fontWeight: 700,
  color: 'var(--text-strong)',
}
/** 제목 + 필터 칩 한 줄(좁으면 칩이 다음 줄로 내려간다). */
const TITLE_ROW: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  flexWrap: 'wrap',
}

/** 필터 칩 순서 — 정책 유형 표기 순서와 같다. 목록에 없는 유형은 칩을 만들지 않는다. */
const FILTER_ORDER: PlcyTypeCd[] = ['EXPAND', 'REPAIR', 'FEE']
type ScenarioFilter = 'all' | PlcyTypeCd
/** 내보내기 버튼 치수 — 시안 인라인 값(`InvestPrrtList` 의 것과 같다). */
const EXPORT_BTN: CSSProperties = {
  height: 36,
  padding: '0 var(--space-12)',
  fontSize: 'var(--label2-size)',
}
/** `.btn`·`.sim-export__item` 에 `:disabled` 규칙이 없어 비활성 표현만 인라인으로 둔다(같은 선례). */
const EXPORT_BTN_DISABLED: CSSProperties = { ...EXPORT_BTN, opacity: 0.5, cursor: 'not-allowed' }
const EXPORT_ITEM_DISABLED: CSSProperties = { opacity: 0.5, cursor: 'not-allowed' }
/** 내보내기 결과 문구 — 색만 다르다(`InvestPrrtList` 와 같은 토큰). */
const NOTICE_BASE: CSSProperties = { margin: 'var(--space-4) 0 0', fontSize: 'var(--label2-size)' }
const NOTICE_STYLE: Record<ExportNotice, CSSProperties> = {
  saved: { ...NOTICE_BASE, color: 'var(--status-positive)' },
  saveFailed: { ...NOTICE_BASE, color: 'var(--status-negative)' },
  saveRejected: { ...NOTICE_BASE, color: 'var(--status-negative)' },
  notFound: { ...NOTICE_BASE, color: 'var(--status-negative)' },
  // 보고서는 남았고 파일만 못 만든 경우 — 빨강이면 "저장도 실패했다" 로 읽힌다.
  fileFailed: { ...NOTICE_BASE, color: 'var(--status-cautionary)' },
}
/*
 * 🔴 `position: relative` 를 빼지 않는다 — 행 안 `.sr-only`(absolute)의 기준이 body 가 되면
 * 이 영역에 잘리지 않아, 행 수만큼 문서가 늘어나 창 전체 스크롤이 생긴다.
 */
const SCROLL: CSSProperties = { position: 'relative', flex: 1, minHeight: 0, overflowY: 'auto' }
const TABLE: CSSProperties = { width: '100%', borderCollapse: 'collapse' }
const CHECK: CSSProperties = {
  width: 16,
  height: 16,
  accentColor: 'var(--cool-neutral-17)',
  cursor: 'pointer',
  verticalAlign: 'middle',
}
const NAME: CSSProperties = { fontWeight: 600, color: 'var(--text-strong)' }
/** `(계수조정)` 표식 — 시안 값(파란 600 · 11px · 줄바꿈 없음). */
const COEFF_MARK: CSSProperties = {
  marginLeft: 6,
  fontSize: 11,
  fontWeight: 600,
  color: 'var(--primary)',
  whiteSpace: 'nowrap',
}
const SUB: CSSProperties = {
  fontSize: 'var(--caption1-size)',
  color: 'var(--text-alternative)',
  marginTop: 4,
}
const RDCT: CSSProperties = { fontWeight: 700, color: 'var(--primary)' }
const IMPV: CSSProperties = { fontWeight: 700, color: '#16a34a' }
/** 혼잡도 변화 없음 — 시안 `fontWeight 600 · --text-alternative`. */
const IMPV_NONE: CSSProperties = { fontWeight: 600, color: 'var(--text-alternative)' }
const RCMD_BADGE: CSSProperties = {
  background: 'var(--blue-95)',
  color: 'var(--primary)',
  fontWeight: 700,
}
const DEL_BTN: CSSProperties = {
  width: 32,
  height: 32,
  border: 'none',
  borderRadius: 'var(--radius-full)',
  background: 'none',
  color: 'var(--text-neutral)',
  cursor: 'pointer',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
}
const ERROR_TD: CSSProperties = {
  textAlign: 'center',
  color: 'var(--status-negative)',
  padding: 16,
  fontSize: 13,
}
const RETRY_BTN: CSSProperties = {
  marginLeft: 8,
  height: 28,
  padding: '0 var(--space-10)',
  fontSize: 'var(--caption1-size)',
}
const TRUNCATED_TD: CSSProperties = {
  textAlign: 'center',
  color: 'var(--text-alternative)',
  padding: 12,
  fontSize: 13,
}
const EMPTY_TD: CSSProperties = {
  textAlign: 'center',
  color: 'var(--text-alternative)',
  padding: 24,
  fontSize: 14,
}
/** 열 수 — 빈 행 `colSpan`. 열을 늘리면 같이 바꾼다. */
const COLS = 7

function Th({ center, children }: { center?: boolean; children: ReactNode }) {
  return (
    <th
      scope="col"
      style={{
        padding: '12px 16px',
        textAlign: center ? 'center' : 'left',
        fontSize: 13,
        fontWeight: 600,
        color: 'var(--text-neutral)',
        borderBottom: '1px solid var(--line-alternative)',
        whiteSpace: 'nowrap',
        background: 'var(--cool-neutral-99)',
        position: 'sticky',
        top: 0,
        zIndex: 1,
      }}
    >
      {children}
    </th>
  )
}

function Td({ center, children }: { center?: boolean; children: ReactNode }) {
  return (
    <td
      style={{
        padding: '14px 16px',
        borderBottom: '1px solid var(--line-alternative)',
        color: 'var(--text-neutral)',
        fontSize: 14,
        textAlign: center ? 'center' : 'left',
        verticalAlign: 'middle',
      }}
    >
      {children}
    </td>
  )
}

export default function ScenarioCompareTable({
  scenarios,
  activeKey,
  recommendedKey,
  checkedKeys,
  onToggleCheck,
  onToggleAll,
  onSelect,
  onDelete,
  savedLoading,
  savedError,
  onRetrySaved,
  truncated,
  exportCriteria,
  onExportNotFound,
}: ScenarioCompareTableProps) {
  // ── 정책 유형 필터 ──
  const [filter, setFilter] = useState<ScenarioFilter>('all')
  const groups = FILTER_ORDER.map((cd) => ({
    cd,
    count: scenarios.filter((s) => s.params.plcyType === cd).length,
  })).filter((g) => g.count > 0)
  // 고른 유형의 행이 다 지워지면 그 칩이 사라지므로 `전체` 로 본다(state 는 다음 클릭에 덮인다).
  const activeFilter: ScenarioFilter =
    filter !== 'all' && groups.some((g) => g.cd === filter) ? filter : 'all'
  const visible =
    activeFilter === 'all' ? scenarios : scenarios.filter((s) => s.params.plcyType === activeFilter)

  // 체크는 목록에서 사라진 행(삭제·100건 밖·필터)까지 남을 수 있으므로 "보이는 행이 전부 체크됐는가" 로 판정한다.
  const allChecked = visible.length > 0 && visible.every((s) => checkedKeys.includes(s.key))
  // 내보내기 대상 = 불러온 행 중 체크한 것(필터로 가려진 행 포함 — 페이지가 조립한 요청의 건수와 같다).
  const checkedCount = exportCriteria.scenarios.length

  // ── 내보내기 ──
  const [exportOpen, setExportOpen] = useState(false)
  const exportRef = useRef<HTMLDivElement>(null)
  const [exporting, setExporting] = useState(false)
  /**
   * 🔴 중복 실행 잠금. 내보내기 1회 = 보고서 1건이라 연타는 **목록에 중복 행**이 된다.
   * `exporting` state 는 다음 렌더에야 반영돼 같은 틱의 두 번째 클릭을 못 막는다 — ref 로 즉시 잠근다.
   */
  const exportLock = useRef(false)
  const [notice, setNotice] = useState<ExportNotice | null>(null)

  const exportBlockReason =
    checkedCount === 0
      ? '내보낼 시나리오를 선택하세요'
      : checkedCount > EXPORT_MAX
        ? `한 번에 ${EXPORT_MAX}개까지 내보낼 수 있습니다`
        : null
  const exportDisabled = exportBlockReason != null || exporting

  // 저장 성공 안내만 스스로 걷힌다.
  useEffect(() => {
    if (notice !== 'saved') return
    const timer = window.setTimeout(() => setNotice(null), SAVED_NOTICE_MS)
    return () => window.clearTimeout(timer)
  }, [notice])

  // 잠기면(체크 해제 · 상한 초과) 열려 있던 메뉴도 닫는다.
  useEffect(() => {
    if (exportDisabled) setExportOpen(false)
  }, [exportDisabled])

  // 바깥 클릭 · Esc 로 닫는다(시안과 같은 구현).
  useEffect(() => {
    if (!exportOpen) return
    const onDocClick = (e: MouseEvent) => {
      if (exportRef.current && !exportRef.current.contains(e.target as Node)) setExportOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setExportOpen(false)
    }
    document.addEventListener('click', onDocClick)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('click', onDocClick)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [exportOpen])

  /** 메뉴 항목 공통 — 메뉴를 닫고 저장 → 파일 생성을 한 번에 돌린다. */
  const runExport = async (fileFmt: PolicySimReportFileFmt) => {
    setExportOpen(false)
    if (exportBlockReason != null || exportLock.current) return
    exportLock.current = true
    setExporting(true)
    setNotice(null)
    try {
      await exportPolicySimReport(exportCriteria, fileFmt)
      setNotice('saved')
    } catch (e) {
      if (e instanceof PolicySimSaveError) {
        if (e.status === 404) {
          setNotice('notFound')
          onExportNotFound()
        } else {
          setNotice(e.status === 400 ? 'saveRejected' : 'saveFailed')
        }
      } else {
        setNotice('fileFailed')
      }
    } finally {
      exportLock.current = false
      setExporting(false)
    }
  }

  return (
    <div className="card" style={CARD}>
      <div style={HEAD}>
        <div>
          <div style={TITLE_ROW}>
            <h2 style={TITLE}>시나리오 비교</h2>
            {scenarios.length > 0 && (
              <div className="sc-filter" role="group" aria-label="정책 유형 필터">
                {(
                  [
                    ['all', `전체 (${scenarios.length})`],
                    ...groups.map((g) => [g.cd, `${PLCY_TYPE_NM[g.cd]} (${g.count})`] as const),
                  ] as const
                ).map(([key, label]) => (
                  <button
                    key={key}
                    type="button"
                    className={`sc-filter__chip${activeFilter === key ? ' is-active' : ''}`}
                    aria-pressed={activeFilter === key}
                    onClick={() => setFilter(key)}
                  >
                    {label}
                  </button>
                ))}
              </div>
            )}
          </div>
          {notice && (
            <p style={NOTICE_STYLE[notice]} role={notice === 'saved' ? 'status' : 'alert'}>
              {NOTICE_TEXT[notice]}
            </p>
          )}
        </div>
        <div className="sim-export" ref={exportRef}>
          <button
            className="btn"
            type="button"
            style={exportDisabled ? EXPORT_BTN_DISABLED : EXPORT_BTN}
            disabled={exportDisabled}
            title={exportBlockReason ?? undefined}
            aria-haspopup="menu"
            aria-expanded={exportOpen}
            onClick={(e) => {
              // document 까지 올라가면 바깥 클릭 리스너가 방금 연 메뉴를 곧바로 닫는다.
              e.stopPropagation()
              setExportOpen((v) => !v)
            }}
          >
            <Icon name="download" size={16} />{' '}
            {exporting ? '내보내는 중…' : `내보내기 (${checkedCount})`}
          </button>

          {exportOpen && (
            <div className="sim-export__menu" role="menu">
              {(
                [
                  ['PDF', 'PDF 파일 (.pdf)'],
                  ['DOCX', 'Word 파일 (.docx)'],
                ] as const
              ).map(([fmt, label]) => (
                <button
                  key={fmt}
                  type="button"
                  role="menuitem"
                  className="sim-export__item"
                  style={exportDisabled ? EXPORT_ITEM_DISABLED : undefined}
                  disabled={exportDisabled}
                  onClick={() => {
                    void runExport(fmt)
                  }}
                >
                  {label}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      <div style={SCROLL}>
        <table style={TABLE}>
          <caption className="sr-only">저장된 정책 시나리오 비교</caption>
          <thead>
            <tr>
              <Th center>
                <SrLabel text="전체 선택">
                  <input
                    type="checkbox"
                    aria-label="전체 선택"
                    checked={allChecked}
                    onChange={() => onToggleAll(visible.map((s) => s.key))}
                    style={CHECK}
                  />
                </SrLabel>
              </Th>
              <Th>시나리오</Th>
              <Th center>민원 감소</Th>
              <Th center>혼잡 개선</Th>
              <Th center>비용 수준</Th>
              <Th center>추천</Th>
              <Th center>삭제</Th>
            </tr>
          </thead>
          <tbody>
            {visible.map((s) => {
              const name = scenarioFullName(s.params, s.result.anlsRngCn)
              const r = s.result
              return (
                <tr
                  key={s.key}
                  onClick={() => onSelect(s.key)}
                  style={{
                    cursor: 'pointer',
                    background: activeKey === s.key ? 'var(--blue-99)' : 'transparent',
                  }}
                >
                  <Td center>
                    <SrLabel text={`${name} 선택`}>
                      <input
                        type="checkbox"
                        aria-label={`${name} 선택`}
                        checked={checkedKeys.includes(s.key)}
                        onClick={(e) => e.stopPropagation()}
                        onChange={() => onToggleCheck(s.key)}
                        style={CHECK}
                      />
                    </SrLabel>
                  </Td>
                  <Td>
                    <span style={NAME}>{name}</span>
                    {r.vrblAjmt?.ajmtYn === 'Y' && (
                      <span
                        style={COEFF_MARK}
                        title={(() => {
                          const note = coeffAjmtNote(r.vrblAjmt)
                          return note ? `분석 계수 조정: ${note}` : undefined
                        })()}
                      >
                        {COEFF_AJMT_MARK}
                      </span>
                    )}
                    <div style={SUB}>{s.at}</div>
                  </Td>
                  <Td center>
                    <span style={RDCT}>{fmtPct(r.cvlcptRdctRt)}</span>
                  </Td>
                  <Td center>
                    {/* 전후 지수가 같으면 `변화없음`(시안). 0 이 아닌데 정수로 0 이면 `1% 미만` — 카드와 같은 판정. */}
                    {isNoCngstChange(r) ? (
                      <span style={IMPV_NONE}>변화없음</span>
                    ) : (
                      <span style={IMPV}>
                        {isRoundedAway(r.cngstImpvRt)
                          ? `${r.cngstImpvRt! < 0 ? '-' : '+'}1% 미만`
                          : fmtPct(r.cngstImpvRt)}
                      </span>
                    )}
                  </Td>
                  <Td center>
                    {r.cstLvlCd ? (
                      <span
                        className="badge"
                        style={{
                          background: CST_LVL_META[r.cstLvlCd].bg,
                          color: CST_LVL_META[r.cstLvlCd].color,
                        }}
                      >
                        {CST_LVL_META[r.cstLvlCd].label}
                      </span>
                    ) : (
                      '—'
                    )}
                  </Td>
                  <Td center>
                    {recommendedKey === s.key && (
                      <span className="badge" style={RCMD_BADGE}>
                        추천
                      </span>
                    )}
                  </Td>
                  <Td center>
                    <button
                      type="button"
                      aria-label={`${name} 삭제`}
                      title="삭제"
                      onClick={(e) => {
                        e.stopPropagation()
                        onDelete(s.key)
                      }}
                      style={DEL_BTN}
                      onMouseEnter={(e) => {
                        e.currentTarget.style.background = 'var(--fill-normal)'
                      }}
                      onMouseLeave={(e) => {
                        e.currentTarget.style.background = 'none'
                      }}
                    >
                      <Icon name="close" size={16} />
                    </button>
                  </Td>
                </tr>
              )
            })}
            {truncated && (
              <tr>
                <td colSpan={COLS} style={TRUNCATED_TD}>
                  최근 100건만 표시합니다.
                </td>
              </tr>
            )}
            {savedError && (
              <tr>
                <td colSpan={COLS} style={ERROR_TD} role="alert">
                  {savedError}
                  <button className="btn" type="button" style={RETRY_BTN} onClick={onRetrySaved}>
                    다시 시도
                  </button>
                </td>
              </tr>
            )}
            {savedLoading && scenarios.length === 0 && (
              <tr>
                <td colSpan={COLS} style={EMPTY_TD}>
                  저장된 시나리오를 불러오는 중입니다…
                </td>
              </tr>
            )}
            {!savedLoading && !savedError && scenarios.length === 0 && (
              <tr>
                <td colSpan={COLS} style={EMPTY_TD}>
                  비교할 시나리오가 없습니다. 시뮬레이션을 실행해 시나리오를 추가하세요.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
