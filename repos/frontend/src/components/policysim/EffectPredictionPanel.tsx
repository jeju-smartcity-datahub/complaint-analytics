import type { CSSProperties } from 'react'
import EffectChart from './EffectChart'
import InfoTip from '../common/InfoTip'
import type { PlcyTypeCd } from '../../api/policySim'
import {
  CNGST_IDX_SCALE,
  LAG_MM,
  RATE_SCALE,
  baselineMonthsLabel,
  fmtCngstIdxPair,
  fmtCntDiff,
  fmtNum,
  fmtRateMagnitude,
  isNoCngstChange,
  isRoundedAway,
  scenarioFullName,
  type Scenario,
} from './policySimView'

/*
 * 정책 효과 시뮬레이션 — 우측 `효과 예측` 카드.
 *
 * 마크업은 시안 정책효과시뮬레이션 우측 카드(제목 → 차트 → 카드 2장)를 그대로 옮겼다 — 시안도 전용
 * 클래스 없이 `.card` 에 인라인 스타일이다. 시안에 없던 것: 포화 경고.
 * 고정 안내는 제목 줄 오른쪽 `분석 근거` InfoTip(`placement="right"`) 안에 있다(공용화).
 * 문구는 원문 그대로이고 유형별로 갈린다(공통 3 · 이면도로 +3 · 요금제 +4). 대시보드 데이터 기준일과 같은 InfoTip 컴포넌트다.
 * 저장 버튼은 없다 — [시뮬레이션 실행]이 계산과 저장을 함께 한다.
 *
 * | 항목 | 표시 (시안 `le`·`ue`·`ge` 규칙) |
 * | 민원 감소 예측 | `9% 감소`(0 이면 `변화 없음`) · 보조 `C0건 → C1건` · `월평균 약 N건 감소 예상` |
 * | 혼잡도 개선 | `5% 개선`(전후 지수가 같으면 `혼잡도 변화 없음`) · 보조 `혼잡 지수 H0 → H1`(2자리 —
 * |            | 2자리로 같아 보이면 3자리 + `소수점 이하 변화(반올림 표시)`) · `혼잡 지수 1 = 면수만큼 가득 · 낮을수록 원활` |
 * 🔴 반올림에 가려진 차이(`1% 미만` · 지수 3자리 표기)가 있으면 분석 근거에 정밀도·반올림 안내 1줄이 붙는다.
 * | 차트 | 정책 전(점선) / 정책 후(실선·영역), 가로축 = 예측 기간 |
 *
 * 🔴 `NO_ENF`·`NO_SPLY` 결과는 여기까지 오지 않는다 — 부모가 팝업만 띄우고 `scenario` 를 넘기지 않는다.
 * 🔴 포화(`excsYn`)는 값(`-100%`)을 숨기지 않고 경고만 붙인다(DEC-025). 판정은 응답 플래그로 한다.
 *    단 요금제(FEE)는 흡수 포화 안내를 띄우지 않는다(2026-10-27 사용자 요청 — `CONVERT` 는 거의 항상
 *    켜져 안내가 상시 노출됐다). 응답 `excsYn` 과 보고서 1절 `*` 표기는 그대로다.
 *
 * 카드 내역 행 정리(2026-10-14 사용자 요청) — 세 유형 모두 차트 + KPI 2장만 남겼다.
 */

interface EffectPredictionPanelProps {
  /**
   * 표시할 시나리오(시나리오 비교에서 고른 행 · 방금 실행한 행). null 이면 안내 문구.
   * 저장분이면 **저장 당시 값 그대로** 그린다 — 차트 가로축도 그 행의 `predcMm`(재계산하지 않는다).
   */
  scenario: Scenario | null
  loading: boolean
  /** 설정 패널에서 고른 정책 유형 — 결과가 없을 때 유형별 고정 안내를 고르는 데만 쓴다(결과가 있으면 그 행의 유형). */
  plcyType: PlcyTypeCd
}

/** 포화 경고 문구. */
const SATURATED_MSG =
  '⚠ 증설 규모가 이 지역 불법주차 추정치를 넘어 효과가 상한(전량 흡수)에 걸렸습니다. 면수를 줄이거나 시 단위로 조회하세요.'

/** 포화 경고 — 이면도로판(증설 규모 대신 정비 연장). */
const SATURATED_MSG_REPAIR =
  '⚠ 확보 면수가 이 지역 불법주차 추정치를 넘어 효과가 상한(전량 흡수)에 걸렸습니다. 연장을 줄이거나 시 단위로 조회하세요.'

