import type { ComplaintKpiResponse } from '../../api/complaints'
import Icon from '../Icon'
import type { IconName } from '../icons'
import SentimentCriteriaTip from './SentimentCriteriaTip.tsx'

/*
 * 민원현황(GIS) §3 KPI 카드 4종.
 *
 * 시안의 민원현황 화면 상단 가로 4열 카드:
 *   1. 총 민원 건수           — `totalCount`
 *   2. 민원 감정 분석         — `negativityRatio` (백분율) + 부정 비율 그라데이션 바
 *   3. 단속 집중 지역         — `enforcementCount` (TB_CRDN_DSCTN 원천 집계 중 **심각 등급 동 수**)
 *   4. 수요 부족 지역         — `shortageCount` (주차민원 > 0 인 zone 중 **심각 등급 동 수**)
 *
 * 모든 값은 백엔드 `GET /api/complaints/gis` 의 flat 응답에서 직접 가져온다.
 * 로딩/에러/미응답 시 `kpi=null` 또는 `error=true` 로 받아 자리표시(— 또는 빈 바)를 띄운다.
 *
 * 🔴 2026-09-30 — 카드 3·4 의 정의가 갱신됐다.
 *    · 카드 3 종전 설명 "TB_CRDN_DSCTN 원천 집계" = 단속이 1건이라도 있는 **모든 동**(9월 43).
 *    · 카드 4 종전 설명 "TB_ADMDST + 민원 0건 zone" 은 **폐기된 옛 정의**다 — 2026-09-04 에
 *      PSDR Jenks k=3 으로 바뀌었고(9월 39), 다시 2026-09-30 지적으로 그 안의 상위 10% 가 됐다.
 *    · 지금은 둘 다 백엔드가 `FLOOR(모집단 × 0.1)` 로 주어 **지도/리스트의 `심각` 행 수와 같다**
 *      (9월 실측 4개동 · 3개동).
 *
 * 🔁 **2026-10-02 — 카드 4 의 모집단이 또 바뀌었다.** 종전엔 Jenks `심각` 등급을
 *    먼저 걸러 그 안에서 상위 10% 였는데(9월 39 → 3), **Jenks 등급을 쓰지 않기로 해**
 *    모집단이 `주차민원 > 0 인 전 zone` 이 됐다(9월 67 → **6**). 🔴 **`FLOOR(모집단 × 0.1)` 규칙과
 *    "지도·리스트의 `심각` 행 수와 같다" 는 그대로다** — 바뀐 것은 모집단뿐이다.
 *    🔴 그 `10%` 임계값은 프론트 `legendConstants.PCT_10` 과 백엔드
 *       `mapper/complaint/ComplaintMapper.xml`(selectEnforcementCount·selectShortageCount)
 *       **두 곳**에 있다 — 한쪽만 고치면 KPI 와 리스트가 다시 어긋난다.
 *
 * 🔴 시안의 "심각 3개동" 같은 단계 라벨 중 **등급 이름은 이제 표시한다**(값이 심각 등급 개수이므로).
 *    그 밖에 응답에 없는 데이터는 여전히 채워 넣지 않는다.
 */

/** 백분율(0~100) → 정수 0~100. 범위 밖은 안전하게 클램프. */
function clampPct(p: number): number {
  if (!Number.isFinite(p)) return 0
  if (p < 0) return 0
  if (p > 100) return 100
  return Math.round(p)
}

/**
 * 큰 숫자 카운트 표기 — 1000 이상이면 천 단위 콤마. 백엔드가 Long 으로 주지만 JS 에선
 * Number 로 들어옴(소수점 없음 가정). null/NaN/Infinity 는 자리표시(—), 음수는
 * 0 으로 표시 — 데이터 오류 가드(서버가 음수를 흘리는 일은 없지만, 들어오면
 * 0 대신 띄우면 "시평값 0" 처럼 보여 잘못 해석된다).
 */
function formatCount(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—'
  if (n < 0) return '0'
  return n.toLocaleString('ko-KR')
}

/** "62%" 처럼 백분율 문자열. */
function formatPct(ratio: number): string {
  return `${clampPct(ratio * 100)}%`
}

