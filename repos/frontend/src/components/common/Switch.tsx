/*
 * 토글 스위치. 시안의 알림 관리 화면 스위치를 옮겼다.
 * 스타일은 `styles/alerts.css` 의 `.switch` / `.switch__knob` / `.switch.is-on`.
 *
 * 시안은 `<div class="switch">` 였지만 여기서는 **`<button role="switch">`** 로 만든다 —
 * 키보드(Enter/Space)로 조작할 수 있고 스크린리더가 켜짐/꺼짐(`aria-checked`)을 읽어 준다.
 *
 * 상태는 호출부가 갖는다(controlled). 알림 수신자 토글은 `PATCH .../activation` 응답으로
 * 목록을 갱신하는 서버 상태라, 여기서 낙관적 내부 상태를 따로 두지 않는다.
 */

interface SwitchProps {
  /** 켜짐 여부. 서버 값(`useYn === 'Y'`)을 그대로 넘긴다. */
  checked: boolean
  /** 토글 콜백. 인자는 **바뀐 뒤** 값이다. */
  onChange: (next: boolean) => void
  /** 스크린리더용 이름. 예) '김지수 알림 수신 활성화'. */
  label: string
  /** 요청 진행 중 등 조작을 막을 때. */
  disabled?: boolean
}

export default function Switch({ checked, onChange, label, disabled = false }: SwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      className={`switch${checked ? ' is-on' : ''}`}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <span className="switch__knob" />
    </button>
  )
}
