package jeju.datahub.lifezone.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.analysis.service.DataRangeVO;

/**
 * 생활권 시뮬레이션 격자 집계 MyBatis 매퍼. SQL 은 resources/mapper/lifezone/LifezoneAnalysisMapper.xml.
 *
 * <p>격자 집계 → 경계도형 → 민원유형 상위 2종 → 행정동까지 <b>한 번의 조회</b>로 끝낸다.
 * 복합 JOIN·통계·공간연산이라 JPA 가 아니라 MyBatis 다.
 */
@Mapper
public interface LifezoneAnalysisMapper {

    /**
     * 조건에 맞는 격자 셀 전량. 분석값 내림차순({@code anls_vl DESC, grid_cd}).
     *
     * <p>🔴 <b>값&gt;0 격자를 전부 낸다</b> — {@code HAVING} 으로 자르지 않는다. 순위 목록은
     * 서비스가 이 결과에서 심각·경고·주의 셀만(limit 지정 시 그 값까지) 골라 만든다 — 셀 목록과 순위 목록은 다른 집합이다.
     *
     * <p>등급({@code grade})·구역명({@code cntrPoiNm})은 매퍼가 채우지 않는다 — 서비스가
     * 분석값 분포로 등급을 판정하고, 구역명은 격자 중심({@code ctrLat}·{@code ctrLot})으로
     * POI 를 조회해 붙인다.
     *
     * @param param 격자 크기·기간(반열림)·지역 필터
     * @return 분석값 내림차순 격자 목록. 조건에 맞는 민원이 없으면 빈 목록
     */
    List<LifezoneCellRow> selectCells(LifezoneQueryParam param);

    /**
     * 원천(민원)이 실제로 보유한 데이터 구간(min/max). 0건 안내에 쓴다.
     *
     * <p>생활권은 민원만 보므로 집중구역과 달리 단속을 섞지 않는다. 원천이 비어 있으면 두 값 모두
     * {@code null} 인 VO 가 돌아온다.
     */
    DataRangeVO selectDataRange();

    /**
     * 캐시 키 목록 → 구역명 캐시 이름 일괄 조회 (보고서 셀 복원용).
     *
     * <p>키는 {@code ZoneNameResolver.key} 와 같은 {@code setScale(5, HALF_UP)} 값을 쉼표로 이은 문자열이고,
     * 두 목록의 i 번째끼리 한 쌍이다(SQL 이 {@code unnest} 로 짝지어 PK 조인 1회 — IN 목록을 만들지 않는다).
     * 출처가 {@code POI}·{@code REVERSE_GEOCODE} 인 행만 돌아온다. 빈 목록이면 호출하지 않는다(서비스 조기 반환).
     *
     * @param latKeys 위도 키 CSV (예: {@code 33.49960,33.51230})
     * @param lotKeys 경도 키 CSV — {@code latKeys} 와 원소 수가 같아야 한다
     */
    List<CachedZoneNmRow> selectCachedZoneNames(@Param("latKeys") String latKeys,
                                                @Param("lotKeys") String lotKeys);
}
