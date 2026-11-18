import {
  formatDtl,
  type LifezoneDtl,
  type LifezoneGrade,
  type LifezoneGradeCriterion,
} from '../../api/lifezone'
import { GRADE_LABEL, resolveGradeColor } from '../../components/lifezone/lifezoneGrade'
import { GRADE_BASIS_TITLE, basisBlockHtml, escapeHtml } from '../report/reportTemplate'

/*
 * 생활권 시뮬레이션 보고서 본문 HTML 생성기.
 *
 * **미리보기 · PDF · DOCX 세 경로가 공유하는 단일 HTML 소스다**.
 *   - 분석 화면 `내보내기` → 저장 API 응답(POST)으로 이 함수 → `exportPdf`/`exportDocx`
 *   - 보고서 목록 > 직접 생성 → GET 스냅샷으로 이 함수 → 미리보기 모달 · 재다운로드
 * 서식이 두 벌로 갈리지 않도록 세 경로 모두 이 함수의 결과만 쓴다 —
 * 화면에서 본 것과 내려받은 파일의 내용이 항상 같아야 한다.
 *
 * 🔴 **집중구역 템플릿(`hotspotReportTemplate.ts`)을 확장하지 않는다.** 문서 구조가 다르다 —
 * 집중구역은 표 1개(순위 5열)뿐이지만 생활권은 `1. 분석 결과 요약`(2열 6행) + `2. {섹션}`(5열 · 심각·경고·주의 전부)
 * **2절 구성**이고, 등급도 4종 ↔ 5종이라 배지·요약 행 수가 함께 갈린다. 한 함수로 합치면
 * 분기가 문서 전체를 덮어 두 화면이 서로의 회귀 위험을 지게 된다.
 *
 * 🔴 **문구·단위·등급명을 이 파일에 박지 않는다.** 섹션 제목(`sectionTitle`)·단위(`unit`)·
 * 유형명(`anlsTrgtNm`)·격자 표기(`gridSzNm`)·등급명(`gradeNm`)이 전부 응답값이다 —
 * 화면(`LifezoneResultPanel`)과 같은 규약이며, 분석유형이 늘어도 백엔드만 바뀌면 된다.
 * 문서 제목·부제 1·절 번호만 고정 문구다(시안 실측).
 *
 * ⚠️ 결과 문자열은 `dangerouslySetInnerHTML` 로 그려지고 DOCX 에도 그대로 실린다.
 * 따라서 **바깥에서 들어온 값은 예외 없이 `escapeHtml` 을 통과시킨다** — 특히 `cntrPoiNm` 은
 * VWorld POI 에서 온 값이고 `gradeNm`·`sectionTitle` 도 서버 응답이라 그대로 넣으면 XSS 경로가 된다.
 *
 * 스타일은 전부 인라인이다. DOCX 변환기(html-docx-js)가 외부 스타일시트를 따라가지 않고,
 * PDF 는 화면 밖 임시 노드를 캡처하므로 앱 CSS 가 적용된다는 보장이 없다.
 */

/* ── 1. 입력 타입 ─────────────────────────────────────── */

/**
 * 문서 한 벌을 그리는 데 필요한 값 전부.
 *
 * 🔴 `LifezoneAnalysisResult` 를 그대로 요구하지 **않는다** — GET 스냅샷에는
 * `dataBgngYmd`·`dataEndYmd`·`availableTargets` 가 없거나 뜻이 다르고(백엔드가 의도적으로 비운다),
 * `cells` 는 문서가 쓰지 않는다(문서에 지도가 없다). **템플릿이 실제로 쓰는 칸만** 선언해
 * 그 칸이 곧 `GET /api/reports/{id}/lifezone` 의 계약이 되게 한다(`HotspotReportDoc` 과 같은 판단).
 *
 * POST 응답(`LifezoneReportResponse`)은 이보다 넓으므로 **그대로 넘길 수 있다.**
 */
