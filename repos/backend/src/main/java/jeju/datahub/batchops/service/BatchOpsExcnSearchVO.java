package jeju.datahub.batchops.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 실행 이력 목록 검색 조건 — 검증은 컨트롤러가 끝낸 값만 담는다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BatchOpsExcnSearchVO {

    /** Job 이름 (정확 일치 · null 이면 전체) */
    private String jobNm;

    /** 실행 상태 (화이트리스트 통과 값 · null 이면 전체) */
    private String status;

    /** 페이지 크기 */
    private int size;

    /** OFFSET (page * size) */
    private long offset;
}
