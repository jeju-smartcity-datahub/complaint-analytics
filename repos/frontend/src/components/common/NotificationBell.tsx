import { useEffect, useRef, useState } from 'react'
import type { MouseEvent as ReactMouseEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import Icon from '../Icon'
import {
  fetchDailyAlerts,
  markAlertRead,
  type RiskAlertDaily,
  type RiskAlertItem,
} from '../../api/alerts'
import type { ComplaintFocusState } from '../../lib/complaintFocus'
import { toYmd } from '../../lib/dateRange'

/*
 * 상단 알림 벨 + 팝오버.
 *
 * 시안의 마크업·클래스를 그대로 옮겼고
 * 목데이터 3건 대신 실 API(`api/alerts.ts` 의 fetchDailyAlerts)를 쓴다.
 *
 * 시안과 같은 부분
 *   - .bell > button + .bell__badge / .notif-pop 5블록 구조
 *   - 닫힘 2종: 바깥 클릭(document click + ref.contains) · Esc
 *   - 팝오버 자신은 stopPropagation 으로 바깥 클릭 핸들러에서 빠진다
 *   - 헤더 문구 `전일 기준 <b>N</b>건` (문구를 바꾸지 않는다)
 *   - 하단 `전체보기` → /alerts/inquiry (행 클릭과 갈린다)
 *
 * 시안과 의도적으로 다른 부분
 *   - 목록 개수 제한이 없다. 시안의 3건은 목데이터일 뿐 상한이 아니다
 *   - 배지 = unreadCnt(미읽음), 헤더 = totalCnt(전체) — **서로 다른 숫자다**
 *   - 읽은 알림은 .notif-row--read 로 흐리게 (시안 CSS 에 이미 있는 클래스)
 *   - 행이 <span> 이 아니라 <button> 이다 (클릭 가능). 시안 CSS 가 이미 버튼 전제다
 *   - 조회 중 / 실패 상태 표시 (시안은 정적 목데이터라 실패 경로가 없다)
 *   - 행 제목에 `(심각 단계)` 접미가 없다. 시안 목데이터는 `연동 민원 45% 증가 (심각 단계)` 지만,
 *     배치가 실제로 저장하는 문구는 `emdNm + " 민원 " + 증가율 + "% 증가"` 뿐이다
 *     (RiskAlertJudgeProcessor.java:94. DB 실측 예: `연동 민원 22% 증가`).
 *     단계는 문구가 아니라 .notif-row__dot 색으로 드러낸다 — notiCtt 를 프론트가 재조립하지 않는다
 *   - 행 클릭은 알림 조회가 아니라 **민원현황**으로 가서 그 알림의 읍면동을 활성화한다.
 *     요청 계약은 `lib/complaintFocus.ts` — 1회성 history state 다
 */

/**
 * 위험단계 → 점(.notif-row__dot) 색. **inline style 로 넣는다** — CSS 에는 색 선언이 없다(시안과 동일).
 *
 * ⚠️ 폴백 `#8A8F99` 를 떼지 말 것 — `--cool-neutral-50` 은 우리 `tokens.css` 에 없다.
 * (CRIT·WARN·CAUT 외 코드는 서버 화이트리스트에 막혀 실제로는 도달하지 않는 방어 분기다.)
 */
const DOT_COLORS: Record<string, string> = {
  CRIT: 'var(--red-50)',
  WARN: 'var(--orange-50)',
  CAUT: 'var(--blue-50)',
}

function dotColor(riskLvlCd: string): string {
  return DOT_COLORS[riskLvlCd] ?? 'var(--cool-neutral-50, #8A8F99)'
}

/**
 * `ocrnDt`(타임존 없는 LocalDateTime 문자열) → 시안 표기 `YYYY.MM.DD`.
 * ⚠️ `toISOString()` 은 UTC 로 되돌려 **날짜가 하루 밀린다** — 조회 화면 `toYmd()` 처럼 직접 조립한다.
 */
function toDisplayYmd(ocrnDt: string): string {
  const d = new Date(ocrnDt)
  if (Number.isNaN(d.getTime())) return ocrnDt
  const mm = `${d.getMonth() + 1}`.padStart(2, '0')
  const dd = `${d.getDate()}`.padStart(2, '0')
  return `${d.getFullYear()}.${mm}.${dd}`
}

export default function NotificationBell() {
  const [open, setOpen] = useState(false)
  const [data, setData] = useState<RiskAlertDaily | null>(null)
  const [failed, setFailed] = useState(false)
  const navigate = useNavigate()
  const wrapRef = useRef<HTMLDivElement>(null) // 🔴 .bell 래퍼에 건다 (아래 근거)

  /** 전일 알림 조회. 마운트 시 1회 + 팝오버를 **열 때마다**(다른 탭에서 읽었을 수 있다). */
  const load = async () => {
    try {
      const res = await fetchDailyAlerts()
      setData(res)
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }

  // 배지를 보여야 하므로 팝오버를 열기 전에 받아 둔다. 마운트 1회만이라 deps 는 빈 배열이다
  // (⚠️ <React.StrictMode> 때문에 dev 에서는 이 이펙트가 2번 돈다 — 진입 시 호출 2회가 정상).
  useEffect(() => {
    void load()
  }, [])

  /*
   * 닫힘 2종 — 바깥 클릭 · Esc.
   * 🔴 ref 는 `.notif-pop` 이 아니라 `.bell` 래퍼에 걸려 있다. 팝오버에만 걸면
   *    벨 버튼 클릭이 "바깥 클릭"으로 잡혀 토글과 동시에 닫혀 팝오버가 열리지 않는다.
   */
  useEffect(() => {
    if (!open) return

    const handleDocClick = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }

    document.addEventListener('click', handleDocClick)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('click', handleDocClick)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open])

  /** 벨 클릭 — 열 때마다 재조회한다. stopPropagation 은 바깥 클릭 핸들러 방어(시안과 동일). */
  const handleBellClick = (e: ReactMouseEvent<HTMLButtonElement>) => {
    e.stopPropagation()
    const next = !open
    setOpen(next)
    if (next) void load()
  }

  // 행 클릭 — 읽음 처리 실패해도 이동은 막지 않는다 (읽음은 부가 동작이다)
  const handleRowClick = async (item: RiskAlertItem) => {
    setOpen(false)
    if (item.prslYn !== 'Y') {
      setData(
        (prev) =>
          prev && {
            ...prev,
            unreadCnt: Math.max(0, prev.unreadCnt - 1),
            items: prev.items.map((i) => (i.notiId === item.notiId ? { ...i, prslYn: 'Y' } : i)),
          },
      )
      try {
        await markAlertRead(item.notiId)
      } catch {
        // 🔴 이동을 막지 않는다. 다음 팝오버 열림에서 서버 값으로 다시 맞춰진다
        //    (열 때마다 재조회하므로 낙관적 갱신이 어긋나도 저절로 수렴한다).
      }
    }
    // 🔴 날짜는 toYmd 로 조립한다 — toISOString() 은 UTC 라 하루 밀린다
    const state: ComplaintFocusState = {
      focusEmd: { emdCd: item.emdCd, ymd: toYmd(new Date(item.ocrnDt)) },
    }
    navigate('/complaints', { state })
  }

  const goInquiry = () => {
    setOpen(false)
    navigate('/alerts/inquiry')
  }

  const unreadCnt = data?.unreadCnt ?? 0
  const totalCnt = data?.totalCnt ?? 0
  const items = data?.items ?? []

  return (
    <div className="bell" ref={wrapRef}>
      <button type="button" aria-label="알림" onClick={handleBellClick}>
        <Icon name="bell" size={22} />
        {/* 조회 실패면 숫자를 만들 근거가 없으므로 배지를 숨긴다 */}
        {!failed && unreadCnt > 0 && <span className="bell__badge">{unreadCnt}</span>}
      </button>

      {open && (
        <div className="notif-pop" onClick={(e) => e.stopPropagation()}>
          <div className="notif-head">
            <span className="notif-head__ic">
              <Icon name="bell" size={18} />
            </span>
            <span className="notif-head__title">알림</span>
            <span className="notif-head__hint">
              전일 기준 <b>{totalCnt}</b>건
            </span>
            <button
              type="button"
              className="notif-head__x"
              aria-label="닫기"
              onClick={() => setOpen(false)}
            >
              <Icon name="close" size={18} />
            </button>
          </div>

          <div className="notif-list">
            {failed ? (
              <div className="notif-row">
                <span className="notif-row__body">
                  <span className="notif-row__title">
                    불러오지 못했습니다{' '}
                    <button type="button" className="btn-edit" onClick={() => void load()}>
                      다시 시도
                    </button>
                  </span>
                </span>
              </div>
            ) : items.length === 0 ? (
              <div className="notif-row">
                <span className="notif-row__body">
                  <span className="notif-row__title">전일 기준 새 알림이 없습니다.</span>
                </span>
              </div>
            ) : (
              items.map((item) => (
                <button
                  key={item.notiId}
                  type="button"
                  className={`notif-row${item.prslYn === 'Y' ? ' notif-row--read' : ''}`}
                  onClick={() => void handleRowClick(item)}
                >
                  <span
                    className="notif-row__dot"
                    style={{ background: dotColor(item.riskLvlCd) }}
                  />
                  <span className="notif-row__body">
                    <span className="notif-row__title">{item.notiCtt}</span>
                  </span>
                  <span className="notif-row__time">{toDisplayYmd(item.ocrnDt)}</span>
                </button>
              ))
            )}
          </div>

          <div className="notif-foot">
            <button
              type="button"
              className="notif-foot__btn notif-foot__btn--primary"
              onClick={goInquiry}
            >
              전체보기
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
