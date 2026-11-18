package jeju.datahub.batchops.service.impl;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jeju.datahub.batchops.service.BatchOpsParamSpecVO;
import jeju.datahub.batchops.service.BatchOpsSchdlVO;
import jeju.datahub.batchops.service.BatchOpsStepSpecVO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 배치 카탈로그 {@code GET /api/batch/ops/jobs} 원소 한 줄 — 수신 전용 (TSK-4337 Step 7).
 *
 * <p>필드명은 배치 {@code BatchOpsService#jobs()} 가 만드는 맵 키와 같다. 응답으로 내보내지 않고
 * {@code BatchOpsServiceImpl} 이 {@code BatchOpsJobVO} 로 옮겨 담는다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchOpsCatalogJob {

    private String jobNm;
    private String jobKorNm;
    private String description;
    private String countLabel;
    private List<BatchOpsParamSpecVO> params = List.of();
    private Boolean schedulable;
    /** 스케줄 대상이 아니면 null. 필드명이 {@link BatchOpsSchdlVO} 와 같아 그대로 받는다 */
    private BatchOpsSchdlVO schedule;
    private String chainNext;
    private String chainCondition;
    private List<BatchOpsStepSpecVO> steps = List.of();
    private List<Long> runningJobExecutionIds = List.of();
}
