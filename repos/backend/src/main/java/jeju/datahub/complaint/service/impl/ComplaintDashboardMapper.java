package jeju.datahub.complaint.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import jeju.datahub.complaint.service.DashboardKpiVO;
import jeju.datahub.complaint.service.DataStatusVO;
import jeju.datahub.complaint.service.RegionRankVO;
import jeju.datahub.complaint.service.RiskLevelCountVO;
import jeju.datahub.complaint.service.TypeCountVO;

/**
 * 민원 대시보드 MyBatis 매퍼. SQL 은 resources/mapper/complaint/ComplaintDashboardMapper.xml.
 */
@Mapper
public interface ComplaintDashboardMapper {

    /** 민원 총건수 (스모크/임시) */
    long countAll();

    /**
     * KPI 집계: 현재 구간 총건수(total)와 직전 구간 총건수(prevTotal)를 한 번에 조회.
     * deltaPct 는 서비스에서 계산한다.
     */
    DashboardKpiVO selectKpi(DashboardQueryParam param);

    /**
     * 단속 건수 KPI (현재 구간). 민원과 <b>모수가 다르다</b> — parking.TB_CRDN_DSCTN 이다.
     * typeCd·riskLvlCd 는 적용되지 않는다(코드체계가 다르거나 컬럼이 없다).
     */
    long selectCrdnCount(DashboardQueryParam param);

    /**
     * 공영주차장 현황 KPI: 선택 지역의 활성 주차장 수 (parking.TB_PBLPKLT). (2026-10-01)
     *
     * <p>🔴 <b>기간을 타지 않는다</b> — 같은 {@link DashboardQueryParam} 을 받지만 SQL 이
     * {@code curStart}/{@code curEnd} 를 참조하지 않는다. "그 시점에 몇 개 있나" 를 세는
     * 시점 현황이라 조회단위를 바꿔도 값이 변하지 않는 것이 정상이다.
     * {@code sggCd} 만 적용되며, 소멸분({@code extsh_dt IS NOT NULL})은 제외한다.
     */
    long selectPkltCount(DashboardQueryParam param);

    /**
     * 읍면동별 순위: 현재 구간 건수(count)·직전 구간 건수(prevCount)를 읍면동별로 집계하고
     * TB_ADMDST 를 조인해 읍면동명을 얻는다. count 내림차순. breakdown·deltaPct 는 서비스에서 주입.
     */
    List<RegionRankVO> selectRegionRanks(DashboardQueryParam param);

    /**
     * 읍면동 × 유형 분해(현재 구간): (emdCd, typeCd, count) 평면 행. 서비스가 emdCd 로 그룹핑.
     */
    List<TypeCountVO> selectTypeBreakdown(DashboardQueryParam param);

    /**
     * 위험단계 알림 KPI: 현재 구간의 단계별 알림 건수(TB_RISK_STP_NOTI_HSTRY).
     * 민원(TB_CVLCPT) 집계와 모수가 다르며 typeCd 필터가 걸리지 않는다.
     * 해당 단계 알림이 0건이면 그 단계는 결과에 나오지 않는다.
     */
    List<RiskLevelCountVO> selectRiskAlertCounts(DashboardQueryParam param);

    /**
     * 추이(시간대) 집계: {@code (bucketKey="00".."23", typeCd, count)} 평면 행.
     * 구간 내 같은 시각끼리 합산되며, 건수 0 인 시간대는 <b>행이 나오지 않는다</b>(서비스가 채운다).
     */
    List<TrendCountVO> selectHourlyTrend(DashboardQueryParam param);

    /**
     * 추이(일자) 집계: {@code (bucketKey="yyyy-MM-dd", typeCd, count)} 평면 행.
     * 건수 0 인 날짜는 <b>행이 나오지 않는다</b>(서비스가 채운다).
     */
    List<TrendCountVO> selectDailyTrend(DashboardQueryParam param);

    /**
     * 데이터 현황: 민원 최종 발생일시(MAX(ocrn_dt))와 총건수.
     * <b>파라미터를 받지 않는다</b> — 대시보드 상단 "최종 업데이트" 표기용 전역 지표라
     * 기간·유형 필터가 걸리지 않는다. 집계 모수만 대시보드 조회 정책(filters)과 맞춘다.
     */
    DataStatusVO selectDataStatus();
}
