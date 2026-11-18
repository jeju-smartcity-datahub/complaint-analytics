import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import InfoTip from '../common/InfoTip'
import Icon from '../Icon'
import {
  formatDtl,
  type LifezoneAnalysisResult,
  type LifezoneFileFmt,
  type LifezoneGrade,
  type LifezoneGradeCriterion,
  type LifezoneReportResponse,
} from '../../api/lifezone'
import { exportLifezoneCngstReport } from '../../lib/analysis/lifezoneCngstExport'
import type { VrblVls } from '../../api/analysisWeight'
import { exportLifezoneReport, type LifezoneExportStatus } from '../../lib/analysis/lifezoneExport'
import { formatCngstSupply, GRADE_COLOR_VAR, GRADE_LABEL, isCngstResult } from './lifezoneGrade'

/*
 * 생활권 시뮬레이션 — 우측 결과 패널 (요약 + 순위) (TSK-4088 Step 10).
 *
 * 마크업·클래스는 시안(https://innopamsolution.github.io/jeju-datahub/)의 `.sim-result`
 * (헤더 `.sim-result__top` + 본문 `.sim-result__body > .sim-sec` 2개)를 그대로 옮겼다.
 * 스타일은 `src/styles/analysis.css` 가 이미 갖고 있다(집중구역 TSK-4032 Step 9 + 생활권 Step 6 의
 * `.sum` / `.size-chip`) — 여기서 CSS 를 새로 만들지 않는다.
 *
 * 🔴 **화면 문구를 이 파일에 박지 않는다.** 섹션 제목·부제(`sectionTitle`/`sectionSub`), 단위(`unit`),
 * 등급명(`gradeCriteria[].gradeNm`), 격자 크기 표기(`gridSzNm`)가 전부 응답값이다 —
 * 분석유형이 늘거나 공통코드가 바뀌면 백엔드만 바뀌면 되게 하려는 것이다.
 * 등급명 폴백(`GRADE_LABEL`)만 두는데, 이는 `gradeCriteria` 가 비어 온 방어 경로 전용이다.
 *
 * 🔴 **선택 상태의 주인은 이 패널이 아니라 페이지**다(`LifestyleSimulationPage`).
 * 목록과 지도가 각자 상태를 들면 서로 다른 셀을 가리키므로, 여기서는 받은 선택을 그리고
 * 클릭을 올려 보내기만 하는 **controlled 프레젠테이션 컴포넌트**다 — `LifezoneSettingPanel`·
 * `HotspotResultPanel` 과 같은 형태다.
 *
 * 시안과 의도적으로 다른 부분
 *   - 요약 `.sum` 이 **4행 → 6행**(총 + 등급 5종). 등급이 5단계이기 때문이다(D17).
 *     혼잡도만 `100점 초과 — 수요가 면수 초과` 행이 하나 더 붙는다(설정값 기준 — §8-1 · 2026-10-08 ⑪ 상한 해제).
 *   - 순위 `.rl` 이 **8행 → 심각·경고·주의 전부**(D9 → 🔁 TSK-4227 D21 — 15행 상한 없음). 행 수는 응답 `ranking` 길이를 그대로 따른다.
 *
 * 범위
 *   - ✅ (Step 10) 요약 6행 · 순위 목록 · 셀 ↔ 목록 양방향 연동(선택 하이라이트 + 스크롤)
 *   - ✅ (Step 12) `내보내기` 메뉴가 `lib/analysis/lifezoneExport` 로 저장 → PDF / Word 를 만든다.
 *        🔴 **저장에 실패해도 파일은 내려간다** — 버튼의 약속은 파일이고 저장은 부가효과다.
 */

/**
 * 헤더 우측(내보내기 + 닫기) 묶음 — 시안 인라인 스타일 그대로.
 * 🔁 TSK-4227 — 왼쪽 `<` 를 지워 이 묶음이 상단 바의 유일한 자식이라 `marginLeft: auto` 로 오른쪽에 붙인다.
 */
const HEAD_RIGHT: CSSProperties = { display: 'flex', alignItems: 'center', gap: 4, marginLeft: 'auto' }

/** 내보내기 버튼 — `.btn`(44px) 을 헤더 높이에 맞춰 줄인 시안 인라인 스타일 그대로. */
const EXPORT_BTN: CSSProperties = {
  height: 36,
  padding: '0 var(--space-12)',
  fontSize: 'var(--label2-size)',
}

