/*
 * 감정 분류 기준 안내 (i 아이콘 + 팝오버) — 시안 `G5` 이식.
 * 민원현황 KPI "민원 감정 분석" 카드 라벨 옆과 오른쪽 "부정 민원 상위 지역" 카드 헤더에 둔다.
 *
 * 🔴 공용 `InfoTip` 을 쓰지 않는 이유 — 그것은 CSS 전용 absolute 팝업이라, 오른쪽 컬럼
 *    (`.minwon-main-grid__right` — `overflow-y:auto`) 안에서는 이 정도 길이의 본문이 잘린다.
 *    시안처럼 **body 포털 + `position:fixed`** 로 띄우고 아이콘 위치에서 좌표를 계산한다
 *    (화면 아래가 모자라면 위로 뒤집는다).
 *
 * 열림: hover·focus 동안 미리보기, 클릭하면 고정(바깥 클릭·Esc·닫기 버튼으로 해제). 시안 동작 그대로다.
 * 문구는 시안 원문 그대로다 — 분류 규칙을 바꾸면 여기와 감정 분석 프롬프트를 함께 본다.
 */
import { useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'

const POP_WIDTH = 340
const GAP = 8
const EDGE = 12

interface SentimentCriteriaTipProps {
  /** 아이콘 크기. 시안 — KPI 카드 14 · 목록 카드 16. */
  size?: 14 | 16
  /** 팝오버를 아이콘 왼쪽 끝(`left`)에 맞출지 오른쪽 끝(`right`)에 맞출지. */
  align?: 'left' | 'right'
}

export default function SentimentCriteriaTip({
  size = 16,
  align = 'left',
}: SentimentCriteriaTipProps) {
  const anchorRef = useRef<HTMLSpanElement>(null)
  const popRef = useRef<HTMLDivElement>(null)
  const [hover, setHover] = useState(false)
  const [pinned, setPinned] = useState(false)
  const [pos, setPos] = useState({ top: -9999, left: -9999 })
  const open = hover || pinned

  // 열려 있는 동안 아이콘 위치에 맞춰 좌표를 다시 잡는다(스크롤·리사이즈 포함).
  useLayoutEffect(() => {
    if (!open) return
    const place = () => {
      const anchor = anchorRef.current
      if (!anchor) return
      const r = anchor.getBoundingClientRect()
      const h = popRef.current?.offsetHeight ?? 360
      let left = align === 'right' ? r.right - POP_WIDTH : r.left - GAP
      left = Math.max(EDGE, Math.min(left, window.innerWidth - POP_WIDTH - EDGE))
      let top = r.bottom + GAP
      if (top + h > window.innerHeight - EDGE) {
        const above = r.top - GAP - h
        top = above >= EDGE ? above : Math.max(EDGE, window.innerHeight - h - EDGE)
      }
      setPos({ top: Math.round(top), left: Math.round(left) })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open, align])

  // 고정 상태에서만 바깥 클릭·Esc 로 닫는다.
  useLayoutEffect(() => {
    if (!pinned) return
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node
      if (anchorRef.current?.contains(t) || popRef.current?.contains(t)) return
      setPinned(false)
    }
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') setPinned(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [pinned])

  const close = () => {
    setPinned(false)
    setHover(false)
  }

  const onAnchorKey = (e: KeyboardEvent<HTMLSpanElement>) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      setPinned((v) => !v)
    } else if (e.key === 'Escape') {
      close()
    }
  }

  return (
    <span
      ref={anchorRef}
      className="senti-info"
      role="button"
      tabIndex={0}
      aria-label="감정 분류 기준 안내"
      aria-expanded={open}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onFocus={() => setHover(true)}
      onBlur={() => setHover(false)}
      onKeyDown={onAnchorKey}
      onClick={(e) => {
        e.stopPropagation()
        setPinned((v) => !v)
      }}
    >
      <svg viewBox="0 0 24 24" width={size} height={size} fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" />
        <path d="M12 11v5.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        <circle cx="12" cy="7.6" r="1.2" fill="currentColor" />
      </svg>
      {open &&
        createPortal(
          <div
            ref={popRef}
            className="senti-pop"
            role="tooltip"
            style={{ top: pos.top, left: pos.left }}
            onClick={(e) => e.stopPropagation()}
            onMouseEnter={() => setHover(true)}
            onMouseLeave={() => setHover(false)}
          >
            <button type="button" className="senti-pop__close" aria-label="닫기" onClick={close}>
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
                <path
                  d="M6 6l12 12M18 6L6 18"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
              </svg>
            </button>
            <div className="senti-pop__title">감정 분류 기준</div>
            <ul className="senti-pop__defs">
              <li>
                <span className="senti-tag senti-tag--pos">긍정</span>
                불만 없음 (신고·문의·요청)
              </li>
              <li>
                <span className="senti-tag senti-tag--neu">보통</span>
                불만은 있으나 감정이 실리지 않음 (사무적 지적, 가벼운 아쉬움)
              </li>
              <li>
                <span className="senti-tag senti-tag--neg">부정</span>
                민원 처리 주체(기관·부서·시설 관리·제도와 처리 결과·응대)를 향한 불만이 감정으로
                드러남
              </li>
            </ul>
            <div className="senti-pop__sec">
              <div className="senti-pop__sub">
                ‘부정’으로 보는 신호 <span>(하나 이상)</span>
              </div>
              <p className="senti-pop__txt">
                미조치·지연 항의, 응대 항의, 따지는 반문, 부정 감정어(완곡한 표현 포함), 불신·단념,
                짜증 섞인 어미·말 끊기, 반말 힐난
              </p>
            </div>
            <div className="senti-pop__sec">
              <div className="senti-pop__sub">‘부정’으로 올리지 않는 경우</div>
              <ul className="senti-pop__list">
                <li>사연이 심각해도 감정 없이 사실만 설명한 경우</li>
                <li>제3자(차주·업자·이웃·다른 운전자)나 상황에 대한 불만</li>
                <li>무뚝뚝하거나 짧은 대답, 방언 어미</li>
                <li>사실 확인·경위 설명·절차 문의</li>
              </ul>
            </div>
            <div className="senti-pop__rule">
              욕설·성희롱 단어가 있으면 강도와 관계없이 <strong>‘부정’</strong>
            </div>
          </div>,
          document.body,
        )}
    </span>
  )
}
