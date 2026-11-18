import type { ZoneRcmdtnReport } from '../../api/zoneRcmdtn'
import { resolveCssColor } from '../../components/analysis/hotspotGrade'
import {
  FEE_RVW_CRTR_SET_NOTE,
  NOTE_RANK_ONLY,
  NOTE_TREND_WINDOW_SHORT,
  RANK_SEC_TITLE,
  SHARE_NOTE_SCOPE,
  SHARE_LEGEND_TITLE,
  SHARE_LVL_BASE,
  SHARE_NOTE_SCR,
  SHARE_SEC_TITLE,
  feeRvwFormulaDescForReport,
  feeRvwGrdColor,
  feeRvwGrdNm,
  feeRvwLegendLine,
  fmtDecimal,
  fmtIncrsRt,
  fmtInt,
  fmtRvwScr,
  fmtYmd,
  incrsRtColor,
  isFeeRvwScr,
  lotDisplayNm,
  noteAltmntRng,
  noteAsgn,
  noteIncrsRtNull,
  noteTrendBfrFallback,
  rankSecSubSggForReport,
  shareLegendRows,
  shareZoneLvl,
  shareLvlColor,
  shareLvlNm,
  shareScrDescForReport,
  shareTrendDescForReport,
} from '../../components/analysis/zoneRcmdtnView'
import {
  GRADE_BASIS_TITLE,
  basisBlockHtml,
  changedSettingsBlockHtml,
  changedSettingsBoxOf,
  escapeHtml,
  type ChangedSettingsBox,
} from '../report/reportTemplate'
import { toHexColor } from './investPrrtReportTemplate'

/*
 * 구역추천 보고서 본문 생성기 — 요금제 적용 · 공유주차제 적용 2종 (TSK-4240 Step 7).
 *
 * **미리보기 · PDF · DOCX 세 경로가 공유하는 단일 소스다**(투자우선순위·집중구역 템플릿과 같은 규약).
 *   - 분석 화면 `내보내기` → 저장 API 응답(201)으로 이 함수 → `exportPdf`/`exportDocx`
 *   - 보고서 목록 → GET 스냅샷으로 이 함수 → 미리보기 모달 · 재다운로드
 *
 * 두 시안(`docs/report/구역추천_요금제.pdf` · `구역추천_공유주차제.pdf`)의 구성이 같아
 * **모델은 하나, 빌더는 둘**이다 — 스냅샷 → 문서 모델(`build…Model`, DOM 없이 돈다) → HTML.
 * 구성: 제목 · 회색 부제 줄들 · `1.` 가로 막대 차트 · `2.` 순위 표 · 하단 필수 문구.
 *
 * 🔴 **레이아웃은 시안을 따르고 칸은 화면이 실제로 가진 값으로 채운다.** 시안은 목데이터("구역")이고
 * 실제 행은 요금제 = 후보 **주차장**, 공유주차제 = 검토 후보 **읍면동**이다.
 * 🔴 **시안의 가중합 기준 줄(민원 40% / 증가율 30% / …)을 쓰지 않는다** — 이 화면에 그런 산식이 없다.
 * 그 자리는 화면 문구(`rankSecSub` · `shareScrDesc`)가 채운다.
 * 🔴 **표기 헬퍼를 새로 만들지 않는다** — 숫자·등급·필수 문구는 전부 `zoneRcmdtnView.ts` 의 것이다.
 * 화면과 문서의 표기가 갈리면 같은 보고서가 두 값으로 보인다.
 * 🔴 **공유주차제의 `smry` 를 쓰지 않는다** — 시군구 필터를 걸어도 도 전체 값이라 "이 보고서의 건수" 가
 * 아니다. 행은 `zones`(필터 범위)에서만 온다.
 *
 * ⚠️ 결과 HTML 은 `dangerouslySetInnerHTML` 로 그려지고 DOCX 에도 실린다 — 바깥에서 온 값은
 * 예외 없이 `escapeHtml` 을 거친다(주차장 이름·읍면동 이름·지역명은 원천 문자열이다).
 * 스타일은 전부 인라인 · 색은 리터럴(hex)이다 — Word 와 화면 밖 캡처 노드는 앱 스타일시트와 `var()` 를 모른다.
 */

/* ── 1. 문서 모델 ─────────────────────────────────────── */