export interface LifezoneReportDoc {
  /**
   * 아래 4칸은 **서버 산출물**이다(`LifezoneReportNaming`). 저장 실패 폴백에는
   * 응답이 없으므로 **선택값**으로 둔다 — 없으면 부제 3 을 생략한다(아래 `metaCaption`).
   *
   * 🔴 없을 때 프론트가 3단 지역 규칙·기간 표기를 **두 번째로 구현하지 않는다** —
   * `LifezoneReportNaming`이 그 규칙의 단일 출처다.
   */
  /** 보고서명 `생활권 민원건수 보고서 전체 지역 500m`. **본문에는 그리지 않는다** — 다운로드 파일명·미리보기 모달 제목에서 쓴다. */
  rptpNm?: string
  /** 지역 표시명 `전체 지역` / `제주시 전체` / `제주시 연동`. 부제 3 의 `분석 지역`. */
  regionNm?: string
  /** 분석기간설명 `2026.08.04 ~ 2026.09.30`(시작=종료면 한 번만). 부제 3 의 `분석 기간`. */
  anlsPrdExpln?: string
  /** 생성일시 (ISO-8601 `2026-08-31T14:03:11`). 부제 3 의 `생성일` — 앞 10자만 쓴다. */
  crtYmd?: string

  /** 분석유형명 (`민원건수`). 부제 2 의 `분석 유형`. */
  anlsTrgtNm: string
  /** 격자크기 표시명 (`500m`). 부제 2 의 `격자 크기`. */
  gridSzNm: string
  /** 값의 단위 (`건`/`%`/`점`). 순위 표의 `값` 칸에 붙인다 — 화면 `rl__cnt` 와 같은 규칙이다. */
  unit: string
  /** 2절 제목 (`민원 집중 구역`). 유형마다 달라 서버가 내린다. */
  sectionTitle: string

  /** 값>0 격자 수. 요약 1행. */
  totGridCnt: number
  /** 심각 등급 격자 수. */
  severeCnt: number
  /** 경고 등급 격자 수. */
  warnCnt: number
  /** 주의 등급 격자 수. */
  cautionCnt: number
  /** 보통 등급 격자 수. */
  normalCnt: number
  /** 낮음 등급 격자 수. */
  lowCnt: number

  /**
   * 등급 판정 기준 — **요약 5행의 순서·라벨이 여기서 나온다**(`minVl` 내림차순, 서버 정렬 그대로).
   * `lgndTxt` 는 `단계 구분 기준` 상자에 그대로 싣는다. `minVl` 은 문서가 쓰지 않아 요구하지 않는다.
   * 비어 오면 등급코드 순서 폴백 + `GRADE_LABEL` 로 떨어진다(문구를 추측하지 않는다).
   */
  gradeCriteria: Pick<LifezoneGradeCriterion, 'grade' | 'gradeNm' | 'lgndTxt'>[]

  /** 구간 분류 방식 표시명(`등간격`·`자연분류`). `단계 구분 기준` 상자에 쓴다 — 없으면 그 줄만 뺀다. */
  clsfMthdNm?: string | null

  /**
   * 순위 표의 행. 서버가 분석값 내림차순으로 정렬해 `rnk` 까지 매겨 주므로 **받은 순서 그대로** 그린다.
   * 문서가 쓰는 5칸만 요구한다 — `gridCd`·`ctrLat`·`ctrLot` 은 지도·연동용이라 문서에 없다.
   */
  ranking: {
    rnk: number
    cntrPoiNm: string
    anlsVl: number
    grade: LifezoneGrade
    /** 유형별 세부 내역(최대 2종). 화면 `.rl__sub` 과 **같은 `formatDtl`** 로 잇는다. */
    dtl: LifezoneDtl[]
  }[]
}

/* ── 2. 고정 문구·치수 ────────────────────────────────── */

/** 문서 제목 — 시안 실측 **고정 문구**다(`rptpNm` 을 쓰지 않는다). */
const DOC_TITLE = '생활권 시뮬레이션 분석 결과'

/** 부제 1 — 시안 실측 고정 문구. */
const DOC_SUBTITLE = '제주 주차민원분석 솔루션 · 격자 기반 생활권 분석'

/** 1절 제목 — 고정이다(요약은 유형과 무관하게 같은 내용이라 서버가 문구를 내리지 않는다). */
const SUMMARY_TITLE = '1. 분석 결과 요약'

/** 요약 1행의 라벨 — 화면 `.sum__row--total` 과 같은 문구. */
const TOTAL_LABEL = '총 분석 격자'

/** 요약·순위의 격자 수 단위. 값 단위(`unit`)와 달리 **개수**라 유형과 무관하게 고정이다. */
const CELL_UNIT = '개'

