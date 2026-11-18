import { useMemo, useState, type CSSProperties } from 'react'
import Icon from '../Icon'
import type { ZoneRcmdtnZone } from '../../api/zoneRcmdtn'
import { MAX_RANGE_DAYS, RANGE_ERROR_TEXT, toYmd, type RangeError } from '../../lib/dateRange'
import { openDatePicker } from '../../lib/datePicker'
import { ZONE_RCMDTN_TABS, type ZoneRcmdtnPeriodKey, type ZoneRcmdtnTab } from './zoneRcmdtnView'
import SrLabel from '../common/SrLabel'
import CoefficientPopover, { CoefficientBanner } from './CoefficientPopover'
import type { CoeffScope } from '../../lib/analysis/coefficientMeta'
import type {
  AnalysisCoefficient,
  CoeffRequest,
} from '../../lib/analysis/useAnalysisCoefficient'

/*
 * 구역추천 — 좌상단 설정 패널 (시안 레이아웃으로 정렬).
 *
 * 마크업·클래스는 시안의 `.card.sim-panel.sim-panel--left` 를 그대로 옮겼고(집중구역
 * `HotspotSettingPanel` 과 같은 조립) 스타일은 `src/styles/analysis.css` 가 이미 갖고 있다 —
 * 여기서 CSS 를 새로 만들지 않는다.
 *
 * 칸은 셋 + 실행 버튼이다(유형 칸 제거).
 *   ① 분석 내용 — 🔁 **상단 탭 2개가 이 칸으로 들어왔다**. 시안도 셀렉트다.
 *   ② 분석 지역 — 🔁 **시군구 select → 읍면동 select 2단**(시안 `.field__region-row`).
 *      `공유주차제 적용` 보기는 **시군구 한 칸**이다(아래 문단)
 *   ③ 분석 기간 — `grid-seg` 4종(`직접설정` 포함)
 *   ④ 실행 — `.btn-run`
 *
 * 🔁 **유료화 대상 유형 칸은 걷었다**(기획 결정 2026-10-12 — `WIDE` 고정).
 * 상수는 `zoneRcmdtnView.DEFAULT_TRGT_TYPE_SE` 한 곳이고 그 주석이 되돌릴 때의 대가를 적어 뒀다.
 *
 * 🔴 **기간 칸이 생겨 실행 버튼이 필요해졌다.** 종전에는 *"읍면동 select 이 조회의 방아쇠"* 였지만
 * 칸이 여러 개면 조회 시점을 묶어야 한다 — `HotspotSettingPanel` 이 같은 이유로 `.btn-run` 을 쓴다.
 * (호출 1회 = `TB_ANLS_EXCN` 1행이라 방아쇠가 여러 개면 이력도 그만큼 늘어난다.)
 *
 * ⚠️ **판정 불가 동도 목록에 남긴다**(§0-3). 빼면 사용자가 *"왜 안 보이지?"* 로 헤맨다.
 * 🔴 **옵션 라벨에 판정 결과(`판정 불가`·`요금제 적용 30.0%`)를 붙이지 않는다** — 읍면동 이름만 적는다.
 * 셀렉트는 **입력**이고 판정은 **결과**다. 라벨에 섞으면 ① 긴 꼬리가 좁은 패널에서 잘리고
 * ② 실행하기 전에 이미 결론이 나온 것처럼 보인다.
 * 판정(적합도 · 추천유형 · 판정 불가 사유)은 **화면 어디에도 띄우지 않는다**(결과 패널의
 * 지역 요약을 걷었다). 판정은 응답과 보고서에만 남는다.
 *
 * **`공유주차제 적용` 보기에서는 지역이 입력이 아니라 필터다.** 시군구를
 * 고르지 않아도 실행되고(도 전체), 고르면 결과 목록만 그 시군구로 줄어든다. 빈 옵션 라벨은
 * `제주도 전체`·`읍면동 전체` 다.
 * **(사용자 요청 2026-10-26) — 요금제 보기의 시군구 칸에서 `제주도 전체` 를 뺐다.** 그 보기에서
 * 빈 값은 "도 전체" 가 아니라 "아직 안 골랐다"(실행 잠김)라 라벨이 거짓이었다. 페이지가 첫 시군구
 * (제주시)를 미리 고른다. 목록을 못 받았을 때만 빈 옵션(`불러오는 중…`/`시군구 선택`)이 남는다 —
 * 빈 select 이 되지 않게. 공유주차제 보기의 `제주도 전체` 는 그대로다(그쪽 빈 값은 진짜 도 전체다).
 * **요금제 보기는 시군구만 골라도 실행된다.** `읍면동 전체` 는 이제 "그 시군구 전체로
 * 실행" 이다(라벨·값 `''` 는 그대로). 🔴 그래도 **시군구는 필수**다(시군구를 가로지르는 순위를
 * 만들지 않는다). 읍면동을 고르면 그 동만, 비우면 시군구 안 모든 동의 주차장 순위다.
 * 🔁 **공유주차제 보기의 읍면동 칸을 걷었다**(2026-10-13) — 결과가 읍면동 **목록**이라 읍면동 하나로
 * 좁히면 1행짜리 목록이 된다. 🔴 비활성으로 두는 것이 아니라 **렌더하지 않는다** — 그 보기에서는
 * 시군구 select 이 `.field__region-row`(2열 격자) 없이 칸 너비를 다 쓴다(`분석 내용` 칸과 같은 조립).
 * 요금제 보기의 2단은 그대로다.
 * ⚠️ 유료화 대상 유형 칸은 이미 걷어 화면에 없다 — 이 보기에서 따로 숨길 것이 없다
 * (`/share` 는 그 파라미터를 받지 않는다).
 *
 * 완전한 controlled 프레젠테이션 컴포넌트다 — 조회·상태는 `RecommendationPage` 하나가 들고 있다.
 *
 * 🆕 **분석 계수 조정** — 제목 줄 오른쪽 버튼 + 그 아래 배너(집중구역 패널과 같은 조립).
 * 🔴 팝오버는 **현재 보기의 계수만** 그린다(세그먼트 없음) — 요금제 `RECOMMEND_FEE` 10개(판정 계수 3개 삭제) /
 * 공유주차제 `RECOMMEND_SHARE` 4개. 보기를 바꾸면 페이지가 계수도 기본값으로 되돌린다(`changeTab`).
 * 상태는 페이지의 `useAnalysisCoefficient('RECOMMEND')` 가 들고, 여기는 팝오버 열림(z-index)만 둔다.
 */