/** 파일명 — 시안의 파일 이름 그대로. 확장자는 내보내기가 붙인다. */
export const ZONE_RCMDTN_FEE_FILE_BASE = '구역추천_요금제'
export const ZONE_RCMDTN_SHARE_FILE_BASE = '구역추천_공유주차제'

/** 차트 막대 한 줄. */
export interface ZoneRcmdtnReportChartItem {
  label: string
  /** 막대 길이의 원값(건수). 길이는 **그 차트 안의 최댓값 대비 비율**이다. */
  value: number
  /** 막대 오른쪽 꼬리표. 예) `52건 (+59.3%)` · `311건 (지수 5.05)`. */
  tail: string
  /** 🔴 hex 다 — `var(--…)` 를 그대로 두면 캡처·DOCX 에서 막대가 비어 나온다. */
  color: string
}

/** 두 보기가 함께 쓰는 문서 모델 — 렌더러는 어느 보기인지 모른다. */
export interface ZoneRcmdtnReportModel {
  /** 제목. */
  title: string
  /** 부제 · 지역/기간 · 기준 문구. 빈 줄은 담지 않는다. */
  subLines: string[]
  chartTitle: string
  chart: ZoneRcmdtnReportChartItem[]
  tableTitle: string
  columns: string[]
  rows: string[][]
  /** 표 아래 `단계 구분 기준` 상자의 줄(TSK-4312). 비면 상자를 그리지 않는다(구 요금제 스냅샷 등). */
  basisLines: string[]
  /** 🆕 TSK-4346 — 기준 상자 바로 뒤 `적용 설정` 상자. `null` = 구 스냅샷 ①(머리 `vrblAjmt` 없음)이라 그리지 않는다. */
  settingsBox: ChangedSettingsBox | null
  /** 하단 필수 문구. */
  notes: string[]
}

/** 요금제 적용 빌더가 읽는 칸. 저장 응답·스냅샷(`ZoneRcmdtnReport`)을 그대로 넘길 수 있다. */
export type ZoneRcmdtnFeeReportDoc = Pick<
  Extract<ZoneRcmdtnReport, { rcmdtnTypeCd: 'FEE' }>,
  'regionNm' | 'anlsPrdExpln' | 'bgngYmd' | 'endYmd' | 'fee' | 'vrblAjmt'
>

/** 공유주차제 적용 빌더가 읽는 칸. */
export type ZoneRcmdtnShareReportDoc = Pick<
  Extract<ZoneRcmdtnReport, { rcmdtnTypeCd: 'SHARE' }>,
  'regionNm' | 'anlsPrdExpln' | 'bgngYmd' | 'endYmd' | 'share' | 'vrblAjmt'
>

/** 부제 첫 줄 — 시안 그대로. */
const DOC_SUBTITLE = '제주 주차민원분석 솔루션'

/** 차트에 그리는 막대 수 — 투자우선순위 템플릿의 `CHART_TOP_N` 과 같다. 표가 전체를 담는다. */
const CHART_TOP_N = 20

/** 값이 없는 칸. */
const EMPTY = '-'

/**
 * 색 문자열 → hex. 🔴 **두 단계다** — `resolveCssColor` 가 `var(--red-50)` 을 `rgb(…)` 로 풀고
 * `toHexColor` 가 그것을 hex 로 바꾼다. `toHexColor` 혼자는 `var(--…)` 를 그대로 돌려준다
 * (공유주차제 시안 PDF 의 막대가 비어 보이는 이유가 그것이다).
 * ⚠️ `resolveCssColor` 는 호출 1회가 강제 리플로우라 색 문자열별로 한 번만 해석한다(색은 몇 개뿐이다).
 */
const hexColorCache = new Map<string, string>()
function hexColor(cssColor: string): string {
  const hit = hexColorCache.get(cssColor)
  if (hit) return hit
  const color = toHexColor(resolveCssColor(cssColor))
  hexColorCache.set(cssColor, color)
  return color
}

/** 기간 문구 — 서버가 조립한 설명(`2025.01.01 ~ 2025.08.31`)이 정본이고, 없을 때만 적용 날짜로 만든다(엑셀 생성기도 쓴다 — TSK-4340). */
export function prdText(doc: { anlsPrdExpln: string | null; bgngYmd: string; endYmd: string }): string {
  if (doc.anlsPrdExpln) return doc.anlsPrdExpln
  if (!doc.bgngYmd || !doc.endYmd) return ''
  return `${fmtYmd(doc.bgngYmd)} ~ ${fmtYmd(doc.endYmd)}`
}

