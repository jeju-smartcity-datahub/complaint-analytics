package jeju.datahub.batchops.service.impl;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.batchops.service.BatchOpsExcnDetailVO;
import jeju.datahub.batchops.service.BatchOpsExcnSearchVO;
import jeju.datahub.batchops.service.BatchOpsExcnVO;
import jeju.datahub.batchops.service.BatchOpsParamVO;
import jeju.datahub.batchops.service.BatchOpsStepVO;
import jeju.datahub.batchops.service.BatchOpsSubRsltVO;

/**
 * 배치 운영 조회 MyBatis 매퍼 (TSK-4337 Step 6) — 읽기 전용. SQL 은 resources/mapper/batchops/BatchOpsMapper.xml.
 *
 * <p>통계(잡별 마지막 실행)·페이징+동적조건·조인이라 MyBatis 다(rule_persistence-jpa-mybatis). 쓰기는 없다 —
 * {@code batch} 스키마의 쓰기 주인은 배치 앱이다.
 */
@Mapper
public interface BatchOpsMapper {

    List<BatchOpsJobRowVO> selectJobRows();

    /** 🔴 ids 는 비어 있으면 안 된다(IN () 문법 오류) — 호출부가 거른다 */
    List<BatchOpsExcnVO> selectExcnsByIds(@Param("ids") Collection<Long> ids);

    List<BatchOpsExcnVO> selectExcnPage(BatchOpsExcnSearchVO search);

    long countExcns(BatchOpsExcnSearchVO search);

    /** 🔴 upIds 는 비어 있으면 안 된다 — 호출부가 거른다 */
    List<BatchOpsSubRsltVO> selectChainSubRslts(@Param("upIds") Collection<Long> upIds);

    BatchOpsExcnDetailVO selectExcnDetail(@Param("jobExecutionId") long jobExecutionId);

    List<BatchOpsParamVO> selectParams(@Param("jobExecutionId") long jobExecutionId);

    List<BatchOpsStepVO> selectSteps(@Param("jobExecutionId") long jobExecutionId);
}
