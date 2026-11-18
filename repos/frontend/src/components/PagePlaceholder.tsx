import type { ReactElement } from 'react'

/**
 * 아직 구현되지 않은 화면의 자리표시자.
 * 화면 내용을 채우면서 이 컴포넌트 사용을 걷어낸다.
 *
 * <p>🔴 한 페이지 안에 heading 레벨이 같은 제목이 두 개면 스크린리더/SEO 가 부서진다.
 *    페이지 상단 topbar 가 이미 {@code <h1 className="page-title">} 를 그리므로 본문 자리표시의
 *    제목은 {@code <h2>} 이상으로 깎아 주는 게 안전하다. 호출부에서 {@code headingLevel} 을
 *    명시하지 않으면 기본 {@code 1} (= 기존 동작 유지). 이 prop 은 본문이 h1 과 충돌할 때만
 *    올리면 된다.
 */
export interface PagePlaceholderProps {
  title: string
  /** 본문 자리표시 제목의 heading 레벨. 기본 1 (기존 동작). 페이지 topbar 가 h1 을 이미 그릴 때 2 로. */
  headingLevel?: 1 | 2 | 3 | 4 | 5 | 6
}

export default function PagePlaceholder({
  title,
  headingLevel = 1,
}: PagePlaceholderProps): ReactElement {
  const HeadingTag = `h${headingLevel}` as const
  return (
    <div className="placeholder">
      <HeadingTag className="placeholder__title">{title}</HeadingTag>
      <p className="placeholder__desc">준비 중인 화면입니다.</p>
    </div>
  )
}
