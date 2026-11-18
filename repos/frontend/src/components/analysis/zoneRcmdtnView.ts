import type { CommonCodeItem } from '../../api/commonCodes'
import type { HotspotGrade } from '../../api/analysis'
import type {
  FeeRvwGrdCd,
  ZoneRcmdtnCrtrVls,
  ZoneRcmdtnExclLot,
  ZoneRcmdtnLot,
  ZoneRcmdtnShareCrtrVls,
  ZoneRcmdtnShareZone,
  ZoneRcmdtnTrgtTypeSe,
} from '../../api/zoneRcmdtn'
import { GRADE_BADGE_CLASS, GRADE_COLOR_VAR } from './hotspotGrade'

/*
 * 구역추천 화면 표기 헬퍼 (공유주차제 적용 보기는 파일 끝).
 *
 * 숫자 서식 · 배지 클래스 · **필수 문구**를 한곳에 모은다(🔁 2026-10-13 ①·② 는 화면에서 걷혔다 — 상수는 남아 있다) — 결과 패널·보고서·지도가 같은 문장을
 * 쓰므로 세 곳에 복사하면 한쪽만 고쳐져 갈린다.
 *
 * 🔴 **`rcmdtnTypeCd` 라벨은 이 파일에도 없다.** 정본은 공통코드 `ZONE_RCMDTN_TYPE_CD`
 * (`FEE`=요금제적용 · `SHARE`=공유주차제)다. `'FEE' → '요금제적용'` 을 상수로 두면 DB 라벨이 바뀔 때
 * 화면만 조용히 틀려진다. 추천유형 배지를 띄우던 요약(지역 분석 결과)을 화면에서 걷어
 * 라벨 조회(`ZONE_RCMDTN_TYPE_GROUP`)·배지 클래스(`RCMDTN_TYPE_BADGE`)·`rcmdtnTypeNm` 도 함께 지웠다.
 */

/**
 * 🔁 **유형 범위는 `WIDE` 로 확정됐다 — 화면에서 고르지 않는다**(기획 결정 2026-10-12).
 * 후보는 **공영 · 노외(공영) · 복층화 · 노상 · 노상(23년 조사) · 공한지** 전부다.
 *
 * 🔴 **서버 기본값(`NARROW`)에 맡기지 않고 매 요청에 명시해 보낸다.** 두 가지 이유다 —
 *   ① `/zones`(읍면동 목록)와 상세가 **같은 정의**를 봐야 목록의 판정과 상세의 판정이 어긋나지 않는다.
 *   ② 서버 기본값이 나중에 바뀌어도 이 화면의 숫자가 **조용히 달라지지 않는다**.
 *   그리고 적재되는 `anls_param_json.trgtTypeSe` 가 `WIDE` 로 남아, 종전 `NARROW` 로 만든
 *   이력 행과 구분된다(기본값에 맡기면 어느 정의로 만든 결과인지 사후에 가릴 수 없다).
 *
 * ⚠️ **되돌리려면 이 상수 하나만 `NARROW` 로 바꾼다.** 그 대가는 크다 — 실측(2026-10-12)으로
 * `SHARE` 4개 동(연동·서귀동·대정읍·강정동)과 후보 0개소 4개 동(삼도일동·삼도이동·아라이동·오라일동)이
 * 전부 `FEE` 로 넘어오고, **`SHARE` 동이 0개가 된다**.
 * (공유주차제 적용 보기는 이제 `/share` 응답을 써서 이 상수와 무관하다.)
 */
export const DEFAULT_TRGT_TYPE_SE: ZoneRcmdtnTrgtTypeSe = 'WIDE'

/** 판정 불가 배지 — 등급이 아니라 "계산하지 않았다" 는 상태다. */
export const UNDECIDABLE_BADGE = 'badge badge--normal'

/* ── 숫자 서식 ──────────────────────────────────────────── */

/** 정수 천단위 구분. `null` 은 `-`. */
export function fmtInt(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '-'
  return Math.round(value).toLocaleString('ko-KR')
}

/**
 * 소수 서식 — 뒤따르는 0 을 떼어 `300.0 → 300` · `116.8 → 116.8` 로 쓴다.
 * 백엔드가 `BigDecimal` 이라 `300.0` 처럼 오는 값(배정 반경·면수 하한)을 문구에 그대로 넣으면
 * `배정 범위(300.0m)` 가 된다.
 */
export function fmtDecimal(value: number | null | undefined, digits = 1): string {
  if (value == null || Number.isNaN(value)) return '-'
  const fixed = value.toFixed(digits)
  const trimmed = fixed.replace(/\.?0+$/, '')
  return Number(trimmed).toLocaleString('ko-KR', { maximumFractionDigits: digits })
}

/* ── 🔴 필수 문구 (🔁 ①·② 는 2026-10-13 화면에서 걷혔다) ─── */

/*
 * 🔁 **①·② 는 2026-10-13 사용자 요청으로 화면에서 걷혔다.** 상수·함수는 **지우지 않고 남긴다** —
 * 다시 렌더하면 되살아나고, 무엇을 걷었는지가 코드에 남는다.
 * ⚠️ 그 대가(요약 컴포넌트를 지우면서 그쪽 주석에서 이 자리로 옮겨 왔다):
 *   ① 이 사라져 *"효과(감소 건수)를 산출하지 않는다"* 는 말이 **화면 어디에도 없다.** 순위가 효과로
 *      읽힐 수 있다(응답에 그 필드가 없다).
 *   ② 가 사라져 "나머지는 유료화로 줄이기 어렵다" 는 해석이 없다 — 요약이 걷혀
 *      이제 **`배정률` 숫자 자체도 화면에 없다**(응답·보고서에는 남는다).
 *   되살리려면 `NOTE_RANK_ONLY`·`noteAsgn` 을 결과 패널에서 다시 렌더하면 된다(보고서는 지금도 쓴다).
 */

/**
 * ① 순위의 한계 — ~~순위를 띄우는 곳에서는 항상~~ **현재 미사용**(위 🔁 참조).
 * 🔴 효과(감소 건수)를 산출하지 않는다는 사실을 화면이 말하지 않으면 순위가 효과로 읽힌다.
 *
 * ⚠️ 공유주차제 보기에는 이 문구를 띄우지 않는다 — 그 보기의 번호는 **표시 순서**이고
 * (그 안내 `SHARE_NOTE_RNK` 는 걷었다). ①②③ 은 전부 주차장 순위에 붙는 주석이라
 * 읍면동 목록에 띄우면 설명할 대상이 없는 문장이 된다.
 */
export const NOTE_RANK_ONLY =
  "※ 순위는 '어디부터'만 말합니다. 주차장별로 민원이 몇 건 줄어드는지는 산출하지 않습니다."

/**
 * ② 배정률 — 순위가 민원의 몇 %를 설명하는가. **현재 미사용**(위 🔁 참조).
 * 🔴 이 문구가 없으면 순위가 **전체를 설명하는 것처럼** 보인다.
 */
export function noteAsgn(
  cvlcptTot: number,
  cvlcptAsgn: number,
  asgnRt: number | null,
  altmntRngVl: number,
): string {
  return (
    `※ 이 동 민원 ${fmtInt(cvlcptTot)}건 중 ${fmtInt(cvlcptAsgn)}건(${fmtDecimal(asgnRt)}%)만 ` +
    `후보 주차장 ${fmtDecimal(altmntRngVl)}m 안에 있습니다. 나머지는 주차장 유료화로 줄이기 어렵습니다.`
  )
}

/** ③ 배정 범위를 바꾸면 순위가 달라진다 — 숫자는 응답 `crtrVls` 가 준다. */
export function noteAltmntRng(altmntRngVl: number): string {
  return `※ 배정 범위(${fmtDecimal(altmntRngVl)}m)를 바꾸면 순위가 달라집니다.`
}

/*
 * ④ SHARE 오독 방지(`NOTE_SHARE_MISREAD`)는 요약과 함께 화면에서 사라져 상수도 지웠다
 * (보고서 템플릿은 쓰지 않았다). 그 역할은 순위 섹션의 `(참고용)` 표기 · {@link feeTabShareWarn} 이 대신한다.
 * ③(배정 범위)은 순위 섹션 하단 각주로 옮겨 갔다.
 */

/**
 * 후보 0개소 안내 — ⚠️ **빈 화면이 아니라 결론이다**(§0-6).
 *
 * 🔁 종전에는 `NARROW` 일 때 *"'노상·공한지 포함' 으로 바꾸면 후보가 나타날 수 있습니다"* 를
 * 덧붙였는데 걷었다 — **그 칸이 화면에서 없어졌다**(유형은 `WIDE` 고정). 바꿀 수 없는 것을
 * 바꾸라고 하면 안내가 아니라 막다른 길이다.
 * 🔴 `WIDE` 에서 후보 0개소는 **2개 동뿐**이고(실측 2026-10-12), 그 둘은 정말로 유료화할 자리가 없다.
 */
export function emptyLotsText(): string {
  return '유료화할 후보 주차장이 없음 → 공유주차제 검토'
}

/* ── 🔴 탭 · 증가율 ─────────────────────
 *
 * 시안(배포본 `assets/index-BKaygY3k.js`) 구조를 따르면서 **점수는 우리 충족률 산식**을 쓴다
 * (시안의 가중합 40/30/20/10 은 쓰지 않는다).
 */

/** 화면 탭 키. 🔴 `ZoneRcmdtnTypeCd` 와 **같은 문자열이지만 다른 층위**다 — 탭은 보기 모드이고
 *  `rcmdtnTypeCd` 는 읍면동 판정 결과다. 요금제 탭에서 `SHARE` 동을 볼 수 있어야 하므로 묶지 않는다. */
export type ZoneRcmdtnTab = 'FEE' | 'SHARE'

/** 탭 정의 — 🔴 **`요금제 적용` 이 기본**이다(이 화면의 주력이 주차장 순위다). */
export const ZONE_RCMDTN_TABS: { key: ZoneRcmdtnTab; label: string }[] = [
  { key: 'FEE', label: '요금제 적용' },
  { key: 'SHARE', label: '공유주차제 적용' },
]

