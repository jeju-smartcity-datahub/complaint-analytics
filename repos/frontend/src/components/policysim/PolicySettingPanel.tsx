import type { CSSProperties, ReactNode, SelectHTMLAttributes } from 'react'
import Icon from '../Icon'
import type { RegionItem } from '../../api/regions'
import {
  ADD_PKLT_SLOTS,
  FEE_INCRS_RTS,
  ROAD_MTNC_LEN_MAX,
  ROAD_MTNC_LEN_MIN,
  type AddPkltSlot,
  type ExpandMethod,
  type FeeAplcnMthd,
  type FeeAplcnZone,
  type FeeIncrsRt,
  type PlcyTypeCd,
  type RoadMtncType,
} from '../../api/policySim'
import { fmtNum, parseRoadMtncLen, type RepairN100 } from './policySimView'
import SrLabel from '../common/SrLabel'

/*
 * 정책 효과 시뮬레이션 — 좌측 `정책 변수 설정` 카드 (TSK-4037 Step 6 · 명세 2.1).
 *
 * 마크업은 시안(https://innopamsolution.github.io/jeju-datahub/)의 정책효과시뮬레이션 화면 좌측 카드를
 * 그대로 옮겼다 — 시안도 전용 클래스 없이 `.card` · `.segment` · `.ds-select` · `.btn-run` 에
 * 인라인 스타일을 얹었다. 여기서 CSS 를 새로 만들지 않는다.
 *
 * | 항목 | 선택지 (기본) | 노출 조건 |
 * | 정책 유형 | 주차장 확충(기본) · 이면도로 정비 · 요금제 적용 | 항상 |
 * | 대상 지역(시) | 제주 전체 · 제주시(기본) · 서귀포시 | 항상 |
 * | 대상 지역(읍면동) | 전체 + 시의 읍면동 | 제주 전체면 비활성 |
 * | 확충 방식 | 공한지 조성(기본) · 복층화 · 신규 조성 | 주차장 확충 |
 * | 주차면 증설 수 | 50·80·100·150·200면 | 주차장 확충 |
 * | 정비 방식 | 상업지역형 · 주거지역형(기본) | 이면도로 정비 (TSK-4038) |
 * | 정비 구간 연장 | 1~10000 m 정수(기본 600, step 100) + 즉시 환산 힌트 | 이면도로 정비 (TSK-4038) |
 * | 적용 방식 | 요금 인상(기본) · 무료 → 유료 전환 | 요금제 적용 (TSK-4039) |
 * | 적용 급지 | 전체(기본) · 1급지 (동) · 2급지 (읍·면) | 요금제 적용 · 읍면동 `전체` (TSK-4291 — 제주 전체 포함) |
 * | 인상률 | +10%(기본) · +20% · +30% | 요금제 적용 · 요금 인상 |
 *
 * 🔴 **읍면동 목록을 하드코딩하지 않는다** — 시안의 읍면동 배열(제주시 5곳·서귀포시 3곳)은 목업이다.
 * 시군구·읍면동 모두 `GET /api/regions` 응답(`regions`)에서 그린다(서버 정렬 그대로).
 *
 * `이면도로 정비` 는 TSK-4038, `요금제 적용` 은 TSK-4039 에서 열었다.
 *
 * 🔴 요금제의 **적용 급지는 읍면동이 `전체` 일 때만** 고른다(TSK-4291 — 제주 전체에서도 노출). 읍면동을 지정하면
 * 세그먼트를 숨기고 요청에서도 빠져(`toQuery`) 서버가 그 읍면동의 급지로 계산한다. 급지 이름 접미(` 1급지`·` 2급지`)는
 * `policySimView.scenarioFullName` 한 곳이 붙인다. 적용 대상 면수 힌트는 **결과 응답 `aplcnTrgtSlot`** 이며, 실행 전이거나
 * 입력이 결과와 달라지면 숨긴다(사전 조회 API 가 없다).
 *
 * 🔴 정비 구간 연장 아래 **환산 힌트(`→ 약 {ΔS}면 확보 ({n_100}면/100m)`)를 즉시** 보인다 — 600m 가 몇 면인지
 * 모르면 결과를 해석할 수 없고, 방식을 바꾸면 면수가 달라진다(86.4 ↔ 72). n_100 은 `GET /api/analysis-weights`
 * 의 POLICY_SIM 행에서 온다(하드코딩 금지). 힌트는 표시용이며 결과 카드는 응답 `vldAddPkltSlot` 을 쓴다.
 *
 * 이 컴포넌트는 **controlled 프레젠테이션 컴포넌트**다. 조회 상태와 API 호출은 전부
 * `PolicySimulationPage` 가 들고 있고, 여기서는 값을 그리고 이벤트를 올려 보내기만 한다
 * (설정 패널이 스스로 조회하면 "진입 직후 자동 조회 금지" 를 지키기 어렵다 — 생활권·집중구역과 같다).
 *
 * 분석 계수 조정(TSK-4332) — 제목 줄 오른쪽 버튼(`coeffAction`)과 제목 아래 배너(`coeffBanner`)는 **페이지가**
 * 만들어 넘긴다(훅 `useAnalysisCoefficient('POLICY')` 는 페이지가 갖는다 — 투자우선순위 `InvestPrrtList` 선례).
 * 팝오버가 열리면(`coeffOpen`) 카드를 `zIndex: 5` 로 올려 옆 `효과 예측` 카드 위에 그린다(시안).
 */

