package jeju.datahub.region.web;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.region.service.AdminDistrictFeatureCollection;
import jeju.datahub.region.service.AdminDistrictRequest;
import jeju.datahub.region.service.AdminDistrictService;
import lombok.RequiredArgsConstructor;

/**
 * 행정동 경계 GeoJSON REST 컨트롤러.
 *
 * < 가이드 §0 / §4 결정: {@code GET /api/admin-districts} 응답 = GeoJSON FeatureCollection.
 * GIS 화면 §4 의 행정동 경계 토글 전용 — 기존 {@code /api/regions} (시군구→읍면동 트리) 와
 * <b>역할 분리</b>.
 *
 * * path prefix 없음 컨벤션 (가이드 §1 결정).
 * * 가이드 §4 결정: {@code unit} / {@code cvlcptTypeCd} 무관 — 행정동 경계는 정적 자산.
 * * 가이드 §4 결정: 클라이언트는 {@code Accept-Encoding: gzip} 필수 (74개 × MultiPolygon = 수 MB).
 */
@Tag(name = "행정동 경계",
        description = "GeoJSON FeatureCollection 응답 — GIS 화면 §4 의 행정동 경계 토글 전용. "
                + "기존 /api/regions (시군구→읍면동 트리) 와 역할 분리.")
@RestController
@RequestMapping("/api/admin-districts")
@RequiredArgsConstructor
public class AdminDistrictController {

    private final AdminDistrictService adminDistrictService;

    /**
     * 가이드 §4 결정: sggCd / emdCd 행정구역 필터만 받음. 행정동 경계 polygon 의 GeoJSON
     * 응답 — PostGIS {@code ST_AsGeoJSON(bndry_geom)} 직렬화 결과를 features[] 에 담아 내린다.
     */
    @Operation(summary = "행정동 경계 GeoJSON 조회",
            description = "GeoJSON FeatureCollection (RFC 7946). sggCd 또는 emdCd 로 행정구역 필터. "
                        + "unit/cvlcptTypeCd 무관 (정적 자산). 응답 크기가 수 MB 가능 — "
                        + "Accept-Encoding: gzip 필수, 클라이언트 캐싱 권장.")
    @GetMapping
    public AdminDistrictFeatureCollection adminDistricts(@ParameterObject AdminDistrictRequest request) {
        return adminDistrictService.findAll(request);
    }
}