/** 기본 탭. */
export const DEFAULT_TAB: ZoneRcmdtnTab = 'FEE'

/* ── 증가율 ──────────────────────────────────────── */

/**
 * 증가율 표기 — 🔴 **`null` 은 `—`** 다(0% 로 쓰지 않는다. "변화 없음" 과 "계산하지 않았다" 는 다르다).
 * 양수에는 `+` 를 붙인다(`-28%` 와 `+59.3%` 가 한 열에 섞이므로 부호가 없으면 방향이 안 읽힌다).
 */
export function fmtIncrsRt(rt: number | null | undefined): string {
  if (rt == null || Number.isNaN(rt)) return '—'
  const sign = rt > 0 ? '+' : ''
  return `${sign}${fmtDecimal(rt)}%`
}

/* ── 증가율 색 · 범례 ───────────────────────────────
 *
 * 🔴 **행의 색 점과 범례가 같은 상수를 읽는다.** 두 곳에 색을 적으면 범례가 설명하지 못하는 색이
 * 행에 찍힌다. 그리고 축은 **증가율**이다 — 적합도(충족률)는 읍면동 지표라 행마다 찍으면 전 행이
 * 같은 색이 되어 "주차장별 적합도" 로 오독된다(9-2 와 같은 이유).
 *
 * 🔴 **구간(`45% 이상`/`40~44%`)을 만들지 않는다** — 시안의 그 구간은 목데이터에 맞춘 임의값이고
 * 우리 실데이터는 `−28.0% ~ +59.3%` 다. 근거 없는 임계를 새로 세우지 않고 **부호**로만 가른다.
 */

/** 증가 — 늘어난 쪽. */
export const INCRS_COLOR_UP = 'var(--red-50)'
/** 감소 — 줄어든 쪽. */
export const INCRS_COLOR_DOWN = 'var(--blue-50)'
/** 판정 불가(`null`) — 이전 블록이 하한 미만이라 계산하지 않았다. */
export const INCRS_COLOR_NONE = 'var(--cool-neutral-80)'
/**
 * 변화 없음(`0.0%`) — 증가도 감소도 아니다. 🔴 **새 토큰이 아니라 이미 쓰고 있던 값**이다
 * (`incrsRtColor` 의 `rt === 0` 분기가 본문 색을 그대로 썼다). 범례에 줄이 생겨
 * 상수로 끌어올렸을 뿐이다 — 🔴 범례와 행 점이 **같은 상수**를 읽어야 설명이 어긋나지 않는다.
 */
export const INCRS_COLOR_FLAT = 'var(--text-neutral)'

/**
 * 증가율 색 — **부호만 본다**. 라벨이 아니라 색만 고른다.
 * `0`(변화 없음)은 증가도 감소도 아니라 본문 색을 그대로 쓴다 — 범례의 회색(판정 불가)과 섞지 않는다.
 */
export function incrsRtColor(rt: number | null | undefined): string {
  if (rt == null || Number.isNaN(rt)) return INCRS_COLOR_NONE
  if (rt === 0) return INCRS_COLOR_FLAT
  return rt > 0 ? INCRS_COLOR_UP : INCRS_COLOR_DOWN
}

/**
 * 범례 제목 — 🔴 축이 적합도가 아니라 증가율이다.
 * (사용자 요청 2026-10-21) — 앞에 `(현재)` 를 붙였다.
 */
export const INCRS_LEGEND_TITLE = '(현재) 민원 증가율'
// 화면 범례는 `우선순위`({@link FEE_PRRT_LEGEND_TITLE})로 바뀌었다. 이 제목과
// `incrsLegendRows` 는 다시 쓸 수 있게 남긴다(증가율 색 `incrsRtColor` 는 보고서 막대가 계속 쓴다).

/**
 * 블록 길이 문구 — 🔁 **일수 + 개월 병기**. 예) `121일(약 4개월)` · `15일`.
 *
 * 🔴 **30일 이상일 때만 괄호를 붙인다** — 15일에 "약 1개월" 은 거짓이다(반올림하면 1이 된다).
 * 환산은 `일수 ÷ 30` 반올림이고, "약" 을 붙여 근삿값임을 드러낸다.
 *
 * ⚠️ 일수가 정본이다 — 괄호는 감각을 주기 위한 덧붙임이라 괄호가 없어도 문장이 성립해야 한다.
 */
export function fmtTrendDays(dayCnt: number): string {
  const days = `${fmtInt(dayCnt)}일`
  if (dayCnt < 30) return days
  return `${days}(약 ${fmtInt(Math.round(dayCnt / 30))}개월)`
}

/**
 * 범례 **최대 4줄** (`변화 없음 (0.0%)` 한 줄이 늘었다). 마지막 줄의 두 숫자는 **전부
 * 응답이 준다** — 하한은 `crtrVls.minTrendBase`, 블록 길이는 `crtrVls.trendDayCnt`
 * (**일수**)다. 🔴 상수를 박으면 기간·하한을 바꿀 때 범례만 조용히 틀려진다
 * (종전에는 `3개월` 이 박혀 있었다).
 *
 * 🔴 **`변화 없음` 을 `판정 불가` 앞에 둔다** — 계산된 값(`0`)과 계산하지 않은 값(`null`)의
 * 구분이 범례 **순서로도** 드러나게 한다. 색은 `incrsRtColor` 가 이미 쓰던 `INCRS_COLOR_FLAT`
 * 그대로다(새 토큰을 만들지 않는다).
 *
 * ⚠️ **`판정 불가` 줄은 조건부로 남긴다** — 증가율 `null` 행이 목록·지도에
 * 다시 나오므로 **평소에 보인다**. 조건(`hasNone`)을 지우지 않는 이유는 반대쪽이다: 전 행이
 * 증가율을 낸 동에서는 회색 점이 없고, 그때 이 줄을 두면 **없는 색을 설명하는 범례**가 된다.
 *
 * 🔁 **일수를 적지 않는다**(사용자 결정 2026-10-13). 종전 `이전 90일(약 3개월) 10건 미만` 은
 * 길고, 더 중요하게는 **어디를 세는지 오해를 샀다** — 하한은 **이전 블록**에만 걸리는데
 * 조회 기간 전체로 읽히기 쉽다. `직전 기간` 으로 줄이고 숫자는 보조줄(`기간 최근 3개월`)과
 * {@link noteIncrsRtNull}(표 아래 안내)이 말한다.
 * 🔴 `기간내의 민원 10건 미만` 으로는 **쓰지 않았다** — 실측(이도이동 `최근3개월`)에서
 * 이전 7건·최근 18건으로 **합 25건인데 판정 불가**인 주차장이 6개다. 그 문구면 모순이 보인다.
 */
export function incrsLegendRows(
  minTrendBase: number,
  hasNone = false,
): { key: string; label: string; color: string }[] {
  const rows = [
    { key: 'up', label: '증가', color: INCRS_COLOR_UP },
    { key: 'down', label: '감소', color: INCRS_COLOR_DOWN },
    { key: 'flat', label: '변화 없음 (0.0%)', color: INCRS_COLOR_FLAT },
  ]
  // 🔴 `판정 불가` 줄은 **회색 점이 화면에 실제로 있을 때만** 붙인다.
  //    증가율 `null` 행이 목록에 다시 나오므로 이 줄은 **평소에 보인다** —
  //    호출부가 `lots.some(l => l.cvlcptIncrsRt == null)` 을 그대로 넘긴다(그려지는 색과
  //    범례가 어긋날 수 없다).
  //    ⚠️ 그래도 조건을 지우지 않는 이유는 반대 경우다: 전 행이 증가율을 낸 동에서는 회색 점이
  //    없고, 그때 이 줄을 두면 **없는 색을 설명하는 범례**가 된다(그 동은 여전히 3줄이다).
  if (hasNone) {
    rows.push({
      key: 'none',
      label: `판정 불가 (직전 기간 민원 ${fmtInt(minTrendBase)}건 미만)`,
      color: INCRS_COLOR_NONE,
    })
  }
  return rows
}

/**
 * 🔴 **증가율이 비는 이유를 화면이 말한다.** 안 적으면 `—` 가 "데이터 없음"(= 고장)으로 읽힌다.
 * 숫자는 응답 `crtrVls.minTrendBase`·`crtrVls.trendDayCnt` 가 준다 — 상수를 여기 박으면
 * 백엔드가 바꿀 때 조용히 틀려진다.
 *
 * 🔴 이것은 **"민원이 적어서"** 인 경우다 — **"기간이 짧아서"**
 * ({@link NOTE_TREND_WINDOW_SHORT})와 섞지 않는다. 사용자가 할 조치가 다르다
 * (전자는 어쩔 수 없고, 후자는 기간을 늘리면 된다).
 */
export function noteIncrsRtNull(
  minTrendBase: number,
  trendDayCnt: number | null,
  anlsMthd?: 'RVW_SCR' | null,
): string {
  const prd = trendDayCnt != null ? `직전 ${fmtTrendDays(trendDayCnt)}` : '직전 기간'
  // 새 방식은 증가율이 증가계수로 순서에 들어간다. 🔴 인자가 없으면(구 스냅샷 · 보고서 템플릿) 구 문구다.
  const tail = isFeeRvwScr(anlsMthd)
    ? `순위는 종합지수 순이며, 증가율이 없는 주차장은 증가계수 1(추세 미반영)로 계산합니다.`
    : `순위는 증가율이 아니라 전 구간 배정 민원 순입니다.`
  return (
    `※ 증가율 '—' 는 ${prd} 배정 민원이 ${fmtInt(minTrendBase)}건 미만이어서 ` +
    `계산하지 않은 것입니다 — 분모가 작으면 몇 건 늘어난 것이 수백 %로 보입니다. ` +
    tail
  )
}

/**
 * ⚠️ **조회 기간이 14일 미만일 때** — 반으로 가르면 한 블록이 7일을 못 채워 증가율·추이가 통째로
 * 빈다 ("완전한 달이 2개 미만" 문구는 폐지됐다 — `최근1개월`도 이제
 * 15+15 로 나온다).
 *
 * 🔴 **분모 미달({@link noteIncrsRtNull})과 다른 문구다** — 둘을 같은 문장으로 쓰면
 * *"기간이 짧아서"* 인지 *"민원이 적어서"* 인지 구분되지 않는다. 여기엔 **조치**까지 적는다.
 */
