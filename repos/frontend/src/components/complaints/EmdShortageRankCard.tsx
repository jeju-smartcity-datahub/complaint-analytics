/*
 * 민원현황(GIS) §7 — "읍·면·동 수요부족 순위" 카드 (TSK-4181 Step 2-3).
 *
 * 결정 (계획서 TSK-4167 D1·D4·D5·D6):
 *   - D1 — 신규 API 를 만들지 않는다. `GET /api/hotspots/ranking?view=shortage` 의 `zones[]` 를
 *     그대로 다시 그린다.
 *   - D4 — 행 틀은 §7·§8 공용 `minwon-emd-compare__*` 클래스만 재사용한다 (CSS 신규 0건).
 *   - D5 — 표시값은 `shortageRatio`(부족률)이고 🔴 정렬은 응답 `rank` 를 그대로 쓴다.
 *     백엔드 정렬은 `out_psdr ASC` 인데 `shortageRatio = 1/(PSDR+1)` 이라 PSDR 오름차순 ⇔
 *     부족률 내림차순으로 순서가 정확히 같다. 프론트에서 다시 정렬하지 않는다.
 *     🔴 `shortageRatio` 가 `null`(분모 0 방어)이면 `—` 로 그린다 — `0%` 로 쓰면 "부족 없음" 으로
 *     읽혀 뜻이 반대가 된다.
 *   - D6 — 🔁 **2026-10-02 TSK-4197(D14) 로 폐기됐다.** 종전엔 `shortage` view 가 Jenks "심각"
 *     등급만 반환해(`fn_jenks_shortage(..., '심각')`) 순위가 **그 등급 안의 순위**였고, 그래서
 *     부제에 "심각 등급" 을 적었다. 지금 매퍼는 6번째 인자에 `NULL::text` 을 넘겨 **주차민원 > 0 인
 *     전 zone** 을 반환하므로 응답 `rank` 가 곧 전 읍·면·동 순위다(부제도 함께 걷었다 — 아래 §부제 주석).
 *   - D13·D14 (2026-09-30 Step 9) — 🔴 **rank 등급 3종(심각·경고·주의)만 노출하고 종전 15행 상한을
 *     쓰지 않는다.** 분모는 **지도와 같은 전체 zone 수**(= 필터 *전* `zones.length`)이고 판정은 공용
 *     `pickRiskGrade` 한 곳이다. 🔴 필터 *후* 길이로 다시 재면 경계가 움직여 순환한다.
 *     🔁 2026-10-02(D14) — 종전의 ⚠️ *"이 카드에서는 `심각` 이 두 층위다"* 는 해소됐다. 응답이 더 이상
 *     Jenks `심각` 집합이 아니라 전 zone 이라, **등급을 정하는 것은 `pickRiskGrade` 한 층뿐이다.**
 *   - R4 — `zoneNm` 이 `null` 이면 `emdCd` 를 그대로 보여주고, React key 는
 *     `` `${emdCd ?? 'x'}-${rank}` `` 로 만든다.
 *
 *   - 🔴 TSK-4197 Step 4-3 — 부족률 옆에 증감 배지(`DeltaBadge`)를 `unit="%p"` 로 붙인다.
 *     `부족률 ` 접두는 떨어뜨리지 않는다(시안 §5). 🔁 TSK-4322 — 배지는 화살표만이라 폭 부담이 줄었다.
 *
 * ⚠️ `shortage` view 의 `cvlcptCnt`·`crdnCnt` 는 SQL 리터럴 `0` 이라 쓰지 않는다
 * (`HotspotRankingMapper.xml:146-147`).
 */

import { useState } from 'react'
import type { HotspotRankingZone } from '../../api/complaints'
import Icon from '../Icon'
import DeltaBadge from './DeltaBadge.tsx'
import InfoTip from '../common/InfoTip.tsx'
import { DELTA_HOVER_NOTE, EMD_COMPARE_DEFAULT_LIMIT, RANK_GRADE_NOTE, RISK_GRADE_COLOR, pickRiskGrade } from './legendConstants.ts'

export interface EmdShortageRankCardProps {
  /** `GET /api/hotspots/ranking?view=shortage` 의 zones[]. 정렬은 응답 `rank` 순 그대로 (D5). */
  zones: HotspotRankingZone[]
  /** zones 전용 로딩 상태 — KPI 의 `loading` 과 다른 state 다 (ComplaintsPage 의 `zonesLoading`). */
  loading?: boolean
  /**
   * 행 클릭 → 지도(§6) 강조·팝업 (TSK-4167 Step 11). 미지정이면 행은 클릭 대상이 아니다.
   * 🔴 강조·팝업을 여기서 만들지 않는다 — `emdCd` 만 올려보내고 `MinwonMap` 의 `onZoneClick`
   *    (지도 클릭과 같은 함수)이 전부 처리한다.
   */
  onSelectEmd?: (emdCd: string) => void
  /** 증감 툴팁 머리말 — 조회 조건별 `전일·전주·전월·전기 대비`(`periodText.compareLabel`). */
  compareLabel?: string
}

