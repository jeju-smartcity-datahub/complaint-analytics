import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import Icon from '../Icon'
import InfoTip from '../common/InfoTip'
import type { LifezoneFileFmt } from '../../api/lifezone'
import type { LifezoneGrade } from '../../api/lifezone'
import type { VrblVls } from '../../api/analysisWeight'
import {
  formatShortagePct,
  supplyCellZoneNm,
  type LifezoneSupplyCell,
  type LifezoneSupplyReportResponse,
  type LifezoneSupplyResult,
} from '../../api/lifezoneSupply'
import {
  exportLifezoneSupplyReport,
  type LifezoneSupplyExportStatus,
} from '../../lib/analysis/lifezoneSupplyExport'
import {
  listedSupplyRows,
  type SupplyListRow,
} from '../../lib/analysis/lifezoneSupplyReportTemplate'
import { GRADE_COLOR_VAR } from './lifezoneGrade'

/*
 * 생활권 **수급** 결과 패널.
 *
 * 🔴 민원건수 패널(`LifezoneResultPanel`)과 **합치지 않는다** — 등급 코드(5단계)는 같아졌지만
 * 판정 기준이 민원 건수가 아니라 **부족률 구간**(경계는 설정 표 `SPLY_GRD_BNDRY1~4`)이고, 목록 행에 붙는 지표도 값 하나가 아니라 수요·공급
 * 두 축이다. 내보내기 저장 코드·스냅샷 모양도 갈린다(아래).
 *
 * 🔴 **내보내기는 민원건수와 저장 코드가 갈린다**(`LIFEZONE_SPLY`). 저장되는 결과 JSON 의 모양이
 * 달라 되읽는 파서가 다르기 때문이다 — 문서 템플릿도 `lifezoneSupplyReportTemplate.ts` 로 따로
 * 둔다. 저장·파일명·표 구성은 전부 `lib/analysis/lifezoneSupplyExport` 가 들고 있고, 이 패널은
 * **버튼을 잠그고 결과를 문구로 바꾸는 일**만 한다.
 */

/**
 * 헤더 우측(내보내기 + 닫기) 묶음 — 민원건수 패널과 같은 시안 인라인 스타일이다.
 * 왼쪽 `<` 를 지워 `marginLeft: auto` 로 오른쪽에 붙인다.
 */
const HEAD_RIGHT: CSSProperties = { display: 'flex', alignItems: 'center', gap: 4, marginLeft: 'auto' }

/** 내보내기 버튼 — `.btn`(44px) 을 헤더 높이에 맞춰 줄인 시안 인라인 스타일. */
const EXPORT_BTN: CSSProperties = {
  height: 36,
  padding: '0 var(--space-12)',
  fontSize: 'var(--label2-size)',
}

/** 0건·진행 중일 때의 버튼 표현. `.btn` 에는 `:disabled` 규칙이 없다(시안에 대기 상태가 없었다). */
const EXPORT_BTN_DISABLED: CSSProperties = { ...EXPORT_BTN, opacity: 0.5, cursor: 'not-allowed' }

/** 잠긴 메뉴 항목 표현. `.sim-export__item` 에도 `:disabled` 규칙이 없다. */
const EXPORT_ITEM_DISABLED: CSSProperties = { opacity: 0.5, cursor: 'not-allowed' }

/** 내보내기 실패 문구 슬롯 — 민원건수 패널과 같은 토큰을 인라인으로 쓴다. */
const EXPORT_ERROR: CSSProperties = {
  margin: 'var(--space-8) 0 0',
  color: 'var(--status-negative)',
  fontSize: 'var(--label2-size)',
}

/** 저장 성공 안내 — 같은 모양이고 색만 바꾼다. */
const SAVE_NOTICE: CSSProperties = { ...EXPORT_ERROR, color: 'var(--status-positive)' }