/** 정책 유형 세그먼트. `available=false` 는 서버 미구현(400)이라 비활성으로 그린다. */
const PLCY_TYPES: { cd: PlcyTypeCd; label: string; available: boolean }[] = [
  { cd: 'EXPAND', label: '주차장 확충', available: true },
  { cd: 'REPAIR', label: '이면도로 정비', available: true },
  { cd: 'FEE', label: '요금제 적용', available: true },
]

/** 미구현 정책 유형 툴팁. */
const PLCY_UNAVAILABLE_RSN = '준비 중인 정책 유형입니다.'

/** 확충 방식 세그먼트 — 라벨은 시안 그대로. */
const EXPAND_METHODS: { cd: ExpandMethod; label: string }[] = [
  { cd: 'VACLOT', label: '공한지 조성' },
  { cd: 'MULTFLR', label: '복층화' },
  { cd: 'NEW', label: '신규 조성' },
]

/** 정비 방식 세그먼트 — 라벨은 계획서 TSK-4038 Step 5-1 그대로(괄호 안은 근거 사업명). */
const ROAD_MTNC_TYPES: { cd: RoadMtncType; label: string }[] = [
  { cd: 'CMRC', label: '상업지역형 (주차환경개선지구)' },
  { cd: 'RSDNC', label: '주거지역형 (주민참여형)' },
]

/** 정비 구간 연장 입력 증감 폭(m). */
const ROAD_MTNC_LEN_STEP = 100

/** 요금 적용 방식 세그먼트(TSK-4039 Step 6-1). */
const FEE_APLCN_MTHDS: { cd: FeeAplcnMthd; label: string }[] = [
  { cd: 'RAISE', label: '요금 인상' },
  { cd: 'CONVERT', label: '무료 → 유료 전환' },
]

/** 요금 적용 급지 세그먼트(TSK-4291 · 시안 순서). 1급지 = 동, 2급지 = 읍·면(서버 `zoneOf`). */
const FEE_APLCN_ZONES: { cd: FeeAplcnZone; label: string }[] = [
  { cd: 'ALL', label: '전체' },
  { cd: 'Z1', label: '1급지 (동)' },
  { cd: 'Z2', label: '2급지 (읍·면)' },
]

/* ── 시안 인라인 스타일 (전용 클래스가 없다) ─────────────────── */

const CARD: CSSProperties = {
  padding: '24px 28px',
  display: 'flex',
  flexDirection: 'column',
  gap: 20,
}
const HEAD: CSSProperties = { display: 'flex', alignItems: 'baseline', gap: 12, flexWrap: 'wrap' }
/** 계수 버튼 자리 — 제목 줄 오른쪽(시안 `marginLeft:auto`). */
const COEFF_ACTION: CSSProperties = { marginLeft: 'auto', alignSelf: 'center' }
/** 계수 배너 자리 — 제목 바로 아래(시안 `margin: -12px 0 0` — 카드 `gap: 20` 을 줄인다). */
const COEFF_BANNER: CSSProperties = { margin: '-12px 0 0' }
const TITLE: CSSProperties = {
  margin: 0,
  fontSize: 17,
  fontWeight: 700,
  color: 'var(--text-strong)',
}
const CAPTION: CSSProperties = {
  fontSize: 'var(--caption1-size)',
  color: 'var(--text-alternative)',
}
const LABEL: CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-neutral)',
  marginBottom: 8,
}
const TWO_COL: CSSProperties = { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }
const FULL: CSSProperties = { width: '100%' }

