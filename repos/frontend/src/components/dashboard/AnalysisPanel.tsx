import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Icon from '../Icon'
import { fetchAnalyses, type AnalysisItem } from '../../api/dashboard'
import { policySimRunTitle } from '../policysim/policySimView'

/*
 * 하단 3카드 중 "정책 효과 시뮬레이션" 패널 — 시안의 `.panel` + `.list__row` 구조를 유지한다.
 * 데이터는 마운트 시 /api/dashboard/analyses 로 직접 조회한다.
 *
 * 바뀐 것
 *   - 이 카드는 **`POLICY_SIM` 실행만** 본다(`fetchAnalyses(4, 'POLICY_SIM')`).
 *     정책 효과 시뮬레이션 화면이 붙었다. 이 유형의 실행 행(`TB_ANLS_EXCN`)은
 *     그 화면의 **[시뮬레이션 실행]**(`POST /api/analysis/policy-sim/results` — 차단 상태
 *     `NO_ENF`·`NO_SPLY` 는 저장하지 않는다)만 만든다(실행 = 계산 + 저장).
 *     그래서 아무도 실행하지 않았으면 카드 본문이
 *     `데이터 없음` 인 것이 여전히 **정상**이다. `HOTSPOT` 행이 보이면 필터가 안 먹은 것이다.
 *   - 상태 뱃지(실행상태 코드 → 뱃지 클래스 매핑)를 **화면에서만** 제거했다.
 *     응답 필드(`excnSttsCd`·`excnSttsNm`)는 그대로 내려오므로 필요하면 되살릴 수 있다.
 *     `.list__row .name { flex: 1 }` 이라 뱃지를 빼도 행 레이아웃은 유지된다 — CSS 변경 없음.
 *   - `전체 →` 이동 대상이 `/analysis/policy-simulation` 이다(정책 변수 설정 화면).
 *   - 행 제목은 `정책시뮬레이션`(유형명) 대신 **그 실행의 조건**이다 —
 *     `제주시 연동 · 주차장 확충 · 공한지 조성 · 100면` 처럼(지역 · 정책 유형 · 방식 · 값). 조합은 `policySimRunTitle()` 한 곳이며,
 *     재료가 없거나 모르는 코드면 종전대로 유형명으로 떨어진다.
 *   - 행을 누르면 정책 시뮬레이션 화면으로 가서 **시나리오 비교의 그 행을 누른 상태**로 연다.
 *     보고서 카드(`ReportPanel`)와 같은 수법으로 `state: { selectRsltId }` 를 넘긴다 — 이름이 아니라
 *     `rsltId` 다(같은 조건으로 여러 번 실행하면 이름이 겹친다). `rsltId` 가 없으면 클릭할 수 없다.
 */

export default function AnalysisPanel() {
  const [items, setItems] = useState<AnalysisItem[] | null>(null)
  const [error, setError] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    let cancelled = false
    fetchAnalyses(4, 'POLICY_SIM')
      .then((d) => {
        if (!cancelled) setItems(d)
      })
      .catch(() => {
        if (!cancelled) {
          setItems(null)
          setError(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div className="card panel">
      <div className="card-head">
        <h2 className="card-head__title">
          <span className="panel__icon">
            <Icon name="chart" size={20} />
          </span>
          정책 효과 시뮬레이션
        </h2>
        <Link to="/analysis/policy-simulation" className="card-link">
          전체 <Icon name="arrow-right" size={16} />
        </Link>
      </div>

      {error ? (
        <div className="panel-state">불러오기 실패</div>
      ) : items === null ? (
        <div className="panel-state">불러오는 중…</div>
      ) : items.length === 0 ? (
        /* 2026-10-02 — `데이터 없음` 보다 **무엇이 없는지** 말한다. */
        <div className="panel-state">아직 실행한 시뮬레이션이 없습니다</div>
      ) : (
        <div className="list">
          {items.map((a) => (
            <div
              className={a.rsltId != null ? 'list__row list__row--link' : 'list__row'}
              key={a.excnId}
              onClick={
                a.rsltId != null
                  ? () =>
                      navigate('/analysis/policy-simulation', {
                        state: { selectRsltId: a.rsltId },
                      })
                  : undefined
              }
            >
              <span className="name">{policySimRunTitle(a) ?? (a.anlsTypeNm || a.anlsTypeCd)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