export interface KpiBarProps {
  /** 백엔드 응답. null 이면 로딩/에러/미응답. */
  kpi: ComplaintKpiResponse | null
  /** 초기 로딩 중 표시. true 이면 카드 값 자리에 — 를 띄움. */
  loading: boolean
  /** 직전 fetch 가 실패한 상태. true 이면 카드 자리에 0/— 외 별도 안내는 띄우지 않는다
   *  (시안엔 에러 안내 자리 없음 — 다음 fetch 가 성공하면 덮어씀). */
  error: boolean
}

/** 카드 4종 정의. JSX 안에서 inline 으로 두면 가독성이 떨어져 데이터로 분리. */
interface CardSpec {
  key: 'total' | 'sentiment' | 'enforcement' | 'shortage'
  /** 카드 1·3·4 의 정적 아이콘. 카드 2 는 stroke 기반 SVG 얼굴(`SentimentFaceIcon`)이라
   *  `icons.ts`(fill 기반)에 넣지 않고 따로 렌더한다 — icon 없음. */
  icon?: IconName
  /** 카드 좌측 동그라미 안의 아이콘 색. CSS 변수명에서 그대로 가져옴. */
  iconTone: 'primary' | 'secondary' | 'warning' | 'info'
  label: string
  /** 시안 카드 1·3·4 의 큰 숫자 표시만. 카드 2 처럼 비율만 보여주는 카드는 null 반환. */
  valueOf: (kpi: ComplaintKpiResponse) => number | null
  /** 카드 2 의 "부정 N%" + 감정 바. 그 외 카드는 undefined. */
  ratioOf?: (kpi: ComplaintKpiResponse) => number
}

const CARDS: CardSpec[] = [
  {
    key: 'total',
    icon: 'document',
    iconTone: 'primary',
    label: '총 민원 건수',
    valueOf: (kpi) => kpi.totalCount,
  },
  {
    // 카드 2 는 시안 `mCe` 의 정적 SVG 얼굴(`SentimentFaceIcon`)이다.
    // 부정 비율에 따라 표정이 바뀌던 emoji 동작은 이 교체로 사라졌다.
    key: 'sentiment',
    iconTone: 'secondary',
    label: '민원 감정 분석',
    // 카드 2 는 비율 + 바만 보여줌 — 큰 숫자 자리엔 null 반환해서 자리표시(—).
    valueOf: () => null,
    ratioOf: (kpi) => kpi.negativityRatio,
  },
  {
    key: 'enforcement',
    icon: 'warning',
    iconTone: 'warning',
    // 🔴 값이 "심각 등급 동 수" 다. 그 사실은 **값 줄이 이미 말한다**
    //    (`kpi-card__text-serious` 슬롯의 `심각` + `4` + `개동`) — 라벨에 다시 적지 않는다.
    label: '단속 집중 지역',
    valueOf: (kpi) => kpi.enforcementCount,
  },
  {
    // 시안 번들의 `stat__icon--teal` 이 `car` 를 쓴다(P 마커가 아니다).
    key: 'shortage',
    icon: 'car',
    iconTone: 'info',
    // 🔴 카드 3 과 같다: 값이 "심각 등급 동 수" 이고 그 사실은 값 줄이 말한다.
    label: '수요 부족 지역',
    valueOf: (kpi) => kpi.shortageCount,
  },
]

/**
 * 카드 2 "민원 감정 분석" 아이콘 — 시안 번들 `mCe` 원문 그대로(26px, stroke 기반).
 * 색은 `currentColor` 라 `.kpi-card[data-tone='secondary'] .kpi-card__icon` 의
 * `--purple-40` 을 받는다. `icons.ts` 는 fill 기반(`<Icon>` 이 fill=currentColor)이라
 * 형식이 달라 여기 인라인으로 둔다.
 */
function SentimentFaceIcon() {
  return (
    <svg viewBox="0 0 24 24" width="26" height="26" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="9.25" stroke="currentColor" strokeWidth="1.6" />
      <circle cx="8.5" cy="10" r="1.1" fill="currentColor" />
      <circle cx="15.5" cy="10" r="1.1" fill="currentColor" />
      <path
        d="M8.3 16c.9-1.4 2.2-2.1 3.7-2.1s2.8.7 3.7 2.1"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        fill="none"
      />
    </svg>
  )
}