/** `.segment__btn` 에는 `:disabled` 규칙이 없다 — 생활권 설정 패널과 같은 보정. */
const SEG_DISABLED: CSSProperties = { opacity: 0.45, cursor: 'not-allowed' }
/** disabled 버튼은 `title` 툴팁이 안 뜨는 브라우저가 있어 감싼 span 에도 사유를 건다. */
const SEG_SLOT: CSSProperties = { display: 'inline-flex' }
/** `.ds-select select` 에도 `:disabled` 규칙이 없다. */
const SELECT_DISABLED: CSSProperties = { opacity: 0.5, cursor: 'not-allowed' }
/** `.btn-run` 에는 `:disabled` 규칙이 없다(시안에 대기 상태가 없었다). */
const RUN_DISABLED: CSSProperties = { opacity: 0.6, cursor: 'not-allowed' }
/**
 * 숫자 입력 — 전용 클래스가 없어 `.ds-select select`(dashboard.css) 의 치수·테두리·글꼴을 그대로 옮겼다
 * (같은 카드 안 select 와 높이·모양이 맞아야 한다).
 */
const NUM_WRAP: CSSProperties = { display: 'flex', alignItems: 'center', gap: 8 }
const NUM_INPUT: CSSProperties = {
  flex: 1,
  minWidth: 0,
  height: 42,
  padding: '0 14px',
  border: '1px solid var(--line-normal)',
  borderRadius: 'var(--radius-10)',
  background: 'var(--card-bg)',
  fontFamily: 'var(--font-body)',
  fontSize: 'var(--label1-size)',
  fontWeight: 'var(--weight-semibold)',
  color: 'var(--text-strong)',
}
const NUM_INPUT_INVALID: CSSProperties = { ...NUM_INPUT, borderColor: 'var(--status-negative)' }
const NUM_SUFFIX: CSSProperties = {
  fontSize: 'var(--label1-size)',
  fontWeight: 600,
  color: 'var(--text-neutral)',
}
/** 환산 힌트 — 입력 바로 아래 한 줄. 값이 눈에 띄도록 primary 색. */
const HINT: CSSProperties = {
  margin: '8px 0 0',
  color: 'var(--primary)',
  fontWeight: 'var(--weight-semibold)',
}
const NOTE_MUTED: CSSProperties = { margin: '8px 0 0' }
/** 인라인 안내 문구 — `HotspotSettingPanel` 의 `.field__label` + 색 마크업과 같다. */
const NOTE_ERROR: CSSProperties = {
  margin: 0,
  color: 'var(--status-negative)',
  fontWeight: 'var(--weight-regular)',
}
const LINK_BTN: CSSProperties = {
  background: 'none',
  border: 'none',
  padding: 0,
  color: 'var(--primary)',
  cursor: 'pointer',
  textDecoration: 'underline',
  font: 'inherit',
}

/** 시안의 `.ds-select` — 네이티브 select 위에 chevron 아이콘을 얹는다. */
function DsSelect({
  children,
  disabled,
  ...rest
}: SelectHTMLAttributes<HTMLSelectElement> & { children: ReactNode }) {
  return (
    <div className="ds-select" style={FULL}>
      <SrLabel text={rest['aria-label']}>
        <select {...rest} disabled={disabled} style={disabled ? SELECT_DISABLED : undefined}>
          {children}
        </select>
      </SrLabel>
      <span className="ds-select__ic">
        <Icon name="chevron-down" size={16} />
      </span>
    </div>
  )
}

interface PolicySettingPanelProps {
  plcyType: PlcyTypeCd
  onPlcyTypeChange: (next: PlcyTypeCd) => void

  /** 시군구 → 읍면동 트리 (`GET /api/regions`). 아직 못 받았으면 `[]`. */
  regions: RegionItem[]
  /** 지역 목록 조회 실패 여부. true 면 안내와 재시도 버튼을 보인다. */
  regionsFailed?: boolean
  onRetryRegions?: () => void
  /** 선택된 시군구명. `''` = 제주 전체. */
  sggNm: string
  /** 선택된 읍면동코드. `''` = 시 전체. */
  emdCd: string
  onSggChange: (next: string) => void
  onEmdChange: (next: string) => void