/**
 * A4 문서 폭(px). 210mm @ 96dpi ≈ 794px.
 *
 * ⚠️ 출처는 `src/lib/report/reportTemplate.ts` 의 `A4_WIDTH_PX` 지만 그쪽은 **모듈 private** 라
 * import 할 수 없다(`hotspotReportTemplate.ts` 도 같은 이유로 같은 값을 선언해 둔다).
 * 값이 바뀌면 세 곳을 함께 고쳐야 한다.
 */
const A4_WIDTH_PX = 794

/** 문서 안쪽 여백(px). 표 폭 계산(`colgroup`)이 이 값에 의존한다. */
const DOC_PADDING_PX = 40

/**
 * 폰트 스택은 **토큰(`var(--font-body)`)이 아니라 리터럴**로 적는다.
 * DOCX 는 앱 스타일시트 없이 Word 가 직접 렌더하므로 `var()` 가 해석되지 않아 폰트가 통째로 날아간다.
 * 값은 `tokens.css` 의 `--font-body` 와 동일.
 */
const DOC_FONT = "'Pretendard', 'Wanted Sans', system-ui, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"

/**
 * 절 제목 좌측 바 색 — 시안 실측 `#0066ff`(`--blue-50` 과 같은 값).
 * 🔴 **리터럴이다.** `var(--blue-50)` 은 DOCX 에서 해석되지 않아 바가 통째로 사라진다.
 */
const ACCENT_COLOR = '#0066ff'

/** 절 제목 — 좌측 4px 파란 바(시안 실측). */
const SECTION_TITLE_STYLE =
  `border-left:4px solid ${ACCENT_COLOR};padding-left:10px;font-size:15px;font-weight:800;` +
  'line-height:1.4;margin:0 0 12px;'

/** 표 머리글 칸 공통 스타일. `text-align` 만 열마다 다르다. */
const TH_STYLE = 'padding:8px 10px;border-bottom:1px solid #e5e6ea;font-weight:700;white-space:nowrap;'

/** 표 본문 칸 공통 스타일. */
const TD_STYLE = 'padding:8px 10px;border-bottom:1px solid #f0f0f0;'

/**
 * 순위 표의 열 정의 — 순서가 곧 열 순서다(시안 5열).
 *
 * 🔴 **`width` 를 명시하는 이유**: `세부 내역` 이 시안 목데이터보다 훨씬 길다
 * (`인도·횡단보도 점유 186 · 장애인전용구역 위반 101` — 실측 33자). 폭을 브라우저 자동 배분에
 * 맡기면 그 칸이 다른 칸을 밀어 `구역`·`등급` 이 글자마다 줄바꿈된다. 고정 4열(40+150+70+60=320px)
 * 을 먼저 떼고 **남는 폭 전부를 `세부 내역` 에 준다**(714-320=394px).
 * 합이 본문 폭(794 - 좌우 여백 80 = 714px)과 같아야 표가 넘치지 않는다.
 */
const COLUMNS: { label: string; align: 'left' | 'right' | 'center'; width: number | null }[] = [
  { label: '순위', align: 'left', width: 40 },
  { label: '구역', align: 'left', width: 150 },
  { label: '값', align: 'right', width: 70 },
  { label: '세부 내역', align: 'left', width: null }, // 남는 폭 전부
  { label: '등급', align: 'center', width: 60 },
]

/** 요약에 세울 등급 순서 — `gradeCriteria` 가 비어 온 경우의 **폴백**이다(화면 패널과 같은 표). */
const FALLBACK_GRADE_ORDER: LifezoneGrade[] = ['SEVERE', 'WARN', 'CAUTION', 'NORMAL', 'LOW']

/* ── 3. 표기 헬퍼 ─────────────────────────────────────── */

/**
 * 부제 2 — `분석 유형 : 민원건수 · 격자 크기 : 500m`.
 * 🔴 **라벨만 고정이고 값은 전부 응답값**이다(`anlsTrgtNm`·`gridSzNm`).
 */
function targetCaption(doc: LifezoneReportDoc): string {
  return `분석 유형 : ${doc.anlsTrgtNm} · 격자 크기 : ${doc.gridSzNm}`
}

