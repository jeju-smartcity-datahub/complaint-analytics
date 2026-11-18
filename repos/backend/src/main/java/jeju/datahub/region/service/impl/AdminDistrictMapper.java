package jeju.datahub.region.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.region.service.AdminDistrictFeatureVO;

/**
 * 행정동 경계 GeoJSON 매퍼 (MyBatis). XML 네임스페이스:
 * {@code jeju.datahub.region.service.impl.AdminDistrictMapper}.
 */
@Mapper
public interface AdminDistrictMapper {

    /**
     * 행정동 경계 polygon 목록. {@code ST_AsGeoJSON(bndry_geom)} 결과를 {@code geometryRaw} 로 받아
     * 서비스에서 {@code geometry} 로 변환해 응답한다.
     *
     * @param sggCd 시군구 코드(5자리, optional)
     * @param emdCd 읍면동코드(8자리, optional — sggCd 보다 우선)
     */
    List<AdminDistrictFeatureVO> selectAdminDistricts(@Param("sggCd") String sggCd,
                                                     @Param("emdCd") String emdCd);

    /**
     * 분석레이어 shortage view 의 polygon 매핑용 일괄 조회 (2026-09-08 추가).
     *
     * <p>{@code parking.fn_jenks_shortage} 함수 시그니처 보존 결정 (사용자 확정) 으로 shortage hotspot
     * zone 의 {@code bndryGeoJson} 을 service 단에서 채우기 위한 admdst 매핑. {@code emd_cd IN (:emdCds)}
     * 로 N+1 회피. {@code bndry_geom IS NULL} 인 행은 매퍼 WHERE 에서 제외.
     *
     * <p>{@code emdCds} 가 {@code null} 이거나 빈 리스트인 경우, 매퍼의 {@code <choose>} 가드가
     * {@code AND 1=0} 분기를 타서 빈 결과(0건) 를 반환한다 — 호출자가 별도 null/empty 체크를 할 필요 없다.
     * (이전 버전에서는 {@code IN ()} SQL syntax error 가 났지만, 2026-09-09 PR #28 review 에서 가드 추가.)
     *
     * @param emdCds 대상 zone 의 emd_cd 목록 — null/empty 면 빈 결과를 반환 (호출자 안전)
     */
    List<AdmdstBndryGeoVO> selectBndryGeoJsonByEmdCds(@Param("emdCds") List<String> emdCds);
}