  expandMethod: ExpandMethod
  onExpandMethodChange: (next: ExpandMethod) => void
  addPkltSlot: AddPkltSlot
  onAddPkltSlotChange: (next: AddPkltSlot) => void

  /** 정비 방식(REPAIR). */
  roadMtncType: RoadMtncType
  onRoadMtncTypeChange: (next: RoadMtncType) => void
  /** 정비 구간 연장 입력 문자열(REPAIR) — 지우고 다시 칠 수 있게 문자열로 든다. 판정은 `parseRoadMtncLen`. */
  roadMtncLenText: string
  onRoadMtncLenChange: (next: string) => void
  /** 이면도로 n_100(정비 유형별). 아직 못 받았거나 실패면 null — 힌트 대신 안내를 보인다. */
  repairN100: RepairN100 | null
  /** n_100 조회 실패 여부(힌트 자리에 안내). */
  repairN100Failed?: boolean

  /** 요금 적용 방식(FEE). */
  feeAplcnMthd: FeeAplcnMthd
  onFeeAplcnMthdChange: (next: FeeAplcnMthd) => void
  /** 요금 적용 급지(FEE · 읍면동 `전체` 일 때만 노출 — TSK-4291). */
  feeAplcnZone: FeeAplcnZone
  onFeeAplcnZoneChange: (next: FeeAplcnZone) => void
  /** 요금 인상률(FEE·RAISE). */
  feeIncrsRt: FeeIncrsRt
  onFeeIncrsRtChange: (next: FeeIncrsRt) => void
  /** 적용 대상 면수 힌트 — 현재 입력과 같은 조건의 결과 `aplcnTrgtSlot`. 없으면(실행 전·조건 변경) 숨김. */
  feeAplcnTrgtSlot?: number | null

  /** 조회 중 여부. */
  running: boolean
  onRun: () => void
  /** 조회 실패 문구. 실패해도 위 입력값은 그대로 둔다. */
  error?: string | null

  /** 분석 계수 조정 버튼(TSK-4332) — 페이지가 만든 `CoefficientPopover`. 없으면 자리도 없다. */
  coeffAction?: ReactNode
  /** 적용 계수 배너(TSK-4332) — 페이지가 만든 `CoefficientBanner`(변경분 없으면 페이지가 넘기지 않는다). */
  coeffBanner?: ReactNode
  /** 팝오버 열림 — 카드 `zIndex: 5`(옆 카드 위로). */
  coeffOpen?: boolean
}

