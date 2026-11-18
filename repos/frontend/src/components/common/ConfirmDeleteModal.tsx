import { useEffect, type CSSProperties } from 'react'

/*
 * 삭제 확인 팝업. 시안(https://innopamsolution.github.io/jeju-datahub/)의 공통 삭제 확인 컴포넌트
 * DOM·스타일을 그대로 옮겼다(TSK-4274 — 보고서 목록 > 직접 생성 보고서 삭제 팝업 기준).
 *
 * `Modal` 과 다른 점(시안과 동일)
 *   - 헤더(제목·닫기 X)가 없다. 질문 문구가 곧 제목이라 `aria-label` 로도 쓴다.
 *   - `role="alertdialog"` · 보조 문구 `삭제하면 되돌릴 수 없습니다.` 고정.
 *   - 버튼은 `.mt-act`(취소) ↔ `.mt-act--del`(삭제)를 좌우로 벌린다.
 *
 * 시안에 없는 것: 서버 왕복 중 버튼 잠금(`busy`)과 실패 문구(`error`). 시안은 목데이터라 실패 경로가 없다.
 * 스크롤 잠금은 `Modal` 과 같이 직전 값을 기억했다가 원복한다.
 */

interface ConfirmDeleteModalProps {
  /** 질문 문구 (예: `보고서를 삭제하시겠습니까?`). */
  message: string
  /** 확인 버튼 문구. 기본 `삭제`. */
  confirmLabel?: string
  /** 삭제 요청 중 — 두 버튼을 잠그고 확인 버튼을 `삭제 중…` 으로 바꾼다. */
  busy?: boolean
  /** 실패 문구. 있으면 보조 문구 아래에 띄운다. */
  error?: string | null
  /** 취소 (overlay 클릭 · Escape · 취소 버튼). */
  onCancel: () => void
  onConfirm: () => void
}

const BODY: CSSProperties = { padding: '28px 28px 20px' }
const MESSAGE: CSSProperties = {
  margin: 0,
  fontSize: 'var(--body1-size)',
  fontWeight: 'var(--weight-bold)' as CSSProperties['fontWeight'],
  color: 'var(--text-strong)',
}
const SUB: CSSProperties = {
  margin: '8px 0 0',
  fontSize: 'var(--label1-size)',
  color: 'var(--text-neutral)',
}
const ERROR: CSSProperties = {
  margin: '8px 0 0',
  fontSize: 'var(--label2-size)',
  color: 'var(--status-negative)',
}
const FOOT: CSSProperties = { paddingTop: 0 }

export default function ConfirmDeleteModal({
  message,
  confirmLabel = '삭제',
  busy = false,
  error,
  onCancel,
  onConfirm,
}: ConfirmDeleteModalProps) {
  useEffect(() => {
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
    }
    document.addEventListener('keydown', onKeyDown)

    return () => {
      document.body.style.overflow = prevOverflow
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [onCancel])

  return (
    <div className="modal">
      <div className="modal__overlay" onClick={onCancel} />
      <div
        className="modal__dialog modal__dialog--sm"
        role="alertdialog"
        aria-modal="true"
        aria-label={message}
      >
        <div className="modal__body" style={BODY}>
          <p style={MESSAGE}>{message}</p>
          <p style={SUB}>삭제하면 되돌릴 수 없습니다.</p>
          {error && (
            <p role="alert" style={ERROR}>
              {error}
            </p>
          )}
        </div>
        <div className="modal__foot modal__foot--split" style={FOOT}>
          <button className="mt-act" type="button" disabled={busy} onClick={onCancel}>
            취소
          </button>
          <button className="mt-act mt-act--del" type="button" disabled={busy} onClick={onConfirm}>
            {busy ? '삭제 중…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
