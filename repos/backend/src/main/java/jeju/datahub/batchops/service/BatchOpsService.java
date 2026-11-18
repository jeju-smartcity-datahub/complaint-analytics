package jeju.datahub.batchops.service;

import jeju.datahub.common.PageResponse;

/**
 * 배치 운영 조회 서비스 (TSK-4337 Step 6 · D2) — 백엔드가 {@code batch} 스키마를 <b>읽기 전용</b>으로 직접 읽는다.
 *
 * <p>배치 앱이 죽어 있어도 "왜 죽었는지" 볼 이력은 보여야 해서 조회는 배치 API 를 거치지 않는다.
 */
public interface BatchOpsService {

    /** 잡별 마지막 실행 · 마지막 성공 · 스케줄 + 배치 카탈로그 병합(못 받으면 DB 만 · {@code catalogAvailable=false}). */
    BatchOpsJobsVO selectJobs();

    /** 실행 이력 페이지 (최신순 = job_execution_id 내림차순). */
    PageResponse<BatchOpsExcnVO> selectExecutions(BatchOpsExcnSearchVO search, int page);

    /** 실행 상세. 없으면 404. */
    BatchOpsExcnDetailVO selectExecution(long jobExecutionId);
}
