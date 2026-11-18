import type { GradeCriterion, HotspotWeight, HotspotWorkbook, HotspotZone, WgvlAjmtYn } from '../../api/analysis'
import { describeGradeRanks, GRADE_LABEL, resolveGradeColor } from '../../components/analysis/hotspotGrade'
import type { AnlsVrblAjmt } from '../../api/analysisWeight'
import {
  basisBlockHtml,
  changedRowsFromWeights,
  changedSettingsBlockHtml,
  changedSettingsBoxOf,
  escapeHtml,
} from '../report/reportTemplate'

/*
 * 집중 구역 분석 보고서 본문 HTML 생성기.
 *
 * **미리보기 · PDF · DOCX 세 경로가 공유하는 단일 HTML 소스다**.
 *   - 분석 화면 `내보내기` → 저장 API 응답으로 이 함수 → `exportPdf`/`exportDocx`
 *   - 보고서 목록 > 직접 생성 → GET 스냅샷으로 이 함수 → 미리보기 모달 · 재다운로드
 * 서식이 두 벌로 갈리지 않도록 세 경로 모두 이 함수의 결과만 쓴다 —
 * 화면에서 본 것과 내려받은 파일의 내용이 항상 같아야 한다.
 *
 * ⚠️ 결과 문자열은 `dangerouslySetInnerHTML` 로 그려지고 DOCX 에도 그대로 실린다.
 * 따라서 **바깥에서 들어온 값은 예외 없이 `escapeHtml` 을 통과시킨다** — 특히 `zoneNm` 은
 * VWorld POI·단속주소에서 온 값이고 `regionNm`·`gradeNm` 도 서버 응답이라 그대로 넣으면 XSS 경로가 된다.
 *
 * 스타일은 전부 인라인이다. DOCX 변환기(html-docx-js)가 외부 스타일시트를 따라가지 않고,
 * PDF 는 화면 밖 임시 노드를 캡처하므로 앱 CSS 가 적용된다는 보장이 없다.
 */

/* ── 1. 입력 타입 ─────────────────────────────────────── */

/**
 * 문서 한 벌을 그리는 데 필요한 값 전부.
 *
 * 타입은 기존 것을 **재정의하지 않고 import 해 쓴다**(가중치 타입의 정본은 `api/analysisWeight.ts`).
 */
