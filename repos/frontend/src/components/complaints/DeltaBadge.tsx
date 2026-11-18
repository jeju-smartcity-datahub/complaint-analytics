/*
 * 증감 배지 — 민원(§7)·단속·수요부족 세 카드 공용 (TSK-4197 Step 4-1).
 *
 * 🔴 **정의는 이 파일 한 곳뿐이다.** 종전에는 `EmdCompareCard.tsx` 안의 로컬 함수였는데,
 *    단속·수요부족 카드가 같은 규칙을 쓰게 되면서 공용 모듈로 옮겼다(색·기호 규칙이 갈리면
 *    같은 화면에서 증감 표기가 두 벌이 된다). 단속 카드가 민원 카드를 import 하는 모양을
 *    피하려고 `legendConstants.ts` 옆에 둔다.
 *
 * 표기 규칙 (🔁 TSK-4322 — 시안 변경. 화면은 화살표만, 수치와 사유는 툴팁):
 *  | 입력                  | 화면 | 색   | 툴팁·스크린리더                          |
 *  | deltaPct > 0          | ▲    | 빨강 | 전기 대비 ▲13%                            |
 *  | deltaPct < 0          | ▼    | 초록 | 전기 대비 ▼3%                             |
 *  | 0 · null              | —    | 검정 | 전기 대비 변동 없음                       |
 *  | inferred && 값 있음   | ▲    | 빨강 | 전기 대비 ▲100% · 이전 기간 0건 치환 사유 |
 *  | inferred && 값 없음   | —    | 검정 | 전기 대비 비교 불가 · 사유                |
 *
 * 🔴 **분기 순서가 의미를 가른다** (TSK-4197 Step 4-3). `inferred` 를 `deltaPct == null` **앞**에 둔다.
 *    shortage 는 `deltaPctInferred = true` ⟺ `shortageDeltaPp = NULL` 이라(D12), `null` 을 먼저 보면
 *    "비교 불가" 와 "변동 없음" 의 툴팁이 구분되지 않는다.
 *
 * 🔴 **소수 자리수는 `unit` 에 묶는다.** `%p`(수요부족)만 소수 1자리다. 민원·단속은 정수 그대로다.
 *
 * 🆕 툴팁 머리말 `전기 대비` 는 조회 조건에 따라 `전일·전주·전월·전기 대비` 로 바뀐다
 *    (`compareLabel` prop · 문구 정본은 `periodText.ts`). 위 표의 `전기 대비` 는 기본값이다.
 *
 * 🔴 툴팁은 CSS `data-tip`(`minwon.css`)이다 — 키보드 포커스에도 뜨고 스크린리더는 `sr-only` 를 읽는다
 *    (TSK-4322 D14). `title` 을 함께 쓰면 툴팁이 이중으로 뜬다.
 */

import { COLOR_DELTA_DOWN, COLOR_DELTA_FLAT, COLOR_DELTA_UP } from './legendConstants.ts'

export interface DeltaBadgeProps {
  /** 증감값. 민원·단속은 `%`(정수), 수요부족은 `%p`(소수 1자리). `null` 이면 `—`. */
  deltaPct: number | null
  /** 추론값 여부. 민원·단속 = 직전 0건 분모 1 치환 / 수요부족 = 직전 분모 0 (비교 불가). */
  inferred: boolean
  /** 단위 접미. 기본 `'%'` — `'%p'` 일 때만 소수 1자리로 찍는다. */
  unit?: '%' | '%p'
  /** 툴팁 머리말 — `periodText.compareLabel()`. 기본 `'전기 대비'`. */
  compareLabel?: string
}

export default function DeltaBadge({
  deltaPct,
  inferred,
  unit = '%',
  compareLabel = '전기 대비',
}: DeltaBadgeProps) {
  const { glyph, color, tip } = describeDelta(deltaPct, inferred, unit, compareLabel)
  return (
    <span className="minwon-emd-compare__delta" style={{ color }} data-tip={tip}>
      <span aria-hidden="true">{glyph}</span>
      <span className="sr-only">{tip}</span>
    </span>
  )
}

function describeDelta(
  deltaPct: number | null,
  inferred: boolean,
  unit: '%' | '%p',
  cmp: string,
) {
  // 🔴 자리수를 `unit` 에 묶는다 — 기본 `'%'` 는 종전처럼 정수 그대로다.
  const value =
    deltaPct == null ? '' : unit === '%p' ? Math.abs(deltaPct).toFixed(1) : String(Math.abs(deltaPct))

  // 🔴 `inferred` 를 `deltaPct == null` 보다 먼저 본다 (위 주석 참고).
  if (inferred) {
    return deltaPct == null
      ? { glyph: '—', color: COLOR_DELTA_FLAT, tip: `${cmp} 비교 불가 · 직전 기간에 공급도 민원도 없습니다` }
      : {
          glyph: '▲',
          color: COLOR_DELTA_UP,
          tip: `${cmp} ▲${value}${unit} · 이전 기간 0건이라 1건으로 바꿔 계산한 값입니다`,
        }
  }
  if (deltaPct != null && deltaPct > 0) {
    return { glyph: '▲', color: COLOR_DELTA_UP, tip: `${cmp} ▲${value}${unit}` }
  }
  if (deltaPct != null && deltaPct < 0) {
    return { glyph: '▼', color: COLOR_DELTA_DOWN, tip: `${cmp} ▼${value}${unit}` }
  }
  return { glyph: '—', color: COLOR_DELTA_FLAT, tip: `${cmp} 변동 없음` }
}
