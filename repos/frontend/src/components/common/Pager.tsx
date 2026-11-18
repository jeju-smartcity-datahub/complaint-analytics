/*
 * 페이지네이션. 시안(https://innopamsolution.github.io/jeju-datahub/)의 `O9` 를 옮겼다.
 * 보고서(TSK-4026)·알림(TSK-4028) 목록이 함께 쓴다.
 *
 * ⚠️ **0-base ↔ 1-base 변환은 이 컴포넌트 한 곳에서만 한다** (TSK-4026 D8).
 * 백엔드 `PageResponse.page` 는 0-base 이고 화면에 찍히는 번호는 1-base 다. 호출부가 저마다
 * ±1 을 하면 오프바이원이 화면마다 흩어지므로, props 는 전부 **서버와 같은 0-base** 로 받고
 * 표시용 1-base 는 이 안에서만 만든다. 호출부는 받은 `page` 를 그대로 API 에 넘기면 된다.
 */

interface PagerProps {
  /** 필터 전체 건수 (`PageResponse.totalElements`). */
  total: number
  /** 현재 페이지 — **0-base** (`PageResponse.page` 를 그대로 넘긴다). */
  page: number
  /** 페이지 변경 콜백. 인자도 **0-base** 라 API 요청의 `page` 파라미터로 바로 쓴다. */
  setPage: (page: number) => void
  /** 페이지 크기 (`PageResponse.size` 와 같아야 한다). 기본 10. */
  perPage?: number
}

/** 총 페이지가 1 이하면 아무것도 그리지 않는다 (시안과 동일 — `null` 반환). */
export default function Pager({ total, page, setPage, perPage = 10 }: PagerProps) {
  const totalPages = Math.ceil(total / perPage)
  if (totalPages <= 1) return null

  // 표시용 1-base 번호. 이 배열 밖으로는 0-base 만 나간다.
  const displayNumbers = Array.from({ length: totalPages }, (_, i) => i + 1)

  return (
    <div className="pager">
      <button
        type="button"
        className="pager__btn pager__btn--nav"
        aria-label="이전 페이지"
        disabled={page === 0}
        onClick={() => setPage(page - 1)}
      >
        ‹
      </button>

      {displayNumbers.map((n) => (
        <button
          key={n}
          type="button"
          className={`pager__btn${n - 1 === page ? ' is-active' : ''}`}
          aria-current={n - 1 === page ? 'page' : undefined}
          onClick={() => setPage(n - 1)}
        >
          {n}
        </button>
      ))}

      <button
        type="button"
        className="pager__btn pager__btn--nav"
        aria-label="다음 페이지"
        disabled={page === totalPages - 1}
        onClick={() => setPage(page + 1)}
      >
        ›
      </button>
    </div>
  )
}