export interface HotspotReportDoc {
  /**
   * 아래 4칸은 **서버 산출물**이다(`HotspotReportNaming`). 저장 실패 폴백에는
   * 응답이 없으므로 **선택값**으로 둔다 — 없으면 `분석 기간` 줄과 부제 3 을 생략한다(아래 `metaCaption`).
   *
   * 🔴 없을 때 프론트가 3단 지역 규칙·기간 표기를 **두 번째로 구현하지 않는다** —
   * `HotspotReportNaming`이 그 규칙의 단일 출처다.
   */
  /** 보고서명 `집중 구역 분석 보고서 {regionNm}`. **본문에는 그리지 않는다** — 다운로드 파일명·미리보기 모달 제목에서 쓴다. */
  rptpNm?: string
  /** 지역 표시명 `전체 지역` / `제주시 전체` / `제주시 연동`. 부제 3 의 `분석 지역`. */
  regionNm?: string
  /** 분석기간설명 `2026.08.01 ~ 2026.08.31`(시작=종료면 한 번만). `분석 기간` 줄의 값이다. */
  anlsPrdExpln?: string
  /** 생성일시 (ISO-8601 `2026-08-27T14:03:11`). 부제 3 의 `생성일` — 앞 10자만 쓴다. */
  crtYmd?: string
  /** 점수에 실제로 반영된 가중치 (sort_seq 순 — **재정렬 금지**). 부제 2 의 비율 출처. */
  weights: HotspotWeight[]
  /**
   * 가중치 조정 여부. `Y` 면 부제 2 끝에 ` (가중치 조정 적용)` 을 붙인다.
   * 구 스냅샷은 키가 없어 표시하지 않는다. 저장 실패 폴백은 `hotspotExport` 가 실행 입력값으로 채운다.
   */
  wgvlAjmtYn?: WgvlAjmtYn | null
  /**
   * 점수 산정 방식. `LIST_MAX` = 조회 결과 1위를 100 으로 둔 상대 점수.
   * 구 스냅샷은 키가 없고 그때의 기준값 방식 문구를 그대로 보여 준다.
   */
  anlsMthd?: 'LIST_MAX' | null
  /** 생성 당시 등급 비율. 구 스냅샷은 없다. */
  gradeCriteria?: GradeCriterion[] | null
  /**
   * 등급 비율 3행의 적용 내역. `점수 산정 기준` 뒤 `적용 설정` 상자의 재료다(가중치 행은 `weights[]`).
   * `null`/없음(구 스냅샷 ① · 저장 실패 폴백)이면 상자를 통째로 생략한다.
   */
  vrblAjmt?: AnlsVrblAjmt | null
  /** 엑셀 재계산 데이터. 구 스냅샷은 `null`/없음 — 그때 Excel 내보내기는 비활성이다. 문서 HTML 에는 쓰지 않는다. */
  wbk?: HotspotWorkbook | null
  /**
   * 순위 표의 행. 서버가 종합점수 내림차순으로 정렬해 `rnk` 까지 매겨 주므로 **받은 순서 그대로** 그린다.
   *
   * 🔴 `HotspotZone` 전체를 요구하지 않는다 — GET 스냅샷에는 `emdNm`·`zoneNmSrc` 가 없다.
   *    문서 템플릿이 실제로 쓰는 칸만 받는다.
   *    ⚠️ POST 응답 타입은 이것이 아니다 — `HotspotReportResponse`(`zones: HotspotZone[]` 9필드)를
   *    쓴다. 화면 상태 갱신에 9필드가 필요하기 때문이며, 넓은 → 좁은 대입이라 그 응답을 이 함수에
   *    그대로 넘길 수 있다.
   */
  zones: Pick<HotspotZone, 'rnk' | 'zoneNm' | 'cvlcptCnt' | 'crdnCnt' | 'snthsScr' | 'grade' | 'gradeNm'>[]
}

/* ── 2. 고정 문구·치수 ────────────────────────────────── */

/** 문서 제목 — 참고 PDF·결과 패널(`.sim-sec__title`)과 같은 **고정 문구**다(`rptpNm` 을 쓰지 않는다). */
const DOC_TITLE = '불법 주차 집중 구역 순위'

/** 부제 1 — 참고 PDF 고정 문구. */
const DOC_SUBTITLE = '제주 주차민원분석 솔루션 · 집중 구역 분석 결과'

/**
 * A4 문서 폭(px). 210mm @ 96dpi ≈ 794px.
 *
 * ⚠️ 출처는 `src/lib/report/reportTemplate.ts:38` 의 `A4_WIDTH_PX` 지만 그쪽은 **모듈 private** 라
 * import 할 수 없다. 그 파일을 고쳐 export 하는 대신
 * **같은 값을 여기 선언**한다 — 값이 바뀌면 두 곳을 함께 고쳐야 한다.
 */
const A4_WIDTH_PX = 794

/**
 * 폰트 스택은 **토큰(`var(--font-body)`)이 아니라 리터럴**로 적는다.
 * DOCX 는 앱 스타일시트 없이 Word 가 직접 렌더하므로 `var()` 가 해석되지 않아 폰트가 통째로 날아간다
 * (`reportTemplate.buildReportHtml`·`hotspotExport` 도 같은 이유로 리터럴을 쓴다).
 * 값은 `tokens.css` 의 `--font-body` 와 동일.
 */
const DOC_FONT = "'Pretendard', 'Wanted Sans', system-ui, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"

/** 표 머리글 칸 공통 스타일. `text-align` 만 열마다 다르다. */
const TH_STYLE = 'padding:8px 10px;border-bottom:1px solid #e5e6ea;'

/** 표 본문 칸 공통 스타일. */
const TD_STYLE = 'padding:8px 10px;border-bottom:1px solid #f0f0f0;'

