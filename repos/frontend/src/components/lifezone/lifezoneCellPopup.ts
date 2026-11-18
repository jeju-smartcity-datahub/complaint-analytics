import type { LifezoneDtl, LifezoneGrade } from '../../api/lifezone'
import { GRADE_BADGE_CLASS } from './lifezoneGrade'
import { escapeHtml } from '../../lib/report/reportTemplate'

/*
 * 격자 셀 hover 팝업의 HTML 조립.
 *
 * 🔴 **컴포넌트(`LifezoneMap.tsx`)가 아니라 별도 모듈에 둔다.** 두 가지 이유다.
 *   1. Leaflet 은 팝업 내용을 **HTML 문자열**로 받으므로 이 함수가 XSS 경계다 —
 *      순수 함수로 떼어 두어야 브라우저 없이 문자열만으로 이스케이프를 확인할 수 있다.
 *   2. `.tsx` 에서 컴포넌트가 아닌 것을 export 하면 fast-refresh 가 깨진다
 *      (`react-refresh/only-export-components`). 집중구역이 등급 매핑 모듈에
 *      `buildLegendRows` 를 둔 것과 같은 배치다.
 *
 * 🔴 **여기에 임계값·등급 문구·유형명을 적지 않는다.** 등급명은 응답 `gradeCriteria[].gradeNm`,
 * 유형명은 `dtl[].cvlcptTypeNm`, 단위는 응답 루트 `unit` 이 정본이며 이 파일은 배치만 한다.
 */

/** 셀 팝업 한 건을 만드는 데 필요한 값. 셀·순위·응답 루트에 흩어져 있어 한 곳에 모은다. */
export interface CellPopupInput {
  /** 등급코드 — 배지 클래스를 고르는 데 쓴다. */
  grade: LifezoneGrade
  /** 등급명. 응답 `gradeCriteria[].gradeNm` 이 정본이며 없을 때만 `GRADE_LABEL` 로 떨어진다. */
  gradeNm: string
  /** 구역명. 🔴 DB 읍면동명 + VWorld POI 응답이라 **반드시 이스케이프**한다. */
  cntrPoiNm: string
  /** 분석값. */
  anlsVl: number
  /** 값의 단위(`건`/`%`/`점`). 응답 루트의 `unit` — 화면에 박지 않는다. */
  unit: string
  /** 유형별 내역(최대 2종). 순위 밖 셀은 빈 배열이며 그때는 `.gp__bd` 를 아예 만들지 않는다. */
  dtl: LifezoneDtl[]
  /**
   * 미리 포맷한 값 문자열(혼잡도 `1.26`). 없으면 `anlsVl.toLocaleString()` 이다.
   * 🔴 이 값도 이스케이프한다(포매터가 무엇을 돌려줄지 이 모듈은 모른다).
   */
  vlText?: string
  /**
   * 값 아래에 붙는 설명 줄(혼잡도 공급·추정·점유율·상태 문구). 줄마다 `escapeHtml` 을 거친다.
   * 비어 있으면 블록을 만들지 않는다.
   */
  lines?: string[]
  /**
   * 조회 기간 개월수(시안 `52건<span>/3개월</span>`). **민원건수 팝업만** 넘긴다.
   * 있으면 단위를 span 밖으로 빼고 `/N개월` 을 span 에 넣는다. 없으면(혼잡도 등) 종전 마크업 그대로다.
   * 🔴 값은 응답 `bgngYmd`~`endYmd` 로 `monthsOfRange()` 가 계산한다(설정 패널 입력값이 아니다).
   */
  months?: number
  /**
   * 값 앞에 붙는 문구. **혼잡도 팝업만** 넘긴다.
   * 있으면 `.gp__big` 이 `혼잡 지수 92.5점` **한 덩어리 글씨**다(단위 `<span>` 없음).
   * 없으면 종전 마크업 그대로다(민원건수 `52건<span>/3개월</span>` 무변경). 🔴 이 값도 이스케이프한다.
   */
  bigPrefix?: string
}

/** 한 달 평균 일수(365.25 ÷ 12). */
const DAYS_PER_MONTH = 30.4375

