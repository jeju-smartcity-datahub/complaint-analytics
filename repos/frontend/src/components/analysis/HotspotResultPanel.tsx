import { useEffect, useRef, useState, type CSSProperties } from 'react'
import Icon from '../Icon'
import InfoTip from '../common/InfoTip'
import type {
  GradeCriterion,
  HotspotFileFmt,
  HotspotReportResponse,
  HotspotWeight,
  HotspotZone,
} from '../../api/analysis'
import { describeGradeRanks, GRADE_BADGE_CLASS, GRADE_COLOR_VAR } from './hotspotGrade'
import {
  exportHotspotReport,
  formatDecimal,
  weightsCaption,
  zoneDetail,
  type HotspotExportCriteria,
  type HotspotExportStatus,
} from '../../lib/analysis/hotspotExport'

/*
 * 불법 주차 집중 구역 분석 — 우측 순위 결과 패널 (TSK-4032 Step 13).
 *
 * 마크업·클래스는 시안(https://innopamsolution.github.io/jeju-datahub/)의
 * `.sim-result`(헤더 `.sim-result__top` + 본문 `.sim-result__body > .sim-sec > .rl`)를 그대로 옮겼다.
 * 스타일은 Step 9 의 `src/styles/analysis.css` 가 이미 갖고 있다 — 여기서 CSS 를 새로 만들지 않는다.
 *
 * 설정 패널(`HotspotSettingPanel`)과 마찬가지로 **controlled 프레젠테이션 컴포넌트**다.
 * 선택 상태(`selectedRnk`)·열림 여부는 `HotspotPage` 가 들고 있고, 여기서는 그리고 이벤트를 올려 보낸다
 * — 지도(마커)와 목록이 **같은 선택 상태 하나**를 봐야 양방향 연동이 어긋나지 않기 때문이다.
 *
 * 시안과 의도적으로 다른 부분
 *   - 부제를 응답의 `weights` 로 조립한다(D4). 시안은 `민원 건수 60% / 단속 40%` 를 화면에 박아 뒀지만,
 *     실제 지표명은 DB(`TB_ANLS_WGVL`)의 `민원빈도`·`단속건수` 이고 비율도 설정 화면에서 바뀐다 —
 *     **DB 값이 정본**이라 응답을 그대로 쓴다(가중치를 따로 조회하지도 않는다).
 *   - 행의 점(`.dot`) 색을 등급(`grade`)에서 고른다 — 목데이터의 `dotColor` 상수 대신 `hotspotGrade.ts`.
 *   - 좌표가 없는 구역은 마커가 없다. 그래도 **행은 남기고** 클릭도 받는다(선택만 되고 지도는 안 움직인다).
 *
 * 이 단계의 범위
 *   - ✅ 헤더(내보내기 드롭다운 · 닫기 — 뒤로가기는 TSK-4243 에서 걷었다) · 순위 목록 · 리스트 ↔ 마커 양방향 연동
 *   - ✅ (Step 14) 내보내기 메뉴가 `lib/analysis/hotspotExport` 로 PDF / Word 를 만든다.
 *        표기(점수·세부 내역·가중치 문구)는 **그 모듈의 헬퍼를 그대로 쓴다** — 화면과 문서가 갈리지 않게 한다.
 *   - ✅ (TSK-4078 Step 9) **내보내기 = 저장**이다. 파일을 만들기 전에
 *        `POST /api/analysis/hotspot/reports` 로 보고서 1건을 남기고, 그 스냅샷으로 문서를 만든 뒤
 *        **화면 목록도 같은 스냅샷으로 갈아 끼운다**(`onReportSaved` — 화면 = 파일 = DB, D3).
 *        저장이 실패해도 다운로드는 진행하고 문구로만 알린다(D11).
 */

/**
 * 헤더 우측(내보내기 + 닫기) 묶음 — 시안 인라인 스타일 그대로.
 * 뒤로가기(`<`)를 걷어 `.sim-result__top` 의 자식이 하나라 `space-between` 으로는 왼쪽에 붙는다 —
 * `marginLeft: 'auto'` 로 오른쪽으로 민다(구역추천·생활권 패널과 같은 방식, TSK-4243).
 */