/** 기간 세그먼트. 라벨은 시안 그대로 붙여 쓴다(4열 격자라 공백을 넣으면 줄이 넘친다). */
const PERIODS: { key: ZoneRcmdtnPeriodKey; label: string }[] = [
  { key: '1', label: '최근1개월' },
  { key: '3', label: '최근3개월' },
  { key: '6', label: '최근6개월' },
  { key: 'custom', label: '직접설정' },
]

const NOTE: CSSProperties = {
  margin: 0,
  fontWeight: 'var(--weight-regular)',
  color: 'var(--text-alternative)',
}
const NOTE_ERROR: CSSProperties = { ...NOTE, color: 'var(--status-negative)' }
const RETRY_BTN: CSSProperties = {
  background: 'none',
  border: 'none',
  padding: 0,
  color: 'var(--primary)',
  cursor: 'pointer',
  textDecoration: 'underline',
  font: 'inherit',
}
/** `.btn-run` 에는 `:disabled` 규칙이 없어(시안에 대기 상태가 없었다) 최소한의 시각 피드백만 얹는다. */
const RUN_DISABLED: CSSProperties = { opacity: 0.6, cursor: 'not-allowed' }

/* ── 분석 계수 조정 — 집중구역 `HotspotSettingPanel` 과 같은 자리·여백 ── */
const TITLE_ROW: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
}
/** 배너 자리 — 패널 `gap` 을 당겨 제목 줄에 붙인다. */
const COEFF_BANNER: CSSProperties = { margin: '-12px 0 0' }

/**
 * `시작일 + (MAX_RANGE_DAYS - 1)일` = 선택 가능한 마지막 종료일 (집중구역 화면과 같은 규칙).
 * ⚠️ 백엔드 상한은 한 창당 36개월이지만, 공용 규칙(`lib/dateRange`)이 더 좁은 1년이라 그쪽을 쓴다 —
 * 화면마다 다른 상한을 두지 않는다.
 */
function maxTo(from: string): string | undefined {
  if (!from) return undefined
  const [y, m, d] = from.split('-').map(Number)
  const last = new Date(y, m - 1, d)
  last.setDate(last.getDate() + (MAX_RANGE_DAYS - 1))
  return toYmd(last)
}