export default function PolicySettingPanel({
  plcyType,
  onPlcyTypeChange,
  regions,
  regionsFailed = false,
  onRetryRegions,
  sggNm,
  emdCd,
  onSggChange,
  onEmdChange,
  expandMethod,
  onExpandMethodChange,
  addPkltSlot,
  onAddPkltSlotChange,
  roadMtncType,
  onRoadMtncTypeChange,
  roadMtncLenText,
  onRoadMtncLenChange,
  repairN100,
  repairN100Failed = false,
  feeAplcnMthd,
  onFeeAplcnMthdChange,
  feeAplcnZone,
  onFeeAplcnZoneChange,
  feeIncrsRt,
  onFeeIncrsRtChange,
  feeAplcnTrgtSlot = null,
  running,
  onRun,
  error,
  coeffAction,
  coeffBanner,
  coeffOpen = false,
}: PolicySettingPanelProps) {
  const emds = regions.find((r) => r.sggNm === sggNm)?.emds ?? []
  /*
   * 기본 시군구(`제주시`)는 목록을 받기 전에도 select 에 찍혀야 한다 — 옵션이 없으면 브라우저가
   * 첫 옵션(`제주 전체`)을 보여 줘 화면 값과 상태가 갈린다. 목록에 없을 때만 자리 옵션을 하나 둔다.
   */
  const sggMissing = !!sggNm && !regions.some((r) => r.sggNm === sggNm)
  const isExpand = plcyType === 'EXPAND'
  const isRepair = plcyType === 'REPAIR'
  const roadMtncLen = parseRoadMtncLen(roadMtncLenText)
  const lenInvalid = isRepair && roadMtncLen === null
  const n100 = repairN100 ? repairN100[roadMtncType] : null
  const isFee = plcyType === 'FEE'
  // 실행 가능 = 확충 · 연장이 유효한 이면도로 · 요금제(세그먼트라 입력이 항상 유효하다).
  const runDisabled = running || !(isExpand || isFee || (isRepair && !lenInvalid))

  return (
    <div className="card" style={{ ...CARD, zIndex: coeffOpen ? 5 : undefined }}>
      <div style={HEAD}>
        <h2 style={TITLE}>정책 변수 설정</h2>
        <span style={CAPTION}>정책 변수 입력 → 효과 예측 → 시나리오 비교</span>
        {coeffAction && <div style={COEFF_ACTION}>{coeffAction}</div>}
      </div>
      {coeffBanner && <div style={COEFF_BANNER}>{coeffBanner}</div>}

      <div>
        <div style={LABEL}>정책 유형</div>
        <div className="segment" role="group" aria-label="정책 유형">
          {PLCY_TYPES.map((t) => {
            const rsn = t.available ? undefined : PLCY_UNAVAILABLE_RSN
            return (
              <span key={t.cd} title={rsn} style={SEG_SLOT}>
                <button
                  type="button"
                  className={`segment__btn${plcyType === t.cd ? ' segment__btn--active' : ''}`}
                  aria-pressed={plcyType === t.cd}
                  disabled={!t.available}
                  title={rsn}
                  style={t.available ? undefined : SEG_DISABLED}
                  onClick={() => onPlcyTypeChange(t.cd)}
                >
                  {t.label}
                </button>
              </span>
            )
          })}
        </div>
      </div>

      <div>
        <div style={TWO_COL}>
          <div>
            <div style={LABEL}>대상 지역</div>
            <DsSelect
              aria-label="시군구"
              value={sggNm}
              onChange={(e) => onSggChange(e.target.value)}
            >
              <option value="">제주도 전체</option>
              {sggMissing && <option value={sggNm}>{sggNm}</option>}
              {regions.map((r) => (
                <option key={r.sggNm} value={r.sggNm}>
                  {r.sggNm}
                </option>
              ))}
            </DsSelect>
          </div>
          <div>
            <div style={LABEL}>{' '}</div>
            <DsSelect
              aria-label="읍면동"
              value={emdCd}
              disabled={!sggNm}
              onChange={(e) => onEmdChange(e.target.value)}
            >
              <option value="">읍면동 전체</option>
              {emds.map((emd) => (
                <option key={emd.emdCd} value={emd.emdCd}>
                  {emd.emdNm}
                </option>
              ))}
            </DsSelect>
          </div>
        </div>

        {regionsFailed && (
          <p className="field__label" role="alert" style={{ ...NOTE_ERROR, marginTop: 8 }}>
            지역 목록을 불러오지 못했습니다. 백엔드가 실행 중인지 확인해 주세요.{' '}
            {onRetryRegions && (
              <button type="button" style={LINK_BTN} onClick={onRetryRegions}>
                다시 시도
              </button>
            )}
          </p>
        )}
      </div>

      {isExpand && (
        <>
          <div>
            <div style={LABEL}>확충 방식</div>
            <div className="segment" role="group" aria-label="확충 방식">
              {EXPAND_METHODS.map((m) => (
                <button
                  key={m.cd}
                  type="button"
                  className={`segment__btn${expandMethod === m.cd ? ' segment__btn--active' : ''}`}
                  aria-pressed={expandMethod === m.cd}
                  onClick={() => onExpandMethodChange(m.cd)}
                >
                  {m.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <div style={LABEL}>주차면 증설 수</div>
            <DsSelect
              aria-label="주차면 증설 수"
              value={addPkltSlot}
              onChange={(e) => onAddPkltSlotChange(Number(e.target.value) as AddPkltSlot)}
            >
              {ADD_PKLT_SLOTS.map((n) => (
                <option key={n} value={n}>
                  {n}면
                </option>
              ))}
            </DsSelect>
          </div>
        </>
      )}

      {isRepair && (
        <>
          <div>
            <div style={LABEL}>정비 방식</div>
            <div className="segment" role="group" aria-label="정비 방식">
              {ROAD_MTNC_TYPES.map((t) => (
                <button
                  key={t.cd}
                  type="button"
                  className={`segment__btn${roadMtncType === t.cd ? ' segment__btn--active' : ''}`}
                  aria-pressed={roadMtncType === t.cd}
                  onClick={() => onRoadMtncTypeChange(t.cd)}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <div style={LABEL}>정비 구간 연장</div>
            <div style={NUM_WRAP}>
              <SrLabel text="정비 구간 연장(m)">
                <input
                  type="number"
                  aria-label="정비 구간 연장(m)"
                  aria-invalid={lenInvalid}
                  min={ROAD_MTNC_LEN_MIN}
                  max={ROAD_MTNC_LEN_MAX}
                  step={ROAD_MTNC_LEN_STEP}
                  inputMode="numeric"
                  value={roadMtncLenText}
                  style={lenInvalid ? NUM_INPUT_INVALID : NUM_INPUT}
                  onChange={(e) => onRoadMtncLenChange(e.target.value)}
                />
              </SrLabel>
              <span style={NUM_SUFFIX}>m</span>
            </div>
            {lenInvalid ? (
              <p className="field__label" role="alert" style={{ ...NOTE_ERROR, marginTop: 8 }}>
                정비 구간 연장은 {ROAD_MTNC_LEN_MIN}~{fmtNum(ROAD_MTNC_LEN_MAX, 0)}m 사이의 정수로
                입력해 주세요.
              </p>
            ) : n100 !== null && roadMtncLen !== null ? (
              <p className="field__label" style={HINT}>
                → 약 {fmtNum((roadMtncLen / 100) * n100, 1)}면 확보 ({fmtNum(n100, 1)}면/100m)
              </p>
            ) : (
              <p className="field__label" style={NOTE_MUTED}>
                {repairN100Failed
                  ? '환산 계수(100m당 확보 면수)를 불러오지 못해 확보 면수를 미리 보여드릴 수 없습니다.'
                  : '환산 계수를 불러오는 중…'}
              </p>
            )}
          </div>
        </>
      )}

      {isFee && (
        <>
          <div>
            <div style={LABEL}>적용 방식</div>
            <div className="segment" role="group" aria-label="적용 방식">
              {FEE_APLCN_MTHDS.map((m) => (
                <button
                  key={m.cd}
                  type="button"
                  className={`segment__btn${feeAplcnMthd === m.cd ? ' segment__btn--active' : ''}`}
                  aria-pressed={feeAplcnMthd === m.cd}
                  onClick={() => onFeeAplcnMthdChange(m.cd)}
                >
                  {m.label}
                </button>
              ))}
            </div>
          </div>

          {!emdCd && (
            <div>
              <div style={LABEL}>적용 급지</div>
              <div className="segment" role="group" aria-label="적용 급지">
                {FEE_APLCN_ZONES.map((z) => (
                  <button
                    key={z.cd}
                    type="button"
                    className={`segment__btn${feeAplcnZone === z.cd ? ' segment__btn--active' : ''}`}
                    aria-pressed={feeAplcnZone === z.cd}
                    onClick={() => onFeeAplcnZoneChange(z.cd)}
                  >
                    {z.label}
                  </button>
                ))}
              </div>
            </div>
          )}

          {feeAplcnMthd === 'RAISE' && (
            <div>
              <div style={LABEL}>인상률</div>
              <div className="segment" role="group" aria-label="인상률">
                {FEE_INCRS_RTS.map((rt) => (
                  <button
                    key={rt}
                    type="button"
                    className={`segment__btn${feeIncrsRt === rt ? ' segment__btn--active' : ''}`}
                    aria-pressed={feeIncrsRt === rt}
                    onClick={() => onFeeIncrsRtChange(rt)}
                  >
                    +{rt}%
                  </button>
                ))}
              </div>
            </div>
          )}

          {feeAplcnTrgtSlot != null && (
            <p className="field__label" style={{ ...HINT, marginTop: 0 }}>
              → 적용 대상 {fmtNum(feeAplcnTrgtSlot, 0)}면
            </p>
          )}
        </>
      )}

      <button
        type="button"
        className="btn-run"
        disabled={runDisabled}
        style={runDisabled ? RUN_DISABLED : undefined}
        onClick={onRun}
      >
        {running ? '계산 중…' : '▷ 시뮬레이션 실행'}
      </button>

      {error && (
        <p className="field__label" role="alert" style={NOTE_ERROR}>
          {error}
        </p>
      )}
    </div>
  )
}