const HEAD_RIGHT: CSSProperties = { display: 'flex', alignItems: 'center', gap: 4, marginLeft: 'auto' }

/** 내보내기 버튼 — `.btn`(44px) 을 헤더 높이에 맞춰 줄인 시안 인라인 스타일 그대로. */
const EXPORT_BTN: CSSProperties = {
  height: 36,
  padding: '0 var(--space-12)',
  fontSize: 'var(--label2-size)',
}

/**
 * 내보낼 게 없거나(0건) 이미 내보내는 중일 때의 버튼 표현.
 * `.btn` 에는 `:disabled` 규칙이 없어(시안에 대기 상태가 없었다) 최소한의 시각 피드백만 인라인으로
 * 얹는다 — `HotspotSettingPanel` 의 `RUN_DISABLED` 와 같은 이유·같은 방식이다.
 */
const EXPORT_BTN_DISABLED: CSSProperties = { ...EXPORT_BTN, opacity: 0.5, cursor: 'not-allowed' }

/**
 * 내보내기 실패 문구. `analysis.css` 에는 결과 패널용 오류 슬롯이 없어(시안에 실패 상태가 없었다)
 * 보고서 화면(`ReportsPage` 의 `ERROR_TEXT`)과 같은 토큰을 인라인으로 쓴다 —
 * `analysis.css` 는 Step 9 산출물이라 이 단계에서 건드리지 않는다.
 */
const EXPORT_ERROR: CSSProperties = {
  margin: 'var(--space-4) 0 0',
  color: 'var(--status-negative)',
  fontSize: 'var(--label2-size)',
}

/**
 * 저장 성공 안내 — `보고서 목록 > 직접 생성 보고서 에 저장되었습니다`.
 * 위 `EXPORT_ERROR` 와 **같은 인라인 슬롯**을 쓰고 색만 바꾼다(`analysis.css` 는 TSK-4032 Step 9
 * 산출물이라 이 단계에서 건드리지 않는다). 새 토스트/알림 라이브러리를 들이지 않는다.
 */
const SAVE_NOTICE: CSSProperties = {
  margin: 'var(--space-4) 0 0',
  color: 'var(--status-positive)',
  fontSize: 'var(--label2-size)',
}

/**
 * 저장 실패 안내 — `보고서 목록에 저장하지 못했습니다`.
 *
 * `--status-negative`(내보내기 자체 실패)와 **의도적으로 색을 나눈다**: 이 경우 파일은 정상적으로
 * 내려갔고 목록에만 안 남은 것이라, 같은 빨강으로 두면 "다운로드도 실패했다"로 읽힌다.
 */
const SAVE_FAIL_NOTICE: CSSProperties = { ...SAVE_NOTICE, color: 'var(--status-cautionary)' }

/** 저장 성공 안내를 띄워 두는 시간(ms). "잠깐" 이면 되고, 실패 안내는 지우지 않는다. */
const SAVE_NOTICE_MS = 5000

/** 내보내는 중인 메뉴 항목 표현. `.sim-export__item` 에는 `:disabled` 규칙이 없어 최소한만 인라인으로 얹는다. */
const EXPORT_ITEM_DISABLED: CSSProperties = { opacity: 0.5, cursor: 'not-allowed' }

/** 스크롤로 행을 끌어올릴 때 컨테이너 위쪽에 남기는 여백(px) — 시안과 동일. */
const SCROLL_MARGIN = 8

/**
 * 선택된 구역 — **순위(`rnk`)만으로는 부족해 `seq` 를 함께 받는다.**
 *
 * 🔴 사용자가 마커로 3위를 고른 뒤 목록을 손으로 굴려 그 행을 화면 밖으로 치우고 **같은 마커를 다시**
 * 누르는 경우가 있다. `rnk` 만 보면 값이 그대로라 스크롤 effect 가 돌지 않아, 선택은 됐는데 행은
 * 여전히 안 보이는 상태가 된다. 그래서 부모가 **선택 이벤트마다**(출처가 목록이든 마커든) `seq` 를
 * 올려 새 객체를 넘기고, 이 패널은 그 신호로 "다시 보여 달라"를 판단한다.
 */
