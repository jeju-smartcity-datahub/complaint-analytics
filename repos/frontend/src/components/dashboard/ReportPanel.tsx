import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Icon from '../Icon'
import { fetchReports, type ReportItem } from '../../api/dashboard'

/*
 * 하단 3카드 중 "보고서" 패널 — 시안의 `.panel` + `.list__row` 구조를 유지한다.
 * 데이터는 마운트 시 /api/dashboard/reports 로 직접 조회한다 (Step 11).
 * 미확인(대기) 건(sttsCd === 'WAIT')이면 대기 뱃지를 붙인다.
 */

/**
 * `crtYmd`(생성 일시)를 `YYYY.MM.DD HH:mm` 으로 포맷한다 — 시안 형식이다(TSK-4169).
 * 종전에는 `HH:mm` 만 보여 **며칠 전 보고서인지 알 수 없었다**(목록이 최신 4건이라 날짜가 갈린다).
 *
 * 🔴 `new Date` 에 넘기지 않고 **문자열을 정규식으로 자른다** — `crtYmd` 는 타임존이 없는 로컬
 * 일시라, 파싱 규칙에 따라 날짜·시각이 밀린다. `DataFreshness.fmt()` 와 같은 수법이다.
 * null·형식 불일치면 빈칸으로 떨어뜨려 화면을 죽이지 않는다.
 */
function formatDateTime(crtYmd: string | null): string {
  const m = crtYmd?.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  if (!m) return ''
  const [, y, mo, d, hh, mi] = m
  return `${y}.${mo}.${d} ${hh}:${mi}`
}

export default function ReportPanel() {
  const [items, setItems] = useState<ReportItem[] | null>(null)
  const [error, setError] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    let cancelled = false
    // 🔴 직접 생성분만 — 자동 생성 보고서는 이 카드에 섞이지 않는다
    //    (TSK-4169 Step 10-1 · 요구 · 백엔드 crtType 필터).
    fetchReports(4, 'MANUAL')
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
          <span className="panel__icon panel__icon--green">
            <Icon name="document" size={20} />
          </span>
          보고서
        </h2>
        {/* 이 카드의 `전체` 는 직접 생성 탭으로 들어간다 (TSK-4085 Step 9 / D5). */}
        <Link to="/reports?crtType=MANUAL" className="card-link">
          전체 <Icon name="arrow-right" size={16} />
        </Link>
      </div>

      {error ? (
        <div className="panel-state">불러오기 실패</div>
      ) : items === null ? (
        <div className="panel-state">불러오는 중…</div>
      ) : items.length === 0 ? (
        /* 🔄 TSK-4169(2026-10-02) — `데이터 없음` 보다 **무엇이 없는지** 말한다.
           ⚠️ 이 카드는 `crtType=MANUAL` 만 조회하므로 "자동 생성분은 있는데 직접 생성분이 0건" 일
           때도 이 문구가 뜬다 — 그게 맞다(카드가 보여주는 것이 직접 생성분이다). */
        <div className="panel-state">아직 생성된 보고서가 없습니다</div>
      ) : (
        <div className="list">
          {items.map((r) => (
            /*
             * 항목 클릭 → 보고서 목록(직접 생성 탭)으로 이동하고 그 행을 강조한다 (TSK-4169 Step 10-2).
             * 🔴 이름이 아니라 `rptpId` 를 넘긴다 — 보고서명은 중복될 수 있어(같은 주기 재생성)
             *    이름으로 찾으면 엉뚱한 행이 강조된다(시안은 `state:{highlight: 이름}` 이지만 따르지 않았다).
             */
            <div
              className="list__row list__row--link"
              key={r.rptpId}
              onClick={() =>
                navigate('/reports?crtType=MANUAL', { state: { highlightRptpId: r.rptpId } })
              }
            >
              <span className="name">{r.rptpNm}</span>
              {r.sttsCd === 'WAIT' && <span className="badge badge--wait">대기</span>}
              <span className="time">{formatDateTime(r.crtYmd)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
