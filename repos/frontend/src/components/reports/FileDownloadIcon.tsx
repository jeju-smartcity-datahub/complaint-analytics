/**
 * 보고서 파일 다운로드 아이콘 — `.tag--file` 버튼(PDF·DOCX) 글자 앞에 붙인다.
 * 시안 번들의 같은 버튼 SVG 를 그대로 옮겼다. 간격은 `.tag` 의 gap 이 진다.
 */
export default function FileDownloadIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" width="15" height="15" aria-hidden="true">
      <path
        d="M12 4v9m0 0 3.5-3.5M12 13 8.5 9.5M5 18h14"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
