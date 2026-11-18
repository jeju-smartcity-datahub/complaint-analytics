import type { AnalysisWeight, AnlsVrblAjmt, AnlsVrblAjmtItem } from '../../api/analysisWeight'
import type { ReportListItem } from '../../api/reports'
import { COEFF_SCOPE_META, type CoeffScope } from '../analysis/coefficientMeta'

/*
 * 보고서 본문 HTML 생성기 (TSK-4026 Step 10).
 *
 * **미리보기 모달 · PDF · DOCX 가 공유하는 단일 HTML 소스다(D10).**
 * 서식이 두 벌로 갈리지 않도록 세 경로 모두 이 함수의 결과만 쓴다 —
 * 화면에서 본 것과 내려받은 파일의 내용이 항상 같아야 한다.
 *
 * D3 범위상 본문은 샘플(`Hello World`) 한 줄이다. 실 보고서 본문(민원 집계·차트)은 범위 밖이다.
 *
 * ⚠️ 결과 문자열은 `dangerouslySetInnerHTML` 로 그려지고 DOCX 에도 그대로 실린다.
 * 따라서 **바깥에서 들어온 값은 예외 없이 `escapeHtml` 을 통과시킨다** — 보고서명(`rptpNm`)은
 * 사용자가 지은 이름이라 그대로 넣으면 XSS 경로가 된다.
 *
 * 스타일은 전부 인라인이다. DOCX 변환기(html-docx-js)가 외부 스타일시트를 따라가지 않고,
 * PDF 는 화면 밖 임시 노드를 캡처하므로 앱 CSS 가 적용된다는 보장이 없다.
 */

/** 이스케이프 대상 문자 → 엔티티. `'` 는 속성값을 홑따옴표로 감쌀 때를 대비해 함께 막는다. */
const HTML_ENTITIES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

/** HTML 특수문자를 엔티티로 치환한다. `<script>` 는 실행되지 않고 문자 그대로 출력된다. */
export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (ch) => HTML_ENTITIES[ch])
}

/** 분석 보고서 기준 상자의 공통 제목(TSK-4312). 집중구역은 `점수 산정 기준`, 투자우선순위는 `순위 산정 기준` 을 쓴다. */
export const GRADE_BASIS_TITLE = '단계 구분 기준'

/**
 * 표 아래 회색 기준 상자 — 미리보기·PDF·DOCX 공용이라 스타일은 인라인이다(TSK-4312 · 집중구역 TSK-4306 D17 모양).
 *
 * 줄이 없으면 빈 문자열이다 — 제목만 남은 상자를 내보내지 않는다. 줄은 여기서 이스케이프한다.
 * `data-pdf-keep` 은 PDF 페이지 경계에서 상자가 잘리지 않게 한다(`pdfPageLayout.layoutPdfPages`).
 */
export function basisBlockHtml(title: string, lines: readonly string[]): string {
  if (lines.length === 0) return ''
  const body = lines
    .map((line) => `<div style="font-size:11px;color:#555;line-height:1.7;">· ${escapeHtml(line)}</div>`)
    .join('')
  return `<div data-pdf-keep style="margin-top:20px;padding:14px 16px;background-color:#f5f6f8;border-radius:8px;">
        <div style="font-size:12px;font-weight:800;margin-bottom:6px;">${escapeHtml(title)}</div>
        ${body}
      </div>`
}

/* ── `적용 설정` 상자 (TSK-4346 Step 7 · D7·D8·D11·D19) ─────────────
 *
 * 분석 보고서 5종(집중구역·투자우선순위·생활권 수급·혼잡도·구역추천)이 **같은 모양**으로 쓴다.
 * 기본값(`bscStngVl`)과 다른 **노출** 계수만 싣고, 출처를 `설정 변경`(관리자가 설정 메뉴에서 바꿈) /
 * `이번 분석에서 조정`(일시 조정)으로 적는다. 설정값을 되풀이만 하던 본문 문장(가중치 캡션·산식 속 숫자)이 이리로 옮겨 왔다.
 *
 * 스냅샷 세 갈래:
 *   - 새 스냅샷(`vrblAjmt.vrbls[]` 에 `bscStngVl`·`expsrYn`) — 아래 규칙 그대로.
 *   - 구 스냅샷 ②(`vrblAjmt` 는 있고 `bscStngVl` 없음 · TSK-4325 ~ Step 6) — 일시 조정분만, 왼쪽 값은 당시 설정값,
 *     끝에 `※ 이 보고서는 설정 변경 여부를 기록하지 않았습니다.`
 *   - 구 스냅샷 ①(`vrblAjmt == null`) — 상자를 그리지 않는다(`null`). 본문 문장도 종전 숫자 그대로다(템플릿 몫).
 */

