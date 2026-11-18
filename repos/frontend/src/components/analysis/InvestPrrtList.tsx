import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import Icon from '../Icon'
import InfoTip from '../common/InfoTip'
import { dashedYmd } from '../../lib/dateRange'
import type {
  InvestPrrtFileFmt,
  InvestPrrtGradeCriterion,
} from '../../api/investPrrt'
import {
  InvestPrrtSaveError,
  exportInvestPrrtReport,
  type InvestPrrtExportCriteria,
} from '../../lib/analysis/investPrrtExport'
import {
  PRRT_LVL_BADGE_CLASS,
  buildLegendRows,
  buildRowMeta,
  dashedYm,
  fmtWgvl,
  prrtLvlLabel,
  type InvestPrrtDataPrd,
  type InvestPrrtDisplayLot,
  type InvestPrrtSort,
} from './investPrrtView'

/*
 * 투자·보강 우선순위 목록 카드. 시안 `.card.prio` 그대로다 —
 * `card-head`(제목 · 가중치 안내문 · 내보내기) + `prio-list > prio-row`.
 *
 * 🔴 **받은 `lots` 를 그대로 그린다** — 여기서 거르거나 자르지 않는다. 표시 집합은 페이지가 한 번
 * 계산해 이 목록과 지도에 같은 배열로 내려준다(각자 자르면 목록에 있는데 지도에 없는 행이 생긴다).
 * 🔴 순위는 **표시 순위 `dspRnk`**(행정구역·정렬 결과 안에서의 순번 — 등급 필터로는 바뀌지 않는다)다 — 페이지가 내려준 값을 그대로 쓰고
 * 여기서 index 로 다시 세지 않는다(지도 핀과 어긋날 여지를 없앤다). 전 지역 순위 `rnk` 는 그리지 않는다.
 * 선택된 행이 목록 밖에 가려 있으면 보이는 자리로 스크롤한다(지도 핀을 눌렀을 때 — 시안 동작).
 * ⚠️ 목록 하단 안내문·`warnings` 문구는 넣지 않는다(사용자 결정 — 시안대로).
 *
 * 내보내기 — 시안의 `sim-export` 메뉴(PDF · Word, 바깥 클릭·Esc 로 닫힘).
 * 🔴 **내보내기 = 보고서 저장이다.** 형식을 고르면 보고서 1건을 저장하고 **그 저장 응답으로** 파일을 만든다
 * (순서·실패 갈래는 `lib/analysis/investPrrtExport`). 저장이 실패하면 파일도 만들지 않는다.
 * 🔴 저장되는 것은 이 목록의 행(상위 N)이 아니라 **그 행정구역의 전체**다 — `exportCriteria` 참고.
 *
 * 분석 계수 조정 — 내보내기 옆 버튼(`coeffAction`)과 배너(`coeffBanner`)는 페이지가 만들어 넘긴다
 * (상태의 주인이 페이지라서다). 여기서는 자리만 잡는다.
 */

/** 내보내기 버튼 치수 — 시안 인라인 값(`HotspotResultPanel` 의 것과 같다). */
const EXPORT_BTN: CSSProperties = {
  height: 36,
  padding: '0 var(--space-12)',
  fontSize: 'var(--label2-size)',
}

/** `.btn`·`.sim-export__item` 에 `:disabled` 규칙이 없어 비활성 표현만 인라인으로 둔다(같은 선례). */
const EXPORT_BTN_DISABLED: CSSProperties = { ...EXPORT_BTN, opacity: 0.5, cursor: 'not-allowed' }
const EXPORT_ITEM_DISABLED: CSSProperties = { opacity: 0.5, cursor: 'not-allowed' }

/** 배너 자리 — 부제 아래. */
const COEFF_BANNER_WRAP: CSSProperties = { marginTop: 'var(--space-4)' }

