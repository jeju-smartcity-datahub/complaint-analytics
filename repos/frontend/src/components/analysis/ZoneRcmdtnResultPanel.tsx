import { useEffect, useRef, useState, type CSSProperties } from 'react'
import Icon from '../Icon'
import type { CommonCodeItem } from '../../api/commonCodes'
import type {
  ZoneRcmdtnFileFmt,
  ZoneRcmdtnResult,
  ZoneRcmdtnShareResult,
} from '../../api/zoneRcmdtn'
import {
  ZoneRcmdtnSaveError,
  exportZoneRcmdtnFeeReport,
  exportZoneRcmdtnShareReport,
  type ZoneRcmdtnFeeExportCriteria,
  type ZoneRcmdtnShareExportCriteria,
} from '../../lib/analysis/zoneRcmdtnExport'
import InfoTip from '../common/InfoTip'
import ZoneRcmdtnSharePanel, { type ZoneRcmdtnShareApplied } from './ZoneRcmdtnSharePanel'
import type { ZoneRcmdtnSelection, ZoneRcmdtnShareSelection } from './zoneRcmdtnSelection'
import {
  NOTE_TREND_WINDOW_SHORT,
  RANK_SEC_TITLE,
  emptyLotsText,
  fmtDecimal,
  fmtInt,
  feePrrtBndry,
  feePrrtColor,
  feeRvwGrdColor,
  feeRvwScrDesc,
  isFeeRvwScr,
  lotRowSub,
  lotRowVal,
  noteAltmntRng,
  noteIncrsRtNull,
  noteTrendBfrFallback,
  rankSecSubSgg,
  stripNoteMark,
  type ZoneRcmdtnTab,
} from './zoneRcmdtnView'