/**
 * 부제 3 — `분석 지역 : 제주시 연동 · 분석 기간 : 2026.08.04 ~ 2026.09.30 · 생성일 : 2026-08-31`.
 * 시안에 없는 **추가 줄**이다(집중구역 문서와 같은 판단) — 보고서로 남고 목록에서 다시 열리는 이상,
 * 무엇을 언제 분석했는지가 문서 안에 있어야 한다. 시안의 3줄(제목·부제 1·부제 2)은 그대로 둔다.
 *
 * 🔴 **헤더 4칸이 없으면(저장 실패 폴백) `null` 이라 이 줄이 통째로 빠진다.**
 * 제목·부제 1·부제 2·두 표는 그대로 그린다.
 *
 * `crtYmd` 는 `2026-08-31T14:03:11` 로 오므로 앞 10자만 쓴다(`ReportsPage.formatDate` 와 같은 규칙).
 */
function metaCaption(doc: LifezoneReportDoc): string | null {
  const parts: string[] = []
  if (doc.regionNm) parts.push(`분석 지역 : ${doc.regionNm}`)
  if (doc.anlsPrdExpln) parts.push(`분석 기간 : ${doc.anlsPrdExpln}`)
  if (doc.crtYmd) parts.push(`생성일 : ${doc.crtYmd.slice(0, 10)}`)
  if (parts.length === 0) return null
  return parts.join(' · ')
}

/**
 * `resolveGradeColor()` 의 계산값 표기(`rgb(255, 66, 66)` · 최신 브라우저는 `rgb(255 66 66)`)를 `#ff4242` 로 바꾼다.
 *
 * ⚠️ 🔴 **DOCX 때문에 반드시 필요하다.** Word 의 HTML 가져오기는 **hex 표기를 가장 확실하게 해석**하고,
 * 배지는 흰 글자 + 색 배경이라 배경이 빠지면 **라벨이 통째로 안 보인다**.
 * 이미 hex 이거나(브라우저 밖에서 `var(--red-50)` 이 그대로 오는 경우 포함) 해석할 수 없는 표기면
 * 그대로 돌려준다.
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
 * 등급 색(hex). 지도 폴리곤·범례·목록 점과 **같은 출처**(`resolveGradeColor`)를 본다 —
 * 등급이 5개뿐이라 그 함수가 결과를 캐시하므로 순위 표 행 수와 무관하게(요약 5행 포함) 해석은 최대 5회다.
 */
function gradeColor(grade: LifezoneGrade): string {
  return toHexColor(resolveGradeColor(grade))
}

/**
 * 등급 배지 HTML — 색 배경 + 흰 글자 알약.
 * `border-radius` 는 Word 가 무시해 알약이 아닌 사각 칩으로 보인다 — 색·라벨은 그대로라 허용한다.
 * 라벨은 **응답의 `gradeNm` 을 우선**하고, 비어 있을 때만 `GRADE_LABEL` 로 메운다.
 */
function gradeBadge(grade: LifezoneGrade, gradeNm: string): string {
  const label = gradeNm || GRADE_LABEL[grade]
  return (
    `<span style="display:inline-block;padding:2px 8px;border-radius:999px;font-size:11px;` +
    `font-weight:700;color:#fff;white-space:nowrap;background-color:${escapeHtml(gradeColor(grade))};">` +
    `${escapeHtml(label)}</span>`
  )
}

/**
 * 요약 6행 — `총 분석 격자` + 등급 5행.
 * **순서·라벨 모두 응답(`gradeCriteria`)이 정본**이고 건수는 루트의 등급별 카운트에서 코드로 찾는다
 * (화면 `LifezoneResultPanel.gradeRows` 와 같은 규칙 — 화면과 문서가 같은 6행을 낸다).
 */
function summaryRows(doc: LifezoneReportDoc): { label: string; color: string | null; cnt: number }[] {
  const cntOf: Record<LifezoneGrade, number> = {
    SEVERE: doc.severeCnt,
    WARN: doc.warnCnt,
    CAUTION: doc.cautionCnt,
    NORMAL: doc.normalCnt,
    LOW: doc.lowCnt,
  }
  const criteria =
    doc.gradeCriteria.length > 0
      ? doc.gradeCriteria
      : FALLBACK_GRADE_ORDER.map((grade) => ({ grade, gradeNm: GRADE_LABEL[grade], lgndTxt: '' }))

  return [
    { label: TOTAL_LABEL, color: null, cnt: doc.totGridCnt },
    ...criteria.map((c) => ({
      label: c.gradeNm || GRADE_LABEL[c.grade],
      color: gradeColor(c.grade),
      cnt: cntOf[c.grade],
    })),
  ]
}

