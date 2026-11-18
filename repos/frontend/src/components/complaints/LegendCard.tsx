/*
 * 민원현황(GIS) §5 — "범례" 카드 (TSK-3874).
 *
 * 가이드 §5 결정: 지도 우측 하단 floating 카드 안에
 *   - 고정 3개 위험 단계 색상 행 (심각/경고/주의, 2026-08-27 회의 결정)
 *   - 동적 2개 (§4 토글 의존 — 공영주차장 P 마커, 행정동 경계 점선)
 *
 * 색상/임계값은 rank 백분위 10/25/50 이고, 분모는 **분석레이어가 반환한 zone 목록**이다
 * (전체 읍면동 수가 아니다).
 *
 * 🔁 **2026-10-02 (TSK-4197) — 두 가지를 고쳤다.**
 *   1. `RISK_LEVEL_THRESHOLDS` 가 `legendConstants.ts` 와 이 파일에 **중복 정의**돼 있었다.
 *      공용 상수를 import 하도록 바꿨다 — 임계값·색을 바꿀 곳은 이제 `legendConstants.ts`
 *      **한 곳**이고, 지도 polygon·순위 카드 dot 과 같은 값을 본다.
 *   2. 🔴 **shortage view 의 `{label} 그룹` 표기를 걷었다.** 그 표기의 근거는
 *      *"Jenks k=3 등급 분류의 결과물임을 분명히 한다"*(2026-09-04)였는데,
 *      **수요부족이 더 이상 Jenks 등급을 쓰지 않는다**(D14 — 매퍼가 등급 필터 없이 전 zone 을
 *      주고 `pickRiskGrade` 한 층만 남았다). 이제 **세 레이어가 완전히 같은 규칙**이므로
 *      범례도 같은 문구(`심각 (상위 10%)`)를 쓴다. 🔴 `view` prop 도 그래서 없앴다 —
 *      범례는 레이어와 무관하다.
 *
 * 🆕 TSK-4295 — **히트맵 모드**(`displayMode='heatmap'`)에서는 위험 단계 3행 대신 그라디언트 막대
 *   "많음 ↔ 적음" + `(상대 밀도)` 를 그린다. 🔴 색은 **조회 범위 안의 상대값**(강도 상한 = 칸 건수 P95)이라
 *   절대 등급으로 읽히지 않게 등급 단어(심각·경고·주의)를 쓰지 않는다. 공영주차장·행정동 경계 행은 그대로다.
 *
 * 🆕 제목에 **무엇을 · 어느 기간으로** 칠한 범례인지 적는다 — 시안 `범례 (민원 건수 기준 · 최근 1주일)`.
 *   지표 이름은 레이어(`metricLabel`), 기간은 §2 조회 조건(`periodLabel` — `periodText.periodLabel`)에서 온다.
 *   등급 행 자체는 여전히 레이어·필터와 무관하다(아래 문단).
 *
 * §2 필터는 범례 행에 영향 없음 (가이드 §5 결정). §4 토글 state 만 따라 변한다.
 * 분석레이어 hotspot zone 경계는 별도 API 미정의라 범례에 추가하지 않음 (UX 결정 후).
 */

import type { DisplayMode, LayerToggles } from './LayerCard.tsx'
import { HEATMAP_GRADIENT, RISK_LEVEL_THRESHOLDS } from './legendConstants.ts'

/**
 * ⚠️ **view 별 보조 텍스트는 두지 않는다**(2026-09-04 사용자 결정 — `LEGEND_CONTEXT` 매핑과
 * `.minwon-card__legend-context` CSS 를 그때 함께 걷었다). 🔁 당시 근거였던
 * *"shortage 는 라벨 단어('심각 그룹')만으로 Jenks 등급임이 전달된다"* 는 **2026-10-02 에
 * 무효가 됐다**(그 표기 자체를 폐지 — 위 헤더 2번). 결론은 그대로 남는다 — 세 레이어가
 * 같은 규칙이라 레이어별로 다르게 설명할 것이 없다.
 */