/*
 * 구역추천 — 우측 결과 패널 (TSK-4207 Step 10-4).
 *
 * 골격은 **집중구역 `HotspotResultPanel` 그대로**다 —
 * `.sim-result` → `.sim-result__top`(내보내기 + 닫기) + `.sim-result__body > .sim-sec > .rl > .rl__row`.
 * 스타일은 `src/styles/analysis.css` 가 이미 갖고 있다 — 여기서 CSS 클래스를 새로 만들지 않는다.
 *
 * 내보내기 (TSK-4240 Step 8) — `InvestPrrtList` 의 `sim-export` 메뉴(PDF · Word, 바깥 클릭·Esc 로 닫힘)를
 * 그대로 옮겨 왔다. 🔴 **내보내기 = 보고서 저장이다**("내보내기 1회 = 보고서 1건" · TSK-4078). 형식을 고르면
 * 서버가 조건으로 **다시 계산해** 보고서 1건을 저장하고, 파일은 **그 저장 응답으로** 만든다
 * (순서·실패 갈래는 `lib/analysis/zoneRcmdtnExport`). 저장이 실패하면 파일도 만들지 않는다.
 * 🔴 조건은 **조회에 적용된 값**이다 — 페이지가 `feeExportCriteria`·`shareExportCriteria` 로 넘긴다.
 *
 * 🔁 **TSK-4282 — 행 표기가 두 갈래다**(`result.anlsMthd` · 판정은 `isFeeRvwScr` 한 곳 · D11).
 * - **새 방식**(`'RVW_SCR'`): 큰 값 = **우선도**(`lotRowVal` · 단계 칩은 색 점과 중복이라 두지 않는다) ·
 *   보조줄(`lotRowSub`) = **면수 · 민원 N건 · 증가율** · 색 점 = **`rvwGrdCd` 단계색**(`feeRvwGrdColor` —
 *   집중구역 등급 토큰 재사용, 범례·지도 핀과 같은 함수). 🔁 D14 — 단계도 우선도(큰 값)로 정해서
 *   위에서 아래로 최우선 → 후순위로만 내려간다(종전 D2 의 "칩 등급이 어긋나는 행" 은 없어졌다).
 * - **구 방식**(`anlsMthd` 없음 — 이 작업 전 응답·스냅샷): 종전 그대로다. 큰 값 = **배정 민원**(사용자 요청
 *   2026-10-26) · 보조줄 = **면수 · 민원 증가율** · 색 점 = **우선순위**(TSK-4276 · `feePrrtColor` — 배정 민원
 *   등간격 3단계).
 *
 * 🔴 **정렬은 서버 순서 그대로**다(새 방식 = 우선도 내림차순 · 구 방식 = 배정 민원 내림차순).
 * 프론트 재정렬 금지 — 큰 값이 위에서 아래로 줄어드는 것이 정상이다.
 *
 * 적합도(충족률)를 색으로 쓰지 않는 이유는 그대로다 — **읍면동 지표**라 행마다 찍으면 전 행이 같은 색이 되어
 * "주차장별 적합도" 로 오독된다.
 *
 * 🔁 **TSK-4259 R2 — 상단의 지역 요약(지역 분석 결과)을 걷었다**(두 모드 모두). 본문 첫 섹션이 곧
 * 주차장 순위다. 화면에 필요 면수·충족률·적합도·추천유형·E0·배정률이 더 이상 없다.
 * 🔴 요약이 들고 있던 필수 문구는 사라지지 않게 옮겼다 — ③ 배정 범위(`noteAltmntRng`)와 계수 대체
 * 경고(`COEF_FALLBACK`)는 **순위 섹션 하단 각주**다(D9·D10). ④ SHARE 오독 방지 문장은 화면에서 사라졌다.
 * 🔁 **TSK-4340 — 읍면동 판정 자체를 서버·화면·보고서에서 걷었다**(참고용 경고 `feeTabShareWarn` 포함).
 * 모드는 `result.scope === 'SGG'` 로 가른다(없으면 `EMD` — 옛 스냅샷).
 *
 * ⚠️ **분석 내용(`tab`)이 둘이라 본문도 둘이다.** 껍데기(`.sim-result`)를 두 번 쓰지 않으려고 한
 * 컴포넌트에서 갈랐다 — `FEE` 는 주차장 순위, `SHARE` 는 **공유주차 검토 후보 읍면동**이다
 * (🔁 TSK-4208 Step 5 — 종전의 "요금제 부적합 동 나열" 을 `/share` 응답으로 바꿨다. 본문은
 * `ZoneRcmdtnSharePanel` 이 그린다). 그쪽의 번호는 **표시 순서이며 우선순위가 아니다.**
 */

/**
 * 헤더 우측(내보내기 + 닫기) 묶음 — 🔴 **시안 인라인 스타일 그대로이며 `marginLeft: 'auto'` 가 핵심**이다.
 *
 * `.sim-result__top` 은 `justify-content: space-between` 이다. `HotspotResultPanel` 은 왼쪽에
 * 뒤로가기(`<`)가 있어 자식이 **둘**이라 양 끝으로 벌어지지만, 우리는 그 버튼을 걷어 자식이
 * **하나**다 — `space-between` 에 자식이 하나면 `flex-start` 와 같아 **왼쪽에 붙는다.**
 * 그래서 시안처럼 `marginLeft: 'auto'` 로 직접 오른쪽으로 민다(CSS 를 고치지 않는다 —
 * `.sim-result__top` 은 다른 화면이 함께 쓴다).
 */
const HEAD_RIGHT: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  marginLeft: 'auto',
}

/** 내보내기 버튼 치수 — `.btn`(44px)을 헤더 높이에 맞춘 시안 인라인 값(`InvestPrrtList` 의 것과 같다). */
const EXPORT_BTN: CSSProperties = {
  height: 36,
  padding: '0 var(--space-12)',
  fontSize: 'var(--label2-size)',
}

/** `.btn`·`.sim-export__item` 에 `:disabled` 규칙이 없어 비활성 표현만 인라인으로 둔다(같은 선례). */
const EXPORT_BTN_DISABLED: CSSProperties = { ...EXPORT_BTN, opacity: 0.5, cursor: 'not-allowed' }
const EXPORT_ITEM_DISABLED: CSSProperties = { opacity: 0.5, cursor: 'not-allowed' }