/**
 * `1. 추천 …별 … 건수 (기간 · 상위 20곳)` — 괄호 안은 있는 것만 적는다.
 * `unit` 은 행의 단위다 — 주차장은 `개소`, 읍면동은 `곳`(화면 표기와 같다).
 */
function chartTitleOf(base: string, prd: string, total: number, unit: string): string {
  // 잘렸을 때만 그 사실을 제목에 적는다 — 막대 20개를 전체로 읽지 않게.
  const parts = [prd, total > CHART_TOP_N ? `상위 ${CHART_TOP_N}${unit}` : ''].filter(Boolean)
  return parts.length ? `1. ${base} (${parts.join(' · ')})` : `1. ${base}`
}

/**
 * 🆕 TSK-4282 — 새 방식 표의 검토지수 열. 🔴 `우선도` 가 순서의 기준이고, `검토지수 × 증가계수` 가 그 값이다.
 * `단계` 는 그 우선도(원값)로 서버가 정한 `rvwGrdCd` 다(D14 — 100/60) — 프론트가 점수로 다시 판정하지 않는다.
 */
const FEE_RVW_COLUMNS = ['종합지수', '검토지수', '증가계수', '단계']

/** {@link FEE_RVW_COLUMNS} 의 칸. 값이 없으면 `-`(`fmtDecimal`·{@link EMPTY}). 증가계수는 서버 자릿수(3)까지 적는다. */
function feeRvwCells(l: ZoneRcmdtnFeeReportDoc['fee']['lots'][number]): string[] {
  return [
    fmtDecimal(l.incrsRfltRvwScr),
    fmtDecimal(l.rvwScr),
    fmtDecimal(l.incrsCfc, 3),
    l.rvwGrdCd ? feeRvwGrdNm(l.rvwGrdCd) : EMPTY,
  ]
}

/**
 * 요금제 적용 — 행 = 후보 **주차장**. `lots` 를 전부 담고 차트만 상위 20개소다.
 *
 * 🔴 순서·순위(`rnk`)·누적 면수(`cumCpcty`)는 서버 값 그대로다(새 방식: 우선도 내림차순 ·
 * 구 스냅샷: 배정 민원 내림차순 — 어느 쪽도 다시 매기지 않는다).
 * 🔴 **적합도는 주차장 행에 없다** — 충족률은 읍면동 지표라 부제의 요약 한 줄에만 둔다.
 * 표의 마지막 칸은 `면수(누적)` 이고, 필요 면수에 **처음 닿는 한 행**에만 도달 표시를 붙인다.
 * 🔴 막대 색은 새 방식이 `rvwGrdCd` 우선도 단계색(화면 행 점·핀·범례의 `feeRvwGrdColor`) · 구 스냅샷이 민원 증가율
 * 부호(`incrsRtColor`)다.
 *
 * 🆕 TSK-4282 D11 — **새 방식**(`fee.anlsMthd === 'RVW_SCR'` · 판정은 `isFeeRvwScr` 한 곳)은 차트 막대 = 우선도
 * (`incrsRfltRvwScr`), 표에 `우선도`·`검토지수`·`증가계수`·`단계` 열(공유주차제 보고서와 같은 구성),
 * 부제에 산식 설명(🔁 TSK-4346 — 보고서 전용 `feeRvwFormulaDescForReport` · 숫자는 `적용 설정` 상자)을, 표 아래 `단계 구분 기준` 상자에 단계 설명
 * (`feeRvwLegendLine` — 화면 범례와 같은 상수 · TSK-4312)을 더한다. 부제·노트의 "순위는 …" 문구도 `anlsMthd` 로 갈린다. 🔴 키가 없는 **구 스냅샷은 종전 표 그대로**다.
 *
 * 🔁 TSK-4259 D11 — **시군구 모드**(`fee.scope === 'SGG'`)는 `zone`·`asgn` 이 `null` 이다. 지역 판정 문장
 * (적합도 줄 · 판정 불가 사유 · 배정률 `noteAsgn` · `(참고용)` 경고)을 생략하고, 부제는 적합도를 말하지 않는
 * `rankSecSubSgg`, 표는 `읍면동` 열을 더하고 누적 없는 `면수` 열이다(`cumCpcty` 가 `null` · 도달 표시 없음).
 * 🔴 **구 스냅샷(`anlsMthd` 없음)의** 읍면동 모드(`scope` 가 `'EMD'` 또는 없음) 출력은 문자 단위로 종전과 같다 —
 * 그 분기의 식·순서를 바꾸지 않는다(시군구 모드 구 스냅샷도 같다).
 */
