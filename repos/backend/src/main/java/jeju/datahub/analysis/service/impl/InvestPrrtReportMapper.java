package jeju.datahub.analysis.service.impl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 투자우선순위 보고서 스냅샷 조회 매퍼. (TSK-4036 Step 7)
 *
 * <p>쓰기(실행·결과·보고서 3행)는 JPA 가 하고, 여기는 <b>세 표를 엮는 조회 1개</b>만 둔다
 * ({@code rule_persistence-jpa-mybatis.md} — 다중 JOIN 조회 = MyBatis). 보고서 헤더(이름·지역명·기간·생성일시)는
 * 주지 않는다 — {@code ReportMapper.selectReportById} 를 그대로 재사용한다(지역 3단 CASE 를 복제하지 않는다).
 */
@Mapper
public interface InvestPrrtReportMapper {

    /**
     * 보고서 1건의 실행 메타 + 결과 원문.
     *
     * @return 보고서가 없으면 {@code null}. 실행 미연결이면 {@code anlsTypeCd} 가, 결과 미저장이면
     *         {@code rsltJson} 이 null 이다
     */
    InvestPrrtSnapshotVO selectInvestSnapshot(@Param("rptpId") Long rptpId);
}