/**
 * 내보내기 결과.
 * - `saved`        저장 + 파일 생성 완료
 * - `saveFailed`   저장 실패(네트워크·5xx 등) — 보고서도 파일도 없다
 * - `saveRejected` 저장 실패(400 — 조건 문제. 대상 0건 포함) — 보고서도 파일도 없다
 * - `fileFailed`   저장은 됐고 파일 생성만 실패 — 🔴 다시 누르면 보고서가 한 건 더 생긴다
 */
type ExportNotice = 'saved' | 'saveFailed' | 'saveRejected' | 'fileFailed'

/**
 * 내보내기 결과 문구 — 헤더 아래 한 줄. 색만 다르다(`InvestPrrtList` 와 같은 토큰).
 * `analysis.css` 에 헤더용 슬롯이 없어 자리(여백)는 인라인이다.
 */
const NOTICE_BASE: CSSProperties = {
  margin: '0 var(--space-12)',
  fontSize: 'var(--label2-size)',
  lineHeight: 1.5,
}
const NOTICE_STYLE: Record<ExportNotice, CSSProperties> = {
  saved: { ...NOTICE_BASE, color: 'var(--status-positive)' },
  saveFailed: { ...NOTICE_BASE, color: 'var(--status-negative)' },
  saveRejected: { ...NOTICE_BASE, color: 'var(--status-negative)' },
  // 보고서는 남았고 파일만 못 만든 경우 — 빨강이면 "저장도 실패했다" 로 읽힌다.
  fileFailed: { ...NOTICE_BASE, color: 'var(--status-cautionary)' },
}

/** 문구는 투자우선순위(`InvestPrrtList`)와 같다 — 400 의 확인 대상만 이 화면의 조건(기간·지역)이다. */
const NOTICE_TEXT: Record<ExportNotice, string> = {
  saved: '보고서 목록 > 직접 생성 보고서 에 저장되었습니다.',
  saveFailed: '보고서를 저장하지 못해 내보내지 않았습니다. 잠시 후 다시 시도해 주세요.',
  saveRejected:
    '보고서를 저장하지 못해 내보내지 않았습니다. 분석 기간·지역을 확인하고 다시 실행해 주세요.',
  // 🔴 "다시 시도" 를 권하지 않는다 — 보고서는 이미 목록에 있다.
  fileFailed:
    '보고서는 저장되었지만 파일을 만들지 못했습니다. 보고서 목록에서 다시 내려받을 수 있습니다.',
}

/** 저장 성공 안내를 띄워 두는 시간(ms) — 투자우선순위와 같다. 실패 안내는 다음 내보내기까지 남는다. */
const SAVED_NOTICE_MS = 5000

/** 스크롤로 행을 끌어올릴 때 컨테이너 위쪽에 남기는 여백(px) — `HotspotResultPanel` 과 같다. */
const SCROLL_MARGIN = 8

/**
 * 🆕 TSK-4282 — 새 방식 행의 큰 값. `.rl__cnt`(18px)에 `우선도 925.5` 를 그대로 넣으면
 * 이름을 밀어내 한 치수 줄인다(공유주차제 행 `ROW_VAL` 과 같은 값).
 */
const ROW_VAL: CSSProperties = { fontSize: 'var(--body1-size)' }


/** 결론 문구(후보 0개소) · 안내줄 공통 — 빈 리스트가 아니라 **결론**이라 리스트 자리에 문장을 둔다. */
const CONCLUSION: CSSProperties = {
  margin: 'var(--space-16) 0 0',
  padding: 'var(--space-14) var(--space-12)',
  borderRadius: 'var(--radius-10)',
  background: 'var(--fill-alternative)',
  fontSize: 'var(--body2-size)',
  lineHeight: 1.6,
  color: 'var(--text-neutral)',
}