/** 내보내기 결과 문구 — 색만 다르다(`HotspotResultPanel` 과 같은 토큰). */
const NOTICE_BASE: CSSProperties = { margin: 'var(--space-4) 0 0', fontSize: 'var(--label2-size)' }
const NOTICE_STYLE: Record<ExportNotice, CSSProperties> = {
  saved: { ...NOTICE_BASE, color: 'var(--status-positive)' },
  saveFailed: { ...NOTICE_BASE, color: 'var(--status-negative)' },
  saveRejected: { ...NOTICE_BASE, color: 'var(--status-negative)' },
  // 보고서는 남았고 파일만 못 만든 경우 — 빨강이면 "저장도 실패했다" 로 읽힌다.
  fileFailed: { ...NOTICE_BASE, color: 'var(--status-cautionary)' },
}

/**
 * 내보내기 결과.
 * - `saved`        저장 + 파일 생성 완료
 * - `saveFailed`   저장 실패(네트워크·5xx 등) — 보고서도 파일도 없다
 * - `saveRejected` 저장 실패(400 — 조건 문제. 필터 통과 0곳 포함) — 보고서도 파일도 없다
 * - `fileFailed`   저장은 됐고 파일 생성만 실패 — 🔴 다시 누르면 보고서가 한 건 더 생긴다
 */
type ExportNotice = 'saved' | 'saveFailed' | 'saveRejected' | 'fileFailed'

const NOTICE_TEXT: Record<ExportNotice, string> = {
  saved: '보고서 목록 > 직접 생성 보고서 에 저장되었습니다.',
  saveFailed: '보고서를 저장하지 못해 내보내지 않았습니다. 잠시 후 다시 시도해 주세요.',
  saveRejected:
    '보고서를 저장하지 못해 내보내지 않았습니다. 선택한 행정구역에 분석 대상 주차장이 있는지 확인해 주세요.',
  fileFailed:
    '보고서는 저장되었지만 파일을 만들지 못했습니다. 보고서 목록에서 다시 내려받을 수 있습니다.',
}

/** 저장 성공 안내를 띄워 두는 시간(ms). 실패 안내는 다음 내보내기까지 남는다. */
const SAVED_NOTICE_MS = 5000

/**
 * 산정 기준 InfoTip 의 목록 6줄.
 * 출처: 응답 `crtrVls`·`gradeCriteria`·`weights`.
 * 🔴 반경·표시 수·등급 경계·지표 이름은 응답에서 온다(소스에 300·50·80·60 을 두지 않는다).
 * 값이 없으면(조회 전·실패) 그 줄을 `null` 로 넘겨 생략한다. 팝업 문장에 `※` 를 붙이지 않고,
 * 응답 `warnings` 는 넣지 않는다(팝업은 산정 방법만 설명한다).
 * 지표마다 `집계·산정 기간 —` 줄을 덧붙인다(`buildPrdItems`).
 */