/**
 * `단계 구분 기준` 상자의 줄.
 *
 * 🔴 구간·분류 방식은 응답(스냅샷) 값 그대로다 — 경계가 조회 조건마다 달라 프론트에 숫자를 두지 않는다.
 * 🔴 분류 방식이 `등간격` 만이 아니다(`자연분류` 스냅샷이 있다) — 화면 InfoTip 의 "범위를 5등분" 표현을 쓰지 않는다.
 * 범례가 없으면 빈 배열이다 — 경계 없이 일반 문구만 남기지 않는다.
 */
export function gradeBasisLines(doc: LifezoneReportDoc): string[] {
  const legends = doc.gradeCriteria.map((c) => c.lgndTxt).filter(Boolean)
  if (legends.length === 0) return []
  return [
    `등급 : ${legends.join(' · ')}`,
    doc.clsfMthdNm
      ? `등급은 격자별 ${doc.anlsTrgtNm}를 ${doc.clsfMthdNm} 방식으로 ${legends.length}단계로 나눕니다.`
      : '',
    '등급 경계는 조회 조건(기간·지역·격자 크기) 안에서 다시 계산되므로, 다른 조건의 보고서와 등급을 직접 비교하지 마십시오.',
    '표에는 심각·경고·주의 등급만 싣습니다. 민원이 없는 격자는 분석에서 제외합니다.',
  ].filter(Boolean)
}

/* ── 4. 본문 ──────────────────────────────────────────── */

/** 순위가 비었을 때 표 본문 안내 1행. 화면(`LifezoneResultPanel`)의 빈 상태 문구와 같다. */
export const EMPTY_RANKING_TEXT = '심각·경고·주의 등급에 해당하는 격자가 없습니다.'

/**
 * 미리보기·PDF·DOCX 공용 본문. 바깥에서 온 값은 예외 없이 `escapeHtml` 을 통과시킨다.
 *
 * 폭은 기존 보고서 템플릿과 같은 794px(A4 한 페이지 가로 정합)이다. 순위 표는
 * 15행 상한 없이 **심각·경고·주의 전부**라 A4 1쪽을 넘을 수 있고, 넘치면 `exportPdf` 가 자동으로
 * 페이지를 나눈다(기존 동작).
 *
 * `ranking` 이 비면 표 본문에 **안내 1행**(`EMPTY_RANKING_TEXT`)을 넣는다 — 순위가
 * 심각·경고·주의로 한정돼 셀이 있어도 비는 것이 정상 경로라, 머리글만 있는 표를 내보내지 않는다.
 * 셀 0 일 때 파일을 만들지 않는 판단은 호출부(`lifezoneExport`·결과 패널)의 몫이다
 * (서버도 셀 0 이면 저장을 400 으로 막는다).
 */
export function buildLifezoneReportHtml(doc: LifezoneReportDoc): string {
  return renderLifezoneReportHtml(doc, {
    formatVl: (rank) => rank.anlsVl.toLocaleString(),
    formatDetail: (rank) => formatDtl(rank.dtl),
    basisLines: gradeBasisLines(doc),
  })
}

/** 유형마다 갈리는 3칸. 문서 구조·서식은 같아 렌더러를 복제하지 않는다. */
export interface LifezoneReportView {
  /** 순위 표 `값` 칸의 숫자 부분. 단위(`doc.unit`)는 렌더러가 붙인다. */
  formatVl: (rank: LifezoneReportDoc['ranking'][number]) => string
  /** 순위 표 `세부 내역` 칸. */
  formatDetail: (rank: LifezoneReportDoc['ranking'][number]) => string
  /** `단계 구분 기준` 상자의 줄. 비면 상자를 그리지 않는다. */
  basisLines: readonly string[]
  /**
   * `단계 구분 기준` 상자 바로 뒤에 넣는 HTML(혼잡도의 `적용 설정` 상자 — 이미 이스케이프된 조각).
   * 민원건수 보고서는 넘기지 않아 종전과 같다.
   */
  settingsHtml?: string
}