/** 리스트 아래 두는 안내줄 — 🔁 TSK-4259 이후 계수 대체 경고(`COEF_FALLBACK`)만 쓴다(나머지는 산정 기준 팝업으로 옮겼다). */
const FOOT_NOTE: CSSProperties = {
  margin: 'var(--space-12) 0 0',
  paddingTop: 'var(--space-12)',
  borderTop: '1px solid var(--line-alternative)',
  fontSize: 'var(--label2-size)',
  lineHeight: 1.5,
  color: 'var(--text-alternative)',
}

export interface ZoneRcmdtnResultPanelProps {
  /** 분석 내용(설정 패널의 첫 칸). 🔴 보기 모드이고 읍면동 판정(`rcmdtnTypeCd`)과 다른 층위다. */
  tab: ZoneRcmdtnTab
  /** 상세 결과. `tab === 'FEE'` 일 때만 쓰인다(없으면 순위 섹션을 그리지 않는다). */
  result: ZoneRcmdtnResult | null
  /** `result` 를 만든 실행의 기간 라벨 — 요금제 부제 첫 줄 `분석 기간 : …`. */
  periodLabel: string
  /**
   * 공유주차 검토 후보 응답(`/share`) — `tab === 'SHARE'` 일 때만 쓰인다. 🔴 **`실행` 을 눌러야
   * 생긴다**(호출마다 실행 이력이 남아 자동 조회가 없다). 🔁 그 전에는 부모가 이 패널을 띄우지 않는다.
   */
  share: ZoneRcmdtnShareResult | null
  /** `share` 를 만든 조건(기간·지역 필터). 설정 패널의 지금 입력이 아니다. */
  shareApplied: ZoneRcmdtnShareApplied | null
  /** 공통코드 `SHRN_RVW_GRD_CD` — 🔁 `UNABLE` 라벨에만 쓴다. 못 받았으면 코드값이 보인다. */
  shareGrdCodes: CommonCodeItem[]
  /** 공유주차제 보기의 선택(행 ↔ 깃발). */
  shareSelection: ZoneRcmdtnShareSelection | null
  /** 행 클릭 — 후보면 해당 깃발을 강조·이동시킨다(후보가 아닌 행은 깃발이 없어 행 강조만 된다). */
  onSelectShareZone: (emdCd: string) => void
  /** 현재 선택(리스트 ↔ 핀 양방향). null 이면 선택 없음. */
  selection: ZoneRcmdtnSelection | null
  /** 행 클릭 — 해당 핀을 강조·이동시킨다. 선택 상태의 주인은 `RecommendationPage` 하나다. */
  onSelectLot: (pkltId: number) => void
  /**
   * 요금제 적용의 내보내기(= 보고서 저장) 조건 — 🔴 **조회에 적용된 값**이다(설정 패널의 지금 입력이
   * 아니다). `null` 이면 버튼을 잠근다(결과 없음 · 후보 주차장 0개소 — 서버가 400 을 낼 조건이다).
   */
  feeExportCriteria: ZoneRcmdtnFeeExportCriteria | null
  /** 공유주차제 적용의 내보내기 조건. `null` 이면 버튼을 잠근다(결과 없음 · 검토 후보 0곳). */
  shareExportCriteria: ZoneRcmdtnShareExportCriteria | null
  /** 패널 닫기. 다시 실행하면 열린다. */
  onClose: () => void
}