/** 표 머리글: 라벨 + 정렬. 순서가 곧 열 순서다(참고 PDF 5열과 동일). */
const COLUMNS: { label: string; align: 'left' | 'right' | 'center' }[] = [
  { label: '순위', align: 'left' },
  { label: '구역', align: 'left' },
  { label: '종합점수', align: 'right' },
  { label: '세부 내역', align: 'left' },
  { label: '등급', align: 'center' },
]

/**
 * 가중치 문구의 **지표 라벨 — 고정이다**. 화면(`screen`)과 문서(`report`)의 표기가 달라
 * 한 표에 두 칸으로 둔다 — 지표를 추가할 때 한쪽만 등재되는 일이 없게 하려는 것이다.
 *
 * 🔴 응답의 `vrblNm` 을 그대로 쓰면 `민원빈도 60% / 단속건수 40%` 가 되어 참고 PDF 부제
 * (`민원 건수 60% / 단속 40%`)와 갈린다. **비율(`stngVl`)만 응답에서 가져오고 라벨은 여기서 고정**한다.
 *
 * 여기 없는 변수코드(장래에 지표가 늘어나는 경우)는 **문구에서 빠진다** — `vrblNm` 으로 몰래 메우지 않는다.
 * 지표를 추가하면 이 표에 라벨을 함께 등재한다.
 *
 * ⚠️ 키는 변수코드(`vrblCd`)로 이름만 바뀐 **같은 코드값**이다(라벨 문자열은 그대로).
 * 계수(`vrblSeCd === 'CFC'`)는 애초에 이 응답에 실리지 않지만
 * (`AnalysisWeightServiceImpl#selectEffectiveWeights` 가 `WGVL`·`use_yn='Y'` 만 남긴다),
 * 실린다 해도 이 표에 없어 문구에서 자동으로 빠진다.
 */
const WEIGHT_LABEL: Record<string, { screen: string; report: string }> = {
  COMPLAINT: { screen: '민원 건수', report: '민원 건수' },
  ENFORCE: { screen: '단속', report: '단속 건수' },
}

/**
 * 표 아래 `점수 산정 기준` — 시안 고정 문구. 스냅샷에 값이 없어 템플릿 고정값이다.
 *
 * 대조 출처: 50m·3개 = `application.yml` `analysis.hotspot.eps`·`min-points` 기본값 ·
 * `주의 이상` = 백엔드 `HotspotGrade`(`MIN_DISPLAYED = CAUTION`).
 * 🔴 저 값들을 바꾸면 이 문구도 함께 바꾼다. 응답값으로 조립하는 화면 InfoTip 과 달리 저절로 따라가지 않는다.
 *
 * 🔴 `anlsMthd` 가 없는 구 보고서의 점수는 기준값 방식으로 계산됐다 — 저장된 점수와 맞도록 구 문구를 유지한다.
 */
const SCORE_BASIS_TITLE = '점수 산정 기준'
/** 1줄 — 군집 정의. 엑셀(`hotspotXlsx`)의 `점수 산정 기준` 도 이 상수를 쓴다. */
export const SCORE_BASIS_CLUSTER =
  '구역은 반경 50m 내 3개 지점 이상의 민원·단속 위치를 묶은 것입니다(DBSCAN).'
/** 2줄 — 상대 점수(`LIST_MAX`) 산식. 엑셀도 이 상수를 쓴다. */
export const SCORE_BASIS_LIST_MAX =
  '종합점수(0~100) = 민원·단속 건수를 각 지표의 최댓값 대비로 환산해 가중합하고, 이번 결과의 1위를 100점으로 맞춘 값입니다.'
