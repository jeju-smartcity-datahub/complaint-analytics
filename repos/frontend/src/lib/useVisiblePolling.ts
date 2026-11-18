import { useEffect, useRef } from 'react'

/**
 * 화면이 보일 때만 도는 폴링 (배치 운영 화면).
 *
 * - `intervalMs` 가 null 이면 멈춘다. 값이 바뀌면 타이머를 새로 건다.
 * - 탭이 숨겨진 동안(`document.visibilityState !== 'visible'`)은 호출을 건너뛰고,
 *   다시 보이면 **즉시 1회** 부른 뒤 간격을 이어 간다.
 * - 앞 호출이 끝나기 전에는 다음 호출을 하지 않는다(setTimeout 연쇄 — 느린 응답이 겹쳐 쌓이지 않는다).
 * - 언마운트 시 타이머·리스너를 정리한다.
 *
 * 첫 호출은 `intervalMs` 뒤다 — 초기 로드는 호출부가 따로 한다.
 */
export function useVisiblePolling(callback: () => unknown, intervalMs: number | null) {
  const cbRef = useRef(callback)
  useEffect(() => {
    cbRef.current = callback
  }, [callback])

  useEffect(() => {
    if (intervalMs == null) return
    let stopped = false
    let inFlight = false
    let timer: number | undefined

    const schedule = () => {
      if (!stopped) timer = window.setTimeout(tick, intervalMs)
    }

    const tick = async () => {
      timer = undefined
      if (stopped) return
      if (document.visibilityState === 'visible' && !inFlight) {
        inFlight = true
        try {
          await cbRef.current()
        } catch {
          // 호출부가 오류 상태를 스스로 그린다 — 폴링은 끊지 않는다.
        } finally {
          inFlight = false
        }
      }
      schedule()
    }

    const onVisible = () => {
      if (document.visibilityState !== 'visible' || timer === undefined) return
      window.clearTimeout(timer)
      void tick()
    }

    schedule()
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      stopped = true
      if (timer !== undefined) window.clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [intervalMs])
}