export default function ZoneRcmdtnResultPanel({
  tab,
  result,
  periodLabel,
  share,
  shareApplied,
  shareGrdCodes,
  shareSelection,
  onSelectShareZone,
  selection,
  onSelectLot,
  feeExportCriteria,
  shareExportCriteria,
  onClose,
}: ZoneRcmdtnResultPanelProps) {
  // ── 내보내기 ──
  // 🔴 이 상태들은 다음 보기로 넘어가지 않는다 — 분석 내용을 바꾸면 부모가 `key` 로 패널을 새로 세운다.
  const [exportOpen, setExportOpen] = useState(false)
  const exportRef = useRef<HTMLDivElement>(null)
  const [exporting, setExporting] = useState(false)
  /**
   * 🔴 중복 실행 잠금. 내보내기 1회 = 보고서 1건이라 연타는 **목록에 중복 행**이 된다.
   * `exporting` state 는 다음 렌더에야 반영돼 같은 틱의 두 번째 클릭을 못 막는다 — ref 로 즉시 잠근다.
   */
  const exportLock = useRef(false)
  const [notice, setNotice] = useState<ExportNotice | null>(null)

  /** 지금 보기의 조건이 없으면(결과 없음 · 대상 0건) 잠근다. */
  const exportReady = tab === 'FEE' ? feeExportCriteria != null : shareExportCriteria != null
  const exportDisabled = !exportReady || exporting

  // 저장 성공 안내만 스스로 걷힌다.
  useEffect(() => {
    if (notice !== 'saved') return
    const timer = window.setTimeout(() => setNotice(null), SAVED_NOTICE_MS)
    return () => window.clearTimeout(timer)
  }, [notice])

  // 잠기면(재실행으로 대상이 사라짐) 열려 있던 메뉴도 닫는다.
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
  const runExport = async (fileFmt: ZoneRcmdtnFileFmt) => {
    setExportOpen(false)
    if (exportLock.current) return
    // 🔴 보기가 조건을 고른다 — 다른 보기의 조건으로 저장하지 않는다.
    const run =
      tab === 'FEE'
        ? feeExportCriteria && (() => exportZoneRcmdtnFeeReport(feeExportCriteria, fileFmt))
        : shareExportCriteria &&
          // 엑셀의 검토 등급 라벨 — 화면이 이미 받은 공통코드를 그대로 넘긴다(요청에는 실리지 않는다).
          (() => exportZoneRcmdtnShareReport({ ...shareExportCriteria, grdCodes: shareGrdCodes }, fileFmt))
    if (!run) return
    exportLock.current = true
    setExporting(true)
    setNotice(null)
    try {
      await run()
      setNotice('saved')
    } catch (e) {
      // 401 은 공용 인터셉터가 로그인으로 보낸다 — 여기서는 따로 가르지 않는다.
      if (e instanceof ZoneRcmdtnSaveError) {
        setNotice(e.status === 400 ? 'saveRejected' : 'saveFailed')
      } else {
        setNotice('fileFailed')
      }
    } finally {
      exportLock.current = false
      setExporting(false)
    }
  }

  /** 스크롤 컨테이너. 시안도 `.sim-result__body` 에 ref 를 건다(`.rl` 이 아니다). */
  const bodyRef = useRef<HTMLDivElement>(null)
  /** `pkltId` → 행 DOM. 순위가 1부터 연속이라는 가정을 두지 않는다. */
  const rowRefs = useRef(new Map<number, HTMLDivElement>())

  /**
   * 핀에서 고른 행을 화면 안으로 끌어온다.
   *
   * 🔴 의존성은 `selection` **객체 전체**다 — `pkltId` 만 보면 같은 핀을 다시 눌렀을 때 effect 가
   * 돌지 않아, 그새 사용자가 굴려 놓은 목록이 그대로 남는다(부모가 선택마다 `seq` 를 올린다).
   */
  useEffect(() => {
    if (!selection) return
    const container = bodyRef.current
    const row = rowRefs.current.get(selection.pkltId)
    if (!container || !row) return

    /*
     * `HotspotResultPanel` 과 같은 계산이다. `offsetTop` 은 가장 가까운 positioned 조상 기준인데
     * `.sim-result__body` 에는 `position` 이 없어 행과 컨테이너가 같은 기준(`.sim-result`)을 공유한다.
     */
    const top = row.offsetTop - container.offsetTop
    const above = top < container.scrollTop
    const below = top + row.offsetHeight > container.scrollTop + container.clientHeight
    if (above || below) container.scrollTo({ top: top - SCROLL_MARGIN, behavior: 'smooth' })
  }, [selection])

  const lots = result?.lots ?? []
  /** 🆕 TSK-4282 — 새 방식(검토지수) 여부. 없으면 구 방식이다(D11). */
  const rvwScr = isFeeRvwScr(result?.anlsMthd)
  /**
   * 우선순위 등간격 경계 — **구 방식 전용**. 🔴 범례·지도 핀도 같은 `lots` 로 같은 함수를 부른다.
   * 새 방식은 색을 `rvwGrdCd` 가 고르므로 계산하지 않는다.
   */
  const prrtBndry = rvwScr ? null : feePrrtBndry(lots)
  /**
   * 🔴 증가율이 빈 행이 하나라도 있으면 그 이유를 리스트 아래에 적는다.
   * 🔁 Step 16 — 증가율 `null` 행이 목록에 **다시 나오므로** 평시에도 `true` 가 흔하다
   * (Step 14-2 의 제외는 철회됐다). 범례의 `판정 불가` 줄도 같은 값을 받는다 — 그려지는 색과
   * 범례·안내가 한 판정을 공유해 어긋날 수 없다.
   */
  const hasNullIncrs = lots.some((l) => l.cvlcptIncrsRt == null)
  const trendWindowShort = result?.warnings.includes('TREND_WINDOW_SHORT') ?? false
  /**
   * 🔁 Step 15 — 증가율의 "이전" 이 **반 분할로 내려앉았다**. 🔴 그때만 보조줄의 `기간 N일` 과
   * 증가율 구간(`trendDayCnt` = N/2)이 어긋나므로 한 줄로 밝힌다(1차면 어긋나지 않아 띄우지 않는다).
   */
  const trendBfrFallback = result?.warnings.includes('TREND_BFR_FALLBACK') ?? false
  /** `COEF_FALLBACK` — 분석변수 행이 없어 코드 상수로 떨어졌다(🔁 TSK-4259 D10 — 요약에서 옮겨 왔다). */
  const coefFallback = result?.warnings.includes('COEF_FALLBACK') ?? false

  return (
    <div className="sim-result">
      <div className="sim-result__top">
        {/*
         * 🔁 **뒤로가기(`<`)를 걷었다**(사용자 요청 2026-10-13). 시안 헤더는 `내보내기` + `닫기` 둘이고
         * `<` 가 없다 — `HotspotResultPanel` 에 있는 그 버튼은 닫기와 **같은 동작**이라 중복이었다.
         */}
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

          <button className="sim-result__x" type="button" aria-label="닫기" onClick={onClose}>
            <Icon name="close" size={22} />
          </button>
        </div>
      </div>

      {notice && (
        <p style={NOTICE_STYLE[notice]} role={notice === 'saved' ? 'status' : 'alert'}>
          {NOTICE_TEXT[notice]}
        </p>
      )}

      <div className="sim-result__body" ref={bodyRef}>
        {tab === 'FEE' && result && (
          <>
            {/*
             * 🔁 TSK-4259 R2 — 지역 요약(`지역 분석 결과`)을 걷어 본문 첫 섹션이 순위다.
             * 요약이 들고 있던 ③ 배정 범위 · 계수 대체 경고는 이 섹션 하단 각주로 옮겼다(D9·D10).
             */}
            <div className="sim-sec">
              {/*
               * 🔁 TSK-4259 (사용자 요청 2026-10-21) — 제목은 **`RANK_SEC_TITLE` 그대로**다.
               * 뒤에 붙던 ` {시군구} 전체`·` (참고용)` 을 걷었다. 🔁 TSK-4340 — 참고용 경고(`feeTabShareWarn`)도
               * 읍면동 판정과 함께 걷었다.
               */}
              {/*
               * 🔁 TSK-4259 (사용자 요청 2026-10-21) — 제목 줄에 **순위 산정 기준 InfoTip** 을 둔다. 배치는
               * 생활권 패널의 `등급 산정 기준` 과 같다(`.sim-sec__head` 오른쪽 끝 · InfoTip(`placement="right"`)). 🔴 아이콘을 제목
               * 바로 옆(왼쪽)으로 붙이면 320px 팝업이 패널 overflow 에 잘린다(`info-tip.css` 의 `.info-tip--right` 계산 주석).
               * 종전 순위 섹션 하단 각주 3종(반 분할 비교 · 증가율 '—' · 배정 범위)을 이 InfoTip 으로 옮겼다 —
               * 조건(언제 뜨는가)은 종전 그대로다. 문장 정본은 `zoneRcmdtnView` 이고 보고서 템플릿도 같은 문장을
               * 쓰므로(D11) 함수는 고치지 않고 화면에서만 앞의 `※` 를 뗀다.
               */}
              <div className="sim-sec__head" style={{ marginBottom: 0 }}>
                <h3 className="sim-sec__title">{RANK_SEC_TITLE}</h3>
                <InfoTip
                  placement="right"
                  label="순위 산정 기준 안내"
                  title="순위 산정 기준"
                  items={[
                    <>
                      후보는 선택 지역의 무료 공영주차장 중 면수{' '}
                      {fmtInt(result.crtrVls.swtcTrgtMinCpcty)}면 이상인 곳입니다.
                    </>,
                    <>
                      주차 민원 1건을 배정 범위({fmtDecimal(result.crtrVls.altmntRngVl)}m) 안에서 가장 가까운
                      후보 주차장 1곳에만 배정합니다(같은 읍면동 안에서만).
                    </>,
                    /*
                     * 🆕 TSK-4282 — 새 방식은 검토지수 산식(가중치·시군구 기준값은 `crtrVls` 에서 · `feeRvwScrDesc`).
                     * 구 방식(구 응답)은 종전 두 줄 그대로다.
                     */
                    ...(rvwScr
                      ? feeRvwScrDesc(result.crtrVls, result.sggNm)
                      : [
                          '순위는 조회 기간 전체의 배정 민원이 많은 순이며, 같으면 면수가 많은 순입니다.',
                          '민원 증가율은 직전 같은 기간과 비교한 참고 지표이며 순위에는 쓰지 않습니다.',
                        ]),
                    lots.length > 0 && trendBfrFallback && stripNoteMark(noteTrendBfrFallback(result.crtrVls.trendDayCnt)),
                    lots.length > 0 &&
                      (trendWindowShort || hasNullIncrs) &&
                      stripNoteMark(
                        trendWindowShort
                          ? NOTE_TREND_WINDOW_SHORT
                          : noteIncrsRtNull(
                              result.crtrVls.minTrendBase,
                              result.crtrVls.trendDayCnt,
                              result.anlsMthd,
                            ),
                      ),
                    stripNoteMark(noteAltmntRng(result.crtrVls.altmntRngVl)),
                  ]}
                />
              </div>
              {/*
               * 🔴 시안의 가중합 부제를 쓰지 않는다(10-0).
               * 🔁 TSK-4259 — 두 모드 모두 `rankSecSubSgg`(후보 개소 + 배정 범위만)다. 화면에 적합도가
               * 없으므로 `rankSecSub` 의 "적합도는 충족률 기준…" 은 근거 없는 문장이 된다 —
               * `rankSecSub` 는 보고서 전용으로 남는다.
               * 🔁 Step 16 — `후보 N개소` 의 N 은 `lots.length` 그대로다(제외가 철회돼 `lots` 가 곧 전 후보).
               */}
              <p className="sim-sec__sub">
                분석 기간 : {periodLabel}
                <br />
                {rankSecSubSgg(lots.length, result.crtrVls, result.anlsMthd)}
              </p>

              {/* ⚠️ 후보 0개소는 빈 리스트가 아니라 **결론**이다. */}
              {lots.length === 0 ? (
                <p style={CONCLUSION}>{emptyLotsText()}</p>
              ) : (
                <div className="rl">
                  {lots.map((lot) => {
                    const active = selection?.pkltId === lot.pkltId
                    return (
                      <div
                        key={lot.pkltId}
                        className={`rl__row${active ? ' is-active' : ''}`}
                        /*
                         * 시안은 클릭만 받는 `<div>` 다(`cursor:pointer` 는 `.rl__row` 가 준다).
                         * 핀을 못 쓰는 사용자에게 이 목록이 유일한 선택 수단이라 키보드 경로를 함께 연다.
                         */
                        role="button"
                        tabIndex={0}
                        aria-current={active ? true : undefined}
                        ref={(el) => {
                          // 언마운트되는 행은 지운다 — 남기면 이전 결과의 DOM 을 스크롤 대상으로 잡는다.
                          if (el) rowRefs.current.set(lot.pkltId, el)
                          else rowRefs.current.delete(lot.pkltId)
                        }}
                        onClick={() => onSelectLot(lot.pkltId)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            onSelectLot(lot.pkltId)
                          }
                        }}
                      >
                        <span className="rl__rank">{lot.rnk}</span>
                        <div className="rl__main">
                          <div className="rl__top">
                            <span className="rl__name">
                              {/*
                               * 🔁 TSK-4282 — 색은 새 방식이면 `rvwGrdCd` 우선도 단계, 구 방식이면 우선순위(TSK-4276)가
                               * 고른다. DOM `style` 은 `var()` 를 받는다.
                               */}
                              <span
                                className="dot"
                                style={{
                                  background: rvwScr
                                    ? feeRvwGrdColor(lot.rvwGrdCd)
                                    : feePrrtColor(lot.asgnCvlcptCnt, prrtBndry),
                                }}
                              />
                              {/*
                               * 🔁 TSK-4259 (사용자 요청 2026-10-21) — 이름 옆 면수를 걷었다(원래 이름 그대로).
                               * ⚠️ 지번형 이름(실측 60.6%)끼리 구분하던 표지가 사라진다. 면수를 붙이는
                               * `lotDisplayNm` 은 보고서 템플릿이 계속 쓰므로 함수는 그대로 둔다(D11).
                               */}
                              {lot.pkltNm}
                            </span>
                            <span className="rl__val">
                              {/*
                               * 🔴 큰 값 = 순위 기준값. 🆕 TSK-4282 새 방식은 **우선도**, 구 방식은 배정 민원
                               * (사용자 요청 2026-10-26). 단계는 색 점·범례가 이미 보여 줘서 칩을 따로 달지 않는다
                               * (중복 — 사용자 요청 2026-10-26).
                               */}
                              <span className="rl__cnt" style={rvwScr ? ROW_VAL : undefined}>
                                {lotRowVal(lot, result.anlsMthd)}
                              </span>
                            </span>
                          </div>
                          {/* 새 방식: 면수 · 민원 N건 · 증가율 / 구 방식: 면수 · 민원 증가율(`null` 은 `—`). */}
                          <span className="rl__sub">
                            {lotRowSub(lot, result.anlsMthd)}
                          </span>
                        </div>
                      </div>
                    )
                  })}
                </div>
              )}

              {/*
               * 🔁 TSK-4259 D10 — `COEF_FALLBACK`(분석변수 행이 없어 코드 상수로 떨어졌다). 평시 경로가 아니라
               * 조용히 넘기면 "어느 계수로 낸 결과인지" 를 알 수 없다(두 모드 공통 · 요약에서 옮겨 왔다).
               */}
              {coefFallback && (
                <p style={FOOT_NOTE} role="status">
                  적용 계수 일부가 기본값으로 대체되었습니다 (COEF_FALLBACK).
                </p>
              )}
            </div>
          </>
        )}

        {/*
         * ── 분석 내용 = 공유주차제 적용 (TSK-4208) ──
         * 스크롤 컨테이너는 이 패널의 것이라 ref 를 넘긴다 — 깃발에서 고른 행을 끌어오는 기준이다.
         */}
        {tab === 'SHARE' && share && (
          <ZoneRcmdtnSharePanel
            share={share}
            applied={shareApplied}
            grdCodes={shareGrdCodes}
            selection={shareSelection}
            onSelectZone={onSelectShareZone}
            containerRef={bodyRef}
          />
        )}
      </div>
    </div>
  )
}