/**
 * 🔴 구 스냅샷 ② 전용 폴백 — **Step 1 마이그레이션(106) 숨김 행과 같은 집합**이다(D19(a)).
 * 새 스냅샷은 행의 `expsrYn` 이 정본이라 **이 목록을 늘리지 않는다**(숨김의 정본은 DB 다).
 * 키는 분석유형(`anls_type_cd` — 스코프 메타의 `anlsTypeCd`)이다 — `DSCL_MLTP`·`FIX_WGVL`·`PCSHR_CRTR_VL` 은
 * `LIFEZONE`·`POLICY_SIM` 에 같은 코드가 있어 코드만으로는 못 가른다(`POLICY_SIM` 의 `PCSHR_CRTR_VL` 은 노출이다).
 */
const HIDDEN_VRBL_CDS_LEGACY: Readonly<Record<string, ReadonlySet<string>>> = {
  POLICY_SIM: new Set([
    'PCSHR_THRSH',
    'DSCL_MLTP',
    'FIX_WGVL',
    'VACLOT_GVBK_RT',
    'CST_VACLOT',
    'CST_PBLPKLT',
    'CST_MULTFLR',
    'CST_LOW_BNDRY',
    'CST_HIGH_BNDRY',
  ]),
  LIFEZONE: new Set(['DSCL_MLTP', 'FIX_WGVL', 'PCSHR_CRTR_VL', 'CVLCPT_WGVL', 'MIN_CPCTY_VL']),
  RECOMMEND: new Set(['ILGL_CRTR_VL', 'STSF_RT_THRSH']),
  INVEST: new Set(['INDCT_PKLT_CNT']),
}

/**
 * 적용 내역 1행이 노출 계수인가 — `expsrYn` 키가 있으면(새 스냅샷) 그 값이 정본, 없으면(구 스냅샷 ②)
 * {@link HIDDEN_VRBL_CDS_LEGACY} 에 없을 때 노출이다. 🔴 숨김 행은 보고서에 싣지 않는다(D2).
 */
export function isExposedAjmtRow(row: AnlsVrblAjmtItem, scope: CoeffScope): boolean {
  if (row.expsrYn != null) return row.expsrYn !== 'N'
  return !HIDDEN_VRBL_CDS_LEGACY[COEFF_SCOPE_META[scope].anlsTypeCd]?.has(row.vrblCd)
}

/** `적용 설정` 상자 한 행. `grp` 가 같은 행(가중치 묶음)은 한 줄로 그린다. */
export interface ChangedSettingRow {
  vrblNm: string
  /** 왼쪽 값 — 기본값(구 스냅샷 ② 는 당시 설정값). */
  bscVl: number
  aplcnVl: number
  unitNm: string | null
  /** `STNG` = 설정 변경 · `AJMT` = 이번 분석에서 조정. */
  src: 'STNG' | 'AJMT'
  /** 가중치 묶음 — 하나라도 바뀌면 묶음 전체를 싣는다(합계 100 이 보이게). */
  grp?: string
}

/**
 * 상자 입력. 🔴 `legacyAjmt` = 구 스냅샷 ② 의 적용 내역을 실었다 — 0건 문구와 끝 `※` 문장을 이 값으로 가른다
 * (`rows` 만으로는 ② 의 0건과 새 스냅샷의 0건이 같은 `[]` 다).
 */
export interface ChangedSettingsBox {
  rows: ChangedSettingRow[]
  legacyAjmt: boolean
}

const WGVL_GRP = 'WGVL'

/** 두 값이 값으로 다른가(`60.00` = `60`). */
function differs(a: number, b: number): boolean {
  return Number(a) !== Number(b)
}

/** 구 스냅샷 ② — 적용 내역은 있는데 행에 `bscStngVl` 이 없다(Step 6 이전). */
export function isLegacyAjmt(vrblAjmt: AnlsVrblAjmt): boolean {
  return vrblAjmt.vrbls.some((r) => r.bscStngVl == null)
}