export interface HotspotSelection {
  rnk: number
  seq: number
}

/** 부제의 `분석 기간`·`종합점수 기준` 구분자 — 시안 인라인 스타일 그대로(D13). */
const SUB_DIVIDER: CSSProperties = { margin: '0 6px', color: 'var(--line-normal)' }

export interface HotspotResultPanelProps {
  /** 순위 목록. **서버 정렬 순서 그대로**이며 `rnk` 도 서버가 매긴 값이다(프론트 재정렬 금지). */
  zones: HotspotZone[]
  /** 계산에 쓰인 가중치. 부제 문구의 유일한 출처다(D4). */
  weights: HotspotWeight[]
  /**
   * 이 결과가 조정한 가중치로 만들어졌는지(TSK-4306). 부제 뒤 ` (조정됨)` 의 근거다.
   * 화면 전용 문구다 — 보고서는 저장된 `wgvlAjmtYn` 으로 ` (가중치 조정 적용)` 을 따로 붙인다(D14).
   */
  weightsAdjusted: boolean
  /**
   * 결과를 만든 실행의 기간 라벨 — `최근 N개월` 또는 `YYYY-MM-DD ~ YYYY-MM-DD`(D13).
   * 🔴 설정 패널의 현재 선택이 아니다. 실행 뒤 세그먼트만 바꿔도 이 값은 그대로다.
   */
  periodLabel: string
  /** 적용된 DBSCAN 반경(m) — 응답 `eps`. 순위 산정 기준 InfoTip 문구의 출처다(TSK-4242 D5). */
  eps: number
  /** 적용된 DBSCAN 최소 지점 수 — 응답 `minPoints`(서로 다른 지점 수). */
  minPoints: number
  /** 등급 기준 — 응답 `gradeCriteria`(구버전 응답이면 `[]` → 등급 줄 생략). */
  gradeCriteria: GradeCriterion[]
  /**
   * 내보내기(=저장) 요청에 실을 분석 조건.
   *
   * 🔴 설정 패널의 **현재 입력값이 아니라 응답이 되비춘 "적용된 조건"** 이다 — 분석을 돌린 뒤
   * select 만 바꿔 놓고 내보내면 화면의 표와 저장된 보고서가 다른 조건이 되기 때문이다.
   */
  criteria: HotspotExportCriteria
  /**
   * 저장 성공 알림 — 부모가 화면 결과(`zones`·`weights`)를 이 스냅샷으로 갈아 끼운다(D3).
   * 서버가 같은 조건으로 **재분석**한 결과라 화면과 미세하게 다를 수 있고, 그때 정본은 저장된 쪽이다.
   */
  onReportSaved: (report: HotspotReportResponse) => void
  /** 현재 선택. 목록 행 클릭과 지도 마커에서 온 선택이 **같은 통로**로 들어온다. */
  selection: HotspotSelection | null
  /** 행 클릭. 부모가 선택 상태를 바꾸고 지도에 `setView`(확대, TSK-4241) + `openPopup` 을 요청한다. */
  onSelectZone: (rnk: number) => void
  /** 패널 닫기(`X`). 같은 동작이던 뒤로가기(`<`)는 TSK-4243 에서 걷었다. */
  onClose: () => void
}