export default function EmdShortageRankCard({
  zones,
  loading = false,
  onSelectEmd,
  compareLabel,
}: EmdShortageRankCardProps) {
  const [expanded, setExpanded] = useState(false)
  // 🔴 분모는 필터 **전** 전체 길이다 (D13 — 필터 후 길이로 재면 순환한다).
  const total = zones.length
  const graded = zones.filter((z) => pickRiskGrade(z.rank, total) !== null)
  // 🔴 15행 상한 제거 — 더보기를 누르면 등급이 있는 행 전부.
  const limit = expanded ? graded.length : EMD_COMPARE_DEFAULT_LIMIT
  const slice = graded.slice(0, limit)
  const showMore = graded.length > EMD_COMPARE_DEFAULT_LIMIT
  const moreCount = Math.max(0, graded.length - EMD_COMPARE_DEFAULT_LIMIT)

  return (
    <aside className="minwon-emd-compare" aria-label="읍·면·동 수요부족 순위">
      <header className="minwon-emd-compare__header">
        <div className="minwon-emd-compare__title-row">
          <h3 className="minwon-emd-compare__title">읍·면·동 수요부족 순위</h3>
          {/* TSK-4242 — 출처: HotspotRankingMapper.xml selectShortage(부족률 · shortageDeltaPp · deltaPctInferred) · WHERE s.out_cvlcpt_sum > 0. 3번 둘째 문장(생활권 수급 등급과 방식이 다름)은 빼지 않는다. */}
          <InfoTip
            placement="block"
            label="읍·면·동 수요부족 순위 안내"
            title="읍·면·동 수요부족 순위"
            items={[
              '부족률 = 주차 관련 민원 ÷ (공영주차면 + 주차 관련 민원)입니다. 높을수록 공급에 비해 민원이 많습니다.',
              '부족률이 높은 순이며, 주차 관련 민원이 없는 지역은 제외합니다.',
              `${RANK_GRADE_NOTE} 생활권 시뮬레이션 수급 등급(부족률 구간)과는 정하는 방식이 다릅니다.`,
              `▲▼ 는 직전 같은 길이 기간 부족률과의 차이입니다. ${DELTA_HOVER_NOTE} 직전 기간에 공급도 민원도 없으면 비교할 수 없어 — 로 표시합니다.`,
            ]}
          />
        </div>
        {/* 🔁 2026-10-02 (TSK-4197 · D14) — 부제에서 `Jenks 심각 등급 안의 rank 순위` 를 걷었다.
            응답이 더 이상 Jenks `심각` 집합이 아니라 **주차민원 > 0 인 전 zone** 이고,
            등급은 `pickRiskGrade` 백분위 **한 층**뿐이다. 종전의 "심각이 두 층위" 는 사라졌다.
            🔴 그래서 민원·단속 카드와 **같은 형식**(집계 기준 한 줄)으로 맞춘다.
            🔁 2026-10-19 TSK-4256 — 문구 끝을 시안대로 `기준` 으로(민원·단속은 `집계` 유지 — 시안이 그렇다). */}
        <p className="minwon-emd-compare__subtitle">주차 수요·공급 부족률 기준</p>
      </header>

      {loading && zones.length === 0 ? (
        <p className="minwon-emd-compare__empty">불러오는 중…</p>
      ) : graded.length === 0 ? (
        <p className="minwon-emd-compare__empty">표시할 데이터가 없습니다</p>
      ) : (
        <ol className="minwon-emd-compare__list">
          {slice.map((zone) => {
            // 필터를 통과했으므로 등급이 반드시 있다 — 항상 색이 붙는다.
            const dotColor = RISK_GRADE_COLOR[pickRiskGrade(zone.rank, total)!]
            // D5 — null 은 `—`. 0% 로 쓰면 "부족 없음" 으로 읽혀 뜻이 반대가 된다.
            const ratioLabel =
              zone.shortageRatio == null ? '—' : `${Math.round(zone.shortageRatio * 100)}%`
            // 공급·수요 둘 다 null 이면 아랫줄 자체를 생략한다.
            // 🔁 2026-09-30 (사용자 요청) — 표기 순서를 시안(민원현황2.pdf §5)에 맞춰
            //    `공급 N면 · 수요 N` → **`수요 N대 · 공급 N면`** 으로 바꿨다.
            // ⚠️ `demandIndex` 는 계약상 **단위 없는 지수**(`공급 면수 + 주차 민원 수` — api/complaints.ts:158)라
            //    `대`(차량 수)는 엄밀히는 그 뜻이 아니다. 시안 표기를 따른 것이며, 산식이 바뀌면 이 단위도 함께 본다.
            const hasSupplyDemand = zone.pkltCpctySum != null || zone.demandIndex != null
            // 🔴 `emdCd` 가 `null` 인 zone(`LEFT JOIN tb_admdst` 미매칭)은 클릭 대상에서 제외한다 —
            //    지도 레지스트리의 키가 `emdCd` 라 부를 대상이 없다.
            const emdCd = zone.emdCd
            const select = onSelectEmd && emdCd ? () => onSelectEmd(emdCd) : undefined
            return (
              <li
                // R4 — `emdCd` 는 타입상 null 일 수 있어 단독 key 로 쓰지 않는다.
                key={`${zone.emdCd ?? 'x'}-${zone.rank}`}
                className="minwon-emd-compare__row minwon-emd-compare__row--stack"
              >
                {/* 🔴 TSK-4167 Step 11-5 — 행 안을 진짜 `<button>` 으로 감싼다(카드 3종 동일 방식).
                    종전 `<li role="button">` 은 `<li>` 의 암묵 `listitem` 을 덮어써 스크린리더가
                    "N개 중 M번째" 를 읽지 못했다. `emdCd` 가 `null` 인 행은 `disabled` —
                    포커스를 받지 않고 `listitem` 은 유지된다. */}
                <button
                  type="button"
                  className="minwon-emd-compare__row-btn"
                  disabled={!select}
                  onClick={select}
                >
                  <span className="minwon-emd-compare__rank">{zone.rank}</span>
                  <span className="minwon-emd-compare__main">
                    <span className="minwon-emd-compare__top">
                      <span className="minwon-emd-compare__emd-name">
                        <span
                          className="minwon-emd-compare__dot"
                          style={{ background: dotColor }}
                          aria-hidden="true"
                        />
                        {zone.zoneNm ?? zone.emdCd ?? '—'}
                      </span>
                      <span className="minwon-emd-compare__count">
                        {/* 🔁 2026-09-30 (사용자 요청) — 시안(민원현황2.pdf §5)의 부족률 칸은 값 자체가
                            `부족률 72%` 다. 값 앞에 `부족률` 을 붙인다.
                            🔴 새 CSS 를 만들지 않는다(D4) — 기존 `__count-num` 한 요소에 문자열로 담는다.
                            폭: 사이드바 최소 300px · 값 ≈95px(18px bold) · 지역명은 `overflow:hidden` 으로
                            줄어들 뿐 넘치지 않는다(`__top > __emd-name{min-width:0}`). */}
                        <span className="minwon-emd-compare__count-num">부족률 {ratioLabel}</span>
                        {/* 직전 동일 길이 구간 대비 부족률 증감 (TSK-4197 Step 4-3).
                            🔁 TSK-4322 — 화면은 화살표, 수치는 툴팁이다.
                            🔴 단위가 `%p` 다 — `shortageDeltaPp` 는 소수 1자리이고, 자리수는 `unit` 에 묶여 있다.
                            🔴 `deltaPctInferred = true` ⟺ 값이 `null`(직전 분모 0 — 비교 불가)이라
                            `DeltaBadge` 가 `—` 로 그리고 툴팁에 사유를 적는다. `0%p`(변동 없음)와 툴팁으로 구분된다(D12). */}
                        <DeltaBadge
                          deltaPct={zone.shortageDeltaPp ?? null}
                          inferred={zone.deltaPctInferred}
                          unit="%p"
                          compareLabel={compareLabel}
                        />
                      </span>
                    </span>
                    {hasSupplyDemand && (
                      <span className="minwon-emd-compare__top-type">
                        수요 {zone.demandIndex ?? '—'}대 · 공급 {zone.pkltCpctySum ?? '—'}면
                      </span>
                    )}
                  </span>
                </button>
              </li>
            )
          })}
        </ol>
      )}

      {showMore && (
        <button
          type="button"
          className="minwon-emd-compare__more"
          onClick={() => setExpanded((v) => !v)}
          aria-expanded={expanded}
        >
          {/* 🔴 15행 상한이 없으므로 `moreCount` 를 캡하지 않는다 (D13). */}
          {expanded ? '접기' : `더보기 (${moreCount})`}
          <Icon
            name="chevron-down"
            size={14}
            className={`minwon-emd-compare__more-ic${expanded ? ' is-expanded' : ''}`}
          />
        </button>
      )}

      {/* footnote — §8 카드와 같은 패턴 (`minwon-emd-compare__footnote`). */}
      <p className="minwon-emd-compare__footnote">
        부족률 = 주차 민원 수 ÷ (공급 면수 + 주차 민원 수)
      </p>
    </aside>
  )
}