/**
 * 내보낼 게 없을 때(0건)의 버튼 표현. `.btn` 에는 `:disabled` 규칙이 없어(시안에 대기 상태가
 * 없었다) 최소한의 시각 피드백만 인라인으로 얹는다 — `LifezoneSettingPanel` 의 `RUN_DISABLED`,
 * `HotspotResultPanel` 의 `EXPORT_BTN_DISABLED` 와 같은 이유·같은 방식이다.
 */
const EXPORT_BTN_DISABLED: CSSProperties = { ...EXPORT_BTN, opacity: 0.5, cursor: 'not-allowed' }

/** 내보내는 중인(또는 0건이라 잠긴) 메뉴 항목 표현. `.sim-export__item` 에도 `:disabled` 규칙이 없다. */
const EXPORT_ITEM_DISABLED: CSSProperties = { opacity: 0.5, cursor: 'not-allowed' }

/**
 * 내보내기 실패 문구 슬롯. `analysis.css` 에는 결과 패널용 오류 규칙이 없어(시안에 실패 상태가 없었다)
 * 집중구역 패널(`HotspotResultPanel.EXPORT_ERROR`)과 **같은 토큰**을 인라인으로 쓴다 —
 * `analysis.css` 는 Step 6 산출물이라 여기서 손대지 않는다.
 */
const EXPORT_ERROR: CSSProperties = {
  margin: 'var(--space-8) 0 0',
  color: 'var(--status-negative)',
  fontSize: 'var(--label2-size)',
}

/** 저장 성공 안내 — 위 슬롯과 같은 모양이고 색만 바꾼다. */
const SAVE_NOTICE: CSSProperties = { ...EXPORT_ERROR, color: 'var(--status-positive)' }

/**
 * 저장 실패 안내.
 *
 * `--status-negative`(내보내기 자체 실패)와 **의도적으로 색을 나눈다**: 이 경우 파일은 정상적으로
 * 내려갔고 목록에만 안 남은 것이라, 같은 빨강으로 두면 "다운로드도 실패했다" 로 읽힌다.
 */
const SAVE_FAIL_NOTICE: CSSProperties = { ...EXPORT_ERROR, color: 'var(--status-cautionary)' }

/** 저장 성공 안내를 띄워 두는 시간(ms). "잠깐" 이면 되고, 실패 안내는 지우지 않는다. */
const SAVE_NOTICE_MS = 5000

/**
 * 0건 안내 문구 슬롯. `analysis.css` 에는 결과 패널용 빈 상태 규칙이 없어(시안이 항상 결과가
 * 있는 목데이터였다) 최소한만 인라인으로 둔다 — `analysis.css` 는 Step 6 산출물이라 여기서 손대지 않는다.
 */
const EMPTY_NOTE: CSSProperties = {
  margin: 'var(--space-8) 0 0',
  color: 'var(--text-alternative)',
  fontSize: 'var(--label2-size)',
  lineHeight: 1.6,
}

/**
 * 혼잡도 격자크기 안내(TSK-4090 §7-3) — 오류가 아니라 권고라 주의색이다(설정 패널 `NOTE_WARN` 과 같은 토큰).
 */
const CNGST_GRID_HINT: CSSProperties = {
  margin: 'var(--space-8) 0 0',
  color: 'var(--status-cautionary)',
  fontSize: 'var(--label2-size)',
  lineHeight: 1.6,
}

/**
 * 혼잡 집중 구역 InfoTip 첫 줄(TSK-4227 R22 · D16) — 종전 응답 `sectionSub` 였던 문구를 화면 고유 설명으로 옮겼다.
 * 부제 자체는 여전히 응답 `sectionSub` 가 정본이다(백엔드 `LifezoneTarget.CNGST`).
 */
const CNGST_RANK_HELP = '공영주차 공급 대비 초과주차 추정'

/** 점유율 설정값 표기 — 응답 `occUsedVl` 그대로(소수 2자리). 값이 없으면 `-`. */
function formatOcc(v: number | null | undefined): string {
  return v == null ? '-' : Number(v).toFixed(2)
}

/** 이 크기(m) 이하 격자에서 혼잡도 근거 없음 제외 격자 안내를 띄운다. */
const CNGST_SMALL_GRID_SZ = 250