/* ── 시안 인라인 스타일 ────────────────────────────────── */
const CARD: CSSProperties = {
  padding: '24px 28px',
  display: 'flex',
  flexDirection: 'column',
  gap: 20,
}
const HEAD: CSSProperties = { display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }
/** 분석 근거 InfoTip 은 제목 줄 맨 오른쪽 — 시안 인라인 값. */
const BASIS_IC: CSSProperties = { marginLeft: 'auto' }
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
const CHART_SLOT: CSSProperties = { flex: 1, minHeight: 0 }
const KPI_GRID: CSSProperties = { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }
const KPI_LABEL: CSSProperties = {
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-neutral)',
  marginBottom: 6,
}
const KPI_SUB: CSSProperties = { fontSize: 12, color: 'var(--text-neutral)', marginTop: 4 }
const KPI_RDCT: CSSProperties = {
  padding: '16px 20px',
  borderRadius: 12,
  background: 'var(--blue-99)',
  border: '1px solid var(--blue-90)',
}
const KPI_IMPV: CSSProperties = {
  padding: '16px 20px',
  borderRadius: 12,
  background: '#f0fdf4',
  border: '1px solid #bbf7d0',
}
const KPI_RDCT_VAL: CSSProperties = { fontSize: 28, fontWeight: 800, color: 'var(--primary)' }
const KPI_IMPV_VAL: CSSProperties = { fontSize: 28, fontWeight: 800, color: '#16a34a' }
/** 포화 경고 — `HotspotSettingPanel` 의 `NOTE_WARN` 색. */
const KPI_WARN: CSSProperties = {
  margin: '8px 0 0',
  fontSize: 12,
  lineHeight: 1.5,
  color: 'var(--status-cautionary)',
}
/** 값 뒤 단위 단어(`감소`·`개선`) — 시안 15px/700. */
const KPI_WORD: CSSProperties = { fontSize: 15, fontWeight: 700, marginLeft: 3 }
/** `변화 없음` — 시안은 숫자 대신 18px 문구. */
const KPI_NONE: CSSProperties = { fontSize: 18 }
/** 둘째 보조 줄(월평균 차이 · 지수 해석) — 시안 `--text-alternative`. */
const KPI_SUB2: CSSProperties = { fontSize: 12, color: 'var(--text-alternative)', marginTop: 2 }
/** 혼잡도 악화(음수 I) — 경고 색. */
const KPI_IMPV_VAL_DOWN: CSSProperties = { ...KPI_IMPV_VAL, color: 'var(--status-cautionary)' }
/** 민원 증가(양수 R) — 경고 색. */
const KPI_RDCT_VAL_UP: CSSProperties = { ...KPI_RDCT_VAL, color: 'var(--status-cautionary)' }
const EMPTY: CSSProperties = {
  margin: 0,
  fontSize: 'var(--label1-size)',
  color: 'var(--text-alternative)',
}