/** 민원건수·혼잡도 보고서 공용 렌더러 — 미리보기·PDF·DOCX 의 단일 HTML 소스다. */
export function renderLifezoneReportHtml(doc: LifezoneReportDoc, view: LifezoneReportView): string {
  const meta = metaCaption(doc)
  const contentWidth = A4_WIDTH_PX - DOC_PADDING_PX * 2

  /* ── ① 분석 결과 요약 — 2열 6행 ── */
  const summary = summaryRows(doc)
    .map(
      (row) => `<tr>
            <td style="${TD_STYLE}">${
              row.color
                ? `<span style="color:${escapeHtml(row.color)};font-size:13px;">&#9679;</span> `
                : ''
            }${escapeHtml(row.label)}</td>
            <td style="${TD_STYLE}text-align:right;font-weight:700;">${escapeHtml(
              `${row.cnt.toLocaleString()}${CELL_UNIT}`,
            )}</td>
          </tr>`,
    )
    .join('')

  /* ── ② 순위 — 5열 ── */
  const cols = COLUMNS.map((c) => {
    const width = c.width ?? contentWidth - COLUMNS.reduce((sum, x) => sum + (x.width ?? 0), 0)
    return `<col style="width:${width}px;" />`
  }).join('')

  const head = COLUMNS.map(
    (c) => `<th style="${TH_STYLE}text-align:${c.align};">${escapeHtml(c.label)}</th>`,
  ).join('')

  const body =
    doc.ranking.length === 0
      ? `<tr>
            <td colspan="${COLUMNS.length}" style="${TD_STYLE}text-align:center;color:#70737c;">${escapeHtml(
              EMPTY_RANKING_TEXT,
            )}</td>
          </tr>`
      : doc.ranking
          .map(
            (rank) => `<tr>
            <td style="${TD_STYLE}">${escapeHtml(String(rank.rnk))}</td>
            <td style="${TD_STYLE}font-weight:700;word-break:keep-all;">${escapeHtml(rank.cntrPoiNm)}</td>
            <td style="${TD_STYLE}text-align:right;font-weight:700;white-space:nowrap;">${escapeHtml(
              `${view.formatVl(rank)}${doc.unit}`,
            )}</td>
            <td style="${TD_STYLE}color:#70737c;font-size:11px;line-height:1.5;word-break:keep-all;">${escapeHtml(
              view.formatDetail(rank),
            )}</td>
            <td style="${TD_STYLE}text-align:center;">${gradeBadge(rank.grade, gradeNmOf(doc, rank.grade))}</td>
          </tr>`,
          )
          .join('')

  return `<div style="font-family:${DOC_FONT};color:#171717;width:${A4_WIDTH_PX}px;padding:${DOC_PADDING_PX}px;background:#fff;box-sizing:border-box;">
      <div style="font-size:22px;font-weight:800;margin-bottom:6px;">${escapeHtml(DOC_TITLE)}</div>
      <div style="margin-bottom:24px;">
        <div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(DOC_SUBTITLE)}</div>
        <div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(targetCaption(doc))}</div>
        ${meta ? `<div style="font-size:12px;color:#70737c;line-height:1.6;">${escapeHtml(meta)}</div>` : ''}
      </div>

      <div data-pdf-keep-next style="${SECTION_TITLE_STYLE}">${escapeHtml(SUMMARY_TITLE)}</div>
      <table style="width:100%;border-collapse:collapse;font-size:12px;margin-bottom:28px;">
        <tbody>${summary}</tbody>
      </table>

      <div data-pdf-keep-next style="${SECTION_TITLE_STYLE}">${escapeHtml(`2. ${doc.sectionTitle}`)}</div>
      <table style="width:100%;table-layout:fixed;border-collapse:collapse;font-size:12px;">
        <colgroup>${cols}</colgroup>
        <thead>
          <tr style="background:#f5f6f8;">${head}</tr>
        </thead>
        <tbody>${body}</tbody>
      </table>
      ${basisBlockHtml(GRADE_BASIS_TITLE, view.basisLines)}
      ${view.settingsHtml ?? ''}
    </div>`
}

/**
 * 순위 행의 등급명 — **응답 `gradeCriteria` 에서 코드로 찾는다.**
 * 순위 행 자체에는 `gradeNm` 이 없고(등급코드만 온다) 라벨의 정본은 `gradeCriteria` 다.
 * 못 찾으면 `gradeBadge` 가 `GRADE_LABEL` 로 메운다.
 */
function gradeNmOf(doc: LifezoneReportDoc, grade: LifezoneGrade): string {
  return doc.gradeCriteria.find((c) => c.grade === grade)?.gradeNm ?? ''
}
