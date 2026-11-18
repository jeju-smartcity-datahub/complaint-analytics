package jeju.datahub.complaint.service;

/**
 * 민원현황(GIS) 핵심 API 서비스 인터페이스.
 *
 * <p>§3 KPI 5필드 응답을 만든다. §7/§8 의 리스트 필드는 추후 메서드 추가로 확장.
 */
public interface ComplaintService {

    /**
     * {@code GET /api/complaints/gis} 응답. §3 KPI 5필드 (lastSyncDt / totalCount /
     * negativityRatio / sentimentDist / enforcementCount / shortageCount) flat 구조.
     *
     * @param request 쿼리스트링 (unit / startDt / endDt / sggCd / emdCd / cvlcptTypeCd)
     */
    ComplaintKpiVO getKpi(ComplaintRequest request);

    /**
     * 조건에 맞는 민원 총건수만 집계한다 (TSK-4311 — 보고서의 전기 민원 건수).
     *
     * <p>{@link #getKpi} 의 {@code totalCount} 와 <b>같은 SQL</b> 이라 필터·집계식이 갈리지 않는다.
     * {@code getKpi} 를 전기 구간으로 다시 부르면 읍면동 집계까지 한 번 더 돌아서 따로 둔다.
     *
     * @param request 쿼리스트링 (unit / startDt / endDt / sggCd / emdCd / cvlcptTypeCd)
     * @return 행 단위 {@code count(*)} — 없으면 0
     */
    long getTotalCount(ComplaintRequest request);
}