const scoreBasisLines = (criteria?: GradeCriterion[] | null) => [
  SCORE_BASIS_CLUSTER,
  SCORE_BASIS_LIST_MAX,
  // 🔴 비율은 설정값이다 — 생성 당시 저장값으로 조립한다. 설정을 바꿔도 과거 문서가 소급해 바뀌지 않는다.
  ...(criteria?.length ? [`등급 : 점수 순위 ${describeGradeRanks(criteria)} — 주의 이상 구역만 표시됩니다.`] : []),
  '점수·등급은 조회 조건 안의 상대값이라 지역·기간이 다르면 비교할 수 없습니다.',
]
const SCORE_BASIS_LINES_LEGACY = [
  SCORE_BASIS_CLUSTER,
  '종합점수(0~100) = 월평균 민원·단속 건수를 기준값 대비 점수화해 가중합한 값입니다.',
  '등급 : 심각 80점 이상 · 경고 60~79점 · 주의 40~59점 — 주의 이상 구역만 표시됩니다.',
]

/* ── 3. 표기 헬퍼 ─────────────────────────────────────── */

/*
 * 🔴 이 절의 헬퍼는 `lib/analysis/hotspotExport.ts`의 것과 규칙이 같다.
 * **의존 방향은 export → template 한 방향**이다 — 그쪽이 여기서 import 한다(반대로 두면
 * `패널 → 내보내기 → 템플릿 → 내보내기` 순환이 된다).
 *
 * 가중치 문구는 **화면(`weightsCaption`)과 문서(`reportWeightsCaption`)가 다른 문자열**이다.
 * 종전 "같은 문자열" 규칙은 폐기했다. 라벨 표와 비율 표기는 여기 한 곳(`weightParts`)을 함께 쓴다.
 */

/**
 * 가중치 비율 표기. `stngVl` 은 백엔드 `BigDecimal(12,4)` 유래라 `60.0000` → JS `60` 으로 오지만,
 * `use_yn='N'` 제외 후 재정규화된 값이라 `33.33` 처럼 정수가 아닐 수 있다 — 1자리까지만 남긴다.
 */
function formatRate(rate: number): string {
  return rate.toLocaleString(undefined, { maximumFractionDigits: 1 })
}

/**
 * 종합점수 표기 — **소수점 반올림 정수 + `점`**(`259.20` → `259점`).
 *
 * ⚠️ 산식이 정규화 없는 가중합이라 **점수에 상한이 없다** — 자릿수를 가정하지 않고
 * 천단위 구분만 넣는다(참고 PDF 의 `90점` 은 시안 목데이터다).
 */
function formatScore(score: number): string {
  return `${Math.round(score).toLocaleString()}점`
}

/**
 * `종합점수 기준 : {라벨} N% / {라벨} M%` 조립 — 화면·문서 공용.
 * **라벨 고정 · 숫자만 `weights` 에서**(위 `WEIGHT_LABEL` 주석 참고).
 *
 * `weights` 가 비었거나 라벨을 아는 지표가 하나도 없으면 `null` — `종합점수 기준 :` 만 남기지 않는다.
 */
function weightParts(weights: HotspotWeight[], target: 'screen' | 'report'): string | null {
  const parts = weights
    .map((w) => (WEIGHT_LABEL[w.vrblCd] ? `${WEIGHT_LABEL[w.vrblCd][target]} ${formatRate(w.stngVl)}%` : null))
    .filter((part): part is string => part !== null)
  if (parts.length === 0) return null
  return `종합점수 기준 : ${parts.join(' / ')}`
}

/**
 * **화면 전용** 가중치 문구 — `종합점수 기준 : 민원 건수 60% / 단속 40%`.
 * 결과 패널(`HotspotResultPanel`)이 `hotspotExport.ts` 의 re-export 로 쓴다. 문서는 `reportWeightsCaption` 이다.
 */
export function weightsCaption(weights: HotspotWeight[]): string | null {
  return weightParts(weights, 'screen')
}

/**
 * **문서 전용** 부제 2 — `종합점수 기준 : 민원 건수 60% / 단속 건수 40%`.
 * 조정분은 끝에 ` (가중치 조정 적용)` 을 붙인다. 판정은 저장된 `wgvlAjmtYn` 이 `Y` 일 때뿐이다 —
 * 구 스냅샷(키 없음)을 숫자 비교로 추정하지 않는다(그 시점 설정값을 알 수 없다).
 */