export const NOTE_TREND_WINDOW_SHORT =
  '※ 조회 기간이 14일 미만이라 증가율을 계산하지 않았습니다. 기간을 넓혀 주세요.'

/**
 * 🔁 **증가율의 "이전" 이 반 분할로 내려앉았다** (`warnings` 에
 * `TREND_BFR_FALLBACK`).
 *
 * 평시(1차)에는 **최근 = 선택 기간 전체 · 이전 = 직전 같은 길이**라 보조줄의 `기간 N일` 과
 * 증가율이 보는 구간이 **일치**한다. 🔴 **폴백이면 어긋난다**(기간 243일 ↔ 증가율 121일씩) —
 * 그 사실을 말하지 않으면 사용자가 증가율이 어느 구간을 본 것인지 알 수 없다.
 *
 * 🔴 **드문 경로가 아니다** — `ocrn_dt` 보유 구간이 304일뿐이라 조회 기간이 약 5개월을 넘으면
 * 직전 같은 기간이 데이터 밖으로 나간다(정본 창 243일이 바로 그 경우다).
 *
 * ⚠️ 여기서는 `fmtTrendDays` 를 쓰지 않는다 — 한 문장에 같은 값이 두 번 들어와
 * `(최근 121일(약 4개월) ↔ 이전 121일(약 4개월))` 로 괄호가 두 겹 중첩되면 읽히지 않는다.
 * 개월 병기는 바로 위 보조줄·범례가 이미 하고 있다.
 */
export function noteTrendBfrFallback(trendDayCnt: number | null): string {
  const n = trendDayCnt != null ? `${fmtInt(trendDayCnt)}일` : '같은 기간'
  return (
    '※ 직전 기간의 민원 데이터가 없어 조회 기간을 반으로 갈라 비교했습니다' +
    `(최근 ${n} ↔ 이전 ${n}).`
  )
}


/* ── 주차장 이름 ─────────────────────────────────── */

/**
 * 🔴 **지번형 이름은 그것만으로 못 읽는다.**
 * 실측(후보 155개소) — 이름형 61개소(39.4%) · 지번형 94개소(60.6%)이고 **제주시 도심이 주로 지번형**이다.
 * `이도이동 1994-1` 처럼 끝이 지번(숫자 또는 숫자-숫자)이면 지번형으로 본다.
 */
export function isLotNmJibun(pkltNm: string): boolean {
  return /\d+(-\d+)?\s*$/.test(pkltNm)
}

/**
 * 표·차트의 주차장 표기 — 지번형이면 **면수를 붙여 구분**한다(`이도이동 1994-1 · 28면`).
 * 이름형(`천지연 공영주차장`)은 이미 구분되므로 붙이지 않는다(열이 길어지기만 한다).
 */
export function lotDisplayNm(lot: Pick<ZoneRcmdtnLot, 'pkltNm' | 'pkltCpcty'>): string {
  if (!isLotNmJibun(lot.pkltNm)) return lot.pkltNm
  return `${lot.pkltNm} · ${fmtInt(lot.pkltCpcty)}면`
}

/* ── 🔴 결과 패널(`rl` 리스트) 문구 ─────────────
 *
 * 표가 `rl` 리스트로 바뀌면서 **열이 사라졌다** — 그 숫자를 보조줄(`rl__sub`)에 담는 문구를
 * 여기 한곳에 모은다. 열이 없어졌다고 값을 버리지 않는다.
 */

/** 순위 섹션 제목 (`sim-sec__title`). */
export const RANK_SEC_TITLE = '요금제 적용 추천 주차장 순위'

/**
 * **화면용** 순위 섹션 부제(읍면동·시군구 두 모드 공통). 후보 개소 + 배정 범위만 말한다.
 *
 * 보고서도 이 문구를 쓴다(읍면동 판정·적합도를 걷어 종전 보고서 전용 `rankSecSub` 를 지웠다).
 */
export function rankSecSubSgg(
  lotCnt: number,
  c: ZoneRcmdtnCrtrVls,
  anlsMthd?: 'RVW_SCR' | null,
): string {
  // 새 방식은 우선도(추세 반영 검토지수) 순이다. 🔴 인자가 없으면(구 스냅샷 · 보고서 템플릿) 구 문구다.
  if (isFeeRvwScr(anlsMthd)) {
    return (
      `후보 ${fmtInt(lotCnt)}개소 · 순위는 배정 범위 ${fmtDecimal(c.altmntRngVl)}m 안의 배정 민원으로 낸 ` +
      `종합지수 순입니다.`
    )
  }
  return `후보 ${fmtInt(lotCnt)}개소 · 순위는 배정 범위 ${fmtDecimal(c.altmntRngVl)}m 안의 배정 민원 순입니다.`
}

/**
 * **보고서 전용** 순위 부제. 배정 범위 숫자는 `적용 설정` 상자가 맡아 문장에서 뺀다.
 * 🔴 화면(`ZoneRcmdtnResultPanel`)은 {@link rankSecSubSgg} 그대로다. 구 스냅샷 ①(`legacy` — `vrblAjmt == null`)은
 * 원 함수에 위임해 종전 숫자 문장을 남긴다(상자가 없어 근거 숫자를 잃지 않게).
 */
export function rankSecSubSggForReport(
  lotCnt: number,
  c: ZoneRcmdtnCrtrVls,
  anlsMthd: 'RVW_SCR' | null | undefined,
  legacy: boolean,
): string {
  if (legacy) return rankSecSubSgg(lotCnt, c, anlsMthd)
  if (isFeeRvwScr(anlsMthd)) {
    return `후보 ${fmtInt(lotCnt)}개소 · 순위는 배정 범위 안의 배정 민원으로 낸 종합지수 순입니다.`
  }
  return `후보 ${fmtInt(lotCnt)}개소 · 순위는 배정 범위 안의 배정 민원 순입니다.`
}

/**
 * 분석 기간 세그먼트 키. 숫자 키는 `최근 N개월`, `custom` 만 from~to 입력을 노출한다.
 * 🔴 **이 파일에 둔다** — 설정 패널과 페이지가 함께 쓰므로 패널에 두면
 * `zoneRcmdtnView` ↔ `ZoneRcmdtnSettingPanel` **순환 import** 가 된다(타입 전용이라도 피한다).
 */
export type ZoneRcmdtnPeriodKey = '1' | '3' | '6' | 'custom'

/**
 * 순위 행 보조줄 (`rl__sub`).
 *
 * (사용자 요청 2026-10-21) — **배정 민원 건수만** 남겼다. 지역명(시군구 모드의 `emdNm`) ·
 * 조회 기간(`기간 최근 1개월`/`기간 243일`) · 면수(`31면`)를 걷었고, 그 기간 라벨을 만들던 `prdLabel` 과
 * 페이지의 `prdDays` 도 함께 지웠다. ⚠️ 잃는 것: 시군구 결과에서 행이 **어느 동**인지는 보조줄로
 * 알 수 없다(지도 핀 팝업의 `읍면동` 줄도 2026-10-21 요청으로 걷었다 — 응답 `lots[].emdNm` 에만 남는다). 보고서 템플릿은 이 함수를 쓰지 않는다.
 * 이전 이력: `필요 면수 도달` 표지도 걷었다(요약을 걷어 근거 숫자가 화면에 없다).
 *
 * 🔁 사용자 요청 2026-10-26 — 배정 민원은 행 오른쪽 큰 값(`lotRowVal`)으로 올라갔고, 보조줄은
 * **면수 · 민원 증가율** 을 담는다. 🔴 증가율 `null` 은 `—` 다(`fmtIncrsRt` — 0% 로 쓰지 않는다).
 *
 * 두 갈래다(`anlsMthd`).
 * - **새 방식**(`'RVW_SCR'`): 큰 값이 우선도(추세 반영 검토지수)라 배정 민원이 보조줄로 내려온다 —
 *   `면수 N면 · 민원 N건 · 증가율 X%`(검토지수의 세 재료).
 * - **구 방식**(인자 없음 · 구 스냅샷): 종전 그대로 `면수 N면 · 민원 증가율 X%`.
 */
export function lotRowSub(lot: ZoneRcmdtnLot, anlsMthd?: 'RVW_SCR' | null): string {
  if (isFeeRvwScr(anlsMthd)) {
    return (
      `면수 ${fmtInt(lot.pkltCpcty)}면 · 민원 ${fmtInt(lot.asgnCvlcptCnt)}건 · ` +
      `증가율 ${fmtIncrsRt(lot.cvlcptIncrsRt)}`
    )
  }
  return `면수 ${fmtInt(lot.pkltCpcty)}면 · 민원 증가율 ${fmtIncrsRt(lot.cvlcptIncrsRt)}`
}

