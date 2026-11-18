import type {
  LifezoneAnalysisResult,
  LifezoneCell,
  LifezoneCngstCell,
  LifezoneCngstResult,
  LifezoneGrade,
} from '../../api/lifezone'

/*
 * 생활권 등급 → 색·라벨·배지 매핑.
 *
 * 🔴 **집중구역(`components/analysis/`)의 등급 매핑을 재사용하지 않는다.** 그쪽은 4색이고 `NORMAL` 이 회색(`양호`)이라
 * 생활권의 5단계 램프(심각 → 낮음)를 담을 수 없다. 5색으로 늘리려 그 파일을 손대면
 * **집중구역 화면의 색이 함께 바뀐다** — 그래서 import 하지 않고 별도 표를 둔다.
 * 두 화면이 같은 등급코드 문자열을 쓰는 것은 우연이며, 등급 체계 자체가 다르다(4종 ↔ 5종).
 *
 * 🔴 **등급 판정은 백엔드가 한다.** 구간 경계는 조회 결과의 최대값에서 로그 등간격으로 매번 다시 산출되므로
 * 프론트에 박을 임계값이 애초에 없다 — 이 파일에 값 비교 코드를 넣지 않는다.
 * 넣는 순간 지도·목록·PDF·DOCX 가 서로 다른 등급을 보게 된다.
 *
 * 🔴 **범례 문구도 만들지 않는다.** 응답 `gradeCriteria[].lgndTxt`(`심각 (56건 이상)`)를 그대로
 * 출력한다 — 경계가 조회마다 달라지고 단위도 분석유형마다 달라서(`건`/`%`/`점`), 프론트가
 * 조립하면 화면과 응답이 어긋난다. 집중구역이 갖고 있는 범례 조립 헬퍼에 해당하는
 * 함수를 여기에 두지 않는 이유다.
 *
 * 이 모듈을 쓰는 곳: 격자 히트맵 폴리곤·팝업·범례 점 · 순위 목록 배지 · 내보내기.
 */

/**
 * 등급 → 색. **CSS 변수 표기 그대로**라 DOM 요소의 `style` 에는 바로 넣을 수 있지만,
 * SVG `fill` 속성에는 쓸 수 없다 — 그쪽은 아래 `resolveGradeColor()` 로 실제 색을 뽑아 넣는다.
 *
 * 램프는 `심각(적) → 경고(주황) → 주의(파랑) → 보통(초록) → 낮음(회색)` 순으로, 다섯 색 전부
 * `tokens.css` 토큰만 참조하며 폴백 리터럴을 두지 않는다.
 * ⚠️ `NORMAL` 이 집중구역(`--cool-neutral-80`)과 다른 색(`--green-50`)이다 — 생활권에서는
 * `NORMAL` 아래에 `LOW` 가 하나 더 있어 회색을 최하위에 내준다. 보통의 초록은 **시안 정본**이다
 * (배포 번들 2026-10-08 추출 — 종전 연한 파랑은 시안 확인 전 임시값이었다).
 * 지도 채움·범례 점·순위 점·보고서 표(`resolveGradeColor` 경유)가 모두 이 표 하나를 읽는다.
 */
export const GRADE_COLOR_VAR: Record<LifezoneGrade, string> = {
  SEVERE: 'var(--red-50)',
  WARN: 'var(--orange-50)',
  CAUTION: 'var(--blue-50)',
  NORMAL: 'var(--green-50)',
  LOW: 'var(--cool-neutral-80)',
}

/**
 * 등급 → 라벨. 응답에 `gradeNm` 이 함께 오므로 **화면 표시는 응답값을 우선**하고,
 * 이 표는 응답 행과 무관하게 등급 자체를 설명해야 할 때의 기본값이다.
 * ⚠️ `NORMAL` 이 집중구역의 `양호` 가 아니라 **`보통`** 이다 — 5단계의 가운데 값이기 때문이다.
 */
