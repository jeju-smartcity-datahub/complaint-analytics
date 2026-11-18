import Modal from '../common/Modal'

/*
 * 배치 운영 화면의 확인 대화상자 (TSK-4337 Step 8) — 중지 · 실패 처리.
 * `ConfirmDeleteModal` 은 보조 문구가 "삭제하면 되돌릴 수 없습니다." 로 고정이라 쓰지 않고,
 * 공용 `Modal`(sm) 셸 위에 같은 버튼(`.mt-act`)을 얹는다.
 */

interface BatchConfirmModalProps {
  title: string
  message: string
  confirmLabel: string
  busy?: boolean
  error?: string | null
  onCancel: () => void
  onConfirm: () => void
}

export default function BatchConfirmModal({
  title,
  message,
  confirmLabel,
  busy = false,
  error,
  onCancel,
  onConfirm,
}: BatchConfirmModalProps) {
  return (
    <Modal
      title={title}
      variant="sm"
      onClose={onCancel}
      footSplit
      footer={
        <>
          <button className="mt-act" type="button" disabled={busy} onClick={onCancel}>
            취소
          </button>
          <button className="mt-act mt-act--del" type="button" disabled={busy} onClick={onConfirm}>
            {busy ? '요청 중…' : confirmLabel}
          </button>
        </>
      }
    >
      <p className="bo-text">{message}</p>
      {error && (
        <p className="bo-error" role="alert">
          {error}
        </p>
      )}
    </Modal>
  )
}