/** `part / whole` → `38.3%`. 분모가 0 이면 `0%`. 🔴 비율을 화면에 박지 않고 응답 요약값으로 계산한다. */
function pct(part: number, whole: number): string {
  if (whole <= 0) return '0%'
  return `${((part / whole) * 100).toFixed(1)}%`
}

/** 스크롤로 행을 끌어올릴 때 컨테이너 위쪽에 남기는 여백(px) — 집중구역 패널과 같은 값. */
const SCROLL_MARGIN = 8

/**
 * 요약에 세울 등급 순서 — `gradeCriteria` 가 비어 온 경우의 **폴백**이다.
 * 정상 경로에서는 응답의 `gradeCriteria`(서버가 `SEVERE`→`LOW` 로 정렬해 내린다) 순서를 그대로 쓴다.
 * ⚠️ 여기 있는 것은 **등급코드**이지 화면 문구가 아니다 — 라벨은 응답 `gradeNm` 이 정본이다.
 */
const FALLBACK_GRADE_ORDER: LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION', 'NORMAL', 'LOW']

/**
 * 선택된 셀 — **격자코드(`gridCd`)만으로는 부족해 `seq` 를 함께 받는다.**
 *
 * 🔴 사용자가 지도에서 3위 셀을 고른 뒤 목록을 손으로 굴려 그 행을 화면 밖으로 치우고 **같은 셀을
 * 다시** 누르는 경우가 있다. `gridCd` 만 보면 값이 그대로라 스크롤 effect 가 돌지 않아, 선택은
 * 됐는데 행은 여전히 안 보이는 상태가 된다. 그래서 페이지가 **선택 이벤트마다**(출처가 목록이든
 * 지도든) `seq` 를 올려 새 객체를 넘기고, 이 패널은 그 신호로 "다시 보여 달라"를 판단한다.
 * (`HotspotSelection` 이 `rnk` 로 하는 것과 같은 장치 — 생활권은 키가 격자코드다.)
 */
export interface LifezoneSelection {
  gridCd: string
  seq: number
}

export interface LifezoneResultPanelProps {
  /** 이 결과를 만든 실행의 기간 라벨 — 부제 `분석 기간 : …`(시안). 페이지가 실행 시점에 확정해 내려 준다. */
  periodLabel: string
  /**
   * 분석 응답 **전체**. 요약 6행·순위(심각·경고·주의 전부)·섹션 문구·단위·격자 크기 표기가 모두 이 하나에서 나온다.
   *
   * 🔴 페이지의 **현재 입력값이 아니라 응답이 되비춘 "적용된 조건"** 을 쓰기 위해 통째로 받는다 —
   * 분석을 돌린 뒤 설정만 바꿔 놓아도 패널이 여전히 실제 결과를 설명하게 하려는 것이다.
   */
  result: LifezoneAnalysisResult
  /** 현재 선택. 목록 행 클릭과 지도 셀 클릭이 **같은 통로**로 들어온다. 순위 밖 셀이면 `null` 이다. */
  selection: LifezoneSelection | null
  /** 행 클릭. 페이지가 선택 상태를 바꾸고 지도에 이동 + 팝업 열기를 요청한다. */
  onSelectRank: (gridCd: string) => void
  /**
   * 저장 성공 알림(선택). 문서를 내려받기 **전에** 호출된다.
   *
   * ⚠️ 집중구역과 달리 **화면 결과를 스냅샷으로 갈아 끼우지 않는다**(부모도 그러지 않는다).
   * 저장 응답은 `dataBgngYmd`·`dataEndYmd` 를 의도적으로 비우고(원천 보유 구간은 "지금" 의 값이라
   * 스냅샷이 아니다) `availableTargets` 도 현재 구현 상태로 되돌아오므로, 그대로 대입하면
   * 0건 안내와 설정 패널이 잃는 것이 생긴다. 서버는 같은 조건을 결정적으로 재분석하므로
   * 숫자는 화면과 같다.
   */
  onReportSaved?: (report: LifezoneReportResponse) => void
  /** 패널 닫기 — 헤더 오른쪽 닫기(`X`)가 부른다(TSK-4227 로 왼쪽 `<` 버튼 삭제). */
  onClose: () => void
  /**
   * 순위값 포매터(TSK-4090 — 혼잡도 `÷100` 점수). 없으면 정수 그대로(`toLocaleString`)다.
   * 지도 라벨·팝업과 **같은 함수**를 받아 화면 세 곳의 표기가 갈리지 않게 한다.
   */
  formatVl?: (vl: number) => string
  /**
   * 🆕 이 결과를 만든 실행의 **혼잡도** 계수 일시 조정(TSK-4325 D7) — 혼잡도 보고서 저장에 그대로 싣는다.
   * 설정값으로 돌렸거나 민원 건수 결과면 `null`(민원 건수 API 는 받지 않는다 — D12).
   * 🔴 **필수 prop 이다** — 선택이면 빠뜨려도 TS 가 못 잡아 보고서만 설정값으로 계산된다.
   */
  vrblVls: VrblVls | null
}