function buildCriteriaItems(
  weights: { vrblCd: string; vrblNm: string }[],
  gradeCriteria: InvestPrrtGradeCriterion[],
  altmntRngVl: number | null,
  indctPkltCnt: number | null,
  dataPrd: InvestPrrtDataPrd | null,
): (string | null)[] {
  const sftyNm = weights.find((w) => w.vrblCd === 'SAFETY')?.vrblNm
  const pctNms = weights.filter((w) => w.vrblCd !== 'SAFETY').map((w) => w.vrblNm)
  const scrParts = [
    // 양호 A 0 · 주의 B 50 · 불량 C 100 은 정의값 — 백엔드 InvestPrrtCalculator.SFTY_SCR_BY_CD 와 같다
    // (등급명은 공통코드 SFTY_CHCK_RSLT_CD). 점검 기록이 없으면 계산기가 0점으로 친다.
    sftyNm && `${sftyNm}: 가장 최근 안전점검 결과(양호 0 · 주의 50 · 불량 100점, 점검 기록이 없으면 0점)`,
    pctNms.length > 0 && `${pctNms.join('·')}: 대상 주차장 안에서의 백분위`,
  ].filter(Boolean)
  const gradeLabels = buildLegendRows(gradeCriteria).map((r) => r.label)

  return [
    // 대상 = 소멸(extsh_dt) 제외 + 노상·공한지 제외(pklt_type_cd) — 조회 기간과 무관 · 이용패턴 없으면 이용 0점 — §2.
    '운영 중인 공영주차장(노상·공한지 제외)이 대상입니다. 이용패턴 자료가 없는 곳은 이용 점수 0점으로 계산합니다.',
    scrParts.length > 0 ? `지표 점수(0~100) — ${scrParts.join('. ')}.` : null,
    altmntRngVl != null
      ? `민원 1건은 반경 ${fmtWgvl(altmntRngVl)}m 안의 대상 주차장 중 가장 가까운 1곳에만 배정해 셉니다.`
      : null,
    // 기간은 여기 두지 않는다 — 지표별 `집계·산정 기간 —` 줄이 응답 날짜로 진다.
    // 🔴 부제(`종합점수 기준 : …`)는 InfoTip 이 있는 제목 줄 **아래**다 — "위 부제" 로 쓰지 않는다.
    //    부제는 `buildWeightNote(weights)` 라 지표가 없으면 비므로 그때는 괄호를 뺀다.
    `종합점수(0~100)는 지표 점수를 가중치${weights.length > 0 ? '(제목 아래 종합점수 기준)' : ''}로 가중평균한 값입니다.`,
    ...buildPrdItems(weights, dataPrd),
    gradeLabels.length > 0 ? `우선순위 등급: ${gradeLabels.join(' · ')}` : null,
    // 표시 순위 규칙은 `investPrrtView.selectDisplayLots` 가 정본이다(§9-1).
    '순위 번호는 행정구역·정렬 기준을 바꾸면 그 결과 안에서 1부터 다시 매깁니다. 우선순위 필터는 번호를 바꾸지 않고 걸러 보여 주기만 하며, 점수는 언제나 전 지역 대상 주차장 기준입니다.' +
      (indctPkltCnt != null ? ` 조건에 맞는 주차장 중 위에서부터 ${indctPkltCnt}곳까지 표시합니다.` : ''),
  ]
}

/**
 * 지표별 `집계·산정 기간 —` 줄. **지표마다 한 줄**, 순서는 `weights` 순서다.
 * 🔴 지표는 이름이 아니라 `vrblCd` 로 고르고, 이름은 `weights.vrblNm` 을 쓴다. 매핑 밖 코드(`ENFORCE` 등 구 스냅샷)는 줄이 없다.
 * 가중치 0 지표는 `weights` 에 없으므로 자연히 빠지고, 값이 없는 지표도 그 줄만 생략한다(전부 없으면 빈 배열).
 * 🔴 "자료가 언제까지 있나" 는 넣지 않는다 — 헤더 `InvestPrrtFreshness` 가 진다(두 곳에 같은 사실을 두지 않는다).
 */
function buildPrdItems(
  weights: { vrblCd: string; vrblNm: string }[],
  dataPrd: InvestPrrtDataPrd | null,
): string[] {
  if (!dataPrd) return []
  const { bgngYmd, endYmd, utztnBgngYm, utztnEndYm, sftyCrtrYmd } = dataPrd
  const ymdRange = bgngYmd && endYmd ? `${dashedYmd(bgngYmd)} ~ ${dashedYmd(endYmd)}` : null
  const prdOf = (vrblCd: string): string | null => {
    switch (vrblCd) {
      case 'SAFETY':
        return sftyCrtrYmd ? `${dashedYmd(sftyCrtrYmd)} 이전 가장 최근 점검 결과` : null
      case 'COMPLAINT':
      case 'ACCIDENT':
        return ymdRange
      case 'USAGE':
        return utztnBgngYm && utztnEndYm
          ? `${dashedYm(utztnBgngYm)} ~ ${dashedYm(utztnEndYm)} (월 집계)`
          : null
      default:
        return null
    }
  }
  return weights.flatMap((w) => {
    const prd = prdOf(w.vrblCd)
    return prd ? [`집계·산정 기간 — ${w.vrblNm}: ${prd}`] : []
  })
}