/**
 * 적용 내역 → 상자 행. 대상은 **노출 행 중 적용값 ≠ 기본값**이다(구 스냅샷 ② 는 기본값을 몰라 적용값 ≠ 설정값 — 일시 조정분만).
 * 출처는 `aplcnVl ≠ stngVl` 이면 `이번 분석에서 조정`, 아니면 `설정 변경`.
 *
 * @param scope 템플릿의 스코프(`AnlsVrblScope` 상수명). 🔴 `vrblAjmt` 행에는 스코프·`vrblSeCd` 가 없어
 *   가중치 묶음 소속을 `COEFF_SCOPE_META[scope].weight.vars` 로 정하고, 구 스냅샷 ② 의 숨김 폴백도 이 값으로 찾는다.
 */
export function changedRowsFromAjmt(vrblAjmt: AnlsVrblAjmt, scope: CoeffScope): ChangedSettingRow[] {
  const legacy = isLegacyAjmt(vrblAjmt)
  const weight = COEFF_SCOPE_META[scope].weight
  const wgvlCds = new Set(weight?.inVrblVls ? weight.vars.map((v) => v.vrblCd) : [])
  const toRow = (r: AnlsVrblAjmtItem): ChangedSettingRow => ({
    vrblNm: r.vrblNm,
    bscVl: legacy || r.bscStngVl == null ? r.stngVl : r.bscStngVl,
    aplcnVl: r.aplcnVl,
    unitNm: r.unitNm,
    src: differs(r.aplcnVl, r.stngVl) ? 'AJMT' : 'STNG',
    ...(wgvlCds.has(r.vrblCd) ? { grp: WGVL_GRP } : {}),
  })
  const rows = vrblAjmt.vrbls.filter((r) => isExposedAjmtRow(r, scope)).map(toRow)
  const changed = (r: ChangedSettingRow) => differs(r.aplcnVl, r.bscVl)
  const wgvlChanged = rows.some((r) => r.grp === WGVL_GRP && changed(r))
  return rows.filter((r) => (r.grp === WGVL_GRP ? wgvlChanged : changed(r)))
}

/**
 * 집중구역 가중치(`weights[]` — `AnlsVrblScope.HOTSPOT` 밖이라 `vrblAjmt` 에 없다 · D11) → 상자 행.
 * `stngVl` 이 적용값이고 출처는 `wgvlAjmtYn` 으로 가른다. 한 쌍 중 하나라도 기본값과 다르면 묶음 전체다.
 */
export function changedRowsFromWeights(
  weights: readonly AnalysisWeight[],
  wgvlAjmtYn: 'Y' | 'N' | null | undefined,
): ChangedSettingRow[] {
  const rows: ChangedSettingRow[] = weights
    .filter((w) => w.expsrYn !== 'N' && w.bscStngVl != null)
    .map((w) => ({
      vrblNm: w.vrblNm,
      bscVl: w.bscStngVl,
      aplcnVl: w.stngVl,
      unitNm: w.unitNm,
      src: wgvlAjmtYn === 'Y' ? 'AJMT' : 'STNG',
      grp: WGVL_GRP,
    }))
  return rows.some((r) => differs(r.aplcnVl, r.bscVl)) ? rows : []
}

/**
 * 템플릿용 조립 — `vrblAjmt == null`(구 스냅샷 ①)이면 `null`(상자 생략).
 * `extraRows`(집중구역 가중치 행)는 앞에 둔다. `※` 문장은 구 스냅샷 ② 의 **적용 내역 행**에만 해당한다 —
 * 가중치 행만 실렸으면 붙이지 않는다(가중치 행은 `bscStngVl` 이 있어 새 스냅샷처럼 판정된다).
 */
export function changedSettingsBoxOf(
  vrblAjmt: AnlsVrblAjmt | null | undefined,
  scope: CoeffScope,
  extraRows: readonly ChangedSettingRow[] = [],
): ChangedSettingsBox | null {
  if (vrblAjmt == null) return null
  const ajmtRows = changedRowsFromAjmt(vrblAjmt, scope)
  const rows = [...extraRows, ...ajmtRows]
  return {
    rows,
    legacyAjmt: isLegacyAjmt(vrblAjmt) && (ajmtRows.length > 0 || rows.length === 0),
  }
}

