import type { ZoneRcmdtnLot, ZoneRcmdtnShareCrtrVls } from '../../api/zoneRcmdtn'
import {
  EXCL_LOT_COLOR,
  FEE_PRRT_LEGEND_TITLE,
  FEE_RVW_LEGEND_TITLE,
  SHARE_LEGEND_TITLE,
  exclLegendLabel,
  feePrrtLegendRows,
  feeRvwLegendRows,
  isFeeRvwScr,
  shareLegendRows,
} from './zoneRcmdtnView'

/*
 * 구역추천 — 지도 범례 (공유주차제 보기의 범례는 파일 끝).
 *
 * 마크업은 집중구역(`HotspotMap`)의 `.sim-legend > .map-legend` 그대로다 — 스타일은
 * `src/styles/analysis.css` 가 이미 갖고 있고(`.sim-legend` 는 `.sim-stage` 기준 좌하단 절대배치)
 * 여기서 CSS 를 새로 만들지 않는다. 🔴 그래서 이 컴포넌트는 **`.sim-stage` 의 직계 자식**이어야 한다.
 *
 * **축이 두 갈래다**(`anlsMthd` · 판정은 `isFeeRvwScr` 한 곳).
 * - **새 방식**(`'RVW_SCR'`): 제목 `우선도 단계` + **항상 3줄**(최우선 100↑ · 우선 60↑ ·
 *   후순위 60 미만 — `feeRvwLegendRows`). 경계가 고정값이라 결과와 무관하다. 색은 서버 `rvwGrdCd` 의 단계색이다.
 * - **구 방식**(`anlsMthd` 없음): 축은 `우선순위`(배정 민원 등간격 3단계 · 시안 범례)다.
 *   줄·경계 숫자는 `feePrrtLegendRows` 가 `lots` 로 계산한다. 🔴 화면에 있는 등급만 줄로 낸다.
 * 어느 쪽이든 행 점·지도 핀과 같은 함수라 어긋나지 않는다.
 * 맨 아래 회색 `후보 아님` 줄. **회색 핀이 실제로 있을 때만**(`exclCnt > 0`) 붙는다.
 * ⚠️ 줌 14 미만에서 회색 핀이 숨겨져도 줄은 **유지**한다 — 확대하면 보인다는 안내를 겸한다.
 * (종전 증가율 범례의 제목·줄 함수 `INCRS_LEGEND_TITLE`·`incrsLegendRows` 는 `zoneRcmdtnView` 에 남아 있다.)
 */

export interface ZoneRcmdtnLegendProps {
  /** 후보 주차장(서버 순서) — 등간격 경계와 "보이는 등급" 을 여기서 낸다. */
  lots: readonly ZoneRcmdtnLot[]
  /**
   * 지도에 그려지는 회색 핀 수(`drawableExclLots(...).length`). 0 이면 `후보 아님` 줄이 없다.
   * 🔴 지도와 **같은 함수**로 센 값을 넘긴다 — 좌표 없는 행까지 세면 없는 핀을 설명한다.
   */
  exclCnt?: number
  /** 응답 `crtrVls.swtcTrgtMinCpcty` — 줄 문구의 `{N}면 미만`. 상수로 박지 않는다. */
  minCpcty?: number | null
  /** 응답 `anlsMthd`. `'RVW_SCR'` 이면 우선도 단계 범례, 없으면 구 방식(우선순위) 범례다. */
  anlsMthd?: 'RVW_SCR' | null
}

export default function ZoneRcmdtnLegend({
  lots,
  exclCnt = 0,
  minCpcty = null,
  anlsMthd = null,
}: ZoneRcmdtnLegendProps) {
  const rvwScr = isFeeRvwScr(anlsMthd)
  const rows = rvwScr ? feeRvwLegendRows() : feePrrtLegendRows(lots)
  return (
    <div className="sim-legend">
      <div className="map-legend">
        <div className="map-legend__title">{rvwScr ? FEE_RVW_LEGEND_TITLE : FEE_PRRT_LEGEND_TITLE}</div>
        {rows.map((row) => (
          <div className="row" key={row.key}>
            {/* 색은 CSS 변수 표기 그대로 넣는다 — DOM `style` 은 `var()` 를 받는다. */}
            <span className="dot" style={{ background: row.color }} />
            {row.label}
          </div>
        ))}
        {exclCnt > 0 && (
          <div className="row">
            <span className="dot" style={{ background: EXCL_LOT_COLOR }} />
            {exclLegendLabel(minCpcty)}
          </div>
        )}
      </div>
    </div>
  )
}

/*
 * 공유주차제 적용 보기의 범례 — `우선도 단계` (이름 변경, 종전 `검토 등급`).
 *
 * 🔁 **2026-10-13 사용자 요청 — 항목이 셋이다**: `최우선 (2 이상)` · `우선 (1 이상 2 미만)` ·
 * `후순위 (1 미만)`(괄호 안 숫자는 검토 지수 경계다). 종전에는 공통코드 라벨 둘(깃발이 있는 등급)뿐이었다.
 * 🔴 라벨·색은 깃발·목록 점과 **같은 표**(`zoneRcmdtnView.SHARE_LVLS`)에서 오고, 괄호 안의 `2` 는
 * 응답 `crtrVls.coreMltp` 다(`shareLegendRows`). 공통코드를 받지 않는다.
 * 🔁 2026-10-27 — 깃발이 목록의 모든 행에 서므로 세 줄 모두 지도 깃발과 목록 색 점을 함께 설명한다.
 *
 * ⚠️ 위 우선순위 범례와 **같은 자리**(`.sim-legend`)를 쓴다 — 페이지가 보기에 따라 둘 중 하나만 띄운다.
 */
export interface ZoneRcmdtnShareLegendProps {
  /** 적용된 판정 기준 — `매우 높음` 의 하한(`coreMltp`)이 여기서 온다. */
  crtrVls: ZoneRcmdtnShareCrtrVls
}

export function ZoneRcmdtnShareLegend({ crtrVls }: ZoneRcmdtnShareLegendProps) {
  return (
    <div className="sim-legend">
      <div className="map-legend">
        <div className="map-legend__title">{SHARE_LEGEND_TITLE}</div>
        {shareLegendRows(crtrVls).map((row) => (
          <div className="row" key={row.key}>
            <span className="dot" style={{ background: row.color }} />
            {row.label}
          </div>
        ))}
      </div>
    </div>
  )
}
