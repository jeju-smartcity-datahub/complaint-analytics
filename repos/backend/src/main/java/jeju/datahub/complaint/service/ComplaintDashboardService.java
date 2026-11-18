package jeju.datahub.complaint.service;

import java.util.List;

/**
 * 민원 대시보드 서비스 인터페이스.
 */
public interface ComplaintDashboardService {

    /** 민원 총건수 조회 (스모크/임시) */
    long countAllComplaints();

    /** KPI 카드 집계(현재/직전 기간 총건수 + 증감률). */
    DashboardKpiVO getKpi(DashboardRequest request);

    /** 읍면동 순위(건수 내림차순 + 유형별 분해 + 전기 대비 증감률). */
    List<RegionRankVO> getRegionRanks(DashboardRequest request);

    /**
     * 추이 차트 집계. 요청 구간을 버킷(HOUR/DAY)으로 접어 유형별 건수를 반환한다.
     * 빈 버킷도 0 으로 채워 x축 칸 수가 항상 일정하다.
     */
    DashboardTrendVO getTrend(DashboardRequest request);

    /**
     * 데이터 현황(민원 최종 발생일시 + 총건수). 대시보드 상단 "최종 업데이트" 표기용이며
     * 필터를 받지 않는 전역 지표다.
     */
    DataStatusVO getDataStatus();
}