/* ── 🔴 요금제 적용 — 우선순위 3단계 (사용자 요청 2026-10-26) ─────────
 *
 * **이 블록은 구 방식 전용이다**(`anlsMthd` 가 없는 응답·보고서 스냅샷). 새 방식
 * (`anlsMthd === 'RVW_SCR'`)의 색은 아래 **우선도 단계** 블록(`feeRvwGrdColor` — 서버 `rvwGrdCd`)이 고른다.
 * 호출부(결과 패널 행 점 · 지도 핀/팝업 · 범례)는 `anlsMthd` 분기 뒤 구 방식에서만 `feePrrt*` 를 부른다.
 *
 * 🔁 **행의 색 점 · 지도 핀 · 범례의 축이 증가율 부호에서 `우선순위` 로 바뀌었다.** 축은 순위와 같은
 * **배정 민원**(`asgnCvlcptCnt`)이고, 라벨·색은 시안(`요금제 적용` 범례)을 따른다 —
 * `높음`(red-50) · `중간`(orange-50) · `낮음`(blue-50).
 *
 * 🔴 **구간은 등간격이다**(사용자 지정) — 시안의 순위 백분위(상위 25%·50%)를 쓰지 않는다.
 * 조회 결과 `lots` 의 배정 민원 **최솟값~최댓값을 3등분**하고, 경계는 정수 건수로 올림한다
 * (`중간` 하한 = ⌈min + w⌉ · `높음` 하한 = ⌈min + 2w⌉ · w = (max − min) / 3). 경계값과 같으면 위 등급이다.
 * ⚠️ 경계가 **결과마다 달라진다**(지역·기간을 바꾸면 같은 건수가 다른 등급이 될 수 있다) — 그래서 범례가
 * 경계 숫자를 함께 적는다. 전 행이 같은 건수면 전부 `높음` 이다(가를 폭이 없다).
 *
 * 🔴 행 점 · 핀 · 범례가 **같은 함수**(`feePrrtBndry` → `feePrrtLvl`)를 읽는다 — 갈리면 범례가 설명하지
 * 못하는 색이 찍힌다(새 방식의 `feeRvwGrdColor` 도 같은 원칙이다). ⚠️ 보고서 템플릿의 막대 색은
 * **구 스냅샷만** 증가율 부호(`incrsRtColor`)다 — 새 방식 보고서는 `feeRvwGrdColor`.
 * 🔁 시안의 `후보 아님`(회색 핀) 줄은 아래 **후보 밖 주차장** 블록이 진다(응답 `exclLots`).
 */

export type FeePrrtLvl = 'HIGH' | 'MID' | 'LOW'

/** 범례 제목 — 🔁 종전 `(현재) 민원 증가율`({@link INCRS_LEGEND_TITLE}). */
export const FEE_PRRT_LEGEND_TITLE = '우선순위'

/** 라벨 · 색 · 팝업 배지의 정본(시안 `u9` 와 같은 값). 높은 등급부터. */
export const FEE_PRRT_LVLS: Record<FeePrrtLvl, { nm: string; color: string; badge: string }> = {
  HIGH: { nm: '높음', color: 'var(--red-50)', badge: 'badge badge--severe' },
  MID: { nm: '중간', color: 'var(--orange-50)', badge: 'badge badge--warn' },
  LOW: { nm: '낮음', color: 'var(--blue-50)', badge: 'badge badge--caution' },
}

/** 등간격 경계 — 둘 다 **배정 민원 건수 하한**(이상)이다. */
export interface FeePrrtBndry {
  midMin: number
  highMin: number
}

/** `lots` 의 배정 민원으로 등간격 경계를 낸다. 후보가 없으면 `null`. */
export function feePrrtBndry(lots: readonly ZoneRcmdtnLot[]): FeePrrtBndry | null {
  if (lots.length === 0) return null
  const cnts = lots.map((l) => l.asgnCvlcptCnt)
  const min = Math.min(...cnts)
  const max = Math.max(...cnts)
  const w = (max - min) / 3
  return { midMin: Math.ceil(min + w), highMin: Math.ceil(min + 2 * w) }
}

/** 배정 민원 → 우선순위. 경계가 없으면(후보 0) `LOW` 지만 그때는 그릴 행이 없다. */
export function feePrrtLvl(cnt: number, b: FeePrrtBndry | null): FeePrrtLvl {
  if (!b) return 'LOW'
  if (cnt >= b.highMin) return 'HIGH'
  if (cnt >= b.midMin) return 'MID'
  return 'LOW'
}

/** 행 점 · 핀의 색. */
export function feePrrtColor(cnt: number, b: FeePrrtBndry | null): string {
  return FEE_PRRT_LVLS[feePrrtLvl(cnt, b)].color
}

/**
 * 범례 줄 — 시안 문구(`높음 (배정 민원 N건 이상)` · `중간 (a~b건)` · `낮음 (N건 미만)`).
 * 🔴 **화면에 실제로 있는 등급만** 낸다 — 없는 색을 설명하지 않는다(증가율 범례의 `판정 불가` 와 같은 원칙).
 */
export function feePrrtLegendRows(
  lots: readonly ZoneRcmdtnLot[],
): { key: FeePrrtLvl; label: string; color: string }[] {
  const b = feePrrtBndry(lots)
  if (!b) return []
  const present = new Set(lots.map((l) => feePrrtLvl(l.asgnCvlcptCnt, b)))
  const midMax = b.highMin - 1
  const ranges: Record<FeePrrtLvl, string> = {
    HIGH: `배정 민원 ${fmtInt(b.highMin)}건 이상`,
    MID: b.midMin === midMax ? `${fmtInt(b.midMin)}건` : `${fmtInt(b.midMin)}~${fmtInt(midMax)}건`,
    LOW: `${fmtInt(b.midMin)}건 미만`,
  }
  return (['HIGH', 'MID', 'LOW'] as const)
    .filter((k) => present.has(k))
    .map((k) => ({
      key: k,
      label: `${FEE_PRRT_LVLS[k].nm} (${ranges[k]})`,
      color: FEE_PRRT_LVLS[k].color,
    }))
}

/* ── 🆕 요금제 적용 — 우선도 단계 (새 방식 `anlsMthd === 'RVW_SCR'`) ─────────
 *
 * 🔴 **단계 판정은 백엔드가 한다**(`lots[].rvwGrdCd` — 순위값과 같은 **우선도** `incrsRfltRvwScr` 원값
 * 100/60). 그래서 목록 위에서 아래로 단계가 최우선 → 후순위로만 내려간다.
 * 프론트는 코드로 **색·라벨만** 고른다 — 점수 비교 코드를 넣지 않는다.
 * 🔁 종전(추세 반영 전 100점 상한 점수 · 심각/경고/주의/양호 · `HotspotGrade` 경계)은 폐기됐다.
 * 색은 집중구역 등급 토큰 중 3개를 **재사용만** 한다(`TOP`→`SEVERE` · `HIGH`→`WARN` · `LOW`→`CAUTION`)
 * — 이름·경계는 집중구역과 다르다.
 * 🔁 2026-10-26 사용자 요청 — 4단계(보통 `MID` 30↑ 포함) → 3단계, 30 경계 삭제(종전 `MID`·`LOW` → `LOW`).
 * 🔴 행 점 · 지도 핀 · 팝업 배지 · 범례가 **같은 함수**를 읽는다 — 갈리면 범례가 설명하지 못하는 색이 찍힌다.
 */

/** 새 방식 여부 — 🔴 판정은 이 한 곳(`anlsMthd === 'RVW_SCR'`). 키가 없으면 구 방식이다. */
export function isFeeRvwScr(anlsMthd: 'RVW_SCR' | null | undefined): boolean {
  return anlsMthd === 'RVW_SCR'
}

/** 우선도 단계 → 집중구역 등급 색 토큰(색·배지 표의 키 — 이름은 {@link FEE_RVW_GRD_NM}). */
const FEE_RVW_GRD_HOTSPOT: Record<FeeRvwGrdCd, HotspotGrade> = {
  TOP: 'SEVERE',
  HIGH: 'WARN',
  LOW: 'CAUTION',
}

/** 우선도 단계 이름. */
const FEE_RVW_GRD_NM: Record<FeeRvwGrdCd, string> = {
  TOP: '높음',
  HIGH: '중간',
  LOW: '낮음',
}

/** 범례·InfoTip 에 세우는 단계 순서(위가 먼저). */
export const FEE_RVW_GRDS: readonly FeeRvwGrdCd[] = ['TOP', 'HIGH', 'LOW']

/**
 * 단계 하한(우선도) — 범례·InfoTip 문구용. 🔴 **판정에 쓰지 않는다**(판정은 서버 `rvwGrdCd`).
 * ⚠️ 백엔드 `ZoneRcmdtnCalculator.PRRT_TOP/HIGH`(100/60)와 같은 값이다 — 응답에 경계가 실리지 않아
 * 이 화면은 숫자를 여기 한 곳에만 둔다. 백엔드를 바꾸면 여기도 함께 바꾼다.
 */
export const FEE_RVW_GRD_MIN_SCR: Record<Exclude<FeeRvwGrdCd, 'LOW'>, number> = {
  TOP: 100,
  HIGH: 60,
}

/** 범례 제목. 구 방식 제목은 {@link FEE_PRRT_LEGEND_TITLE}. */
// 🔁 2026-10-27 사용자 요청 — `우선도 단계`(최우선·우선·후순위) → `종합지수 단계`(높음·중간·낮음).
export const FEE_RVW_LEGEND_TITLE = '종합지수 단계'

/** 단계코드 → 색 토큰 키. 값이 없으면(구 계약·비정상) `NORMAL`(회색)이다. */
function feeRvwHotspot(cd: FeeRvwGrdCd | null | undefined): HotspotGrade {
  return (cd && FEE_RVW_GRD_HOTSPOT[cd]) || 'NORMAL'
}

/** 행 점 · 핀의 색(CSS 변수 표기). */
export function feeRvwGrdColor(cd: FeeRvwGrdCd | null | undefined): string {
  return GRADE_COLOR_VAR[feeRvwHotspot(cd)]
}

/** 단계명(최우선·우선·후순위) — 팝업 배지·행 칩. 값이 없으면 `—`. */
export function feeRvwGrdNm(cd: FeeRvwGrdCd | null | undefined): string {
  return (cd && FEE_RVW_GRD_NM[cd]) || '—'
}

/** 단계 배지 클래스(`badge badge--severe` …). */
export function feeRvwGrdBadge(cd: FeeRvwGrdCd | null | undefined): string {
  return GRADE_BADGE_CLASS[feeRvwHotspot(cd)]
}

/**
 * 범례 3줄 — `최우선 (100 이상)` · `우선 (60 이상)` · `후순위 (60 미만)`.
 * 🔴 **항상 3줄**이다 — 단계 경계가 고정값이라(min-max 3등분이 아니다) 결과와 무관하게 같은 범례가 같은 뜻이다.
 */
export function feeRvwLegendRows(): { key: FeeRvwGrdCd; label: string; color: string }[] {
  return FEE_RVW_GRDS.map((k) => ({
    key: k,
    label:
      k === 'LOW'
        ? `${feeRvwGrdNm(k)} (${FEE_RVW_GRD_MIN_SCR.HIGH} 미만)`
        : `${feeRvwGrdNm(k)} (${FEE_RVW_GRD_MIN_SCR[k]} 이상)`,
    color: feeRvwGrdColor(k),
  }))
}