export function buildZoneRcmdtnFeeReportModel(doc: ZoneRcmdtnFeeReportDoc): ZoneRcmdtnReportModel {
  const { zone, asgn, crtrVls, lots, warnings } = doc.fee
  const isSgg = doc.fee.scope === 'SGG'
  /** 🆕 TSK-4282 — 새 방식(검토지수). 키가 없으면(구 스냅샷) 종전 그대로 그린다(D11). */
  const anlsMthd = doc.fee.anlsMthd
  const rvwScr = isFeeRvwScr(anlsMthd)
  const prd = prdText(doc)
  /*
   * 🆕 TSK-4346 — 구 스냅샷 ①(머리 `vrblAjmt` 없음 · TSK-4325 이전)은 상자가 없어 본문 숫자 문장을 종전대로 둔다.
   * 🔴 적용 내역은 본문 `fee.vrblAjmt`(보고서에선 `null`)가 아니라 **머리** `vrblAjmt` 에 있다.
   */
  const legacyDoc = doc.vrblAjmt == null
  /*
   * 지역명은 머리(`regionNm`)가 정본이다. 없으면 백엔드 `regionNm(sggNm, emdNm)` 과 같은 꼴로 만든다 —
   * 읍면동 모드 `{시군구} {읍면동}` · 시군구 모드 `{시군구} 전체`(내려받은 파일과 재다운로드가 갈리지 않게).
   */
  const region =
    doc.regionNm ||
    (isSgg
      ? `${doc.fee.sggNm ?? ''} 전체`.trim()
      : zone
        ? `${zone.sggNm} ${zone.emdNm}`.trim()
        : '')
  /*
   * 🔴 증가율 안내의 조건 — 화면(`ZoneRcmdtnResultPanel` 의 순위 섹션 아래 안내줄)의
   * `hasNullIncrs` · `trendWindowShort` · `trendBfrFallback` 과 **같은 식**이고, 싣는 순서
   * (폴백 안내 → `—` 의 사유)와 `lots.length > 0` 조건도 화면 그대로다. 증가율 칸이 `—` 인 이유가
   * 문서에 없으면 고장으로 읽힌다. ⚠️ 그 식은 패널 안 로컬 식이라 가져올 수 없다 — 화면을 고치면 여기도 고친다.
   */
  const hasNullIncrs = lots.some((l) => l.cvlcptIncrsRt == null)
  const trendWindowShort = warnings.includes('TREND_WINDOW_SHORT')
  const trendBfrFallback = warnings.includes('TREND_BFR_FALLBACK')

  return {
    title: RANK_SEC_TITLE,
    subLines: [
      DOC_SUBTITLE,
      [region, prd].filter(Boolean).join(' · '),
      // 🔁 TSK-4340 — 읍면동 판정(적합도·충족률)을 걷어 두 모드 모두 같은 부제다(구 스냅샷도 판정 문장 없이 그린다).
      // 🔁 TSK-4346 — 보고서 전용 변형(숫자는 `적용 설정` 상자로 · 구 스냅샷 ① 은 종전 숫자 문장).
      rankSecSubSggForReport(lots.length, crtrVls, anlsMthd, legacyDoc),
      // 🆕 TSK-4282 — 새 방식만: 산식(🔁 TSK-4346 — 구조만, 숫자는 상자로). 단계 설명은 `basisLines` 로 옮겼다(TSK-4312).
      ...(rvwScr ? feeRvwFormulaDescForReport(crtrVls, doc.fee.sggNm, legacyDoc) : []),
    ].filter(Boolean),
    chartTitle: rvwScr
      ? chartTitleOf('추천 주차장별 종합지수', prd, lots.length, '개소')
      : chartTitleOf('추천 주차장별 배정 민원 건수', prd, lots.length, '개소'),
    chart: lots.slice(0, CHART_TOP_N).map((l) =>
      rvwScr
        ? {
            label: lotDisplayNm(l),
            // 막대 길이 = 순서의 기준(서버 순서와 같은 값). 값이 없으면 0 폭이다(`chartHtml` 의 `valueOf`).
            value: l.incrsRfltRvwScr ?? 0,
            tail: `종합지수 ${fmtDecimal(l.incrsRfltRvwScr)} (${fmtIncrsRt(l.cvlcptIncrsRt)})`,
            // 🔴 색은 우선도 단계(`rvwGrdCd` · D14)다 — 화면 행 점·핀·범례와 같은 함수.
            color: hexColor(feeRvwGrdColor(l.rvwGrdCd)),
          }
        : {
            label: lotDisplayNm(l),
            value: l.asgnCvlcptCnt,
            // 🔴 증가율 `null` 은 `—` 다(0% 로 쓰지 않는다 — "변화 없음" 과 "계산하지 않았다" 는 다르다).
            tail: `${fmtInt(l.asgnCvlcptCnt)}건 (${fmtIncrsRt(l.cvlcptIncrsRt)})`,
            color: hexColor(incrsRtColor(l.cvlcptIncrsRt)),
          },
    ),
    tableTitle: '2. 추천 주차장 순위',
    /*
     * 🔁 TSK-4259 — 시군구 모드만 `읍면동` 열을 더하고 누적 없는 `면수` 다. 읍면동 모드 열 구성은 그대로.
     * 🆕 TSK-4282 — 새 방식은 `주차장`(시군구 모드는 `읍면동`) 뒤에 검토지수 4열을 끼운다. 구 스냅샷은 종전 열 그대로.
     */
    columns: [
      ...(isSgg ? ['순위', '주차장', '읍면동'] : ['순위', '주차장']),
      ...(rvwScr ? FEE_RVW_COLUMNS : []),
      ...(isSgg ? ['증가율', '민원 건수', '면수'] : ['증가율', '민원 건수', '면수(누적)']),
    ],
    rows: lots.map((l) => {
      const row = isSgg
        ? [
            String(l.rnk),
            lotDisplayNm(l),
            l.emdNm || EMPTY,
            fmtIncrsRt(l.cvlcptIncrsRt),
            `${fmtInt(l.asgnCvlcptCnt)}건`,
            `${fmtInt(l.pkltCpcty)}면`,
          ]
        : [
            String(l.rnk),
            lotDisplayNm(l),
            fmtIncrsRt(l.cvlcptIncrsRt),
            `${fmtInt(l.asgnCvlcptCnt)}건`,
            `${fmtInt(l.pkltCpcty)}면 (누적 ${fmtDecimal(l.cumCpcty)}면)`,
          ]
      if (!rvwScr) return row
      // 새 방식 — 이름 칸(시군구 모드는 읍면동 칸) 뒤에 끼운다(`columns` 와 같은 자리).
      const at = isSgg ? 3 : 2
      return [...row.slice(0, at), ...feeRvwCells(l), ...row.slice(at)]
    }),
    // 🔴 구 스냅샷(`anlsMthd` 없음)은 단계가 없어 상자도 없다 — 출력이 종전과 같아야 한다(D11).
    // 🔁 TSK-4325 D20 — `상위 10%` 문장은 뺐다(기준값 조정·변경 시 사실이 아니다). 이미 만든 보고서도 받을 때 빠진다.
    basisLines: rvwScr ? [feeRvwLegendLine(), FEE_RVW_CRTR_SET_NOTE] : [],
    settingsBox: changedSettingsBoxOf(doc.vrblAjmt, 'RECOMMEND_FEE'),
    notes: [
      NOTE_RANK_ONLY,
      // 배정률은 읍면동 지표다 — 시군구 모드(`asgn` null)는 문장을 두지 않는다(D4·D11).
      asgn ? noteAsgn(asgn.cvlcptTot, asgn.cvlcptAsgn, asgn.asgnRt, crtrVls.altmntRngVl) : '',
      // 🔁 TSK-4346 — 배정 범위 되풀이 문장은 `적용 설정` 상자가 대신한다. 구 스냅샷 ① 에만 남긴다.
      legacyDoc ? noteAltmntRng(crtrVls.altmntRngVl) : '',
      // 화면의 순위 섹션 아래 두 안내줄 — 조건·문구·순서가 화면과 같다(위 주석).
      lots.length > 0 && trendBfrFallback ? noteTrendBfrFallback(crtrVls.trendDayCnt) : '',
      lots.length > 0 && (trendWindowShort || hasNullIncrs)
        ? trendWindowShort
          ? NOTE_TREND_WINDOW_SHORT
          : noteIncrsRtNull(crtrVls.minTrendBase, crtrVls.trendDayCnt, anlsMthd)
        : '',
    ].filter(Boolean),
  }
}