interface Props {
  /** 표시 집합 — 페이지가 계산한 배열. */
  lots: InvestPrrtDisplayLot[]
  /** 등급 라벨의 출처. */
  gradeCriteria: InvestPrrtGradeCriterion[]
  /** 민원·단속 배정 반경(m) — 응답 `crtrVls.altmntRngVl`. 조회 전·실패면 null. */
  altmntRngVl: number | null
  /** 표시 주차장 수 N — 응답 `crtrVls.indctPkltCnt`. 조회 전·실패면 null. */
  indctPkltCnt: number | null
  /**
   * 시안 `p.card-head__sub.prio-cond` — `조회조건 : {기간} · {행정구역} · 우선순위 {등급}`(`buildCondLabel`).
   * 종전 `분석 기간 : {기간}` 줄을 대신한다. 조회 전·실패면 null(줄을 그리지 않는다).
   */
  condLabel: string | null
  /**
   * 지표별 데이터 기간 — 응답 `bgngYmd`·`endYmd`·`utztnBgngYm`·`utztnEndYm`·`sftyCrtrYmd`.
   * 조회 전·실패면 null(줄을 그리지 않는다).
   */
  dataPrd: InvestPrrtDataPrd | null
  /** 가중치 안내문(응답 `weights` 로 조립한 문장). 비면 줄을 그리지 않는다. */
  weightNote: string
  /** 지금 정렬 기준 — 지표로 정렬하면 행의 종합점수 옆에 그 지표의 값을 덧붙인다(`buildRowMeta`). */
  sort: InvestPrrtSort
  /** 지표 이름의 출처(응답 `weights`). */
  weights: { vrblCd: string; vrblNm: string }[]
  /** 선택된 주차장 — 그 행에 `is-active` 가 붙는다. */
  selectedPkltId: number | null
  onSelect: (pkltId: number) => void
  /** 행 대신 보여 줄 문구(조회 중 · 실패 · 0건). null 이면 행을 그린다. */
  note: string | null
  /** 주면 문구 아래에 `다시 시도` 를 둔다(실패 상태). */
  onRetry?: () => void
  /**
   * 내보내기(= 보고서 저장) 대상 — 화면의 **행정구역 필터**. `null` 이면 버튼을 잠근다
   * (조회 전 · 조회 중 · 실패 · 대상 0곳).
   */
  exportCriteria: InvestPrrtExportCriteria | null
  /** 내보내기 옆 `분석 계수 조정` 버튼. 없으면 그리지 않는다. */
  coeffAction?: ReactNode
  /** 적용 계수 배너 — 기본값과 다를 때만 페이지가 넘긴다. */
  coeffBanner?: ReactNode
}

