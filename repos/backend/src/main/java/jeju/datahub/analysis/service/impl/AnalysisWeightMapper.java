package jeju.datahub.analysis.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.analysis.service.AnalysisTypeWeightVO;
import jeju.datahub.analysis.service.AnalysisWeightHstryVO;
import jeju.datahub.analysis.service.AnalysisWeightVO;

/**
 * 분석 가중치 설정 MyBatis 매퍼. SQL 은 resources/mapper/analysis/AnalysisWeightMapper.xml.
 *
 * <p>분석유형명을 {@code TB_COM_CD} 에서 가져오는 JOIN + 이력 페이징이라 영속성 규칙상 MyBatis 담당이다
 * (rule_persistence-jpa-mybatis.md — 복합 JOIN·페이징은 MyBatis). 쓰기는 JPA 가 맡는다(Step 4).
 * {@code MyBatisConfig} 가 {@code @Mapper} 애노테이션이 붙은 인터페이스만 스캔하므로 애노테이션은 필수다.
 */
@Mapper
public interface AnalysisWeightMapper {

    /** 분석유형 5종 목록 (ANLS_TYPE_CD 중 가중치 표에 행이 있는 것만, sort_seq 순). */
    List<AnalysisTypeWeightVO> selectAnalysisTypes();

    /**
     * 전체 지표 (분석유형·정렬순서 순). <b>서비스가</b> 분석유형별로 묶어 AnalysisTypeWeightVO 로
     * 조립한다 — 프론트는 중첩 응답을 그대로 받는다(Step 5·Step 7).
     */
    List<AnalysisWeightVO> selectWeights();

    /** 분석유형 1건의 지표 (저장 전 현재값 스냅샷용). */
    List<AnalysisWeightVO> selectWeightsByType(@Param("anlsTypeCd") String anlsTypeCd);

    /**
     * 이력 최근 N건 (chg_dt DESC, LIMIT #{size}).
     * SELECT 에 <b>chg_hstry_sn</b> 을 반드시 포함한다 — 프론트 이력 표의 행 key 다(Step 7·Step 9).
     * 조회 컬럼: chg_hstry_sn, anls_type_cd, chg_artcl_nm, chg_cn, chg_rsn, chnrg_nm, chg_dt
     *
     * <p>🆕 <b>2026-09-11 (TSK-4140 Step 7-1) — {@code anlsTypeCd} 선택 필터를 더했다.</b>
     * 🔴 XML 에서 <b>{@code WHERE} 가 {@code LIMIT} 보다 앞</b>이다 — "거른 뒤에 자른다".
     * 뒤집히거나(프론트 필터 포함) 하면 한 분석유형을 {@code size} 번 저장했을 때 다른 유형의
     * 이력이 실제로는 있는데도 0건으로 보인다.
     *
     * @param size        최대 건수
     * @param anlsTypeCd  분석유형코드. {@code null} 이면 전 유형(기존 동작)
     */
    List<AnalysisWeightHstryVO> selectHistories(@Param("size") int size,
                                                @Param("anlsTypeCd") String anlsTypeCd);
}
