import { useEffect, useRef, useState, type ReactNode } from 'react'

/*
 * 커스텀 스크롤 썸 래퍼 (TSK-4121 Step 7).
 *
 * 시안(https://innopamsolution.github.io/jeju-datahub/) 번들의 동명 컴포넌트를 그대로 옮겼다 —
 * 스크롤 영역(`.recent`·`.report`)은 네이티브 스크롤바를 숨기고(`scrollbar-width:none`),
 * 대신 `.scroll-wrap__thumb` 을 비율로 그려 얇은 표시선만 보이게 한다.
 *
 * 동작(시안과 동일)
 *   - 내용이 넘치지 않으면(`scrollHeight <= clientHeight + 1`) 썸을 **그리지 않는다**.
 *   - `top` = 스크롤 위치 비율, `height` = 보이는 영역 비율 (둘 다 %).
 *   - `scroll` 이벤트 + `ResizeObserver` 로 갱신한다. `ResizeObserver` 는 **관찰 요소 자신의
 *     크기**만 본다 — `.recent` 가 `max-height:616px` 에 닿기 전까지는 목록이 늘 때 높이도 함께
 *     늘어 콜백이 돈다. 상한에 닿은 뒤에는 크기가 고정이라 콜백이 없고, 그때는 첫 스크롤에서
 *     비율이 보정된다(시안과 같은 동작).
 *
 * 🔴 `.scroll-wrap__thumb` 은 `pointer-events:none` 이라 **드래그 조작용이 아니다**(표시 전용).
 *    스크롤은 내부 영역의 네이티브 스크롤이 그대로 담당한다.
 */

/** 썸의 위치·높이(%). 내용이 넘치지 않으면 `null` 이라 썸 자체가 렌더되지 않는다. */
interface ThumbMetrics {
  top: number
  height: number
}

export default function ScrollWrap({
  className,
  children,
}: {
  /** 실제 스크롤이 일어나는 내부 요소의 클래스 (`recent` · `report`). */
  className: string
  children: ReactNode
}) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const [thumb, setThumb] = useState<ThumbMetrics | null>(null)

  useEffect(() => {
    const el = scrollRef.current
    if (!el) return

    const update = () => {
      // +1 은 소수점 반올림 여유. 이것 없이 비교하면 딱 맞는 높이에서도 썸이 깜빡인다(시안과 동일).
      if (el.scrollHeight <= el.clientHeight + 1) {
        setThumb(null)
        return
      }
      setThumb({
        top: (el.scrollTop / el.scrollHeight) * 100,
        height: (el.clientHeight / el.scrollHeight) * 100,
      })
    }

    update()
    el.addEventListener('scroll', update, { passive: true })
    const ro = new ResizeObserver(update)
    ro.observe(el)

    return () => {
      el.removeEventListener('scroll', update)
      ro.disconnect()
    }
  }, [])

  return (
    <div className="scroll-wrap">
      <div className={className} ref={scrollRef}>
        {children}
      </div>
      {thumb && (
        <div
          className="scroll-wrap__thumb"
          style={{ top: `${thumb.top}%`, height: `${thumb.height}%` }}
          aria-hidden="true"
        />
      )}
    </div>
  )
}