export default function HotspotResultPanel({
  zones,
  weights,
  weightsAdjusted,
  periodLabel,
  eps,
  minPoints,
  gradeCriteria,
  criteria,
  onReportSaved,
  selection,
  onSelectZone,
  onClose,
}: HotspotResultPanelProps) {
  /** 하이라이트용 순위. 스크롤은 `seq` 까지 보는 아래 effect 가 따로 판단한다. */
  const selectedRnk = selection?.rnk ?? null
  /** 스크롤 컨테이너. 시안도 `.sim-result__body` 에 ref 를 건다(`.rl` 이 아니다). */
  const bodyRef = useRef<HTMLDivElement>(null)
  /** `rnk` → 행 DOM. 시안은 배열 + `rank-1` 인덱스지만, 순위가 1부터 연속이라는 가정을 두지 않는다. */
  const rowRefs = useRef(new Map<number, HTMLDivElement>())

  // ── 내보내기 드롭다운 (Step 14) ──
  const [exportOpen, setExportOpen] = useState(false)
  const exportRef = useRef<HTMLDivElement>(null)
  /**
   * 내보내는 동안 토글 버튼과 **두 메뉴 항목을 함께** 잠근다.
   *
   * 🔴 TSK-4078 부터 내보내기 1회 = **보고서 1건 저장**이라, 연타는 파일이 여러 번 받아지는 정도가
   * 아니라 **목록에 중복 행이 쌓이는 것**이다(D7). 그래서 잠금이 UX 편의가 아니라 정합성 장치다.
   */
  const [exporting, setExporting] = useState(false)
  const [exportFailed, setExportFailed] = useState(false)
  /**
   * 저장 결과 안내. `saved` 는 잠깐 뒤 스스로 사라지고, `unsaved`(D11)는 다음 내보내기까지 남는다 —
   * 저장에 실패했다는 사실은 사용자가 놓치면 안 되기 때문이다.
   */
  const [saveNotice, setSaveNotice] = useState<HotspotExportStatus | null>(null)

  // 저장 성공 안내만 스스로 걷힌다. 화면을 떠나거나 다시 내보내면 타이머도 함께 정리된다.
  useEffect(() => {
    if (saveNotice !== 'saved') return
    const timer = window.setTimeout(() => setSaveNotice(null), SAVE_NOTICE_MS)
    return () => window.clearTimeout(timer)
  }, [saveNotice])

  /**
   * 🔴 0건 방어. `HotspotPage` 는 0건이면 이 패널을 아예 열지 않지만, 그 판단이 바뀌어도
   * **머리글만 있는 빈 표 파일**이 내려가지 않도록 여기서도 잠근다(`hotspotExport` 안에도 같은 방어가 있다).
   */
  const canExport = zones.length > 0
  const exportDisabled = !canExport || exporting

  /**
   * 메뉴 항목 공통 동작 — **메뉴를 먼저 닫고** 저장 → 문서 → 다운로드를 한 번에 돌린다(시안도 그 순서다).
   *
   * 저장 성공/실패의 갈래와 파일명 규칙은 전부 `exportHotspotReport` 안에 있다 — 여기서는
   * **버튼을 잠그고 결과를 문구로 바꾸는 일**만 한다.
   * 캡처·변환 실패(= 파일이 아예 안 받아지는 경우)는 서버 오류가 아니라 브라우저 쪽 문제라
   * 상태코드로 갈릴 게 없어 단일 문구다(`ReportsPage.DownloadCell` 과 같은 처리).
   */
  const runExport = async (fileFmt: HotspotFileFmt) => {
    setExportOpen(false)
    if (exportDisabled) return
    setExporting(true)
    setExportFailed(false)
    setSaveNotice(null)
    try {
      // 저장 성공 스냅샷은 **다운로드보다 먼저** 화면에 반영된다 — 화면 = 파일 = DB(D3).
      const status = await exportHotspotReport({
        criteria,
        fileFmt,
        zones,
        weights,
        onSaved: onReportSaved,
      })
      setSaveNotice(status)
    } catch {
      setExportFailed(true)
    } finally {
      setExporting(false)
    }
  }

  /**
   * 바깥 클릭 · `Escape` 로 닫는다 — 시안과 같은 구현이다.
   * 토글 버튼이 `stopPropagation()` 을 하므로 "여는 클릭"이 곧바로 이 리스너에 잡혀 다시 닫히지 않는다
   * (React 18 은 핸들러를 루트 컨테이너에 붙이므로 거기서 멈추면 `document` 까지 오지 않는다).
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
   * 선택된 행을 화면 안으로 끌어온다.
   *
   * 🔴 의존성은 `selectedRnk` 가 아니라 **`selection` 객체 전체**다. `rnk` 만 보면 같은 구역을 다시
   * 고를 때(마커 재클릭·같은 행 재클릭) effect 가 돌지 않아, 그새 사용자가 굴려 놓은 목록이 그대로
   * 남는다. 부모가 선택 이벤트마다 새 객체(`seq` 증가)를 만들어 주므로 매번 다시 판단한다.
   *
   * 중복 호출 걱정은 하지 않아도 된다 — **목적지가 같아 결과가 동일**하고, 이미 보이는 행이면
   * 아래 가시성 검사에서 `scrollTo` 자체를 건너뛴다(멱등).
   */
  useEffect(() => {
    if (!selection) return
    const container = bodyRef.current
    const row = rowRefs.current.get(selection.rnk)
    if (!container || !row) return

    /*
     * 시안의 계산 그대로다. `offsetTop` 은 **가장 가까운 positioned 조상** 기준인데,
     * `.sim-result__body` 에는 `position` 이 없어 행과 컨테이너가 같은 기준(`.sim-result`)을 공유한다
     * — 그래서 두 값을 빼면 컨테이너 안에서의 위치가 나온다. `.sim-result__body` 에 `position:relative`
     * 가 붙으면 이 식이 깨지므로, 그때는 `getBoundingClientRect` 로 바꿔야 한다.
     */
    const top = row.offsetTop - container.offsetTop
    const above = top < container.scrollTop
    const below = top + row.offsetHeight > container.scrollTop + container.clientHeight
    // 이미 보이는 행이면 굳이 움직이지 않는다(시안과 동일).
    if (above || below) container.scrollTo({ top: top - SCROLL_MARGIN, behavior: 'smooth' })
  }, [selection])

  const caption = weightsCaption(weights)

  /*
   * 순위 산정 기준 InfoTip 의 등급 줄(TSK-4242 Step 5). 응답 `gradeCriteria` 를 `maxRankPct` 오름차순으로
   * 세우고 양호(NORMAL)는 뺀다 — 임계값을 프론트에 적지 않는다(D5, `hotspotGrade.buildLegendRows` 와 같은 표기).
   * 맨 아래 등급(= 목록에 나오는 최하위 등급)의 이름으로 "○○ 이상만 목록에 나온다" 를 만든다 — 하한 필터는
   * 백엔드 SQL(`HotspotGrade.MIN_DISPLAYED`)이 걸고 지도 마커도 같은 집합이다.
   */
  const shownGrades = [...gradeCriteria]
    .filter((c) => c.grade !== 'NORMAL')
    .sort((a, b) => a.maxRankPct - b.maxRankPct)
  const lowestGradeNm = shownGrades.length > 0 ? shownGrades[shownGrades.length - 1].gradeNm : null

  return (
    <div className="sim-result">
      <div className="sim-result__top">
        {/* 🔁 뒤로가기(`<`)를 걷었다(TSK-4243) — 닫기(`X`)와 같은 동작이라 중복이었다. */}
        <div style={HEAD_RIGHT}>
          <div className="sim-export" ref={exportRef}>
            {/*
              내보낼 게 없거나(0건) 내보내는 중이면 **메뉴 자체를 열지 않는다.**
              항목마다 비활성 상태를 두는 것보다 진입을 한 곳에서 막는 편이 상태가 적다.
            */}
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
                 * 저장·표 구성·파일명·등급 색은 전부 `lib/analysis/hotspotExport` 가 들고 있다.
                 * 여기서는 어떤 형식으로 내보낼지만 고른다 — PDF·Excel 두 가지다(TSK-4331 — Word 제외).
                 *
                 * 🔴 **두 항목을 함께 잠근다**(D7). 토글 버튼이 이미 `disabled` 라 내보내는 중에는
                 * 메뉴가 열리지도 않지만, 그 판단이 바뀌어도 연타로 보고서가 여러 건 저장되지
                 * 않도록 항목 자체에도 잠금을 둔다.
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
                {/* 엑셀(TSK-4338) — 저장 실패면 폴백 파일 없이 `내보내기 실패`(D7-3). */}
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
        <div className="sim-sec">
          {/*
           * 🆕 TSK-4242 Step 5 — 제목 줄 오른쪽 끝에 순위 산정 기준 InfoTip(생활권·구역추천 패널과 같은 배치).
           * `marginBottom: 0` — `.sim-sec__head` 의 하단 16px 이 붙으면 제목↔부제 간격이 벌어진다(구역추천과 같은 처리).
           * 출처: 응답 `eps`·`minPoints`·`gradeCriteria`·`weights`(부제) · docs/Analysis-guide/hotspot.md §1·§4·§7 ·
           * score-formula.md §1. 숫자는 응답에서만 쓴다(D5). `0~100`·`최대 100` 은 산식의 정의(지표별 캡 — TSK-4196)다.
           */}
          <div className="sim-sec__head" style={{ marginBottom: 0 }}>
            <h3 className="sim-sec__title">불법 주차 집중 구역 순위</h3>
            <InfoTip
              placement="right"
              label="순위 산정 기준 안내"
              title="순위 산정 기준"
              items={[
                `구역은 반경 ${eps}m 내 ${minPoints}개 지점 이상의 민원·단속 위치를 묶은 것입니다.`,
                '종합점수(0~100) = 민원·단속 건수를 각 지표의 최댓값 대비로 환산해 가중합하고, 이번 결과의 1위를 100점으로 맞춘 값입니다.',
                '점수·등급은 조회 조건 안의 상대값이라 지역·기간이 다르면 비교할 수 없습니다.',
                shownGrades.length > 0 &&
                  `등급: 점수 순위 ${describeGradeRanks(shownGrades)} — ${lowestGradeNm} 이상 구역만 표시됩니다.`,
                '가중치는 ‘설정 > 분석 지표 가중치 설정’에서 변경할 수 있습니다(권한 필요).',
              ]}
            />
          </div>
          <p className="sim-sec__sub">
            분석 기간 : {periodLabel}
            {caption && (
              <>
                <span aria-hidden="true" style={SUB_DIVIDER}>
                  |
                </span>
                {caption}
                {weightsAdjusted ? ' (조정됨)' : ''}
              </>
            )}
          </p>
          {exportFailed && (
            <p style={EXPORT_ERROR} role="alert">
              내보내기에 실패했습니다. 잠시 후 다시 시도해 주세요.
            </p>
          )}
          {/*
           * 저장 결과 안내 — 파일은 두 경우 모두 내려갔고, 다른 것은 목록에 남았는지뿐이다(D11).
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

          <div className="rl">
            {zones.map((zone) => (
              <div
                key={zone.rnk}
                className={`rl__row${selectedRnk === zone.rnk ? ' is-active' : ''}`}
                /*
                 * 시안은 클릭만 받는 `<div>` 다(`cursor:pointer` 는 Step 9 에서 `.rl__row` 로 들어갔다).
                 * 마커를 못 쓰는 사용자에게 이 목록이 유일한 선택 수단이라 키보드 경로를 함께 연다 —
                 * 보이는 모양은 그대로다.
                 */
                role="button"
                tabIndex={0}
                aria-current={selectedRnk === zone.rnk ? true : undefined}
                ref={(el) => {
                  // 언마운트되는 행은 지운다 — 남겨 두면 이전 결과의 DOM 을 스크롤 대상으로 잡는다.
                  if (el) rowRefs.current.set(zone.rnk, el)
                  else rowRefs.current.delete(zone.rnk)
                }}
                onClick={() => onSelectZone(zone.rnk)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onSelectZone(zone.rnk)
                  }
                }}
              >
                <span className="rl__rank">{zone.rnk}</span>
                <div className="rl__main">
                  <div className="rl__top">
                    <span className="rl__name">
                      {/* 등급색은 CSS 변수 표기 그대로 넣는다 — DOM `style` 은 `var()` 를 받는다. */}
                      <span className="dot" style={{ background: GRADE_COLOR_VAR[zone.grade] }} />
                      {zone.zoneNm}
                    </span>
                    {/* 투자우선순위 보드와 같은 공용 `.badge`. 라벨은 응답 `gradeNm` 이다(TSK-4321). */}
                    <span className={GRADE_BADGE_CLASS[zone.grade]}>{zone.gradeNm}</span>
                  </div>
                  {/* 건수는 문서 표의 `세부 내역` 칸과 **같은 함수**로 만든다 — 화면과 파일이 갈리지 않게 한다. */}
                  <span className="rl__sub">
                    종합점수 {formatDecimal(zone.snthsScr)}점 ({zoneDetail(zone)})
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