export interface LegendCardProps {
  /** §4 토글 state — 동적 2개 행의 조건부 렌더에 사용. */
  toggles: LayerToggles
  /** §4 지도 표시 방식 (TSK-4295). 기본 `'emd'`. `heatmap` 이면 등급 3행 대신 그라디언트 막대. */
  displayMode?: DisplayMode
  /** 제목의 지표 이름 — `민원 건수` · `단속 건수` · `부족률`. 없으면 제목은 `범례` 만. */
  metricLabel?: string
  /** 제목의 기간 — `어제` · `최근 1주일` · `최근 1개월` · `yyyy.mm.dd ~ yyyy.mm.dd`. */
  periodLabel?: string
}

/**
 * 히트맵 범례 막대의 `background` (TSK-4295). `HEATMAP_GRADIENT` 를 정지점 **내림차순**으로 펼쳐
 * 왼쪽 = 많음(빨강) → 오른쪽 = 적음(파랑) 으로 만든다(라벨 순서와 일치). 정지점 `t` 는 `(1 - t)` 위치에
 * 놓으므로 가장 낮은 정지점(0.2) 오른쪽 구간은 그 색(파랑)으로 이어진다.
 * ⚠️ `Object.entries` 의 키는 문자열(`'0.2'`·`'1'`)이라 `Number()` 로 바꿔 정렬한다.
 * 상수만 읽으므로 모듈 로드 시 1회 계산한다.
 */
const HEATMAP_LEGEND_BACKGROUND = `linear-gradient(to right, ${Object.entries(HEATMAP_GRADIENT)
  .map(([stop, color]) => [Number(stop), color] as const)
  .sort((a, b) => b[0] - a[0])
  .map(([stop, color]) => `${color} ${Math.round((1 - stop) * 100)}%`)
  .join(', ')})`

export default function LegendCard({
  toggles,
  displayMode = 'emd',
  metricLabel,
  periodLabel,
}: LegendCardProps) {
  const detail = [metricLabel && `${metricLabel} 기준`, periodLabel].filter(Boolean).join(' · ')
  return (
    <aside className="minwon-card minwon-card--legend" aria-label="범례">
      <section className="minwon-card__section">
        <h2 className="minwon-card__title minwon-card__title--legend">
          {detail ? `범례 (${detail})` : '범례'}
        </h2>

        {/* 고정 3개 — 분석레이어 위험 단계 색상 코딩. 레이어가 켜져 있는 동안 항상 표시.
            🔁 2026-10-02 — view 별 분기 없음(세 레이어가 같은 `pickRiskGrade` 규칙이다).
            🆕 TSK-4295 — 히트맵 모드면 이 3행 대신 그라디언트 막대(등급 단어 없음 — 상대 밀도). */}
        {displayMode === 'heatmap' ? (
          <div className="minwon-card__legend-heatmap">
            <div
              className="minwon-card__legend-gradient"
              style={{ background: HEATMAP_LEGEND_BACKGROUND }}
              aria-hidden="true"
            />
            <div className="minwon-card__legend-gradient-labels">
              <span>많음</span>
              <span>적음</span>
            </div>
            <span className="minwon-card__legend-label">(상대 밀도)</span>
          </div>
        ) : (
          <ul className="minwon-card__legend-list">
            {RISK_LEVEL_THRESHOLDS.map((row) => (
              <li key={row.percentile} className="minwon-card__legend-row">
                <span
                  className="minwon-card__legend-dot"
                  style={{ background: row.color }}
                  aria-hidden="true"
                />
                <span className="minwon-card__legend-label">
                  {`${row.label} (${row.sub})`}
                </span>
              </li>
            ))}
          </ul>
        )}

        {/* 동적 2개 — §4 토글 ON 일 때만 표시. 가이드 §5 결정. */}
        {toggles.publicParking && (
          <ul className="minwon-card__legend-list">
            <li className="minwon-card__legend-row">
              <span
                className="minwon-card__legend-parking"
                aria-hidden="true"
              >
                P
              </span>
              <span className="minwon-card__legend-label">공영주차장</span>
            </li>
          </ul>
        )}
        {toggles.adminBoundary && (
          <ul className="minwon-card__legend-list">
            <li className="minwon-card__legend-row">
              <span
                className="minwon-card__legend-dashed"
                aria-hidden="true"
              />
              <span className="minwon-card__legend-label">행정동 경계</span>
            </li>
          </ul>
        )}
      </section>
    </aside>
  )
}