export default function KpiBar({ kpi, loading, error }: KpiBarProps) {
  // 카드 2 의 감정 바 폭 — sentimentDist(건수)로 3구간 비율을 계산한다.
  // 부정 끝 위치(--neg-ratio) 와 (주의 끝=양호 시작) 위치(--pos-bar) —
  // 두 끝 위치 사이가 회색(주의), pos-bar 이후가 초록(양호).
  // 로딩/에러/미응답/미분류 합계 0 인 경우는 '빈 바'처럼 보이도록 회색으로 채운다.
  // ⚠️ 이 컴포넌트는 카드 나열만 책임. CSS 는 minwon.css 의
  // `.kpi-bar`/`.kpi-card`/`.kpi-card__bar` 참조.
  const showSkeleton = loading || error || !kpi
  const distTotal = kpi
    ? kpi.sentimentDist.positive + kpi.sentimentDist.neutral + kpi.sentimentDist.negative
    : 0
  const negPct =
    kpi && !showSkeleton && distTotal > 0
      ? clampPct((kpi.sentimentDist.negative / distTotal) * 100)
      : 0
  const posEndPct =
    kpi && !showSkeleton && distTotal > 0
      ? clampPct(((kpi.sentimentDist.negative + kpi.sentimentDist.neutral) / distTotal) * 100)
      : 100

  return (
    <div className="kpi-bar" role="group" aria-label="민원현황 핵심 지표">
      {CARDS.map((card) => {
        // 1차 표시값 결정 — 로딩 중이거나 에러/미응답이면 placeholder, 아니면 응답값
        const valueText = showSkeleton ? '—' : formatCount(card.valueOf(kpi!))

        return (
          <div key={card.key} className="kpi-card" data-tone={card.iconTone}>
            <span className="kpi-card__icon" aria-hidden="true">
              {card.icon ? <Icon name={card.icon} size={26} /> : <SentimentFaceIcon />}
            </span>
            <div className="kpi-card__body">
              {card.key === 'sentiment' ? (
                // 감정 분류 기준 — 시안 `stat__label--tip` + `G5`(14px, 왼쪽 맞춤).
                <span className="kpi-card__label kpi-card__label--tip">
                  {card.label}
                  <SentimentCriteriaTip size={14} />
                </span>
              ) : (
                <span className="kpi-card__label">{card.label}</span>
              )}
              {card.key === 'sentiment' && (
                <span className="kpi-card__value">
                  <span className="kpi-card__text-serious">부정</span>
                  {showSkeleton ? '—' : formatPct(kpi!.negativityRatio)}
                </span>
              )}
              {card.key === 'total' && <span className="kpi-card__value">{valueText}</span>}
              {(card.key === 'enforcement' || card.key === 'shortage') && (
                  // 시안처럼 값 줄 하나(심각 · 숫자 · 개동)가 `.stat__value` 한 블록이다.
                  <div className="kpi-card__value">
                    <span className="kpi-card__text-serious">심각</span>
                    <span>{valueText}</span>
                    <span className="kpi-card__text-unit">개동</span>
                  </div>
                )}
              {card.key === 'sentiment' &&
                // prettier-ignore — 커스텀 CSS 변수 객체를 printWidth 안에서
                // 한 줄로 합치지 않게 보호. 캐스팅은 AnalysisWeightTab 과 동일.
                // prettier-ignore
                <span
                  className="kpi-card__bar"
                  style={
                    {
                      '--neg-ratio': `${negPct}%`,
                      '--pos-bar': `${posEndPct}%`,
                    } as React.CSSProperties
                  }
                  aria-label={kpi ? `부정 비율 ${negPct}%` : '부정 비율 정보 없음'}
                  role="progressbar"
                  aria-valuenow={kpi ? negPct : undefined}
                  aria-valuemin={0}
                  aria-valuemax={100}
                />}
            </div>
          </div>
        )
      })}
    </div>
  )
}