export default function InvestPrrtList({
  lots,
  gradeCriteria,
  altmntRngVl,
  indctPkltCnt,
  condLabel,
  dataPrd,
  weightNote,
  sort,
  weights,
  selectedPkltId,
  onSelect,
  note,
  onRetry,
  exportCriteria,
  coeffAction,
  coeffBanner,
}: Props) {
  const listRef = useRef<HTMLDivElement>(null)
  const rowRefs = useRef(new Map<number, HTMLDivElement>())

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

  const exportDisabled = exportCriteria == null || exporting

  // 저장 성공 안내만 스스로 걷힌다.
  useEffect(() => {
    if (notice !== 'saved') return
    const timer = window.setTimeout(() => setNotice(null), SAVED_NOTICE_MS)
    return () => window.clearTimeout(timer)
  }, [notice])

  // 잠기면(재조회·필터로 대상이 사라짐) 열려 있던 메뉴도 닫는다.
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
  const runExport = async (fileFmt: InvestPrrtFileFmt) => {
    setExportOpen(false)
    if (exportCriteria == null || exportLock.current) return
    exportLock.current = true
    setExporting(true)
    setNotice(null)
    try {
      await exportInvestPrrtReport(exportCriteria, fileFmt)
      setNotice('saved')
    } catch (e) {
      if (e instanceof InvestPrrtSaveError) {
        setNotice(e.status === 400 ? 'saveRejected' : 'saveFailed')
      } else {
        setNotice('fileFailed')
      }
    } finally {
      exportLock.current = false
      setExporting(false)
    }
  }

  // 선택된 행이 스크롤 영역 밖이면 안으로 들인다. 이미 보이면 움직이지 않는다(시안 `m()` 과 같은 판정).
  useEffect(() => {
    if (selectedPkltId == null) return
    const list = listRef.current
    const row = rowRefs.current.get(selectedPkltId)
    if (!list || !row) return
    const top = row.offsetTop - list.offsetTop
    if (top < list.scrollTop || top + row.offsetHeight > list.scrollTop + list.clientHeight) {
      list.scrollTo({ top: top - 8, behavior: 'smooth' })
    }
  }, [selectedPkltId])

  return (
    <div className="card prio prio--fill">
      {/* 시안 구조: 머리가 세로 2단 — [제목 · 조회조건 · 종합점수 기준] 아래 [계수 조정 · 내보내기] 오른쪽 정렬. */}
      <div className="card-head">
        <div className="prio__head-text">
          <div className="prio__title-row">
            <h2 className="card-head__title">투자·보강 우선순위</h2>
            {/*
             * 우선순위 산정 기준. 문장은 `buildCriteriaItems`(출처: 응답 crtrVls·gradeCriteria·weights).
             * `.side-col` 이 최소 300px 라
             * `block` — 팝업이 `.prio > .card-head`(position:relative) 폭에 맞는다.
             */}
            <InfoTip
              placement="block"
              label="우선순위 산정 기준 안내"
              title="우선순위 산정 기준"
              items={buildCriteriaItems(weights, gradeCriteria, altmntRngVl, indctPkltCnt, dataPrd)}
            />
          </div>
          {condLabel && <p className="card-head__sub prio-cond">{condLabel}</p>}
          {weightNote && <p className="card-head__sub">{weightNote}</p>}
          {coeffBanner && <div style={COEFF_BANNER_WRAP}>{coeffBanner}</div>}
          {notice && (
            <p style={NOTICE_STYLE[notice]} role={notice === 'saved' ? 'status' : 'alert'}>
              {NOTICE_TEXT[notice]}
            </p>
          )}
        </div>
        <div className="prio__actions">
          {coeffAction}
          <div className="sim-export" ref={exportRef}>
            <button
              className="btn"
              type="button"
              style={exportDisabled ? EXPORT_BTN_DISABLED : EXPORT_BTN}
              disabled={exportDisabled}
              aria-haspopup="menu"
              aria-expanded={exportOpen}
              onClick={(e) => {
                // document 까지 올라가면 바깥 클릭 리스너가 방금 연 메뉴를 곧바로 닫는다.
                e.stopPropagation()
                setExportOpen((v) => !v)
              }}
            >
              <Icon name="download" size={16} /> {exporting ? '내보내는 중…' : '내보내기'}
            </button>

            {exportOpen && (
              <div className="sim-export__menu" role="menu">
                {(
                  [
                    ['PDF', 'PDF 파일 (.pdf)'],
                    ['XLSX', 'Excel 파일 (.xlsx)'],
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
      </div>

      <div className="prio-list" ref={listRef}>
        {note != null ? (
          <div className="prio-list__note">
            <p>{note}</p>
            {onRetry && (
              <button className="btn" type="button" onClick={onRetry}>
                다시 시도
              </button>
            )}
          </div>
        ) : (
          lots.map((lot) => (
            <div
              key={lot.pkltId}
              ref={(el) => {
                if (el) rowRefs.current.set(lot.pkltId, el)
                else rowRefs.current.delete(lot.pkltId)
              }}
              className={`prio-row${selectedPkltId === lot.pkltId ? ' is-active' : ''}`}
              role="button"
              tabIndex={0}
              aria-pressed={selectedPkltId === lot.pkltId}
              onClick={() => onSelect(lot.pkltId)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  onSelect(lot.pkltId)
                }
              }}
            >
              <span className="prio-row__rank">{lot.dspRnk}</span>
              <div className="prio-row__body">
                <div className="prio-row__name">{lot.pkltNm}</div>
                <div className="prio-row__meta">{buildRowMeta(lot, sort, weights)}</div>
              </div>
              <span className={PRRT_LVL_BADGE_CLASS[lot.prrtLvlCd] ?? 'badge'}>
                {prrtLvlLabel(lot.prrtLvlCd, gradeCriteria)}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  )
}