/**
 * 공유주차제 적용 — 행 = 검토 후보 **읍면동**.
 *
 * 🔴 **후보(`rvwTrgtYn === 'Y'`)만** 차트·표에 쓴다. `rnk != null` 로 거르지 않는다 — 번호는 후보 밖의
 * 읍면동에도 붙는다. 순서와 순위 칸(`rnk`)은 서버 값 그대로다(다시 매기지 않는다).
 * 🔴 **마지막 칸은 "적합도" 가 아니라 `우선도 단계`**(🔁 TSK-4287 — 종전 `검토 등급`)이다 — 판정은 여전히
 * 검토 지수다(D1).
 * 🔁 TSK-4283 — `우선도`(🔁 TSK-4287 — 종전 `추세 반영 지수` · 순서의 기준 · D2) · `민원 증가율` 열을 더했다. `검토 지수` 열은 남긴다 —
 * 등급의 근거다(D1). 순서 설명(`shareTrendDesc`)·증가율 경고 2종(요금제 보기와 같은 문장)도 싣는다.
 * ⚠️ **이 작업 이전에 저장된 스냅샷에는 새 필드·`crtrVls` 키가 없다**(다시 계산하지 않는다 — 요금제 TSK-4259
 * 선례) — 두 열은 `—`(`fmtRvwScr`·`fmtIncrsRt` 가 `undefined` 를 `—` 로 쓴다)이고 순서 설명은
 * 생략된다(`shareTrendDesc` 가 빈 배열). 그 스냅샷의 행 순서는 종전 규칙(검토 지수 순)이다.
 */