export const GRADE_LABEL: Record<LifezoneGrade, string> = {
  SEVERE: '심각',
  WARN: '경고',
  CAUTION: '주의',
  NORMAL: '보통',
  LOW: '낮음',
}

/**
 * 등급 → 배지 클래스(`dashboard.css` 의 공용 `.badge`).
 * `severe`/`warn`/`caution` 은 `dashboard.css`, `low` 는 `analysis.css` 것을 쓴다 — 인라인 스타일을 쓰지 않는다.
 * `NORMAL` 은 시안 지도 팝업처럼 **`badge--done`**(`dashboard.css` 의 초록 상태 배지)이다 — 위 색표의 초록과
 * 맞춘 것이다. ⚠️ `analysis.css` 의 `badge--normal`(회색)은 집중구역 `양호` 용이라 여기서 쓰지 않는다.
 */
export const GRADE_BADGE_CLASS: Record<LifezoneGrade, string> = {
  SEVERE: 'badge badge--severe',
  WARN: 'badge badge--warn',
  CAUTION: 'badge badge--caution',
  NORMAL: 'badge badge--done',
  LOW: 'badge badge--low',
}

/**
 * 목록에 싣는 등급 — 심각·경고·주의(수급 목록 `listedSupplyRows`).
 *
 * 🔴 **백엔드 `LifezoneGrade.isListed()` 와 같은 집합이다 — 한쪽만 바꾸지 않는다.**
 * 등급 **코드** 집합 판정이라 위 "값 비교(임계값 판정) 코드를 넣지 않는다" 원칙과 충돌하지 않는다.
 */
export const LISTED_GRADES: readonly LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION']

/** 목록 대상 등급인지 — {@link LISTED_GRADES}. 값이 없으면 `false`. */
export function isListedGrade(g: LifezoneGrade | null | undefined): boolean {
  return g != null && LISTED_GRADES.includes(g)
}

/**
 * CSS 변수 표기(`var(--red-50)`)를 **실제 색 문자열**(`rgb(255, 66, 66)`)로 해석한다.
 *
 * SVG 의 `fill` 속성(프레젠테이션 속성)과 캔버스 API 는 `var()` 를 받지 않고, 지도 오버레이는
 * 문자열로 만들어 Leaflet 에 넘기므로 CSS 상속으로 우회할 수도 없다. 그래서 화면 밖 임시 노드에
 * 색을 얹고 `getComputedStyle` 로 브라우저가 해석한 값을 읽어 온다.
 *
 * ⚠️ 집중구역 등급 매핑에 같은 구현이 있지만 **공용 모듈로 빼지 않았다** — 그 파일을
 * 건드리면 집중구역 화면의 회귀 위험을 지게 되고, 이 함수는 등급 체계와 무관한 8줄짜리라
 * 지금은 중복이 더 싸다. 세 번째 화면이 같은 것을 요구하면 그때 `lib/` 로 승격한다.
 *
 * 임시 노드는 같은 함수 안에서 반드시 제거한다(누수 방지). SSR 환경은 이 앱의 대상이 아니지만
 * `document` 가 없으면 계산할 수 없으므로 표기 문자열을 그대로 돌려준다.
 */
export function resolveCssColor(cssColor: string): string {
  if (typeof document === 'undefined') return cssColor
  const probe = document.createElement('span')
  probe.style.cssText = 'position:absolute;left:-9999px;top:-9999px'
  probe.style.color = cssColor
  document.body.appendChild(probe)
  const resolved = getComputedStyle(probe).color
  document.body.removeChild(probe)
  return resolved
}

/**
 * 등급 → 실제 색 문자열. 폴리곤 SVG·내보내기(캔버스/문서)처럼 `var()` 를 못 쓰는 곳에서 쓴다.
 *
 * ⚠️ 해석에 임시 DOM 노드가 필요해 호출 1회가 **강제 리플로우**를 부른다. 격자 셀은 수백 개라
 * (500m 전 지역 실측 597칸) 루프에서 그대로 부르면 수백 회가 되므로, 등급이 5개뿐인 점을 이용해
 * 캐시한다. 테마 전환처럼 토큰 값이 런타임에 바뀌는 기능은 아직 없다 — 생기면
 * `clearGradeColorCache()` 를 부른다.
 */