export interface ZoneRcmdtnSettingPanelProps {
  /** 분석 내용(요금제 적용 / 공유주차제 적용). 🔴 보기 모드이고 읍면동 판정과 다른 층위다. */
  tab: ZoneRcmdtnTab
  /**
   * 🔴 **값이 바뀌면 페이지가 화면을 첫 진입 상태로 되돌린다**(지역·기간·결과·지도) — 두 보기는
   * 입력·결과 단위가 달라 조건을 넘기지 않는다. 이 패널은 controlled 라 따로 지울 것이 없다.
   */
  onTabChange: (next: ZoneRcmdtnTab) => void
  /** 읍면동 참고 목록(`GET /zone-recommendation/zones`). 아직 못 받았으면 `[]`. */
  zones: ZoneRcmdtnZone[]
  /** 목록 조회 중 — select 을 비활성으로 둔다(빈 목록을 "지역이 없다" 로 읽지 않게). */
  zonesLoading: boolean
  /** 목록 조회 실패 문구. null 이면 정상. */
  zonesError: string | null
  onRetryZones: () => void
  /**
   * 선택된 시군구명. `''` = 아직 고르지 않음(진입 직후) — 읍면동 select 이 잠긴다.
   * 요금제 보기에서도 이 값이 **입력**이다(필수). 읍면동이 비면 `sggNm` 이 요청
   * 파라미터로 나가고(시군구 모드), 읍면동을 고르면 `emdCd` 만 나간다(배타 규칙은 `fetchZoneRcmdtn`).
   * 공유주차제 보기에서는 `''` 가 "전체" 이고 고른 값이 필터로 나간다.
   */
  sggNm: string
  onSggChange: (next: string) => void
  /** 선택된 읍면동코드. `''` = 아직 고르지 않음. 🔴 **요금제 보기 전용**이다(공유주차제 보기는 칸이 없다). */
  emdCd: string
  onEmdChange: (next: string) => void
  /** 선택된 기간 세그먼트. */
  period: ZoneRcmdtnPeriodKey
  onPeriodChange: (next: ZoneRcmdtnPeriodKey) => void
  /** 직접설정 시작일 (yyyy-MM-dd). `period !== 'custom'` 이면 입력이 숨겨진다. */
  customFrom: string
  /** 직접설정 종료일 (yyyy-MM-dd). */
  customTo: string
  onCustomFromChange: (next: string) => void
  onCustomToChange: (next: string) => void
  /** 기간 검증 결과(빈 값·역전·최대 1년 초과). null 이 아니면 서버가 400 을 내므로 실행을 막는다. */
  rangeError: RangeError | null
  /** 상세 조회 중 — 연타로 실행 이력이 여러 행 남지 않게 버튼을 잠근다. */
  running: boolean
  onRun: () => void
  /** 조회 실패 문구. 실패해도 입력값은 그대로 둔다. */
  error?: string | null
  /** 안내 문구(읍면동 미선택 등). 결과 자체는 우측 결과 패널이 그린다. */
  resultNote?: string | null
  /** 분석 계수 상태(`useAnalysisCoefficient('RECOMMEND')`). 조회 실패·그릴 행 0개면 버튼이 숨는다. */
  coeff: AnalysisCoefficient
  /** 현재 보기의 스코프 — 요금제 `RECOMMEND_FEE` · 공유주차제 `RECOMMEND_SHARE`. */
  coeffScope: CoeffScope
  /** `적용` — 확정된 요청. 결과가 있으면 페이지가 재분석한다. */
  onApplyCoeff: (req: CoeffRequest) => void
  /** 배너 `초기화`. */
  onResetCoeff: () => void
}