/**
 * 저장 실패 안내.
 *
 * 🔴 `--status-negative`(내보내기 자체 실패)와 **의도적으로 색을 나눈다** — 이 경우 파일은
 * 정상적으로 내려갔고 목록에만 안 남은 것이라, 같은 빨강으로 두면 "다운로드도 실패했다" 로 읽힌다.
 */
const SAVE_FAIL_NOTICE: CSSProperties = { ...EXPORT_ERROR, color: 'var(--status-cautionary)' }

/** 저장 성공 안내를 띄워 두는 시간(ms). 실패 안내는 스스로 지우지 않는다. */
const SAVE_NOTICE_MS = 5000

const EMPTY_NOTE: CSSProperties = {
  margin: '8px 0 0',
  color: 'var(--text-alternative)',
  fontSize: 13,
}
/** 평가 대상 최소 기준·격자 해석 주의 안내 — 0건 안내와 같은 보조 글씨. */
const SUM_NOTE: CSSProperties = {
  margin: '8px 0 0',
  color: 'var(--text-alternative)',
  fontSize: 12,
  lineHeight: 1.5,
}
/**
 * 선택된 셀 — 격자ID만으로는 부족해 `seq` 를 함께 받는다.
 *
 * 🔴 같은 셀을 다시 눌렀을 때도 목록이 그 행을 다시 보여 줘야 한다. `gridId` 만 보면 값이
 * 그대로라 스크롤 effect 가 돌지 않아, 선택은 됐는데 행은 화면 밖에 있는 상태가 된다
 * (민원건수 패널의 `LifezoneSelection` 과 같은 장치 — 키만 격자ID 로 갈렸다).
 */
export interface LifezoneSupplySelection {
  gridId: string
  seq: number
}

export interface LifezoneSupplyResultPanelProps {
  /** 이 결과를 만든 실행의 기간 라벨 — 부제 `분석 기간 : …`(시안). 페이지가 실행 시점에 확정해 내려 준다. */
  periodLabel: string
  /**
   * 분석 응답 **전체**.
   *
   * 🔴 페이지의 현재 입력값이 아니라 **응답이 되비춘 "적용된 조건"** 을 쓰기 위해 통째로 받는다 —
   * 분석을 돌린 뒤 설정만 바꿔 놓아도 패널이 실제 결과를 설명하게 하려는 것이다.
   */
  result: LifezoneSupplyResult
  selection: LifezoneSupplySelection | null
  onSelectCell: (gridId: string) => void
  /**
   * 저장 성공 알림(선택). 문서를 내려받기 **전에** 호출된다.
   *
   * ⚠️ 화면 결과를 스냅샷으로 갈아 끼우라는 뜻이 아니다 — 저장 응답은 `dataBgngYmd`·`dataEndYmd`
   * 를 의도적으로 비우므로 대입하면 0건 안내가 잃는 것이 생긴다(민원건수 패널과 같은 방침).
   */
  onReportSaved?: (report: LifezoneSupplyReportResponse) => void
  onClose: () => void
  /**
   * 🆕 이 결과를 만든 실행의 수급 계수 일시 조정 — 보고서 저장에 그대로 싣는다. 설정값이면 `null`.
   * 🔴 **필수 prop 이다** — 선택이면 빠뜨려도 TS 가 못 잡아 보고서만 설정값으로 계산된다.
   */
  vrblVls: VrblVls | null
}