export default function EffectPredictionPanel({
  scenario,
  loading,
  plcyType,
}: EffectPredictionPanelProps) {
  const r = scenario?.result ?? null
  /** 그리는 행의 유형 — 결과가 없으면 설정 패널의 유형(고정 안내 선택용). */
  const shownType: PlcyTypeCd = scenario?.params.plcyType ?? plcyType
  const isRepair = shownType === 'REPAIR'
  const isFee = shownType === 'FEE'
  /** 요금제 R 이 양수 = 민원 증가 — 라벨·색만 바꾸고 값은 `fmtPct` 그대로(부호 포함). */
  const feeUp = isFee && r?.cvlcptRdctRt != null && r.cvlcptRdctRt > 0
  /** 고정 안내용 적용 계수 — 요금제 전이율 `tau` · 이면도로 풍선효과 `balloonRt`(둘 다 0~1). */
  const tau = r?.aplcnParam?.tau
  const balloonRt = r?.aplcnParam?.balloonRt
  /** 캡션 끝의 기간(시안). 화면에 기간 입력이 없어 응답 `aplcnParam` 이 유일한 출처다. */
  const monthsLabel = baselineMonthsLabel(r?.aplcnParam)
  /** 실행 전 자리 표시(값이 결과의 적용 계수라 결과가 있을 때만 채운다). */
  const pending = '(실행 후 표시)'
  const pct = (v: number | null | undefined) => (v != null ? `${fmtNum(v * 100, 1)}%` : '—')

  /* ── KPI 표시 판정 (시안 `le`·`ue`·`he`·`ge`) ── */
  const rdctRt = r?.cvlcptRdctRt ?? null
  const impvRt = r?.cngstImpvRt ?? null
  /** 민원 변화 단어 — 요금제 양수 R 이면 증가. */
  const rdctWord = feeUp ? '증가' : '감소'
  const cntDiff = r ? fmtCntDiff(r.crtrCvlcptCnt, r.aftrCvlcptCnt) : null
  const noCngst = r ? isNoCngstChange(r) : false
  const idxPair = r ? fmtCngstIdxPair(r.crtrCngstIdx, r.aftrCngstIdx) : null
  /** 화면 반올림이 실제 차이를 가렸다 — 분석 근거에 정밀도 안내를 붙이는 조건. */
  const precisionHidden =
    !!idxPair?.refined || (!noCngst && isRoundedAway(impvRt)) || isRoundedAway(rdctRt)
  /**
   * 분석 근거 문구(원문 그대로).
   * 점유율 `occ`·풍선효과 `balloonRt`·전이율 `tau` 는 결과의 적용 계수다.
   */
  const basis: string[] = [
    '참고: 가정값에 기반한 시뮬레이션 결과이며 실제 정책 효과를 보장하지 않습니다.',
    '혼잡 지수는 주차 혼잡(정상 이용 + 불법주차 ÷ 공급 면수)이며 도로 통행량이 아닙니다.',
    // 혼잡 지수의 의미·범위·해석 기준. 🔴 상한을 1 로 적지 않는다 — 불법주차가 면수의 (1 − 점유율)을 넘으면 1 을 넘는다.
    '혼잡 지수는 0 이상이며 1 이면 공급 면수만큼 차가 차 있는 상태, 1 을 넘으면 불법주차까지 더해 면수보다 많은 차가 있는 상태입니다. 낮을수록 원활하고, 혼잡도 개선률은 (정책 전 지수 − 정책 후 지수) ÷ 정책 전 지수입니다.',
    `점유율 실측값이 없어 설정값 ${r ? (r.aplcnParam?.occ ?? '—') : pending}를 사용했습니다. 이 값이 결과를 크게 좌우합니다.`,
    ...(isRepair
      ? [
          `정비로 밀려난 차량이 인접 지역으로 옮겨가는 효과(풍선효과 ${r ? pct(balloonRt) : pending})를 반영했습니다.`,
          '확보 면수는 한쪽 정비 기준이며 진출입부를 제외한 가정값입니다.',
          '제주 실적상 이면도로 정비는 5회 시도 중 2회 완료되었습니다(주민 반대로 철회·보류 3건).',
        ]
      : []),
    ...(isFee
      ? [
          '요금 변경은 빈자리를 만들어 불법주차를 흡수하는 효과와, 요금을 피해 불법주차가 늘어나는 효과가 함께 있습니다. 둘 중 전이가 크면 민원이 증가합니다.',
          `단속을 병행하는 것을 전제로 계산했습니다(전이율 ${r ? pct(tau) : pending}).`,
          '이 결과는 수요감소율(인상이면 가격탄력성)·전이율·적발배수·점유율 가정값에 크게 좌우됩니다.',
          '빠져나간 차량이 주차장 밖으로 사라진 것으로 계산되어, 민원이 늘어도 혼잡도는 개선으로 나올 수 있습니다.',
        ]
      : []),
    // 반올림에 가려진 차이가 있을 때만 — 계산값의 정밀도와 반올림 방식.
    ...(precisionHidden
      ? [
          `계산값은 혼잡 지수 소수 ${CNGST_IDX_SCALE}자리, 증감률 소수 ${RATE_SCALE}자리(%)까지 반올림(사사오입)한 값입니다. 화면은 혼잡 지수를 소수 2자리, 증감률을 정수 %로 다시 반올림해 보이므로, 표시가 같아도 소수점 이하 차이가 있을 수 있습니다(그때 지수는 소수 ${CNGST_IDX_SCALE}자리로, 1 미만 증감률은 '1% 미만'으로 표시).`,
        ]
      : []),
  ]

  return (
    <div className="card" style={CARD}>
      <div style={HEAD}>
        <h2 style={TITLE}>효과 예측</h2>
        {scenario && (
          <span style={CAPTION}>
            {scenarioFullName(scenario.params, scenario.result.anlsRngCn)}
            {monthsLabel && ` · ${monthsLabel}`}
          </span>
        )}
        {/* 분석 근거 InfoTip — hover·focus 로 열린다(CSS 전용). 결과 전에도 유형별 문구를 보인다. */}
        <InfoTip
          placement="right"
          items={basis}
          style={BASIS_IC}
          label="분석 근거 안내"
          title="분석 근거"
        />
      </div>

      {scenario && r ? (
        <>
          <div style={CHART_SLOT}>
            <EffectChart
              crtrCvlcptCnt={r.crtrCvlcptCnt ?? 0}
              cvlcptRdctRt={r.cvlcptRdctRt}
              predcMm={scenario.params.predcMm}
              lagMm={LAG_MM[scenario.params.plcyType] ?? LAG_MM.EXPAND}
            />
          </div>

          <div style={KPI_GRID}>
            <div style={KPI_RDCT}>
              <div style={KPI_LABEL}>{feeUp ? '민원 증가 예측' : '민원 감소 예측'}</div>
              <div style={feeUp ? KPI_RDCT_VAL_UP : KPI_RDCT_VAL}>
                {rdctRt == null ? (
                  '—'
                ) : rdctRt === 0 ? (
                  <span style={KPI_NONE}>변화 없음</span>
                ) : (
                  <>
                    {fmtRateMagnitude(rdctRt)}
                    <span style={KPI_WORD}>{rdctWord}</span>
                  </>
                )}
              </div>
              <div style={KPI_SUB}>
                {fmtNum(r.crtrCvlcptCnt, 1)}건 → {fmtNum(r.aftrCvlcptCnt, 1)}건
              </div>
              {rdctRt !== 0 && cntDiff && (
                <div style={KPI_SUB2}>
                  월평균 {cntDiff} {rdctWord} 예상
                </div>
              )}
              {r.excsYn && !isFee && (
                <p role="alert" style={KPI_WARN}>
                  {isRepair ? SATURATED_MSG_REPAIR : SATURATED_MSG}
                </p>
              )}
              {isFee && r.wrnngCn && (
                <p role="alert" style={KPI_WARN}>
                  ⚠ {r.wrnngCn}
                </p>
              )}
            </div>
            <div style={KPI_IMPV}>
              <div style={KPI_LABEL}>
                {isRepair || isFee ? '혼잡도 개선 (대상 지역)' : '혼잡도 개선'}
              </div>
              <div
                style={impvRt != null && impvRt < 0 && !noCngst ? KPI_IMPV_VAL_DOWN : KPI_IMPV_VAL}
              >
                {noCngst ? (
                  <span style={KPI_NONE}>혼잡도 변화 없음</span>
                ) : impvRt == null ? (
                  '—'
                ) : (
                  <>
                    {/* `1% 미만` 의 `미만` 은 숫자가 아니라 단위 단어라 옆 `개선` 과 같은 크기(KPI_WORD)로 —
                        통째로 숫자 크기(28px)면 `미만` 이 `개선` 보다 커 보였다. */}
                    {isRoundedAway(impvRt) ? (
                      <>
                        1%<span style={KPI_WORD}>미만 {impvRt < 0 ? '악화' : '개선'}</span>
                      </>
                    ) : (
                      <>
                        {fmtRateMagnitude(impvRt)}
                        <span style={KPI_WORD}>{impvRt < 0 ? '악화' : '개선'}</span>
                      </>
                    )}
                  </>
                )}
              </div>
              <div style={KPI_SUB}>
                혼잡 지수 {idxPair?.from} → {idxPair?.to}
              </div>
              <div style={KPI_SUB2}>
                혼잡 지수 1 = 면수만큼 가득 · 낮을수록 원활
                {idxPair?.refined && ' · 소수점 이하 변화(반올림 표시)'}
              </div>
            </div>
          </div>
        </>
      ) : (
        <>
          {/* 값이 없을 때 차트 자리를 스켈레톤으로 채운다 — 실행 전·계산 중·팝업(NO_ENF·NO_SPLY) 뒤 공통.
              스타일은 analysis.css `.sim-chart-skeleton`. */}
          <div style={CHART_SLOT}>
            <div className="sim-chart-skeleton" aria-hidden="true" />
          </div>
          <p style={EMPTY}>
            {loading
              ? '계산 중입니다…'
              : '정책 변수를 설정하고 시뮬레이션을 실행하면 결과가 표시됩니다.'}
          </p>
        </>
      )}
    </div>
  )
}