/**
 * 보고서 부제 1줄 — `우선도 단계 : 최우선 100↑ · 우선 60↑ · 후순위 60 미만`.
 * 🔴 범례({@link feeRvwLegendRows})와 **같은 제목·경계 상수**를 읽는다 — 보고서에는 범례 칸이 없어 한 줄로 싣는다.
 */
export function feeRvwLegendLine(): string {
  const parts = FEE_RVW_GRDS.map((k) =>
    k === 'LOW'
      ? `${feeRvwGrdNm(k)} ${FEE_RVW_GRD_MIN_SCR.HIGH} 미만`
      : `${feeRvwGrdNm(k)} ${FEE_RVW_GRD_MIN_SCR[k]}↑`,
  )
  return `${FEE_RVW_LEGEND_TITLE} : ${parts.join(' · ')}`
}

/**
 * 순위 산정 기준 InfoTip 문장(새 방식) — 🔴 **가중치·기준값은 전부 응답 `crtrVls` 가 준다**(설정 M0603).
 * 기준값 A·B 는 **시군구별**이라 `sggNm`(응답 루트)으로 고른다. 시군구를 모르면 두 시군구 값을 함께 적는다.
 * ⚠️ 키가 없으면(설정 키 배포 전) 그 조각은 숫자를 지어내지 않고 빠진다.
 * 가중치는 합으로 나눈 비율(%)로 적는다 — 평시 합 100 이라 설정값과 같고, 한 행이 꺼져도 실제 반영 비율이 된다.
 */
export function feeRvwScrDesc(c: ZoneRcmdtnCrtrVls, sggNm: string | null | undefined): string[] {
  return [
    ...feeRvwFormulaDesc(c, sggNm),
    `색은 종합지수로 정한 단계(${FEE_RVW_GRD_MIN_SCR.TOP} 이상 높음 · ${FEE_RVW_GRD_MIN_SCR.HIGH} 이상 중간 · ` +
      `그 밑 낮음)입니다. ${FEE_RVW_CRTR_SET_NOTE}`,
    // `종합지수 100 = 이 시군구 상위 10% …` 문장은 조건 없이 뺐다(기준값을 조정·변경하면 사실이 아니다).
  ]
}

/** 기준값을 어디서 바꾸는지 — 화면 InfoTip · 보고서 기준 상자 공용. */
export const FEE_RVW_CRTR_SET_NOTE = '기준값은 시군구별로 설정에서 바꿀 수 있습니다.'

/**
 * {@link feeRvwScrDesc} 의 산식 줄만 — 보고서 부제용. 단계 설명은 보고서에서 `단계 구분 기준` 상자가 맡는다.
 */
export function feeRvwFormulaDesc(c: ZoneRcmdtnCrtrVls, sggNm: string | null | undefined): string[] {
  const w1 = c.feeCvlcptCntWgvl
  const w2 = c.feeCvlcptRtWgvl
  const wSum = (w1 ?? 0) + (w2 ?? 0)
  const wTxt =
    w1 != null && w2 != null && wSum > 0
      ? `민원 강도 ${fmtDecimal((w1 / wSum) * 100)}% + 면당 민원 ${fmtDecimal((w2 / wSum) * 100)}%`
      : '민원 강도 + 면당 민원'

  const crtr = (
    jeju: number | undefined,
    sgp: number | undefined,
    digits: number,
  ): string | null => {
    if (sggNm === '제주시') return jeju != null ? fmtDecimal(jeju, digits) : null
    if (sggNm === '서귀포시') return sgp != null ? fmtDecimal(sgp, digits) : null
    if (jeju == null || sgp == null) return null
    return `제주시 ${fmtDecimal(jeju, digits)} · 서귀포시 ${fmtDecimal(sgp, digits)}`
  }
  const a = crtr(c.feeCvlcptMmCrtrJeju, c.feeCvlcptMmCrtrSeogwipo, 1)
  const b = crtr(c.feeCvlcptRtCrtrJeju, c.feeCvlcptRtCrtrSeogwipo, 1)
  const sggTxt = sggNm === '제주시' || sggNm === '서귀포시' ? `${sggNm} ` : ''

  const lines: string[] = [
    `순위는 종합지수(검토지수 × 증가계수 · 검토지수 = ${wTxt}) 순입니다. 같으면 배정 민원, 면수가 많은 순입니다.`,
  ]
  if (a != null && b != null) {
    lines.push(
      `민원 강도 = 월 배정 민원 ÷ ${a}건 × 100 · 면당 민원 = 100면당 월 배정 민원 ÷ ${b}건 × 100 ` +
        `(${sggTxt}기준값 — 기준값에 닿으면 100점)`,
    )
  }
  const wi = c.feeCvlcptIncrsWgvl
  const up = c.feeCvlcptIncrsRtUplmtVl
  if (wi != null && up != null) {
    const lo = 1 - (wi * Math.min(up, 100)) / 100
    const hi = 1 + (wi * up) / 100
    lines.push(
      `증가계수(${fmtDecimal(lo, 2)}~${fmtDecimal(hi, 2)}) = ` +
        `1 + ${fmtDecimal(wi, 2)} × 민원 증가율(±${fmtDecimal(up)}%에서 자름). ` +
        `증가율은 직전 같은 기간과 비교합니다.`,
    )
  }
  return lines
}

/**
 * **보고서 전용** 산식 줄. 가중치·기준값·증가 계수의 숫자는 `적용 설정` 상자가 맡아
 * 산식 구조만 남긴다. 줄의 수·조건(키가 있을 때만 그 줄)은 {@link feeRvwFormulaDesc} 와 같다.
 * 🔴 화면 InfoTip·엑셀은 원 함수 그대로다. 구 스냅샷 ①(`legacy`)은 원 함수에 위임한다(종전 숫자 문장).
 */
export function feeRvwFormulaDescForReport(
  c: ZoneRcmdtnCrtrVls,
  sggNm: string | null | undefined,
  legacy: boolean,
): string[] {
  if (legacy) return feeRvwFormulaDesc(c, sggNm)
  const hasCrtr = (jeju: number | undefined, sgp: number | undefined): boolean =>
    sggNm === '제주시' ? jeju != null : sggNm === '서귀포시' ? sgp != null : jeju != null && sgp != null
  const sggTxt = sggNm === '제주시' || sggNm === '서귀포시' ? `${sggNm} ` : ''
  const lines: string[] = [
    '순위는 종합지수(검토지수 × 증가계수 · 검토지수 = 민원 강도와 면당 민원의 가중합) 순입니다. ' +
      '같으면 배정 민원, 면수가 많은 순입니다.',
  ]
  if (
    hasCrtr(c.feeCvlcptMmCrtrJeju, c.feeCvlcptMmCrtrSeogwipo) &&
    hasCrtr(c.feeCvlcptRtCrtrJeju, c.feeCvlcptRtCrtrSeogwipo)
  ) {
    lines.push(
      '민원 강도 = 월 배정 민원 ÷ 민원 기준값 × 100 · 면당 민원 = 100면당 월 배정 민원 ÷ 면당 기준값 × 100 ' +
        `(${sggTxt}기준값 — 기준값에 닿으면 100점)`,
    )
  }
  if (c.feeCvlcptIncrsWgvl != null && c.feeCvlcptIncrsRtUplmtVl != null) {
    lines.push(
      '증가계수 = 1 + 증가 가중값 × 민원 증가율(상한값에서 자름). 증가율은 직전 같은 기간과 비교합니다.',
    )
  }
  return lines
}

/* ── 🆕 요금제 적용 — 후보 밖 주차장 · 회색 `P` 핀 ─────────
 *
 * 🔴 **지도 전용이다** — 결과 패널 목록에 행이 없다(시안). 그래서 순위·선택·`fitBounds` 와 무관하다.
 * 색 · 사유 문구 · 범례 줄을 여기 모은다 — 지도 팝업과 범례가 같은 기준값(`swtcTrgtMinCpcty`)을 읽어야
 * `20면 미만` 이 두 곳에서 갈리지 않는다(상수 20 을 박지 않는다).
 */

/**
 * 회색 핀 색 — 시안 값 그대로. ⚠️ `tokens.css` 에 같은 값 토큰이 없다(가까운 `--cool-neutral-70` 은
 * `#989ba2` 라 다른 색이다). divIcon 인라인 스타일에만 쓰여 `var()` 해석도 필요 없다.
 */
export const EXCL_LOT_COLOR = '#9ea1aa'

/**
 * 핀을 **실제로 그릴 수 있는** 후보 밖 주차장만 고른다(좌표 있음).
 * 🔴 지도와 범례의 개수가 **같은 함수**를 읽는다 — 백엔드가 좌표 NULL 을 이미 빼지만, 갈리면 범례가
 * 지도에 없는 핀을 설명하게 된다.
 */
export function drawableExclLots(
  lots: readonly ZoneRcmdtnExclLot[] | null | undefined,
): (ZoneRcmdtnExclLot & { lat: number; lot: number })[] {
  if (!lots) return []
  return lots.filter(
    (l): l is ZoneRcmdtnExclLot & { lat: number; lot: number } => l.lat != null && l.lot != null,
  )
}

/**
 * 팝업의 사유 문구(`후보 아님 — {사유}`). 사유 코드는 하나만 온다.
 * 🔴 `CPCTY_LACK` 에는 **면수 NULL 도** 들어 있다 — 그때는 `면수 미상` 이다(`-면` 으로 쓰지 않는다).
 * `minCpcty` 는 응답 `crtrVls.swtcTrgtMinCpcty`(`BigDecimal` 이라 `20.0` 으로 올 수 있어 `fmtDecimal`).
 */