export default function LifezoneSupplyResultPanel({
  result,
  periodLabel,
  selection,
  onSelectCell,
  onReportSaved,
  onClose,
  vrblVls,
}: LifezoneSupplyResultPanelProps) {
  const selectedGridId = selection?.gridId ?? null
  const bodyRef = useRef<HTMLDivElement>(null)
  const rowRefs = useRef(new Map<string, HTMLDivElement>())

  // ── 내보내기 ──
  const [exportOpen, setExportOpen] = useState(false)
  const exportRef = useRef<HTMLDivElement>(null)
  /** 저장·문서 생성이 도는 동안 버튼과 두 메뉴 항목을 잠근다 — 연타로 보고서가 여러 건 남는 것을 막는다. */
  const [exporting, setExporting] = useState(false)
  /** 캡처·변환 실패(= 파일이 아예 안 받아지는 경우). 저장 실패와는 다른 상태다. */
  const [exportFailed, setExportFailed] = useState(false)
  /** 저장 결과 안내. 파일은 두 경우 모두 내려갔고 다른 것은 목록에 남았는지뿐이다. */
  const [saveNotice, setSaveNotice] = useState<LifezoneSupplyExportStatus | null>(null)

  // 저장 성공 안내만 스스로 걷힌다. 화면을 떠나거나 다시 내보내면 타이머도 함께 정리된다.
  useEffect(() => {
    if (saveNotice !== 'saved') return
    const timer = window.setTimeout(() => setSaveNotice(null), SAVE_NOTICE_MS)
    return () => window.clearTimeout(timer)
  }, [saveNotice])

  /**
   * 바깥 클릭 · `Escape` 로 메뉴를 닫는다 — 민원건수 패널과 같은 구현이다.
   * 토글 버튼이 `stopPropagation()` 을 하므로 "여는 클릭" 이 곧바로 이 리스너에 잡혀 다시 닫히지
   * 않는다(React 18 은 핸들러를 루트 컨테이너에 붙이므로 거기서 멈추면 `document` 까지 오지 않는다).
   */
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

  /**
   * 요약 5행 — 개수는 응답 요약값(`severeCnt`…`lowCnt`)을 그대로 쓰고, 등급명은 범례(`gradeCriteria`)에서 읽는다.
   * 🔴 셀을 다시 세지 않는다(같은 숫자를 두 번 세지 않는다). 순서는 시안 그대로 심각 → 낮음이다.
   */
  const gradeRows = useMemo(() => {
    const nmOf = new Map(result.gradeCriteria.map((c) => [c.grade, c.gradeNm]))
    const cnts: [LifezoneGrade, number][] = [
      ['SEVERE', result.severeCnt],
      ['WARN', result.warnCnt],
      ['CAUTION', result.cautionCnt],
      ['NORMAL', result.normalCnt],
      ['LOW', result.lowCnt],
    ]
    return cnts.map(([grade, cnt]) => ({ grade, gradeNm: nmOf.get(grade) ?? grade, cnt }))
  }, [result])

  /**
   * 목록에 그릴 행 — **심각·경고·주의 전부**(민원 건수 탭과 같은 규칙).
   * 🔴 종전 상한 15곳은 폐기했다 — 거르는 기준은 서버 `grade` 하나다(`listedSupplyRows` → `isListedGrade`).
   * 🔴 신규 보고서 표와 **같은 함수**이고, 순위는 서버 `rnk`(전체 순위)다 — 프론트에서 재정렬하지 않는다.
   * ⚠️ 지도에서 보통·낮음 셀을 고르면 목록에 그 행이 없다(스크롤 effect 가 그냥 넘어간다).
   */
  const rows = useMemo<SupplyListRow<LifezoneSupplyCell>[]>(
    () => listedSupplyRows(result.cells),
    [result.cells],
  )

  // 선택된 행을 보이게 굴린다. `seq` 가 바뀌면 같은 셀이라도 다시 돈다.
  useEffect(() => {
    if (!selection) return
    const row = rowRefs.current.get(selection.gridId)
    if (!row || !bodyRef.current) return
    row.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [selection])

  const isEmpty = result.cells.length === 0
  /** 0건이면 토글 자체를 막는다 — 머리글만 있는 빈 문서를 만들 이유가 없고 서버도 400 이다. */
  const exportDisabled = isEmpty || exporting

  /**
   * 내보내기 실행.
   *
   * 🔴 조건은 설정 패널의 현재 입력값이 아니라 **응답이 되비춘 "적용된 조건"** 이다 —
   * `result` 를 통째로 넘기면 그 규칙이 저절로 지켜진다.
   * 저장 성공/실패의 갈래와 파일명 규칙은 전부 `exportLifezoneSupplyReport` 안에 있다.
   */
  const runExport = async (fileFmt: LifezoneFileFmt) => {
    setExportOpen(false)
    if (exportDisabled) return
    setExporting(true)
    setExportFailed(false)
    setSaveNotice(null)
    try {
      const status = await exportLifezoneSupplyReport({
        result,
        fileFmt,
        vrblVls,
        onSaved: onReportSaved,
      })
      setSaveNotice(status)
    } catch {
      // 캡처·변환 실패는 서버 오류가 아니라 브라우저 쪽 문제라 상태코드로 갈릴 게 없다.
      setExportFailed(true)
    } finally {
      setExporting(false)
    }
  }

  return (
    <div className="sim-result">
      <div className="sim-result__top">
        <div style={HEAD_RIGHT}>
          <div className="sim-export" ref={exportRef}>
            <button
              className="btn"
              type="button"
              style={exportDisabled ? EXPORT_BTN_DISABLED : EXPORT_BTN}
              disabled={exportDisabled}
              aria-haspopup="menu"
              aria-expanded={exportOpen}
              onClick={(e) => {
                // 이 클릭이 document 까지 올라가면 위 바깥클릭 리스너가 방금 연 메뉴를 곧바로 닫는다.
                e.stopPropagation()
                setExportOpen((v) => !v)
              }}
            >
              <Icon name="download" size={16} /> {exporting ? '내보내는 중…' : '내보내기'}
            </button>

            {exportOpen && (
              <div className="sim-export__menu" role="menu">
                {/*
                 * 저장·표 구성·파일명·사분면 색은 전부 `lib/analysis/lifezoneSupplyExport` 와
                 * `lib/analysis/lifezoneSupplyReportTemplate` 이 들고 있다 — 여기서는 형식만 고른다.
                 */}
                <button
                  type="button"
                  role="menuitem"
                  className="sim-export__item"
                  style={exportDisabled ? EXPORT_ITEM_DISABLED : undefined}
                  disabled={exportDisabled}
                  onClick={() => {
                    void runExport('PDF')
                  }}
                >
                  PDF 파일 (.pdf)
                </button>
                {/* 엑셀 — 저장 실패·`wbk` 부재면 폴백 파일 없이 `내보내기 실패`. */}
                <button
                  type="button"
                  role="menuitem"
                  className="sim-export__item"
                  style={exportDisabled ? EXPORT_ITEM_DISABLED : undefined}
                  disabled={exportDisabled}
                  onClick={() => {
                    void runExport('XLSX')
                  }}
                >
                  Excel 파일 (.xlsx)
                </button>
              </div>
            )}
          </div>

          <button className="sim-result__x" type="button" aria-label="닫기" onClick={onClose}>
            <Icon name="close" size={22} />
          </button>
        </div>
      </div>

      <div className="sim-result__body" ref={bodyRef}>
        {/* ── ① 분석 결과 요약 ── */}
        <div className="sim-sec">
          {/* 시안 구조 — 민원건수 패널과 같은 `분석 결과 요약 [500M]`(제목 옆 칩). */}
          <div className="sim-sec__head">
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <h3 className="sim-sec__title" style={{ margin: 0 }}>분석 결과 요약</h3>
                {/* 🔴 응답 `gridSzNm` 을 그대로 넣는다 — `.size-chip` 의 uppercase 가 표기를 만든다. */}
                <span className="size-chip">{result.gridSzNm}</span>
              </div>
              <p className="sim-sec__sub" style={{ margin: '4px 0 0' }}>분석 기간 : {periodLabel}</p>
            </div>
            {/*
             * 등급 산정 기준 InfoTip — 시안 위치(`.sim-sec__head` 두 번째 자식 · InfoTip(`placement="right"`)). 민원건수 탭과 같은 배치다.
             * 🔴 구간 문구는 서버 `gradeCriteria[].lgndTxt` 그대로 쓴다 — 경계 숫자를 프론트에 다시 적지 않는다.
             * 경계·최소 민원은 설정 > 분석 지표 가중치 설정(`tb_anls_vrbl_stng`)에서 바뀌므로 구간 폭 같은
             * 고정 서술도 두지 않는다.
             */}
            <InfoTip
              placement="right"
              label="등급 산정 기준 안내"
              title="등급 산정 기준"
              items={[
                '부족률 = 주차 민원 ÷ (공영주차면 + 주차 민원)입니다.',
                <>
                  등급은 부족률 구간으로 나눕니다 —{' '}
                  {result.gradeCriteria.map((c) => c.lgndTxt).join(' · ')}.
                </>,
                <>
                  월평균 주차 민원 {result.minMnthAvgCvlcpt.toLocaleString()}건 이상인 격자만 평가합니다.
                </>,
                '공영주차면이 0면인 격자는 부족률 100%로 심각 등급입니다.',
                <>등급 경계와 최소 민원 기준은 관리자가 설정 &gt; 분석 지표 가중치 설정에서 바꿀 수 있습니다.</>,
              ]}
            />
          </div>
          {exportFailed && (
            <p style={EXPORT_ERROR} role="alert">
              내보내기에 실패했습니다. 잠시 후 다시 시도해 주세요.
            </p>
          )}
          {/*
           * 저장 결과 안내 — 파일은 두 경우 모두 내려갔고, 다른 것은 목록에 남았는지뿐이다.
           * `role="status"` 라 스크린리더가 현재 작업을 끊지 않고 읽어 준다(실패 문구의 `alert` 와 다르다).
           */}
          {saveNotice === 'saved' && (
            <p style={SAVE_NOTICE} role="status">
              보고서 목록 &gt; 직접 생성 보고서 에 저장되었습니다.
            </p>
          )}
          {saveNotice === 'unsaved' && (
            <p style={SAVE_FAIL_NOTICE} role="status">
              보고서 목록에 저장하지 못했습니다. 파일은 정상적으로 내려받았습니다.
            </p>
          )}

          <div className="sum">
            <div className="sum__row sum__row--total">
              <span>총 분석 격자</span>
              <b>{result.totGridCnt.toLocaleString()}개</b>
            </div>
            {/* 시안대로 총 분석 격자 + 5등급 — 보통·낮음도 요약에는 싣는다(목록에서만 빠진다). */}
            {gradeRows.map((row) => (
              <div className="sum__row" key={row.grade}>
                <span>
                  {/* 등급색은 CSS 변수 표기 그대로 넣는다 — DOM `style` 은 `var()` 를 받는다. */}
                  <span className="dot" style={{ background: GRADE_COLOR_VAR[row.grade] }} />
                  {row.gradeNm}
                </span>
                <b>{row.cnt.toLocaleString()}개</b>
              </div>
            ))}
          </div>

          {/*
           * 범례 5행(서버 `gradeCriteria[].lgndTxt` 그대로)은 지도 좌하단 범례가 그린다(`LifezoneSupplyMap` — 민원 건수 탭과
           * 같은 자리). 공용 셸 `GridCellMap` 을 고치지 않으려 범례 보조 문구인 최소 기준은 요약 아래에 둔다.
           * 🔴 이 문구를 빼지 않는다 — 걸러진 격자가 "민원 없음" 으로 오해되지 않게 한다.
           */}
          <p style={SUM_NOTE} role="note">
            월평균 민원 {result.minMnthAvgCvlcpt.toLocaleString()}건 이상 격자만 평가
          </p>
          {/* 🔴 100m 격자 해석 주의 — 서버가 100m 에서만 내린다. 문구는 서버 것 그대로. */}
          {result.gridNotice && (
            <p style={SUM_NOTE} role="note">
              {result.gridNotice}
            </p>
          )}
        </div>

        {/* ── ② 구역 목록 (심각·경고·주의 전부 · 칩 없음) ── */}
        <div className="sim-sec">
          {/* 🔴 제목·부제 모두 서버(`sectionTitle`·`sectionSub`)가 내린다 — 시안 `격자 기반 수요·공급 격차 (심각, 경고, 주의)`. */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <h3 className="sim-sec__title" style={{ margin: 0 }}>{result.sectionTitle}</h3>
            {/*
             * 🔴 데이터 한계 안내 — 종전 목록 아래 상시 문구를 InfoTip 으로 옮겼다.
             * 빼지 않는다 — 산출물이 "주차 부족 면수" 로 읽히지 않게 한다. 문구는 서버 `disclaimer` 그대로.
             */}
            <InfoTip
              placement="right"
              label="주차 수급 부족 구역 안내"
              title={result.sectionTitle}
              style={{ marginLeft: 'auto' }}
              items={[result.disclaimer]}
            />
          </div>
          <p className="sim-sec__sub">{result.sectionSub}</p>

          {isEmpty ? (
            <p style={EMPTY_NOTE} role="status">
              해당 조건에 분석 대상 격자가 없습니다.
              <br />
              {/* 0건의 대부분은 기간을 잘못 고른 경우다 — 원천이 실제로 갖고 있는 구간을 알려 준다. */}
              원천 보유 구간: {formatYmd(result.dataBgngYmd)} ~ {formatYmd(result.dataEndYmd)}
            </p>
          ) : (
            <div className="rl">
              {rows.map(({ cell, dispRnk }) => (
                <div
                  key={cell.gridId}
                  className={`rl__row${selectedGridId === cell.gridId ? ' is-active' : ''}`}
                  /*
                   * 시안은 클릭만 받는 `<div>` 다. 지도를 못 쓰는 사용자에게 이 목록이 유일한
                   * 선택 수단이라 키보드 경로를 함께 연다 — 보이는 모양은 그대로다.
                   */
                  role="button"
                  tabIndex={0}
                  aria-current={selectedGridId === cell.gridId ? true : undefined}
                  ref={(el) => {
                    // 언마운트되는 행은 지운다 — 남겨 두면 이전 결과의 DOM 을 스크롤 대상으로 잡는다.
                    if (el) rowRefs.current.set(cell.gridId, el)
                    else rowRefs.current.delete(cell.gridId)
                  }}
                  onClick={() => onSelectCell(cell.gridId)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      onSelectCell(cell.gridId)
                    }
                  }}
                >
                  {/* 🔴 서버 `rnk`(전체 순위) 그대로다 — 보통·낮음이 빠져도 순위가 바뀌지 않는다 */}
                  <span className="rl__rank">{dispRnk}</span>
                  <div className="rl__main">
                    <div className="rl__top">
                      <span className="rl__name">
                        <span className="dot" style={{ background: GRADE_COLOR_VAR[cell.grade] }} />
                        {/* POI 구역명(민원 무게중심 최근접) → 읍면동명 → 격자ID 폴백 */}
                        {supplyCellZoneNm(cell)}
                      </span>
                      <span className="rl__val">
                        {/* 시안 `82%` — 부족률 ×100 반올림. 판정에는 쓰지 않는다(등급은 서버 `grade`). */}
                        <span className="rl__cnt">{formatShortagePct(cell.shortageRatio)}%</span>
                      </span>
                    </div>
                    <span className="rl__sub">
                      {/*
                       * 시안 `수요 69대 · 공급 12면`. 수요 = 면수 + 민원(`demandIndex`), 공급 = 공영주차면.
                       * 🔴 공급은 공급량 자체다 — "부족 N면" 이 아니다. 0 이면 `0면` 그대로 보인다.
                       */}
                      수요 {cell.demandIndex.toLocaleString()}대 · 공급 {cell.pkltCpctySum.toLocaleString()}면
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}

        </div>
      </div>
    </div>
  )
}

/** `YYYYMMDD` → `YYYY-MM-DD`. 원천이 비면 `null` 이 오므로 그때는 `-` 로 둔다. */
function formatYmd(ymd: string | null): string {
  if (!ymd || ymd.length !== 8) return '-'
  return `${ymd.slice(0, 4)}-${ymd.slice(4, 6)}-${ymd.slice(6, 8)}`
}
