import Icon from '../Icon'

/*
 * 페이지 상단 breadcrumb. 시안(https://innopamsolution.github.io/jeju-datahub/)의 `G7` 을 그대로 옮겼다.
 * 보고서(TSK-4026)·알림(TSK-4028) 화면이 함께 쓴다.
 *
 * 시안과 마찬가지로 **2단 고정**이다 (그룹 > 현재 페이지). 링크가 아니라 위치 표시용 텍스트라
 * <Link> 를 쓰지 않는다.
 */

interface BreadcrumbProps {
  /** 상위 그룹명. 예) '보고서·알림' */
  group: string
  /** 현재 페이지명. 예) '보고서 관리' */
  page: string
}

export default function Breadcrumb({ group, page }: BreadcrumbProps) {
  return (
    <p className="page-crumb">
      <span>{group}</span>
      <Icon name="chevron-right" size={12} />
      <span className="page-crumb__cur">{page}</span>
    </p>
  )
}
