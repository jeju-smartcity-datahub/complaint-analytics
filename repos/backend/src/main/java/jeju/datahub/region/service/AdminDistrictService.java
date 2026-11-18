package jeju.datahub.region.service;

/**
 * 행정동 경계 GeoJSON 조회 서비스 인터페이스.
 *
 * < {@code GET /api/admin-districts} 응답은 GeoJSON FeatureCollection.
 * 기존 {@code /api/regions} (시군구→읍면동 트리) 와 <b>역할 분리</b> — 본 API 는 polygon 만,
 * 트리는 트리 API 가 담당.
 *
 * < 사용자가 2026-08-31 follow-up 으로 분리 의도 확정.
 */
public interface AdminDistrictService {

    /**
     * 행정동 경계 GeoJSON FeatureCollection 조회.
     *
     * @param req sggCd / emdCd (optional)
     * @return FeatureCollection. 0건이어도 빈 features[] 로 내린다.
     */
    AdminDistrictFeatureCollection findAll(AdminDistrictRequest req);
}