export default function LifezoneResultPanel({
  result,
  periodLabel,
  selection,
  onSelectRank,
  onReportSaved,
  onClose,
  formatVl,
  vrblVls,
}: LifezoneResultPanelProps) {
  /** 하이라이트용 격자코드. 스크롤은 `seq` 까지 보는 아래 effect 가 따로 판단한다. */
  const selectedGridCd = selection?.gridCd ?? null
  /** 스크롤 컨테이너. 시안도 `.sim-result__body` 에 ref 를 건다(`.rl` 이 아니다). */
  const bodyRef = useRef<HTMLDivElement>(null)
  /** 격자코드 → 행 DOM. 순위가 1부터 연속이라는 가정을 두지 않으려 코드로 잡는다. */
  const rowRefs = useRef(new Map<string, HTMLDivElement>())

  // ── 내보내기 (Step 12) ──
  const [exportOpen, setExportOpen] = useState(false)
  const exportRef = useRef<HTMLDivElement>(null)
  /** 저장·문서 생성이 도는 동안 버튼과 두 메뉴 항목을 잠근다 — 연타로 보고서가 여러 건 남는 것을 막는다. */
  const [exporting, setExporting] = useState(false)
  /** 캡처·변환 실패(= 파일이 아예 안 받아지는 경우). 저장 실패와는 다른 상태다. */
  const [exportFailed, setExportFailed] = useState(false)
  /** 저장 결과 안내. 파일은 두 경우 모두 내려갔고 다른 것은 목록에 남았는지뿐이다. */
  const [saveNotice, setSaveNotice] = useState<LifezoneExportStatus | null>(null)

  // 저장 성공 안내만 스스로 걷힌다. 화면을 떠나거나 다시 내보내면 타이머도 함께 정리된다.
  useEffect(() => {
    if (saveNotice !== 'saved') return
    const timer = window.setTimeout(() => setSaveNotice(null), SAVE_NOTICE_MS)
    return () => window.clearTimeout(timer)
  }, [saveNotice])

  /**
   * 바깥 클릭 · `Escape` 로 닫는다 — 집중구역 패널과 같은 구현이다.
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
   * 🔴 의존성은 `selectedGridCd` 가 아니라 **`selection` 객체 전체**다. 코드만 보면 같은 셀을 다시
   * 고를 때(지도 재클릭·같은 행 재클릭) effect 가 돌지 않아, 그새 사용자가 굴려 놓은 목록이 그대로
   * 남는다. 페이지가 선택 이벤트마다 새 객체(`seq` 증가)를 만들어 주므로 매번 다시 판단한다.
   *
   * 🔴 **순위 밖 셀**(보통·낮음 — 🔁 TSK-4227 D21 이후 순위는 심각·경고·주의 전부)은 페이지가 선택을
   * `null` 로 내려 주므로 여기서 걸러진다. 설령 코드만 오더라도 `rowRefs` 에 대응 행이 없어 조용히
   * 아무 일도 하지 않는다 — 셀 대부분이 하위 등급이라(등간격 쏠림) **이 경로가 기본값에 가깝다.**
   */
  useEffect(() => {
    if (!selection) return
    const container = bodyRef.current
    const row = rowRefs.current.get(selection.gridCd)
    if (!container || !row) return

    /*
     * 집중구역 패널과 같은 계산이다. `offsetTop` 은 **가장 가까운 positioned 조상** 기준인데,
     * `.sim-result__body` 에는 `position` 이 없어 행과 컨테이너가 같은 기준(`.sim-result`)을 공유한다
     * — 그래서 두 값을 빼면 컨테이너 안에서의 위치가 나온다. `.sim-result__body` 에 `position:relative`
     * 가 붙으면 이 식이 깨지므로, 그때는 `getBoundingClientRect` 로 바꿔야 한다.
     */
    const top = row.offsetTop - container.offsetTop
    const above = top < container.scrollTop
    const below = top + row.offsetHeight > container.scrollTop + container.clientHeight
    // 이미 보이는 행이면 굳이 움직이지 않는다(멱등 — 지도에서 같은 셀을 다시 눌러도 화면이 튀지 않는다).
    if (above || below) container.scrollTo({ top: top - SCROLL_MARGIN, behavior: 'smooth' })
  }, [selection])

  /**
   * 요약 `.sum` 의 등급 5행. **순서·라벨 모두 응답(`gradeCriteria`)이 정본**이고,
   * 건수는 응답 루트의 등급별 카운트에서 코드로 찾아 붙인다.
   * `gradeCriteria` 가 비어 오면 등급코드 순서 폴백 + `GRADE_LABEL` 로 떨어진다(문구를 추측하지 않는다).
   */
  const gradeRows = useMemo(() => {
    const cntOf: Record<LifezoneGrade, number> = {
      SEVERE: result.severeCnt,
      WARN: result.warnCnt,
      CAUTION: result.cautionCnt,
      NORMAL: result.normalCnt,
      LOW: result.lowCnt,
    }
    const criteria: Pick<LifezoneGradeCriterion, 'grade' | 'gradeNm'>[] =
      result.gradeCriteria.length > 0
        ? result.gradeCriteria
        : FALLBACK_GRADE_ORDER.map((grade) => ({ grade, gradeNm: GRADE_LABEL[grade] }))
    return criteria.map((c) => ({
      grade: c.grade,
      gradeNm: c.gradeNm || GRADE_LABEL[c.grade],
      cnt: cntOf[c.grade],
    }))
  }, [result])

  /**
   * 0건 = 조건에 맞는 격자가 없는 **정상 상태**다(오류가 아니다).
   * 이때는 순위 목록 대신 안내 문구를 세우고 **원천 보유 구간**을 곁들인다 — 기간을 잘못 고른 것이
   * 대부분인데, 화면 어디에도 "언제 데이터가 있는지"가 없으면 사용자가 알 방법이 없다.
   */
  const cngst = isCngstResult(result) ? result : null
  /** 혼잡도 순위 행의 공급·초과주차 줄(TSK-4333) — 순위 행에는 셀 값이 없어 `gridCd` 로 셀을 찾는다. */
  const cngstCellByGrid = useMemo(
    () => (cngst ? new Map(cngst.cells.map((c) => [c.gridCd, c])) : null),
    [cngst],
  )
  /**
   * "대상 격자 없음" 은 유형 구분 없이 **`cells` 기준**으로 판정한다(TSK-4227 R5 — 종전 민원건수는 `ranking` 기준).
   * 순위가 심각·경고·주의로 한정되고(D5) 등간격은 하위 등급으로 쏠리므로, **셀은 있는데 순위가 비는 것이
   * 정상 경로**가 됐다 — 그때가 `rankEmpty` 다(TSK-4227 정상 경로 · 방어용이 아니다).
   */
  const isEmpty = result.cells.length === 0
  const rankEmpty = !isEmpty && result.ranking.length === 0

  /**
   * 🔴 0건이면 내보내기를 막는다 — 서버도 0건이면 저장을 400 으로 거절하고(열 수 없는 빈 보고서를
   * 목록에 남기지 않는다), 머리글만 있는 빈 표 파일이 내려가면 사용자는 그것을 성공으로 오해한다.
   * `exporting` 중에도 잠가 **내보내기 1회 = 보고서 1건**을 지킨다.
   */
  const exportDisabled = isEmpty || exporting

  /**
   * 메뉴 항목 공통 동작 — **메뉴를 먼저 닫고** 저장 → 문서 → 다운로드를 한 번에 돌린다.
   *
   * 저장 성공/실패의 갈래와 파일명 규칙은 전부 `exportLifezoneReport` 안에 있다 — 여기서는
   * **버튼을 잠그고 결과를 문구로 바꾸는 일**만 한다.
   * 캡처·변환 실패(= 파일이 아예 안 받아지는 경우)는 서버 오류가 아니라 브라우저 쪽 문제라
   * 상태코드로 갈릴 게 없어 단일 문구다(`ReportsPage.DownloadCell` 과 같은 처리).
   */
  const runExport = async (fileFmt: LifezoneFileFmt) => {
    setExportOpen(false)
    if (exportDisabled) return
    setExporting(true)
    setExportFailed(false)
    setSaveNotice(null)
    try {
      // 🔴 조건은 설정 패널의 현재 입력값이 아니라 **응답이 되비춘 "적용된 조건"** 이다 —
      //    `result` 를 통째로 넘기면 그 규칙이 저절로 지켜진다.
      // 🔴 혼잡도는 저장 경로·문서가 갈린다(TSK-4317). `onReportSaved` 는 민원건수 응답 전용이라 넘기지 않는다.
      const status = cngst
        ? await exportLifezoneCngstReport({ result: cngst, fileFmt, vrblVls })
        : await exportLifezoneReport({ result, fileFmt, onSaved: onReportSaved })
      setSaveNotice(status)
    } catch {
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
            {/*
             * 0건이면 토글 자체를 막는다 — 머리글만 있는 빈 문서를 내보낼 이유가 없고 서버도 400 이다.
             * 내보내는 동안에도 잠가 연타로 보고서가 여러 건 남는 것을 막는다.
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
                 * 저장·표 구성·파일명·등급 색은 전부 `lib/analysis/lifezoneExport` 와
                 * `lib/analysis/lifezoneReportTemplate` 이 들고 있다 — 여기서는 형식만 고른다.
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
                {/* 엑셀(TSK-4340) — 혼잡도는 저장 실패·`wbk` 부재면 폴백 파일 없이 `내보내기 실패`(D7-2). 민원건수는 PDF 와 같은 폴백. */}
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
          {/*
           * 시안 구조(TSK-4227) — 제목 옆 칩(`분석 결과 요약 [500M]`), 그 아래 부제.
           * 부제는 민원건수 전용이다 — 혼잡도는 등급이 혼잡 1.0 기준(고정 경계)이라 조회 조건 기준 문구가 틀려 내렸다(R20).
           * 🔁 TSK-4258 D18 — 민원건수 등급은 순위가 아니라 **등간격**(`EQUAL_INTERVAL` · TSK-4227)이라 부제도 그렇게 적는다.
           * InfoTip 은 시안 위치 — `.sim-sec__head` 의 두 번째 자식(제목 줄 오른쪽 끝) · InfoTip(`placement="right"`)(R26 D20).
           */}
          <div className="sim-sec__head">
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <h3 className="sim-sec__title" style={{ margin: 0 }}>분석 결과 요약</h3>
                {/*
                 * 🔴 응답 `gridSzNm`(`500m`)을 **그대로** 넣는다. 시안 표기 `500M` 은 문자열이 아니라
                 * `.size-chip` 의 `text-transform: uppercase` 가 만든다 — 프론트가 대문자 문자열을 만들지 않는다.
                 */}
                <span className="size-chip">{result.gridSzNm}</span>
              </div>
              <p className="sim-sec__sub" style={{ margin: '4px 0 0' }}>분석 기간 : {periodLabel}</p>
            </div>
            {/*
             * 🔁 등급 산정 기준 InfoTip — 종전 부제(`격자 기반 분석(등급은 … 등간격입니다.)`)를 수급 패널과 같은 InfoTip 으로 옮겼다
             * (2026-10-21 사용자 요청). 방법명은 응답 `clsfMthdNm`, 구간은 응답 `gradeCriteria[].lgndTxt` 그대로 —
             * 경계 숫자·분류방법을 프론트에 다시 적지 않는다.
             */}
            {!cngst && (
              <InfoTip
                placement="right"
                label="등급 산정 기준 안내"
                title="등급 산정 기준"
                items={[
                  <>등급은 격자별 주차 민원 건수 범위를 5등분한 {result.clsfMthdNm}입니다.</>,
                  result.gradeCriteria.length > 0 && result.gradeCriteria.map((c) => c.lgndTxt).join(' · '),
                  '등급 경계는 조회 조건(기간·지역·격자 크기) 안에서 다시 계산됩니다.',
                  '민원이 없는 격자는 지도에 표시하지 않습니다.',
                ]}
              />
            )}
            {/*
             * 🔴 혼잡도 전용 안내 4줄(§8-2 → R21 D14 A안 팝업). 마지막 줄을 빼지 않는다 — "혼잡도" 라벨을 보고
             * 도로 혼잡을 기대한 사용자가 주차 혼잡을 그것으로 읽는 것을 막는다. hover·focus 로 열린다(D15).
             */}
            {cngst && (
              <InfoTip
                placement="right"
                label="혼잡 점수 안내"
                title="혼잡 점수 안내"
                items={[
                  <>
                    혼잡 점수는 <b>공영주차 공급 대비 추정 주차 수요</b>(혼잡 지수)를 100배 한 값입니다(지수 × 100)
                    — <b>100점 초과</b>는 수요가 면수를 넘는 곳입니다.
                  </>,
                  <>
                    점유율 실측값이 없어 설정값({formatOcc(cngst.occUsedVl)})을 사용하므로{' '}
                    <b>격자 간 상대 비교</b>에만 쓰십시오.
                  </>,
                  // TSK-4227 — 종전 "등급은 선택 지역 내 순위" 는 혼잡 1.0 기준(고정 경계)과 맞지 않아 바꿨다.
                  '등급은 혼잡 지수 1.0(100점)을 기준으로 한 고정 경계입니다.',
                ]}
              />
            )}
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
            {/*
             * 요약 행은 시안대로 총 분석 격자 + 5등급이다(2026-10-08). 혼잡도도 근거 없는 격자를 셀·요약에서
             * 모두 빼므로 총 분석 격자 = 5등급 합이다(R10). 제외된 칸 수는 아래 격자 힌트가 알린다.
             */}
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
            {/*
             * 🔴 `100점 초과 — 수요가 면수 초과`(= 지수 1.0 초과)는 데이터가 아니라 **설정값(occ)의 함수**라 기준을 반드시 붙인다(§8-1) —
             * occ 를 1.00 으로 두면 등급 대상 전부가 1.0 을 넘는다.
             */}
            {cngst && (
              <div className="sum__row">
                <span>100점 초과 — 수요가 면수 초과 (점유율 설정값 {formatOcc(cngst.occUsedVl)} 기준)</span>
                <b>{cngst.over1GridCnt.toLocaleString()}개</b>
              </div>
            )}
          </div>

          {/*
           * 혼잡도 격자크기 안내(TSK-4090 §7-3). 작은 격자일수록 불법주차 근거가 없어 제외되는 칸이 많다.
           * 🔴 비율은 응답 요약값으로 계산한다(분모 = 공급>0 격자 = tot + noEnf + ref) — `CVLCPT_WGVL`·기간에 따라
           * 바뀌므로 화면에 박지 않는다. 요약은 조회 뒤에만 있다.
           */}
          {isCngstResult(result) && result.gridSz <= CNGST_SMALL_GRID_SZ && (
            <p style={CNGST_GRID_HINT} role="note">
              {result.gridSz}m 격자에서는 불법주차 근거(단속·민원)가 없는{' '}
              {result.noEnfGridCnt.toLocaleString()}칸이 분석에서 제외됐습니다(
              {pct(result.noEnfGridCnt, result.totGridCnt + result.noEnfGridCnt + result.refGridCnt)}). 500m 를 권장합니다.
            </p>
          )}
        </div>

        {/* ── ② {sectionTitle} — 순위 ── */}
        <div className="sim-sec">
          {/* 🔴 제목·부제는 분석유형마다 다르다. 응답값을 그대로 쓰고 프론트에 문구를 박지 않는다. */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <h3 className="sim-sec__title" style={{ margin: 0 }}>{result.sectionTitle}</h3>
            {/*
             * 혼잡 집중 구역 InfoTip(R22). ② 면수 하한 안내(Step 5 → §8 → 2026-10-08 분석 제외로 변경)는
             * 하한 값 자체가 응답에 없어 문구에 넣지 않는다 — 응답의 칸 수만 쓴다.
             */}
            {cngst && (
              <InfoTip
                placement="right"
                label="혼잡 집중 구역 안내"
                title={result.sectionTitle}
                style={{ marginLeft: 'auto' }}
                items={[
                  CNGST_RANK_HELP,
                  cngst.refGridCnt > 0 && (
                    <>
                      면수 하한 미만 {cngst.refGridCnt.toLocaleString()}칸은 분석에서 제외했습니다. 하한을 올리면
                      소규모 주차장 격자가 결과에서 사라집니다.
                    </>
                  ),
                ]}
              />
            )}
          </div>
          <p className="sim-sec__sub">{result.sectionSub}</p>

          {rankEmpty ? (
            <p style={EMPTY_NOTE} role="status">
              심각·경고·주의 등급에 해당하는 격자가 없습니다.
            </p>
          ) : isEmpty ? (
            <p style={EMPTY_NOTE} role="status">
              해당 조건에 분석 대상 격자가 없습니다.
              <br />
              {/* 0건의 대부분은 기간을 잘못 고른 경우다 — 원천이 실제로 갖고 있는 구간을 알려 준다. */}
              원천 보유 구간: {formatYmd(result.dataBgngYmd)} ~ {formatYmd(result.dataEndYmd)}
            </p>
          ) : (
            <div className="rl">
              {result.ranking.map((rank) => (
                <div
                  key={rank.gridCd}
                  className={`rl__row${selectedGridCd === rank.gridCd ? ' is-active' : ''}`}
                  /*
                   * 시안은 클릭만 받는 `<div>` 다(`cursor:pointer` 는 `analysis.css` 의 `.rl__row`).
                   * 지도를 못 쓰는 사용자에게 이 목록이 유일한 선택 수단이라 키보드 경로를 함께 연다 —
                   * 보이는 모양은 그대로다.
                   */
                  role="button"
                  tabIndex={0}
                  aria-current={selectedGridCd === rank.gridCd ? true : undefined}
                  ref={(el) => {
                    // 언마운트되는 행은 지운다 — 남겨 두면 이전 결과의 DOM 을 스크롤 대상으로 잡는다.
                    if (el) rowRefs.current.set(rank.gridCd, el)
                    else rowRefs.current.delete(rank.gridCd)
                  }}
                  onClick={() => onSelectRank(rank.gridCd)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      onSelectRank(rank.gridCd)
                    }
                  }}
                >
                  <span className="rl__rank">{rank.rnk}</span>
                  <div className="rl__main">
                    <div className="rl__top">
                      <span className="rl__name">
                        <span className="dot" style={{ background: GRADE_COLOR_VAR[rank.grade] }} />
                        {rank.cntrPoiNm}
                      </span>
                      <span className="rl__val">
                        {/* 🔴 단위는 응답 `unit`(`건`/`%`/`점`)이다 — 화면에 박지 않는다. */}
                        <span className="rl__cnt">
                          {formatVl ? formatVl(rank.anlsVl) : rank.anlsVl.toLocaleString()}
                          {result.unit}
                        </span>
                      </span>
                    </div>
                    {/*
                     * 유형별 내역은 `formatDtl` 이 잇는다 — 지도 팝업(Step 9)·문서(Step 12)와 **같은 함수**를
                     * 써서 화면·팝업·파일이 서로 다른 문구를 갖지 않게 한다. 내역이 없으면 빈 문자열이다.
                     */}
                    {/* 혼잡도는 지도 팝업과 같은 공급·초과주차 줄이다(TSK-4333 — 종전 `피크시간 통행 데이터 없음`). */}
                    <span className="rl__sub">
                      {cngstCellByGrid
                        ? (() => {
                            const cell = cngstCellByGrid.get(rank.gridCd)
                            return cell ? formatCngstSupply(cell) : ''
                          })()
                        : formatDtl(rank.dtl)}
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

/**
 * `YYYYMMDD` → `YYYY-MM-DD`. 0건 안내의 보유 구간에만 쓴다.
 * 원천이 통째로 비면 `null` 이 오므로(`dataBgngYmd`/`dataEndYmd`) 그때는 `-` 로 둔다 —
 * 그 상태를 "구간이 없다"가 아니라 빈칸으로 두면 화면이 깨진 것처럼 보인다.
 */
function formatYmd(ymd: string | null): string {
  if (!ymd || ymd.length !== 8) return '-'
  return `${ymd.slice(0, 4)}-${ymd.slice(4, 6)}-${ymd.slice(6, 8)}`
}