export function exclRsnText(lot: ZoneRcmdtnExclLot, minCpcty: number | null | undefined): string {
  switch (lot.exclRsnCd) {
    case 'PAID':
      return '유료 주차장'
    case 'CPCTY_LACK':
      if (lot.pkltCpcty == null) return '면수 미상'
      return minCpcty == null
        ? `면수 부족 (${fmtInt(lot.pkltCpcty)}면)`
        : `${fmtDecimal(minCpcty)}면 미만 (${fmtInt(lot.pkltCpcty)}면)`
    case 'PRVT':
      return '민영 주차장'
    case 'TYPE_EXCL':
      return '대상 유형 아님'
    default:
      // 🔴 백엔드가 사유를 늘렸는데 여기만 모르는 경우 — 빈 문구 대신 중립 문구로 둔다.
      return '후보 기준 밖'
  }
}

/**
 * 범례 줄 문구 — `후보 아님 (유료·{N}면 미만 등)`. `등` 은 민영·유형 제외를 덮는다.
 * 기준값이 없으면 숫자를 지어내지 않고 `면수 미달` 로 쓴다.
 */
export function exclLegendLabel(minCpcty: number | null | undefined): string {
  const cpcty = minCpcty == null ? '면수 미달' : `${fmtDecimal(minCpcty)}면 미만`
  return `후보 아님 (유료·${cpcty} 등)`
}

/**
 * 순위 행 오른쪽 큰 값 — 🔴 **순위 기준값**을 보인다.
 * - **새 방식**(`'RVW_SCR'`): **우선도**(`incrsRfltRvwScr` — 서버 순서의 기준이자 단계 판정값).
 *   상한이 없어 100 을 넘는다(100↑ = 최우선 — 행 점 색·범례가 말한다). 서버가 소수 1자리로 준다.
 *   값이 없으면 `—`.
 * - **구 방식**(인자 없음 · 구 스냅샷): 배정 민원 건수 = 순위 기준(사용자 요청 2026-10-26).
 */
export function lotRowVal(lot: ZoneRcmdtnLot, anlsMthd?: 'RVW_SCR' | null): string {
  if (isFeeRvwScr(anlsMthd)) {
    const v = lot.incrsRfltRvwScr
    return v == null || Number.isNaN(v) ? '—' : `종합지수 ${fmtDecimal(v)}`
  }
  return `${fmtInt(lot.asgnCvlcptCnt)}건`
}

/* ── 🔴 공유주차제 적용 보기 ─────────────
 *
 * 🔁 **이 보기의 내용물이 바뀌었다.** 종전에는 `/zones` 응답에서 요금제 부적합(`rcmdtnTypeCd='SHARE'`)
 * 동을 번호·핀 없이 나열했는데, 이제 **`/share` 응답**(공유주차 검토 후보 읍면동)을 그린다.
 * 옛 문구(`NOTE_SHARE_TAB`·`NOTE_SHARE_TAB_EMPTY`·`shareZoneRsn`)는 쓰임이 사라져 지웠다.
 *
 * 🔴 **이 화면이 답하는 질문은 "주차 민원 기준으로, 공유주차제를 검토해 볼 만한 읍면동은 어디인가"**
 * 하나다. 답하지 못하는 것(공유할 부설주차장이 있는가 · 어느 시간대인가 · 민원이 얼마나 줄어드는가 ·
 * 읍면동 안의 어디인가)은 아래 필수 문구가 화면에 적는다 — 그래서 이름이 "검토 후보" 다.
 * "적합 지역"·"필요 지역"·"추천 구역" 이라고 쓰지 않는다.
 *
 * 🔁 **2026-10-13 — 두 조건을 `검토 지수` 하나로 보여 주고 세 목록을 한 목록으로 합쳤다.**
 * 종전에는 후보 목록 + 접힌 구역 2개(`CVLCPT_ONLY` · `UNABLE`)였다. 이제 응답의 `rvwScr`
 * (= 두 조건 각각의 "기준 대비 배수" 중 작은 값) 순으로 **한 목록**이다.
 * 🔴 필드명은 `rvwScr` 이지만 **화면 표기는 "검토 지수"** 다(표준사전에 `지수` 가 없어 `점수 SCR` 을 썼다).
 *
 * 🔁 **2026-10-13 사용자 요청 — 화면의 검토 등급을 지수 3단계로 바꿨다**(`매우 높음`·`높음`·`보통`).
 * 🔁 셋째 등급의 이름은 처음 `낮음` 이었다가 같은 날 `보통` 으로 바뀌었다(사용자 요청). 코드값 `LOW` 는 그대로다.
 * 🔁 **2026-10-26 사용자 요청 — 이름이 `우선도 단계`(`최우선`·`우선`·`후순위`)가 됐다.** 이름만 바꿨고
 * 🔴 **판정은 여전히 검토 지수(`rvwScr`)다** — 우선도(`incrsRfltRvwScr`)로 자르면 후보(깃발)인데
 * 후순위 · 후보가 아닌데 우선인 행이 생겨 깃발과 단계가 다른 말을 한다.
 * ⚠️ 대가로 목록 순서(우선도 하나)와 단계가 어긋나 색 점이 위아래로 섞일 수 있다.
 * 종전에는 공통코드 `SHRN_RVW_GRD_CD` 의 `cd_nm`(5종)을 행 배지·범례·팝업의 등급 이름으로 썼다.
 * 이제 그 이름은 **화면 등급으로 쓰지 않는다** — 사용자가 지정한 표시 등급이라 아래 상수
 * ({@link SHARE_LVLS})가 정본이고, 색·범례·팝업 배지·산출 설명이 전부 그 표를 읽는다.
 * 행의 등급 배지와 주의 배지(`cutnCds`) · 시군구명도 함께 걷었다(색 점이 등급을 말한다).
 * ⚠️ 공통코드는 **`UNABLE` 의 라벨 하나**에만 남았다({@link shareGrdNm}) — 그 행은 지수가 없어
 * 3단계 어디에도 들지 않고, 왜 값이 비었는지는 그 라벨이 말한다. 소스에 그 낱말을 적지 않는다.
 */

/** 공통코드 그룹 — 🔁 이제 `UNABLE` 라벨의 출처로만 쓴다(화면 등급 이름은 {@link SHARE_LVLS}). */
export const SHARE_GRD_GROUP = 'SHRN_RVW_GRD_CD'

/**
 * 공통코드 `SHRN_RVW_GRD_CD` 의 라벨 — 🔁 **`UNABLE` 한 가지에만 쓴다**(행 배지 · 더 보기 버튼 · 목록 아래 안내).
 * 목록을 못 받았거나 그 코드가 없으면 **코드값 원문**을 돌려준다(한글 라벨을 추측해 박지 않는다).
 */
export function shareGrdNm(codes: CommonCodeItem[], cd: string): string {
  return codes.find((c) => c.cdVl === cd)?.cdNm ?? cd
}

/** 화면의 검토 등급 — 🔴 서버의 `rvwGrdCd`(5종)와 **다른 층위**다. 검토 지수 하나로 정한다. */
export type ZoneRcmdtnShareLvl = 'VERY_HIGH' | 'HIGH' | 'LOW'

/**
 * `높음` 의 하한 = 기준선. 🔴 설정값이 아니라 **지수의 정의**다 — 지수가 "기준 대비 배수" 라 기준선이 곧 1 이다.
 * `매우 높음` 의 하한은 여기 없다 — 응답 `crtrVls.coreMltp` 가 준다(숫자를 박지 않는다).
 */
export const SHARE_LVL_BASE = 1

/**
 * 검토 등급 표 — 라벨 · 색 · (지도 팝업용) 배지 클래스. 🔴 **목록의 점 · 지도 깃발 · 범례 · 팝업이 이 표
 * 하나를 읽는다**(두 곳에 적으면 갈린다). 순서가 곧 범례 순서다.
 */
export const SHARE_LVLS: {
  lvl: ZoneRcmdtnShareLvl
  label: string
  color: string
  badge: string
}[] = [
  { lvl: 'VERY_HIGH', label: '높음', color: 'var(--red-50)', badge: 'badge badge--severe' },
  { lvl: 'HIGH', label: '중간', color: 'var(--orange-50)', badge: 'badge badge--warn' },
  { lvl: 'LOW', label: '낮음', color: 'var(--blue-50)', badge: 'badge badge--caution' },
]

/** 등급 없음(지수 없음 = `UNABLE` · 구 응답)의 색 — 등급색으로 오독되지 않게 중립 회색이다. */
export const SHARE_LVL_COLOR_NONE = 'var(--cool-neutral-80)'

/**
 * 검토 지수 → 검토 등급. `crtrVls.coreMltp` 이상 `매우 높음` · {@link SHARE_LVL_BASE} 이상 `높음` · 그 밑 `보통`.
 *
 * 🔴 **서버 판정과 어긋나지 않는다** — 서버가 지수를 소수 2자리로 **버려서** 주므로
 * `rvwScr >= coreMltp ⇔ rvwGrdCd === 'CORE'` · `rvwScr >= 1 ⇔ rvwTrgtYn === 'Y'` 가 보장된다
 * (반올림이었다면 `1.996` 이 `2.00` 으로 와서 갈렸을 것이다). 그래서 등급 코드를 다시 보지 않고 지수만 본다.
 *
 * 🔴 지수가 없으면(`null` = 공영 면수 0 · `undefined` = 이 필드가 없는 구 응답) **등급이 없다**(`null`) —
 * 0 으로 보고 `보통` 을 주면 계산하지 않은 읍면동이 등급을 얻는다. 기준(`c`)이 없을 때도 같다.
 */
export function shareLvl(
  rvwScr: number | null | undefined,
  c: Pick<ZoneRcmdtnShareCrtrVls, 'coreMltp'> | null | undefined,
): ZoneRcmdtnShareLvl | null {
  if (rvwScr == null || Number.isNaN(rvwScr) || !c) return null
  if (rvwScr >= c.coreMltp) return 'VERY_HIGH'
  return rvwScr >= SHARE_LVL_BASE ? 'HIGH' : 'LOW'
}

