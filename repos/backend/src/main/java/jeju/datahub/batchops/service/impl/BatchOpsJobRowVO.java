package jeju.datahub.batchops.service.impl;

import java.time.LocalDateTime;

import jeju.datahub.batchops.service.BatchOpsSchdlVO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 잡 목록 1차 조회 행 (매퍼 내부용 — 응답으로 내리지 않는다). 서비스가 {@code lastExcnId} 로 요약을 붙여
 * {@link jeju.datahub.batchops.service.BatchOpsJobVO} 를 만든다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BatchOpsJobRowVO {

    private String jobNm;

    /** 마지막 실행 ID (실행 없는 인스턴스뿐이면 null) */
    private Long lastExcnId;

    /** 마지막 성공(COMPLETED) 실행 ID */
    private Long lastSuccessExcnId;

    /** 마지막 성공 실행의 종료 시각 */
    private LocalDateTime lastSuccessDt;

    /** 스케줄 (표에 행이 없으면 null) */
    private BatchOpsSchdlVO schedule;
}