export function buildZoneRcmdtnShareReportModel(
  doc: ZoneRcmdtnShareReportDoc,
): ZoneRcmdtnReportModel {
  const { crtrVls, zones } = doc.share
  const prd = prdText(doc)
  const cands = zones.filter((z) => z.rvwTrgtYn === 'Y')
  const emdNmOf = (z: (typeof zones)[number]) => `${z.sggNm} ${z.emdNm}`.trim()
  /** 증가율 경고 — 화면(`ZoneRcmdtnSharePanel` InfoTip)과 같은 조건(표에 행이 있을 때만)·같은 문장이다. */
  const warnings = doc.share.warnings ?? []
  const trendWindowShort = warnings.includes('TREND_WINDOW_SHORT')
  const trendBfrFallback = warnings.includes('TREND_BFR_FALLBACK')
  /** 종합지수 도입 전 스냅샷은 단계가 검토 지수 판정이다 — `shareZoneLvl` 의 폴백과 같은 조건(`null` 은 등급 없음이라 다르다). */
  const scrLvlSnapshot = cands.some((z) => z.incrsRfltRvwScr === undefined)
  /** 🆕 TSK-4346 — 구 스냅샷 ①(머리 `vrblAjmt` 없음)은 산식 숫자 문장을 종전대로 둔다. */
  const legacyDoc = doc.vrblAjmt == null

  return {
    title: SHARE_SEC_TITLE,
    subLines: [
      DOC_SUBTITLE,
      // `regionNm` 은 응답 값 그대로다(`{시군구} 전체` 또는 `전체 지역`).
      [doc.regionNm ?? '', prd].filter(Boolean).join(' · '),
      // 🔁 TSK-4346 — 보고서 전용 변형(기준값·증가 계수 숫자는 `적용 설정` 상자로).
      ...shareScrDescForReport(crtrVls, legacyDoc),
      ...shareTrendDescForReport(crtrVls, legacyDoc),
    ].filter(Boolean),
    chartTitle: chartTitleOf('추천 읍면동별 민원 건수', prd, cands.length, '곳'),
    chart: cands.slice(0, CHART_TOP_N).map((z) => ({
      label: emdNmOf(z),
      value: z.cvlcptCnt,
      tail: `${fmtInt(z.cvlcptCnt)}건 (지수 ${fmtRvwScr(z.rvwScr)})`,
      // 화면의 깃발·목록 점과 같은 색이다(우선도 단계 3단계).
      color: hexColor(shareLvlColor(shareZoneLvl(z, crtrVls))),
    })),
    tableTitle: '2. 추천 읍면동',
    columns: [
      '순위',
      '읍면동',
      '종합지수',
      '검토 지수',
      '민원 증가율',
      '민원 건수',
      '종합지수 단계',
    ],
    rows: cands.map((z) => [
      z.rnk == null ? EMPTY : String(z.rnk),
      emdNmOf(z),
      fmtRvwScr(z.incrsRfltRvwScr),
      fmtRvwScr(z.rvwScr),
      fmtIncrsRt(z.cvlcptIncrsRt),
      `${fmtInt(z.cvlcptCnt)}건`,
      // 🔁 2026-10-27 — 단계는 종합지수로 정한다(`shareZoneLvl` · 화면과 같은 함수).
      shareLvlNm(shareZoneLvl(z, crtrVls)) || EMPTY,
    ]),
    // 표에 행이 없으면 설명할 단계도 없다. 범례 숫자는 화면과 같은 함수·스냅샷 `coreMltp` 다(TSK-4312).
    basisLines:
      cands.length > 0 && crtrVls?.coreMltp != null
        ? [
            `${SHARE_LEGEND_TITLE} : ${shareLegendRows(crtrVls).map((r) => r.label).join(' · ')}`,
            ...(scrLvlSnapshot
              ? [
                  `표에는 검토 지수 ${SHARE_LVL_BASE} 이상인 후보만 싣습니다.`,
                  '이 보고서는 종합지수 도입 전에 저장되어 단계를 검토 지수로 판정했습니다.',
                ]
              : [
                  `표에는 검토 지수 ${SHARE_LVL_BASE} 이상인 후보만 싣습니다. 단계는 종합지수로 정하므로 후보여도 낮음일 수 있습니다.`,
                ]),
          ]
        : [],
    settingsBox: changedSettingsBoxOf(doc.vrblAjmt, 'RECOMMEND_SHARE'),
    notes: [
      SHARE_NOTE_SCOPE,
      SHARE_NOTE_SCR,
      cands.length > 0 && trendBfrFallback ? noteTrendBfrFallback(crtrVls.trendDayCnt ?? null) : '',
      cands.length > 0 && trendWindowShort ? NOTE_TREND_WINDOW_SHORT : '',
    ].filter(Boolean),
  }
}