const gradeColorCache = new Map<LifezoneGrade, string>()

export function resolveGradeColor(grade: LifezoneGrade): string {
  const hit = gradeColorCache.get(grade)
  if (hit) return hit
  const resolved = resolveCssColor(GRADE_COLOR_VAR[grade])
  gradeColorCache.set(grade, resolved)
  return resolved
}

/** 색 캐시를 비운다(테마 전환 등 토큰 값이 바뀌었을 때). */
export function clearGradeColorCache(): void {
  gradeColorCache.clear()
}

/* ── 혼잡도 ──────────────────────────
 * 컴포넌트가 아닌 헬퍼라 `.tsx` 가 아니라 이 모듈에 둔다(react-refresh 규칙).
 */

/**
 * 혼잡도 값(`anlsVl` = 점수 × 100 정수) → **점수** 표시 문자열(`85.4` · `100` · `159.7`). 지도 라벨·팝업·순위값이 같은 함수를 쓴다.
 * 혼잡 점수 = 혼잡 지수 × 100 이다(**상한 없음** — 2026-10-08 ⑪ · 100점 초과 = 수요가 면수 초과). 단위(`점`)는 응답 `unit` 이 붙인다.
 * 🔴 소수 첫째 자리이되 **`.0` 은 지운다**(`100.0` → `100`) — 서버 범례 포매터(`formatScore`)와 같은 규칙이다.
 * ⚠️ 범례(`lgndTxt`)는 서버가 같은 규칙으로 이미 찍어 내리므로 이 함수를 거치지 않는다.
 * 🔴 반올림은 **정수(`anlsVl`) 단계에서 사사오입**한다 — `(v / 100).toFixed(1)` 은 이진 소수
 * 반올림이라 `9755` 를 `97.5` 로 찍었다(서버 범례·엑셀은 `97.6`). 엑셀 `cngstScoreText` 와 같은 규칙이다.
 */
export function formatCngstVl(v: number): string {
  const tenths = Math.sign(v) * Math.floor((Math.abs(v) + 5) / 10)
  return tenths % 10 === 0 ? String(tenths / 10) : (tenths / 10).toFixed(1)
}

/**
 * 결과가 혼잡도 응답인지. 🔴 `result.anlsTrgtCd === 'CNGST'` 를 그 자리에서 비교하면 타입이 좁혀지지
 * 않아(prop 이 `LifezoneAnalysisResult`) 혼잡도 필드를 읽을 수 없다 — 이 가드를 쓴다.
 */
export function isCngstResult(r: LifezoneAnalysisResult): r is LifezoneCngstResult {
  return r.anlsTrgtCd === 'CNGST'
}

/**
 * 혼잡도 공급·초과주차 줄 — 순위 행·지도 팝업·보고서 세부 내역이 함께 쓴다(문구가 갈리지 않게 한 곳).
 * 예) `공급 64면 (2개소) · 초과주차 추정 1.1대`
 */
export function formatCngstSupply(
  cell: Pick<LifezoneCngstCell, 'pkltCpctySum' | 'pkltCnt' | 'excsPrkPrsmpVl'>,
): string {
  return (
    `공급 ${cell.pkltCpctySum.toLocaleString()}면 (${cell.pkltCnt.toLocaleString()}개소) · ` +
    `초과주차 추정 ${Number(cell.excsPrkPrsmpVl).toFixed(1)}대`
  )
}

/** 셀이 혼잡도 셀인지(`noEnfYn` 보유 여부로 판정). */
export function isCngstCell(c: LifezoneCell): c is LifezoneCngstCell {
  return 'noEnfYn' in c
}
