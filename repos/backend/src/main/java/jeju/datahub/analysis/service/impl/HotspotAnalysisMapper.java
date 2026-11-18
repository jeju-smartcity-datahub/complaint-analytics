package jeju.datahub.analysis.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.analysis.service.HotspotWorkbookConstVO;
import jeju.datahub.analysis.service.HotspotWorkbookZoneVO;
import jeju.datahub.analysis.service.HotspotZoneVO;

/**
 * 집중구역(핫스팟) 분석 MyBatis 매퍼. SQL 은 resources/mapper/analysis/HotspotAnalysisMapper.xml.
 *
 * <p>PostGIS {@code ST_ClusterDBSCAN} 군집화 + 군집별 집계 + 대표지명까지 <b>한 번의 조회</b>로 끝낸다.
 * 복합 JOIN·통계·공간연산이라 JPA 가 아니라 MyBatis 다(rule_persistence-jpa-mybatis).
 */
@Mapper
public interface HotspotAnalysisMapper {

    /**
     * 조건에 맞는 상위 N 개 집중구역. 종합점수 내림차순.
     *
     * <p>구역명({@code zoneNm})·출처({@code zoneNmSrc})는 <b>①단속주소 단계까지만</b> 채워진다.
     * 반경 안에 단속주소가 없으면 둘 다 {@code null} 이며, 그 상태가 "폴백이 필요한 구역"의 표시다
     * (VWorld POI → 역지오코딩 → {@code {읍면동명} 일대} 폴백은 서비스가 붙인다 — D6).
     *
     * <p>{@code grade}·{@code gradeNm} 도 매퍼가 채우지 않는다 — 서비스가 종합점수로 판정한다(D5).
     *
     * @param param 지역·기간·DBSCAN 파라미터·가중치·LIMIT
     * @return 순위 오름차순 집중구역 목록. 군집이 하나도 안 생기면 빈 목록
     */
    List<HotspotZoneVO> selectHotspotZones(HotspotQueryParam param);

    /**
     * 엑셀 재계산용 후보 구역 (TSK-4338 D3) — 민원 가중치 0~100%(정수) ∪ {@code param.cvlcptWgvl} 중
     * 어느 하나에서든 주의 이상에 드는 군집의 합집합. {@code cid} 오름차순.
     *
     * <p>{@link #selectHotspotZones} 와 같은 산식 조각({@code hotspotBaseCtes})을 쓴다.
     * 🔴 {@code param.limit} 은 무시한다. 구역명은 ①단속주소 단계까지만 채워진다(나머지는 서비스 폴백).
     *
     * @param param {@link #selectHotspotZones} 와 같은 파라미터 + 적용 민원 가중치(%)
     * @return 후보 구역. 군집이 없으면 빈 목록
     */
    List<HotspotWorkbookZoneVO> selectHotspotWorkbookZones(HotspotQueryParam param);

    /**
     * 엑셀 재계산 상수 (TSK-4338 D5) — 전체 군집의 지표별 최댓값 + 가중치별 0점 제외 군집 수.
     *
     * <p>가중치 행은 항상 101개(적용 민원 가중치가 비정수면 102개)다 — 군집이 없으면 최댓값·수 모두 0.
     *
     * @param param {@link #selectHotspotWorkbookZones} 와 같은 파라미터
     * @return 상수 1건(null 이 아니다)
     */
    HotspotWorkbookConstVO selectHotspotWorkbookConst(HotspotQueryParam param);

    /**
     * 원천(민원·단속)이 실제로 보유한 데이터 구간(min/max). 0건 안내에 쓴다(D11).
     *
     * <p>원천이 비어 있으면 두 값 모두 {@code null} 인 VO 가 돌아온다.
     */
    DataRangeVO selectDataRange();
}