/* ── 2. HTML ──────────────────────────────────────────── */

/*
 * 아래 값은 투자우선순위 템플릿(`investPrrtReportTemplate.ts`)과 **같다** — 시안의 모양이 같다.
 * 그쪽 상수는 모듈 private 이라 가져올 수 없어 같은 값을 여기 둔다(가져다 쓰는 것은 `toHexColor` 하나다).
 */
const A4_WIDTH_PX = 794
const DOC_PADDING_PX = 40
const DOC_FONT =
  "'Pretendard', 'Wanted Sans', system-ui, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif"
const ACCENT_COLOR = '#0066ff'
const MUTED = '#70737c'
const BAR_TRACK = '#EEF0F3'

const SECTION_TITLE_STYLE =
  `border-left:4px solid ${ACCENT_COLOR};padding-left:10px;font-size:15px;font-weight:800;` +
  'line-height:1.4;margin:28px 0 12px;'

const TH_STYLE = 'padding:6px 8px;border-bottom:1px solid #e5e6ea;font-weight:700;text-align:left;'
const TD_STYLE = 'padding:5px 8px;border-bottom:1px solid #f0f0f0;'

/**
 * 막대 차트 — `[이름] [막대] [꼬리표]` 를 표로 짠다(Word 가 flex·inline-block 을 접지 못한다).
 * 막대도 2칸 표라 Word 에서 폭이 유지된다.
 *
 * 🔴 막대 길이는 **이 차트 안의 최댓값 대비 비율**이다(건수는 0~100 눈금이 아니다).
 * 최댓값이 0 이면 전부 0 폭이다 — 0 으로 나누지 않는다.
 */