/**
 * 🔁 2026-10-27 사용자 요청 — 행·깃발·팝업·보고서의 단계는 **종합지수**(`incrsRfltRvwScr`)로 정한다.
 * 경계는 종전과 같다(`crtrVls.coreMltp` 이상 높음 · {@link SHARE_LVL_BASE} 이상 중간 · 그 밑 낮음).
 * 🔴 `단계 = 검토 지수` 규칙을 폐기한 대가 — 후보(깃발 · `rvwTrgtYn`)는 여전히 검토 지수 1 이상이라
 * **깃발이 `낮음` 이거나 후보가 아닌 행이 `중간`** 일 수 있다. 대신 목록 순서(종합지수)와 단계가 어긋나지 않는다.
 * ⚠️ 키가 없으면(`undefined` — 종합지수 이전 보고서 스냅샷) 그때의 순서 기준인 검토 지수로 판정한다.
 * `null`(`UNABLE`)은 등급 없음이다.
 */
export function shareZoneLvl(
  z: Pick<ZoneRcmdtnShareZone, 'rvwScr' | 'incrsRfltRvwScr'>,
  c: Pick<ZoneRcmdtnShareCrtrVls, 'coreMltp'> | null | undefined,
): ZoneRcmdtnShareLvl | null {
  return shareLvl(z.incrsRfltRvwScr !== undefined ? z.incrsRfltRvwScr : z.rvwScr, c)
}

function shareLvlRow(lvl: ZoneRcmdtnShareLvl | null) {
  return SHARE_LVLS.find((r) => r.lvl === lvl)
}

/** 등급 라벨. 등급이 없으면 빈 문자열이다(호출부가 배지를 그리지 않는다). */
export function shareLvlNm(lvl: ZoneRcmdtnShareLvl | null): string {
  return shareLvlRow(lvl)?.label ?? ''
}

/** 등급 색 — 목록의 점과 지도 깃발. 등급이 없으면 중립 회색이다. */
export function shareLvlColor(lvl: ZoneRcmdtnShareLvl | null): string {
  return shareLvlRow(lvl)?.color ?? SHARE_LVL_COLOR_NONE
}

/** 등급 배지 클래스 — 지도 팝업 전용(목록 행에는 등급 배지가 없다). 색 표와 같은 계열이다. */
export function shareLvlBadge(lvl: ZoneRcmdtnShareLvl | null): string {
  return shareLvlRow(lvl)?.badge ?? UNDECIDABLE_BADGE
}

/** 범례 제목. */
// 🔁 2026-10-27 사용자 요청 — `우선도 단계` → `우선지수 단계` → `종합지수 단계`(높음·중간·낮음 · 요금제 범례와 같은 말).
export const SHARE_LEGEND_TITLE = '종합지수 단계'

/**
 * 범례 3줄 — `최우선 (2 이상)` · `우선 (1 이상 2 미만)` · `후순위 (1 미만)`.
 * 사용자 요청 — 괄호 안의 `지수` 낱말을 뺐다(요금제 범례 `최우선 (100 이상)` 과 같은 모양).
 * 🔴 괄호 안의 `2` 는 응답 `crtrVls.coreMltp` 다 — 백엔드가 배수를 바꾸면 범례가 따라간다.
 * ⚠️ `보통` 은 지도에 깃발이 없다(깃발은 지수 1 이상만 찍는다) — 그 줄은 결과 패널 목록의 색 점을 설명한다.
 */
export function shareLegendRows(
  c: Pick<ZoneRcmdtnShareCrtrVls, 'coreMltp'>,
): { key: ZoneRcmdtnShareLvl; label: string; color: string }[] {
  const core = fmtDecimal(c.coreMltp, 2)
  const rng: Record<ZoneRcmdtnShareLvl, string> = {
    VERY_HIGH: `${core} 이상`,
    HIGH: `${SHARE_LVL_BASE} 이상 ${core} 미만`,
    LOW: `${SHARE_LVL_BASE} 미만`,
  }
  return SHARE_LVLS.map((r) => ({
    key: r.lvl,
    label: `${r.label} (${rng[r.lvl]})`,
    color: r.color,
  }))
}

/** 조회 기간에 민원이 0건이라는 경고 코드(`warnings`) — 🔴 이때는 목록도 범례도 걷는다(판정한 것처럼 보인다). */
export const SHARE_WARN_NO_DATA = 'NO_CVLCPT_IN_RANGE'

/**
 * 결과 섹션 제목 — 🔁 **사용자 지정 문구**다(2026-10-13. 종전 `공유주차 검토 후보 읍면동`).
 * 🔁 처음에는 끝에 `순위` 가 붙어 있었으나 걷었다 — "번호는 우선순위가 아니다" 안내와 어긋나고,
 * 목록이 지수 1 미만·판정 불가 읍면동까지 이어지기 때문이다.
 * ⚠️ "추천" 은 지표(민원 접수 건수 기준의 검토 지수)보다 강한 말이다 — 그 간극은 안내 문구가 메운다.
 *    부설주차장 미확인(`SHARE_NOTE_SCOPE`)은 목록 아래 **상시 노출**로 남고, 번호·지수는
 *    우선순위 아님(`SHARE_NOTE_RNK`·`SHARE_NOTE_SCR`)은 제목 옆 InfoTip 으로 옮겼다. 둘 다 걷지 않는다.
 */
export const SHARE_SEC_TITLE = '공유주차제 적용 추천 읍면동'

/**
 * 검토 지수 표기 — 🔴 **소수 2자리 고정**이다(`5.05` · `1.00`). `fmtDecimal` 처럼 꼬리 0 을 떼면
 * 기준선 위의 `1.00` 이 `1` 이 되어, 한 열에서 자릿수가 들쭉날쭉해지고 "딱 1" 인지 "1.0x" 인지 흐려진다.
 * 서버가 이미 2자리로 **버린** 값을 주므로 여기서는 자릿수만 맞춘다(다시 계산하지 않는다).
 *
 * 🔴 `null`(`UNABLE` — 공영 면수 0)은 `—` 다. 0 으로 쓰면 "기준에 한참 못 미친다" 로 읽힌다.
 * ⚠️ `undefined` 도 같게 다룬다 — 백엔드가 이 필드를 배포하기 전에는 키 자체가 없다.
 */
export function fmtRvwScr(rvwScr: number | null | undefined): string {
  if (rvwScr == null || Number.isNaN(rvwScr)) return '—'
  return rvwScr.toFixed(2)
}

/**
 * 제목 밑 산출 설명 — **검토 지수가 무엇을 무엇으로 나눈 값인가**를 2~3줄로 적는다.
 * 🔁 종전 부제(`판정 기준 : …`)를 대체한다 — 같은 기준값이 산식 안에 들어 있다.
 *
 * 🔴 **숫자는 전부 응답 `crtrVls` 가 준다**(두 기준값 · `매우 높음` 배수 · 개월 수). 50·7·2 를 박지 않는다 —
 * `RECOMMEND` 분석변수가 바뀌면 문구만 조용히 틀려진다.
 * ⚠️ 기준선 `1`({@link SHARE_LVL_BASE})만은 상수다 — 설정이 아니라 지수의 **정의**다.
 *
 */
export function shareScrDesc(c: ZoneRcmdtnShareCrtrVls): string[] {
  // 🔁 2026-10-13 사용자 요청 — 등급 줄을 걷고(등급은 범례가 말한다) 두 정의를 한 줄씩 나눠 적는다.
  //    패널이 실행 뒤에만 뜨므로 기준값 없는(실행 전) 문장은 없다.
  return [
    `검토 지수 = (월평균 주차 민원 ÷ ${fmtDecimal(c.cvlcptMmCrtrVl)}건)과 ` +
      `(공영 100면당 월 민원 ÷ ${fmtDecimal(c.cvlcptRtCrtrVl, 2)}건) 중 작은 값`,
    // 개월 수는 소수다(달별 조회 비율 합, 1.005376 → `1.01`). fmtInt 면 `1개월` 로 오독된다.
    `월평균 주차 민원 = 조회 기간 주차 민원 ÷ ${fmtDecimal(c.months, 2)}개월`,
    '개월 수 = 조회 기간이 걸친 달마다 (조회한 일수 ÷ 그 달의 일수)를 더한 값 (온전한 달은 1개월)',
    '100면당 월 민원 = 월평균 민원 ÷ 공영 주차면수 × 100',
  ]
}

/**
 * **보고서 전용** {@link shareScrDesc}. 두 기준값(설정)은 `적용 설정` 상자가 맡아 숫자를 뺀다.
 * 개월 수는 설정이 아니라 조회 기간에서 나온 값이라 그대로 둔다. 구 스냅샷 ①(`legacy`)은 원 함수에 위임한다.
 */
export function shareScrDescForReport(c: ZoneRcmdtnShareCrtrVls, legacy: boolean): string[] {
  if (legacy) return shareScrDesc(c)
  return [
    '검토 지수 = (월평균 주차 민원 ÷ 민원 월기준값)과 (공영 100면당 월 민원 ÷ 면당 기준값) 중 작은 값',
    `월평균 주차 민원 = 조회 기간 주차 민원 ÷ ${fmtDecimal(c.months, 2)}개월`,
    '개월 수 = 조회 기간이 걸친 달마다 (조회한 일수 ÷ 그 달의 일수)를 더한 값 (온전한 달은 1개월)',
    '100면당 월 민원 = 월평균 민원 ÷ 공영 주차면수 × 100',
  ]
}

/**
 * **목록 순서의 정의** 를 InfoTip·보고서에 적는다.
 *
 * 🔴 계수 범위는 상수로 적지 않는다 — 응답 `crtrVls.incrsWgvl`(w) · `crtrVls.incrsRtUplmtVl`(상한 %)로 만든다
 * (설정 M0603 을 바꾸면 문구가 따라가야 한다). 하한 = `1 − w × min(상한, 100) / 100`(증가율은 −100% 아래로
 * 내려가지 않는다) · 상한 = `1 + w × 상한 / 100` → 기본 0.5 / 100% 이면 `0.5~1.5`.
 * ⚠️ 두 키가 없으면(이 작업 이전에 저장된 보고서 스냅샷) **빈 배열**이다 — 그 스냅샷의 순서는 종전 규칙
 * (검토 지수 순)이라 이 문장이 거짓이 된다. 분모 하한(`minTrendBase`)이 없으면 그 꼬리만 뺀다.
 */
