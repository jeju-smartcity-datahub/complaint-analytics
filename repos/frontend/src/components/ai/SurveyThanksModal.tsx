import Modal from '../common/Modal'

/* 설문 제출 후 감사 팝업 (TSK-4369 · 시안 2장째). ✕·Esc·오버레이·확인 모두 닫기다. */
export default function SurveyThanksModal({ onClose }: { onClose: () => void }) {
  return (
    <Modal
      title="참여해 주셔서 감사합니다"
      variant="sm"
      onClose={onClose}
      footer={
        <button type="button" className="ai-srvy__submit" onClick={onClose} autoFocus>
          확인
        </button>
      }
    >
      <p className="ai-srvy__guide ai-srvy__guide--only">
        소중한 의견이 등록되었습니다.
        <br />더 나은 AI 어시스턴트를 위해 활용하겠습니다.
      </p>
    </Modal>
  )
}