/** `YYYYMMDD` → UTC 자정 epoch(ms). 형식이 어긋나면 NaN. */
function ymdToUtc(ymd: string): number {
  if (!/^\d{8}$/.test(ymd)) return Number.NaN
  return Date.UTC(Number(ymd.slice(0, 4)), Number(ymd.slice(4, 6)) - 1, Number(ymd.slice(6, 8)))
}

/**
 * 조회 기간의 개월수 — **양끝 포함 일수 ÷ 30.4375 를 반올림, 최소 1**.
 * 시안의 `3개월` 고정값을 옮기지 않고 기간에서 계산해 1·6개월·직접설정에도 맞게 한다.
 * (`recentMonths(1|3|6)` → 1·3·6.) 날짜 형식이 어긋나면 1 로 떨어진다.
 */
export function monthsOfRange(bgngYmd: string, endYmd: string): number {
  const days = (ymdToUtc(endYmd) - ymdToUtc(bgngYmd)) / 86_400_000 + 1
  if (!Number.isFinite(days)) return 1
  return Math.max(1, Math.round(days / DAYS_PER_MONTH))
}

/** 설명 줄 블록 — `.gp__bd` 구분선을 쓰되 세로로 쌓는다(`analysis.css` 를 건드리지 않으려 인라인). */
const LINES_STYLE = 'flex-direction:column;gap:4px;white-space:normal;max-width:280px'

/**
 * 셀 팝업 HTML — 시안 `.gp` 마크업 그대로(`.gp__h` 배지+구역명 / `.gp__big` 값+단위 / `.gp__bd` 내역).
 *
 * 🔴 **문자열 조립이라 XSS 경로다.** `cntrPoiNm`(DB + VWorld 외부 문자열)과
 * `cvlcptTypeNm`(공통코드)은 예외 없이 `escapeHtml` 을 통과시킨다.
 * ⚠️ `HotspotMap.tsx` 에도 동명 함수가 있지만 **비-export 로컬 함수**라 import 할 수 없어,
 * 보고서 생성기(`lib/report/reportTemplate.ts`)의 export 된 것을 쓴다 —
 * 이스케이프 규칙이 세 벌로 갈리지 않게 하려는 것이다.
 */
export function buildCellPopupHtml(input: CellPopupInput): string {
  // 내역이 없으면 줄 자체를 만들지 않는다 — 빈 `.gp__bd` 는 구분선만 남아 오해를 준다.
  const dtl = input.dtl.length
    ? `<div class="gp__bd">${input.dtl
        .map((d) => `<span>${escapeHtml(d.cvlcptTypeNm)} <b>${d.cnt}</b></span>`)
        .join('')}</div>`
    : ''
  const lines = input.lines?.length
    ? `<div class="gp__bd" style="${LINES_STYLE}">${input.lines
        .map((line) => `<span>${escapeHtml(line)}</span>`)
        .join('')}</div>`
    : ''
  const vlText = input.vlText ?? input.anlsVl.toLocaleString()
  // 문구가 있으면(혼잡도) `혼잡 지수 92.5점` **한 덩어리 글씨**다(시안 `혼잡 지수 ${e}점`).
  //   단위를 `<span>`(작은 회색 글씨)에 넣지 않는다 — 앞 글자와 같은 크기·색.
  // 개월수가 있으면(민원건수) `52건<span>/3개월</span>`, 둘 다 없으면 종전 `52<span>건</span>`(무변경).
  const big = input.bigPrefix
    ? escapeHtml(`${input.bigPrefix}${vlText}${input.unit}`)
    : input.months != null
      ? `${escapeHtml(vlText)}${escapeHtml(input.unit)}<span>/${escapeHtml(String(input.months))}개월</span>`
      : `${escapeHtml(vlText)}<span>${escapeHtml(input.unit)}</span>`
  return `<div class="gp">
    <div class="gp__h">
      <span class="${GRADE_BADGE_CLASS[input.grade]}">${escapeHtml(input.gradeNm)}</span>
      <span class="gp__loc">${escapeHtml(input.cntrPoiNm)}</span>
    </div>
    <div class="gp__big">${big}</div>
    ${dtl}
    ${lines}
  </div>`
}