export function shareTrendDesc(
  c: Pick<ZoneRcmdtnShareCrtrVls, 'incrsWgvl' | 'incrsRtUplmtVl' | 'minTrendBase'>,
): string[] {
  const w = c.incrsWgvl
  const up = c.incrsRtUplmtVl
  if (w == null || up == null || Number.isNaN(w) || Number.isNaN(up)) return []
  const lo = 1 - (w * Math.min(up, 100)) / 100
  const hi = 1 + (w * up) / 100
  const base =
    c.minTrendBase != null
      ? ` 직전 기간 민원이 ${fmtInt(c.minTrendBase)}건 미만이면 증가율을 계산하지 않고 계수 1입니다.`
      : ''
  return [
    // 서버가 "후보 먼저" 키를 걷어 순서가 우선도 하나다. ⚠️ 과거 한 시기에 저장된 보고서
    //    스냅샷은 후보 먼저 순서인데 구분할 표식이 없어 이 문장이 그대로 실린다(알려진 한계).
    `순서는 종합지수 = 검토 지수 × 증가계수(${fmtDecimal(lo, 2)}~${fmtDecimal(hi, 2)}) 높은 순입니다. ` +
      '종합지수 단계도 종합지수로 정합니다.',
    `증가계수 = 1 + ${fmtDecimal(w, 2)} × 민원 증가율(±${fmtDecimal(up)}%에서 자름) — ` +
      `민원 증가율은 조회 기간과 직전 같은 기간의 민원 건수 비교입니다.${base}`,
  ]
}

/**
 * **보고서 전용** {@link shareTrendDesc}. 증가 가중값·상한(설정)과 그로 낸 계수 범위는
 * `적용 설정` 상자가 맡아 뺀다. 두 키가 없으면 빈 배열인 것·분모 하한 꼬리(설정이 아니다)는 원 함수와 같다.
 * 구 스냅샷 ①(`legacy`)은 원 함수에 위임한다.
 */
export function shareTrendDescForReport(
  c: Pick<ZoneRcmdtnShareCrtrVls, 'incrsWgvl' | 'incrsRtUplmtVl' | 'minTrendBase'>,
  legacy: boolean,
): string[] {
  if (legacy) return shareTrendDesc(c)
  const w = c.incrsWgvl
  const up = c.incrsRtUplmtVl
  if (w == null || up == null || Number.isNaN(w) || Number.isNaN(up)) return []
  const base =
    c.minTrendBase != null
      ? ` 직전 기간 민원이 ${fmtInt(c.minTrendBase)}건 미만이면 증가율을 계산하지 않고 계수 1입니다.`
      : ''
  return [
    '순서는 종합지수 = 검토 지수 × 증가계수 높은 순입니다. 종합지수 단계도 종합지수로 정합니다.',
    '증가계수 = 1 + 증가 가중값 × 민원 증가율(상한값에서 자름) — ' +
      `민원 증가율은 조회 기간과 직전 같은 기간의 민원 건수 비교입니다.${base}`,
  ]
}

/** `YYYYMMDD` → `YYYY-MM-DD`. 형식이 다르면 원문을 그대로 돌려준다(추측해 자르지 않는다). */
export function fmtYmd(ymd: string | null | undefined): string {
  if (!ymd) return '-'
  return /^\d{8}$/.test(ymd) ? `${ymd.slice(0, 4)}-${ymd.slice(4, 6)}-${ymd.slice(6, 8)}` : ymd
}

/**
 * 행의 큰 값 — **우선도**(`incrsRfltRvwScr` — 목록 순서의 기준이라서다).
 * 🔁 2026-10-26 사용자 요청 — 화면 표기를 `추세 반영 지수` → `우선도` 로 바꿨다(요금제 보기와 같은 말).
 * 🔁 2026-10-27 사용자 요청 — 다시 `우선지수` 로 바꿨다가, 같은 날 요금제 보기와 함께 `종합지수` 로 통일했다.
 * 숫자 형식은 검토 지수와 같은 2자리(`fmtRvwScr`). 🔴 값이 없으면(`UNABLE` · 구 계약) 라벨 없이 `—` 만 둔다.
 * ⚠️ 등급(색 점)은 이 값이 아니라 `rvwScr` 다 — 보조줄 첫 조각이 그 검토 지수를 함께 보인다.
 */
export function shareRowVal(incrsRfltRvwScr: number | null | undefined): string {
  if (incrsRfltRvwScr == null || Number.isNaN(incrsRfltRvwScr)) return '—'
  return `종합지수 ${fmtRvwScr(incrsRfltRvwScr)}`
}

/**
 * 행 보조줄 — `100면당 민원 X건 · 민원 증가율 ±Y%`.
 * 검토 지수의 재료(`월평균` · `100면당` · `공영 면수`)를 걷었다(사용자 요청 — 줄이 너무 길다).
 * 🔁 2026-10-27 사용자 요청 — `검토 지수 X` 조각을 걷고 `100면당 민원` 을 되살렸다. 검토 지수는 지도 팝업·InfoTip 이 말한다.
 * 🔴 `null`(`UNABLE` 의 100면당 · 증가율 분모·기간 하한 미달)·부재(구 응답)도 조각을 빼지 않고 `—` 다 — 행마다
 * 조각 수가 달라지면 열이 어긋난다(`fmtIncrsRt` 관례 · 요금제 행 `민원 증가율 —` 와 같다).
 */
export function shareRowSub(z: Pick<ZoneRcmdtnShareZone, 'cvlcptRt' | 'cvlcptIncrsRt'>): string {
  const rt = z.cvlcptRt != null && !Number.isNaN(z.cvlcptRt) ? `${fmtDecimal(z.cvlcptRt, 2)}건` : '—'
  return `100면당 민원 ${rt} · 민원 증가율 ${fmtIncrsRt(z.cvlcptIncrsRt)}`
}

/** 팝업의 검토 지수 한 줄에 쓰는 이름 — 🔴 화면 표기는 필드명(`rvwScr`)이 아니라 이 말이다. */
export const SHARE_SCR_LABEL = '검토 지수'

/**
 * `UNABLE` 행의 안내 — 목록 아래 한 줄. 🔴 지수의 `—` 가 "자료 없음"(= 고장)으로 읽히지 않게 이유를 적는다.
 * 등급 이름은 공통코드 라벨(`unableNm`)이다.
 */
export function shareUnableNote(unableNm: string, unableCnt: number): string {
  return (
    `※ ${unableNm} ${fmtInt(unableCnt)}곳은 읍면동 안에 등록된 공영주차장이 없어 ` +
    '100면당 민원과 검토 지수를 계산하지 못했습니다.'
  )
}

/** 조회 기간에 민원이 0건(`NO_CVLCPT_IN_RANGE`) — 🔴 빈 화면 대신 자료 구간을 알려 준다. */
export function shareNoDataText(dataBgngYmd: string | null, dataEndYmd: string | null): string {
  const base = '선택한 기간에 민원 자료가 없습니다'
  // 자료 구간을 모르면 `-~-` 를 만들지 않는다.
  if (!dataBgngYmd || !dataEndYmd) return `${base}.`
  return `${base}(자료 구간: ${fmtYmd(dataBgngYmd)}~${fmtYmd(dataEndYmd)})`
}

/**
 * 후보 0곳 — ⚠️ **빈 화면이 아니라 결론이다.**
 * 분기는 `sggNm` 유무 하나다. 시군구를 골랐으면 그 시군구 기준 문구로 끝낸다
 * (도 전체 후보 수 꼬리는 걷었다 — 응답 `smry` 는 계약 그대로 두되 화면·PDF·엑셀은 읽지 않는다).
 */
export function shareEmptyCandText(sggNm: string | null): string {
  if (sggNm) return `${sggNm}에는 두 기준을 모두 넘는 읍면동이 없습니다.`
  return '이 기간에는 두 기준을 모두 넘는 읍면동이 없습니다 — 공유주차 검토 후보 0곳.'
}

/* 🔴 안내 문구 — 결과 패널 하단에 **항상** 보인다. 이 화면이 답하지 못하는 것을 화면이 직접
 *    말하지 않으면 "검토 후보" 가 "적합 지역" 으로 읽힌다.
 *    🔁 2026-10-13 사용자 요청 — 5개 중 ②(시간대) · ④(공한지 제외 수) ·
 *    ⑤(요금제 `SHARE` 표기와 다름)를 걷었다. 남은 것은 ①(부설주차장) · ③(번호) 과
 *    검토 지수의 한계(`SHARE_NOTE_SCR`) 세 문장이다.
 *    ③(번호는 우선순위가 아님)을 걷었다. 이름이 `우선도`·`최우선` 이 되어 정면으로 어긋난다. */

/** ① 부설주차장 — 공유할 물량이 있는지는 보지 않았다. */
export const SHARE_NOTE_SCOPE =
  '※ 이 목록은 주차 민원 접수 건수 기준의 검토 후보입니다. 공유할 부설주차장이 있는지는 확인하지 않았습니다.'
/**
 * ③-2 우선도의 뜻 — 사용자 요청: 종전 "적합도나 사업 우선순위가 아닙니다" 부정문을
 * 지수가 무엇을 수치화했는지 풀어 쓰는 문장으로 바꿨다(적합도 단서는 사용자 판단으로 넣지 않는다).
 */
export const SHARE_NOTE_SCR =
  '※ 종합지수는 주차 민원이 많고, 공영주차면에 비해 민원이 많으며, 최근 민원이 늘어날수록 커지도록 수치화한 값입니다.'

/**
 * InfoTip 팝업 항목 — 문장 정본(이 파일 · 보고서와 공유)의 앞 `※` 만 뗀다(목록 점과 겹친다).
 * 🔴 상수 값은 고치지 않는다 — `lib/analysis/zoneRcmdtnReportTemplate.ts` 가 `※` 째로 쓴다.
 * `ZoneRcmdtnResultPanel` 지역 함수에서 옮겼다(요금제 순위 · 공유주차 두 패널이 쓴다).
 */
export function stripNoteMark(note: string): string {
  return note.replace(/^※\s*/, '')
}