function chartHtml(items: ZoneRcmdtnReportChartItem[]): string {
  const valueOf = (v: number) => (Number.isFinite(v) && v > 0 ? v : 0)
  const max = items.reduce((m, it) => Math.max(m, valueOf(it.value)), 0)
  const rows = items
    .map((it) => {
      const pct = max > 0 ? Math.min(100, (valueOf(it.value) / max) * 100) : 0
      const rest = 100 - pct
      return `<tr>
          <td style="width:200px;padding:3px 8px 3px 0;text-align:right;font-size:11px;font-weight:700;word-break:keep-all;">${escapeHtml(it.label)}</td>
          <td style="padding:3px 0;">
            <table style="width:100%;border-collapse:collapse;table-layout:fixed;background:${BAR_TRACK};"><tr>
              ${pct > 0 ? `<td style="width:${pct.toFixed(2)}%;height:14px;padding:0;background:${escapeHtml(it.color)};"></td>` : ''}
              ${rest > 0 ? `<td style="width:${rest.toFixed(2)}%;height:14px;padding:0;"></td>` : ''}
            </tr></table>
          </td>
          <td style="width:120px;padding:3px 0 3px 8px;font-size:11px;color:${MUTED};white-space:nowrap;">${escapeHtml(it.tail)}</td>
        </tr>`
    })
    .join('')
  return `<table style="width:100%;border-collapse:collapse;">${rows}</table>`
}

/** 표 — 앞 두 열(순위·이름)만 폭·굵기를 따로 준다. */
function tableHtml(columns: string[], rows: string[][]): string {
  const head = columns.map((c) => `<th style="${TH_STYLE}">${escapeHtml(c)}</th>`).join('')
  const body = rows
    .map(
      (row) =>
        `<tr>${row
          .map((cell, i) => {
            const extra =
              i === 0
                ? 'width:36px;white-space:nowrap;'
                : i === 1
                  ? 'font-weight:700;word-break:keep-all;'
                  : 'word-break:keep-all;'
            return `<td style="${TD_STYLE}${extra}">${escapeHtml(cell)}</td>`
          })
          .join('')}</tr>`,
    )
    .join('')
  return `<table style="width:100%;border-collapse:collapse;font-size:11px;line-height:1.4;">
        <thead><tr style="background:#f5f6f8;">${head}</tr></thead>
        <tbody>${body}</tbody>
      </table>`
}

/** 문서 모델 → HTML. 어느 보기인지 모른다 — 모델에 담긴 것만 그린다. */
export function renderZoneRcmdtnReportModel(model: ZoneRcmdtnReportModel): string {
  const subLines = model.subLines
    .map(
      (line) =>
        `<div style="font-size:12px;color:${MUTED};line-height:1.6;">${escapeHtml(line)}</div>`,
    )
    .join('')

  const sectionTitle = (text: string) =>
    `<div data-pdf-keep-next style="${SECTION_TITLE_STYLE}">${escapeHtml(text)}</div>`

  // 하단 필수 문구 — 표와 구분선으로 가른다. 문구가 없으면 묶음 자체를 두지 않는다.
  const notes = model.notes.length
    ? `<div data-pdf-keep style="margin-top:20px;padding-top:12px;border-top:1px solid #e5e6ea;">${model.notes
        .map(
          (note) =>
            `<div style="font-size:11px;color:${MUTED};line-height:1.6;">${escapeHtml(note)}</div>`,
        )
        .join('')}</div>`
    : ''

  return `<div style="font-family:${DOC_FONT};color:#171717;width:${A4_WIDTH_PX}px;padding:${DOC_PADDING_PX}px;background:#fff;box-sizing:border-box;">
      <div style="font-size:22px;font-weight:800;margin-bottom:6px;">${escapeHtml(model.title)}</div>
      ${subLines}
      ${sectionTitle(model.chartTitle)}
      ${chartHtml(model.chart)}
      ${sectionTitle(model.tableTitle)}
      ${tableHtml(model.columns, model.rows)}
      ${basisBlockHtml(GRADE_BASIS_TITLE, model.basisLines)}
      ${changedSettingsBlockHtml(model.settingsBox)}
      ${notes}
    </div>`
}

/**
 * 미리보기·PDF·DOCX 공용 본문 — 저장 응답·스냅샷을 그대로 넘긴다.
 * 🔴 `rcmdtnTypeCd` 가 본문을 정한다(두 보기가 분석유형코드를 함께 써서 목록만 보고는 가를 수 없다).
 */
export function buildZoneRcmdtnReportHtml(report: ZoneRcmdtnReport): string {
  return renderZoneRcmdtnReportModel(
    report.rcmdtnTypeCd === 'FEE'
      ? buildZoneRcmdtnFeeReportModel(report)
      : buildZoneRcmdtnShareReportModel(report),
  )
}