function reportWeightsCaption(doc: HotspotReportDoc): string | null {
  const caption = weightParts(doc.weights, 'report')
  if (!caption) return null
  return doc.wgvlAjmtYn === 'Y' ? `${caption} (가중치 조정 적용)` : caption
}

/**
 * 부제 3 — `분석 지역 : 제주시 연동 · 생성일 : 2026-08-27`.
 * 참고 PDF 에 없는 **추가 줄**이다 — 보고서로 남는 이상 어디를 언제 분석했는지가 문서 안에 있어야 한다.
 * `분석 기간` 은 시안대로 별도 줄이라 여기 넣지 않는다(`buildHotspotReportHtml` 참고).
 *
 * 🔴 **헤더 4칸이 없으면(저장 실패 폴백) `null` 이라 이 줄이 통째로 빠진다.**
 * 제목·부제 1·부제 2·표·점수 산정 기준은 그대로 그린다.
 *
 * `crtYmd` 는 `2026-08-27T14:03:11` 로 오므로 앞 10자만 쓴다(`ReportsPage.formatDate` 와 같은 규칙).
 * 이미 `yyyy-MM-dd` 면 그대로 통과한다.
 */
function metaCaption(doc: HotspotReportDoc): string | null {
  const parts: string[] = []
  if (doc.regionNm) parts.push(`분석 지역 : ${doc.regionNm}`)
  if (doc.crtYmd) parts.push(`생성일 : ${doc.crtYmd.slice(0, 10)}`)
  if (parts.length === 0) return null
  return parts.join(' · ')
}

/** 구역 1건의 `세부 내역` 칸 — 전 행 같은 형식이다(결과 목록 행 `.rl__sub` 과 같은 문구). */
function zoneDetail(zone: HotspotReportDoc['zones'][number]): string {
  return `민원 건수 ${zone.cvlcptCnt.toLocaleString()}건 · 단속 ${zone.crdnCnt.toLocaleString()}건`
}

/**
 * `resolveGradeColor()` 의 계산값 표기(`rgb(255, 66, 66)` · 최신 브라우저는 `rgb(255 66 66)`)를 `#ff4242` 로 바꾼다.
 *
 * ⚠️ 🔴 **DOCX 때문에 반드시 필요하다.** Word 의 HTML 가져오기는 **hex 표기를 가장 확실하게 해석**하고,
 * 배지는 흰 글자 + 색 배경이라 배경이 빠지면 **라벨이 통째로 안 보인다**. `hotspotExport.ts`
 * 도 같은 결론에 도달해 같은 변환을 두고 있으나 그쪽 함수는 모듈 private 라 가져올 수 없다
 * (그 파일은 이 템플릿을 쓰도록 바뀐다 — 그때 이쪽이 유일한 출처가 된다).
 * 이미 hex 이거나 해석할 수 없는 표기면 그대로 돌려준다.
 */
function toHexColor(color: string): string {
  const m = /^rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(color)
  if (!m) return color
  const hex = m
    .slice(1)
    .map((v) => Number(v).toString(16).padStart(2, '0'))
    .join('')
  return `#${hex}`
}

/**
 * 등급 배지 HTML.
 *
 * 색은 **이미 있는 `resolveGradeColor()`**로 뽑는다 — 지도 마커·범례·목록 점·문서가
 * 한 출처를 본다. 색 헬퍼를 이 파일에 새로 만들지 않는다.
 * ⚠️ 문서에는 `var(--red-50)` 이 통하지 않아 **실제 색**이 필요하다(DOCX 는 Word 가 렌더하므로 CSS 변수가
 * 통째로 빠진다). 반환 형식은 hex 가 아니라 `rgb(255, 66, 66)` 이다.
 * 등급이 4개뿐이라 그 함수가 결과를 캐시하므로 표 15행을 돌아도 해석은 최대 4회다.
 *
 * 라벨은 **응답의 `gradeNm` 을 우선**하고(서버가 정본), 비어 있을 때만 `GRADE_LABEL` 로 메운다.
 * `border-radius` 는 Word 가 무시해 알약이 아닌 사각 칩으로 보인다 — 색·라벨은 그대로라 허용한다.
 */
