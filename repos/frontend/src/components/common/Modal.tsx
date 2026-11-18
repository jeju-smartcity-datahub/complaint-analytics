import { useEffect, type ReactNode } from 'react'

/*
 * 모달 셸. 시안(https://innopamsolution.github.io/jeju-datahub/)의 모달 DOM·동작을 그대로 옮겼다.
 * 보고서 미리보기·설정 폼(TSK-4026)과 알림 화면(TSK-4028)이 함께 쓴다.
 *
 * 동작 (시안과 동일)
 *   - overlay 클릭 → 닫기
 *   - Escape → 닫기
 *   - 열려 있는 동안 `body` 스크롤 잠금
 *
 * 시안과 다른 점: 시안은 잠금 해제 시 `document.body.style.overflow = ''` 로 비워버린다.
 * 여기서는 **직전 값을 기억했다가 원복**한다 — 모달이 겹쳐 뜨거나 바깥에서 스크롤을 제어하는
 * 경우에 남의 설정을 지우지 않기 위함이다.
 */

interface ModalProps {
  /** 헤더 제목. `aria-label` 로도 쓰인다. */
  title: string
  /** 닫기 요청 (overlay 클릭 · Escape · 닫기 버튼). */
  onClose: () => void
  /**
   * 다이얼로그 폭 변형.
   * `sm` = `.modal__dialog--sm`(460px) · `rpt` = `.modal__dialog--rpt`(640px) · 없으면 기본 720px.
   */
  variant?: 'sm' | 'rpt'
  /** 푸터 내용. 없으면 푸터를 그리지 않는다. */
  footer?: ReactNode
  /** 푸터를 좌우로 벌린다(`.modal__foot--split`). 삭제 ↔ 저장처럼 성격이 다른 버튼을 나눌 때. */
  footSplit?: boolean
  /** 본문. */
  children: ReactNode
}

/** 시안과 동일한 닫기(X) 아이콘. `icons.ts` 는 fill 기반이라 stroke 아이콘은 여기 둔다. */
const CloseIcon = (
  <svg viewBox="0 0 24 24" fill="none" width="24" height="24" aria-hidden="true">
    <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
  </svg>
)

export default function Modal({
  title,
  onClose,
  variant,
  footer,
  footSplit = false,
  children,
}: ModalProps) {
  useEffect(() => {
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)

    return () => {
      document.body.style.overflow = prevOverflow
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [onClose])

  const variantClass = variant ? ` modal__dialog--${variant}` : ''

  return (
    <div className="modal">
      <div className="modal__overlay" onClick={onClose} />
      <div className={`modal__dialog${variantClass}`} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal__head">
          <h2 className="modal__title">{title}</h2>
          <button type="button" className="modal__close" aria-label="닫기" onClick={onClose}>
            {CloseIcon}
          </button>
        </div>

        <div className="modal__body">{children}</div>

        {footer && (
          <div className={`modal__foot${footSplit ? ' modal__foot--split' : ''}`}>{footer}</div>
        )}
      </div>
    </div>
  )
}