export default function ZoneRcmdtnSettingPanel({
  tab,
  onTabChange,
  zones,
  zonesLoading,
  zonesError,
  onRetryZones,
  sggNm,
  onSggChange,
  emdCd,
  onEmdChange,
  period,
  onPeriodChange,
  customFrom,
  customTo,
  onCustomFromChange,
  onCustomToChange,
  rangeError,
  running,
  onRun,
  error,
  resultNote,
  coeff,
  coeffScope,
  onApplyCoeff,
  onResetCoeff,
}: ZoneRcmdtnSettingPanelProps) {
  // 팝오버가 열리면 패널을 결과 패널 위로 올린다(팝오버가 패널 오른쪽 밖으로 나간다).
  const [coeffOpen, setCoeffOpen] = useState(false)

  /**
   * 시군구 목록 — `zones` 순서 그대로 묶는다. 페이지가 `zones` 를 **읍면동코드 오름차순**으로
   * 넘긴다(앞 5자리가 시군구라 시군구도 코드순 · 묶음이 이어져 있다). 여기서 다시 정렬하지 않는다.
   */
  const sggList = useMemo(() => {
    const out: string[] = []
    zones.forEach((z) => {
      if (out[out.length - 1] !== z.sggNm) out.push(z.sggNm)
    })
    return out
  }, [zones])

  /**
   * 선택 시군구의 읍면동. 🔴 시군구를 고르지 않으면 **빈 배열**이고 읍면동 select 이 잠긴다 —
   * 74개 동을 한 셀렉트에 쏟지 않기 위해 2단으로 가른 것이 이 칸의 목적이다.
   */
  const emds = useMemo(() => zones.filter((z) => z.sggNm === sggNm), [zones, sggNm])

  /**
   * 요금제 적용은 **시군구**가 입력이라 시군구를 고르지 않으면 실행할 것이 없다
   * (읍면동은 선택 — 비우면 시군구 전체). 도 전체 실행은 열지 않는다. 공유주차제 적용은
   * 지역이 **필터**라 고르지 않아도 도 전체로 실행된다.
   */
  const runDisabled = running || !!rangeError || (tab === 'FEE' && !sggNm)
  /**
   * 공유주차제 적용 보기 — 지역 칸이 **시군구 하나**이고, 그 빈 옵션이 "아직 안 골랐다" 가 아니라
   * "전체" 다.
   */
  const sggOnly = tab === 'SHARE'

  /** 시군구 select — 두 보기가 같은 칸을 쓴다(`aria-label` 만 보기를 따른다). */
  const sggSelect = (
    <div className="field__select-wrap">
      <SrLabel text={sggOnly ? '시군구 필터' : '시군구'}>
        <select
          className="field__select-native"
          aria-label={sggOnly ? '시군구 필터' : '시군구'}
          value={sggNm}
          disabled={zonesLoading || zones.length === 0}
          onChange={(e) => onSggChange(e.target.value)}
        >
          {/* 요금제 보기는 시군구가 정해지면 빈 옵션을 두지 않는다(머리 주석). */}
          {(sggOnly || !sggNm) && (
            <option value="">
              {zonesLoading ? '불러오는 중…' : sggOnly ? '제주도 전체' : '시군구 선택'}
            </option>
          )}
          {sggList.map((nm) => (
            <option key={nm} value={nm}>
              {nm}
            </option>
          ))}
        </select>
      </SrLabel>
      <Icon name="chevron-down" size={18} />
    </div>
  )

  return (
    <div className="card sim-panel sim-panel--left" style={{ zIndex: coeffOpen ? 1200 : undefined }}>
      <div style={TITLE_ROW}>
        <h2 className="sim-panel__title" style={{ margin: 0, whiteSpace: 'nowrap' }}>
          구역추천 분석 설정
        </h2>

        {/* 🔴 현재 보기의 계수만(세그먼트 없음). 보기가 바뀌면 `scope` 가 바뀐다. */}
        <CoefficientPopover
          coeff={coeff}
          scope={coeffScope}
          disabled={running}
          onApply={onApplyCoeff}
          onOpenChange={setCoeffOpen}
        />
      </div>

      <CoefficientBanner
        banner={coeff.banner(coeffScope)}
        onReset={onResetCoeff}
        disabled={running}
        style={COEFF_BANNER}
      />

      {/* ① 분석 내용 — 🔁 상단 탭이 이 칸으로 들어왔다. 문구는 `ZONE_RCMDTN_TABS` 가 정본이다. */}
      <div className="field">
        <span className="field__label">분석 내용</span>
        <div className="field__select-wrap">
          <SrLabel text="분석 내용">
            <select
              className="field__select-native"
              aria-label="분석 내용"
              value={tab}
              onChange={(e) => onTabChange(e.target.value as ZoneRcmdtnTab)}
            >
              {ZONE_RCMDTN_TABS.map((t) => (
                <option key={t.key} value={t.key}>
                  {t.label}
                </option>
              ))}
            </select>
          </SrLabel>
          <Icon name="chevron-down" size={18} />
        </div>
      </div>

      {/* ② 분석 지역 */}
      <div className="field">
        <span className="field__label">분석 지역</span>
        {sggOnly ? (
          /*
           * 🔴 공유주차제 보기는 **시군구 한 칸**이다 — 읍면동 select 을 렌더하지 않는다(머리 주석).
           * 2열 격자(`.field__region-row`)에 넣으면 반 칸만 차므로 감싸지 않는다.
           * 이 보기에서 지역은 필터다 — 비워도 실행된다(판정은 도 전체 기준, 번호는 고른 시군구 안에서 매긴다).
           * 🔁 그 내용을 적던 칸 아래 안내 문구는 걷었다(사용자 요청 2026-10-13).
           */
          sggSelect
        ) : (
          /*
           * 🔴 시군구 → 읍면동 **2단**. 시안 `.field__region-row`(집중구역과 같은 조립)이고
           * 옵션 라벨은 **이름만** 적는다 — 판정 결과를 라벨에 붙이지 않는다(머리 주석 참조).
           */
          <div className="field__region-row">
            {sggSelect}

            <div className="field__select-wrap">
              <SrLabel text="읍면동">
                <select
                  className="field__select-native"
                  aria-label="읍면동"
                  value={emdCd}
                  disabled={!sggNm}
                  onChange={(e) => onEmdChange(e.target.value)}
                >
                  <option value="">읍면동 전체</option>
                  {emds.map((z) => (
                    <option key={z.emdCd} value={z.emdCd}>
                      {z.emdNm}
                    </option>
                  ))}
                </select>
              </SrLabel>
              <Icon name="chevron-down" size={18} />
            </div>
          </div>
        )}

        {zonesError && (
          <p className="field__label" role="alert" style={NOTE_ERROR}>
            {zonesError}{' '}
            <button type="button" onClick={onRetryZones} style={RETRY_BTN}>
              다시 시도
            </button>
          </p>
        )}
      </div>

      {/* ③ 분석 기간 — 🆕 민원 창이다(단속 창은 보내지 않는다 — 서버 기본값이 분석 정본과 같다). */}
      <div className="field">
        <span className="field__label">분석 기간</span>
        <div className="grid-seg">
          {PERIODS.map((p) => (
            <button
              key={p.key}
              type="button"
              aria-pressed={period === p.key}
              className={period === p.key ? 'is-active' : undefined}
              onClick={() => onPeriodChange(p.key)}
            >
              {p.label}
            </button>
          ))}
        </div>

        {/* 직접설정일 때만 날짜 칸이 열린다. 네이티브 date 입력을 투명하게 덮어 칸 전체가 트리거다. */}
        {period === 'custom' && (
          <div className="field__dates">
            <label className="field__date">
              <span>{customFrom || '시작일'}</span>
              <Icon name="calendar" size={16} />
              <input
                className="field__date-input"
                type="date"
                onClick={openDatePicker}
                aria-label="분석 시작일"
                value={customFrom}
                max={customTo || undefined}
                onChange={(e) => onCustomFromChange(e.target.value)}
              />
            </label>
            <span className="field__tilde">~</span>
            <label className="field__date">
              <span>{customTo || '종료일'}</span>
              <Icon name="calendar" size={16} />
              <input
                className="field__date-input"
                type="date"
                onClick={openDatePicker}
                aria-label="분석 종료일"
                value={customTo}
                min={customFrom || undefined}
                max={maxTo(customFrom)}
                onChange={(e) => onCustomToChange(e.target.value)}
              />
            </label>
          </div>
        )}
      </div>

      {/* 서버가 400 을 내는 조건이라 버튼에서 먼저 끊는다. 문구는 5화면 공통이다. */}
      {rangeError && (
        <p className="field__label" role="alert" style={NOTE_ERROR}>
          {RANGE_ERROR_TEXT[rangeError]}
        </p>
      )}

      {/* ⑤ 실행 — 🔴 호출 1회가 실행 이력 1행이다. 연타는 버튼 잠금으로 막는다. */}
      <button
        type="button"
        className="btn-run"
        disabled={runDisabled}
        style={runDisabled ? RUN_DISABLED : undefined}
        onClick={onRun}
      >
        {running ? '분석 중…' : '▷ 구역 추천 실행'}
      </button>

      {error && (
        <p className="field__label" role="alert" style={NOTE_ERROR}>
          {error}
        </p>
      )}

      {resultNote && (
        <p className="field__label" style={NOTE}>
          {resultNote}
        </p>
      )}
    </div>
  )
}