function gradeBadge(zone: HotspotReportDoc['zones'][number]): string {
  const color = toHexColor(resolveGradeColor(zone.grade))
  const label = zone.gradeNm || GRADE_LABEL[zone.grade]
  return `<span style="display:inline-block;padding:2px 10px;border-radius:999px;font-size:11px;font-weight:700;color:#fff;background-color:${escapeHtml(color)};">${escapeHtml(label)}</span>`
}

/**
 * `적용 설정` 상자. 🔴 부제 2(`reportWeightsCaption`)는 그대로 두고 상자에는 기본값과 다른
 * 가중치(`weights[]` — 출처는 `wgvlAjmtYn`)와 등급 비율(`vrblAjmt`)을 싣는다. `vrblAjmt == null` 이면
 * `weights[]` 가 있어도 상자 전체를 생략한다(구 스냅샷 ① · 저장 실패 폴백).
 */
function settingsBox(doc: HotspotReportDoc) {
  if (doc.vrblAjmt == null) return null
  return changedSettingsBoxOf(doc.vrblAjmt, 'HOTSPOT', changedRowsFromWeights(doc.weights, doc.wgvlAjmtYn))
}

/* ── 4. 본문 ──────────────────────────────────────────── */

/**
 * 미리보기·PDF·DOCX 공용 본문. 바깥에서 온 값은 예외 없이 `escapeHtml` 을 통과시킨다.
 *
 * 폭은 기존 보고서 템플릿과 같은 794px(A4 한 페이지 가로 정합)이다. 15행 + 머리글이 A4 1쪽에 들어가며,
 * 넘치면 `exportPdf` 가 자동으로 페이지를 나눈다(기존 동작).
 *
 * `zones` 가 비면 머리글만 있는 표가 나온다 — 0건일 때 파일을 만들지 않는 판단은 호출부(10)의 몫이다.
 */
export function buildHotspotReportHtml(doc: HotspotReportDoc): string {
  const period = doc.anlsPrdExpln ? `분석 기간 : ${doc.anlsPrdExpln}` : null
  const caption = reportWeightsCaption(doc)
  const meta = metaCaption(doc)

  const head = COLUMNS.map(
    (c) => `<th style="${TH_STYLE}text-align:${c.align};">${escapeHtml(c.label)}</th>`,
  ).join('')

  const body = doc.zones
    .map(
      (zone) => `<tr>
            <td style="${TD_STYLE}">${escapeHtml(String(zone.rnk))}</td>
            <td style="${TD_STYLE}font-weight:700;">${escapeHtml(zone.zoneNm)}</td>
            <td style="${TD_STYLE}text-align:right;font-weight:700;">${escapeHtml(formatScore(zone.snthsScr))}</td>
            <td style="${TD_STYLE}color:#70737c;">${escapeHtml(zoneDetail(zone))}</td>
            <td style="${TD_STYLE}text-align:center;">${gradeBadge(zone)}</td>
          </tr>`,
    )
    .join('')

  return `<div style="font-family:${DOC_FONT};color:#171717;width:${A4_WIDTH_PX}px;padding:40px;background:#fff;box-sizing:border-box;">
      <div style="font-size:22px;font-weight:800;margin-bottom:6px;">${escapeHtml(DOC_TITLE)}</div>
      <div style="margin-bottom:24px;">
        <div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(DOC_SUBTITLE)}</div>
        ${period ? `<div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(period)}</div>` : ''}
        ${caption ? `<div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(caption)}</div>` : ''}
        ${meta ? `<div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(meta)}</div>` : ''}
      </div>
      <table style="width:100%;border-collapse:collapse;font-size:12px;">
        <thead>
          <tr style="background:#f5f6f8;">${head}</tr>
        </thead>
        <tbody>${body}</tbody>
      </table>
      ${basisBlockHtml(SCORE_BASIS_TITLE, doc.anlsMthd === 'LIST_MAX' ? scoreBasisLines(doc.gradeCriteria) : SCORE_BASIS_LINES_LEGACY)}
      ${changedSettingsBlockHtml(settingsBox(doc))}
    </div>`
}