const CHANGED_SETTINGS_TITLE = '적용 설정'
export const CHANGED_SETTINGS_NONE = '기본 설정으로 분석했습니다.'
export const CHANGED_SETTINGS_LEGACY_NOTE = '※ 이 보고서는 설정 변경 여부를 기록하지 않았습니다.'
const SRC_LABEL: Record<ChangedSettingRow['src'], string> = {
  STNG: '설정 변경',
  AJMT: '이번 분석에서 조정',
}

/** 계수 값 표기 — 서버 `BigDecimal` 유래라 `0.8500` → `0.85`. 천 단위 구분을 넣는다. */
function fmtSettingVl(v: number): string {
  return Number(v).toLocaleString('ko-KR', { maximumFractionDigits: 4 })
}

/** 상자 줄 — 가중치 묶음은 ` · ` 로 이어 한 줄, 출처는 줄 끝에 한 번(묶음에 일시 조정이 하나라도 있으면 그것). */
function changedSettingLines(rows: readonly ChangedSettingRow[]): string[] {
  const item = (r: ChangedSettingRow) =>
    `${r.vrblNm} : ${fmtSettingVl(r.bscVl)} → ${fmtSettingVl(r.aplcnVl)}${r.unitNm ?? ''}`
  const lines: string[] = []
  const done = new Set<string>()
  for (const r of rows) {
    if (r.grp == null) {
      lines.push(`${item(r)} (${SRC_LABEL[r.src]})`)
      continue
    }
    if (done.has(r.grp)) continue
    done.add(r.grp)
    const grp = rows.filter((x) => x.grp === r.grp)
    const src = grp.some((x) => x.src === 'AJMT') ? 'AJMT' : 'STNG'
    lines.push(`${grp.map(item).join(' · ')} (${SRC_LABEL[src]})`)
  }
  return lines
}

/**
 * `적용 설정` 상자 HTML — 5종 공용 · 기존 기준 상자 바로 뒤에 둔다. `null`(구 스냅샷 ①)이면 빈 문자열.
 * 0건이면 상자 대신 한 줄(새 스냅샷 `기본 설정으로 분석했습니다.` · 구 스냅샷 ② `※ …기록하지 않았습니다.` —
 * ② 는 설정 변경 여부를 몰라 "기본 설정" 이라고 단정하지 않는다).
 */
export function changedSettingsBlockHtml(box: ChangedSettingsBox | null): string {
  if (box == null) return ''
  const lineStyle = 'font-size:11px;color:#555;line-height:1.7;'
  if (box.rows.length === 0) {
    const text = box.legacyAjmt ? CHANGED_SETTINGS_LEGACY_NOTE : CHANGED_SETTINGS_NONE
    return `<div data-pdf-keep style="margin-top:12px;${lineStyle}">${escapeHtml(text)}</div>`
  }
  const body = changedSettingLines(box.rows)
    .map((line) => `<div style="${lineStyle}">· ${escapeHtml(line)}</div>`)
    .join('')
  const legacy = box.legacyAjmt
    ? `<div style="${lineStyle}margin-top:4px;">${escapeHtml(CHANGED_SETTINGS_LEGACY_NOTE)}</div>`
    : ''
  return `<div data-pdf-keep style="margin-top:12px;padding:14px 16px;background-color:#f5f6f8;border-radius:8px;">
        <div style="font-size:12px;font-weight:800;margin-bottom:6px;">${escapeHtml(CHANGED_SETTINGS_TITLE)}</div>
        ${body}${legacy}
      </div>`
}

/**
 * A4 문서 폭(px). 210mm @ 96dpi ≈ 794px.
 * 이 폭으로 캡처해야 PDF 한 페이지에 가로로 딱 맞는다.
 */
const A4_WIDTH_PX = 794

/**
 * 보고서 한 건의 본문 HTML 을 만든다.
 *
 * @param report 목록 행. 제목에 `rptpNm` 만 사용한다(나머지 칸은 샘플 단계에서 쓰지 않는다).
 */
export function buildReportHtml(report: ReportListItem): string {
  return `<div class="rpt-doc" style="width:${A4_WIDTH_PX}px;padding:40px;background:#fff;font-family:'Pretendard',sans-serif;color:#111;box-sizing:border-box;">
      <h1 style="font-size:24px;margin:0 0 8px;">${escapeHtml(report.rptpNm)}</h1>
      <p style="color:#888;font-size:12px;margin:0 0 24px;">제주 주차민원분석 솔루션 · 보고서 샘플</p>
      <p style="font-size:16px;margin:0;">Hello World</p>
    </div>`
}
